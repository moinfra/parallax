package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Plugin, Service}
import parallax.issue._

/**
 * SuperScalarFreeListPlugin wraps SuperScalarFreeList as a service provider.
 * This plugin provides FreeListControlService interface for other components.
 */
class SuperScalarFreeListPlugin(
    val flConfig: SimpleFreeListConfig
) extends Plugin with SSFreeListControlService {
  
  val early_setup = create early new Area {
    val freeList = new SuperScalarFreeList(flConfig)
  }

  // Implement FreeListControlService
  override def getCurrentFreeListState(): SuperScalarFreeListCheckpoint = early_setup.freeList.io.currentState
  override def getFreePorts(): Vec[SuperScalarFreeListFreePort] = early_setup.freeList.io.free
  override def newRestorePort(): Stream[SuperScalarFreeListCheckpoint] = early_setup.freeList.io.restoreState

  override def getAllocatePorts(): Vec[SuperScalarFreeListAllocatePort] = early_setup.freeList.io.allocate

  override def getNumFreeRegs(): UInt = early_setup.freeList.io.numFreeRegs

  val logic = create late new Area {
    // FreeList is ready - no additional logic needed for basic service
  }
}
