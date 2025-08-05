package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._


// =========================================================================
//  Integer ALU/Shift IQ Entry
// =========================================================================
case class IQEntryAluInt(pCfg: PipelineConfig) extends Bundle with IQEntryLike {
  // --- Common Fields from IQEntryLike ---
  val robPtr          = UInt(pCfg.robPtrWidth)
  val pc              = UInt(pCfg.pcWidth)
  val physDest        = PhysicalRegOperand(pCfg.physGprIdxWidth)
  val physDestIsFpr   = Bool()
  val writesToPhysReg = Bool()

  val useSrc1         = Bool()
  val src1Data        = Bits(pCfg.dataWidth)
  val src1Tag         = UInt(pCfg.physGprIdxWidth)
  val src1Ready       = Bool()
  val src1IsFpr       = Bool()
  val src1IsPc       = Bool()

  val useSrc2         = Bool()
  val src2Data        = Bits(pCfg.dataWidth)
  val src2Tag         = UInt(pCfg.physGprIdxWidth)
  val src2Ready       = Bool()
  val src2IsFpr       = Bool()

  // --- Unit-Specific Control Fields ---
  val aluCtrl         = AluCtrlFlags()
  val shiftCtrl       = ShiftCtrlFlags()
  
  // --- Immediate Support ---
  val imm             = Bits(pCfg.dataWidth) // Immediate value for I-type instructions
  val immUsage        = ImmUsageType() // How the immediate is used

  override def getEuType(): ExeUnitType.E = {
    ExeUnitType.ALU_INT
  }

  override def setDefault(): this.type = {
    robPtr                := 0
    pc                    := 0
    physDest.setDefault()
    physDestIsFpr         := False
    writesToPhysReg       := False
    useSrc1               := False
    src1Data              := B(0)
    src1Tag               := 0
    src1Ready             := False
    src1IsFpr             := False
    src1IsPc              := False
    useSrc2               := False
    src2Data              := B(0)
    src2Tag               := 0
    src2Ready             := False
    src2IsFpr             := False
    aluCtrl.setDefault()    
    shiftCtrl.setDefault()   
    imm                   := B(0)
    immUsage              := ImmUsageType.NONE
    this
  }
  
  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    robPtr #= 0
    pc #= 0
    physDest.setDefaultForSim()
    physDestIsFpr #= false
    writesToPhysReg #= false
    useSrc1 #= false
    src1Data #= 0
    src1Tag #= 0
    src1Ready #= false
    src1IsFpr #= false
    src1IsPc #= false
    useSrc2 #= false
    src2Data #= 0
    src2Tag #= 0
    src2Ready #= false
    src2IsFpr #= false
    aluCtrl.setDefaultForSim()
    shiftCtrl.setDefaultForSim()
    imm #= 0
    immUsage #= ImmUsageType.NONE
    this
  }

  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    val decoded = renamedUop.decoded
    val renameInfo = renamedUop.rename

    this.robPtr          := allocatedRobPtr
    this.pc              := decoded.pc
    this.physDest        := renameInfo.physDest
    this.physDestIsFpr   := renameInfo.physDestIsFpr
    this.writesToPhysReg := renameInfo.writesToPhysReg

    this.useSrc1         := decoded.useArchSrc1
    this.src1Tag         := renameInfo.physSrc1.idx
    this.src1Ready       := !decoded.useArchSrc1
    this.src1IsFpr       := renameInfo.physSrc1IsFpr
    this.src1IsPc        := decoded.src1IsPc
    
    
    this.useSrc2         := decoded.useArchSrc2
    this.src2Tag         := renameInfo.physSrc2.idx
    this.src2Ready       := !decoded.useArchSrc2
    this.src2IsFpr       := renameInfo.physSrc2IsFpr

    this.src1Data.assignDontCare()
    this.src2Data.assignDontCare()

    this.aluCtrl         := decoded.aluCtrl
    this.shiftCtrl       := decoded.shiftCtrl
    
    // Initialize immediate fields
    this.imm             := decoded.imm
    this.immUsage        := decoded.immUsage
    this
  }
}

