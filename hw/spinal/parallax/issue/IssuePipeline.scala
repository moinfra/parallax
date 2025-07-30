// filename: src/main/scala/parallax/issue/IssuePipeline.scala
package parallax.issue

import spinal.core._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities._

case class IssuePipelineSignals(val config: PipelineConfig) extends AreaObject {
  val GROUP_PC_IN = Stageable(UInt(config.pcWidth))
  val RAW_INSTRUCTIONS_IN = Stageable(Vec(Bits(config.dataWidth), config.fetchWidth))
  val BRANCH_PREDICTION = Stageable(Vec(BranchPredictionInfo(config), config.fetchWidth))
  val IS_FAULT_IN = Stageable(Bool())
  val VALID_MASK = Stageable(Bits(config.fetchWidth bits))

  val DECODED_UOPS = Stageable(Vec.fill(config.renameWidth)(DecodedUop(config)))
  val RENAMED_UOPS = Stageable(Vec.fill(config.renameWidth)(RenamedUop(config)))
  val ALLOCATED_UOPS = Stageable(Vec(RenamedUop(config), config.renameWidth))
  val NEEDS_PHYS_REG = Stageable(Vec(Bool(), config.renameWidth))

}

class IssuePipeline(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {
  lazy val signals = IssuePipelineSignals(issueConfig)
  val enableLog = true
  val pipeline = create early new Pipeline {
    val s0_decode    = newStage().setName("s0_Decode")
    val s1_rename    = newStage().setName("s1_Rename")
    val s2_rob_alloc = newStage().setName("s2_RobAlloc") // New stage
    val s3_dispatch  = newStage().setName("s3_Dispatch") // Old s2 is now s3


    getServiceOption[DebugDisplayService].foreach(dbg => {
      dbg.setDebugValueOnce(s0_decode.isFiring, DebugValue.DECODE_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(s1_rename.isFiring, DebugValue.RENAME_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(s2_rob_alloc.isFiring, DebugValue.ROBALLOC_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(s3_dispatch.isFiring, DebugValue.DISPATCH_FIRE, expectIncr = true)
    })
    if(enableLog) report(Seq(
      L"IssuePipeline status: \n",
      formatStage(s0_decode),
      formatStage(s1_rename),
      formatStage(s2_rob_alloc),
      formatStage(s3_dispatch)
    ))

    connect(s0_decode, s1_rename)(Connection.M2S())
    connect(s1_rename, s2_rob_alloc)(Connection.M2S()) // Connect new stage
    connect(s2_rob_alloc, s3_dispatch)(Connection.M2S())
  }

  create late new Area {
    lock.await()
    val entry_s0_decode = pipeline.s0_decode
    val s1_rename = pipeline.s1_rename
    val s2_rob_alloc = pipeline.s2_rob_alloc
    val s3_dispatch = pipeline.s3_dispatch
    pipeline.build()
    ParallaxLogger.log("IssuePipeline built")
  }

  def entryStage: Stage = pipeline.s0_decode
  def exitStage: Stage = pipeline.s3_dispatch
}
