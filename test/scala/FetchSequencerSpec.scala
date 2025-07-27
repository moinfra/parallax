// testOnly test.scala.FetchSequencerSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch2._ // 导入我们正在测试的组件
import parallax.fetch.icache._
import spinal.lib.bus.amba4.axi.Axi4Config
import LA32RInstrBuilder._
import scala.collection.mutable
import parallax.utilities._

// =========================================================================
//  Test Bench Setup
// =========================================================================

// 测试环境，包含FetchSequencer, ICache, 和内存系统
class FetchSequencerTestEnv(pCfg: PipelineConfig, iCfg: ICacheConfig, axiCfg: Axi4Config) extends Component {
  val io = new Bundle {
    val newRequest = slave(Stream(FetchRequestPayload(pCfg)))
    val flush = in(Bool())
    val dispatchGroupOut = master(Stream(FetchGroup(pCfg)))
  }.simPublic()

  // 使用SpinalHDL的Framework来集成各个插件
  val framework = new Framework(
    Seq(
      new ICachePlugin(iCfg, axiCfg, pCfg.pcWidth.value),
      new TestOnlyMemSystemPlugin(axiConfig = axiCfg),
      // 这里不使用FetchPipelinePlugin，而是直接实例化FetchSequencer
      new Plugin {
        val setup = create early new Area {
          val iCacheService = getService[ICacheService]
          iCacheService.retain()
        }

        val logic = create late new Area {
          val sequencer = new FetchSequencer(pCfg, iCfg)

          // 将sequencer连接到顶层IO
          sequencer.io.newRequest <> io.newRequest
          sequencer.io.flush <> io.flush
          io.dispatchGroupOut <> sequencer.io.dispatchGroupOut

          // 将sequencer连接到ICache服务
          val iCachePort = setup.iCacheService.newICachePort()
          sequencer.io.iCacheCmd <> iCachePort.cmd
          iCachePort.rsp <> sequencer.io.iCacheRsp

          setup.iCacheService.release()
        }
      }
    )
  )
}

// =========================================================================
//  Test Helper
// =========================================================================

class FetchSequencerTestHelper(dut: FetchSequencerTestEnv)(implicit cd: ClockDomain) {
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
  val receivedGroups = mutable.Queue[FetchGroupCapture]()

  // 初始化DUT输入
  def init(): Unit = {
    dut.io.newRequest.valid #= false
    dut.io.dispatchGroupOut.ready #= false
    dut.io.flush #= false
    cd.waitSampling()
  }

  // 监控输出
  def startMonitor(): Unit = {
    StreamMonitor(dut.io.dispatchGroupOut, cd) { payload =>
      val captured = FetchGroupCapture(payload)
      receivedGroups.enqueue(captured)
      println(s"[Monitor @ ${simTime()}] Received FetchGroupCapture for PC=0x${payload.pc.toBigInt.toString(16)}")
    }
  }

