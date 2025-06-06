// filename: hw/spinal/parallax/components/lsu/StoreQueuePlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Pipeline
import parallax.common._
import parallax.components.rob.{ROBFlushPayload, ROBService}
import parallax.utilities.{Plugin, Service, LockedImpl, ParallaxLogger, ParallaxSim}
import parallax.components.dcache2._
import spinal.lib.pipeline.Stageable
import spinal.lib.pipeline.Connection

/**
 * StoreQueueService 定义了 Store Queue (SQ) 插件向系统其他部分提供的服务接口。
 * 它遵循依赖倒置原则，允许高层模块与SQ交互而无需了解其内部实现。
 */
trait StoreQueueService extends Service with LockedImpl {
  /**
   * 从 Dispatch/Rename 阶段接收新的存储指令，用于分配条目。
   * @return Stream of StoreQueueEntry
   */
  def getAllocatePort(): Stream[StoreQueueEntry]

  /**
   * 从外部执行单元（如 PRF Reader, DCache）接收状态更新。
   * 这个端口是SQ状态变更的主要入口之一。
   * @return Flow of SqStatusUpdate
   */
  def getStatusUpdatePort(): Flow[SqStatusUpdate]

  /**
   * 从 Commit 阶段接收已提交指令的 ROB ID，用于标记SQ条目为“已提交”。
   * @return Vec of Flows containing ROB pointers.
   */
  def getReleasePorts(): Vec[Flow[UInt]]

  /**
   * 与 getReleasePorts() 配对使用，指示哪些提交槽位是有效的。
   * @return Vec of Bools.
   */
  def getReleaseMask(): Vec[Bool]

  /**
   * 接收来自系统（如ROB）的精确刷新命令。这是一个输入端口。
   * @return Flow of ROBFlushPayload
   */
  def getSQFlushPort(): Flow[ROBFlushPayload]
}

/**
 * StoreQueuePlugin 实现了处理器的存储队列。
 * 它负责管理存储指令的生命周期，从分配、地址计算、数据获取，到最终写回内存。
 *
 * 核心设计思想:
 * 1.  **内聚模型:** 插件内部包含所有的数据存储和控制逻辑，通过清晰的`Area`划分来组织代码。
 * 2.  **混合存储:** 使用`Regs`存储频繁变化的动态状态，使用`Mems`存储分配后基本不变的静态信息。
 * 3.  **流水化调度:** 核心的调度逻辑被实现为一个2级流水线(`exePipe`)，以解决时序问题。
 *     - Stage 0 (SCHEDULE): 基于快速可达的`Reg`状态进行仲裁，选择任务。
 *     - Stage 1 (DISPATCH): 使用上一阶段的选择结果，从`Mem`中收集数据并驱动外部执行单元。
 * 4.  **健壮的状态管理:** 通过上下文传递(qPtr)和响应验证来安全地处理来自异步执行单元的陈旧或被取消的响应。
 */
