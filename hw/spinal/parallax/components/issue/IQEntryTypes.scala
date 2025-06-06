package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._

case class IQEntryAluInt(val pipelineConfig: PipelineConfig) extends Bundle with IQEntryLike {
  // --- Common IQEntryLike fields ---
  override val robPtr = UInt(pipelineConfig.robPtrWidth)
  override val physDest = PhysicalRegOperand(pipelineConfig.physGprIdxWidth)
  override val physDestIsFpr = Bool()
  override val writesToPhysReg = Bool()

  override val useSrc1 = Bool()
  override val src1Data = Bits(pipelineConfig.dataWidth)
  override val src1Tag = UInt(pipelineConfig.physGprIdxWidth)
  override val src1Ready = Bool()
  override val src1IsFpr = Bool()

  override val useSrc2 = Bool()
  override val src2Data = Bits(pipelineConfig.dataWidth)
  override val src2Tag = UInt(pipelineConfig.physGprIdxWidth)
  override val src2Ready = Bool()
  override val src2IsFpr = Bool()

  // --- ALU Specific Fields ---
  val aluCtrl = AluCtrlFlags()
  val shiftCtrl = ShiftCtrlFlags()

  override def setDefault(): this.type = {
    robPtr := U(0)

    physDest.setDefault()
    physDestIsFpr := False
    writesToPhysReg := False

    useSrc1 := False
    src1Data := B(0)
    src1Tag := U(0)
    src1Ready := False
    src1IsFpr := False

    useSrc2 := False
    src2Data := B(0)
    src2Tag := U(0)
    src2Ready := False
    src2IsFpr := False

    aluCtrl.setDefault()
    shiftCtrl.setDefault()
    this
  }

  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    this.setDefault() // Start with defaults

    this.robPtr := allocatedRobPtr

    this.physDest := renamedUop.rename.physDest
    this.physDestIsFpr := renamedUop.rename.physDestIsFpr
    this.writesToPhysReg := renamedUop.rename.writesToPhysReg

    this.useSrc1 := renamedUop.decoded.useArchSrc1
    when(renamedUop.decoded.useArchSrc1) {
      this.src1Tag := renamedUop.rename.physSrc1.idx
      this.src1IsFpr := renamedUop.rename.physSrc1IsFpr
    }

    this.useSrc2 := renamedUop.decoded.useArchSrc2
    when(renamedUop.decoded.useArchSrc2) {
      this.src2Tag := renamedUop.rename.physSrc2.idx
      this.src2IsFpr := renamedUop.rename.physSrc2IsFpr
    }

    this.aluCtrl := renamedUop.decoded.aluCtrl
    this.shiftCtrl := renamedUop.decoded.shiftCtrl
    this
  }
}

case class IQEntryFpu(val pipelineConfig: PipelineConfig, val usesSrc3: Boolean) extends Bundle with IQEntryLike {
  // --- Common IQEntryLike fields ---
  override val robPtr = UInt(pipelineConfig.robPtrWidth)

  override val physDest = PhysicalRegOperand(
    pipelineConfig.physGprIdxWidth
  ) // Assuming FPRs use same phys reg idx width
  override val physDestIsFpr = Bool()
  override val writesToPhysReg = Bool()

  override val useSrc1 = Bool()
  override val src1Data = Bits(pipelineConfig.dataWidth) // Assuming FP data width is same as GPR for now
  override val src1Tag = UInt(pipelineConfig.physGprIdxWidth)
  override val src1Ready = Bool()
  override val src1IsFpr = Bool()

  override val useSrc2 = Bool()
  override val src2Data = Bits(pipelineConfig.dataWidth)
  override val src2Tag = UInt(pipelineConfig.physGprIdxWidth)
  override val src2Ready = Bool()
  override val src2IsFpr = Bool()

  // Conditional Src3 for FMA etc.
  val src3Data = if (usesSrc3) Bits(pipelineConfig.dataWidth) else null
  val src3Tag = if (usesSrc3) UInt(pipelineConfig.physGprIdxWidth) else null
  val src3Ready = if (usesSrc3) Bool() else null
  val src3IsFpr = if (usesSrc3) Bool() else null
  val useSrc3 = if (usesSrc3) Bool() else null

  // --- FPU Specific Fields ---
  val fpuCtrl = FpuCtrlFlags()

  override def setDefault(): this.type = {
    robPtr := U(0)

    physDest.setDefault()
    physDestIsFpr := True // Default to true for an FPU entry's dest
    writesToPhysReg := False

    useSrc1 := False
    src1Data := B(0)
    src1Tag := U(0)
    src1Ready := False
    src1IsFpr := True // Default to true for an FPU entry's src

    useSrc2 := False
    src2Data := B(0)
    src2Tag := U(0)
    src2Ready := False
    src2IsFpr := True

    if (usesSrc3) {
      this.useSrc3 := False
      this.src3Data := B(0)
      this.src3Tag := U(0)
      this.src3Ready := False
      this.src3IsFpr := True
    }

    fpuCtrl.setDefault()
    this
  }

  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    this.setDefault()

    this.robPtr := allocatedRobPtr

    this.physDest := renamedUop.rename.physDest
    this.physDestIsFpr := renamedUop.rename.physDestIsFpr // Should be true if dispatched to FPU IQ
    this.writesToPhysReg := renamedUop.rename.writesToPhysReg

    this.useSrc1 := renamedUop.decoded.useArchSrc1
    when(renamedUop.decoded.useArchSrc1) {
      this.src1Tag := renamedUop.rename.physSrc1.idx
      this.src1IsFpr := renamedUop.rename.physSrc1IsFpr // Should be true
    }

    this.useSrc2 := renamedUop.decoded.useArchSrc2
    when(renamedUop.decoded.useArchSrc2) {
      this.src2Tag := renamedUop.rename.physSrc2.idx
      this.src2IsFpr := renamedUop.rename.physSrc2IsFpr // Should be true
    }

    if (usesSrc3) {
      this.useSrc3 := renamedUop.decoded.useArchSrc3
      when(renamedUop.decoded.useArchSrc3) {
        this.src3Tag := renamedUop.rename.physSrc3.idx
        this.src3IsFpr := renamedUop.rename.physSrc3IsFpr // Should be true
      }
    }

    this.fpuCtrl := renamedUop.decoded.fpuCtrl
    this
  }
}
