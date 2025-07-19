package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.components.execute._
import parallax.common._
import parallax.components.issue.IQEntryAluInt
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

class IntAluSpec extends CustomSpinalSimFunSuite {

  val config = PipelineConfig(xlen = 32, robDepth = 32, memOpIdWidth = 8 bits)
  val MAX_U32 = BigInt("FFFFFFFF", 16)
  val NEG_ONE = MAX_U32

  // 辅助函数，现在驱动 .valid 标志
  def driveAluInput(
      dut: IntAlu,
      valid: Boolean,
      aluValid: Boolean = false,
      shiftValid: Boolean = false,
      isAdd: Boolean = false,
      isSub: Boolean = false,
      isSigned: Boolean = false,
      logicOp: LogicOp.E = LogicOp.NONE,
      isRight: Boolean = false,
      isArithmetic: Boolean = false,
      src1: BigInt = 0,
      src2: BigInt = 0,
      imm: BigInt = 0,
      immUsage: ImmUsageType.E = ImmUsageType.NONE,
      dest: Int = 0,
      writesDest: Boolean = true,
      robPtr: Int = 0
  ): Unit = {
    dut.io.iqEntryIn.valid #= valid
    if (valid) {
      dut.io.iqEntryIn.payload.aluCtrl.valid #= aluValid
      dut.io.iqEntryIn.payload.aluCtrl.isAdd #= isAdd
      dut.io.iqEntryIn.payload.aluCtrl.isSub #= isSub
      dut.io.iqEntryIn.payload.aluCtrl.isSigned #= isSigned
      dut.io.iqEntryIn.payload.aluCtrl.logicOp #= logicOp
      
      dut.io.iqEntryIn.payload.shiftCtrl.valid #= shiftValid
      dut.io.iqEntryIn.payload.shiftCtrl.isRight #= isRight
      dut.io.iqEntryIn.payload.shiftCtrl.isArithmetic #= isArithmetic
      
      dut.io.iqEntryIn.payload.src1Data #= src1
      dut.io.iqEntryIn.payload.src2Data #= src2
      dut.io.iqEntryIn.payload.imm #= imm
      dut.io.iqEntryIn.payload.immUsage #= immUsage
      dut.io.iqEntryIn.payload.physDest.idx #= dest
      dut.io.iqEntryIn.payload.writesToPhysReg #= writesDest
      dut.io.iqEntryIn.payload.robPtr #= robPtr
    } else {
      dut.io.iqEntryIn.payload.aluCtrl.valid #= false
      dut.io.iqEntryIn.payload.shiftCtrl.valid #= false
    }
  }

  def checkAluOutput(
      dut: IntAlu,
      isValid: Boolean,
      expectedData: BigInt = 0,
      expectedDest: Int = 0,
      expectedWritesDest: Boolean = false,
      expectedRobPtr: Int = 0,
      expectedException: Boolean = false,
      expectedExceptionCode: IntAluExceptionCode.E = IntAluExceptionCode.NONE
  ): Unit = {
    sleep(0) // 允许组合逻辑稳定
    assert(dut.io.resultOut.valid.toBoolean == isValid, s"Validity mismatch: Expected ${isValid}, got ${dut.io.resultOut.valid.toBoolean}")
    if (isValid) {
      assert(dut.io.resultOut.payload.data.toBigInt == expectedData, s"Data mismatch: Expected ${expectedData}, got ${dut.io.resultOut.payload.data.toBigInt}")
      assert(dut.io.resultOut.payload.physDest.idx.toInt == expectedDest, s"Dest mismatch")
      assert(dut.io.resultOut.payload.writesToPhysReg.toBoolean == expectedWritesDest, s"writesToPhysReg mismatch")
      assert(dut.io.resultOut.payload.robPtr.toInt == expectedRobPtr, s"robPtr mismatch")
      assert(dut.io.resultOut.payload.hasException.toBoolean == expectedException, s"hasException mismatch")
      if (expectedException) {
        assert(dut.io.resultOut.payload.exceptionCode.toEnum == expectedExceptionCode, s"exceptionCode mismatch")
      }
    }
  }

  // C1.1: ALU Operations
  test("IntAlu - ALU Operations") {
    simConfig.compile(new IntAlu(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      println("ADD")
      driveAluInput(dut, valid = true, aluValid = true, isAdd = true, src1 = 10, src2 = 20, dest = 1, robPtr = 2)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 30, expectedDest = 1, expectedWritesDest = true, expectedRobPtr = 2)
      
      println("SUB")
      driveAluInput(dut, valid = true, aluValid = true, isSub = true, src1 = 20, src2 = 10, dest = 3, robPtr = 4)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 10, expectedDest = 3, expectedWritesDest = true, expectedRobPtr = 4)
      
      println("AND")
      driveAluInput(dut, valid = true, aluValid = true, logicOp = LogicOp.AND, src1 = 0xF0, src2 = 0xFF, dest = 5, robPtr = 6)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0xF0, expectedDest = 5, expectedWritesDest = true, expectedRobPtr = 6)
      
