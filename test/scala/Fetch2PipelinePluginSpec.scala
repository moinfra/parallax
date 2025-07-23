package test.scala

// filename: test/scala/fetch2/FetchPipelinePluginSpec.scala
// cmd: testOnly test.scala.FetchPipelinePluginSpec

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch2._ // Use the new fetch2 package
import parallax.bpu._
import parallax.components.memory._
import parallax.utilities._
// import parallax.components.dcache2._ // 移除对DataCache的引用
import parallax.fetch.icache.{ICachePlugin, ICacheConfig} // 导入我们自己的ICache
import spinal.lib.bus.amba4.axi.Axi4Config
import LA32RInstrBuilder._
import scala.collection.mutable
import parallax.components.bpu.BpuPipelinePlugin
import parallax.fetch.FetchedInstr

// =========================================================================
//  Test Bench Setup
// =========================================================================

/** Test IO bundle for the FetchPipeline. */
case class Fetch2PipelineTestBenchIo(pCfg: PipelineConfig) extends Bundle {
  // --- To DUT ---
  val bpuUpdate = slave(Stream(BpuUpdate(pCfg)))
  val hardRedirect = slave(Flow(UInt(pCfg.pcWidth)))
  val fetchDisable = in Bool ()

  // --- From DUT ---
  val fetchOutput = master(Stream(FetchedInstr(pCfg)))
}

/** A test-only plugin connecting services to the top-level IOs. */
class Fetch2TestSetupPlugin(io: Fetch2PipelineTestBenchIo) extends Plugin {
  val setup = create early new Area {
    ParallaxLogger.debug("Setting up test-only plugin")
    // Get handles to the real services
    val bpuService = getService[BpuService]
    val fetchService = getService[FetchService] // Use the new FetchService trait
    // val dcService = getService[DataCacheService] // DataCacheService 不再需要了

    // 所有申请端口的操作放在 early
    val bpuUpdatePort = bpuService.newBpuUpdatePort(); ParallaxLogger.debug("Got bpuUpdatePort")
    val hardRedirectPort = fetchService.newHardRedirectPort(0); ParallaxLogger.debug("Got hardRedirectPort")
    val fetchDisablePort = fetchService.newFetchDisablePort(); ParallaxLogger.debug("Got fetchDisablePort")
    val fetchOutputPort = fetchService.fetchOutput(); ParallaxLogger.debug("Got fetchOutputPort")
    // val unusedStorePort = dcService.newStorePort() ; ParallaxLogger.debug("Got unusedStorePort") // 移除对DataCache端口的引用

    ParallaxLogger.debug("Leaving test-only plugin setup")

    bpuService.retain()
    fetchService.retain()
    // dcService.retain() // 移除对DataCacheService的retain
  }

  val logic = create late new Area {
    println("Creating test-only plugin logic")

    setup.bpuUpdatePort <> io.bpuUpdate

    // Connect FetchService ports
    setup.hardRedirectPort <> io.hardRedirect
    setup.fetchDisablePort <> io.fetchDisable
    io.fetchOutput <> setup.fetchOutputPort

    // Terminate unused store port // 移除对DataCache端口的终止
    // setup.unusedStorePort.cmd.setIdle()
    println("Leaving test-only plugin logic")

    setup.bpuService.release()
    setup.fetchService.release()
    // setup.dcService.release() // 移除对DataCacheService的release
  }
}

/** The top-level DUT for testing FetchPipelinePlugin. */
class Fetch2PipelineTestBench(
    val pCfg: PipelineConfig,
    val iCfg: ICacheConfig, // 使用 ICacheConfig
    val axiCfg: Axi4Config
) extends Component {
  val io = Fetch2PipelineTestBenchIo(pCfg)
  io.simPublic()

  val framework = new Framework(
    Seq(
      // new DataCachePlugin(dCfg), // 移除 DCache
      new ICachePlugin(iCfg, axiCfg, pCfg.pcWidth.value), // 添加 ICache
      new TestOnlyMemSystemPlugin(axiConfig = axiCfg),
      new BpuPipelinePlugin(pCfg),
      new FetchPipelinePlugin(pCfg, iCfg), // 修改 FetchPipelinePlugin 的构造参数，传入 ICacheConfig
      new Fetch2TestSetupPlugin(io)
    )
  )
}

// =========================================================================
//  Test Helper
// =========================================================================

// A clone of FetchedInstr for safe monitoring
case class FetchedInstrCapture(
    pc: BigInt,
    instruction: BigInt,
    isBranch: Boolean,
    isJump: Boolean,
    predictedTaken: Boolean
)
object FetchedInstrCapture {
  def apply(payload: FetchedInstr): FetchedInstrCapture = {
    FetchedInstrCapture(
      payload.pc.toBigInt,
      payload.instruction.toBigInt,
      payload.predecode.isBranch.toBoolean,
      payload.predecode.isDirectJump.toBoolean,
      payload.bpuPrediction.isTaken.toBoolean
    )
  }
}

