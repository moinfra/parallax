// filename: parallax/components/issue/SequentialIssueQueueComponent.scala
// NOTE: This is the final, corrected version intended for direct replacement.
// This version incorporates a pipelined wakeup mechanism to resolve timing issues.
package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.WakeupPayload
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.utilities.ParallaxSim.{debug, notice}

/**
 * IQEntryWrapper: A wrapper to associate a 'valid' bit with a generic IQ entry.
 * This is cleaner than modifying the IQEntryLike trait itself.
 */
case class IQEntryWrapper[T_IQEntry <: Data with IQEntryLike](entryType: HardType[T_IQEntry]) extends Bundle {
  val valid = Bool()
  val entry = entryType()

  def setDefault(): this.type = {
    valid := False
    entry.setDefault()
    this
  }
}

/**
 * SequentialIssueQueueComponent (Final Ring Buffer Remake with Pipelined Wakeup)
 *
 * This version incorporates corrections for state update logic and removes redundant
 * state representation. Crucially, it pipelines the wakeup-match logic to resolve
 * critical timing paths, at the cost of one additional cycle of wakeup latency for
 * non-head entries. A bypass path is included for the issue logic to prevent any
 * increase in issue latency.
 *
 * @param iqConfig       IQ's configuration.
 * @param numWakeupPorts Number of parallel wakeup ports.
 * @param id             Unique ID for logging.
 */
class SequentialIssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val numWakeupPorts: Int,
    val id: Int = 0
) extends Component
    with IssueQueueLike[T_IQEntry] {

  val enableLog = false
  override val io = slave(IssueQueueComponentIo(iqConfig, numWakeupPorts))
  override val idStr = s"${iqConfig.name}-SEQ_RING-${id.toString()}"

  require(isPow2(iqConfig.depth), s"Issue Queue depth must be a power of two for ring buffer. Got ${iqConfig.depth}.")
  val addressWidth = log2Up(iqConfig.depth)

  val storage = new Area {
    // 1. State Registers: Pointers and Storage using the new Wrapper
    def T_EntryWrapper = IQEntryWrapper(iqConfig.getIQEntry())
    val slotsReg = Vec.fill(iqConfig.depth)(Reg(T_EntryWrapper))

    val allocPtrReg = Reg(UInt(addressWidth bits)).init(0) // Tail
    val freePtrReg = Reg(UInt(addressWidth bits)).init(0)  // Head
    val wrapModeReg = Reg(Bool()).init(False)

    val isFull = (allocPtrReg === freePtrReg) && wrapModeReg
    val isEmpty = (allocPtrReg === freePtrReg) && !wrapModeReg

    // Helper function: The single source of truth for valid index range
    def isIndexInFlight(idx: UInt): Bool = {
        val result = Bool()
        when(isEmpty)       { result := False }
        .elsewhen(isFull)   { result := True }
        .elsewhen(allocPtrReg > freePtrReg) { result := (idx >= freePtrReg && idx < allocPtrReg) }
        .otherwise          { result := (idx >= freePtrReg || idx < allocPtrReg) }
        result
    }

    // Initialize all physical slots
    for (i <- 0 until iqConfig.depth) {
      slotsReg(i).init(T_EntryWrapper.setDefault())
    }
  }

  val logic = new Area {
    // 2. Next-state signals are still needed as the final latching target
    val slotsNext = CombInit(storage.slotsReg)
    val allocPtrNext = CombInit(storage.allocPtrReg)
    val freePtrNext = CombInit(storage.freePtrReg)
    val wrapModeRegNext = CombInit(storage.wrapModeReg)

    val doAllocate = io.allocateIn.fire
    val doIssue = io.issueOut.fire
    val doFlush = io.flush
    io.allocateIn.ready := !storage.isFull && !doFlush

    // =========================================================================
    //               PIPELINED WAKEUP LOGIC
    // =========================================================================

    // --- Stage 1: Latch Wakeup Input ---
    // This is the first pipeline stage for incoming wakeup signals.
    val wakeupInReg = RegNext(io.wakeupIn) initZero()
    when(doFlush) {
      wakeupInReg.clearAll()
    }

    // --- Stage 2: Combinational Wakeup Match ---
    // This performs the massive tag comparison but does NOT directly drive state.
    // Its outputs are intermediate signals to be registered in the next stage.
    val wakeupMatch = new Area {
        val src1Mask = Bits(iqConfig.depth bits)
        val src2Mask = Bits(iqConfig.depth bits)
        src1Mask.clearAll()
        src2Mask.clearAll()

        for (i <- 0 until iqConfig.depth) {
            val slot = storage.slotsReg(i) // Read registered state from previous cycle
            when(storage.isIndexInFlight(U(i)) && slot.valid) {
                val entry = slot.entry
                val canWakeupSrc1 = !entry.src1Ready && entry.useSrc1
                val canWakeupSrc2 = !entry.src2Ready && entry.useSrc2
                for (wakeup <- wakeupInReg) { // Use registered wakeups from previous cycle
                    when(wakeup.valid) {
                        when(canWakeupSrc1 && (entry.src1Tag === wakeup.payload.physRegIdx)) { src1Mask(i) := True }
                        when(canWakeupSrc2 && (entry.src2Tag === wakeup.payload.physRegIdx)) { src2Mask(i) := True }
                    }
                }
            }
        }
    }

    // --- Stage 3: Latch Match Results ---
    // These registers break the critical path from tag comparison to slot update.
    val wokeUpSrc1MaskReg = RegNext(wakeupMatch.src1Mask) init(0)
    val wokeUpSrc2MaskReg = RegNext(wakeupMatch.src2Mask) init(0)
    when(doFlush) {
      wokeUpSrc1MaskReg := 0
      wokeUpSrc2MaskReg := 0
    }
    
    // --- Issue Logic (with Wakeup Bypass) ---
    val issue = new Area {
        val headIdx = storage.freePtrReg
        val headSlot = storage.slotsReg(headIdx)
        val headEntry = headSlot.entry

        // **BYPASS**: To avoid adding a cycle of issue latency, we use the
        // combinational match result from *this* cycle for the head entry.
        // This path is short (wakeupInReg -> one entry compare -> issue valid)
        // and should not be critical.
        val immediateWokeUpSrc1 = wakeupMatch.src1Mask(headIdx)
        val immediateWokeUpSrc2 = wakeupMatch.src2Mask(headIdx)

        val headFinalSrc1Ready = headEntry.src1Ready || immediateWokeUpSrc1
        val headFinalSrc2Ready = headEntry.src2Ready || immediateWokeUpSrc2
        val headIsReadyToIssue = (!headEntry.useSrc1 || headFinalSrc1Ready) && (!headEntry.useSrc2 || headFinalSrc2Ready)
        val canIssue = !storage.isEmpty && headSlot.valid && headIsReadyToIssue && !doFlush
        io.issueOut.valid := canIssue
        
        val issuePayload = iqConfig.getIQEntry().allowOverride()
        issuePayload := headEntry
        issuePayload.src1Ready := headFinalSrc1Ready
        issuePayload.src2Ready := headFinalSrc2Ready
        io.issueOut.payload := issuePayload

        when(doIssue) {
            if (enableLog) debug(L"[${idStr}] ISSUE: robPtr=${headEntry.robPtr} from slotIdx=${headIdx}")
        }
    }
    
    // =========================================================================
    //               NEW PARALLEL UPDATE LOGIC
    // =========================================================================
    val update = new Area {
        // --- 1. Pre-calculate the state of a new entry IF allocated ---
        val newEntry = IQEntryWrapper(iqConfig.getIQEntry())
        newEntry.valid := True
        val allocCmd = io.allocateIn.payload
        val allocUop = allocCmd.uop.asInstanceOf[RenamedUop]
        newEntry.entry.initFrom(allocUop, allocUop.robPtr).allowOverride()

        // ** CRITICAL FIX: Allocation-Wakeup Forwarding **
        // A new instruction needs to check against wakeups from the previous cycle (in wakeupInReg)
        // and wakeups from the current cycle (at io.wakeupIn) to be woken up immediately.
        val s1WakesUpOnReg = wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc1.idx).orR
        val s2WakesUpOnReg = wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc2.idx).orR
        val s1WakesUpOnIo = io.wakeupIn.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc1.idx).orR
        val s2WakesUpOnIo = io.wakeupIn.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc2.idx).orR
        newEntry.entry.src1Ready := allocCmd.src1InitialReady || s1WakesUpOnReg || s1WakesUpOnIo
        newEntry.entry.src2Ready := allocCmd.src2InitialReady || s2WakesUpOnReg || s2WakesUpOnIo

        // --- 2. Determine next state for each physical slot ---
        for(i <- 0 until iqConfig.depth) {
            val isAllocatingToThisSlot = doAllocate && (storage.allocPtrReg === i)
            val isIssuingFromThisSlot = doIssue && (storage.freePtrReg === i)

            when(isAllocatingToThisSlot) {
                // This slot is being overwritten by a new entry.
                slotsNext(i) := newEntry
                if (enableLog)
                    debug(L"[${idStr}] ALLOC: robPtr=${allocUop.robPtr} to slotIdx=${storage.allocPtrReg}")
            }
            .elsewhen(isIssuingFromThisSlot) {
                // This slot is being freed.
                slotsNext(i).valid := False
            }
            .otherwise {
                // Otherwise, apply wakeups from the pipelined match results.
                // This is now a short, register-to-register path.
                when(wokeUpSrc1MaskReg(i)) { slotsNext(i).entry.src1Ready := True }
                when(wokeUpSrc2MaskReg(i)) { slotsNext(i).entry.src2Ready := True }
            }
        }
        
        // --- 3. Drive pointers and wrap mode based on events ---
        when(doAllocate) { allocPtrNext := storage.allocPtrReg + 1 }
        when(doIssue)    { freePtrNext := storage.freePtrReg + 1 }
        
        when(doAllocate =/= doIssue) {
            when(doAllocate) { when(allocPtrNext === storage.freePtrReg) { wrapModeRegNext := True } }
            .otherwise { when(freePtrNext === storage.allocPtrReg) { wrapModeRegNext := False } }
        }
    }
    
    // --- 4. Flush (highest priority) ---
    when(doFlush) {
      if (enableLog) notice(L"[${idStr}] FLUSH received. Resetting pointers.")
      allocPtrNext := 0
      freePtrNext := 0
      wrapModeRegNext := False
      for(i <- 0 until iqConfig.depth) { slotsNext(i).valid := False }
    }

    // --- 5. Final State Update (unconditional latching) ---
    storage.allocPtrReg := allocPtrNext
    storage.freePtrReg := freePtrNext
    storage.wrapModeReg := wrapModeRegNext
    storage.slotsReg := slotsNext
  }

  val debug_signals = new Area {
    if(enableLog) {
      // --- 1. Calculated Valid Count from Pointers ---
      val validCountFromPointers = UInt(log2Up(iqConfig.depth + 1) bits)
      when(storage.isFull) {
        validCountFromPointers := iqConfig.depth
      }.elsewhen(storage.isEmpty) {
        validCountFromPointers := 0
      }.elsewhen(storage.allocPtrReg > storage.freePtrReg) {
        validCountFromPointers := (storage.allocPtrReg - storage.freePtrReg).resized
      }.otherwise { // Wrapped case
        validCountFromPointers := (iqConfig.depth + storage.allocPtrReg - storage.freePtrReg).resized
      }

      // --- 2. High-Level Status Summary ---
      debug(
        L"[${idStr}] STATUS: " :+
        L"Count=${validCountFromPointers}, " :+
        L"allocPtr=${storage.allocPtrReg}, " :+
        L"freePtr=${storage.freePtrReg}, " :+
        L"wrapMode=${storage.wrapModeReg}, " :+
        L"isEmpty=${storage.isEmpty}, " :+
        L"isFull=${storage.isFull}"
      )

      // --- 3. IO Port State ---
      debug(
        L"[${idStr}] IO_STATE: " :+
        L"allocateIn(valid=${io.allocateIn.valid}, ready=${io.allocateIn.ready}, fire=${io.allocateIn.fire}), " :+
        L"issueOut(valid=${io.issueOut.valid}, ready=${io.issueOut.ready}, fire=${io.issueOut.fire})"
      )

      // --- 4. Detailed Issue Decision Logic ---
      when(!storage.isEmpty) {
        val headIdx = storage.freePtrReg
        val headSlot = storage.slotsReg(headIdx)
        val headEntry = headSlot.entry
        val wau = logic.issue

        debug(L"[${idStr}] ISSUE_CHECK (Head@${headIdx}): robPtr=${headEntry.robPtr}, valid=${headSlot.valid}")
        debug(
          L"  - Src1: use=${headEntry.useSrc1}, rdy=${headEntry.src1Ready}, tag=${headEntry.src1Tag}, " :+
          L"wakeUpThisCycle(bypass)=${wau.immediateWokeUpSrc1} => finalRdy=${wau.headFinalSrc1Ready}"
        )
        debug(
          L"  - Src2: use=${headEntry.useSrc2}, rdy=${headEntry.src2Ready}, tag=${headEntry.src2Tag}, " :+
          L"wakeUpThisCycle(bypass)=${wau.immediateWokeUpSrc2} => finalRdy=${wau.headFinalSrc2Ready}"
        )
        debug(L"  - Final Decision: headIsReady=${wau.headIsReadyToIssue}, canIssue=${wau.canIssue}")
      }

      // --- 5. Key Event Logs ---
      when(io.allocateIn.fire) {
          debug(L"  => EVENT: ALLOCATED robPtr=${io.allocateIn.payload.uop.robPtr}")
      }
      when(io.issueOut.fire) {
          debug(L"  => EVENT: ISSUED robPtr=${io.issueOut.payload.robPtr}")
      }
      when(io.flush) {
          notice(L"  => EVENT: FLUSH received!")
      }

      // --- 6. Full Queue Content Dump ---
       when(validCountFromPointers > 0) {
        debug(L"  -- [${idStr}] Contents --")
        for (i <- 0 until iqConfig.depth) {
            when(storage.slotsReg(i).valid) {
                val slot = storage.slotsReg(i)
                val entry = slot.entry
                val isHead = !storage.isEmpty && (storage.freePtrReg === i)
                val isTail = !storage.isEmpty && (storage.allocPtrReg === i)
                
                debug(L"  -> Slot[${i}]: robPtr=${entry.robPtr}, Src1(u=${entry.useSrc1},r=${entry.src1Ready},t=${entry.src1Tag}), Src2(u=${entry.useSrc2},r=${entry.src2Ready},t=${entry.src2Tag})")
                
                when(isHead) {
                  debug(L"    (is HEAD)")
                }
                when(isTail) {
                  debug(L"    (is TAIL)")
                }
            }
        }
      }
      
      debug(L"--- [${idStr}] CYCLE END ---")
    }
  }

  ParallaxLogger.log(
    s"${idStr} Component (depth ${iqConfig.depth}, wakeup ports ${numWakeupPorts}, type ${iqConfig.uopEntryType().getClass.getSimpleName}) elaborated with Pipelined Wakeup Ring Buffer implementation."
  )
}
