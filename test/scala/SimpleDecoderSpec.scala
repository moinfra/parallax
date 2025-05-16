package parallax.test.scala

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import parallax.components.decode._
import parallax.common._ // For DecodedUop, PipelineConfig, Enums

// Helper function to check DecodedUop fields
object DecoderTestHelper {
  // Define expected logic op values if used in checks consistently
  val LOGIC_OP_AND = B"001"
  val LOGIC_OP_OR  = B"010"
  val LOGIC_OP_XOR = BigInt("011",2)

  def checkDecodedUop(
      dutBench: SimpleDecoderTestBench, // The testbench wrapping the decoder
      pc: Long, // For logging
      // Core Info
      isValid: Boolean,
      expectedUopCode: BaseUopCode.E = null,
      expectedExeUnit: ExeUnitType.E = null,
      expectedIsa: IsaType.E = IsaType.DEMO, // Assuming DEMO for this decoder
      // Operands (Architectural)
      expectedArchDestIdx: Option[Int] = None,
      expectedArchDestRType: ArchRegType.E = ArchRegType.GPR,
      expectedWriteArchDestEn: Option[Boolean] = None,
      expectedArchSrc1Idx: Option[Int] = None,
      expectedArchSrc1RType: ArchRegType.E = ArchRegType.GPR,
      expectedUseArchSrc1: Option[Boolean] = None,
      expectedArchSrc2Idx: Option[Int] = None,
      expectedArchSrc2RType: ArchRegType.E = ArchRegType.GPR,
      expectedUseArchSrc2: Option[Boolean] = None,
      // Immediate
      expectedImm: Option[BigInt] = None,
      expectedImmUsage: ImmUsageType.E = null,
      // Control Flags (add more as needed)
      expectedAluIsSub: Option[Boolean] = None,
      expectedAluLogicOp: Option[BigInt] = None, // Check as BigInt for Bits
      expectedMemIsStore: Option[Boolean] = None,
      expectedMemSize: MemAccessSize.E = null,
      expectedMemIsSignedLoad: Option[Boolean] = None,
      expectedBranchIsJump: Option[Boolean] = None,
      expectedBranchCond: BranchCondition.E = null,
      expectedIsBranchOrJump: Option[Boolean] = None,
      expectedMulDivIsDiv: Option[Boolean] = None,
      expectedMulDivIsSigned: Option[Boolean] = None
  ): Unit = {
    val uop = dutBench.io.decodedUop // Access the DecodedUop from the testbench IO

    def contextMsg(field: String) = s"[PC=0x${pc.toHexString}, OpCode=${uop.uopCode.toEnum}] $field mismatch."

    assert(uop.isValid.toBoolean == isValid, contextMsg("isValid") + s" Expected $isValid, Got ${uop.isValid.toBoolean}")
    if (!isValid) return // If not valid, most other fields are don't care or default

    if (expectedUopCode != null) assert(uop.uopCode.toEnum == expectedUopCode, contextMsg("uopCode"))
    if (expectedExeUnit != null) assert(uop.exeUnit.toEnum == expectedExeUnit, contextMsg("exeUnit"))
    if (expectedIsa != null) assert(uop.isa.toEnum == expectedIsa, contextMsg("isa"))

    expectedArchDestIdx.foreach(exp => assert(uop.archDest.idx.toInt == exp, contextMsg("archDest.idx")))
    if(expectedArchDestIdx.isDefined) assert(uop.archDest.rtype.toEnum == expectedArchDestRType, contextMsg("archDest.rtype"))
    expectedWriteArchDestEn.foreach(exp => assert(uop.writeArchDestEn.toBoolean == exp, contextMsg("writeArchDestEn")))

    expectedArchSrc1Idx.foreach(exp => assert(uop.archSrc1.idx.toInt == exp, contextMsg("archSrc1.idx")))
    if(expectedArchSrc1Idx.isDefined) assert(uop.archSrc1.rtype.toEnum == expectedArchSrc1RType, contextMsg("archSrc1.rtype"))
    expectedUseArchSrc1.foreach(exp => assert(uop.useArchSrc1.toBoolean == exp, contextMsg("useArchSrc1")))

    expectedArchSrc2Idx.foreach(exp => assert(uop.archSrc2.idx.toInt == exp, contextMsg("archSrc2.idx")))
    if(expectedArchSrc2Idx.isDefined) assert(uop.archSrc2.rtype.toEnum == expectedArchSrc2RType, contextMsg("archSrc2.rtype"))
    expectedUseArchSrc2.foreach(exp => assert(uop.useArchSrc2.toBoolean == exp, contextMsg("useArchSrc2")))

    expectedImm.foreach { expSigned =>

      val actualImmExtendedRaw = uop.imm.toBigInt
      val width = uop.imm.getWidth // This 'width' is the *extended* width
      val mask = (BigInt(1) << width) - 1
      val expectedImmAsExtendedRaw = expSigned & mask
  
      assert(actualImmExtendedRaw == expectedImmAsExtendedRaw,
             contextMsg("imm") + s" Expected signed value $expSigned (which as $width-bit raw is $expectedImmAsExtendedRaw), " +
                                 s"Got $width-bit raw $actualImmExtendedRaw from DUT (already extended)")
  }
    if (expectedImmUsage != null) assert(uop.immUsage.toEnum == expectedImmUsage, contextMsg("immUsage"))

    // ALU Control
    expectedAluIsSub.foreach(exp => assert(uop.aluCtrl.isSub.toBoolean == exp, contextMsg("aluCtrl.isSub")))
    expectedAluLogicOp.foreach(exp => assert(uop.aluCtrl.logicOp.toBigInt == exp, contextMsg("aluCtrl.logicOp")))

    // Memory Control
    expectedMemIsStore.foreach(exp => assert(uop.memCtrl.isStore.toBoolean == exp, contextMsg("memCtrl.isStore")))
    if (expectedMemSize != null) assert(uop.memCtrl.size.toEnum == expectedMemSize, contextMsg("memCtrl.size"))
    expectedMemIsSignedLoad.foreach(exp => assert(uop.memCtrl.isSignedLoad.toBoolean == exp, contextMsg("memCtrl.isSignedLoad")))
    
    // Branch Control
    expectedBranchIsJump.foreach(exp => assert(uop.branchCtrl.isJump.toBoolean == exp, contextMsg("branchCtrl.isJump")))
    if (expectedBranchCond != null) assert(uop.branchCtrl.condition.toEnum == expectedBranchCond, contextMsg("branchCtrl.condition"))
    expectedIsBranchOrJump.foreach(exp => assert(uop.isBranchOrJump.toBoolean == exp, contextMsg("isBranchOrJump")))

    // Mul/Div Control
    expectedMulDivIsDiv.foreach(exp => assert(uop.mulDivCtrl.isDiv.toBoolean == exp, contextMsg("mulDivCtrl.isDiv")))
    expectedMulDivIsSigned.foreach(exp => assert(uop.mulDivCtrl.isSigned.toBoolean == exp, contextMsg("mulDivCtrl.isSigned")))

    // Check PC
    assert(uop.pc.toLong == pc, contextMsg("PC") + s" Expected $pc, Got ${uop.pc.toLong}")
  }
}

