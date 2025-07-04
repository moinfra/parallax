// filename: src/main/scala/parallax/issue/IssueQueueService.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Plugin, Service}
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.LockedImpl

/**
 * The payload sent from Dispatch to an Issue Queue.
 * It contains the fully renamed instruction.
 */
case class IqDispatchCmd(pCfg: PipelineConfig) extends Bundle {
  val uop = RenamedUop(pCfg)
}

/**
 * A service that allows different execution units (or their issue queues)
 * to register themselves to receive dispatched uops.
 */
trait IssueQueueService extends Service with LockedImpl {
  /**
   * Called by an EU's IQ to register itself.
   * @param uopCodes A list of BaseUopCode that this IQ handles.
   * @return A Stream of commands that the Dispatch stage will send to this IQ.
   */
  def newIssueQueue(uopCodes: Seq[BaseUopCode.C]): Stream[IqDispatchCmd]
  def getRegistrations: Seq[(Seq[BaseUopCode.C], Stream[IqDispatchCmd])]
}


// --- Implementation of the service ---

class IssueQueuePlugin(pCfg: PipelineConfig) extends Plugin with IssueQueueService {
  private val registrations = ArrayBuffer[(Seq[BaseUopCode.C], Stream[IqDispatchCmd])]()

  override def newIssueQueue(uopCodes: Seq[BaseUopCode.C]): Stream[IqDispatchCmd] = {
    // This function is called by consumers (IQs) during the 'early' phase.
    // It creates a new Stream port and registers it.
    val port = Stream(IqDispatchCmd(pCfg))
    registrations += ((uopCodes, port))
    port
  }
  
  // This function is called by the DispatchPlugin during the 'late' phase.
  def getRegistrations: Seq[(Seq[BaseUopCode.C], Stream[IqDispatchCmd])] = registrations.toSeq
}
