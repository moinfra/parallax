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
import scala.util.{Random, Try} // 引入 Try
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

  // driveAllocRequest: 使用无等待的变体，以支持反压测试
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
  ): Unit = {
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

    cmd.src1InitialReady #= src1InitialReadyVal
    cmd.src2InitialReady #= src2InitialReadyVal
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

  test("SequentialIQ - Golden Model Fuzzy Test") {
    // 1. 配置
    val IQ_DEPTH = 8 // 使用稍大的深度以增加测试覆盖
    val pCfg = createPipelineConfig(IQ_DEPTH)

    SimConfig.withWave.compile(new SequentialIssueQueueTestBench(pCfg, MOCK_WAKEUP_PORTS, IQ_DEPTH)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)
      initDutIO(dut)

      // 2. 初始化
      val model = new SequentialIQModel(IQ_DEPTH) // 使用新模型
      val rand = new Random(seed = 42)
      var robPtrCounter = 100
      val totalInstructions = 500
      var timeout = totalInstructions * 20 + 2000

      // ====================================================================
      // 3. 主测试循环 (现在是纯粹的激励生成器 + 比较器)
      // ====================================================================
      for (_ <- 0 until timeout if model.getQueueSize < totalInstructions) {

        // --- 阶段一: 激励生成 (只生成输入, 不关心状态) ---

        // a. 分配激励
        val do_alloc = rand.nextInt(10) < 6
        val alloc_payload_model = if (do_alloc) {
          val useSrc1 = rand.nextBoolean()
          val src1Tag = rand.nextInt(pCfg.archGprCount)
          val isReady = !useSrc1 || rand.nextBoolean()
          val deps = if (useSrc1 && !isReady) mutable.HashSet(src1Tag) else mutable.HashSet.empty[Int]
          Some(ModelIQEntry(robPtrCounter, isReady, deps))
        } else {
          None
        }

        // b. 唤醒激励
        val wakeup_tag = if (rand.nextInt(10) < 4) Some(rand.nextInt(pCfg.archGprCount)) else None

        // c. 发射反压激励
        val issue_ready = rand.nextInt(10) < 8

        // --- 阶段二: 驱动硬件和模型 (无脑“扔”激励) ---

        // 驱动硬件
        if (do_alloc) {
          // 只有在确定要分配时，才调用这个函数来驱动一个完整的、有效的请求
          // (valid=true + payload)
          val entry = alloc_payload_model.get
          driveAllocRequest( // 使用我们最初的、好的那个函数
            dut.io.allocateIn,
            entry.robPtr,
            pCfg,
            useSrc1Val = entry.dependencies.nonEmpty,
            src1TagVal = entry.dependencies.headOption.getOrElse(0),
            src1InitialReadyVal = entry.isReady
          )
        } else {
          // 如果不分配，我们只需要确保 valid 信号为 false
          // payload 是什么则无关紧要
          dut.io.allocateIn.valid #= false
        }

// B. 驱动其他硬件信号
        wakeup_tag.foreach(tag => driveWakeup(dut.io.wakeupIn, 0, tag))
        dut.io.issueOut.ready #= issue_ready

// C. 驱动模型 (这部分逻辑不变，因为它已经正确地接收了 do_alloc)
        model.driveInputs(do_alloc, alloc_payload_model, wakeup_tag, issue_ready)

        // --- 阶段三: 时钟步进 ---
        cd.waitSampling()

        // --- 阶段四: 模型状态演化 ---
        // 让模型根据刚刚接收的输入，独立地演化一个周期
        model.cycle()

        // --- 阶段五: 对比 (Checker) ---
        // 在时钟沿之后，比较硬件和模型的所有可见状态和输出
        val time = simTime()

        // a. 对比接口行为
        assert(
          dut.io.allocateIn.ready.toBoolean == model.isReadyToAlloc,
          s"[$time] allocateIn.ready mismatch! DUT: ${dut.io.allocateIn.ready.toBoolean}, Model: ${model.isReadyToAlloc}"
        )

        assert(
          dut.io.issueOut.valid.toBoolean == model.isIssuing,
          s"[$time] issueOut.valid mismatch! DUT: ${dut.io.issueOut.valid.toBoolean}, Model: ${model.isIssuing}"
        )

        // b. 对比功能输出
        if (dut.io.issueOut.valid.toBoolean) {
          val dutPtr = dut.io.issueOut.payload.robPtr.toInt
          val modelPtr = model.getIssuedEntry.get.robPtr
          assert(dutPtr == modelPtr, s"[$time] Issued robPtr mismatch! DUT: $dutPtr, Model: $modelPtr")
        }


        // d. 清理一次性信号 (为下个周期做准备)
        if (wakeup_tag.isDefined) deassertWakeup(dut.io.wakeupIn, 0)

        // 更新计数器
        if (do_alloc && dut.io.allocateIn.ready.toBoolean) {
          robPtrCounter += 1
        }
      }

      // 最终检查
      assert(model.getQueueSize < totalInstructions, s"Test timed out! Completed ${model.getQueueSize} instructions.")
      println(s"Golden Model Fuzzy Test PASSED! Completed ${robPtrCounter - 100} instructions.")
    }
  }

  thatsAll
}

// filename: test/scala/SequentialIQModel.scala
// (为了清晰，可以把它放到一个单独的文件中，或者继续放在Spec文件底部)
// filename: test/scala/SequentialIQModel.scala 
// (或者放在 SequentialIssueQueueSpec.scala 文件底部)

import scala.collection.mutable
import scala.util.Try

// 模型条目定义保持不变
case class ModelIQEntry(robPtr: Int, var isReady: Boolean, dependencies: mutable.HashSet[Int])

