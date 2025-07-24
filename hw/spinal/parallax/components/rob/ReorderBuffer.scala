package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.decode._
import parallax.utilities.ParallaxSim
import _root_.parallax.utilities.ParallaxLogger

import spinal.core._
import parallax.utilities.Formattable

object ROBFlushReason extends SpinalEnum {
  val NONE,                // No flush, or an invalid reason
  FULL_FLUSH,          // Complete flush of the ROB and dependent structures (e.g., after major exception)
  ROLLBACK_TO_ROB_IDX, // Rollback ROB state to a specific instruction (e.g., branch mispredict, LSU replay)
  EXCEPTION_HANDLING = newElement()  // Flush due to an exception being handled (might be similar to FULL_FLUSH or specific rollback)
}

// --- Configuration for ROB ---
case class ROBConfig[RU <: Data with Formattable with HasRobPtr](
    val robDepth: Int,
    val pcWidth: BitCount,
    val commitWidth: Int,
    val allocateWidth: Int,
    val numWritebackPorts: Int,
    val uopType: HardType[RU],
    val defaultUop: () => RU,
    val exceptionCodeWidth: BitCount = 8 bits
) extends Formattable {

  require(robDepth > 0 && isPow2(robDepth), "ROB depth must be a positive power of 2.")
  require(commitWidth > 0 && commitWidth <= robDepth)
  require(allocateWidth > 0 && allocateWidth <= robDepth)
  require(numWritebackPorts > 0)
  require(uopType != null)
  require(defaultUop != null)
  
  val robPhysIdxWidth: BitCount = log2Up(robDepth) bits
  val robGenBitWidth: BitCount = 1 bits
  val robPtrWidth: BitCount = robPhysIdxWidth + robGenBitWidth

  def format(): Seq[Any] = {
    val str = L"""ROBConfig(
        robDepth: ${robDepth},
        pcWidth: ${pcWidth},
        commitWidth: ${commitWidth},
        allocateWidth: ${allocateWidth},
        numWritebackPorts: ${numWritebackPorts},
        uopType: ${uopType.getClass.getName},
        defaultUop: ${defaultUop.getClass.getName},
        defaultUop.width: ${defaultUop().robPtr.getWidth},
        exceptionCodeWidth: ${exceptionCodeWidth},
        robPhysIdxWidth: ${robPhysIdxWidth},
        robGenBitWidth: ${robGenBitWidth},
        robPtrWidth: ${robPtrWidth}
      )"""
    str
  }
}

object FlushReason extends SpinalEnum {
  val NONE, FULL_FLUSH, ROLLBACK_TO_ROB_IDX = newElement()
}

case class ROBFlushPayload(robPtrWidth: BitCount) extends Bundle {
  val reason = FlushReason()
  val targetRobPtr = UInt(robPtrWidth) 
}

// --- Data Bundles for ROB Internal Storage ---
case class ROBStatus[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val busy = Bool()
  val done = Bool() // 已经完成了其执行阶段，并且其结果（包括数据结果、分支预测是否正确、是否发生异常等）已经写入 ROB。
  val isMispredictedBranch = Bool()
  val isTaken = Bool()
  val result = Bits(config.pcWidth)
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth)
  val genBit = Bool()
}

case class ROBPayload[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val uop = config.uopType()
  val pc = UInt(config.pcWidth)
}

case class ROBFullEntry[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val payload = ROBPayload(config)
  val status = ROBStatus(config)
}

// --- IO Bundles for ROB (原始版本) ---
case class ROBAllocateSlot[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val valid = Bool()
  val uopIn = config.uopType()
  val pcIn = UInt(config.pcWidth)
  val robPtr = UInt(config.robPtrWidth) 
  val ready = Bool()

  override def asMaster(): Unit = {
    in(valid, uopIn, pcIn)
    out(robPtr, ready)
  }
}

