// filename: parallax/test/scala/execute/DemoAluSpec.scala
// testOnly parallax.test.scala.execute.DemoAluSpec
package parallax.test.scala.execute

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import parallax.components.execute._
import parallax.common._ // Make sure this path and its contents are correct

object DemoAluTestConfig {
  // Centralized configuration for tests
  val testConfig = PipelineConfig(
  )
}

class DemoAluTestBench(val config: PipelineConfig) extends Component {
  val io = new Bundle {
    // Flattened inputs for easier driving in tests
    // DecodedUop parts
    val uopIn_payload_decoded_uopCode = in(BaseUopCode())
    val uopIn_payload_decoded_aluCtrl_isSub = in Bool ()
    val uopIn_payload_decoded_aluCtrl_logicOp = in(LogicOp())
    val uopIn_payload_decoded_aluCtrl_isAdd = in Bool ()
    val uopIn_payload_decoded_imm = in Bits (config.dataWidth)
    val uopIn_payload_decoded_immUsage = in(ImmUsageType())
    val uopIn_payload_decoded_hasDecodeException = in Bool ()
    // Add any other non-optional fields from DecodedUop that DemoAlu might implicitly depend on default values for
    // e.g. if DecodedUop contains 'pc', 'archSrc1/2', etc. that are not optional in Bundle def.
    // For this ALU, it seems most other DecodedUop fields are not directly used.

    // RenameInfo parts
    val uopIn_payload_rename_physDest_idx = in UInt (config.physGprIdxWidth)
    val uopIn_payload_rename_writesToPhysReg = in Bool ()

    // RenamedUop direct parts
    val uopIn_payload_robIdx = in UInt (config.robIdxWidth)

    val uopIn_valid = in Bool ()
    val src1DataIn = in Bits (config.dataWidth)
    val src2DataIn = in Bits (config.dataWidth)

    val resultOut_payload = out(AluOutputPayload(config))
    val resultOut_valid = out Bool ()
  }

  val alu = new DemoAlu(config)

  // Connect inputs to ALU
  alu.io.uopIn.valid := io.uopIn_valid

  // Assign to sub-fields of the payload bundle
  // This is verbose but ensures all necessary fields of the complex RenamedUop input are driven.
  // If RenamedUop has many fields not used by DemoAlu, one might need to assign default/dummy values
  // to them here or ensure they are `OptionalSignal` if applicable in their definition.
  alu.io.uopIn.payload.decoded.uopCode := io.uopIn_payload_decoded_uopCode
  alu.io.uopIn.payload.decoded.aluCtrl.isSub := io.uopIn_payload_decoded_aluCtrl_isSub
  alu.io.uopIn.payload.decoded.aluCtrl.logicOp := io.uopIn_payload_decoded_aluCtrl_logicOp
  alu.io.uopIn.payload.decoded.aluCtrl.isAdd := io.uopIn_payload_decoded_aluCtrl_isAdd
  alu.io.uopIn.payload.decoded.imm := io.uopIn_payload_decoded_imm
  alu.io.uopIn.payload.decoded.immUsage := io.uopIn_payload_decoded_immUsage
  alu.io.uopIn.payload.decoded.hasDecodeException := io.uopIn_payload_decoded_hasDecodeException
  // Example of defaulting other fields if they exist in DecodedUop and must be driven:
  // alu.io.uopIn.payload.decoded.pc := U(0)
  // alu.io.uopIn.payload.decoded.archSrc1.idx := U(0) // Assuming ArchSrcOperand exists
  // alu.io.uopIn.payload.decoded.archSrc1.rtype := ArchRegType.GPR
  // alu.io.uopIn.payload.decoded.useArchSrc1 := False

  alu.io.uopIn.payload.rename.physDest.idx := io.uopIn_payload_rename_physDest_idx
  alu.io.uopIn.payload.rename.writesToPhysReg := io.uopIn_payload_rename_writesToPhysReg
  // Example for other RenameInfo fields:
  // alu.io.uopIn.payload.rename.physSrc1.idx := U(0) // Assuming PhysicalRegOperand exists
  // alu.io.uopIn.payload.rename.usesPhysSrc1 := False

  alu.io.uopIn.payload.robIdx := io.uopIn_payload_robIdx

  alu.io.src1DataIn := io.src1DataIn
  alu.io.src2DataIn := io.src2DataIn

  // Connect outputs from ALU
  io.resultOut_payload := alu.io.resultOut.payload
  io.resultOut_valid := alu.io.resultOut.valid

  // Make outputs sim public
  io.resultOut_payload.simPublic()
  io.resultOut_valid.simPublic()
}

