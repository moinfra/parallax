// filename: parallax/execute/AluIntEu.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities.ParallaxLogger
import parallax.components.execute.IntAlu
import parallax.utilities.ParallaxSim
import parallax.components.issue.IQEntryAluInt

case class AluIntEuSignals(val pipelineConfig: PipelineConfig) {
  val S1_RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
  val S1_RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))
}

class AluIntEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig
) extends EuBasePlugin(euName, pipelineConfig) {

  // 只需要2个GPR读端口，不需要FPR端口
  override def numGprReadPortsPerEu: Int = 2
  override def numFprReadPortsPerEu: Int = 0

  // --- ParallaxEuBase Implementation ---
  override type T_IQEntry = IQEntryAluInt
  override def iqEntryType: HardType[IQEntryAluInt] = HardType(IQEntryAluInt(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY
  override def getEuType: ExeUnitType.E = ExeUnitType.ALU_INT

  addMicroOp(BaseUopCode.ALU)
  addMicroOp(BaseUopCode.SHIFT)
  addMicroOp(BaseUopCode.NOP)
  addMicroOp(BaseUopCode.IDLE)

  // 将 IntAlu 实例化移到插件的主体中
  val intAlu = new IntAlu(pipelineConfig)
  
  lazy val signals = AluIntEuSignals(pipelineConfig)

  // 实现新的抽象方法
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[AluInt ${euName}] Logic start definition.")

    // 1. 子类自己定义流水线
    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("s0_Dispatch")
      val s1_readRegs = newStage().setName("s1_ReadRegs")
      val s2_execute  = newStage().setName("s2_Execute")
    }.setCompositeName(this, "internal_pipeline")

    // 3. 定义流水线内部的信号和逻辑 - 在流水线外部定义 Stageable
    val S1_RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val S1_RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType()) // 在流水线外部定义，所有阶段共享

    // 2. 连接输入端口到流水线入口，并初始化 EU_INPUT_PAYLOAD
    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload // 初始化 EU_INPUT_PAYLOAD

    // 连接阶段
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_readRegs)(Connection.M2S())
    pipeline.connect(pipeline.s1_readRegs, pipeline.s2_execute)(Connection.M2S())
    
    // --- Stage S1: Read Registers ---
    val iqEntry_s1 = pipeline.s1_readRegs(EU_INPUT_PAYLOAD) // 使用共享的 Stageable
    val data_rs1 = connectGprRead(pipeline.s1_readRegs, 0, iqEntry_s1.src1Tag, iqEntry_s1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_readRegs, 1, iqEntry_s1.src2Tag, iqEntry_s1.useSrc2)
    pipeline.s1_readRegs(S1_RS1_DATA) := data_rs1
    pipeline.s1_readRegs(S1_RS2_DATA) := data_rs2

    // --- Stage S2: Execute ---
    val uopAtS2 = pipeline.s2_execute(EU_INPUT_PAYLOAD) // 使用共享的 Stageable

    // ALU source operands from PRF read in S1
    val aluSrc1Data = pipeline.s2_execute(S1_RS1_DATA) // Data was read from PRF in S1
    val aluSrc2Data = pipeline.s2_execute(S1_RS2_DATA)

    // Handle immediate vs register operand for src2
    val effectiveSrc2Data = Mux(
      uopAtS2.immUsage === ImmUsageType.SRC_ALU,
      uopAtS2.imm, // Use immediate when specified
      aluSrc2Data  // Use register data otherwise
    )

    // Create a modified IQEntry with the actual data from PRF
    val iqEntryWithData = IQEntryAluInt(pipelineConfig)
    // 逐个字段赋值，避免重复赋值
    iqEntryWithData.robPtr := uopAtS2.robPtr
    iqEntryWithData.physDest := uopAtS2.physDest
    iqEntryWithData.physDestIsFpr := uopAtS2.physDestIsFpr
    iqEntryWithData.writesToPhysReg := uopAtS2.writesToPhysReg
    iqEntryWithData.useSrc1 := uopAtS2.useSrc1
    iqEntryWithData.src1Tag := uopAtS2.src1Tag
    iqEntryWithData.src1Ready := uopAtS2.src1Ready
    iqEntryWithData.src1IsFpr := uopAtS2.src1IsFpr
    iqEntryWithData.useSrc2 := uopAtS2.useSrc2
    iqEntryWithData.src2Tag := uopAtS2.src2Tag
    iqEntryWithData.src2Ready := uopAtS2.src2Ready
    iqEntryWithData.src2IsFpr := uopAtS2.src2IsFpr
    iqEntryWithData.aluCtrl := uopAtS2.aluCtrl
    iqEntryWithData.shiftCtrl := uopAtS2.shiftCtrl
    iqEntryWithData.imm := uopAtS2.imm
    iqEntryWithData.immUsage := uopAtS2.immUsage
    // 使用从PRF读取的实际数据
    iqEntryWithData.src1Data := aluSrc1Data
    iqEntryWithData.src2Data := effectiveSrc2Data

    // Connect inputs to IntAlu
    intAlu.io.iqEntryIn.valid := pipeline.s2_execute.isFiring // IntAlu processes when this stage is firing
    intAlu.io.iqEntryIn.payload := iqEntryWithData

    // 4. 在流水线最后阶段，驱动 euResult "契约"
    val aluResultPayload = intAlu.io.resultOut.payload
    
    when(pipeline.s2_execute.isFiring) {
      euResult.valid         := True
      euResult.uop           := uopAtS2
      euResult.data          := aluResultPayload.data
      euResult.writesToPreg  := aluResultPayload.writesToPhysReg
      euResult.hasException  := aluResultPayload.hasException
      euResult.exceptionCode := aluResultPayload.exceptionCode.asBits.asUInt.resized
      euResult.destIsFpr     := False // Integer ALU never writes to FPR
    }

    // Logging
    when(pipeline.s2_execute.isFiring) {
      ParallaxSim.debug(
        Seq(
          L"AluIntEu (${euName}) S2 Firing: ",
          L"RobPtr=${uopAtS2.robPtr}, ResultData=${aluResultPayload.data}, ",
          L"WritesPreg=${aluResultPayload.writesToPhysReg}, ",
          L"HasExc=${aluResultPayload.hasException}, ExcCode=${aluResultPayload.exceptionCode.asBits}, ",
          L"ImmUsage=${uopAtS2.immUsage.asBits}, UseSrc2=${uopAtS2.useSrc2}", 
          L" op: ${uopAtS2.aluCtrl.format}, lhs=${aluSrc1Data}, rhs=${effectiveSrc2Data}"
        )
      )
    }

    // 5. 构建流水线
    pipeline.build()
    ParallaxLogger.log(s"[AluInt ${euName}] Logic defined and pipeline built.")
  }
}
