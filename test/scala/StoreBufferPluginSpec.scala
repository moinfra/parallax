// filename: test/scala/lsu/StoreBufferPluginSpec.scala
package test.scala.lsu

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.lsu._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import parallax.bus._
import parallax.components.memory._

import scala.collection.mutable
import scala.util.Random
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4

/** This plugin connects the abstract services required by the StoreBuffer DUT
  * to the concrete IO of the testbench. It acts as a bridge.
  */
class StoreBufferTestConnectionPlugin(
    tbIo: StoreBufferTestBenchIo,
    pCfg: PipelineConfig,
    dCacheCfg: DataCachePluginConfig
) extends Plugin {
  val setup = create early new Area {
    val sbPlugin = getService[StoreBufferPlugin]
    val robService = getService[ROBService[RenamedUop]]
    val dcService = getService[DataCacheService]

    // Connect Testbench IO to DUT (StoreBufferPlugin)
    sbPlugin.getPushPort() <> tbIo.lsuPushIn
    sbPlugin.getBypassQueryAddressInput() := tbIo.bypassQueryAddr
    sbPlugin.getBypassQuerySizeInput() := tbIo.bypassQuerySize
    tbIo.bypassDataOut <> sbPlugin.getBypassDataOutput()

    // Drive ROB allocation from Testbench IO
    val robAllocPorts = robService.getAllocatePorts(pCfg.renameWidth)
    robAllocPorts(0).fire := tbIo.robAllocateIn.fire
    robAllocPorts(0).pcIn := tbIo.robAllocateIn.pcIn
    robAllocPorts(0).uopIn := tbIo.robAllocateIn.uopIn

    tbIo.canRobAllocate := robService.getCanAllocateVec(pCfg.renameWidth)(0)
    tbIo.allocatedRobPtr := robAllocPorts(0).robPtr

    tbIo.robFlushIn <> robService.getFlushPort()

    // Drive ROB writeback from Testbench IO
    val robWbPort = robService.newWritebackPort("TestEU")
    robWbPort.fire := tbIo.robWritebackIn.valid
    robWbPort.robPtr := tbIo.robWritebackIn.payload.robPtr
    robWbPort.exceptionOccurred := tbIo.robWritebackIn.payload.hasException
    robWbPort.exceptionCodeIn := tbIo.robWritebackIn.payload.exceptionCode

    // Monitor and Acknowledge ROB commit
    val commitSlots = robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = robService.getCommitAcks(pCfg.commitWidth)
    tbIo.committedStores.valid := commitSlots(0).valid && commitSlots(
      0
    ).entry.payload.uop.decoded.uopCode === BaseUopCode.STORE
    tbIo.committedStores.payload := commitSlots(0).entry
    commitAcks(0) := tbIo.robCommitAck

    val unusedLoadPort = dcService.newLoadPort(priority = 0)
    unusedLoadPort.cmd.valid := False
    unusedLoadPort.cmd.payload.assignDontCare()
    unusedLoadPort.translated.assignDontCare()
    unusedLoadPort.cancels := 0
    // Expose the D-Cache's writeback busy signal to the testbench
    tbIo.dcacheWritebackBusy := dcService.writebackBusy()
  }
}

/** This plugin provides a concrete memory system implementation (a simulated SRAM)
  * for the DataCache to connect to via the DBusService.
  */
class SBDataMembusTestPlugin(axiConfig: Axi4Config) extends Plugin with DBusService {
  val hw = create early new Area {
    private val sramSize = BigInt("4000", 16) // 16 KiB
    private val extSramCfg = ExtSRAMConfig(
      addressWidth = 16,
      dataWidth = 32,
      virtualBaseAddress = BigInt("00000000", 16),
      sizeBytes = sramSize,
      readWaitCycles = 0,
      enableLog = true
    )
    val sram = new SimulatedSRAM(extSramCfg)
    val ctrl = new ExtSRAMController(axiConfig, extSramCfg)
    ctrl.io.ram <> sram.io.ram
    ctrl.io.simPublic()
    sram.io.simPublic()
  }

  override def getBus(): Axi4 = hw.ctrl.io.axi
  def getSram(): SimulatedSRAM = hw.sram
}