/**
 * 一个功能和时序完备的"黄金模型", 用于模拟SequentialIssueQueue的行为。
 * 这个版本精确地模拟了硬件寄存器的“延迟更新”特性，确保与硬件时序一致。
 */
class SequentialIQModel(val depth: Int) {

  // --- 内部状态寄存器 ---
  // `queue` 代表了在每个时钟周期 *开始* 时的硬件队列状态
  private val queue = mutable.ListBuffer[ModelIQEntry]()

  // --- 输入激励的“寄存器” ---
  // 这些变量会在每个周期开始时被测试平台设置
  private var drive_alloc_valid: Boolean = false
  private var drive_alloc_payload: Option[ModelIQEntry] = None
  private var drive_wakeup_tag: Option[Int] = None
  private var drive_issue_ready: Boolean = false

  // --- 组合逻辑输出线 (Wires) ---
  // 这些变量代表了在当前周期内，基于周期开始时状态计算出的组合逻辑输出
  // 它们的值在 cycle() 方法中被计算，并由外部查询方法读取
  private var combinational_isReadyToAlloc: Boolean = false
  private var combinational_isIssuing: Boolean = false
  private var combinational_issuedEntry: Option[ModelIQEntry] = None


  // === 对外暴露的查询方法 (模拟从硬件IO或状态寄存器读取) ===

  /** 模拟硬件的 allocateIn.ready 信号 */
  def isReadyToAlloc: Boolean = this.combinational_isReadyToAlloc

  /** 模拟硬件的 issueOut.valid 信号 */
  def isIssuing: Boolean = this.combinational_isIssuing

  /** 获取本周期模型发射的指令 (模拟 issueOut.payload) */
  def getIssuedEntry: Option[ModelIQEntry] = this.combinational_issuedEntry
  
  /** 获取模型队列的当前大小 (模拟读取 validCountReg) */
  def getQueueSize: Int = this.queue.size


  // === 状态转换 ===

  /**
   * [供测试平台调用] 驱动本周期的所有输入激励。
   * 这模拟了激励在时钟沿到来之前到达硬件的输入端口。
   */
  def driveInputs(
      allocValid: Boolean,
      allocPayload: Option[ModelIQEntry],
      wakeupTag: Option[Int],
      issueReady: Boolean
  ): Unit = {
    this.drive_alloc_valid = allocValid
    this.drive_alloc_payload = allocPayload
    this.drive_wakeup_tag = wakeupTag
    this.drive_issue_ready = issueReady
  }

   /**
   * 模拟一个时钟周期的所有行为，严格遵循硬件时序。
   * 1. 计算当前周期的组合逻辑输出 (基于周期开始时的状态)。
   * 2. 计算下一周期的状态，包含对新分配指令的唤醒前推。
   * 3. 在“时钟沿”更新所有状态。
   */
  def cycle(): Unit = {
    // ======================================================================
    // 阶段一: 计算当前周期的组合逻辑输出 (基于周期开始时的`queue`状态)
    // ======================================================================

    val queueAfterWakeup = mutable.ListBuffer[ModelIQEntry]()
    this.queue.foreach { e => 
      queueAfterWakeup += e.copy(dependencies = e.dependencies.clone())
    }
    
    drive_wakeup_tag.foreach { tag =>
      queueAfterWakeup.foreach { entry =>
        if (entry.dependencies.contains(tag)) {
          entry.dependencies.remove(tag)
          if (entry.dependencies.isEmpty) entry.isReady = true
        }
      }
    }

    this.combinational_isReadyToAlloc = this.queue.size < this.depth

    val headAfterWakeup = queueAfterWakeup.headOption
    if (headAfterWakeup.isDefined && headAfterWakeup.get.isReady && drive_issue_ready) {
      this.combinational_isIssuing = true
      this.combinational_issuedEntry = this.queue.headOption 
    } else {
      this.combinational_isIssuing = false
      this.combinational_issuedEntry = None
    }

    // ======================================================================
    // 阶段二: 根据本周期的事件，计算下一周期的状态
    // ======================================================================
    
    val issue_fired = this.combinational_isIssuing
    val alloc_fired = this.drive_alloc_valid && this.combinational_isReadyToAlloc

    val next_queue = mutable.ListBuffer[ModelIQEntry]()
    next_queue ++= this.queue

    if (issue_fired) {
      next_queue.remove(0)
    }
    if (alloc_fired) {
      // ** 关键修复：模拟“分配-唤醒前推” **
      val new_entry_to_add = this.drive_alloc_payload.get

      // 检查本周期的唤醒信号是否能直接让新指令就绪
      drive_wakeup_tag.foreach { tag =>
          if (new_entry_to_add.dependencies.contains(tag)) {
              new_entry_to_add.dependencies.remove(tag)
              if (new_entry_to_add.dependencies.isEmpty) {
                  new_entry_to_add.isReady = true
              }
          }
      }
      // 将可能已被唤醒的新指令加入队列
      next_queue.append(new_entry_to_add)
    }

    // ======================================================================
    // 阶段三: “时钟沿” - 更新所有状态寄存器
    // ======================================================================
    
    this.queue.clear()
    this.queue ++= next_queue

    if (this.queue.nonEmpty && queueAfterWakeup.nonEmpty) {
      this.queue.find(_.robPtr == queueAfterWakeup.head.robPtr).foreach { entryInNewQueue =>
        val head_after_wakeup = queueAfterWakeup.head
        entryInNewQueue.isReady = head_after_wakeup.isReady
        entryInNewQueue.dependencies.clear()
        entryInNewQueue.dependencies ++= head_after_wakeup.dependencies
      }
    }
  }
}
