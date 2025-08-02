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
  val targetPc = UInt(config.pcWidth)
  val isTaken = Bool()
  val result = Bits(config.pcWidth)
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth)
  // val pendingRetirement = Bool() 
  val genBit = Bool()

  def setDefault(): this.type = {
    this.busy := False
    this.done := False
    this.isMispredictedBranch := False
    this.targetPc.assignDontCare()
    this.isTaken := False
    this.result.assignDontCare()
    this.hasException := False
    this.exceptionCode.assignDontCare()
    // 关键：不要在这里重置genBit，它的生命周期由指针逻辑管理。
    this
  }
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
  val targetPc = UInt(config.pcWidth)
  val result = Bits(config.pcWidth)
  val exceptionOccurred = Bool()
  val exceptionCodeIn = UInt(config.exceptionCodeWidth)

  override def asMaster(): Unit = {
    in(fire, robPtr, isMispredictedBranch, targetPc, isTaken, result, exceptionOccurred, exceptionCodeIn)
  }

  def setDefault(): this.type = {
    this.fire := False
    this.robPtr.assignDontCare()
    this.isMispredictedBranch.assignDontCare()
    this.targetPc.assignDontCare()
    this.isTaken.assignDontCare()
    this.result.assignDontCare()
    this.exceptionOccurred.assignDontCare()
    this.exceptionCodeIn.assignDontCare()
    this
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
  // val retireAck = slave Flow(UInt(config.robPtrWidth))
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
    // master(retireAck)
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
    // statuses(i).pendingRetirement init(False)
    statuses(i).genBit init(False)
  }

  val headPtr_reg = Reg(UInt(config.robPtrWidth)) init(0)
  val tailPtr_reg = Reg(UInt(config.robPtrWidth)) init(0)
  val count_reg = Reg(UInt(log2Up(config.robDepth + 1) bits)) init(0)

  // --- 2. Combinational Logic for this cycle's operations ---
  
  // --- 2a. Allocation Logic (无环路 ready/fire 计算) ---
  val slotWillAllocate = Vec(Bool(), config.allocateWidth)

  // <<< 新增逻辑：创建一个向量来跟踪本周期被分配的物理索引 >>>
  val isBeingAllocatedThisCycle = Vec(Bool(), config.robDepth)
  isBeingAllocatedThisCycle.foreach(_ := False) // 默认所有索引都未被分配

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

    // <<< 新增逻辑：当一个槽位被分配时，标记其物理索引 >>>
    when(slotWillAllocate(i)) {
      val ptr = tailPtr_reg + numPrevSlotsFiring
      val physIdx = ptr.resize(config.robPhysIdxWidth)
      isBeingAllocatedThisCycle(physIdx) := True
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

    // <<< 新增逻辑：获取“未被分配”的条件 >>>
    val notBeingAllocatedThisCycle = !isBeingAllocatedThisCycle(currentCommitPhysIdx)

    canCommitFlags(i) := notBeingAllocatedThisCycle && // <-- 防竞争检查
                        !flushBlocking && 
                        (count_reg > U(i)) && 
                        currentStatus.done && 
                        (currentCommitGenBit === currentStatus.genBit)
    
    io.commit(i).valid := count_reg > U(i)
    io.commit(i).canCommit := canCommitFlags(i)
    io.commit(i).entry.payload := payloads.readAsync(address = currentCommitPhysIdx)
    io.commit(i).entry.status := currentStatus

    val prevCommitsAccepted = if (i == 0) True else actualCommittedMask(i - 1)
    actualCommittedMask(i) := prevCommitsAccepted && canCommitFlags(i) && io.commitAck(i)

    // 您的方案：在指令被确认提交的当周期，就将其状态重置为默认值
    when(actualCommittedMask(i)) {
        if(enableLog) report(L"[ROB] COMMIT_CLEAN[${i}]: Cleaning status for committed robPtr=${currentCommitFullIdx}")
        statuses(currentCommitPhysIdx).setDefault() // 调用修改后的setDefault
    }
    
    if(enableLog) when(count_reg > U(i)) {
      report(L"[ROB] COMMIT_CHECK[${i}]: ptr=${currentCommitFullIdx}, done=${currentStatus.done}, genMatch=${currentCommitGenBit === currentStatus.genBit}, flushBlocking=${flushBlocking}, isBeingAllocated=${!notBeingAllocatedThisCycle} -> canCommitFlags(i)=${canCommitFlags(i)}. io.commitAck(i)=${io.commitAck(i)} -> actualCommit=${actualCommittedMask(i)}")
      when(flushBlocking) {
        report(L"[ROB] COMMIT_BLOCKED[${i}]: Commit blocked due to flush (current=${io.flush.valid}, inProgress=${flushInProgressReg}, wasActive=${flushWasActiveLastCycle})")
      }
    }
  }
  val numToCommit = CountOne(actualCommittedMask)
  when(numToCommit > 0) {
    if(enableLog) report(L"[ROB] COMMIT_SUMMARY: Committing ${numToCommit} entries, total entries=${count_reg}, capacity=${config.robDepth}, first commit ${io.commit(0).entry.payload.uop.robPtr}")
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
      
      // 在分配时设置状态，genBit在这里被正确设置
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
    
    val isLegalWriteback = !io.flush.valid && (wbGenBit === statusBeforeWb.genBit) && statusBeforeWb.busy

    when(wbPort.fire) {
        assert(
            assertion = isLegalWriteback,
            message   = L"[ROB] FATAL: Illegal writeback detected for robPtr=${wbPort.robPtr}!\n" :+
                      L"  Details:\n" :+
                      L"    - isFlushing: ${io.flush.valid}\n" :+
                      L"    - genBitMatch: ${wbGenBit === statusBeforeWb.genBit} (portGen=${wbGenBit}, statusGen=${statusBeforeWb.genBit})\n" :+
                      L"    - isBusyInRob: ${statusBeforeWb.busy}\n" :+
                      L"  This indicates a double writeback, a write to a flushed/committed entry, or a logic error in a functional unit.",
            severity  = FAILURE // 立即停止仿真
        )

        // 只有在断言通过后，才执行状态更新
        when(isLegalWriteback) {
            statuses(wbPhysIdx).busy := False
            statuses(wbPhysIdx).done := True
            statuses(wbPhysIdx).isMispredictedBranch := wbPort.isMispredictedBranch
            statuses(wbPhysIdx).targetPc := wbPort.targetPc
            statuses(wbPhysIdx).isTaken := wbPort.isTaken
            statuses(wbPhysIdx).result := wbPort.result
            statuses(wbPhysIdx).hasException := wbPort.exceptionOccurred
            statuses(wbPhysIdx).exceptionCode := Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode)
            if (enableLog) report(L"[ROB] WB_WRITE: ptr=${wbPort.robPtr} -> status.done=T")
        }
    }
  }

  when(io.flush.valid) {
    switch(io.flush.payload.reason) {
      is(FlushReason.FULL_FLUSH) {
        for (k <- 0 until config.robDepth) {
          statuses(k).busy := False
          statuses(k).done := False
          statuses(k).hasException := False
          // 在这里清理genBit是正确的，因为是完全冲刷，所有世代信息都无效了
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
                statuses(physIdx).setDefault()
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

  val debg = enableLog generate new Area {
    val robPtrToMonitor: Int = 1 // 我们要监控的robPtr
    val physIdxToMonitor = robPtrToMonitor % config.robDepth
    val statusToMonitor = statuses(physIdxToMonitor)

    report(
      L"Status for physIdx=${physIdxToMonitor}: " :+
      L"busy=${statusToMonitor.busy}, " :+
      L"done=${statusToMonitor.done}, " :+
      L"genBit=${statusToMonitor.genBit}, " :+
      L"hasException=${statusToMonitor.hasException}"
    )

  }

  val verification = new Area {
    val expected_count = UInt(config.robPtrWidth.value + 1 bits)
    
    val head_ext = headPtr_reg.resize(config.robPtrWidth.value + 1)
    val tail_ext = tailPtr_reg.resize(config.robPtrWidth.value + 1)

    when(tail_ext >= head_ext) {
      expected_count := (tail_ext - head_ext)
    } otherwise {
      expected_count := (tail_ext + (U(1) << config.robPtrWidth.value) - head_ext)
    }

    assert(
      assertion = count_reg === expected_count.resized,
      message   = L"[ROB] FATAL: count_reg (${count_reg}) mismatches pointer difference (${expected_count.resized}).\n" :+
                L"  - headPtr=${headPtr_reg}, tailPtr=${tailPtr_reg}",
      severity  = FAILURE
    )

    if(enableLog) report(L"[ROB] COUNTER_UPDATE: count=${count_reg}, allocs=${numActuallyAllocatedThisCycle}, commits=${numToCommit} => nextCount=${nextCount}")

  }

    val perfCounter = Reg(UInt(32 bits)) init(0)
  perfCounter := perfCounter + 1

  // --- 8. (新增) 全面的状态转储区域 ---
  val full_state_dump = enableLog generate new Area {
      // 打印最核心的指针和计数器状态 (在周期开始时)
      report(
        L"[ROB_SNAPSHOT] Cycle=${perfCounter} | " :+
        L"head=${headPtr_reg}, tail=${tailPtr_reg}, count=${count_reg} | " :+
        L"allocs=${numActuallyAllocatedThisCycle}, commits=${numToCommit} | " :+
        L"canAllocate=${io.canAllocate(0)}" // 假设allocateWidth至少为1
      )
      
      // 打印ROB中所有有效条目的状态
      // 这将告诉我们，在分配 `robPtr=1` (Load) 的瞬间，旧的 `robPtr=1` (Store) 是否还在ROB中
      val hasValidEntries = count_reg > 0
      when(hasValidEntries) {
        report(L"  -- ROB Contents --")
        for(i <- 0 until config.robDepth) {
          val current_ptr = headPtr_reg + i
          // 检查这个索引是否在有效范围内
          val isInRange = Bool()
          val head_ext = headPtr_reg.resize(config.robPtrWidth.value + 1)
          val tail_ext = tailPtr_reg.resize(config.robPtrWidth.value + 1)
          val current_ext = current_ptr.resize(config.robPtrWidth.value + 1)
          
          when(tail_ext > head_ext) { // No wrap
            isInRange := current_ext >= head_ext && current_ext < tail_ext
          } otherwise { // Wrapped
            isInRange := current_ext >= head_ext || current_ext < tail_ext
          }

          // 只有当条目在逻辑上有效时才打印
          when(i < count_reg) { // 一个更简单的检查
            val physIdx = current_ptr.resize(config.robPhysIdxWidth)
            val status = statuses(physIdx)
            val payload = payloads.readAsync(physIdx) // 注意: readAsync用于仿真/调试

            report(
              L"  -> Entry[robPtr=${current_ptr}]: pc=${payload.pc}, " :+
              L"status(busy=${status.busy}, done=${status.done}, gen=${status.genBit}, ex=${status.hasException})"
            )
          }
        }
      }
  }
}
