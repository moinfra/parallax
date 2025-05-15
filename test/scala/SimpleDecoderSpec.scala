package boson.test.scala

import spinal.core._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite
import boson.demo2.components.decode._
import spinal.core.sim.SimDataPimper
import spinal.core.sim.SimBoolPimper
import spinal.core.sim.SimEnumPimper
import spinal.core.sim.SimBaseTypePimper
import spinal.core.sim.SimBitVectorPimper

// Assuming your Decoder and related Bundles/Enums are in a package, e.g., `mycore.decode`
// import mycore.decode._ // Adjust this to your actual package structure

// If Decoder is in the default package for simplicity:
// (Ensure InstructionOpcodes, AluOp, MicroOpType, AluInfo, etc. are accessible)

// Helper function to check MicroOp fields
// This can be expanded to be very detailed
object Helper {
  def checkMicroOp(
      dut: SimpleDecoderTestBench,
      pc: Long, // For logging purposes
      isValid: Boolean,
      uopType: MicroOpType.E = null, // Use .E for SpinalEnum value in simulation
      archRegRd: Option[Int] = None,
      archRegRs: Option[Int] = None,
      archRegRt: Option[Int] = None,
      useRd: Option[Boolean] = None,
      useRs: Option[Boolean] = None,
      useRt: Option[Boolean] = None,
      writeRd: Option[Boolean] = None,
      imm: Option[BigInt] = None,
      // AluInfo
      aluOp: AluOp.E = null,
      // MemInfo
      memAccessType: MemoryAccessType.E = null,
      memSize: MemoryOpSize.E = null,
      // CtrlFlowInfo
      branchCond: BranchCond.E = null,
      branchOffset: Option[BigInt] = None
  ): Unit = {
    assert(
      dut.io.microOp.isValid.toBoolean == isValid,
      s"[PC=0x${pc.toHexString}] isValid mismatch. Expected $isValid, Got ${dut.io.microOp.isValid.toBoolean}"
    )
    if (!isValid) return // If not valid, no need to check other fields

    if (uopType != null)
      assert(
        dut.io.microOp.uopType.toEnum == uopType,
        s"[PC=0x${pc.toHexString}] uopType mismatch. Expected $uopType, Got ${dut.io.microOp.uopType.toEnum}"
      )

    archRegRd.foreach(exp =>
      assert(
        dut.io.microOp.archRegRd.toInt == exp,
        s"[PC=0x${pc.toHexString}] archRegRd mismatch. Expected $exp, Got ${dut.io.microOp.archRegRd.toInt}"
      )
    )
    archRegRs.foreach(exp =>
      assert(
        dut.io.microOp.archRegRs.toInt == exp,
        s"[PC=0x${pc.toHexString}] archRegRs mismatch. Expected $exp, Got ${dut.io.microOp.archRegRs.toInt}"
      )
    )
    archRegRt.foreach(exp =>
      assert(
        dut.io.microOp.archRegRt.toInt == exp,
        s"[PC=0x${pc.toHexString}] archRegRt mismatch. Expected $exp, Got ${dut.io.microOp.archRegRt.toInt}"
      )
    )

    useRd.foreach(exp => assert(dut.io.microOp.useRd.toBoolean == exp, s"[PC=0x${pc.toHexString}] useRd mismatch"))
    useRs.foreach(exp => assert(dut.io.microOp.useRs.toBoolean == exp, s"[PC=0x${pc.toHexString}] useRs mismatch"))
    useRt.foreach(exp => assert(dut.io.microOp.useRt.toBoolean == exp, s"[PC=0x${pc.toHexString}] useRt mismatch"))
    writeRd.foreach(exp =>
      assert(dut.io.microOp.writeRd.toBoolean == exp, s"[PC=0x${pc.toHexString}] writeRd mismatch")
    )

    imm.foreach(exp =>
      assert(
        dut.io.microOp.imm.toBigInt == exp,
        s"[PC=0x${pc.toHexString}] imm mismatch. Expected $exp, Got ${dut.io.microOp.imm.toBigInt}"
      )
    )

    if (uopType == MicroOpType.ALU_REG || uopType == MicroOpType.ALU_IMM || uopType == MicroOpType.LOAD_IMM) {
      if (aluOp != null)
        assert(
          dut.io.microOp.aluInfo.op.toEnum == aluOp,
          s"[PC=0x${pc.toHexString}] aluOp mismatch. Expected $aluOp, Got ${dut.io.microOp.aluInfo.op.toEnum}"
        )
    }

    if (uopType == MicroOpType.LOAD || uopType == MicroOpType.STORE) {
      if (memAccessType != null)
        assert(
          dut.io.microOp.memInfo.accessType.toEnum == memAccessType,
          s"[PC=0x${pc.toHexString}] memAccessType mismatch"
        )
      if (memSize != null)
        assert(dut.io.microOp.memInfo.size.toEnum == memSize, s"[PC=0x${pc.toHexString}] memSize mismatch")
    }

    if (uopType == MicroOpType.BRANCH_COND) {
      if (branchCond != null)
        assert(
          dut.io.microOp.ctrlFlowInfo.condition.toEnum == branchCond,
          s"[PC=0x${pc.toHexString}] branchCond mismatch"
        )
      branchOffset.foreach(exp =>
        assert(
          dut.io.microOp.ctrlFlowInfo.targetOffset.toBigInt == exp,
          s"[PC=0x${pc.toHexString}] branchOffset mismatch"
        )
      )
    }
  }
}

