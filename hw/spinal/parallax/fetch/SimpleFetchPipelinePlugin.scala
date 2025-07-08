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


case class FetchedInstr(pCfg: PipelineConfig) extends Bundle with Formattable {
  val pc = UInt(pCfg.pcWidth)
  val instruction = Bits(pCfg.dataWidth)
  val predecode = PredecodeInfo()
  val bpuPrediction = Flow(new Bundle {
    val isTaken = Bool()
    val target = UInt(pCfg.pcWidth)
  })

  override def format: Seq[Any] = Seq(
    L"FetchedInstr(pc=0x${pc}, inst=0x${instruction}, bpuTaken=${bpuPrediction.valid && bpuPrediction.isTaken})"
  )
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
        bpuPredictionValid = payload.bpuPrediction.valid.toBoolean, 
        bpuPredictedTaken = payload.bpuPrediction.isTaken.toBoolean, 
        bpuPredictedTarget = payload.bpuPrediction.target.toBigInt
    )
  }
}

trait SimpleFetchPipelineService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchedInstr]
 /**
    * Requests a new port for a hard redirect.
    * Multiple ports can be requested. The one with the highest priority integer
    * that is valid in a given cycle will win arbitration.
    * @param priority The priority of this redirect source. A higher number means higher priority.
    * @return A Flow port to be driven by the requesting module.
    */
  def newRedirectPort(priority: Int): Flow[UInt]
}

