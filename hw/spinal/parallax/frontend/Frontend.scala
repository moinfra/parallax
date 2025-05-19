package parallax.frontend

import spinal.core._
import spinal.core.fiber.{Handle, Lock}
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.misc.pipeline.CtrlLink
import spinal.lib.StreamArbiterFactory
import spinal.lib.pipeline.Stage
import spinal.lib.pipeline.Pipeline
import spinal.lib.fsm._

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import parallax.utilities.LockedImpl
import parallax.common._
import parallax.fetch.FetchPipeline // Assuming FetchPipeline is defined elsewhere

case class FrontendPipelineKeys(val config: PipelineConfig) extends AreaObject {

  // It's better if PipelineConfig is passed to FrontendPipelineKeys if it can vary.
  // e.g., class FrontendPipelineKeys(val config: PipelineConfig) extends AreaObject
  // For now, using a globally accessible default for Stageable type definition.

  val PC = Stageable(UInt(config.pcWidth)) // pc to fetch
  val FETCHED_PC = Stageable(UInt(config.pcWidth)) // pc that accord with the fetched instruction
  val REDIRECT_PC = Stageable(UInt(config.pcWidth)) // 重定向的指令的地址，来自分支或者预测
  val REDIRECT_PC_VALID = Stageable(Bool())
  val INSTRUCTION = Stageable(Bits(config.dataWidth)) // Assuming instruction width = data width
  val FETCH_FAULT = Stageable(Bool())

  // When these Stageables are *used* (e.g. pipeline.stage(UOP) := myUop),
  // myUop must have been created with a PipelineConfig.
  // The Stageable definition itself needs a type, which DecodedUop() provides.
  val UOP = Stageable(DecodedUop(config))
  val RENAMED_UOP = Stageable(RenamedUop(config))
}

class FrontendPipeline(val config: PipelineConfig = PipelineConfig()) extends Plugin with LockedImpl { // Allow config override
  // If FrontendPipelineKeys were a class: val keys = new FrontendPipelineKeys()
  lazy val signals = FrontendPipelineKeys(config)
  val pipeline = create early new Pipeline {
    val decode = newStage()
    val rename = newStage()
    val dispatch = newStage()
    connect(decode, rename)(M2S())
    connect(rename, dispatch)(M2S())

    // Make config available to stages if they create hardware needing it
    // (Often handled by plugins/services or passing config through constructors)
  }

  def firstStage: Stage = pipeline.decode
  def exitStage: Stage = pipeline.dispatch

}

class FetchFrontendBridge(val config: PipelineConfig = PipelineConfig()) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val frontendPipeline = getService[FrontendPipeline]
    fetchPipeline.retain()
    frontendPipeline.retain()
  }

  val logic = create late new Area {
    val sourceStage = setup.fetchPipeline.exitStage
    val destStage = setup.frontendPipeline.firstStage
    val sourceSignals = setup.frontendPipeline.signals
    val destSignals = setup.fetchPipeline.signals

    // Assuming FrontendPipelineKeys are accessible globally
    // And that FetchPipeline output keys match these names/types
    // Ensure data types match what FrontendPipelineKeys expects (widths from config)
    destStage(destSignals.PC) := sourceStage(sourceSignals.PC).resized
    destStage(destSignals.INSTRUCTION) := sourceStage(sourceSignals.INSTRUCTION).resized
    destStage(destSignals.FETCH_FAULT) := sourceStage(sourceSignals.FETCH_FAULT)
    // If FETCHED_PC is also passed from Fetch:
    // destStage(FrontendPipelineKeys.FETCHED_PC) := sourceStage(FrontendPipelineKeys.FETCHED_PC).resized

    destStage.valid := sourceStage.valid
    sourceStage.haltIt(!destStage.isReady)

    setup.fetchPipeline.release()
    setup.frontendPipeline.release()
  }
}
