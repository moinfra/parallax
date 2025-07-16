// filename: src/test/scala/CommitPluginSpec.scala
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob._
import parallax.issue._ // 导入 CommitPlugin, CheckpointManagerPlugin, CommitService, CheckpointManagerService
import parallax.utilities._ // 导入 Plugin, Service, ParallaxLogger, CustomSpinalSimFunSuite, Formattable, HasRobPtr

/** Test bench for CommitPlugin that provides a controlled environment
  * for testing commit stage operations. This version uses a MockROBService
  * to isolate the CommitPlugin for true unit testing.
  */
class CommitPluginTestBench(
    val pCfg: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Component {
  val io = new Bundle {
    // Control interfaces
    val commitEnable = in Bool ()
    val triggerFlush = in Bool ()

    // ROB simulation interface
    val robConfig = ROBConfig(
      robDepth = pCfg.robDepth,
      pcWidth = pCfg.pcWidth,
      commitWidth = pCfg.commitWidth,
      allocateWidth = pCfg.renameWidth,
      numWritebackPorts = pCfg.totalEuCount,
      uopType = HardType(RenamedUop(pCfg)),
      defaultUop = () => RenamedUop(pCfg),
      exceptionCodeWidth = pCfg.exceptionCodeWidth
    )
    val robSlotsInput = Vec(in(ROBCommitSlot[RenamedUop](robConfig)), pCfg.commitWidth)
    report(L"robSlotsInput(0).valid: ${robSlotsInput(0).valid}\n")
    val robAcksObserve = Vec(out Bool (), pCfg.commitWidth)

    // FreeList monitoring
    val freeListOps = Vec(out(SuperScalarFreeListFreePort(flConfig)), flConfig.numFreePorts)

    // Statistics output
    val stats = out(CommitStats(pCfg))

    // RAT state for verification
    val ratState = out(RatCheckpoint(ratConfig))

    // CheckpointManager restore trigger for observation
    val checkpointRestoreTrigger = out Bool ()
  }

  // A mock ROB service implementation to provide only the necessary interfaces for CommitPlugin.
  // This avoids including the full, complex ROBPlugin in a unit test.
  class MockROBService extends Plugin with ROBService[RenamedUop] {
    // --- Interfaces needed by CommitPlugin ---
    val commitSlots = Vec(ROBCommitSlot[RenamedUop](io.robConfig), pCfg.commitWidth)
    val commitAcks = Vec(Bool(), pCfg.commitWidth) // 新增，testbench 驱动 commit handshake
    val flushPort = Flow(ROBFlushPayload(pCfg.robPtrWidth))

    override def getCommitSlots(width: Int): Vec[ROBCommitSlot[RenamedUop]] = {
      require(width == pCfg.commitWidth, "MockROBService commit width mismatch")
      println("CALL getCommitSlots")
      commitSlots
    }
    override def getCommitAcks(width: Int): Vec[Bool] = {
      require(width == pCfg.commitWidth, "MockROBService ack width mismatch")
      println("CALL getCommitAcks")
      commitAcks
    }
    override def newFlushPort(): Flow[ROBFlushPayload] = {
      println("CALL getFlushPort")
      flushPort
    }
    
    override def getFlushListeningPort(): Flow[ROBFlushPayload] = {
      println("CALL getFlushListeningPort")
      flushPort
    }

    // --- Unused interfaces required by the trait, provide dummy implementations ---
    override def getAllocatePorts(width: Int): Vec[ROBAllocateSlot[RenamedUop]] = {
      println("CALL getAllocatePorts")
      null
    }
    override def getCanAllocateVec(width: Int): Vec[Bool] = {
      println("CALL getCanAllocateVec")
      null
    }
    override def newWritebackPort(euName: String): ROBWritebackPort[RenamedUop] = {
      println("CALL newWritebackPort")
      null
    }
  }

  val framework = new Framework(
    Seq(
      new RenameMapTablePlugin(ratConfig),
      new SuperScalarFreeListPlugin(flConfig),
      new MockROBService(), // ADDED: The lightweight mock service
      new CheckpointManagerPlugin(pCfg, ratConfig, flConfig),
      new CommitPlugin(pCfg),
      new Plugin {
        val logic = create late new Area {
          // Get services
          val commitService = getService[CommitService]
          val ratService = getService[RatControlService]
          val flService = getService[FreeListControlService]
          val robService = getService[ROBService[RenamedUop]] // This will get our MockROBService
          val checkpointManager = getService[CheckpointManagerService]

          // Cast to MockROBService to access its internal ports
          val mockRob = robService.asInstanceOf[MockROBService]

          // --- Connect Test IO to the Mock Service ---
          // Drive the mock service's inputs from the test bench IO
          mockRob.commitSlots := io.robSlotsInput
          // Observe the mock service's outputs via the test bench IO
          io.robAcksObserve := mockRob.commitAcks
          // Drive the mock flush port
          mockRob.flushPort.valid := io.triggerFlush
          mockRob.flushPort.payload.reason := FlushReason.ROLLBACK_TO_ROB_IDX
          mockRob.flushPort.payload.targetRobPtr := 0

          // Connect control interfaces
          commitService.setCommitEnable(io.commitEnable)

          // Connect FreeList monitoring
          val freePorts = flService.getFreePorts()
          for (i <- 0 until flConfig.numFreePorts) {
            if (i < freePorts.length) {
              io.freeListOps(i).enable := freePorts(i).enable
              io.freeListOps(i).physReg := freePorts(i).physReg
            } else {
              io.freeListOps(i).enable := False
              io.freeListOps(i).physReg := 0
            }
          }

          // Connect statistics and other observation ports
          io.stats := commitService.getCommitStatsReg()
          io.ratState := ratService.getCurrentState()
          io.checkpointRestoreTrigger := checkpointManager.getRestoreCheckpointTrigger()

          // Initialize unused ports from other services
          val ratReadPorts = ratService.getReadPorts()
          ratReadPorts.foreach(_.archReg := 0)
          val ratWritePorts = ratService.getWritePorts()
          ratWritePorts.foreach(p => { p.wen := False; p.archReg := 0; p.physReg := 0 })

          val saveCheckpointTrigger = checkpointManager.getSaveCheckpointTrigger()
          saveCheckpointTrigger := False
        }
      }
    )
  )
}

// ===================================================================================================
// CommitPlugin Simulation Specification (No changes needed here)
// ===================================================================================================

class CommitPluginSpec extends CustomSpinalSimFunSuite {

  def createTestBenchConfig(): (PipelineConfig, RenameMapTableConfig, SuperScalarFreeListConfig) = {
    val pCfg = PipelineConfig(
      commitWidth = 2,
      robDepth = 16,
      renameWidth = 2,
      aluEuCount = 1,
      lsuEuCount = 0,
      physGprCount = 16
    )
    val ratConfig = RenameMapTableConfig(
      archRegCount = 8,
      physRegCount = 16,
      numReadPorts = 2,
      numWritePorts = 2
    )
    val flConfig = SuperScalarFreeListConfig(
      numPhysRegs = 16,
      resetToFull = true,
      numInitialArchMappings = 8,
      numAllocatePorts = 2,
      numFreePorts = 2
    )

    (pCfg, ratConfig, flConfig)
  }

  def driveRobSlot(
      slot: ROBCommitSlot[RenamedUop],
      robPtr: BigInt,
      pc: BigInt,
      allocatesPhysDest: Boolean,
      physDest: BigInt,
      oldPhysDest: BigInt,
      done: Boolean
  ): Unit = {
    slot.valid #= true
    slot.entry.payload.uop.robPtr #= robPtr
    slot.entry.payload.uop.rename.allocatesPhysDest #= allocatesPhysDest
    slot.entry.payload.uop.rename.physDest.idx #= physDest
    slot.entry.payload.uop.rename.oldPhysDest.idx #= oldPhysDest
    slot.entry.payload.pc #= pc
    slot.entry.status.done #= done
    slot.entry.status.busy #= false
    slot.entry.status.hasException #= false
    slot.entry.status.genBit #= robPtr.testBit(slot.entry.payload.uop.robPtr.getWidth - 1)
  }

  def clearRobSlot(slot: ROBCommitSlot[RenamedUop]): Unit = {
    slot.valid #= false
    slot.entry.assignDontCare()
  }

  test("CommitPlugin - Basic Commit Functionality (Single Instruction)") {
    val (pCfg, ratConfig, flConfig) = createTestBenchConfig()

    simConfig.withWave.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 100) { tb =>
      tb.clockDomain.forkStimulus(10)

      // Initialize control signals
      tb.io.commitEnable #= true
      tb.io.triggerFlush #= false

      // Initialize ROB slots as empty
      for (i <- 0 until pCfg.commitWidth) {
        clearRobSlot(tb.io.robSlotsInput(i))
      }

      tb.clockDomain.waitSampling()
      tb.clockDomain.waitSampling()

      println("\n[Test] === Basic Commit Test (Single Instruction) ===")

      // --- Cycle 1: ROB outputs Instruction 0 (PC=100, robPtr=0) ---
      println("--- Cycle 1: Testing commit of first instruction ---")
      driveRobSlot(
        tb.io.robSlotsInput(0),
        robPtr = 0,
        pc = 100,
        allocatesPhysDest = true,
        physDest = 10,
        oldPhysDest = 5,
        done = true
      )

      tb.clockDomain.waitSampling()

      val ack0_cyc1 = tb.io.robAcksObserve(0).toBoolean
      assert(ack0_cyc1, "Cycle 1: Instruction 0 should be committed (robAcks(0) should be true)")
      assert(tb.io.stats.committedThisCycle.toBigInt == 1, "Cycle 1: Should commit 1 instruction")
      assert(tb.io.stats.physRegRecycled.toBigInt == 1, "Cycle 1: Should recycle 1 register")
      assert(tb.io.freeListOps(0).enable.toBoolean, "Cycle 1: FreeList op 0 should be enabled")
      assert(tb.io.freeListOps(0).physReg.toBigInt == 5, "Cycle 1: FreeList op 0 should recycle p5")
      println(
        s"Cycle 1: Committed PC=100. Stats: CommittedThisCycle=${tb.io.stats.committedThisCycle.toBigInt}, Recycled=${tb.io.stats.physRegRecycled.toBigInt}"
      )

      // --- Cycle 2: ROB outputs Instruction 1 (PC=104, robPtr=1) ---
      println("--- Cycle 2: Testing commit of second instruction ---")
      clearRobSlot(tb.io.robSlotsInput(0))
      tb.clockDomain.waitSampling()
      driveRobSlot(
        tb.io.robSlotsInput(0),
        robPtr = 1,
        pc = 104,
        allocatesPhysDest = true,
        physDest = 11,
        oldPhysDest = 6,
        done = true
      )

      tb.clockDomain.waitSampling()

      val ack0_cyc2 = tb.io.robAcksObserve(0).toBoolean
      assert(ack0_cyc2, "Cycle 2: Instruction 1 should be committed (robAcks(0) should be true)")
      assert(tb.io.stats.committedThisCycle.toBigInt == 1, "Cycle 2: Should commit 1 instruction")
      assert(tb.io.stats.totalCommitted.toBigInt == 2, "Cycle 2: Total committed should be 2")
      assert(tb.io.stats.physRegRecycled.toBigInt == 2, "Cycle 2: Total recycled should be 2")
      assert(tb.io.freeListOps(0).enable.toBoolean, "Cycle 2: FreeList op 0 should be enabled")
      assert(tb.io.freeListOps(0).physReg.toBigInt == 6, "Cycle 2: FreeList op 0 should recycle p6")
      println(
        s"Cycle 2: Committed PC=104. Stats: CommittedThisCycle=${tb.io.stats.committedThisCycle.toBigInt}, TotalCommitted=${tb.io.stats.totalCommitted.toBigInt}, Recycled=${tb.io.stats.physRegRecycled.toBigInt}"
      )

      // --- Cycle 3: No more instructions ---
      println("--- Cycle 3: No instructions to commit ---")
      clearRobSlot(tb.io.robSlotsInput(0))

      tb.clockDomain.waitSampling()

      assert(!tb.io.robAcksObserve(0).toBoolean, "Cycle 3: No instruction, no commit (robAcks(0) should be false)")
      assert(tb.io.stats.committedThisCycle.toBigInt == 0, "Cycle 3: Should commit 0 instructions")
      println(
        s"Cycle 3: No commits. Stats: CommittedThisCycle=${tb.io.stats.committedThisCycle.toBigInt}, TotalCommitted=${tb.io.stats.totalCommitted.toBigInt}"
      )

      println("[Test] ✓ Basic commit functionality works correctly with proper ROB slot progression")
    }
  }

  test("CommitPlugin - In-Order Commit Logic") {
    val (pCfg, ratConfig, flConfig) = createTestBenchConfig()

    simConfig.withWave.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 200) { tb =>
      tb.clockDomain.forkStimulus(10)

      tb.io.commitEnable #= true
      tb.io.triggerFlush #= false
      for (i <- 0 until pCfg.commitWidth) clearRobSlot(tb.io.robSlotsInput(i))
      tb.clockDomain.waitSampling()
      tb.clockDomain.waitSampling()

      println("\n[Test] === In-Order Commit Test ===")

      driveRobSlot(
        tb.io.robSlotsInput(0),
        robPtr = 0,
        pc = 100,
        allocatesPhysDest = true,
        physDest = 10,
        oldPhysDest = 5,
        done = false
      )
      driveRobSlot(
        tb.io.robSlotsInput(1),
        robPtr = 1,
        pc = 104,
        allocatesPhysDest = true,
        physDest = 11,
        oldPhysDest = 6,
        done = true
      )

      tb.clockDomain.waitSampling()

      assert(!tb.io.robAcksObserve(0).toBoolean, "Slot 0 not done, should not be committed (robAcks(0) false)")
      assert(
        !tb.io.robAcksObserve(1).toBoolean,
        "Slot 1 should not be committed due to slot 0 not being done (robAcks(1) false)"
      )
      assert(!tb.io.freeListOps(0).enable.toBoolean, "No recycling should happen for slot 0")
      assert(!tb.io.freeListOps(1).enable.toBoolean, "No recycling should happen for slot 1")
      val stats = tb.io.stats
      assert(stats.committedThisCycle.toBigInt == 0, "Should commit 0 instructions")
      println(s"In-Order Test: CommittedThisCycle=${stats.committedThisCycle.toBigInt}")

      println("[Test] ✓ In-order commit logic works correctly")
    }
  }

  test("CommitPlugin - Commit Enable/Disable") {
    val (pCfg, ratConfig, flConfig) = createTestBenchConfig()

    simConfig.withWave.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 400) { tb =>
      tb.clockDomain.forkStimulus(10)

      tb.io.commitEnable #= false
      tb.io.triggerFlush #= false
      for (i <- 0 until pCfg.commitWidth) clearRobSlot(tb.io.robSlotsInput(i))
      tb.clockDomain.waitSampling()
      tb.clockDomain.waitSampling()
      println("\n[Test] === Commit Enable/Disable Test ===")

      driveRobSlot(
        tb.io.robSlotsInput(0),
        robPtr = 0,
        pc = 200,
        allocatesPhysDest = true,
        physDest = 10,
        oldPhysDest = 5,
        done = true
      )

      tb.clockDomain.waitSampling()

      assert(!tb.io.robAcksObserve(0).toBoolean, "Should not commit when disabled (robAcks(0) false)")
      assert(tb.io.stats.committedThisCycle.toBigInt == 0, "Should commit 0 instructions when disabled")
      println(s"Disabled: CommittedThisCycle=${tb.io.stats.committedThisCycle.toBigInt}")

      tb.io.commitEnable #= true
      tb.clockDomain.waitSampling()
      tb.clockDomain.waitSampling()

      assert(tb.io.robAcksObserve(0).toBoolean, "Should commit when enabled (robAcks(0) true)")
      assert(tb.io.stats.committedThisCycle.toBigInt == 1, "Should commit 1 instruction when enabled")
      println(s"Enabled: CommittedThisCycle=${tb.io.stats.committedThisCycle.toBigInt}")

      println("[Test] ✓ Commit enable/disable works correctly")
    }
  }

  thatsAll()
}
