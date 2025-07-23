// filename: src/test/scala/parallax/issue/IssueQueueComponentSpec.scala
// testOnly test.scala.IssueQueueComponentSpec
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

// A simple IO bundle for the testbench
case class IssueQueueTestIO(
    pCfg: PipelineConfig,
    numWakeupPorts: Int // The parameter we need to control
) extends Bundle
    with IMasterSlave {
  val allocateIn = slave(Stream(IqDispatchCmd(pCfg)))
  val issueOut = master(Stream(IQEntryAluInt(pCfg))) // Assuming we test an Int IQ

  // This is our mock wakeup bus
  val wakeupIn = Vec.fill(numWakeupPorts)(slave(Flow(WakeupPayload(pCfg))))

  val flush = in(Bool())

  override def asMaster(): Unit = {
    master(allocateIn)
    slave(issueOut)
    wakeupIn.foreach(master(_))
    out(flush)
  }
}

// The testbench component that wraps the IQ
class IssueQueueTestBench(
    val pCfg: PipelineConfig,
    val numWakeupPorts: Int // Pass the parameter here
) extends Component {
  val io = slave(IssueQueueTestIO(pCfg, numWakeupPorts))
  io.simPublic() // Make IO accessible in the simulation

  // Instantiate the component under test
  val iq = new IssueQueueComponent(
    iqConfig = IssueQueueConfig[IQEntryAluInt](
      pipelineConfig = pCfg,
      depth = 4, // Example depth
      exeUnitType = ExeUnitType.ALU_INT,
      uopEntryType = HardType(IQEntryAluInt(pCfg)),
      name = "TestIntIQ"
    ),
    numWakeupPorts = numWakeupPorts, // Pass the controlled parameter
    id = 0
  )
  // 新增一个内部信号的别名，以便在测试中访问
  val internalValidCount = iq.currentValidCount
  internalValidCount.simPublic()

  // Connect the testbench IO to the IQ component
  iq.io.allocateIn << io.allocateIn
  io.issueOut <> iq.io.issueOut

  // Connect the mock wakeup bus
  iq.io.wakeupIn := io.wakeupIn

  iq.io.flush <> io.flush
}

// 下面需要大改

class IssueQueueComponentSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val DEFAULT_IQ_DEPTH = 4
  val MOCK_WAKEUP_PORTS = 4 // 为所有测试定义一个固定的模拟唤醒端口数量

  // pCfg创建函数保持不变
  def createPipelineConfig(
      iqDepth: Int = DEFAULT_IQ_DEPTH
  ): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    physGprCount = 32 + 16,
    archGprCount = 32,
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    memOpIdWidth = 8 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 64 + iqDepth
  )

  // 更新initDutIO以处理wakeupIn向量
  def initDutIO(dut: IssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
    dut.io.allocateIn.valid #= false
    dut.io.issueOut.ready #= false
    // 遍历所有唤醒端口进行初始化
    for (port <- dut.io.wakeupIn) {
      port.valid #= false
      port.payload.physRegIdx #= 0
    }
    dut.io.flush #= false
    cd.waitSampling()
  }

  // driveAllocRequest辅助函数保持不变，它已经很完美了
  def driveAllocRequest(
      allocTarget: Stream[IqDispatchCmd],
      robPtrVal: Int,
      pCfg: PipelineConfig,
      physDestIdxVal: Int = 1,
      writesPhysVal: Boolean = true,
      destIsFprVal: Boolean = false,
      useSrc1Val: Boolean = false,
      src1TagVal: Int = 0,
      src1IsFprVal: Boolean = false,
      src1InitialReadyVal: Boolean = true,
      useSrc2Val: Boolean = false,
      src2TagVal: Int = 0,
      src2IsFprVal: Boolean = false,
      src2InitialReadyVal: Boolean = true
  )(implicit cd: ClockDomain): Unit = {
    allocTarget.valid #= true

    val cmd = allocTarget.payload
    val uop = cmd.uop

    uop.setDefaultForSim()

    uop.robPtr #= robPtrVal

    uop.decoded.isValid #= true
    uop.decoded.writeArchDestEn #= writesPhysVal
    uop.decoded.archDest.idx #= physDestIdxVal

    uop.decoded.useArchSrc1 #= useSrc1Val
    if (useSrc1Val) {
      uop.decoded.archSrc1.idx #= src1TagVal
      uop.decoded.archSrc1.rtype #= (if (src1IsFprVal) ArchRegType.FPR else ArchRegType.GPR)
    }

    uop.decoded.useArchSrc2 #= useSrc2Val
    if (useSrc2Val) {
      uop.decoded.archSrc2.idx #= src2TagVal
      uop.decoded.archSrc2.rtype #= (if (src2IsFprVal) ArchRegType.FPR else ArchRegType.GPR)
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

    // 分配初始就绪状态
    cmd.src1InitialReady #= src1InitialReadyVal
    cmd.src2InitialReady #= src2InitialReadyVal

    cd.waitSampling()
    allocTarget.valid #= false
  }

  // driveWakeup/deassertWakeup现在需要指定端口索引
  def driveWakeup(
      wakeupPorts: Vec[Flow[WakeupPayload]],
      portIdx: Int,
      pRegIdx: Int
  )(implicit cd: ClockDomain): Unit = {
    require(portIdx < wakeupPorts.length, s"Wakeup port index ${portIdx} is out of bounds.")
    wakeupPorts(portIdx).valid #= true
    wakeupPorts(portIdx).payload.physRegIdx #= pRegIdx
  }

  def deassertWakeup(wakeupPorts: Vec[Flow[WakeupPayload]], portIdx: Int): Unit = {
    require(portIdx < wakeupPorts.length, s"Wakeup port index ${portIdx} is out of bounds.")
    wakeupPorts(portIdx).valid #= false
  }

  // testParams不再需要，因为我们为所有测试使用固定的深度和唤醒端口数
  val pCfg = createPipelineConfig(iqDepth = DEFAULT_IQ_DEPTH)

  test("Basic elaboration test") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
    // Elaboration successful is a pass
    }
  }

  test("IntIQ - Basic Allocation and Issue (Immediately Ready)") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
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
      assert(dut.io.allocateIn.ready.toBoolean)
      assert(dut.internalValidCount.toInt == 1)

      dut.io.issueOut.ready #= true
      var timeout = 20
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for basic alloc/issue")

      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
    }
  }

  test("IntIQ - Single Source Wakeup via Global Wakeup") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
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
        src1InitialReadyVal = false, // 明确它需要被唤醒
        useSrc2Val = false
      )
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 1)

      // 从模拟的唤醒端口2发出唤醒信号
      driveWakeup(dut.io.wakeupIn, portIdx = 2, pRegIdx = 5)
      cd.waitSampling()
      deassertWakeup(dut.io.wakeupIn, portIdx = 2)

      var timeout = 30
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for single source wakeup")
      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
    }
  }

  test("IntIQ - Two Sources Wakeup (Sequential Global Wakeup)") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
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
        src1InitialReadyVal = false,
        useSrc2Val = true,
        src2TagVal = 11,
        src2InitialReadyVal = false
      )
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 1, "Count after alloc for 2-src wakeup")

      // 唤醒源1
      driveWakeup(dut.io.wakeupIn, portIdx = 0, pRegIdx = 10)
      cd.waitSampling()
      deassertWakeup(dut.io.wakeupIn, portIdx = 0)
      cd.waitSampling(2)

      // 唤醒源2
      driveWakeup(dut.io.wakeupIn, portIdx = 3, pRegIdx = 11)
      cd.waitSampling()
      deassertWakeup(dut.io.wakeupIn, portIdx = 3)

      var timeout = 40
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for two-source sequential wakeup")
      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
    }
  }

  // ======================= 最重要的新增测试 =======================
  test("IntIQ - Two Sources Wakeup (Simultaneous Global Wakeup)") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }
      StreamReadyRandomizer(dut.io.issueOut, cd)

      scoreboard.pushRef(35) // 使用新的 ROB 指针
      driveAllocRequest(
        dut.io.allocateIn,
        robPtrVal = 35,
        pCfg = pCfg,
        useSrc1Val = true,
        src1TagVal = 15,
        src1InitialReadyVal = false,
        useSrc2Val = true,
        src2TagVal = 16,
        src2InitialReadyVal = false
      )
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 1, "Count after alloc for simultaneous wakeup")

      // 在同一个周期，从不同的端口唤醒两个源
      driveWakeup(dut.io.wakeupIn, portIdx = 1, pRegIdx = 15)
      driveWakeup(dut.io.wakeupIn, portIdx = 2, pRegIdx = 16)

      cd.waitSampling()

      // 在下一个周期取消断言
      deassertWakeup(dut.io.wakeupIn, portIdx = 1)
      deassertWakeup(dut.io.wakeupIn, portIdx = 2)

      var timeout = 40
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for two-source simultaneous wakeup")
      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
    }
  }
  test("IntIQ - Local Wakeup via Issue Port") {
    // 这个测试验证了当一个指令被发射时，其结果能否在下一个周期
    // 立即唤醒IQ中依赖它的另一条指令（本地转发/Bypass）。
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      // 步骤1：阻止IQ发射，以便我们可以安全地将两条指令都分配进去
      dut.io.issueOut.ready #= false

      // --- 1. 分配生产者指令 (Inst_A, robPtr=40) ---
      // 这条指令本身是就绪的，它会产生physReg=5的结果
      scoreboard.pushRef(40)
      driveAllocRequest(
        allocTarget = dut.io.allocateIn,
        robPtrVal = 40,
        pCfg = pCfg,
        physDestIdxVal = 5,
        writesPhysVal = true,
        src1InitialReadyVal = true,
        src2InitialReadyVal = true
      )

      // --- 2. 分配消费者指令 (Inst_B, robPtr=41) ---
      // 这条指令依赖Inst_A产生的physReg=5
      scoreboard.pushRef(41)
      driveAllocRequest(
        allocTarget = dut.io.allocateIn,
        robPtrVal = 41,
        pCfg = pCfg,
        writesPhysVal = false, // 它不写寄存器
        useSrc1Val = true,
        src1TagVal = 5,
        src1InitialReadyVal = false // 需要被唤醒
      )
      cd.waitSampling()
      println(s"[SIM] After allocs (Producer & Consumer): validCount = ${dut.internalValidCount.toInt}")
      assert(dut.internalValidCount.toInt == 2, "IQ should contain 2 entries before issue.")

      // 步骤2：打开发射端口，让IQ开始工作
      dut.io.issueOut.ready #= true

      // --- 3. 验证背靠背发射 ---
      // 我们期望 Inst_A 在下一个周期发射，
      // 并在再下一个周期，被本地唤醒的 Inst_B 被发射。
      var timeout = 30
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }

      assert(timeout > 0, "Timeout! Not all instructions were issued. Local wakeup might have failed.")
      scoreboard.checkEmptyness()

      cd.waitSampling(5)
      assert(
        dut.internalValidCount.toInt == 0,
        s"IQ should be empty, but still has ${dut.internalValidCount.toInt} entries."
      )

      println(s"\n[SUCCESS] Test 'IntIQ - Local Wakeup via Issue Port' PASSED!")
    }
  }

  test("IntIQ - Fill IQ and Drain (Oldest First)") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)

      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      // 阻止发射，先填满IQ
      dut.io.issueOut.ready #= false
      for (i <- 0 until DEFAULT_IQ_DEPTH) {
        val robPtr = 50 + i
        scoreboard.pushRef(robPtr)
        driveAllocRequest(dut.io.allocateIn, robPtrVal = robPtr, pCfg = pCfg)
      }
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH)
      assert(!dut.io.allocateIn.ready.toBoolean, "IQ should not accept when full")

      // 现在允许发射，并验证它们按顺序出来
      dut.io.issueOut.ready #= true
      var timeout = DEFAULT_IQ_DEPTH * 5 + 20
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for fill and drain")
      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
      assert(dut.io.allocateIn.ready.toBoolean)
    }
  }

  test("IntIQ - Flush Operation") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)

      dut.io.issueOut.ready #= false
      driveAllocRequest(
        dut.io.allocateIn,
        robPtrVal = 60,
        pCfg = pCfg,
        useSrc1Val = true,
        src1TagVal = 20,
        src1InitialReadyVal = false
      )
      driveAllocRequest(dut.io.allocateIn, robPtrVal = 61, pCfg = pCfg)

      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 2)

      // 发出Flush信号
      dut.io.flush #= true
      cd.waitSampling()
      dut.io.flush #= false
      cd.waitSampling()

      assert(dut.internalValidCount.toInt == 0, "IQ should be empty after flush")
      assert(dut.io.allocateIn.ready.toBoolean, "IQ should accept after flush")

      // 确保flush后没有指令意外发射
      dut.io.issueOut.ready #= true
      cd.waitSampling(5)
      assert(dut.internalValidCount.toInt == 0, "IQ should remain empty, nothing issued post-flush")
    }
  }

  test("IntIQ - Back-to-Back Issue") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)

      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      // 填满IQ
      dut.io.issueOut.ready #= false
      for (i <- 0 until DEFAULT_IQ_DEPTH) {
        val robPtr = 70 + i
        scoreboard.pushRef(robPtr)
        driveAllocRequest(dut.io.allocateIn, robPtrVal = robPtr, pCfg = pCfg)
      }
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH)

      // 允许发射，并期望IQ能够每个周期发射一条指令
      dut.io.issueOut.ready #= true
      var timeout = DEFAULT_IQ_DEPTH + 10
      while (scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout waiting for back-to-back issue")
      scoreboard.checkEmptyness()
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 0)
    }
  }

  test("IntIQ - Combined Local and Global Wakeup") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      dut.io.issueOut.ready #= false

      scoreboard.pushRef(80)
      driveAllocRequest(dut.io.allocateIn, robPtrVal = 80, pCfg = pCfg, physDestIdxVal = 20)
      
      scoreboard.pushRef(81)
      driveAllocRequest(
          dut.io.allocateIn,
          robPtrVal = 81,
          pCfg = pCfg,
          writesPhysVal = false,
          useSrc1Val = true, src1TagVal = 20, src1InitialReadyVal = false,
          useSrc2Val = true, src2TagVal = 21, src2InitialReadyVal = false
      )
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 2)

      dut.io.issueOut.ready #= true
      driveWakeup(dut.io.wakeupIn, portIdx = 0, pRegIdx = 21)
      
      println("[SIM] Firing Inst_A and globally waking pReg=21 simultaneously.")
      cd.waitSampling()
      deassertWakeup(dut.io.wakeupIn, portIdx = 0)

      var timeout = 20
      while(scoreboard.ref.nonEmpty && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
      }
      
      assert(timeout > 0, "Timeout! Combined wakeup scenario failed.")
      scoreboard.checkEmptyness()
      cd.waitSampling(5)
      assert(dut.internalValidCount.toInt == 0)
    }
  }

  // ======================= 新增测试 1: 资源竞争与反压 =======================
  test("IntIQ - Allocation and Issue Stall Interaction") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      // 1. 填满IQ，所有指令都已就绪
      dut.io.issueOut.ready #= false
      for (i <- 0 until DEFAULT_IQ_DEPTH) {
        driveAllocRequest(dut.io.allocateIn, robPtrVal = 90 + i, pCfg = pCfg)
      }
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH, "IQ should be full")
      assert(!dut.io.allocateIn.ready.toBoolean, "canAccept should be false when full")

      // 2. 模拟EU持续繁忙（stall）
      dut.io.issueOut.ready #= false
      cd.waitSampling(5)
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH, "Count should not change during stall")
      assert(dut.io.issueOut.valid.toBoolean, "issueOut.valid should be high as entries are ready")

      // 3. 在一个周期内，既发射一条指令，又分配一条新指令
      // 这模拟了当一个槽位被清空时，新指令立即填补进来的场景
      println("[SIM] Simultaneously issuing an old instruction and allocating a new one.")
      dut.io.issueOut.ready #= true  // 允许发射
      scoreboard.pushRef(90) // 最老的指令90将被发射

      // 同时，驱动分配一个新的指令 (robPtr=100)
      // 因为IQ现在可以接受一个了 (canAccept 会在一个组合逻辑延迟后变为true)
      scoreboard.pushRef(100)
      driveAllocRequest(dut.io.allocateIn, robPtrVal = 100, pCfg = pCfg) 
      
      // 4. 检查状态
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH, "Count should be full again (-1, +1)")
      
      // --- 这里是修改点 ---
      // 在这个点，IQ是满的，但它正准备发射91，所以allocateIn.ready应该是true。
      assert(dut.io.allocateIn.ready.toBoolean, "allocateIn.ready should be TRUE as it's ready to issue another instruction")
      
      // 为了验证我们的理解，让我们模拟一个周期没有新指令分配的情况
      println("[SIM] No allocation for one cycle, just issuing.")
      cd.waitSampling() // 这个周期91发射，没有新指令分配
      
      // 现在IQ应该有空位了
      assert(dut.internalValidCount.toInt == DEFAULT_IQ_DEPTH - 1, "Count should decrease after one issue without alloc")
      assert(dut.io.allocateIn.ready.toBoolean, "allocateIn.ready should be true as there is a free slot")

      for(i <- 1 until DEFAULT_IQ_DEPTH) { scoreboard.pushRef(90 + i) } // 将剩余的指令加入参考队列
      
      var timeout = 30
      while(scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout while draining remaining instructions")
      scoreboard.checkEmptyness()
    }
  }

  // ======================= 新增测试 2: 边界条件 - 分配时唤醒 =======================
  test("IntIQ - Wakeup targeting an entry being allocated") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)
      val scoreboard = ScoreboardInOrder[Int]()
      StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

      // 1. 在同一个周期，既分配一个需要唤醒的指令，又发送它的唤醒信号
      // 这模拟了一个非常紧凑的依赖链
      println("[SIM] Allocating an instruction and waking it up in the same cycle.")
      scoreboard.pushRef(110)

      // 驱动分配，指令依赖 pReg=25
      dut.io.allocateIn.valid #= true
      val cmd = dut.io.allocateIn.payload
      val uop = cmd.uop
      uop.setDefaultForSim()
      uop.robPtr #= 110
      uop.decoded.useArchSrc1 #= true
      uop.decoded.archSrc1.idx #= 25
      uop.rename.physSrc1.idx #= 25
      cmd.src1InitialReady #= false // 它不是就绪的

      // 同时，驱动 pReg=25 的唤醒信号
      driveWakeup(dut.io.wakeupIn, portIdx = 1, pRegIdx = 25)

      // 保持一个周期
      cd.waitSampling()
      
      // 取消驱动
      dut.io.allocateIn.valid #= false
      deassertWakeup(dut.io.wakeupIn, portIdx = 1)
      
      // 2. 检查结果
      // 我们期望这条指令在进入IQ后，其src1Ready位就已经被正确设置为true
      cd.waitSampling()
      assert(dut.internalValidCount.toInt == 1, "Instruction should be allocated")
      
      // 3. 验证它能否被立即发射
      dut.io.issueOut.ready #= true
      
      var timeout = 20
      while(scoreboard.ref.nonEmpty && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
      }
      assert(timeout > 0, "Timeout! Instruction allocated and woken up in the same cycle was not issued.")
      scoreboard.checkEmptyness()
    }
  }

