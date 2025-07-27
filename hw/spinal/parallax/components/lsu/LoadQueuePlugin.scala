// filename: parallax/components/lsu/LoadQueuePlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.execute.WakeupService
import parallax.components.rename.BusyTableService

// --- 输入到Load Queue的命令 ---
// 这个命令由LsuEu在地址计算后发出
case class LoadQueuePushCmd(pCfg: PipelineConfig, lsuCfg: LsuConfig) extends Bundle {
  val robPtr            = UInt(lsuCfg.robPtrWidth)
  val pdest             = UInt(pCfg.physGprIdxWidth)
  val address           = UInt(lsuCfg.pcWidth)
  val isIO              = Bool()
  val size              = MemAccessSize()
  val isSignedLoad      = Bool()
  val hasEarlyException = Bool()
  val earlyExceptionCode = UInt(8 bits)
}

// --- Load Queue 服务接口 ---
trait LoadQueueService extends Service with LockedImpl {
  def newPushPort(): Stream[LoadQueuePushCmd]
}

// LoadQueueSlot 保持不变，但我们移除里面不再需要的字段（如baseReg, immediate等）
// 因为地址已经在LsuEu中计算好了
case class LoadQueueSlot(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheParams: DataCacheParameters) extends Bundle with Formattable {
    val valid             = Bool()
    val address           = UInt(lsuCfg.pcWidth)
    val size              = MemAccessSize()
    val robPtr            = UInt(lsuCfg.robPtrWidth)
    val pdest             = UInt(pCfg.physGprIdxWidth)
    val isIO              = Bool()
    val isSignedLoad      = Bool()

    val hasException      = Bool()
    val exceptionCode     = UInt(8 bits)

    val isWaitingForFwdRsp    = Bool()
    val isStalledByDependency = Bool()
    val isReadyForDCache      = Bool() // 可以安全地向数据缓存（D-Cache）发出加载请求。
    val isWaitingForRsp = Bool()
    
    def setDefault(): this.type = {
        this.valid                 := False
        this.address               := 0
        this.size                  := MemAccessSize.W
        this.robPtr                := 0
        this.pdest                 := 0
        this.isIO                  := False
        this.isSignedLoad          := False
        this.hasException          := False
        this.exceptionCode         := 0
        this.isWaitingForFwdRsp    := False
        this.isStalledByDependency := False
        this.isReadyForDCache      := False
        this.isWaitingForRsp := False
        this
    }

    def initFromPushCmd(cmd: LoadQueuePushCmd): this.type = {
        this.valid                 := True
        this.address               := cmd.address
        this.size                  := cmd.size
        this.robPtr                := cmd.robPtr
        this.pdest                 := cmd.pdest
        this.isIO                  := cmd.isIO
        this.isSignedLoad          := cmd.isSignedLoad
        this.hasException          := cmd.hasEarlyException
        this.exceptionCode         := cmd.earlyExceptionCode
        
        this.isWaitingForFwdRsp    := False // Will be set to true next cycle if it queries
        this.isStalledByDependency := False
        this.isReadyForDCache      := False // Will be set by forwarding logic or early exception handling
        this.isWaitingForRsp := False
        this
    }

    def initFromAguOutput(cmd: AguOutput): this.type = {
        this.valid                 := True
        // this.addrValid             := True
        this.address               := cmd.address
        this.size                  := cmd.accessSize
        this.robPtr                := cmd.robPtr
        this.pdest                 := cmd.physDst
        this.isIO                  := cmd.isIO
        this.isSignedLoad          := cmd.isSignedLoad
        
        // 这些字段在 AguRspCmd 中可能没有直接对应，但为了完整性，这里也进行初始化
        // 如果 AguRspCmd 提供了这些信息，可以根据需要进行映射
        // this.baseReg               := cmd.basePhysReg
        // this.immediate             := cmd.immediate
        // this.usePc                 := cmd.usePc
        // this.pc                    := cmd.pc

        this.hasException          := cmd.alignException
        this.exceptionCode         := ExceptionCode.LOAD_ADDR_MISALIGNED // 假设只有对齐异常
        
        this.isWaitingForFwdRsp    := False
        this.isStalledByDependency := False
        this.isReadyForDCache      := False
        this.isWaitingForRsp := False
        this
    }

    // format 方法为了调试可以保留
    def format: Seq[Any] = {
        Seq(
            L"LQSlot(valid=${valid}, " :+
            L"address=${address}, " :+
            L"size=${size}, " :+
            L"robPtr=${robPtr}, " :+
            L"pdest=${pdest}, " :+
            L"isIO=${isIO}, " :+
            L"isSignedLoad=${isSignedLoad}, " :+
            L"hasException=${hasException}, " :+
            L"isWaitingForFwdRsp=${isWaitingForFwdRsp}, " :+
            L"isStalledByDependency=${isStalledByDependency}, " :+
            L"isReadyForDCache=${isReadyForDCache}, " :+
            L"isWaitingForRsp=${isWaitingForRsp})"
        )
    }
}

