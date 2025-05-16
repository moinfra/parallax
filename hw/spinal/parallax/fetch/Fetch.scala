package parallax.fetch

import spinal.core._
import spinal.lib._
import spinal.core.sim.SimDataPimper
import spinal.lib.pipeline.{Pipeline, Stage, Connection}

import parallax.components.memory._
import parallax.utilities.{Plugin, LockedImpl, Service}
import parallax.frontend.FrontendPipelineKeys
import spinal.lib.pipeline.Stageable
import parallax.common._

case class FetchPipelineSignals(val config: PipelineConfig) extends AreaObject {
  val PC = Stageable(UInt(config.pcWidth)) // pc to fetch
  val FETCHED_PC = Stageable(UInt(config.pcWidth)) // pc that accord with the fetched instruction
  val REDIRECT_PC = Stageable(UInt(config.pcWidth)) // 重定向的指令的地址，来自分支或者预测
  val REDIRECT_PC_VALID = Stageable(Bool())
  val INSTRUCTION = Stageable(Bits(config.dataWidth)) // Assuming instruction width = data width
  val FETCH_FAULT = Stageable(Bool())

}

class FetchPipeline(config: PipelineConfig) extends Plugin with LockedImpl {
  lazy val signals = FetchPipelineSignals(config)
  val pipeline = create early new Pipeline {
    val s0_PcGen = newStage().setName("s0_PcGen") // PC Generation
    val s1_Fetch = newStage().setName("s1_Fetch") // Instruction Fetch
    connect(s0_PcGen, s1_Fetch)(Connection.M2S())
  }

  val build = create late new Area {
    pipeline.build()
  }

  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.s0_PcGen
  def exitStage: Stage = pipeline.s1_Fetch
}

case class Fetch0PluginConfig() {
  val pcWidth: BitCount = 32 bits
}
// Fetch0Plugin: Generates PC
class Fetch0Plugin(val pcWidth: BitCount = 32 bits) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val signals = setup.fetchPipeline.signals
    val s0_PcGen = setup.fetchPipeline.pipeline.s0_PcGen

    val pcReg = Reg(UInt(pcWidth)) init (0) // Hardware reset to 0
    val pcPlus4 = pcReg + 4

    val redirectValid =
      s0_PcGen(signals.REDIRECT_PC_VALID) // Assuming redirect comes from a previous stage or external
    val redirectPc = s0_PcGen(signals.REDIRECT_PC) // Correctly access as input if it's pipelined to s0

    // --- Initialization Logic (Simplified) ---
    val bootDelay = 2 // 复位后等待2个周期
    val initCounter = Reg(UInt(log2Up(bootDelay + 1) bits)) init (0)
    val booted = Reg(Bool()) init (False) // 使用一个寄存器来锁存booted状态

    when(!booted) {
      initCounter := initCounter + 1
      when(initCounter === bootDelay) {
        booted := True
      }
    }

    // --- PC Generation Logic ---
    val pcLogic = new Area {
      val currentPc = pcReg
      // By default, next PC is PC+4, unless a redirect is valid.
      // Redirects should have higher priority.
      val nextPcCalc = pcPlus4
      when(redirectValid) {
        nextPcCalc := redirectPc
      }

      when(booted) {
        // PC updates if the stage is firing (meaning s0 is valid and s1 is ready)
        // OR if there's a redirect request, which should override normal flow.
        // If redirectValid is asserted, pcReg should update regardless of s0_PcGen.isFiring
        // to ensure the redirect takes effect promptly.
        when(s0_PcGen.isFiring || (s0_PcGen.isValid && redirectValid)) {
          pcReg := nextPcCalc
        }
      }

      val isValidToStage = booted
    }

    s0_PcGen.valid := pcLogic.isValidToStage
    s0_PcGen(signals.PC) := pcReg

    report(
      L"[Fetch0Plugin] s0_PcGen.valid: ${s0_PcGen.valid} booted: ${booted} redirectValid: ${redirectValid} pc: ${pcReg} nextPcCalc: ${pcLogic.nextPcCalc} s0_isFiring: ${s0_PcGen.isFiring}"
    )
  }
}

