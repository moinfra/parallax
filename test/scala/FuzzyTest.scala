package test.scala

import java.io.{File, FileInputStream}
import java.nio.{ByteBuffer, ByteOrder}
import _root_.test.scala.LA32RInstrBuilder._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import java.io.File
import scala.sys.process.ProcessLogger
import scala.concurrent.{Promise, Await} // 引入 Promise 和 Await
import scala.concurrent.duration._ // 引入 Duration

/**
 * Configuration for a fuzzing test session.
 */
case class FuzzCfg(
    seed: Long,
    instCount: Int,
    maxCommits: Long,
    maxStaleCommits: Long,
    iSramBase: BigInt,
    iSramSize: BigInt,
    dSramBase: BigInt,
    dSramSize: BigInt,
    pcSafeLimit: BigInt,
    memCheckSize: Int,
    memFillValue: BigInt
)

/**
 * Manages the state and logic for a co-simulation run.
 * @param dut The device under test.
 * @param cfg The fuzzing configuration.
 */
class CoSimState(dut: LabTestBench, cfg: FuzzCfg) {
  // --- Simulation State ---
  var lastCommitPc: BigInt = 0
  var goldenModelFinalStatus: Option[CVmStatus.Value] = None
  var commitCounter: Long = 0
  var hitTerminationCondition = false

  // 定义终止原因的枚举
  object TerminationReason extends Enumeration {
    val Unknown, MaxCommitLimitReached, PcOutOfBounds, GprMismatch, DutStalled, GoldenModelHaltedNatural, GoldenModelHaltedLoop, GoldenModelError, LoopMismatch = Value
  }
  var terminationReason: TerminationReason.Value = TerminationReason.Unknown // 使用枚举

  // 新增：用于传递终止消息的 Promise
  val terminationPromise: Promise[String] = Promise[String]()

  // --- Edge Coverage Tracking ---
  private val edgeCoverage = scala.collection.mutable.Set[(BigInt, BigInt)]()
  var staleCommitCounter: Long = 0
  
  // --- Stall Detection State ---
  val stallThresholdCycles: Long = 1000 // Consider DUT stalled if no commit for this many cycles.
  var hasCommittedOnce: Boolean = false

  // --- Loop Detection State (新增) ---
  var isCheckingForLoop = false // 新增状态，表示我们怀疑进入了循环

