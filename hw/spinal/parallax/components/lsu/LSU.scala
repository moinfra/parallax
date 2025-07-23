// filename: parallax/components/lsu/LsuPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer
import parallax.utils.Encoders.PriorityEncoderOH

 object ExceptionCode {
   def LOAD_ADDR_MISALIGNED = U(4, 8 bits) // Example code
   def LOAD_ACCESS_FAULT = U(5, 8 bits) // Example code
   def STORE_ADDRESS_MISALIGNED = U(6, 8 bits) // Example code
   def STORE_ACCESS_FAULT =  U(7, 8 bits) // Example code
   // Add other codes as needed
 }

case class LsuInputCmd(pCfg: PipelineConfig) extends Bundle {
  val robPtr        = UInt(pCfg.robPtrWidth)
  val isLoad        = Bool()
  val isStore       = Bool()
  val isFlush       = Bool()
  val isIO       = Bool()

  val baseReg       = UInt(pCfg.physGprIdxWidth)
  val immediate     = SInt(pCfg.dataWidth)
  val usePc         = Bool()
  val pc            = UInt(pCfg.pcWidth)

  val dataReg       = UInt(pCfg.physGprIdxWidth) // For Stores: source data reg
  val pdest         = UInt(pCfg.physGprIdxWidth) // For Loads: destination reg

  val accessSize    = MemAccessSize()
}

case class LsuPort(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  val input = Stream(LsuInputCmd(pCfg))
  override def asMaster(): Unit = {
    master(input)
  }
}

trait LsuService extends Service with LockedImpl {
    def newLsuPort(): LsuPort
}

// case class LoadQueueSlot(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheParams: DataCacheParameters) extends Bundle with Formattable {
//     val valid             = Bool()
//     val addrValid         = Bool()
//     val address           = UInt(lsuCfg.pcWidth)
//     val size              = MemAccessSize()
//     val robPtr            = UInt(lsuCfg.robPtrWidth)
//     val pdest             = UInt(pCfg.physGprIdxWidth)
    
//     val baseReg           = UInt(pCfg.physGprIdxWidth) // 基地址寄存器
//     val immediate         = SInt(12 bits) // 立即数
//     val usePc             = Bool() // 是否使用PC作为基址
//     val pc                = UInt(pCfg.pcWidth) // PC值

//     val hasException      = Bool()
//     val exceptionCode     = UInt(8 bits)

//     val isWaitingForFwdRsp    = Bool() // Query sent to SB, waiting for response.
//     val isStalledByDependency = Bool() // SB response indicated a dependency, must wait and retry.
//     val isReadyForDCache      = Bool() // SB cleared this load for D-Cache access.
//     val isWaitingForRsp = Bool() // Request sent to D-Cache, waiting for response.
    
//     def setDefault(): this.type = {
//         this.valid                 := False
//         this.addrValid             := False
//         this.address               := 0
//         this.size                  := MemAccessSize.W
//         this.robPtr                := 0
//         this.pdest                 := 0
        
//         this.baseReg               := 0 // 新增
//         this.immediate             := 0 // 新增
//         this.usePc                 := False // 新增
//         this.pc                    := 0 // 新增

//         this.hasException          := False
//         this.exceptionCode         := 0
        
//         this.isWaitingForFwdRsp    := False
//         this.isStalledByDependency := False
//         this.isReadyForDCache      := False
//         this.isWaitingForRsp := False
//         this
//     }

//         // 新增方法，用于从 AguRspCmd 初始化
//     def initFromAguOutput(cmd: AguOutput): this.type = {
//         this.valid                 := True
//         this.addrValid             := True
//         this.address               := cmd.address
//         this.size                  := cmd.accessSize
//         this.robPtr                := cmd.robPtr
//         this.pdest                 := cmd.physDst
        
//         // 这些字段在 AguRspCmd 中可能没有直接对应，但为了完整性，这里也进行初始化
//         // 如果 AguRspCmd 提供了这些信息，可以根据需要进行映射
//         this.baseReg               := cmd.basePhysReg // 假设 AguRspCmd 提供了
//         this.immediate             := cmd.immediate // 假设 AguRspCmd 提供了
//         this.usePc                 := cmd.usePc // 假设 AguRspCmd 提供了
//         this.pc                    := cmd.pc // 假设 AguRspCmd 提供了

//         this.hasException          := cmd.alignException
//         this.exceptionCode         := ExceptionCode.LOAD_ADDR_MISALIGNED // 假设只有对齐异常
        
