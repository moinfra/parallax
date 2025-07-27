package test.scala

// filename: test/scala/fetch2/FetchPipelineModelCheckerSpec.scala
// cmd: testOnly test.scala.FetchPipelineModelCheckerSpec

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch2._
import parallax.bpu._
import parallax.components.memory._
import parallax.utilities._
import parallax.fetch.icache.{ICachePlugin, ICacheConfig}
import spinal.lib.bus.amba4.axi.Axi4Config
import LA32RInstrBuilder._
import scala.collection.mutable
import parallax.components.bpu.BpuPipelinePlugin
import parallax.fetch.FetchedInstr
import java.util.concurrent.LinkedBlockingQueue // 导入并发队列
import scala.collection.mutable.ArrayBuffer

/**
 * 一个无副作用的、异步的激励应用助手。
 * 它的所有方法都只在当前仿真tick设置信号值，不包含任何`waitSampling`。
 */
class AsyncStimulusHelper(dut: Fetch2PipelineTestBench, pCfg: PipelineConfig) {
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

  /**
   * 在测试开始前执行的、带有副作用的初始化。
   * 这在主循环开始前是安全的。
   */
  def blockingWriteInstructionsToMem(address: BigInt, instructions: Seq[BigInt])(implicit cd: ClockDomain): Unit = {
    for ((inst, i) <- instructions.zipWithIndex) {
      val currentAddr = address + (i * 4)
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= currentAddr
      sram.io.tb_writeData #= inst
      println(s"dbg write ${inst.toString(16)} to 0x${currentAddr.toString(16)}")
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(50)
  }
  /**
   * 校验SRAM的内存内容是否与期望值一致。
   * 这在初始化后、主循环开始前调用，以确保测试环境的正确性。
   *
   * @param expectedMemory 一个从地址到指令编码的Map，代表期望的内存状态。
   * @param sramMinAddr SRAM的起始地址。
   * @param sramMaxAddr SRAM的结束地址（不含）。
   */
  def verifyMemoryInitialization(
      expectedMemory: Map[BigInt, BigInt],
      sramMinAddr: BigInt,
      sramMaxAddr: BigInt
  )(implicit cd: ClockDomain): Unit = {
    ParallaxLogger.info("[Setup] Verifying SRAM initialization...")
    val mismatches = ArrayBuffer[(BigInt, BigInt, BigInt)]()
    
    // 遍历整个SRAM地址空间
    for (addr <- sramMinAddr until sramMaxAddr by 4) {
      // 从SRAM中读出数据
      sram.io.tb_readEnable #= true
      sram.io.tb_readAddress #= addr
      cd.waitSampling() // 等待一个周期让读操作完成

      val dutData = sram.io.tb_readData.toBigInt
      val expectedData = expectedMemory.getOrElse(addr, nop()) // 默认期望是NOP

      if (dutData != expectedData) {
        mismatches += ((addr, expectedData, dutData))
      }
    }
    sram.io.tb_readEnable #= false
    cd.waitSampling()

    if (mismatches.nonEmpty) {
      val errorReport = new StringBuilder
      errorReport.append(s"\n\n================================================================================\n")
      errorReport.append(s"[FATAL] SRAM initialization verification FAILED! Found ${mismatches.length} mismatches.\n")
      errorReport.append(s"--------------------------------------------------------------------------------\n")
      for ((addr, expected, actual) <- mismatches.take(20)) { // 最多显示20个错误
        errorReport.append(f"  - Address: 0x${addr}%08x, Expected: 0x${expected}%08x, Got: 0x$actual%08x\n")
      }
      if(mismatches.length > 20) errorReport.append(s"  ... and ${mismatches.length - 20} more mismatches.\n")
      errorReport.append(s"================================================================================\n")
      assert(false, errorReport.toString())
    } else {
      ParallaxLogger.success("[Setup] SRAM initialization verified successfully.")
    }
  }
  /**
   * 在主循环的每个周期调用，根据stimulus对象应用激励。
   * 这是一个纯组合逻辑的赋值过程。
   */
  def applyStimulus(stimulus: FetchStimulus): Unit = {
    // --- 脉冲信号 ---
    // 硬重定向只在一个周期内有效
    dut.io.hardRedirect.valid #= stimulus.doHardRedirect
    if (stimulus.doHardRedirect) {
      dut.io.hardRedirect.payload #= stimulus.hardRedirectTarget
    }

    // BPU更新也只在一个周期内有效
    dut.io.bpuUpdate.valid #= stimulus.doBpuUpdate
    if (stimulus.doBpuUpdate) {
      dut.io.bpuUpdate.payload.pc #= stimulus.bpuUpdatePc
      dut.io.bpuUpdate.payload.target #= stimulus.bpuUpdateTarget
      dut.io.bpuUpdate.payload.isTaken #= stimulus.bpuUpdateTaken
    }

    // fetchDisable 和 fetchOutput.ready 现在由主循环直接控制，因为它们是持续性信号
    // dut.io.fetchDisable #= (stimulus.disableCycles > 0)
    // dut.io.fetchOutput.ready #= (stimulus.stallCycles == 0)
  }

  /**
   * 在主循环的每个周期调用，将脉冲信号拉低。
   * 确保 hardRedirect 和 bpuUpdate 只持续一个周期。
   */
  def clearPulseStimulus(): Unit = {
    dut.io.hardRedirect.valid #= false
    dut.io.bpuUpdate.valid #= false
  }

  // 初始化信号到安全状态
  def initSignals(): Unit = {
    dut.io.bpuUpdate.valid #= false
    dut.io.hardRedirect.valid #= false
    dut.io.fetchDisable #= false
    dut.io.fetchOutput.ready #= false // 默认拉低，等待主循环控制
  }
}

// =========================================================================
//  Test Suite
// =========================================================================

class FetchPipelineModelCheckerSpec extends CustomSpinalSimFunSuite {

