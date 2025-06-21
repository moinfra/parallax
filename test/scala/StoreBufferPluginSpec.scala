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

class StoreBufferTestConnectionPlugin(tbIo: StoreBufferTestBenchIo, pCfg: PipelineConfig) extends Plugin {
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

    val dcacheService = getService[DataCacheService]
    val unusedLoadPort = dcacheService.newLoadPort(priority = 0)
    unusedLoadPort.cmd.valid := False
    unusedLoadPort.cmd.payload.assignDontCare()
    unusedLoadPort.translated.assignDontCare()
    unusedLoadPort.cancels := 0
  }
}
// --- MODIFICATION END ---

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

// --- Testbench with Real ROB, DataCache, and SimulatedSRAM backend ---
class StoreBufferFullIntegrationTestBench(
    val pCfg: PipelineConfig,
    val lsuCfg: LsuConfig,
    val sbDepth: Int,
    val dCacheCfg: DataCachePluginConfig,
    val axiConfig: Axi4Config
) extends Component {
  val io = StoreBufferTestBenchIo(pCfg, lsuCfg)
  io.simPublic()

  val database = new DataBase

  val framework = ProjectScope(database) on new Framework(
    Seq(
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new DataCachePlugin(dCacheCfg),
      new StoreBufferPlugin(pCfg, lsuCfg, sbDepth),
      new SBDataMembusTestPlugin(axiConfig),
      new StoreBufferTestConnectionPlugin(io, pCfg)
    )
  )

  def getSramHandle(): SimulatedSRAM = framework.getService[SBDataMembusTestPlugin].getSram()
}

case class StoreBufferTestBenchIo(pCfg: PipelineConfig, lsuCfg: LsuConfig) extends Bundle {
  val lsuPushIn = slave Stream (StoreBufferPushCmd(pCfg, lsuCfg))
  val bypassQueryAddr = in UInt (pCfg.pcWidth)
  val bypassQuerySize = in(MemAccessSize())
  val bypassDataOut = master Flow (StoreBufferBypassData(pCfg))
  val robAllocateIn = in (ROBAllocateSlot(
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
  ))
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
}
// --- MODIFICATION END ---

class StoreBufferPluginSpec extends CustomSpinalSimFunSuite {
  // ... (Test helpers and test cases remain unchanged) ...
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

