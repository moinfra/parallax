// filename: src/test/scala/parallax/fetch/icache/ICacheSpec.scala
// testOnly test.scala.ICachePluginSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import parallax.common.PipelineConfig
import parallax.utilities._
import spinal.lib.bus.amba4.axi.Axi4CrossbarFactory
import spinal.lib.bus.misc.SizeMapping
import scala.collection.mutable
import parallax.fetch.icache._

// =========================================================================
//  Test Helper Classes
// =========================================================================

/** Captures the ICache response for easy handling in Scala testbench. */
case class ICacheRspCapture(
    transactionId: BigInt,
    instructions: Seq[BigInt],
    wasHit: Boolean,
    redo: Boolean
)

object ICacheRspCapture {
  def apply(payload: ICacheRsp): ICacheRspCapture = {
    ICacheRspCapture(
      transactionId = payload.transactionId.toBigInt,
      instructions = payload.instructions.map(_.toBigInt),
      wasHit = payload.wasHit.toBoolean,
      redo = payload.redo.toBoolean
    )
  }
}

// =========================================================================
//  Test Bench Component
// =========================================================================

/** A testbench component that wires up the ICachePlugin with a simulated memory system.
  * This setup allows for isolated testing of the ICache's functionality.
  */
class ICacheTestBench(
    iCacheCfg: ICacheConfig,
    axiCfg: Axi4Config,
    pcWidth: Int
) extends Component {

  val io = new Bundle {
    // Expose the ICache port for driving commands and monitoring responses
    val iCachePort = slave(ICachePort(iCacheCfg, pcWidth))
    // Expose invalidate signal for testing
    val invalidate = in Bool ()
    // Expose FSM state for white-box testing
    val sim_fsmStateId = out UInt (2 bits)
  }

  // The Framework will automatically wire up plugins that provide/require services.
  val framework = new Framework(
    Seq(
      // The ICache plugin under test.
      // It requires an AxiReadOnlyMasterService, which TestOnlyMemSystemPlugin provides.
      new ICachePlugin(iCacheCfg, axiCfg, pcWidth),

      // The simulated memory system.
      // It provides an AxiReadOnlyMasterService, which the ICachePlugin will consume.
      new TestOnlyMemSystemPlugin(axiCfg)
    )
  )

  // --- Connect the testbench IO to the ICachePlugin ---
  val iCacheService = framework.getService[ICacheService]
  val iCachePort = iCacheService.newICachePort()

  iCachePort.cmd << io.iCachePort.cmd
  io.iCachePort.rsp << iCachePort.rsp
  iCacheService.invalidate := io.invalidate

  // --- Expose internal signals for white-box testing ---
  val iCachePlugin = framework.getService[ICachePlugin]
  // This assumes ICachePlugin has a 'management' area with 'sim_fsmStateId'
  io.sim_fsmStateId := iCachePlugin.logic.management.sim_fsmStateId

  // Expose the SRAM for direct memory manipulation in the test case
  val memSystem = framework.getService[TestOnlyMemSystemPlugin]
  val sram = memSystem.getSram()
  sram.io.simPublic()
}

// =========================================================================
//  Test Helper Object
// =========================================================================

object ICacheTestHelper {

  /** Drives an ICache command for one cycle. */
  def driveCmd(dut: ICacheTestBench, addr: BigInt, tid: Int): Unit = {
    dut.io.iCachePort.cmd.valid #= true
    dut.io.iCachePort.cmd.payload.address #= addr
    dut.io.iCachePort.cmd.payload.transactionId #= tid
    dut.clockDomain.waitSampling()
    dut.io.iCachePort.cmd.valid #= false
  }

