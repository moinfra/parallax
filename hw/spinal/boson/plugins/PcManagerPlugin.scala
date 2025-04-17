package boson.plugins

import spinal.core._
import spinal.lib._

import boson.Stage
import boson.plugins._
import boson.Pipeline
import boson.Boson

class PcManagerPlugin() extends Plugin[Boson] {

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    // This plugin manages the PC register and calculates the next PC value.
    // It needs branch/jump results from later stages (Execute/Memory).

    val pcReg = Reg(UInt(pcWidth bits)).init(U(resetPc))
    val pcPlus4 = pcReg + 4

    // --- Provide PC to Fetch Stage ---
    fetch plug new Area {
      // Default PC value for fetch is the current registered PC
      fetch.insert(PC) := pcReg
      // TODO: Add branch prediction logic here
      // insert(PREDICTED_PC) := predict_next_pc
    }

    // --- Calculate Next PC ---
    // This requires results from the Execute stage (branch resolution)
    // and potentially Decode stage (for JIRL target from register).
    // We access these signals via the pipeline's stage connections.

    // Get necessary info from Execute stage (passed through pipeline)
    val exStage = pipeline.execute // Shortcut to execute stage
    val exValid = exStage.arbitration.isValid
    val exBranchTaken = exStage.input(BRANCH_TAKEN)
    val exBranchTarget = exStage.input(BRANCH_CALC_TARGET) // PC + offset target
    val exIsJump = exStage.input(IS_JUMP) // JIRL
    val exJumpTarget = exStage.input(EFF_ADDRESS) // JIRL target address (comes from RJ_DATA + offset)

    // Determine the source of the next PC value
    val nextPc = UInt(pcWidth bits)
    when(exValid && exIsJump) {
      nextPc := exJumpTarget
    } elsewhen (exValid && exBranchTaken) { // Assumes IS_BRANCH handled by BRANCH_TAKEN
      nextPc := exBranchTarget
    } otherwise {
      nextPc := pcPlus4 // Default: sequential execution
    }

    // --- Update PC Register ---
    // Stall condition provided by HazardUnitPlugin or other sources
    val fetchStalled = fetch.arbitration.isStuck // Fetch stage stalled?
    when(!fetchStalled) {
      pcReg := nextPc
    }

    // --- Control Hazard Handling ---
    // If Execute stage resolves a misprediction (branch taken, jump), flush earlier stages.
    // This signal could also come from a dedicated Branch Prediction Unit.
    val mispredicted = exValid && (exBranchTaken || exIsJump) // Simplified: assume any taken branch/jump might be mispredicted

    when(mispredicted) {
      report(L"[PCManager] Mispredict detected at Execute! Target: ${nextPc}, Flushing...")
      // Flush stages before Execute
      fetch.arbitration.flushIt := True
      decodeRename.arbitration.flushIt := True
      renameDispatch.arbitration.flushIt := True
      issueRegRead.arbitration.flushIt := True
      // Execute stage itself is not flushed, it contains the correct path instruction
    }

    // Report current and next calculated PC
    report(L"[PCManager] PC=${pcReg} NextPC=${nextPc} EX_Taken=${exBranchTaken} EX_Jump=${exIsJump} FetchStalled=${fetchStalled}")
  }
}
