// filename: parallax/components/lsu/StoreBufferPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._

case class SqQuery(lsuCfg: LsuConfig, pipelineCfg: PipelineConfig) extends Bundle {
    val robPtr      = UInt(pipelineCfg.robPtrWidth) // ESSENTIAL for ordering
    val address     = UInt(lsuCfg.pcWidth)
    val size        = MemAccessSize()
}

case class SqQueryRsp(lsuCfg: LsuConfig) extends Bundle {
    val hit         = Bool() // 是否命中一个可以转发的store
    val data        = Bits(lsuCfg.dataWidth)

    // 依赖相关 (Disambiguation)
    val olderStoreHasUnknownAddress = Bool() // 是否存在一个更早的、地址未知的store
    val olderStoreMatchingAddress = Bool() // 是否存在一个更早的、地址匹配但数据未就绪的store
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

trait StoreBufferService extends Service {
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
    val sbDepth: Int
) extends Plugin with StoreBufferService with LockedImpl {

    val hw = create early new Area {
        val pushPortInst      = Stream(StoreBufferPushCmd(pipelineConfig, lsuConfig))
        val bypassQueryAddrIn = UInt(pipelineConfig.pcWidth)
        val bypassQuerySizeIn = MemAccessSize()
        val bypassDataOutInst = Flow(StoreBufferBypassData(pipelineConfig))
        val sqQueryPort       = slave(SqQueryPort(lsuConfig, pipelineConfig))
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val dCacheStorePort = dcacheServiceInst.newStorePort();
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
        
        // >> 修改 3: 实例化Slot时传入D-Cache参数
        val slots       = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
        val slotsAfterUpdates = CombInit(slots)
        val slotsNext   = CombInit(slotsAfterUpdates)

        for(i <- 0 until sbDepth) {
            slots(i).init(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
        }

        private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
            val genA = robPtrA.msb
            val idxA = robPtrA(robPtrA.high - 1 downto 0)
            val genB = robPtrB.msb
            val idxB = robPtrB(robPtrB.high - 1 downto 0)
            (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
        }
        private def isOlder(robPtrA: UInt, robPtrB: UInt): Bool = !isNewerOrSame(robPtrA, robPtrB)

        // --- SB Push/Pop/Commit/Flush Logic (Existing Logic) ---
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
            ParallaxSim.log(L"[SB] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
        }

        val headSlot = slots(0)
        val canPopToDCache = headSlot.valid && headSlot.isCommitted &&
                             !headSlot.isSentToDCache && !headSlot.dcacheOpPending &&
                             !headSlot.isWaitingForRefill && // << 不能在等待Refill时发送
                             !headSlot.hasEarlyException && !headSlot.isIO

        storePortDCache.cmd.valid := canPopToDCache
        storePortDCache.cmd.payload.assignDontCare()
        when(canPopToDCache) {
            // 根据 headSlot.isFlush 来设置不同的payload
            when(headSlot.isFlush) {
                // 这是一个 FLUSH 命令
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
            }
        }
        
        val dcacheCmdFired = canPopToDCache && storePortDCache.cmd.ready
        when(dcacheCmdFired) {
            slotsAfterUpdates(0).isSentToDCache  := True
            slotsAfterUpdates(0).dcacheOpPending := True
            ParallaxSim.log(L"[SB] SEND_TO_DCACHE: robPtr=${headSlot.robPtr} (slotIdx=0), addr=${headSlot.addr}, data=${headSlot.data}, be=${headSlot.be}")
        }

        val dcacheRspArrived = storePortDCache.rsp.valid && slots(0).valid && slots(0).dcacheOpPending
        when(dcacheRspArrived) {
            slotsAfterUpdates(0).dcacheOpPending := False
            when(storePortDCache.rsp.payload.redo) {
                slotsAfterUpdates(0).isWaitingForRefill := True
                slotsAfterUpdates(0).refillSlotToWatch  := storePortDCache.rsp.payload.refillSlot
                ParallaxSim.log(L"[SB] REDO received, entering WAIT_FOR_REFILL for robPtr=${slots(0).robPtr}, watching slot=${storePortDCache.rsp.payload.refillSlot}")
            }
        }

        val refillCompletionsFromDCache = dcacheService.getRefillCompletions()
        ParallaxSim.log(L"[SB] Watching... refillCompletionsFromDCache=${refillCompletionsFromDCache}")
        val waitedRefillIsDone = slots(0).valid && slots(0).isWaitingForRefill && 
                                 (slots(0).refillSlotToWatch & refillCompletionsFromDCache).orR

        when(waitedRefillIsDone) {
            // Refill 完成，我们可以准备重试了！
            // 将状态恢复到可以再次发送的状态
            slotsAfterUpdates(0).isWaitingForRefill := False
            slotsAfterUpdates(0).isSentToDCache     := False // 允许重新发送
            ParallaxSim.log(L"[SB] REFILL_DONE observed for robPtr=${slots(0).robPtr}. Ready to retry.")
        }

        val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
        val commitAcksFromRob = robService.getCommitAcks(pipelineConfig.commitWidth)
        for(i <- 0 until sbDepth){
            when(slots(i).valid && !slots(i).isCommitted){
                for(j <- 0 until pipelineConfig.commitWidth){
                    when(commitInfoFromRob(j).valid && commitAcksFromRob(j) &&
                         commitInfoFromRob(j).entry.payload.uop.robPtr === slots(i).robPtr &&
                         commitInfoFromRob(j).entry.payload.uop.decoded.uopCode === BaseUopCode.STORE &&
                         !commitInfoFromRob(j).entry.status.hasException) {
                        slotsAfterUpdates(i).isCommitted := True
                        ParallaxSim.log(L"[SB] COMMIT_SIGNAL: robPtr=${slots(i).robPtr} (slotIdx=${i}) marked as committed.")
                    }
                }
            }
        }

        val robFlushPort = robService.getFlushPort()
        when(robFlushPort.valid) {
            val flushedRobStartIdx = robFlushPort.payload.targetRobPtr
            ParallaxLogger.warning(s"[SB] FLUSH received from ROB: targetRobPtr=${flushedRobStartIdx}")
            for(i <- 0 until sbDepth){
                when(slots(i).valid && !slots(i).isCommitted && isNewerOrSame(slots(i).robPtr, flushedRobStartIdx)){
                    slotsAfterUpdates(i).valid := False
                    ParallaxSim.log(L"[SB] FLUSH: Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr})")
                }
            }
        }
        
        val popHeadSlot         = slotsAfterUpdates(0)

        // 为 flush 指令定义一个完成条件
        val flushDone           = popHeadSlot.isFlush && popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    popHeadSlot.isSentToDCache && !popHeadSlot.dcacheOpPending &&
                                    !popHeadSlot.isWaitingForRefill &&
                                    !popHeadSlot.hasEarlyException

        val normalStoreDone     = !popHeadSlot.isFlush && popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    popHeadSlot.isSentToDCache && !popHeadSlot.dcacheOpPending &&
                                    !popHeadSlot.isWaitingForRefill && 
                                    !popHeadSlot.hasEarlyException && !popHeadSlot.isIO
        val canPopIO            = popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    !popHeadSlot.isSentToDCache &&
                                    !popHeadSlot.dcacheOpPending &&
                                    !popHeadSlot.isWaitingForRefill && // << 也需要在pop条件里检查
                                    !popHeadSlot.hasEarlyException && popHeadSlot.isIO
        val earlyExcStoreDone   = popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    popHeadSlot.hasEarlyException

        // 将 flushDone 添加到 popRequest 中
        val popRequest = normalStoreDone || canPopIO || earlyExcStoreDone || flushDone
        report(L"[SB] popRequest=${popRequest}, flushDone=${flushDone}, normalStoreDone=${normalStoreDone}, canPopIO=${canPopIO}, earlyExcStoreDone=${earlyExcStoreDone}")
        when(popRequest) {
            ParallaxSim.log(L"[SB] POP: Popping slot 0 (robPtr=${popHeadSlot.robPtr}, isFlush=${popHeadSlot.isFlush})")
            for (i <- 0 until sbDepth - 1) {
                slotsNext(i) := slotsAfterUpdates(i + 1)
            }
            slotsNext(sbDepth - 1).setDefault()
        }

        // =============================================================================
        // >> 集成: Store-to-Load 转发逻辑 (SqQueryPort)
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

            val forwardingResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
                val nextAcc = CombInit(acc)
                val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                val canForward = slot.valid && !slot.hasEarlyException &&
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
                nextAcc
            }

            val allRequiredBytesHit = (forwardingResult.hitMask & loadMask) === loadMask
            rsp.hit  := query.valid && allRequiredBytesHit
            rsp.data := forwardingResult.data

            // --- 依赖关系检查 (Disambiguation) ---
            rsp.olderStoreHasUnknownAddress := False
            val potentialConflict = Bool()
            potentialConflict := False
            for(slot <- slots) {
                val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)

                // =================================================================
                // >> 语法修正处 <<
                // =================================================================
                // 判断Store是否完全覆盖了Load所需的所有字节
                val isFullContainment = (slot.be & loadMask) === loadMask
                // 判断是否存在任何字节的重叠
                val hasSomeOverlap    = (slot.be & loadMask).orR
                // 部分重叠 = 有重叠 但 非完全覆盖
                val isPartialOverlap  = hasSomeOverlap && !isFullContainment
                // =================================================================

                when(slot.valid && !slot.hasEarlyException &&
                     isOlder(slot.robPtr, query.payload.robPtr) &&
                     (loadWordAddr === storeWordAddr) &&
                     isPartialOverlap) {
                    potentialConflict := True
                }
            }
            rsp.olderStoreMatchingAddress := query.valid && !rsp.hit && potentialConflict

            when(query.valid && rsp.hit){
                ParallaxSim.debug(L"[SB-Fwd] HIT: Forwarding to Load(rob=${query.payload.robPtr}) data=${rsp.data} from SB.")
            }
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


        slots := slotsNext

        hw.dcacheServiceInst.release();
        hw.robServiceInst.release()
    }
}
