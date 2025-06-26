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
    dut.io.pop.ready #= true // 默认设为true
    receivedInstructions.clear()
    cd.waitSampling()
  }

  // **关键修复**: 不再创建新的Bundle，而是直接驱动DUT的输入端口
  def pushPacket(pc: BigInt, instructions: Seq[BigInt], fault: Boolean = false): Unit = {
    dut.io.push.valid #= true
    dut.io.push.payload.pc #= pc
    dut.io.push.payload.fault #= fault
    for (i <- instructions.indices) {
      dut.io.push.payload.instructions(i) #= instructions(i)
    }

    // 等待Buffer准备好接收
    val isTimeout = cd.waitSamplingWhere(timeout = 100)(dut.io.push.ready.toBoolean)
    if(isTimeout) {
      assert(false, "timeout")
    }
    // 握手发生在这个周期的末尾，下一个周期才算完成
    cd.waitSampling()
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

  testOnly("FetchBuffer_FullAndEmptyState") {
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
      val helper = new FetchBufferTestHelper(dut, cd) // << 修复：传入ClockDomain
      helper.init()

      helper.pushPacket(pc = BigInt(0x1000), instructions = Seq(BigInt(0x11), BigInt(0x22)))
      helper.pushPacket(pc = BigInt(0x2000), instructions = Seq(BigInt(0x33), BigInt(0x44)))

      val isTimeout = cd.waitSamplingWhere(timeout = 100)(dut.io.pop.valid.toBoolean) // Wait until pop is valid
      if(isTimeout) {
        fail("timeout")
      }
      cd.waitSampling() // Let one pop happen

      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false

      cd.waitSampling()
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after flush")
      assert(helper.receivedInstructions.size == 1, "Only one instruction should have been received before flush")
    }
  }

  test("FetchBuffer_PopOnBranch") {
    SimConfig.withWave.compile(new FetchBufferTestBench(pCfg, ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd) // << 修复：传入ClockDomain
      helper.init()

      helper.pushPacket(pc = BigInt(0x1000), instructions = Seq(BigInt(0x11), BigInt(0x22)))

      dut.io.pop.ready #= true

      val isTimeout = cd.waitSamplingWhere(timeout = 100)(dut.io.pop.valid.toBoolean)
      if(isTimeout) {
        fail("timeout")
      }
      dut.io.popOnBranch #= true
      cd.waitSampling()
      dut.io.popOnBranch #= false

      cd.waitSampling()
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
      if(isTimeout) {
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
