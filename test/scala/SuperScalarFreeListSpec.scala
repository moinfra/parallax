package parallax.test.scala // Or your test package

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.components.rename._ // Import your SuperScalarFreeList components

// TestBench for SuperScalarFreeList
class SuperScalarFreeListTestBench(val flConfig: SuperScalarFreeListConfig) extends Component {
  val io = slave(SuperScalarFreeListIO(flConfig)) // Use SuperScalarFreeListIO

  val freeList = new SuperScalarFreeList(flConfig) // Instantiate SuperScalarFreeList
  freeList.io <> io
  freeList.io.simPublic() // For accessing internal signals like numFreeRegs if needed
  freeList.freeRegsMask.simPublic() // For direct mask checking
}

class SuperScalarFreeListSpec extends CustomSpinalSimFunSuite { // Or your custom base class

  val numTotalPhysRegs = 16
  val numAllocatableRegs = numTotalPhysRegs - 1 // p0 is special

  // --- Helper to drive allocate ports ---
  def driveAllocatePorts(
      dutIo: SuperScalarFreeListIO,
      enables: Seq[Boolean]
  ): Unit = {
    require(
      enables.length == dutIo.config.numAllocatePorts,
      "Mismatch in enable flags and number of allocate ports"
    )
    println(s"[TB] Driving Allocate Ports - Enables: ${enables.mkString(",")}")
    for (i <- 0 until dutIo.config.numAllocatePorts) {
      dutIo.allocate(i).enable #= enables(i)
    }
    sleep(1) // Use sleep(1) for robustness
  }

  // --- Helper to drive free ports ---
  def driveFreePorts(
      dutIo: SuperScalarFreeListIO,
      frees: Seq[(Boolean, Int)] // Seq of (enable, physRegToFree)
  ): Unit = {
    require(
      frees.length <= dutIo.config.numFreePorts,
      "Too many free requests for available free ports"
    )
    println(s"[TB] Driving Free Ports - Requests (enable, preg): ${frees.map(f => s"(${f._1},p${f._2})").mkString(",")}")
    for (i <- 0 until dutIo.config.numFreePorts) {
      if (i < frees.length) {
        dutIo.free(i).enable #= frees(i)._1
        dutIo.free(i).physReg #= frees(i)._2
      } else {
        dutIo.free(i).enable #= false
      }
    }
    sleep(1) // Use sleep(1) for robustness
  }

  // --- Helper to drive checkpoint restore ---
  def driveRestore(dutIo: SuperScalarFreeListIO, enable: Boolean, mask: BigInt): Unit = {
    println(s"[TB] Driving Restore - Enable: $enable, Mask: ${if (enable) mask.toString(16) else "N/A"}")
    dutIo.restoreState.valid #= enable
    if (enable) {
      dutIo.restoreState.payload.freeMask #= mask
    }
    // Depending on usage, sleep(0) or sleep(1) might be needed.
    // If followed by waitSampling, sleep(0) is fine.
    // If followed by immediate combinational check, sleep(1) is safer.
    sleep(0) // Let's keep it sleep(0) if usually followed by waitSampling
  }

  // --- Helper to get internal mask ---
  def getInternalMask(tb: SuperScalarFreeListTestBench): BigInt = {
    tb.freeList.freeRegsMask.toBigInt
  }

  // --- Common setup for each test case ---
  def initSimSuper(dutIo: SuperScalarFreeListIO): Unit = {
    // Drive with enables=false, then sleep(1) is already handled by driveAllocatePorts
    driveAllocatePorts(dutIo, Seq.fill(dutIo.config.numAllocatePorts)(false))
    driveFreePorts(dutIo, Seq.fill(dutIo.config.numFreePorts)((false, 0)))
    driveRestore(dutIo, false, 0) // driveRestore has sleep(0)
    // dutIo.restoreState.ready should be driven by DUT, not typically TB here.
  }