  /** Writes a full cache line of data directly into the simulated SRAM. */
  def writeSramLine(dut: ICacheTestBench, virtualLineAddr: BigInt, data: Seq[BigInt]): Unit = {
    val iCfg = dut.iCachePort.iCfg
    val sramCfg = dut.sram.config
    require(data.length == iCfg.lineWords, s"Data length must match cache line words (${iCfg.lineWords})")

    // --- 地址转换逻辑 ---
    // 1. 获取SRAM的虚拟基地址
    val sramVirtualBase = sramCfg.virtualBaseAddress

    // 2. 计算相对于基地址的物理字节偏移量
    val physicalByteOffset = virtualLineAddr - sramVirtualBase

    // 3. 检查物理地址是否在SRAM的容量范围内
    require(physicalByteOffset >= 0, "Virtual address is below SRAM base address")
    require(
      physicalByteOffset + iCfg.bytesPerLine <= sramCfg.sizeBytes,
      s"Address ${physicalByteOffset} is out of SRAM physical bounds (sramVirtualBase=${sramVirtualBase}, sramSize=${sramCfg.sizeBytes})"
    )

    ParallaxLogger.info(
      s"[TB Helper] Writing to SRAM: Virtual=0x${virtualLineAddr.toString(16)} -> PhysicalOffset=0x${physicalByteOffset.toString(16)}"
    )

    for ((word, i) <- data.zipWithIndex) {
      // 4. 使用计算出的物理字节地址写入
      val physicalWordAddr = physicalByteOffset + i * 4
      dut.sram.io.tb_writeEnable #= true
      dut.sram.io.tb_writeAddress #= physicalWordAddr
      dut.sram.io.tb_writeData #= word
      dut.clockDomain.waitSampling()
    }
    dut.sram.io.tb_writeEnable #= false
  }

  /** Initializes the DUT's inputs. */
  def initDut(dut: ICacheTestBench): Unit = {
    dut.io.iCachePort.cmd.valid #= false
    dut.io.invalidate #= false
    dut.clockDomain.waitSampling()
  }

  // 在 ICacheTestHelper object 中添加这个辅助函数

  /** 一个健壮的辅助函数，用于请求一个地址，处理可能的miss和refill，
    * 并最终确认该地址在缓存中是一个带有正确数据的hit。
    * @param dut The device under test.
    * @param rspQueue The response queue.
    * @param addr The address to request.
    * @param tidBase A base for transaction IDs to ensure uniqueness.
    * @param expectedData The data expected on a hit.
    */
  def requestAndConfirmHit(
      dut: ICacheTestBench,
      rspQueue: mutable.Queue[ICacheRspCapture],
      addr: BigInt,
      tidBase: Int,
      expectedData: Seq[BigInt]
  ): Unit = {

    ParallaxLogger.info(
      s"[TB Helper] Requesting and confirming hit for addr 0x${addr.toString(16)} with TID base $tidBase"
    )

    // 清空队列，准备接收新响应
    rspQueue.clear()

    // 1. 发出初始请求
    driveCmd(dut, addr, tid = tidBase)

    // 2. 等待第一个响应
    dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
    val firstRsp = rspQueue.dequeue()

    // 3. 如果是 miss，则等待 FSM 完成填充
    if (!firstRsp.wasHit) {
      ParallaxLogger.info(
        s"[TB Helper] Initial request was a MISS (TID=${firstRsp.transactionId}). Waiting for FSM to complete."
      )
      assert(firstRsp.redo, s"Miss for addr 0x${addr.toString(16)} should have redo=true")

      // 等待 FSM 返回 IDLE 状态
      val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100) {
        dut.io.sim_fsmStateId.toInt == 0 // 0 is IDLE
      }
      if (timeout) {
        assert(false, s"FSM did not return to IDLE while filling addr 0x${addr.toString(16)}")
      }
      ParallaxLogger.info("[TB Helper] FSM is IDLE. Refill should be complete.")
    }

    // 4. 再次请求以确认它现在是一个 HIT
    // 无论第一次是hit还是miss，这一步都必须执行，以确保状态一致
    rspQueue.clear()
    driveCmd(dut, addr, tid = tidBase + 1)

    val timeout = dut.clockDomain.waitSamplingWhere(timeout = 20)(rspQueue.nonEmpty)
    if (timeout) {
      assert(false, s"Did not receive response to confirmation request for addr 0x${addr.toString(16)}")
    }
    val hitRsp = rspQueue.dequeue()

    assert(
      hitRsp.wasHit,
      s"Confirmation request for addr 0x${addr.toString(16)} (TID=${hitRsp.transactionId}) was not a HIT."
    )
    assert(!hitRsp.redo, s"Confirmation HIT for addr 0x${addr.toString(16)} should not have redo=true.")
    assert(hitRsp.transactionId == tidBase + 1, "TID mismatch on hit confirmation.")
    assert(hitRsp.instructions == expectedData, "Data mismatch on hit confirmation.")

    ParallaxLogger.info(s"[TB Helper] Successfully confirmed HIT for addr 0x${addr.toString(16)}")
  }
}

