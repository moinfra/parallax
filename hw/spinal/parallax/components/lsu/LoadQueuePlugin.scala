// filename: parallax/components/lsu/LoadQueuePlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer
import parallax.utils.Encoders.PriorityEncoderOH

// --- 输入到Load Queue的命令 ---
// 这个命令由LsuEu在地址计算后发出
case class LoadQueuePushCmd(pCfg: PipelineConfig, lsuCfg: LsuConfig) extends Bundle {
  val robPtr            = UInt(lsuCfg.robPtrWidth)
  val pdest             = UInt(pCfg.physGprIdxWidth)
  val address           = UInt(lsuCfg.pcWidth)
  val size              = MemAccessSize()
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

    val hasException      = Bool()
    val exceptionCode     = UInt(8 bits)

    val isWaitingForFwdRsp    = Bool()
    val isStalledByDependency = Bool()
    val isReadyForDCache      = Bool()
    val isWaitingForDCacheRsp = Bool()
    
    def setDefault(): this.type = {
        this.valid                 := False
        this.address               := 0
        this.size                  := MemAccessSize.W
        this.robPtr                := 0
        this.pdest                 := 0
        this.hasException          := False
        this.exceptionCode         := 0
        this.isWaitingForFwdRsp    := False
        this.isStalledByDependency := False
        this.isReadyForDCache      := False
        this.isWaitingForDCacheRsp := False
        this
    }

    def initFromPushCmd(cmd: LoadQueuePushCmd): this.type = {
        this.valid                 := True
        this.address               := cmd.address
        this.size                  := cmd.size
        this.robPtr                := cmd.robPtr
        this.pdest                 := cmd.pdest
        this.hasException          := cmd.hasEarlyException
        this.exceptionCode         := cmd.earlyExceptionCode
        
        this.isWaitingForFwdRsp    := False // Will be set to true next cycle if it queries
        this.isStalledByDependency := False
        this.isReadyForDCache      := !cmd.hasEarlyException
        this.isWaitingForDCacheRsp := False
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
            L"hasException=${hasException}, " :+
            L"isWaitingForFwdRsp=${isWaitingForFwdRsp}, " :+
            L"isStalledByDependency=${isStalledByDependency}, " :+
            L"isReadyForDCache=${isReadyForDCache}, " :+
            L"isWaitingForDCacheRsp=${isWaitingForDCacheRsp})"
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
    val lqDepth: Int = 16
) extends Plugin with LoadQueueService {

    private val pushPorts = ArrayBuffer[Stream[LoadQueuePushCmd]]()
    override def newPushPort(): Stream[LoadQueuePushCmd] = {
        val port = Stream(LoadQueuePushCmd(pipelineConfig, lsuConfig))
        pushPorts += port
        port
    }

    val hw = create early new Area {
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val prfServiceInst    = getService[PhysicalRegFileService]
        val storeBufferServiceInst = getService[StoreBufferService]

        val dCacheLoadPort   = dcacheServiceInst.newLoadPort(priority = 1)
        val robLoadWritebackPort = robServiceInst.newWritebackPort("LQ_Load")
        val prfWritePort     = prfServiceInst.newWritePort()
        val sbQueryPort      = storeBufferServiceInst.getStoreQueueQueryPort()

        robServiceInst.retain()
        dcacheServiceInst.retain()
        prfServiceInst.retain()
        storeBufferServiceInst.retain()
    }

    val logic = create late new Area {
        lock.await()
        // 将所有push端口仲裁成一个
        val pushCmd = StreamArbiterFactory.roundRobin.on(pushPorts)

        // 从hw区域获取端口
        val sbQueryPort      = hw.sbQueryPort
        val dCacheLoadPort   = hw.dCacheLoadPort
        val robLoadWritebackPort = hw.robLoadWritebackPort
        val prfWritePort     = hw.prfWritePort
        val robFlushPort = hw.robServiceInst.getFlushPort()

        // Store Path Area is completely removed.

        // isNewerOrSame 辅助函数保持不变
        private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
            val widthA = robPtrA.getWidth
            val widthB = robPtrB.getWidth
            require(widthA == widthB, s"ROB pointer widths must match: $widthA vs $widthB")
            
            if (widthA == 1) {
                // Only 1 bit, no generation bit
                robPtrA >= robPtrB
            } else {
                val genA = robPtrA.msb
                val idxA = robPtrA.resize(widthA - 1)
                val genB = robPtrB.msb  
                val idxB = robPtrB.resize(widthB - 1)
                (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
            }
        }

        // =========================================================
        // >> 加载队列区域 (Load Queue Area)
        // =========================================================
        val loadQueue = new Area {
            val slots = Vec.fill(lqDepth)(Reg(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams)))
            val slotsAfterUpdates = CombInit(slots)
            val slotsNext   = CombInit(slots)

            // 初始化和Flush逻辑几乎不变
            for(i <- 0 until lqDepth) {
                slots(i).init(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
            }
            when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                for(i <- 0 until lqDepth) slotsNext(i).setDefault()
            }.elsewhen(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX) {
                val targetRobPtr = robFlushPort.payload.targetRobPtr
                for(i <- 0 until lqDepth) {
                    when(slots(i).valid && isNewerOrSame(slots(i).robPtr, targetRobPtr)) {
                        slotsNext(i).setDefault()
                    }
                }
            }

            // --- 1. LQ Push (from LsuEu) ---
            val canPush = !slots.map(_.valid).andR
            pushCmd.ready := canPush
            
            val availableSlotsMask = slots.map(!_.valid).asBits
            val pushOh = PriorityEncoderOH(availableSlotsMask)
            val pushIdx = OHToUInt(pushOh)
            when(pushCmd.fire) {
                slotsAfterUpdates(pushIdx).initFromPushCmd(pushCmd.payload)
                ParallaxSim.log(L"[LQ] PUSH from LsuEu: robPtr=${pushCmd.robPtr} addr=${pushCmd.address} to slotIdx=${pushIdx}")
            }

            // --- 2-6. 队列头部的处理逻辑 (Store Forwarding, D-Cache, Completion, Pop) ---
            // 这部分逻辑与原 LsuPlugin 中的 loadQueue 区域完全相同，可以直接复制过来。
            // 为了简洁，这里只展示关键部分，实际应包含所有细节。
            val head = slots(0)
            val headIsValid = head.valid

            // --- Disambiguation ---
            val headIsReadyForFwdQuery = headIsValid && !head.hasException &&
                                         !head.isWaitingForFwdRsp && !head.isStalledByDependency &&
                                         head.isReadyForDCache && !head.isWaitingForDCacheRsp

            sbQueryPort.cmd.valid   := headIsReadyForFwdQuery
            sbQueryPort.cmd.payload.address := head.address
            sbQueryPort.cmd.payload.size    := head.size
            sbQueryPort.cmd.payload.robPtr  := head.robPtr

            when(sbQueryPort.cmd.fire) {
                slotsAfterUpdates(0).isWaitingForFwdRsp := True
                ParallaxSim.log(L"[LQ-Fwd] QUERY: robPtr=${head.robPtr} addr=${head.address}")
            }
            
            when(head.isWaitingForFwdRsp) {
                val fwdRsp = sbQueryPort.rsp
                slotsAfterUpdates(0).isWaitingForFwdRsp := False
                when(fwdRsp.hit) {
                    // Completion logic is handled below by popOnFwdHit
                } .elsewhen(fwdRsp.olderStoreHasUnknownAddress || fwdRsp.olderStoreMatchingAddress) {
                    ParallaxSim.log(L"[LQ-Fwd] STALL: robPtr=${head.robPtr} has dependency...")
                    slotsAfterUpdates(0).isStalledByDependency := True
                } otherwise {
                    ParallaxSim.log(L"[LQ-Fwd] MISS: robPtr=${head.robPtr} is clear to access D-Cache.")
                    slotsAfterUpdates(0).isReadyForDCache := True
                }
            }

            when(head.isStalledByDependency) {
                slotsAfterUpdates(0).isStalledByDependency := False
            }
            
            // --- D-Cache Interaction ---
            // 注意: isReadyForDCache 现在在入队时就设置好了，不再需要等待转发结果（除非转发失败）
            val headIsReadyToExecute = headIsValid && head.isReadyForDCache && !head.isWaitingForDCacheRsp

            dCacheLoadPort.cmd.valid                 := headIsReadyToExecute && !head.hasException
            dCacheLoadPort.cmd.payload.virtual       := head.address
            dCacheLoadPort.cmd.payload.size          := MemAccessSize.toByteSizeLog2(head.size)
            dCacheLoadPort.cmd.payload.redoOnDataHazard := True
            if(pipelineConfig.transactionIdWidth > 0) {
                 dCacheLoadPort.cmd.payload.id       := head.robPtr.resize(pipelineConfig.transactionIdWidth)
            }
            dCacheLoadPort.translated.physical       := head.address
            dCacheLoadPort.translated.abord          := head.hasException
            dCacheLoadPort.cancels                   := 0
            
            when(dCacheLoadPort.cmd.fire) {
                slotsAfterUpdates(0).isWaitingForDCacheRsp := True
                slotsAfterUpdates(0).isReadyForDCache      := False
                ParallaxSim.log(L"[LQ-DCache] SEND_TO_DCACHE: robPtr=${head.robPtr} addr=${head.address}")
            }

            // --- 4. D-Cache Response Handling (for Redo) ---
            when(dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForDCacheRsp) {
                when(dCacheLoadPort.rsp.payload.redo) {
                    slotsAfterUpdates(0).isWaitingForDCacheRsp := False
                    slotsAfterUpdates(0).isReadyForDCache      := True 
                    ParallaxSim.log(L"[LQ-DCache] REDO received for robPtr=${head.robPtr}")
                }
            }

            // --- Completion & Pop Logic ---
            // 这部分也保持不变
            val popOnFwdHit = head.isWaitingForFwdRsp && sbQueryPort.rsp.hit
            val popOnDCacheSuccess = dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForDCacheRsp && !dCacheLoadPort.rsp.payload.redo
            val popOnEarlyException = head.valid && head.hasException && !head.isReadyForDCache // Exception was known at dispatch
            val popRequest = popOnFwdHit || popOnDCacheSuccess || popOnEarlyException
            
                        robLoadWritebackPort.fire := False
            robLoadWritebackPort.robPtr.assignDontCare()
            robLoadWritebackPort.exceptionOccurred.assignDontCare()
            robLoadWritebackPort.exceptionCodeIn.assignDontCare()
            
            prfWritePort.valid   := False
            prfWritePort.address.assignDontCare()
            prfWritePort.data.assignDontCare()

            // Completion paths
            when(popOnFwdHit) {
                ParallaxSim.log(L"[LQ-Fwd] HIT: robPtr=${head.robPtr}, data=${sbQueryPort.rsp.data}. Completing instruction.")
                prfWritePort.valid   := True
                prfWritePort.address := head.pdest
                prfWritePort.data    := sbQueryPort.rsp.data
                
                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := head.robPtr
                robLoadWritebackPort.exceptionOccurred := False
            } elsewhen (popOnDCacheSuccess) {
                ParallaxSim.log(L"[LQ-DCache] DCACHE_RSP_OK for robPtr=${head.robPtr}, data=${dCacheLoadPort.rsp.payload.data}, fault=${dCacheLoadPort.rsp.payload.fault}")
                prfWritePort.valid   := !dCacheLoadPort.rsp.payload.fault
                prfWritePort.address := head.pdest
                prfWritePort.data    := dCacheLoadPort.rsp.payload.data

                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := head.robPtr
                robLoadWritebackPort.exceptionOccurred := dCacheLoadPort.rsp.payload.fault
                robLoadWritebackPort.exceptionCodeIn   := ExceptionCode.LOAD_ACCESS_FAULT
            } elsewhen (popOnEarlyException) {
                ParallaxSim.log(L"[LQ] Alignment exception for robPtr=${head.robPtr}")
                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := head.robPtr
                robLoadWritebackPort.exceptionOccurred := True
                robLoadWritebackPort.exceptionCodeIn := head.exceptionCode
            }
            // <<< FIX END

            // --- LQ Pop Execution ---
            when(popRequest) {
                for (i <- 0 until lqDepth - 1) {
                    slotsNext(i) := slotsAfterUpdates(i + 1)
                }
                slotsNext(lqDepth - 1).setDefault()
            }

            // Final register update
            for(i <- 0 until lqDepth) {
                slots(i) := slotsNext(i)
            }
        } // End of loadQueue Area

        hw.robServiceInst.release()
        hw.dcacheServiceInst.release()
        hw.prfServiceInst.release()
        hw.storeBufferServiceInst.release()
    }
}
