package parallax.bpu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Stageable
import parallax.common.{BranchPredictionInfo, PipelineConfig}

/**
 * 这是一个包含BPU相关信号的独立数据包，将被注入到IssuePipeline中。
 * 继承自AreaObject，使其可以作为Stageable的容器。
 */
case class IssueBpuSignals(pCfg: PipelineConfig) extends AreaObject {
  // 由 BpuAndAlignerPlugin 驱动，在 s0_decode 阶段使用
  val INSTRUCTION_VALID_MASK = Stageable(Bits(pCfg.fetchWidth bits))
  // 由 BpuAndAlignerPlugin 驱动，在 s1_rename 阶段使用
  val PREDICTION_INFO        = Stageable(BranchPredictionInfo(pCfg))
}
