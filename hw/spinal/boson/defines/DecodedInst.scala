package boson.defines

import spinal.core._
import spinal.lib._
import boson.BosonConfig._ // Assuming config keys are here
import boson.Stageable

// --- Enumerations (Copy or ensure accessible from DecodePlugin) ---
// (UopCode, ExeUnit, MemSize, BranchCond, FpSize, etc. from previous Uop definition)
// ... include the enums defined in the Uop design step ...
// Add ExceptionCode enum if not already present
object ExceptionCode extends SpinalEnum(binarySequential) {
  val NONE, // No exception
  SYS, BRK, INE, IPE, ADEF, ALE, FPE, // From ISA spec
  // Add other internal/MMU exceptions as needed
  dummy // Ensure at least one element if others are omitted
  = newElement()
  defaultEncoding = binarySequential // Explicitly set default
}

// --- Helper Bundles ---
case class ArchRegIndex() extends Bundle {
  val idx = UInt(5 bits)
  val isFpr = Bool() // True if FPR, false if GPR
  val isCfr = Bool() // True if CFR (overrides isFpr)

  // Helper to check if it's GPR
  def isGpr = !isFpr && !isCfr
}

// Intermediate structure produced by decode
// Holds ARCHITECTURAL register info for the Rename stage
case class DecodedInst(cfg: PhysicalRegCfg = PhysicalRegCfg()) extends Bundle {
  // --- Core Operation & Control ---
  val uopCode = UopCode()
  val executeUnit = ExeUnit()
  val pc = UInt(32 bits)
  val isValid = Bool() // Is this a validly decoded instruction?
  // Microcode placeholders - for complex instructions not handled here
  // val isMicrocodeTrigger = Bool()
  // val microcodeEntry = UInt(...)

  // --- Operands (Architectural) ---
  val archDest = ArchRegIndex()
  val writeDestEn = Bool() // Enable destination write (False if rd/fd/cd is r0/f0/c0)

  val archSrc1 = ArchRegIndex()
  val useSrc1 = Bool()
  val archSrc2 = ArchRegIndex()
  val useSrc2 = Bool()
  val archSrc3 = ArchRegIndex() // For FMA, FSEL etc.
  val useSrc3 = Bool()

  // Immediate Value (Sign/Zero extended as needed by the operation)
  val imm = Bits(32 bits)
  val useImmAsSrc2 = Bool() // Use `imm` instead of `archSrc2` value
  val useImmAsSrc3 = Bool() // Sometimes imm might replace src3 (less common)

  // --- Execution Details / Flags ---
  val aluFlags = AluOpFlags()
  val shiftFlags = ShiftOpFlags()
  val fmaFlags = FmaOpFlags()
  val memFlags = MemOpFlags()
  val csrFlags = CsrOpFlags()
  val tlbFlags = TlbOpFlags()
  val fpMiscFlags = FpuMiscOpFlags()

  val memSize = MemSize()
  val fpSize = FpSize()
  val branchCond = BranchCond()

  // Specific data fields needed by certain ops
  val csrAddr = Bits(14 bits) // CSR number/address
  val sysCode = Bits(15 bits) // Code for SYSCALL, BREAK
  val hintOrCacheOp = Bits(15 bits) // Hint for DBAR/IBAR/PRELD or OpCode for CACOP
  val invTlbOp = Bits(5 bits) // Opcode for INVTLB
  val fcmpCond = Bits(5 bits) // Original cond field for FCMP

  // Exception Info
  val excCode = ExceptionCode() // Use enum members
  val generatesExc = Bool()
  val mayCauseExc = Bool()

  // Atomic Operation Support
  val llbitSet = Bool() // Tell core to set LLbit after LL.W completes
  val llbitCheck = Bool() // Tell core to check LLbit for SC.W

  // --- Helpers for Rename Stage ---
  def archDestIsGpr = writeDestEn && archDest.isGpr
  def archDestIsFpr = writeDestEn && archDest.isFpr
  def archDestIsCfr = writeDestEn && archDest.isCfr

  def archSrc1IsGpr = useSrc1 && archSrc1.isGpr
  def archSrc1IsFpr = useSrc1 && archSrc1.isFpr
  def archSrc1IsCfr = useSrc1 && archSrc1.isCfr
  // ... similar helpers for src2, src3 ...
  def archSrc2IsGpr = useSrc2 && archSrc2.isGpr
  def archSrc2IsFpr = useSrc2 && archSrc2.isFpr
  def archSrc2IsCfr = useSrc2 && archSrc2.isCfr

  def archSrc3IsGpr = useSrc3 && archSrc3.isGpr
  def archSrc3IsFpr = useSrc3 && archSrc3.isFpr
  def archSrc3IsCfr = useSrc3 && archSrc3.isCfr

  // Default assignment helper
  def assignDefaults(): this.type = {
    isValid.assignDontCare()
    uopCode := UopCode.NOP
    executeUnit := ExeUnit.ALU // Default, override as needed
    pc.assignDontCare()

    archDest.idx.assignDontCare()
    archDest.isFpr := False
    archDest.isCfr := False
    writeDestEn := False

    archSrc1.idx.assignDontCare()
    archSrc1.isFpr := False
    archSrc1.isCfr := False
    useSrc1 := False
    archSrc2.idx.assignDontCare()
    archSrc2.isFpr := False
    archSrc2.isCfr := False
    useSrc2 := False
    archSrc3.idx.assignDontCare()
    archSrc3.isFpr := False
    archSrc3.isCfr := False
    useSrc3 := False

    imm.assignDontCare()
    useImmAsSrc2 := False
    useImmAsSrc3 := False

    // Default flags to safe/neutral values
    aluFlags.isSub := False
    aluFlags.isUns := False
    shiftFlags.isRight := False
    shiftFlags.isArith := False
    fmaFlags.negateA := False
    fmaFlags.negateC := False
    memFlags.isSigned := False
    memFlags.isUns := False
    memFlags.isLL := False
    memFlags.isSC := False
    csrFlags.isWrite := False
    csrFlags.isXchg := False
    tlbFlags.assignDefaults()
    fpMiscFlags.targetInt := False
    fpMiscFlags.explicitRM := False
    fpMiscFlags.rmOverride.assignDontCare()

    memSize.assignDontCare()
    fpSize.assignDontCare()
    branchCond := BranchCond.NUL

    csrAddr.assignDontCare()
    sysCode.assignDontCare()
    hintOrCacheOp.assignDontCare()
    invTlbOp.assignDontCare()
    fcmpCond.assignDontCare()

    excCode := ExceptionCode.NONE
    generatesExc := False
    mayCauseExc := False
    llbitSet := False
    llbitCheck := False

    this
  }
}
