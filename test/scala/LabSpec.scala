package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import parallax.CoreNSCSCC
import parallax.components.memory.{SRAMConfig, SimulatedSRAM}
import scala.collection.mutable.ArrayBuffer
import _root_.test.scala.LA32RInstrBuilder._
import parallax.issue.CommitStats

/**
 * Testbench for LabSpec.
 * This component wraps the CoreNSCSCC DUT and connects it to two SimulatedSRAM instances,
 * one for instruction memory (iSram) and one for data memory (dSram).
 */
class LabTestBench(val iDataWords: Seq[BigInt]) extends Component {
  val io = new Bundle {
    val commitStats = out(CommitStats())
  }

  // Instantiate the DUT
  val dut = new CoreNSCSCC(traceCommit = true)
  io.commitStats := dut.io.commitStats
  io.commitStats.simPublic()

  // Configure and instantiate the Instruction SRAM (iSram)
  val iSramConfig = SRAMConfig(
    addressWidth = 20,
    dataWidth = 32,
    virtualBaseAddress = BigInt("80000000", 16),
    sizeBytes = 4 * 1024 * 1024,
    readWaitCycles = 0,
    useWordAddressing = true,
    enableLog = false
  )
  val iSram = new SimulatedSRAM(iSramConfig, initialContent = iDataWords)
  iSram.io.simPublic()
  iSram.io.ram.addr := dut.io.isram_addr
  iSram.io.ram.data.write := dut.io.isram_din
  dut.io.isram_dout := iSram.io.ram.data.read
  iSram.io.ram.ce_n := !dut.io.isram_en
  iSram.io.ram.oe_n := !dut.io.isram_re
  iSram.io.ram.we_n := !dut.io.isram_we
  // Convert active-high wmask from DUT to active-low be_n for SRAM
  iSram.io.ram.be_n := ~dut.io.isram_wmask

  // Configure and instantiate the Data SRAM (dSram)
  val dSramConfig = SRAMConfig(
    addressWidth = 20,
    dataWidth = 32,
    virtualBaseAddress = BigInt("80400000", 16),
    sizeBytes = 4 * 1024 * 1024,
    useWordAddressing = true,
    readWaitCycles = 0,
    enableLog = false
  )
  val dSram = new SimulatedSRAM(dSramConfig)
  dSram.io.simPublic()
  dSram.io.ram.addr := dut.io.dsram_addr
  dSram.io.ram.data.write := dut.io.dsram_din
  dut.io.dsram_dout := dSram.io.ram.data.read
  dSram.io.ram.ce_n := !dut.io.dsram_en
  dSram.io.ram.oe_n := !dut.io.dsram_re
  dSram.io.ram.we_n := !dut.io.dsram_we
  // Convert active-high wmask from DUT to active-low be_n for SRAM
  dSram.io.ram.be_n := ~dut.io.dsram_wmask

  // Tie off UART signals to prevent stalls
  dut.io.uart_ar_ready := True
  dut.io.uart_r_valid := False
  dut.io.uart_aw_ready := True
  dut.io.uart_w_ready := True
  dut.io.uart_b_valid := False
  dut.io.uart_r_bits_id.assignDontCare()
  dut.io.uart_r_bits_resp.assignDontCare()
  dut.io.uart_r_bits_data.assignDontCare()
  dut.io.uart_r_bits_last.assignDontCare()
  dut.io.uart_b_bits_id.assignDontCare()
  dut.io.uart_b_bits_resp.assignDontCare()
}


class LabSpec extends CustomSpinalSimFunSuite {

