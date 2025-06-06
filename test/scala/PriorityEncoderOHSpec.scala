package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite
import parallax.utils.Encoders.PriorityEncoderOH

case class PriorityEncoderOHTestBench(width: Int) extends Component {
  val io = new Bundle {
    val input  = in Bits(width bits)
    val output = out Bits(width bits)
  }
  io.output := PriorityEncoderOH(io.input)
}

class PriorityEncoderOHSpec extends SpinalSimFunSuite {
  def runCase(width: Int, testNameSuffix: String = ""): Unit = {
    test(s"PriorityEncoderOH $width-bit ${testNameSuffix}") {
      SimConfig.withWave.compile(PriorityEncoderOHTestBench(width)).doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        def getExpected(inputValue: BigInt, w: Int): BigInt = {
          if (inputValue == 0) return BigInt(0)
          var i = 0
          while (i < w) {
            if (((inputValue >> i) & 1) == 1) {
              return BigInt(1) << i
            }
            i += 1
          }
          BigInt(0)
        }

        // Test case 1: All zeros
        dut.io.input #= 0
        sleep(1) // Allow combinatorial logic to propagate
        assert(dut.io.output.toBigInt == 0, "All zeros input failed")

        // Test case 2: Single bit set
        for (i <- 0 until width) {
          val inputVal = BigInt(1) << i
          dut.io.input #= inputVal
          sleep(1)
          assert(dut.io.output.toBigInt == inputVal, s"Single bit at $i input failed (input: ${inputVal.toString(16)})")
        }

        // Test case 3: Multiple bits set
        if (width >= 4) {
          val testInputs = Seq(
            BigInt("0110", 2), // expected 0010
            BigInt("1110", 2), // expected 0010
            BigInt("1001", 2), // expected 0001
            (BigInt(1) << width) -1 // All ones, expected 00...01
          )
          for (inputVal <- testInputs) {
            dut.io.input #= inputVal
            sleep(1)
            val expectedVal = getExpected(inputVal, width)
            assert(dut.io.output.toBigInt == expectedVal, s"Multi-bit input ${inputVal.toString(2)} failed, expected ${expectedVal.toString(2)}, got ${dut.io.output.toBigInt.toString(2)}")
          }
        }
        
        // Test max value for this width (all ones)
        val maxInput = (BigInt(1) << width) - 1
        if (maxInput > 0) { // Avoid if width is 0, though our component requires width > 0
            dut.io.input #= maxInput
            sleep(1)
            assert(dut.io.output.toBigInt == 1, s"All ones input (0x${maxInput.toString(16)}) failed")
        }


        // Random tests
        val random = new scala.util.Random(42)
        for (_ <- 0 until 50) {
          val randomInput = BigInt(width, random)
          dut.io.input #= randomInput
          sleep(1)
          val expectedVal = getExpected(randomInput, width)
          assert(dut.io.output.toBigInt == expectedVal, s"Random input ${randomInput.toString(16)} failed, expected ${expectedVal.toString(16)}, got ${dut.io.output.toBigInt.toString(16)}")
        }
        
        dut.clockDomain.waitSampling(5)
      }
    }
  }
  runCase(1, "1-bit")
  runCase(4, "4-bit")
  runCase(8, "8-bit")
  runCase(16, "16-bit")
}
