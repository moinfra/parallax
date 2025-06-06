package parallax.common

import spinal.core._
import spinal.lib._

trait CdbTargetedMessage[K <: Data] extends Bundle {
  def cdbTargetIdx: K
}

class GenericCdbArbiter[K <: Data, T <: CdbTargetedMessage[K]](
    payloadHardType: HardType[T],
    val numInputs: Int, 
    val numOutputs: Int,
    arbiterId: String = "CdbArbiter",
    enableLog: Boolean = false
) extends Component {
  require(numInputs >= 0, "Number of inputs cannot be negative.")
  require(numOutputs > 0, "Number of outputs must be positive.")

  val io = new Bundle {
    val inputs = Vec(slave Stream (payloadHardType()), numInputs)
    val outputs = Vec(master Stream (payloadHardType()), numOutputs)
  }

  if (numInputs == 0) {
    io.outputs.foreach { outStream =>
      outStream.valid := False
      outStream.payload.assignDontCare()
    }
  } else if (numInputs == 1) {
    // --- SPECIAL CASE: numInputs == 1 ---
    // (Same as previous version, which passed 1x1 tests)
    val input0_valid = io.inputs(0).valid
    val input0_payload = io.inputs(0).payload
    var input0_granted_to_a_port_this_cycle = False 

    val outputPortIndexWidth = if (numOutputs == 1) 0 else log2Up(numOutputs)
    val mapElementSIntWidth = Math.max(1, outputPortIndexWidth + 1) 
    val grantedInputToOutputPortMap_single = Vec(SInt(mapElementSIntWidth bits), 1) 
    grantedInputToOutputPortMap_single(0) := S(BigInt(-1), mapElementSIntWidth bits)

    io.outputs.foreach(_.valid := False) 
    io.outputs.foreach(_.payload.assignDontCare())

    for (portIdx_p <- 0 until numOutputs) {
      // For 1-input, grant if input is valid and not yet granted, AND output is ready
      when(input0_valid && !input0_granted_to_a_port_this_cycle && io.outputs(portIdx_p).ready) { 
        io.outputs(portIdx_p).valid := True
        io.outputs(portIdx_p).payload := input0_payload
        input0_granted_to_a_port_this_cycle = True 
        grantedInputToOutputPortMap_single(0) := S(BigInt(portIdx_p), mapElementSIntWidth bits)

        if (enableLog) {
            val pLoad = input0_payload.cdbTargetIdx // Safe as input0_valid is true here
            report(L"${arbiterId} Port[${portIdx_p.toString()}] (1-Input Case) GRANTS Input[0] (robPtr=${pLoad})")
        }
      }
    }

    io.inputs(0).ready := False
    when(input0_granted_to_a_port_this_cycle) { // If a grant attempt was made
        when(grantedInputToOutputPortMap_single(0) =/= S(BigInt(-1), mapElementSIntWidth bits)){
            val assignedPortIdx = grantedInputToOutputPortMap_single(0).asUInt.resize(outputPortIndexWidth bits)
            // Input is ready if the specific output it was granted to is actually ready now (and thus fires)
            when(io.outputs(assignedPortIdx).ready){ 
                 io.inputs(0).ready := True
            }
        }
    }

    if (enableLog) {
        val anyGrant_log = input0_granted_to_a_port_this_cycle
        val anyFire_log = io.outputs.map(_.fire).orR
        
        report(L"${arbiterId} Cycle End (1-Input Case): grantsMade=${anyGrant_log}, fires=${anyFire_log}")
        // Potentially access payload only if valid for log
        when(input0_valid || grantedInputToOutputPortMap_single(0) =/= S(BigInt(-1), mapElementSIntWidth bits)){
             val robPtr_log = Mux(input0_valid, input0_payload.cdbTargetIdx, U(0)) // Default for log if not valid
             val mapVal_log = grantedInputToOutputPortMap_single(0)
             val inputReady_log = io.inputs(0).ready
             report(L"${arbiterId}: InputState[0] valid=${input0_valid}, reqRobPtr=${robPtr_log}, gToOutP=${mapVal_log}, inputRdy=${inputReady_log}")
        }
        for(p_s <- 0 until numOutputs){
             val outValid_log = io.outputs(p_s).valid
             val outReady_log = io.outputs(p_s).ready
             report(L"${arbiterId} PortState[${p_s.toString()}]: outValid=${outValid_log}, outReady=${outReady_log}")
        }
    }

  } else { // numInputs > 1 (General case)
    val inputRequestsValid = Vec(io.inputs.map(_.valid))
    val inputRequestsPayload = Vec(io.inputs.map(_.payload))

    val rrInputPtrWidth = log2Up(numInputs) 
    val rrInputPtr = Reg(UInt(rrInputPtrWidth bits)) init (U(0, rrInputPtrWidth bits)) 
    
    val outputPortIndexWidth = if (numOutputs == 1) 0 else log2Up(numOutputs)
    val mapElementSIntWidth = Math.max(1, outputPortIndexWidth + 1) 
    
    val grantedInputToOutputPortMap = Vec(SInt(mapElementSIntWidth bits), numInputs)
    grantedInputToOutputPortMap.foreach(_ := S(BigInt(-1), mapElementSIntWidth bits))

    val outputPortMakesGrant = Vec(Bool(), numOutputs)
    outputPortMakesGrant.foreach(_ := False)

    val grantedInputIndexByPort = Vec(UInt(rrInputPtrWidth bits), numOutputs) 
    grantedInputIndexByPort.foreach(_ := U(0, rrInputPtrWidth bits)) 

    io.outputs.foreach { out =>
      out.valid := False
      out.payload.assignDontCare()
    }

    val scanHeadForPortP = Vec(UInt(rrInputPtrWidth bits), numOutputs) 
    val inputAvailableForPortP = Vec.tabulate(numOutputs) { _ => Vec(Bool(), numInputs) }
    
    val scanOffsetWidth = log2Up(numInputs)


    if(enableLog) {
        report(L"${arbiterId} Cycle Start: rrInputPtr=${rrInputPtr}")
    }

    for (portIdx_p <- 0 until numOutputs) {
      if (portIdx_p == 0) {
        scanHeadForPortP(0) := rrInputPtr
      } else {
        when(outputPortMakesGrant(portIdx_p - 1)) {
          scanHeadForPortP(portIdx_p) := (grantedInputIndexByPort(portIdx_p - 1) + U(1, 1 bit)).resize(rrInputPtrWidth)
        } otherwise {
          scanHeadForPortP(portIdx_p) := scanHeadForPortP(portIdx_p - 1)
        }
      }

      if(enableLog && numOutputs > 0) { 
          val scanHeadVal = scanHeadForPortP(portIdx_p) 
          report(L"${arbiterId} Port[${portIdx_p.toString()}]: scanHead=${scanHeadVal}")
      }

      for (inputIdx_k <- 0 until numInputs) {
        if (portIdx_p == 0) {
          inputAvailableForPortP(0)(inputIdx_k) := True
        } else {
          val isGrantedByLowerPort = Bool()
          isGrantedByLowerPort := False 
          for (prevPort_q <- 0 until portIdx_p) {
            when(outputPortMakesGrant(prevPort_q) && (grantedInputIndexByPort(prevPort_q) === U(inputIdx_k, rrInputPtrWidth bits))) {
              isGrantedByLowerPort := True
            }
          }
          inputAvailableForPortP(portIdx_p)(inputIdx_k) := !isGrantedByLowerPort
        }
      }
      
      val foundGrantInScan = Vec(Bool(), numInputs + 1) 
      foundGrantInScan(0) := False

      for (scanOffset <- 0 until numInputs) {
        val currentScanOffset_hw = U(scanOffset, scanOffsetWidth bits)
        val candidateInput = (scanHeadForPortP(portIdx_p) + currentScanOffset_hw).resize(rrInputPtrWidth)
        
        // -- MODIFICATION: Make canGrantThisSlot a wire for clarity and ensure its components are stable for the when block --
        val currentInputReqValid = inputRequestsValid(candidateInput)
        val currentInputAvailable = inputAvailableForPortP(portIdx_p)(candidateInput)
        val notYetFoundGrantInThisPortScan = !foundGrantInScan(scanOffset)

        val canGrantThisSlot = currentInputReqValid && currentInputAvailable && notYetFoundGrantInThisPortScan
        
        if(enableLog && numInputs > 0){ 
            // Log all conditions for canGrantThisSlot regardless of inputRequestsValid(candidateInput)
            // to see why a grant might not happen for an expected candidate.
            val robPtrVal = Mux(currentInputReqValid, inputRequestsPayload(candidateInput).cdbTargetIdx, U(0)) // Default for log
            report(L"${arbiterId} Port[${portIdx_p.toString()}] ScanOff[${scanOffset.toString()}] CandIn[${candidateInput}] robPtr=${robPtrVal}: reqV=${currentInputReqValid}, avail=${currentInputAvailable}, !found=${notYetFoundGrantInThisPortScan} => canG=${canGrantThisSlot}")
        }
        
        when(canGrantThisSlot) {
          io.outputs(portIdx_p).valid := True
          io.outputs(portIdx_p).payload := inputRequestsPayload(candidateInput)
          outputPortMakesGrant(portIdx_p) := True
          grantedInputIndexByPort(portIdx_p) := candidateInput
          if(enableLog){
              val robPtrGranted = inputRequestsPayload(candidateInput).cdbTargetIdx // Safe, canGrantThisSlot is true
              report(L"${arbiterId} Port[${portIdx_p.toString()}] GRANTS to Input[${candidateInput}] (robPtr=${robPtrGranted})")
          }
        }
        foundGrantInScan(scanOffset + 1) := foundGrantInScan(scanOffset) || canGrantThisSlot
      }
    }

    // ... (rest of the code is the same as your last version)
    for(input_k <- 0 until numInputs){
        for(port_q <- 0 until numOutputs){
            when(outputPortMakesGrant(port_q) && (grantedInputIndexByPort(port_q) === U(input_k, rrInputPtrWidth bits))){
                grantedInputToOutputPortMap(input_k) := S(BigInt(port_q), mapElementSIntWidth bits)
            }
        }
    }
        
    val nextCycle_rrInputPtr_RegInput = UInt(rrInputPtrWidth bits) 
    val anyGrantMadeOverall = outputPortMakesGrant.orR

    nextCycle_rrInputPtr_RegInput := rrInputPtr 

    val lastPortIdx = numOutputs - 1 
    when(outputPortMakesGrant(lastPortIdx)) {
        nextCycle_rrInputPtr_RegInput := (grantedInputIndexByPort(lastPortIdx) + U(1,1 bit)).resize(rrInputPtrWidth)
    } otherwise {
        nextCycle_rrInputPtr_RegInput := scanHeadForPortP(lastPortIdx)
    }
    
    when(!anyGrantMadeOverall) { 
      if (numInputs > 1) { 
          when(inputRequestsValid.orR) { 
            nextCycle_rrInputPtr_RegInput := (rrInputPtr + U(1,1 bit)).resize(rrInputPtrWidth)
          } otherwise { 
            nextCycle_rrInputPtr_RegInput := rrInputPtr
          }
      } else { 
          nextCycle_rrInputPtr_RegInput := rrInputPtr 
      }
    }
    rrInputPtr := nextCycle_rrInputPtr_RegInput

    for (inputIdx <- 0 until numInputs) {
      io.inputs(inputIdx).ready := False
      when(grantedInputToOutputPortMap(inputIdx) =/= S(BigInt(-1), mapElementSIntWidth bits)) {
        val assignedOutputPortIdx_SIntValue = grantedInputToOutputPortMap(inputIdx)
        val assignedOutputPortIdx_UIntValue_Full = assignedOutputPortIdx_SIntValue.asUInt 
        val actualOutputIdx_forVecAccess = assignedOutputPortIdx_UIntValue_Full.resize(outputPortIndexWidth bits)
        
        when(io.outputs(actualOutputIdx_forVecAccess).fire) {
          io.inputs(inputIdx).ready := True
        }
      }
    }

    if (enableLog) {
      for (outputIdx_s <- 0 until numOutputs) { 
        when(io.outputs(outputIdx_s).fire) {
          val payloadVal = io.outputs(outputIdx_s).payload 
          val targetIdxAny = payloadVal.cdbTargetIdx
          report(L"${arbiterId}: Output[${outputIdx_s.toString()}] FIRES. Payload robPtr: ${targetIdxAny}")
        } elsewhen(io.outputs(outputIdx_s).valid) {
             val payloadValStall = io.outputs(outputIdx_s).payload
             val targetIdxAnyStall = payloadValStall.cdbTargetIdx
             report(L"${arbiterId}: Output[${outputIdx_s.toString()}] VALID STALLED. Payload robPtr: ${targetIdxAnyStall}")
        }
      }
      val grantsMadeCount_forLog = CountOne(outputPortMakesGrant)
      val actualFires_forLog = CountOne(io.outputs.map(_.fire))
      
      val curRrPtr_log = rrInputPtr
      val nextPtrVal_log = nextCycle_rrInputPtr_RegInput
      val anyReqValid_log = inputRequestsValid.orR
      val anyOutFire_log = io.outputs.map(_.fire).orR
      val anyOutValid_log = io.outputs.map(_.valid).orR
      
      when(anyReqValid_log || anyOutFire_log || anyOutValid_log) { // More robust condition for logging Cycle End
          report(L"${arbiterId}: Cycle End: curRRInPtr=${curRrPtr_log}, nextRRInPtr=${nextPtrVal_log}, grantsAttempted=${grantsMadeCount_forLog}, grantsFired=${actualFires_forLog}")
          for(i_s <- 0 until numInputs) { 
              val isValid_log = inputRequestsValid(i_s)
              val isMapped_log = grantedInputToOutputPortMap(i_s) =/= S(BigInt(-1), mapElementSIntWidth bits)
              when(isValid_log || isMapped_log) { 
                  val robPtrReq_log = Mux(isValid_log, inputRequestsPayload(i_s).cdbTargetIdx, U(0))
                  val grantedTo_log = grantedInputToOutputPortMap(i_s)
                  val readyVal_log = io.inputs(i_s).ready
                  report(L"${arbiterId}: InputState[${i_s.toString()}] valid=${isValid_log}, reqRobPtr=${robPtrReq_log}, gToOutP=${grantedTo_log}, inputRdy=${readyVal_log}")
              }
          }
           for(p_s <- 0 until numOutputs){ 
               val grantedInForPort_log = grantedInputIndexByPort(p_s) 
               val outValidVal_log = io.outputs(p_s).valid
               val outReadyVal_log = io.outputs(p_s).ready
               val makesGrant_log = outputPortMakesGrant(p_s)
               report(L"${arbiterId} PortState[${p_s.toString()}]: makesGrant=${makesGrant_log}, grantedInIdx=${grantedInForPort_log}, outValid=${outValidVal_log}, outReady=${outReadyVal_log}")
           }
      }
    }
  } 
}
