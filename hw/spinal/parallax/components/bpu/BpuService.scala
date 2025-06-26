package parallax.bpu

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.utilities.{LockedImpl, Service}

case class BpuUpdate(pCfg: PipelineConfig) extends Bundle {
  val pc      = UInt(pCfg.pcWidth)
  val isTaken = Bool()
  val target  = UInt(pCfg.pcWidth)
}

case class BpuQuery(pCfg: PipelineConfig) extends Bundle {
    val pc = UInt(pCfg.pcWidth)
}

case class BpuResponse(pCfg: PipelineConfig) extends Bundle {
    val isTaken = Bool()
    val target  = UInt(pCfg.pcWidth)
}

trait BpuService extends Service with LockedImpl {
  def newBpuUpdatePort(): Stream[BpuUpdate]
  def newBpuQueryPort(): Flow[BpuQuery]
  def getBpuResponse(): Flow[BpuResponse]
}

trait IssueBpuSignalService extends Service with LockedImpl {
  def getBpuSignals(): IssueBpuSignals
}
