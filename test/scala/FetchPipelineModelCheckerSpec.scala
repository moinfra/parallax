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
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(50)
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
        val maxInstructionsToVerify = 5000
        val helper = new AsyncStimulusHelper(dut, pCfg)
        val model = new FetchGoldenModel(pCfg)
        // 确保生成的指令内存大小不超过SRAM范围
        val (instructionIntents, instructionEncodings) = StimulusGenerator.generateInstructionMemory(
          (StimulusGenerator.SRAM_MAX_ADDR - StimulusGenerator.SRAM_MIN_ADDR) / 4, // 最大可容纳的指令数
          0.3
        )

        helper.initSignals()
        // 写入指令到SRAM，从SRAM_MIN_ADDR开始
        helper.blockingWriteInstructionsToMem(StimulusGenerator.SRAM_MIN_ADDR, instructionEncodings.toSeq.sortBy(_._1).map(_._2))
        model.fillMemory(instructionIntents)
        model.reset()

        // --- 2. Fuzzing Loop ---
        var verifiedInstructionCount = 0
        var cycles = 0
        val maxCycles = maxInstructionsToVerify * 200

        var stallCyclesLeft = 0
        var disableCyclesLeft = 0
        var stimulus = FetchStimulus() // 当前周期的激励

        ParallaxLogger.info(s"[ModelChecker] Starting single-tick synchronous verification...")

        // 在循环外只做一次启动操作
        // 确保重定向目标在SRAM范围内
        stimulus = FetchStimulus(doHardRedirect = true, hardRedirectTarget = StimulusGenerator.randomPcInSramRange())
        
        while (verifiedInstructionCount < maxInstructionsToVerify && cycles < maxCycles) {
            
            // --- A. 激励应用 (为即将到来的时钟沿设置所有输入) ---
            model.applyStimulus(stimulus, cycles)
            helper.applyStimulus(stimulus)
            
            // 应用由循环管理的持续性激励
            dut.io.fetchOutput.ready #= (stallCyclesLeft == 0)
            dut.io.fetchDisable #= (disableCyclesLeft > 0)

            // --- B. 时间前进 (整个循环中唯一的waitSampling) ---
            cd.waitSampling(1)
            cycles += 1
            ParallaxLogger.debug(s"[ModelChecker] Cycle $cycles: $stimulus")

            // --- C. 验证 (检查时钟沿之后稳定下来的信号) ---
            if (dut.io.fetchOutput.valid.toBoolean && dut.io.fetchOutput.ready.toBoolean) {
                val receivedPc = dut.io.fetchOutput.payload.pc.toBigInt
                ParallaxLogger.debug(s"[ModelChecker] Valid fetch output $receivedPc @ cycle $cycles")
                model.consumeAndVerify(receivedPc, cycles)
                ParallaxLogger.success(s"[ModelChecker] Verified PC=0x${receivedPc.toString(16)} @ cycle $cycles")
                verifiedInstructionCount += 1
            } else {
              ParallaxLogger.debug(s"[ModelChecker] Skipping cycle $cycles: fetchOutput.valid=${dut.io.fetchOutput.valid.toBoolean}, fetchOutput.ready=${dut.io.fetchOutput.ready.toBoolean}")
            }

            // --- D. 准备下一周期的激励 ---
            
            // 首先，将脉冲激励重置为Idle
            stimulus = FetchStimulus()

            // 更新持续性激励的倒计时
            if (stallCyclesLeft > 0) stallCyclesLeft -= 1
            if (disableCyclesLeft > 0) disableCyclesLeft -= 1

            // 如果当前没有持续性激励，则有概率生成新的激励
            if (stallCyclesLeft == 0 && disableCyclesLeft == 0) {
                val dice = StimulusGenerator.rand.nextInt(100)
                if (dice < 15) { // 脉冲激励
                    // 确保生成的激励目标在SRAM范围内
                    stimulus = StimulusGenerator.generatePulseStimulusInSramRange()
                } else if (dice < 25) { // stall
                    stallCyclesLeft = StimulusGenerator.rand.nextInt(5) + 1
                } else if (dice < 35) { // disable
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
    awaitingHardRedirectPc match {
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
        expectedPc = receivedPc + 4 // 硬重定向后，下一条指令就是顺序的
        ParallaxLogger.debug(s"[GOLDEN MODEL] Hard Redirect (0x${targetPc.toString(16)}) received. Next expected PC: 0x${expectedPc.toString(16)}")

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

        // 验证通过后，根据正常指令流处理来计算下一个期望的PC
        memory.get(receivedPc) match {
          case Some(intent: BranchIntent) if bpu.get(receivedPc).exists(_._2) =>
            {
              println("[GOLDEN MODEL] new intent: " + intent.fmt)
              expectedPc = bpu(receivedPc)._1 // BPU预测跳转
              }
          case _ =>
            expectedPc = receivedPc + 4 // 顺序执行
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
      maxInstructions: BigInt, // 传入最大指令数，而不是固定的pcAddrSpaceSize
      branchRatio: Double
  ): (Map[BigInt, InstructionIntent], Map[BigInt, BigInt]) = {
    val intents = mutable.Map[BigInt, InstructionIntent]()
    val encodings = mutable.Map[BigInt, BigInt]()

    // 从SRAM_MIN_ADDR开始填充指令
    for (i <- 0 until maxInstructions.toInt) {
      val addr = SRAM_MIN_ADDR + (i * 4)
      // 确保生成的地址在SRAM范围内
      if (addr >= SRAM_MAX_ADDR) {
        assert(false, s"Exceeded SRAM memory limit at address 0x${addr.toString(16)}. Stopping instruction generation.")
        return (intents.toMap, encodings.toMap)
      }

      if (rand.nextDouble() < branchRatio) {
        val targetAddr = randomPcInSramRange() // 确保分支目标在SRAM范围内
        val offset = targetAddr - addr
        // 确保偏移量在beq指令的范围内，且是4字节对齐
        // LA32R的beq指令偏移量是16位有符号数，表示字数，所以实际字节偏移量是 offset * 2
        // 范围是 -32768*2 到 32767*2
        if (offset >= -32768 * 2 && offset <= 32767 * 2 && (offset & 0x3) == 0) {
          intents(addr) = BranchIntent(addr, targetAddr, isConditional = true)
          encodings(addr) = beq(0, 0, offset = offset.toInt)
        } else {
          // 如果无法生成合法的分支指令，则生成nop或addi_w
          intents(addr) = SequentialIntent(addr)
          val safeImm = (addr.toInt & 0x7ff) - 1024
          encodings(addr) = addi_w(rd = 1, rj = 1, imm = safeImm)
        }
      } else {
        intents(addr) = SequentialIntent(addr)
        val safeImm = (addr.toInt & 0x7ff) - 1024
        encodings(addr) = addi_w(rd = 1, rj = 1, imm = safeImm)
      }
    }
    (intents.toMap, encodings.toMap)
  }
}