// ========================================================================
//  Final Plugin Implementation
// ========================================================================
class SimpleFetchPipelinePlugin(
    pCfg: PipelineConfig,
    ifuCfg: InstructionFetchUnitConfig,
    fifoDepth: Int = 16
) extends Plugin with SimpleFetchPipelineService {

  val hw = create early new Area {
    val bpuService = getService[BpuService]
    val ifuService = getService[IFUService]
    bpuService.retain(); ifuService.retain()
    
    val redirectFlowInst = Flow(UInt(pCfg.pcWidth))
    val finalOutputInst = Stream(FetchedInstr(pCfg))
  }

  // --- Service Implementation: Collect redirect requests ---
  private val hardRedirectPorts = mutable.ArrayBuffer[(Int, Flow[UInt])]()
  override def newRedirectPort(priority: Int): Flow[UInt] = {
    framework.requireEarly()
    val port = Flow(UInt(pCfg.pcWidth))
    hardRedirectPorts += (priority -> port)
    port
  }

  override def fetchOutput(): Stream[FetchedInstr] = hw.finalOutputInst

  val logic = create late new Area {
    lock.await()
    val bpu = hw.bpuService
    val ifu = hw.ifuService

    // --- Components ---
    val ifuPort = ifu.newFetchPort()
    val bpuQueryPort = bpu.newBpuQueryPort()
    val bpuResponse = bpu.getBpuResponse()
    val ifuRspFifo = StreamFifo(IFetchRsp(ifuCfg), depth = 2)
    val outputFifo = StreamFifo(FetchedInstr(pCfg), depth = fifoDepth)
    val unpacker = new StreamUnpacker(
      input_type  = IFetchRsp(ifuCfg),
      output_type = FetchedInstr(pCfg),
      unpack_func = (rsp: IFetchRsp, instr_idx: UInt) => {
        val packet = FetchedInstr(pCfg)
        packet.pc          := rsp.pc + (instr_idx << log2Up(ifuCfg.bytesPerInstruction))
        packet.instruction := rsp.instructions(instr_idx)
        packet.predecode   := rsp.predecodeInfo(instr_idx)
        packet.bpuPrediction.valid := False
        packet.bpuPrediction.payload.assignDontCare()
        packet
      },
      valid_mask_getter = (rsp: IFetchRsp) => rsp.validMask
    )
    
    // --- Data Path ---
    ifuRspFifo.io.push << ifuPort.rsp
    unpacker.io.input << ifuRspFifo.io.pop
    val unpackedStream = unpacker.io.output
    
    // IDLE instruction filtering: block IDLE instructions from flowing downstream
    val filteredStream = Stream(FetchedInstr(pCfg))
    filteredStream.valid := unpackedStream.valid && !unpackedStream.payload.predecode.isIdle
    filteredStream.payload := unpackedStream.payload
    unpackedStream.ready := filteredStream.ready || unpackedStream.payload.predecode.isIdle
    
    outputFifo.io.push << filteredStream
    hw.finalOutputInst << outputFifo.io.pop

    // --- PC & Redirect Logic ---
    val fetchPc = Reg(UInt(pCfg.pcWidth)) init(pCfg.resetVector)
    val pcOnRequest = Reg(UInt(pCfg.pcWidth)) 

    val unpackedInstr = unpackedStream.payload
    val predecode = unpackedInstr.predecode
    
    // BPU Query for Conditional Branches
    bpuQueryPort.valid := unpackedStream.valid && predecode.isBranch
    bpuQueryPort.payload.pc := unpackedInstr.pc
    bpuQueryPort.payload.transactionId.assignDontCare()
        // ====================== NEW: Priority Arbitrator for Hard Redirects ======================
    // Sort ports by priority, descending. Highest priority first.
    val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)

    if (sortedHardRedirects.nonEmpty) {
        val valids = sortedHardRedirects.map(_.valid)
        val payloads = sortedHardRedirects.map(_.payload)

        // The winning hard redirect is the first valid one in the prioritized list.
        hw.redirectFlowInst.valid   := valids.orR
        hw.redirectFlowInst.payload := MuxOH(OHMasking.first(valids), payloads)
    } else {
        // If no hard redirect ports were ever requested, it can never be valid.
        hw.redirectFlowInst.valid := False
        hw.redirectFlowInst.payload.assignDontCare()
    }
    // =======================================================================================
    
    // THIS LOGIC IS NOW UNCHANGED. It consumes the result of the new arbitrator.
    val doHardRedirect = hw.redirectFlowInst.valid


    // BPU Redirect (from prediction)
val doBpuRedirect = bpuResponse.valid && bpuResponse.isTaken && !doHardRedirect
    
    // Unconditional Direct Jump Redirect (from predecode)
val doJumpRedirect = unpackedStream.valid && unpackedInstr.predecode.isDirectJump && !doHardRedirect
    val jumpTarget = unpackedInstr.pc + predecode.jumpOffset.asUInt

    // Combine all soft redirect sources
    val doSoftRedirect = doBpuRedirect || doJumpRedirect
    val softRedirectTarget = Mux(doBpuRedirect, bpuResponse.target, jumpTarget)
    
    // Hard Redirect (from backend)
    
    // --- Control FSM ---
    val fsm = new StateMachine {
        val IDLE = new State with EntryPoint
        val WAITING = new State
        val UPDATE_PC = new State
        val HALTED = new State

        ifuPort.cmd.valid := False
        ifuPort.cmd.pc := fetchPc

        IDLE.whenIsActive {
            ifuPort.cmd.valid := True
            when(ifuPort.cmd.fire) {
                pcOnRequest := fetchPc
                report(L"[FSM] IDLE->WAITING: IFU cmd fired, pcOnRequest=0x${fetchPc}")
                goto(WAITING)
            }
        }

        val unpackerWasBusy = RegNext(unpacker.io.isBusy, init=False)
        val unpackerJustFinished = unpackerWasBusy && !unpacker.io.isBusy

        WAITING.whenIsActive {
            when(doSoftRedirect) {
                fetchPc := softRedirectTarget
                report(L"[FSM] WAITING->IDLE: Soft redirect to 0x${softRedirectTarget}")
                goto(IDLE)
            } .elsewhen(unpackedStream.valid && predecode.isIdle) {
                report(L"[FSM] WAITING: IDLE detected at PC=0x${unpackedInstr.pc}, going to HALTED")
                goto(HALTED)
            } .elsewhen(unpackerJustFinished) {
                report(L"[FSM] WAITING->UPDATE_PC: Unpacker finished")
                goto(UPDATE_PC)
            }
        }
        
        UPDATE_PC.whenIsActive {
            // Normal PC increment by fetch group size (8 bytes for fetchWidth=2)
            report(L"[FSM] UPDATE_PC: Normal PC update from 0x${pcOnRequest} to 0x${pcOnRequest + ifuCfg.bytesPerFetchGroup}")
            fetchPc := pcOnRequest + ifuCfg.bytesPerFetchGroup
            goto(IDLE)
        }

        HALTED.whenIsActive {
            // Stay halted until hard redirect
            // Do nothing - fetch pipeline is stopped
            report(L"[FSM] HALTED: Fetch pipeline stopped")
        }

        always {
            when(doHardRedirect) {
                fetchPc := hw.redirectFlowInst.payload
                goto(IDLE)
            } .elsewhen(doSoftRedirect) {
                fetchPc := softRedirectTarget
                goto(IDLE)
            }
        }
    }
    
    // --- Flush Logic ---
    val needsFlush = doHardRedirect || doSoftRedirect
    ifuPort.flush := needsFlush
    ifuRspFifo.io.flush := needsFlush
    outputFifo.io.flush := doHardRedirect // Critical: Only hard flush clears the output FIFO
    unpacker.io.flush := needsFlush

    // ========================================================================
    //  Detailed Logs
    // ========================================================================
    val logger = new Area {
        report(Seq(
            L"[[FETCH-PLUGIN]] ",
            L"PC(fetch=0x${fetchPc}, onReq=0x${pcOnRequest}) | ",
            L"REQ(fire=${ifuPort.cmd.fire}) | ",
            L"UNPACKED(valid=${unpackedStream.valid}, fire=${unpackedStream.fire}, pc=0x${unpackedInstr.pc}, isJmp=${predecode.isJump}, isBranch=${predecode.isBranch}, isIdle=${predecode.isIdle}) | ",
            L"FILTERED(valid=${filteredStream.valid}, fire=${filteredStream.fire}) | ",
            L"BPU(QueryFire=${bpuQueryPort.fire}, RspValid=${bpuResponse.valid}, RspTaken=${bpuResponse.isTaken}) | ",
            L"JUMP(do=${doJumpRedirect}, target=0x${jumpTarget}) | ",
            L"REDIRECT(Soft=${doSoftRedirect}, Hard=${doHardRedirect}, Target=0x${softRedirectTarget}) | ",
            L"FLUSH(needs=${needsFlush}, outFifo=${outputFifo.io.flush}) | ",
            L"UNPACK_STATE(busy=${unpacker.io.isBusy}, fin=${fsm.unpackerJustFinished}) | ",
            L"FIFOS(rsp=${ifuRspFifo.io.occupancy}, out=${outputFifo.io.occupancy})"
        ))
    }

    hw.bpuService.release()
    hw.ifuService.release()
  }
}

// ==========================================================================
//  Final Corrected StreamUnpacker (with Logs)
// ==========================================================================
class StreamUnpacker[T_IN <: Bundle, T_OUT <: Data](
    input_type: HardType[T_IN],
    output_type: HardType[T_OUT],
    unpack_func: (T_IN, UInt) => T_OUT,
    valid_mask_getter: T_IN => Bits
) extends Component {
    
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

    val logger = new Area {
        val outPayload = io.output.payload.asInstanceOf[FetchedInstr]
        report(Seq(
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
