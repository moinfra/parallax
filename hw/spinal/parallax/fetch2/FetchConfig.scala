package parallax.fetch2

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.components.dcache2.DataCachePluginConfig
import parallax.fetch.PredecodeInfo // 复用已有的PredecodeInfo

  /**
   * I-Cache响应和其原始请求PC的捆绑。
   */
  case class ICacheRspWithPC(pCfg: PipelineConfig, dCfg: DataCachePluginConfig) extends Bundle {
      val pc           = UInt(pCfg.pcWidth)
      val data         = Bits(dCfg.memDataWidth bits)
      val fault        = Bool()
      val redo         = Bool()
  }

  /**
   * 预解码后的整个指令块（Cache Line）。
   */
  case class FetchGroup(pCfg: PipelineConfig) extends Bundle {
    val pc             = UInt(pCfg.pcWidth)
    val instructions   = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
    val predecodeInfos = Vec(PredecodeInfo(), pCfg.fetchWidth)
    val branchMask     = Bits(pCfg.fetchWidth bits)
    val fault          = Bool()
    val numValidInstructions = UInt(log2Up(pCfg.fetchWidth + 1) bits) // <<<< ADD THIS
    val startInstructionIndex = UInt(log2Up(pCfg.fetchWidth) bits)


    def setDefault(): this.type = {
      pc := 0
      instructions.foreach(_ := 0)
      predecodeInfos.foreach(_.setDefault())
      branchMask := 0
      fault := False
      numValidInstructions := 0
    startInstructionIndex := 0
      this
    }
  }

  /**
   * 智能分发器内部用于暂存待处理分支信息的Bundle。
   */
  case class BranchInfo(pCfg: PipelineConfig) extends Bundle {
    val pc               = UInt(pCfg.pcWidth)
    val instruction      = Bits(pCfg.dataWidth)
    val predecodeInfo    = PredecodeInfo()
    val bpuTransactionId = UInt(pCfg.bpuTransactionIdWidth)

    def setDefault(): this.type = {
      pc := 0
      instruction := 0
      predecodeInfo.setDefault()
      bpuTransactionId := 0
      this
  }
}
