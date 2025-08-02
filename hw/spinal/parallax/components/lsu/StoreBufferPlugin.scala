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
    val isIO        = Bool()
    val isCoherent  = Bool()

    def format: Seq[Any] = {
        Seq(
            L"SqQuery(",
            L"robPtr=${robPtr},",
            L"address=${address},",
            L"isIO=${isIO},",
            L"isCoherent=${isCoherent},",
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

    def setDefault(): this.type = {
        hit := False
        data := 0
        olderStoreHasUnknownAddress := False
        olderStoreDataNotReady := False
        this
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
    val pc    = UInt(pCfg.pcWidth)
    val addr    = UInt(pCfg.pcWidth)
    val data    = Bits(pCfg.dataWidth)
    val be      = Bits(pCfg.dataWidth.value / 8 bits)
    val robPtr  = UInt(lsuCfg.robPtrWidth)
    val accessSize = MemAccessSize()
    val isFlush = Bool() // Bench Test only
    val isIO    = Bool()
    val isCoherent = Bool()
    val hasEarlyException = Bool()
    val earlyExceptionCode= UInt(pCfg.exceptionCodeWidth)
}

// StoreBufferSlot
case class StoreBufferSlot(val pCfg: PipelineConfig, val lsuCfg: LsuConfig, val dCacheParams: DataCacheParameters) extends Bundle with Formattable {
    val txid                = UInt(pCfg.transactionIdWidth bits)
    val isFlush             = Bool()
    val pc                  = UInt(pCfg.pcWidth)
    val addr                = UInt(pCfg.pcWidth)
    val data                = Bits(pCfg.dataWidth)
    val be                  = Bits(pCfg.dataWidth.value / 8 bits)
    val robPtr              = UInt(lsuCfg.robPtrWidth)
    val accessSize          = MemAccessSize()
    val isIO                = Bool()
    val isCoherent          = Bool()
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
        txid                := 0
        isFlush             := False
        valid               := False
        pc                  := 0
        addr                := 0
        data                := 0
        be                  := 0
        robPtr              := 0
        accessSize          := MemAccessSize.W
        isIO                := False
        isCoherent          := False
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

      def initFromCommand(command: StoreBufferPushCmd, transactionId: UInt): this.type = {
        this.txid                := transactionId
        this.isFlush             := command.isFlush
        this.pc                  := command.pc
        this.addr                := command.addr
        this.be                  := command.be
        this.robPtr              := command.robPtr
        this.accessSize          := command.accessSize
        this.isIO                := command.isIO
        this.isCoherent          := command.isCoherent
        this.hasEarlyException   := command.hasEarlyException
        this.earlyExceptionCode  := command.earlyExceptionCode
        this.valid               := True
        this.isCommitted         := False
        this.sentCmd      := False
        this.waitRsp     := False
        this.isWaitingForRefill  := False
        this.isWaitingForWb      := False
        this.refillSlotToWatch   := 0

        // Calculate and assign the aligned data
        val dataWidth = command.data.getWidth
        val dataWidthBytes = dataWidth / 8
        val byteOffset = command.addr(log2Up(dataWidthBytes)-1 downto 0)
        val shiftedData = command.data << (byteOffset << 3)

        this.data := shiftedData(dataWidth-1 downto 0)
        this
    }

    override def format: Seq[Any] = {
        Seq(
            L"StoreBufferSlot(",
            L"txid=${txid},",
            L"valid=${valid},",
            L"isFlush=${isFlush},",
            L"addr=${addr},",
            L"pc=${pc},",
            L"data=${data},",
            L"be=${be},",
            L"robPtr=${robPtr},",
            L"accessSize=${accessSize},",
            L"isIO=${isIO},",
            L"isCoherent=${isCoherent},",
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

    def setDefault(): this.type = { data := 0; hitMask := 0; this }
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
    val enableLog = false
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

        // =================================================================
        //  存储区域 (Storage Area)
        // =================================================================
        val storage = new Area {
            // 两层视图，避免环路
            // 本周期开始时的、稳定的、已知的状态。所有本周期的计算都应该以此为起点。
            val slots       = Vec.fill(sbDepth)(Reg(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)))
            // 用于本周期更新的组合逻辑视图,会根据各自的条件来覆盖这个默认值。这最终会生成一个大型的多路复用器（Mux）结构。
            val slotsAfterUpdates = CombInit(slots) 
            // 用于下一周期寄存器更新的视图, 只有那些需要改变队列结构的逻辑（比如 pop_write_logic 中的 popRequest）才会来覆盖这个值。
            val slotsNext   = CombInit(slotsAfterUpdates) // 默认继承本周期的组合逻辑更新

            for(i <- 0 until sbDepth) {
                slots(i).init(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
            }
        }

        // =================================================================
        //  辅助函数区域 (Helper Functions Area)
        // =================================================================
        val helper_functions = new Area {
            // ROB指针比较函数
            def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
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
            def isOlder(robPtrA: UInt, robPtrB: UInt): Bool = !isNewerOrSame(robPtrA, robPtrB)
        }

        // =================================================================
        //  ROB 交互与冲刷逻辑 (ROB Interaction and Flush Logic)
        // =================================================================
        val rob_interaction = new Area {
            // --- Flush 逻辑 (部分) ---
            val robFlushPort = robService.doRobFlush()
            val flushInProgressOnCommit = robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX
            val flushTargetRobPtr = robFlushPort.payload.targetRobPtr
            
            // 使用 RegNext 锁存 flush 请求，以避免组合逻辑环路
            // 确保 flush 执行与请求在不同周期
            val registeredFlush = RegNext(robFlushPort).init(robFlushPort.getZero)

            // --- Commit 逻辑 (核心修改) ---
            val commitInfoFromRob = robService.getCommitSlots(pipelineConfig.commitWidth)
            val commitPort = commitInfoFromRob(0)

            // 直接在本周期计算 commit 匹配掩码，不使用 RegNext
            val commitMatchMask = Bits(sbDepth bits)
            when(commitPort.canCommit && 
                 (commitPort.entry.payload.uop.decoded.uopCode === BaseUopCode.STORE) && 
                 !commitPort.entry.status.hasException) {
                
                // 使用 OHMasking.last 来正确处理 robPtr 复用的情况
                val candidates = B(storage.slots.map(s => s.valid && !s.isCommitted && s.robPtr === commitPort.entry.payload.uop.robPtr))
                commitMatchMask := OHMasking.last(candidates)
            } otherwise {
                commitMatchMask := 0
            }

            if(enableLog) {
                when(commitMatchMask.orR) {
                     report(L"[SQ] COMMIT_DETECT: robPtr=${commitPort.entry.payload.uop.robPtr} commitMatchMask=${commitMatchMask}")
                }
            }
            
            // --- 应用 Flush 和 Commit 更新 ---
            for(i <- 0 until sbDepth){
                // Flush 逻辑
                val toBeFlushedByRob = registeredFlush.valid && 
                                      (registeredFlush.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX) &&
                                      storage.slots(i).valid && 
                                      !storage.slots(i).isCommitted && 
                                      helper_functions.isNewerOrSame(storage.slots(i).robPtr, registeredFlush.payload.targetRobPtr)
                
                when(toBeFlushedByRob) {
                    storage.slotsAfterUpdates(i).valid := False
                    if(enableLog) report(L"[SQ] FLUSH (Exec): Invalidating slotIdx=${i} (robPtr=${storage.slots(i).robPtr}) by ROB flush.")
                } .elsewhen(storage.slots(i).valid && !storage.slots(i).isCommitted) {
                    // Commit 逻辑 (直接使用本周期的 commitMatchMask)
                    when(commitMatchMask(i)) {
                        storage.slotsAfterUpdates(i).isCommitted := True
                        if(enableLog) report(L"[SQ] COMMIT_EXEC: robPtr=${storage.slots(i).robPtr} (slotIdx=${i}) marked as committed.")
                    }
                }
            }

            // 全局冲刷逻辑
            when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                if(enableLog) report(L"[SQ] FULL_FLUSH received. Processing slots.")
                for(i <- 0 until sbDepth) {
                    // (1) 读取本周期中间组合逻辑视图，这里包含了同周期Commit的最新状态
                    val slotToCheck = storage.slotsAfterUpdates(i)

                    // (2) 【核心原则】: 只清除未提交的条目。
                    // 已提交的条目(isCommitted=True)是架构状态的一部分，必须被保留下来等待完成。
                    // 无论是普通内存写还是MMIO写，一旦提交，就必须执行。
                    // 这种做法可以防止数据丢失，并正确处理了另一个AI指出的“僵尸问题”——我们选择让“僵尸”寿终正寝，而不是杀死它。
                    when(!slotToCheck.isCommitted) {
                        // (3) 写入下一周期的状态视图 `slotsNext`，将其无效化。
                        // 使用 setDefault() 是最彻底的清理方式，确保所有状态位都被重置。
                        storage.slotsNext(i).setDefault()
                        if(enableLog) report(L"[SQ] FLUSH (Full): Invalidating UNCOMMITTED slotIdx=${i} (robPtr=${slotToCheck.robPtr}).")
                    }
                }
            }
        }

        // =================================================================
        //  Store Buffer 推入逻辑 (SB Push Logic)
        // =================================================================
        val push_logic = new Area {
            // --- 对空洞免疫的 Push 逻辑 ---
            val usageMask = B(storage.slots.map(_.valid))
            
            // CountOne 返回一个 UInt，其位宽足以表示 sbDepth。
            // 例如 sbDepth=8, CountOne 返回 UInt(4 bits)。
            val entryCount = CountOne(usageMask)

            // tailPtr 的值范围是 0 到 sbDepth。
            // 因此它的位宽必须是 log2Up(sbDepth + 1)。
            // entryCount 已经具有正确的位宽，所以我们直接使用它。
            val tailPtr = entryCount

            // isFull 的比较现在是安全的，因为 tailPtr 和 sbDepth 在比较时
            // SpinalHDL 会将 sbDepth (Int) 转换为与 tailPtr 位宽相同的 UInt。
            val isFull = (tailPtr === sbDepth)

            val canPush = !isFull && !rob_interaction.flushInProgressOnCommit
            pushPortIn.ready := canPush

            // pushIdx 的范围是 0 到 sbDepth-1。
            // 当 canPush 为 True 时，tailPtr 的值一定在 0 到 sbDepth-1 之间。
            // 因此，可以安全地将 tailPtr 赋值给 pushIdx，即使 pushIdx 的位宽可能更小。
            // SpinalHDL 的 .resized 会截断高位，但在此处是安全的。
            // pushIdx 的位宽是 log2Up(sbDepth)，例如 sbDepth=8, 位宽是3。
            val pushIdx = tailPtr(log2Up(sbDepth)-1 downto 0)

            if(enableLog) {
                when(pushPortIn.valid) {
                    report(L"POISON_PILL_DEBUG: tailPtr=${tailPtr}, usageMask=${usageMask}, canPush=${canPush}")
                }
            }

            when(pushPortIn.fire) {
                val newSlotData = StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams)
                newSlotData.initFromCommand(pushPortIn.payload, U(0))
                // 使用 pushIdx (正确位宽) 进行索引
                storage.slotsAfterUpdates(pushIdx) := newSlotData
                if(enableLog) report(L"[SQ] PUSH: robPtr=${pushPortIn.payload.robPtr} to slotIdx=${pushIdx}")
            }
        }


        // =================================================================
        //  Store Buffer 弹出与内存写入逻辑 (SB Pop and Memory Write Logic)
        // =================================================================
        val pop_write_logic = new Area {
            // 【第1步：识别本周期的动作候选者】
            // 这些都是纯组合逻辑，判断每个槽位 *如果* 位于头部，是否满足条件
            val headSlotReg = storage.slots(0) // 读取上一周期的稳定状态
            val headSlotIsCommittedTC = storage.slotsAfterUpdates(0).isCommitted // 使用本周期更新后的 isCommitted

            // --- 发送决策 (Fire Decision) ---
            // 决策基于上一周期的稳定状态，但结合了本周期的Commit信息
            val isHeadReadyToSend = headSlotReg.valid && headSlotIsCommittedTC && !headSlotReg.isFlush &&
                                   !headSlotReg.sentCmd && // 尚未发送命令
                                   !headSlotReg.waitRsp && !headSlotReg.isWaitingForRefill && !headSlotReg.isWaitingForWb &&
                                   !headSlotReg.hasEarlyException // 仅当没有早期异常时才尝试发送
            
            val canSendToMMIO = isHeadReadyToSend && headSlotReg.isIO
            when(Bool(!mmioConfig.isDefined) && headSlotReg.isIO) {
                assert(False, "Got isIO but no MMIO service.")
            }
            if(enableLog) report(Seq(L"[SQ] isHeadReadyToSend = ${isHeadReadyToSend} of headslot: ${headSlotReg.format}", L"canSendToMMIO = ${canSendToMMIO}. "))
            
            // --- 连接MMIO接口 ---
            // 实际的发送命令。这里的数据仍然来自上一周期的稳定状态
            val mmioCmdFired = hw.mmioWriteChannel.map { mmioChannel =>
                mmioChannel.cmd.valid := canSendToMMIO
                mmioChannel.cmd.address := headSlotReg.addr
                mmioChannel.cmd.data := headSlotReg.data
                mmioChannel.cmd.byteEnables := headSlotReg.be
                mmioChannel.cmd.last := True
                if (mmioConfig.get.useId) {
                    require(mmioChannel.cmd.id.getWidth >= headSlotReg.robPtr.getWidth, "MMIO ID width must be at least as wide as ROB pointer width")
                    mmioChannel.cmd.id := headSlotReg.robPtr.resized
                }
                when(mmioChannel.cmd.valid) {
                    if(enableLog) report(L"[SQ-MMIO] Sending MMIO STORE: payload=${mmioChannel.cmd.payload.format} headslot=${headSlotReg.format} valid=${mmioChannel.cmd.valid} ready=${mmioChannel.cmd.ready}")
                }
                mmioChannel.cmd.ready && canSendToMMIO
            }.getOrElse(False)
            if(enableLog) report(L"[SQ] mmioCmdFired=${mmioCmdFired} because canSendToMMIO=${canSendToMMIO}, channel.cmd.ready=${hw.mmioWriteChannel.map(_.cmd.ready).getOrElse(null)}")

            // --- 响应决策 (Response Decision) ---
            val mmioResponseForHead = hw.mmioWriteChannel.map { mmioChannel =>
                mmioChannel.rsp.valid && headSlotReg.valid && headSlotReg.isIO && headSlotReg.waitRsp && // 必须是MMIO事务，且正在等待响应
                (headSlotReg.robPtr.resized === mmioChannel.rsp.payload.id) // ID匹配
            }.getOrElse(False)
            
            // MMIO响应总线
            hw.mmioWriteChannel.foreach(_.rsp.ready := mmioResponseForHead)
            getServiceOption[DebugDisplayService].foreach(dbg => { 
                dbg.setDebugValueOnce(mmioResponseForHead, DebugValue.MEM_WRITE_FIRE, expectIncr = true)
            })

            // --- 弹出决策 (Pop Decision) ---
            // 弹出决策基于上一周期的稳定状态 + 本周期的响应
            val operationDone = headSlotReg.sentCmd && 
                                ((!headSlotReg.waitRsp) || mmioResponseForHead) && // 如果在等响应，那必须本周期收到
                                !headSlotReg.isWaitingForRefill && !headSlotReg.isWaitingForWb

            val popRequest = False
            when(headSlotReg.valid && headSlotIsCommittedTC) {
                when(headSlotReg.isFlush) {
                    when(operationDone) { 
                        popRequest := True
                        report(L"[SQ] POP_FLUSH: robPtr=${headSlotReg.robPtr}")
                    }
                } elsewhen (headSlotReg.hasEarlyException) { // 早期异常的Store，一旦提交即可弹出
                    popRequest := True
                    report(L"[SQ] POP_EARLY_EXCEPTION: robPtr=${headSlotReg.robPtr}")
                } otherwise {
                    when(operationDone) { 
                        popRequest := True
                        report(L"[SQ] POP_NORMAL_STORE/MMIO: robPtr=${headSlotReg.robPtr}, isIO=${headSlotReg.isIO}")
                    }
                }
            }

            // 【第2步：执行动作并更新下一周期状态】
            // 将所有更新逻辑集中到 `slotsNext` 的生成中，打断时序路径
            when(popRequest) {
                // 如果弹出，就移位
                for (i <- 0 until sbDepth - 1) {
                    // 注意：我们从 `storage.slotsAfterUpdates` 移位，因为它包含了最新的 commit 信息
                    storage.slotsNext(i) := storage.slotsAfterUpdates(i + 1)
                }
                storage.slotsNext(sbDepth - 1).setDefault()
                report(L"[SQ] POPPING HEAD: robPtr=${headSlotReg.robPtr}")
            } otherwise {
                // 如果不弹出，则继承本周期的组合逻辑更新
                storage.slotsNext := storage.slotsAfterUpdates

                // 并且，只对头部槽位应用本周期的动作后果
                when(mmioCmdFired) {
                    storage.slotsNext(0).sentCmd := True
                    storage.slotsNext(0).waitRsp := True
                    report(L"[SQ] CMD_FIRED_MMIO: robPtr=${headSlotReg.robPtr} (slotIdx=0), addr=${headSlotReg.addr} data=${headSlotReg.data} be=${headSlotReg.be}")
                }
                when(mmioResponseForHead) {
                    storage.slotsNext(0).waitRsp := False
                    val mmioError = hw.mmioWriteChannel.get.rsp.payload.error
                    when(mmioError) {
                        report(L"[SQ-MMIO] MMIO RSP_ERROR received for robPtr=${headSlotReg.robPtr}.")
                        // 标记为有异常以便处理和弹出
                        storage.slotsNext(0).hasEarlyException := True
                        storage.slotsNext(0).earlyExceptionCode := ExceptionCode.STORE_ACCESS_FAULT 
                    } otherwise {
                        report(L"[SQ-MMIO] MMIO RSP_SUCCESS received for robPtr=${headSlotReg.robPtr}.")
                    }
                }
            }
        }

        // =============================================================================
        //  Store-to-Load 转发逻辑 (SqQueryPort)
        // =============================================================================
        val forwarding_logic = new Area {
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

            // +++++++++++++++++++++++ START: 核心修改 +++++++++++++++++++++++
            // 创建一个组合逻辑的 slot 视图，它反映了本周期的所有更新
            val slotsView = Vec.fill(sbDepth)(StoreBufferSlot(pipelineConfig, lsuConfig, dCacheParams))
            for (i <- 0 until sbDepth) {
                // 如果这个 slot 是本周期 PUSH 的目标，使用最新的命令数据
                // 否则，使用经过其他逻辑（commit, pop等）更新后的组合逻辑视图
                when(pushPortIn.fire && (push_logic.pushIdx === i)) {
                    slotsView(i).initFromCommand(pushPortIn.payload, U(0))
                } otherwise {
                    // 【关键修复】: 使用 storage.slotsAfterUpdates 而不是 storage.slots
                    slotsView(i) := storage.slotsAfterUpdates(i) 
                }
            }
            // +++++++++++++++++++++++ END: 核心修改 +++++++++++++++++++++++
            
            // Store-to-Load 转发逻辑：遍历所有比 Load 旧的、有效的且未被冲刷的 Store。
            val forwardingResult = slotsView.reverse.foldLeft(bypassInitial) { (acc, slot) =>
                val nextAcc = CombInit(acc)
                
                // 一个槽位只有在它本身有效，并且它不是一个正在被冲刷的推测性条目时，才是可见的。
                // 此外，只有已提交或在转发时可用的Store（如在Commit后的Forwarding阶段）才可用于转发。
                // 但在SB，未Commit的Store也可以转发给Load，只要其地址已知且不等待Disambiguation。
                val isSlotVisibleAndForwardable = slot.valid && 
                                                  !slot.hasEarlyException &&
                                                  !slot.isFlush &&
                                                  helper_functions.isOlder(slot.robPtr, query.payload.robPtr) &&
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
                    val isOlderAndPotentiallyConflicting = helper_functions.isOlder(slot.robPtr, query.payload.robPtr)
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

        // =================================================================
        //  同周期流水线旁路逻辑 (Bypass Logic)
        // =================================================================
        val bypass_logic = new Area {
            val bypassResult            = StoreBufferBypassData(pipelineConfig)
            val loadQueryBe             = MemAccessSize.toByteEnable(bypassQuerySz, bypassQueryAddr(log2Up(pipelineConfig.dataWidth.value/8)-1 downto 0), pipelineConfig.dataWidth.value / 8)
            val bypassInitial           = BypassAccumulator(pipelineConfig)
            bypassInitial.data.assignFromBits(B(0))
            bypassInitial.hitMask.assignFromBits(B(0))

            val finalBypassResult = storage.slots.reverse.foldLeft(bypassInitial) { (acc, slot) =>
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
        }

        // =================================================================
        //  状态更新与调试区域 (State Update and Debug Area)
        // =================================================================
        val state_update_debug = new Area {
            storage.slots := storage.slotsNext

            // 调试日志
            for (i <- 0 until sbDepth) {
                when(storage.slots(i).valid) {
                    if(enableLog) report(L"[SQ-Debug] SlotState[${i}]: " :+
                        L"robPtr=${storage.slots(i).robPtr}, " :+
                        L"addr=${storage.slots(i).addr}, " :+
                        L"valid=${storage.slots(i).valid}, " :+
                        L"isCommitted=${storage.slots(i).isCommitted}, " :+
                        L"sentCmd=${storage.slots(i).sentCmd}, " :+
                        L"waitRsp=${storage.slots(i).waitRsp}, " :+
                        L"isWaitingForRefill=${storage.slots(i).isWaitingForRefill}, " :+
                        L"isWaitingForWb=${storage.slots(i).isWaitingForWb}, " :+
                        L"hasEarlyException=${storage.slots(i).hasEarlyException}, " :+
                        L"isIO=${storage.slots(i).isIO}, " :+
                        L"isFlush=${storage.slots(i).isFlush}"
                    )
                }
            }
            hw.mmioWriteChannel.foreach { mmioChannel =>
                if(enableLog) report(L"[SQ-Debug] MMIO Interface: " :+
                    L"cmd.valid=${mmioChannel.cmd.valid}, " :+
                    L"cmd.ready=${mmioChannel.cmd.ready}, " :+
                    L"rsp.valid=${mmioChannel.rsp.valid}, " :+
                    L"rsp.ready=${mmioChannel.rsp.ready}, " :+
                    L"canSendToMMIO=${pop_write_logic.canSendToMMIO}"
                )
            }
            
            hw.robServiceInst.release()
            hw.sgmbServiceOpt.foreach(_.release())
        }
    }
}