//         this.isWaitingForFwdRsp    := False
//         this.isStalledByDependency := False
//         this.isReadyForDCache      := False
//         this.isWaitingForRsp := False
//         this
//     }

//     def format: Seq[Any] = {
//         Seq(
//             L"LQSlot(valid=${valid}, " :+
//             L"addrValid=${addrValid}, " :+
//             L"address=${address}, " :+
//             L"size=${size}, " :+
//             L"robPtr=${robPtr}, " :+
//             L"pdest=${pdest}, " :+
//             L"baseReg=${baseReg}, " :+
//             L"immediate=${immediate}, " :+
//             L"usePc=${usePc}, " :+
//             L"pc=${pc}, " :+
//             L"hasException=${hasException}, " :+
//             L"exceptionCode=${exceptionCode}, " :+
//             L"isWaitingForFwdRsp=${isWaitingForFwdRsp}, " :+
//             L"isStalledByDependency=${isStalledByDependency}, " :+
//             L"isReadyForDCache=${isReadyForDCache}, " :+
//             L"isWaitingForRsp=${isWaitingForRsp})"
//         )
//     }
// }


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
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val prfServiceInst    = getService[PhysicalRegFileService]
        val aguServiceInst    = getService[AguService]
        val storeBufferServiceInst = getService[StoreBufferService]

        // --- 为LSU获取硬件端口 ---
        val lqAguPort        = aguServiceInst.newAguPort()
        val sqAguPort        = aguServiceInst.newAguPort()
        
        val dCacheLoadPort   = dcacheServiceInst.newLoadPort(priority = 1)
        val robLoadWritebackPort = robServiceInst.newWritebackPort("LSU_Load")
        val robStoreWritebackPort = robServiceInst.newWritebackPort("LSU_Store")

        val prfWritePort     = prfServiceInst.newPrfWritePort(s"LSU.gprWritePort")
        val sbQueryPort      = storeBufferServiceInst.getStoreQueueQueryPort()
        val sbPushPort       = storeBufferServiceInst.getPushPort()

        // -- 服务保持 --
        robServiceInst.retain()
        dcacheServiceInst.retain()
        prfServiceInst.retain()
        aguServiceInst.retain()
        storeBufferServiceInst.retain()
    }

    private val lsuPorts = ArrayBuffer[LsuPort]()
    override def newLsuPort(): LsuPort = {
        this.framework.requireEarly()
        assert(lsuPorts.length == 0, "Only one LSU port is allowed")
        val port = LsuPort(pipelineConfig).setCompositeName(this, "lsuPort_" + lsuPorts.size)
        lsuPorts += port
        port
    }


    val logic = create late new Area {
        lock.await()
        val lsuInputCmd             = lsuPorts.head.input
        val cmdIn                   = lsuInputCmd
        val lqAguPort               = hw.lqAguPort
        val sqAguPort               = hw.sqAguPort
        val sbQueryPort             = hw.sbQueryPort
        val sbPushPort              = hw.sbPushPort
        val dCacheLoadPort          = hw.dCacheLoadPort
        val robLoadWritebackPort    = hw.robLoadWritebackPort
        val robStoreWritebackPort   = hw.robStoreWritebackPort
        val prfWritePort            = hw.prfWritePort

        // FIXME 这里有问题，robFlushPort 应该被控制而非监听
        val robService = hw.robServiceInst
        val robFlushPort = robService.doRobFlush()
        lqAguPort.flush := robFlushPort.valid
        sqAguPort.flush := robFlushPort.valid

        // 统一的指令分派到AGU
        val (loadStream, storeStream) = StreamDemux.two(cmdIn, select = cmdIn.isStore)
        // >>> ADD DIAGNOSTIC LOGS for LSU input and demux <<<
        when(cmdIn.fire) {
            ParallaxSim.log(L"[LSU-Input] FIRE: isLoad=${cmdIn.isLoad}, isStore=${cmdIn.isStore}, robPtr=${cmdIn.robPtr}")
        }
        when(loadStream.fire) {
            ParallaxSim.log(L"[LSU-Demux] Load stream FIRE for robPtr=${loadStream.robPtr}")
        }
        when(storeStream.fire) {
            ParallaxSim.log(L"[LSU-Demux] Store stream FIRE for robPtr=${storeStream.robPtr}")
        }
        // >>> END DIAGNOSTIC LOGS <<<
        // --- 连接Load指令到LQ的AGU端口 ---
        lqAguPort.input.valid           := loadStream.valid
        lqAguPort.input.payload.isLoad  := True
        lqAguPort.input.payload.isStore := False
        lqAguPort.input.payload.isFlush := loadStream.payload.isFlush
        lqAguPort.input.payload.isIO := loadStream.payload.isIO
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
        sqAguPort.input.payload.isIO := storeStream.payload.isIO
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
            // >>> ADD DIAGNOSTIC LOGS for the AGU->SB interface <<<
            ParallaxSim.logWhen(
                cond = sqAguPort.input.valid || aguRsp.valid,
                message = L"[LSU-StorePath] AGU Interface Status: " :+
                          L"input.valid=${sqAguPort.input.valid}, input.ready=${sqAguPort.input.ready}, " :+
                          L"output.valid=${aguRsp.valid}, output.ready=${aguRsp.ready}, " :+
                          L"output.payload.robPtr=${aguRsp.payload.robPtr}"
            )

            ParallaxSim.logWhen(
                cond = sbPushPort.valid || sbPushPort.ready,
                message = L"[LSU-StorePath] SB Push Interface Status: " :+
                          L"sbPushPort.valid=${sbPushPort.valid}, sbPushPort.ready=${sbPushPort.ready}"
            )
            // >>> END DIAGNOSTIC LOGS <<<
            // 默认情况下，store写回端口不触发
            robStoreWritebackPort.setDefault()

            sbPushPort << aguRsp.translateWith {
                val sbCmd = StoreBufferPushCmd(pipelineConfig, lsuConfig)
                sbCmd.addr      := aguRsp.payload.address
                sbCmd.data      := aguRsp.payload.storeData
                sbCmd.be        := aguRsp.payload.storeMask
                sbCmd.robPtr    := aguRsp.payload.robPtr
                sbCmd.accessSize:= aguRsp.payload.accessSize
                sbCmd.isFlush   := aguRsp.payload.isFlush
                sbCmd.isIO      := aguRsp.payload.isIO
                sbCmd.hasEarlyException := aguRsp.payload.alignException
                sbCmd.earlyExceptionCode:= ExceptionCode.STORE_ADDRESS_MISALIGNED
                sbCmd
            }
            
            when(sbPushPort.fire) {
                ParallaxSim.log(L"[LSU-Store] AGU result pushed to SB: robPtr=${sbPushPort.payload.robPtr}")
                // >>> FIX: 当Store成功进入SB后，向ROB报告完成
                robStoreWritebackPort.fire := True
                robStoreWritebackPort.robPtr := aguRsp.payload.robPtr
                robStoreWritebackPort.exceptionOccurred := aguRsp.payload.alignException
                robStoreWritebackPort.exceptionCodeIn   := ExceptionCode.STORE_ADDRESS_MISALIGNED
                // <<< FIX END
            }
        }

        private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
            val genA = robPtrA.msb
            val idxA = robPtrA(robPtrA.high - 1 downto 0)
            val genB = robPtrB.msb
            val idxB = robPtrB(robPtrB.high - 1 downto 0)

            (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
        }
        // =========================================================
        // >> 加载队列区域 (Load Queue Area)
        // =========================================================
        val loadQueue = new Area {
            val slots = Vec.fill(lqDepth)(Reg(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams)))
            val slotsAfterUpdates = CombInit(slots)
            val slotsNext   = CombInit(slotsAfterUpdates)

            for(i <- 0 until lqDepth) {
                slots(i).init(LoadQueueSlot(pipelineConfig, lsuConfig, dCacheParams).setDefault())
            }

            when(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.FULL_FLUSH) {
                ParallaxSim.log(L"[LQ] FULL_FLUSH received. Clearing all slots.")
                for(i <- 0 until lqDepth) {
                    slotsNext(i).setDefault()
                }
            }.elsewhen(robFlushPort.valid && robFlushPort.payload.reason === FlushReason.ROLLBACK_TO_ROB_IDX) {
                val targetRobPtr = robFlushPort.payload.targetRobPtr
                ParallaxSim.log(L"[LQ] ROLLBACK received. Invalidating entries >= robPtr=${targetRobPtr}")

                for(i <- 0 until lqDepth) {
                    // 读取当前寄存器的值来做判断
                    when(slots(i).valid && isNewerOrSame(slots(i).robPtr, targetRobPtr)) {
                        ParallaxSim.log(L"[LQ] ROLLBACK: Invalidating slot ${i} with robPtr=${slots(i).robPtr}")
                        // 将修改应用到下一周期的信号上
                        slotsNext(i).setDefault()
                    }
                }
            }


            val aguRsp = lqAguPort.output

            // --- 1. LQ Push (from AGU Response) ---
            val canPush = !slots.map(_.valid).andR
            aguRsp.ready := canPush
            
            val availableSlotsMask = slots.map(!_.valid).asBits // e.g., B"1111" (multi-hot)
            val pushOh = PriorityEncoderOH(availableSlotsMask) // e.g., B"0001" (one-hot)
            val pushIdx = OHToUInt(pushOh)
            when(aguRsp.fire) {
                val newSlot = slotsAfterUpdates(pushIdx)
                val rsp = aguRsp.payload
                
                newSlot.initFromAguOutput(rsp)
                ParallaxSim.log(L"[LQ] PUSH from AGU: robPtr=${rsp.robPtr} addr=${rsp.address} to slotIdx=${pushIdx}")
            }

            // --- 2. Store-to-Load Forwarding & Disambiguation ---
            val head = slots(0)
            ParallaxSim.debug(head.format)
            val headIsReadyForFwdQuery = head.valid && !head.hasException &&
                                         !head.isWaitingForFwdRsp && !head.isStalledByDependency &&
                                         !head.isReadyForDCache && !head.isWaitingForRsp

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
                } .elsewhen(fwdRsp.olderStoreHasUnknownAddress || fwdRsp.olderStoreDataNotReady) {
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
            val headIsReadyToExecute = head.valid && head.isReadyForDCache && !head.isWaitingForRsp

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
                slotsAfterUpdates(0).isWaitingForRsp := True
                slotsAfterUpdates(0).isReadyForDCache      := False
                ParallaxSim.log(L"[LQ-DCache] SEND_TO_DCACHE: robPtr=${head.robPtr} addr=${head.address}")
            }

            // --- 4. D-Cache Response Handling (for Redo) ---
            when(dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForRsp) {
                when(dCacheLoadPort.rsp.payload.redo) {
                    slotsAfterUpdates(0).isWaitingForRsp := False
                    slotsAfterUpdates(0).isReadyForDCache      := True 
                    ParallaxSim.log(L"[LQ-DCache] REDO received for robPtr=${head.robPtr}")
                }
            }

            // --- 5. Completion & Pop Logic (Refactored) ---
            val popOnFwdHit = head.isWaitingForFwdRsp && sbQueryPort.rsp.hit
            val popOnDCacheSuccess = dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForRsp && !dCacheLoadPort.rsp.payload.redo
            val popOnEarlyException = head.valid && head.hasException && !head.isWaitingForFwdRsp && !head.isWaitingForRsp
            val popRequest = popOnFwdHit || popOnDCacheSuccess || popOnEarlyException

            // >>> FIX: 使用 if/elsewhen 结构避免赋值冲突
            // Default assignments to prevent latches
            robLoadWritebackPort.setDefault()
            
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
                robLoadWritebackPort.result            := sbQueryPort.rsp.data
            } elsewhen (popOnDCacheSuccess) {
                ParallaxSim.log(L"[LQ-DCache] DCACHE_RSP_OK for robPtr=${head.robPtr}, data=${dCacheLoadPort.rsp.payload.data}, fault=${dCacheLoadPort.rsp.payload.fault}")
                prfWritePort.valid   := !dCacheLoadPort.rsp.payload.fault
                prfWritePort.address := head.pdest
                prfWritePort.data    := dCacheLoadPort.rsp.payload.data

                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := head.robPtr
                robLoadWritebackPort.exceptionOccurred := dCacheLoadPort.rsp.payload.fault
                robLoadWritebackPort.exceptionCodeIn   := ExceptionCode.LOAD_ACCESS_FAULT
                robLoadWritebackPort.result            := dCacheLoadPort.rsp.payload.data
            } elsewhen (popOnEarlyException) {
                ParallaxSim.log(L"[LQ] Alignment exception for robPtr=${head.robPtr}")
                robLoadWritebackPort.fire := True
                robLoadWritebackPort.robPtr := head.robPtr
                robLoadWritebackPort.exceptionOccurred := True
                robLoadWritebackPort.exceptionCodeIn := head.exceptionCode
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
            // `else` 分支是隐式的：当 popRequest 为 false 时, slotsNext 保持为 slotsAfterUpdates 的值。

            // >>> FIX: 在区域末尾显式地更新寄存器
            // 这样可以确保在每个时钟周期，slots 都有一个明确的驱动源
            for(i <- 0 until lqDepth) {
                slots(i) := slotsNext(i)
            }
            // <<< FIX END
        } // End of loadQueue Area

        // Release services
        hw.robServiceInst.release()
        hw.dcacheServiceInst.release()
        hw.prfServiceInst.release()
        hw.aguServiceInst.release()
        hw.storeBufferServiceInst.release()
    }
}
