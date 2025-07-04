// filename: src/main/scala/parallax/issue/DispatchPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
import parallax.components.issue._
import parallax.utilities.ParallaxSim

class DispatchPlugin(val pCfg: PipelineConfig) extends Plugin with LockedImpl {
  
  assert(pCfg.renameWidth == 1, "This DispatchPlugin is designed for a rename/dispatch width of 1.")

  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val iqService = getService[IssueQueueService]
    issuePpl.retain()
    iqService.retain()

    val s3_dispatch = issuePpl.pipeline.s3_dispatch
    s3_dispatch(issuePpl.signals.RENAMED_UOPS)
  }

  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val iqService = setup.iqService
    val s3_dispatch = setup.s3_dispatch
    val issueSignals = issuePpl.signals
    
    val renamedUopIn = s3_dispatch(issueSignals.ALLOCATED_UOPS)(0)
    val decodedUop = renamedUopIn.decoded
    val robPtr = renamedUopIn.robPtr

    val iqRegistrations = iqService.getRegistrations
    val iqPorts = iqRegistrations.map(_._2)

    iqPorts.foreach(_.valid := False)
    iqPorts.foreach(_.payload.assignDontCare())

    val driveOutputValid = s3_dispatch.isValid && decodedUop.isValid

    val isRealOperation = Bool()
    when(decodedUop.uopCode === BaseUopCode.ALU || decodedUop.uopCode === BaseUopCode.SHIFT) {
      isRealOperation := decodedUop.writeArchDestEn
    } .elsewhen (
      decodedUop.uopCode === BaseUopCode.LOAD || 
      decodedUop.uopCode === BaseUopCode.STORE || 
      decodedUop.uopCode === BaseUopCode.BRANCH || 
      decodedUop.uopCode === BaseUopCode.JUMP_REG || 
      decodedUop.uopCode === BaseUopCode.MUL
    ) {
      isRealOperation := True
    } .otherwise {
      isRealOperation := False
    }

    val dispatchOH = B(iqRegistrations.map { case (uopCodes, _) =>
      uopCodes.map(decodedUop.uopCode === _).orR
    })
    val iqPortsReady = Vec(iqPorts.map(_.ready))
    val destinationIqReady = MuxOH(dispatchOH, iqPortsReady)
    val destinationReady = destinationIqReady || !isRealOperation

    for (((uopCodes, port), i) <- iqRegistrations.zipWithIndex) {
      when(driveOutputValid && dispatchOH(i) && isRealOperation) {
        port.valid := True
        port.payload.uop := renamedUopIn
      }
    }
    
    s3_dispatch.haltWhen(driveOutputValid && isRealOperation && !destinationReady)

    when(s3_dispatch.isFiring && decodedUop.isValid) {
      ParallaxSim.log(L"DispatchPlugin: Firing robPtr=${robPtr} (UopCode=${decodedUop.uopCode}), isRealOp=${isRealOperation}")
    }

    report(L"DEBUG: s3_dispatch.isFiring=${s3_dispatch.isFiring}, isReady=${s3_dispatch.isReady}, driveOutputValid=${driveOutputValid}, decodedUop.isValid=${decodedUop.isValid}, destinationReady=${destinationReady}")
    
    setup.issuePpl.release()
    setup.iqService.release()
  }
}
