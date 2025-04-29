package boson.plugins

import boson._ // Assuming Uop/DecodedInst related definitions are here or accessible
import spinal.core._
import spinal.lib._
import boson.defines._
import spinal.core.MaskedLiteral // For opcode matching

class DecodePlugin extends Plugin[Boson] {
  override def build(pipeline: Boson): Unit = {
    import pipeline.{config => pCfg}

    pipeline.decodeRename plug new Area {
      import pipeline.{decodeRename => cur} // Use 'cur' for current stage area
      import cur.{arbitration => arb}

      // --- Inputs ---
      val instruction = cur.input(pCfg.INSTRUCTION)
      val pc          = cur.input(pCfg.PC)
      // Need current privilege level for checks (assuming it comes from CSR plugin or similar)
      // val currentPlv = cur.input(pCfg.CURRENT_PLV)

      
      // --- Decoding Logic ---
      val decoder = new DecoderArea(pCfg) // Instantiate decoder logic area
      decoder.io.instruction := instruction
      decoder.io.pc          := pc

      // --- Output ---
      cur.insert(pCfg.DECODED_INST_OUTPUT) := decoder.io.decodedInst // Insert decoded instruction into pipeline
      // FIMXE: pass IS_FETCH_INST_VALID?

    } // End decodeRename Area
  } // End build
} // End DecodePlugin


// --- Decoder Logic Area ---
// Contains the combinational decoding logic
class DecoderArea(pCfg: BosonConfig) extends Area {

    // --- IO Bundle ---
    val io = new Bundle {
        // Inputs
        val instruction = in(Bits(32 bits))
        val pc          = in(UInt(32 bits))
        // val currentPlv  = in(UInt(2 bits)) // If privilege checks happen here

        // Output
        val decodedInst = out(DecodedInst())
    }

    // --- Instruction Field Parsing ---
    // Extract fields based on LA32R formats. Naming convention: i_fieldName
    val i_opcode_main = io.instruction(31 downto 26) // Primary opcode bits
    val i_opcode_ext  = io.instruction(25 downto 22) // Secondary opcode/func bits
    val i_opcode_func = io.instruction(21 downto 15) // Function bits for some formats

    // Register fields (common locations)
    val i_rd_raw = io.instruction(4 downto 0).asUInt
    val i_rj_raw = io.instruction(9 downto 5).asUInt
    val i_rk_raw = io.instruction(14 downto 10).asUInt
    val i_ra_raw = io.instruction(19 downto 15).asUInt
    // Floating Point register fields map to the same bits
    val i_fd_raw = i_rd_raw
    val i_fj_raw = i_rj_raw
    val i_fk_raw = i_rk_raw
    val i_fa_raw = i_ra_raw
    // Condition flag fields (assuming use rj/rd fields based on encoding)
    val i_cd_raw = i_rd_raw(2 downto 0) // BCEQZ/BCNEZ use cj in rj field
    val i_cj_raw = i_rj_raw(2 downto 0) // FCMP uses cd in rd field, FSEL uses ca in rd field


    // Immediate fields extraction (raw bits)
    val i_imm12_raw = io.instruction(21 downto 10)
    val i_imm5_raw  = io.instruction(14 downto 10) // For shifts ui5
    val i_imm14_raw = io.instruction(23 downto 10) // For LL/SC si14
    val i_imm15_raw = io.instruction(14 downto 0)  // For SYSCALL/BREAK code
    val i_imm16_raw = io.instruction(15 downto 0)  // For branches offs16
    val i_imm20_raw = io.instruction(24 downto 5)  // For LU12I/PCADDU12I si20
    val i_imm21_raw = io.instruction(25 downto 0)  // Mix of fields for BCEQZ/BCNEZ offs21
    val i_imm26_raw = io.instruction(25 downto 0)  // For B/BL offs26

    // CSR address (14 bits)
    val i_csr_raw   = io.instruction(23 downto 10)

