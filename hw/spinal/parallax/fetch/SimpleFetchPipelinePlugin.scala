package parallax.fetch

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.components.memory.{IFetchRsp, InstructionFetchUnitConfig}
import parallax.components.ifu.IFUService
import scala.collection.mutable
import parallax.issue.CheckpointManagerService


case class FetchedInstr(pCfg: PipelineConfig) extends Bundle with Formattable {
  val pc = UInt(pCfg.pcWidth)
  val instruction = Bits(pCfg.dataWidth)
  val predecode = PredecodeInfo()
  val bpuPrediction = BranchPredictionInfo(pCfg)

  override def format: Seq[Any] = Seq(
    L"FetchedInstr(pc=0x${pc}, inst=0x${instruction}, valid=${bpuPrediction.wasPredicted}, bpuTaken=${bpuPrediction.isTaken})"
  )
}

// +++ NEW +++
// An intermediate data structure for unpacked instructions before BPU prediction is available.
case class UnpackedInstr(pCfg: PipelineConfig) extends Bundle {
    val pc = UInt(pCfg.pcWidth)
    val instruction = Bits(pCfg.dataWidth)
    val predecode = PredecodeInfo()
}


case class FetchedInstrCapture(pc: BigInt, instruction: BigInt, isBranch: Boolean, isJump: Boolean, isDirectJump: Boolean, jumpOffset: BigInt, isIdle: Boolean, bpuPredictionValid: Boolean, bpuPredictedTaken: Boolean, bpuPredictedTarget: BigInt)
object FetchedInstrCapture {
  def apply(payload: FetchedInstr): FetchedInstrCapture = {
    import spinal.core.sim._
    new FetchedInstrCapture(
        pc = payload.pc.toBigInt,
        instruction = payload.instruction.toBigInt,
        isBranch = payload.predecode.isBranch.toBoolean,
        isJump = payload.predecode.isJump.toBoolean,
        isDirectJump = payload.predecode.isDirectJump.toBoolean,
        jumpOffset = payload.predecode.jumpOffset.toBigInt,
        isIdle = payload.predecode.isIdle.toBoolean,
        bpuPredictionValid = payload.bpuPrediction.wasPredicted.toBoolean,
        bpuPredictedTaken = payload.bpuPrediction.isTaken.toBoolean,
        bpuPredictedTarget = payload.bpuPrediction.target.toBigInt
    )
  }
}

trait SimpleFetchPipelineService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchedInstr]
  def newHardRedirectPort(priority: Int): Flow[UInt]
  def newFetchDisablePort(): Bool
}

