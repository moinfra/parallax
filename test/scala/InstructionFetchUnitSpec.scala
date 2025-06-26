// filename: test/scala/ifu/InstructionFetchUnitSpec.scala
// cmd testOnly test.scala.ifu.InstructionFetchUnitSpec
package test.scala.ifu

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.ifu._ // IFUPlugin, IFUService, IFetchPort, IFetchCmd, IFetchRsp
import parallax.components.memory._
import parallax.components.dcache2._
import parallax.utilities.ParallaxLogger

import scala.collection.mutable
import scala.util.Random
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.components.memory.IFetchPort
import parallax.utilities._
import test.scala.lsu.TestOnlyMemSystemPlugin

/** Test Setup Plugin for IFU tests.
  */
class IFUTestSetupPlugin(io: IFUTBIO) extends Plugin {
  ParallaxLogger.log(s"[IFUTestSetupPlugin] Creating IFUTestSetupPlugin")
  val setup = create early new Area {
    val ifuService = getService[IFUService]
    val dcService = getService[DataCacheService]
    ifuService.retain()
    dcService.retain()

    val ifuPort = ifuService.newFetchPort()
    ifuPort <> io.tbCpuFetchPort

    io.tbDcacheWritebackBusy <> dcService.writebackBusy()

    val unusedStorePort = dcService.newStorePort()
    unusedStorePort.cmd.valid := False
    unusedStorePort.cmd.payload.assignDontCare()

  }

  val logic = create late new Area {
    setup.ifuService.release()
    setup.dcService.release()
  }
}

case class IFUTBIO(ifuCfg: InstructionFetchUnitConfig) extends Bundle with IMasterSlave {
  val tbCpuFetchPort = slave(IFetchPort(ifuCfg))
  val tbDcacheWritebackBusy = out Bool ()
  override def asMaster(): Unit = {
    master(tbCpuFetchPort)
    in(tbDcacheWritebackBusy)
  }
}

/** Top-level DUT for IFU tests.
  */
class IFUTestBench(
    val pCfg: PipelineConfig,
    val ifuCfg: InstructionFetchUnitConfig,
    val dCacheCfg: DataCachePluginConfig,
    val axiConfig: Axi4Config
) extends Component {
  val io = IFUTBIO(ifuCfg)
  io.simPublic()
  val framework = new Framework(
    Seq(
      new IFUTestSetupPlugin(io),
      new TestOnlyMemSystemPlugin(axiConfig),
      new DataCachePlugin(dCacheCfg),
      new IFUPlugin(ifuCfg)
    )
  )
  def getSramHandle(): SimulatedSRAM = framework.getService[TestOnlyMemSystemPlugin].getSram()
}

/** Test Helper for IFU simulations.
  */
class IFUTestHelper(dut: IFUTestBench, enableRspReadyRandomizer: Boolean = true)(implicit cd: ClockDomain) {
  val sram: SimulatedSRAM = dut.getSramHandle()

  // Get SRAM word size from the DUT configuration for generic helper functions
  val sramWordWidth: Int = dut.axiConfig.dataWidth
  val sramWordBytes: Int = sramWordWidth / 8

  val receivedRspQueue = mutable.Queue[IFetchRspCapture]()
  StreamMonitor(dut.io.tbCpuFetchPort.rsp, cd) { payload =>
    receivedRspQueue.enqueue(IFetchRspCapture(payload))
  }

  if (enableRspReadyRandomizer) StreamReadyRandomizer(dut.io.tbCpuFetchPort.rsp, cd)

  def init(): Unit = {
    dut.io.tbCpuFetchPort.cmd.valid #= false
    dut.io.tbCpuFetchPort.rsp.ready #= true
    dut.io.tbCpuFetchPort.flush #= false
    sram.io.tb_writeEnable #= false
    sram.io.tb_readEnable #= false
    receivedRspQueue.clear()
    cd.waitSampling()
  }

