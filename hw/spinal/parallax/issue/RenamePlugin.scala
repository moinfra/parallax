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
    issuePpl.retain()

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

  // --- MODIFICATION START: Simplified logic for Rename-only stage ---
  val logic = create late new Area {
    lock.await()
    val issuePpl = early_setup.issuePpl
    val s1_rename = early_setup.s1_rename
    val issueSignals = issuePpl.signals
    
    val renameUnit = early_setup.renameUnit
    val rat = early_setup.rat
    val freeList = early_setup.freeList

    // --- 1. Connect data paths ---
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

    // --- 2. Define HALT condition (only depends on FreeList) ---
    val willNeedPhysRegs = decodedUopsIn(0).isValid && decodedUopsIn(0).writeArchDestEn
    val notEnoughPhysRegs = freeList.io.numFreeRegs < Mux(willNeedPhysRegs, U(1), U(0))
    s1_rename.haltWhen(notEnoughPhysRegs)

    // --- 3. Drive state-changing requests only when firing ---
    val fire = s1_rename.isFiring
    for(i <- 0 until pipelineConfig.renameWidth) {
      val needsReg = renameUnit.io.numPhysRegsRequired > i
      freeList.io.allocate(i).enable := fire && needsReg
    }
    report(L"DEBUG: s1_rename.isFiring=${s1_rename.isFiring}, decodedUopsIn(0).isValid=${decodedUopsIn(0).isValid}")

    // --- 4. Connect outputs ---
    // The output RenamedUop will have a garbage robPtr, which is fine.
    // It will be overwritten in the next stage.
    s1_rename(issueSignals.RENAMED_UOPS) := renameUnit.io.renamedUopsOut
    
    issuePpl.release()
  }
  // --- MODIFICATION END --
}