class SimpleFetchPipelinePlugin(
    pCfg: PipelineConfig,
    ifuCfg: InstructionFetchUnitConfig,
    fifoDepth: Int = 16
) extends Plugin with SimpleFetchPipelineService with HardRedirectService{

    /*
     * =======================================================================================
     *                        取指流水线 (Fetch Pipeline) 拓扑图
     * =======================================================================================
     *
     *  指令数据流 (从上到下):
     *
     *  +-------------------------+
     *  |   Instruction Fetch     |  <-- IFU (外部)
     *  |   Unit (IFU)            |
     *  +-------------------------+
     *             |
     *             | ifuPort.rsp (Stream[IFetchRsp])
     *             v
     *  +-------------------------+
     *  |      ifuRspFifo         |  (FIFO, depth=2)
     *  +-------------------------+
     *             |
     *             | Stream[IFetchRsp]
     *             v
     *  +-------------------------+
     *  |        unpacker         |  (将指令块解包成单条指令流)
     *  +-------------------------+
     *             |
     *             | unpackedStream (Stream[UnpackedInstr])
     *             v
     *  +-------------------------+
     *  |        s0_query         |  (mergePipe 阶段0: 发起BPU查询) -------> BPU Query Port
     *  +-------------------------+
     *             |
     *             | (流水线寄存器)
     *             v
     *  +-------------------------+
     *  |        s1_join          |  (mergePipe 阶段2: 合并BPU响应) <------- BPU Response
     *  +-------------------------+
     *             |
     *             | Stream[FetchedInstr]
     *             v
     *  +-------------------------+
     *  |       outputFifo        |  (FIFO, depth=16, 插件最终输出缓冲区)
     *  +-------------------------+
     *             |
     *             | fetchOutput (Stream[FetchedInstr])
     *             v
     *  +-------------------------+
     *  |   Decode Stage (后端)   |
     *  +-------------------------+
     *
     * =======================================================================================
     */

    val enableLog = false

    private val hardRedirectPorts = mutable.ArrayBuffer[(Int, Flow[UInt])]()
    private val fetchDisablePorts = mutable.ArrayBuffer[Bool]()
    private val doHardRedirect_ = Bool()

    override def newHardRedirectPort(priority: Int): Flow[UInt] = {
        framework.requireEarly()
        val port = Flow(UInt(pCfg.pcWidth))
        hardRedirectPorts += (priority -> port)
        port
    }

    override def newFetchDisablePort(): Bool = {
        framework.requireEarly()
        val port = Bool()
        fetchDisablePorts += port
        port
    }

    override def doHardRedirect(): Bool = {
        doHardRedirect_
    }

  override def fetchOutput(): Stream[FetchedInstr] = hw.finalOutputInst

  val hw = create early new Area {
    val bpuService = getService[BpuService]
    val ifuService = getService[IFUService]
    bpuService.retain(); ifuService.retain()

    val redirectFlowInst = Flow(UInt(pCfg.pcWidth))
    val finalOutputInst = Stream(FetchedInstr(pCfg))

    val ifuPort         = ifuService.newFetchPort()
    val bpuQueryPort    = bpuService.newBpuQueryPort()
  }

  val logic = create late new Area {
    lock.await()
    val bpu = hw.bpuService
    val ifu = hw.ifuService
    val ifuPort = hw.ifuPort
    val bpuQueryPort = hw.bpuQueryPort


    // Components
    val bpuResponse = bpu.getBpuResponse()
    val ifuRspFifo = StreamFifo(IFetchRsp(ifuCfg), depth = 2)
    val outputFifo = StreamFifo(FetchedInstr(pCfg), depth = fifoDepth)

    // --- MODIFIED: StreamUnpacker now outputs the intermediate UnpackedInstr type ---
    val unpacker = new StreamUnpacker(
      input_type  = IFetchRsp(ifuCfg),
      output_type = UnpackedInstr(pCfg), // <-- Changed from FetchedInstr
      unpack_func = (rsp: IFetchRsp, instr_idx: UInt) => {
        val packet = UnpackedInstr(pCfg) // <-- Changed from FetchedInstr
        packet.pc          := rsp.pc + (instr_idx << log2Up(ifuCfg.bytesPerInstruction))
        packet.instruction := rsp.instructions(instr_idx)
        packet.predecode   := rsp.predecodeInfo(instr_idx)
        // BPU prediction is NOT handled here anymore
        packet
      },
      valid_mask_getter = (rsp: IFetchRsp) => rsp.validMask
    )

    // --- Data Path ---
    ifuRspFifo.io.push << ifuPort.rsp
    unpacker.io.input << ifuRspFifo.io.pop
    val unpackedStream = unpacker.io.output
    unpackedStream.payload.pc.addAttribute("MARK_DEBUG","TRUE")
    unpackedStream.payload.instruction.addAttribute("MARK_DEBUG","TRUE")


    // ========================================================================
    // +++ NEW: Synchronous Pipeline for BPU Prediction Merging +++
    // This pipeline synchronizes the instruction stream with the BPU response stream.
    // s0: Receives unpacked instruction, sends BPU query.
    // s2: Instruction and BPU response arrive together and are merged.
    // ========================================================================
    val mergePipe = new Pipeline()
    val s0_query = mergePipe.newStage() 
    val s1_join = mergePipe.newStage()
    mergePipe.connect(s0_query, s1_join)(Connection.M2S())
    
    // --- Stage 0: Query BPU ---
    s0_query.driveFrom(unpackedStream) // Input from the unpacker

    val S0_UNPACKED_INSTR = Stageable(UnpackedInstr(pCfg))
    s0_query(S0_UNPACKED_INSTR) := unpackedStream.payload

    // BPU Query for Conditional Branches, triggered from this stage
    bpuQueryPort.valid := s0_query.isFiring && s0_query(S0_UNPACKED_INSTR).predecode.isBranch
    bpuQueryPort.payload.pc := s0_query(S0_UNPACKED_INSTR).pc
    // Transaction ID is not needed in a synchronous pipeline, but the port expects it.
    bpuQueryPort.payload.transactionId.assignDontCare()

    // --- Stage 1: Wait ---
    // This stage simply holds the data for one cycle, acting as a delay slot
    // to match the BPU's internal read latency.

    // --- Stage 2: Join Instruction and Prediction ---
    val s2_unpacked = s1_join(S0_UNPACKED_INSTR)

    // Create the final FetchedInstr by merging the instruction and the BPU response
    val mergedInstr = FetchedInstr(pCfg)
    mergedInstr.pc          := s2_unpacked.pc
    mergedInstr.instruction := s2_unpacked.instruction
    mergedInstr.predecode   := s2_unpacked.predecode

    // BPU prediction is now valid and corresponds to the instruction in this stage
    mergedInstr.bpuPrediction.wasPredicted   := bpuResponse.valid && s2_unpacked.predecode.isBranch
    mergedInstr.bpuPrediction.isTaken := bpuResponse.payload.isTaken
    mergedInstr.bpuPrediction.target  := bpuResponse.payload.target
    when(bpuResponse.valid) {
        assert(bpuResponse.payload.qPc === s2_unpacked.pc, L"BPU response PC does not match instruction PC, qPc=0x${bpuResponse.payload.qPc}, pc=0x${s2_unpacked.pc}")
        report(L"[FETCH] BPU predicted that 0x${s2_unpacked.pc} to 0x${bpuResponse.payload.target} is_taken=${bpuResponse.payload.isTaken}")
    }

    // Connect the output of the merge pipeline to the final output FIFO
    outputFifo.io.push.valid := s1_join.isFiring
    s1_join.haltWhen(!outputFifo.io.push.ready)
    outputFifo.io.push.payload := mergedInstr

    hw.finalOutputInst << outputFifo.io.pop
    mergePipe.build()

    // --- PC & Redirect Logic ---
    val fetchPc = Reg(UInt(pCfg.pcWidth)) init(pCfg.resetVector)
    fetchPc.addAttribute("MARK_DEBUG","TRUE")

    val pcOnRequest = Reg(UInt(pCfg.pcWidth))

    // --- MODIFIED: Redirect logic now based on s0_query and bpuResponse ---
    val predecode_s0 = s0_query(S0_UNPACKED_INSTR).predecode
    
    // Priority Arbitrator for Hard Redirects (Unchanged)
    val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)
    if (sortedHardRedirects.nonEmpty) {
        val valids = sortedHardRedirects.map(_.valid)
        val payloads = sortedHardRedirects.map(_.payload)
        hw.redirectFlowInst.valid   := valids.orR
        hw.redirectFlowInst.payload := MuxOH(OHMasking.first(valids), payloads)
        when(valids.orR) {
            if(enableLog) report(L"[FETCH-PLUGIN] Taken hard redirect to 0x${hw.redirectFlowInst.payload}")
        }
    } else {
        hw.redirectFlowInst.setIdle()
    }
    doHardRedirect_ := hw.redirectFlowInst.valid

    // BPU Redirect (from prediction) - now directly from bpuResponse
    val doBpuRedirect = bpuResponse.valid && bpuResponse.isTaken && !doHardRedirect_

    // Unconditional Direct Jump Redirect (from predecode) - now from s0
    val doJumpRedirect = s0_query.isValid && s0_query.isFiring && predecode_s0.isDirectJump && !doHardRedirect_
    val jumpTarget = s0_query(S0_UNPACKED_INSTR).pc + predecode_s0.jumpOffset.asUInt

    // Combine all soft redirect sources
    val doSoftRedirect = doBpuRedirect || doJumpRedirect
    val softRedirectTarget = Mux(doBpuRedirect, bpuResponse.target, jumpTarget)

    ifuPort.flush := False
    ifuRspFifo.io.flush := False
    unpacker.io.flush := False
    outputFifo.io.flush := False

    // 硬重定向必须全部清空。
    when(doHardRedirect_) {
        ifuPort.flush := True
        ifuRspFifo.io.flush := True
        unpacker.io.flush := True
        s0_query.flushIt()
        s1_join.flushIt()
        outputFifo.io.flush := True
        report(L"[FETCH-PLUGIN] Flushing entire fetch pipeline because hardRedirect=${doHardRedirect_}")
    } elsewhen (doBpuRedirect) {
        // BPU 重定向的决策点是 s1_join 阶段，所有在 s1_join 之前的指令都必须被清空。
        ifuPort.flush := True
        ifuRspFifo.io.flush := True
        unpacker.io.flush := True
        s0_query.flushIt()
    } elsewhen (doJumpRedirect) {
        ifuPort.flush := True
        ifuRspFifo.io.flush := True
        unpacker.io.flush := True
    }

    // --- Control FSM with Fetch Disable Support ---
    val fetchDisable = if (fetchDisablePorts.nonEmpty) fetchDisablePorts.orR else False
    val debugService = getServiceOption[DebugDisplayService]

    debugService.foreach(dbg => {
        dbg.setDebugValueOnce(ifuPort.cmd.valid, DebugValue.FETCH_START, expectIncr = true)
        dbg.setDebugValueOnce(ifuPort.cmd.fire, DebugValue.FETCH_FIRE, expectIncr = true)
    })

    // FSM logic is largely the same, but the condition to advance is now based on s0_query firing
    val fsm = new StateMachine {
        val IDLE = new State with EntryPoint
        val WAITING = new State
        val UPDATE_PC = new State
        val DISABLED = new State

        ifuPort.cmd.valid := False
        ifuPort.cmd.pc := fetchPc

        IDLE.whenIsActive {
            when(fetchDisable) {
                if(enableLog) report(L"[Fetch-FSM] IDLE->DISABLED: Fetch disabled")
                goto(DISABLED)
            } .otherwise {
                ifuPort.cmd.valid := True
                when(ifuPort.cmd.fire) {
                    pcOnRequest := fetchPc
                    if(enableLog) report(L"[Fetch-FSM] IDLE->WAITING: IFU cmd fired, pcOnRequest=0x${fetchPc}")
                    goto(WAITING)
                }
            }
        }

        DISABLED.whenIsActive {
            when(!fetchDisable) {
                if(enableLog) report(L"[Fetch-FSM] DISABLED->IDLE: Fetch re-enabled")
                goto(IDLE)
            }
        }

        val unpackerWasBusy = RegNext(unpacker.io.isBusy, init=False)
        val unpackerJustFinished = unpackerWasBusy && !unpacker.io.isBusy

        WAITING.whenIsActive {
            when(fetchDisable) {
                if(enableLog) report(L"[Fetch-FSM] WAITING->DISABLED: Fetch disabled")
                goto(DISABLED)
            } .elsewhen(doSoftRedirect) {
                // 软重定向具有高优先级，可以打断等待
                fetchPc := softRedirectTarget
                if(enableLog) report(L"[Fetch-FSM] WAITING->IDLE: Soft redirect to 0x${softRedirectTarget}")
                goto(IDLE)
            } .elsewhen(unpackedStream.fire) {
                if(enableLog) report(L"[Fetch-FSM] WAITING->UPDATE_PC: Unpacker finished (fire path)")
                goto(UPDATE_PC)
            } .elsewhen(unpackerJustFinished) {
                if(enableLog) report(L"[Fetch-FSM] WAITING->UPDATE_PC: Unpacker finished")
                goto(UPDATE_PC)
            }
            // 当处于 WAITING 状态时，即使 unpacker.output.fire 为高，也不做任何事，耐心等待 unpackerJustFinished
        }

        UPDATE_PC.whenIsActive {
            when(fetchDisable) {
                if(enableLog) report(L"[Fetch-FSM] UPDATE_PC->DISABLED: Fetch disabled")
                goto(DISABLED)
            } .otherwise {
                if(enableLog) report(L"[Fetch-FSM] UPDATE_PC: Normal PC update from 0x${pcOnRequest} to 0x${pcOnRequest + ifuCfg.bytesPerFetchGroup}")
                fetchPc := pcOnRequest + ifuCfg.bytesPerFetchGroup
                goto(IDLE)
            }
        }

        always {
            // 硬重定向优先级最高，可以覆盖任何状态
            when(doHardRedirect_) {
                fetchPc := hw.redirectFlowInst.payload
                goto(IDLE)
            } .elsewhen(doSoftRedirect) {
                // 软重定向也需要能打断 UPDATE_PC，以处理背靠背的跳转
                fetchPc := softRedirectTarget
                goto(IDLE)
            }
        }
    }



    when(hw.bpuQueryPort.valid) {
        assert(
             hw.bpuQueryPort.payload.pc === s0_query(S0_UNPACKED_INSTR).pc,
            "BPU query PC mismatch with fetch pipeline stage 0 PC!",
        )
    }

    when(hw.finalOutputInst.fire) {
        if(enableLog) ParallaxSim.notice(L"[FETCH] OUTPUT PC 0x${hw.finalOutputInst.payload.pc} (INSTR 0x${hw.finalOutputInst.payload.instruction})")
    }

    hw.bpuService.release()
    hw.ifuService.release()
  }
}

