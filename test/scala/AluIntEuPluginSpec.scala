// filename: parallax/test/scala/AluIntEuIntegrationSpec.scala
package parallax.test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
// StreamDriver, StreamMonitor, StreamReadyRandomizer might not be needed if using SimpleStreamDrive and callbacks
import spinal.tester.SpinalSimFunSuite

import parallax.common._ // Import all common definitions
import parallax.execute._ // For EuBasePlugin, AluIntEuPlugin, DemoAlu, AluExceptionCode
import parallax.components.rob.{ROBConfig, ROBService, ROBWritebackPort} // Specific ROB imports
import parallax.utilities._ // For Framework, Plugin, Service, ParallaxLogger

import scala.collection.mutable
import scala.util.Random
import parallax.components.rob.ROBPlugin
import parallax.components.execute.AluExceptionCode

// --- Scala Case Class for Driving EU Input ---
case class AluEuTestInputParams(
    uopCode: BaseUopCode.E,
    robIdxVal: Int,
    physSrc1Val: Int,
    useSrc1: Boolean,
    src1DataVal: BigInt,
    physSrc2Val: Int,
    useSrc2: Boolean,
    src2DataVal: BigInt,
    physDestVal: Int,
    writesDest: Boolean, // For both decoded.writeArchDestEn and rename.writesToPhysReg
    immVal: BigInt,
    immUse: ImmUsageType.E,
    aluIsSub: Boolean,
    aluIsAdd: Boolean,
    aluLogicOp: LogicOp.E,
    aluIsSigned: Boolean,
    hasDecodeExc: Boolean,
    decodeExcCode: DecodeExCode.E,
    pcVal: Long
)

// --- Scala Snapshot Classes for Monitoring ---
case class RobCompletionSnapshot(
    robIdx: BigInt,
    hasException: Boolean,
    exceptionCode: BigInt
)

case class BypassMessageSnapshot(
    physRegIdx: BigInt,
    physRegData: BigInt,
    physRegDataValid: Boolean,
    robIdx: BigInt,
    isFPR: Boolean,
    hasException: Boolean,
    exceptionCode: BigInt
)

class TestEuInputProviderPlugin(
    pCfgForPayload: PipelineConfig, // Config needed for EuPushPortPayload type
    val drivingStream: Stream[EuPushPortPayload[EmptyBundle]], // The stream this service will provide
    val targetName: String
) extends Plugin
    with EuInputSourceService[EmptyBundle] {
  override def getEuInputPort(): Stream[EuPushPortPayload[EmptyBundle]] = {
    drivingStream
  }

  override def getTargetName(): String = targetName
}

class TestPrfPreloadPlugin(
    pCfg: PipelineConfig,
    tbPreloadWriteCmdPort: PrfWritePort // This is the master port from TestBench.io
) extends Plugin {
  setName("TestPrfPreloader")

  val setup = create early new Area {
    val gprService = findService[PhysicalRegFileService](_.isGprService()) // Assuming GPR for preload
    val serviceWritePort = gprService.newWritePort() // Get a write port from the service

    // Connect the TestBench's master port (tbPreloadWriteCmdPort) to drive the service's slave port
    // serviceWritePort is slave-like from the perspective of the driver (TestBench)
    // tbPreloadWriteCmdPort is master-like (TestBench drives its .valid, .address, .data)
    serviceWritePort.valid := tbPreloadWriteCmdPort.valid
    serviceWritePort.address := tbPreloadWriteCmdPort.address
    serviceWritePort.data := tbPreloadWriteCmdPort.data
  }
}

