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

// SqQuery
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

// SqQueryRsp
case class SqQueryRsp(lsuCfg: LsuConfig) extends Bundle with Formattable {
    val hit         = Bool() // 是否命中一个可以转发的store
    val data        = Bits(lsuCfg.dataWidth)

    // 依赖相关 (Disambiguation)
    val olderStoreHasUnknownAddress = Bool() // 是否存在一个更早的、地址未知的store
    val olderStoreDataNotReady = Bool() // 是否存在一个更早的、地址匹配但数据未就绪的store

    def format: Seq[Any] = {
        Seq(
            L"SqQueryRsp(",
            L"hit=${hit},",
            L"data=${data},",
            L"olderStoreHasUnknownAddress=${olderStoreHasUnknownAddress},",
            L"olderStoreDataNotReady=${olderStoreDataNotReady}",
            L")"
        )
    }
}

// SqQueryPort
case class SqQueryPort(lsuCfg: LsuConfig, pipelineCfg: PipelineConfig) extends Bundle with IMasterSlave {
    val cmd = Flow(SqQuery(lsuCfg, pipelineCfg))
    val rsp = SqQueryRsp(lsuCfg)
    override def asMaster(): Unit = { master(cmd); in(rsp) }
}

// StoreBufferPushCmd
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

// StoreBufferSlot
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

// BypassAccumulator
case class BypassAccumulator(pCfg: PipelineConfig) extends Bundle {
    val data    = Bits(pCfg.dataWidth)
    val hitMask = Bits(pCfg.dataWidth.value / 8 bits)
}

// StoreBufferBypassData
case class StoreBufferBypassData(val pCfg: PipelineConfig) extends Bundle {
    val hit     = Bool()
    val data    = Bits(pCfg.dataWidth)
    val hitMask = Bits(pCfg.dataWidth.value / 8 bits)
    def setDefault(): this.type = { hit := False; data := 0; hitMask := 0; this }
}

