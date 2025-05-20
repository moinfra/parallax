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
  require(cacheSize % bytePerLine == 0, "cacheSize must be a multiple of lineSize")

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

  // Important: setIndexWidth is 0 if setCount is 1
  val setIndexWidth: BitCount = if (setCount == 1) 0 bits else log2Up(setCount) bits

  val tagWidth: BitCount = addressWidth - setIndexWidth - byteOffsetWidth
  require(
    tagWidth.value > 0,
    s"Tag width must be positive. Calculated: ${tagWidth.value}. Check addressWidth (${addressWidth.value}), setIndexWidth (${setIndexWidth.value}), byteOffsetWidth (${byteOffsetWidth.value})."
  )

  // Important: wordOffsetWidth is 0 if wordsPerLine is 1
  val wordOffsetWidth: BitCount = if (wordsPerLine == 1) 0 bits else log2Up(wordsPerLine) bits

  // Print config (unchanged, but values will reflect 0 if calculated)
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
  def getLineBaseAddress(fullAddress: BigInt): BigInt = (fullAddress >> byteOffsetWidth.value) << byteOffsetWidth.value
  def getTag(fullAddress: UInt): UInt = fullAddress(
    addressWidth.value - 1 downto setIndexWidth.value + byteOffsetWidth.value
  )
  def getSetIndex(fullAddress: UInt): UInt = {
    // If setIndexWidth is 0, the set index is always 0.
    // We don't need to extract bits if the width is 0.
    if (setIndexWidth.value == 0) U(0).resized // Return 0-width U(0) implicitly
    else fullAddress(setIndexWidth.value + byteOffsetWidth.value - 1 downto byteOffsetWidth.value)
  }
  def getWordOffsetInLineForFetchGroup(fullAddress: UInt): UInt = {
    // If wordOffsetWidth is 0, the word offset is always 0.
    // We don't need to extract bits if the width is 0.
    if (wordOffsetWidth.value == 0) U(0).resized // Return 0-width U(0) implicitly
    else {
      val wordSizeInBytesLog2 = log2Up(dataWidth.value / 8)
      // Ensure LSB of slice is not greater than MSB for 0-width.
      // wordOffset is relative to start of line, in units of dataWidth words.
      // If byteOffsetWidth <= wordSizeInBytesLog2, the word offset is always 0 within the line base address.
      if (byteOffsetWidth.value <= wordSizeInBytesLog2) {
        U(0, wordOffsetWidth) // Offset must be 0 if line is not larger than one word.
      } else {
        fullAddress(byteOffsetWidth.value - 1 downto wordSizeInBytesLog2) // word offset part of byte offset
      }
    }
  }
}

case class CacheLineEntry(implicit val cacheConfig: AdvancedICacheConfig) extends Bundle {
  val tag = UInt(cacheConfig.tagWidth) // tagWidth is required > 0
  val data = Bits(cacheConfig.bitsPerLine bits)
  val valid = Bool()
  // Age is instantiated only if wayCount is > 1
  val age: UInt = if (cacheConfig.wayCount > 1) UInt(log2Up(cacheConfig.wayCount) bits) else null
}

