// filename: parallax/fetch/FetchPipeline.scala

package parallax.fetch

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.components.memory._
import parallax.components.ifu.IFUService

/** The final output payload from the fetch pipeline, sent to the Decode stage.
  */
case class FetchOutput(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig) extends Bundle with Formattable {
  val pc = UInt(pCfg.pcWidth)
  val instruction = Bits(pCfg.dataWidth)
  val isFirstInGroup = Bool()
  val fault = Bool()
  val isBranch = Bool()
  val isJump = Bool()

  override def format: Seq[Any] = {
    Seq(
      L"FetchOutput(",
      L"pc=${pc},",
      L"instruction=${instruction},",
      L"isFirstInGroup=${isFirstInGroup},",
      L"fault=${fault},",
      L"isBranch=${isBranch},",
      L"isJump=${isJump}",
      L")"
    )
  }
}

case class FetchOutputCapture(
    val pc: BigInt,
    val instruction: BigInt,
    val isFirstInGroup: Boolean,
    val fault: Boolean,
    val isBranch: Boolean,
    val isJump: Boolean
) {}

object FetchOutputCapture {
  def apply(output: FetchOutput): FetchOutputCapture = {
    import spinal.core.sim._
    FetchOutputCapture(
      output.pc.toBigInt,
      output.instruction.toBigInt,
      output.isFirstInGroup.toBoolean,
      output.fault.toBoolean,
      output.isBranch.toBoolean,
      output.isJump.toBoolean
    )
  }
}

/** The service provided by the FetchPipelinePlugin.
  */
trait FetchPipelineService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchOutput]
  def getRedirectPort(): Flow[UInt] // Changed from redirect(pc: UInt)
}


class FetchPipelinePlugin(
    pCfg: PipelineConfig,
    ifuCfg: InstructionFetchUnitConfig,
    bufferDepth: Int = 8
) extends Plugin
    with FetchPipelineService {

  // --- HW Area: Declarations and Service Ports ---
  val hw = create early new Area {
    val bpuService = getService[BpuService]
    val ifuService = getService[IFUService]
    bpuService.retain()
    ifuService.retain()
    val redirectFlowInst = Flow(UInt(pCfg.pcWidth))
    val finalOutputInst = Stream(FetchOutput(pCfg, ifuCfg))
  }

  // --- Service Implementation ---
  override def getRedirectPort(): Flow[UInt] = hw.redirectFlowInst
  override def fetchOutput(): Stream[FetchOutput] = hw.finalOutputInst

  // --- Logic Area: Hardware Implementation ---
  val logic = create late new Area {
    lock.await()
    val bpu = hw.bpuService
    val ifu = hw.ifuService

    val buffer = new FetchBuffer(pCfg, ifuCfg, depth = bufferDepth)
    val predecoder = new InstructionPredecoder(pCfg)

    val pipelineFlush = hw.redirectFlowInst.valid

    // =================================================================================
    //  PC Generation and IFU/BPU Request Logic (Not in a pipeline)
    // =================================================================================
    val pcGenArea = new Area {
      val pcReg = Reg(UInt(pCfg.pcWidth)) init (pCfg.resetVector)
      
      val bpuQueryPort = bpu.newBpuQueryPort()
      val bpuResponse  = bpu.getBpuResponse()
      val ifuPort      = ifu.newFetchPort()

      val isStalled = !ifuPort.cmd.ready

      val sequentialPc = pcReg + (ifuCfg.instructionsPerFetchGroup * (pCfg.dataWidth.value / 8))
      val predictedPc  = bpuResponse.target
      val isPredictedTaken = bpuResponse.valid && bpuResponse.isTaken
      val nextPc = Mux(isPredictedTaken, predictedPc, sequentialPc)
     val normalNextPc = Mux(isPredictedTaken, predictedPc, sequentialPc)

      // === FIX START ===
      // The PC register update must prioritize the flush signal and be independent of stalls.
      when(pipelineFlush) {
        pcReg := hw.redirectFlowInst.payload
      } .elsewhen(!isStalled) {
        pcReg := normalNextPc
      }
      // === FIX END ===
      // Send requests only when not stalled and not being flushed.
      val canSendRequest = !isStalled && !pipelineFlush
      
      bpuQueryPort.valid      := canSendRequest
      bpuQueryPort.payload.pc := pcReg

      ifuPort.cmd.valid      := canSendRequest
      ifuPort.cmd.payload.pc := pcReg
      ifuPort.flush          := pipelineFlush
      
      // Connect IFU response directly to the buffer.
      // This decouples the fetch request from the instruction consumption.
      buffer.io.push << ifuPort.rsp
    }

    // =================================================================================
    //  Instruction Consumption Logic (The real, simple pipeline)
    // =================================================================================
    // This part pops from the buffer, pre-decodes, and outputs.
    // It's a simple, single-stage data path.
    val consumeArea = new Area {
      // Connect FetchBuffer pop to the final output stream, passing through the predecoder.
      hw.finalOutputInst.valid := buffer.io.pop.valid && !pipelineFlush 
      buffer.io.pop.ready := hw.finalOutputInst.ready

      predecoder.io.instruction := buffer.io.pop.instruction

      hw.finalOutputInst.payload.pc             := buffer.io.pop.pc
      hw.finalOutputInst.payload.instruction    := buffer.io.pop.instruction
      hw.finalOutputInst.payload.isFirstInGroup := buffer.io.pop.isFirstInGroup
      hw.finalOutputInst.payload.fault          := buffer.io.pop.hasFault
      hw.finalOutputInst.payload.isBranch       := predecoder.io.isBranch
      hw.finalOutputInst.payload.isJump         := predecoder.io.isJump
    }

    // =================================================================================
    //  Global Flush Logic
    // =================================================================================
    buffer.io.flush := pipelineFlush
    buffer.io.popOnBranch := False
    
    // Invalidate output stream on flush
    when(pipelineFlush) {
      hw.finalOutputInst.valid := False
    }

    // --- Release Dependencies ---
    hw.bpuService.release()
    hw.ifuService.release()
  }
}