  /**
   * Processes a single valid instruction commit from the DUT.
   */
  def processCommit(currentCommitPc: BigInt): Unit = {
    // 设置第一次提交的标志，并激活停滞检测器
    if (!hasCommittedOnce) {
        hasCommittedOnce = true
        println("INFO: First instruction committed. Stall detector is now active.")
    }

    // 只有在非循环检查模式下才正常处理 `staleCommitCounter` 和 `edgeCoverage`
    // 因为在循环检查模式下，我们希望 DUT 持续在同一个 PC 提交
    if (!isCheckingForLoop) { // <--- 关键修改：只有在非循环检查模式下才执行这些逻辑
        // 1. 更新计数器
        commitCounter += 1
        
        // 2. 检查新边覆盖
        val newEdge = (lastCommitPc, currentCommitPc)
        if (!edgeCoverage.contains(newEdge)) {
            edgeCoverage.add(newEdge)
            staleCommitCounter = 0 // 重置计数器，因为发现了新边
        } else {
            staleCommitCounter += 1 // 同一个边，增加计数
        }
        
        lastCommitPc = currentCommitPc // 更新上一个PC

        // 3. 检查是否达到循环检测阈值
        if (staleCommitCounter > cfg.maxStaleCommits) {
            println(s"INFO: Stale commit limit reached at PC 0x${currentCommitPc.toString(16)}. Entering loop verification mode.")
            isCheckingForLoop = true // 进入循环检查模式
            // 注意：这里不返回，让本次提交继续正常处理，因为这是触发循环检查的提交
        }
        
        // 4. 检查最大提交数限制
        if (commitCounter > cfg.maxCommits) {
          terminate(TerminationReason.MaxCommitLimitReached, s"Reached maximum commit limit of ${cfg.maxCommits}.")
          return
        }

      // 5. 与Golden Model协同仿真
      if (!hitTerminationCondition) {
        if (currentCommitPc >= cfg.pcSafeLimit) {
          terminate(TerminationReason.PcOutOfBounds, s"PC Out of Bounds! PC: 0x${currentCommitPc.toString(16)}")
          return // 立即返回，不再执行后续逻辑
        }

        if (goldenModelFinalStatus.isEmpty) {
          val vmStatus = GoldenModel.stepUntil(currentCommitPc)
          checkGprs(currentCommitPc) // GPRs 必须在 VM 停机前检查
          
          vmStatus match {
            case CVmStatus.Halted =>
              // --- 这是关键的修改 ---
              // VM 停机了。我们需要检查它停机的原因。
              // 如果它停在当前 DUT 正在执行的 PC 上，这极有可能是它检测到了无限循环。
              val vmPc = GoldenModel.getPc()
              if (vmPc == currentCommitPc) {
                // VM 停在了我们当前的位置。这被视为一种特殊的、一致的“循环停机”。
                // 我们现在可以认为软硬件模型都同意这里是一个循环。
                terminate(TerminationReason.GoldenModelHaltedLoop, s"Golden Model halted at PC 0x${vmPc.toString(16)} due to a likely infinite loop, which is consistent with the DUT's behavior. Test PASSED.")
                goldenModelFinalStatus = Some(CVmStatus.Halted) 
              } else {
                // VM 在其他地方停机了，这可能是程序正常结束。
                terminate(TerminationReason.GoldenModelHaltedNatural, s"Golden Model halted naturally at PC 0x${vmPc.toString(16)}.")
                goldenModelFinalStatus = Some(CVmStatus.Halted)
              }
            
            case CVmStatus.Error =>
              terminate(TerminationReason.GoldenModelError, s"Golden Model errored unexpectedly at PC 0x${currentCommitPc.toString(16)}.")
              goldenModelFinalStatus = Some(CVmStatus.Error)
              // 这里不直接 assert false，而是让主线程处理
            
            case _ => // Running or Idle
          }
        }
      }
    } else {
        // 如果处于循环检查模式，则不更新 staleCommitCounter 和 edgeCoverage
        // 也不执行正常的 Golden Model 步进，因为这部分逻辑会在 Fuzz.runCoSim 中处理
        // 只需要更新 lastCommitPc，以便 Fuzz.runCoSim 进行比较
        lastCommitPc = currentCommitPc
    }
  }
  
  /** Performs GPR comparison between DUT and Golden Model. */
  private def checkGprs(pc: BigInt): Unit = {
    val dutGprs = dut.dut.simArchGprs.get.map(_.toBigInt)
    val goldenGprs = GoldenModel.getAllGprs()
    // GPR r0 永远是 0，不参与比较
    if (!dutGprs.slice(1, 32).sameElements(goldenGprs.slice(1, 32))) {
      println(s"\n!!! GPR MISMATCH at PC 0x${pc.toString(16)} !!!")
      (1 until 32).foreach { k =>
        if (dutGprs(k) != goldenGprs(k)) {
          val dutHex = s"0x${dutGprs(k).toString(16).reverse.padTo(8, '0').reverse}"
          val gldHex = s"0x${goldenGprs(k).toString(16).reverse.padTo(8, '0').reverse}"
          println(f"r$k%-3d| DUT: $dutHex, Golden: $gldHex <- MISMATCH")
        }
      }
      terminate(TerminationReason.GprMismatch, "GPR state mismatch.")
      // 这里不直接 assert false，而是让主线程处理
    }
  }

  /** Centralized function to set the termination flag and reason, and complete the promise. */
  def terminate(reason: TerminationReason.Value, message: String): Unit = {
    if (!hitTerminationCondition) {
      hitTerminationCondition = true
      terminationReason = reason
      println(s"INFO: Test terminated. Reason: ${message}") // 打印详细信息
      // 完成 Promise，传递终止消息
      terminationPromise.trySuccess(message)
    }
  }
}

/**
 * Provides concise, powerful utilities for fuzz testing.
 */
object Fuzz {
  
  def prepMemData(content: Seq[BigInt], totalWordCount: BigInt, paddingValue: BigInt): Seq[BigInt] = {
    require(content.length <= totalWordCount)
    content ++ Seq.fill((totalWordCount - content.length).toInt)(paddingValue)
  }

