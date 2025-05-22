package parallax.components.issue // Or parallax.common if preferred for all IQ entry types

import spinal.core._
import spinal.lib._
import parallax.common._ // For PipelineConfig, RenamedUop, ArchRegType, etc.

case class IQEntryAluInt(val config: PipelineConfig) extends Bundle {
  val valid = Bool() // Is this entry in use?

  // --- Identifiers ---
  val robIdx = UInt(config.robIdxWidth) // Link to the ROB entry for this uop
  // val uopId = UInt(config.uopUniqueIdWidth) // Alternative or additional unique ID if needed

  // --- Destination Physical Register ---
  // Needed if the EU writes back and this IQ needs to know where for some reason,
  // or if the select logic needs it. Often, the EU gets this from the full Uop.
  // For wake-up, we primarily care about source tags.
  // Let's include it for now, can be optimized out if truly unused by IQ select/wake-up.
  val physDest = PhysicalRegOperand(config.physGprIdxWidth) // From RenamedUop.rename.physDest
  val physDestIsFpr = Bool() // From RenamedUop.rename.physDestIsFpr
  val writesToPhysReg = Bool() // From RenamedUop.rename.writesToPhysReg

  // --- Source Operand 1 ---
  val src1Data = Bits(config.dataWidth)
  val src1Tag = UInt(config.physGprIdxWidth) // From RenamedUop.rename.physSrc1.idx
  val src1Ready = Bool()
  val src1IsFpr = Bool() // From RenamedUop.rename.physSrc1IsFpr (if needed by EU/bypass)

  // --- Source Operand 2 ---
  val src2Data = Bits(config.dataWidth)
  val src2Tag = UInt(config.physGprIdxWidth) // From RenamedUop.rename.physSrc2.idx
  val src2Ready = Bool()
  val src2IsFpr = Bool() // From RenamedUop.rename.physSrc2IsFpr

  // --- Source Operand 3 (If ALU ops might use it, e.g. for future extensions like ternary ops) ---
  // For standard ALU ops, this might be unused.
  // val src3Data = Bits(config.dataWidth)
  // val src3Tag = UInt(config.physGprIdxWidth)
  // val src3Ready = Bool()
  // val src3IsFpr = Bool()

  // --- Execution Info for ALU ---
  // These are specific control signals from DecodedUop that the ALU EU needs.
  // The Dispatch stage will copy these from RenamedUop.decoded.
  val aluCtrl = AluCtrlFlags() // From RenamedUop.decoded.aluCtrl
  val shiftCtrl = ShiftCtrlFlags() // From RenamedUop.decoded.shiftCtrl
  // val uopCode = BaseUopCode()     // Could be useful for select logic or EU if it's very generic
  // Or, the EU might infer operation from aluCtrl/shiftCtrl.
  // Let's assume for now aluCtrl/shiftCtrl are sufficient for a dedicated ALU EU.

  // --- Status ---
  val isReadyToIssue = Bool() // All source operands ready

  // --- Other potential fields for select logic ---
  // val age = UInt(log2Up(config.aluIntIqDepth) bits) // Example, if depth is a config param

  def setDefault(): this.type = {
    valid := False
    robIdx := U(0)
    // uopId := U(0)

    physDest.setDefault()
    physDestIsFpr := False
    writesToPhysReg := False

    src1Data := B(0)
    src1Tag := U(0)
    src1Ready := False
    src1IsFpr := False

    src2Data := B(0)
    src2Tag := U(0)
    src2Ready := False
    src2IsFpr := False

    // if (config.aluUsesSrc3) { // Hypothetical config
    //   src3Data := B(0)
    //   src3Tag := U(0)
    //   src3Ready := False
    //   src3IsFpr := False
    // }

    aluCtrl.setDefault()
    shiftCtrl.setDefault()
    // uopCode := BaseUopCode.NOP

    isReadyToIssue := False
    // age := U(0)
    this
  }

  // Helper to check if sources are ready based on which ones the original DecodedUop used.
  // This requires knowing which sources the original uop intended to use.
  // This information would need to be passed from Dispatch or be part of this IQEntry.
  // For simplicity, let's add `useSrc1` and `useSrc2` flags to IQEntryAluInt,
  // which Dispatch will populate from RenamedUop.decoded.useArchSrc1/2.
  val useSrc1 = Bool()
  val useSrc2 = Bool()
  // val useSrc3 = Bool()

  def reevaluateIsReadyToIssue(): Unit = { // Call this after a wake-up
    val s1ok = !useSrc1 || src1Ready
    val s2ok = !useSrc2 || src2Ready
    // val s3ok = !useSrc3 || src3Ready // If using src3
    isReadyToIssue := valid && s1ok && s2ok // && s3ok
  }

  def initFrom(renamedUop: RenamedUop, allocatedRobIdx: UInt): this.type = {
    valid := False
    robIdx := U(0)

    physDest.setDefault()
    physDestIsFpr := False
    writesToPhysReg := False

    src1Data := B(0)
    src1Tag := U(0)
    src1Ready := False
    src1IsFpr := False

    src2Data := B(0)
    src2Tag := U(0)
    src2Ready := False
    src2IsFpr := False

    aluCtrl.setDefault()
    shiftCtrl.setDefault()

    isReadyToIssue := False

    this.valid := True // Mark it valid for the IQ
    this.robIdx := allocatedRobIdx

    this.physDest := renamedUop.rename.physDest
    this.physDestIsFpr := renamedUop.rename.physDestIsFpr
    this.writesToPhysReg := renamedUop.rename.writesToPhysReg

    this.useSrc1 := renamedUop.decoded.useArchSrc1
    when(renamedUop.decoded.useArchSrc1) { // Conditional assignment for tag and type
      this.src1Tag := renamedUop.rename.physSrc1.idx
      this.src1IsFpr := renamedUop.rename.physSrc1IsFpr
    }
    // src1Ready remains False, to be set by IQ logic

    this.useSrc2 := renamedUop.decoded.useArchSrc2
    when(renamedUop.decoded.useArchSrc2) { // Conditional assignment for tag and type
      this.src2Tag := renamedUop.rename.physSrc2.idx
      this.src2IsFpr := renamedUop.rename.physSrc2IsFpr
    }
    // src2Ready remains False

    this.aluCtrl := renamedUop.decoded.aluCtrl
    this.shiftCtrl := renamedUop.decoded.shiftCtrl
    // isReadyToIssue remains False initially

    this
  }
}
