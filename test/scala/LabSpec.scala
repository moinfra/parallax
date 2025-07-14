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

/** Testbench for LabSpec.
  * This component wraps the CoreNSCSCC DUT and connects it to two SimulatedSRAM instances,
  * one for instruction memory (iSram) and one for data memory (dSram).
  */
class LabTestBench(val iDataWords: Seq[BigInt]) extends Component {
  val io = new Bundle {
    val commitStats = out(CommitStats())
  }

  // Instantiate the DUT
  val dut = new CoreNSCSCC(simDebug = true)
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

object LabHelper {

  def dumpBinary(data: Seq[BigInt], filename: String): Unit = {
    import java.io.{FileOutputStream, BufferedOutputStream}
    val fos = new BufferedOutputStream(new FileOutputStream(filename))
    try {
      data.foreach { word =>
        val bytes = Array(
          ((word >> 0) & 0xff).toByte,
          ((word >> 8) & 0xff).toByte,
          ((word >> 16) & 0xff).toByte,
          ((word >> 24) & 0xff).toByte
        )
        fos.write(bytes)
      }
      println(s"Wrote ${data.length} words to $filename")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      fos.close()
    }
  }
}

class LabSpec extends CustomSpinalSimFunSuite {

  val instructions = ArrayBuffer[BigInt]()
  // Original Assembly:
  // addi.w      $t0,$zero,0x1   # t0 = 1
  // addi.w      $t1,$zero,0x1   # t1 = 1
  // lu12i.w     $a0,-0x7fc00    # a0 = 0x80400000
  // addi.w      $a1,$a0,0x100   # a1 = 0x80400100
  // loop:
  // add.w       $t2,$t0,$t1     # t2 = t0+t1
  // addi.w      $t0,$t1,0x0     # t0 = t1
  // addi.w      $t1,$t2,0x0     # t1 = t2
  // st.w        $t2,$a0,0x0
  // ld.w        $t3,$a0,0x0
  // bne         $t2,$t3,end
  // addi.w      $a0,$a0,0x4     # a0 += 4
  // bne         $a0,$a1,loop
  // end:
  // bne         $a0,$zero,end

  // Mapped to rX registers based on ABI:
  // $zero = r0
  // $t0 = r12, $t1 = r13, $t2 = r14, $t3 = r15
  // $a0 = r4, $a1 = r5

  instructions += addi_w(rd = 12, rj = 0, imm = 1) // $t0 ($r12) = 1
  instructions += addi_w(rd = 13, rj = 0, imm = 1) // $t1 ($r13) = 1
  instructions += lu12i_w(rd = 4, imm = 0x80400) // $a0 ($r4) = 0x80400000 (imm -0x7fc00 for lu12i.w)
  instructions += addi_w(rd = 5, rj = 4, imm = 0x100) // $a1 ($r5) = $a0 + 0x100 (0x80400100)

  // loop: (PC = 0x10, relative to start)
  instructions += add_w(rd = 14, rj = 12, rk = 13) // $t2 ($r14) = $t0 + $t1
  instructions += addi_w(rd = 12, rj = 13, imm = 0) // $t0 = $t1
  instructions += addi_w(rd = 13, rj = 14, imm = 0) // $t1 = $t2
  instructions += st_w(rd = 14, rj = 4, offset = 0) // mem[$a0] = $t2
  instructions += ld_w(rd = 15, rj = 4, offset = 0) // $t3 ($r15) = mem[$a0]

  // bne $t2,$t3,end
  // Current PC for this instruction is 0x20 (relative to start)
  // 'end' label is at 0x30 (relative to start)
  // Offset = 0x30 - 0x20 = 0x10 bytes = 4 words
  instructions += bne(rj = 14, rd = 15, offset = 12) // if $t2 != $t3, goto end

  instructions += addi_w(rd = 4, rj = 4, imm = 4) // $a0 += 4

  // bne $a0,$a1,loop
  // Current PC for this instruction is 0x2C (relative to start)
  // 'loop' label is at 0x10 (relative to start)
  // Offset = 0x10 - 0x2C = -0x1C bytes = -7 words
  instructions += bne(rj = 4, rd = 5, offset = -28) // if $a0 != $a1, goto loop

  // end: (PC = 0x30, relative to start)
  // bne $a0,$zero,end
  // Current PC for this instruction is 0x30 (relative to start)
  // 'end' label is at 0x30 (relative to start)
  // Offset = 0x30 - 0x30 = 0 bytes = 0 words
  instructions += bne(rj = 4, rd = 0, offset = 0) // infinite loop to halt

  LabHelper.dumpBinary(instructions, "bin/LabSpec.bin")

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
      // val expectedTotalCommitted = numInitInstructions + (numIterations * numLoopInstructions) + numHaltInstructions
      val expectedTotalCommitted = 10

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
        assert(
          actual_val == expected_fib,
          f"Fibonacci sequence mismatch at index $i! Expected 0x$expected_fib%x, but got 0x$actual_val%x"
        )

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
