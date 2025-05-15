package boson.demo2.components.rename // Or your chosen package

import spinal.core._
import spinal.lib._
// Assuming MicroOp, MicroOpConfig, and related enums are in boson.demo2.components.decode
import boson.demo2.components.decode._ // Make sure this path is correct
import boson.demo2.utils.Encoders.PriorityEncoderOH

// --- Configuration for ROB ---
case class ROBConfig(
    robDepth: Int,
    microOpConfig: MicroOpConfig, // From decode package
    commitWidth: Int,
    allocateWidth: Int,
    // Number of writeback ports for execution units.
    // Can be different from commit/allocate widths.
    // For example, if you have 2 ALUs, 1 LSU completing per cycle.
    numWritebackPorts: Int,
    exceptionCodeWidth: BitCount = 8 bits,
    pcWidth: BitCount = 32 bits
) {
  require(robDepth > 0 && isPow2(robDepth), "ROB depth must be a positive power of 2.")
  require(commitWidth > 0 && commitWidth <= robDepth)
  require(allocateWidth > 0 && allocateWidth <= robDepth)
  require(numWritebackPorts > 0)
  val robIdxWidth: BitCount = log2Up(robDepth) bits
}

// --- Data Bundles for ROB Internal Storage ---
// Status part of the ROB entry (frequently updated)
case class ROBStatus(config: ROBConfig) extends Bundle {
  val busy = Bool()
  val done = Bool()
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth)
}

// Payload part of the ROB entry (written once at allocation)
case class ROBPayload(config: ROBConfig) extends Bundle {
  val uop = MicroOp(config.microOpConfig) // Contains physReg info
  val pc = UInt(config.pcWidth)
}

// Combined entry for communication (e.g., commit port)
case class ROBFullEntry(config: ROBConfig) extends Bundle {
  val payload = ROBPayload(config)
  val status = ROBStatus(config)
}

// --- IO Bundles for ROB ---

// For allocation port (per slot)
case class ROBAllocateSlot(config: ROBConfig) extends Bundle with IMasterSlave {
  val fire = Bool() // From Rename: attempt to allocate this slot
  val uopIn = MicroOp(config.microOpConfig)
  val pcIn = UInt(config.pcWidth)
  val robIdx = UInt(config.robIdxWidth) // To Rename: allocated ROB index for this slot

  override def asMaster(): Unit = { // Perspective of Rename Stage
    in(fire, uopIn, pcIn)
    out(robIdx)
  }
}

// For writeback port (per execution unit writeback)
case class ROBWritebackPort(config: ROBConfig) extends Bundle with IMasterSlave {
  val fire = Bool()
  val robIdx = UInt(config.robIdxWidth)
  // isDone is implicit if fire is true for a normal writeback.
  val exceptionOccurred = Bool()
  val exceptionCodeIn = UInt(config.exceptionCodeWidth)

  override def asMaster(): Unit = { // Perspective of Execute Stage
    // out(fire, robIdx, exceptionOccurred, exceptionCodeIn)
    in(fire, robIdx, exceptionOccurred, exceptionCodeIn)
  }
}

// For commit port (per slot)
case class ROBCommitSlot(config: ROBConfig) extends Bundle with IMasterSlave {
  val valid = Bool() // From ROB: this entry is ready to commit
  val entry = ROBFullEntry(config) // From ROB: data of the entry

  override def asMaster(): Unit = { // Perspective of ROB
    out(valid, entry)
  }
}

// For flush command (restoring ROB pointers)
case class ROBFlushCommand(config: ROBConfig) extends Bundle {
  val newHead = UInt(config.robIdxWidth)
  val newTail = UInt(config.robIdxWidth)
  val newCount = UInt(log2Up(config.robDepth + 1) bits)
}

