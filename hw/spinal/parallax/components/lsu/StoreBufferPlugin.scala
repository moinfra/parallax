// filename: parallax/components/lsu/StoreBufferPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.utilities._

// SGMB服务接口，用于测试时提供MMIO端口
trait SgmbService extends Service with LockedImpl {
  def newReadPort(): SplitGmbReadChannel
  def newWritePort(): SplitGmbWriteChannel
}

case class SqQuery(lsuCfg: LsuConfig, pipelineCfg: PipelineConfig) extends Bundle with Formattable {
    val robPtr      = UInt(pipelineCfg.robPtrWidth) // ESSENTIAL for ordering
    val address     = UInt(lsuCfg.pcWidth)
    val size        = MemAccessSize()

    def format: Seq[Any] = {
        Seq(
            L"SqQuery(",
            L"robPtr=${robPtr},",
            L"address=${address},",
            L"size=${size}",
            L")"
        )
    }
}

case class SqQueryRsp(lsuCfg: LsuConfig) extends Bundle with Formattable {
    val hit         = Bool() // 是否命中一个可以转发的store
    val data        = Bits(lsuCfg.dataWidth)

    // 依赖相关 (Disambiguation)
    val olderStoreHasUnknownAddress = Bool() // 是否存在一个更早的、地址未知的store
    val olderStoreMatchingAddress = Bool() // 是否存在一个更早的、地址匹配但数据未就绪的store

    def format: Seq[Any] = {
        Seq(
            L"SqQueryRsp(",
            L"hit=${hit},",
            L"data=${data},",
            L"olderStoreHasUnknownAddress=${olderStoreHasUnknownAddress},",
            L"olderStoreMatchingAddress=${olderStoreMatchingAddress}",
            L")"
        )
    }
}

case class SqQueryPort(lsuCfg: LsuConfig, pipelineCfg: PipelineConfig) extends Bundle with IMasterSlave {
    val cmd = Flow(SqQuery(lsuCfg, pipelineCfg))
    val rsp = SqQueryRsp(lsuCfg)
    override def asMaster(): Unit = { master(cmd); in(rsp) }
}

case class StoreBufferPushCmd(val pCfg: PipelineConfig, val lsuCfg: LsuConfig) extends Bundle {
    val addr    = UInt(pCfg.pcWidth)
    val data    = Bits(pCfg.dataWidth)
    val be      = Bits(pCfg.dataWidth.value / 8 bits)
    val robPtr  = UInt(lsuCfg.robPtrWidth)
    val accessSize = MemAccessSize()
    val isFlush = Bool() // Bench Test only
    val isIO    = Bool()
    val hasEarlyException = Bool()
    val earlyExceptionCode= UInt(pCfg.exceptionCodeWidth)
}

case class StoreBufferSlot(val pCfg: PipelineConfig, val lsuCfg: LsuConfig, val dCacheParams: DataCacheParameters) extends Bundle with Formattable {
    val isFlush             = Bool()
    val addr                = UInt(pCfg.pcWidth)
    val data                = Bits(pCfg.dataWidth)
    val be                  = Bits(pCfg.dataWidth.value / 8 bits)
    val robPtr              = UInt(lsuCfg.robPtrWidth)
    val accessSize          = MemAccessSize()
    val isIO                = Bool()
    val valid               = Bool()
    val hasEarlyException   = Bool()
    val earlyExceptionCode  = UInt(pCfg.exceptionCodeWidth)
    /*
        推测阶段 (isCommitted = False): Store位于SB中，但其结果是临时的。如果发生冲刷，它可能被作废。它只对后续的Load指令（通过转发）可见，但对系统的其它部分（如D-Cache和其他核心）不可见。
        提交阶段 (isCommitted = True):  ROB已经确认这条指令是正确路径上的一部分。它的写操作现在必须发生，且不可撤销。这个标志是触发Store从SB写入D-Cache的先决条件。
     */
    val isCommitted         = Bool()
    val sentCmd      = Bool()
    val waitRsp     = Bool()
    val isWaitingForRefill  = Bool()
    val isWaitingForWb      = Bool() 
    val refillSlotToWatch   = Bits(dCacheParams.refillCount bits)

    def setDefault(): this.type = {
        isFlush             := False
        valid               := False
        addr                := 0
        data                := 0
        be                  := 0
        robPtr              := 0
        accessSize          := MemAccessSize.W
        isIO                := False
        hasEarlyException   := False
        earlyExceptionCode  := 0
        isCommitted         := False
        sentCmd      := False
        waitRsp     := False
        isWaitingForRefill  := False
        isWaitingForWb      := False
        refillSlotToWatch   := 0
        this
    }

    def initFromCommand(command: StoreBufferPushCmd): this.type = {
        this.isFlush             := command.isFlush
        this.addr                := command.addr
        this.data                := command.data
        this.be                  := command.be
        this.robPtr              := command.robPtr
        this.accessSize          := command.accessSize
        this.isIO                := command.isIO
        this.hasEarlyException   := command.hasEarlyException
        this.earlyExceptionCode  := command.earlyExceptionCode
        this.valid               := True
        this.isCommitted         := False
        this.sentCmd      := False
        this.waitRsp     := False
        this.isWaitingForRefill  := False
        this.isWaitingForWb      := False
        this.refillSlotToWatch   := 0
        this
    }

    override def format: Seq[Any] = {
        Seq(
            L"StoreBufferSlot(",
            L"valid=${valid},",

            L"isFlush=${isFlush},",
            L"addr=${addr},",
            L"data=${data},",
            L"be=${be},",
            L"robPtr=${robPtr},",
            L"accessSize=${accessSize},",
            L"isIO=${isIO},",
            L"hasEarlyException=${hasEarlyException},",
            L"earlyExceptionCode=${earlyExceptionCode},",
            L"isCommitted=${isCommitted},",
            L"sentCmd=${sentCmd},",
            L"waitRsp=${waitRsp},",
            L"isWaitingForRefill=${isWaitingForRefill},",
            L"isWaitingForWb=${isWaitingForWb},",
            L"refillSlotToWatch=${refillSlotToWatch}",
            L")"
        )
    }
}

