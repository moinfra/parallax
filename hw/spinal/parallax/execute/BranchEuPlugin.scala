// filename: parallax/execute/BranchEuPlugin.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rob.{FlushReason, ROBService}
import parallax.bpu.{BpuService, BpuUpdate}
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.components.issue.IQEntryBru
import parallax.issue.CheckpointManagerService

class BranchEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    val genMonitorSignals: Boolean = false
) extends EuBasePlugin(euName, pipelineConfig) {

  // 需要2个GPR读端口（比较两个源操作数），不需要FPR端口
  override def numGprReadPortsPerEu: Int = 2
  override def numFprReadPortsPerEu: Int = 0

  // --- ParallaxEuBase Implementation ---
  override type T_IQEntry = IQEntryBru
  override def iqEntryType: HardType[IQEntryBru] = HardType(IQEntryBru(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY
  override def getEuType: ExeUnitType.E = ExeUnitType.BRU

  // BRU现在处理更明确的UopCode
  addMicroOp(BaseUopCode.BRANCH)
  addMicroOp(BaseUopCode.JUMP_REG) // 用于 JALR
  addMicroOp(BaseUopCode.JUMP_IMM) // 用于 JAL

  // --- 硬件服务与端口 ---
  val hw = create early new Area {
    val bpuServiceInst = getService[BpuService]
    val checkpointManagerService = getService[CheckpointManagerService] 

    val bpuUpdatePort    = bpuServiceInst.newBpuUpdatePort()

    bpuServiceInst.retain()
    checkpointManagerService.retain()
  }

  // --- 监控信号暴露 (用于测试) ---
  val monitorSignals = create late new Area {
      val branchTaken   = genMonitorSignals generate Bool()
      val targetPC      = genMonitorSignals generate UInt(pipelineConfig.pcWidth)
      val actuallyTaken = genMonitorSignals generate Bool()
    // 这些信号将在buildEuLogic中被连接
  }

  // 实现新的抽象方法
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[BranchEu ${euName}] Logic start definition.")
    
    // +++ CHANGED: Pipeline expanded from 3 to 4 stages to break critical path +++
    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("s0_Dispatch")
      val s1_calc     = newStage().setName("s1_Calc")
      val s2_select   = newStage().setName("s2_Select")
      val s3_result   = newStage().setName("s3_Result")

      s0_dispatch.flushIt(robFlushPort.valid)
      s1_calc.flushIt(robFlushPort.valid)
      s2_select.flushIt(robFlushPort.valid)
      s3_result.flushIt(robFlushPort.valid)
    }.setCompositeName(this, "internal_pipeline")

    // --- Stageables ---
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType())
    
    // +++ NEW: Stageables to pass calculation results from S1 to S2 +++
    val BRANCH_TAKEN = Stageable(Bool())
    val BRANCH_TARGET = Stageable(UInt(pipelineConfig.pcWidth))

    case class MispredictInfo() extends Bundle {
      val mispredicted = Bool()
      val finalTarget = UInt(pipelineConfig.pcWidth)
      val robPtrToFlush = UInt(pipelineConfig.robPtrWidth)
      val linkValue = Bits(pipelineConfig.dataWidth)
      val actuallyTaken = Bool()
      val writesToPreg = Bool()
      val outputData = Bits(pipelineConfig.dataWidth)
    }
    val MISPREDICT_INFO = Stageable(MispredictInfo())
    
    // --- Pipeline Connections ---
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_calc)(Connection.M2S())
    pipeline.connect(pipeline.s1_calc, pipeline.s2_select)(Connection.M2S())
    pipeline.connect(pipeline.s2_select, pipeline.s3_result)(Connection.M2S())

    // --- Stage S0: Dispatch (No change) ---
    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload
    when(pipeline.s0_dispatch.isFiring) {
      report(L"[BranchEU-S0] DISPATCH: PC=0x${pipeline.s0_dispatch(EU_INPUT_PAYLOAD).pc}")
    }
    
    // +++ NEW: Stage S1: Calculate Branch Condition and Potential Target +++
    val uopAtS1 = pipeline.s1_calc(EU_INPUT_PAYLOAD)
    when(pipeline.s1_calc.isFiring) {
      report(L"[BranchEU-S1-Calc] CALC START: PC=0x${uopAtS1.pc}")
    }
    
    // 1. 获取操作数数据
    val data_rs1 = connectGprRead(pipeline.s1_calc, 0, uopAtS1.src1Tag, uopAtS1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_calc, 1, uopAtS1.src2Tag, uopAtS1.useSrc2)
    val src1Data = data_rs1
    val src2Data = data_rs2

    // 2. 执行分支条件判断 (Heavy logic)
    val branchTaken_s1 = Bool()
    switch(uopAtS1.branchCtrl.condition) {
      is(BranchCondition.EQ)  { branchTaken_s1 := (src1Data === src2Data) }
      is(BranchCondition.NE)  { branchTaken_s1 := (src1Data =/= src2Data) }
      is(BranchCondition.LT)  { branchTaken_s1 := (src1Data.asSInt < src2Data.asSInt) }
      is(BranchCondition.GE)  { branchTaken_s1 := (src1Data.asSInt >= src2Data.asSInt) }
      is(BranchCondition.LTU) { branchTaken_s1 := (src1Data.asUInt < src2Data.asUInt) }
      is(BranchCondition.GEU) { branchTaken_s1 := (src1Data.asUInt >= src2Data.asUInt) }
      is(BranchCondition.EQZ) { branchTaken_s1 := (src1Data === 0) }
      is(BranchCondition.NEZ) { branchTaken_s1 := (src1Data =/= 0) }
      is(BranchCondition.LTZ) { branchTaken_s1 := (src1Data.asSInt < 0) }
      is(BranchCondition.GEZ) { branchTaken_s1 := (src1Data.asSInt >= 0) }
      is(BranchCondition.GTZ) { branchTaken_s1 := (src1Data.asSInt > 0) }
      is(BranchCondition.LEZ) { branchTaken_s1 := (src1Data.asSInt <= 0) }
      default { branchTaken_s1 := True } // 无条件跳转
    }

    // 3. 计算分支目标地址 (Heavy logic)
    val branchTarget_s1 = UInt(pipelineConfig.pcWidth)
    switch(uopAtS1.branchCtrl.isJump ## uopAtS1.branchCtrl.isIndirect) {
      is(B"00") { branchTarget_s1 := uopAtS1.pc + uopAtS1.imm.asSInt.resize(pipelineConfig.pcWidth).asUInt }
      is(B"01") { branchTarget_s1 := (src1Data.asSInt + uopAtS1.imm.asSInt).asUInt.resized }
      is(B"10") { branchTarget_s1 := uopAtS1.pc + uopAtS1.imm.asSInt.resize(pipelineConfig.pcWidth).asUInt }
      default   { branchTarget_s1 := uopAtS1.pc + 4 }
    }
    
    // 将计算结果存入Stageable，传递给下一级
    pipeline.s1_calc(BRANCH_TAKEN) := branchTaken_s1
    pipeline.s1_calc(BRANCH_TARGET) := branchTarget_s1

    // +++ NEW: Stage S2: Select Final Target and Verify Prediction +++
    val uopAtS2 = pipeline.s2_select(EU_INPUT_PAYLOAD)
    // 从上一级寄存器获取计算结果
    val branchTaken_s2 = pipeline.s2_select(BRANCH_TAKEN)
    val branchTarget_s2 = pipeline.s2_select(BRANCH_TARGET)
    
    when(pipeline.s2_select.isFiring) {
      report(L"[BranchEU-S2-Select] SELECT START: PC=0x${uopAtS2.pc}, branchTaken(from S1)=${branchTaken_s2}")
    }

    // 4. 决定最终跳转地址和链接寄存器值 (Lighter logic)
    val nextPc = uopAtS2.pc + 4
    val finalTarget = UInt(pipelineConfig.pcWidth)
    val linkValue = Bits(pipelineConfig.dataWidth)
    val actuallyTaken = Bool()
    
    when(uopAtS2.branchCtrl.isJump) {
      actuallyTaken := True
      finalTarget := branchTarget_s2
    } otherwise {
      actuallyTaken := branchTaken_s2
      finalTarget := Mux(branchTaken_s2, branchTarget_s2, nextPc)
    }
    linkValue := nextPc.asBits.resized

    // 更新监控信号
    if (genMonitorSignals)    {
      monitorSignals.branchTaken := branchTaken_s2
      monitorSignals.targetPC := finalTarget
      monitorSignals.actuallyTaken := actuallyTaken
    }

    // 5. 分支预测验证 (Lighter logic)
    val predictionCorrect = Bool()
    val predictedTaken = uopAtS2.branchPrediction.isTaken
    val predictedTarget = uopAtS2.branchPrediction.target
    val wasPredicted = uopAtS2.branchPrediction.wasPredicted
    
    when(wasPredicted) {
      val directionCorrect = (predictedTaken === actuallyTaken)
      val targetCorrect = (!actuallyTaken) || (predictedTarget === finalTarget)
      predictionCorrect := directionCorrect && targetCorrect
    } otherwise {
      predictionCorrect := !actuallyTaken
    }
    
    when(pipeline.s2_select.isFiring) {
      report(L"[BranchEU-S2-Select] PREDICTION: wasPredicted(valid)=${wasPredicted}: predictedTaken=${predictedTaken}, actuallyTaken=${actuallyTaken}, finalTarget=0x${finalTarget}, mispredicted=${!predictionCorrect}")
    }

    // 将最终信息存入Stageable，供S3使用
    pipeline.s2_select(MISPREDICT_INFO).mispredicted := !predictionCorrect
    pipeline.s2_select(MISPREDICT_INFO).finalTarget := finalTarget
    pipeline.s2_select(MISPREDICT_INFO).robPtrToFlush := uopAtS2.robPtr + 1
    pipeline.s2_select(MISPREDICT_INFO).linkValue := linkValue
    pipeline.s2_select(MISPREDICT_INFO).actuallyTaken := actuallyTaken
    pipeline.s2_select(MISPREDICT_INFO).writesToPreg := uopAtS2.branchCtrl.isLink
    pipeline.s2_select(MISPREDICT_INFO).outputData := Mux(uopAtS2.branchCtrl.isLink, linkValue, finalTarget.asBits.resized)

    // +++BPU更新逻辑移至Commit，这是能获取到完整信息的最早阶段 +++
    // hw.bpuUpdatePort.valid := pipeline.s2_select.isFiring
    // hw.bpuUpdatePort.payload.pc := uopAtS2.pc
    // hw.bpuUpdatePort.payload.isTaken := actuallyTaken
    // hw.bpuUpdatePort.payload.target := finalTarget
    // when(hw.bpuUpdatePort.fire) {
    //   report(L"[BranchEU-BPU] BPU UPDATE (from S2): pc=0x${hw.bpuUpdatePort.payload.pc}, isTaken=${hw.bpuUpdatePort.payload.isTaken}, target=0x${hw.bpuUpdatePort.payload.target}")
    // }
    
    // +++ NEW: Stage S3: Handle Misprediction and Drive Result +++
    val mispredictInfoAtS3 = pipeline.s3_result(MISPREDICT_INFO)
    val uopAtS3 = pipeline.s3_result(EU_INPUT_PAYLOAD)

    when(pipeline.s3_result.isFiring) {
      euResult.valid := True
      euResult.uop := uopAtS3
      euResult.data := mispredictInfoAtS3.outputData
      euResult.writesToPreg := mispredictInfoAtS3.writesToPreg
      euResult.isMispredictedBranch := mispredictInfoAtS3.mispredicted // This is the signal from your screenshot
      euResult.isTaken := mispredictInfoAtS3.actuallyTaken
      euResult.hasException := False
      euResult.exceptionCode := 0
      euResult.destIsFpr := False
      report(L"[BranchEU-S3-Result] RESULT: euResult.valid=1, writesToPreg=${euResult.writesToPreg}, data=0x${euResult.data}, mispredicted=${euResult.isMispredictedBranch}")
    }

    pipeline.build()

    ParallaxLogger.log(s"[BranchEu ${euName}] 4-stage pipeline with branch logic built: S0(Dispatch) -> S1(Calc) -> S2(Select) -> S3(Result).")
    hw.bpuServiceInst.release()
    hw.checkpointManagerService.release()
  }
}
