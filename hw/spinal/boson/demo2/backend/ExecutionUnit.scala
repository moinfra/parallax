package boson.demo2.backend

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
import boson.demo2.frontend.RenamedMicroOp


object ExecutionUnitData extends AreaObject {
  val RENAMED_UOP = Stageable(RenamedMicroOp())
  val SRC1_VALUE = Stageable(Bits(Config.XLEN bits))
  val SRC2_VALUE = Stageable(Bits(Config.XLEN bits))
  val WRITEBACK_VALUE = Stageable(Bits(Config.XLEN bits))
  val WRITEBACK_ENABLE = Stageable(Bool())
}
