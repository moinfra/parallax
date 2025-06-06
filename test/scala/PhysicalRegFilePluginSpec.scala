package test.scala

import parallax.common.PhysicalRegFilePlugin
import parallax.common._
import parallax.utilities._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.Flow
import spinal.lib.sim.{FlowDriver, FlowMonitor}
import scala.util.Random
import spinal.tester.{SpinalSimFunSuite, SpinalAnyFunSuite}
import parallax.common.PhysicalRegFileService

class PhysicalRegFilePluginSpec extends SpinalSimFunSuite {

  // 开发迭代阶段，只使用 verilator，其他两个禁用
  ghdlEnabled = false
  iverilogEnabled = false
  // Define a test clock domain
  def simConfig = SimConfig.withWave.withVcdWave // 必须用 def，不要擅自改成 val

  // Helper function to create and compile the DUT
  def createDut() = {
    class TestPlugin extends Plugin {
      val setup = create early new Area {
        val regfile = getService[PhysicalRegFileService]
        val readPort = regfile.newReadPort().simPublic()
        val writePort = regfile.newWritePort().simPublic()
      }
    }

    class TestBench extends Component {
      val database = new DataBase
      val framework = ProjectScope(database) on new Framework(
        Seq(
          new PhysicalRegFilePlugin(
            numPhysRegs = 32,
            dataWidth = 32 bits
          ),
          new TestPlugin()
        )
      )

      def testPlugin = framework.getService[TestPlugin]
      def readPort = testPlugin.setup.readPort
      def writePort = testPlugin.setup.writePort
    }
    new TestBench()
  }