// =========================================================================
//  Integer Multiplier IQ Entry (现在只处理 MUL)
// =========================================================================
case class IQEntryMul(pCfg: PipelineConfig) extends Bundle with IQEntryLike {
  val uop = RenamedUop(pCfg) // Add the uop field

  override def getEuType(): ExeUnitType.E = ExeUnitType.MUL_INT

  // --- Common Fields ---
  val robPtr          = UInt(pCfg.robPtrWidth)
  val pc              = UInt(pCfg.pcWidth)

  val physDest        = PhysicalRegOperand(pCfg.physGprIdxWidth)
  val physDestIsFpr   = Bool()
  val writesToPhysReg = Bool()
  val useSrc1         = Bool()
  val src1Data        = Bits(pCfg.dataWidth)
  val src1Tag         = UInt(pCfg.physGprIdxWidth)
  val src1Ready       = Bool()
  val src1IsFpr       = Bool()
  val useSrc2         = Bool()
  val src2Data        = Bits(pCfg.dataWidth)
  val src2Tag         = UInt(pCfg.physGprIdxWidth)
  val src2Ready       = Bool()
  val src2IsFpr       = Bool()

  // --- Unit-Specific Control Fields ---
  val mulDivCtrl      = MulDivCtrlFlags()

  override def setDefault(): this.type = {
    uop.setDefault() // Initialize the uop field
    pc := 0;
    robPtr := 0; physDest.setDefault(); physDestIsFpr := False; writesToPhysReg := False
    useSrc1 := False; src1Data := B(0); src1Tag := 0; src1Ready := False; src1IsFpr := False
    useSrc2 := False; src2Data := B(0); src2Tag := 0; src2Ready := False; src2IsFpr := False
    mulDivCtrl.setDefault()
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    uop.setDefaultForSim() // Initialize the uop field for simulation
    pc #= 0;
    robPtr #= 0; physDest.setDefaultForSim(); physDestIsFpr #= false; writesToPhysReg #= false
    useSrc1 #= false; src1Data #= 0; src1Tag #= 0; src1Ready #= false; src1IsFpr #= false
    useSrc2 #= false; src2Data #= 0; src2Tag #= 0; src2Ready #= false; src2IsFpr #= false
    mulDivCtrl.setDefaultForSim()
    this
  }
  
  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    this.uop := renamedUop // Assign the entire renamedUop
    val decoded = renamedUop.decoded
    val renameInfo = renamedUop.rename
    this.robPtr := allocatedRobPtr
    this.pc := decoded.pc
    this.physDest := renameInfo.physDest
    this.physDestIsFpr := renameInfo.physDestIsFpr
    this.writesToPhysReg := renameInfo.writesToPhysReg
    this.useSrc1 := decoded.useArchSrc1
    this.src1Tag := renameInfo.physSrc1.idx
    this.src1Ready := !decoded.useArchSrc1
    this.src1IsFpr := renameInfo.physSrc1IsFpr
    this.useSrc2 := decoded.useArchSrc2
    this.src2Tag := renameInfo.physSrc2.idx
    this.src2Ready := !decoded.useArchSrc2
    this.src2IsFpr := renameInfo.physSrc2IsFpr
    this.src1Data.assignDontCare()
    this.src2Data.assignDontCare()
    this.mulDivCtrl := decoded.mulDivCtrl
    this
  }
}

