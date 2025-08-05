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

  /**
   * 标记该指令正在队头处理微流水线中进行依赖检查。
   * 当它为 True 时，该指令不能再次发起新的查询，直到流程结束（完成/暂停/进入内存访问）。
   */
  val isBusyChecking = Bool()


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
    isBusyChecking := False

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
    isBusyChecking := False // 新指令从这里开始

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
      L"state: flushed=${isFlushed}, earlyEx=${hasEarlyException}, busyCheck=${isBusyChecking}, stallSB=${isStalledBySB}, readyMem=${isReadyForMem}, waitRsp=${isWaitingForMemRsp}, loaded=${isLoaded}, writingBack=${isWritingBack})"
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

  case class FindLastResult() extends Bundle {

    val found = Bool()
    val ptr = UInt(log2Up(lqDepth) bits)
  }


  // --- Service Interface Implementation ---
  private val pushPort = Stream(LoadQueuePushCmd(pipelineConfig, lsuConfig))
  override def getPushPort(): Stream[LoadQueuePushCmd] = {
    pushPort
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
    // val pushCmd = StreamArbiterFactory.roundRobin.on(pushPorts)
    val pushCmd = pushPort

    val nextTxid = RegInit(U(0, pipelineConfig.transactionIdWidth bits))
    val addressWidth = log2Up(lqDepth)

    // =========================================================================
    // === 队头处理微流水线 (Head Processing Mini-Pipeline) ===
    // =========================================================================
    val head_proc_pipe = new Area {
        // --- S1 -> S2 的流水线寄存器 ---
        // 这一级负责锁存从LQ队头读取的、发起查询所需的全部信息。
        val s2_pipe = new Area {
            val validReg     = Reg(Bool()).init(False)
            val indexReg     = Reg(UInt(addressWidth bits))
            val robPtrReg    = Reg(UInt(lsuConfig.robPtrWidth))
            val addressReg   = Reg(UInt(lsuConfig.pcWidth)) // **关键：锁存地址**
            val sizeReg      = Reg(MemAccessSize())
            val isCoherentReg = Reg(Bool())
            val isIoReg       = Reg(Bool())
        }

        // --- S2 -> S3 的流水线寄存器 ---
        // 这一级负责锁存SQ查询的完整结果。
        val s3_pipe = new Area {
            val validReg     = Reg(Bool()).init(False)
            val indexReg     = Reg(UInt(addressWidth bits))
            val robPtrReg    = Reg(UInt(lsuConfig.robPtrWidth)) // 用于验证
            val latchedSbHitReg   = Reg(Bool())
            val latchedSbDataReg  = Reg(Bits(pipelineConfig.dataWidth))
            val latchedSbStallReg = Reg(Bool())
        }
    }


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
    //  Flush Pipeline Registers - NEW 3-STAGE DESIGN
    // =================================================================
    val flush_pipeline = new Area {
        // --- Flush Stage 1 -> 2 Registers ---
        // Captures the flush request and the calculated "keep" status for each slot.
        val s2_pipe = new Area {
            val validReg    = Reg(Bool()).init(False)
            // We need to know which slots survive into the next stage.
            val isKeptVecReg = Reg(Vec.fill(lqDepth)(Bool()))
            // We also need to latch the freePtr at the time of the flush request.
            // This is critical for the wrapMode calculation in S3.
            val freePtrOnFlushReg = Reg(UInt(addressWidth bits))
        }

        // --- Flush Stage 2 -> 3 Registers ---
        // Captures the result of the reduction stage.
        val s3_pipe = new Area {
            val validReg    = Reg(Bool()).init(False)
            // The final result of the reduction: whether any slot was kept, and if so, which one.
            val anyKeptReg  = Reg(Bool())
            val lastKeptSlotPtrReg = Reg(UInt(addressWidth bits))
            // The original freePtr needs to be passed through.
            val freePtrOnFlushReg = Reg(UInt(addressWidth bits))
        }

        // --- Final Application Control ---
        // This signal will be True in the cycle when the final flush state is applied.
        // It replaces the old `isApplying` register.
        val doApplyFlushState = s3_pipe.validReg
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
    val doFlushComb = hw.robServiceInst.doRobFlush().valid // Original ROB flush signal

    // NEW: Centralized flush in progress signal
    val flushInProgress = doFlushComb || flush_pipeline.s2_pipe.validReg || flush_pipeline.s3_pipe.validReg;


    // =================================================================
    //  Push Logic (Allocation)
    // =================================================================
    val push_logic = new Area {
      // Block push if a flush is in any stage of its pipeline.
      // This is a conservative but safe approach.
      val canPush = !storage.isFull && !flushInProgress
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
    //  Flush Logic - NEW 3-STAGE PIPELINE
    // =================================================================
    val flush_logic_calc = new Area {
      val robFlushPort = hw.robServiceInst.doRobFlush()
      val doFlush = robFlushPort.valid

      // --- S1: Calculation Stage ---
      // Reset S2 pipe by default
      flush_pipeline.s2_pipe.validReg := False

      when(doFlush) {
        if (enableLog)
          notice(L"[LQ-FLUSH-S1] CALC: received. Reason: ${robFlushPort.payload.reason}, targetRobPtr=${robFlushPort.payload.targetRobPtr}")

        // Latch for S2
        flush_pipeline.s2_pipe.validReg := True
        flush_pipeline.s2_pipe.freePtrOnFlushReg := storage.freePtrReg // Latch current freePtr

        // Calculate `isKept` for each slot and latch it. This is a simple, parallel operation.
        for (i <- 0 until lqDepth) {
          val slot = storage.slotsReg(i)
          val isInFlight = storage.isIndexInFlight(U(i))
          var shouldKeep = False // Default to not keeping

          when(isInFlight && slot.valid) {
            val shouldFlushThisSlot = helper_functions.isNewerOrSame(slot.robPtr, robFlushPort.payload.targetRobPtr) ||
                                      (robFlushPort.payload.reason === FlushReason.FULL_FLUSH)

            // Keep if it's not meant to be flushed OR if it needs draining.
            shouldKeep = !shouldFlushThisSlot || slot.isWaitingForMemRsp
          }
          flush_pipeline.s2_pipe.isKeptVecReg(i) := shouldKeep
        }
      }

      // --- S2: Reduction Stage ---
      // Reset S3 pipe by default
      flush_pipeline.s3_pipe.validReg := False
      
      when(flush_pipeline.s2_pipe.validReg) {
          if (enableLog) notice(L"[LQ-FLUSH-S2] REDUCE: Finding last kept slot.")
          
          // Latch for S3
          flush_pipeline.s3_pipe.validReg := True
          // Pass through the original freePtr
          flush_pipeline.s3_pipe.freePtrOnFlushReg := flush_pipeline.s2_pipe.freePtrOnFlushReg


          // Perform reduction on the LATCHED `isKept` vector from S1.
          // The search order must be based on the allocPtr AT THE TIME OF THE FLUSH.
          // NOTE: We depend on `storage.allocPtrReg` here. This is safe because `allocPtrReg` only changes
          // on push, and we've blocked pushes while a flush is in progress.
          val searchOrderResults = Vec.tabulate(lqDepth) { i =>
              val readPtr = (storage.allocPtrReg - 1 - i).resized
              val result = FindLastResult()
              // We need to check if the slot was "in flight" at the time of the flush.
              // We can re-calculate this using the latched freePtr and current allocPtr.
              val wasInFlight = Bool()
              val allocPtr = storage.allocPtrReg
              val freePtr = flush_pipeline.s2_pipe.freePtrOnFlushReg
              val wasEmpty = (allocPtr === freePtr) && !storage.wrapModeReg
              val wasFull = (allocPtr === freePtr) && storage.wrapModeReg

              when(wasEmpty) { wasInFlight := False }
              .elsewhen(wasFull) { wasInFlight := True }
              .elsewhen(allocPtr > freePtr) { wasInFlight := (readPtr >= freePtr && readPtr < allocPtr) }
              .otherwise { wasInFlight := (readPtr >= freePtr || readPtr < allocPtr) }

              when(wasInFlight) {
                  result.found := flush_pipeline.s2_pipe.isKeptVecReg(readPtr)
                  result.ptr   := readPtr
              }.otherwise {
                  result.found := False
                  result.ptr   := readPtr
              }
              result
          }

          val finalResult = searchOrderResults.reduceBalancedTree((newer, older) =>
            Mux(newer.found, newer, older)
          )

          // Latch the final reduction result for S3
          flush_pipeline.s3_pipe.anyKeptReg := finalResult.found
          flush_pipeline.s3_pipe.lastKeptSlotPtrReg := finalResult.ptr
      }


      // --- S3: Application Stage (Combinational part) ---
      // This part runs when S3 is valid and generates the final state to be applied.
      val flush_allocPtrNext = UInt(addressWidth bits)
      val flush_wrapModeNext = Bool()
      val flush_slotsNext = Vec.fill(lqDepth)(LoadRingBufferSlot(pipelineConfig, lsuConfig, dCacheParams))

      // Default to current state
      flush_allocPtrNext := storage.allocPtrReg
      flush_wrapModeNext := storage.wrapModeReg
      flush_slotsNext := storage.slotsReg

      when(flush_pipeline.doApplyFlushState) {
        if (enableLog) notice(L"[LQ-FLUSH-S3] APPLY: Calculating final state.")

        // 1. Calculate new allocPtr based on the latched reduction result from S2
        when(flush_pipeline.s3_pipe.anyKeptReg) {
          flush_allocPtrNext := flush_pipeline.s3_pipe.lastKeptSlotPtrReg + 1
        } otherwise {
          // Nothing is kept, queue becomes empty. New allocPtr should equal the OLD freePtr.
          flush_allocPtrNext := flush_pipeline.s3_pipe.freePtrOnFlushReg
        }

        // 2. Calculate new wrapMode based on new allocPtr and OLD freePtr (both from S2/S3 pipe)
        val newAllocPtr = Mux(flush_pipeline.s3_pipe.anyKeptReg, flush_pipeline.s3_pipe.lastKeptSlotPtrReg + 1, flush_pipeline.s3_pipe.freePtrOnFlushReg)
        val oldFreePtr = flush_pipeline.s3_pipe.freePtrOnFlushReg
        
        // This logic is now trivial as it uses latched values.
        when (newAllocPtr === oldFreePtr) {
          flush_wrapModeNext := flush_pipeline.s3_pipe.anyKeptReg // If pointers meet and something was kept, it's full.
        } .elsewhen (storage.allocPtrReg > oldFreePtr) { // Was not wrapped
          flush_wrapModeNext := newAllocPtr < oldFreePtr
        } .otherwise { // Was wrapped or full
          flush_wrapModeNext := newAllocPtr < oldFreePtr || (newAllocPtr === oldFreePtr && flush_pipeline.s3_pipe.anyKeptReg)
        }
        
        // 3. Calculate final state of slots
        // We can re-use the `isKept` vector from the S1->S2 pipe registers.
        val keptStatus = flush_pipeline.s2_pipe.isKeptVecReg
        for(i <- 0 until lqDepth) {
            val slot = storage.slotsReg(i)
            flush_slotsNext(i) := slot // Start with current state

            // Was this slot in-flight at the time of the flush? (Re-calculate again)
            val wasInFlight = Bool()
            val allocPtr = storage.allocPtrReg
            val freePtr = flush_pipeline.s2_pipe.freePtrOnFlushReg
            val wasEmpty = (allocPtr === freePtr) && !storage.wrapModeReg
            val wasFull = (allocPtr === freePtr) && storage.wrapModeReg
            when(wasEmpty) { wasInFlight := False }
            .elsewhen(wasFull) { wasInFlight := True }
            .elsewhen(allocPtr > freePtr) { wasInFlight := (U(i) >= freePtr && U(i) < allocPtr) }
            .otherwise { wasInFlight := (U(i) >= freePtr || U(i) < allocPtr) }
            
            when(wasInFlight && slot.valid) {
                when(!keptStatus(i)) {
                    // This slot is being fully invalidated.
                    flush_slotsNext(i).valid := False
                    if(enableLog) debug(L"[LQ-FLUSH-S3-Invalidate] Slot[${i}] invalidated.")
                } .elsewhen(slot.isWaitingForMemRsp) {
                    // This slot was kept for draining. Mark it as flushed.
                    flush_slotsNext(i).isFlushed := True
                    if(enableLog) debug(L"[LQ-FLUSH-S3-Drain] Slot[${i}] marked for draining.")
                }
            }
        }

        if (enableLog) notice(L"[LQ-FLUSH-S3-RESULT] Final state: allocPtr=${flush_allocPtrNext}, wrapMode=${flush_wrapModeNext}")
      }
    }


    // =================================================================
    //  Head-of-Queue Processing & Memory Disambiguation
    // =================================================================
    val head_processing_logic = new Area {
      val headSlot = storage.slotsReg(storage.freePtrReg)
      val headSlotNext = slotsNext(storage.freePtrReg)
      // Block if flush is applying
      val canProcessHead = !storage.isEmpty && headSlot.valid && !flushInProgress

      // --- 流水线默认控制 ---
      // 默认情况下，S2和S3的valid信号在下一拍会变为False，除非被S1或S2的逻辑置位。
      head_proc_pipe.s2_pipe.validReg := False
      head_proc_pipe.s3_pipe.validReg := False

      // =================================================================
      // === 微流水线 阶段1 (S1): 地址准备 & 送入管道 (组合逻辑) ===
      // =================================================================
      
      // S1的启动条件：是队头，且未进入任何后续处理流程
      val canStartCheck = canProcessHead && !headSlot.isBusyChecking && !headSlot.isStalledBySB && 
                          !headSlot.isReadyForMem && !headSlot.isWaitingForMemRsp && !headSlot.isLoaded &&
                          !headSlot.hasEarlyException

      // 当S1启动时，计算下一拍S2寄存器的值
      when(canStartCheck) {
          // 标记该槽位已进入处理流程，防止重复发起
          headSlotNext.isBusyChecking := True
          
          // S2将在下一拍有效
          head_proc_pipe.s2_pipe.validReg := True 
          
          // 将S1的所有信息送到S2寄存器的输入端
          head_proc_pipe.s2_pipe.indexReg     := storage.freePtrReg
          head_proc_pipe.s2_pipe.robPtrReg    := headSlot.robPtr
          head_proc_pipe.s2_pipe.addressReg   := headSlot.address
          head_proc_pipe.s2_pipe.sizeReg      := headSlot.size
          head_proc_pipe.s2_pipe.isCoherentReg:= headSlot.isCoherent
          head_proc_pipe.s2_pipe.isIoReg      := headSlot.isIO
          
          if (enableLog) debug(L"[LQ-HEAD-S1] Address Prep for robPtr=${headSlot.robPtr} at index ${storage.freePtrReg}. Addr=0x${headSlot.address}")
      }

      // =================================================================
      // === 微流水线 阶段2 (S2): 发起查询 & 锁存结果 (时序->组合->时序) ===
      // =================================================================
      
      // 2.A: 从S2寄存器读取稳定的查询参数
      val s2_valid    = head_proc_pipe.s2_pipe.validReg
      val s2_address  = head_proc_pipe.s2_pipe.addressReg
      val s2_robPtr   = head_proc_pipe.s2_pipe.robPtrReg
      // ... (读取其他 s2_*Reg)

      // 2.B: 使用S2寄存器的稳定输出，向SQ发起组合逻辑查询
      hw.sbQueryPort.cmd.valid         := s2_valid
      hw.sbQueryPort.cmd.payload.address  := s2_address
      hw.sbQueryPort.cmd.payload.robPtr   := s2_robPtr
      hw.sbQueryPort.cmd.payload.size       := head_proc_pipe.s2_pipe.sizeReg
      hw.sbQueryPort.cmd.payload.isCoherent := head_proc_pipe.s2_pipe.isCoherentReg
      hw.sbQueryPort.cmd.payload.isIO       := head_proc_pipe.s2_pipe.isIoReg

      // 2.C: 接收SQ的组合逻辑响应
      val sbRsp = hw.sbQueryPort.rsp
      
      // 2.D: 当S2有效时，将SQ的响应送到S3寄存器的输入端
      when(s2_valid) {
          // S3将在下一拍有效
          head_proc_pipe.s3_pipe.validReg := True
          
          // 透传身份信息
          head_proc_pipe.s3_pipe.indexReg   := head_proc_pipe.s2_pipe.indexReg
          head_proc_pipe.s3_pipe.robPtrReg  := s2_robPtr
          
          // 锁存SQ的响应
          head_proc_pipe.s3_pipe.latchedSbHitReg   := sbRsp.hit
          head_proc_pipe.s3_pipe.latchedSbStallReg := sbRsp.olderStoreDataNotReady || sbRsp.olderStoreHasUnknownAddress
          head_proc_pipe.s3_pipe.latchedSbDataReg  := sbRsp.data
          
          if (enableLog) debug(L"[LQ-HEAD-S2] Firing Query for robPtr=${s2_robPtr} at index ${head_proc_pipe.s2_pipe.indexReg}. Latching Rsp (hit=${sbRsp.hit}, stall=${sbRsp.olderStoreDataNotReady}).")
      }

      // =================================================================
      // === 微流水线 阶段3 (S3): 应用锁存的结果 (时序逻辑) ===
      // =================================================================
      
      // S3 的执行条件：S3 寄存器有效
      when(head_proc_pipe.s3_pipe.validReg) {
        val targetIndex = head_proc_pipe.s3_pipe.indexReg
        val targetSlotNext = slotsNext(targetIndex)
        val currentTargetSlot = storage.slotsReg(targetIndex)

        // **鲁棒性验证**
        val isValidTarget = currentTargetSlot.valid && 
                            currentTargetSlot.isBusyChecking &&
                            currentTargetSlot.robPtr === head_proc_pipe.s3_pipe.robPtrReg

        if (enableLog) debug(L"[LQ-HEAD-S3] Applying Rsp for robPtr=${head_proc_pipe.s3_pipe.robPtrReg} at index ${targetIndex}. Valid Target: ${isValidTarget}")
        
        when(isValidTarget) {
            // 安全地应用结果
            targetSlotNext.isBusyChecking := False // 流程结束，槽位可以进入下一状态

            when(head_proc_pipe.s3_pipe.latchedSbStallReg) {
              targetSlotNext.isStalledBySB := True
              if (enableLog) debug(L"  -> Decision: STALL")
            }.elsewhen(head_proc_pipe.s3_pipe.latchedSbHitReg) {
              targetSlotNext.isLoaded := True
              targetSlotNext.data := head_proc_pipe.s3_pipe.latchedSbDataReg
              targetSlotNext.hasFault := False
              if (enableLog) debug(L"  -> Decision: FORWARDED")
            } otherwise {
              targetSlotNext.isReadyForMem := True
              if (enableLog) debug(L"  -> Decision: READY FOR MEMORY")
            }
        } otherwise {
            if (enableLog) notice(L"[LQ-HEAD-S3] Discarding stale latched result for index ${targetIndex} (current robPtr=${currentTargetSlot.robPtr}, expected=${head_proc_pipe.s3_pipe.robPtrReg})")
        }
      }
      
      // --- 辅助逻辑: Stall 恢复和 Early Exception ---
      // 这部分逻辑与微流水线并行工作，处理进入/退出流水线的特殊情况
      
      // Stall 恢复
      when(canProcessHead && headSlot.isStalledBySB) {
        headSlotNext.isStalledBySB := False
        // 清除Stall后，下一拍 canStartCheck 将自动满足，从而重试
      }
      
      // Early Exception
      // 条件：是队头，有异常，且尚未进入微流水线
      when(canProcessHead && headSlot.hasEarlyException && !headSlot.isBusyChecking) {
          headSlotNext.isLoaded := True
          headSlotNext.hasFault := True
          headSlotNext.exceptionCode := headSlot.earlyExceptionCode
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
      val canProcessHead = !storage.isEmpty && headSlot.valid && !flushInProgress

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
        ioRsp.ready := responseMatchesSlot && !responsePipe.valid && !flushInProgress

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
      when(responsePipe.valid && !flushInProgress) {
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
      // The old flush pipeline registers are gone. This area is now much simpler.
      
      when(flush_pipeline.doApplyFlushState) {
          // ---- 冲刷应用阶段: 具有最高优先级 ----
          // 使用S3阶段计算出的组合逻辑结果，无条件地更新所有状态寄存器
          storage.allocPtrReg := flush_logic_calc.flush_allocPtrNext
          storage.wrapModeReg := flush_logic_calc.flush_wrapModeNext
          storage.slotsReg    := flush_logic_calc.flush_slotsNext
          
          // 关键点：当冲刷正在应用时，freePtr不能改变！
          // Pop操作被流水线冲刷的 `flushInProgress` 信号暂停了，所以 freePtrNext 就是 freePtrReg。
          storage.freePtrReg  := storage.freePtrReg

          if (enableLog) notice(L"[LQ-STATE-UPDATE] Applying flushed state.")
          
      } .otherwise {
          // ---- 常规操作: Push/Pop ----
          // 只有在没有应用冲刷时，才执行常规的指针和状态更新
          when(doPush =/= doPop) {
            when(doPush) { // Queue grows
              when(allocPtrNext === storage.freePtrReg) {
                wrapModeRegNext := True
              }
            }.otherwise { // Queue shrinks (doPop is true)
              when(freePtrNext === storage.allocPtrReg) {
                wrapModeRegNext := False
              }
            }
          }

          storage.allocPtrReg := allocPtrNext
          storage.freePtrReg  := freePtrNext
          storage.wrapModeReg := wrapModeRegNext
          storage.slotsReg    := slotsNext
      }
      
      if (enableLog && verbose) {
        debug(
          L"[LQ-Ring] State Update: allocPtrReg=${storage.allocPtrReg} -> ${allocPtrNext}, freePtrReg=${storage.freePtrReg} -> ${freePtrNext}, wrapModeReg=${storage.wrapModeReg} -> ${wrapModeRegNext}"
        )
        for (i <- 0 until lqDepth) {
          // 只打印有效或即将有效的槽位，避免刷屏
          when(storage.slotsReg(i).valid || slotsNext(i).valid || flush_logic_calc.flush_slotsNext(i).valid) {
            val effectiveSlot = Mux(flush_pipeline.doApplyFlushState, flush_logic_calc.flush_slotsNext(i), slotsNext(i))
            debug(
              L"[LQ-Ring-Debug] Slot[${i}]: " :+
                L"valid=${effectiveSlot.valid}, " :+
                L"robPtr=${effectiveSlot.robPtr}, " :+
                L"pc=${effectiveSlot.pc}, " :+
                L"addr=${effectiveSlot.address}, " :+
                L"pdest=${effectiveSlot.pdest}, " :+
                L"txid=${effectiveSlot.txid}, " :+
                L"isFlushed=${effectiveSlot.isFlushed}, " :+ // NEW: Log isFlushed
                L"isBusyChecking=${effectiveSlot.isBusyChecking}, " :+
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
