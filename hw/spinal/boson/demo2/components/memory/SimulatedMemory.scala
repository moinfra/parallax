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
  val maxByteAddress = memSize - 1
}

class SimulatedMemory(
    val memConfig: SimulatedMemoryConfig, // Its own configuration
    val busConfig: GenericMemoryBusConfig // Configuration of the bus it connects to
) extends Component {

  val io = new Bundle {
    val bus = slave(GenericMemoryBus(busConfig))
    // Testbench write port (byte addressed, data is internalDataWidth)
    val writeEnable = in Bool () default (False)
    val writeAddress = in UInt (busConfig.addressWidth bits) default (0) // Byte address
    val writeData = in Bits (memConfig.internalDataWidth bits) default (0)
  }

  // --- Derived Parameters ---
  // How many internal memory words make up one bus data word
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
    val internalWriteWordAddress = (io.writeAddress >> log2Up(memConfig.internalDataWidthBytes)).resized
    when(internalWriteWordAddress < memConfig.internalWordCount) {
      mem.write(
        address = internalWriteWordAddress(memConfig.internalAddrWidth - 1 downto 0),
        data = io.writeData
      )
    } otherwise {
      report(
        L"WARNING! Testbench write BYTE address ${io.writeAddress} (internal word addr ${internalWriteWordAddress}) is out of simulated RAM range."
      )
    }
  }

  // --- FSM Logic ---
  val currentBusAddressReg = Reg(UInt(busConfig.addressWidth bits)) init (0)
  val currentIsWriteReg = Reg(Bool()) init (False)
  val currentWriteDataReg = Reg(Bits(busConfig.dataWidth bits)) init (0)

  // For assembling/disassembling busDataWidth from/to internalDataWidth
  val partCounterReg = Reg(UInt(internalWordsPerBusDataWidth bits)) init (0) // Width can be 0
  val assemblyBufferReg = Reg(Bits(busConfig.dataWidth bits)) init (0)
  val latencyCounterReg = Reg(UInt(log2Up(memConfig.initialLatency + 1) bits)) init (0)

  // --- Read/Write Logic ---
  val baseInternalWordAddr = (currentBusAddressReg >> log2Up(memConfig.internalDataWidthBytes)).resized
  val currentInternalWordIdx = (baseInternalWordAddr + partCounterReg)
  val readAddrInRange = currentInternalWordIdx < memConfig.internalWordCount
  val internalReadData = Bits(memConfig.internalDataWidth bits)

  internalReadData := B(0) // Default to 0 for reads

  val sm = new StateMachine {
    io.bus.cmd.ready := False
    io.bus.rsp.valid := False
    io.bus.rsp.payload.readData := B(0)
    io.bus.rsp.payload.error := False // Default to no error

    val sIdle: State = new State with EntryPoint {
      whenIsActive {
        io.bus.cmd.ready := True
        when(io.bus.cmd.fire) {
          currentBusAddressReg := io.bus.cmd.payload.address
          currentIsWriteReg := io.bus.cmd.payload.isWrite
          currentWriteDataReg := io.bus.cmd.payload.writeData
          partCounterReg := 0
          assemblyBufferReg := 0 // Clear for reads
          latencyCounterReg := 0
          goto(sProcessInternal)
        }
      }
    }

    val sProcessInternal: State = new State {
      whenIsActive {
        when(latencyCounterReg < memConfig.initialLatency) {
          latencyCounterReg := latencyCounterReg + 1
        } otherwise { // Latency met for this internal part
          val dataError = !readAddrInRange

          when(currentIsWriteReg) { // Handle Write
            // Write was armed above via mem.write when condition was met
          } otherwise { // Handle Read
            val dataToAssemble = Bits(memConfig.internalDataWidth bits)
            when(readAddrInRange) {
              dataToAssemble := internalReadData
            } otherwise {
              dataToAssemble := B(0) // Or some error pattern
              report(
                L"WARNING! Simulated Memory read access out of range. ByteAddress: ${currentBusAddressReg}, Part: ${partCounterReg}"
              )
            }
            val shiftAmount = (partCounterReg * memConfig.internalDataWidth)
            assemblyBufferReg := (assemblyBufferReg.asUInt | (dataToAssemble.asUInt << shiftAmount)).asBits.resized
          }

          latencyCounterReg := 0 // Reset for next part or next bus transaction

          when(partCounterReg === (internalWordsPerBusData - 1)) { // All parts for one bus transaction processed
            io.bus.rsp.valid := True
            io.bus.rsp.payload.readData := Mux(
              currentIsWriteReg,
              B(0),
              assemblyBufferReg
            ) // Only send readData on reads
            io.bus.rsp.payload.error := dataError // Propagate error if any part was out of range

            when(io.bus.rsp.fire || currentIsWriteReg) { // Slave is ready or it's a write (no rsp.fire needed for write ack)
              // A more robust bus might have a write response/ack.
              // For simplicity here, writes are "fire and forget" from FSM perspective after parts are written.
              goto(sIdle)
            }
          } otherwise {
            partCounterReg := partCounterReg + 1
            // Stay in sProcessInternal for the next part
          }
        }
      }
    }
  }

  when(
    sm.isActive(
      sm.sProcessInternal
    ) && !currentIsWriteReg && latencyCounterReg === memConfig.initialLatency && readAddrInRange
  ) {
    internalReadData := mem.readAsync(
      address =
        currentInternalWordIdx.resize(memConfig.internalAddrWidth.bits)(memConfig.internalAddrWidth - 1 downto 0)
    )
  }

// For writes, we'd need a similar mechanism to write parts if busDataWidth > internalDataWidth
  when(
    sm.isActive(
      sm.sProcessInternal
    ) && currentIsWriteReg && latencyCounterReg === memConfig.initialLatency && readAddrInRange
  ) {
    // Subdivide the bus data into internal data word sized chunks
    val writeDataParts = currentWriteDataReg.subdivideIn(memConfig.internalDataWidth bits)
    // Select the correct part using the dynamic partCounterReg
    val dataToWrite = writeDataParts(partCounterReg)

    mem.write(
      address = currentInternalWordIdx(memConfig.internalAddrWidth - 1 downto 0),
      data = dataToWrite
    )
  }

}
