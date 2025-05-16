package parallax.common

import spinal.core._
import spinal.lib._

case class PipelineConfig() {
  val xlen: Int = 32
  // GPR/FPR data width
  val dataWidth: BitCount = xlen bits
  // Program Counter width
  val pcWidth: BitCount = xlen bits
  // Architectural GPR/FPR index (e.g. 2^5 = 32 regs)
  val archGprIdxWidth: BitCount = 5 bits
  // LoongArch has 14-bit CSRs RISC-V 12-bit. Max for flexibility.
  val csrArchAddrWidth: BitCount = 14 bits
  // Physical register configuration
  val numArchGprs: Int = 32
  val numPhysGprs: Int = 64
  val numPhysFprs: Int = 64 // If you have separate physical FPRs
  // We might also need numPhysCsrs if CSRs are renamed or other reg types
  val robDepth: Int = 64
  // For microarchitecture-specific fields
  val uopUniqueIdWidth: BitCount = 16 bits
  def physGprIdxWidth: BitCount = log2Up(numPhysGprs) bits
  def physFprIdxWidth: BitCount = log2Up(numPhysFprs) bits // If different from GPRs
  def robIdxWidth: BitCount = log2Up(robDepth) bits
}
