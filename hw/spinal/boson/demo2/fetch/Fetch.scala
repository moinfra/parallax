package boson.demo2.fetch

import boson.utilities.{Plugin, LockedImpl, Service}
import boson.demo2.common.Config
import boson.demo2.frontend.FrontendPipelineKeys
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.{Pipeline, Stage, Connection}
import boson.demo2.components.memory._

class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val s0_PcGen = newStage() // PC Generation
    val s1_Fetch = newStage() // Instruction Fetch
    connect(s0_PcGen, s1_Fetch)(Connection.M2S())
  }
  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.s0_PcGen
  def exitStage: Stage = pipeline.s1_Fetch
}

// Fetch0Plugin: Generates PC
class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val s0_PcGen = setup.fetchPipeline.pipeline.s0_PcGen
    val pcReg = Reg(UInt(Config.XLEN bits)) init (0) // Start PC
    val pcPlus4 = pcReg + 4

    val redirectValid = s0_PcGen(FrontendPipelineKeys.REDIRECT_PC_VALID)
    val redirectPc = s0_PcGen(FrontendPipelineKeys.REDIRECT_PC)

    when(s0_PcGen.isFiring) { // isFiring means stage is valid and not stalled
      pcReg := Mux(redirectValid, redirectPc, pcPlus4)
    }

    s0_PcGen(FrontendPipelineKeys.PC) := pcReg
  }
}

// Fetch1Plugin: Instantiates InstructionFetchUnit and performs the fetch
class Fetch1Plugin(val ifuConfig: InstructionFetchUnitConfig) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val memPlugin = getService[SimpleMemoryService]
    val fetchPipeline = getService[FetchPipeline]
    val f0 = fetchPipeline.pipeline.s0_PcGen
    val f1 = fetchPipeline.pipeline.s1_Fetch
    val ifu = new InstructionFetchUnit(ifuConfig)
    ifu.io.memBus <> memPlugin.memBus
    // F1 depends on PC from F0
    f1(FrontendPipelineKeys.PC)
  }

  val logic = create late new Area {
    val stageF1 = setup.fetchPipeline.pipeline.s1_Fetch
    val ifu = setup.ifu
    ifu.io.pcIn.valid := stageF1.valid // Input valid signal for F1
    ifu.io.pcIn.payload := stageF1(FrontendPipelineKeys.PC)

    stageF1.haltWhen(stageF1.valid && !ifu.io.pcIn.ready)

    stageF1(FrontendPipelineKeys.INSTRUCTION) := ifu.io.dataOut.payload.instruction
    stageF1(FrontendPipelineKeys.PC) := ifu.io.dataOut.payload.pc
    stageF1(FrontendPipelineKeys.FETCH_FAULT) := ifu.io.dataOut.payload.fault

    stageF1.haltWhen(!ifu.io.dataOut.valid && stageF1.isValid)
    ifu.io.dataOut.ready := stageF1.isReady // F1 is ready to consume instruction

  }
}
