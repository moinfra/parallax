package parallax.common

import spinal.core._
import spinal.lib._
import spinal.lib.experimental.hdl.VerilogToSpinal.matches
import parallax.utilities.Formattable

trait HasRobPtr {
  def robPtr: UInt
}

// --- ISA Identifier ---
object IsaType extends SpinalEnum {
  val UNKNOWN, DEMO, RISCV, LOONGARCH = newElement()
}

// --- General UopCode (higher level, execution unit can refine) ---
object BaseUopCode extends SpinalEnum {
  val NOP, ILLEGAL, ALU, SHIFT, MUL, DIV, LOAD, STORE, ATOMIC, MEM_BARRIER, PREFETCH, BRANCH, JUMP_REG, JUMP_IMM,
      SYSTEM_OP, CSR_ACCESS, FPU_ALU, FPU_CVT, FPU_CMP, FPU_SEL, LA_BITMANIP, LA_CACOP, LA_TLB, IDLE = newElement()

  def isJumpOrBranch(uopCode: BaseUopCode.C) = uopCode === BaseUopCode.JUMP_REG || uopCode === BaseUopCode.JUMP_IMM || uopCode === BaseUopCode.BRANCH
}

object DecodeExCode extends SpinalEnum {
  val INVALID, FETCH_ERROR, DECODE_ERROR, OK = newElement()
}

case class BranchPredictionInfo(pplCfg: PipelineConfig) extends Bundle {
  val isTaken = Bool()
  val target  = UInt(pplCfg.pcWidth)
  val wasPredicted = Bool()
  // 可以包含更多信息，如预测器状态等

  def setDefault(): this.type = {
    isTaken := False
    target := 0
    wasPredicted := False
    this
  }

  def format(): Seq[Any] = {
    Seq("BranchPredictionInfo: isTaken=", isTaken, " target=", target, " wasPredicted=", wasPredicted)
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    isTaken #= false
    target #= 0
    wasPredicted #= false
    this
  }
}

// --- Execution Unit Hint ---
object ExeUnitType extends SpinalEnum {
  val NONE, ALU_INT, // Integer ALU, Shift
  MUL_INT, // Integer Multiplier
  DIV_INT, // Integer Divider
  MEM, // LSU: Load/Store, Atomics, Barriers
  BRU, // Branch/Jump Unit
  CSR, // CSR access, System instructions
  FPU_ADD_MUL_CVT_CMP, // Common FP Ops
  FPU_DIV_SQRT // FP Div/Sqrt
  = newElement()
}

// --- Operand Register Type ---
object ArchRegType extends SpinalEnum {
  val GPR, FPR, CSR, LA_CF // LoongArch Condition Flags (if architecturally addressable & renamed)
  = newElement()
}

case class ArchRegOperand(config: PipelineConfig) extends Bundle {
  val idx = UInt(config.archRegIdxWidth) // For GPR/FPR
  // For CSRs, the address is larger and comes from a dedicated field
  val rtype = ArchRegType()

  def isGPR = rtype === ArchRegType.GPR
  def isFPR = rtype === ArchRegType.FPR
  def isCSR = rtype === ArchRegType.CSR // Indicates a CSR operation, addr elsewhere
  // etc.

  def setDefault(): this.type = {
    idx := 0 // Default to register 0
    rtype := ArchRegType.GPR // Default to GPR
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    idx #= 0
    rtype #= ArchRegType.GPR
    this
  }

  def clear(): this.type = { // Useful for unused operands
    idx := 0
    rtype := ArchRegType.GPR // Default to GPR, actual value may not matter if not used
    this
  }

  def format(): Seq[Any] = {
    Seq("ArchRegOperand: idx=", idx, "rtype=", rtype, "isGPR=", isGPR, "isFPR=", isFPR, "isCSR=", isCSR)
  }
}

// --- Immediate Types ---
object ImmUsageType extends SpinalEnum {
  val NONE, // Immediate not used as a source operand
  SRC_ALU, // General ALU/Logic immediate
  SRC_SHIFT_AMT, // Shift amount
  SRC_CSR_UIMM, // CSR immediate (e.g. for CSRRWI)
  MEM_OFFSET, // Memory address offset
  BRANCH_OFFSET, // PC-relative branch offset
  JUMP_OFFSET // PC-relative jump offset (can be larger than branch)
  = newElement()
}

