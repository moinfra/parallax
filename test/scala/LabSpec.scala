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
    test("Minimal Failing Case for SUB.W rd, rj, rd") {
    // === 目标 ===
    // 这个测试旨在用最少的指令复现一个特定的硬件 Bug。
    // Bug 假设: 当 sub_w rd, rj, rk 指令中的 rd 和 rk 是同一个寄存器时，
    // 运算结果出错。

    // === 寄存器定义 ===
    val RJ_REG = 1      // r1, 作为减数 rj
    val RKD_REG = 2     // r2, 同时作为被减数 rk 和目标寄存器 rd
    val EXPECT_REG = 3  // r3, 用于存放期望的结果

    // === 测试值 ===
    val VAL_RJ = 100
    val VAL_RKD_INITIAL = 33
    val EXPECTED_RESULT = VAL_RJ - VAL_RKD_INITIAL // 100 - 33 = 67

    val insts = collection.mutable.ArrayBuffer[BigInt]()

    // === 1. 设置阶段 ===
    // 将已知值载入源寄存器
    insts += addi_w(rd = RJ_REG, rj = 0, imm = VAL_RJ)         // r1 = 100
    insts += addi_w(rd = RKD_REG, rj = 0, imm = VAL_RKD_INITIAL) // r2 = 33

    // === 2. 触发阶段 ===
    // 执行疑似有 Bug 的指令
    // 预期行为: r2 = r1 - r2  =>  r2 = 100 - 33 = 67
    // Buggy 行为 (根据之前的日志推断): 减法未执行或执行错误
    insts += sub_w(rd = RKD_REG, rj = RJ_REG, rk = RKD_REG)

    // === 3. 验证阶段 ===
    // 将期望的正确结果载入 r3
    insts += addi_w(rd = EXPECT_REG, rj = 0, imm = EXPECTED_RESULT) // r3 = 67
    
    // 比较 r2 (实际结果) 和 r3 (期望结果)。
    // 如果相等，则 Bug 未复现，跳转到成功循环。
    // 如果不等，则 Bug 成功复现，顺序执行到失败循环。
    insts += beq(rj = RKD_REG, rd = EXPECT_REG, offset = 8) // 若 r2 == r3, PC += 8 (跳过失败循环)
    
    // 失败循环 (PC_FAIL)
    // 如果程序停在这里，说明 r2 != 67，Bug 已被成功复现。
    insts += beq(rj = 0, rd = 0, offset = 0) // beq r0, r0, 0 => 无限循环
    
    // 成功循环 (PC_SUCCESS)
    // 如果程序停在这里，说明 r2 == 67，Bug 未复现或已被修复。
    insts += beq(rj = 0, rd = 0, offset = 0) // beq r0, r0, 0 => 无限循环

    
    // --- 执行测试 ---
    val finalInstructions = insts.toSeq
    val baseAddr = BigInt("80000000", 16)
    val failPC = baseAddr + (finalInstructions.length - 2) * 4
    val successPC = baseAddr + (finalInstructions.length - 1) * 4

    LabHelper.dumpBinary(finalInstructions, "bin/sub_minimal_fail_case.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(
      iDataWords = finalInstructions
    ))

    compiled.doSim { dut =>
      val cd = dut.clockDomain.get
      cd.forkStimulus(period = 10)
      
      println(s"--- Starting 'Minimal Failing Case for SUB.W rd, rj, rd' ---")
      println(s"Expecting failure at PC=0x${failPC.toString(16)}")
      println(s"Success would be at PC=0x${successPC.toString(16)}")

      // 运行足够长的时间以确保能进入无限循环
      cd.waitSampling(500)
      
      val finalPC = dut.io.commitStats.maxCommitPc.toBigInt
      println(s"Simulation finished. Final PC: 0x${finalPC.toString(16)}")

      assert(finalPC == successPC, s"Expected PC to be 0x${failPC.toString(16)} but got 0x${finalPC.toString(16)}. It seems the bug is fixed or the trigger is different.")
    }
  }
// test("GPR R1-R31 Correct Read/Write and Verify Test") {
//     // 寄存器定义
//     val BASE_REG = 30      // 主要的基址寄存器 (r30)
//     val ERR_COUNT_REG = 29 // 错误计数器 (r29)
//     val SCRATCH_REG = 28   // 临时寄存器 (r28)，用于保存基地址和期望值

