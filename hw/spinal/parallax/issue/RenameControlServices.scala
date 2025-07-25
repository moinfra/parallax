package parallax.issue


import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
import parallax.utilities.Service
import parallax.components.rename._

// RatCheckpoint 定义已经由您提供，这里不再重复

/**
 * Service to expose control ports of the Rename Alias Table (RAT).
 * Uses external management philosophy: external components read currentState and manage checkpoints.
 */
trait RatControlService extends Service {
  /** 
   * Get the current RAT state for external checkpoint management.
   * External components can read this to capture current mapping state.
   */
  def getCurrentState(): RatCheckpoint
  def getCommitPort(): RatCommitUpdatePort
  
  /** 
   * Get a master port to command the RAT to restore its state.
   * The controller drives this stream, providing the checkpoint data to restore.
   */
  def newCheckpointRestorePort(): Stream[RatCheckpoint]
  
  /**
   * Get RAT read ports for testing and external access.
   * Returns the read ports that can be driven by external components.
   */
  def getReadPorts(): Vec[RatReadPort]
  
  /**
   * Get RAT write ports for testing and external access.
   * Returns the write ports that can be driven by external components.
   */
  def getWritePorts(): Vec[RatWritePort]
  
  // BACKWARD COMPATIBILITY: Deprecated method for existing tests
  @deprecated("Use getCurrentState() to read current state instead", "1.0")
  def newCheckpointSavePort(): Stream[RatCheckpoint] = {
    // Return a dummy stream that's always ready but never used
    val dummyStream = Stream(RatCheckpoint(null)) // Will be overridden in implementation
    dummyStream.valid := False
    dummyStream.payload.assignDontCare()
    dummyStream
  }
}

/**
 * Service to expose control ports of the Free List.
 */
trait FreeListControlService extends Service {
  /** 
   * Get ports for recycling physical registers.
   * The Commit stage will drive these ports.
   */
  def getFreePorts(): Vec[SimpleFreeListFreePort]
  def getAllocatePorts(): Vec[SimpleFreeListAllocatePort]
  /** 
   * Get a master port to command the FreeList to restore its state.
   * The controller drives this stream.
   */
  def newRestorePort(): Bool
  def getNumFreeRegs(): UInt
}

/**
 * Service to expose control ports of the Free List.
 */
trait SSFreeListControlService extends Service {
  /** 
   * Get ports for recycling physical registers.
   * The Commit stage will drive these ports.
   */
  def getFreePorts(): Vec[SuperScalarFreeListFreePort]
  def getAllocatePorts(): Vec[SuperScalarFreeListAllocatePort]
  def getCurrentFreeListState(): SuperScalarFreeListCheckpoint
  
  /** 
   * Get a master port to command the FreeList to restore its state.
   * The controller drives this stream.
   */
  def newRestorePort(): Stream[SuperScalarFreeListCheckpoint]
  def getNumFreeRegs(): UInt
}
