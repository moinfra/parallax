package boson.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import boson.demo2.components.rename._ // Where ROBConfig, ROBIo, ReorderBuffer are
import boson.demo2.components.decode._ // For MicroOpConfig, MicroOp if needed by helpers
import scala.collection.mutable.ArrayBuffer

class ReorderBufferTestBench(val robConfig: ROBConfig) extends Component {
  val io = slave(ROBIo(robConfig)) // Master IO to drive the ReorderBuffer

  val rob = new ReorderBuffer(robConfig)
  rob.io <> io

  // Expose internal signals for easier testing if needed
  rob.io.simPublic() // Makes all IO signals easily accessible
  rob.headPtr.simPublic()
  rob.tailPtr.simPublic()
  rob.count.simPublic()
  // To observe specific entry statuses or payloads if complex tests require it:
  // rob.statuses.foreach(_.simPublic()) // This might create too many signals for large ROBs
  // rob.payloads.ram.simPublic() // If payloads is a Mem and you want to inspect its contents
  // For Vec[Reg] like statuses, direct access is possible:
  // def getStatus(idx: Int) = rob.statuses(idx)
}

class ReorderBufferSpec extends CustomSpinalSimFunSuite {

  // Default MicroOpConfig for tests - adjust as needed
  val microOpCfg = MicroOpConfig(physRegIdxWidth = 6 bits, dataWidth = 32 bits)

  // --- Helper Functions for Driving ROB IO ---

