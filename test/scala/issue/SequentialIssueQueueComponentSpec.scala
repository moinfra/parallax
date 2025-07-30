// filename: test/scala/SequentialIssueQueueSpec.scala
// command: testOnly test.scala.SequentialIssueQueueSpec
package test.scala

import parallax.common._
import parallax.components.issue._
import parallax.execute.WakeupPayload
import parallax.utilities.ParallaxLogger

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}

import scala.collection.mutable
import scala.util.Random
import parallax.issue.IqDispatchCmd

// Testbench component that wraps the Sequential IQ
class SequentialIssueQueueTestBench(
    val pCfg: PipelineConfig,
    val numWakeupPorts: Int,
    val depth: Int
) extends Component {
  // IO与通用IQ的Testbench完全相同
  val io = slave(IssueQueueTestIO(pCfg, numWakeupPorts))
  io.simPublic()

  // 实例化被测组件：SequentialIssueQueueComponent
  val iq = new SequentialIssueQueueComponent(
    iqConfig = IssueQueueConfig[IQEntryAluInt](
      pipelineConfig = pCfg,
      depth = depth,
      exeUnitType = ExeUnitType.ALU_INT,
      uopEntryType = HardType(IQEntryAluInt(pCfg)),
      name = "TestSeqIntIQ"
    ),
    numWakeupPorts = numWakeupPorts,
    id = 0
  )
  val internalValidCountReg = iq.state.validCountReg
  internalValidCountReg.simPublic()

  // 连接IO
  iq.io.allocateIn << io.allocateIn
  io.issueOut <> iq.io.issueOut
  iq.io.wakeupIn := io.wakeupIn
  iq.io.flush <> io.flush
}

class SequentialIssueQueueSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val DEFAULT_IQ_DEPTH = 4
  val MOCK_WAKEUP_PORTS = 4

  // pCfg创建函数保持不变
  def createPipelineConfig(
      iqDepth: Int = DEFAULT_IQ_DEPTH
  ): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    physGprCount = 32 + 16,
    archGprCount = 32,
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    memOpIdWidth = 32 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 512
  )

  // 更新initDutIO以处理wakeupIn向量
  def initDutIO(dut: SequentialIssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
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
      src2InitialReadyVal: Boolean = true,
      autoSamplingAndDeassert: Boolean = true
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

    if (autoSamplingAndDeassert) {
      cd.waitSampling()
      allocTarget.valid #= false
    }
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

  val pCfg = createPipelineConfig(DEFAULT_IQ_DEPTH)

  test("SequentialIQ - Basic Allocation and Issue") {
    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, DEFAULT_IQ_DEPTH)).doSim {
      dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        initDutIO(dut)

        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        scoreboard.pushRef(10)
        driveAllocRequest(dut.io.allocateIn, robPtrVal = 10, pCfg = pCfg)
        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 1)

        dut.io.issueOut.ready #= true

        // 【修正】: 使用带超时的等待循环
        var timeout = 20
        while (scoreboard.ref.nonEmpty && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for basic issue")

        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 0)
    }
  }

  test("SequentialIQ - Wakeup and Issue") {
    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, DEFAULT_IQ_DEPTH)).doSim {
      dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        initDutIO(dut)
        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }
        StreamReadyRandomizer(dut.io.issueOut, cd)

        scoreboard.pushRef(20)
        driveAllocRequest(
          dut.io.allocateIn,
          20,
          pCfg,
          useSrc1Val = true,
          src1TagVal = 5,
          src1InitialReadyVal = false
        )
        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 1)

        driveWakeup(dut.io.wakeupIn, 0, 5)
        cd.waitSampling()
        deassertWakeup(dut.io.wakeupIn, 0)

        // 【修正】: 使用带超时的等待循环
        var timeout = 30
        while (scoreboard.ref.nonEmpty && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for wakeup and issue")

        scoreboard.checkEmptyness()
    }
  }

  test("SequentialIQ - Strict In-Order Issue Verification") {
    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, DEFAULT_IQ_DEPTH)).doSim {
      dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        initDutIO(dut)
        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload => scoreboard.pushDut(payload.robPtr.toInt) }

        scoreboard.pushRef(30)
        driveAllocRequest(
          dut.io.allocateIn,
          30,
          pCfg,
          useSrc1Val = true,
          src1TagVal = 10,
          src1InitialReadyVal = false
        )

        scoreboard.pushRef(31)
        driveAllocRequest(dut.io.allocateIn, 31, pCfg)

        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 2)

        dut.io.issueOut.ready #= true
        cd.waitSampling(5)

        assert(scoreboard.dut.isEmpty, "ERROR: A ready instruction was issued before an older, un-ready instruction!")

        driveWakeup(dut.io.wakeupIn, 0, 10)
        cd.waitSampling()
        deassertWakeup(dut.io.wakeupIn, 0)

        // 【修正】: 使用带超时的等待循环
        var timeout = 30
        while (scoreboard.ref.nonEmpty && timeout > 0) {
          cd.waitSampling()
          timeout -= 1
        }
        assert(timeout > 0, "Timeout waiting for both instructions to issue")

        scoreboard.checkEmptyness()
        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 0)
    }
  }

  test("SequentialIQ - Compression Logic Verification") {
    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, DEFAULT_IQ_DEPTH)).doSim {
      dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        initDutIO(dut)
        val scoreboard = ScoreboardInOrder[Int]()
        StreamMonitor(dut.io.issueOut, cd) { payload =>
          println(s"[DUT ISSUE] @ ${simTime()}: robPtr=${payload.robPtr.toInt}")
          scoreboard.pushDut(payload.robPtr.toInt)
        }

        // 1. 填充阶段
        dut.io.issueOut.ready #= false
        for (i <- 0 until DEFAULT_IQ_DEPTH) {
          val robPtr = 40 + i
          scoreboard.pushRef(robPtr)

          driveAllocRequest(
            dut.io.allocateIn,
            robPtr,
            pCfg,
            useSrc1Val = false,
            useSrc2Val = false,
            src1InitialReadyVal = true,
            src2InitialReadyVal = true
          )
          println(s">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>i=${i} over")
        }

        cd.waitSampling()
        assert(
          dut.internalValidCountReg.toInt == DEFAULT_IQ_DEPTH,
          s"IQ should be full, but count is ${dut.internalValidCountReg.toInt}"
        )

        // 2. 发射阶段
        dut.io.issueOut.ready #= true
        for (i <- 0 until DEFAULT_IQ_DEPTH) {
          cd.waitSampling()

          val expectedCount = DEFAULT_IQ_DEPTH - i
          assert(
            dut.internalValidCountReg.toInt == expectedCount,
            s"After issuing robPtr=${40 + i}, count should be ${expectedCount}, but was ${dut.internalValidCountReg.toInt}"
          )
        }
        cd.waitSampling()
        scoreboard.checkEmptyness()

        cd.waitSampling()
        assert(dut.internalValidCountReg.toInt == 0, "IQ should be empty at the end")
    }
  }

  test("SequentialIQ - Final Corrected Fuzzy Test") {
    // 1. 在测试内部创建专用配置
    val pCfg = createPipelineConfig(DEFAULT_IQ_DEPTH)

    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, DEFAULT_IQ_DEPTH)).doSim {
      dut =>
        implicit val cd = dut.clockDomain.get
        cd.forkStimulus(10)
        initDutIO(dut)

        // 2. 初始化
        val model = new SequentialIQModel()
        val scoreboard = ScoreboardInOrder[Int]()
        val rand = new Random(seed = 1337)
        var robPtrCounter = 100
        val totalInstructions = 400
        var timeout = totalInstructions * 30 + 2000

        // 3. 在线检查器 (StreamMonitor)
        StreamMonitor(dut.io.issueOut, cd) { payload =>
          val issuedRobPtr = payload.robPtr.toInt
          println(
            s"[DUT ISSUE] @ ${simTime()}: robPtr=${issuedRobPtr}. Scoreboard(ref=${scoreboard.ref.size}, dut=${scoreboard.dut.size}), Model(size=${model.size})"
          )

          // 在调用 issue 之前打印模型头部，看看我们期望的是什么
          println(s"            > Model expects to issue robPtr=${model.getHeadRobPtr().getOrElse("EMPTY")}")

          scoreboard.pushDut(issuedRobPtr)
          model.issue(issuedRobPtr).get
        }

        // ========================================================================
        // 最终的、返璞归真的、绝对稳健的循环
        // ========================================================================
        while (scoreboard.ref.size < totalInstructions && timeout > 0) {
          println("hello")
          // --- 阶段一: 决策 ---
          val do_alloc_attempt = !model.isFull(DEFAULT_IQ_DEPTH) && rand.nextInt(10) < 6
          println("1")

          var allocRobPtr, allocSrc1Tag = 0
          var allocUseSrc1, allocIsReady = false
          var allocDeps = Set.empty[Int]
          println("2")

          // --- 阶段二: 驱动 ---
          if (do_alloc_attempt) {
            // 生成并暂存指令信息
            allocRobPtr = robPtrCounter; robPtrCounter += 1
            allocUseSrc1 = rand.nextBoolean()
            allocSrc1Tag = rand.nextInt(pCfg.archGprCount)
            allocIsReady = !allocUseSrc1 || rand.nextBoolean()
            allocDeps = if (allocUseSrc1 && !allocIsReady) Set(allocSrc1Tag) else Set.empty[Int]
          println("3")

            // 使用无等待函数驱动硬件
            driveAllocRequest(
              dut.io.allocateIn,
              allocRobPtr,
              pCfg,
              useSrc1Val = allocUseSrc1,
              src1TagVal = allocSrc1Tag,
              src1InitialReadyVal = allocIsReady
            )
          } else {
          println("4")
            dut.io.allocateIn.valid #= false
          }

          val tag_to_wake_opt = model.getNextWakeupTag()
          tag_to_wake_opt.foreach { tag =>
            driveWakeup(dut.io.wakeupIn, 0, tag)
            model.wakeup(tag)
          }
          println("5")

          dut.io.issueOut.ready #= rand.nextInt(10) < 8

          // --- 阶段三: 执行 ---

          // 在时钟沿前采样 ready
          val alloc_was_ready_before_edge = dut.io.allocateIn.ready.toBoolean

          cd.waitSampling()
          println("hello")

          // --- 阶段四: 模型更新与清理 ---

          // 只有在硬件在上一个周期同意接收时，才更新模型
          if (do_alloc_attempt && alloc_was_ready_before_edge) {
            println(s"[MODEL ALLOC] @ ${simTime()}: robPtr=${allocRobPtr}")
            scoreboard.pushRef(allocRobPtr)
            model.allocate(allocRobPtr, allocIsReady, allocDeps)
          } else {
            println(
              s"[MODEL NO-OP] @ ${simTime()} because do_alloc_attempt && alloc_was_ready_before_edge = ${do_alloc_attempt} && ${alloc_was_ready_before_edge}"
            )
          }

          // 清理一次性信号
          dut.io.allocateIn.valid #= false
          if (tag_to_wake_opt.isDefined) {
            deassertWakeup(dut.io.wakeupIn, 0)
          }

          timeout -= 1
        }

        // ====================================================================
        // 5. 排空阶段 (包含对 "model is empty" 错误的修复)
        // ====================================================================
        println(s"--- Draining phase, ${model.size} instructions remaining in model ---")
        dut.io.issueOut.ready #= true

        // 关键修复: 循环的条件是 scoreboard 没有检查完所有指令，而不是 model 是否为空
        while (scoreboard.ref.nonEmpty && timeout > 0) {
          val tagToWakeOpt = model.getNextWakeupTag()
          tagToWakeOpt.foreach { tag =>
            println(s"[Drain] Waking up tag: ${tag} for robPtr=${model.getHeadRobPtr().get}")
            driveWakeup(dut.io.wakeupIn, 0, tag)
            model.wakeup(tag)
          }

          cd.waitSampling()

          if (tagToWakeOpt.isDefined) {
            deassertWakeup(dut.io.wakeupIn, 0)
          }
          timeout -= 1
        }

        // 在所有可能的发射都结束后，再额外等待几个周期，确保最后一个发射被 StreamMonitor 捕获
        cd.waitSampling(5)

        // 最终断言
        assert(timeout > 0, s"Fuzzy test timed out! ${scoreboard.dut.size} instructions still in DUT scoreboard.")
        scoreboard.checkEmptyness()
    }
  }
  thatsAll
}

