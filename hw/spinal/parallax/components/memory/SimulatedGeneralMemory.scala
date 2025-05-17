// hw/spinal/parallax/components/memory/SimulatedGeneralMemory.scala
package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class SimulatedGeneralMemory(
    val memConfig: SimulatedMemoryConfig,
    val busConfig: GenericMemoryBusConfig,
    val enableLog: Boolean = true
) extends Component {

  require(memConfig.internalWordCount > 0, "SimulatedGeneralMemory internalWordCount must be greater than 0.")

  val io = new Bundle {
    val bus = slave(GenericMemoryBus(busConfig))
    val writeEnable = in Bool () default (False)
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

  when(io.writeEnable) {
    val internalWriteWordAddress =
      (io.writeAddress >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
    when(internalWriteWordAddress.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount) {
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

  val currentBusAddressReg = Reg(UInt(busConfig.addressWidth)) init (U(0, busConfig.addressWidth))
  val currentOpcodeReg = Reg(GenericMemoryBusOpcode()) init (GenericMemoryBusOpcode.OP_READ)
  val currentWriteDataReg = Reg(Bits(busConfig.dataWidth)) init (B(0, busConfig.dataWidth))
  val currentByteEnablesReg =
    Reg(Bits(busConfig.dataWidth.value / 8 bits)) init (B(0, busConfig.dataWidth.value / 8 bits))
  val latencyCounterReg =
    Reg(UInt(log2Up(memConfig.initialLatency + 1) bits)) init (U(0, log2Up(memConfig.initialLatency + 1) bits))

  val baseInternalWordAddr =
    (currentBusAddressReg >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)

  val internalReadData = Bits(memConfig.internalDataWidth)

  val partCounterReg: UInt = if (numChunksPerWord > 1) {
    Reg(UInt(log2Up(numChunksPerWord) bits)) init (U(0, log2Up(numChunksPerWord) bits))
  } else {
    U(0, 1 bits)
  }

  val assemblyBufferReg: Bits = if (numChunksPerWord > 1) {
    Reg(Bits(busConfig.dataWidth)) init (B(0, busConfig.dataWidth))
  } else {
    null
  }

  val dataErrorForRspReg = Reg(Bool()) init (False)

  val sm = new StateMachine {

    io.bus.cmd.ready := False
    io.bus.rsp.valid := False
    io.bus.rsp.payload.readData := B(0, busConfig.dataWidth)
    io.bus.rsp.payload.error := False

    val sIdle: State = new State with EntryPoint {
      whenIsActive {
        io.bus.cmd.ready := True
        when(io.bus.cmd.fire) {

          if (enableLog) {
            report(
              L"""[SimGenMem FSM] sIdle: CMD Fire! Addr=${io.bus.cmd.payload.address}, 
              Op=${io.bus.cmd.payload.opcode}, WrData=${io.bus.cmd.payload.writeData}, 
              BE=${io.bus.cmd.payload.writeByteEnables}"""
            )
          }

          currentBusAddressReg := io.bus.cmd.payload.address
          currentOpcodeReg := io.bus.cmd.payload.opcode
          currentWriteDataReg := io.bus.cmd.payload.writeData
          currentByteEnablesReg := io.bus.cmd.payload.writeByteEnables

          if (numChunksPerWord > 1) {
            partCounterReg := U(0)
            if (assemblyBufferReg != null) assemblyBufferReg := B(0)
          }
          latencyCounterReg := U(0)
          dataErrorForRspReg := False
          goto(sProcessInternal)
        }
      }
    }

    val sProcessInternal: State = new State {

      whenIsActive {
        when(latencyCounterReg < memConfig.initialLatency) {
          latencyCounterReg := latencyCounterReg + 1
          if (enableLog) {
            report(
              L"[SimGenMem FSM] sProcessInternal: Stalling for latency. New LatencyCtr=${latencyCounterReg} + 1"
            )
          }
        } otherwise {
          val chunkId = if (numChunksPerWord > 1) partCounterReg else U(0).setName("0")
          val wordAddr = baseInternalWordAddr + chunkId

          val chunkAddrValid = wordAddr.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount

          val endByteOffsetOfBusTransaction =
            currentBusAddressReg + U(internalBytesPerBusData - 1, busConfig.addressWidth)
          val busTransactionOutOfBounds = endByteOffsetOfBusTransaction >= U(memConfig.memSize, busConfig.addressWidth)

          if (enableLog) {
            report(
              L"[SimGenMem FSM] sProcessInternal (after latency): LatencyCtr=${latencyCounterReg}, PartCtr=${chunkId}, Op=${currentOpcodeReg}, CurrAddrReg=${currentBusAddressReg}, BaseInternalAddr=${baseInternalWordAddr}, CurrProcInternalAddr=${wordAddr}"
            )
            report(
              L"  OOB_BusLevel=${busTransactionOutOfBounds}, ValidInternalAddrChunk=${chunkAddrValid}, internalReadData_val_for_this_chunk=${internalReadData}"
            )
          }

          val effectiveErrorForThisChunk =
            busTransactionOutOfBounds || !chunkAddrValid // Error for current chunk
          when(effectiveErrorForThisChunk && !dataErrorForRspReg) { // Latch error if new for this transaction
            dataErrorForRspReg := True
            if (enableLog)
              report(
                L"[SimGenMem FSM] Error latched for transaction. BusOOB=${busTransactionOutOfBounds}, ChunkInvalid=${!chunkAddrValid}"
              )
          }

          when(currentOpcodeReg === GenericMemoryBusOpcode.OP_WRITE) {
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sProcessInternal: Processing WRITE chunk ${chunkId} for InternalAddr=${wordAddr}"
              )
            }
            when(!effectiveErrorForThisChunk) { // Only process write for this chunk if it's not an error condition
              val dataFromBusForThisInternalWord =
                currentWriteDataReg.subdivideIn(memConfig.internalDataWidth).reverse.apply(chunkId)
              val bytesPerInternalWord = memConfig.internalDataWidth.value / 8
              val enablesForSlice =
                currentByteEnablesReg.subdivideIn(bytesPerInternalWord bits).reverse.apply(chunkId)

              if (enableLog) {
                report(
                  L"  dataFromBusInternalChunk=${dataFromBusForThisInternalWord}, enablesForInternalChunk=${enablesForSlice}"
                )
              }

              if (memConfig.internalDataWidth.value == 8) {
                when(enablesForSlice(0)) {
                  mem.write(
                    address = wordAddr.resize(memConfig.internalAddrWidth),
                    data = dataFromBusForThisInternalWord
                  )
                  if (enableLog) {
                    report(
                      L"  MEM_WRITE (byte): InternalAddr=${wordAddr}, Data=${dataFromBusForThisInternalWord}"
                    )
                  }
                }
              } else {
                val allBytesEnabled = enablesForSlice.andR
                when(allBytesEnabled) {
                  mem.write(
                    address = wordAddr.resize(memConfig.internalAddrWidth),
                    data = dataFromBusForThisInternalWord
                  )
                  if (enableLog) {
                    report(
                      L"  MEM_WRITE (full internal word): InternalAddr=${wordAddr}, Data=${dataFromBusForThisInternalWord}"
                    )
                  }
                } otherwise {
                  val oldDataForThisInternalWord = internalReadData // This is read one cycle prior
                  val mergedWord = Bits(memConfig.internalDataWidth)
      
                  // Use MSB-first indexing consistently
                  val bytesOfOldData = oldDataForThisInternalWord.subdivideIn(8 bits)
                  val bytesOfNewDataFromBus = dataFromBusForThisInternalWord.subdivideIn(8 bits)
                  val individualByteEnablesVec = enablesForSlice.asBools // enablesForSlice is B"0100", asBools gives Vec(F,T,F,F) (MSB enable at index 0)
      
                  if (enableLog) {
                    report(
                      L"  RMW: oldDataInternal=${oldDataForThisInternalWord}, newDataFromBusInternal=${dataFromBusForThisInternalWord}, enablesForSlice=${enablesForSlice}"
                    )
                    for (i_log <- 0 until bytesPerInternalWord) {
                      val reportOld = bytesOfOldData(i_log)
                      val reportNew = bytesOfNewDataFromBus(i_log)
                      val reportEn = individualByteEnablesVec(i_log)
                      report(
                        L"       RMW Byte ${i_log.toString()}: Old=${reportOld}, New=${reportNew}, Enable=${reportEn}"
                      )
                    }
                  }

                  for (i <- 0 until bytesPerInternalWord) {
                    when(individualByteEnablesVec(i)) {
                      mergedWord((i * 8) until (i * 8 + 8)) := bytesOfNewDataFromBus(i)
                    } otherwise {
                      mergedWord((i * 8) until (i * 8 + 8)) := bytesOfOldData(i)
                    }
                  }
                  mem.write(
                    address = wordAddr.resize(memConfig.internalAddrWidth),
                    data = mergedWord
                  )
                  if (enableLog) {
                    report(
                      L"  MEM_WRITE (RMW result): InternalAddr=${wordAddr}, Data=${mergedWord}"
                    )
                  }
                }
              }
            } otherwise {
              if (enableLog) {
                report(
                  L"[SimGenMem FSM] sProcessInternal: WRITE suppressed for chunk ${chunkId} due to error (BusOOB=${busTransactionOutOfBounds}, ChunkAddrInvalid=${!chunkAddrValid})."
                )
              }
            }
          }

          val assembledDataForOutput = Bits(busConfig.dataWidth)
          assembledDataForOutput.assignDontCare()

          when(currentOpcodeReg === GenericMemoryBusOpcode.OP_READ) {
            if (enableLog) {
              report(
                L"[SimGenMem FSM] sProcessInternal: Processing READ chunk ${chunkId} for InternalAddr=${wordAddr}"
              )
            }
            val dataToAssemble = Bits(memConfig.internalDataWidth)
            when(!effectiveErrorForThisChunk) { // Only use internalReadData if this chunk is valid
              dataToAssemble := internalReadData
              if (enableLog) {
                report(
                  L"  READ_CHUNK: Valid access, dataToAssemble (from internalReadData)=${internalReadData}"
                )
              }
            } otherwise {
              dataToAssemble := B(0, memConfig.internalDataWidth)
              if (enableLog) {
                report(L"  READ_CHUNK: OOB/Invalid access for this chunk, dataToAssemble=0")
              }
            }

            if (numChunksPerWord == 1) {
              assembledDataForOutput := dataToAssemble.resized
              if (enableLog) {
                report(
                  L"  READ_ASSEMBLE (single chunk): assembledDataForOutput=${assembledDataForOutput}"
                )
              }
            } else {
              if (assemblyBufferReg != null) {
                val nextAssemblyBuffer = CombInit(assemblyBufferReg)
                nextAssemblyBuffer
                  .subdivideIn(memConfig.internalDataWidth)
                  .apply(chunkId) := dataToAssemble
                assembledDataForOutput := nextAssemblyBuffer
                assemblyBufferReg := nextAssemblyBuffer
                if (enableLog) {
                  report(
                    L"  READ_ASSEMBLE (multi-chunk part ${chunkId}): dataToAssembleIntoSlice=${dataToAssemble}, assemblyBufferReg_In=${assemblyBufferReg}, assemblyBufferReg_Out(nextCycle)=${nextAssemblyBuffer}, assembledDataForOutputThisCycle=${assembledDataForOutput}"
                  )
                }
              } else {
                assembledDataForOutput := dataToAssemble.resized
              }
            }
          }

          latencyCounterReg := U(0)
          val isLastPart =
            if (numChunksPerWord > 1) partCounterReg === (numChunksPerWord - 1) else True

          if (enableLog) { report(L"[SimGenMem FSM] sProcessInternal: Chunk processed. isLastPart=${isLastPart}") }

          when(isLastPart) {
            io.bus.rsp.valid := True
            val finalErrorSignal = dataErrorForRspReg
            val finalReadData = Mux(
              currentOpcodeReg === GenericMemoryBusOpcode.OP_READ && !finalErrorSignal,
              assembledDataForOutput,
              B(0)
            )
            io.bus.rsp.payload.readData := finalReadData
            io.bus.rsp.payload.error := finalErrorSignal
            if (enableLog) {
              when(io.bus.rsp.fire) {
                report(
                  L"[SimGenMem FSM] sProcessInternal: RSP Fire! To sIdle. ReadDataOut=${finalReadData}, ErrorOut=${finalErrorSignal}"
                )
              }
            }
            when(io.bus.rsp.fire) {
              goto(sIdle)
            }
          } otherwise {
            if (numChunksPerWord > 1) {
              partCounterReg := partCounterReg + U(1, partCounterReg.getWidth bits)
              if (enableLog) {
                report(
                  L"[SimGenMem FSM] sProcessInternal: Advancing to next part. New partCounterReg=${partCounterReg} + 1"
                )
              }
            }
          }
        }
      }
    }
  }

  val addrForAsyncReadInProcess_comb =
    baseInternalWordAddr + (if (numChunksPerWord > 1) partCounterReg else U(0))
  val addrValidForAsyncRead_comb =
    addrForAsyncReadInProcess_comb.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount

  // Drive internalReadData based on the address for the *current* chunk being processed *after* latency.
  // This means it uses partCounterReg which reflects the current chunk index.
  when(
    sm.isActive(sm.sProcessInternal) && (latencyCounterReg >= memConfig.initialLatency) && addrValidForAsyncRead_comb
  ) {
    internalReadData := mem.readAsync(address = addrForAsyncReadInProcess_comb.resize(memConfig.internalAddrWidth))
  } otherwise {
    internalReadData := B(0)
  }
}
