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
    val mem = master(SimpleMemoryBus(memBusConfig)) // NEW: Uses SimpleMemoryBus
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

  val waitingForMemRspReg = Reg(Bool()) init (False)

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

    io.flush.rsp.done := False

    // --- States ---
    val sIdle: State = new State with EntryPoint // Waiting for CPU or flush command
    val sDecodeAndReadCache: State = new State // Decode address, latches index for async RAM read
    val sCompareTag: State = new State // Cache RAM read done, compare tag
    val sMiss_FetchWord: State = new State // Fetch one word from SimpleMemoryBus
    val sFlush_Invalidate: State = new State // Handling flush request

    // --- sIdle State Logic ---
    sIdle.whenIsActive {
      io.cpu.cmd.ready := True // Ready to accept new CPU requests

      when(io.flush.cmd.valid && io.flush.cmd.payload.start) {
        report(L"ICache: sIdle - Flush command received.")

        if (cacheConfig.lineCount > 0) { // Only flush if there are cache lines
          if (cacheConfig.indexWidth > 0) flushIndexCounter := 0 else flushIndexCounter := U(0, 0 bits)
          goto(sFlush_Invalidate)
        } else { // No lines to flush, operation is instantaneously done
          io.flush.rsp.done := True
        }
      } elsewhen (io.cpu.cmd.fire) { // New CPU request
        report(L"ICache: sIdle - CPU command fired. Address: ${io.cpu.cmd.payload.address}")
        report(L"ICache: sIdle - Calculated Tag: ${cacheConfig.tag(io.cpu.cmd.payload.address)}, Index: ${cacheConfig
            .index(io.cpu.cmd.payload.address)}, WordOffset: ${cacheConfig.wordOffset(io.cpu.cmd.payload.address)}")
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
      report(L"ICache: sDecodeAndReadCache Active. Current Index for async read: ${currentIndexReg}")
      goto(sCompareTag)
    }

    // --- sCompareTag State Logic ---
    // Cache memory read results (tag, valid bit, data line) are now available.
    sCompareTag.whenIsActive {
      val tagMatch =
        if (cacheConfig.tagWidth > 0) (readTagFromMem === currentTagReg) else True // No tag means tag always matches
      val isValid = if (cacheConfig.lineCount > 0) readValidFromVec else False // No lines means nothing is valid
      val hit = tagMatch && isValid
      report(
        L"ICache: sCompareTag Active. CPU Addr: ${currentCpuAddressReg}, Req Index: ${currentIndexReg}, Req Tag: ${currentTagReg}"
      )
      report(L"ICache: sCompareTag - Read from Cache: ValidBit=${readValidFromVec}, TagFromMem=${readTagFromMem}")
      report(L"ICache: sCompareTag - Comparison: tagMatch=${tagMatch}, isValid=${isValid}, Overall Hit=${hit}")
      when(hit) { // Cache Hit
        report(L"ICache: sCompareTag - Cache HIT.")

        io.cpu.rsp.valid := True
        io.cpu.rsp.payload.instruction := extractWordFromLine(readDataLineFromMem, currentWordOffsetReg)
        // io.cpu.rsp.payload.pc is already set to currentCpuAddressReg by default
        // io.cpu.rsp.payload.fault is False by default

        when(io.cpu.rsp.fire) { // CPU accepts the instruction
          report(L"ICache: sCompareTag - HIT response fired to CPU.")

          goto(sIdle)
        }
        // If CPU is not ready (io.cpu.rsp.ready is False), FSM stalls here, io.cpu.rsp.valid remains high.
      } otherwise { // Cache Miss
        report(L"ICache: sCompareTag - Cache MISS.")

        if (cacheConfig.lineCount > 0) { // Only attempt refill if cache has place to store
          refillWordCounter := 0
          refillBuffer := B(0) // 清空 refill buffer
          waitingForMemRspReg := False 
          report(L"ICache: sCompareTag - MISS - Resetting counters and waitingForMemRspReg.")

          goto(sMiss_FetchWord) // Start fetching the line, word by word
        } else { // No cache lines to fill, this is effectively a permanent miss/error
          report(L"ICache: sCompareTag - MISS but no cache lines to fill (permanent miss/error).")

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
      report(
        L"ICache: sMiss_FetchWord Active. CPU Addr: ${currentCpuAddressReg}, RefillWordCounter: ${refillWordCounter}"
      )

      // --- 控制与内存的交互 ---
      // 默认不与内存交互，除非明确允许
      io.mem.cmd.valid := False
      io.mem.cmd.payload.address := U(0) // Default address
      io.mem.cmd.payload.isWrite := False
      io.mem.rsp.ready := False

      // 只要 refillWordCounter < wordsPerLine，我们就处于“正在填充”或“准备填充下一个字”的状态
      when(refillWordCounter < cacheConfig.wordsPerLine) {
        when(!waitingForMemRspReg) {
          // 我们需要请求或正在等待当前 refillWordCounter 指向的字
          val lineBaseByteAddress =
            (cacheConfig.lineAddress(currentCpuAddressReg) << cacheConfig.byteOffsetWidth).resized
          val currentWordByteOffsetInLine = (refillWordCounter * (cacheConfig.dataWidth / 8)).resized
          val calculatedMemAddress = lineBaseByteAddress + currentWordByteOffsetInLine

          report(
            L"ICache: sMiss_FetchWord - Target word for current cycle (based on counter ${refillWordCounter}): Addr ${calculatedMemAddress}"
          )

          // 尝试发出内存命令
          // 注意：这里没有检查是否已经为当前的 refillWordCounter 发出过命令并正在等待响应。
          // 一个简单的处理方式是，只要在这个状态并且字没满，就持续尝试发送命令。
          // 如果内存接受了 (cmd.fire)，那么很好。如果没接受，下个周期继续尝试。
          // 这种方式依赖 Stream 的握手机制。
          io.mem.cmd.valid := True
          io.mem.cmd.payload.address := calculatedMemAddress
        }
        // 无论是否发送了新命令，只要还在填充过程中，并且已经发出了命令（即 waitingForMemRspReg 为真，或者本周期即将发出命令）
        // 我们就应该准备接收响应。
        // 更准确地说，只要 waitingForMemRspReg 为真，或者本周期 cmd.fire 了，就应该准备接收。
        // 为了简单且避免环路，只要还在填充，就设置 ready。
        // 如果 cmd.valid 为 false (因为 waitingForMemRspReg 为 true), 那么 rsp.ready 即使为 true 也只是空等。
        io.mem.rsp.ready := True
      }

      // --- 处理内存命令发送成功 ---
      when(io.mem.cmd.fire) { // cmd.valid 为高 (由上面的逻辑设置) 且 mem 接受了 (cmd.ready 为高)
        report(
          L"ICache: sMiss_FetchWord - Memory command fired for Addr: ${io.mem.cmd.payload.address} (word ${refillWordCounter}). Setting waitingForMemRspReg = True."
        )
        waitingForMemRspReg := True // 命令已发出，开始等待响应
        // 注意：发出命令后，我们通常会停留在当前状态等待响应，或者如果有流水线，FSM 可能不用等待。
        // 在这个简单的 FSM 中，我们停留在 sMiss_FetchWord。
      }

      // --- 处理内存响应接收成功 ---
      when(io.mem.rsp.fire) { // rsp.valid 为高 (内存发来数据) 且 rsp.ready 为高 (我们设置的)
        report(
          L"ICache: sMiss_FetchWord - Memory response received. Data: ${io.mem.rsp.payload.readData}, RefillCounter was: ${refillWordCounter}. Setting waitingForMemRspReg = False."
        )
        waitingForMemRspReg := False // 响应已收到，不再等待这个字的响应

        // 将收到的数据存入 refillBuffer 的正确位置
        // 使用当前的 refillWordCounter 作为索引，因为这个响应对应的是这个计数器值的请求
        if (cacheConfig.bitsPerLine > 0 && cacheConfig.dataWidth > 0) {
          refillBuffer
            .subdivideIn(cacheConfig.dataWidth bits)
            .reverse(refillWordCounter.resize(cacheConfig.wordOffsetWidth)) := io.mem.rsp.payload.readData
        }

        // 收到一个字的响应后，准备获取下一个字
        val nextRefillCounter = refillWordCounter + 1
        refillWordCounter := nextRefillCounter // 寄存器在周期结束时更新

        report(L"ICache: sMiss_FetchWord - RefillWordCounter will be ${nextRefillCounter} next cycle.")

        // 检查是否所有字都已接收完毕 (基于 *将要更新为* 的 nextRefillCounter)
        when(nextRefillCounter === cacheConfig.wordsPerLine) {
          report(L"ICache: sMiss_FetchWord - All words for the line have been received (based on nextRefillCounter).")
          tagWriteEnable := True
          dataWriteEnable := True
          if (cacheConfig.lineCount > 0) validArray(effectiveWriteIndex) := True

          report(L"ICache: sMiss_FetchWord - Transitioning to sDecodeAndReadCache.")
          goto(sDecodeAndReadCache)
          // 注意：当 goto 发生时，当前状态的后续逻辑（包括 io.mem.cmd.valid 和 io.mem.rsp.ready 的赋值）
          // 在下一个周期就不再是 sMiss_FetchWord 的逻辑了。
          // sDecodeAndReadCache 状态应该有自己的 io.mem.* 信号控制（默认为False）。
        } otherwise {
          report(L"ICache: sMiss_FetchWord - More words needed for the line.")
          // 自动停留在 sMiss_FetchWord 状态，
          // 上面的 when(refillWordCounter < cacheConfig.wordsPerLine) 会在下一个周期
          // 使用更新后的 refillWordCounter 继续尝试发送 cmd 和设置 rsp.ready。
        }
      }
    }

    // --- sFlush_Invalidate State Logic ---
    // Invalidate all cache lines.
    sFlush_Invalidate.whenIsActive {
      report(L"ICache: sFlush_Invalidate Active. Flushing Index: ${flushIndexCounter}")

      io.cpu.cmd.ready := False // Stall CPU requests during flush

      if (cacheConfig.lineCount > 0) {
        // Invalidate the cache line pointed to by flushIndexCounter
        validArray(flushIndexCounter.resize(cacheConfig.indexWidth)) := False

        // Check if this is the last line to invalidate
        when(flushIndexCounter === (cacheConfig.lineCount - 1)) {
          report(L"ICache: sFlush_Invalidate - Flush completed.")

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
