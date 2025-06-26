package parallax.frontend.v2

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.bpu.BpuResponse
import parallax.utilities.Formattable
import parallax.common.HasRobPtr

// 1. PC生成器向FetchBuffer发出的“取指请求”
//    只包含预测信息，还没有指令。
case class FetchRequest(pCfg: PipelineConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val bpuResponse = BpuResponse(pCfg) // 与PC绑定的预测结果
}

// 2. FetchBuffer向解码器发出的“完整指令包”
//    包含了所有取指信息。
case class FetchPacket(pCfg: PipelineConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val instructions = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  val fault = Bool()
  val bpuResponse = BpuResponse(pCfg) // 原始的预测结果，随指令一起传递
  // 可以添加更多元数据，例如指令有效掩码等
  // val mask = Bits(pCfg.fetchWidth bits)
}

// 这个 uop 非常简单，只需要一个 robPtr
// 其他信息（如PC）将存储在 ROB 的 payload 部分
case class FetchUop(pCfg: PipelineConfig, robPtrWidth: BitCount) extends Bundle with Formattable with HasRobPtr {
  val robPtr = UInt(robPtrWidth)

  override def format(): Seq[Any] = Seq(L"FetchUop(robPtr=${robPtr})")
}
