// filename: test/scala/MemoryDisambiguationUnitSpec.scala
// command: testOnly test.scala.MemoryDisambiguationUnitSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.lsu._
import scala.collection.mutable
import scala.util.Random

// =========================================================================
//  Test Intents Namespace
// =========================================================================
object MduTestIntents {
  sealed trait TestIntent
  case object DispatchStore extends TestIntent
  case object ResolveStore extends TestIntent
  case object QueryLoad extends TestIntent
  case object HardFlush extends TestIntent
  case object Wait extends TestIntent
}

// =========================================================================
//  Test Helper
// =========================================================================


class MduTestHelper(dut: MemoryDisambiguationUnit)(implicit cd: ClockDomain) {
  def init(): Unit = {
    dut.io.storeDispatched.valid #= false
    dut.io.storeAddressResolved.valid #= false
    dut.io.queryPort.cmd.valid #= false
    dut.io.flush #= false
    cd.waitSampling()
  }
  def dispatchStore(robPtr: BigInt): Unit = {
    dut.io.storeDispatched.valid #= true
    dut.io.storeDispatched.payload #= robPtr
    cd.waitSampling()
    dut.io.storeDispatched.valid #= false
  }
  def resolveStore(robPtr: BigInt): Unit = {
    dut.io.storeAddressResolved.valid #= true
    dut.io.storeAddressResolved.payload #= robPtr
    cd.waitSampling()
    dut.io.storeAddressResolved.valid #= false
  }
  def queryLoad(robPtr: BigInt): Boolean = {
    dut.io.queryPort.cmd.valid #= true
    dut.io.queryPort.cmd.payload.robPtr #= robPtr
    cd.waitSampling()
    dut.io.queryPort.cmd.valid #= false
    dut.io.queryPort.rsp.canProceed.toBoolean
  }
  def issueHardFlush(): Unit = {
    dut.io.flush #= true
    cd.waitSampling()
    dut.io.flush #= false
  }
}

// =========================================================================
//  Golden Model (作为状态机封装)
// =========================================================================

/**
 * 黄金模型 MDU，模拟 MemoryDisambiguationUnit 的行为。
 * 接收输入事件，更新内部状态，并提供查询响应。
 */
class MduGoldenModel(pCfg: PipelineConfig) {
  import MduTestIntents._ // 导入 TestIntents

  // 内部状态：HashSet 用于高效的插入和删除（因为robPtr不保证顺序入队）
  private val pendingStores = mutable.HashSet[BigInt]()

  // 辅助函数: 比较带回绕的ROB指针 (A是否比B旧)
  // robPtr 通常是 (GenerationBit, Index) 的形式
  private def isOlder(robPtrA: BigInt, robPtrB: BigInt): Boolean = {
    val width = pCfg.robPtrWidth.value
    require(width > 1, "robPtrWidth must be greater than 1 for generation bit logic")

    val genBitMask = BigInt(1) << (width - 1)
    val idxMask = genBitMask - 1 // 索引的掩码

    val genA = (robPtrA & genBitMask) != 0
    val idxA = robPtrA & idxMask
    val genB = (robPtrB & genBitMask) != 0
    val idxB = robPtrB & idxMask

    // 经典ROB指针比较逻辑
    (genA == genB && idxA < idxB) || (genA != genB && idxA > idxB)
  }

  /**
   * 模拟MDU的输入事件，更新内部状态。
   * 这个方法代表 MDU 在每个周期接收到的输入。
   */
  def tick(
      storeDispatchedRobPtrOpt: Option[BigInt],
      storeAddressResolvedRobPtrOpt: Option[BigInt],
      flushSignal: Boolean
  ): Unit = {
    // 1. 处理 flush
    if (flushSignal) {
      pendingStores.clear()
      return // Flush优先级最高，清空后不处理其他事件
    }

    // 2. 处理 Store Resolve (出队)
    storeAddressResolvedRobPtrOpt.foreach { robPtr =>
      pendingStores.remove(robPtr)
    }

    // 3. 处理 Store Dispatch (入队) - 必须在 Resolve 之后，模拟在同一个周期 Store 先被“解决”再被“分派”的可能性
    // 或者理解为，如果一个Store在同一周期被Dispatched和Resolved，Resolved优先
    storeDispatchedRobPtrOpt.foreach { robPtr =>
      pendingStores.add(robPtr)
    }
  }