// (接续之前所有的测试用例...)

  // ======================= 新增测试 3: 随机化压力测试 =======================
  test("IntIQ - Random Stress Test with Scoreboard") {
    SimConfig.withWave.compile(new IssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)
      initDutIO(dut)

      val scoreboard = new OooScoreboard()
      StreamMonitor(dut.io.issueOut, cd) { payload =>
        scoreboard.dutIssuing(payload.robPtr.toInt)
      }

      // --- 测试参数 ---
      val totalInstructions = 50
      var instructionsToAllocate = totalInstructions
      val maxPhysRegTag = pCfg.archGprCount - 1
      val random = new Random(0) // 使用固定的种子以保证测试可重复

      var robPtrCounter = 200
      var timeout = totalInstructions * 10 + 200

      // --- 主仿真循环 ---
      while(scoreboard.instructionsIssued < totalInstructions && timeout > 0) {
        
        // 1. 随机决定是否分配新指令
        if (instructionsToAllocate > 0 && dut.io.allocateIn.ready.toBoolean && random.nextBoolean()) {
          instructionsToAllocate -= 1
          val currentRobPtr = robPtrCounter
          robPtrCounter += 1

          // 随机决定指令的依赖
          val useSrc1 = random.nextBoolean()
          val useSrc2 = random.nextBoolean()
          val src1Tag = random.nextInt(maxPhysRegTag)
          val src2Tag = if (useSrc1) random.nextInt(maxPhysRegTag - 1) max (src1Tag + 1) else random.nextInt(maxPhysRegTag)

          val deps = mutable.HashSet[Int]()
          if (useSrc1) deps.add(src1Tag)
          if (useSrc2) deps.add(src2Tag)
          val writesDest = random.nextBoolean()
          val destTag = if(writesDest) random.nextInt(maxPhysRegTag) else -1
          scoreboard.push(currentRobPtr, destTag, deps.toSet)

          driveAllocRequest(
            dut.io.allocateIn,
            robPtrVal = currentRobPtr,
            pCfg = pCfg,
            useSrc1Val = useSrc1, src1TagVal = src1Tag, src1InitialReadyVal = false,
            useSrc2Val = useSrc2, src2TagVal = src2Tag, src2InitialReadyVal = false
          )
        }

        // 2. 随机决定是否发送唤醒信号
        // 每个周期，每个唤醒端口都有一定概率发送唤醒
        for (i <- 0 until MOCK_WAKEUP_PORTS) {
          if (random.nextDouble() < 0.3) { // 30%的概率
            val wakeupTag = random.nextInt(maxPhysRegTag)
            scoreboard.wakeup(wakeupTag)
            driveWakeup(dut.io.wakeupIn, portIdx = i, pRegIdx = wakeupTag)
          } else {
            deassertWakeup(dut.io.wakeupIn, portIdx = i)
          }
        }

        // 3. 随机决定是否反压
        dut.io.issueOut.ready #= random.nextDouble() > 0.2 // 80%的时间是ready

        cd.waitSampling()
        timeout -= 1
      }

      // --- 检查最终状态 ---
      scoreboard.checkEmptyness(timeout)
    }
  }

  thatsAll()
}