object AluTestHelper {
  val config = DemoAluTestConfig.testConfig // Use the centralized config
  val dataMask = (BigInt(1) << 32) - 1

  def driveAluInput(
      dut: DemoAluTestBench,
      valid: Boolean,
      uopCode: BaseUopCode.E = BaseUopCode.ALU,
      aluIsSub: Boolean = false,
      aluLogicOp: LogicOp.E = LogicOp.NONE,
      aluIsAdd: Boolean = false,
      imm: BigInt = 0, // This is the intended (possibly signed) immediate value
      immUsage: ImmUsageType.E = ImmUsageType.NONE,
      hasDecodeExc: Boolean = false,
      physDestIdx: Int = 1,
      writesToPhysReg: Boolean = true,
      robIdx: Int = 1,
      src1Val: BigInt = 0, // This is the intended (possibly signed) source 1 value
      src2Val: BigInt = 0 // This is the intended (possibly signed) source 2 value
  ): Unit = {
    dut.io.uopIn_valid #= valid

    dut.io.uopIn_payload_decoded_uopCode #= uopCode
    dut.io.uopIn_payload_decoded_aluCtrl_isSub #= aluIsSub
    dut.io.uopIn_payload_decoded_aluCtrl_logicOp #= aluLogicOp
    dut.io.uopIn_payload_decoded_aluCtrl_isAdd #= aluIsAdd
    // Apply the dataMask to convert BigInt to its bit pattern representation
    dut.io.uopIn_payload_decoded_imm #= (imm & dataMask)
    dut.io.uopIn_payload_decoded_immUsage #= immUsage
    dut.io.uopIn_payload_decoded_hasDecodeException #= hasDecodeExc

    dut.io.uopIn_payload_rename_physDest_idx #= physDestIdx
    dut.io.uopIn_payload_rename_writesToPhysReg #= writesToPhysReg
    dut.io.uopIn_payload_robIdx #= robIdx

    // Apply the dataMask to convert BigInt to its bit pattern representation
    dut.io.src1DataIn #= (src1Val & dataMask)
    dut.io.src2DataIn #= (src2Val & dataMask)
  }

  def checkAluOutput(
      dut: DemoAluTestBench,
      testName: String,
      expectedValid: Boolean,
      expectedData: Option[BigInt] = None,
      expectedPhysDestIdx: Option[Int] = None,
      expectedWritesToPhysReg: Option[Boolean] = None,
      expectedRobIdx: Option[Int] = None,
      expectedHasException: Option[Boolean] = None,
      expectedExceptionCode: Option[AluExceptionCode.E] = None
  ): Unit = {
    val prefix = if (testName.nonEmpty) s"[$testName] " else ""

    assert(
      dut.io.resultOut_valid.toBoolean == expectedValid,
      s"${prefix}resultOut.valid mismatch. Expected $expectedValid, Got ${dut.io.resultOut_valid.toBoolean}"
    )

    // Only check payload if output is valid and was expected to be valid
    if (expectedValid && dut.io.resultOut_valid.toBoolean) {
      expectedData.foreach { exp =>
        val actualData = dut.io.resultOut_payload.data.toBigInt
        val maskedExp = exp & dataMask // Ensure expected value is also masked for comparison
        assert(actualData == maskedExp, s"${prefix}data mismatch. Expected $maskedExp (from $exp), Got $actualData")
      }
      expectedPhysDestIdx.foreach(exp =>
        assert(
          dut.io.resultOut_payload.physDest.idx.toInt == exp,
          s"${prefix}physDest.idx mismatch. Expected $exp, Got ${dut.io.resultOut_payload.physDest.idx.toInt}"
        )
      )
      expectedWritesToPhysReg.foreach(exp =>
        assert(
          dut.io.resultOut_payload.writesToPhysReg.toBoolean == exp,
          s"${prefix}writesToPhysReg mismatch. Expected $exp, Got ${dut.io.resultOut_payload.writesToPhysReg.toBoolean}"
        )
      )
      expectedRobIdx.foreach(exp =>
        assert(
          dut.io.resultOut_payload.robIdx.toInt == exp,
          s"${prefix}robIdx mismatch. Expected $exp, Got ${dut.io.resultOut_payload.robIdx.toInt}"
        )
      )

      val actualHasException = dut.io.resultOut_payload.hasException.toBoolean
      expectedHasException.foreach(exp =>
        assert(actualHasException == exp, s"${prefix}hasException mismatch. Expected $exp, Got $actualHasException")
      )

      // Only check exception code if an exception was expected
      if (expectedHasException.getOrElse(false) && actualHasException) {
        expectedExceptionCode.foreach(exp =>
          assert(
            dut.io.resultOut_payload.exceptionCode.toEnum == exp,
            s"${prefix}exceptionCode mismatch. Expected $exp, Got ${dut.io.resultOut_payload.exceptionCode.toEnum}"
          )
        )
      } else if (expectedHasException.getOrElse(false) != actualHasException) {
        // Mismatch in hasException already asserted, but good to note related code won't be checked as expected.
      } else { // No exception expected, and none occurred
        // Optionally assert that exceptionCode is NONE if no exception
        if (!actualHasException) {
          assert(
            dut.io.resultOut_payload.exceptionCode.toEnum == AluExceptionCode.NONE,
            s"${prefix}exceptionCode should be NONE when no exception. Got ${dut.io.resultOut_payload.exceptionCode.toEnum}"
          )
        }
      }
    }
  }
}

