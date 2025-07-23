// filename: src/test/scala/parallax/issue/IssueQueueComponentSpec.scala
package test.scala

import parallax.common._
import parallax.components.issue._
import parallax.execute.WakeupPayload
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

  def createPipelineConfig(
      iqDepth: Int = DEFAULT_IQ_DEPTH
  ): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    physGprCount = 32 + 16,
    archGprCount = 32,
    bypassNetworkSources = 0, // No longer needed with wakeup bus
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    memOpIdWidth = 8 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 64 + iqDepth
  )

  def initDutIO(dut: IntIssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
    dut.io.allocateIn.valid #= false
    dut.io.issueOut.ready #= false
    dut.io.wakeupIn.valid #= false
    dut.io.wakeupIn.payload.physRegIdx #= 0
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
      src2IsFprVal: Boolean = false,
      
    src1InitialReadyVal: Boolean = true,
    src2InitialReadyVal: Boolean = true,
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

        allocFlowTarget.payload.uop := uop
    allocFlowTarget.payload.src1InitialReady #= src1InitialReadyVal
    allocFlowTarget.payload.src2InitialReady #= src2InitialReadyVal
    cd.waitSampling()

    allocFlowTarget.valid #= false
  }
  
  def driveWakeup(
      wakeupFlow: Flow[WakeupPayload],
      pRegIdx: Int
  )(implicit cd: ClockDomain): Unit = {
    wakeupFlow.valid #= true
    wakeupFlow.payload.physRegIdx #= pRegIdx
  }

  def deassertWakeup(wakeupFlow: Flow[WakeupPayload]): Unit = {
    wakeupFlow.valid #= false
  }

  val testParams = Seq(
    DEFAULT_IQ_DEPTH,
    2,
    8
  )

  test("Basic elaboration test") {
    SimConfig.withWave.compile(new IntIssueQueueTestBench(createPipelineConfig(), 4)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
    }
  }

  for (iqDepth <- testParams) {
    val pCfg = createPipelineConfig(iqDepth = iqDepth)

    test(s"IntIQ_${iqDepth}deep - Basic Allocation and Issue (Immediately Ready)") {
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

    test(s"IntIQ_${iqDepth}deep - Single Source Wakeup via Global Wakeup") {
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

        driveWakeup(dut.io.wakeupIn, pRegIdx = 5)
        cd.waitSampling()
        deassertWakeup(dut.io.wakeupIn)

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

    test(s"IntIQ_${iqDepth}deep - Two Sources Wakeup (Sequential Global Wakeup)") {
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

        driveWakeup(dut.io.wakeupIn, pRegIdx = 10)
        cd.waitSampling()
        deassertWakeup(dut.io.wakeupIn)
        cd.waitSampling(2)

        driveWakeup(dut.io.wakeupIn, pRegIdx = 11)
        cd.waitSampling()
        deassertWakeup(dut.io.wakeupIn)

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
testOnly(s"IntIQ_${iqDepth}deep - Local Wakeup via Issue Port") {
  SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
    implicit val cd = dut.clockDomain.get
    dut.clockDomain.forkStimulus(10)
    initDutIO(dut)
    val scoreboard = ScoreboardInOrder[Int]()
    StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

    // 【关键修复】步骤1：在分配指令前，通过将 ready 拉低来阻止 IQ 发射指令。
    dut.io.issueOut.ready #= false

    // --- 1. 分配生产者指令 (Inst_A) ---
    // 这条指令本身是就绪的，但因为 issueOut.ready=false，它不会被发射
    scoreboard.pushRef(40)
    driveAllocRequest(
      allocFlowTarget = dut.io.allocateIn,
      robPtrVal = 40,
      pCfg = pCfg,
      physDestIdxVal = 5,
      writesPhysVal = true,
      src1InitialReadyVal = true, 
      src2InitialReadyVal = true
    )
    cd.waitSampling(1) 
    println(s"[SIM] After first alloc (Producer): validCount = ${dut.internalValidCount.toInt}")
    assert(dut.internalValidCount.toInt == 1, "After 1st alloc, IQ should have 1 entry.")

    // --- 2. 分配消费者指令 (Inst_B) ---
    // 这条指令依赖 Inst_A (通过 physDest 5)，所以它不是就绪的
    scoreboard.pushRef(41)
    driveAllocRequest(
      allocFlowTarget = dut.io.allocateIn,
      robPtrVal = 41,
      pCfg = pCfg,
      writesPhysVal = false,
      useSrc1Val = true,
      src1TagVal = 5,
      src1InitialReadyVal = false
    )
    cd.waitSampling(1)
    println(s"[SIM] After second alloc (Consumer): validCount = ${dut.internalValidCount.toInt}")
    
    // 【关键修复】步骤2：现在两条指令都在 IQ 中，可以安全地进行断言了。
    assert(dut.internalValidCount.toInt == 2, "IQ should contain 2 entries before issue.")

    // 【关键修复】步骤3：重新打开发射端口，让 IQ 开始工作。
    dut.io.issueOut.ready #= true
    
    // --- 3. 验证背靠背发射 ---
    // 现在，我们期望 Inst_A 在下一个周期发射，
    // 并在再下一个周期，被唤醒的 Inst_B 被发射。
    var timeout = 30
    while (scoreboard.ref.nonEmpty && timeout > 0) {
      cd.waitSampling()
      timeout -= 1
    }
    
    assert(timeout > 0, "Timeout! Not all instructions were issued. Local wakeup might have failed.")
    scoreboard.checkEmptyness()
    
    cd.waitSampling(5)
    assert(dut.internalValidCount.toInt == 0, s"IQ should be empty, but still has ${dut.internalValidCount.toInt} entries.")

    println(s"\n[SUCCESS] Test 'IntIQ_${iqDepth}deep - Local Wakeup via Issue Port' PASSED!")
  }
}


    test(s"IntIQ_${iqDepth}deep - Fill IQ and Drain (Oldest First)") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        dut.io.issueOut.ready #= false
        for (i <- 0 until iqDepth) {
          val robPtr = 50 + i
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

    test(s"IntIQ_${iqDepth}deep - Flush Operation") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        dut.io.issueOut.ready #= false
        driveAllocRequest(dut.io.allocateIn, robPtrVal = 60, pCfg = pCfg)
        var expectedCountBeforeFlush = 1
        if (iqDepth > 1) {
          driveAllocRequest(
            dut.io.allocateIn,
            robPtrVal = 61,
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

    test(s"IntIQ_${iqDepth}deep - Back-to-Back Issue") {
      SimConfig.withWave.compile(new IntIssueQueueTestBench(pCfg, iqDepth)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        dut.io.issueOut.ready #= false
        for (i <- 0 until iqDepth) {
          val robPtr = 70 + i
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