import scala.collection.mutable
import scala.util.Try

// 模型条目，保持不变
case class ModelIQEntry(robPtr: Int, var isReady: Boolean, dependencies: mutable.HashSet[Int])

/** 一个用于模拟 SequentialIssueQueue 行为的黑盒模型。
  * 它维护一个内部队列，并提供操作来改变其状态。
  */
class SequentialIQModel {

  // 内部状态：一个代表硬件队列的列表缓冲
  private val queue = mutable.ListBuffer[ModelIQEntry]()

  // --- 查询方法 (Queries) ---

  /** 检查模型是否已满 */
  def isFull(depth: Int): Boolean = queue.size >= depth

  /** 检查模型是否为空 */
  def isEmpty: Boolean = queue.isEmpty

  /** 获取下一个需要被唤醒的物理寄存器Tag。
    * 策略是：优先唤醒阻塞队列头部的Tag。
    * @return Option[Int] 如果有需要唤醒的Tag，则返回它
    */
  def getNextWakeupTag(): Option[Int] = {
    queue.headOption.flatMap { head =>
      if (!head.isReady && head.dependencies.nonEmpty) {
        Some(head.dependencies.head)
      } else {
        None
      }
    }
  }

  /** 获取模型队列的当前大小 */
  def size: Int = queue.size