/** The top-level component for the full integration testbench.
  * It uses the Parallax framework to instantiate and connect all necessary components:
  * ROB, DataCache, StoreBuffer, and the test-specific plugins.
  */
class StoreBufferFullIntegrationTestBench(
    val pCfg: PipelineConfig,
    val lsuCfg: LsuConfig,
    val sbDepth: Int,
    val dCacheCfg: DataCachePluginConfig,
    val axiConfig: Axi4Config
) extends Component {
  val io = StoreBufferTestBenchIo(pCfg, lsuCfg, dCacheCfg)
  io.simPublic()

  val database = new DataBase
  val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCacheCfg)

  val framework = ProjectScope(database) on new Framework(
    Seq(
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new DataCachePlugin(dCacheCfg),
      new StoreBufferPlugin(pCfg, lsuCfg, dCacheParams, sbDepth),
      new SBDataMembusTestPlugin(axiConfig),
      new StoreBufferTestConnectionPlugin(io, pCfg, dCacheCfg)
    )
  )

  def getSramHandle(): SimulatedSRAM = framework.getService[SBDataMembusTestPlugin].getSram()
}

/** Defines the IO bundle for the testbench, exposing all necessary control and
  * observation points to the test driver.
  */
case class StoreBufferTestBenchIo(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheCfg: DataCachePluginConfig)
    extends Bundle {
  val lsuPushIn = slave Stream (StoreBufferPushCmd(pCfg, lsuCfg))
  val bypassQueryAddr = in UInt (pCfg.pcWidth)
  val bypassQuerySize = in(MemAccessSize())
  val bypassDataOut = master Flow (StoreBufferBypassData(pCfg))
  val robAllocateIn = in(
    ROBAllocateSlot(
      ROBConfig(
        robDepth = pCfg.robDepth,
        pcWidth = pCfg.pcWidth,
        commitWidth = pCfg.commitWidth,
        allocateWidth = pCfg.renameWidth,
        numWritebackPorts = pCfg.totalEuCount,
        uopType = HardType(RenamedUop(pCfg)),
        defaultUop = () => RenamedUop(pCfg).setDefault(),
        exceptionCodeWidth = pCfg.exceptionCodeWidth
      )
    )
  )
  val robFlushIn = slave Flow (ROBFlushPayload(robPtrWidth = pCfg.robPtrWidth))
  val robWritebackIn = slave Flow (RobCompletionMsg(pCfg))
  val robCommitAck = in Bool ()
  val canRobAllocate = out Bool ()
  val allocatedRobPtr = out(UInt(pCfg.robPtrWidth))
  val committedStores = master(
    Flow(
      ROBFullEntry[RenamedUop](
        ROBConfig(
          robDepth = pCfg.robDepth,
          pcWidth = pCfg.pcWidth,
          commitWidth = pCfg.commitWidth,
          allocateWidth = pCfg.renameWidth,
          numWritebackPorts = pCfg.totalEuCount,
          uopType = HardType(RenamedUop(pCfg)),
          defaultUop = () => RenamedUop(pCfg).setDefault(),
          exceptionCodeWidth = pCfg.exceptionCodeWidth
        )
      )
    )
  )
  val dcacheWritebackBusy = out Bool ()
}

/** A collection of high-level, semantic helper functions to drive the DUT and
  * orchestrate complex test scenarios, making test cases clean and readable.
  */
class TestHelper(dut: StoreBufferFullIntegrationTestBench)(implicit cd: ClockDomain) {

  val sram = dut.getSramHandle()

  def init(): Unit = {
    dut.io.lsuPushIn.valid #= false
    dut.io.robAllocateIn.fire #= false
    dut.io.robWritebackIn.valid #= false
    dut.io.robCommitAck #= false
    dut.io.bypassQueryAddr #= 0x0L
    dut.io.bypassQuerySize #= MemAccessSize.W
    dut.io.robFlushIn.valid #= false
    dut.io.committedStores.valid #= false
    sram.io.tb_writeEnable #= false
    sram.io.tb_readEnable #= false
    cd.waitSampling()
  }

