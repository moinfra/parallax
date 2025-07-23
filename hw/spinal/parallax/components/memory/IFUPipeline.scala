package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.common.PipelineConfig
import parallax.components.dcache2.{DataCacheParameters, DataLoadPort, DataLoadCmd}
import parallax.fetch.InstructionPredecoder
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.fetch

// Case classes, Config, and Port definitions are reused and should be in their own files
// or at the top of the package's scope. Assuming they are accessible here.

// ========================================================================
//  High-Performance Pipelined InstructionFetchUnit
// ========================================================================
class InstructionFetchUnitPipelined(val config: InstructionFetchUnitConfig) extends Component {

  val io = new IFUIO(config)
  val enableLog = false // Set to true for debugging

  // --- Registers and Buffers ---
  val currentPc = Reg(UInt(config.pcWidth))
  val receivedChunksBuffer = Reg(Vec(Bits(config.xlen bits), config.chunksPerFetchGroup))
  val chunksReceivedMask = Reg(Bits(config.chunksPerFetchGroup bits))
  val faultOccurred = Reg(Bool())

  // --- In-flight Request Tracking ---
  // We need to know which chunk corresponds to which transaction ID.
  // The size of this array must be 2^transactionIdWidth to handle all possible IDs.
  require(isPow2(1 << config.transactionIdWidth), "This design assumes power-of-2 inflight capacity")
  val inflightChunkMap = Mem(UInt(log2Up(config.chunksPerFetchGroup) bits), 1 << config.transactionIdWidth)
  val inflightRequestValid = Reg(Bits(1 << config.transactionIdWidth bits)) init (0)

  // A counter to assign a new transaction ID for each request.
  val dcacheTransIdCounter = Counter(1 << config.transactionIdWidth, inc = io.dcacheLoadPort.cmd.fire)
  
  // --- Predecoders and Data Assembly (same as before) ---
  val predecoders = Seq.fill(config.instructionsPerFetchGroup)(new InstructionPredecoder(config.pCfg))
  val assembledData = Cat(receivedChunksBuffer.reverse)
  val rawInstructions = assembledData.subdivideIn(config.instructionWidth).reverse

  for (i <- 0 until config.instructionsPerFetchGroup) {
    predecoders(i).io.instruction := rawInstructions(i)
  }
  
  // --- Valid Mask Generation (same as before) ---
  val groupBasePc = currentPc(config.pcWidth.value - 1 downto log2Up(config.bytesPerFetchGroup)) ## U(0, log2Up(config.bytesPerFetchGroup) bits)
  val pcInGroupOffset = currentPc(log2Up(config.bytesPerFetchGroup) - 1 downto log2Up(config.bytesPerInstruction))
  val alignmentMask = Bits(config.instructionsPerFetchGroup bits)
  for (i <- 0 until config.instructionsPerFetchGroup) {
      alignmentMask(i) := U(i) >= pcInGroupOffset
  }
  val finalValidMask = Mux(faultOccurred, B(0), alignmentMask)

