// package parallax.fetch

// import spinal.core._
// import spinal.lib._
// import spinal.lib.pipeline._
// import parallax.components.memory._
// import parallax.utilities._
// import parallax.common._
// import spinal.core._
// import spinal.lib._
// import parallax.common._
// import parallax.issue.IssuePipeline
// import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
// import parallax.components.lsu.SkidBuffer
// import parallax.bpu._


// class BpuAndAlignerPlugin(val pCfg: PipelineConfig) extends Plugin with LockedImpl with IssueBpuSignalService {
//   override def getBpuSignals(): IssueBpuSignals = setup.bpuSignals

//   val setup = create early new Area {
//     val bpuSignals = IssueBpuSignals(pCfg)
//     val fetchPipeline = getService[FetchPipeline]
//     val issuePipeline = getService[IssuePipeline]
//     val bpuService = getService[BpuService]
//     val redirectService = getService[FetchRedirectService]

//     // 我们会消费 FetchPipeline 的输出，并驱动 IssuePipeline 的输入
//     fetchPipeline.retain()
//     issuePipeline.retain()
//     bpuService.retain()
//     redirectService.retain()
    
//     // 在 IssuePipeline 中实例化我们的信号包
//     val issueEntryStage = issuePipeline.entryStage
//     issueEntryStage(bpuSignals.INSTRUCTION_VALID_MASK) // 使用 this.bpuSignals
    
//     val renameStage = issuePipeline.pipeline.s1_rename
//     renameStage(bpuSignals.PREDICTION_INFO) // 使用 this.bpuSignals
//   }

//   val logic = create late new Area {
//     lock.await()
//     val fetchPpl = setup.fetchPipeline
//     val issuePpl = setup.issuePipeline
//     val bpuService = setup.bpuService
//     val redirectService = setup.redirectService
//     val bpuSignals = setup.bpuSignals

//     // --- 1. 从 FetchPipeline 获取指令组 ---
//     val fromFetch = fetchPpl.exitStage.toStream
//       .throwWhen(fetchPpl.exitStage(fetchPpl.signals.FETCH_FAULT))
//       .m2sPipe()

//     val inputPayload = fromFetch.payload // Payload现在包含了所有从Fetch流水线传来的Stageable
//     val fetchedPc = inputPayload(fetchPpl.signals.FETCHED_PC)
//     val instructions = inputPayload(fetchPpl.signals.INSTRUCTION_GROUP)


// val predecoders = Seq.fill(pCfg.fetchWidth)(new InstructionPredecoder(pCfg))
//     predecoders.zip(instructions).foreach { case (pd, inst) => pd.io.instruction := inst }
//     val isControlFlowVec = predecoders.map(p => p.io.isBranch || p.io.isJump)
    
//     val firstControlFlowOh = OHMasking.first(isControlFlowVec.asBits)
//     val hasControlFlow = firstControlFlowOh.orR
//     val firstControlFlowIdx = OHToUInt(firstControlFlowOh)
    
//     val bpuQueryPort = bpuService.newBpuQueryPort()
//     bpuQueryPort.valid := fromFetch.valid && hasControlFlow
//     bpuQueryPort.payload.pc := fetchedPc + (firstControlFlowIdx << log2Up(pCfg.bytesPerInstruction))
//     val bpuResponse = bpuService.getBpuResponse()
    
//     val validMask = Bits(pCfg.fetchWidth bits)
//     when(hasControlFlow && bpuResponse.isTaken) {
//       // 有效位应该到分支指令（包含它自己）
//       validMask := ((U(1, pCfg.fetchWidth + 1 bits) << (firstControlFlowIdx + 1)) - 1).asBits
//     } otherwise {
//       validMask.setAll()
//     }
    
//     val predictionInfo = BranchPredictionInfo(pCfg)
//     predictionInfo.setDefault()
//     when(hasControlFlow) {
//         predictionInfo.isTaken := bpuResponse.isTaken
//         predictionInfo.target  := bpuResponse.target
//     }

//     // --- 3. 连接到 IssuePipeline 的入口 ---
//     val issueEntry = issuePpl.entryStage
//     issueEntry.arbitrationFrom(fromFetch)
    
//     issueEntry(issuePpl.signals.GROUP_PC_IN)         := inputGroup.pc
//     issueEntry(issuePpl.signals.RAW_INSTRUCTIONS_IN) := inputGroup.instructions
//     issueEntry(issuePpl.signals.IS_FAULT_IN)         := inputGroup.fault
    
//     // 驱动新增的 Stageable
//     issueEntry(bpuSignals.INSTRUCTION_VALID_MASK) := validMask
//     issueEntry(bpuSignals.PREDICTION_INFO)       := predictionInfo

//     // --- 4. PC 重定向 ---
//     when(fromFetch.fire && hasControlFlow && bpuResponse.isTaken) {
//       redirectService.redirectFlow.valid   := True
//       redirectService.redirectFlow.payload := bpuResponse.target
//     }
    
//     // 释放服务
//     setup.fetchPipeline.release()
//     setup.issuePipeline.release()
//     setup.bpuService.release()
//     setup.redirectService.release()
//   }
// }