// =========================================================================
//  The Test Suite
// =========================================================================

class ICachePluginSpec extends CustomSpinalSimFunSuite {

  // --- Common configurations for all tests ---
  val iCacheCfg = ICacheConfig(
    totalSize = (4 KiB).toInt,
    ways = 2,
    bytesPerLine = 32,
    fetchWidth = 2,
    enableLog = true
  )

  val axiCfg = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4, // Example value
    useLock = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useRegion = false
  )

  val pcWidth = 32

  // 增加了UNKNOWN状态，以防FSM进入未预期的状态
  val fsmStateNames = Map(0 -> "IDLE", 1 -> "SEND_REQ", 2 -> "RECEIVE_DATA", 3 -> "UNKNOWN")

  // --- Test Cases ---
  test("Core_Flow_Miss_Refill_Hit") {
    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)

      // --- Setup Response Monitor ---
      val rspQueue = mutable.Queue[ICacheRspCapture]()
      FlowMonitor(dut.io.iCachePort.rsp, dut.clockDomain) { payload =>
        rspQueue.enqueue(ICacheRspCapture(payload))
      }

      // --- Test Data ---
      val testAddr = BigInt("00000000", 16) // A typical instruction address
      // Ensure the test address is line-aligned
      val lineAddr = testAddr - (testAddr % iCacheCfg.bytesPerLine)
      val testData = Seq.tabulate(iCacheCfg.lineWords)(i => lineAddr + i * 4)

      // 1. Inject test data directly into the simulated SRAM
      ParallaxLogger.info(s"[TB] Phase 1: Injecting data into SRAM at line address 0x${lineAddr.toString(16)}")
      ICacheTestHelper.writeSramLine(dut, lineAddr, testData)
      dut.clockDomain.waitSampling(5)

      // 2. First request: This must be a MISS
      ParallaxLogger.info(s"[TB] Phase 2: Requesting 0x${lineAddr.toString(16)}, expecting MISS and REDO")
      ICacheTestHelper.driveCmd(dut, lineAddr, tid = 1)

      // The first response should be a miss, requesting a redo
      dut.clockDomain.waitSampling(2) // Wait for F1->F2 pipeline
      assert(rspQueue.nonEmpty, "Should have received a response after 2 cycles")
      var rsp = rspQueue.dequeue()
      assert(rsp.transactionId == 1, "TID mismatch on first response")
      assert(!rsp.wasHit, "First request should be a MISS")
      assert(rsp.redo, "Miss should trigger a REDO")

      // 使用字符串比较FSM状态
      val currentStateAfterMiss = fsmStateNames(dut.io.sim_fsmStateId.toInt)
      assert(currentStateAfterMiss != "IDLE", s"FSM should have left IDLE state, but is in $currentStateAfterMiss")
      ParallaxLogger.info(s"[TB] Correctly received MISS/REDO. FSM state: $currentStateAfterMiss")

      // 3. Wait for Refill to complete
      // The upstream (testbench) should be "redoing" the request. We simulate this by
      // waiting, assuming the Fetch Control would keep the `cmd` asserted or re-assert it.
      // The ICache is now busy filling. We wait until the FSM returns to IDLE.
      ParallaxLogger.info(s"[TB] Phase 3: Waiting for FSM to complete refill and return to IDLE")

      // 替换 waitUntil 为带超时检测的 waitSamplingWhere
      val timeoutRefill = dut.clockDomain.waitSamplingWhere(timeout = 100) {
        fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE"
      }
      if (timeoutRefill) {
        val finalState = fsmStateNames(dut.io.sim_fsmStateId.toInt)
        assert(false, s"FSM did not return to IDLE state within 100 cycles after refill. Current state: $finalState")
      }
      ParallaxLogger.info(s"[TB] FSM has returned to IDLE. Refill complete.")

      // 4. Second request to the same address: This must be a HIT
      // The Fetch Control, after seeing the last REDO, would send the command again.
      ParallaxLogger.info(s"[TB] Phase 4: Requesting 0x${lineAddr.toString(16)} again, expecting HIT")
      // Clear queue of any lingering redo responses from the refill period
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, lineAddr, tid = 2)

      dut.clockDomain.waitSampling(2)
      assert(rspQueue.nonEmpty, "Should have received a HIT response")
      rsp = rspQueue.dequeue()
      assert(rsp.transactionId == 2, "TID mismatch on hit response")
      assert(rsp.wasHit, "Second request should be a HIT")
      assert(!rsp.redo, "Hit should not trigger a REDO")
      assert(rsp.instructions == testData, "Hit data does not match injected data")

      // 使用字符串比较FSM状态
      val currentStateAfterHit = fsmStateNames(dut.io.sim_fsmStateId.toInt)
      assert(
        currentStateAfterHit == "IDLE",
        s"FSM should remain in IDLE state on a hit, but is in $currentStateAfterHit"
      )
      ParallaxLogger.info(s"[TB] Correctly received HIT with matching data.")

      ParallaxLogger.debug("[TB] Test 'Core_Flow_Miss_Refill_Hit' PASSED.")
    }
  }