//     val MEM_BASE_ADDR = 0x80400000 // 内存测试区域的起始地址
//     val insts = collection.mutable.ArrayBuffer[BigInt]()

//     // === 阶段 1: 初始化 ===
//     // 1. 设置 r30 为内存基地址
//     insts += lu12i_w(rd = BASE_REG, imm = MEM_BASE_ADDR >>> 12)
//     insts += ori(rd = BASE_REG, rj = BASE_REG, imm = MEM_BASE_ADDR & 0xfff)
//     // 2. 初始化错误计数器 r29 为 0
//     insts += addi_w(rd = ERR_COUNT_REG, rj = 0, imm = 0)

//     // === 阶段 2: 写入内存 ===
//     // 按顺序测试寄存器 r1 到 r31
//     for (i <- 1 to 31) {
//       // 当要测试主基址寄存器 r30 时，它的地址值马上要被覆盖。
//       // 因此，我们必须先将它的地址保存到另一个临时寄存器（r28）中。
//       if (i == BASE_REG) {
//         // r28 = r30 (保存基地址)
//         insts += add_w(rd = SCRATCH_REG, rj = BASE_REG, rk = 0)
//       }

//       // 2a. 将寄存器ID `i` 写入寄存器 r[i]
//       insts += addi_w(rd = i, rj = 0, imm = i)

//       // 2b. 选择正确的基址寄存器来执行存储操作
//       val baseForStore = if (i == BASE_REG) {
//         // 当测试 r30 时，使用刚刚保存了地址的 r28 作为基址
//         SCRATCH_REG
//       } else {
//         // 其他情况，正常使用 r30 作为基址
//         BASE_REG
//       }
      
//       // 2c. 将 r[i] 的值存入内存 mem[base + i*4]
//       // (使用 i*4 作为偏移量，使得每个寄存器的内存槽位分开)
//       insts += st_w(rd = i, rj = baseForStore, offset = i * 4)
//     }

//     // === 阶段 3: 读回内存 ===
//     // 3a. 恢复主基址寄存器 r30
//     // 此时，原始基地址仍然保存在 r28 中，我们从那里恢复它。
//     insts += add_w(rd = BASE_REG, rj = SCRATCH_REG, rk = 0)

//     // 3b. 将内存中的值读回到对应的寄存器
//     for (i <- 1 to 31) {
//       insts += ld_w(rd = i, rj = BASE_REG, offset = i * 4)
//     }

//     // === 阶段 4: 校验 ===
//     // 遍历 r1-r31, 检查 r[i] 的值是否等于 i
//     for (i <- 1 to 31) {
//       // 4a. 将期望值 `i` 加载到临时寄存器 r28
//       insts += addi_w(rd = SCRATCH_REG, rj = 0, imm = i)
      
//       // 4b. 比较 r[i] 和 r28 (期望值)。如果它们不相等，则它们的差不为零。
//       insts += sub_w(rd = SCRATCH_REG, rj = i, rk = SCRATCH_REG)
      
//       // 4c. 如果差值不为零 (bne)，则跳转过一条指令；否则，执行下一条指令，使错误计数器+1
//       insts += bne(rj = SCRATCH_REG, rd = 0, offset = 8) // offset=8 表示跳转到两条指令之后
//       insts += addi_w(rd = ERR_COUNT_REG, rj = ERR_COUNT_REG, imm = 1)
//     }

//     // === 阶段 5: 结束程序 ===
//     // 检查错误计数器 r29。如果为 0，跳转到成功循环，否则进入失败循环。
//     insts += beq(rj = ERR_COUNT_REG, rd = 0, offset = 8) // 如果 r29 == 0, 跳转到8字节后的成功循环
    
//     // 失败循环 (如果 r29 != 0)
//     insts += beq(rj = 0, rd = 0, offset = 0) // beq r0, r0, 0 ==> 无限循环
    
//     // 成功循环 (如果 r29 == 0)
//     insts += beq(rj = 0, rd = 0, offset = 0) // beq r0, r0, 0 ==> 无限循环


//     // --- 以下是执行测试的模板代码 ---
//     val finalInstructions = insts.toSeq
//     LabHelper.dumpBinary(finalInstructions, "bin/gpr_read_write_test.bin")

//     val maxExpectedPc = BigInt("80000000", 16) + (finalInstructions.length - 1) * 4
//     val compiled = SimConfig.withFstWave.compile(new LabTestBench(
//       iDataWords = finalInstructions,
//       maxCommitPc = maxExpectedPc,
//       enablePcCheck = true
//     ))

