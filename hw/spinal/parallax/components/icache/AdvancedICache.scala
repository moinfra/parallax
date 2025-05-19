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
// --- Configuration for Advanced ICache ---
case class AdvancedICacheConfig(
    cacheSize: BigInt = 1 KiB, // Smaller for Reg-based example
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
  require(lineCountTotal > 0, "Total line count must be positive.") // Ensure lineCountTotal > 0
  require(lineCountTotal % wayCount == 0, "Total line count must be divisible by wayCount")
  val setCount: Int = lineCountTotal / wayCount
  require(setCount > 0, "Set count must be positive.")

  val bitsPerLine: Int = bytePerLine * 8
  val wordsPerLine: Int = bytePerLine / (dataWidth.value / 8)
  require(wordsPerLine > 0, "Line size must accommodate at least one dataWidth word.")

  val fetchWordsPerFetchGroup: Int = fetchDataWidth.value / dataWidth.value
  require(bytePerLine >= (fetchDataWidth.value / 8), "Line size must be able to hold at least one full fetch group.")

  val byteOffsetWidth: BitCount = log2Up(bytePerLine) bits
  val setIndexWidth: BitCount = log2Up(setCount) bits
  val tagWidth: BitCount = addressWidth - setIndexWidth - byteOffsetWidth
  require(
    tagWidth.value > 0,
    s"Tag width must be positive. Calculated: ${tagWidth.value}. Check addressWidth (${addressWidth.value}), setIndexWidth (${setIndexWidth.value}), byteOffsetWidth (${byteOffsetWidth.value})."
  )

  val wordOffsetWidth: BitCount = log2Up(wordsPerLine) bits

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
  def getSetIndex(fullAddress: UInt): UInt = fullAddress(
    setIndexWidth.value + byteOffsetWidth.value - 1 downto byteOffsetWidth.value
  )
  def getWordOffsetInLineForFetchGroup(fullAddress: UInt): UInt = fullAddress(
    byteOffsetWidth.value - 1 downto log2Up(dataWidth.value / 8)
  )
}

