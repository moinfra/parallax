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
import scala.collection.mutable.ArrayBuffer

class PhysicalRegFilePluginSpec extends SpinalSimFunSuite {

  // 开发迭代阶段，只使用 verilator，其他两个禁用
  ghdlEnabled = false
  iverilogEnabled = false
  // Define a test clock domain
  def simConfig = SimConfig.withWave.withVcdWave // 必须用 def，不要擅自改成 val

  // Helper function to create the original DUT with 1 read/1 write port
  def createDut() = {
    class TestPlugin extends Plugin {
      val setup = create early new Area {
        val regfile = getService[PhysicalRegFileService]
        val readPort = regfile.newPrfReadPort().simPublic()
        val writePort = regfile.newPrfWritePort().simPublic()
      }

      def getTbReadPort = setup.readPort
      def getTbWritePort = setup.writePort
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
      def readPort = testPlugin.getTbReadPort
      def writePort = testPlugin.getTbWritePort
    }
    new TestBench()
  }

  // Helper function to create a DUT for arbitration test (1 read, 3 write ports)
  def createArbitrationDut() = {
    class ArbitrationTestPlugin extends Plugin {
      val setup = create early new Area {
        val regfile = getService[PhysicalRegFileService]
        val readPort = regfile.newPrfReadPort().simPublic()
        // Create 3 write ports to test arbitration
        val writePorts = ArrayBuffer.fill(3)(regfile.newPrfWritePort().simPublic())
      }
      def getTbReadPort = setup.readPort
      def getTbWritePorts = setup.writePorts
    }

    class ArbitrationTestBench extends Component {
      val database = new DataBase
      val framework = ProjectScope(database) on new Framework(
        Seq(
          new PhysicalRegFilePlugin(
            numPhysRegs = 32,
            dataWidth = 32 bits
          ),
          new ArbitrationTestPlugin()
        )
      )
      def testPlugin = framework.getService[ArbitrationTestPlugin]
      def readPort = testPlugin.getTbReadPort
      def writePorts = testPlugin.getTbWritePorts
    }
    new ArbitrationTestBench()
  }

  // Helper function for reading a value from a specific address
  def readValue(addr: Int, readPort: PrfReadPort, dut: Component): BigInt = {
    readPort.valid #= true
    readPort.address #= addr
    dut.clockDomain.waitSampling()
    readPort.valid #= false
    readPort.rsp.toBigInt
  }

  test("Write to a reg and read it back") {
    simConfig
      .compile(createDut())
      .doSim { dut =>
        val readPort = dut.readPort
        val writePort = dut.writePort

        dut.clockDomain.forkStimulus(period = 10)
        // Initialize
        writePort.valid #= false
        readPort.valid #= false
        dut.clockDomain.waitSampling()

        // Test 1: Write and read back
        println("Starting Test 1: Write to a non-R0 register and read it back.")
        val testAddr1 = 5
        val testData1 = 0x12345678L
        writePort.valid #= true
        writePort.address #= testAddr1
        writePort.data #= testData1
        dut.clockDomain.waitSampling()
        writePort.valid #= false
        dut.clockDomain.waitSampling()
        val readData1 = readValue(testAddr1, readPort, dut)
        assert(readData1 == testData1, s"Test 1 Read data mismatch: Expected ${testData1.toHexString}, got ${readData1.toString(16)}")

        // Test 2: Write to R0 is ignored
        println("Starting Test 2: Attempt to write to R0 and read it back.")
        val R0_TAG = 0
        writePort.valid #= true
        writePort.address #= R0_TAG
        writePort.data #= 0xFFFFFFFFL
        dut.clockDomain.waitSampling()
        writePort.valid #= false
        dut.clockDomain.waitSampling()
        val readDataR0 = readValue(R0_TAG, readPort, dut)
        assert(readDataR0 == 0, s"Test 2 Read from R0 mismatch: Expected 0, got ${readDataR0.toString(16)}")

        println("All basic tests completed.")
        dut.clockDomain.waitSampling(10)
      }
  }

