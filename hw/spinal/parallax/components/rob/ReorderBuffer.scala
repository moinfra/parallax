package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.decode._
import parallax.utilities.ParallaxSim
import _root_.parallax.utilities.ParallaxLogger

import spinal.core._

object ROBFlushReason extends SpinalEnum {
  val NONE,                // No flush, or an invalid reason
  FULL_FLUSH,          // Complete flush of the ROB and dependent structures (e.g., after major exception)
  ROLLBACK_TO_ROB_IDX, // Rollback ROB state to a specific instruction (e.g., branch mispredict, LSU replay)
  EXCEPTION_HANDLING = newElement()  // Flush due to an exception being handled (might be similar to FULL_FLUSH or specific rollback)
  // Add other reasons as needed, e.g., DEBUG_FLUSH
}

// --- Configuration for ROB ---
case class ROBConfig[RU <: Data with Dumpable with HasRobPtr](
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
  
  // robPtrWidth 现在表示的是物理索引的位宽
  val robPhysIdxWidth: BitCount = log2Up(robDepth) bits
  // 增加一个世代位
  val robGenBitWidth: BitCount = 1 bits
  // 对外暴露的 robPtr 的总位宽 (物理索引 + 世代位)
  val robPtrWidth: BitCount = robPhysIdxWidth + robGenBitWidth

  def dump(): Seq[Any] = {
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
        robPhysIdxWidth: ${robPhysIdxWidth}, // 物理索引位宽
        robGenBitWidth: ${robGenBitWidth},   // 世代位位宽
        robPtrWidth: ${robPtrWidth}          // 对外暴露的 robPtr 总位宽
      )"""
    str
  }
}

// --- MODIFICATION START (Flush Mechanism Refactor: Enum and Command Bundle) ---
object FlushReason extends SpinalEnum {
  val NONE, FULL_FLUSH, ROLLBACK_TO_ROB_IDX = newElement()
}

// For flush command (restoring ROB pointers)
// This is the payload of the io.flush Flow
case class ROBFlushPayload(robPtrWidth: BitCount) extends Bundle {
  val reason = FlushReason()
  // targetRobPtr 现在是完整的 ROB ID (物理索引 + 世代位)
  val targetRobPtr = UInt(robPtrWidth) 
}
// --- MODIFICATION END ---

// --- Data Bundles for ROB Internal Storage ---
// Status part of the ROB entry (frequently updated)
case class ROBStatus[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val busy = Bool()
  val done = Bool()
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth)
  val genBit = Bool() // 增加世代位
}

// Payload part of the ROB entry (written once at allocation)
case class ROBPayload[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val uop = config.uopType() // Contains physReg info
  val pc = UInt(config.pcWidth)
}

// Combined entry for communication (e.g., commit port)
case class ROBFullEntry[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU]) extends Bundle {
  val payload = ROBPayload(config)
  val status = ROBStatus(config)
}

// --- IO Bundles for ROB ---

// For allocation port (per slot)
case class ROBAllocateSlot[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val fire = Bool() // From Rename: attempt to allocate this slot
  val uopIn = config.uopType() // uopIn.robPtr 应该已经是 config.robPtrWidth
  val pcIn = UInt(config.pcWidth)
  // robPtr 现在是完整的 ROB ID (物理索引 + 世代位)
  val robPtr = UInt(config.robPtrWidth) 

  override def asMaster(): Unit = { // Perspective of Rename Stage
    in(fire, uopIn, pcIn)
    out(robPtr)
  }
}

// For writeback port (per execution unit writeback)
case class ROBWritebackPort[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU])
    extends Bundle
    with IMasterSlave {
  val fire = Bool()
  // robPtr 现在是完整的 ROB ID (物理索引 + 世代位)
  val robPtr = UInt(config.robPtrWidth) 
  val exceptionOccurred = Bool()
  val exceptionCodeIn = UInt(config.exceptionCodeWidth)

  override def asMaster(): Unit = { // Perspective of Execute Stage
    in(fire, robPtr, exceptionOccurred, exceptionCodeIn)
  }
}

// For commit port (per slot)
case class ROBCommitSlot[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU])
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
case class ROBIo[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU]) extends Bundle with IMasterSlave {
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
  val flush = slave Flow (ROBFlushPayload(config.robPtrWidth))
  val flushed = out Bool () // Indicates ROB has completed flush operation this cycle
  // --- MODIFICATION END ---

  // Status outputs
  val empty = out Bool ()
  // headPtrOut 和 tailPtrOut 现在输出完整的 ROB ID (物理索引 + 世代位)
  val headPtrOut = out UInt (config.robPtrWidth) 
  val tailPtrOut = out UInt (config.robPtrWidth)
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

class ReorderBuffer[RU <: Data with Dumpable with HasRobPtr](config: ROBConfig[RU]) extends Component {
  ParallaxLogger.log(
    s"Creating ReorderBuffer with config: ${config.dump().mkString("")}"
  )
  val io = slave(ROBIo(config)) // ROB is slave to the overall system for its IO bundle

  // payloads 和 statuses 仍然使用物理索引作为地址
  val payloads = Mem(ROBPayload(config), config.robDepth)
  val statuses = Vec(Reg(ROBStatus(config)), config.robDepth)

  payloads.init(
    Seq.fill(config.robDepth)(
      {
        // 这里的 assert 需要调整，因为 uop.robPtr 现在是 robPtrWidth
        assert(config.defaultUop().robPtr.getBitsWidth == config.robPtrWidth.value) 
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
    statuses(i).genBit init (False) // 初始化世代位
  }

  // Actual registers for state
  // headPtr_reg 和 tailPtr_reg 现在存储完整的 ROB ID (物理索引 + 世代位)
  val headPtr_reg = Reg(UInt(config.robPtrWidth)) init (0) 
  val tailPtr_reg = Reg(UInt(config.robPtrWidth)) init (0) 
  val count_reg = Reg(UInt(log2Up(config.robDepth + 1) bits)) init (0)

  // Combinational view of current state for calculations this cycle.
  val currentHead = headPtr_reg
  val currentTail = tailPtr_reg
  val currentCount = count_reg

  val slotWillAllocate = Vec(Bool(), config.allocateWidth)
  // slotRobPtr 现在存储完整的 ROB ID (物理索引 + 世代位)
  val slotRobPtr = Vec(UInt(config.robPtrWidth), config.allocateWidth) 
  val numPrevAllocations = Vec(UInt(log2Up(config.allocateWidth + 1) bits), config.allocateWidth + 1)
  // robPtrAtSlotStart 也存储完整的 ROB ID (物理索引 + 世代位)
  val robPtrAtSlotStart = Vec(UInt(config.robPtrWidth), config.allocateWidth + 1)

  numPrevAllocations(0) := U(0)
  robPtrAtSlotStart(0) := currentTail
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
    
    // 分配给外部的 robPtr 是完整的 ROB ID
    slotRobPtr(i) := robPtrAtSlotStart(i) 
    allocPort.robPtr := slotRobPtr(i)

    when(allocPort.fire) {
      ParallaxSim.debug(
        Seq(
          L"[ROB] ALLOC_ATTEMPT[${i}]: fire=${allocPort.fire}, pcIn=${allocPort.pcIn}, ",
          L"spaceAvailableForSlot=${spaceAvailableForThisSlot}, currentCountPlusPrevAllocs=${(currentCount + numPrevAllocations(
              i
            ))}, robDepth=${U(config.robDepth)}, calculatedRobPtr=${slotRobPtr(i)}"
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
    // robPtrAtSlotStart(i + 1) 也是完整的 ROB ID，自动处理进位
    robPtrAtSlotStart(i + 1) := robPtrAtSlotStart(i) + U(slotWillAllocate(i)) 
    when(slotWillAllocate(i)) {
      report(
        L"[ROB] ALLOC_DO[${i}]: Allocating to robPtr=${slotRobPtr(i)}, pcIn=${allocPort.pcIn}"
      )
      val newPayload = ROBPayload(config).allowOverride()
      newPayload.uop := allocPort.uopIn
      newPayload.uop.robPtr := slotRobPtr(i) // uop 内部的 robPtr 也是完整的 ROB ID
      newPayload.pc := allocPort.pcIn
      
      // 提取物理索引和世代位，用于内部存储
      val physIdx = slotRobPtr(i).resize(config.robPhysIdxWidth)
      val genBit = slotRobPtr(i).msb // 假设 robGenBitWidth = 1

      payloads.write(address = physIdx, data = newPayload) // 使用物理索引写入 Mem
      report(L"[ROB] ALLOC_PAYLOAD_WRITE[${i}]: Writing payload to robPtr=${slotRobPtr(i)} (physIdx=${physIdx}, genBit=${genBit}): ${newPayload.uop.dump()}")
      
      statuses(physIdx).busy := True
      statuses(physIdx).done := False
      statuses(physIdx).hasException := False
      statuses(physIdx).exceptionCode := U(0, config.exceptionCodeWidth)
      statuses(physIdx).genBit := genBit // 存储世代位
      report(
        Seq(L"[ROB] ALLOC_STATUS_SET[${i}]: robPtr=${slotRobPtr(i)} (physIdx=${physIdx}, genBit=${genBit}) set busy=T, ", L"done=F, hasException=F")
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
    // 提取物理索引和世代位
    val wbPhysIdx = wbPort.robPtr.resize(config.robPhysIdxWidth)
    val wbGenBit = wbPort.robPtr.msb // 假设 robGenBitWidth = 1

    val statusBeforeWb = statuses(wbPhysIdx) // 使用物理索引访问状态
    
    // 匹配世代位
    when(wbPort.fire && wbGenBit === statusBeforeWb.genBit) {
      report(
        Seq(
          L"[ROB] WRITEBACK[${portIdx}]: Fired. robPtr=${wbPort.robPtr} (physIdx=${wbPhysIdx}, genBit=${wbGenBit}), exceptionOccurred=${wbPort.exceptionOccurred}, ",
          L"exceptionCodeIn=${wbPort.exceptionCodeIn}"
        )
      )
      report(
        Seq(
          L"[ROB] WRITEBACK_STATUS_OLD[${portIdx}]: robPtr=${wbPort.robPtr}, oldBusy=${statusBeforeWb.busy}, ",
          L"oldDone=${statusBeforeWb.done}, oldHasExcp=${statusBeforeWb.hasException}, oldExcpCode=${statusBeforeWb.exceptionCode}, oldGen=${statusBeforeWb.genBit}"
        )
      )
      statuses(wbPhysIdx).busy := False
      statuses(wbPhysIdx).done := True
      statuses(wbPhysIdx).hasException := wbPort.exceptionOccurred
      statuses(wbPhysIdx).exceptionCode := Mux(
        wbPort.exceptionOccurred,
        wbPort.exceptionCodeIn,
        statuses(wbPhysIdx).exceptionCode // Keep old if no new exception
      )
      report(
        Seq(
          L"[ROB] WRITEBACK_STATUS_NEW[${portIdx}]: robPtr=${wbPort.robPtr}, newBusy=F, newDone=T, ",
          L"newHasExcp=${wbPort.exceptionOccurred}, newExcpCode=${Mux(wbPort.exceptionOccurred, wbPort.exceptionCodeIn, statusBeforeWb.exceptionCode)}, newGen=${statuses(wbPhysIdx).genBit}"
        )
      )
    } elsewhen(wbPort.fire) { // Fired but generation bit mismatch
        report(
            Seq(
                L"[ROB] WRITEBACK[${portIdx}]: Fired but GEN_BIT MISMATCH. robPtr=${wbPort.robPtr} (physIdx=${wbPhysIdx}, wbGen=${wbGenBit}), storedGen=${statusBeforeWb.genBit}"
            )
        )
    } otherwise {
      report(
        Seq(
          L"[ROB] WRITEBACK[${portIdx}]: NOT Fired. robPtr=${wbPort.robPtr}, exceptionOccurred=${wbPort.exceptionOccurred}, ",
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
    // currentCommitIdx 是完整的 ROB ID
    val currentCommitFullIdx = currentHead + U(i) 
    val currentCommitPhysIdx = currentCommitFullIdx.resize(config.robPhysIdxWidth) // 物理索引
    val currentCommitGenBit = currentCommitFullIdx.msb // 世代位

    val currentPayload = payloads.readAsync(address = currentCommitPhysIdx)
    val currentStatus = statuses(currentCommitPhysIdx) // 使用物理索引访问状态

    when(currentCount > U(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_CHECK_SLOT[${i}]: Considering commit. robPtr=${currentCommitFullIdx} (physIdx=${currentCommitPhysIdx}, genBit=${currentCommitGenBit}), ",
          L"status.busy=${currentStatus.busy}, status.done=${currentStatus.done}, status.hasException=${currentStatus.hasException}, ",
          L"status.exceptionCode=${currentStatus.exceptionCode}, status.genBit=${currentStatus.genBit}, pc=${currentPayload.pc}, count=${currentCount}"
        )
      )
    }
    // 提交条件：ROB 中有指令，指令已完成，且世代位匹配
    canCommitFlags(i) := (currentCount > U(i)) && currentStatus.done && (currentCommitGenBit === currentStatus.genBit)
    io.commit(i).valid := canCommitFlags(i)
    io.commit(i).entry.payload := currentPayload
    io.commit(i).entry.status := currentStatus
    when(canCommitFlags(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_VALID_SLOT[${i}]: Slot IS VALID for commit. robPtr=${currentCommitFullIdx}, ",
          L"io.commit(i).valid=${io
              .commit(i)
              .valid}, external commitFire(${i})=${io.commitFire(i)}"
        )
      )
    }
    when(!canCommitFlags(i) && (currentCount > U(i))) {
      report(
        Seq(
          L"[ROB] COMMIT_NOT_VALID_SLOT[${i}]: Slot NOT VALID for commit. robPtr=${currentCommitFullIdx}, ",
          L"status.done=${currentStatus.done}, status.genBit=${currentStatus.genBit}, count=${currentCount}, external commitFire(${i})=${io.commitFire(i)}"
        )
      )
    }
    when(io.commitFire(i) && !canCommitFlags(i)) {
      report(
        Seq(
          L"[ROB] COMMIT_WARN_SLOT[${i}]: Commit stage fired for non-committable slot! robPtr=${currentCommitFullIdx}, ",
          L"canCommitFlag=${canCommitFlags(
              i
            )}, status.done=${currentStatus.done}, status.genBit=${currentStatus.genBit}, count=${currentCount}"
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
  // nextHeadFromOps 和 nextTailFromOps 现在是完整的 ROB ID
  val nextHeadFromOps = UInt(config.robPtrWidth) 
  val nextTailFromOps = UInt(config.robPtrWidth) 
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
      L"[ROB] FLUSH: Received flush command. Valid=${io.flush.valid}, Reason=${io.flush.payload.reason}, TargetRobPtr=${io.flush.payload.targetRobPtr}"
    )

    // headAtFlushStart 和 tailAtFlushStart 存储完整的 ROB ID
    val headAtFlushStart = headPtr_reg 
    val tailAtFlushStart = tailPtr_reg
    val countAtFlushStart = count_reg

    // headAfterFlush 和 tailAfterFlush 也存储完整的 ROB ID
    val headAfterFlush = UInt(config.robPtrWidth) 
    val tailAfterFlush = UInt(config.robPtrWidth)
    val countAfterFlush = UInt(log2Up(config.robDepth + 1) bits)

    // Default to current _reg values, to be overridden by specific flush reason
    headAfterFlush := headAtFlushStart
    tailAfterFlush := tailAtFlushStart
    countAfterFlush := countAtFlushStart

    switch(io.flush.payload.reason) {
      is(FlushReason.FULL_FLUSH) {
        report(L"[ROB] FLUSH: Reason=FULL_FLUSH")
        headAfterFlush := U(0, config.robPtrWidth) // 重置为 0 (物理索引 0，世代位 0)
        tailAfterFlush := U(0, config.robPtrWidth) // 重置为 0
        countAfterFlush := U(0)
        for (k <- 0 until config.robDepth) {
          statuses(k).busy := False
          statuses(k).done := False
          statuses(k).hasException := False
          statuses(k).exceptionCode := 0
          statuses(k).genBit := False // 重置世代位
        }
        ParallaxSim.debug(L"[ROB DEBUG] Before final reg assignment in flush path: head=${headAfterFlush}, tail=${tailAfterFlush}, count=${countAfterFlush}")
      }
      is(FlushReason.ROLLBACK_TO_ROB_IDX) {
        // targetIdx 是完整的 ROB ID
        val targetFullIdx = io.flush.payload.targetRobPtr 
        report(L"[ROB] FLUSH: Reason=ROLLBACK_TO_ROB_IDX, Target ROB Index=${targetFullIdx}")
        report(
          L"[ROB] FLUSH: State before rollback: head=${headAtFlushStart}, tail=${tailAtFlushStart}, count=${countAtFlushStart}"
        )

        headAfterFlush := headAtFlushStart // Head pointer usually doesn't change on rollback unless target is head
        tailAfterFlush := targetFullIdx // tail 回滚到目标 ID

        // Calculate new count: number of elements from headAtFlushStart (inclusive) to targetFullIdx (exclusive)
        // 这里的计算需要使用完整的 ROB ID 进行比较和减法
        val h_ext = headAtFlushStart.resize(config.robPtrWidth.value + 1) // 扩展一位用于比较
        val t_ext = targetFullIdx.resize(config.robPtrWidth.value + 1) // 扩展一位用于比较
        val depth_val_ext = U(config.robDepth, config.robPtrWidth.value + 1 bits) // 深度也扩展一位
        val tempCount = UInt(log2Up(config.robDepth + 1) bits)

        when(t_ext >= h_ext) {
          tempCount := (t_ext - h_ext).resized
        } otherwise { // Wrap around
          tempCount := (t_ext - h_ext + depth_val_ext).resized
        }
        countAfterFlush := tempCount
        report(
          L"[ROB] FLUSH: ROLLBACK_TO_ROB_IDX calculated: new head=${headAfterFlush}, new tail=${tailAfterFlush}, new count=${countAfterFlush}"
        )

        // Clear statuses for entries from targetFullIdx (inclusive) up to tailAtFlushStart (exclusive)
        // These are the entries being squashed by the rollback.
        report(
          L"[ROB] FLUSH: Clearing statuses for entries from ${targetFullIdx} (inclusive) to ${tailAtFlushStart} (exclusive)"
        )
        for (k <- 0 until config.robDepth) {
          val currentRobEntryPhysIdx = U(k, config.robPhysIdxWidth) // 物理索引
          val currentRobEntryGenBit = statuses(currentRobEntryPhysIdx).genBit // 获取当前存储的世代位
          // 构造当前条目的完整 ROB ID
          val currentRobEntryFullIdx = (currentRobEntryGenBit ## currentRobEntryPhysIdx.resize(config.robPhysIdxWidth)).asUInt

          var shouldClearThisEntryInRollback = False

          if (config.robDepth > 0) { 
            // Check if currentRobEntryFullIdx is in the range [targetFullIdx, tailAtFlushStart)
            val rangeIsEmptyOrInvalid = (targetFullIdx === tailAtFlushStart)

            when(!rangeIsEmptyOrInvalid) {
              val noWrapInRange = targetFullIdx < tailAtFlushStart
              val wrapInRange = targetFullIdx > tailAtFlushStart 

              when(noWrapInRange) {
                when(currentRobEntryFullIdx >= targetFullIdx && currentRobEntryFullIdx < tailAtFlushStart) {
                  shouldClearThisEntryInRollback := True
                }
              } elsewhen (wrapInRange) { // wrap
                when(currentRobEntryFullIdx >= targetFullIdx || currentRobEntryFullIdx < tailAtFlushStart) {
                  shouldClearThisEntryInRollback := True
                }
              }
            }
          }
          when(shouldClearThisEntryInRollback) {
            report(L"[ROB] FLUSH: Clearing status for ROB entry index ${currentRobEntryPhysIdx} (Full ID: ${currentRobEntryFullIdx})")
            statuses(currentRobEntryPhysIdx).busy := False
            statuses(currentRobEntryPhysIdx).done := False
            statuses(currentRobEntryPhysIdx).hasException := False
            statuses(currentRobEntryPhysIdx).exceptionCode := U(0, config.exceptionCodeWidth)
            // 关键：被清除的槽位，其世代位应该翻转，表示它现在属于下一个世代
            statuses(currentRobEntryPhysIdx).genBit := !currentRobEntryGenBit 
          }
        }
      }
      default { 
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
