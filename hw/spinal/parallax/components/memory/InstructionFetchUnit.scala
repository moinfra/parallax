package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.components.dcache2.{DataCacheParameters, DataLoadPort, DataLoadCmd}
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.common.PipelineConfig
// <<<<<<< NEW LOGIC: Import the predecoder
import parallax.fetch.InstructionPredecoder 

// Command from CPU to IFU (no change)
case class IFetchCmd(pcWidth: BitCount) extends Bundle {
  val pc = UInt(pcWidth)
}

// <<<<<<< NEW LOGIC: Rename to PredecodeInfo to avoid conflict, make it a generic bundle
case class PredecodeInfo() extends Bundle {
  val isBranch = Bool()
  val isJump = Bool()
}

// Response from IFU to CPU (updated with predecodes)
case class IFetchRsp(config: InstructionFetchUnitConfig) extends Bundle {
  val pc = UInt(config.pcWidth)
  val fault = Bool()
  val instructions = Vec(Bits(config.instructionWidth), config.instructionsPerFetchGroup)
  // <<<<<<< NEW LOGIC: Field name updated for clarity
  val predecodeInfo = Vec(PredecodeInfo(), config.instructionsPerFetchGroup)
  val validMask = Bits(config.instructionsPerFetchGroup bits)
}

// Capture class updated to match IFetchRsp
case class IFetchRspCapture(
    val pc: BigInt,
    val fault: Boolean,
    val instructions: Seq[BigInt],
    val predecodeInfo: Seq[(Boolean, Boolean)], // (isBranch, isJump)
    val validMask: BigInt
) {}

object IFetchRspCapture {
  def apply(rsp: IFetchRsp): IFetchRspCapture = {
    import spinal.core.sim._
    IFetchRspCapture(
      pc = rsp.pc.toBigInt,
      fault = rsp.fault.toBoolean,
      instructions = rsp.instructions.map(_.toBigInt),
      predecodeInfo = rsp.predecodeInfo.map(p => (p.isBranch.toBoolean, p.isJump.toBoolean)),
      validMask = rsp.validMask.toBigInt
    )
  }
}


// IFetchPort (no change)
case class IFetchPort(config: InstructionFetchUnitConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(IFetchCmd(config.pcWidth))
  val rsp = Stream(IFetchRsp(config))
  val flush = Bool()

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
    out(flush)
  }
}

// InstructionFetchUnitConfig needs pCfg for the predecoder
case class InstructionFetchUnitConfig(
    pCfg: PipelineConfig, // <<<<<<< NEW LOGIC: Added pCfg for predecoder instantiation
    dcacheParameters: DataCacheParameters,
    pcWidth: BitCount = 32 bits,
    instructionWidth: BitCount = 32 bits,
    fetchGroupDataWidth: BitCount = 64 bits,
    enableLog: Boolean = true
) {
  val xlen: Int = dcacheParameters.cpuDataWidth
  require(
    fetchGroupDataWidth.value >= xlen,
    s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be >= DCache port xlen (${xlen})."
  )
  require(
    fetchGroupDataWidth.value % xlen == 0,
    s"IFU fetchGroupDataWidth (${fetchGroupDataWidth.value}) must be a multiple of DCache port xlen (${xlen})."
  )
  val chunksPerFetchGroup: Int = fetchGroupDataWidth.value / xlen
  require(
    fetchGroupDataWidth.value % instructionWidth.value == 0,
    "fetchGroupDataWidth must be a multiple of instructionWidth"
  )
  val instructionsPerFetchGroup: Int = fetchGroupDataWidth.value / instructionWidth.value
  require(instructionsPerFetchGroup > 0, "Must fetch at least one instruction per group")
  val bytesPerInstruction = instructionWidth.value / 8
  val bytesPerXlenChunk = xlen / 8
  val bytesPerFetchGroup = fetchGroupDataWidth.value / 8

  val transactionIdWidth: Int = dcacheParameters.transactionIdWidth
  require(transactionIdWidth > 0, "DCache transaction ID width must be > 0 for this design")
}

