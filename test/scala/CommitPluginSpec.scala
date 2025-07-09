package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob.{ROBPlugin, ROBService, ROBCommitSlot, ROBConfig, FlushReason}
import parallax.issue._
import parallax.utilities._

/**
 * Test bench for CommitPlugin that provides a controlled environment
 * for testing commit stage operations.
 */
class CommitPluginTestBench(
    val pCfg: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Component {
  val io = new Bundle {
    // Control interfaces
    val commitEnable = in Bool()
    val triggerFlush = in Bool()
    
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
    val robSlots = Vec(master(ROBCommitSlot[RenamedUop](robConfig)), pCfg.commitWidth)
    val robAcks = Vec(out Bool(), pCfg.commitWidth)
    
    // FreeList monitoring
    val freeListOps = Vec(out(SuperScalarFreeListFreePort(flConfig)), flConfig.numFreePorts)
    
    // Statistics output
    val stats = out(CommitStats(pCfg))
    
    // RAT state for verification
    val ratState = out(RatCheckpoint(ratConfig))
  }
  
  val framework = new Framework(Seq(
    new RenameMapTablePlugin(ratConfig),
    new SuperScalarFreeListPlugin(flConfig),
    new ROBPlugin(pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg)),
    new CheckpointManagerPlugin(pCfg, ratConfig, flConfig),  // Add checkpoint manager
    new CommitPlugin(pCfg),
    new Plugin {
      val logic = create late new Area {
        // Get services
        val commitService = getService[CommitService]
        val ratService = getService[RatControlService]
        val flService = getService[FreeListControlService]
        val robService = getService[ROBService[RenamedUop]]
        val checkpointManager = getService[CheckpointManagerPlugin]
        
        // Connect control interfaces
        commitService.setCommitEnable(io.commitEnable)
        
        // Connect ROB slots (simulation drives these)
        val commitSlots = robService.getCommitSlots(pCfg.commitWidth)
        val commitAcks = robService.getCommitAcks(pCfg.commitWidth)
        
        for (i <- 0 until pCfg.commitWidth) {
          // Connect ROB commit slots (bidirectional connection)
          commitSlots(i) <> io.robSlots(i)
          io.robAcks(i) := commitAcks(i)
        }
        
        // Connect FreeList monitoring (read-only monitoring)
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
        
        // Connect statistics
        io.stats := commitService.getCommitStats()
        
        // Connect RAT state
        io.ratState := ratService.getCurrentState()
        
        // Initialize unused RAT ports
        val ratReadPorts = ratService.getReadPorts()
        val ratWritePorts = ratService.getWritePorts()
        
        for (i <- 0 until ratReadPorts.length) {
          ratReadPorts(i).archReg := 0
        }
        
        for (i <- 0 until ratWritePorts.length) {
          ratWritePorts(i).wen := False
          ratWritePorts(i).archReg := 0
          ratWritePorts(i).physReg := 0
        }
        
        // NOTE: Checkpoint interfaces are handled by the service implementation internally
        // We avoid accessing them directly to prevent SpinalHDL direction violations
        
        // Initialize unused ROB writeback ports (no execution units in test)
        for (i <- 0 until pCfg.totalEuCount) {
          val wbPort = robService.newWritebackPort(s"test_dummy_$i")
          wbPort.setIdle()
        }
        
        // Initialize unused ROB allocation ports (test bench drives these externally via io.robSlots)
        val allocPorts = robService.getAllocatePorts(pCfg.renameWidth)
        for (i <- 0 until pCfg.renameWidth) {
          allocPorts(i).valid := False
          allocPorts(i).uopIn.assignDontCare()
          allocPorts(i).pcIn := 0
        }
        
        // Initialize checkpoint manager triggers (not used in commit test)
        checkpointManager.saveCheckpointTrigger := False
        checkpointManager.restoreCheckpointTrigger := False
        
        // Handle flush trigger
        val flushPort = robService.getFlushPort()
        flushPort.valid := io.triggerFlush
        flushPort.payload.reason := FlushReason.ROLLBACK_TO_ROB_IDX
        flushPort.payload.targetRobPtr := 0
      }
    }
  ))
}

class CommitPluginSpec extends CustomSpinalSimFunSuite {
  
