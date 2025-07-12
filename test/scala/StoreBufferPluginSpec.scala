// filename: test/scala/lsu/StoreBufferPluginSpec.scala
// cmd: testOnly test.scala.lsu.StoreBufferPluginSpec
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
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4, Axi4CrossbarFactory}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.amba4.axi.Axi4Shared

// SGMB服务接口，用于测试时提供MMIO端口
trait SgmbService extends Service with LockedImpl {
  def newReadPort(): SplitGmbReadChannel
  def newWritePort(): SplitGmbWriteChannel
}

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
    sbPlugin.getStoreQueueQueryPort() <> tbIo.sqQueryPort

    // Drive ROB allocation from Testbench IO
    val robAllocPorts = robService.getAllocatePorts(pCfg.renameWidth)
    robAllocPorts(0).valid := tbIo.robAllocateIn.valid
    robAllocPorts(0).pcIn := tbIo.robAllocateIn.pcIn
    robAllocPorts(0).uopIn := tbIo.robAllocateIn.uopIn

    tbIo.canRobAllocate := robService.getCanAllocateVec(pCfg.renameWidth)(0)
    tbIo.allocatedRobPtr := robAllocPorts(0).robPtr

    tbIo.robFlushIn <> robService.newFlushPort()

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
  * for the DataCache to connect to via the DBusService, and also provides SGMB
  * interfaces for MMIO operations.
  */
class TestOnlyMemSystemPluginForSB(axiConfig: Axi4Config) extends Plugin with DBusService with SgmbService {
  import scala.collection.mutable.ArrayBuffer
  
  // SGMB 部分保持不变
  private val readPorts = ArrayBuffer[SplitGmbReadChannel]()
  private val writePorts = ArrayBuffer[SplitGmbWriteChannel]()
  
  override def newReadPort(): SplitGmbReadChannel = {
    this.framework.requireEarly()
    val sgmbConfig = GenericMemoryBusConfig(
      addressWidth = axiConfig.addressWidth bits,
      dataWidth = axiConfig.dataWidth bits,
      useId = true,
      idWidth = axiConfig.idWidth bits
    )
    val port = slave(SplitGmbReadChannel(sgmbConfig))
    readPorts += port
    port
  }
  
  override def newWritePort(): SplitGmbWriteChannel = {
    this.framework.requireEarly()
    val sgmbConfig = GenericMemoryBusConfig(
      addressWidth = axiConfig.addressWidth bits,
      dataWidth = axiConfig.dataWidth bits,
      useId = true,
      idWidth = axiConfig.idWidth bits
    )
    val port = slave(SplitGmbWriteChannel(sgmbConfig))
    writePorts += port
    port
  }


  override def getBus(): Axi4 = {
    println("CALL getBus.")
    null
  }