  def dumpAndDisasm(instructions: Seq[BigInt], baseName: String, objdumpPath: String): Unit = {
    val binFileName = s"$baseName.bin"
    val asmFileName = s"$baseName.S"
    LabHelper.dumpBinary(instructions, binFileName)
    println(s"Dumped ${instructions.length} instructions to $binFileName")
    val objdumpFile = new File(objdumpPath)
    if (!objdumpFile.exists() || !objdumpFile.canExecute) {
      println(s"Warning: objdump not found or not executable at '$objdumpPath'. Skipping disassembly.")
      return
    }
    try {
      val command = Seq(objdumpPath, "-D", "-b", "binary", "-m", "loongarch", binFileName)
      val processLogger = ProcessLogger(_ => (), err => println(s"objdump stderr: $err"))
      import scala.sys.process._
      if ((command #> new File(asmFileName)).!(processLogger) != 0) {
        println(s"Warning: objdump failed when disassembling $binFileName.")
      } else {
        println(s"Successfully disassembled to $asmFileName")
      }
    } catch {
      case e: Exception => println(s"An exception occurred while running objdump: ${e.getMessage}")
    }
  }

  // <--- 关键修改：函数签名修改为返回 CoSimState
  def runCoSim(dut: LabTestBench, cfg: FuzzCfg): CoSimState = {
    val cd = dut.clockDomain
    val state = new CoSimState(dut, cfg)

    // Fork a concurrent process for stall detection
    val stallDetector = fork {
      var cyclesWithoutCommit = 0
      while (!state.hitTerminationCondition) {
        cd.waitSampling()
        if (state.hasCommittedOnce) { // 停滞检测器只有在第一次提交后才激活
          // <--- 关键修改：停滞检测器现在检查 doCommit 信号，而不是 commitCounter
          if (dut.commitLog(0).doCommit.toBoolean) { // 如果 DUT 正在提交指令
            cyclesWithoutCommit = 0 // 重置计数器
          } else {
            cyclesWithoutCommit += 1 // 如果 DUT 没有提交指令
          }
          
          if (cyclesWithoutCommit > state.stallThresholdCycles) {
            state.terminate(state.TerminationReason.DutStalled, s"DUT has not committed any instruction for ${state.stallThresholdCycles} cycles. DUT stalled.")
          }
        }
      }
    }

    // Main commit processing loop
    cd.onSamplings {
      // 只有在没有终止信号发出时才处理提交
      if (!state.hitTerminationCondition) {
        val comm = dut.commitLog(0)
        if (comm.doCommit.toBoolean) {
          val currentCommitPc = comm.pc.toBigInt
          // <--- 关键修改：处理循环检查逻辑
          if (state.isCheckingForLoop) {
            // 如果我们正处于循环检查模式
            if (currentCommitPc == state.lastCommitPc) {
              // DUT 确认在循环中。现在检查 Golden Model
              // 让 Golden Model 执行一步，并检查其 PC
              val vmStatus = GoldenModel.step() // Golden Model 步进
              val vmPc = GoldenModel.getPc()

              // 验证 Golden Model 是否也在同一个循环点
              if (vmPc == state.lastCommitPc) {
                state.terminate(state.TerminationReason.GoldenModelHaltedLoop, s"DUT and Golden Model both confirmed to be in an infinite loop at PC 0x${currentCommitPc.toString(16)}. Test PASSED.")
                println(">>> Loop detected and verified. Ending test successfully. <<<")
              } else {
                // 严重错误：DUT 在循环，但 Golden Model 已经前进到其他地方了
                state.terminate(state.TerminationReason.LoopMismatch, s"Loop Mismatch! DUT is looping at PC 0x${currentCommitPc.toString(16)}, but Golden Model is at PC 0x${vmPc.toString(16)}.")
              }
            } else {
              // 误报：DUT 并没有继续循环，而是前进到了新的 PC
              println(s"INFO: Loop check failed. DUT moved from 0x${state.lastCommitPc.toString(16)} to 0x${currentCommitPc.toString(16)}. Resuming normal operation.")
              state.isCheckingForLoop = false // 退出循环检查模式
              state.staleCommitCounter = 0 // 重置计数器，因为现在又有了新的边
              // 正常处理这次提交
              state.processCommit(currentCommitPc)
            }
          } else {
            // 正常模式：只有当 PC 发生变化时才处理提交，避免重复处理同一条指令
            // 注意：state.processCommit 内部已经处理了 lastCommitPc 的更新，
            // 并且如果 currentCommitPc == lastCommitPc 且没有新边，staleCommitCounter 会增加。
            // 所以这里不需要额外的 currentCommitPc != state.lastCommitPc 检查。
            state.processCommit(currentCommitPc)
          }
        }
        
        // 处理 Golden Model 已经停机但 DUT 仍在运行的情况
        if (state.goldenModelFinalStatus.contains(CVmStatus.Halted) && comm.doCommit.toBoolean) {
          // 如果 Golden Model 已经停机，并且 DUT 还在提交指令，这通常是错误
          // 但如果 Golden Model 是因为循环而停机（GoldenModelHaltedLoop），则 DUT 继续提交是预期的
          if (state.terminationReason != state.TerminationReason.GoldenModelHaltedLoop) {
            state.terminate(state.TerminationReason.GoldenModelHaltedNatural, s"DUT is still committing (PC: 0x${comm.pc.toBigInt.toString(16)}) after Golden Model has halted!")
          }
        }
      }
    }

    // 等待终止条件被触发
    cd.waitSamplingWhere(state.hitTerminationCondition)
    
    // 额外等待一些周期，以确保波形捕获完整
    cd.waitSampling(1) // 增加等待时间

    println(s"INFO: Test terminated. Final reason: ${state.terminationReason}") // 打印最终原因
    stallDetector.join() // 确保停滞检测器线程正常结束

    val finalStatus = GoldenModel.getStatus()
    // 如果终止原因是“自然停止”或“无限循环”，则 Golden Model 必须是 Halted 状态
    state.terminationReason match {
      case state.TerminationReason.GoldenModelHaltedNatural | state.TerminationReason.GoldenModelHaltedLoop =>
        assert(finalStatus == CVmStatus.Halted, s"Program should have halted naturally or entered infinite loop, but final status is $finalStatus")
      case _ =>
        // 其他终止原因（如达到最大提交数、PC 越界、GPR 不匹配、DUT 停滞等）
        // 此时 Golden Model 的状态可以是 Running/Idle/Error，都是可接受的
        println(s"Final Golden Model status after forceful termination: $finalStatus. This is acceptable given the termination reason.")
    }
    // 移除这里误导性的成功消息
    // println("GPR co-simulation phase passed successfully.") 

    state // <--- 关键修改：返回 state 实例
  }

  def checkMemDump(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    println(s"--- Starting final memory consistency check (${cfg.memCheckSize} bytes) ---")
    val cd = dut.clockDomain
    val goldenMem = new Array[Byte](cfg.memCheckSize)
    assert(GoldenModel.readMemory(cfg.dSramBase.toInt, goldenMem) == 0, "Golden Model memory read failed.")
    val dutMem = new Array[Byte](cfg.memCheckSize)
    for (i <- 0 until cfg.memCheckSize by 4) {
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= i
      cd.waitSampling()
      val word = dut.dSram.io.tb_readData.toBigInt
      // 字节顺序：小端序
      dutMem(i + 3) = ((word >> 24) & 0xff).toByte; dutMem(i + 2) = ((word >> 16) & 0xff).toByte
      dutMem(i + 1) = ((word >> 8) & 0xff).toByte; dutMem(i) = (word & 0xff).toByte
    }
    dut.dSram.io.tb_readEnable #= false
    if (!dutMem.sameElements(goldenMem)) {
      println("\n!!! MEMORY MISMATCH DETECTED !!!")
      var mismatchesFound = 0
      (0 until cfg.memCheckSize).foreach { i =>
        if (dutMem(i) != goldenMem(i) && mismatchesFound < 20) { // 只打印前20个不匹配项
          mismatchesFound += 1
          println(f"  - Address 0x${cfg.dSramBase + i}%x: DUT=0x${dutMem(i) & 0xff}%02x, Golden=0x${goldenMem(i) & 0xff}%02x")
        }
      }
      assert(false, "Final memory consistency check FAILED.")
    }
    println("--- Memory consistency check PASSED ---")
  }
}

/**
 * Main test suite for advanced fuzzing.
 */
class FuzzyTest extends CustomSpinalSimFunSuite {

