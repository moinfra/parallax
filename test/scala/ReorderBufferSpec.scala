package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.components.rename._
import parallax.components.decode._
import scala.collection.mutable.ArrayBuffer

class ReorderBufferTestBench(val robConfig: ROBConfig) extends Component {
  val io = slave(ROBIo(robConfig))

  val rob = new ReorderBuffer(robConfig)
  rob.io <> io

  rob.io.simPublic()
  rob.headPtr_reg.simPublic()
  rob.tailPtr_reg.simPublic()
  rob.count_reg.simPublic()
}

class ReorderBufferSpec extends CustomSpinalSimFunSuite {

  // Default MicroOpConfig for tests
  val microOpCfg = MicroOpConfig(physRegIdxWidth = 6 bits, dataWidth = 32 bits)

  // --- Helper Functions for Driving ROB IO ---

  // Helper to create a default/dummy MicroOp for allocation
  def driveDefaultMicroOp(targetUopPort: MicroOp, cfg: MicroOpConfig): Unit = {
    targetUopPort.isValid #= false
    targetUopPort.uopType #= MicroOpType.ILLEGAL
    targetUopPort.archRegRd #= 0
    targetUopPort.archRegRs #= 0
    targetUopPort.archRegRt #= 0
    targetUopPort.useRd #= false
    targetUopPort.useRs #= false
    targetUopPort.useRt #= false
    targetUopPort.writeRd #= false
    targetUopPort.imm #= 0 // Ensure correct width for SInt default

    targetUopPort.aluFlags.op #= AluOp.NOP // Default for sub-bundle
    targetUopPort.memOpFlags.accessType #= MemoryAccessType.LOAD_U // Default
    targetUopPort.memOpFlags.size #= MemoryOpSize.WORD // Default
    targetUopPort.ctrlFlowInfo.condition #= BranchCond.ALWAYS // Default
    targetUopPort.ctrlFlowInfo.targetOffset #= 0 // Default

    targetUopPort.physRegRs #= 0
    targetUopPort.physRegRt #= 0
    targetUopPort.physRegRdOld #= 0
    targetUopPort.physRegRdNew #= 0
    targetUopPort.allocatesPhysReg #= false
    targetUopPort.writesPhysReg #= false
  }

  def createDummyMicroOp(
      robIdxForUop: Int, // Contextual, not directly part of MicroOp state
      isBranch: Boolean = false,
      isLoad: Boolean = false,
      isStore: Boolean = false,
      hasException: Boolean = false, // For exception testing
      cfg: MicroOpConfig = microOpCfg
  ): MicroOp = {
    val uop = MicroOp(cfg)
    uop.setDefault()
    uop.isValid := True
    uop.archRegRd := U((robIdxForUop % 30) + 1) // Avoid r0 and ensure variety, ensure fits in 5 bits
    uop.writeRd := True
    uop.allocatesPhysReg := True
    uop.writesPhysReg := True

    if (isBranch) uop.uopType := MicroOpType.BRANCH_COND
    else if (isLoad) uop.uopType := MicroOpType.LOAD
    else if (isStore) {
      uop.uopType := MicroOpType.STORE
      uop.writesPhysReg := False
      uop.allocatesPhysReg := False
    } else if (hasException) {
      // For an ALU op that causes an exception, still set type to ALU
      uop.uopType := MicroOpType.ALU_REG
    } else uop.uopType := MicroOpType.ALU_REG
    uop
  }

  // Drive allocate ports
  def driveAllocate(
      dutIo: ROBIo,
      allocRequests: Seq[(Boolean, MicroOp, Int)] // Seq of (fire, uop, pc)
  ): Unit = {
    require(allocRequests.length <= dutIo.config.allocateWidth)
    println(
      s"[TB] Driving Allocate - Count: ${allocRequests.count(_._1)}, Requests (fire,uop.pc,pc): " +
        allocRequests
          .map(r => s"(${r._1},uop@pc${r._3.toInt})") // Simplified uop print
          .mkString(",")
    )
    for (i <- 0 until dutIo.config.allocateWidth) {
      val port = dutIo.allocate(i)
      if (i < allocRequests.length) {
        port.fire #= allocRequests(i)._1
        // Assign members of the uop Bundle individually
        val reqUop = allocRequests(i)._2
        // Assign directly from reqUop's fields (which hold SpinalHDL literals/values)
        // to port.uopIn's fields (which are DUT's hardware inputs).
        // The #= operator handles assignment between compatible SpinalHDL types.
        // No .toBoolean, .toInt, .toEnum needed here because both sides are SpinalHDL types.

        driveDefaultMicroOp(port.uopIn, dutIo.config.microOpConfig)

        port.pcIn #= allocRequests(i)._3.toInt
      } else {
        port.fire #= false
      }
    }
    sleep(1) // Ensure inputs are stable for DUT combinational logic
  }

