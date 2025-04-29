package boson.defines

import spinal.core._
import spinal.lib._

import spinal.core._
import spinal.lib._

// --- Enumerations ---

// Micro-operation codes (A representative subset, needs to be exhaustive for a real design)
// Grouped loosely by potential execution unit / function
object UopCode extends SpinalEnum {
  val NOP, // No operation / Invalid

  // Integer ALU & Shift
  ALU_ADD, ALU_SUB, ALU_SLT, ALU_SLTU, ALU_AND, ALU_OR, ALU_NOR, ALU_XOR, // Reg-Reg/Reg-Imm common code
  ALU_LU12I, ALU_PCADDU12I, // Special immediate formations
  SHIFT_L, SHIFT_R, SHIFT_RA, // Reg-Reg/Reg-Imm common code

  // Integer Multiply/Divide
  MUL_W, MULH_W, MULH_WU, DIV_W, MOD_W, DIV_WU, MOD_WU,

  // Branch & Jump
  BRANCH, // Covers BEQ, BNE, BLT, BGE, BLTU, BGEU, B, BL, BCEQZ, BCNEZ
  JUMP_REG, // JIRL

  // Load/Store Unit (LSU)
  LOAD, // Covers LD.B/H/W/BU/HU
  STORE, // Covers ST.B/H/W
  ATOMIC_LL, // LL.W
  ATOMIC_SC, // SC.W
  MEM_BARRIER, // DBAR, IBAR
  PREFETCH, // PRELD
  CACHE_OP, // CACOP

  // System & CSR
  SYSCALL, BREAK, ERTN, IDLE, CSR_READ, CSR_WRITE, CSR_XCHG, TLB_OP, // TLBSRCH, TLBRD, TLBWR, TLBFILL
  INV_TLB, // INVTLB

  // Counter Read
  RD_CNT_VL, RD_CNT_VH, RD_CNT_ID,

  // Floating Point ALU (Common codes for S/D where possible)
  FPU_ADD, FPU_SUB, FPU_MUL, FPU_DIV, FPU_FMA, // FMA includes MADD, MSUB, NMADD, NMSUB via flags
  FPU_MAX, FPU_MIN, FPU_MAXA, FPU_MINA, FPU_ABS, FPU_NEG, FPU_SQRT, FPU_RECIP, FPU_RSQRT, FPU_CPYSGN, FPU_CLASS,
      FPU_MOV, FPU_CVT_F2F, FPU_CVT_I2F, FPU_CVT_F2I, // Conversion ops
  FPU_CMP, // FCMP.cond
  FPU_SEL, // FSEL

  // Move between Reg Files
  MOV_GPR2FPR, MOV_FPR2GPR, MOV_GPR2FCSR, MOV_FCSR2GPR, MOV_FPR2CFR, MOV_CFR2FPR, MOV_GPR2CFR, MOV_CFR2GPR =
    newElement()
}

// Execution Unit Types (Logical grouping for scheduling)
object ExeUnit extends SpinalEnum {
  val ALU, // Simple Integer ALU (Add, Logic, SLT) + Shift + Basic FP Moves?
  MUL, // Integer Multiplier
  DIV, // Integer Divider / Remainder
  LSU, // Load Store Unit (handles address calc + mem access + atomics + barriers)
  BRU, // Branch Unit (handles branch condition + target calc + jumps)
  CSR, // CSR Access Unit + System instructions (SYSCALL, BREAK, TLB, Cache, Counters)
  FPU_ADD, // FP Add/Sub/Compare/Convert/MinMax/Class/Select/Moves
  FPU_MUL, // FP Multiply / FMA
  FPU_DIV // FP Divide / Sqrt / Recip / RSqrt
  = newElement()
}

// Memory Access Size / Type
object MemSize extends SpinalEnum(binarySequential) {
  val B, H, W, D = newElement() // Byte, Half, Word, Double (for FP)
}

// Branch/Select Conditions (Combined Int, FP, CFR)
object BranchCond extends SpinalEnum {
  val NUL, // Not a branch or unconditional
  // Integer Reg Compare
  EQ, NE, LT, GE, LTU, GEU,
  // FP Compare (cond bits directly? or map common ones?)
  // Example mapping some common/representative FP conditions
  F_EQ, F_NE, F_LT, F_LE, F_UN, // Needs full mapping based on FCMP.cond encoding if used directly
  // CFR Compare
  CEQZ, CNEZ,
  // Select (FSEL uses CFR)
  SEL_TRUE // Condition for FSEL when CFR[ca] is true
  = newElement()
}

// Floating Point Size
object FpSize extends SpinalEnum {
  val S, D = newElement() // Single, Double
}

