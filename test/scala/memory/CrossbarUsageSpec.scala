package test.scala.memory // Using a new package for clarity

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.fsm._
import spinal.tester.SpinalSimFunSuite
import parallax.components.memory.Axi4SlaveRam // Provided component
import parallax.bus.SplitGmbToAxi4Bridge // Provided component
import parallax.components.memory.{
  GenericMemoryBusConfig,
  SplitGenericMemoryBus,
  GenericMemoryBusOpcode
} // For GMB types

// Define common configurations for testbenches
object TestBenchConfigs {
  val GMB_ADDR_WIDTH = 16
  val GMB_DATA_WIDTH = 32
  val GMB_ID_WIDTH = 4 // GMB master native ID width
  val GMB_USE_ID = true

  val gmbConfig = GenericMemoryBusConfig(
    addressWidth = GMB_ADDR_WIDTH bits,
    dataWidth = GMB_DATA_WIDTH bits,
    useId = GMB_USE_ID,
    idWidth = GMB_ID_WIDTH bits
  )

  // This is the AXI config that the SplitGmbToAxi4Bridge will output on its master port.
  // Slaves connected directly to this (or via a crossbar with 1 master path) would use this.
  val bridgeOutputAxiConfig = Axi4Config(
    addressWidth = GMB_ADDR_WIDTH,
    dataWidth = GMB_DATA_WIDTH,
    idWidth = GMB_ID_WIDTH, // Bridge passes GMB ID, or AXI ID can be wider if bridge handles it.
    // SplitGmbToAxi4Bridge resizes, so AXI ID width should be >= GMB ID width.
    useId = GMB_USE_ID,
    useLock = false,
    useRegion = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useLen = true, // SplitGmbToAxi4Bridge uses len
    useBurst = true, // SplitGmbToAxi4Bridge uses burst
    useSize = true, // SplitGmbToAxi4Bridge uses size
    useStrb = true, // SplitGmbToAxi4Bridge uses strb for writes
    useResp = true,
    useLast = true // SplitGmbToAxi4Bridge uses W.last
  )
}

// Helper class for driving SplitGenericMemoryBus from testbench
case class SplitGmbMasterDriver(gmb: SplitGenericMemoryBus, cd: ClockDomain) {
  private def init(): Unit = {
    gmb.read.cmd.valid #= false
    if (gmb.read.cmd.payload != null) gmb.read.cmd.payload.assignDontCare() // Handle null check for safety
    gmb.read.rsp.ready #= false

    gmb.write.cmd.valid #= false
    if (gmb.write.cmd.payload != null) gmb.write.cmd.payload.assignDontCare()
    gmb.write.rsp.ready #= false
    cd.waitSampling() // Ensure initial values propagate
  }
  init()

  def sendReadCmd(address: BigInt, id: Int = 0, timeoutCycles: Int = 20): Unit = {
    gmb.read.cmd.valid #= true
    gmb.read.cmd.payload.address #= address
    if (gmb.config.useId && gmb.read.cmd.payload.id != null) gmb.read.cmd.payload.id #= id

    if (cd.waitSamplingWhere(timeoutCycles)(gmb.read.cmd.ready.toBoolean)) {
      assert(false, s"Timeout waiting for GMB Read CMD ready. Addr=$address, ID=$id")
    }
    gmb.read.cmd.valid #= false
  }

  def expectReadRsp(
      expectedData: BigInt,
      expectedId: Int = 0,
      expectError: Boolean = false,
      timeoutCycles: Int = 50
  ): Unit = {
    gmb.read.rsp.ready #= true
    if (cd.waitSamplingWhere(timeoutCycles)(gmb.read.rsp.valid.toBoolean)) {
      assert(false, s"Timeout waiting for GMB Read RSP valid. Expected ID=$expectedId")
    }
    gmb.read.rsp.ready #= false
    assert(
      gmb.read.rsp.payload.data.toBigInt == expectedData,
      s"Read data mismatch: Got 0x${gmb.read.rsp.payload.data.toBigInt.toString(16)}, Expected 0x${expectedData.toString(16)}"
    )
    if (gmb.config.useId && gmb.read.rsp.payload.id != null) {
      assert(
        gmb.read.rsp.payload.id.toInt == expectedId,
        s"Read Rsp ID mismatch: Got ${gmb.read.rsp.payload.id.toInt}, Expected ${expectedId}"
      )
    }
    if (gmb.read.rsp.payload.error != null) {
      assert(
        gmb.read.rsp.payload.error.toBoolean == expectError,
        s"Read Rsp error mismatch: Got ${gmb.read.rsp.payload.error.toBoolean}, Expected ${expectError}"
      )
    }
    gmb.read.rsp.ready #= false
  }

  def sendWriteCmd(address: BigInt, data: BigInt, byteEnables: BigInt, id: Int = 0, timeoutCycles: Int = 20): Unit = {
    gmb.write.cmd.valid #= true
    gmb.write.cmd.payload.address #= address
    gmb.write.cmd.payload.data #= data
    gmb.write.cmd.payload.byteEnables #= byteEnables
    if (gmb.config.useId && gmb.write.cmd.payload.id != null) gmb.write.cmd.payload.id #= id

    if (cd.waitSamplingWhere(timeoutCycles)(gmb.write.cmd.ready.toBoolean)) {
      assert(false, s"Timeout waiting for GMB Write CMD ready. Addr=$address, ID=$id")

    }
    gmb.write.cmd.valid #= false
  }

  def expectWriteRsp(expectedId: Int = 0, expectError: Boolean = false, timeoutCycles: Int = 50): Unit = {
    gmb.write.rsp.ready #= true
    if (cd.waitSamplingWhere(timeoutCycles)(gmb.write.rsp.valid.toBoolean)) {
      assert(false, s"Timeout waiting for GMB Write RSP valid. Expected ID=$expectedId")
    }
    gmb.write.rsp.ready #= false
    if (gmb.config.useId && gmb.write.rsp.payload.id != null) {
      assert(
        gmb.write.rsp.payload.id.toInt == expectedId,
        s"Write Rsp ID mismatch: Got ${gmb.write.rsp.payload.id.toInt}, Expected ${expectedId}"
      )
    }
    if (gmb.write.rsp.payload.error != null) {
      assert(
        gmb.write.rsp.payload.error.toBoolean == expectError,
        s"Write Rsp error mismatch: Got ${gmb.write.rsp.payload.error.toBoolean}, Expected ${expectError}"
      )
    }
    gmb.write.rsp.ready #= false
  }
}