  val hw = create early new Area {
    // SRAM 和控制器定义
    private val sramSize = BigInt("4000", 16)
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
  
  val logic = create late new Area {
    lock.await()
    val dcacheMaster = getService[DataCachePlugin].getDCacheMaster
    // SGMB 桥接器部分
    val sgmbConfig = GenericMemoryBusConfig(
      addressWidth = axiConfig.addressWidth bits,
      dataWidth = axiConfig.dataWidth bits,
      useId = true,
      idWidth = axiConfig.idWidth bits
    )
    val readBridges = readPorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    val writeBridges = writePorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    
    // ... SGMB 桥接器连接 ... (这部分是正确的)
    for ((port, bridge) <- readPorts.zip(readBridges)) {
      bridge.io.gmbIn.read.cmd <> port.cmd
      bridge.io.gmbIn.read.rsp <> port.rsp
      bridge.io.gmbIn.write.cmd.setIdle()
      bridge.io.gmbIn.write.rsp.ready := True
    }
    for ((port, bridge) <- writePorts.zip(writeBridges)) {
      bridge.io.gmbIn.write.cmd <> port.cmd
      bridge.io.gmbIn.write.rsp <> port.rsp
      bridge.io.gmbIn.read.cmd.setIdle()
      bridge.io.gmbIn.read.rsp.ready := True
    }
    val sramMasters = writeBridges.map(_.io.axiOut) ++ readBridges.map(_.io.axiOut) ++ Seq(dcacheMaster)

      val crossbar = Axi4CrossbarFactory()
      val sramSize = BigInt("4000", 16)
      crossbar.addSlave(hw.ctrl.io.axi, SizeMapping(0x0000L, sramSize))
      for (master <- sramMasters) {
        crossbar.addConnection(master, Seq(hw.ctrl.io.axi))
      }
      crossbar.build()
  
  }

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
  
  // 创建MMIO配置，与AXI4配置匹配
  val mmioConfig = GenericMemoryBusConfig(
    addressWidth = axiConfig.addressWidth bits,
    dataWidth = axiConfig.dataWidth bits,
    useId = true,
    idWidth = axiConfig.idWidth bits
  )

  val framework = ProjectScope(database) on new Framework(
    Seq(
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new DataCachePlugin(dCacheCfg),
      new StoreBufferPlugin(pCfg, lsuCfg, dCacheParams, sbDepth, Some(mmioConfig)),
      new TestOnlyMemSystemPluginForSB(axiConfig),
      new StoreBufferTestConnectionPlugin(io, pCfg, dCacheCfg)
    )
  )

  def getSramHandle(): SimulatedSRAM = framework.getService[TestOnlyMemSystemPluginForSB].getSram()
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
  val sqQueryPort = slave(SqQueryPort(lsuCfg, pCfg))

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
  val dataWidthBytes = dut.pCfg.dataWidth.value / 8

  def init(): Unit = {
    dut.io.lsuPushIn.valid #= false
    dut.io.robAllocateIn.valid #= false
    dut.io.robWritebackIn.valid #= false
    dut.io.robCommitAck #= false
    dut.io.bypassQueryAddr #= 0x0L
    dut.io.bypassQuerySize #= MemAccessSize.W
    dut.io.robFlushIn.valid #= false
    dut.io.committedStores.valid #= false
    dut.io.sqQueryPort.cmd.valid #= false
    sram.io.tb_writeEnable #= false
    sram.io.tb_readEnable #= false
    cd.waitSampling()
  }

  /** Issues a store operation, accurately modeling how the LSU would prepare it.
    * @param addr The exact address for the store.
    * @param data The data to be stored, MUST be right-aligned.
    * @param size The size of the access (B, H, W).
    * @return The allocated ROB pointer.
    */
  def cpuIssueStore(addr: BigInt, data: BigInt, size: MemAccessSize.E, pc: Long = 0x8000): BigInt = {
    // 1. Allocate a ROB entry
    dut.io.robAllocateIn.valid #= true
    dut.io.robAllocateIn.pcIn #= pc
    dut.io.robAllocateIn.uopIn.decoded.uopCode #= BaseUopCode.STORE
    cd.waitActiveEdge()
    val robPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.valid #= false
    cd.waitSampling()
    ParallaxLogger.debug(s"[Helper] CPU allocated robPtr=$robPtr for store at 0x${addr.toString(16)}")

    // 2. Prepare the store command as the LSU would
    val byteOffset = addr.toInt & (dataWidthBytes - 1)
    val be = MemAccessSize.toByteEnable_sw(size, byteOffset, dataWidthBytes)
    val alignedData = data << (byteOffset * 8)

    // 3. Push the command to the Store Buffer
    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr
    p.data #= alignedData
    p.be #= be
    p.robPtr #= robPtr
    p.accessSize #= size
    p.isIO #= false
    p.hasEarlyException #= false
    p.isFlush #= false
    val timeout = cd.waitSamplingWhere(timeout = 30)(dut.io.lsuPushIn.ready.toBoolean)
    if (timeout) {
      assert(false, "timeout ")
    }
    dut.io.lsuPushIn.valid #= false
    ParallaxLogger.debug(s"[Helper] Pushed op (robPtr=$robPtr, addr=0x${addr.toString(16)}, data=0x${alignedData
        .toString(16)}, be=0x${be.toString(16)}, size=$size) to SB.")
    robPtr
  }

  def cpuIssueFullWordStore(
      addr: BigInt,
      data: BigInt = 0,
      be: BigInt = 0,
      pc: Long = 0x8000,
      isFlush: Boolean = false
  ): BigInt = {
    dut.io.robAllocateIn.valid #= true
    dut.io.robAllocateIn.pcIn #= pc
    val uop = dut.io.robAllocateIn.uopIn
    uop.setDefault()
    uop.decoded.uopCode #= BaseUopCode.STORE
    cd.waitActiveEdge()
    val robPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.valid #= false
    cd.waitSampling()
    ParallaxLogger.debug(s"[Helper] CPU allocated robPtr=$robPtr for op at 0x${addr.toString(16)}")

    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr; p.data #= data; p.be #= be; p.robPtr #= robPtr
    p.accessSize #= MemAccessSize.W
    p.isIO #= false
    p.hasEarlyException #= false
    p.isFlush #= isFlush
    val timeout = cd.waitSamplingWhere(timeout = 100)(dut.io.lsuPushIn.ready.toBoolean)
    if (timeout) {
      assert(false, "timeout ")
    }
    dut.io.lsuPushIn.valid #= false
    ParallaxLogger.debug(s"[Helper] Pushed op (robPtr=$robPtr) to StoreBuffer.")
    robPtr
  }

