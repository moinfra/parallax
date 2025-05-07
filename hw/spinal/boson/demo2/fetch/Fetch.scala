package boson.demo2.fetch // Or a common package like boson.demo2.pipeline

import boson.utilities.{Plugin, LockedImpl, Service}
import boson.demo2.common.Config
import boson.demo2.frontend.FrontendPipelineData // Your pipeline data definitions
// Removed direct icache import from here, Fetch1Plugin will handle it
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.{Pipeline, Stage, Connection}
import boson.demo2.system.FetchMemorySubsystemConfig
import boson.demo2.system.FetchMemorySubsystem


// FetchPipeline definition (remains the same as your provided code)
class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val fetch0 = newStage() // PC Generation
    val fetch1 = newStage() // Instruction Fetch
    connect(fetch0, fetch1)(Connection.M2S())
  }
  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.fetch0
  def exitStage: Stage = pipeline.fetch1
}


// Fetch0Plugin: Generates PC
class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    // No direct dependency on ICache config here
  }

  val logic = create late new Area {
    val stageF0 = setup.fetchPipeline.pipeline.fetch0
    val pcReg = Reg(UInt(Config.XLEN bits)) init (0) // Start PC
    val pcPlus4 = pcReg + 4

    // Example redirect logic (adapt as needed)
    val redirectValid = stageF0(FrontendPipelineData.FETCH_REDIRECT_VALID)
    val redirectPc = stageF0(FrontendPipelineData.FETCH_REDIRECT_PC)
    
    // Stall F0 if it's producing output (isValid) but F1 is not ready (isStuck)
    // This backpressure is handled by the M2S connection and F1's stalls.

    when(stageF0.isFiring) { // isFiring means stage is valid and not stalled
      pcReg := Mux(redirectValid, redirectPc, pcPlus4)
    }

    // Output PC to F1 for fetching
    stageF0(FrontendPipelineData.PC_FETCH) := pcReg
    // We could also pass the current PC for F1 to output with the instruction
    // stageF0(FrontendPipelineData.PC) := pcReg // If F0 is the source of truth for PC
  }
}


// Fetch1Plugin: Instantiates FetchMemorySubsystem and performs the fetch
class Fetch1Plugin(val fmSubsystemConfig: FetchMemorySubsystemConfig) extends Plugin with LockedImpl {

  // Service for testbench to access FetchMemorySubsystem internals (e.g., flush, memory write)
  // This service is useful for testbenches.
  val fetchMemoryAccessService = create early new FetchMemorySubsystemAccessService {}

  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val f0 = fetchPipeline.pipeline.fetch0
    val f1 = fetchPipeline.pipeline.fetch1

    // F1 depends on PC_FETCH from F0
    f1(FrontendPipelineData.PC_FETCH)
  }

  val logic = create late new Area {
    val stageF1 = setup.fetchPipeline.pipeline.fetch1
    val fetchSystem = new FetchMemorySubsystem(fmSubsystemConfig)

    // Provide access to the instantiated fetchSystem for the testbench service
    fetchMemoryAccessService.fetchSystem = fetchSystem

    // Connect PC from F0 (now available as input in F1) to FetchMemorySubsystem
    fetchSystem.io.pcIn.valid := stageF1.valid // Input valid signal for F1
    fetchSystem.io.pcIn.payload := stageF1(FrontendPipelineData.PC_FETCH)
    // Backpressure: F1's input stream ready is driven by fetchSystem.io.pcIn.ready
    // The M2S connection from F0 to F1 handles stalling F0 if F1's input buffer is full
    // or if fetchSystem cannot accept new requests.
    // We need to ensure F1 stalls if fetchSystem.io.pcIn is not ready.
    stageF1.haltWhen(stageF1.valid && !fetchSystem.io.pcIn.ready)


    // Connect FetchMemorySubsystem output to F1 stage outputs
    stageF1(FrontendPipelineData.INSTRUCTION) := fetchSystem.io.dataOut.payload.instruction
    stageF1(FrontendPipelineData.PC) := fetchSystem.io.dataOut.payload.pc
    stageF1(FrontendPipelineData.FETCH_FAULT) := fetchSystem.io.dataOut.payload.fault

    // Control pipeline flow based on FetchMemorySubsystem's output stream
    // Halt F1 if it's supposed to produce output (isFireable) but fetchSystem has no valid data.
    stageF1.haltWhen(!fetchSystem.io.dataOut.valid && stageF1.isValid)
    fetchSystem.io.dataOut.ready := stageF1.isReady // F1 is ready to consume instruction


    // --- Flush Handling ---
    // Example: If flush is initiated by a signal in pipeline data from an earlier stage
    // or by an external interrupt/control signal.
    // For now, let's assume io.flush on FetchMemorySubsystem is controlled externally
    // or via the testbench service.
    // If CPU pipeline triggers flush:
    // val flushCmd = stageF1(FrontendPipelineData.FLUSH_ICACHE_CMD) // Assuming this exists
    // fetchSystem.io.flush.cmd.valid := flushCmd && stageF1.valid
    // fetchSystem.io.flush.cmd.payload.start := True // if flushCmd is a pulse
    // stageF1.haltWhen(fetchSystem.io.flush.cmd.valid && !fetchSystem.io.flush.rsp.done)
    // stageF1(FLUSH_DONE_SIGNAL_TO_CPU) := fetchSystem.io.flush.rsp.done
  }
}

// Service for testbench to access FetchMemorySubsystem (e.g., for flush, tb writes)
trait FetchMemorySubsystemAccessService extends Service {
  var fetchSystem: FetchMemorySubsystem = null // Will be populated by Fetch1Plugin
}
