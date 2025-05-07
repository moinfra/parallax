package boson.demo2.components.icache

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import boson.demo2.components.memory._

class SimpleICache(implicit
    val cacheConfig: SimpleICacheConfig,
    val memBusConfig: GenericMemoryBusConfig // Configuration for its memory-side bus
) extends Component {
  require(
    cacheConfig.dataWidth == memBusConfig.dataWidth,
    "ICache dataWidth must match the memory bus dataWidth for this direct mapping."
  )
  require(
    cacheConfig.addressWidth == memBusConfig.addressWidth,
    "ICache addressWidth must match the memory bus addressWidth."
  )

  val io = new Bundle {
    val cpu = slave(ICacheCpuBus()) // Uses cacheConfig
    val mem = master(GenericMemoryBus(memBusConfig)) // NEW: Uses GenericMemoryBus
    val flush = slave(ICacheFlushBus()) // Uses its own simple flush bus
  }

  // --- Cache Memory Declarations ---
  // Tag array: stores the tag for each line
  // Assuming config.tagWidth > 0. If tagWidth is 0, Mem(UInt(0 bits), ...) is problematic.
  // SimpleICacheConfig should ensure a valid configuration.
  val tagArray = Mem(UInt(cacheConfig.tagWidth bits), cacheConfig.lineCount)

  // Valid array: stores the valid bit for each line
  val validArray = Vec(Reg(Bool()) init (False), cacheConfig.lineCount)

  // Data array: stores the actual cache lines
  val dataArray = Mem(Bits(cacheConfig.bitsPerLine bits), cacheConfig.lineCount)

  // --- Registers for FSM state and intermediate values ---
  // These registers hold the properties of the CPU request being processed.
  val currentCpuAddressReg = Reg(UInt(cacheConfig.addressWidth bits)) init (0)
  val currentTagReg = Reg(UInt(cacheConfig.tagWidth bits)) init (0)
  val currentIndexReg = Reg(UInt(cacheConfig.indexWidth bits)) init (0) // Width can be 0 if lineCount is 1
  val currentWordOffsetReg = Reg(UInt(cacheConfig.wordOffsetWidth bits)) init (0) // Width can be 0 if wordsPerLine is 1

  // Counter for words received during a line refill
  val refillWordCounter = Reg(UInt(log2Up(cacheConfig.wordsPerLine + 1) bits)) init (0) // Counts 0 to wordsPerLine
  // Buffer to assemble a cache line during refill
  val refillBuffer = Reg(Bits(cacheConfig.bitsPerLine bits)) init (0)

  // Counter for cache lines during a flush operation
  val flushIndexCounter = Reg(UInt(cacheConfig.indexWidth bits)) init (0) // Width can be 0 if lineCount is 1

  // --- Cache Memory Read Logic ---
  // Determine the effective index for memory reads. Handles 0-width index for single-line cache.
  val effectiveReadIndex = if (cacheConfig.indexWidth > 0) currentIndexReg else U(0, 0 bits)

  // Asynchronously read tag, valid status, and data line from cache arrays.
  // Results are available in the cycle *after* the address (effectiveReadIndex) is stable.
  val readTagFromMem =
    if (cacheConfig.tagWidth > 0) tagArray.readAsync(effectiveReadIndex) else U(0, cacheConfig.tagWidth bits)
  val readValidFromVec = if (cacheConfig.lineCount > 0) validArray(effectiveReadIndex) else False
  val readDataLineFromMem = dataArray.readAsync(effectiveReadIndex)

  // --- Cache Memory Write Logic ---
  val tagWriteEnable = Bool().setAsReg() init (False) // Registered enable for tag memory write
  val dataWriteEnable = Bool().setAsReg() init (False) // Registered enable for data memory write
  // validArray is written directly: validArray(index) := value

  // Determine effective index for memory writes.
  val effectiveWriteIndex = if (cacheConfig.indexWidth > 0) currentIndexReg else U(0, 0 bits)

  when(tagWriteEnable) {
    if (cacheConfig.tagWidth > 0) tagArray.write(address = effectiveWriteIndex, data = currentTagReg)

  }

  when(dataWriteEnable) {
    dataArray.write(
      address = effectiveWriteIndex,
      data = refillBuffer
    )
  }
  // Reset write enables at the beginning of each cycle (unless FSM sets them)
  tagWriteEnable := False
  dataWriteEnable := False

  // Helper to extract a specific word from a full cache line
  def extractWordFromLine(line: Bits, wordOffset: UInt): Bits = {
    line.subdivideIn(cacheConfig.dataWidth bits).reverse(wordOffset.resize(cacheConfig.wordOffsetWidth))
  }

  // --- State Machine Definition ---
  val fsm = new StateMachine {
    // Default assignments for I/O signals
    io.cpu.cmd.ready := False
    io.cpu.rsp.valid := False
    io.cpu.rsp.payload.instruction := B(0)
    io.cpu.rsp.payload.fault := False
    io.cpu.rsp.payload.pc := currentCpuAddressReg // Echo back the PC of the original request

    io.mem.cmd.valid := False
    io.mem.cmd.payload.address := 0 // Needs to be set correctly in miss states
    io.mem.cmd.payload.isWrite := False // ICache only reads from main memory for fills
    io.mem.cmd.payload.writeData := B(0) // Not used for reads
    io.mem.rsp.ready := False

    // --- States ---
    val sIdle: State = new State with EntryPoint // Waiting for CPU or flush command
    val sDecodeAndReadCache: State = new State // Decode address, latches index for async RAM read
    val sCompareTag: State = new State // Cache RAM read done, compare tag
    val sMiss_FetchWord: State = new State // Fetch one word from GenericMemoryBus
    val sFlush_Invalidate: State = new State // Handling flush request

    // --- sIdle State Logic ---
    sIdle.whenIsActive {
      io.cpu.cmd.ready := True // Ready to accept new CPU requests

      when(io.flush.cmd.valid && io.flush.cmd.payload.start) {
        if (cacheConfig.lineCount > 0) { // Only flush if there are cache lines
          if (cacheConfig.indexWidth > 0) flushIndexCounter := 0 else flushIndexCounter := U(0, 0 bits)
          goto(sFlush_Invalidate)
        } else { // No lines to flush, operation is instantaneously done
          io.flush.rsp.done := True
        }
      } elsewhen (io.cpu.cmd.fire) { // New CPU request
        currentCpuAddressReg := io.cpu.cmd.payload.address
        // Pre-calculate and store tag, index, offset for the current request
        if (cacheConfig.tagWidth > 0) currentTagReg := cacheConfig.tag(io.cpu.cmd.payload.address)
        if (cacheConfig.indexWidth > 0) currentIndexReg := cacheConfig.index(io.cpu.cmd.payload.address)
        else currentIndexReg := U(0, 0 bits)
        if (cacheConfig.wordOffsetWidth > 0) currentWordOffsetReg := cacheConfig.wordOffset(io.cpu.cmd.payload.address)
        else currentWordOffsetReg := U(0, 0 bits)

        goto(sDecodeAndReadCache) // Proceed to read cache memories
      }
    }

    // --- sDecodeAndReadCache State Logic ---
    // This state effectively acts as the first cycle of cache memory access.
    // The currentIndexReg (set in sIdle) is used by readAsync ports of tagArray and dataArray.
    // Their outputs (readTagFromMem, readDataLineFromMem) will be valid in the *next* cycle (sCompareTag).
    sDecodeAndReadCache.whenIsActive {
      goto(sCompareTag)
    }

    // --- sCompareTag State Logic ---
    // Cache memory read results (tag, valid bit, data line) are now available.
    sCompareTag.whenIsActive {
      val tagMatch =
        if (cacheConfig.tagWidth > 0) (readTagFromMem === currentTagReg) else True // No tag means tag always matches
      val isValid = if (cacheConfig.lineCount > 0) readValidFromVec else False // No lines means nothing is valid
      val hit = tagMatch && isValid

      when(hit) { // Cache Hit
        io.cpu.rsp.valid := True
        io.cpu.rsp.payload.instruction := extractWordFromLine(readDataLineFromMem, currentWordOffsetReg)
        // io.cpu.rsp.payload.pc is already set to currentCpuAddressReg by default
        // io.cpu.rsp.payload.fault is False by default

        when(io.cpu.rsp.fire) { // CPU accepts the instruction
          goto(sIdle)
        }
        // If CPU is not ready (io.cpu.rsp.ready is False), FSM stalls here, io.cpu.rsp.valid remains high.
      } otherwise { // Cache Miss
        if (cacheConfig.lineCount > 0) { // Only attempt refill if cache has place to store
          goto(sMiss_FetchWord) // Start fetching the line, word by word
        } else { // No cache lines to fill, this is effectively a permanent miss/error
          io.cpu.rsp.valid := True
          io.cpu.rsp.payload.fault := True // Signal fault
          when(io.cpu.rsp.fire) {
            goto(sIdle)
          }
        }
      }
    }

     // Fetches one word (cacheConfig.dataWidth) for the cache line from main memory.
    sMiss_FetchWord.whenIsActive {
      io.mem.cmd.valid := True
      // Calculate byte address of the current word to fetch for the line
      val lineBaseByteAddress = (cacheConfig.lineAddress(currentCpuAddressReg) << cacheConfig.byteOffsetWidth).resized
      val currentWordByteOffsetInLine = (refillWordCounter * (cacheConfig.dataWidth / 8)).resized
      io.mem.cmd.payload.address := lineBaseByteAddress + currentWordByteOffsetInLine
      io.mem.cmd.payload.isWrite := False // Always reading for ICache fill

      io.mem.rsp.ready := True // Ready to accept response

      when(io.mem.cmd.fire) { // Memory accepted the request for a word
        // Now wait for response in this same state, or move to a wait state if mem has latency
        // For simplicity with GenericMemoryBus being pipelined, we can expect rsp in a few cycles.
      }

      when(io.mem.rsp.fire) { // A word is received from memory
        // Place the received word into the correct position in the refillBuffer.
        if (cacheConfig.bitsPerLine > 0 && cacheConfig.dataWidth > 0) {
          refillBuffer
            .subdivideIn(cacheConfig.dataWidth bits)
            .reverse(refillWordCounter.resize(cacheConfig.wordOffsetWidth)) := io.mem.rsp.payload.readData
          // Check for memory error (optional, depends on GenericMemoryBus providing it)
          // if(io.mem.rsp.payload.error) { /* handle error, e.g. fault CPU */ }
        }
        refillWordCounter := refillWordCounter + 1

        when(refillWordCounter === cacheConfig.wordsPerLine) { // All words for the line received
          tagWriteEnable := True
          dataWriteEnable := True
          if (cacheConfig.lineCount > 0) validArray(effectiveWriteIndex) := True
          goto(sDecodeAndReadCache) // Re-evaluate, should be a hit now
        } otherwise {
          // Stay in sMiss_FetchWord to request the next word of the line.
          // The io.mem.cmd.valid will be asserted again with the updated address.
        }
      }
      // If neither cmd.fire nor rsp.fire, FSM stalls here, io.mem.cmd.valid remains high.
    }

    // --- sFlush_Invalidate State Logic ---
    // Invalidate all cache lines.
    sFlush_Invalidate.whenIsActive {
      io.cpu.cmd.ready := False // Stall CPU requests during flush

      if (cacheConfig.lineCount > 0) {
        // Invalidate the cache line pointed to by flushIndexCounter
        validArray(flushIndexCounter.resize(cacheConfig.indexWidth)) := False

        // Check if this is the last line to invalidate
        when(flushIndexCounter === (cacheConfig.lineCount - 1)) {
          io.flush.rsp.done := True // Signal flush completion
          // flushIndexCounter will be reset by sIdle if a new flush starts.
          goto(sIdle)
        } otherwise {
          flushIndexCounter := flushIndexCounter + 1 // Move to the next line
        }
      } else { // Should ideally not reach here if sIdle checks lineCount > 0
        io.flush.rsp.done := True
        goto(sIdle)
      }
    }
  } // End of StateMachine
}
