package boson.plugins

import spinal.core._
import spinal.lib._
import boson._

class HazardUnitPlugin extends Plugin[Boson] {
  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    // Access stages for hazard checks and data forwarding
    val drStage = pipeline.decodeRename
    val rrStage = pipeline.issueRegRead
    val exStage = pipeline.execute
    val memStage = pipeline.memory
    val wbStage = pipeline.writeback

    // --- Stall Detection (Load-Use Hazard) ---
    val rrNeedsRj = rrStage.input(RJ) =/= 0
    val rrNeedsRk = rrStage.input(RK) =/= 0
    val exIsLoadToRd = exStage.arbitration.isValid && exStage.input(IS_LOAD) && exStage.input(WRITE_ENABLE)

    val loadUseHazard = exIsLoadToRd && (
      (rrNeedsRj && (exStage.input(RD) === rrStage.input(RJ))) ||
        (rrNeedsRk && (exStage.input(RD) === rrStage.input(RK)))
    )

    issueRegRead plug new Area {
      when(loadUseHazard) {
        issueRegRead.arbitration.haltByOther := True
        report(L"[Hazard] Load-Use Stall Detected! Halting RegRead.")
      }
    }

    // --- Forwarding Path Signal and Data Generation ---
    issueRegRead plug new Area { // Add forwarding signals/data in RegRead stage's input domain
      import issueRegRead._

      // --- Determine Forwarding Validity ---
      // Check Execute stage
      val exValidWrite = exStage.arbitration.isValid && exStage.input(WRITE_ENABLE) && (exStage.input(RD) =/= 0)
      val exFwdRjValid = exValidWrite && (exStage.input(RD) === input(RJ)) && input(RJ) =/= 0
      val exFwdRkValid = exValidWrite && (exStage.input(RD) === input(RK)) && input(RK) =/= 0

      // Check Memory stage
      val memValidWrite = memStage.arbitration.isValid && memStage.input(WRITE_ENABLE) && (memStage.input(RD) =/= 0)
      val memFwdRjValidRaw = memValidWrite && (memStage.input(RD) === input(RJ)) && input(RJ) =/= 0
      val memFwdRkValidRaw = memValidWrite && (memStage.input(RD) === input(RK)) && input(RK) =/= 0
      // Prioritize EX over MEM forwarding
      val memFwdRjValid = memFwdRjValidRaw && !exFwdRjValid
      val memFwdRkValid = memFwdRkValidRaw && !exFwdRkValid

      // Check Writeback stage
      val wbValidWrite = wbStage.arbitration.isValid && wbStage.input(WRITE_ENABLE) && (wbStage.input(RD) =/= 0)
      val wbFwdRjValidRaw = wbValidWrite && (wbStage.input(RD) === input(RJ)) && input(RJ) =/= 0
      val wbFwdRkValidRaw = wbValidWrite && (wbStage.input(RD) === input(RK)) && input(RK) =/= 0
      // Prioritize MEM/EX over WB forwarding
      val wbFwdRjValid = wbFwdRjValidRaw && !exFwdRjValid && !memFwdRjValidRaw
      val wbFwdRkValid = wbFwdRkValidRaw && !exFwdRkValid && !memFwdRkValidRaw

      // --- Determine Forwarded Data ---
      // Data from Execute stage (always ALU result leaving EX)
      val exForwardData = exStage.output(ALU_RESULT)

      // Data from Memory stage (depends on if it was a load)
      // Use output signals as they represent data leaving the MEM stage
      val memForwardData = Mux(
        memStage.output(IS_LOAD),
        memStage.output(MEM_READ_DATA), // Data from Load
        memStage.output(ALU_RESULT)
      ) // Data is ALU result passed through

      // Data from Writeback stage (this is the final selected data *entering* WB)
      // Use input signals as they represent data entering the WB stage
      val wbForwardData = wbStage.input(WB_DATA) // WB_DATA is selected in RF plugin's WB area

      // --- Insert Forwarding Signals and Data ---
      insert(EX_FWD_RJ_VALID) := exFwdRjValid
      insert(EX_FWD_RK_VALID) := exFwdRkValid
      insert(MEM_FWD_RJ_VALID) := memFwdRjValid
      insert(MEM_FWD_RK_VALID) := memFwdRkValid
      insert(WB_FWD_RJ_VALID) := wbFwdRjValid
      insert(WB_FWD_RK_VALID) := wbFwdRkValid

      insert(EX_FWD_DATA) := exForwardData
      insert(MEM_FWD_DATA) := memForwardData
      insert(WB_FWD_DATA) := wbForwardData // Use the final selected data entering WB

      // Reporting
      when(arbitration.isFiring) {
        val reportRJ = (input(RJ) =/= 0)
        val reportRK = (input(RK) =/= 0)
        when(wbFwdRjValid) {
          report(L"[Hazard] FWD WB -> RJ(${input(RJ)}) Data=${wbForwardData}")
        } elsewhen (memFwdRjValid) {
          report(L"[Hazard] FWD MEM -> RJ(${input(RJ)}) Data=${memForwardData}")
        } elsewhen (exFwdRjValid) { report(L"[Hazard] FWD EX -> RJ(${input(RJ)}) Data=${exForwardData}") }
        when(wbFwdRkValid) {
          report(L"[Hazard] FWD WB -> RK(${input(RK)}) Data=${wbForwardData}")
        } elsewhen (memFwdRkValid) {
          report(L"[Hazard] FWD MEM -> RK(${input(RK)}) Data=${memForwardData}")
        } elsewhen (exFwdRkValid) { report(L"[Hazard] FWD EX -> RK(${input(RK)}) Data=${exForwardData}") }
      }
    }
  }
}