  // 向SRAM写入指令
  def writeInstructions(addr: BigInt, insts: Seq[BigInt]): Unit = {
    for ((inst, i) <- insts.zipWithIndex) {
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= addr + i * 4
      sram.io.tb_writeData #= inst
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
  }

  // 发送一个取指请求
  def sendRequest(pc: BigInt, rawPc: BigInt): Unit = {
    dut.io.newRequest.valid #= true
    dut.io.newRequest.payload.pc #= pc
    dut.io.newRequest.payload.rawPc #= rawPc
    cd.waitSamplingWhere(dut.io.newRequest.ready.toBoolean)
    dut.io.newRequest.valid #= false
  }

  // 发出一个硬冲刷脉冲
  def issueHardFlush(): Unit = {
    dut.io.flush #= true
    cd.waitSampling()
    dut.io.flush #= false
  }

  // 设置下游是否准备好
  def driveDispatchReady(isReady: Boolean): Unit = {
    dut.io.dispatchGroupOut.ready #= isReady
  }

  // 验证收到的下一个FetchGroup
  def expectGroup(expectedPc: BigInt, timeout: Int = 200): Unit = {
    driveDispatchReady(true)
    val fail = dut.clockDomain.waitSamplingWhere(timeout)(receivedGroups.nonEmpty)
    driveDispatchReady(false)

    if (fail) {
      simFailure(s"Timeout: Did not receive any FetchGroup within ${timeout} cycles.")
    }

    val received = receivedGroups.dequeue()
    sleep(1) // 留些空间给日志.
    assert(
      received.pc.toBigInt == expectedPc,
      s"PC Mismatch! Expected 0x${expectedPc.toString(16)}, Got 0x${received.pc.toBigInt.toString(16)}"
    )
  }

  def expectNoGroup(duration: Int): Unit = {
    val initialCount = receivedGroups.size
    cd.waitSampling(duration)
    val finalCount = receivedGroups.size
    assert(initialCount == finalCount, s"Expected no group, but received ${finalCount - initialCount} groups.")
  }
}

// =========================================================================
//  Test Suite
// =========================================================================

class FetchSequencerSpec extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(fetchWidth = 4, resetVector = 0x1000)
  val iCfg = ICacheConfig(
    totalSize = 1 * 1024, // 使用小容量ICache以便更容易触发Miss
    ways = 2,
    bytesPerLine = 16, // 4 instructions
    fetchWidth = pCfg.fetchWidth,
    enableLog = false
  )
  val axiConfig = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 4)

  test("Sequencer - Sequential Hit") {
    SimConfig.withWave.compile(new FetchSequencerTestEnv(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new FetchSequencerTestHelper(dut)
      helper.init()

      // 预热ICache
      helper.writeInstructions(0x1000, (0 until 4).map(i => nop()))
      helper.writeInstructions(0x1010, (0 until 4).map(i => nop()))
      helper.sendRequest(0x1000, 0x1000)
      cd.waitSampling(150) // 等待miss处理完
      helper.sendRequest(0x1010, 0x1010)
      cd.waitSampling(150) // 等待miss处理完
      helper.startMonitor()
      helper.driveDispatchReady(true)
      ParallaxLogger.debug("Start monitor")

      // 测试顺序命中
      helper.sendRequest(0x1000, 0x1000)
      helper.sendRequest(0x1010, 0x1010)
      ParallaxLogger.debug("Expecting groups")

      helper.expectGroup(0x1000)
      helper.expectGroup(0x1010)
      helper.expectNoGroup(50)
    }
  }

  test("Sequencer - Out-of-Order Response Handling") {
    // 验证当ICache响应乱序到达时，sequencer能正确按序输出
    SimConfig.withWave.compile(new FetchSequencerTestEnv(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new FetchSequencerTestHelper(dut)
      helper.init()

      // 写入指令
      helper.writeInstructions(0x1000, Seq(nop())) // 会Miss
      helper.writeInstructions(0x2000, Seq(nop())) // 先预热，让它Hit

      helper.driveDispatchReady(true)
      helper.sendRequest(0x2000, 0x2000)

      cd.waitSampling(150)
      helper.driveDispatchReady(false)

      ParallaxLogger.debug("Start monitor")
      helper.startMonitor()
      helper.driveDispatchReady(true)

      // 发出两个请求：0x1000 (Miss) 和 0x2000 (Hit)
      // ICache应该会先响应0x2000，后响应0x1000
      helper.sendRequest(0x1000, 0x1000) // 老的，会Miss
      helper.sendRequest(0x2000, 0x2000) // 新的，会Hit

      // 尽管0x2000的响应先到，但sequencer必须等待0x1000处理完
      // 0x1000 会经历 Miss -> Retry -> Hit
      helper.expectGroup(0x1000, timeout = 300)
      helper.expectGroup(0x2000)
      helper.expectNoGroup(50)
    }
  }

  test("Sequencer - Hard Flush") {
    SimConfig.withWave.compile(new FetchSequencerTestEnv(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new FetchSequencerTestHelper(dut)
      helper.init()

      helper.writeInstructions(0x1000, Seq(nop()))
      helper.writeInstructions(0x2000, Seq(nop()))

      helper.startMonitor()
      helper.driveDispatchReady(true)

      // 将两个请求填满IFT
      helper.sendRequest(0x1000, 0x1000)
      helper.sendRequest(0x2000, 0x2000)

      // 在响应到达前发出硬冲刷
      cd.waitSampling(2)
      helper.issueHardFlush()

      // 冲刷后不应有任何输出
      helper.expectNoGroup(100)

      // 验证IFT已清空，可以接收新请求
      helper.sendRequest(0x3000, 0x3000)
      cd.waitSampling(150) // 等待miss处理
      helper.expectGroup(0x3000)
    }
  }

  test("Sequencer - Downstream Stall") {
    SimConfig.withWave.compile(new FetchSequencerTestEnv(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new FetchSequencerTestHelper(dut)
      helper.init()

      helper.writeInstructions(0x1000, Seq(nop()))
      helper.writeInstructions(0x1010, Seq(nop()))
      // 预热

      helper.driveDispatchReady(true)
      helper.sendRequest(0x1000, 0x1000); cd.waitSampling(150)
      helper.sendRequest(0x1010, 0x1010); cd.waitSampling(150)
      helper.driveDispatchReady(false)

      helper.startMonitor()
      helper.driveDispatchReady(true)

      // 发出两个Hit请求
      helper.sendRequest(0x1000, 0x1000)
      helper.sendRequest(0x1010, 0x1010)

      // 保持下游反压
      helper.driveDispatchReady(false)

      // 等待足够长的时间，让两个响应都到达IFT
      cd.waitSampling(150)

      // IFT应该已满，但没有输出
      assert(helper.receivedGroups.isEmpty, "Group was dispatched despite stall")

      // 解除反压，验证数据能按序流出
      helper.driveDispatchReady(true)
      cd.waitSampling() // 等一拍让ready信号生效
      helper.expectGroup(0x1000)
      helper.expectGroup(0x1010)
    }
  }

  test("xSequencer - Fuzzy Test with Random Intents") {
    SimConfig.withWave.compile(new FetchSequencerTestEnv(pCfg, iCfg, axiConfig)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      val helper = new FetchSequencerTestHelper(dut)
      val model = new FetchSequencerGoldenModel(iCfg)

      helper.init()
      helper.startMonitor()

      // 填充内存，让所有可能的请求都命中
      val maxAddr = 0x1000 + 100 * iCfg.bytesPerLine
      helper.writeInstructions(0x1000, Seq.fill((maxAddr - 0x1000) / 4)(nop()))
      cd.waitSampling(10)

      val rand = new scala.util.Random(0)
      var currentPc = BigInt(0x1000)

      val intents = mutable.Queue[TestIntent]()
      val iterations = 10000

      // --- 主模糊测试循环 ---
      for (i <- 0 until iterations) {

        // 1. 验证上一个周期的输出 (如果有)
        if (helper.receivedGroups.nonEmpty) {
          val received = helper.receivedGroups.dequeue()
          val expectedOpt = model.expectedPc()

          assert(
            expectedOpt.isDefined,
            s"[Verify FAIL @ ${simTime()}] DUT produced a group for PC=0x${received.pc.toBigInt.toString(16)}, but model expected nothing!"
          )

          val expected = expectedOpt.get
          assert(
            received.pc.toBigInt == expected,
            s"[Verify FAIL @ ${simTime()}] PC Mismatch! DUT produced 0x${received.pc.toBigInt
                .toString(16)}, but model expected 0x${expected.toString(16)}"
          )

          println(s"[Verify OK @ ${simTime()}] DUT matched model for PC=0x${expected.toString(16)}")

          model.retire()
        }

        // 2. 随机生成并执行下一个意图
        if (intents.isEmpty) {
          val intentCount = rand.nextInt(5) + 1
          for (_ <- 0 until intentCount) {
            val choice = rand.nextInt(100)
            val intent = choice match {
              case c if c < 40 => SendRequest
              case c if c < 50 => HardFlush
              case c if c < 65 => Stall
              case c if c < 80 => Unstall
              case _           => Wait
            }
            intents.enqueue(intent)
          }
        }

        val intent = intents.dequeue()
        println(s"[Intent @ ${simTime()}] Executing: $intent")

        intent match {
          case SendRequest =>
            if (dut.io.newRequest.ready.toBoolean) {
              helper.sendRequest(currentPc, currentPc)
              model.applyIntent(SendRequest, currentPc)
              currentPc += iCfg.bytesPerLine
            } else {
              println(s"  -> Skipped SendRequest, DUT not ready.")
            }

          case HardFlush =>
            helper.issueHardFlush()
            model.applyIntent(HardFlush)

          case Stall =>
            helper.driveDispatchReady(false)

          case Unstall =>
            helper.driveDispatchReady(true)

          case Wait =>
            cd.waitSampling(rand.nextInt(5) + 1)
        }

        // 3. 前进一个时钟周期，让意图生效并可能产生新的输出
        cd.waitSampling()
      }

      println("Fuzzy test completed successfully.")
    }
  }

  thatsAll
}

class FetchSequencerGoldenModel(iCfg: ICacheConfig) {
  // 用简单的case class模拟IFT slot
  case class GoldenSlot(pc: BigInt, tid: Int)

  private val ift = mutable.Queue[GoldenSlot]()
  private var nextTid = 0

  // 预测下一个应该被分发的PC
  def expectedPc(): Option[BigInt] = {
    ift.headOption.map(_.pc)
  }

  // 应用一个意图到模型上
  def applyIntent(intent: TestIntent, pc: BigInt = 0): Unit = intent match {
    case SendRequest =>
      if (ift.size < 2) {
        ift.enqueue(GoldenSlot(pc, nextTid))
        nextTid = (nextTid + 1) % 256
      }
    case HardFlush =>
      ift.clear()
    case _ => // Stall, Unstall, Wait不影响模型状态
  }

  def retire(): Unit = {
    if (ift.nonEmpty) {
      ift.dequeue()
    }
  }
}

// 定义测试意图
sealed trait TestIntent
case object SendRequest extends TestIntent
case object HardFlush extends TestIntent
case object Stall extends TestIntent
case object Unstall extends TestIntent
case object Wait extends TestIntent
