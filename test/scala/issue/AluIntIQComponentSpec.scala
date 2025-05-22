// testOnly parallax.test.issue.AluIntIQComponentSpec
package parallax.test.issue

import parallax.common._ // Import common definitions including BypassMessage and PipelineConfig
import parallax.components.issue._ // AluIntIQComponent, AluIntIQComponentIo, IQEntryAluInt
import parallax.utilities.ParallaxLogger

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable
import scala.util.Random

// Testbench component that wraps the AluIntIQComponent
class AluIntIQTestBench(val pCfg: PipelineConfig, val iqDepth: Int, val iqId: Int = 0) extends Component {
  val io = new Bundle {
    val allocateIn = slave Flow (IQEntryAluInt(pCfg))
    val canAccept = out Bool ()
    val issueOut = master Stream (IQEntryAluInt(pCfg))
    val bypassIn = in(Vec(Flow(BypassMessage(pCfg)), pCfg.bypassNetworkSources))
    val flush = in Bool ()
  }

  val iq = new AluIntIQComponent(config = pCfg, depth = iqDepth, id = iqId)

  iq.io.allocateIn <> io.allocateIn
  io.canAccept <> iq.io.canAccept
  io.issueOut <> iq.io.issueOut
  iq.io.bypassIn <> io.bypassIn
  iq.io.flush <> io.flush
  val internalValidCount = iq.currentValidCount
  internalValidCount.simPublic()
  io.simPublic()
}

class AluIntIQComponentSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val IQ_DEPTH = 4
  val NUM_BYPASS_SOURCES = 2 // This should match pCfg.bypassNetworkSources

  // Default configs for testing
  def defaultPipelineConfig = PipelineConfig(
    xlen = XLEN,
    physGprCount = 32 + 16,
    archGprCount = 32,
    // robIdxWidth is derived, remove
    bypassNetworkSources = NUM_BYPASS_SOURCES, // Ensure this is correctly set
    // dataWidth is derived, remove
    // pcWidth is derived, remove
    // archRegIdxWidth is derived, remove
    // physGprIdxWidth is derived, remove
    uopUniqueIdWidth = 8 bits, // This is a direct constructor param
    exceptionCodeWidth = 8 bits, // This is a direct constructor param
    fetchWidth = 2, // This is a direct constructor param
    dispatchWidth = 2, // This is a direct constructor param
    commitWidth = 2, // This is a direct constructor param
    robDepth = 64 // robIdxWidth depends on this, so robDepth should be a param
  )

  // Helper to drive IQEntryAluInt for allocation
  def driveIQEntry(
      entryTarget: IQEntryAluInt,
      robIdx: Int,
      physDestIdx: Int = 1,
      writesPhys: Boolean = true,
      useSrc1: Boolean = false,
      src1Tag: Int = 0,
      src1InitialReady: Boolean = false,
      src1Data: BigInt = 0,
      useSrc2: Boolean = false,
      src2Tag: Int = 0,
      src2InitialReady: Boolean = false,
      src2Data: BigInt = 0
  ): Unit = {
    ParallaxLogger.log(
      s"Driving entry with ROBIdx=${robIdx.toHexString}H"
    )
    entryTarget.valid #= true // This valid is for the Flow payload, IQ will manage its internal valid state
    entryTarget.robIdx #= robIdx
    entryTarget.physDest.idx #= physDestIdx
    entryTarget.writesToPhysReg #= writesPhys
    entryTarget.physDestIsFpr #= false

    entryTarget.useSrc1 #= useSrc1
    if (useSrc1) {
      entryTarget.src1Tag #= src1Tag
      entryTarget.src1Ready #= src1InitialReady
      entryTarget.src1Data #= src1Data
      entryTarget.src1IsFpr #= false
    } else {
      entryTarget.src1Ready #= true // Unused sources are considered "ready" by default for IQ logic
    }

    entryTarget.useSrc2 #= useSrc2
    if (useSrc2) {
      entryTarget.src2Tag #= src2Tag
      entryTarget.src2Ready #= src2InitialReady
      entryTarget.src2Data #= src2Data
      entryTarget.src2IsFpr #= false
    } else {
      entryTarget.src2Ready #= true
    }
    // aluCtrl and shiftCtrl will use their defaults from IQEntryAluInt.setDefault()
    // which is called by initFrom() if that's how the payload is constructed before being sent.
    // Here, we are directly driving the payload, so defaults apply unless overridden.
    entryTarget.isReadyToIssue #= false // IQ component will calculate this based on src readiness
  }

  // Helper to drive a BypassMessage
  def driveBypassMessage(
      bypassPort: Flow[BypassMessage], // Target specific bypass port
      valid: Boolean,
      pRegIdx: Int,
      data: BigInt,
      robIdxProducer: Int = 0,
      isExcp: Boolean = false
  ): Unit = {
    bypassPort.valid #= valid
    if (valid) {
      bypassPort.payload.physRegIdx #= pRegIdx
      bypassPort.payload.physRegData #= data
      bypassPort.payload.physRegDataValid #= true // Data in bypass message is considered valid
      bypassPort.payload.robIdx #= robIdxProducer
      bypassPort.payload.isFPR #= false // Assuming GPR for these tests
      bypassPort.payload.hasException #= isExcp
      bypassPort.payload.exceptionCode #= 0 // Default, change if testing exception propagation
    }
  }

  test(s"AluIntIQ_Depth${IQ_DEPTH}_BasicAllocAndIssue_WithTestBench") {
    val pCfg = defaultPipelineConfig
    var cycle = 0

    SimConfig.withWave.compile(new AluIntIQTestBench(pCfg, IQ_DEPTH)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.onActiveEdges({
        ParallaxLogger.log(s"Cycle ${cycle}")
        cycle += 1
      })
      dut.io.flush #= false
      dut.io.issueOut.ready #= false // Initially downstream not ready

      val allocatedEntriesRobIndices = mutable.Queue[Int]() // Store ROB indices of allocated entries
      val issuedEntriesMonitorRobIndices = mutable.Queue[Int]()

      StreamMonitor(dut.io.issueOut, dut.clockDomain) { payload =>
        issuedEntriesMonitorRobIndices.enqueue(payload.robIdx.toInt)
        ParallaxLogger.log(
          s"SIM MONITOR: Issued ROBIdx=${payload.robIdx.toInt.toHexString}H, Src1Rdy=${payload.src1Ready.toBoolean}, Src2Rdy=${payload.src2Ready.toBoolean}"
        )
      }

      // Initialize all bypass ports to not valid
      dut.io.bypassIn.foreach { bp =>
        bp.valid #= false
        bp.payload.assignDontCare() // Or set to default values
      }
      dut.clockDomain.waitSampling()

      // --- Test 1: Allocate an entry that is immediately ready (no sources) ---
      ParallaxLogger.log("SIM TEST 1: Allocate immediately ready uop (robIdx 10)")
      dut.io.allocateIn.valid #= true
      driveIQEntry(dut.io.allocateIn.payload, robIdx = 10, useSrc1 = false, useSrc2 = false)
      allocatedEntriesRobIndices.enqueue(10)
      dut.clockDomain.waitSampling()
      dut.io.allocateIn.valid #= false // Deassert after one cycle
      sleep(0)
      assert(dut.io.canAccept.toBoolean, "Test 1: IQ should still be able to accept after 1 alloc")
      assert(dut.internalValidCount.toInt == 1, "Test 1: internalValidCount should be 1 after one allocation")

      dut.io.issueOut.ready #= true // Now make downstream ready
      val timeout1 = dut.clockDomain.waitSamplingWhere(20)(issuedEntriesMonitorRobIndices.nonEmpty)
      assert(!timeout1, "Test 1: Timeout waiting for entry 10 to issue")
      assert(issuedEntriesMonitorRobIndices.nonEmpty, "Test 1: Entry 10 (no_src) did not issue despite condition met")
      assert(issuedEntriesMonitorRobIndices.dequeue() == 10 && allocatedEntriesRobIndices.dequeue() == 10, "Test 1: Issued ROBIdx mismatch for entry 10")
      ParallaxLogger.log("SIM TEST 1: Entry 10 issued.")
      dut.clockDomain.waitSampling() // Allow internal state (like valid count) to update after issue
      assert(dut.internalValidCount.toInt == 0, "Test 1: internalValidCount should be 0 after issue")

      // --- Test 2: Allocate an entry waiting for one source, then bypass ---
      ParallaxLogger.log("SIM TEST 2: Allocate uop (robIdx 20) waiting for src1 (pTag 5)")
      dut.io.issueOut.ready #= false // Prevent immediate issue if it became ready by chance
      dut.io.allocateIn.valid #= true
      driveIQEntry(dut.io.allocateIn.payload, robIdx = 20, useSrc1 = true, src1Tag = 5, src1InitialReady = false)
      allocatedEntriesRobIndices.enqueue(20)
      dut.clockDomain.waitSampling()
      dut.io.allocateIn.valid #= false
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      sleep(0)
      assert(
        issuedEntriesMonitorRobIndices.isEmpty,
        "Test 2: Entry 20 should not issue before bypass. "
      )
      assert(dut.internalValidCount.toInt == 1, "Test 2: internalValidCount should be 1 (entry 20 allocated)")

      ParallaxLogger.log("SIM TEST 2: Bypassing for pTag 5 on bypassIn(0)")
      driveBypassMessage(dut.io.bypassIn(0), valid = true, pRegIdx = 5, data = 123, robIdxProducer = 1)
      // Keep other bypass ports invalid
      if (NUM_BYPASS_SOURCES > 1) driveBypassMessage(dut.io.bypassIn(1), valid = false, pRegIdx = 0, data = 0)

      dut.clockDomain.waitSampling()
      // Deassert bypass signal after one cycle
      driveBypassMessage(dut.io.bypassIn(0), valid = false, pRegIdx = 0, data = 0)

      dut.io.issueOut.ready #= true
      val timeout2 = dut.clockDomain.waitSamplingWhere(20)(issuedEntriesMonitorRobIndices.nonEmpty)
      assert(!timeout2, "Test 2: Timeout waiting for entry 20 to issue after bypass")
      assert(
        issuedEntriesMonitorRobIndices.nonEmpty,
        "Test 2: Entry 20 (src1_wait) did not issue after bypass despite condition met"
      )
      assert(issuedEntriesMonitorRobIndices.dequeue() == 20 && allocatedEntriesRobIndices.dequeue() == 20, "Test 2: Issued ROBIdx mismatch for entry 20")
      ParallaxLogger.log("SIM TEST 2: Entry 20 issued.")
      dut.clockDomain.waitSampling()
      assert(dut.internalValidCount.toInt == 0, "Test 2: internalValidCount should be 0 after issue")
      assert(allocatedEntriesRobIndices.isEmpty, "Test 2: All entries should be dequeued after issue")
      // --- Test 3: Fill the IQ, check canAccept, then drain ---
      ParallaxLogger.log(s"SIM TEST 3: Fill IQ to depth ${IQ_DEPTH}")
      dut.io.issueOut.ready #= false // Stop issues to fill up
      for (i <- 0 until IQ_DEPTH) { // Fill all slots
        assert(
          dut.io.canAccept.toBoolean,
          s"Test 3: IQ should accept before full, iter $i, current count ${dut.internalValidCount.toInt}"
        )
        dut.io.allocateIn.valid #= true
        val robId = 30 + i
        driveIQEntry(
          dut.io.allocateIn.payload,
          robIdx = robId,
          useSrc1 = false,
          useSrc2 = false
        ) // Immediately ready entries
        allocatedEntriesRobIndices.enqueue(robId)
        dut.clockDomain.waitSampling()
      }
      dut.io.allocateIn.valid #= false
      sleep(0)
      dut.clockDomain.waitSampling() // Allow count to update if it's registered based on `allocateIn.fire`
      assert(!dut.io.canAccept.toBoolean, "Test 3: IQ should be full now")
      ParallaxLogger.log(s"SIM TEST 3: IQ is full. Valid count: ${dut.internalValidCount.toInt}")
      assert(dut.internalValidCount.toInt == IQ_DEPTH, s"Test 3: internalValidCount should be ${IQ_DEPTH} when full")

      ParallaxLogger.log("SIM TEST 3: Draining IQ")
      dut.io.issueOut.ready #= true
      for (k <- 0 until IQ_DEPTH) {
        ParallaxLogger.log(s"SIM TEST 3: Draining IQ - iter $k")
        val timeout3 = dut.clockDomain.waitSamplingWhere(20)(issuedEntriesMonitorRobIndices.nonEmpty)
        assert(!timeout3, s"Test 3: Timeout waiting for IQ to issue during drain (iter $k)")
        assert(
          issuedEntriesMonitorRobIndices.nonEmpty,
          s"Test 3: IQ did not issue during drain (iter $k) despite condition met"
        )
        val expectedRobId = allocatedEntriesRobIndices.dequeue()
        val actualRobId = issuedEntriesMonitorRobIndices.dequeue()
        assert(
          actualRobId == expectedRobId,
          s"Test 3: IQ drain order mismatch: expected $expectedRobId (${expectedRobId.toHexString}), got $actualRobId (${actualRobId.toHexString})"
        )
      }
      dut.clockDomain.waitSampling()
      assert(dut.io.canAccept.toBoolean, "Test 3: IQ should be able to accept after draining")
      assert(dut.internalValidCount.toInt == 0, "Test 3: IQ should be empty after draining")
      ParallaxLogger.log("SIM TEST 3: IQ drained.")
      assert(allocatedEntriesRobIndices.isEmpty, "Test 3: All entries should be dequeued after draining")
      assert(issuedEntriesMonitorRobIndices.isEmpty, "Test 3: All entries should be dequeued after draining")
      // --- Test 4: Flush ---
      ParallaxLogger.log("SIM TEST 4: Flush test")
      // Allocate two entries
      dut.io.allocateIn.valid #= true
      driveIQEntry(dut.io.allocateIn.payload, robIdx = 40, useSrc1 = false, useSrc2 = false)
      dut.clockDomain.waitSampling() // Entry 40 allocated

      dut.io.allocateIn.valid #= true
      driveIQEntry(dut.io.allocateIn.payload, robIdx = 41, useSrc1 = true, src1Tag = 7)
      dut.clockDomain.waitSampling() // Entry 41 allocated
      dut.io.allocateIn.valid #= false
      sleep(0)
      dut.clockDomain.waitSampling() // Allow count to update
      ParallaxLogger.log(s"SIM TEST 4: Before flush, valid count: ${dut.internalValidCount.toInt}")
      assert(dut.internalValidCount.toInt == 2, "Test 4: IQ should have 2 entries before flush")
      assert(issuedEntriesMonitorRobIndices.dequeue() == 40, "Test 4: Entry 40 should be issue before flush")
      assert(issuedEntriesMonitorRobIndices.isEmpty, "Test 4: Entry 41 should not issue before flush")

      dut.io.flush #= true
      dut.clockDomain.waitSampling()
      dut.io.flush #= false // Deassert flush
      dut.clockDomain.waitSampling() // Allow count to update
      ParallaxLogger.log(s"SIM TEST 4: After flush, valid count: ${dut.internalValidCount.toInt}")
      assert(dut.internalValidCount.toInt == 0, "Test 4: IQ should be empty after flush")
      assert(dut.io.canAccept.toBoolean, "Test 4: IQ should accept after flush")
      assert(issuedEntriesMonitorRobIndices.isEmpty, "Test 4: No entries should issue after flush until new allocs")

      dut.clockDomain.waitSampling(5)
      ParallaxLogger.log("SIM TEST: All tests completed.")
    }
  }

  thatsAll()
}