class AdvancedICache(implicit
    val cacheConfig: AdvancedICacheConfig,
    val memBusConfig: GenericMemoryBusConfig,
    val enableLog: Boolean = false
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

  // Conditionally instantiate registers based on configuration
  val currentSetIdxReg = if (hasMultipleSets) Reg(UInt(cacheConfig.setIndexWidth)) init (0) else null
  val currentWordOffsetInLineReg = if (lineHasMultipleWords) Reg(UInt(cacheConfig.wordOffsetWidth)) init (0) else null

  // hitWayReg and victimWayReg are instantiated only if associative
  val hitWayReg = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null
  val victimWayReg = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null

  // refillWordCounter width is log2Up(wordsPerLine + 1).
  // Since wordsPerLine >= 1, wordsPerLine+1 >= 2. So log2Up >= 1. This Reg is never 0-width.
  val refillWordCounter = Reg(UInt(log2Up(cacheConfig.wordsPerLine + 1) bits)) init (0)
  val refillBuffer = Reg(Bits(cacheConfig.bitsPerLine bits)) init (0)
  val refillErrorReg = Reg(Bool()) init (False)

  // flush counters are instantiated only if needed
  val flushSetCounter = if (hasMultipleSets) Reg(UInt(cacheConfig.setIndexWidth)) init (0) else null
  val flushWayCounter = if (isAssociative) Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0) else null

  val waitingForMemRspReg = Reg(Bool()) init (False)

  // Get the current set index. If hasMultipleSets is false, the index is always 0.
  // We don't need a 0-width signal here, just use the integer 0 for indexing the Vec of sets.
  def getCurrentSetIdx: UInt = if (hasMultipleSets) currentSetIdxReg else U(0)

  // Get the current word offset in line. If lineHasMultipleWords is false, the offset is always 0.
  // We don't need a 0-width signal here, just use the integer 0 for indexing.
  def getCurrentWordOffsetInLine: UInt = if (lineHasMultipleWords) currentWordOffsetInLineReg else U(0)

  // Get the victim way. If not associative, the victim is always way 0.
  def getVictimWay: UInt = if (isAssociative) victimWayReg else U(0)

  // Get the flush set counter value.
  def getFlushSetCounter: UInt = if (hasMultipleSets) flushSetCounter else U(0)

  // Get the flush way counter value.
  def getFlushWayCounter: UInt = if (isAssociative) flushWayCounter else U(0)

  // Access cacheLines using the integer set index.
  val ways_data_from_current_set = cacheLines(getCurrentSetIdx)

  def extractInstructionsFromLine(lineData: Bits, firstWordOffsetInt: UInt): Vec[Bits] = {
    if (enableLog) report(L"extractInstructionsFromLine ${lineData} offset ${firstWordOffsetInt}")
    val wordsInLine = lineData.subdivideIn(cacheConfig.dataWidth) // Vec[Bits]
    val extractedInstructions = Vec(Bits(cacheConfig.dataWidth), cacheConfig.fetchWordsPerFetchGroup)

    for (i <- 0 until cacheConfig.fetchWordsPerFetchGroup) {
      val currentOffsetInLineInt = firstWordOffsetInt + i
      // Access wordsInLine using the integer offset.
      extractedInstructions(i) := wordsInLine(currentOffsetInLineInt)
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
    val sWaitingFaultAck: State = new State // New state

    sIdle.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sIdle")
      io.cpu.cmd.ready := True
      io.flush.cmd.ready := True
      io.cpu.rsp.valid := False // Default not sending response

      when(io.flush.cmd.fire && io.flush.cmd.payload.start) {
        if (enableLog) report(L"AdvICache: sIdle - Flush command received.")
        io.cpu.cmd.ready := False // Stop receiving CPU commands
        if (hasMultipleSets) flushSetCounter := U(0)
        if (isAssociative) flushWayCounter := U(0)
        goto(sFlush_Invalidate)
      } elsewhen (io.cpu.cmd.fire) { // cmd.fire is based on sIdle default cmd.ready = True
        val cpuAddr = io.cpu.cmd.payload.address

        if (enableLog) {
          report(L"AdvICache: sIdle - CPU cmd fire.")
          report(L"  - Addr=${cpuAddr}")
        }

        // Latch address and calculated tag/set/offset, as sWaitingFaultAck or sCompareTags may need them
        currentCpuAddressReg := cpuAddr
        currentTagReg := cacheConfig.getTag(cpuAddr)

        if (hasMultipleSets) {
          currentSetIdxReg := cacheConfig.getSetIndex(cpuAddr)
        }
        if (lineHasMultipleWords) {
          currentWordOffsetInLineReg := cacheConfig.getWordOffsetInLineForFetchGroup(cpuAddr)
        }

        val endAddressOfFetchGroup = cpuAddr + (cacheConfig.fetchDataWidth.value / 8) - 1
        val startLineBaseAddr = cacheConfig.getLineBaseAddress(cpuAddr)
        val endLineBaseAddr = cacheConfig.getLineBaseAddress(endAddressOfFetchGroup)

        when(startLineBaseAddr =/= endLineBaseAddr) { // Fault condition

          if (enableLog) {
            report(L"AdvICache: FATAL sIdle - Fetch group crosses line boundary.")
            report(L"  - Addr=${cpuAddr}")
          }

          io.cpu.rsp.valid := True // Issue fault response
          io.cpu.rsp.payload.fault := True
          io.cpu.rsp.payload.pc := cpuAddr
          goto(sWaitingFaultAck) // Transition to waiting state
        } otherwise { // Non-fault path

          if (enableLog) {
            report(L"AdvICache: sIdle - Non-fault path.")
            report(L"  - Addr=${cpuAddr}")
            report(L"  - Tag=${currentTagReg}")
            if (hasMultipleSets) report(L"  - SetIdx=${currentSetIdxReg}")
            if (lineHasMultipleWords) report(L"  - WordOffset=${currentWordOffsetInLineReg}")
            report(L"  - Transition to sCompareTags.")
          }

          // cmd.ready remains True (from sIdle default)
          // rsp.valid remains False (from sIdle default)
          goto(sCompareTags)
        }
      }
    }

    sWaitingFaultAck.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sWaitingFaultAck")
      // Maintain fault response
      io.cpu.rsp.valid := True
      io.cpu.rsp.payload.fault := True
      io.cpu.rsp.payload.pc := currentCpuAddressReg // Use previously latched address

      // Do not accept new commands
      io.cpu.cmd.ready := False

      when(io.cpu.rsp.fire) { // CPU has acknowledged the fault
        if (enableLog) report(L"AdvICache: sWaitingFaultAck - Fault response ACKED by CPU. Transitioning to sIdle.")
        goto(sIdle)
      } otherwise {
        if (enableLog) report(L"AdvICache: sWaitingFaultAck - Waiting for CPU to ACK fault response.")
      }
    }

    sCompareTags.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sCompareTags")
      val setIdxForLookup = getCurrentSetIdx // Uses the integer value
      val reqTag = currentTagReg

      if (enableLog) {
        report(L"AdvICache: FSM State sCompareTags")
        if (hasMultipleSets) report(L"  - SetIdx: ${currentSetIdxReg}") else report(L"  - SetIdx: 0 (Single Set)")
        report(L"  - ReqTag: ${reqTag}")
      }

      // ways_data_from_current_set is already using the integer set index
      val hitSignals = Vec.tabulate(cacheConfig.wayCount) { w =>
        val wayEntry = ways_data_from_current_set(w)
        val wayValid = wayEntry.valid
        val tagMatch = wayEntry.tag === reqTag

        if (enableLog) {
          report(L"AdvICache: sCompareTags - Way ${w.toString()}:")
          report(L"  - ValidRAM=${wayValid}")
          report(L"  - TagRAM=${wayEntry.tag}")
          if (isAssociative) report(L"  - AgeRAM=${wayEntry.age}")
          report(L"  - TagMatch=${tagMatch}")
        }

        wayValid && tagMatch
      }
      val isHit = hitSignals.orR
      val hitWayOH = OHMasking.first(hitSignals)
      // determinedHitWay will be U(0) if wayCount=1, which is correct for indexing a 1-element Vec.
      val determinedHitWay = OHToUInt(hitWayOH)

      if (false) {
        report(L"AdvICache: sCompareTags - isHit=${isHit}")
        report(L"  - determinedHitWay=${determinedHitWay}")
      }

      when(isHit) {

        if (false) {
          report(L"AdvICache: sCompareTags - HIT.")
          report(L"  - Way ${determinedHitWay}")
        }

        if (isAssociative) hitWayReg := determinedHitWay // Assign to Reg only if it exists

        io.cpu.rsp.valid := True
        io.cpu.rsp.payload.instructions := extractInstructionsFromLine(
          ways_data_from_current_set(determinedHitWay).data, // Use determinedHitWay
          getCurrentWordOffsetInLine // Use integer offset
        )
        io.cpu.rsp.payload.fault := False

        if (isAssociative) { // LRU Update on HIT

          if (false) {
            report(L"AdvICache: sCompareTags - Updating LRU for HIT.")
            report(L"  - Way ${determinedHitWay}")
            if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}") else report(L"  - Set 0 (Single Set)")
          }

          val oldAgeOfHitWay = ways_data_from_current_set(determinedHitWay).age
          for (w <- 0 until cacheConfig.wayCount) {
            val currentWayEntry = ways_data_from_current_set(w)
            // victimWayToFill will be U(0,0 bits) if !isAssociative. U(w) will be U(0,0 bits) if w=0. Comparison is fine.
            when(U(w) === determinedHitWay) {
              currentWayEntry.age := U(0).resized
            } otherwise {
              when(currentWayEntry.valid && currentWayEntry.age < oldAgeOfHitWay) {
                currentWayEntry.age := currentWayEntry.age + 1
              }
            }
          }
        }
        // log all data fetched
        if (enableLog) {
          for (i <- 0 until cacheConfig.fetchWordsPerFetchGroup) {
            report(L"AdvICache: sCompareTags - Instruction ${i.toString()}: ${io.cpu.rsp.payload.instructions(i)}")
          }
        }
        when(io.cpu.rsp.fire) {
          if (enableLog) report(L"AdvICache: sCompareTags - HIT response fired to CPU.")
          goto(sIdle)
        } otherwise {
          if (enableLog) report(L"AdvICache: sCompareTags - HIT response STALLED by CPU.")
        }

      } otherwise { // Cache Miss

        if (enableLog) {
          report(L"AdvICache: sCompareTags - MISS.")
          if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}") else report(L"  - Set 0 (Single Set)")
          report(L"  - Tag ${reqTag}")
        }

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
            when(w_entry.valid && w_entry.age === U(cacheConfig.wayCount - 1)) {
              lruWayCand := U(w_idx)
              lruFound := True
            }
          }

          val chosenVictimWayAssociative = UInt(log2Up(cacheConfig.wayCount) bits)
          when(invalidFound) { chosenVictimWayAssociative := invalidWayCand }
            .elsewhen(lruFound) { chosenVictimWayAssociative := lruWayCand }
            .otherwise { chosenVictimWayAssociative := U(0) } // Fallback

          if (enableLog) {
            report(L"AdvICache: sCompareTags MISS - Evaluating ways for victim selection:")
            for (w_idx <- 0 until cacheConfig.wayCount) {
              val w_entry = ways_data_from_current_set(w_idx)
              report(L"  - Way ${w_idx.toString()}: Valid=${w_entry.valid}, Age=${w_entry.age}")
            }
            report(L"  - invalidFound=${invalidFound}, invalidWayCand=${invalidWayCand}")
            report(L"  - lruFound=${lruFound}, lruWayCand=${lruWayCand}")
            report(L"  - Chosen victim (before reg assignment): ${chosenVictimWayAssociative}")
          }
          victimWayReg := chosenVictimWayAssociative
          if (enableLog) {
            report(L"  - victimWayReg will be updated to: ${chosenVictimWayAssociative}") // D input
            report(L"  - current victimWayReg (Q output): ${victimWayReg}") // Q output
          }

          if (enableLog) {
            report(L"AdvICache: sCompareTags MISS - Final Victim Way.")
            if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}") else report(L"  - Set 0 (Single Set)")
            report(L"  - Victim Way ${victimWayReg}")
          }

        } else { // Direct mapped, victim is always way 0
          // victimWayReg is null, no assignment needed. getVictimWay will provide 0.

          if (enableLog) {
            report(L"AdvICache: sCompareTags MISS - Victim Way for Set ${if (hasMultipleSets) currentSetIdxReg
              else "0".toString()} is 0 (direct mapped)")
          }

        }

        refillWordCounter := 0
        refillBuffer := B(0)
        refillErrorReg := False
        waitingForMemRspReg := False
        goto(sMiss_FetchLine)
      }
    }

    sMiss_FetchLine.whenIsActive {
      val setIdxToFill = getCurrentSetIdx
      val victimWayToFill = getVictimWay // Use integer value

      if (enableLog) {
        report(L"AdvICache: FSM State sMiss_FetchLine ")
        if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}, ") else report(L"  - Set 0 (Single Set), ")
        if (isAssociative) report(L"  - VictimWay ${victimWayReg}, ") else report(L"  - VictimWay 0 (Direct Mapped), ")
        report(L"  - RefillCtr: ${refillWordCounter}, Waiting: ${waitingForMemRspReg}")
      }

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

          if (enableLog) {
            report(L"AdvICache: sMiss_FetchLine - Requesting word from Mem.")
            report(L"  - Word ${refillWordCounter}")
            report(L"  - Addr=${memAccessAddress}")
          }

        }
        io.mem.read.rsp.ready := True
      }

      when(io.mem.read.cmd.fire) {

        if (enableLog) {
          report(L"AdvICache: sMiss_FetchLine - Memory read cmd FIRED.")
          report(L"  - Word ${refillWordCounter}.")
        }

        waitingForMemRspReg := True
      }

      when(io.mem.read.rsp.fire) {

        if (enableLog) {
          report(L"AdvICache: sMiss_FetchLine - Memory read rsp RECEIVED.")
          report(L"  - Word ${refillWordCounter}.")
          report(L"  - Data=${io.mem.read.rsp.payload.data}")
          report(L"  - Error=${io.mem.read.rsp.payload.error}")
        }

        waitingForMemRspReg := False
        refillErrorReg := refillErrorReg || io.mem.read.rsp.payload.error

        // wordOffsetWidth is used here implicitly by .resize
        // if wordsPerLine is 1, wordOffsetWidth is 0. refillWordCounter.resize(0) is U(0,0 bits). Correct.
        if (enableLog) report(L"AdvICache: sMiss_FetchLine - Before update cacheline: ${refillBuffer}.")
        val wordIdxInLine = refillWordCounter.resize(cacheConfig.wordOffsetWidth)
        val wordsInRefillBuffer = refillBuffer.subdivideIn(cacheConfig.dataWidth)
        val nextRefillBuffer = Vec(Bits(cacheConfig.dataWidth), cacheConfig.wordsPerLine)

        for(i <- 0 until cacheConfig.wordsPerLine) {
          nextRefillBuffer(i) := Mux(U(i) === wordIdxInLine, io.mem.read.rsp.payload.data, wordsInRefillBuffer(i))
        }

        refillBuffer := nextRefillBuffer.asBits

        val nextRefillCounter = refillWordCounter + 1
        refillWordCounter := nextRefillCounter

        when(nextRefillCounter === U(cacheConfig.wordsPerLine)) { // Compare with UInt

          if (enableLog) {
            report(L"AdvICache: sMiss_FetchLine - Entire line fetched.")
            report(L"  - nextRefillCounter: ${nextRefillCounter}")
            report(L"  - Total Error: ${refillErrorReg}")
            report(L"  - New Cacheline: ${nextRefillBuffer.asBits}")
          }

          when(!refillErrorReg) {

            if (enableLog) {
              report(L"AdvICache: sMiss_FetchLine - Line OK. Writing to Cache.")
              if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}") else report(L"  - Set 0 (Single Set)")
              if (isAssociative) report(L"  - Way ${victimWayReg}") else report(L"  - Way 0 (Direct Mapped)")
              report(L"  - Validating.")
            }

            val wayToUpdate = cacheLines(setIdxToFill)(victimWayToFill) // Indexing with integer values
            wayToUpdate.valid := True
            wayToUpdate.tag := currentTagReg
            wayToUpdate.data := nextRefillBuffer.asBits

            report(L"AdvICache: sMiss_FetchLine - Line written to Cache.")
            if (isAssociative) { // LRU Update on FILL

              if (enableLog) {
                report(L"AdvICache: sMiss_FetchLine - Updating LRU for FILL.")
                if (isAssociative) report(L"  - Way ${victimWayReg}") else report(L"  - Way 0 (Direct Mapped)")
                if (hasMultipleSets) report(L"  - Set ${currentSetIdxReg}") else report(L"  - Set 0 (Single Set)")
              }

              for (w <- 0 until cacheConfig.wayCount) {
                val currentWayEntry = cacheLines(setIdxToFill)(w)
                // victimWayToFill will be U(0,0 bits) if !isAssociative. U(w) will be U(0,0 bits) if w=0. Comparison is fine.
                when(U(w) === victimWayToFill) {
                  currentWayEntry.age := U(0).resized
                } otherwise {
                  when(currentWayEntry.valid) {
                    currentWayEntry.age := currentWayEntry.age + 1
                  }
                }
              }
            }
            // ---- FIX: Transition to sCompareTags to serve CPU ----
            if (enableLog) report(L"AdvICache: sMiss_FetchLine - Successful refill, transitioning to sCompareTags.")
            goto(sCompareTags)
            // --------------------------------------------------------
          } otherwise { // Refill error
            // ---- FIX: Signal fault to CPU ----
            if (enableLog) {
              report(L"AdvICache: sMiss_FetchLine - Error during line refill. Line NOT written/validated.")
              report(L"AdvICache: sMiss_FetchLine - Signalling fault to CPU. Transitioning to sWaitingFaultAck.")
            }
            io.cpu.rsp.valid := True
            io.cpu.rsp.payload.fault := True
            io.cpu.rsp.payload.pc := currentCpuAddressReg
            goto(sWaitingFaultAck)
            // ------------------------------------
          }
        } otherwise { // Line fetch not yet complete (nextRefillCounter < wordsPerLine)
          // refillWordCounter has been updated. FSM stays in sMiss_FetchLine.
          // waitingForMemRspReg is False, so next mem req will be issued in the next cycle.
          if (enableLog) report(L"AdvICache: sMiss_FetchLine - More words to fetch for the line.")
        }
      }
    }

    sFlush_Invalidate.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sFlush_Invalidate")
      val setIdxToFlush = getFlushSetCounter
      val wayIdxToFlush = getFlushWayCounter

      if (enableLog) {
        report(L"AdvICache: FSM State sFlush_Invalidate - Flushing.")
        if (hasMultipleSets) report(L"  - Set: ${flushSetCounter}") else report(L"  - Set: 0")
        if (isAssociative) report(L"  - Way: ${flushWayCounter}") else report(L"  - Way: 0")
      }

      io.cpu.cmd.ready := False

      cacheLines(setIdxToFlush)(wayIdxToFlush).valid := False
      if (isAssociative) {
        cacheLines(setIdxToFlush)(wayIdxToFlush).age := U(cacheConfig.wayCount - 1)
      }

      // Way increment logic
      val nextFlushWayNum = wayIdxToFlush.resize(log2Up(cacheConfig.wayCount) + 1) + 1

      when(nextFlushWayNum === cacheConfig.wayCount) { // If !isAssociative, wayCount=1. 1 === 1 is true.
        if (isAssociative) flushWayCounter := U(0) // Reset Reg only if it exists

        // Set increment logic
        // Use effFlushSetCounter for current value, resize for comparison
        val nextFlushSetNum =
          if (hasMultipleSets) flushSetCounter.resize(log2Up(cacheConfig.setCount + 1)) + 1
          else U(1).resize(log2Up(cacheConfig.setCount + 1))

        when(nextFlushSetNum === cacheConfig.setCount) { // If !hasMultipleSets, setCount=1. U(1,1bit) === 1 is true.
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
            flushSetCounter := nextFlushSetNum.resize(cacheConfig.setIndexWidth)
          }
        }
      } otherwise { // More ways in current set to flush (only if isAssociative)
        if (isAssociative) { // Assign to Reg only if it exists (and this branch only taken if isAssociative)
          flushWayCounter := nextFlushWayNum.resize(log2Up(cacheConfig.wayCount))
        }
      }
    }
  }
}