  // 请确保 objdumpPath 指向你的 loongarch32r-linux-gnusf-objdump 可执行文件
  val objdumpPath = "../la32r-vm/tools/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin/loongarch32r-linux-gnusf-objdump"
  
  val mainFuzzCfg = FuzzCfg(
    seed = 6,
    instCount = 8000,
    maxCommits = 8000,
    maxStaleCommits = 500, // 连续 500 次提交没有新边覆盖，则进入循环检查模式
    iSramBase = BigInt("80000000", 16),
    iSramSize = 4 * 1024 * 1024,
    dSramBase = BigInt("80400000", 16),
    dSramSize = 4 * 1024 * 1024,
    pcSafeLimit = BigInt("81000000", 16),
    memCheckSize = 8 * 1024,
    memFillValue = BigInt("DEADBEEF", 16)
  )

  test(s"Advanced Fuzz Test (seed=${mainFuzzCfg.seed})") {
    val rand = new Random(mainFuzzCfg.seed)
    println(s"--- Test Setup Phase (seed=${mainFuzzCfg.seed}) ---")

    val instructions: Seq[BigInt] = ProgramGenerator.generate(mainFuzzCfg, rand)
    // iSramContent 填充指令，dSramContent 填充随机数据
    val iSramContent = Fuzz.prepMemData(instructions, mainFuzzCfg.iSramSize / 4, 0)
    val dSramContent = Fuzz.prepMemData(Seq.empty, mainFuzzCfg.dSramSize / 4, mainFuzzCfg.memFillValue)
    
    val baseFileName = s"bin/advanced_fuzzy_test_seed_${mainFuzzCfg.seed}"
    Fuzz.dumpAndDisasm(instructions, baseFileName, objdumpPath)

    val memoryMap = Seq((mainFuzzCfg.iSramBase, mainFuzzCfg.iSramSize), (mainFuzzCfg.dSramBase, mainFuzzCfg.dSramSize))
    GoldenModel.initialize(mainFuzzCfg.iSramBase.toInt, memoryMap)

    def toBytes(words: Seq[BigInt]): Array[Byte] = words.flatMap { w =>
      // LoongArch 是小端序
      Array((w & 0xff).toByte, ((w >> 8) & 0xff).toByte, ((w >> 16) & 0xff).toByte, ((w >> 24) & 0xff).toByte)
    }.toArray

    GoldenModel.initMemory(mainFuzzCfg.iSramBase.toInt, toBytes(iSramContent))
    GoldenModel.initMemory(mainFuzzCfg.dSramBase.toInt, toBytes(dSramContent))
    println("Golden Model initialized.")

    val compiled = SimConfig.withFstWave.compile(
      new LabTestBench(iDataWords = iSramContent, dDataWords = dSramContent)
    )
    println("DUT compiled.")

    compiled.doSim(s"FuzzTest_seed_${mainFuzzCfg.seed}") { dut =>
      dut.clockDomain.forkStimulus(10) // 10ns 周期
      SimTimeout(4000000) // 增加仿真超时时间，以适应更长的测试运行

      println("--- Simulation Started ---")

      // <--- 关键修改：接收 runCoSim 的返回值
      val finalCoSimState = Fuzz.runCoSim(dut, mainFuzzCfg)
      
      // 检查是否有终止信号，如果有，则在等待后抛出 assert
      if (finalCoSimState.hitTerminationCondition) {
        // 尝试获取终止消息，设置一个超时时间防止死锁
        val terminationMessage = Await.result(finalCoSimState.terminationPromise.future, 1.second)
        // 只有当终止原因不是“循环已验证”时才抛出 assert
        if (finalCoSimState.terminationReason != finalCoSimState.TerminationReason.GoldenModelHaltedLoop) {
          assert(false, terminationMessage)
        }
      }

      // 只有当测试不是因为循环而成功终止时，才进行内存检查
      finalCoSimState.terminationReason match {
        case finalCoSimState.TerminationReason.GoldenModelHaltedLoop =>
          println("INFO: Skipping final memory check due to verified infinite loop.")
        case _ =>
          Fuzz.checkMemDump(dut, mainFuzzCfg)
      }

      // 只有当没有 assert 抛出时，才打印测试通过
      if (!finalCoSimState.hitTerminationCondition || finalCoSimState.terminationReason == finalCoSimState.TerminationReason.GoldenModelHaltedLoop) {
        println(s"\n>>> Advanced Fuzz Test PASSED! (seed=${mainFuzzCfg.seed}) <<<")
      }
    }

    GoldenModel.destroy()
    println("--- Test Finished ---")
  }

