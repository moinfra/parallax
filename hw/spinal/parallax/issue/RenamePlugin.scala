// filename: src/main/scala/parallax/issue/RenamePlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob.ROBService
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin 
    with LockedImpl
    with RatControlService
    with FreeListControlService {

  val early_setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val robService = getService[ROBService[RenamedUop]]
    issuePpl.retain()
    robService.retain()

    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val rat = new RenameMapTable(ratConfig)
    val freeList = new SuperScalarFreeList(flConfig)
    
    val s1_rename = issuePpl.pipeline.s1_rename
    s1_rename(issuePpl.signals.DECODED_UOPS)
    s1_rename(issuePpl.signals.RENAMED_UOPS)
    s1_rename(issuePpl.signals.FLUSH_PIPELINE)
  }

  override def newCheckpointSavePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointSave
  override def newCheckpointRestorePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointRestore
  override def getFreePorts(): Vec[SuperScalarFreeListFreePort] = early_setup.freeList.io.free
  override def newRestorePort(): Stream[SuperScalarFreeListCheckpoint] = early_setup.freeList.io.restoreState

  val logic = create late new Area {
    lock.await()
    val issuePpl = early_setup.issuePpl
    val s1_rename = early_setup.s1_rename
    val issueSignals = issuePpl.signals
    val robService = early_setup.robService
    
    val renameUnit = early_setup.renameUnit
    val rat = early_setup.rat
    val freeList = early_setup.freeList

    // --- 1. Connect data paths to RenameUnit ---
    val decodedUopsIn = s1_rename(issueSignals.DECODED_UOPS)
    renameUnit.io.decodedUopsIn := decodedUopsIn
    
    for(i <- 0 until pipelineConfig.renameWidth) {
      renameUnit.io.physRegsIn(i) := freeList.io.allocate(i).physReg
    }
    
    rat.io.readPorts <> renameUnit.io.ratReadPorts
    
    rat.io.writePorts.zip(renameUnit.io.ratWritePorts).foreach { case (ratPort, ruPort) =>
      ratPort.wen     := ruPort.wen && s1_rename.isFiring
      ratPort.archReg := ruPort.archReg
      ratPort.physReg := ruPort.physReg
    }

    // --- 2. Independently check all resource availabilities ---
    val robAllocPort = robService.getAllocatePorts(pipelineConfig.renameWidth)(0)
    val notEnoughPhysRegs = freeList.io.numFreeRegs < renameUnit.io.numPhysRegsRequired
    val robIsFull = !robAllocPort.ready

    // --- 3. Define the halt condition based *only* on resource availability ---
    val resourcesNotReady = notEnoughPhysRegs || robIsFull
    s1_rename.haltWhen(s1_rename.isValid && resourcesNotReady)

    // --- 4. Drive state-changing requests only when firing ---
    val fire = s1_rename.isFiring
    
    for(i <- 0 until pipelineConfig.renameWidth) {
      val needsReg = renameUnit.io.numPhysRegsRequired > i
      freeList.io.allocate(i).enable := fire && needsReg
    }

    robAllocPort.valid := fire && decodedUopsIn(0).isValid
    robAllocPort.pcIn  := decodedUopsIn(0).pc
    val tempUopForRob = RenamedUop(pipelineConfig).setDefault(decoded = decodedUopsIn(0))
    robAllocPort.uopIn := tempUopForRob
    
    // --- 5. Connect outputs ---
    val finalRenamedUop = RenamedUop(pipelineConfig)
    finalRenamedUop.initWithRobPtr(renameUnit.io.renamedUopsOut(0), robPtr = robAllocPort.robPtr)
    s1_rename(issueSignals.RENAMED_UOPS)(0) := finalRenamedUop
    
    issuePpl.release()
    robService.release()
  }
}