case class ROBWritebackPort[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val fire = Bool()
  val robPtr = UInt(config.robPtrWidth) 
  val isTaken = Bool()
  val isMispredictedBranch = Bool()
  val result = Bits(config.pcWidth)
  val exceptionOccurred = Bool()
  val exceptionCodeIn = UInt(config.exceptionCodeWidth)

  override def asMaster(): Unit = {
    in(fire, robPtr, isMispredictedBranch, isTaken, result, exceptionOccurred, exceptionCodeIn)
  }

  def setDefault(): Unit = {
    this.fire := False
    this.robPtr.assignDontCare()
    this.isMispredictedBranch.assignDontCare()
    this.isTaken.assignDontCare()
    this.result.assignDontCare()
    this.exceptionOccurred.assignDontCare()
    this.exceptionCodeIn.assignDontCare()
  }
}

case class ROBCommitSlot[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val valid = Bool() // 当前持有一个有效的、可以被检查是否可以提交的指令条目。但不一定可以提交。
  val canCommit = Bool() // 满足了所有提交的条件，只需要一个 ack 就可以完成提交
  val entry = ROBFullEntry(config)

  override def asMaster(): Unit = {
    out(valid, canCommit, entry)
  }

  def asFlow: Flow[ROBFullEntry[RU]] = {
    val ret = Flow(ROBFullEntry(config))
    ret.valid := this.canCommit
    ret.payload := this.entry
    ret
  }
}

case class ROBIo[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU]) extends Bundle with IMasterSlave {
  val allocate = Vec(master(ROBAllocateSlot(config)), config.allocateWidth)
  val canAllocate = out Vec (Bool(), config.allocateWidth)
  val writeback = Vec(master(ROBWritebackPort(config)), config.numWritebackPorts)
  val commit = Vec(slave(ROBCommitSlot(config)), config.commitWidth)
  val commitAck = in Vec (Bool(), config.commitWidth)
  val flush = slave Flow (ROBFlushPayload(config.robPtrWidth))
  val flushed = out Bool ()
  val empty = out Bool ()
  val headPtrOut = out UInt (config.robPtrWidth) 
  val tailPtrOut = out UInt (config.robPtrWidth)
  val countOut = out UInt (log2Up(config.robDepth + 1) bits)

  def asMaster(): Unit = {
    allocate.foreach(_.asSlave())
    writeback.foreach(_.asSlave())
    commit.foreach(_.asSlave())
    master(flush)
    in(flushed)
    in(canAllocate)
    out(commitAck)
    in(empty, headPtrOut, tailPtrOut, countOut)
  }
}

class ReorderBuffer[RU <: Data with Formattable with HasRobPtr](config: ROBConfig[RU]) extends Component {
  val enableLog = false
  ParallaxLogger.log(
    s"Creating ReorderBuffer with config: ${config.format().mkString("")}"
  )
  val io = slave(ROBIo(config))

  // ==========================================================================================
  // --- 内部实现逻辑开始 ---
  // ==========================================================================================
  
  // --- 1. State Registers ---
  val payloads = Mem(ROBPayload(config), config.robDepth)
  val statuses = Vec(Reg(ROBStatus(config)), config.robDepth)

  for (i <- 0 until config.robDepth) {
    statuses(i).busy init(False)
    statuses(i).done init(False)
    statuses(i).hasException init(False)
    statuses(i).exceptionCode init(0)
    statuses(i).genBit init(False)
  }

  val headPtr_reg = Reg(UInt(config.robPtrWidth)) init(0)
  val tailPtr_reg = Reg(UInt(config.robPtrWidth)) init(0)
  val count_reg = Reg(UInt(log2Up(config.robDepth + 1) bits)) init(0)

  // --- 2. Combinational Logic for this cycle's operations ---
  
  // --- 2a. Allocation Logic (无环路 ready/fire 计算) ---
  val slotWillAllocate = Vec(Bool(), config.allocateWidth)

