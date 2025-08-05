package parallax.common

import spinal.core._
import spinal.lib._

case class PipelineConfig(
    val xlen: Int = 32,
    val fetchWidth: Int = 2, // Number of instructions fetched per cycle from I-cache
    val renameWidth: Int = 1, // Number of instructions processed per cycle in decode/rename stages (currently always 1)
    val dispatchWidth: Int = 1, // Number of uops that can be dispatched to IQs in parallel (currently always 1)
    val commitWidth: Int = 1, // Number of uops that can be committed in parallel (currently always 1)

    val csrArchAddrWidth: BitCount = 14 bits,
    val archGprCount: Int = 32,
    val physGprCount: Int = 64, // Example: 32 arch + 32 renaming = 64

    val archFprCount: Int = 0, // Number of architectural FPRs (if supporting FP)
    val physFprCount: Int = 0, // Number of physical FPRs (if supporting FP and renaming them)

    val robDepth: Int = 8,
    val uopUniqueIdWidth: BitCount = 16 bits, // For debugging/tracing
    val transactionIdWidth: Int = 8, 
    // Number of parallel sources feeding the bypass network
    // This should typically match the number of writeback ports from EUs.
    val bypassNetworkSources: Int = 2,
    val aluEuCount: Int = 2, // Number of ALU EUs
    val lsuEuCount: Int = 1,
    val bruEuCount: Int = 0, // Number of BRU EUs
    val exceptionCodeWidth: BitCount = 8 bits, // Width of the exception code signal

    // Configuration for specific Issue Queues (example for ALU IQ)
    val aluIntIqDepth: Int = 8, // Depth of the ALU Integer IQ
    val resetVector: BigInt = 0x00000000, // Address of reset vector
    val bpuTransactionIdWidth: BitCount = 3 bits,
    // Add other IQ depths as needed, e.g.:
    // val lsuIqDepth: Int = 16
    // val mulIqDepth: Int = 4
    val defaultIsIO: Boolean = true, // Force LS requests to bypass the DCache
    val busIdWidth: BitCount = 8 bits,
    val mulEuCount: Int = 0,
    val fetchGenIdWidth: BitCount = 4 bits,
) {
  
  def divEuCount: Int = 0
  def csrEuCount: Int = 0
  def totalEuCount: Int = aluEuCount + lsuEuCount * 2 + mulEuCount + divEuCount + csrEuCount + bruEuCount // * 2 for load/store
  
  // GPR/FPR data width (also individual instruction width)
  def dataWidth: BitCount = xlen bits
  // Program Counter width & general address width for memory access
  def pcWidth: BitCount = xlen bits

  // Derived widths
  def bytesPerInstruction: Int = dataWidth.value / 8
  def fetchGroupBytes: Int = fetchWidth * bytesPerInstruction

  def archRegIdxWidth: BitCount = log2Up(archGprCount) bits
  def physGprIdxWidth: BitCount = log2Up(physGprCount) bits

  def archFprIdxWidth: BitCount = if (archFprCount > 0) log2Up(archFprCount) bits else 1 bit // Avoid log2Up(0)
  def physFprIdxWidth: BitCount = if (physFprCount > 0) log2Up(physFprCount) bits else 1 bit

  def robPtrWidth: BitCount = log2Up(robDepth) + 1 bits // extra bit for generation
  
  require(busIdWidth.value >= robPtrWidth.value, "Memory operation ID width must be at least as wide as the ROB pointer width")
  // Helper to check if Floating Point is notionally supported by config
  def hasFpu: Boolean = archFprCount > 0 && physFprCount > 0
}