  val pCfg =
    PipelineConfig(fetchWidth = 4, resetVector = 0x1000, bpuTransactionIdWidth = 3 bit, fetchGenIdWidth = 2 bit)
  val iCfg = ICacheConfig(
    totalSize = 4 * 1024,
    ways = 2,
    bytesPerLine = 16,
    fetchWidth = pCfg.fetchWidth,
    enableLog = false
  )
  val axiConfig = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 4)



test("Fetch2 - ModelChecker with Single-Tick Synchronous Verification") {
SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim(seed = 2333) { dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(100)

        // --- 1. Setup ---
        val maxInstructionsToVerify = 100000
        val helper = new AsyncStimulusHelper(dut, pCfg)
        val model = new FetchGoldenModel(pCfg)
        val sramSizeInWords = (StimulusGenerator.SRAM_MAX_ADDR - StimulusGenerator.SRAM_MIN_ADDR) / 4

        // 步骤 1: 在软件中构建完整的、期望的内存镜像和指令意图
        ParallaxLogger.info("[Setup] Building expected memory image and instruction intents...")
        val (instructionIntents, instructionAsms) = StimulusGenerator.generateInstructionMemory(
          sramSizeInWords,
          branchRatio = 0.3,
          directBranchRatio = 0.3
        )
        
        val fullExpectedMemory = (
            (StimulusGenerator.SRAM_MIN_ADDR until StimulusGenerator.SRAM_MAX_ADDR by 4)
                .map(addr => (addr, nop()))
                .toMap
            ) ++ instructionAsms

        val fullIntents = (
            (StimulusGenerator.SRAM_MIN_ADDR until StimulusGenerator.SRAM_MAX_ADDR by 4)
                .map(addr => (addr, SequentialIntent(addr)))
                .toMap
            ) ++ instructionIntents

        // 步骤 2: 将完整的期望内存一次性写入物理SRAM
        ParallaxLogger.info("[Setup] Writing full memory image to SRAM...")
        // 我们直接写入最终构建好的fullExpectedMemory，这样更高效
        helper.blockingWriteInstructionsToMem(
            StimulusGenerator.SRAM_MIN_ADDR, 
            fullExpectedMemory.toSeq.sortBy(_._1).map(_._2)
        )

        // 步骤 3: 校验物理SRAM的内容是否与期望完全一致
        helper.verifyMemoryInitialization(
            fullExpectedMemory, 
            StimulusGenerator.SRAM_MIN_ADDR, 
            StimulusGenerator.SRAM_MAX_ADDR
        )

        // 步骤 4: 初始化Golden Model和DUT
        model.fillMemory(fullIntents)
        model.reset()
        helper.initSignals()

        // --- 2. Fuzzing Loop (no changes needed here) ---
        var verifiedInstructionCount = 0
        var cycles = 0
        val maxCycles = maxInstructionsToVerify * 200

        var stallCyclesLeft = 0
        var disableCyclesLeft = 0
        var stimulus = FetchStimulus()

        ParallaxLogger.info(s"[ModelChecker] Starting single-tick synchronous verification...")

        // 启动重定向到现在可以安全地指向任何SRAM地址
        stimulus = FetchStimulus(doHardRedirect = true, hardRedirectTarget = StimulusGenerator.randomPcInSramRange())
          
        while (verifiedInstructionCount < maxInstructionsToVerify && cycles < maxCycles) {
          // ... (The fuzzing loop remains exactly the same) ...
          // A. Apply Stimulus
          model.applyStimulus(stimulus, cycles)
          helper.applyStimulus(stimulus)
          dut.io.fetchOutput.ready #= (stallCyclesLeft == 0)
          dut.io.fetchDisable #= (disableCyclesLeft > 0)

          // B. Step Time
          cd.waitSampling(1)
          cycles += 1
          ParallaxLogger.debug(s"[ModelChecker] Cycle $cycles: $stimulus")

          // C. Verify
          if (dut.io.fetchOutput.valid.toBoolean && dut.io.fetchOutput.ready.toBoolean) {
              val receivedPc = dut.io.fetchOutput.payload.pc.toBigInt
              ParallaxLogger.debug(s"[ModelChecker] Valid fetch output ${receivedPc.toString(16)} @ cycle $cycles")
              model.consumeAndVerify(receivedPc, cycles) // The fixed model will now work correctly
              ParallaxLogger.success(s"[ModelChecker] Verified PC=0x${receivedPc.toString(16)} @ cycle $cycles")
              verifiedInstructionCount += 1
          } else {
              ParallaxLogger.debug(s"[ModelChecker] Skipping cycle $cycles: fetchOutput.valid=${dut.io.fetchOutput.valid.toBoolean}, fetchOutput.ready=${dut.io.fetchOutput.ready.toBoolean}")
          }

          // D. Prepare Next Stimulus
          stimulus = FetchStimulus()
          if (stallCyclesLeft > 0) stallCyclesLeft -= 1
          if (disableCyclesLeft > 0) disableCyclesLeft -= 1
          if (stallCyclesLeft == 0 && disableCyclesLeft == 0) {
              val dice = StimulusGenerator.rand.nextInt(100)
              if (dice < 15) {
                  stimulus = StimulusGenerator.generatePulseStimulusInSramRange()
              } else if (dice < 25) {
                  stallCyclesLeft = StimulusGenerator.rand.nextInt(5) + 1
              } else if (dice < 35) {
                  disableCyclesLeft = StimulusGenerator.rand.nextInt(5) + 1
              }
          }
        }

        // --- Final Check ---
        assert(verifiedInstructionCount >= maxInstructionsToVerify, s"Test timed out after $maxCycles cycles. Verified $verifiedInstructionCount instructions.")
        ParallaxLogger.success(s"[ModelChecker] Test PASSED. Verified $verifiedInstructionCount instructions.")
      }
    }


thatsAll
}

