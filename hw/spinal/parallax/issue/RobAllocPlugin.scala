// filename: src/main/scala/parallax/issue/RobAllocPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rob.ROBService
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}

class RobAllocPlugin(val pCfg: PipelineConfig) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val robService = getService[ROBService[RenamedUop]]
    issuePpl.retain()
    robService.retain()

    val s2_rob_alloc = issuePpl.pipeline.s2_rob_alloc
    s2_rob_alloc(issuePpl.signals.RENAMED_UOPS)
    s2_rob_alloc(issuePpl.signals.ALLOCATED_UOPS) // 确保Stageable被注册
  }

  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val s2_rob_alloc = setup.s2_rob_alloc
    val issueSignals = issuePpl.signals
    val robService = setup.robService

    // 【关键修正】: 从 s2_rob_alloc 的输入寄存器获取数据
    // 这是从 s1_rename 阶段传递过来的 RenamedUop 数组
    val renamedUopsInFromS1 = s2_rob_alloc(issueSignals.RENAMED_UOPS)
    // 依然使用 renamedUopIn 这个名字，但它的数据源现在是正确的了
    val renamedUopIn = renamedUopsInFromS1(0)
    val decodedUop = renamedUopIn.decoded

    // 接下来完全遵照你最初的实现结构
    val robAllocPort = robService.getAllocatePorts(pCfg.renameWidth)(0)
    
    // 停顿逻辑
    s2_rob_alloc.haltWhen(!robAllocPort.ready)

    // 驱动ROB分配端口 - 这是避免LATCH的关键
    // 无论 valid 是真是假，payload 都必须被驱动
    robAllocPort.valid := s2_rob_alloc.isFiring && decodedUop.isValid
    robAllocPort.pcIn  := decodedUop.pc
    robAllocPort.uopIn := renamedUopIn

    // 构造输出到 s3 的数据
    // 再次使用你最初的、安全的、逐字段赋值的构造方式
    val robAllocPorts = robService.getAllocatePorts(pCfg.renameWidth)
    val newUopsArray = Vec(RenamedUop(pCfg), pCfg.renameWidth)
    for (i <- 0 until pCfg.renameWidth) {
      // 数据源来自正确的流水线输入
      val currentUopIn = renamedUopsInFromS1(i) 
      
      newUopsArray(i).decoded      := currentUopIn.decoded
      newUopsArray(i).rename       := currentUopIn.rename
      newUopsArray(i).uniqueId     := currentUopIn.uniqueId
      newUopsArray(i).dispatched   := currentUopIn.dispatched
      newUopsArray(i).executed     := currentUopIn.executed
      newUopsArray(i).hasException := currentUopIn.hasException
      newUopsArray(i).exceptionCode:= currentUopIn.exceptionCode
      // 用从ROB获取的新指针覆盖旧的
      newUopsArray(i).robPtr       := robAllocPorts(i).robPtr
    }
    s2_rob_alloc(issueSignals.ALLOCATED_UOPS) := newUopsArray

    // 你最初的日志，也保留
    when(s2_rob_alloc.isValid) {
        report(L"RobAllocPlugin(s2): STAGE_VALID. isFiring=${s2_rob_alloc.isFiring}, uopValid=${decodedUop.isValid}, uopPC=${decodedUop.pc}")
        report(L"  IN:  renamedUopIn.robPtr = ${renamedUopIn.robPtr}")
        report(L"  ROB: robAllocPort.robPtr = ${robAllocPorts(0).robPtr}")
        report(L"  OUT: Driving uopOut.robPtr with ${newUopsArray(0).robPtr}")
    }
    
    val flush = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        val doHardRedirect = hr.getFlushListeningPort()
        when(doHardRedirect) {
          s2_rob_alloc.flushIt()
          report(L"RobAllocPlugin (s2): Flushing pipeline due to hard redirect")
        }
      })
    }

    issuePpl.release()
    robService.release()
  }
}
