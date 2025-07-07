package parallax.components.decode

import spinal.core._
import spinal.lib._
import parallax.common._

// --- Opcodes (修正和细化) ---
object LoongArchOpcodes {
  // Major Opcodes (top-level switch)
  def OP_BRANCH_GROUP = B"01" // [31:30] -> BEQ, BNE, BLTU, B, BL
  def OP_JIRL = B"010011" // [31:26]

  def OP_RTYPE_2R1I = B"0000001" // [31:25] -> ADDI, SLTI, ANDI, ORI
  def OP_LOAD_STORE = B"0010100" // [31:25] -> LD/ST
  def OP_LU12I = B"0001010" // [31:25]
  def OP_PCADDU12I = B"0001110" // [31:25]

  // This is the major opcode for all 2R and 2R-Shift-Imm instructions
  def OP_GROUP_00 = B"0000000" // [31:25]

  // Minor Opcodes for OP_GROUP_00 (using more specific fields)
  // For standard R-type (distinguished by inst[24:15])
  def Minor_ADD_W = B"0000100000"
  def Minor_SUB_W = B"0000100010"
  def Minor_AND = B"0000101001"
  def Minor_OR = B"0000101010"
  def Minor_XOR = B"0000101011"
  def Minor_MUL_W = B"0000111000"
  def Minor_SRL_W = B"0000101111"

  // For shift-immediate R-type (distinguished by inst[24:10])
  def Minor_SLLI_W = B"0010000001" // This is the value of inst[24:15]
  def Minor_SRLI_W = B"0010001001" // This is the value of inst[24:15]

  // Minor Opcodes for OP_RTYPE_2R1I (distinguished by inst[24:22])
  def Minor_ADDI_W_i = B"010"
  def Minor_SLTI_i = B"000"
  def Minor_ANDI_i = B"101"
  def Minor_ORI_i = B"110"

  // Minor Opcodes for OP_LOAD_STORE (distinguished by inst[24:22])
  def Minor_LD_B_m = B"000"
  def Minor_LD_W_m = B"010"
  def Minor_ST_B_m = B"100"
  def Minor_ST_W_m = B"110"

  // Minor Opcodes for Branch Group (distinguished by full 6-bit opcode)
  def Minor_B = B"010100"
  def Minor_BL = B"010101"
  def Minor_BEQ = B"010110"
  def Minor_BNE = B"010111"
  def Minor_BLTU = B"011010"
  
  // IDLE instruction: 0000011 00100 10001 level[14:0]
  def OP_IDLE = B"0000011" // [31:25]
  def IDLE_FIXED_BITS = B"0010010001" // [24:15] = 00100 10001
}

// --- Raw Fields (修正) ---
case class LA32RRawInstructionFields() extends Bundle {
  val inst = Bits(32 bits)

  // Register fields
  def rd = inst(4 downto 0).asUInt
  def rj = inst(9 downto 5).asUInt
  def rk = inst(14 downto 10).asUInt

  // Immediate fields
  def ui5 = inst(14 downto 10).asUInt
  def si12 = inst(21 downto 10)
  def ui12 = inst(21 downto 10)
  def si20 = inst(24 downto 5)

  // For branches with 16-bit offset
  def offs16 = inst(25 downto 10) // JIRL, BEQ, BNE, BLTU

  // FIX: Correctly extract the 26-bit offset for B and BL
  def offs26_b_type = inst(25 downto 16) ## inst(15 downto 0)
  
  // For IDLE instruction level field
  def idle_level = inst(14 downto 0) // level[14:0]

  // Opcode fields for decoding (more structured)
  def opcode_6b = inst(31 downto 26)
  def opcode_7b = inst(31 downto 25)

  def opcode_r_minor = inst(24 downto 15) // For ADD.W, SUB.W, etc. (10 bits)
  def opcode_r_sh_minor = inst(24 downto 10) // For SLLI.W, SRLI.W, etc. (15 bits)
  def opcode_i_minor = inst(24 downto 22) // For ADDI.W, LD.W, etc. (3 bits)
}

class LA32RSimpleDecoder(val config: PipelineConfig = PipelineConfig()) extends Component {
  val io = new Bundle {
    val instruction = in Bits (config.dataWidth)
    val pcIn = in UInt (config.pcWidth)
    val decodedUop = out(DecodedUop(config))
  }

  val fields = LA32RRawInstructionFields()
  fields.inst := io.instruction

  io.decodedUop.setDefault(
    pc = io.pcIn,
    isa = IsaType.LOONGARCH
  )

  val r0_idx = U(0, config.archRegIdxWidth)
  val r1_idx = U(1, config.archRegIdxWidth)

