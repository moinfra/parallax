package parallax.components.icache

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.components.memory.{GenericMemoryBusConfig, SplitGenericMemoryBus}

case class AdvancedICacheCpuCmd(implicit config: AdvancedICacheConfig) extends Bundle {
  val address = UInt(config.addressWidth)
}

case class AdvancedICacheCpuRsp(implicit config: AdvancedICacheConfig) extends Bundle {
  val instructions = Vec(Bits(config.dataWidth), config.fetchWordsPerFetchGroup)
  val fault = Bool()
  val pc = UInt(config.addressWidth)
}

case class AdvancedICacheCpuBus(implicit config: AdvancedICacheConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(AdvancedICacheCpuCmd())
  val rsp = Stream(AdvancedICacheCpuRsp())
  override def asMaster(): Unit = { master(cmd); slave(rsp) }
}

case class AdvancedICacheFlushCmd() extends Bundle {
  val start = Bool()
}
case class AdvancedICacheFlushRsp() extends Bundle {
  val done = Bool()
}
case class AdvancedICacheFlushBus() extends Bundle with IMasterSlave {
  val cmd = Stream(AdvancedICacheFlushCmd())
  val rsp = Stream(AdvancedICacheFlushRsp())
  override def asMaster(): Unit = { master(cmd); slave(rsp) }
}
package parallax.components.icache

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.components.memory.{GenericMemoryBusConfig, SplitGenericMemoryBus}

// ... (AdvancedICacheCpuCmd, AdvancedICacheCpuRsp, AdvancedICacheCpuBus, AdvancedICacheFlushCmd, AdvancedICacheFlushRsp, AdvancedICacheFlushBus remain unchanged) ...
// ... (AdvancedICacheConfig remains largely unchanged, ensure requires are robust)