  /**
   * Loads a binary file and converts its content into a sequence of BigInts (words).
   * @param path Path to the binary file.
   * @return A sequence of BigInts representing the file content, or null if file not found.
   */
  def loadBinaryFile(path: String): Seq[BigInt] = {
    val file = new File(path)
    if (!file.exists()) {
      println(s"ERROR: Binary file not found at '$path'")
      return null
    }

    val fis = new FileInputStream(file)
    val bytes = fis.readAllBytes()
    fis.close()

    // Ensure byte count is a multiple of 4
    val paddedBytes = if (bytes.length % 4 != 0) {
      bytes ++ Array.fill[Byte](4 - bytes.length % 4)(0)
    } else {
      bytes
    }

    val buffer = ByteBuffer.wrap(paddedBytes)
    buffer.order(ByteOrder.LITTLE_ENDIAN) // LoongArch is Little-Endian

    val words = new ArrayBuffer[BigInt]()
    while (buffer.hasRemaining) {
      // Read an Int, convert to unsigned long to avoid sign extension issues, then to BigInt
      words += BigInt(buffer.getInt() & 0xFFFFFFFFL)
    }
    words.toSeq
  }

    val kernelCfg = FuzzCfg(
    seed = 0, // Seed is not used for instruction generation here
    instCount = 0, // Not applicable, we load from file
    maxCommits = 500000, // Set a high limit for the kernel to run
    maxStaleCommits = 2000, // Allow more stale commits for potentially complex loops in kernel
    iSramBase = BigInt("80000000", 16),
    iSramSize = 4 * 1024 * 1024,
    dSramBase = BigInt("80400000", 16),
    dSramSize = 4 * 1024 * 1024,
    pcSafeLimit = BigInt("81000000", 16),
    memCheckSize = 8 * 1024,
    memFillValue = 0 // Use 0 for data memory padding
  )

