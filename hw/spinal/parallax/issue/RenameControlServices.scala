package parallax.issue


import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.utilities.Service
import parallax.components.rename.RatCheckpoint
import parallax.components.rename.SuperScalarFreeListCheckpoint
import parallax.components.rename.SuperScalarFreeListFreePort

// RatCheckpoint 定义已经由您提供，这里不再重复

/**
 * Service to expose control ports of the Rename Alias Table (RAT).
 */
trait RatControlService extends Service {
  /** 
   * Get a master port to command the RAT to save its state.
   * The controller drives this stream. The payload contains the checkpoint data to be saved.
   * In many designs, the RAT just saves its *current* state, so the payload might just be an ID.
   * However, your RenameMapTableIO defines it as Stream[RatCheckpoint], implying the controller
   * might provide the state to save (e.g., from a speculative copy). We will match this.
   */
  def newCheckpointSavePort(): Stream[RatCheckpoint]
  
  /** 
   * Get a master port to command the RAT to restore its state.
   * The controller drives this stream, providing the checkpoint data to restore.
   */
  def newCheckpointRestorePort(): Stream[RatCheckpoint]
}

/**
 * Service to expose control ports of the Free List.
 */
trait FreeListControlService extends Service {
  /** 
   * Get ports for recycling physical registers.
   * The Commit stage will drive these ports.
   */
  def getFreePorts(): Vec[SuperScalarFreeListFreePort]
  
  /** 
   * Get a master port to command the FreeList to restore its state.
   * The controller drives this stream.
   */
  def newRestorePort(): Stream[SuperScalarFreeListCheckpoint]
}
