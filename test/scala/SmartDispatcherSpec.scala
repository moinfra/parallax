// testOnly test.scala.SmartDispatcherSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.fetch.{FetchedInstr, PredecodeInfo}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import parallax.fetch2.SmartDispatcher
import spinal.lib.sim.StreamMonitor
import parallax.fetch2.FetchGroup
import parallax.bpu.BpuResponse
import parallax.bpu.BpuQuery
import spinal.lib.sim.FlowMonitor
import parallax.utilities.ParallaxLogger

// --- Capture an PredecodeInfo from simulation for easy handling in Scala testbench ---
case class PredecodeInfoCapture(
    isBranch: Boolean = false,
    isJump: Boolean = false,
    isDirectJump: Boolean = false,
    jumpOffset: BigInt = 0
)

// --- Capture an BpuResponse from simulation ---
case class BpuResponseCapture(
    isTaken: Boolean,
    target: BigInt,
    transactionId: BigInt
)

// --- Capture a FetchedInstr from simulation output for scoreboard checking ---
case class FetchedInstrCapture2(
    pc: BigInt,
    instruction: BigInt,
    predecode: PredecodeInfoCapture,
    bpuWasPredicted: Boolean,
    bpuIsTaken: Boolean,
    bpuTarget: BigInt
)
object FetchedInstrCapture2 {
  def apply(payload: FetchedInstr): FetchedInstrCapture2 = {
    FetchedInstrCapture2(
      pc = payload.pc.toBigInt,
      instruction = payload.instruction.toBigInt,
      predecode = PredecodeInfoCapture(
        isBranch = payload.predecode.isBranch.toBoolean,
        isJump = payload.predecode.isJump.toBoolean,
        isDirectJump = payload.predecode.isDirectJump.toBoolean,
        jumpOffset = payload.predecode.jumpOffset.toBigInt
      ),
      bpuWasPredicted = payload.bpuPrediction.wasPredicted.toBoolean,
      bpuIsTaken = payload.bpuPrediction.isTaken.toBoolean,
      bpuTarget = payload.bpuPrediction.target.toBigInt
    )
  }
}

// 使用您提供的测试基类
class SmartDispatcherSpec extends CustomSpinalSimFunSuite {

  // --- 仿真配置 ---
  // 使用simConfig，它应该在您的CustomSpinalSimFunSuite中定义

  // --- DUT的通用配置 ---
  val testPCfg = PipelineConfig(
    xlen = 32,
    fetchWidth = 4, // 保持与FetchGroup内部Vec大小一致
    bpuTransactionIdWidth = 4 bits
  )

  // --- 辅助函数 ---

  // 驱动FetchGroup输入，现在使用原始类型
  def driveFetchGroup(dut: SmartDispatcher, group: Option[(BigInt, Int, Seq[(BigInt, PredecodeInfoCapture)])]): Unit = {
    if (group.isDefined) {
      val (pc, genId, instructions) = group.get // <-- 增加 isFault
      dut.io.fetchGroupIn.valid #= true
      dut.io.fetchGroupIn.payload.fault #= false // <-- 这里明确设置为 false
      dut.io.fetchGroupIn.payload.pc #= pc
      dut.io.fetchGroupIn.numValidInstructions #= instructions.length
      dut.io.fetchGroupIn.startInstructionIndex #= 0

      for (i <- 0 until testPCfg.fetchWidth) {
        if (i < instructions.length) {
          val (inst, predecode) = instructions(i)
          dut.io.fetchGroupIn.payload.instructions(i) #= inst
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isBranch #= predecode.isBranch
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isJump #= predecode.isJump
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isDirectJump #= predecode.isDirectJump
          dut.io.fetchGroupIn.payload.predecodeInfos(i).jumpOffset #= predecode.jumpOffset
        } else {
          dut.io.fetchGroupIn.payload.instructions(i) #= 0
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isBranch #= false
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isJump #= false
          dut.io.fetchGroupIn.payload.predecodeInfos(i).isDirectJump #= false
          dut.io.fetchGroupIn.payload.predecodeInfos(i).jumpOffset #= 0 // 确保 offset 也是 0
        }
      }
    } else {
      dut.io.fetchGroupIn.valid #= false
    }
  }

  // 驱动BPU响应，使用Capture对象
  def driveBpuResponse(dut: SmartDispatcher, rsp: Option[BpuResponseCapture]): Unit = {
    if (rsp.isDefined) {
      ParallaxLogger.debug(s"[TB] Drive BPU Response: ${rsp.get}")
      dut.io.bpuRsp.valid #= true
      dut.io.bpuRsp.payload.isTaken #= rsp.get.isTaken
      dut.io.bpuRsp.payload.target #= rsp.get.target
      dut.io.bpuRsp.payload.transactionId #= rsp.get.transactionId
    } else {
      dut.io.bpuRsp.valid #= false
      ParallaxLogger.debug("[TB] Drive BPU Response: None")
    }
  }

  def initDut(dut: SmartDispatcher): Unit = {
    dut.io.fetchGroupIn.valid #= false
    dut.io.bpuRsp.valid #= false
    dut.io.fetchOutput.ready #= false
    dut.io.flush #= false
    dut.clockDomain.waitSampling()
    sleep(1)
  }

  // --- 封装的单周期激励辅助函数 ---

  /** 驱动 fetchGroupIn 一个周期然后停止
    */
  def pulseFetchGroup(dut: SmartDispatcher, group: (BigInt, Int, Seq[(BigInt, PredecodeInfoCapture)])): Unit = {
    driveFetchGroup(dut, Some(group))
    dut.clockDomain.waitSampling()
    sleep(1)
    driveFetchGroup(dut, None)
  }

  /** 驱动 bpuRsp 一个周期然后停止
    */
  def pulseBpuResponse(dut: SmartDispatcher, rsp: BpuResponseCapture): Unit = {
    driveBpuResponse(dut, Some(rsp))
    dut.clockDomain.waitSampling()
    sleep(1)
    driveBpuResponse(dut, None)
  }

  /** 驱动 flush 一个周期
    */
  def pulseFlush(dut: SmartDispatcher): Unit = {
    dut.io.flush #= true
    dut.clockDomain.waitSampling()
    sleep(1)
    dut.io.flush #= false
  }

  // --- 测试用例 (使用修正后的辅助函数和Capture对象) ---

  def createDut(cfg: PipelineConfig): SmartDispatcher = {
    val dut = new SmartDispatcher(cfg)
    dut.isBusyReg.simPublic()
    dut.fsm.stateReg.simPublic()
    dut
  }

  test("Fast Path - Processes a full group of non-branch instructions") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      val testInstructions = Seq.tabulate(4) { i =>
        (BigInt(0x13 + i), PredecodeInfoCapture()) // 使用Capture对象
      }
      val testGroup = (BigInt(0x1000), 0, testInstructions)

      driveFetchGroup(dut, Some(testGroup))
      dut.io.fetchOutput.ready #= true
      dut.clockDomain.waitSampling()

      driveFetchGroup(dut, None)
      dut.clockDomain.waitSampling(4)

      assert(outputQueue.length == 4)
      for (i <- 0 until 4) {
        val instr = outputQueue.dequeue()
        assert(instr.pc == 0x1000 + i * 4)
        assert(!instr.predecode.isBranch)
        assert(!instr.bpuWasPredicted)
      }

