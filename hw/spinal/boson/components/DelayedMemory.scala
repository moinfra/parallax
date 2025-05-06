package boson.components

import spinal.core._
import spinal.lib._
import spinal.core.sim._
// import scala.util.Random // No longer needed for hardware random

// Import the hardware PRNG library

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
    minReadDelay: Int = 3, // Min cycles for a memory read to complete (if not bypassed)
    maxReadDelay: Int = 15, // Max cycles for a memory read to complete
    minWriteProcessDelay: Int = 20, // Min cycles for a write to be processed from queue to memory
    maxWriteProcessDelay: Int = 30, // Max cycles for a write to be processed
    enableBypassWriteToRead: Boolean = false // Enables forwarding and bypass
)

class DelayedMemory(config: DelayedMemoryConfig) extends Component {
  val io = new Bundle {
    val cmd = slave Stream (MemoryCommand(config.addressWidth, config.dataWidth))
    val rsp = master Stream (MemoryResponse(config.dataWidth))
  }

  val mem = Mem(Bits(config.dataWidth bits), wordCount = 1 << config.addressWidth)

  // --- Hardware PRNG ---
  // Use a Fibonacci LFSR as a simple hardware PRNG
  // The width should be sufficient to cover the maximum possible delay range.
  // Max delay range size is max(maxReadDelay, maxWriteProcessDelay) - min(...) + 1
  // Here max is 30, min is 3. Range ~28. log2Up(28) is 5 bits. A 32-bit PRNG is standard and sufficient.
  val rng = FibonacciLFSR(8, 42)
  rng.io.enable := True

  // --- Write Path ---
  val writeQueue =
    StreamFifo(dataType = MemoryCommand(config.addressWidth, config.dataWidth), depth = config.writeQueueDepth)
  val writeProcessing = RegInit(False) // True if a write is being processed (delay before actual mem write)
  val writeProcessCounterWidth = log2Up(config.maxWriteProcessDelay + 1)
  val writeProcessCounter = Reg(UInt(writeProcessCounterWidth bits)) init (0)
  val currentWriteCmd = Reg(
    MemoryCommand(config.addressWidth, config.dataWidth)
  ) // Stores the write cmd being processed

  // --- Write Queue Mirror for Bypass Search ---
  // This mirror reflects the contents of 'writeQueue' for easier searching.
  val writeQueueMirror_Cmd = config.enableBypassWriteToRead generate Vec.fill(config.writeQueueDepth)(
    Reg(MemoryCommand(config.addressWidth, config.dataWidth))
  )
  val writeQueueMirror_Valid =
    config.enableBypassWriteToRead generate Vec.fill(config.writeQueueDepth)(Reg(Bool()) init (False))
  val mirrorHead =
    config.enableBypassWriteToRead generate Counter(config.writeQueueDepth) // Points to the next pop location
  val mirrorTail =
    config.enableBypassWriteToRead generate Counter(config.writeQueueDepth) // Points to the next push location
  val mirrorOccupancy =
    config.enableBypassWriteToRead generate Reg(UInt(log2Up(config.writeQueueDepth + 1) bits)) init (0)

  if (config.enableBypassWriteToRead) {
    when(writeQueue.io.push.fire && mirrorOccupancy < config.writeQueueDepth) {
      // Note: The report below might show the *next* cycle's value for mirrorTail and mirrorOccupancy
      // Use the values *before* the increment for accurate reporting in the current cycle
      val currentMirrorTail = mirrorTail.value
      val currentMirrorOccupancy = mirrorOccupancy
      report(
        L"Write CMD 0x${writeQueue.io.push.payload.address} Data 0x${writeQueue.io.push.payload.writeData}: Pushing to writeQueueMirror at index 0x${currentMirrorTail}. Occupancy: ${currentMirrorOccupancy} -> ${currentMirrorOccupancy + 1}"
      )
      writeQueueMirror_Cmd(currentMirrorTail) := writeQueue.io.push.payload
      writeQueueMirror_Valid(currentMirrorTail) := True
      mirrorTail.increment()
      mirrorOccupancy := currentMirrorOccupancy + 1
    }

    when(writeQueue.io.pop.fire && mirrorOccupancy > 0) {
      // This entry is moving from queue to 'currentWriteCmd' for processing
      val currentMirrorHead = mirrorHead.value
      val currentMirrorOccupancy = mirrorOccupancy
      report(
        L"Write CMD 0x${writeQueueMirror_Cmd(currentMirrorHead).address}: Popping from writeQueueMirror at index 0x${currentMirrorHead}. Occupancy: ${currentMirrorOccupancy} -> ${currentMirrorOccupancy - 1}"
      )
      writeQueueMirror_Valid(currentMirrorHead) := False // Mark as invalid in mirror, as it's now in 'currentWriteCmd'
      mirrorHead.increment()
      mirrorOccupancy := currentMirrorOccupancy - 1
    }
  }

