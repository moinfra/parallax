package parallax.common

import spinal.core._
import spinal.lib._


object AluOp extends SpinalEnum {
  val NOP, ADD, SUB, AND, OR, XOR, MUL, DIV, SGT, EQ,
  COPY_SRC1, // Pass through src1 (e.g., for LI if src1 is considered 0 or loaded with imm)
  COPY_SRC2  // Pass through src2 (e.g., for address calculation in LD/ST if Rs is src2)
  = newElement()
  // For NEG: Rd = 0 - Rs -> SUB with src1=0, src2=Rs
  // For NOT: Rd = Rs ^ -1 -> XOR with src1=Rs, src2=S(-1)
  // For LI: Rd = Imm -> COPY_SRC1 with src1=Imm (or ADD with src1=0, src2=Imm)
  // For LD/ST address: Addr = Rs -> COPY_SRC2 with src2=Rs (or ADD with src1=0, src2=Rs)
}

object MicroOpType extends SpinalEnum {
  val ILLEGAL,
  ALU_REG,     // R-Type ALU ops (Rs, Rt -> Rd)
  ALU_IMM,     // I-Type ALU ops (Rs, Imm -> Rd)
  LOAD,
  STORE,
  BRANCH_COND,
  LOAD_IMM     // Specifically for LI Rd, Imm
  = newElement()
}

// --- MicroOp Sub-Bundles ---

case class AluFlags() extends Bundle {
  val op = AluOp()
  // We can infer if an immediate is used from MicroOpType (ALU_IMM, LOAD_IMM)
  // or specific AluOp (e.g. if ADDI was a distinct AluOp).
  // For simplicity here, the main 'imm' field in MicroOp will be used by ALU_IMM types.
}

object MemoryOpSize extends SpinalEnum { // For future ISA extensions
  val WORD = newElement() // Add BYTE, HALF_WORD if needed
}
object MemoryAccessType extends SpinalEnum { // If we need more fine-grained mem ops
    val LOAD_U, LOAD_S, STORE = newElement()
}

case class MemOpFlags() extends Bundle {
  // For current ISA:
  // LD: type=LOAD_U, size=WORD. Address is Rs.
  // ST: type=STORE, size=WORD. Address is Rs. Data from Rt.
  val accessType = MemoryAccessType()
  val size = MemoryOpSize()
  // val isSigned = Bool() // Covered by MemoryAccessType (LOAD_S vs LOAD_U)
  // Address calculation is Rs + 0 for this ISA.
  // If complex addressing modes (e.g. Rs + ImmOffset), immOffset could be here
  // or use the main MicroOp.imm field.
}

object BranchCond extends SpinalEnum {
  // For BCOND Rs, Imm: check if Rs is NOT_ZERO
  // For future (e.g. BEQ Rs, Rt, Imm): could have EQ_REGS, NE_REGS etc.
  val ALWAYS, NOT_ZERO = newElement()
}

case class ControlFlowInfo() extends Bundle {
  val condition = BranchCond()
  // val compareReg1 = UInt(5 bits) // For BCOND, this is Rs, already in MicroOp.archRegRs
  // val compareReg2 = UInt(5 bits) // Not used for BCOND
  val targetOffset = SInt(32 bits) // PC-relative byte offset
}


case class MicroOpConfig(
    physRegIdxWidth: BitCount = 6 bits,
    dataWidth: BitCount = 32 bits
)
/* 
 * physRegIdxWidth: MicroOp 现在参数化物理寄存器索引的位宽。
 * physRegRs: 重命名阶段填充的 archRegRs 对应的物理寄存器索引。
 * physRegRt: 重命名阶段填充的 archRegRt 对应的物理寄存器索引。
 * physRegRdOld: 在为 archRegRd 分配新物理寄存器之前，archRegRd 原本映射的物理寄存器。这对于处理异常、分支预测错误时的状态恢复很重要，也用于提交时释放旧的物理寄存器。
 * physRegRdNew: 重命名阶段为 archRegRd 新分配的物理寄存器索引。这个物理寄存器将接收操作结果。
 * allocatesPhysReg: 一个由重命名阶段设置的标志，表明这个微操作是否真的为 archRegRd 分配了一个新的物理寄存器。通常是 useRd && writeRd 并且指令不是单纯的存储或无链接的跳转/分支。
 * writesPhysReg: 指示此微操作是否会向物理寄存器（physRegRdNew）写入结果。对于存储指令，它会 writeRd (如果 archRegRd 不是 r0，尽管对于 ST，archRegRd 字段通常被用作源 Rt) 但它不写物理目标寄存器。这个标志可以简化后续执行单元的逻辑。
 */
