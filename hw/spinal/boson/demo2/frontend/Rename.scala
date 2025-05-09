package boson.demo2.frontend

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
import boson.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import boson.utilities.LockedImpl
import boson.demo2.common.Config
import boson.demo2.common.MicroOp
import boson.demo2.PhysicalRegFreeService
import boson.demo2.Stack



case class RenamedMicroOp() extends Bundle {
  val uop = MicroOp()
  val pDest = UInt(Config.PHYS_REG_TAG_WIDTH)
  val pSrc1 = UInt(Config.PHYS_REG_TAG_WIDTH)
  val pSrc2 = UInt(Config.PHYS_REG_TAG_WIDTH)
  val archDest = UInt(Config.ARCH_REG_WIDTH)
  val stalePDest = UInt(Config.PHYS_REG_TAG_WIDTH)
}



// --- Rename --- (**FIXED: Removed hardFork init**)
class RenamePlugin extends Plugin with LockedImpl with PhysicalRegFreeService {

  val freePortIn = Flow(UInt(Config.PHYS_REG_TAG_WIDTH)) // Input from Commit
  override def getFreePort(): Flow[UInt] = freePortIn

  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }

  val logic = create late new Area {
    val stage = setup.frontendPipeline.pipeline.rename
    import stage._

    // --- State ---
    val mappingTable = Mem.fill(Config.ARCH_REG_COUNT)(UInt(Config.PHYS_REG_TAG_WIDTH))
    mappingTable.initBigInt(
      (0 until Config.ARCH_REG_COUNT).map(i => BigInt(i))
    )

    val freeList = new Stack(
      dataType = UInt(Config.PHYS_REG_TAG_WIDTH),
      depth = Config.PHYS_REG_COUNT
    )
    freeList.setCompositeName(this, "freeListStack")

    // --- Initialization Logic using Reset ---
    val resetInitArea = new Area {
      val initCounter = Reg(UInt(log2Up(Config.PHYS_REG_COUNT + 1) bits)) init (0)
      val initActive = RegInit(True) // Start in init state

      // Assume push is normally driven by commit logic below
      // Temporarily override during init
      val initPushValid = False
      val initPushPayload = UInt(Config.PHYS_REG_TAG_WIDTH)
      initPushPayload.assignDontCare()

      val isReset = ClockDomain.current.isResetActive // Check reset state

      when(initActive) {
        val currentTag = U(Config.ARCH_REG_COUNT) + initCounter
        val numToInit = Config.PHYS_REG_COUNT - Config.ARCH_REG_COUNT
        val lastTagIndex = U(numToInit - 1)

        initPushValid := True // Try to push during init
        initPushPayload := currentTag.resized

        when(freeList.io.push.ready && initPushValid) { // If stack accepts the push from init
          initCounter := initCounter + 1
          when(initCounter === lastTagIndex) { // Check if last tag was just pushed
            initActive := False // Exit init state next cycle
          }
        }
        // If still in reset, keep initActive True, otherwise (reset just ended), exit init
        when(!isReset) {
          initActive := False
        }

      } elsewhen (isReset) {
        // If reset occurs *after* initialization phase, re-enter init state
        initActive := True
        initCounter := 0
      }

      // Multiplex push source: Init logic has priority
      freeList.io.push.valid := initPushValid || (freePortIn.valid && !initActive)
      freeList.io.push.payload := Mux(initActive, initPushPayload, freePortIn.payload)

      val blockCommitPush = initActive // Used below? Maybe not needed if muxing push source
    }

    // --- Input MicroOp ---
    val inputUop = stage(FrontendPipelineKeys.UOP)
    val renamedUop = RenamedMicroOp().assignDontCare()
    renamedUop.uop := inputUop
    renamedUop.archDest := inputUop.rd

    // --- Renaming Logic ---
    val archRd = inputUop.rd
    val archRj = inputUop.rj
    val archRk = inputUop.rk

    val pSrc1TagCurrent = mappingTable.readAsync(address = archRj)
    val pSrc2TagCurrent = mappingTable.readAsync(address = archRk)
    val stalePDestCurrent = mappingTable.readAsync(address = archRd)

    renamedUop.pSrc1 := Mux(archRj === 0, Config.R0_PHYS_TAG, pSrc1TagCurrent)
    renamedUop.pSrc2 := Mux(archRk === 0, Config.R0_PHYS_TAG, pSrc2TagCurrent)
    renamedUop.stalePDest := Mux(archRd === 0, Config.R0_PHYS_TAG, stalePDestCurrent)

    val writesReg = inputUop.writesArf()
    val allocationRequired = stage.isValid && writesReg

    val allocatedPDest = freeList.io.pop.payload
    freeList.io.pop.ready := False // Default

    renamedUop.pDest := Config.R0_PHYS_TAG

    when(allocationRequired) {
      when(freeList.io.pop.valid) { // Free register available?
        renamedUop.pDest := allocatedPDest
        freeList.io.pop.ready := stage.isReady // Pop only if rename stage not stalled

        when(stage.isFiring) { // Update RAT only when instruction advances
          mappingTable.write(
            address = archRd,
            data = allocatedPDest,
            enable = True
          )
        }
      } otherwise {
        stage.haltIt() // Stall if no free registers
      }
    }

    stage(FrontendPipelineKeys.RENAMED_UOP) := renamedUop

    // Free List Input Logic is handled by the muxing logic in resetInitArea
  } // End logic Area
}