// Top-level IO for ReorderBuffer
case class ROBIo(config: ROBConfig) extends Bundle with IMasterSlave {
  // Allocation: Vector of master ports from ROB's perspective (Rename stage is slave)
  val allocate = Vec(master(ROBAllocateSlot(config)), config.allocateWidth)
  val canAllocate = out Vec (Bool(), config.allocateWidth) // To Rename: can each slot be allocated?

  // Writeback: Vector of master ports from ROB's perspective (Execute stage is slave)
  val writeback = Vec(master(ROBWritebackPort(config)), config.numWritebackPorts)

  // Commit: Vector of slave ports from ROB's perspective (Commit stage is master)
  val commit = Vec(slave(ROBCommitSlot(config)), config.commitWidth)
  val commitFire = in Vec (Bool(), config.commitWidth) // From Commit: which slots are actually committed

  // Flush: Slave Flow port from ROB's perspective (Recovery/Checkpoint manager is master)
  val flush = slave Flow (ROBFlushCommand(config))

  // Status outputs
  val empty = out Bool ()
  val headPtrOut = out UInt (config.robIdxWidth)
  val tailPtrOut = out UInt (config.robIdxWidth)
  val countOut = out UInt (log2Up(config.robDepth + 1) bits)

  def asMaster(): Unit = { // Perspective of ROB
    allocate.foreach(_.asSlave())
    writeback.foreach(_.asSlave())
    commit.foreach(_.asSlave())
    in(canAllocate)
    out(commitFire)
    in(empty, headPtrOut, tailPtrOut, countOut)
    master(flush)
  }
}

class ReorderBuffer(val config: ROBConfig) extends Component {
  val io = slave(ROBIo(config))

  // --- Internal Storage ---
  // Payload part (written once at allocation, read at commit)
  val payloads = Mem(ROBPayload(config), config.robDepth)
  // Status part (updated at allocation and writeback, read at commit)
  val statuses = Vec(Reg(ROBStatus(config)), config.robDepth)

  // Initialize memory/registers to avoid Xes in simulation and define reset state
  payloads.init(
    Seq.fill(config.robDepth)(
      {
        val defaultPayload = ROBPayload(config)
        val defaultMicroOp = MicroOp(config.microOpConfig)
        defaultMicroOp.setDefault()
        defaultPayload.uop := defaultMicroOp
        defaultPayload.pc := U(0)
        defaultPayload
      }
    )
  )

  for (i <- 0 until config.robDepth) {
    statuses(i).busy init (False)
    statuses(i).done init (False)
    statuses(i).hasException init (False)
    statuses(i).exceptionCode init (0)
  }

  // --- Pointers and Count ---
  val headPtr = Reg(UInt(config.robIdxWidth)) init (0)
  val tailPtr = Reg(UInt(config.robIdxWidth)) init (0)
  val count = Reg(UInt(log2Up(config.robDepth + 1) bits)) init (0)

 // --- Allocation Logic (Superscalar) ---
  val slotWillAllocate = Vec(Bool(), config.allocateWidth) // For each slot, will it actually allocate?
  val slotRobIdx = Vec(UInt(config.robIdxWidth), config.allocateWidth) // ROB index for each slot if it allocates

  // Calculate how many successful allocations happen *before* the current slot 'i'
  // And what the ROB tail pointer would be *before* considering slot 'i'
  val numPrevAllocations = Vec(UInt(log2Up(config.allocateWidth + 1) bits), config.allocateWidth + 1)
  val robIdxAtSlotStart = Vec(UInt(config.robIdxWidth), config.allocateWidth + 1)

  numPrevAllocations(0) := U(0) // Before slot 0, 0 previous allocations
  robIdxAtSlotStart(0) := tailPtr  // Before slot 0, robIdx starts at current tailPtr

  report(Seq("[ROB] ALLOC_PRE: Cycle Start. tailPtr=", tailPtr, ", count=", count, ", headPtr=", headPtr, ", empty=", (count === 0)))

