package parallax.bpu

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.utilities.{LockedImpl, Service}
import parallax.utilities.Formattable

case class BpuUpdate(pCfg: PipelineConfig) extends Bundle with Formattable {
  val pc      = UInt(pCfg.pcWidth)
  val isTaken = Bool()
  val target  = UInt(pCfg.pcWidth)

  override def format: Seq[Any] = {
    Seq(
      L"BpuUpdate(",
      L"pc=${pc}, ",
      L"isTaken=${isTaken}, ",
      L"target=${target})"
    )
  }
}

case class BpuQuery(pCfg: PipelineConfig) extends Bundle with Formattable {
    val pc = UInt(pCfg.pcWidth)
    val transactionId = UInt(pCfg.bpuTransactionIdWidth)

    override def format: Seq[Any] = {
      Seq(
        L"BpuQuery(",
        L"pc=${pc}, ",
        L"transactionId=${transactionId})"
      )
    }
}

case class BpuResponse(pCfg: PipelineConfig) extends Bundle with Formattable {
    val isTaken = Bool()
    val target  = UInt(pCfg.pcWidth)
    val transactionId = UInt(pCfg.bpuTransactionIdWidth)

    override def format: Seq[Any] = {
      Seq(
        L"BpuResponse(",
        L"isTaken=${isTaken}, ",
        L"target=${target}, ",
        L"transactionId=${transactionId})"
      )
    }
}

trait BpuService extends Service with LockedImpl {
  def newBpuUpdatePort(): Stream[BpuUpdate]
  def newBpuQueryPort(): Flow[BpuQuery]
  def getBpuResponse(): Flow[BpuResponse]
}

trait IssueBpuSignalService extends Service with LockedImpl {
  def getBpuSignals(): IssueBpuSignals
}