// --- TestBench Component ---
class AluIntEuTestBench(
    val pCfg: PipelineConfig,
    val aluEuName: String = "testAluIntEu",
    val readPushDataSetting: Boolean
) extends Component {
  val database = new DataBase

  val HT_RenamedUop = HardType(RenamedUop(pCfg))
  def FN_DefaultRenamedUop = () => { val uop = RenamedUop(pCfg); uop.setDefault(); uop }

  val HT_BypassMessage = HardType(BypassMessage(pCfg))
  // Assuming EuPushPortPayload is in parallax.common
  val HT_EuPushPayload = HardType(EuPushPortPayload(HardType(EmptyBundle()), pCfg))

  val io = new Bundle {
    val driveEuInput = slave Stream (HT_EuPushPayload())

    val prfReadCmd = slave(PrfReadPort(pCfg.physGprIdxWidth)) simPublic ()

    val prfPreloadWriteCmd = if (!readPushDataSetting) slave(PrfWritePort(pCfg.physGprIdxWidth)) simPublic () else null
  }
  io.driveEuInput.simPublic()

  def plugins: Seq[Plugin] = {
    val ret = mutable.ArrayBuffer(
      new TestEuInputProviderPlugin(
        pCfgForPayload = pCfg,
        drivingStream = io.driveEuInput,
        targetName = aluEuName
      ),
      new PhysicalRegFilePlugin(
        numPhysRegs = pCfg.physGprCount,
        dataWidth = pCfg.dataWidth
      ),
      new ROBPlugin[RenamedUop](
        pCfg,
        HT_RenamedUop,
        FN_DefaultRenamedUop
      ),
      new BypassPlugin[BypassMessage](
        HT_BypassMessage
      ),
      new AluIntEuPlugin( // DUT
        euName = aluEuName,
        pipelineConfig = pCfg,
        readPhysRsDataFromPush = readPushDataSetting
      )
    )

    if (io.prfPreloadWriteCmd != null) {
      ret += new TestPrfPreloadPlugin(
        pCfg = pCfg,
        tbPreloadWriteCmdPort = io.prfPreloadWriteCmd
      )
    }

    ret.toSeq
  }

  val framework = ProjectScope(database) on new Framework(
    plugins
  )

  val aluIntEuPlugin = framework.getServiceWhere[AluIntEuPlugin](_.euName == aluEuName)
  val gprService = framework.getServiceWhere[PhysicalRegFileService](_.isGprService())

  val tbPrfReadPortInstance = gprService.newReadPort()
  tbPrfReadPortInstance.simPublic()
  tbPrfReadPortInstance.valid := io.prfReadCmd.valid
  tbPrfReadPortInstance.address := io.prfReadCmd.address
  io.prfReadCmd.rsp := tbPrfReadPortInstance.rsp // Connect slave's output to master's input
  ParallaxLogger.log("创建了一个测试用的 GPR 读端口")
  // Expose internal ports of AluIntEuPlugin for simulation observation
  // Requires these to be public val in EuBasePlugin or have public getters
  if (aluIntEuPlugin.gprWritePort != null) aluIntEuPlugin.gprWritePort.simPublic()
  if (aluIntEuPlugin.robWritebackPortBundle != null) aluIntEuPlugin.robWritebackPortBundle.simPublic()
  if (aluIntEuPlugin.bypassOutputPort != null) aluIntEuPlugin.bypassOutputPort.simPublic()
}

// --- Test Suite ---
class AluIntEuIntegrationSpec extends CustomSpinalSimFunSuite {
  // Simulation configuration
  def simCfg = SimConfig.withWave.withVcdWave

  // Helper to create a default PipelineConfig for tests
  def createTestPipelineConfig(
      physGprCount: Int = 64,
      robDepth: Int = 32,
      aluEuCount: Int = 1,
      commitWidth: Int = 2,
      renameWidth: Int = 2
  ): PipelineConfig = PipelineConfig(
    physGprCount = physGprCount,
    robDepth = robDepth,
    aluEuCount = aluEuCount,
    commitWidth = commitWidth,
    renameWidth = renameWidth,
    xlen = 32,
    archGprCount = 32,
    physFprCount = 0,
    archFprCount = 0
  )