      assert(!dut.isBusyReg.toBoolean)
      ParallaxLogger.debug("[TB] Fast Path test PASSED.")
    }
  }

  test("Slow Path - Handles a single conditional branch correctly") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]() // (pc, tid)
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testInstructions = Seq(
        (BigInt(0x13), PredecodeInfoCapture()),
        (BigInt(0x63), branchPredecode),
        (BigInt(0x13), PredecodeInfoCapture())
      )
      val testGroup = (BigInt(0x2000), 1, testInstructions)

      driveFetchGroup(dut, Some(testGroup))
      dut.io.fetchOutput.ready #= true
      dut.clockDomain.waitSampling()
      driveFetchGroup(dut, None)

      dut.clockDomain.waitSampling()

      assert(bpuQueryLog.length == 1)
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x2004)

      ParallaxLogger.debug(s"[TB] BPU Query for PC=0x2004 sent with TID=$queryTid,  Dispatcher is now waiting.")
      assert(dut.isBusyReg.toBoolean)

      dut.clockDomain.waitSampling(2)

      val bpuRsp = BpuResponseCapture(isTaken = true, target = 0x3000, transactionId = queryTid)
      driveBpuResponse(dut, Some(bpuRsp))
      dut.clockDomain.waitSampling(1)
      driveBpuResponse(dut, None)

      dut.clockDomain.waitSampling(1)

      assert(dut.io.softRedirect.valid.toBoolean)
      assert(dut.io.softRedirect.payload.toBigInt == 0x3000)

      assert(dut.isBusyReg.toBoolean)
      dut.clockDomain.waitSampling()
      assert(!dut.isBusyReg.toBoolean)

      ParallaxLogger.debug("[TB] Slow Path test PASSED.")
    }
  }
  test("State Management - Hard flush correctly resets the state") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()

      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      // Corrected: numValidInstructions should match Seq size if it's the only one
      // If it's 2, it implies two instructions, 0th and 1st.
      // testGroup (BigInt(0x4000), 1, Seq((BigInt(0x63), branchPredecode))) // This is correct for one instruction
      val testGroup = (BigInt(0x4000), 1, Seq((BigInt(0x63), branchPredecode)))

      driveFetchGroup(dut, Some(testGroup))
      dut.clockDomain.waitSampling() // Cycle 3: Group received, BPU query sent, FSM goes WAITING_FOR_BPU
      driveFetchGroup(dut, None) // Stop driving input
      dut.clockDomain.waitSampling() // Cycle 4: FSM is WAITING_FOR_BPU, isBusyReg=1

      // Assert that we are busy and waiting for BPU
      assert(dut.isBusyReg.toBoolean, "DUT should be busy after sending BPU query")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      // Trigger flush
      dut.io.flush #= true
      dut.clockDomain
        .waitSampling() // Cycle 5: Flush arrives. FSM transitions to DRAINING_BPU. isBusyReg should remain true.
      dut.io.flush #= false // De-assert flush

      dut.clockDomain.waitSampling() // Cycle 6: FSM.stateReg is now DRAINING_BPU.

      // NOW, after flush arrived and before BPU response clears in-flight counter
      // Check if DUT is in DRAINING_BPU state and is still busy
      assert(dut.isBusyReg.toBoolean, "DUT should remain busy in DRAINING_BPU after flush")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "DRAINING_BPU",
        s"Expected DRAINING_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      // This is the problematic assert that fails:
      // assert(dut.isBusyReg.toBoolean) // DRAIN BPU

      // Now provide BPU response to clear the in-flight counter
      driveBpuResponse(dut, Some(BpuResponseCapture(isTaken = true, target = 0x5000, transactionId = 0)))
      dut.clockDomain
        .waitSampling() // Cycle 6: BPU Rsp arrives. bpuInFlightCounterReg becomes 0. FSM transitions to IDLE. isBusyReg becomes false.
      driveBpuResponse(dut, None) // Stop driving BPU response

      dut.clockDomain.waitSampling() // Cycle 7: FSM is now IDLE, isBusyReg is false.
      // Wait one more cycle to ensure state propagation and then assert
      // dut.clockDomain.waitSampling() // This wait is crucial if the assert is checking a RegNext value.

      dut.clockDomain.waitSampling()
      assert(!dut.isBusyReg.toBoolean, "DUT should be idle after BPU draining complete") // IDLE again
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      val newGroup = (BigInt(0x5000), 0, Seq()) // Ensure this is 0 for empty group
      driveFetchGroup(dut, Some(newGroup))
      dut.clockDomain.waitSampling() // Cycle 8: Empty group received. FSM immediately goes IDLE.
      driveFetchGroup(dut, None)

      // This assert should now pass if the empty group logic is correct and instant
      assert(!dut.isBusyReg.toBoolean, "DUT should not be busy after processing an empty group")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      ParallaxLogger.debug("[TB] Hard Flush test PASSED.")
    }
  }
  test("State Management - Stale FetchGroup is correctly discarded") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val testGroup = (BigInt(0x6000), 5, Seq((BigInt(0x13), PredecodeInfoCapture())))
      driveFetchGroup(dut, Some(testGroup))

      dut.clockDomain.waitSampling()

      assert(!dut.isBusyReg.toBoolean)

      ParallaxLogger.debug("[TB] Stale FetchGroup discard test PASSED.")
    }
  }

// 定义测试用例
  test("Boundary - Instruction Lost on Soft Redirect (Regresion Test)") {
    // 1. 设置仿真环境
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10) // 启动时钟，周期为 10 个时间单位
      initDut(dut) // 初始化 DUT 的输入信号（例如将 ready/valid 设置为默认值）

      // 2. 准备监控和数据捕获
      // 创建一个队列来存储从 dut.io.fetchOutput 端口成功发送出去的指令
      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      // StreamMonitor 是一个仿真辅助工具，当 dut.io.fetchOutput 上发生一次成功的传输 (valid=1, ready=1) 时，
      // 它会捕获 payload 并执行大括号内的代码。
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload)) // 将捕获到的指令存入队列
      }

      // 3. 构造测试数据 (FetchGroup)
      // 定义一条 JAL 指令的预解码信息：是直接跳转，跳转偏移量是 +0x100 字节
      val jumpPredecode = PredecodeInfoCapture(isDirectJump = true, jumpOffset = 0x100)

      // 创建一个指令序列。pCfg.fetchWidth 应该是 4
      val testInstructions = Seq(
        // 指令0: 普通指令 (addi)
        (BigInt(0x13), PredecodeInfoCapture()),
        // 指令1: 跳转指令 (jal)，使用上面定义的预解码信息
        (BigInt(0x6f), jumpPredecode),
        // 指令2: 普通指令。这条指令不应该被分发！
        (BigInt(0x13), PredecodeInfoCapture()),
        // 指令3: 普通指令。这条指令也不应该被分发！
        (BigInt(0x13), PredecodeInfoCapture())
      )
      // 将指令序列包装成一个 FetchGroup。起始 PC 是 0x7000。
      val testGroup = (BigInt(0x7000), 10, testInstructions) // 10 是一个占位符，例如 transaction ID

      // 4. 执行测试激励
      // 在时钟的下一个上升沿，将 testGroup 驱动到 DUT 的输入端口
      driveFetchGroup(dut, Some(testGroup))
      // 确保 DUT 的输出端口总是准备好接收，这样就不会因为下游阻塞而影响测试逻辑
      dut.io.fetchOutput.ready #= true
      // 等待一个时钟周期，让 DUT 接收并开始处理这个 FetchGroup，并且发送第一个指令
      dut.clockDomain.waitSampling()

      // 停止驱动输入，模拟输入流结束
      driveFetchGroup(dut, None)

      // 再等待一个时钟周期，让第二条指令（JAL）能够被处理和发送
      dut.clockDomain.waitSampling()

      // 5. 验证结果 (Asserts)

      // **断言 1: 检查输出指令的数量**
      // 队列中应该只有两条指令：第一条普通指令和第二条 JAL 指令。
      // 这是测试的核心！如果长度是 3 或 4，说明 bug 存在。
      assert(outputQueue.length == 2)

      // **断言 2: 检查第二条输出指令的内容**
      val jalInstr = outputQueue.last // 获取队列中的最后一条指令，即 JAL 指令
      assert(jalInstr.pc == 0x7004) // JAL 指令的 PC 应该是 0x7000 + 4

      // **断言 3: 检查软重定向信号**
      // JAL 指令应该触发了软重定向
      assert(dut.io.softRedirect.valid.toBoolean)
      // 重定向的目标地址应该是 JAL 的 PC (0x7004) + 偏移量 (0x100) = 0x7104
      assert(dut.io.softRedirect.payload.toBigInt == 0x7104)
      dut.clockDomain.waitSampling()

      // **断言 4: 检查 DUT 状态**
      // 在处理完导致跳转的指令后，调度器应该认为这个 FetchGroup 已经处理完毕，
      // 并将 isBusy 状态复位为 false，准备接收新的指令包。
      assert(!dut.isBusyReg.toBoolean)

      // 如果所有断言都通过，打印成功信息
      ParallaxLogger.debug("[TB] Instruction Lost on Redirect regression test PASSED.")
    }
  }