  // --- Helper to check allocation results ---
  def checkAllocResults(
      allocIo: Vec[SuperScalarFreeListAllocatePort],
      expectedSuccess: Seq[Boolean],
      expectedPhysRegs: Seq[Option[Int]]
  ): Unit = {
    println(s"[TB] Checking Allocate Results (combinational):")
    println(s"  Expected Success: ${expectedSuccess.mkString(",")}")
    println(s"  Expected PhysRegs: ${expectedPhysRegs.map(_.getOrElse("N/A")).mkString(",")}")
    for (i <- 0 until allocIo.length) {
      val port = allocIo(i)
      val actualSuccess = port.success.toBoolean
      val actualPhysRegVal = port.physReg.toInt // Read once
      val actualPhysRegStr = if (actualSuccess || expectedSuccess(i)) s"p${actualPhysRegVal}" else "N/A"
      println(f"[TB]   Port $i: Actual Success=${actualSuccess}, Actual PhysReg=${actualPhysRegStr}%-4s (Input Enable was ${port.enable.toBoolean})")
      assert(
        actualSuccess == expectedSuccess(i),
        s"Alloc port $i: success mismatch. Expected ${expectedSuccess(i)}, got $actualSuccess"
      )
      if (expectedSuccess(i) && expectedPhysRegs(i).isDefined) {
        // Only check physReg if expected to be successful and a specific reg is expected
        assert(
          actualPhysRegVal == expectedPhysRegs(i).get,
          s"Alloc port $i: physReg mismatch. Expected p${expectedPhysRegs(i).get}, got p${actualPhysRegVal}"
        )
      }
    }
  }

  // --- Test Cases for SuperScalarFreeList ---