  if (enableLog) report(L"[ROB] CYCLE_START: head=${headPtr_reg}, tail=${tailPtr_reg}, count=${count_reg}")

  for (i <- 0 until config.allocateWidth) {
    val allocPort = io.allocate(i)
    val numPrevSlotsFiring = if (i == 0) U(0) else CountOne(slotWillAllocate.take(i))
    val spaceAvailable = (count_reg + numPrevSlotsFiring) < config.robDepth
    
    allocPort.ready := spaceAvailable
    io.canAllocate(i) := spaceAvailable
    slotWillAllocate(i) := allocPort.valid && allocPort.ready

    val robPtrForThisSlot = tailPtr_reg + numPrevSlotsFiring
    allocPort.robPtr := robPtrForThisSlot
        if(enableLog) report(L"[ROB] ALLOC_PORT_DRIVE[${i}]: Driving ready(spaceAvailable)=${spaceAvailable}")

    // **新增日志: 打印getAllocatePorts的valid/ready状态**
    if (enableLog) {
      report(L"[ROB] ALLOC_PORT[${i}]: valid=${allocPort.valid}, ready=${allocPort.ready}, fire=${slotWillAllocate(i)}")
    }
  }
  val numActuallyAllocatedThisCycle = CountOne(slotWillAllocate)

  // --- 2b. Commit Logic ---
  // CRITICAL FIX: Enhanced flush state tracking with pipeline delay to prevent timing races
  // The core issue is that flush signals arrive at the exact same cycle as commit decisions,
  // creating a race condition where speculative instructions commit before flush takes effect
  val flushInProgressReg = RegInit(False) 
  val flushWasActiveLastCycle = RegNext(io.flush.valid, False)  // KEY: Previous cycle flush detection
  val flushProcessedThisCycle = io.flush.valid
  
  // Multi-cycle flush state management for robust timing
  when(io.flush.valid) {
    flushInProgressReg := True
  } elsewhen(flushWasActiveLastCycle) {
    flushInProgressReg := False  // Clear flush state the cycle after flush was processed
  }
  
  if(enableLog) when(io.flush.valid) {
    report(L"[ROB] FLUSH_STATE: Starting flush, setting flushInProgress=True")
  }

  if(enableLog) when(flushWasActiveLastCycle) {
    report(L"[ROB] FLUSH_STATE: Clearing flushInProgress after flush processing")
  }
  
  val canCommitFlags = Vec(Bool(), config.commitWidth)
  val actualCommittedMask = Vec(Bool(), config.commitWidth)
  
  for (i <- 0 until config.commitWidth) {
    val currentCommitFullIdx = headPtr_reg + U(i)
    val currentCommitPhysIdx = currentCommitFullIdx.resize(config.robPhysIdxWidth)
    val currentCommitGenBit = currentCommitFullIdx.msb
    val currentStatus = statuses(currentCommitPhysIdx)
    
    val flushBlocking = io.flush.valid || flushInProgressReg || flushWasActiveLastCycle
    canCommitFlags(i) := !flushBlocking && 
                        (count_reg > U(i)) && currentStatus.done && (currentCommitGenBit === currentStatus.genBit)
    
    io.commit(i).valid := count_reg > U(i)
    io.commit(i).canCommit := canCommitFlags(i)
    io.commit(i).entry.payload := payloads.readAsync(address = currentCommitPhysIdx)
    io.commit(i).entry.status := currentStatus

    val prevCommitsAccepted = if (i == 0) True else actualCommittedMask(i - 1)
    actualCommittedMask(i) := prevCommitsAccepted && canCommitFlags(i) && io.commitAck(i)

    if(enableLog) when(count_reg > U(i)) {
      report(L"[ROB] COMMIT_CHECK[${i}]: ptr=${currentCommitFullIdx}, done=${currentStatus.done}, genMatch=${currentCommitGenBit === currentStatus.genBit}, flushBlocking=${flushBlocking} -> canCommitFlags(i)=${canCommitFlags(i)}. io.commitAck(i)=${io.commitAck(i)} -> actualCommit=${actualCommittedMask(i)}")
      when(flushBlocking) {
        report(L"[ROB] COMMIT_BLOCKED[${i}]: Commit blocked due to flush (current=${io.flush.valid}, inProgress=${flushInProgressReg}, wasActive=${flushWasActiveLastCycle})")
      }
    }
  }
  val numToCommit = CountOne(actualCommittedMask)
  when(numToCommit > 0) {
    report(L"[ROB] COMMIT_SUMMARY: Committing ${numToCommit} entries, total entries=${count_reg}, capacity=${config.robDepth}")
  }
  if(enableLog) when(numToCommit > 0) { report(L"[ROB] COMMIT_SUMMARY: Committing ${numToCommit} entries.") }


