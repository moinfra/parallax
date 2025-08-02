// filename: parallax/execute/AluIntEu.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities.ParallaxLogger
import parallax.components.execute.{IntAlu, IntAluOutputPayload}
import parallax.utilities.ParallaxSim
import parallax.components.issue.IQEntryAluInt

class AluIntEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig
) extends EuBasePlugin(euName, pipelineConfig) {

  override def numGprReadPortsPerEu: Int = 2
  override def numFprReadPortsPerEu: Int = 0

  override type T_IQEntry = IQEntryAluInt
  override def iqEntryType: HardType[IQEntryAluInt] = HardType(IQEntryAluInt(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY
  override def getEuType: ExeUnitType.E = ExeUnitType.ALU_INT

  addMicroOp(BaseUopCode.ALU)
  addMicroOp(BaseUopCode.SHIFT)
  addMicroOp(BaseUopCode.NOP)
  addMicroOp(BaseUopCode.IDLE)

  val intAlu = new IntAlu(pipelineConfig)
  
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[AluInt ${euName}] Logic start definition.")

    val pipeline = new Pipeline {
      val s0_dispatch  = newStage().setName("s0_Dispatch")
      val s1_readRegs  = newStage().setName("s1_ReadRegs")
      val s2_execute   = newStage().setName("s2_Execute")
      val s3_writeback = newStage().setName("s3_Writeback")

      s0_dispatch.flushIt(robFlushPort.valid)
      s1_readRegs.flushIt(robFlushPort.valid)
      s2_execute.flushIt(robFlushPort.valid)
      s3_writeback.flushIt(robFlushPort.valid)
    }.setCompositeName(this, "internal_pipeline")

    val S1_RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val S1_RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType())
    val ALU_RESULT = Stageable(IntAluOutputPayload(pipelineConfig))

    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload
    
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_readRegs)(Connection.M2S())
    pipeline.connect(pipeline.s1_readRegs, pipeline.s2_execute)(Connection.M2S())
    pipeline.connect(pipeline.s2_execute, pipeline.s3_writeback)(Connection.M2S())
    
    val iqEntry_s1 = pipeline.s1_readRegs(EU_INPUT_PAYLOAD)
    val data_rs1 = connectGprRead(pipeline.s1_readRegs, 0, iqEntry_s1.src1Tag, iqEntry_s1.useSrc1)
    val data_rs2 = connectGprRead(pipeline.s1_readRegs, 1, iqEntry_s1.src2Tag, iqEntry_s1.useSrc2)
    pipeline.s1_readRegs(S1_RS1_DATA) := data_rs1
    pipeline.s1_readRegs(S1_RS2_DATA) := data_rs2

    // --- Stage S2: Execute ---
    val uopAtS2 = pipeline.s2_execute(EU_INPUT_PAYLOAD)
    val prfSrc1Data = pipeline.s2_execute(S1_RS1_DATA) // 来自PRF的数据

    val aluSrc1Data = Mux(
      uopAtS2.src1IsPc,
      uopAtS2.pc.asBits, // 使用指令的PC值
      prfSrc1Data        // 使用从PRF读出的数据
    )
    val aluSrc2Data = pipeline.s2_execute(S1_RS2_DATA)

    val effectiveSrc2Data = Mux(
      uopAtS2.immUsage === ImmUsageType.SRC_ALU,
      uopAtS2.imm,
      aluSrc2Data
    )


    // +++ CORRECTED: Use strict, field-by-field assignment to avoid overlapping +++
    val iqEntryForAlu = intAlu.io.iqEntryIn.payload
    iqEntryForAlu.robPtr := uopAtS2.robPtr
    iqEntryForAlu.pc := uopAtS2.pc
    iqEntryForAlu.physDest := uopAtS2.physDest
    iqEntryForAlu.physDestIsFpr := uopAtS2.physDestIsFpr
    iqEntryForAlu.writesToPhysReg := uopAtS2.writesToPhysReg
    iqEntryForAlu.useSrc1 := uopAtS2.useSrc1
    iqEntryForAlu.src1Tag := uopAtS2.src1Tag
    iqEntryForAlu.src1Ready := uopAtS2.src1Ready
    iqEntryForAlu.src1IsFpr := uopAtS2.src1IsFpr
    iqEntryForAlu.useSrc2 := uopAtS2.useSrc2
    iqEntryForAlu.src2Tag := uopAtS2.src2Tag
    iqEntryForAlu.src2Ready := uopAtS2.src2Ready
    iqEntryForAlu.src2IsFpr := uopAtS2.src2IsFpr
    iqEntryForAlu.aluCtrl := uopAtS2.aluCtrl
    iqEntryForAlu.shiftCtrl := uopAtS2.shiftCtrl
    iqEntryForAlu.imm := uopAtS2.imm
    iqEntryForAlu.immUsage := uopAtS2.immUsage
    // Assign the actual data from PRF (or immediate)
    iqEntryForAlu.src1Data := aluSrc1Data
    iqEntryForAlu.src2Data := effectiveSrc2Data

    // Store the ALU's combinational result into the S2->S3 inter-stage register
    pipeline.s2_execute(ALU_RESULT) := intAlu.io.resultOut.payload


    // Connect inputs to IntAlu
    intAlu.io.iqEntryIn.valid := pipeline.s2_execute.isFiring
    when(pipeline.s2_execute.isFiring) {
      ParallaxSim.debug(
        Seq(
          L"AluIntEu (${euName}) S2 Firing: ",
          L"For uop@${uopAtS2.pc}, robPtr=${uopAtS2.robPtr}, ResultData=${intAlu.io.resultOut.payload.data}, ",
          L"WritesPreg=${intAlu.io.resultOut.payload.writesToPhysReg}, ",
          L"ImmUsage=${uopAtS2.immUsage.asBits}, UseSrc2=${uopAtS2.useSrc2}", 
          L" op: ${uopAtS2.aluCtrl.format}, lhs=${aluSrc1Data}, rhs=${effectiveSrc2Data}"
        )
      )
    }

    // --- Stage S3: Writeback ---
    val uopAtS3 = pipeline.s3_writeback(EU_INPUT_PAYLOAD)
    val aluResultPayload = pipeline.s3_writeback(ALU_RESULT)

    when(pipeline.s3_writeback.isFiring) {
      euResult.valid         := True
      euResult.uop           := uopAtS3
      euResult.data          := aluResultPayload.data
      euResult.writesToPreg  := aluResultPayload.writesToPhysReg
      euResult.hasException  := aluResultPayload.hasException
      euResult.exceptionCode := aluResultPayload.exceptionCode.asBits.asUInt.resized
      euResult.destIsFpr     := False
    }

    pipeline.build()
    ParallaxLogger.log(s"[AluInt ${euName}] Logic defined and 4-stage pipeline built.")
  }
}
