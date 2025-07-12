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

case class StoreBufferSlot(val pCfg: PipelineConfig, val lsuCfg: LsuConfig, val dCacheParams: DataCacheParameters) extends Bundle {
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
    val isSentToDCache      = Bool()
    val dcacheOpPending     = Bool()
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
        isSentToDCache      := False
        dcacheOpPending     := False
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
        this.isSentToDCache      := False
        this.dcacheOpPending     := False
        this.isWaitingForRefill  := False
        this.isWaitingForWb      := False
        this.refillSlotToWatch   := 0
        this
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

    val hw = create early new Area {
        val pushPortInst      = Stream(StoreBufferPushCmd(pipelineConfig, lsuConfig))
        val bypassQueryAddrIn = UInt(pipelineConfig.pcWidth)
        val bypassQuerySizeIn = MemAccessSize()
        val bypassDataOutInst = Flow(StoreBufferBypassData(pipelineConfig))
        val sqQueryPort       = SqQueryPort(lsuConfig, pipelineConfig)
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val dCacheStorePort = dcacheServiceInst.newStorePort()

        // MMIO支持：如果配置了MMIO，则通过SgmbService获取写通道
        val sgmbServiceOpt = mmioConfig.flatMap { config =>
            try {
                Some(getService[SgmbService])
            } catch {
                case _: Exception => None
            }
        }
        
        val mmioWriteChannel = mmioConfig.map { config =>
            sgmbServiceOpt match {
                case Some(sgmbService) => sgmbService.newWritePort()
                case None => master(SplitGmbWriteChannel(config))
            }
        }
        
        // 保持服务引用
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

        // --- SB Push/Pop/Commit/Flush Logic ---
        val validFall = Vec(Bool(), sbDepth)
        validFall(0) := !slots(0).valid
        for(i <- 1 until sbDepth) { validFall(i) := slots(i-1).valid && !slots(i).valid }
        val canPush = validFall.orR
        pushPortIn.ready := canPush

        when(pushPortIn.fire) {
            val pushIdx = OHToUInt(validFall)
            val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
            newSlotData.initFromCommand(pushPortIn.payload)
            slotsAfterUpdates(pushIdx) := newSlotData
            ParallaxSim.log(L"[SQ] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
        }
        
        // --- Sending Logic: Strict In-Order from the head ---
        val headSlot = slots(0)
        
        // Normal Store操作发送条件（非MMIO）
        val canPopNormalOp = headSlot.valid && headSlot.isCommitted && !headSlot.isFlush &&
                            !headSlot.dcacheOpPending && !headSlot.isWaitingForRefill && !headSlot.isWaitingForWb &&
                            !headSlot.hasEarlyException && !headSlot.isIO

        // Flush指令: 只要它到达头部就可以立即发送，不需要等待提交
        val canPopFlushOp = headSlot.valid && headSlot.isFlush && !headSlot.dcacheOpPending && !headSlot.isWaitingForWb

        // MMIO Store操作发送条件
        val canPopMMIOOp = headSlot.valid && headSlot.isCommitted && !headSlot.isFlush &&
                          !headSlot.dcacheOpPending && !headSlot.isWaitingForRefill && !headSlot.isWaitingForWb &&
                          !headSlot.hasEarlyException && headSlot.isIO

        val canPopToDCache = canPopNormalOp || canPopFlushOp
        report(L"[SQ] canPopToDCache=${canPopToDCache} because canPopNormalOp=${canPopNormalOp}, canPopFlushOp=${canPopFlushOp}")
        report(L"[SQ] canPopMMIOOp=${canPopMMIOOp}")

        // DCache路径（非MMIO）
        storePortDCache.cmd.valid := canPopToDCache
        storePortDCache.cmd.payload.assignDontCare()
        when(canPopToDCache) {
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
                report(L"[SQ] Sending FLUSH to D-Cache: addr=${headSlot.addr}, robPtr=${headSlot.robPtr}")
            } otherwise {
                storePortDCache.cmd.payload.address  := headSlot.addr
                storePortDCache.cmd.payload.data     := headSlot.data
                storePortDCache.cmd.payload.mask     := headSlot.be
                storePortDCache.cmd.payload.io       := headSlot.isIO
                storePortDCache.cmd.payload.flush    := False
                storePortDCache.cmd.payload.flushFree:= False
                storePortDCache.cmd.payload.prefetch := False
                if(pipelineConfig.transactionIdWidth > 0) {
                    storePortDCache.cmd.payload.id := headSlot.robPtr.resize(pipelineConfig.transactionIdWidth bits)
                }
                report(L"[SQ] Sending STORE to D-Cache: addr=${headSlot.addr}, data=${headSlot.data}, be=${headSlot.be}, robPtr=${headSlot.robPtr}")
            }
        }

        // MMIO路径
        val mmioWriteCmd = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.cmd.valid := canPopMMIOOp
            mmioChannel.cmd.address := headSlot.addr
            mmioChannel.cmd.data := headSlot.data
            mmioChannel.cmd.byteEnables := headSlot.be
            mmioChannel.cmd.last := True // MMIO操作总是单拍
            if (mmioConfig.get.useId) {
                mmioChannel.cmd.id := headSlot.robPtr.resized
            }
            
            when(mmioChannel.cmd.fire) {
                slotsAfterUpdates(0).isSentToDCache := True  // 复用这个状态表示已发送
                slotsAfterUpdates(0).dcacheOpPending := True // 复用这个状态等待响应
                ParallaxSim.log(L"[SQ-MMIO] SEND_TO_MMIO: robPtr=${headSlot.robPtr} addr=${headSlot.addr}, data=${headSlot.data}")
            }
            mmioChannel.cmd
        }
        // --- Response and Retry Logic ---
        // Signal that a command was fired from the head THIS cycle.
        val dcacheCmdFired = canPopToDCache && storePortDCache.cmd.ready
        val mmioCmdFired = hw.mmioWriteChannel.map(channel => canPopMMIOOp && channel.cmd.ready).getOrElse(False)

        // A D-Cache response is for the head slot if:
        val responseIsForHead = storePortDCache.rsp.valid && slots(0).valid &&
                               (slots(0).dcacheOpPending || dcacheCmdFired) && !slots(0).isIO

        // MMIO response is for the head slot if:
        val mmioResponseIsForHead = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.rsp.valid && slots(0).valid && 
            (slots(0).dcacheOpPending || mmioCmdFired) && slots(0).isIO
        }.getOrElse(False)

        report(L"[SQ] responseIsForHead=${responseIsForHead}, mmioResponseIsForHead=${mmioResponseIsForHead}")

        // When a command is fired, we mark it as sent.
        when(dcacheCmdFired) {
            slotsAfterUpdates(0).isSentToDCache := True
            ParallaxSim.log(L"[SQ] SEND_TO_DCACHE: robPtr=${slots(0).robPtr} (slotIdx=0), addr=${slots(0).addr}, data=${slots(0).data}, be=${slots(0).be}")
        }

        when(mmioCmdFired) {
            slotsAfterUpdates(0).isSentToDCache := True  // 复用这个状态
            ParallaxSim.log(L"[SQ] SEND_TO_MMIO: robPtr=${slots(0).robPtr} (slotIdx=0), addr=${slots(0).addr}, data=${slots(0).data}, be=${slots(0).be}")
        }

        // Centralized, prioritized management of the dcacheOpPending state.
        when(responseIsForHead) {
            // DCache response arrived, so the operation is no longer pending.
            slotsAfterUpdates(0).dcacheOpPending := False
            
            when(storePortDCache.rsp.payload.redo) {
                // REDO means we must retry. We clear isSentToDCache to re-enable sending.
                slotsAfterUpdates(0).isSentToDCache := False
                
                when(storePortDCache.rsp.payload.flush) {
                    slotsAfterUpdates(0).isWaitingForWb := True
                    ParallaxSim.log(L"[SQ] REDO_FOR_FLUSH received for robPtr=${slots(0).robPtr}. Entering WAIT_FOR_WB.")
                } otherwise {
                    slotsAfterUpdates(0).isWaitingForRefill := True
                    slotsAfterUpdates(0).refillSlotToWatch  := storePortDCache.rsp.payload.refillSlot
                    ParallaxSim.log(L"[SQ] REDO_FOR_REFILL received for robPtr=${slots(0).robPtr}. Entering WAIT_FOR_REFILL.")
                }
            } otherwise {
                // Successful response (redo=0). The operation is done.
                ParallaxSim.log(L"[SQ] RSP_SUCCESS received for robPtr=${slots(0).robPtr}.")
            }
        } elsewhen(mmioResponseIsForHead) {
            // MMIO response arrived, operation is done (MMIO doesn't have redo)
            slotsAfterUpdates(0).dcacheOpPending := False
            hw.mmioWriteChannel.foreach { mmioChannel =>
                val mmioError = mmioChannel.rsp.payload.error
                when(mmioError) {
                    ParallaxSim.log(L"[SQ-MMIO] RSP_ERROR received for robPtr=${slots(0).robPtr}.")
                } otherwise {
                    ParallaxSim.log(L"[SQ-MMIO] RSP_SUCCESS received for robPtr=${slots(0).robPtr}.")
                }
            }
        } elsewhen(dcacheCmdFired || mmioCmdFired) {
            // No response arrived THIS cycle, but we just fired a command.
            // So, we must enter the pending state and wait for a response next cycle.
            slotsAfterUpdates(0).dcacheOpPending := True
        }

        // 设置MMIO响应的ready信号
        hw.mmioWriteChannel.foreach { mmioChannel =>
            mmioChannel.rsp.ready := slots(0).valid && slots(0).isIO && slots(0).dcacheOpPending
        }

        val refillCompletionsFromDCache = dcacheService.getRefillCompletions()
        ParallaxSim.log(L"[SQ] Watching... refillCompletionsFromDCache=${refillCompletionsFromDCache}")
        val waitedRefillIsDone = slots(0).valid && slots(0).isWaitingForRefill &&
                         (slots(0).refillSlotToWatch & refillCompletionsFromDCache).orR
        report(L"[SQ] waitedRefillIsDone=${waitedRefillIsDone} because: valid=${slots(0).valid} isWaitingForRefill=${slots(0).isWaitingForRefill} refillSlotToWatch=${slots(0).refillSlotToWatch} refillCompletionsFromDCache=${refillCompletionsFromDCache}")
        when(waitedRefillIsDone) {
            slotsAfterUpdates(0).isWaitingForRefill := False
            ParallaxSim.log(L"[SQ] REFILL_DONE observed for robPtr=${slots(0).robPtr}. Ready to retry.")
        }
        val dCacheIsWbBusy = dcacheService.writebackBusy()
        report(L"[SQ] dCacheIsWbBusy=${dCacheIsWbBusy}")
        when(slots(0).valid && slots(0).isWaitingForWb && !dCacheIsWbBusy) {
            slotsAfterUpdates(0).isWaitingForWb := False
            ParallaxSim.log(L"[SQ] DCACHE_READY observed for robPtr=${slots(0).robPtr}. Exiting WAIT_FOR_WB.")
        }
        // --- Commit and Flush Logic (Unchanged) ---
        val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
        for(i <- 0 until sbDepth){
            when(slots(i).valid && !slots(i).isCommitted){
                for(j <- 0 until pipelineConfig.commitWidth){
                    // 关键修复：移除了对 commitAcksFromRob 的依赖
                    when(commitInfoFromRob(j).valid &&
                         commitInfoFromRob(j).entry.payload.uop.robPtr === slots(i).robPtr &&
                         commitInfoFromRob(j).entry.payload.uop.decoded.uopCode === BaseUopCode.STORE &&
                         !commitInfoFromRob(j).entry.status.hasException) {
                        slotsAfterUpdates(i).isCommitted := True
                        ParallaxSim.log(L"[SQ] COMMIT_SIGNAL: robPtr=${slots(i).robPtr} (slotIdx=${i}) marked as committed.")
                    }
                }
            }
        }



        val robFlushPort = robService.getFlushListeningPort()
        when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                ParallaxSim.log(L"[SQ] FULL_FLUSH received. Clearing all slots.")
                for(i <- 0 until sbDepth) {
                    slotsAfterUpdates(i).valid := False
                }
        }.elsewhen (robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX) {
            val flushedRobStartIdx = robFlushPort.payload.targetRobPtr
            ParallaxSim.warning(L"[SQ] FLUSH received from ROB: targetRobPtr=${flushedRobStartIdx}")
            for(i <- 0 until sbDepth){
                when(slots(i).valid && !slots(i).isCommitted && isNewerOrSame(slots(i).robPtr, flushedRobStartIdx)){
                    slotsAfterUpdates(i).valid := False
                    ParallaxSim.log(L"[SQ] FLUSH: Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr})")
                }
            }
        }

        // --- Pop Logic ---
        val popHeadSlot = slotsAfterUpdates(0)

        val operationDone = popHeadSlot.isSentToDCache && !popHeadSlot.dcacheOpPending && !popHeadSlot.isWaitingForRefill && !popHeadSlot.isWaitingForWb

        // 一个 `flush` 指令，只要它在SB头部，并且它对D-Cache的操作已完成，就可以从SB中弹出。
        val flushDone = popHeadSlot.isFlush && popHeadSlot.valid && operationDone

        // 普通Store的弹出条件不变，仍然需要isCommitted
        val normalStoreDone = !popHeadSlot.isFlush && popHeadSlot.valid && popHeadSlot.isCommitted && operationDone && !popHeadSlot.hasEarlyException && !popHeadSlot.isIO

        // MMIO Store的弹出条件：需要isCommitted且操作已完成
        val mmioStoreDone = popHeadSlot.valid && popHeadSlot.isCommitted && popHeadSlot.isIO && !popHeadSlot.hasEarlyException && operationDone

        val earlyExcStoreDone = popHeadSlot.valid && popHeadSlot.isCommitted && popHeadSlot.hasEarlyException
        
        val isSbEmpty = !slots.map(_.valid).orR
        val popInvalidSlot = !isSbEmpty && !popHeadSlot.valid
        val popRequest = normalStoreDone || mmioStoreDone || earlyExcStoreDone || flushDone || popInvalidSlot

        report(L"[SQ] popRequest=${popRequest}, flushDone=${flushDone}, normalStoreDone=${normalStoreDone}, mmioStoreDone=${mmioStoreDone}, earlyExcStoreDone=${earlyExcStoreDone}")
        when(popRequest) {
            ParallaxSim.log(L"[SQ] POP: Popping slot 0 (robPtr=${popHeadSlot.robPtr}, isFlush=${popHeadSlot.isFlush})")
            for (i <- 0 until sbDepth - 1) {
                slotsNext(i) := slotsAfterUpdates(i + 1)
            }
            slotsNext(sbDepth - 1).setDefault()
        }

        // =============================================================================
        // >> 集成: Store-to-Load 转发逻辑 (SqQueryPort)
        // (Unchanged)
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
            ParallaxSim.debug(L"[SQ-Fwd] Query: valid=${query.valid} robPtr=${query.payload.robPtr} addr=${query.payload.address} size=${query.payload.size}")

            val forwardingResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
                val nextAcc = CombInit(acc)
                val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                val canForward = slot.valid && !slot.hasEarlyException &&
                                 isOlder(slot.robPtr, query.payload.robPtr) &&
                                 (loadWordAddr === storeWordAddr)
                when(slot.valid) {
                    // ParallaxSim.debug(L"[SQ-Fwd] Checking slot(rob=${slot.robPtr}, addr=${slot.addr}): canForward=${canForward} (isOlder=${isOlder(slot.robPtr, query.payload.robPtr)}, addrMatch=${loadWordAddr === storeWordAddr})")
                }
                // ParallaxSim.debug(L"[SQ-Fwd] Forwarding? slot=${slot.robPtr} (load=${loadWordAddr}, store=${storeWordAddr}) canForward=${canForward}")
                when(canForward) {
                    for (k <- 0 until dataWidthBytes) {
                        when(slot.be(k) && loadMask(k) && !acc.hitMask(k)) {
                            nextAcc.data(k*8, 8 bits) := slot.data(k*8, 8 bits)
                            nextAcc.hitMask(k)        := True
                        }
                    }
                }
                nextAcc
            }

            val allRequiredBytesHit = (forwardingResult.hitMask & loadMask) === loadMask
            rsp.data := forwardingResult.data
            val hasSomeOverlap = (forwardingResult.hitMask & loadMask).orR

            // --- 依赖关系检查 (Disambiguation) ---
            rsp.olderStoreHasUnknownAddress := False
            val potentialConflict = Bool()
            rsp.hit  := query.valid && allRequiredBytesHit
            ParallaxSim.debug(L"[SQ-Fwd] Forwarding? hit=${rsp.hit}, because query.valid=${query.valid}, allRequiredBytesHit=${allRequiredBytesHit}")
            potentialConflict := False
            for(slot <- slots) {
                val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)

                // 判断Store是否完全覆盖了Load所需的所有字节
                val isFullContainment = (slot.be & loadMask) === loadMask
                // 判断是否存在任何字节的重叠
                val hasSomeOverlap    = (slot.be & loadMask).orR
                // 部分重叠 = 有重叠 但 非完全覆盖
                val isPartialOverlap  = hasSomeOverlap && !isFullContainment

                val thisSlotConfigt = slot.valid && !slot.hasEarlyException &&
                     isOlder(slot.robPtr, query.payload.robPtr) &&
                     (loadWordAddr === storeWordAddr) &&
                     isPartialOverlap

                when(thisSlotConfigt) {
                    ParallaxSim.debug(L"[SQ-Fwd] Conflict detected: slot=${slot.robPtr} (load=${loadWordAddr}, store=${storeWordAddr})")
                    potentialConflict := True
                }
            }
            rsp.olderStoreMatchingAddress := query.valid && !allRequiredBytesHit && hasSomeOverlap

            when(query.valid && rsp.hit){
                ParallaxSim.debug(L"[SQ-Fwd] HIT: Forwarding to Load(rob=${query.payload.robPtr}) data=${rsp.data} from SB.")
            }

            when(query.valid) {
                // debug print
                ParallaxSim.debug(L"[SQ-Fwd] Query: ${query.payload.format}")
                ParallaxSim.debug(L"[SQ-Fwd] Rsp: ${rsp.format}")
            }
                ParallaxSim.debug(L"[SQ-Fwd] Result: hitMask=${forwardingResult.hitMask} (loadMask=${loadMask}), allHit=${allRequiredBytesHit}, finalRsp.hit=${rsp.hit}")

        }


        // --- 同周期流水线旁路逻辑 (Existing Logic) ---
        val bypassResult            = StoreBufferBypassData(pipelineConfig)
        val loadQueryBe             = MemAccessSize.toByteEnable(bypassQuerySz, bypassQueryAddr(log2Up(pipelineConfig.dataWidth.value/8)-1 downto 0), pipelineConfig.dataWidth.value / 8)
        val bypassInitial           = BypassAccumulator(pipelineConfig)
        bypassInitial.data.assignFromBits(B(0))
        bypassInitial.hitMask.assignFromBits(B(0))

        val finalBypassResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
            val nextAcc = CombInit(acc)
            when(slot.valid && !slot.hasEarlyException) {
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
        ParallaxSim.log(L"[SQ] bypassDataOut: valid=${bypassDataOut.valid}, data=${bypassDataOut.payload.data}, hit=${bypassDataOut.payload.hit}, hitMask=${bypassDataOut.payload.hitMask}")

        slots := slotsNext

        // 在StoreBufferPlugin中添加调试输出
        for (i <- 0 until sbDepth) {
        when(slots(i).valid) {
            ParallaxSim.log(L"[SQ-Debug] HeadSlot State: " :+
                L"valid=${slots(i).valid}, " :+
                L"isCommitted=${slots(i).isCommitted}, " :+
                L"isSentToDCache=${slots(i).isSentToDCache}, " :+
                L"dcacheOpPending=${slots(i).dcacheOpPending}, " :+
                L"isWaitingForRefill=${slots(i).isWaitingForRefill}, " :+
                L"isWaitingForWb=${slots(i).isWaitingForWb}, " :+
                L"hasEarlyException=${slots(i).hasEarlyException}, " :+
                L"isIO=${slots(i).isIO}, " :+
                L"isFlush=${slots(i).isFlush}"
            )
        }}
        // 监控DCache接口状态
        ParallaxSim.log(L"[SQ-Debug] DCache Interface: " :+
            L"cmd.valid=${storePortDCache.cmd.valid}, " :+
            L"cmd.ready=${storePortDCache.cmd.ready}, " :+
            L"rsp.valid=${storePortDCache.rsp.valid}, " :+
            L"canPopToDCache=${canPopToDCache}"
        )

        for(i <- 0 until sbDepth){
            when(slots(i).valid && !slots(i).isCommitted){
                ParallaxSim.log(L"[SQ-Debug] Slot ${i}: waiting for commit, robPtr=${slots(i).robPtr}")
            }
        }

        hw.dcacheServiceInst.release();
        hw.robServiceInst.release()
        hw.sgmbServiceOpt.foreach(_.release())
    }
}
