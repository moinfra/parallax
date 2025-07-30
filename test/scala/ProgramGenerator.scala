package test.scala

import _root_.test.scala.LA32RInstrBuilder._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import spinal.lib.BigIntRicher

// =================================================================================
// 1. Core Data Structures for the Generator
// =================================================================================

/** A Basic Block, the fundamental unit of a program's control flow. */
case class BasicBlock(id: Int, var exitEdge: Option[ControlFlowEdge] = None)

/** A Control Flow Edge, representing the transition between Basic Blocks. */
case class ControlFlowEdge(edgeType: EdgeType)

/** The type of control flow transition. */
sealed trait EdgeType
case class UnconditionalJump(targetBlockId: Int, useJirl: Boolean) extends EdgeType
case class ConditionalBranch(trueBlockId: Int, falseBlockId: Int) extends EdgeType
case object Fallthrough extends EdgeType
case object Halt extends EdgeType

/** The Control Flow Graph, representing the entire program structure. */
class ControlFlowGraph {
  val blocks = new ArrayBuffer[BasicBlock]()
  private var nextBlockId = 0

  def createBlock(): BasicBlock = {
    val block = BasicBlock(nextBlockId)
    nextBlockId += 1
    blocks += block
    block
  }
}

/** Placeholder for a branch/jump instruction whose offset is not yet known. */
sealed trait PlaceholderInstruction

// For standard branches
case class BeqPlaceholder(rj: Int, rk: Int, targetBlockId: Int) extends PlaceholderInstruction
case class BnePlaceholder(rj: Int, rk: Int, targetBlockId: Int) extends PlaceholderInstruction
case class BPlaceholder(targetBlockId: Int) extends PlaceholderInstruction

// Special placeholders for the 3-instruction JIRL sequence
// lu12i.w rd, imm20 <- Placeholder for this
// ori     rd, rd, imm12 <- This will be a concrete instruction, but patched based on the lu12i
// jirl    rd, rj, 0   <- Placeholder for this

/** Placeholder for the `lu12i.w` instruction of a JIRL sequence. */
case class JirlLu12iPlaceholder(rd: Int, targetBlockId: Int) extends PlaceholderInstruction

/** Placeholder for the final `jirl` instruction of a JIRL sequence.
  * It needs to know which registers were chosen for the sequence.
  * `rj` is the base register (containing the address).
  * `rd` is the link register.
  */
case class JirlFinalPlaceholder(rj: Int, rd: Int) extends PlaceholderInstruction

/** Placeholder for a program halt instruction. */
case object HaltPlaceholder extends PlaceholderInstruction

// =================================================================================
// 2. The ProgramGenerator Object
// =================================================================================

object ProgramGenerator {

  private var cfg: FuzzCfg = _
  private var rand: Random = _

  // --- Helper Methods & Instruction Templates (from previous discussions) ---

  private def load32bImm(rd: Int, value: BigInt): Seq[BigInt] = {
    val destReg = if (rd == 0) rand.nextInt(31) + 1 else rd
    val high20 = ((value >> 12) & 0xfffff).toInt
    val low12 = (value & 0xfff).toInt
    Seq(lu12i_w(destReg, high20), ori(destReg, destReg, low12))
  }

  private def getRandReg(exclude: Set[Int] = Set(0)): Int = {
    var reg = 0
    do {
      reg = rand.nextInt(32)
    } while (exclude.contains(reg))
    reg
  }

  private def genAluR(): Seq[BigInt] = {
    val rd = getRandReg(); val rj = getRandReg(Set()); val rk = getRandReg(Set())
    Seq(rand.nextInt(5) match {
      case 0 => add_w(rd, rj, rk); case 1 => sub_w(rd, rj, rk)
      case 2 => and(rd, rj, rk); case 3   => or(rd, rj, rk); case 4 => xor(rd, rj, rk)
    })
  }

