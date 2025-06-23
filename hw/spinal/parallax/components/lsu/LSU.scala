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

  val dataReg       = UInt(pCfg.physGprIdxWidth)
  val pdest         = UInt(pCfg.physGprIdxWidth)

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

    val isWaitingForDCache= Bool() // 是否已发送给D-Cache并等待响应
    val isWaitingForFwd   = Bool() // 是否正在等待转发查询结果 (为未来预留)
    
    def setDefault(): this.type = {
        
        this.valid               := False
        this.addrValid           := False
        this.address             := 0
        this.size                := MemAccessSize.W
        this.robPtr              := 0
        this.pdest               := 0
        this.hasException        := False
        this.exceptionCode       := 0
        this.isWaitingForDCache  := False
        this.isWaitingForFwd     := False
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
) extends Plugin with LsuService { // LsuService 现在是可选的，因为没有外部端口了

    val hw = create early new Area {
        // IO
        val lsuInputCmd = slave(Stream(LsuInputCmd(pipelineConfig)))
        // --- 获取所有需要的服务 ---
        val robServiceInst    = getService[ROBService[RenamedUop]]
        val dcacheServiceInst = getService[DataCacheService]
        val prfServiceInst    = getService[PhysicalRegFileService]
        val aguServiceInst    = getService[AguService]

        // --- 实例化内部插件 ---
        // StoreBufferPlugin现在是LSU的内部实现细节，不再需要作为独立服务
        val storeBuffer = getService[StoreBufferService]

        // --- 为LSU获取自己的硬件端口 ---
        val lqAguPort        = aguServiceInst.newAguPort()
        val sqAguPort        = aguServiceInst.newAguPort()
        val dCacheLoadPort   = dcacheServiceInst.newLoadPort(priority = 0)
        val robWritebackPort = robServiceInst.newWritebackPort("LSU_Load")
        val prfWritePort     = prfServiceInst.newWritePort()

        // 保持服务引用
        robServiceInst.retain()
        dcacheServiceInst.retain()
        prfServiceInst.retain()
        aguServiceInst.retain()
    }

    override def getInputPort(): Stream[LsuInputCmd] = hw.lsuInputCmd

    val logic = create late new Area {
        lock.await()

        val cmdIn = hw.lsuInputCmd
        val lqPush = Stream(LsuInputCmd(pipelineConfig))
        val sbPush = Stream(LsuInputCmd(pipelineConfig))
        val (loadStream, storeStream) = StreamDemux.two(cmdIn, select = cmdIn.isLoad)

        // 直接连接
        loadStream >> lqPush
        storeStream >> sbPush

        // --- 引用硬件实例 ---
        val lqAguPort        = hw.lqAguPort
        val sbAguPort        = hw.sqAguPort
        val sbPushPort       = hw.storeBuffer.getPushPort()
        val dCacheLoadPort   = hw.dCacheLoadPort
        val robWritebackPort = hw.robWritebackPort
        val prfWritePort     = hw.prfWritePort

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
            // --- 1. Dispatch & AGU Request ---
            
            // a. Push Logic
            val canPush = !slots.map(_.valid).andR
            lqPush.ready := canPush
            
            val pushIdx = OHToUInt(slots.map(!_.valid).asBits)
            when(lqPush.fire) {
                val newSlot = slotsAfterUpdates(pushIdx)
                val cmd = lqPush.payload
                
                newSlot.valid       := True
                newSlot.addrValid   := False // 明确指出地址无效
                
                // 保存地址计算所需的操作数
                newSlot.baseReg     := cmd.baseReg
                newSlot.immediate   := cmd.immediate
                newSlot.usePc       := cmd.usePc
                newSlot.pc          := cmd.pc

                // 保存上下文
                newSlot.size        := cmd.accessSize
                newSlot.robPtr      := cmd.robPtr
                newSlot.pdest       := cmd.pdest
                
                newSlot.hasException      := False // 异常将在AGU返回后更新
                newSlot.isWaitingForDCache  := False
                newSlot.isWaitingForFwd     := False
                ParallaxSim.log(L"[LQ] PUSH: robPtr=${cmd.robPtr} to slotIdx=${pushIdx}")
            }

            // b. AGU Request Logic
            val aguRequest = Stream(AguInput(lsuConfig))
            
            // 找到第一个需要计算地址的、有效的LQ槽位
            val needsAddrCalc = slots.map(s => s.valid && !s.addrValid)
            val needsAddrOh = OHMasking.first(needsAddrCalc)
            val needsAddrIdx = OHToUInt(needsAddrOh)
            val requestSlot = slots(needsAddrIdx)

            aguRequest.valid := needsAddrCalc.orR
            aguRequest.basePhysReg := requestSlot.baseReg
            aguRequest.immediate   := requestSlot.immediate
            aguRequest.usePc       := requestSlot.usePc
            aguRequest.pc          := requestSlot.pc
            aguRequest.accessSize  := requestSlot.size
            aguRequest.qPtr        := needsAddrIdx // **关键**: 将LQ索引作为qPtr传给AGU
            // --- 透传上下文 ---
            aguRequest.robPtr      := requestSlot.robPtr
            aguRequest.isLoad      := True
            aguRequest.isStore     := False
            aguRequest.isFlush    := False
            aguRequest.physDst     := requestSlot.pdest
            
            lqAguPort.input << aguRequest

            // --- 2. AGU Response & Address Update ---
            
            val aguRsp = lqAguPort.output
            when(aguRsp.fire) {
                val updateIdx = aguRsp.payload.qPtr // **关键**: 使用AGU返回的qPtr来定位槽位
                val targetSlot = slotsAfterUpdates(updateIdx)
                
                targetSlot.addrValid := True
                targetSlot.address   := aguRsp.payload.address
                targetSlot.hasException   := aguRsp.payload.alignException
                targetSlot.exceptionCode := ExceptionCode.LOAD_ADDR_MISALIGNED
                ParallaxSim.log(L"[LQ] AGU_RSP: robPtr=${aguRsp.payload.robPtr} got address=${aguRsp.payload.address} for slotIdx=${updateIdx}")
            }
            aguRsp.ready := True // 总是准备好接收AGU的结果

            // --- 3. Execution (at head) ---
            val head = slots(0)
            val headIsReadyToExecute = head.valid && head.addrValid && !head.isWaitingForDCache && !head.isWaitingForFwd

            // ---- 与D-Cache的交互 ----
            dCacheLoadPort.cmd.valid                 := headIsReadyToExecute && !head.hasException
            dCacheLoadPort.cmd.payload.virtual       := head.address // 假设VA=PA
            dCacheLoadPort.cmd.payload.size          := MemAccessSize.toByteSizeLog2(head.size)
            dCacheLoadPort.cmd.payload.redoOnDataHazard := True
            if(pipelineConfig.transactionIdWidth > 0) {
                 dCacheLoadPort.cmd.payload.id       := head.robPtr.resize(pipelineConfig.transactionIdWidth)
            }
            dCacheLoadPort.translated.physical       := head.address
            dCacheLoadPort.translated.abord          := head.hasException
            dCacheLoadPort.cancels                   := 0
            
            when(dCacheLoadPort.cmd.fire) {
                slotsAfterUpdates(0).isWaitingForDCache := True
                ParallaxSim.log(L"[LQ] SEND_TO_DCACHE: robPtr=${head.robPtr} addr=${head.address}")
            }

            // --- LQ Pop & Writeback Logic ---
            val popRequest = False
            
            robWritebackPort.fire := False
            robWritebackPort.robPtr.assignDontCare()
            robWritebackPort.exceptionOccurred.assignDontCare()
            robWritebackPort.exceptionCodeIn.assignDontCare()
            
            prfWritePort.valid   := False
            prfWritePort.address.assignDontCare()
            prfWritePort.data.assignDontCare()

            when(dCacheLoadPort.rsp.valid && head.valid && head.isWaitingForDCache) {
                when(dCacheLoadPort.rsp.payload.redo) {
                    slotsAfterUpdates(0).isWaitingForDCache := False
                    ParallaxSim.log(L"[LQ] REDO received for robPtr=${head.robPtr}")
                } otherwise {
                    // 步骤1: 驱动PRF写端口
                    prfWritePort.valid   := True
                    prfWritePort.address := head.pdest
                    prfWritePort.data    := dCacheLoadPort.rsp.payload.data

                    // 步骤2: 通知ROB指令已完成
                    robWritebackPort.fire := True
                    robWritebackPort.robPtr := head.robPtr
                    robWritebackPort.exceptionOccurred := dCacheLoadPort.rsp.payload.fault
                    
                    popRequest := True
                    ParallaxSim.log(L"[LQ] DCACHE_RSP_OK for robPtr=${head.robPtr}, data=${dCacheLoadPort.rsp.payload.data}")
                }
            }

            // 处理指令自带的对齐异常
            when(head.valid && head.hasException && !head.isWaitingForDCache) {
                robWritebackPort.fire := True
                robWritebackPort.robPtr := head.robPtr
                robWritebackPort.exceptionOccurred := True
                robWritebackPort.exceptionCodeIn := ExceptionCode.LOAD_ADDR_MISALIGNED // 使用标准异常码
                
                popRequest := True
                ParallaxSim.log(L"[LQ] Alignment exception for robPtr=${head.robPtr}")
            }

            when(popRequest) {
                ParallaxSim.log(L"[LQ] POP: Popping slot 0 (robPtr=${slotsAfterUpdates(0).robPtr})")
                for (i <- 0 until lqDepth - 1) {
                    slotsNext(i) := slotsAfterUpdates(i + 1)
                }
                slotsNext(lqDepth - 1).setDefault()
            }
        } // End of loadQueue Area

        // =========================================================
        // >> 控制器区域 (Controller Area) - 协调AGU, SB, LQ
        // =========================================================
        val controller = new Area {
            val aguOutput = hw.lqAguPort.output
            val aguOut = aguOutput.payload
            
            // --- Store 指令分派 ---
            sbPushPort.valid := aguOutput.valid && aguOut.isStore
            sbPushPort.payload.addr      := aguOut.address
            sbPushPort.payload.data      := aguOut.storeData // 假设AGU输出中有storeData
            sbPushPort.payload.be        := aguOut.storeMask
            sbPushPort.payload.robPtr    := aguOut.robPtr
            sbPushPort.payload.accessSize:= aguOut.accessSize
            sbPushPort.payload.isFlush   := False // 假设AGU还不支持flush
            sbPushPort.payload.isIO      := False // 假设AGU还不支持IO
            sbPushPort.payload.hasEarlyException := aguOut.alignException
            sbPushPort.payload.earlyExceptionCode:= ExceptionCode.STORE_ADDRESS_MISALIGNED

            // --- 背压逻辑 ---
            // LSU准备好接收AGU的数据，当
            // 1. 如果是Store，SB准备好接收
            // 2. 如果是Load，LQ准备好接收
            aguOutput.ready := (aguOut.isStore && sbPushPort.ready) ||
                                 (aguOut.isLoad  && loadQueue.canPush)
        }

        // 释放服务引用
        hw.robServiceInst.release()
        hw.dcacheServiceInst.release()
        hw.prfServiceInst.release()
        hw.aguServiceInst.release()
    }
}
