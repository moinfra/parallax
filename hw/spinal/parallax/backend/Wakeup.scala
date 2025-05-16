package parallax.backend

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
import parallax.common.Config


case class WakeupPortPayload() extends Bundle {
  val physicalTag = UInt(Config.PHYS_REG_TAG_WIDTH)
}

trait WakeupService extends Service {
  def getWakeupPorts(): Vec[Flow[WakeupPortPayload]] // Get all available wakeup ports
}