// =========================================================================
//  Model Checker Golden Model and Stimulus Generator (Black-Box Version)
// =========================================================================

sealed trait InstructionIntent
case class SequentialIntent(pc: BigInt) extends InstructionIntent
case class BranchIntent(pc: BigInt, target: BigInt, isConditional: Boolean) extends InstructionIntent {
  def fmt: String = s"Branch(pc=0x${pc.toString(16)}, target=0x${target.toString(16)}, isConditional=$isConditional)"
}

case class FetchStimulus(
    // stallCycles: Int = 0, // 由测试循环管理
    // disableCycles: Int = 0, // 由测试循环管理
    doHardRedirect: Boolean = false,
    hardRedirectTarget: BigInt = 0,
    doBpuUpdate: Boolean = false,
    bpuUpdatePc: BigInt = 0,
    bpuUpdateTarget: BigInt = 0,
    bpuUpdateTaken: Boolean = false
) {
  override def toString: String = {
    val parts = mutable.ArrayBuffer[String]()
    // if (stallCycles > 0) parts += s"Stall($stallCycles)"
    // if (disableCycles > 0) parts += s"Disable($disableCycles)"
    if (doHardRedirect) parts += s"HardRedirect(0x${hardRedirectTarget.toString(16)})"
    if (doBpuUpdate)
      parts += s"BpuUpdate(pc=0x${bpuUpdatePc.toString(16)}, target=0x${bpuUpdateTarget.toString(16)}, taken=$bpuUpdateTaken)"
    if (parts.isEmpty) "Idle" else parts.mkString(", ")
  }
}

