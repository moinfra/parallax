package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.components.decode._ // For SimpleDecoder
import parallax.utilities.{LockedImpl, ParallaxLogger, Plugin} // 确保有这些import

class DecodePlugin(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline] // 获取 IssuePipeline 服务
    issuePpl.retain() // 因为我们要配置其阶段
    val bpuSignalService = getService[IssueBpuSignalService]
    val bpuSignals = bpuSignalService.getBpuSignals()


    val s0_decode = issuePpl.pipeline.s0_decode // 获取 s0_decode 阶段
    // 声明 s0_decode 阶段将消耗的输入 Stageable 和产生的输出 Stageable
    s0_decode(issuePpl.signals.GROUP_PC_IN) // consumes
    s0_decode(issuePpl.signals.RAW_INSTRUCTIONS_IN) // consumes
    s0_decode(issuePpl.signals.IS_FAULT_IN) // consumes
    s0_decode(bpuSignals.INSTRUCTION_VALID_MASK)
    s0_decode(issuePpl.signals.DECODED_UOPS) // produces
  }

  val logic = create late new Area {
    lock.await() // 如果其它组件需要阻止编码器过早完成elaborate，我们需要在这里等他们操作完。
    val issuePpl = setup.issuePpl // 从 setup 中获取
    val signals = issuePpl.signals
    val s0_decode = issuePpl.pipeline.s0_decode

    ParallaxLogger.log(s"DecodePlugin: logic LATE area entered for s0_decode")

    // --- 读取输入 Stageables ---
    // 这些值仅在 s0_decode.isFiring 或 s0_decode.isValid 时有意义
    val groupPcIn = s0_decode(signals.GROUP_PC_IN)
    val rawInstructionsIn = s0_decode(signals.RAW_INSTRUCTIONS_IN)
    val isGroupFaultIn = s0_decode(signals.IS_FAULT_IN)

    // --- 并行解码逻辑 ---
    // 创建一个 Vec 来存储每个解码槽的 DecodedUop 结果
    val decodedUopsOutputVec = Vec(HardType(DecodedUop(issueConfig)), issueConfig.fetchWidth)
    val validMaskIn = s0_decode(setup.bpuSignals.INSTRUCTION_VALID_MASK)
    for (i <- 0 until issueConfig.fetchWidth) {
      val decoder = new SimpleDecoder(issueConfig) // 每个槽位一个解码器实例

      // 计算当前指令的精确PC
      val instructionPC = groupPcIn + U(i * issueConfig.bytesPerInstruction)

      // 连接解码器输入
      decoder.io.instruction := rawInstructionsIn(i)
      decoder.io.pcIn := instructionPC

      // 获取解码器的输出
      val currentDecodedUop = DecodedUop(issueConfig)
      currentDecodedUop := decoder.io.decodedUop

      // 处理来自取指阶段的故障（覆盖解码器自身的判断）
      when(isGroupFaultIn) {
        currentDecodedUop.isValid := False
        currentDecodedUop.hasDecodeException := True
        currentDecodedUop.decodeExceptionCode := DecodeExCode.FETCH_ERROR
        currentDecodedUop.uopCode := BaseUopCode.ILLEGAL // 或 NOP
      }
      // 如果解码器本身发现错误 (e.g., 非法指令), currentDecodedUop.hasDecodeException 会是 True
      // isGroupFaultIn 的优先级更高
      currentDecodedUop.isValid := currentDecodedUop.isValid && validMaskIn(i)
      decodedUopsOutputVec(i) := currentDecodedUop
    }

    // 将解码结果驱动到 s0_decode 阶段的输出 Stageable
    s0_decode(signals.DECODED_UOPS) := decodedUopsOutputVec

    // --- 详细报告 ---
    when(s0_decode.isFiring) {
      report(L"DecodePlugin (s0_decode): Firing. Input PC_Group=${groupPcIn}, Input GroupFault=${isGroupFaultIn}")
      for (i <- 0 until issueConfig.fetchWidth) {
        val pc = groupPcIn + U(i * issueConfig.bytesPerInstruction)
        report(
          L"  Slot ${i.toString()}: RawInstr=${rawInstructionsIn(i)}, Calc PC=${pc} -> Decoded PC=${decodedUopsOutputVec(i).pc}, Valid=${decodedUopsOutputVec(
              i
            ).isValid}, UopCode=${decodedUopsOutputVec(i).uopCode}, Excp=${decodedUopsOutputVec(i).hasDecodeException}, ExcpCode=${decodedUopsOutputVec(i).decodeExceptionCode}"
        )
      }
    }

    setup.issuePpl.release()
  }
}