  // --- 3. Compute Next State for Pointers and Count ---
  val nextHead = UInt(config.robPtrWidth)
  val nextTail = UInt(config.robPtrWidth)
  val nextCount = UInt(log2Up(config.robDepth + 1) bits)

  nextHead := headPtr_reg + numToCommit
  nextTail := tailPtr_reg + numActuallyAllocatedThisCycle
  nextCount := count_reg + numActuallyAllocatedThisCycle - numToCommit

  // --- 4. High-priority Flush Logic ---
  io.flushed := False
  when(io.flush.valid) {
    io.flushed := True
    if(enableLog) report(L"[ROB] FLUSH_CMD: reason=${io.flush.payload.reason}, target=${io.flush.payload.targetRobPtr}")

    switch(io.flush.payload.reason) {
      is(FlushReason.FULL_FLUSH) {
        if(enableLog) report(L"[ROB] FLUSH_ACTION: FULL_FLUSH")
        nextHead := 0
        nextTail := 0
        nextCount := 0
      }
      is(FlushReason.ROLLBACK_TO_ROB_IDX) {
        val targetPtr = io.flush.payload.targetRobPtr
        if(enableLog) report(L"[ROB] FLUSH_ACTION: ROLLBACK to ${targetPtr} from head=${headPtr_reg}")
        
        val newCount = UInt(config.robPtrWidth.value + 1 bits)
        val h_ext = headPtr_reg.resize(config.robPtrWidth.value + 1)
        val t_ext = targetPtr.resize(config.robPtrWidth.value + 1)
        
        when (t_ext >= h_ext) {
            newCount := t_ext - h_ext
        } otherwise {
            newCount := t_ext - h_ext + (U(1) << config.robPtrWidth.value)
        }
        
        nextHead := headPtr_reg
        nextTail := targetPtr
        nextCount := newCount.resized

        if(enableLog) report(L"[ROB] FLUSH_RESULT: newHead=${nextHead}, newTail=${nextTail}, newCount=${nextCount}")
      }
      default {
         if(enableLog) report(L"[ROB] FLUSH_WARN: Flush valid but reason is NONE. No state change from flush.")
      }
    }
  }
  
  // --- 5. Final Register Updates ---
  headPtr_reg := nextHead
  tailPtr_reg := nextTail
  count_reg := nextCount
  
  if (enableLog) report(L"[ROB] CYCLE_END: regs will be updated to: head=${nextHead}, tail=${nextTail}, count=${nextCount}")

  // --- 6. Concurrent Writes to Memories/RegVecs ---
  