class SimpleDecoderTestBench(val config: PipelineConfig = PipelineConfig()) extends Component {
  val io = new Bundle {
    val instruction = in Bits (config.dataWidth)
    val pcIn = in UInt(config.pcWidth)
    val decodedUop = out(DecodedUop(config)) // Uses implicit config
  }
  val decoder = new SimpleDecoder() // Implicit config passed here
  decoder.io.instruction := io.instruction
  decoder.io.pcIn := io.pcIn
  io.decodedUop := decoder.io.decodedUop

  io.decodedUop.simPublic() // Make all fields of decodedUop public for sim
}

class SimpleDecoderSpec extends CustomSpinalSimFunSuite {
  import DecoderTestHelper._ // Import the helper object

  val testConfig: PipelineConfig = PipelineConfig()
  // InstructionOpcodesBigInts can be used from parallax.components.decode if accessible, or defined locally
  // For simplicity, assuming it's accessible.

  test("Decoder - NOP (Illegal instruction 0x0)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      val currentPC = 0x1000L

      dut.io.instruction #= 0x00000000
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(dut, pc = currentPC, isValid = false, expectedUopCode = BaseUopCode.ILLEGAL)
    }
  }
  
  test("Decoder - NOP (Illegal instruction high bits)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 43) { dut =>
      dut.clockDomain.forkStimulus(10)
      val currentPC = 0x1004L
      dut.io.instruction #= 0x7FFFFFFF 
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(dut, pc = currentPC, isValid = false, expectedUopCode = BaseUopCode.ILLEGAL)
    }
  }


  test("Decoder - ADD R1, R2, R3") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val instruction = (InstructionOpcodesBigInts.ADD<< 26) | (1 << 21) | (2 << 16) | (3 << 11)
      val currentPC = 0x2000L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()

      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedArchSrc2Idx = Some(3), expectedUseArchSrc2 = Some(true),
        expectedAluIsSub = Some(false)
      )
    }
  }

  test("Decoder - ADD R0, R2, R3 (write to R0)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val instruction = (InstructionOpcodesBigInts.ADD << 26) | (0 << 21) | (2 << 16) | (3 << 11)
      val currentPC = 0x2004L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(0), expectedWriteArchDestEn = Some(false), // R0 not written
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedArchSrc2Idx = Some(3), expectedUseArchSrc2 = Some(true),
        expectedAluIsSub = Some(false)
      )
    }
  }

  test("Decoder - ADDI R1, R2, 100") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val immValue = 100
      val instruction = (InstructionOpcodesBigInts.ADDI << 26) | (1 << 21) | (2 << 16) | immValue
      val currentPC = 0x2008L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedUseArchSrc2 = Some(false), // Rt not used by ADDI
        expectedImm = Some(BigInt(immValue)), expectedImmUsage = ImmUsageType.SRC_ALU,
        expectedAluIsSub = Some(false)
      )
    }
  }
  
  test("Decoder - ADDI R1, R2, -100 (negative immediate)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val immValueRaw: Short = -100
      val immValueSext = BigInt(immValueRaw) // Sign extension to BigInt for comparison
      val instruction = (InstructionOpcodesBigInts.ADDI << 26) | (1 << 21) | (2 << 16) | (BigInt(immValueRaw) & 0xFFFF)
      val currentPC = 0x200CL
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(immValueSext), expectedImmUsage = ImmUsageType.SRC_ALU,
        expectedAluIsSub = Some(false)
      )
    }
  }

  test("Decoder - LD R1, [R2]") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val instruction = (InstructionOpcodesBigInts.LD << 26) | (1 << 21) | (2 << 16)
      val currentPC = 0x2010L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.LOAD, expectedExeUnit = ExeUnitType.MEM,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true), // Base address
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(0), expectedImmUsage = ImmUsageType.MEM_OFFSET, // Offset is 0
        expectedMemIsStore = Some(false), expectedMemSize = MemAccessSize.W, expectedMemIsSignedLoad = Some(false)
      )
    }
  }

  test("Decoder - ST R1, [R2] (ST rt, rs -> Mem[rs] = rt)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: ST, Rs (addr): R2 (idx 2), Rt (data): R1 (idx 1)
      val instruction = (InstructionOpcodesBigInts.ST << 26) | (2 << 16) | (1 << 11)
      val currentPC = 0x2014L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.STORE, expectedExeUnit = ExeUnitType.MEM,
        expectedWriteArchDestEn = Some(false), // ST does not write to arch Rd
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true), // Address
        expectedArchSrc2Idx = Some(1), expectedUseArchSrc2 = Some(true), // Data
        expectedImm = Some(0), expectedImmUsage = ImmUsageType.MEM_OFFSET,
        expectedMemIsStore = Some(true), expectedMemSize = MemAccessSize.W
      )
    }
  }

  test("Decoder - BCOND R1, 0x10 (target PC + 0x40)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val immWordOffset = 0x10
      val expectedByteOffset = immWordOffset * 4
      // Opcode: BCOND, Rs (cond): R1 (idx 1), Imm: 0x10
      val instruction = (InstructionOpcodesBigInts.BCOND << 26) | (1 << 16) | immWordOffset
      val currentPC = 0x2018L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.BRANCH, expectedExeUnit = ExeUnitType.BRU,
        expectedIsBranchOrJump = Some(true),
        expectedWriteArchDestEn = Some(false),
        expectedArchSrc1Idx = Some(1), expectedUseArchSrc1 = Some(true), // Condition reg
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(BigInt(expectedByteOffset)), // Byte offset
        expectedImmUsage = ImmUsageType.BRANCH_OFFSET,
        expectedBranchIsJump = Some(false),
        expectedBranchCond = BranchCondition.NEZ // Rs != 0
      )
    }
  }

  test("Decoder - LI R1, 0x1234") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      val immValue = 0x1234
      // Opcode: LI, Rd: R1 (idx 1), Imm: 0x1234
      val instruction = (InstructionOpcodesBigInts.LI << 26) | (1 << 21) | immValue
      val currentPC = 0x201CL
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, // LI as 0 + Imm
        expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedUseArchSrc1 = Some(false), // No register source for LI
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(immValue), // Sign-extended from 16 bits
        expectedImmUsage = ImmUsageType.SRC_ALU,
        expectedAluIsSub = Some(false) // 0 + imm
      )
    }
  }

  test("Decoder - NEG R1, R2") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: NEG, Rd: R1, Rs: R2
      val instruction = (InstructionOpcodesBigInts.NEG << 26) | (1 << 21) | (2 << 16)
      val currentPC = 0x2020L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, // NEG as Imm - Rs (where Imm is 0)
        expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(0), // Imm = 0 for 0 - Rs
        expectedImmUsage = ImmUsageType.SRC_ALU,
        expectedAluIsSub = Some(true) // For Imm - Rs
      )
    }
  }

  test("Decoder - NOT R1, R2") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: NOT, Rd: R1, Rs: R2
      val instruction = (InstructionOpcodesBigInts.NOT << 26) | (1 << 21) | (2 << 16)
      val currentPC = 0x2024L
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.io.pcIn #= currentPC
      dut.clockDomain.waitSampling()
      checkDecodedUop(
        dut, pc = currentPC, isValid = true,
        expectedUopCode = BaseUopCode.ALU, // NOT as Rs XOR Imm (where Imm is -1)
        expectedExeUnit = ExeUnitType.ALU_INT,
        expectedArchDestIdx = Some(1), expectedWriteArchDestEn = Some(true),
        expectedArchSrc1Idx = Some(2), expectedUseArchSrc1 = Some(true),
        expectedUseArchSrc2 = Some(false),
        expectedImm = Some(-1), // Imm = -1 for Rs XOR -1
        expectedImmUsage = ImmUsageType.SRC_ALU,
        expectedAluLogicOp = Some(LOGIC_OP_XOR)
      )
    }
  }
  
  // Tests for MUL, DIV, EQ, SGT would follow a similar pattern,
  // mapping their old AluOp to new BaseUopCode and relevant CtrlFlags.
  // For EQ & SGT, the current AluCtrlFlags might be insufficient for a full distinct
  // operation and may rely on specific ALU unit behavior for set-on-condition.
  // The provided solution for SimpleDecoder uses aluCtrl.isSub and aluCtrl.isSigned for these.
  thatsAll()
}