object LogicOp extends SpinalEnum {
  val NONE, AND, OR, XOR = newElement()
}

// --- Control Flags Sub-Bundles ---
case class AluCtrlFlags() extends Bundle {
  val valid = Bool()
  val isSub = Bool()
  val isAdd = Bool()
  val isSigned = Bool()
  val logicOp = LogicOp()

  def setDefault(): this.type = {
    valid := False
    isSub := False
    isAdd := False
    isSigned := False
    logicOp := LogicOp.NONE // Default to a benign/NOP logic op representation
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    valid #= false
    isSub #= false
    isAdd #= false
    isSigned #= false
    logicOp #= LogicOp.NONE
    this
  }

  def format(): Seq[Any] = {
    Seq("AluCtrlFlags: isSub=", isSub, " isAdd=", isAdd, " isSigned=", isSigned, " logicOp=", logicOp)
  }
}
case class ShiftCtrlFlags() extends Bundle {
  val valid = Bool()
  val isRight = Bool()
  val isArithmetic = Bool()
  val isRotate = Bool()
  val isDoubleWord = Bool() // For RV64 shiftd, LA dsll/dsrl/dsra

  def setDefault(): this.type = {
    valid := False
    isRight := False
    isArithmetic := False
    isRotate := False
    isDoubleWord := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    valid := False
    isRight #= false
    isArithmetic #= false
    isRotate #= false
    isDoubleWord #= false
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "ShiftCtrlFlags: isRight=",
      isRight,
      " isArithmetic=",
      isArithmetic,
      " isRotate=",
      isRotate,
      " isDoubleWord=",
      isDoubleWord
    )
  }
}
case class MulDivCtrlFlags() extends Bundle {
  val valid = Bool()
  val isDiv = Bool()
  val isSigned = Bool()
  val isWordOp = Bool() // For 32-bit ops in 64-bit mode (e.g., MULW, DIVW)

  def setDefault(): this.type = {
    valid := False
    isDiv := False
    isSigned := False
    isWordOp := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    valid #= false
    isDiv #= false
    isSigned #= false
    isWordOp #= false
    this
  }

  def format(): Seq[Any] = {
    Seq("MulDivCtrlFlags: isDiv=", isDiv, " isSigned=", isSigned, " isWordOp=", isWordOp)
  }
}
object MemAccessSize extends SpinalEnum(binarySequential) {
  val B, H, W, D = newElement() // Byte, Half, Word, Double

  def toByteSize(v: MemAccessSize.C): UInt = {
    val width = BitCount(4)
    val out = U(0, width)
    switch(v) {
      is(B) { out := U(1, width) }
      is(H) { out := U(2, width) }
      is(W) { out := U(4, width) }
      is(D) { out := U(8, width) }
    }
    return out
  }

  def toByteSizeLog2(v: MemAccessSize.C): UInt = {
    val width = BitCount(2)
    val out = U(0, width)
    switch(v) {
      is(B) { out := U(0, width) }
      is(H) { out := U(1, width) }
      is(W) { out := U(2, width) }
      is(D) { out := U(3, width) }
    }
    return out
  }

  def toByteEnable(size: MemAccessSize.C, lowerAddressBits: UInt, dataWidthBytes: Int): Bits = {
    val resultBE = Bits(dataWidthBytes bits)
    resultBE := 0

    val addrWidth = log2Up(dataWidthBytes)
    // 确保我们只取需要的地址位，防止位宽不匹配
    val addr = lowerAddressBits(addrWidth - 1 downto 0)

    switch(size) {
      is(MemAccessSize.B) { resultBE := (U(1) << addr).asBits.resized }
      is(MemAccessSize.H) {
        resultBE := (U(3) << (addr(addrWidth - 1 downto 1) << 1)).asBits.resized
      } // addr(1) is the half-word selector
      is(MemAccessSize.W) {
        resultBE := (U(15) << (addr(addrWidth - 1 downto 2) << 2)).asBits.resized
      } // addr(2) is the word selector
      is(MemAccessSize.D) { resultBE.setAll() } // Assumes dataWidthBytes is 8 for a Double Word access
    }
    resultBE.resize(dataWidthBytes) // 确保最终位宽正确
  }

