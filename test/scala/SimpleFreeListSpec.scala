package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.components.rename._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim.SimConfig
import scala.collection.mutable
import parallax.utilities.ParallaxLogger

class SimpleFreeListSpec extends CustomSpinalSimFunSuite {

  // --- Helper to drive allocate ports ---
  def driveAllocatePorts(
      dutIo: SimpleFreeListIO,
      enables: Seq[Boolean]
  ): Unit = {
    require(enables.length == dutIo.config.numAllocatePorts)
    for (i <- 0 until dutIo.config.numAllocatePorts) {
      dutIo.allocate(i).enable #= enables(i)
      println(s"drive allocate port $i: enable=${enables(i)}")
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
    sleep(1)
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
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 17, // requiredDepth = 16 (power of 2)
      numInitialArchMappings = 1,
      numAllocatePorts = 2,
      numFreePorts = 1
    )
    val initialFreeRegs = testConfig.requiredDepth

    simConfig.compile(new SimpleFreeList(testConfig)).doSim(seed = 202) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)

      // --- SETUP: 消耗寄存器直到只剩一个 ---
      println("[TB] SETUP: Consuming registers to leave only one.")
      val regsToConsume = initialFreeRegs - 1 // 15
      for (i <- 0 until regsToConsume) {
        driveAllocatePorts(dut.io, Seq(true, false))
        dut.clockDomain.waitSampling()
      }

      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitSampling()
      
      // *** FIX: Wait one more cycle for the last allocation result to be valid ***
      assert(dut.io.numFreeRegs.toInt == 1, s"SETUP FAILED: Expected 1 free register, found ${dut.io.numFreeRegs.toInt}.")
      println(s"[TB] PRE-CHECK PASSED: 1 register (p16) is available.")

      // --- ACTION: 请求2个分配，同时释放1个 (p1) ---
      println("[TB] ACTION: Requesting 2 Allocs while concurrently Freeing p1.")
      driveAllocatePorts(dut.io, Seq(true, true))
      driveFreePorts(dut.io, Seq((true, 1)))
      dut.clockDomain.waitSampling()

      // --- POST-CHECK: 验证并发操作的结果 (在请求的下一个周期) ---
      driveAllocatePorts(dut.io, Seq(false, false)) // Stop driving
      driveFreePorts(dut.io, Seq())

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
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 33, // requiredDepth = 32
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
        dut.clockDomain.waitSampling()
      }
      
      driveAllocatePorts(dut.io, Seq(false, false, false))
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0, s"Queue should be empty, but has ${dut.io.numFreeRegs.toInt} regs.")
      println("[TB] Queue is now empty.")

      // Step 2: 关键测试点 - 从空队列请求3个分配，同时释放2个寄存器 (p1, p2)
      println("[TB] Step 2: Requesting 3 Allocs from empty queue, while concurrently freeing 2.")
      driveAllocatePorts(dut.io, Seq(true, true, true))
      driveFreePorts(dut.io, Seq((true, 1), (true, 2)))
      dut.clockDomain.waitSampling()

      // Step 3: 验证结果 (在请求的下一个周期)
      driveAllocatePorts(dut.io, Seq(false, false, false))
      driveFreePorts(dut.io, Seq())

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
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 9, // requiredDepth = 8
      numInitialArchMappings = 1,
      numAllocatePorts = 2,
      numFreePorts = 2
    )
    val initialFreeRegs = testConfig.requiredDepth

    simConfig.compile(new SimpleFreeList(testConfig)).doSim(seed = 103) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)

      // Step 1: 让 DUT 进入一个任意的“脏”状态
      println("[TB] Step 1: Making the DUT state 'dirty'.")
      driveAllocatePorts(dut.io, Seq(true, true)) // Alloc p1, p2
      dut.clockDomain.waitSampling()
      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitSampling()
      
      driveFreePorts(dut.io, Seq((true, 1)))     // Free p1
      dut.clockDomain.waitSampling()
      driveFreePorts(dut.io, Seq())

      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt != initialFreeRegs, "DUT state should not be initial.")
      val dirtyFreeCount = dut.io.numFreeRegs.toInt
      println(s"[TB] DUT is in a dirty state with ${dirtyFreeCount} free regs.")

      // Step 2: 关键测试点 - 在一个完整的周期内保持 recover 为高
      println("[TB] Step 2: Asserting recover for one full cycle.")
      driveAllocatePorts(dut.io, Seq(true, true)) // 这些输入会被 recover 忽略
      driveFreePorts(dut.io, Seq((true, 2)))
      driveRecover(dut.io, true)
      dut.clockDomain.waitSampling() // recover=true 在这个周期生效

      // Step 3: 撤销所有输入并进入下一个周期，然后验证
      println("[TB] Step 3: Verifying the aftermath of recovery.")
      driveAllocatePorts(dut.io, Seq(false, false))
      driveFreePorts(dut.io, Seq())
      driveRecover(dut.io, false)
      sleep(0) 
      
      // 在 recover 撤销的这个周期，组合逻辑应该输出正确的值
      assert(dut.io.numFreeRegs.toInt == initialFreeRegs, s"NumFreeRegs should have reset to $initialFreeRegs, but is ${dut.io.numFreeRegs.toInt}.")
      println(s"[TB] Verified: NumFreeRegs has been reset to $initialFreeRegs.")

      // 验证上一个周期的分配请求失败了
      checkAllocResults(dut.io.allocate, Seq(false, false), Seq(None, None))
      println("[TB] Verified: All allocations failed during recovery cycle.")
      
      // 进入下一个周期，让状态稳定
      dut.clockDomain.waitSampling()

      // 验证现在可以成功分配
      println("[TB] Verified: Next allocation should succeed from the restored pointer state.")
      driveAllocatePorts(dut.io, Seq(true, false))
      dut.clockDomain.waitSampling()
      driveAllocatePorts(dut.io, Seq(false, false))
      
      checkAllocResults(dut.io.allocate, Seq(true, false), Seq(None, None))
      assert(dut.io.numFreeRegs.toInt == initialFreeRegs - 1)
      
      println("[TB] Test successful. Recovery correctly overrides all other operations and restores state.")
    }
  }