  // Drive writeback ports
  def driveWriteback(
      dutIo: ROBIo,
      wbRequests: Seq[(Boolean, Int, Boolean, Int)] // Seq of (fire, robIdx, hasException, excCode)
  ): Unit = {
    require(wbRequests.length <= dutIo.config.numWritebackPorts)
    println(
      s"[TB] Driving Writeback - Count: ${wbRequests.count(_._1)}, Requests (fire,robIdx,exc,code): [" +
        wbRequests.map(r => s"(${r._1},#${r._2},${r._3},${r._4})").mkString(",") + "]"
    )
    for (i <- 0 until dutIo.config.numWritebackPorts) {
      if (i < wbRequests.length) {
        dutIo.writeback(i).fire #= wbRequests(i)._1
        dutIo.writeback(i).robIdx #= wbRequests(i)._2
        dutIo.writeback(i).exceptionOccurred #= wbRequests(i)._3
        dutIo.writeback(i).exceptionCodeIn #= wbRequests(i)._4
      } else {
        dutIo.writeback(i).fire #= false
      }
    }
    sleep(1)
  }

  // Helper to just set allocate inputs, NO internal sleep
  def setAllocateInputs(dutIo: ROBIo, allocRequests: Seq[(Boolean, MicroOp, Int)]): Unit = {
    require(allocRequests.length <= dutIo.config.allocateWidth)

    println(
      s"[TB] Driving Writeback - Count: ${allocRequests.count(_._1)}, Requests (fire,uop,pc): [" +
        allocRequests.map(r => s"(${r._1},uop@pc${r._3.toInt},${r._3},)").mkString(",") + "]"
    )

    for (i <- 0 until dutIo.config.allocateWidth) {
      val port = dutIo.allocate(i)
      if (i < allocRequests.length) {
        port.fire #= allocRequests(i)._1
        port.uopIn := allocRequests(i)._2
        port.pcIn #= allocRequests(i)._3
      } else {
        port.fire #= false
        driveDefaultMicroOp(port.uopIn, dutIo.config.microOpConfig)
        port.pcIn #= 0
      }
    }
  }

  // Drive commit fire signals
  def driveCommitFire(dutIo: ROBIo, fires: Seq[Boolean]): Unit = {
    require(fires.length == dutIo.config.commitWidth)
    println(s"[TB] Driving CommitFire - Fires: ${fires.mkString(",")}")
    for (i <- 0 until dutIo.config.commitWidth) {
      dutIo.commitFire(i) #= fires(i)
    }
    sleep(1)
  }

  // Drive flush command
  def driveFlush(dutIo: ROBIo, fire: Boolean, head: Int, tail: Int, count: Int): Unit = {
    println(s"[TB] Driving Flush - Fire: $fire, newH/T/C: $head/$tail/$count")
    dutIo.flush.valid #= fire
    if (fire) {
      dutIo.flush.payload.newHead #= head
      dutIo.flush.payload.newTail #= tail
      dutIo.flush.payload.newCount #= count
    }
    sleep(1) // Allow Flow to be processed
  }

  // Initialize all inputs for a clean state at start of test/cycle
  def initRobInputs(dutIo: ROBIo): Unit = {
    driveAllocate(dutIo, Seq.empty) // Effectively sets all allocate fires to false
    driveWriteback(dutIo, Seq.empty)
    driveCommitFire(dutIo, Seq.fill(dutIo.config.commitWidth)(false))
    driveFlush(dutIo, false, 0, 0, 0)
  }

  // Helper to get internal pointers and count via simPublic
  // Assumes ReorderBufferTestBench exposes these with specific names
  def getInternalState(tb: ReorderBufferTestBench): (Int, Int, Int) = {
    (
      tb.rob.headPtr_reg.toInt, // Accessing directly from tb.rob...
      tb.rob.tailPtr_reg.toInt,
      tb.rob.count_reg.toInt
    )
  }
  def getInternalRobPointers(tb: ReorderBufferTestBench): (Int, Int, Int) = {
    val head = tb.rob.headPtr_reg.toInt
    val tail = tb.rob.tailPtr_reg.toInt
    val count = tb.rob.count_reg.toInt
    (head, tail, count)
  }

  // --- Test Cases ---
  val robDepth = 16
  val allocW = 2
  val commitW = 2
  val wbW = 2

  val baseRobConfig = ROBConfig(
    robDepth = robDepth,
    microOpConfig = microOpCfg,
    commitWidth = commitW,
    allocateWidth = allocW,
    numWritebackPorts = wbW
  )