// 在 class IssueQueueComponentSpec 的外部，或者一个单独的文件中
// 一个用于随机化测试的、更复杂的记分板
// 它可以处理乱序发射
class OooScoreboard {
  // 等待被唤醒的指令
  // Key: robPtr, Value: Set of pending wakeup tags
  private val pending = mutable.HashMap[Int, mutable.HashSet[Int]]()

  // 已经就绪、等待发射的指令
  private val readyToIssue = mutable.Queue[Int]()

  // 已经从DUT发射的指令
  private val issuedFromDut = mutable.Queue[Int]()

  var instructionsAllocated = 0
  var instructionsIssued = 0
  private val instructionDestinations = mutable.HashMap[Int, Int]()
// 新增一个数据结构，用于存储本周期发生的所有唤醒事件
private val recentWakeups = mutable.HashSet[Int]()

// 在每个周期的开始，我们需要清空这个集合
def cycleStart(): Unit = {
  recentWakeups.clear()
}

 // 修改 wakeup 方法，让它记录唤醒事件
def wakeup(tag: Int): Unit = {
  println(s"[SB] WAKEUP: tag=$tag")
  recentWakeups.add(tag) // 记录本周期的唤醒

  // 立即处理已经存在的pending指令 (这部分逻辑不变)
  for ((robPtr, deps) <- pending) {
    if (deps.contains(tag)) {
      deps.remove(tag)
      println(s"[SB]   - robPtr=$robPtr dependency satisfied for tag=$tag. Remaining: ${deps.size}")
      if (deps.isEmpty) {
        println(s"[SB]   - robPtr=$robPtr is now READY!")
        readyToIssue.enqueue(robPtr)
      }
    }
  }
  pending.retain((_, deps) => deps.nonEmpty)
}

// 修改 push 方法，让它检查最近的唤醒事件
def push(robPtr: Int, destTag: Int, dependencies: Set[Int]): Unit = {
  instructionsAllocated += 1
  if (destTag != -1) {
    instructionDestinations(robPtr) = destTag
  }

  // ================== 关键修复 ==================
  // 在决定是否pending之前，先用本周期的唤醒事件来满足依赖
  val initialDeps = mutable.HashSet(dependencies.toSeq:_*)
  val remainingDeps = initialDeps -- recentWakeups // 集合减法

  if (remainingDeps.isEmpty) {
    println(s"[SB] PUSH_READY: robPtr=$robPtr (dependencies ${dependencies.mkString(",")} were satisfied by recent wakeups)")
    readyToIssue.enqueue(robPtr)
  } else {
    println(s"[SB] PUSH_PENDING: robPtr=$robPtr, depends on ${remainingDeps.mkString(",")}")
    pending(robPtr) = remainingDeps
  }
  // ============================================
}




def dutIssuing(robPtr: Int): Unit = {
  println(s"[SB] DUT_ISSUE: robPtr=$robPtr")
  instructionsIssued += 1
  
  if (readyToIssue.contains(robPtr)) {
    readyToIssue.dequeueAll(_ == robPtr)
    
    // ================== 本地唤醒建模 ==================
    if (instructionDestinations.contains(robPtr)) {
      val wakeupTag = instructionDestinations(robPtr)
      println(s"[SB] LOCAL WAKEUP from issued robPtr=$robPtr for tag=$wakeupTag")
      // 调用和全局唤醒相同的逻辑
      wakeup(wakeupTag)
      // 清理
      instructionDestinations.remove(robPtr)
    }
    // ================================================

  } else {
    simFailure(s"Scoreboard Error: DUT issued robPtr=$robPtr, but it was not ready to issue!")
  }
}



  // 检查测试结束时是否所有东西都匹配
  def checkEmptyness(timeout: Int): Unit = {
    if (timeout <= 0) {
      simFailure("Random test timed out!")
    }
    if (pending.nonEmpty || readyToIssue.nonEmpty) {
      println("Scoreboard Check Failed at end of test:")
      if(pending.nonEmpty) {
        println("Pending instructions that were never woken up:")
        pending.foreach(p => println(s"  - robPtr=${p._1}, waiting for tags=${p._2.mkString(",")}"))
      }
      if(readyToIssue.nonEmpty) {
        println("Ready instructions that were never issued by DUT:")
        readyToIssue.foreach(p => println(s"  - robPtr=$p"))
      }
      simFailure("Scoreboard was not empty at the end of the test.")
    }
    println(s"[SB] SUCCESS: All ${instructionsAllocated} allocated instructions were issued correctly.")
  }
}
