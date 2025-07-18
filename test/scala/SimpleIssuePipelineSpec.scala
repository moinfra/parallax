// filename: src/test/scala/parallax/issue/SimpleIssuePipelineSpec.scala
// testOnly parallax.test.issue.SimpleIssuePipelineSpec
package parallax.test.issue

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch._
import parallax.issue._
import parallax.components.rename._
import parallax.components.rob._
import parallax.utilities._
import test.scala.LA32RInstrBuilder
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

case class DecodedUopCapture(
    pc: BigInt,
    isValid: Boolean,
    uopCode: BaseUopCode.E,
    exeUnit: ExeUnitType.E,
    archDestIdx: Int,
    writeArchDestEn: Boolean,
    archSrc1Idx: Int,
    useArchSrc1: Boolean,
    archSrc2Idx: Int,
    useArchSrc2: Boolean,
    imm: BigInt
)
object DecodedUopCapture {
  def from(uop: DecodedUop): DecodedUopCapture = {
    import spinal.core.sim._
    DecodedUopCapture(
      pc = uop.pc.toBigInt,
      isValid = uop.isValid.toBoolean,
      uopCode = uop.uopCode.toEnum,
      exeUnit = uop.exeUnit.toEnum,
      archDestIdx = uop.archDest.idx.toInt,
      writeArchDestEn = uop.writeArchDestEn.toBoolean,
      archSrc1Idx = uop.archSrc1.idx.toInt,
      useArchSrc1 = uop.useArchSrc1.toBoolean,
      archSrc2Idx = uop.archSrc2.idx.toInt,
      useArchSrc2 = uop.useArchSrc2.toBoolean,
      imm = uop.imm.toBigInt // Assuming imm_sext_16_to_dataWidth.asBits.resized is the value
    )
  }
}

case class RenameInfoCapture(
    physSrc1Idx: Int,
    physSrc2Idx: Int,
    physDestIdx: Int,
    oldPhysDestIdx: Int,
    allocatesPhysDest: Boolean
)
object RenameInfoCapture {
  def from(renameInfo: RenameInfo): RenameInfoCapture = {
    import spinal.core.sim._
    RenameInfoCapture(
      physSrc1Idx = renameInfo.physSrc1.idx.toInt,
      physSrc2Idx = renameInfo.physSrc2.idx.toInt,
      physDestIdx = renameInfo.physDest.idx.toInt,
      oldPhysDestIdx = renameInfo.oldPhysDest.idx.toInt,
      allocatesPhysDest = renameInfo.allocatesPhysDest.toBoolean
    )
  }
}

case class RenamedUopCapture(
    decoded: DecodedUopCapture,
    rename: RenameInfoCapture,
    robPtr: Int // robPtr is a direct value, not a sub-bundle
)
object RenamedUopCapture {
  def from(uop: RenamedUop): RenamedUopCapture = {
    import spinal.core.sim._
    RenamedUopCapture(
      decoded = DecodedUopCapture.from(uop.decoded),
      rename = RenameInfoCapture.from(uop.rename),
      robPtr = uop.robPtr.toInt
    )
  }
}

// =========================================================================
//  Mock Fetch Service & Test Bench
// =========================================================================

class MockFetchService(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def newHardRedirectPort(priority: Int): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
  override def newFetchDisablePort(): Bool = Bool()
}

// -- MODIFICATION START: Add enableCommit control to MockControllerPlugin --
// 在 SimpleIssuePipelineSpec.scala 文件中

class MockControllerPlugin(pCfg: PipelineConfig) extends Plugin {
  val enableCommit = in Bool ()
  val commitAcksPort = in Vec (Bool(), pCfg.commitWidth)

  val setup = create early new Area {
    val ratControl = getService[RatControlService]
    val flControl = getService[FreeListControlService]
    val robService = getService[ROBService[RenamedUop]]
  }

