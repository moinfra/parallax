package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import parallax.CoreNSCSCC
import parallax.components.memory.{SRAMConfig, SimulatedSRAM}
import parallax.issue.{CommitStats, CommitService}
import scala.collection.mutable.ArrayBuffer
import _root_.test.scala.LA32RInstrBuilder._
import java.io.BufferedOutputStream
import java.io.FileOutputStream

/** Testbench for LabSpec.
  * This component wraps the CoreNSCSCC DUT and connects it to two SimulatedSRAM instances,
  * one for instruction memory (iSram) and one for data memory (dSram).
  */
class LabTestBench(val iDataWords: Seq[BigInt], val maxCommitPc: BigInt = 0, val enablePcCheck: Boolean = false) extends Component {
  val io = new Bundle {
    val commitStats = out(CommitStats())
  }

  // Instantiate the DUT
  val dut = new CoreNSCSCC(simDebug = true)
  io.commitStats := dut.io.commitStats
  io.commitStats.simPublic()

  // Configure PC bounds checking if enabled
  // if (enablePcCheck) {
  //   val commitService = dut.framework.getService[CommitService]
  //   commitService.setMaxCommitPc(U(maxCommitPc, 32 bits), True)
  // }

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

  def ramdump(
      sram: SimulatedSRAM,
      vaddr: BigInt,
      size: Int,
      filename: String
  )(implicit cd: ClockDomain): Unit = {
    println(f"--- Starting RAM dump: vaddr=0x$vaddr%x, size=$size bytes, file='$filename' ---")

    // 1. Check address validity
    assert(
      vaddr >= sram.config.virtualBaseAddress,
      "Dump start address is below SRAM base address."
    )
    assert(
      (vaddr + size) <= (sram.config.virtualBaseAddress + sram.config.sizeBytes),
      "Dump region extends beyond SRAM size."
    )

    // 2. Read data from SRAM via testbench interface
    val byteBuffer = new ArrayBuffer[Byte]()
    val baseOffset = vaddr - sram.config.virtualBaseAddress
    for (i <- 0 until size by 4) {
      val addrOffset = baseOffset + i
      sram.io.tb_readEnable #= true
      sram.io.tb_readAddress #= addrOffset.toLong
      cd.waitSampling() // Wait one cycle for the read to complete
      val word = sram.io.tb_readData.toBigInt

      // Add bytes in little-endian order to the buffer
      byteBuffer += ((word >> 0) & 0xff).toByte
      byteBuffer += ((word >> 8) & 0xff).toByte
      byteBuffer += ((word >> 16) & 0xff).toByte
      byteBuffer += ((word >> 24) & 0xff).toByte
    }
    sram.io.tb_readEnable #= false

    val data = byteBuffer.take(size).toArray // Ensure we only have `size` bytes

    // 3. Dump raw bytes to file
    val fos = new BufferedOutputStream(new FileOutputStream(filename))
    try {
      fos.write(data)
      println(s"Wrote $size bytes to $filename")
    } catch {
      case e: Exception => e.printStackTrace()
    } finally {
      fos.close()
    }

    // 4. Print formatted hexdump to console
    println("--- Hexdump ---")
    for (i <- data.indices by 16) {
      val chunk = data.slice(i, i + 16)

      // Address part
      val addressPart = f"${i}%08x:"

      // Hex part
      val hexPart = chunk
        .map(b => f"${b & 0xff}%02x")
        .padTo(16, "  ") // Pad if last line is short
        .mkString(" ")

      // ASCII part
      val asciiPart = chunk.map { b =>
        val char = b.toChar
        if (char.isControl || char > 126) '.' else char
      }.mkString

      println(s"$addressPart $hexPart |$asciiPart|")
    }
    println("--- RAM dump finished ---")
  }

}

class LabSpec extends CustomSpinalSimFunSuite {
  test("Sequential Memory Write Test") {
    val instructions = Seq(
      // --- 初始化数据寄存器 (R12 = 0x10000000) ---
      lu12i_w(rd = 12, imm = 0x10000000 >>> 12),
      ori(rd = 12, rj = 12, imm = 0x10000000 & 0xfff),

      // --- 初始化地址寄存器 (R13 = 0x80400000) ---
      lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
      ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff),

      // --- 循环写入10次 ---

      // Iteration 1: Write 0x10000000 to 0x80400000
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1), // Increment data: r12 = r12 + 1
      addi_w(rd = 13, rj = 13, imm = 4), // Increment address: r13 = r13 + 4

      // Iteration 2: Write 0x10000001 to 0x80400004
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 3: Write 0x10000002 to 0x80400008
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 4: Write 0x10000003 to 0x8040000C
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 5: Write 0x10000004 to 0x80400010
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 6: Write 0x10000005 to 0x80400014
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 7: Write 0x10000006 to 0x80400018
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 8: Write 0x10000007 to 0x8040001C
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 9: Write 0x10000008 to 0x80400020
      st_w(rd = 12, rj = 13, offset = 0),
      addi_w(rd = 12, rj = 12, imm = 1),
      addi_w(rd = 13, rj = 13, imm = 4),

