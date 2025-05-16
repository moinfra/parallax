package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.decode._

// --- Configuration for ROB ---
case class ROBConfig(
    robDepth: Int,
    microOpConfig: MicroOpConfig,
    commitWidth: Int,
    allocateWidth: Int,
    // Number of writeback ports for execution units.
    // Can be different from commit/allocate widths.
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

  val payloads = Mem(ROBPayload(config), config.robDepth)
  val statuses = Vec(Reg(ROBStatus(config)), config.robDepth)

  payloads.init(Seq.fill(config.robDepth)(
    { val dp = ROBPayload(config); val dmu = MicroOp(config.microOpConfig); dmu.setDefault(); dp.uop := dmu; dp.pc := U(0); dp }
  ))
  for (i <- 0 until config.robDepth) {
    statuses(i).busy init(False); statuses(i).done init(False)
    statuses(i).hasException init(False); statuses(i).exceptionCode init(0)
  }

  // Actual registers for state
  val headPtr_reg = Reg(UInt(config.robIdxWidth)) init (0)
  val tailPtr_reg = Reg(UInt(config.robIdxWidth)) init (0)
  val count_reg   = Reg(UInt(log2Up(config.robDepth + 1) bits)) init (0)

  // Combinational view of current state for calculations this cycle
  // These are used by the allocation, commit, and other logic sections below.
  val currentHead = headPtr_reg
  val currentTail = tailPtr_reg
  val currentCount = count_reg

  val slotWillAllocate = Vec(Bool(), config.allocateWidth)
  val slotRobIdx = Vec(UInt(config.robIdxWidth), config.allocateWidth)
  val numPrevAllocations = Vec(UInt(log2Up(config.allocateWidth + 1) bits), config.allocateWidth + 1)
  val robIdxAtSlotStart = Vec(UInt(config.robIdxWidth), config.allocateWidth + 1)

  numPrevAllocations(0) := U(0)
  robIdxAtSlotStart(0) := currentTail // Use combinational currentTail
  report(Seq("[ROB] ALLOC_PRE: Cycle Start. tailPtr=", currentTail, ", count=", currentCount, ", headPtr=", currentHead, ", empty=", (currentCount === 0)))

  for (i <- 0 until config.allocateWidth) {
    val allocPort = io.allocate(i)
    // --- START OF MODIFIED SECTION ---
    val spaceAvailableForThisSlot = (currentCount + numPrevAllocations(i)) < config.robDepth // Use currentCount
    // --- END OF MODIFIED SECTION ---
    io.canAllocate(i) := spaceAvailableForThisSlot
    slotWillAllocate(i) := allocPort.fire && spaceAvailableForThisSlot
    slotRobIdx(i)       := robIdxAtSlotStart(i)
    allocPort.robIdx    := slotRobIdx(i)
    
    when(allocPort.fire) {
      report(Seq("[ROB] ALLOC_ATTEMPT[", i.toString(), "]: fire=", allocPort.fire,
                 ", uopIn.physRegRdOld=", allocPort.uopIn.physRegRdOld,
                 ", uopIn.physRegRdNew=", allocPort.uopIn.physRegRdNew,
                 ", pcIn=", allocPort.pcIn,
                 ", spaceAvailableForSlot=", spaceAvailableForThisSlot,
                 // --- START OF MODIFIED SECTION ---
                 ", currentCountPlusPrevAllocs=", (currentCount + numPrevAllocations(i)), // Use currentCount
                 // --- END OF MODIFIED SECTION ---
                 ", robDepth=", U(config.robDepth),
                 ", calculatedRobIdx=", slotRobIdx(i)))
    }
    when(allocPort.fire && !spaceAvailableForThisSlot) {
      // --- START OF MODIFIED SECTION ---
      report(Seq("[ROB] ALLOC_ATTEMPT[", i.toString(), "]: Fired but NO SPACE. count=", currentCount, ", numPrevAllocationsForThisSlot=", numPrevAllocations(i))) // Use currentCount
      // --- END OF MODIFIED SECTION ---
    }
    numPrevAllocations(i+1) := numPrevAllocations(i) + U(slotWillAllocate(i))
    robIdxAtSlotStart(i+1)  := robIdxAtSlotStart(i) + U(slotWillAllocate(i))
    when(slotWillAllocate(i)) {
      report(Seq("[ROB] ALLOC_DO[", i.toString(), "]: Allocating to robIdx=", slotRobIdx(i),
                 ", uopIn.physRegRdOld=", allocPort.uopIn.physRegRdOld,
                 ", uopIn.physRegRdNew=", allocPort.uopIn.physRegRdNew,
                 ", pcIn=", allocPort.pcIn))
      val newPayload = ROBPayload(config); newPayload.uop := allocPort.uopIn; newPayload.pc  := allocPort.pcIn
      payloads.write(address = slotRobIdx(i), data = newPayload)
      statuses(slotRobIdx(i)).busy         := True
      statuses(slotRobIdx(i)).done         := False
      // --- START OF MODIFIED SECTION ---
      // Removed .resized from statuses indices as slotRobIdx(i) should have correct width
      statuses(slotRobIdx(i)).hasException := False 
      statuses(slotRobIdx(i)).exceptionCode:= U(0, config.exceptionCodeWidth)
      // --- END OF MODIFIED SECTION ---
      report(Seq("[ROB] ALLOC_STATUS_SET[", i.toString(), "]: robIdx=", slotRobIdx(i), " set busy=T, done=F, hasException=F"))
    }
  }

  val numActuallyAllocatedThisCycle = numPrevAllocations(config.allocateWidth)
  when(numActuallyAllocatedThisCycle > 0) {
      // --- START OF MODIFIED SECTION ---
      report(Seq("[ROB] ALLOC_SUMMARY: numActuallyAllocatedThisCycle=", numActuallyAllocatedThisCycle,
                 ", currentTailPtr=", currentTail, " -> newTailPtr (calculated for next reg)=", (currentTail + numActuallyAllocatedThisCycle),
                 ", currentCount=", currentCount, " -> newCount (calculated for next reg)=", (currentCount + numActuallyAllocatedThisCycle))) // Use currentTail, currentCount
      // --- END OF MODIFIED SECTION ---
  }

  // --- Writeback Logic (Superscalar) ---
  for (portIdx <- 0 until config.numWritebackPorts) {
    val wbPort = io.writeback(portIdx)
    when(wbPort.fire) {
      report(Seq("[ROB] WRITEBACK[", portIdx.toString(), "]: Fired. robIdx=", wbPort.robIdx,
                 ", exceptionOccurred=", wbPort.exceptionOccurred,
                 ", exceptionCodeIn=", wbPort.exceptionCodeIn))
      val statusBeforeWb = statuses(wbPort.robIdx)
      report(Seq("[ROB] WRITEBACK_STATUS_OLD[", portIdx.toString(), "]: robIdx=", wbPort.robIdx,
                 ", oldBusy=", statusBeforeWb.busy, ", oldDone=", statusBeforeWb.done,
                 ", oldHasExcp=", statusBeforeWb.hasException, ", oldExcpCode=", statusBeforeWb.exceptionCode))
      statuses(wbPort.robIdx).busy := False; statuses(wbPort.robIdx).done := True
      statuses(wbPort.robIdx).hasException := wbPort.exceptionOccurred
      statuses(wbPort.robIdx).exceptionCode := Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statuses(wbPort.robIdx).exceptionCode.resized)
      report(Seq("[ROB] WRITEBACK_STATUS_NEW[", portIdx.toString(), "]: robIdx=", wbPort.robIdx,
                 ", newBusy=F", ", newDone=T", ", newHasExcp=", wbPort.exceptionOccurred,
                 ", newExcpCode=", Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode.resized)))
    }
  }

  // --- Commit Logic (Superscalar) ---
  val canCommitFlags = Vec(Bool(), config.commitWidth)
  // --- START OF MODIFIED SECTION ---
  report(Seq("[ROB] COMMIT_PRE: Cycle Start. headPtr=", currentHead, ", count=", currentCount, ", empty=", (currentCount === 0))) // Use currentHead, currentCount
  // --- END OF MODIFIED SECTION ---

  for (i <- 0 until config.commitWidth) {
    // --- START OF MODIFIED SECTION ---
    val currentCommitIdx = currentHead + U(i) // Use currentHead
    // --- END OF MODIFIED SECTION ---
    val currentPayload = payloads.readAsync(address = currentCommitIdx)
    val currentStatus = statuses(currentCommitIdx)
    // --- START OF MODIFIED SECTION ---
    when(currentCount > U(i)) { // Use currentCount
        report(Seq("[ROB] COMMIT_CHECK_SLOT[", i.toString(), "]: Considering commit. robIdx=", currentCommitIdx,
                   ", status.busy=", currentStatus.busy, ", status.done=", currentStatus.done,
                   ", status.hasException=", currentStatus.hasException,
                   ", status.exceptionCode=", currentStatus.exceptionCode,
                   ", uop.physRegRdOld=", currentPayload.uop.physRegRdOld,
                   ", uop.physRegRdNew=", currentPayload.uop.physRegRdNew,
                   ", pc=", currentPayload.pc,
                   ", count=", currentCount // Use currentCount
        ))
    }
    canCommitFlags(i) := (currentCount > U(i)) && currentStatus.done // Use currentCount
    // --- END OF MODIFIED SECTION ---
    io.commit(i).valid := canCommitFlags(i)
    io.commit(i).entry.payload := currentPayload
    io.commit(i).entry.status := currentStatus
    when(canCommitFlags(i)) {
        report(Seq("[ROB] COMMIT_VALID_SLOT[", i.toString(), "]: Slot IS VALID for commit. robIdx=", currentCommitIdx,
                   ", io.commit(i).valid=", io.commit(i).valid,
                   ", external commitFire(", i.toString() ,")=", io.commitFire(i)))
    }
    // --- START OF MODIFIED SECTION ---
    when(!canCommitFlags(i) && (currentCount > U(i))) { // Use currentCount
        report(Seq("[ROB] COMMIT_NOT_VALID_SLOT[", i.toString(), "]: Slot NOT VALID for commit. robIdx=", currentCommitIdx,
                   ", status.done=", currentStatus.done, ", count=", currentCount, // Use currentCount
                   ", external commitFire(", i.toString() ,")=", io.commitFire(i)))
    }
    when(io.commitFire(i) && !canCommitFlags(i)) {
        report(Seq("[ROB] COMMIT_WARN_SLOT[", i.toString(), "]: Commit stage fired for non-committable slot! robIdx=", currentCommitIdx,
                   ", canCommitFlag=", canCommitFlags(i),
                   ", status.done=", currentStatus.done, ", count=", currentCount)) // Use currentCount
    }
    // --- END OF MODIFIED SECTION ---
  }
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
      // --- START OF MODIFIED SECTION ---
      report(Seq("[ROB] COMMIT_SUMMARY: numToCommit=", numToCommit,
                 ", actualCommittedMask=", actualCommittedMask.asBits,
                 ", currentHeadPtr=", currentHead, " -> newHeadPtr (calculated for next reg)=", (currentHead + numToCommit.resized),
                 ", currentCount=", currentCount, " -> newCount (calculated for next reg)=", (currentCount - numToCommit.resized))) // Use currentHead, currentCount
      // --- END OF MODIFIED SECTION ---
  }

  // --- START OF MODIFIED SECTION ---
  // --- Pointer and Count Update Logic ---
  // Calculate the next state based on this cycle's operations
  val nextHead = UInt(config.robIdxWidth)
  val nextTail = UInt(config.robIdxWidth)
  val nextCount = UInt(log2Up(config.robDepth + 1) bits)

  // Default to no change from registered values for pointers
  nextHead := currentHead
  nextTail := currentTail
  // nextCount will be calculated based on net effect

  // Effect of allocation on tail pointer
  when(numActuallyAllocatedThisCycle > 0) {
    nextTail := currentTail + numActuallyAllocatedThisCycle
  }

  // Effect of commit on head pointer
  when(numToCommit > 0) {
    nextHead := currentHead + numToCommit.resized
  }

  // Calculate next count based on net effect of alloc and commit
  val sAlloc = S(numActuallyAllocatedThisCycle.resize(nextCount.getWidth bits))
  val sCommit = S(numToCommit.resize(nextCount.getWidth bits))
  val sCurrentCount = S(currentCount.resize(nextCount.getWidth bits))
  
  nextCount := (sCurrentCount + sAlloc - sCommit).asUInt.resize(nextCount.getWidth bits)

  // Apply updates to registers, with flush having highest priority
  when(io.flush.valid) {
    report(Seq("[ROB] FLUSH: Received flush command. Valid=", io.flush.valid))
    report(Seq("[ROB] FLUSH: Payload: newHead=", io.flush.payload.newHead,
               ", newTail=", io.flush.payload.newTail, ", newCount=", io.flush.payload.newCount))
    report(Seq("[ROB] FLUSH: Pointers BEFORE flush: headPtr=", currentHead, ", tailPtr=", currentTail, ", count=", currentCount))

    headPtr_reg := io.flush.payload.newHead
    tailPtr_reg := io.flush.payload.newTail
    count_reg   := io.flush.payload.newCount
    
    report(Seq("[ROB] FLUSH: Pointers AFTER flush (assigned values): headPtr=", io.flush.payload.newHead,
               ", tailPtr=", io.flush.payload.newTail, ", count=", io.flush.payload.newCount,
               " (These override alloc/commit effects on pointers for this cycle)"))
  } otherwise {
    headPtr_reg := nextHead
    tailPtr_reg := nextTail
    count_reg   := nextCount
    report(Seq("[ROB] POINTER_UPDATE: nextHead=", nextHead, ", nextTail=", nextTail, ", nextCount=", nextCount, 
               " (from currentH=", currentHead, ", currentT=", currentTail, ", currentC=", currentCount, 
               ", alloc=", numActuallyAllocatedThisCycle, ", commit=", numToCommit,")" ))
  }
  // --- Status Outputs ---
  io.empty      := count_reg === 0       // Use registered value for output
  io.headPtrOut := headPtr_reg     // Use registered value for output
  io.tailPtrOut := tailPtr_reg     // Use registered value for output
  io.countOut   := count_reg       // Use registered value for output

  // Report final state of actual registers for the cycle end
  report(Seq("[ROB] CYCLE_END_REG_VALUES: headPtr_reg=", headPtr_reg, ", tailPtr_reg=", tailPtr_reg, ", count_reg=", count_reg, ", empty_reg=", (count_reg === 0)))
}
