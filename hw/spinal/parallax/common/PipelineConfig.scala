package parallax.common

import spinal.core._
import spinal.lib._

case class PipelineConfig(
  val xlen: Int = 32,
  // Number of instructions to fetch in parallel (e.g., 2 for a 2-wide superscalar)
  val fetchWidth: Int = 2 ,
  // Architectural GPR/FPR index (e.g. 2^5 = 32 regs)
  val archGprIdxWidth: BitCount = 5 bits,
  // LoongArch has 14-bit CSRs RISC-V 12-bit. Max for flexibility.
  val csrArchAddrWidth: BitCount = 14 bits,
  // Physical register configuration
  val numArchGprs: Int = 32,
  val numPhysGprs: Int = 64,
   // If you have separate physical FPRs,
  val numPhysFprs: Int = 64,
  // We might also need numPhysCsrs if CSRs are renamed or other reg types
  val robDepth: Int = 64,
  // For microarchitecture-specific fields
  val uopUniqueIdWidth: BitCount = 16 bits
) {


  // GPR/FPR data width (also individual instruction width)
  def dataWidth: BitCount = xlen bits
  // Program Counter width & general address width for memory access
  def pcWidth: BitCount = xlen bits
  // Derived widths
  def bytesPerInstruction: Int = dataWidth.value / 8
  def fetchGroupBytes: Int = fetchWidth * bytesPerInstruction

  def physGprIdxWidth: BitCount = log2Up(numPhysGprs) bits
  def physFprIdxWidth: BitCount = log2Up(numPhysFprs) bits // If different from GPRs
  def robIdxWidth: BitCount = log2Up(robDepth) bits
}
