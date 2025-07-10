// cmd testOnly test.scala.LA32RSimpleDecoderSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import parallax.common._
import parallax.components.decode.LA32RSimpleDecoder

// A testbench component to wrap the LA32RSimpleDecoder
class LA32RSimpleDecoderTestBench(val config: PipelineConfig = PipelineConfig()) extends Component {
  val io = new Bundle {
    val instruction = in Bits(config.dataWidth)
    val pcIn = in UInt(config.pcWidth)
    val decodedUop = out(DecodedUop(config))
  }
  val decoder = new LA32RSimpleDecoder(config)
  decoder.io.instruction := io.instruction
  decoder.io.pcIn := io.pcIn
  io.decodedUop := decoder.io.decodedUop

  io.decodedUop.simPublic() // Make all fields public for simulation inspection
}

// The main test suite class
class LA32RSimpleDecoderSpec extends CustomSpinalSimFunSuite {
  // Use a shared configuration for all tests
  val testConfig: PipelineConfig = PipelineConfig(xlen = 32)
  def compiledDut = simConfig.withFstWave.compile(new LA32RSimpleDecoderTestBench(testConfig))

  /**
   * Helper function to correctly compare an expected immediate value with the DUT's output.
   * It handles the conversion of a mathematical BigInt (which can be negative) to its
   * raw bit representation for a given width, which is how the DUT's output is interpreted.
   * @param uop The decoded uop from the DUT.
   * @param expected The expected mathematical immediate value (can be negative).
   */
  private def assertImm(uop: DecodedUop, expected: BigInt): Unit = {
    val dataWidth = uop.imm.getWidth
    val mask = (BigInt(1) << dataWidth) - 1
    val expectedAsBits = expected & mask // This correctly computes the two's complement representation
    val actualAsBits = uop.imm.toBigInt

    assert(actualAsBits == expectedAsBits,
      s"Immediate check failed. Expected value ${expected} (as ${dataWidth}-bit: 0x${expectedAsBits.toString(16)}), " +
      s"but got 0x${actualAsBits.toString(16)} from DUT.")
  }

