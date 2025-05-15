package boson.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.tester.SpinalSimFunSuite
import boson.demo2.components.rename._

class FreeListTestBench(val flConfig: FreeListConfig) extends Component {
  val io = slave(FreeListIO(flConfig))

  val freeList = new FreeList(flConfig)
  freeList.io <> io
  freeList.io.simPublic()
  freeList.freeRegsMask.simPublic()
}

class FreeListSpec extends CustomSpinalSimFunSuite {

  val numTotalPhysRegs = 8
  val numAllocatableRegs = numTotalPhysRegs - 1 // p0 is special

  // Config for the FreeList DUT
  val dutConfig = FreeListConfig(
    numPhysRegs = numTotalPhysRegs,
    resetToFull = true
  )

  // Helper to drive allocate port on the TestBench's IO
  def driveAllocate(dutIo: FreeListIO, enable: Boolean): Unit = {
    dutIo.allocate.enable #= enable
    sleep(0)
  }

  // Helper to drive free port
  def driveFree(dutIo: FreeListIO, enable: Boolean, physRegToFree: Int): Unit = {
    println(f"[Sim] Freeing p$physRegToFree")
    dutIo.free.enable #= enable
    dutIo.free.physReg #= physRegToFree
    sleep(0)
  }

  // Helper to drive checkpoint restore
  def driveRestore(dutIo: FreeListIO, enable: Boolean, mask: BigInt): Unit = {
    dutIo.restoreState.valid #= enable
    if (enable) {
      dutIo.restoreState.payload.freeMask #= mask
    }
    sleep(0)
  }

  // Helper to get the internal free mask via simPublic on the FreeList instance *inside* TestBench
  def getInternalMaskFromTestBench(tb: FreeListTestBench): BigInt = {
    tb.freeList.freeRegsMask.toBigInt
  }

  // Common setup for each test case in doSim
  def initSim(dutIo: FreeListIO): Unit = {
    driveAllocate(dutIo, false)
    driveFree(dutIo, false, 0)
    driveRestore(dutIo, false, 0)
  }

