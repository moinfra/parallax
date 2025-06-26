// command: testOnly test.scala.fetch.FetchBufferSpec
package test.scala.fetch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.ifu._
import parallax.components.dcache2._
import parallax.utilities.ParallaxLogger

import scala.collection.mutable
import scala.util.Random
import parallax.components.memory._

/** Top-level DUT for FetchBuffer tests.
  */
class FetchBufferTestBench(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig, bufferDepth: Int)
    extends Component {
  val io = FetchBufferIo(pCfg, ifuCfg)
  val buffer = new FetchBuffer(pCfg, ifuCfg, bufferDepth)
  io <> buffer.io
}

/** Test Helper for FetchBuffer simulations.
  */
class FetchBufferTestHelper(
    dut: FetchBufferTestBench,
    cd: ClockDomain,
    enablePopRandomizer: Boolean = true // << 新增控制参数
) {
  val receivedInstructions = mutable.Queue[FetchBufferOutputCapture]()

  StreamMonitor(dut.io.pop, cd) { payload =>
    receivedInstructions.enqueue(FetchBufferOutputCapture(payload))
  }

  if (enablePopRandomizer) {
    StreamReadyRandomizer(dut.io.pop, cd)
  }

  def init(): Unit = {
    dut.io.push.valid #= false
    dut.io.flush #= false
    dut.io.popOnBranch #= false
    if (!enablePopRandomizer) {
      dut.io.pop.ready #= true // 手动控制时的默认值
    }
    receivedInstructions.clear()
    cd.waitSampling()
  }
  def setPopReady(ready: Boolean): Unit = {
    if (enablePopRandomizer) {
      throw new IllegalStateException("Cannot manually set pop.ready when randomizer is enabled!")
    }
    dut.io.pop.ready #= ready
  }
  // **关键修复**: 不再创建新的Bundle，而是直接驱动DUT的输入端口
  def pushPacket(pc: BigInt, instructions: Seq[BigInt], fault: Boolean = false): Unit = {
    // 1. 等待缓冲区准备好接收
    // 这个改动可以解决潜在的背压问题
    cd.waitSamplingWhere(timeout = 100) {
      if (dut.io.push.ready.toBoolean) {
        true
      } else {
        println("pushPacket: push.ready is low, waiting...")
        false
      }
    }

    // 2. 驱动数据和valid信号
    dut.io.push.valid #= true
    dut.io.push.payload.pc #= pc
    dut.io.push.payload.fault #= fault
    for (i <- instructions.indices) {
      dut.io.push.payload.instructions(i) #= instructions(i)
    }

    // 3. 等待一个时钟周期以完成握手
    cd.waitSampling()

    // 4. 撤销valid信号
    dut.io.push.valid #= false
  }

  def expectInstructions(expected: Seq[(BigInt, BigInt)]): Unit = {
    // 等待足够长的时间以确保所有指令都通过随机化的ready信号被pop出来
    cd.waitSampling(expected.length * 5 + 20)
    assert(
      receivedInstructions.size == expected.size,
      s"Expected ${expected.size} instructions, but got ${receivedInstructions.size}"
    )

    for ((expectedPc, expectedInst) <- expected) {
      val received = receivedInstructions.dequeue()
      assert(
        received.pc.toBigInt == expectedPc,
        s"PC mismatch! Expected 0x${expectedPc.toString(16)}, got 0x${received.pc.toBigInt.toString(16)}"
      )
      assert(
        received.instruction.toBigInt == expectedInst,
        s"Instruction mismatch! Expected 0x${expectedInst.toString(16)}, got 0x${received.instruction.toBigInt.toString(16)}"
      )
    }
    ParallaxLogger.info(s"Verified ${expected.size} instructions successfully.")
  }
}

class FetchBufferSpec extends CustomSpinalSimFunSuite {

  // --- Common Configurations ---
  val pCfg = PipelineConfig(
    xlen = 32,
    fetchWidth = 1,
    commitWidth = 1,
    robDepth = 4,
    renameWidth = 1,
    physGprCount = 4,
    archGprCount = 4,
    aluEuCount = 0,
    transactionIdWidth = 1
  )

