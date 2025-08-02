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
    val doFlush = hw.robServiceInst.doRobFlush().valid

    // =================================================================
    //  Push Logic (Allocation)
    // =================================================================
    val push_logic = new Area {
      // Push is suppressed by a flush
      val canPush = !storage.isFull && !doFlush
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
            L"[SQ-Ring] PushPort: valid=${pushPortIn.valid}, ready=${pushPortIn.ready}, canPush=${canPush}, isFull=${storage.isFull}, doFlush=${doFlush}"
          )
        }
      }
    }

    val commit_flush_logic = new Area {
      val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
      val commitPort = commitInfoFromRob(0) // Given commitWidth = 1 for now, we can use the first element of the vector
      val robFlushPort = robService.doRobFlush()
      val doFlush = robFlushPort.valid

      // --- Pre-computation to break combinational loops ---
      // 1. 预计算本周期哪些槽位被提交了。这是一个独立的信号，不依赖于slotsNext。
      val justCommitted = Vec(Bool(), sbDepth)
      for (i <- 0 until sbDepth) {
        val slot = storage.slotsReg(i)
        justCommitted(i) := storage.isIndexInFlight(U(i)) && slot.valid && !slot.isCommitted &&
          (slot.robPtr === commitPort.entry.payload.uop.robPtr) &&
          commitPort.canCommit && (commitPort.entry.payload.uop.decoded.uopCode === BaseUopCode.STORE) &&
          !commitPort.entry.status.hasException && !doFlush
      }

      // --- Flush Logic (Pointer Rollback - The True Ring Buffer Way) ---
      when(doFlush) {
        if (enableLog)
          debug(
            L"[SQ-Ring] FLUSH received. Reason: ${robFlushPort.payload.reason}, targetRobPtr=${robFlushPort.payload.targetRobPtr}"
          )

        // 1. 确定哪些槽位是幸存者。幸存者是那些已提交的条目。
        val isSurvivor = Vec(Bool(), sbDepth)
        for (i <- 0 until sbDepth) {
          val slot = storage.slotsReg(i)
          // NOTE: 判断一个槽位是否应该在冲刷中幸存的唯一标准是它是否已经被提交 (slot.isCommitted)，而不是它是否正在被提交。
          val isCommitted = slot.isCommitted

          val shouldFlushThisSlot = Bool()
          switch(robFlushPort.payload.reason) {
            is(FlushReason.FULL_FLUSH) {
              shouldFlushThisSlot := !isCommitted
            }
            is(FlushReason.ROLLBACK_TO_ROB_IDX) {
              shouldFlushThisSlot := !isCommitted && helper_functions.isNewerOrSame(
                slot.robPtr,
                robFlushPort.payload.targetRobPtr
              )
            }
            default {
              shouldFlushThisSlot := False
            }
          }
          // 一个槽位是幸存者，当且仅当它当前有效，且不被冲刷
          isSurvivor(i) := storage.isIndexInFlight(U(i)) && !shouldFlushThisSlot
        }

        // 2. 使用 foldLeft 安全地反向查找最新的幸存者
        //    我们构建一个元组 (found: Bool, ptr: UInt) 作为累加器
        val initialAccumulator = (False, U(0, addressWidth bits))

        val (found, lastSurvivorPtr) = (0 until sbDepth).foldLeft(initialAccumulator) { (acc, i) =>
          val (foundSoFar, ptrSoFar) = acc
          val readPtr = storage.allocPtrReg - 1 - i // 从 allocPtr-1, -2, ...

          // 如果我们还没有找到，并且当前的槽位是一个幸存者，那么就更新累加器
          val newFound = foundSoFar || isSurvivor(readPtr)
          val newPtr = Mux(isSurvivor(readPtr) && !foundSoFar, readPtr, ptrSoFar)

          (newFound, newPtr)
        }

        // 3. 根据查找结果更新指针
        when(found) {
          // 找到了幸存者，allocPtr 回退到最新幸存者的下一个位置
          allocPtrNext := lastSurvivorPtr + 1

          val newAllocPtrAfterInc = lastSurvivorPtr + 1
          // 重新计算 wrapMode，这个逻辑很关键
          when(storage.allocPtrReg > storage.freePtrReg) { // 原来没有回绕
            wrapModeRegNext := (newAllocPtrAfterInc < storage.freePtrReg) || (newAllocPtrAfterInc === storage.freePtrReg)
          } otherwise { // 原来已经回绕或是满的
            val allocWrapped = storage.allocPtrReg <= storage.freePtrReg && !storage.isEmpty
            val newAllocWrapped = newAllocPtrAfterInc <= storage.freePtrReg
            wrapModeRegNext := newAllocWrapped
          }
          // The most robust wrapMode calculation:
          // New wrap is true if new alloc ptr is "behind" or at free ptr, AND the buffer isn't becoming empty.
          when(newAllocPtrAfterInc === storage.freePtrReg) {
            // If we rollback and the new alloc ptr is exactly the free ptr, is it full or empty?
            // It depends on whether we found any survivors. Since `found` is true, it must be full.
            wrapModeRegNext := True
          } otherwise {
            wrapModeRegNext := newAllocPtrAfterInc < storage.freePtrReg
          }

        } otherwise {
          // 没有任何幸存者，队列变空
          allocPtrNext := storage.freePtrReg
          wrapModeRegNext := False
        }

        // 4. 将被冲刷的条目无效化 (可选但推荐)
        for (i <- 0 until sbDepth) {
          when(!isSurvivor(i) && storage.isIndexInFlight(U(i))) {
            slotsNext(i).valid := False
            if (enableLog)
              debug(L"[SQ-Ring] FLUSH_INVALIDATE: slotIdx=${i} (robPtr=${storage.slotsReg(i).robPtr}) invalidated.")
          }
        }

        if (enableLog)
          notice(
            L"[SQ-Ring] FLUSH_POINTER_ROLLBACK: Found=${found}, LastSurvivor=${lastSurvivorPtr}. New state: freePtr=${freePtrNext}, allocPtr=${allocPtrNext}, wrapMode=${wrapModeRegNext}"
          )

      } otherwise {
        for (i <- 0 until sbDepth) {
          when(justCommitted(i)) {
            slotsNext(i).isCommitted := True
            if (enableLog)
              debug(
                L"[SQ-Ring] COMMIT_EXEC: robPtr=${storage.slotsReg(i).robPtr} (slotIdx=${i}) marked as committed. pc=${storage
                    .slotsReg(i)
                    .pc}, addr=${storage.slotsReg(i).addr}"
              )
          }
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
        !headSlot.isWaitingForWb && !headSlot.hasEarlyException

      val canSendToIO = isReadyToSend && headSlot.isIO
      when(Bool(!ioConfig.isDefined) && headSlot.isIO) {
        assert(False, "Got isIO but no IO service is configured.")
      }
      if (enableLog && verbose)
        debug(
          L"[SQ-Ring] PopLogic: isReadyToSend=${isReadyToSend}, canSendToIO=${canSendToIO}, headSlot.valid=${headSlot.valid}, headSlotIsCommitted=${headSlotIsCommitted}, headSlot.isFlush=${headSlot.isFlush}, headSlot.sentCmd=${headSlot.sentCmd}, headSlot.waitRsp=${headSlot.waitRsp}, headSlot.isWaitingForRefill=${headSlot.isWaitingForRefill}, headSlot.isWaitingForWb=${headSlot.isWaitingForWb}, headSlot.hasEarlyException=${headSlot.hasEarlyException}, headSlot.isIO=${headSlot.isIO}"
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
          if(enableLog) debug(L"[SQ-Ring] IO RSP_ERROR for txid=${ioResponsePort.payload.id} matched to slotIdx=${matchingSlotIndex}.")
        }.otherwise {
          if(enableLog) debug(L"[SQ-Ring] IO RSP_SUCCESS for txid=${ioResponsePort.payload.id} matched to slotIdx=${matchingSlotIndex} (robPtr=${storage.slotsReg(matchingSlotIndex).robPtr})")
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

      doPop := !storage.isEmpty && headSlot.valid && headSlotIsCommitted && (canBePopped || headSlot.hasEarlyException) && !doFlush
      if (enableLog && verbose)
        debug(
          L"[SQ-Ring] PopDecision: doPop=${doPop}, isEmpty=${storage.isEmpty}, headSlot.valid=${headSlot.valid}, headSlotIsCommitted=${headSlotIsCommitted}, canBePopped=${canBePopped}, hasEarlyException=${headSlot.hasEarlyException}, doFlush=${doFlush}"
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
        if (ioConfig.get.useId) ch.cmd.id := headSlot.txid
        
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
    //  Store-to-Load Forwarding & Bypass Logic (Purely Combinational)
    // =============================================================================
    val forwarding_and_bypass = new Area {
      val dataWidthBytes = pipelineConfig.dataWidth.value / 8
      val wordAddrBits = log2Up(dataWidthBytes)
      val wordAddrRange = pipelineConfig.pcWidth.value - 1 downto wordAddrBits
      val byteOffsetRange = wordAddrBits - 1 downto 0

      val query_view = new Area {
        // NOTE: 这个写法错误，会偶尔导致查询逻辑错误地读取了被同一周期pop操作无效化后的状态。 查询逻辑应该看到pop发生之前的状态。
        // val slotsCurrentCycleView = CombInit(slotsNext) <-- 引以为鉴
        val slotsCurrentCycleView = CombInit(storage.slotsReg) 
        when(doPush) {
          val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
          newSlotData.initFromCommand(pushPortIn.payload, nextTxid)
          slotsCurrentCycleView(storage.allocPtrReg) := newSlotData
          if (enableLog && verbose)
            debug(
              L"[SQ-Ring-FwdBypass] QueryView: Overriding slot ${storage.allocPtrReg} with new push data (robPtr=${pushPortIn.payload.robPtr})"
            )
        }

        def isIndexInFlightForQuery(idx: UInt): Bool = {
          storage.isIndexInFlight(idx) || (doPush && (idx === storage.allocPtrReg))
        }

        val queryTraversalStartPtr = storage.allocPtrReg
        if (enableLog && verbose)
          debug(L"[SQ-Ring-FwdBypass] QueryView: queryTraversalStartPtr=${queryTraversalStartPtr}")
      }

      // --- Store-to-Load Forwarding Logic (SqQueryPort) ---
      val fwd = new Area {
        val query = hw.sqQueryPort.cmd
        val rsp = hw.sqQueryPort.rsp

        when(query.valid) {
          val loadMask = MemAccessSize.toByteEnable(
            query.payload.size,
            query.payload.address(byteOffsetRange),
            dataWidthBytes
          )

          val bypassInitial = BypassAccumulator(pipelineConfig).setDefault()

          // ======================= BUG FIX: 重构暂停信号 =======================
          // olderStoreIsUncoherent: 用于记录是否遇到了任何一个不可转发的(MMIO)Store
          var olderStoreIsUncoherent = False
          var hasOlderOverlappingStore = False
          // =================================================================

          if (enableLog && verbose) debug(L"[SQ-Ring-Fwd] Query: ${query.payload.format}, loadMask=${loadMask}")

          val finalResult = (0 until sbDepth).foldLeft(bypassInitial) { (acc, i) =>
            val nextAcc = CombInit(acc)
            val readPtr = query_view.queryTraversalStartPtr - i
            val slot = query_view.slotsCurrentCycleView(readPtr)

            val isRelevant = query_view.isIndexInFlightForQuery(readPtr) && slot.valid &&
              helper_functions.isOlder(slot.robPtr, query.payload.robPtr)

            when(isRelevant) {
              val loadWordAddr = query.payload.address(wordAddrRange)
              val storeWordAddr = slot.addr(wordAddrRange)

              when(loadWordAddr === storeWordAddr) {
                hasOlderOverlappingStore := True

                // --- 仅在遍历中判断MMIO ---
                when(!slot.isCoherent) {
                  // 只要遇到一个地址重叠的MMIO，就必须暂停。这个决策是立即且最终的。
                  olderStoreIsUncoherent := True
                  if (enableLog && verbose)
                    debug(
                      L"[SQ-Ring-Fwd] MMIO Hazard DETECTED from robPtr=${slot.robPtr}, pc=${slot.pc}, addr=${slot.addr}. Load robPtr=${query.payload.robPtr}, addr=${query.payload.address}"
                    )
                }

                // --- 数据转发逻辑: 不再做任何暂停判断！ ---
                // 只要Load和Store都是Coherent的，就尝试转发数据。
                when(query.payload.isCoherent && slot.isCoherent) {
                  for (k <- 0 until dataWidthBytes) {
                    when(slot.be(k) && loadMask(k) && !acc.hitMask(k)) {
                      nextAcc.data(k * 8, 8 bits) := slot.data(k * 8, 8 bits)
                      nextAcc.hitMask(k) := True
                      if (enableLog)
                        debug(
                          L"[SQ-Ring-Fwd] Hit byte ${k} from Coherent store robPtr=${slot.robPtr} (slotIdx=${readPtr}) pc=${slot.pc}, addr=${slot.addr}, data=${slot.data}, be=${slot.be}. Load robPtr=${query.payload.robPtr}, addr=${query.payload.address}, size=${query.payload.size}"
                        )
                    }
                  }
                }
              }
            }
            nextAcc
          }

          // ======================= BUG FIX: 在遍历后进行最终的暂停决策 =======================

          // 1. 数据是否凑齐?
          val allRequiredBytesHit = (finalResult.hitMask & loadMask) === loadMask

          // 2. Load是否可以被转发? (Load必须是Coherent的)
          val canForward = query.payload.isCoherent

          // 3. 计算暂停原因
          //   a. 如果有MMIO风险，必须暂停
          val stallForUncoherent = olderStoreIsUncoherent

          //   b. 如果Load可以被转发，但数据没凑齐，也必须暂停
          val stallForInsufficientCoverage = canForward && hasOlderOverlappingStore && !allRequiredBytesHit

          //   c. 如果Load本身是Non-coherent，且有重叠的老Store，也必须暂停
          val stallForUncoherentLoad = !canForward && hasOlderOverlappingStore

          val finalStall = stallForUncoherent || stallForInsufficientCoverage || stallForUncoherentLoad
          if (enableLog && verbose) {
              debug(
                L"[SQ-Ring-Fwd] QueryView: finalStall=${finalStall} due to " :+
                L"(allRequiredBytesHit=${allRequiredBytesHit} due to finalResult.hitMask=${finalResult.hitMask}, loadMask=${loadMask}), " :+
                L"(canForward=${canForward} due to query.payload.isCoherent=${query.payload.isCoherent}), " :+
                L"(stallForUncoherent=${stallForUncoherent} due to olderStoreIsUncoherent=${olderStoreIsUncoherent}), " :+
                L"(stallForInsufficientCoverage=${stallForInsufficientCoverage} due to hasOlderOverlappingStore=${hasOlderOverlappingStore}), " :+
                L"(stallForUncoherentLoad=${stallForUncoherentLoad} due to !canForward=${!canForward} && hasOlderOverlappingStore=${hasOlderOverlappingStore})"
              )
          }


          // 4. 计算最终的命中信号
          //    只有在允许转发、数据完全凑齐且不需要暂停的情况下，才算命中。
          val finalHit = canForward && allRequiredBytesHit && !finalStall
          if (enableLog && verbose) debug(L"[SQ-Ring-Fwd] Query: finalHit=${finalHit} due to canForward=${canForward} && allRequiredBytesHit=${allRequiredBytesHit} && !finalStall=${!finalStall}")

          // ===================================================================================

          rsp.data := finalResult.data
          rsp.olderStoreDataNotReady := finalStall
          rsp.olderStoreHasUnknownAddress := False
          rsp.hit := finalHit

          if (enableLog && verbose) {
            debug(
              L"[SQ-Ring-Fwd] Result: hitMask=${finalResult.hitMask}, allHit=${allRequiredBytesHit}, canForward=${canForward}, stall=${finalStall}, finalRsp.hit=${finalHit}"
            )
            debug(L"[SQ-Ring-Fwd] Rsp: ${rsp.format}")
          } else if (enableLog) {
            when(finalHit) {
              debug(
                L"[SQ-Ring-Fwd] FORWARDED: Load robPtr=${query.payload.robPtr}, addr=${query.payload.address}, size=${query.payload.size}. Data=${rsp.data}, HitMask=${finalResult.hitMask}"
              )
            }
          } else if (enableLog) {
            when(finalStall) {
              debug(
                L"[SQ-Ring-Fwd] STALL: Load robPtr=${query.payload.robPtr}, addr=${query.payload.address}, size=${query.payload.size}. Reason: olderStoreIsUncoherent=${olderStoreIsUncoherent}, stallForInsufficientCoverage=${stallForInsufficientCoverage}, stallForUncoherentLoad=${stallForUncoherentLoad}"
              )
            }
          }
        } otherwise { // Default values when query is not valid
          if(enableLog && verbose) {
            debug(
              L"[SQ-Ring-Fwd] Query: valid=${query.valid}"
            )
          }
          rsp.setDefault()
        }
      }
      // --- Same-cycle Pipeline Bypass Logic ---
      val bypass = new Area {
        val loadQueryBe =
          MemAccessSize.toByteEnable(bypassQuerySz, bypassQueryAddr(byteOffsetRange), dataWidthBytes)
        val bypassInitial = BypassAccumulator(pipelineConfig).setDefault()

        if (enableLog && verbose)
          debug(L"[SQ-Ring-Bypass] Query: addr=${bypassQueryAddr}, size=${bypassQuerySz}, loadQueryBe=${loadQueryBe}")

        val finalBypassResult = (0 until sbDepth).foldLeft(bypassInitial) { (acc, i) =>
          val nextAcc = CombInit(acc)
          val readPtr = query_view.queryTraversalStartPtr - i
          val slot = query_view.slotsCurrentCycleView(readPtr)

          when(query_view.isIndexInFlightForQuery(readPtr) && slot.valid) {
            val loadWordAddr = bypassQueryAddr(wordAddrRange)
            val storeWordAddr = slot.addr(wordAddrRange)

            when(loadWordAddr === storeWordAddr) {
              // Bypass 逻辑: 只有 Coherent 的操作才能提供数据
              when(slot.isCoherent) {
                for (k <- 0 until dataWidthBytes) {
                  when(slot.be(k) && loadQueryBe(k) && !acc.hitMask(k)) {
                    nextAcc.data(k * 8, 8 bits) := slot.data(k * 8, 8 bits)
                    nextAcc.hitMask(k) := True
                    if (enableLog)
                      debug(
                        L"[SQ-Ring-Bypass] Hit byte ${k} from slot ${readPtr} (robPtr=${slot.robPtr}) pc=${slot.pc}, addr=${slot.addr}, data=${slot.data}, be=${slot.be}. Load addr=${bypassQueryAddr}, size=${bypassQuerySz}"
                      )
                  }
                }
              }
            }
          }
          nextAcc
        }

        val finalHitMask = finalBypassResult.hitMask
        val overallBypassHit = finalHitMask.orR // 只要有任何一个字节命中就算valid
        bypassDataOut.valid := overallBypassHit
        bypassDataOut.payload.data := finalBypassResult.data
        bypassDataOut.payload.hitMask := finalHitMask
        // 'hit' 信号表示所有请求的字节都被命中了
        bypassDataOut.payload.hit := overallBypassHit && ((finalHitMask & loadQueryBe) === loadQueryBe)

        if (enableLog && verbose)
          debug(
            L"[SQ-Ring-Bypass] bypassDataOut: valid=${bypassDataOut.valid}, data=${bypassDataOut.payload.data}, hit=${bypassDataOut.payload.hit}, hitMask=${bypassDataOut.payload.hitMask}"
          )
        else if (enableLog) {
          when(overallBypassHit) {
            debug(
              L"[SQ-Ring-Bypass] BYPASSED: Load addr=${bypassQueryAddr}, size=${bypassQuerySz}. Data=${bypassDataOut.payload.data}, HitMask=${finalHitMask}"
            )
          }
        }
      }
    }

    // =================================================================
    //  Final State Update
    // =================================================================
    val state_update = new Area {
      // --- Regular wrapModeReg Update (when no flush) ---
      // This logic is only active when a flush is not occurring.
      when(!doFlush) {
        when(doPush =/= doPop) {
          when(doPush) { // Queue grows
            when(allocPtrNext === freePtrNext) {
              wrapModeRegNext := True // Became full
              if (enableLog)
                debug(
                  L"[SQ-Ring] WrapMode Update: Became Full (allocPtrNext=${allocPtrNext}, freePtrNext=${freePtrNext})"
                )
            }
          }.otherwise { // Queue shrinks
            when(freePtrNext === allocPtrNext) {
              wrapModeRegNext := False // Became empty
              if (enableLog)
                debug(
                  L"[SQ-Ring] WrapMode Update: Became Empty (freePtrNext=${freePtrNext}, allocPtrNext=${allocPtrNext})"
                )
            }
          }
        }
        // If doPush === doPop or neither happens, wrapMode does not change.
      }

      // --- Latch Final Register Values ---
      // The 'Next' signals, which have been calculated based on priority (Flush > Pop/Push),
      // are now assigned to the actual registers.
      val prevAllocPtr = storage.allocPtrReg
      val prevFreePtr = storage.freePtrReg
      val prevWrapMode = storage.wrapModeReg

      storage.allocPtrReg := allocPtrNext
      storage.freePtrReg := freePtrNext
      storage.wrapModeReg := wrapModeRegNext
      storage.slotsReg := slotsNext

      if (enableLog && verbose) {
        debug(
          L"[SQ-Ring] State Update: allocPtrReg=${storage.allocPtrReg} -> ${allocPtrNext}, freePtrReg=${storage.freePtrReg} -> ${freePtrNext}, wrapModeReg=${storage.wrapModeReg} -> ${wrapModeRegNext}"
        )
        for (i <- 0 until sbDepth) {
          when(storage.slotsReg(i).valid || slotsNext(i).valid) { // Report if was valid or becomes valid
            debug(
              L"[SQ-Ring-Debug] SlotState[${i}]: " :+
                L"txid=${slotsNext(i).txid}, " :+
                L"robPtr=${slotsNext(i).robPtr}, " :+
                L"pc=${slotsNext(i).pc}, " :+
                L"addr=${slotsNext(i).addr}, " :+
                L"valid=${slotsNext(i).valid}, " :+
                L"isCommitted=${slotsNext(i).isCommitted}, " :+
                L"sentCmd=${slotsNext(i).sentCmd}, " :+
                L"waitRsp=${slotsNext(i).waitRsp}, " :+
                L"isWaitingForRefill=${slotsNext(i).isWaitingForRefill}, " :+
                L"isWaitingForWb=${slotsNext(i).isWaitingForWb}, " :+
                L"hasEarlyException=${slotsNext(i).hasEarlyException}, " :+
                L"isIO=${slotsNext(i).isIO}, " :+
                L"isCoherent=${slotsNext(i).isCoherent}, " :+
                L"isFlush=${slotsNext(i).isFlush}"
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
      debug("...")
      // --- Resource Release ---
      hw.robServiceInst.release()
      hw.sgmbServiceOpt.foreach(_.release())
    }
  }
}
