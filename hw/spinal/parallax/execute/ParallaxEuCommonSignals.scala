package parallax.execute

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.common.RenamedUop
import spinal.lib.pipeline.Stageable

case class ParallaxEuCommonSignals(val config: PipelineConfig) extends AreaObject {

  // --- Common Input Payload Structure ---
  // This defines the *wrapper* that EUs receive.
  // The 'euContext' within it is specific to the EU that defines its input Stageable.
  case class EuPushPortPayload[T_CTX <: Data](val euSpecificContextType: HardType[T_CTX]) extends Bundle {
    val robIdx = UInt(config.robIdxWidth)
    val renamedUop = RenamedUop(config)
    val src1Data = Bits(config.dataWidth)
    val src2Data = Bits(config.dataWidth)
    // val src3Data = Bits(config.dataWidth) // If universally supported
    val euContext = euSpecificContextType() // Context specific to the EU

    def setDefault(): this.type = {
      robIdx := 0
      renamedUop.setDefault()
      src1Data := B(0)
      src2Data := B(0)
      // src3Data := B(0)
      if (euContext.isInstanceOf[Bundle]) {
        euContext.asInstanceOf[Bundle].elements.foreach { case (_, data) => data.assignDontCare() }
      } else {
        euContext.assignDontCare()
      }
      this
    }
  }
  // Example of how an EU would define its specific input Stageable:
  // val MY_EU_INPUT = Stageable(HardType(ParallaxEuCommonSignals(config).EuPushPortPayload(HardType(MyEuContextBundle()))))

  // --- Common Output/Writeback Signals (produced by EUs) ---
  // These are the signals that almost all EUs will generate for the writeback stage.
  val EXEC_RESULT_DATA = Stageable(Bits(config.dataWidth))
  val EXEC_TARGET_PREG_IDX = Stageable(UInt(config.physGprIdxWidth)) // Assuming GPR width for now
  val EXEC_TARGET_PREG_IS_FPR = Stageable(Bool())
  val EXEC_WRITES_TO_PREG = Stageable(Bool()) // Does this result write to a physical register?
  val EXEC_HAS_EXCEPTION = Stageable(Bool())
  val EXEC_EXCEPTION_CODE = Stageable(UInt(config.exceptionCodeWidth))

  // It's also common for EUs to need the original ROB index or RenamedUop at writeback.
  // This could be passed through or re-fetched. For simplicity, let's assume it's passed.
  // An EU would declare a Stageable for its specific input payload (which includes robIdx and RenamedUop)
  // and ensure it's available in the writeback stage.
  // Example (EU would define this):
  // val UOP_AT_WB = Stageable(HardType(EuPushPortPayload(HardType(MyEuContextBundle()))))
}
