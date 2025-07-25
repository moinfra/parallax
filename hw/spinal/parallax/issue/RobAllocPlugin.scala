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
    issuePpl.retain(); robService.retain()

    val s2_rob_alloc = issuePpl.pipeline.s2_rob_alloc
    val signals = issuePpl.signals
  }
  val doGlobalFlush = Bool()
  val logic = create late new Area {
    lock.await()
    val s2_rob_alloc = setup.s2_rob_alloc
    val robService = setup.robService
    val signals = setup.signals

    val robAllocPorts = robService.getAllocatePorts(pCfg.renameWidth)

    // 1. 停顿决策：只关心 ROB 是否已满
    s2_rob_alloc.haltWhen(!robAllocPorts(0).ready)
    
    // 2. 驱动 ROB 分配端口
    // 【关键】: 数据源是 RENAMED_UOPS
    val finalRenamedUops = s2_rob_alloc(signals.RENAMED_UOPS)
    
    for (i <- 0 until pCfg.renameWidth) {
        robAllocPorts(i).valid := s2_rob_alloc.isFiring && finalRenamedUops(i).decoded.isValid
        robAllocPorts(i).pcIn  := finalRenamedUops(i).decoded.pc
        robAllocPorts(i).uopIn := finalRenamedUops(i) // uopIn 现在是完整的 RenamedUop
    }

    // 3. 聚合信息，构造最终输出到 s3
    val allocatedUops = Vec(RenamedUop(pCfg), pCfg.renameWidth)
    for(i <- 0 until pCfg.renameWidth) {
        val inUop = finalRenamedUops(i)
        val outUop = allocatedUops(i)
        
        // --- 逐个字段赋值，现在 inUop.rename 是正确的了 ---
        outUop.decoded      := inUop.decoded
        outUop.rename       := inUop.rename // 现在这个赋值是正确的！
        outUop.uniqueId     := inUop.uniqueId
        outUop.dispatched   := inUop.dispatched
        outUop.executed     := inUop.executed
        outUop.hasException := inUop.hasException
        outUop.exceptionCode:= inUop.exceptionCode
        
        outUop.robPtr       := robAllocPorts(i).robPtr
    }
    
    val flushLogic = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        doGlobalFlush := hr.doHardRedirect()
        when(doGlobalFlush) {
          s2_rob_alloc.flushIt()
          report(L"DispatchPlugin: (s3): Flushing pipeline due to hard redirect")
        }
      })
    }

    s2_rob_alloc(signals.ALLOCATED_UOPS) := allocatedUops
    
    setup.issuePpl.release(); setup.robService.release()
  }
}