  def toByteEnable_sw(size: MemAccessSize.E, address: BigInt, dataWidthBytes: Int): BigInt = {
    // We only care about the lower bits of the address within the data word boundary
    val lowerAddr = (address % dataWidthBytes).toInt

    size match {
      case MemAccessSize.B => BigInt(1) << lowerAddr
      case MemAccessSize.H =>
        // Aligns the address to the start of the 2-byte half-word it falls into
        val alignedAddr = lowerAddr & ~(2 - 1)
        BigInt(0x3) << alignedAddr // 0b11
      case MemAccessSize.W =>
        // Aligns the address to the start of the 4-byte word it falls into
        val alignedAddr = lowerAddr & ~(4 - 1)
        BigInt(0xF) << alignedAddr // 0b1111
      case MemAccessSize.D =>
        if (dataWidthBytes < 8) {
          throw new IllegalArgumentException(s"Double-word access not possible on data bus of $dataWidthBytes bytes")
        }
        // Aligns the address to the start of the 8-byte double-word it falls into
        val alignedAddr = lowerAddr & ~(8 - 1)
        BigInt(0xFF) << alignedAddr // 0b11111111
    }
  }

}
case class MemCtrlFlags() extends Bundle {
  val size = MemAccessSize()
  val isSignedLoad = Bool()
  val isStore = Bool()
  val isLoadLinked = Bool()
  val isStoreCond = Bool()
  val atomicOp = Bits(5 bits)
  val isFence = Bool()
  val fenceMode = Bits(8 bits)
  val isCacheOp = Bool()
  val cacheOpType = Bits(5 bits)
  val isPrefetch = Bool()

  def setDefault(): this.type = {
    size := MemAccessSize.W // Default to Word
    isSignedLoad := False
    isStore := False
    isLoadLinked := False
    isStoreCond := False
    atomicOp := 0
    isFence := False
    fenceMode := 0
    isCacheOp := False
    cacheOpType := 0
    isPrefetch := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    size #= MemAccessSize.W
    isSignedLoad #= false
    isStore #= false
    isLoadLinked #= false
    isStoreCond #= false
    atomicOp #= 0
    isFence #= false
    fenceMode #= 0
    isCacheOp #= false
    cacheOpType #= 0
    isPrefetch #= false
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "MemCtrlFlags: size=",
      size,
      " isSignedLoad=",
      isSignedLoad,
      " isStore=",
      isStore,
      " isLoadLinked=",
      isLoadLinked,
      " isStoreCond=",
      isStoreCond,
      " atomicOp=",
      atomicOp,
      " isFence=",
      isFence,
      " fenceMode=",
      fenceMode,
      " isCacheOp=",
      isCacheOp,
      " cacheOpType=",
      cacheOpType,
      " isPrefetch=",
      isPrefetch
    )
  }
}
object BranchCondition extends SpinalEnum {
  val NUL, EQ, NE, LT, GE, LTU, GEU, // GPR Compares
  EQZ, NEZ, LTZ, GEZ, GTZ, LEZ, // GPR vs Zero
  F_EQ, F_NE, F_LT, F_LE, F_UN, // FP Compares
  LA_CF_TRUE, LA_CF_FALSE // LA Condition Flag Compares
  = newElement()
}

