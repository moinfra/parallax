// filename: src/main/scala/parallax/issue/RenamePlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob.ROBService
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
import parallax.components.rename.BusyTableService

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin 
    with LockedImpl {
  val enableLog = false
  val early_setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val busyTableService = getService[BusyTableService] // 获取服务

    issuePpl.retain()
    busyTableService.retain() // 保持服务

    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val rat = getService[RatControlService]
    val freeList = getService[FreeListControlService]
    
    val s1_rename = issuePpl.pipeline.s1_rename
    s1_rename(issuePpl.signals.DECODED_UOPS)
    s1_rename(issuePpl.signals.RENAMED_UOPS)
  }

  // --- MODIFICATION START: Simplified logic for Rename-only stage ---
  val logic = create late new Area {
    lock.await()
    val issuePpl = early_setup.issuePpl
    val s1_rename = early_setup.s1_rename
    val issueSignals = issuePpl.signals
    
    val renameUnit = early_setup.renameUnit
    val rat = early_setup.rat
    val freeList = early_setup.freeList
    val setBusyPorts = early_setup.busyTableService.newSetPort() // 获取set端口

    // --- 1. Connect data paths ---
    val decodedUopsIn = s1_rename(issueSignals.DECODED_UOPS)
    renameUnit.io.decodedUopsIn := decodedUopsIn
    
    for(i <- 0 until pipelineConfig.renameWidth) {
      renameUnit.io.physRegsIn(i) := freeList.getAllocatePorts()(i).physReg
    }
    
    rat.getReadPorts() <> renameUnit.io.ratReadPorts
    
    // === RAT Write Port Arbitration ===
    // Rename requests RAT write port (lower priority than commit)
    val renameWriteReqs = Vec(Bool(), pipelineConfig.renameWidth)
    val renameWriteData = Vec(RatWritePort(ratConfig), pipelineConfig.renameWidth)
    
    for(i <- 0 until pipelineConfig.renameWidth) {
      val ruPort = renameUnit.io.ratWritePorts(i)
      renameWriteReqs(i) := ruPort.wen && s1_rename.isFiring
      renameWriteData(i).wen := ruPort.wen && s1_rename.isFiring
      renameWriteData(i).archReg := ruPort.archReg
      renameWriteData(i).physReg := ruPort.physReg
    }
    
    // Note: Actual RAT write port assignment will be handled by arbitration logic
    // For now, directly connect (will be overridden by CommitPlugin arbitration)
    rat.getWritePorts.zip(renameUnit.io.ratWritePorts).foreach { case (ratPort, ruPort) =>
      ratPort.wen     := ruPort.wen && s1_rename.isFiring
      ratPort.archReg := ruPort.archReg
      ratPort.physReg := ruPort.physReg
    }

    // --- 2. Define HALT condition (only FreeList stall remains) ---
    val willNeedPhysRegs = decodedUopsIn(0).isValid && decodedUopsIn(0).writeArchDestEn
    val notEnoughPhysRegs = freeList.getNumFreeRegs < Mux(willNeedPhysRegs, U(1), U(0))
    
    val shouldHalt = notEnoughPhysRegs
    s1_rename.haltWhen(shouldHalt)
    
    // 保留物理寄存器不足的调试日志
    when(s1_rename.isValid && notEnoughPhysRegs) {
      report(L"[RENAME] FREELIST STALL: Not enough physical registers")
    }

    // --- 3. Drive state-changing requests only when firing ---
    val fire = s1_rename.isFiring
    for(i <- 0 until pipelineConfig.renameWidth) {
      val needsReg = renameUnit.io.numPhysRegsRequired > i
      freeList.getAllocatePorts()(i).enable := fire && needsReg

      // *** 驱动 BusyTable set 端口 ***
      val uopOut = renameUnit.io.renamedUopsOut(i)
      when(fire && uopOut.decoded.isValid && uopOut.rename.allocatesPhysDest) {
          setBusyPorts(i).valid   := True
          setBusyPorts(i).payload := uopOut.rename.physDest.idx
      } otherwise {
          setBusyPorts(i).valid   := False
          setBusyPorts(i).payload.assignDontCare()
      }
    }

    if(enableLog) report(L"DEBUG: s1_rename.isFiring=${s1_rename.isFiring}, decodedUopsIn(0).isValid=${decodedUopsIn(0).isValid}")
    if(enableLog) report(L"DEBUG: s1_rename.isReady=${s1_rename.isReady}, s1_rename.isValid=${s1_rename.isValid}, willNeedPhysRegs=${willNeedPhysRegs}, notEnoughPhysRegs=${notEnoughPhysRegs}")

    // --- 4. Connect outputs ---
    // The output RenamedUop will have a garbage robPtr, which is fine.
    // It will be overwritten in the next stage.
    s1_rename(issueSignals.RENAMED_UOPS) := renameUnit.io.renamedUopsOut

    // +++ FLUSH LOGIC INSERTION START +++
    val flush = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        val doHardRedirect = hr.getFlushListeningPort()
        when(doHardRedirect) {
          s1_rename.flushIt()
          report(L"DecodePlugin (s0_decode): Flushing pipeline due to hard redirect")
        }
      })
    }
    // +++ FLUSH LOGIC INSERTION END +++

    val branchLimit = new Area {
      getServiceOption[BranchTrackerService].foreach(bt => {
        report(L"s1_rename.isFiring = ${s1_rename.isFiring}, decodedUopsIn(0).isValid = ${decodedUopsIn(0).isValid}, decodedUopsIn(0).isBranchOrJump = ${decodedUopsIn(0).isBranchOrJump}")
        when(s1_rename.isFiring && decodedUopsIn(0).isValid && decodedUopsIn(0).isBranchOrJump) {
          bt.doIncrement() // Rename 时分配了资源，因此+1，Commit 时释放资源，因此-1
        } otherwise {
          report(L"Not a branch or jump instruction")
        }
        s1_rename.haltWhen(bt.isExceedLimit(Mux(decodedUopsIn(0).isValid && decodedUopsIn(0).isBranchOrJump, U(1), U(0)))) // 超出分支限制时暂停重命名阶段
      })
      if(getServiceOption[BranchTrackerService].isEmpty) {
        println("WARNING: BranchTrackerService is not available")
      }
    }

    issuePpl.release()
    early_setup.busyTableService.release() // 释放服务
  }
  // --- MODIFICATION END --
}