  // **关键修复**: 创建一个完整的DCacheConfig，然后用它来生成DataCacheParameters
  val dCachePluginCfg = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = 32,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 16,
    transactionIdWidth = 2
  )
  val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCachePluginCfg)
  val ifuCfg = InstructionFetchUnitConfig(dCacheParams, fetchGroupDataWidth = 64 bits, instructionWidth = 32 bits)
  val bufferDepth = 4

  test("FetchBuffer_BasicPushPop") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd) // << 修复：传入ClockDomain
      helper.init()

      helper.pushPacket(pc = BigInt(0x1000), instructions = Seq(BigInt(0x11), BigInt(0x22)))

      helper.expectInstructions(
        Seq(
          (BigInt(0x1000), BigInt(0x11)),
          (BigInt(0x1004), BigInt(0x22))
        )
      )
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after consuming all instructions")
    }
  }

  test("FetchBuffer_FullAndEmptyState") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)

      // **关键修复：创建一个没有随机反压的Helper**
      val helper = new FetchBufferTestHelper(dut, cd, enablePopRandomizer = false)
      helper.init()

      assert(dut.io.isEmpty.toBoolean, "Buffer should be initially empty")
      assert(!dut.io.isFull.toBoolean, "Buffer should not be initially full")

      // **在填充阶段禁用pop**
      dut.io.pop.ready #= false

      // Fill the buffer completely
      for (i <- 0 until bufferDepth) {
        helper.pushPacket(pc = BigInt(0x1000) + i * 8, instructions = Seq(i * 2, i * 2 + 1))
      }

      cd.waitSampling()
      assert(dut.io.isFull.toBoolean, "Buffer should be full")
      assert(!dut.io.push.ready.toBoolean, "Push port should not be ready when buffer is full")

      // **在消费阶段再重新启用pop**
      dut.io.pop.ready #= true

      // Consume all instructions
      val expected = (0 until bufferDepth).flatMap { i =>
        Seq((BigInt(0x1000) + i * 8, BigInt(i * 2)), (BigInt(0x1004) + i * 8, BigInt(i * 2 + 1)))
      }
      helper.expectInstructions(expected)

      cd.waitSampling()
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after full consumption")
    }
  }

  test("FetchBuffer_Flush") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)

      // 我们需要一个监控器来收集弹出的指令
      val receivedInstructions = mutable.Queue[FetchBufferOutputCapture]()
      StreamMonitor(dut.io.pop, cd) { payload =>
        receivedInstructions.enqueue(FetchBufferOutputCapture(payload))
      }

      // --- 手动、周期精确的测试流程 ---

      // 1. 初始化所有输入
      dut.io.push.valid #= false
      dut.io.pop.ready #= false // **开始时完全禁止pop**
      dut.io.flush #= false
      dut.io.popOnBranch #= false
      cd.waitSampling()

      // 2. 推入第一个包
      dut.io.push.valid #= true
      dut.io.push.payload.pc #= 0x1000
      dut.io.push.payload.instructions(0) #= 0x11
      dut.io.push.payload.instructions(1) #= 0x22
      cd.waitSampling() // 等待一个周期让 push.fire 发生
      dut.io.push.valid #= false // 立即拉低

      // 此时 usage=1

      // 3. 推入第二个包
      dut.io.push.valid #= true
      dut.io.push.payload.pc #= 0x2000
      dut.io.push.payload.instructions(0) #= 0x33
      dut.io.push.payload.instructions(1) #= 0x44
      cd.waitSampling() // 等待一个周期让 push.fire 发生
      dut.io.push.valid #= false

      // 此时 usage=2, 缓冲区里有两个包，一条指令都还没弹出去

      // 4. 精确地弹出一个指令
      dut.io.pop.ready #= true
      cd.waitSampling() // 在这个周期，pop.fire=1
      dut.io.pop.ready #= false // **立即禁止pop，确保只弹出一个**

      // 此时，第一条指令 (0x11) 已经被弹出并进入 receivedInstructions

      // 5. 立即执行 Flush
      dut.io.flush #= true
      cd.waitSampling() // 让 flush 信号生效一个周期
      dut.io.flush #= false

      // 6. 检查最终状态
      cd.waitSampling(5) // 等待几个周期让系统稳定
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after flush")
      assert(
        receivedInstructions.size == 1,
        s"Expected 1 instruction before flush, but got ${receivedInstructions.size}"
      )
      assert(receivedInstructions.front.instruction == 0x11)
    }
  }

  test("FetchBuffer_PopOnBranch") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd) // << 修复：传入ClockDomain
      helper.init()

      helper.pushPacket(pc = BigInt(0x1000), instructions = Seq(BigInt(0x11), BigInt(0x22)))
      cd.waitSamplingWhere(dut.io.pop.ready.toBoolean && dut.io.pop.valid.toBoolean)
      // ================== 终极修复 ==================
      // 在驱动 popOnBranch 的同一个周期，强制 pop.ready 为 false，
      // 确保丢弃操作和下一个 pop 不会竞争。
      dut.io.pop.ready #= false
      dut.io.popOnBranch #= true
      cd.waitSampling() // 让 popOnBranch 信号生效一个周期
      dut.io.popOnBranch #= false
      // 恢复 pop.ready 的正常驱动
      dut.io.pop.ready #= true
      // ==============================================

      cd.waitSampling(5)
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after popOnBranch")
      assert(helper.receivedInstructions.size == 1, "Only the branch instruction itself should be consumed")
      assert(
        helper.receivedInstructions.front.instruction.toBigInt == BigInt(0x11),
        "The consumed instruction should be the first one"
      )
    }
  }

  test("FetchBuffer_ConcurrentPushPop") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, 2)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd) // << 修复：传入ClockDomain
      helper.init()

      helper.pushPacket(pc = BigInt(0x1000), instructions = Seq(BigInt(0x11), BigInt(0x22)))

      val isTimeout = cd.waitSamplingWhere(timeout = 100)(helper.receivedInstructions.nonEmpty) // Wait for first pop
      if (isTimeout) {
        fail("timeout")
      }

      // At this point, buffer has one instruction left and is not full.
      // We can push a new packet while the last instruction of the old one is being popped.
      dut.io.push.valid #= true
      dut.io.push.payload.pc #= BigInt(0x2000)
      dut.io.push.payload.instructions(0) #= BigInt(0x33)
      dut.io.push.payload.instructions(1) #= BigInt(0x44)

      cd.waitSampling() // Let the concurrent push/pop happen
      dut.io.push.valid #= false

      helper.expectInstructions(
        Seq(
          (BigInt(0x1000), BigInt(0x11)),
          (BigInt(0x1004), BigInt(0x22)),
          (BigInt(0x2000), BigInt(0x33)),
          (BigInt(0x2004), BigInt(0x44))
        )
      )
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty at the end")
    }
  }

  thatsAll()
}