/**
 * A simple, sequence-based golden model. It is NOT time-aware.
 * It only cares about the sequence of received instructions.
 */
// 在文件: test/scala/fetch2/FetchPipelineModelCheckerSpec.scala

class FetchGoldenModel(pCfg: PipelineConfig) {
  var expectedPc: BigInt = pCfg.resetVector
  var memory: Map[BigInt, InstructionIntent] = Map()
  var bpu: Map[BigInt, (BigInt, Boolean)] = Map()

  private var lastAppliedStimulus: FetchStimulus = FetchStimulus()
  private var lastStimulusCycle: Long = 0

  // <<< 关键修复：新增模型内部状态 >>>
  // 表示模型正在等待一个硬重定向的目标PC被DUT输出
  private var awaitingHardRedirectPc: Option[BigInt] = None 

  /**
   * 验证收到的PC，并根据该PC和*指令流自身*的逻辑，计算下一个期望的PC。
   * 这个方法现在必须处理“重定向等待”状态。
   */
  def consumeAndVerify(receivedPc: BigInt, simCycle: Long): Unit = {
    val verificationPassed: Boolean = awaitingHardRedirectPc match {
      case Some(targetPc) =>
        // 模型正在等待硬重定向的目标PC
        if(receivedPc != targetPc)
        {
          sleep(1) // let log come
          assert(receivedPc == targetPc,
          s"[GOLDEN MODEL] FATAL @ cycle $simCycle: Hard Redirect target mismatch!\n" +
            s"  -> Last stimulus applied @ cycle $lastStimulusCycle: $lastAppliedStimulus\n" +
            s"  -> Model Expected Hard Redirect Target: 0x${targetPc.toString(16)}\n" +
            s"  -> DUT Produced PC:   0x${receivedPc.toString(16)}"
        )
        }
        // 成功收到硬重定向目标，清除等待状态
        awaitingHardRedirectPc = None
        ParallaxLogger.debug(s"[GOLDEN MODEL] Hard Redirect (0x${targetPc.toString(16)}) received. Next expected PC: 0x${expectedPc.toString(16)}")
        true

      case None =>
        // 模型处于正常指令流模式
        if(receivedPc != expectedPc) {
          sleep(1) // let log come
          val seperator = ("\n" * 2) + ("-" * 80) + "\n"
          assert(receivedPc == expectedPc,
            seperator +
             s"[GOLDEN MODEL] FATAL @ cycle $simCycle: PC mismatch!\n" +
              s"  -> Last stimulus applied @ cycle $lastStimulusCycle: $lastAppliedStimulus\n" +
              s"  -> Model Expected PC: 0x${expectedPc.toString(16)}\n" +
              s"  -> DUT Produced PC:   0x${receivedPc.toString(16)}"
          )
        }
        true
    }

          if (verificationPassed) {
              memory.get(receivedPc) match {
                // Case 1: 无条件跳转。
                case Some(BranchIntent(_, target, false)) =>
                  expectedPc = target
                  ParallaxLogger.debug(s"[GOLDEN MODEL] Verified 0x${receivedPc.toString(16)}. Unconditional Branch. Next expected PC: 0x${target.toString(16)}")

                // Case 2: 条件跳转。我们需要查询BPU。
                case Some(BranchIntent(_, target, true)) =>
                  bpu.get(receivedPc) match {
                    // Case 2a: BPU命中，且预测跳转。
                    case Some((bpuTarget, true)) =>
                      expectedPc = bpuTarget
                      ParallaxLogger.debug(s"[GOLDEN MODEL] Verified 0x${receivedPc.toString(16)}. Conditional Branch, BPU predicts TAKEN. Next expected PC: 0x${bpuTarget.toString(16)}")
                    
                    // Case 2b: BPU命中，但预测不跳转。
                    case Some((_, false)) =>
                      expectedPc = receivedPc + 4
                      ParallaxLogger.debug(s"[GOLDEN MODEL] Verified 0x${receivedPc.toString(16)}. Conditional Branch, BPU predicts NOT-TAKEN. Next expected PC: 0x${(receivedPc + 4).toString(16)}")

                    // Case 2c: BPU Miss (没有记录)。默认预测不跳转。
                    case None =>
                      expectedPc = receivedPc + 4
                      ParallaxLogger.debug(s"[GOLDEN MODEL] Verified 0x${receivedPc.toString(16)}. Conditional Branch, BPU MISS (default to NOT-TAKEN). Next expected PC: 0x${(receivedPc + 4).toString(16)}")
                  }

                // Case 3: 明确的顺序指令。
                case Some(SequentialIntent(_)) =>
                  expectedPc = receivedPc + 4
                  ParallaxLogger.debug(s"[GOLDEN MODEL] Verified 0x${receivedPc.toString(16)}. Sequential. Next expected PC: 0x${(receivedPc + 4).toString(16)}")

                // Case 4: 指令在内存中未定义 (Fuzzing时可能发生)。
                case None =>
                  assert(false, s"Golden Model has no information about PC=0x${receivedPc.toString(16)}. DUT is executing in unknown memory.")
              }
          }
  }