  val imm_sext_12 = S(fields.si12).resize(config.dataWidth)
  val imm_zext_12 = U(fields.ui12).resize(config.dataWidth)
  val imm_lu12i = (S(fields.si20) << 12).resize(config.dataWidth)
  val imm_pcadd_u12i = (S(fields.si20) << 12).resize(config.dataWidth)

  // For JIRL, BEQ, BNE, BLTU. Format: [offs[15:0], rj, rd]
  // The immediate is in inst[25:10].
  val imm_branch_16 = (S(fields.offs16) << 2).resize(config.dataWidth)

  // FIX: Use the corrected field for B/BL
  val imm_branch_26 = (S(fields.offs26_b_type) << 2).resize(config.dataWidth)

  val imm_shift_5 = fields.ui5.resize(config.dataWidth)

  // --- Main Decoding Logic (重构) ---
  switch(fields.opcode_7b) {
    is(LoongArchOpcodes.OP_IDLE) {
      // IDLE instruction: 0000011 00100 10001 level[14:0]
      // Check if bits [24:15] match the fixed pattern
      when(fields.opcode_r_minor === LoongArchOpcodes.IDLE_FIXED_BITS) {
        io.decodedUop.isValid := True
        io.decodedUop.uopCode := BaseUopCode.NOP
        io.decodedUop.exeUnit := ExeUnitType.ALU_INT
        io.decodedUop.writeArchDestEn := False
        // Level field is in fields.idle_level but we don't use it for basic CPU halt
      } otherwise {
        io.decodedUop.isValid := False
      }
    }
    
    is(LoongArchOpcodes.OP_GROUP_00) {
      // This group contains both standard R-type and shift-imm R-type
      // They have different opcode fields, so we need a nested check.
      // We can check the most specific one first. SLLI/SRLI have a unique inst[14:10] pattern.
      // Let's use `sub_opcode_r` (inst[24:15]) which is 10 bits and distinguishes them well.

      io.decodedUop.isValid := True
      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx

      switch(fields.opcode_r_minor) {
        is(LoongArchOpcodes.Minor_SLLI_W) {
          io.decodedUop.uopCode := BaseUopCode.SHIFT
          io.decodedUop.exeUnit := ExeUnitType.ALU_INT
          io.decodedUop.archSrc1.idx := fields.rj
          io.decodedUop.archSrc1.rtype := ArchRegType.GPR
          io.decodedUop.useArchSrc1 := True
          io.decodedUop.imm := imm_shift_5.asBits
          io.decodedUop.immUsage := ImmUsageType.SRC_SHIFT_AMT
          io.decodedUop.shiftCtrl.isRight := False
        }
        is(LoongArchOpcodes.Minor_SRLI_W) {
          io.decodedUop.uopCode := BaseUopCode.SHIFT
          io.decodedUop.exeUnit := ExeUnitType.ALU_INT
          io.decodedUop.archSrc1.idx := fields.rj
          io.decodedUop.archSrc1.rtype := ArchRegType.GPR
          io.decodedUop.useArchSrc1 := True
          io.decodedUop.imm := imm_shift_5.asBits
          io.decodedUop.immUsage := ImmUsageType.SRC_SHIFT_AMT
          io.decodedUop.shiftCtrl.isRight := True
          io.decodedUop.shiftCtrl.isArithmetic := False
        }
        // Fallback for other R-type instructions
        default {
          // All these use rj and rk as sources
          io.decodedUop.archSrc1.idx := fields.rj
          io.decodedUop.archSrc1.rtype := ArchRegType.GPR
          io.decodedUop.useArchSrc1 := True
          io.decodedUop.archSrc2.idx := fields.rk
          io.decodedUop.archSrc2.rtype := ArchRegType.GPR
          io.decodedUop.useArchSrc2 := True

          switch(fields.opcode_r_minor) {
            is(LoongArchOpcodes.Minor_ADD_W) {
              io.decodedUop.uopCode := BaseUopCode.ALU; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.aluCtrl.isAdd := True
            }
            is(LoongArchOpcodes.Minor_SUB_W) {
              io.decodedUop.uopCode := BaseUopCode.ALU; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.aluCtrl.isSub := True
            }
            is(LoongArchOpcodes.Minor_AND) {
              io.decodedUop.uopCode := BaseUopCode.ALU; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.aluCtrl.logicOp := LogicOp.AND
            }
            is(LoongArchOpcodes.Minor_OR) {
              io.decodedUop.uopCode := BaseUopCode.ALU; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.aluCtrl.logicOp := LogicOp.OR
            }
            is(LoongArchOpcodes.Minor_XOR) {
              io.decodedUop.uopCode := BaseUopCode.ALU; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.aluCtrl.logicOp := LogicOp.XOR
            }
            is(LoongArchOpcodes.Minor_MUL_W) {
              io.decodedUop.uopCode := BaseUopCode.MUL; io.decodedUop.exeUnit := ExeUnitType.MUL_INT
              io.decodedUop.mulDivCtrl.isSigned := True
            }
            is(LoongArchOpcodes.Minor_SRL_W) {
              io.decodedUop.uopCode := BaseUopCode.SHIFT; io.decodedUop.exeUnit := ExeUnitType.ALU_INT
              io.decodedUop.shiftCtrl.isRight := True
              io.decodedUop.shiftCtrl.isArithmetic := False
            }
            default { io.decodedUop.isValid := False }
          }
        }
      }
    }

    is(LoongArchOpcodes.OP_RTYPE_2R1I) {
      io.decodedUop.isValid := True
      io.decodedUop.uopCode := BaseUopCode.ALU
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      io.decodedUop.archDest.idx := fields.rd
      io.decodedUop.archDest.rtype := ArchRegType.GPR
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.archSrc1.idx := fields.rj
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.immUsage := ImmUsageType.SRC_ALU

      switch(fields.opcode_i_minor) {
        is(LoongArchOpcodes.Minor_ADDI_W_i) {
          io.decodedUop.imm := imm_sext_12.asBits
          io.decodedUop.aluCtrl.isAdd := True
        }
        is(LoongArchOpcodes.Minor_SLTI_i) {
          io.decodedUop.imm := imm_sext_12.asBits
          io.decodedUop.aluCtrl.isSub := True
          io.decodedUop.aluCtrl.isSigned := True
        }
        is(LoongArchOpcodes.Minor_ANDI_i) {
          io.decodedUop.imm := imm_zext_12.asBits
          io.decodedUop.aluCtrl.logicOp := LogicOp.AND
        }
        is(LoongArchOpcodes.Minor_ORI_i) {
          io.decodedUop.imm := imm_zext_12.asBits
          io.decodedUop.aluCtrl.logicOp := LogicOp.OR
        }
        default { io.decodedUop.isValid := False }
      }
    }

    is(LoongArchOpcodes.OP_LU12I) {
      // Logic is correct, no change needed
      io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.ALU;
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR;
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.imm := imm_lu12i.asBits; io.decodedUop.immUsage := ImmUsageType.SRC_ALU
      io.decodedUop.aluCtrl.isAdd := True
    }

    is(LoongArchOpcodes.OP_PCADDU12I) {
      // Logic is correct, no change needed
      io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.ALU;
      io.decodedUop.exeUnit := ExeUnitType.ALU_INT
      io.decodedUop.archDest.idx := fields.rd; io.decodedUop.archDest.rtype := ArchRegType.GPR;
      io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx
      io.decodedUop.useArchSrc1 := False
      io.decodedUop.imm := imm_pcadd_u12i.asBits; io.decodedUop.immUsage := ImmUsageType.SRC_ALU
      io.decodedUop.aluCtrl.isAdd := True
    }

    is(LoongArchOpcodes.OP_LOAD_STORE) {
      // Logic is correct, but use new minor opcodes
      io.decodedUop.isValid := True
      io.decodedUop.exeUnit := ExeUnitType.MEM
      io.decodedUop.archSrc1.idx := fields.rj
      io.decodedUop.archSrc1.rtype := ArchRegType.GPR
      io.decodedUop.useArchSrc1 := True
      io.decodedUop.imm := imm_sext_12.asBits
      io.decodedUop.immUsage := ImmUsageType.MEM_OFFSET

      switch(fields.opcode_i_minor) {
        is(LoongArchOpcodes.Minor_LD_B_m) {
          io.decodedUop.uopCode := BaseUopCode.LOAD; io.decodedUop.archDest.idx := fields.rd;
          io.decodedUop.archDest.rtype := ArchRegType.GPR
          io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx; io.decodedUop.memCtrl.isStore := False
          io.decodedUop.memCtrl.isSignedLoad := True; io.decodedUop.memCtrl.size := MemAccessSize.B
        }
        is(LoongArchOpcodes.Minor_LD_W_m) {
          io.decodedUop.uopCode := BaseUopCode.LOAD; io.decodedUop.archDest.idx := fields.rd;
          io.decodedUop.archDest.rtype := ArchRegType.GPR
          io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx; io.decodedUop.memCtrl.isStore := False
          io.decodedUop.memCtrl.isSignedLoad := True; io.decodedUop.memCtrl.size := MemAccessSize.W
        }
        is(LoongArchOpcodes.Minor_ST_B_m) {
          io.decodedUop.uopCode := BaseUopCode.STORE; io.decodedUop.writeArchDestEn := False;
          io.decodedUop.archSrc2.idx := fields.rd
          io.decodedUop.archSrc2.rtype := ArchRegType.GPR; io.decodedUop.useArchSrc2 := True
          io.decodedUop.memCtrl.isStore := True; io.decodedUop.memCtrl.size := MemAccessSize.B
        }
        is(LoongArchOpcodes.Minor_ST_W_m) {
          io.decodedUop.uopCode := BaseUopCode.STORE; io.decodedUop.writeArchDestEn := False;
          io.decodedUop.archSrc2.idx := fields.rd
          io.decodedUop.archSrc2.rtype := ArchRegType.GPR; io.decodedUop.useArchSrc2 := True
          io.decodedUop.memCtrl.isStore := True; io.decodedUop.memCtrl.size := MemAccessSize.W
        }
        default { io.decodedUop.isValid := False }
      }
    }

    default {
      // This 'default' handles remaining opcodes, mainly branches
      switch(fields.opcode_6b) {
        is(LoongArchOpcodes.OP_JIRL) {
          io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.JUMP_REG;
          io.decodedUop.exeUnit := ExeUnitType.BRU
          io.decodedUop.isBranchOrJump := True; io.decodedUop.archDest.idx := fields.rd;
          io.decodedUop.archDest.rtype := ArchRegType.GPR
          io.decodedUop.writeArchDestEn := fields.rd =/= r0_idx; io.decodedUop.archSrc1.idx := fields.rj
          io.decodedUop.archSrc1.rtype := ArchRegType.GPR; io.decodedUop.useArchSrc1 := True
          io.decodedUop.imm := imm_branch_16.asBits; io.decodedUop.immUsage := ImmUsageType.BRANCH_OFFSET
          io.decodedUop.branchCtrl.isJump := True; io.decodedUop.branchCtrl.isIndirect := True
          io.decodedUop.branchCtrl.isLink := True; io.decodedUop.branchCtrl.linkReg.idx := fields.rd
        }
        is(LoongArchOpcodes.Minor_B) {
          io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.JUMP_IMM;
          io.decodedUop.exeUnit := ExeUnitType.BRU
          io.decodedUop.isBranchOrJump := True; io.decodedUop.writeArchDestEn := False;
          io.decodedUop.imm := imm_branch_26.asBits
          io.decodedUop.immUsage := ImmUsageType.JUMP_OFFSET; io.decodedUop.branchCtrl.isJump := True
        }
        is(LoongArchOpcodes.Minor_BL) {
          io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.JUMP_IMM;
          io.decodedUop.exeUnit := ExeUnitType.BRU
          io.decodedUop.isBranchOrJump := True; io.decodedUop.archDest.idx := r1_idx
          io.decodedUop.archDest.rtype := ArchRegType.GPR; io.decodedUop.writeArchDestEn := True
          io.decodedUop.imm := imm_branch_26.asBits; io.decodedUop.immUsage := ImmUsageType.JUMP_OFFSET
          io.decodedUop.branchCtrl.isJump := True; io.decodedUop.branchCtrl.isLink := True;
          io.decodedUop.branchCtrl.linkReg.idx := r1_idx
        }
        is(LoongArchOpcodes.Minor_BEQ, LoongArchOpcodes.Minor_BNE, LoongArchOpcodes.Minor_BLTU) {
          io.decodedUop.isValid := True; io.decodedUop.uopCode := BaseUopCode.BRANCH;
          io.decodedUop.exeUnit := ExeUnitType.BRU
          io.decodedUop.isBranchOrJump := True; io.decodedUop.writeArchDestEn := False
          // Correct format for these branches: rj is at [9:5], rd is at [4:0]
          io.decodedUop.archSrc1.idx := fields.rj; io.decodedUop.archSrc1.rtype := ArchRegType.GPR
          io.decodedUop.useArchSrc1 := True; io.decodedUop.archSrc2.idx := fields.rd
          io.decodedUop.archSrc2.rtype := ArchRegType.GPR; io.decodedUop.useArchSrc2 := True
          io.decodedUop.imm := imm_branch_16.asBits; io.decodedUop.immUsage := ImmUsageType.BRANCH_OFFSET
          when(fields.opcode_6b === LoongArchOpcodes.Minor_BEQ) {
            io.decodedUop.branchCtrl.condition := BranchCondition.EQ
          }
            .elsewhen(fields.opcode_6b === LoongArchOpcodes.Minor_BNE) {
              io.decodedUop.branchCtrl.condition := BranchCondition.NE
            }
            .elsewhen(fields.opcode_6b === LoongArchOpcodes.Minor_BLTU) {
              io.decodedUop.branchCtrl.condition := BranchCondition.LTU
            }
        }
        default {
          io.decodedUop.isValid := False
        }
      }
    }
  }

  when(!io.decodedUop.isValid) {
    io.decodedUop.uopCode := BaseUopCode.ILLEGAL
    io.decodedUop.hasDecodeException := True
    io.decodedUop.decodeExceptionCode := DecodeExCode.DECODE_ERROR
  }
}
