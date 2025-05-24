// // filename: hw/spinal/parallax/execute/IntExecutePipelinePlugin.scala
// // -- MODIFICATION START (Removing StageLink, ensuring EXEC_DEST_IS_FPR is driven) --
// package parallax.execute

// import spinal.core._
// import spinal.lib._
// import spinal.lib.pipeline.{Pipeline, Stage, Stageable, Connection} // Removed StageLink
// import parallax.common._
// import parallax.PhysicalRegFileService
// import parallax.components.rob.ROBService
// import parallax.utilities._
// import parallax.components.execute.DemoAlu

// case class IntAluContext(val config: PipelineConfig) extends Bundle {}

// class IntExecutePipelinePlugin(
//     override val euName: String,
//     override val pipelineConfig: PipelineConfig,
//     override val readPhysRsDataFromPush: Boolean = false
// ) extends ParallaxEuBase(euName, pipelineConfig, readPhysRsDataFromPush) {

//   override type T_EuSpecificContext = IntAluContext
//   override protected def euSpecificContextType: HardType[T_EuSpecificContext] = HardType(IntAluContext(pipelineConfig))
//   override protected def euRegType: EuRegType.C = EuRegType.GPR_ONLY

//   val RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
//   val RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))

//   var s0_dispatch: Stage = null
//   var s1_readRegs: Stage = null
//   var s2_executeAlu: Stage = null
//   var s3_writebackPrep: Stage = null

//   override protected def buildMicroPipeline(pipeline: Pipeline): Unit = {
//     s0_dispatch = pipeline.newStage().setName("s0_Dispatch")
//     // s0_dispatch.setCompositedName(this, "s0_Dispatch") // setCompositedName is on Plugin/Area, not Stage

//     var lastStage = s0_dispatch

//     if (!readPhysRsDataFromPush) {
//       s1_readRegs = pipeline.newStage().setName("s1_ReadRegs")
//       s1_readRegs(RS1_DATA) // Declare stageable for this stage
//       s1_readRegs(RS2_DATA) // Declare stageable for this stage
//       pipeline.connect(lastStage, s1_readRegs)(Connection.M2S())
//       lastStage = s1_readRegs
//     }

//     s2_executeAlu = pipeline.newStage().setName("s2_ExecuteAlu")
//     // EXEC_ signals are declared on s2_executeAlu because it produces them
//     s2_executeAlu(commonSignals.EXEC_RESULT_DATA)
//     s2_executeAlu(commonSignals.EXEC_WRITES_TO_PREG)
//     s2_executeAlu(commonSignals.EXEC_HAS_EXCEPTION)
//     s2_executeAlu(commonSignals.EXEC_EXCEPTION_CODE)
//     // Since this is GPR_ONLY, EXEC_DEST_IS_FPR is effectively always False if this EU determines it.
//     // It must be declared if getWritebackStage returns this stage and base class reads it.
//     s2_executeAlu(commonSignals.EXEC_DEST_IS_FPR)


//     pipeline.connect(lastStage, s2_executeAlu)(Connection.M2S())
//     lastStage = s2_executeAlu

//     s3_writebackPrep = pipeline.newStage().setName("s3_WritebackPrep")
//     // s3_writebackPrep also needs EXEC_DEST_IS_FPR if it's the writeback stage and base class reads it.
//     // More accurately, the stage returned by getWritebackStage must have EXEC_DEST_IS_FPR declared.
//     // If s2 is the WB stage, it's declared above. If s3 is WB, it needs it here.
//     // Let's assume s2_executeAlu is where these are finalized.
//     // s3_writebackPrep is primarily for pipeline balancing or final output registration.
//     // The values from s2_executeAlu will flow to s3_writebackPrep.

//     pipeline.connect(lastStage, s3_writebackPrep)(Connection.M2S())

//     addMicroOp(BaseUopCode.ALU)
//   }

//   override protected def connectPipelineLogic(pipeline: Pipeline): Unit = {
//     val inputPayload = s0_dispatch(EU_INPUT_PAYLOAD)
//     val renamedUop   = inputPayload.renamedUop
//     val decodedUop   = renamedUop.decoded