  def cpuIssueMemoryOp(
      addr: BigInt,
      data: BigInt = 0,
      be: BigInt = 0,
      pc: Long = 0x8000,
      isFlush: Boolean = false
  ): BigInt = {
    dut.io.robAllocateIn.fire #= true
    dut.io.robAllocateIn.pcIn #= pc
    val uop = dut.io.robAllocateIn.uopIn
    uop.setDefault()
    uop.decoded.uopCode #= BaseUopCode.STORE
    cd.waitActiveEdge()
    val robPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.fire #= false
    cd.waitSampling()
    ParallaxLogger.debug(s"[Helper] CPU allocated robPtr=$robPtr for op at 0x${addr.toString(16)}")

    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr; p.data #= data; p.be #= be; p.robPtr #= robPtr
    p.accessSize #= MemAccessSize.W
    p.isIO #= false
    p.hasEarlyException #= false
    p.isFlush #= isFlush
    cd.waitSamplingWhere(dut.io.lsuPushIn.ready.toBoolean)
    dut.io.lsuPushIn.valid #= false
    ParallaxLogger.debug(s"[Helper] Pushed op (robPtr=$robPtr) to StoreBuffer.")
    robPtr
  }

  def euSignalCompletion(robPtr: BigInt): Unit = {
    dut.io.robWritebackIn.valid #= true
    dut.io.robWritebackIn.payload.robPtr #= robPtr
    dut.io.robWritebackIn.payload.hasException #= false
    cd.waitActiveEdge()
    dut.io.robWritebackIn.valid #= false
    ParallaxLogger.debug(s"[Helper] EU signaled completion for robPtr=$robPtr.")
  }

  def waitForCommitAndAck(robPtr: BigInt): Unit = {
    val timedOut = cd.waitSamplingWhere(timeout = 200)(
      dut.io.committedStores.valid.toBoolean && dut.io.committedStores.payload.payload.uop.robPtr.toBigInt == robPtr
    )
    assert(!timedOut, s"Timeout waiting for robPtr=$robPtr to be committed.")
    ParallaxLogger.debug(s"[Helper] Op (robPtr=$robPtr) is now at commit head.")

    dut.io.robCommitAck #= true
    cd.waitSampling()
    dut.io.robCommitAck #= false
    cd.waitSampling(30) // 这里必须等一会儿，让整个缓存行所有字索引都OK
    ParallaxLogger.debug(s"[Helper] Acknowledged commit for robPtr=$robPtr.")
  }

  def forceFlushAndWait(address: BigInt): Unit = {
    ParallaxLogger.info(s"[Helper] Forcing D-Cache flush for address 0x${address.toString(16)}")
    val flushRobPtr = cpuIssueMemoryOp(addr = address, pc = 0x9000, isFlush = true)
    euSignalCompletion(flushRobPtr)
    waitForCommitAndAck(flushRobPtr)

    if (dut.io.dcacheWritebackBusy.toBoolean) {
      ParallaxLogger.info(s"[Helper] D-Cache is busy with writeback, waiting...")
      cd.waitSamplingWhere(!dut.io.dcacheWritebackBusy.toBoolean)
    }
    cd.waitSampling(5) // Extra cycles for safety
    ParallaxLogger.info(s"[Helper] Flush and writeback complete.")
  }

  def sramWrite(addr: BigInt, data: BigInt): Unit = {
    sram.io.tb_writeEnable #= true
    sram.io.tb_writeAddress #= addr
    sram.io.tb_writeData #= data
    cd.waitSampling()
    sram.io.tb_writeEnable #= false
  }

  def sramVerify(addr: BigInt, expectedData: BigInt): Unit = {
    sram.io.tb_readEnable #= true
    sram.io.tb_readAddress #= addr
    cd.waitSampling(sram.config.readWaitCycles + 2)
    val readData = sram.io.tb_readData.toBigInt
    assert(
      readData == expectedData,
      s"Memory verification failed at 0x${addr.toString(16)}. Got 0x${readData.toString(16)}, Expected 0x${expectedData.toString(16)}"
    )
    sram.io.tb_readEnable #= false
  }
}

class StoreBufferPluginSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val DEFAULT_SB_DEPTH = 4

  def createPConfig(commitWidth: Int = 1, robDepth: Int = 16, renameWidth: Int = 1): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    commitWidth = commitWidth,
    robDepth = robDepth,
    renameWidth = renameWidth,
    physGprCount = 32,
    archGprCount = 32,
    fetchWidth = 1,
    dispatchWidth = 1,
    aluEuCount = 1,
    transactionIdWidth = 8
  )

  def createLsuConfig(pCfg: PipelineConfig, lqDepth: Int, sqDepth: Int): LsuConfig = LsuConfig(
    lqDepth = lqDepth,
    sqDepth = sqDepth,
    robPtrWidth = pCfg.robPtrWidth,
    pcWidth = pCfg.pcWidth,
    dataWidth = pCfg.dataWidth,
    physGprIdxWidth = pCfg.physGprIdxWidth,
    exceptionCodeWidth = pCfg.exceptionCodeWidth,
    commitWidth = pCfg.commitWidth,
    dcacheRefillCount = 2
  )

  def createDCacheConfig(pCfg: PipelineConfig): DataCachePluginConfig = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = pCfg.dataWidth.value,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 64,
    transactionIdWidth = pCfg.transactionIdWidth
  )

  def createAxi4Config(pCfg: PipelineConfig): Axi4Config = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = pCfg.xlen,
    idWidth = 1,
    useLock = false,
    useCache = false,
    useProt = true,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  test(s"SB_FullIntegration - Basic Store Lifecycle") {
    val pCfg = createPConfig()
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, DEFAULT_SB_DEPTH, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new TestHelper(dut)
        helper.init()

        val storeAddr = BigInt("100", 16)
        val storeData = BigInt("DEADBEEF", 16)
        val initialMemData = BigInt("CAFEBABE", 16)

        // 1. Setup initial state
        helper.sramWrite(storeAddr, initialMemData)
        cd.waitSampling(2)

        // 2. CPU issues a store instruction
        val storeRobPtr = helper.cpuIssueMemoryOp(addr = storeAddr, data = storeData, be = 0xf)

        // 3. Execution unit signals completion
        helper.euSignalCompletion(storeRobPtr)

        // 4. Wait for the store to be committed by the ROB
        helper.waitForCommitAndAck(storeRobPtr)

        // 5. Force a flush and wait for it to complete
        helper.forceFlushAndWait(storeAddr)

        // 6. Verify the final memory state
        helper.sramVerify(storeAddr, storeData)

        println("Test 'SB_FullIntegration - Basic Store Lifecycle' PASSED")
      }
  }

  test("SB_FullIntegration - Backpressure and Queue Full") {
    val sbDepth = 4
    val pCfg = createPConfig(robDepth = 16)
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, sbDepth, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new TestHelper(dut)
        helper.init()

        println("[Test] Starting Backpressure Test")

        // 1. Issue enough stores to fill the buffer
        val storeTasks = for (i <- 0 until sbDepth) yield {
          helper.cpuIssueMemoryOp(addr = 0x100 + i * 4, data = 0x1aaa0000 + i, be = 0xf, pc = 0x8000 + i * 4)
        }

        // 2. Verify SB is full and exerts backpressure
        println("[Test] Verifying backpressure...")
        dut.io.lsuPushIn.valid #= true
        dut.io.lsuPushIn.payload.addr #= 0x200
        cd.waitSampling(5)
        assert(!dut.io.lsuPushIn.ready.toBoolean, "SB should not be ready when full!")
        println("[Test] Backpressure confirmed.")
        dut.io.lsuPushIn.valid #= false

        // 3. Complete and commit all stores
        for (robPtr <- storeTasks) {
          helper.euSignalCompletion(robPtr)
          helper.waitForCommitAndAck(robPtr)
        }

        // 4. Force a flush for the last address to ensure all data is written back
        helper.forceFlushAndWait(0x100 + (sbDepth - 1) * 4)

        // 5. Verify all data is in memory
        println("[Test] Verifying memory content...")
        for (i <- 0 until sbDepth) {
          helper.sramVerify(addr = 0x100 + i * 4, expectedData = 0x1aaa0000 + i)
        }

        println("Test 'SB_FullIntegration - Backpressure and Queue Full' PASSED")
      }
  }

  test("SB_FullIntegration - Flush non-committed stores") {
    val pCfg = createPConfig(robDepth = 16)
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, 4, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new TestHelper(dut)
        helper.init()

        println("[Test] Starting Flush Test")

        // 1. Initialize memory
        helper.sramWrite(0x100, 0)
        helper.sramWrite(0x104, 0)

        // 2. Issue two stores
        val robPtr1 = helper.cpuIssueMemoryOp(addr = 0x100, data = 0x11111111, be = 0xf)
        val robPtr2 = helper.cpuIssueMemoryOp(addr = 0x104, data = 0x22222222, be = 0xf, pc = 0x8004)

        // 3. Complete and commit only the first store
        helper.euSignalCompletion(robPtr1)
        helper.waitForCommitAndAck(robPtr1)

        // 4. Issue a ROB flush targeting the second, non-committed store
        ParallaxLogger.debug(s"[Test] Issuing a flush from ROB, targeting robPtr=$robPtr2")
        dut.io.robFlushIn.valid #= true
        dut.io.robFlushIn.payload.targetRobPtr #= robPtr2
        cd.waitSampling()
        dut.io.robFlushIn.valid #= false

        // 5. Force a flush for the first store's address to check its state
        helper.forceFlushAndWait(0x100)

        // 6. Verify memory: first store is there, second is not
        println("[Test] Verifying memory...")
        helper.sramVerify(addr = 0x100, expectedData = 0x11111111)
        helper.sramVerify(addr = 0x104, expectedData = 0) // Should NOT be written

        println("Test 'SB_FullIntegration - Flush non-committed stores' PASSED")
      }
  }

  test("SB_FullIntegration - Store to Load Forwarding") {
    val pCfg = createPConfig()
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, 4, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new TestHelper(dut)
        helper.init()

        println("[Test] Starting Store-to-Load Forwarding Test")

        val storeAddr = 0x200L
        val storeData = BigInt("ABCD1234", 16)

        // 1. Initialize memory with a known old value
        helper.sramWrite(storeAddr, 0)

        // 2. Push a store to SB, but DO NOT commit it
        helper.cpuIssueMemoryOp(addr = storeAddr, data = storeData, be = 0xf)
        cd.waitSampling(2)

        // 3. Issue a bypass query for the same address (full word)
        println("[Test] Querying for a full word load at the same address...")
        dut.io.bypassQueryAddr #= storeAddr
        dut.io.bypassQuerySize #= MemAccessSize.W
        cd.waitSampling()
        assert(dut.io.bypassDataOut.valid.toBoolean, "Bypass should be valid for a matching load")
        assert(dut.io.bypassDataOut.payload.hit.toBoolean, "Bypass should signal a hit")
        assert(
          dut.io.bypassDataOut.payload.data.toBigInt == storeData,
          s"Forwarded data incorrect. Got 0x${dut.io.bypassDataOut.payload.data.toBigInt
              .toString(16)}, Expected 0x${storeData.toString(16)}"
        )
        println("[Test] Full word forwarding successful.")

        // 4. Issue a bypass query for a byte within the word
        val byteAddr = storeAddr + 1 // Address 0x201
        val expectedByte = (storeData >> 8) & 0xff // Byte "CD"
        ParallaxLogger.debug(s"[Test] Querying for a byte load at 0x${byteAddr.toHexString}...")
        dut.io.bypassQueryAddr #= byteAddr
        dut.io.bypassQuerySize #= MemAccessSize.B
        cd.waitSampling()
        assert(dut.io.bypassDataOut.valid.toBoolean, "Bypass should be valid for a partial load")
        assert(dut.io.bypassDataOut.payload.hit.toBoolean, "Bypass should signal a hit for partial load")
        val forwardedByte = (dut.io.bypassDataOut.payload.data.toBigInt >> 8) & 0xff
        assert(
          forwardedByte == expectedByte,
          s"Forwarded byte incorrect. Got 0x${forwardedByte.toString(16)}, Expected 0x${expectedByte.toString(16)}"
        )
        println("[Test] Partial (byte) forwarding successful.")

        // 5. Query a non-matching address
        println("[Test] Querying a non-matching address...")
        dut.io.bypassQueryAddr #= 0x300L
        dut.io.bypassQuerySize #= MemAccessSize.W
        cd.waitSampling()
        assert(!dut.io.bypassDataOut.valid.toBoolean, "Bypass should not be valid for a non-matching address")
        println("[Test] Non-matching query correctly produced no hit.")

        // Reset query
        dut.io.bypassQueryAddr #= 0
        cd.waitSampling()

        println("Test 'SB_FullIntegration - Store to Load Forwarding' PASSED")
      }
  }

  thatsAll()
}