    // --- Immediate Processing Helpers ---
    def signExtend(value: Bits, fromWidth: Int): Bits = {
        val sign = value(fromWidth-1)
        val extension = Bits(32 - fromWidth bits).setAllTo(sign)
        (extension ## value).asBits
    }
    def zeroExtend(value: Bits, fromWidth: Int): Bits = {
        val extension = B(0, 32 - fromWidth bits)
        (extension ## value).asBits
    }

    // Processed Immediates
    val si12 = signExtend(i_imm12_raw, 12)
    val ui12 = zeroExtend(i_imm12_raw, 12)
    val ui5  = zeroExtend(i_imm5_raw, 5)
    val si14 = signExtend(i_imm14_raw, 14)
    val si16 = signExtend(i_imm16_raw, 16)
    val si20 = signExtend(i_imm20_raw, 20)
    // Special immediate constructions
    val imm_lu12i    = (si20(19 downto 0) ## B(0, 12 bits)).asBits // {si20, 12'b0}
    val imm_pcadd12i = signExtend(imm_lu12i, 32) // Sign extend the {si20, 12'b0} value
    val imm_offs16   = signExtend(i_imm16_raw ## B"00", 18) // {offs16, 2'b0} sign extended
    val imm_offs21   = signExtend(i_imm21_raw(4 downto 0) ## i_imm21_raw(20 downto 5) ## B"00", 23) // {offs[20:16], offs[15:0], 2'b0} signed
    val imm_offs26   = signExtend(i_imm26_raw(9 downto 0) ## i_imm26_raw(25 downto 10) ## B"00", 28) // {offs[25:16], offs[15:0], 2'b0} signed


    // --- The Decoded Instruction Bundle ---
    val d = DecodedInst() // Output bundle instance
    d.assignDefaults() // Assign safe defaults first
    d.pc := io.pc      // Pass PC through

    // --- Helper Functions for Populating DecodedInst ---
    def setDestGpr(idx: UInt): Unit = {
        d.archDest.idx   := idx
        d.archDest.isFpr := False
        d.archDest.isCfr := False
        d.writeDestEn    := (idx =/= 0) // Disable write if rd is r0
    }
    def setDestFpr(idx: UInt): Unit = {
        d.archDest.idx   := idx
        d.archDest.isFpr := True
        d.archDest.isCfr := False
        d.writeDestEn    := True // Assuming f0 is not special zero reg
    }
     def setDestCfr(idx: UInt): Unit = {
        d.archDest.idx   := idx(2 downto 0) // CFR index is smaller
        d.archDest.isFpr := False
        d.archDest.isCfr := True
        d.writeDestEn    := True // Assuming no zero CFR
    }
    // Source setters...
    def setSrc1Gpr(idx: UInt): Unit = { d.archSrc1.idx := idx; d.archSrc1.isFpr := False; d.archSrc1.isCfr := False; d.useSrc1 := True }
    def setSrc1Fpr(idx: UInt): Unit = { d.archSrc1.idx := idx; d.archSrc1.isFpr := True;  d.archSrc1.isCfr := False; d.useSrc1 := True }
    def setSrc1Cfr(idx: UInt): Unit = { d.archSrc1.idx := idx(2 downto 0); d.archSrc1.isFpr := False; d.archSrc1.isCfr := True; d.useSrc1 := True }
    // ... similar for Src2 and Src3 ...
    def setSrc2Gpr(idx: UInt): Unit = { d.archSrc2.idx := idx; d.archSrc2.isFpr := False; d.archSrc2.isCfr := False; d.useSrc2 := True }
    def setSrc2Fpr(idx: UInt): Unit = { d.archSrc2.idx := idx; d.archSrc2.isFpr := True;  d.archSrc2.isCfr := False; d.useSrc2 := True }
    def setSrc2Cfr(idx: UInt): Unit = { d.archSrc2.idx := idx(2 downto 0); d.archSrc2.isFpr := False; d.archSrc2.isCfr := True; d.useSrc2 := True }
    def setSrc3Gpr(idx: UInt): Unit = { d.archSrc3.idx := idx; d.archSrc3.isFpr := False; d.archSrc3.isCfr := False; d.useSrc3 := True }
    def setSrc3Fpr(idx: UInt): Unit = { d.archSrc3.idx := idx; d.archSrc3.isFpr := True;  d.archSrc3.isCfr := False; d.useSrc3 := True }
    def setSrc3Cfr(idx: UInt): Unit = { d.archSrc3.idx := idx(2 downto 0); d.archSrc3.isFpr := False; d.archSrc3.isCfr := True; d.useSrc3 := True }


    // --- Main Decoding Logic (Combinational) ---
    // Use MaskedLiteral for matching opcodes and function bits
    // Format: M"opcode_main_opcode_ext_func_rk_rj_rd" (example structure)
    // Adjust mask based on actual LoongArch encoding table provided.

    // Set isValid=True only if a valid instruction is matched
    d.isValid := True // Assume valid unless default case is hit

    switch(io.instruction) {
      // Use M"..." literals based on the provided encoding table
      // Integer Arithmetic (Reg-Reg) - Opcode 0000000, Func varies
      is(M"0000000_0000_100000?????_?????_?????") { // ADD.W
        d.uopCode := UopCode.ALU_ADD; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.aluFlags.isSub := False; d.aluFlags.isUns := False
      }
      is(M"0000000_0000_100010?????_?????_?????") { // SUB.W
        d.uopCode := UopCode.ALU_ADD; d.executeUnit := ExeUnit.ALU // Use ALU_ADD with isSub flag
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.aluFlags.isSub := True; d.aluFlags.isUns := False
      }
       is(M"0000000_0000_100100?????_?????_?????") { // SLT
        d.uopCode := UopCode.ALU_SLT; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.aluFlags.isSub := False; d.aluFlags.isUns := False
      }
       is(M"0000000_0000_100101?????_?????_?????") { // SLTU
        d.uopCode := UopCode.ALU_SLT; d.executeUnit := ExeUnit.ALU // Use ALU_SLT with isUns flag
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.aluFlags.isSub := False; d.aluFlags.isUns := True
      }
      // Integer Logic (Reg-Reg)
       is(M"0000000_0000_101001?????_?????_?????") { // AND
        d.uopCode := UopCode.ALU_AND; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0000_101010?????_?????_?????") { // OR
        d.uopCode := UopCode.ALU_OR; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0000_101000?????_?????_?????") { // NOR
        d.uopCode := UopCode.ALU_NOR; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0000_101011?????_?????_?????") { // XOR
        d.uopCode := UopCode.ALU_XOR; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }

      // Integer Shifts (Reg-Reg) - Use only lower 5 bits of rk
       is(M"0000000_0000_101110?????_?????_?????") { // SLL.W
        d.uopCode := UopCode.SHIFT_L; d.executeUnit := ExeUnit.ALU // Or dedicated SHIFT unit
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw) // Src2 provides shift amount (lower 5 bits)
        d.shiftFlags.isRight := False; d.shiftFlags.isArith := False
      }
      is(M"0000000_0000_101111?????_?????_?????") { // SRL.W
        d.uopCode := UopCode.SHIFT_R; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.shiftFlags.isRight := True; d.shiftFlags.isArith := False
      }
      is(M"0000000_0000_110000?????_?????_?????") { // SRA.W
        d.uopCode := UopCode.SHIFT_RA; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
        d.shiftFlags.isRight := True; d.shiftFlags.isArith := True
      }

      // Integer Multiply/Divide (Reg-Reg)
       is(M"0000000_0000_111000?????_?????_?????") { // MUL.W
          d.uopCode := UopCode.MUL_W; d.executeUnit := ExeUnit.MUL
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0000_111001?????_?????_?????") { // MULH.W
          d.uopCode := UopCode.MULH_W; d.executeUnit := ExeUnit.MUL
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0000_111010?????_?????_?????") { // MULH.WU
          d.uopCode := UopCode.MULH_WU; d.executeUnit := ExeUnit.MUL
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
      }
       is(M"0000000_0001_000000?????_?????_?????") { // DIV.W
          d.uopCode := UopCode.DIV_W; d.executeUnit := ExeUnit.DIV
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
          d.mayCauseExc := True // Division by zero
      }
       is(M"0000000_0001_000001?????_?????_?????") { // MOD.W
          d.uopCode := UopCode.MOD_W; d.executeUnit := ExeUnit.DIV
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
          d.mayCauseExc := True // Division by zero
      }
      is(M"0000000_0001_000010?????_?????_?????") { // DIV.WU
          d.uopCode := UopCode.DIV_WU; d.executeUnit := ExeUnit.DIV
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
          d.mayCauseExc := True // Division by zero
      }
      is(M"0000000_0001_000011?????_?????_?????") { // MOD.WU
          d.uopCode := UopCode.MOD_WU; d.executeUnit := ExeUnit.DIV
          setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rk_raw)
          d.mayCauseExc := True // Division by zero
      }

      // System Calls / Breakpoints
      is(M"0000000_0001_010100?????_?????_?????") { // BREAK
          d.uopCode := UopCode.BREAK; d.executeUnit := ExeUnit.CSR // Or specific System unit
          d.sysCode := i_imm15_raw.asBits
          d.generatesExc := True
          d.excCode := ExceptionCode.BRK
      }
      is(M"0000000_0001_010110?????_?????_?????") { // SYSCALL
          d.uopCode := UopCode.SYSCALL; d.executeUnit := ExeUnit.CSR
          d.sysCode := i_imm15_raw.asBits // Encoding says 'code', assume it fits 15 bits
          d.generatesExc := True
          d.excCode := ExceptionCode.SYS
      }

      // Integer Arithmetic (Reg-Imm) - Opcode 0000001
      is(M"0000001_010????????????_?????_?????") { // ADDI.W
        d.uopCode := UopCode.ALU_ADD; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := si12; d.useImmAsSrc2 := True
        d.aluFlags.isSub := False; d.aluFlags.isUns := False
      }
       is(M"0000001_000????????????_?????_?????") { // SLTI
        d.uopCode := UopCode.ALU_SLT; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := si12; d.useImmAsSrc2 := True
        d.aluFlags.isSub := False; d.aluFlags.isUns := False
      }
       is(M"0000001_001????????????_?????_?????") { // SLTUI
        d.uopCode := UopCode.ALU_SLT; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := si12; d.useImmAsSrc2 := True // ISA says si12, but comparison is unsigned? Check ISA doc. Assuming unsigned compare against sign-extended imm.
        d.aluFlags.isSub := False; d.aluFlags.isUns := True
      }
      // Integer Logic (Reg-Imm)
       is(M"0000001_101????????????_?????_?????") { // ANDI
        d.uopCode := UopCode.ALU_AND; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := ui12; d.useImmAsSrc2 := True
      }
       is(M"0000001_110????????????_?????_?????") { // ORI
        d.uopCode := UopCode.ALU_OR; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := ui12; d.useImmAsSrc2 := True
      }
       is(M"0000001_111????????????_?????_?????") { // XORI
        d.uopCode := UopCode.ALU_XOR; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw);
        d.imm := ui12; d.useImmAsSrc2 := True
      }

       // Integer Shifts (Reg-Imm) - Opcode 0000000, Func encodes immediate
      is(M"0000000_0010_000001?????_?????_?????") { // SLLI.W
        d.uopCode := UopCode.SHIFT_L; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := ui5.resize(32); d.useImmAsSrc2 := True // Use Imm field for shift amount
        d.shiftFlags.isRight := False; d.shiftFlags.isArith := False
      }
       is(M"0000000_0010_001001?????_?????_?????") { // SRLI.W
        d.uopCode := UopCode.SHIFT_R; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := ui5.resize(32); d.useImmAsSrc2 := True
        d.shiftFlags.isRight := True; d.shiftFlags.isArith := False
      }
       is(M"0000000_0010_010001?????_?????_?????") { // SRAI.W
        d.uopCode := UopCode.SHIFT_RA; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := ui5.resize(32); d.useImmAsSrc2 := True
        d.shiftFlags.isRight := True; d.shiftFlags.isArith := True
      }

      // Load Upper Immediate - Opcode 0001010 / 0001110
      is(M"0001010_??????????????????????_?????") { // LU12I.W
        d.uopCode := UopCode.ALU_LU12I; d.executeUnit := ExeUnit.ALU
        setDestGpr(i_rd_raw)
        d.imm := imm_lu12i // Special format {si20, 12'b0}
        // Operation is just GR[rd] = imm
      }
      is(M"0001110_??????????????????????_?????") { // PCADDU12I
        d.uopCode := UopCode.ALU_PCADDU12I; d.executeUnit := ExeUnit.ALU // Needs PC as input
        setDestGpr(i_rd_raw)
        // Src1 is implicitly PC
        d.imm := imm_pcadd12i // Special format sext({si20, 12'b0})
        d.useImmAsSrc2 := True // Treat imm as second operand to add to PC
      }

      // Branches (Reg-Reg compare) - Opcode 010110->011011
      is(M"010110????????????????_?????_?????") { // BEQ
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw) // Note src order: BEQ rj, rd, offs
        d.imm := imm_offs16; d.branchCond := BranchCond.EQ
      }
      is(M"010111????????????????_?????_?????") { // BNE
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := imm_offs16; d.branchCond := BranchCond.NE
      }
       is(M"011000????????????????_?????_?????") { // BLT
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := imm_offs16; d.branchCond := BranchCond.LT
      }
       is(M"011001????????????????_?????_?????") { // BGE
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := imm_offs16; d.branchCond := BranchCond.GE
      }
       is(M"011010????????????????_?????_?????") { // BLTU
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := imm_offs16; d.branchCond := BranchCond.LTU
      }
       is(M"011011????????????????_?????_?????") { // BGEU
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := imm_offs16; d.branchCond := BranchCond.GEU
      }

      // Unconditional Jump/Branch - Opcode 010100 / 010101
      is(M"010100??????????????????????????") { // B
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        d.imm := imm_offs26; d.branchCond := BranchCond.NUL // Unconditional
      }
      is(M"010101??????????????????????????") { // BL
        d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
        setDestGpr(U(1)) // Implicit destination r1 (Link Register)
        d.imm := imm_offs26; d.branchCond := BranchCond.NUL // Unconditional
        // Result written to r1 is PC+4, handled by BRU/commit
      }

      // Jump Indirect Register Link - Opcode 010011
      is(M"010011????????????????_?????_?????") { // JIRL rd, rj, offs16
        d.uopCode := UopCode.JUMP_REG; d.executeUnit := ExeUnit.BRU
        setDestGpr(i_rd_raw) // Link address PC+4 goes to rd
        setSrc1Gpr(i_rj_raw) // Base jump address comes from rj
        d.imm := imm_offs16; d.useImmAsSrc2 := True // Offset is added to rj
      }

      // Load Instructions - Opcode 0010100 / 0010101
      is(M"0010100_000????????????_?????_?????") { // LD.B
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw) // Address base rj
        d.imm := si12; d.useImmAsSrc2 := True // Offset si12
        d.memSize := MemSize.B; d.memFlags.isSigned := True; d.memFlags.isUns := False
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010100_001????????????_?????_?????") { // LD.H
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := si12; d.useImmAsSrc2 := True
        d.memSize := MemSize.H; d.memFlags.isSigned := True; d.memFlags.isUns := False
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010100_010????????????_?????_?????") { // LD.W
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := si12; d.useImmAsSrc2 := True
        d.memSize := MemSize.W; d.memFlags.isSigned := False; d.memFlags.isUns := False // Word load is not signed/unsigned
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010101_000????????????_?????_?????") { // LD.BU
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := si12; d.useImmAsSrc2 := True
        d.memSize := MemSize.B; d.memFlags.isSigned := False; d.memFlags.isUns := True
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010101_001????????????_?????_?????") { // LD.HU
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := si12; d.useImmAsSrc2 := True
        d.memSize := MemSize.H; d.memFlags.isSigned := False; d.memFlags.isUns := True
        d.mayCauseExc := True // Alignment, Page Fault
      }

      // Store Instructions - Opcode 0010100
      is(M"0010100_100????????????_?????_?????") { // ST.B
        d.uopCode := UopCode.STORE; d.executeUnit := ExeUnit.LSU
        setSrc1Gpr(i_rj_raw) // Address base rj
        setSrc2Gpr(i_rd_raw) // Data source rd
        d.imm := si12; d.useImmAsSrc2 := False // Imm is offset, not operand source
        // Need separate field for store data or use src2 convention
        // Let's stick to src1=base, src2=data, imm=offset
        d.memSize := MemSize.B
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010100_101????????????_?????_?????") { // ST.H
        d.uopCode := UopCode.STORE; d.executeUnit := ExeUnit.LSU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := si12
        d.memSize := MemSize.H
        d.mayCauseExc := True
      }
       is(M"0010100_110????????????_?????_?????") { // ST.W
        d.uopCode := UopCode.STORE; d.executeUnit := ExeUnit.LSU
        setSrc1Gpr(i_rj_raw); setSrc2Gpr(i_rd_raw)
        d.imm := si12
        d.memSize := MemSize.W
        d.mayCauseExc := True
      }

      // Atomics - Opcode 0010000
      is(M"0010000_0??????????????_?????_?????") { // LL.W (si14 offset needs checking)
        d.uopCode := UopCode.ATOMIC_LL; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw); setSrc1Gpr(i_rj_raw) // Addr base rj
        d.imm := signExtend(i_imm14_raw ## B"00", 16); d.useImmAsSrc2 := True // Offset {si14, 2'b0}
        d.memSize := MemSize.W; d.memFlags.isLL := True
        d.llbitSet := True // Signal core to set LLbit on completion
        d.mayCauseExc := True
      }
      is(M"0010000_1??????????????_?????_?????") { // SC.W
        d.uopCode := UopCode.ATOMIC_SC; d.executeUnit := ExeUnit.LSU
        setDestGpr(i_rd_raw) // Destination rd receives success/fail (1/0)
        setSrc1Gpr(i_rj_raw) // Addr base rj
        setSrc2Gpr(i_rd_raw) // Data to store comes from rd (overwritten on success/fail) -> needs careful renaming/handling
        d.imm := signExtend(i_imm14_raw ## B"00", 16); d.useImmAsSrc2 := False
        d.memSize := MemSize.W; d.memFlags.isSC := True
        d.llbitCheck := True // Signal core to check LLbit
        d.mayCauseExc := True
      }

      // Memory Barriers / Prefetch - Opcode 0011100 / 0010101
       is(M"0011100_001_1100100?????????????????") { // DBAR
           d.uopCode := UopCode.MEM_BARRIER; d.executeUnit := ExeUnit.LSU
           d.hintOrCacheOp := io.instruction(14 downto 0).asBits // hint
       }
       is(M"0011100_001_1100101?????????????????") { // IBAR
           d.uopCode := UopCode.MEM_BARRIER; d.executeUnit := ExeUnit.LSU // Instruction barrier needs special handling in fetch/frontend
           d.hintOrCacheOp := io.instruction(14 downto 0).asBits // hint
       }
       is(M"0010101_011????????????_?????_?????") { // PRELD
           d.uopCode := UopCode.PREFETCH; d.executeUnit := ExeUnit.LSU
           setSrc1Gpr(i_rj_raw) // Base address
           d.imm := si12; d.useImmAsSrc2 := True // Offset
           d.hintOrCacheOp := io.instruction(4 downto 0).asBits // hint
       }

      // CSR Instructions - Opcode 0000010
      is(M"0000010_0???????????00000_?????") { // CSRRD rd, csr
        d.uopCode := UopCode.CSR_READ; d.executeUnit := ExeUnit.CSR
        setDestGpr(i_rd_raw)
        d.csrAddr := i_csr_raw.asBits
        d.csrFlags.isWrite := False; d.csrFlags.isXchg := False
        d.mayCauseExc := True // Privilege
      }
      is(M"0000010_0???????????00001_?????") { // CSRWR rd, csr (rd seems unused based on pseudocode? Verify)
        d.uopCode := UopCode.CSR_WRITE; d.executeUnit := ExeUnit.CSR
        setSrc1Gpr(i_rd_raw) // Source data from rd? Check ISA manual. Assuming rd is source.
        d.csrAddr := i_csr_raw.asBits
        d.csrFlags.isWrite := True; d.csrFlags.isXchg := False
        d.mayCauseExc := True // Privilege
      }
      is(M"0000010_0???????????_?????_?????") { // CSRXCHG rd, rj, csr (matches if rj != 00000 or 00001)
        // Need to exclude CSRRD/CSRWR patterns if they overlap
        when(i_rj_raw =/= 0 && i_rj_raw =/= 1) {
             d.uopCode := UopCode.CSR_XCHG; d.executeUnit := ExeUnit.CSR
             setDestGpr(i_rd_raw) // Old CSR value to rd
             setSrc1Gpr(i_rj_raw) // New CSR value from rj
             d.csrAddr := i_csr_raw.asBits
             d.csrFlags.isWrite := True; d.csrFlags.isXchg := True
             d.mayCauseExc := True // Privilege
         } otherwise {
           // This case should ideally not be hit if CSRRD/WR matched first
           d.isValid := False
           d.generatesExc := True
           d.excCode := ExceptionCode.INE // Treat overlap as illegal
         }
      }

      // --- Floating Point Instructions ---
      // FP ALU (Reg-Reg) - Opcode 0000000 / 0000110 etc.
      is(M"0000000_1000_000001?????_?????_?????") { // FADD.S
          d.uopCode := UopCode.FPU_ADD; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.S; d.aluFlags.isSub := False; d.mayCauseExc := True
      }
       is(M"0000000_1000_000010?????_?????_?????") { // FADD.D
          d.uopCode := UopCode.FPU_ADD; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.D; d.aluFlags.isSub := False; d.mayCauseExc := True
      }
      // ... (Similarly for FSUB, FMUL, FDIV, FMAX, FMIN etc.) ...
       is(M"0000000_1000_000101?????_?????_?????") { // FSUB.S
          d.uopCode := UopCode.FPU_ADD; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.S; d.aluFlags.isSub := True; d.mayCauseExc := True
      }
       is(M"0000000_1000_000110?????_?????_?????") { // FSUB.D
          d.uopCode := UopCode.FPU_ADD; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.D; d.aluFlags.isSub := True; d.mayCauseExc := True
      }
      // ... FMUL ...
       is(M"0000000_1000_001001?????_?????_?????") { // FMUL.S
          d.uopCode := UopCode.FPU_MUL; d.executeUnit := ExeUnit.FPU_MUL
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.S; d.mayCauseExc := True
      }
       is(M"0000000_1000_001010?????_?????_?????") { // FMUL.D
          d.uopCode := UopCode.FPU_MUL; d.executeUnit := ExeUnit.FPU_MUL
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.D; d.mayCauseExc := True
      }
       // ... FDIV ...
       is(M"0000000_1000_001101?????_?????_?????") { // FDIV.S
          d.uopCode := UopCode.FPU_DIV; d.executeUnit := ExeUnit.FPU_DIV
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.S; d.mayCauseExc := True
      }
       is(M"0000000_1000_001110?????_?????_?????") { // FDIV.D
          d.uopCode := UopCode.FPU_DIV; d.executeUnit := ExeUnit.FPU_DIV
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.D; d.mayCauseExc := True
      }

      // FP FMA (4R) - Opcode 0000100
      is(M"0000100_00001?????_?????_?????_?????") { // FMADD.S
          d.uopCode := UopCode.FPU_FMA; d.executeUnit := ExeUnit.FPU_MUL
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw); setSrc3Fpr(i_fa_raw)
          d.fpSize := FpSize.S; d.fmaFlags.negateA := False; d.fmaFlags.negateC := False; d.mayCauseExc := True
      }
       is(M"0000100_00010?????_?????_?????_?????") { // FMADD.D
          d.uopCode := UopCode.FPU_FMA; d.executeUnit := ExeUnit.FPU_MUL
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw); setSrc3Fpr(i_fa_raw)
          d.fpSize := FpSize.D; d.fmaFlags.negateA := False; d.fmaFlags.negateC := False; d.mayCauseExc := True
      }
      // ... (Similarly for FMSUB, FNMADD, FNMSUB using fmaFlags) ...
       is(M"0000100_00101?????_?????_?????_?????") { // FMSUB.S
          d.uopCode := UopCode.FPU_FMA; d.executeUnit := ExeUnit.FPU_MUL
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw); setSrc3Fpr(i_fa_raw)
          d.fpSize := FpSize.S; d.fmaFlags.negateA := False; d.fmaFlags.negateC := True; d.mayCauseExc := True
      }
      // ... etc ...

