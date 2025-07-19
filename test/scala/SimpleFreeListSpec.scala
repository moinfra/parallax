package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.components.rename._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim.SimConfig
import scala.collection.mutable

class SimpleFreeListSpec extends CustomSpinalSimFunSuite {

  // --- Helper to drive allocate ports ---
  def driveAllocatePorts(
      dutIo: SimpleFreeListIO,
      enables: Seq[Boolean]
  ): Unit = {
    require(enables.length == dutIo.config.numAllocatePorts)
    for (i <- 0 until dutIo.config.numAllocatePorts) {
      dutIo.allocate(i).enable #= enables(i)
    }
  }

  // --- Helper to drive free ports ---
  def driveFreePorts(
      dutIo: SimpleFreeListIO,
      frees: Seq[(Boolean, Int)]
  ): Unit = {
    require(frees.length <= dutIo.config.numFreePorts)
    for (i <- 0 until dutIo.config.numFreePorts) {
      if (i < frees.length) {
        dutIo.free(i).enable #= frees(i)._1
        dutIo.free(i).physReg #= frees(i)._2
      } else {
        dutIo.free(i).enable #= false
        dutIo.free(i).physReg #= 0
      }
    }
  }

  // --- Helper to drive recover signal ---
  def driveRecover(dutIo: SimpleFreeListIO, enable: Boolean): Unit = {
    dutIo.recover #= enable
  }

  // --- Common setup for each test case ---
  def initSim(dut: SimpleFreeList): Unit = {
    driveAllocatePorts(dut.io, Seq.fill(dut.io.config.numAllocatePorts)(false))
    driveFreePorts(dut.io, Seq.fill(dut.io.config.numFreePorts)((false, 0)))
    driveRecover(dut.io, false)
    dut.clockDomain.waitSampling()
  }

  // --- Helper to check allocation results ---
  def checkAllocResults(
      allocIo: Vec[SimpleFreeListAllocatePort],
      expectedSuccess: Seq[Boolean],
      expectedPhysRegs: Seq[Option[Int]]
  ): Unit = {
    sleep(0) // Allow combinational logic to settle
    for (i <- 0 until allocIo.length) {
      val port = allocIo(i)
      assert(
        port.success.toBoolean == expectedSuccess(i),
        s"Alloc port $i: success mismatch. Expected ${expectedSuccess(i)}, got ${port.success.toBoolean}"
      )

      if (expectedSuccess(i) && expectedPhysRegs(i).isDefined) {
        assert(
          port.physReg.toInt == expectedPhysRegs(i).get,
          s"Alloc port $i: physReg mismatch. Expected p${expectedPhysRegs(i).get}, got p${port.physReg.toInt}"
        )
      }
    }
  }

  // --- Helper to count successful allocations ---
  def countSuccessfulAllocations(allocIo: Vec[SimpleFreeListAllocatePort]): Int = {
    sleep(0)
    allocIo.count(_.success.toBoolean)
  }

  // =================================================================
  // ==                TIER 1: CONCURRENCY TESTS                    ==
  // =================================================================
  
  test("Tier 1 - Concurrent alloc and free (borrowing)") {
    // **修正配置**: 确保可分配深度是2的幂次 (17 - 1 = 16)
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 17,
      numInitialArchMappings = 1,
      numAllocatePorts = 2,
      numFreePorts = 1
    )
    val initialFreeRegs = testConfig.requiredDepth // 16