// IFUIO (no change)
case class IFUIO(val config: InstructionFetchUnitConfig) extends Bundle {
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

class InstructionFetchUnit(val config: InstructionFetchUnitConfig) extends Component {

  val io = new IFUIO(config)

  // --- 内部寄存器 (no significant change) ---
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
  
  // ========================== NEW LOGIC START ==========================

  // --- 1. Instantiate Predecoders ---
  // Create a predecoder for each instruction slot in the fetch group.
  val predecoders = Seq.fill(config.instructionsPerFetchGroup)(new InstructionPredecoder(config.pCfg))

  // --- 2. Assemble Data and Predecode ---
  // This logic is combinational and prepares the final output payload.
  // It is used in the RESPONDING state.
  val assembledData = Cat(receivedChunksBuffer.reverse)
  val rawInstructions = assembledData.subdivideIn(config.instructionWidth).reverse

  // Connect each instruction to its corresponding predecoder
  for (i <- 0 until config.instructionsPerFetchGroup) {
    predecoders(i).io.instruction := rawInstructions(i)
  }

  // --- 3. Generate Valid Mask ---

  // a) Base mask from PC alignment
  val pcInGroupOffset = currentPc.asBits(log2Up(config.bytesPerFetchGroup) - 1 downto log2Up(config.bytesPerInstruction)).asUInt
  val alignmentMask = (B((BigInt(1) << config.instructionsPerFetchGroup) - 1, config.instructionsPerFetchGroup bits) |<< pcInGroupOffset).resize(config.instructionsPerFetchGroup)

   // b) Mask from in-packet unconditional jumps
  val isJumpVec = Vec(predecoders.map(_.io.isJump))

  // This logic creates a mask that is valid up to and including the first jump.
  // It works by creating a "stop" signal that propagates after the first jump is seen.
  //
  // Example for 2 instructions, where the first is a jump:
  // isJumpVec           -> [isJump_inst1, isJump_inst0] -> [False, True]
  // .asBools            -> Seq(Bool(false), Bool(true))
  // .scanLeft(False)(_|_)-> Seq(False, True, True)  // Cumulative OR: [F, F|T=T, T|F=T]
  // .dropRight(1)       -> Seq(False, True)        // These are flags to *invalidate* instructions
  // Vec(...)            -> A Vec of Bools with values [F, T]
  // B(...)              -> The Vec converted to Bits: B"10" (inst1 invalid, inst0 valid)
  // ~B(...)             -> Inverted to get the final valid mask: B"01"
  val jumpInvalidates = (isJumpVec.scanLeft(False)(_ | _)).dropRight(1)
  val jumpTruncateMask = ~B(Vec(jumpInvalidates))
  
  // c) Combine masks
  // The final valid mask is the intersection of the alignment mask and the jump truncation mask.
  // If a fault occurred, the mask is forced to zero.
  val finalValidMask = Mux(faultOccurred, B(0), alignmentMask & jumpTruncateMask)
  
  // =========================== NEW LOGIC END ===========================

  // --- 主状态机定义 ---
    // --- 主状态机定义 ---
  val fsm = new StateMachine {
    // Default outputs
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
          // <<<<<< LOG
          if (config.enableLog) report(L"[IFU-FSM] IDLE -> FETCHING for PC 0x${io.cpuPort.cmd.pc}")
          goto(FETCHING)
        }
      }
    }

    val FETCHING: State = new State {
      whenIsActive {
        when(io.cpuPort.flush) {
          // <<<<<< LOG
          if (config.enableLog) report(L"[IFU-FSM] FETCHING interrupted by FLUSH. -> IDLE")
          goto(IDLE)
        } otherwise {
          val allChunksReceived = chunksReceivedMask.andR
          when(allChunksReceived) {
            // <<<<<< LOG
            if (config.enableLog) report(L"[IFU-FSM] FETCHING -> RESPONDING for PC 0x${currentPc}. All chunks received.")
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
              // <<<<<< LOG
              if (config.enableLog) {
                report(
                  L"[IFU-Request] SENT request for PC 0x${currentPc}, chunk ${chunkToRequestIdx}, address 0x${addressOfChunk}, transID ${dcacheTransIdCounter.value}"
                )
              }
            }
          }
        }
      }
    }

    val RESPONDING: State = new State {
      whenIsActive {
        when(io.cpuPort.flush) {
          // <<<<<< LOG
          if (config.enableLog) report(L"[IFU-FSM] RESPONDING interrupted by FLUSH. -> IDLE")
          goto(IDLE)
        } otherwise {
          io.cpuPort.rsp.valid := True
          io.cpuPort.rsp.payload.pc := currentPc
          io.cpuPort.rsp.payload.fault := faultOccurred
          io.cpuPort.rsp.payload.instructions := Vec(rawInstructions)
          for(i <- 0 until config.instructionsPerFetchGroup) {
              io.cpuPort.rsp.payload.predecodeInfo(i).isBranch := predecoders(i).io.isBranch
              io.cpuPort.rsp.payload.predecodeInfo(i).isJump   := predecoders(i).io.isJump
          }
          io.cpuPort.rsp.payload.validMask := finalValidMask

          when(io.cpuPort.rsp.fire) {
            // <<<<<< LOG
            if (config.enableLog) report(L"[IFU-FSM] RESPONDING -> IDLE. Popped PC 0x${currentPc}")
            goto(IDLE)
          }
        }
      }
    }
  }

  // --- DCache响应处理 (独立于状态机) ---
  when(io.dcacheLoadPort.rsp.valid && fsm.isActive(fsm.FETCHING)) {
    when(inflight_valid && io.dcacheLoadPort.rsp.id === inflight_transId) {
      inflight_valid := False
      when(io.dcacheLoadPort.rsp.redo) {
        // <<<<<< LOG
        if (config.enableLog) report(L"[IFU-Response] REDO received for transID ${io.dcacheLoadPort.rsp.id}. Will re-issue.")
      } otherwise {
        val chunkReceivedIdx = inflight_chunkIdx
        receivedChunksBuffer(chunkReceivedIdx) := io.dcacheLoadPort.rsp.data
        chunksReceivedMask(chunkReceivedIdx) := True
        faultOccurred := faultOccurred | io.dcacheLoadPort.rsp.fault
        // <<<<<< LOG
        if (config.enableLog) {
          report(
            L"[IFU-Response] RECEIVED data for PC 0x${currentPc}, chunk ${chunkReceivedIdx}, transID ${io.dcacheLoadPort.rsp.id}, fault=${io.dcacheLoadPort.rsp.fault}, data=0x${io.dcacheLoadPort.rsp.data}"
          )
        }
      }
    }
  }
}