// =========================================================================
//  Integer Divider IQ Entry (新增，但是不使用)
// =========================================================================
case class IQEntryDiv(pCfg: PipelineConfig) extends Bundle with IQEntryLike {
  // 结构与 IQEntryMul 完全相同，但它是一个独立的类型，用于路由到不同的IQ
  // --- Common Fields ---
  val robPtr          = UInt(pCfg.robPtrWidth)
  val pc              = UInt(pCfg.pcWidth)

  val physDest        = PhysicalRegOperand(pCfg.physGprIdxWidth)
  val physDestIsFpr   = Bool()
  val writesToPhysReg = Bool()
  val useSrc1         = Bool()
  val src1Data        = Bits(pCfg.dataWidth)
  val src1Tag         = UInt(pCfg.physGprIdxWidth)
  val src1Ready       = Bool()
  val src1IsFpr       = Bool()
  val useSrc2         = Bool()
  val src2Data        = Bits(pCfg.dataWidth)
  val src2Tag         = UInt(pCfg.physGprIdxWidth)
  val src2Ready       = Bool()
  val src2IsFpr       = Bool()

  // --- Unit-Specific Control Fields ---
  val mulDivCtrl      = MulDivCtrlFlags()

  override def setDefault(): this.type = {
    pc := 0;
    robPtr := 0; physDest.setDefault(); physDestIsFpr := False; writesToPhysReg := False
    useSrc1 := False; src1Data := B(0); src1Tag := 0; src1Ready := False; src1IsFpr := False
    useSrc2 := False; src2Data := B(0); src2Tag := 0; src2Ready := False; src2IsFpr := False
    mulDivCtrl.setDefault()
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    pc #= 0;
    robPtr #= 0; physDest.setDefaultForSim(); physDestIsFpr #= false; writesToPhysReg #= false
    useSrc1 #= false; src1Data #= 0; src1Tag #= 0; src1Ready #= false; src1IsFpr #= false
    useSrc2 #= false; src2Data #= 0; src2Tag #= 0; src2Ready #= false; src2IsFpr #= false
    mulDivCtrl.setDefaultForSim()
    this
  }

  override def getEuType(): ExeUnitType.E = {
     ExeUnitType.DIV_INT
  }
  
  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    val decoded = renamedUop.decoded
    val renameInfo = renamedUop.rename
    this.robPtr := allocatedRobPtr
    this.pc := decoded.pc
    this.physDest := renameInfo.physDest
    this.physDestIsFpr := renameInfo.physDestIsFpr
    this.writesToPhysReg := renameInfo.writesToPhysReg
    this.useSrc1 := decoded.useArchSrc1
    this.src1Tag := renameInfo.physSrc1.idx
    this.src1Ready := !decoded.useArchSrc1
    this.src1IsFpr := renameInfo.physSrc1IsFpr
    this.useSrc2 := decoded.useArchSrc2
    this.src2Tag := renameInfo.physSrc2.idx
    this.src2Ready := !decoded.useArchSrc2
    this.src2IsFpr := renameInfo.physSrc2IsFpr
    this.src1Data.assignDontCare()
    this.src2Data.assignDontCare()
    this.mulDivCtrl := decoded.mulDivCtrl
    this
  }
}

// =========================================================================
//  Load/Store Unit (LSU) IQ Entry
// =========================================================================
case class IQEntryLsu(pCfg: PipelineConfig) extends Bundle with IQEntryLike {
  // --- Common Fields ---
  val robPtr          = UInt(pCfg.robPtrWidth)
  val pc              = UInt(pCfg.pcWidth)

  val physDest        = PhysicalRegOperand(pCfg.physGprIdxWidth)
  val physDestIsFpr   = Bool()
  val writesToPhysReg = Bool()
  val useSrc1         = Bool()
  val src1Data        = Bits(pCfg.dataWidth)
  val src1Tag         = UInt(pCfg.physGprIdxWidth)
  val src1Ready       = Bool()
  val src1IsFpr       = Bool()
  val useSrc2         = Bool()
  val src2Data        = Bits(pCfg.dataWidth)
  val src2Tag         = UInt(pCfg.physGprIdxWidth)
  val src2Ready       = Bool()
  val src2IsFpr       = Bool()

  // --- Unit-Specific Control Fields ---
  val memCtrl         = MemCtrlFlags()
  val imm             = Bits(pCfg.dataWidth)
  val usePc           = Bool()
  val pcData          = UInt(pCfg.pcWidth)

  override def getEuType(): ExeUnitType.E = {
    ExeUnitType.MEM
  }
  override def setDefault(): this.type = {
    pc := 0;
    robPtr := 0; physDest.setDefault(); physDestIsFpr := False; writesToPhysReg := False
    useSrc1 := False; src1Data := B(0); src1Tag := 0; src1Ready := False; src1IsFpr := False
    useSrc2 := False; src2Data := B(0); src2Tag := 0; src2Ready := False; src2IsFpr := False
    memCtrl.setDefault(); imm := 0; usePc := False; pcData := 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    pc #= 0;
    robPtr #= 0; physDest.setDefaultForSim(); physDestIsFpr #= false; writesToPhysReg #= false
    useSrc1 #= false; src1Data #= 0; src1Tag #= 0; src1Ready #= false; src1IsFpr #= false
    useSrc2 #= false; src2Data #= 0; src2Tag #= 0; src2Ready #= false; src2IsFpr #= false
    memCtrl.setDefaultForSim(); imm #= 0; usePc #= false; pcData #= 0
    this
  }
  
  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    val decoded = renamedUop.decoded
    val renameInfo = renamedUop.rename
    this.robPtr := allocatedRobPtr
    this.pc := decoded.pc
    this.physDest := renameInfo.physDest
    this.physDestIsFpr := renameInfo.physDestIsFpr
    this.writesToPhysReg := renameInfo.writesToPhysReg
    this.useSrc1 := decoded.useArchSrc1
    this.src1Tag := renameInfo.physSrc1.idx
    this.src1Ready := !decoded.useArchSrc1
    this.src1IsFpr := renameInfo.physSrc1IsFpr
    this.useSrc2 := decoded.useArchSrc2
    this.src2Tag := renameInfo.physSrc2.idx
    this.src2Ready := !decoded.useArchSrc2
    this.src2IsFpr := renameInfo.physSrc2IsFpr
    this.src1Data.assignDontCare()
    this.src2Data.assignDontCare()
    this.memCtrl := decoded.memCtrl
    this.imm := decoded.imm
    this.usePc := decoded.usePcForAddr
    this.pcData := decoded.pc
    this
  }
}