  test("Write arbitration logic") {
    simConfig.compile(createArbitrationDut()).doSim { dut =>
      val readPort = dut.readPort
      val writePorts = dut.writePorts

      dut.clockDomain.forkStimulus(period = 10)
      // Initialize all ports
      readPort.valid #= false
      writePorts.foreach { port =>
        port.valid #= false
        port.address #= 0
        port.data #= 0
      }
      dut.clockDomain.waitSampling()

      // Define test data for each port
      val (addr0, data0) = (10, 0xAAAAAAAAL)
      val (addr1, data1) = (11, 0xBBBBBBBBL)
      val (addr2, data2) = (12, 0xCCCCCCCCL)

      // --- Scenario 1: All 3 ports request write. Port 0 should win. ---
      println("Starting Arbitration Scenario 1: All ports request, port 0 should win.")
      // Set requests
      writePorts(0).valid #= true; writePorts(0).address #= addr0; writePorts(0).data #= data0
      writePorts(1).valid #= true; writePorts(1).address #= addr1; writePorts(1).data #= data1
      writePorts(2).valid #= true; writePorts(2).address #= addr2; writePorts(2).data #= data2
      dut.clockDomain.waitSampling()
      // De-assert requests
      writePorts.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()

      // Verify results of Scenario 1
      println("Verifying Scenario 1...")
      var readBack = readValue(addr0, readPort, dut)
      assert(readBack == data0, s"Scenario 1 (Winner): Address $addr0 FAILED. Expected ${data0.toHexString}, got ${readBack.toString(16)}")
      readBack = readValue(addr1, readPort, dut)
      assert(readBack == 0, s"Scenario 1 (Loser): Address $addr1 FAILED. Expected 0, got ${readBack.toString(16)}")
      readBack = readValue(addr2, readPort, dut)
      assert(readBack == 0, s"Scenario 1 (Loser): Address $addr2 FAILED. Expected 0, got ${readBack.toString(16)}")
      println("Scenario 1 PASSED.")
      dut.clockDomain.waitSampling(5)

      // --- Scenario 2: Ports 1 and 2 request write. Port 1 should win. ---
      println("Starting Arbitration Scenario 2: Ports 1 and 2 request, port 1 should win.")
      writePorts(1).valid #= true; writePorts(1).address #= addr1; writePorts(1).data #= data1
      writePorts(2).valid #= true; writePorts(2).address #= addr2; writePorts(2).data #= data2
      dut.clockDomain.waitSampling()
      writePorts.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()

      // Verify results of Scenario 2
      println("Verifying Scenario 2...")
      readBack = readValue(addr1, readPort, dut)
      assert(readBack == data1, s"Scenario 2 (Winner): Address $addr1 FAILED. Expected ${data1.toHexString}, got ${readBack.toString(16)}")
      readBack = readValue(addr2, readPort, dut)
      assert(readBack == 0, s"Scenario 2 (Loser): Address $addr2 FAILED. Expected 0, got ${readBack.toString(16)}")
      // Also check that addr0 was not disturbed
      readBack = readValue(addr0, readPort, dut)
      assert(readBack == data0, s"Scenario 2 (Sanity Check): Address $addr0 FAILED. Expected ${data0.toHexString}, got ${readBack.toString(16)}")
      println("Scenario 2 PASSED.")
      dut.clockDomain.waitSampling(5)

      // --- Scenario 3: Only port 2 requests write. Port 2 should win. ---
      println("Starting Arbitration Scenario 3: Only port 2 requests, port 2 should win.")
      writePorts(2).valid #= true; writePorts(2).address #= addr2; writePorts(2).data #= data2
      dut.clockDomain.waitSampling()
      writePorts.foreach(_.valid #= false)
      dut.clockDomain.waitSampling()
      
      // Verify results of Scenario 3
      println("Verifying Scenario 3...")
      readBack = readValue(addr2, readPort, dut)
      assert(readBack == data2, s"Scenario 3 (Winner): Address $addr2 FAILED. Expected ${data2.toHexString}, got ${readBack.toString(16)}")
      // Check that others were not disturbed
      readBack = readValue(addr0, readPort, dut)
      assert(readBack == data0, s"Scenario 3 (Sanity Check): Address $addr0 FAILED. Expected ${data0.toHexString}, got ${readBack.toString(16)}")
      readBack = readValue(addr1, readPort, dut)
      assert(readBack == data1, s"Scenario 3 (Sanity Check): Address $addr1 FAILED. Expected ${data1.toHexString}, got ${readBack.toString(16)}")
      println("Scenario 3 PASSED.")
      
      println("All arbitration tests completed successfully.")
      dut.clockDomain.waitSampling(10)
    }
  }
}