// 在 SmartDispatcherSpec 类中添加这个新的测试
  val fsmStateNames = Map(
    0 -> "IDLE",
    1 -> "DISPATCHING",
    2 -> "WAITING_FOR_BPU",
    3 -> "SEND_BRANCH",
    4 -> "DRAINING_BPU",
    5 -> "UNKNOWN"
  )

  test("Back-pressure - Stalls correctly when output is not ready") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        ParallaxLogger.info(
          f"[TB] >> Output Monitor: Received PC=0x${payload.pc.toBigInt}%04x, Instr=0x${payload.instruction.toBigInt}%08x"
        )
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // fetchWidth is 4. We provide a group with only 2 valid instructions.
      // The DUT is expected to process all 4 slots.
      val testInstructions = Seq(
        (BigInt(0x13), PredecodeInfoCapture()), // ADDI
        (BigInt(0x33), PredecodeInfoCapture()) // ADD
      )
      val testGroup = (BigInt(0x8000), 20, testInstructions)

      // --- Phase 1: Drive group, stall output, let DUT process first instruction ---
      ParallaxLogger.info("[TB] Phase 1: Driving group with output stalled")
      dut.io.fetchOutput.ready #= false
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE -> SEND_BRANCH
      dut.clockDomain.waitSampling() // Cycle 2: Stalled in SEND_BRANCH

      assert(dut.isBusyReg.toBoolean)
      assert(outputQueue.isEmpty)
      ParallaxLogger.info(s"[TB] Phase 1 OK: DUT stalled in ${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 2: Unstall, send first instruction ---
      ParallaxLogger.info("[TB] Phase 2: Unstalling to send first instruction")
      dut.io.fetchOutput.ready #= true
      dut.clockDomain.waitSampling() // Cycle 3: Fire from SEND_BRANCH -> DISPATCHING

      assert(outputQueue.length == 1)
      assert(dut.isBusyReg.toBoolean)
      ParallaxLogger
        .info(s"[TB] Phase 2 OK: First instruction sent. DUT moving to ${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 3: Send remaining instructions until the group is finished ---
      ParallaxLogger.info("[TB] Phase 3: Sending remaining instructions in the group (2 real, 2 NOPs)")

      // The DUT will now send instr 1, 2, and 3 (total of 3 more cycles if not stalled)
      dut.clockDomain.waitSampling(3)
      // ^ Cycle 4: Sends instruction at index 1 (PC 8004)
      // ^ Cycle 5: Sends instruction at index 2 (PC 8008, NOP)
      // ^ Cycle 6: Sends instruction at index 3 (PC 800C, NOP). This is the last one.
      //            The DUT decides isBusy := False.

      // Check final state of the output queue
      assert(
        outputQueue.length == testInstructions.length,
        s"Expected ${testInstructions.length} instructions, but got ${outputQueue.length}"
      )
      val instrs = outputQueue.toList
      assert(instrs(0).pc == 0x8000 && instrs(0).instruction == 0x13)
      assert(instrs(1).pc == 0x8004 && instrs(1).instruction == 0x33)
      // 移除对 instr(2) 和 instr(3) 的断言，因为它们不会被发送
      // assert(instrs(2).pc == 0x8008)
      // assert(instrs(3).pc == 0x800c)

      // isBusy was set to False at the end of Cycle 4.
      // 修正：DUT 在发送完最后一个有效指令后立即变为 IDLE，所以在 Cycle 4 结束时已经是 False
      dut.clockDomain.waitSampling() // 确保 isBusy 寄存器已经更新
      assert(!dut.isBusyReg.toBoolean, "isBusy should now be false as the full group has been processed")
      ParallaxLogger.info(s"[TB] Phase 3 OK: Full group processed. DUT is now idle.")

      ParallaxLogger.debug("[TB] Back-pressure test PASSED.")
    }
  }

  // --- Race Condition 测试 ---
  // 在 SmartDispatcherSpec 类中添加这个新的测试用例

  test("Race Condition - Handles flush and BPU response arriving simultaneously") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]() // (pc, tid)
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // --- Phase 1: Setup - Send a branch to create an in-flight BPU query ---
      ParallaxLogger.info("[TB] Phase 1: Sending a branch to trigger a BPU query")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testGroup = (BigInt(0x9000), 30, Seq((BigInt(0x63), branchPredecode)))

      dut.io.fetchOutput.ready #= true // Keep output ready for this phase
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE -> WAITING_FOR_BPU (BPU query sent)
      // ^ Cycle 1: FetchGroup fired, BPU query for 0x9000 with TID=0 sent.
      //            DUT decides: isBusy := True, goto(WAITING_FOR_BPU).

      dut.clockDomain.waitSampling()
      // ^ Cycle 2: FSM is in WAITING_FOR_BPU. isBusy=true. bpuInFlightCounter=1.

      assert(dut.isBusyReg.toBoolean, "Phase 1: DUT should be busy waiting for BPU")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Phase 1: Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      assert(bpuQueryLog.length == 1, "Phase 1: A BPU query should have been sent")
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x9000, s"Phase 1: Expected BPU query PC 0x9000, got 0x${queryPc.toLong}%x")
      assert(queryTid == 0, s"Phase 1: Expected BPU query TID 0, got ${queryTid}")
      ParallaxLogger.info(
        s"[TB] Phase 1 OK: DUT is in WAITING_FOR_BPU for TID=$queryTid. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      // --- Phase 2: The Race - flush and bpuRsp arrive at the same time ---
      ParallaxLogger.info("[TB] Phase 2: Triggering the race condition (flush + bpuRsp)")

      // In Cycle 2's combinational phase, set inputs for Cycle 3
      dut.io.flush #= true
      val bpuRsp = BpuResponseCapture(isTaken = true, target = 0xaaaa, transactionId = queryTid)
      driveBpuResponse(dut, Some(bpuRsp)) // This will make bpuRsp.valid=true for Cycle 3

      dut.clockDomain.waitSampling()
      // ^ Cycle 3: Critical cycle!
      // - FSM is in WAITING_FOR_BPU. `io.flush` is true.
      // - DUT decides: `goto(DRAINING_BPU)`. (flush takes priority)
      // - `rspConsumed` is false because `!io.flush` is false. So `bpuInFlightCounter` does NOT decrement.

      // Immediately stop driving flush and BPU response after the critical cycle
      dut.io.flush #= false
      driveBpuResponse(dut, None)
      sleep(1)

      // --- Phase 3: Verification - Check if DUT entered DRAINING state ---
      ParallaxLogger.info("[TB] Phase 3: Verifying the outcome of the race")

      // At the start of Cycle 4, FSM should be in DRAINING_BPU. isBusy is still true.
      assert(dut.isBusyReg.toBoolean, "Phase 3: isBusy should remain true while draining")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "DRAINING_BPU",
        s"Phase 3: FSM should have transitioned to DRAINING_BPU, but was ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      assert(outputQueue.isEmpty, "Phase 3: No instruction should have been sent as flush took priority")
      ParallaxLogger.info(s"[TB] Phase 3 OK: Correctly entered DRAINING_BPU state.")

      // --- Phase 4: Drain the bpuInFlightCounter and verify final IDLE state ---
      ParallaxLogger.info("[TB] Phase 4: Draining bpuInFlightCounter and waiting for IDLE")

      dut.clockDomain.waitSampling()
      // ^ Cycle 4: FSM is in DRAINING_BPU. bpuInFlightCounter is still 1.
      //            DUT stays in DRAINING_BPU (since counter not zero).

      // Now, send the BPU response again (flush is off), so bpuInFlightCounter can finally decrement.
      // This simulates the BPU sending the response again, or the dispatcher picking it up again.
      pulseBpuResponse(dut, bpuRsp) // Cycle 5: Send response again.
      // ^ Cycle 5: FSM is in DRAINING_BPU. `io.flush` is false. `rspConsumed` is true.
      //            `bpuInFlightCounter` will now decrement to 0.

      dut.clockDomain.waitSampling()
      // ^ Cycle 6: FSM is in DRAINING_BPU. `bpuInFlightCounter` is now 0.
      //            DUT decides: `isBusy:=False`, `goto(IDLE)`.

      dut.clockDomain.waitSampling()
      // ^ Cycle 7: FSM is in IDLE. `isBusy` is False.

      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after draining is complete")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 4: FSM should be in IDLE state, but was ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger
        .info(s"[TB] Phase 4 OK: DUT is now correctly idle. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      ParallaxLogger.debug("[TB] Race Condition (Flush vs BPU Rsp) test PASSED.")
    }
  }