// =========================================================================
//  Branch Resolution Unit (BRU) IQ Entry
// =========================================================================
case class IQEntryBru(pCfg: PipelineConfig) extends Bundle with IQEntryLike {
  // --- Common Fields ---
  val robPtr          = UInt(pCfg.robPtrWidth)
  val physDest        = PhysicalRegOperand(pCfg.physGprIdxWidth)
  val physDestIsFpr   = Bool()
  val writesToPhysReg = Bool()
  val useSrc1         = Bool()
  val src1Data        = Bits(pCfg.dataWidth)
  val src1Tag         = UInt(pCfg.physGprIdxWidth)
  val src1Ready       = Bool()
  val src1IsFpr       = Bool()
  val useSrc2         = Bool()
  val src2Data        = Bits(pCfg.dataWidth)
  val src2Tag         = UInt(pCfg.physGprIdxWidth)
  val src2Ready       = Bool()
  val src2IsFpr       = Bool()

  // --- Unit-Specific Control Fields ---
  val branchCtrl      = BranchCtrlFlags(pCfg)
  val imm             = Bits(pCfg.dataWidth) // For branch/jump offsets
  val pc              = UInt(pCfg.pcWidth)    // For PC-relative calculations
  val branchPrediction = BranchPredictionInfo(pCfg) // 添加分支预测信息
  
  override def getEuType(): ExeUnitType.E = {
    ExeUnitType.BRU
  }
  override def setDefault(): this.type = {
    robPtr := 0; physDest.setDefault(); physDestIsFpr := False; writesToPhysReg := False
    useSrc1 := False; src1Data := B(0); src1Tag := 0; src1Ready := False; src1IsFpr := False
    useSrc2 := False; src2Data := B(0); src2Tag := 0; src2Ready := False; src2IsFpr := False
    branchCtrl.setDefault(); imm := 0; pc := 0
    branchPrediction.setDefault() // 初始化分支预测信息
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    robPtr #= 0; physDest.setDefaultForSim(); physDestIsFpr #= false; writesToPhysReg #= false
    useSrc1 #= false; src1Data #= 0; src1Tag #= 0; src1Ready #= false; src1IsFpr #= false
    useSrc2 #= false; src2Data #= 0; src2Tag #= 0; src2Ready #= false; src2IsFpr #= false
    branchCtrl.setDefaultForSim(); imm #= 0; pc #= 0
    branchPrediction.setDefaultForSim() // 初始化分支预测信息
    this
  }

  override def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type = {
    val decoded = renamedUop.decoded
    val renameInfo = renamedUop.rename
    this.robPtr := allocatedRobPtr
    this.physDest := renameInfo.physDest
    this.physDestIsFpr := renameInfo.physDestIsFpr
    this.writesToPhysReg := renameInfo.writesToPhysReg
    this.useSrc1 := decoded.useArchSrc1
    this.src1Tag := renameInfo.physSrc1.idx
    this.src1Ready := !decoded.useArchSrc1
    this.src1IsFpr := renameInfo.physSrc1IsFpr
    this.useSrc2 := decoded.useArchSrc2
    this.src2Tag := renameInfo.physSrc2.idx
    this.src2Ready := !decoded.useArchSrc2
    this.src2IsFpr := renameInfo.physSrc2IsFpr
    this.src1Data.assignDontCare()
    this.src2Data.assignDontCare()
    this.branchCtrl := decoded.branchCtrl
    this.imm := decoded.imm
    this.pc := decoded.pc
    this.branchPrediction := renamedUop.decoded.branchPrediction // 复制分支预测信息
    this
  }
}