case class AdvancedICacheConfig(
    cacheSize: BigInt = 1 KiB,
    bytePerLine: Int = 32,
    wayCount: Int = 2,
    addressWidth: BitCount = 32 bits,
    dataWidth: BitCount = 32 bits,
    fetchDataWidth: BitCount = 64 bits
) {
  require(isPow2(bytePerLine), "Bytes per line must be a power of 2")
  require(wayCount >= 1, "Way count must be at least 1.")
  if (wayCount > 1) require(isPow2(wayCount), "Way count (if >1) must be a power of 2 for this LRU.")

  require(fetchDataWidth.value % dataWidth.value == 0, "fetchDataWidth must be a multiple of dataWidth")
  require(fetchDataWidth.value >= dataWidth.value, "fetchDataWidth must be at least dataWidth")
  require(cacheSize > 0, "cacheSize must be positive.")
  require(bytePerLine > 0, "bytePerLine must be positive.")
  require(cacheSize % bytePerLine == 0, "cacheSize must be a multiple of bytePerLine")

  val lineCountTotal: Int = cacheSize.toInt / bytePerLine
  require(lineCountTotal > 0, "Total line count must be positive.")
  require(lineCountTotal % wayCount == 0, "Total line count must be divisible by wayCount")
  val setCount: Int = lineCountTotal / wayCount
  require(setCount > 0, "Set count must be positive.")

  val bitsPerLine: Int = bytePerLine * 8
  val wordsPerLine: Int = bytePerLine / (dataWidth.value / 8)
  require(wordsPerLine > 0, "Line size must accommodate at least one dataWidth word.")

  val fetchWordsPerFetchGroup: Int = fetchDataWidth.value / dataWidth.value
  require(bytePerLine >= (fetchDataWidth.value / 8), "Line size must be able to hold at least one full fetch group.")

  val byteOffsetWidth: BitCount = log2Up(bytePerLine) bits
  
  // Important: Ensure setIndexWidth is 0 if setCount is 1, handle this in calculations
  val setIndexWidth: BitCount = if (setCount == 1) 0 bits else log2Up(setCount) bits
  
  val tagWidth: BitCount = addressWidth - setIndexWidth - byteOffsetWidth
  require(
    tagWidth.value > 0,
    s"Tag width must be positive. Calculated: ${tagWidth.value}. Check addressWidth (${addressWidth.value}), setIndexWidth (${setIndexWidth.value}), byteOffsetWidth (${byteOffsetWidth.value})."
  )

  // Important: Ensure wordOffsetWidth is 0 if wordsPerLine is 1
  val wordOffsetWidth: BitCount = if (wordsPerLine == 1) 0 bits else log2Up(wordsPerLine) bits
  
  // Print config (unchanged)
  println(s"--- 指令缓存配置 ---")
  println(s"缓存大小: ${cacheSize}")
  println(s"每行字节数: ${bytePerLine}")
  println(s"路数: ${wayCount}")
  println(s"地址宽度: ${addressWidth}")
  println(s"数据宽度: ${dataWidth}")
  println(s"取指数据宽度: ${fetchDataWidth}")
  println(s"总行数: ${lineCountTotal}")
  println(s"组数: ${setCount}")
  println(s"每行位数: ${bitsPerLine}")
  println(s"每行字数: ${wordsPerLine}")
  println(s"每个取指组的字数: ${fetchWordsPerFetchGroup}")
  println(s"字节偏移宽度: ${byteOffsetWidth}")
  println(s"组索引宽度: ${setIndexWidth}")
  println(s"标签宽度: ${tagWidth}")
  println(s"字偏移宽度: ${wordOffsetWidth}")
  println(s"--------------------")


  def getLineBaseAddress(fullAddress: UInt): UInt = (fullAddress >> byteOffsetWidth.value) << byteOffsetWidth.value
  def getTag(fullAddress: UInt): UInt = fullAddress(
    addressWidth.value - 1 downto setIndexWidth.value + byteOffsetWidth.value
  )
  def getSetIndex(fullAddress: UInt): UInt = {
    if (setCount == 1) {
      U(0, 0 bits) // If only one set, index is always 0 and 0-bits wide
    } else {
      fullAddress(setIndexWidth.value + byteOffsetWidth.value - 1 downto byteOffsetWidth.value)
    }
  }
  def getWordOffsetInLineForFetchGroup(fullAddress: UInt): UInt = {
    if (wordsPerLine == 1 && fetchWordsPerFetchGroup == 1) { // Simplified case: if line holds one word, and we fetch one word
        U(0, 0 bits)
    } else {
        // Ensure LSB of slice is not greater than MSB for 0-width.
        // log2Up(dataWidth.value / 8) is the width of the byte-in-word offset.
        // wordOffset is relative to start of line, in units of dataWidth words.
        val wordSizeInBytesLog2 = log2Up(dataWidth.value / 8)
        if (byteOffsetWidth.value <= wordSizeInBytesLog2) { // e.g. bytePerLine <= wordSize
             U(0, wordOffsetWidth) // Offset must be 0 if line is not larger than one word.
        } else {
             fullAddress(byteOffsetWidth.value - 1 downto wordSizeInBytesLog2) bits // word offset part of byte offset
        }
    }
  }
}


case class CacheLineEntry(implicit val cacheConfig: AdvancedICacheConfig) extends Bundle {
  val tag = UInt(cacheConfig.tagWidth) // tagWidth is required > 0
  val data = Bits(cacheConfig.bitsPerLine bits)
  val valid = Bool()
  // Age is null if wayCount is 1 (direct-mapped), otherwise UInt of appropriate width
  val age = if (cacheConfig.wayCount > 1) UInt(log2Up(cacheConfig.wayCount) bits) else null
}

