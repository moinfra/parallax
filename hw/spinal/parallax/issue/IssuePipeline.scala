package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.decode._
import parallax.utilities._

case class IssuePipelineSignals(val config: PipelineConfig) extends AreaObject {
  val GROUP_PC_IN = Stageable(UInt(config.pcWidth))
  val RAW_INSTRUCTIONS_IN = Stageable(Vec(Bits(config.dataWidth), config.fetchWidth))
  val IS_FAULT_IN = Stageable(Bool())
  val VALID_MASK = Stageable(Bits(config.fetchWidth bits)) // <<< 新增

  val DECODED_UOPS = Stageable(Vec.fill(config.fetchWidth)(DecodedUop(config)))

  val RENAMED_UOPS = Stageable(Vec.fill(config.fetchWidth)(RenamedUop(config)))

  val DISPATCHED_UOPS = Stageable(Vec.fill(config.fetchWidth)(RenamedUop(config)))

  val FLUSH_PIPELINE = Stageable(Bool())
  val FLUSH_TARGET_PC = Stageable(UInt(config.pcWidth))
}

class IssuePipeline(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {
  lazy val signals = IssuePipelineSignals(issueConfig)

  val pipeline = create early new Pipeline {
    val s0_decode = newStage().setName("s0_Decode")
    val s1_rename = newStage().setName("s1_Rename")
    val s2_Dispatch = newStage().setName("s2_Dispatch")

    connect(s0_decode, s1_rename)(Connection.M2S())
    connect(s1_rename, s2_Dispatch)(Connection.M2S())
  }

  create late new Area {
    lock.await()

    val entry_s0_decode = pipeline.s0_decode
    when(entry_s0_decode(signals.FLUSH_PIPELINE)) {
      report(
        L"IssuePipeline: FLUSHING all stages due to FLUSH_PIPELINE signal. Target PC: ${entry_s0_decode(signals.FLUSH_TARGET_PC)}"
      )

      pipeline.s1_rename.flushIt()
      pipeline.s2_Dispatch.flushIt()

    }

    pipeline.build()
    ParallaxLogger.log("IssuePipeline built")
  }

  def entryStage: Stage = pipeline.s0_decode
  def exitStage: Stage = pipeline.s2_Dispatch

}