  /**
   * 模拟MDU的查询输出。
   * 这个方法代表 MDU 在每个周期根据当前内部状态给出的组合逻辑响应。
   */
  def query(loadRobPtr: BigInt): Boolean = {
    // 检查是否存在任何一个比当前Load更旧的pendingStore
    // 如果存在，Load就不能Proceed
    !pendingStores.exists(storeRobPtr => isOlder(storeRobPtr, loadRobPtr))
  }

  // 用于调试的辅助方法
  def getPendingStores: Set[BigInt] = pendingStores.toSet
}


// =========================================================================
//  Test Suite
// =========================================================================

class MemoryDisambiguationUnitSpec extends CustomSpinalSimFunSuite {
    import MduTestIntents._ // 导入 TestIntents
  // 使用合法的 PipelineConfig
  val pCfg = PipelineConfig(
    robDepth = 16 // robDepth 为 16，robPtrWidth 将自动推断为 log2Up(16) + 1 = 5
  )
  val mduDepth = 8 // MDU 的内部追踪深度

  test("MDU - Fuzzy Test with Random Intents") {
    SimConfig.withWave.compile(new MemoryDisambiguationUnit(pCfg, mduDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val helper = new MduTestHelper(dut)
      val model = new MduGoldenModel(pCfg) // 使用新的黄金模型
      val rand = new Random(seed = 42)

      helper.init()

      // 用于追踪模型中“已分派但未解决”的Store，以确保 Resolve 操作的合法性
      val dispatchedButNotResolvedRobPtrs = mutable.Queue[BigInt]()
      
      // ROB指针生成器
      var currentRobIdx: BigInt = 0
      val maxRobIdx = pCfg.robDepth - 1
      var currentGenBit: BigInt = 0 // 用于生成 robPtr 的最高位 (generation bit)

      def getNextRobPtr(): BigInt = {
        val ptr = currentRobIdx | currentGenBit // 合并索引和生成位
        if (currentRobIdx == maxRobIdx) {
          currentRobIdx = 0
          currentGenBit = if (currentGenBit == 0) BigInt(1) << (pCfg.robPtrWidth.value - 1) else 0
        } else {
          currentRobIdx += 1
        }
        ptr
      }

      val iterations = 50000

      println("=== Starting MDU Fuzzy Test ===")

      for (i <- 0 until iterations) {
        // --- DUT 和模型输入信号准备 ---
        var storeDispatchedRobPtr:   Option[BigInt] = None
        var storeAddressResolvedRobPtr: Option[BigInt] = None
        var flushSignal: Boolean = false
        var loadQueryRobPtr:         Option[BigInt] = None // 用于QueryLoad意图
        
        // --- 随机生成并执行一个意图 ---
        val choice = rand.nextInt(100)

        // 动态调整意图权重
        val intent = choice match {
          // StoreDispatch: 如果 MDU 内部有空间 (这里我们假设 MDU 满了会上游反压，
          // 但在Sim模式下，我们只需确保 MDU 的 pendingStores 不会溢出)
          case c if c < 45 && dispatchedButNotResolvedRobPtrs.size < mduDepth =>
            DispatchStore
          // StoreResolve: 如果有 Store 待解决
          case c if c < 60 && dispatchedButNotResolvedRobPtrs.nonEmpty =>
            ResolveStore
          // LoadQuery: 经常查询 Load
          case c if c < 90 =>
            QueryLoad
          // HardFlush: 偶尔冲刷
          case c if c < 95 =>
            HardFlush
          // Wait: 偶尔等待
          case _ =>
            Wait
        }

        // --- 根据意图设置 DUT 和模型输入 ---
        intent match {
          case DispatchStore =>
            val robPtr = getNextRobPtr()
            println(s"[Intent @ ${simTime()}] DispatchStore(robPtr=0x${robPtr.toString(16)})")
            helper.dispatchStore(robPtr)
            storeDispatchedRobPtr = Some(robPtr)
            dispatchedButNotResolvedRobPtrs.enqueue(robPtr)

          case ResolveStore =>
            val robPtr = dispatchedButNotResolvedRobPtrs.dequeue() // 从队列中取出一个最老的来解决
            println(s"[Intent @ ${simTime()}] ResolveStore(robPtr=0x${robPtr.toString(16)})")
            helper.resolveStore(robPtr)
            storeAddressResolvedRobPtr = Some(robPtr)

          case QueryLoad =>
            val robPtr = getNextRobPtr()
            println(s"[Intent @ ${simTime()}] QueryLoad(robPtr=0x${robPtr.toString(16)})")
            loadQueryRobPtr = Some(robPtr) // 设置给模型查询用
            
            // DUT查询由 helper.queryLoad 完成，并在内部立即验证

          case HardFlush =>
            println(s"[Intent @ ${simTime()}] HardFlush")
            helper.issueHardFlush()
            flushSignal = true
            dispatchedButNotResolvedRobPtrs.clear() // 清空模型追踪的队列

          case Wait =>
            val waitCycles = rand.nextInt(5) + 1
            println(s"[Intent @ ${simTime()}] Wait(${waitCycles})")
            cd.waitSampling(waitCycles -1 ) // Wait for N cycles, so tick for N-1 cycles
            // 然后在循环末尾的 cd.waitSampling() 会完成第N个周期
        }
        
        // --- 模型状态更新 ---
        // 模拟 DUT 的时序：MDU 模型在一个周期开始时接收所有输入事件
        model.tick(
          storeDispatchedRobPtr,
          storeAddressResolvedRobPtr,
          flushSignal
        )

        // --- 验证 QueryLoad 意图 ---
        loadQueryRobPtr.foreach { robPtr =>
          val dutResult = helper.queryLoad(robPtr) // helper.queryLoad 包含了 DUT 的交互和 cd.waitSampling(1)
          val modelResult = model.query(robPtr)
          
          assert(dutResult == modelResult,
            s"""[Verify FAIL @ ${simTime()}] MDU MISMATCH for Load(robPtr=0x${robPtr.toString(16)})!
               |  -> DUT says: ${if(dutResult) "CAN PROCEED" else "MUST WAIT"}
               |  -> Model says: ${if(modelResult) "CAN PROCEED" else "MUST WAIT"}
               |  Model's pending stores: ${model.getPendingStores.map("0x"+_.toString(16)).mkString(", ")}
               |""".stripMargin
          )
          println(s"  -> Verify OK: Both DUT and Model say ${if(dutResult) "CAN PROCEED" else "MUST WAIT"}")
        }


        // --- 前进一个时钟周期 ---
        cd.waitSampling() // 确保所有操作在时钟沿更新
      }
      println(s"=== MDU Fuzzy Test Completed Successfully after ${iterations} iterations ===")
    }
  }
  
  // 基础功能测试
  test("MDU - Basic functional tests") {
    SimConfig.withWave.compile(new MemoryDisambiguationUnit(pCfg, mduDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        val helper = new MduTestHelper(dut)
        val model = new MduGoldenModel(pCfg) // 用于验证
        helper.init()
        
        // Test 1: Load should proceed when no pending stores
        var robPtr = BigInt(0)
        assert(helper.queryLoad(robPtr), "Test 1 Failed: Load should proceed with no pending stores.")
        model.tick(None, None, false) // 模型也走一拍

        // Test 2: Load should be blocked by an older store
        robPtr = 4
        helper.dispatchStore(robPtr)
        model.tick(Some(robPtr), None, false) // 模型更新
        assert(!helper.queryLoad(robPtr=5), "Test 2 Failed: Load should be blocked by an older pending store.")
        
        // Test 3: Load should proceed with a newer store (robPtr=3, 假设 robPtr=4 是刚 dispatch 的)
        // 注意 robPtr=3 比 robPtr=4 旧，这里测试的是 robPtr=3 访问一个比它新的 Store
        // 应该是不受阻碍的
        assert(helper.queryLoad(robPtr=3), "Test 3 Failed: Load should proceed with a newer pending store.")

        // Test 4: Resolving the store should unblock the load
        helper.resolveStore(robPtr=4)
        model.tick(None, Some(robPtr), false) // 模型更新
        assert(helper.queryLoad(robPtr=5), "Test 4 Failed: Resolving store did not unblock the load.")

        // Test 5: Flush should clear all pending stores
        robPtr = BigInt(10) // 明确为 BigInt
        helper.dispatchStore(robPtr)
        model.tick(Some(robPtr), None, false) // 修正：直接传入 robPtr 变量
        robPtr = BigInt(11) // 明确为 BigInt
        helper.dispatchStore(robPtr)
        model.tick(Some(robPtr), None, false) // 修正：直接传入 robPtr 变量
        helper.issueHardFlush()
        model.tick(None, None, true)
        assert(helper.queryLoad(robPtr=BigInt(12)), "Test 5 Failed: Flush did not clear pending stores.") // 明确为 BigInt

        println("Basic functional tests passed.")
    }
  }

  thatsAll
}
