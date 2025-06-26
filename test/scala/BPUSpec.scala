// 文件路径: hw/test/scala/bpu/BpuPluginSpec.scala
// command: testOnly test.scala.bpu.BpuPluginSpec
package test.scala.bpu

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import parallax.common._
import parallax.components.bpu._
import parallax.bpu._
import parallax.utilities._

import scala.collection.mutable
import scala.util.Random
import spinal.lib.sim.FlowMonitor

/** 这是一个简单的插件，用于将BPU的接口连接到测试台的顶层IO。
  * 它实现了 BpuService 的客户端部分。
  */
class BpuTestSetupPlugin(io: BpuTestBenchIo) extends Plugin {
  val setup = create early new Area {
    // 1. 获取 BpuService
    val bpuService = getService[BpuPipelinePlugin]

    // 2. 从 Service 获取内部插件的端口
    val queryPort = bpuService.newBpuQueryPort()
    val responsePort = bpuService.getBpuResponse()
    val updatePort = bpuService.newBpuUpdatePort()

    queryPort <> io.query
    io.response <> responsePort
    updatePort <> io.update
  }
}

/** 测试台的顶层IO定义。
  */
case class BpuTestBenchIo(pCfg: PipelineConfig) extends Bundle {
  val query = slave(Flow(BpuQuery(pCfg)))
  val response = master(Flow(BpuResponse(pCfg)))
  val update = slave(Stream(BpuUpdate(pCfg)))
}

/** BPU测试的顶层DUT组件。
  */
class BpuTestBench(pCfg: PipelineConfig) extends Component {
  val io = BpuTestBenchIo(pCfg)
  io.simPublic() // 确保IO在仿真中可见

  // 使用SpinalHDL的Framework来组装插件
  val framework = new Framework(
    Seq(
      new BpuPipelinePlugin(pCfg), // 这是我们要测试的DUT
      new BpuTestSetupPlugin(io)
    )
  )
}

// =========================================================================
//  测试辅助类 (Test Helper)
// =========================================================================

/** BPU测试的辅助类
  */
// 文件路径: hw/test/scala/bpu/BpuPluginSpec.scala

// ... (其他测试代码) ...

import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import scala.collection.mutable

/** BPU测试的辅助类
  */
class BpuTestHelper(dut: BpuTestBench)(implicit cd: ClockDomain) {
  val queryQueue = mutable.Queue[BpuQuerySim]()
  val responseQueue = mutable.Queue[BpuResponseSim]()

  // 定义用于仿真的数据结构
  case class BpuQuerySim(pc: BigInt)
  case class BpuResponseSim(isTaken: Boolean, target: BigInt)

     def init(): Unit = {
        dut.io.query.valid #= false
        dut.io.update.valid #= false // 手动控制
        dut.io.update.payload.pc #= 0
        dut.io.update.payload.isTaken #= false
        dut.io.update.payload.target #= 0
        StreamReadyRandomizer(dut.io.update, cd) // 仍然可以保留，以测试反压
        cd.waitSampling()
    }

    // 驱动BPU的查询端口 (不变)
    def query(pc: BigInt): Unit = {
        dut.io.query.valid #= true
        dut.io.query.payload.pc #= pc
        queryQueue.enqueue(BpuQuerySim(pc))
        cd.waitSampling()
        dut.io.query.valid #= false
    }

    // 新的 update 函数，现在只准备 payload
    def prepareUpdate(pc: BigInt, isTaken: Boolean, target: BigInt): Unit = {
        val p = dut.io.update.payload
        p.pc #= pc
        p.isTaken #= isTaken
        p.target #= target
    }
    
    // 我们需要一个通用的 update 方法用于其他测试
    def blockingUpdate(pc: BigInt, isTaken: Boolean, target: BigInt): Unit = {
        dut.io.update.valid #= true
        prepareUpdate(pc, isTaken, target)
        // 循环等待直到握手成功
        do {
            cd.waitSampling()
        } while(!(dut.io.update.valid.toBoolean && dut.io.update.ready.toBoolean))
        dut.io.update.valid #= false
    }

  // 监控BPU的响应端口 (不变)
  def startMonitor(): Unit = {
    val monitor = FlowMonitor(dut.io.response, cd) { payload =>
      responseQueue.enqueue(BpuResponseSim(payload.isTaken.toBoolean, payload.target.toBigInt))
    }
  }

  // 等待并验证一个响应 (不变)
  def expectResponse(expected: BpuResponseSim, timeout: Int = 50): Unit = {
    // BPU有2个周期的延迟，所以至少等待2个周期
    cd.waitSampling(3)

    var received: Option[BpuResponseSim] = None
    val timedOut = cd.waitSamplingWhere(timeout = timeout) {
      if (responseQueue.nonEmpty) {
        received = Some(responseQueue.dequeue())
        true
      } else {
        false
      }
    }

    assert(!timedOut, "Timeout waiting for BPU response.")
    val resp = received.get
    assert(
      resp.isTaken == expected.isTaken,
      s"Response 'isTaken' mismatch. Got ${resp.isTaken}, Expected ${expected.isTaken}"
    )
    if (expected.isTaken) {
      assert(
        resp.target == expected.target,
        s"Response 'target' mismatch. Got 0x${resp.target.toString(16)}, Expected 0x${expected.target.toString(16)}"
      )
    }
  }
}