  /**
   * 应用一个新的激励。
   * 这个方法负责处理所有会*立即改变*程序流向的事件，比如硬重定向。
   */
  def applyStimulus(stimulus: FetchStimulus, simCycle: Long): Unit = {
    this.lastAppliedStimulus = stimulus
    this.lastStimulusCycle = simCycle

    // 更新BPU状态
    if (stimulus.doBpuUpdate) {
      bpu = bpu.updated(stimulus.bpuUpdatePc, (stimulus.bpuUpdateTarget, stimulus.bpuUpdateTaken))
    }

    // *** 关键修复：硬重定向的处理方式变化 ***
    if (stimulus.doHardRedirect) {
      // 当发生硬重定向时，模型进入“等待硬重定向目标PC”的状态
      awaitingHardRedirectPc = Some(stimulus.hardRedirectTarget)
      // expectedPc 在这里不直接更新，它将由 awaitingHardRedirectPc 状态管理
      ParallaxLogger.debug(s"[GOLDEN MODEL] Initiating Hard Redirect to 0x${stimulus.hardRedirectTarget.toString(16)} @ cycle $simCycle")
    }
  }

  def reset(): Unit = {
    expectedPc = pCfg.resetVector
    bpu = Map()
    lastAppliedStimulus = FetchStimulus()
    lastStimulusCycle = 0
    awaitingHardRedirectPc = None // 确保复位时清除
  }