/** Helper class for verifying fetch pipeline outputs */
class Fetch2TestHelper(dut: Fetch2PipelineTestBench)(implicit cd: ClockDomain) {
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
  val receivedInstrs = mutable.Queue[FetchedInstrCapture]()

  private var realtimeCheckEnabled = false
  private var expectedNextPc: Option[BigInt] = None

  def init(): Unit = {
    dut.io.bpuUpdate.valid #= false
    dut.io.hardRedirect.valid #= false
    dut.io.fetchDisable #= false
    dut.io.fetchOutput.ready #= false // Start with the backend stalled
    realtimeCheckEnabled = false
    expectedNextPc = None
    cd.waitSampling()
  }

  def startMonitor(): Unit = {
    StreamMonitor(dut.io.fetchOutput, cd) { payload =>
      val received = FetchedInstrCapture(payload)

      // ==================== 新增/修改 开始 ====================
      // --- 实时检查逻辑 ---
      if (realtimeCheckEnabled) {
        expectedNextPc match {
          case Some(expectedPc) =>
            // 使用 fork 在独立的线程中执行断言，避免阻塞仿真器主线程
            fork {
              assert(
                received.pc == expectedPc,
                s"REALTIME-CHECK FAILED: PC out of order! Expected 0x${expectedPc.toString(16)}, but got 0x${received.pc
                    .toString(16)} at cycle ${simTime()}."
              )
            }
            // 更新下一个期望的PC
            expectedNextPc = Some(received.pc + 4)

          case None =>
            // 这是第一次收到指令，我们用它来初始化期望的PC
            expectedNextPc = Some(received.pc + 4)
        }
      }
      // ==================== 新增/修改 结束 ====================

      // 原始功能保持不变：将指令入队
      receivedInstrs.enqueue(received)
    }
  }

  /** 启用实时顺序PC检查，并设置期望的第一个PC。
    * @param firstExpectedPc 期望收到的第一条指令的PC地址。
    */
  def enableRealtimeSequentialCheck(firstExpectedPc: BigInt): Unit = {
    realtimeCheckEnabled = true
    expectedNextPc = Some(firstExpectedPc)
    // 清空队列，为新的检查序列做准备
    receivedInstrs.clear()
  }

  /** 禁用实时检查。
    * 在需要非顺序检查（如跳转后）或使用expectInstr时调用。
    */
  def disableRealtimeCheck(): Unit = {
    realtimeCheckEnabled = false
    expectedNextPc = None
  }

  def clearBuffer(): Unit = {
    receivedInstrs.clear()
  }

  def writeInstructionsToMem(address: BigInt, instructions: Seq[BigInt]): Unit = {
    for ((inst, i) <- instructions.zipWithIndex) {
      val currentAddr = address + (i * 4)
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= currentAddr
      sram.io.tb_writeData #= inst
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(5)
  }

  def issueHardRedirect(newPc: BigInt): Unit = {
    dut.io.hardRedirect.valid #= true
    dut.io.hardRedirect.payload #= newPc
    cd.waitSampling()
    dut.io.hardRedirect.valid #= false
  }

  def setFetchDisable(disable: Boolean): Unit = {
    dut.io.fetchDisable #= disable
  }

  def updateBpu(pc: BigInt, target: BigInt, isTaken: Boolean): Unit = {
    dut.io.bpuUpdate.valid #= true
    dut.io.bpuUpdate.payload.pc #= pc
    dut.io.bpuUpdate.payload.target #= target
    dut.io.bpuUpdate.payload.isTaken #= isTaken
    cd.waitSampling()
    dut.io.bpuUpdate.valid #= false
  }

  def expectInstr(
      expectedPc: BigInt,
      expectedInst: BigInt,
      expectedIsBranch: Boolean = false,
      expectedIsJump: Boolean = false,
      predictedTaken: Option[Boolean] = None,
      timeout: Int = 100
  ): Unit = {
    dut.io.fetchOutput.ready #= true
    val failed = cd.waitSamplingWhere(timeout = timeout) {
      if (receivedInstrs.nonEmpty) {
        val peeked = receivedInstrs.front
        // If we have a specific PC expectation, wait for it. Otherwise, take the first available.
        peeked.pc == expectedPc
      } else {
        false
      }
    }
    if (failed) {
      assert(false, s"Timed out waiting for PC 0x${expectedPc.toString(16)}")
    }
    dut.io.fetchOutput.ready #= false

    val received = receivedInstrs.dequeue()
    assert(
      received.pc == expectedPc,
      f"PC Mismatch! Expected 0x${expectedPc.toString(16)}, Got 0x${received.pc.toString(16)}"
    )
    assert(
      received.instruction == expectedInst,
      f"Instruction Mismatch! Expected 0x${expectedInst.toString(16)}, Got 0x${received.instruction.toString(16)}"
    )
    assert(
      received.isBranch == expectedIsBranch,
      s"isBranch Mismatch! For PC 0x${received.pc.toString(16)}, Expected $expectedIsBranch, Got ${received.isBranch}"
    )
    assert(
      received.isJump == expectedIsJump,
      s"isJump Mismatch! For PC 0x${received.pc.toString(16)}, Expected $expectedIsJump, Got ${received.isJump}"
    )
    predictedTaken.foreach { expected =>
      assert(
        received.predictedTaken == expected,
        s"BPU Prediction Mismatch! For PC 0x${received.pc.toString(16)}, Expected $expected, Got ${received.predictedTaken}"
      )
    }
  }

  def expectNoOutput(duration: Int): Unit = {
    val initialCount = receivedInstrs.size
    cd.waitSampling(duration)
    val finalCount = receivedInstrs.size
    assert(initialCount == finalCount, s"Expected no instructions, but received ${finalCount - initialCount}")
  }
}