  // Helper to drive the EuPushPortPayload for simulation
  def driveFullEuPushPayload(
      targetPortPayload: EuPushPortPayload[EmptyBundle],
      pCfg: PipelineConfig,
      params: AluEuTestInputParams
  ): Unit = {
    import spinal.core.sim._

    val targetRenamedUop = targetPortPayload.renamedUop
    val decoded = targetRenamedUop.decoded
    val rename = targetRenamedUop.rename

    // Initialize sub-bundles to avoid X's from unassigned signals
    decoded.setDefaultForSim()
    rename.setDefaultForSim()
    targetRenamedUop.setDefaultForSim() // Sets its own fields and calls setDefaultForSim on decoded & rename

    // Drive DecodedUop part
    decoded.isValid #= true
    decoded.pc #= params.pcVal
    decoded.uopCode #= params.uopCode
    decoded.exeUnit #= ExeUnitType.ALU_INT
    decoded.isa #= IsaType.DEMO // Example

    decoded.archSrc1.idx #= params.physSrc1Val
    decoded.archSrc1.rtype #= ArchRegType.GPR
    decoded.useArchSrc1 #= params.useSrc1

    decoded.archSrc2.idx #= params.physSrc2Val
    decoded.archSrc2.rtype #= ArchRegType.GPR
    decoded.useArchSrc2 #= params.useSrc2

    decoded.archDest.idx #= params.physDestVal
    decoded.archDest.rtype #= ArchRegType.GPR
    decoded.writeArchDestEn #= params.writesDest

    decoded.imm #= params.immVal
    decoded.immUsage #= params.immUse

    decoded.aluCtrl.isSub #= params.aluIsSub
    decoded.aluCtrl.isAdd #= params.aluIsAdd
    decoded.aluCtrl.isSigned #= params.aluIsSigned
    decoded.aluCtrl.logicOp #= params.aluLogicOp

    decoded.hasDecodeException #= params.hasDecodeExc
    decoded.decodeExceptionCode #= params.decodeExcCode

    // Drive RenameInfo part
    rename.physSrc1.idx #= params.physSrc1Val
    rename.physSrc2.idx #= params.physSrc2Val
    rename.physDest.idx #= params.physDestVal
    rename.writesToPhysReg #= params.writesDest

    // Drive RenamedUop direct fields
    targetRenamedUop.robIdx #= params.robIdxVal

    // Drive EuPushPortPayload specific fields
    targetPortPayload.src1Data #= params.src1DataVal
    targetPortPayload.src2Data #= params.src2DataVal
  }

  // Helper to read PRF from TestBench
  def readPrf(dut: AluIntEuTestBench, addr: Int, clockDomain: ClockDomain): BigInt = {
    dut.io.prfReadCmd.valid #= true
    dut.io.prfReadCmd.address #= addr
    clockDomain.waitSampling()
    val data = dut.tbPrfReadPortInstance.rsp.toBigInt
    dut.io.prfReadCmd.valid #= false
    // clockDomain.waitSampling() // Optional: wait for valid deassertion to propagate
    data
  }

  // Helper to preload PRF
  def preloadPrfWritePort(dut: AluIntEuTestBench, addr: Int, data: BigInt, clockDomain: ClockDomain): Unit = {
    println(s"SIM_INFO: Preloading PRF Addr=$addr with Data=0x${data.toString(16)} via TB IO")
    dut.io.prfPreloadWriteCmd.valid #= true
    dut.io.prfPreloadWriteCmd.address #= addr
    dut.io.prfPreloadWriteCmd.data #= data
    clockDomain.waitSampling()
    dut.io.prfPreloadWriteCmd.valid #= false
    println(s"SIM_INFO: PRF preload executed for Addr=$addr with Data=0x${data.toString(16)}")
  }

