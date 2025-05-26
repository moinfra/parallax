package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.decode._
import parallax.utilities.ParallaxSim
import _root_.parallax.utilities.ParallaxLogger

// --- Configuration for ROB ---
case class ROBConfig[RU <: Data with Dumpable with HasRobIdx](
    val robDepth: Int,
    val pcWidth: BitCount,
    val commitWidth: Int,
    val allocateWidth: Int,
    // Number of writeback ports for execution units.
    // Can be different from commit/allocate widths.
    val numWritebackPorts: Int,
    val uopType: HardType[RU],
    val defaultUop: () => RU,
    val exceptionCodeWidth: BitCount = 8 bits
) extends Dumpable {

  require(robDepth > 0 && isPow2(robDepth), "ROB depth must be a positive power of 2.")
  require(commitWidth > 0 && commitWidth <= robDepth)
  require(allocateWidth > 0 && allocateWidth <= robDepth)
  require(numWritebackPorts > 0)
  val robIdxWidth: BitCount = log2Up(robDepth) bits

  def dump(): Seq[Any] = {
    val str = L"""ROBConfig(
        robDepth: ${robDepth},
        pcWidth: ${pcWidth},
        commitWidth: ${commitWidth},
        allocateWidth: ${allocateWidth},
        numWritebackPorts: ${numWritebackPorts},
        uopType: ${uopType.getClass.getName},
        defaultUop: ${defaultUop.getClass.getName},
        defaultUop.width: ${defaultUop().robIdx.getWidth},
        exceptionCodeWidth: ${exceptionCodeWidth},
        robIdxWidth: ${robIdxWidth},
      )"""
    str
  }
}

// --- MODIFICATION START (Flush Mechanism Refactor: Enum and Command Bundle) ---
object FlushReason extends SpinalEnum {
  val NONE, FULL_FLUSH, ROLLBACK_TO_ROB_IDX = newElement()
  // NONE is implicitly the default for SpinalEnum, but explicit for clarity if needed.
  // We'll assume io.flush.valid implies reason is not NONE.
}