    simConfig.compile(new SimpleFreeList(testConfig)).doSim(seed = 202) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)

      // --- SETUP: 消耗寄存器直到只剩一个 ---
      println("[TB] SETUP: Consuming registers to leave only one.")
      // 我们将分配 p1 到 p15，留下最后一个（p16）在队列中
      val regsToConsume = initialFreeRegs - 1 // 15
      for (i <- 0 until regsToConsume) {
        driveAllocatePorts(dut.io, Seq(true, false))
        dut.clockDomain.waitRisingEdge()
      }

      // 插入一个空闲周期来稳定状态
      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitRisingEdge()
      
      // --- PRE-CHECK: 确认设置正确 ---
      assert(dut.io.numFreeRegs.toInt == 1, s"SETUP FAILED: Expected 1 free register, found ${dut.io.numFreeRegs.toInt}.")
      println(s"[TB] PRE-CHECK PASSED: 1 register (p16) is available.")

      // --- ACTION: 请求2个分配，同时释放1个 (p1) ---
      // 期望: 一个分配获得队列中最后的p16，另一个分配获得被直通的p1
      println("[TB] ACTION: Requesting 2 Allocs while concurrently Freeing p1.")
      driveAllocatePorts(dut.io, Seq(true, true))
      driveFreePorts(dut.io, Seq((true, 1)))
      dut.clockDomain.waitRisingEdge()

      // --- POST-CHECK: 验证并发操作的结果 ---
      // **修正验证**: 在请求的下一个周期进行验证
      val successfulAllocs = countSuccessfulAllocations(dut.io.allocate)
      assert(successfulAllocs == 2, s"POST-CHECK FAILED: Expected 2 successful allocations, but got $successfulAllocs.")

      val finalFreeRegs = dut.io.numFreeRegs.toInt
      assert(finalFreeRegs == 0, s"POST-CHECK FAILED: NumFreeRegs should be 0, but is $finalFreeRegs.")
      
      val allocatedPregs = dut.io.allocate.filter(_.success.toBoolean).map(_.physReg.toInt).toSet
      assert(allocatedPregs.contains(1), "POST-CHECK FAILED: The freed register p1 was not re-allocated.")
      assert(allocatedPregs.contains(16), "POST-CHECK FAILED: The last queue register p16 was not allocated.")
      
      println("[TB] TEST PASSED: Both allocations succeeded by 'borrowing' the concurrently freed register.")
    }
  }
  
  test("Tier 1 - Boundary: Allocate from empty queue, fed by concurrent frees") {
    // **修正配置**: 确保可分配深度是2的幂次 (33 - 1 = 32)
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 33,
      numInitialArchMappings = 1,
      numAllocatePorts = 3,
      numFreePorts = 2
    )
    val allocatableRegs = testConfig.requiredDepth

    simConfig.compile(new SimpleFreeList(testConfig)).doSim(seed = 102) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)

      // Step 1: 将队列完全清空
      println(s"[TB] Step 1: Consuming all $allocatableRegs registers.")
      for(_ <- 0 until allocatableRegs) {
        driveAllocatePorts(dut.io, Seq(true, false, false))
        dut.clockDomain.waitRisingEdge()
      }
      
      driveAllocatePorts(dut.io, Seq(false, false, false))
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.numFreeRegs.toInt == 0, s"Queue should be empty, but has ${dut.io.numFreeRegs.toInt} regs.")
      println("[TB] Queue is now empty.")

      // Step 2: 关键测试点 - 从空队列请求3个分配，同时释放2个寄存器 (p1, p2)
      println("[TB] Step 2: Requesting 3 Allocs from empty queue, while concurrently freeing 2.")
      driveAllocatePorts(dut.io, Seq(true, true, true))
      driveFreePorts(dut.io, Seq((true, 1), (true, 2)))
      dut.clockDomain.waitRisingEdge()

      // Step 3: 验证结果
      // **修正验证**: 在请求的下一个周期进行验证
      val successfulAllocs = countSuccessfulAllocations(dut.io.allocate)
      assert(successfulAllocs == 2, s"Expected 2 successful allocations, but got $successfulAllocs")

      assert(dut.io.numFreeRegs.toInt == 0, s"NumFreeRegs should be 0, but is ${dut.io.numFreeRegs.toInt}.")
      
      val allocatedPregs = dut.io.allocate.filter(_.success.toBoolean).map(_.physReg.toInt).toSet
      assert(allocatedPregs == Set(1, 2), s"Expected allocated pregs to be {1, 2}, but got $allocatedPregs")

      println("[TB] Test successful. Correct number of allocations granted from concurrent frees.")
    }
  }

  // =================================================================
  // ==          TIER 2: RECOVERY SIGNAL INTERACTION TESTS          ==
  // =================================================================

  test("Tier 2 - Recovery signal overrides concurrent alloc/free requests") {
    // **修正配置**: 确保可分配深度是2的幂次 (9 - 1 = 8)
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 9,
      numInitialArchMappings = 1,
      numAllocatePorts = 2,
      numFreePorts = 2
    )
    val initialFreeRegs = testConfig.requiredDepth // 8

    simConfig.compile(new SimpleFreeList(testConfig)).doSim(seed = 103) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)

      // Step 1: 让 DUT 进入一个任意的“脏”状态
      println("[TB] Step 1: Making the DUT state 'dirty'.")
      driveAllocatePorts(dut.io, Seq(true, true)) // Alloc p1, p2
      dut.clockDomain.waitRisingEdge()
      driveFreePorts(dut.io, Seq((true, 1)))     // Free p1
      dut.clockDomain.waitRisingEdge()

      driveAllocatePorts(dut.io, Seq(false, false))
      driveFreePorts(dut.io, Seq())
      assert(dut.io.numFreeRegs.toInt != initialFreeRegs, "DUT state should not be initial.")
      println(s"[TB] DUT is in a dirty state with ${dut.io.numFreeRegs.toInt} free regs.")

      // Step 2: 关键测试点 - 同时发起分配、释放和恢复请求
      println("[TB] Step 2: Asserting recover alongside alloc and free requests.")
      driveAllocatePorts(dut.io, Seq(true, true)) // Request to allocate
      driveFreePorts(dut.io, Seq((true, 2)))   // Request to free
      driveRecover(dut.io, true)                 // <== RECOVER ASSERTED
      dut.clockDomain.waitRisingEdge()

      // Step 3: 验证恢复的后果
      println("[TB] Step 3: Verifying the aftermath of recovery.")
      driveRecover(dut.io, false) // 取消断言

      // 验证 1: 上一个周期的所有分配请求必须失败
      checkAllocResults(dut.io.allocate, Seq(false, false), Seq(None, None))
      println("[TB] Verified: All allocations failed during recovery cycle.")

      // 验证 2: 空闲寄存器数量已恢复到初始值
      assert(dut.io.numFreeRegs.toInt == initialFreeRegs, s"NumFreeRegs should have reset to $initialFreeRegs, but is ${dut.io.numFreeRegs.toInt}.")
      println(s"[TB] Verified: NumFreeRegs has been reset to $initialFreeRegs.")

      // 验证 3: 下一次分配应该从第一个寄存器开始，确认指针已重置
      driveAllocatePorts(dut.io, Seq(true, false))
      dut.clockDomain.waitRisingEdge()
      checkAllocResults(dut.io.allocate, Seq(true, false), Seq(Some(testConfig.numInitialArchMappings), None))
      println(s"[TB] Verified: Next allocation is p${testConfig.numInitialArchMappings}, confirming pointers reset.")
      println("[TB] Test successful. Recovery correctly overrides all other operations.")
    }
  }

  // =================================================================
  // ==          TIER 3: RANDOMIZED STRESS & SCOREBOARDING          ==
  // =================================================================

  test("Tier 3 - Randomized Stress Test with Scoreboard") {
    // **配置**: 使用一个较大的2的幂次深度 (32)
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 32,
      numInitialArchMappings = 0,
      numAllocatePorts = 3,
      numFreePorts = 3
    )
    val numCycles = 5000

    simConfig.withWave.compile({
      val dut = new SimpleFreeList(testConfig).setDefinitionName("SimpleFreeList_Tier3")
      dut.freeGrantsReg.simPublic()
      dut
    }).doSim(seed = 42) { dut =>
      // 使用一个简单的软件模型作为参照
      var modelFreeCount = testConfig.requiredDepth
      val allocatedRegs = mutable.Set[Int]()
      val freePool = mutable.Queue[Int]() ++ (0 until testConfig.requiredDepth)
      
      dut.clockDomain.forkStimulus(10)
      initSim(dut)
      println(s"[TB] Starting Randomized Stress Test for $numCycles cycles...")

      for (cycle <- 0 until numCycles) {
        // 在周期的开始，根据上一周期的结果更新模型
        val lastCycleSuccessfulAllocs = dut.io.allocate.filter(_.success.toBoolean).map(_.physReg.toInt)
        val lastCycleSuccessfulFrees = dut.freeGrantsReg.zip(dut.io.free).filter(_._1.toBoolean).map(_._2.physReg.toInt)
        
        modelFreeCount = modelFreeCount - lastCycleSuccessfulAllocs.length + lastCycleSuccessfulFrees.length
        
        // 将 scoreboard 的断言放在周期的开始，验证上一个周期的状态
        assert(dut.io.numFreeRegs.toInt == modelFreeCount, s"Cycle $cycle: NumFreeRegs Mismatch! DUT: ${dut.io.numFreeRegs.toInt}, Model: $modelFreeCount")

        // 准备并驱动下一个周期的激励
        val allocRequests = scala.util.Random.nextInt(testConfig.numAllocatePorts + 1)
        val freeRequests = scala.util.Random.nextInt(testConfig.numFreePorts + 1)
        
        driveAllocatePorts(dut.io, Seq.tabulate(testConfig.numAllocatePorts)(_ < allocRequests))
        // 简单起见，我们只跟踪数量，不跟踪具体ID
        driveFreePorts(dut.io, Seq.fill(freeRequests)((true, 0)))

        dut.clockDomain.waitRisingEdge()
      }
      println("[TB] Randomized Stress Test PASSED.")
    }
  }

  // =================================================================
  // ==             TIER 4: POINTER WRAP-AROUND TESTS (REVISED)     ==
  // =================================================================

  test("Tier 4 - Pointer Wrap-Around Test (Power-of-2)") {
    // **修正配置**: 确保可分配深度是2的幂次 (17 - 1 = 16)
    val testConfig =
      SimpleFreeListConfig(numPhysRegs = 17, numInitialArchMappings = 1, numAllocatePorts = 1, numFreePorts = 1)
    val logicDepth = testConfig.requiredDepth // 16

    simConfig.compile(new SimpleFreeList(testConfig).setDefinitionName("SimpleFreeList_Tier4")).doSim(seed = 112) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)
      println("[TB] Testing Alloc Pointer Wrap-Around with Power-of-2 depth...")

      // --- 1. 分配所有寄存器，直到队列为空 ---
      println(s"[TB] Step 1: Allocating all $logicDepth registers...")
      for (i <- 0 until logicDepth) {
        driveAllocatePorts(dut.io, Seq(true))
        dut.clockDomain.waitRisingEdge()
        // **修正验证**: 验证上一个周期的请求
        checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(i + testConfig.numInitialArchMappings)))
      }
      
      // 插入一个空闲周期来稳定状态并验证
      driveAllocatePorts(dut.io, Seq(false))
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.numFreeRegs.toInt == 0, s"Queue should be empty, but has ${dut.io.numFreeRegs.toInt} regs.")
      println("[TB] Queue is now empty.")

      // --- 2. 释放两个寄存器回队列 (p1, p2) ---
      println("[TB] Step 2: Freeing p1 and p2 back into the queue.")
      driveFreePorts(dut.io, Seq((true, 1)))
      dut.clockDomain.waitRisingEdge()
      driveFreePorts(dut.io, Seq((true, 2)))
      dut.clockDomain.waitRisingEdge()

      // 插入空闲周期并验证
      driveFreePorts(dut.io, Seq())
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.numFreeRegs.toInt == 2, s"Expected 2 free regs, but found ${dut.io.numFreeRegs.toInt}.")
      println("[TB] Queue now contains 2 freed registers.")

      // --- 3. 重新分配这两个寄存器，验证环绕 ---
      println("[TB] Step 3: Re-allocating the two registers to test pointer wrapping.")
      
      driveAllocatePorts(dut.io, Seq(true)) // 请求分配 p1
      dut.clockDomain.waitRisingEdge()
      checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(1)))

      driveAllocatePorts(dut.io, Seq(true)) // 请求分配 p2
      dut.clockDomain.waitRisingEdge()
      checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(2)))

      // --- 4. 再次尝试分配，预期失败 ---
      println("[TB] Step 4: Attempting to allocate from now-empty queue, expecting failure.")
      driveAllocatePorts(dut.io, Seq(true))
      dut.clockDomain.waitRisingEdge()
      checkAllocResults(dut.io.allocate, Seq(false), Seq(None))
      
      // 最终验证
      driveAllocatePorts(dut.io, Seq(false))
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.numFreeRegs.toInt == 0, s"Final check failed: queue should be empty, but has ${dut.io.numFreeRegs.toInt}.")

      println("[TB] Pointer Wrap-Around Test PASSED.")
    }
  }

  thatsAll
}
