package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.ParallaxLogger
import parallax.issue._

case class AluIntIQComponentIo(config: PipelineConfig, depth: Int) extends Bundle with IMasterSlave {
  val allocateIn = Flow(IQEntryAluInt(config))
  val canAccept = Bool()
  val issueOut = Stream(IQEntryAluInt(config))
  val bypassIn = Vec(Flow(BypassMessage(config)), config.bypassNetworkSources)
  val flush = Bool()

  override def asMaster(): Unit = {
    master(allocateIn)
    out(bypassIn)
    out(flush)
    in(canAccept)
    slave(issueOut)
  }
}

class AluIntIQComponent(val config: PipelineConfig, val depth: Int, val id: Int = 0) extends Component {
  val io = slave(AluIntIQComponentIo(config, depth))
  val idStr = id.toString()
  // --- Sanity Checks ---
  require(depth > 0, "AluIntIQ depth must be positive")
  require(
    isPow2(depth) || depth < 8,
    "AluIntIQ depth should ideally be a power of 2 or small for simpler logic/potential future pointer use."
  )

  // --- Internal Storage for IQ Entries ---
  // 注意这些都存在一个周期的延迟
  val entries = Vec.fill(depth)(Reg(IQEntryAluInt(config)))
  val entryValids = Vec.fill(depth)(Reg(Bool()) init (False))

  // --- Allocation Logic ---
  val freeSlotOh = OHMasking.first(entryValids.map(!_))
  val hasFreeSlot = freeSlotOh.orR
  val allocateIdx = OHToUInt(freeSlotOh)

  io.canAccept := hasFreeSlot && !io.flush // Cannot accept if being flushed

  when(io.allocateIn.valid && hasFreeSlot && !io.flush) {
    entries(allocateIdx) := io.allocateIn.payload
    entryValids(allocateIdx) := True
    entries(allocateIdx).isReadyToIssue := False
    report(L"AluIntIQ-${idStr}: Allocated entry at index ${allocateIdx}, ROBIdx=${io.allocateIn.payload.robIdx}")
  }

  // --- Wake-up Logic & Ready to Issue Evaluation ---
  // This block replaces original lines 61-91.
  // Original lines 61-63 (val srcXWokenThisCycle = False) are effectively removed.
  for (i <- 0 until depth) {
    when(entryValids(i)) {
      val currentEntry = entries(i)

      // --- Wake up for Source 1 ---
      // Determine if src1 is woken by any bypass port this cycle.
      // The accumulator for foldLeft (`s1AlreadyWokenByPriorPort`) is True if a higher-priority port already woke src1.
      val src1NeedsWakeupBasedOnRegisteredState = currentEntry.useSrc1 && !currentEntry.src1Ready
      val s1IsWokenByBypassLogicThisCycle = io.bypassIn.foldLeft(False) { (s1AlreadyWokenByPriorPort, bypassPort) =>
        val src1MatchesThisBypass = currentEntry.src1Tag === bypassPort.payload.physRegIdx
        // Condition for *this specific* bypass port to wake src1 (if not already handled)
        val thisBypassCanWakeSrc1 =
          bypassPort.valid && bypassPort.payload.physRegDataValid && src1NeedsWakeupBasedOnRegisteredState && src1MatchesThisBypass

        when(thisBypassCanWakeSrc1 && !s1AlreadyWokenByPriorPort) {
          currentEntry.src1Data := bypassPort.payload.physRegData
          currentEntry.src1Ready := True // Updates register input for next cycle
          report(
            L"AluIntIQ-${idStr}: Entry ${i.toString()} (ROB ${currentEntry.robIdx}) src1 (Tag ${currentEntry.src1Tag}) WOKE UP by Bypass ROB ${bypassPort.payload.robIdx} (pReg ${bypassPort.payload.physRegIdx})"
          )
        }
        // Accumulator update: True if already woken OR this port can wake it.
        s1AlreadyWokenByPriorPort || thisBypassCanWakeSrc1
      }

      // --- Wake up for Source 2 ---
      val src2NeedsWakeupBasedOnRegisteredState = currentEntry.useSrc2 && !currentEntry.src2Ready
      val s2IsWokenByBypassLogicThisCycle = io.bypassIn.foldLeft(False) { (s2AlreadyWokenByPriorPort, bypassPort) =>
        val src2MatchesThisBypass = currentEntry.src2Tag === bypassPort.payload.physRegIdx
        val thisBypassCanWakeSrc2 =
          bypassPort.valid && bypassPort.payload.physRegDataValid && src2NeedsWakeupBasedOnRegisteredState && src2MatchesThisBypass

        when(thisBypassCanWakeSrc2 && !s2AlreadyWokenByPriorPort) {
          currentEntry.src2Data := bypassPort.payload.physRegData
          currentEntry.src2Ready := True
          report(
            L"AluIntIQ-${idStr}: Entry ${i.toString()} (ROB ${currentEntry.robIdx}) src2 (Tag ${currentEntry.src2Tag}) WOKE UP by Bypass ROB ${bypassPort.payload.robIdx} (pReg ${bypassPort.payload.physRegIdx})"
          )
        }
        s2AlreadyWokenByPriorPort || thisBypassCanWakeSrc2
      }
      // Add src3 wake-up if used, following the same foldLeft pattern

      // --- Combinational re-evaluation of isReadyToIssue for *this* cycle ---
      // Uses registered srcXReady (from previous cycle) OR if any bypass port in *this* cycle is waking it up.
      val s1EffectivelyReadyThisCycle = currentEntry.src1Ready || s1IsWokenByBypassLogicThisCycle
      val s2EffectivelyReadyThisCycle = currentEntry.src2Ready || s2IsWokenByBypassLogicThisCycle
      // val s3EffectivelyReadyThisCycle = currentEntry.src3Ready || s3IsWokenByBypassLogicThisCycle // If using src3

      currentEntry.isReadyToIssue := entryValids(i) &&
        (!currentEntry.useSrc1 || s1EffectivelyReadyThisCycle) &&
        (!currentEntry.useSrc2 || s2EffectivelyReadyThisCycle) // &&
      // (!currentEntry.useSrc3 || s3EffectivelyReadyThisCycle) // If using src3
    } otherwise { // If !entryValids(i)
      entries(i).isReadyToIssue := False // Invalid entries are not ready
    }
  } // End for (i <- 0 until depth)