// Sub-operation flags/codes needed for complex UopCodes
case class AluOpFlags() extends Bundle {
  val isSub = Bool() // Differentiate ADD/SUB if using same UopCode
  val isUns = Bool() // Differentiate SLT/SLTU if using same UopCode
  // Add other flags if needed (e.g., to differentiate AND/OR/XOR/NOR)
}
case class ShiftOpFlags() extends Bundle {
  val isRight = Bool()
  val isArith = Bool()
}
case class FmaOpFlags() extends Bundle {
  val negateA = Bool() // For FNMADD/FNMSUB
  val negateC = Bool() // For FMSUB/FNMSUB
}
case class MemOpFlags() extends Bundle {
  val isSigned = Bool() // For sign extension on load B/H
  val isUns = Bool() // Differentiate LD.B/H vs LD.BU/HU (alternative to isSigned)
  val isLL = Bool() // Indicate Load-Link
  val isSC = Bool() // Indicate Store-Conditional
  // Could add cache hints, barrier types here if needed
}
case class CsrOpFlags() extends Bundle {
  val isWrite = Bool()
  val isXchg = Bool() // Differentiate Read/Write/Exchange
}
case class TlbOpFlags() extends Bundle {
  val isSearch = Bool()
  val isRead = Bool()
  val isWrite = Bool()
  val isFill = Bool()
}
case class FpuMiscOpFlags() extends Bundle {
  // Flags to differentiate variants if using common UopCodes
  val targetInt = Bool() // e.g., for FTINT* result type
  val explicitRM = Bool() // For FTINT{RM/RP/RZ/RNE} variants
  val rmOverride = FpRoundingMode() // Store specific RM for FTINT{RM/RP/RZ/RNE}
}

object FpRoundingMode extends SpinalEnum(binarySequential) {
  val RNE, RTP, RTN, RTZ = newElement()
}
// --- Main Uop Bundle Definition ---

// Define physical register widths (Example: 32 arch + 32 renaming = 64 physical)
case class PhysicalRegCfg(
    gprWidth: Int = 6, // 2^6 = 64 physical GPRs
    fprWidth: Int = 6, // 2^6 = 64 physical FPRs
    cfrWidth: Int = 3 // 2^3 = 8 architectural CFRs (assuming no complex renaming needed)
)

case class Uop(cfg: PhysicalRegCfg = PhysicalRegCfg()) extends Bundle {
  // --- Core Operation & Control ---
  val uopCode = UopCode() // Micro-operation identifier
  val executeUnit = ExeUnit() // Target execution unit type
  val pc = UInt(32 bits) // PC of the original LA32R instruction
  val isValid = Bool() // Is this uop slot valid?
  val isMicrocode = Bool() // Is this part of a multi-uop sequence (e.g., for complex FP or system ops)?
  val isLastMicro = Bool() // Is this the last uop in a sequence?

  // --- Operands ---
  // Destination Registers (Physical indices after renaming)
  val pdest = UInt(
    Math.max(cfg.gprWidth, cfg.fprWidth) bits
  ) // Unified destination index? Or separate? Let's try separate for clarity:
  val pdestGpr = UInt(cfg.gprWidth bits)
  val pdestGprEn = Bool()
  val pdestFpr = UInt(cfg.fprWidth bits)
  val pdestFprEn = Bool()
  val pdestCfr = UInt(cfg.cfrWidth bits) // Usually Arch ID is fine here.
  val pdestCfrEn = Bool()

  // Source Registers (Physical indices after renaming)
  val psrc1 = UInt(Math.max(cfg.gprWidth, cfg.fprWidth) bits) // Physical Reg File Index 1
  val psrc2 = UInt(Math.max(cfg.gprWidth, cfg.fprWidth) bits) // Physical Reg File Index 2
  val psrc3 = UInt(Math.max(cfg.gprWidth, cfg.fprWidth) bits) // Physical Reg File Index 3 (for FMA, FSEL)
  // Need flags to indicate if source is GPR/FPR/CFR
  val psrc1IsFpr = Bool()
  val psrc1IsCfr = Bool() // Assumes GPR if both are false
  val psrc2IsFpr = Bool()
  val psrc2IsCfr = Bool()
  val psrc3IsFpr = Bool()
  val psrc3IsCfr = Bool()
  // Indicate which sources are actually used by this uop
  val useSrc1 = Bool()
  val useSrc2 = Bool()
  val useSrc3 = Bool()

  // Immediate Value (Decoded and potentially sign/zero extended)
  val imm = Bits(32 bits) // Holds decoded immediate (si12, ui12, si20<<12, offs, etc.)
  val useImmAsSrc2 = Bool() // Flag: Use `imm` instead of `psrc2`? (e.g., for ADDI)

  // --- Execution Details / Flags ---
  val aluFlags = AluOpFlags()
  val shiftFlags = ShiftOpFlags()
  val fmaFlags = FmaOpFlags()
  val memFlags = MemOpFlags()
  val csrFlags = CsrOpFlags()
  val tlbFlags = TlbOpFlags()
  val fpMiscFlags = FpuMiscOpFlags()

  val memSize = MemSize() // Access size for LSU ops
  val fpSize = FpSize() // FP Operand size (S/D)

  val branchCond = BranchCond() // Condition for branches / FSEL

  // Specific data fields needed by certain ops
  val csrAddr = Bits(14 bits) // CSR address (e.g., for CSRRD/WR/XCHG)
  val sysCode = Bits(15 bits) // Code for SYSCALL, BREAK
  val hintOrCacheOp = Bits(15 bits) // Hint for DBAR/IBAR/PRELD or OpCode for CACOP
  val invTlbOp = Bits(5 bits) // Opcode for INVTLB
  val fcmpCond = Bits(5 bits) // Original cond field for FCMP
  val llscSuccess = Bool().assignDontCare() // Output from LSU for SC.W success (set during execute/mem stage)

  // Exception Info
  val exceptionCode = UInt(8 bits) // Holds potential exception code (e.g., LA32R codes + internal)
  val mayCauseExc = Bool() // Indicates this uop *could* cause an exception (checked during execution)

  // Atomic Operation Support
  val llbitSet = Bool() // LSU should set the core's LLbit after this LL.W
  val llbitCheck = Bool() // LSU should check (and clear) the core's LLbit for this SC.W
}
