package parallax.backend

import spinal.core._
import spinal.core.fiber.{Handle, Lock} 
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.misc.pipeline.CtrlLink 
import spinal.lib.StreamArbiterFactory
import spinal.lib.pipeline.Stage
import spinal.lib.pipeline.Pipeline
import spinal.lib.fsm._ 

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import parallax.utilities.LockedImpl
import parallax.common._
import parallax.PhysicalRegFreeService


case class CommitEntry() extends Bundle {
  val uop = MicroOp()
  val writebackValue = Bits(Config.XLEN bits)
  val writebackEnable = Bool()
  val fault = Bool()
  val pDest = UInt(Config.PHYS_REG_TAG_WIDTH)
  val archDest = UInt(Config.ARCH_REG_WIDTH)
  val stalePDest = UInt(Config.PHYS_REG_TAG_WIDTH)
}


class CommitPlugin(val executionUnitCount: Int) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val arbiter = StreamArbiterFactory.roundRobin.build(Fragment(CommitEntry()), executionUnitCount)
    val freeRegService = getService[PhysicalRegFreeService]
    val freePort = freeRegService.getFreePort() // Output Flow port
  }
  def arbiter: StreamArbiter[Fragment[CommitEntry]] = setup.arbiter // Expose arbiter

  val commitLogic = create late new Area {
    val inputCommitStream = setup.arbiter.io.output

    setup.freePort.valid := False // Default
    setup.freePort.payload.assignDontCare()

    inputCommitStream.ready := True // Assume commit is always ready (simplification)

    when(inputCommitStream.fire) {
      val commitData = inputCommitStream.payload.fragment

      // Free Stale Physical Register
      when(commitData.stalePDest =/= Config.R0_PHYS_TAG && !commitData.fault) {
        setup.freePort.valid := True
        setup.freePort.payload := commitData.stalePDest
      }

      // Architectural State Update (Simplified: Debug only)
      report(
        Seq(
          L"COMMIT: PC=0x${commitData.uop.pc}%x OP=${commitData.uop.opType} ",
          L"ArchRD=${commitData.archDest} PDest=${commitData.pDest} Stale=${commitData.stalePDest} ",
          L"WEn=${commitData.writebackEnable} WVal=0x${commitData.writebackValue}%x Fault=${commitData.fault}"
        )
      )
    }
  }
}
