package parallax.components.decode

import spinal.core._
import spinal.lib._
import parallax.common._

/// DEMO instruction set
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
  def ILLEGAL = BigInt("111111", 2)
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
class SimpleDecoder(val config: PipelineConfig = PipelineConfig()) extends Component { // Make config implicit and provide a default
  val io = new Bundle {
    val instruction = in Bits (config.dataWidth) // Use config for instruction width
    val pcIn = in UInt (config.pcWidth) // Decoder needs PC
    val decodedUop = out(DecodedUop(config)) // Output is DecodedUop
  }

  val fields = RawInstructionFields()
  fields.inst := io.instruction

  // Initialize all fields to defaults
  // The DecodedUop bundle's implicit config will be picked up from the class's implicit config
  io.decodedUop.setDefault(
    pc = io.pcIn,
    isa = IsaType.DEMO
  )
  // val LogicOp.PASS_ADD_SUB = B"000" // If ALU uses logicOp to select add/sub path

  val imm_sext_16_to_dataWidth = S(fields.imm16).resize(config.dataWidth)
  val r0_idx = U(0, config.archRegIdxWidth)

  switch(fields.opcode) {
    is(InstructionOpcodes.LD) { // LD Rd, [Rs] (assuming offset is 0 or handled by LSU with Rs only)
      io.decodedUop.isValid := (io.instruction =/= 0) // Assuming all-zero is not a valid LD
      io.decodedUop.uopCode := BaseUopCode.LOAD
      io.decodedUop.exeUnit := ExeUnitType.MEM

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      io.decodedUop.archSrc1.idx := fields.rs // Base address register
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      // For LD Rd, imm(Rs) -> imm would be used. For LD Rd, [Rs], imm is 0.
      io.decodedUop.immUsage := ImmUsageType.MEM_OFFSET // For Rs + offset
      io.decodedUop.imm := B(0, config.dataWidth) // Effective offset is 0

      io.decodedUop.memCtrl.isStore := False
      io.decodedUop.memCtrl.isSignedLoad := False // LOAD_U
      io.decodedUop.memCtrl.size := MemAccessSize.W
    }
    is(InstructionOpcodes.ST) { // ST Rt, [Rs] (Store Rt into address Rs)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.STORE
      io.decodedUop.exeUnit := ExeUnitType.MEM

      // No architectural destination register for ST
      io.decodedUop.writeArchDestEn := False

      io.decodedUop.archSrc1.idx := fields.rs // Base address register
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      io.decodedUop.archSrc2.idx := fields.rt // Data to store
      io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.immUsage := ImmUsageType.MEM_OFFSET
      io.decodedUop.imm := B(0, config.dataWidth) // Effective offset is 0

      io.decodedUop.memCtrl.isStore := True
      io.decodedUop.memCtrl.size := MemAccessSize.W
    }
    is(InstructionOpcodes.ADD) { // ADD Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      io.decodedUop.archSrc1.idx := fields.rs
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      io.decodedUop.archSrc2.idx := fields.rt
      io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.aluCtrl.valid := True
      io.decodedUop.aluCtrl.isSub := False
      io.decodedUop.aluCtrl.isAdd := True
    }
    is(InstructionOpcodes.ADDI) { // ADDI Rd, Rs, Imm
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      io.decodedUop.archSrc1.idx := fields.rs
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      io.decodedUop.imm := imm_sext_16_to_dataWidth.asBits.resized
      io.decodedUop.immUsage := ImmUsageType.SRC_ALU

      io.decodedUop.aluCtrl.valid := True
      io.decodedUop.aluCtrl.isSub := False
      io.decodedUop.aluCtrl.isAdd := True
    }
    is(InstructionOpcodes.NEG) { // NEG Rd, Rs (Rd = 0 - Rs)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      io.decodedUop.archSrc1.idx := fields.rs // This is the Rs to be negated
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      // ALU computes imm - src1Val => 0 - Rs
      io.decodedUop.imm := B(0, config.dataWidth)
      io.decodedUop.immUsage := ImmUsageType.SRC_ALU

      io.decodedUop.aluCtrl.valid := True
      io.decodedUop.aluCtrl.isSub := True
      io.decodedUop.aluCtrl.isAdd := False
    }
    is(InstructionOpcodes.AND) { // AND Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.aluCtrl.logicOp := LogicOp.AND
    }
    is(InstructionOpcodes.OR) { // OR Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      // Similar to AND
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.aluCtrl.logicOp := LogicOp.OR
    }
    is(InstructionOpcodes.XOR) { // XOR Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      // Similar to AND
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.aluCtrl.logicOp := LogicOp.XOR
    }
    is(InstructionOpcodes.NOT) { // NOT Rd, Rs (Rd = Rs XOR -1)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      io.decodedUop.archSrc1.idx := fields.rs // Rs is the register to be NOTed
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      io.decodedUop.imm := S(-1, config.dataWidth).asBits // The value to XOR with (0xFFFFFFFF...)
      io.decodedUop.immUsage := ImmUsageType.SRC_ALU

      io.decodedUop.aluCtrl.logicOp := LogicOp.XOR // ALU will compute archSrc1_value XOR Imm
    }
    is(InstructionOpcodes.MUL) { // MUL Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.MUL
      io.decodedUop.exeUnit := ExeUnitType.MUL_INT

      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.mulDivCtrl.valid := True
      io.decodedUop.mulDivCtrl.isDiv := False
      // Assuming signed multiply for this demo op. Add to ISA spec if it can be unsigned.
      io.decodedUop.mulDivCtrl.isSigned := True
    }
    is(InstructionOpcodes.DIV) { // DIV Rd, Rs, Rt
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.DIV
      io.decodedUop.exeUnit := ExeUnitType.DIV_INT
      // Similar to MUL
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      io.decodedUop.mulDivCtrl.valid := True
      io.decodedUop.mulDivCtrl.isDiv := True
      io.decodedUop.mulDivCtrl.isSigned := True // Assuming signed
    }
    is(InstructionOpcodes.LI) { // LI Rd, sext(Imm) (Rd = 0 + sext(Imm))
      io.decodedUop.isValid := True
      // Represent as ALU op: 0 + Imm
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      // No register source from instruction, ALU will use 0 as one operand
      io.decodedUop.useArchSrc1 := False

      io.decodedUop.imm := imm_sext_16_to_dataWidth.asBits.resized
      io.decodedUop.immUsage := ImmUsageType.SRC_ALU

      io.decodedUop.aluCtrl.valid := True // For 0 + imm
      io.decodedUop.aluCtrl.isSub := False // For 0 + imm
      io.decodedUop.aluCtrl.isAdd := True
    }
    is(InstructionOpcodes.EQ) { // EQ Rd, Rs, Rt (Rd = (Rs == Rt) ? 1 : 0)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU // ALU must support set-on-condition
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT

      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      // To implement Rd = (Rs == Rt): ALU computes Rs - Rt. If zero, Rd = 1, else Rd = 0.
      io.decodedUop.aluCtrl.valid := True
      io.decodedUop.aluCtrl.isSub := True
      io.decodedUop.aluCtrl.isAdd := False
      // The ALU execution unit would need a mode to output 1 if (src1-src2)==0, else 0.
      // This might need an extension to AluCtrlFlags or a specific BaseUopCode.CMP if not directly mappable.
      // For now, this is a simplification.
    }
    is(InstructionOpcodes.SGT) { // SGT Rd, Rs, Rt (Rd = (Rs > Rt signed) ? 1 : 0)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU // ALU must support set-on-condition
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      // Similar to EQ
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rs; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.archSrc2.idx := fields.rt; io.decodedUop.archSrc2.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc2 := True

      // To implement Rd = (Rs > Rt signed): ALU computes Rs - Rt.
      // If result is positive AND non-zero (for signed >), Rd = 1. More complex.
      // Typically (Rs > Rt) is equivalent to (Rt < Rs).
      // (Rs - Rt) -> check sign bit and zero bit.
      io.decodedUop.aluCtrl.valid := True
      io.decodedUop.aluCtrl.isSub := True
      io.decodedUop.aluCtrl.isAdd := False
      io.decodedUop.aluCtrl.isSigned := True // Comparison is signed
      // ALU unit needs mode for "set if signed greater than".
    }
    is(InstructionOpcodes.BCOND) { // BCOND Rs, Imm (if (Rs != 0) PC += sext(Imm)*4)
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.BRANCH
      io.decodedUop.exeUnit := ExeUnitType.BRU
      io.decodedUop.isBranchOrJump := True

      // No architectural destination register for BCOND
      io.decodedUop.writeArchDestEn := False

      io.decodedUop.archSrc1.idx := fields.rs // Register for condition check
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True

      // Immediate is PC-relative byte offset
      val byteOffset = imm_sext_16_to_dataWidth << 2
      io.decodedUop.imm := byteOffset.asBits.resized
      io.decodedUop.immUsage := ImmUsageType.BRANCH_OFFSET

      io.decodedUop.branchCtrl.isJump := False
      io.decodedUop.branchCtrl.isIndirect := False
      io.decodedUop.branchCtrl.condition := BranchCondition.NEZ // Condition is Rs != 0 (compare Rs against Zero)
    }
    default {
      io.decodedUop.isValid := False // Already False due to setDefault(), but explicit is fine
      io.decodedUop.uopCode := BaseUopCode.ILLEGAL
      io.decodedUop.hasDecodeException := True
      io.decodedUop.decodeExceptionCode := DecodeExCode.DECODE_ERROR
      // Could set decodeExceptionCode to an "illegal instruction" code
    }
  }
}