      // Iteration 10: Write 0x10000009 to 0x80400024
      st_w(rd = 12, rj = 13, offset = 0),
      // addi_w(rd = 12, rj = 12, imm = 1), // End of loop, no need to increment
      // addi_w(rd = 13, rj = 13, imm = 4), // End of loop, no need to increment

      // --- 停机 ---
      bne(rj = 0, rd = 0, offset = 0)
    )
    LabHelper.dumpBinary(instructions, "bin/why_stopped.bin")
  }

  testOnly("mem write test") {
    val instructions = Seq(
      // Load 0xdeadbeef into $t0 (R12)
      lu12i_w(rd = 12, imm = 0xdeadbeef >>> 12),
      ori(rd = 12, rj = 12, imm = 0xdeadbeef & 0xfff),

      // Load 0x80400000 into $t1 (R13)
      lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
      ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff),

      // Store $t0 (R12) to memory at address in $t1 (R13)
      st_w(rd = 12, rj = 13, offset = 0),

      // Infinite loop to halt
      bne(rj = 12, rd = 0, offset = 0) // bne $t0, $zero, 0 -> infinite loop since $t0 is not zero
    )

    LabHelper.dumpBinary(instructions, "bin/write_test.bin")

    // Configure PC bounds checking
    val maxExpectedPc = BigInt("80000000", 16) + (instructions.length - 1) * 4
    val compiled = SimConfig.withFstWave.compile(new LabTestBench(
      iDataWords = instructions,
      maxCommitPc = maxExpectedPc,
      enablePcCheck = true
    ))

    compiled.doSim { dut =>
      val cd = dut.clockDomain.get
      cd.forkStimulus(period = 10)
      
      println(s"--- Starting 'mem write test' with PC bounds check (maxPc=0x${maxExpectedPc.toString(16)}) ---")

      // Run for extended time to detect OOB issues
      val maxCycles = 10000
      val minCommitsNeeded = 5 // 4 loads + 1 store
      var minCommitsReached = false
      
      for (cycle <- 1 to maxCycles) {
        cd.waitSampling()
        val commitStats = dut.io.commitStats
        
        // Check for CPU runaway (OOB)
        if (commitStats.commitOOB.toBoolean) {
          val maxCommitPc = commitStats.maxCommitPc.toBigInt
          assert(false, s"CPU runaway detected! PC=0x${maxCommitPc.toString(16)}, max=0x${maxExpectedPc.toString(16)}")
        }
        
        // Track minimum commits reached
        if (commitStats.totalCommitted.toBigInt >= minCommitsNeeded) {
          minCommitsReached = true
        }
        
        // Progress logging
        if (cycle % 1000 == 0) {
          println(s"Cycle $cycle: Committed ${commitStats.totalCommitted.toBigInt}, maxPC=0x${commitStats.maxCommitPc.toBigInt.toString(16)}")
        }
      }

      println(s"--- Simulation completed after $maxCycles cycles ---")

      // Verify memory content
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= 0
      cd.waitSampling()
      val actualValue = dut.dSram.io.tb_readData.toBigInt
      dut.dSram.io.tb_readEnable #= false
      
      val expectedValue = BigInt("deadbeef", 16)
      
      // Test passes if: minimum commits reached AND memory is correct
      assert(minCommitsReached, s"Insufficient commits: need at least $minCommitsNeeded")
      assert(actualValue == expectedValue, f"Memory mismatch: expected 0x$expectedValue%x, got 0x$actualValue%x")
      
      println("--- 'mem write test' Passed ---")
      
      // Dump memory for inspection
      LabHelper.ramdump(
        sram = dut.dSram,
        vaddr = BigInt("80400000", 16),
        size = 64,
        filename = "dsram_dump_mem_write_test.bin"
      )(cd)
    }
  }

  test("Isolate Data Register r14") {
    val instructions = Seq(
      // 使用 r14 (来自失败的测试) 作为数据寄存器
      lu12i_w(rd = 14, imm = 0x12340000 >>> 12),
      ori(rd = 14, rj = 14, imm = 0x12340000 & 0xfff), // Data = 0x12340000

      // 使用 r13 (来自成功的测试) 作为地址寄存器
      lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
      ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff), // Address = 0x80400000

      // 存储指令 st.w $t2, 0($t1)  (st.w r14, 0(r13))
      st_w(rd = 14, rj = 13, offset = 0),

      // 停机
      bne(rj = 0, rd = 0, offset = 0)
    )
    LabHelper.dumpBinary(instructions, "bin/isolate_r14_test.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(10000)

      cd.waitSampling(600)

      println("--- Verification for 'Isolate Data Register r14' ---")
      val verificationAddress = 0
      val expectedValue = BigInt("12340000", 16)

      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= verificationAddress
      cd.waitSampling()
      val actual_val = dut.dSram.io.tb_readData.toBigInt
      dut.dSram.io.tb_readEnable #= false

      println(f"Checking dSram: Expected=0x$expectedValue%x, Got=0x$actual_val%x")
      assert(actual_val == expectedValue, "Test for r14 FAILED!")
      println("--- Test for r14 PASSED ---")
    }
  }
  test("Isolate Address Register r4") {
    val instructions = Seq(
      // 使用 r12 (来自成功的测试) 作为数据寄存器
      lu12i_w(rd = 12, imm = 0xbeef0000 >>> 12),
      ori(rd = 12, rj = 12, imm = 0xbeef0000 & 0xfff), // Data = 0xbeef0000

      // 使用 r4 (来自失败的测试) 作为地址寄存器
      lu12i_w(rd = 4, imm = 0x80400000 >>> 12),
      ori(rd = 4, rj = 4, imm = 0x80400000 & 0xfff), // Address = 0x80400000

      // 存储指令 st.w $t0, 0($a0)  (st.w r12, 0(r4))
      st_w(rd = 12, rj = 4, offset = 0),

      // 停机
      bne(rj = 0, rd = 0, offset = 0)
    )

    LabHelper.dumpBinary(instructions, "bin/isolate_r4_test.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      SimTimeout(10000)

      // 等待足够长的时间让指令执行完
      cd.waitSampling(600)

      println("--- Verification for 'Isolate Address Register r4' ---")
      val verificationAddress = 0
      val expectedValue = BigInt("beef0000", 16)

      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= verificationAddress
      cd.waitSampling()
      val actual_val = dut.dSram.io.tb_readData.toBigInt
      dut.dSram.io.tb_readEnable #= false

      println(f"Checking dSram: Expected=0x$expectedValue%x, Got=0x$actual_val%x")
      assert(actual_val == expectedValue, "Test for r4 FAILED!")
      println("--- Test for r4 PASSED ---")
    }
  }

  /** 这是一个极简的内存写入测试，旨在调试为何斐波那契测试无法写入内存。
    * 它只做三件事：
    * 1. 将数据 0x12345678 加载到 $t2 (r14)
    * 2. 将地址 0x80400000 加载到 $a0 (r4)
    * 3. 将 $t2 的内容存储到 $a0 指向的内存地址
    * 4. 停机
    * 它使用了和斐波那契测试相同的寄存器，以进行精确的问题定位。
    */
  test("Minimal Store Test (using fib registers)") {
    val instructions = Seq(
      // 1. 将数据 0x12345678 加载到 $t2 (R14)
      lu12i_w(rd = 14, imm = 0x12345678 >>> 12),
      ori(rd = 14, rj = 14, imm = 0x12345678 & 0xfff),

      // 2. 将地址 0x80400000 加载到 $a0 (R4)
      lu12i_w(rd = 4, imm = 0x80400000 >>> 12),
      ori(rd = 4, rj = 4, imm = 0x80400000 & 0xfff),

      // 3. 执行存储指令: st.w $t2, 0($a0)
      st_w(rd = 14, rj = 4, offset = 0),

      // 4. 无限循环停机
      bne(rj = 0, rd = 0, offset = 0) // bne $zero, $zero, . -> 无条件跳转到自身
    )

    LabHelper.dumpBinary(instructions, "bin/minimal_store_test.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))

    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(10000) // 这个测试非常快，10000个周期足够了

      val dSram = dut.dSram

      println("--- Starting 'Minimal Store Test' ---")

      // 总共只有 5 条指令会提交（2个lu12i, 2个ori, 1个st_w），然后进入无限循环
      // 我们等待至少 6 条指令提交的时间，确保 st_w 已经完成
      var currentCommitted = 0
      while (currentCommitted < 6) {
        cd.waitSampling()
        currentCommitted = dut.io.commitStats.totalCommitted.toBigInt.toInt
      }
      cd.waitSampling(100) // 额外等待，确保流水线稳定

      println(s"--- Simulation Finished: Committed $currentCommitted instructions ---")

      // --- 验证阶段 ---
      println("--- Verification Phase ---")
      val verificationAddress = 0 // 物理地址偏移
      val expectedValue = BigInt("12345678", 16)

      // 使用 testbench 接口直接读取 dSram
      dSram.io.tb_readEnable #= true
      dSram.io.tb_readAddress #= verificationAddress
      cd.waitSampling() // 等待一个周期让读操作完成
      val actual_val = dSram.io.tb_readData.toBigInt
      dSram.io.tb_readEnable #= false

      println(f"Checking dSram[0x$verificationAddress%x]: Expected=0x$expectedValue%x, Got=0x$actual_val%x")
      assert(
        actual_val == expectedValue,
        f"Minimal store test FAILED! Expected 0x$expectedValue%x, but got 0x$actual_val%x"
      )

      println("--- 'Minimal Store Test' Passed ---")
    }
  }

  test("Fibonacci Test on CoreNSCSCC") {

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
