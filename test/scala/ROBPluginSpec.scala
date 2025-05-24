// testOnly parallax.test.scala.ROBPluginSpec
package parallax.test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.tester.SpinalSimFunSuite

import parallax.common._
import parallax.components.rob._
import parallax.utilities._

import scala.collection.mutable._
import scala.util.Random

case class DummyUop2(
    val config: PipelineConfig
) extends Bundle {
  val robIdx = UInt(config.robIdxWidth)
  val pc = UInt(config.pcWidth)

  def setDefault(): this.type = {
    this.robIdx := 0
    this.pc := 0
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    this.robIdx #= 0
    this.pc #= 0
    this
  }
}

// --- 辅助测试插件 ---
/** 模拟 Rename/Dispatch 阶段，请求 ROB 分配
  */
class TestAllocatorPlugin(
    id: String,
    val allocateWidth: Int,
    val pCfg: PipelineConfig,
    val uopHardType: HardType[DummyUop2]
) extends Plugin {
  setName(s"Allocator_${id}")
  var allocatePorts: Vec[(ROBAllocateSlot[DummyUop2])] = null
  var canAllocateVec: Vec[Bool] = null

  case class IO() extends Bundle {
    val allocationsIn = Vec(slave(Flow(ROBAllocateSlotPayloadForTest(pCfg, uopHardType))), allocateWidth)
    val allocatedRobIndicesOut = Vec(out(UInt(pCfg.robIdxWidth)), allocateWidth)
    val canAllocateOut = Vec(out(Bool()), allocateWidth)
  }
  val io = IO()
  io.simPublic()

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    allocatePorts = robService.getAllocatePorts(allocateWidth)
    canAllocateVec = robService.getCanAllocateVec(allocateWidth)
  }

  val logic = create late new Area {
    for (i <- 0 until allocateWidth) {
      allocatePorts(i).fire := io.allocationsIn(i).valid
      allocatePorts(i).uopIn := io.allocationsIn(i).payload.uop
      allocatePorts(i).pcIn := io.allocationsIn(i).payload.pc
      io.allocatedRobIndicesOut(i) := allocatePorts(i).robIdx
      io.canAllocateOut(i) := canAllocateVec(i)
    }
  }
}
// Helper bundle for TestAllocatorPlugin's input Flow
case class ROBAllocateSlotPayloadForTest(pCfg: PipelineConfig, uopHardType: HardType[DummyUop2]) extends Bundle {
  val uop = uopHardType()
  val pc = UInt(pCfg.pcWidth)
}

/** 模拟执行单元 (EU)，向 ROB 写回完成状态
  */
class TestEuPlugin(
    euName: String,
    val pCfg: PipelineConfig
) extends Plugin {
  setName(euName)
  var writebackPort: (ROBWritebackPort[DummyUop2]) = null

  case class IO() extends Bundle {
    val writebackIn = slave(Flow(RobCompletionMsg(pCfg)))
  }
  val io = IO()
  io.simPublic()

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    writebackPort = robService.newWritebackPort(euName)
  }

  val logic = create late new Area {
    writebackPort.fire := io.writebackIn.valid
    writebackPort.robIdx := io.writebackIn.payload.robIdx
    writebackPort.exceptionOccurred := io.writebackIn.payload.hasException
    writebackPort.exceptionCodeIn := io.writebackIn.payload.exceptionCode
  }
}

/** 模拟 Commit 阶段，从 ROB 获取并确认提交
  */
class TestCommitterPlugin(
    id: String,
    val commitWidth: Int,
    val pCfg: PipelineConfig
) extends Plugin {
  setName(s"Committer_${id}")
  var commitSlots: Vec[(ROBCommitSlot[DummyUop2])] = null
  var commitAcks: Vec[(Bool)] = null

  case class IO() extends Bundle {
    val committableEntriesOut = Vec(
      master(
        Flow(
          ROBFullEntry[DummyUop2](
            ROBConfig(
              robDepth = pCfg.robDepth,
              pcWidth = pCfg.pcWidth,
              commitWidth = commitWidth,
              allocateWidth = pCfg.renameWidth,
              numWritebackPorts = pCfg.bypassNetworkSources,
              uopType = HardType(DummyUop2(pCfg)),
              defaultUop = () => DummyUop2(pCfg).setDefault(),
              exceptionCodeWidth = pCfg.exceptionCodeWidth
            )
          )
        )
      ),
      commitWidth
    )
    val commitAcksIn = Vec((Bool()), commitWidth)
  }
  val io = IO()
  io.simPublic()

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    commitSlots = robService.getCommitSlots(commitWidth)
    commitAcks = robService.getCommitAcks(commitWidth)
  }

  val logic = create late new Area {
    for (i <- 0 until commitWidth) {
      io.committableEntriesOut(i).valid := commitSlots(i).valid
      io.committableEntriesOut(i).payload := commitSlots(i).entry
      commitAcks(i) := io.commitAcksIn(i)
    }
  }
}