class AdvancedICache(implicit
    val cacheConfig: AdvancedICacheConfig,
    val memBusConfig: GenericMemoryBusConfig,
    val enableLog: Boolean = true
) extends Component {
  require(
    cacheConfig.dataWidth == memBusConfig.dataWidth,
    "AdvancedICache config.dataWidth must match memBusConfig.dataWidth for refills."
  )
  require(
    cacheConfig.addressWidth == memBusConfig.addressWidth,
    "AdvancedICache config.addressWidth must match memBusConfig.addressWidth."
  )

  val io = new Bundle {
    val cpu = slave(AdvancedICacheCpuBus())
    val mem = master(SplitGenericMemoryBus(memBusConfig))
    val flush = slave(AdvancedICacheFlushBus())
  }

  // Helper booleans for conditional hardware generation
  val isAssociative = cacheConfig.wayCount > 1
  val hasMultipleSets = cacheConfig.setCount > 1
  val lineHasMultipleWords = cacheConfig.wordsPerLine > 1 // If false, wordsPerLine is 1, wordOffsetWidth is 0.

  private val cacheLines = Vec.tabulate(cacheConfig.setCount) { setId =>
    Vec.tabulate(cacheConfig.wayCount) { wayId =>
      val initialEntry = CacheLineEntry()
      initialEntry.tag.assignDontCare()
      initialEntry.data.assignDontCare()
      initialEntry.valid := False
      if (isAssociative) { // Only use age if associative
        initialEntry.age := U(cacheConfig.wayCount - 1)
      }
      Reg(initialEntry).setName(s"cacheLine_set${setId}_way${wayId}")
    }
  }

  // --- Registers for FSM state and intermediate values ---
  val currentCpuAddressReg = Reg(UInt(cacheConfig.addressWidth)) init (0)
  val currentTagReg = Reg(UInt(cacheConfig.tagWidth)) init (0) // tagWidth must be > 0 by config require

  val currentSetIdxReg = if (hasMultipleSets) Reg(UInt(cacheConfig.setIndexWidth)) init (0) else null
  val currentWordOffsetInLineReg = if (lineHasMultipleWords) Reg(UInt(cacheConfig.wordOffsetWidth)) init (0) else null

  // hitWayReg is arguably not strictly needed if hit data is consumed in the same cycle it's determined.
  // However, to fix the 0-bit issue if it were kept:
  val hitWayReg = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null
  val victimWayReg = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null

  // refillWordCounter width is log2Up(wordsPerLine + 1).
  // Since wordsPerLine >= 1, wordsPerLine+1 >= 2. So log2Up >= 1. This Reg is never 0-width.
  val refillWordCounter = Reg(UInt(log2Up(cacheConfig.wordsPerLine + 1) bits)) init (0)
  val refillBuffer = Reg(Bits(cacheConfig.bitsPerLine bits)) init (0)
  val refillErrorReg = Reg(Bool()) init (False)

  val flushSetCounter = if (hasMultipleSets) Reg(UInt(cacheConfig.setIndexWidth)) init (0) else null
  val flushWayCounter = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null

  val waitingForMemRspReg = Reg(Bool()) init (False)

  // Effective values for indices/counters that might not have a register
  def effCurrentSetIdx: UInt = if (hasMultipleSets) currentSetIdxReg else U(0, 0 bits)
  def effCurrentWordOffsetInLine: UInt = if (lineHasMultipleWords) currentWordOffsetInLineReg else U(0, 0 bits)
  def effVictimWay: UInt = if (isAssociative) victimWayReg else U(0, 0 bits)
  def effFlushSetCounter: UInt = if (hasMultipleSets) flushSetCounter else U(0, 0 bits)
  def effFlushWayCounter: UInt = if (isAssociative) flushWayCounter else U(0, 0 bits)


  val ways_data_from_current_set = cacheLines(effCurrentSetIdx)

  def extractInstructionsFromLine(lineData: Bits, firstWordOffset: UInt): Vec[Bits] = {
    val wordsInLine = lineData.subdivideIn(cacheConfig.dataWidth).reverse // Vec[Bits]
    val extractedInstructions = Vec(Bits(cacheConfig.dataWidth), cacheConfig.fetchWordsPerFetchGroup)
    
    // wordOffsetWidth is 0 if wordsPerLine is 1. Resize to 0 is fine.
    val wordOffsetResizeTarget = cacheConfig.wordOffsetWidth 

    for (i <- 0 until cacheConfig.fetchWordsPerFetchGroup) {
      // firstWordOffset will be U(0,0 bits) if lineHasSingleWord.
      // Adding U(i) will result in U(i, appropriate_width).
      val currentOffsetInLineCalculated = firstWordOffset + U(i)
      // Resize to the actual wordOffsetWidth. If wordsPerLine is 1, width is 0, so result is U(0,0 bits).
      val currentOffsetResized = currentOffsetInLineCalculated.resize(wordOffsetResizeTarget)
      extractedInstructions(i) := wordsInLine(currentOffsetResized)
    }
    extractedInstructions
  }

  val fsm = new StateMachine {
    // ... (Default assignments remain similar) ...
    io.cpu.cmd.ready := False
    io.cpu.rsp.valid := False
    io.cpu.rsp.payload.instructions.foreach(_ := B(0))
    io.cpu.rsp.payload.fault := False
    io.cpu.rsp.payload.pc := currentCpuAddressReg

    io.mem.read.cmd.valid := False
    io.mem.read.cmd.payload.address := U(0)
    if (memBusConfig.useId) io.mem.read.cmd.payload.id := U(0)
    io.mem.read.rsp.ready := False

    io.mem.write.cmd.valid := False 
    io.mem.write.cmd.payload.address := U(0)
    io.mem.write.cmd.payload.data := B(0)
    io.mem.write.cmd.payload.byteEnables := B(0)
    if (memBusConfig.useId) io.mem.write.cmd.payload.id := U(0)
    io.mem.write.rsp.ready := True 

    io.flush.cmd.ready := False
    io.flush.rsp.valid := False
    io.flush.rsp.payload.done := False

    val sIdle: State = new State with EntryPoint
    val sCompareTags: State = new State
    val sMiss_FetchLine: State = new State
    val sFlush_Invalidate: State = new State

    sIdle.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sIdle")
      io.cpu.cmd.ready := True
      io.flush.cmd.ready := True

      when(io.flush.cmd.fire && io.flush.cmd.payload.start) {
        if (enableLog) report(L"AdvICache: sIdle - Flush command received.")
        if (hasMultipleSets) flushSetCounter := U(0)
        if (isAssociative) flushWayCounter := U(0)
        goto(sFlush_Invalidate)
      } elsewhen (io.cpu.cmd.fire) {
        val cpuAddr = io.cpu.cmd.payload.address
        if (enableLog) report(L"AdvICache: sIdle - CPU cmd fire. Addr=${cpuAddr}")

        currentCpuAddressReg := cpuAddr
        currentTagReg := cacheConfig.getTag(cpuAddr)
        
        val newSetIdx = cacheConfig.getSetIndex(cpuAddr)
        if (hasMultipleSets) currentSetIdxReg := newSetIdx
        
        val newWordOffset = cacheConfig.getWordOffsetInLineForFetchGroup(cpuAddr)
        if (lineHasMultipleWords) currentWordOffsetInLineReg := newWordOffset

        val endAddressOfFetchGroup = cpuAddr + (cacheConfig.fetchDataWidth.value / 8) - 1
        val startLineBaseAddr = cacheConfig.getLineBaseAddress(cpuAddr)
        val endLineBaseAddr = cacheConfig.getLineBaseAddress(endAddressOfFetchGroup)

        when(startLineBaseAddr =/= endLineBaseAddr) {
          if (enableLog) report(L"AdvICache: FATAL sIdle - Fetch group crosses line boundary. Addr=${cpuAddr}")
          io.cpu.rsp.valid := True
          io.cpu.rsp.payload.fault := True
          io.cpu.rsp.payload.pc := cpuAddr
          when(!io.cpu.rsp.fire) { io.cpu.cmd.ready := False }
        } otherwise {
          if (enableLog)
            report(
              L"AdvICache: sIdle - Addr=${cpuAddr}, Tag=${cacheConfig.getTag(cpuAddr)}, SetIdx=${newSetIdx}, WordOffset=${newWordOffset}. Transition to sCompareTags."
            )
          goto(sCompareTags)
        }
      }
    }

    sCompareTags.whenIsActive {
      val setIdxForLookup = effCurrentSetIdx // Uses the effective value
      val reqTag = currentTagReg
      if (enableLog) report(L"AdvICache: FSM State sCompareTags - SetIdx: ${setIdxForLookup}, ReqTag: ${reqTag}")

      // ways_data_from_current_set is already using effCurrentSetIdx
      val hitSignals = Vec.tabulate(cacheConfig.wayCount) { w =>
        val wayEntry = ways_data_from_current_set(w)
        val wayValid = wayEntry.valid
        val tagMatch = wayEntry.tag === reqTag
        if (enableLog) {
            val ageReport = if (isAssociative) wayEntry.age else U(0) // Handle null age for reporting
            report(L"AdvICache: sCompareTags - Way ${w}: ValidRAM=${wayValid}, TagRAM=${wayEntry.tag}, AgeRAM=${ageReport}, TagMatch=${tagMatch}")
        }
        wayValid && tagMatch
      }
      val isHit = hitSignals.orR
      val hitWayOH = OHMasking.first(hitSignals)
      // determinedHitWay will be U(0,0 bits) if wayCount=1, which is correct for indexing a 1-element Vec.
      val determinedHitWay = OHToUInt(hitWayOH) 

      if (enableLog) report(L"AdvICache: sCompareTags - isHit=${isHit}, determinedHitWay=${determinedHitWay}")

      when(isHit) {
        if (enableLog) report(L"AdvICache: sCompareTags - HIT in Way ${determinedHitWay}")
        if (isAssociative) hitWayReg := determinedHitWay // Assign to Reg only if it exists

        io.cpu.rsp.valid := True
        io.cpu.rsp.payload.instructions := extractInstructionsFromLine(
          ways_data_from_current_set(determinedHitWay).data, // Use determinedHitWay directly
          effCurrentWordOffsetInLine // Use effective value
        )
        io.cpu.rsp.payload.fault := False

        if (isAssociative) { // LRU Update on HIT
          if (enableLog)
            report(L"AdvICache: sCompareTags - Updating LRU for HIT on Way ${determinedHitWay} in Set ${setIdxForLookup}")
          val oldAgeOfHitWay = ways_data_from_current_set(determinedHitWay).age
          for (w <- 0 until cacheConfig.wayCount) {
            val currentWayEntry = ways_data_from_current_set(w)
            when(U(w) === determinedHitWay) { // Compare with U(w) of appropriate width
              currentWayEntry.age := U(0)
            } otherwise {
              when(currentWayEntry.valid && currentWayEntry.age < oldAgeOfHitWay) {
                currentWayEntry.age := currentWayEntry.age + 1
              }
            }
          }
        }

        when(io.cpu.rsp.fire) {
          if (enableLog) report(L"AdvICache: sCompareTags - HIT response fired to CPU.")
          goto(sIdle)
        } otherwise {
          if (enableLog) report(L"AdvICache: sCompareTags - HIT response STALLED by CPU.")
        }

      } otherwise { // Cache Miss
        if (enableLog) report(L"AdvICache: sCompareTags - MISS for Set ${setIdxForLookup}, Tag ${reqTag}")
        
        if (isAssociative) {
          val lruWayCand = UInt(log2Up(cacheConfig.wayCount) bits)
          val lruFound = Bool()
          lruWayCand := U(0); lruFound := False

          val invalidWayCand = UInt(log2Up(cacheConfig.wayCount) bits)
          val invalidFound = Bool()
          invalidWayCand := U(0); invalidFound := False

          // Iterate to pick lowest index if multiple candidates
          for (w_idx <- (0 until cacheConfig.wayCount).reverse) { 
            val w_entry = ways_data_from_current_set(w_idx)
            when(!w_entry.valid) {
              invalidWayCand := U(w_idx)
              invalidFound := True
            }
            // age is only valid if isAssociative, which is true in this block
            when(w_entry.valid && w_entry.age === (cacheConfig.wayCount - 1)) {
              lruWayCand := U(w_idx)
              lruFound := True
            }
          }
          
          val chosenVictimWayAssociative = UInt(log2Up(cacheConfig.wayCount) bits)
          when(invalidFound) { chosenVictimWayAssociative := invalidWayCand }
          .elsewhen(lruFound) { chosenVictimWayAssociative := lruWayCand }
          .otherwise { chosenVictimWayAssociative := U(0) } // Fallback
          
          victimWayReg := chosenVictimWayAssociative 
          if (enableLog)
            report(L"AdvICache: sCompareTags MISS - Final Victim Way for Set ${setIdxForLookup} is ${chosenVictimWayAssociative}")
        } else { // Direct mapped, victim is always way 0
            // victimWayReg is null, no assignment needed. effVictimWay will provide U(0,0 bits).
            if (enableLog)
                report(L"AdvICache: sCompareTags MISS - Victim Way for Set ${setIdxForLookup} is 0 (direct mapped)")
        }

        refillWordCounter := 0
        refillBuffer := B(0)
        refillErrorReg := False
        waitingForMemRspReg := False
        goto(sMiss_FetchLine)
      }
    }

    sMiss_FetchLine.whenIsActive {
      val setIdxToFill = effCurrentSetIdx
      val victimWayToFill = effVictimWay // Use effective value, U(0,0 bits) if direct-mapped
      
      if (enableLog)
        report(
          L"AdvICache: FSM State sMiss_FetchLine - Set ${setIdxToFill}, VictimWay ${victimWayToFill}, RefillCtr: ${refillWordCounter}, Waiting: ${waitingForMemRspReg}"
        )

      io.mem.read.cmd.valid := False
      io.mem.read.rsp.ready := False

      when(refillWordCounter < cacheConfig.wordsPerLine) {
        when(!waitingForMemRspReg) {
          val lineBaseByteAddr = cacheConfig.getLineBaseAddress(currentCpuAddressReg)
          // refillWordCounter is at least 1 bit wide.
          val currentWordByteOffsetInLine = (refillWordCounter * (cacheConfig.dataWidth.value / 8)).resized
          val memAccessAddress = lineBaseByteAddr + currentWordByteOffsetInLine
          io.mem.read.cmd.valid := True
          io.mem.read.cmd.payload.address := memAccessAddress
          if (memBusConfig.useId) io.mem.read.cmd.payload.id := U(0)
          if (enableLog)
            report(
              L"AdvICache: sMiss_FetchLine - Requesting word ${refillWordCounter} from Mem Addr=${memAccessAddress}"
            )
        }
        io.mem.read.rsp.ready := True
      }

      when(io.mem.read.cmd.fire) {
        if (enableLog) report(L"AdvICache: sMiss_FetchLine - Memory read cmd FIRED for word ${refillWordCounter}.")
        waitingForMemRspReg := True
      }

      when(io.mem.read.rsp.fire) {
        if (enableLog)
          report(
            L"AdvICache: sMiss_FetchLine - Memory read rsp RECEIVED for word ${refillWordCounter}. Data=${io.mem.read.rsp.payload.data}, Error=${io.mem.read.rsp.payload.error}"
          )
        waitingForMemRspReg := False
        refillErrorReg := refillErrorReg || io.mem.read.rsp.payload.error

        if (cacheConfig.wordsPerLine > 0) { // Should always be true due to require
          // wordOffsetWidth is used here implicitly by .resize
          // if wordsPerLine is 1, wordOffsetWidth is 0. refillWordCounter.resize(0) is U(0,0 bits). Correct.
          val wordIdxInLine = refillWordCounter.resize(cacheConfig.wordOffsetWidth)
          refillBuffer.subdivideIn(cacheConfig.dataWidth).reverse(wordIdxInLine) := io.mem.read.rsp.payload.data
        }

        val nextRefillCounter = refillWordCounter + 1
        refillWordCounter := nextRefillCounter

        when(nextRefillCounter === cacheConfig.wordsPerLine) {
          if (enableLog) report(L"AdvICache: sMiss_FetchLine - Entire line fetched. Total Error: ${refillErrorReg}")
          when(!refillErrorReg) {
            if (enableLog)
              report(
                L"AdvICache: sMiss_FetchLine - Line OK. Writing to Cache Set ${setIdxToFill}, Way ${victimWayToFill}. Validating."
              )
            val wayToUpdate = cacheLines(setIdxToFill)(victimWayToFill) // Indexing with effective values
            wayToUpdate.valid := True
            wayToUpdate.tag := currentTagReg
            wayToUpdate.data := refillBuffer

            if (isAssociative) { // LRU Update on FILL
              if (enableLog)
                report(
                  L"AdvICache: sMiss_FetchLine - Updating LRU for FILL of Way ${victimWayToFill} in Set ${setIdxToFill}"
                )
              for (w <- 0 until cacheConfig.wayCount) {
                val currentWayEntry = cacheLines(setIdxToFill)(w)
                // victimWayToFill will be U(0,0 bits) if !isAssociative. U(w) will be U(0,0 bits) if w=0. Comparison is fine.
                when(U(w) === victimWayToFill) { 
                  currentWayEntry.age := U(0)
                } otherwise {
                  when(currentWayEntry.valid) {
                    currentWayEntry.age := currentWayEntry.age + 1
                  }
                }
              }
            }
          } otherwise {
            if (enableLog)
              report(L"AdvICache: sMiss_FetchLine - Error during line refill. Line NOT written or validated.")
          }
          goto(sIdle)
        }
      }
    }

    sFlush_Invalidate.whenIsActive {
      val setIdxToFlush = effFlushSetCounter
      val wayIdxToFlush = effFlushWayCounter
      if (enableLog)
        report(L"AdvICache: FSM State sFlush_Invalidate - Flushing Set: ${setIdxToFlush}, Way: ${wayIdxToFlush}")
      io.cpu.cmd.ready := False

      cacheLines(setIdxToFlush)(wayIdxToFlush).valid := False
      if (isAssociative) {
        cacheLines(setIdxToFlush)(wayIdxToFlush).age := U(cacheConfig.wayCount - 1)
      }

      // Way increment logic
      val nextFlushWayNum = wayIdxToFlush + 1 // wayIdxToFlush is U(0,0 bits) if !isAssociative. +1 becomes U(1,1 bit)
      
      when(nextFlushWayNum === cacheConfig.wayCount) { // If !isAssociative, wayCount=1. U(1,1bit) === 1 is true.
        if (isAssociative) flushWayCounter := U(0) // Reset Reg only if it exists
        
        // Set increment logic
        // Use effFlushSetCounter for current value, resize for comparison
        val nextFlushSetNumForComp = effFlushSetCounter.resize(log2Up(cacheConfig.setCount + 1)) + 1
        
        when(nextFlushSetNumForComp === cacheConfig.setCount) { // If !hasMultipleSets, setCount=1. U(1,1bit) === 1 is true.
          if (enableLog) report(L"AdvICache: sFlush_Invalidate - Flush completed.")
          io.flush.rsp.valid := True
          io.flush.rsp.payload.done := True
          when(io.flush.rsp.fire) {
            if (enableLog) report(L"AdvICache: sFlush_Invalidate - Flush completion ACKED.")
            goto(sIdle)
          }
        } otherwise { // More sets to flush
          if (hasMultipleSets) { // Assign to Reg only if it exists
            // Truncate back to original width for storage
            flushSetCounter := nextFlushSetNumForComp.resize(cacheConfig.setIndexWidth)
          }
        }
      } otherwise { // More ways in current set to flush (only if isAssociative)
        if (isAssociative) { // Assign to Reg only if it exists (and this branch only taken if isAssociative)
          flushWayCounter := nextFlushWayNum 
        }
      }
    }
  }
}