  private def genAluI(): Seq[BigInt] = {
    val rd = getRandReg(); val rj = getRandReg(Set()); val imm12 = rand.nextInt(4096) - 2048
    Seq(addi_w(rd, rj, imm12))
  }

  /** Helper: Calculate a guaranteed safe and aligned memory address.
    * @param accessSize Size of access in bytes (e.g., 4 for word).
    * @param offset The immediate offset from the instruction.
    * @return A base address that, when added to offset, results in a final address
    *         that is BOTH within D-SRAM bounds AND aligned to accessSize.
    */
  private def getSafeDataAddress(accessSize: Int, offset: Int): BigInt = {
    val dSramByteSize = cfg.dSramSize
    val alignmentMask = ~(accessSize - 1)

    // Calculate the valid range for the *final* address (base + offset)
    val minFinalAddr = cfg.dSramBase
    val maxFinalAddr = cfg.dSramBase + dSramByteSize - accessSize

    // How many aligned "slots" are there in the valid range?
    val firstAlignedSlot = (minFinalAddr + (accessSize - 1)) & alignmentMask
    val lastAlignedSlot = maxFinalAddr & alignmentMask

    if (firstAlignedSlot > lastAlignedSlot) {
      // Should not happen with reasonable SRAM sizes
      return cfg.dSramBase
    }

    val numSlots = ((lastAlignedSlot - firstAlignedSlot) / accessSize) + 1
    val randomSlotIndex = rand.nextInt(numSlots.toInt)

    val finalAddress = firstAlignedSlot + randomSlotIndex * accessSize

    // Now, calculate the base address that will produce this final address
    val baseAddress = finalAddress - offset
    baseAddress
  }

  private def genSafeMemAccess(): Seq[BigInt] = {
    val r_base = getRandReg()
    val r_data = getRandReg()
    // For ld.w/st.w, the offset itself doesn't strictly need to be aligned,
    // but the final address (base + offset) MUST be.
    val offset = rand.nextInt(2048) - 1024 // Use full 12-bit range

    // Let's generate a word access
    val accessSize = 4
    val safeBase = getSafeDataAddress(accessSize, offset)

    // Verify for ourselves
    val finalAddr = safeBase + offset
    assert(
      (finalAddr % accessSize) == 0,
      s"Generated misaligned address! Base=0x${safeBase.toString(16)}, Offset=0x${Integer
          .toHexString(offset)}, Final=0x${finalAddr.toString(16)}"
    )