// 在 SimpleFreeListSpec.scala 中

  // ... (其他测试用例保持不变) ...

  // =================================================================
  // ==          TIER 3: RANDOMIZED STRESS & SCOREBOARDING          ==
  // =================================================================

  // =================================================================
  // ==          TIER 3: RANDOMIZED STRESS & SCOREBOARDING (REFACTORED) ==
  // =================================================================


  test("xTier 3 - Randomized Stress Test with Scoreboard") {
    val testConfig = SimpleFreeListConfig(
      numPhysRegs = 32,
      numInitialArchMappings = 0,
      numAllocatePorts = 3,
      numFreePorts = 3
    )
    val numCycles = 10000
    val logicDepth = testConfig.requiredDepth

    simConfig.compile({
      val dut = new SimpleFreeList(testConfig).setDefinitionName("SimpleFreeList_Tier3_Final")
      dut
    }).doSim(seed = 42) { dut =>
      println(s"[TB] Starting Randomized Stress Test with State Transition Scoreboard for $numCycles cycles...")
      dut.clockDomain.forkStimulus(10)
      initSim(dut)
      sleep(1) // Initial sleep

      // --- Scoreboard (Golden Model) as a State Transition System ---

      // --- Scoreboard (Golden Model) with Garbage Collection Recovery ---
      class Scoreboard(depth: Int, initialMapping: Int, totalRegs: Int) {
        var freeQueue = mutable.Queue[Int]()
        for (i <- 0 until depth) { freeQueue.enqueue(i + initialMapping) }
        
        // 状态迁移函数: s_next = f(s_current, inputs)
        def nextState(
            allocEnables: Seq[Boolean], 
            freesToDrive: Seq[(Boolean, Int)], 
            doRecover: Boolean
        ): Scoreboard = {
          
          val nextSb = new Scoreboard(depth, initialMapping, totalRegs)
          nextSb.freeQueue = this.freeQueue.clone()

          if (doRecover) {
            // --- 模拟垃圾回收 ---
            // 1. 找出所有已分配的寄存器 (inFlight)
            val inFlightRegs = (0 until totalRegs).toSet -- nextSb.freeQueue.toSet
            // 2. 将它们全部加回到队列中，模拟“瞬时”回收
            inFlightRegs.toSeq.sorted.foreach(reg => nextSb.freeQueue.enqueue(reg))

          } else {
            // --- 正常操作 ---
            val freesThisCycle = freesToDrive.filter(_._1).map(_._2)
            val allocRequests = allocEnables.count(identity)
            val availableThisCycle = nextSb.freeQueue.size + freesThisCycle.length
            val successfulAllocs = Math.min(allocRequests, availableThisCycle)

            freesThisCycle.foreach(reg => nextSb.freeQueue.enqueue(reg))
            for(_ <- 0 until successfulAllocs) { nextSb.freeQueue.dequeue() }
          }
          nextSb
        }

        def size: Int = freeQueue.size
        def inFlightRegs(totalRegs: Int): Set[Int] = {
          (0 until totalRegs).toSet -- freeQueue.toSet
        }
      }

      var scoreboard = new Scoreboard(logicDepth, testConfig.numInitialArchMappings, testConfig.numPhysRegs)

      // --- Randomized Simulation Loop ---
      for (cycle <- 0 until numCycles) {

        // --- 1. 生成连续的随机激励 ---
        val inFlight = scoreboard.inFlightRegs(testConfig.numPhysRegs)
        
        // 生成连续的分配请求
        val numAllocs = scala.util.Random.nextInt(testConfig.numAllocatePorts + 1)
        val allocEnables = (0 until testConfig.numAllocatePorts).map(i => i < numAllocs)

        // 生成连续的释放请求
        val numFrees = if (inFlight.nonEmpty) scala.util.Random.nextInt(testConfig.numFreePorts + 1) else 0
        val regsToFree = scala.util.Random.shuffle(inFlight.toList).take(numFrees)
        val freesToDrive = (0 until testConfig.numFreePorts).map { i =>
          if (i < regsToFree.length) (true, regsToFree(i)) else (false, 0)
        }
        
        val action = scala.util.Random.nextInt(100)
        val doCreateCheckpoint = action < 5
        val doRecover = action >= 5 && action < 10
        
        // --- 2. 驱动 DUT ---
        driveAllocatePorts(dut.io, allocEnables)
        driveFreePorts(dut.io, freesToDrive)
        driveRecover(dut.io, doRecover)

        val nextScoreboard = scoreboard.nextState(allocEnables, freesToDrive, doRecover)
        
        dut.clockDomain.waitRisingEdge()
        sleep(1)
        
        scoreboard = nextScoreboard

        assert(
          dut.io.numFreeRegs.toInt == scoreboard.size,
          s"[Cycle $cycle] numFreeRegs mismatch! DUT: ${dut.io.numFreeRegs.toInt}, Scoreboard: ${scoreboard.size}. Action: recover=${doRecover}"
        )

      }
      println(s"[TB] Randomized Stress Test with State Transition Scoreboard PASSED for $numCycles cycles.")
    }
  }


  // =================================================================
  // ==             TIER 4: POINTER WRAP-AROUND TESTS             ==
  // =================================================================

  test("Tier 4 - Pointer Wrap-Around Test") {
    val testConfig =
      SimpleFreeListConfig(numPhysRegs = 17, numInitialArchMappings = 1, numAllocatePorts = 1, numFreePorts = 1)
    val logicDepth = testConfig.requiredDepth

    simConfig.compile(new SimpleFreeList(testConfig).setDefinitionName("SimpleFreeList_Tier4")).doSim(seed = 112) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut)
      println("[TB] Testing Pointer Wrap-Around...")

      // --- 1. 分配所有寄存器，直到队列为空 ---
      println(s"[TB] Step 1: Allocating all $logicDepth registers...")
      for (i <- 0 until logicDepth) {
        driveAllocatePorts(dut.io, Seq(true))
        dut.clockDomain.waitSampling()
        // *** FIX: Verification moved to next cycle ***
        checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(i + testConfig.numInitialArchMappings)))
      }
      
      driveAllocatePorts(dut.io, Seq(false))
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0, s"Queue should be empty, but has ${dut.io.numFreeRegs.toInt} regs.")
      println("[TB] Queue is now empty.")

      // --- 2. 释放两个寄存器回队列 (p1, p2) ---
      println("[TB] Step 2: Freeing p1 and p2 back into the queue.")
      driveFreePorts(dut.io, Seq((true, 1)))
      dut.clockDomain.waitSampling()
      driveFreePorts(dut.io, Seq((true, 2)))
      dut.clockDomain.waitSampling()

      driveFreePorts(dut.io, Seq())
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 2, s"Expected 2 free regs, but found ${dut.io.numFreeRegs.toInt}.")
      println("[TB] Queue now contains 2 freed registers.")

      // --- 3. 重新分配这两个寄存器，验证环绕 ---
      println("[TB] Step 3: Re-allocating the two registers to test pointer wrapping.")
      
      driveAllocatePorts(dut.io, Seq(true))
      dut.clockDomain.waitSampling()
      checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(1)))

      driveAllocatePorts(dut.io, Seq(true))
      dut.clockDomain.waitSampling()
      checkAllocResults(dut.io.allocate, Seq(true), Seq(Some(2)))

      // --- 4. 再次尝试分配，预期失败 ---
      println("[TB] Step 4: Attempting to allocate from now-empty queue, expecting failure.")
      driveAllocatePorts(dut.io, Seq(true))
      dut.clockDomain.waitSampling()
      checkAllocResults(dut.io.allocate, Seq(false), Seq(None))
      
      driveAllocatePorts(dut.io, Seq(false))
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0, s"Final check failed: queue should be empty, but has ${dut.io.numFreeRegs.toInt}.")

      println("[TB] Pointer Wrap-Around Test PASSED.")
    }
  }

  thatsAll
}