  // Helper to setup monitors (moved from SimTestHelpers for better locality)
  def setupAluEuMonitors(
      dut: AluIntEuTestBench,
      pCfg: PipelineConfig,
      gprWritesMon: mutable.ArrayBuffer[(Int, BigInt)],
      robCompletionsMon: mutable.ArrayBuffer[RobCompletionSnapshot],
      bypassMessagesMon: mutable.ArrayBuffer[BypassMessageSnapshot]
  ): Unit = {
    dut.clockDomain.onSamplings {
      if (dut.aluIntEuPlugin.gprWritePort != null && dut.aluIntEuPlugin.gprWritePort.valid.toBoolean) {
        gprWritesMon += ((dut.aluIntEuPlugin.gprWritePort.address.toInt, dut.aluIntEuPlugin.gprWritePort.data.toBigInt))
      }
      if (
        dut.aluIntEuPlugin.robWritebackPortBundle != null && dut.aluIntEuPlugin.robWritebackPortBundle.fire.toBoolean
      ) {
        val wbBundle = dut.aluIntEuPlugin.robWritebackPortBundle
        robCompletionsMon += RobCompletionSnapshot(
          wbBundle.robIdx.toBigInt,
          wbBundle.exceptionOccurred.toBoolean,
          wbBundle.exceptionCodeIn.toBigInt
        )
      }
      if (dut.aluIntEuPlugin.bypassOutputPort != null && dut.aluIntEuPlugin.bypassOutputPort.valid.toBoolean) {
        val bpPayload = dut.aluIntEuPlugin.bypassOutputPort.payload
        bypassMessagesMon += BypassMessageSnapshot(
          bpPayload.physRegIdx.toBigInt,
          bpPayload.physRegData.toBigInt,
          bpPayload.physRegDataValid.toBoolean,
          bpPayload.robIdx.toBigInt,
          bpPayload.isFPR.toBoolean,
          bpPayload.hasException.toBoolean,
          bpPayload.exceptionCode.toBigInt
        )
      }
    }
  }

  // --- Test Cases ---
  val testModes = Seq(
    ("SrcData_Pushed_By_Dispatcher", true),
    ("SrcData_Read_By_EU", false)
  )

  for ((modeName, readPushDataValue) <- testModes) {
    test(s"AluIntEuPlugin - ADD Operation - $modeName") {
      val pCfg = createTestPipelineConfig()
      simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        val inputQueue = mutable.Queue[AluEuTestInputParams]()
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue) {
          (dutPayloadPort, paramsCaseClass) =>
            driveFullEuPushPayload(dutPayloadPort, pCfg, paramsCaseClass)
        }

        val gprWritesMon = mutable.ArrayBuffer[(Int, BigInt)]()
        val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]()
        val bypassMessagesMon = mutable.ArrayBuffer[BypassMessageSnapshot]()
        setupAluEuMonitors(dut, pCfg, gprWritesMon, robCompletionsMon, bypassMessagesMon)

        dut.clockDomain.waitSampling() // Initial sampling

        val robIdxTest = 0
        val physSrc1 = 1; val physSrc2 = 2; val physDest = 3
        val src1Val = BigInt(10); val src2Val = BigInt(20)
        val expectedResult = src1Val + src2Val

        if (!readPushDataValue) {
          preloadPrfWritePort(dut, physSrc1, src1Val, dut.clockDomain)
          preloadPrfWritePort(dut, physSrc2, src2Val, dut.clockDomain)
          // Add a small delay after preloading to ensure PRF is stable before EU reads
          dut.clockDomain.waitSampling(2)
        }

        inputQueue.enqueue(
          AluEuTestInputParams(
            uopCode = BaseUopCode.ALU,
            robIdxVal = robIdxTest,
            physSrc1Val = physSrc1,
            useSrc1 = true,
            src1DataVal = src1Val,
            physSrc2Val = physSrc2,
            useSrc2 = true,
            src2DataVal = src2Val,
            physDestVal = physDest,
            writesDest = true,
            immVal = BigInt(0),
            immUse = ImmUsageType.NONE,
            aluIsSub = false,
            aluIsAdd = true,
            aluLogicOp = LogicOp.NONE,
            aluIsSigned = false,
            hasDecodeExc = false,
            decodeExcCode = DecodeExCode.OK,
            pcVal = 0x100L
          )
        )