// ==========================================================================
// --- MODIFIED: StreamUnpacker now outputs UnpackedInstr ---
// The core logic is the same, only the output type and unpack function are adapted.
// ==========================================================================
class StreamUnpacker[T_IN <: Bundle, T_OUT <: Data](
    input_type: HardType[T_IN],
    output_type: HardType[T_OUT],
    unpack_func: (T_IN, UInt) => T_OUT,
    valid_mask_getter: T_IN => Bits
) extends Component {
    val enableLog = false
    val instructionsPerGroup = widthOf(valid_mask_getter(input_type()))

    val io = new Bundle {
        val input = slave Stream(input_type)
        val output = master Stream(output_type)
        val isBusy = out Bool()
        val flush = in Bool()
    }

    val buffer = Reg(input_type)
    val bufferValid = Reg(Bool()) init(False)
    val unpackIndex = Reg(UInt(log2Up(instructionsPerGroup) bits))

    io.isBusy := bufferValid
    io.input.ready := !bufferValid

    when(io.input.fire) {
        buffer := io.input.payload
        bufferValid := True
        unpackIndex := 0
    }

    val currentMaskBit = valid_mask_getter(buffer)(unpackIndex)
    io.output.payload := unpack_func(buffer, unpackIndex)
    io.output.valid := bufferValid && currentMaskBit

    val isLast = if (isPow2(instructionsPerGroup) && instructionsPerGroup > 1) unpackIndex.msb else unpackIndex === instructionsPerGroup - 1
    val canAdvance = io.output.fire || (bufferValid && !currentMaskBit)

    when(canAdvance) {
        when(isLast) {
            bufferValid := False
            unpackIndex := 0
        } .otherwise {
            unpackIndex := unpackIndex + 1
        }
    }

    when(io.flush) {
        bufferValid := False
        unpackIndex := 0
    }

    // Logger for this component. Note the type cast for logging.
    val logger = new Area {
            val outPayload = io.output.payload.asInstanceOf[UnpackedInstr]
            if(enableLog) report(Seq(
                L"  [[UNPACKER]] ",
                L"State(busy=${io.isBusy}, bufV=${bufferValid}, idx=${unpackIndex}) | ",
                L"Input(fire=${io.input.fire}) | ",
                L"Output(v=${io.output.valid}, r=${io.output.ready}, fire=${io.output.fire}) | ",
                L"Payload(pc=0x${outPayload.pc}) | ",
                L"Control(mask=${currentMaskBit}, isLast=${isLast}, canAdv=${canAdvance}) | ",
                L"Flush(f=${io.flush})"
            ))
    }
}