    load32bImm(r_base, safeBase) :+ (if (rand.nextBoolean()) st_w(r_data, r_base, offset)
                                     else ld_w(r_data, r_base, offset))
  }

  // --- The 3-Pass Generation Logic ---

  /** Pass 1: Build the abstract Control Flow Graph.
    * Creates blocks and defines the control flow edges between them without any instructions.
    */
  private def buildAbstractCFG(numBlocks: Int): ControlFlowGraph = {
    val cfgBuilder = new ControlFlowGraph()
    val blocks = (0 until numBlocks).map(_ => cfgBuilder.createBlock()).toList
    val haltBlock = cfgBuilder.createBlock()

    for ((block, i) <- blocks.zipWithIndex) {
      val nextBlock = if (i + 1 < blocks.length) blocks(i + 1) else haltBlock

      rand.nextInt(10) match {
        case 0 | 1 => // Conditional branch to a random block, else fallthrough to the next
          val targetBlock = blocks(rand.nextInt(blocks.length))
          block.exitEdge = Some(ControlFlowEdge(ConditionalBranch(targetBlock.id, nextBlock.id)))
        case 2 => // Unconditional B jump to a random block
          val targetBlock = blocks(rand.nextInt(blocks.length))
          block.exitEdge = Some(ControlFlowEdge(UnconditionalJump(targetBlock.id, useJirl = false)))
        case 3 => // Unconditional JIRL jump to a random block
          val targetBlock = blocks(rand.nextInt(blocks.length))
          block.exitEdge = Some(ControlFlowEdge(UnconditionalJump(targetBlock.id, useJirl = true)))
        case _ => // Fallthrough to the next physically laid out block
          block.exitEdge = Some(ControlFlowEdge(Fallthrough))
      }
    }
    blocks.last.exitEdge = Some(ControlFlowEdge(UnconditionalJump(haltBlock.id, useJirl = false)))
    haltBlock.exitEdge = Some(ControlFlowEdge(Halt))
    cfgBuilder
  }

  /** Pass 2: Instruction Generation and Layout (Corrected Logic).
    * Fills blocks with concrete instructions and placeholders for jumps.
    * Calculates the final start address of every block.
    * @return A tuple containing the instruction stream (with placeholders) and the block address map.
    */
  private def generateAndLayout(
      cfgBuilder: ControlFlowGraph
  ): (Seq[Either[BigInt, PlaceholderInstruction]], Map[Int, BigInt]) = {
    val instructionStream = new ArrayBuffer[Either[BigInt, PlaceholderInstruction]]()
    val blockAddrMap = scala.collection.mutable.Map[Int, BigInt]()
    val bodyInstructionGens = Seq(() => genAluR(), () => genAluI(), () => genSafeMemAccess())

    for (block <- cfgBuilder.blocks) {
      // Record the starting address of this block
      blockAddrMap(block.id) = cfg.iSramBase + instructionStream.length * 4

      // 1. Populate block body with general-purpose instructions
      if (block.exitEdge.get.edgeType != Halt) {
        val numInstructionSeqs = 3 + rand.nextInt(5)
        for (_ <- 0 until numInstructionSeqs) {
          val gen = bodyInstructionGens(rand.nextInt(bodyInstructionGens.length))
          gen().foreach(inst => instructionStream += Left(inst))
        }
      }

      // 2. Add exit instructions as an ATOMIC sequence at the end of the block.
      block.exitEdge.foreach { edge =>
        edge.edgeType match {
          case Fallthrough =>
            // To make fallthrough more interesting, add a NOP or a random ALU op
            instructionStream += Left(addi_w(0, 0, 0))

          case Halt =>
            instructionStream += Right(HaltPlaceholder)

          case UnconditionalJump(targetId, useJirl) =>
            if (useJirl) {
              // The JIRL sequence is now generated ATOMICALLY here.
              // We choose a random base register that won't be easily guessed or clobbered.
              val r_link = getRandReg()
              val r_base = getRandReg(Set(0, r_link))

              // We insert the concrete load instructions now, with a dummy address.
              // The placeholder will tell Pass 3 how to patch them.
              instructionStream += Right(JirlLu12iPlaceholder(r_base, targetId)) // Placeholder for lu12i
              instructionStream += Left(ori(r_base, r_base, 0)) // Placeholder for ori, will be patched based on lu12i
              instructionStream += Right(JirlFinalPlaceholder(r_base, r_link)) // Placeholder for the final jirl
            } else {
              instructionStream += Right(BPlaceholder(targetId))
            }

          case ConditionalBranch(trueId, falseId) =>
            // This sequence is also atomic.
            val rj = getRandReg(Set())
            val rk = getRandReg(Set())
            val placeholder = if (rand.nextBoolean()) BeqPlaceholder(rj, rk, trueId) else BnePlaceholder(rj, rk, trueId)
            instructionStream += Right(placeholder)
            instructionStream += Right(BPlaceholder(falseId))
        }
      }
    }
    (instructionStream.toSeq, blockAddrMap.toMap)
  }

  /** Pass 3: Offset Patching (Backpatching) (Corrected Logic).
    * Replaces all placeholders in the instruction stream with concrete instructions
    * containing the correctly calculated offsets.
    */
  private def patchOffsets(
      stream: Seq[Either[BigInt, PlaceholderInstruction]],
      blockAddrMap: Map[Int, BigInt]
  ): Seq[BigInt] = {

    val finalInstructions = new ArrayBuffer[BigInt](stream.size)

    for ((instOrPlaceholder, i) <- stream.zipWithIndex) {
      val currentPc = cfg.iSramBase + i * 4

      instOrPlaceholder match {
        case Left(concreteInst) =>
          // This is a concrete instruction. We only need to patch it if it's the `ori` of a JIRL sequence.
          // To identify it, we check if the PREVIOUS instruction was a JirlLu12iPlaceholder.

          var patched = false
          if (i > 0) { // <-- CRITICAL FIX: Check for index boundary
            stream(i - 1) match {
              case Right(JirlLu12iPlaceholder(jirl_base, targetId)) =>
                // The previous instruction was a JIRL lu12i placeholder.
                // This means the current `concreteInst` is the `ori` that needs patching.
                // We must also verify that the register matches.
                val ori_rd = (concreteInst >> 5) & 0x1f
                val ori_rj = (concreteInst >> 10) & 0x1f
                if (ori_rd == jirl_base && ori_rj == jirl_base) {
                  val targetAddr = blockAddrMap(targetId)
                  val low12 = (targetAddr & 0xfff).toInt
                  finalInstructions += ori(jirl_base, jirl_base, low12)
                  patched = true
                }
              case _ => // Previous instruction was not a JIRL placeholder.
            }
          }

          if (!patched) {
            // If it wasn't patched, add the instruction as is.
            finalInstructions += concreteInst
          }

        case Right(placeholder) =>
          placeholder match {
            case HaltPlaceholder => finalInstructions += beq(0, 0, 0)
            case BPlaceholder(targetId) =>
              val targetAddr = blockAddrMap(targetId)
              val offset = targetAddr - currentPc
              finalInstructions += b(offset.toInt)
            case BeqPlaceholder(rj, rk, targetId) =>
              val targetAddr = blockAddrMap(targetId)
              val offset = targetAddr - (currentPc + 4)
              finalInstructions += beq(rj, rk, offset.toInt)
            case BnePlaceholder(rj, rk, targetId) =>
              val targetAddr = blockAddrMap(targetId)
              val offset = targetAddr - (currentPc + 4)
              finalInstructions += bne(rj, rk, offset.toInt)
            case JirlLu12iPlaceholder(rd, targetId) =>
              // This is the first instruction of the JIRL sequence.
              val targetAddr = blockAddrMap(targetId)
              val high20 = ((targetAddr >> 12) & 0xfffff).toInt
              finalInstructions += lu12i_w(rd, high20)
            case JirlFinalPlaceholder(rj, rd) =>
              // This is the final instruction of the JIRL sequence.
              finalInstructions += jirl(rd, rj, 0)
          }
      }
    }
    finalInstructions.toSeq
  }

  /** The main entry point that orchestrates the 3 passes */
  def generate(p_cfg: FuzzCfg, p_rand: Random): Seq[BigInt] = {
    this.cfg = p_cfg
    this.rand = p_rand

    // Pass 1: Build the abstract graph of blocks and edges
    val cfgGraph = buildAbstractCFG(numBlocks = 50) // Generate 50 blocks

    // Pass 2: Fill blocks with instructions and layout placeholders, get addresses
    val (instructionStreamWithPlaceholders, blockAddressMap) = generateAndLayout(cfgGraph)

    // Pass 3: Replace placeholders with concrete instructions with correct offsets
    var finalInstructions = patchOffsets(instructionStreamWithPlaceholders, blockAddressMap)

    // Truncate if the generated program is too long
    if (finalInstructions.length > cfg.instCount) {
      println(
        s"Warning: Generated program (${finalInstructions.length}) is longer than instCount (${cfg.instCount}). Truncating."
      )
      finalInstructions = finalInstructions.take(cfg.instCount - 1) :+ beq(0, 0, 0)
    }

    finalInstructions
  }
}