        // Wait for the operation to complete and be monitored
        val timeoutCycles = 50
        val success = dut.clockDomain.waitSamplingWhere(timeout = timeoutCycles) {
          robCompletionsMon.nonEmpty && bypassMessagesMon.nonEmpty &&
          (if (readPushDataValue) gprWritesMon.nonEmpty
           else true) // GPR write might take an extra cycle if PRF is registered
        }
        if (!success) { // If timeout occurred
          dut.clockDomain.waitSampling(5) // Allow a few more cycles for potential late signals
        }
        assert(
          robCompletionsMon.nonEmpty,
          s"[$modeName] ADD: Timeout - No ROB completion received after $timeoutCycles cycles."
        )

        assert(robCompletionsMon.nonEmpty, s"[$modeName] ADD: No ROB completion received")
        val robComp = robCompletionsMon.head
        assert(!robComp.hasException, s"[$modeName] ADD: Op should not have exception. Code: ${robComp.exceptionCode}")
        assert(
          robComp.robIdx == robIdxTest,
          s"[$modeName] ADD: ROB index mismatch. Expected $robIdxTest, got ${robComp.robIdx}"
        )

        assert(bypassMessagesMon.nonEmpty, s"[$modeName] ADD: No bypass message received")
        val bypassMsg = bypassMessagesMon.head
        assert(bypassMsg.physRegIdx == physDest, s"[$modeName] ADD: Bypass physRegIdx mismatch")
        assert(
          bypassMsg.physRegData == expectedResult,
          s"[$modeName] ADD: Bypass data mismatch. Expected $expectedResult, got ${bypassMsg.physRegData}"
        )
        assert(!bypassMsg.hasException, s"[$modeName] ADD: Bypass should not have exception")

        val gprReadValue = readPrf(dut, physDest, dut.clockDomain)
        assert(
          gprReadValue == expectedResult,
          s"[$modeName] ADD: GPR read back mismatch. Expected $expectedResult, got $gprReadValue"
        )

        // The gprWritesMon check might be tricky if the write is registered and takes an extra cycle
        // compared to when ROB/Bypass messages are generated.
        // For now, let's assume it should be there shortly after.
        assert(gprWritesMon.nonEmpty, s"[$modeName] ADD: No GPR write observed via monitor")
        assert(
          gprWritesMon.head._1 == physDest && gprWritesMon.head._2 == expectedResult,
          s"[$modeName] ADD: GPR write monitor mismatch"
        )

