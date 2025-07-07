package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.components.dcache2.{DataCacheParameters, DataLoadPort, DataLoadCmd}
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.common.PipelineConfig
import parallax.fetch.InstructionPredecoder 
import parallax.fetch

// --- case classes and IFetchPort remain the same ---
case class IFetchCmd(pcWidth: BitCount) extends Bundle {
  val pc = UInt(pcWidth)
}

case class IFetchRsp(config: InstructionFetchUnitConfig) extends Bundle {
  val pc = UInt(config.pcWidth)
  val fault = Bool()
  val instructions = Vec(Bits(config.instructionWidth), config.instructionsPerFetchGroup)
  val predecodeInfo = Vec(fetch.PredecodeInfo(), config.instructionsPerFetchGroup)
  val validMask = Bits(config.instructionsPerFetchGroup bits)
}
case class IFetchRspCapture(val pc: BigInt, val fault: Boolean, val instructions: Seq[BigInt], val predecodeInfo: Seq[(Boolean, Boolean)], val validMask: BigInt) {}
object IFetchRspCapture {
  def apply(rsp: IFetchRsp): IFetchRspCapture = {
    import spinal.core.sim._
    IFetchRspCapture(pc = rsp.pc.toBigInt, fault = rsp.fault.toBoolean, instructions = rsp.instructions.map(_.toBigInt), predecodeInfo = rsp.predecodeInfo.map(p => (p.isBranch.toBoolean, p.isJump.toBoolean)), validMask = rsp.validMask.toBigInt)
  }
}
case class IFetchPort(config: InstructionFetchUnitConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(IFetchCmd(config.pcWidth))
  val rsp = Stream(IFetchRsp(config))
  val flush = Bool()
  override def asMaster(): Unit = { master(cmd); slave(rsp); out(flush) }
}
case class InstructionFetchUnitConfig(pCfg: PipelineConfig, dcacheParameters: DataCacheParameters, pcWidth: BitCount = 32 bits, instructionWidth: BitCount = 32 bits, fetchGroupDataWidth: BitCount = 64 bits, enableLog: Boolean = true) {
  val xlen: Int = dcacheParameters.cpuDataWidth
  require(fetchGroupDataWidth.value >= xlen, s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be >= DCache port xlen (${xlen}).")
  require(fetchGroupDataWidth.value % xlen == 0, s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be a multiple of DCache port xlen (${xlen}).")
  val chunksPerFetchGroup: Int = fetchGroupDataWidth.value / xlen
  require(fetchGroupDataWidth.value % instructionWidth.value == 0, "fetchGroupDataWidth must be a multiple of instructionWidth")
  val instructionsPerFetchGroup: Int = fetchGroupDataWidth.value / instructionWidth.value
  require(instructionsPerFetchGroup > 0, "Must fetch at least one instruction per group")
  val bytesPerInstruction = instructionWidth.value / 8
  val bytesPerXlenChunk = xlen / 8
  val bytesPerFetchGroup = fetchGroupDataWidth.value / 8
  val transactionIdWidth: Int = dcacheParameters.transactionIdWidth
  require(transactionIdWidth > 0, "DCache transaction ID width must be > 0 for this design")
}
case class IFUIO(val config: InstructionFetchUnitConfig) extends Bundle {
  val cpuPort = slave(IFetchPort(config))
  val dcacheLoadPort = master(DataLoadPort(preTranslationWidth = config.pcWidth.value, postTranslationWidth = config.dcacheParameters.postTranslationWidth, dataWidth = config.xlen, refillCount = config.dcacheParameters.refillCount, rspAt = config.dcacheParameters.loadRspAt, translatedAt = config.dcacheParameters.loadTranslatedAt, transactionIdWidth = config.transactionIdWidth))
}

// ========================================================================
//  Final Corrected InstructionFetchUnit
// ========================================================================
class InstructionFetchUnit(val config: InstructionFetchUnitConfig) extends Component {

  val io = new IFUIO(config)

  val currentPc = Reg(UInt(config.pcWidth))
  val receivedChunksBuffer = Reg(Vec(Bits(config.xlen bits), config.chunksPerFetchGroup))
  val chunksReceivedMask = Reg(Bits(config.chunksPerFetchGroup bits))
  val faultOccurred = Reg(Bool())

  val dcacheTransIdCounter = Counter(1 << config.transactionIdWidth, inc = io.dcacheLoadPort.cmd.fire)
  val inflight_transId = Reg(UInt(config.transactionIdWidth bits))
  val inflight_chunkIdx = Reg(UInt(log2Up(config.chunksPerFetchGroup) bits))
  val inflight_valid = Reg(Bool()) init (False)

  io.dcacheLoadPort.translated.physical := io.dcacheLoadPort.cmd.payload.virtual
  io.dcacheLoadPort.translated.abord := False
  io.dcacheLoadPort.cancels.clearAll()
  
  val predecoders = Seq.fill(config.instructionsPerFetchGroup)(new InstructionPredecoder(config.pCfg))
  val assembledData = Cat(receivedChunksBuffer.reverse)
  val rawInstructions = assembledData.subdivideIn(config.instructionWidth).reverse

  for (i <- 0 until config.instructionsPerFetchGroup) {
    predecoders(i).io.instruction := rawInstructions(i)
  }

  // --- Generate Valid Mask (Safe and Simple Version) ---
  // The valid mask is now ONLY determined by alignment and memory faults.
  // The aggressive (and faulty) jump truncation optimization has been removed.
  // This logic correctly handles the case where the fetch PC is not aligned to the start of the fetch group
  // --- Generate Valid Mask (Final Corrected Version) ---
  // 计算对齐后的基地址
  val groupBasePc = currentPc(config.pcWidth.value - 1 downto log2Up(config.bytesPerFetchGroup)) ## U(0, log2Up(config.bytesPerFetchGroup) bits)

  // 在FETCHING状态，使用groupBasePc来计算addressOfChunk
  // (您的代码已经这样做了，这是正确的)
  // val addressOfChunk = groupBasePc.asUInt + ...

  // --- Generate Valid Mask (Final Correct Version) ---
  val pcInGroupOffset = currentPc(log2Up(config.bytesPerFetchGroup) - 1 downto log2Up(config.bytesPerInstruction))
  
  val alignmentMask = Bits(config.instructionsPerFetchGroup bits)
  for (i <- 0 until config.instructionsPerFetchGroup) {
      // A slot is valid if its index is greater than or equal to the PC's index within the group.
      alignmentMask(i) := U(i) >= pcInGroupOffset
  }

  val finalValidMask = Mux(faultOccurred, B(0), alignmentMask)
  
  // --- FSM (unchanged) ---
  val fsm = new StateMachine {
    io.cpuPort.cmd.ready := False
    io.cpuPort.rsp.valid := False
    io.cpuPort.rsp.payload.assignDontCare()
    io.dcacheLoadPort.cmd.valid := False
    io.dcacheLoadPort.cmd.payload.assignDontCare()

    val IDLE: State = new State with EntryPoint {
      onEntry {
        inflight_valid := False
        chunksReceivedMask.clearAll()
        faultOccurred := False
      }
      whenIsActive {
        io.cpuPort.cmd.ready := !inflight_valid && !io.cpuPort.flush
        when(io.cpuPort.cmd.fire) {
          currentPc := io.cpuPort.cmd.pc
          goto(FETCHING)
        }
      }
    }

    val FETCHING: State = new State {
      whenIsActive {
        when(io.cpuPort.flush) {
          goto(IDLE)
        } otherwise {
          val allChunksReceived = chunksReceivedMask.andR
          when(allChunksReceived) {
            goto(RESPONDING)
          } elsewhen (!inflight_valid) {
            val chunkToRequestIdx = OHToUInt(PriorityEncoderOH(~chunksReceivedMask))
            val upper = currentPc(config.pcWidth.value - 1 downto log2Up(config.bytesPerFetchGroup))
            val lower = U(0, log2Up(config.bytesPerFetchGroup) bits)
            val addressOfChunk = (upper ## lower).asUInt + (chunkToRequestIdx * config.bytesPerXlenChunk).resize(config.pcWidth)

            val cmdPayload = DataLoadCmd(config.pcWidth.value, config.xlen, config.transactionIdWidth)
            cmdPayload.virtual := addressOfChunk
            cmdPayload.size := log2Up(config.bytesPerXlenChunk)
            cmdPayload.redoOnDataHazard := False
            cmdPayload.id := dcacheTransIdCounter.value.resized

            io.dcacheLoadPort.cmd.valid := True
            io.dcacheLoadPort.cmd.payload := cmdPayload

            when(io.dcacheLoadPort.cmd.fire) {
              inflight_valid := True
              inflight_chunkIdx := chunkToRequestIdx
              inflight_transId := dcacheTransIdCounter.value.resized
            }
          }
        }
      }
    }

    val RESPONDING: State = new State {
      whenIsActive {
        when(io.cpuPort.flush) {
          goto(IDLE)
        } otherwise {
          io.cpuPort.rsp.valid := True
          io.cpuPort.rsp.payload.pc := groupBasePc.asUInt
          io.cpuPort.rsp.payload.fault := faultOccurred
          io.cpuPort.rsp.payload.instructions := Vec(rawInstructions)
          for(i <- 0 until config.instructionsPerFetchGroup) {
              io.cpuPort.rsp.payload.predecodeInfo(i) := predecoders(i).io.predecodeInfo
          }
          io.cpuPort.rsp.payload.validMask := finalValidMask

          when(io.cpuPort.rsp.fire) {
            goto(IDLE)
          }
        }
      }
    }
  }

  // --- DCache Response Handling (unchanged) ---
  when(io.dcacheLoadPort.rsp.valid && fsm.isActive(fsm.FETCHING)) {
    when(inflight_valid && io.dcacheLoadPort.rsp.id === inflight_transId) {
      inflight_valid := False  // Always clear inflight regardless of redo
      when(!io.dcacheLoadPort.rsp.redo) {
        val chunkReceivedIdx = inflight_chunkIdx
        receivedChunksBuffer(chunkReceivedIdx) := io.dcacheLoadPort.rsp.data
        chunksReceivedMask(chunkReceivedIdx) := True
        faultOccurred := faultOccurred | io.dcacheLoadPort.rsp.fault
        report(L"[IFU] DCache response processed: chunkIdx=${chunkReceivedIdx}, data=0x${io.dcacheLoadPort.rsp.data}, mask=${chunksReceivedMask}")
      } otherwise {
        report(L"[IFU] DCache response REDO: data=0x${io.dcacheLoadPort.rsp.data} - will retry")
      }
    } otherwise {
      report(L"[IFU] DCache response IGNORED: inflightValid=${inflight_valid}, rspId=${io.dcacheLoadPort.rsp.id}, inflightId=${inflight_transId}")
    }
  }

  // Debug logging
  val logger = new Area {
    report(Seq(
      L"[[IFU]] PC=0x${currentPc} | CMD(v=${io.cpuPort.cmd.valid}, r=${io.cpuPort.cmd.ready}, fire=${io.cpuPort.cmd.fire}, pc=0x${io.cpuPort.cmd.pc}) | ",
      L"RSP(v=${io.cpuPort.rsp.valid}, r=${io.cpuPort.rsp.ready}, fire=${io.cpuPort.rsp.fire}, pc=0x${io.cpuPort.rsp.pc}) | ",
      L"DCACHE_CMD(v=${io.dcacheLoadPort.cmd.valid}, r=${io.dcacheLoadPort.cmd.ready}, fire=${io.dcacheLoadPort.cmd.fire}, addr=0x${io.dcacheLoadPort.cmd.virtual}) | ",
      L"DCACHE_RSP(v=${io.dcacheLoadPort.rsp.valid}, data=0x${io.dcacheLoadPort.rsp.data}, fault=${io.dcacheLoadPort.rsp.fault}) | ",
      L"STATE(fsm=${fsm.stateReg}, inflight=${inflight_valid}, mask=${chunksReceivedMask}, allChunksReceived=${chunksReceivedMask.andR}) | ",
      L"INSTR0=0x${rawInstructions(0)}"
    ))
  }
}
