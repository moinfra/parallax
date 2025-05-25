// filename: test/scala/ROBPluginSpec.scala
package parallax.test.scala.rob

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.tester.SpinalSimFunSuite

import parallax.common._
import parallax.components.rob._
import parallax.utilities._

import scala.collection.mutable
import scala.util.Random

// DummyUop2 and ROBAllocateSlotPayloadForTest definitions remain the same
case class DummyUop2(val config: PipelineConfig) extends Bundle with Dumpable with HasRobIdx{
  val robIdx = UInt(config.robIdxWidth)
  val pc = UInt(config.pcWidth)
  def setDefault(): this.type = { this.robIdx := 0; this.pc := 0; this }
  def setDefaultForSim(): this.type = { import spinal.core.sim._; this.robIdx #= 0; this.pc #= 0; this }

  def dump(): Seq[Any] = {
    L"DummyUop(pc=${pc}, robIdx=${robIdx})"
  }
}
case class ROBAllocateSlotPayloadForTest(pCfg: PipelineConfig, uopHardType: HardType[DummyUop2]) extends Bundle {
  val uop = uopHardType()
  val pc = UInt(pCfg.pcWidth)
}

// --- 辅助测试插件 (存储从服务获取的端口引用) ---
class TestAllocatorLogicHolder(
    id: String,
    val allocateWidth: Int,
    val pCfg: PipelineConfig
) extends Plugin {
  setName(s"Allocator_${id}")
  var allocatePortsFromService: Vec[ROBAllocateSlot[DummyUop2]] = null // ROB's slave ports
  var canAllocateVecFromService: Vec[Bool] = null

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    allocatePortsFromService = robService.getAllocatePorts(allocateWidth)
    canAllocateVecFromService = robService.getCanAllocateVec(allocateWidth)
  }
}

class TestEuLogicHolder(euName: String, val pCfg: PipelineConfig) extends Plugin {
  ParallaxLogger.log(s"create TestEuLogicHolder $euName")
  setName(euName)
  var writebackPortFromService: ROBWritebackPort[DummyUop2] = null // ROB's slave port

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    writebackPortFromService = robService.newWritebackPort(euName)
  }
}

class TestCommitterLogicHolder(id: String, val commitWidth: Int, val pCfg: PipelineConfig) extends Plugin {
  setName(s"Committer_${id}")
  var commitSlotsFromService: Vec[ROBCommitSlot[DummyUop2]] = null // ROB's master ports
  var commitAcksToService: Vec[Bool] = null // ROB's slave/in ports for acks

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    commitSlotsFromService = robService.getCommitSlots(commitWidth) // Gets master ports from ROB
    commitAcksToService = robService.getCommitAcks(commitWidth) // Gets slave ports for ROB to receive acks
  }
}

class TestFlusherLogicHolder(id: String, val pCfg: PipelineConfig) extends Plugin {
  setName(s"Flusher_${id}")
  var flushPortToService: Flow[ROBFlushCommand[DummyUop2]] = null // ROB's slave Flow

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    flushPortToService = robService.getFlushPort()
  }
}