        println(s"Test 'AluIntEuPlugin - ADD Operation - $modeName' PASSED")
        dut.clockDomain.waitSampling(10)
      }
    }

    test(s"AluIntEuPlugin - SUB Operation - $modeName") {
      val pCfg = createTestPipelineConfig()
      simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        val inputQueue = mutable.Queue[AluEuTestInputParams]()
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue)((payload, params) =>
          driveFullEuPushPayload(payload, pCfg, params)
        )
        val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]();
        val bypassMessagesMon = mutable.ArrayBuffer[BypassMessageSnapshot]()
        setupAluEuMonitors(
          dut,
          pCfg,
          mutable.ArrayBuffer(),
          robCompletionsMon,
          bypassMessagesMon
        ) // gprWritesMon not primary for this check
        dut.clockDomain.waitSampling()

        val robIdxTest = 1; val physSrc1 = 4; val physSrc2 = 5; val physDest = 6
        val src1Val = BigInt(100); val src2Val = BigInt(30); val expectedResult = src1Val - src2Val
        if (!readPushDataValue) {
          preloadPrfWritePort(dut, physSrc1, src1Val, dut.clockDomain);
          preloadPrfWritePort(dut, physSrc2, src2Val, dut.clockDomain); dut.clockDomain.waitSampling(2)
        }
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robIdxTest,
            physSrc1,
            true,
            src1Val,
            physSrc2,
            true,
            src2Val,
            physDest,
            true,
            0,
            ImmUsageType.NONE,
            true,
            false,
            LogicOp.NONE,
            false,
            false,
            DecodeExCode.OK,
            0x200L
          )
        )
        dut.clockDomain.waitSamplingWhere(50)(robCompletionsMon.nonEmpty && bypassMessagesMon.nonEmpty)

        assert(robCompletionsMon.nonEmpty, s"[$modeName] SUB: No ROB completion"); val robComp = robCompletionsMon.head
        assert(!robComp.hasException, s"[$modeName] SUB: Exception. Code: ${robComp.exceptionCode}");
        assert(robComp.robIdx == robIdxTest, s"[$modeName] SUB: ROB index")
        assert(bypassMessagesMon.nonEmpty, s"[$modeName] SUB: No bypass"); val bypassMsg = bypassMessagesMon.head
        assert(
          bypassMsg.physRegData == expectedResult,
          s"[$modeName] SUB: Bypass data. Exp $expectedResult, Got ${bypassMsg.physRegData}"
        )
        val gprRead = readPrf(dut, physDest, dut.clockDomain);
        assert(gprRead == expectedResult, s"[$modeName] SUB: GPR read. Exp $expectedResult, Got $gprRead")
        println(s"Test 'AluIntEuPlugin - SUB Operation - $modeName' PASSED"); dut.clockDomain.waitSampling(10)
      }
    }

    test(s"AluIntEuPlugin - AND Operation - $modeName") {
      val pCfg = createTestPipelineConfig()
      simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        val inputQueue = mutable.Queue[AluEuTestInputParams]()
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue)((payload, params) =>
          driveFullEuPushPayload(payload, pCfg, params)
        )
        val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]();
        val bypassMessagesMon = mutable.ArrayBuffer[BypassMessageSnapshot]()
        setupAluEuMonitors(dut, pCfg, mutable.ArrayBuffer(), robCompletionsMon, bypassMessagesMon)
        dut.clockDomain.waitSampling()

        val robIdxTest = 2; val physSrc1 = 7; val physSrc2 = 8; val physDest = 9
        val src1Val = BigInt("FFFF0000", 16); val src2Val = BigInt("00FFFF00", 16);
        val expectedResult = src1Val & src2Val
        if (!readPushDataValue) {
          preloadPrfWritePort(dut, physSrc1, src1Val, dut.clockDomain);
          preloadPrfWritePort(dut, physSrc2, src2Val, dut.clockDomain); dut.clockDomain.waitSampling(2)
        }
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robIdxTest,
            physSrc1,
            true,
            src1Val,
            physSrc2,
            true,
            src2Val,
            physDest,
            true,
            0,
            ImmUsageType.NONE,
            false,
            false,
            LogicOp.AND,
            false,
            false,
            DecodeExCode.OK,
            0x300L
          )
        )
        dut.clockDomain.waitSamplingWhere(50)(robCompletionsMon.nonEmpty && bypassMessagesMon.nonEmpty)

        assert(robCompletionsMon.nonEmpty, s"[$modeName] AND: No ROB completion"); val robComp = robCompletionsMon.head
        assert(!robComp.hasException, s"[$modeName] AND: Exception. Code: ${robComp.exceptionCode}");
        assert(robComp.robIdx == robIdxTest, s"[$modeName] AND: ROB index")
        assert(bypassMessagesMon.nonEmpty, s"[$modeName] AND: No bypass"); val bypassMsg = bypassMessagesMon.head
        assert(
          bypassMsg.physRegData == expectedResult,
          s"[$modeName] AND: Bypass data. Exp $expectedResult, Got ${bypassMsg.physRegData}"
        )
        val gprRead = readPrf(dut, physDest, dut.clockDomain);
        assert(gprRead == expectedResult, s"[$modeName] AND: GPR read. Exp $expectedResult, Got $gprRead")
        println(s"Test 'AluIntEuPlugin - AND Operation - $modeName' PASSED"); dut.clockDomain.waitSampling(10)
      }
    }

    // test(s"AluIntEuPlugin - Decode Exception Propagation - $modeName") {
    //   val pCfg = createTestPipelineConfig()
    //   simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
    //     dut.clockDomain.forkStimulus(10)
    //     val inputQueue = mutable.Queue[AluEuTestInputParams]()
    //     SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue)((payload, params) =>
    //       driveFullEuPushPayload(payload, pCfg, params)
    //     )
    //     val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]()
    //     setupAluEuMonitors(
    //       dut,
    //       pCfg,
    //       mutable.ArrayBuffer(),
    //       robCompletionsMon,
    //       mutable.ArrayBuffer()
    //     ) // Bypass may or may not happen with exception
    //     dut.clockDomain.waitSampling()

    //     val robIdxTest = 3; val physDest = 10
    //     val expectedExcCode = AluExceptionCode.DECODE_EXCEPTION // This is what DemoAlu should output
    //     inputQueue.enqueue(
    //       AluEuTestInputParams(
    //         BaseUopCode.ALU,
    //         robIdxTest,
    //         0,
    //         false,
    //         0,
    //         0,
    //         false,
    //         0,
    //         physDest,
    //         false,
    //         0,
    //         ImmUsageType.NONE,
    //         false,
    //         true,
    //         LogicOp.NONE,
    //         false,
    //         true,
    //         DecodeExCode.DECODE_ERROR,
    //         0x400L
    //       )
    //     )
    //     dut.clockDomain.waitSamplingWhere(50)(robCompletionsMon.nonEmpty)

    //     assert(robCompletionsMon.nonEmpty, s"[$modeName] DecodeExc: No ROB completion");
    //     val robComp = robCompletionsMon.head
    //     assert(robComp.hasException, s"[$modeName] DecodeExc: Should have exception");
    //     assert(robComp.robIdx == robIdxTest, s"[$modeName] DecodeExc: ROB index")
    //     assert(
    //       robComp.exceptionCode == expectedExcCode.asUInt.toBigInt,
    //       s"[$modeName] DecodeExc: Exc code. Exp ${expectedExcCode.asUInt.toBigInt}, Got ${robComp.exceptionCode}"
    //     )
    //     println(s"Test 'AluIntEuPlugin - Decode Exception Propagation - $modeName' PASSED");
    //     dut.clockDomain.waitSampling(10)
    //   }
    // }

    // test(s"AluIntEuPlugin - Dispatch to Wrong EU (LOAD to ALU) - $modeName") {
    //   val pCfg = createTestPipelineConfig()
    //   simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
    //     dut.clockDomain.forkStimulus(10)
    //     val inputQueue = mutable.Queue[AluEuTestInputParams]()
    //     SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue)((payload, params) =>
    //       driveFullEuPushPayload(payload, pCfg, params)
    //     )
    //     val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]()
    //     setupAluEuMonitors(dut, pCfg, mutable.ArrayBuffer(), robCompletionsMon, mutable.ArrayBuffer())
    //     dut.clockDomain.waitSampling()

    //     val robIdxTest = 4; val physDest = 11
    //     val expectedExcCode = AluExceptionCode.DISPATCH_TO_WRONG_EU
    //     inputQueue.enqueue(
    //       AluEuTestInputParams(
    //         BaseUopCode.LOAD,
    //         robIdxTest,
    //         0,
    //         false,
    //         0,
    //         0,
    //         false,
    //         0,
    //         physDest,
    //         true,
    //         0,
    //         ImmUsageType.MEM_OFFSET,
    //         false,
    //         false,
    //         LogicOp.NONE,
    //         false,
    //         false,
    //         DecodeExCode.OK,
    //         0x500L
    //       )
    //     )
    //     dut.clockDomain.waitSamplingWhere(50)(robCompletionsMon.nonEmpty)

    //     assert(robCompletionsMon.nonEmpty, s"[$modeName] WrongEU: No ROB completion");
    //     val robComp = robCompletionsMon.head
    //     assert(robComp.hasException, s"[$modeName] WrongEU: Should have exception");
    //     assert(robComp.robIdx == robIdxTest, s"[$modeName] WrongEU: ROB index")
    //     assert(
    //       robComp.exceptionCode == expectedExcCode.asUInt.toBigInt,
    //       s"[$modeName] WrongEU: Exc code. Exp ${expectedExcCode.asUInt.toBigInt}, Got ${robComp.exceptionCode}"
    //     )
    //     println(s"Test 'AluIntEuPlugin - Dispatch to Wrong EU - $modeName' PASSED"); dut.clockDomain.waitSampling(10)
    //   }
    // }

    test(s"AluIntEuPlugin - Back-to-back ADD Operations - $modeName") {
      val pCfg = createTestPipelineConfig()
      simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        val inputQueue = mutable.Queue[AluEuTestInputParams]()
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue)((payload, params) =>
          driveFullEuPushPayload(payload, pCfg, params)
        )
        val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]();
        val bypassMessagesMon = mutable.ArrayBuffer[BypassMessageSnapshot]()
        setupAluEuMonitors(dut, pCfg, mutable.ArrayBuffer(), robCompletionsMon, bypassMessagesMon)
        dut.clockDomain.waitSampling()

        val robIdx1 = 5; val ps1_1 = 12; val ps2_1 = 13; val pd_1 = 14; val sd1_1 = 1; val sd2_1 = 2; val exp1 = 3
        val robIdx2 = 6; val ps1_2 = 15; val ps2_2 = 16; val pd_2 = 17; val sd1_2 = 5; val sd2_2 = 6; val exp2 = 11

        if (!readPushDataValue) {
          preloadPrfWritePort(dut, ps1_1, sd1_1, dut.clockDomain);
          preloadPrfWritePort(dut, ps2_1, sd2_1, dut.clockDomain)
          preloadPrfWritePort(dut, ps1_2, sd1_2, dut.clockDomain);
          preloadPrfWritePort(dut, ps2_2, sd2_2, dut.clockDomain)
          dut.clockDomain.waitSampling(2)
        }
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robIdx1,
            ps1_1,
            true,
            sd1_1,
            ps2_1,
            true,
            sd2_1,
            pd_1,
            true,
            0,
            ImmUsageType.NONE,
            false,
            true,
            LogicOp.NONE,
            false,
            false,
            DecodeExCode.OK,
            0x600L
          )
        )
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robIdx2,
            ps1_2,
            true,
            sd1_2,
            ps2_2,
            true,
            sd2_2,
            pd_2,
            true,
            0,
            ImmUsageType.NONE,
            false,
            true,
            LogicOp.NONE,
            false,
            false,
            DecodeExCode.OK,
            0x604L
          )
        )
        dut.clockDomain.waitSamplingWhere(100)(robCompletionsMon.length >= 2 && bypassMessagesMon.length >= 2)
        dut.clockDomain.waitSampling(5)

        assert(
          robCompletionsMon.length == 2,
          s"[$modeName] B2B: Expected 2 ROB completions, got ${robCompletionsMon.length}"
        )
        assert(
          bypassMessagesMon.length == 2,
          s"[$modeName] B2B: Expected 2 bypass messages, got ${bypassMessagesMon.length}"
        )

        val robComp1 = robCompletionsMon.find(_.robIdx == robIdx1).get;
        assert(!robComp1.hasException && robComp1.robIdx == robIdx1)
        val bypass1 = bypassMessagesMon.find(_.robIdx == robIdx1).get;
        assert(bypass1.physRegIdx == pd_1 && bypass1.physRegData == exp1)
        val gpr1 = readPrf(dut, pd_1, dut.clockDomain); assert(gpr1 == exp1)

        val robComp2 = robCompletionsMon.find(_.robIdx == robIdx2).get;
        assert(!robComp2.hasException && robComp2.robIdx == robIdx2)
        val bypass2 = bypassMessagesMon.find(_.robIdx == robIdx2).get;
        assert(bypass2.physRegIdx == pd_2 && bypass2.physRegData == exp2)
        val gpr2 = readPrf(dut, pd_2, dut.clockDomain); assert(gpr2 == exp2)
        println(s"Test 'AluIntEuPlugin - Back-to-back ADD Operations - $modeName' PASSED");
        dut.clockDomain.waitSampling(10)
      }
    }
  }
  thatsAll()
}
