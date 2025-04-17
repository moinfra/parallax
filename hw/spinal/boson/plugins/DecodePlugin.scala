package boson.plugins

import scala.collection.mutable

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._ // Import enums like AluOp

class DecodePlugin extends Plugin[Boson] {

  // Define instruction encodings (replace with actual LA32R values)
  object Opcodes {
    // Example opcodes - FILL THESE IN FROM LOONGARCH MANUAL
    val SPECIAL = B"000000" // Placeholder
    val SPECIAL2 = B"000001" // Placeholder
    val JUMP = B"000010" // Placeholder for B, BL, JIRL opcodes
    val BRANCH = B"000011" // Placeholder for BEQ, BNE, etc.
    val LOAD = B"010000" // Placeholder for LD.*
    val STORE = B"011000" // Placeholder for ST.*
    val ALU_IMM = B"001000" // Placeholder for ADDI, SLTI, ANDI, ORI, XORI
    val ALU_IMM_SHIFT = B"001100" // Placeholder for SLLI, SRLI, SRAI
    val LU12I = B"001010" // Placeholder
    val PCADDU12I = B"001110" // Placeholder
    // ... add all relevant opcodes
  }

  object Functs {
    // Example funct codes for SPECIAL/SPECIAL2 - FILL THESE IN
    val ADD_W = B"0000000001" // Placeholder
    val SUB_W = B"0000000011" // Placeholder
    val SLT = B"0000100100" // Placeholder
    val SLTU = B"0000100101" // Placeholder
    val AND = B"0000101100" // Placeholder
    val OR = B"0000101101" // Placeholder
    val XOR = B"0000101110" // Placeholder
    val SLL_W = B"0000111001" // Placeholder
    val SRL_W = B"0000111011" // Placeholder
    val SRA_W = B"0000111101" // Placeholder
    val SYSCALL = B"0101101011" // Placeholder
    val BREAK = B"0101101101" // Placeholder
    // ... add all relevant funct codes
  }

  object BranchFuncts {
    // Example funct codes for BRANCH opcode - FILL THESE IN
    val BEQ = B"000" // Placeholder
    val BNE = B"001" // Placeholder
    val BLT = B"010" // Placeholder
    val BGE = B"011" // Placeholder
    val BLTU = B"100" // Placeholder
    val BGEU = B"101" // Placeholder
  }

  object JumpFuncts {
    // Example funct codes for JUMP opcode - FILL THESE IN
    val B = B"00" // Placeholder
    val BL = B"01" // Placeholder
    val JIRL = B"10" // Placeholder
  }

