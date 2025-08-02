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
case class UnconditionalJump(targetBlockId: Int, useJirl: Boolean, useBl: Boolean = false) extends EdgeType
case class ConditionalBranch(branchType: BranchType, trueBlockId: Int, falseBlockId: Int) extends EdgeType
case object Fallthrough extends EdgeType
case object Halt extends EdgeType

/** Enum for different conditional branch types */
sealed trait BranchType
case object BEQ  extends BranchType
case object BNE  extends BranchType
case object BLTU extends BranchType

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
case class BeqPlaceholder(rj: Int, rd: Int, targetBlockId: Int) extends PlaceholderInstruction
case class BnePlaceholder(rj: Int, rd: Int, targetBlockId: Int) extends PlaceholderInstruction
case class BltuPlaceholder(rj: Int, rd: Int, targetBlockId: Int) extends PlaceholderInstruction
case class BPlaceholder(targetBlockId: Int) extends PlaceholderInstruction
case class BlPlaceholder(targetBlockId: Int) extends PlaceholderInstruction

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
// 2. The ProgramGenerator Object (Expanded)
// =================================================================================

object ProgramGenerator {

  private var cfg: FuzzCfg = _
  private var rand: Random = _

  // --- Helper Methods ---

  private def getRandReg(exclude: Set[Int] = Set(0)): Int = {
    var reg = 0
    do {
      reg = rand.nextInt(32)
    } while (exclude.contains(reg))
    reg
  }

  // --- Instruction Generation Functions (Expanded) ---

  private def load32bImm(rd: Int, value: BigInt): Seq[BigInt] = {
    val destReg = if (rd == 0) getRandReg() else rd
    val high20 = ((value >> 12) & 0xfffff).toInt
    val low12 = (value & 0xfff).toInt
    Seq(lu12i_w(destReg, high20), ori(destReg, destReg, low12))
  }

  // Group 1: Standard ALU (R-Type)
  private def genAluR(): Seq[BigInt] = {
    val rd = getRandReg(); val rj = getRandReg(Set()); val rk = getRandReg(Set())
    Seq(rand.nextInt(7) match {
      case 0 => add_w(rd, rj, rk)
      case 1 => sub_w(rd, rj, rk)
      case 2 => and(rd, rj, rk)
      case 3 => or(rd, rj, rk)
      case 4 => xor(rd, rj, rk)
      case 5 => mul_w(rd, rj, rk)
      case 6 => srl_w(rd, rj, rk) // Note: sll_w is R-type too, but shift-by-register is less common to fuzz
    })
  }

  // Group 2: Standard ALU (I-Type)
  private def genAluI(): Seq[BigInt] = {
    val rd = getRandReg(); val rj = getRandReg(Set());
    rand.nextInt(5) match {
      case 0 => Seq(addi_w(rd, rj, rand.nextInt(4096) - 2048))
      case 1 => Seq(andi(rd, rj, rand.nextInt(4096)))
      case 2 => Seq(ori(rd, rj, rand.nextInt(4096)))
      case 3 => Seq(xori(rd, rj, rand.nextInt(4096)))
      case 4 => Seq(slti(rd, rj, rand.nextInt(4096)))
    }
  }

  // Group 3: Shift Immediate (I-Type)
  private def genShiftI(): Seq[BigInt] = {
    val rd = getRandReg(); val rj = getRandReg(Set()); val imm5 = rand.nextInt(32)
    Seq(rand.nextInt(2) match {
        case 0 => slli_w(rd, rj, imm5)
        case 1 => srli_w(rd, rj, imm5)
    })
  }

  // Group 4: Memory Access
  /** Helper: Calculate a guaranteed safe and aligned memory address. */
  private def getSafeDataAddress(accessSize: Int, offset: Int): BigInt = {
    val dSramByteSize = cfg.dSramSize
    val alignmentMask = ~(accessSize - 1)

    val minFinalAddr = cfg.dSramBase
    val maxFinalAddr = cfg.dSramBase + dSramByteSize - accessSize

    val firstAlignedSlot = (minFinalAddr + (accessSize - 1)) & alignmentMask
    val lastAlignedSlot = maxFinalAddr & alignmentMask

    if (firstAlignedSlot > lastAlignedSlot) return cfg.dSramBase // Should not happen

    val numSlots = ((lastAlignedSlot - firstAlignedSlot) / accessSize) + 1
    val randomSlotIndex = if (numSlots > 1) rand.nextInt(numSlots.toInt) else 0

    val finalAddress = firstAlignedSlot + randomSlotIndex * accessSize
    val baseAddress = finalAddress - offset
    baseAddress
  }

