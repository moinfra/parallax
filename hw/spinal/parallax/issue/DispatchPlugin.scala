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
    val busyBits = setup.busyTableService.getBusyBits()
    val iqRegs = setup.iqService.getRegistrations

    val uopIn = s3_dispatch(setup.issuePpl.signals.ALLOCATED_UOPS)(0)
    val decoded = uopIn.decoded
    val rename = uopIn.rename

    // 查询BusyTable以确定初始就绪状态
    val src1InitialReady = !decoded.useArchSrc1 || !busyBits(rename.physSrc1.idx)
    val src2InitialReady = !decoded.useArchSrc2 || !busyBits(rename.physSrc2.idx)
    
    // --- 路由和停顿逻辑 ---
    val iqPorts = iqRegs.map(_._2)
    val dispatchOH = B(iqRegs.map { case (uopCodes, _) =>
      uopCodes.map(decoded.uopCode === _).orR
    })
    val destinationIqReady = MuxOH(dispatchOH, Vec(iqPorts.map(_.ready)))

    // 停顿条件：当本阶段有效，且指令有效，但目标IQ已满时，停顿流水线。
    // 【修正】: 移除了 isRealOperation 的判断，因为无效指令在Decode阶段已被过滤。
    s3_dispatch.haltWhen(s3_dispatch.isValid && decoded.isValid && !destinationIqReady)
    
    // --- 输出驱动 ---
    for (((_, port), i) <- iqRegs.zipWithIndex) {
      val isTarget = dispatchOH(i)
      
      // 【修正】: 移除了 isRealOperation 的判断
      port.valid := s3_dispatch.isFiring && decoded.isValid && isTarget

      when(port.valid) { // 仅在驱动时赋值，避免latch
          port.payload.uop             := uopIn
          port.payload.src1InitialReady := src1InitialReady
          port.payload.src2InitialReady := src2InitialReady
      } otherwise {
          port.payload.assignDontCare() // 在不驱动时，明确表示不关心payload的值
      }
    }
    
    // --- 日志和收尾 ---
    when(s3_dispatch.isFiring && decoded.isValid) {
      ParallaxSim.log(
        L"DispatchPlugin: Firing robPtr=${uopIn.robPtr} (UopCode=${decoded.uopCode}), " :+
        L"s1_ready=${src1InitialReady}, s2_ready=${src2InitialReady}"
      )
    }
    
    setup.issuePpl.release()
    setup.iqService.release()
    setup.busyTableService.release()
  }
}
