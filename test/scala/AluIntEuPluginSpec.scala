// filename: parallax/test/scala/AluIntEuIntegrationSpec.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite

import parallax.common._ // Import all common definitions
import parallax.execute._ // For EuBasePlugin, AluIntEuPlugin, DemoAlu, AluExceptionCode
import parallax.components.rob.{ROBConfig, ROBService, ROBWritebackPort} // Specific ROB imports
import parallax.utilities._ // For Framework, Plugin, Service, ParallaxLogger
import parallax.components.issue.IQEntryAluInt // For IQEntryAluInt type
import parallax.execute.{WakeupPlugin, WakeupService} // For WakeupService
import parallax.components.rename.BusyTablePlugin // For BusyTableService

import scala.collection.mutable
import scala.util.Random
import parallax.components.rob.ROBPlugin
import parallax.components.execute.AluExceptionCode

// --- Scala Case Class for Driving EU Input ---
case class AluEuTestInputParams(
    uopCode: BaseUopCode.E,
    robPtrVal: Int,
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
    robPtr: BigInt,
    hasException: Boolean,
    exceptionCode: BigInt
)

case class BypassMessageSnapshot(
    physRegIdx: BigInt,
    physRegData: BigInt,
    robPtr: BigInt,
    isFPR: Boolean,
    hasException: Boolean,
    exceptionCode: BigInt
)