  def fillMemory(intents: Map[BigInt, InstructionIntent]): Unit = { this.memory = intents }
}


object StimulusGenerator {
  val rand = new scala.util.Random(2333)
  
  val SRAM_MIN_ADDR: BigInt = 0x0
  val SRAM_MAX_ADDR: BigInt = 0x4000

  def randomPcInSramRange(): BigInt = {
    val numAddresses = (SRAM_MAX_ADDR - SRAM_MIN_ADDR) / 4
    SRAM_MIN_ADDR + (rand.nextInt(numAddresses.toInt) * 4)
  }

  // 生成一次性的脉冲激励 (硬重定向或BPU更新)，确保目标在SRAM范围内
  def generatePulseStimulusInSramRange(): FetchStimulus = {
    val dice = rand.nextInt(100)
    if (dice < 5) { // 5% 概率发起硬重定向
      FetchStimulus(doHardRedirect = true, hardRedirectTarget = randomPcInSramRange())
    } else if (dice < 15) { // 10% 概率更新BPU
      FetchStimulus(
        doBpuUpdate = true,
        bpuUpdatePc = randomPcInSramRange(),
        bpuUpdateTarget = randomPcInSramRange(),
        bpuUpdateTaken = rand.nextBoolean()
      )
    } else { // 80% 概率无操作
      FetchStimulus()
    }
  }

    def generateInstructionMemory(
      maxInstructions: BigInt,
      branchRatio: Double,
      directBranchRatio: Double // 新增参数: 直接跳转的比例
  ): (Map[BigInt, InstructionIntent], Map[BigInt, BigInt]) = {
    val intents = mutable.Map[BigInt, InstructionIntent]()
    val asms = mutable.Map[BigInt, BigInt]()

    for (i <- 0 until maxInstructions.toInt) {
      val addr = SRAM_MIN_ADDR + (i * 4)
      if (addr >= SRAM_MAX_ADDR) {
        return (intents.toMap, asms.toMap)
      }

      val dice = rand.nextDouble()
      if (dice < directBranchRatio) { // 生成直接跳转 (B指令)
        val targetAddr = randomPcInSramRange()
        val offset = targetAddr - addr
        // LA32R的B指令偏移量是26位，范围更大
        if (offset >= -(1 << 27) && offset < (1 << 27) && (offset & 0x3) == 0) {
          intents(addr) = BranchIntent(addr, targetAddr, isConditional = false)
          asms(addr) = b(offset.toInt)
        } else {
          intents(addr) = SequentialIntent(addr)
          asms(addr) = nop() // Fallback to NOP
        }
      } else if (dice < branchRatio) { // 生成条件跳转 (BEQ指令)
        val targetAddr = randomPcInSramRange()
        val offset = targetAddr - addr
        // BEQ指令偏移量范围较小
        if (offset >= -32768 * 2 && offset <= 32767 * 2 && (offset & 0x3) == 0) {
          intents(addr) = BranchIntent(addr, targetAddr, isConditional = true)
          asms(addr) = beq(0, 0, offset = offset.toInt)
        } else {
          intents(addr) = SequentialIntent(addr)
          asms(addr) = nop() // Fallback to NOP
        }
      } else { // 生成顺序指令
        intents(addr) = SequentialIntent(addr)
        asms(addr) = addi_w(rd = rand.nextInt(32), rj = rand.nextInt(32), imm = rand.nextInt(2048) - 1024)
      }

      println(f"Instruction at 0x${addr.toString(16)}: Intent is ${intents(addr).toString} assembly is ${asms(addr).toString(16)}")
    }
    (intents.toMap, asms.toMap)
  }
}
