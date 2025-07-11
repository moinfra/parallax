package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rename._
import parallax.utilities.{Plugin, Service}
import parallax.utilities.LockedImpl

/**
 * Service interface for single checkpoint management in speculative execution.
 * Provides single-cycle save/restore operations for RAT and FreeList state.
 */
trait CheckpointManagerService extends Service with LockedImpl {
  /**
   * Trigger checkpoint save operation.
   * Single-cycle operation - when asserted, captures current RAT and FreeList state.
   */
  def getSaveCheckpointTrigger(): Bool

  /**
   * Trigger checkpoint restore operation.
   * Single-cycle operation - when asserted, restores RAT and FreeList to saved state.
   */
  def getRestoreCheckpointTrigger(): Bool
}

class CheckpointManagerPlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin with CheckpointManagerService {
  
  val enableLog = true
  
  // Service interface signals
  val saveCheckpointTrigger = Bool()
  val restoreCheckpointTrigger = Bool() 
  
  override def getSaveCheckpointTrigger(): Bool = saveCheckpointTrigger
  override def getRestoreCheckpointTrigger(): Bool = restoreCheckpointTrigger

  val setup = create early new Area {
    // Get the actual services that control RAT and FreeList
    val ratControlService = getService[RatControlService]
    val flControlService = getService[FreeListControlService]

    
    // Get actual checkpoint ports from the services
    val ratRestorePort = ratControlService.newCheckpointRestorePort()
    val flRestorePort = flControlService.newRestorePort()
  }

  val logic = create late new Area {
    val ratControlService = setup.ratControlService
    val flControlService = setup.flControlService
    val ratRestorePort = setup.ratRestorePort
    val flRestorePort = setup.flRestorePort

    // Single checkpoint storage
    val storedRatCheckpoint = Reg(RatCheckpoint(ratConfig))
    val storedFlCheckpoint = Reg(SuperScalarFreeListCheckpoint(flConfig))
    val hasValidCheckpoint = RegInit(False)
    
    // Initialize with proper initial state
    val initialRatCheckpoint = RatCheckpoint(ratConfig)
    for (i <- 0 until ratConfig.archRegCount) {
      if (i == 0) {
        initialRatCheckpoint.mapping(i) := U(0, ratConfig.physRegIdxWidth) // r0 -> p0
      } else {
        initialRatCheckpoint.mapping(i) := U(i, ratConfig.physRegIdxWidth) // rX -> pX
      }
    }
    
    val initialFlCheckpoint = SuperScalarFreeListCheckpoint(flConfig)
    val initialFreeMask = Bits(flConfig.numPhysRegs bits)
    initialFreeMask.clearAll()
    for (i <- flConfig.numInitialArchMappings until flConfig.numPhysRegs) {
      initialFreeMask(i) := True
    }
    initialFlCheckpoint.freeMask := initialFreeMask
    
    // Initialize storage
    storedRatCheckpoint init(initialRatCheckpoint)
    storedFlCheckpoint init(initialFlCheckpoint)
    
    // REAL SAVE OPERATION: Capture ACTUAL current state
    when(saveCheckpointTrigger) {
      // Capture the REAL current state from RAT service
      val currentRatState = ratControlService.getCurrentState()
      storedRatCheckpoint := currentRatState
      
      // Capture the REAL current state from FreeList service
      val currentFlState = flControlService.getCurrentFreeListState()
      storedFlCheckpoint := currentFlState
      
      hasValidCheckpoint := True
      
      if (enableLog) {
        report(L"[CheckpointManager] Checkpoint saved - captured REAL RAT and FreeList state (single-cycle)")
      }
    }
    
    // REAL RESTORE OPERATION: Send saved state to RAT and FreeList
    when(restoreCheckpointTrigger && hasValidCheckpoint) {
      // Drive RAT restore with REAL saved state
      ratRestorePort.valid := True
      ratRestorePort.payload := storedRatCheckpoint
      
      // Drive FreeList restore
      flRestorePort.valid := True
      flRestorePort.payload := storedFlCheckpoint
      
      if (enableLog) {
        report(L"[CheckpointManager] Checkpoint restored - restored REAL state (single-cycle)")
      }
    } otherwise {
      ratRestorePort.valid := False
      ratRestorePort.payload.assignDontCare()
      
      flRestorePort.valid := False
      flRestorePort.payload.assignDontCare()
    }
  }
}
