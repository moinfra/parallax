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

    override def newWakeupSource(): Flow[WakeupPayload] = {
        val source = Flow(WakeupPayload(pCfg))
        wakeupSources += source
        source
    }

    override def getWakeupFlow(): Flow[WakeupPayload] = {
        if (mergedWakeupFlow == null) {
            SpinalError("getWakeupFlow() called before WakeupPlugin logic is built.")
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
                
                // Manually create Flow from the free-running stream
                val manualFlow = Flow(WakeupPayload(pCfg))
                manualFlow.valid := freeRunningStream.valid
                manualFlow.payload := freeRunningStream.payload
                
                mergedWakeupFlow = manualFlow
            } else {
                // Single source, no arbitration needed
                mergedWakeupFlow = wakeupSources.head
            }
        } else {
            // No sources, create empty flow
            val emptyFlow = Flow(WakeupPayload(pCfg))
            emptyFlow.valid := False
            emptyFlow.payload.assignDontCare()
            mergedWakeupFlow = emptyFlow
        }
    }
}