//     if (!readPhysRsDataFromPush) {
//       connectGprRead(s1_readRegs, 0, renamedUop.rename.physSrc1.idx, decodedUop.useArchSrc1, RS1_DATA)
//       connectGprRead(s1_readRegs, 1, renamedUop.rename.physSrc2.idx, decodedUop.useArchSrc2, RS2_DATA)
//       when(!decodedUop.useArchSrc1) { s1_readRegs(RS1_DATA) := 0 }
//       when(!decodedUop.useArchSrc2 && decodedUop.immUsage === ImmUsageType.NONE) { s1_readRegs(RS2_DATA) := 0 }
//     }

//     val alu = new DemoAlu(xlen = pipelineConfig.xlen, enableOverflowDetect = true)
//     val aluCtrl = decodedUop.aluCtrl

//     val aluSrc1 = if (readPhysRsDataFromPush) inputPayload.src1Data else s2_executeAlu(RS1_DATA) // Read from s2 inputs
//     val aluSrc2 = Bits(pipelineConfig.dataWidth)

//     if (readPhysRsDataFromPush) {
//         when(decodedUop.immUsage === ImmUsageType.SRC_ALU) {
//             aluSrc2 := decodedUop.imm.resized
//         } otherwise {
//             aluSrc2 := inputPayload.src2Data
//         }
//     } else {
//         when(decodedUop.immUsage === ImmUsageType.SRC_ALU) {
//             aluSrc2 := decodedUop.imm.resized
//         } otherwise {
//             aluSrc2 := s2_executeAlu(RS2_DATA) // Read from s2 inputs
//         }
//     }
    
//     alu.io.src1     := aluSrc1
//     alu.io.src2     := aluSrc2
//     alu.io.isSub    := aluCtrl.isSub
//     alu.io.isAdd    := aluCtrl.isAdd
//     alu.io.logicOp  := aluCtrl.logicOp
//     alu.io.isSigned := aluCtrl.isSigned

//     s2_executeAlu(commonSignals.EXEC_RESULT_DATA)      := alu.io.result
//     s2_executeAlu(commonSignals.EXEC_WRITES_TO_PREG)   := renamedUop.rename.writesToPhysReg
//     s2_executeAlu(commonSignals.EXEC_HAS_EXCEPTION)    := alu.io.overflow || decodedUop.hasDecodeException
//     s2_executeAlu(commonSignals.EXEC_EXCEPTION_CODE)   := Mux(decodedUop.hasDecodeException,
//                                                             U(decodedUop.decodeExceptionCode.asBits.resize(pipelineConfig.exceptionCodeWidth)),
//                                                             Mux(alu.io.overflow, U"x01", U"x00")).resized
//     // This EU is GPR_ONLY, so the destination is never an FPR *from its perspective*.
//     // It must drive EXEC_DEST_IS_FPR to False.
//     s2_executeAlu(commonSignals.EXEC_DEST_IS_FPR)      := False


//     when(decodedUop.uopCode === BaseUopCode.NOP) {
//       s2_executeAlu(commonSignals.EXEC_RESULT_DATA)    := B(0)
//       s2_executeAlu(commonSignals.EXEC_WRITES_TO_PREG) := False
//       s2_executeAlu(commonSignals.EXEC_HAS_EXCEPTION)  := False
//       s2_executeAlu(commonSignals.EXEC_DEST_IS_FPR)    := False // NOP doesn't have FPR dest
//     }
//     when(decodedUop.uopCode === BaseUopCode.ILLEGAL || decodedUop.hasDecodeException) {
//       s2_executeAlu(commonSignals.EXEC_WRITES_TO_PREG) := False
//       s2_executeAlu(commonSignals.EXEC_HAS_EXCEPTION)  := True
//       s2_executeAlu(commonSignals.EXEC_DEST_IS_FPR)    := False // Exception implies no specific dest type
//     }
//   }

//   override protected def getWritebackStage(pipeline: Pipeline): Stage = {
//     // If s2_executeAlu produces all results, and s3 is just for timing/buffering
//     // then s3_writebackPrep is the stage from which ParallaxEuBase reads.
//     // The signals EXEC_RESULT_DATA etc. will be implicitly passed from s2 to s3.
//     s3_writebackPrep
//   }
// }
// // -- MODIFICATION END --