case class BranchCtrlFlags(val config: PipelineConfig) extends Bundle { // Added config
  val condition = BranchCondition()
  val isJump = Bool()
  val isLink = Bool()
  val linkReg = ArchRegOperand(config) // Pass config
  val isIndirect = Bool()
  val laCfIdx = UInt(3 bits)

  def setDefault(): this.type = {
    condition := BranchCondition.NUL
    isJump := False
    isLink := False
    linkReg.setDefault() // Call setDefault on sub-bundle
    isIndirect := False
    laCfIdx := 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    condition #= BranchCondition.NUL
    isJump #= false
    isLink #= false
    linkReg.setDefaultForSim()
    isIndirect #= false
    laCfIdx #= 0
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "BranchCtrlFlags: condition=",
      condition,
      " isJump=",
      isJump,
      " isLink=",
      isLink,
      " linkReg=",
      linkReg.format(),
      " isIndirect=",
      isIndirect,
      " laCfIdx=",
      laCfIdx
    )
  }
}
case class FpuCtrlFlags() extends Bundle {
  val opType = Bits(4 bits) // INVALID, ADD,SUB,MUL,DIV,FMA,SQRT,CVT,CMP,MINMAX,CLASS,MOV,SEL
  val fpSizeSrc1 = MemAccessSize() // S, D
  val fpSizeSrc2 = MemAccessSize()
  val fpSizeDest = MemAccessSize()
  val roundingMode = Bits(3 bits) // From instruction or CSR
  val isIntegerDest = Bool() // For FCVT.x.y
  val isSignedCvt = Bool() // For FCVT.x.y
  val fmaNegSrc1 = Bool()
  val fcmpCond = Bits(5 bits)

  def setDefault(): this.type = {
    opType := 0
    fpSizeSrc1 := MemAccessSize.W // Default to Single Precision (Word)
    fpSizeSrc2 := MemAccessSize.W
    fpSizeDest := MemAccessSize.W
    roundingMode := 0
    isIntegerDest := False
    isSignedCvt := False
    fmaNegSrc1 := False
    fcmpCond := 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    opType #= 0
    fpSizeSrc1 #= MemAccessSize.W
    fpSizeSrc2 #= MemAccessSize.W
    fpSizeDest #= MemAccessSize.W
    roundingMode #= 0
    isIntegerDest #= false
    isSignedCvt #= false
    fmaNegSrc1 #= false
    fcmpCond #= 0
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "FpuCtrlFlags: opType=",
      opType,
      " fpSizeSrc1=",
      fpSizeSrc1,
      " fpSizeSrc2=",
      fpSizeSrc2,
      " fpSizeDest=",
      fpSizeDest,
      " roundingMode=",
      roundingMode,
      " isIntegerDest=",
      isIntegerDest,
      " isSignedCvt=",
      isSignedCvt,
      " fmaNegSrc1=",
      fmaNegSrc1,
      " fcmpCond=",
      fcmpCond
    )
  }
}

case class CsrCtrlFlags(config: PipelineConfig) extends Bundle {
  val csrAddr = UInt(config.csrArchAddrWidth)
  val isWrite = Bool()
  val isRead = Bool()
  val isExchange = Bool()
  val useUimmAsSrc = Bool()

  def setDefault(): this.type = {
    csrAddr := 0 // Default to a known CSR address (e.g., an invalid one or a safe RO one)
    isWrite := False
    isRead := False
    isExchange := False
    useUimmAsSrc := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    csrAddr #= 0
    isWrite #= false
    isRead #= false
    isExchange #= false
    useUimmAsSrc #= false
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "CsrCtrlFlags: csrAddr=",
      csrAddr,
      " isWrite=",
      isWrite,
      " isRead=",
      isRead,
      " isExchange=",
      isExchange,
      " useUimmAsSrc=",
      useUimmAsSrc
    )
  }
}
case class SystemCtrlFlags() extends Bundle {
  val sysCode = Bits(20 bits)
  val isExceptionReturn = Bool()
  val isTlbOp = Bool()
  val tlbOpType = Bits(4 bits)

  def setDefault(): this.type = {
    sysCode := 0
    isExceptionReturn := False
    isTlbOp := False
    tlbOpType := 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._

    sysCode #= 0
    isExceptionReturn #= false
    isTlbOp #= false
    tlbOpType #= 0
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "SystemCtrlFlags: sysCode=",
      sysCode,
      " isExceptionReturn=",
      isExceptionReturn,
      " isTlbOp=",
      isTlbOp,
      " tlbOpType=",
      tlbOpType
    )
  }
}

