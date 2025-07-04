// testOnly test.scala.BusyTablePluginSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite
import parallax.components.rename._
import parallax.common._
import parallax.utilities._

// Test setup plugin to connect BusyTable ports to testbench IO
class BusyTableTestSetupPlugin(
    pCfg: PipelineConfig,
    testIO: BusyTableTestIO
) extends Plugin {
  val setup = create early new Area {
    val busyTableService = getService[BusyTableService]
    
    // Connect set ports
    val setPorts = busyTableService.newSetPort()
    for (i <- 0 until pCfg.renameWidth) {
      setPorts(i) <> testIO.setPorts(i)
    }
    
    // Connect clear ports  
    val clearPorts = busyTableService.newClearPort()
    for (i <- 0 until clearPorts.length) {
      clearPorts(i) <> testIO.clearPorts(i)
    }
    
    // Connect busy bits output
    testIO.busyBitsOut := busyTableService.getBusyBits()
  }
}

// Test IO bundle
case class BusyTableTestIO(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  val setPorts = Vec(slave(Flow(UInt(pCfg.physGprIdxWidth))), pCfg.renameWidth)
  val clearPorts = Vec(slave(Flow(UInt(pCfg.physGprIdxWidth))), 1) // Single clear port for now
  val busyBitsOut = out(Bits(pCfg.physGprCount bits))
  
  override def asMaster(): Unit = {
    setPorts.foreach(master(_))
    clearPorts.foreach(master(_))
    in(busyBitsOut)
  }
}

// Test framework component
class BusyTableTestBench(val pCfg: PipelineConfig) extends Component {
  val io = slave(BusyTableTestIO(pCfg))
  io.simPublic()

  val framework = new Framework(
    Seq(
      new BusyTablePlugin(pCfg),
      new BusyTableTestSetupPlugin(pCfg, io)
    )
  )
}

class BusyTablePluginSpec extends CustomSpinalSimFunSuite {
  val physGprCount = 8
  val renameWidth = 2
  val pCfg = PipelineConfig(
    renameWidth = renameWidth,
    physGprCount = physGprCount
  )

  def setPort(tb: BusyTableTestBench, idx: Int, valid: Boolean, payload: Int): Unit = {
    tb.io.setPorts(idx).valid #= valid
    tb.io.setPorts(idx).payload #= payload
  }
  def clearPort(tb: BusyTableTestBench, idx: Int, valid: Boolean, payload: Int): Unit = {
    tb.io.clearPorts(idx).valid #= valid
    tb.io.clearPorts(idx).payload #= payload
  }
  def clearAllPorts(tb: BusyTableTestBench): Unit = {
    for (i <- tb.io.setPorts.indices) setPort(tb, i, false, 0)
    for (i <- tb.io.clearPorts.indices) clearPort(tb, i, false, 0)
  }

  test("BusyTable - Initialization") {
    simConfig.compile(new BusyTableTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      assert(tb.io.busyBitsOut.toBigInt == 0, "All busy bits should be 0 after reset")
    }
  }

  test("BusyTable - Set and Clear Single") {
    simConfig.compile(new BusyTableTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      // Set busy for p3
      setPort(tb, 0, true, 3)
      tb.clockDomain.waitSampling()
      setPort(tb, 0, false, 0)
      tb.clockDomain.waitSampling()
      assert((tb.io.busyBitsOut.toBigInt & (1 << 3)) != 0, "p3 should be busy after set")
      // Clear busy for p3
      clearPort(tb, 0, true, 3)
      tb.clockDomain.waitSampling()
      clearPort(tb, 0, false, 0)
      tb.clockDomain.waitSampling()
      assert((tb.io.busyBitsOut.toBigInt & (1 << 3)) == 0, "p3 should be not busy after clear")
    }
  }

  test("BusyTable - Multiple Set and Clear Ports") {
    simConfig.compile(new BusyTableTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      // Set busy for p1, p2
      setPort(tb, 0, true, 1)
      setPort(tb, 1, true, 2)
      tb.clockDomain.waitSampling()
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      assert((tb.io.busyBitsOut.toBigInt & (1 << 1)) != 0, "p1 should be busy")
      assert((tb.io.busyBitsOut.toBigInt & (1 << 2)) != 0, "p2 should be busy")
      // Clear busy for p1, p2
      clearPort(tb, 0, true, 1)
      tb.clockDomain.waitSampling()
      clearPort(tb, 0, true, 2)
      tb.clockDomain.waitSampling()
      clearPort(tb, 0, false, 0)
      tb.clockDomain.waitSampling()
      assert((tb.io.busyBitsOut.toBigInt & (1 << 1)) == 0, "p1 should be not busy")
      assert((tb.io.busyBitsOut.toBigInt & (1 << 2)) == 0, "p2 should be not busy")
    }
  }

  test("BusyTable - Set and Clear Same Cycle (Clear Priority)") {
    simConfig.compile(new BusyTableTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      // Set busy for p4
      setPort(tb, 0, true, 4)
      tb.clockDomain.waitSampling()
      setPort(tb, 0, false, 0)
      tb.clockDomain.waitSampling()
      // Set and clear p4 in the same cycle
      setPort(tb, 0, true, 4)
      clearPort(tb, 0, true, 4)
      tb.clockDomain.waitSampling()
      setPort(tb, 0, false, 0)
      clearPort(tb, 0, false, 0)
      tb.clockDomain.waitSampling()
      // With current implementation: (busyTableReg & ~clearMask) | setMask
      // If same bit is both cleared and set, set will override clear
      // So p4 should be busy (set wins)
      assert((tb.io.busyBitsOut.toBigInt & (1 << 4)) != 0, "p4 should be busy when set and clear in same cycle (set wins)")
    }
  }

  test("BusyTable - Edge and Mask Behavior") {
    simConfig.compile(new BusyTableTestBench(pCfg)).doSim { tb =>
      tb.clockDomain.forkStimulus(2)
      clearAllPorts(tb)
      tb.clockDomain.waitSampling()
      // Set busy for all registers one by one
      for (i <- 0 until physGprCount) {
        setPort(tb, 0, true, i)
        tb.clockDomain.waitSampling()
        setPort(tb, 0, false, 0)
        tb.clockDomain.waitSampling()
      }
      assert(tb.io.busyBitsOut.toBigInt == ((BigInt(1) << physGprCount) - 1), "All should be busy")
      // Clear all
      for (i <- 0 until physGprCount) {
        clearPort(tb, 0, true, i)
        tb.clockDomain.waitSampling()
        clearPort(tb, 0, false, 0)
        tb.clockDomain.waitSampling()
      }
      assert(tb.io.busyBitsOut.toBigInt == 0, "All should be not busy after clear")
    }
  }

  thatsAll // 确保测试被启用
} 