  private def genSafeMemAccess(): Seq[BigInt] = {
    val r_base = getRandReg()
    val r_data = getRandReg()
    val offset = rand.nextInt(2048) - 1024 // Full 12-bit signed range

    // Choose access type: Byte, Half-word, or Word
    val (accessSize, accessInstPair) = rand.nextInt(6) match {
        case 0 => (4, (ld_w(r_data, r_base, offset), st_w(r_data, r_base, offset)))
        case 1 => (1, (ld_b(r_data, r_base, offset), st_b(r_data, r_base, offset)))
        case 2 => (2, (ld_h(r_data, r_base, offset), st_h(r_data, r_base, offset)))
        case 3 => (1, (ld_bu(r_data, r_base, offset), st_b(r_data, r_base, offset))) // st_bu doesn't exist
        case 4 => (2, (ld_hu(r_data, r_base, offset), st_h(r_data, r_base, offset))) // st_hu doesn't exist
        case 5 => (4, (ld_w(r_data, r_base, offset), st_w(r_data, r_base, offset))) // Increase weight of word access
    }

    val safeBase = getSafeDataAddress(accessSize, offset)
    val finalAddr = safeBase + offset
    assert((finalAddr % accessSize) == 0, s"Misaligned address! Size=$accessSize, Final=0x${finalAddr.toString(16)}")

    val instToGen = if (rand.nextBoolean()) accessInstPair._2 else accessInstPair._1
    load32bImm(r_base, safeBase) :+ instToGen
  }
  
  // Group 5: Misc instructions
  private def genMisc(): Seq[BigInt] = {
    val rd = getRandReg()
    Seq(rand.nextInt(2) match {
      // pcaddu12i rd, imm. rd <- pc + (imm << 12).
      // A fun way to get a pointer to nearby code.
      case 0 => pcaddu12i(rd, rand.nextInt(1 << 20))
      // idle. Might expose timing issues or pipeline stalls.
      // case 1 => idle(rand.nextInt(1 << 15)) // NOT Supported in 个人赛 yet
      case 1 => pcaddu12i(rd, rand.nextInt(1 << 20))

    })
  }


  // --- The 3-Pass Generation Logic ---

  /** Pass 1: Build the abstract Control Flow Graph. */
  private def buildAbstractCFG(numBlocks: Int): ControlFlowGraph = {
    val cfgBuilder = new ControlFlowGraph()
    val blocks = (0 until numBlocks).map(_ => cfgBuilder.createBlock()).toList
    val haltBlock = cfgBuilder.createBlock()

    for ((block, i) <- blocks.zipWithIndex) {
      val nextBlock = if (i + 1 < blocks.length) blocks(i + 1) else haltBlock
      val targetBlock = blocks(rand.nextInt(blocks.length))

      rand.nextInt(12) match {
        case 0 | 1 | 2 => // Conditional branch
          val branchType = rand.nextInt(3) match {
              case 0 => BEQ
              case 1 => BNE
              case 2 => BLTU
          }
          block.exitEdge = Some(ControlFlowEdge(ConditionalBranch(branchType, targetBlock.id, nextBlock.id)))
        case 3 => // Unconditional B jump
          block.exitEdge = Some(ControlFlowEdge(UnconditionalJump(targetBlock.id, useJirl = false, useBl = false)))
        case 4 => // Unconditional BL jump
          block.exitEdge = Some(ControlFlowEdge(UnconditionalJump(targetBlock.id, useJirl = false, useBl = true)))
        case 5 => // Unconditional JIRL jump
          block.exitEdge = Some(ControlFlowEdge(UnconditionalJump(targetBlock.id, useJirl = true, useBl = false)))
        case _ => // Fallthrough (higher probability)
          block.exitEdge = Some(ControlFlowEdge(Fallthrough))
      }
    }
    // Ensure program can terminate
    blocks.last.exitEdge = Some(ControlFlowEdge(UnconditionalJump(haltBlock.id, useJirl = false)))
    haltBlock.exitEdge = Some(ControlFlowEdge(Halt))
    cfgBuilder
  }

