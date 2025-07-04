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

// Test setup plugin to connect WakeupPlugin ports to testbench IO
class WakeupTestSetupPlugin(
    pCfg: PipelineConfig,
    testIO: WakeupTestIO
) extends Plugin {
  val setup = create early new Area {
    val wakeupService = getService[WakeupService]
    
    // Create multiple wakeup sources for testing
    val source1 = wakeupService.newWakeupSource()
    val source2 = wakeupService.newWakeupSource()
    val source3 = wakeupService.newWakeupSource()
    
    // Connect sources to test IO
    source1 <> testIO.source1
    source2 <> testIO.source2
    source3 <> testIO.source3
  }

  val logic = create late new Area {
    val wakeupService = getService[WakeupService]
    
    // Connect merged wakeup flow to test IO (must be done in late Area)
    testIO.mergedWakeupFlow <> wakeupService.getWakeupFlow()
  }
}

// Test IO bundle
case class WakeupTestIO(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  val source1 = slave(Flow(WakeupPayload(pCfg)))
  val source2 = slave(Flow(WakeupPayload(pCfg)))
  val source3 = slave(Flow(WakeupPayload(pCfg)))
  val mergedWakeupFlow = master(Flow(WakeupPayload(pCfg)))
  
  override def asMaster(): Unit = {
    master(source1, source2, source3)
    slave(mergedWakeupFlow)
  }
}

// Test framework component
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

  def clearAllSources(tb: WakeupTestBench): Unit = {
    driveSource(tb.io.source1, false, 0)
    driveSource(tb.io.source2, false, 0)
    driveSource(tb.io.source3, false, 0)
  }

  test("WakeupPlugin - Initialization") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      assert(!tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be invalid when no sources active")
    }
  }

  test("WakeupPlugin - Single Source") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      
      // Test source1
      driveSource(tb.io.source1, true, 3)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be valid when source1 active")
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 3, "Merged flow should carry physRegIdx 3")
      
      driveSource(tb.io.source1, false, 0)
      tb.clockDomain.waitSampling()
      assert(!tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be invalid when source1 inactive")
    }
  }

  test("WakeupPlugin - Multiple Sources Round Robin") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      
      // Activate all sources simultaneously
      driveSource(tb.io.source1, true, 1)
      driveSource(tb.io.source2, true, 2)
      driveSource(tb.io.source3, true, 3)
      
      // Round-robin arbitration should pick sources in order
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be valid")
      // First should be source1 (round-robin starts with first source)
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 1, "First should be source1 (physRegIdx 1)")
      
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should still be valid")
      // Second should be source2
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 2, "Second should be source2 (physRegIdx 2)")
      
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should still be valid")
      // Third should be source3
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 3, "Third should be source3 (physRegIdx 3)")
      
      // Clear all sources
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      assert(!tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be invalid when all sources inactive")
    }
  }

  test("WakeupPlugin - Dynamic Source Activation") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      
      // Start with source1
      driveSource(tb.io.source1, true, 5)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 5, "Should get physRegIdx 5 from source1")
      
      // Add source2
      driveSource(tb.io.source2, true, 6)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 6, "Should get physRegIdx 6 from source2 (round-robin)")
      
      // Remove source1, keep source2
      driveSource(tb.io.source1, false, 0)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 6, "Should still get physRegIdx 6 from source2")
      
      // Add source3
      driveSource(tb.io.source3, true, 7)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 7, "Should get physRegIdx 7 from source3 (round-robin)")
      
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      assert(!tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be invalid when all sources inactive")
    }
  }

  test("WakeupPlugin - Edge Cases") {
    simConfig.compile(new WakeupTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
      
      // Test with physRegIdx 0 (edge case)
      driveSource(tb.io.source1, true, 0)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.valid.toBoolean, "Merged flow should be valid for physRegIdx 0")
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == 0, "Should get physRegIdx 0")
      
      // Test with maximum physRegIdx
      driveSource(tb.io.source1, true, physGprCount - 1)
      tb.clockDomain.waitSampling()
      assert(tb.io.mergedWakeupFlow.payload.physRegIdx.toInt == physGprCount - 1, s"Should get physRegIdx ${physGprCount - 1}")
      
      clearAllSources(tb)
      tb.clockDomain.waitSampling()
    }
  }

  thatsAll // 确保测试被启用
} 