  // --- Read Path ---
  val readActive = RegInit(False) // For normal delayed reads (not bypassed/forwarded)
  val readDelayCounterWidth = log2Up(config.maxReadDelay + 1)
  val readDelayCounter = Reg(UInt(readDelayCounterWidth bits)) init (0)
  val readAddressReg = Reg(UInt(config.addressWidth bits))
  val readDataReg = Reg(Bits(config.dataWidth bits)) // Data fetched from memory for delayed read

  // --- Forwarding & Bypass Path for Reads ---
  val sBypassRespond_CmdAccepted = Bool() // True if a forwarded/bypassed read was accepted this cycle
  val sBypassRespond_Data = Reg(Bits(config.dataWidth bits)) // Data for the forwarded/bypassed response
  val sBypassRespond_Active =
    RegNext(sBypassRespond_CmdAccepted, init = False) // True if actively trying to send fwd/bypass response

  sBypassRespond_CmdAccepted := False // Default

  // Default assignments for outputs
  io.cmd.ready := False
  writeQueue.io.push.valid := False
  writeQueue.io.push.payload.assignDontCare()

  io.rsp.valid := False
  io.rsp.payload.readData.assignDontCare()

  // --- Forwarding and Bypass Search Logic (combinational) ---
  val forwardFromProcessorHit = Bool()
  val forwardedDataFromProcessor = Bits(config.dataWidth bits)
  val bypassHitFromQueue = Bool()
  val bypassedValueFromQueueData = Bits(config.dataWidth bits)

  // Default assignments for these signals for each cycle
  forwardFromProcessorHit := False
  forwardedDataFromProcessor.assignDontCare()
  bypassHitFromQueue := False
  bypassedValueFromQueueData.assignDontCare()

  if (config.enableBypassWriteToRead) {
    when(io.cmd.valid && !io.cmd.payload.isWrite) { // Only for valid read commands
      val readCmdAddr = io.cmd.payload.address
      report(L"Read CMD 0x${readCmdAddr}: Evaluating forwarding/bypass options.")

      // 1. Check for forwarding from the currently processing write (highest priority)
      when(writeProcessing && currentWriteCmd.address === readCmdAddr) {
        report(
          L"Read CMD 0x${readCmdAddr}: FORWARD from processor HIT. Data: 0x${currentWriteCmd.writeData}"
        )
        forwardFromProcessorHit := True
        forwardedDataFromProcessor := currentWriteCmd.writeData
      } otherwise {
        // 2. If not forwarded from processor, check bypass from writeQueueMirror (next priority)
        report(L"Read CMD 0x${readCmdAddr}: No forward from processor. Checking BYPASS from writeQueueMirror.")

        val potentialHits = Vec(Bool(), config.writeQueueDepth)
        val potentialData = Vec(Bits(config.dataWidth bits), config.writeQueueDepth) // Ensure type

        // Search from newest (tail-1) to oldest in the circular buffer mirror
        for (i <- 0 until config.writeQueueDepth) {
          val physicalIndex =
            ((mirrorTail.value - 1 - i + config.writeQueueDepth * (i + 1)) % config.writeQueueDepth)
              .resize(log2Up(config.writeQueueDepth) bits)

          val isInOccupancyRange = U(i) < mirrorOccupancy // Is this relative index 'i' valid w.r.t current occupancy?
          val isValidEntryInMirror =
            writeQueueMirror_Valid(physicalIndex) // Is the data at this physical mirror index valid?
          val isAddressMatch = writeQueueMirror_Cmd(physicalIndex).address === readCmdAddr

          potentialHits(i) := isInOccupancyRange && isValidEntryInMirror && isAddressMatch
          potentialData(i) := writeQueueMirror_Cmd(physicalIndex).writeData
        }

        val hitsAsBits = potentialHits.asBits // LSB is newest (i=0)
        val firstHitMask = OHMasking.first(hitsAsBits) // One-hot mask for the highest priority (newest) hit
        val anyHitInQueue = hitsAsBits.orR // True if any hit in the queue mirror

        when(anyHitInQueue) {
          bypassHitFromQueue := True
          bypassedValueFromQueueData := MuxOH(firstHitMask, potentialData)
          report(
            L"Read CMD 0x${readCmdAddr}: BYPASS from writeQueueMirror HIT. Data: 0x${bypassedValueFromQueueData}"
          )
        } otherwise {
          report(L"Read CMD 0x${readCmdAddr}: BYPASS from writeQueueMirror MISS.")
        }
      }
    }
  }

