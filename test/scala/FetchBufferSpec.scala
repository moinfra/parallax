// filename: test/scala/fetch/FetchBufferSpec.scala

package test.scala.fetch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.ifu._
import parallax.components.dcache2._
import parallax.components.memory.IFetchRsp
import parallax.components.memory.FetchBuffer
import parallax.components.memory.FetchBufferIo // 引入新的 IO 定义
import parallax.utilities.ParallaxLogger

import scala.collection.mutable
import scala.util.Random
import parallax.components.memory._

// TestBench DUT: 更新以匹配新的 FetchBuffer 构造函数和 IO
class FetchBufferTestBench(ifuCfg: InstructionFetchUnitConfig, bufferDepth: Int) extends Component {
  // IO 使用新的定义，不再需要 pCfg
  val io = FetchBufferIo(ifuCfg)
  // 实例化新的 FetchBuffer
  val buffer = new FetchBuffer(ifuCfg, bufferDepth)
  io <> buffer.io
}

// 新的 Capture 对象，直接对应 IFetchRsp
case class IFetchRspCapture(
    pc: BigInt,
    instructions: Seq[BigInt],
    fault: Boolean,
    validMask: BigInt,
    predecodeInfo: Seq[(Boolean, Boolean)] // (isBranch, isJump)
)

object IFetchRspCapture {
  // <<<<< UPDATED: apply method to extract predecodeInfo
  def apply(payload: IFetchRsp): IFetchRspCapture = {
    import spinal.core.sim._
    IFetchRspCapture(
      payload.pc.toBigInt,
      payload.instructions.map(_.toBigInt).toSeq,
      payload.fault.toBoolean,
      payload.validMask.toBigInt,
      // Extract predecode info from the payload
      payload.predecodeInfo.map(p => (p.isBranch.toBoolean, p.isJump.toBoolean))
    )
  }
}

/** Test Helper for the group-oriented FetchBuffer.
  */
class FetchBufferTestHelper(
    dut: FetchBufferTestBench,
    cd: ClockDomain,
    ifuCfg: InstructionFetchUnitConfig, // 需要 ifuCfg 来知道组的大小
    enablePopRandomizer: Boolean = true
) {
  // 队列现在存储 IFetchRspCapture
  val receivedGroups = mutable.Queue[IFetchRspCapture]()

  // 监控 dut.io.pop 流，并用新的 Capture 对象填充队列
  StreamMonitor(dut.io.pop, cd) { payload =>
    receivedGroups.enqueue(IFetchRspCapture(payload))
  }

  if (enablePopRandomizer) {
    StreamReadyRandomizer(dut.io.pop, cd)
  }

  // 初始化：增加了 popOnBranch
  def init(): Unit = {
    dut.io.push.valid #= false
    dut.io.flush #= false
    dut.io.popOnBranch #= false // popOnBranch 是一个关键控制信号
    if (!enablePopRandomizer) {
      dut.io.pop.ready #= true
    }
    receivedGroups.clear()
    cd.waitSampling()
  }

  def setPopReady(ready: Boolean): Unit = {
    if (enablePopRandomizer) {
      throw new IllegalStateException("Cannot manually set pop.ready when randomizer is enabled!")
    }
    dut.io.pop.ready #= ready
  }

  // <<<<< UPDATED: pushPacket to drive predecodeInfo
  def pushPacket(
      pc: BigInt,
      instructions: Seq[BigInt],
      fault: Boolean = false,
      validMask: BigInt = -1,
      predecode: Seq[(Boolean, Boolean)] = null // Default to non-control-flow
  ): Unit = {
    ParallaxLogger.debug(s"[Helper] pushPacket for PC=0x${pc.toString(16)}: Waiting for ready...")
    cd.waitSamplingWhere(timeout = 100) { dut.io.push.ready.toBoolean }
    ParallaxLogger.debug(s"[Helper] pushPacket for PC=0x${pc.toString(16)}: Got ready. Driving valid.")

    dut.io.push.valid #= true
    val payload = dut.io.push.payload

    payload.pc #= pc
    payload.fault #= fault
    val finalValidMask = if (validMask == -1) (BigInt(1) << instructions.length) - 1 else validMask
    payload.validMask #= finalValidMask

    val finalPredecode = if (predecode == null) Seq.fill(instructions.length)((false, false)) else predecode
    for (i <- instructions.indices) {
      payload.instructions(i) #= instructions(i)
      payload.predecodeInfo(i).isBranch #= finalPredecode(i)._1
      payload.predecodeInfo(i).isJump #= finalPredecode(i)._2
    }

    cd.waitSampling()
    dut.io.push.valid #= false
        ParallaxLogger.debug(s"[Helper] pushPacket for PC=0x${pc.toString(16)}: Done.")
  }

  def signalPopOnBranch(): Unit = {
    dut.io.popOnBranch #= true
    cd.waitSampling()
    dut.io.popOnBranch #= false
  }

  // <<<<< UPDATED: expectGroups to verify predecodeInfo
  def expectGroups(expected: Seq[IFetchRspCapture]): Unit = {
    cd.waitSampling(expected.length * 5 + 20)
    assert(receivedGroups.size == expected.size, s"Expected ${expected.size} groups, but got ${receivedGroups.size}")

    for (expectedGroup <- expected) {
      val received = receivedGroups.dequeue()
      assert(
        received.pc == expectedGroup.pc,
        s"PC mismatch! Expected 0x${expectedGroup.pc.toString(16)}, got 0x${received.pc.toString(16)}"
      )
      assert(received.instructions == expectedGroup.instructions, "Instruction mismatch!")
      assert(received.fault == expectedGroup.fault, "Fault mismatch!")
      assert(received.validMask == expectedGroup.validMask, "ValidMask mismatch!")
      assert(received.predecodeInfo == expectedGroup.predecodeInfo, "PredecodeInfo mismatch!")
    }
    ParallaxLogger.info(s"Verified ${expected.size} groups successfully.")
  }

}