  def createTestBench(): (PipelineConfig, RenameMapTableConfig, SuperScalarFreeListConfig) = {
    val pCfg = PipelineConfig(
      commitWidth = 4,
      robDepth = 16,
      renameWidth = 4,
      aluEuCount = 1,  // Reduce to 1 to minimize writeback ports
      lsuEuCount = 0,  // Disable LSU to simplify test
      physGprCount = 16  // Match the FreeList numPhysRegs
    )
    val ratConfig = RenameMapTableConfig(
      archRegCount = 8,
      physRegCount = 16,
      numReadPorts = 4,
      numWritePorts = 4
    )
    val flConfig = SuperScalarFreeListConfig(
      numPhysRegs = 16,
      resetToFull = true,
      numInitialArchMappings = 8,
      numAllocatePorts = 4,
      numFreePorts = 4
    )
    
    (pCfg, ratConfig, flConfig)
  }
  
  test("CommitPlugin - Basic Commit Functionality") {
    val (pCfg, ratConfig, flConfig) = createTestBench()
    
    simConfig.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 100) { tb =>
      tb.clockDomain.forkStimulus(10)
      
      // Initialize
      tb.io.commitEnable #= true
      tb.io.triggerFlush #= false
      
      // Initialize ROB slots as empty
      for (i <- 0 until pCfg.commitWidth) {
        tb.io.robSlots(i).valid #= false
        tb.io.robSlots(i).entry.assignDontCare()
      }
      
      tb.clockDomain.waitSampling()
      
      println("[Test] === Basic Commit Test ===")
      
      // This test needs to work with the actual ROB flow, but since the test bench
      // design bypasses ROB storage, let's create a minimal working test by 
      // directly driving the robSlots interface as intended
      
      // Set up ROB slot with valid instruction for commit
      tb.io.robSlots(0).valid #= true
      tb.io.robSlots(0).entry.payload.uop.robPtr #= 0
      tb.io.robSlots(0).entry.payload.uop.rename.allocatesPhysDest #= true
      tb.io.robSlots(0).entry.payload.uop.rename.physDest.idx #= 10
      tb.io.robSlots(0).entry.payload.uop.rename.oldPhysDest.idx #= 5
      tb.io.robSlots(0).entry.payload.pc #= 100
      tb.io.robSlots(0).entry.status.done #= true  // Mark as done for commit
      tb.io.robSlots(0).entry.status.busy #= false
      tb.io.robSlots(0).entry.status.hasException #= false
      
      tb.clockDomain.waitSampling()
      
      // Verify commit acknowledgment
      val ack0 = tb.io.robAcks(0).toBoolean
      assert(ack0, "First instruction should be committed")
      
      // Verify physical register recycling
      val freeOp0 = tb.io.freeListOps(0)
      assert(freeOp0.enable.toBoolean, "Should recycle old physical register")
      assert(freeOp0.physReg.toBigInt == 5, "Should recycle register p5")
      
      // Verify statistics
      val stats = tb.io.stats
      assert(stats.committedThisCycle.toBigInt == 1, "Should commit 1 instruction")
      assert(stats.physRegRecycled.toBigInt == 1, "Should recycle 1 register")
      
      println("[Test] ✓ Basic commit functionality works correctly")
    }
  }
  
  test("CommitPlugin - In-Order Commit Logic") {
    val (pCfg, ratConfig, flConfig) = createTestBench()
    
    simConfig.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 200) { tb =>
      tb.clockDomain.forkStimulus(10)
      
      // Initialize
      tb.io.commitEnable #= true
      tb.io.triggerFlush #= false
      
      for (i <- 0 until pCfg.commitWidth) {
        tb.io.robSlots(i).valid #= false
        tb.io.robSlots(i).entry.assignDontCare()
      }
      
      tb.clockDomain.waitSampling()
      
      println("[Test] === In-Order Commit Test ===")
      
      // Set up scenario: slot 0 empty, slot 1 has instruction, slot 2 has instruction
      // Only slot 1 should NOT be committed due to in-order requirement
      
      tb.io.robSlots(0).valid #= false  // Empty slot
      
      // Slot 1 - valid instruction
      tb.io.robSlots(1).valid #= true
      tb.io.robSlots(1).entry.payload.uop.robPtr #= 1
      tb.io.robSlots(1).entry.payload.uop.rename.allocatesPhysDest #= true
      tb.io.robSlots(1).entry.payload.uop.rename.physDest.idx #= 11
      tb.io.robSlots(1).entry.payload.uop.rename.oldPhysDest.idx #= 6
      tb.io.robSlots(1).entry.payload.pc #= 104
      tb.io.robSlots(1).entry.status.done #= true  // Mark as done for commit
      tb.io.robSlots(1).entry.status.busy #= false
      tb.io.robSlots(1).entry.status.hasException #= false
      
      // Slot 2 - valid instruction
      tb.io.robSlots(2).valid #= true
      tb.io.robSlots(2).entry.payload.uop.robPtr #= 2
      tb.io.robSlots(2).entry.payload.uop.rename.allocatesPhysDest #= true
      tb.io.robSlots(2).entry.payload.uop.rename.physDest.idx #= 12
      tb.io.robSlots(2).entry.payload.uop.rename.oldPhysDest.idx #= 7
      tb.io.robSlots(2).entry.payload.pc #= 108
      tb.io.robSlots(2).entry.status.done #= true  // Mark as done for commit
      tb.io.robSlots(2).entry.status.busy #= false
      tb.io.robSlots(2).entry.status.hasException #= false
      
      tb.clockDomain.waitSampling()
      
      // Verify in-order commit behavior
      val ack0 = tb.io.robAcks(0).toBoolean
      val ack1 = tb.io.robAcks(1).toBoolean
      val ack2 = tb.io.robAcks(2).toBoolean
      
      assert(!ack0, "Empty slot 0 should not be committed")
      assert(!ack1, "Slot 1 should not be committed due to empty slot 0")
      assert(!ack2, "Slot 2 should not be committed due to in-order requirement")
      
      // Verify no recycling happened
      assert(!tb.io.freeListOps(0).enable.toBoolean, "No recycling should happen")
      assert(!tb.io.freeListOps(1).enable.toBoolean, "No recycling should happen")
      
      // Verify statistics
      val stats = tb.io.stats
      assert(stats.committedThisCycle.toBigInt == 0, "Should commit 0 instructions")
      
      println("[Test] ✓ In-order commit logic works correctly")
    }
  }
  
  test("CommitPlugin - Commit Enable/Disable") {
    val (pCfg, ratConfig, flConfig) = createTestBench()
    
    simConfig.compile(new CommitPluginTestBench(pCfg, ratConfig, flConfig)).doSim(seed = 400) { tb =>
      tb.clockDomain.forkStimulus(10)
      
      // Initialize with commit disabled
      tb.io.commitEnable #= false
      tb.io.triggerFlush #= false
      
      for (i <- 0 until pCfg.commitWidth) {
        tb.io.robSlots(i).valid #= false
        tb.io.robSlots(i).entry.assignDontCare()
      }
      
      tb.clockDomain.waitSampling()
      
      println("[Test] === Commit Enable/Disable Test ===")
      
      // Set up valid instruction
      tb.io.robSlots(0).valid #= true
      tb.io.robSlots(0).entry.payload.uop.robPtr #= 0
      tb.io.robSlots(0).entry.payload.uop.rename.allocatesPhysDest #= true
      tb.io.robSlots(0).entry.payload.uop.rename.physDest.idx #= 10
      tb.io.robSlots(0).entry.payload.uop.rename.oldPhysDest.idx #= 5
      tb.io.robSlots(0).entry.payload.pc #= 200
      tb.io.robSlots(0).entry.status.done #= true  // Mark as done for commit
      tb.io.robSlots(0).entry.status.busy #= false
      tb.io.robSlots(0).entry.status.hasException #= false
      
      tb.clockDomain.waitSampling()
      
      // Verify nothing is committed when disabled
      val ack0_disabled = tb.io.robAcks(0).toBoolean
      assert(!ack0_disabled, "Should not commit when disabled")
      assert(!tb.io.freeListOps(0).enable.toBoolean, "Should not recycle when disabled")
      
      // Enable commit
      tb.io.commitEnable #= true
      tb.clockDomain.waitSampling()
      
      // Verify instruction is now committed
      val ack0_enabled = tb.io.robAcks(0).toBoolean
      assert(ack0_enabled, "Should commit when enabled")
      assert(tb.io.freeListOps(0).enable.toBoolean, "Should recycle when enabled")
      
      println("[Test] ✓ Commit enable/disable works correctly")
    }
  }
  
  thatsAll()
}