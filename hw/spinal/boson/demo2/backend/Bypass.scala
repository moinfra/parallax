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


case class BypassSource() extends Bundle {
  val valid = Bool()
  val pDest = UInt(Config.PHYS_REG_TAG_WIDTH)
  val value = Bits(Config.XLEN bits)
}

trait BypassService extends Service {
  def addBypassSource(source: BypassSource): Unit
  def getAllBypassSources(): Seq[BypassSource]
}



// --- Bypass Service Implementation --- (NO CHANGES)
class BypassPlugin extends Plugin with BypassService with LockedImpl {
  private val sources = ArrayBuffer[BypassSource]()
  override def addBypassSource(source: BypassSource): Unit = sources += source
  override def getAllBypassSources(): Seq[BypassSource] = sources.toSeq
}
