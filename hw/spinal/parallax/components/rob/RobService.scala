// In parallax/components/rob/RobService.scala (or similar)
package parallax.components.rob // Or parallax.common

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.Service

// This is the slot interface that Dispatch will drive as a Master
// It's essentially what was in ROBIo.allocate(i)
case class RobAllocateRequest(config: PipelineConfig) extends Bundle {
  val uopIn = RenamedUop(config) // Or a subset of fields ROB needs for allocation
  val pcIn = UInt(config.pcWidth)
}

case class RobAllocateResponse(config: PipelineConfig) extends Bundle {
  val robIdx = UInt(config.robIdxWidth)
  val allocated = Bool() // True if this specific request was granted
}

trait RobService extends Service {
  // Dispatch drives requests, ROB provides responses and overall status
  // DispatchWidth is the number of parallel allocation requests Dispatch can make
  def getRobAllocatePorts(dispatchWidth: Int): Vec[Stream[RobAllocateRequest]] // Dispatch is Slave to this Stream (drives ready)
                                                                             // ROB is Master (drives valid, payload)
                                                                             // OR:
                                                                             // Vec[Flow[RobAllocateRequest]] if ROB drives valid/payload
                                                                             // and Dispatch drives ready on an outer Stream.
                                                                             // Let's refine this.

  // A more direct approach: Dispatch is MASTER of ROB's allocation interface.
  // ROBIo.allocate is Vec(master(ROBAllocateSlot(config)))
  // ROBAllocateSlot has: fire (out), uopIn (out), pcIn (out), robIdx (in)
  // ROBIo also needs: canAllocate(i) (out Bool) for each slot.

  // Let's assume RobService provides a way for Dispatch to be the master.
  // This implies RobService might just return the ROBIo instance or a sub-interface.
  // For now, let's stick to the conceptual `getRobAllocationControl` and refine its internals.
  // The key is that Dispatch needs to send a request and get a confirmation + robIdx per slot.

  // A better conceptual interface for RobService might be:
  // Dispatch asks: "I have X uops, can you allocate them and give me their robIdx?"
  // ROB responds: "Yes/No for each, and here are the robIdx for the 'Yes' ones."

  // Let's refine robAllocationCtrl from DispatchPlugin's perspective:
  // Dispatch needs to:
  // 1. For each slot i: Tell ROB "I want to allocate this uop: renamedUopsIn(i)" -> robAllocationCtrl.requests(i).valid/payload
  // 2. ROB needs to tell Dispatch for each slot i: "OK, allocated, robIdx is Y" OR "Cannot allocate" -> robAllocationCtrl.responses(i).valid/payload.robIdx / .allocated
  // This implies:
  // requests: Vec[Flow[RenamedUop]] (Dispatch drives valid/payload)
  // responses: Vec[Flow[RobAllocateResponse]] (ROB drives valid/payload)
  // This seems more aligned. Dispatch sends, ROB responds.
  def getRobAllocationInterface(dispatchWidth: Int): RobAllocationInterface
}

case class RobAllocationInterface(dispatchWidth: Int, config: PipelineConfig) extends Bundle {
  val requests = Vec(master(Flow(RenamedUop(config))), dispatchWidth) // Dispatch drives these
  val responses = Vec(slave(Flow(RobAllocateResponse(config))), dispatchWidth) // ROB drives these
}