// --- Cache Storage (Reg-based) ---
// Define a Bundle for a single cache line entry (tag, data, valid, age)
case class CacheLineEntry(implicit val cacheConfig: AdvancedICacheConfig) extends Bundle {
  val tag = UInt(cacheConfig.tagWidth)
  val data = Bits(cacheConfig.bitsPerLine bits)
  val valid = Bool()
  val age = if (cacheConfig.wayCount > 1) UInt(log2Up(cacheConfig.wayCount) bits) else null // Age only if associative
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

  // Create the 2D array of Registers: Vec(sets)(Vec(ways)(Reg(CacheLineEntry)))
  // Initialize all entries: tag=0, data=0, valid=False, age=LRU (e.g. wayCount-1 or 0 if wayCount=1)
  private val cacheLines = Vec.tabulate(cacheConfig.setCount) { setId =>
    Vec.tabulate(cacheConfig.wayCount) { wayId =>
      val initialEntry = CacheLineEntry()
      initialEntry.tag.assignDontCare() // Or U(0)
      initialEntry.data.assignDontCare() // Or B(0)
      initialEntry.valid := False
      if (cacheConfig.wayCount > 1) {
        // Initialize age to make way 0 MRU, way N-1 LRU initially if all become valid.
        // Or simply set all to LRU (wayCount - 1), first fills will sort it out.
        initialEntry.age := U(cacheConfig.wayCount - 1)
      }
      Reg(initialEntry).setName(s"cacheLine_set${setId}_way${wayId}")
    }
  }

  // --- Registers for FSM state and intermediate values ---
  val currentCpuAddressReg = Reg(UInt(cacheConfig.addressWidth)) init (0)
  val currentTagReg = Reg(UInt(cacheConfig.tagWidth)) init (0)
  val currentSetIdxReg = Reg(UInt(cacheConfig.setIndexWidth)) init (0)
  val currentWordOffsetInLineReg = Reg(UInt(cacheConfig.wordOffsetWidth)) init (0)

  val hitWayReg = Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0)
  val victimWayReg = Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0)

  val refillWordCounter = Reg(UInt(log2Up(cacheConfig.wordsPerLine + 1) bits)) init (0)
  val refillBuffer = Reg(Bits(cacheConfig.bitsPerLine bits)) init (0) // Still needed to assemble line from memory
  val refillErrorReg = Reg(Bool()) init (False)

  val flushSetCounter = Reg(UInt(cacheConfig.setIndexWidth)) init (0)
  val flushWayCounter = Reg(UInt(log2Up(cacheConfig.wayCount) bits)) init (0)

  val waitingForMemRspReg = Reg(Bool()) init (False)

  // --- Cache Memory Read Logic (Combinational from Regs) ---
  // The 'effectiveReadSetIdx' is currentSetIdxReg, available in the same cycle.
  // These are now direct reads from the 'cacheLines' Registers based on currentSetIdxReg.
  // The values will be used in sCompareTags.
  val ways_data_from_current_set = cacheLines(currentSetIdxReg) // Selects the Vec of ways for the current set

  // --- LRU Update Logic ---
  // This logic will be triggered in sCompareTags (on hit) or sMiss_FetchLine (on fill)
  // It directly modifies the .age field of the cacheLines Registers.
  // No separate updateLruEventReg needed as updates are direct.

  // Helper to extract fetchWordsPerFetchGroup words from a cache line
  def extractInstructionsFromLine(lineData: Bits, firstWordOffset: UInt): Vec[Bits] = {
    // Implementation remains the same
    val wordsInLine = lineData.subdivideIn(cacheConfig.dataWidth).reverse
    val extractedInstructions = Vec(Bits(cacheConfig.dataWidth), cacheConfig.fetchWordsPerFetchGroup)
    for (i <- 0 until cacheConfig.fetchWordsPerFetchGroup) {
      val currentOffsetInLine = firstWordOffset + i
      extractedInstructions(i) := wordsInLine(currentOffsetInLine.resize(cacheConfig.wordOffsetWidth))
    }
    extractedInstructions
  }

  val fsm = new StateMachine {
    // Default assignments (same as before)
    io.cpu.cmd.ready := False
    io.cpu.rsp.valid := False
    io.cpu.rsp.payload.instructions.foreach(_ := B(0))
    io.cpu.rsp.payload.fault := False
    io.cpu.rsp.payload.pc := currentCpuAddressReg

    io.mem.read.cmd.valid := False
    io.mem.read.cmd.payload.address := U(0)
    if (memBusConfig.useId) io.mem.read.cmd.payload.id := U(0)
    io.mem.read.rsp.ready := False

    io.mem.write.cmd.valid := False // ICache doesn't write to memory via this path
    io.mem.write.cmd.payload.address := U(0)
    io.mem.write.cmd.payload.data := B(0)
    io.mem.write.cmd.payload.byteEnables := B(0)
    if (memBusConfig.useId) io.mem.write.cmd.payload.id := U(0)
    io.mem.write.rsp.ready := True // Always ready to sink (ignore) write responses

    io.flush.cmd.ready := False
    io.flush.rsp.valid := False
    io.flush.rsp.payload.done := False

    // States
    val sIdle: State = new State with EntryPoint
    // sDecodeAndReadCache is effectively merged into sIdle for Reg-based storage,
    // as reads are combinational. We go directly to sCompareTags.
    // However, keeping a cycle for inputs to propagate and for complex calculations is often wise.
    // Let's make sIdle prepare inputs, then sCompareTags uses them.
    val sCompareTags: State = new State
    val sMiss_FetchLine: State = new State
    val sFlush_Invalidate: State = new State

    sIdle.whenIsActive {
      if (enableLog) report(L"AdvICache: FSM State sIdle")
      io.cpu.cmd.ready := True
      io.flush.cmd.ready := True

      when(io.flush.cmd.fire && io.flush.cmd.payload.start) {
        if (enableLog) report(L"AdvICache: sIdle - Flush command received.")
        flushSetCounter := 0
        flushWayCounter := 0
        goto(sFlush_Invalidate)
      } elsewhen (io.cpu.cmd.fire) {
        val cpuAddr = io.cpu.cmd.payload.address
        if (enableLog) report(L"AdvICache: sIdle - CPU cmd fire. Addr=${cpuAddr}")

        currentCpuAddressReg := cpuAddr
        currentTagReg := cacheConfig.getTag(cpuAddr)
        currentSetIdxReg := cacheConfig.getSetIndex(cpuAddr)
        currentWordOffsetInLineReg := cacheConfig.getWordOffsetInLineForFetchGroup(cpuAddr)

        val endAddressOfFetchGroup = cpuAddr + (cacheConfig.fetchDataWidth.value / 8) - 1
        val startLineBaseAddr = cacheConfig.getLineBaseAddress(cpuAddr)
        val endLineBaseAddr = cacheConfig.getLineBaseAddress(endAddressOfFetchGroup)

        when(startLineBaseAddr =/= endLineBaseAddr) {
          if (enableLog) report(L"AdvICache: FATAL sIdle - Fetch group crosses line boundary. Addr=${cpuAddr}")
          io.cpu.rsp.valid := True
          io.cpu.rsp.payload.fault := True
          io.cpu.rsp.payload.pc := cpuAddr
          when(!io.cpu.rsp.fire) { io.cpu.cmd.ready := False } // Stall if fault not taken
          // Stays in sIdle, CPU should re-evaluate or handle fault
        } otherwise {
          if (enableLog)
            report(
              L"AdvICache: sIdle - Addr=${cpuAddr}, Tag=${currentTagReg}, SetIdx=${currentSetIdxReg}, WordOffset=${currentWordOffsetInLineReg}. Transition to sCompareTags."
            )
          goto(sCompareTags) // Data for comparison will be read from Regs in sCompareTags
        }
      }
    }

    sCompareTags.whenIsActive {
      val setIdx = currentSetIdxReg // From previous cycle's sIdle
      val reqTag = currentTagReg // From previous cycle's sIdle
      if (enableLog) report(L"AdvICache: FSM State sCompareTags - SetIdx: ${setIdx}, ReqTag: ${reqTag}")

      val hitSignals = Vec.tabulate(cacheConfig.wayCount) { w =>
        val wayEntry = ways_data_from_current_set(w) // ways_data_from_current_set is cacheLines(setIdx)
        val wayValid = wayEntry.valid
        val tagMatch = wayEntry.tag === reqTag
        if (enableLog)
          report(
            L"AdvICache: sCompareTags - Way ${w}: ValidRAM=${wayValid}, TagRAM=${wayEntry.tag}, AgeRAM=${if (cacheConfig.wayCount > 1) wayEntry.age
              else U(0)}, TagMatch=${tagMatch}"
          )
        wayValid && tagMatch
      }
      val isHit = hitSignals.orR
      val hitWayOH = OHMasking.first(hitSignals)
      val determinedHitWay = OHToUInt(hitWayOH)

      if (enableLog) report(L"AdvICache: sCompareTags - isHit=${isHit}, determinedHitWay=${determinedHitWay}")

      when(isHit) {
        if (enableLog) report(L"AdvICache: sCompareTags - HIT in Way ${determinedHitWay}")
        hitWayReg := determinedHitWay
        io.cpu.rsp.valid := True
        // Read data directly from the hit way's register
        io.cpu.rsp.payload.instructions := extractInstructionsFromLine(
          ways_data_from_current_set(determinedHitWay).data,
          currentWordOffsetInLineReg
        )
        io.cpu.rsp.payload.fault := False

        // LRU Update on HIT
        if (cacheConfig.wayCount > 1) {
          if (enableLog)
            report(L"AdvICache: sCompareTags - Updating LRU for HIT on Way ${determinedHitWay} in Set ${setIdx}")
          val oldAgeOfHitWay = ways_data_from_current_set(determinedHitWay).age
          for (w <- 0 until cacheConfig.wayCount) {
            val currentWayEntry = ways_data_from_current_set(w) // cacheLines(setIdx)(w)
            when(w === determinedHitWay) {
              cacheLines(setIdx)(w).age := 0 // Hit way becomes MRU
            } otherwise {
              when(currentWayEntry.valid && currentWayEntry.age < oldAgeOfHitWay) {
                cacheLines(setIdx)(w).age := currentWayEntry.age + 1
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
        if (enableLog) report(L"AdvICache: sCompareTags - MISS for Set ${setIdx}, Tag ${reqTag}")
        var chosenVictimWay = U(0, log2Up(cacheConfig.wayCount) bits)
        if (cacheConfig.wayCount > 1) {
          // Simplified victim selection for Reg based:
          // Find way with age == wayCount - 1 (LRU). If multiple, first one.
          // If none are LRU (e.g. after flush), or if an invalid way exists, pick invalid first.
          val lruWayCand = UInt(log2Up(cacheConfig.wayCount) bits)
          val lruFound = Bool()
          lruWayCand := 0; lruFound := False

          val invalidWayCand = UInt(log2Up(cacheConfig.wayCount) bits)
          val invalidFound = Bool()
          invalidWayCand := 0; invalidFound := False

          for (w_idx <- (0 until cacheConfig.wayCount).reverse) { // Iterate to pick lowest index if multiple candidates
            val w_entry = ways_data_from_current_set(w_idx)
            when(!w_entry.valid) {
              invalidWayCand := U(w_idx)
              invalidFound := True
            }
            when(w_entry.valid && w_entry.age === (cacheConfig.wayCount - 1)) {
              lruWayCand := U(w_idx)
              lruFound := True
            }
          }

          when(invalidFound) { chosenVictimWay := invalidWayCand }
            .elsewhen(lruFound) { chosenVictimWay := lruWayCand }
            .otherwise { chosenVictimWay := U(0) } // Fallback, e.g. all valid, no unique LRU
        }
        victimWayReg := chosenVictimWay
        if (enableLog)
          report(L"AdvICache: sCompareTags MISS - Final Victim Way for Set ${setIdx} is ${chosenVictimWay}")

        refillWordCounter := 0
        refillBuffer := B(0)
        refillErrorReg := False
        waitingForMemRspReg := False
        goto(sMiss_FetchLine)
      }
    }

    sMiss_FetchLine.whenIsActive {
      val setIdxToFill = currentSetIdxReg // Latched from sIdle/sCompareTags
      val victimWayToFill = victimWayReg // Latched from sCompareTags
      if (enableLog)
        report(
          L"AdvICache: FSM State sMiss_FetchLine - Set ${setIdxToFill}, VictimWay ${victimWayToFill}, RefillCtr: ${refillWordCounter}, Waiting: ${waitingForMemRspReg}"
        )

      io.mem.read.cmd.valid := False
      io.mem.read.rsp.ready := False

      when(refillWordCounter < cacheConfig.wordsPerLine) {
        when(!waitingForMemRspReg) {
          val lineBaseByteAddr = cacheConfig.getLineBaseAddress(currentCpuAddressReg)
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

        if (cacheConfig.wordsPerLine > 0) {
          refillBuffer
            .subdivideIn(cacheConfig.dataWidth)
            .reverse(refillWordCounter.resize(cacheConfig.wordOffsetWidth)) := io.mem.read.rsp.payload.data
        }

        val nextRefillCounter = refillWordCounter + 1
        refillWordCounter := nextRefillCounter

        when(nextRefillCounter === cacheConfig.wordsPerLine) { // Entire line fetched
          if (enableLog) report(L"AdvICache: sMiss_FetchLine - Entire line fetched. Total Error: ${refillErrorReg}")
          when(!refillErrorReg) {
            if (enableLog)
              report(
                L"AdvICache: sMiss_FetchLine - Line OK. Writing to Cache Set ${setIdxToFill}, Way ${victimWayToFill}. Validating."
              )
            val wayToUpdate = cacheLines(setIdxToFill)(victimWayToFill)
            wayToUpdate.valid := True
            wayToUpdate.tag := currentTagReg // Tag of the missed address
            wayToUpdate.data := refillBuffer

            // LRU Update on FILL
            if (cacheConfig.wayCount > 1) {
              if (enableLog)
                report(
                  L"AdvICache: sMiss_FetchLine - Updating LRU for FILL of Way ${victimWayToFill} in Set ${setIdxToFill}"
                )
              for (w <- 0 until cacheConfig.wayCount) {
                val currentWayEntry = cacheLines(setIdxToFill)(w)
                when(w === victimWayToFill) {
                  currentWayEntry.age := U(0) // Filled way becomes MRU
                } otherwise {
                  when(currentWayEntry.valid) { // Increment age of other valid ways
                    currentWayEntry.age := currentWayEntry.age + 1
                  }
                }
              }
            }
          } otherwise {
            if (enableLog)
              report(L"AdvICache: sMiss_FetchLine - Error during line refill. Line NOT written or validated.")
          }
          goto(sIdle) // Go back to sIdle to re-evaluate the original CPU request (or next one)
          // If refill was successful, it should now hit. If error, it might fault or miss again.
        }
      }
    }

    sFlush_Invalidate.whenIsActive {
      val setIdxToFlush = flushSetCounter
      val wayIdxToFlush = flushWayCounter
      if (enableLog)
        report(L"AdvICache: FSM State sFlush_Invalidate - Flushing Set: ${setIdxToFlush}, Way: ${wayIdxToFlush}")
      io.cpu.cmd.ready := False

      cacheLines(setIdxToFlush)(wayIdxToFlush).valid := False
      if (cacheConfig.wayCount > 1) {
        cacheLines(setIdxToFlush)(wayIdxToFlush).age := U(cacheConfig.wayCount - 1) // Set to LRU
      }

      val nextFlushWay = flushWayCounter + 1
      when(nextFlushWay === cacheConfig.wayCount) {
        flushWayCounter := 0
        // 这里多给1位确保不溢出，从而 nextFlushSet === cacheConfig.setCount 判断才能可能
        val nextFlushSet = flushSetCounter.resize(log2Up(cacheConfig.setCount + 1)) + 1
        when(nextFlushSet === cacheConfig.setCount) {
          if (enableLog) report(L"AdvICache: sFlush_Invalidate - Flush completed.")
          io.flush.rsp.valid := True
          io.flush.rsp.payload.done := True
          when(io.flush.rsp.fire) {
            if (enableLog) report(L"AdvICache: sFlush_Invalidate - Flush completion ACKED.")
            goto(sIdle)
          }
        } otherwise {
          // 递增时，只取低位，最高位肯定是 0
          flushSetCounter := nextFlushSet.resize(log2Up(cacheConfig.setCount))
        }
      } otherwise {
        // 如果 wayCount = 1，则 flushWayCounter 始终为 0
        if (cacheConfig.wayCount > 1) {
          flushWayCounter := nextFlushWay
        }
      }
    }
  } // End of StateMachine
}

object AdvancedICacheRegBasedGen extends App {
  val defaultClockDomain = ClockDomain.external("clk", withReset = true)
  val clkCfg = ClockDomainConfig(clockEdge = RISING, resetKind = ASYNC, resetActiveLevel = LOW)

  SpinalConfig(
    defaultConfigForClockDomains = clkCfg,
    targetDirectory = "rtl/parallax/icache_regbased"
  ).generateVerilog({
    // Using smaller config for Reg-based to keep Verilog manageable
    implicit val cacheCfg = AdvancedICacheConfig(
      cacheSize = 256 Byte, // e.g., 256 Bytes total
      bytePerLine = 32, // 32 bytes per line => 8 lines total
      wayCount = 2, // 2-way => 4 sets
      addressWidth = 32 bits,
      dataWidth = 32 bits,
      fetchDataWidth = 64 bits
    )
    // Ensure setCount is reasonable for Reg-based: 256B / 32B/line = 8 lines. 8 lines / 2 ways = 4 sets. This is okay.

    implicit val gmbConf = GenericMemoryBusConfig(
      addressWidth = cacheCfg.addressWidth,
      dataWidth = cacheCfg.dataWidth,
      useId = true,
      idWidth = 4 bits
    )
    new AdvancedICache()(cacheCfg, gmbConf, enableLog = true)
  })

  println(s"Reg-Based AdvancedICache Verilog generated.")
}