    // 2. Translate the assembly program into machine code
    val instructions = ArrayBuffer[BigInt]()
    instructions += addi_w(rd = 5, rj = 0, imm = 1)          // t0 ($r5) = 1
    instructions += addi_w(rd = 6, rj = 0, imm = 1)          // t1 ($r6) = 1
    instructions += lu12i_w(rd = 10, imm = 0x80400)          // a0 ($r10) = 0x80400000
    instructions += addi_w(rd = 11, rj = 10, imm = 0x100)    // a1 ($r11) = 0x80400100
    // loop: (PC = 0x10, relative to start)
    instructions += add_w(rd = 7, rj = 5, rk = 6)            // t2 ($r7) = t0 + t1
    instructions += addi_w(rd = 5, rj = 6, imm = 0)          // t0 = t1
    instructions += addi_w(rd = 6, rj = 7, imm = 0)          // t1 = t2
    instructions += st_w(rd = 7, rj = 10, offset = 0)        // mem[a0] = t2
    instructions += ld_w(rd = 8, rj = 10, offset = 0)        // t3 ($r8) = mem[a0]
    instructions += bne(rj = 7, rd = 8, offset = 12)         // if t2 != t3, goto end (jump over 3 instructions)
    instructions += addi_w(rd = 10, rj = 10, imm = 4)        // a0 += 4
    instructions += bne(rj = 10, rd = 11, offset = -28)      // if a0 != a1, goto loop (jump back 7 instructions to 0x10)
    // end: (PC = 0x30, relative to start)
    instructions += bne(rj = 0, rd = 0, offset = 0)          // infinite loop to halt
  
  test("Fibonacci Test on CoreNSCSCC") {
    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))

    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(600000)

      // 1. Get handles to the SRAM instances
      val iSram = dut.iSram
      val dSram = dut.dSram

      println("--- Starting Simulation ---")

      // 计算期望的总提交指令数
      val numInitInstructions = 4 // addi_w, addi_w, lu12i_w, addi_w
      val numLoopInstructions = 8 // add_w, addi_w, addi_w, st_w, ld_w, bne, addi_w, bne
      val numHaltInstructions = 1 // bne(0,0,0)
      val numIterations = 0x100

      // 期望的总提交指令数 = 初始化指令数 + 循环体指令数 * 循环次数 + 停止指令数
      // 注意：循环的最后一次迭代会执行 bne(rj=10, rd=11, offset=-28)，但它不会跳转，然后会执行 bne(rj=0, rd=0, offset=0)
      // 所以循环体是完整执行 64 次的。
      val expectedTotalCommitted = numInitInstructions + (numIterations * numLoopInstructions) + numHaltInstructions

      // 运行模拟直到提交数量达到预期
      var currentCommitted = 0
      while (currentCommitted < expectedTotalCommitted) {
        cd.waitSampling() // 等待一个时钟周期
        currentCommitted = dut.io.commitStats.totalCommitted.toBigInt.toInt // 读取当前的提交数量
        // 可选：打印进度
        if (currentCommitted % 50 == 0) {
          println(s"Committed: $currentCommitted / $expectedTotalCommitted")
        }
      }
      
      println(s"--- Simulation Finished: Committed $currentCommitted instructions ---")

      // 5. Verify the results stored in data memory (dSram)
      println("--- Verification Phase ---")
      var fib_prev = BigInt(1)
      var fib_curr = BigInt(1)
      val num_iterations_verify = (0x80400100 - 0x80400000) / 4 // 64 iterations
      val MASK_32_BITS = BigInt("FFFFFFFF", 16) 
      val SIGN_BIT_32 = BigInt("80000000", 16) // 32位符号位

      for (i <- 0 until num_iterations_verify) {
        val byteAddr = i * 4
        val next_fib_untruncated = fib_prev + fib_curr
        val expected_fib = next_fib_untruncated & MASK_32_BITS 

        // Read from dSram using testbench ports
        dSram.io.tb_readEnable #= true
        dSram.io.tb_readAddress #= byteAddr
        cd.waitSampling() // Wait one cycle for the read to propagate
        val actual_val = dSram.io.tb_readData.toBigInt
        
        println(f"Checking dSram[0x$byteAddr%x]: Expected=0x$expected_fib%x, Got=0x$actual_val%x")
        assert(actual_val == expected_fib, f"Fibonacci sequence mismatch at index $i! Expected 0x$expected_fib%x, but got 0x$actual_val%x")

        // Update for next iteration
        fib_prev = fib_curr
        fib_curr = expected_fib
      }
      dSram.io.tb_readEnable #= false
      
      println("--- LabSpec Test Passed ---")
    }
  }

  thatsAll()
}