  test("SuperScalarFreeList - Initialization") {
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs,
      resetToFull = true,
      numAllocatePorts = 2,
      numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 200) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io)

      dut.clockDomain.waitSampling() // Let reset and init take effect. All enables are false.
      sleep(1) // Ensure outputs like numFreeRegs are stable after reset and init drives.

      println(s"[TB Init Check] Enabling all ${testConfig.numAllocatePorts} allocate ports simultaneously to check potential.")
      // Enable ALL ports we want to check for their initial allocation capability
      for (k <- 0 until testConfig.numAllocatePorts) {
        dut.io.allocate(k).enable #= true
      }
      sleep(1) // Allow combinational logic to update for ALL enabled ports

      // Now check the results for each port
      for (i <- 0 until testConfig.numAllocatePorts) {
        val currentSuccess = dut.io.allocate(i).success.toBoolean
        val currentPhysReg = dut.io.allocate(i).physReg.toInt
        println(s"[TB Init Check] Port $i: (after all enabled) success=${currentSuccess}, physReg=p${currentPhysReg}")

        assert(
          currentSuccess,
          s"Port $i: Expected success=true with all ports enabled, got ${currentSuccess}. " +
            s"Initial mask=${getInternalMask(dut).toString(16)}, numFreeRegs=${dut.io.numFreeRegs.toInt}"
        )
        assert(currentPhysReg == i + 1, s"Port $i: Expected physReg p${i + 1}, got p${currentPhysReg}")
      }

      // Disable all ports again to clean up for subsequent assertions on numFreeRegs etc.
      println(s"[TB Init Check] Disabling all allocate ports.")
      for (k <- 0 until testConfig.numAllocatePorts) {
        dut.io.allocate(k).enable #= false
      }
      sleep(1) // Allow disable to propagate

      assert(
        dut.io.numFreeRegs.toInt == numAllocatableRegs,
        s"Expected ${numAllocatableRegs} free regs, got ${dut.io.numFreeRegs.toInt}"
      )

      val expectedMask = (BigInt(1) << numTotalPhysRegs) - 1
      val p0MaskBit = BigInt(1) << 0
      val finalExpectedMask = expectedMask ^ p0MaskBit
      assert(
        getInternalMask(dut) == finalExpectedMask,
        s"Initial mask incorrect. Expected ${finalExpectedMask.toString(2)}, Got ${getInternalMask(dut).toString(2)}"
      )
    }
  }

  test("SuperScalarFreeList - Basic 2-wide Allocation") {
    val numAllocPorts = 2
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs,
      resetToFull = true,
      numAllocatePorts = numAllocPorts,
      numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 201) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io)
      dut.clockDomain.waitSampling()
      sleep(1) // For numFreeRegs from init to stabilize

      var allocatedCount = 0
      var nextExpectedPReg = 1

      println("[TB Basic 2-wide] Step 1: Allocate 2 regs")
      driveAllocatePorts(dut.io, Seq(true, true)) // driveAllocatePorts now has sleep(1)
      checkAllocResults(
        dut.io.allocate,
        expectedSuccess = Seq(true, true),
        expectedPhysRegs = Seq(Some(nextExpectedPReg), Some(nextExpectedPReg + 1))
      )
      // val numFreeBefore = dut.io.numFreeRegs.toInt // numFreeRegs is based on REG, so sample after waitSampling
      dut.clockDomain.waitSampling() // Allocations take effect on freeRegsMask_reg
      sleep(1) // For numFreeRegs output to update based on new freeRegsMask_reg
      allocatedCount += 2
      nextExpectedPReg += 2

      assert(
        dut.io.numFreeRegs.toInt == numAllocatableRegs - allocatedCount,
        s"NumFreeRegs mismatch after 2-wide alloc. Expected ${numAllocatableRegs - allocatedCount}, got ${dut.io.numFreeRegs.toInt}"
      )

      println("[TB Basic 2-wide] Step 2: Allocate 1 more (port 0 true, port 1 false)")
      driveAllocatePorts(dut.io, Seq(true, false))
      checkAllocResults(
        dut.io.allocate,
        expectedSuccess = Seq(true, false),
        expectedPhysRegs = Seq(Some(nextExpectedPReg), None)
      )
      dut.clockDomain.waitSampling()
      sleep(1)
      allocatedCount += 1
      nextExpectedPReg += 1
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - allocatedCount)

      println("[TB Basic 2-wide] Step 3: Allocate until nearly full")
      val regsToAllocateLoop = numAllocatableRegs - allocatedCount
      for (i <- 0 until regsToAllocateLoop / numAllocPorts) {
        driveAllocatePorts(dut.io, Seq(true, true))
        // Optional: checkAllocResults here before waitSampling
        dut.clockDomain.waitSampling()
        sleep(1)
        allocatedCount += 2
        nextExpectedPReg += 2 // This tracking might be off if regs are not contiguous due to prior ops, but ok for this linear test
      }
      val remainder = regsToAllocateLoop % numAllocPorts
      if (remainder > 0) {
        driveAllocatePorts(dut.io, Seq.tabulate(numAllocPorts)(idx => idx < remainder))
        dut.clockDomain.waitSampling()
        sleep(1)
        allocatedCount += remainder
      }
      assert(dut.io.numFreeRegs.toInt == 0, "List should be empty")

      println("[TB Basic 2-wide] Step 4: Try to allocate more - should fail")
      driveAllocatePorts(dut.io, Seq(true, true))
      checkAllocResults(
        dut.io.allocate,
        expectedSuccess = Seq(false, false),
        expectedPhysRegs = Seq(None, None)
      )
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.numFreeRegs.toInt == 0, "NumFreeRegs should still be 0")

      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - 2-wide Free") {
    val numFreePorts = 2
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs,
      resetToFull = true,
      numAllocatePorts = 1,
      numFreePorts = numFreePorts
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 202) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io)
      dut.clockDomain.waitSampling()
      sleep(1)

      println("[TB 2-wide Free] Step 1: Allocate all regs")
      val allocatedPregs = collection.mutable.ListBuffer[Int]()
      for (i <- 0 until numAllocatableRegs) {
        driveAllocatePorts(dut.io, Seq(true))
        // Combinational check before waitSampling
        assert(dut.io.allocate(0).success.toBoolean, s"Alloc failed for reg $i unexpectedly")
        allocatedPregs += dut.io.allocate(0).physReg.toInt
        dut.clockDomain.waitSampling()
        sleep(1) // For numFreeRegs to update
      }
      driveAllocatePorts(dut.io, Seq(false)) // Disable alloc port
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.numFreeRegs.toInt == 0, "Expected 0 free regs after allocating all")

      println("[TB 2-wide Free] Step 2: Free them 2 at a time")
      var freedCount = 0
      val toFreeInPairs = allocatedPregs.toList.grouped(numFreePorts).toList

      for (pair <- toFreeInPairs) {
        val freeOps = pair.map(pReg => (true, pReg))
        driveFreePorts(dut.io, freeOps) // Has sleep(1)
        dut.clockDomain.waitSampling()
        sleep(1)
        freedCount += pair.length
        assert(
          dut.io.numFreeRegs.toInt == freedCount,
          s"NumFreeRegs expected $freedCount, got ${dut.io.numFreeRegs.toInt} after freeing ${pair.mkString(",")}"
        )
      }
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs, "All regs should be free")

      driveFreePorts(dut.io, Seq.fill(numFreePorts)((false, 0)))
      dut.clockDomain.waitSampling()
      sleep(1)

      println("[TB 2-wide Free] Step 3: Try freeing p0")
      val countBeforeP0Free = dut.io.numFreeRegs.toInt
      driveFreePorts(dut.io, Seq((true, 0)))
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.numFreeRegs.toInt == countBeforeP0Free, "Freeing p0 should not change count")
      driveFreePorts(dut.io, Seq((false, 0)))
      dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - Concurrent 2-Alloc, 1-Free (different regs)") {
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs,
      resetToFull = true,
      numAllocatePorts = 2,
      numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 203) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      println("[TB Concurrent A/F] Step 1: Allocate p1, p2")
      driveAllocatePorts(dut.io, Seq(true, true))
      checkAllocResults(dut.io.allocate, Seq(true, true), Seq(Some(1), Some(2)))
      val p1_val = dut.io.allocate(0).physReg.toInt
      val p2_val = dut.io.allocate(1).physReg.toInt
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 2)

      println("[TB Concurrent A/F] Step 2: Concurrently Alloc(p3,p4) and Free(p1)")
      driveAllocatePorts(dut.io, Seq(true, true)) // Sets alloc enables, sleep(1)
      driveFreePorts(dut.io, Seq((true, p1_val)))   // Sets free enable, sleep(1). Alloc enables are still true.

      // Combinational check: All enables (2 alloc, 1 free) are now active.
      // DUT's current freeRegsMask_reg has p1,p2 allocated.
      // Alloc logic: port0 wants p3, port1 wants p4.
      val chosenForAlloc0 = dut.io.allocate(0).physReg.toInt
      val chosenForAlloc1 = dut.io.allocate(1).physReg.toInt
      println(s"[TB Concurrent A/F] Combinational check: alloc0 gets p$chosenForAlloc0, alloc1 gets p$chosenForAlloc1 (p1_val was p$p1_val)")
      assert(dut.io.allocate(0).success.toBoolean && dut.io.allocate(1).success.toBoolean, "Both allocs should succeed")
      assert(chosenForAlloc0 == 3, s"Expected alloc port 0 to choose p3, got p$chosenForAlloc0")
      assert(chosenForAlloc1 == 4, s"Expected alloc port 1 to choose p4, got p$chosenForAlloc1")

      dut.clockDomain.waitSampling(); sleep(1) // Operations take effect on freeRegsMask_reg

      driveAllocatePorts(dut.io, Seq(false, false))
      driveFreePorts(dut.io, Seq((false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)

      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 3,
        s"Expected ${numAllocatableRegs - 3} free regs, got ${dut.io.numFreeRegs.toInt}")

      val finalMask = getInternalMask(dut)
      println(s"[TB Concurrent A/F] Final mask: ${finalMask.toString(16)}")
      assert((finalMask & (BigInt(1) << p1_val)) != 0, s"p$p1_val should be free")
      assert((finalMask & (BigInt(1) << p2_val)) == 0, s"p$p2_val should be allocated")
      assert((finalMask & (BigInt(1) << chosenForAlloc0)) == 0, s"p$chosenForAlloc0 (p3) should be allocated")
      assert((finalMask & (BigInt(1) << chosenForAlloc1)) == 0, s"p$chosenForAlloc1 (p4) should be allocated")
    }
  }

  test("SuperScalarFreeList - Checkpoint Save and Restore (2-wide alloc)") {
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = true, numAllocatePorts = 2, numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 204) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io); dut.clockDomain.waitSampling(); sleep(1)

      println("[TB Checkpoint] Allocating p1,p2 then p3,p4")
      driveAllocatePorts(dut.io, Seq(true, true)); dut.clockDomain.waitSampling(); sleep(1) // p1,p2
      // val allocatedPRegs_1_2 = dut.io.allocate.map(_.physReg.toInt).take(2)
      driveAllocatePorts(dut.io, Seq(true, true)); dut.clockDomain.waitSampling(); sleep(1) // p3,p4
      // val allocatedPRegs_3_4 = dut.io.allocate.map(_.physReg.toInt).take(2)

      driveAllocatePorts(dut.io, Seq(false, false)) // De-assert
      val maskAtCheckpoint = getInternalMask(dut)
      val numFreeAtCheckpoint = dut.io.numFreeRegs.toInt
      println(s"[TB Checkpoint] Saved checkpoint: mask=${maskAtCheckpoint.toString(16)}, numFree=$numFreeAtCheckpoint")
      dut.clockDomain.waitSampling(); sleep(1)

      println("[TB Checkpoint] Allocating p5,p6 (after checkpoint save)")
      driveAllocatePorts(dut.io, Seq(true, true))
      checkAllocResults(dut.io.allocate, Seq(true,true), Seq(Some(5), Some(6))) // Assuming p5,p6 are next
      val p5_before_restore = dut.io.allocate(0).physReg.toInt
      val p6_before_restore = dut.io.allocate(1).physReg.toInt
      dut.clockDomain.waitSampling(); sleep(1)
      driveAllocatePorts(dut.io, Seq(false, false))
      assert(getInternalMask(dut) != maskAtCheckpoint)
      println(s"[TB Checkpoint] After allocating p5,p6: mask=${getInternalMask(dut).toString(16)}")


      println("[TB Checkpoint] Restoring to checkpoint")
      driveRestore(dut.io, true, maskAtCheckpoint) // driveRestore has sleep(0)
      dut.clockDomain.waitSampling(); sleep(1)      // Restore takes effect
      driveRestore(dut.io, false, 0)
      dut.clockDomain.waitSampling(); sleep(1)      // Allow restore valid=false to propagate

      assert(getInternalMask(dut) == maskAtCheckpoint, s"Mask did not restore. Expected ${maskAtCheckpoint.toString(16)}, got ${getInternalMask(dut).toString(16)}")
      assert(dut.io.numFreeRegs.toInt == numFreeAtCheckpoint, "NumFreeRegs incorrect after restore")
      println(s"[TB Checkpoint] After restore: mask=${getInternalMask(dut).toString(16)}, numFree=${dut.io.numFreeRegs.toInt}")


      println("[TB Checkpoint] Allocating again, should get p5,p6")
      driveAllocatePorts(dut.io, Seq(true, true))
      checkAllocResults(dut.io.allocate, Seq(true,true), Seq(Some(p5_before_restore), Some(p6_before_restore)))
      val p5_again = dut.io.allocate(0).physReg.toInt
      val p6_again = dut.io.allocate(1).physReg.toInt
      dut.clockDomain.waitSampling(); sleep(1)

      assert(p5_again == p5_before_restore, s"Expected p${p5_before_restore}, got p$p5_again")
      assert(p6_again == p6_before_restore, s"Expected p${p6_before_restore}, got p$p6_again")
      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - Allocate with Limited Availability (e.g., 1 free, 2 alloc ports)") {
    val numAllocPorts = 2
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = false, numAllocatePorts = numAllocPorts, numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 205) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.numFreeRegs.toInt == 0, "Should be 0 free regs initially")

      println("[TB Limited Avail] Manually freeing p1")
      driveFreePorts(dut.io, Seq((true, 1)))
      dut.clockDomain.waitSampling(); sleep(1)
      driveFreePorts(dut.io, Seq((false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.numFreeRegs.toInt == 1, "Should have 1 free reg (p1)")
      assert((getInternalMask(dut) & (BigInt(1) << 1)) != 0, "p1 should be free in mask")

      println("[TB Limited Avail] Try to allocate with 2 ports, only p1 is free")
      driveAllocatePorts(dut.io, Seq(true, true)) // driveAllocatePorts has sleep(1)
      checkAllocResults(
        dut.io.allocate,
        expectedSuccess = Seq(true, false), // Port 0 gets p1, Port 1 fails
        expectedPhysRegs = Seq(Some(1), None)
      )
      val p1_alloc_port0 = dut.io.allocate(0).physReg.toInt

      dut.clockDomain.waitSampling(); sleep(1) // Allocation takes effect

      assert(p1_alloc_port0 == 1, "Port 0 should have allocated p1")
      assert(dut.io.numFreeRegs.toInt == 0, "NumFreeRegs should be 0 after p1 is allocated")
      assert((getInternalMask(dut) & (BigInt(1) << 1)) == 0, "p1 should be allocated in mask")

      driveAllocatePorts(dut.io, Seq(false, false))
      dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - Freeing the Same Register on Multiple Ports Simultaneously") {
    val numFreePorts = 2
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = true, numAllocatePorts = 1, numFreePorts = numFreePorts
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 206) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io); dut.clockDomain.waitSampling(); sleep(1)

      println("[TB Multi-Free Same] Allocate p1")
      driveAllocatePorts(dut.io, Seq(true))
      assert(dut.io.allocate(0).success.toBoolean)
      val p1_val = dut.io.allocate(0).physReg.toInt
      dut.clockDomain.waitSampling(); sleep(1)
      driveAllocatePorts(dut.io, Seq(false))
      dut.clockDomain.waitSampling(); sleep(1)
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs - 1)
      assert((getInternalMask(dut) & (BigInt(1) << p1_val)) == 0, s"p$p1_val should be allocated")

      println(s"[TB Multi-Free Same] Free p$p1_val on both free ports simultaneously")
      driveFreePorts(dut.io, Seq((true, p1_val), (true, p1_val))) // driveFreePorts has sleep(1)
      dut.clockDomain.waitSampling(); sleep(1)

      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs, "NumFreeRegs should be back to full allocatable")
      assert((getInternalMask(dut) & (BigInt(1) << p1_val)) != 0, s"p$p1_val should be free again")

      driveFreePorts(dut.io, Seq((false, 0), (false, 0)))
      dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - Concurrent Allocate and Free Targeting the Same Register") {
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = true, numAllocatePorts = 1, numFreePorts = 1
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 207) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io); dut.clockDomain.waitSampling(); sleep(1)
      val initialNumFree = dut.io.numFreeRegs.toInt

      println("[TB Alloc/Free Same] Port 0 wants to allocate (p1). Free port 0 wants to free p1.")
      driveAllocatePorts(dut.io, Seq(true))   // Sets alloc enable, sleep(1)
      driveFreePorts(dut.io, Seq((true, 1))) // Sets free enable for p1, sleep(1). Alloc enable still true.

      // Combinational check:
      assert(dut.io.allocate(0).success.toBoolean, "Allocate port 0 should succeed combinatorially")
      val chosenForAlloc = dut.io.allocate(0).physReg.toInt
      assert(chosenForAlloc == 1, s"Expected alloc port 0 to choose p1, got p$chosenForAlloc")
      println(s"[TB Alloc/Free Same] Combinational: alloc gets p$chosenForAlloc, free targets p1")

      dut.clockDomain.waitSampling(); sleep(1) // Operations take effect

      driveAllocatePorts(dut.io, Seq(false))
      driveFreePorts(dut.io, Seq((false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)

      assert((getInternalMask(dut) & (BigInt(1) << 1)) != 0, "p1 should be free in the mask after concurrent alloc/free")
      assert(dut.io.numFreeRegs.toInt == initialNumFree,
        s"NumFreeRegs should be $initialNumFree (unchanged), got ${dut.io.numFreeRegs.toInt}")
    }
  }

  test("SuperScalarFreeList - Allocation Exhaustion and Recovery (2-alloc, 2-free)") {
    val numPorts = 2
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = true, numAllocatePorts = numPorts, numFreePorts = numPorts
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 208) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io); dut.clockDomain.waitSampling(); sleep(1)

      var cycle = 0
      var allAllocatedPregsList = List[Int]() // Store all allocated pregs over time

      println("--- Phase 1: Allocating all ---")
      var totalAllocated = 0
      while (totalAllocated < numAllocatableRegs) {
        cycle += 1
        driveAllocatePorts(dut.io, Seq.fill(numPorts)(true))
        val newlyAllocatedThisCycle = dut.io.allocate.filter(_.success.toBoolean).map(_.physReg.toInt)
        allAllocatedPregsList = allAllocatedPregsList ++ newlyAllocatedThisCycle
        dut.clockDomain.waitSampling(); sleep(1)
        totalAllocated += newlyAllocatedThisCycle.length
        println(s"Cycle $cycle: Allocated ${newlyAllocatedThisCycle.mkString(",")}. Total allocated: $totalAllocated. Free: ${dut.io.numFreeRegs.toInt}")
        if (newlyAllocatedThisCycle.isEmpty && totalAllocated < numAllocatableRegs) {
          assert(false, "Allocation stalled before filling up")
        }
      }
      assert(dut.io.numFreeRegs.toInt == 0, "All allocatable regs should be allocated")
      driveAllocatePorts(dut.io, Seq.fill(numPorts)(false)); dut.clockDomain.waitSampling(); sleep(1)

      println("--- Phase 2: Freeing all ---")
      var totalFreed = 0
      val regsToFree = allAllocatedPregsList.distinct.sorted // Free in defined order for predictability
      
      for (batch <- regsToFree.grouped(numPorts)) {
        cycle += 1
        val freeOps = batch.map(pReg => (true, pReg))
        driveFreePorts(dut.io, freeOps)
        dut.clockDomain.waitSampling(); sleep(1)
        totalFreed += batch.length
        println(s"Cycle $cycle: Freed ${batch.mkString(",")}. Total freed: $totalFreed. Free: ${dut.io.numFreeRegs.toInt}")
      }
      assert(dut.io.numFreeRegs.toInt == numAllocatableRegs, "All allocatable regs should be free")
      driveFreePorts(dut.io, Seq.fill(numPorts)((false, 0))); dut.clockDomain.waitSampling(); sleep(1)

      println("--- Phase 3: Re-Allocating all ---")
      totalAllocated = 0
      allAllocatedPregsList = List() // Reset for re-allocation tracking
      while (totalAllocated < numAllocatableRegs) {
        cycle += 1
        driveAllocatePorts(dut.io, Seq.fill(numPorts)(true))
        val newlyAllocatedThisCycle = dut.io.allocate.filter(_.success.toBoolean).map(_.physReg.toInt)
        allAllocatedPregsList = allAllocatedPregsList ++ newlyAllocatedThisCycle
        dut.clockDomain.waitSampling(); sleep(1)
        totalAllocated += newlyAllocatedThisCycle.length
        println(s"Cycle $cycle: Re-Allocated ${newlyAllocatedThisCycle.mkString(",")}. Total allocated: $totalAllocated. Free: ${dut.io.numFreeRegs.toInt}")
      }
      assert(dut.io.numFreeRegs.toInt == 0, "All allocatable regs should be re-allocated")
      driveAllocatePorts(dut.io, Seq.fill(numPorts)(false)); dut.clockDomain.waitSampling()
    }
  }

  test("SuperScalarFreeList - Restore to Partially Full then Allocate/Free") {
    val testConfig = SuperScalarFreeListConfig(
      numPhysRegs = numTotalPhysRegs, resetToFull = true, numAllocatePorts = 2, numFreePorts = 2
    )
    simConfig.compile(new SuperScalarFreeListTestBench(testConfig)).doSim(seed = 209) { dut =>
      dut.clockDomain.forkStimulus(10)
      initSimSuper(dut.io); dut.clockDomain.waitSampling(); sleep(1)

      var partialMask = (BigInt(1) << numTotalPhysRegs) - 1
      partialMask &= ~(BigInt(1) << 0)
      partialMask &= ~(BigInt(1) << 1); partialMask &= ~(BigInt(1) << 3); partialMask &= ~(BigInt(1) << 5)
      val numFreeInPartialMask = partialMask.bitCount // BitCount on BigInt is convenient

      println(s"[TB Restore Partial] Restoring to partialMask=${partialMask.toString(16)} (numFree=$numFreeInPartialMask)")
      driveRestore(dut.io, true, partialMask); dut.clockDomain.waitSampling(); sleep(1)
      driveRestore(dut.io, false, 0); dut.clockDomain.waitSampling(); sleep(1)

      assert(getInternalMask(dut) == partialMask, "Mask did not restore to partialMask correctly")
      assert(dut.io.numFreeRegs.toInt == numFreeInPartialMask, s"NumFreeRegs expected $numFreeInPartialMask, got ${dut.io.numFreeRegs.toInt}")

      println("[TB Restore Partial] Allocating 2 (expect p2, p4)")
      driveAllocatePorts(dut.io, Seq(true, true))
      checkAllocResults(dut.io.allocate, Seq(true, true), Seq(Some(2), Some(4)))
      val p2_alloc = dut.io.allocate(0).physReg.toInt
      val p4_alloc = dut.io.allocate(1).physReg.toInt
      dut.clockDomain.waitSampling(); sleep(1)
      driveAllocatePorts(dut.io, Seq(false, false)); dut.clockDomain.waitSampling(); sleep(1)

      assert(p2_alloc == 2 && p4_alloc == 4, "Allocation after partial restore incorrect")
      assert(dut.io.numFreeRegs.toInt == numFreeInPartialMask - 2, "NumFreeRegs incorrect after alloc from partial restore")

      println(s"[TB Restore Partial] Freeing p1 and p$p2_alloc (p2)")
      driveFreePorts(dut.io, Seq((true, 1), (true, p2_alloc)))
      dut.clockDomain.waitSampling(); sleep(1)
      driveFreePorts(dut.io, Seq((false, 0), (false, 0))); dut.clockDomain.waitSampling(); sleep(1)
      
      assert(dut.io.numFreeRegs.toInt == numFreeInPartialMask, "NumFreeRegs incorrect after free from partial restore state")
      assert((getInternalMask(dut) & (BigInt(1) << 1)) != 0, "p1 should be free")
      assert((getInternalMask(dut) & (BigInt(1) << p2_alloc)) != 0, "p2 should be free")
    }
  }
  thatsAll()
}
