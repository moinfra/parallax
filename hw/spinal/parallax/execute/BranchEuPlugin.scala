// filename: parallax/execute/BranchEuPlugin.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rob.{FlushReason, ROBService}
import parallax.bpu.{BpuService, BpuUpdate}
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.bpu.BpuService
import parallax.components.issue.IQEntryBru

class BranchEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig
) extends EuBasePlugin(euName, pipelineConfig) {

  // 只需要1个GPR读端口，不需要FPR端口
  override def numGprReadPortsPerEu: Int = 1
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

  // 实现新的抽象方法
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[BranchEu ${euName}] Logic start definition.")

    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("s0_Dispatch")
      val s1_resolve  = newStage().setName("s1_Resolve")
    }.setCompositeName(this, "internal_pipeline")

    // 定义共享的 Stageable
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType()) // 在流水线外部定义，所有阶段共享

    // 连接输入端口到流水线入口，并初始化 EU_INPUT_PAYLOAD
    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload // 初始化 EU_INPUT_PAYLOAD
    
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_resolve)(Connection.M2S())

    // --- Stage S1: Resolve Branch ---
    val uopAtS1 = pipeline.s1_resolve(EU_INPUT_PAYLOAD) // 使用共享的 Stageable

    // 1. 获取操作数数据 - 从PRF读取
    val data_rs1 = connectGprRead(pipeline.s1_resolve, 0, uopAtS1.src1Tag, uopAtS1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_resolve, 1, uopAtS1.src2Tag, uopAtS1.useSrc2)
    val src1Data = data_rs1
    val src2Data = data_rs2

    // 2. 执行分支条件判断，使用 branchCtrl.condition
    val branchTaken = Bool()
    switch(uopAtS1.branchCtrl.condition) {
      // GPR 寄存器比较
      is(BranchCondition.EQ)  { branchTaken := (src1Data === src2Data) }
      is(BranchCondition.NE)  { branchTaken := (src1Data =/= src2Data) }
      is(BranchCondition.LT)  { branchTaken := (src1Data.asSInt < src2Data.asSInt) }
      is(BranchCondition.GE)  { branchTaken := (src1Data.asSInt >= src2Data.asSInt) }
      is(BranchCondition.LTU) { branchTaken := (src1Data.asUInt < src2Data.asUInt) }
      is(BranchCondition.GEU) { branchTaken := (src1Data.asUInt >= src2Data.asUInt) }
      
      // GPR 与零比较
      is(BranchCondition.EQZ) { branchTaken := (src1Data === 0) }
      is(BranchCondition.NEZ) { branchTaken := (src1Data =/= 0) }
      is(BranchCondition.LTZ) { branchTaken := (src1Data.asSInt < 0) }
      is(BranchCondition.GEZ) { branchTaken := (src1Data.asSInt >= 0) }
      is(BranchCondition.GTZ) { branchTaken := (src1Data.asSInt > 0) }
      is(BranchCondition.LEZ) { branchTaken := (src1Data.asSInt <= 0) }
      
      // LoongArch 条件标志比较
      is(BranchCondition.LA_CF_TRUE)  { branchTaken := True }  // 总是跳转
      is(BranchCondition.LA_CF_FALSE) { branchTaken := False } // 从不跳转
      
      // 对于 JUMP_IMM 和 JUMP_REG，它们的 condition 是 NUL，总是taken
      default { branchTaken := True }
    }

    // 3. 计算实际目标地址
    val targetAddress = UInt(pipelineConfig.pcWidth)
    val imm_s1 = uopAtS1.imm.asSInt
    when(uopAtS1.branchCtrl.isIndirect) { // JALR
      targetAddress := (src1Data.asSInt + imm_s1).asUInt & ~U(1, pipelineConfig.pcWidth )
    } otherwise { // BRANCH, JUMP_IMM (JAL)
      targetAddress := (uopAtS1.pc.asSInt + imm_s1).asUInt
    }

    // 4. 检查分支预测是否错误
    // Note: For now, we'll assume no prediction info in IQEntryBru
    // This would need to be added to the IQEntryBru structure if needed
    val isMispredicted = False // Placeholder - would need prediction info

    // 默认值
    hw.robFlushPort.valid := False
    hw.robFlushPort.payload.assignDontCare()
    hw.bpuUpdatePort.valid := False
    hw.bpuUpdatePort.payload.assignDontCare()
    
    // 当结果就绪时
    when(pipeline.s1_resolve.isFiring) {
      // 1. 驱动 euResult "契约"
      val writesLinkAddress = uopAtS1.branchCtrl.isLink && uopAtS1.writesToPhysReg
      
      euResult.valid        := True
      euResult.uop          := uopAtS1
      euResult.data         := (uopAtS1.pc + 4).asBits.resized // 链接地址
      euResult.writesToPreg := writesLinkAddress && !isMispredicted
      euResult.hasException := False
      euResult.exceptionCode:= U(0)
      euResult.destIsFpr    := False

      // 2. 处理 BRU 特有的副作用
      when(isMispredicted) {
        hw.robFlushPort.valid                := True
        hw.robFlushPort.payload.reason       := FlushReason.ROLLBACK_TO_ROB_IDX
        hw.robFlushPort.payload.targetRobPtr := uopAtS1.robPtr
        ParallaxSim.log(L"[BRU] MISPREDICT! robPtr=${uopAtS1.robPtr}, pc=${uopAtS1.pc}, actualTarget=${targetAddress}, actualTaken=${branchTaken}")
      }
      when(uopAtS1.branchCtrl.condition =/= BranchCondition.NUL) {
        hw.bpuUpdatePort.valid           := True
        hw.bpuUpdatePort.payload.pc      := uopAtS1.pc
        hw.bpuUpdatePort.payload.isTaken := branchTaken
        hw.bpuUpdatePort.payload.target  := targetAddress
      }
      
      ParallaxSim.debug(Seq(
          L"BranchEu (${euName}) S1 Firing: RobPtr=${uopAtS1.robPtr}, PC=${uopAtS1.pc}, ",
          L"Taken=${branchTaken}, Target=${targetAddress}, ",
          L"Mispredicted=${isMispredicted}"
      ))
    }
    
    pipeline.build()
    ParallaxLogger.log(s"[BranchEu ${euName}] Logic defined and pipeline built.")
  }
}