  /** Writes a fetch group (e.g., 64 bits) to the SRAM by breaking it down
    * into multiple SRAM words (e.g., 32 bits each).
    *
    * @param baseAddress The starting address of the fetch group.
    * @param data The entire data for the fetch group (e.g., 64-bit value).
    */
  def sramWriteFetchGroup(baseAddress: BigInt, data: BigInt): Unit = {
    val fetchGroupWidth = dut.ifuCfg.fetchGroupDataWidth.value
    val numWords = fetchGroupWidth / sramWordWidth
    val mask = (BigInt(1) << sramWordWidth) - 1

    ParallaxLogger.debug(
      s"[Helper] SRAM Write Fetch Group: Address 0x${baseAddress.toString(16)}, Data 0x${data.toString(16)}"
    )

    for (i <- 0 until numWords) {
      val wordAddress = baseAddress + (i * sramWordBytes)
      val wordData = (data >> (i * sramWordWidth)) & mask

      ParallaxLogger.debug(
        s"[Helper]   - Writing word $i: Address 0x${wordAddress.toString(16)}, Data 0x${wordData.toString(16)}"
      )

      sram.io.tb_writeAddress #= wordAddress
      sram.io.tb_writeData #= wordData
      sram.io.tb_writeEnable #= true
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(1)
  }

  def sramReadFetchGroup(address: BigInt): BigInt = {
    // This function is likely not needed if tests only verify IFU responses,
    // but kept for completeness. It would need similar logic to sramWriteFetchGroup.
    sram.io.tb_readAddress #= address
    sram.io.tb_readEnable #= true
    cd.waitSampling()
    sram.io.tb_readEnable #= false
    cd.waitSampling(2)
    val readData = sram.io.tb_readData.toBigInt
    ParallaxLogger.debug(s"[Helper] SRAM Read: Address 0x${address.toString(16)}, Got Data 0x${readData.toString(16)}")
    readData
  }

  def requestPc(pc: BigInt): Unit = {
    ParallaxLogger.debug(s"[Helper] Requesting PC: 0x${pc.toString(16)}")
    dut.io.tbCpuFetchPort.cmd.valid #= true
    dut.io.tbCpuFetchPort.cmd.pc #= pc
    cd.waitSampling()
    dut.io.tbCpuFetchPort.cmd.valid #= false
  }

  def expectFetchPacket(expectedPc: BigInt, expectedInstructions: Seq[BigInt]): Unit = {
    if (receivedRspQueue.isEmpty) { // Check our local queue
      cd.waitSamplingWhere(timeout = 160)(receivedRspQueue.nonEmpty)
    }
    assert(receivedRspQueue.nonEmpty, s"Timeout waiting for fetch packet for PC 0x${expectedPc.toString(16)}")

    val receivedPacket = receivedRspQueue.dequeue() // Dequeue from our local queue
    val receivedPc = receivedPacket.pc.toBigInt
    val receivedInstrBits = receivedPacket.instructions.map(_.toBigInt)

    ParallaxLogger.info(
      s"[Helper] Received Packet: PC=0x${receivedPc.toString(16)}, " +
        s"Fault=${receivedPacket.fault}, " +
        s"Instructions=${receivedInstrBits.map("0x" + _.toString(16)).mkString(", ")}"
    )
    assert(
      receivedPc == expectedPc,
      s"PC Mismatch! Expected 0x${expectedPc.toString(16)}, Got 0x${receivedPc.toString(16)}"
    )
    assert(!receivedPacket.fault, "Fetch fault was not expected!")
    assert(
      receivedInstrBits.size == expectedInstructions.size,
      s"Instruction count mismatch! Expected ${expectedInstructions.size}, Got ${receivedInstrBits.size}"
    )
    expectedInstructions.zip(receivedInstrBits).zipWithIndex.foreach { case (((expected, actual), idx)) =>
      assert(
        actual == expected,
        s"Instruction mismatch at index $idx for PC 0x${expectedPc.toString(16)}! Expected 0x${expected
            .toString(16)}, Got 0x${actual.toString(16)}"
      )
    }
    ParallaxLogger.info(s"[Helper] Packet for PC 0x${expectedPc.toString(16)} verified.")
  }

