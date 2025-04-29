LA32R ISA
===

## Op Name Abbr Rules

ADD[I].{W/D} represents four ops: ADD.W, ADD.D, ADDI.W, ADDI.D

## CSR Name Rule

CSR.CRMD.PLV means the PLV field of CRMD register.

## Data Types

b, B, H, W, D (D not applicable for LA32)

## Regs

r0: constant 0; r1~r31: GPRs
PC: next instruction address register
CSRs: control and status register
BL instr target is implicitly stored in r1
Default return address is stored in r1

FR: f0~f31: 64bits floating point registers
CFR: condition flags register fcc0
FCSRs: floating-point control and status registers

| Bits    | Name    | R/W | Description                                                                                                                                                                                                                                    |
|---------|---------|-----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 4:0     | Enables | RW  | Enable bits for allowing VZOU floating-point exceptions to be triggered. Bit 4 corresponds to V, bit 3 to Z, bit 2 to O, bit 1 to U, and bit 0 to I.                                                                                          |
| 7:5     | 0       | R0  | Reserved. Reads return 0, and software must not modify its value.                                                                                                                                         |
| 9:8     | RM      | RW  | Contains rounding mode control. Defined as follows:<br>0: RNE;<br>1: RZ;<br>2: RP;<br>3: RM. |
| 15:10   | 0       | R0  | Reserved. Reads return 0, and software must not modify its value.                                                                                                                                        |
| 20:16   | Flags   | RW  | Since the last Flags field was cleared by software, cumulative floating-point exception status (except VZOU exceptions). Bit 20 corresponds to V, bit 19 to Z, bit 18 to O, bit 17 to U, and bit 16 to I.                                      |
| 23:21   | 0       | R0  | Reserved. Reads return 0, and software must not modify its value.                                                                                                                                        |
| 28:24   | Cause   | RW  | Most recent floating-point operation exception (VZOU exception). Bit 28 corresponds to V, bit 27 to Z, bit 26 to O, bit 25 to U, and bit 24 to I.                                                        |
| 31:29   | 0       | R0  | Reserved. Reads return 0, and software must not modify its value.                                                                                                                                        |
```

**Notes:**
- FCSR1 is the alias for the Enables field in FCSR0, with the same position as in FCSR0.
- FCSR2 is the alias for the Cause and Flags fields in FCSR0, with the same position as in FCSR0.
- FCSR3 is the alias for the RM field in FCSR0, with the same position as in FCSR0.

---

- I: Inexact
- U: Underflow
- O: Overflow
- Z: Division by Zero
- V: Invalid Operation

## Privileged Level (PLV)

PLV0 for OS, PLV3 for user progs

## Exception

Internal occur:
- SYS: by SYSCALL instr
- BRK: by BREAK instr
- INE: when opcode undef
- IPE: when execute privileged instr in user mode
- ADEF: when fetch unaligned instr address
- ALE: when load/store address unaligned
- FPE: when floating point exception occurs

## Address space

0x00000000-0xFFFFFFFF

## Endianness

Little-endian

## Mem Access

Weakly Consistency

- Coherent Cached(CC)
- Strongly-Ordered Uncached(SUC):
    - No side effects
    - No speculation

MAT field in PTE:
- 0: SUC
- 1: CC
- 2: Reserved

Sync ops: DBAR, IBAR, LL-SC instr pair


## Instr Types

Bits High -> Low
TYPE_2R:     opcode[31:10]                      rj[9:5] rd[4:0]
TYPE_3R:     opcode[31:15] rk[14:10]            rj[9:5] rd[4:0]
TYPE_4R:     opcode[31:20] ra[19:15] rk[14:10]  rj[9:5] rd[4:0]
TYPE_2RI8:   opcode[31:18] I8[17:10]            rj[9:5] rd[4:0]
TYPE_2RI12:  opcode[31:22] I12[21:10]           rj[9:5] rd[4:0]
TYPE_2RI14:  opcode[31:20] I14[19:10]           rj[9:5] rd[4:0]
TYPE_2RI16:  opcode[31:16] I16[15:0]            rj[9:5] rd[4:0]
TYPE_1RI21:  opcode[31:21] I21[15:0]            rj[9:5] I21[20:16]
TYPE_I26:    opcode[31:6]  I26[15:0]            I26[25:16]

## Ops

Okay, here is the Markdown table summarizing the provided LoongArch 32-bit instruction excerpts.

*Note: There seem to be some inconsistencies or ambiguities in the provided `TYPE_*` definitions regarding bit counts or exact field mappings for certain immediate sizes (e.g., `si20`, `ui5`, `si14`) and instructions with implicit operands or unique formats (e.g., `LU12I`, `PCADDU12I`, `SYSCALL`, `DBAR`). The "Instruction Type" column uses the best fit based on the operands present, sometimes making assumptions about how fields might be used or that the provided list is representative rather than exhaustive.*

```markdown
| Opcode      | Instruction Type | Instruction Format        | Pseudocode                                                                 |
| :---------- | :--------------- | :------------------------ | :------------------------------------------------------------------------- |
| ADD.W       | TYPE_3R          | `add.w rd, rj, rk`        | `tmp = GR[rj] + GR[rk]; GR[rd] = tmp[31:0]`                                 |
| SUB.W       | TYPE_3R          | `sub.w rd, rj, rk`        | `tmp = GR[rj] - GR[rk]; GR[rd] = tmp[31:0]`                                 |
| ADDI.W      | TYPE_2RI12       | `addi.w rd, rj, si12`     | `tmp = GR[rj] + sext(si12, 32); GR[rd] = tmp[31:0]`                   |
| LU12I.W     | TYPE_2RI14 (?)   | `lu12i.w rd, si20`        | `GR[rd] = {si20, 12'b0}`                                                   |
| SLT         | TYPE_3R          | `slt rd, rj, rk`          | `GR[rd] = (signed(GR[rj]) < signed(GR[rk])) ? 1 : 0`                       |
| SLTU        | TYPE_3R          | `sltu rd, rj, rk`         | `GR[rd] = (unsigned(GR[rj]) < unsigned(GR[rk])) ? 1 : 0`                   |
| SLTI        | TYPE_2RI12       | `slti rd, rj, si12`       | `tmp = sext(si12, 32); GR[rd] = (signed(GR[rj]) < signed(tmp)) ? 1 : 0` |
| SLTUI       | TYPE_2RI12       | `sltui rd, rj, si12`      | `tmp = sext(si12, 32); GR[rd] = (unsigned(GR[rj]) < unsigned(tmp)) ? 1 : 0` |
| PCADDU12I   | TYPE_2RI14 (?)   | `pcaddu12i rd, si20`      | `GR[rd] = PC + sext({si20, 12'b0}, 32)`                              |
| AND         | TYPE_3R          | `and rd, rj, rk`          | `GR[rd] = GR[rj] & GR[rk]`                                                 |
| OR          | TYPE_3R          | `or rd, rj, rk`           | `GR[rd] = GR[rj] | GR[rk]`                                                 |
| NOR         | TYPE_3R          | `nor rd, rj, rk`          | `GR[rd] = ~(GR[rj] | GR[rk])`                                                |
| XOR         | TYPE_3R          | `xor rd, rj, rk`          | `GR[rd] = GR[rj] ^ GR[rk]`                                                 |
| ANDI        | TYPE_2RI12       | `andi rd, rj, ui12`       | `GR[rd] = GR[rj] & ZeroExtend(ui12, 32)`                                   |
| ORI         | TYPE_2RI12       | `ori rd, rj, ui12`        | `GR[rd] = GR[rj] | ZeroExtend(ui12, 32)`                                   |
| XORI        | TYPE_2RI12       | `xori rd, rj, ui12`       | `GR[rd] = GR[rj] ^ ZeroExtend(ui12, 32)`                                   |
| NOP         | TYPE_2RI12       | `nop`                     | `GR[r0] = GR[r0] & 0 // Alias for andi r0, r0, 0`                          |
| MUL.W       | TYPE_3R          | `mul.w rd, rj, rk`        | `product = signed(GR[rj]) * signed(GR[rk]); GR[rd] = product[31:0]`        |
| MULH.W      | TYPE_3R          | `mulh.w rd, rj, rk`       | `product = signed(GR[rj]) * signed(GR[rk]); GR[rd] = product[63:32]`       |
| MULH.WU     | TYPE_3R          | `mulh.wu rd, rj, rk`      | `product = unsigned(GR[rj]) * unsigned(GR[rk]); GR[rd] = product[63:32]`    |
| DIV.W       | TYPE_3R          | `div.w rd, rj, rk`        | `quotient = signed(GR[rj]) / signed(GR[rk]); GR[rd] = quotient[31:0]`      |
| MOD.W       | TYPE_3R          | `mod.w rd, rj, rk`        | `remainder = signed(GR[rj]) % signed(GR[rk]); GR[rd] = remainder[31:0]`   |
| DIV.WU      | TYPE_3R          | `div.wu rd, rj, rk`       | `quotient = unsigned(GR[rj]) / unsigned(GR[rk]); GR[rd] = quotient[31:0]`  |
| MOD.WU      | TYPE_3R          | `mod.wu rd, rj, rk`       | `remainder = unsigned(GR[rj]) % unsigned(GR[rk]); GR[rd] = remainder[31:0]`|
| SLL.W       | TYPE_3R          | `sll.w rd, rj, rk`        | `tmp = SLL(GR[rj], GR[rk][4:0]); GR[rd] = tmp[31:0]`                       |
| SRL.W       | TYPE_3R          | `srl.w rd, rj, rk`        | `tmp = SRL(GR[rj], GR[rk][4:0]); GR[rd] = tmp[31:0]`                       |
| SRA.W       | TYPE_3R          | `sra.w rd, rj, rk`        | `tmp = SRA(GR[rj], GR[rk][4:0]); GR[rd] = tmp[31:0]`                       |
| SLLI.W      | TYPE_2RI12 (?)   | `slli.w rd, rj, ui5`      | `tmp = SLL(GR[rj], ui5); GR[rd] = tmp[31:0]`                               |
| SRLI.W      | TYPE_2RI12 (?)   | `srli.w rd, rj, ui5`      | `tmp = SRL(GR[rj], ui5); GR[rd] = tmp[31:0]`                               |
| SRAI.W      | TYPE_2RI12 (?)   | `srai.w rd, rj, ui5`      | `tmp = SRA(GR[rj], ui5); GR[rd] = tmp[31:0]`                               |
| BEQ         | TYPE_2RI16       | `beq rj, rd, offs16`      | `if GR[rj]==GR[rd] : PC = PC + sext({offs16, 2'b0}, 32)`             |
| BNE         | TYPE_2RI16       | `bne rj, rd, offs16`      | `if GR[rj]!=GR[rd] : PC = PC + sext({offs16, 2'b0}, 32)`             |
| BLT         | TYPE_2RI16       | `blt rj, rd, offs16`      | `if signed(GR[rj]) < signed(GR[rd]) : PC = PC + sext({offs16, 2'b0}, 32)` |
| BGE         | TYPE_2RI16       | `bge rj, rd, offs16`      | `if signed(GR[rj]) >= signed(GR[rd]) : PC = PC + sext({offs16, 2'b0}, 32)`|
| BLTU        | TYPE_2RI16       | `bltu rj, rd, offs16`     | `if unsigned(GR[rj]) < unsigned(GR[rd]) : PC = PC + sext({offs16, 2'b0}, 32)`|
| BGEU        | TYPE_2RI16       | `bgeu rj, rd, offs16`     | `if unsigned(GR[rj]) >= unsigned(GR[rd]) : PC = PC + sext({offs16, 2'b0}, 32)`|
| B           | TYPE_I26         | `b offs26`                | `PC = PC + sext({offs26, 2'b0}, 32)`                                 |
| BL          | TYPE_I26         | `bl offs26`               | `GR[1] = PC + 4; PC = PC + sext({offs26, 2'b0}, 32)`                 |
| JIRL        | TYPE_2RI16       | `jirl rd, rj, offs16`     | `GR[rd] = PC + 4; PC = GR[rj] + sext({offs16, 2'b0}, 32)`            |
| LD.B        | TYPE_2RI12       | `ld.b rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); byte = ld(vaddr, BYTE); GR[rd] = sext(byte, 32)` |
| LD.H        | TYPE_2RI12       | `ld.h rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); half = ld(vaddr, HALFWORD); GR[rd] = sext(half, 32)` |
| LD.W        | TYPE_2RI12       | `ld.w rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); word = ld(vaddr, WORD); GR[rd] = word` |
| LD.BU       | TYPE_2RI12       | `ld.bu rd, rj, si12`      | `vaddr = GR[rj] + sext(si12, 32); byte = ld(vaddr, BYTE); GR[rd] = ZeroExtend(byte, 32)` |
| LD.HU       | TYPE_2RI12       | `ld.hu rd, rj, si12`      | `vaddr = GR[rj] + sext(si12, 32); half = ld(vaddr, HALFWORD); GR[rd] = ZeroExtend(half, 32)` |
| ST.B        | TYPE_2RI12       | `st.b rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); st(GR[rd][7:0], vaddr, BYTE)` |
| ST.H        | TYPE_2RI12       | `st.h rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); st(GR[rd][15:0], vaddr, HALFWORD)` |
| ST.W        | TYPE_2RI12       | `st.w rd, rj, si12`       | `vaddr = GR[rj] + sext(si12, 32); st(GR[rd][31:0], vaddr, WORD)` |
| PRELD       | TYPE_2RI12       | `preld hint, rj, si12`    | `vaddr = GR[rj] + sext(si12, 32); // Prefetch from vaddr based on hint` |
| LL.W        | TYPE_2RI14 (?)   | `ll.w rd, rj, si14`       | `vaddr = GR[rj] + sext({si14, 2'b0}, 32); GR[rd] = ld(vaddr, WORD); // Set LLbit` |
| SC.W        | TYPE_2RI14 (?)   | `sc.w rd, rj, si14`       | `vaddr = GR[rj] + sext({si14, 2'b0}, 32); if LLbit { st(GR[rd][31:0], vaddr, WORD); GR[rd] = 1; } else { GR[rd] = 0; } // Clear LLbit` |
| DBAR        | TYPE_2R (?)      | `dbar hint`               | `// Memory barrier based on hint`                                          |
| IBAR        | TYPE_2R (?)      | `ibar hint`               | `// Instruction fetch barrier based on hint`                               |
| SYSCALL     | TYPE_2R (?)      | `syscall code`            | `// Trigger system call exception; code passed to handler`                 |
| BREAK       | TYPE_2R (?)      | `break code`              | `// Trigger breakpoint exception; code passed to handler`                  |
| RDCNTVL.W   | TYPE_2R          | `rdcntvl.w rd`            | `GR[rd] = StableCounter[31:0]`                                             |
| RDCNTVH.W   | TYPE_2R          | `rdcntvh.w rd`            | `GR[rd] = StableCounter[63:32]`                                            |
| RDCNTID     | TYPE_2R          | `rdcntid rj`              | `GR[rj] = CounterID`                                                       |


