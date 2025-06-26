package parallax.fetch

import spinal.core._
import parallax.common.PipelineConfig

/**
 * A lightweight pre-decoder for the LA32R ISA, specifically designed for branch prediction.
 * It identifies instructions that can alter the program counter's flow.
 * 
 * In this context:
 * - isJump:   Identifies direct, unconditional jumps (B, BL). The target is encoded in the
 *             instruction, and the jump is always taken. These are the easiest to predict.
 * - isBranch: Identifies conditional branches (BEQ, BNE, BLT, etc.) and indirect jumps (JIRL).
 *             The outcome (taken/not-taken) or the target address (for JIRL) is not known
 *             at decode time, making them targets for more complex prediction mechanisms
 *             like a Branch Target Buffer (BTB) and history predictors.
 */
class InstructionPredecoder(pCfg: PipelineConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (pCfg.dataWidth)
    val isBranch    = out Bool()
    val isJump      = out Bool()
  }

  // Per the LA32R ISA Encoding, the primary opcode is in the most significant 6 bits.
  val opcode = io.instruction(31 downto 26)

  // Default to a non-control-flow instruction
  io.isBranch := False
  io.isJump   := False

  // Decode based on the primary 6-bit opcode.
  switch(opcode) {
    // --- Conditional Branches & Indirect Jumps (isBranch = True) ---

    // Floating-Point Conditional Branches: BCEQZ, BCNEZ
    // Ref: TYPE_1RI21 `opcode[31:26] offs[15:0] cj[9:5] offs[20:16]`
    is(B"010010") {
      io.isBranch := True
    }

    // Indirect Jump: JIRL (Jump and Link Register)
    // Ref: TYPE_2RI16 `opcode[31:16] offs[15:0] rj[9:5] rd[4:0]`
    // The top 6 bits of the opcode are 0b010011.
    is(B"010011") {
      io.isBranch := True
    }

    // Integer Conditional Branches on Equality: BEQ, BNE
    // Ref: TYPE_2RI16 `opcode[31:16] offs[15:0] rj[9:5] rd[4:0]`
    // Opcodes are 0b010110 (BEQ) and 0b010111 (BNE).
    is(B"010110", B"010111") {
      io.isBranch := True
    }

    // Integer Conditional Branches on Comparison: BLT, BGE, BLTU, BGEU
    // Ref: TYPE_2RI16 `opcode[31:16] offs[15:0] rj[9:5] rd[4:0]`
    // Opcodes are 0b011000 (BLT), 0b011001 (BGE), 0b011010 (BLTU), 0b011011 (BGEU).
    is(B"011000", B"011001", B"011010", B"011011") {
      io.isBranch := True
    }

    // --- Unconditional Direct Jumps (isJump = True) ---

    // Unconditional Jump: B
    // Ref: TYPE_I26 `opcode[31:6] I26[15:0] I26[25:16]`
    // The top 6 bits of the opcode are 0b010100.
    is(B"010100") {
      io.isJump := True
    }

    // Unconditional Jump and Link: BL
    // Ref: TYPE_I26 `opcode[31:6] I26[15:0] I26[25:16]`
    // The top 6 bits of the opcode are 0b010101.
    is(B"010101") {
      io.isJump := True
    }
  }
}