      // FP Compare - Opcode 0000110
      is(M"0000110_00001?????_?????_?????_?????") { // FCMP.cond.S
          d.uopCode := UopCode.FPU_CMP; d.executeUnit := ExeUnit.FPU_ADD // Often shares adder path
          setDestCfr(i_cd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.S
          d.fcmpCond := io.instruction(20 downto 16).asBits // Extract 5-bit condition code
          d.mayCauseExc := True // May raise Invalid Op if NaN and signaling compare
      }
       is(M"0000110_00010?????_?????_?????_?????") { // FCMP.cond.D
          d.uopCode := UopCode.FPU_CMP; d.executeUnit := ExeUnit.FPU_ADD
          setDestCfr(i_cd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw)
          d.fpSize := FpSize.D
          d.fcmpCond := io.instruction(20 downto 16).asBits
          d.mayCauseExc := True
      }

      // FP Select - Opcode 0000110
      is(M"0000110_1000000???_?????_?????_?????") { // FSEL fd, fj, fk, ca (ca is in rd field bits 2:0)
          d.uopCode := UopCode.FPU_SEL; d.executeUnit := ExeUnit.FPU_ADD // Simple select often on adder path
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw); setSrc2Fpr(i_fk_raw); setSrc3Cfr(i_rd_raw) // Src3 is condition flag 'ca'
          d.branchCond := BranchCond.SEL_TRUE // Use condition mechanism
      }