case class TestBenchConfig(
    pCfg: PipelineConfig,
    numEus: Int
)
// --- TestBench IO Definition ---
case class TestBenchIO(tbCfg: TestBenchConfig, LOCAL_UOP_HT: HardType[DummyUop2]) extends Bundle with IMasterSlave {

  // Create ROBConfig instance once to reduce redundancy
  private val robConfigParams = ROBConfig(
    robDepth = tbCfg.pCfg.robDepth,
    pcWidth = tbCfg.pCfg.pcWidth,
    commitWidth = tbCfg.pCfg.commitWidth,
    allocateWidth = tbCfg.pCfg.renameWidth,
    numWritebackPorts = tbCfg.numEus,
    uopType = LOCAL_UOP_HT,
    defaultUop = () => LOCAL_UOP_HT().asInstanceOf[DummyUop2].setDefault(), // Ensure DummyUop2 has setDefault
    exceptionCodeWidth = tbCfg.pCfg.exceptionCodeWidth
  )

  // Allocation: Testbench drives allocation requests
  val allocRequests = Vec(master(Flow(ROBAllocateSlotPayloadForTest(tbCfg.pCfg, LOCAL_UOP_HT))), tbCfg.pCfg.renameWidth)
  // Allocation: Testbench observes responses from ROB
  val allocResponsesRobIdx = Vec(in(UInt(robConfigParams.robIdxWidth)), tbCfg.pCfg.renameWidth)
  val allocResponsesCanAlloc = Vec(in(Bool()), tbCfg.pCfg.renameWidth)

  // EU Writeback: Testbench drives writeback info
  val euWritebacks = Vec(master(Flow(RobCompletionMsg(tbCfg.pCfg))), tbCfg.numEus)

  // Commit: Testbench observes committable slots from ROB
  val commitProvidedSlots = Vec(slave(Flow(ROBFullEntry[DummyUop2](robConfigParams))), tbCfg.pCfg.commitWidth)
  // Commit: Testbench drives commit acknowledgements
  val commitDriveAcks = Vec(out(Bool()), tbCfg.pCfg.commitWidth)

  // Flush: Testbench drives flush command
  val flushCommand = master(Flow(ROBFlushCommand(robConfigParams)))

  override def asMaster(): Unit = {
    // For `slave(Flow(...))` types, `slave(signalName)` correctly sets directions.
    allocRequests.foreach(slave(_))
    euWritebacks.foreach(slave(_))
    slave(flushCommand)

    // For `out(...)` types, `out(signalName)` makes them outputs.
    out(allocResponsesRobIdx)
    out(allocResponsesCanAlloc)

    // For `master(Flow(...))` types, `master(signalName)` correctly sets directions.
    commitProvidedSlots.foreach(master(_))

    // For `in(...)` types, `in(signalName)` makes them inputs.
    in(commitDriveAcks)
  }
}
// -- MODIFICATION END --
// --- ROBPlugin Test Suite ---
class ROBPluginSpec extends CustomSpinalSimFunSuite {
  def testPipelineConfig(
      robD: Int = 16,
      allocW: Int = 2,
      commitW: Int = 2,
      numEus: Int = 2
  ) = PipelineConfig(
    robDepth = robD,
    renameWidth = allocW,
    commitWidth = commitW,
    aluEuCount = numEus
  )

  def createDut(tbCfg: TestBenchConfig) = {
    assert(tbCfg.pCfg.aluEuCount == tbCfg.numEus, "Number of EUs must match ALU EUs in PipelineConfig")
    ParallaxLogger.log(s"createDut with ${tbCfg.pCfg.aluEuCount} EUs")
    class TestBench extends Component {
      val database = new DataBase
      val LOCAL_UOP_HT = HardType(DummyUop2(tbCfg.pCfg))
      def LOCAL_DEFAULT_UOP_FUNC = () => DummyUop2(tbCfg.pCfg).setDefault()

      val io = master(TestBenchIO(tbCfg, LOCAL_UOP_HT))
      io.simPublic()

      val robPlugin = new ROBPlugin[DummyUop2](tbCfg.pCfg, LOCAL_UOP_HT, LOCAL_DEFAULT_UOP_FUNC)
      val allocatorHolder = new TestAllocatorLogicHolder("Alloc0", tbCfg.pCfg.renameWidth, tbCfg.pCfg)
      val euHolders = (0 until tbCfg.numEus).map(i => new TestEuLogicHolder(s"EU$i", tbCfg.pCfg))
      val committerHolder = new TestCommitterLogicHolder("Commit0", tbCfg.pCfg.commitWidth, tbCfg.pCfg)
      val flusherHolder = new TestFlusherLogicHolder("Flush0", tbCfg.pCfg)

      val allFrameworkPlugins: Seq[Plugin] =
        Seq(robPlugin, allocatorHolder) ++ euHolders ++ Seq(committerHolder, flusherHolder)
      val framework = ProjectScope(database) on new Framework(allFrameworkPlugins)

      // --- Connections: TestBench IO <-> ROBService Ports (via LogicHolders) ---

      val serviceAllocPorts: Vec[ROBAllocateSlot[DummyUop2]] =
        allocatorHolder.allocatePortsFromService // ROB's slave ports
      for (i <- 0 until tbCfg.pCfg.renameWidth) {
        serviceAllocPorts(i).fire := io.allocRequests(i).valid
        serviceAllocPorts(i).uopIn := io.allocRequests(i).payload.uop
        serviceAllocPorts(i).pcIn := io.allocRequests(i).payload.pc
        io.allocResponsesRobIdx(i) := serviceAllocPorts(i).robIdx
        // report(L"[TB] Allocation response: slot ${i.toString()}: robIdx=${serviceAllocPorts(i).robIdx}, canAlloc=${io.allocResponsesRobIdx(i)}")
      }
      (io.allocResponsesCanAlloc, allocatorHolder.canAllocateVecFromService).zipped.foreach(_ := _)

      val serviceEuWbPorts: Seq[ROBWritebackPort[DummyUop2]] =
        euHolders.map(_.writebackPortFromService) // ROB's slave ports
      for (i <- 0 until tbCfg.numEus) {
        serviceEuWbPorts(i).fire := io.euWritebacks(i).valid
        serviceEuWbPorts(i).robIdx := io.euWritebacks(i).payload.robIdx
        serviceEuWbPorts(i).exceptionOccurred := io.euWritebacks(i).payload.hasException
        serviceEuWbPorts(i).exceptionCodeIn := io.euWritebacks(i).payload.exceptionCode
      }

      // serviceCommitSlots are ROB's MASTER ports.
      // io.commitProvidedSlots is TestBench's SLAVE Flow.
      // Connection: slaveFlow << masterBundle.asFlow()
      val serviceCommitSlots: Vec[ROBCommitSlot[DummyUop2]] = committerHolder.commitSlotsFromService
      for (i <- 0 until tbCfg.pCfg.commitWidth) {
        // *** CORRECTED CONNECTION FOR COMMIT DATA ***
        io.commitProvidedSlots(i) << serviceCommitSlots(i).asFlow
      }
      // io.commitDriveAcks are TestBench's OUT ports.
      // serviceCommitAcks are ROB's IN (slave) ports.
      // Connection: rob_slave_port := tb_out_port
      (committerHolder.commitAcksToService, io.commitDriveAcks).zipped.foreach(_ := _)

      // io.flushCommand is TestBench's MASTER Flow.
      // serviceFlushPort is ROB's SLAVE Flow.
      // Connection: slaveFlow << masterFlow
      flusherHolder.flushPortToService << io.flushCommand
    }
    new TestBench()
  }