// ... (AdvancedICacheRegBasedGen object remains unchanged) ...
object AdvancedICacheRegBasedGen extends App {
  val defaultClockDomain = ClockDomain.external("clk", withReset = true)
  val clkCfg = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW)

  SpinalConfig(
    defaultConfigForClockDomains = clkCfg,
    targetDirectory = "rtl/parallax/icache_regbased"
  ).generateVerilog({
    implicit val cacheCfg = AdvancedICacheConfig(
      cacheSize = 256 Byte, 
      bytePerLine = 32, 
      wayCount = 2, // Test with 2 ways (isAssociative = true)
      addressWidth = 32 bits,
      dataWidth = 32 bits,
      fetchDataWidth = 64 bits
    )
    // setCount = (256/32)/2 = 4. hasMultipleSets = true
    // wordsPerLine = 32 / (32/8) = 8. lineHasMultipleWords = true.
    // All registers should be generated with this config.

    implicit val gmbConf = GenericMemoryBusConfig(
      addressWidth = cacheCfg.addressWidth,
      dataWidth = cacheCfg.dataWidth,
      useId = true,
      idWidth = 4 bits
    )
    new AdvancedICache()(cacheCfg, gmbConf, enableLog = true)
  })
  
  println(s"Reg-Based AdvancedICache (wayCount=2, setCount=4) Verilog generated.")

  SpinalConfig(
    defaultConfigForClockDomains = clkCfg,
    targetDirectory = "rtl/parallax/icache_regbased_directmapped_singleset"
  ).generateVerilog({
    // Test configuration that would generate 0-width registers
    implicit val cacheCfgDirectSingleSet = AdvancedICacheConfig(
      cacheSize = 32 Byte,     // Small cache
      bytePerLine = 32,        // Line = 32 bytes
      wayCount = 1,            // Direct-mapped (isAssociative = false)
      addressWidth = 32 bits,
      dataWidth = 32 bits,     // dataWidth = 32 bits
      fetchDataWidth = 32 bits // fetch one word at a time
    )
    // lineCountTotal = 32/32 = 1.
    // setCount = 1/1 = 1. (hasMultipleSets = false)
    // wordsPerLine = 32 / (32/8) = 8. (lineHasMultipleWords = true)
    // wordOffsetWidth = log2Up(8) = 3 bits. currentWordOffsetInLineReg exists.
    // victimWayReg, hitWayReg, flushWayCounter, age field -> should be 'null' or logic adapted
    // currentSetIdxReg, flushSetCounter -> should be 'null' or logic adapted

    implicit val gmbConfDirect = GenericMemoryBusConfig(
      addressWidth = cacheCfgDirectSingleSet.addressWidth,
      dataWidth = cacheCfgDirectSingleSet.dataWidth,
      useId = false // Example
    )
    new AdvancedICache()(cacheCfgDirectSingleSet, gmbConfDirect, enableLog = true)
  })
  println(s"Reg-Based AdvancedICache (direct-mapped, single-set) Verilog generated to test 0-bit avoidance.")

  SpinalConfig(
    defaultConfigForClockDomains = clkCfg,
    targetDirectory = "rtl/parallax/icache_regbased_singlewordline"
  ).generateVerilog({
    implicit val cacheCfgSingleWordLine = AdvancedICacheConfig(
      cacheSize = 64 Byte,     
      bytePerLine = 4,        // Line = 4 bytes (equals dataWidth)
      wayCount = 2,           // 2-way (isAssociative = true)
      addressWidth = 32 bits,
      dataWidth = 32 bits,    // dataWidth = 32 bits (4 bytes)
      fetchDataWidth = 32 bits 
    )
    // wordsPerLine = 4 / (32/8) = 1. (lineHasMultipleWords = false)
    // wordOffsetWidth = 0 bits. currentWordOffsetInLineReg should be 'null' / adapted.
    // lineCountTotal = 64/4 = 16 lines.
    // setCount = 16/2 = 8 sets. (hasMultipleSets = true)

    implicit val gmbConfSWL = GenericMemoryBusConfig(
      addressWidth = cacheCfgSingleWordLine.addressWidth,
      dataWidth = cacheCfgSingleWordLine.dataWidth
    )
    new AdvancedICache()(cacheCfgSingleWordLine, gmbConfSWL, enableLog = true)
  })
  println(s"Reg-Based AdvancedICache (single-word-line) Verilog generated to test 0-bit wordOffsetWidth avoidance.")
}