  for (i <- 0 until config.allocateWidth) {
    val allocPort = io.allocate(i)

    // Space available considering allocations *before* this slot i
    val spaceAvailableForThisSlot = (count + numPrevAllocations(i)) < config.robDepth
    io.canAllocate(i) := spaceAvailableForThisSlot // Inform Rename stage about general availability for this slot position

    slotWillAllocate(i) := allocPort.fire && spaceAvailableForThisSlot
    slotRobIdx(i)       := robIdxAtSlotStart(i)
    allocPort.robIdx    := slotRobIdx(i) // Output the calculated ROB index
    
    when(allocPort.fire) {
      // Replace .physRegDest with a relevant field from your MicroOp or use allocPort.uopIn.toString if it's well-defined
      report(Seq("[ROB] ALLOC_ATTEMPT[", i.toString(), "]: fire=", allocPort.fire,
                 ", uopIn.physRegRdOld=", allocPort.uopIn.physRegRdOld,
                 ", uopIn.physRegRdNew=", allocPort.uopIn.physRegRdNew,
                 ", pcIn=", allocPort.pcIn,
                 ", spaceAvailableForSlot=", spaceAvailableForThisSlot,
                 ", currentCountPlusPrevAllocs=", (count + numPrevAllocations(i)),
                 ", robDepth=", U(config.robDepth),
                 ", calculatedRobIdx=", slotRobIdx(i)))
    }
    when(allocPort.fire && !spaceAvailableForThisSlot) {
      report(Seq("[ROB] ALLOC_ATTEMPT[", i.toString(), "]: Fired but NO SPACE. count=", count, ", numPrevAllocationsForThisSlot=", numPrevAllocations(i)))
    }

    // For the *next* slot (i+1), calculate its starting numPrevAllocations and robIdxAtSlotStart
    numPrevAllocations(i+1) := numPrevAllocations(i) + U(slotWillAllocate(i)) // Increment if this slot allocates
    robIdxAtSlotStart(i+1)  := robIdxAtSlotStart(i) + U(slotWillAllocate(i))  // Increment if this slot allocates

    when(slotWillAllocate(i)) {
      report(Seq("[ROB] ALLOC_DO[", i.toString(), "]: Allocating to robIdx=", slotRobIdx(i),
                 ", uopIn.physRegRdOld=", allocPort.uopIn.physRegRdOld,
                 ", uopIn.physRegRdNew=", allocPort.uopIn.physRegRdNew,
                 ", pcIn=", allocPort.pcIn))

      val newPayload = ROBPayload(config)
      newPayload.uop := allocPort.uopIn
      newPayload.pc  := allocPort.pcIn
      payloads.write(
        address = slotRobIdx(i), // Use the pre-calculated robIdx for this slot
        data    = newPayload
      )
      statuses(slotRobIdx(i)).busy         := True
      statuses(slotRobIdx(i)).done         := False
      statuses(slotRobIdx(i).resized).hasException := False //resized是因为slotRobIdx(i)可能因为robDepth为1而宽度为0，但statuses的索引至少需要1位
      statuses(slotRobIdx(i).resized).exceptionCode:= U(0, config.exceptionCodeWidth)
      
      report(Seq("[ROB] ALLOC_STATUS_SET[", i.toString(), "]: robIdx=", slotRobIdx(i), " set busy=T, done=F, hasException=F"))
    }
  }

  val numActuallyAllocatedThisCycle = numPrevAllocations(config.allocateWidth) // Total successful allocations this cycle

  when(numActuallyAllocatedThisCycle > 0) {
      report(Seq("[ROB] ALLOC_SUMMARY: numActuallyAllocatedThisCycle=", numActuallyAllocatedThisCycle,
                 ", currentTailPtr=", tailPtr, " -> newTailPtr=", (tailPtr + numActuallyAllocatedThisCycle),
                 ", currentCount=", count, " -> newCount (if only alloc happens)=", (count + numActuallyAllocatedThisCycle)))
  }

  // Update global pointers
  when(numActuallyAllocatedThisCycle > 0) {
    tailPtr := tailPtr + numActuallyAllocatedThisCycle
    count   := count + numActuallyAllocatedThisCycle
  }

