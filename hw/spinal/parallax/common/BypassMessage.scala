// filename: parallax/common/BypassMessage.scala
package parallax.common

import spinal.core._
import spinal.lib._

case class BypassMessage(val config: PipelineConfig) extends Bundle {
  // Physical register index being broadcasted.
  // For simplicity, assuming GPRs for now. If FPRs have a separate physical register file
  // and bypass network, this might need an additional type field or separate bypass messages.
  val physRegIdx = UInt(config.physGprIdxWidth)

  // The data value of the physical register.
  val physRegData = Bits(config.dataWidth)

  // ROB index of the Uop that produced this result.
  // Useful for wake-up logic if tags are ROB-based, or for debugging and linking back to ROB.
  val robPtr = UInt(config.robPtrWidth)

  // If GPR/FPR share a physical register file or bypass network and need to be distinguished.
  // For now, let's assume GPRs, or that the physRegIdx space is unique.
  val isFPR = Bool() // Indicates if the physRegIdx refers to an FPR

  // Exception information from the Uop that produced this result.
  val hasException = Bool()
  val exceptionCode = UInt(config.exceptionCodeWidth) // Ensure exceptionCodeWidth is in PipelineConfig

  def setDefault(): this.type = {
    physRegIdx := U(0)
    physRegData := B(0)
    robPtr := U(0)
    isFPR := False
    hasException := False
    exceptionCode := U(0)
    this
  }
}
