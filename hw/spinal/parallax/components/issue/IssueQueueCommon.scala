package parallax.components.issue


import spinal.core._
import spinal.lib._
import parallax.common._

// -- MODIFICATION START (Introduce IssueQueueConfig and IQEntryLike trait) --
/**
 * Configuration for a generic Issue Queue.
 *
 * @param config Global pipeline configuration.
 * @param depth Depth of the issue queue.
 * @param exeUnitType The type of execution unit this IQ feeds.
 * @param uopDataType The HardType of the micro-operation payload (e.g., for ALU, FPU specific fields).
 * @param usesSrc3 If the execution unit can consume a third source operand.
 * @param name A descriptive name for this IQ instance (e.g., "IntIQ", "FpuIQ").
 */
case class IssueQueueConfig[T_IQEntry <: Data with IQEntryLike](
    pipelineConfig: PipelineConfig,
    depth: Int,
    exeUnitType: ExeUnitType.C, // TODO: Potentially a Seq[ExeUnitType.C] if IQ feeds multiple types
    uopEntryType: HardType[T_IQEntry],
    usesSrc3: Boolean = false,
    name: String = "GenericIQ"
) {
  require(depth > 1, s"$name depth must be greater than 1")
  require(
    isPow2(depth) || depth < 8, // Or some other reasonable check
    s"$name depth should ideally be a power of 2 or small for simpler logic."
  )
  val robPtrWidth: BitCount = pipelineConfig.robPtrWidth
  val physRegIdxWidth: BitCount = pipelineConfig.physGprIdxWidth // Assume GPR/FPR phys reg idx width is same for now
                                                                  // If different, this needs to be more complex or IQs become more specialized
  val dataWidth: BitCount = pipelineConfig.dataWidth
  val bypassNetworkSources: Int = pipelineConfig.bypassNetworkSources

  def getIQEntry(): T_IQEntry = uopEntryType()
}

/**
 * Trait defining the common interface for entries stored in an Issue Queue.
 * This allows the IssueQueueComponent to be generic.
 */
trait IQEntryLike extends Bundle {
  // --- Common Identifiers & Status ---
  val robPtr: UInt

  // --- Destination Info (Potentially common, or can be specific if needed by select logic) ---
  val physDest: PhysicalRegOperand // Physical destination register
  val physDestIsFpr: Bool          // True if physDest is an FPR
  val writesToPhysReg: Bool        // Does this uop write to a physical register?

  // --- Source Operand 1 ---
  val useSrc1: Bool      // Input: Does this uop use architectural source 1?
  val src1Data: Bits     // Output: Data for source 1 (filled on wake-up)
  val src1Tag: UInt      // Input: Physical register tag for source 1
  val src1Ready: Bool    // Output: Is source 1 ready (data available)?
  val src1IsFpr: Bool    // Input: Is architectural source 1 an FPR?

  // --- Source Operand 2 ---
  val useSrc2: Bool
  val src2Data: Bits
  val src2Tag: UInt
  val src2Ready: Bool
  val src2IsFpr: Bool

  // --- Source Operand 3 (Optional, presence controlled by IssueQueueConfig.usesSrc3) ---
  // These fields should only be physically present if config.usesSrc3 is true.
  // This can be handled by specific entry types or conditional generation.
  // For simplicity here, we'll assume specific entry types will include them if needed.
  // val useSrc3: Bool
  // val src3Data: Bits
  // val src3Tag: UInt
  // val src3Ready: Bool
  // val src3IsFpr: Bool

  // --- Common Methods ---
  def setDefault(): this.type
  def initFrom(renamedUop: RenamedUop, allocatedRobPtr: UInt): this.type
}
// -- MODIFICATION END --