  // Command Acceptance Logic
  val canAcceptNewCommand =
    !readActive && !sBypassRespond_Active // Can't accept if busy with delayed read or sending fwd/bypass response

  when(canAcceptNewCommand) {
    when(io.cmd.valid) {
      val cmdAddr = io.cmd.payload.address
      when(io.cmd.payload.isWrite) {
        io.cmd.ready := writeQueue.io.push.ready // Ready if queue can accept
        when(writeQueue.io.push.ready) { // Command is accepted
          report(L"Write CMD 0x${cmdAddr} Data 0x${io.cmd.payload.writeData}: Accepted into writeQueue.")
          writeQueue.io.push.valid := True
          writeQueue.io.push.payload := io.cmd.payload
        } otherwise {
          report(L"Write CMD 0x${cmdAddr}: CANNOT be accepted, writeQueue FULL.")
        }
      } otherwise { // It's a Read command
        if (config.enableBypassWriteToRead) {
          when(forwardFromProcessorHit) {
            report(L"Read CMD 0x${cmdAddr}: Accepted (FORWARD from processor).")
            io.cmd.ready := True // Accept read for immediate forwarding
            sBypassRespond_CmdAccepted := True
            sBypassRespond_Data := forwardedDataFromProcessor
          } elsewhen (bypassHitFromQueue) {
            report(L"Read CMD 0x${cmdAddr}: Accepted (BYPASS from writeQueueMirror).")
            io.cmd.ready := True // Accept read for immediate bypass from queue
            sBypassRespond_CmdAccepted := True
            sBypassRespond_Data := bypassedValueFromQueueData
          } otherwise { // Normal delayed read (missed forwarding and queue bypass)
            report(L"Read CMD 0x${cmdAddr}: Accepted (NORMAL delayed read from Mem). Missed fwd/bypass.")
            io.cmd.ready := True // Accept read if not already active with other ops
            readActive := True
            readAddressReg := cmdAddr
            // readUnderWrite = readFirst means if a write happens to 'mem' in the same cycle as 'readSync'
            // is issued, the read will get the value *before* the write.
            // Given writeProcessing delay, direct conflict at 'mem' is less likely unless delays are minimal.
            readDataReg := mem.readAsync(cmdAddr, readUnderWrite = writeFirst)
            report(
              L"Read CMD 0x${cmdAddr}: mem.readSync issued. Will read 0x${readDataReg} (value from next cycle)."
            )

            // *** FIX: Use hardware PRNG for dynamic read delay ***
            val readDelayRange = U(config.maxReadDelay - config.minReadDelay + 1)
            val actualReadDelayCycles =
              U(config.minReadDelay) + (rng.io.value.asUInt % readDelayRange).resize(readDelayCounterWidth)
            readDelayCounter := actualReadDelayCycles - 1 // -1 because we count down to 0 then respond
            report(L"Read CMD 0x${cmdAddr}: Calculated dynamic read delay: ${actualReadDelayCycles} cycles.")
          }
        } else { // Bypass/Forwarding is disabled
          report(L"Read CMD 0x${cmdAddr}: Accepted (NORMAL delayed read, bypass/fwd disabled).")
          io.cmd.ready := True
          readActive := True
          readAddressReg := cmdAddr
          readDataReg := mem.readAsync(cmdAddr, readUnderWrite = writeFirst)
          report(
            L"Read CMD 0x${cmdAddr}: mem.readSync issued (bypass/fwd disabled). Will read 0x${readDataReg} (value from next cycle)."
          )

          // *** FIX: Use hardware PRNG for dynamic read delay (when bypass disabled) ***
          val readDelayRange = U(config.maxReadDelay - config.minReadDelay + 1)
          val actualReadDelayCycles =
            U(config.minReadDelay) + (rng.io.value.asUInt % readDelayRange).resize(readDelayCounterWidth)
          readDelayCounter := actualReadDelayCycles - 1
          report(
            L"Read CMD 0x${cmdAddr}: Calculated dynamic read delay: ${actualReadDelayCycles} cycles (bypass/fwd disabled)."
          )
        }
      }
    } otherwise { // io.cmd not valid
      // report(L"No command valid this cycle.") // Can be too verbose
    }
  } otherwise { // Cannot accept new command
    when(io.cmd.valid) {
      report(L"CMD VALID but cannot accept: readActive=${readActive}, sBypassRespond_Active=${sBypassRespond_Active}")
    }
  }

