// testOnly test.scala.WakeupPluginSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite
import parallax.execute._
import parallax.common._
import parallax.utilities._
import spinal.lib.sim.{StreamDriver, StreamMonitor, FlowMonitor}

// +++ NEW +++
// 定义一个常量，以便在多个地方使用
object WakeupTestConfig {
    val NumSources = 3
}

// Test setup plugin to connect WakeupPlugin ports to testbench IO
class WakeupTestSetupPlugin(
    pCfg: PipelineConfig,
    testIO: WakeupTestIO
) extends Plugin {
  val setup = create early new Area {
    val wakeupService = getService[WakeupService]
    
    // 创建多个唤醒源
    for (i <- 0 until WakeupTestConfig.NumSources) {
      val source = wakeupService.newWakeupSource()
      source <> testIO.sources(i)
    }
  }

  val logic = create late new Area {
    val wakeupService = getService[WakeupService]
    
    // 连接广播总线
    // testIO.allWakeupFlows <> wakeupService.getWakeupFlows()
    testIO.allWakeupFlows.zip(wakeupService.getWakeupFlows()) foreach {
      case (sink, source) => sink <> source
    }
  }
}

// Test IO bundle
case class WakeupTestIO(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  // --- OLD ---
  // val source1 = ...
  // val mergedWakeupFlow = ...
  // +++ NEW +++
  val sources = Vec.fill(WakeupTestConfig.NumSources)(slave(Flow(WakeupPayload(pCfg))))
  val allWakeupFlows = Vec.fill(WakeupTestConfig.NumSources)(master(Flow(WakeupPayload(pCfg))))
  
  override def asMaster(): Unit = {
    sources.foreach(master(_))
    allWakeupFlows.foreach(slave(_))
  }
}

// Test framework component (保持不变)
class WakeupTestBench(val pCfg: PipelineConfig) extends Component {
  val io = slave(WakeupTestIO(pCfg))
  io.simPublic()

  val framework = new Framework(
    Seq(
      new WakeupPlugin(pCfg),
      new WakeupTestSetupPlugin(pCfg, io)
    )
  )
}

class WakeupPluginSpec extends CustomSpinalSimFunSuite {
  val physGprCount = 8
  val renameWidth = 2
  val pCfg = PipelineConfig(
    renameWidth = renameWidth,
    physGprCount = physGprCount
  )

  def driveSource(source: Flow[WakeupPayload], valid: Boolean, physRegIdx: Int): Unit = {
    source.valid #= valid
    if (valid) {
      source.payload.physRegIdx #= physRegIdx
    }
  }
  
  // +++ NEW +++
  def clearAllSources(sources: Vec[Flow[WakeupPayload]]): Unit = {
      for (source <- sources) {
          driveSource(source, false, 0)
      }
  }

  // --- 重写所有测试用例 ---

  test("WakeupPlugin - No Sources") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb.io.sources)
      tb.clockDomain.waitSampling()
      
      for (flow <- tb.io.allWakeupFlows) {
          assert(!flow.valid.toBoolean, "All broadcast flows should be invalid")
      }
    }
  }

  test("WakeupPlugin - Single Source") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb.io.sources)
      tb.clockDomain.waitSampling()
      
      // 测试 source 0
      driveSource(tb.io.sources(0), true, 3)
      tb.clockDomain.waitSampling()
      
      assert(tb.io.allWakeupFlows(0).valid.toBoolean, "Broadcast flow 0 should be valid")
      assert(tb.io.allWakeupFlows(0).payload.physRegIdx.toInt == 3, "Broadcast flow 0 should carry physRegIdx 3")
      assert(!tb.io.allWakeupFlows(1).valid.toBoolean, "Broadcast flow 1 should be invalid")
      assert(!tb.io.allWakeupFlows(2).valid.toBoolean, "Broadcast flow 2 should be invalid")
      
      driveSource(tb.io.sources(0), false, 0)
      tb.clockDomain.waitSampling()
      assert(!tb.io.allWakeupFlows(0).valid.toBoolean, "Broadcast flow 0 should now be invalid")
    }
  }

  // 这是最重要的测试！验证并行广播
  test("WakeupPlugin - Multiple Sources Simultaneously") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb.io.sources)
      tb.clockDomain.waitSampling()
      
      // 同时激活所有源
      driveSource(tb.io.sources(0), true, 1)
      driveSource(tb.io.sources(1), true, 2)
      driveSource(tb.io.sources(2), true, 3)
      
      tb.clockDomain.waitSampling()
      
      // 验证所有广播端口都同时有效，并携带正确的数据
      assert(tb.io.allWakeupFlows(0).valid.toBoolean, "Broadcast flow 0 should be valid")
      assert(tb.io.allWakeupFlows(0).payload.physRegIdx.toInt == 1, "Payload 0 should be 1")
      
      assert(tb.io.allWakeupFlows(1).valid.toBoolean, "Broadcast flow 1 should be valid")
      assert(tb.io.allWakeupFlows(1).payload.physRegIdx.toInt == 2, "Payload 1 should be 2")
      
      assert(tb.io.allWakeupFlows(2).valid.toBoolean, "Broadcast flow 2 should be valid")
      assert(tb.io.allWakeupFlows(2).payload.physRegIdx.toInt == 3, "Payload 2 should be 3")
      
      // 清除所有源
      clearAllSources(tb.io.sources)
      tb.clockDomain.waitSampling()
      for (flow <- tb.io.allWakeupFlows) {
          assert(!flow.valid.toBoolean, "All broadcast flows should be invalid after clearing sources")
      }
    }
  }

  // 我们可以保留这个测试，但要修改断言
  test("WakeupPlugin - Dynamic Source Activation") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb.io.sources)
      tb.clockDomain.waitSampling()
      
      // 启动源 1
      driveSource(tb.io.sources(1), true, 6)
      tb.clockDomain.waitSampling()
      assert(!tb.io.allWakeupFlows(0).valid.toBoolean)
      assert(tb.io.allWakeupFlows(1).valid.toBoolean && tb.io.allWakeupFlows(1).payload.physRegIdx.toInt == 6)
      assert(!tb.io.allWakeupFlows(2).valid.toBoolean)
      
      // 再加上源 2
      driveSource(tb.io.sources(2), true, 7)
      tb.clockDomain.waitSampling()
      assert(!tb.io.allWakeupFlows(0).valid.toBoolean)
      assert(tb.io.allWakeupFlows(1).valid.toBoolean && tb.io.allWakeupFlows(1).payload.physRegIdx.toInt == 6) // 源1依然有效
      assert(tb.io.allWakeupFlows(2).valid.toBoolean && tb.io.allWakeupFlows(2).payload.physRegIdx.toInt == 7) // 源2也有效
    }
  }

  // Edge case test is still valid
  test("WakeupPlugin - Edge Cases") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
        tb.clockDomain.forkStimulus(2)
        clearAllSources(tb.io.sources)
        tb.clockDomain.waitSampling()
      
        driveSource(tb.io.sources(0), true, 0)
        tb.clockDomain.waitSampling()
        assert(tb.io.allWakeupFlows(0).valid.toBoolean && tb.io.allWakeupFlows(0).payload.physRegIdx.toInt == 0)

        driveSource(tb.io.sources(0), true, physGprCount - 1)
        tb.clockDomain.waitSampling()
        assert(tb.io.allWakeupFlows(0).valid.toBoolean && tb.io.allWakeupFlows(0).payload.physRegIdx.toInt == physGprCount - 1)
    }
  }

  thatsAll
}