/** 模拟清空控制器
  */
class TestFlusherPlugin(id: String, val pCfg: PipelineConfig) extends Plugin {
  setName(s"Flusher_${id}")
  var flushPort: (Flow[ROBFlushCommand[DummyUop2]]) = null

  case class IO() extends Bundle {
    val flushCmdIn = slave(
      Flow(
        ROBFlushCommand(
          ROBConfig(
            robDepth = pCfg.robDepth,
            pcWidth = pCfg.pcWidth,
            commitWidth = pCfg.commitWidth,
            allocateWidth = pCfg.renameWidth,
            numWritebackPorts = pCfg.bypassNetworkSources,
            uopType = HardType(DummyUop2(pCfg)),
            defaultUop = () => DummyUop2(pCfg).setDefault(),
            exceptionCodeWidth = pCfg.exceptionCodeWidth
          )
        )
      )
    )
  }
  val io = IO()
  io.simPublic()

  val setup = create early new Area {
    val robService = getService[ROBService[DummyUop2]]
    flushPort = robService.getFlushPort()
  }

  val logic = create late new Area {
    flushPort.valid := io.flushCmdIn.valid
    flushPort.payload := io.flushCmdIn.payload
  }
}

// --- ROBPlugin Test Suite ---
class ROBPluginSpec extends CustomSpinalSimFunSuite { // or SpinalSimFunSuite

  // Helper to create a default PipelineConfig for tests
  def testPipelineConfig(
      robD: Int = 16, // ROB Depth
      allocW: Int = 2, // Allocate Width
      commitW: Int = 2, // Commit Width
      numEus: Int = 2 // Number of EUs, determines numWritebackPorts for exclusive scheme
  ) = PipelineConfig(
    robDepth = robD,
    renameWidth = allocW, // Assume alloc width matches rename for simplicity
    commitWidth = commitW,
    bypassNetworkSources = numEus // Critical for exclusive writeback port scheme
  )

  val UOP_HT = HardType(DummyUop2(testPipelineConfig())) // Default config for HardType
  def DEFAULT_UOP_FUNC = () => DummyUop2(testPipelineConfig()).setDefault()

  case class TestBenchConfig(
      pCfg: PipelineConfig,
      numAllocators: Int = 1, // For simplicity, usually 1 allocator group
      numEus: Int, // Number of EUs, must match pCfg.bypassNetworkSources for this test setup
      numCommitters: Int = 1, // Usually 1 committer group
      numFlushers: Int = 1
  )