    // A mask of chunks for which a request has already been sent.
    val chunksRequestedMask = Reg(Bits(config.chunksPerFetchGroup bits))
  // --- FSM ---
  val fsm = new StateMachine {
    io.cpuPort.cmd.ready := False
    io.cpuPort.rsp.valid := False
    io.cpuPort.rsp.payload.assignDontCare()
    io.dcacheLoadPort.cmd.valid := False
    io.dcacheLoadPort.cmd.payload.assignDontCare()
    io.dcacheLoadPort.translated.physical := io.dcacheLoadPort.cmd.payload.virtual
    io.dcacheLoadPort.translated.abord := False
    io.dcacheLoadPort.cancels.clearAll()
    

    val IDLE: State = new State with EntryPoint {
      onEntry {
        chunksReceivedMask.clearAll()
        chunksRequestedMask.clearAll()
        faultOccurred := False
        // On flush or idle entry, we must invalidate all pending requests.
        inflightRequestValid.clearAll()
      }
      whenIsActive {
        // Ready to accept a new command if no requests from a previous transaction are pending.
        io.cpuPort.cmd.ready := (inflightRequestValid === 0) && !io.cpuPort.flush
        when(io.cpuPort.cmd.fire) {
          currentPc := io.cpuPort.cmd.pc
          goto(FETCHING)
        }
      }
      onExit {
        // This ensures the counter starts from a known state for the new fetch group.
        dcacheTransIdCounter.clear()
      }
    }

    val FETCHING: State = new State {
      val allChunksRequested = chunksRequestedMask.andR
      val allChunksReceived = chunksReceivedMask.andR

      whenIsActive {
        when(io.cpuPort.flush) {
          goto(IDLE)
        } otherwise {
          // If all chunks have been received, we are done.
          when(allChunksReceived) {
            goto(RESPONDING)
          }

          // If not all chunks have been requested yet, and DCache is ready, send the next request.
          // This is the core of the pipelined logic. It will try to send a new request each cycle.
          when(!allChunksRequested) {
            io.dcacheLoadPort.cmd.valid := True
            
            val chunkToRequestIdx = OHToUInt(PriorityEncoderOH(~chunksRequestedMask))
            val addressOfChunk = (groupBasePc).asUInt + (chunkToRequestIdx * config.bytesPerXlenChunk).resize(config.pcWidth)

            val cmdPayload = DataLoadCmd(config.pcWidth.value, config.xlen, config.transactionIdWidth)
            cmdPayload.virtual := addressOfChunk
            cmdPayload.size := log2Up(config.bytesPerXlenChunk)
            cmdPayload.redoOnDataHazard := False
            cmdPayload.id := dcacheTransIdCounter.value.resized
            io.dcacheLoadPort.cmd.payload := cmdPayload

            when(io.dcacheLoadPort.cmd.fire) {
              chunksRequestedMask(chunkToRequestIdx) := True
              inflightRequestValid(dcacheTransIdCounter.value.resized) := True
              inflightChunkMap.write(
                address = dcacheTransIdCounter.value.resized,
                data = chunkToRequestIdx
              )
            }
          }
        }
      }
    }

    val RESPONDING: State = new State {
      whenIsActive {
        when(io.cpuPort.flush) {
          goto(IDLE)
        } otherwise {
          io.cpuPort.rsp.valid := True
          io.cpuPort.rsp.payload.pc := groupBasePc.asUInt
          io.cpuPort.rsp.payload.fault := faultOccurred
          io.cpuPort.rsp.payload.instructions := Vec(rawInstructions)
          for(i <- 0 until config.instructionsPerFetchGroup) {
              io.cpuPort.rsp.payload.predecodeInfo(i) := predecoders(i).io.predecodeInfo
          }
          io.cpuPort.rsp.payload.validMask := finalValidMask

          when(io.cpuPort.rsp.fire) {
            goto(IDLE)
          }
        }
      }
    }
  }
  
  // --- DCache Response Handling ---
  // This logic is now outside the FSM, as responses can arrive at any time.
  when(io.dcacheLoadPort.rsp.valid) {
  val rspId = io.dcacheLoadPort.rsp.id
  // Check if this response corresponds to a valid, in-flight request.
  when(inflightRequestValid(rspId)) {
    // This transaction is now complete, regardless of redo.
    // We clear the valid bit to free up this transaction ID slot.
    inflightRequestValid(rspId) := False 
    
    // Asynchronously read the Mem to find out which chunk this was for.
    // This read will be valid in the same cycle.
    val chunkIndexForThisRsp = inflightChunkMap.readAsync(rspId)

    when(!io.dcacheLoadPort.rsp.redo) {
      // --- Normal Response Path ---
      when(!chunksReceivedMask(chunkIndexForThisRsp)) { // Avoid double-writing if a redo was processed late
        receivedChunksBuffer(chunkIndexForThisRsp) := io.dcacheLoadPort.rsp.data
        chunksReceivedMask(chunkIndexForThisRsp) := True
        faultOccurred := faultOccurred | io.dcacheLoadPort.rsp.fault
        if(enableLog) report(L"[IFU-P] Chunk ${chunkIndexForThisRsp} received for PC 0x${currentPc}")
      }
    } otherwise {
      // --- Redo Response Path ---
      // The request for this chunk failed. We need to re-issue it.
      // We do this by clearing its bit in the 'chunksRequestedMask'.
      // The FSM, while in the FETCHING state, will see this and re-request it.
      
      // IMPORTANT: Ensure this logic only triggers when the FSM is in a state
      // where a re-request makes sense.
      when(fsm.isActive(fsm.FETCHING)) {
        chunksRequestedMask(chunkIndexForThisRsp) := False
        if(enableLog) report(L"[IFU-P] REDO for chunk ${chunkIndexForThisRsp} of PC 0x${currentPc}. Re-queuing request.")
      }
    }
  }
}


  // ... (Optional debug logger) ...
}