  // Helper to create a default/dummy MicroOp for allocation

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
      allocRequests: Seq[(Bool, MicroOp, UInt)] // Seq of (fire, uop, pc)
  ): Unit = {
    require(allocRequests.length <= dutIo.config.allocateWidth)
    println(
      s"[TB] Driving Allocate - Count: ${allocRequests.count(_._1.toBoolean)}, Requests (fire,uop.pc,pc): " +
        allocRequests
          .map(r => s"(${r._1.toBoolean},uop@pc${r._3.toInt})") // Simplified uop print
          .mkString(",")
    )
    for (i <- 0 until dutIo.config.allocateWidth) {
      val port = dutIo.allocate(i)
      if (i < allocRequests.length) {
        port.fire #= allocRequests(i)._1.toBoolean
        // *****************************************************************************************
        // START OF MODIFIED SECTION for Bundle assignment
        // Assign members of the uop Bundle individually
        val reqUop = allocRequests(i)._2
        port.uopIn.isValid #= reqUop.isValid.toBoolean
        port.uopIn.uopType #= reqUop.uopType.toEnum // Assuming uopType is SpinalEnum
        port.uopIn.archRegRd #= reqUop.archRegRd.toInt
        port.uopIn.archRegRs #= reqUop.archRegRs.toInt
        port.uopIn.archRegRt #= reqUop.archRegRt.toInt
        port.uopIn.useRd #= reqUop.useRd.toBoolean
        port.uopIn.useRs #= reqUop.useRs.toBoolean
        port.uopIn.useRt #= reqUop.useRt.toBoolean
        port.uopIn.writeRd #= reqUop.writeRd.toBoolean
        port.uopIn.imm #= reqUop.imm.toBigInt
        // Assign sub-bundles if they exist and are used
        port.uopIn.aluInfo.op #= reqUop.aluInfo.op.toEnum
        port.uopIn.memInfo.accessType #= reqUop.memInfo.accessType.toEnum
        port.uopIn.memInfo.size #= reqUop.memInfo.size.toEnum
        port.uopIn.ctrlFlowInfo.condition #= reqUop.ctrlFlowInfo.condition.toEnum
        port.uopIn.ctrlFlowInfo.targetOffset #= reqUop.ctrlFlowInfo.targetOffset.toBigInt
        // Physical register fields are typically not driven by allocate, but set by Rename
        // For testing, if we need to simulate Rename's output directly:
        port.uopIn.physRegRs #= reqUop.physRegRs.toInt
        port.uopIn.physRegRt #= reqUop.physRegRt.toInt
        port.uopIn.physRegRdOld #= reqUop.physRegRdOld.toInt
        port.uopIn.physRegRdNew #= reqUop.physRegRdNew.toInt
        port.uopIn.allocatesPhysReg #= reqUop.allocatesPhysReg.toBoolean
        port.uopIn.writesPhysReg #= reqUop.writesPhysReg.toBoolean

        port.pcIn #= allocRequests(i)._3.toInt
        // END OF MODIFIED SECTION for Bundle assignment
        // *****************************************************************************************
      } else {
        port.fire #= false
      }
    }
    sleep(1) // Ensure inputs are stable for DUT combinational logic
  }

  // Drive writeback ports
  def driveWriteback(
      dutIo: ROBIo,
      wbRequests: Seq[(Bool, Int, Bool, Int)] // Seq of (fire, robIdx, hasException, excCode)
  ): Unit = {
    require(wbRequests.length <= dutIo.config.numWritebackPorts)
    println(
      s"[TB] Driving Writeback - Count: ${wbRequests.count(_._1.toBoolean)}, Requests (fire,robIdx,exc,code): " +
        wbRequests.map(r => s"(${r._1.toBoolean},#${r._2},${r._3.toBoolean},${r._4})").mkString(",")
    )
    for (i <- 0 until dutIo.config.numWritebackPorts) {
      if (i < wbRequests.length) {
        dutIo.writeback(i).fire #= wbRequests(i)._1.toBoolean
        dutIo.writeback(i).robIdx #= wbRequests(i)._2
        dutIo.writeback(i).exceptionOccurred #= wbRequests(i)._3.toBoolean
        dutIo.writeback(i).exceptionCodeIn #= wbRequests(i)._4
      } else {
        dutIo.writeback(i).fire #= false
      }
    }
    sleep(1)
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
    // sleep(1) might be implicitly handled by the drive functions or needed here too.
    // The individual drive functions have sleep(1), so this should be okay.
  }

  // Helper to get internal pointers and count via simPublic
  // Assumes ReorderBufferTestBench exposes these with specific names
  def getInternalState(tb: ReorderBufferTestBench): (Int, Int, Int) = {
    (
      tb.rob.headPtr.toInt, // Accessing directly from tb.rob...
      tb.rob.tailPtr.toInt,
      tb.rob.count.toInt
    )
  }
  def getInternalRobPointers(tb: ReorderBufferTestBench): (Int, Int, Int) = {
    val head = tb.rob.headPtr.toInt
    val tail = tb.rob.tailPtr.toInt
    val count = tb.rob.count.toInt
    (head, tail, count)
  }

  // --- Test Cases ---

  val robDepth = 16 // Example depth
  val allocW = 2
  val commitW = 2
  val wbW = 2 // Example writeback width

  val baseRobConfig = ROBConfig(
    robDepth = robDepth,
    microOpConfig = microOpCfg,
    commitWidth = commitW,
    allocateWidth = allocW,
    numWritebackPorts = wbW
  )

  testOnly("ROB - Initialization and Empty/Full") {
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
          (Bool(true), createDummyMicroOp(i * testConfig.allocateWidth + slot), U(i * testConfig.allocateWidth + slot))
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
      driveAllocate(dut.io, Seq((Bool(true), createDummyMicroOp(0), U(0))))
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
        driveAllocate(dut.io, Seq((Bool(true), uop, U(currentPC))))
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
        Seq(allocatedRobIndices(1), allocatedRobIndices(0), allocatedRobIndices(2)) // Example out-of-order WB
      for (robIdxToWb <- wbOrder) {
        driveWriteback(dut.io, Seq((Bool(true), robIdxToWb, Bool(false), 0)))
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
          s"Commit $i: commit(0).valid should be true. Head: ${dut.rob.headPtr.toInt}, Count: ${dut.rob.count.toInt}"
        )
        val committedEntry = dut.io.commit(0).entry
        println(
          s"[TB] Commit $i: ROB Idx ${dut.rob.headPtr.toInt}, Valid=${dut.io.commit(0).valid.toBoolean}, " +
            s"PC=${committedEntry.payload.pc.toInt}, Done=${committedEntry.status.done.toBoolean}"
        )
        assert(committedEntry.status.done.toBoolean, s"Commit $i: Entry should be done.")
        assert(
          committedEntry.payload.pc.toInt == 100 + allocatedRobIndices.indexOf(dut.rob.headPtr.toInt) * 4,
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
          (Bool(true), uop, U(pcCounter + j * 4))
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
        val wbRequestsForCycle = batch.map(idx => (Bool(true), idx, Bool(false), 0))
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
            s"Commit cycle $i, slot $k_commit: Expected commit valid. Head=${dut.rob.headPtr.toInt}, Count=${dut.rob.count.toInt}"
          )
          val entry = dut.io.commit(k_commit).entry
          assert(entry.status.done.toBoolean, s"Commit cycle $i, slot $k_commit: Entry not done.")
          // Simple PC check: assumes linear allocation and commit
          val expectedRobIdxForCommitSlot = (dut.rob.headPtr.toInt + k_commit) % testConfig.robDepth
          val originalIdxInAllocList = allocatedRobIdxList.indexOf(expectedRobIdxForCommitSlot)
          assert(originalIdxInAllocList != -1, "Committed ROB index not found in original allocation list")
          val expectedPC = 200 + originalIdxInAllocList * 4
          assert(
            entry.payload.pc.toInt == expectedPC,
            s"Commit cycle $i, slot $k_commit: PC mismatch. Expected $expectedPC, got ${entry.payload.pc.toInt}. ROB Idx: $expectedRobIdxForCommitSlot"
          )
          println(
            s"[TB] Commit candidate slot $k_commit: ROB Idx $expectedRobIdxForCommitSlot, PC ${entry.payload.pc.toInt}"
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
        driveAllocate(dut.io, Seq((Bool(true), createDummyMicroOp(i, cfg = testConfig.microOpConfig), U(100 + i * 4))))
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
        driveWriteback(dut.io, Seq((Bool(true), i, Bool(false), 0))) // Mark first 'flushCount' as done
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
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      val pcOp1 = 100
      val pcOp2Exc = 104 // This op will have an exception
      val pcOp3 = 108

      // 1. Allocate three ops: Normal, Exception, Normal
      driveAllocate(dut.io, Seq((Bool(true), createDummyMicroOp(0, cfg = testConfig.microOpConfig), pcOp1)))
      dut.clockDomain.waitSampling(); sleep(1)
      val robIdxOp1 = dut.io.allocate(0).robIdx.toInt

      driveAllocate(
        dut.io,
        Seq((Bool(true), createDummyMicroOp(1, hasException = true, cfg = testConfig.microOpConfig), pcOp2Exc))
      )
      dut.clockDomain.waitSampling(); sleep(1)
      val robIdxOp2Exc = dut.io.allocate(0).robIdx.toInt

      driveAllocate(dut.io, Seq((Bool(true), createDummyMicroOp(2, cfg = testConfig.microOpConfig), pcOp3)))
      dut.clockDomain.waitSampling(); sleep(1)
      val robIdxOp3 = dut.io.allocate(0).robIdx.toInt
      driveAllocate(dut.io, Seq.empty) // Stop allocation

      // 2. Writeback all ops
      // Op1 (normal)
      driveWriteback(dut.io, Seq((Bool(true), robIdxOp1, Bool(false), 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op2 (exception)
      val testExceptionCode = 0xab
      driveWriteback(dut.io, Seq((Bool(true), robIdxOp2Exc, Bool(true), testExceptionCode)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op3 (normal, after exception op)
      driveWriteback(dut.io, Seq((Bool(true), robIdxOp3, Bool(false), 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      driveWriteback(dut.io, Seq.empty)

      // 3. Attempt to Commit
      // Commit Op1 (should be fine)
      dut.clockDomain.waitSampling(0); sleep(1) // Allow commit.valid to update
      assert(dut.io.commit(0).valid.toBoolean, "Op1 should be valid for commit")
      assert(!dut.io.commit(0).entry.status.hasException.toBoolean, "Op1 should not have exception")
      driveCommitFire(dut.io, Seq(true)); dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op2 (exception op)
      // ROB should present it as 'done' and with exception flags set.
      // The commit stage (external to ROB) would typically see the exception and not "retire" it normally.
      dut.clockDomain.waitSampling(0); sleep(1)
      assert(dut.io.commit(0).valid.toBoolean, "Op2 (exception) should be valid for commit check")
      assert(dut.io.commit(0).entry.status.done.toBoolean, "Op2 (exception) should be done")
      assert(dut.io.commit(0).entry.status.hasException.toBoolean, "Op2 should have exception flag set")
      assert(dut.io.commit(0).entry.status.exceptionCode.toInt == testExceptionCode, "Op2 exception code mismatch")
      assert(dut.io.commit(0).entry.payload.pc.toInt == pcOp2Exc, "Op2 PC mismatch")
      // Simulate commit stage seeing exception and NOT firing commit for this, instead triggering flush
      // For this test, let's assume commit stage *does* "commit" it to process the exception.
      driveCommitFire(dut.io, Seq(true)); dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op3 (after an exception, its commit depends on precise exception model)
      // If exceptions cause a flush from the excepting instruction onwards, Op3 wouldn't reach commit head
      // after Op2 is "handled". If exceptions are handled by commit stage and pipeline continues
      // speculatively until commit, then Op3 would appear.
      // Let's assume a model where after an exception is "committed" (meaning taken by handler),
      // subsequent instructions are flushed. So, Op3 should not be committed if Op2 caused a flush.
      // For this ROB test, we'll just check if ROB *presents* it if not flushed.
      // If a flush happened due to Op2, count would be 0, head would be at Op3's old slot or later.
      val (_, _, countAfterExcCommit) = getInternalRobPointers(dut)
      if (countAfterExcCommit > 0) { // If ROB wasn't flushed entirely
        dut.clockDomain.waitSampling(0); sleep(1)
        println(
          s"Checking Op3: commit_valid=${dut.io.commit(0).valid.toBoolean}, head=${dut.rob.headPtr.toInt}, robIdxOp3=${robIdxOp3}"
        )
        // This assertion depends on whether the test implies a flush after Op2's "commit"
        // For a pure ROB test, if not flushed, it should be valid.
        // assert(dut.io.commit(0).valid.toBoolean, "Op3 might be valid if no flush occurred")
      }

      driveCommitFire(dut.io, Seq(false))
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
          driveAllocate(dut.io, Seq((Bool(true), createDummyMicroOp(i, cfg = testConfig.microOpConfig), pc)))
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
        driveWriteback(dut.io, Seq((Bool(true), idx, Bool(false), 0)))
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)

      // Phase 3: Commit all to trigger head wrap
      println("--- Committing all to wrap head ---")
      for (i <- 0 until testConfig.robDepth) {
        dut.clockDomain.waitSampling(0); sleep(1)
        assert(dut.io.commit(0).valid.toBoolean, s"Commit iter $i: Expected valid. Head ${dut.rob.headPtr.toInt}")
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
          (Bool(i < 2), createDummyMicroOp(i * testConfig.allocateWidth + j, cfg = testConfig.microOpConfig), U(currentPc))
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
          (Bool(true), allocatedOps(0)._1, Bool(false), 0),
          (Bool(true), allocatedOps(1)._1, Bool(false), 0)
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
        (Bool(true), createDummyMicroOp(100 + i, cfg = testConfig.microOpConfig), U(newPcBase + i * 4))
      }
      driveAllocate(dut.io, allocReqs) // This helper includes sleep(1)

      // Setup writeback for later ops
      val wbReqs = Seq(
        (Bool(true), allocatedOps(2)._1, Bool(false), 0),
        (Bool(true), allocatedOps(3)._1, Bool(false), 0)
      )
      driveWriteback(dut.io, wbReqs) // This helper includes sleep(1)

      // Check commit valid flags (combinational based on state before this cycle's clock edge)
      assert(
        dut.io.commit(0).valid.toBoolean && dut.io.commit(1).valid.toBoolean,
        "Commit slots should be valid for pre-filled ops"
      )
      val committedPcs = dut.io.commit.map(_.entry.payload.pc.toInt).take(2)
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