  val logic = create late new Area {
    // 设置不用的端口为空闲状态
    setup.ratControl.newCheckpointSavePort().setIdle()
    setup.ratControl.newCheckpointRestorePort().setIdle()
    setup.flControl.newRestorePort().setIdle()

    // 获取 FreeList 的回收端口
    val freePorts = setup.flControl.getFreePorts()

    // 获取 ROB 的提交端口和应答信号
    val commitSlots = setup.robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = setup.robService.getCommitAcks(pCfg.commitWidth)

    // 循环处理每个提交槽
    for (i <- 0 until pCfg.commitWidth) {
      // 1. 驱动提交应答信号
      val doCommit = enableCommit && commitAcksPort(i) && commitSlots(i).valid
      commitAcks(i) := doCommit
      when(doCommit) {
        val committedUop = commitSlots(i).entry.payload.uop
        // 在一个正常的处理器中，oldPhysDest 不应该指向 p0（通常是零寄存器）
        // 这是一个弱断言，但可以捕获一些异常情况。
        assert(
          committedUop.rename.oldPhysDest.idx =/= 0,
          "Assertion failed in MockController: Trying to free physical register 0 during commit!"
        )
      }
      // 2. 驱动 FreeList 的回收端口

      // 【最终修正】: 使用正确的访问路径 .entry.payload.uop
      val committedUop = commitSlots(i).entry.payload.uop

      // 当提交成功，并且被提交的指令确实分配过物理寄存器时，才启用回收
      freePorts(i).enable := doCommit && committedUop.rename.allocatesPhysDest

      // 从Uop中获取其 *替换掉的* 旧物理寄存器信息
      val oldPhysReg = committedUop.rename.oldPhysDest

      // 将旧物理寄存器的索引发送给FreeList
      freePorts(i).physReg := oldPhysReg.idx
      when(enableCommit)
      {val uopAllocates = committedUop.rename.allocatesPhysDest
      val freeEnable = doCommit && uopAllocates
      report(
        L"MockController[${i}]: Trying to commit! " :+
          L"enableCommit=${enableCommit}, commitAcksPort=${commitAcksPort(i)}, " :+
          L"rob.commitSlot.valid=${commitSlots(i).valid} " :+
          L"-> doCommit=${doCommit}. " :+
          L"uop.allocatesPhysDest=${uopAllocates} " :+
          L"-> freePort.enable=${freeEnable}"
      )}
    }
  }
}
// -- MODIFICATION END --

