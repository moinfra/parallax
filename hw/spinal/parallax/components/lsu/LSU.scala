// filename: parallax/components/lsu/LsuPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._

case class LsuInputCmd(pCfg: PipelineConfig) extends Bundle {
  val robPtr        = UInt(pCfg.robPtrWidth)
  val isLoad        = Bool()
  val isStore       = Bool()
  val isFlush       = Bool()

  val baseReg       = UInt(pCfg.physGprIdxWidth)
  val immediate     = SInt(12 bits)
  val usePc         = Bool()
  val pc            = UInt(pCfg.pcWidth)

  val dataReg       = UInt(pCfg.physGprIdxWidth) // For Stores: source data reg
  val pdest         = UInt(pCfg.physGprIdxWidth) // For Loads: destination reg

  val accessSize    = MemAccessSize()
}


trait LsuService extends Service with LockedImpl {
    def getInputPort(): Stream[LsuInputCmd]
}

case class LoadQueueSlot(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheParams: DataCacheParameters) extends Bundle {
    val valid             = Bool()
    val addrValid         = Bool()
    val address           = UInt(lsuCfg.pcWidth)
    val size              = MemAccessSize()
    val robPtr            = UInt(lsuCfg.robPtrWidth)
    val pdest             = UInt(pCfg.physGprIdxWidth)
    
    val baseReg           = UInt(pCfg.physGprIdxWidth) // 基地址寄存器
    val immediate         = SInt(12 bits) // 立即数
    val usePc             = Bool() // 是否使用PC作为基址
    val pc                = UInt(pCfg.pcWidth) // PC值

    val hasException      = Bool()
    val exceptionCode     = UInt(8 bits)

    val isWaitingForFwdRsp    = Bool() // Query sent to SB, waiting for response.
    val isStalledByDependency = Bool() // SB response indicated a dependency, must wait and retry.
    val isReadyForDCache      = Bool() // SB cleared this load for D-Cache access.
    val isWaitingForDCacheRsp = Bool() // Request sent to D-Cache, waiting for response.
    
    def setDefault(): this.type = {
        this.valid                 := False
        this.addrValid             := False
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
}


// =========================================================
// >> LSU插件主体 (集成AGU, SB, PRF, ROB)
// =========================================================
class LsuPlugin(
    val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters,
    val lqDepth: Int = 16,
    val sbDepth: Int = 16
) extends Plugin with LsuService {

    val hw = create early new Area {
        val lsuInputCmd = slave(Stream(LsuInputCmd(pipelineConfig)))
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val prfServiceInst    = getService[PhysicalRegFileService]
        val aguServiceInst    = getService[AguService]
        val storeBufferServiceInst = getService[StoreBufferService]

        // --- 为LSU获取硬件端口 ---
        val lqAguPort        = aguServiceInst.newAguPort()
        val sqAguPort        = aguServiceInst.newAguPort()
        
        val dCacheLoadPort   = dcacheServiceInst.newLoadPort(priority = 0)
        val robWritebackPort = robServiceInst.newWritebackPort("LSU_Load")
        val prfWritePort     = prfServiceInst.newWritePort()
        val sbQueryPort      = storeBufferServiceInst.getStoreQueueQueryPort()
        val sbPushPort       = storeBufferServiceInst.getPushPort()

        // -- 服务保持 --
        robServiceInst.retain()
        dcacheServiceInst.retain()
        prfServiceInst.retain()
        aguServiceInst.retain()
        storeBufferServiceInst.retain()
    }

    override def getInputPort(): Stream[LsuInputCmd] = hw.lsuInputCmd

    val logic = create late new Area {
        lock.await()

        val cmdIn = hw.lsuInputCmd
        val lqAguPort        = hw.lqAguPort
        val sqAguPort        = hw.sqAguPort
        val sbQueryPort      = hw.sbQueryPort
        val sbPushPort       = hw.sbPushPort
        val dCacheLoadPort   = hw.dCacheLoadPort
        val robWritebackPort = hw.robWritebackPort
        val prfWritePort     = hw.prfWritePort

        // >>> FIX: 从ROB服务获取冲刷信号并连接到AGU端口
        val robService = hw.robServiceInst
        val robFlushPort = robService.getFlushPort()
        lqAguPort.flush := robFlushPort.valid
        sqAguPort.flush := robFlushPort.valid
        // <<< FIX END

        // 统一的指令分派到AGU
        val (loadStream, storeStream) = StreamDemux.two(cmdIn, select = cmdIn.isLoad)

        // --- 连接Load指令到LQ的AGU端口 ---
        lqAguPort.input.valid           := loadStream.valid
        lqAguPort.input.payload.isLoad  := True
        lqAguPort.input.payload.isStore := False
        lqAguPort.input.payload.isFlush := loadStream.payload.isFlush
        lqAguPort.input.payload.robPtr        := loadStream.payload.robPtr
        lqAguPort.input.payload.basePhysReg   := loadStream.payload.baseReg
        lqAguPort.input.payload.immediate     := loadStream.payload.immediate
        lqAguPort.input.payload.usePc         := loadStream.payload.usePc
        lqAguPort.input.payload.pc            := loadStream.payload.pc
        lqAguPort.input.payload.physDst       := loadStream.payload.pdest
        lqAguPort.input.payload.accessSize    := loadStream.payload.accessSize
        lqAguPort.input.payload.dataReg       := 0 // Load不需要
        loadStream.ready := lqAguPort.input.ready

        // --- 连接Store指令到SQ的AGU端口 ---
        sqAguPort.input.valid           := storeStream.valid
        sqAguPort.input.payload.isLoad  := False
        sqAguPort.input.payload.isStore := True
        // >>> FIX: 补全 isFlush 字段的连接
        sqAguPort.input.payload.isFlush := storeStream.payload.isFlush
        // <<< FIX END
        sqAguPort.input.payload.robPtr        := storeStream.payload.robPtr
        sqAguPort.input.payload.basePhysReg   := storeStream.payload.baseReg
        sqAguPort.input.payload.immediate     := storeStream.payload.immediate
        sqAguPort.input.payload.usePc         := storeStream.payload.usePc
        sqAguPort.input.payload.pc            := storeStream.payload.pc
        sqAguPort.input.payload.dataReg       := storeStream.payload.dataReg
        sqAguPort.input.payload.physDst       := 0 // Store不需要
        sqAguPort.input.payload.accessSize    := storeStream.payload.accessSize
        storeStream.ready := sqAguPort.input.ready

        // =========================================================
        // >> Store Path Area
        // =========================================================
        val storePath = new Area {
            val aguRsp = sqAguPort.output
            
            sbPushPort << aguRsp.translateWith {
                val sbCmd = StoreBufferPushCmd(pipelineConfig, lsuConfig)
                sbCmd.addr      := aguRsp.payload.address
                sbCmd.data      := aguRsp.payload.storeData
                sbCmd.be        := aguRsp.payload.storeMask
                sbCmd.robPtr    := aguRsp.payload.robPtr
                sbCmd.accessSize:= aguRsp.payload.accessSize
                sbCmd.isFlush   := aguRsp.payload.isFlush
                sbCmd.isIO      := False // 假设
                sbCmd.hasEarlyException := aguRsp.payload.alignException
                sbCmd.earlyExceptionCode:= ExceptionCode.STORE_ADDRESS_MISALIGNED
                sbCmd
            }
            
            when(sbPushPort.fire) {
                ParallaxSim.log(L"[LSU-Store] AGU result pushed to SB: robPtr=${sbPushPort.payload.robPtr}")
            }
        }


        // =========================================================
        // >> 加载队列区域 (Load Queue Area)
        // =========================================================
        val loadQueue = new Area {
            val slots = Vec.fill(lqDepth)(Reg(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams)))
            val slotsAfterUpdates = CombInit(slots)
            val slotsNext   = CombInit(slots)

            for(i <- 0 until lqDepth) {
                slots(i).init(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
            }

            val aguRsp = lqAguPort.output

            // --- 1. LQ Push (from AGU Response) ---
            val canPush = !slots.map(_.valid).andR
            aguRsp.ready := canPush
            
            val pushIdx = OHToUInt(slots.map(!_.valid).asBits)
            when(aguRsp.fire) {
                val newSlot = slotsAfterUpdates(pushIdx)
                val cmd = aguRsp.payload
                
                newSlot.valid       := True
                newSlot.addrValid   := True
                newSlot.address     := cmd.address
                newSlot.hasException:= cmd.alignException
                newSlot.exceptionCode := ExceptionCode.LOAD_ADDR_MISALIGNED
                newSlot.size        := cmd.accessSize
                newSlot.robPtr      := cmd.robPtr
                newSlot.pdest       := cmd.physDst
                
                newSlot.isWaitingForFwdRsp    := False
                newSlot.isStalledByDependency := False
                newSlot.isReadyForDCache      := False
                newSlot.isWaitingForDCacheRsp := False
                ParallaxSim.log(L"[LQ] PUSH from AGU: robPtr=${cmd.robPtr} addr=${cmd.address} to slotIdx=${pushIdx}")
            }

            // --- 2. Store-to-Load Forwarding & Disambiguation ---
            val head = slots(0)
            val headIsReadyForFwdQuery = head.valid && !head.hasException &&
                                         !head.isWaitingForFwdRsp && !head.isStalledByDependency &&
                                         !head.isReadyForDCache && !head.isWaitingForDCacheRsp

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

            // --- 3. D-Cache Interaction ---
            val headIsReadyToExecute = head.valid && head.isReadyForDCache && !head.isWaitingForDCacheRsp

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

            // --- 5. Completion & Pop Logic (Refactored) ---
            val popOnFwdHit = head.isWaitingForFwdRsp && sbQueryPort.rsp.hit
            val popOnDCacheSuccess = dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForDCacheRsp && !dCacheLoadPort.rsp.payload.redo
            val popOnEarlyException = head.valid && head.hasException && !head.isWaitingForFwdRsp && !head.isWaitingForDCacheRsp
            val popRequest = popOnFwdHit || popOnDCacheSuccess || popOnEarlyException

            // >>> FIX: 使用 if/elsewhen 结构避免赋值冲突
            // Default assignments to prevent latches
            robWritebackPort.fire := False
            robWritebackPort.robPtr.assignDontCare()
            robWritebackPort.exceptionOccurred.assignDontCare()
            robWritebackPort.exceptionCodeIn.assignDontCare()
            
            prfWritePort.valid   := False
            prfWritePort.address.assignDontCare()
            prfWritePort.data.assignDontCare()

            // Completion paths
            when(popOnFwdHit) {
                ParallaxSim.log(L"[LQ-Fwd] HIT: robPtr=${head.robPtr}, data=${sbQueryPort.rsp.data}. Completing instruction.")
                prfWritePort.valid   := True
                prfWritePort.address := head.pdest
                prfWritePort.data    := sbQueryPort.rsp.data
                
                robWritebackPort.fire := True
                robWritebackPort.robPtr := head.robPtr
                robWritebackPort.exceptionOccurred := False
            } elsewhen (popOnDCacheSuccess) {
                ParallaxSim.log(L"[LQ-DCache] DCACHE_RSP_OK for robPtr=${head.robPtr}, data=${dCacheLoadPort.rsp.payload.data}, fault=${dCacheLoadPort.rsp.payload.fault}")
                prfWritePort.valid   := !dCacheLoadPort.rsp.payload.fault
                prfWritePort.address := head.pdest
                prfWritePort.data    := dCacheLoadPort.rsp.payload.data

                robWritebackPort.fire := True
                robWritebackPort.robPtr := head.robPtr
                robWritebackPort.exceptionOccurred := dCacheLoadPort.rsp.payload.fault
                robWritebackPort.exceptionCodeIn   := ExceptionCode.LOAD_ACCESS_FAULT
            } elsewhen (popOnEarlyException) {
                ParallaxSim.log(L"[LQ] Alignment exception for robPtr=${head.robPtr}")
                robWritebackPort.fire := True
                robWritebackPort.robPtr := head.robPtr
                robWritebackPort.exceptionOccurred := True
                robWritebackPort.exceptionCodeIn := head.exceptionCode
            }
            // <<< FIX END

            // --- 6. LQ Pop Execution ---
            when(popRequest) {
                ParallaxSim.log(L"[LQ] POP: Popping slot 0 (robPtr=${slotsAfterUpdates(0).robPtr})")
                for (i <- 0 until lqDepth - 1) {
                    slotsNext(i) := slotsAfterUpdates(i + 1)
                }
                slotsNext(lqDepth - 1).setDefault()
            }
        } // End of loadQueue Area

        // Release services
        hw.robServiceInst.release()
        hw.dcacheServiceInst.release()
        hw.prfServiceInst.release()
        hw.aguServiceInst.release()
        hw.storeBufferServiceInst.release()
    }
}
