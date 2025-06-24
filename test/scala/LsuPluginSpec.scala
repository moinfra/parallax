// filename: test/scala/lsu/LsuPluginSpec.scala
// command: testOnly test.scala.lsu.LsuPluginSpec
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
import parallax.execute.BypassPlugin

/**
 * >>> REFACTORED <<<
 * This plugin is now simpler. It connects services required by the TestHelper
 * to the DUT's internal services, bypassing the now-integrated LSU plugin.
 */
class LsuTestConnectionPlugin(tbIo: LsuTestBenchIo) extends Plugin {
  val setup = create early new Area {
    val robService = getService[ROBService[RenamedUop]]
    val dcService  = getService[DataCacheService]
    val prfService = getService[PhysicalRegFileService]
    val lsuService = getService[LsuService] // Get the LSU service

    // --- Connect LSU Input from Testbench ---
    lsuService.getInputPort() <> tbIo.lsuIn

    // --- ROB Connections ---
    val robAllocPorts = robService.getAllocatePorts(tbIo.pCfg.renameWidth)
    robAllocPorts(0).valid := tbIo.robAllocate.valid
    robAllocPorts(0).pcIn := tbIo.robAllocate.pcIn
    robAllocPorts(0).uopIn := tbIo.robAllocate.uopIn
    tbIo.canRobAllocate := robAllocPorts(0).isReady

    tbIo.robFlushIn <> robService.getFlushPort()

    // --- ROB Commit Monitoring ---
    val commitSlots = robService.getCommitSlots(tbIo.pCfg.commitWidth)
    val commitAcks = robService.getCommitAcks(tbIo.pCfg.commitWidth)
    tbIo.committedOps.valid := commitSlots(0).valid
    tbIo.committedOps.payload := commitSlots(0).entry
    commitAcks(0) := tbIo.robCommitAck

    // --- Test-Only PRF Connections for Verification ---
    val tbPrfReadPort = prfService.newReadPort()
    tbIo.tbPrfReadOut := tbPrfReadPort.rsp
    tbPrfReadPort.address := tbIo.tbPrfReadAddr
    tbPrfReadPort.valid   := tbIo.tbPrfReadValid

    val tbPrfWritePort = prfService.newWritePort()
    tbPrfWritePort <> tbIo.tbPrfWrite

    // --- D-Cache Status Monitoring ---
    tbIo.dcacheWritebackBusy := dcService.writebackBusy()
  }
}

/**
 * Top-level DUT for the full LSU integration testbench.
 */
class LsuFullIntegrationTestBench(
    val pCfg: PipelineConfig,
    val lsuCfg: LsuConfig,
    val dCacheCfg: DataCachePluginConfig,
    val axiConfig: Axi4Config
) extends Component {
  val io = LsuTestBenchIo(pCfg, lsuCfg, dCacheCfg)
  io.simPublic()

  val database = new DataBase
  val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCacheCfg)

  val framework = ProjectScope(database) on new Framework(
    Seq(
      new StoreBufferPlugin(pCfg, lsuCfg, dCacheParams, lsuCfg.sqDepth),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      // BypassPlugin is no longer directly needed by LSU test, but AGU uses it, so we keep it.
      new BypassPlugin[AguBypassData](payloadType = HardType(AguBypassData())),
      new AguPlugin(lsuCfg, supportPcRel = true),
      new DataCachePlugin(dCacheCfg),
      new LsuPlugin(pCfg, lsuCfg, dCacheParams, lsuCfg.lqDepth, lsuCfg.sqDepth),
      new SBDataMembusTestPlugin(axiConfig),
      new LsuTestConnectionPlugin(io)
    )
  )

  def getSramHandle(): SimulatedSRAM = framework.getService[SBDataMembusTestPlugin].getSram()
}

/**
 * >>> REFACTORED <<<
 * IO bundle for the LSU testbench. Now sends LsuInputCmd directly.
 */
