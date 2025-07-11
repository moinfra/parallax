// filename: test/scala/ROBPluginSpec.scala
// testOnly test.scala.ROBPluginSpec
package test.scala

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
import spinal.lib.misc.BinTools.initRam

// DummyUop2 and ROBAllocateSlotPayloadForTest definitions remain the same
case class DummyUop2(val config: PipelineConfig) extends Bundle with Formattable with HasRobPtr {
  val robPtr = UInt(config.robPtrWidth)
  val pc = UInt(config.pcWidth)
  def setDefault(): this.type = { this.robPtr := 0; this.pc := 0; this }
  def setDefaultForSim(): this.type = { import spinal.core.sim._; this.robPtr #= 0; this.pc #= 0; this }

  def format(): Seq[Any] = {
    L"DummyUop(pc=${pc}, robPtr=${robPtr})"
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
  var flushPortToService: Flow[ROBFlushPayload] = null // ROB's slave Flow

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    flushPortToService = robService.newFlushPort()
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
  val allocResponsesRobPtr = Vec(in(UInt(robConfigParams.robPtrWidth)), tbCfg.pCfg.renameWidth)
  val allocResponsesCanAlloc = Vec(in(Bool()), tbCfg.pCfg.renameWidth)

  // EU Writeback: Testbench drives writeback info
  val euWritebacks = Vec(master(Flow(RobCompletionMsg(tbCfg.pCfg))), tbCfg.numEus)

  // Commit: Testbench observes committable slots from ROB
  val commitProvidedSlots = Vec(slave(Flow(ROBFullEntry[DummyUop2](robConfigParams))), tbCfg.pCfg.commitWidth)
  // Commit: Testbench drives commit acknowledgements
  val commitDriveAcks = Vec(out(Bool()), tbCfg.pCfg.commitWidth)

  // Flush: Testbench drives flush command
  val flushCommand = master(Flow(ROBFlushPayload(robConfigParams.robPtrWidth)))

  override def asMaster(): Unit = {
    // For `slave(Flow(...))` types, `slave(signalName)` correctly sets directions.
    allocRequests.foreach(slave(_))
    euWritebacks.foreach(slave(_))
    slave(flushCommand)

    // For `out(...)` types, `out(signalName)` makes them outputs.
    out(allocResponsesRobPtr)
    out(allocResponsesCanAlloc)

    // For `master(Flow(...))` types, `master(signalName)` correctly sets directions.
    commitProvidedSlots.foreach(master(_))

    // For `in(...)` types, `in(signalName)` makes them inputs.
    in(commitDriveAcks)
  }
}
// --- ROBPlugin Test Suite ---
class ROBPluginSpec extends CustomSpinalSimFunSuite {
  def testPipelineConfig(
      robD: Int = 16,
      allocW: Int = 2,
      commitW: Int = 2,
      numEus: Int = 2,
      lsuEuCount: Int = 0,
  ) = PipelineConfig(
    robDepth = robD,
    renameWidth = allocW,
    commitWidth = commitW,
    aluEuCount = numEus,
    lsuEuCount = lsuEuCount,
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
        serviceAllocPorts(i).valid := io.allocRequests(i).valid
        serviceAllocPorts(i).uopIn := io.allocRequests(i).payload.uop
        serviceAllocPorts(i).pcIn := io.allocRequests(i).payload.pc
        io.allocResponsesRobPtr(i) := serviceAllocPorts(i).robPtr
        // report(L"[TB] Allocation response: slot ${i.toString()}: robPtr=${serviceAllocPorts(i).robPtr}, canAlloc=${io.allocResponsesRobPtr(i)}")
      }
      (io.allocResponsesCanAlloc, allocatorHolder.canAllocateVecFromService).zipped.foreach(_ := _)

      val serviceEuWbPorts: Seq[ROBWritebackPort[DummyUop2]] =
        euHolders.map(_.writebackPortFromService) // ROB's slave ports
      for (i <- 0 until tbCfg.numEus) {
        serviceEuWbPorts(i).fire := io.euWritebacks(i).valid
        serviceEuWbPorts(i).robPtr := io.euWritebacks(i).payload.robPtr
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

  def driveFlushTb(
      tbIo: TestBenchIO,
      newHead: Int,
      newTail: Int,
      newCount: Int,
      clockDomain: ClockDomain
  ): Unit = {
    tbIo.flushCommand.valid #= true
    tbIo.flushCommand.payload.reason #= FlushReason.FULL_FLUSH
    // FIXME
    clockDomain.waitSampling()
    tbIo.flushCommand.valid #= false
    ParallaxLogger.log(s"SIM: Flushed ROB. newHead=$newHead, newTail=$newTail, newCount=$newCount")
  }

  def driveAllocationTb(
      tbIo: TestBenchIO,
      slotIdx: Int,
      pc: Long,
      uopSetter: DummyUop2 => Unit,
      clockDomain: ClockDomain,
      pCfg: PipelineConfig
  ): (Int, Boolean) = { // 返回分配到的 robPtr
    tbIo.allocRequests(slotIdx).valid #= true
    tbIo.allocRequests(slotIdx).payload.pc #= pc
    val tempUop = DummyUop2(pCfg); tempUop.setDefault(); uopSetter(tempUop)
    tbIo.allocRequests(slotIdx).payload.uop.assignFrom(tempUop)

    var allocatedPtr = -1
    var canAlloc = false
    clockDomain.waitSampling() // ROB 在这个周期的上升沿看到 valid=true 并计算 robPtr
    allocatedPtr = tbIo.allocResponsesRobPtr(slotIdx).toInt // 读取 robPtr
    // 此时 tailPtr_reg 仍是旧值
    canAlloc = tbIo.allocResponsesCanAlloc(slotIdx).toBoolean // 读取 canAlloc
    tbIo.allocRequests(slotIdx).valid #= false
    ParallaxLogger.success(s"Allocated slot $slotIdx to ROB index $allocatedPtr, canAllocateNext=$canAlloc")
    // remove genbit
    val allocatedIdx = allocatedPtr & ~(1 << pCfg.robPtrWidth.value - 1)
    (allocatedIdx, canAlloc)
  }

  def driveWritebackTb(
      tbIo: TestBenchIO,
      euIdx: Int,
      robPtrVal: Int,
      hasEx: Boolean,
      exCode: Int,
      clockDomain: ClockDomain
  ): Unit = {
    tbIo.euWritebacks(euIdx).valid #= true
    tbIo.euWritebacks(euIdx).payload.robPtr #= robPtrVal
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

  def driveInitTb(tbIo: TestBenchIO): Unit = {
    tbIo.commitProvidedSlots.foreach(_.valid #= false)
      tbIo.allocRequests.foreach(flow => flow.valid #= false)
      tbIo.euWritebacks.foreach(flow => flow.valid #= false)
      tbIo.commitDriveAcks.foreach(_ #= false)
      tbIo.flushCommand.valid #= false
  }

  // --- Test Cases ---
  val pCfgBase = testPipelineConfig(robD = 8, allocW = 1, commitW = 1, numEus = 1)
  test("ROBPlugin - Basic Allocate, Writeback, Commit") {
    val currentTestCfg = pCfgBase
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      var allocatedRobPtr = 0
      var receivedCommitPc = -1L
      var receivedCommitRobPtr = -1
      driveInitTb(dut.io)

      FlowMonitor(dut.io.commitProvidedSlots(0), dut.clockDomain) { payload =>
        ParallaxLogger.log(
          s"SIM: Commit monitor: ROB valid, ROB Idx=${payload.payload.uop.robPtr.toInt}, PC=0x${payload.payload.pc.toLong.toHexString}"
        )
        receivedCommitPc = payload.payload.pc.toLong
        receivedCommitRobPtr = payload.payload.uop.robPtr.toInt
      }
      dut.clockDomain.waitSampling(5)

      println("SIM: Driving allocation via TestBench.io...")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x1000, _ => {}, dut.clockDomain, currentTestCfg)
      allocatedRobPtr = idx0
      ParallaxLogger.log(s"SIM: Allocated. ROB Index = $allocatedRobPtr, CanAllocate(0)=$canAlloc0")
      assert(allocatedRobPtr == 0, s"Expected first allocated ROB index to be 0, got $allocatedRobPtr")
      // 这里的 canAlloc0 已经是下一个周期的 canAlloc 状态，所以直接断言即可
      assert(canAlloc0, "ROB should allow next allocation after first")

      ParallaxLogger.log(s"SIM: Driving writeback via TestBench.io for ROB Index = $allocatedRobPtr...")
      driveWritebackTb(dut.io, 0, allocatedRobPtr, false, 0, dut.clockDomain)
      dut.clockDomain.waitSampling(5)

      ParallaxLogger.log(s"SIM: Waiting for commit valid for ROB Index = $allocatedRobPtr...")
      val commitTimeout = dut.clockDomain.waitSamplingWhere(timeout = 50) {
        dut.io.commitProvidedSlots(0).valid.toBoolean &&
        dut.io.commitProvidedSlots(0).payload.payload.uop.robPtr.toInt == allocatedRobPtr
      }
      assert(!commitTimeout, s"Timeout waiting for ROB idx $allocatedRobPtr to be committable.")
      ParallaxLogger.log(s"SIM: ROB Idx $allocatedRobPtr is committable. Driving commit ack via TestBench.io.")
      assert(receivedCommitPc == 0x1000)
      assert(receivedCommitRobPtr == allocatedRobPtr)

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
      driveInitTb(dut.io)
      dut.clockDomain.waitSampling(5)
      println("SIM: ROB Full Test: Start")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x100, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 1: robPtr=$idx0, canAllocNext=$canAlloc0")
      assert(canAlloc0, "ROB (depth 2) should allow second allocation after first")

      val (idx1, canAlloc1) = driveAllocationTb(dut.io, 0, 0x104, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 2: robPtr=$idx1, canAllocNext=$canAlloc1")
      dut.clockDomain.waitSampling(1)
      val canAlloc2 = dut.io.allocResponsesCanAlloc(0).toBoolean
      assert(
        !canAlloc2,
        "ROB (depth 2) should be full after two allocations, canAllocate(0) for next op should be false."
      )

      println("SIM: Attempting to allocate to full ROB via TestBench.io...")
      // 再次调用 driveAllocationTb，它会返回当前周期的 canAlloc 状态
      val (idx2, canAlloc3) = driveAllocationTb(dut.io, 0, 0x108, _ => {}, dut.clockDomain, pCfgFull)
      ParallaxLogger.log(s"SIM: Alloc 3 (attempt): robPtr=$idx2, canAllocNext=$canAlloc2")
      // 此时 canAlloc2 应该仍然是 false，因为 ROB 仍然是满的
      assert(!canAlloc2, "canAllocate should remain false when ROB is full and allocation is attempted")

      println("Test 'ROBPlugin - ROB Full Scenario' PASSED")
    }
  }
// TC_ROB_003: ROB Full, then Commit, then Allocate
  test("ROBPlugin - ROB Full, Commit, then Allocate") {
    val currentTestCfg = pCfgFull // robD = 2
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      driveInitTb(dut.io)
      dut.clockDomain.waitSampling(5)

      var lastCommittedRobPtr = -1
      FlowMonitor(dut.io.commitProvidedSlots(0), dut.clockDomain) { payload =>
        ParallaxLogger.log(
          s"SIM: Commit monitor: PC=0x${payload.payload.pc.toLong.toHexString}, ROB Idx=${payload.payload.uop.robPtr.toInt}"
        )
        lastCommittedRobPtr = payload.payload.uop.robPtr.toInt
      }

      println("SIM: Allocating to fill ROB (depth 2)...")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x100, _ => {}, dut.clockDomain, currentTestCfg)
      assert(idx0 == 0, s"Expected idx0=0, got $idx0")
      assert(canAlloc0, "Should be able to alloc second op")

      val (idx1, canAlloc1) = driveAllocationTb(dut.io, 0, 0x104, _ => {}, dut.clockDomain, currentTestCfg)
      assert(idx1 == 1, s"Expected idx1=1, got $idx1")
      dut.clockDomain.waitSampling(1) // Allow canAlloc to propagate
      val canAlloc2 = dut.io.allocResponsesCanAlloc(0).toBoolean
      assert(!canAlloc2, "ROB should be full, canAllocNext should be false")

      println(s"SIM: ROB is full. Writing back idx0 ($idx0)...")
      driveWritebackTb(dut.io, 0, idx0, false, 0, dut.clockDomain)

      println(s"SIM: Waiting for idx0 ($idx0) to be committable...")
      val commitTimeout = dut.clockDomain.waitSamplingWhere(timeout = 50) {
        dut.io.commitProvidedSlots(0).valid.toBoolean &&
        dut.io.commitProvidedSlots(0).payload.payload.uop.robPtr.toInt == idx0
      }
      assert(!commitTimeout, s"Timeout waiting for ROB idx $idx0 to be committable.")
      assert(lastCommittedRobPtr == idx0)

      println(s"SIM: Committing idx0 ($idx0)...")
      driveCommitAcksTb(dut.io, Seq(true), dut.clockDomain)
      dut.clockDomain.waitSampling(2) // Allow commit to process and free up space

      println("SIM: ROB should have space now. Attempting new allocation...")
      // After commit, tailPtr moves, canAlloc should be true
      // The driveAllocationTb will sample canAlloc *after* the allocation request is seen by ROB
      // So we need to check the canAlloc *returned* by the *next* allocation attempt
      // Or, we can try to read dut.io.allocResponsesCanAlloc(0) directly for one cycle if ROB updates it combinationally
      // For now, let's rely on the returned canAlloc from a new allocation.
      // To be super sure about the canAlloc status *before* new allocation, we might need a dedicated signal or observe internal ROB state if possible.
      // However, driveAllocationTb's returned canAlloc is for the *next* potential allocation *after* the current one is processed.

      // Attempt allocation, expecting it to succeed and canAlloc to be potentially false if ROB becomes full again
      val (idx2, canAlloc3) = driveAllocationTb(dut.io, 0, 0x200, _ => {}, dut.clockDomain, currentTestCfg)
      ParallaxLogger.log(s"SIM: Alloc after commit: robPtr=$idx2, canAllocNext=$canAlloc3")
      assert(
        idx2 == 0,
        s"Expected new allocation to use ROB index 0 (or wrapped around), got $idx2."
      ) // Assuming ROB wraps and idx0 is now free
      assert(canAlloc3, "ROB should allow allocation after commit")
      // If ROB depth is 2, and idx0 (0) and idx1 (1) were allocated. idx0 committed.
      // New allocation should go into entry 0. ROB now contains [new, idx1]. It's full again.
      dut.clockDomain.waitSampling(1) // Allow canAlloc to propagate
      val canAlloc4 = dut.io.allocResponsesCanAlloc(0).toBoolean
      assert(!canAlloc4, "ROB should be full again after allocating into the freed slot.")

      println("Test 'ROBPlugin - ROB Full, Commit, then Allocate' PASSED")
    }
  }

// TC_ROB_004: Out-of-Order Writeback, In-Order Commit
  test("ROBPlugin - Out-of-Order Writeback, In-Order Commit") {
    val currentTestCfg = testPipelineConfig(robD = 4, allocW = 1, commitW = 1, numEus = 1) // Need ROB depth >= 3
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      driveInitTb(dut.io)
      dut.clockDomain.waitSampling(5)

      val committedOrder = mutable.ArrayBuffer[Int]()
      val committedPcs = mutable.ArrayBuffer[Long]()
      FlowMonitor(dut.io.commitProvidedSlots(0), dut.clockDomain) { payload =>
        val robPtr = payload.payload.uop.robPtr.toInt
        val pc = payload.payload.pc.toLong
        ParallaxLogger.log(s"SIM: Commit monitor: PC=0x${pc.toHexString}, ROB Idx=$robPtr")
        if (committedOrder.isEmpty || committedOrder.last != robPtr) {
          committedOrder += robPtr
          committedPcs += pc
        }
      }

      println("SIM: Allocating 3 instructions...")
      val (idx0, _) = driveAllocationTb(dut.io, 0, 0x100, _ => {}, dut.clockDomain, currentTestCfg) // robPtr 0
      val (idx1, _) = driveAllocationTb(dut.io, 0, 0x104, _ => {}, dut.clockDomain, currentTestCfg) // robPtr 1
      val (idx2, _) = driveAllocationTb(dut.io, 0, 0x108, _ => {}, dut.clockDomain, currentTestCfg) // robPtr 2
      assert(idx0 == 0 && idx1 == 1 && idx2 == 2)

      println("SIM: Writing back in out-of-order: idx1, then idx2, then idx0")
      driveWritebackTb(dut.io, 0, idx1, false, 0, dut.clockDomain)
      driveWritebackTb(dut.io, 0, idx2, false, 0, dut.clockDomain)
      driveWritebackTb(dut.io, 0, idx0, false, 0, dut.clockDomain) // Head instruction written back last

      println("SIM: Waiting for commits and acknowledging...")
      for (expectedRobPtr <- Seq(idx0, idx1, idx2)) {
        val commitTimeout = dut.clockDomain.waitSamplingWhere(timeout = 50) {
          dut.io.commitProvidedSlots(0).valid.toBoolean &&
          dut.io.commitProvidedSlots(0).payload.payload.uop.robPtr.toInt == expectedRobPtr
        }
        assert(!commitTimeout, s"Timeout waiting for ROB idx $expectedRobPtr to be committable.")
        driveCommitAcksTb(dut.io, Seq(true), dut.clockDomain)
        ParallaxLogger.log(s"SIM: Acknowledging commit of ROB idx $expectedRobPtr")
        dut.clockDomain.waitSampling() // Give a cycle for ack to be processed
      }
      dut.clockDomain.waitSampling(5) // Ensure all monitor events are processed

      assert(committedOrder.toList == List(0, 1, 2), s"Commit order incorrect: ${committedOrder.toList}")
      assert(
        committedPcs.toList == List(0x100, 0x104, 0x108),
        s"Committed PCs incorrect: ${committedPcs.map(_.toHexString).toList}"
      )

      println("Test 'ROBPlugin - Out-of-Order Writeback, In-Order Commit' PASSED")
    }
  }

// TC_ROB_005: Flush Empty ROB
  test("ROBPlugin - Flush Empty ROB") {
    val currentTestCfg = pCfgBase // robD = 8
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      driveInitTb(dut.io)

      println("SIM: ROB is empty. Flushing to newHead=0, newTail=0, newCount=0")
      driveFlushTb(dut.io, 0, 0, 0, dut.clockDomain)
      dut.clockDomain.waitSampling(2) // Allow flush to propagate

      println("SIM: Attempting allocation after flush...")
      val (idx0, canAlloc0) = driveAllocationTb(dut.io, 0, 0x300, _ => {}, dut.clockDomain, currentTestCfg)
      assert(idx0 == 0, s"Expected first allocation after flush to be ROB index 0, got $idx0")
      assert(canAlloc0, "Should be able to allocate further after first allocation post-flush")

      println("Test 'ROBPlugin - Flush Empty ROB' PASSED")
    }
  }

  test("ROBPlugin - Multiple Flush Ports Aggregation") {
    val currentTestCfg = pCfgBase // robD = 8
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.totalEuCount)
    
    // 创建一个简化的测试，只测试多个flush端口
    class SimpleMultiFlushTest extends Component {
      val robPlugin = new ROBPlugin(currentTestCfg, HardType(DummyUop2(currentTestCfg)), () => DummyUop2(currentTestCfg).setDefault())
      val framework = new Framework(List(robPlugin))
      
      // 获取ROB服务并创建多个flush端口
      val robService = framework.getService[ROBService[DummyUop2]]
      val flushPort1 = robService.newFlushPort()
      val flushPort2 = robService.newFlushPort() 
      val flushPort3 = robService.newFlushPort()
      val listeningPort = robService.getFlushListeningPort()
      
      // 测试IO
      val flush1Valid = in Bool()
      val flush2Valid = in Bool()
      val flush3Valid = in Bool()
      val flush1Reason = in(FlushReason())
      val flush2Reason = in(FlushReason())
      val flush3Reason = in(FlushReason())
      val flush1Target = in UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      val flush2Target = in UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      val flush3Target = in UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      
      val aggregatedValid = out Bool()
      val aggregatedReason = out(FlushReason())
      val aggregatedTarget = out UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      
      // 连接flush端口
      flushPort1.valid := flush1Valid
      flushPort1.payload.reason := flush1Reason
      flushPort1.payload.targetRobPtr := flush1Target
      
      flushPort2.valid := flush2Valid
      flushPort2.payload.reason := flush2Reason
      flushPort2.payload.targetRobPtr := flush2Target
      
      flushPort3.valid := flush3Valid
      flushPort3.payload.reason := flush3Reason
      flushPort3.payload.targetRobPtr := flush3Target
      
      // 观察聚合结果
      aggregatedValid := listeningPort.valid
      aggregatedReason := listeningPort.payload.reason
      aggregatedTarget := listeningPort.payload.targetRobPtr
    }
    
    val dut = simConfig.compile(new SimpleMultiFlushTest)
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      
      // 初始化
      dut.flush1Valid #= false
      dut.flush2Valid #= false
      dut.flush3Valid #= false
      dut.flush1Reason #= FlushReason.NONE
      dut.flush2Reason #= FlushReason.NONE
      dut.flush3Reason #= FlushReason.NONE
      dut.flush1Target #= 0
      dut.flush2Target #= 0
      dut.flush3Target #= 0
      dut.clockDomain.waitSampling(2)
      
      println("Test 1: No flush - aggregated should be invalid")
      assert(!dut.aggregatedValid.toBoolean, "Aggregated signal should be invalid when no flush is active")
      
      println("Test 2: Single flush port 1")
      dut.flush1Valid #= true
      dut.flush1Reason #= FlushReason.FULL_FLUSH
      dut.flush1Target #= 0
      dut.clockDomain.waitSampling(1)
      assert(dut.aggregatedValid.toBoolean, "Aggregated signal should be valid when port 1 is active")
      assert(dut.aggregatedReason.toEnum == FlushReason.FULL_FLUSH, "Should forward port 1 reason")
      assert(dut.aggregatedTarget.toInt == 0, "Should forward port 1 target")
      dut.flush1Valid #= false
      dut.clockDomain.waitSampling(1)
      
      println("Test 3: Single flush port 2")
      dut.flush2Valid #= true
      dut.flush2Reason #= FlushReason.ROLLBACK_TO_ROB_IDX
      dut.flush2Target #= 3
      dut.clockDomain.waitSampling(1)
      assert(dut.aggregatedValid.toBoolean, "Aggregated signal should be valid when port 2 is active")
      assert(dut.aggregatedReason.toEnum == FlushReason.ROLLBACK_TO_ROB_IDX, "Should forward port 2 reason")
      assert(dut.aggregatedTarget.toInt == 3, "Should forward port 2 target")
      dut.flush2Valid #= false
      dut.clockDomain.waitSampling(1)
      
      println("Test 4: Multiple flush ports (should see port 1 priority)")
      dut.flush1Valid #= true
      dut.flush2Valid #= true
      dut.flush3Valid #= true
      dut.flush1Reason #= FlushReason.FULL_FLUSH
      dut.flush2Reason #= FlushReason.ROLLBACK_TO_ROB_IDX
      dut.flush3Reason #= FlushReason.ROLLBACK_TO_ROB_IDX
      dut.flush1Target #= 1
      dut.flush2Target #= 2
      dut.flush3Target #= 5
      dut.clockDomain.waitSampling(1)
      assert(dut.aggregatedValid.toBoolean, "Aggregated signal should be valid when multiple ports are active")
      // 从日志看来，端口1（索引0）具有最高优先级
      assert(dut.aggregatedReason.toEnum == FlushReason.FULL_FLUSH, "Should forward port 1 reason")
      assert(dut.aggregatedTarget.toInt == 1, "Should forward port 1 target")
      dut.flush1Valid #= false
      dut.flush2Valid #= false  
      dut.flush3Valid #= false
      dut.clockDomain.waitSampling(1)
      
      println("Test 'ROBPlugin - Multiple Flush Ports Aggregation' PASSED")
    }
  }

  test("ROBPlugin - Multiple Flush Listeners") {
    val currentTestCfg = pCfgBase // robD = 8
    
    // 创建一个测试，验证多个监听者都能收到flush信号
    class MultiListenerFlushTest extends Component {
      val robPlugin = new ROBPlugin(currentTestCfg, HardType(DummyUop2(currentTestCfg)), () => DummyUop2(currentTestCfg).setDefault())
      val framework = new Framework(List(robPlugin))
      
      // 获取ROB服务
      val robService = framework.getService[ROBService[DummyUop2]]
      
      // 创建多个flush端口（发送者）
      val flushPort1 = robService.newFlushPort()
      val flushPort2 = robService.newFlushPort()
      
      // 创建多个监听端口（接收者）
      val listener1 = robService.getFlushListeningPort()
      val listener2 = robService.getFlushListeningPort()
      val listener3 = robService.getFlushListeningPort()
      
      // 测试IO - 发送者控制
      val flush1Valid = in Bool()
      val flush2Valid = in Bool()
      val flush1Reason = in(FlushReason())
      val flush2Reason = in(FlushReason())
      val flush1Target = in UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      val flush2Target = in UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      
      // 测试IO - 监听者观察
      val listener1Valid = out Bool()
      val listener2Valid = out Bool()
      val listener3Valid = out Bool()
      val listener1Reason = out(FlushReason())
      val listener2Reason = out(FlushReason())
      val listener3Reason = out(FlushReason())
      val listener1Target = out UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      val listener2Target = out UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      val listener3Target = out UInt(log2Up(currentTestCfg.robDepth) + 1 bits)
      
      // 连接发送者
      flushPort1.valid := flush1Valid
      flushPort1.payload.reason := flush1Reason
      flushPort1.payload.targetRobPtr := flush1Target
      
      flushPort2.valid := flush2Valid
      flushPort2.payload.reason := flush2Reason
      flushPort2.payload.targetRobPtr := flush2Target
      
      // 连接监听者
      listener1Valid := listener1.valid
      listener1Reason := listener1.payload.reason
      listener1Target := listener1.payload.targetRobPtr
      
      listener2Valid := listener2.valid
      listener2Reason := listener2.payload.reason
      listener2Target := listener2.payload.targetRobPtr
      
      listener3Valid := listener3.valid
      listener3Reason := listener3.payload.reason
      listener3Target := listener3.payload.targetRobPtr
    }
    
    val dut = simConfig.compile(new MultiListenerFlushTest)
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      
      // 初始化
      dut.flush1Valid #= false
      dut.flush2Valid #= false
      dut.flush1Reason #= FlushReason.NONE
      dut.flush2Reason #= FlushReason.NONE
      dut.flush1Target #= 0
      dut.flush2Target #= 0
      dut.clockDomain.waitSampling(2)
      
      println("Test 1: No flush - all listeners should be invalid")
      assert(!dut.listener1Valid.toBoolean, "Listener 1 should be invalid when no flush")
      assert(!dut.listener2Valid.toBoolean, "Listener 2 should be invalid when no flush")
      assert(!dut.listener3Valid.toBoolean, "Listener 3 should be invalid when no flush")
      
      println("Test 2: Single flush - all listeners should receive the same signal")
      dut.flush1Valid #= true
      dut.flush1Reason #= FlushReason.FULL_FLUSH
      dut.flush1Target #= 7
      dut.clockDomain.waitSampling(1)
      
      // 验证所有监听者都收到相同的信号
      assert(dut.listener1Valid.toBoolean, "Listener 1 should be valid")
      assert(dut.listener2Valid.toBoolean, "Listener 2 should be valid")
      assert(dut.listener3Valid.toBoolean, "Listener 3 should be valid")
      
      assert(dut.listener1Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 1 should receive correct reason")
      assert(dut.listener2Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 2 should receive correct reason")
      assert(dut.listener3Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 3 should receive correct reason")
      
      assert(dut.listener1Target.toInt == 7, "Listener 1 should receive correct target")
      assert(dut.listener2Target.toInt == 7, "Listener 2 should receive correct target")
      assert(dut.listener3Target.toInt == 7, "Listener 3 should receive correct target")
      
      dut.flush1Valid #= false
      dut.clockDomain.waitSampling(1)
      
      println("Test 3: Multiple flush sources - all listeners should receive aggregated signal")
      dut.flush1Valid #= true
      dut.flush2Valid #= true
      dut.flush1Reason #= FlushReason.FULL_FLUSH
      dut.flush2Reason #= FlushReason.ROLLBACK_TO_ROB_IDX
      dut.flush1Target #= 3
      dut.flush2Target #= 5
      dut.clockDomain.waitSampling(1)
      
      // 根据优先级逻辑，应该看到端口1的信号（FULL_FLUSH, target=3）
      assert(dut.listener1Valid.toBoolean, "Listener 1 should be valid during multi-flush")
      assert(dut.listener2Valid.toBoolean, "Listener 2 should be valid during multi-flush")
      assert(dut.listener3Valid.toBoolean, "Listener 3 should be valid during multi-flush")
      
      assert(dut.listener1Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 1 should receive priority signal")
      assert(dut.listener2Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 2 should receive priority signal")
      assert(dut.listener3Reason.toEnum == FlushReason.FULL_FLUSH, "Listener 3 should receive priority signal")
      
      assert(dut.listener1Target.toInt == 3, "Listener 1 should receive priority target")
      assert(dut.listener2Target.toInt == 3, "Listener 2 should receive priority target")
      assert(dut.listener3Target.toInt == 3, "Listener 3 should receive priority target")
      
      dut.flush1Valid #= false
      dut.flush2Valid #= false
      dut.clockDomain.waitSampling(1)
      
      println("Test 'ROBPlugin - Multiple Flush Listeners' PASSED")
    }
  }

  thatsAll()
}
