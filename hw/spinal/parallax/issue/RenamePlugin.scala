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
import parallax.utilities.ParallaxSim.notice

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SimpleFreeListConfig
) extends Plugin
    with LockedImpl {

  val enableLog = false

  // --- 服务和流水线阶段的早期设置 ---
  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val busyTableService = getService[BusyTableService]
    val rat = getService[RatControlService]
    val freeList = getService[FreeListControlService]
    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val btSetBusyPorts = busyTableService.newSetPort()

    issuePpl.retain(); busyTableService.retain();

    val s1_rename = issuePpl.pipeline.s1_rename
    val s2_rob_alloc = issuePpl.pipeline.s2_rob_alloc
    val signals = issuePpl.signals
  }
  val doGlobalFlush = Bool()

  // --- 核心逻辑实现 ---
  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val s1_rename = setup.s1_rename
    val s2_rob_alloc = setup.s2_rob_alloc
    val signals = setup.signals
    val rat = setup.rat
    val freeList = setup.freeList
    val busyTableService = setup.busyTableService
    // 获取在 setup 中创建的 renameUnit 实例
    val renameUnit = setup.renameUnit
    val needRetryReg = RegInit(False)
    // val stalledUopReg = Reg(DecodedUop(pipelineConfig))


    // ================================================================
    // ==           S1: 乐观请求与数据缓冲阶段                      ==
    // ================================================================
    val s1_logic = new Area {
      val decodedUops = s1_rename(signals.DECODED_UOPS)
      val needsPhysRegVec = Vec(decodedUops.map(_.writeArchDestEn))
      val flAllocPort = freeList.getAllocatePorts()

      // 乐观地发出 FreeList 分配请求
      for (i <- 0 until pipelineConfig.renameWidth) {
        flAllocPort(i).enable := s1_rename.isValid && (needsPhysRegVec(i) || needRetryReg)
        notice(L"flAllocPort.enable(${i}) := ${s1_rename.isValid} && ${needsPhysRegVec(i)}")
      }

      // 将请求掩码传递到 S2
      s1_rename(signals.NEEDS_PHYS_REG) := needsPhysRegVec

      when(needRetryReg) {
        s1_rename.haltIt() // 避免 s1 的指令在 s2 完成前流入 s2
      }
    }

    // ================================================================
    // ==           S2: 验证、重命名与写回阶段                     ==
    // ================================================================
    val s2_logic = new Area {
      val decodedUopsInS2 = s2_rob_alloc(signals.DECODED_UOPS)
      val lastCycleNeedsPhysReg = s2_rob_alloc(signals.NEEDS_PHYS_REG)
      val flAllocPort = freeList.getAllocatePorts()
      val flFreePort = freeList.getFreePorts()

      val renameUnitInputUops = Vec(HardType(DecodedUop(pipelineConfig)), pipelineConfig.renameWidth)
      val renameUnitPhysRegs = Vec(HardType(UInt(ratConfig.physRegIdxWidth)), pipelineConfig.renameWidth)

      // --- 将 RenameUnit 的连接放到一个独立的 Area 中，方便管理 ---
      val renameArea = new Area {
        renameUnit.io.decodedUopsIn := renameUnitInputUops
        renameUnit.io.flush := doGlobalFlush || RegNext(doGlobalFlush)
        rat.getReadPorts() <> renameUnit.io.ratReadPorts
        renameUnit.io.physRegsIn := renameUnitPhysRegs
      }

      // --- 默认情况下，RenameUnit 的输入是无效的 ---
      renameUnitInputUops.foreach(_.setDefault()) // 或者 _.isValid := False
      renameUnitPhysRegs.foreach(_ := U(0))

      val allocationOk = (0 until pipelineConfig.renameWidth).map { i =>
        !lastCycleNeedsPhysReg(i) || flAllocPort(i).success
      }.andR

      // 为流水线载荷提供默认值
      s2_rob_alloc(signals.RENAMED_UOPS).foreach(_.setDefault())

      // 为所有状态更新端口提供默认值
      rat.getWritePorts().foreach { port =>
        port.wen := False
        port.archReg.assignDontCare() // 或者 assign 0
        port.physReg.assignDontCare() // 或者 assign 0
      }
      setup.btSetBusyPorts.foreach { port =>
        port.valid := False
        port.payload.assignDontCare() // 或者 assign 0
      }

        flFreePort(1).enable := False
        flFreePort(1).physReg := U(0)

      // // --- 归还逻辑 --- 只能在提交时归还。
      // for (i <- 0 until pipelineConfig.renameWidth) {
      //   // 检查 S1 是否为这个槽位成功分配了资源
      //   val allocatedSuccessfully = flAllocPort(i).success
      //   // 检查这个槽位的指令是否真的需要这个资源
      //   val isNotNeeded = !lastCycleNeedsPhysReg(i)

      //   // 当 "成功分配了" 但 "实际上不需要" 时，立即归还
      //   when(s2_rob_alloc.isValid && allocatedSuccessfully && isNotNeeded) {
      //     flFreePort(1).enable := True
      //     flFreePort(1).physReg := flAllocPort(i).physReg
          
      //     if(enableLog) notice(L"[RegRes] S2: Returning unneeded preg ${flAllocPort(i).physReg} from slot ${i}")
      //   }
      // }

      // --- 只有当 S2 阶段发射时，才驱动 RenameUnit ---
      when(s2_rob_alloc.isFiring) {
        assert(allocationOk, "ASSERTION FAILED: Firing S2 stage with failed FreeList allocation!")
        needRetryReg := False

        renameUnitInputUops := decodedUopsInS2
        for (i <- 0 until pipelineConfig.renameWidth) {
          renameUnitPhysRegs(i) := flAllocPort(i).physReg
        }

        // 3. 将 RenameUnit 的结果放入 Stageable
        s2_rob_alloc(signals.RENAMED_UOPS) := renameUnit.io.renamedUopsOut

        // 4. 写回状态表 (RAT 和 BusyTable)
        rat.getWritePorts().zip(renameUnit.io.renamedUopsOut).foreach { case (ratPort, uop) =>
          ratPort.wen := uop.rename.allocatesPhysDest && !doGlobalFlush && !RegNext(doGlobalFlush)
          ratPort.archReg := uop.decoded.archDest.idx
          ratPort.physReg := uop.rename.physDest.idx
        }

        for (i <- 0 until pipelineConfig.renameWidth) {
          val uopOut = renameUnit.io.renamedUopsOut(i)
          setup.btSetBusyPorts(i).valid := uopOut.rename.allocatesPhysDest && !doGlobalFlush && !RegNext(doGlobalFlush)
          setup.btSetBusyPorts(i).payload := uopOut.rename.physDest.idx
        }

      }

      when(s2_rob_alloc.isValid && !allocationOk) {
        notice(
          L"[RegRes] S2: Failed to allocate physical registers for uops: ${s2_rob_alloc(signals.DECODED_UOPS).map(_.tinyDump())} because s2.isValid=${s2_rob_alloc.isValid} and allocationOk=${allocationOk}"
        )
        needRetryReg := True
        s2_rob_alloc.haltIt()
      }
    }

    // Flush 逻辑，与您原来的一致
    val flushLogic = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        doGlobalFlush := hr.doHardRedirect()
        when(doGlobalFlush) {
          s1_rename.flushIt()
        }
      })
    }

    // --- 资源释放 ---
    setup.issuePpl.release();
    setup.busyTableService.release();
  }
}