FP

| FADD.S             | TYPE_3R          | `fadd.s fd, fj, fk`          | `FR[fd][31:0] = FP32_addition(FR[fj][31:0], FR[fk][31:0])`                             |
| FADD.D             | TYPE_3R          | `fadd.d fd, fj, fk`          | `FR[fd] = FP64_addition(FR[fj], FR[fk])`                                               |
| FSUB.S             | TYPE_3R          | `fsub.s fd, fj, fk`          | `FR[fd][31:0] = FP32_subtraction(FR[fj][31:0], FR[fk][31:0])`                           |
| FSUB.D             | TYPE_3R          | `fsub.d fd, fj, fk`          | `FR[fd] = FP64_subtraction(FR[fj], FR[fk])`                                             |
| FMUL.S             | TYPE_3R          | `fmul.s fd, fj, fk`          | `FR[fd][31:0] = FP32_multiplication(FR[fj][31:0], FR[fk][31:0])`                       |
| FMUL.D             | TYPE_3R          | `fmul.d fd, fj, fk`          | `FR[fd] = FP64_multiplication(FR[fj], FR[fk])`                                         |
| FDIV.S             | TYPE_3R          | `fdiv.s fd, fj, fk`          | `FR[fd][31:0] = FP32_division(FR[fj][31:0], FR[fk][31:0])`                             |
| FDIV.D             | TYPE_3R          | `fdiv.d fd, fj, fk`          | `FR[fd] = FP64_division(FR[fj], FR[fk])`                                               |
| FMADD.S            | TYPE_4R          | `fmadd.s fd, fj, fk, fa`     | `FR[fd][31:0] = FP32_fusedMultiplyAdd(FR[fj][31:0], FR[fk][31:0], FR[fa][31:0])`       |
| FMADD.D            | TYPE_4R          | `fmadd.d fd, fj, fk, fa`     | `FR[fd] = FP64_fusedMultiplyAdd(FR[fj], FR[fk], FR[fa])`                               |
| FMSUB.S            | TYPE_4R          | `fmsub.s fd, fj, fk, fa`     | `FR[fd][31:0] = FP32_fusedMultiplyAdd(FR[fj][31:0], FR[fk][31:0], -FR[fa][31:0])`      |
| FMSUB.D            | TYPE_4R          | `fmsub.d fd, fj, fk, fa`     | `FR[fd] = FP64_fusedMultiplyAdd(FR[fj], FR[fk], -FR[fa])`                              |
| FNMADD.S           | TYPE_4R          | `fnmadd.s fd, fj, fk, fa`    | `FR[fd][31:0] = -FP32_fusedMultiplyAdd(FR[fj][31:0], FR[fk][31:0], FR[fa][31:0])`      |
| FNMADD.D           | TYPE_4R          | `fnmadd.d fd, fj, fk, fa`    | `FR[fd] = -FP64_fusedMultiplyAdd(FR[fj], FR[fk], FR[fa])`                              |
| FNMSUB.S           | TYPE_4R          | `fnmsub.s fd, fj, fk, fa`    | `FR[fd][31:0] = -FP32_fusedMultiplyAdd(FR[fj][31:0], FR[fk][31:0], -FR[fa][31:0])`     |
| FNMSUB.D           | TYPE_4R          | `fnmsub.d fd, fj, fk, fa`    | `FR[fd] = -FP64_fusedMultiplyAdd(FR[fj], FR[fk], -FR[fa])`                             |
| FMAX.S             | TYPE_3R          | `fmax.s fd, fj, fk`          | `FR[fd][31:0] = FP32_maxNum(FR[fj][31:0], FR[fk][31:0])`                               |
| FMAX.D             | TYPE_3R          | `fmax.d fd, fj, fk`          | `FR[fd] = FP64_maxNum(FR[fj], FR[fk])`                                                 |
| FMIN.S             | TYPE_3R          | `fmin.s fd, fj, fk`          | `FR[fd][31:0] = FP32_minNum(FR[fj][31:0], FR[fk][31:0])`                               |
| FMIN.D             | TYPE_3R          | `fmin.d fd, fj, fk`          | `FR[fd] = FP64_minNum(FR[fj], FR[fk])`                                                 |
| FMAXA.S            | TYPE_3R          | `fmaxa.s fd, fj, fk`         | `FR[fd][31:0] = FP32_maxNumMag(FR[fj][31:0], FR[fk][31:0])`                            |
| FMAXA.D            | TYPE_3R          | `fmaxa.d fd, fj, fk`         | `FR[fd] = FP64_maxNumMag(FR[fj], FR[fk])`                                              |
| FMINA.S            | TYPE_3R          | `fmina.s fd, fj, fk`         | `FR[fd][31:0] = FP32_minNumMag(FR[fj][31:0], FR[fk][31:0])`                            |
| FMINA.D            | TYPE_3R          | `fmina.d fd, fj, fk`         | `FR[fd] = FP64_minNumMag(FR[fj], FR[fk])`                                              |
| FABS.S             | TYPE_2R          | `fabs.s fd, fj`              | `FR[fd][31:0] = FP32_abs(FR[fj][31:0])`                                                |
| FABS.D             | TYPE_2R          | `fabs.d fd, fj`              | `FR[fd] = FP64_abs(FR[fj])`                                                            |
| FNEG.S             | TYPE_2R          | `fneg.s fd, fj`              | `FR[fd][31:0] = FP32_negate(FR[fj][31:0])`                                             |
| FNEG.D             | TYPE_2R          | `fneg.d fd, fj`              | `FR[fd] = FP64_negate(FR[fj])`                                                         |
| FSQRT.S            | TYPE_2R          | `fsqrt.s fd, fj`             | `FR[fd][31:0] = FP32_squareRoot(FR[fj][31:0])`                                         |
| FSQRT.D            | TYPE_2R          | `fsqrt.d fd, fj`             | `FR[fd] = FP64_squareRoot(FR[fj])`                                                     |
| FRECIP.S           | TYPE_2R          | `frecip.s fd, fj`            | `FR[fd][31:0] = FP32_division(1.0, FR[fj][31:0])`                                      |
| FRECIP.D           | TYPE_2R          | `frecip.d fd, fj`            | `FR[fd] = FP64_division(1.0, FR[fj])`                                                  |
| FRSQRT.S           | TYPE_2R          | `frsqrt.s fd, fj`            | `FR[fd][31:0] = FP32_division(1.0, FP_squareRoot(FR[fj][31:0]))`                       |
| FRSQRT.D           | TYPE_2R          | `frsqrt.d fd, fj`            | `FR[fd] = FP64_division(1.0, FP_squareRoot(FR[fj]))`                                   |
| FCOPYSIGN.S        | TYPE_3R          | `fcopysign.s fd, fj, fk`     | `FR[fd][31:0] = FP32_copySign(FR[fj][31:0], FR[fk][31:0])`                             |
| FCOPYSIGN.D        | TYPE_3R          | `fcopysign.d fd, fj, fk`     | `FR[fd] = FP64_copySign(FR[fj], FR[fk])`                                               |
| FCLASS.S           | TYPE_2R          | `fclass.s fd, fj`            | `FR[fd][31:0] = FP32_class(FR[fj][31:0])`                                              |
| FCLASS.D           | TYPE_2R          | `fclass.d fd, fj`            | `FR[fd] = FP64_class(FR[fj])`                                                          |
| FCMP.cond.S        | TYPE_3R          | `fcmp.cond.s cc, fj, fk`     | `CFR[cc] = FP32_compare(FR[fj][31:0], FR[fk][31:0], cond)`                             |
| FCMP.cond.D        | TYPE_3R          | `fcmp.cond.d cc, fj, fk`     | `CFR[cc] = FP64_compare(FR[fj], FR[fk], cond)`                                         |
| FCVT.S.D           | TYPE_2R          | `fcvt.s.d fd, fj`            | `FR[fd][31:0] = FP32_convertFormat(FR[fj], FP64)`                                      |
| FCVT.D.S           | TYPE_2R          | `fcvt.d.s fd, fj`            | `FR[fd] = FP64_convertFormat(FR[fj][31:0], FP32)`                                      |
| FFINT.S.W          | TYPE_2R          | `ffint.s.w fd, fj`           | `FR[fd][31:0] = FP32_convertFromInt(FR[fj][31:0], SINT32)`                             |
| FFINT.D.W          | TYPE_2R          | `ffint.d.w fd, fj`           | `FR[fd] = FP64_convertFromInt(FR[fj][31:0], SINT32)`                                   |
| FTINT.W.S          | TYPE_2R          | `ftint.w.s fd, fj`           | `FR[fd][31:0] = FP32toSint32(FR[fj][31:0], FCSR.RM)`                            |
| FTINT.W.D          | TYPE_2R          | `ftint.w.d fd, fj`           | `FR[fd][31:0] = FP64toSint32(FR[fj], FCSR.RM)` // Note: Result is 32-bit int in FR |
| FTINTRM.W.S        | TYPE_2R          | `ftintrm.w.s fd, fj`         | `FR[fd][31:0] = FP32toSint32(FR[fj][31:0], 3)` // RM = TowardNegative         |
| FTINTRM.W.D        | TYPE_2R          | `ftintrm.w.d fd, fj`         | `FR[fd][31:0] = FP64toSint32(FR[fj], 3)` // RM = TowardNegative                 |
| FTINTRP.W.S        | TYPE_2R          | `ftintrp.w.s fd, fj`         | `FR[fd][31:0] = FP32toSint32(FR[fj][31:0], 2)` // RM = TowardPositive         |
| FTINTRP.W.D        | TYPE_2R          | `ftintrp.w.d fd, fj`         | `FR[fd][31:0] = FP64toSint32(FR[fj], 2)` // RM = TowardPositive                 |
| FTINTRZ.W.S        | TYPE_2R          | `ftintrz.w.s fd, fj`         | `FR[fd][31:0] = FP32toSint32(FR[fj][31:0], 1)` // RM = TowardZero             |
| FTINTRZ.W.D        | TYPE_2R          | `ftintrz.w.d fd, fj`         | `FR[fd][31:0] = FP64toSint32(FR[fj], 1)` // RM = TowardZero                     |
| FTINTRNE.W.S       | TYPE_2R          | `ftintrne.w.s fd, fj`        | `FR[fd][31:0] = FP32toSint32(FR[fj][31:0], 0)` // RM = TiesToEven             |
| FTINTRNE.W.D       | TYPE_2R          | `ftintrne.w.d fd, fj`        | `FR[fd][31:0] = FP64toSint32(FR[fj], 0)` // RM = TiesToEven                     |
| FMOV.S             | TYPE_2R          | `fmov.s fd, fj`              | `FR[fd][31:0] = FR[fj][31:0]`                                                          |
| FMOV.D             | TYPE_2R          | `fmov.d fd, fj`              | `FR[fd] = FR[fj]`                                                                      |
| FSEL               | TYPE_4R          | `fsel fd, fj, fk, ca`        | `FR[fd] = CFR[ca] ? FR[fk] : FR[fj]`                                                   |
| MOVGR2FR.W         | TYPE_2R          | `movgr2fr.w fd, rj`          | `FR[fd][31:0] = GR[rj]`                                                                |
| MOVGR2FRH.W        | TYPE_2R          | `movgr2frh.w fd, rj`         | `FR[fd][63:32] = GR[rj]`                                                               |
| MOVFR2GR.S         | TYPE_2R          | `movfr2gr.s rd, fj`          | `GR[rd] = FR[fj][31:0]`                                                                |
| MOVFRH2GR.S        | TYPE_2R          | `movfrh2gr.s rd, fj`         | `GR[rd] = FR[fj][63:32]`                                                               |
| MOVGR2FCSR         | TYPE_2R          | `movgr2fcsr fcsr, rj`        | `FCSR[fcsr] = GR[rj]`                                                                  |
| MOVFCSR2GR         | TYPE_2R          | `movfcsr2gr rd, fcsr`        | `GR[rd] = FCSR[fcsr]`                                                                  |
| MOVFR2CF           | TYPE_2R          | `movfr2cf cd, fj`            | `CFR[cd] = FR[fj][0]`                                                                  |
| MOVCF2FR           | TYPE_2R          | `movcf2fr fd, cj`            | `FR[fd][0] = ZeroExtend(CFR[cj], 64)` // Note: Manual says 64, likely typo for 32/64 based on context |
| MOVGR2CF           | TYPE_2R          | `movgr2cf cd, rj`            | `CFR[cd] = GR[rj][0]`                                                                  |
| MOVCF2GR           | TYPE_2R          | `movcf2gr rd, cj`            | `GR[rd][0] = ZeroExtend(CFR[cj], 32)` // Note: Manual says 32, likely typo for 64 based on context |
| BCEQZ              | TYPE_1RI21       | `bceqz cj, offs21`           | `if CFR[cj]==0 : PC = PC + sext({offs21, 2'b0}, 32)`                             |
| BCNEZ              | TYPE_1RI21       | `bcnez cj, offs21`           | `if CFR[cj]!=0 : PC = PC + sext({offs21, 2'b0}, 32)`                             |
| FLD.S              | TYPE_2RI12       | `fld.s fd, rj, si12`         | `vaddr = GR[rj] + sext(si12, 32); word = ld(vaddr, WORD); FR[fd][31:0] = word` |
| FLD.D              | TYPE_2RI12       | `fld.d fd, rj, si12`         | `vaddr = GR[rj] + sext(si12, 32); dword = ld(vaddr, DOUBLEWORD); FR[fd] = dword` |
| FST.S              | TYPE_2RI12       | `fst.s fd, rj, si12`         | `vaddr = GR[rj] + sext(si12, 32); st(FR[fd][31:0], vaddr, WORD)`         |
| FST.D              | TYPE_2RI12       | `fst.d fd, rj, si12`         | `vaddr = GR[rj] + sext(si12, 32); st(FR[fd], vaddr, DOUBLEWORD)`         |