  /** Issues a MMIO store operation to the StoreBuffer.
    * Similar to cpuIssueFullWordStore but with isIO=true.
    */
  def cpuIssueMMIOStore(
      addr: BigInt,
      data: BigInt = 0,
      be: BigInt = 0xF,
      pc: Long = 0x8000
  ): BigInt = {
    dut.io.robAllocateIn.valid #= true
    dut.io.robAllocateIn.pcIn #= pc
    val uop = dut.io.robAllocateIn.uopIn
    uop.setDefault()
    uop.decoded.uopCode #= BaseUopCode.STORE
    cd.waitActiveEdge()
    val robPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.valid #= false
    cd.waitSampling()
    ParallaxLogger.debug(s"[Helper] CPU allocated robPtr=$robPtr for MMIO op at 0x${addr.toString(16)}")

    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr; p.data #= data; p.be #= be; p.robPtr #= robPtr
    p.accessSize #= MemAccessSize.W
    p.isIO #= true  // 关键：设置为MMIO操作
    p.hasEarlyException #= false
    p.isFlush #= false
    val timeout = cd.waitSamplingWhere(timeout = 100)(dut.io.lsuPushIn.ready.toBoolean)
    if (timeout) {
      assert(false, "timeout waiting for StoreBuffer to accept MMIO store")
    }
    dut.io.lsuPushIn.valid #= false
    ParallaxLogger.debug(s"[Helper] Pushed MMIO op (robPtr=$robPtr) to StoreBuffer.")
    robPtr
  }

