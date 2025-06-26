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

class BranchEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    override val readPhysRsDataFromPush: Boolean
) extends EuBasePlugin(euName, pipelineConfig, readPhysRsDataFromPush) {

  // --- ParallaxEuBase Implementation ---
  override type T_EuSpecificContext = EmptyBundle
  override def euSpecificContextType: HardType[EmptyBundle] = EmptyBundle()
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY

  // BRU现在处理更明确的UopCode
  addMicroOp(BaseUopCode.BRANCH)
  addMicroOp(BaseUopCode.JUMP_REG) // 用于 JALR
  addMicroOp(BaseUopCode.JUMP_IMM) // 用于 JAL

  // --- 硬件服务与端口 ---
  val hw = create early new Area {
    val robServiceInst = getService[ROBService[RenamedUop]]
    val bpuServiceInst = getService[BpuService]
    val prfServiceInst = getService[PhysicalRegFileService]

    val robWritebackPort = robServiceInst.newWritebackPort(s"BRU_${euName}")
    val robFlushPort     = robServiceInst.getFlushPort()
    val bpuUpdatePort    = bpuServiceInst.newBpuUpdatePort()
    val prfWritePort     = prfServiceInst.newWritePort()

    robServiceInst.retain()
    bpuServiceInst.retain()
    prfServiceInst.retain()
  }

  // --- 微流水线定义 ---
  var s0_dispatch: Stage = null
  var s1_resolve: Stage = null

  override def buildMicroPipeline(pipeline: Pipeline): Unit = {
    s0_dispatch = euPipeline.newStage().setName("s0_Dispatch")
    s1_resolve  = euPipeline.newStage().setName("s1_Resolve")

    s1_resolve(commonSignals.EXEC_RESULT_DATA)
    s1_resolve(commonSignals.EXEC_WRITES_TO_PREG)
    s1_resolve(commonSignals.EXEC_HAS_EXCEPTION)
    s1_resolve(commonSignals.EXEC_EXCEPTION_CODE)
    s1_resolve(commonSignals.EXEC_DEST_IS_FPR)

    pipeline.connect(s0_dispatch, s1_resolve)(Connection.M2S())
  }

  override def getWritebackStage(pipeline: Pipeline): Stage = s1_resolve

  override def connectPipelineLogic(pipeline: Pipeline): Unit = {
    ParallaxLogger.log(s"[BranchEu ${euName}] Logic start definition.")

    // --- Stage S0: Dispatch ---
    s0_dispatch.valid := this.obtainedEuInput.valid
    this.obtainedEuInput.ready := s0_dispatch.isReady
    s0_dispatch(commonSignals.EU_INPUT_PAYLOAD) := this.obtainedEuInput.payload
    
    // --- Stage S1: Resolve Branch ---
    val uopFullPayload_s1 = s1_resolve(commonSignals.EU_INPUT_PAYLOAD)
    // 使用 asPimped 方法将基类转换为我们临时的派生类以访问 branchPrediction
    val renamedUop_s1     = uopFullPayload_s1.renamedUop
    val decodedUop_s1     = renamedUop_s1.decoded

    // 1. 获取操作数数据
    val src1Data = Bits(pipelineConfig.dataWidth)
    val src2Data = Bits(pipelineConfig.dataWidth)

    if (readPhysRsDataFromPush) {
      src1Data := uopFullPayload_s1.src1Data
      src2Data := uopFullPayload_s1.src2Data
    } else {
      val data_rs1 = connectGprRead(s1_resolve, 0, renamedUop_s1.rename.physSrc1.idx, decodedUop_s1.useArchSrc1)
      val data_rs2 = connectGprRead(s1_resolve, 1, renamedUop_s1.rename.physSrc2.idx, decodedUop_s1.useArchSrc2)
      src1Data := data_rs1
      src2Data := data_rs2
    }

    // 2. 执行分支条件判断，使用 DecodedUop.branchCtrl.condition
    val branchTaken = Bool()
    switch(decodedUop_s1.branchCtrl.condition) {
      is(BranchCondition.EQ)  { branchTaken := (src1Data === src2Data) }
      is(BranchCondition.NE)  { branchTaken := (src1Data =/= src2Data) }
      is(BranchCondition.LT)  { branchTaken := (src1Data.asSInt < src2Data.asSInt) }
      is(BranchCondition.GE)  { branchTaken := (src1Data.asSInt >= src2Data.asSInt) }
      is(BranchCondition.LTU) { branchTaken := (src1Data.asUInt < src2Data.asUInt) }
      is(BranchCondition.GEU) { branchTaken := (src1Data.asUInt >= src2Data.asUInt) }
      // 对于 JUMP_IMM 和 JUMP_REG，它们的 condition 是 NUL，总是taken
      default { branchTaken := True }
    }

    // 3. 计算实际目标地址
    val targetAddress = UInt(pipelineConfig.pcWidth)
    val imm_s1 = decodedUop_s1.imm.asSInt
    when(decodedUop_s1.uopCode === BaseUopCode.JUMP_REG) { // JALR
      targetAddress := (src1Data.asSInt + imm_s1).asUInt & ~U(1, pipelineConfig.pcWidth )
    } otherwise { // BRANCH, JUMP_IMM (JAL)
      targetAddress := (renamedUop_s1.decoded.pc.asSInt + imm_s1).asUInt
    }

    // 4. 检查分支预测是否错误
    val prediction = renamedUop_s1.rename.branchPrediction
    val isMispredicted = Bool()

    when(decodedUop_s1.uopCode === BaseUopCode.BRANCH) {
      isMispredicted := prediction.isTaken =/= branchTaken
    } otherwise { // JUMP_REG, JUMP_IMM
      // 对于跳转指令，不仅要看是否跳转，还要看目标地址是否正确
      isMispredicted := !prediction.isTaken || (prediction.target =/= targetAddress)
    }

    // --- 驱动所有输出端口 ---

    // 默认值
    hw.robFlushPort.valid := False
    hw.robFlushPort.payload.assignDontCare()
    hw.bpuUpdatePort.valid := False
    hw.bpuUpdatePort.payload.assignDontCare()
    hw.prfWritePort.valid := False
    hw.prfWritePort.data.assignDontCare()
    hw.prfWritePort.address.assignDontCare()

    when(s1_resolve.isFiring) {
      // 5. 如果预测错误，请求流水线冲刷
      when(isMispredicted) {
        hw.robFlushPort.valid                := True
        hw.robFlushPort.payload.reason       := FlushReason.ROLLBACK_TO_ROB_IDX
        hw.robFlushPort.payload.targetRobPtr       := renamedUop_s1.robPtr
        ParallaxSim.log(L"[BRU] MISPREDICT! robPtr=${renamedUop_s1.robPtr}, pc=${renamedUop_s1.decoded.pc}, actualTarget=${targetAddress}, actualTaken=${branchTaken}")
      }

      // 6. 更新分支预测器 (仅对条件分支)
      when(decodedUop_s1.uopCode === BaseUopCode.BRANCH) {
        hw.bpuUpdatePort.valid           := True
        hw.bpuUpdatePort.payload.pc      := renamedUop_s1.decoded.pc
        hw.bpuUpdatePort.payload.isTaken := branchTaken
        hw.bpuUpdatePort.payload.target  := targetAddress
      }

      // 7. 写回链接地址 (JAL/JALR)，使用 DecodedUop.branchCtrl.isLink
      val writesLinkAddress = decodedUop_s1.branchCtrl.isLink && renamedUop_s1.rename.writesToPhysReg
      when(writesLinkAddress) {
        // 只有在预测正确时才写回，防止错误的写操作污染寄存器堆
        hw.prfWritePort.valid   := !isMispredicted
        hw.prfWritePort.address := renamedUop_s1.rename.physDest.idx
        hw.prfWritePort.data    := (renamedUop_s1.decoded.pc + 4).asBits.resized
      }

      // 8. 向ROB报告指令完成
      hw.robWritebackPort.fire := True
      hw.robWritebackPort.robPtr := renamedUop_s1.robPtr
      hw.robWritebackPort.exceptionOccurred := False // 简单BRU不产生异常
      hw.robWritebackPort.exceptionCodeIn.assignDontCare()

      // 9. 填充 EuBasePlugin 的标准输出信号
      s1_resolve(commonSignals.EXEC_WRITES_TO_PREG) := writesLinkAddress && !isMispredicted
      s1_resolve(commonSignals.EXEC_RESULT_DATA)   := (renamedUop_s1.decoded.pc + 4).asBits.resized
      s1_resolve(commonSignals.EXEC_HAS_EXCEPTION) := False
      s1_resolve(commonSignals.EXEC_EXCEPTION_CODE) := U(0)
      s1_resolve(commonSignals.EXEC_DEST_IS_FPR)   := False
      
      ParallaxSim.debug(Seq(
          L"BranchEu (${euName}) S1 Firing: UopCode=${decodedUop_s1.uopCode}, ",
          L"RobPtr=${renamedUop_s1.robPtr}, PC=${renamedUop_s1.decoded.pc}, ",
          L"Taken=${branchTaken}, Target=${targetAddress}, ",
          L"Mispredicted=${isMispredicted}"
      ))
    }
  }

}