// testOnly parallax.test.scala.execute.DemoAluSpec
class DemoAluSpec extends CustomSpinalSimFunSuite { // Ensure CustomSpinalSimFunSuite is correctly set up
  import AluTestHelper._
  val alu_config = DemoAluTestConfig.testConfig // Use the config for instantiating testbench

  val testDestReg = 5
  val testRobIdx = 3

  // Helper to run a standard test sequence
  def runTest(
      testName: String,
      uopCode: BaseUopCode.E = BaseUopCode.ALU,
      aluIsSub: Boolean = false,
      aluLogicOp: LogicOp.E = LogicOp.NONE,
      aluIsAdd: Boolean = false,
      imm: BigInt = 0,
      immUsage: ImmUsageType.E = ImmUsageType.NONE,
      hasDecodeExc: Boolean = false,
      physDestIdx: Int = testDestReg,
      writesToPhysReg: Boolean = true,
      robIdx: Int = testRobIdx,
      src1Val: BigInt,
      src2Val: BigInt,
      expectedData: BigInt,
      expectedHasException: Boolean = false,
      expectedExceptionCode: AluExceptionCode.E = AluExceptionCode.NONE,
      inputValid: Boolean = true,
      outputValidOverride: Option[Boolean] = None // If outputValid behavior is special (e.g. for inputInvalid test)
  ): Unit = {
    test(testName) {
      simConfig.compile(new DemoAluTestBench(alu_config)).doSim(seed = Math.abs(testName.hashCode)) { dut =>
        dut.clockDomain.forkStimulus(10)

        driveAluInput(
          dut,
          valid = inputValid,
          uopCode = uopCode,
          aluIsSub = aluIsSub,
          aluLogicOp = aluLogicOp,
          aluIsAdd = aluIsAdd,
          imm = imm,
          immUsage = immUsage,
          hasDecodeExc = hasDecodeExc,
          physDestIdx = physDestIdx,
          writesToPhysReg = writesToPhysReg,
          robIdx = robIdx,
          src1Val = src1Val,
          src2Val = src2Val
        )

        dut.clockDomain.waitSampling()

        val effectiveOutputValid = outputValidOverride.getOrElse(inputValid)

        checkAluOutput(
          dut,
          testName,
          expectedValid = effectiveOutputValid,
          expectedData =
            if (effectiveOutputValid && !expectedHasException) Some(expectedData)
            else if (expectedHasException) Some(0)
            else None, // Expect 0 data on exception per DemoAlu logic
          expectedPhysDestIdx = if (effectiveOutputValid) Some(physDestIdx) else None,
          expectedWritesToPhysReg = if (effectiveOutputValid) Some(writesToPhysReg) else None,
          expectedRobIdx = if (effectiveOutputValid) Some(robIdx) else None,
          expectedHasException = if (effectiveOutputValid) Some(expectedHasException) else None,
          expectedExceptionCode =
            if (effectiveOutputValid && expectedHasException) Some(expectedExceptionCode)
            else if (effectiveOutputValid && !expectedHasException) Some(AluExceptionCode.NONE)
            else None
        )
      }
    }
  }

  // --- Standard Operations ---
  runTest("ADD R-Type", aluIsAdd = true, src1Val = 10, src2Val = 20, expectedData = 30)
  runTest("ADD R-Type (negative result)", aluIsAdd = true, src1Val = 5, src2Val = -10, expectedData = -5)
  runTest(
    "ADD I-Type",
    aluIsAdd = true,
    imm = 100,
    immUsage = ImmUsageType.SRC_ALU,
    src1Val = 50,
    src2Val = 0 /*ignored*/,
    expectedData = 150
  )

  runTest("SUB R-Type", aluIsSub = true, src1Val = 30, src2Val = 10, expectedData = 20)
  runTest("SUB R-Type (negative result)", aluIsSub = true, src1Val = 5, src2Val = 10, expectedData = -5)
  runTest(
    "SUB I-Type",
    aluIsSub = true,
    imm = 25,
    immUsage = ImmUsageType.SRC_ALU,
    src1Val = 50,
    src2Val = 0 /*ignored*/,
    expectedData = 25
  ) // src1 - imm