  // Read Response Logic (Prioritizing Forwarded/Bypassed, then Delayed)
  when(sBypassRespond_Active) { // Sending a forwarded or bypassed-from-queue response
    io.rsp.valid := True
    io.rsp.payload.readData := sBypassRespond_Data
    report(L"RSP: Sending forwarded/bypassed data 0x${sBypassRespond_Data}. Waiting for rsp.ready.")
    when(io.rsp.ready) {
      report(L"RSP: Forwarded/bypassed data 0x${sBypassRespond_Data} ACCEPTED by master.")
      sBypassRespond_CmdAccepted := False // Allow new commands if current fwd/bypass response accepted
      // sBypassRespond_Active will become False in the next cycle due to RegNext
    }
  } elsewhen (readActive) { // Sending a normal delayed read response
    when(readDelayCounter === 0) {
      io.rsp.valid := True
      io.rsp.payload.readData := readDataReg // This was latched when mem.readSync was initiated
      report(
        L"RSP: Sending delayed read data 0x${readDataReg} for Addr 0x${readAddressReg}. Waiting for rsp.ready."
      )
      when(io.rsp.ready) { // Master accepts the response
        report(L"RSP: Delayed read data 0x${readDataReg} for Addr 0x${readAddressReg} ACCEPTED by master.")
        readActive := False
      }
    } otherwise {
      readDelayCounter := readDelayCounter - 1
      // report(L"Read Addr 0x${readAddressReg}: Decrementing readDelayCounter to ${readDelayCounter - 1}.") // Can be verbose
    }
  }

  // Internal Write Processing Logic (from queue to actual memory)
  // Pop from write queue if:
  // 1. Not already processing another write from the queue (writeProcessing is False)
  // 2. AND The read path is not active (either normal delayed read OR sending a fwd/bypass response)
  // This prioritizes completing any read activity (including sending response) before starting a new memory write.
  writeQueue.io.pop.ready := !writeProcessing && !readActive && !sBypassRespond_Active

  when(writeProcessing) { // If currently processing a write command (delay before mem.write)
    when(writeProcessCounter === 0) {
      report(
        L"MEM WRITE: Start writing addr 0x${currentWriteCmd.address}, Data 0x${currentWriteCmd.writeData}"
      )
      mem.write(currentWriteCmd.address, currentWriteCmd.writeData)
      report(
        L"MEM WRITE: Finished writing addr 0x${currentWriteCmd.address}, Data 0x${currentWriteCmd.writeData} (End of write processing delay)"
      )
      writeProcessing := False
    } otherwise {
      writeProcessCounter := writeProcessCounter - 1
      // report(L"Write CMD 0x${currentWriteCmd.address}: Decrementing writeProcessCounter to ${writeProcessCounter - 1}.") // Verbose
    }
  }

  when(writeQueue.io.pop.fire) { // A new write command is popped from queue to start processing
    writeProcessing := True
    currentWriteCmd := writeQueue.io.pop.payload // Latch the command

    // *** FIX: Use hardware PRNG for dynamic write delay ***
    val writeDelayRange = U(config.maxWriteProcessDelay - config.minWriteProcessDelay + 1)
    val actualWriteDelayCycles =
      U(config.minWriteProcessDelay) + (rng.io.value.asUInt % writeDelayRange).resize(writeProcessCounterWidth)
    writeProcessCounter := actualWriteDelayCycles - 1 // -1 because we count down to 0 then write
    report(
      L"Write CMD 0x${writeQueue.io.pop.payload.address} Data 0x${writeQueue.io.pop.payload.writeData}: Popped from queue, starting processing. Delay set to 0x${actualWriteDelayCycles.asSInt} cycles."
    )
  }
}

object DelayedMemoryMain {
  def main(args: Array[String]) {
    // Note: scala.util.Random is still fine here for setting simulation initial memory content or configurations,
    // but NOT for determining cycle-accurate hardware behavior delays.

    val defaultConfig = DelayedMemoryConfig(
      addressWidth = 8,
      dataWidth = 32,
      writeQueueDepth = 4,
      minReadDelay = 3,
      maxReadDelay = 5, // Reduced for simpler simulation traces
      minWriteProcessDelay = 6, // Reduced
      maxWriteProcessDelay = 10 // Reduced
    )

    SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC),
      anonymSignalPrefix = "zz_" // Optional: for cleaner signal names if needed
    ).generateVerilog(new DelayedMemory(defaultConfig.copy(enableBypassWriteToRead = false))).printPruned()
    println("Verilog generated for DelayedMemory (Bypass/Forwarding Disabled)")

    SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC),
      anonymSignalPrefix = "zz_"
    ).generateVerilog(new DelayedMemory(defaultConfig.copy(enableBypassWriteToRead = true))).printPruned()
    println("Verilog generated for DelayedMemory (Bypass/Forwarding Enabled)")
  }
}
