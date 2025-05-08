package boson.demo2.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// SimulatedMemoryConfig remains largely the same or can be simplified
case class SimulatedMemoryConfig(
    internalDataWidth: Int = 16, // Width of one memory word in this simulated RAM (e.g., 16 bits = 2 bytes)
    memSize: BigInt = 8 KiB, // Total size of this simulated RAM in bytes
    initialLatency: Int = 2, // Latency for the first access to a new address
    burstLatency: Int = 1 // Latency for subsequent accesses in a burst (if bus supports burst)
    // For now, let's use a single latency parameter
) {
  require(isPow2(internalDataWidth / 8), "Simulated memory data width (in bytes) must be a power of 2")
  val internalDataWidthBytes: Int = internalDataWidth / 8
  val internalWordCount: Int = (memSize / internalDataWidthBytes).toInt
  require(internalWordCount > 0, "Memory size results in zero words.")
  val internalAddrWidth: Int = log2Up(internalWordCount)

  // Max byte address this memory can store
  val maxByteAddress: BigInt = memSize - 1
}

class SimulatedMemory(
    val memConfig: SimulatedMemoryConfig,
    val busConfig: GenericMemoryBusConfig
) extends Component {

  val io = new Bundle {
    val bus = slave(GenericMemoryBus(busConfig))
    val writeEnable = in Bool () default (False)
    val writeAddress = in UInt (busConfig.addressWidth bits) default (U(0, busConfig.addressWidth bits))
    val writeData = in Bits (memConfig.internalDataWidth bits) default (B(0, memConfig.internalDataWidth bits))
  }

  val internalWordsPerBusData: Int = busConfig.dataWidth / memConfig.internalDataWidth
  require(busConfig.dataWidth % memConfig.internalDataWidth == 0)
  require(internalWordsPerBusData > 0)

  val mem = Mem(Bits(memConfig.internalDataWidth bits), wordCount = memConfig.internalWordCount)
  when(io.writeEnable) {
    val internalWriteWordAddress =
      (io.writeAddress >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
    when(internalWriteWordAddress.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount) {
      mem.write(address = internalWriteWordAddress, data = io.writeData)
      report(L"[SimMem TB] Testbench Write to Addr: ${internalWriteWordAddress} Data: ${io.writeData}")
    } otherwise {
      report(
        L"WARNING! [SimMem TB] Testbench write out of range. ByteAddr: ${io.writeAddress} InternalAddr: ${internalWriteWordAddress}"
      )
    }
  }
val currentBusAddressReg = Reg(UInt(busConfig.addressWidth bits)) init (U(0, busConfig.addressWidth bits))
  val currentIsWriteReg = Reg(Bool()) init (False)
  val currentWriteDataReg = Reg(Bits(busConfig.dataWidth bits)) init (B(0, busConfig.dataWidth bits))
  val latencyCounterReg = Reg(UInt(log2Up(memConfig.initialLatency + 1) bits)) init (U(0, log2Up(memConfig.initialLatency + 1) bits))
  
  val baseInternalWordAddr = (currentBusAddressReg >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
  val internalReadData = Bits(memConfig.internalDataWidth bits)
  internalReadData := B(0)

  // --- Conditional Part Counter & Assembly Buffer (Elaboration Time) ---
  val partCounterReg: UInt = if (internalWordsPerBusData > 1) {
    val width = log2Up(internalWordsPerBusData)
    Reg(UInt(width bits)) init(U(0, width bits)) setName("partCounterReg_physical")
  } else {
    null 
  }

  val assemblyBufferReg: Bits = if (internalWordsPerBusData > 1) {
    Reg(Bits(busConfig.dataWidth bits)) init(B(0, busConfig.dataWidth bits)) setName("assemblyBufferReg_physical")
  } else {
    null 
  }

  // --- FSM ---
  val sm = new StateMachine {
    io.bus.cmd.ready := False
    io.bus.rsp.valid := False
    io.bus.rsp.payload.readData := B(0, busConfig.dataWidth bits)
    io.bus.rsp.payload.error := False

    val sIdle: State = new State with EntryPoint {
      whenIsActive {
        io.bus.cmd.ready := True
        when(io.bus.cmd.fire) {
          currentBusAddressReg := io.bus.cmd.payload.address
          currentIsWriteReg := io.bus.cmd.payload.isWrite
          currentWriteDataReg := io.bus.cmd.payload.writeData
          
          if (internalWordsPerBusData > 1) {
            partCounterReg := U(0) 
            assemblyBufferReg := B(0) // Only reset if it exists
          }
          latencyCounterReg := 0
          goto(sProcessInternal)
        }
      }
    }

    val sProcessInternal: State = new State {
      val assembledDataForOutput = Bits(busConfig.dataWidth bits)
      assembledDataForOutput := B(0) 

      val lastByteAddrOfCurrentBusTransaction = currentBusAddressReg + U(busConfig.dataWidth/8 - 1, busConfig.addressWidth bits)
      val busLevelAccessOutOfBounds = lastByteAddrOfCurrentBusTransaction >= U(memConfig.memSize, busConfig.addressWidth bits)
      
      val currentProcessingWordIdx: UInt = UInt(memConfig.internalAddrWidth bits)
      val currentWordAccessValid: Bool = Bool()
      val dataErrorForRsp = Reg(Bool()) init(False) 

      if (internalWordsPerBusData > 1) {
        currentProcessingWordIdx := baseInternalWordAddr + partCounterReg
        currentWordAccessValid := currentProcessingWordIdx.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount
      } else { 
        currentProcessingWordIdx := baseInternalWordAddr
        currentWordAccessValid := currentProcessingWordIdx.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount
      }
      
      whenIsActive {
        // ... (reports as before) ...
        when(latencyCounterReg < memConfig.initialLatency) {
          latencyCounterReg := latencyCounterReg + 1
        } otherwise { 
          val currentCycleError = busLevelAccessOutOfBounds || !currentWordAccessValid
          when(currentCycleError && !dataErrorForRsp) { 
             dataErrorForRsp := True 
          }

          when(currentIsWriteReg) {
          } otherwise { // Read
            val dataToAssemble = Bits(memConfig.internalDataWidth bits)
            when(currentWordAccessValid && !busLevelAccessOutOfBounds) { 
                dataToAssemble := internalReadData 
            } otherwise { dataToAssemble := B(0, memConfig.internalDataWidth bits) }

            if (internalWordsPerBusData == 1) {
              assembledDataForOutput := dataToAssemble.resize(busConfig.dataWidth bits)
            } else { // Multi-part
              val shiftAmountDynamic = partCounterReg * memConfig.internalDataWidth
              val shiftedDataPart = (dataToAssemble.asUInt << shiftAmountDynamic).resize(busConfig.dataWidth bits)
              val nextAssemblyBuffer = assemblyBufferReg | shiftedDataPart.asBits // Accessing assemblyBufferReg
              assembledDataForOutput := nextAssemblyBuffer
              assemblyBufferReg := nextAssemblyBuffer // Assigning to assemblyBufferReg
            }
          }
          latencyCounterReg := 0 

          // --- FSM Progression Logic ---
          if (internalWordsPerBusData == 1) {
            io.bus.rsp.valid := True
            io.bus.rsp.payload.readData := Mux(currentIsWriteReg || dataErrorForRsp, B(0), assembledDataForOutput)
            io.bus.rsp.payload.error := dataErrorForRsp
            when(io.bus.rsp.fire || currentIsWriteReg) { 
                dataErrorForRsp := False 
                goto(sIdle)
            }
          } else { // Multi-part
            val isLastPart = partCounterReg === (internalWordsPerBusData - 1)
            when(isLastPart) {
              io.bus.rsp.valid := True
              io.bus.rsp.payload.readData := Mux(currentIsWriteReg || dataErrorForRsp, B(0), assembledDataForOutput)
              io.bus.rsp.payload.error := dataErrorForRsp
              when(io.bus.rsp.fire || currentIsWriteReg) { 
                  dataErrorForRsp := False 
                  goto(sIdle)
              }
            } otherwise { 
              partCounterReg := partCounterReg + U(1, 1 bits) 
            }
          }
        }
      }
    }
  } // End of FSM

  // --- Combinational Read/Write Logic ---
  val combCurrentProcessingWordIdx = if(internalWordsPerBusData > 1) baseInternalWordAddr + partCounterReg else baseInternalWordAddr
  val combWordAccessValid = if (memConfig.internalWordCount == 0) False else combCurrentProcessingWordIdx.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount
  val combBusLevelAccessOutOfBounds = (currentBusAddressReg + U(busConfig.dataWidth/8 - 1, busConfig.addressWidth bits)) >= U(memConfig.memSize, busConfig.addressWidth bits)

  when(sm.isActive(sm.sProcessInternal) && !currentIsWriteReg && (latencyCounterReg >= memConfig.initialLatency) && combWordAccessValid && !combBusLevelAccessOutOfBounds) {
    internalReadData := mem.readAsync(address = combCurrentProcessingWordIdx)
  }
  when(sm.isActive(sm.sProcessInternal) && currentIsWriteReg && (latencyCounterReg >= memConfig.initialLatency) && combWordAccessValid && !combBusLevelAccessOutOfBounds) {
    val writeDataParts = currentWriteDataReg.subdivideIn(memConfig.internalDataWidth bits)
    val actualPartIndexForWrite: UInt = if (internalWordsPerBusData == 1) { U(0) } 
                                      else { partCounterReg } // Your fix
    mem.write(address = combCurrentProcessingWordIdx, data = writeDataParts(actualPartIndexForWrite))
  }
}
