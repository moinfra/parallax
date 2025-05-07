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
    val memConfig: SimulatedMemoryConfig, // Its own configuration
    val busConfig: GenericMemoryBusConfig // Configuration of the bus it connects to
) extends Component {

  val io = new Bundle {
    val bus = slave(GenericMemoryBus(busConfig))
    // Testbench write port (byte addressed, data is internalDataWidth)
    val writeEnable = in Bool () default (False)
    val writeAddress = in UInt (busConfig.addressWidth bits) default (U(0, busConfig.addressWidth bits)) // Byte address
    val writeData = in Bits (memConfig.internalDataWidth bits) default (B(0, memConfig.internalDataWidth bits))
  }

  // --- Derived Parameters ---
  val internalWordsPerBusData: Int = busConfig.dataWidth / memConfig.internalDataWidth
  require(
    busConfig.dataWidth % memConfig.internalDataWidth == 0,
    s"Bus data width (${busConfig.dataWidth}) must be a multiple of simulated memory internal data width (${memConfig.internalDataWidth})"
  )
  require(internalWordsPerBusData > 0)
  val internalWordsPerBusDataWidth: Int = if (internalWordsPerBusData > 1) log2Up(internalWordsPerBusData) else 0

  // --- Internal Memory Storage ---
  val mem = Mem(Bits(memConfig.internalDataWidth bits), wordCount = memConfig.internalWordCount)

  // --- Testbench Write Port Logic ---
  when(io.writeEnable) {
    val internalWriteWordAddress =
      (io.writeAddress >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
    // Ensure the address for Mem.write is correctly sized for wordCount.
    // mem.write internally masks the address to fit mem.wordCount, but checking prevents unexpected behavior with large addresses.
    // A direct comparison with internalWordCount (Int) is fine.
    if (memConfig.internalWordCount > 0) { // Only write if memory exists
      when(internalWriteWordAddress.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount) {
        mem.write(
          address = internalWriteWordAddress,
          data = io.writeData
        )
      } otherwise {
        report(
          L"WARNING! Testbench write BYTE address ${io.writeAddress} (internal word addr ${internalWriteWordAddress}) is out of simulated RAM range (${memConfig.internalWordCount.toString()} words)."
        )
      }
    }
  }

  // --- FSM Logic ---
  val currentBusAddressReg = Reg(UInt(busConfig.addressWidth bits)) init (U(0, busConfig.addressWidth bits))
  val currentIsWriteReg = Reg(Bool()) init (False)
  val currentWriteDataReg = Reg(Bits(busConfig.dataWidth bits)) init (B(0, busConfig.dataWidth bits))

  val partCounterRegWidth = if (internalWordsPerBusData > 1) log2Up(internalWordsPerBusData) else 1
  val partCounterReg = Reg(UInt(partCounterRegWidth bits)) init (U(0, partCounterRegWidth bits))

  val assemblyBufferReg = Reg(Bits(busConfig.dataWidth bits)) init (B(0, busConfig.dataWidth bits))
  val latencyCounterReg =
    Reg(UInt(log2Up(memConfig.initialLatency + 1) bits)) init (U(0, log2Up(memConfig.initialLatency + 1) bits))

  // --- Read/Write Logic ---
  val baseInternalWordAddr =
    (currentBusAddressReg >> log2Up(memConfig.internalDataWidthBytes)).resize(memConfig.internalAddrWidth)
  val currentInternalWordIdx = baseInternalWordAddr + partCounterReg

  // Check address range based on the number of words in the memory
  val readAddrInRange =
    if (memConfig.internalWordCount == 0) False
    else currentInternalWordIdx.resize(memConfig.internalWordCount.bits) < memConfig.internalWordCount

  val internalReadData = Bits(memConfig.internalDataWidth bits)
  internalReadData := B(0) // Default

  val sm = new StateMachine {
    io.bus.cmd.ready := False
    io.bus.rsp.valid := False
    io.bus.rsp.payload.readData := B(0, busConfig.dataWidth bits)
    io.bus.rsp.payload.error := False

    val sIdle: State = new State with EntryPoint {
      whenIsActive {
        io.bus.cmd.ready := True
        report(L"[SimMem] Idle state")
        when(io.bus.cmd.fire) {
          currentBusAddressReg := io.bus.cmd.payload.address
          currentIsWriteReg := io.bus.cmd.payload.isWrite
          currentWriteDataReg := io.bus.cmd.payload.writeData
          partCounterReg := 0
          assemblyBufferReg := 0 // Clear for new read transaction
          latencyCounterReg := 0
          goto(sProcessInternal)
        }
      }
    }

    val sProcessInternal: State = new State {
      // This wire will hold the fully assembled data for the current cycle's read operation,
      // including the part being processed in this cycle.
      val assembledDataForOutput = Bits(busConfig.dataWidth bits)
      assembledDataForOutput := B(0) // Default, important for writes

      whenIsActive {
        report(
          L"[SimMem] sProcessInternal: LatencyCounter: ${latencyCounterReg}, PartCounter: ${partCounterReg}, IsWrite: ${currentIsWriteReg}"
        )

        when(latencyCounterReg < memConfig.initialLatency) {
          latencyCounterReg := latencyCounterReg + 1
          report(L"[SimMem] Processing latency ${latencyCounterReg} of ${memConfig.initialLatency.toString}")
        } otherwise { // Latency met for this internal part
          val dataError = !readAddrInRange // Error if current part's address is out of range

          when(currentIsWriteReg) { // Handle Write
            // Write is armed via mem.write in the combinational logic outside FSM.
            // No specific data assembly needed here for writes.
            // assembledDataForOutput remains B(0) as per its default.
            report(L"[SimMem] Write part ${partCounterReg} for addr ${currentBusAddressReg}")
          } otherwise { // Handle Read
            val dataToAssemble = Bits(memConfig.internalDataWidth bits)
            when(readAddrInRange) {
              dataToAssemble := internalReadData // internalReadData gets its value from mem.readAsync
            } otherwise {
              dataToAssemble := B(0, memConfig.internalDataWidth bits) // Or some error pattern
              report(
                L"WARNING! Simulated Memory read access out of range. ByteAddress: ${currentBusAddressReg}, Part: ${partCounterReg}, InternalWordIdx: ${currentInternalWordIdx}"
              )
            }

            val shiftAmountDynamic = partCounterReg * memConfig.internalDataWidth
            // Ensure shiftedDataPart has the busDataWidth before ORing.
            // The result of `<<` can be wider if dataToAssemble.asUInt is not explicitly sized.
            // However, .resize after shift is correct.
            val shiftedDataPart = (dataToAssemble.asUInt << shiftAmountDynamic).resize(busConfig.dataWidth bits)

            // Calculate the data that includes the current part. This is what should be output if it's the last part.
            assembledDataForOutput := (assemblyBufferReg | shiftedDataPart.asBits)
            // Update the register for the next part / next cycle.
            assemblyBufferReg := assembledDataForOutput
            report(
              L"[SimMem] Read part ${partCounterReg}. Assembling ${dataToAssemble} (shifted ${shiftedDataPart}) into current buffer ${assemblyBufferReg} -> new buffer ${assembledDataForOutput}"
            )
          }

          latencyCounterReg := 0 // Reset for next part or next bus transaction

          when(partCounterReg === (internalWordsPerBusData - 1)) { // internalWordsPerBusData can be 0 if busConfig.dataWidth == memConfig.internalDataWidth (then partCounter width is 0) --> effectively 1 part. More robust: internalWordsPerBusData == 1 implies partCounterReg is always 0.
            // Check should be against (internalWordsPerBusData - 1). If internalWordsPerBusData is 1, then this is 0.
            val isLastPart =
              if (internalWordsPerBusData > 0) partCounterReg === (internalWordsPerBusData - 1) else True

            when(isLastPart) {
              report(
                L"[SimMem] All parts processed for bus transaction. Outputting readData: ${assembledDataForOutput}"
              )
              io.bus.rsp.valid := True
              io.bus.rsp.payload.readData := Mux(
                currentIsWriteReg,
                B(0, busConfig.dataWidth bits), // No read data on actual writes
                assembledDataForOutput // Use the data assembled *this cycle*
              )
              io.bus.rsp.payload.error := dataError // Error reflects if *any* part had an issue.

              when(io.bus.rsp.fire || (currentIsWriteReg && io.bus.rsp.ready)) { // Slave is ready or it's a write and master accepts response (even if empty)
                goto(sIdle)
              }
            }
          } 
          
          if (internalWordsPerBusData > 1) { // If there are multiple parts to process, increment the part counter.
            when(partCounterReg =/= (internalWordsPerBusData - 1) ) {
                partCounterReg := partCounterReg + 1
              // Stay in sProcessInternal for the next part
            }
          }
        }
      }
    }
  }

  // Combinational logic for memory read operation
  when(
    sm.isActive(sm.sProcessInternal) &&
      !currentIsWriteReg &&
      (latencyCounterReg >= memConfig.initialLatency) && // Condition should be >=, as FSM waits until counter < N is false
      readAddrInRange
  ) {
    internalReadData := mem.readAsync(
      address = currentInternalWordIdx
    )
  }

  // Combinational logic for memory write operation (writing parts)
  when(
    sm.isActive(sm.sProcessInternal) &&
      currentIsWriteReg &&
      (latencyCounterReg >= memConfig.initialLatency) && // Condition should be >=
      readAddrInRange // Use readAddrInRange for consistency, effectively checking write address validity
  ) {
    val writeDataParts = currentWriteDataReg.subdivideIn(memConfig.internalDataWidth bits)

    val actualPartIndex = if (internalWordsPerBusData == 1) {
      U(0)
    } else {
      partCounterReg
    }

    mem.write(
      address = currentInternalWordIdx,
      data = writeDataParts(actualPartIndex)
    )
    report(
      L"[SimMem] Writing part ${partCounterReg}, index ${actualPartIndex}: ${writeDataParts(actualPartIndex)} to internal addr ${currentInternalWordIdx}"
    )
  }
}
