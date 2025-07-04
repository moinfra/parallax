// filename: src/test/scala/parallax/issue/IssueQueueComponentSpec.scala
package test.issue

import parallax.common._
import parallax.components.issue._
import parallax.utilities.ParallaxLogger

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}

import scala.collection.mutable
import scala.util.Random
import parallax.issue.IqDispatchCmd

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

  val internalValidCount = iq.currentValidCount
  internalValidCount.simPublic()

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
    physGprCount = 32 + 16 + bypassSources,
    archGprCount = 32,
    bypassNetworkSources = bypassSources,
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 64 + iqDepth
  )

  def initDutIO(dut: IntIssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
    dut.io.allocateIn.valid #= false
    dut.io.issueOut.ready #= false
    dut.io.bypassIn.foreach { bp =>
      bp.valid #= false
      bp.payload.setDefault()
    }
    dut.io.flush #= false
    cd.waitSampling()
  }

  // driveAllocRequest now drives an IqDispatchCmd
  def driveAllocRequest(
      allocFlowTarget: Flow[IqDispatchCmd], // Target the DUT's allocateIn Flow
      robPtrVal: Int,
      pCfg: PipelineConfig,
      physDestIdxVal: Int = 1,
      writesPhysVal: Boolean = true,
      destIsFprVal: Boolean = false,
      useSrc1Val: Boolean = false,
      src1TagVal: Int = 0,
      src1IsFprVal: Boolean = false,
      useSrc2Val: Boolean = false,
      src2TagVal: Int = 0,
      src2IsFprVal: Boolean = false
  )(implicit cd: ClockDomain): Unit = {
    allocFlowTarget.valid #= true
    
    val cmd = allocFlowTarget.payload
    val uop = cmd.uop
    
    uop.setDefaultForSim()

    uop.robPtr #= robPtrVal
    
    uop.decoded.isValid #= true
    uop.decoded.writeArchDestEn #= writesPhysVal
    uop.decoded.archDest.idx #= physDestIdxVal

    uop.decoded.useArchSrc1 #= useSrc1Val
    if (useSrc1Val) {
      uop.decoded.archSrc1.idx #= src1TagVal
      uop.decoded.archSrc1.rtype #= (if(src1IsFprVal) ArchRegType.FPR else ArchRegType.GPR)
    }
    
    uop.decoded.useArchSrc2 #= useSrc2Val
    if (useSrc2Val) {
      uop.decoded.archSrc2.idx #= src2TagVal
      uop.decoded.archSrc2.rtype #= (if(src2IsFprVal) ArchRegType.FPR else ArchRegType.GPR)
    }
    
    uop.rename.writesToPhysReg #= writesPhysVal
    if (writesPhysVal) {
      uop.rename.physDest.idx #= physDestIdxVal
      uop.rename.physDestIsFpr #= destIsFprVal
      uop.rename.allocatesPhysDest #= true
    }
    if (useSrc1Val) {
      uop.rename.physSrc1.idx #= src1TagVal
      uop.rename.physSrc1IsFpr #= src1IsFprVal
    }
    if (useSrc2Val) {
      uop.rename.physSrc2.idx #= src2TagVal
      uop.rename.physSrc2IsFpr #= src2IsFprVal
    }

    cd.waitSampling()
    allocFlowTarget.valid #= false
  }
  
  def driveBypassOnPort(
      bypassPort: Flow[BypassMessage],
      pRegIdx: Int,
      data: BigInt,
      isFpr: Boolean = false,
      robPtrProducer: Int = 0,
      isExcp: Boolean = false
  )(implicit cd: ClockDomain): Unit = {
    bypassPort.valid #= true
    bypassPort.payload.physRegIdx #= pRegIdx
    bypassPort.payload.physRegData #= data
    bypassPort.payload.robPtr #= robPtrProducer
    bypassPort.payload.isFPR #= isFpr
    bypassPort.payload.hasException #= isExcp
  }

  def deassertBypassOnPort(bypassPort: Flow[BypassMessage]): Unit = {
    bypassPort.valid #= false
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
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload =>
          scoreboard.pushDut(payload.robPtr.toInt)
        }

        scoreboard.pushRef(10)
        driveAllocRequest(dut.io.allocateIn, robPtrVal = 10, pCfg = pCfg)

        cd.waitSampling()
        assert(dut.io.canAccept.toBoolean)
        assert(dut.internalValidCount.toInt == 1)

        dut.io.issueOut.ready #= true
        var timeout = 20
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for basic alloc/issue")

        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0)
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Single Source Wakeup via Bypass") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }
        StreamReadyRandomizer(dut.io.issueOut, cd)

        scoreboard.pushRef(20)
        driveAllocRequest(
          dut.io.allocateIn,
          robPtrVal = 20,
          pCfg = pCfg,
          useSrc1Val = true,
          src1TagVal = 5,
          useSrc2Val = false
        )
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 1)

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 5, data = 123)
        cd.waitSampling()
        deassertBypassOnPort(dut.io.bypassIn(0))

        var timeout = 30
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for single source wakeup")
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
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }
        StreamReadyRandomizer(dut.io.issueOut, cd)

        scoreboard.pushRef(30)
        driveAllocRequest(
          dut.io.allocateIn,
          robPtrVal = 30,
          pCfg = pCfg,
          useSrc1Val = true,
          src1TagVal = 10,
          useSrc2Val = true,
          src2TagVal = 11
        )
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 1, "Count after alloc for 2-src wakeup")

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 10, data = 301)
        cd.waitSampling()
        deassertBypassOnPort(dut.io.bypassIn(0))
        cd.waitSampling(2)

        driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 11, data = 302)
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
          StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }
          StreamReadyRandomizer(dut.io.issueOut, cd)

          scoreboard.pushRef(35)
          driveAllocRequest(
            dut.io.allocateIn,
            robPtrVal = 35,
            pCfg = pCfg,
            useSrc1Val = true,
            src1TagVal = 15,
            useSrc2Val = true,
            src2TagVal = 16
          )
          cd.waitSampling()
          assert(dut.internalValidCount.toInt == 1, "Count after alloc for simultaneous wakeup")

          driveBypassOnPort(dut.io.bypassIn(0), pRegIdx = 15, data = 351)
          driveBypassOnPort(dut.io.bypassIn(1), pRegIdx = 16, data = 352)
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
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        dut.io.issueOut.ready #= false
        for (i <- 0 until iqDepth) {
          val robPtr = 40 + i
          scoreboard.pushRef(robPtr)
          driveAllocRequest(dut.io.allocateIn, robPtrVal = robPtr, pCfg = pCfg)
        }
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == iqDepth)
        assert(!dut.io.canAccept.toBoolean, "IQ should not accept when full")

        dut.io.issueOut.ready #= true
        var timeout = iqDepth * 5 + 20
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for fill and drain")
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0)
        assert(dut.io.canAccept.toBoolean)
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Flush Operation") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        dut.io.issueOut.ready #= false
        driveAllocRequest(dut.io.allocateIn, robPtrVal = 50, pCfg = pCfg)
        var expectedCountBeforeFlush = 1
        if (iqDepth > 1) {
          driveAllocRequest(
            dut.io.allocateIn,
            robPtrVal = 51,
            pCfg = pCfg,
            useSrc1Val = true,
            src1TagVal = 20
          )
          expectedCountBeforeFlush = 2
        }
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == expectedCountBeforeFlush)

        dut.io.flush #= true
        cd.waitSampling()
        dut.io.flush #= false
        cd.waitSampling()

        assert(dut.internalValidCount.toInt == 0, "IQ should be empty after flush")
        assert(dut.io.canAccept.toBoolean, "IQ should accept after flush")

        dut.io.issueOut.ready #= true
        cd.waitSampling(5)
        assert(dut.internalValidCount.toInt == 0, "IQ should remain empty, nothing issued post-flush")
      }
    }

    test(s"IntIQ_${iqDepth}deep_${numBypass}bp - Back-to-Back Issue") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        dut.io.issueOut.ready #= false
        for (i <- 0 until iqDepth) {
          val robPtr = 60 + i
          scoreboard.pushRef(robPtr)
          driveAllocRequest(dut.io.allocateIn, robPtrVal = robPtr, pCfg = pCfg)
        }
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == iqDepth)

        dut.io.issueOut.ready #= true
        var timeout = iqDepth + 10
        while ((scoreboard.dut.nonEmpty || scoreboard.ref.nonEmpty) && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for back-to-back issue")
        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCount.toInt == 0)
      }
    }
  }
  thatsAll()
}