  runTest("AND R-Type", aluLogicOp = LogicOp.AND, src1Val = 0xf0, src2Val = 0x0f, expectedData = 0x00)
  runTest(
    "AND I-Type",
    aluLogicOp = LogicOp.AND,
    imm = 0xaa,
    immUsage = ImmUsageType.SRC_ALU,
    src1Val = 0xf0,
    src2Val = 0 /*ignored*/,
    expectedData = 0xa0
  )

  runTest("OR R-Type", aluLogicOp = LogicOp.OR, src1Val = 0xf0, src2Val = 0x0f, expectedData = 0xff)
  runTest(
    "OR I-Type",
    aluLogicOp = LogicOp.OR,
    imm = 0xaa,
    immUsage = ImmUsageType.SRC_ALU,
    src1Val = 0x05,
    src2Val = 0 /*ignored*/,
    expectedData = 0xaf
  )

  runTest("XOR R-Type", aluLogicOp = LogicOp.XOR, src1Val = 0xff, src2Val = 0x0f, expectedData = 0xf0)
  runTest(
    "XOR I-Type",
    aluLogicOp = LogicOp.XOR,
    imm = 0xaa,
    immUsage = ImmUsageType.SRC_ALU,
    src1Val = 0x55,
    src2Val = 0 /*ignored*/,
    expectedData = 0xff
  )

  // 别上压力了
  // runTest("MUL R-Type", uopCode = BaseUopCode.MUL, src1Val = 7, src2Val = 6, expectedData = 42)
  // runTest("MUL R-Type (negative)", uopCode = BaseUopCode.MUL, src1Val = -5, src2Val = 10, expectedData = -50)
  // runTest(
  //   "MUL R-Type (overflow)",
  //   uopCode = BaseUopCode.MUL,
  //   src1Val = 0x7fffffff,
  //   src2Val = 2,
  //   expectedData = BigInt("FFFFFFFE", 16)
  // ) // Lower 32 bits

  // --- Exception Cases ---
  runTest(
    "Decode Exception Passthrough",
    hasDecodeExc = true,
    src1Val = 1,
    src2Val = 1,
    expectedData = 0, // Data is 0 on exception
    expectedHasException = true,
    expectedExceptionCode = AluExceptionCode.DECODE_EXCEPTION
  )

  // To test UNDEFINED_ALU_OP for logic, LogicOp would need an unhandled state, e.g. LogicOp.UNHANDLED_FOR_TEST
  // Assuming LogicOp.NONE is used and it's not AND/OR/XOR, it would fall into the 'otherwise' of logic ops.
  // This depends on LogicOp enum definition and DemoAlu's `logicOp =/= LogicOp.NONE` check.
  // If LogicOp has only NONE, AND, OR, XOR, then the 'otherwise' in that switch might be unreachable if logicOp can only be one of these.
  // Let's assume LogicOp could have other values or the 'otherwise' is reachable.
  // For now, test the "no specific ALU op flags set" case:
  runTest(
    "Undefined ALU Op (no flags)",
    aluIsSub = false,
    aluLogicOp = LogicOp.NONE,
    aluIsAdd = false,
    src1Val = 1,
    src2Val = 1,
    expectedData = 0,
    expectedHasException = true,
    expectedExceptionCode = AluExceptionCode.UNDEFINED_ALU_OP
  )

  runTest(
    "Dispatch to Wrong EU (LOAD Uop)",
    uopCode = BaseUopCode.LOAD, // Assuming LOAD is not ALU/MUL
    src1Val = 1,
    src2Val = 1,
    expectedData = 0,
    expectedHasException = true,
    expectedExceptionCode = AluExceptionCode.DISPATCH_TO_WRONG_EU
  )

  // --- Input Invalid Case ---
  test("Input Not Valid") {
    simConfig.compile(new DemoAluTestBench(alu_config)).doSim(seed = 123) { dut =>
      dut.clockDomain.forkStimulus(10)
      // The DemoAlu has ParallaxSim.fatal if io.uopIn.valid is false and that 'otherwise' branch is hit.
      // This test checks that output valid is low. The simulation might terminate due to the fatal call.
      // If the fatal call is removed from DemoAlu, this test just checks valid_low.
      var simThrewException = false

      driveAluInput(dut, valid = false, src1Val = 10, src2Val = 20) // Other params don't matter much
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, "Input Not Valid", expectedValid = false)
    }
  }

  thatsAll()
}