  /** 获取队列头部的 ROB 指针，用于验证 */
  def getHeadRobPtr(): Option[Int] = queue.headOption.map(_.robPtr)

  // --- 状态转换方法 (State Transitions) ---

  /** 向模型中分配一个新指令。
    * @param robPtr 新指令的ROB指针
    * @param initialReady 指令是否初始就绪
    * @param deps 指令的依赖Tag集合
    */
  def allocate(robPtr: Int, initialReady: Boolean, deps: Set[Int]): Unit = {
    val entry = ModelIQEntry(robPtr, initialReady, mutable.HashSet() ++= deps)
    queue.append(entry)
  }

  /** 模拟一个指令被发射。
    * 会从模型队列头部移除一个条目，并进行验证。
    * @param issuedRobPtr 从硬件实际发射的ROB指针
    * @return 成功则返回被移除的条目，失败则抛出异常
    */
  def issue(issuedRobPtr: Int): Try[ModelIQEntry] = Try {
    if (isEmpty) {
      throw new IllegalStateException(s"DUT issued robPtr=${issuedRobPtr} but model is empty.")
    }
    val head = queue.head
    if (head.robPtr != issuedRobPtr) {
      throw new AssertionError(
        s"Model/DUT mismatch! Model expected ${head.robPtr}, DUT issued ${issuedRobPtr}"
      )
    }
    if (!head.isReady) {
      // 打印出导致断言失败的那个条目的完整状态
      val errorMessage = s"""
      |
      |!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      |  ASSERTION FAILED: DUT issued an un-ready instruction!
      |----------------------------------------------------------------
      |  - ROB Pointer        : ${head.robPtr}
      |  - Model 'isReady'      : ${head.isReady}
      |  - Remaining Dependencies : [${head.dependencies.mkString(", ")}]
      |!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      |
      """.stripMargin
      throw new AssertionError(errorMessage)
    }
    queue.remove(0)
  }

  /** 模拟一个唤醒信号。
    * 会遍历整个模型队列，更新所有依赖于此Tag的条目的状态。
    * @param tag 被唤醒的物理寄存器Tag
    */
  def wakeup(tag: Int): Unit = {
    for (entry <- queue) {
      if (entry.dependencies.contains(tag)) {
        entry.dependencies.remove(tag)
        if (entry.dependencies.isEmpty) {
          entry.isReady = true
        }
      }
    }
  }

  /** 打印当前模型状态，用于调试。
    */
  override def toString: String = {
    if (isEmpty) {
      "ModelIQ(empty)"
    } else {
      val entriesStr = queue
        .map { e =>
          s"  Rob(${e.robPtr}), Rdy(${e.isReady}), Deps(${e.dependencies.mkString(",")})"
        }
        .mkString("\n")
      s"ModelIQ(size=${queue.size}):\n$entriesStr"
    }
  }
}