  def forceDCacheFlushAndWait(targetAddress: BigInt): Unit = {
    // This is a coarse way to wait for cache operations. A more robust way would involve
    // issuing a flush command and waiting for its completion signal if available.
    if (dut.io.tbDcacheWritebackBusy.toBoolean) {
      ParallaxLogger.info("[Helper] DCache is busy with writeback, waiting...")
      cd.waitSamplingWhere(timeout = 200)(!dut.io.tbDcacheWritebackBusy.toBoolean)
      assert(!dut.io.tbDcacheWritebackBusy.toBoolean, "Timeout waiting for DCache writeback to complete.")
    }
    // Add extra delay to allow any pending operations to clear the pipeline.
    cd.waitSampling(50)
  }
}

class InstructionFetchUnitSpec extends CustomSpinalSimFunSuite {

  def createPConfig(): PipelineConfig = PipelineConfig(
    xlen = 32,
    fetchWidth = 1,
    commitWidth = 1,
    robDepth = 4,
    renameWidth = 1,
    physGprCount = 4,
    archGprCount = 4,
    aluEuCount = 0,
    transactionIdWidth = 1
  )

  def createDCacheConfig(pCfg: PipelineConfig): DataCachePluginConfig = {
    DataCachePluginConfig(
      pipelineConfig = pCfg,
      memDataWidth = 32,
      cacheSize = 1024,
      wayCount = 2,
      refillCount = 2,
      writebackCount = 2,
      lineSize = 16,
      transactionIdWidth = pCfg.transactionIdWidth,
      loadRefillCheckEarly = true,
      storeRefillCheckEarly = true,
      loadReadBanksAt = 0,
      loadReadTagsAt = 1,
      loadTranslatedAt = 0,
      loadHitsAt = 1,
      loadHitAt = 2,
      loadBankMuxesAt = 1,
      loadBankMuxAt = 2,
      loadControlAt = 2,
      loadRspAt = 2,
      storeReadBanksAt = 0,
      storeReadTagsAt = 1,
      storeHitsAt = 1,
      storeHitAt = 1,
      storeControlAt = 2,
      storeRspAt = 2,
      tagsReadAsync = true,
      reducedBankWidth = false
    )
  }

  def createIFUConfig(
      pCfg: PipelineConfig,
      dCacheParams: DataCacheParameters,
      fetchGroupWidthBits: Int,
      instrWidthBits: Int
  ): InstructionFetchUnitConfig = {
    InstructionFetchUnitConfig(
      dcacheParameters = dCacheParams,
      pcWidth = pCfg.xlen bits,
      instructionWidth = instrWidthBits bits,
      fetchGroupDataWidth = fetchGroupWidthBits bits,
      enableLog = true
    )
  }