  def driveAllocationTb(
      tbIo: TestBenchIO,
      slotIdx: Int,
      pc: Long,
      uopSetter: DummyUop2 => Unit,
      clockDomain: ClockDomain,
      pCfg: PipelineConfig
  ): (Int, Boolean) = { // 返回分配到的 robIdx
    tbIo.allocRequests(slotIdx).valid #= true
    tbIo.allocRequests(slotIdx).payload.pc #= pc
    val tempUop = DummyUop2(pCfg); tempUop.setDefault(); uopSetter(tempUop)
    tbIo.allocRequests(slotIdx).payload.uop.assignFrom(tempUop)

    var allocatedIdx = -1
    var canAlloc = false
    clockDomain.waitSampling() // ROB 在这个周期的上升沿看到 valid=true 并计算 robIdx
    allocatedIdx = tbIo.allocResponsesRobIdx(slotIdx).toInt // 读取 robIdx
    // 此时 tailPtr_reg 仍是旧值
    canAlloc = tbIo.allocResponsesCanAlloc(slotIdx).toBoolean // 读取 canAlloc
    tbIo.allocRequests(slotIdx).valid #= false
    ParallaxLogger.success(s"Allocated slot $slotIdx to ROB index $allocatedIdx, canAllocateNext=$canAlloc")
    (allocatedIdx, canAlloc)
  }

  def driveWritebackTb(
      tbIo: TestBenchIO,
      euIdx: Int,
      robIdxVal: Int,
      hasEx: Boolean,
      exCode: Int,
      clockDomain: ClockDomain
  ): Unit = {
    tbIo.euWritebacks(euIdx).valid #= true
    tbIo.euWritebacks(euIdx).payload.robIdx #= robIdxVal
    tbIo.euWritebacks(euIdx).payload.hasException #= hasEx
    tbIo.euWritebacks(euIdx).payload.exceptionCode #= exCode
    clockDomain.waitSampling()
    tbIo.euWritebacks(euIdx).valid #= false
  }

