// testOnly parallax.test.issue.IssueQueueComponentSpec
package parallax.test.issue

import parallax.common._
import parallax.components.issue._
import parallax.utilities.ParallaxLogger

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}

import scala.collection.mutable
import scala.util.Random

// Testbench component that wraps the generic IssueQueueComponent for IntIQ
class IntIssueQueueTestBench(
    val pCfg: PipelineConfig,
    val iqDepth: Int,
    val iqId: Int = 0
) extends Component {
  val iqConfig = IssueQueueConfig[IQEntryAluInt](
    pipelineConfig = pCfg,
    depth = iqDepth,
    exeUnitType = ExeUnitType.ALU_INT,
    uopEntryType = HardType(IQEntryAluInt(pCfg)),
    usesSrc3 = false,
    name = "IntTestIQ"
  )
  val io = slave(IssueQueueComponentIo(iqConfig))

  val iq = new IssueQueueComponent(iqConfig, id = iqId)

  iq.io <> io

  val internalValidCount = iq.currentValidCount // This is a Reg
  internalValidCount.simPublic()

  // For white-box inspection, make internal state accessible
  // These are Reg(...) or Vec(Reg(...)) so their values are from the *start* of the current cycle
  val simInternalEntries = Vec(iq.entries.map(e => e.simPublic()))
  val simInternalEntryValids = Vec(iq.entryValids.map(ev => ev.simPublic()))

  io.simPublic()
}

class IssueQueueComponentSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val DEFAULT_IQ_DEPTH = 4
  val DEFAULT_NUM_BYPASS_SOURCES = 2

  def createPipelineConfig(
      iqDepth: Int = DEFAULT_IQ_DEPTH,
      bypassSources: Int = DEFAULT_NUM_BYPASS_SOURCES
  ): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    physGprCount = 32 + 16 + bypassSources, // Adjusted for unique phys regs
    archGprCount = 32,
    bypassNetworkSources = bypassSources,
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 64 + iqDepth // Adjusted for unique ROB idx
    // archRegIdxWidth, physGprIdxWidth, etc. are now correctly derived inside PipelineConfig
  )

  def initDutIO(dut: IntIssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
    dut.io.allocateIn.valid #= false
    // dut.io.allocateIn.payload.setDefault() // Payload is driven per-test
    dut.io.issueOut.ready #= false
    dut.io.bypassIn.foreach { bp =>
      bp.valid #= false
      bp.payload.setDefault()
    }
    dut.io.flush #= false
    cd.waitSampling()
  }

  // -- MODIFICATION START (Corrected driveAllocRequest) --
  def driveAllocRequest(
      allocFlowTarget: Flow[IQEntryAluInt], // Target the DUT's allocateIn Flow directly
      robIdxVal: Int,
      pCfg: PipelineConfig,
      physDestIdxVal: Int = 1,
      writesPhysVal: Boolean = true,
      destIsFprVal: Boolean = false,
      useSrc1Val: Boolean = false,
      src1TagVal: Int = 0,
      src1InitialReadyVal: Boolean = true,
      src1DataVal: BigInt = 0,
      src1IsFprVal: Boolean = false,
      useSrc2Val: Boolean = false,
      src2TagVal: Int = 0,
      src2InitialReadyVal: Boolean = true,
      src2DataVal: BigInt = 0,
      src2IsFprVal: Boolean = false
  )(implicit cd: ClockDomain): Unit = {
    allocFlowTarget.valid #= true

    allocFlowTarget.payload.robIdx #= robIdxVal
    allocFlowTarget.payload.physDest.idx #= physDestIdxVal
    allocFlowTarget.payload.writesToPhysReg #= writesPhysVal
    allocFlowTarget.payload.physDestIsFpr #= destIsFprVal

    allocFlowTarget.payload.useSrc1 #= useSrc1Val
    if (useSrc1Val) {
      allocFlowTarget.payload.src1Tag #= src1TagVal
      allocFlowTarget.payload.src1Ready #= src1InitialReadyVal
      allocFlowTarget.payload.src1Data #= src1DataVal
      allocFlowTarget.payload.src1IsFpr #= src1IsFprVal
    } else {
      allocFlowTarget.payload.src1Ready #= true // If not used, effectively ready
      allocFlowTarget.payload.src1Tag #= 0 // Default
      allocFlowTarget.payload.src1Data #= 0
      allocFlowTarget.payload.src1IsFpr #= false
    }

    allocFlowTarget.payload.useSrc2 #= useSrc2Val
    if (useSrc2Val) {
      allocFlowTarget.payload.src2Tag #= src2TagVal
      allocFlowTarget.payload.src2Ready #= src2InitialReadyVal
      allocFlowTarget.payload.src2Data #= src2DataVal
      allocFlowTarget.payload.src2IsFpr #= src2IsFprVal
    } else {
      allocFlowTarget.payload.src2Ready #= true // If not used, effectively ready
      allocFlowTarget.payload.src2Tag #= 0
      allocFlowTarget.payload.src2Data #= 0
      allocFlowTarget.payload.src2IsFpr #= false
    }

    // isReadyToIssue should be determined by the IQ logic based on the source ready states.
    // For IQEntryAluInt, it doesn't have a separate 'valid' field beyond its presence in entryValids.
    // The default state of aluCtrl/shiftCtrl is handled by IQEntryAluInt.setDefault if needed,
    // or they are driven directly if specific values are required for a test.
    // Here, we assume the default controls are fine for typical issue tests.
    allocFlowTarget.payload.aluCtrl.setDefault()
    allocFlowTarget.payload.shiftCtrl.setDefault()
  
    cd.waitSampling()
    allocFlowTarget.valid #= false
  }
  // -- MODIFICATION END --

  def driveBypassOnPort(
      bypassPort: Flow[BypassMessage],
      pRegIdx: Int,
      data: BigInt,
      isFpr: Boolean = false,
      robIdxProducer: Int = 0,
      isExcp: Boolean = false
  )(implicit cd: ClockDomain): Unit = {
    bypassPort.valid #= true
    bypassPort.payload.physRegIdx #= pRegIdx
    bypassPort.payload.physRegData #= data
    bypassPort.payload.physRegDataValid #= true
    bypassPort.payload.robIdx #= robIdxProducer
    bypassPort.payload.isFPR #= isFpr
    bypassPort.payload.hasException #= isExcp
    // bypassPort.payload.exceptionCode will be default (0) from setDefault if not driven
  }

  def deassertBypassOnPort(bypassPort: Flow[BypassMessage]): Unit = {
    bypassPort.valid #= false
    // bypassPort.payload.setDefault() // Payload values don't matter when valid is false
  }

  val testParams = Seq(
    (DEFAULT_IQ_DEPTH, DEFAULT_NUM_BYPASS_SOURCES),
    (2, 1),
    (8, 4)
  )

  for ((iqDepth, numBypass) <- testParams) {
    val pCfg = createPipelineConfig(iqDepth = iqDepth, bypassSources = numBypass)

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Basic Allocation and Issue (Immediately Ready)") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        // -- MODIFICATION START (Corrected clock domain and scoreboard usage) --
        implicit val cd = dut.clockDomain.get // Corrected
        // -- MODIFICATION END --
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload =>
          scoreboard.pushDut(payload.robIdx.toInt)
          ParallaxLogger.log(s"SIM MONITOR: Issued ROBIdx=${payload.robIdx.toInt}")
        }

        scoreboard.pushRef(10)
        driveAllocRequest(dut.io.allocateIn, robIdxVal = 10, pCfg = pCfg, useSrc1Val = false, useSrc2Val = false)

        cd.waitSampling() // Allow allocation to propagate
        assert(dut.io.canAccept.toBoolean, "IQ should still accept after 1 alloc if not full")
        // internalValidCount is a Reg, reflects state from *start* of this cycle
        // After driveAllocRequest, alloc happened in previous cycle.
        assert(dut.internalValidCount.toInt == 1, "Valid count should be 1 (checked at start of cycle after alloc)")

        dut.io.issueOut.ready #= true
        // -- MODIFICATION START (Corrected scoreboard wait condition) --
        // Wait for scoreboard to process, or a timeout
        var timeout = 20
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for basic alloc/issue to complete in scoreboard")
        // -- MODIFICATION END --

        scoreboard.checkEmptyness() // Final check
        cd.waitSampling() // Sample again for internalValidCount to reflect the issue
        assert(dut.internalValidCount.toInt == 0, "Valid count should be 0 after issue")
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Single Source Wakeup via Bypass") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robIdx.toInt) }
        StreamReadyRandomizer(dut.io.issueOut, cd)

        scoreboard.pushRef(20)
        driveAllocRequest(
          dut.io.allocateIn,
          robIdxVal = 20,
          pCfg = pCfg,
          useSrc1Val = true,
          src1TagVal = 5,
          src1InitialReadyVal = false,
          src1IsFprVal = false,
          useSrc2Val = false
        )
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 1)

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 5, data = 123, isFpr = false)
        cd.waitSampling()
        deassertBypassOnPort(dut.io.bypassIn(0))

        var timeout = 30
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for single source wakeup to complete")
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0)
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Two Sources Wakeup (Sequential Bypass)") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)
        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robIdx.toInt) }
        StreamReadyRandomizer(dut.io.issueOut, cd)

        scoreboard.pushRef(30)
        driveAllocRequest(
          dut.io.allocateIn,
          robIdxVal = 30,
          pCfg = pCfg,
          useSrc1Val = true,
          src1TagVal = 10,
          src1InitialReadyVal = false,
          src1IsFprVal = false,
          useSrc2Val = true,
          src2TagVal = 11,
          src2InitialReadyVal = false,
          src2IsFprVal = false
        )
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 1, "Count after alloc for 2-src wakeup")

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 10, data = 301, isFpr = false)
        cd.waitSampling()
        deassertBypassOnPort(dut.io.bypassIn(0))
        cd.waitSampling(2) // Wait to ensure it doesn't issue yet

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 11, data = 302, isFpr = false)
        cd.waitSampling()
        deassertBypassOnPort(dut.io.bypassIn(0))

        var timeout = 40
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for two-source sequential wakeup")
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0)
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Two Sources Wakeup (Simultaneous Bypass on different ports)") {
      if (numBypass < 2) {
        ParallaxLogger.log(s"Skipping IntIQ_${iqDepth}deep_${numBypass}bp - Simultaneous Bypass test as numBypass < 2")
      } else {
        SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
          implicit val cd = dut.clockDomain.get
          dut.clockDomain.forkStimulus(10)
          initDutIO(dut)
          val scoreboard = ScoreboardInOrder[Int]()
          StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robIdx.toInt) }
          StreamReadyRandomizer(dut.io.issueOut, cd)

          scoreboard.pushRef(35)
          driveAllocRequest(
            dut.io.allocateIn,
            robIdxVal = 35,
            pCfg = pCfg,
            useSrc1Val = true,
            src1TagVal = 15,
            src1InitialReadyVal = false,
            src1IsFprVal = false,
            useSrc2Val = true,
            src2TagVal = 16,
            src2InitialReadyVal = false,
            src2IsFprVal = false
          )
          cd.waitSampling()
          assert(dut.internalValidCount.toInt == 1, "Count after alloc for simultaneous wakeup")

          driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 15, data = 351, isFpr = false)
          driveBypassOnPort(dut.io.bypassIn(1), pRegIdx = 16, data = 352, isFpr = false) // Simultaneous
          cd.waitSampling()
          deassertBypassOnPort(dut.io.bypassIn(0))
          deassertBypassOnPort(dut.io.bypassIn(1))

          var timeout = 40
          while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
            cd.waitSampling()
            timeout -= 1
          }
          assert(timeout > 0, "Timeout waiting for two-source simultaneous wakeup")
          scoreboard.checkEmptyness()
          cd.waitSampling()
          assert(dut.internalValidCount.toInt == 0)
        }
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Fill IQ and Drain (Oldest First)") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robIdx.toInt) }

        dut.io.issueOut.ready #= false
        for (i <- 0 until iqDepth) {
          val robId = 40 + i
          scoreboard.pushRef(robId)
          driveAllocRequest(dut.io.allocateIn, robIdxVal = robId, pCfg = pCfg, useSrc1Val = false, useSrc2Val = false)
        }
        cd.waitSampling() // After all allocations
        assert(
          dut.internalValidCount.toInt == iqDepth,
          s"IQ should be full with $iqDepth entries. Actual: ${dut.internalValidCount.toInt}"
        )
        assert(!dut.io.canAccept.toBoolean, "IQ should not accept when full")

        dut.io.issueOut.ready #= true
        var timeout = iqDepth * 5 + 20
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          // ParallaxLogger.log(s"Fill/Drain Loop: DUT Q: ${scoreboard.dut.size}, REF Q: ${scoreboard.ref.size}, Timeout: $timeout")
          // ParallaxLogger.log(s"IQ Valid Count: ${dut.internalValidCount.toInt}, canAccept: ${dut.io.canAccept.toBoolean}, issueOut.valid: ${dut.io.issueOut.valid.toBoolean}")
          // for(k_idx <- 0 until iqDepth){
          // }
          cd.waitSampling()
          timeout -= 1
        }
        assert(
          timeout > 0,
          s"Timeout waiting for fill and drain. DUT Q: ${scoreboard.dut.size}, REF Q: ${scoreboard.ref.size}"
        )
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0, "IQ should be empty after draining")
        assert(dut.io.canAccept.toBoolean, "IQ should accept after draining")
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Flush Operation") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        // No scoreboard needed here as we expect no issues after flush for non-ready items.
        // And ready items might issue before flush if issueOut.ready is high.

        dut.io.issueOut.ready #= false // Prevent issues to observe flush effect clearly
        driveAllocRequest(
          dut.io.allocateIn,
          robIdxVal = 50,
          pCfg = pCfg,
          useSrc1Val = false,
          useSrc2Val = false
        ) // Ready
        var expectedCountBeforeFlush = 1
        if (iqDepth > 1) {
          driveAllocRequest(
            dut.io.allocateIn,
            robIdxVal = 51,
            pCfg = pCfg,
            useSrc1Val = true,
            src1TagVal = 20,
            src1InitialReadyVal = false // Not ready
          )
          expectedCountBeforeFlush = 2
        }
        cd.waitSampling()
        assert(
          dut.internalValidCount.toInt == expectedCountBeforeFlush,
          s"Count should be $expectedCountBeforeFlush before flush. Actual: ${dut.internalValidCount.toInt}"
        )

        dut.io.flush #= true
        cd.waitSampling() // Flush is combinational to entryValids write enable for Regs
        dut.io.flush #= false
        cd.waitSampling() // entryValids registers are cleared, internalValidCount (Reg) updates

        assert(dut.internalValidCount.toInt == 0, "IQ should be empty after flush")
        assert(dut.io.canAccept.toBoolean, "IQ should accept after flush")

        dut.io.issueOut.ready #= true // Enable issue
        cd.waitSampling(5) // Wait a few cycles
        // If StreamMonitor existed and recorded anything, it'd be an error.
        assert(dut.internalValidCount.toInt == 0, "IQ should remain empty, nothing issued post-flush")
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Back-to-Back Issue") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robIdx.toInt) }

        dut.io.issueOut.ready #= false // Prevent issue while filling
        for (i <- 0 until iqDepth) {
          val robId = 60 + i
          scoreboard.pushRef(robId)
          driveAllocRequest(dut.io.allocateIn, robIdxVal = robId, pCfg = pCfg, useSrc1Val = false, useSrc2Val = false)
        }
        cd.waitSampling()
        assert(
          dut.internalValidCount.toInt == iqDepth,
          s"IQ should be full for back-to-back test. Actual: ${dut.internalValidCount.toInt}"
        )

        dut.io.issueOut.ready #= true // Enable issue
        var timeout = iqDepth + 10
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(
          timeout > 0,
          s"Timeout waiting for back-to-back issue. DUT Q: ${scoreboard.dut.size}, REF Q: ${scoreboard.ref.size}"
        )
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0, "IQ should be empty after back-to-back draining")
      }
    }
  }
  thatsAll()
}
