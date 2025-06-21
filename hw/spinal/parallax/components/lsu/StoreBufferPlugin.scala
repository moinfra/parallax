// filename: parallax/components/lsu/StoreBufferPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._


case class StoreBufferPushCmd(val pCfg: PipelineConfig, val lsuCfg: LsuConfig) extends Bundle {
    val addr    = UInt(pCfg.pcWidth)
    val data    = Bits(pCfg.dataWidth)
    val be      = Bits(pCfg.dataWidth.value / 8 bits)
    val robPtr  = UInt(lsuCfg.robPtrWidth)
    val accessSize = MemAccessSize()
    val isIO    = Bool()
    val hasEarlyException = Bool()
    val earlyExceptionCode= UInt(pCfg.exceptionCodeWidth)
}

case class StoreBufferSlot(val pCfg: PipelineConfig, val lsuCfg: LsuConfig) extends Bundle {
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

    def setDefault(): this.type = {
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
        this
    }

    def initFromCommand(command: StoreBufferPushCmd): this.type = {
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
}

class StoreBufferPlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val sbDepth: Int = 8
) extends Plugin with StoreBufferService with LockedImpl {

    val hw = create early new Area {
        val pushPortInst      = Stream(StoreBufferPushCmd(pipelineConfig, lsuConfig))
        val bypassQueryAddrIn = UInt(pipelineConfig.pcWidth)
        val bypassQuerySizeIn = MemAccessSize()
        val bypassDataOutInst = Flow(StoreBufferBypassData(pipelineConfig))
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


    val logic = create late new Area {
        lock.await()

        val robService    = hw.robServiceInst
        val dcacheService = hw.dcacheServiceInst
        
        // Connect our master port to the slave port provided by the service.
        // All internal logic now uses our own correctly-directed master port.
        val storePortDCache = hw.dCacheStorePort

        val pushPortIn    = hw.pushPortInst
        val bypassQueryAddr= hw.bypassQueryAddrIn
        val bypassQuerySz = hw.bypassQuerySizeIn
        val bypassDataOut = hw.bypassDataOutInst
        
        val slots       = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig)))
        val slotsAfterUpdates = CombInit(slots)
        val slotsNext   = CombInit(slotsAfterUpdates)

        for(i <- 0 until sbDepth) {
            slots(i).init(StoreBufferSlot(pipelineConfig, lsuConfig).setDefault())
        }

        val validFall = Vec(Bool(), sbDepth)
        validFall(0) := !slots(0).valid
        for(i <- 1 until sbDepth) { validFall(i) := slots(i-1).valid && !slots(i).valid }
        val canPush = validFall.orR
        pushPortIn.ready := canPush

        when(pushPortIn.fire) {
            val pushIdx = OHToUInt(validFall)
            val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig)
            newSlotData.initFromCommand(pushPortIn.payload)
            slotsAfterUpdates(pushIdx) := newSlotData
            ParallaxSim.log(L"[SB] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
        }

        val headSlot = slots(0)
        val canPopToDCache = headSlot.valid && headSlot.isCommitted &&
                             !headSlot.isSentToDCache && !headSlot.dcacheOpPending &&
                             !headSlot.hasEarlyException && !headSlot.isIO

        storePortDCache.cmd.valid := canPopToDCache
        storePortDCache.cmd.payload.assignDontCare()
        when(canPopToDCache) {
            storePortDCache.cmd.payload.address  := headSlot.addr
            storePortDCache.cmd.payload.data     := headSlot.data
            storePortDCache.cmd.payload.mask     := headSlot.be
            storePortDCache.cmd.payload.io       := False
            storePortDCache.cmd.payload.flush    := False
            storePortDCache.cmd.payload.flushFree:= False
            storePortDCache.cmd.payload.prefetch := False
            if(pipelineConfig.transactionIdWidth > 0) {
                storePortDCache.cmd.payload.id := headSlot.robPtr.resize(pipelineConfig.transactionIdWidth bits)
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
                slotsAfterUpdates(0).isSentToDCache := False
            }
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
                when(slots(i).valid && slots(i).robPtr >= flushedRobStartIdx && !slots(i).isCommitted){
                    slotsAfterUpdates(i).valid := False
                    ParallaxSim.log(L"[SB] FLUSH: Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr})")
                }
            }
        }
        
        val popHeadSlot         = slotsAfterUpdates(0)
        val normalStoreDone     = popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    popHeadSlot.isSentToDCache && !popHeadSlot.dcacheOpPending &&
                                    !popHeadSlot.hasEarlyException && !popHeadSlot.isIO
        val canPopIO            = popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    !popHeadSlot.isSentToDCache &&
                                    !popHeadSlot.dcacheOpPending &&
                                    !popHeadSlot.hasEarlyException && popHeadSlot.isIO
        val earlyExcStoreDone   = popHeadSlot.valid && popHeadSlot.isCommitted &&
                                    popHeadSlot.hasEarlyException

        val popRequest = normalStoreDone || canPopIO || earlyExcStoreDone

        when(popRequest) {
            ParallaxSim.log(L"[SB] POP: Popping slot 0 (robPtr=${popHeadSlot.robPtr})")
            for (i <- 0 until sbDepth - 1) {
                slotsNext(i) := slotsAfterUpdates(i + 1)
            }
            slotsNext(sbDepth - 1).setDefault()
        }

        val bypassResult            = StoreBufferBypassData(pipelineConfig)

        val dataWidthBytes          = pipelineConfig.dataWidth.value / 8
        val loadQueryBe             = MemAccessSize.toByteEnable(bypassQuerySz, bypassQueryAddr(log2Up(dataWidthBytes)-1 downto 0), dataWidthBytes)

        val bypassInitial           = BypassAccumulator(pipelineConfig)
        bypassInitial.data.assignFromBits(B(0))
        bypassInitial.hitMask.assignFromBits(B(0))

        // slots 是从 0 到 sbDepth-1 的，我们需要从最新的store开始检查，所以要 .reverse
        val finalBypassResult = slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
            // acc: 上一轮迭代的结果 (accumulator)
            // slot: 当前正在检查的 SB 槽位 (slots(i))

            // 为本轮迭代创建一个新的累加器，初始值为上一轮的结果
            val nextAcc = BypassAccumulator(pipelineConfig)
            nextAcc := acc

            when(slot.valid && !slot.hasEarlyException) {
                val loadWordAddr = bypassQueryAddr(pipelineConfig.pcWidth.value-1 downto log2Up(dataWidthBytes))
                val storeWordAddr= slot.addr(pipelineConfig.pcWidth.value-1 downto log2Up(dataWidthBytes))

                when(loadWordAddr === storeWordAddr) {
                    for (k <- 0 until dataWidthBytes) {
                        // 如果当前store的这个字节是有效的，并且load也需要它
                        when(slot.be(k) && loadQueryBe(k)) {
                            // 更新 *本轮* 累加器的值
                            // 注意：我们只在需要时覆盖，之前更新的值会从acc继承下来
                            nextAcc.data(k*8, 8 bits) := slot.data(k*8, 8 bits)
                            nextAcc.hitMask(k)        := True
                        }
                    }
                }
            }
            // 返回本轮迭代更新后的累加器，供下一轮使用
            nextAcc
        }

        // foldLeft 结束后，finalBypassResult 就包含了最终的转发结果
        val finalHitMask = finalBypassResult.hitMask
        val overallBypassHit = finalHitMask.orR // 只要有任何一个字节命中，就认为有部分命中

        bypassDataOut.valid            := overallBypassHit
        bypassDataOut.payload.data     := finalBypassResult.data
        bypassDataOut.payload.hitMask  := finalHitMask
        bypassDataOut.payload.hit      := overallBypassHit && (finalHitMask === loadQueryBe)

        slots := slotsNext

        hw.dcacheServiceInst.release();
        hw.robServiceInst.release()
    }
}