// Component for Testbench 1: 1 Master -> Bridge -> Crossbar -> 1 Axi4SlaveRam
class Test1_GmbToAxiRamSystem extends Component {
  val gmbConfig = TestBenchConfigs.gmbConfig
  // For a single master path to a slave, the slave's AXI config can match the bridge's output AXI config.
  val axiConfig = TestBenchConfigs.bridgeOutputAxiConfig

  val io = new Bundle {
    val gmbMasterIn = slave(SplitGenericMemoryBus(gmbConfig))
  }

  val bridge = new SplitGmbToAxi4Bridge(gmbConfig, axiConfig)
  bridge.io.gmbIn <> io.gmbMasterIn

  val crossbar = Axi4CrossbarFactory()

  val ramSizeWords = 256
  val ramBytePerWord = axiConfig.dataWidth / 8
  val ramSizeInBytes = ramSizeWords * ramBytePerWord
  val ramSlave = new Axi4SlaveRam(axiConfig, ramSizeWords)

  crossbar.addSlave(ramSlave.io.axi, SizeMapping(0x0000, ramSizeInBytes))
  crossbar.addConnection(bridge.io.axiOut, Seq(ramSlave.io.axi)) // Only one master connecting to this slave
  crossbar.build()
}

// Component for Testbench 2: 2 Masters -> 2 Bridges -> Crossbar -> 2 AXI Slaves
class Test2_TwoGmbToTwoAxiSlavesSystem extends Component {
  val gmbConfig = TestBenchConfigs.gmbConfig
  val bridgeOutputAxiConfig = TestBenchConfigs.bridgeOutputAxiConfig

  val numMasters = 2
  val routingIdBits = if (numMasters > 1) log2Up(numMasters) else 0 // 1 bit for 2 masters

  // Slaves need an AXI config that can accommodate IDs from the crossbar,
  // which include routing bits.
  val slaveAxiConfig = bridgeOutputAxiConfig.copy(
    idWidth = bridgeOutputAxiConfig.idWidth + routingIdBits
  )

  val io = new Bundle {
    val gmbMaster0In = slave(SplitGenericMemoryBus(gmbConfig))
    val gmbMaster1In = slave(SplitGenericMemoryBus(gmbConfig))
  }

  val bridge0 = new SplitGmbToAxi4Bridge(gmbConfig, bridgeOutputAxiConfig)
  bridge0.io.gmbIn <> io.gmbMaster0In