class TestPrfPreloadPlugin(
    pCfg: PipelineConfig,
    tbPreloadWriteCmdPort: PrfWritePort // This is the master port from TestBench.io
) extends Plugin {
  setName("TestPrfPreloader")

  val setup = create early new Area {
    val gprService = findService[PhysicalRegFileService](_.isGprService()) // Assuming GPR for preload
    val serviceWritePort = gprService.newPrfWritePort() // Get a write port from the service

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

  val io = new Bundle {
    val driveEuInput = slave Stream (HardType(IQEntryAluInt(pCfg))()) // Use IQEntryAluInt directly

    val prfReadCmd = slave(PrfReadPort(pCfg.physGprIdxWidth)) simPublic ()

    val prfPreloadWriteCmd = if (!readPushDataSetting) slave(PrfWritePort(pCfg.physGprIdxWidth)) simPublic () else null
  }
  io.driveEuInput.simPublic()

  def plugins: Seq[Plugin] = {
    val ret = mutable.ArrayBuffer(
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
      new WakeupPlugin(pCfg), // Add WakeupService
      new BusyTablePlugin(pCfg), // Add BusyTableService
      new AluIntEuPlugin( // DUT
        euName = aluEuName,
        pipelineConfig = pCfg
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

  val tbPrfReadPortInstance = gprService.newPrfReadPort()
  tbPrfReadPortInstance.simPublic()
  tbPrfReadPortInstance.valid := io.prfReadCmd.valid
  tbPrfReadPortInstance.address := io.prfReadCmd.address
  io.prfReadCmd.rsp := tbPrfReadPortInstance.rsp // Connect slave's output to master's input
  ParallaxLogger.log("创建了一个测试用的 GPR 读端口")
  val euInputPort = aluIntEuPlugin.getEuInputPort

  // 连接所有必要的信号
  euInputPort.valid := io.driveEuInput.valid
  euInputPort.payload := io.driveEuInput.payload
  io.driveEuInput.ready := euInputPort.ready
  
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
          wbBundle.robPtr.toBigInt,
          wbBundle.exceptionOccurred.toBoolean,
          wbBundle.exceptionCodeIn.toBigInt
        )
      }
      if (dut.aluIntEuPlugin.bypassOutputPort != null && dut.aluIntEuPlugin.bypassOutputPort.valid.toBoolean) {
        val bpPayload = dut.aluIntEuPlugin.bypassOutputPort.payload
        bypassMessagesMon += BypassMessageSnapshot(
          bpPayload.physRegIdx.toBigInt,
          bpPayload.physRegData.toBigInt,
          bpPayload.robPtr.toBigInt,
          bpPayload.isFPR.toBoolean,
          bpPayload.hasException.toBoolean,
          bpPayload.exceptionCode.toBigInt
        )
      }
    }
  }

  // --- Test Cases ---
  val testModes = Seq(
    ("SrcData_Read_By_EU", false) // Only test the mode where EU reads from PRF
  )

  for ((modeName, readPushDataValue) <- testModes) {
    test(s"AluIntEuPlugin - ADD Operation - $modeName") {
      val pCfg = createTestPipelineConfig()
      simCfg.compile(new AluIntEuTestBench(pCfg, readPushDataSetting = readPushDataValue)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        val inputQueue = mutable.Queue[AluEuTestInputParams]()
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue) {
          (dutPayloadPort, paramsCaseClass) =>
            // Initialize IQEntryAluInt fields
            dutPayloadPort.setDefaultForSim()

            // Drive IQEntryAluInt fields directly
            dutPayloadPort.robPtr #= paramsCaseClass.robPtrVal
            dutPayloadPort.physDest.idx #= paramsCaseClass.physDestVal
            dutPayloadPort.physDestIsFpr #= false
            dutPayloadPort.writesToPhysReg #= paramsCaseClass.writesDest

            dutPayloadPort.useSrc1 #= paramsCaseClass.useSrc1
            dutPayloadPort.src1Tag #= paramsCaseClass.physSrc1Val
            dutPayloadPort.src1Ready #= true // Assume ready for test
            dutPayloadPort.src1IsFpr #= false

            dutPayloadPort.useSrc2 #= paramsCaseClass.useSrc2
            dutPayloadPort.src2Tag #= paramsCaseClass.physSrc2Val
            dutPayloadPort.src2Ready #= true // Assume ready for test
            dutPayloadPort.src2IsFpr #= false

            dutPayloadPort.aluCtrl.isSub #= paramsCaseClass.aluIsSub
            dutPayloadPort.aluCtrl.isAdd #= paramsCaseClass.aluIsAdd
            dutPayloadPort.aluCtrl.isSigned #= paramsCaseClass.aluIsSigned
            dutPayloadPort.aluCtrl.logicOp #= paramsCaseClass.aluLogicOp

            dutPayloadPort.shiftCtrl.setDefaultForSim()
        }

        val gprWritesMon = mutable.ArrayBuffer[(Int, BigInt)]()
        val robCompletionsMon = mutable.ArrayBuffer[RobCompletionSnapshot]()
        val bypassMessagesMon = mutable.ArrayBuffer[BypassMessageSnapshot]()
        setupAluEuMonitors(dut, pCfg, gprWritesMon, robCompletionsMon, bypassMessagesMon)

        dut.clockDomain.waitSampling() // Initial sampling

        val robPtrTest = 0
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
            robPtrVal = robPtrTest,
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
          robComp.robPtr == robPtrTest,
          s"[$modeName] ADD: ROB index mismatch. Expected $robPtrTest, got ${robComp.robPtr}"
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
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue) {
          (payload, params) =>
            // Initialize IQEntryAluInt fields
            payload.setDefaultForSim()

            // Drive IQEntryAluInt fields directly
            payload.robPtr #= params.robPtrVal
            payload.physDest.idx #= params.physDestVal
            payload.physDestIsFpr #= false
            payload.writesToPhysReg #= params.writesDest

            payload.useSrc1 #= params.useSrc1
            payload.src1Tag #= params.physSrc1Val
            payload.src1Ready #= true // Assume ready for test
            payload.src1IsFpr #= false

            payload.useSrc2 #= params.useSrc2
            payload.src2Tag #= params.physSrc2Val
            payload.src2Ready #= true // Assume ready for test
            payload.src2IsFpr #= false

            payload.aluCtrl.isSub #= params.aluIsSub
            payload.aluCtrl.isAdd #= params.aluIsAdd
            payload.aluCtrl.isSigned #= params.aluIsSigned
            payload.aluCtrl.logicOp #= params.aluLogicOp

            payload.shiftCtrl.setDefaultForSim()
        }
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

        val robPtrTest = 1; val physSrc1 = 4; val physSrc2 = 5; val physDest = 6
        val src1Val = BigInt(100); val src2Val = BigInt(30); val expectedResult = src1Val - src2Val
        if (!readPushDataValue) {
          preloadPrfWritePort(dut, physSrc1, src1Val, dut.clockDomain);
          preloadPrfWritePort(dut, physSrc2, src2Val, dut.clockDomain); dut.clockDomain.waitSampling(2)
        }
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robPtrTest,
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
        assert(robComp.robPtr == robPtrTest, s"[$modeName] SUB: ROB index")
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
        SimTestHelpers.SimpleStreamDrive(dut.io.driveEuInput, dut.clockDomain, inputQueue) {
          (payload, params) =>
            // Initialize IQEntryAluInt fields
            payload.setDefaultForSim()

            // Drive IQEntryAluInt fields directly
            payload.robPtr #= params.robPtrVal
            payload.physDest.idx #= params.physDestVal
            payload.physDestIsFpr #= false
            payload.writesToPhysReg #= params.writesDest

            payload.useSrc1 #= params.useSrc1
            payload.src1Tag #= params.physSrc1Val
            payload.src1Ready #= true // Assume ready for test
            payload.src1IsFpr #= false

            payload.useSrc2 #= params.useSrc2
            payload.src2Tag #= params.physSrc2Val
            payload.src2Ready #= true // Assume ready for test
            payload.src2IsFpr #= false

            payload.aluCtrl.isSub #= params.aluIsSub
            payload.aluCtrl.isAdd #= params.aluIsAdd
            payload.aluCtrl.isSigned #= params.aluIsSigned
            payload.aluCtrl.logicOp #= params.aluLogicOp

            payload.shiftCtrl.setDefaultForSim()
        }
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

        val robPtrTest = 2; val physSrc1 = 7; val physSrc2 = 8; val physDest = 9
        val src1Val = BigInt(0xFF); val src2Val = BigInt(0x0F); val expectedResult = src1Val & src2Val
        if (!readPushDataValue) {
          preloadPrfWritePort(dut, physSrc1, src1Val, dut.clockDomain);
          preloadPrfWritePort(dut, physSrc2, src2Val, dut.clockDomain); dut.clockDomain.waitSampling(2)
        }
        inputQueue.enqueue(
          AluEuTestInputParams(
            BaseUopCode.ALU,
            robPtrTest,
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
        assert(robComp.robPtr == robPtrTest, s"[$modeName] AND: ROB index")
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
  }
  
  // Add thatsAll to ensure tests are executed
  thatsAll
}
