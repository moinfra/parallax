package boson.plugins

import spinal.core._
import spinal.lib._
import boson._

class RegisterFilePlugin(registerCount: Int, dataWidth: Int) extends Plugin[Boson] {

  var regFile: Vec[Bits] = null

  override def setup(pipeline: Boson): Unit = {
    // Physical register file instance
    regFile = Vec.fill(registerCount)(Reg(Bits(dataWidth bits)) init (0))
  }

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    // --- Register Read Logic (in Issue/RegRead Stage) ---
    issueRegRead plug new Area {
      import issueRegRead._

      val rjAddr = input(RJ)
      val rkAddr = input(RK)

      // Get forwarding signals and data from HazardUnitPlugin
      val exFwdRjValid = input(EX_FWD_RJ_VALID)
      val exFwdRkValid = input(EX_FWD_RK_VALID)
      val memFwdRjValid = input(MEM_FWD_RJ_VALID)
      val memFwdRkValid = input(MEM_FWD_RK_VALID)
      val wbFwdRjValid = input(WB_FWD_RJ_VALID)
      val wbFwdRkValid = input(WB_FWD_RK_VALID)

      val exData = input(EX_FWD_DATA)
      val memData = input(MEM_FWD_DATA)
      val wbData = input(WB_FWD_DATA)

      // Read from physical register file
      val rjRegData = regFile(rjAddr)
      val rkRegData = regFile(rkAddr)

      // Select data for RJ (Priority: WB > MEM > EX > RegFile)
      val rjForwardData = Mux(wbFwdRjValid, wbData, Mux(memFwdRjValid, memData, Mux(exFwdRjValid, exData, rjRegData)))

      // Select data for RK (Priority: WB > MEM > EX > RegFile)
      val rkForwardData = Mux(wbFwdRkValid, wbData, Mux(memFwdRkValid, memData, Mux(exFwdRkValid, exData, rkRegData)))

      // Handle R0 always being zero, overriding forwarding
      val finalRjData = Mux(rjAddr === 0, B(0, config.dataWidth bits), rjForwardData)
      val finalRkData = Mux(rkAddr === 0, B(0, config.dataWidth bits), rkForwardData)

      // Insert the potentially forwarded data for Execute stage
      insert(RJ_DATA) := finalRjData
      insert(RK_DATA) := finalRkData

      // (Reporting logic remains the same)
      when(arbitration.isFiring) {
        when(rjAddr =/= 0) {
          when(wbFwdRjValid) { report(L"[RegRead] FWD WB -> RJ(${rjAddr}) Data=${wbData}") } elsewhen (memFwdRjValid) {
            report(L"[RegRead] FWD MEM -> RJ(${rjAddr}) Data=${memData}")
          } elsewhen (exFwdRjValid) { report(L"[RegRead] FWD EX -> RJ(${rjAddr}) Data=${exData}") } otherwise {
            report(L"[RegRead] RF Read RJ(${rjAddr}) Data=${rjRegData}")
          }
        }
        when(rkAddr =/= 0) {
          when(wbFwdRkValid) { report(L"[RegRead] FWD WB -> RK(${rkAddr}) Data=${wbData}") } elsewhen (memFwdRkValid) {
            report(L"[RegRead] FWD MEM -> RK(${rkAddr}) Data=${memData}")
          } elsewhen (exFwdRkValid) { report(L"[RegRead] FWD EX -> RK(${rkAddr}) Data=${exData}") } otherwise {
            report(L"[RegRead] RF Read RK(${rkAddr}) Data=${rkRegData}")
          }
        }
      }
    } // End of issueRegRead Area

    // --- Writeback Data Selection and Physical Write Logic (in Writeback Stage) ---
    writeback plug new Area {
      import writeback._

      // --- Data Selection ---
      // Read potential data sources coming *into* the writeback stage
      val aluResult = input(ALU_RESULT)
      val memReadData = input(MEM_READ_DATA)
      val linkAddr = input(LINK_ADDR)

      // Read control signals coming *into* the writeback stage
      val isLoad = input(IS_LOAD)
      val isJumpLink = input(IS_JUMP_LINK)
      val rdAddr = input(RD) // Destination register address
      val writeEnable = input(WRITE_ENABLE) // Write enable signal

      // Select the final data based on instruction type
      val finalWbData = Bits(config.dataWidth bits)
      when(isLoad) {
        finalWbData := memReadData // Use data from memory for loads
      } elsewhen (isJumpLink) {
        finalWbData := linkAddr.asBits // Use PC+4 for BL/JIRL
      } otherwise {
        finalWbData := aluResult // Use ALU result for ALU ops, etc.
      }

      // Insert the final selected data into the WB_DATA signal for this stage
      // This makes it available for potential forwarding (by HazardUnit) from WB->RegRead
      // and for the write operation below.
      insert(WB_DATA) := finalWbData

      report(
        L"[RegWB Select] Firing=${arbitration.isFiring} IsLoad=${isLoad} IsLink=${isJumpLink} -> WB_Data=${finalWbData} for RD=${rdAddr} (WE=${writeEnable})"
      )

      // --- Physical Register File Write ---
      // Perform the write if stage is firing, enabled, and not R0
      when(arbitration.isFiring && writeEnable && rdAddr =/= 0) {
        regFile(rdAddr) := finalWbData // Write the selected data
        report(L"[RegWB Write] Firing: R${rdAddr} <= ${finalWbData}")
      }
      when(arbitration.isFiring && writeEnable && rdAddr === 0) {
        report(L"[RegWB Write] Firing: Attempt to write R0 ignored.")
      }

      // --- Commit Point ---
      when(arbitration.isFiring && writeEnable) {
        report(L"[Commit] Instruction writing R${rdAddr} completed.")
      }
      when(arbitration.isFiring && !writeEnable) {
        report(L"[Commit] Instruction (no WB) completed.")
      }

    } // End of writeback Area
  } // End of build method
} // End of class RegisterFilePlugin
