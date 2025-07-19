package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities._
import parallax.components.execute._
import parallax.components.issue.IQEntryMul
import parallax.components.memory.MultiplierBlackbox

class MulEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    simDebug: Boolean
) extends EuBasePlugin(euName, pipelineConfig) {

  override def numGprReadPortsPerEu: Int = 2
  override def numFprReadPortsPerEu: Int = 0

  override type T_IQEntry = IQEntryMul
  override def iqEntryType: HardType[IQEntryMul] = HardType(IQEntryMul(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY
    override def getEuType: ExeUnitType.E = ExeUnitType.MUL_INT

  addMicroOp(BaseUopCode.MUL)

  override def buildEuLogic(): Unit = {
    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("mul_s0_Dispatch")
      val s1_readRegs = newStage().setName("mul_s1_ReadRegs")
      val s2_execute = newStage().setName("mul_s2_Execute")
      val s3_execute = newStage().setName("mul_s3_Execute")
      val s4_execute = newStage().setName("mul_s4_Execute")
      val s5_execute = newStage().setName("mul_s5_Execute")
      val s6_execute = newStage().setName("mul_s6_Execute")
      val s7_writeback = newStage().setName("mul_s7_Writeback")
    }.setCompositeName(this, "internal_pipeline")

    val S1_RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val S1_RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType())

    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload

    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_readRegs)(Connection.M2S())
    pipeline.connect(pipeline.s1_readRegs, pipeline.s2_execute)(Connection.M2S())
    pipeline.connect(pipeline.s2_execute, pipeline.s3_execute)(Connection.M2S())
    pipeline.connect(pipeline.s3_execute, pipeline.s4_execute)(Connection.M2S())
    pipeline.connect(pipeline.s4_execute, pipeline.s5_execute)(Connection.M2S())
    pipeline.connect(pipeline.s5_execute, pipeline.s6_execute)(Connection.M2S())
    pipeline.connect(pipeline.s6_execute, pipeline.s7_writeback)(Connection.M2S())

    val iqEntry_s1 = pipeline.s1_readRegs(EU_INPUT_PAYLOAD)
    val data_rs1 = connectGprRead(pipeline.s1_readRegs, 0, iqEntry_s1.src1Tag, iqEntry_s1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_readRegs, 1, iqEntry_s1.src2Tag, iqEntry_s1.useSrc2)
    pipeline.s1_readRegs(S1_RS1_DATA) := data_rs1
    pipeline.s1_readRegs(S1_RS2_DATA) := data_rs2

    val uopAtS1 = pipeline.s1_readRegs(EU_INPUT_PAYLOAD)
    val src1Data = pipeline.s1_readRegs(S1_RS1_DATA)
    val src2Data = pipeline.s1_readRegs(S1_RS2_DATA)

    val mulResult = SInt(64 bits)

    if (simDebug) {
      val multiplier = Multiplier(aWidth = 32, bWidth = 32, pWidth = 64, pipelineStages = 6)
      multiplier.io.A := src1Data.asSInt
      multiplier.io.B := src2Data.asSInt
      mulResult := multiplier.io.P
    } else {
      val multiplier = MultiplierBlackbox(aWidth = 32, bWidth = 32, pWidth = 64, pipelineStages = 6)
      multiplier.io.CLK := ClockDomain.current.readClockWire
      multiplier.io.RST := ClockDomain.current.readResetWire
      multiplier.io.A := src1Data.asSInt
      multiplier.io.B := src2Data.asSInt
      mulResult := multiplier.io.P
    }

    val S7_RESULT = Stageable(SInt(64 bits))
    pipeline.s7_writeback(S7_RESULT) := mulResult

    val uopAtS7 = pipeline.s7_writeback(EU_INPUT_PAYLOAD)
    val finalResult = pipeline.s7_writeback(S7_RESULT)

    when(pipeline.s2_execute.isFiring) {
      ParallaxSim.log(Seq(
        L"MulEuPlugin (${euName}) S2 Firing: ",
        L"RobPtr=${uopAtS1.robPtr}, ",
        L"src1=${src1Data}, src2=${src2Data}",
        L"mulDivCtrl=${uopAtS1.mulDivCtrl.format()}"
      ))
    }

    when(pipeline.s7_writeback.isFiring) {
      euResult.valid := True
      euResult.uop.robPtr := uopAtS7.robPtr
      euResult.uop.physDest := uopAtS7.physDest
      euResult.data := finalResult.asBits.resized
      euResult.writesToPreg := uopAtS7.writesToPhysReg
      euResult.hasException := False
      euResult.exceptionCode := 0
      euResult.destIsFpr := False

      ParallaxSim.log(Seq(
        L"MulEuPlugin (${euName}) S7 Firing: ",
        L"RobPtr=${uopAtS7.robPtr}, ",
        L"Result=${finalResult}",
        L"mulDivCtrl=${uopAtS7.mulDivCtrl.format()}"
      ))
    }

    pipeline.build()
  }
}
