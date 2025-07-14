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
import java.io.BufferedOutputStream
import java.io.FileOutputStream

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

  testSkip("mem write test") {
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

    LabHelper.dumpBinary(instructions, "write_test.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))

    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(10000) // Set a timeout

      val dSram = dut.dSram

      println("--- Starting 'mem write test' Simulation ---")

      // The core needs to execute 5 instructions to perform the store,
      // then it will hit the infinite loop.
      // Let's wait until at least 6 instructions are committed to be safe.
      var currentCommitted = 0
      while (currentCommitted < 6) {
        cd.waitSampling()
        currentCommitted = dut.io.commitStats.totalCommitted.toBigInt.toInt
      }
      cd.waitSampling(100)
      println(s"--- Simulation Finished: Committed $currentCommitted instructions ---")

      // --- Verification Phase ---
      println("--- Verification Phase ---")
      val verificationAddress = 0
      val expectedValue = BigInt("deadbeef", 16)

      // Read from dSram using testbench ports
      dSram.io.tb_readEnable #= true
      dSram.io.tb_readAddress #= verificationAddress
      cd.waitSampling() // Wait one cycle for the read to propagate
      val actual_val = dSram.io.tb_readData.toBigInt
      dSram.io.tb_readEnable #= false


      println(f"Checking dSram[0x$verificationAddress%x]: Expected=0x$expectedValue%x, Got=0x$actual_val%x")
      assert(
        actual_val == expectedValue,
        f"Memory write verification failed! Expected 0x$expectedValue%x, but got 0x$actual_val%x"
      )

      // Dump memory for inspection
      LabHelper.ramdump(
        sram = dSram,
        vaddr = BigInt("80400000", 16),
        size = 64, // Dump 64 bytes
        filename = "dsram_dump_mem_write_test.bin"
      )(cd.get)

      println("--- 'mem write test' Passed ---")
    }
  }
testOnly("Isolate Data Register r14") {
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
    LabHelper.dumpBinary(instructions, "isolate_r14_test.bin")

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

    LabHelper.dumpBinary(instructions, "isolate_r4_test.bin")

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

  /**
   * 这是一个极简的内存写入测试，旨在调试为何斐波那契测试无法写入内存。
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

    LabHelper.dumpBinary(instructions, "minimal_store_test.bin")

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

        instructions += addi_w(rd = 12, rj = 0, imm = 1)     // $t0 ($r12) = 1
    instructions += addi_w(rd = 13, rj = 0, imm = 1)     // $t1 ($r13) = 1
    instructions += lu12i_w(rd = 4, imm = 0x80400)       // $a0 ($r4) = 0x80400000

    // *** 修改点 1: 改变循环结束条件 ***
    // 原来是 addi_w(..., imm = 0x100)，让循环跑 64 次
    // 现在改为 imm = 4，这样 $a1 = 0x80400004。
    // 在第一次循环后，$a0 会被增加到 0x80400004，与 $a1 相等，从而退出循环。
    instructions += addi_w(rd = 5, rj = 4, imm = 4)      // $a1 ($r5) = $a0 + 4 (0x80400004)

    // loop: (PC = 0x10)
    instructions += add_w(rd = 14, rj = 12, rk = 13)    // $t2 = $t0 + $t1 (1 + 1 = 2)
    instructions += addi_w(rd = 12, rj = 13, imm = 0)    // $t0 = $t1
    instructions += addi_w(rd = 13, rj = 14, imm = 0)    // $t1 = $t2
    instructions += st_w(rd = 14, rj = 4, offset = 0)   // mem[$a0] = $t2 (将 2 写入 0x80400000)
    instructions += ld_w(rd = 15, rj = 4, offset = 0)   // $t3 = mem[$a0] (读回 2)
    instructions += bne(rj = 14, rd = 15, offset = 12)  // if $t2 != $t3, goto end
    instructions += addi_w(rd = 4, rj = 4, imm = 4)     // $a0 += 4 (a0 = 0x80400004)
    instructions += bne(rj = 4, rd = 5, offset = -28)   // if $a0 != $a1, goto loop (此时 $a0 == $a1, 不会跳转)

    // end: (PC = 0x30)
    instructions += bne(rj = 4, rd = 0, offset = 0)     // infinite loop to halt

    LabHelper.dumpBinary(instructions, "bin/labspec1.bin")
    fail()

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
      // val expectedTotalCommitted = 10

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

      LabHelper.ramdump(
        sram = dSram,
        vaddr = BigInt("80400000", 16),
        size = 256, // Dump 256 bytes (0x100)
        filename = "dsram_dump.bin"
      )(cd.get)

      println("--- LabSpec Test Passed ---")
    }
  }

  thatsAll()
}