// StoreBufferService
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

        var sgmbServiceOpt: Option[SgmbService] = None
        var mmioWriteChannel: Option[SplitGmbWriteChannel] = None

        if (mmioConfig.isDefined) {
            val config = mmioConfig.get
            val sgmbService = getService[SgmbService]
            sgmbServiceOpt = Some(sgmbService)
            mmioWriteChannel = Some(sgmbService.newWritePort())
        }
        sgmbServiceOpt.foreach(_.retain())
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
        
        val robFlushPort = robService.doRobFlush()
        val flushInProgress = robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX
        val flushTargetRobPtr = robFlushPort.payload.targetRobPtr
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
        
        // --- SB Push Logic  ---
        val validFall = Vec(Bool(), sbDepth)
        validFall(0) := !slots(0).valid
        for(i <- 1 until sbDepth) { validFall(i) := slots(i-1).valid && !slots(i).valid }
        val canPush = validFall.orR && !flushInProgress
        pushPortIn.ready := canPush

        val pushIdx = OHToUInt(validFall)
        when(pushPortIn.fire) {
            val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
            newSlotData.initFromCommand(pushPortIn.payload)
            slotsAfterUpdates(pushIdx) := newSlotData
            if(enableLog) report(L"[SQ] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
        }
        

        val headSlot = slots(0)
        val sharedWriteCond = headSlot.valid && headSlot.isCommitted && !headSlot.isFlush &&
                            !headSlot.waitRsp && !headSlot.isWaitingForRefill && !headSlot.isWaitingForWb &&
                            !headSlot.hasEarlyException // 仅当没有早期异常时才尝试发送
        val canPopNormalOp = sharedWriteCond && !headSlot.isIO // 普通DCache写
        val canPopFlushOp = headSlot.valid && headSlot.isFlush && !headSlot.waitRsp && !headSlot.isWaitingForWb
        val canPopMMIOOp = sharedWriteCond && headSlot.isIO
        when(Bool(!mmioConfig.isDefined) && headSlot.isIO) {
            assert(False, "Got isIO but no MMIO service.")
        }
        val canSendToDCache = canPopNormalOp || canPopFlushOp
        val canSendToMMIO = canPopMMIOOp
        if(enableLog) report(Seq(L"[SQ] sharedWriteCond = ${sharedWriteCond} of headslot: ${headSlot.format}", L"canSendToDCache = ${canSendToDCache}. ", L"canSendToMMIO = ${canSendToMMIO}. "))
        val mmioWriteCmd = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.cmd.valid := canSendToMMIO
            mmioChannel.cmd.address := headSlot.addr
            mmioChannel.cmd.data := headSlot.data
            mmioChannel.cmd.byteEnables := headSlot.be
            mmioChannel.cmd.last := True
            if (mmioConfig.get.useId) {
                require(mmioChannel.cmd.id.getWidth >= headSlot.robPtr.getWidth, "MMIO ID width must be at least as wide as ROB pointer width")
                mmioChannel.cmd.id := headSlot.robPtr.resized
            }
            when(mmioChannel.cmd.valid) {
                if(enableLog) report(L"[SQ-MMIO] Sending MMIO STORE: payload=${mmioChannel.cmd.payload.format} headslot=${headSlot.format} valid=${mmioChannel.cmd.valid} ready=${mmioChannel.cmd.ready}")
            }
            mmioChannel.cmd
        }
        val mmioCmdFired = hw.mmioWriteChannel.map(channel => canSendToMMIO && channel.cmd.ready).getOrElse(False)
        if(enableLog) report(L"[SQ] mmioCmdFired=${mmioCmdFired} because canSendToMMIO=${canSendToMMIO}, channel.cmd.ready=${hw.mmioWriteChannel.map(_.cmd.ready).getOrElse(null)}")
        when(mmioCmdFired) {
            slotsAfterUpdates(0).sentCmd := True
            slotsAfterUpdates(0).waitRsp := True
            report(L"[SQ] CMD_FIRED_MMIO: robPtr=${slots(0).robPtr} (slotIdx=0), addr=${slots(0).addr} data=${slots(0).data} be=${slots(0).be}")
        }
        val mmioResponseForHead = hw.mmioWriteChannel.map { mmioChannel =>
            mmioChannel.rsp.valid && headSlot.valid && headSlot.isIO && // 必须是MMIO事务
            (headSlot.waitRsp || mmioCmdFired) && // 'mmioCmdFired' 确保响应是为当前发送的命令
            (headSlot.robPtr.resized === mmioChannel.rsp.payload.id) // ID匹配
        }.getOrElse(False)
        when(mmioResponseForHead) {
            hw.mmioWriteChannel.foreach { mmioChannel =>
                // MMIO 响应到达，操作完成 (MMIO 没有 redo)
                slotsAfterUpdates(0).waitRsp := False
                val mmioError = mmioChannel.rsp.payload.error
                when(mmioError) {
                    report(L"[SQ-MMIO] MMIO RSP_ERROR received for robPtr=${headSlot.robPtr}.")
                    // 标记为有异常以便处理和弹出
                    slotsAfterUpdates(0).hasEarlyException := True
                    slotsAfterUpdates(0).earlyExceptionCode := ExceptionCode.STORE_ACCESS_FAULT 
                } otherwise {
                     report(L"[SQ-MMIO] MMIO RSP_SUCCESS received for robPtr=${headSlot.robPtr}.")
                }
            }
        }
        getServiceOption[DebugDisplayService].foreach(dbg => { 
            dbg.setDebugValueOnce(mmioResponseForHead, DebugValue.MEM_WRITE_FIRE, expectIncr = true)
        })
        hw.mmioWriteChannel.foreach { mmioChannel =>
            mmioChannel.rsp.ready := mmioResponseForHead
        }
        val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
        case class CommitUpdateInfo() extends Bundle {
            val validMask = Bits(sbDepth bits)
            def setDefault(initValue: Int = 0): this.type = {
                validMask := initValue
                this
            }
        }
        val commitUpdateInfo = CommitUpdateInfo()
        commitUpdateInfo.validMask.clearAll()
        for(i <- 0 until sbDepth) {
            val slotIsCommittedThisCycle = False
            when(slots(i).valid && !slots(i).isCommitted) {
                for(j <- 0 until pipelineConfig.commitWidth){
                    when(commitInfoFromRob(j).canCommit &&
                         commitInfoFromRob(j).entry.payload.uop.robPtr === slots(i).robPtr &&
                         (commitInfoFromRob(j).entry.payload.uop.decoded.uopCode === BaseUopCode.STORE ||
                          /*commitInfoFromRob(j).entry.payload.uop.isFlush*/ False) &&
                         !commitInfoFromRob(j).entry.status.hasException) {
                        
                        slotIsCommittedThisCycle := True
                        report(L"[SQ] COMMIT_DETECT: robPtr=${slots(i).robPtr} (slotIdx=${i}) will be marked as committed next cycle.")
                    }
                }
            }
            commitUpdateInfo.validMask(i) := slotIsCommittedThisCycle
        }
        val registeredCommitUpdate = RegNext(commitUpdateInfo).init(CommitUpdateInfo().setDefault())
        for(i <- 0 until sbDepth){
            val toBeFlushedByRob = registeredFlush.valid && 
                                  slots(i).valid && 
                                  !slots(i).isCommitted && // 只冲刷未提交的
                                  isNewerOrSame(slots(i).robPtr, registeredFlush.targetRobPtr)
            when(toBeFlushedByRob) {
                slotsAfterUpdates(i).valid := False
                report(L"[SQ] FLUSH (Exec): Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr}) by ROB flush.")
            } .elsewhen(slots(i).valid && !slots(i).isCommitted) {
                when(registeredCommitUpdate.validMask(i)) {
                    slotsAfterUpdates(i).isCommitted := True
                    if(enableLog) report(L"[SQ] COMMIT_EXEC: robPtr=${slots(i).robPtr} (slotIdx=${i}) marked as committed based on registered info.")
                }
            }
        }
        when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                if(enableLog) report(L"[SQ] FULL_FLUSH received. Clearing all slots.")
                for(i <- 0 until sbDepth) {
                    slotsAfterUpdates(i).setDefault()
                }
        }
        val popHeadSlot = slotsAfterUpdates(0)
        if(enableLog) report("slotsAfterUpdates(0): " :+ popHeadSlot.format)
        val operationDone = popHeadSlot.sentCmd && !popHeadSlot.waitRsp && !popHeadSlot.isWaitingForRefill && !popHeadSlot.isWaitingForWb
        val popRequest = False
        when(popHeadSlot.valid && popHeadSlot.isCommitted) {
            when(popHeadSlot.isFlush) {
                when(operationDone) {
                    popRequest := True
                    report(L"[SQ] POP_FLUSH: robPtr=${popHeadSlot.robPtr}")
                }
            } elsewhen (popHeadSlot.hasEarlyException) {
                popRequest := True
                report(L"[SQ] POP_EARLY_EXCEPTION: robPtr=${popHeadSlot.robPtr}")
            } otherwise {
                when(operationDone) {
                    popRequest := True
                    report(L"[SQ] POP_NORMAL_STORE/MMIO: robPtr=${popHeadSlot.robPtr}, isIO=${popHeadSlot.isIO}")
                }
            }
        } 
        .elsewhen(!popHeadSlot.valid && !slots.tail.map(_.valid).orR) {
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
            else when(query.valid) {
                report(L"[SQ-Fwd] Query: valid=${query.valid} robPtr=${query.payload.robPtr} addr=${query.payload.address} size=${query.payload.size}")
            }

            // *** 修改开始 ***
            // 创建一个组合逻辑的 slot 视图，它反映了本周期的更新
            val slotsView = Vec.fill(sbDepth)(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams))
            for (i <- 0 until sbDepth) {
                // 如果这个 slot 是本周期 PUSH 的目标
                when(pushPortIn.fire && (pushIdx === i)) {
                    // 使用 pushPort 进来的新数据来构建视图
                    slotsView(i).initFromCommand(pushPortIn.payload)
                } otherwise {
                    // 否则，使用寄存器中的旧数据
                    slotsView(i) := slots(i) 
                }
            }
            
            // Store-to-Load 转发逻辑：遍历所有比 Load 旧的、有效的且未被冲刷的 Store。
            // 使用 slotsView 而不是 slots
            val forwardingResult = slotsView.reverse.foldLeft(bypassInitial) { (acc, slot) =>
            // *** 修改结束 ***
                val nextAcc = CombInit(acc)
                
                // 一个槽位只有在它本身有效，并且它不是一个正在被冲刷的推测性条目时，才是可见的。
                // 此外，只有已提交或在转发时可用的Store（如在Commit后的Forwarding阶段）才可用于转发。
                // 但在SB，未Commit的Store也可以转发给Load，只要其地址已知且不等待Disambiguation。
                val isSlotVisibleAndForwardable = slot.valid && 
                                                  !slot.hasEarlyException &&
                                                  !slot.isFlush &&
                                                  isOlder(slot.robPtr, query.payload.robPtr) &&
                                                  !slot.waitRsp && !slot.isWaitingForRefill && !slot.isWaitingForWb

                when(isSlotVisibleAndForwardable) {
                    val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                    val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                    val canForward = (loadWordAddr === storeWordAddr)
                    
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

            rsp.olderStoreHasUnknownAddress := False

            val dataNotReadyStall = Bool()
            dataNotReadyStall := False
            val hasOlderOverlappingStore = Bool()
            hasOlderOverlappingStore := False

            for(slot <- slotsView) {
                val isSlotActiveForDisambiguation = slot.valid &&
                                                    !slot.hasEarlyException &&
                                                    !slot.isFlush

                when(isSlotActiveForDisambiguation) {
                    val isOlderAndPotentiallyConflicting = isOlder(slot.robPtr, query.payload.robPtr)
                    val loadWordAddr = query.payload.address(pcAddrWidth-1 downto wordAddrBits)
                    val storeWordAddr = slot.addr(pcAddrWidth-1 downto wordAddrBits)
                    val isAddressOverlap = (loadWordAddr === storeWordAddr)

                    when(isOlderAndPotentiallyConflicting && isAddressOverlap) {
                        hasOlderOverlappingStore := True

                        when(slot.waitRsp || slot.isWaitingForRefill || slot.isWaitingForWb) {
                            report(L"[SQ-Fwd] STALL_DATA_NOT_READY (Slot Status): slot=${slot.robPtr} (LoadAddr(word)=${loadWordAddr}, StoreAddr(word)=${storeWordAddr})" :+
                            L"reason: waitRsp=${slot.waitRsp}, isWaitingForRefill=${slot.isWaitingForRefill}, isWaitingForWb=${slot.isWaitingForWb}")
                            dataNotReadyStall := True
                        }
                    }
                }
            }

            // 2. 计算 "数据覆盖不足" (Insufficient Coverage) 的 Stall 条件
            //    只有在 `query.valid` 且 `hasOlderOverlappingStore` 为 `true`
            //    且 `allRequiredBytesHit` 为 `false` 时才触发。
            val insufficientCoverageStall = query.valid && hasOlderOverlappingStore && !allRequiredBytesHit
            rsp.olderStoreDataNotReady := dataNotReadyStall || insufficientCoverageStall

            // A load hits if:
            // 1. It's a valid query.
            // 2. All required bytes are covered by forwarding from older, available stores.
            // 3. There are no older stores with unknown addresses (simplified to false).
            // 4. There are no older, overlapping stores whose data is still undetermined/not ready,
            //    OR if the available older stores do not fully cover the load.
            rsp.hit  := query.valid && allRequiredBytesHit && !rsp.olderStoreHasUnknownAddress && !rsp.olderStoreDataNotReady

            if(enableLog) report(L"[SQ-Fwd] Forwarding? hit=${rsp.hit}, because query.valid=${query.valid}, allRequiredBytesHit=${allRequiredBytesHit}, olderStoreHasUnknownAddress=${rsp.olderStoreHasUnknownAddress}, olderStoreDataNotReady=${rsp.olderStoreDataNotReady}")
            
            when(query.valid) {
                report(L"[SQ-Fwd] Query: ${query.payload.format}")
                report(L"[SQ-Fwd] Rsp: ${rsp.format}")
                report(L"[SQ-Fwd] Result: hitMask=${forwardingResult.hitMask} (loadMask=${loadMask}), allHit=${allRequiredBytesHit}, finalRsp.hit=${rsp.hit}")
            }
            if(enableLog) report(L"[SQ-Fwd] Result: hitMask=${forwardingResult.hitMask} (loadMask=${loadMask}), allHit=${allRequiredBytesHit}, finalRsp.hit=${rsp.hit}")
        }

        // --- 同周期流水线旁路逻辑 (Existing Logic) ---
        // (保持不变)
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
        if(enableLog) report(L"[SQ] bypassDataOut: valid=${bypassDataOut.valid}, data=${bypassDataOut.payload.data}, hit=${bypassDataOut.payload.hit}, hitMask=${bypassDataOut.payload.hitMask}")

        slots := slotsNext

        // ... (调试日志保持不变)
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
        hw.mmioWriteChannel.foreach { mmioChannel =>
            if(enableLog) report(L"[SQ-Debug] MMIO Interface: " :+
                L"cmd.valid=${mmioChannel.cmd.valid}, " :+
                L"cmd.ready=${mmioChannel.cmd.ready}, " :+
                L"rsp.valid=${mmioChannel.rsp.valid}, " :+
                L"rsp.ready=${mmioChannel.rsp.ready}, " :+
                L"canSendToMMIO=${canSendToMMIO}"
            )
        }
        
        hw.robServiceInst.release()
        hw.sgmbServiceOpt.foreach(_.release())
    }
}