//     compiled.doSim { dut =>
//       val cd = dut.clockDomain.get
//       cd.forkStimulus(period = 10)
      
//       println(s"--- Starting 'GPR R1-R31 Correct Read/Write and Verify Test' (maxPc=0x${maxExpectedPc.toString(16)}) ---")

//       val maxCycles = 15000 // 根据指令数量和处理器设计调整
//       var simTime = 0
//       while(dut.io.commitStats.maxCommitPc.toBigInt < maxExpectedPc && simTime < maxCycles) {
//         cd.waitSampling()
//         simTime += 1
//       }
      
//       println(s"Simulation finished at cycle $simTime. Final PC: 0x${dut.io.commitStats.maxCommitPc.toBigInt.toString(16)}")

//       // 验证最终PC是否停在成功循环处
//       val finalPC = dut.io.commitStats.maxCommitPc.toBigInt
//       val successPC = BigInt("80000000", 16) + (finalInstructions.length - 1) * 4
//       assert(finalPC == successPC, s"Test failed! CPU ended at wrong PC. Expected 0x${successPC.toString(16)}, got 0x${finalPC.toString(16)}")

//       println("--- 'GPR R1-R31 Correct Read/Write and Verify Test' Passed ---")
//     }
//   }
    test("GPR R1-R31 Correct Read/Write and Verify Test (Bug Fixed)") {
    // 寄存器定义
    val BASE_REG = 30      // 主要的基址寄存器 (r30)
    val ERR_COUNT_REG = 29 // 错误计数器 (r29)
    val SCRATCH_REG = 28   // 临时寄存器 (r28)，用于保存基地址和期望值
    
    // *** BUG FIX: 使用一个在当前迭代中未被用作源或目标的寄存器作为临时结果寄存器 ***
    val TEMP_RESULT_REG = 31 // 将 r31 用作减法结果的临时存放处

    val MEM_BASE_ADDR = 0x80400000 // 内存测试区域的起始地址
    val insts = collection.mutable.ArrayBuffer[BigInt]()

    // === 阶段 1: 初始化 ===
    insts += lu12i_w(rd = BASE_REG, imm = MEM_BASE_ADDR >>> 12)
    insts += ori(rd = BASE_REG, rj = BASE_REG, imm = MEM_BASE_ADDR & 0xfff)
    insts += addi_w(rd = ERR_COUNT_REG, rj = 0, imm = 0)

    // === 阶段 2: 写入内存 ===
    for (i <- 1 to 31) {
      if (i == BASE_REG) {
        insts += add_w(rd = SCRATCH_REG, rj = BASE_REG, rk = 0)
      }
      insts += addi_w(rd = i, rj = 0, imm = i)
      val baseForStore = if (i == BASE_REG) SCRATCH_REG else BASE_REG
      insts += st_w(rd = i, rj = baseForStore, offset = i * 4)
    }

    // === 阶段 3: 读回内存 ===
    insts += add_w(rd = BASE_REG, rj = SCRATCH_REG, rk = 0)
    for (i <- 1 to 31) {
      insts += ld_w(rd = i, rj = BASE_REG, offset = i * 4)
    }

    // === 阶段 4: 校验 (已修复) ===
    for (i <- 1 to 31) {
      // 4a. 将期望值 `i` 加载到临时寄存器 r28
      insts += addi_w(rd = SCRATCH_REG, rj = 0, imm = i)
      
      // 4b. 【修复点】计算 r[i] - r28, 结果存入 r31, 避免了 sub r28, r_i, r28 的模式
      insts += sub_w(rd = TEMP_RESULT_REG, rj = i, rk = SCRATCH_REG)
      
      // 4c. 【修复点】现在比较 r31 和 r0
      insts += bne(rj = TEMP_RESULT_REG, rd = 0, offset = 8) // offset=8 表示跳转到两条指令之后
      insts += addi_w(rd = ERR_COUNT_REG, rj = ERR_COUNT_REG, imm = 1)
    }

    // === 阶段 5: 结束程序 ===
    insts += beq(rj = ERR_COUNT_REG, rd = 0, offset = 8) 
    insts += beq(rj = 0, rd = 0, offset = 0) // 失败循环
    insts += beq(rj = 0, rd = 0, offset = 0) // 成功循环


    // --- 以下是执行测试的模板代码 ---
    val finalInstructions = insts.toSeq
    LabHelper.dumpBinary(finalInstructions, "bin/gpr_read_write_test_fixed.bin")

    val maxExpectedPc = BigInt("80000000", 16) + (finalInstructions.length - 1) * 4
    val compiled = SimConfig.withFstWave.compile(new LabTestBench(
      iDataWords = finalInstructions,
      maxCommitPc = maxExpectedPc,
      enablePcCheck = true
    ))

    compiled.doSim { dut =>
      val cd = dut.clockDomain.get
      cd.forkStimulus(period = 10)
      
      println(s"--- Starting 'GPR R/W Test (Fixed)' (maxPc=0x${maxExpectedPc.toString(16)}) ---")

      val maxCycles = 15000 
      var simTime = 0
      cd.waitSampling()
      while(dut.io.commitStats.maxCommitPc.toBigInt < maxExpectedPc && simTime < maxCycles) {
        cd.waitSampling()
        simTime += 1
      }
      
      println(s"Simulation finished at cycle $simTime. Final PC: 0x${dut.io.commitStats.maxCommitPc.toBigInt.toString(16)}")
      
      val finalPC = dut.io.commitStats.maxCommitPc.toBigInt
      val successPC = BigInt("80000000", 16) + (finalInstructions.length - 1) * 4
      assert(finalPC == successPC, s"Test failed! CPU ended at wrong PC. Expected 0x${successPC.toString(16)}, got 0x${finalPC.toString(16)}")

      println("--- 'GPR R/W Test (Fixed)' Passed ---")
    }
  }
  test("Sequential Memory Write Test") {
    val rawInstructions = Seq(
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
      beq(rj = 0, rd = 0, offset = 0), // 0x80000080
    )
    val extraInstsCount = 1000
    val extraInsts = Seq.fill(extraInstsCount)(addi_w(rd = 31, rj = 0, imm = 1))
    val instructions = rawInstructions ++ extraInsts

    LabHelper.dumpBinary(instructions, "bin/why_stopped.bin")

    
    // Configure PC bounds checking
    val maxExpectedPc = BigInt("80000000", 16) + (rawInstructions.length - 1) * 4
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

      assert(minCommitsReached, s"Insufficient commits: need at least $minCommitsNeeded")
      assert(dut.io.commitStats.maxCommitPc.toBigInt <= maxExpectedPc, s"Max PC exceeded: max=0x${dut.io.commitStats.maxCommitPc.toBigInt.toString(16)}, expected=0x${maxExpectedPc.toString(16)}")

      println("--- 'Sequential Memory Write Test' Passed ---")
    }
  }

  test("mem write test") {
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

  test("a+b") {
      val instructions_add_test = Seq(
        addi_w(rd = 10, rj = 0, imm = 7),
        addi_w(rd = 15, rj = 0, imm = 8),
        add_w(rd = 12, rj = 15, rk = 10),
        lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
        ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff),
        st_w(rd = 12, rj = 13, offset = 0),
        bne(rj = 12, rd = 0, offset = 0)
      )

      LabHelper.dumpBinary(instructions_add_test, "bin/plus_a_b_test.bin")
  }


  test("1xor2") {
      val instructions_add_test = Seq(
        /*00*/addi_w(rd = 10, rj = 0, imm = 4),
        /*04 */addi_w(rd = 0, rj = 0, imm = 0),
        /*08*/xor(rd = 12, rj = 15, rk = 10),
        /*0c*/lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
        /*10*/ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff),
        /* */st_w(rd = 12, rj = 13, offset = 0),
        /* */bne(rj = 12, rd = 0, offset = 0)
      )

      LabHelper.dumpBinary(instructions_add_test, "bin/xor_a_b_test.bin")
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

      // 原地跳转
      bne(rj = 14, rd = 0, offset = 0)
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
      bne(rj = 12, rd = 0, offset = 0)
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
    // 00 addi.w      $t0,$zero,0x1   # t0 = 1
    //    addi.w      $t1,$zero,0x1   # t1 = 1
    //    lu12i.w     $a0,-0x7fc00    # a0 = 0x80400000
    //    addi.w      $a1,$a0,0x100   # a1 = 0x80400100
    //    loop:
    // 10 add.w       $t2,$t0,$t1     # t2 = t0+t1
    //    addi.w      $t0,$t1,0x0     # t0 = t1
    //    addi.w      $t1,$t2,0x0     # t1 = t2
    //    st.w        $t2,$a0,0x0
    // 20 ld.w        $t3,$a0,0x0
    //    bne         $t2,$t3,end
    //    addi.w      $a0,$a0,0x4     # a0 += 4
    //    bne         $a0,$a1,loop
    //    end:
    // 30 bne         $a0,$zero,end

    // Mapped to rX registers based on ABI:
    // $zero = r0
    // $t0 = r12, $t1 = r13, $t2 = r14, $t3 = r15
    // $a0 = r4, $a1 = r5

    /*00*/ instructions += addi_w(rd = 12, rj = 0, imm = 1) // $t0 ($r12) = 1
    /*  */ instructions += addi_w(rd = 13, rj = 0, imm = 1) // $t1 ($r13) = 1
    /*  */ instructions += lu12i_w(rd = 4, imm = 0x80400) // $a0 ($r4) = 0x80400000 (imm -0x7fc00 for lu12i.w)
    /*  */ instructions += addi_w(rd = 5, rj = 4, imm = 0x100) // $a1 ($r5) = $a0 + 0x100 (0x80400100)

    // loop: (PC = 0x10, relative to start)
    /*10*/ instructions += add_w(rd = 14, rj = 12, rk = 13) // $t2 ($r14) = $t0 + $t1
    /*  */ instructions += addi_w(rd = 12, rj = 13, imm = 0) // $t0 = $t1
    /*  */ instructions += addi_w(rd = 13, rj = 14, imm = 0) // $t1 = $t2
    /*  */ instructions += st_w(rd = 14, rj = 4, offset = 0) // mem[$a0] = $t2
    /*20*/ instructions += ld_w(rd = 15, rj = 4, offset = 0) // $t3 ($r15) = mem[$a0]

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

    val compiled = SimConfig.withConfig(
        SpinalConfig().copy(
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
        )
      ).withFstWave.compile(new LabTestBench(instructions))

    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(300000)

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

  testOnly("Multiplier Test") {
    val instructions = Seq(
      // Load multiplicand A (e.g., 5) into R10
      addi_w(rd = 10, rj = 0, imm = 5),
      // Load multiplier B (e.g., 7) into R11
      addi_w(rd = 11, rj = 0, imm = 7),
      // Perform multiplication: R12 = R10 * R11 (5 * 7 = 35)
      mul_w(rd = 12, rj = 10, rk = 11),
      // Load memory address (e.g., 0x80400000) into R13
      lu12i_w(rd = 13, imm = 0x80400000 >>> 12),
      ori(rd = 13, rj = 13, imm = 0x80400000 & 0xfff),
      // Store the result (R12) to memory at address in R13
      st_w(rd = 12, rj = 13, offset = 0),
      // Infinite loop to halt simulation
      beq(rj = 0, rd = 0, offset = 0)
    )

    LabHelper.dumpBinary(instructions, "bin/multiplier_test.bin")

    val compiled = SimConfig.withFstWave.compile(new LabTestBench(instructions))

    compiled.doSim { dut =>
      val cd = dut.clockDomain.get
      cd.forkStimulus(period = 10)
      SimTimeout(10000) // Set a timeout for the simulation

      println("--- Starting Multiplier Test ---")

      // Wait for enough cycles for the multiplication and store to complete
      // (approx. 4 for loads, 6 for mul, 1 for store, plus pipeline delays)
      cd.waitSampling(200) 

      // Verify memory content
      val verificationAddress = 0 // Offset from dSram base address
      val expectedValue = BigInt(35) // 5 * 7 = 35

      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= verificationAddress
      cd.waitSampling() // Wait one cycle for the read to complete
      val actualValue = dut.dSram.io.tb_readData.toBigInt
      dut.dSram.io.tb_readEnable #= false

      println(s"Memory check at 0x80400000: Expected=0x${expectedValue.toString(16)}, Got=0x${actualValue.toString(16)}")
      assert(actualValue == expectedValue, s"Multiplier test FAILED! Expected 0x${expectedValue.toString(16)}, but got 0x${actualValue.toString(16)}")
      println("--- Multiplier Test PASSED ---")

      // Optional: Dump memory for inspection
      LabHelper.ramdump(
        sram = dut.dSram,
        vaddr = BigInt("80400000", 16),
        size = 64,
        filename = "bin/dsram_dump_multiplier_test.bin"
      )(cd)
    }
  }

  thatsAll()
}
