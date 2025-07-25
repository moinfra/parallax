// filename: hw/spinal/parallax/issue/DispatchPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities._
import parallax.components.rename.BusyTableService

class DispatchPlugin(pCfg: PipelineConfig) extends Plugin with LockedImpl {
  
  assert(pCfg.renameWidth == 1, "This DispatchPlugin is designed for a dispatch width of 1.")

  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val iqService = getService[IssueQueueService]
    val busyTableService = getService[BusyTableService]
    issuePpl.retain(); iqService.retain(); busyTableService.retain()

    val s3_dispatch = issuePpl.pipeline.s3_dispatch
    s3_dispatch(issuePpl.signals.ALLOCATED_UOPS)
  }

  val logic = create late new Area {
    lock.await()
    val s3_dispatch = setup.s3_dispatch
    val busyBitsReg = setup.busyTableService.getBusyBitsReg()
    val iqRegs = setup.iqService.getRegistrations

    // 强硬断言：IQ注册列表不能为空
    assert(iqRegs.nonEmpty, "DispatchPlugin: No IQ registrations found! This indicates a retain/release timing issue.")

    val uopIn = s3_dispatch(setup.issuePpl.signals.ALLOCATED_UOPS)(0)
    val physSrc1 = uopIn.rename.physSrc1.idx
    val physSrc2 = uopIn.rename.physSrc2.idx

    // --- 检查与 physSrc1 的冲突 ---
    val uopInS2 = setup.issuePpl.pipeline.s2_rob_alloc(setup.issuePpl.signals.ALLOCATED_UOPS)(0)
    val physSrc1ConflictS1 = False
    
    // 检查指令 X 的源，是否与指令 Z 的目标冲突
    val physSrc1ConflictS2 = 
        uopInS2.decoded.isValid &&
        uopInS2.rename.allocatesPhysDest &&
        uopInS2.rename.physDest.idx === physSrc1
    
    // --- 检查与 physSrc2 的冲突 (逻辑同上) ---
    val physSrc2ConflictS1 = False
    
    val physSrc2ConflictS2 = 
        uopInS2.decoded.isValid &&
        uopInS2.rename.allocatesPhysDest &&
        uopInS2.rename.physDest.idx === physSrc2

    val clearBypass = setup.busyTableService.getClearBypass()

    val src1SetBypass = physSrc1ConflictS1 || physSrc1ConflictS2
    val src2SetBypass = physSrc2ConflictS1 || physSrc2ConflictS2

    // --- 修正后的最终就绪逻辑 (紧凑版) ---
    val src1ReadyCandidate = !busyBitsReg(physSrc1) || clearBypass(physSrc1)
    val src1InitialReady = !uopIn.decoded.useArchSrc1 || (src1ReadyCandidate && !src1SetBypass)

    val src2ReadyCandidate = !busyBitsReg(physSrc2) || clearBypass(physSrc2)
    val src2InitialReady = !uopIn.decoded.useArchSrc2 || (src2ReadyCandidate && !src2SetBypass)


    // --- 路由和停顿逻辑 ---
    val iqPorts = iqRegs.map(_._2)
    val dispatchOH = B(iqRegs.map { case (uopCodes, _) =>
      uopCodes.map(uopIn.decoded.uopCode === _).orR
    })
    val destinationIqReady = MuxOH(dispatchOH, Vec(iqPorts.map(_.ready)))

    // 停顿条件：当本阶段有效，且指令有效，但目标IQ已满时，停顿流水线。
    // 【修正】: 移除了 isRealOperation 的判断，因为无效指令在Decode阶段已被过滤。
    s3_dispatch.haltWhen(s3_dispatch.isValid && uopIn.decoded.isValid && !destinationIqReady)
    
    // --- 输出驱动 ---
    for (((_, port), i) <- iqRegs.zipWithIndex) {
      val isTarget = dispatchOH(i)
      
      // 【修正】: 移除了 isRealOperation 的判断
      port.valid := s3_dispatch.isFiring && uopIn.decoded.isValid && isTarget

      when(port.valid) { // 仅在驱动时赋值，避免latch
          port.payload.uop             := uopIn
          port.payload.src1InitialReady := src1InitialReady
          port.payload.src2InitialReady := src2InitialReady
      } otherwise {
          port.payload.assignDontCare() // 在不驱动时，明确表示不关心payload的值
      }

      val debugDispatchedUopSrc2 = RegNext(uopIn.rename.physSrc2.idx) addAttribute("MARK_DEBUG", "TRUE") addAttribute("DONT_TOUCH", "TRUE") setCompositeName(this, "debugDispatchedUopSrc2_iq" + i.toString())
      val debugDispatchedUopSrc1 = RegNext(uopIn.rename.physSrc1.idx) addAttribute("MARK_DEBUG", "TRUE") addAttribute("DONT_TOUCH", "TRUE") setCompositeName(this, "debugDispatchedUopSrc1_iq" + i.toString())
    }
    
    // --- 日志和收尾 ---
    when(s3_dispatch.isFiring && uopIn.decoded.isValid) {
      ParallaxSim.log(
        L"DispatchPlugin: Firing robPtr=${uopIn.robPtr} (UopCode=${uopIn.decoded.uopCode}), " :+
        L"s1_ready(initial)=${src1InitialReady}, s2_ready(initial)=${src2InitialReady}"
      )
    }

    val flush = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        val doHardRedirect = hr.doHardRedirect()
        when(doHardRedirect) {
          s3_dispatch.flushIt()
          report(L"DispatchPlugin: (s3): Flushing pipeline due to hard redirect")
        }
      })
    }
    
    setup.issuePpl.release()
    setup.iqService.release()
    setup.busyTableService.release()
  }
}