  /** Pass 2: Instruction Generation and Layout. */
  private def generateAndLayout(
      cfgBuilder: ControlFlowGraph
  ): (Seq[Either[BigInt, PlaceholderInstruction]], Map[Int, BigInt]) = {
    val instructionStream = new ArrayBuffer[Either[BigInt, PlaceholderInstruction]]()
    val blockAddrMap = scala.collection.mutable.Map[Int, BigInt]()
    // The main pool of instruction generators
    val bodyInstructionGens = Seq(
      () => genAluR(),
      () => genAluI(),
      () => genShiftI(),
      () => genSafeMemAccess(),
      () => genMisc()
    )

    for (block <- cfgBuilder.blocks) {
      blockAddrMap(block.id) = cfg.iSramBase + instructionStream.length * 4

      if (block.exitEdge.get.edgeType != Halt) {
        val numInstructionSeqs = 3 + rand.nextInt(5)
        for (_ <- 0 until numInstructionSeqs) {
          val gen = bodyInstructionGens(rand.nextInt(bodyInstructionGens.length))
          gen().foreach(inst => instructionStream += Left(inst))
        }
      }

      block.exitEdge.foreach { edge =>
        edge.edgeType match {
          case Fallthrough =>
            instructionStream += Left(nop())
          case Halt =>
            instructionStream += Right(HaltPlaceholder)
          case UnconditionalJump(targetId, useJirl, useBl) =>
            if (useJirl) {
              val r_link = getRandReg()
              val r_base = getRandReg(Set(0, r_link))
              instructionStream += Right(JirlLu12iPlaceholder(r_base, targetId))
              instructionStream += Left(ori(r_base, r_base, 0)) // Patched later
              instructionStream += Right(JirlFinalPlaceholder(r_base, r_link))
            } else if (useBl) {
              instructionStream += Right(BlPlaceholder(targetId))
            } else {
              instructionStream += Right(BPlaceholder(targetId))
            }
          case ConditionalBranch(branchType, trueId, falseId) =>
            val rj = getRandReg(Set())
            val rd = getRandReg(Set()) // In LoongArch, conditional branch uses rd and rj
            val placeholder = branchType match {
                case BEQ => BeqPlaceholder(rj, rd, trueId)
                case BNE => BnePlaceholder(rj, rd, trueId)
                case BLTU => BltuPlaceholder(rj, rd, trueId)
            }
            instructionStream += Right(placeholder)
            // The unconditional jump to the 'false' block
            instructionStream += Right(BPlaceholder(falseId))
        }
      }
    }
    (instructionStream.toSeq, blockAddrMap.toMap)
  }

  /** Pass 3: Offset Patching (Backpatching). */
  private def patchOffsets(
      stream: Seq[Either[BigInt, PlaceholderInstruction]],
      blockAddrMap: Map[Int, BigInt]
  ): Seq[BigInt] = {

    val finalInstructions = new ArrayBuffer[BigInt](stream.size)

    for ((instOrPlaceholder, i) <- stream.zipWithIndex) {
      val currentPc = cfg.iSramBase + i * 4

      instOrPlaceholder match {
        case Left(concreteInst) =>
          var patched = false
          if (i > 0) {
            stream(i - 1) match {
              case Right(JirlLu12iPlaceholder(jirl_base, targetId)) =>
                val ori_rd = (concreteInst >> 5) & 0x1f
                val ori_rj = (concreteInst >> 10) & 0x1f
                if (ori_rd == jirl_base && ori_rj == jirl_base) {
                  val targetAddr = blockAddrMap(targetId)
                  val low12 = (targetAddr & 0xfff).toInt
                  finalInstructions += ori(jirl_base, jirl_base, low12)
                  patched = true
                }
              case _ =>
            }
          }
          if (!patched) {
            finalInstructions += concreteInst
          }

        case Right(placeholder) =>
          placeholder match {
            case HaltPlaceholder => finalInstructions += beq(0, 0, 0) // beq r0, r0, 0 (loop forever)
            
            // Unconditional Jumps
            case BPlaceholder(targetId) =>
              val offset = blockAddrMap(targetId) - currentPc
              finalInstructions += b(offset.toInt)
            case BlPlaceholder(targetId) =>
              val offset = blockAddrMap(targetId) - currentPc
              finalInstructions += bl(offset.toInt)
            
            // Conditional Branches (note: offset is from the next instruction)
            case BeqPlaceholder(rj, rd, targetId) =>
              val offset = blockAddrMap(targetId) - currentPc
              finalInstructions += beq(rj, rd, offset.toInt)
            case BnePlaceholder(rj, rd, targetId) =>
              val offset = blockAddrMap(targetId) - currentPc
              finalInstructions += bne(rj, rd, offset.toInt)
            case BltuPlaceholder(rj, rd, targetId) =>
              val offset = blockAddrMap(targetId) - currentPc
              finalInstructions += bltu(rj, rd, offset.toInt)

            // JIRL sequence
            case JirlLu12iPlaceholder(rd, targetId) =>
              val targetAddr = blockAddrMap(targetId)
              val high20 = ((targetAddr >> 12) & 0xfffff).toInt
              finalInstructions += lu12i_w(rd, high20)
            case JirlFinalPlaceholder(rj, rd) =>
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
    val cfgGraph = buildAbstractCFG(numBlocks = 50)

    // Pass 2: Fill blocks with instructions and layout placeholders, get addresses
    val (instructionStreamWithPlaceholders, blockAddressMap) = generateAndLayout(cfgGraph)

    // Pass 3: Replace placeholders with concrete instructions with correct offsets
    var finalInstructions = patchOffsets(instructionStreamWithPlaceholders, blockAddressMap)

    // Truncate if the generated program is too long
    if (finalInstructions.length > cfg.instCount) {
      println(
        s"Warning: Generated program (${finalInstructions.length}) is longer than instCount (${cfg.instCount}). Truncating."
      )
      // Ensure termination instruction is at the end
      finalInstructions = finalInstructions.take(cfg.instCount - 1) :+ beq(0, 0, 0)
    }

    finalInstructions
  }
}
