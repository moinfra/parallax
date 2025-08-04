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

  /** 初始状态/空闲态: 准备好发起依赖检查 (例如查询SQ)。*/
  val isReadyForCheck = Bool()

  /**
   * 正在查询依赖: 已向SQ发出查询，正在等待当周期的组合逻辑响应。
   * 这个状态只在发起查询的那个周期内为真。
   */
  val isQueryingDeps = Bool()

  /**
   * 依赖检查完毕: 上一周期已完成查询，结果已锁存。
   * 在这个状态下，我们将根据锁存的结果做出最终行动。
   */
  val isCheckCompleted = Bool()

  /** 锁存的Store Buffer查询结果：是否命中 */
  val latchedSbHit = Bool()
  /** 锁存的Store Buffer查询结果：转发的数据 */
  val latchedSbData = Bits(pCfg.dataWidth)
  /** 锁存的Store Buffer查询结果：是否需要暂停 */
  val latchedSbStall = Bool()

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

    // 默认情况下，新分配的槽位处于 isReadyForCheck 状态
    isReadyForCheck := True
    isQueryingDeps := False
    isCheckCompleted := False

    latchedSbHit.assignDontCare()
    latchedSbData.assignDontCare()
    latchedSbStall.assignDontCare()

    // 确保与原有状态机的互斥
    isStalledBySB := False // 这个状态仍然需要，但由新逻辑驱动
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

    // 初始化状态
    isReadyForCheck := True // 新指令从这里开始
    isQueryingDeps := False
    isCheckCompleted := False

    latchedSbHit.assignDontCare()
    latchedSbData.assignDontCare()
    latchedSbStall.assignDontCare()

    // Initialize all mutable state flags to False
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
      L"state: flushed=${isFlushed}, earlyEx=${hasEarlyException}, readyCheck=${isReadyForCheck}, queryDeps=${isQueryingDeps}, checkCpl=${isCheckCompleted}, stallSB=${isStalledBySB}, readyMem=${isReadyForMem}, waitRsp=${isWaitingForMemRsp}, loaded=${isLoaded}, writingBack=${isWritingBack})"
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
    //  Flush Pipeline Registers
    // =================================================================
    val flush_pipeline = new Area {
        /** 标记一个冲刷正在流水线的“应用阶段” */
        val isApplying = Reg(Bool()).init(False)

        /** 锁存的冲刷计算结果 */
        val latched_allocPtrNext = Reg(UInt(addressWidth bits)).init(0)
        val latched_wrapModeNext = Reg(Bool()).init(False)
        
        /** 
         * 每个槽位在冲刷后的新状态。我们用一个Vec来锁存每个槽位的最终命运。
         * 这比只锁存指针更强大，可以处理更复杂的逻辑，比如标记 draining。
         */
        val latched_slotsNext = Vec.fill(lqDepth)(Reg(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))

        for (i <- 0 until lqDepth) {
            latched_slotsNext(i).init(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
        }
    }


    // =================================================================
    //  Next-state Logic (Combinational Views) - Regular Operations
    // =================================================================
    val slotsNext = CombInit(storage.slotsReg)
    val allocPtrNext = CombInit(storage.allocPtrReg)
    val freePtrNext = CombInit(storage.freePtrReg)
    val wrapModeRegNext = CombInit(storage.wrapModeReg)

    val doPush = Bool()
    val doPop = Bool() // Will be driven in Part 3
    val doFlush_rob = hw.robServiceInst.doRobFlush().valid // Original ROB flush signal


    // =================================================================
    //  Push Logic (Allocation)
    // =================================================================
    val push_logic = new Area {
      // Push is only allowed if not full AND no flush is in progress (either calculation or application)
      val canPush = !storage.isFull && !doFlush_rob && !flush_pipeline.isApplying
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
    //  Flush Logic - Calculation Stage (Cycle N)
    // =================================================================
    val flush_logic_calc = new Area {
      val robFlushPort = hw.robServiceInst.doRobFlush()
      val doFlush = robFlushPort.valid // This is the input signal to the flush pipeline

      // Combinational outputs for the flush pipeline registers
      val flush_allocPtrNext = UInt(addressWidth bits)
      val flush_wrapModeNext = Bool()
      val flush_slotsNext = Vec.fill(lqDepth)(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams))

      // Default assignments when no flush is active (important for synthesis)
      flush_allocPtrNext := storage.allocPtrReg
      flush_wrapModeNext := storage.wrapModeReg
      for (i <- 0 until lqDepth) {
        flush_slotsNext(i) := storage.slotsReg(i)
      }


      when(doFlush) {
        if (enableLog)
          notice(L"[LQ-Ring] FLUSH_CALC: received. Reason: ${robFlushPort.payload.reason}, targetRobPtr=${robFlushPort.payload.targetRobPtr}")

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

        // 1. 定义规约操作所需的数据结构
        case class FindLastResult() extends Bundle {
          val found = Bool()
          val ptr = UInt(addressWidth bits)
        }

        // 2. 为了找到最新的幸存者，我们需要从 allocPtr-1 倒着找。
        //    创建一个 Vec，其顺序就是遍历的顺序，然后规约它。
        val searchOrderResults = Vec.tabulate(lqDepth) { i =>
            // 计算当前循环迭代对应的逻辑索引
            val readPtr = (storage.allocPtrReg - 1 - i).resized // 从新到旧
            val result = FindLastResult()
            // 确保只考虑在飞行中的有效槽位
            when(storage.isIndexInFlight(readPtr) && storage.slotsReg(readPtr).valid) {
                result.found := isKept(readPtr)
                result.ptr   := readPtr
            }.otherwise {
                result.found := False
                result.ptr   := readPtr // 默认值，不重要
            }
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


        // --- Step 3: Calculate new pointers and slot states combinationally ---

        // Update allocPtr based on the combinational search result
        when(anyKept) {
          flush_allocPtrNext := lastKeptSlotPtr + 1
        } otherwise {
          // Nothing is kept, the queue becomes empty.
          flush_allocPtrNext := storage.freePtrReg // Use current freePtr for empty state
        }

        // The wrap mode must be re-calculated based on the new allocPtr and OLD freePtr.
        // This logic is now safe and combinational.
        val newAllocPtr = Mux(anyKept, lastKeptSlotPtr + 1, storage.freePtrReg)

        when (newAllocPtr === storage.freePtrReg) {
          flush_wrapModeNext := anyKept // If something is kept and pointers meet, it's full. Otherwise, empty.
        } .elsewhen (storage.allocPtrReg > storage.freePtrReg) { // Was not wrapped
          flush_wrapModeNext := newAllocPtr < storage.freePtrReg
        } .otherwise { // Was wrapped or full
          flush_wrapModeNext := newAllocPtr < storage.freePtrReg || (newAllocPtr === storage.freePtrReg && anyKept)
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
                flush_slotsNext(i).isFlushed := True
                if(enableLog) debug(L"[LQ-Ring] FLUSH_DRAIN: slotIdx=${i} (robPtr=${slot.robPtr}) marked for draining.")
              } otherwise {
                // Invalidate immediately
                flush_slotsNext(i).valid := False
                if(enableLog) debug(L"[LQ-Ring] FLUSH_INVALIDATE: slotIdx=${i} (robPtr=${slot.robPtr}) invalidated.")
              }
            }
          }
        }

        if (enableLog)
          notice(
            L"[LQ-Ring] FLUSH_CALC_RESULT: anyKept=${anyKept}, lastKeptSlotPtr=${lastKeptSlotPtr}. Calculated allocPtr=${flush_allocPtrNext}, wrapMode=${flush_wrapModeNext}"
          )
      }
    }


    // =================================================================
    //  Head-of-Queue Processing & Memory Disambiguation
    // =================================================================
    val head_proc_pipe = new Area {
        val valid = Reg(Bool()).init(False) // 标记流水线第二级是否有效
        val index = Reg(UInt(addressWidth bits)) // 锁存队头索引
        
        // 锁存SQ查询结果
        val latchedSbHit = Reg(Bool())
        val latchedSbData = Reg(Bits(32 bits))
        val latchedSbStall = Reg(Bool())
    }
    val head_processing_logic = new Area {
      val headSlot = storage.slotsReg(storage.freePtrReg)
      val headSlotNext = slotsNext(storage.freePtrReg)
      // Added !headSlot.isFlushed to canProcessHead for robustness
      // Also, block if flush is applying, as storage.slotsReg might be in a transient state
      val canProcessHead = !storage.isEmpty && headSlot.valid && !doFlush_rob && !headSlot.isFlushed && !flush_pipeline.isApplying

      // =========================================================
      // === 微流水线 阶段1: 发起查询 (在 isReadyForCheck 状态) ===
      // =========================================================
      // 条件：是队头，且处于可以发起查询的状态
      val canStartQuery = canProcessHead && headSlot.isReadyForCheck && !headSlot.hasEarlyException

      // 发送查询命令给SQ
      hw.sbQueryPort.cmd.valid := canStartQuery
      hw.sbQueryPort.cmd.payload.address := headSlot.address
      hw.sbQueryPort.cmd.payload.size := headSlot.size
      hw.sbQueryPort.cmd.payload.robPtr := headSlot.robPtr
      hw.sbQueryPort.cmd.payload.isCoherent := headSlot.isCoherent
      hw.sbQueryPort.cmd.payload.isIO := headSlot.isIO

      // 如果查询发出，则进入 isQueryingDeps 状态
      when(canStartQuery) {
          headSlotNext.isReadyForCheck := False
          headSlotNext.isQueryingDeps := True
          if (enableLog) debug(L"[LQ-HEAD-S1] Firing Query: robPtr=${headSlot.robPtr}")
      }

      // =========================================================
      // === 微流水线 阶段2: 接收并锁存响应 (在 isQueryingDeps 状态) ===
      // =========================================================
      val sbRsp = hw.sbQueryPort.rsp

      // 条件：是队头，且在当前周期发出了查询
      when(canProcessHead && headSlot.isQueryingDeps) {
        if (enableLog) debug(L"[LQ-HEAD-S2] Latched Rsp: robPtr=${headSlot.robPtr}, sbHit=${sbRsp.hit}, sbStall=${sbRsp.olderStoreDataNotReady}")

        // 无论响应如何，都退出 isQueryingDeps 状态
        headSlotNext.isQueryingDeps := False

        // 将SQ的响应锁存到 headSlotNext 的新字段中
        headSlotNext.latchedSbHit   := sbRsp.hit
        headSlotNext.latchedSbStall := sbRsp.olderStoreDataNotReady || sbRsp.olderStoreHasUnknownAddress
        headSlotNext.latchedSbData := sbRsp.data // 无条件锁存，切断组合路径

        // 进入下一阶段
        headSlotNext.isCheckCompleted := True
      }

      // =========================================================
      // === 微流水线 阶段3: 应用锁存的结果 (在 isCheckCompleted 状态) ===
      // =========================================================
      // 条件：是队头，且依赖检查的结果已经锁存
      when(canProcessHead && headSlot.isCheckCompleted) {
        if (enableLog) debug(L"[LQ-HEAD-S3] Applying Latched Rsp: robPtr=${headSlot.robPtr}")

        // 消耗掉这个状态
        headSlotNext.isCheckCompleted := False

        // 根据锁存的结果，做出最终决策
        when(headSlot.latchedSbStall) {
          // 需要暂停 -> 进入 Stall 状态，等待后续重试
          headSlotNext.isStalledBySB := True
          if (enableLog) debug(L"  -> Decision: STALL")
        }.elsewhen(headSlot.latchedSbHit) {
          // 命中 -> 加载完成，数据来自转发
          headSlotNext.isLoaded := True
          headSlotNext.data := headSlot.latchedSbData // 使用锁存的数据
          headSlotNext.hasFault := False
          if (enableLog) debug(L"  -> Decision: FORWARDED")
        } otherwise {
          // 未命中 -> 可以安全地访问内存
          headSlotNext.isReadyForMem := True
          if (enableLog) debug(L"  -> Decision: READY FOR MEMORY")
        }
      }

      // --- 处理 Stall 恢复的逻辑 ---
      // 当指令处于Stall状态时，它会在下一拍被清除，并回到 isReadyForCheck 状态，从而自动重试查询
      when(canProcessHead && headSlot.isStalledBySB) {
        headSlotNext.isStalledBySB := False
        headSlotNext.isReadyForCheck := True // 回到初始状态以重试
        if (enableLog) debug(L"[LQ-HEAD-RECOVER] robPtr=${headSlot.robPtr} recovering from SB stall, will retry.")
      }

      // --- 处理 Early Exception 的逻辑 ---
      // 这个逻辑优先级很高，如果成立，直接跳过所有查询
      when(canProcessHead && headSlot.hasEarlyException) {
          when(headSlot.isReadyForCheck || headSlot.isQueryingDeps || headSlot.isCheckCompleted){
              headSlotNext.isReadyForCheck := False
              headSlotNext.isQueryingDeps := False
              headSlotNext.isCheckCompleted := False
              headSlotNext.isLoaded := True
              headSlotNext.hasFault := True
              headSlotNext.exceptionCode := headSlot.earlyExceptionCode
              if (enableLog) debug(L"[LQ-HEAD-EARLY-EX] robPtr=${headSlot.robPtr} has early exception.")
          }
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
      // Block if flush is applying
      val canProcessHead = !storage.isEmpty && headSlot.valid && !doFlush_rob && !flush_pipeline.isApplying

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
        // Also, block if flush is applying, to prevent writing to slots that are being flushed
        ioRsp.ready := responseMatchesSlot && !responsePipe.valid && !flush_pipeline.isApplying

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
      // This stage should also be blocked if a flush is applying, to avoid conflicts
      when(responsePipe.valid && !flush_pipeline.isApplying) {
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
      // Block if flush is applying
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
      // Block if flush is applying
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

    // =========================================================================
    // === 主状态更新 (带优先级的最终赋值) ===
    // =========================================================================
    val state_update = new Area {
      // --- 锁存计算阶段的结果 (周期 N 的结束) ---
      // If a flush request is active, latch the calculated next state for the next cycle.
      flush_pipeline.isApplying := flush_logic_calc.doFlush

      when(flush_logic_calc.doFlush) {
          flush_pipeline.latched_allocPtrNext := flush_logic_calc.flush_allocPtrNext
          flush_pipeline.latched_wrapModeNext := flush_logic_calc.flush_wrapModeNext
          flush_pipeline.latched_slotsNext    := flush_logic_calc.flush_slotsNext
          if (enableLog) notice(L"[LQ-FLUSH-LATCH] Latching flush results for next cycle.")
      }
      
      // --- 根据优先级选择最终的下一状态 ---
      when(flush_pipeline.isApplying) {
          // ---- 应用阶段 (周期 N+1): 冲刷逻辑具有最高优先级 ----
          // 使用上一周期锁存的结果，无条件地更新所有状态寄存器
          storage.allocPtrReg := flush_pipeline.latched_allocPtrNext
          storage.wrapModeReg := flush_pipeline.latched_wrapModeNext
          storage.slotsReg    := flush_pipeline.latched_slotsNext
          
          // 关键点：当冲刷正在应用时，freePtrReg不能改变！
          // 因为冲刷逻辑是基于冲刷发生那一刻的freePtr计算的。
          // 所以我们冻结freePtr。
          storage.freePtrReg  := storage.freePtrReg

          if (enableLog) notice(L"[LQ-FLUSH-APPLY] Applying flushed state. New allocPtr=${storage.allocPtrReg}, wrapMode=${storage.wrapModeReg}")
          
      } .otherwise {
          // ---- 常规操作: Push/Pop ----
          // 只有在没有冲刷应用时，才执行常规的指针和状态更新
          // Regular wrapModeReg Update (when no flush)
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

          storage.allocPtrReg := allocPtrNext // 来自常规push逻辑
          storage.freePtrReg  := freePtrNext  // 来自常规pop逻辑
          storage.wrapModeReg := wrapModeRegNext// 来自常规逻辑
          storage.slotsReg    := slotsNext    // 来自常规逻辑
      }

      if (enableLog && verbose) {
        debug(
          L"[LQ-Ring] State Update: allocPtrReg=${storage.allocPtrReg} -> ${allocPtrNext}, freePtrReg=${storage.freePtrReg} -> ${freePtrNext}, wrapModeReg=${storage.wrapModeReg} -> ${wrapModeRegNext}"
        )
        for (i <- 0 until lqDepth) {
          // 只打印有效或即将有效的槽位，避免刷屏
          when(storage.slotsReg(i).valid || slotsNext(i).valid || flush_pipeline.latched_slotsNext(i).valid) {
            val slot = slotsNext(i) // 打印下一周期的状态，因为它反映了本周期的所有决策
            // If flush is applying, print the latched state as the "next" state
            val effectiveSlot = Mux(flush_pipeline.isApplying, flush_pipeline.latched_slotsNext(i), slotsNext(i))
            debug(
              L"[LQ-Ring-Debug] Slot[${i}]: " :+
                L"valid=${effectiveSlot.valid}, " :+
                L"robPtr=${effectiveSlot.robPtr}, " :+
                L"pc=${effectiveSlot.pc}, " :+
                L"addr=${effectiveSlot.address}, " :+
                L"pdest=${effectiveSlot.pdest}, " :+
                L"txid=${effectiveSlot.txid}, " :+
                L"isFlushed=${effectiveSlot.isFlushed}, " :+ // NEW: Log isFlushed
                L"isReadyForCheck=${effectiveSlot.isReadyForCheck}, " :+
                L"isQueryingDeps=${effectiveSlot.isQueryingDeps}, " :+
                L"isCheckCompleted=${effectiveSlot.isCheckCompleted}, " :+
                L"latchedSbHit=${effectiveSlot.latchedSbHit}, " :+
                L"latchedSbStall=${effectiveSlot.latchedSbStall}, " :+
                L"isLoaded=${effectiveSlot.isLoaded}, " :+
                L"isWritingBack=${effectiveSlot.isWritingBack}, " :+
                L"isStalledBySB=${effectiveSlot.isStalledBySB}, " :+
                L"isReadyForMem=${effectiveSlot.isReadyForMem}, " :+
                L"isWaitingForMemRsp=${effectiveSlot.isWaitingForMemRsp}, " :+
                L"hasEarlyEx=${effectiveSlot.hasEarlyException}, " :+
                L"hasFault=${effectiveSlot.hasFault}"
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