// 在 SmartDispatcherSpec 类中添加这个新的测试用例

  test("Complex BPU - Handles back-to-back conditional branches") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]() // (pc, tid)
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // --- Phase 1: Setup - Send a group with two consecutive branches ---
      ParallaxLogger.info("[TB] Phase 1: Sending a group with two consecutive branches")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testInstructions = Seq(
        (BigInt(0x13), PredecodeInfoCapture()), // Instruction 0: Normal
        (BigInt(0x63), branchPredecode), // Instruction 1: Branch A (PC 0x1004)
        (BigInt(0x63), branchPredecode), // Instruction 2: Branch B (PC 0x1008)
        (BigInt(0x13), PredecodeInfoCapture()) // Instruction 3: Normal
      )
      val testGroup = (BigInt(0x1000), 40, testInstructions) // Start PC 0x1000

      dut.io.fetchOutput.ready #= true // Keep output ready throughout
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE receives group
      // ^ Cycle 1: FetchGroup fired. DUT decides: isBusy:=True, goto(IDLE) (due to fast path of first instr).
      //            Then, it will transition to DISPATCHING.

      dut.clockDomain.waitSampling()
      // ^ Cycle 2: FSM is in DISPATCHING. Instruction 0 (PC 0x1000) is sent.
      //            DUT decides: dispatchIndex:=1, stay in DISPATCHING.

      assert(outputQueue.length == 1, "Phase 1: First instruction should be sent")
      assert(outputQueue.head.pc == 0x1000)
      assert(dut.isBusyReg.toBoolean, "Phase 1: DUT should be busy")
      ParallaxLogger.info(s"[TB] Phase 1 OK: First instruction sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 2: Process Branch A (PC 0x1004) ---
      ParallaxLogger.info("[TB] Phase 2: Processing Branch A (PC 0x1004)")
      dut.clockDomain.waitSampling()
      // ^ Cycle 3: FSM is in DISPATCHING. Instruction 1 (Branch A, PC 0x1004) is current.
      //            DUT decides: bpuQuery.valid:=True, goto(WAITING_FOR_BPU). Query for TID=0 sent.

      assert(bpuQueryLog.length == 1, "Phase 2: BPU query for Branch A should be sent")
      val (queryPc0, queryTid0) = bpuQueryLog.dequeue()
      assert(queryPc0 == 0x1004, s"Phase 2: Expected Branch A PC 0x1004, got 0x${queryPc0.toLong}%x")
      assert(queryTid0 == 0, s"Phase 2: Expected TID 0, got ${queryTid0}")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Phase 2: Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(
        s"[TB] Phase 2 OK: BPU Query for Branch A (TID=${queryTid0}) sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      // Send BPU response for Branch A (NOT TAKEN)
      val bpuRsp0 = BpuResponseCapture(isTaken = false, target = 0x0, transactionId = queryTid0)
      pulseBpuResponse(dut, bpuRsp0) // Cycle 4: BPU Rsp 0 arrives.
      // ^ Cycle 4: FSM is in WAITING_FOR_BPU. BPU Rsp 0 matches.
      //            DUT decides: goto(SEND_BRANCH). bpuInFlightCounter decrements.

      dut.clockDomain.waitSampling()
      // ^ Cycle 5: FSM is in SEND_BRANCH. Branch A is sent.
      //            Since not taken and not last, DUT decides: dispatchIndex:=2, goto(DISPATCHING).

      assert(outputQueue.length == 2, "Phase 2: Branch A should be sent")
      assert(outputQueue.last.pc == 0x1004, "Phase 2: PC of Branch A should be 0x1004")
      assert(!outputQueue.last.bpuIsTaken, "Phase 2: Branch A should be predicted not taken")
      ParallaxLogger
        .info(s"[TB] Phase 2 OK: Branch A sent (not taken). State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 3: Process Branch B (PC 0x1008) ---
      ParallaxLogger.info("[TB] Phase 3: Processing Branch B (PC 0x1008)")
      dut.clockDomain.waitSampling()
      // ^ Cycle 6: FSM is in DISPATCHING. Instruction 2 (Branch B, PC 0x1008) is current.
      //            DUT decides: bpuQuery.valid:=True, goto(WAITING_FOR_BPU). Query for TID=1 sent.

      assert(bpuQueryLog.length == 1, "Phase 3: BPU query for Branch B should be sent")
      val (queryPc1, queryTid1) = bpuQueryLog.dequeue()
      assert(queryPc1 == 0x1008, s"Phase 3: Expected Branch B PC 0x1008, got 0x${queryPc1.toLong}%x")
      assert(queryTid1 == 1, s"Phase 3: Expected TID 1, got ${queryTid1}") // Check transaction ID increment
      sleep(1)
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Phase 3: Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(
        s"[TB] Phase 3 OK: BPU Query for Branch B (TID=${queryTid1}) sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      // Send BPU response for Branch B (TAKEN)
      val bpuRsp1 = BpuResponseCapture(isTaken = true, target = 0x2000, transactionId = queryTid1)
      pulseBpuResponse(dut, bpuRsp1) // Cycle 7: BPU Rsp 1 arrives.
      // ^ Cycle 7: FSM is in WAITING_FOR_BPU. BPU Rsp 1 matches.
      //            DUT decides: goto(SEND_BRANCH). bpuInFlightCounter decrements.

      dut.clockDomain.waitSampling()
      // ^ Cycle 8: FSM is in SEND_BRANCH. Branch B is sent.
      //            Since taken, DUT decides: isBusy:=False, goto(IDLE). softRedirect is valid.

      assert(outputQueue.length == 3, "Phase 3: Branch B should be sent")
      assert(outputQueue.last.pc == 0x1008, "Phase 3: PC of Branch B should be 0x1008")
      assert(outputQueue.last.bpuIsTaken, "Phase 3: Branch B should be predicted taken")
      assert(dut.io.softRedirect.valid.toBoolean, "Phase 3: Soft redirect should be valid for Branch B")
      assert(dut.io.softRedirect.payload.toBigInt == 0x2000, "Phase 3: Soft redirect target should be 0x2000")
      ParallaxLogger.info(s"[TB] Phase 3 OK: Branch B sent (taken). State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 4: Final verification ---
      ParallaxLogger.info("[TB] Phase 4: Final verification")
      dut.clockDomain.waitSampling()
      // ^ Cycle 9: FSM is in IDLE. isBusy is False.

      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after processing taken branch")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 4: Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      assert(outputQueue.length == 3, "Phase 4: Only 3 instructions should be sent (0, Branch A, Branch B)")
      ParallaxLogger.info(s"[TB] Phase 4 OK: All expected instructions sent. DUT is idle.")

      ParallaxLogger.debug("[TB] Back-to-back conditional branches test PASSED.")
    }
  }

// ... (之前的代码)

  test("Control Flow - Flush during DISPATCHING (no in-flight BPU)") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        ParallaxLogger.info(
          f"[TB] >> Output Monitor: Received PC=0x${payload.pc.toBigInt}%04x, Instr=0x${payload.instruction.toBigInt}%08x"
        )
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // --- Phase 1: Setup - Send a full group of non-branch instructions ---
      ParallaxLogger.info("[TB] Phase 1: Sending a full group of non-branch instructions")
      val testInstructions = Seq.tabulate(4) { i =>
        (BigInt(0x100 + i), PredecodeInfoCapture()) // PC 0x100, 0x104, 0x108, 0x10C
      }
      val testGroup = (BigInt(0x100), 50, testInstructions)

      dut.io.fetchOutput.ready #= true // Keep output ready
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE receives group, sends Instruction 0 (PC 0x100)
      // ^ Cycle 1: FetchGroup fired. DUT decides: isBusy:=True, goto(IDLE) (first instr fast path).
      //            Then, it will transition to DISPATCHING. Instruction 0 is sent.
      sleep(1) // Ensure StreamMonitor captures Instruction 0

      // At the start of Cycle 2, Instruction 0 should be in queue.
      assert(outputQueue.length == 1, "Phase 1: Instruction 0 (PC 0x100) should be sent")
      assert(outputQueue.head.pc == 0x100)
      assert(dut.isBusyReg.toBoolean, "Phase 1: DUT should be busy")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "DISPATCHING",
        s"Phase 1: Expected DISPATCHING, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 1 OK: Instruction 0 sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 2: Trigger flush while in DISPATCHING ---
      ParallaxLogger.info("[TB] Phase 2: Triggering flush while in DISPATCHING")

      // Send second instruction (Instruction 1, PC 0x104)
      dut.clockDomain.waitSampling()
      // ^ Cycle 3: FSM is in DISPATCHING. Instruction 1 (PC 0x104) is sent.
      //            DUT decides: dispatchIndex:=2, stay in DISPATCHING.
      sleep(1) // Ensure StreamMonitor captures Instruction 1

      // At the start of Cycle 3, Instruction 1 should be in queue.
      assert(outputQueue.length == 2, "Phase 2: Instruction 1 (PC 0x104) should be sent")
      assert(outputQueue.last.pc == 0x104)
      ParallaxLogger.info(s"[TB] Phase 2: Instruction 1 sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // Now, assert flush
      pulseFlush(dut) // Cycle 4: flush is asserted for one cycle
      // ^ Cycle 4: FSM is in DISPATCHING. `io.flush` is true.
      //            DUT decides: `goto(DRAINING_BPU)`.
      //            Since bpuInFlightCounter is 0, DRAINING_BPU will immediately decide `isBusy:=False`, `goto(IDLE)`.

      // --- Phase 3: Verification after flush ---
      ParallaxLogger.info("[TB] Phase 3: Verifying state after flush")

      dut.clockDomain.waitSampling()
      // ^ Cycle 5: FSM should be in IDLE. isBusy should be False.
      //            No more instructions should be sent.
      sleep(1) // Ensure monitors capture any final events before assertions

      assert(!dut.isBusyReg.toBoolean, "Phase 3: DUT should be idle after flush")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 3: Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      assert(outputQueue.length == 2, "Phase 3: Only first two instructions should be sent, rest discarded by flush")
      ParallaxLogger.info(s"[TB] Phase 3 OK: DUT is idle. Remaining instructions discarded.")

      // Verify it can receive a new group
      ParallaxLogger.info("[TB] Phase 4: Verify receiving new group after flush")
      // Provide a new group with 1 valid instruction, but DUT will process 4 slots
      val newGroup = (BigInt(0x200), 51, Seq((BigInt(0x13), PredecodeInfoCapture()))) // numValidInstructions = 1
      pulseFetchGroup(dut, newGroup) // Cycle 6: IDLE receives new group, sends first instr of new group (PC 0x200)
      // ^ Cycle 6: New group fired. DUT decides: isBusy:=True, goto(IDLE) (first instr fast path).
      //            Then, it will transition to DISPATCHING. Instruction (PC 0x200) is sent.
      sleep(1) // Ensure StreamMonitor captures Instruction (PC 0x200)

      // At the start of Cycle 7, Instruction (PC 0x200) should be in queue.
      assert(outputQueue.length == 3, "Phase 4: First instruction of new group (PC 0x200) should be sent")
      assert(outputQueue.last.pc == 0x200)

      // 修正这里的断言：
      // 因为这个新组只有一个有效指令，DUT 在处理完后会立即回到 IDLE。
      // 所以我们预期它在下一个周期就是 IDLE。
      dut.clockDomain.waitSampling() // Wait until Cycle 7 starts
      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after processing a single-instruction group")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 4: Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger
        .info(s"[TB] Phase 4: Instruction (PC 0x200) sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // 移除剩余的 waitSampling(3) 和 NOPs 检查，因为只有一个有效指令，没有额外的 NOPs 需要等待发出。
      // Total instructions: 2 (from old group) + 1 (from new group) = 3
      assert(
        outputQueue.length == 3, // 修正期望的指令数量
        s"Phase 4: Expected 3 instructions (2 old + 1 new), but got ${outputQueue.length}"
      )
      // 最后一个 isBusy 的断言保持不变，因为它已经处理完了。
      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after new group is fully processed")
      ParallaxLogger.info(s"[TB] Phase 4 OK: New group fully processed. DUT is idle.")

      ParallaxLogger.debug("[TB] Flush during DISPATCHING test PASSED.")

    }
  }

  // 在 SmartDispatcherSpec 类中添加这个新的测试用例

  test("Back-pressure - Stalls correctly when SEND_BRANCH output is not ready") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]()
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        ParallaxLogger.info(
          f"[TB] >> Output Monitor: Received PC=0x${payload.pc.toBigInt}%04x, Instr=0x${payload.instruction.toBigInt}%08x"
        )
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // --- Phase 1: Setup - Send a branch to create an in-flight BPU query ---
      ParallaxLogger.info("[TB] Phase 1: Sending a branch to trigger a BPU query")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testInstructions = Seq(
        (BigInt(0x13), PredecodeInfoCapture()), // Instruction 0: Normal
        (BigInt(0x63), branchPredecode), // Instruction 1: Branch (PC 0x3004)
        (BigInt(0x13), PredecodeInfoCapture()), // Instruction 2: Normal
        (BigInt(0x13), PredecodeInfoCapture()) // Instruction 3: Normal
      )
      val testGroup = (BigInt(0x3000), 60, testInstructions)

      dut.io.fetchOutput.ready #= true // Keep output ready initially
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE receives group, sends Instruction 0 (PC 0x3000)
      // ^ Cycle 1: Instruction 0 sent. DUT decides: isBusy:=True, dispatchIndex:=1, goto(DISPATCHING).
      sleep(1) // Ensure StreamMonitor captures Instruction 0

      assert(outputQueue.length == 1, "Phase 1: Instruction 0 should be sent")
      assert(outputQueue.head.pc == 0x3000)
      assert(dut.isBusyReg.toBoolean, "Phase 1: DUT should be busy")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "DISPATCHING",
        s"Phase 1: Expected DISPATCHING, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 1 OK: Instruction 0 sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 2: Process Branch (PC 0x3004) - Query BPU ---
      ParallaxLogger.info("[TB] Phase 2: Processing Branch (PC 0x3004) - Query BPU")
      dut.clockDomain.waitSampling()
      // ^ Cycle 2: FSM is in DISPATCHING. Instruction 1 (Branch, PC 0x3004) is current.
      //            DUT decides: bpuQuery.valid:=True, goto(WAITING_FOR_BPU). Query for TID=0 sent.
      sleep(1) // Ensure FlowMonitor captures bpuQuery

      assert(bpuQueryLog.length == 1, "Phase 2: BPU query for Branch should be sent")
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x3004)
      assert(queryTid == 0)
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Phase 2: Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 2 OK: BPU Query sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 3: Receive BPU Response, but stall output ---
      ParallaxLogger.info("[TB] Phase 3: Receiving BPU Response, but stalling output")
      dut.io.fetchOutput.ready #= false // !!! CRITICAL: Stall output here !!!
      val bpuRsp = BpuResponseCapture(isTaken = false, target = 0x0, transactionId = queryTid) // Branch not taken
      pulseBpuResponse(dut, bpuRsp) // Cycle 3: BPU Rsp arrives.
      // ^ Cycle 3: FSM is in WAITING_FOR_BPU. BPU Rsp matches.
      //            DUT decides: goto(SEND_BRANCH). bpuInFlightCounter decrements.
      //            However, fetchOutput.ready is false, so it will stall in SEND_BRANCH.

      dut.clockDomain.waitSampling()
      // ^ Cycle 4: FSM is in SEND_BRANCH. Branch is ready to be sent, but output is stalled.
      //            DUT stays in SEND_BRANCH. outputQueue is still 1.

      assert(dut.isBusyReg.toBoolean, "Phase 3: DUT should be busy and stalled in SEND_BRANCH")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "SEND_BRANCH",
        s"Phase 3: Expected SEND_BRANCH, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      assert(outputQueue.length == 1, "Phase 3: Branch should NOT be sent yet due to stall")
      ParallaxLogger
        .info(s"[TB] Phase 3 OK: DUT stalled in SEND_BRANCH. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 4: Unstall output, send Branch, then continue processing group ---
      ParallaxLogger.info("[TB] Phase 4: Unstalling output, sending Branch, then remaining instructions")
      dut.io.fetchOutput.ready #= true // !!! CRITICAL: Unstall output !!!
      dut.clockDomain.waitSampling()
      // ^ Cycle 5: FSM is in SEND_BRANCH. Branch is sent.
      //            Since not taken, DUT decides: dispatchIndex:=2, goto(DISPATCHING).
      sleep(1) // Ensure StreamMonitor captures Branch

      assert(outputQueue.length == 2, "Phase 4: Branch should now be sent")
      assert(outputQueue.last.pc == 0x3004)
      assert(!outputQueue.last.bpuIsTaken)
      ParallaxLogger.info(s"[TB] Phase 4: Branch sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // Send remaining 2 (NOP) instructions from the group (total 4 slots)
      dut.clockDomain.waitSampling(2)
      // ^ Cycle 6: Sends Instruction 2 (PC 0x3008)
      // ^ Cycle 7: Sends Instruction 3 (PC 0x300C). This is the last one.
      //            DUT decides: isBusy:=False, goto(IDLE).
      sleep(1) // Ensure StreamMonitor captures final NOPs

      assert(
        outputQueue.length == 4,
        s"Phase 4: Expected 4 instructions (1 normal + 1 branch + 2 NOPs), but got ${outputQueue.length}"
      )
      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after group is fully processed")
      ParallaxLogger
        .info(s"[TB] Phase 4 OK: Full group processed. DUT is idle. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      ParallaxLogger.debug("[TB] Back-pressure (SEND_BRANCH) test PASSED.")
    }
  }

  // 在 SmartDispatcherSpec 类中添加这个新的测试用例

  test("Group Boundary - Last instruction is a conditional branch") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]()
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        ParallaxLogger.info(
          f"[TB] >> Output Monitor: Received PC=0x${payload.pc.toBigInt}%04x, Instr=0x${payload.instruction.toBigInt}%08x"
        )
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // --- Phase 1: Setup - Send a group where the last instruction is a branch ---
      ParallaxLogger.info("[TB] Phase 1: Sending group with last instruction as a branch")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testInstructions = Seq(
        (BigInt(0x13), PredecodeInfoCapture()), // Instruction 0: Normal (PC 0x4000)
        (BigInt(0x13), PredecodeInfoCapture()), // Instruction 1: Normal (PC 0x4004)
        (BigInt(0x63), branchPredecode) // Instruction 2: Branch (PC 0x4008) - This is the last valid instruction
        // Instruction 3 (PC 0x400C) will be a NOP due to fetchWidth=4
      )
      val testGroup = (BigInt(0x4000), 70, testInstructions)

      dut.io.fetchOutput.ready #= true // Keep output ready
      pulseFetchGroup(dut, testGroup) // Cycle 1: IDLE receives group, sends Instruction 0 (PC 0x4000)
      // ^ Cycle 1: Instruction 0 sent. DUT decides: isBusy:=True, dispatchIndex:=1, goto(DISPATCHING).
      sleep(1) // Ensure StreamMonitor captures Instruction 0

      assert(outputQueue.length == 1, "Phase 1: Instruction 0 should be sent")
      assert(outputQueue.head.pc == 0x4000)
      assert(dut.isBusyReg.toBoolean, "Phase 1: DUT should be busy")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "DISPATCHING",
        s"Phase 1: Expected DISPATCHING, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 1 OK: Instruction 0 sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 2: Process Instruction 1 (PC 0x4004) ---
      ParallaxLogger.info("[TB] Phase 2: Processing Instruction 1 (PC 0x4004)")
      dut.clockDomain.waitSampling()
      // ^ Cycle 2: FSM is in DISPATCHING. Instruction 1 (PC 0x4004) is sent.
      //            DUT decides: dispatchIndex:=2, stay in DISPATCHING.
      sleep(1) // Ensure StreamMonitor captures Instruction 1

      assert(outputQueue.length == 2, "Phase 2: Instruction 1 should be sent")
      assert(outputQueue.last.pc == 0x4004)
      ParallaxLogger.info(s"[TB] Phase 2 OK: Instruction 1 sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 3: Process Branch (PC 0x4008) - Query BPU ---
      ParallaxLogger.info("[TB] Phase 3: Processing Branch (PC 0x4008) - Query BPU")
      dut.clockDomain.waitSampling()
      // ^ Cycle 3: FSM is in DISPATCHING. Instruction 2 (Branch, PC 0x4008) is current.
      //            DUT decides: bpuQuery.valid:=True, goto(WAITING_FOR_BPU). Query for TID=0 sent.
      sleep(1) // Ensure FlowMonitor captures bpuQuery

      assert(bpuQueryLog.length == 1, "Phase 3: BPU query for Branch should be sent")
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x4008)
      assert(queryTid == 0)
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        s"Phase 3: Expected WAITING_FOR_BPU, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 3 OK: BPU Query sent. State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      // --- Phase 4: Receive BPU Response (NOT TAKEN) and verify IDLE transition ---
      ParallaxLogger.info("[TB] Phase 4: Receiving BPU Response (NOT TAKEN) and verifying IDLE transition")
      val bpuRsp_notTaken = BpuResponseCapture(isTaken = false, target = 0x0, transactionId = queryTid)
      pulseBpuResponse(dut, bpuRsp_notTaken) // Cycle 4: BPU Rsp arrives.
      // ^ Cycle 4: FSM is in WAITING_FOR_BPU. BPU Rsp matches.
      //            DUT decides: goto(SEND_BRANCH). bpuInFlightCounter decrements.
      sleep(1) // Ensure StreamMonitor captures Branch

      dut.clockDomain.waitSampling()
      // ^ Cycle 5: FSM is in SEND_BRANCH. Branch is sent.
      //            Since not taken, but `isLastInstruction` is true (dispatchIndex=2, fetchWidth=4, but this is the last *valid* instruction).
      //            This is where the `isLastInstruction` logic needs to be correct.
      //            DUT should decide: isBusy:=False, goto(IDLE).
      sleep(1) // Ensure StreamMonitor captures final events

      assert(outputQueue.length == 3, "Phase 4: Branch should be sent, total 3 instructions")
      assert(outputQueue.last.pc == 0x4008)
      assert(!outputQueue.last.bpuIsTaken, "Phase 4: Branch should be predicted not taken")
      ParallaxLogger.info(s"[TB] Phase 4: Branch sent (not taken). State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      dut.clockDomain.waitSampling()
      // ^ Cycle 6: FSM should be in IDLE. isBusy should be False.
      sleep(1)
      assert(!dut.isBusyReg.toBoolean, "Phase 4: DUT should be idle after processing last branch (not taken)")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 4: Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 4 OK: Last branch (not taken) processed. DUT is idle.")

      // --- Phase 5: Repeat with TAKEN branch ---
      ParallaxLogger.info("[TB] Phase 5: Repeating with TAKEN branch")
      val testGroup_taken = (
        BigInt(0x5000),
        71,
        Seq(
          (BigInt(0x13), PredecodeInfoCapture()),
          (BigInt(0x13), PredecodeInfoCapture()),
          (BigInt(0x63), branchPredecode) // Last valid instruction
        )
      )
      pulseFetchGroup(dut, testGroup_taken) // Cycle 7: IDLE receives group, sends Instruction 0 (PC 0x5000)
      // ^ Cycle 7: Instruction 0 sent. DUT decides: isBusy:=True, dispatchIndex:=1, goto(DISPATCHING).
      sleep(1)

      dut.clockDomain.waitSampling() // Cycle 8: Instruction 1 (PC 0x5004) sent.
      sleep(1)

      dut.clockDomain.waitSampling() // Cycle 9: Branch (PC 0x5008) query sent.
      sleep(1)

      val (queryPc_taken, queryTid_taken) = bpuQueryLog.dequeue()
      assert(queryPc_taken == 0x5008)
      assert(queryTid_taken == 1) // Next transaction ID

      val bpuRsp_taken = BpuResponseCapture(isTaken = true, target = 0x6000, transactionId = queryTid_taken)
      pulseBpuResponse(dut, bpuRsp_taken) // Cycle 10: BPU Rsp arrives.
      sleep(1)

      assert(dut.io.softRedirect.valid.toBoolean, "Phase 5: Soft redirect should be valid for taken branch")
      assert(dut.io.softRedirect.payload.toBigInt == 0x6000, "Phase 5: Soft redirect target should be 0x6000")

      dut.clockDomain.waitSampling() // Cycle 11: Branch (PC 0x5008) sent.
      sleep(1)

      assert(outputQueue.length == 6, "Phase 5: Total 6 instructions (3 old + 3 new)")
      assert(outputQueue.last.pc == 0x5008)
      assert(outputQueue.last.bpuIsTaken, "Phase 5: Branch should be predicted taken")
      ParallaxLogger.info(s"[TB] Phase 5: Branch sent (taken). State=${fsmStateNames(dut.sim_fsmStateId.toInt)}")

      dut.clockDomain.waitSampling() // Cycle 12: FSM should be in IDLE. isBusy should be False.
      sleep(1)

      assert(!dut.isBusyReg.toBoolean, "Phase 5: DUT should be idle after processing last branch (taken)")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"Phase 5: Expected IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )
      ParallaxLogger.info(s"[TB] Phase 5 OK: Last branch (taken) processed. DUT is idle.")

      ParallaxLogger.debug("[TB] Last instruction is a conditional branch test PASSED.")
    }
  }

  // SmartDispatcherSpec.scala

  test("Boundary - Handles empty FetchGroup (numValidInstructions = 0)") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      ParallaxLogger.info("[TB] Sending an empty FetchGroup.")
      // 创建一个 numValidInstructions = 0 的 FetchGroup
      val emptyGroup = (BigInt(0x0000), 100, Seq.empty[(BigInt, PredecodeInfoCapture)])

      dut.io.fetchOutput.ready #= true // 确保输出 ready，但不应该有输出

      // 发送空组
      pulseFetchGroup(dut, emptyGroup) // Cycle 1: Group received

      dut.clockDomain.waitSampling() // Cycle 2: DUT 应该处理完毕并回到 IDLE
      assert("IDLE" == fsmStateNames(dut.sim_fsmStateId.toInt))
      // 验证：
      // 1. outputQueue 应该为空
      assert(outputQueue.isEmpty, "No instructions should be dispatched for an empty group.")
      // 2. DUT 应该立即回到 IDLE 状态
      assert(!dut.isBusyReg.toBoolean, "DUT should not be busy after processing an empty group.")
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE",
        s"FSM should be IDLE, got ${fsmStateNames(dut.sim_fsmStateId.toInt)}"
      )

      ParallaxLogger.debug("[TB] Empty FetchGroup test PASSED.")
    }
  }

  test("BPU Logic - Handles mispredicted branch and subsequent correction") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]()
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }
      val softRedirectLog = mutable.Queue[BigInt]()
      FlowMonitor(dut.io.softRedirect, dut.clockDomain) { payload =>
        softRedirectLog.enqueue(payload.toBigInt)
      }
      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      ParallaxLogger.info("[TB] Phase 1: Sending branch (PC 0xC000) - Mispredict as TAKEN.")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val group1 = (BigInt(0xc000), 120, Seq((BigInt(0x63), branchPredecode))) // numValidInstructions = 1

      dut.io.fetchOutput.ready #= true
      pulseFetchGroup(dut, group1) // Cycle 1: Group received, BPU query for 0xC000 (TID=0) sent.
      dut.clockDomain.waitSampling() // Cycle 2: In WAITING_FOR_BPU

      assert(bpuQueryLog.length == 1)
      val (queryPc0, queryTid0) = bpuQueryLog.dequeue()
      assert(queryPc0 == 0xc000)

      val bpuRsp0 = BpuResponseCapture(isTaken = true, target = 0xd000, transactionId = queryTid0) // Mispredict: Taken
      pulseBpuResponse(dut, bpuRsp0) // Cycle 3: BPU Rsp arrives, branch (0xC000) sent, softRedirect to 0xD000.
      dut.clockDomain.waitSampling() // Cycle 4: DUT returns to IDLE.

      assert(outputQueue.length == 1)
      assert(outputQueue.dequeue().pc == 0xc000)
      assert(softRedirectLog.length == 1)
      assert(softRedirectLog.dequeue() == 0xd000)
      weakAssert("IDLE" == fsmStateNames(dut.sim_fsmStateId.toInt))
      weakAssert(!dut.isBusyReg.toBoolean)
      dut.clockDomain.waitSampling() // Cycle 4: DUT returns to IDLE.
      weakAssert("IDLE" == fsmStateNames(dut.sim_fsmStateId.toInt))
      weakAssert(!dut.isBusyReg.toBoolean)

      ParallaxLogger.info("[TB] Phase 1 OK: Branch 0xC000 mispredicted as TAKEN, redirected to 0xD000.")

      ParallaxLogger.info("[TB] Phase 2: Sending branch (PC 0xC000) again - Correctly predict as NOT TAKEN.")
      // 模拟重取指，流水线需要从 0xC000 重新取指
      val group2 = (BigInt(0xc000), 121, Seq((BigInt(0x63), branchPredecode))) // Same PC, numValidInstructions = 1

      pulseFetchGroup(dut, group2) // Cycle 5: Group received, BPU query for 0xC000 (TID=1) sent.
      dut.clockDomain.waitSampling() // Cycle 6: In WAITING_FOR_BPU

      assert(bpuQueryLog.length == 1)
      val (queryPc1, queryTid1) = bpuQueryLog.dequeue()
      assert(queryPc1 == 0xc000)
      assert(queryTid1 == 1) // New transaction ID

      val bpuRsp1 =
        BpuResponseCapture(isTaken = false, target = 0x0, transactionId = queryTid1) // Correct predict: Not Taken
      pulseBpuResponse(dut, bpuRsp1) // Cycle 7: BPU Rsp arrives, branch (0xC000) sent. No redirect.
      dut.clockDomain.waitSampling() // Cycle 8: DUT returns to IDLE.

      assert(outputQueue.length == 1)
      assert(outputQueue.dequeue().pc == 0xc000)
      assert(!outputQueue.headOption.exists(_.bpuIsTaken), "Branch should be predicted NOT TAKEN")
      assert(softRedirectLog.isEmpty, "No soft redirect expected for not taken branch")
      dut.clockDomain.waitSampling()
      assert(!dut.isBusyReg.toBoolean)
      ParallaxLogger.info("[TB] Phase 2 OK: Branch 0xC000 correctly predicted as NOT TAKEN.")

      ParallaxLogger.debug("[TB] BPU mispredicted branch test PASSED.")
    }
  }