  test("Write to a reg and read it back") {
    simConfig
      .compile(createDut())
      .doSim { dut =>
        {
          val readPort = dut.readPort
          val writePort = dut.writePort

          dut.clockDomain.forkStimulus(period = 10)
          // Initialize port signals to a known state
          writePort.valid #= false
          writePort.address #= 0
          writePort.data #= 0

          readPort.valid #= false
          readPort.address #= 0
          dut.clockDomain.waitSampling() // Wait for initial values to propagate

          // Constants for testing.
          // It's assumed Config.R0_PHYS_TAG is 0. If it's different, this literal must change.
          // The DUT's behavior is dictated by the actual Config.R0_PHYS_TAG value used during its compilation.
          val R0_TAG = 0
          // val DATA_WIDTH = 32 // dataWidth is set to 32 bits in plugin instantiation

          // --- Test 1: Write to a non-R0 register and read it back ---
          println("Starting Test 1: Write to a non-R0 register and read it back.")
          val testAddr1 = 5 // Arbitrary non-R0 address
          val testData1 = 0x12345678L // 'L' suffix to ensure it's a Long, good for BigInt conversion

          // Write Operation
          writePort.valid #= true
          writePort.address #= testAddr1
          writePort.data #= testData1
          println(s"Tick ${simTime()}: Writing ${testData1.toHexString} to PReg ${testAddr1}")
          dut.clockDomain.waitSampling() // Write occurs at this clock edge

          writePort.valid #= false // De-assert valid
          dut.clockDomain
            .waitSampling() // Wait a tick before reading for clarity, though not strictly needed if readAsync is truly async

          // Read Operation
          readPort.valid #= true
          readPort.address #= testAddr1
          println(s"Tick ${simTime()}: Reading from PReg ${testAddr1}")
          dut.clockDomain.waitSampling() // Read command sent; rsp should be updated in this tick due to readAsync

          val readData1 = readPort.rsp.toBigInt
          println(s"Tick ${simTime()}: Read back ${readData1.toString(16)} from PReg ${testAddr1}")
          assert(
            readData1 == testData1,
            s"Test 1 Read data mismatch: Expected ${testData1.toHexString}, got ${readData1.toString(16)}"
          )

          readPort.valid #= false
          dut.clockDomain.waitSampling(5) // Some delay

          // --- Test 2: Attempt to write to R0 (physical tag R0_TAG) and read it back (should be 0) ---
          println(s"Starting Test 2: Attempt to write to R0 (PReg $R0_TAG) and read it back.")
          val nonZeroDataForR0 = 0xffffffffL

          // Attempt to write to R0
          writePort.valid #= true
          writePort.address #= R0_TAG
          writePort.data #= nonZeroDataForR0 // This write should be ignored by the DUT
          println(s"Tick ${simTime()}: Attempting to write ${nonZeroDataForR0.toHexString} to PReg ${R0_TAG}")
          dut.clockDomain.waitSampling()

          writePort.valid #= false
          dut.clockDomain.waitSampling()

          // Read from R0
          readPort.valid #= true
          readPort.address #= R0_TAG
          println(s"Tick ${simTime()}: Reading from PReg ${R0_TAG}")
          dut.clockDomain.waitSampling()

          val readDataR0 = readPort.rsp.toBigInt
          println(s"Tick ${simTime()}: Read back ${readDataR0.toString(16)} from PReg ${R0_TAG}")
          assert(readDataR0 == 0, s"Test 2 Read from R0 mismatch: Expected 0, got ${readDataR0.toString(16)}")

          readPort.valid #= false
          dut.clockDomain.waitSampling(5)

          // --- Test 3: Write to a different non-R0 register, then read previously written non-R0, then R0 ---
          println("Starting Test 3: Write to another reg, verify, then re-check first reg and R0.")
          val testAddr2 = 10 // Another arbitrary non-R0 address
          val testData2 = 0xaabbccddL

          // Write to testAddr2
          writePort.valid #= true
          writePort.address #= testAddr2
          writePort.data #= testData2
          println(s"Tick ${simTime()}: Writing ${testData2.toHexString} to PReg ${testAddr2}")
          dut.clockDomain.waitSampling()
          writePort.valid #= false
          dut.clockDomain.waitSampling()

          // Read from testAddr2 to verify
          readPort.valid #= true
          readPort.address #= testAddr2
          println(s"Tick ${simTime()}: Reading from PReg ${testAddr2}")
          dut.clockDomain.waitSampling()
          val readData2 = readPort.rsp.toBigInt
          println(s"Tick ${simTime()}: Read back ${readData2.toString(16)} from PReg ${testAddr2}")
          assert(
            readData2 == testData2,
            s"Test 3 Read from testAddr2 mismatch: Expected ${testData2.toHexString}, got ${readData2.toString(16)}"
          )
          readPort.valid #= false
          dut.clockDomain.waitSampling()

          // Read from testAddr1 again (should still hold testData1)
          readPort.valid #= true
          readPort.address #= testAddr1
          println(s"Tick ${simTime()}: Reading again from PReg ${testAddr1}")
          dut.clockDomain.waitSampling()
          val readData1Again = readPort.rsp.toBigInt
          println(s"Tick ${simTime()}: Read back ${readData1Again.toString(16)} from PReg ${testAddr1}")
          assert(
            readData1Again == testData1,
            s"Test 3 Read from testAddr1 (second time) mismatch: Expected ${testData1.toHexString}, got ${readData1Again
                .toString(16)}"
          )
          readPort.valid #= false
          dut.clockDomain.waitSampling()

          // Read from R0 again (should still be 0)
          readPort.valid #= true
          readPort.address #= R0_TAG
          println(s"Tick ${simTime()}: Reading again from PReg ${R0_TAG}")
          dut.clockDomain.waitSampling()
          val readDataR0Again = readPort.rsp.toBigInt
          println(s"Tick ${simTime()}: Read back ${readDataR0Again.toString(16)} from PReg ${R0_TAG}")
          assert(
            readDataR0Again == 0,
            s"Test 3 Read from R0 (second time) mismatch: Expected 0, got ${readDataR0Again.toString(16)}"
          )
          readPort.valid #= false

          println(s"Tick ${simTime()}: All tests completed.")
          dut.clockDomain.waitSampling(10) // Final wait for waves to capture last states
        }
      }
  }
}
