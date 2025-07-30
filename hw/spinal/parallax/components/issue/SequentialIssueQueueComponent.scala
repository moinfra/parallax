package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.WakeupPayload
import parallax.utilities.{ParallaxLogger, ParallaxSim}

class SequentialIssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val numWakeupPorts: Int,
    val id: Int = 0
) extends Component
    with IssueQueueLike[T_IQEntry] {
  val enableLog = false
  override val io = slave(IssueQueueComponentIo(iqConfig, numWakeupPorts))
  val idStr = s"${iqConfig.name}-SEQ-${id.toString()}"

  val state = new Area {
    val entriesReg = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
    val entryValidsReg = Reg(Bits(iqConfig.depth bits)) init (0)
    val validCountReg = Reg(UInt(log2Up(iqConfig.depth + 1) bits)) init (0)

    val isEmpty = validCountReg === 0
    val isFull = validCountReg === iqConfig.depth

    val wakeupInReg = Reg(Vec.fill(numWakeupPorts)(Flow(WakeupPayload(iqConfig.pipelineConfig))))
    wakeupInReg.foreach(_.valid.init(False))
  }

  val wakeup = new Area {
    state.wakeupInReg := io.wakeupIn

    val wokeUpSrc1Mask = Bits(iqConfig.depth bits)
    val wokeUpSrc2Mask = Bits(iqConfig.depth bits)
    wokeUpSrc1Mask.clearAll()
    wokeUpSrc2Mask.clearAll()

    for (i <- 0 until iqConfig.depth) {
      val entry = state.entriesReg(i)
      when(state.entryValidsReg(i)) {
        val canWakeupSrc1 = !entry.src1Ready && entry.useSrc1
        val canWakeupSrc2 = !entry.src2Ready && entry.useSrc2

        for (wakeup <- state.wakeupInReg) {
          when(wakeup.valid) {
            when(canWakeupSrc1 && (entry.src1Tag === wakeup.payload.physRegIdx)) { wokeUpSrc1Mask(i) := True }
            when(canWakeupSrc2 && (entry.src2Tag === wakeup.payload.physRegIdx)) { wokeUpSrc2Mask(i) := True }
            when(wokeUpSrc1Mask(i)) {
              report(L"${idStr} wokeUpSrc1Mask(${i}) is ${wokeUpSrc1Mask(i)}, because canWakeupSrc1=${canWakeupSrc1}, entry.src1Tag=${entry.src1Tag}, wakeup.payload.physRegIdx=${wakeup.payload.physRegIdx}")
            }
            when(wokeUpSrc2Mask(i)) {
              report(L"${idStr} wokeUpSrc2Mask(${i}) is ${wokeUpSrc2Mask(i)}, because canWakeupSrc2=${canWakeupSrc2}, entry.src2Tag=${entry.src2Tag}, wakeup.payload.physRegIdx=${wakeup.payload.physRegIdx}")
            }
          }
        }
      }
    }
  }

  val issue_alloc = new Area {
    val headEntry = state.entriesReg(0)
    val headIsValid = state.entryValidsReg(0)

    // Wakeup bypass for the issue logic
    val headWakesUpSrc1 = wakeup.wokeUpSrc1Mask(0)
    val headWakesUpSrc2 = wakeup.wokeUpSrc2Mask(0)

    val headFinalSrc1Ready = headEntry.src1Ready || headWakesUpSrc1
    val headFinalSrc2Ready = headEntry.src2Ready || headWakesUpSrc2

    val headIsReady = (!headEntry.useSrc1 || headFinalSrc1Ready) &&
      (!headEntry.useSrc2 || headFinalSrc2Ready)

    val canIssue = headIsValid && headIsReady

    io.issueOut.valid := canIssue && !io.flush

    // Fix assignment conflict by building a new payload object
    val issuePayload = iqConfig.getIQEntry().allowOverride()
    issuePayload := headEntry
    issuePayload.src1Ready := headFinalSrc1Ready
    issuePayload.src2Ready := headFinalSrc2Ready
    io.issueOut.payload := issuePayload

    val issueFired = io.issueOut.fire
    when(issueFired) {
        report(L"${idStr} ISSUED PAYLOAD: RobPtr=${issuePayload.robPtr}")
    }

    io.allocateIn.ready := !state.isFull && !io.flush
    val allocationFired = io.allocateIn.fire
  }

  // ========================================================================
  //
  //                        FULLY CORRECTED UPDATE LOGIC
  //
  // ========================================================================
  val update = new Area {

    // 1. Calculate the state for a newly allocated entry
    val newEntry = iqConfig.getIQEntry()
    val allocCmd = io.allocateIn.payload
    val allocUop = allocCmd.uop
    newEntry.initFrom(allocUop, allocUop.robPtr).allowOverride()
    val s1WakesUp = state.wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc1.idx).orR
    val s2WakesUp = state.wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc2.idx).orR
    newEntry.src1Ready := allocCmd.src1InitialReady || s1WakesUp
    newEntry.src2Ready := allocCmd.src2InitialReady || s2WakesUp

    // 2. Calculate the state of existing entries after this cycle's wakeup
    //    FIX: First copy the current state, THEN override ready bits to avoid NO DRIVER errors.
    val entriesAfterWakeup = CombInit(state.entriesReg).allowOverride()
    for (i <- 0 until iqConfig.depth) {
      entriesAfterWakeup(i).src1Ready := state.entriesReg(i).src1Ready || wakeup.wokeUpSrc1Mask(i)
      entriesAfterWakeup(i).src2Ready := state.entriesReg(i).src2Ready || wakeup.wokeUpSrc2Mask(i)
    }

    // 3. Calculate the final next state for all entries and valids using a priority-encoded
    //    decision tree. This handles all cases (issue, allocate, both, neither) correctly.
    val nextEntries = Vec.fill(iqConfig.depth)(iqConfig.getIQEntry())
    val nextValids = Bits(iqConfig.depth bits)

    // Determine the index where a new instruction would be written
    val allocWriteIdx = Mux(
      issue_alloc.issueFired,
      (state.validCountReg - 1).resize(log2Up(iqConfig.depth)),
      state.validCountReg.resize(log2Up(iqConfig.depth))
    )

    // For each slot, decide its next state
    for (i <- 0 until iqConfig.depth) {
      when(issue_alloc.allocationFired && U(i) === allocWriteIdx) {
        // Highest priority: a new instruction is allocated to this slot.
        nextEntries(i) := newEntry
        nextValids(i)  := True
      }
      .elsewhen(issue_alloc.issueFired) {
        // Second priority: an instruction was issued, so we shift contents.
        // FIX: Use a compile-time `if` to prevent out-of-bounds access.
        if (i < iqConfig.depth - 1) {
          // This slot gets the content of the next slot (with wakeups applied).
          nextEntries(i) := entriesAfterWakeup(i + 1)
          nextValids(i)  := state.entryValidsReg(i + 1)
        } else {
          // The last slot becomes empty after a shift.
          nextEntries(i).assignDontCare()
          nextValids(i)  := False
        }
      }
      .otherwise {
        // Default case: no allocation, no issue. Just apply wakeups to current state.
        nextEntries(i) := entriesAfterWakeup(i)
        nextValids(i)  := state.entryValidsReg(i)
      }
    }

    // 4. Perform the final, unconditional assignments to the state registers
    state.entriesReg := nextEntries
    state.entryValidsReg := nextValids

    val nextValidCount = state.validCountReg + U(issue_alloc.allocationFired) - U(issue_alloc.issueFired)

    state.validCountReg := nextValidCount

    // 5. Handle flush (highest priority, overrides all previous calculations)
    when(io.flush) {
      state.entryValidsReg := 0
      state.validCountReg := 0
    }
  }

  val debug = new Area {
    // This debug area can be kept as is for verification.
    if(enableLog) {
        ParallaxSim.debug(L"STATUS: validCount=${state.validCountReg}, valids=${state.entryValidsReg}")
        ParallaxSim.debug(
        L"ISSUE_INPUTS: " :+
            L"state.entryValidsReg(0)=${state.entryValidsReg(0)}, " :+
            L"headEntry.useSrc1=${state.entriesReg(0).useSrc1}, " :+
            L"headEntry.src1Ready=${state.entriesReg(0).src1Ready}, " :+
            L"headEntry.useSrc2=${state.entriesReg(0).useSrc2}, " :+
            L"headEntry.src2Ready=${state.entriesReg(0).src2Ready}, " :+
            L"wakeup.wokeUpSrc1Mask(0)=${wakeup.wokeUpSrc1Mask(0)}, " :+
            L"wakeup.wokeUpSrc2Mask(0)=${wakeup.wokeUpSrc2Mask(0)}"
        )
        ParallaxSim.debug(
        L"ISSUE_CALC: " :+
            L"headIsValid=${issue_alloc.headIsValid}, " :+
            L"headFinalSrc1Ready=${issue_alloc.headFinalSrc1Ready}, " :+
            L"headFinalSrc2Ready=${issue_alloc.headFinalSrc2Ready}, " :+
            L"headIsReady=${issue_alloc.headIsReady}, " :+
            L"canIssue=${issue_alloc.canIssue}"
        )
        ParallaxSim.debug(L"IO_STATE: issueOut(valid=${io.issueOut.valid}, ready=${io.issueOut.ready}, fire=${io.issueOut.fire}), allocateIn(valid=${io.allocateIn.valid}, ready=${io.allocateIn.ready}, fire=${io.allocateIn.fire})")
        when(io.issueOut.fire) {
            ParallaxSim.debug(L"  => ISSUED PAYLOAD: RobPtr=${io.issueOut.payload.robPtr}")
        }
        when(io.allocateIn.fire) {
            ParallaxSim.debug(L"  => ALLOCATED PAYLOAD: RobPtr=${io.allocateIn.payload.uop.robPtr}")
        }
        for (i <- 0 until iqConfig.depth) {
            when(state.entryValidsReg(i)) {
                ParallaxSim.debug(L"  -> ENTRY[${i}]: RobPtr=${state.entriesReg(i).robPtr}, Src1Rdy=${state.entriesReg(i).src1Ready}, Src2Rdy=${state.entriesReg(i).src2Ready}")
            }
        }
        ParallaxSim.debug(L"--- CYCLE END ---")

    }
  }
}