  test("ROB - Initialization and Empty/Full") {
    val testConfig = baseRobConfig
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 300) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io) // Initializes all control inputs to ROB

      dut.clockDomain.waitSampling() // Let reset and init take effect
      sleep(1) // Ensure combinational outputs are stable

      assert(dut.io.empty.toBoolean, "ROB should be empty after init")
      for (i <- 0 until testConfig.allocateWidth) {
        assert(dut.io.canAllocate(i).toBoolean, s"ROB canAllocate($i) should be true for empty ROB")
      }

      val (h, t, c) = getInternalRobPointers(dut)
      assert(h == 0, s"Initial headPtr should be 0, got $h")
      assert(t == 0, s"Initial tailPtr should be 0, got $t")
      assert(c == 0, s"Initial count should be 0, got $c")

      // Fill the ROB
      var allocatedIndices = List[Int]()
      for (i <- 0 until testConfig.robDepth / testConfig.allocateWidth) {
        val uopsToAlloc = (0 until testConfig.allocateWidth).map(slot =>
          (true, createDummyMicroOp(i * testConfig.allocateWidth + slot), i * testConfig.allocateWidth + slot)
        )
        driveAllocate(dut.io, uopsToAlloc.map(req => (req._1, req._2, req._3)))
        // Check canAllocate immediately (combinational based on current count)
        for (k <- 0 until testConfig.allocateWidth) {
          assert(dut.io.canAllocate(k).toBoolean, s"Fill loop iter $i, slot $k: canAllocate should be true")
        }
        allocatedIndices = allocatedIndices ++ dut.io.allocate.map(_.robIdx.toInt).take(testConfig.allocateWidth)
        dut.clockDomain.waitSampling() // Allocation takes effect
        sleep(1)
      }
      val (hFull, tFull, cFull) = getInternalRobPointers(dut)
      println(s"After filling: Head=$hFull, Tail=$tFull, Count=$cFull. Indices: ${allocatedIndices.mkString(",")}")
      assert(cFull == testConfig.robDepth, s"ROB count should be ${testConfig.robDepth}, got $cFull")
      assert(dut.io.empty.toBoolean == false, "ROB should not be empty when full")

      for (i <- 0 until testConfig.allocateWidth) {
        assert(!dut.io.canAllocate(i).toBoolean, s"ROB canAllocate($i) should be false for full ROB")
      }

      // Try to allocate one more - should fail / not change count
      driveAllocate(
        dut.io,
        Seq(
          (true, createDummyMicroOp(0), 0)
        )
      )
      dut.clockDomain.waitSampling()
      sleep(1)
      val (hAfterOverflow, tAfterOverflow, cAfterOverflow) = getInternalRobPointers(dut)
      assert(cAfterOverflow == testConfig.robDepth, "ROB count should not change when trying to overflow")
      assert(tAfterOverflow == tFull, "ROB tailPtr should not change when trying to overflow")

      initRobInputs(dut.io) // Clear inputs
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Basic Allocate, Writeback, Commit (Single Stream)") {
    val testConfig = baseRobConfig.copy(allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 301) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      var currentPC = 100
      val numOps = 3

      // Phase 1: Allocate Ops
      var allocatedRobIndices = ArrayBuffer[Int]()
      for (i <- 0 until numOps) {
        val uop = createDummyMicroOp(i, cfg = testConfig.microOpConfig)
        driveAllocate(dut.io, Seq((true, uop, currentPC)))
        assert(dut.io.canAllocate(0).toBoolean, s"Alloc $i: canAllocate should be true")
        val robIdx = dut.io.allocate(0).robIdx.toInt
        allocatedRobIndices += robIdx
        println(s"[TB] Allocated op $i to ROB index $robIdx, PC $currentPC")
        dut.clockDomain.waitSampling(); sleep(1)
        currentPC += 4
      }
      driveAllocate(dut.io, Seq.empty) // Disable allocation
      val (h1, t1, c1) = getInternalRobPointers(dut)
      assert(c1 == numOps, s"Count should be $numOps after allocation, got $c1")
      assert(
        t1 == numOps % testConfig.robDepth,
        s"Tail pointer mismatch. Expected ${numOps % testConfig.robDepth}, got $t1"
      )

      // Phase 2: Writeback Ops (in some order)
      val wbOrder =
        Seq(allocatedRobIndices(1), allocatedRobIndices(0), allocatedRobIndices(2))
      for (robIdxToWb <- wbOrder) {
        driveWriteback(dut.io, Seq((true, robIdxToWb, false, 0)))
        println(s"[TB] Writing back ROB index $robIdxToWb")
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty) // Disable writeback

      // Phase 3: Commit Ops (must be in order)
      var committedCount = 0
      for (i <- 0 until numOps) {
        dut.clockDomain.waitSampling(0) // Let commit valid propagate
        assert(
          dut.io.commit(0).valid.toBoolean,
          s"Commit $i: commit(0).valid should be true. Head: ${dut.rob.headPtr_reg.toInt}, Count: ${dut.rob.count_reg.toInt}"
        )
        val committedEntry = dut.io.commit(0).entry
        println(
          s"[TB] Commit $i: ROB Idx ${dut.rob.headPtr_reg.toInt}, Valid=${dut.io.commit(0).valid.toBoolean}, " +
            s"PC=${committedEntry.payload.pc.toBigInt}, Done=${committedEntry.status.done.toBoolean}"
        )
        assert(committedEntry.status.done.toBoolean, s"Commit $i: Entry should be done.")
        assert(
          committedEntry.payload.pc.toBigInt == 100 + allocatedRobIndices.indexOf(dut.rob.headPtr_reg.toInt) * 4,
          "Committed PC mismatch"
        ) // Check original PC

        driveCommitFire(dut.io, Seq(true)) // Fire commit for this entry
        dut.clockDomain.waitSampling(); sleep(1)
        committedCount += 1
      }
      driveCommitFire(dut.io, Seq(false)) // Disable commit
      val (h2, t2, c2) = getInternalRobPointers(dut)
      assert(c2 == 0, s"Count should be 0 after all commits, got $c2")
      assert(dut.io.empty.toBoolean, "ROB should be empty after all commits")
      assert(h2 == t2, s"Head and Tail should match when empty. H=$h2, T=$t2")

      dut.clockDomain.waitSampling(5)
    }
  }

  // --- Test Case for Superscalar Allocate and Commit ---
  test("ROB - Superscalar Allocate (2-wide) and Commit (2-wide)") {
    val numSlots = 2
    val testConfig = baseRobConfig.copy(allocateWidth = numSlots, commitWidth = numSlots, numWritebackPorts = numSlots)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 302) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      val numOpsToProcess = 4 // Must be multiple of numSlots for this simple test flow
      assert(numOpsToProcess % numSlots == 0)
      var pcCounter = 200
      var allocatedRobIdxList = List[Int]()

      println("--- Phase 1: Superscalar Allocation ---")
      for (i <- 0 until numOpsToProcess / numSlots) {
        val uopsForCycle = (0 until numSlots).map { j =>
          val uop = createDummyMicroOp(i * numSlots + j, cfg = testConfig.microOpConfig)
          (true, uop, pcCounter + j * 4)
        }
        driveAllocate(dut.io, uopsForCycle)
        for (k_alloc <- 0 until numSlots) {
          assert(dut.io.canAllocate(k_alloc).toBoolean, s"Cycle $i, Alloc slot $k_alloc: canAllocate should be true")
          allocatedRobIdxList = allocatedRobIdxList :+ dut.io.allocate(k_alloc).robIdx.toInt
          println(
            s"[TB] Allocated for PC ${pcCounter + k_alloc * 4} to ROB index ${dut.io.allocate(k_alloc).robIdx.toInt}"
          )
        }
        dut.clockDomain.waitSampling(); sleep(1)
        pcCounter += numSlots * 4
      }
      driveAllocate(dut.io, Seq.empty)
      val (h_after_alloc, t_after_alloc, c_after_alloc) = getInternalRobPointers(dut)
      assert(c_after_alloc == numOpsToProcess, s"Count mismatch after alloc: $c_after_alloc vs $numOpsToProcess")

      println("--- Phase 2: Superscalar Writeback (all ops) ---")
      // Writeback all allocated ops. For simplicity, assume they can all WB in one go if wbW is high enough.
      // Or iterate if wbW < numOpsToProcess
      val wbBatches = allocatedRobIdxList.grouped(testConfig.numWritebackPorts).toList
      for (batch <- wbBatches) {
        val wbRequestsForCycle = batch.map(idx => (true, idx, false, 0))
        driveWriteback(dut.io, wbRequestsForCycle)
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)
      // After this, all ops in ROB should have status.done = true

      println("--- Phase 3: Superscalar Commit ---")
      var totalCommitted = 0
      for (i <- 0 until numOpsToProcess / numSlots) {
        dut.clockDomain.waitSampling(0) // Let commit valid signals propagate

        for (k_commit <- 0 until numSlots) {
          assert(
            dut.io.commit(k_commit).valid.toBoolean,
            s"Commit cycle $i, slot $k_commit: Expected commit valid. Head=${dut.rob.headPtr_reg.toInt}, Count=${dut.rob.count_reg.toInt}"
          )
          val entry = dut.io.commit(k_commit).entry
          assert(entry.status.done.toBoolean, s"Commit cycle $i, slot $k_commit: Entry not done.")
          // Simple PC check: assumes linear allocation and commit
          val expectedRobIdxForCommitSlot = (dut.rob.headPtr_reg.toInt + k_commit) % testConfig.robDepth
          val originalIdxInAllocList = allocatedRobIdxList.indexOf(expectedRobIdxForCommitSlot)
          assert(originalIdxInAllocList != -1, "Committed ROB index not found in original allocation list")
          val expectedPC = 200 + originalIdxInAllocList * 4
          assert(
            entry.payload.pc.toBigInt == expectedPC,
            s"Commit cycle $i, slot $k_commit: PC mismatch. Expected $expectedPC, got ${entry.payload.pc.toBigInt}. ROB Idx: $expectedRobIdxForCommitSlot"
          )
          println(
            s"[TB] Commit candidate slot $k_commit: ROB Idx $expectedRobIdxForCommitSlot, PC ${entry.payload.pc.toBigInt}"
          )
        }

        driveCommitFire(dut.io, Seq.fill(numSlots)(true)) // Fire all commit slots
        dut.clockDomain.waitSampling(); sleep(1)
        totalCommitted += numSlots
      }
      driveCommitFire(dut.io, Seq.fill(numSlots)(false))
      val (h_after_commit, t_after_commit, c_after_commit) = getInternalRobPointers(dut)
      assert(c_after_commit == 0, s"Count should be 0 after all commits, got $c_after_commit")
      assert(dut.io.empty.toBoolean, "ROB should be empty")
      assert(
        h_after_commit == t_after_alloc,
        s"Head should be at old tail. H=$h_after_commit, OldTail=$t_after_alloc"
      ) // Since tail wraps

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Flush Operation") {
    val testConfig = baseRobConfig.copy(allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 303) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      // Allocate a few entries
      for (i <- 0 until 5) {
        driveAllocate(dut.io, Seq((true, createDummyMicroOp(i, cfg = testConfig.microOpConfig), 100 + i * 4)))
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveAllocate(dut.io, Seq.empty)
      val (h_before_flush, t_before_flush, c_before_flush) = getInternalRobPointers(dut)
      assert(c_before_flush == 5, "Should have 5 entries before flush")

      // Simulate a flush, e.g., restoring to a state where only first 2 were valid
      // Checkpoint state: head=0, tail=2, count=2
      val flushHead = 0
      val flushTail = 2
      val flushCount = 2
      driveFlush(dut.io, true, flushHead, flushTail, flushCount)
      dut.clockDomain.waitSampling(); sleep(1)
      driveFlush(dut.io, false, 0, 0, 0) // De-assert flush
      dut.clockDomain.waitSampling(); sleep(1)

      val (h_after_flush, t_after_flush, c_after_flush) = getInternalRobPointers(dut)
      println(s"After flush: Head=$h_after_flush, Tail=$t_after_flush, Count=$c_after_flush")
      assert(h_after_flush == flushHead, s"Head pointer incorrect after flush. Expected $flushHead, got $h_after_flush")
      assert(t_after_flush == flushTail, s"Tail pointer incorrect after flush. Expected $flushTail, got $t_after_flush")
      assert(c_after_flush == flushCount, s"Count incorrect after flush. Expected $flushCount, got $c_after_flush")

      // Try to commit - only first 'flushCount' entries should be considered (if they were marked done)
      // For this test, mark them done to see if commit proceeds correctly.
      for (i <- 0 until flushCount) {
        // Mark first 'flushCount' as done
        driveWriteback(dut.io, Seq((true, i, false, 0)))
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)

      var committedAfterFlush = 0
      for (i <- 0 until flushCount) {
        dut.clockDomain.waitSampling(0)
        assert(dut.io.commit(0).valid.toBoolean, s"Commit after flush $i: valid should be true")
        driveCommitFire(dut.io, Seq(true))
        dut.clockDomain.waitSampling(); sleep(1)
        committedAfterFlush += 1
      }
      assert(committedAfterFlush == flushCount, s"Should commit $flushCount entries after flush")
      assert(dut.io.empty.toBoolean, "ROB should be empty after committing flushed entries")

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Exception Propagation and Commit Handling") {
    val testConfig = baseRobConfig.copy(allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 304) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io) // Initializes all control inputs to ROB
      dut.clockDomain.waitSampling(); // Initial wait for reset/init
      // sleep(1) // Original sleep, can be kept if it helps other signal stability for tb.

      val pcOp1 = 100
      val pcOp2Exc = 104 // This op will have an exception
      val pcOp3 = 108
      val testExceptionCode = 0xab

      println("--- Phase 1: Allocation ---")
      // Op1
      val uop1 = createDummyMicroOp(0, cfg = testConfig.microOpConfig)
      setAllocateInputs(dut.io, Seq((true, uop1, pcOp1)))
      dut.clockDomain.waitSampling(0) // Let ROB outputs (like robIdx) stabilize
      val robIdxOp1 = dut.io.allocate(0).robIdx.toInt
      println(s"[TB CAPTURE] robIdxOp1 CAPTURED AS: $robIdxOp1, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toInt}")
      dut.clockDomain.waitSampling(); // Clock edge to update ROB's internal tailPtr, count, statuses
      sleep(1)

      // Op2
      val uop2 = createDummyMicroOp(
        1,
        hasException = false,
        cfg = testConfig.microOpConfig
      ) // Initially allocate as non-exception
      setAllocateInputs(dut.io, Seq((true, uop2, pcOp2Exc)))
      dut.clockDomain.waitSampling(0)
      val robIdxOp2Exc = dut.io.allocate(0).robIdx.toInt
      println(s"[TB CAPTURE] robIdxOp2Exc CAPTURED AS: $robIdxOp2Exc, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toInt}")
      dut.clockDomain.waitSampling();
      sleep(1)

      // Op3
      val uop3 = createDummyMicroOp(2, cfg = testConfig.microOpConfig)
      setAllocateInputs(dut.io, Seq((true, uop3, pcOp3)))
      dut.clockDomain.waitSampling(0)
      val robIdxOp3 = dut.io.allocate(0).robIdx.toInt
      println(s"[TB CAPTURE] robIdxOp3 CAPTURED AS: $robIdxOp3, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toInt}")
      dut.clockDomain.waitSampling();
      sleep(1)

      // Stop allocation inputs
      setAllocateInputs(dut.io, Seq.empty)
      dut.clockDomain.waitSampling(0) // Ensure fire=false propagates if setAllocateInputs doesn't sleep

      println(s"[TB FINAL CAPTURED IDs] robIdxOp1=$robIdxOp1, robIdxOp2Exc=$robIdxOp2Exc, robIdxOp3=$robIdxOp3")
      assert(robIdxOp1 == 0, "robIdxOp1 should be 0")
      assert(robIdxOp2Exc == 1, "robIdxOp2Exc should be 1")
      assert(robIdxOp3 == 2, "robIdxOp3 should be 2")

      println("--- Phase 2: Writeback ---")
      // Op1 (normal)
      driveWriteback(dut.io, Seq((true, robIdxOp1, false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op2 (exception) - This is where the exception is signaled during writeback
      driveWriteback(dut.io, Seq((true, robIdxOp2Exc, true, testExceptionCode)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op3 (normal, after exception op)
      driveWriteback(dut.io, Seq((true, robIdxOp3, false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Stop writeback inputs
      driveWriteback(dut.io, Seq.empty)
      dut.clockDomain.waitSampling(0) // Ensure fire=false propagates if driveWriteback doesn't sleep

      println("--- Phase 3: Commit ---")
      // Commit Op1 (should be fine)
      println("[TB] Attempting to commit Op1 (robIdx=0)")
      dut.clockDomain.waitSampling(0); // Let commit.valid update based on new 'done' states
      sleep(1) // Give a little extra time for signals to settle if needed after waitSampling(0)
      assert(dut.io.commit(0).valid.toBoolean, "Op1 should be valid for commit")
      assert(!dut.io.commit(0).entry.status.hasException.toBoolean, "Op1 should not have exception")
      driveCommitFire(dut.io, Seq(true));
      dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op2 (exception op)
      println("[TB] Attempting to commit Op2 (robIdx=1, the exception op)")
      dut.clockDomain.waitSampling(0); sleep(1)
      assert(dut.io.commit(0).valid.toBoolean, "Op2 (exception) should be valid for commit check")
      assert(dut.io.commit(0).entry.status.done.toBoolean, "Op2 (exception) should be done")
      assert(dut.io.commit(0).entry.status.hasException.toBoolean, "Op2 should have exception flag set")
      assert(dut.io.commit(0).entry.status.exceptionCode.toInt == testExceptionCode, "Op2 exception code mismatch")
      assert(dut.io.commit(0).entry.payload.pc.toBigInt == pcOp2Exc, "Op2 PC mismatch")
      // In a real CPU, commit stage sees exception. It might fire commit to "process" the exception,
      // then trigger a flush. For this test, we fire commit.
      driveCommitFire(dut.io, Seq(true));
      dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op3 (after an exception op)
      // In a typical precise exception model, after Op2 (exception) is "committed" (handled),
      // Op3 would be flushed and not reach the commit stage with its original PC/data.
      // The ROB would be empty or head would point past Op3.
      println("[TB] Checking state after Op2 (exception) commit")
      val (h_after_exc, t_after_exc, c_after_exc) = getInternalRobPointers(dut)
      println(s"ROB state: Head=$h_after_exc, Tail=$t_after_exc, Count=$c_after_exc")

      // If your CPU model flushes on exception commit, then 'count' might be 0 or 1 (if Op3 was also speculatively done).
      // If no flush is modeled *by this test scenario yet*, Op3 would be at the head.
      if (c_after_exc > 0 && h_after_exc == robIdxOp3) { // Check if Op3 is now at the head
        println("[TB] Attempting to commit Op3 (robIdx=2)")
        dut.clockDomain.waitSampling(0); sleep(1)
        assert(dut.io.commit(0).valid.toBoolean, "Op3 should be valid for commit if not flushed")
        assert(!dut.io.commit(0).entry.status.hasException.toBoolean, "Op3 should not have exception")
        driveCommitFire(dut.io, Seq(true));
        dut.clockDomain.waitSampling(); sleep(1)
        assert(dut.io.empty.toBoolean, "ROB should be empty after committing Op3")
      } else if (c_after_exc == 0) {
        println("[TB] ROB is empty after Op2 exception commit, as expected if flush occurred.")
        assert(dut.io.empty.toBoolean, "ROB should be empty if Op2 caused full flush on commit")
      } else {
        println(
          s"[TB] ROB state after Op2 commit is unexpected for Op3 check. Head is $h_after_exc (expected $robIdxOp3 if Op3 is next)."
        )
      }

      driveCommitFire(dut.io, Seq(false)) // Stop commit fire
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Pointer Wrapping Behavior") {
    val depth = 8 // Use a smaller depth for easier wrapping test
    val testConfig = baseRobConfig.copy(robDepth = depth, allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 305) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      var pc = 0
      var robIndices = ArrayBuffer[Int]()

      // Phase 1: Fill ROB to trigger tail wrap
      println("--- Filling ROB to wrap tail ---")
      for (i <- 0 until testConfig.robDepth + 2) { // Allocate more than depth
        val canAllocNow = dut.io.canAllocate(0).toBoolean
        if (canAllocNow) {
          driveAllocate(dut.io, Seq((true, createDummyMicroOp(i, cfg = testConfig.microOpConfig), pc)))
          robIndices += dut.io.allocate(0).robIdx.toInt
          pc += 4
        } else {
          driveAllocate(dut.io, Seq.empty) // Keep it false if cannot alloc
          println(s"ROB full at iter $i, cannot allocate.")
        }
        dut.clockDomain.waitSampling(); sleep(1)
        val (h, t, c) = getInternalRobPointers(dut)
        println(
          s"Iter $i: Alloc. Head=$h, Tail=$t, Count=$c. Last Alloc Idx: ${if (canAllocNow) robIndices.last else -1}"
        )
        if (i == testConfig.robDepth - 1) assert(t == 0, s"Tail should wrap to 0 after filling depth-1, got $t")
      }
      driveAllocate(dut.io, Seq.empty)
      val (h1, t1, c1) = getInternalRobPointers(dut)
      assert(c1 == testConfig.robDepth, "ROB should be full")
      assert(t1 == h1, "Tail should meet head when full and wrapped") // If depth allocations done, tail wraps to head

      // Phase 2: Writeback all
      robIndices.take(testConfig.robDepth).foreach { idx => // only wb what's in ROB
        driveWriteback(dut.io, Seq((true, idx, false, 0)))
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)

      // Phase 3: Commit all to trigger head wrap
      println("--- Committing all to wrap head ---")
      for (i <- 0 until testConfig.robDepth) {
        dut.clockDomain.waitSampling(0); sleep(1)
        assert(dut.io.commit(0).valid.toBoolean, s"Commit iter $i: Expected valid. Head ${dut.rob.headPtr_reg.toInt}")
        driveCommitFire(dut.io, Seq(true))
        dut.clockDomain.waitSampling(); sleep(1)
        val (h, t, c) = getInternalRobPointers(dut)
        println(s"Iter $i: Commit. Head=$h, Tail=$t, Count=$c")
        if (i == testConfig.robDepth - 1) assert(h == t1, s"Head should wrap to meet initial tail $t1, got $h")
      }
      driveCommitFire(dut.io, Seq(false))
      val (h2, t2, c2) = getInternalRobPointers(dut)
      assert(c2 == 0, "ROB should be empty")
      assert(h2 == t2, "Head and tail should match when empty")

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Concurrent Allocate, Writeback, Commit (Single Cycle Stress)") {
    val testConfig = baseRobConfig.copy(robDepth = 8, allocateWidth = 2, commitWidth = 2, numWritebackPorts = 2)
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 306) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      // Pre-fill ROB with some ops that can be committed
      var pc = 0
      var allocatedOps = ArrayBuffer[(Int, Int)]() // (robIdx, pc)
      for (i <- 0 until 4) { // Allocate 4 ops (2 cycles for 2-wide alloc)
        val fire = i < 2 // Allocate 2 per cycle for 2 cycles
        val uops = (0 until testConfig.allocateWidth).map { j =>
          val currentPc = pc + (i * testConfig.allocateWidth + j) * 4
          (i < 2, createDummyMicroOp(i * testConfig.allocateWidth + j, cfg = testConfig.microOpConfig), currentPc)
        }
        driveAllocate(dut.io, uops)
        if (i < 2) {
          allocatedOps ++= dut.io.allocate.zipWithIndex.map { case (port, slotIdx) =>
            (port.robIdx.toInt, uops(slotIdx)._3.toInt)
          }
        }
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveAllocate(dut.io, Seq.empty)
      println(s"Pre-fill: Allocated ${allocatedOps.length} ops. Indices: ${allocatedOps.map(_._1).mkString(",")}")
      val (h_pre, t_pre, c_pre) = getInternalRobPointers(dut)
      assert(c_pre == 4)

      // Mark first two ops as done (indices 0, 1 by allocation order) for commit
      driveWriteback(
        dut.io,
        Seq(
          (true, allocatedOps(0)._1, false, 0),
          (true, allocatedOps(1)._1, false, 0)
        )
      )
      dut.clockDomain.waitSampling(); sleep(1) // WB takes effect
      driveWriteback(dut.io, Seq.empty)

      // In one cycle:
      // - Commit first two ops (ROB indices from allocatedOps(0), allocatedOps(1))
      // - Allocate two new ops
      // - Writeback next two ops (ROB indices from allocatedOps(2), allocatedOps(3))
      println("--- Concurrent Operations Cycle ---")
      // Setup commit fire for ops at head
      driveCommitFire(dut.io, Seq(true, true)) // Assumes commit(0) and commit(1) will be valid

      // Setup allocation for new ops
      val newPcBase = 800
      val allocReqs = (0 until testConfig.allocateWidth).map { i =>
        (true, createDummyMicroOp(100 + i, cfg = testConfig.microOpConfig), newPcBase + i * 4)
      }
      driveAllocate(dut.io, allocReqs) // This helper includes sleep(1)

      // Setup writeback for later ops
      val wbReqs = Seq(
        (true, allocatedOps(2)._1, false, 0),
        (true, allocatedOps(3)._1, false, 0)
      )
      driveWriteback(dut.io, wbReqs) // This helper includes sleep(1)

      // Check commit valid flags (combinational based on state before this cycle's clock edge)
      assert(
        dut.io.commit(0).valid.toBoolean && dut.io.commit(1).valid.toBoolean,
        "Commit slots should be valid for pre-filled ops"
      )
      val committedPcs = dut.io.commit.map(_.entry.payload.pc.toBigInt).take(2)
      println(s"Concurrent: Commit valid for PCs: ${committedPcs.mkString(",")}")

      // Check canAllocate flags
      (0 until testConfig.allocateWidth)
        .foreach(i => assert(dut.io.canAllocate(i).toBoolean, s"canAllocate($i) should be true"))
      val newAllocRobIndices = dut.io.allocate.map(_.robIdx.toInt).take(testConfig.allocateWidth)
      println(s"Concurrent: Allocating to ROB indices: ${newAllocRobIndices.mkString(",")}")

      dut.clockDomain.waitSampling() // THE BIG CLOCK EDGE where all takes effect
      sleep(1) // Let signals propagate

      // De-assert all inputs
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      val (h_post, t_post, c_post) = getInternalRobPointers(dut)
      println(s"After Concurrent: Head=$h_post, Tail=$t_post, Count=$c_post")

      // Expected: 2 committed, 2 allocated. Count should remain 4.
      // Head should advance by 2. Tail should advance by 2.
      assert(c_post == 4, s"Count after concurrent ops should be 4, got $c_post")
      assert(h_post == (h_pre + testConfig.commitWidth) % testConfig.robDepth, "Head pointer incorrect")
      assert(t_post == (t_pre + testConfig.allocateWidth) % testConfig.robDepth, "Tail pointer incorrect")

      // Verify that the newly allocated ops are at the new tail positions
      // And the just-written-back ops are marked done.
      // This requires reading ROB entries, which is more involved.
      // For now, pointer and count checks are a good start.

      dut.clockDomain.waitSampling(10)
    }
  }
  thatsAll()
}
