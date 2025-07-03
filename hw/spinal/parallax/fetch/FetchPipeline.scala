package parallax.fetch

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.components.memory.{IFetchRsp, FetchBuffer, InstructionFetchUnitConfig}
import parallax.components.ifu.IFUService

trait FetchPipelineService extends Service with LockedImpl {
  def fetchOutput(): Stream[IFetchRsp]
  def getRedirectPort(): Flow[UInt]
}

class FetchPipelinePlugin(
    pCfg: PipelineConfig,
    ifuCfg: InstructionFetchUnitConfig,
    bufferDepth: Int = 4
) extends Plugin
    with FetchPipelineService {

  val bpuTransactionIdWidth = 3 bits

  val hw = create early new Area {
    val bpuService = getService[BpuService]
    val ifuService = getService[IFUService]
    bpuService.retain(); ifuService.retain()
    val redirectFlowInst = Flow(UInt(pCfg.pcWidth))
    val finalOutputInst = Stream(IFetchRsp(ifuCfg))
  }

  override def getRedirectPort(): Flow[UInt] = hw.redirectFlowInst
  override def fetchOutput(): Stream[IFetchRsp] = hw.finalOutputInst

  val logic = create late new Area {
    lock.await()
    val bpu = hw.bpuService
    val ifu = hw.ifuService
    val buffer = new FetchBuffer(ifuCfg, depth = bufferDepth)

    val fetchPipe = new Pipeline()

    val ifuPort = ifu.newFetchPort()
    val bpuQueryPort = bpu.newBpuQueryPort()
    val bpuResponse = bpu.getBpuResponse()
    
    val PC = Stageable(UInt(pCfg.pcWidth))
    val BPU_TID = Stageable(UInt(bpuTransactionIdWidth))

    // FIX: Stageable to carry BPU prediction info through the pipeline
    val BPU_PRED_IS_TAKEN = Stageable(Bool())
    val BPU_PRED_TARGET = Stageable(UInt(pCfg.pcWidth))
    val BPU_PRED_VALID = Stageable(Bool())

    // ========================================================================
    //  PC Generation State Machine
    // ========================================================================
    val pcReg = Reg(UInt(pCfg.pcWidth)) init (pCfg.resetVector)
    val pcValid = Reg(Bool()) init(True)
    val bpuTidCounter = Counter(1 << bpuTransactionIdWidth.value)

    // ========================================================================
    //  Pipeline Stages Definition
    // ========================================================================
    val f1 = fetchPipe.newStage()
    val f2 = fetchPipe.newStage()
    val f3 = fetchPipe.newStage()
    
    fetchPipe.connect(f1, f2)(Connection.M2S())
    fetchPipe.connect(f2, f3)(Connection.M2S())

    f1.isValid := pcValid
    f1(PC) := pcReg
    f1(BPU_TID) := bpuTidCounter.value.resized
    
    when(bpuResponse.valid && f2(BPU_TID) === bpuResponse.payload.transactionId) {
      f2(BPU_PRED_IS_TAKEN) := bpuResponse.payload.isTaken
      f2(BPU_PRED_TARGET) := bpuResponse.payload.target
      f2(BPU_PRED_VALID) := True
    } .otherwise {
      f2(BPU_PRED_IS_TAKEN).assignDontCare()
      f2(BPU_PRED_TARGET).assignDontCare()
      f2(BPU_PRED_VALID) := False
    }

    // ========================================================================
    //  Control Logic & PC Generation (Reworked for Correct Flushing)
    // ========================================================================
    
    val f3_hasPrediction = f3.isValid && f3(BPU_PRED_VALID)
    val f3_isPredictedTaken = f3_hasPrediction && f3(BPU_PRED_IS_TAKEN)
    val f3_predictedPc = f3(BPU_PRED_TARGET)

    val externalRedirect = hw.redirectFlowInst
    val f3_wantsToKill = f3.isValid && f3_isPredictedTaken
    
    // --- Final Fix: Differentiate flush types ---
    // `pipelineFlush` clears the speculative front-end stages (PC gen, F1, F2).
    val pipelineFlush = externalRedirect.valid || f3_wantsToKill

    // `downstreamFlush` clears units that hold committed or semi-committed state (IFU, Buffer).
    // BPU prediction should NOT flush the buffer, only an external redirect should.
    val downstreamFlush = externalRedirect.valid

    // --- Stall Signal ---
    val isStalled = !ifuPort.cmd.ready || buffer.io.isFull
    f1.haltWhen(isStalled)

    // --- PC Next Logic ---
    val sequentialPc = pcReg + ifuCfg.bytesPerFetchGroup
    val pcAfterPrediction = Mux(f3_wantsToKill, f3_predictedPc, sequentialPc)
    val finalNextPc = Mux(externalRedirect.valid, externalRedirect.payload, pcAfterPrediction)

    // --- PC Update Logic ---
    val pcGenCanAdvance = f1.isReady && !pipelineFlush

    when(pipelineFlush) {
        pcReg := finalNextPc
        pcValid := True
        bpuTidCounter.clear()
    } .elsewhen(pcGenCanAdvance) {
        pcReg := finalNextPc
        pcValid := True
    } .otherwise {
        pcReg := pcReg
        pcValid := pcValid
    }

    when(f1.isFiring) {
        bpuTidCounter.increment()
    }

    // Apply the flush signals to the correct units
    f1.flushIt(pipelineFlush)
    f2.flushIt(pipelineFlush)
    
    fetchPipe.build()

    // ========================================================================
    //  Downstream Connections
    // ========================================================================
    ifuPort.cmd.valid := f1.isFiring
    ifuPort.cmd.payload.pc := f1(PC)
    bpuQueryPort.valid := f1.isFiring
    bpuQueryPort.payload.pc := f1(PC)
    bpuQueryPort.payload.transactionId := f1(BPU_TID)

    // IFU is also part of the speculative path, so it flushes with the pipeline.
    ifuPort.flush := pipelineFlush 
    
    // Buffer is flushed ONLY by external redirects.
    buffer.io.flush := downstreamFlush
    
    // Use popOnBranch to discard speculatively fetched groups already in the buffer AFTER the branch.
    // This assumes the BPU prediction kill signal arrives when the group *after* the branch is at the head of the buffer.
    // For a simple pipeline, this might just be f3_wantsToKill.
    buffer.io.popOnBranch := f3_wantsToKill

    buffer.io.push << ifuPort.rsp
    hw.finalOutputInst << buffer.io.pop
    
    // ========================================================================
    //  Logging
    // ========================================================================
    ParallaxSim.log(
      Seq(
        L"[[FP]] ",
        L"PC_GEN(pc=0x${pcReg}, v=${pcValid}, tid=${bpuTidCounter.value}) | ",
        L"F1(pc=0x${f1(PC)}, v=${f1.isValid}, r=${f1.isReady}, fire=${f1.isFiring}) ",
        L"F3(pc=0x${f3(PC)}, v=${f3.isValid}, taken=${f3.isValid.mux(f3(BPU_PRED_IS_TAKEN), False)}) | ",
        L"BPU_Stream(rspV=${bpuResponse.valid}, rspTID=${bpuResponse.payload.transactionId}) | ",
        L"CTRL(stall=${isStalled}, pipeFlush=${pipelineFlush}, downstreamFlush=${downstreamFlush}, bpuKill=${f3_wantsToKill}) | ",
        L"BUF(push=${buffer.io.push.fire}, pop=${buffer.io.pop.fire}, flush=${buffer.io.flush}, popOnBranch=${buffer.io.popOnBranch})"
      )
    )
    
    hw.bpuService.release()
    hw.ifuService.release()
  }
}
