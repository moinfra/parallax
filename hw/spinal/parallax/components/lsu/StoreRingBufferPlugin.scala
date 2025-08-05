// filename: parallax/components/lsu/StoreRingBufferPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.core.sim._ // simPublic
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.utilities._
import parallax.utilities.ParallaxSim.debug
import parallax.utilities.ParallaxSim.notice
import spinal.lib.pipeline.Pipeline
import spinal.lib.pipeline.Connection
import spinal.lib.pipeline.Stageable

// NOTE: Case class definitions are in the same package.
/*
| `isIO` | `isCoherent` | I/O 类型 | 描述 | 能否Bypass? |
| :--- | :--- | :--- | :--- | :--- |
| `false`| `true` | **Cache I/O** | 默认的可缓存内存访问 | **能** |
| `true` | `true` | **RAM I/O** | 非缓存但行为像内存 | **能** |
| `true` | `false`| **MMIO** | 非缓存且有强副作用 | **不能** |
| `false`| `false`| (非法组合) | 没有意义的组合 | N/A |
 */

class StoreRingBufferPlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters,
    val sbDepth: Int,
    val ioConfig: Option[GenericMemoryBusConfig] = None
) extends Plugin
    with StoreBufferService
    with LockedImpl {
  require(isPow2(sbDepth), s"StoreRingBufferPlugin depth must be a power of two. Got $sbDepth.")

  val enableLog = true // 控制关键事件日志
  val verbose = true // 控制高频/每周期日志

  // --- Hardware Area: Define all hardware interfaces ---
  val hw = create early new Area {
    val pushPortInst = Stream(StoreBufferPushCmd(pipelineConfig, lsuConfig))
    val bypassQueryAddrIn = UInt(pipelineConfig.pcWidth)
    val bypassQuerySizeIn = MemAccessSize()
    val bypassDataOutInst = Flow(StoreBufferBypassData(pipelineConfig))
    val sqQueryPort = SqQueryPort(lsuConfig, pipelineConfig)
    val robServiceInst = getService[ROBService[RenamedUop]]

    var sgmbServiceOpt: Option[SgmbService] = None
    var ioWriteChannel: Option[SplitGmbWriteChannel] = None

    if (ioConfig.isDefined) {
      val config = ioConfig.get
      val sgmbService = getService[SgmbService]
      sgmbServiceOpt = Some(sgmbService)
      ioWriteChannel = Some(sgmbService.newWritePort())
    }
    sgmbServiceOpt.foreach(_.retain())
    robServiceInst.retain()
  }

  // --- Service Interface Implementation ---
  override def getPushPort(): Stream[StoreBufferPushCmd] = hw.pushPortInst
  override def getBypassQueryAddressInput(): UInt = hw.bypassQueryAddrIn
  override def getBypassQuerySizeInput(): MemAccessSize.C = hw.bypassQuerySizeIn
  override def getBypassDataOutput(): Flow[StoreBufferBypassData] = hw.bypassDataOutInst
  override def getStoreQueueQueryPort(): SqQueryPort = hw.sqQueryPort

  // --- Core Logic ---
  val logic = create late new Area {
    val nextTxid = RegInit(U(0, pipelineConfig.transactionIdWidth bits))

    lock.await()
    val robService = hw.robServiceInst
    val pushPortIn = hw.pushPortInst
    val bypassQueryAddr = hw.bypassQueryAddrIn
    val bypassQuerySz = hw.bypassQuerySizeIn
    val bypassDataOut = hw.bypassDataOutInst

    val addressWidth = log2Up(sbDepth)

    // =================================================================
    //  Helper Functions Area
    // =================================================================
    val helper_functions = new Area {
      def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
        val width = robPtrA.getWidth
        require(width == robPtrB.getWidth, "ROB pointer widths must match")
        if (width <= 1) return robPtrA >= robPtrB
        val genA = robPtrA.msb
        val idxA = robPtrA(width - 2 downto 0)
        val genB = robPtrB.msb
        val idxB = robPtrB(width - 2 downto 0)
        (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
      }
      def isOlder(robPtrA: UInt, robPtrB: UInt): Bool = !isNewerOrSame(robPtrA, robPtrB)
    }

    // =================================================================
    //  Storage and Pointers (State Registers)
    // =================================================================
    val storage = new Area {
      // All state-holding elements are registers and end with 'Reg'
      val slotsReg = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
      val allocPtrReg = Reg(UInt(addressWidth bits)).init(0) // Tail: points to the next free slot for writing
      val freePtrReg = Reg(UInt(addressWidth bits)).init(0) // Head: points to the oldest entry to be processed
      val wrapModeReg = Reg(Bool()).init(False) // True: buffer is full (allocPtr caught up to freePtr)

      // Combinational status signals derived from registers
      val isFull = (allocPtrReg === freePtrReg) && wrapModeReg
      val isEmpty = (allocPtrReg === freePtrReg) && !wrapModeReg

      // Helper function: check if an index is within the valid queue range
      def isIndexInFlight(idx: UInt): Bool = {
        val result = Bool()
        when(isEmpty) {
          result := False
        }.elsewhen(isFull) {
          result := True
        }.elsewhen(allocPtrReg > freePtrReg) { // Normal case
          result := (idx >= freePtrReg && idx < allocPtrReg)
        }.otherwise { // Wrapped case
          result := (idx >= freePtrReg || idx < allocPtrReg)
        }
        result
      }

      // Initialize all slots to a default state
      for (i <- 0 until sbDepth) {
        slotsReg(i).init(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
      }
    }

    // =========================================================================
    // === 输入锁存级 (S1) 和冲刷流水线 ===
    // =========================================================================

    // S1: 锁存来自外部慢速路径的信号
    val s1_inputs = new Area {
        val robFlushPort = robService.doRobFlush()
        val commitPort = robService.getCommitSlots(1)(0) // 假设 commitWidth=1

        val info = Reg(new Bundle {
            // 来自 ROB 的事件
            val robFlushValid = Bool()
            val robFlushPayload = ROBFlushPayload(pipelineConfig.robPtrWidth)
            val commitCanFire = Bool()
            val commitRobPtr = UInt(pipelineConfig.robPtrWidth)

            // 事件发生时的 SQ 内部状态快照
            val snapshot_freePtr = UInt(addressWidth bits)
            val snapshot_allocPtr = UInt(addressWidth bits)
            val snapshot_wrapMode = Bool()
        })

        // 组合逻辑：捕捉当前周期的事件和状态
        info.robFlushValid := robFlushPort.valid
        info.robFlushPayload := robFlushPort.payload
        
        // 完整的 commit fire 条件
        val canCommit = commitPort.canCommit && (commitPort.entry.payload.uop.decoded.uopCode === BaseUopCode.STORE) && !commitPort.entry.status.hasException
        info.commitCanFire := canCommit
        info.commitRobPtr := commitPort.entry.payload.uop.robPtr
        
        info.snapshot_freePtr  := storage.freePtrReg
        info.snapshot_allocPtr := storage.allocPtrReg
        info.snapshot_wrapMode := storage.wrapModeReg
    }

    // S2/S3: 冲刷执行流水线
    val flush_pipeline = new Area {
        /** S3: 标记一个冲刷正在被应用 */
        val isApplying = Reg(Bool()).init(False)

        /** S2 -> S3: 锁存的冲刷计算结果 */
        val latched_allocPtrNext = Reg(UInt(addressWidth bits))
        val latched_wrapModeNext = Reg(Bool())
        val latched_slotsNext = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
        
        // 初始化寄存器
        latched_allocPtrNext.init(0)
        latched_wrapModeNext.init(False)
        for (slot <- latched_slotsNext) {
            slot.init(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
        }
    }

    // =================================================================
    //  Next-state Logic (Combinational Views)
    // =================================================================
    // These signals represent the calculated next state for all registers.
    val slotsNext = CombInit(storage.slotsReg)
    val allocPtrNext = CombInit(storage.allocPtrReg)
    val freePtrNext = CombInit(storage.freePtrReg)
    val wrapModeRegNext = CombInit(storage.wrapModeReg)

    // Control signals for this cycle
    val doPush = Bool()
    val doPop = Bool()
    // doFlush_rob: 原始的、来自ROB的冲刷请求 (周期N)
    val doFlush_rob = s1_inputs.robFlushPort.valid 
    // doFlush_calc: SQ开始计算冲刷 (周期N+1)
    val doFlush_calc = s1_inputs.info.robFlushValid 

    // 抑制所有常规状态更新的条件
    val blockRegularOps = doFlush_rob || doFlush_calc || flush_pipeline.isApplying

    // =================================================================
    //  Push Logic (Allocation)
    // =================================================================
    val push_logic = new Area {
      // Push is suppressed by a flush or when flush is applying
      val canPush = !storage.isFull && !blockRegularOps
      pushPortIn.ready := canPush
      doPush := pushPortIn.fire

      when(doPush) {
        val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
        newSlotData.initFromCommand(pushPortIn.payload, nextTxid)
        nextTxid := nextTxid + 1
        slotsNext(storage.allocPtrReg) := newSlotData
        allocPtrNext := storage.allocPtrReg + 1
        if (enableLog)
          debug(
            L"[SQ-Ring] PUSH: robPtr=${pushPortIn.payload.robPtr}, addr=${pushPortIn.payload.addr}, raw_data=${pushPortIn.payload.data}, be=${pushPortIn.payload.be} to slotIdx=${storage.allocPtrReg}. Stored aligned_data=${newSlotData.data}"
          )
      }
      if (enableLog && verbose) {
        when(pushPortIn.valid) {
          debug(
            L"[SQ-Ring] PushPort: valid=${pushPortIn.valid}, ready=${pushPortIn.ready}, canPush=${canPush}, isFull=${storage.isFull}, blockRegularOps=${blockRegularOps}"
          )
        }
      }
    }

    // =================================================================
    //  Flush Logic - Calculation Stage (Cycle N+1 / S2)
    // =================================================================
    val flush_logic_calc = new Area {
        val s1_info = s1_inputs.info
        val doFlush = s1_info.robFlushValid // 使用锁存的冲刷信号

        // 定义组合逻辑输岀，用于驱动 flush_pipeline 中的寄存器
        val flush_allocPtrNext = UInt(addressWidth bits)
        val flush_wrapModeNext = Bool()
        val flush_slotsNext = CombInit(storage.slotsReg) // 从当前状态开始计算

        // 默认值，当 doFlush 为 false 时，这些信号无效
        flush_allocPtrNext.assignDontCare()
        flush_wrapModeNext.assignDontCare()
        
        when(doFlush) {
            if (enableLog)
              notice(L"[SQ-Ring] FLUSH_CALC: received. Reason: ${s1_info.robFlushPayload.reason}, targetRobPtr=${s1_info.robFlushPayload.targetRobPtr}")

            // --- 辅助函数，用于基于快照判断 ---
            def isIndexInFlightOnSnapshot(idx: UInt): Bool = {
                val result = Bool()
                val isEmpty = (s1_info.snapshot_allocPtr === s1_info.snapshot_freePtr) && !s1_info.snapshot_wrapMode
                val isFull = (s1_info.snapshot_allocPtr === s1_info.snapshot_freePtr) && s1_info.snapshot_wrapMode

                when(isEmpty) {
                  result := False
                }.elsewhen(isFull) {
                  result := True
                }.elsewhen(s1_info.snapshot_allocPtr > s1_info.snapshot_freePtr) { // Normal case
                  result := (idx >= s1_info.snapshot_freePtr && idx < s1_info.snapshot_allocPtr)
                }.otherwise { // Wrapped case
                  result := (idx >= s1_info.snapshot_freePtr || idx < s1_info.snapshot_allocPtr)
                }
                result
            }

            // 1. 确定哪些槽位是幸存者 (逻辑不变)
            val isSurvivor = Vec.tabulate(sbDepth) { i =>
                val slot = storage.slotsReg(i) // 状态（如isCommitted）读取当前值
                val shouldFlushThisSlot = Bool()
                switch(s1_info.robFlushPayload.reason) {
                    is(FlushReason.FULL_FLUSH) { shouldFlushThisSlot := !slot.isCommitted }
                    is(FlushReason.ROLLBACK_TO_ROB_IDX) {
                        shouldFlushThisSlot := !slot.isCommitted && helper_functions.isNewerOrSame(slot.robPtr, s1_info.robFlushPayload.targetRobPtr)
                    }
                    default { shouldFlushThisSlot := False }
                }
                isIndexInFlightOnSnapshot(U(i)) && !shouldFlushThisSlot
            }

            // 2. 使用 reduceBalancedTree 查找最新的幸存者 (替换 foldLeft)
            case class FindLastResult() extends Bundle {
                val found = Bool()
                val ptr = UInt(addressWidth bits)
            }
            val searchOrderResults = Vec.tabulate(sbDepth) { i =>
                // 基于快照的 allocPtr 进行倒序查找
                val readPtr = (s1_info.snapshot_allocPtr - 1 - i).resized
                val result = FindLastResult()
                result.found := isSurvivor(readPtr)
                result.ptr   := readPtr
                result
            }
            val finalResult = searchOrderResults.reduceBalancedTree((newer, older) => {
                val merged = FindLastResult()
                merged.found := newer.found || older.found
                merged.ptr   := Mux(newer.found, newer.ptr, older.ptr)
                merged
            })
            val foundSurvivor = finalResult.found
            val lastSurvivorPtr = finalResult.ptr

            // 3. 计算冲刷后的新指针和新wrapMode (赋值给 flush_* 信号)
            val newAllocPtr = Mux(foundSurvivor, lastSurvivorPtr + 1, s1_info.snapshot_freePtr) // **使用锁存的freePtr**
            flush_allocPtrNext := newAllocPtr
            
            when(newAllocPtr === s1_info.snapshot_freePtr) {
                flush_wrapModeNext := foundSurvivor // 如果新allocPtr等于freePtr，则根据是否找到幸存者来决定是否为满
            }.elsewhen(s1_info.snapshot_allocPtr > s1_info.snapshot_freePtr) { // Normal case on snapshot
                flush_wrapModeNext := newAllocPtr < s1_info.snapshot_freePtr // 如果新allocPtr小于freePtr，则表示发生了环绕
            }.otherwise { // Wrapped case on snapshot
                flush_wrapModeNext := newAllocPtr < s1_info.snapshot_freePtr || (newAllocPtr === s1_info.snapshot_freePtr && foundSurvivor)
            }
            
            // 4. 计算冲刷后每个槽位的新状态 (赋值给 flush_slotsNext)
            for (i <- 0 until sbDepth) {
                when(!isSurvivor(i) && isIndexInFlightOnSnapshot(U(i))) {
                    flush_slotsNext(i).valid := False
                    if (enableLog)
                        debug(L"[SQ-Ring] FLUSH_CALC_INVALIDATE: slotIdx=${i} (robPtr=${storage.slotsReg(i).robPtr}) marked for invalidation.")
                }
            }
            if (enableLog)
                notice(
                    L"[SQ-Ring] FLUSH_CALC_RESULT: Found=${foundSurvivor}, LastSurvivor=${lastSurvivorPtr}. Calc state: freePtr=${s1_info.snapshot_freePtr}, allocPtr=${flush_allocPtrNext}, wrapMode=${flush_wrapModeNext}"
                )
        }
    }

    // =================================================================
    //  Commit Logic (常规操作 - S2的一部分)
    // =================================================================
    val commit_logic_calc = new Area {
        val s1_info = s1_inputs.info

        val justCommitted = Vec.tabulate(sbDepth) { i =>
            val slot = storage.slotsReg(i) // 读取 S2 周期的当前状态
            
            // 关键的验证：必须在 S2 周期仍然在队列中
            val isInFlightNow = storage.isIndexInFlight(U(i))

            isInFlightNow && slot.valid && !slot.isCommitted &&
            (slot.robPtr === s1_info.commitRobPtr) && // 使用 S1 锁存的提交信息
            s1_info.commitCanFire && !blockRegularOps // 提交被冲刷抑制
        }
        
        // 提交逻辑现在只更新常规的 slotsNext
        for (i <- 0 until sbDepth) {
            when(justCommitted(i)) {
                slotsNext(i).isCommitted := True
                if (enableLog)
                    debug(
                        L"[SQ-Ring] COMMIT_EXEC: robPtr=${storage.slotsReg(i).robPtr} (slotIdx=${i}) marked as committed. pc=${storage.slotsReg(i).pc}, addr=${storage.slotsReg(i).addr}"
                    )
            }
        }
    }

    // =================================================================
    //  Pop & Write Logic (Freeing)
    // =================================================================
    val pop_write_logic = new Area {
      val headSlot = storage.slotsReg(storage.freePtrReg)
      // The commit status must be checked from the 'Next' view to react in the same cycle.
      val headSlotIsCommitted = slotsNext(storage.freePtrReg).isCommitted
      if (enableLog && verbose) {
        debug(
          L"[SQ-Ring-PopDetail] Cycle State: " :+
            L"allocPtr=${storage.allocPtrReg}, freePtr=${storage.freePtrReg}, wrapMode=${storage.wrapModeReg}, " :+
            L"isEmpty=${storage.isEmpty}, isFull=${storage.isFull}"
        )
        debug(
          L"[SQ-Ring-PopDetail] Head Slot (at freePtr=${storage.freePtrReg}) content: ${headSlot.format}"
        )
        debug(
          L"[SQ-Ring-PopDetail] Head Slot 'Next' isCommitted: ${headSlotIsCommitted}"
        )
      }

      // 判定队头是否可以发送到IO总线 (MMIO or RAM I/O)
      val isReadyToSend = !storage.isEmpty && headSlot.valid && headSlotIsCommitted &&
        !headSlot.isFlush && !headSlot.sentCmd && !headSlot.waitRsp && !headSlot.isWaitingForRefill &&
        !headSlot.isWaitingForWb && !headSlot.hasEarlyException && !blockRegularOps // 冲刷应用阶段禁止发送新命令

      val canSendToIO = isReadyToSend && headSlot.isIO
      when(Bool(!ioConfig.isDefined) && headSlot.isIO) {
        assert(False, "Got isIO but no IO service is configured.")
      }
      if (enableLog && verbose)
        debug(
          L"[SQ-Ring] PopLogic: isReadyToSend=${isReadyToSend}, canSendToIO=${canSendToIO}, headSlot.valid=${headSlot.valid}, headSlotIsCommitted=${headSlotIsCommitted}, headSlot.isFlush=${headSlot.isFlush}, headSlot.sentCmd=${headSlot.sentCmd}, headSlot.waitRsp=${headSlot.waitRsp}, headSlot.isWaitingForRefill=${headSlot.isWaitingForRefill}, headSlot.isWaitingForWb=${headSlot.isWaitingForWb}, headSlot.hasEarlyException=${headSlot.hasEarlyException}, headSlot.isIO=${headSlot.isIO}, blockRegularOps=${blockRegularOps}"
        )

      val ioCmdFired = hw.ioWriteChannel.map(ch => ch.cmd.ready && canSendToIO).getOrElse(False)

      // --- FIX: The IO response now needs to match ANY slot waiting for a response, not just the head ---
      val ioResponsePort = hw.ioWriteChannel.get.rsp
      // 1. Create a one-hot vector of matching slots.
      val matchOH = Vec(Bool(), sbDepth)
      for (i <- 0 until sbDepth) {
        val slot = storage.slotsReg(i)
        matchOH(i) := storage.isIndexInFlight(U(i)) && slot.valid && slot.isIO && slot.waitRsp && slot.txid === ioResponsePort.payload.id
      }
      
      // 2. Check for matching conditions.
      val numMatch = CountOne(matchOH)
      val responseMatchesSlot = numMatch === 1
      val responseMatchesNoSlot = numMatch === 0
      
      // 3. Convert the one-hot vector to an index. This is safe only when there's exactly one match.
      val matchingSlotIndex = OHToUInt(matchOH)

      // --- Assertions for Design Robustness ---
      when(ioResponsePort.fire) {
        assert(responseMatchesSlot || responseMatchesNoSlot, "IO response matches multiple SB slots simultaneously, this is a critical bug!")
        // Optional: Assert that we don't receive unexpected responses. This might be too strict if the bus can have other masters.
        // assert(responseMatchesSlot, "Received an IO response with a txid that does not match any waiting SB slot!")
      }
      
      // --- 4. Update the state of the matched slot ---
      when(ioResponsePort.fire && responseMatchesSlot) {
        val matchedSlot = slotsNext(matchingSlotIndex) // Use a temporary variable for clarity
        matchedSlot.waitRsp := False
        when(ioResponsePort.payload.error) {
          matchedSlot.hasEarlyException := True
          matchedSlot.earlyExceptionCode := ExceptionCode.STORE_ACCESS_FAULT
          if(enableLog) 
            debug(L"[SQ-Ring] IO RSP_ERROR for txid=${ioResponsePort.payload.id} matched to slotIdx=${matchingSlotIndex}.")
        }.otherwise {
          if(enableLog) 
            debug(L"[SQ-Ring] IO RSP_SUCCESS for txid=${ioResponsePort.payload.id} matched to slotIdx=${matchingSlotIndex} (robPtr=${storage.slotsReg(matchingSlotIndex).robPtr})")
        }
      }
      
      // The head can only be popped if its own IO operation is complete
      val headIoOperationDone = headSlot.sentCmd && !headSlot.waitRsp

      // --- 定义操作完成 (Operation Done) 逻辑 ---
      // 对于IO操作，完成意味着总线事务收到了响应。
      val ioOperationDone = headSlot.sentCmd && (!headSlot.waitRsp || (ioResponsePort.valid && responseMatchesSlot && matchingSlotIndex === storage.freePtrReg))

      // Pop condition: Entry is committed and operation is complete (or has an exception).
      // Pop is suppressed by a flush.
      val canBePopped = Bool()
      when(headSlot.isIO) {
        // IO 操作 (MMIO, RAM I/O) 必须等待其总线事务完成
        canBePopped := headIoOperationDone
      } otherwise {
        // Cacheable 操作提交即可，因为我们假设它们被内存系统立即处理
        // (在有D-Cache的真实系统中，这里会检查是否被D-Cache接受)
        canBePopped := True
      }

      // doPop 仅检查当周期是否有 ROB 冲刷，不检查 flush_pipeline.isApplying，因为 freePtr 的最终更新在 state_update 中有优先级处理
      doPop := !storage.isEmpty && headSlot.valid && headSlotIsCommitted && (canBePopped || headSlot.hasEarlyException) && !blockRegularOps
      if (enableLog && verbose)
        debug(
          L"[SQ-Ring] PopDecision: doPop=${doPop}, isEmpty=${storage.isEmpty}, headSlot.valid=${headSlot.valid}, headSlotIsCommitted=${headSlotIsCommitted}, canBePopped=${canBePopped}, hasEarlyException=${headSlot.hasEarlyException}, blockRegularOps=${blockRegularOps}"
        )

      val popMonitor = master Flow (StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams))
      popMonitor.valid := doPop
      popMonitor.payload := headSlot
      popMonitor.simPublic()

      // Drive IO channel (for both MMIO and RAM I/O)
      hw.ioWriteChannel.foreach { ch =>
        ch.simPublic()
        ch.cmd.valid := canSendToIO
        ch.cmd.address := headSlot.addr
        ch.cmd.data := headSlot.data
        ch.cmd.byteEnables := headSlot.be
        ch.cmd.last := True
        if (ioConfig.get.useId) {
          require(headSlot.txid.getWidth <= ch.cmd.id.getWidth, "TXID is too large for IO channel ID field.")
          ch.cmd.id := headSlot.txid.resized
        }
        
        // --- FIX: The response ready signal is now driven by the search result ---
        ch.rsp.ready := ioResponsePort.valid && responseMatchesSlot
        if (enableLog) {
          when(ch.cmd.valid) {
            debug(
              L"[SQ-Ring-IO] Sending IO STORE: payload=${ch.cmd.payload.format} headslot=${headSlot.format} valid=${ch.cmd.valid} ready=${ch.cmd.ready}"
            )
          }
        }
      }
      if (enableLog && verbose)
        debug(
          L"[SQ-Ring] ioCmdFired=${ioCmdFired} because canSendToIO=${canSendToIO}, channel.cmd.ready=${hw.ioWriteChannel
              .map(_.cmd.ready)
              .getOrElse(null)}"
        )

      // Update head slot state based on IO interaction
      when(ioCmdFired) {
        slotsNext(storage.freePtrReg).sentCmd := True
        slotsNext(storage.freePtrReg).waitRsp := True // 假设所有IO写都需要等待响应
        if (enableLog)
          debug(
            L"[SQ-Ring] CMD_FIRED_IO: robPtr=${headSlot.robPtr} (slotIdx=${storage.freePtrReg}), pc=${headSlot.pc}, addr=${headSlot.addr}, data=${headSlot.data}, be=${headSlot.be}"
          )
      }
      // The ioResponseForHead logic is now handled by the general matchingSlotIndex logic above.
      // We only need to ensure the head slot's waitRsp is cleared if it was the one that received the response.
      when(ioResponsePort.valid && responseMatchesSlot && matchingSlotIndex === storage.freePtrReg) {
        slotsNext(storage.freePtrReg).waitRsp := False
        when(ioResponsePort.payload.error) {
          slotsNext(storage.freePtrReg).hasEarlyException := True
          slotsNext(storage.freePtrReg).earlyExceptionCode := ExceptionCode.STORE_ACCESS_FAULT
          if (enableLog) debug(L"[SQ-Ring] IO RSP_ERROR for robPtr=${headSlot.robPtr} (slotIdx=${storage.freePtrReg}).")
        }.otherwise {
          if (enableLog)
            debug(L"[SQ-Ring] IO RSP_SUCCESS for robPtr=${headSlot.robPtr} (slotIdx=${storage.freePtrReg}).")
        }
      }


      // Update free pointer
      when(doPop) {
        freePtrNext := storage.freePtrReg + 1
        slotsNext(storage.freePtrReg).valid := False // Invalidate popped slot
        if (enableLog)
          debug(
            L"[SQ-Ring] POP: robPtr=${headSlot.robPtr} (slotIdx=${storage.freePtrReg}), pc=${headSlot.pc}, addr=${headSlot.addr}, data=${headSlot.data}, be=${headSlot.be} from slotIdx=${storage.freePtrReg}, newFreePtr=${freePtrNext}"
          )
      }
    }
    // =============================================================================
    //  Store-to-Load Forwarding & Bypass Logic (Pipelined)
    // =============================================================================
    val forwarding_and_bypass = new Area {
      val dataWidthBytes = pipelineConfig.dataWidth.value / 8
      val wordAddrBits = log2Up(dataWidthBytes)
      val wordAddrRange = pipelineConfig.pcWidth.value - 1 downto wordAddrBits
      val byteOffsetRange = wordAddrBits - 1 downto 0

      // --- Store-to-Load Forwarding Logic (SqQueryPort) ---
      val fwd = new Area {
        val query = hw.sqQueryPort.cmd
        val rsp = hw.sqQueryPort.rsp

        // NEW: 2-stage forwarding pipeline to break critical path
        val sqFwdPipe = new Pipeline {
          // --- 1. Stage Definitions ---
          val sqFwdS0 = newStage().setName("SQ_FWD_S0_CALC")
          val sqFwdS1 = newStage().setName("SQ_FWD_S1_DRIVE")
          connect(sqFwdS0, sqFwdS1)(Connection.M2S())

          // --- 2. Stageable Definition for inter-stage data transfer ---
          case class SqFwdIntermediate() extends Bundle {
            val originalQuery = SqQueryCmd(lsuConfig, pipelineConfig)
            val mergedHitMask = Bits(dataWidthBytes bits)
            val mergedData = Bits(pipelineConfig.dataWidth)
            val olderStoreIsUncoherent = Bool()
            val hasOlderOverlappingStore = Bool()
          }
          val SQ_FWD_INTERMEDIATE = Stageable(SqFwdIntermediate())

          // --- 3. S0: Heavy Calculation Stage ---
          sqFwdS0.valid := query.valid
          val s0_query = query.payload

          val s0_loadMask = MemAccessSize.toByteEnable(
            s0_query.size,
            s0_query.address(byteOffsetRange),
            dataWidthBytes
          )

          // Path A: Query against registered slots
          val s0_path_a = new Area {
            val slotResults = Vec.tabulate(sbDepth) { i =>
              val slot = storage.slotsReg(i)
              val acc = BypassAccumulator(pipelineConfig).setDefault()
              val isRelevant = storage.isIndexInFlight(U(i)) && slot.valid &&
                helper_functions.isOlder(slot.robPtr, s0_query.robPtr)
              val addrMatch = slot.addr(wordAddrRange) === s0_query.address(wordAddrRange)

              when(isRelevant && addrMatch) {
                when(s0_query.isCoherent && slot.isCoherent) {
                  for (k <- 0 until dataWidthBytes) {
                    when(slot.be(k) && s0_loadMask(k)) {
                      acc.data(k * 8, 8 bits) := slot.data(k * 8, 8 bits)
                      acc.hitMask(k) := True
                    }
                  }
                }
              }
              acc
            }
            def mergeBypassResults(older: BypassAccumulator, newer: BypassAccumulator): BypassAccumulator = {
              val merged = cloneOf(older)
              merged.hitMask := older.hitMask | newer.hitMask
              for (k <- 0 until dataWidthBytes) {
                merged.data(k * 8, 8 bits) := Mux(newer.hitMask(k), newer.data(k * 8, 8 bits), older.data(k * 8, 8 bits))
              }
              merged
            }
            val forwardedFromRegs = slotResults.reverse.reduceBalancedTree(mergeBypassResults)
          }

          // Path B: Query against in-flight push command
          val s0_path_b = new Area {
            val forwardedFromPush = BypassAccumulator(pipelineConfig).setDefault()
            when(doPush) {
              val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
              newSlotData.initFromCommand(pushPortIn.payload, nextTxid)
              val isRelevant = helper_functions.isOlder(newSlotData.robPtr, s0_query.robPtr)
              val addrMatch = newSlotData.addr(wordAddrRange) === s0_query.address(wordAddrRange)
              when(isRelevant && addrMatch) {
                when(s0_query.isCoherent && newSlotData.isCoherent) {
                  for (k <- 0 until dataWidthBytes) {
                    when(newSlotData.be(k) && s0_loadMask(k)) {
                      forwardedFromPush.data(k * 8, 8 bits) := newSlotData.data(k * 8, 8 bits)
                      forwardedFromPush.hitMask(k) := True
                    }
                  }
                }
              }
            }
          }

          // Merge paths and calculate stall conditions
          val s0_final_intermediate = s0_path_a.mergeBypassResults(s0_path_a.forwardedFromRegs, s0_path_b.forwardedFromPush)

          var s0_olderStoreIsUncoherent = False
          var s0_hasOlderOverlappingStore = False
          for (i <- 0 until sbDepth) {
            val slot = storage.slotsReg(i)
            val isRelevant = storage.isIndexInFlight(U(i)) && slot.valid && helper_functions.isOlder(slot.robPtr, s0_query.robPtr)
            val addrMatch = slot.addr(wordAddrRange) === s0_query.address(wordAddrRange)
            when(isRelevant && addrMatch) {
              s0_hasOlderOverlappingStore \= True
              when(!slot.isCoherent) {
                s0_olderStoreIsUncoherent \= True
              }
            }
          }
          when(doPush) {
            val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
            newSlotData.initFromCommand(pushPortIn.payload, nextTxid)
            val isRelevant = helper_functions.isOlder(newSlotData.robPtr, s0_query.robPtr)
            val addrMatch = newSlotData.addr(wordAddrRange) === s0_query.address(wordAddrRange)
            when(isRelevant && addrMatch) {
              s0_hasOlderOverlappingStore \= True
              when(!newSlotData.isCoherent) {
                s0_olderStoreIsUncoherent \= True
              }
            }
          }

          // Store all intermediate results into the stageable
          sqFwdS0(SQ_FWD_INTERMEDIATE).originalQuery := s0_query
          sqFwdS0(SQ_FWD_INTERMEDIATE).mergedHitMask := s0_final_intermediate.hitMask
          sqFwdS0(SQ_FWD_INTERMEDIATE).mergedData    := s0_final_intermediate.data
          sqFwdS0(SQ_FWD_INTERMEDIATE).olderStoreIsUncoherent := s0_olderStoreIsUncoherent
          sqFwdS0(SQ_FWD_INTERMEDIATE).hasOlderOverlappingStore := s0_hasOlderOverlappingStore

          // --- 4. S1: Lightweight Decision and Drive Stage ---
          rsp.valid := sqFwdS1.valid
          rsp.payload.setDefault()

          when(sqFwdS1.valid) {
            val s1_intermediate = sqFwdS1(SQ_FWD_INTERMEDIATE)
            val s1_query = s1_intermediate.originalQuery

            val s1_loadMask = MemAccessSize.toByteEnable(
              s1_query.size,
              s1_query.address(byteOffsetRange),
              dataWidthBytes
            )

            val s1_allRequiredBytesHit = (s1_intermediate.mergedHitMask & s1_loadMask) === s1_loadMask
            val s1_canForward = s1_query.isCoherent

            val s1_stallForUncoherent = s1_intermediate.olderStoreIsUncoherent
            val s1_stallForInsufficientCoverage = s1_canForward && s1_intermediate.hasOlderOverlappingStore && !s1_allRequiredBytesHit
            val s1_stallForUncoherentLoad = !s1_canForward && s1_intermediate.hasOlderOverlappingStore

            val s1_finalStall = s1_stallForUncoherent || s1_stallForInsufficientCoverage || s1_stallForUncoherentLoad
            val s1_finalHit = s1_canForward && s1_allRequiredBytesHit && !s1_finalStall

            rsp.payload.data := s1_intermediate.mergedData
            rsp.payload.olderStoreDataNotReady := s1_finalStall
            rsp.payload.hit := s1_finalHit

            if (enableLog) {
              when(sqFwdS1.isFiring) {
                debug(
                  L"[SQ-Fwd-S1] Firing response for Load robPtr=${s1_query.robPtr}, addr=${s1_query.address}. " :+
                  L"Result: hit=${s1_finalHit}, stall=${s1_finalStall}"
                )
              }
            }
          }
          
          // --- 5. Build the pipeline ---
          build()
        }
      }
    }
    // =================================================================
    //  Final State Update (S2结束 -> S3应用)
    // =================================================================
    val state_update = new Area {
      val doFlushCalc = s1_inputs.info.robFlushValid

      // --- S2 -> S3: 锁存冲刷计算结果 ---
      flush_pipeline.isApplying := doFlushCalc

      when(doFlushCalc) {
          flush_pipeline.latched_allocPtrNext := flush_logic_calc.flush_allocPtrNext
          flush_pipeline.latched_wrapModeNext := flush_logic_calc.flush_wrapModeNext
          flush_pipeline.latched_slotsNext    := flush_logic_calc.flush_slotsNext
          if (enableLog)
              debug(L"[SQ-Ring] FLUSH_LATCH: Latching flush results for next cycle.")
      }
      
      // --- 常规 wrapModeReg 更新逻辑 (优化后版本) ---
      val becomingFull = doPush && !doPop && (storage.allocPtrReg + 1 === storage.freePtrReg)
      val becomingEmpty = doPop && !doPush && (storage.freePtrReg + 1 === storage.allocPtrReg)

      // 这个逻辑只在没有冲刷请求时才计算，并且结果只送给常规的 wrapModeRegNext
      when(!doFlushCalc) { // 只有当没有冲刷计算时，才进行常规的 wrapMode 更新
          when(becomingFull) { // 只有当 push 和 pop 状态不同时才可能改变 wrapMode
              when(doPush) { // Queue grows
                      wrapModeRegNext := True
                      if (enableLog)
                          debug(
                              L"[SQ-Ring] WrapMode Update: Became Full (allocPtrNext=${allocPtrNext}, freePtrNext=${freePtrNext})"
                          )
              }.otherwise { // Queue shrinks
                      wrapModeRegNext := False
                      if (enableLog)
                          debug(
                              L"[SQ-Ring] WrapMode Update: Became Empty (freePtrNext=${freePtrNext}, allocPtrNext=${allocPtrNext})"
                          )
              }
          }
          // If doPush === doPop or neither happens, wrapMode does not change.
      }

      // --- 根据优先级选择最终的下一状态 ---
      when(flush_pipeline.isApplying) {
          // ---- S3: 应用冲刷结果 ----
          storage.allocPtrReg := flush_pipeline.latched_allocPtrNext
          storage.wrapModeReg := flush_pipeline.latched_wrapModeNext
          storage.slotsReg    := flush_pipeline.latched_slotsNext
          
          // **冻结 freePtr**
          storage.freePtrReg  := storage.freePtrReg
          if (enableLog)
              notice(
                  L"[SQ-Ring] FLUSH_APPLY: Applying latched flush results. New state: freePtr=${storage.freePtrReg}, allocPtr=${storage.allocPtrReg}, wrapMode=${storage.wrapModeReg}"
              )
          
      } .otherwise {
          // ---- 常规操作: Push/Pop ----
          storage.allocPtrReg := allocPtrNext // 来自常规push逻辑
          storage.freePtrReg  := freePtrNext  // 来自常规pop逻辑
          storage.wrapModeReg := wrapModeRegNext// 来自常规逻辑 (已优化)
          storage.slotsReg    := slotsNext    // 来自常规逻辑 (包含commit)
          if (enableLog && verbose)
              debug(
                  L"[SQ-Ring] Regular State Update: allocPtrReg=${storage.allocPtrReg} -> ${allocPtrNext}, freePtrReg=${storage.freePtrReg} -> ${freePtrNext}, wrapModeReg=${storage.wrapModeReg} -> ${wrapModeRegNext}"
              )
      }

      if (enableLog && verbose) {
        for (i <- 0 until sbDepth) {
          when(storage.slotsReg(i).valid || slotsNext(i).valid) { // Report if was valid or becomes valid
            debug(
              L"[SQ-Ring-Debug] SlotState[${i}]: " :+
                L"txid=${storage.slotsReg(i).txid}, " :+ // Use storage.slotsReg for current cycle's state
                L"robPtr=${storage.slotsReg(i).robPtr}, " :+
                L"pc=${storage.slotsReg(i).pc}, " :+
                L"addr=${storage.slotsReg(i).addr}, " :+
                L"valid=${storage.slotsReg(i).valid}, " :+
                L"isCommitted=${storage.slotsReg(i).isCommitted}, " :+
                L"sentCmd=${storage.slotsReg(i).sentCmd}, " :+
                L"waitRsp=${storage.slotsReg(i).waitRsp}, " :+
                L"isWaitingForRefill=${storage.slotsReg(i).isWaitingForRefill}, " :+
                L"isWaitingForWb=${storage.slotsReg(i).isWaitingForWb}, " :+
                L"hasEarlyException=${storage.slotsReg(i).hasEarlyException}, " :+
                L"isIO=${storage.slotsReg(i).isIO}, " :+
                L"isCoherent=${storage.slotsReg(i).isCoherent}, " :+
                L"isFlush=${storage.slotsReg(i).isFlush}"
            )
          }
        }
        hw.ioWriteChannel.foreach { ioChannel =>
          debug(
            L"[SQ-Ring-Debug] IO Interface: " :+
              L"cmd.valid=${ioChannel.cmd.valid}, " :+
              L"cmd.ready=${ioChannel.cmd.ready}, " :+
              L"rsp.valid=${ioChannel.rsp.valid}, " :+
              L"rsp.ready=${ioChannel.rsp.ready}"
          )
        }
      }
      if(enableLog) debug("...")
      // --- Resource Release ---
      hw.robServiceInst.release()
      hw.sgmbServiceOpt.foreach(_.release())
    }
  }
}
