// filename: parallax/issue/DispatchPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rob._
import parallax.components.issue._
import parallax.utilities._

// Service definition for IQ allocation (example for ALU IQ)
// This would typically be provided by the AluIntIQPlugin.
// Defined here or in a shared 'interfaces' package.
trait AluIntIQAllocateService extends Service {
  // For a single ALU IQ, or the first one if multiple exist and Dispatch targets one by one
  def getAluIntIQAllocationPort(): Flow[IQEntryAluInt]
  def getAluIntIQCanAccept(): Bool

  // If supporting multiple ALU IQs directly from Dispatch service:
  // def getAluIntIQAllocationPorts(): Vec[Flow[IQEntryAluInt]]
  // def getAluIntIQCanAccepts(): Vec[Bool]
  // def getNumAluIQs(): Int
}

class DispatchPlugin(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {

  // --- Early phase for service discovery and pipeline stage setup ---
  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    issuePpl.retain()

    val robService = getService[RobService]
    val aluIntIqAllocService = getService[AluIntIQAllocateService]

    val s2_Dispatch = issuePpl.pipeline.s2_Dispatch
    s2_Dispatch(issuePpl.signals.RENAMED_UOPS) // Consumes
    s2_Dispatch(issuePpl.signals.DISPATCHED_UOPS) // Produces
    s2_Dispatch(issuePpl.signals.FLUSH_PIPELINE) // Consumes
  }

  // --- Late phase for implementing the hardware logic ---
  val logic = create late new Area {
    lock.await()

    val issuePpl = setup.issuePpl
    val signals = issuePpl.signals
    val s2_Dispatch = issuePpl.pipeline.s2_Dispatch

    // --- Get interfaces from services ---
    val robAllocationInterface = setup.robService.getRobAllocationInterface(issueConfig.dispatchWidth)
    // robAllocationInterface.requests(i): master Flow[RenamedUop]
    // robAllocationInterface.responses(i): slave Flow[RobAllocateResponse]

    // Assuming for now a single ALU IQ target from this service
    val aluIqAllocFlow = setup.aluIntIqAllocService.getAluIntIQAllocationPort()
    val aluIqCanAccept = setup.aluIntIqAllocService.getAluIntIQCanAccept()

    // --- Input from s2_Dispatch stage ---
    val renamedUopsIn = s2_Dispatch(signals.RENAMED_UOPS)
    val dispatchedUopsOut = Vec.fill(issueConfig.dispatchWidth)(RenamedUop(issueConfig))
    dispatchedUopsOut.foreach(_.setDefault())

    // --- Pipeline Control Signals ---
    val stageFlush = s2_Dispatch(signals.FLUSH_PIPELINE)

    // --- Per-slot dispatch logic: ROB Allocation Part ---
    val slotWantsToDispatch = Vec(Bool(), issueConfig.dispatchWidth)
    val slotRobRequestSent = Vec(Bool(), issueConfig.dispatchWidth) // Track if ROB request was made
    val slotRobAllocated = Vec(Bool(), issueConfig.dispatchWidth)
    val slotAllocatedRobIdx = Vec(UInt(issueConfig.robIdxWidth), issueConfig.dispatchWidth)
    val slotIsAluInt = Vec(Bool(), issueConfig.dispatchWidth)

    for (i <- 0 until issueConfig.dispatchWidth) {
      val currentRenamedUop = renamedUopsIn(i)
      slotIsAluInt(i) := currentRenamedUop.decoded.exeUnit === ExeUnitType.ALU_INT
      slotWantsToDispatch(i) := currentRenamedUop.decoded.isValid && !stageFlush

      // Drive ROB allocation request
      robAllocationInterface.requests(i).valid := slotWantsToDispatch(i)
      robAllocationInterface.requests(i).payload := currentRenamedUop // ROB might need the full Uop
      slotRobRequestSent(i) := robAllocationInterface.requests(i).valid // For debug/stall logic

      // Process ROB allocation response
      slotRobAllocated(i) := robAllocationInterface
        .responses(i)
        .valid && robAllocationInterface.responses(i).payload.allocated
      when(slotRobAllocated(i)) {
        slotAllocatedRobIdx(i) := robAllocationInterface.responses(i).payload.robIdx
      } otherwise {
        slotAllocatedRobIdx(i) := U(0) // Default if not allocated
      }

      // Prepare the output UOP for DISPATCHED_UOPS Stageable
      // This Uop is considered "dispatched" if ROB allocation was successful (or not needed for invalid uops)
      dispatchedUopsOut(i) := currentRenamedUop // Start with a copy
      when(slotWantsToDispatch(i) && slotRobAllocated(i)) {
        dispatchedUopsOut(i).decoded.isValid := True // Mark as validly processed by dispatch for ROB
        dispatchedUopsOut(i).robIdx := slotAllocatedRobIdx(i)
      } otherwise {
        // If it didn't want to dispatch (invalid uop or flush), or ROB alloc failed
        dispatchedUopsOut(i).decoded.isValid := False
      }
    }

    // --- ALU IQ Allocation Part: Arbitration and Driving Flow ---
    val aluIqCandidateStreams = Vec(Stream(IQEntryAluInt(issueConfig)), issueConfig.dispatchWidth)
    val slotWantsAluIqDispatch = Vec(Bool(), issueConfig.dispatchWidth)

    for (i <- 0 until issueConfig.dispatchWidth) {
      // A slot is a candidate for ALU IQ if it wanted to dispatch, was an ALU op, AND successfully got a ROB slot
      val isCandidateForAluIq = slotWantsToDispatch(i) && slotIsAluInt(i) && slotRobAllocated(i)
      slotWantsAluIqDispatch(i) := isCandidateForAluIq

      aluIqCandidateStreams(i).valid := isCandidateForAluIq
      aluIqCandidateStreams(i).payload := IQEntryAluInt(issueConfig).initFrom(
        renamedUopsIn(i),
        slotAllocatedRobIdx(i) // Use the robIdx obtained above
      )
    }

    // Arbitrate these candidate streams to the single ALU IQ allocation flow
    val aluIqArbiter = StreamArbiterFactory.roundRobin.on(aluIqCandidateStreams)

    aluIqAllocFlow.valid := aluIqArbiter.valid && aluIqCanAccept
    aluIqAllocFlow.payload := aluIqArbiter.payload
    aluIqArbiter.ready := aluIqCanAccept // Backpressure to arbiter if IQ cannot accept

    // --- Determine overall stage stall conditions ---
    var stageStallCondition = False

    for (i <- 0 until issueConfig.dispatchWidth) {
      // Stall if a valid uop wanted to dispatch (ROB request was sent) but ROB did not allocate for it
      when(slotRobRequestSent(i) && !slotRobAllocated(i)) {
        stageStallCondition = True
        // Use report(L"...") for sim-time hardware signal values
        report(
          L"DispatchPlugin: Slot ${i.toString()} STALLED due to ROB not allocating. UopValid: ${renamedUopsIn(i).decoded.isValid}, Flush: ${stageFlush}"
        )
      }

      // Stall if a slot was a candidate for ALU IQ but its request through the arbiter is not being accepted.
      // This means aluIqCandidateStreams(i).valid was true, but aluIqCandidateStreams(i).ready is false.
      when(aluIqCandidateStreams(i).valid && !aluIqCandidateStreams(i).ready) {
        stageStallCondition = True
        report(
          L"DispatchPlugin: Slot ${i.toString()} (ALU Cand.) STALLED, arbiter for ALU IQ not ready. ROBIdx: ${slotAllocatedRobIdx(i)}"
        )
      }
    }
    // An additional check: if the arbiter has selected a uop (arbiter.valid is true)
    // but the ALU IQ cannot accept it (aluIqCanAccept is false), this will cause arbiter.ready to be false,
    // which in turn makes the winning aluIqCandidateStreams(winner_idx).ready false, triggering the stall above.

    // Drive pipeline control signals
    s2_Dispatch.haltIt(stageStallCondition)

    // Assign the final output to the DISPATCHED_UOPS Stageable
    s2_Dispatch(signals.DISPATCHED_UOPS) := dispatchedUopsOut

    // --- Debugging reports (use report(L"...") for hardware signals) ---
    when(s2_Dispatch.isFiring) {
      report(L"DispatchPlugin: s2_Dispatch is FIRING. StageStalled=${stageStallCondition}")
      for (i <- 0 until issueConfig.dispatchWidth) {
        when(dispatchedUopsOut(i).decoded.isValid) {
          report(
            L"  Slot ${i.toString()}: Dispatched UOP. PC=0x${renamedUopsIn(i).decoded.pc}, ROBIdx=${dispatchedUopsOut(i).robIdx}, IsALU=${slotIsAluInt(i)}"
          )
          when(slotIsAluInt(i) && aluIqAllocFlow.valid && aluIqArbiter.payload.robIdx === dispatchedUopsOut(i).robIdx) {
            // This check for robIdx match is a bit complex for a simple report,
            // better to check if the arbiter's chosen one corresponds to this slot's original request.
            // For now, just indicate if aluIqAllocFlow is firing.
            report(L"    ALU IQ Dest: Firing to ALU IQ with ROBIdx=${aluIqAllocFlow.payload.robIdx}")
          }
        }
      }
    }
    when(stageStallCondition && s2_Dispatch.isValid) { // Report when stalled but had valid input
      report(L"DispatchPlugin: s2_Dispatch is STALLED but had valid input.")
    }

    // --- Release services ---
    setup.issuePpl.release()
    // Assuming RobService and AluIntIQAllocateService might have their own retain/release if they are LockedImpl
    // If they were retained in setup, they should be released here.
    // e.g., if (setup.robService.isInstanceOf[LockedImpl]) setup.robService.release()
    // This depends on the actual service implementation.

    ParallaxLogger.log("DispatchPlugin logic elaboration complete.") // Elaboration-time log
  }
}