FCMP.cond:

| Mnemonic | cond  | Meaning                        | True Condition      | QNaN Exception | IEEE 754-2008 Function          |
|----------|-------|-------------------------------|--------------------|---------------|---------------------------------|
| CAF      | 0x0   | False                         | None               | No            |                                 |
| CUN      | 0x8   | Unordered                     | UN                 | No            | compareQuietUnordered           |
| CEQ      | 0x4   | Equal                         | EQ                 | No            | compareQuietEqual               |
| CUEQ     | 0xC   | Unordered or Equal            | UN EQ              | No            |                                 |
| CLT      | 0x2   | Less than                     | LT                 | No            | compareQuietLess                |
| CULT     | 0xA   | Unordered or Less than        | UN LT              | No            | compareQuietLessUnordered       |
| CLE      | 0x6   | Less than or Equal            | LT EQ              | No            | compareQuietLessEqual           |
| CULE     | 0xE   | Unordered or Less/Equal       | UN LT EQ           | No            | compareQuietNotGreater          |
| CNE      | 0x10  | Not Equal                     | GT LT EQ           | No            |                                 |
| COR      | 0x12  | Ordered                       | GT LT              | No            |                                 |
| CUNE     | 0x18  | Unordered or Not Equal        | UN GT LT           | No            | compareQuietNotEqual            |
| SAF      | 0x1   | False                         | None               | Yes           |                                 |
| SUN      | 0x9   | Not Greater or Equal          | UN                 | Yes           |                                 |
| SEQ      | 0x5   | Equal                         | EQ                 | Yes           | compareSignalingEqual           |
| SUEQ     | 0xD   | Not Greater or Less           | UN EQ              | Yes           |                                 |
| SLT      | 0x3   | Less than                     | LT                 | Yes           | compareSignalingLess            |
| SULT     | 0xB   | Not Greater                   | UN LT              | Yes           | compareSignalingLessUnordered   |
| SLE      | 0x7   | Less than or Equal            | LT EQ              | Yes           | compareSignalingLessEqual       |
| SULE     | 0xF   | Not Greater                   | UN LT EQ           | Yes           | compareSignalingNotGreater      |
| SNE      | 0x11  | Not Equal                     | GT LT EQ           | Yes           |                                 |
| SOR      | 0x15  | Ordered                       | GT LT              | Yes           |                                 |
| SUNE     | 0x19  | Unordered or Not Equal        | UN GT LT           | Yes           |                                 |

