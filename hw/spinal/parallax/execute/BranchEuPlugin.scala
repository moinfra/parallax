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

class BranchEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig
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
    val robServiceInst = getService[ROBService[RenamedUop]]
    val bpuServiceInst = getService[BpuService]

    val robFlushPort     = robServiceInst.getFlushPort()
    val bpuUpdatePort    = bpuServiceInst.newBpuUpdatePort()

    robServiceInst.retain()
    bpuServiceInst.retain()
  }

  // --- 监控信号暴露 (用于测试) ---
  val monitorSignals = create late new Area {
    val branchTaken = Bool()
    val targetPC = UInt(pipelineConfig.pcWidth)
    val actuallyTaken = Bool()
    
    // 这些信号将在buildEuLogic中被连接，不在这里设置默认值
  }

  // 实现新的抽象方法
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[BranchEu ${euName}] Logic start definition.")
    
    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("s0_Dispatch")
      val s1_resolve  = newStage().setName("s1_Resolve")
    }.setCompositeName(this, "internal_pipeline")

    // 添加Stageable和输入连接
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType())
    
    // 连接输入端口到流水线入口
    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload
    
    // 添加调试日志 - S0阶段
    when(pipeline.s0_dispatch.isFiring) {
      report(L"[BranchEU-S0] DISPATCH: PC=0x${pipeline.s0_dispatch(EU_INPUT_PAYLOAD).pc}, branchCtrl.condition=${pipeline.s0_dispatch(EU_INPUT_PAYLOAD).branchCtrl.condition}")
    }
    
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_resolve)(Connection.M2S())

    // --- Stage S1: Resolve Branch ---
    val uopAtS1 = pipeline.s1_resolve(EU_INPUT_PAYLOAD)

    // 添加调试日志 - S1开始阶段
    when(pipeline.s1_resolve.isFiring) {
      report(L"[BranchEU-S1] RESOLVE START: PC=0x${uopAtS1.pc}, useSrc1=${uopAtS1.useSrc1}, useSrc2=${uopAtS1.useSrc2}, src1Tag=${uopAtS1.src1Tag}, src2Tag=${uopAtS1.src2Tag}")
    }

    // 1. 获取操作数数据 - 从PRF读取
    val data_rs1 = connectGprRead(pipeline.s1_resolve, 0, uopAtS1.src1Tag, uopAtS1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_resolve, 1, uopAtS1.src2Tag, uopAtS1.useSrc2)
    val src1Data = data_rs1
    val src2Data = data_rs2

    // 2. 执行分支条件判断
    val branchTaken = Bool()
    switch(uopAtS1.branchCtrl.condition) {
      is(BranchCondition.EQ)  { branchTaken := (src1Data === src2Data) }
      is(BranchCondition.NE)  { branchTaken := (src1Data =/= src2Data) }
      is(BranchCondition.LT)  { branchTaken := (src1Data.asSInt < src2Data.asSInt) }
      is(BranchCondition.GE)  { branchTaken := (src1Data.asSInt >= src2Data.asSInt) }
      is(BranchCondition.LTU) { branchTaken := (src1Data.asUInt < src2Data.asUInt) }
      is(BranchCondition.GEU) { branchTaken := (src1Data.asUInt >= src2Data.asUInt) }
      // 零比较分支
      is(BranchCondition.EQZ) { branchTaken := (src1Data === 0) }
      is(BranchCondition.NEZ) { branchTaken := (src1Data =/= 0) }
      is(BranchCondition.LTZ) { branchTaken := (src1Data.asSInt < 0) }
      is(BranchCondition.GEZ) { branchTaken := (src1Data.asSInt >= 0) }
      is(BranchCondition.GTZ) { branchTaken := (src1Data.asSInt > 0) }
      is(BranchCondition.LEZ) { branchTaken := (src1Data.asSInt <= 0) }
      default { branchTaken := True } // 无条件跳转(JAL等)
    }

    // 添加分支条件判断日志
    when(pipeline.s1_resolve.isFiring) {
      report(L"[BranchEU-S1] CONDITION: src1Data=0x${src1Data}, src2Data=0x${src2Data}, condition=${uopAtS1.branchCtrl.condition}, branchTaken=${branchTaken}")
    }

    // 3. 计算分支目标地址
    val branchTarget = UInt(pipelineConfig.pcWidth)
    val nextPc = uopAtS1.pc + 4 // 默认下一条指令地址
    
    switch(uopAtS1.branchCtrl.isJump ## uopAtS1.branchCtrl.isIndirect) {
      // 00: 条件分支 (BEQ, BNE 等) - LoongArch uses PC+4 for branch target calculation
      is(B"00") {
        branchTarget := uopAtS1.pc + 4 + uopAtS1.imm.asSInt.resize(pipelineConfig.pcWidth).asUInt
      }
      // 01: 间接跳转 (JALR)
      is(B"01") {
        branchTarget := (src1Data.asSInt + uopAtS1.imm.asSInt).asUInt.resized
      }
      // 10: 直接跳转 (JAL)
      is(B"10") {
        branchTarget := uopAtS1.pc + uopAtS1.imm.asSInt.resize(pipelineConfig.pcWidth).asUInt
      }
      // 11: 保留
      default {
        branchTarget := nextPc
      }
    }

    // 4. 决定最终跳转地址和链接寄存器值
    val finalTarget = UInt(pipelineConfig.pcWidth)
    val linkValue = Bits(pipelineConfig.dataWidth)
    val actuallyTaken = Bool()
    
    when(uopAtS1.branchCtrl.isJump) {
      // 无条件跳转 (JAL/JALR) - 总是跳转
      actuallyTaken := True
      finalTarget := branchTarget
    } otherwise {
      // 条件分支 - 根据条件决定
      actuallyTaken := branchTaken
      finalTarget := Mux(branchTaken, branchTarget, nextPc)
    }
    
    // 链接寄存器值 (用于JAL/JALR)
    linkValue := nextPc.asBits.resized

    // 5. 更新监控信号
    monitorSignals.branchTaken := branchTaken
    monitorSignals.targetPC := finalTarget
    monitorSignals.actuallyTaken := actuallyTaken

    // 5. 分支预测验证
    val predictionCorrect = Bool()
    val predictedTaken = uopAtS1.branchPrediction.isTaken
    val predictedTarget = uopAtS1.branchPrediction.target
    val wasPredicted = uopAtS1.branchPrediction.wasPredicted
    
    // 验证预测结果：
    // 1. 检查预测的跳转方向是否正确
    // 2. 如果预测跳转，检查目标地址是否正确
    when(wasPredicted) {
      val directionCorrect = (predictedTaken === actuallyTaken)
      val targetCorrect = (!actuallyTaken) || (predictedTarget === finalTarget)
      predictionCorrect := directionCorrect && targetCorrect
    } otherwise {
      // CRITICAL FIX: When no prediction is available, default assumption is "not taken"
      // If branch is actually taken, this is a misprediction
      predictionCorrect := !actuallyTaken
    }

    // 添加分支预测验证日志
    when(pipeline.s1_resolve.isFiring) {
      report(L"[BranchEU-S1] PREDICTION: wasPredicted=${wasPredicted}, predictedTaken=${predictedTaken}, actuallyTaken=${actuallyTaken}, finalTarget=0x${finalTarget}, predictionCorrect=${predictionCorrect}")
    }

    // 6. 更新euResult输出
    when(pipeline.s1_resolve.isFiring) {
      euResult.valid := True
      euResult.uop := uopAtS1
      
      // 根据指令类型决定写回数据
      when(uopAtS1.branchCtrl.isLink) {
        // JAL/JALR 需要写回链接地址
        euResult.data := linkValue
        euResult.writesToPreg := True
      } otherwise {
        // 普通分支指令不写回寄存器
        euResult.data := finalTarget.asBits.resized
        euResult.writesToPreg := False
      }
      
      euResult.hasException := False
      euResult.exceptionCode := 0
      euResult.destIsFpr := False
      
      // 添加执行结果日志
      report(L"[BranchEU-S1] RESULT: euResult.valid=1, writesToPreg=${euResult.writesToPreg}, data=0x${euResult.data}")
    } otherwise {
      euResult.valid := False
      euResult.uop := uopAtS1
      euResult.data := 0
      euResult.writesToPreg := False
      euResult.hasException := False
      euResult.exceptionCode := 0
      euResult.destIsFpr := False
    }

    // 7. BPU更新逻辑
    when(pipeline.s1_resolve.isFiring) {
      hw.bpuUpdatePort.valid := True
      hw.bpuUpdatePort.payload.pc := uopAtS1.pc
      hw.bpuUpdatePort.payload.isTaken := actuallyTaken
      hw.bpuUpdatePort.payload.target := finalTarget
    } otherwise {
      hw.bpuUpdatePort.valid := False
      hw.bpuUpdatePort.payload.pc := 0
      hw.bpuUpdatePort.payload.isTaken := False
      hw.bpuUpdatePort.payload.target := 0
    }

    // 8. ROB Flush逻辑 (分支预测错误时)
    when(pipeline.s1_resolve.isFiring && !predictionCorrect) {
      hw.robFlushPort.valid := True
      hw.robFlushPort.payload.reason := FlushReason.ROLLBACK_TO_ROB_IDX
      // CRITICAL FIX: Flush from the instruction AFTER the branch, not the branch itself
      // The branch instruction should commit, but all following speculative instructions should be flushed
      hw.robFlushPort.payload.targetRobPtr := uopAtS1.robPtr + 1
      report(L"[BranchEU-S1] MISPREDICTION DETECTED: Flushing ROB from robPtr=${uopAtS1.robPtr + 1}, targetPC=0x${finalTarget}")
    } otherwise {
      hw.robFlushPort.valid := False
      hw.robFlushPort.payload.reason := FlushReason.NONE
      hw.robFlushPort.payload.targetRobPtr := 0
    }

    pipeline.build()

    ParallaxLogger.log(s"[BranchEu ${euName}] Pipeline with branch logic built.")
  }
}