class SimpleIssuePipelineTestBench(
    val pCfg: PipelineConfig,
    val ratCfg: RenameMapTableConfig,
    val flCfg: SuperScalarFreeListConfig
) extends Component {

  val io = new Bundle {
    val fetchStreamIn = slave(Stream(FetchedInstr(pCfg)))
    val aluIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val mulIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val divIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val lsuIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val bruIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val enableCommit = in Bool ()
    val commitAcks = in Vec (Bool(), pCfg.commitWidth) // 新增，testbench 驱动 commit handshake

    // *** 新增：暴露一个写回端口给测试代码 ***
    val testWbPort = master(
      ROBWritebackPort(
        ROBConfig( // 需要提供 ROB 的配置
          robDepth = pCfg.robDepth,
          pcWidth = pCfg.pcWidth,
          commitWidth = pCfg.commitWidth,
          allocateWidth = pCfg.renameWidth,
          numWritebackPorts = pCfg.totalEuCount + 1, // +1 for this test port
          uopType = HardType(RenamedUop(pCfg)),
          defaultUop = () => RenamedUop(pCfg).setDefault()
        )
      )
    )
  }

  val UOP_HT = HardType(RenamedUop(pCfg))

  val framework = new Framework(
    Seq(
      new MockFetchService(pCfg),
      new BusyTablePlugin(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.xlen bits),
      new ROBPlugin(pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(pCfg, ratCfg, flCfg),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      new DispatchPlugin(pCfg),
      new MockControllerPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchService]
  val iqService = framework.getService[IssueQueueService]
  val issuePipeline = framework.getService[IssuePipeline]
  val robService = framework.getService[ROBService[RenamedUop]]

  val connections = new Area {
    io.fetchStreamIn <> fetchService.fetchStreamIn

    val aluIqPort = iqService.newIssueQueue(Seq(BaseUopCode.ALU, BaseUopCode.SHIFT))
    val mulIqPort = iqService.newIssueQueue(Seq(BaseUopCode.MUL))
    val divIqPort = iqService.newIssueQueue(Seq(BaseUopCode.DIV))
    val lsuIqPort = iqService.newIssueQueue(Seq(BaseUopCode.LOAD, BaseUopCode.STORE))
    val bruIqPort = iqService.newIssueQueue(Seq(BaseUopCode.BRANCH, BaseUopCode.JUMP_REG))

    io.aluIqOut << aluIqPort
    io.mulIqOut << mulIqPort
    io.divIqOut << divIqPort
    io.lsuIqOut << lsuIqPort
    io.bruIqOut << bruIqPort

    // -- MODIFICATION START: Add logging for internal ready signals --
    when(issuePipeline.entryStage.isFiring) { // Log only when pipeline is active to reduce spam
      report(
        L"TestBench IO Readies -> ALU: ${io.aluIqOut.ready}, MUL: ${io.mulIqOut.ready}, LSU: ${io.lsuIqOut.ready}, BRU: ${io.bruIqOut.ready}"
      )
      report(
        L"IQService Ports Readies -> ALU: ${aluIqPort.ready}, MUL: ${mulIqPort.ready}, LSU: ${lsuIqPort.ready}, BRU: ${bruIqPort.ready}"
      )
    }
    // -- MODIFICATION END --

    val fetchOutStream = fetchService.fetchOutput()
    val issueEntryStage = issuePipeline.entryStage
    val issueSignals = issuePipeline.signals
    issueEntryStage.valid := fetchOutStream.valid
    fetchOutStream.ready := issueEntryStage.isReady

    val fetched = fetchOutStream.payload
    val instructionVec = Vec(Bits(pCfg.dataWidth), 1)
    instructionVec(0) := fetched.instruction

    issueEntryStage(issueSignals.GROUP_PC_IN) := fetched.pc
    issueEntryStage(issueSignals.RAW_INSTRUCTIONS_IN) := instructionVec
    issueEntryStage(issueSignals.VALID_MASK) := B"1"
    issueEntryStage(issueSignals.IS_FAULT_IN) := False
    issueEntryStage(issueSignals.FLUSH_TARGET_PC) := 0

    robService.newRobFlushPort().setIdle()

    val wbPortFromTest = robService.newWritebackPort("TestBench_WB_From_Test")
    wbPortFromTest <> io.testWbPort

    for (i <- 0 until pCfg.totalEuCount - 1) {
      val wbPort = robService.newWritebackPort(s"TestBench_Dummy_EU_$i")
      wbPort.fire := False
      wbPort.robPtr.assignDontCare()
      wbPort.exceptionOccurred.assignDontCare()
      wbPort.exceptionCodeIn.assignDontCare()
    }
  }

  val s0_decode_isReady_sim = out(Bool())
  s0_decode_isReady_sim := issuePipeline.entryStage.isReady
}

class SimpleIssuePipelineSpec extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(
    xlen = 32,
    fetchWidth = 1,
    renameWidth = 1,
    archGprCount = 32,
    physGprCount = 64,
    robDepth = 32,
    commitWidth = 1
  )
  val ratCfg = RenameMapTableConfig(
    archRegCount = pCfg.archGprCount,
    physRegCount = pCfg.physGprCount,
    numReadPorts = pCfg.renameWidth * 3,
    numWritePorts = pCfg.renameWidth
  )
  val flCfg = SuperScalarFreeListConfig(
    numPhysRegs = pCfg.physGprCount,
    resetToFull = true,
    numInitialArchMappings = pCfg.archGprCount,
    numAllocatePorts = pCfg.renameWidth,
    numFreePorts = pCfg.commitWidth
  )

  // === 辅助函数：只送一拍 valid，且只在 ready 时推进 ===
  private def sendInstr(
      dut: SimpleIssuePipelineTestBench,
      pc: BigInt,
      inst: BigInt,
      burst: Boolean = false,
      mustPass: Boolean = true
  ): Unit = {
    dut.io.fetchStreamIn.valid #= true
    dut.io.fetchStreamIn.payload.pc #= pc
    dut.io.fetchStreamIn.payload.instruction #= inst
    dut.io.fetchStreamIn.payload.predecode.setDefaultForSim()
    dut.io.fetchStreamIn.payload.bpuPrediction.valid #= false
    val err = dut.clockDomain.waitSamplingWhere(timeout = 10)(
      dut.io.fetchStreamIn.ready.toBoolean && dut.io.fetchStreamIn.valid.toBoolean
    )
    dut.io.fetchStreamIn.valid #= false
    if (err) {
      if (mustPass) assert(false, s"sendInstr timeout for pc: ${pc.toString(16)}, inst: ${inst.toString(16)}")
      else {
        println(s"sendInstr failed for pc: ${pc.toString(16)}, inst: ${inst.toString(16)}")
      }
    } else {
      println(s"sendInstr succeeded for pc: ${pc.toString(16)}, inst: ${inst.toString(16)}")
    }

    if (!burst) {
      dut.clockDomain.waitSampling(5)
    }
  }

  test("Issue Pipeline should correctly dispatch a full mix of instructions") {
    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val instrs = Seq(
        (0x1000, LA32RInstrBuilder.add_w(1, 2, 3)),
        (0x1004, LA32RInstrBuilder.mul_w(4, 5, 6)),
        (0x1008, LA32RInstrBuilder.ld_w(7, 8, 12)),
        (0x100c, LA32RInstrBuilder.beq(1, 4, 64)),
        (0x1010, LA32RInstrBuilder.slli_w(9, 7, 2))
      )

      val aluReceived = mutable.Queue[RenamedUopCapture]()
      val mulReceived = mutable.Queue[RenamedUopCapture]()
      val lsuReceived = mutable.Queue[RenamedUopCapture]()
      val bruReceived = mutable.Queue[RenamedUopCapture]()

      StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }
      StreamMonitor(dut.io.mulIqOut, dut.clockDomain) { p => mulReceived.enqueue(RenamedUopCapture.from(p.uop)) }
      StreamMonitor(dut.io.lsuIqOut, dut.clockDomain) { p => lsuReceived.enqueue(RenamedUopCapture.from(p.uop)) }
      StreamMonitor(dut.io.bruIqOut, dut.clockDomain) { p => bruReceived.enqueue(RenamedUopCapture.from(p.uop)) }

      dut.io.aluIqOut.ready #= true
      dut.io.mulIqOut.ready #= true
      dut.io.lsuIqOut.ready #= true
      dut.io.bruIqOut.ready #= true

      // 用辅助函数逐条送指令
      for ((pc, inst) <- instrs) sendInstr(dut, pc, inst, burst = true)

      // 等待所有指令流过流水线
      dut.clockDomain.waitSampling(20)

      assert(aluReceived.size == 2, s"ALU IQ count mismatch")
      assert(mulReceived.size == 1, s"MUL IQ count mismatch")
      assert(lsuReceived.size == 1, s"LSU IQ count mismatch")
      assert(bruReceived.size == 1, s"BRU IQ count mismatch")

      val aluUop1 = aluReceived.dequeue()
      val mulUop1 = mulReceived.dequeue()
      val lsuUop1 = lsuReceived.dequeue()
      val bruUop1 = bruReceived.dequeue()
      val aluUop2 = aluReceived.dequeue()

      assert(aluUop1.decoded.pc == 0x1000 && aluUop1.robPtr == 0)
      assert(mulUop1.decoded.pc == 0x1004 && mulUop1.robPtr == 1)
      assert(lsuUop1.decoded.pc == 0x1008 && lsuUop1.robPtr == 2)
      assert(bruUop1.decoded.pc == 0x100c && bruUop1.robPtr == 3)
      assert(aluUop2.decoded.pc == 0x1010 && aluUop2.robPtr == 4)

      assert(bruUop1.rename.physSrc1Idx == aluUop1.rename.physDestIdx, "BEQ dependency on r1 (from ADD) failed")
      assert(bruUop1.rename.physSrc2Idx == mulUop1.rename.physDestIdx, "BEQ dependency on r4 (from MUL) failed")

      assert(aluUop2.rename.physSrc1Idx == lsuUop1.rename.physDestIdx, "SLLI dependency on r7 (from LD) failed")

      println("Test 'Issue Pipeline dispatch with real ROB and PRF' PASSED!")
    }
  }

  test("Pipeline should stall correctly when a target IQ is full") {
    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val aluReceived = mutable.Queue[RenamedUopCapture]()
      val mulReceived = mutable.Queue[RenamedUopCapture]()

      StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }
      StreamMonitor(dut.io.mulIqOut, dut.clockDomain) { p => mulReceived.enqueue(RenamedUopCapture.from(p.uop)) }

      dut.io.aluIqOut.ready #= true
      dut.io.mulIqOut.ready #= true
      dut.io.divIqOut.ready #= true
      dut.io.lsuIqOut.ready #= true
      dut.io.bruIqOut.ready #= true

      // 1. Send first ADD, it should pass
      sendInstr(dut, 0x2000, LA32RInstrBuilder.add_w(1, 2, 3))
      assert(aluReceived.size == 1, "First ALU instruction should be dispatched")
      aluReceived.clear()

      // 2. Make ALU IQ not ready (full)
      println("[Test IQ Stall] Making ALU IQ not ready.")
      dut.io.aluIqOut.ready #= false

      // 3. Send the second ADD (will get stuck in dispatch) and the MUL (will get stuck behind the ADD)
      sendInstr(dut, 0x2004, LA32RInstrBuilder.add_w(4, 5, 6), burst = true)
      sendInstr(dut, 0x2008, LA32RInstrBuilder.mul_w(7, 8, 9), burst = true)

      // 4. Wait a few cycles to ensure the pipeline is stalled.
      //    Nothing should be dispatched during this time.
      dut.clockDomain.waitSampling(5)
      assert(aluReceived.isEmpty, "Second ALU instruction should be stalled in dispatch")
      assert(mulReceived.isEmpty, "MUL instruction should be stalled behind the ALU instruction")

      // 5. Make ALU IQ ready again. Now both instructions should flow through.
      println("[Test IQ Stall] Making ALU IQ ready again.")
      dut.io.aluIqOut.ready #= true

      // 6. Wait for both instructions to be received.
      val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100)(aluReceived.nonEmpty && mulReceived.nonEmpty)
      if (timeout) fail("timeout")

      assert(aluReceived.size == 1, "Stalled ALU instruction should now be dispatched")
      assert(mulReceived.size == 1, "Following MUL instruction should now be dispatched")

      val stalledAlu = aluReceived.dequeue()
      val followingMul = mulReceived.dequeue()

      assert(stalledAlu.decoded.pc == 0x2004, "The correct stalled ALU instruction should be dispatched")
      assert(followingMul.decoded.pc == 0x2008, "The correct following MUL instruction should be dispatched")

      println("Test 'Pipeline stall on full IQ' PASSED!")
    }
  }

  test("Pipeline should handle back-to-back data dependencies") {
    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val aluReceived = mutable.Queue[RenamedUopCapture]()
      StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }

      dut.io.aluIqOut.ready #= true
      dut.io.mulIqOut.ready #= true
      dut.io.divIqOut.ready #= true
      dut.io.lsuIqOut.ready #= true
      dut.io.bruIqOut.ready #= true

      // Instruction sequence with dependencies: r4 <- r1 <- r2
      sendInstr(dut, 0x3000, LA32RInstrBuilder.add_w(2, 10, 11), burst = true)
      sendInstr(dut, 0x3004, LA32RInstrBuilder.add_w(1, 2, 12), burst = true)
      sendInstr(dut, 0x3008, LA32RInstrBuilder.add_w(4, 1, 13), burst = true)

      val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100)(aluReceived.size == 3)
      if (timeout) fail("timeout")

      assert(aluReceived.size == 3, "All 3 dependent instructions should be dispatched")

      val uop1 = aluReceived.dequeue() // add r2, ...
      val uop2 = aluReceived.dequeue() // add r1, r2, ...
      val uop3 = aluReceived.dequeue() // add r4, r1, ...

      assert(uop1.decoded.pc == 0x3000)
      assert(uop2.decoded.pc == 0x3004)
      assert(uop3.decoded.pc == 0x3008)

      // Verify dependency r2 -> r1
      assert(uop2.rename.physSrc1Idx == uop1.rename.physDestIdx, "Dependency check failed: r1 depends on r2")

      // Verify dependency r1 -> r4
      assert(uop3.rename.physSrc1Idx == uop2.rename.physDestIdx, "Dependency check failed: r4 depends on r1")

      println("Test 'Back-to-back data dependencies' PASSED!")
    }
  }

  test("Pipeline should correctly process NOP instructions") {
    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val aluReceived = mutable.Queue[RenamedUopCapture]()
      val mulReceived = mutable.Queue[RenamedUopCapture]()
      StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }
      StreamMonitor(dut.io.mulIqOut, dut.clockDomain) { p => mulReceived.enqueue(RenamedUopCapture.from(p.uop)) }

      dut.io.aluIqOut.ready #= true
      dut.io.mulIqOut.ready #= true
      dut.io.divIqOut.ready #= true
      dut.io.lsuIqOut.ready #= true
      dut.io.bruIqOut.ready #= true

      sendInstr(dut, 0x4000, LA32RInstrBuilder.add_w(1, 2, 3), burst = true)
      sendInstr(dut, 0x4004, LA32RInstrBuilder.nop(), burst = true)
      sendInstr(dut, 0x4008, LA32RInstrBuilder.mul_w(4, 5, 6), burst = true)

      val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100)(aluReceived.size == 1 && mulReceived.size == 1)
      if (timeout) fail("timeout")

      // Give one extra cycle to ensure NOP doesn't appear anywhere late
      dut.clockDomain.waitSampling()

      assert(aluReceived.size == 1, "Should only receive one ALU instruction")
      assert(mulReceived.size == 1, "Should only receive one MUL instruction")

      val aluUop = aluReceived.dequeue()
      val mulUop = mulReceived.dequeue()

      // The key check: ROB pointers should be consecutive, skipping the NOP.
      assert(aluUop.robPtr == 0, "First instruction should have robPtr 0")
      assert(mulUop.robPtr == 1, "Instruction after NOP should have robPtr 1")

      println("Test 'NOP instruction processing' PASSED!")
    }
  }

  test("Physical registers should be recycled correctly after commit") {
    // 使用一个较小的PRF来更快地触发回收
    val pCfgRecycle = pCfg.copy(physGprCount = 40, robDepth = 16) // 初始空闲8个
    val ratCfgRecycle = ratCfg.copy(physRegCount = pCfgRecycle.physGprCount)
    val flCfgRecycle = flCfg.copy(numPhysRegs = pCfgRecycle.physGprCount, numInitialArchMappings = pCfg.archGprCount)

    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfgRecycle, ratCfgRecycle, flCfgRecycle)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // 初始化
      dut.io.enableCommit #= false
      dut.io.commitAcks.foreach(_ #= false)
      dut.io.testWbPort.fire #= false
      dut.io.aluIqOut.ready #= true

      println("[PRF Recycle Test] Phase 1: Exhaust initial free physical registers.")
      // 发送8条指令，耗尽初始的8个空闲物理寄存器
      for (i <- 0 until 8) {
        // 使用不同的目标寄存器，避免覆盖
        sendInstr(dut, 0x1000 + i * 4, LA32RInstrBuilder.add_w(i + 1, 10, 11))
      }

      // 等待指令进入ROB
      dut.clockDomain.waitSampling(10)

      println("[PRF Recycle Test] Phase 2: Commit one instruction to free a physical register.")
      // 写回并提交第一条指令 (robPtr=0)
      // 这条指令会释放它所替换的那个旧物理寄存器 (p(i+1))
      dut.io.testWbPort.fire #= true
      dut.io.testWbPort.robPtr #= 0
      dut.clockDomain.waitSampling(1)
      dut.io.testWbPort.fire #= false

      dut.io.enableCommit #= true
      dut.io.commitAcks.foreach(_ #= true)
      dut.clockDomain.waitSampling(1)
      dut.io.enableCommit #= false
      dut.io.commitAcks.foreach(_ #= false)

      println("[PRF Recycle Test] Phase 3: Verify a new instruction can be allocated.")
      // 等待FreeList状态更新，流水线解除停顿
      dut.clockDomain.waitSampling(5)

      // 现在应该有一个空闲的物理寄存器，可以发送第9条指令
      sendInstr(dut, 0x1000 + 8 * 4, LA32RInstrBuilder.add_w(9, 10, 11))

      println("\n[SUCCESS] Test 'Physical register recycling' PASSED!")
    }
  }
test("Pipeline should stall when ROB is full V2") {
      // 使用一个较小的PRF来更快地触发回收
    val pCfgRecycle = pCfg.copy(physGprCount = 40, robDepth = 16) // 初始空闲8个
    val ratCfgRecycle = ratCfg.copy(physRegCount = pCfgRecycle.physGprCount)
    val flCfgRecycle = flCfg.copy(numPhysRegs = pCfgRecycle.physGprCount, numInitialArchMappings = pCfg.archGprCount)

    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfgRecycle, ratCfgRecycle, flCfgRecycle)
    )
    compiled.doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // --- 1. 初始化 ---
        dut.io.enableCommit #= false
        dut.io.commitAcks.foreach(_ #= false)
        dut.io.testWbPort.fire #= false
        dut.io.aluIqOut.ready #= true
        // ...

        // --- 2. 耗尽资源 ---
        println("[Phase 1] Exhausting initial free registers...")
        for (i <- 0 until 8) {
            sendInstr(dut, 0x5000 + i * 4, LA32RInstrBuilder.add_w(i + 1, 10, 11))
        }

        // --- 3. 触发停顿 ---
        println("[Phase 2] Sending 9th instruction to trigger stall...")
        // 不用fork，直接发送。这个调用将会被阻塞，直到流水线解除停顿。
        // 我们在另一个线程中执行后续操作。
        val unstallThread = fork {
            // 等待，直到我们确认流水线已经停顿
            dut.clockDomain.waitSamplingWhere(timeout=20)(!dut.s0_decode_isReady_sim.toBoolean)
            println("  -> Stall Verified: Pipeline frontend is not ready.")

            // --- 4. 解除停顿 ---
            println("[Phase 3] Unstalling pipeline: Writeback + Commit...")
            
            // 4a. 写回
            println("  -> Step 4a: Simulating writeback for robPtr=0...")
            dut.io.testWbPort.fire #= true
            dut.io.testWbPort.robPtr #= 0
            dut.io.testWbPort.exceptionOccurred #= false
            dut.clockDomain.waitSampling(1)
            dut.io.testWbPort.fire #= false

            // 等待ROB更新done位，并将commit.valid拉高
            dut.clockDomain.waitSampling(2)

            // 4b. 提交
            println("  -> Step 4b: Simulating commit...")
            dut.io.enableCommit #= true
            dut.io.commitAcks.foreach(_ #= true)
            dut.clockDomain.waitSampling(1)
            dut.io.enableCommit #= false
            dut.io.commitAcks.foreach(_ #= false)

            // 等待FreeList更新
            dut.clockDomain.waitSampling(2)
            println("[Phase 4] Unstall sequence finished.")
        }
        
        // 主线程负责发送第9条指令，它会被阻塞，直到unstallThread完成工作
        sendInstr(dut, 0x5000 + 8 * 4, LA32RInstrBuilder.add_w(9, 10, 11))

        unstallThread.join() // 等待fork的线程结束

        println("\n[SUCCESS] Test 'Pipeline stall on resource exhaustion' PASSED!")
    }
}
  test("NOP instructions should be correctly discarded and not consume resources") {
    val compiled = SimConfig.withFstWave.compile(
      new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
    )

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val aluReceived = mutable.Queue[RenamedUopCapture]()
      StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }

      // 初始化
      dut.io.aluIqOut.ready #= true
      dut.io.enableCommit #= false // 禁用提交，以便检查ROB指针

      println("[NOP Test] Sending a sequence with NOPs...")
      sendInstr(dut, 0x2000, LA32RInstrBuilder.add_w(1, 2, 3), burst = true) // 指令1 -> robPtr=0
      sendInstr(dut, 0x2004, LA32RInstrBuilder.nop(), burst = true) // NOP -> 不应分配ROB
      sendInstr(dut, 0x2008, LA32RInstrBuilder.add_w(4, 5, 6), burst = true) // 指令2 -> robPtr=1
      sendInstr(dut, 0x200c, LA32RInstrBuilder.nop(), burst = true) // NOP
      sendInstr(dut, 0x2010, LA32RInstrBuilder.nop(), burst = true) // NOP
      sendInstr(dut, 0x2014, LA32RInstrBuilder.add_w(7, 8, 9), burst = true) // 指令3 -> robPtr=2

      // 等待所有非NOP指令到达发射队列
      val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100)(aluReceived.size == 3)
      if (timeout) fail("Did not receive all non-NOP instructions in ALU IQ.")

      println("[NOP Test] Verifying ROB pointers...")
      val uop1 = aluReceived.dequeue()
      val uop2 = aluReceived.dequeue()
      val uop3 = aluReceived.dequeue()

      assert(uop1.decoded.pc == 0x2000, "PC of first uop is wrong.")
      assert(uop1.robPtr == 0, s"First uop should have robPtr=0, but got ${uop1.robPtr}")

      assert(uop2.decoded.pc == 0x2008, "PC of second uop is wrong.")
      assert(uop2.robPtr == 1, s"Second uop (after NOP) should have robPtr=1, but got ${uop2.robPtr}")

      assert(uop3.decoded.pc == 0x2014, "PC of third uop is wrong.")
      assert(uop3.robPtr == 2, s"Third uop (after NOPs) should have robPtr=2, but got ${uop3.robPtr}")

      println("\n[SUCCESS] Test 'NOP instruction handling' PASSED!")
    }

    test("Pipeline should handle back-to-back Read-After-Write (RAW) dependencies") {
      val compiled = SimConfig.withFstWave.compile(
        new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
      )

      compiled.doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        val aluReceived = mutable.Queue[RenamedUopCapture]()
        StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }

        dut.io.aluIqOut.ready #= true

        println("[RAW Test] Sending a chain of dependent instructions: r3 <- r2 <- r1")
        // r1 = r10 + r11
        sendInstr(dut, 0x3000, LA32RInstrBuilder.add_w(1, 10, 11), burst = true)
        // r2 = r1 + r12
        sendInstr(dut, 0x3004, LA32RInstrBuilder.add_w(2, 1, 12), burst = true)
        // r3 = r2 + r13
        sendInstr(dut, 0x3008, LA32RInstrBuilder.add_w(3, 2, 13), burst = true)

        // 等待所有指令被派发
        val timeout = dut.clockDomain.waitSamplingWhere(timeout = 100)(aluReceived.size == 3)
        if (timeout) fail("Did not receive all dependent instructions.")

        val uop1 = aluReceived.dequeue() // addi r1, ...
        val uop2 = aluReceived.dequeue() // addi r2, r1, ...
        val uop3 = aluReceived.dequeue() // addi r3, r2, ...

        println("[RAW Test] Verifying physical register mappings...")
        // 验证 uop2 的源(r1)是否是 uop1 的目标
        assert(
          uop2.rename.physSrc1Idx == uop1.rename.physDestIdx,
          s"Dependency r2 <- r1 failed. uop2.physSrc1 (${uop2.rename.physSrc1Idx}) should be uop1.physDest (${uop1.rename.physDestIdx})."
        )

        // 验证 uop3 的源(r2)是否是 uop2 的目标
        assert(
          uop3.rename.physSrc1Idx == uop2.rename.physDestIdx,
          s"Dependency r3 <- r2 failed. uop3.physSrc1 (${uop3.rename.physSrc1Idx}) should be uop2.physDest (${uop2.rename.physDestIdx})."
        )

        println("\n[SUCCESS] Test 'Back-to-back RAW dependencies' PASSED!")
      }
    }

  }
  thatsAll()
}