// 在 ICachePluginSpec class 内部，添加一个新的 test case

  test("Back_To_Back_Miss_On_Same_Set") {
    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)

      // --- Setup Monitor ---
      val rspQueue = mutable.Queue[ICacheRspCapture]()
      FlowMonitor(dut.io.iCachePort.rsp, dut.clockDomain) { payload =>
        rspQueue.enqueue(ICacheRspCapture(payload))
      }

      // --- Test Data Setup ---
      // 1. 选择两个地址，它们映射到同一个缓存组 (set) 但 tag 不同
      val addrA = BigInt("00000000", 16) // index=0, tag=0
      // sets = 4KiB / 2ways / 32B/line = 64 sets. indexWidth = 6.
      // bytesPerLine = 32. offsetWidth = 5.
      // addr[10:5] is the index.
      // To get a different tag with the same index, we need to change bits above bit 10.
      // e.g., change bit 11. 2^11 = 2048 = 0x800
      val addrB = addrA + (1 << (iCacheCfg.offsetWidth + iCacheCfg.indexWidth))

      val lineAddrA = addrA - (addrA % iCacheCfg.bytesPerLine)
      val lineAddrB = addrB - (addrB % iCacheCfg.bytesPerLine)

      ParallaxLogger.info(s"[TB] AddrA=0x${lineAddrA.toString(16)}, AddrB=0x${lineAddrB.toString(16)}")
      assert(
        (lineAddrA & 0x7e0) == (lineAddrB & 0x7e0),
        "Addresses must map to the same set index"
      ) // 0x7E0 for 64 sets, 32B/line
      assert(lineAddrA != lineAddrB, "Addresses must be different")

      val testDataA = Seq.tabulate(iCacheCfg.lineWords)(i => lineAddrA + i * 4)
      val testDataB = Seq.tabulate(iCacheCfg.lineWords)(i => lineAddrB + i * 4)

      // 2. 将两行数据都写入SRAM
      ICacheTestHelper.writeSramLine(dut, lineAddrA, testDataA)
      ICacheTestHelper.writeSramLine(dut, lineAddrB, testDataB)
      dut.clockDomain.waitSampling(5)

      // --- Test Execution ---
      // 3. 发起对 AddrA 的请求，这将是一个 miss 并开始 refill
      ParallaxLogger.info(s"[TB] Phase 1: Requesting AddrA (0x${lineAddrA.toString(16)}), starting first refill.")
      ICacheTestHelper.driveCmd(dut, lineAddrA, tid = 1)

      // 等待 FSM 完成填充。我们不在这里模拟 redo，直接等待FSM完成。
      val timeoutA = dut.clockDomain.waitSamplingWhere(timeout = 100) {
        fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE"
      }
      if (timeoutA) assert(false, "FSM did not return to IDLE for AddrA")
      ParallaxLogger.info(s"[TB] Refill for AddrA complete.")
      dut.clockDomain.waitSampling(2) // Give it a couple of cycles to settle

      // 清空响应队列，准备下一次请求
      rspQueue.clear()

      // 4. 发起对 AddrB 的请求。这也将是一个 miss，替换掉 way 1。
      ParallaxLogger.info(s"[TB] Phase 2: Requesting AddrB (0x${lineAddrB.toString(16)}), starting second refill.")
      ICacheTestHelper.driveCmd(dut, lineAddrB, tid = 2)

      // 等待 FSM 完成对 AddrB 的填充
      val timeoutB = dut.clockDomain.waitSamplingWhere(timeout = 100) {
        fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE"
      }
      if (timeoutB) assert(false, "FSM did not return to IDLE for AddrB")
      ParallaxLogger.info(s"[TB] Refill for AddrB complete.")
      dut.clockDomain.waitSampling(2)

      // 5. 关键测试点: 再次立即请求 AddrA。
      // 此时，AddrA 的数据应该还在缓存中 (way 0)，而 AddrB 的数据在 way 1。
      // 这次请求应该是一个完美的 HIT。如果发生了冲突，这里读出的数据可能会是 AddrB 的，或者损坏。
      ParallaxLogger
        .info(s"[TB] Phase 3: Requesting AddrA (0x${lineAddrA.toString(16)}) again. MUST be a HIT with correct data.")
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, lineAddrA, tid = 3)

      dut.clockDomain.waitSampling(2)
      assert(rspQueue.nonEmpty, "Should have received a response for AddrA hit")
      val rsp = rspQueue.dequeue()
      assert(rsp.transactionId == 3, "TID mismatch on AddrA hit response")
      assert(rsp.wasHit, "Request to AddrA after filling AddrB should be a HIT")
      assert(!rsp.redo, "Hit should not trigger a REDO")

      // 这是最严格的检查
      assert(
        rsp.instructions == testDataA,
        s"HIT data for AddrA is corrupted! Got ${rsp.instructions}, Expected ${testDataA}"
      )

      ParallaxLogger.debug("[TB] Test 'Back_To_Back_Miss_On_Same_Set' PASSED.")
    }
  }

  // 在 ICachePluginSpec class 内部，添加这个新的 fuzz test case

  import scala.util.Random