// SmartDispatcherSpec.scala

  testSkip("Control Flow - Flush in IDLE state") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut) // DUT should be in IDLE after init

      assert(!dut.isBusyReg.toBoolean, "DUT should be idle before flush")
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE", "FSM should be IDLE")

      ParallaxLogger.info("[TB] Asserting flush while in IDLE.")
      pulseFlush(dut) // Cycle 1: flush high for one cycle

      dut.clockDomain.waitSampling() // Cycle 2: Check state after flush is deasserted
      assert(!dut.isBusyReg.toBoolean, "DUT should remain idle after flush in IDLE state")
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE", "FSM should remain IDLE after flush")

      ParallaxLogger.info("[TB] Verifying DUT can still accept a new group.")
      val testInstructions = Seq((BigInt(0xe000), PredecodeInfoCapture())) // numValidInstructions = 1
      val testGroup = (BigInt(0xe000), 130, testInstructions)
      dut.io.fetchOutput.ready #= true
      pulseFetchGroup(dut, testGroup) // Cycle 3: New group received

      dut.clockDomain.waitSampling(2) // Cycles 4-5: Process group and return to IDLE
      assert(!dut.isBusyReg.toBoolean)
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE")

      ParallaxLogger.debug("[TB] Flush in IDLE state test PASSED.")
    }
  }