  // --- Writeback Logic (Superscalar) ---
  for (portIdx <- 0 until config.numWritebackPorts) {
    val wbPort = io.writeback(portIdx)
    when(wbPort.fire) {
      report(Seq("[ROB] WRITEBACK[", portIdx.toString(), "]: Fired. robIdx=", wbPort.robIdx,
                 ", exceptionOccurred=", wbPort.exceptionOccurred,
                 ", exceptionCodeIn=", wbPort.exceptionCodeIn))
      
      // Report status BEFORE update for this specific robIdx
      // Note: If multiple wbPorts target the same robIdx in one cycle, 'old' values for later ports
      // might reflect intermediate combinational state due to earlier port updates in the same cycle.
      val statusBeforeWb = statuses(wbPort.robIdx) // Capture current state
      report(Seq("[ROB] WRITEBACK_STATUS_OLD[", portIdx.toString(), "]: robIdx=", wbPort.robIdx,
                 ", oldBusy=", statusBeforeWb.busy,
                 ", oldDone=", statusBeforeWb.done,
                 ", oldHasExcp=", statusBeforeWb.hasException,
                 ", oldExcpCode=", statusBeforeWb.exceptionCode))

      statuses(wbPort.robIdx).busy := False
      statuses(wbPort.robIdx).done := True
      statuses(wbPort.robIdx).hasException := wbPort.exceptionOccurred
      statuses(wbPort.robIdx).exceptionCode := Mux(
        wbPort.exceptionOccurred,
        wbPort.exceptionCodeIn,
        statuses(wbPort.robIdx).exceptionCode.resized // Retain old if no new exc.
      )
      
      report(Seq("[ROB] WRITEBACK_STATUS_NEW[", portIdx.toString(), "]: robIdx=", wbPort.robIdx,
                 ", newBusy=F",
                 ", newDone=T",
                 ", newHasExcp=", wbPort.exceptionOccurred,
                 ", newExcpCode=", Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode.resized))) // Show code being written
    }
  }

  // --- Commit Logic (Superscalar) ---
  val canCommitFlags = Vec(Bool(), config.commitWidth)
  report(Seq("[ROB] COMMIT_PRE: Cycle Start. headPtr=", headPtr, ", count=", count, ", empty=", (count === 0)))


  for (i <- 0 until config.commitWidth) {
    val currentCommitIdx = headPtr + U(i)
    val currentPayload = payloads.readAsync(address = currentCommitIdx)
    val currentStatus = statuses(currentCommitIdx) // Reads registered status at start of cycle (or as updated by WB this cycle)

    // Report status of entry being considered for commit
    when(count > U(i)) { // Only report for potentially valid entries
        report(Seq("[ROB] COMMIT_CHECK_SLOT[", i.toString(), "]: Considering commit. robIdx=", currentCommitIdx,
                   ", status.busy=", currentStatus.busy, ", status.done=", currentStatus.done,
                   ", status.hasException=", currentStatus.hasException,
                   ", status.exceptionCode=", currentStatus.exceptionCode,
                   ", uop.physRegRdOld=", currentPayload.uop.physRegRdOld,
                   ", uop.physRegRdNew=", currentPayload.uop.physRegRdNew,
                   ", pc=", currentPayload.pc,
                   ", count=", count
        ))
    }

    canCommitFlags(i) := (count > U(i)) && currentStatus.done

    io.commit(i).valid := canCommitFlags(i)
    io.commit(i).entry.payload := currentPayload
    io.commit(i).entry.status := currentStatus
    
    when(canCommitFlags(i)) {
        report(Seq("[ROB] COMMIT_VALID_SLOT[", i.toString(), "]: Slot IS VALID for commit. robIdx=", currentCommitIdx,
                   ", io.commit(i).valid=", io.commit(i).valid,
                   ", external commitFire(", i.toString() ,")=", io.commitFire(i)))
    }
    when(!canCommitFlags(i) && (count > U(i))) { // Potentially valid but not done
        report(Seq("[ROB] COMMIT_NOT_VALID_SLOT[", i.toString(), "]: Slot NOT VALID for commit. robIdx=", currentCommitIdx,
                   ", status.done=", currentStatus.done, ", count=", count,
                   ", external commitFire(", i.toString() ,")=", io.commitFire(i)))
    }
    when(io.commitFire(i) && !canCommitFlags(i)) {
        report(Seq("[ROB] COMMIT_WARN_SLOT[", i.toString(), "]: Commit stage fired for non-committable slot! robIdx=", currentCommitIdx,
                   ", canCommitFlag=", canCommitFlags(i),
                   ", status.done=", currentStatus.done, ", count=", count))
    }
  }

  // Calculate the number of entries that are actually committed this cycle.
  val actualCommittedMask = Vec(Bool(), config.commitWidth)
  if (config.commitWidth > 0) {
    actualCommittedMask(0) := canCommitFlags(0) && io.commitFire(0)
    report(Seq("[ROB] COMMIT_MASK_CALC[0]: canCommitFlags(0)=", canCommitFlags(0),
               ", io.commitFire(0)=", io.commitFire(0),
               ", -> actualCommittedMask(0)=", actualCommittedMask(0)))
    for (i <- 1 until config.commitWidth) {
      actualCommittedMask(i) := actualCommittedMask(i - 1) && canCommitFlags(i) && io.commitFire(i)
      report(Seq("[ROB] COMMIT_MASK_CALC[", i.toString(), "]: prevActualCommittedMask(",(i-1).toString(),")=", actualCommittedMask(i-1),
                 ", canCommitFlags(", i.toString() ,")=", canCommitFlags(i),
                 ", io.commitFire(", i.toString() ,")=", io.commitFire(i),
                 ", -> actualCommittedMask(", i.toString() ,")=", actualCommittedMask(i)))
    }
  }
  val numToCommit = if (config.commitWidth > 0) CountOne(actualCommittedMask) else U(0, 1 bits)

  when(numToCommit > 0) {
    report(Seq("[ROB] COMMIT_SUMMARY: numToCommit=", numToCommit,
               ", actualCommittedMask=", actualCommittedMask.asBits, // .asBits or .asBools
               ", currentHeadPtr=", headPtr, " -> newHeadPtr=", (headPtr + numToCommit.resized),
               ", currentCount=", count, " -> newCount (if only commit happens, or commit wins priority)=", (count - numToCommit.resized)))
  }

  when(numToCommit > 0) {
    headPtr := headPtr + numToCommit.resized // Ensure numToCommit is resized to headPtr's width if different
    count := count - numToCommit.resized // Ensure numToCommit is resized to count's width if different
  }

  // --- Flush Logic (Pointer Restore) ---
  when(io.flush.valid) {
    report(Seq("[ROB] FLUSH: Received flush command. Valid=", io.flush.valid))
    report(Seq("[ROB] FLUSH: Payload: newHead=", io.flush.payload.newHead,
               ", newTail=", io.flush.payload.newTail, ", newCount=", io.flush.payload.newCount))
    report(Seq("[ROB] FLUSH: Pointers BEFORE flush: headPtr=", headPtr, ", tailPtr=", tailPtr, ", count=", count))

    headPtr := io.flush.payload.newHead
    tailPtr := io.flush.payload.newTail
    count := io.flush.payload.newCount
    
    report(Seq("[ROB] FLUSH: Pointers AFTER flush (assigned values): headPtr=", io.flush.payload.newHead,
               ", tailPtr=", io.flush.payload.newTail, ", count=", io.flush.payload.newCount,
               " (These override alloc/commit effects on pointers for this cycle)"))
  }

  // --- Status Outputs ---
  io.empty := count === 0
  io.headPtrOut := headPtr
  io.tailPtrOut := tailPtr
  io.countOut := count

  // Report final state of pointers for the cycle (these are the .next values that will be registered)
  // The 'count' reported here will be the result of the prioritized updates (flush > commit > alloc)
  report(Seq("[ROB] CYCLE_END_STATE: headPtr=", headPtr, ", tailPtr=", tailPtr, ", count=", count, ", empty=", (count === 0)))
}