// 在 ICachePluginSpec class 内部

  import scala.util.Random

  test("Fuzzing_With_Random_Delays_And_Aggressive_Redo") {
    val seed = Random.nextInt()
    // val seed = some_failing_seed // for reproducing failures
    val rand = new Random(seed)
    ParallaxLogger.info(s"Fuzz test starting with seed: $seed")

    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)
      // --- 2. 准备测试数据和地址 ---
      val sramSize = 4000
      val numLines = sramSize / iCacheCfg.bytesPerLine

      val addressPool = mutable.ArrayBuffer[BigInt]()
      for (i <- 0 until 16) {
        for (j <- 0 until iCacheCfg.sets) {
          val addr = (i * (1 << (iCacheCfg.offsetWidth + iCacheCfg.indexWidth))) + (j * iCacheCfg.bytesPerLine)
          if (addr + iCacheCfg.bytesPerLine <= sramSize) {
            addressPool += addr
          }
        }
      }

      val memoryContent = mutable.Map[BigInt, Seq[BigInt]]()
      for (addr <- addressPool) {
        // --- 修复点 1: Int -> BigInt ---
        val data = Seq.tabulate(iCacheCfg.lineWords)(_ => BigInt(rand.nextInt(Int.MaxValue)) & 0xffffffffL)
        memoryContent(addr) = data
        ICacheTestHelper.writeSramLine(dut, addr, data)
      }
      dut.clockDomain.waitSampling(5)

      // --- 3. 创建一个攻击性的 Driver 线程 ---
      var transactionId = 0
      // lastCmd现在存储Scala类型的值，而不是硬件信号
      var lastCmdSent: (BigInt, Int) = (0, 0) // (address, tid)
      val maxTransactions = 255
      val rspQueue = mutable.Queue[ICacheRspCapture]()

      val driver = fork {
        // 初始发送第一个请求
        transactionId = 1
        val initialAddr = addressPool.head
        lastCmdSent = (initialAddr, transactionId)
        dut.io.iCachePort.cmd.valid #= true
        dut.io.iCachePort.cmd.payload.address #= initialAddr
        dut.io.iCachePort.cmd.payload.transactionId #= transactionId
        dut.clockDomain.waitSampling()
        dut.io.iCachePort.cmd.valid #= false

        while (transactionId < maxTransactions) {
          dut.clockDomain.waitSampling()

          if (rspQueue.nonEmpty) {
            val rsp = rspQueue.dequeue()

            if (rsp.wasHit) {
              val expectedData = memoryContent(lastCmdSent._1)
              assert(
                rsp.instructions == expectedData,
                s"HIT DATA MISMATCH on TID=${rsp.transactionId}, Addr=0x${lastCmdSent._1.toString(16)}"
              )
            }

            var nextAddr = BigInt(0)

            if (rsp.redo) {
              if (rand.nextFloat() < 0.7) { // 70% 概率 redo
                ParallaxLogger
                  .info(s"[Driver] Redoing TID=${rsp.transactionId} for Addr=0x${lastCmdSent._1.toString(16)}")
                nextAddr = lastCmdSent._1
                // tid 保持不变
              } else {
                transactionId += 1
                nextAddr = addressPool(rand.nextInt(addressPool.size))
                ParallaxLogger.info(
                  s"[Driver] Abandoning redo, sending NEW aggressive cmd TID=${transactionId} for Addr=0x${nextAddr.toString(16)}"
                )
              }
            } else {
              transactionId += 1
              nextAddr = addressPool(rand.nextInt(addressPool.size))
              ParallaxLogger.info(s"[Driver] Sending next cmd TID=${transactionId} for Addr=0x${nextAddr.toString(16)}")
            }

            lastCmdSent = (nextAddr, transactionId)
            dut.io.iCachePort.cmd.valid #= true
            dut.io.iCachePort.cmd.payload.address #= nextAddr
            dut.io.iCachePort.cmd.payload.transactionId #= transactionId
            dut.clockDomain.waitSampling()
            dut.io.iCachePort.cmd.valid #= false
          }
        }
      }

      // --- 4. 创建监控线程 ---
      val monitor = fork {
        while (transactionId < maxTransactions) {
          if (dut.io.iCachePort.rsp.valid.toBoolean) {
            rspQueue.enqueue(ICacheRspCapture(dut.io.iCachePort.rsp.payload))
          }
          dut.clockDomain.waitSampling()
        }
      }

      driver.join()
      monitor.join()

      dut.clockDomain.waitSampling(10)
      ParallaxLogger.debug(s"[TB] Fuzz test with seed $seed PASSED.")
    }
  }