  test("FreeList - Initialization") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 100) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)

      dut.clockDomain.waitSampling() // Let reset and init take effect

      assert(dut.io.allocate.success.toBoolean, "Should be success after init")
      assert(
        dut.io.numFreeRegs.toInt == numAllocatableRegs,
        s"Expected ${numAllocatableRegs} free regs, got ${dut.io.numFreeRegs.toInt}"
      )

      val expectedMask = (BigInt(1) << numTotalPhysRegs) - 1 // All 1s
      val p0MaskBit = BigInt(1) << 0
      val finalExpectedMask = expectedMask ^ p0MaskBit

      assert(
        getInternalMaskFromTestBench(dut) == finalExpectedMask,
        s"Initial mask incorrect. Expected ${finalExpectedMask.toString(2)}, Got ${getInternalMaskFromTestBench(dut).toString(2)}"
      )
    }
  }

  test("FreeList - Basic Allocation") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 101) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling() // Initial state settle

      var allocatedRegs = Set[Int]()
      var expectedCurrentAllocIdx = 1 // The register we expect to be allocated *in this iteration*

      for (i <- 0 until numAllocatableRegs) {
        // Phase 1: Drive allocate enable
        dut.io.allocate.enable #= true
        // physReg output is combinational based on current freeRegsMask
        // So, before the clock edge, physReg should point to the current first free
        assert(dut.io.allocate.success.toBoolean, s"Cycle $i Pre-CLK: Available should be true")
        val phyToAllocateThisCycle = dut.io.allocate.physReg.toInt
        assert(
          phyToAllocateThisCycle == expectedCurrentAllocIdx,
          s"Cycle $i Pre-CLK: Expected to see p$expectedCurrentAllocIdx as next, got p$phyToAllocateThisCycle"
        )
        val numFree = dut.io.numFreeRegs.toInt
        dut.clockDomain.waitSampling() // CLOCK EDGE: freeRegsMask is updated (phyToAllocateThisCycle is marked as used)
        dut.clockDomain.waitSampling(0)
        sleep(1) // 增加这一行测试就pass了，不知道为什么
        assert(
          dut.io.numFreeRegs.toInt == numFree - 1,
          s"Cycle $i Pre-CLK: NumFreeRegs mismatch. Expected ${numFree - 1}, Got ${dut.io.numFreeRegs.toInt}"
        )
        // Phase 2: Check results after clock edge
        assert(
          !allocatedRegs.contains(phyToAllocateThisCycle),
          s"Cycle $i Post-CLK: Allocated p$phyToAllocateThisCycle which was already in our set!?"
        )
        allocatedRegs += phyToAllocateThisCycle

        // Check numFreeRegs after the allocation
        assert(
          dut.io.numFreeRegs.toInt == numAllocatableRegs - (i + 1),
          s"Cycle $i Post-CLK: NumFreeRegs mismatch. Expected ${numAllocatableRegs - (i + 1)}, Got ${dut.io.numFreeRegs.toInt}"
        )

        expectedCurrentAllocIdx += 1 // For the next iteration's pre-check

        // De-assert enable for the next cycle if we are not at the end of loop where we test emptiness
        if (i < numAllocatableRegs - 1) {
          dut.io.allocate.enable #= false
        }
      }

      // After loop, enable is still true (from last iteration)
      // List should be empty now (for allocatable regs)
      assert(!dut.io.allocate.success.toBoolean, "Should not be success when list is empty (after loop)")
      assert(dut.io.numFreeRegs.toInt == 0, "NumFreeRegs should be 0 when empty (after loop)")

      // Explicitly de-assert
      dut.io.allocate.enable #= false
      dut.clockDomain.waitSampling()
    }
  }

  test("FreeList - Basic Free") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 102) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      for (i <- 0 until numAllocatableRegs) {
        driveAllocate(dut.io, true)
        dut.clockDomain.waitSampling()
      }
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0)

      val regsToFree = (1 to numAllocatableRegs).toList
      for ((regToFree, i) <- regsToFree.zipWithIndex) {
        driveFree(dut.io, true, regToFree)
        dut.clockDomain.waitSampling()
        sleep(0)

        assert(dut.io.numFreeRegs.toInt == i + 1, s"After freeing p$regToFree, NumFreeRegs mismatch")
        driveFree(dut.io, false, 0)
      }
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs)
      assert(dut.io.allocate.success.toBoolean)

      val numFreeBeforeP0Free = dut.io.numFreeRegs.toInt
      driveFree(dut.io, true, 0) // Try to free p0
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numFreeBeforeP0Free, "Freeing p0 should not change count")
      driveFree(dut.io, false, 0)
    }
  }

  test("FreeList - Checkpoint Save and Restore") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 103) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      driveAllocate(dut.io, true); dut.clockDomain.waitSampling() // p1 allocated
      val p1 = dut.io.allocate.physReg.toInt
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling() // p2 allocated
      val p2 = dut.io.allocate.physReg.toInt
      driveAllocate(dut.io, false)
      val maskAtCheckpoint = getInternalMaskFromTestBench(dut)
      dut.clockDomain.waitSampling()
      val numFreeAtCheckpoint = dut.io.numFreeRegs.toInt
      println(
        s"[TestBench] maskAtCheckpoint: ${getInternalMaskFromTestBench(dut).toString(2)}, freeRegs: $numFreeAtCheckpoint"
      )

      driveAllocate(dut.io, true); dut.clockDomain.waitSampling() // p3 allocated
      val p3 = dut.io.allocate.physReg.toInt
      driveAllocate(dut.io, false)
      assert(getInternalMaskFromTestBench(dut) != maskAtCheckpoint)

      println(s"[TestBench] maskAtCheckpoint before driveRestore: ${getInternalMaskFromTestBench(dut).toString(2)}")
      driveRestore(dut.io, true, maskAtCheckpoint)
      dut.clockDomain.waitSampling()
      driveRestore(dut.io, false, 0)

      println(s"[TestBench] maskAtCheckpoint after driveRestore: ${getInternalMaskFromTestBench(dut).toString(2)}")
      assert(getInternalMaskFromTestBench(dut) == maskAtCheckpoint, "Mask did not restore correctly")
      assert(dut.io.numFreeRegs.toInt == numFreeAtCheckpoint, "NumFreeRegs incorrect after restore")

      driveAllocate(dut.io, true); dut.clockDomain.waitSampling()
      val p3_again = dut.io.allocate.physReg.toInt
      assert(p3_again == p3, "Did not allocate the same next register (p3) after restore") // p3 was next free
      driveAllocate(dut.io, false)
    }
  }

  // --- New Test Cases ---

  test("FreeList - Allocate and Free Interleaved") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 104) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      var allocatedPRegs = List[Int]()

      // Allocate p1, p2
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling();
      allocatedPRegs = dut.io.allocate.physReg.toInt :: allocatedPRegs
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling();
      allocatedPRegs = dut.io.allocate.physReg.toInt :: allocatedPRegs
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 2)

      // Free p1 (assuming p1 was the first one allocated, which is allocatedPRegs.last with LIFO)
      val toFree1 = allocatedPRegs.last; allocatedPRegs = allocatedPRegs.init
      driveFree(dut.io, true, toFree1)
      dut.clockDomain.waitSampling()
      driveFree(dut.io, false, 0)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 1)

      // Allocate p3 (should get the just freed p1 if lowest index first)
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling()
      val p3_alloc = dut.io.allocate.physReg.toInt
      assert(p3_alloc == toFree1, s"Expected to re-allocate p$toFree1, but got p$p3_alloc")
      allocatedPRegs = p3_alloc :: allocatedPRegs
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 2)

      // Free p2
      val toFree2 = allocatedPRegs.last; allocatedPRegs = allocatedPRegs.init
      driveFree(dut.io, true, toFree2)
      dut.clockDomain.waitSampling()
      driveFree(dut.io, false, 0)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 1)
    }
  }

  test("FreeList - Freeing an already free register") {
    // This test assumes no specific assertion in DUT, just checks for no bad side-effects.
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 105) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      val initialMask = getInternalMaskFromTestBench(dut)
      dut.clockDomain.waitSampling()
      val initialNumFree = dut.io.numFreeRegs.toInt

      // p1 is initially free. Try to free it again.
      driveFree(dut.io, true, 1)
      dut.clockDomain.waitSampling()
      driveFree(dut.io, false, 0)

      assert(getInternalMaskFromTestBench(dut) == initialMask, "Mask changed after freeing an already free reg")
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == initialNumFree, "NumFreeRegs changed after freeing an already free reg")
    }
  }

  test("FreeList - Allocate all then Free all (Reverse order of free)") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 106) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      var allocatedOrder = List[Int]()
      for (i <- 0 until numAllocatableRegs) {
        driveAllocate(dut.io, true); dut.clockDomain.waitSampling()
        allocatedOrder = dut.io.allocate.physReg.toInt :: allocatedOrder
      }
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0)
      allocatedOrder = allocatedOrder.reverse // Allocation order: p1, p2, ...

      // Free in reverse order of allocation (LIFO for allocation list)
      for ((regToFree, i) <- allocatedOrder.reverse.zipWithIndex) {
        driveFree(dut.io, true, regToFree)
        dut.clockDomain.waitSampling()
        sleep(0)
        assert(dut.io.numFreeRegs.toInt == i + 1)
        driveFree(dut.io, false, 0)
      }
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs)
    }
  }

  test("FreeList - Concurrent Allocate and Free (if FreeList logic handles it safely)") {
    // The current FreeList design updates nextFreeRegsMask based on both allocate and free flags.
    // If allocate.enable and free.enable are true in the same cycle:
    // - allocate marks its chosen 'firstFreeIdx' as False.
    // - free marks its 'io.free.physReg' as True.
    // The order of these operations on 'nextFreeRegsMask' matters if firstFreeIdx == io.free.physReg.
    // In SpinalHDL, assignments within the same 'when' or to the same Reg in different 'when' blocks
    // that are active simultaneously might behave like the last one written if not careful.
    // Here, they are to different bits of nextFreeRegsMask unless firstFreeIdx == io.free.physReg.

    // Test case: Allocate one reg, and simultaneously free a *different* reg.
    // The net change in numFreeRegs should be 0.
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 107) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      // Allocate p1
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling(); val p1_alloc = dut.io.allocate.physReg.toInt
      driveAllocate(dut.io, false)
      assert(p1_alloc == 1)
      dut.clockDomain.waitSampling() // Settle; freeRegsMask = 0xFC (p1 allocated)
      val numFreeAfterP1Alloc = dut.io.numFreeRegs.toInt // numAllocatableRegs - 1 (e.g., 6 if 7 allocatable)

      // Now, allocate (should get p2) and simultaneously free p1
      driveAllocate(dut.io, true) // Wants to allocate next free (p2, based on current mask 0xFC)
      driveFree(dut.io, true, p1_alloc) // Wants to free p1

      // --- SAMPLE THE CHOSEN REGISTER FOR ALLOCATION ---
      // Before the clock edge, io.allocate.physReg reflects the choice based on the *current* freeRegsMask (0xFC)
      // and io.allocate.enable = true.
      val phyRegActuallyChosenForConcurrentAlloc = dut.io.allocate.physReg.toInt
      // 根据DUT日志和逻辑，此时 freeRegsMask=0xFC, PriorityEncoderOH(0xFC) -> 2
      // 所以 phyRegActuallyChosenForConcurrentAlloc 应该是 2。

      dut.clockDomain.waitSampling() // Both actions take effect. freeRegsMask becomes 0xFA.

      // De-assert inputs for the next cycle
      driveAllocate(dut.io, false)
      driveFree(dut.io, false, 0)
      // Optional: wait another cycle for de-asserted inputs to propagate if subsequent checks depend on it,
      // or for numFreeRegs to update based on the new mask.
      dut.clockDomain.waitSampling()

      // --- PERFORM ASSERTIONS ---
      assert(
        phyRegActuallyChosenForConcurrentAlloc == 2,
        s"Expected allocation of p2, got p$phyRegActuallyChosenForConcurrentAlloc"
      )

      // Check numFreeRegs: one allocated (p2), one freed (p1), net change 0.
      // Should be same as numFreeAfterP1Alloc.
      // After concurrent op and waitSampling, freeRegsMask is 0xFA. numFreeRegs should be CountOne(0xFA & ~1) = 6.
      assert(dut.io.numFreeRegs.toInt == numFreeAfterP1Alloc, "NumFreeRegs incorrect after concurrent alloc/free")

      // Check mask: p1 should be free, p2 (phyRegActuallyChosenForConcurrentAlloc) should be allocated
      val currentMask = getInternalMaskFromTestBench(dut) // Should be 0xFA
      assert(
        (currentMask & (BigInt(1) << p1_alloc)) != 0,
        s"p$p1_alloc (p1) should be free in mask 0x${currentMask.toString(16)}"
      )
      assert(
        (currentMask & (BigInt(1) << phyRegActuallyChosenForConcurrentAlloc)) == 0,
        s"p$phyRegActuallyChosenForConcurrentAlloc (p2) should be allocated in mask 0x${currentMask.toString(16)}"
      )
    }
  }

  test("FreeList - Restore to empty then allocate") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 108) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)
      dut.clockDomain.waitSampling()

      val emptyMask = 0 // All allocated (p0 also shows as allocated)
      driveRestore(dut.io, true, emptyMask)
      dut.clockDomain.waitSampling()
      driveRestore(dut.io, false, 0)

      assert(getInternalMaskFromTestBench(dut) == emptyMask.toBigInt, "Mask not empty after restore")
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == 0, "NumFreeRegs not 0 after restore to empty")
      assert(!dut.io.allocate.success.toBoolean, "Allocate should not be success")

      // Try to allocate - should fail
      driveAllocate(dut.io, true)
      dut.clockDomain.waitSampling()
      assert(!dut.io.allocate.success.toBoolean, "Allocate still not success")
      driveAllocate(dut.io, false)
    }
  }

  test("FreeList - Restore to full then allocate") {
    simConfig.compile(new FreeListTestBench(dutConfig)).doSim(seed = 109) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSim(dut.io)

      // First, make it not full
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling()
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt != numAllocatableRegs) // Verify it's not full

      // Restore to full state (same as initial state)
      val fullMaskExpected = ((BigInt(1) << numTotalPhysRegs) - 1) ^ (BigInt(1) << 0)
      driveRestore(dut.io, true, fullMaskExpected)
      dut.clockDomain.waitSampling()
      driveRestore(dut.io, false, 0)

      assert(getInternalMaskFromTestBench(dut) == fullMaskExpected, "Mask not full after restore")
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs, "NumFreeRegs not full after restore")
      assert(dut.io.allocate.success.toBoolean, "Allocate should be success")

      // Allocate one
      driveAllocate(dut.io, true); dut.clockDomain.waitSampling()
      assert(dut.io.allocate.physReg.toInt == 1, "Should allocate p1 after restore to full")
      driveAllocate(dut.io, false)
      dut.clockDomain.waitSampling()
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 1)
    }
  }

  thatsAll()
}