  /** Issues a query to the SqQueryPort and immediately checks the response
    * in the same clock cycle, correctly testing combinational logic.
    * @param comment A string to identify the test case in logs.
    */
  def queryAndCheck(
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      shouldHit: Boolean,
      expectedData: BigInt = 0,
      shouldHaveDep: Boolean = false,
      comment: String = ""
  ): Unit = {
    // Drive the command signals
    val sq_cmd = dut.io.sqQueryPort.cmd
    sq_cmd.valid #= true
    sq_cmd.payload.robPtr #= loadRobPtr
    sq_cmd.payload.address #= loadAddr
    sq_cmd.payload.size #= loadSize

    // In the SAME cycle, check the response.
    // waitSampling(0) or just proceeding is fine, as sim steps are discrete.
    val rsp = dut.io.sqQueryPort.rsp
    val checkComment = if (comment.nonEmpty) s" ($comment)" else ""
    sleep(1)
    // Perform assertions immediately
    assert(
      rsp.hit.toBoolean == shouldHit,
      s"RSP.hit mismatch$checkComment. Got ${rsp.hit.toBoolean}, expected $shouldHit"
    )
    if (shouldHit) {
      assert(
        rsp.data.toBigInt == expectedData,
        s"RSP.data mismatch$checkComment. Got 0x${rsp.data.toBigInt.toString(16)}, expected 0x${expectedData.toString(16)}"
      )
    }
    assert(
      rsp.olderStoreMatchingAddress.toBoolean == shouldHaveDep,
      s"RSP.olderStoreMatchingAddress mismatch$checkComment. Got ${rsp.olderStoreMatchingAddress.toBoolean}, expected $shouldHaveDep"
    )

    // Wait one cycle with the command de-asserted to clean up for the next test
    cd.waitSampling()
    sq_cmd.valid #= false
    cd.waitSampling()
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
    val timedOut = cd.waitSamplingWhere(timeout = 20)(
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
    val flushRobPtr = cpuIssueFullWordStore(addr = address, pc = 0x9000, isFlush = true)
    euSignalCompletion(flushRobPtr)
    waitForCommitAndAck(flushRobPtr)

    if (dut.io.dcacheWritebackBusy.toBoolean) {
      ParallaxLogger.info(s"[Helper] D-Cache is busy with writeback, waiting...")
      cd.waitSamplingWhere(!dut.io.dcacheWritebackBusy.toBoolean)
    }
    cd.waitSampling(1)
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
    lsuEuCount = 0,
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
        val storeRobPtr = helper.cpuIssueFullWordStore(addr = storeAddr, data = storeData, be = 0xf)

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
          helper.cpuIssueFullWordStore(
            addr = 0x100 + i * 4,
            data = BigInt("1aaa0000", 16) + i,
            be = 0xf,
            pc = 0x8000 + i * 4
          )
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
          helper.sramVerify(addr = 0x100 + i * 4, expectedData = BigInt("1aaa0000", 16) + i)
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

      println("[Test] Starting 'Flush non-committed stores' Test")

        // === Phase 1: Setup ===
        val addr1 = 0x100L
        val addr2 = 0x104L
        val initialVal = 0L
        val data1 = BigInt("11111111", 16)
        val data2 = BigInt("22222222", 16)
        
        helper.sramWrite(addr1, initialVal)
        helper.sramWrite(addr2, initialVal)
        cd.waitSampling(5)

        val robPtr1 = helper.cpuIssueFullWordStore(addr = addr1, data = data1, be = 0xf)
        val robPtr2 = helper.cpuIssueFullWordStore(addr = addr2, data = data2, be = 0xf)
        helper.euSignalCompletion(robPtr1)
        helper.waitForCommitAndAck(robPtr1)

        // === Phase 2: The DIAGNOSTIC CHANGE ===
        // 为了“欺骗”ROB，我们在这里发出完成信号
        // 这样ROB在处理被冲刷的robPtr2时，就不会因为等待完成信号而卡住
        println(s"[DIAGNOSTIC] Signaling completion for robPtr=$robPtr2 BEFORE flushing it.")
        helper.euSignalCompletion(robPtr2)
        
        // 现在再发出流水线冲刷信号
        println(s"[Test] Issuing Pipeline Flush targeting robPtr=$robPtr2.")
        dut.io.robFlushIn.valid #= true
        dut.io.robFlushIn.payload.targetRobPtr #= robPtr2
        dut.io.robFlushIn.payload.reason #= FlushReason.ROLLBACK_TO_ROB_IDX

        cd.waitSampling()
        dut.io.robFlushIn.valid #= false
        cd.waitSampling(10) // Give it a moment

        // === Phase 3: Verification ===
        // 发出一个内存屏障来同步D-Cache
        println("[Test] Issuing a Memory Flush to synchronize D-Cache for verification.")
        helper.forceFlushAndWait(addr1)

        println("[Test] Verifying final memory state...")
        helper.sramVerify(addr = addr1, expectedData = data1)
        // 验证Store(1)的数据没有被写入，因为即使它完成了，ROB也应该把它作为冲刷指令处理掉，不让它提交
        helper.sramVerify(addr = addr2, expectedData = initialVal) 

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
        helper.cpuIssueFullWordStore(addr = storeAddr, data = storeData, be = 0xf)
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

  test("SB_FullIntegration - MMIO Store Operations") {
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

        println("[Test] Starting MMIO Store Operations Test")

        // Test data
        val mmioAddr = 0x1000L  // MMIO address range
        val mmioData = BigInt("BAADC0DE", 16)
        val regularAddr = 0x100L  // Regular memory address
        val regularData = BigInt("DEADBEEF", 16)
        
        // 1. Issue a regular store operation first (as baseline)
        println("[Test] Issuing regular store operation...")
        val regularStoreRobPtr = helper.cpuIssueFullWordStore(addr = regularAddr, data = regularData, be = 0xf)
        helper.euSignalCompletion(regularStoreRobPtr)
        helper.waitForCommitAndAck(regularStoreRobPtr)
        
        // 2. Issue MMIO store operation
        println("[Test] Issuing MMIO store operation...")
        val mmioStoreRobPtr = helper.cpuIssueMMIOStore(addr = mmioAddr, data = mmioData, be = 0xf)
        
        // 3. Complete the MMIO operation
        helper.euSignalCompletion(mmioStoreRobPtr)
        helper.waitForCommitAndAck(mmioStoreRobPtr)
        
        // 4. Verify regular store worked (flush and check SRAM)
        helper.forceFlushAndWait(regularAddr)
        helper.sramVerify(regularAddr, regularData)
        
        // 5. Test MMIO and regular store mixed operations
        println("[Test] Testing mixed MMIO and regular operations...")
        val mixedOps = Seq(
          (0x200L, BigInt("12345678", 16), false), // Regular
          (0x1004L, BigInt("87654321", 16), true), // MMIO  
          (0x204L, BigInt("ABCDEF00", 16), false), // Regular
          (0x1008L, BigInt("FEDCBA09", 16), true)  // MMIO
        )
        
        val mixedRobPtrs = mixedOps.map { case (addr, data, isMMIO) =>
          if (isMMIO) {
            helper.cpuIssueMMIOStore(addr = addr, data = data, be = 0xf)
          } else {
            helper.cpuIssueFullWordStore(addr = addr, data = data, be = 0xf)
          }
        }
        
        // Complete all mixed operations
        for (robPtr <- mixedRobPtrs) {
          helper.euSignalCompletion(robPtr)
          helper.waitForCommitAndAck(robPtr)
        }
        
        // 6. Verify regular memory stores worked
        println("[Test] Verifying regular memory stores...")
        helper.forceFlushAndWait(0x204L)
        helper.sramVerify(0x200L, BigInt("12345678", 16))
        helper.sramVerify(0x204L, BigInt("ABCDEF00", 16))
        
        println("Test 'SB_FullIntegration - MMIO Store Operations' PASSED")
      }
  }

  test("SB_FullIntegration - Store to Load Forwarding (SqQueryPort)") {
    val pCfg = createPConfig(robDepth = 32) // Use a larger ROB for wrap-around test
    val lsuCfg = createLsuConfig(pCfg, 16, 16)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withWave
      .compile(new StoreBufferFullIntegrationTestBench(pCfg, lsuCfg, 8, dCacheCfg, axiConfig)) // Use 8-entry SB
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new TestHelper(dut)
        helper.init()
        cd.waitSampling()

        println("[Test] Starting Advanced Store-to-Load Forwarding Test (SqQueryPort)")

        // --- Scenario 1: Youngest-First Priority ---
        println("\n--- Scenario 1: Youngest-First Priority ---")
        val addr1 = BigInt("100", 16)
        val robPtr_s1_old = helper.cpuIssueStore(addr = addr1, data = BigInt("AAAAAAAA", 16), size = MemAccessSize.W)
        val robPtr_s1_new = helper.cpuIssueStore(addr = addr1, data = BigInt("BBBBBBBB", 16), size = MemAccessSize.W)

        helper.queryAndCheck(
          loadRobPtr = robPtr_s1_new + 1,
          loadAddr = addr1,
          loadSize = MemAccessSize.W,
          shouldHit = true,
          expectedData = BigInt("BBBBBBBB", 16),
          comment = "Youngest-First"
        )
        println("Youngest-First Priority PASSED")

        // --- Scenario 2: Data Merging from two stores ---
        println("\n--- Scenario 2: Data Merging ---")
        val addr2 = BigInt("200", 16)
        val robPtr_s2_lo = helper.cpuIssueStore(addr = addr2, data = BigInt("1122", 16), size = MemAccessSize.H)
        val robPtr_s2_hi = helper.cpuIssueStore(addr = addr2 + 2, data = BigInt("3344", 16), size = MemAccessSize.H)

        helper.queryAndCheck(
          loadRobPtr = robPtr_s2_hi + 1,
          loadAddr = addr2,
          loadSize = MemAccessSize.W,
          shouldHit = true,
          expectedData = BigInt("33441122", 16),
          comment = "Data Merging"
        )
        println("Data Merging PASSED")

        // --- Scenario 3: Ordering Violation (Load is Older) ---
        println("\n--- Scenario 3: Ordering Violation ---")
        val addr3 = BigInt("300", 16)
        val robPtr_s3 = helper.cpuIssueStore(addr = addr3, data = BigInt("CCCCCCCC", 16), size = MemAccessSize.W)

        helper.queryAndCheck(
          loadRobPtr = robPtr_s3 - 1,
          loadAddr = addr3,
          loadSize = MemAccessSize.W,
          shouldHit = false,
          comment = "Ordering Violation"
        )
        println("Ordering Violation PASSED")

        // --- Scenario 4: Full Containment Check ---
        println("\n--- Scenario 4: Full Containment Check ---")
        val addr4 = BigInt("400", 16)
        val robPtr_s4_byte = helper.cpuIssueStore(addr = addr4 + 1, data = BigInt("DD", 16), size = MemAccessSize.B)

        helper.queryAndCheck(
          loadRobPtr = robPtr_s4_byte + 1,
          loadAddr = addr4,
          loadSize = MemAccessSize.W,
          shouldHit = false,
          shouldHaveDep = true,
          comment = "Full Containment"
        )
        println("Full Containment Check PASSED")

        // --- Scenario 5: Merging from Overwriting Stores ---
        println("\n--- Scenario 5: Merging from Overwriting Stores ---")
        val addr5 = BigInt("500", 16)
        val robPtr_s5_word = helper.cpuIssueStore(addr = addr5, data = BigInt("AAAAAAAA", 16), size = MemAccessSize.W)
        val robPtr_s5_byte = helper.cpuIssueStore(addr = addr5 + 1, data = BigInt("CC", 16), size = MemAccessSize.B)

        helper.queryAndCheck(
          loadRobPtr = robPtr_s5_byte + 1,
          loadAddr = addr5,
          loadSize = MemAccessSize.W,
          shouldHit = true,
          expectedData = BigInt("AAAACCAA", 16),
          comment = "Overwrite Merge"
        )
        println("Merging from Overwriting Stores PASSED")

      // --- Cleanup Phase: Commit all stores from Scenarios 1-5 to clean the pipeline state ---
        println("\n--- Cleanup: Committing outstanding stores from previous scenarios ---")
        val previousStores = Seq(
            robPtr_s1_old, robPtr_s1_new, robPtr_s2_lo, robPtr_s2_hi, robPtr_s3, robPtr_s4_byte, robPtr_s5_word, robPtr_s5_byte
        )
        // First, signal completion for all of them (as if they all finished execution out-of-order)
        for (ptr <- previousStores) {
            helper.euSignalCompletion(ptr)
        }
        // Then, wait for them to commit in-order, which will empty the Store Buffer.
        for (ptr <- previousStores) {
            helper.waitForCommitAndAck(ptr)
        }
        // Ensure all data is written from D-Cache to SRAM before proceeding
        helper.forceFlushAndWait(addr5)
        println("--- Cleanup complete. Starting wrap-around test. ---")

         // --- Scenario 6: ROB Pointer Wrap-around ---
        println("\n--- Scenario 6: ROB Pointer Wrap-around ---")
        assert(dut.io.lsuPushIn.ready.toBoolean, "SB should be ready after cleanup")
        val storesToIssue = 54
        
        // Use a simple, robust, serial loop to avoid race conditions between testbench and DUT
        for(i <- 0 until storesToIssue){
            val robPtr = helper.cpuIssueStore(
              addr = BigInt("1000", 16) + i * 4,
              data = BigInt(i),
              size = MemAccessSize.W
            )
            helper.euSignalCompletion(robPtr)
            helper.waitForCommitAndAck(robPtr)
            // This log is less noisy and more informative
            if ((i + 1) % 5 == 0) {
              println(s"[Main] Processed instruction ${i + 1}/${storesToIssue}")
            }
        }
        
        println(s"All $storesToIssue instructions processed successfully!")

        // Now that the ROB has been filled and wrapped around, test forwarding again.
        val addr6_old_gen = 0x600L
        val addr6_new_gen = 0x604L

        val robPtr_s6_old_gen = helper.cpuIssueStore(addr = addr6_old_gen, data = 0x11111111, size = MemAccessSize.W)
        val robPtr_s6_new_gen = helper.cpuIssueStore(addr = addr6_new_gen, data = 0x22222222, size = MemAccessSize.W)
        // Issue another instruction to get a valid ROB pointer for a "load" that is younger than the two stores.
        val loadRobPtr = helper.cpuIssueStore(addr = 0, data = 0, size = MemAccessSize.W) 

        println(
          s"Test pointers: old_gen_ptr=${robPtr_s6_old_gen}, new_gen_ptr=${robPtr_s6_new_gen}, load_ptr=${loadRobPtr}"
        )
        assert(robPtr_s6_old_gen > robPtr_s6_new_gen, "ROB pointer should have wrapped around")

        println("Verifying forwarding across ROB wrap-around...")
        helper.queryAndCheck(
          loadRobPtr = loadRobPtr,
          loadAddr = addr6_old_gen,
          loadSize = MemAccessSize.W,
          shouldHit = true,
          expectedData = 0x11111111,
          comment = "Wrap-around old gen"
        )
        helper.queryAndCheck(
          loadRobPtr = loadRobPtr,
          loadAddr = addr6_new_gen,
          loadSize = MemAccessSize.W,
          shouldHit = true,
          expectedData = 0x22222222,
          comment = "Wrap-around new gen"
        )

        println("ROB Pointer Wrap-around PASSED")
        println("\nAll advanced forwarding scenarios PASSED.")
      }
  }

  thatsAll()
}
