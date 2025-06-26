package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._ // 导入FSM库
import parallax.components.dcache2.{DataCacheParameters, DataLoadPort}
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.components.dcache2.DataLoadCmd

// Command from CPU to IFU
case class IFetchCmd(pcWidth: BitCount) extends Bundle {
  val pc = UInt(pcWidth)
}

// Response from IFU to CPU
case class IFetchRsp(config: InstructionFetchUnitConfig) extends Bundle {
  val pc = UInt(config.pcWidth)
  val fault = Bool()
  val instructions = Vec(Bits(config.instructionWidth), config.instructionsPerFetchGroup)
}

// Helper case class for simulation capture
case class IFetchRspCapture(
    val pc: BigInt,
    val fault: Boolean,
    val instructions: Seq[BigInt]
) {}

object IFetchRspCapture {
  def apply(rsp: IFetchRsp): IFetchRspCapture = {
    import spinal.core.sim._
    IFetchRspCapture(
      pc = rsp.pc.toBigInt,
      fault = rsp.fault.toBoolean,
      instructions = rsp.instructions.map(_.toBigInt)
    )
  }
}

// Combined port for instruction fetching
case class IFetchPort(config: InstructionFetchUnitConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(IFetchCmd(config.pcWidth))
  val rsp = Stream(IFetchRsp(config))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}

