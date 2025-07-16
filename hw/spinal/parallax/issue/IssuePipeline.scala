// filename: src/main/scala/parallax/issue/IssuePipeline.scala
package parallax.issue

import spinal.core._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities._

// -- MODIFICATION START: No change to Stageables is needed if we reuse RenamedUop --
case class IssuePipelineSignals(val config: PipelineConfig) extends AreaObject {
  val GROUP_PC_IN = Stageable(UInt(config.pcWidth))
  val RAW_INSTRUCTIONS_IN = Stageable(Vec(Bits(config.dataWidth), config.fetchWidth))
  val IS_FAULT_IN = Stageable(Bool())
  val VALID_MASK = Stageable(Bits(config.fetchWidth bits))

  // DECODED_UOPS should use renameWidth since decode stage processes renameWidth instructions
  val DECODED_UOPS = Stageable(Vec.fill(config.renameWidth)(DecodedUop(config)))
  // RENAMED_UOPS will be used across s1_rename, s2_rob_alloc, and s3_dispatch
  val RENAMED_UOPS = Stageable(Vec.fill(config.renameWidth)(RenamedUop(config)))
  // 注册新的 Stageable 信号用于插入分配后的 Uops
  val ALLOCATED_UOPS = Stageable(Vec(RenamedUop(config), config.renameWidth))

  val FLUSH_TARGET_PC = Stageable(UInt(config.pcWidth)) // 暂时用不到，考虑啥时候删了
}
// -- MODIFICATION END --

class IssuePipeline(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {
  lazy val signals = IssuePipelineSignals(issueConfig)

  val pipeline = create early new Pipeline {
    // -- MODIFICATION START: Add s2_rob_alloc stage --
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

    connect(s0_decode, s1_rename)(Connection.M2S())
    connect(s1_rename, s2_rob_alloc)(Connection.M2S()) // Connect new stage
    connect(s2_rob_alloc, s3_dispatch)(Connection.M2S())
    // -- MODIFICATION END --
  }

  create late new Area {
    lock.await()
    val entry_s0_decode = pipeline.s0_decode
    val s1_rename = pipeline.s1_rename
    val s2_rob_alloc = pipeline.s2_rob_alloc
    val s3_dispatch = pipeline.s3_dispatch
    // 加日志，排查每个阶段的 isFiring 和 isReady
    // report(L"DEBUG: s0_decode.isFiring=${entry_s0_decode.isFiring}, isReady=${entry_s0_decode.isReady}")
    // report(L"DEBUG: s1_rename.isFiring=${s1_rename.isFiring}, isReady=${s1_rename.isReady}")
    // report(L"DEBUG: s2_rob_alloc.isFiring=${s2_rob_alloc.isFiring}, isReady=${s2_rob_alloc.isReady}")
    // report(L"DEBUG: s3_dispatch.isFiring=${s3_dispatch.isFiring}, isReady=${s3_dispatch.isReady}")
    pipeline.build()
    ParallaxLogger.log("IssuePipeline built")
  }

  def entryStage: Stage = pipeline.s0_decode
  def exitStage: Stage = pipeline.s3_dispatch
}