// 在 ICachePluginSpec class 内部，添加这个新的测试

  test("LRU_Replacement_Verification") {
    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)

      val rspQueue = mutable.Queue[ICacheRspCapture]()
      FlowMonitor(dut.io.iCachePort.rsp, dut.clockDomain) { payload =>
        rspQueue.enqueue(ICacheRspCapture(payload))
      }

      // --- 1. 地址和数据准备 ---
      // 选择三个地址，它们必须映射到同一个缓存组(set)，但tag不同
      val sramSize = 16384
      val baseAddr = BigInt("1000", 16) // 选一个非0地址开始，避免边界效应
      val lineSize = iCacheCfg.bytesPerLine
      val setSize = lineSize * iCacheCfg.ways
      val indexMask = (iCacheCfg.sets - 1) * lineSize
      val tagIncrement = (1 << (iCacheCfg.offsetWidth + iCacheCfg.indexWidth))

      val addrA = baseAddr
      val addrB = addrA + tagIncrement
      val addrC = addrB + tagIncrement

      // 确保所有地址都在SRAM范围内
      require(addrC + lineSize <= sramSize, "Test addresses exceed SRAM size")

      // 验证它们在同一个set
      assert((addrA & indexMask) == (addrB & indexMask) && (addrA & indexMask) == (addrC & indexMask))
      ParallaxLogger.info(
        s"Using addresses in the same set: A=0x${addrA.toString(16)}, B=0x${addrB.toString(16)}, C=0x${addrC.toString(16)}"
      )

      val testDataA = Seq.tabulate(iCacheCfg.lineWords)(i => addrA + i * 4)
      val testDataB = Seq.tabulate(iCacheCfg.lineWords)(i => addrB + i * 4)
      val testDataC = Seq.tabulate(iCacheCfg.lineWords)(i => addrC + i * 4)

      ICacheTestHelper.writeSramLine(dut, addrA, testDataA)
      ICacheTestHelper.writeSramLine(dut, addrB, testDataB)
      ICacheTestHelper.writeSramLine(dut, addrC, testDataC)
      dut.clockDomain.waitSampling(5)

      // --- 2. 步骤 1: 填充 AddrA (miss, 占用 way 0) ---
      ParallaxLogger.info("[TB] Step 1: Requesting AddrA (Miss -> fill way 0)")
      ICacheTestHelper.driveCmd(dut, addrA, tid = 1)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      var rsp = rspQueue.dequeue()
      assert(!rsp.wasHit && rsp.redo, "AddrA initial request should be a miss/redo")
      dut.clockDomain.waitSamplingWhere(fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE")
      // 再次请求以确认命中
      ICacheTestHelper.driveCmd(dut, addrA, tid = 11)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrA should be a hit after refill")

      // --- 3. 步骤 2: 填充 AddrB (miss, 占用 way 1) ---
      ParallaxLogger.info("[TB] Step 2: Requesting AddrB (Miss -> fill way 1)")
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrB, tid = 2)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(!rsp.wasHit && rsp.redo, "AddrB request should be a miss/redo")
      dut.clockDomain.waitSamplingWhere(fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE")
      // 再次请求以确认命中
      ICacheTestHelper.driveCmd(dut, addrB, tid = 22)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrB should be a hit after refill")
      // 此时 way 0 (AddrA) 是 LRU

      // --- 4. 步骤 3: 访问 AddrA (hit, 更新LRU) ---
      ParallaxLogger.info("[TB] Step 3: Requesting AddrA again (Hit -> update LRU)")
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrA, tid = 3)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrA re-request should be a hit")
      // 访问后, way 1 (AddrB) 变为 LRU

      // --- 5. 步骤 4: 填充 AddrC (miss, 替换 AddrB) ---
      ParallaxLogger.info("[TB] Step 4: Requesting AddrC (Miss -> should replace AddrB in way 1)")
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrC, tid = 4)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(!rsp.wasHit, "AddrC request should be a miss")
      dut.clockDomain.waitSamplingWhere(fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE")
      // 再次请求以确认命中
      ICacheTestHelper.driveCmd(dut, addrC, tid = 44)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrC should be a hit after refill")

      // --- 6. 验证 ---
      ParallaxLogger.info("[TB] Step 5: Verification")
      // a) AddrA 必须还在缓存中 (hit)
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrA, tid = 5)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrA should still be in cache (HIT)")
      assert(rsp.instructions == testDataA, "AddrA data corrupted")

      // b) AddrB 必须已经被替换掉 (miss)
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrB, tid = 6)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(!rsp.wasHit, "AddrB should have been replaced (MISS)")

      // c) AddrC 必须在缓存中 (hit)
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrC, tid = 7)
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      rsp = rspQueue.dequeue()
      assert(rsp.wasHit, "AddrC should be in cache (HIT)")
      assert(rsp.instructions == testDataC, "AddrC data corrupted")

      ParallaxLogger.debug("[TB] Test 'LRU_Replacement_Verification' PASSED.")
    }
  }
