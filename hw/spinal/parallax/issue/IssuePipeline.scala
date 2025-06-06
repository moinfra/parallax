package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.decode._
import parallax.utilities._

// RenamedUop 之前已定义

case class IssuePipelineSignals(val config: PipelineConfig) extends AreaObject {
  val GROUP_PC_IN = Stageable(UInt(config.pcWidth)) // 取指组的起始PC
  val RAW_INSTRUCTIONS_IN = Stageable(Vec(Bits(config.dataWidth), config.fetchWidth))
  val IS_FAULT_IN = Stageable(Bool()) // 整个取指组是否有故障

  // --- s0_decode 的输出 / s1_rename 的输入 ---
  val DECODED_UOPS = Stageable(Vec.fill(config.fetchWidth)(DecodedUop(config)))

  // --- s1_rename 的输出 / s2_Dispatch 的输入 ---
  val RENAMED_UOPS = Stageable(Vec.fill(config.fetchWidth)(RenamedUop(config)))

  // --- s2_Dispatch 的输出 (送往IQ/ROB写等) ---
  // 仍然是 RenamedUop，但其 robPtr 字段已被填充
  val DISPATCHED_UOPS = Stageable(Vec.fill(config.fetchWidth)(RenamedUop(config)))

  // 流水线冲刷信号 (可被插入到任何阶段，通常由BRU或Commit阶段驱动回来)
  val FLUSH_PIPELINE = Stageable(Bool())
  val FLUSH_TARGET_PC = Stageable(UInt(config.pcWidth))
}

class IssuePipeline(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {
    lazy val signals = IssuePipelineSignals(issueConfig)
  
    // 定义流水线及其阶段
    val pipeline = create early new Pipeline {
      val s0_decode   = newStage().setName("s0_Decode")
      val s1_rename   = newStage().setName("s1_Rename")
      val s2_Dispatch = newStage().setName("s2_Dispatch")
  
      // 连接阶段
      connect(s0_decode, s1_rename)(Connection.M2S())   // Master to Slave connection (通常带寄存器)
      connect(s1_rename, s2_Dispatch)(Connection.M2S())
    }
  
    // 流水线构建和可选的全局逻辑 (如冲刷)
    create late new Area {
      lock.await() // 等待所有插件的setup完成
  
      // 示例：全局冲刷逻辑
      // 这个冲刷信号可能由BRU (Branch Resolution Unit) 或 Commit 阶段通过服务机制驱动回来
      // FLUSH_PIPELINE 和 FLUSH_TARGET_PC 需要被某个服务或输入正确驱动
      // 这里只是展示如何应用它
      val entry_s0_decode = pipeline.s0_decode // 获取入口阶段的引用
      when(entry_s0_decode(signals.FLUSH_PIPELINE)) {
        report(L"IssuePipeline: FLUSHING all stages due to FLUSH_PIPELINE signal. Target PC: ${entry_s0_decode(signals.FLUSH_TARGET_PC)}")
        // 冲刷 s0_decode 之后的所有阶段。s0_decode 自身通常不被自己插入的 FLUSH_PIPELINE 冲刷，
        // 而是其输入有效性会被控制，或者其内容被新PC覆盖。
        // 更安全的做法是让Fetch流水线处理PC重定向，Issue流水线仅清空其内容。
        pipeline.s1_rename.flushIt()   // 冲刷 Rename 阶段
        pipeline.s2_Dispatch.flushIt() // 冲刷 Dispatch 阶段
        // s0_decode 阶段可能需要特殊处理，例如，如果它有内部状态，
        // 或者它的输入 valid 应该被拉低，或者它的输出 Stageable 被设置为无效/NOP。
        // 通常，`flushIt()` 会处理阶段的输出有效性。
        // 对于 s0_decode，如果它的输入来自上游，上游的 flush 会传递过来。
        // 如果 FLUSH_PIPELINE 是在本流水线内部产生的，s0_decode 的输入可能仍然有效，
        // 此时 s0_decode 需要被正确地“清除”或“NOP化”。
        // 简单起见，先只 flush s1 及之后的。更复杂的 flush 需要仔细设计。
        // 或者，如果 FLUSH_PIPELINE 意味着整个流水线从头开始，那么所有阶段都应 flush。
        // pipeline.stagesSet.foreach(_.flushIt(entry_s0_decode(signals.FLUSH_PIPELINE))) // 这样会冲刷所有阶段
      }
  
      pipeline.build()
      ParallaxLogger.log("IssuePipeline built")
    }
  
    // 定义流水线的入口和出口阶段
    def entryStage: Stage = pipeline.s0_decode
    def exitStage: Stage = pipeline.s2_Dispatch
  
    // （可选）提供服务，例如获取 signals 对象
    // def getSignals(): IssuePipelineSignals = signals
  }