  for (i <- 0 until config.allocateWidth) {
    when(slotWillAllocate(i)) {
      val numPrevAllocs = if (i == 0) U(0) else CountOne(slotWillAllocate.take(i))
      val ptr = tailPtr_reg + numPrevAllocs
      val physIdx = ptr.resize(config.robPhysIdxWidth)
      val genBit = ptr.msb

      val newPayload = ROBPayload(config).allowOverride
      newPayload.uop := io.allocate(i).uopIn
      newPayload.uop.robPtr := ptr
      newPayload.pc := io.allocate(i).pcIn
      payloads.write(address = physIdx, data = newPayload)
      
      statuses(physIdx).busy := True
      statuses(physIdx).done := False
      statuses(physIdx).hasException := False
      statuses(physIdx).genBit := genBit

      if (enableLog) report(L"[ROB] ALLOC_WRITE[${i}]: ptr=${ptr} -> status.busy=T, genBit=${genBit}")
    }
  }

  for (wbPort <- io.writeback) {
    val wbPhysIdx = wbPort.robPtr.resize(config.robPhysIdxWidth)
    val wbGenBit = wbPort.robPtr.msb
    val statusBeforeWb = statuses(wbPhysIdx)
    
    when(!io.flush.valid && wbPort.fire && wbGenBit === statusBeforeWb.genBit) {
      statuses(wbPhysIdx).busy := False
      statuses(wbPhysIdx).done := True
      statuses(wbPhysIdx).isMispredictedBranch := wbPort.isMispredictedBranch
      statuses(wbPhysIdx).isTaken := wbPort.isTaken
      statuses(wbPhysIdx).result := wbPort.result
      statuses(wbPhysIdx).hasException := wbPort.exceptionOccurred
      statuses(wbPhysIdx).exceptionCode := Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode)
      if (enableLog) report(L"[ROB] WB_WRITE: ptr=${wbPort.robPtr} -> status.done=T")
    } elsewhen(wbPort.fire) {
        if (enableLog) report(L"[ROB] WB_WARN: Writeback for ptr=${wbPort.robPtr} ignored due to genBit mismatch.")
    }
  }

  when(io.flush.valid) {
    switch(io.flush.payload.reason) {
      is(FlushReason.FULL_FLUSH) {
        for (k <- 0 until config.robDepth) {
          statuses(k).busy := False
          statuses(k).done := False
          statuses(k).hasException := False
          statuses(k).genBit := False
        }
      }
      is(FlushReason.ROLLBACK_TO_ROB_IDX) {
        val targetPtr = io.flush.payload.targetRobPtr
        val oldTailPtr = tailPtr_reg
        for (k <- 0 until config.robDepth) {
            val physIdx = U(k, config.robPhysIdxWidth)
            val storedGen = statuses(physIdx).genBit
            val fullIdx = (storedGen ## physIdx.resize(config.robPhysIdxWidth)).asUInt
            
            val target_ext = targetPtr.resize(config.robPtrWidth.value + 1)
            val oldTail_ext = oldTailPtr.resize(config.robPtrWidth.value + 1)
            val current_ext = fullIdx.resize(config.robPtrWidth.value + 1)

            val isInSquashedRange = Bool()
            when (oldTail_ext >= target_ext) {
                isInSquashedRange := current_ext >= target_ext && current_ext < oldTail_ext
            } otherwise {
                isInSquashedRange := current_ext >= target_ext || current_ext < oldTail_ext
            }

            when(isInSquashedRange) {
                if (enableLog) report(L"[ROB] FLUSH_CLEAR: Squashing status for physIdx ${physIdx} (full ptr ${fullIdx})")
                statuses(physIdx).busy := False
                statuses(physIdx).done := False
                statuses(physIdx).hasException := False
            }
        }
      }
      default {}
    }
  }

  // --- 7. Final Status Outputs ---
  io.empty := count_reg === 0
  io.headPtrOut := headPtr_reg
  io.tailPtrOut := tailPtr_reg
  io.countOut := count_reg

  // **新增日志: 打印输出的 count**
  if(enableLog) {
    report(L"[ROB] IO_OUTPUT: empty=${io.empty}, headPtrOut=${io.headPtrOut}, tailPtrOut=${io.tailPtrOut}, countOut=${io.countOut}")
  }
}
