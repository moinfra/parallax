package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._ // Import enums

class ExecutePlugin extends Plugin[Boson] {
  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    execute plug new Area {
      import execute._

      // Inputs from Issue/RegRead stage
      val pc = input(PC)
      val rjData = input(RJ_DATA)
      val rkData = input(RK_DATA)
      val rd = input(RD) // Pass through RD
      val writeEnable = input(WRITE_ENABLE) // Pass through WE
      val isLoad = input(IS_LOAD) // Pass through load/store signals
      val isStore = input(IS_STORE)
      val memOp = input(MEM_OP) // Pass through mem op details
      val memSigned = input(MEM_SIGNED)

      // Immediates (select based on instruction type if needed, or pass all)
      val imm_si12 = input(IMM_SI12)
      val imm_ui12 = input(IMM_UI12)
      val imm_si16 = input(IMM_SI16)
      val imm_ui20 = input(IMM_UI20)
      val imm_si26 = input(IMM_SI26)

      // Control signals
      val aluOp = input(ALU_OP)
      val isBranch = input(IS_BRANCH)
      val isCondBranch = input(IS_COND_BRANCH)
      val isJump = input(IS_JUMP)
      val isJumpLink = input(IS_JUMP_LINK)
      val linkAddr = input(LINK_ADDR) // PC+4 for BL/JIRL

      // --- ALU Operand Selection ---
      // Simplified: Assume ALU uses rjData and either rkData or an immediate
      // A more complex design might have dedicated muxes based on instruction format
      val aluInput1 = rjData
      val aluInput2 = Bits(dataWidth bits)

      // Select second ALU operand (RK or Immediate)
      // TODO: Refine selection based on decoded instruction type
      // This is a simplification; real CPUs use format info.
      when(isLoad || isStore || aluOp === AluOp.ADD /* for ADDI */ ) {
          aluInput2 := imm_si12.resize(dataWidth bits).asBits
      } elsewhen(aluOp === AluOp.SLL || aluOp === AluOp.SRL || aluOp === AluOp.SRA /* for shifts */) {
          // Shift amount could be RK or immediate
          // Assuming immediate shift amount comes from lower bits of imm12 for SLLI etc.
          aluInput2 := imm_ui12(4 downto 0).resize(dataWidth bits).asBits // Example: 5-bit shift amount
      } elsewhen(isJump && isJumpLink) { // JIRL uses RJ + Imm16 for target
          aluInput2 := imm_si16.resize(dataWidth bits).asBits
      } otherwise {
          aluInput2 := rkData // Default to RK data for R-type ALU ops
      }


      // --- ALU Calculation ---
      val aluResult = Bits(dataWidth bits)
      val sltResult = Bool() // For SLT/SLTU

      // Signed/Unsigned comparators
      val lessThanSigned = aluInput1.asSInt < aluInput2.asSInt
      val lessThanUnsigned = aluInput1.asUInt < aluInput2.asUInt

      switch(aluOp) {
        is(AluOp.ADD) { aluResult := (aluInput1.asSInt + aluInput2.asSInt).asBits }
        is(AluOp.SUB) { aluResult := (aluInput1.asSInt - aluInput2.asSInt).asBits }
        is(AluOp.SLT) { sltResult := lessThanSigned; aluResult := B(dataWidth bits, default -> lessThanSigned) }
        is(AluOp.SLTU){ sltResult := lessThanUnsigned; aluResult := B(dataWidth bits, default -> lessThanUnsigned) }
        is(AluOp.AND) { aluResult := aluInput1 & aluInput2 }
        is(AluOp.OR)  { aluResult := aluInput1 | aluInput2 }
        is(AluOp.XOR) { aluResult := aluInput1 ^ aluInput2 }
        is(AluOp.SLL) { aluResult := aluInput1 |<< aluInput2(4 downto 0).asUInt } // Use lower 5 bits for shift amount
        is(AluOp.SRL) { aluResult := aluInput1 |>> aluInput2(4 downto 0).asUInt }
        is(AluOp.SRA) { aluResult := (aluInput1.asSInt >> aluInput2(4 downto 0).asUInt).asBits }
        // Handle LU12I, PCADDU12I specifically
        // is(AluOp.LU12I) { aluResult := (imm_ui20 << 12).resize(dataWidth bits).asBits } // Assuming LU12I uses imm20
        // is(AluOp.PCADDU12I) { aluResult := (pc.asSInt + S(imm_ui20 << 12).resize(dataWidth)).asBits }
        default { aluResult := B(0) } // NOP or default
      }

      // --- Address Calculation ---
      // Loads, Stores, and JIRL use ALU result as address/target
      val effectiveAddress = aluResult.asUInt // Result of RJ + Imm usually

      // --- Branch Condition Evaluation ---
      val branchCondMet = Bool()
      // TODO: Decode specific branch type from FUNCT passed from Decode
      // Example using placeholders:
      // val branchType = input(BRANCH_TYPE_FUNCT) // Assuming this signal exists
      // switch(branchType) {
      //    is(BranchFuncts.BEQ) { branchCondMet := rjData === rkData }
      //    is(BranchFuncts.BNE) { branchCondMet := rjData =/= rkData }
      //    is(BranchFuncts.BLT) { branchCondMet := rjData.asSInt < rkData.asSInt }
      //    ... etc ...
      // }
      // Simplified placeholder: assume BEQ if conditional branch
      branchCondMet := (isCondBranch && (rjData === rkData))

      // --- Branch/Jump Target Calculation ---
      // Conditional branches and B/BL use PC + offset(imm)
      val branchOffset = imm_si16.resize(config.pcWidth) // For conditional branches
      val jumpOffset = imm_si26.resize(config.pcWidth)   // For B, BL
      val pcRelativeTarget = (pc.asSInt + Mux(isCondBranch, branchOffset, jumpOffset)).asUInt

      val finalBranchTarget = pcRelativeTarget // JIRL target is effectiveAddress

      // --- Determine if Branch is Taken ---
      val branchTaken = (isCondBranch && branchCondMet) || (isBranch && !isCondBranch && !isJump) // Taken if cond met, or unconditional B/BL

      // --- Insert results into pipeline ---
      insert(ALU_RESULT) := aluResult // Result of ALU op
      insert(EFF_ADDRESS) := effectiveAddress // Address for LD/ST, JIRL target
      insert(BRANCH_CALC_TARGET) := finalBranchTarget // PC-relative target
      insert(BRANCH_TAKEN) := branchTaken // Branch condition result
      insert(IS_JUMP) := isJump // Pass through jump signal for PC Manager

      // Pass through necessary data/control to Memory stage
      insert(RD) := rd
      insert(WRITE_ENABLE) := writeEnable
      insert(IS_LOAD) := isLoad
      insert(IS_STORE) := isStore
      insert(MEM_OP) := memOp
      insert(MEM_SIGNED) := memSigned
      insert(RK_DATA) := rkData // Store data often comes from RK
      insert(IS_JUMP_LINK) := isJumpLink // Pass through for WB stage
      insert(LINK_ADDR) := linkAddr // Pass through for WB stage

      report(L"[Execute] Firing=${arbitration.isFiring} Addr=${effectiveAddress} ALU=${aluResult} BrTaken=${branchTaken} BrTarget=${finalBranchTarget}")
    }
  }
}
