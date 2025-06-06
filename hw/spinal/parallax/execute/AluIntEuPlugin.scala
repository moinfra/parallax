// filename: parallax/execute/AluIntEu.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities.ParallaxLogger
import parallax.components.execute.DemoAlu
import parallax.utilities.ParallaxSim

case class AluIntEuSignals(val pipelineConfig: PipelineConfig) {
  val S1_RS1_DATA = Stageable(Bits(pipelineConfig.dataWidth))
  val S1_RS2_DATA = Stageable(Bits(pipelineConfig.dataWidth))
}

class AluIntEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    override val readPhysRsDataFromPush: Boolean
) extends EuBasePlugin(euName, pipelineConfig, readPhysRsDataFromPush) {

  // --- ParallaxEuBase Implementation ---
  override type T_EuSpecificContext = EmptyBundle
  override def euSpecificContextType: HardType[EmptyBundle] = EmptyBundle()
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY

  addMicroOp(BaseUopCode.ALU)
  // Consider adding NOP explicitly if it needs special handling beyond DemoAlu's default
  // addMicroOp(BaseUopCode.NOP) // If NOPs are dispatched to ALUs

  // --- Stageables for PRF Read Results (if needed) ---
  // These are only used if !readPhysRsDataFromPush

  // --- Micro-pipeline Stages ---

  // Optional s3_writeBuffer if needed for timing, for now s2 is the wb stage.
  var s0_dispatch: Stage = null
  var s1_readRegs: Stage = null
  var s2_execute: Stage = null

  lazy val signals = AluIntEuSignals(pipelineConfig)
  override def buildMicroPipeline(pipeline: Pipeline): Unit = {
    s0_dispatch = euPipeline.newStage().setName("s0_Dispatch")
    s1_readRegs = if (!readPhysRsDataFromPush) euPipeline.newStage().setName("s1_ReadRegs") else null
    s2_execute = euPipeline.newStage().setName("s2_Execute")

    var lastStage = s0_dispatch

    if (!readPhysRsDataFromPush) {
      s1_readRegs(signals.S1_RS1_DATA) // Declare for this stage
      s1_readRegs(signals.S1_RS2_DATA) // Declare for this stage
      pipeline.connect(lastStage, s1_readRegs)(Connection.M2S())
      lastStage = s1_readRegs
    }

    // EXEC_ signals are produced in s2_execute
    s2_execute(commonSignals.EXEC_RESULT_DATA)
    s2_execute(commonSignals.EXEC_WRITES_TO_PREG)
    s2_execute(commonSignals.EXEC_HAS_EXCEPTION)
    s2_execute(commonSignals.EXEC_EXCEPTION_CODE)
    s2_execute(commonSignals.EXEC_DEST_IS_FPR) // Must be declared by writeback stage

    pipeline.connect(lastStage, s2_execute)(Connection.M2S())
  }

  override def getWritebackStage(pipeline: Pipeline): Stage = {
    s2_execute // s2_execute produces all final signals for ParallaxEuBase
  }

  override def connectPipelineLogic(pipeline: Pipeline): Unit = {
    ParallaxLogger.log(s"[AluInt ${euName}] Logic start definition.")

    // --- Stage S0: Dispatch ---
    // EU_INPUT_PAYLOAD is available in all stages if declared in the first stage.
    // s0_dispatch is the first stage, so EU_INPUT_PAYLOAD is implicitly available.
    s0_dispatch.valid := this.obtainedEuInput.valid
    this.obtainedEuInput.ready := s0_dispatch.isReady

    s0_dispatch(commonSignals.EU_INPUT_PAYLOAD) := this.obtainedEuInput.payload // Stack overflow

    val uopFullPayload_s0 = this.obtainedEuInput.payload
    val renamedUop_s0 = uopFullPayload_s0.renamedUop
    val decodedUop_s0 = renamedUop_s0.decoded

    ParallaxLogger.log(s"[AluInt ${euName}] Stage S0 logic defined.")
    // --- Stage S1: Read Registers (Conditional) ---
    if (readPhysRsDataFromPush) {
      ParallaxLogger.log("读寄存器逻辑跳过，因为从推送端口读取寄存器数据。")
    } else {
      ParallaxLogger.log("读寄存器逻辑开始定义。")
      // EU_INPUT_PAYLOAD is passed from s0 to s1.
      val uopFullPayload_s1 = s1_readRegs(commonSignals.EU_INPUT_PAYLOAD) // Get it from s1's input
      val renamedUop_s1 = uopFullPayload_s1.renamedUop
      val decodedUop_s1 = renamedUop_s1.decoded

      // Use ParallaxEuBase helper to connect GPR reads
      // Reads are driven by s1_readRegs.isFiring
      val data_rs1: Bits = connectGprRead(
        stage = s1_readRegs,
        prfReadPortIdx = 0,
        physRegIdx = renamedUop_s1.rename.physSrc1.idx,
        useThisSrcSignal = decodedUop_s1.useArchSrc1 // Condition for this read
      )
      val data_rs2: Bits = connectGprRead(
        stage = s1_readRegs,
        prfReadPortIdx = 1,
        physRegIdx = renamedUop_s1.rename.physSrc2.idx,
        useThisSrcSignal =
          decodedUop_s1.useArchSrc2 && (decodedUop_s1.immUsage =/= ImmUsageType.SRC_ALU) // Don't read src2 if imm is used
      )

      s1_readRegs(signals.S1_RS1_DATA) := data_rs1
      s1_readRegs(signals.S1_RS2_DATA) := data_rs2
    }
    ParallaxLogger.log(s"[AluInt ${euName}] Stage S1 logic defined.")

    // --- Stage S2: Execute ---
    val uopFullPayload_s2 = s2_execute(commonSignals.EU_INPUT_PAYLOAD) // Get payload propagated to s2
    val renamedUop_s2 = uopFullPayload_s2.renamedUop
    val decodedUop_s2 = renamedUop_s2.decoded

    val demoAlu = new DemoAlu(pipelineConfig) // Instantiate the ALU component

    // Determine ALU source operands based on readPhysRsDataFromPush
    val aluSrc1Data = Bits(pipelineConfig.dataWidth)
    val aluSrc2Data = Bits(pipelineConfig.dataWidth)

    if (readPhysRsDataFromPush) {
      aluSrc1Data := uopFullPayload_s2.src1Data // Data was pushed with uop
      aluSrc2Data := uopFullPayload_s2.src2Data
    } else {
      aluSrc1Data := s2_execute(signals.S1_RS1_DATA) // Data was read from PRF in S1
      aluSrc2Data := s2_execute(signals.S1_RS2_DATA)
    }

    // Connect inputs to DemoAlu
    demoAlu.io.uopIn.valid := s2_execute.isFiring // DemoAlu processes when this stage is firing
    demoAlu.io.uopIn.payload := renamedUop_s2
    demoAlu.io.src1DataIn := aluSrc1Data
    // src2DataIn for DemoAlu will be handled by its internal Mux for immediate
    // So, we pass the register value (or pushed value) here. DemoAlu will select imm if needed.
    demoAlu.io.src2DataIn := aluSrc2Data

    // Drive the common execution result Stageables based on DemoAlu's output
    val aluResultPayload = demoAlu.io.resultOut.payload

    // Handle NOP explicitly if it's dispatched here and DemoAlu doesn't make it a no-op
    // A NOP should not write to a register and should not have an exception.
    // DemoAlu might already handle this if uop.rename.writesToPhysReg is False for NOP.
    val isNopInstruction = decodedUop_s2.uopCode === BaseUopCode.NOP // Assuming NOP is a BaseUopCode

    s2_execute(commonSignals.EXEC_RESULT_DATA) := aluResultPayload.data
    s2_execute(commonSignals.EXEC_WRITES_TO_PREG) := aluResultPayload.writesToPhysReg && !isNopInstruction
    s2_execute(commonSignals.EXEC_HAS_EXCEPTION) := aluResultPayload.hasException && !isNopInstruction
    s2_execute(
      commonSignals.EXEC_EXCEPTION_CODE
    ) := aluResultPayload.exceptionCode.asBits.asUInt.resized // FIXME: it's wrong
    s2_execute(commonSignals.EXEC_DEST_IS_FPR) := False // Integer ALU never writes to FPR

    // Ensure NOPs don't cause exceptions if DemoAlu might raise one for an unhandled NOP pattern
    when(isNopInstruction) {
      s2_execute(commonSignals.EXEC_RESULT_DATA) := B(0) // NOPs usually produce 0 or don't care
      s2_execute(commonSignals.EXEC_WRITES_TO_PREG) := False
      s2_execute(commonSignals.EXEC_HAS_EXCEPTION) := False
      s2_execute(commonSignals.EXEC_EXCEPTION_CODE) := U(0)
    }

    // Logging
    when(s2_execute.isFiring) {
      ParallaxSim.debug(
        Seq(
          L"AluIntEu (${euName}) S2 Firing: UopCode=${decodedUop_s2.uopCode}, ",
          L"RobPtr=${renamedUop_s2.robPtr}, ResultData=${s2_execute(commonSignals.EXEC_RESULT_DATA)}, ",
          L"WritesPreg=${s2_execute(commonSignals.EXEC_WRITES_TO_PREG)}, ",
          L"HasExc=${s2_execute(commonSignals.EXEC_HAS_EXCEPTION)}, ExcCode=${s2_execute(commonSignals.EXEC_EXCEPTION_CODE)}"
        )
      )
    }

    ParallaxLogger.log(s"[AluInt ${euName}] Stage S2 logic defined.")

  }
}