class SimpleDecoderTestBench extends Component {
  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val microOp = out(MicroOp(MicroOpConfig()))
  }
  val decoder = new SimpleDecoder()
  decoder.io.instruction := io.instruction
  io.microOp := decoder.io.microOp

  io.simPublic
}

class SimpleDecoderSpec extends SpinalSimFunSuite { // Or extends SpinalSimFunSuite if you prefer that structure
  import spinal.core.sim._

  onlyVerilator()
  // Simulation configuration
  def simConfig = SimConfig.withWave // Enable VCD waves for debugging

  test("Decoder - NOP (Illegal instruction)") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.instruction #= 0x00000000 // A true NOP or an illegal instruction
      dut.clockDomain.waitSampling()
      Helper.checkMicroOp(dut, pc = 0x0, isValid = false) // Assuming 0x0 is an illegal opcode mapping

      dut.io.instruction #= 0x7fffffff // Another illegal instruction
      dut.clockDomain.waitSampling()
      Helper.checkMicroOp(dut, pc = 0x4, isValid = false)
    }
  }

  test("Decoder - ADD R1, R2, R3") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000010, Rd: 00001, Rs: 00010, Rt: 00011, rest: 0s
      // 000010 00001 00010 00011 00000 000000 => 0x08221800
      val instruction = BigInt("00001000001000100001100000000000", 2)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_REG,
        archRegRd = Some(1),
        archRegRs = Some(2),
        archRegRt = Some(3),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(true),
        writeRd = Some(true), // Assuming R1 is not R0
        aluOp = AluOp.ADD
      )
    }
  }

  test("Decoder - ADD R0, R2, R3 (write to R0)") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000010, Rd: 00000, Rs: 00010, Rt: 00011, rest: 0s
      // 000010 00000 00010 00011 00000 000000 => 0x08021800
      val instruction = BigInt("00001000000000100001100000000000", 2)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_REG,
        archRegRd = Some(0),
        archRegRs = Some(2),
        archRegRt = Some(3),
        useRd = Some(true), // Rd field is used
        useRs = Some(true),
        useRt = Some(true),
        writeRd = Some(false), // But R0 is not written
        aluOp = AluOp.ADD
      )
    }
  }

  test("Decoder - ADDI R1, R2, 100") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000011, Rd: 00001, Rs: 00010, Imm: 100 (0x0064)
      // 000011 00001 00010 0000000001100100 => 0x0C220064
      val immValue = 100

      val instruction = (InstructionOpcodesBigInts.ADDI << 26) |
        (BigInt(1) << 21) | (BigInt(2) << 16) | BigInt(immValue)
      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_IMM,
        archRegRd = Some(1),
        archRegRs = Some(2),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(false), // Rt not used by ADDI
        writeRd = Some(true),
        imm = Some(immValue), // Sign-extended if negative, but 100 is positive
        aluOp = AluOp.ADD
      )
    }
  }

  test("Decoder - ADDI R1, R2, -100 (negative immediate)") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000011, Rd: 00001, Rs: 00010, Imm: -100 (0xFF9C in 16 bits)
      // 000011 00001 00010 1111111110011100 => 0x0C22FF9C
      val immValueRaw: Short = -100 // Signed 16-bit
      val immValueSext = BigInt(immValueRaw) // Sign extension to BigInt for comparison
      val instruction = (InstructionOpcodesBigInts.ADDI << 26) |
        (BigInt(1) << 21) | (BigInt(2) << 16) | (BigInt(immValueRaw) & 0xffff) // Mask to 16 bits

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_IMM,
        archRegRd = Some(1),
        archRegRs = Some(2),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(false),
        writeRd = Some(true),
        imm = Some(immValueSext), // Should be sign-extended -100
        aluOp = AluOp.ADD
      )
    }
  }

  test("Decoder - LD R1, [R2]") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000000, Rd: 00001, Rs: 00010, rest: 0s
      // 000000 00001 00010 0000000000000000 => 0x00220000
      val instruction = (InstructionOpcodesBigInts.LD << 26) |
        (BigInt(1) << 21) | (BigInt(2) << 16)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.LOAD,
        archRegRd = Some(1),
        archRegRs = Some(2),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(false), // Rt not used for LD [Rs]
        writeRd = Some(true),
        memAccessType = MemoryAccessType.LOAD_U,
        memSize = MemoryOpSize.WORD
      )
    }
  }

  test("Decoder - ST [R2], R1") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000001, "Rd" (source data Rt): 00001, Rs (address): 00010, rest: 0s
      // 000001 00001 00010 0000000000000000 => 0x04220000
      val instruction = (InstructionOpcodesBigInts.ST << 26) |
        (BigInt(2) << 16) | // This is archRegRs (address)
        (BigInt(1) << 11) // This is archRegRt (data source)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.STORE,
        // archRegRd is not a destination for ST
        archRegRs = Some(2), // Address
        archRegRt = Some(1), // Data from instruction's "Rd" field
        useRd = Some(false), // Not a destination
        useRs = Some(true),
        useRt = Some(true),
        writeRd = Some(false), // ST does not write to arch Rd
        memAccessType = MemoryAccessType.STORE,
        memSize = MemoryOpSize.WORD
      )
    }
  }

  test("Decoder - BCOND R1, 0x10 (target PC+0x40)") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 001110, Rs: 00001, "Rd" (unused): 00000, Imm: 0x10 (word offset)
      // 001110 00001 00000 0000000000010000 => 0x38200010
      val immWordOffset = 0x10
      val expectedByteOffset = immWordOffset * 4
      val instruction = (InstructionOpcodesBigInts.BCOND << 26) |
        (BigInt(1) << 16) | // Rs for condition
        (BigInt(immWordOffset))

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.BRANCH_COND,
        archRegRs = Some(1),
        useRs = Some(true),
        useRd = Some(false), // No Rd destination
        useRt = Some(false),
        writeRd = Some(false),
        imm = Some(immWordOffset), // The raw immediate from instruction
        branchCond = BranchCond.NOT_ZERO,
        branchOffset = Some(expectedByteOffset)
      )
    }
  }

  test("Decoder - LI R1, 0x1234") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 001011, Rd: 00001, "Rs" (unused): 00000, Imm: 0x1234
      // 001011 00001 00000 0001001000110100 => 0x2C201234
      val immValue = 0x1234
      val instruction = (InstructionOpcodesBigInts.LI << 26) |
        (BigInt(1) << 21) | // Rd
        BigInt(immValue)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.LOAD_IMM,
        archRegRd = Some(1),
        useRd = Some(true),
        useRs = Some(false), // Or true if ALU needs a path for 0
        useRt = Some(false),
        writeRd = Some(true),
        imm = Some(immValue),
        aluOp = AluOp.ADD // Assuming LI uses ALU with 0 + imm
      )
    }
  }

  test("Decoder - NEG R1, R2") {
    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 000100, Rd: 00001, Rs: 00010, "Rt" (unused/0s): 00000, rest 0s
      // 000100 00001 00010 00000 00000 000000 => 0x10220000
      val instruction = (InstructionOpcodesBigInts.NEG << 26) |
        (BigInt(1) << 21) | (BigInt(2) << 16)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_REG, // Or a special unary type if ALU has 0 input
        archRegRd = Some(1),
        archRegRs = Some(2),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(false), // Rt from instruction not used functionally for NEG
        writeRd = Some(true),
        imm = Some(0), // For ALU doing 0 - Rs
        aluOp = AluOp.SUB
      )
    }
  }

  test("Decoder - NOT R1, R2") {

    simConfig.compile(new SimpleDecoderTestBench).doSim(seed = 42) { dut =>
      // Opcode: 001000, Rd: 00001, Rs: 00010, "Rt" (unused/0s): 00000, rest 0s
      // 001000 00001 00010 00000 00000 000000 => 0x20220000
      val instruction = (InstructionOpcodesBigInts.NOT << 26) |
        (BigInt(1) << 21) | (BigInt(2) << 16)

      dut.clockDomain.forkStimulus(10)
      dut.io.instruction #= instruction
      dut.clockDomain.waitSampling()

      Helper.checkMicroOp(
        dut,
        pc = 0x0,
        isValid = true,
        uopType = MicroOpType.ALU_IMM, // Rs XOR -1
        archRegRd = Some(1),
        archRegRs = Some(2),
        useRd = Some(true),
        useRs = Some(true),
        useRt = Some(false),
        writeRd = Some(true),
        imm = Some(BigInt(-1)), // For ALU doing Rs XOR -1
        aluOp = AluOp.XOR
      )
    }
  }
}
