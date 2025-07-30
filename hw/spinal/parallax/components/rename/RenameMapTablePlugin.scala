package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Plugin, Service}
import parallax.issue.RatControlService

/**
 * RenameMapTablePlugin wraps RenameMapTable as a service provider.
 * This plugin provides RatControlService interface for other components.
 */
class RenameMapTablePlugin(
    val ratConfig: RenameMapTableConfig
) extends Plugin with RatControlService {
  
  val early_setup = create early new Area {
    val rat = new RenameMapTable(ratConfig)
  }

  // Implement RatControlService by directly returning RAT's ports (following BPU pattern)
  def getDebugAratUsedMask(): Bits = early_setup.rat.io.aratUsedMask
  override def getCurrentState(): RatCheckpoint = early_setup.rat.io.currentState
  override def getCommitPort(): RatCommitUpdatePort = early_setup.rat.io.commitUpdatePort
  override def newCheckpointRestorePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointRestore
  override def getReadPorts(): Vec[RatReadPort] = early_setup.rat.io.readPorts
  override def getWritePorts(): Vec[RatWritePort] = early_setup.rat.io.writePorts
  
  // BACKWARD COMPATIBILITY: Provide save port for existing tests
  override def newCheckpointSavePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointSave

  val logic = create late new Area {
    // No additional connections needed - service returns actual RAT ports directly
  }
}
