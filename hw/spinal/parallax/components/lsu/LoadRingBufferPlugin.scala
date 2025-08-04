// filename: parallax/components/lsu/LoadRingBufferPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.core.sim._ // simPublic
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.utilities._
import parallax.utilities.ParallaxSim.{debug, notice}
import parallax.execute.WakeupService
import scala.collection.mutable.ArrayBuffer

// NOTE: Case class definitions `LoadQueuePushCmd`, `SqQueryPort`, etc. are in the same package.
//       The LoadQueueService trait is defined as in the old plugin.

/** 新的 LoadRingBufferSlot, 采用环形缓冲区设计.
  * 状态由一系列布尔标志管理, 以清晰地表示加载指令的生命周期.
  */
case class LoadRingBufferSlot(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheParams: DataCacheParameters)
    extends Bundle
    with Formattable {
  // --- 身份信息 (在Push时设置, 之后不变) ---
  val valid = Bool()
  val robPtr = UInt(lsuCfg.robPtrWidth)
  val pdest = UInt(pCfg.physGprIdxWidth)
  val pc = UInt(lsuCfg.pcWidth)
  val address = UInt(lsuCfg.pcWidth)
  val size = MemAccessSize()
  val isIO = Bool()
  val isCoherent = Bool()
  val isSignedLoad = Bool()
  val txid = UInt(pCfg.transactionIdWidth bits) // 用于匹配乱序返回的IO/内存响应

  // --- 状态标志 (在生命周期中会改变) ---
  val isFlushed = Bool() // NEW: 标记此条目已被流水线冲刷
  val hasEarlyException = Bool() // 在分派时就检测到的异常
  val earlyExceptionCode = UInt(8 bits)

  val isQueryingSB = Bool() // 正在向Store Buffer查询依赖
  val isStalledBySB = Bool() // 被Store Buffer告知需要暂停 (依赖未解决)
  val isReadyForMem = Bool() // 已确认无依赖, 可以安全地向内存系统(D-Cache/IO)发出请求
  val isWaitingForMemRsp = Bool() // 已发出内存请求, 正在等待响应
  val isLoaded = Bool() // 数据已加载到此槽位 (来自内存或转发), 但还未写回
  val isWritingBack = Bool() // 正在执行为期1周期的写回操作

  // --- 结果载荷 (在完成时填充) ---
  val data = Bits(pCfg.dataWidth)
  val hasFault = Bool() // 从内存系统返回的故障/异常
  val exceptionCode = UInt(8 bits)

  def setDefault(): this.type = {
    valid := False
    robPtr.assignDontCare()
    pdest.assignDontCare()
    pc.assignDontCare()
    address.assignDontCare()
    size.assignDontCare()
    isIO := False
    isCoherent := False
    isSignedLoad := False
    txid.assignDontCare()

    isFlushed := False // NEW: Default to not flushed
    hasEarlyException := False
    earlyExceptionCode.assignDontCare()

    isQueryingSB := False
    isStalledBySB := False
    isReadyForMem := False
    isWaitingForMemRsp := False
    isLoaded := False
    isWritingBack := False

    data.assignDontCare()
    hasFault := False
    exceptionCode.assignDontCare()
    this
  }

  def initFromPushCmd(cmd: LoadQueuePushCmd, transactionId: UInt): this.type = {
    valid := True
    robPtr := cmd.robPtr
    pdest := cmd.pdest
    pc := cmd.pc
    address := cmd.address
    size := cmd.size
    isIO := cmd.isIO
    isCoherent := cmd.isCoherent
    isSignedLoad := cmd.isSignedLoad
    txid := transactionId

    isFlushed := False // NEW: Initialize to not flushed
    hasEarlyException := cmd.hasEarlyException
    earlyExceptionCode := cmd.earlyExceptionCode

    // Initialize all mutable state flags to False
    isQueryingSB := False
    isStalledBySB := False
    isReadyForMem := False
    isWaitingForMemRsp := False
    isLoaded := False
    isWritingBack := False

    data.assignDontCare()
    hasFault := False
    exceptionCode.assignDontCare()
    this
  }

  override def format: Seq[Any] = Seq(
    L"LQSlot(valid=${valid}, robPtr=${robPtr}, pc=${pc}, addr=${address}, pdest=${pdest}, " :+
      L"state: flushed=${isFlushed}, earlyEx=${hasEarlyException}, querySB=${isQueryingSB}, stallSB=${isStalledBySB}, readyMem=${isReadyForMem}, waitRsp=${isWaitingForMemRsp}, loaded=${isLoaded}, writingBack=${isWritingBack})"
  )
}

class LoadRingBufferPlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters,
    val lqDepth: Int,
    val ioConfig: Option[GenericMemoryBusConfig] = None
) extends Plugin
    with LoadQueueService {
  require(isPow2(lqDepth), s"LoadRingBufferPlugin depth must be a power of two. Got $lqDepth.")

  val enableLog = false // 控制关键事件日志
  val verbose = true // 控制高频/每周期日志

  // --- Service Interface Implementation ---
  private val pushPorts = ArrayBuffer[Stream[LoadQueuePushCmd]]()
  override def newPushPort(): Stream[LoadQueuePushCmd] = {
    this.framework.requireEarly()
    val port = Stream(LoadQueuePushCmd(pipelineConfig, lsuConfig))
    pushPorts += port
    port
  }

  // --- Hardware Area: Define all hardware interfaces ---
  val hw = create early new Area {
    val robServiceInst = getService[ROBService[RenamedUop]]
    val prfServiceInst = getService[PhysicalRegFileService]
    val storeBufferServiceInst = getService[StoreBufferService]
    val wakeupServiceInst = getService[WakeupService]

    // dcachedisable: Keeping dcacheServiceInst commented out to match old code's style, but it will be needed.
    // val dcacheServiceInst = getService[DataCacheService]
    // dcachedisable val dCacheLoadPort = dcacheServiceInst.newLoadPort(priority = 1)

    val robLoadWritebackPort = robServiceInst.newWritebackPort("LQ_Load")
    val prfWritePort = prfServiceInst.newPrfWritePort(s"LQ.gprWritePort")
    val sbQueryPort = storeBufferServiceInst.getStoreQueueQueryPort()
    val wakeupPort = wakeupServiceInst.newWakeupSource("LQ.wakeupPort")

    var sgmbServiceOpt: Option[SgmbService] = None
    var ioReadChannel: Option[SplitGmbReadChannel] = None

    if (ioConfig.isDefined) {
      val sgmbService = getService[SgmbService]
      sgmbServiceOpt = Some(sgmbService)
      ioReadChannel = Some(sgmbService.newReadPort())
    }

    // Retain all services
    sgmbServiceOpt.foreach(_.retain())
    robServiceInst.retain()
    prfServiceInst.retain()
    storeBufferServiceInst.retain()
    wakeupServiceInst.retain()
    // dcacheServiceInst.retain()
  }

  // --- Core Logic ---
  val logic = create late new Area {
    lock.await()
    val robService = hw.robServiceInst

    // Arbitrate multiple push ports into one
    val pushCmd = StreamArbiterFactory.roundRobin.on(pushPorts)

    val nextTxid = RegInit(U(0, pipelineConfig.transactionIdWidth bits))
    val addressWidth = log2Up(lqDepth)

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
      val slotsReg = Vec.fill(lqDepth)(Reg(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
      val allocPtrReg = Reg(UInt(addressWidth bits)).init(0) // Tail pointer
      val freePtrReg = Reg(UInt(addressWidth bits)).init(0) // Head pointer
      val wrapModeReg = Reg(Bool()).init(False) // True if allocPtr has wrapped around freePtr

      val isFull = (allocPtrReg === freePtrReg) && wrapModeReg
      val isEmpty = (allocPtrReg === freePtrReg) && !wrapModeReg

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

      for (i <- 0 until lqDepth) {
        slotsReg(i).init(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
      }
    }

    // =================================================================
    //  Next-state Logic (Combinational Views)
    // =================================================================
    val slotsNext = CombInit(storage.slotsReg)
    val allocPtrNext = CombInit(storage.allocPtrReg)
    val freePtrNext = CombInit(storage.freePtrReg)
    val wrapModeRegNext = CombInit(storage.wrapModeReg)

    val doPush = Bool()
    val doPop = Bool() // Will be driven in Part 3
    val doFlush = hw.robServiceInst.doRobFlush().valid

    // =================================================================
    //  Push Logic (Allocation)
    // =================================================================
    val push_logic = new Area {
      val canPush = !storage.isFull && !doFlush
      pushCmd.ready := canPush
      doPush := pushCmd.fire

      when(doPush) {
        val newSlotData = LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
        newSlotData.initFromPushCmd(pushCmd.payload, nextTxid)
        nextTxid := nextTxid + 1

        slotsNext(storage.allocPtrReg) := newSlotData
        allocPtrNext := storage.allocPtrReg + 1

        if (enableLog)
          debug(
            L"[LQ-Ring] PUSH: robPtr=${pushCmd.payload.robPtr}, pc=${pushCmd.payload.pc} load_addr=${pushCmd.payload.address} to slotIdx=${storage.allocPtrReg}"
          )
      }
    }


    // =================================================================
    //  Flush Logic (Pointer Rollback with Drain Support)
    // =================================================================
    val flush_logic = new Area {
      val robFlushPort = hw.robServiceInst.doRobFlush()
      val doFlush = robFlushPort.valid

      when(doFlush) {
        if (enableLog)
          notice(L"[LQ-Ring] FLUSH received. Reason: ${robFlushPort.payload.reason}, targetRobPtr=${robFlushPort.payload.targetRobPtr}")

        // --- Step 1: Determine the fate of each slot (purely combinational) ---
        val isKept = Vec(Bool(), lqDepth)
        for (i <- 0 until lqDepth) {
          val slot = storage.slotsReg(i)
          val isInFlight = storage.isIndexInFlight(U(i))
          
          isKept(i) := False
          when(isInFlight && slot.valid) {
            val shouldFlushThisSlot = Bool()
            switch(robFlushPort.payload.reason) {
              is(FlushReason.FULL_FLUSH) {
                shouldFlushThisSlot := True
              }
              is(FlushReason.ROLLBACK_TO_ROB_IDX) {
                shouldFlushThisSlot := helper_functions.isNewerOrSame(slot.robPtr, robFlushPort.payload.targetRobPtr)
              }
              default {
                shouldFlushThisSlot := False
              }
            }
            // 一个槽位被保留，如果它不应被冲刷，或者它需要等待内存响应（drain）
            isKept(i) := !shouldFlushThisSlot || slot.isWaitingForMemRsp
          }
        }

        // ======================= 新的优化实现 =======================
        
        // 1. 定义规约操作所需的数据结构
        case class FindLastResult() extends Bundle {
          val found = Bool()
          val ptr = UInt(addressWidth bits)
        }

        // 2. 准备初始输入向量
        val initialResults = Vec.tabulate(lqDepth) { i =>
          val result = FindLastResult()
          result.found := isKept(i)
          result.ptr   := U(i)
          result
        }

        // 3. 定义合并操作 (older, newer) => merged
        //    这里的优先级是：如果 newer.found 为真，就取 newer.ptr
        def mergeFindLast(older: FindLastResult, newer: FindLastResult): FindLastResult = {
          val merged = FindLastResult()
          merged.found := older.found || newer.found
          merged.ptr   := Mux(newer.found, newer.ptr, older.ptr)
          merged
        }

        // 4. 使用 reduceBalancedTree 进行规约
        //    我们想找索引最新的（最大的）那个，但 reduceBalancedTree 是从索引0开始合并。
        //    所以我们需要反转原始的 isKept 顺序来思考。
        //    或者更简单的，我们让合并操作优先选择索引更大的指针。
        //    让我们重新定义合并函数，使其更健壮。
        def mergeFindLastRobust(left: FindLastResult, right: FindLastResult): FindLastResult = {
          val merged = FindLastResult()
          merged.found := left.found || right.found
          // 关键：总是选择右边（索引更大）的指针，如果它有效的话。否则，如果左边有效，选左边的。
          merged.ptr := Mux(right.found, right.ptr, left.ptr)
          merged
        }
        
        // 为了找到最新的幸存者，我们需要从 allocPtr-1 倒着找。
        // 一个更清晰的方式是创建一个 Vec，其顺序就是遍历的顺序，然后规约它。
        val searchOrderResults = Vec.tabulate(lqDepth) { i =>
            val readPtr = storage.allocPtrReg - 1 - i // 从新到旧
            val result = FindLastResult()
            result.found := isKept(readPtr)
            result.ptr   := readPtr
            result
        }
        
        // 合并时，我们总是优先保留左边（更“新”）的结果
        val finalResult = searchOrderResults.reduceBalancedTree((newer, older) => {
            val merged = FindLastResult()
            merged.found := newer.found || older.found
            merged.ptr   := Mux(newer.found, newer.ptr, older.ptr)
            merged
        })
        
        val anyKept = finalResult.found
        val lastKeptSlotPtr = finalResult.ptr


        // --- Step 3: Update pointers and slot states combinationally ---
        
        // Update pointers based on the combinational search result
        when(anyKept) {
          allocPtrNext := lastKeptSlotPtr + 1
        } otherwise {
          // Nothing is kept, the queue becomes empty.
          allocPtrNext := storage.freePtrReg
        }
        
        // The wrap mode must be re-calculated based on the new allocPtr and OLD freePtr.
        // This logic is now safe and combinational.
        val newAllocPtr = Mux(anyKept, lastKeptSlotPtr + 1, storage.freePtrReg)
        
        when (newAllocPtr === storage.freePtrReg) {
          wrapModeRegNext := False // Became empty
        } .elsewhen (storage.allocPtrReg > storage.freePtrReg) { // Was not wrapped
          wrapModeRegNext := newAllocPtr < storage.freePtrReg
        } .otherwise { // Was wrapped or full
          wrapModeRegNext := newAllocPtr < storage.freePtrReg || (newAllocPtr === storage.freePtrReg && anyKept)
        }

        // Update the state of individual slots
        for (i <- 0 until lqDepth) {
          val slot = storage.slotsReg(i)
          val isInFlight = storage.isIndexInFlight(U(i))
          when(isInFlight && slot.valid) {
            val shouldFlushThisSlot = // Re-calculate condition for clarity, synthesizer will optimize
              Mux(
                robFlushPort.payload.reason === FlushReason.FULL_FLUSH,
                True,
                helper_functions.isNewerOrSame(slot.robPtr, robFlushPort.payload.targetRobPtr)
              )

            when(shouldFlushThisSlot) {
              when(slot.isWaitingForMemRsp) {
                // Mark for draining
                slotsNext(i).isFlushed := True
                if(enableLog) debug(L"[LQ-Ring] FLUSH_DRAIN: slotIdx=${i} (robPtr=${slot.robPtr}) marked for draining.")
              } otherwise {
                // Invalidate immediately
                slotsNext(i).valid := False
                if(enableLog) debug(L"[LQ-Ring] FLUSH_INVALIDATE: slotIdx=${i} (robPtr=${slot.robPtr}) invalidated.")
              }
            }
          }
        }
        
        if (enableLog)
          notice(
            L"[LQ-Ring] FLUSH_POINTER_ROLLBACK: anyKept=${anyKept}, lastKeptSlotPtr=${lastKeptSlotPtr}. New allocPtr=${allocPtrNext}"
          )
      }
    }
    // =================================================================
    //  Head-of-Queue Processing & Memory Disambiguation
    // =================================================================
    val head_processing_logic = new Area {
      val headSlot = storage.slotsReg(storage.freePtrReg)
      val headSlotNext = slotsNext(storage.freePtrReg)
      val canProcessHead = !storage.isEmpty && headSlot.valid && !doFlush

      // =========================================================
      //  重写查询和响应逻辑，以匹配 Flow 接口
      // =========================================================

      // --- Stage 1: Check for Early Exceptions (No change here) ---
      val hasEarlyEx = headSlot.hasEarlyException
      when(canProcessHead && hasEarlyEx && !headSlot.isLoaded && !headSlot.isWritingBack) {
        headSlotNext.isLoaded := True
        headSlotNext.hasFault := True
        headSlotNext.exceptionCode := headSlot.earlyExceptionCode
        if (enableLog)
          debug(L"[LQ-Ring] HEAD_EARLY_EX: robPtr=${headSlot.robPtr} marked as loaded due to early exception.")
      }

      // --- Stage 2 & 3 Combined: Combinational Query and Response Handling ---
      
      // An instruction is ready to query the SB if it's the head, has no exception, and is in its initial state.
      val isReadyForSbQuery = canProcessHead && !headSlot.isFlushed && !headSlot.hasEarlyException &&
        !headSlot.isQueryingSB && !headSlot.isStalledBySB && // isQueryingSB/isStalledBySB might be removable
        !headSlot.isReadyForMem && !headSlot.isWaitingForMemRsp &&
        !headSlot.isLoaded && !headSlot.isWritingBack
      if(enableLog) debug(L"[LQ-Ring] HEAD_READY_FOR_SB_QUERY: robPtr=${headSlot.robPtr} is ${isReadyForSbQuery}" :+
      L" due to canProcessHead=${canProcessHead}, hasEarlyEx=${hasEarlyEx}, isFlushed=${headSlot.isFlushed}, " :+
      L"isQueryingSB=${headSlot.isQueryingSB}, isStalledBySB=${headSlot.isStalledBySB}, isReadyForMem=${headSlot.isReadyForMem}, " :+
      L"isWaitingForMemRsp=${headSlot.isWaitingForMemRsp}, isLoaded=${headSlot.isLoaded}, isWritingBack=${headSlot.isWritingBack}")

      // Drive the query Flow
      hw.sbQueryPort.cmd.valid := isReadyForSbQuery
      hw.sbQueryPort.cmd.payload.address := headSlot.address
      hw.sbQueryPort.cmd.payload.size := headSlot.size
      hw.sbQueryPort.cmd.payload.robPtr := headSlot.robPtr
      hw.sbQueryPort.cmd.payload.isCoherent := headSlot.isCoherent
      hw.sbQueryPort.cmd.payload.isIO := headSlot.isIO
      
      // Get the response combinationally
      val sbRsp = hw.sbQueryPort.rsp

      // Process the response in the SAME cycle when the query is valid
      when(isReadyForSbQuery) {
        if (enableLog && verbose)
          debug(L"[LQ-Ring] HEAD_QUERY_SB: Sending query for robPtr=${headSlot.robPtr}, addr=${headSlot.address}")

        if (enableLog)
          debug(
            L"[LQ-Ring] HEAD_SB_RSP: Received SB response for robPtr=${headSlot.robPtr}. Hit=${sbRsp.hit}, Stall=${sbRsp.olderStoreDataNotReady}, UnkAddr=${sbRsp.olderStoreHasUnknownAddress}"
          )

        when(sbRsp.hit) {
          // HIT: Data was forwarded from the Store Buffer. The load is complete.
          headSlotNext.isLoaded := True
          headSlotNext.data := sbRsp.data
          headSlotNext.hasFault := False
          if (enableLog)
            debug(L"[LQ-Ring] HEAD_SB_HIT: robPtr=${headSlot.robPtr} completed via forwarding. Data=${sbRsp.data}")

        }.elsewhen(sbRsp.olderStoreDataNotReady || sbRsp.olderStoreHasUnknownAddress) {
          // STALL: We must wait. To prevent re-querying every cycle, we set a stall flag.
          // This is one of the few state changes needed.
          headSlotNext.isStalledBySB := True
          if (enableLog)
            debug(L"[LQ-Ring] HEAD_SB_STALL: robPtr=${headSlot.robPtr} is stalled by SB dependency.")

        }.otherwise { // MISS
          // MISS: Clear to access the memory system.
          headSlotNext.isReadyForMem := True
          if (enableLog)
            debug(L"[LQ-Ring] HEAD_SB_MISS: robPtr=${headSlot.robPtr} is now ready for memory access.")
        }
      }

      // --- Stage 4: Handle Stall Recovery (No change, but now it's more important) ---
      // This logic will clear the stall flag, allowing a re-query on the next cycle.
      when(canProcessHead && headSlot.isStalledBySB) {
        headSlotNext.isStalledBySB := False
        if (enableLog && verbose)
          debug(L"[LQ-Ring] HEAD_STALL_RECOVER: robPtr=${headSlot.robPtr} will retry SB query.")
      }
    }

    // =================================================================
    //  Memory Access, Completion & Pop Logic
    // =================================================================
    val mem_access_completion_logic = new Area {
      // --- Stage 5: Memory System Interaction (Send Request) ---
      // We only process the head of the queue for sending requests to simplify logic.
      // A more complex design could arbitrate among all ready entries.
      val headSlot = storage.slotsReg(storage.freePtrReg)
      val headSlotNext = slotsNext(storage.freePtrReg)
      val canProcessHead = !storage.isEmpty && headSlot.valid && !doFlush

      // Only send to memory if not flushed and ready
      val canSendToMem = canProcessHead && !headSlot.isFlushed && headSlot.isReadyForMem && !headSlot.isWaitingForMemRsp

      // IO Path
      val ioCmdFired = hw.ioReadChannel
        .map { ch =>
          ch.cmd.valid := canSendToMem && headSlot.isIO
          ch.cmd.address := headSlot.address
          // Use the slot's unique txid for the bus transaction ID
          if (ioConfig.get.useId) {
            require(headSlot.txid.getWidth <= ch.cmd.id.getWidth, "txid width exceeds bus ID width")
            ch.cmd.id := headSlot.txid.resized
          }

          when(ch.cmd.fire) {
            headSlotNext.isReadyForMem := False // Consume the ready signal
            headSlotNext.isWaitingForMemRsp := True
            if (enableLog)
              debug(
                L"[LQ-Ring] IO_SEND: Sent IO read for robPtr=${headSlot.robPtr}, addr=${headSlot.address}, txid=${headSlot.txid}"
              )
          }
          ch.cmd.fire
        }
        .getOrElse(False)

      // DCache Path (placeholder for future implementation)
      // dcachedisable val dcacheCmdFired = hw.dCacheLoadPort.map { ch =>
      // dcachedisable   ch.cmd.valid := canSendToMem && !headSlot.isIO
      // dcachedisable   // ... set command fields ...
      // dcachedisable   when(ch.cmd.fire) { /* update state */ }
      // dcachedisable   ch.cmd.fire
      // dcachedisable }.getOrElse(False)

      // --- Stage 6: Handle Memory System Response (Out-of-Order) ---
      // This logic must search the entire LQ for a matching transaction ID.

      // IO Response Handling
      // 用于在“查找”和“写入”阶段之间暂存内存响应的流水线寄存器
      val responsePipe = Reg(new Bundle {
        val valid = Bool() // 管道中是否有正在处理的响应？
        val matchingSlotIndex = UInt(addressWidth bits) // 找到的槽位索引
        val txid = UInt(pipelineConfig.transactionIdWidth bits) // 用于匹配的ID (验证的关键！)

        // 来自响应的实际数据
        val data = Bits(pipelineConfig.dataWidth)
        val hasFault = Bool()
        val exceptionCode = UInt(8 bits)
      })
      responsePipe.valid.init(False)
      responsePipe.matchingSlotIndex.init(0)
      responsePipe.txid.init(0)
      responsePipe.data.init(0)
      responsePipe.hasFault.init(False)
      responsePipe.exceptionCode.init(0)

      // 默认情况下，在下一周期清空流水线寄存器
      responsePipe.valid := False // Will be set to true if a new response is captured

      // =================================================================
      //  第一级：查找内存响应并锁存到 `responsePipe`
      // =================================================================
      hw.ioReadChannel.foreach { ch =>
        val ioRsp = ch.rsp

        // 1. Find the matching slot using a one-hot vector (robust approach)
        val matchOH = Vec(Bool(), lqDepth)
        for (i <- 0 until lqDepth) {
          val slot = storage.slotsReg(i)
          // Match criteria: in flight, valid, is IO, waiting for response, and matching txid
          matchOH(i) := storage.isIndexInFlight(U(i)) &&
                        slot.valid &&
                        slot.isIO &&
                        slot.isWaitingForMemRsp && // <<-- 必须有这一行！
                        slot.txid === ioRsp.payload.id
        }
        val responseMatchesSlot = CountOne(matchOH) === 1
        val matchingSlotIndex = OHToUInt(matchOH)

        // Drive ready signal based on whether we found a unique match AND the pipe is not already full
        ioRsp.ready := responseMatchesSlot && !responsePipe.valid

        // Assert that we don't have multiple matches (critical bug if it happens)
        when(ioRsp.valid) {
          assert(
            CountOne(matchOH) <= 1,
            "IO response matches multiple LQ slots simultaneously, this is a critical bug!"
          )
        }

        // 2. Update the state of the matched slot when response is consumed
        when(ioRsp.fire) {
          // 将所有必要数据锁存到流水线寄存器中，供第二级使用
          responsePipe.valid := True
          responsePipe.matchingSlotIndex := matchingSlotIndex
          responsePipe.txid := ioRsp.payload.id // 锁存ID用于验证！
          responsePipe.data := ioRsp.payload.data
          responsePipe.hasFault := ioRsp.payload.error
          responsePipe.exceptionCode := ExceptionCode.LOAD_ACCESS_FAULT

          if (enableLog) {
            when(ioRsp.payload.error) {
              notice(
                L"[LQ-Ring] IO_RSP_CAPTURE_ERROR: Captured IO error for txid=${ioRsp.payload.id}, matched to slotIdx=${matchingSlotIndex}"
              )
            }.otherwise {
              debug(
                L"[LQ-Ring] IO_RSP_CAPTURE_SUCCESS: Captured IO data for txid=${ioRsp.payload.id}, matched to slotIdx=${matchingSlotIndex}. Data=${ioRsp.payload.data}"
              )
            }
          }
        }
      }

      // =================================================================
      //  第二级：验证锁存的响应并写入 `slotsNext`
      // =================================================================
      when(responsePipe.valid) {
        val slotIdx = responsePipe.matchingSlotIndex
        val currentSlotState = storage.slotsReg(slotIdx) // 读取当前状态用于验证

        // 关键的验证检查
        val isValid = currentSlotState.valid &&
                      currentSlotState.isWaitingForMemRsp &&
                      currentSlotState.txid === responsePipe.txid

        when(isValid) {
          // 验证通过：更新 'slotsNext'
          val matchedSlotNext = slotsNext(slotIdx)
          matchedSlotNext.isWaitingForMemRsp := False
          matchedSlotNext.isLoaded := True
          matchedSlotNext.data := responsePipe.data
          matchedSlotNext.hasFault := responsePipe.hasFault
          matchedSlotNext.exceptionCode := responsePipe.exceptionCode
          // Note: isFlushed state is preserved here, it's not cleared.

          if (enableLog) {
            when(responsePipe.hasFault) {
              notice(
                L"[LQ-Ring] IO_RSP_PROCESS_ERROR: Processed IO error for txid=${responsePipe.txid}, matched to slotIdx=${slotIdx} (robPtr=${currentSlotState.robPtr})"
              )
            }.otherwise {
              debug(
                L"[LQ-Ring] IO_RSP_PROCESS_SUCCESS: Processed IO data for txid=${responsePipe.txid}, matched to slotIdx=${slotIdx} (robPtr=${currentSlotState.robPtr}). Data=${responsePipe.data}"
              )
            }
          }
        } otherwise {
          // 验证失败：指令在上一个周期被冲刷或弹出了。
          // 我们手里的响应现在是给一个“幽灵”指令的。
          // 正确的操作是：简单地丢弃这个响应。
          if(enableLog) notice(L"[LQ-Ring] IO_RSP_DISCARD: 第二阶段丢弃了过期或无效的txid=${responsePipe.txid}的响应")
        }
      }


      // DCache Response Handling (placeholder)
      // dcachedisable when(hw.dCacheLoadPort.rsp.valid) { /* ... similar matching logic ... */ }

      // --- Stage 7 & 8: Two-Phase Write-back and Pop Logic ---

      // Default connections for write-back ports
      hw.robLoadWritebackPort.setDefault()
      hw.prfWritePort.valid := False
      hw.prfWritePort.address.assignDontCare()
      hw.prfWritePort.data.assignDontCare()
      hw.wakeupPort.valid := False
      hw.wakeupPort.payload.physRegIdx.assignDontCare()
      doPop := False // Default to false, driven true by writeback logic

      // --- Data Alignment and Sign Extension ---
      val rawData = headSlot.data
      val addrLow = headSlot.address(1 downto 0)
      val shiftedData = (rawData >> (addrLow << 3)).asBits

      val extendedData = Bits(pipelineConfig.dataWidth)
      switch(headSlot.size) {
        is(MemAccessSize.B) {
          extendedData := Mux(
            headSlot.isSignedLoad,
            S(shiftedData(7 downto 0)).resize(pipelineConfig.dataWidth).asBits,
            U(shiftedData(7 downto 0)).resize(pipelineConfig.dataWidth).asBits
          )
        }
        is(MemAccessSize.H) {
          extendedData := Mux(
            headSlot.isSignedLoad,
            S(shiftedData(15 downto 0)).resize(pipelineConfig.dataWidth).asBits,
            U(shiftedData(15 downto 0)).resize(pipelineConfig.dataWidth).asBits
          )
        }
        default { // Word
          extendedData := shiftedData
        }
      }

      // --- Stage 7: Start Write-back / Drain Logic ---
      // Condition: Head instruction has loaded data, but has not yet entered the writeback stage.
      val canStartWriteback = canProcessHead && headSlot.isLoaded && !headSlot.isWritingBack

      when(canStartWriteback) {
        when(headSlot.isFlushed) {
          // This entry was flushed. Now that its memory response is here, we can drain it.
          if (enableLog)
            notice(L"[LQ-Ring] DRAIN_POP: Popping flushed robPtr=${headSlot.robPtr} after memory response received.")

          doPop := True // Perform the pop operation.
          headSlotNext.valid := False // Invalidate the popped slot
          freePtrNext := storage.freePtrReg + 1
          // No PRF write, no wakeup, no ROB notification for flushed entries.
        } otherwise {
          // Not flushed, proceed with normal write-back
          if (enableLog)
            debug(L"[LQ-Ring] WB_START: robPtr=${headSlot.robPtr} is starting writeback to PRF.")

          // 1. Advance state to isWritingBack, preparing for next cycle's operation.
          headSlotNext.isWritingBack := True

          // 2. Drive PRF write port and Wakeup port this cycle (only if no fault).
          //    Data will be latched by PRF on the next clock edge.
          when(!headSlot.hasFault) {
            hw.prfWritePort.valid := True
            hw.prfWritePort.address := headSlot.pdest
            hw.prfWritePort.data := extendedData

            hw.wakeupPort.valid := True
            hw.wakeupPort.payload.physRegIdx := headSlot.pdest

            if (enableLog)
              debug(
                L"[LQ-Ring] WB: robPtr=${headSlot.robPtr} (pc=${headSlot.pc}) writing pdest=${headSlot.pdest} with data=${extendedData}, waking up dependents."
              )
          } otherwise {
            if (enableLog)
              notice(L"[LQ-Ring] WB_FAULT_PRF_SKIP: robPtr=${headSlot.robPtr} has fault, skipping PRF write.")
          }
        }
      }

      // --- Stage 8: Complete Write-back (Drive ROB) and Pop ---
      // Condition: Head instruction is currently in the writeback stage (set by previous cycle).
      val isCurrentlyWritingBack = canProcessHead && headSlot.isWritingBack
      when(isCurrentlyWritingBack) {
        // This logic should only be reached by non-flushed instructions that started writeback in Stage 7.
        assert(!headSlot.isFlushed, "A flushed instruction should not reach the final writeback stage!")

        if (enableLog)
          debug(L"[LQ-Ring] POP & ROB_WB: Popping robPtr=${headSlot.robPtr} and notifying ROB.")

        // 1. Drive ROB writeback port this cycle.
        //    At this point, data in PRF has already been updated. It's safe for ROB to set 'done'.
        hw.robLoadWritebackPort.fire := True
        hw.robLoadWritebackPort.robPtr := headSlot.robPtr
        hw.robLoadWritebackPort.exceptionOccurred := headSlot.hasFault
        hw.robLoadWritebackPort.exceptionCodeIn := headSlot.exceptionCode
        hw.robLoadWritebackPort.result := extendedData

        // 2. Perform the pop operation.
        doPop := True
        headSlotNext.valid := False // Invalidate the popped slot
        freePtrNext := storage.freePtrReg + 1
      }
    }

    // =================================================================
    //  Final State Update
    // =================================================================
    val state_update = new Area {
      // Regular wrapModeReg Update (when no flush)
      when(!doFlush) {
        when(doPush =/= doPop) {
          when(doPush) { // Queue grows
            when(allocPtrNext === storage.freePtrReg) { // Use old freePtr for comparison
              wrapModeRegNext := True
            }
          }.otherwise { // Queue shrinks (doPop is true)
            when(freePtrNext === storage.allocPtrReg) { // Use old allocPtr for comparison
              wrapModeRegNext := False
            }
          }
        }
      }

      // Latch all 'Next' signals into the registers
      storage.allocPtrReg := allocPtrNext
      storage.freePtrReg := freePtrNext
      storage.wrapModeReg := wrapModeRegNext
      storage.slotsReg := slotsNext

      if (enableLog && verbose) {
        debug(
          L"[LQ-Ring] State Update: allocPtrReg=${storage.allocPtrReg} -> ${allocPtrNext}, freePtrReg=${storage.freePtrReg} -> ${freePtrNext}, wrapModeReg=${storage.wrapModeReg} -> ${wrapModeRegNext}"
        )
        for (i <- 0 until lqDepth) {
          // 只打印有效或即将有效的槽位，避免刷屏
          when(storage.slotsReg(i).valid || slotsNext(i).valid) {
            val slot = slotsNext(i) // 打印下一周期的状态，因为它反映了本周期的所有决策
            debug(
              L"[LQ-Ring-Debug] Slot[${i}]: " :+
                L"valid=${slot.valid}, " :+
                L"robPtr=${slot.robPtr}, " :+
                L"pc=${slot.pc}, " :+
                L"addr=${slot.address}, " :+
                L"pdest=${slot.pdest}, " :+
                L"txid=${slot.txid}, " :+
                L"isFlushed=${slot.isFlushed}, " :+ // NEW: Log isFlushed
                L"isLoaded=${slot.isLoaded}, " :+
                L"isWritingBack=${slot.isWritingBack}, " :+
                L"isStalledBySB=${slot.isStalledBySB}, " :+
                L"isReadyForMem=${slot.isReadyForMem}, " :+
                L"isWaitingForMemRsp=${slot.isWaitingForMemRsp}, " :+
                L"hasEarlyEx=${slot.hasEarlyException}, " :+
                L"hasFault=${slot.hasFault}"
            )
          }
        }
      }

      if(enableLog) debug("...")

      // --- Resource Release ---
      hw.robServiceInst.release()
      hw.prfServiceInst.release()
      hw.storeBufferServiceInst.release()
      hw.wakeupServiceInst.release()
      hw.sgmbServiceOpt.foreach(_.release())
      // hw.dcacheServiceInst.release()
    }
  }
}
