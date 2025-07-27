package parallax.fetch2

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.components.dcache2.DataCachePluginConfig
import parallax.fetch.PredecodeInfo // 复用已有的PredecodeInfo
import spinal.core.sim.SimBaseTypePimper
import spinal.core.sim.SimBitVectorPimper
import parallax.utilities.Formattable

/** I-Cache响应和其原始请求PC的捆绑。
  */
case class ICacheRspWithPC(pCfg: PipelineConfig, dCfg: DataCachePluginConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val data = Bits(dCfg.memDataWidth bits)
  val fault = Bool()
  val redo = Bool()
}

/** 预解码后的整个指令块（Cache Line）。
  */
case class FetchGroup(pCfg: PipelineConfig) extends Bundle with Formattable {
  val pc = UInt(pCfg.pcWidth)
  val firstPc = UInt(pCfg.pcWidth) // 继承 firstPc
  val instructions = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  val predecodeInfos = Vec(PredecodeInfo(), pCfg.fetchWidth)
  val branchMask = Bits(pCfg.fetchWidth bits)
  val fault = Bool()
  val numValidInstructions = UInt(log2Up(pCfg.fetchWidth + 1) bits) // <<<< ADD THIS
  val startInstructionIndex = UInt(log2Up(pCfg.fetchWidth) bits)
  val potentialJumpTargets = Vec(UInt(pCfg.pcWidth), pCfg.fetchWidth)

  def setDefault(): this.type = {
    pc := 0
    firstPc := 0
    instructions.foreach(_ := 0)
    predecodeInfos.foreach(_.setDefault())
    branchMask := 0
    fault := False
    numValidInstructions := 0
    startInstructionIndex := 0
    potentialJumpTargets.foreach(_.assignDontCare())
    this
  }

  override def format: Seq[Any] = Seq(
    L"FetchGroup(",
    L"pc=${pc}, ",
    L"firstPc=${firstPc}, ",
    L"instructions=${instructions}, ",
    L"predecodeInfos=..., ",
    L"branchMask=${branchMask}, ",
    L"fault=${fault}, ",
    L"numValidInstructions=${numValidInstructions}, ",
    L"startInstructionIndex=${startInstructionIndex}, ",
    L"potentialJumpTargets=${potentialJumpTargets}, ",
    L")"
  )
}

case class FetchGroupCapture(
    val pc: BigInt
)

object FetchGroupCapture {
  def apply(fetchGroup: FetchGroup): FetchGroupCapture = {
    import spinal.lib.sim._
    FetchGroupCapture(fetchGroup.pc.toBigInt)
  }
}

case class PredecodedFetchGroup(pCfg: PipelineConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val firstPc = UInt(pCfg.pcWidth) // 继承 firstPc
  val instructions = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  val fault = Bool()
  val numValidInstructions = UInt(log2Up(pCfg.fetchWidth + 1) bits)
  val startInstructionIndex = UInt(log2Up(pCfg.fetchWidth) bits)
  val predecodeInfos = Vec(PredecodeInfo(), pCfg.fetchWidth) // 新增预解码信息

  def setDefault(): this.type = {
    pc := 0
    firstPc := 0
    instructions.foreach(_ := 0)
    fault := False
    numValidInstructions := 0
    startInstructionIndex := 0
    predecodeInfos.foreach(_.setDefault())
    this
  }
}

/** 智能分发器内部用于暂存待处理分支信息的Bundle。
  */
case class BranchInfo(pCfg: PipelineConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val instruction = Bits(pCfg.dataWidth)
  val predecodeInfo = PredecodeInfo()
  val bpuTransactionId = UInt(pCfg.bpuTransactionIdWidth)

  def setDefault(): this.type = {
    pc := 0
    instruction := 0
    predecodeInfo.setDefault()
    bpuTransactionId := 0
    this
  }
}

/** 从取指流水线发往分发器的原始指令组，不包含预解码信息。
  */
case class RawFetchGroup(pCfg: PipelineConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val firstPc = UInt(pCfg.pcWidth)
  val instructions = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  val fault = Bool()
  val numValidInstructions = UInt(log2Up(pCfg.fetchWidth + 1) bits)
  val startInstructionIndex = UInt(log2Up(pCfg.fetchWidth) bits)

  def setDefault(): this.type = {
    pc := 0
    instructions.foreach(_ := 0)
    fault := False
    numValidInstructions := 0
    startInstructionIndex := 0
    this
  }
}