// 在 ICachePluginSpec class 内部，添加这个新的测试

// 在 ICachePluginSpec class 内部

  test("Invalidate_Cache_Test") {
    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)

      val rspQueue = mutable.Queue[ICacheRspCapture]()
      // FlowMonitor不是必需的，因为我们现在在主线程中处理队列
      // 但保留它也没有坏处
      FlowMonitor(dut.io.iCachePort.rsp, dut.clockDomain) { payload =>
        rspQueue.enqueue(ICacheRspCapture(payload))
      }

      // --- 1. 准备数据并填充缓存 ---
      val sramSize = 16384
      val addrA = BigInt("2000", 16)
      require(addrA + iCacheCfg.bytesPerLine <= sramSize, "Test address exceeds SRAM size")
      val testDataA = Seq.tabulate(iCacheCfg.lineWords)(i => addrA + i * 4)
      ICacheTestHelper.writeSramLine(dut, addrA, testDataA)

      // 使用辅助函数填充并确认命中
      ParallaxLogger.info("[TB] Step 1: Filling cache with AddrA and confirming hit.")
      ICacheTestHelper.requestAndConfirmHit(dut, rspQueue, addrA, tidBase = 10, testDataA)
      ParallaxLogger.info("[TB] Cache filled and verified.")

      // --- 2. 执行 Invalidate ---
      ParallaxLogger.info("[TB] Step 2: Asserting invalidate signal for one cycle.")
      dut.io.invalidate #= true
      dut.clockDomain.waitSampling()
      dut.io.invalidate #= false
      dut.clockDomain.waitSampling() // 等待信号传播

      // --- 3. 验证缓存已被清空 ---
      ParallaxLogger.info("[TB] Step 3: Requesting AddrA again, expecting a MISS.")
      rspQueue.clear()
      ICacheTestHelper.driveCmd(dut, addrA, tid = 30)

      // 等待响应，并验证它是一个 miss/redo
      dut.clockDomain.waitSamplingWhere(rspQueue.nonEmpty)
      val missRsp = rspQueue.dequeue()
      assert(!missRsp.wasHit, "AddrA should be a MISS after invalidation.")
      assert(missRsp.redo, "Invalidated entry should cause a miss/redo.")
      ParallaxLogger.info("[TB] Correctly received MISS after invalidation.")

      // --- 4. 验证可以再次填充 ---
      ParallaxLogger.info("[TB] Step 4: Verifying cache can be refilled after invalidation.")
      // 再次使用辅助函数，它会处理miss和refill，并最终确认hit
      ICacheTestHelper.requestAndConfirmHit(dut, rspQueue, addrA, tidBase = 40, testDataA)

      ParallaxLogger.debug("[TB] Test 'Invalidate_Cache_Test' PASSED.")
    }
  }