  def createDut(tbCfg: TestBenchConfig) = {
    class TestBench extends Component {
      val database = new DataBase

      var allocatorInterfaces: Seq[TestAllocatorPlugin#IO] = null
      var euInterfaces: Seq[TestEuPlugin#IO] = null
      var committerInterfaces: Seq[TestCommitterPlugin#IO] = null
      var flusherInterfaces: Seq[TestFlusherPlugin#IO] = null

      lazy val allPlugins: Seq[Plugin] = {
        val robPlugin = new ROBPlugin[DummyUop2](tbCfg.pCfg, UOP_HT, DEFAULT_UOP_FUNC)

        val allocatorPlugins = ArrayBuffer[TestAllocatorPlugin]()
        if (tbCfg.numAllocators > 0) { // Assuming allocateWidth is per allocator plugin
          allocatorPlugins += new TestAllocatorPlugin("Alloc0", tbCfg.pCfg.renameWidth, tbCfg.pCfg, UOP_HT)
        }

        val euPlugins = ArrayBuffer[TestEuPlugin]()
        if (tbCfg.numEus > 0) { // Assuming numEus is per eu plugin
          euPlugins += new TestEuPlugin("Eu0", tbCfg.pCfg)
        }

        val committerPlugins = ArrayBuffer[TestCommitterPlugin]()
        if (tbCfg.numCommitters > 0) { // Assuming commitWidth is per committer plugin
          committerPlugins += new TestCommitterPlugin("Commit0", tbCfg.pCfg.commitWidth, tbCfg.pCfg)
        }

        val flusherPlugins = ArrayBuffer[TestFlusherPlugin]()
        if (tbCfg.numFlushers > 0) {
          flusherPlugins += new TestFlusherPlugin("Flush0", tbCfg.pCfg)
        }

        allocatorInterfaces = allocatorPlugins.map(_.io)
        euInterfaces = euPlugins.map(_.io)
        committerInterfaces = committerPlugins.map(_.io)
        flusherInterfaces = flusherPlugins.map(_.io)

        var allPlugins: Seq[Plugin] =
          Seq(robPlugin) ++ allocatorPlugins ++ euPlugins ++ committerPlugins ++ flusherPlugins

        allPlugins
      }

      val framework = ProjectScope(database) on new Framework(allPlugins)

      // Expose IOs for testbench driving/monitoring

      // Expose ROB internal state for assertions if ReorderBuffer component makes them public
      // val robInternalHead = robPlugin.robComponent.headPtr_reg.simPublic()
      // val robInternalTail = robPlugin.robComponent.tailPtr_reg.simPublic()
      // val robInternalCount = robPlugin.robComponent.count_reg.simPublic()
    }
    new TestBench()
  }

  // --- Helper functions for tests ---
  def driveAllocation(
      allocIo: TestAllocatorPlugin#IO,
      pc: Long,
      uopSetter: DummyUop2 => Unit,
      clockDomain: ClockDomain
  ): Unit = {
    allocIo.allocationsIn(0).valid #= true // Assuming single slot for simplicity in this helper
    allocIo.allocationsIn(0).payload.pc #= pc
    val tempUop = DummyUop2(testPipelineConfig()) // Use a consistent config for the temp Uop
    tempUop.setDefault() // Set defaults
    uopSetter(tempUop) // Apply specific test values
    allocIo.allocationsIn(0).payload.uop.assignFrom(tempUop) // Assign to hardware
    clockDomain.waitSampling()
    allocIo.allocationsIn(0).valid #= false
  }

  def driveWriteback(
      euIo: TestEuPlugin#IO,
      robIdx: Int,
      hasEx: Boolean,
      exCode: Int,
      clockDomain: ClockDomain
  ): Unit = {
    euIo.writebackIn.valid #= true
    euIo.writebackIn.payload.robIdx #= robIdx
    euIo.writebackIn.payload.hasException #= hasEx
    euIo.writebackIn.payload.exceptionCode #= exCode
    clockDomain.waitSampling()
    euIo.writebackIn.valid #= false
  }

  def driveCommitAcks(commitIo: TestCommitterPlugin#IO, acks: Seq[Boolean], clockDomain: ClockDomain): Unit = {
    acks.zipWithIndex.foreach { case (ack, i) => commitIo.commitAcksIn(i) #= ack }
    clockDomain.waitSampling()
    acks.indices.foreach { i => commitIo.commitAcksIn(i) #= false } // সাধারণত ack 是单周期的
  }

  // --- Test Cases ---
  val pCfgBase = testPipelineConfig(robD = 8, allocW = 1, commitW = 1, numEus = 1)

  test("ROBPlugin - Basic Allocate, Writeback, Commit") {
    val currentTestCfg = pCfgBase
    val tbCfg = TestBenchConfig(pCfg = currentTestCfg, numEus = currentTestCfg.bypassNetworkSources)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val allocIo = dut.allocatorInterfaces.head
      val euIo = dut.euInterfaces.head
      val commitIo = dut.committerInterfaces.head

      var allocatedRobIdx = -1
      var receivedCommitPc = -1L
      var receivedCommitRobIdx = -1

      // Monitor committer output
      FlowMonitor(commitIo.committableEntriesOut(0), dut.clockDomain) { payload =>
        if (commitIo.committableEntriesOut(0).valid.toBoolean) { // Check valid from ROB
          println(
            s"SIM: Commit monitor: ROB valid, ROB Idx=${payload.payload.uop.robIdx.toInt}, PC=0x${payload.payload.pc.toLong.toHexString}"
          )
          receivedCommitPc = payload.payload.pc.toLong
          receivedCommitRobIdx = payload.payload.uop.robIdx.toInt
        }
      }

      dut.clockDomain.waitSampling(5) // Initial settle

      // 1. Allocate
      println("SIM: Driving allocation...")
      driveAllocation(
        allocIo,
        0x1000,
        uop => {
          // Set other Uop fields if necessary for ROB logic or commit verification
        },
        dut.clockDomain
      )

      dut.clockDomain.waitSamplingWhere(timeout = 20)(
        allocIo.canAllocateOut(0).toBoolean == false
      ) // Wait until canAllocate potentially drops after alloc
      assert(allocIo.allocatedRobIndicesOut(0).toInt != -1, "ROB index should be valid after allocation")
      allocatedRobIdx = allocIo.allocatedRobIndicesOut(0).toInt
      println(s"SIM: Allocated. ROB Index = $allocatedRobIdx, CanAllocate(0)=${allocIo.canAllocateOut(0).toBoolean}")
      // assert(dut.robInternalCount.toInt == 1, s"ROB count should be 1, was ${dut.robInternalCount.toInt}")

      // 2. Writeback
      println(s"SIM: Driving writeback for ROB Index = $allocatedRobIdx...")
      driveWriteback(euIo, allocatedRobIdx, false, 0, dut.clockDomain)
      dut.clockDomain.waitSampling(5) // Allow ROB status to update

      // 3. Commit
      println(s"SIM: Waiting for commit valid for ROB Index = $allocatedRobIdx...")
      val commitTimeout = dut.clockDomain.waitSamplingWhere(timeout = 50) {
        commitIo.committableEntriesOut(0).valid.toBoolean &&
        commitIo.committableEntriesOut(0).payload.payload.uop.robIdx.toInt == allocatedRobIdx
      }
      assert(!commitTimeout, s"Timeout waiting for ROB idx $allocatedRobIdx to be committable.")
      println(s"SIM: ROB Idx $allocatedRobIdx is committable. Driving commit ack.")
      assert(
        receivedCommitPc == 0x1000,
        s"Committed PC mismatch. Expected 0x1000, got 0x${receivedCommitPc.toHexString}"
      )
      assert(
        receivedCommitRobIdx == allocatedRobIdx,
        s"Committed ROB Index mismatch. Expected $allocatedRobIdx, got $receivedCommitRobIdx"
      )

      driveCommitAcks(commitIo, Seq(true), dut.clockDomain)
      dut.clockDomain.waitSampling(5)
      // assert(dut.robInternalCount.toInt == 0, s"ROB count should be 0 after commit, was ${dut.robInternalCount.toInt}")
      // assert(dut.robInternalHead.toInt == (allocatedRobIdx + 1) % currentTestCfg.robDepth, "ROB head pointer mismatch")

      println("Test 'ROBPlugin - Basic Allocate, Writeback, Commit' PASSED")
    }
  }

  // --- Test for ROB Full ---
  val pCfgFull = testPipelineConfig(robD = 2, allocW = 1, commitW = 1, numEus = 1)
  test("ROBPlugin - ROB Full Scenario") {
    val tbCfg = TestBenchConfig(pCfg = pCfgFull, numEus = pCfgFull.bypassNetworkSources)
    val dut = simConfig.compile(createDut(tbCfg))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val allocIo = dut.allocatorInterfaces.head

      println("SIM: ROB Full Test: Start")
      // Allocate 2 entries to fill ROB (depth 2)
      println("SIM: Allocating entry 0...")
      driveAllocation(allocIo, 0x100, _ => {}, dut.clockDomain)
      assert(allocIo.canAllocateOut(0).toBoolean, "ROB should allow first allocation")
      val idx0 = allocIo.allocatedRobIndicesOut(0).toInt
      println(s"SIM: Allocated entry 0 to robIdx $idx0")

      println("SIM: Allocating entry 1...")
      driveAllocation(allocIo, 0x104, _ => {}, dut.clockDomain)
      assert(allocIo.canAllocateOut(0).toBoolean, "ROB should allow second allocation if space for next")
      val idx1 = allocIo.allocatedRobIndicesOut(0).toInt
      println(s"SIM: Allocated entry 1 to robIdx $idx1")

      // ROB should now be full (or have no space for a new single allocation if allocW=1)
      // The canAllocate signal for the *next* potential allocation should be false.
      // To observe this, we need to wait a cycle for ROB's internal count to update
      // and propagate to canAllocate.
      dut.clockDomain.waitSampling(2)
      println(s"SIM: After 2 allocations, canAllocate(0) = ${allocIo.canAllocateOut(0).toBoolean}")
      assert(!allocIo.canAllocateOut(0).toBoolean, "ROB should be full, canAllocate(0) should be false.")

      // Attempt to allocate another one - it should not succeed this cycle if canAllocate is respected
      // Or, if Allocator doesn't respect canAllocate, ROB internal logic should prevent overwriting.
      // Our TestAllocatorPlugin drives 'fire' based on its input 'valid',
      // but ROB internal logic uses `allocPort.fire && spaceAvailableForThisSlot`.
      println("SIM: Attempting to allocate to full ROB...")
      allocIo.allocationsIn(0).valid #= true
      allocIo.allocationsIn(0).payload.pc #= 0x108
      // Set Uop for allocation
      val tempUop = DummyUop2(pCfgFull).setDefault()
      allocIo.allocationsIn(0).payload.uop.assignFrom(tempUop)

      dut.clockDomain.waitSampling()
      allocIo.allocationsIn(0).valid #= false
      // Assert that the previously allocated robIdx for this slot (idx1) has not changed,
      // meaning no new allocation actually happened for this slot.
      // Or, more directly, check ROB count.
      // For this test, we simply rely on canAllocate being false.
      // A more robust test would check ROB internal count if exposed.

      println("Test 'ROBPlugin - ROB Full Scenario' PASSED")
    }
  }

  // TODO: Add more test cases:
  // - Multiple allocations in parallel
  // - Multiple commits in parallel
  // - Writeback with exception, commit stops at exception
  // - Flush test (basic flush, flush after mispredict)
  // - Head/Tail pointer wrapping

  thatsAll()
}
