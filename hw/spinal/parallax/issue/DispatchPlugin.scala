// filename: src/main/scala/parallax/issue/DispatchPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
import parallax.components.issue._
import parallax.utilities.ParallaxSim

class DispatchPlugin(val pCfg: PipelineConfig) extends Plugin with LockedImpl {
  
  assert(pCfg.renameWidth == 1, "This DispatchPlugin is designed for a rename/dispatch width of 1.")

  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val iqService = getService[IssueQueueService]
    issuePpl.retain()
    iqService.retain()

    val s2_dispatch = issuePpl.pipeline.s2_Dispatch
    s2_dispatch(issuePpl.signals.RENAMED_UOPS) // Input
  }

  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val iqService = setup.iqService
    val s2_dispatch = issuePpl.pipeline.s2_Dispatch
    val issueSignals = issuePpl.signals
    
    val renamedUopIn = s2_dispatch(issueSignals.RENAMED_UOPS)(0)
    val decodedUop = renamedUopIn.decoded
    val robPtr = renamedUopIn.robPtr

    val iqRegistrations = iqService.getRegistrations
    val iqPorts = iqRegistrations.map(_._2)

    // --- Default assignments for output ports ---
    iqPorts.foreach(_.valid := False)
    iqPorts.foreach(_.payload.assignDontCare())

    val driveOutputValid = s2_dispatch.isValid && decodedUop.isValid

    // -- MODIFICATION START: High-performance structural modeling --

    // 1. Create a one-hot vector indicating which IQ the uop should be dispatched to.
    //    Each bit of this vector corresponds to an IQ port.
    val dispatchOH = B(iqRegistrations.map { case (uopCodes, _) =>
      uopCodes.map(decodedUop.uopCode === _).orR
    })

    // 2. Use MuxOH to select the ready signal from the chosen IQ port.
    //    This creates a fast, parallel Mux instead of a slow priority encoder.
    //    The inputs to the MuxOH are all the ready signals from the IQ ports.
    val iqPortsReady = Vec(iqPorts.map(_.ready))
    val destinationIqReady = MuxOH(dispatchOH, iqPortsReady)

    // 3. The final destination is ready if either the target IQ is ready,
    //    or if the instruction doesn't need to be dispatched to any IQ (e.g., NOP).
    val isHandledByIq = dispatchOH.orR
    val destinationReady = destinationIqReady || !isHandledByIq

    // 4. Drive the output ports' valid and payload signals using the one-hot vector.
    for (((uopCodes, port), i) <- iqRegistrations.zipWithIndex) {
      when(driveOutputValid && dispatchOH(i)) {
        port.valid := True
        port.payload.uop := renamedUopIn
      }
    }
    
    // --- Pipeline Handshake ---
    s2_dispatch.haltWhen(driveOutputValid && !destinationReady)

    // -- MODIFICATION END --
    
    when(s2_dispatch.isFiring && decodedUop.isValid) {
      ParallaxSim.log(L"DispatchPlugin: Firing robPtr=${robPtr} (UopCode=${decodedUop.uopCode})")
    }
    
    setup.issuePpl.release()
    setup.iqService.release()
  }
}