  def createAxiConfig(pCfg: PipelineConfig, dCacheCfg: DataCachePluginConfig): Axi4Config = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = dCacheCfg.memDataWidth,
    idWidth = dCacheCfg.transactionIdWidth,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true,
    useProt = true,
    useId = true,
    useLast = true,
    useRegion = false,
    useLock = false,
    useCache = false,
    useQos = false
  )

  test("IFU_Basic_Fetch_Hit") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    val dCachePluginCfg = createDCacheConfig(pCfg)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut)
        helper.init()

        val pcTarget = BigInt("00000000", 16)
        val instr0 = BigInt("12345678", 16)
        val instr1 = BigInt("ABCDEF01", 16)
        // Assuming little-endian: instr0 is at the lower address, instr1 at the higher address.
        // Data is assembled with higher address data in MSBs.
        val fetchGroupData = (instr1 << 32) | instr0

        // This will now write two 32-bit words to SRAM
        helper.sramWriteFetchGroup(pcTarget, fetchGroupData)
        cd.waitSampling(10)

        // The DUT will miss, fetch the 64-bit line (in two 32-bit chunks) from SRAM
        helper.requestPc(pcTarget)
        helper.expectFetchPacket(pcTarget, Seq(instr0, instr1))
        ParallaxLogger.info("[Test] DCache primed after initial miss.")

        helper.forceDCacheFlushAndWait(pcTarget)
        cd.waitSampling(10)

        ParallaxLogger.info("[Test] Requesting PC again, expecting DCache hit.")
        // The DUT should now hit in the cache
        helper.requestPc(pcTarget)
        helper.expectFetchPacket(pcTarget, Seq(instr0, instr1))
        println("Test 'IFU_Basic_Fetch_Hit' PASSED")
      }
  }

  test("IFU_Fetch_With_DCache_Redo") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    val dCachePluginCfg = createDCacheConfig(pCfg)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut)
        helper.init()

        val pcTarget = BigInt("00000100", 16)
        val instrA = BigInt("AADDCCBB", 16)
        val instrB = BigInt("FFEE0011", 16)
        val fetchGroupData = (instrB << 32) | instrA

        helper.sramWriteFetchGroup(pcTarget, fetchGroupData)
        cd.waitSampling(10)

        ParallaxLogger.info("[Test] Requesting PC, expecting initial DCache miss & redo handling.")
        helper.requestPc(pcTarget)
        helper.expectFetchPacket(pcTarget, Seq(instrA, instrB))
        println("Test 'IFU_Fetch_With_DCache_Redo' PASSED")
      }
  }

  test("IFU_Fetch_Unaligned_Address") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    val dCachePluginCfg = createDCacheConfig(pCfg)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut)
        helper.init()

        // Fetch group starts at 0x4, which is aligned to 32-bit but not 64-bit boundary
        val pcTarget = BigInt("00000004", 16)
        val instr0 = BigInt("11223344", 16)
        val instr1 = BigInt("AABBCCDD", 16)
        // IFU should fetch the aligned 64-bit group (starting at 0x0)
        // and then select the correct instructions.

        // Data in memory:
        // Addr 0x0: junk data
        // Addr 0x4: instr0
        // Addr 0x8: instr1
        // Addr 0xC: junk data
        val fetchGroupData0 = (instr0 << 32) | BigInt("DEADBEEF", 16) // Line starting at 0x0
        val fetchGroupData1 = (BigInt("CAFEBABE", 16) << 32) | instr1 // Line starting at 0x8

        helper.sramWriteFetchGroup(0, fetchGroupData0)
        helper.sramWriteFetchGroup(8, fetchGroupData1)
        cd.waitSampling(10)

        // Requesting an unaligned PC. IFU should handle this gracefully.
        // The implementation should fetch the aligned group containing the PC.
        helper.requestPc(pcTarget)
        helper.expectFetchPacket(pcTarget, Seq(instr0, instr1))

        println("Test 'IFU_Fetch_Unaligned_Address' PASSED")
      }
  }

  test("IFU_Fetch_Cross_Cache_Line") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    // Make cache line size equal to fetch group size for a clear cross-line test
    val dCachePluginCfg = createDCacheConfig(pCfg).copy(lineSize = 8)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut)
        helper.init()

        // Target PC is 0xC, which is the last 32-bit word of the cache line starting at 0x8
        // The fetch group (64 bits) will need data from 0xC (line 1) and 0x10 (line 2)
        val pcTarget = BigInt("0000000C", 16)

        val instr0 = BigInt("CCCCCCCC", 16) // at 0xC
        val instr1 = BigInt("10101010", 16) // at 0x10

        // Cache Line 1 (0x8 to 0xF)
        val line1Data = (instr0 << 32) | BigInt("AAAAAAAA", 16)
        helper.sramWriteFetchGroup(8, line1Data)

        // Cache Line 2 (0x10 to 0x17)
        val line2Data = (BigInt("BBBBBBBB", 16) << 32) | instr1
        helper.sramWriteFetchGroup(16, line2Data)
        cd.waitSampling(10)

        // The IFU should correctly fetch from two different cache lines
        // This will likely involve two separate DCache requests internally
        helper.requestPc(pcTarget)
        helper.expectFetchPacket(pcTarget, Seq(instr0, instr1))

        println("Test 'IFU_Fetch_Cross_Cache_Line' PASSED")
      }
  }

  test("IFU_Back_To_Back_Requests") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    val dCachePluginCfg = createDCacheConfig(pCfg)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut)
        helper.init()

        // First request
        val pcTarget1 = BigInt("00000000", 16)
        val instrA = BigInt("AAAAAAAA", 16)
        val instrB = BigInt("BBBBBBBB", 16)
        val fetchGroupData1 = (instrB << 32) | instrA
        helper.sramWriteFetchGroup(pcTarget1, fetchGroupData1)

        // Second request
        val pcTarget2 = BigInt("00000100", 16)
        val instrC = BigInt("CCCCCCCC", 16)
        val instrD = BigInt("DDDDDDDD", 16)
        val fetchGroupData2 = (instrD << 32) | instrC
        helper.sramWriteFetchGroup(pcTarget2, fetchGroupData2)

        cd.waitSampling(10)

        // Prime the cache with both lines
        helper.requestPc(pcTarget1)
        helper.expectFetchPacket(pcTarget1, Seq(instrA, instrB))
        helper.requestPc(pcTarget2)
        helper.expectFetchPacket(pcTarget2, Seq(instrC, instrD))

        helper.forceDCacheFlushAndWait(pcTarget1)
        cd.waitSampling(10)

        // Now, issue two requests back-to-back
        helper.requestPc(pcTarget1)
        // The IFU should accept the first request and become busy.
        // It should not be ready for the second request immediately.
        // assert(
        //   dut.io.tbCpuFetchPort.cmd.ready.toBoolean == false,
        //   "IFU should not be ready for a new request immediately"
        // )

        // Wait for the first packet to be delivered
        helper.expectFetchPacket(pcTarget1, Seq(instrA, instrB))

        // After the first packet is out, the IFU should become ready again
        cd.waitSamplingWhere(dut.io.tbCpuFetchPort.cmd.ready.toBoolean)

        // Issue the second request
        helper.requestPc(pcTarget2)
        helper.expectFetchPacket(pcTarget2, Seq(instrC, instrD))

        println("Test 'IFU_Back_To_Back_Requests' PASSED")
      }
  }

  test("IFU_Response_Stalled_By_CPU") {
    val fetchGroupWidthBits = 64
    val instrWidthBits = 32
    val pCfg = createPConfig()
    val dCachePluginCfg = createDCacheConfig(pCfg)
    val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
    val ifuCfg = createIFUConfig(pCfg, dCacheParams, fetchGroupWidthBits, instrWidthBits)
    val axiCfg = createAxiConfig(pCfg, dCachePluginCfg)

    SimConfig.withWave
      .compile(new IFUTestBench(pCfg, ifuCfg, dCachePluginCfg, axiCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        val helper = new IFUTestHelper(dut, false)
        helper.init()

        // Set CPU to not be ready for responses
        dut.io.tbCpuFetchPort.rsp.ready #= false

        val pcTarget = BigInt("00000000", 16)
        val instr0 = BigInt("12345678", 16)
        val instr1 = BigInt("ABCDEF01", 16)
        val fetchGroupData = (instr1 << 32) | instr0
        helper.sramWriteFetchGroup(pcTarget, fetchGroupData)
        cd.waitSampling(10)

        helper.requestPc(pcTarget)

        // Wait until IFU has a valid response ready to send
        cd.waitSamplingWhere(dut.io.tbCpuFetchPort.rsp.valid.toBoolean)
        ParallaxLogger.info("[Test] IFU has valid response, but CPU is stalling.")
        dut.io.tbCpuFetchPort.rsp.ready #= false
        // IFU should hold its response. The `cmd` port should also be blocked.
        assert(
          dut.io.tbCpuFetchPort.cmd.ready.toBoolean == false,
          "IFU should not be ready for new requests while stalled on response."
        )

        // Wait for some cycles to ensure the state is stable
        cd.waitSampling(20)
        assert(dut.io.tbCpuFetchPort.rsp.valid.toBoolean, "IFU should keep its response valid.")
        assert(helper.receivedRspQueue.isEmpty, "Testbench queue should be empty as CPU is not ready.")

        // Now, make the CPU ready
        ParallaxLogger.info("[Test] CPU is now ready to accept the response.")
        dut.io.tbCpuFetchPort.rsp.ready #= true

        // The packet should be received immediately
        helper.expectFetchPacket(pcTarget, Seq(instr0, instr1))

        println("Test 'IFU_Response_Stalled_By_CPU' PASSED")

      }
  }

  thatsAll()
}