  def driveCommitAcksTb(tbIo: TestBenchIO, acks: Seq[Boolean], clockDomain: ClockDomain): Unit = {
    acks.zipWithIndex.foreach { case (ack, i) => tbIo.commitDriveAcks(i) #= ack }
    clockDomain.waitSampling()
    acks.indices.foreach { i => tbIo.commitDriveAcks(i) #= false }
  }

  // --- Test Cases ---
  val pCfgBase = testPipelineConfig(robD = 8, allocW = 1, commitW = 1, numEus = 1)
  testOnly("ROBPlugin - Basic Allocate, Writeback, Commit") {
    val currentTestCfg = pCfgBase
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      var allocatedRobIdx = 0
      var receivedCommitPc = -1L
      var receivedCommitRobIdx = -1
      dut.io.commitProvidedSlots.foreach(_.valid #= false)
      dut.io.allocRequests.foreach(flow => flow.valid #= false)
      dut.io.euWritebacks.foreach(flow => flow.valid #= false)
      dut.io.commitDriveAcks.foreach(_ #= false)
      dut.io.flushCommand.valid #= false

      FlowMonitor(dut.io.commitProvidedSlots(0), dut.clockDomain) { payload =>
        ParallaxLogger.log(
          s"SIM: Commit monitor: ROB valid, ROB Idx=${payload.payload.uop.robIdx.toInt}, PC=0x${payload.payload.pc.toLong.toHexString}"
        )
        receivedCommitPc = payload.payload.pc.toLong
        receivedCommitRobIdx = payload.payload.uop.robIdx.toInt
      }
      dut.clockDomain.waitSampling(5)

      println("SIM: Driving allocation via TestBench.io...")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x1000, _ => {}, dut.clockDomain, currentTestCfg)
      allocatedRobIdx = idx0
      ParallaxLogger.log(s"SIM: Allocated. ROB Index = $allocatedRobIdx, CanAllocate(0)=$canAlloc0")
      assert(allocatedRobIdx == 0, s"Expected first allocated ROB index to be 0, got $allocatedRobIdx")
      // 这里的 canAlloc0 已经是下一个周期的 canAlloc 状态，所以直接断言即可
      assert(canAlloc0, "ROB should allow next allocation after first")

      ParallaxLogger.log(s"SIM: Driving writeback via TestBench.io for ROB Index = $allocatedRobIdx...")
      driveWritebackTb(dut.io, 0, allocatedRobIdx, false, 0, dut.clockDomain)
      dut.clockDomain.waitSampling(5)

      ParallaxLogger.log(s"SIM: Waiting for commit valid for ROB Index = $allocatedRobIdx...")
      val commitTimeout = dut.clockDomain.waitSamplingWhere(timeout = 50) {
        dut.io.commitProvidedSlots(0).valid.toBoolean &&
        dut.io.commitProvidedSlots(0).payload.payload.uop.robIdx.toInt == allocatedRobIdx
      }
      assert(!commitTimeout, s"Timeout waiting for ROB idx $allocatedRobIdx to be committable.")
      ParallaxLogger.log(s"SIM: ROB Idx $allocatedRobIdx is committable. Driving commit ack via TestBench.io.")
      assert(receivedCommitPc == 0x1000)
      assert(receivedCommitRobIdx == allocatedRobIdx)

      driveCommitAcksTb(dut.io, Seq(true), dut.clockDomain)
      dut.clockDomain.waitSampling(5)
      println("Test 'ROBPlugin - Basic Allocate, Writeback, Commit' PASSED")
    }
  }

  val pCfgFull = testPipelineConfig(robD = 2, allocW = 1, commitW = 1, numEus = 1)
  test("ROBPlugin - ROB Full Scenario") {
    val tbCfg = TestBenchConfig(pCfg = pCfgFull, numEus = pCfgFull.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      println("SIM: ROB Full Test: Start")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x100, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 1: robIdx=$idx0, canAllocNext=$canAlloc0")
      assert(canAlloc0, "ROB (depth 2) should allow second allocation after first")

      val (idx1, canAlloc1) = driveAllocationTb(dut.io, 0, 0x104, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 2: robIdx=$idx1, canAllocNext=$canAlloc1")
      assert(
        !canAlloc1,
        "ROB (depth 2) should be full after two allocations, canAllocate(0) for next op should be false."
      )

      println("SIM: Attempting to allocate to full ROB via TestBench.io...")
      // 再次调用 driveAllocationTb，它会返回当前周期的 canAlloc 状态
      val (idx2, canAlloc2) = driveAllocationTb(dut.io, 0, 0x108, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 3 (attempt): robIdx=$idx2, canAllocNext=$canAlloc2")
      // 此时 canAlloc2 应该仍然是 false，因为 ROB 仍然是满的
      assert(!canAlloc2, "canAllocate should remain false when ROB is full and allocation is attempted")

      println("Test 'ROBPlugin - ROB Full Scenario' PASSED")
    }
  }
  thatsAll()
}