  // --- Selection Logic (Oldest Ready First - simplified) ---
  val readyToIssueMask = B(entries.map(_.isReadyToIssue)) // Bitmask of all ready entries
  val issueRequestOh = OHMasking.first(readyToIssueMask)
  val canIssue = issueRequestOh.orR
  val issueIdx = OHToUInt(issueRequestOh)

  val grantedIndexIsActuallyValid = if (depth > 0) entryValids(issueIdx) else False
  io.issueOut.valid := canIssue && grantedIndexIsActuallyValid && !io.flush
  io.issueOut.payload := entries(issueIdx)

  val nextEntryValidsAfterIssue = Vec(Bool(), depth)
  val currentValidCount = CountOne(entryValids)

  for (idx <- 0 until depth) {
    nextEntryValidsAfterIssue(idx) := entryValids(idx)
  }
  when(io.issueOut.fire) {
    entryValids(issueIdx) := False
    nextEntryValidsAfterIssue(issueIdx) := False
    val nextValidCount = CountOne(nextEntryValidsAfterIssue)
    report(
      L"AluIntIQ-${idStr}: Issued entry at index ${issueIdx}, ROBIdx=${entries(issueIdx).robIdx}. Valid count after this issue: ${nextValidCount}"
    )
  }

  // --- Flush Logic ---
  when(io.flush) {
    entryValids.foreach(_ := False)
    report(L"AluIntIQ-${idStr}: FLUSHED. All entries invalidated.")
  }

  // --- Elaboration-time Log ---
  ParallaxLogger.log(
    s"AluIntIQ-${idStr} Component (depth ${depth}, bypass sources ${config.bypassNetworkSources}) elaborated."
  )

  // --- Simulation-time Logs ---
  if (GenerationFlags.simulation.isEnabled) {
    for (i <- 0 until depth) {
      val e = entries(i)
      // when(entryValids(i)){ // Example of conditional detailed log
      //   report(L"AluIntIQ-${idStr} Entry ${i.toString()}: Valid=${entryValids(i)}, ROB=${e.robIdx}, pDest=${e.physDest.idx}, s1Rdy=${e.src1Ready}, s1Tag=${e.src1Tag}, s2Rdy=${e.src2Ready}, s2Tag=${e.src2Tag}, ReadyToIssue=${e.isReadyToIssue}")
      // }
    }
    report(
      L"AluIntIQ-${idStr}: CycleEnd: canAccept=${io.canAccept}, issueOut.valid=${io.issueOut.valid}, issueOut.ready=${io.issueOut.ready}, issueOut.fire=${io.issueOut.fire}, flush=${io.flush}, ValidCount=${currentValidCount}"
    )
  }
}