case class InstructionFetchUnitConfig(
    dcacheParameters: DataCacheParameters,
    pcWidth: BitCount = 32 bits,
    instructionWidth: BitCount = 32 bits,
    fetchGroupDataWidth: BitCount = 64 bits,
    enableLog: Boolean = true
) {
  val xlen: Int = dcacheParameters.cpuDataWidth
  require(fetchGroupDataWidth.value >= xlen, s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be >= DCache port xlen (${xlen}).")
  require(fetchGroupDataWidth.value % xlen == 0, s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be a multiple of DCache port xlen (${xlen}).")
  val chunksPerFetchGroup: Int = fetchGroupDataWidth.value / xlen
  require(fetchGroupDataWidth.value % instructionWidth.value == 0, "fetchGroupDataWidth must be a multiple of instructionWidth")
  val instructionsPerFetchGroup: Int = fetchGroupDataWidth.value / instructionWidth.value
  require(instructionsPerFetchGroup > 0, "Must fetch at least one instruction per group")
  val bytesPerXlenChunk = xlen / 8
  
  val transactionIdWidth: Int = dcacheParameters.transactionIdWidth
  require(transactionIdWidth > 0, "DCache transaction ID width must be > 0 for this design")
}


class InstructionFetchUnit(val config: InstructionFetchUnitConfig) extends Component {

  val io = new Bundle {
    val cpuPort = slave(IFetchPort(config))
    val dcacheLoadPort = master(
      DataLoadPort(
        preTranslationWidth = config.pcWidth.value,
        postTranslationWidth = config.dcacheParameters.postTranslationWidth,
        dataWidth = config.xlen,
        refillCount = config.dcacheParameters.refillCount,
        rspAt = config.dcacheParameters.loadRspAt,
        translatedAt = config.dcacheParameters.loadTranslatedAt,
        transactionIdWidth = config.transactionIdWidth
      )
    )
  }

  // --- 内部寄存器，用于保存当前正在处理的请求 ---
  val currentPc = Reg(UInt(config.pcWidth))
  val receivedChunksBuffer = Reg(Vec(Bits(config.xlen bits), config.chunksPerFetchGroup))
  val chunksReceivedMask = Reg(Bits(config.chunksPerFetchGroup bits))
  val faultOccurred = Reg(Bool())
  
  // 用于为每个chunk生成唯一ID的计数器
  val dcacheTransIdCounter = Counter(1 << config.transactionIdWidth, inc = io.dcacheLoadPort.cmd.fire)

  // --- 用于追踪在途请求的状态 ---
  val inflight_transId = Reg(UInt(config.transactionIdWidth bits))
  val inflight_chunkIdx = Reg(UInt(log2Up(config.chunksPerFetchGroup) bits))
  val inflight_valid = Reg(Bool()) init(False)

  // 不使用的DCache端口信号
  io.dcacheLoadPort.translated.physical := io.dcacheLoadPort.cmd.payload.virtual
  io.dcacheLoadPort.translated.abord := False
  io.dcacheLoadPort.cancels.clearAll()
  
  // --- 主状态机定义 ---
  val fsm = new StateMachine {
    // 默认输出
    io.cpuPort.cmd.ready := False
    io.cpuPort.rsp.valid := False
    io.cpuPort.rsp.payload.assignDontCare()
    io.dcacheLoadPort.cmd.valid := False
    io.dcacheLoadPort.cmd.payload.assignDontCare()

    val IDLE: State = new State with EntryPoint {
      whenIsActive {
        // 只有在没有在途请求时才接收新命令 (确保上一个请求已完全结束)
        io.cpuPort.cmd.ready := !inflight_valid
        when(io.cpuPort.cmd.fire) {
          currentPc := io.cpuPort.cmd.pc
          chunksReceivedMask.clearAll()
          faultOccurred := False
          inflight_valid := False // 确保在途请求标志被清除
          if(config.enableLog) report(L"[IFU-FSM] IDLE -> FETCHING for PC 0x${io.cpuPort.cmd.pc}")
          goto(FETCHING)
        }
      }
    }

    val FETCHING: State = new State {
      whenIsActive {
        val allChunksReceived = chunksReceivedMask.andR
        when(allChunksReceived) {
          if(config.enableLog) report(L"[IFU-FSM] FETCHING -> RESPONDING for PC 0x${currentPc}")
          goto(RESPONDING)
        } elsewhen (!inflight_valid) {
          val chunkToRequestIdx = OHToUInt(PriorityEncoderOH(~chunksReceivedMask))
          val addressOfChunk = currentPc + (chunkToRequestIdx * config.bytesPerXlenChunk).resize(config.pcWidth)
          
          val cmdPayload = DataLoadCmd(config.pcWidth.value, config.xlen, config.transactionIdWidth)
          cmdPayload.virtual := addressOfChunk
          cmdPayload.size := log2Up(config.bytesPerXlenChunk)
          cmdPayload.redoOnDataHazard := False
          cmdPayload.id := dcacheTransIdCounter.value.resized
          
          // **最终修复：在发出cmd的同时，驱动所有相关的master端口**
          io.dcacheLoadPort.cmd.valid := True
          io.dcacheLoadPort.cmd.payload := cmdPayload
          
          // 驱动 translated 端口
          io.dcacheLoadPort.translated.physical := addressOfChunk // 假设直通翻译
          io.dcacheLoadPort.translated.abord := False
          
          // 驱动 cancels 端口 (我们永不取消)
          io.dcacheLoadPort.cancels.clearAll()

          when(io.dcacheLoadPort.cmd.fire) {
            inflight_valid    := True
            inflight_chunkIdx := chunkToRequestIdx
            inflight_transId  := dcacheTransIdCounter.value.resized
            if(config.enableLog) report(L"[IFU-Request] SENT request for PC 0x${currentPc}, chunk ${chunkToRequestIdx}, address 0x${addressOfChunk}, transID ${dcacheTransIdCounter.value}")
          }
        }
      }
    }



    val RESPONDING: State = new State {
      whenIsActive {
        io.cpuPort.rsp.valid := True
        io.cpuPort.rsp.payload.pc := currentPc
        io.cpuPort.rsp.payload.fault := faultOccurred

        val assembledData = Cat(receivedChunksBuffer.reverse)
        val rawWords = assembledData.subdivideIn(config.instructionWidth)
        io.cpuPort.rsp.payload.instructions := Vec(rawWords.reverse)

        when(io.cpuPort.rsp.fire) {
          if(config.enableLog) report(L"[IFU-FSM] RESPONDING -> IDLE. Popped PC 0x${currentPc}")
          goto(IDLE)
        }
      }
    }
  }

  // --- DCache响应处理 (独立于状态机) ---
  when(io.dcacheLoadPort.rsp.valid) {
    // 只处理与我们记录的在途请求ID相匹配的响应
    when(inflight_valid && io.dcacheLoadPort.rsp.id === inflight_transId) {
      // 收到响应，清除在途标志，这会允许FSM在下一个周期发出下一个请求
      inflight_valid := False 
      
      when(io.dcacheLoadPort.rsp.redo) {
        if(config.enableLog) report(L"[IFU-Response] REDO received for transID ${io.dcacheLoadPort.rsp.id}. Will re-issue.")
      } otherwise {
        // 使用记录的chunk index，而不是重新计算
        val chunkReceivedIdx = inflight_chunkIdx 
        
        receivedChunksBuffer(chunkReceivedIdx) := io.dcacheLoadPort.rsp.data
        chunksReceivedMask(chunkReceivedIdx)   := True
        faultOccurred                          := faultOccurred | io.dcacheLoadPort.rsp.fault

        if(config.enableLog) report(L"[IFU-Response] RECEIVED data for PC 0x${currentPc}, chunk ${chunkReceivedIdx}, transID ${io.dcacheLoadPort.rsp.id}, data 0x${io.dcacheLoadPort.rsp.data}")
      }
    }
  }
}