case class LsuTestBenchIo(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheCfg: DataCachePluginConfig) extends Bundle {
  // --- Inputs to drive the DUT ---
  val lsuIn = master Stream(LsuInputCmd(pCfg))
  val robAllocate = in(
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
  val robCommitAck    = in Bool()
  
  // --- Outputs to observe DUT state ---
  val canRobAllocate      = out Bool()
  val committedOps = master(
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
  val dcacheWritebackBusy = out Bool()

  // --- Test-only verification ports ---
  val tbPrfWrite      = slave(PrfWritePort(pCfg.physGprIdxWidth, pCfg.dataWidth))
  val tbPrfReadAddr   = in UInt(pCfg.physGprIdxWidth)
  val tbPrfReadValid  = in Bool()
  val tbPrfReadOut    = out Bits(pCfg.dataWidth)
}

/**
 * >>> REFACTORED <<<
 * The definitive TestHelper for the integrated LSU.
 */
class LsuTestHelper(dut: LsuFullIntegrationTestBench)(implicit cd: ClockDomain) {
    val sram = dut.getSramHandle()
    var pcCounter = 0x8000L

    def init(): Unit = {
        dut.io.lsuIn.valid #= false
        dut.io.robAllocate.valid #= false
        dut.io.robCommitAck #= false
        dut.io.robFlushIn.valid #= false
        dut.io.tbPrfWrite.valid #= false
        dut.io.tbPrfReadValid #= false
        cd.waitSampling()
    }
    
    case class CpuOp(
        isLoad: Boolean,
        baseReg: Int,
        addrImm: BigInt,
        dataReg: Int = 0, // For Stores
        pdest: Int = 0,   // For Loads
        isFlush: Boolean = false, 
        var robPtr: BigInt = -1 // Will be filled by issue()
    )

    def issue(op: CpuOp): CpuOp = {
        // 1. Allocate in ROB
        dut.io.robAllocate.valid #= true
        dut.io.robAllocate.pcIn #= pcCounter
        val uop = dut.io.robAllocate.uopIn
        uop.setDefault()
        uop.decoded.uopCode := (if(op.isLoad) BaseUopCode.LOAD else BaseUopCode.STORE)
        uop.rename.physDest.idx := op.pdest
        uop.rename.physSrc1.idx := op.baseReg
        uop.rename.physSrc2.idx := op.dataReg // For stores
        cd.waitSamplingWhere(dut.io.canRobAllocate.toBoolean)
        val robPtr = dut.io.robAllocate.robPtr.toBigInt
        dut.io.robAllocate.valid #= false
        val currentPc = pcCounter
        pcCounter += 4
        
        // 2. Send to LSU
        val lsuPayload = dut.io.lsuIn.payload
        lsuPayload.baseReg #= op.baseReg
        lsuPayload.immediate #= op.addrImm
        lsuPayload.isLoad #= op.isLoad
        lsuPayload.isStore #= !op.isLoad
        lsuPayload.dataReg #= op.dataReg
        lsuPayload.pdest #= op.pdest
        lsuPayload.robPtr #= robPtr
        lsuPayload.accessSize #= MemAccessSize.W
        lsuPayload.isFlush #= op.isFlush
        lsuPayload.usePc #= false // Assume not PC-relative for now
        lsuPayload.pc #= currentPc
        
        dut.io.lsuIn.valid #= true
        cd.waitSamplingWhere(dut.io.lsuIn.ready.toBoolean)
        dut.io.lsuIn.valid #= false
        ParallaxLogger.debug(s"[Helper] Issued robPtr=$robPtr (${if(op.isLoad) "Load" else "Store"}) to LSU.")
        
        op.copy(robPtr = robPtr)
    }

    def waitForCommit(robPtr: BigInt): Unit = {
        val timedOut = cd.waitSamplingWhere(timeout = 500)(
          dut.io.committedOps.valid.toBoolean && dut.io.committedOps.payload.payload.uop.robPtr.toBigInt == robPtr
        )
        assert(!timedOut, s"Timeout waiting for robPtr=$robPtr to be committed.")
        dut.io.robCommitAck #= true
        cd.waitSampling()
        dut.io.robCommitAck #= false
    }

    def forceFlushAndWait(address: BigInt): Unit = {
        ParallaxLogger.info(s"[Helper] Forcing D-Cache flush for address 0x${address.toString(16)}")
        val flushOp = issue(CpuOp(isLoad = false, baseReg = 0, addrImm = address, isFlush = true))
        waitForCommit(flushOp.robPtr)
        if (dut.io.dcacheWritebackBusy.toBoolean) {
            cd.waitSamplingWhere(!dut.io.dcacheWritebackBusy.toBoolean)
        }
        cd.waitSampling(5)
        ParallaxLogger.info(s"[Helper] Flush and writeback complete.")
    }

    def prfWrite(preg: Int, data: BigInt): Unit = {
        dut.io.tbPrfWrite.valid #= true
        dut.io.tbPrfWrite.address #= preg
        dut.io.tbPrfWrite.data #= data
        cd.waitSampling()
        dut.io.tbPrfWrite.valid #= false
    }

    def prfVerify(preg: Int, expectedData: BigInt): Unit = {
        dut.io.tbPrfReadValid #= true
        dut.io.tbPrfReadAddr #= preg
        cd.waitSampling() // PRF read is asynchronous
        val readData = dut.io.tbPrfReadOut.toBigInt
        dut.io.tbPrfReadValid #= false
        assert(readData == expectedData, s"PRF verification failed at pReg $preg. Got 0x${readData.toString(16)}, Expected 0x${expectedData.toString(16)}")
    }
    
    // sramVerify and sramWrite can remain unchanged
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

    def sramWrite(addr: BigInt, data: BigInt): Unit = {
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= addr
      sram.io.tb_writeData #= data
      cd.waitSampling()
      sram.io.tb_writeEnable #= false
    }
}

class LsuPluginSpec extends CustomSpinalSimFunSuite {
  // --- Configuration helpers (remain unchanged) ---
  def createPConfig(commitWidth: Int = 1, robDepth: Int = 16, renameWidth: Int = 1): PipelineConfig = PipelineConfig(
    xlen = 32,
    commitWidth = commitWidth,
    robDepth = robDepth,
    renameWidth = renameWidth,
    physGprCount = 64,
    archGprCount = 32,
    fetchWidth = 1,
    dispatchWidth = 1,
    aluEuCount = 1,
    transactionIdWidth = 8,
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
    dcacheRefillCount = 2,
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

  test("LSU_Integration - Basic Load Hit") {
    val pCfg = createPConfig()
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new LsuFullIntegrationTestBench(pCfg, lsuCfg, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new LsuTestHelper(dut)
        helper.init()
        
        val baseReg = 5
        val pdestReg = 10
        val memAddr = 0x100
        val memData = 0xABCD1234L

        // 1. Setup PRF and SRAM state
        helper.prfWrite(baseReg, memAddr)
        helper.sramWrite(memAddr, memData)
        cd.waitSampling(5)

        // 2. Prime the cache by issuing a load to the target address
        val primeLoad = helper.issue(helper.CpuOp(isLoad = true, baseReg = baseReg, addrImm = 0, pdest = 20))
        helper.waitForCommit(primeLoad.robPtr)

        // 3. Now, issue the actual test Load, which should hit in the D-Cache
        val testLoad = helper.issue(helper.CpuOp(isLoad = true, baseReg = baseReg, addrImm = 0, pdest = pdestReg))
        helper.waitForCommit(testLoad.robPtr)

        // 4. Verify the result was written correctly to the destination register in the PRF
        helper.prfVerify(pdestReg, memData)
        
        println("Test 'LSU_Integration - Basic Load Hit' PASSED")
      }
  }

  test("LSU_Integration - Store to Load Forwarding") {
    val pCfg = createPConfig()
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new LsuFullIntegrationTestBench(pCfg, lsuCfg, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new LsuTestHelper(dut)
        helper.init()

        val baseReg = 7
        val dataReg = 8
        val pdestReg = 15
        val addr = 0x200
        val storeData = 0xDEADBEEFL
        
        // 1. Setup initial state: PRF has address and data, SRAM has an old value
        helper.prfWrite(baseReg, addr)
        helper.prfWrite(dataReg, storeData)
        helper.sramWrite(addr, 0) 
        cd.waitSampling(5)

        // 2. Issue a Store instruction
        val storeOp = helper.issue(helper.CpuOp(isLoad = false, baseReg = baseReg, addrImm = 0, dataReg = dataReg))
        
        // 3. Immediately issue a Load to the same address, before the Store commits
        val loadOp = helper.issue(helper.CpuOp(isLoad = true, baseReg = baseReg, addrImm = 0, pdest = pdestReg))
        
        // 4. Wait for both operations to commit. 
        helper.waitForCommit(storeOp.robPtr)
        helper.waitForCommit(loadOp.robPtr)
        
        // 5. THE CRITICAL VERIFICATION:
        helper.prfVerify(pdestReg, storeData)

        // 6. As a final check, flush the cache and verify the data is now in main memory
        helper.forceFlushAndWait(addr)
        helper.sramVerify(addr, storeData)

        println("Test 'LSU_Integration - Store to Load Forwarding' PASSED")
      }
  }
  
  thatsAll()
}
