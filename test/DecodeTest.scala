import spinal.core._
import spinal.sim._
import spinal.core.formal._
import org.scalatest.funsuite.AnyFunSuite
import boson.BosonConfig
import boson.plugins.Decoder
import spinal.core.sim._
import boson.defines._
import spinal.core
// Assuming UopCode, ExeUnit, MemSize, BranchCond, ExceptionCode, BosonConfig, DecodedInst are defined elsewhere

class DecoderAreaTest extends AnyFunSuite {

  // Helper function to create a testbench
  def testDecoder(config: BosonConfig)(testFn: (boson.plugins.Decoder) => Unit): Unit = {
    SimConfig.withFstWave
      .compile({
        val dut = new Decoder(config)
        dut.io.decodedInst.uopCode.simPublic()
        dut
      })
      .doSim(testFn)
  }

  test("ADD.W instruction decoding") {
    testDecoder(new BosonConfig()) { dut =>
      println("Testing ADD.W instruction decoding")
      dut.clockDomain.forkStimulus(period = 10) // 10ns clock period

      // Construct ADD.W instruction: M"0000000_0000_100000-----_-----_-----"
      // Assuming fields:
      // opcode_main = 0000000 (7 bits), opcode_ext = 0000 (4 bits), func = 100000 (6 bits)
      // rk = 2, rj = 3, rd = 5
      dut.io.instruction #= BigInt("0000000_0000_100000_00010_00011_00101".replace("_", ""), 2)
      
      dut.io.pc #= 0x100

      dut.clockDomain.waitSampling(1)

      println(s"dut.io.decodedInst.uopCode ${dut.io.decodedInst.uopCode.toBigInt}")
      assert(dut.io.decodedInst.isValid.toBoolean === true)
      assert(dut.io.decodedInst.uopCode.toBigInt === UopCode.ALU_ADD.position)
      assert(dut.io.decodedInst.executeUnit.toBigInt === ExeUnit.ALU.position)
      assert(dut.io.decodedInst.archDest.idx.toInt === 5)
      assert(dut.io.decodedInst.archSrc1.idx.toInt === 3)
      assert(dut.io.decodedInst.archSrc2.idx.toInt === 2)
      assert(dut.io.decodedInst.aluFlags.isSub.toBoolean === false)
    }
  }

  // test("BEQ branch instruction decoding") {
  //   testDecoder(new BosonConfig()) { dut =>
  //     // Construct BEQ instruction: M"010110----------------_-----_-----"
  //     // Example: BEQ r3, r5, +0x100
  //     val instruction = 0x14650100 // Example hex value
  //     dut.io.instruction #= instruction
  //     dut.io.pc #= 0x200

  //     sleep(1)

  //     assert(dut.io.decodedInst.isValid.toBoolean === true)
  //     assert(dut.io.decodedInst.uopCode.toBigInt === UopCode.BRANCH.position)
  //     assert(dut.io.decodedInst.executeUnit.toBigInt === ExeUnit.BRU.position)
  //     assert(dut.io.decodedInst.archSrc1.idx.toInt === 3)
  //     assert(dut.io.decodedInst.archSrc2.idx.toInt === 5)
  //     assert(dut.io.decodedInst.branchCond.toInt === BranchCond.EQ)
  //     assert(dut.io.decodedInst.imm.toInt === 0x100 << 2) // Check sign-extended offset
  //   }
  // }

  // test("Illegal instruction handling") {
  //   testDecoder(new BosonConfig()) { dut =>
  //     // Use an undefined opcode (e.g., all ones)
  //     val instruction = 0xFFFFFFFF
  //     dut.io.instruction #= instruction
  //     dut.io.pc #= 0x300

  //     sleep(1)

  //     assert(dut.io.decodedInst.isValid.toBoolean === false)
  //     assert(dut.io.decodedInst.generatesExc.toBoolean === true)
  //     assert(dut.io.decodedInst.excCode.toInt === ExceptionCode.INE)
  //   }
  // }

  // test("LD.W load instruction decoding") {
  //   testDecoder(new BosonConfig()) { dut =>
  //     // Construct LD.W instruction: M"0010100_000------------_-----_-----"
  //     // Example: LD.W r5, 0x100(r3)
  //     val instruction = 0x8C650100 // Opcode + rd=5, rj=3, si12=0x100
  //     dut.io.instruction #= instruction
  //     dut.io.pc #= 0x400

  //     sleep(1)

  //     assert(dut.io.decodedInst.isValid.toBoolean === true)
  //     assert(dut.io.decodedInst.uopCode.toBigInt === UopCode.LOAD.position)
  //     assert(dut.io.decodedInst.executeUnit.toBigInt === ExeUnit.LSU.position)
  //     assert(dut.io.decodedInst.archDest.idx.toInt === 5)
  //     assert(dut.io.decodedInst.archSrc1.idx.toInt === 3)
  //     assert(dut.io.decodedInst.imm.toInt === 0x100)
  //     assert(dut.io.decodedInst.memSize.toInt === MemSize.W)
  //   }
  // }

  // test("CSRRD CSR read instruction decoding") {
  //   testDecoder(new BosonConfig()) { dut =>
  //     // Construct CSRRD instruction: M"0000010_0_--------------_00000_-----"
  //     // Example: CSRRD r5, CSR_ADDR
  //     val csrAddr = 0x123
  //     val instruction = (0x04000000 | (csrAddr << 10) | (5 << 15)) // Adjust based on encoding
  //     dut.io.instruction #= instruction
  //     dut.io.pc #= 0x500

  //     sleep(1)

  //     assert(dut.io.decodedInst.isValid.toBoolean === true)
  //     assert(dut.io.decodedInst.uopCode.toBigInt === UopCode.CSR_READ.position)
  //     assert(dut.io.decodedInst.executeUnit.toBigInt === ExeUnit.CSR.position)
  //     assert(dut.io.decodedInst.archDest.idx.toInt === 5)
  //     assert(dut.io.decodedInst.csrAddr.toInt === csrAddr)
  //   }
  // }

  // test("FADD.S float add instruction decoding") {
  //   testDecoder(new BosonConfig()) { dut =>
  //     // Construct FADD.S instruction: M"0000000_1000_000001-----_-----_-----"
  //     // Example: FADD.S fd=5, fj=3, fk=2
  //     val instruction = 0x40032005 // Adjust based on encoding
  //     dut.io.instruction #= instruction
  //     dut.io.pc #= 0x600

  //     sleep(1)

  //     assert(dut.io.decodedInst.isValid.toBoolean === true)
  //     assert(dut.io.decodedInst.uopCode.toBigInt === UopCode.FPU_ADD.position)
  //     assert(dut.io.decodedInst.executeUnit.toBigInt === ExeUnit.FPU_ADD.position)
  //     assert(dut.io.decodedInst.archDest.idx.toInt === 5)
  //     assert(dut.io.decodedInst.archSrc1.idx.toInt === 3)
  //     assert(dut.io.decodedInst.archSrc2.idx.toInt === 2)
  //     assert(dut.io.decodedInst.fpSize.toInt === FpSize.S)
  //   }
  // }
}
