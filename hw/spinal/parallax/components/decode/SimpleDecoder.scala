package parallax.components.decode

import spinal.core._
import spinal.lib._
import parallax.common._

object InstructionOpcodes {
  def LD = B"000000"
  def ST = B"000001"
  def ADD = B"000010"
  def ADDI = B"000011"
  def NEG = B"000100"
  def AND = B"000101"
  def OR = B"000110"
  def XOR = B"000111"
  def NOT = B"001000"
  def MUL = B"001001"
  def DIV = B"001010"
  def LI = B"001011"
  def EQ = B"001100"
  def SGT = B"001101" // Signed Greater Than
  def BCOND = B"001110"
}

object InstructionOpcodesBigInts {
  def LD = BigInt("000000", 2)
  def ST = BigInt("000001", 2)
  def ADD = BigInt("000010", 2)
  def ADDI = BigInt("000011", 2)
  def NEG = BigInt("000100", 2)
  def AND = BigInt("000101", 2)
  def OR = BigInt("000110", 2)
  def XOR = BigInt("000111", 2)
  def NOT = BigInt("001000", 2)
  def MUL = BigInt("001001", 2)
  def DIV = BigInt("001010", 2)
  def LI = BigInt("001011", 2)
  def EQ = BigInt("001100", 2)
  def SGT = BigInt("001101", 2) // Signed Greater Than
  def BCOND = BigInt("001110", 2)
}

// --- Instruction Field Extraction ---
case class RawInstructionFields() extends Bundle {
  val inst = Bits(32 bits)

  def opcode = inst(31 downto 26)
  def rd = inst(25 downto 21).asUInt
  def rs = inst(20 downto 16).asUInt
  def rt = inst(15 downto 11).asUInt
  def imm16 = inst(15 downto 0)
}

// --- Decoder Component ---
class SimpleDecoder extends Component {
  val microOpConfig = MicroOpConfig()
  val io = new Bundle {
    val instruction = in Bits (32 bits)
    val microOp = out(MicroOp(microOpConfig))
  }

  val fields = RawInstructionFields()
  fields.inst := io.instruction

  io.microOp.setDefault() // Initialize all fields to defaults

  val imm_sext_16_to_32 = S(fields.imm16).resize(32)
  val r0_idx = U(0, 5 bits)

  switch(fields.opcode) {
    is(InstructionOpcodes.LD) {
      io.microOp.isValid := (io.instruction =/= 0) // All zero is illegal
      io.microOp.uopType := MicroOpType.LOAD
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs // Base address register
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.writeRd := fields.rd =/= r0_idx
      // imm for LD [Rs] is effectively 0 for address calculation (Rs + 0)
      // If ISA was LD Rd, offset(Rs), then imm would be used.
      // For this ISA, Rs is the address. AluOp.COPY_SRC2 can represent this.
      // The LSU will use archRegRs as the address.

      io.microOp.memOpFlags.accessType := MemoryAccessType.LOAD_U
      io.microOp.memOpFlags.size := MemoryOpSize.WORD
    }
    is(InstructionOpcodes.ST) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.STORE
      io.microOp.archRegRs := fields.rs // Base address register
      io.microOp.archRegRt := fields.rt // Data to store is in instruction's Rd field
      io.microOp.useRs := True // Rs for address
      io.microOp.useRt := True // Rt (from inst's Rd field) for data
      // No writeRd

      io.microOp.memOpFlags.accessType := MemoryAccessType.STORE
      io.microOp.memOpFlags.size := MemoryOpSize.WORD
    }
    is(InstructionOpcodes.ADD) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.ADD
    }
    is(InstructionOpcodes.ADDI) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_IMM
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.writeRd := fields.rd =/= r0_idx
      io.microOp.imm := imm_sext_16_to_32

      io.microOp.aluFlags.op := AluOp.ADD // ALU performs Rs + Imm
    }
    is(InstructionOpcodes.NEG) { // Rd = -Rs (0 - Rs)
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG // Consumes one reg Rs, effectively 0 - Rs
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs // This is the Rs to be negated
      // archRegRt is not used by instruction, but ALU might see it as src1=0, src2=Rs
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.writeRd := fields.rd =/= r0_idx
      // imm field will be 0, ALU will take 0 as one operand.
      io.microOp.imm := S(0)

      io.microOp.aluFlags.op := AluOp.SUB // ALU will compute 0 - archRegRs_value
    }
    is(InstructionOpcodes.AND) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.AND
    }
    is(InstructionOpcodes.OR) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.OR
    }
    is(InstructionOpcodes.XOR) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.XOR
    }
    is(InstructionOpcodes.NOT) { // Rd = ~Rs (Rs XOR -1)
      io.microOp.isValid := True
      // This is like an ALU_IMM op where the immediate is fixed to -1
      // Or an ALU_REG where Rt is implicitly -1.
      // Let's model it as ALU_IMM where imm is set to -1.
      io.microOp.uopType := MicroOpType.ALU_IMM
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs // Rs is the register to be NOTed
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.writeRd := fields.rd =/= r0_idx
      io.microOp.imm := S(-1) // The value to XOR with (0xFFFFFFFF)

      io.microOp.aluFlags.op := AluOp.XOR // ALU will compute archRegRs_value XOR S(-1)
    }
    is(InstructionOpcodes.MUL) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.MUL
    }
    is(InstructionOpcodes.DIV) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.DIV
    }
    is(InstructionOpcodes.LI) { // Rd = sext(Imm)
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.LOAD_IMM
      io.microOp.archRegRd := fields.rd
      // Rs is not used by instruction encoding, but ALU might see it as 0 + Imm
      io.microOp.useRd := True
      // io.microOp.useRs could be false, or true if ALU takes 0 from Rs path
      io.microOp.writeRd := fields.rd =/= r0_idx
      io.microOp.imm := imm_sext_16_to_32

      // ALU will effectively pass imm to Rd. Can be ALUOp.ADD (0 + imm) or COPY_SRC1 (if imm is routed to src1)
      io.microOp.aluFlags.op := AluOp.ADD // Assuming ALU does 0 + imm for LI
    }
    is(InstructionOpcodes.EQ) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.EQ
    }
    is(InstructionOpcodes.SGT) {
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.ALU_REG
      io.microOp.archRegRd := fields.rd
      io.microOp.archRegRs := fields.rs
      io.microOp.archRegRt := fields.rt
      io.microOp.useRd := True
      io.microOp.useRs := True
      io.microOp.useRt := True
      io.microOp.writeRd := fields.rd =/= r0_idx

      io.microOp.aluFlags.op := AluOp.SGT
    }
    is(InstructionOpcodes.BCOND) { // if (Rs != 0) PC += sext(Imm)*4
      io.microOp.isValid := True
      io.microOp.uopType := MicroOpType.BRANCH_COND
      io.microOp.archRegRs := fields.rs // Register for condition check
      // Rd, Rt not used by instruction
      io.microOp.useRs := True
      // No writeRd
      io.microOp.imm := imm_sext_16_to_32 // This is the word offset

      io.microOp.ctrlFlowInfo.condition := BranchCond.NOT_ZERO // Condition is Rs != 0
      io.microOp.ctrlFlowInfo.targetOffset := (imm_sext_16_to_32 << 2).resized // sext(Imm)*4
    }
    default {
      // io.microOp.isValid is already False due to setDefault()
    }
  }
}