case class BypassAccumulator(pCfg: PipelineConfig) extends Bundle {
    val data    = Bits(pCfg.dataWidth)
    val hitMask = Bits(pCfg.dataWidth.value / 8 bits)
}

case class StoreBufferBypassData(val pCfg: PipelineConfig) extends Bundle {
    val hit     = Bool()
    val data    = Bits(pCfg.dataWidth)
    val hitMask = Bits(pCfg.dataWidth.value / 8 bits)
    def setDefault(): this.type = { hit := False; data := 0; hitMask := 0; this }
}

trait StoreBufferService extends Service with LockedImpl {
    def getPushPort(): Stream[StoreBufferPushCmd]
    def getBypassQueryAddressInput(): UInt
    def getBypassQuerySizeInput(): MemAccessSize.C
    def getBypassDataOutput(): Flow[StoreBufferBypassData]
    def getStoreQueueQueryPort(): SqQueryPort
}

class StoreBufferPlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters,
    val sbDepth: Int,
    val mmioConfig: Option[GenericMemoryBusConfig] = None
) extends Plugin with StoreBufferService with LockedImpl {
    ParallaxLogger.debug("Creating Store Buffer Plugin, mmioConfig = " + mmioConfig.toString())
    val enableLog = true
    val hw = create early new Area {
        val pushPortInst      = Stream(StoreBufferPushCmd(pipelineConfig, lsuConfig))
        val bypassQueryAddrIn = UInt(pipelineConfig.pcWidth)
        val bypassQuerySizeIn = MemAccessSize()
        val bypassDataOutInst = Flow(StoreBufferBypassData(pipelineConfig))
        val sqQueryPort       = SqQueryPort(lsuConfig, pipelineConfig)
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val dCacheStorePort = dcacheServiceInst.newStorePort()

        var sgmbServiceOpt: Option[SgmbService] = None
        var mmioWriteChannel: Option[SplitGmbWriteChannel] = None

        if (mmioConfig.isDefined) {
            val config = mmioConfig.get
            val sgmbService = getService[SgmbService]
            sgmbServiceOpt = Some(sgmbService)
            mmioWriteChannel = Some(sgmbService.newWritePort())
        }
        sgmbServiceOpt.foreach(_.retain())
        dcacheServiceInst.retain()
        robServiceInst.retain()
    }

    override def getPushPort(): Stream[StoreBufferPushCmd] = hw.pushPortInst
    override def getBypassQueryAddressInput(): UInt = hw.bypassQueryAddrIn
    override def getBypassQuerySizeInput(): MemAccessSize.C = hw.bypassQuerySizeIn
    override def getBypassDataOutput(): Flow[StoreBufferBypassData] = hw.bypassDataOutInst
    override def getStoreQueueQueryPort(): SqQueryPort = hw.sqQueryPort


    val logic = create late new Area {
        lock.await()

        val robService    = hw.robServiceInst
        val dcacheService = hw.dcacheServiceInst

        val storePortDCache = hw.dCacheStorePort

        val pushPortIn    = hw.pushPortInst
        val bypassQueryAddr= hw.bypassQueryAddrIn
        val bypassQuerySz = hw.bypassQuerySizeIn
        val bypassDataOut = hw.bypassDataOutInst

        val slots       = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
        val slotsAfterUpdates = CombInit(slots)
        val slotsNext   = CombInit(slotsAfterUpdates)

        for(i <- 0 until sbDepth) {
            slots(i).init(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
        }

        // is robPtrA newer or same than robPtrB?
        private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
            val width = robPtrA.getWidth
            require(width == robPtrB.getWidth, "ROB pointer widths must match")

            if (width <= 1) { // 处理没有generation bit的情况
                return robPtrA >= robPtrB
            }

            val genA = robPtrA.msb
            val idxA = robPtrA(width - 2 downto 0)
            val genB = robPtrB.msb
            val idxB = robPtrB(width - 2 downto 0)

            (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
        }
        private def isOlder(robPtrA: UInt, robPtrB: UInt): Bool = !isNewerOrSame(robPtrA, robPtrB)

        // --- Flush Logic (moved here to define flushInProgress early) ---
        val robFlushPort = robService.doRobFlush()
        
        // 1. 立即生效的组合逻辑信号：用于立即屏蔽交互
        val flushInProgress = robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX
        val flushTargetRobPtr = robFlushPort.payload.targetRobPtr // 立即生效的冲刷目标
        
        // 2. 延迟一拍的寄存器信号：用于实际执行槽位清理
        case class FlushInfo() extends Bundle {
            val valid = Bool()
            val targetRobPtr = UInt(pipelineConfig.robPtrWidth)
        }
        val registeredFlush = RegNext({
            val info = FlushInfo()
            info.valid      := flushInProgress
            info.targetRobPtr := flushTargetRobPtr
            info
        }).init({
            val info = FlushInfo()
            info.valid := False
            info.targetRobPtr := 0
            info
        })
        
        // --- SB Push/Pop/Commit/Flush Logic ---
        val validFall = Vec(Bool(), sbDepth)
        validFall(0) := !slots(0).valid
        for(i <- 1 until sbDepth) { validFall(i) := slots(i-1).valid && !slots(i).valid }
        val canPush = validFall.orR && !flushInProgress
        pushPortIn.ready := canPush

        when(pushPortIn.fire) {
            val pushIdx = OHToUInt(validFall)
            val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
            newSlotData.initFromCommand(pushPortIn.payload)
            slotsAfterUpdates(pushIdx) := newSlotData
            if(enableLog) report(L"[SQ] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
        }
        
        // --- Sending Logic: Strict In-Order from the head ---
        val headSlot = slots(0)
        
        // Normal Store操作发送条件（非MMIO）
        val sharedWriteCond = headSlot.valid && headSlot.isCommitted && !headSlot.isFlush &&
                            !headSlot.waitRsp && !headSlot.isWaitingForRefill && !headSlot.isWaitingForWb &&
                            !headSlot.hasEarlyException // 仅当没有早期异常时才尝试发送
        val canPopNormalOp = sharedWriteCond && !headSlot.isIO // 普通DCache写
        
        // Flush指令: 只要它到达头部并且它自己的操作未完成，就可以尝试发送。
        // Flush 指令不需要 isCommitted，但其操作本身也可能需要等待
        val canPopFlushOp = headSlot.valid && headSlot.isFlush && !headSlot.waitRsp && !headSlot.isWaitingForWb

        // MMIO Store操作发送条件
        val canPopMMIOOp = sharedWriteCond && headSlot.isIO
        when(Bool(!mmioConfig.isDefined) && headSlot.isIO) {
            assert(False, "Got isIO but no MMIO service.")
        }
        
        // canPopToDCache 现在只表示非MMIO操作，并且它们必须可以发送到DCache（而非陷入某种等待）
        val canSendToDCache = canPopNormalOp || canPopFlushOp
        val canSendToMMIO = canPopMMIOOp

        if(enableLog) report(Seq(
            L"[SQ] sharedWriteCond = ${sharedWriteCond} of headslot: ${headSlot.format}",
            L"canSendToDCache = ${canSendToDCache}. ",
            L"canSendToMMIO = ${canSendToMMIO}. "
        ))

        // DCache路径（非MMIO）
        storePortDCache.cmd.valid := canSendToDCache
        storePortDCache.cmd.payload.assignDontCare()
        when(canSendToDCache) {
            when(headSlot.isFlush) {
                storePortDCache.cmd.payload.address  := headSlot.addr
                storePortDCache.cmd.payload.flush    := True
                storePortDCache.cmd.payload.data     := 0
                storePortDCache.cmd.payload.mask     := 0
                storePortDCache.cmd.payload.io       := False
                storePortDCache.cmd.payload.flushFree:= False
                storePortDCache.cmd.payload.prefetch := False
                if(pipelineConfig.transactionIdWidth > 0) {
                    storePortDCache.cmd.payload.id := headSlot.robPtr.resize(pipelineConfig.transactionIdWidth bits)
                }
                if(enableLog) report(L"[SQ] Sending FLUSH to D-Cache: addr=${headSlot.addr}, robPtr=${headSlot.robPtr}")
            } otherwise {
                storePortDCache.cmd.payload.address  := headSlot.addr
                storePortDCache.cmd.payload.data     := headSlot.data
                storePortDCache.cmd.payload.mask     := headSlot.be
                storePortDCache.cmd.payload.io       := headSlot.isIO // For DCache path, this should be False for normal stores
                storePortDCache.cmd.payload.flush    := False
                storePortDCache.cmd.payload.flushFree:= False
                storePortDCache.cmd.payload.prefetch := False
                if(pipelineConfig.transactionIdWidth > 0) {
                    storePortDCache.cmd.payload.id := headSlot.robPtr.resize(pipelineConfig.transactionIdWidth bits)
                }
                if(enableLog) report(L"[SQ] Sending STORE to D-Cache: addr=${headSlot.addr}, data=${headSlot.data}, be=${headSlot.be}, robPtr=${headSlot.robPtr}")
            }
        }

        // MMIO路径
        val mmioWriteCmd = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.cmd.valid := canSendToMMIO
            mmioChannel.cmd.address := headSlot.addr
            mmioChannel.cmd.data := headSlot.data
            mmioChannel.cmd.byteEnables := headSlot.be
            mmioChannel.cmd.last := True // MMIO操作总是单拍
            if (mmioConfig.get.useId) {
                require(mmioChannel.cmd.id.getWidth >= headSlot.robPtr.getWidth, "MMIO ID width must be at least as wide as ROB pointer width")
                mmioChannel.cmd.id := headSlot.robPtr.resized
            }

            when(mmioChannel.cmd.valid) {
                if(enableLog) report(L"[SQ-MMIO] Sending MMIO STORE: payload=${mmioChannel.cmd.payload.format} headslot=${headSlot.format} valid=${mmioChannel.cmd.valid} ready=${mmioChannel.cmd.ready}")
            }
            mmioChannel.cmd
        }


        // --- Response and Retry Logic ---
        // Signal that a command was fired from the head THIS cycle.
        val dcacheCmdFired = canSendToDCache && storePortDCache.cmd.ready
        val mmioCmdFired = hw.mmioWriteChannel.map(channel => canSendToMMIO && channel.cmd.ready).getOrElse(False)
        if(enableLog) report(L"[SQ] mmioCmdFired=${mmioCmdFired} because canSendToMMIO=${canSendToMMIO}, channel.cmd.ready=${hw.mmioWriteChannel.map(_.cmd.ready).getOrElse(null)}")

        // Store Buffer是FIFO，所以我们只期望头部槽位的响应
        val dcacheResponseForHead = storePortDCache.rsp.valid && headSlot.valid &&
                                    (headSlot.waitRsp || dcacheCmdFired) && !headSlot.isIO && // 必须是DCache事务
                                    (headSlot.robPtr.resize(pipelineConfig.transactionIdWidth) === storePortDCache.rsp.payload.id) // ID匹配

        val mmioResponseForHead = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.rsp.valid && headSlot.valid && headSlot.isIO && // 必须是MMIO事务
            (headSlot.waitRsp || mmioCmdFired) && // 'mmioCmdFired' 确保响应是为当前发送的命令
            (headSlot.robPtr.resized === mmioChannel.rsp.payload.id) // ID匹配
        }.getOrElse(False)

        if(enableLog) report(L"[SQ] dcacheResponseForHead=${dcacheResponseForHead}, mmioResponseForHead=${mmioResponseForHead}")

        // 当命令成功发出时（必须valid&&ready），我们将其标记为已发送并等待响应。
        when(dcacheCmdFired) {
            slotsAfterUpdates(0).sentCmd := True
            slotsAfterUpdates(0).waitRsp := True 
            if(enableLog) report(L"[SQ] CMD_FIRED_DCACHE: robPtr=${slots(0).robPtr} (slotIdx=0), addr=${slots(0).addr}")
        }
        when(mmioCmdFired) {
            slotsAfterUpdates(0).sentCmd := True
            slotsAfterUpdates(0).waitRsp := True
            if(enableLog) report(L"[SQ] CMD_FIRED_MMIO: robPtr=${slots(0).robPtr} (slotIdx=0), addr=${slots(0).addr}")
        }

        // 处理 D-Cache 响应
        when(dcacheResponseForHead) {
            // 响应到达，操作不再是待定状态
            slotsAfterUpdates(0).waitRsp := False
            
            when(storePortDCache.rsp.payload.redo) {
                // REDO 意味着需要重试。清除 sentCmd 以重新启用发送。
                slotsAfterUpdates(0).sentCmd := False
                
                when(storePortDCache.rsp.payload.flush) {
                    slotsAfterUpdates(0).isWaitingForWb := True // 等待写回完成
                    if(enableLog) report(L"[SQ] REDO_FOR_FLUSH received for robPtr=${headSlot.robPtr}. Entering WAIT_FOR_WB.")
                } otherwise {
                    slotsAfterUpdates(0).isWaitingForRefill := True // 等待填充完成
                    slotsAfterUpdates(0).refillSlotToWatch  := storePortDCache.rsp.payload.refillSlot
                    if(enableLog) report(L"[SQ] REDO_FOR_REFILL received for robPtr=${headSlot.robPtr}. Entering WAIT_FOR_REFILL.")
                }
            } otherwise {
                // 成功响应 (redo=0)。操作完成。
                if(enableLog) report(L"[SQ] DCACHE_RSP_SUCCESS received for robPtr=${headSlot.robPtr}.")
            }
        }
        // 处理 MMIO 响应
        when(mmioResponseForHead) {
            hw.mmioWriteChannel.foreach { mmioChannel =>
                // MMIO 响应到达，操作完成 (MMIO 没有 redo)
                slotsAfterUpdates(0).waitRsp := False
                val mmioError = mmioChannel.rsp.payload.error
                when(mmioError) {
                    if(enableLog) report(L"[SQ-MMIO] MMIO RSP_ERROR received for robPtr=${headSlot.robPtr}.")
                    // 标记为有异常以便处理和弹出
                    slotsAfterUpdates(0).hasEarlyException := True
                    slotsAfterUpdates(0).earlyExceptionCode := ExceptionCode.STORE_ACCESS_FAULT 
                } otherwise {
                     if(enableLog) report(L"[SQ-MMIO] MMIO RSP_SUCCESS received for robPtr=${headSlot.robPtr}.")
                }
            }
        }

            getServiceOption[DebugDisplayService].foreach(dbg => { 
            dbg.setDebugValueOnce(mmioResponseForHead, DebugValue.MEM_WRITE_FIRE, expectIncr = true)
        })

        // MMIO响应的 ready 信号只在响应有效且是为当前头部的 Store 时拉高
        hw.mmioWriteChannel.foreach { mmioChannel =>
            mmioChannel.rsp.ready := mmioResponseForHead
        }

        // 处理 D-Cache 填充完成信号 (用于 redo 情况)
        val refillCompletionsFromDCache = dcacheService.getRefillCompletions()
        // report(L"[SQ] Watching... refillCompletionsFromDCache=${refillCompletionsFromDCache}")
        val waitedRefillIsDone = headSlot.valid && headSlot.isWaitingForRefill &&
                                (headSlot.refillSlotToWatch & refillCompletionsFromDCache).orR
        if(enableLog) report(L"[SQ] waitedRefillIsDone=${waitedRefillIsDone} because: valid=${headSlot.valid} isWaitingForRefill=${headSlot.isWaitingForRefill} refillSlotToWatch=${headSlot.refillSlotToWatch} refillCompletionsFromDCache=${refillCompletionsFromDCache}")
        when(waitedRefillIsDone) {
            slotsAfterUpdates(0).isWaitingForRefill := False
            slotsAfterUpdates(0).sentCmd := False // Clear sentCmd to retry sending
            if(enableLog) report(L"[SQ] REFILL_DONE observed for robPtr=${headSlot.robPtr}. Ready to retry.")
        }
        
        // 处理 D-Cache 写回忙信号 (用于 Flush 的 redo 情况)
        val dCacheIsWbBusy = dcacheService.writebackBusy()
        if(enableLog) report(L"[SQ] dCacheIsWbBusy=${dCacheIsWbBusy}")
        when(headSlot.valid && headSlot.isWaitingForWb && !dCacheIsWbBusy) {
            slotsAfterUpdates(0).isWaitingForWb := False
            slotsAfterUpdates(0).sentCmd := False // Clear sentCmd to retry sending
            if(enableLog) report(L"[SQ] DCACHE_READY observed for robPtr=${headSlot.robPtr}. Exiting WAIT_FOR_WB.")
        }

        // --- Commit and Flush Logic ---
        val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
        
        for(i <- 0 until sbDepth){
            // 检查这个槽位是否在此周期被延迟的冲刷信号命中
            val toBeFlushedByRob = registeredFlush.valid && 
                                  slots(i).valid && 
                                  !slots(i).isCommitted && // 只冲刷未提交的
                                  isNewerOrSame(slots(i).robPtr, registeredFlush.targetRobPtr)

            // 优先处理冲刷
            when(toBeFlushedByRob) {
                // 如果要冲刷，则直接将槽位置为无效，忽略所有其他信号
                slotsAfterUpdates(i).valid := False
                if(enableLog) report(L"[SQ] FLUSH (Exec): Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr}) by ROB flush.")
            } .elsewhen(slots(i).valid && !slots(i).isCommitted) {
                // 只有在不被冲刷的前提下，才检查提交信号
                for(j <- 0 until pipelineConfig.commitWidth){
                    when(commitInfoFromRob(j).canCommit &&
                         commitInfoFromRob(j).entry.payload.uop.robPtr === slots(i).robPtr &&
                         (commitInfoFromRob(j).entry.payload.uop.decoded.uopCode === BaseUopCode.STORE ||
                          /*commitInfoFromRob(j).entry.payload.uop.isFlush*/ False) && // 提交也可以是Flush指令
                         !commitInfoFromRob(j).entry.status.hasException) {
                        
                        slotsAfterUpdates(i).isCommitted := True
                        if(enableLog) report(L"[SQ] COMMIT_SIGNAL: robPtr=${slots(i).robPtr} (slotIdx=${i}) marked as committed.")
                    }
                }
            }
        }

        when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                if(enableLog) report(L"[SQ] FULL_FLUSH received. Clearing all slots.")
                for(i <- 0 until sbDepth) {
                    slotsAfterUpdates(i).setDefault() // Use setDefault to clear all internal state
                }
        }

        // --- Pop Logic (FIFO Enforcement) ---
        val popHeadSlot = slotsAfterUpdates(0)
        if(enableLog) report("slotsAfterUpdates(0): " :+ popHeadSlot.format)
        // 操作完成的通用条件：命令已发出，且不再等待任何响应/重试信号
        val operationDone = popHeadSlot.sentCmd && !popHeadSlot.waitRsp && !popHeadSlot.isWaitingForRefill && !popHeadSlot.isWaitingForWb

        val popRequest = False // 默认不弹出

        when(popHeadSlot.valid && popHeadSlot.isCommitted) { // 只有有效、已提交的头部槽位才可能弹出
            when(popHeadSlot.isFlush) {
                // Flush 指令：一旦自身操作完成即可弹出
                when(operationDone) {
                    popRequest := True
                    if(enableLog) report(L"[SQ] POP_FLUSH: robPtr=${popHeadSlot.robPtr}")
                }
            } elsewhen (popHeadSlot.hasEarlyException) {
                // 带有早期异常的 Store 指令：一旦提交，即可弹出
                // 注意：如果早期异常的 Store 仍然发出了内存请求（例如，地址异常在发出后才检测到），
                // 那么它也必须等待 operationDone。目前的 hasEarlyException 意味着不需要内存访问。
                popRequest := True
                if(enableLog) report(L"[SQ] POP_EARLY_EXCEPTION: robPtr=${popHeadSlot.robPtr}")
            } otherwise { // 普通 Store (非 Flush, 无早期异常)
                // 普通 Store 和 MMIO Store：必须等到其内存操作完成
                when(operationDone) {
                    popRequest := True
                    if(enableLog) report(L"[SQ] POP_NORMAL_STORE/MMIO: robPtr=${popHeadSlot.robPtr}, isIO=${popHeadSlot.isIO}")
                }
            }
        } 
         // FIXME: 这个 popInvalidSlot 条件过于激进！ 
            // 如果 slots(0) 因为某种外部原因而 valid=0，但它实际上是一个需要被响应机制清除其 waitRsp 标志的事务，
            // 那么 POP_INVALID_SLOT 可能会在 mmioResponseForHead 有机会将其 waitRsp 清除之前就将其弹出，导致响应无法被消费。
            // 这样内存控制器会一直试图发送这个响应，导致卡死
            // 不对，如果 waitRsp 是真的，那么 popHeadSlot.valid 肯定是真的。因为冲刷也必须等待 waitRsp  响应之后。而一个等待 等待 waitRsp  的条目一定是 有效条目。
        .elsewhen(!popHeadSlot.valid && !slots.tail.map(_.valid).orR) { // 如果头部无效，且其余槽位都无效，则清空队列 (避免死锁)
            // This condition is for when the head slot becomes invalid (e.g. by full flush)
            // and we need to shift the queue. This is a cleanup pop.
            popRequest := True
            if(enableLog) report(L"[SQ] POP_INVALID_SLOT: Clearing invalid head slot.")
        }


        when(popRequest) {
            for (i <- 0 until sbDepth - 1) {
                slotsNext(i) := slotsAfterUpdates(i + 1)
            }
            slotsNext(sbDepth - 1).setDefault()
        }

        // =============================================================================
        // >> 集成: Store-to-Load 转发逻辑 (SqQueryPort)
        // (保持不变，因为这部分逻辑是查询，不涉及实际的内存操作和弹出)
        // =============================================================================
        val forwardingLogic = new Area {
            ParallaxLogger.log("StoreBufferPlugin: Elaborating Store-to-Load forwarding logic.")

            val query = hw.sqQueryPort.cmd
            val rsp   = hw.sqQueryPort.rsp

            val dataWidthBytes = pipelineConfig.dataWidth.value / 8
            val pcAddrWidth = pipelineConfig.pcWidth.value
            val wordAddrBits = log2Up(dataWidthBytes)

            val loadMask = Bits(dataWidthBytes bits)
            loadMask := MemAccessSize.toByteEnable(query.payload.size, query.payload.address(wordAddrBits-1 downto 0), dataWidthBytes)

            val bypassInitial = BypassAccumulator(pipelineConfig)
            bypassInitial.data.assignFromBits(B(0))
            bypassInitial.hitMask.assignFromBits(B(0))
            if(enableLog) report(L"[SQ-Fwd] Query: valid=${query.valid} robPtr=${query.payload.robPtr} addr=${query.payload.address} size=${query.payload.size}")

            // Store-to-Load 转发逻辑：遍历所有比 Load 旧的、有效的且未被冲刷的 Store。
            val forwardingResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
                val nextAcc = CombInit(acc)
                
                // 一个槽位只有在它本身有效，并且它不是一个正在被冲刷的推测性条目时，才是可见的。
                // 此外，只有已提交或在转发时可用的Store（如在Commit后的Forwarding阶段）才可用于转发。
                // 但在SB，未Commit的Store也可以转发给Load，只要其地址已知且不等待Disambiguation。
                val isSlotVisibleAndForwardable = slot.valid && 
                                                  !slot.hasEarlyException && // 早期异常的Store不提供数据
                                                  !slot.isFlush && // Flush指令不提供数据
                                                  isOlder(slot.robPtr, query.payload.robPtr) && // Store必须比Load旧
                                                  // NOTE: isWaitingForRefill / isWaitingForWb 的 Store 在内存中数据不确定，不能转发
                                                  !slot.isWaitingForRefill && !slot.isWaitingForWb

                when(isSlotVisibleAndForwardable) {
                    val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                    val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                    val canForward = !slot.hasEarlyException &&
                                     isOlder(slot.robPtr, query.payload.robPtr) &&
                                     (loadWordAddr === storeWordAddr)
                    
                    when(canForward) {
                        for (k <- 0 until dataWidthBytes) {
                            when(slot.be(k) && loadMask(k) && !acc.hitMask(k)) {
                                nextAcc.data(k*8, 8 bits) := slot.data(k*8, 8 bits)
                                nextAcc.hitMask(k)        := True
                            }
                        }
                    }
                }
                nextAcc
            }

            val allRequiredBytesHit = (forwardingResult.hitMask & loadMask) === loadMask
            rsp.data := forwardingResult.data
            val hasSomeOverlap = (forwardingResult.hitMask & loadMask).orR

            // --- 依赖关系检查 (Disambiguation) ---
            rsp.olderStoreHasUnknownAddress := False // 简化：假设Store地址在写入SB时已是已知
            rsp.olderStoreMatchingAddress := False
            val mustStall = rsp.olderStoreHasUnknownAddress || rsp.olderStoreMatchingAddress

            // 初始化rsp.hit
            rsp.hit  := query.valid && allRequiredBytesHit && !mustStall
            if(enableLog) report(L"[SQ-Fwd] Forwarding? hit=${rsp.hit}, because query.valid=${query.valid}, allRequiredBytesHit=${allRequiredBytesHit}")
            
            // 检查是否存在与当前Load冲突的、比Load旧的Store
            for(slot <- slots) {
                val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                
                // 一个槽位只有在它本身有效，并且它没有被冲刷时，才可能导致冲突
                val isSlotActiveForDisambiguation = slot.valid && 
                                                    !slot.hasEarlyException && // 异常Store不参与普通Disambiguation
                                                    !slot.isFlush // Flush指令不参与普通Disambiguation

                when(isSlotActiveForDisambiguation) {
                    val isOlderAndPotentiallyConflicting = isOlder(slot.robPtr, query.payload.robPtr) // Store必须比Load旧
                    val isAddressOverlap = (loadWordAddr === storeWordAddr) // 地址匹配（字粒度）

                    when(isOlderAndPotentiallyConflicting && isAddressOverlap) {
                        val fullySatisfiedByThisStore = (slot.be & loadMask) === loadMask // 这个Store是否完全覆盖了Load所需的所有字节
                        val partialSatisfiedByThisStore = (slot.be & loadMask).orR // 这个Store是否部分覆盖了Load所需字节
                        
                        when(!fullySatisfiedByThisStore && partialSatisfiedByThisStore) {
                            // 存在部分重叠但未完全覆盖的旧Store，需要stall
                            if(enableLog) report(L"[SQ-Fwd] STALL_PARTIAL_OVERLAP: slot=${slot.robPtr} (LoadAddr=${loadWordAddr}, StoreAddr=${storeWordAddr})")
                            rsp.olderStoreMatchingAddress := True
                        }
                        
                        when(slot.waitRsp || slot.isWaitingForRefill || slot.isWaitingForWb) {
                            // 如果旧Store地址已知，但其数据仍未写入缓存/MMIO，且与当前Load地址匹配，则需要等待
                            if(enableLog) report(L"[SQ-Fwd] STALL_DATA_NOT_READY: slot=${slot.robPtr} (LoadAddr=${loadWordAddr}, StoreAddr=${storeWordAddr})")
                            rsp.olderStoreMatchingAddress := True
                        }
                    }
                }
            }

            // AXI ID is critical for checking if the response is actually for the head.
            // If it's a strict FIFO, any response that comes back MUST be for the head.
            // If it's not for the head, it's a fundamental AXI infrastructure problem or ID collision.
            when(query.valid) {
                // debug print
                if(enableLog) report(L"[SQ-Fwd] Query: ${query.payload.format}")
                if(enableLog) report(L"[SQ-Fwd] Rsp: ${rsp.format}")
            }
            if(enableLog) report(L"[SQ-Fwd] Result: hitMask=${forwardingResult.hitMask} (loadMask=${loadMask}), allHit=${allRequiredBytesHit}, finalRsp.hit=${rsp.hit}")

        } // End of forwardingLogic

        // --- 同周期流水线旁路逻辑 (Existing Logic) ---
        // (这部分逻辑不涉及对外部总线的交互，仅处理内部数据转发，所以通常无需修改)
        val bypassResult            = StoreBufferBypassData(pipelineConfig)
        val loadQueryBe             = MemAccessSize.toByteEnable(bypassQuerySz, bypassQueryAddr(log2Up(pipelineConfig.dataWidth.value/8)-1 downto 0), pipelineConfig.dataWidth.value / 8)
        val bypassInitial           = BypassAccumulator(pipelineConfig)
        bypassInitial.data.assignFromBits(B(0))
        bypassInitial.hitMask.assignFromBits(B(0))

        val finalBypassResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
            val nextAcc = CombInit(acc)
            
            // 使用 isSlotVisible 来控制旁路逻辑，与 forwardingLogic 保持一致
            // 旁路也需要考虑 Store 的有效性、是否被冲刷、是否有早期异常。
            val isSlotVisible = slot.valid && 
                                !slot.hasEarlyException && 
                                !slot.isFlush // Flush指令不提供旁路数据
            
            when(isSlotVisible) {
                 val dataWidthBytes = pipelineConfig.dataWidth.value / 8
                 val loadWordAddr = bypassQueryAddr(pipelineConfig.pcWidth.value-1 downto log2Up(dataWidthBytes))
                 val storeWordAddr= slot.addr(pipelineConfig.pcWidth.value-1 downto log2Up(dataWidthBytes))

                 when(loadWordAddr === storeWordAddr) {
                     for (k <- 0 until dataWidthBytes) {
                         when(slot.be(k) && loadQueryBe(k) && !acc.hitMask(k)) {
                             nextAcc.data(k*8, 8 bits) := slot.data(k*8, 8 bits)
                             nextAcc.hitMask(k)        := True
                         }
                     }
                 }
            }
            nextAcc
        }

        val finalHitMask = finalBypassResult.hitMask
        val overallBypassHit = finalHitMask.orR

        bypassDataOut.valid            := overallBypassHit
        bypassDataOut.payload.data     := finalBypassResult.data
        bypassDataOut.payload.hitMask  := finalHitMask
        bypassDataOut.payload.hit      := overallBypassHit && (finalHitMask === loadQueryBe)
        // --- End of Bypass Logic ---
        if(enableLog) report(L"[SQ] bypassDataOut: valid=${bypassDataOut.valid}, data=${bypassDataOut.payload.data}, hit=${bypassDataOut.payload.hit}, hitMask=${bypassDataOut.payload.hitMask}")

        slots := slotsNext

        // 在StoreBufferPlugin中添加调试输出
        for (i <- 0 until sbDepth) {
            when(slots(i).valid) {
                if(enableLog) report(L"[SQ-Debug] SlotState[${i}]: " :+
                    L"robPtr=${slots(i).robPtr}, " :+
                    L"valid=${slots(i).valid}, " :+
                    L"isCommitted=${slots(i).isCommitted}, " :+
                    L"sentCmd=${slots(i).sentCmd}, " :+
                    L"waitRsp=${slots(i).waitRsp}, " :+
                    L"isWaitingForRefill=${slots(i).isWaitingForRefill}, " :+
                    L"isWaitingForWb=${slots(i).isWaitingForWb}, " :+
                    L"hasEarlyException=${slots(i).hasEarlyException}, " :+
                    L"isIO=${slots(i).isIO}, " :+
                    L"isFlush=${slots(i).isFlush}"
                )
            }
        }
        // 监控DCache接口状态
        if(enableLog) report(L"[SQ-Debug] DCache Interface: " :+
            L"cmd.valid=${storePortDCache.cmd.valid}, " :+
            L"cmd.ready=${storePortDCache.cmd.ready}, " :+
            L"rsp.valid=${storePortDCache.rsp.valid}, " :+
            L"canSendToDCache=${canSendToDCache}"
        )

        // Monitor MMIO interface state
        hw.mmioWriteChannel.foreach { mmioChannel =>
            if(enableLog) report(L"[SQ-Debug] MMIO Interface: " :+
                L"cmd.valid=${mmioChannel.cmd.valid}, " :+
                L"cmd.ready=${mmioChannel.cmd.ready}, " :+
                L"rsp.valid=${mmioChannel.rsp.valid}, " :+
                L"rsp.ready=${mmioChannel.rsp.ready}, " :+
                L"canSendToMMIO=${canSendToMMIO}"
            )
        }
        
        hw.dcacheServiceInst.release();
        hw.robServiceInst.release()
        hw.sgmbServiceOpt.foreach(_.release())
    }
}