      // FP Load/Store - Opcode 0010101
      is(M"0010101_100????????????_?????_?????") { // FLD.S
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestFpr(i_fd_raw); setSrc1Gpr(i_rj_raw) // Addr base rj
        d.imm := si12; d.useImmAsSrc2 := True // Offset si12
        d.memSize := MemSize.W; d.fpSize := FpSize.S // Load Word size for Single FP
        d.mayCauseExc := True // Alignment, Page Fault
      }
       is(M"0010101_110????????????_?????_?????") { // FLD.D
        d.uopCode := UopCode.LOAD; d.executeUnit := ExeUnit.LSU
        setDestFpr(i_fd_raw); setSrc1Gpr(i_rj_raw)
        d.imm := si12; d.useImmAsSrc2 := True
        d.memSize := MemSize.D; d.fpSize := FpSize.D // Load Double Word size for Double FP
        d.mayCauseExc := True
      }
       is(M"0010101_101????????????_?????_?????") { // FST.S
        d.uopCode := UopCode.STORE; d.executeUnit := ExeUnit.LSU
        setSrc1Gpr(i_rj_raw) // Addr base rj
        setSrc2Fpr(i_fd_raw) // Data source fd
        d.imm := si12
        d.memSize := MemSize.W; d.fpSize := FpSize.S
        d.mayCauseExc := True
      }
       is(M"0010101_111????????????_?????_?????") { // FST.D
        d.uopCode := UopCode.STORE; d.executeUnit := ExeUnit.LSU
        setSrc1Gpr(i_rj_raw); setSrc2Fpr(i_fd_raw)
        d.imm := si12
        d.memSize := MemSize.D; d.fpSize := FpSize.D
        d.mayCauseExc := True
      }