case class MicroOp(val config: MicroOpConfig = MicroOpConfig()) extends Bundle { // Example width
  // --- Decoded Information (from Decoder) ---
  val isValid = Bool()
  val uopType = MicroOpType()

  // Architectural Register Operands (from instruction fields)
  val archRegRd = UInt(5 bits)
  val archRegRs = UInt(5 bits)
  val archRegRt = UInt(5 bits)

  // Flags indicating architectural operand usage
  val useRd = Bool()     // Is archRegRd a destination for this uop?
  val useRs = Bool()     // Is archRegRs a source for this uop?
  val useRt = Bool()     // Is archRegRt a source for this uop (R-type or ST data)?
  val writeRd = Bool()   // Should the result be written to archRegRd (i.e., Rd =/= r0)?

  val imm = SInt(config.dataWidth)

  // Specialized information blocks
  val aluFlags = AluFlags()
  val memOpFlags = MemOpFlags()
  val ctrlFlowInfo = ControlFlowInfo()

  // --- Information for/from Rename Stage ---
  // These are filled by the Rename stage
  val physRegRs = UInt(config.physRegIdxWidth)      // Physical register for archRegRs
  val physRegRt = UInt(config.physRegIdxWidth)      // Physical register for archRegRt
  val physRegRdOld = UInt(config.physRegIdxWidth)   // OLD physical register previously mapped to archRegRd (for recovery/commit)
  val physRegRdNew = UInt(config.physRegIdxWidth)   // NEW physical register allocated for archRegRd

  // Flag indicating if this uop allocates a new physical destination register
  val allocatesPhysReg = Bool() // True if useRd and writeRd and uop is not a store/branch without link

  // Flag to indicate if this uop writes back to a physical register (not just a store to memory)
  // This is similar to 'writeRd' but at the physical register level.
  // For most uops that writeRd, this will be true.
  // For ST, this is false. For BCOND, this is false unless it's JAL.
  val writesPhysReg = Bool()


  def setDefault(): Unit = {
    isValid := False
    uopType := MicroOpType.ILLEGAL // Assuming ILLEGAL is a valid default

    archRegRd := U(0, 5 bits) // Or assignDontCare() if that's acceptable for non-init contexts
    archRegRs := U(0, 5 bits)
    archRegRt := U(0, 5 bits)

    useRd := False
    useRs := False
    useRt := False
    writeRd := False

    imm := S(0, 32 bits) // Ensure width is specified if not inferred

    // Initialize sub-bundles
    aluFlags.op := AluOp.NOP // Assuming AluOp.NOP is a valid default
    // aluFlags.useImm := False // If aluFlags has more fields

    memOpFlags.accessType := MemoryAccessType.LOAD_U // Assuming a default
    memOpFlags.size := MemoryOpSize.WORD
    // memOpFlags.isSigned := False

    ctrlFlowInfo.condition := BranchCond.ALWAYS
    ctrlFlowInfo.targetOffset := S(0, 32 bits)
    // ctrlFlowInfo.conditionReg1 := U(0)
    // ctrlFlowInfo.conditionReg2 := U(0)
    // ctrlFlowInfo.useReg2ForCond := False
    // ctrlFlowInfo.isJumpAndLink := False
    // ctrlFlowInfo.linkReg := U(0)


    // Defaults for rename-related fields (These are usually set later, but for init, give them a value)
    physRegRs := U(0, config.physRegIdxWidth) // config.physRegIdxWidth needs to be accessible here
    physRegRt := U(0, config.physRegIdxWidth)
    physRegRdOld := U(0, config.physRegIdxWidth)
    physRegRdNew := U(0, config.physRegIdxWidth)
    allocatesPhysReg := False
    writesPhysReg := False
}
}