// Fetch1Plugin: Instantiates InstructionFetchUnit and performs the fetch
class Fetch1Plugin(val ifuConfig: InstructionFetchUnitConfig) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val memPlugin = getService[SimpleMemoryService]
    val fetchPipeline = getService[FetchPipeline]
    // val s0_PcGen = fetchPipeline.pipeline.s0_PcGen // Not directly used for payload access here
    val s1_Fetch = fetchPipeline.pipeline.s1_Fetch
    val ifu = new InstructionFetchUnit(ifuConfig)
    ifu.io.memBus <> memPlugin.memBus
  }

  val logic = create late new Area {
    val signals = setup.fetchPipeline.signals
    val s1_Fetch = setup.s1_Fetch
    val ifu = setup.ifu

    // This is the PC that s1_Fetch has received from s0_PcGen for the current operation
    val currentPcForS1 = s1_Fetch(signals.PC)

    // State: indicates S1 has sent a PC to IFU and is waiting for data.
    val isFetching = Reg(Bool()) init (False) setName ("isFetching")
    // Registers to store the data from IFU once it arrives.
    // This decouples IFU response timing from s1_Fetch downstream firing.
    val regFetchedPc = Reg(UInt(ifuConfig.pcWidth)) setName ("regFetchedPc")
    val regFetchedInstruction = Reg(Bits(ifuConfig.cpuDataWidth)) setName ("regFetchedInstruction")
    val regFetchedFault = Reg(Bool()) setName ("regFetchedFault")
    // Indicates that the registers above hold valid data from a completed IFU fetch.
    val regDataFromIfuIsValid = Reg(Bool()) init (False) setName ("regDataFromIfuIsValid")

    // Drive IFU's pcIn Stream
    // Only send a new request if:
    // 1. s1_Fetch has valid input from s0_PcGen (s1_Fetch.isValid).
    // 2. We are not already waiting for IFU (isFetching = False).
    // 3. We don't already have valid data in our registers waiting to be sent downstream (regDataFromIfuIsValid = False).
    ifu.io.pcIn.valid := s1_Fetch.isValid && !isFetching && !regDataFromIfuIsValid
    ifu.io.pcIn.payload := currentPcForS1 // Use the PC s1_Fetch is currently processing

    // Update isFetching state and latch IFU data
    when(s1_Fetch.isFlushed) { // If pipeline flushes s1 (e.g. branch mispredict)
      isFetching := False
      regDataFromIfuIsValid := False
    }

    when(ifu.io.pcIn.fire) { // IFU accepted a new PC request
      isFetching := True
      report(L"Fetch1Plugin: fetch started for pc=${ifu.io.pcIn.payload}")
    }

    // Drive IFU's dataOut.ready
    // We are ready to accept data from IFU if:
    // 1. We are indeed waiting for it (isFetching = True).
    // 2. Our internal holding registers are free (regDataFromIfuIsValid = False).
    ifu.io.dataOut.ready := isFetching && !regDataFromIfuIsValid

    when(ifu.io.dataOut.fire) { // IFU provided data, and we accepted it
      isFetching := False
      regFetchedPc := ifu.io.dataOut.payload.pc
      regFetchedInstruction := ifu.io.dataOut.payload.instruction
      regFetchedFault := ifu.io.dataOut.payload.fault
      regDataFromIfuIsValid := True
      report(
        L"Fetch1Plugin: fetch complete for pc=${ifu.io.dataOut.payload.pc}, data=${ifu.io.dataOut.payload.instruction}, latched into regs."
      )
    }

    // Drive s1_Fetch stage's output signals from our internal registers
    s1_Fetch(signals.FETCHED_PC) := regFetchedPc
    s1_Fetch(signals.INSTRUCTION) := regFetchedInstruction
    s1_Fetch(signals.FETCH_FAULT) := regFetchedFault

    // When s1_Fetch fires (sends data downstream), the data in our registers has been consumed.
    when(s1_Fetch.isFiring && regDataFromIfuIsValid) {
      regDataFromIfuIsValid := False
      report(L"Fetch1Plugin: S1 FIRING, consumed latched data. PC=${regFetchedPc} INSTR=${regFetchedInstruction}")
    }

    // Stall conditions for s1_Fetch:
    // 1. If s1_Fetch has valid input, but it cannot send a new request to IFU because:
    //    a. It's not already fetching (isFetching=False) AND
    //    b. It doesn't have data ready to go (regDataFromIfuIsValid=False) AND
    //    c. IFU is not ready to accept a new PC (ifu.io.pcIn.ready=False).
    val stall_waiting_to_send_to_ifu = s1_Fetch.isValid && !isFetching && !regDataFromIfuIsValid && !ifu.io.pcIn.ready

    // 2. If s1_Fetch is waiting for data from IFU (isFetching=True), but IFU hasn't made it valid yet.
    //    (regDataFromIfuIsValid is implicitly False if isFetching is True, based on state transitions)
    val stall_waiting_for_ifu_data = s1_Fetch.isValid && isFetching && !ifu.io.dataOut.valid

    // 3. (Implicitly Handled by Pipeline) If s1_Fetch has valid data in its registers (regDataFromIfuIsValid=True),
    //    but the downstream stage/bridge is not ready (s1_Fetch.isReady is False), the pipeline automatically stalls s1_Fetch.

    // Halt s1_Fetch if it's supposed to be doing work but cannot make progress.
    // The stage should also effectively stall if it has valid inputs but its output registers are full (regDataFromIfuIsValid = True)
    // and the downstream isn't ready. This is automatically handled by the pipeline if s1_Fetch.isReady (which comes from fetchOutput.ready) is low.
    // The key is that s1_Fetch should only be considered "done" with its current PC and ready to output
    // when regDataFromIfuIsValid is True. If it's True, it will only fire if downstream is ready.
    // If regDataFromIfuIsValid is False, it means s1_Fetch is still working on getting the data.
    s1_Fetch.haltWhen(stall_waiting_to_send_to_ifu || stall_waiting_for_ifu_data)

    // This provides an explicit halt condition if the stage has valid inputs,
    // but hasn't yet latched data from the IFU.
    // This ensures s1_Fetch doesn't fire downstream if regDataFromIfuIsValid is false.
    s1_Fetch.haltWhen(s1_Fetch.isValid && !regDataFromIfuIsValid)

    when(s1_Fetch.isValid && !s1_Fetch.isStuck && !s1_Fetch.isReady && regDataFromIfuIsValid) {
      report(L"Fetch1Plugin: Has valid data in regs, but downstream not ready. PC=${regFetchedPc}")
    }
    when(s1_Fetch.isValid && stall_waiting_to_send_to_ifu) {
      report(L"Fetch1Plugin: Stalling, IFU not ready for new PC. Current S1 PC=${currentPcForS1}")
    }
    when(s1_Fetch.isValid && stall_waiting_for_ifu_data) {
      report(L"Fetch1Plugin: Stalling, waiting for IFU data. Current S1 PC=${currentPcForS1}")
    }
  }
}

