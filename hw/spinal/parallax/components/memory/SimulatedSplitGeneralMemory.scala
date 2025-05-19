// hw/spinal/parallax/components/memory/SimulatedGeneralMemory.scala
package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SimulatedSplitGeneralMemory(
    val memConfig: SimulatedMemoryConfig,
    val busConfig: GenericMemoryBusConfig,
    val enableLog: Boolean = true
) extends Component {

  require(memConfig.internalWordCount > 0, "SimulatedGeneralMemory internalWordCount must be greater than 0.")

  val io = new Bundle {
    val bus = slave(SplitGenericMemoryBus(busConfig)) // Updated bus type
    val writeEnable = in Bool () default (False) // For external direct writes (e.g., init)
    val writeAddress = in UInt (busConfig.addressWidth) default (U(0, busConfig.addressWidth))
    val writeData = in Bits (memConfig.internalDataWidth) default (B(0, memConfig.internalDataWidth))
  }

  val internalBytesPerBusData: Int = busConfig.dataWidth.value / 8

  require(
    busConfig.dataWidth.value % memConfig.internalDataWidth.value == 0,
    "Bus data width must be a multiple of internal memory data width."
  )
  val numChunksPerWord: Int = busConfig.dataWidth.value / memConfig.internalDataWidth.value

  val mem = Mem(Bits(memConfig.internalDataWidth), wordCount = memConfig.internalWordCount)

  // Direct memory write port (e.g., for initialization from testbench)
  when(io.writeEnable) {
    val internalWriteWordAddress =
      (io.writeAddress >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
    when(internalWriteWordAddress <= memConfig.internalWordMaxAddr) {
      if (enableLog) {
        report(L"[SimGenMem] TB_WRITE: Addr=${internalWriteWordAddress}, Data=${io.writeData}")
      }
      mem.write(address = internalWriteWordAddress, data = io.writeData)
    } otherwise {
      if (enableLog) {
        report(L"[SimGenMem] TB_WRITE_OOB: Addr=${internalWriteWordAddress}")
      }
    }
  }

  // Registers to hold current transaction details
  val currentBusAddressReg = Reg(UInt(busConfig.addressWidth)) init (U(0, busConfig.addressWidth))
  val currentWriteDataReg = Reg(Bits(busConfig.dataWidth)) init (B(0, busConfig.dataWidth)) // Only for writes
  val currentByteEnablesReg =
    Reg(Bits(busConfig.dataWidth.value / 8 bits)) init (B(0, busConfig.dataWidth.value / 8 bits)) // Only for writes
  val currentIdReg = if (busConfig.useId) Reg(UInt(busConfig.idWidth)) init (U(0, busConfig.idWidth)) else null

  val isWriteOperationReg = Reg(Bool()) init (False) // True if current op is write, False for read

  val latencyCounterReg =
    if (memConfig.initialLatency > 0) { // Use >0 to allow zero latency
      Reg(UInt(log2Up(memConfig.initialLatency + 1) bits)) init (U(0, log2Up(memConfig.initialLatency + 1) bits))
    } else null

  // Calculate unresized base internal word address
  val baseInternalWordAddr_unresized = currentBusAddressReg >> log2Up(memConfig.internalDataWidthBytes)

  val internalReadData = Bits(memConfig.internalDataWidth) // Data read from one internal memory word

  val partCounterReg: UInt = if (numChunksPerWord > 1) {
    Reg(UInt(log2Up(numChunksPerWord) bits)) init (U(0, log2Up(numChunksPerWord) bits))
  } else {
    U(0, 1 bits) // Still useful to have a 1-bit 0 for consistency in logic
  }

  val assemblyBufferReg: Bits = if (numChunksPerWord > 1) {
    Reg(Bits(busConfig.dataWidth)) init (B(0, busConfig.dataWidth))
  } else {
    null // No buffer needed if bus data width == internal data width
  }

  val dataErrorForRspReg = Reg(Bool()) init (False)

  val sm = new StateMachine {

    // Default assignments for outputs
    io.bus.read.cmd.ready := False
    io.bus.read.rsp.valid := False
    io.bus.read.rsp.payload.data := B(0, busConfig.dataWidth)
    io.bus.read.rsp.payload.error := False
    if (busConfig.useId) io.bus.read.rsp.payload.id := U(0)

    io.bus.write.cmd.ready := False
    io.bus.write.rsp.valid := False
    io.bus.write.rsp.payload.error := False
    if (busConfig.useId) io.bus.write.rsp.payload.id := U(0)

    val sIdle: State = new State with EntryPoint {
      whenIsActive {
        // Simple arbitration: Read has priority over Write if both are valid.
        // A real memory might have a more complex arbiter.
        when(io.bus.read.cmd.valid) {
          io.bus.read.cmd.ready := True
          when(io.bus.read.cmd.fire) {
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sIdle: READ CMD Fire! Addr=${io.bus.read.cmd.address} ID=${if (busConfig.useId) io.bus.read.cmd.id
                  else U(0).setName("0")}"
              )
            }
            currentBusAddressReg := io.bus.read.cmd.address
            if (busConfig.useId) currentIdReg := io.bus.read.cmd.id
            isWriteOperationReg := False

            if (numChunksPerWord > 1) partCounterReg := U(0)
            if (assemblyBufferReg != null) assemblyBufferReg.clearAll() // Clear for new read
            if (memConfig.initialLatency > 0) latencyCounterReg := U(0)
            dataErrorForRspReg := False
            goto(sProcessInternal)
          }
        } otherwise when(io.bus.write.cmd.valid) {
          io.bus.write.cmd.ready := True
          when(io.bus.write.cmd.fire) {
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sIdle: WRITE CMD Fire! Addr=${io.bus.write.cmd.address}, Data=${io.bus.write.cmd.data}, BE=${io.bus.write.cmd.byteEnables} ID=${if (busConfig.useId)
                    io.bus.write.cmd.id
                  else U(0).setName("0")}"
              )
            }
            currentBusAddressReg := io.bus.write.cmd.address
            currentWriteDataReg := io.bus.write.cmd.data
            currentByteEnablesReg := io.bus.write.cmd.byteEnables
            if (busConfig.useId) currentIdReg := io.bus.write.cmd.id
            isWriteOperationReg := True

            if (numChunksPerWord > 1) partCounterReg := U(0)
            // assemblyBufferReg not used for writes
            if (memConfig.initialLatency > 0) latencyCounterReg := U(0)
            dataErrorForRspReg := False
            goto(sProcessInternal)
          }
        }
      }
    }

    val sProcessInternal: State = new State {
      whenIsActive {
        // --- Latency Handling ---
        if (memConfig.initialLatency > 0) {
          when(latencyCounterReg < memConfig.initialLatency) {
            latencyCounterReg := latencyCounterReg + 1
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sProcessInternal: Stalling for latency. New LatencyCtr=${latencyCounterReg} + 1"
              )
            }
            // Stay in this state, do nothing else until latency is met
            // The when(cond) below will handle the actual processing
          }
        }

        // Condition to proceed after latency
        val canProcessChunk = if (memConfig.initialLatency > 0) latencyCounterReg === memConfig.initialLatency else True

        when(canProcessChunk) {
          val chunkId = if (numChunksPerWord > 1) partCounterReg else U(0) // Ensure it has a name for debug
          // Use unresized address for calculations and comparisons
          val wordAddr_fullwidth = baseInternalWordAddr_unresized + chunkId

          val baseAddressOutOfBounds = currentBusAddressReg >= U(memConfig.memSize, busConfig.addressWidth)
          // Compare full-width calculated word address with max valid internal word address
          val chunkInternalWordAddrValid = wordAddr_fullwidth <= memConfig.internalWordMaxAddr

          if (enableLog) { // Log with fullwidth address
            report(
              L"[SimGenMem FSM] sProcessInternal (after latency): OpIsWrite=${isWriteOperationReg}, LatencyCtr=${if (memConfig.initialLatency > 0) latencyCounterReg
                else U(0).setName("0")}, PartCtr=chunkId, CurrBusAddrReg=${currentBusAddressReg}, BaseInternalAddr=${baseInternalWordAddr_unresized}, CurrProcInternalAddr=${wordAddr_fullwidth}"
            )
            report(
              L"  BaseOOB=${baseAddressOutOfBounds}, ValidInternalChunkAddr=${chunkInternalWordAddrValid}, internalReadData_val_for_this_chunk=${internalReadData}"
            )
          }

          val isCurrentChunkOOB = baseAddressOutOfBounds || !chunkInternalWordAddrValid
          when(isCurrentChunkOOB && !dataErrorForRspReg) {
            dataErrorForRspReg := True
            if (enableLog)
              report(
                L"[SimGenMem FSM] Error latched for transaction. BaseOOB=${baseAddressOutOfBounds}, ChunkInternalAddrInvalid=${!chunkInternalWordAddrValid}"
              )
          }

          // --- Write Operation Logic ---
          when(isWriteOperationReg) {
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sProcessInternal: Processing WRITE chunk chunkId for InternalAddr=${wordAddr_fullwidth}"
              )
            }
            when(!isCurrentChunkOOB) { // Use isCurrentChunkOOB
              val dataFromBusForThisInternalWord =
                currentWriteDataReg.subdivideIn(memConfig.internalDataWidth).apply(chunkId)
              val bytesPerInternalWord = memConfig.internalDataWidth.value / 8
              val enablesForSlice =
                currentByteEnablesReg.subdivideIn(bytesPerInternalWord bits).apply(chunkId)

              if (enableLog) {
                report(
                  L"  dataFromBusInternalChunk=${dataFromBusForThisInternalWord}, enablesForInternalChunk=${enablesForSlice}"
                )
              }

              // RMW (Read-Modify-Write) or direct write logic
              // internalReadData provides the old data for RMW for the current 'wordAddr'
              if (memConfig.internalDataWidth.value == 8) { // Byte-addressable internal memory
                when(enablesForSlice(0)) {
                  mem.write(
                    address = wordAddr_fullwidth.resize(memConfig.internalAddrWidth), // Resize only for Mem access
                    data = dataFromBusForThisInternalWord
                  )
                  if (enableLog)
                    report(
                      L"  MEM_WRITE (byte): InternalAddr=${wordAddr_fullwidth}, Data=${dataFromBusForThisInternalWord}"
                    )
                }
              } else { // Multi-byte internal words
                val allBytesEnabled = enablesForSlice.andR
                when(allBytesEnabled) { // All byte enables for this internal word are set
                  mem.write(
                    address = wordAddr_fullwidth.resize(memConfig.internalAddrWidth), // Resize only for Mem access
                    data = dataFromBusForThisInternalWord
                  )
                  if (enableLog)
                    report(
                      L"  MEM_WRITE (full internal word): InternalAddr=${wordAddr_fullwidth}, Data=${dataFromBusForThisInternalWord}"
                    )
                } otherwise { // Partial write, requires RMW
                  val oldDataForThisInternalWord = internalReadData // From mem.readAsync targeting 'wordAddr'
                  val mergedWord = Bits(memConfig.internalDataWidth)
                  val bytesOfOldData = oldDataForThisInternalWord.subdivideIn(8 bits)
                  val bytesOfNewDataFromBus = dataFromBusForThisInternalWord.subdivideIn(8 bits)
                  val individualByteEnablesVec = enablesForSlice.asBools

                  if (enableLog) {
                    report(
                      L"  RMW: oldDataInternal=${oldDataForThisInternalWord}, newDataFromBusInternal=${dataFromBusForThisInternalWord}, enablesForSlice=${enablesForSlice}"
                    )
                  }

                  for (i <- 0 until bytesPerInternalWord) {
                    when(individualByteEnablesVec(i)) {
                      mergedWord((i * 8) until (i * 8 + 8)) := bytesOfNewDataFromBus(i)
                    } otherwise {
                      mergedWord((i * 8) until (i * 8 + 8)) := bytesOfOldData(i)
                    }
                  }
                  mem.write(
                    address = wordAddr_fullwidth.resize(memConfig.internalAddrWidth), // Resize only for Mem access
                    data = mergedWord
                  )
                  if (enableLog)
                    report(L"  MEM_WRITE (RMW result): InternalAddr=${wordAddr_fullwidth}, Data=${mergedWord}")
                }
              }
            } otherwise { // Write suppressed due to error
              if (enableLog)
                report(L"[SimGenMem FSM] sProcessInternal: WRITE suppressed for chunk chunkId due to error.")
            }
          } // End of isWriteOperationReg

          // --- Read Operation Logic & Data Assembly ---
          val assembledDataForOutput = Bits(busConfig.dataWidth) // Will hold the full bus-width data for read response
          assembledDataForOutput.assignDontCare() // Default

          when(!isWriteOperationReg) { // This is a READ operation
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sProcessInternal: Processing READ chunk chunkId for InternalAddr=${wordAddr_fullwidth}"
              )
            }
            val dataToAssemble = Bits(memConfig.internalDataWidth)
            when(!isCurrentChunkOOB) { // Use isCurrentChunkOOB
              dataToAssemble := internalReadData // internalReadData should be from mem.readAsync(wordAddr_fullwidth.resize)
              if (enableLog)
                report(L"  READ_CHUNK: Valid access, dataToAssemble (from internalReadData)=${internalReadData}")
            } otherwise {
              dataToAssemble.clearAll() // Return zeros for errored read chunk
              if (enableLog) report(L"  READ_CHUNK: OOB/Invalid access for this chunk, dataToAssemble=0")
            }

            if (numChunksPerWord == 1) {
              assembledDataForOutput := dataToAssemble.resized
            } else {
              // assemblyBufferReg holds previously read chunks
              val nextAssemblyBuffer = CombInit(assemblyBufferReg) // Start with current buffer content
              nextAssemblyBuffer.subdivideIn(memConfig.internalDataWidth).apply(chunkId) := dataToAssemble
              assemblyBufferReg := nextAssemblyBuffer // Update register for next cycle (if more chunks)
              assembledDataForOutput := nextAssemblyBuffer // Output the fully (or partially) assembled data
              if (enableLog) {
                report(
                  L"  READ_ASSEMBLE (multi-chunk part chunkId): dataToAssembleIntoSlice=${dataToAssemble}, assemblyBufferReg_In=${assemblyBufferReg}, assemblyBufferReg_Out(nextCycle)=${nextAssemblyBuffer}, assembledDataForOutputThisCycle=${assembledDataForOutput}"
                )
              }
            }
          } // End of !isWriteOperationReg (READ operation)

          // --- State Transition & Response ---
          if (memConfig.initialLatency > 0) latencyCounterReg := U(0) // Reset for next chunk/op
          val isLastPart = if (numChunksPerWord > 1) partCounterReg === (numChunksPerWord - 1) else True

          if (enableLog) report(L"[SimGenMem FSM] sProcessInternal: Chunk processed. isLastPart=${isLastPart}")

          when(isLastPart) {
            val finalErrorSignal =
              dataErrorForRspReg || isCurrentChunkOOB // Use the latched error status for the whole transaction
            when(isWriteOperationReg) {
              io.bus.write.rsp.valid := True
              io.bus.write.rsp.payload.error := finalErrorSignal
              if (busConfig.useId) io.bus.write.rsp.payload.id := currentIdReg

              if (enableLog) {
                when(io.bus.write.rsp.fire) {
                  report(
                    L"[SimGenMem FSM] sProcessInternal: RSP Fire! To sIdle. ReadDataOut=${assembledDataForOutput}, ErrorOut=${finalErrorSignal}"
                  )
                }
              }
              when(io.bus.write.rsp.fire) {
                goto(sIdle)
              }
            } otherwise { // Read operation response
              io.bus.read.rsp.valid := True
              // For reads, data is zero if there was an error on any chunk
              io.bus.read.rsp.payload.data := Mux(finalErrorSignal, B(0), assembledDataForOutput)
              io.bus.read.rsp.payload.error := finalErrorSignal
              if (busConfig.useId) io.bus.read.rsp.payload.id := currentIdReg

              if (enableLog) {
                when(io.bus.read.rsp.fire) {
                  report(
                    L"[SimGenMem FSM] sProcessInternal: READ RSP Fire! To sIdle. ReadDataOut=${io.bus.read.rsp.payload.data}, ErrorOut=${finalErrorSignal}, ID=${if (busConfig.useId)
                        io.bus.read.rsp.payload.id
                      else U(0).setName("0")}"
                  )
                }
              }
              when(io.bus.read.rsp.fire) {
                goto(sIdle)
              }
            }
          } otherwise { // Not the last part, increment partCounter
            if (numChunksPerWord > 1) { // Should always be true if !isLastPart and numChunks > 1
              partCounterReg := partCounterReg + U(1) // U(1) will be correctly sized
              if (enableLog)
                report(
                  L"[SimGenMem FSM] sProcessInternal: Advancing to next part. New partCounterReg=${partCounterReg} + 1"
                )
            }
            // Stay in sProcessInternal for the next chunk
          }
        } // End of when(canProcessChunk)
      } // End of whenIsActive for sProcessInternal
    } // End of sProcessInternal state
  } // End of StateMachine

  // --- Asynchronous Read Logic from Mem for the current chunk ---
  val addrForAsyncRead_unresized = baseInternalWordAddr_unresized + (if (numChunksPerWord > 1) partCounterReg else U(0))
  val addrValidForAsyncRead_comb_preResize = addrForAsyncRead_unresized <= memConfig.internalWordMaxAddr

  val doAsyncRead = sm.isActive(sm.sProcessInternal) &&
    (if (memConfig.initialLatency > 0) latencyCounterReg === memConfig.initialLatency else True) &&
    addrValidForAsyncRead_comb_preResize // Check validity before resize

  when(doAsyncRead) {
    internalReadData := mem.readAsync(address =
      addrForAsyncRead_unresized.resize(memConfig.internalAddrWidth)
    ) // Resize for Mem access
    if (enableLog) {
      // This log can be very verbose, enable with caution or make it conditional
      // report(L"[SimGenMem AsyncRead] Active: Addr=${addrForAsyncRead_unresized}, ReadData=${internalReadData} (next cycle)")
    }
  } otherwise {
    internalReadData := B(0) // Default, or hold previous value if mem.readAsync has that behavior
  }
}