      // FP Moves GPR <-> FPR (Example)
      is(M"0000000_1000_10100101001_?????_?????") { // MOVGR2FR.W fd, rj
          d.uopCode := UopCode.MOV_GPR2FPR; d.executeUnit := ExeUnit.ALU // Or FPU_MISC
          setDestFpr(i_fd_raw); setSrc1Gpr(i_rj_raw)
          d.fpSize := FpSize.S // Affects lower 32 bits of FPR
      }
      is(M"0000000_1000_10100101101_?????_?????") { // MOVFR2GR.S rd, fj
          d.uopCode := UopCode.MOV_FPR2GPR; d.executeUnit := ExeUnit.ALU
          setDestGpr(i_rd_raw); setSrc1Fpr(i_fj_raw)
          d.fpSize := FpSize.S // Reads lower 32 bits of FPR
      }
      // ... (Add other MOV variants: GR2FRH, FRH2GR, GR2FCSR, FCSR2GR, FR2CF, CF2FR, GR2CF, CF2GR)


      // FP Conversions (Example)
      is(M"0000000_1000_11001000110_?????_?????") { // FCVT.S.D fd, fj
          d.uopCode := UopCode.FPU_CVT_F2F; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw)
          d.fpSize := FpSize.S // Destination size
          // Need flag to indicate source size is Double
          d.mayCauseExc := True
      }
       is(M"0000000_1000_11101000100_?????_?????") { // FFINT.S.W fd, fj (fj interpreted as GPR content via FPR)
          d.uopCode := UopCode.FPU_CVT_I2F; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw) // Source is integer bits in FPR
          d.fpSize := FpSize.S
          // Need flag for source Int size (W)
          d.mayCauseExc := True
      }
       is(M"0000000_1000_11011000001_?????_?????") { // FTINT.W.S fd, fj (Result is Int bits in FPR)
          d.uopCode := UopCode.FPU_CVT_F2I; d.executeUnit := ExeUnit.FPU_ADD
          setDestFpr(i_fd_raw); setSrc1Fpr(i_fj_raw)
          d.fpSize := FpSize.S
          d.fpMiscFlags.targetInt := True
          // Need flag for target Int size (W), uses FCSR rounding mode
          d.mayCauseExc := True
      }
      // ... (Add other conversion variants FTINT{RM/RP/RZ/RNE})

      // Branch on Condition Flag - Opcode 010010 / 010011 (Note: Encoding seems mixed up in table)
      // Assuming BCEQZ cj, offs (cj in rj field bits 2:0)
      is(M"010010???????????????00???_??????????") { // BCEQZ (cj in bits 9:7?)
          d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
          setSrc1Cfr(i_rj_raw) // cj specified in rj field bits
          d.imm := imm_offs21; d.branchCond := BranchCond.CEQZ
      }
       is(M"010010???????????????01???_??????????") { // BCNEZ (cj in bits 9:7?)
          d.uopCode := UopCode.BRANCH; d.executeUnit := ExeUnit.BRU
          setSrc1Cfr(i_rj_raw) // cj specified in rj field bits
          d.imm := imm_offs21; d.branchCond := BranchCond.CNEZ
      }

      // Add RDCNTV* instructions if needed
      is(M"0000000_0000_00000011000_00000_?????") { // RDCNTVL.W rd
          d.uopCode := UopCode.RD_CNT_VL; d.executeUnit := ExeUnit.CSR // Or dedicated counter logic
          setDestGpr(i_rd_raw)
      }
      is(M"0000000_0000_00000011001_00000_?????") { // RDCNTVH.W rd
          d.uopCode := UopCode.RD_CNT_VH; d.executeUnit := ExeUnit.CSR
          setDestGpr(i_rd_raw)
      }
      is(M"0000000_0000_00000011000_?????_00000") { // RDCNTID.W rj
          d.uopCode := UopCode.RD_CNT_ID; d.executeUnit := ExeUnit.CSR
          setDestGpr(i_rj_raw) // Manual says rj, encoding shows rd position? Assuming rj is dest. Double check ISA spec.
      }


      // Default case: Illegal Instruction
      default {
        d.isValid := False // Mark as invalid instruction
        d.uopCode := UopCode.NOP
        d.generatesExc := True
        d.excCode := ExceptionCode.INE
      }
    } // End switch

    // --- Final Output Assignment ---
    io.decodedInst := d

} // End DecoderArea