// =========================================================================
//  测试规格 (Test Specification)
// =========================================================================

class BpuPluginSpec extends CustomSpinalSimFunSuite {

  def createPConfig(commitWidth: Int = 1, robDepth: Int = 16, renameWidth: Int = 1): PipelineConfig = PipelineConfig(
    xlen = 32,
    commitWidth = commitWidth,
    robDepth = robDepth,
    renameWidth = renameWidth,
    physGprCount = 64,
    archGprCount = 32,
    fetchWidth = 1,
    dispatchWidth = 1,
    aluEuCount = 0,
    transactionIdWidth = 8
  )

  test("BPU - Initial prediction is Not Taken") {
    val pCfg = createPConfig()
    SimConfig.withWave.compile(new BpuTestBench(pCfg)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new BpuTestHelper(dut)
      helper.init()
      helper.startMonitor()

      val testPc = 0x80001000L

      helper.query(testPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = false, target = 0))

      println("Test 'BPU - Initial prediction is Not Taken' PASSED")
    }
  }

  test("BPU - Basic Update and Predict Taken") {
    val pCfg = createPConfig()
    SimConfig.withWave.compile(new BpuTestBench(pCfg)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new BpuTestHelper(dut)
      helper.init()
      helper.startMonitor()

      val branchPc = 0x80002000L
      val targetPc = 0x80002020L

      // 1. 第一次查询，应该是 Not Taken
      helper.query(branchPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = false, target = 0))

      // 2. 更新BPU，告诉它这个分支实际发生了跳转
      helper.blockingUpdate(branchPc, isTaken = true, target = targetPc)
      cd.waitSampling(5) // 等待更新完成

      // 3. 第二次查询，现在应该预测为 Taken
      helper.query(branchPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = true, target = targetPc))

      println("Test 'BPU - Basic Update and Predict Taken' PASSED")
    }
  }

  test("BPU - Forwarding on Query-Update Hazard") {
    val pCfg = createPConfig()
    SimConfig.withWave.compile(new BpuTestBench(pCfg)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new BpuTestHelper(dut)
      helper.init()
      helper.startMonitor()

      val branchPc = 0x80003000L
      val targetPc = 0x80003040L

      // 1. 第一次查询，此时BPU是空的，预测 Not Taken
      ParallaxLogger.debug("第一次查询")
      helper.query(branchPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = false, target = 0))

      // 2. 制造一个查询-更新冒险
      //    - 在一个周期发出查询
      //    - 在紧接着的下一个周期发出对相同PC的更新
      //    BPU内部，查询在s1，更新也在u1，当查询到达s2时，更新正好到达u2
      //    此时前递逻辑应该生效。
      fork {
        ParallaxLogger.debug("第二次查询")
        helper.query(branchPc)
      }
      fork {
        ParallaxLogger.debug("更新")
        helper.blockingUpdate(branchPc, isTaken = true, target = targetPc)
      }

      // 3. 验证响应
      //    即使BPU内存中的数据还是旧的，响应也应该是通过前递得到的正确结果
      helper.expectResponse(helper.BpuResponseSim(isTaken = true, target = targetPc))

      println("Test 'BPU - Forwarding on Query-Update Hazard' PASSED")
    }
  }

  test("BPU - PHT Saturation Counter") {
    val pCfg = createPConfig()
    SimConfig.withWave.compile(new BpuTestBench(pCfg)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new BpuTestHelper(dut)
      helper.init()
      helper.startMonitor()

      val branchPc = 0x80004000L
      val targetPc = 0x80004080L

      // 初始状态: Weakly Not Taken (01)
      // 1. 更新为 Taken -> 状态变为 Weakly Taken (10)
      helper.blockingUpdate(branchPc, isTaken = true, target = targetPc)
      cd.waitSampling(5)
      helper.query(branchPc)
      // PHT是10，msb=1，所以预测Taken
      helper.expectResponse(helper.BpuResponseSim(isTaken = true, target = targetPc))

      // 2. 再次更新为 Taken -> 状态变为 Strongly Taken (11)
      helper.blockingUpdate(branchPc, isTaken = true, target = targetPc)
      cd.waitSampling(5)
      helper.query(branchPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = true, target = targetPc))

      // 3. 更新为 Not Taken -> 状态变回 Weakly Taken (10)
      helper.blockingUpdate(branchPc, isTaken = false, target = 0)
      cd.waitSampling(5)
      helper.query(branchPc)
      helper.expectResponse(helper.BpuResponseSim(isTaken = true, target = targetPc))

      // 4. 再次更新为 Not Taken -> 状态变为 Weakly Not Taken (01)
      helper.blockingUpdate(branchPc, isTaken = false, target = 0)
      cd.waitSampling(5)
      helper.query(branchPc)
      // PHT是01，msb=0，所以预测Not Taken
      helper.expectResponse(helper.BpuResponseSim(isTaken = false, target = 0))

      println("Test 'BPU - PHT Saturation Counter' PASSED")
    }
  }

  thatsAll()
}