  test("xKernel Co-Simulation Test (from bin/kernel.bin)") {
    println("--- Test Setup Phase (Kernel) ---")

    val kernelPath = "bin/kernel.bin"
    val instructions = loadBinaryFile(kernelPath)
    assert(instructions != null, s"Failed to load kernel file from '$kernelPath'. Test cannot proceed.")
    println(s"Successfully loaded ${instructions.length} words from $kernelPath.")

    // Prepare memory content
    val iSramContent = Fuzz.prepMemData(instructions, kernelCfg.iSramSize / 4, 0)
    val dSramContent = Fuzz.prepMemData(Seq.empty, kernelCfg.dSramSize / 4, kernelCfg.memFillValue)

    val memoryMap = Seq((kernelCfg.iSramBase, kernelCfg.iSramSize), (kernelCfg.dSramBase, kernelCfg.dSramSize))
    GoldenModel.initialize(kernelCfg.iSramBase.toInt, memoryMap)

    def toBytes(words: Seq[BigInt]): Array[Byte] = words.flatMap { w =>
      // LoongArch 是小端序
      Array((w & 0xff).toByte, ((w >> 8) & 0xff).toByte, ((w >> 16) & 0xff).toByte, ((w >> 24) & 0xff).toByte)
    }.toArray

    GoldenModel.initMemory(kernelCfg.iSramBase.toInt, toBytes(iSramContent))
    GoldenModel.initMemory(kernelCfg.dSramBase.toInt, toBytes(dSramContent))
    println("Golden Model initialized for kernel test.")

    val compiled = SimConfig.withFstWave.compile(
      new LabTestBench(iDataWords = iSramContent, dDataWords = dSramContent)
    )

    compiled.doSim("KernelTest") { dut =>
      dut.clockDomain.forkStimulus(10) // 10ns clock period
      SimTimeout(10000000) // Use a generous timeout for the kernel

      println("--- Kernel Simulation Started ---")

      val finalCoSimState = Fuzz.runCoSim(dut, kernelCfg)
      
      // 检查是否有终止信号，如果有，则在等待后抛出 assert
      if (finalCoSimState.hitTerminationCondition) {
        val terminationMessage = Await.result(finalCoSimState.terminationPromise.future, 1.second)
        if (finalCoSimState.terminationReason != finalCoSimState.TerminationReason.GoldenModelHaltedLoop) {
          assert(false, terminationMessage)
        }
      }
      
      finalCoSimState.terminationReason match {
        case finalCoSimState.TerminationReason.GoldenModelHaltedLoop =>
          println("INFO: Skipping final memory check due to verified infinite loop in kernel.")
        case _ =>
          Fuzz.checkMemDump(dut, kernelCfg)
      }

      // 只有当没有 assert 抛出时，才打印测试通过
      if (!finalCoSimState.hitTerminationCondition || finalCoSimState.terminationReason == finalCoSimState.TerminationReason.GoldenModelHaltedLoop) {
        println(s"\n>>> Kernel Co-Simulation Test PASSED! <<<")
      }
    }

    GoldenModel.destroy()
    println("--- Kernel Test Finished ---")
  }

  thatsAll
}