  val bridge1 = new SplitGmbToAxi4Bridge(gmbConfig, bridgeOutputAxiConfig)
  bridge1.io.gmbIn <> io.gmbMaster1In

  val crossbar = Axi4CrossbarFactory()

  // Slave 1: Axi4SlaveRam
  val ram1SizeWords = 256
  val ram1BytePerWord = slaveAxiConfig.dataWidth / 8
  val ram1SizeInBytes = ram1SizeWords * ram1BytePerWord
  val ram1BaseAddr = 0x00000000L // Use Long for addresses
  val ramSlave1 = new Axi4SlaveRam(slaveAxiConfig, ram1SizeWords, ram1BaseAddr)

  // Slave 2: SimulatedSplitGeneralMemoryAxiSlave
  val ram2SizeWords = 512
  val ram2BytePerWord = slaveAxiConfig.dataWidth / 8
  val ram2SizeInBytes = ram2SizeWords * ram2BytePerWord
  val ram2BaseAddr = 0x00001000L // Ensure no overlap
  val ramSlave2 = new Axi4SlaveRam(slaveAxiConfig, ram2SizeWords, ram2BaseAddr)

  crossbar.addSlave(ramSlave1.io.axi, SizeMapping(ram1BaseAddr, ram1SizeInBytes))
  crossbar.addSlave(ramSlave2.io.axi, SizeMapping(ram2BaseAddr, ram2SizeInBytes))

  // Both masters can connect to both slaves
  crossbar.addConnection(bridge0.io.axiOut, Seq(ramSlave1.io.axi, ramSlave2.io.axi))
  crossbar.addConnection(bridge1.io.axiOut, Seq(ramSlave1.io.axi, ramSlave2.io.axi))

  crossbar.build()
}

class CrossbarUsageSpec extends SpinalSimFunSuite {
  onlyVerilator() // Or whatever backend you prefer

  test("Test1_SingleMasterSingleRam") {
    SimConfig.withWave.compile(new Test1_GmbToAxiRamSystem()).doSim { dut =>
      implicit val cd = dut.clockDomain
      dut.clockDomain.forkStimulus(10)

      val driver = SplitGmbMasterDriver(dut.io.gmbMasterIn, dut.clockDomain)

      println("Test1: Starting simple write/read test.")
      cd.waitSampling(5)

      val testAddr = 0x40
      val testData = BigInt("CAFED00D", 16)
      val testId = 1
      val byteEnableAll = (BigInt(1) << (TestBenchConfigs.GMB_DATA_WIDTH / 8)) - 1

      // Write operation
      println(s"Test1: Driving GMB Write: Addr=0x${testAddr.toHexString}, Data=0x${testData.toString(16)}, ID=$testId")
      driver.sendWriteCmd(testAddr, testData, byteEnableAll, testId)
      driver.expectWriteRsp(testId)
      println("Test1: GMB Write operation complete.")

      cd.waitSampling(5)

      // Read operation
      val readId = 2
      println(s"Test1: Driving GMB Read: Addr=0x${testAddr.toHexString}, ID=$readId")
      driver.sendReadCmd(testAddr, readId)
      driver.expectReadRsp(testData, readId)
      println("Test1: GMB Read operation complete. Data verified.")

      cd.waitSampling(10)
      simSuccess()
    }
    println("--- Test: Test1_SingleMasterSingleRam Passed ---")
  }