  // --- Reusable Test Runner ---
  def testInstruction(
    instructionName: String,
    instruction: BigInt,
    pc: Long = 0x80000000L
  )(checker: DecodedUop => Unit): Unit = {
    test(s"Decoder - $instructionName") {
      compiledDut.doSim(seed = instruction.toInt) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.instruction #= instruction
        dut.io.pcIn #= pc
        dut.clockDomain.waitSampling(2) // Wait a couple cycles for signals to be stable

        // Apply the provided checker function
        checker(dut.io.decodedUop)
      }
    }
  }

  // --- Test Cases ---

  // ** Illegal and NOP **
  testInstruction("Illegal Instruction (all zeros)", 0x0) { uop =>
    assert(!uop.isValid.toBoolean, "isValid should be false for illegal op")
    assert(uop.uopCode.toEnum == BaseUopCode.ILLEGAL)
    assert(uop.hasDecodeException.toBoolean)
  }

  testInstruction("NOP (ADDI.W r0, r0, 0)", LA32RInstrBuilder.nop()) { uop =>
    assert(uop.isValid.toBoolean)
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assert(uop.exeUnit.toEnum == ExeUnitType.ALU_INT)
    assert(uop.archDest.idx.toInt == 0)
    assert(!uop.writeArchDestEn.toBoolean, "writeArchDestEn should be false for r0")
    assert(uop.archSrc1.idx.toInt == 0)
    assert(uop.useArchSrc1.toBoolean)
    assertImm(uop, 0)
    assert(uop.immUsage.toEnum == ImmUsageType.SRC_ALU)
    assert(uop.aluCtrl.isAdd.toBoolean)
  }

  // ** R-Type Instructions **
  testInstruction("ADD.W r3, r1, r2", LA32RInstrBuilder.add_w(3, 1, 2)) { uop =>
    assert(uop.isValid.toBoolean)
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assert(uop.archDest.idx.toInt == 3)
    assert(uop.writeArchDestEn.toBoolean)
    assert(uop.archSrc1.idx.toInt == 1)
    assert(uop.useArchSrc1.toBoolean)
    assert(uop.archSrc2.idx.toInt == 2)
    assert(uop.useArchSrc2.toBoolean)
    assert(uop.aluCtrl.isAdd.toBoolean)
    assert(!uop.aluCtrl.isSub.toBoolean)
  }

  testInstruction("SUB.W r5, r10, r20", LA32RInstrBuilder.sub_w(5, 10, 20)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assert(uop.archDest.idx.toInt == 5)
    assert(uop.archSrc1.idx.toInt == 10)
    assert(uop.archSrc2.idx.toInt == 20)
    assert(uop.aluCtrl.isSub.toBoolean)
    assert(!uop.aluCtrl.isAdd.toBoolean)
  }

  testInstruction("OR r7, r8, r9", LA32RInstrBuilder.or(7, 8, 9)) {
    uop => assert(uop.aluCtrl.logicOp.toEnum == LogicOp.OR)
  }
  
  testInstruction("AND r1, r2, r3", LA32RInstrBuilder.and(1, 2, 3)) {
    uop => assert(uop.aluCtrl.logicOp.toEnum == LogicOp.AND)
  }

  testInstruction("XOR r4, r5, r6", LA32RInstrBuilder.xor(4, 5, 6)) {
    uop => assert(uop.aluCtrl.logicOp.toEnum == LogicOp.XOR)
  }

  testInstruction("MUL.W r11, r12, r13", LA32RInstrBuilder.mul_w(11, 12, 13)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.MUL)
    assert(uop.exeUnit.toEnum == ExeUnitType.MUL_INT)
    assert(uop.archDest.idx.toInt == 11)
    assert(uop.archSrc1.idx.toInt == 12)
    assert(uop.archSrc2.idx.toInt == 13)
    assert(!uop.mulDivCtrl.isDiv.toBoolean)
    assert(uop.mulDivCtrl.isSigned.toBoolean)
  }
  
  testInstruction("SRL.W r1, r2, r3", LA32RInstrBuilder.srl_w(1, 2, 3)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.SHIFT)
    assert(uop.useArchSrc1.toBoolean)
    assert(uop.useArchSrc2.toBoolean)
    assert(uop.shiftCtrl.isRight.toBoolean)
    assert(!uop.shiftCtrl.isArithmetic.toBoolean)
  }

  // ** I-Type Instructions **
  testInstruction("ADDI.W r1, r2, 1024", LA32RInstrBuilder.addi_w(1, 2, 1024)) { uop =>
    assert(uop.isValid.toBoolean)
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assert(uop.archDest.idx.toInt == 1)
    assert(uop.archSrc1.idx.toInt == 2)
    assert(!uop.useArchSrc2.toBoolean)
    assertImm(uop, 1024)
    assert(uop.aluCtrl.isAdd.toBoolean)
  }

  testInstruction("ADDI.W r3, r4, -1", LA32RInstrBuilder.addi_w(3, 4, -1)) { uop =>
    assertImm(uop, -1)
  }

  testInstruction("SLTI r5, r6, -500", LA32RInstrBuilder.slti(5, 6, -500)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assertImm(uop, -500)
    assert(uop.aluCtrl.isSub.toBoolean)
    assert(uop.aluCtrl.isSigned.toBoolean)
  }
  
  testInstruction("ANDI r7, r8, 0xABC", LA32RInstrBuilder.andi(7, 8, 0xABC)) { uop =>
    assert(uop.aluCtrl.logicOp.toEnum == LogicOp.AND)
    assertImm(uop, 0xABC) // Zero extended
  }
  
  testInstruction("ORI r9, r10, 0xDEF", LA32RInstrBuilder.ori(9, 10, 0xDEF)) { uop =>
    assert(uop.aluCtrl.logicOp.toEnum == LogicOp.OR)
    assertImm(uop, 0xDEF) // Zero extended
  }

  testInstruction("SLLI.W r1, r2, 31", LA32RInstrBuilder.slli_w(1, 2, 31)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.SHIFT)
    assert(uop.immUsage.toEnum == ImmUsageType.SRC_SHIFT_AMT)
    assertImm(uop, 31)
    assert(!uop.shiftCtrl.isRight.toBoolean)
  }
  
  testInstruction("SRLI.W r3, r4, 15", LA32RInstrBuilder.srli_w(3, 4, 15)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.SHIFT)
    assert(uop.immUsage.toEnum == ImmUsageType.SRC_SHIFT_AMT)
    assertImm(uop, 15)
    assert(uop.shiftCtrl.isRight.toBoolean)
    assert(!uop.shiftCtrl.isArithmetic.toBoolean)
  }


  // ** Load/Store Instructions **
  testInstruction("LD.W r5, 100(r6)", LA32RInstrBuilder.ld_w(5, 6, 100)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.LOAD)
    assert(uop.exeUnit.toEnum == ExeUnitType.MEM)
    assert(uop.archDest.idx.toInt == 5)
    assert(uop.archSrc1.idx.toInt == 6)
    assertImm(uop, 100)
    assert(uop.immUsage.toEnum == ImmUsageType.MEM_OFFSET)
    assert(!uop.memCtrl.isStore.toBoolean)
    assert(uop.memCtrl.size.toEnum == MemAccessSize.W)
  }

  testInstruction("ST.W r7, -20(r8)", LA32RInstrBuilder.st_w(7, 8, -20)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.STORE)
    assert(uop.exeUnit.toEnum == ExeUnitType.MEM)
    assert(!uop.writeArchDestEn.toBoolean)
    assert(uop.archSrc1.idx.toInt == 8) // base address
    assert(uop.archSrc2.idx.toInt == 7) // data to store
    assert(uop.useArchSrc2.toBoolean)
    assertImm(uop, -20)
    assert(uop.memCtrl.isStore.toBoolean)
    assert(uop.memCtrl.size.toEnum == MemAccessSize.W)
  }
  
  testInstruction("LD.B r9, 0(r10)", LA32RInstrBuilder.ld_b(9, 10, 0)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.LOAD)
    assert(uop.memCtrl.size.toEnum == MemAccessSize.B)
    assert(uop.memCtrl.isSignedLoad.toBoolean)
  }

  testInstruction("ST.B r11, 1(r12)", LA32RInstrBuilder.st_b(11, 12, 1)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.STORE)
    assert(uop.memCtrl.size.toEnum == MemAccessSize.B)
  }

  // ** PC-Relative Instructions **
  testInstruction("LU12I.W r1, 0xABCDE", LA32RInstrBuilder.lu12i_w(1, 0xABCDE), pc=0x1000) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.ALU)
    assert(!uop.useArchSrc1.toBoolean)
    assertImm(uop, BigInt("ABCDE000", 16))
    assert(uop.aluCtrl.isAdd.toBoolean)
  }

  testInstruction("PCADDU12I r2, 0x12345", LA32RInstrBuilder.pcaddu12i(2, 0x12345), pc=0x2000) { uop =>
      assert(uop.uopCode.toEnum == BaseUopCode.ALU)
      assert(!uop.useArchSrc1.toBoolean, "Should not use GPR source for PCADDU12I")
      assertImm(uop, BigInt("12345000", 16))
  }

  // ** Control Flow Instructions **
  testInstruction("B 0x100", LA32RInstrBuilder.b(0x100), pc=0x8000) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.JUMP_IMM)
    assert(uop.exeUnit.toEnum == ExeUnitType.BRU)
    assert(uop.isBranchOrJump.toBoolean)
    assert(uop.immUsage.toEnum == ImmUsageType.JUMP_OFFSET)
    assertImm(uop, 0x100)
    assert(uop.branchCtrl.isJump.toBoolean)
    assert(!uop.branchCtrl.isLink.toBoolean)
  }
  
  testInstruction("BL -0x200", LA32RInstrBuilder.bl(-0x200), pc=0x9000) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.JUMP_IMM)
    assert(uop.isBranchOrJump.toBoolean)
    assertImm(uop, -0x200)
    assert(uop.branchCtrl.isJump.toBoolean)
    assert(uop.branchCtrl.isLink.toBoolean)
    assert(uop.writeArchDestEn.toBoolean)
    assert(uop.archDest.idx.toInt == 1) // Link register is r1
  }

  testInstruction("BEQ r1, r2, 64", LA32RInstrBuilder.beq(1, 2, 64)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.BRANCH)
    assert(uop.isBranchOrJump.toBoolean)
    assert(uop.archSrc1.idx.toInt == 1)
    assert(uop.archSrc2.idx.toInt == 2)
    assertImm(uop, 64)
    assert(uop.immUsage.toEnum == ImmUsageType.BRANCH_OFFSET)
    assert(uop.branchCtrl.condition.toEnum == BranchCondition.EQ)
  }
  
  testInstruction("BNE r3, r4, -128", LA32RInstrBuilder.bne(3, 4, -128)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.BRANCH)
    assertImm(uop, -128)
    assert(uop.branchCtrl.condition.toEnum == BranchCondition.NE)
  }
  
  testInstruction("BLTU r5, r6, 256", LA32RInstrBuilder.bltu(5, 6, 256)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.BRANCH)
    assertImm(uop, 256)
    assert(uop.branchCtrl.condition.toEnum == BranchCondition.LTU)
  }
  
  testInstruction("JIRL r1, r2, 0", LA32RInstrBuilder.jirl(1, 2, 0)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.JUMP_REG)
    assert(uop.isBranchOrJump.toBoolean)
    assert(uop.branchCtrl.isIndirect.toBoolean)
    assert(uop.branchCtrl.isLink.toBoolean)
    assert(uop.archDest.idx.toInt == 1) // Link register
    assert(uop.archSrc1.idx.toInt == 2) // Jump target base
    assertImm(uop, 0) // Offset
  }

  testInstruction("JIRL r0, r15, 32", LA32RInstrBuilder.jirl(0, 15, 32)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.JUMP_REG)
    assert(uop.archDest.idx.toInt == 0)
    assert(!uop.writeArchDestEn.toBoolean) // rd=r0, so no write
    assert(uop.branchCtrl.isLink.toBoolean) // The instruction is still linking type
    assert(uop.archSrc1.idx.toInt == 15)
    assertImm(uop, 32)
  }

  // ** SHIFT Instructions **
  testInstruction("SLLI.W r2, r1, 2", LA32RInstrBuilder.slli_w(2, 1, 2)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.SHIFT)
    assert(uop.exeUnit.toEnum == ExeUnitType.ALU_INT)
    assert(uop.archSrc1.idx.toInt == 1)
    assert(uop.useArchSrc1.toBoolean)
    assert(!uop.useArchSrc2.toBoolean) // Uses immediate, not register
    assert(uop.immUsage.toEnum == ImmUsageType.SRC_SHIFT_AMT)
    assertImm(uop, 2)
    assert(!uop.shiftCtrl.isRight.toBoolean) // Left shift
    assert(!uop.shiftCtrl.isArithmetic.toBoolean)
    assert(uop.writeArchDestEn.toBoolean)
    assert(uop.archDest.idx.toInt == 2)
  }

  testInstruction("SRLI.W r3, r2, 1", LA32RInstrBuilder.srli_w(3, 2, 1)) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.SHIFT)
    assert(uop.exeUnit.toEnum == ExeUnitType.ALU_INT)
    assert(uop.archSrc1.idx.toInt == 2)
    assert(uop.useArchSrc1.toBoolean)
    assert(!uop.useArchSrc2.toBoolean) // Uses immediate, not register
    assert(uop.immUsage.toEnum == ImmUsageType.SRC_SHIFT_AMT)
    assertImm(uop, 1)
    assert(uop.shiftCtrl.isRight.toBoolean) // Right shift
    assert(!uop.shiftCtrl.isArithmetic.toBoolean) // Logical right shift
    assert(uop.writeArchDestEn.toBoolean)
    assert(uop.archDest.idx.toInt == 3)
  }

  // ** IDLE Instructions **
  testInstruction("IDLE", LA32RInstrBuilder.idle()) { uop =>
    assert(uop.uopCode.toEnum == BaseUopCode.IDLE)
    assert(uop.exeUnit.toEnum == ExeUnitType.ALU_INT)
    assert(!uop.useArchSrc1.toBoolean)
    assert(!uop.useArchSrc2.toBoolean)
    assert(!uop.writeArchDestEn.toBoolean)
  }

  thatsAll()
}