// =========================================================================
//  Test Suite
// =========================================================================

class FetchPipelinePluginSpec extends CustomSpinalSimFunSuite {

  val pCfg =
    PipelineConfig(fetchWidth = 4, resetVector = 0x1000, bpuTransactionIdWidth = 3 bit, fetchGenIdWidth = 2 bit)
  // 定义我们自己的 ICache 配置
  val iCfg = ICacheConfig(
    totalSize = 4 * 1024,
    ways = 2,
    bytesPerLine = 16, // 16 bytes = 4 instructions
    fetchWidth = pCfg.fetchWidth,
    enableLog = false
  )
  val axiConfig = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 4)

  test("Fetch2 - Single Instruction Fetch") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 写入一条指令到复位向量地址
      helper.writeInstructionsToMem(0x1000, Seq(nop()))
      helper.startMonitor()

      // 期望收到这一条指令
      helper.expectInstr(0x1000, nop())

      // 之后不应该有任何输出
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Fetch Across Cache Line Boundary") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // iCfg.bytesPerLine = 16 (4 instructions)
      // Line 1: 0x1000 to 0x100F
      // Line 2: 0x1010 to 0x101F
      // 我们写入6条指令，跨越一个边界
      val insts = (0 until 6).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x1000, insts)
      helper.startMonitor()

      // 期望按顺序收到这6条指令
      for (i <- 0 until 6) {
        helper.expectInstr(0x1000 + i * 4, addi_w(1, 1, i))
      }

      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Start Fetch from Unaligned PC") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 在 0x1000 写入一个完整的 cache line (4条指令)
      val insts = Seq(nop(), addi_w(1, 1, 1), addi_w(2, 2, 2), addi_w(3, 3, 3))
      helper.writeInstructionsToMem(0x1000, insts)

      helper.startMonitor()

      // 发出一个硬重定向到 0x1008 (第三条指令)
      helper.issueHardRedirect(0x1008)

      // 应该从 0x1008 开始取指
      helper.expectInstr(0x1008, addi_w(2, 2, 2))
      // 然后是同一行中的下一条指令
      helper.expectInstr(0x100c, addi_w(3, 3, 3))

      // 之后流水线会继续取下一行，但内存中没有指令，所以不会有输出
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Sequential Fetch") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      val insts = (0 until 8).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x1000, insts)

      helper.startMonitor()

      for (i <- 0 until 8) {
        helper.expectInstr(0x1000 + i * 4, addi_w(1, 1, i), timeout = 50)
      }
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Hard Redirect") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      helper.writeInstructionsToMem(0x1000, Seq(nop(), nop(), nop(), nop()))
      helper.writeInstructionsToMem(0x2000, Seq(b(1), nop()))

      helper.startMonitor()

      helper.expectInstr(0x1000, nop())

      helper.issueHardRedirect(0x2000)

      helper.expectInstr(0x2000, b(1), expectedIsJump = true)
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - BPU Prediction Taken (Soft Redirect)") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // Predict branch at 0x1004 is taken to 0x3000
      helper.updateBpu(pc = 0x1004, target = 0x3000, isTaken = true)

      helper
        .writeInstructionsToMem(0x1000, Seq(nop(), beq(1, 2, 8), addi_w(1, 1, 1), addi_w(2, 2, 2))) // Sequential path
      helper.writeInstructionsToMem(0x3000, Seq(b(0), nop())) // Target path

      helper.startMonitor()

      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // Next instruction MUST be from the predicted target
      helper.expectInstr(0x3000, b(0), expectedIsJump = true, timeout = 100)

      helper.expectNoOutput(500)
    }
  }

  test("Fetch2 - BPU vs Hard Redirect (Hard Wins)") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      helper.updateBpu(pc = 0x1004, target = 0x3000, isTaken = true)
      helper.writeInstructionsToMem(0x1000, Seq(nop(), beq(1, 2, 8)))
      helper.writeInstructionsToMem(0x3000, Seq(nop()))
      helper.writeInstructionsToMem(0x3090, Seq(b(0)))

      helper.startMonitor()

      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // At this point, a soft redirect to 0x3000 is pending.
      // Issue a hard redirect to 0x3090 immediately.
      helper.issueHardRedirect(0x3090)

      // The hard redirect must win.
      helper.expectInstr(0x3090, b(0), expectedIsJump = true)
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Stall and Resume") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      val insts = (0 until 16).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x1000, insts)
      helper.startMonitor()

      // Stall the backend and wait for FIFOs to fill
      dut.io.fetchOutput.ready #= false
      helper.expectNoOutput(100)

      // Unstall and consume all
      for (i <- 0 until 16) {
        helper.expectInstr(0x1000 + i * 4, addi_w(1, 1, i))
      }
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Fetch Disable") {
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      val insts = (0 until 8).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x1000, insts)
      helper.startMonitor()

      // Consume first two instructions
      helper.expectInstr(0x1000, addi_w(1, 1, 0))
      helper.expectInstr(0x1004, addi_w(1, 1, 1))

      // Disable fetch
      helper.setFetchDisable(true)
      helper.expectNoOutput(50)

      // Re-enable fetch
      helper.setFetchDisable(false)

      // Expect remaining instructions
      for (i <- 2 until 8) {
        helper.expectInstr(0x1000 + i * 4, addi_w(1, 1, i))
      }
    }
  }

  test("Fetch2 - Correctly recovers from ICache miss retry") {
    // 这个测试专门验证之前修复的 fetchPcReg 过度超前的问题。
    // 1. 取指 0x1000 (发生miss)，流水线投机地将PC设置为 0x1010
    // 2. 取指 0x1010 (发生miss)，流水线投机地将PC设置为 0x1020
    // 3. ICache 对 0x1000 的请求返回 'redo'
    // 4. 重试逻辑必须启动。它应该重新请求 0x1000，并且【必须】将 fetchPcReg 从 0x1020 纠正回 0x1010。
    // 5. 当 0x1000 的取指成功后，下一次取指的目标应该是 0x1010，而不是被跳过。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 写入两个完整的 cache line 的指令
      val insts1 = (0 until 4).map(i => addi_w(1, 1, i))
      val insts2 = (4 until 8).map(i => addi_w(2, 2, i))
      helper.writeInstructionsToMem(0x1000, insts1)
      helper.writeInstructionsToMem(0x1010, insts2)

      helper.startMonitor()

      // 关键在于流水线会背靠背地发出这两个取指请求。
      // 对每个 line 的首次访问都会是 cache miss，从而触发重试逻辑。
      // 我们期望能按顺序收到全部8条指令，没有任何指令被跳过。
      for (i <- 0 until 8) {
        val pc = 0x1000 + i * 4
        val inst = if (i < 4) addi_w(1, 1, i) else addi_w(2, 2, i)
        helper.expectInstr(pc, inst, timeout = 200)
      }

      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Unaligned branch with soft redirect discards remainder of fetch group") {
    // 测试当一个取指组（FetchGroup）中间包含一个被预测为跳转的分支时，
    // SmartDispatcher 是否能正确地丢弃该分支之后的所有指令。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // fetchWidth 是 4, 所以 0x1000-0x100C 是一个取指组。
      // 我们在组的中间 (0x1008) 放置一个分支指令。
      val insts_line1 = Seq(nop(), nop(), beq(1, 2, 8), addi_w(7, 7, 7))
      helper.writeInstructionsToMem(0x1000, insts_line1)

      // 分支的目标地址
      helper.writeInstructionsToMem(0x2080, Seq(b(0)))

      // 预测该分支会成功跳转
      helper.updateBpu(pc = 0x1008, target = 0x2080, isTaken = true)

      helper.startMonitor()

      // 期望收到分支前的指令
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, nop())
      // 期望收到分支指令本身
      helper.expectInstr(0x1008, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 位于 0x100C 的指令 (addi_w) 应该被丢弃，下一条指令必须来自跳转目标。
      helper.expectInstr(0x2080, b(0), expectedIsJump = true, timeout = 100)

      // 确认我们从未收到过那条被丢弃的指令
      assert(!helper.receivedInstrs.exists(_.pc == 0x100c), "Instruction after taken branch was not discarded!")

      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - Handles ICache miss on a BPU-predicted branch target") {
    // 测试软重定向 (soft redirect) 的目标地址发生 ICache miss 的情况。
    // 流水线应能正确处理这个 miss，暂停，然后从正确的目标地址恢复。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 分支在 0x1004, 预测跳转到 0x2080
      helper.updateBpu(pc = 0x1004, target = 0x2080, isTaken = true)

      // 原始路径上的指令
      helper.writeInstructionsToMem(0x1000, Seq(nop(), beq(1, 2, 8)))
      // 目标地址上的指令。对这里的首次访问将导致 cache miss。
      helper.writeInstructionsToMem(0x2080, Seq(addi_w(5, 5, 5)))

      helper.startMonitor()

      // 消费原始路径上的指令，直到分支指令
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 此时，流水线重定向到 0x2080, 这会引发 miss 和 retry。
      // helper 会等待直到 stall 结束，并最终收到正确的指令。
      helper.expectInstr(0x2080, addi_w(5, 5, 5), timeout = 200)

      helper.expectNoOutput(50)
    }
  }

  // =========================================================================
  //  Priority 1 Test Cases
  // =========================================================================

  test("Fetch2 - P1 - HardRedirect_Interrupts_SoftRedirect") {
    // 优先级1: 硬重定向必须能中断一个正在处理中的软重定向。
    // 场景:
    // 1. BPU预测PC 0x1004处的beq会跳转到0x3000 (软重定向)。
    // 2. Dispatcher在处理完beq后，会发出软重定向请求。
    // 3. 在软重定向生效的同一周期或紧接着的周期，我们注入一个更高优先级的硬重定向到0x3080。
    // 预期: 流水线最终必须稳定在0x3080，来自0x3000的指令绝不能被输出。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 设置BPU进行软重定向
      helper.updateBpu(pc = 0x1004, target = 0x3000, isTaken = true)

      // 写入指令
      helper.writeInstructionsToMem(0x1000, Seq(nop(), beq(1, 2, 8))) // 初始路径
      helper.writeInstructionsToMem(0x3000, Seq(addi_w(1, 1, 1))) // 软重定向的目标 (不应执行)
      helper.writeInstructionsToMem(0x3080, Seq(addi_w(2, 2, 2))) // 硬重定向的目标 (必须执行)

      helper.startMonitor()

      // 消费到分支指令，触发软重定向
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 关键: 在软重定向即将生效时，立即注入硬重定向
      // softRedirect 信号在 expectInstr 返回后变为 valid，下一周期 dispatchAnyFlushReg 生效
      // 我们在此时注入硬重定向，它的优先级更高，应该会胜出。
      helper.issueHardRedirect(0x3080)

      // 期望收到的下一条指令必须来自硬重定向的目标
      helper.expectInstr(0x3080, addi_w(2, 2, 2), timeout = 100)

      // 确保没有收到来自软重定向路径的指令
      assert(
        !helper.receivedInstrs.exists(_.pc == 0x3000),
        "Instruction from soft redirect target was incorrectly fetched!"
      )
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - P1 - Redirect_To_CacheMiss_Address") {
    // 优先级1: 验证重定向到一个导致ICache Miss的地址时，流水线能正确处理。
    // 场景:
    // 1. 正常执行到0x1000。
    // 2. 发出一个硬重定向到地址0x2004，该地址从未被访问过，确保会发生ICache Miss。
    // 3. 流水线应停顿，等待ICache填充数据并重试。
    // 4. 重试成功后，应能正确地从0x2004取到指令。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 写入指令
      helper.writeInstructionsToMem(0x1000, Seq(nop())) // 初始路径
      helper.writeInstructionsToMem(0x2004, Seq(addi_w(5, 5, 5))) // 重定向的目标 (会Miss)

      helper.startMonitor()

      // 消费初始指令
      helper.expectInstr(0x1000, nop())

      // 重定向到会导致Miss的地址
      helper.issueHardRedirect(0x2004)

      // 流水线会经历 stall -> retry -> success 的过程。
      // expectInstr 内部的超时机制会覆盖这个停顿时间。
      // 我们期望最终能正确收到目标指令。
      helper.expectInstr(0x2004, addi_w(5, 5, 5), timeout = 200)

      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - P1 - Branch_At_EndOf_CacheLine") {
    // 优先级1: 测试分支指令位于Cache Line末尾的边界情况。
    // 场景:
    // 1. Cache Line大小为16字节（4条指令）。
    // 2. 我们在地址 0x100C (line 0x1000-0x100F 的最后一条指令) 放置一个分支。
    // 3. BPU预测该分支会跳转到 0x1904。
    // 4. 下一个Cache Line (0x1010) 也填充指令。
    // 预期: 在输出0x100C的分支指令后，下一条指令必须来自跳转目标0x1904，
    //       而绝不能是顺序执行的0x1010。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 设置BPU进行软重定向
      helper.updateBpu(pc = 0x100c, target = 0x1904, isTaken = true)

      // 写入指令
      // Line 1 (0x1000 - 0x100F)
      helper.writeInstructionsToMem(
        0x1000,
        Seq(
          nop(), // 0x1000
          nop(), // 0x1004
          nop(), // 0x1008
          beq(1, 2, 8) // 0x100C <-- 分支在行末
        )
      )
      // Line 2 (0x1010 - 0x101F)
      helper.writeInstructionsToMem(0x1010, Seq(addi_w(9, 9, 9))) // 错误路径的指令
      // 跳转目标
      helper.writeInstructionsToMem(0x1904, Seq(addi_w(6, 6, 6))) // 正确路径的指令

      helper.startMonitor()

      // 消费到行末的分支指令
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, nop())
      helper.expectInstr(0x1008, nop())
      helper.expectInstr(0x100c, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 期望下一条指令来自跳转目标
      helper.expectInstr(0x1904, addi_w(6, 6, 6), timeout = 50)

      // 确认没有收到来自错误顺序路径的指令
      assert(
        !helper.receivedInstrs.exists(_.pc == 0x1010),
        "Instruction from next cache line was incorrectly fetched after taken branch!"
      )
      helper.expectNoOutput(50)
    }
  }

  // =========================================================================
  //  Priority 2 Test Cases
  // =========================================================================

  test("Fetch2 - P2 - HardRedirect_During_BackendStall") {
    // 优先级2: 验证在后端完全停顿(反压)的情况下，硬冲刷依然有效。
    // 场景:
    // 1. 让后端停止接收指令 (fetchOutput.ready = false)。
    // 2. 流水线会继续取指，直到所有内部FIFO (predecodedGroups, fetchOutput) 都被填满，导致整个取指流水线停顿。
    // 3. 在这个完全停滞的状态下，注入一个硬重定向到0x2000。
    // 4. 重新让后端接收指令 (fetchOutput.ready = true)。
    // 预期: 所有FIFO中缓存的旧指令应被清除，输出的第一条指令必须来自硬重定向的新地址0x2000。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 写入大量指令以确保能填满FIFO
      val insts = (0 until 16).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x1000, insts)
      helper.writeInstructionsToMem(0x2000, Seq(addi_w(9, 9, 9))) // 硬重定向的目标

      helper.startMonitor()

      // 1. 停止后端接收，让流水线填满并停顿
      dut.io.fetchOutput.ready #= false
      // 等待足够长的时间以确保所有FIFO饱和。
      // 流水线会取几条指令，然后停在s1或s4。
      cd.waitSampling(50)

      // 2. 在停滞状态下注入硬重定向
      helper.issueHardRedirect(0x2000)
      cd.waitSampling(5) // 等待冲刷信号传播

      // 3. 重新使能后端
      // 此时，所有旧数据(来自0x1000...)都应该被冲刷掉了。

      // 4. 期望收到的第一条指令来自新地址
      helper.expectInstr(0x2000, addi_w(9, 9, 9), timeout = 100)

      // 确认没有收到任何来自旧路径的指令
      assert(
        helper.receivedInstrs.isEmpty,
        s"Received unexpected instructions from old path after flush: ${helper.receivedInstrs.map(_.pc.toString(16)).mkString(", ")}"
      )

      helper.expectNoOutput(50)
    }
  }

  // =========================================================================
  //  Priority 2 Test Cases (REVISED)
  // =========================================================================

  test("Fetch2 - P2 - Redirect Chain (Predicted Branch -> Direct Jump)") {
    // 验证重定向链: 一个被BPU预测跳转的条件分支，其目标是另一个无条件直接跳转。
    // 场景:
    // 1. BEQ (在0x1004) 被BPU预测为跳转到0x2000。
    // 2. B (在0x2000) 是一个直接跳转指令，它不需要BPU预测，其目标(0x3000)可以直接从指令解码。
    // 预期:
    // - Dispatcher为0x1004查询BPU并根据预测结果(TAKEN)发出软重定向。
    // - Dispatcher处理0x2000时，识别为直接跳转，不查询BPU，直接根据解码结果发出软重定向。
    // - 最终指令流正确地从0x1000 -> 0x1004 -> 0x2000 -> 0x3000。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 只需为条件分支设置BPU
      helper.updateBpu(pc = 0x1004, target = 0x2000, isTaken = true)

      // 写入指令
      helper.writeInstructionsToMem(
        0x1000,
        Seq(
          nop(), // 0x1000
          beq(1, 2, 8), // 0x1004 -> 条件分支 (被预测)
          addi_w(1, 1, 1) // 0x1008 (错误路径)
        )
      )
      helper.writeInstructionsToMem(
        0x2000,
        Seq(
          b(4092), // 0x2000 -> 直接跳转到 0x3000 (0x2000 + 4096 - 4, 使用b指令), 目标是 0x3000 - 4
          addi_w(2, 2, 2) // 0x2004 (错误路径)
        )
      )
      helper.writeInstructionsToMem(
        0x2ffc,
        Seq(
          addi_w(3, 3, 3) // 0x2FFC (最终目标)
        )
      )

      helper.startMonitor()

      // 期望的指令序列
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 对于直接跳转，不检查BPU预测
      helper.expectInstr(0x2000, b(4092), expectedIsJump = true, timeout = 100)

      helper.expectInstr(0x2ffc, addi_w(3, 3, 3), timeout = 100)

      // 确认没有收到任何来自错误路径的指令
      assert(
        !helper.receivedInstrs.exists(i => i.pc == 0x1008 || i.pc == 0x2004),
        "Instruction from an intermediate wrong path was fetched!"
      )
      helper.expectNoOutput(50)
    }
  }

  test("Fetch2 - P2 - Redirect Chain (Predicted Branch -> Predicted Branch)") {
    // 验证重定向链: 一个被BPU预测跳转的条件分支，其目标是另一个被BPU预测跳转的条件分支。
    // 场景:
    // 1. BEQ (在0x1004) 被BPU预测为跳转到0x2000。
    // 2. BNE (在0x2000) 也被BPU预测为跳转到0x3000。
    // 预期:
    // - Dispatcher为两条分支都查询BPU，并都根据预测结果(TAKEN)发出软重定向。
    // - 整个流程平滑，没有错误路径的指令泄漏。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 为两个条件分支都设置BPU
      helper.updateBpu(pc = 0x1004, target = 0x2000, isTaken = true)
      helper.updateBpu(pc = 0x2000, target = 0x3000, isTaken = true)

      // 写入指令
      helper.writeInstructionsToMem(
        0x1000,
        Seq(
          nop(), // 0x1000
          beq(1, 2, 8), // 0x1004 -> 第一个条件分支
          addi_w(1, 1, 1) // 0x1008 (错误路径)
        )
      )
      helper.writeInstructionsToMem(
        0x2000,
        Seq(
          bne(3, 4, 12), // 0x2000 -> 第二个条件分支
          addi_w(2, 2, 2) // 0x2004 (错误路径)
        )
      )
      helper.writeInstructionsToMem(
        0x3000,
        Seq(
          addi_w(3, 3, 3) // 0x3000 (最终目标)
        )
      )

      helper.startMonitor()

      // 期望的指令序列
      helper.expectInstr(0x1000, nop())
      helper.expectInstr(0x1004, beq(1, 2, 8), expectedIsBranch = true, predictedTaken = Some(true))

      // 第二个分支也需要检查BPU预测
      helper.expectInstr(0x2000, bne(3, 4, 12), expectedIsBranch = true, predictedTaken = Some(true), timeout = 100)

      helper.expectInstr(0x3000, addi_w(3, 3, 3), timeout = 100)

      // 确认没有收到任何来自错误路径的指令
      assert(
        !helper.receivedInstrs.exists(i => i.pc == 0x1008 || i.pc == 0x2004),
        "Instruction from an intermediate wrong path was fetched!"
      )
      helper.expectNoOutput(50)
    }
  }

  // =========================================================================
  //  Priority 3 Test Cases
  // =========================================================================

  test("Fetch2 - P3 - Redirect_And_BpuUpdate_During_FetchDisable") {
    // 优先级3: 验证在 s1 发射被暂停 (fetchDisable) 期间，控制信号是否能被正确处理。
    // 场景:
    // 1. 正常取指，然后拉高 fetchDisable，暂停新的取指请求发射。
    // 2. 在暂停期间，做两件事：
    //    a. 注入一个硬重定向到 0x3000。
    //    b. 更新 BPU，使得 0x3004 处的分支被预测为跳转到 0x3080。
    // 3. 解除 fetchDisable。
    // 预期:
    // - 流水线恢复后，必须从硬重定向的地址 0x3000 开始取指。
    // - 当取到 0x3004 处的指令时，Dispatcher 必须使用我们在暂停期间更新的 BPU 信息进行预测。
    SimConfig.withWave.compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new Fetch2TestHelper(dut)
      helper.init()

      // 写入指令
      helper.writeInstructionsToMem(0x1000, Seq(nop(), nop())) // 初始路径
      helper.writeInstructionsToMem(
        0x3000,
        Seq(
          nop(), // 0x3000
          nop(), // 0x3004
          bne(1, 2, 4), // 0x3008 <-- 分支指令
          nop() // 0x300C
        )
      )
      helper.writeInstructionsToMem(0x3080, Seq(addi_w(7, 7, 7))) // BPU 预测目标

      helper.startMonitor()

      // 消费一条指令
      helper.expectInstr(0x1000, nop())

      // 1. 拉高 fetchDisable
      ParallaxLogger.info("[TB] Enabling fetchDisable.")
      helper.setFetchDisable(true)
      cd.waitSampling(5) // 等待，确保流水线前端停滞

      // 2. 在暂停期间注入控制信号
      ParallaxLogger.info("[TB] Issuing Hard Redirect and BPU Update while disabled.")
      helper.issueHardRedirect(0x3000)
      helper.updateBpu(pc = 0x3008, target = 0x3080, isTaken = true)
      cd.waitSampling(5) // 等待信号被锁存

      // 3. 解除 fetchDisable
      ParallaxLogger.info("[TB] Disabling fetchDisable, resuming fetch.")
      helper.setFetchDisable(false)

      // 期望流水线从新地址恢复，并使用新的BPU信息
      helper.expectInstr(0x3000, nop(), timeout = 100)
      helper.expectInstr(0x3004, nop(), timeout = 100)
      helper.expectInstr(0x3008, bne(1, 2, 4), expectedIsBranch = true, predictedTaken = Some(true), timeout = 100)
      helper.expectInstr(0x3080, addi_w(7, 7, 7), timeout = 100)

      helper.expectNoOutput(50)
    }
  }

  // =========================================================================
  //  Performance Test Cases (REVISED with CycleTimer)
  // =========================================================================
  test("Fetch2 - Perf - Throughput with high ICache hit rate") {
    SimConfig
      .compile(new Fetch2PipelineTestBench(pCfg, iCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        val helper = new Fetch2TestHelper(dut)
        val timer = new CycleTimer()

        // --- 测试参数 ---
        val totalInstructions = 256
        val startAddress = BigInt(0x1000)

        // ==================== 最终修改版 开始 ====================

        // 1. 初始化 & 暂停取指
        helper.init()
        helper.setFetchDisable(true) // <<<--- 关键修改1: 立即暂停取指
        cd.waitSampling(5)

        // 2. 内存写入
        ParallaxLogger.info(s"[TB] Writing ${totalInstructions} instructions while fetch is disabled...")
        val insts = (0 until totalInstructions).map(i => addi_w(1, 1, i))
        helper.writeInstructionsToMem(startAddress, insts)

        // 3. 预热阶段
        ParallaxLogger.info("[TB] Warming up ICache...")
        helper.startMonitor() // 此时启动监控器
        dut.io.fetchOutput.ready #= true // 让数据可以流出
        helper.setFetchDisable(false) // <<<--- 解除暂停，开始预热
        helper.issueHardRedirect(startAddress)

        val warmupTimeout = totalInstructions * 10
        val warmupFailed = cd.waitSamplingWhere(timeout = warmupTimeout) {
          helper.receivedInstrs.length >= totalInstructions
        }
        if (warmupFailed) {
          assert(
            false,
            s"Warm-up failed: received only ${helper.receivedInstrs.length}/${totalInstructions} instructions within ${warmupTimeout} cycles."
          )
        }

        // 4. 重置 & 准备测量
        ParallaxLogger.info("[TB] Resetting for measurement phase...")
        helper.setFetchDisable(true) // <<<--- 再次暂停，以便清理状态
        dut.io.fetchOutput.ready #= false
        cd.waitSampling(10)

        helper.receivedInstrs.clear() // 清空预热时收到的指令
        helper.issueHardRedirect(0x0) // 重定向到安全地址
        cd.waitSampling(50) // 确保流水线完全排空和重置

        // 5. 测量阶段
        ParallaxLogger.info("[TB] Starting performance measurement phase...")

        timer.start() // 启动计时器
        helper.clearBuffer()
        helper.enableRealtimeSequentialCheck(startAddress) // <<<--- 在这里为干净的队列启用实时检查
        dut.io.fetchOutput.ready #= true
        helper.setFetchDisable(false) // <<<--- 解除暂停，开始测量
        helper.issueHardRedirect(startAddress)

        // ==================== 最终修改版 结束 ====================

        val measurementTimeout = totalInstructions * 5
        val measurementFailed = cd.waitSamplingWhere(timeout = measurementTimeout) {
          helper.receivedInstrs.length >= totalInstructions
        }
        val elapsedCycles = timer.stop()

        if (measurementFailed) {
          assert(
            false,
            s"Measurement timed out after ${measurementTimeout} cycles. Only received ${helper.receivedInstrs.length} instructions."
          )
        }

        // 4. 验证和计算
        assert(
          helper.receivedInstrs.length == totalInstructions,
          s"Expected ${totalInstructions} instructions, but received ${helper.receivedInstrs.length}"
        )

        for (i <- 0 until totalInstructions) {
          val expectedPc = startAddress + i * 4
          val received = helper.receivedInstrs.dequeue()
          assert(
            received.pc == expectedPc,
            s"Instruction order mismatch! Expected PC 0x${expectedPc.toString(16)}, got 0x${received.pc.toString(16)}"
          )
        }

        val ipc = totalInstructions.toDouble / elapsedCycles.toDouble

        println("=====================================================")
        println("           Fetch Pipeline Performance Summary")
        println("-----------------------------------------------------")
        println(f"  Total Instructions Fetched: ${totalInstructions}")
        println(f"  Elapsed Clock Cycles:       ${elapsedCycles}")
        println(f"  Fetch IPC (Instructions/Cycle): ${ipc}%.3f")
        println(f"  Theoretical Max IPC:            ${1.0}%.2f")
        println("=====================================================")

        val ipcThreshold = 0.95
        assert(ipc > ipcThreshold, s"Fetch IPC (${ipc}%.3f) is below the threshold of ${ipcThreshold}%.2f")
      }
  }

  thatsAll()
}
