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
  def OP_LOAD_STORE = B"010100" // [31:25] -> LD/ST
  def OP_LU12I = B"0001010" // [31:25]
  def OP_PCADDU12I = B"0001110" // [31:25]

  // This is the major opcode for all 2R and 2R-Shift-Imm instructions
  def OP_GROUP_00 = B"0000000" // [31:25]

  // Minor Opcodes for OP_GROUP_00 (using more specific fields)
  // For standard R-type (distinguished by inst[24:15])
  def Minor_ADD_W  = B"0000100000"
  def Minor_SUB_W  = B"0000100010"
  def Minor_AND    = B"0000101001"
  def Minor_OR     = B"0000101010"
  def Minor_NOR    = B"0000101000"
  def Minor_XOR    = B"0000101011"
  def Minor_MUL_W  = B"0000111000"

  // For shift-immediate R-type (distinguished by inst[24:15])
  def Minor_SLLI_W = B"0010000001" // inst[24:15] for SLLI.W
  def Minor_SRLI_W = B"0010001001" // inst[24:15] for SRLI.W
  def Minor_SRAI_W = B"0010010001" // inst[24:15] for SRAI.W

  // For register-register shift instructions (distinguished by inst[24:15])
  def Minor_SLL_W  = B"0000101110"  // inst[24:15] for SLL.W
  def Minor_SRL_W  = B"0000101111"  // inst[24:15] for SRL.W
  def Minor_SRA_W  = B"0000110000"  // inst[24:15] for SRA.W
  def Minor_SLT    = B"0000100100"
  def Minor_SLTU   = B"0000100101"

  // Minor Opcodes for OP_RTYPE_2R1I (distinguished by inst[24:22])
  def Minor_ADDI_W_i = B"010"
  def Minor_SLTUI_i  = B"001"
  def Minor_SLTI_i   = B"000"
  def Minor_ANDI_i   = B"101"
  def Minor_ORI_i    = B"110"
  def Minor_XORI_i   = B"111"

  // 10-bit Opcodes for Load/Store (inst[31:22])
  def OP_LD_B  =  B"0010100000"
  def OP_LD_H  =  B"0010100001"
  def OP_LD_W  =  B"0010100010"
  def OP_ST_B  =  B"0010100100"
  def OP_ST_H  =  B"0010100101"
  def OP_ST_W  =  B"0010100110"
  def OP_LD_BU = B"0010101000"
  def OP_LD_HU = B"0010101001"
  

  // Minor Opcodes for Branch Group (distinguished by full 6-bit opcode)
  def Minor_B     = B"010100"
  def Minor_BL    = B"010101"
  def Minor_BEQ   = B"010110"
  def Minor_BNE   = B"010111"
  def Minor_BLT   = B"011000"
  def Minor_BLTU  = B"011010"
  def Minor_BGE   = B"011001"
  def Minor_BGEU  = B"011011"
  
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
  def opcode_10b = inst(31 downto 22)

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

  // --- 立即数预计算 ---
  val r0_idx = U(0, config.archRegIdxWidth)
  val r1_idx = U(1, config.archRegIdxWidth)

  val imm_sext_12 = S(fields.si12).resize(config.dataWidth).asBits
  val imm_zext_12 = U(fields.ui12).resize(config.dataWidth).asBits
  val imm_lu12i = (fields.si20 ## B(0, 12 bits)).asUInt.resize(config.dataWidth).asBits
  val imm_pcadd_s12i = (fields.si20 ## B(0, 12 bits)).asSInt.asBits
  val imm_branch_16 = (S(fields.offs16) << 2).resize(config.dataWidth).asBits
  val imm_branch_26 = (S(fields.offs26_b_type) << 2).resize(config.dataWidth).asBits
  val imm_shift_5 = fields.ui5.resize(config.dataWidth).asBits

  // --- 1. 扁平化的指令识别标志 (并行计算) ---

  // OP_GROUP_00 类型的指令
  val is_add_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_ADD_W
  val is_sub_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SUB_W
  val is_mul_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_MUL_W

  val is_and    = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_AND
  val is_or     = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_OR
  val is_nor    = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_NOR
  val is_xor    = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_XOR

  val is_sll_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SLL_W
  val is_srl_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SRL_W
  val is_sra_w  = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SRA_W
  val is_slli_w = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SLLI_W
  val is_srli_w = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SRLI_W
  val is_srai_w = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SRAI_W

  val is_slt    = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SLT
  val is_sltu   = fields.opcode_7b === LoongArchOpcodes.OP_GROUP_00 && fields.opcode_r_minor === LoongArchOpcodes.Minor_SLTU

  // OP_RTYPE_2R1I 类型的指令
  val is_addi_w = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_ADDI_W_i

  val is_andi   = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_ANDI_i
  val is_ori    = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_ORI_i
  val is_xori   = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_XORI_i

  val is_slti   = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_SLTI_i
  val is_sltui  = fields.opcode_7b === LoongArchOpcodes.OP_RTYPE_2R1I && fields.opcode_i_minor === LoongArchOpcodes.Minor_SLTUI_i

  val is_ld_b   = fields.opcode_10b === LoongArchOpcodes.OP_LD_B
  val is_ld_h   = fields.opcode_10b === LoongArchOpcodes.OP_LD_H
  val is_ld_w   = fields.opcode_10b === LoongArchOpcodes.OP_LD_W
  val is_st_b   = fields.opcode_10b === LoongArchOpcodes.OP_ST_B
  val is_st_h   = fields.opcode_10b === LoongArchOpcodes.OP_ST_H
  val is_st_w   = fields.opcode_10b === LoongArchOpcodes.OP_ST_W
  val is_ld_bu  = fields.opcode_10b === LoongArchOpcodes.OP_LD_BU
  val is_ld_hu  = fields.opcode_10b === LoongArchOpcodes.OP_LD_HU

  val is_lu12i    = fields.opcode_7b === LoongArchOpcodes.OP_LU12I
  val is_pcaddu12i = fields.opcode_7b === LoongArchOpcodes.OP_PCADDU12I
  val is_jirl   = fields.opcode_6b === LoongArchOpcodes.OP_JIRL
  val is_b      = fields.opcode_6b === LoongArchOpcodes.Minor_B
  val is_bl     = fields.opcode_6b === LoongArchOpcodes.Minor_BL
  val is_beq    = fields.opcode_6b === LoongArchOpcodes.Minor_BEQ
  val is_bne    = fields.opcode_6b === LoongArchOpcodes.Minor_BNE
  val is_blt    = fields.opcode_6b === LoongArchOpcodes.Minor_BLT
  val is_bltu   = fields.opcode_6b === LoongArchOpcodes.Minor_BLTU
  val is_bge    = fields.opcode_6b === LoongArchOpcodes.Minor_BGE
  val is_bgeu   = fields.opcode_6b === LoongArchOpcodes.Minor_BGEU

  val is_idle   = fields.opcode_7b === LoongArchOpcodes.OP_IDLE && fields.opcode_r_minor === LoongArchOpcodes.IDLE_FIXED_BITS

  // --- 用于逻辑简化的分组标志 ---
  val is_r_type_alu   = is_add_w | is_sub_w | is_and | is_or | is_nor | is_xor | is_slt | is_sltu
  val is_r_type_shift = is_sll_w | is_srl_w | is_sra_w
  val is_r_type       = is_r_type_alu | is_r_type_shift | is_mul_w
  val is_shift_imm    = is_slli_w | is_srli_w | is_srai_w
  val is_i_type       = is_addi_w | is_slti | is_sltui | is_andi | is_ori | is_xori
  val is_load         = is_ld_b | is_ld_h | is_ld_w | is_ld_bu | is_ld_hu
  val is_store        = is_st_b | is_st_h | is_st_w
  val is_mem_op       = is_load | is_store
  val is_branch       = is_beq | is_bne | is_blt | is_bltu | is_bge | is_bgeu
  val is_jump         = is_b | is_bl | is_jirl
  val is_branch_or_jump = is_branch | is_jump
  
  // --- 2. 扁平化并行解码逻辑 ---
  // 为每个输出信号独立设置逻辑

  io.decodedUop.setDefault(pc = io.pcIn, isa = IsaType.LOONGARCH).allowOverride

  val isValid = is_r_type | is_shift_imm | is_i_type | is_mem_op | is_lu12i | is_pcaddu12i | is_branch_or_jump | is_idle
  io.decodedUop.isValid := isValid

  // --- UopCode 和执行单元 --- 
  io.decodedUop.uopCode := BaseUopCode.ILLEGAL
  when(is_r_type_alu | is_i_type | is_lu12i | is_pcaddu12i) { io.decodedUop.uopCode := BaseUopCode.ALU }
  when(is_r_type_shift | is_shift_imm) { io.decodedUop.uopCode := BaseUopCode.SHIFT }
  when(is_mul_w)   { io.decodedUop.uopCode := BaseUopCode.MUL }
  when(is_load)    { io.decodedUop.uopCode := BaseUopCode.LOAD }
  when(is_store)   { io.decodedUop.uopCode := BaseUopCode.STORE }
  when(is_b | is_bl){ io.decodedUop.uopCode := BaseUopCode.JUMP_IMM }
  when(is_jirl)    { io.decodedUop.uopCode := BaseUopCode.JUMP_REG }
  when(is_branch)  { io.decodedUop.uopCode := BaseUopCode.BRANCH }
  when(is_idle)    { io.decodedUop.uopCode := BaseUopCode.IDLE }

  io.decodedUop.exeUnit := ExeUnitType.NONE
  when(is_r_type | is_shift_imm | is_i_type | is_lu12i | is_pcaddu12i | is_idle) { io.decodedUop.exeUnit := ExeUnitType.ALU_INT }
  when(is_mul_w)       { io.decodedUop.exeUnit := ExeUnitType.MUL_INT }
  when(is_mem_op)      { io.decodedUop.exeUnit := ExeUnitType.MEM }
  when(is_branch_or_jump) { io.decodedUop.exeUnit := ExeUnitType.BRU }

  // --- 源/目标寄存器 ---
  io.decodedUop.useArchSrc1 := is_r_type | is_shift_imm | is_i_type | is_mem_op | is_jirl | is_branch
  io.decodedUop.useArchSrc2 := is_r_type | is_store | is_branch

  io.decodedUop.archSrc1.idx := 0
  when(io.decodedUop.useArchSrc1) { io.decodedUop.archSrc1.idx := fields.rj }
  
  io.decodedUop.archSrc2.idx := 0
  when(is_r_type) { io.decodedUop.archSrc2.idx := fields.rk } // R-Type 使用 rk
  when(is_store | is_branch) { io.decodedUop.archSrc2.idx := fields.rd } // Store 和 Branch 使用 rd

  io.decodedUop.archDest.idx := 0
  when(is_r_type | is_shift_imm | is_i_type | is_load | is_lu12i | is_pcaddu12i | is_jirl) { io.decodedUop.archDest.idx := fields.rd }
  when(is_bl) { io.decodedUop.archDest.idx := r1_idx }

  io.decodedUop.writeArchDestEn := False
  when((is_r_type | is_shift_imm | is_i_type | is_load | is_lu12i | is_pcaddu12i | is_jirl) && fields.rd =/= r0_idx) { io.decodedUop.writeArchDestEn := True }
  when(is_bl) { io.decodedUop.writeArchDestEn := True }
  
  // rtype 默认为 NONE, 在需要时设置为 GPR
  when(io.decodedUop.useArchSrc1) { io.decodedUop.archSrc1.rtype := ArchRegType.GPR }
  when(io.decodedUop.useArchSrc2) { io.decodedUop.archSrc2.rtype := ArchRegType.GPR }
  when(io.decodedUop.writeArchDestEn) { io.decodedUop.archDest.rtype := ArchRegType.GPR }

  // --- 立即数 ---
  io.decodedUop.imm := 0
  when(is_addi_w | is_slti | is_sltui | is_mem_op) { io.decodedUop.imm := imm_sext_12 }
  when(is_andi | is_ori | is_xori) { io.decodedUop.imm := imm_zext_12 }
  when(is_lu12i)                { io.decodedUop.imm := imm_lu12i }
  when(is_pcaddu12i)            { io.decodedUop.imm := imm_pcadd_s12i }
  when(is_branch | is_jirl)     { io.decodedUop.imm := imm_branch_16 }
  when(is_b | is_bl)            { io.decodedUop.imm := imm_branch_26 }
  when(is_shift_imm)            { io.decodedUop.imm := imm_shift_5 }

  io.decodedUop.immUsage := ImmUsageType.NONE
  when(is_i_type | is_lu12i | is_pcaddu12i) { io.decodedUop.immUsage := ImmUsageType.SRC_ALU }
  when(is_shift_imm)    { io.decodedUop.immUsage := ImmUsageType.SRC_SHIFT_AMT }
  when(is_mem_op)       { io.decodedUop.immUsage := ImmUsageType.MEM_OFFSET }
  when(is_branch)       { io.decodedUop.immUsage := ImmUsageType.BRANCH_OFFSET }
  when(is_b | is_bl)    { io.decodedUop.immUsage := ImmUsageType.JUMP_OFFSET }
  when(is_jirl)         { io.decodedUop.immUsage := ImmUsageType.BRANCH_OFFSET } // JIRL 使用 branch-style offset

  // --- 控制信号 ---
  io.decodedUop.isBranchOrJump := is_branch_or_jump
  when(is_pcaddu12i)    { io.decodedUop.src1IsPc := True }
  // ALU Control
  when(is_add_w | is_addi_w | is_lu12i | is_pcaddu12i) { io.decodedUop.aluCtrl.isAdd     := True }
  when(is_sub_w)                                       { io.decodedUop.aluCtrl.isSub     := True }
  when(is_slt   | is_slti)                             { io.decodedUop.aluCtrl.condition := BranchCondition.LT }
  when(is_sltu  | is_sltui)                            { io.decodedUop.aluCtrl.condition := BranchCondition.LTU }
  when(is_and   | is_andi)                             { io.decodedUop.aluCtrl.logicOp   := LogicOp.AND }
  when(is_nor)                                         { io.decodedUop.aluCtrl.logicOp   := LogicOp.NOR }
  when(is_or    | is_ori)                              { io.decodedUop.aluCtrl.logicOp   := LogicOp.OR }
  when(is_xor   | is_xori)                             { io.decodedUop.aluCtrl.logicOp   := LogicOp.XOR }
  io.decodedUop.aluCtrl.valid := (is_add_w | is_addi_w | is_lu12i | is_pcaddu12i) | 
                                 (is_sub_w) |
                                 (is_and | is_andi) |
                                 (is_nor) |
                                 (is_or | is_ori) |
                                 (is_xor | is_xori) |
                                 (is_slt | is_slti) |
                                 (is_sltu | is_sltui)

  // SHIFT Control
  when(is_srl_w | is_srli_w | is_sra_w | is_srai_w) { io.decodedUop.shiftCtrl.isRight := True }
  when(is_sra_w | is_srai_w) { io.decodedUop.shiftCtrl.isArithmetic := True }
  io.decodedUop.shiftCtrl.valid := (is_srl_w | is_srli_w | is_sra_w | is_srai_w) |
                                   (is_srai_w) |
                                   (is_slli_w)
  
  // MUL/DIV Control
  when(is_mul_w) { io.decodedUop.mulDivCtrl.isSigned := True }
  io.decodedUop.mulDivCtrl.valid := is_mul_w

  // MEM Control
  when(is_store) { io.decodedUop.memCtrl.isStore := True }

  // 默认设为无符号加载，仅在需要时设为有符号
  io.decodedUop.memCtrl.isSignedLoad := False 
  when(is_ld_b | is_ld_w) { 
    io.decodedUop.memCtrl.isSignedLoad := True 
  }

  // 设置访存大小
  when(is_ld_b | is_st_b | is_ld_bu) { io.decodedUop.memCtrl.size := MemAccessSize.B }
  when(is_ld_w | is_st_w) { io.decodedUop.memCtrl.size := MemAccessSize.W }

  // BRANCH Control
  when(is_jump) { io.decodedUop.branchCtrl.isJump := True }
  when(is_jirl) { io.decodedUop.branchCtrl.isIndirect := True }
  when(is_bl | is_jirl) { io.decodedUop.branchCtrl.isLink := True }
  when(is_bl)   { io.decodedUop.branchCtrl.linkReg.idx := r1_idx }
  when(is_jirl) { io.decodedUop.branchCtrl.linkReg.idx := fields.rd }
  
  when(is_beq)  { io.decodedUop.branchCtrl.condition := BranchCondition.EQ }
  when(is_bne)  { io.decodedUop.branchCtrl.condition := BranchCondition.NE }
  when(is_blt)  { io.decodedUop.branchCtrl.condition := BranchCondition.LT }
  when(is_bltu) { io.decodedUop.branchCtrl.condition := BranchCondition.LTU }
  when(is_bge)  { io.decodedUop.branchCtrl.condition := BranchCondition.GE }
  when(is_bgeu) { io.decodedUop.branchCtrl.condition := BranchCondition.GEU }
  
  // --- 异常处理 ---
  when(!isValid) {
    io.decodedUop.uopCode := BaseUopCode.ILLEGAL
    io.decodedUop.hasDecodeException := True
    io.decodedUop.decodeExceptionCode := DecodeExCode.DECODE_ERROR
  }
}
