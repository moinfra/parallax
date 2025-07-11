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
        
        // Add logging to debug wakeup source collection (elaboration time)
        ParallaxLogger.log(s"WakeupPlugin: Found ${wakeupSources.length} wakeup sources")
        
        if (wakeupSources.nonEmpty) {
            if (wakeupSources.length > 1) {
                // FIXED: Direct Flow merging instead of Stream arbitration
                // Multiple wakeup signals can occur simultaneously and should all be propagated
                // We use a priority encoder: any valid wakeup will be forwarded
                val anyValid = wakeupSources.map(_.valid).reduce(_ || _)
                
                // Priority-based selection: first valid source wins
                val selectedPayload = WakeupPayload(pCfg)
                selectedPayload.assignDontCare()
                
                // Use when-elsewhen chain for priority selection
                var priorityChain = when(False) {} // Start with dummy condition
                for ((source, idx) <- wakeupSources.zipWithIndex) {
                    priorityChain = priorityChain.elsewhen(source.valid) {
                        selectedPayload := source.payload
                        // Add debugging for each wakeup source (simulation time)
                        ParallaxSim.log(L"WakeupPlugin: Forwarding wakeup from source ${idx}, physReg=${source.payload.physRegIdx}")
                    }
                }
                
                mergedWakeupFlow.valid := anyValid
                mergedWakeupFlow.payload := selectedPayload
                
                // Debug logging for merged output (simulation time)
                when(mergedWakeupFlow.valid) {
                    ParallaxSim.log(L"WakeupPlugin: GLOBAL WAKEUP sent for physReg=${mergedWakeupFlow.payload.physRegIdx}")
                }
            } else {
                // Single source, direct connection
                mergedWakeupFlow << wakeupSources.head
                ParallaxLogger.log("WakeupPlugin: Single source - direct connection")
                
                // Debug logging for single source (simulation time)
                when(mergedWakeupFlow.valid) {
                    ParallaxSim.log(L"WakeupPlugin: GLOBAL WAKEUP (single source) sent for physReg=${mergedWakeupFlow.payload.physRegIdx}")
                }
            }
        } else {
            // No sources, create empty flow
            mergedWakeupFlow.valid := False
            mergedWakeupFlow.payload.assignDontCare()
            ParallaxLogger.log("WakeupPlugin: No wakeup sources found")
            // Also log at simulation time
            ParallaxSim.log(L"WakeupPlugin: ERROR - No wakeup sources registered!")
        }
    }
}