// 在 ICachePluginSpec class 内部

  test("Fill_Entire_Cache_And_Verify") {
    simConfig.withWave.compile(new ICacheTestBench(iCacheCfg, axiCfg, pcWidth)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      ICacheTestHelper.initDut(dut)

      val rspQueue = mutable.Queue[ICacheRspCapture]()
      FlowMonitor(dut.io.iCachePort.rsp, dut.clockDomain) { payload =>
        rspQueue.enqueue(ICacheRspCapture(payload))
      }

      val sramSize = 16384
      val numCacheLines = iCacheCfg.sets * iCacheCfg.ways
      ParallaxLogger.info(s"[TB] Cache capacity: ${numCacheLines} lines (${iCacheCfg.sets} sets x ${iCacheCfg.ways}).")

      val memoryContent = mutable.Map[BigInt, Seq[BigInt]]()
      val addressesToTest = mutable.ArrayBuffer[BigInt]()

      // --- 1. 生成地址并写入SRAM ---
      ParallaxLogger.info("[TB] Step 1: Generating addresses and writing to SRAM...")
      var tidBase = 10
      for (i <- 0 until numCacheLines) {
        val addr = i * (1 << (iCacheCfg.offsetWidth + iCacheCfg.indexWidth))

        // --- 修复点 1: toString 语法 ---
        if (addr + iCacheCfg.bytesPerLine > sramSize) {
          ParallaxLogger.warning(s"Skipping address 0x${addr.toString()} as it exceeds SRAM size.")
        } else {
          addressesToTest += addr
          // --- 修复点 2: 确保数据类型正确 ---
          val data = Seq.tabulate(iCacheCfg.lineWords)(j => addr + BigInt(j))
          memoryContent(addr) = data
          ICacheTestHelper.writeSramLine(dut, addr, data)
        }
      }
      require(addressesToTest.nonEmpty, "Could not generate any valid test addresses within SRAM size.")
      dut.clockDomain.waitSampling(5)

      // --- 2. 填充整个缓存 ---
      ParallaxLogger.info(s"[TB] Step 2: Filling the entire cache with ${addressesToTest.size} unique lines...")
      for (addr <- addressesToTest) {
        ICacheTestHelper.driveCmd(dut, addr, tid = tidBase)

        // --- 修复点 3: waitSamplingWhere 语法 ---
        var timeout = dut.clockDomain.waitSamplingWhere(20)(rspQueue.nonEmpty)
        if (timeout) assert(false, s"Timeout waiting for miss/redo response for addr 0x${addr.toString(16)}")
        rspQueue.dequeue()

        timeout = dut.clockDomain.waitSamplingWhere(100)(fsmStateNames(dut.io.sim_fsmStateId.toInt) == "IDLE")
        if (timeout) assert(false, s"Timeout waiting for FSM to become IDLE for addr 0x${addr.toString(16)}")

        tidBase += 10
      }
      ParallaxLogger.info("[TB] Cache filling process complete.")

      // --- 3. 验证所有行都已命中且数据正确 ---
      ParallaxLogger.info("[TB] Step 3: Verifying all lines result in a HIT with correct data...")
      for (addr <- addressesToTest) {
        val expectedData = memoryContent(addr)
        ICacheTestHelper.requestAndConfirmHit(dut, rspQueue, addr, tidBase, expectedData)
        tidBase += 10
        dut.clockDomain.waitSampling(Random.nextInt(3) + 1)
      }

      ParallaxLogger.debug("[TB] Test 'Fill_Entire_Cache_And_Verify' PASSED.")
    }
  }
  thatsAll()
}