  object MemFuncts {
     // Example funct codes for LOAD/STORE opcodes - FILL THESE IN
     val LB = B"000" // Placeholder
     val LH = B"001"
     val LW = B"010"
     val LBU = B"100"
     val LHU = B"101"
     val SB = B"000"
     val SH = B"001"
     val SW = B"010"
  }


  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    decodeRename plug new Area {
      import decodeRename._

      val instruction = input(INSTRUCTION)
      val pc = input(PC) // Needed for some instructions like BL, PCADDU12I

      // --- Field Extraction (LA32R Format) ---
      val opcode = instruction(31 downto 26) // Adjust bits as per spec
      val rk = instruction(14 downto 10).asUInt
      val rj = instruction(9 downto 5).asUInt
      val rd = instruction(4 downto 0).asUInt

      // Immediates depend on format
      val imm12 = instruction(21 downto 10) // For I-type (ADDI etc.)
      val imm16 = instruction(25 downto 10) // For branches
      val imm20 = instruction(24 downto 5) // For LU12I
      val imm26 = instruction(25 downto 0) // For B/BL

      // Funct fields depend on opcode
      val funct_jumps = instruction(25 downto 24) // For B/BL/JIRL type
      val funct_branch = instruction(24 downto 22) // For Branch type
      val funct_mem = instruction(9 downto 7) // For Load/Store type width/sign
      val funct_alu_shift = instruction(25 downto 15) // For R-type ALU/Shift

      // --- Decoding Logic ---
      val N = False // Helper for No
      val Y = True  // Helper for Yes

      // Default values for control signals (important to avoid latches)
      val ctrl = mutable.LinkedHashMap[Stageable[_ <: Data], Data]()
      ctrl(IS_VALID) = input(IS_VALID) // Pass validity through
      ctrl(PC) = pc // Pass PC through
      ctrl(OPCODE) = opcode
      ctrl(FUNCT) = funct_alu_shift // Example default, adjust
      ctrl(RD) = rd
      ctrl(RJ) = rj
      ctrl(RK) = rk
      ctrl(IMM_SI12) = S(imm12).resize(12)
      ctrl(IMM_UI12) = U(imm12).resize(12)
      ctrl(IMM_SI16) = S(imm16).resize(16)
      ctrl(IMM_UI20) = U(imm20).resize(20)
      ctrl(IMM_SI26) = S(imm26).resize(26)

      ctrl(ALU_OP) = AluOp.NOP
      ctrl(IS_BRANCH) = N
      ctrl(IS_COND_BRANCH) = N
      ctrl(IS_JUMP) = N
      ctrl(IS_JUMP_LINK) = N
      ctrl(IS_LOAD) = N
      ctrl(IS_STORE) = N
      ctrl(IS_FPU) = N // Placeholder
      ctrl(IS_SYSCALL) = N
      ctrl(IS_BREAK) = N
      ctrl(WRITE_ENABLE) = N
      ctrl(LINK_ADDR) = U(0)
      ctrl(MEM_OP) = MemOp.NONE
      ctrl(MEM_SIGNED) = N

      // --- Main Decode Logic ---
      // Use a big switch statement based on opcode and funct fields
      switch(opcode) {
        is(Opcodes.SPECIAL) { // R-type ALU/Shift instructions
          ctrl(WRITE_ENABLE) = Y
          switch(funct_alu_shift) {
            is(Functs.ADD_W) { ctrl(ALU_OP) = AluOp.ADD }
            is(Functs.SUB_W) { ctrl(ALU_OP) = AluOp.SUB }
            is(Functs.SLT)   { ctrl(ALU_OP) = AluOp.SLT }
            is(Functs.SLTU)  { ctrl(ALU_OP) = AluOp.SLTU }
            is(Functs.AND)   { ctrl(ALU_OP) = AluOp.AND }
            is(Functs.OR)    { ctrl(ALU_OP) = AluOp.OR }
            is(Functs.XOR)   { ctrl(ALU_OP) = AluOp.XOR }
            is(Functs.SLL_W) { ctrl(ALU_OP) = AluOp.SLL }
            is(Functs.SRL_W) { ctrl(ALU_OP) = AluOp.SRL }
            is(Functs.SRA_W) { ctrl(ALU_OP) = AluOp.SRA }
            is(Functs.SYSCALL){ ctrl(IS_SYSCALL) = Y; ctrl(WRITE_ENABLE) = N } // Syscall doesn't write GPR
            is(Functs.BREAK)  { ctrl(IS_BREAK) = Y; ctrl(WRITE_ENABLE) = N } // Break doesn't write GPR
            // default { // Handle illegal instruction }
          }
        }
        is(Opcodes.ALU_IMM) { // I-type ALU immediate instructions
          ctrl(WRITE_ENABLE) = Y
          // Extract specific ALU op based on funct3 or similar field if needed
          // Example: Assuming opcode directly implies op for simplicity here
          // if (opcode == Opcodes.ADDI_W) ctrl(ALU_OP) = AluOp.ADD
          // else if (opcode == Opcodes.SLTI) ctrl(ALU_OP) = AluOp.SLT
          // ... etc for ANDI, ORI, XORI
          // For now, assume a generic ADD for immediate ops needing ALU
          ctrl(ALU_OP) = AluOp.ADD // Placeholder - refine based on exact opcodes
        }
         is(Opcodes.ALU_IMM_SHIFT) { // I-type Shift immediate instructions
          ctrl(WRITE_ENABLE) = Y
           // Extract specific shift op based on funct or similar
           // if (opcode == Opcodes.SLLI_W) ctrl(ALU_OP) = AluOp.SLL
           // ... etc for SRLI, SRAI
           ctrl(ALU_OP) = AluOp.SLL // Placeholder - refine
         }
        is(Opcodes.LU12I) {
          ctrl(WRITE_ENABLE) = Y
          // Special handling in Execute: result = imm << 12
          ctrl(ALU_OP) = AluOp.NOP // Or a dedicated LU12I op
        }
        is(Opcodes.PCADDU12I) {
            ctrl(WRITE_ENABLE) = Y
            // Special handling in Execute: result = pc + (imm << 12)
            ctrl(ALU_OP) = AluOp.ADD // Use ALU for PC + imm part
        }
        is(Opcodes.BRANCH) {
          ctrl(IS_BRANCH) = Y
          ctrl(IS_COND_BRANCH) = Y
          // Decode specific branch type (BEQ, BNE, etc.) using funct_branch
          // This info might be used directly in Execute or passed via ALU_OP/custom signal
        }
        is(Opcodes.JUMP) {
          ctrl(IS_BRANCH) = Y // Treat jumps as branches for PC logic
          switch(funct_jumps) {
            is(JumpFuncts.B) { /* No link */ }
            is(JumpFuncts.BL) {
              ctrl(IS_JUMP_LINK) = Y
              ctrl(WRITE_ENABLE) = Y // Writes link address to R1 (usually)
              ctrl(LINK_ADDR) = pc + 4
            }
            is(JumpFuncts.JIRL) {
              ctrl(IS_JUMP) = Y
              ctrl(IS_JUMP_LINK) = Y
              ctrl(WRITE_ENABLE) = Y // Writes link address to RD
              ctrl(LINK_ADDR) = pc + 4
            }
          }
        }
        is(Opcodes.LOAD) {
          ctrl(IS_LOAD) = Y
          ctrl(WRITE_ENABLE) = Y
          ctrl(ALU_OP) = AluOp.ADD // For address calculation (rj + imm)
          switch(funct_mem) {
             is(MemFuncts.LB)  { ctrl(MEM_OP) = MemOp.LOAD_B; ctrl(MEM_SIGNED) = Y }
             is(MemFuncts.LH)  { ctrl(MEM_OP) = MemOp.LOAD_H; ctrl(MEM_SIGNED) = Y }
             is(MemFuncts.LW)  { ctrl(MEM_OP) = MemOp.LOAD_W; ctrl(MEM_SIGNED) = Y } // Signedness doesn't matter for W
             is(MemFuncts.LBU) { ctrl(MEM_OP) = MemOp.LOAD_BU; ctrl(MEM_SIGNED) = N }
             is(MemFuncts.LHU) { ctrl(MEM_OP) = MemOp.LOAD_HU; ctrl(MEM_SIGNED) = N }
             // default { // Handle illegal load }
          }
        }
        is(Opcodes.STORE) {
          ctrl(IS_STORE) = Y
          ctrl(WRITE_ENABLE) = N // Stores don't write back to GPRs
          ctrl(ALU_OP) = AluOp.ADD // For address calculation (rj + imm)
           switch(funct_mem) {
             is(MemFuncts.SB)  { ctrl(MEM_OP) = MemOp.STORE_B }
             is(MemFuncts.SH)  { ctrl(MEM_OP) = MemOp.STORE_H }
             is(MemFuncts.SW)  { ctrl(MEM_OP) = MemOp.STORE_W }
             // default { // Handle illegal store }
          }
        }
        // Add cases for FPU, CSR, other instruction types
        // default {
        //   // Handle illegal instruction - maybe set a specific signal
        //   ctrl(IS_VALID) = N // Mark as invalid instruction
        // }
      }

      // --- Final Control Signal Adjustments ---
      // Ensure R0 is never written
      when(rd === 0) {
        ctrl(WRITE_ENABLE) = N
      }
      // Special case for JIRL/BL link register (often R1 for BL)
      // if (ctrl(IS_JUMP_LINK) && opcode == Opcodes.JUMP && funct_jumps == JumpFuncts.BL) {
      //    ctrl(RD) = U(1) // Force RD to R1 for BL
      // }

      // --- Insert decoded signals into the pipeline ---
      ctrl.foreach { case (key, value) => insert(key.asInstanceOf[Stageable[Data]]) := value }

      report(L"[Decode] Firing=${arbitration.isFiring} Instr=${instruction} Op=${opcode} RD=${rd} RJ=${rj} RK=${rk} WE=${ctrl(WRITE_ENABLE)} ALU=${ctrl(ALU_OP)}")
    }
  }
}