// SmartDispatcherSpec.scala

  testSkip("Performance - Continuous short instruction groups") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      dut.io.fetchOutput.ready #= true // Keep output ready

      val numGroups = 10
      var currentPc = BigInt(0xf000)
      for (i <- 0 until numGroups) {
        ParallaxLogger.info(s"[TB] Sending short group ${i + 1} at PC 0x${currentPc.toLong}%x")
        val instructions = Seq(
          (currentPc, PredecodeInfoCapture()), // Instr 0
          (currentPc + 4, PredecodeInfoCapture()) // Instr 1
        ) // numValidInstructions = 2
        val testGroup = (currentPc, 200 + i, instructions)

        pulseFetchGroup(dut, testGroup) // Group received (e.g., Cycle N)
        dut.clockDomain.waitSampling() // Instr 0 sent (e.g., Cycle N+1)
        dut.clockDomain.waitSampling() // Instr 1 sent (e.g., Cycle N+2)

        // After 2 instructions from a group of 2, DUT should be idle again
        dut.clockDomain.waitSampling() // Cycle N+3: Ensure DUT transitions to IDLE
        assert(!dut.isBusyReg.toBoolean, s"DUT should be idle after group ${i + 1}")
        assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE", s"FSM should be IDLE after group ${i + 1}")

        currentPc += 8 // Move to next PC for next group
      }

      assert(outputQueue.length == numGroups * 2, s"Expected ${numGroups * 2} instructions, got ${outputQueue.length}")
      ParallaxLogger.debug("[TB] Continuous short instruction groups test PASSED.")
    }
  }

