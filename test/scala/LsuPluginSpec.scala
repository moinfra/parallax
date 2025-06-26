// filename: test/scala/lsu/LsuPluginSpec.scala
// command: test test.scala.lsu.LsuPluginSpec
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

// >>> FIX: 创建一个 LsuTestSetupPlugin，完全模仿 Agu 测试的 TestSetupPlugin
class LsuTestSetupPlugin(
    lsuIoSetup: LsuPort => Unit,
    robIoSetup: (ROBAllocateSlot[RenamedUop], Flow[ROBFlushPayload], ROBCommitSlot[RenamedUop], Bool) => Unit,
    prfIoSetup: (PrfReadPort, PrfWritePort) => Unit,
    dCacheIoSetup: Bool => Unit
) extends Plugin {
  val setup = create early new Area {
    val lsuService = getService[LsuService]
    val robService = getService[ROBService[RenamedUop]]
    val dcService  = getService[DataCacheService]
    val prfService = getService[PhysicalRegFileService]

    // Setup LSU port
    val lsuPort = lsuService.newLsuPort()
    lsuIoSetup(lsuPort)

    // Setup ROB ports
    val robAllocPort = robService.getAllocatePorts(1)(0)
    val robFlushPort = robService.getFlushPort()
    val robCommitSlot = robService.getCommitSlots(1)(0)
    val robCommitAck = robService.getCommitAcks(1)(0)
    robIoSetup(robAllocPort, robFlushPort, robCommitSlot, robCommitAck)
    
    // Setup PRF ports
    val prfReadPort = prfService.newReadPort()
    val prfWritePort = prfService.newWritePort()
    prfIoSetup(prfReadPort, prfWritePort)

    // Setup D-Cache ports
    dCacheIoSetup(dcService.writebackBusy())
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

  val framework = new Framework(
    Seq(
      new StoreBufferPlugin(pCfg, lsuCfg, dCacheParams, lsuCfg.sqDepth),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BypassPlugin[AguBypassData](payloadType = HardType(AguBypassData())),
      new AguPlugin(lsuCfg, supportPcRel = true),
      new DataCachePlugin(dCacheCfg),
      new LsuPlugin(pCfg, lsuCfg, dCacheParams, lsuCfg.lqDepth, lsuCfg.sqDepth),
      new TestOnlyMemSystemPlugin(axiConfig),
      // >>> FIX: 使用新的 TestSetupPlugin
      new LsuTestSetupPlugin(
        lsuIoSetup = lsuPort => {
          lsuPort.input << io.lsuIn
        },
        robIoSetup = (allocPort, flushPort, commitSlot, commitAck) => {
          allocPort.valid := io.robAllocate.valid
          allocPort.pcIn := io.robAllocate.pcIn
          allocPort.uopIn := io.robAllocate.uopIn
          io.robAllocate.robPtr := allocPort.robPtr
          io.robAllocate.ready := allocPort.ready

          flushPort <> io.robFlushIn

          io.committedOps.valid := commitSlot.valid
          io.committedOps.payload := commitSlot.entry
          commitAck := io.robCommitAck
        },
        prfIoSetup = (readPort, writePort) => {
          writePort <> io.tbPrfWrite

          readPort.address := io.tbPrfReadAddr
          readPort.valid := io.tbPrfReadValid
          io.tbPrfReadOut := readPort.rsp
        },
        dCacheIoSetup = writebackBusy => {
          io.dcacheWritebackBusy := writebackBusy
        }
      )
    )
  )

  def getSramHandle(): SimulatedSRAM = framework.getService[TestOnlyMemSystemPlugin].getSram()
}

/**
 * >>> REFACTORED <<<
 * IO bundle for the LSU testbench. Now sends LsuInputCmd directly.
 */
case class LsuTestBenchIo(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheCfg: DataCachePluginConfig) extends Bundle {
  // --- Inputs to drive the DUT ---
  val lsuIn = slave Stream(LsuInputCmd(pCfg))
  val robAllocate = master(
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
        uop.setDefaultForSim()
        uop.decoded.uopCode #= (if(op.isLoad) BaseUopCode.LOAD else BaseUopCode.STORE)
        uop.rename.physDest.idx #= op.pdest
        uop.rename.physSrc1.idx #= op.baseReg
        uop.rename.physSrc2.idx #= op.dataReg // For stores
        cd.waitSamplingWhere(dut.io.robAllocate.ready.toBoolean)
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

    def triggerRollback(targetRobPtr: BigInt): Unit = {
        ParallaxLogger.info(s"[Helper] Triggering precise rollback to robPtr=${targetRobPtr}")
        dut.io.robFlushIn.valid #= true
        dut.io.robFlushIn.payload.reason #= FlushReason.ROLLBACK_TO_ROB_IDX
        dut.io.robFlushIn.payload.targetRobPtr #= targetRobPtr
        cd.waitSampling()
        dut.io.robFlushIn.valid #= false
    }

    def waitForCommit(robPtr: BigInt): Unit = {
        val timedOut = cd.waitSamplingWhere(timeout = 200)(
          dut.io.committedOps.valid.toBoolean && dut.io.committedOps.payload.payload.uop.robPtr.toBigInt == robPtr
        )
        assert(!timedOut, s"Timeout waiting for robPtr=$robPtr to be committed.")
        dut.io.robCommitAck #= true
        cd.waitSampling()
        dut.io.robCommitAck #= false
    }

    def forceFlushAndWait(address: BigInt): Unit = {
        ParallaxLogger.info(s"[Helper] Forcing D-Cache flush for address 0x${address.toString(16)}")

        // --- FIX: Don't use immediate for full address ---
        val flushAddrReg = 63 // Use a high-numbered pReg as a scratch register
        prfWrite(flushAddrReg, address)

        val flushOp = issue(CpuOp(
            isLoad = false, 
            baseReg = flushAddrReg, // Use the register containing the address
            addrImm = 0,            // Immediate is now 0
            isFlush = true
        ))
        // --- END FIX ---
        
        waitForCommit(flushOp.robPtr)
        if (dut.io.dcacheWritebackBusy.toBoolean) {
            cd.waitSamplingWhere(!dut.io.dcacheWritebackBusy.toBoolean)
        }
        cd.waitSampling(60)
        ParallaxLogger.info(s"[Helper] Flush and writeback complete.")
    }

    def prfWrite(preg: Int, data: BigInt): Unit = {
        // --- 1. 写操作 ---
        dut.io.tbPrfWrite.valid #= true
        dut.io.tbPrfWrite.address #= preg
        dut.io.tbPrfWrite.data #= data
        cd.waitSampling()
        dut.io.tbPrfWrite.valid #= false
        
        // --- 2. 立即读回以验证 ---
        // 留一个周期的空隙，确保写操作稳定
        cd.waitSampling(1) 

        dut.io.tbPrfReadValid #= true
        dut.io.tbPrfReadAddr #= preg
        cd.waitSampling() // PRF是异步读，所以数据在本周期就有效

        val readbackData = dut.io.tbPrfReadOut.toBigInt
        dut.io.tbPrfReadValid #= false

        // --- 3. 断言 ---
        val success = readbackData == data
        ParallaxLogger.info(
            s"[Helper] prfWrite attempt: reg=${preg}, data=0x${(data.toString(16))}, " +
            s"readback=0x${(readbackData.toString(16))} -> " + 
            (if(success) L"SUCCESS" else L"FAILURE")
        )
        assert(success, s"PRF write verification failed for pReg $preg! Wrote 0x${data.toString(16)}, but read back 0x${readbackData.toString(16)}")

        cd.waitSampling(1) // 恢复状态
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
    aluEuCount = 0,
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
        val memAddr = BigInt("100", 16) // Changed from 0x100
        val memData = BigInt("ABCD1234", 16) // Changed from 0xABCD1234L

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

test("LSU_Integration_Simplest_Store_and_Verify") {
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

        // --- 1. 定义测试参数 ---
        val baseReg = 1
        val dataReg = 2
        val memAddr = BigInt("500", 16) // Changed from 0x500
        val storeData = BigInt("CAFEBABE", 16) // Changed from 0xCAFEBABEFL

        println(s"[Test] Starting Simplest Store Test. Target Addr: 0x${memAddr.toString(16)}, Data: 0x${storeData.toString(16)}")

        // --- 2. 准备初始状态 ---
        // 将基地址和要存储的数据写入物理寄存器文件(PRF)
        helper.prfWrite(baseReg, memAddr)
        helper.prfWrite(dataReg, storeData)
        // 确保SRAM中的初始值与我们要写入的值不同
        helper.sramWrite(memAddr, BigInt("00000000", 16)) // Changed from 0x00000000
        cd.waitSampling(5)

        // --- 3. 发出STORE指令 ---
        // 使用helper函数创建并发送一条store指令到LSU
        // isLoad=false, baseReg=1, addrImm=0, dataReg=2
        val storeOp = helper.issue(helper.CpuOp(isLoad = false, baseReg = baseReg, addrImm = 0, dataReg = dataReg))
        println(s"[Test] Store instruction issued with robPtr=${storeOp.robPtr}")

        // --- 4. 等待指令提交 ---
        // 模拟指令在流水线中执行完毕，并等待ROB按序提交它
        helper.waitForCommit(storeOp.robPtr)
        println(s"[Test] Store instruction (robPtr=${storeOp.robPtr}) has been committed by ROB.")

        // --- 5. 强制数据写回内存 ---
        // Store指令提交后，数据仍在D-Cache中（处于dirty状态）。
        // 我们需要一个内存屏障或缓存冲刷操作来确保它被写回到SRAM。
        // forceFlushAndWait 会发出一个flush指令，并等待它完成，包括可能的D-Cache写回。
        println("[Test] Forcing D-Cache to write data back to SRAM...")
        helper.forceFlushAndWait(memAddr)

        // --- 6. 最终验证 ---
        // 直接从模拟的SRAM中读取数据，验证是否与我们存入的值一致
        println("[Test] Verifying data in SRAM...")
        helper.sramVerify(memAddr, storeData)
        
        println("Test 'LSU_Integration_Simplest_Store_and_Verify' PASSED")
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
        val addr = BigInt("200", 16) // Changed from 0x200
        val storeData = BigInt("DEADBEEF", 16) // Changed from 0xDEADBEEFL
        
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
  
  test("LSU_Integration - Precise Rollback") {
    val pCfg = createPConfig(robDepth = 32) // Use a larger ROB to avoid wrap-around issues in test
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

        // --- 1. Define Test Parameters ---
        val initialMemVal = BigInt("11111111", 16)

        // 'Good' ops (pre-rollback)
        val goodStoreAddr = BigInt("A00", 16)
        val goodStoreData = BigInt("AAAAAAAA", 16)
        val goodStoreBaseReg = 1
        val goodStoreDataReg = 2
        
        val goodLoadAddr = BigInt("B00", 16)
        val goodLoadData = BigInt("BBBBBBBB", 16)
        val goodLoadBaseReg = 3
        val goodLoadPdestReg = 4

        // 'Bad' ops (on wrong path, should be cancelled)
        val badStoreAddr = BigInt("C00", 16)
        val badStoreData = BigInt("CCCCCCCC", 16)
        val badStoreBaseReg = 5
        val badStoreDataReg = 6

        val badLoadAddr = BigInt("D00", 16)
        val badLoadData = BigInt("DDDDDDDD", 16)
        val badLoadBaseReg = 7
        val badLoadPdestReg = 8

        // 'Recovery' op (on new correct path, after rollback)
        val recoveryLoadAddr = BigInt("E00", 16)
        val recoveryLoadData = BigInt("EEEEEEEE", 16)
        val recoveryLoadBaseReg = 9
        val recoveryLoadPdestReg = 10

        // --- 2. Setup Initial State ---
        helper.prfWrite(goodStoreBaseReg, goodStoreAddr)
        helper.prfWrite(goodStoreDataReg, goodStoreData)
        helper.prfWrite(goodLoadBaseReg, goodLoadAddr)
        helper.prfWrite(badStoreBaseReg, badStoreAddr)
        helper.prfWrite(badStoreDataReg, badStoreData)
        helper.prfWrite(badLoadBaseReg, badLoadAddr)
        helper.prfWrite(recoveryLoadBaseReg, recoveryLoadAddr)
        // Set initial value for bad pdest to detect if it gets overwritten
        helper.prfWrite(badLoadPdestReg, 0) 

        helper.sramWrite(goodLoadAddr, goodLoadData)
        helper.sramWrite(badStoreAddr, initialMemVal) // Should remain this value
        helper.sramWrite(badLoadAddr, badLoadData)
        helper.sramWrite(recoveryLoadAddr, recoveryLoadData)
        cd.waitSampling(5)

        // --- 3. Issue Instructions ---
        val goodStore = helper.issue(helper.CpuOp(isLoad = false, baseReg = goodStoreBaseReg, addrImm = 0, dataReg = goodStoreDataReg))
        val goodLoad  = helper.issue(helper.CpuOp(isLoad = true, baseReg = goodLoadBaseReg, addrImm = 0, pdest = goodLoadPdestReg))
        
        // These are on the wrong path
        val badStore = helper.issue(helper.CpuOp(isLoad = false, baseReg = badStoreBaseReg, addrImm = 0, dataReg = badStoreDataReg))
        val badLoad  = helper.issue(helper.CpuOp(isLoad = true, baseReg = badLoadBaseReg, addrImm = 0, pdest = badLoadPdestReg))

        // --- 4. Trigger the Rollback ---
        // We want to cancel badStore and everything after it.
        helper.triggerRollback(badStore.robPtr)
        cd.waitSampling(2) // Give flush time to propagate

        // --- 5. Issue instruction on the new correct path ---
        val recoveryLoad = helper.issue(helper.CpuOp(isLoad = true, baseReg = recoveryLoadBaseReg, addrImm = 0, pdest = recoveryLoadPdestReg))

        // --- 6. Verification ---
        // Wait for the good and recovery ops to commit
        helper.waitForCommit(goodStore.robPtr)
        helper.waitForCommit(goodLoad.robPtr)
        helper.waitForCommit(recoveryLoad.robPtr)
        
        // To be safe, wait a bit for any potential wrong-path stores to write back
        // A flush is needed to ensure goodStore is visible in SRAM
        helper.forceFlushAndWait(goodStoreAddr)
        cd.waitSampling(10)

        ParallaxLogger.info("[Test] Verifying results...")
        
        // Verify good ops succeeded
        helper.sramVerify(goodStoreAddr, goodStoreData)
        helper.prfVerify(goodLoadPdestReg, goodLoadData)

        // Verify recovery op succeeded
        helper.prfVerify(recoveryLoadPdestReg, recoveryLoadData)

        // CRITICAL: Verify bad ops were cancelled and had no effect
        helper.sramVerify(badStoreAddr, initialMemVal) // Memory should NOT have been written
        helper.prfVerify(badLoadPdestReg, 0)          // Register should NOT have been written

        println("Test 'LSU_Integration - Precise Rollback' PASSED")
      }
  }
  thatsAll()
}
