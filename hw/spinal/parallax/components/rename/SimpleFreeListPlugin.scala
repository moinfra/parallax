package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Plugin, Service}
import parallax.issue.FreeListControlService

/**
 * SimpleFreeListPlugin wraps SimpleFreeList as a service provider.
 * This plugin provides FreeListControlService interface for other components.
 */
class SimpleFreeListPlugin(
    val flConfig: SimpleFreeListConfig
) extends Plugin with FreeListControlService {
  
  val early_setup = create early new Area {
    val freeList = new SimpleFreeList(flConfig)
  }

  // Implement FreeListControlService
  override def getFreePorts(): Vec[SimpleFreeListFreePort] = early_setup.freeList.io.free
  override def newRestorePort(): Bool = early_setup.freeList.io.recover

  override def getAllocatePorts(): Vec[SimpleFreeListAllocatePort] = early_setup.freeList.io.allocate

  override def getNumFreeRegs(): UInt = early_setup.freeList.io.numFreeRegs

  val logic = create late new Area {
    // FreeList is ready - no additional logic needed for basic service
  }
}