// --- End of example Control Flags Sub-Bundles ---

// === Decoded Uop (Output of Decoder, Input to Rename) ===
case class DecodedUop(val config: PipelineConfig) extends Bundle {
  // --- Core Info ---
  val pc = UInt(config.pcWidth)
  val isValid = Bool() // Is this a validly decoded instruction?
  val uopCode = BaseUopCode()
  val exeUnit = ExeUnitType()
  val isa = IsaType()

  // --- Operands (Architectural) ---
  val archDest = ArchRegOperand(config)
  val writeArchDestEn =
    Bool() // Enable architectural destination write (e.g. rd != x0) 这里是精确的。例如如果 rd = 0 译码器会保证 writeArchDestEn 是 false

  val archSrc1 = ArchRegOperand(config)
  val useArchSrc1 = Bool()
  val archSrc2 = ArchRegOperand(config)
  val useArchSrc2 = Bool()
  val usePcForAddr = Bool() // 是否使用pc寻址
  val src1IsPc = Bool()
  // Immediate Value (Sign/Zero extended as needed by the operation by decoder)
  val imm = Bits(config.dataWidth)
  val immUsage = ImmUsageType() // How the immediate is used (as operand, offset, etc.)
  // --- Control Flags / Specific Payloads (The "Union" Part) ---
  val aluCtrl = AluCtrlFlags()
  val shiftCtrl = ShiftCtrlFlags()
  val mulDivCtrl = MulDivCtrlFlags()
  val memCtrl = MemCtrlFlags()
  val branchCtrl = BranchCtrlFlags(config) // Needs config for linkReg.archRegIdxWidth
  val fpuCtrl = FpuCtrlFlags()
  val csrCtrl = CsrCtrlFlags(config)
  val sysCtrl = SystemCtrlFlags()

  // --- Exception Info from Decoder ---
  val decodeExceptionCode = DecodeExCode() // ISA-agnostic internal codes, or map ISA-specific ones
  val hasDecodeException = Bool()

  // --- Other Decoder Output ---
  val isMicrocode = Bool() // Does this uop trigger a microcode sequence?
  val microcodeEntry = UInt(8 bits)
  val isSerializing = Bool() // Instruction that must serialize execution (e.g. FENCE, ERET)
  val isBranchOrJump = Bool() // Hint for frontend/predictor

  val branchPrediction = BranchPredictionInfo(config)

  def setDefault(
      pc: UInt = 0,
      isa: SpinalEnumCraft[IsaType.type] = IsaType.UNKNOWN
  ): this.type = {
    this.pc := pc
    isValid := False
    uopCode := BaseUopCode.NOP // Explicit NOP
    exeUnit := ExeUnitType.NONE // Explicit NONE
    this.isa := isa

    archDest.setDefault()
    writeArchDestEn := False
    archSrc1.setDefault()
    useArchSrc1 := False
    archSrc2.setDefault()
    useArchSrc2 := False
    
    usePcForAddr := False
    src1IsPc := False

    imm := 0
    immUsage := ImmUsageType.NONE

    aluCtrl.setDefault()
    shiftCtrl.setDefault()
    mulDivCtrl.setDefault()
    memCtrl.setDefault()
    branchCtrl.setDefault()
    fpuCtrl.setDefault()
    csrCtrl.setDefault()
    sysCtrl.setDefault()

    decodeExceptionCode := DecodeExCode.OK
    hasDecodeException := False
    isMicrocode := False
    microcodeEntry := 0
    isSerializing := False
    isBranchOrJump := False

    branchPrediction.setDefault()

    this
  }

  def setDefaultForSim() = {
    import spinal.core.sim._

    pc #= 0
    isValid #= false
    uopCode #= BaseUopCode.NOP
    exeUnit #= ExeUnitType.NONE
    isa #= IsaType.UNKNOWN

    archDest.setDefaultForSim()
    writeArchDestEn #= false
    archSrc1.setDefaultForSim()
    useArchSrc1 #= false
    archSrc2.setDefaultForSim()
    useArchSrc2 #= false

    usePcForAddr #= false
    src1IsPc #= false

    imm #= 0
    immUsage #= ImmUsageType.NONE

    aluCtrl.setDefaultForSim()
    shiftCtrl.setDefaultForSim()
    mulDivCtrl.setDefaultForSim()
    memCtrl.setDefaultForSim()
    branchCtrl.setDefaultForSim()
    fpuCtrl.setDefaultForSim()
    csrCtrl.setDefaultForSim()
    sysCtrl.setDefaultForSim()

    decodeExceptionCode #= DecodeExCode.OK
    hasDecodeException #= false
    isMicrocode #= false
    microcodeEntry #= 0
    isSerializing #= false
    isBranchOrJump #= false
    branchPrediction.setDefaultForSim()

  }

  def format(): Seq[Any] = {
    Seq(
      "DecodedUop @ pc=",
      pc,
      " (",
      isValid,
      ")\n",
      "  Core Info: ",
      "  uopCode=",
      uopCode,
      "  exeUnit=",
      exeUnit,
      "  isa=",
      isa,
      "\n",
      "  Operands:\n",
      "    dest=",
      archDest.format(),
      " writeEn=",
      writeArchDestEn,
      "\n",
      "    src1=",
      archSrc1.format(),
      " use=",
      useArchSrc1,
      "\n",
      "    src2=",
      archSrc2.format(),
      " use=",
      useArchSrc2,
      "\n",
      " use=",
      "\n",
      "    imm=",
      imm,
      " (usage=",
      immUsage,
      ")\n",
      "  Control Flags:\n",
      "    ALU: ",
      aluCtrl.format(),
      "\n",
      "    Shift: ",
      shiftCtrl.format(),
      "\n",
      "    MulDiv: ",
      mulDivCtrl.format(),
      "\n",
      "    Mem: ",
      memCtrl.format(),
      "\n",
      "    Branch: ",
      branchCtrl.format(),
      "\n",
      "    FPU: ",
      fpuCtrl.format(),
      "\n",
      "    CSR: ",
      csrCtrl.format(),
      "\n",
      "    System: ",
      sysCtrl.format(),
      "\n",
      "  Status:\n",
      "    decodeEx=",
      decodeExceptionCode,
      " hasEx=",
      hasDecodeException,
      "\n",
      "    isMicrocode=",
      isMicrocode,
      " entry=",
      microcodeEntry,
      "\n",
      "    isSerializing=",
      isSerializing,
      " isBranchOrJump=",
      isBranchOrJump,
      " branchPrediction=",
      branchPrediction.format()

    )
  }

  def tinyDump(): Seq[Any] = {
    Seq(
      "DecodedUop @ pc=",
      pc,
      " (",
      isValid,
      ")\n"
    )
  }
}

