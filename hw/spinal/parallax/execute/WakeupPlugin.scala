// filename: hw/spinal/parallax/execute/WakeupService.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer

// Payload on the wakeup bus is just the physical register tag
case class WakeupPayload(pCfg: PipelineConfig) extends Bundle {
    val physRegIdx = UInt(pCfg.physGprIdxWidth)
}

// The service trait
trait WakeupService extends Service with LockedImpl {
    /** Called by EUs to broadcast a completed tag */
    def newWakeupSource(): Flow[WakeupPayload]

    /** Called by IQs to listen to all wakeup events */
    def getWakeupFlow(): Flow[WakeupPayload]
}

// The plugin that implements the service by merging all sources
class WakeupPlugin(pCfg: PipelineConfig) extends Plugin with WakeupService {

    private val wakeupSources = ArrayBuffer[Flow[WakeupPayload]]()
    private var mergedWakeupFlow: Flow[WakeupPayload] = null

    // Early initialization of the wakeup flow to allow other plugins to access it
    val setup = create early new Area {
        mergedWakeupFlow = Flow(WakeupPayload(pCfg))
        mergedWakeupFlow.setName("globalWakeupFlow")
    }

    override def newWakeupSource(): Flow[WakeupPayload] = {
        val source = Flow(WakeupPayload(pCfg))
        wakeupSources += source
        source
    }

    override def getWakeupFlow(): Flow[WakeupPayload] = {
        if (mergedWakeupFlow == null) {
            SpinalError("getWakeupFlow() called before WakeupPlugin early setup.")
        }
        mergedWakeupFlow
    }

    val logic = create late new Area {
        lock.await()
        if (wakeupSources.nonEmpty) {
            if (wakeupSources.length > 1) {
                // Convert input Flows to Streams
                val streamsToArbiter = wakeupSources.map(_.toStream)
                
                // Arbitrate these streams
                val arbitratedStream = StreamArbiterFactory().roundRobin.on(streamsToArbiter)
                
                // Create a free-running stream (always ready)
                val freeRunningStream = arbitratedStream.freeRun()
                
                // Connect to the pre-created flow
                mergedWakeupFlow.valid := freeRunningStream.valid
                mergedWakeupFlow.payload := freeRunningStream.payload
            } else {
                // Single source, no arbitration needed
                mergedWakeupFlow << wakeupSources.head
            }
        } else {
            // No sources, create empty flow
            mergedWakeupFlow.valid := False
            mergedWakeupFlow.payload.assignDontCare()
        }
    }
}