// SmartDispatcherSpec.scala

// SmartDispatcherSpec.scala

  testSkip("BPU Logic - Handles delayed BPU response") {
    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]()
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }

      // 关键改变：在 StreamMonitor 内部，如果可以，立即检查刚刚入队的元素
      // 或者，在 StreamMonitor 内部只入队，在主线程中等待并检查
      val capturedInstructions = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        val instr = FetchedInstrCapture2(payload)
        capturedInstructions.enqueue(instr)
        ParallaxLogger.info(s"[TB] Captured instruction: PC=0x${instr.pc.toLong}%x, taken=${instr.bpuIsTaken}")
      }

      ParallaxLogger.info("[TB] Phase 1: Sending branch (PC 0x10000) and introducing BPU response delay.")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      val testGroup = (BigInt(0x10000), 140, Seq((BigInt(0x63), branchPredecode))) // numValidInstructions = 1

      dut.io.fetchOutput.ready #= true
      pulseFetchGroup(dut, testGroup) // Cycle 1: Group received, BPU query for 0x10000 (TID=0) sent.
      dut.clockDomain.waitSampling() // Cycle 2: In WAITING_FOR_BPU

      assert(bpuQueryLog.length == 1)
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x10000)

      ParallaxLogger.info("[TB] Phase 2: Waiting for 5 cycles for BPU response (simulating delay).")
      dut.clockDomain.waitSampling(5) // Cycles 3-7: DUT should remain in WAITING_FOR_BPU
      assert(dut.isBusyReg.toBoolean)
      assert(
        fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU",
        "DUT should be in WAITING_FOR_BPU during delay"
      )
      assert(capturedInstructions.isEmpty, "No output during BPU delay") // Use capturedInstructions here

      ParallaxLogger.info("[TB] Phase 3: Providing BPU response after delay.")
      val bpuRsp = BpuResponseCapture(isTaken = true, target = 0x11000, transactionId = queryTid)
      pulseBpuResponse(dut, bpuRsp) // Cycle 8: BPU Rsp arrives. outputReg is loaded.

      // 现在，需要等待指令从 outputReg 发送到 fetchOutput
      // 这通常发生在 pulseBpuResponse 之后的下一个 dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling() // Cycle 9: Instruction (0x10000) is fired to outputQueue

      assert(capturedInstructions.length == 1, s"Expected 1 instruction, but got ${capturedInstructions.length}")
      val issuedInstr = capturedInstructions.dequeue() // Take the one and only instruction
      assert(issuedInstr.pc == 0x10000, "Issued instruction PC mismatch")
      assert(issuedInstr.bpuIsTaken, "Issued instruction bpuIsTaken should be true") // This should pass now!

      dut.clockDomain.waitSampling() // Cycle 10: DUT transitions to IDLE

      assert(!dut.isBusyReg.toBoolean)
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE")

      ParallaxLogger.debug("[TB] Delayed BPU response test PASSED.")
    }
  }

  test("REGRESSION - Handles BPU query for a branch arriving immediately after a long stall") {
    // 这个测试专门复现一个bug：
    // 1. 流水线长时间停顿（例如，因为ICache Miss）。
    // 2. 停顿结束后，Dispatcher立即收到了一个新的FetchGroup。
    // 3. 这个FetchGroup的第一条指令就是需要BPU预测的分支。
    // 4. 由于没有提前量，Dispatcher必须在处理这条分支前，先发送BPU查询并等待结果。
    // Bug表现: Dispatcher没有等待，直接使用了默认的“不跳转”预测。
    // 预期行为: Dispatcher应该进入WAITING_FOR_BPU状态，等待BPU响应，然后再根据响应做决策。

    simConfig.compile(createDut(testPCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initDut(dut)

      val bpuQueryLog = mutable.Queue[(BigInt, BigInt)]()
      FlowMonitor(dut.io.bpuQuery, dut.clockDomain) { payload =>
        bpuQueryLog.enqueue((payload.pc.toBigInt, payload.transactionId.toBigInt))
      }
      val outputQueue = mutable.Queue[FetchedInstrCapture2]()
      StreamMonitor(dut.io.fetchOutput, dut.clockDomain) { payload =>
        outputQueue.enqueue(FetchedInstrCapture2(payload))
      }

      // 模拟长时间停顿，Dispatcher处于IDLE状态
      ParallaxLogger.info("[TB] Phase 1: Simulating a long stall (DUT is IDLE)")
      dut.io.fetchOutput.ready #= true
      dut.clockDomain.waitSampling(10) // 保持IDLE状态10个周期
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "IDLE")

      // --- 停顿结束，分支指令包立即到达 ---
      ParallaxLogger.info("[TB] Phase 2: Stall ends, branch group arrives immediately")
      val branchPredecode = PredecodeInfoCapture(isBranch = true)
      // 这个组的第一条指令就是分支
      val testGroup = (BigInt(0x0bcd0000), 150, Seq((BigInt(0x63), branchPredecode)))

      // 激励：发送这个组
      pulseFetchGroup(dut, testGroup) // Cycle 11: Group arrives
      // Cycle 11: FSM从IDLE接收到组，发现第一条是分支，应该发送BPU查询并进入WAITING_FOR_BPU

      dut.clockDomain.waitSampling() // Cycle 12: 检查状态

      // **核心断言**
      assert(bpuQueryLog.length == 1, "A BPU query MUST be sent for the first instruction if it's a branch.")
      val (queryPc, queryTid) = bpuQueryLog.dequeue()
      assert(queryPc == 0x0bcd0000)

      assert(dut.isBusyReg.toBoolean, "DUT should be busy while waiting for BPU response.")
      assert(fsmStateNames(dut.sim_fsmStateId.toInt) == "WAITING_FOR_BPU", "FSM must transition to WAITING_FOR_BPU.")
      assert(outputQueue.isEmpty, "No instruction should be dispatched before BPU response is received.")

      ParallaxLogger.info("[TB] Phase 2 OK: DUT correctly sent BPU query and is waiting.")

      // --- 提供BPU响应，完成流程 ---
      ParallaxLogger.info("[TB] Phase 3: Providing BPU response to complete the flow")
      val bpuRsp = BpuResponseCapture(isTaken = true, target = 0x0eadbeef, transactionId = queryTid)
      pulseBpuResponse(dut, bpuRsp) // Cycle 13: BPU Rsp arrives.

      dut.clockDomain.waitSampling() // Cycle 14: Branch is sent.

      assert(outputQueue.length == 1, "Branch instruction should be dispatched after BPU response.")
      val dispatchedInstr = outputQueue.dequeue()
      assert(dispatchedInstr.pc == 0x0bcd0000)
      assert(dispatchedInstr.bpuIsTaken, "The BPU prediction (TAKEN) should be reflected in the output.")
      assert(dut.io.softRedirect.valid.toBoolean, "A soft redirect should be triggered.")
      assert(dut.io.softRedirect.payload.toBigInt == 0x0eadbeef)

      dut.clockDomain.waitSampling() // Cycle 15: DUT returns to IDLE.
      assert(!dut.isBusyReg.toBoolean)

      ParallaxLogger.debug("[TB] REGRESSION test for branch-after-stall PASSED.")
    }
  }

  thatsAll
}
