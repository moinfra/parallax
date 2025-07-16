package parallax.common

import spinal.core._
import spinal.lib._

case class MallocNStreamArbiterIo[T_REQ <: Data, T_RSP <: Data](
    reqType: HardType[T_REQ],
    rspType: HardType[T_RSP],
    numRequesters: Int, 
    numGrantSlots: Int  
) extends Bundle {
  val requester_req = Vec(slave Stream reqType(), numRequesters)
  val requester_rsp = Vec(master Stream rspType(), numRequesters)
  val grant_req = Vec(master Stream reqType(), numGrantSlots)
  val grant_rsp = Vec(slave Stream rspType(), numGrantSlots)
}

class MallocNStreamArbiter[T_REQ <: Data, T_RSP <: Data](
    reqType: HardType[T_REQ],
    rspType: HardType[T_RSP],
    val numRequesters: Int, 
    val numGrantSlots: Int
) extends Component {

  val io = MallocNStreamArbiterIo(reqType, rspType, numRequesters, numGrantSlots)

  val arbiterId = "MallocNArbiter" 
  val enableLog = false 

  if (numRequesters == 0 || numGrantSlots == 0) {
    // ... (no change)
    if (numRequesters > 0) {
      io.requester_req.foreach(_.ready := False) 
      io.requester_rsp.foreach(s => {s.valid := False; s.payload.assignDontCare()})
    }
    if (numGrantSlots > 0) {
      io.grant_req.foreach(s => {s.valid := False; s.payload.assignDontCare()})
      io.grant_rsp.foreach(_.ready := False) 
    }
  } else {
    val requesterIdxWidth = log2Up(numRequesters) bits
    val slotOwnerRegs = Vec.fill(numGrantSlots)(Reg(UInt(requesterIdxWidth)) init(U(0, requesterIdxWidth)))
    val slotBusyRegs = Vec.fill(numGrantSlots)(Reg(Bool()) init (False))

    val reqRrPtr = Reg(UInt(requesterIdxWidth)) init (U(0, requesterIdxWidth))
    
    val grantSlotWantsToGrant = Vec(Bool(), numGrantSlots); grantSlotWantsToGrant.foreach(_ := False)
    val grantSlotGrantedRequesterIdx = Vec(UInt(requesterIdxWidth), numGrantSlots); grantSlotGrantedRequesterIdx.foreach(_.assignDontCare())

    io.requester_req.foreach(_.ready := False)
    io.grant_req.foreach(s => {s.valid := False; s.payload := reqType().getZero })


    // -- MODIFICATION START: Staged availability and scan head --
    // availabilityForPortP(p)(r) = is requester 'r' available for consideration by grant_slot 'p'
    val availabilityForPortP = Vec.tabulate(numGrantSlots){ p_idx => Vec(Bool(), numRequesters) }
    // scanHeadForPortP(p) = the starting requester index for grant_slot 'p's scan
    val scanHeadForPortP = Vec(UInt(requesterIdxWidth), numGrantSlots)

    // Scala var to hold the 'next rr_ptr' that results from the current port's grant.
    // This will be used to determine the scan_head for the *next* port.
    var nextRrHeadAfterThisPort = reqRrPtr 

    for (grantSlotIdx <- 0 until numGrantSlots) {
      if (grantSlotIdx == 0) {
        availabilityForPortP(0).foreach(_ := True) // All requesters available for the first slot
        scanHeadForPortP(0) := reqRrPtr
      } else {
        // Availability for current slot depends on grants made by PREVIOUS slot
        for (reqIdx <- 0 until numRequesters) {
          // An input is available for current slot IF it was available for previous slot
          // AND it was NOT granted by the previous slot.
          val grantedByPreviousSlot = grantSlotWantsToGrant(grantSlotIdx - 1) && 
                                      (grantSlotGrantedRequesterIdx(grantSlotIdx - 1) === U(reqIdx))
          availabilityForPortP(grantSlotIdx)(reqIdx) := availabilityForPortP(grantSlotIdx - 1)(reqIdx) && !grantedByPreviousSlot
        }
        // Scan head for current slot depends on outcome of PREVIOUS slot
        when(grantSlotWantsToGrant(grantSlotIdx - 1)){
          scanHeadForPortP(grantSlotIdx) := (grantSlotGrantedRequesterIdx(grantSlotIdx - 1) + U(1,1 bit)).resize(requesterIdxWidth)
        } otherwise {
          scanHeadForPortP(grantSlotIdx) := scanHeadForPortP(grantSlotIdx - 1)
        }
      }
      
      // Now, perform arbitration for the current grantSlotIdx using its determined availability and scanHead
      var foundGrantForThisSlotLocal = False // Scala var, local to this slot's scan
      for (scanOffset <- 0 until numRequesters) {
        val candidateRequesterIdx = (scanHeadForPortP(grantSlotIdx) + U(scanOffset, log2Up(numRequesters) bits)).resize(requesterIdxWidth)
        
        val canGrantThisRequester = io.requester_req(candidateRequesterIdx).valid &&
                                    availabilityForPortP(grantSlotIdx)(candidateRequesterIdx) &&
                                    !foundGrantForThisSlotLocal
        
        when(canGrantThisRequester && !slotBusyRegs(grantSlotIdx)) { // Also check if slot is free from Reg
          grantSlotWantsToGrant(grantSlotIdx) := True
          grantSlotGrantedRequesterIdx(grantSlotIdx) := candidateRequesterIdx
          
          io.grant_req(grantSlotIdx).valid := io.requester_req(candidateRequesterIdx).valid
          io.grant_req(grantSlotIdx).payload := io.requester_req(candidateRequesterIdx).payload
          
          foundGrantForThisSlotLocal = True
          // The 'nextRrHeadAfterThisPort' should reflect the point after this grant,
          // to be used by the *next* port in the chain or for the final RR update.
          // This needs to be the final value after all ports.
        }
      }
      // After this slot's arbitration, update nextRrHeadAfterThisPort if this slot granted
      when(grantSlotWantsToGrant(grantSlotIdx)){
          nextRrHeadAfterThisPort = (grantSlotGrantedRequesterIdx(grantSlotIdx) + U(1,1 bit)).resize(requesterIdxWidth)
      } otherwise {
          // If this slot didn't grant, the next port starts where this one started (or where the previous one left off)
          // This is implicitly handled if nextRrHeadAfterThisPort is not updated here.
          // More accurately: nextRrHeadAfterThisPort should be scanHeadForPortP(grantSlotIdx) if no grant by this port
          nextRrHeadAfterThisPort = scanHeadForPortP(grantSlotIdx)
      }
    }

    // Update registered state based on grant_req.fire
    for (grantSlotIdx <- 0 until numGrantSlots) {
      when(io.grant_req(grantSlotIdx).fire) {
        slotBusyRegs(grantSlotIdx) := True
        slotOwnerRegs(grantSlotIdx) := grantSlotGrantedRequesterIdx(grantSlotIdx)
        io.requester_req(grantSlotGrantedRequesterIdx(grantSlotIdx)).ready := True 
        if (enableLog) {
            val owner = grantSlotGrantedRequesterIdx(grantSlotIdx)
            // report(L"${arbiterId} Req: Slot[${grantSlotIdx.toString()}] assigned to Requester[${owner}] by fire.")
        }
      }
    }
    
    val anyReqGrantAttempted = grantSlotWantsToGrant.orR
    when(anyReqGrantAttempted) { 
        // nextRrHeadAfterThisPort now holds the value based on the grant of the highest-indexed port that granted
        reqRrPtr := nextRrHeadAfterThisPort
    } otherwise {
        when(io.requester_req.map(_.valid).orR) { 
            if (numRequesters > 1) reqRrPtr := (reqRrPtr + U(1,1 bit)).resize(requesterIdxWidth)
        }
    }
    // -- MODIFICATION END --
    

    // --- Response Path (N grant_rsp to M requester_rsp) ---
    // (Response path logic remains the same as previous correct version)
    io.grant_rsp.foreach(_.ready := False)
    for (requesterIdx <- 0 until numRequesters) {
      io.requester_rsp(requesterIdx).payload := rspType().getZero

      var foundResponseForThisRequester = False 
      val chosenGrantSlotWidth = if(numGrantSlots == 1) 0 else log2Up(numGrantSlots)

      val relevantGrantSlotsValid = Vec(Bool(), numGrantSlots)
      val relevantGrantSlotPayloads = Vec(rspType(), numGrantSlots)
      relevantGrantSlotPayloads.foreach(_.assignDontCare())

      for(grantSlotIdx_rsp <- 0 until numGrantSlots){
          relevantGrantSlotsValid(grantSlotIdx_rsp) := slotBusyRegs(grantSlotIdx_rsp) && 
                                                       (slotOwnerRegs(grantSlotIdx_rsp) === U(requesterIdx)) && 
                                                       io.grant_rsp(grantSlotIdx_rsp).valid
          relevantGrantSlotPayloads(grantSlotIdx_rsp) := io.grant_rsp(grantSlotIdx_rsp).payload
      }
      
      val grantSlotTakesPriority = OHMasking.first(relevantGrantSlotsValid) // one-hot of the first valid
      val responseForThisRequesterValid = grantSlotTakesPriority.orR
      var chosenGrantSlotForThisRequester = OHToUInt(grantSlotTakesPriority)


      io.requester_rsp(requesterIdx).valid := responseForThisRequesterValid
      when(responseForThisRequesterValid) {
        io.requester_rsp(requesterIdx).payload := relevantGrantSlotPayloads(chosenGrantSlotForThisRequester)
        io.grant_rsp(chosenGrantSlotForThisRequester).ready := io.requester_rsp(requesterIdx).ready
            
        when(io.requester_rsp(requesterIdx).fire) { 
            slotBusyRegs(chosenGrantSlotForThisRequester) := False 
            // if (enableLog) report(L"${arbiterId} Rsp: Slot[${chosenGrantSlotForThisRequester}] (for Requester[${requesterIdx.toString()}]) response sent and slot freed.")
        }
      }
    }
  } 
}