// For flush command (restoring ROB pointers)
// This is the payload of the io.flush Flow
case class ROBFlushPayload[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Bundle {
  val reason = FlushReason()
  val targetRobIdx = UInt(config.robIdxWidth) // Relevant for ROLLBACK_TO_ROB_IDX
}
// --- MODIFICATION END ---

// --- Data Bundles for ROB Internal Storage ---
// Status part of the ROB entry (frequently updated)
case class ROBStatus[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Bundle {
  val busy = Bool()
  val done = Bool()
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth)
}

// Payload part of the ROB entry (written once at allocation)
case class ROBPayload[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Bundle {
  val uop = config.uopType() // Contains physReg info
  val pc = UInt(config.pcWidth)
}

// Combined entry for communication (e.g., commit port)
case class ROBFullEntry[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Bundle {
  val payload = ROBPayload(config)
  val status = ROBStatus(config)
}

// --- IO Bundles for ROB ---

// For allocation port (per slot)
case class ROBAllocateSlot[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val fire = Bool() // From Rename: attempt to allocate this slot
  val uopIn = config.uopType()
  val pcIn = UInt(config.pcWidth)
  val robIdx = UInt(config.robIdxWidth) // To Rename: allocated ROB index for this slot

  override def asMaster(): Unit = { // Perspective of Rename Stage
    in(fire, uopIn, pcIn)
    out(robIdx)
  }
}

// For writeback port (per execution unit writeback)
case class ROBWritebackPort[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val fire = Bool()
  val robIdx = UInt(config.robIdxWidth)
  // isDone is implicit if fire is true for a normal writeback.
  val exceptionOccurred = Bool()
  val exceptionCodeIn = UInt(config.exceptionCodeWidth)

  override def asMaster(): Unit = { // Perspective of Execute Stage
    in(fire, robIdx, exceptionOccurred, exceptionCodeIn)
  }
}

// For commit port (per slot)
case class ROBCommitSlot[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val valid = Bool() // From ROB: this entry is ready to commit
  val entry = ROBFullEntry(config) // From ROB: data of the entry

  override def asMaster(): Unit = { // Perspective of ROB
    out(valid, entry)
  }

  def asFlow: Flow[ROBFullEntry[RU]] = {
    val ret = Flow(ROBFullEntry(config))
    ret.valid := this.valid
    ret.payload := this.entry
    ret
  }
}

// Top-level IO for ReorderBuffer
case class ROBIo[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Bundle with IMasterSlave {
  // Allocation: Vector of master ports from ROB's perspective (Rename stage is slave)
  val allocate = Vec(master(ROBAllocateSlot(config)), config.allocateWidth)
  val canAllocate = out Vec (Bool(), config.allocateWidth) // To Rename: can each slot be allocated?

  // Writeback: Vector of master ports from ROB's perspective (Execute stage is slave)
  val writeback = Vec(master(ROBWritebackPort(config)), config.numWritebackPorts)

  // Commit: Vector of slave ports from ROB's perspective (Commit stage is master)
  val commit = Vec(slave(ROBCommitSlot(config)), config.commitWidth)
  val commitFire = in Vec (Bool(), config.commitWidth) // From Commit: which slots are actually committed

  // --- MODIFICATION START (Flush Mechanism Refactor: IO Update) ---
  // Flush: Slave Flow port from ROB's perspective (Recovery/Checkpoint manager is master)
  val flush = slave Flow (ROBFlushPayload(config))
  val flushed = out Bool () // Indicates ROB has completed flush operation this cycle
  // --- MODIFICATION END ---

  // Status outputs
  val empty = out Bool ()
  val headPtrOut = out UInt (config.robIdxWidth)
  val tailPtrOut = out UInt (config.robIdxWidth)
  val countOut = out UInt (log2Up(config.robDepth + 1) bits)

  def asMaster(): Unit = { // Perspective of ROB
    allocate.foreach(_.asSlave())
    writeback.foreach(_.asSlave())
    commit.foreach(_.asSlave())
    // --- MODIFICATION START (Flush Mechanism Refactor: IO asMaster Update) ---
    master(flush)
    in(flushed)
    // --- MODIFICATION END ---
    in(canAllocate)
    out(commitFire)
    in(empty, headPtrOut, tailPtrOut, countOut)
  }
}

class ReorderBuffer[RU <: Data with Dumpable with HasRobIdx](config: ROBConfig[RU]) extends Component {
  ParallaxLogger.log(
    s"Creating ReorderBuffer with config: ${config.dump().mkString("")}"
  )
  val io = slave(ROBIo(config)) // ROB is slave to the overall system for its IO bundle

  val payloads = Mem(ROBPayload(config), config.robDepth)
  val statuses = Vec(Reg(ROBStatus(config)), config.robDepth)

  payloads.init(
    Seq.fill(config.robDepth)(
      {
        assert(config.defaultUop().robIdx.getBitsWidth == config.robIdxWidth.value)
        val dp = ROBPayload(config)
        dp.uop := config.defaultUop()
        dp.pc := U(0)
        dp
      }
    )
  )
  for (i <- 0 until config.robDepth) {
    statuses(i).busy init (False)
    statuses(i).done init (False)
    statuses(i).hasException init (False)
    statuses(i).exceptionCode init (0)
  }

  // Actual registers for state
  val headPtr_reg = Reg(UInt(config.robIdxWidth)) init (0)
  val tailPtr_reg = Reg(UInt(config.robIdxWidth)) init (0)
  val count_reg = Reg(UInt(log2Up(config.robDepth + 1) bits)) init (0)

  // Combinational view of current state for calculations this cycle.
  val currentHead = headPtr_reg
  val currentTail = tailPtr_reg
  val currentCount = count_reg

  val slotWillAllocate = Vec(Bool(), config.allocateWidth)
  val slotRobIdx = Vec(UInt(config.robIdxWidth), config.allocateWidth)
  val numPrevAllocations = Vec(UInt(log2Up(config.allocateWidth + 1) bits), config.allocateWidth + 1)
  val robIdxAtSlotStart = Vec(UInt(config.robIdxWidth), config.allocateWidth + 1)

  numPrevAllocations(0) := U(0)
  robIdxAtSlotStart(0) := currentTail
  report(
    Seq(
      L"[ROB] ALLOC_PRE: Cycle Start. tailPtr=${currentTail}, count=${currentCount}, ",
      L"headPtr=${currentHead}, empty=${(currentCount === 0)}"
    )
  )

  for (i <- 0 until config.allocateWidth) {
    val allocPort = io.allocate(i)
    val spaceAvailableForThisSlot = (currentCount + numPrevAllocations(i)) < config.robDepth
    io.canAllocate(i) := spaceAvailableForThisSlot
    slotWillAllocate(i) := allocPort.fire && spaceAvailableForThisSlot
    slotRobIdx(i) := robIdxAtSlotStart(i)
    allocPort.robIdx := slotRobIdx(i)
    when(allocPort.fire) {
      ParallaxSim.debug(
        Seq(
          L"[ROB] ALLOC_ATTEMPT[${i}]: fire=${allocPort.fire}, pcIn=${allocPort.pcIn}, ",
          L"spaceAvailableForSlot=${spaceAvailableForThisSlot}, currentCountPlusPrevAllocs=${(currentCount + numPrevAllocations(
              i
            ))}, robDepth=${U(config.robDepth)}, calculatedRobIdx=${slotRobIdx(i)}"
        )
      )
    }
    when(allocPort.fire && !spaceAvailableForThisSlot) {
      report(
        Seq(
          L"[ROB] ALLOC_ATTEMPT[${i}]: Fired but NO SPACE. count=${currentCount}, ",
          L"numPrevAllocationsForThisSlot=${numPrevAllocations(i)}"
        )
      )
    }
    numPrevAllocations(i + 1) := numPrevAllocations(i) + U(slotWillAllocate(i))
    robIdxAtSlotStart(i + 1) := robIdxAtSlotStart(i) + U(slotWillAllocate(i))
    when(slotWillAllocate(i)) {
      report(
        L"[ROB] ALLOC_DO[${i}]: Allocating to robIdx=${slotRobIdx(i)}, pcIn=${allocPort.pcIn}"
      )
      val newPayload = ROBPayload(config).allowOverride()
      newPayload.uop := allocPort.uopIn
      newPayload.uop.robIdx := slotRobIdx(i)
      newPayload.pc := allocPort.pcIn
      payloads.write(address = slotRobIdx(i), data = newPayload)
      report(L"[ROB] ALLOC_PAYLOAD_WRITE[${i}]: Writing payload to robIdx=${slotRobIdx(i)}: ${newPayload.uop.dump()}")
      statuses(slotRobIdx(i)).busy := True
      statuses(slotRobIdx(i)).done := False
      statuses(slotRobIdx(i)).hasException := False
      statuses(slotRobIdx(i)).exceptionCode := U(0, config.exceptionCodeWidth)
      report(
        Seq(L"[ROB] ALLOC_STATUS_SET[${i}]: robIdx=${slotRobIdx(i)} set busy=T, ", L"done=F, hasException=F")
      )
    }
  }

  val numActuallyAllocatedThisCycle = numPrevAllocations(config.allocateWidth)
  when(numActuallyAllocatedThisCycle > 0) {
    report(
      Seq(
        L"[ROB] ALLOC_SUMMARY: numActuallyAllocatedThisCycle=${numActuallyAllocatedThisCycle}, ",
        L"currentTailPtr=${currentTail} -> newTailPtr (calculated for next reg)=${(currentTail + numActuallyAllocatedThisCycle)}, ",
        L"currentCount=${currentCount} -> newCount (calculated for next reg)=${(currentCount + numActuallyAllocatedThisCycle)}"
      )
    )
  }

  // --- Writeback Logic (Superscalar) ---
  for (portIdx <- 0 until config.numWritebackPorts) {
    val wbPort = io.writeback(portIdx)
    when(wbPort.fire) {
      report(
        Seq(
          L"[ROB] WRITEBACK[${portIdx}]: Fired. robIdx=${wbPort.robIdx}, exceptionOccurred=${wbPort.exceptionOccurred}, ",
          L"exceptionCodeIn=${wbPort.exceptionCodeIn}"
        )
      )
      val statusBeforeWb = statuses(wbPort.robIdx)
      report(
        Seq(
          L"[ROB] WRITEBACK_STATUS_OLD[${portIdx}]: robIdx=${wbPort.robIdx}, oldBusy=${statusBeforeWb.busy}, ",
          L"oldDone=${statusBeforeWb.done}, oldHasExcp=${statusBeforeWb.hasException}, oldExcpCode=${statusBeforeWb.exceptionCode}"
        )
      )
      statuses(wbPort.robIdx).busy := False
      statuses(wbPort.robIdx).done := True
      statuses(wbPort.robIdx).hasException := wbPort.exceptionOccurred
      statuses(wbPort.robIdx).exceptionCode := Mux(
        wbPort.exceptionOccurred,
        wbPort.exceptionCodeIn,
        statuses(wbPort.robIdx).exceptionCode // Keep old if no new exception
      )
      report(
        Seq(
          L"[ROB] WRITEBACK_STATUS_NEW[${portIdx}]: robIdx=${wbPort.robIdx}, newBusy=F, newDone=T, ",
          L"newHasExcp=${wbPort.exceptionOccurred}, newExcpCode=${Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode)}"
        )
      )
    } otherwise {
      report(
        Seq(
          L"[ROB] WRITEBACK[${portIdx}]: NOT Fired. robIdx=${wbPort.robIdx}, exceptionOccurred=${wbPort.exceptionOccurred}, ",
          L"exceptionCodeIn=${wbPort.exceptionCodeIn}"
        )
      )
    }
  }

  // --- Commit Logic (Superscalar) ---
  val canCommitFlags = Vec(Bool(), config.commitWidth)
  report(
    L"[ROB] COMMIT_PRE: Cycle Start. headPtr=${currentHead}, count=${currentCount}, empty=${(currentCount === 0)}"
  )

  for (i <- 0 until config.commitWidth) {
    val currentCommitIdx = currentHead + U(i) // Pointer arithmetic handles wrap
    val currentPayload = payloads.readAsync(address = currentCommitIdx)
    val currentStatus = statuses(currentCommitIdx)
    when(currentCount > U(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_CHECK_SLOT[${i}]: Considering commit. robIdx=${currentCommitIdx}, ",
          L"status.busy=${currentStatus.busy}, status.done=${currentStatus.done}, status.hasException=${currentStatus.hasException}, ",
          L"status.exceptionCode=${currentStatus.exceptionCode}, pc=${currentPayload.pc}, count=${currentCount}"
        )
      )
    }
    canCommitFlags(i) := (currentCount > U(i)) && currentStatus.done
    io.commit(i).valid := canCommitFlags(i)
    io.commit(i).entry.payload := currentPayload
    io.commit(i).entry.status := currentStatus
    when(canCommitFlags(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_VALID_SLOT[${i}]: Slot IS VALID for commit. robIdx=${currentCommitIdx}, ",
          L"io.commit(i).valid=${io
              .commit(i)
              .valid}, external commitFire(${i})=${io.commitFire(i)}"
        )
      )
    }
    when(!canCommitFlags(i) && (currentCount > U(i))) {
      report(
        Seq(
          L"[ROB] COMMIT_NOT_VALID_SLOT[${i}]: Slot NOT VALID for commit. robIdx=${currentCommitIdx}, ",
          L"status.done=${currentStatus.done}, count=${currentCount}, external commitFire(${i})=${io.commitFire(i)}"
        )
      )
    }
    when(io.commitFire(i) && !canCommitFlags(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_WARN_SLOT[${i}]: Commit stage fired for non-committable slot! robIdx=${currentCommitIdx}, ",
          L"canCommitFlag=${canCommitFlags(
              i
            )}, status.done=${currentStatus.done}, count=${currentCount}"
        )
      )
    }
  }
  val actualCommittedMask = Vec(Bool(), config.commitWidth)
  if (config.commitWidth > 0) {
    actualCommittedMask(0) := canCommitFlags(0) && io.commitFire(0)
    report(
      L"[ROB] COMMIT_MASK_CALC[0]: canCommitFlags(0)=${canCommitFlags(0)}, io.commitFire(0)=${io
          .commitFire(0)}, -> actualCommittedMask(0)=${actualCommittedMask(0)}"
    )
    for (i <- 1 until config.commitWidth) {
      actualCommittedMask(i) := actualCommittedMask(i - 1) && canCommitFlags(i) && io.commitFire(i)
      report(
        L"[ROB] COMMIT_MASK_CALC[${i}]: prevActualCommittedMask(${(i - 1)})=${actualCommittedMask(
            i - 1
          )}, canCommitFlags(${i})=${canCommitFlags(i)}, io.commitFire(${i})=${io
            .commitFire(i)}, -> actualCommittedMask(${i})=${actualCommittedMask(i)}"
      )
    }
  }
  val numToCommit = if (config.commitWidth > 0) CountOne(actualCommittedMask) else U(0, 1 bits).resized

  when(numToCommit > 0) {
    report(
      Seq(
        L"[ROB] COMMIT_SUMMARY: numToCommit=${numToCommit}, actualCommittedMask=${actualCommittedMask.asBits}, ",
        L"currentHeadPtr=${currentHead} -> newHeadPtr (calculated for next reg)=${(currentHead + numToCommit.resized)}, ", // Pointer arithmetic handles wrap
        L"currentCount=${currentCount} -> newCount (calculated for next reg)=${(currentCount - numToCommit.resized)}"
      )
    )
  }

  // --- Pointer and Count Update Logic ---
  // Calculate the next state based on this cycle's operations (alloc/commit)
  val nextHeadFromOps = UInt(config.robIdxWidth)
  val nextTailFromOps = UInt(config.robIdxWidth)
  val nextCountFromOps = UInt(log2Up(config.robDepth + 1) bits)

  nextHeadFromOps := currentHead + numToCommit.resized // Pointer arithmetic handles wrap
  nextTailFromOps := currentTail + numActuallyAllocatedThisCycle // Pointer arithmetic handles wrap

  val sAlloc = S(numActuallyAllocatedThisCycle.resize(nextCountFromOps.getWidth))
  val sCommit = S(numToCommit.resize(nextCountFromOps.getWidth))
  val sCurrentCount = S(currentCount.resize(nextCountFromOps.getWidth))
  nextCountFromOps := (sCurrentCount + sAlloc - sCommit).asUInt.resize(nextCountFromOps.getWidth)

  // --- MODIFICATION START (Flush Mechanism Refactor: Core Logic) ---
  // Apply updates to registers, with flush having highest priority
  io.flushed := False // Default flushed to False

  when(io.flush.valid) {
    io.flushed := True // Flush operation completes in this cycle
    report(
      L"[ROB] FLUSH: Received flush command. Valid=${io.flush.valid}, Reason=${io.flush.payload.reason}, TargetROBIdx=${io.flush.payload.targetRobIdx}"
    )

    val headAtFlushStart = headPtr_reg // State at the beginning of the cycle
    val tailAtFlushStart = tailPtr_reg
    val countAtFlushStart = count_reg

    val headAfterFlush = UInt(config.robIdxWidth)
    val tailAfterFlush = UInt(config.robIdxWidth)
    val countAfterFlush = UInt(log2Up(config.robDepth + 1) bits)

    // Default to current _reg values, to be overridden by specific flush reason
    headAfterFlush := headAtFlushStart
    tailAfterFlush := tailAtFlushStart
    countAfterFlush := countAtFlushStart

    switch(io.flush.payload.reason) {
      is(FlushReason.FULL_FLUSH) {
        report(L"[ROB] FLUSH: Reason=FULL_FLUSH")
        headAfterFlush := U(0)
        tailAfterFlush := U(0)
        countAfterFlush := U(0)
        for (k <- 0 until config.robDepth) {
          statuses(k).busy := False
          statuses(k).done := False
          statuses(k).hasException := False
          statuses(k).exceptionCode := 0
        }
        ParallaxSim.debug(L"[ROB DEBUG] Before final reg assignment in flush path: head=${headAfterFlush}, tail=${tailAfterFlush}, count=${countAfterFlush}") // <--- AND THIS
      }
      is(FlushReason.ROLLBACK_TO_ROB_IDX) {
        val targetIdx = io.flush.payload.targetRobIdx
        report(L"[ROB] FLUSH: Reason=ROLLBACK_TO_ROB_IDX, Target ROB Index=${targetIdx}")
        report(
          L"[ROB] FLUSH: State before rollback: head=${headAtFlushStart}, tail=${tailAtFlushStart}, count=${countAtFlushStart}"
        )

        headAfterFlush := headAtFlushStart // Head pointer usually doesn't change on rollback unless target is head
        tailAfterFlush := targetIdx

        // Calculate new count: number of elements from headAtFlushStart (inclusive) to targetIdx (exclusive)
        val h_ext = headAtFlushStart.resize(config.robIdxWidth.value + 1)
        val t_ext = targetIdx.resize(config.robIdxWidth.value + 1)
        val depth_val_ext = U(config.robDepth, config.robIdxWidth.value + 1 bits)
        val tempCount = UInt(log2Up(config.robDepth + 1) bits)

        when(t_ext >= h_ext) {
          tempCount := (t_ext - h_ext).resized
        } otherwise {
          tempCount := (t_ext - h_ext + depth_val_ext).resized
        }
        countAfterFlush := tempCount
        report(
          L"[ROB] FLUSH: ROLLBACK_TO_ROB_IDX calculated: new head=${headAfterFlush}, new tail=${tailAfterFlush}, new count=${countAfterFlush}"
        )

        // Clear statuses for entries from targetIdx (inclusive) up to tailAtFlushStart (exclusive)
        // These are the entries being squashed by the rollback.
        report(
          L"[ROB] FLUSH: Clearing statuses for entries from ${targetIdx} (inclusive) to ${tailAtFlushStart} (exclusive)"
        )
        for (k <- 0 until config.robDepth) {
          val currentRobEntryIdx = U(k, config.robIdxWidth)
          var shouldClearThisEntryInRollback = False

          if (config.robDepth > 0) { // Ensure robDepth is positive
            // Check if currentRobEntryIdx is in the range [targetIdx, tailAtFlushStart)
            // This range represents entries that were valid before flush and are now being squashed.
            val rangeIsEmptyOrInvalid =
              (targetIdx === tailAtFlushStart) // If new tail is old tail, no entries in this specific range to clear
            // (count logic handles the overall state)

            when(!rangeIsEmptyOrInvalid) {
              val noWrapInRange = targetIdx < tailAtFlushStart
              val wrapInRange = targetIdx > tailAtFlushStart // target is numerically larger, so range wraps

              when(noWrapInRange) {
                when(currentRobEntryIdx >= targetIdx && currentRobEntryIdx < tailAtFlushStart) {
                  shouldClearThisEntryInRollback := True
                }
              } elsewhen (wrapInRange) { // wrap
                when(currentRobEntryIdx >= targetIdx || currentRobEntryIdx < tailAtFlushStart) {
                  shouldClearThisEntryInRollback := True
                }
              }
            }
          }
          when(shouldClearThisEntryInRollback) {
            report(L"[ROB] FLUSH: Clearing status for ROB entry index ${currentRobEntryIdx}")
            statuses(currentRobEntryIdx).busy := False
            statuses(currentRobEntryIdx).done := False
            statuses(currentRobEntryIdx).hasException := False
            statuses(currentRobEntryIdx).exceptionCode := U(0, config.exceptionCodeWidth)
          }
        }
      }
      default { // Should include FlushReason.NONE if it can be valid with io.flush.valid
        // This case should ideally not be hit if io.flush.valid implies a meaningful flush reason.
        // If io.flush.valid is true but reason is NONE, treat as no-op for pointers/count from flush perspective.
        // Pointers will take on alloc/commit values if this path is taken and then the 'otherwise' branch for flush.
        // However, the outer when(io.flush.valid) means we *must* assign to _reg here.
        // So, if reason is NONE, we effectively override alloc/commit with current _reg values.
        report(
          L"[ROB] FLUSH: WARNING - Flush valid but reason is NONE or unhandled. No change from flush logic itself."
        )
        headAfterFlush := headAtFlushStart
        tailAfterFlush := tailAtFlushStart
        countAfterFlush := countAtFlushStart
      }
    }

    headPtr_reg := headAfterFlush
    tailPtr_reg := tailAfterFlush
    count_reg := countAfterFlush

    report(
      Seq(
        L"[ROB] FLUSH: Pointers AFTER flush logic: headPtr_reg=${headPtr_reg}, ",
        L"tailPtr_reg=${tailPtr_reg}, count_reg=${count_reg}"
      )
    )

  } otherwise { // No flush request
    io.flushed := False
    headPtr_reg := nextHeadFromOps
    tailPtr_reg := nextTailFromOps
    count_reg := nextCountFromOps
    report(
      Seq(
        L"[ROB] POINTER_UPDATE (No Flush): nextHead=${nextHeadFromOps}, nextTail=${nextTailFromOps}, nextCount=${nextCountFromOps} (from currentH=${currentHead}, ",
        L"currentT=${currentTail}, currentC=${currentCount}, alloc=${numActuallyAllocatedThisCycle}, commit=${numToCommit})"
      )
    )
  }
  // --- MODIFICATION END ---

  // --- Status Outputs ---
  io.empty := count_reg === 0 // Based on the final updated count_reg
  io.headPtrOut := headPtr_reg
  io.tailPtrOut := tailPtr_reg
  io.countOut := count_reg

  // Report final state of actual registers for the cycle end
  report(
    L"[ROB] CYCLE_END_REG_VALUES: headPtr_reg=${headPtr_reg}, tailPtr_reg=${tailPtr_reg}, count_reg=${count_reg}, empty_reg=${(count_reg === 0)}"
  )
}