**Notes:**
- UN: Unordered (at least one operand is NaN, so the numbers cannot be compared).
- EQ: Equal.
- LT: Less than.

Encoding:

                        31                                 0
RDCNTID.W rj            0000000 0000000000 11000 rj    00000
RDCNTVL.W rd            0000000 0000000000 11000 00000 rd
RDCNTVH.W rd            0000000 0000000000 11001 00000 rd
ADD.W rd, rj, rk        0000000 0000100000 rk    rj    rd
SUB.W rd, rj, rk        0000000 0000100010 rk    rj    rd
SLT rd, rj, rk          0000000 0000100100 rk    rj    rd
SLTU rd, rj, rk         0000000 0000100101 rk    rj    rd
NOR rd, rj, rk          0000000 0000101000 rk    rj    rd
AND rd, rj, rk          0000000 0000101001 rk    rj    rd
OR rd, rj, rk           0000000 0000101010 rk    rj    rd
XOR rd, rj, rk          0000000 0000101011 rk    rj    rd
SLL.W rd, rj, rk        0000000 0000101110 rk    rj    rd
SRL.W rd, rj, rk        0000000 0000101111 rk    rj    rd
SRA.W rd, rj, rk        0000000 0000110000 rk    rj    rd
MUL.W rd, rj, rk        0000000 0000111000 rk    rj    rd
MULH.W rd, rj, rk       0000000 0000111001 rk    rj    rd
MULH.WU rd, rj, rk      0000000 0000111010 rk    rj    rd
DIV.W rd, rj, rk        0000000 0001000000 rk    rj    rd
MOD.W rd, rj, rk        0000000 0001000001 rk    rj    rd
DIV.WU rd, rj, rk       0000000 0001000010 rk    rj    rd
MOD.WU rd, rj, rk       0000000 0001000011 rk    rj    rd
BREAK code              0000000 0001010100 code[14:0]
SYSCALL code            0000000 0001010110 code
SLLI.W rd, rj, ui5      0000000 0010000001 ui5   rj    rd
SRLI.W rd, rj, ui5      0000000 0010001001 ui5   rj    rd
SRAI.W rd, rj, ui5      0000000 0010010001 ui5   rj    rd
FADD.S fd, fj, fk       0000000 1000000001 fk    fj    fd
FADD.D fd, fj, fk       0000000 1000000010 fk    fj    fd
FSUB.S fd, fj, fk       0000000 1000000101 fk    fj    fd
FSUB.D fd, fj, fk       0000000 1000000110 fk    fj    fd
FMUL.S fd, fj, fk       0000000 1000001001 fk    fj    fd
FMUL.D fd, fj, fk       0000000 1000001010 fk    fj    fd
FDIV.S fd, fj, fk       0000000 1000001101 fk    fj    fd
FDIV.D fd, fj, fk       0000000 1000001110 fk    fj    fd
FMAX.S fd, fj, fk       0000000 1000010001 fk    fj    fd
FMAX.D fd, fj, fk       0000000 1000010010 fk    fj    fd
FMIN.S fd, fj, fk       0000000 1000010101 fk    fj    fd
FMIN.D fd, fj, fk       0000000 1000010110 fk    fj    fd
FMAXA.S fd, fj, fk      0000000 1000011001 fk    fj    fd
FMAXA.D fd, fj, fk      0000000 1000011010 fk    fj    fd
FMINA.S fd, fj, fk      0000000 1000011101 fk    fj    fd
FMINA.D fd, fj, fk      0000000 1000011110 fk    fj    fd
FCOPYSIGN.S fd, fj, fk  0000000 1000100101 fk    fj    fd
FCOPYSIGN.D fd, fj, fk  0000000 1000100110 fk    fj    fd
FABS.S fd, fj           0000000 1000101000 00001 fj    fd
FABS.D fd, fj           0000000 1000101000 00010 fj    fd
FNEG.S fd, fj           0000000 1000101000 00101 fj    fd
FNEG.D fd, fj           0000000 1000101000 00110 fj    fd
FCLASS.S fd, fj         0000000 1000101000 01101 fj    fd
FCLASS.D fd, fj         0000000 1000101000 01110 fj    fd
FSQRT.S fd, fj          0000000 1000101000 10001 fj    fd
FSQRT.D fd, fj          0000000 1000101000 10010 fj    fd
FRECIP.S fd, fj         0000000 1000101000 10101 fj    fd
FRECIP.D fd, fj         0000000 1000101000 10110 fj    fd
FRSQRT.S fd, fj         0000000 1000101000 11001 fj    fd
FRSQRT.D fd, fj         0000000 1000101000 11010 fj    fd
FMOV.S fd, fj           0000000 1000101001 00101 fj    fd
FMOV.D fd, fj           0000000 1000101001 00110 fj    fd
MOVGR2FR.W fd, rj       0000000 1000101001 01001 rj    fd
MOVGR2FRH.W fd, rj      0000000 1000101001 01011 rj    fd
MOVFR2GR.S rd, fj       0000000 1000101001 01101 fj    rd
MOVFRH2GR.S rd, fj      0000000 1000101001 01111 fj    rd
MOVGR2FCSR fcsr, rj     0000000 1000101001 10000 rj    fcsr
MOVFCSR2GR rd, fcsr     0000000 1000101001 10010 fcsr  rd
MOVFR2CF cd, fj         0000000 1000101001 10100 fj    00cd
MOVCF2FR fd, cj         0000000 1000101001 10101 00cj  fd
MOVGR2CF cd, rj         0000000 1000101001 10110 rj    00cd
MOVCF2GR rd, cj         0000000 1000101001 10111 00cj  rd
FCVT.S.D fd, fj         0000000 1000110010 00110 fj    fd
FCVT.D.S fd, fj         0000000 1000110010 01001 fj    fd
FTINTRM.W.S fd, fj      0000000 1000110100 00001 fj    fd
FTINTRM.W.D fd, fj      0000000 1000110100 00010 fj    fd
FTINTRP.W.S fd, fj      0000000 1000110100 10001 fj    fd
FTINTRP.W.D fd, fj      0000000 1000110100 10010 fj    fd
FTINTRZ.W.S fd, fj      0000000 1000110101 00001 fj    fd
FTINTRZ.W.D fd, fj      0000000 1000110101 00010 fj    fd
FTINTRNE.W.S fd, fj     0000000 1000110101 10001 fj    fd
FTINTRNE.W.D fd, fj     0000000 1000110101 10010 fj    fd
FTINT.W.S fd, fj        0000000 1000110110 00001 fj    fd
FTINT.W.D fd, fj        0000000 1000110110 00010 fj    fd
FFINT.S.W fd, fj        0000000 1000111010 00100 fj    fd
FFINT.D.W fd, fj        0000000 1000111010 01000 fj    fd
SLTI rd, rj, si12       0000001 000 si12         rj    rd
SLTUI rd, rj, si12      0000001 001 si12         rj    rd
ADDI.W rd, rj, si12     0000001 010 si12         rj    rd
ANDI rd, rj, ui12       0000001 101 ui12         rj    rd
ORI rd, rj, ui12        0000001 110 ui12         rj    rd
XORI rd, rj, ui12       0000001 111 ui12         rj    rd
CSRRD rd, csr           0000010 0 csr[23:10]     00000 rd
CSRWR rd, csr           0000010 0 csr[23:10]     00001 rd
CSRXCHG rd, rj, csr     0000010 0 csr[23:10]     rj    rd // rj!=0,1
CACOP code, rj, si12    0000011 000 si12         rj  code
TLBSRCH                 0000011 00100 10000 010100000000000
TLBRD                   0000011 00100 10000 010110000000000
TLBWR                   0000011 00100 10000 011000000000000
TLBFILL                 0000011 00100 10000 011010000000000
ERTN                    0000011 00100 10000 011100000000000
IDLE level              0000011 00100 10001 level[14:0]
INVTLB op, rj, rk       0000011 00100 10011 rk   rj    op
FMADD.S fd, fj, fk, fa  0000100 00001 fa    fk   fj    fd
FMADD.D fd, fj, fk, fa  0000100 00010 fa    fk   fj    fd
FMSUB.S fd, fj, fk, fa  0000100 00101 fa    fk   fj    fd
FMSUB.D fd, fj, fk, fa  0000100 00110 fa    fk   fj    fd
FNMADD.S fd, fj, fk, fa 0000100 01001 fa    fk   fj    fd
FNMADD.D fd, fj, fk, fa 0000100 01010 fa    fk   fj    fd
FNMSUB.S fd, fj, fk, fa 0000100 01101 fa    fk   fj    fd
FNMSUB.D fd, fj, fk, fa 0000100 01110 fa    fk   fj    fd
FCMP.cond.S cd, fj, fk  0000110 00001 cond  fk   fj    00cd
FCMP.cond.D cd, fj, fk  0000110 00010 cond  fk   fj    00cd
FSEL fd, fj, fk, ca     0000110 10000 00ca  fk   fj    fd
LU12I.W rd, si20        0001010 si20                   rd
PCADDU12I rd, si20      0001110 si20                   rd
LL.W rd, rj, si14       0010000 0 si14           rj    rd
SC.W rd, rj, si14       0010000 1 si14           rj    rd
LD.B rd, rj, si12       0010100 000 si12         rj    rd
LD.H rd, rj, si12       0010100 001 si12         rj    rd
LD.W rd, rj, si12       0010100 010 si12         rj    rd
ST.B rd, rj, si12       0010100 100 si12         rj    rd
ST.H rd, rj, si12       0010100 101 si12         rj    rd
ST.W rd, rj, si12       0010100 110 si12         rj    rd
LD.BU rd, rj, si12      0010101 000 si12         rj    rd
LD.HU rd, rj, si12      0010101 001 si12         rj    rd
PRELD hint, rj, si12    0010101 011 si12         rj    hint
FLD.S fd, rj, si12      0010101 100 si12         rj    fd
FST.S fd, rj, si12      0010101 101 si12         rj    fd
FLD.D fd, rj, si12      0010101 110 si12         rj    fd
FST.D fd, rj, si12      0010101 111 si12         rj    fd
DBAR hint               0011100 001 1100100 hint[14:0]
IBAR hint               0011100 001 1100101 hint[14:0]
BCEQZ cj, offs          010010 offs[15:0]        00cj  offs[20:16]
BCNEZ cj, offs          010010 offs[15:0]        01cj  offs[20:16]
JIRL rd, rj, offs       010011 offs[15:0]        rj    rd
B offs                  010100 offs[15:0]        offs[25:16]
BL offs                 010101 offs[15:0]        offs[25:16]
BEQ rj, rd, offs        010110 offs[15:0]        rj    rd
BNE rj, rd, offs        010111 offs[15:0]        rj    rd
BLT rj, rd, offs        011000 offs[15:0]        rj    rd
BGE rj, rd, offs        011001 offs[15:0]        rj    rd
BLTU rj, rd, offs       011010 offs[15:0]        rj    rd
BGEU rj, rd, offs       011011 offs[15:0]        rj    rd