  test("Test2_TwoMastersTwoSlaves") {
    SimConfig.withWave.compile(new Test2_TwoGmbToTwoAxiSlavesSystem()).doSim { dut =>
      implicit val cd = dut.clockDomain
      dut.clockDomain.forkStimulus(10)

      val driver0 = SplitGmbMasterDriver(dut.io.gmbMaster0In, dut.clockDomain)
      val driver1 = SplitGmbMasterDriver(dut.io.gmbMaster1In, dut.clockDomain)

      val byteEnableAll = (BigInt(1) << (TestBenchConfigs.GMB_DATA_WIDTH / 8)) - 1

      println("Test2: Starting two masters, two slaves test.")
      cd.waitSampling(5)

      // Master 0 operations
      val m0_s1_addr = dut.ram1BaseAddr + 0x10
      val m0_s1_wdata = BigInt("1111AAAA", 16)
      val m0_s1_wid = 1
      val m0_s1_rid = 2

      val m0_s2_addr = dut.ram2BaseAddr + 0x20
      val m0_s2_wdata = BigInt("2222BBBB", 16)
      val m0_s2_wid = 3
      val m0_s2_rid = 4

      // Master 1 operations
      val m1_s1_addr = dut.ram1BaseAddr + 0x50 // Different address in RAM1
      val m1_s1_wdata = BigInt("3333CCCC", 16)
      val m1_s1_wid = 5
      val m1_s1_rid = 6

      val m1_s2_addr = dut.ram2BaseAddr + 0x60 // Different address in RAM2
      val m1_s2_wdata = BigInt("4444DDDD", 16)
      val m1_s2_wid = 7
      val m1_s2_rid = 8

      // --- Sequential operations for clarity, can be interleaved with fork/join ---

      // Master 0 writes to Slave 1 (Axi4SlaveRam)
      println(
        s"Test2: M0 -> S1 (RAM) Write: Addr=0x${m0_s1_addr.toHexString}, Data=0x${m0_s1_wdata.toString(16)}, ID=$m0_s1_wid"
      )
      driver0.sendWriteCmd(m0_s1_addr, m0_s1_wdata, byteEnableAll, m0_s1_wid)
      driver0.expectWriteRsp(m0_s1_wid)

      // Master 0 writes to Slave 2 (SimulatedSplitGeneralMemoryAxiSlave)
      println(
        s"Test2: M0 -> S2 (SimMem) Write: Addr=0x${m0_s2_addr.toHexString}, Data=0x${m0_s2_wdata.toString(16)}, ID=$m0_s2_wid"
      )
      driver0.sendWriteCmd(m0_s2_addr, m0_s2_wdata, byteEnableAll, m0_s2_wid)
      driver0.expectWriteRsp(m0_s2_wid)

      cd.waitSampling(2)

      // Master 1 writes to Slave 1 (Axi4SlaveRam)
      println(
        s"Test2: M1 -> S1 (RAM) Write: Addr=0x${m1_s1_addr.toHexString}, Data=0x${m1_s1_wdata.toString(16)}, ID=$m1_s1_wid"
      )
      driver1.sendWriteCmd(m1_s1_addr, m1_s1_wdata, byteEnableAll, m1_s1_wid)
      driver1.expectWriteRsp(m1_s1_wid)

      // Master 1 writes to Slave 2 (SimulatedSplitGeneralMemoryAxiSlave)
      println(
        s"Test2: M1 -> S2 (SimMem) Write: Addr=0x${m1_s2_addr.toHexString}, Data=0x${m1_s2_wdata.toString(16)}, ID=$m1_s2_wid"
      )
      driver1.sendWriteCmd(m1_s2_addr, m1_s2_wdata, byteEnableAll, m1_s2_wid)
      driver1.expectWriteRsp(m1_s2_wid)

      cd.waitSampling(5) // Let writes settle

      // --- Read operations ---

      // Master 0 reads from Slave 1
      println(s"Test2: M0 -> S1 (RAM) Read: Addr=0x${m0_s1_addr.toHexString}, ID=$m0_s1_rid")
      driver0.sendReadCmd(m0_s1_addr, m0_s1_rid)
      driver0.expectReadRsp(m0_s1_wdata, m0_s1_rid)

      // Master 0 reads from Slave 2
      println(s"Test2: M0 -> S2 (SimMem) Read: Addr=0x${m0_s2_addr.toHexString}, ID=$m0_s2_rid")
      driver0.sendReadCmd(m0_s2_addr, m0_s2_rid)
      driver0.expectReadRsp(m0_s2_wdata, m0_s2_rid)

      cd.waitSampling(2)

      // Master 1 reads from Slave 1
      println(s"Test2: M1 -> S1 (RAM) Read: Addr=0x${m1_s1_addr.toHexString}, ID=$m1_s1_rid")
      driver1.sendReadCmd(m1_s1_addr, m1_s1_rid)
      driver1.expectReadRsp(m1_s1_wdata, m1_s1_rid)

      // Master 1 reads from Slave 2
      println(s"Test2: M1 -> S2 (SimMem) Read: Addr=0x${m1_s2_addr.toHexString}, ID=$m1_s2_rid")
      driver1.sendReadCmd(m1_s2_addr, m1_s2_rid)
      driver1.expectReadRsp(m1_s2_wdata, m1_s2_rid)

      println("Test2: All operations complete and verified.")
      cd.waitSampling(10)
      simSuccess()
    }
    println("--- Test: Test2_TwoMastersTwoSlaves Passed ---")
  }
}