case class FetchOutput(val ifuConfig: InstructionFetchUnitConfig) extends Bundle {
  val pc = UInt(ifuConfig.pcWidth)
  val instruction = Bits(ifuConfig.cpuDataWidth)
  val fault = Bool()
}

class FetchOutputBridge(val ifuConfig: InstructionFetchUnitConfig) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val s1_Fetch = fetchPipeline.pipeline.s1_Fetch

    if (s1_Fetch.internals.output.ready == null) {
      s1_Fetch.internals.output.ready = Bool()
    }
    s1_Fetch.internals.output.ready := fetchOutput.ready
  }

  val fetchOutput = create early Stream(FetchOutput(ifuConfig))

  create late new Area {
    val signals = setup.fetchPipeline.signals
    val s1_Fetch = setup.s1_Fetch
    val s1_output_pc = s1_Fetch(signals.FETCHED_PC) // Read the value explicitly

    fetchOutput.valid := s1_Fetch.internals.output.valid
    fetchOutput.payload.pc := s1_Fetch(signals.FETCHED_PC)
    fetchOutput.payload.instruction := s1_Fetch(signals.INSTRUCTION)
    fetchOutput.payload.fault := s1_Fetch(signals.FETCH_FAULT)

    fetchOutput.valid.simPublic()
    fetchOutput.ready.simPublic()
    fetchOutput.payload.pc.simPublic()
    fetchOutput.payload.instruction.simPublic()
    fetchOutput.payload.fault.simPublic()

    when(fetchOutput.fire) { // fire = valid && ready
      report(
        L"[Bridge] Firing output: Valid=${fetchOutput.valid} Ready=${fetchOutput.ready} fetchOutput.payload.pc=${fetchOutput.payload.pc} s1_output_pc=${s1_output_pc} Instr=${fetchOutput.payload.instruction}"
      )
    }
  }
}