// === Renamed Uop (Output of Rename, Input to ROB/Issue/Execute) ===
case class PhysicalRegOperand(physRegIdxWidth: BitCount) extends Bundle {
  val idx = UInt(physRegIdxWidth)
  // Optionally, carry ArchRegType if physical reg files are shared and type is needed for access.
  // val rtype = ArchRegType()
  def setDefault(): this.type = {
    idx := 0 // Default to physical register 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    idx #= 0
    this
  }

  def format(): Seq[Any] = {
    Seq("PhysicalRegOperand: idx=", idx)
  }
}

case class RenameInfo(val config: PipelineConfig) extends Bundle {
  // Physical source registers (assuming GPR/FPR might have different phys reg counts)
  val physSrc1 = PhysicalRegOperand(config.physGprIdxWidth) // Default to GPR width, adjust if src is FPR
  val physSrc1IsFpr = Bool() // True if physSrc1 maps to FPR file

  val physSrc2 = PhysicalRegOperand(config.physGprIdxWidth)
  val physSrc2IsFpr = Bool()


  // Physical destination register
  val physDest = PhysicalRegOperand(config.physGprIdxWidth)
  val physDestIsFpr = Bool()
  val oldPhysDest = PhysicalRegOperand(config.physGprIdxWidth) // Previous mapping
  val oldPhysDestIsFpr = Bool()

  // Flags from rename stage
  val allocatesPhysDest = Bool() // Did this uop allocate a new physical register for archDest?
  val writesToPhysReg = Bool() // Does this uop's execution result go to physDest?
  // (False for ST data, some branches)


  def setDefault(): this.type = {
    physSrc1.setDefault(); physSrc1IsFpr := False
    physSrc2.setDefault(); physSrc2IsFpr := False
    physDest.setDefault(); physDestIsFpr := False
    oldPhysDest.setDefault(); oldPhysDestIsFpr := False
    allocatesPhysDest := False
    writesToPhysReg := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    physSrc1.setDefaultForSim(); physSrc1IsFpr #= false
    physSrc2.setDefaultForSim(); physSrc2IsFpr #= false
    physDest.setDefaultForSim(); physDestIsFpr #= false
    oldPhysDest.setDefaultForSim(); oldPhysDestIsFpr #= false
    allocatesPhysDest #= false
    writesToPhysReg #= false
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "RenameInfo: physSrc1=",
      physSrc1.format(),
      " physSrc1IsFpr=",
      physSrc1IsFpr,
      " physSrc2=",
      physSrc2.format(),
      " physSrc2IsFpr=",
      physSrc2IsFpr,
      " physDest=",
      physDest.format(),
      " physDestIsFpr=",
      physDestIsFpr,
      " oldPhysDest=",
      oldPhysDest.format(),
      " oldPhysDestIsFpr=",
      oldPhysDestIsFpr,
      " allocatesPhysDest=",
      allocatesPhysDest,
      " writesToPhysReg=",
      writesToPhysReg,
    )
  }
}

