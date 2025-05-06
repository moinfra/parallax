package boson.components

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import scala.util.Random

// Define the command structure for memory access
case class MemoryCommand(addressWidth: Int, dataWidth: Int) extends Bundle {
  val address = UInt(addressWidth bits)
  val isWrite = Bool()
  val writeData = Bits(dataWidth bits)
}

// Define the response structure (only for reads)
case class MemoryResponse(dataWidth: Int) extends Bundle {
  val readData = Bits(dataWidth bits)
}

case class DelayedMemoryConfig(
    addressWidth: Int = 8,
    dataWidth: Int = 32,
    writeQueueDepth: Int = 4, // Depth of the write buffer
    minReadDelay: Int = 3,
    maxReadDelay: Int = 15,
    minWriteProcessDelay: Int = 20,
    maxWriteProcessDelay: Int = 30
)

class DelayedMemory(config: DelayedMemoryConfig) extends Component {
  val io = new Bundle {
    // Interpreting ireq as cmd, ires as rsp
    val cmd = slave Stream (MemoryCommand(config.addressWidth, config.dataWidth))
    val rsp = master Stream (MemoryResponse(config.dataWidth))
  }

  // Internal memory storage
  val mem = Mem(Bits(config.dataWidth bits), wordCount = 1 << config.addressWidth)

  // --- Write Path ---
  val writeQueue = StreamFifo(dataType = MemoryCommand(config.addressWidth, config.dataWidth), depth = config.writeQueueDepth)
  writeQueue.io.push.valid := False
  writeQueue.io.push.payload.assignDontCare()

  val writeProcessing = RegInit(False)
  val counterWidth = log2Up(config.maxWriteProcessDelay + 1)
  val writeProcessCounter = Reg(UInt(counterWidth bits)) init (0)
  val currentWriteCmd = Reg(MemoryCommand(config.addressWidth, config.dataWidth))

  // Connect external command to write queue input if it's a write
  // And if not currently processing a read (to simplify ready logic, though can be optimized)
  val canAcceptWrite = !writeQueue.io.push.ready
  
  // --- Read Path ---
  val readActive = RegInit(False)
  val readDelayCounter = Reg(UInt(log2Up(config.maxReadDelay + 1) bits)) init (0)
  val readAddressReg = Reg(UInt(config.addressWidth bits))
  val readDataReg = Reg(Bits(config.dataWidth bits)) // Data fetched from memory

  // Default assignments
  io.cmd.ready := False
  io.rsp.valid := False
  io.rsp.payload.readData := readDataReg

  // Command Acceptance Logic
  when(!readActive) { // Can only accept new commands if not busy with a read's delay phase
    when(io.cmd.valid) {
      when(io.cmd.payload.isWrite) {
        io.cmd.ready := writeQueue.io.push.ready
        when(io.cmd.ready){ // Command is accepted
            writeQueue.io.push.valid := True
            writeQueue.io.push.payload := io.cmd.payload
        }.otherwise{
            writeQueue.io.push.valid := False
            writeQueue.io.push.payload.assignDontCare()
        }
      } otherwise { // It's a Read
        io.cmd.ready := True // Accept read if not already active
        when(io.cmd.ready){ // Command is accepted
            readActive := True
            readAddressReg := io.cmd.payload.address
            // Fetch data immediately, delay is for response
            readDataReg := mem.readSync(io.cmd.payload.address, readUnderWrite = readFirst) // readSync is safer
            
            // For simulation, generate random delay. For synthesis, this needs a PRNG.
            // The value is latched when readActive becomes true.
            val randomReadDelay = U(Random.nextInt(config.maxReadDelay - config.minReadDelay + 1) + config.minReadDelay, counterWidth bits)
            readDelayCounter := randomReadDelay - 1 // -1 because we count down to 0
            
            // Make sure write queue push is not asserted if this is a read
            writeQueue.io.push.valid := False
            writeQueue.io.push.payload.assignDontCare()
        }
      }
    } otherwise { // io.cmd not valid
        writeQueue.io.push.valid := False
        writeQueue.io.push.payload.assignDontCare()
    }
  } otherwise { // readActive is true
    writeQueue.io.push.valid := False // Don't push to write queue while read is active
    writeQueue.io.push.payload.assignDontCare()
  }


  // Read Response Logic
  when(readActive) {
    when(readDelayCounter === 0) {
      io.rsp.valid := True
      when(io.rsp.ready) { // Master accepts the response
        readActive := False
        // readDataReg already holds the data
      }
    } otherwise {
      readDelayCounter := readDelayCounter - 1
    }
  }

  // Internal Write Processing Logic
  writeQueue.io.pop.ready := !writeProcessing && !readActive // Prioritize completing reads before starting new write processing. More elaborate arbitration is possible.

  when(writeProcessing) {
    when(writeProcessCounter === 0) {
      mem.write(currentWriteCmd.address, currentWriteCmd.writeData)
      writeProcessing := False
      // Ensure pop is not asserted in the same cycle write completes if queue was empty
      // writeQueue.io.pop.ready is handled above
    } otherwise {
      writeProcessCounter := writeProcessCounter - 1
    }
  }

  when(writeQueue.io.pop.fire) { // Pop a new write command from queue
    writeProcessing := True
    currentWriteCmd := writeQueue.io.pop.payload
    // For simulation, generate random delay. For synthesis, this needs a PRNG.
    val randomWriteDelay = U(Random.nextInt(config.maxWriteProcessDelay - config.minWriteProcessDelay + 1) + config.minWriteProcessDelay, counterWidth bits)
    writeProcessCounter := randomWriteDelay -1 // -1 because we count down to 0
  }
}

object DelayedMemoryMain {
  def main(args: Array[String]) {
    val config = DelayedMemoryConfig(
        addressWidth = 8, 
        dataWidth = 32,
        writeQueueDepth = 4 
    )
    SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
    ).generateVerilog(new DelayedMemory(config))
    println("Verilog generated")
  }
}
