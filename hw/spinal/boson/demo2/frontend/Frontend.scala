package boson.demo2.frontend

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
import boson.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import boson.utilities.LockedImpl
import boson.demo2.common._
import boson.demo2.fetch.FetchPipeline

object FrontendPipelineKeys extends AreaObject {
  val PC = Stageable(UInt(Config.XLEN bits)) // pc to fetch
  val FETCHED_PC = Stageable(UInt(Config.XLEN bits)) // pc that accord with the fetched instruction
  val REDIRECT_PC = Stageable(UInt(Config.XLEN bits)) // 重定向的指令的地址，来自分支或者预测
  val REDIRECT_PC_VALID = Stageable(Bool())
  val INSTRUCTION = Stageable(Bits(Config.XLEN bits))
  val FETCH_FAULT = Stageable(Bool())
  val UOP = Stageable(MicroOp())
  val RENAMED_UOP = Stageable(RenamedMicroOp())
}

class FrontendPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val decode = newStage()
    val rename = newStage()
    val dispatch = newStage()
    connect(decode, rename)(M2S())
    connect(rename, dispatch)(M2S())
  }
  pipeline.setCompositeName(this, "Frontend")

  def firstStage: Stage = pipeline.decode
  def exitStage: Stage = pipeline.dispatch
}

class FetchFrontendBridge extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val frontendPipeline = getService[FrontendPipeline]
    fetchPipeline.retain()
    frontendPipeline.retain()
  }

  val logic = create late new Area {
    val sourceStage = setup.fetchPipeline.exitStage
    val destStage = setup.frontendPipeline.firstStage

    destStage(FrontendPipelineKeys.PC) := sourceStage(FrontendPipelineKeys.PC)
    destStage(FrontendPipelineKeys.INSTRUCTION) := sourceStage(FrontendPipelineKeys.INSTRUCTION)
    destStage(FrontendPipelineKeys.FETCH_FAULT) := sourceStage(FrontendPipelineKeys.FETCH_FAULT)

    destStage.valid := sourceStage.valid
    sourceStage.haltIt(!destStage.isReady)

    setup.fetchPipeline.release()
    setup.frontendPipeline.release()
  }
}