class StoreQueuePlugin(
    val lsuConfig: LsuConfig,
    val pipelineConfig: PipelineConfig,
    val dCacheConfig: DataCacheParameters
) extends Plugin
    with StoreQueueService
    with LockedImpl {

  private val enableLog = true

  // --- 辅助函数 (private) ---
  private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
    val genA = robPtrA.msb; val idxA = robPtrA(robPtrA.high - 1 downto 0)
    val genB = robPtrB.msb; val idxB = robPtrB(robPtrB.high - 1 downto 0)
    (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
  }
  private def getPhysicalIndex(ptr: UInt): UInt = ptr(lsuConfig.sqIdxWidth.value - 1 downto 0)

  // --- 核心硬件定义区域 (private) ---
  private val hw = create early new Area {
    // 获取所需的服务
    val aguService = getService[AguService]

    // 为整个SQ请求一个共享的AGU端口，并设置部分名称以便于在波形中识别
    val aguPort = aguService.newAguPort()
    aguPort.setPartialName("sqAgu")
    
    // 混合存储模型
    // Regs for fast, parallel state updates
    val valids       = Vec(Reg(Bool()) init(False), lsuConfig.sqDepth)
    val committeds   = Vec(Reg(Bool()) init(False), lsuConfig.sqDepth)
    val writebackCompletes = Vec(Reg(Bool()) init(False), lsuConfig.sqDepth) // 标记DCache写回是否已收到ACK
    val waitOns      = Vec.fill(lsuConfig.sqDepth)(Reg(SqWaitOn(lsuConfig)))
    
    // Mems for static or slowly changing data
    val sqPtrs       = Mem(UInt(lsuConfig.sqPtrWidth), lsuConfig.sqDepth) // 存储每个条目自身的完整指针
    val robPtrs      = Mem(UInt(lsuConfig.robPtrWidth), lsuConfig.sqDepth)
    val pcs          = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    val physDataSrcs = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    val physDataSrcIsFprs = Mem(Bool(), lsuConfig.sqDepth)
    val agImm        = Mem(SInt(12 bits), lsuConfig.sqDepth)
    val agBasePhysReg = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    val agUsePc      = Mem(Bool(), lsuConfig.sqDepth)
    val accessSizes  = Mem(MemAccessSize(), lsuConfig.sqDepth)
    
    val physicalAddresses = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    val dataToWrites      = Mem(Bits(lsuConfig.dataWidth), lsuConfig.sqDepth)
    val storeMasks        = Mem(Bits(lsuConfig.dataWidth.value / 8 bits), lsuConfig.sqDepth)
    
    // 指针定义
    val allocPtrReg     = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    val commitPtrReg    = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    val writebackPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    val priorityPtrReg  = Reg(UInt(lsuConfig.sqIdxWidth)) init(0) // 用于轮询仲裁

    // 队列状态计算
    val entryCount = UInt(log2Up(lsuConfig.sqDepth + 1) bits)
    val isFull     = Bool()
    val ptrsSameGen = allocPtrReg.msb === writebackPtrReg.msb
    val allocIdxForCount    = getPhysicalIndex(allocPtrReg)
    val writebackIdxForCount = getPhysicalIndex(writebackPtrReg)
    when(ptrsSameGen) { entryCount := (allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth) }
    .otherwise { entryCount := (U(lsuConfig.sqDepth) + allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth) }
    isFull  := entryCount === lsuConfig.sqDepth
    
    // 服务端口实例化
    val allocatePortInst     = Stream(StoreQueueEntry(lsuConfig))
    val statusUpdatePortInst = Flow(SqStatusUpdate(lsuConfig))
    val releasePortsInst     = Vec(Flow(UInt(pipelineConfig.robPtrWidth)), lsuConfig.commitWidth)
    val releaseMaskInst      = Vec(Bool(), lsuConfig.commitWidth)
    val sqFlushPortInst      = slave(Flow(ROBFlushPayload(pipelineConfig.robPtrWidth)))
  }

  // --- 服务接口实现 ---
  override def getAllocatePort(): Stream[StoreQueueEntry] = hw.allocatePortInst
  override def getStatusUpdatePort(): Flow[SqStatusUpdate] = hw.statusUpdatePortInst
  override def getReleasePorts(): Vec[Flow[UInt]] = hw.releasePortsInst
  override def getReleaseMask(): Vec[Bool] = hw.releaseMaskInst
  override def getSQFlushPort(): Flow[ROBFlushPayload] = hw.sqFlushPortInst

  // --- 核心逻辑 (private) ---
  private val logic = create late new Area {
    lock.await() // 等待所有服务就绪
    ParallaxLogger.log("SQPlugin: Elaborating core logic.")

    // --- 1. 分配逻辑 ---
    private val allocationLogic = new Area {
      hw.allocatePortInst.ready := !hw.isFull
      when(hw.allocatePortInst.fire) {
        val newEntry = hw.allocatePortInst.payload
        val allocPhysIdx = getPhysicalIndex(hw.allocPtrReg)
        if(enableLog) ParallaxSim.debug(L"[SQPlugin] Allocating new entry at Ptr: ${hw.allocPtrReg} (ROB ID: ${newEntry.robPtr})")
        
        // 写入所有静态信息
        hw.sqPtrs.write(allocPhysIdx, hw.allocPtrReg)
        hw.robPtrs.write(allocPhysIdx, newEntry.robPtr)
        hw.pcs.write(allocPhysIdx, newEntry.pc)
        hw.physDataSrcs.write(allocPhysIdx, newEntry.physDataSrc)
        hw.physDataSrcIsFprs.write(allocPhysIdx, newEntry.physDataSrcIsFpr)
        hw.agImm.write(allocPhysIdx, newEntry.aguImmediate)
        hw.agBasePhysReg.write(allocPhysIdx, newEntry.aguBasePhysReg)
        hw.agUsePc.write(allocPhysIdx, newEntry.aguUsePcAsBase)
        hw.accessSizes.write(allocPhysIdx, newEntry.accessSize)
        
        // 初始化所有动态状态
        hw.valids(allocPhysIdx) := True
        hw.committeds(allocPhysIdx) := False
        hw.writebackCompletes(allocPhysIdx) := False
        hw.waitOns(allocPhysIdx).setDefault()
        hw.waitOns(allocPhysIdx).isStalledForAgu := True // 新条目首先等待AGU
        
        hw.allocPtrReg := hw.allocPtrReg + 1
      }
    }

    // --- 2. 提交逻辑 ---
    private val releaseOnCommitLogic = new Area {
      val initialAccumulator = (U(0, log2Up(lsuConfig.commitWidth + 1) bits), hw.commitPtrReg)
      val (totalReleased, finalCommitPtr) = (0 until lsuConfig.commitWidth).foldLeft(initialAccumulator) {
        case ((releasedCountSoFar, commitPtrSoFar), i) =>
          val doRelease = hw.releasePortsInst(i).valid && hw.releaseMaskInst(i)
          val commitPhysIdx = getPhysicalIndex(commitPtrSoFar)
          val expectedRobPtr = hw.robPtrs.readAsync(commitPhysIdx)
          val success = False
          when(doRelease && hw.valids(commitPhysIdx) && !hw.committeds(commitPhysIdx) && expectedRobPtr === hw.releasePortsInst(i).payload) {
            hw.committeds(commitPhysIdx) := True
            success := True
            if(enableLog) ParallaxSim.debug(L"[SQPlugin] Committing entry at Ptr: ${commitPtrSoFar} (ROB ID: ${hw.releasePortsInst(i).payload})")
          }
          (releasedCountSoFar + success.asUInt, commitPtrSoFar + success.asUInt)
      }
      hw.commitPtrReg := finalCommitPtr
    }

    // --- 3. 刷新逻辑 ---
    private val flushLogic = new Area {
      when(hw.sqFlushPortInst.valid) {
        val flushCmd = hw.sqFlushPortInst.payload
        if (enableLog) ParallaxSim.debug(L"[SQPlugin] Precise Flush received: TargetROB=${flushCmd.targetRobPtr}")
        for (i <- 0 until lsuConfig.sqDepth) {
          when(hw.valids(i) && !hw.committeds(i)) {
            val entryRobPtr = hw.robPtrs.readAsync(U(i))
            when(isNewerOrSame(entryRobPtr, flushCmd.targetRobPtr)) {
              hw.valids(i) := False
              if (enableLog) ParallaxSim.debug(L"[SQPlugin] Invalidating SQ entry at Idx ${i.toString} with ROB ID ${entryRobPtr} due to flush.")
            }
          }
        }
        hw.allocPtrReg := hw.commitPtrReg
        hw.priorityPtrReg := getPhysicalIndex(hw.commitPtrReg) // 刷新后，优先级从头开始
      }
    }
    
    // --- 内部使用的端口定义 ---
    val prfReadPort = Stream(SqDataReadRequest(lsuConfig))
    val dCacheStorePort = Stream(DataStoreCmd(dCacheConfig.postTranslationWidth, dCacheConfig.cpuDataWidth))
    // 外部连接(模拟)
    // getService[PrfReaderService].getReadPort() << prfReadPort
    // getService[DCache].getStorePort() << dCacheStorePort

    // --- 4. 调度与执行流水线 ---
    private val execution = new Area {
        val exePipe = new Pipeline {
          val SCHEDULE, DISPATCH = newStage()
          connect(SCHEDULE, DISPATCH)(Connection.M2S())
        }
        
        val SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
        object ExeTask extends SpinalEnum { defaultEncoding = binaryOneHot; val NONE, AGU, PRF_READ = newElement() }
        val EXE_TASK = Stageable(ExeTask())
  
        // --- Stage 0: 调度选择 (SCHEDULE) ---
        val scheduleLogic = new Area {
          // -- MODIFICATION: 使用正确的 Stage 对象进行操作 --
          exePipe.SCHEDULE.haltWhen(exePipe.SCHEDULE.isValid && !exePipe.SCHEDULE.isReady)
  
          val priorityMask = ~((U(1) << hw.priorityPtrReg) - 1)
          val aguCandidates = B(hw.valids.zip(hw.waitOns).map { case (v, w) => v && w.isStalledForAgu })
          val aguSelOh = OHMasking.roundRobin(aguCandidates, priorityMask)
          
          val prfReadCandidates = B(hw.valids.zip(hw.waitOns).map { case (v, w) => v && w.isStalledForPrfRead })
          val prfReadSelOh = OHMasking.roundRobin(prfReadCandidates, priorityMask)
  
          val aguTaskSelected = aguSelOh.orR
          val prfReadTaskSelected = prfReadSelOh.orR && !aguTaskSelected
          
          exePipe.SCHEDULE(EXE_TASK) := ExeTask.NONE
          exePipe.SCHEDULE(SQ_PTR)   := U(0)
          
          when(aguTaskSelected) {
            exePipe.SCHEDULE(EXE_TASK) := ExeTask.AGU
            exePipe.SCHEDULE(SQ_PTR)   := hw.sqPtrs.readAsync(OHToUInt(aguSelOh))
          } elsewhen(prfReadTaskSelected) {
            exePipe.SCHEDULE(EXE_TASK) := ExeTask.PRF_READ
            exePipe.SCHEDULE(SQ_PTR)   := hw.sqPtrs.readAsync(OHToUInt(prfReadSelOh))
          }
        }
  
        // --- Stage 1: 数据收集与发送 (DISPATCH) ---
        val dispatchLogic = new Area {
          // -- MODIFICATION: 使用正确的 Stage 对象进行操作 --
          val task = exePipe.DISPATCH(EXE_TASK)
          val sqPtr = exePipe.DISPATCH(SQ_PTR)
          val sqIdx = getPhysicalIndex(sqPtr)
          
          hw.aguPort.input.valid := False
          prfReadPort.valid := False
          
          val doHalt = False
          exePipe.DISPATCH.haltIt(doHalt)
  
          switch(task) {
            is(ExeTask.AGU) {
              val aguIn = hw.aguPort.input
              aguIn.valid := True
              aguIn.payload.basePhysReg := hw.agBasePhysReg.readAsync(sqIdx)
              aguIn.payload.immediate   := hw.agImm.readAsync(sqIdx)
              aguIn.payload.accessSize  := hw.accessSizes.readAsync(sqIdx)
              aguIn.payload.usePc       := hw.agUsePc.readAsync(sqIdx)
              aguIn.payload.pc          := hw.pcs.readAsync(sqIdx)
              aguIn.payload.robPtr      := hw.robPtrs.readAsync(sqIdx)
              aguIn.payload.qPtr        := sqPtr
              aguIn.payload.isLoad      := False
              aguIn.payload.isStore     := True
              aguIn.payload.physDst     := U(0)
  
              when(aguIn.fire) {
                hw.waitOns(sqIdx).isStalledForAgu := False
                hw.priorityPtrReg := sqIdx + 1
                if(enableLog) ParallaxSim.debug(L"[SQ Dispatch] Fired AGU request for SQ Ptr ${sqPtr}")
              } otherwise {
                doHalt := True
              }
            }
            is(ExeTask.PRF_READ) {
              prfReadPort.valid := True
              prfReadPort.payload.sqId := sqIdx
              prfReadPort.payload.physRegSrc := hw.physDataSrcs.readAsync(sqIdx)
  
              when(prfReadPort.fire) {
                hw.waitOns(sqIdx).isStalledForPrfRead := False
                hw.priorityPtrReg := sqIdx + 1
                if(enableLog) ParallaxSim.debug(L"[SQ Dispatch] Fired PRF Read request for SQ Idx ${sqIdx}")
              } otherwise {
                doHalt := True
              }
            }
          }
        }
        exePipe.build()
      }
    // --- 5. DCache 写回与最终释放逻辑 ---
    private val writebackAndRelease = new Area {
        val ptr = hw.writebackPtrReg
        val physIdx = getPhysicalIndex(ptr)

        val canWriteback = hw.valids(physIdx) &&
                           hw.committeds(physIdx) &&
                           hw.waitOns(physIdx).addressGenerated &&
                           !hw.waitOns(physIdx).prfRead &&
                           !hw.waitOns(physIdx).dCacheStoreRsp

        dCacheStorePort.valid   := canWriteback
        dCacheStorePort.address := hw.physicalAddresses.readAsync(physIdx)
        dCacheStorePort.data    := hw.dataToWrites.readAsync(physIdx)
        dCacheStorePort.mask    := hw.storeMasks.readAsync(physIdx)
        dCacheStorePort.io      := False
        dCacheStorePort.flush   := False
        dCacheStorePort.flushFree := False
        dCacheStorePort.prefetch := False

        when(dCacheStorePort.fire) {
          hw.waitOns(physIdx).dCacheStoreRsp := False
          if(enableLog) ParallaxSim.debug(L"[SQ Writeback] Firing DCache write for SQ Ptr ${ptr}")
        }
        
        // 当队首的写回已完成时 (由 statusUpdateLogic 设置), 推进指针并释放资源
        when(hw.writebackCompletes(physIdx)) {
            hw.valids(physIdx) := False
            hw.committeds(physIdx) := False
            hw.writebackCompletes(physIdx) := False
            hw.writebackPtrReg := ptr + 1
            if(enableLog) ParallaxSim.debug(L"[SQ Release] Releasing SQ Ptr ${ptr} after writeback ack.")
        }
    }

    // --- 6. 状态更新逻辑 ---
    private val statusUpdateLogic = new Area {
      val aguOut = hw.aguPort.output
      when(aguOut.valid) {
          val sqPtrFromAgu = aguOut.payload.qPtr
          val sqIdx = getPhysicalIndex(sqPtrFromAgu)
          val expectedSqPtr = hw.sqPtrs.readAsync(sqIdx)
          
          when(hw.valids(sqIdx) && expectedSqPtr === sqPtrFromAgu) {
              val waitOn = hw.waitOns(sqIdx)
              val accessSizeFromAgu = aguOut.payload.accessSize
              
              hw.physicalAddresses.write(sqIdx, aguOut.payload.address)
              hw.storeMasks.write(sqIdx, AddressToMask(aguOut.payload.address, MemAccessSize.toByteSize(accessSizeFromAgu), lsuConfig.dataWidth.value/8))
              
              waitOn.addressGenerated := True
              waitOn.prfRead := True
              
              if(enableLog) ParallaxSim.debug(L"[SQ Status] AGU Response for SQ Ptr ${sqPtrFromAgu}: Addr=${aguOut.payload.address}. Update ACCEPTED.")
          } otherwise {
              if(enableLog) ParallaxSim.debug(L"[SQ Status] AGU Response for SQ Ptr ${sqPtrFromAgu}: Update REJECTED (stale).")
          }
      }

      when(hw.statusUpdatePortInst.valid) {
        val update = hw.statusUpdatePortInst.payload
        val sqIdx = getPhysicalIndex(update.sqPtr) // 假设update也使用完整指针
        val expectedSqPtr = hw.sqPtrs.readAsync(sqIdx)
        
        when(hw.valids(sqIdx) && expectedSqPtr === update.sqPtr) {
            val waitOn = hw.waitOns(sqIdx)
            if(enableLog) ParallaxSim.debug(L"[SQ Status] Port Update for SQ Ptr ${update.sqPtr}: Type=${update.updateType}")
            
            switch(update.updateType) {
              is(SqUpdateType.PRF_DATA_READY) {
                hw.dataToWrites.write(sqIdx, update.dataFromPrfPdr)
                waitOn.prfRead := False
              }
              is(SqUpdateType.DCACHE_STORE_RESPONSE) {
                when(update.dCacheRedoDsr) {
                  waitOn.dCacheRefill := waitOn.dCacheRefill | update.dCacheRefillSlotDsr
                  waitOn.dCacheRefillAny := waitOn.dCacheRefillAny | update.dCacheRefillAnyDsr
                  waitOn.dCacheStoreRsp := True
                } otherwise {
                  hw.writebackCompletes(sqIdx) := True
                }
              }
              is(SqUpdateType.DCACHE_REFILL_DONE) {
                waitOn.dCacheRefill := waitOn.dCacheRefill & ~update.dCacheRefillSlotDr
                when(update.dCacheRefillAnyDr) { waitOn.dCacheRefillAny := False }
                waitOn.dCacheStoreRsp := True
              }
              default {}
            }
        }
      }
    }
  }
}

// 假设 AguInput/Output 和 SqStatusUpdate 已按讨论修正，包含 qPtr/sqPtr 和 accessSize