// =========================================================
// >> Load Queue 插件主体 (负责加载指令的执行和写回)
// =========================================================
class LoadQueuePlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters,
    val lqDepth: Int = 16,
    val mmioConfig: Option[GenericMemoryBusConfig] = None
) extends Plugin with LoadQueueService {

    private val pushPorts = ArrayBuffer[Stream[LoadQueuePushCmd]]()
    override def newPushPort(): Stream[LoadQueuePushCmd] = {
        this.framework.requireEarly()
        val port = Stream(LoadQueuePushCmd(pipelineConfig, lsuConfig))
        pushPorts += port
        port
    }

    val hw = create early new Area {
        val robServiceInst    = getService[ROBService[RenamedUop]]
        // val dcacheServiceInst = getService[DataCacheService]
        val prfServiceInst    = getService[PhysicalRegFileService]
        val storeBufferServiceInst = getService[StoreBufferService]
        val busyTableServiceInst = getService[BusyTableService] // 获取服务
        val hardRedirectService   = getService[HardRedirectService]

        // val busyTableClearPort = busyTableServiceInst.newClearPort() // 创建清除端口
        //dcachedisable val dCacheLoadPort   = dcacheServiceInst.newLoadPort(priority = 1)
        val robLoadWritebackPort = robServiceInst.newWritebackPort("LQ_Load")
        val prfWritePort     = prfServiceInst.newPrfWritePort(s"LQ.gprWritePort")
        val sbQueryPort      = storeBufferServiceInst.getStoreQueueQueryPort()
        val wakeupServiceInst = getService[WakeupService]
        val wakeupPort = wakeupServiceInst.newWakeupSource("LQ.wakeupPort")
        // ROB 刷新端口应该也会在硬重定向时触发，我们监听了ROB刷新，所以处理 doHardRedirect 应该是多余的
        val doHardRedirect = hardRedirectService.doHardRedirect()

        // MMIO支持：如果配置了MMIO，则创建SGMB读通道
        var sgmbServiceOpt: Option[SgmbService] = None
        var mmioReadChannel: Option[SplitGmbReadChannel] = None

        if (mmioConfig.isDefined) {
            val sgmbService = getService[SgmbService]
            sgmbServiceOpt = Some(sgmbService)
            mmioReadChannel = Some(sgmbService.newReadPort())
        }

        busyTableServiceInst.retain()
        sgmbServiceOpt.foreach(_.retain())
        robServiceInst.retain()
        //dcachedisable dcacheServiceInst.retain()
        prfServiceInst.retain()
        storeBufferServiceInst.retain()
        wakeupServiceInst.retain()
        hardRedirectService.retain()
    }

    val logic = create late new Area {
        lock.await()
        // 将所有push端口仲裁成一个
        val pushCmd = StreamArbiterFactory.roundRobin.on(pushPorts)

        // 从hw区域获取端口
        val sbQueryPort         = hw.sbQueryPort
        //dcachedisable val dCacheLoadPort      = hw.dCacheLoadPort
        val robLoadWritebackPort = hw.robLoadWritebackPort
        val prfWritePort        = hw.prfWritePort
        val robFlushPort        = hw.robServiceInst.doRobFlush()
        val wakeupPort          = hw.wakeupPort
        // // val busyTableClearPort  = hw.busyTableClearPort
        // Store Path Area is completely removed.

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

        // =========================================================
        // >> 加载队列区域 (Load Queue Area)
        // =========================================================
        val loadQueue = new Area {
            val slots = Vec.fill(lqDepth)(Reg(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams)))

            // +++ START: 新增代码 +++
            // 为不同来源的完成事件创建专用的锁存器
            case class CompletionInfo() extends Bundle {
                val valid = Bool()
                val fromFwd = Bool() // 来自Store转发
                val fromDCache = Bool() // 来自DCache
                val fromMMIO = Bool() // 来自MMIO
                val fromEarlyExc = Bool() // 来自早期异常
                
                // 携带需要的数据
                val data = Bits(pipelineConfig.dataWidth)
                val hasFault = Bool()
                val exceptionCode = UInt(8 bits)

                def setDefault(): this.type = {
                    valid := False
                    fromFwd := False
                    fromDCache := False
                    fromMMIO := False
                    fromEarlyExc := False
                    data.assignDontCare()
                    hasFault := False
                    exceptionCode.assignDontCare()
                    this
                }
            }

            val completionInfo = CompletionInfo()
            completionInfo.setDefault() // 设置默认值

            // 这个寄存器将锁存本周期发生的完成事件
            val completionInfoReg = RegNext(completionInfo).init(CompletionInfo().setDefault())
            // +++ END: 新增代码 +++

            val slotsAfterUpdates = CombInit(slots)
            val slotsNext   = CombInit(slotsAfterUpdates)
            
            val sbQueryRspReg = Reg(SqQueryRsp(lsuConfig))
            val sbQueryRspValid = RegNext(sbQueryPort.cmd.fire, init = False)
            when(sbQueryPort.cmd.fire) {
                sbQueryRspReg := sbQueryPort.rsp
            }
            
            // --- Flush Logic (moved here to define flushInProgress early) ---
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
            
            // 初始化和Flush逻辑几乎不变
            for(i <- 0 until lqDepth) {
                slots(i).init(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
            }
            when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                for(i <- 0 until lqDepth) slotsNext(i).setDefault()
            }
            
            // --- 1. LQ Push (from LsuEu) ---
            val canPush = !slots.map(_.valid).andR && !flushInProgress
            pushCmd.ready := canPush
            
            val availableSlotsMask = slots.map(!_.valid).asBits
            val pushOh = PriorityEncoderOH(availableSlotsMask)
            val pushIdx = OHToUInt(pushOh)
            when(pushCmd.fire) {
                slotsAfterUpdates(pushIdx).initFromPushCmd(pushCmd.payload)
                ParallaxSim.log(L"[LQ] PUSH from LsuEu: robPtr=${pushCmd.robPtr} addr=${pushCmd.address} to slotIdx=${pushIdx}")
            }

            // --- 2-6. 队列头部的处理逻辑 (Store Forwarding, D-Cache, Completion, Pop) ---
            val head = slots(0)
            val headIsValid = head.valid
            
            // 使用 isSlotVisible 来控制对头部槽位的所有外部交互
            val headIsVisible = head.valid && !(flushInProgress && isNewerOrSame(head.robPtr, flushTargetRobPtr))

            // --- Disambiguation ---
            val headIsReadyForFwdQuery = headIsVisible && !head.hasException &&
                                         !head.isWaitingForFwdRsp && !head.isStalledByDependency &&
                                         !head.isWaitingForRsp && !head.isReadyForDCache

            sbQueryPort.cmd.valid   := headIsReadyForFwdQuery
            sbQueryPort.cmd.address := head.address
            sbQueryPort.cmd.size    := head.size
            sbQueryPort.cmd.robPtr  := head.robPtr

            when(sbQueryPort.cmd.valid) {
                slotsAfterUpdates(0).isWaitingForFwdRsp := True
                ParallaxSim.log(L"[LQ-Fwd] QUERY: robPtr=${head.robPtr} addr=${head.address}")
            }
            
            when(head.isWaitingForFwdRsp && sbQueryRspValid) {
                val fwdRsp = sbQueryRspReg
                slotsAfterUpdates(0).isWaitingForFwdRsp := False
                ParallaxSim.log(
                    L"  Head ROB Ptr: ${head.robPtr}\n" :+
                    L"  Rsp Hit: ${fwdRsp.hit}, Rsp Stall: ${fwdRsp.olderStoreHasUnknownAddress || fwdRsp.olderStoreDataNotReady}"
                )
                when(fwdRsp.hit) {
                    // Completion logic is handled below by popOnFwdHit
                    ParallaxSim.log(L"[LQ-Fwd] HIT: robPtr=${head.robPtr}, data=${fwdRsp.data}. Will complete via popOnFwdHit.")
                } .elsewhen(fwdRsp.olderStoreHasUnknownAddress || fwdRsp.olderStoreDataNotReady) {
                    ParallaxSim.log(L"[LQ-Fwd] STALL: robPtr=${head.robPtr} has dependency...")
                    slotsAfterUpdates(0).isStalledByDependency := True
                } otherwise {
                    ParallaxSim.log(L"[LQ-Fwd] MISS: robPtr=${head.robPtr} is clear to access D-Cache or MMIO.")
                    slotsAfterUpdates(0).isReadyForDCache := True
                }
            }

            when(head.isStalledByDependency) {
                slotsAfterUpdates(0).isStalledByDependency := False
            }
            
            // --- Handle Early Exceptions ---
            // For instructions with early exceptions, skip forwarding and mark as ready for exception handling
            when(headIsVisible && head.hasException && !head.isReadyForDCache && !head.isWaitingForFwdRsp && !head.isWaitingForRsp) {
                slotsAfterUpdates(0).isReadyForDCache := True
                ParallaxSim.log(L"[LQ] Early exception for robPtr=${head.robPtr}, marking ready for exception handling")
            }
            
            // --- Cache/MMIO Interaction ---
            // 注意: isReadyForDCache 现在在入队时就设置好了，不再需要等待转发结果（除非转发失败）
            val headIsReadyToExecute = headIsVisible && head.isReadyForDCache && !head.isWaitingForRsp
            
            // 如果正在等待转发响应，或者转发命中，则不应该发送到DCache/MMIO
            val shouldNotSendToMemory = head.isWaitingForFwdRsp

            // DCache路径（非MMIO）
        // disabledcache    dCacheLoadPort.cmd.valid := headIsReadyToExecute && !head.hasException && !shouldNotSendToMemory && !head.isIO
        //disabledcache    dCacheLoadPort.cmd.virtual       := head.address
        //disabledcache    dCacheLoadPort.cmd.size          := MemAccessSize.toByteSizeLog2(head.size)
        //disabledcache    dCacheLoadPort.cmd.redoOnDataHazard := True
        //disabledcache    if(pipelineConfig.transactionIdWidth > 0) {
        //disabledcache         dCacheLoadPort.cmd.id       := head.robPtr.resize(pipelineConfig.transactionIdWidth)
        //disabledcache    }
        //disabledcache    dCacheLoadPort.translated.physical       := head.address
        //disabledcache    dCacheLoadPort.translated.abord          := head.hasException
        //disabledcache    dCacheLoadPort.cancels                   := 0
        //disabledcache    
        //disabledcache    when(dCacheLoadPort.cmd.fire) {
        //disabledcache        slotsAfterUpdates(0).isWaitingForRsp := True
        //disabledcache        slotsAfterUpdates(0).isReadyForDCache      := False
        //disabledcache        ParallaxSim.log(L"[LQ-DCache] SEND_TO_DCACHE: robPtr=${head.robPtr} addr=${head.address}")
        //disabledcache    }

            // MMIO路径
            val mmioReadCmd = hw.mmioReadChannel.map { mmioChannel =>
                mmioChannel.cmd.valid := headIsReadyToExecute && !head.hasException && !shouldNotSendToMemory && head.isIO
                when(headIsReadyToExecute && head.isIO) {
                    ParallaxSim.log(L"[LQ-MMIO] robPtr=${head.robPtr} head is ready but: head.hasException=${head.hasException}, shouldNotSendToMemory=${shouldNotSendToMemory}" :+
                    L", head.isIO=${head.isIO}, mmioChannel.cmd.ready=${mmioChannel.cmd.ready}")
                }
                mmioChannel.cmd.address := head.address
                if (mmioConfig.get.useId) {
                    require(mmioChannel.cmd.id.getWidth >= head.robPtr.getWidth, "MMIO ID width must be at least as wide as ROB pointer width")
                    mmioChannel.cmd.id := head.robPtr.resized
                }
                
                when(mmioChannel.cmd.fire) {
                    slotsAfterUpdates(0).isWaitingForRsp := True  // 复用这个状态
                    slotsAfterUpdates(0).isReadyForDCache      := False
                    ParallaxSim.log(L"[LQ-MMIO] SEND_TO_MMIO: robPtr=${head.robPtr} addr=${head.address}")
                }
                mmioChannel.cmd
            }

            // Track MMIO command firing for response correlation
            val mmioCmdFired = hw.mmioReadChannel.map(_.cmd.fire).getOrElse(False)

            // Add MMIO response identification logic similar to StoreBufferPlugin
            val mmioResponseIsForHead = hw.mmioReadChannel.map { mmioChannel =>
                mmioChannel.rsp.valid && head.valid && 
                (head.isWaitingForRsp || mmioCmdFired) && head.isIO
            }.getOrElse(False)

            // --- 4. Memory Response Handling ---

            // DCache Response Handling (for Redo)
            //dcachedisable when(dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForRsp && !head.isIO) {
            //dcachedisable     when(dCacheLoadPort.rsp.payload.redo) {
            //dcachedisable         slotsAfterUpdates(0).isWaitingForRsp := False
            //dcachedisable         slotsAfterUpdates(0).isReadyForDCache      := True 
            //dcachedisable         ParallaxSim.log(L"[LQ-DCache] REDO received for robPtr=${head.robPtr}")
            //dcachedisable     }
            //dcachedisable }

            // MMIO Response Handling (MMIO operations don't have redo)
            hw.mmioReadChannel.foreach { mmioChannel =>
                // mmioChannel.rsp.ready := head.valid && head.isIO && head.isWaitingForRsp
                mmioChannel.rsp.ready := head.valid && head.isIO
                // TODO: 调查 head.isWaitingForRsp
            }

            // --- Completion & Pop Logic ---

            // 1. (组合逻辑) 检测本周期是否有完成事件，并准备好要锁存的信息
            val popOnFwdHit = head.isWaitingForFwdRsp && sbQueryRspReg.hit
            //dcachedisable val popOnDCacheSuccess = dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForRsp && !head.isIO && !dCacheLoadPort.rsp.payload.redo
            val popOnDCacheSuccess = False
            val popOnMMIOSuccess = mmioResponseIsForHead
            val popOnEarlyException = head.valid && head.hasException && !head.isReadyForDCache
            val popRequest = popOnFwdHit || popOnDCacheSuccess || popOnMMIOSuccess || popOnEarlyException
            // when(popRequest) {
            //     ParallaxSim.log(
            //         L"--- LQ POP REQUEST --- Cycle=${perfCounter.value}\n" :+
            //         L"  Head ROB Ptr: ${head.robPtr}\n" :+
            //         L"  Triggers: \n" :+
            //         L"    - popOnFwdHit: ${popOnFwdHit}\n" :+
            //         L"    - popOnDCacheSuccess: ${popOnDCacheSuccess}\n" :+
            //         L"    - popOnMMIOSuccess: ${popOnMMIOSuccess}\n" :+
            //         L"    - popOnEarlyException: ${popOnEarlyException}\n" :+
            //         L"  Head State (at time of pop request):\n" :+
            //         L"    - valid: ${head.valid}, isWaitingForFwdRsp: ${head.isWaitingForFwdRsp}, isReadyForDCache: ${head.isReadyForDCache}, isWaitingForRsp: ${head.isWaitingForRsp}"
            //     )
            // }
            // 设置本周期要锁存的完成信息
            when(popOnFwdHit) {
                completionInfo.valid := True
                completionInfo.fromFwd := True
                completionInfo.data := sbQueryRspReg.data
                completionInfo.hasFault := False
            //dcachedisable } .elsewhen(popOnDCacheSuccess) {
            //dcachedisable     completionInfo.valid := True
            //dcachedisable     completionInfo.fromDCache := True
            //dcachedisable     completionInfo.data := dCacheLoadPort.rsp.payload.data
            //dcachedisable     completionInfo.hasFault := dCacheLoadPort.rsp.payload.fault
            //dcachedisable     completionInfo.exceptionCode := ExceptionCode.LOAD_ACCESS_FAULT
            } .elsewhen(popOnMMIOSuccess) {
                hw.mmioReadChannel.foreach { mmioChannel =>
                    completionInfo.valid := True
                    completionInfo.fromMMIO := True
                    completionInfo.data := mmioChannel.rsp.payload.data
                    completionInfo.hasFault := mmioChannel.rsp.payload.error
                    completionInfo.exceptionCode := ExceptionCode.LOAD_ACCESS_FAULT

                    report(L"[LQ-MMIO] MMIO RESPONSE: robPtr=${head.robPtr}, data=${mmioChannel.rsp.payload.data}, error=${mmioChannel.rsp.payload.error}")
                }
            } .elsewhen(popOnEarlyException) {
                completionInfo.valid := True
                completionInfo.fromEarlyExc := True
                completionInfo.hasFault := True
                completionInfo.exceptionCode := head.exceptionCode
            }

            // 2. (时序逻辑) 在下一周期，根据锁存的完成信息执行写回操作
            robLoadWritebackPort.setDefault()
            prfWritePort.valid   := False
            prfWritePort.address.assignDontCare()
            prfWritePort.data.assignDontCare()
            wakeupPort.valid := False
            wakeupPort.payload.physRegIdx.assignDontCare()
            // busyTableClearPort.valid := False
            // busyTableClearPort.payload.assignDontCare()

            // 从寄存器中读取上一周期的完成事件
            val completingHead = RegNext(head) // 同时锁存需要写回的head信息
            when(completionInfoReg.valid) {
                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := completingHead.robPtr
                robLoadWritebackPort.exceptionOccurred := completionInfoReg.hasFault
                robLoadWritebackPort.exceptionCodeIn := completionInfoReg.exceptionCode
                    // --- 数据扩展逻辑 ---
                    val rawData = completionInfoReg.data
                    val extendedData = Bits(pipelineConfig.dataWidth)
                    
                    // 根据大小和符号位进行选择性扩展
                    switch(completingHead.size) {
                        is(MemAccessSize.B) {
                            when(completingHead.isSignedLoad) {
                                extendedData := S(rawData(7 downto 0)).resize(pipelineConfig.dataWidth).asBits
                            } otherwise {
                                extendedData := U(rawData(7 downto 0)).resize(pipelineConfig.dataWidth).asBits
                            }
                        }
                        is(MemAccessSize.H) { // 假设未来支持半字
                            when(completingHead.isSignedLoad) {
                                extendedData := S(rawData(15 downto 0)).resize(pipelineConfig.dataWidth).asBits
                            } otherwise {
                                extendedData := U(rawData(15 downto 0)).resize(pipelineConfig.dataWidth).asBits
                            }
                        }
                        default { // 对于 MemAccessSize.W (字) 或更大，不需要扩展
                            extendedData := rawData
                        }
                    }
                robLoadWritebackPort.result := extendedData
                
                // 只有在没有故障/异常时才写回PRF并唤醒
                when(!completionInfoReg.hasFault) {
                    prfWritePort.valid   := True
                    prfWritePort.address := completingHead.pdest
                    prfWritePort.data    := extendedData
                    
                    wakeupPort.valid := True
                    wakeupPort.payload.physRegIdx := completingHead.pdest
                }
                
                // 无论成功与否，只要指令完成，就要清楚busybit，但是物理寄存器直到commit才回收
                // busyTableClearPort.valid := True
                // busyTableClearPort.payload := completingHead.pdest
            }

            // 3. (组合逻辑) LQ Pop的请求仍然是组合的，但执行被推迟
            when(popRequest&& !flushInProgress) {
                for (i <- 0 until lqDepth - 1) {
                    slotsNext(i) := slotsAfterUpdates(i + 1)
                }
                slotsNext(lqDepth - 1).setDefault()
            }
            
            // <<<<<<< 重构: 循环更新每个槽位，并确保冲刷优先 >>>>>>>
            for(i <- 0 until lqDepth){
                // 检查这个槽位是否在此周期被延迟的冲刷信号命中
                val toBeFlushed = registeredFlush.valid && 
                                  slots(i).valid && 
                                  isNewerOrSame(slots(i).robPtr, registeredFlush.targetRobPtr)

                // 优先处理冲刷
                when(toBeFlushed) {
                    // 如果要冲刷，则直接将槽位置为无效，忽略所有其他信号
                    slotsNext(i).setDefault()
                    ParallaxSim.log(L"[LQ] FLUSH (Exec): Invalidating slotIdx=${i} (robPtr=${slots(i).robPtr})")
                }
                // 注意：LoadQueue 不像 StoreBuffer 那样有明确的 commit 操作
                // 所以这里不需要 .elsewhen 的特殊处理
            }
            // when(head.valid || slotsNext(0).valid) { // 只在LQ非空时打印，避免刷屏
            //     ParallaxSim.log(
            //         L"--- LQ FINAL UPDATE --- Cycle=${perfCounter.value}\n" :+
            //         L"  Current head(0).robPtr: ${slots(0).robPtr}\n" :+
            //         L"  slotsAfterUpdates(0).isReadyForDCache: ${slotsAfterUpdates(0).isReadyForDCache}\n" :+
            //         L"  popRequest this cycle: ${popRequest}\n" :+
            //         L"  Next state for slot 0 (slotsNext(0)):\n" :+
            //         L"    - robPtr: ${slotsNext(0).robPtr}\n" :+
            //         L"    - isReadyForDCache: ${slotsNext(0).isReadyForDCache}\n" :+
            //         L"    - From where? slotsAfterUpdates(1).robPtr: ${slotsAfterUpdates(1).robPtr}, valid: ${slotsAfterUpdates(1).valid}"
            //     )
            // }
            // Final register update
            for(i <- 0 until lqDepth) {
                slots(i) := slotsNext(i)
            }
        } // End of loadQueue Area

        hw.robServiceInst.release()
        //dcachedisable hw.dcacheServiceInst.release()
        hw.prfServiceInst.release()
        hw.storeBufferServiceInst.release()
        hw.sgmbServiceOpt.foreach(_.release())
        hw.wakeupServiceInst.release()
        hw.busyTableServiceInst.release()
        hw.hardRedirectService.release()
    }
}