      println("OR")
      driveAluInput(dut, valid = true, aluValid = true, logicOp = LogicOp.OR, src1 = 0xF0, src2 = 0x0F, dest = 7, robPtr = 8)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0xFF, expectedDest = 7, expectedWritesDest = true, expectedRobPtr = 8)
      
      println("XOR")
      driveAluInput(dut, valid = true, aluValid = true, logicOp = LogicOp.XOR, src1 = 0xFF, src2 = 0x0F, dest = 9, robPtr = 10)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0xF0, expectedDest = 9, expectedWritesDest = true, expectedRobPtr = 10)
      
      println("SLT (True)")
      driveAluInput(dut, valid = true, aluValid = true, isSub = true, isSigned = true, src1 = BigInt("FFFFFFFB", 16), src2 = 5, dest = 1, robPtr = 2)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 1, expectedDest = 1, expectedWritesDest = true, expectedRobPtr = 2)

      println("SLT (False)")
      driveAluInput(dut, valid = true, aluValid = true, isSub = true, isSigned = true, src1 = 5, src2 = BigInt("FFFFFFFB", 16), dest = 3, robPtr = 4)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0, expectedDest = 3, expectedWritesDest = true, expectedRobPtr = 4)
    }
  }

  // C1.2 & C2: Shift Operations and Data Sources
  test("IntAlu - Shift Operations") {
    simConfig.compile(new IntAlu(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      println("SLL from register (src2)")
      driveAluInput(dut, valid = true, shiftValid = true, isRight = false, src1 = 1, src2 = 4, dest = 11, robPtr = 12)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 16, expectedDest = 11, expectedWritesDest = true, expectedRobPtr = 12)

      println("SRL from immediate")
      driveAluInput(dut, valid = true, shiftValid = true, isRight = true, src1 = 16, imm = 2, immUsage = ImmUsageType.SRC_SHIFT_AMT, dest = 13, robPtr = 14)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 4, expectedDest = 13, expectedWritesDest = true, expectedRobPtr = 14)
      
      println("SRA from register")
      val src1_sra = BigInt("FFFFFFF0", 16) // -16
      val expected_sra = BigInt("FFFFFFFC", 16) // -4
      driveAluInput(dut, valid = true, shiftValid = true, isRight = true, isArithmetic = true, src1 = src1_sra, src2 = 2, dest = 15, robPtr = 16)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = expected_sra, expectedDest = 15, expectedWritesDest = true, expectedRobPtr = 16)
    }
  }

  // C3: Exception Paths
  test("IntAlu - Exception Paths") {
    simConfig.compile(new IntAlu(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      println("C3.1: No valid control flags")
      driveAluInput(dut, valid = true, aluValid = false, shiftValid = false, src1 = 10, src2 = 20, dest = 1, robPtr = 2)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0, expectedDest = 1, expectedWritesDest = true, expectedRobPtr = 2, expectedException = true, expectedExceptionCode = IntAluExceptionCode.UNDEFINED_ALU_OP)
    }
  }

  // C5: Corner Case Data
  test("IntAlu - Corner Case Data") {
    simConfig.compile(new IntAlu(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      println("C5.1: Zero operand")
      driveAluInput(dut, valid = true, aluValid = true, isAdd = true, src1 = 0, src2 = 0, writesDest = false)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0, expectedWritesDest = false)

      println("C5.2: Max operand (overflow check)")
      driveAluInput(dut, valid = true, aluValid = true, isAdd = true, src1 = MAX_U32, src2 = 1, writesDest = false)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0, expectedWritesDest = false) // Wraps around

      println("C5.4 & C5.5: Shift by 0 and 31")
      driveAluInput(dut, valid = true, shiftValid = true, isRight=false, src1 = 0xAAAAAAAAL, src2 = 0, writesDest = false) // Shift by 0
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0xAAAAAAAAL, expectedWritesDest = false)

      driveAluInput(dut, valid = true, shiftValid = true, isRight=true, isArithmetic=true, src1 = 0x80000000L, src2 = 31, writesDest = false) // Shift by 31
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 0xFFFFFFFFL, expectedWritesDest = false)

      println("C5.6: Shift by > 31 (should use lower bits)")
      driveAluInput(dut, valid = true, shiftValid = true, isRight=false, src1 = 1, src2 = 34, writesDest = false) // 34 mod 32 is 2
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 4, expectedWritesDest = false)
    }
  }

  // C6 & C7: Temporal Tests
  test("IntAlu - Temporal Tests") {
    simConfig.compile(new IntAlu(config)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      println("C7.1: Start with invalid input")
      driveAluInput(dut, valid = false)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=false)
      
      println("Transition to valid ADD")
      driveAluInput(dut, valid = true, aluValid = true, isAdd = true, src1 = 1, src2 = 2, dest=1, robPtr=1)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 3, expectedDest = 1, expectedWritesDest=true, expectedRobPtr=1)

      println("C6.1: Back-to-back SUB")
      driveAluInput(dut, valid = true, aluValid = true, isSub = true, src1 = 10, src2 = 3, dest=2, robPtr=2)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 7, expectedDest=2, expectedWritesDest=true, expectedRobPtr=2)

      println("Back-to-back SHIFT")
      driveAluInput(dut, valid = true, shiftValid = true, isRight=false, src1 = 5, src2 = 1, dest=3, robPtr=3)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=true, expectedData = 10, expectedDest=3, expectedWritesDest=true, expectedRobPtr=3)

      println("Back to invalid")
      driveAluInput(dut, valid = false)
      dut.clockDomain.waitSampling()
      checkAluOutput(dut, isValid=false)
    }
  }
  
  thatsAll
}