class FetchBufferSpec extends CustomSpinalSimFunSuite {

  // --- Common Configurations ---
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
  val ifuCfg =
    InstructionFetchUnitConfig(pCfg = pCfg, dCacheParams, fetchGroupDataWidth = 64 bits, instructionWidth = 32 bits)
  val bufferDepth = 4
  val instsPerGroup = ifuCfg.instructionsPerFetchGroup
  test("FetchBuffer_BasicPushPop") {
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg)
      helper.init()

      // Push packets with predecode info
      helper.pushPacket(pc = 0x1000, instructions = Seq(0x11, 0x22), predecode = Seq((false, false), (true, false)))
      helper.pushPacket(pc = 0x2000, instructions = Seq(0x33, 0x44), predecode = Seq((false, true), (false, false)))

      // Expect packets with predecode info
      helper.expectGroups(Seq(
        IFetchRspCapture(0x1000, Seq(0x11, 0x22), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (true, false))),
        IFetchRspCapture(0x2000, Seq(0x33, 0x44), fault = false, validMask = 3, predecodeInfo = Seq((false, true), (false, false)))
      ))
      cd.waitSampling()
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after consuming all groups")
    }
  }

  // --- Other tests updated similarly ---

  test("FetchBuffer_FullAndEmptyState") {
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg, enablePopRandomizer = false)
      helper.init()

      assert(dut.io.isEmpty.toBoolean, "Buffer should be initially empty")
      helper.setPopReady(false)

      for (i <- 0 until bufferDepth) {
        helper.pushPacket(pc = 0x1000 + i * 8, instructions = Seq(i, i + 1)) // Uses default predecode
      }
      cd.waitSampling()
      assert(dut.io.isFull.toBoolean, "Buffer should be full")

      helper.setPopReady(true)
      val expected = (0 until bufferDepth).map { i =>
        IFetchRspCapture(0x1000 + i * 8, Seq(i, i + 1), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false)))
      }
      helper.expectGroups(expected)
      cd.waitSampling()
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty")
    }
  }

  test("FetchBuffer_Flush") {
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      // <<<<< FIXED: Disable the randomizer for precise control
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg, enablePopRandomizer = false)
      helper.init()
      
      // Keep pop disabled while pushing
      helper.setPopReady(false)
      helper.pushPacket(pc = 0x1000, instructions = Seq(0x11, 0x22))
      helper.pushPacket(pc = 0x2000, instructions = Seq(0x33, 0x44))
      cd.waitSampling() // Ensure pushes are registered and nothing is popped
      
      // Pop exactly one group
      helper.setPopReady(true)
      cd.waitSamplingWhere(helper.receivedGroups.nonEmpty) // Wait until something is actually received
      helper.setPopReady(false)
      
      // Now buffer contains one item. Flush it.
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      
      cd.waitSampling(5)
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty after flush")
      assert(helper.receivedGroups.size == 1, s"Expected 1 group before flush, but got ${helper.receivedGroups.size}")
      assert(helper.receivedGroups.front.pc == 0x1000)
    }
  }

  test("FetchBuffer_PopOnBranch") {
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      // <<<<< FIXED: Disable the randomizer for precise control
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg, enablePopRandomizer = false)
      helper.init()
      
      // Push three groups into the buffer while pop is disabled
      helper.setPopReady(false)
      helper.pushPacket(pc = 0x1000, instructions = Seq(0xAA, 0xBB))
      helper.pushPacket(pc = 0x2000, instructions = Seq(0xCC, 0xDD))
      helper.pushPacket(pc = 0x3000, instructions = Seq(0xEE, 0xFF))
      cd.waitSampling()

      // Signal a branch mispredict. This should discard P1. Nothing should be popped.
      ParallaxLogger.info("Signaling popOnBranch to discard the first group.")
      helper.signalPopOnBranch()
      cd.waitSampling()
      assert(helper.receivedGroups.isEmpty, "No groups should be received during popOnBranch")


      // Now, allow the downstream module to consume the remaining packets
      helper.setPopReady(true)
      
      // We should now receive Group 2 and Group 3
      helper.expectGroups(Seq(
        IFetchRspCapture(0x2000, Seq(0xCC, 0xDD), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false))),
        IFetchRspCapture(0x3000, Seq(0xEE, 0xFF), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false)))
      ))
      
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty at the end")
      assert(helper.receivedGroups.isEmpty, "Received queue should be empty after verification")
    }
  }

  
  test("FetchBuffer_ConcurrentPushPop") {
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, 2)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg)
      helper.init()

      helper.pushPacket(pc = 0x1000, instructions = Seq(0x11, 0x22))
      helper.pushPacket(pc = 0x2000, instructions = Seq(0x33, 0x44))
      
      val pusherThread = fork {
        helper.pushPacket(pc = 0x3000, instructions = Seq(0x55, 0x66))
      }
      
      pusherThread.join()
      
      helper.expectGroups(Seq(
        IFetchRspCapture(0x1000, Seq(0x11, 0x22), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false))),
        IFetchRspCapture(0x2000, Seq(0x33, 0x44), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false))),
        IFetchRspCapture(0x3000, Seq(0x55, 0x66), fault = false, validMask = 3, predecodeInfo = Seq((false, false), (false, false)))
      ))
      assert(dut.io.isEmpty.toBoolean, "Buffer should be empty at the end")
    }
  }
test("FetchBuffer_IsFull_Timing_Check_Sanitized") {
    val bufferDepth = 2
    SimConfig.withWave.compile(new FetchBufferTestBench(ifuCfg, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain
      cd.forkStimulus(10)
      val helper = new FetchBufferTestHelper(dut, cd, ifuCfg, enablePopRandomizer = false)
      helper.init()
      helper.setPopReady(false)
      
      // Cycle 1: Push first packet
      dut.io.push.valid #= true
      dut.io.push.payload.pc #= 0x1000
      cd.waitSampling() // usage becomes 1
      
      // Cycle 2: Push second packet
      dut.io.push.payload.pc #= 0x2000
      cd.waitSampling() // usage becomes 2
      
      // Cycle 3: Deassert valid and check
      dut.io.push.valid #= false
      cd.waitSampling()

      // After two full push cycles, usage MUST be 2.
      assert(dut.io.isFull.toBoolean, "Buffer must be full after two push cycles.")
      assert(!dut.io.push.ready.toBoolean, "Buffer must not be ready when full.")
      
      println("Test 'FetchBuffer_IsFull_Timing_Check_Sanitized' PASSED")
    }
  }
  thatsAll

}