  def createAxi4Config(pCfg: PipelineConfig): Axi4Config = {
    val axiConfig = Axi4Config(
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
    return axiConfig
  }

  def initSram(sram: SimulatedSRAM, addr: BigInt, data: BigInt)(implicit cd: ClockDomain): Unit = {
    sram.io.tb_writeEnable #= true
    sram.io.tb_writeAddress #= addr
    sram.io.tb_writeData #= data
    cd.waitSampling()
    sram.io.tb_writeEnable #= false
  }

  def verifySram(sram: SimulatedSRAM, addr: BigInt, expectedData: BigInt)(implicit cd: ClockDomain): Unit = {
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

  def driveRobAlloc(dut: StoreBufferFullIntegrationTestBench, pc: Long)(implicit cd: ClockDomain): BigInt = {
    dut.io.robAllocateIn.fire #= true
    dut.io.robAllocateIn.pcIn #= pc
    val uop = dut.io.robAllocateIn.uopIn
    uop.setDefault()
    uop.decoded.uopCode #= BaseUopCode.STORE
    cd.waitActiveEdge()
    val allocatedPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.fire #= false
    cd.waitSampling()
    allocatedPtr
  }

  def driveRobWriteback(dut: StoreBufferFullIntegrationTestBench, robPtr: BigInt)(implicit cd: ClockDomain): Unit = {
    dut.io.robWritebackIn.valid #= true
    dut.io.robWritebackIn.payload.robPtr #= robPtr
    dut.io.robWritebackIn.payload.hasException #= false
    cd.waitActiveEdge()
    dut.io.robWritebackIn.valid #= false
  }

  def driveSbPush(
      dut: StoreBufferFullIntegrationTestBench,
      addr: BigInt,
      data: BigInt,
      be: BigInt,
      robPtr: Int,
      accessSize: MemAccessSize.E = MemAccessSize.W,
      isIO: Boolean = false,
      hasEarlyEx: Boolean = false,
      earlyExCode: Int = 0
  )(implicit cd: ClockDomain): Unit = {
    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr; p.data #= data; p.be #= be; p.robPtr #= robPtr
    p.accessSize #= accessSize; p.isIO #= isIO
    p.hasEarlyException #= hasEarlyEx; p.earlyExceptionCode #= earlyExCode
    cd.waitSamplingWhere(dut.io.lsuPushIn.ready.toBoolean)
    dut.io.lsuPushIn.valid #= false
  }

  def initDutInputs(dut: StoreBufferFullIntegrationTestBench)(implicit cd: ClockDomain) {
    dut.io.lsuPushIn.valid #= false
    dut.io.robAllocateIn.fire #= false
    dut.io.robWritebackIn.valid #= false
    dut.io.robCommitAck #= false
    dut.io.bypassQueryAddr #= 0x0L
    dut.io.bypassQuerySize #= MemAccessSize.W
    dut.io.robFlushIn.valid #= false
    cd.waitSampling()
  }

  testOnly(s"SB_FullIntegration - Basic Store Lifecycle") {
    val pCfg = createPConfig(renameWidth = 1, commitWidth = 1, robDepth = 8)
    val lsuCfg = createLsuConfig(pCfg, lqDepth = 16, sqDepth = 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, DEFAULT_SB_DEPTH, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutInputs(dut)

        val sram = dut.getSramHandle()

        val storeAddr = 0x100L
        val storeData = BigInt("DEADBEEF", 16)
        val initialMemData = BigInt("CAFEBABE", 16)

        initSram(sram, storeAddr, initialMemData)
        cd.waitSampling(2)

        val robPtr = driveRobAlloc(dut, pc = 0x8000L)
        ParallaxLogger.debug(s"[Test] Allocated Store to ROB, got robPtr=$robPtr")

        driveSbPush(dut, addr = storeAddr, data = storeData, be = 0xf, robPtr = robPtr.toInt)
        ParallaxLogger.debug(s"[Test] Pushed Store (robPtr=$robPtr) to StoreBuffer.")

        cd.waitSampling(2)
        ParallaxLogger.debug("[Test] Simulated EU commit for robPtr=$robPtr.")
        verifySram(sram, storeAddr, initialMemData)

        driveRobWriteback(dut, robPtr)
        ParallaxLogger.debug(s"[Test] Simulated EU writeback for robPtr=$robPtr.")

        cd.waitSamplingWhere(timeout = 200)(
          dut.io.committedStores.valid.toBoolean && dut.io.committedStores.payload.payload.uop.robPtr.toBigInt == robPtr
        )
        ParallaxLogger.debug(s"[Test] Store (robPtr=$robPtr) is now at commit head.")

        dut.io.robCommitAck #= true
        cd.waitSampling()
        dut.io.robCommitAck #= false
        ParallaxLogger.debug(s"[Test] Acknowledged commit for robPtr=$robPtr.")

        val timeout = 20
        ParallaxLogger.debug(s"[Test] Waiting up to $timeout cycles for memory to be updated...")
        val isTimeout = cd.waitSamplingWhere(timeout) {
          sram.io.tb_readEnable #= true
          sram.io.tb_readAddress #= storeAddr
          cd.waitSampling(sram.config.readWaitCycles + 1)
          val currentData = sram.io.tb_readData.toBigInt
          sram.io.tb_readEnable #= false
          currentData == storeData
        }
        if (isTimeout) {
            fail(s"Memory verification timed out after $timeout cycles.")
        }
        ParallaxLogger.debug(s"[Test] Memory at 0x${storeAddr.toHexString} updated.")

        verifySram(sram, storeAddr, storeData)

        println("Test 'SB_FullIntegration - Basic Store Lifecycle' PASSED")
      }
  }

  // ==================================================================================
  // =================== NEW TEST CASES WRITTEN BY THE ASSISTANT ====================
  // ==================================================================================

  test("SB_FullIntegration - Backpressure and Queue Full") {
    val sbDepth = 4
    val pCfg = createPConfig(robDepth = 16)
    val lsuCfg = createLsuConfig(pCfg, lqDepth = 16, sqDepth = 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, sbDepth, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutInputs(dut)
        val sram = dut.getSramHandle()

        println("[Test] Starting Backpressure Test")

        val storeTasks = for (i <- 0 until sbDepth) yield {
          val addr = 0x100 + i * 4
          val data = 0xaaaa0000 + i
          val robPtr = driveRobAlloc(dut, 0x8000 + i * 4)
          (addr, data, robPtr)
        }

        // 1. Fill the Store Buffer
        ParallaxLogger.debug(s"[Test] Filling the SB with $sbDepth stores...")
        for (((addr, data, robPtr), i) <- storeTasks.zipWithIndex) {
          driveSbPush(dut, addr, data, 0xf, robPtr.toInt)
          ParallaxLogger.debug(s"[Test] Pushed store ${i + 1}/$sbDepth (robPtr=$robPtr)")
        }

        // 2. Verify SB is full and exerts backpressure
        println("[Test] Verifying backpressure...")
        dut.io.lsuPushIn.valid #= true
        dut.io.lsuPushIn.payload.addr #= 0x200
        dut.io.lsuPushIn.payload.data #= 0xffffffffL
        dut.io.lsuPushIn.payload.be #= 0xf
        dut.io.lsuPushIn.payload.robPtr #= 99 // Dummy robPtr
        cd.waitSampling(5)
        assert(!dut.io.lsuPushIn.ready.toBoolean, "SB should not be ready when full!")
        println("[Test] Backpressure confirmed. SB is not accepting new stores.")
        dut.io.lsuPushIn.valid #= false

        // 3. Commit all stores one by one
        println("[Test] Committing all stores...")
        for (((addr, data, robPtr), i) <- storeTasks.zipWithIndex) {
          driveRobWriteback(dut, robPtr)
          cd.waitSamplingWhere(
            dut.io.committedStores.valid.toBoolean && dut.io.committedStores.payload.payload.uop.robPtr.toBigInt == robPtr
          )
          dut.io.robCommitAck #= true
          cd.waitSampling()
          dut.io.robCommitAck #= false
          ParallaxLogger.debug(s"[Test] Committed store ${i + 1}/$sbDepth (robPtr=$robPtr)")
        }

        // 4. Verify all data is written to SRAM correctly and in order
        println("[Test] Verifying memory content...")
        cd.waitSampling(200) // Wait for all stores to drain to memory

        for (((addr, data, _), i) <- storeTasks.zipWithIndex) {
          verifySram(sram, addr, data)
          ParallaxLogger.debug(s"[Test] Verified memory for store ${i + 1}/$sbDepth at 0x${addr.toHexString}")
        }

        println("Test 'SB_FullIntegration - Backpressure and Queue Full' PASSED")
      }
  }

  test("SB_FullIntegration - Flush non-committed stores") {
    val sbDepth = 4
    val pCfg = createPConfig(robDepth = 16)
    val lsuCfg = createLsuConfig(pCfg, lqDepth = 16, sqDepth = 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, sbDepth, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val robFlushPort = dut.io.robFlushIn
        robFlushPort.valid #= false // Init

        initDutInputs(dut)
        val sram = dut.getSramHandle()

        println("[Test] Starting Flush Test")

        val storeAddr1 = 0x100L
        val storeData1 = 0x11111111L
        val storeAddr2 = 0x104L
        val storeData2 = 0x22222222L // This one will be flushed
        val storeAddr3 = 0x108L
        val storeData3 = 0x33333333L // This one will be flushed

        // Initialize memory
        initSram(sram, storeAddr1, 0)
        initSram(sram, storeAddr2, 0)
        initSram(sram, storeAddr3, 0)

        // 1. Push three stores
        val robPtr1 = driveRobAlloc(dut, 0x8000); driveSbPush(dut, storeAddr1, storeData1, 0xf, robPtr1.toInt)
        val robPtr2 = driveRobAlloc(dut, 0x8004); driveSbPush(dut, storeAddr2, storeData2, 0xf, robPtr2.toInt)
        val robPtr3 = driveRobAlloc(dut, 0x8008); driveSbPush(dut, storeAddr3, storeData3, 0xf, robPtr3.toInt)
        ParallaxLogger.debug(s"[Test] Pushed 3 stores (robPtrs: $robPtr1, $robPtr2, $robPtr3)")

        // 2. Commit only the first store
        driveRobWriteback(dut, robPtr1)
        cd.waitSamplingWhere(
          dut.io.committedStores.valid.toBoolean && dut.io.committedStores.payload.payload.uop.robPtr.toBigInt == robPtr1
        )
        dut.io.robCommitAck #= true
        cd.waitSampling()
        dut.io.robCommitAck #= false
        ParallaxLogger.debug(s"[Test] Committed store 1 (robPtr=$robPtr1)")

        // Wait for the first store to drain to memory to avoid race conditions in verification
        cd.waitSampling(200)

        // 3. Issue a flush from the ROB, targeting the second store
        ParallaxLogger.debug(s"[Test] Issuing a flush from ROB, targeting robPtr=$robPtr2")
        robFlushPort.valid #= true
        robFlushPort.payload.targetRobPtr #= robPtr2
        cd.waitSampling()
        robFlushPort.valid #= false

        // 4. Verify memory content
        cd.waitSampling(50) // Give time for any potential faulty writes to occur

        println("[Test] Verifying memory...")
        verifySram(sram, storeAddr1, storeData1)
        ParallaxLogger.debug(s"[Test] Verified store 1 (robPtr=$robPtr1) was written correctly.")
        verifySram(sram, storeAddr2, 0) // Should NOT be written
        ParallaxLogger.debug(s"[Test] Verified store 2 (robPtr=$robPtr2) was NOT written (flushed).")
        verifySram(sram, storeAddr3, 0) // Should also NOT be written
        ParallaxLogger.debug(s"[Test] Verified store 3 (robPtr=$robPtr3) was also NOT written (flushed).")

        println("Test 'SB_FullIntegration - Flush non-committed stores' PASSED")
      }
  }

  test("SB_FullIntegration - Store to Load Forwarding") {
    val sbDepth = 4
    val pCfg = createPConfig()
    val lsuCfg = createLsuConfig(pCfg, lqDepth = 16, sqDepth = 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, sbDepth, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutInputs(dut)
        val sram = dut.getSramHandle()

        println("[Test] Starting Store-to-Load Forwarding Test")

        val storeAddr = 0x200L
        val storeData = BigInt("ABCD1234", 16)
        val initialMemData = BigInt("00000000", 16)

        // 1. Initialize memory with a known old value
        initSram(sram, storeAddr, initialMemData)

        // 2. Push a store to SB, but DO NOT commit it
        val robPtr = driveRobAlloc(dut, 0x9000)
        driveSbPush(dut, storeAddr, storeData, 0xf, robPtr.toInt)
        ParallaxLogger.debug(s"[Test] Pushed store (robPtr=$robPtr, addr=0x${storeAddr.toHexString}) to SB.")

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