case class RenamedUop(
    val config: PipelineConfig
) extends Bundle
    with Formattable
    with HasRobPtr {
  // --- Original Decoded Information ---
  val decoded = DecodedUop(config) // Embeds all architectural and control info

  // --- Rename Stage Output ---
  val rename = RenameInfo(config)

  // --- ROB / Dispatch / Execute Info ---
  val robPtr = UInt(config.robPtrWidth)
  val uniqueId = UInt(config.uopUniqueIdWidth) // For debugging, tracing

  // These flags are typically set/cleared as the Uop moves through the pipeline
  // Or are part of the ROB entry, not the Uop itself if Uop is just a "view" into ROB.
  // For simplicity, including some common ones here.
  val dispatched = Bool()
  val executed = Bool()
  val hasException = Bool() // Exception detected during execution
  val exceptionCode = UInt(8 bits) // Actual exception code from execution

  def setDefault(decoded: DecodedUop = null): this.type = {
    if (decoded != null) this.decoded := decoded else this.decoded.setDefault()
    rename.setDefault()
    robPtr := 0 // Or a specific "invalid" index if 0 is valid
    uniqueId := 0
    dispatched := False
    executed := False
    hasException := False
    exceptionCode := 0 // Default to "No Exception"
    this
  }

  def initWithRobPtr(uop: RenamedUop, robPtr: UInt): this.type = {
    this.decoded := uop.decoded
    this.rename := uop.rename
    this.robPtr := robPtr
    this.uniqueId := uop.uniqueId
    this.dispatched := uop.dispatched
    this.executed := uop.executed
    this.hasException := uop.hasException
    this.exceptionCode := uop.exceptionCode
    this
  }

  def setDefaultForSim(decoded: DecodedUop = null): this.type = {
    import spinal.core.sim._
    if (decoded != null) this.decoded := decoded else this.decoded.setDefaultForSim()
    rename.setDefaultForSim()
    robPtr #= 0
    uniqueId #= 0
    dispatched #= false
    executed #= false
    hasException #= false
    exceptionCode #= 0
    this
  }

  def format(): Seq[Any] = {
    Seq(
      "RenamedUop: decoded=",
      decoded.format(),
      "\n",
      "rename=",
      rename.format(),
      "\n",
      "robPtr=",
      robPtr,
      " uniqueId=",
      uniqueId,
      "\n",
      "dispatched=",
      dispatched,
      " executed=",
      executed,
      "\n",
      "hasException=",
      hasException,
      " exceptionCode=",
      exceptionCode
    )
  }
}
