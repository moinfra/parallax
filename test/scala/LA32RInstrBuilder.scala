// command: testOnly test.scala.LA32RInstrBuilderSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.math.BigInt

object LA32RInstrBuilder {

  private def toBinary(value: BigInt, width: Int): String = {
    val mask = (BigInt(1) << width) - 1
    val maskedValue = value & mask
    val binaryString = maskedValue.toString(2)
    "0" * (width - binaryString.length) + binaryString
  }

  // Overload for Int for convenience
  private def toBinary(value: Int, width: Int): String = toBinary(BigInt(value), width)

  private def fromBinary(binStr: String): BigInt = {
    val cleanStr = binStr.replace(" ", "")
    if (cleanStr.length != 32) {
      throw new IllegalArgumentException(s"Binary string must be 32 bits long, but got ${cleanStr.length} for '$binStr'")
    }
    BigInt(cleanStr, 2)
  }

  // --- R-Type (2R) Instructions ---
  // Format: opcode[31:25]=0 | opcode[24:15] | rk[14:10] | rj[9:5] | rd[4:0]
  private def buildRType(minorOp: String, rd: Int, rj: Int, rk: Int): BigInt = {
    val majorOp = "0000000"
    fromBinary(s"$majorOp$minorOp${toBinary(rk, 5)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  def add_w(rd: Int, rj: Int, rk: Int): BigInt = buildRType("0000100000", rd, rj, rk)
  def sub_w(rd: Int, rj: Int, rk: Int): BigInt = buildRType("0000100010", rd, rj, rk)
  def or(rd: Int, rj: Int, rk: Int): BigInt    = buildRType("0000101010", rd, rj, rk)
  def and(rd: Int, rj: Int, rk: Int): BigInt   = buildRType("0000101001", rd, rj, rk)
  def xor(rd: Int, rj: Int, rk: Int): BigInt   = buildRType("0000101011", rd, rj, rk)
  def mul_w(rd: Int, rj: Int, rk: Int): BigInt = buildRType("0000111000", rd, rj, rk)
  def srl_w(rd: Int, rj: Int, rk: Int): BigInt = buildRType("0000101111", rd, rj, rk)

  // ... (I-Type part is correct) ...
  def addi_w(rd: Int, rj: Int, imm: Int): BigInt = fromBinary(s"0000001010${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def slti(rd: Int, rj: Int, imm: Int): BigInt   = fromBinary(s"0000001000${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def ori(rd: Int, rj: Int, imm: Int): BigInt    = fromBinary(s"0000001110${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def andi(rd: Int, rj: Int, imm: Int): BigInt   = fromBinary(s"0000001101${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")

  // --- I-Type (2R1I) Shift Instructions ---
  // Format: opcode[31:25]=0 | opcode[24:10] | ...
  private def buildShiftImm(minorOp: String, rd: Int, rj: Int, imm: Int): BigInt = {
    val majorOp = "0000000"
    fromBinary(s"$majorOp$minorOp${toBinary(imm, 5)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }
  
  // Note: Shift Imm opcodes are 15 bits long, including the ui5 field
  // The unique part is inst[24:15]
  def slli_w(rd: Int, rj: Int, imm: Int): BigInt = buildShiftImm(s"0010000001", rd, rj, imm)
  def srli_w(rd: Int, rj: Int, imm: Int): BigInt = buildShiftImm(s"0010001001", rd, rj, imm)


  // ... (LU12I, PCADDU12I, LD/ST are correct) ...
  def lu12i_w(rd: Int, imm: Int): BigInt      = fromBinary(s"0001010${toBinary(imm, 20)}${toBinary(rd, 5)}")
  def pcaddu12i(rd: Int, imm: Int): BigInt = fromBinary(s"0001110${toBinary(imm, 20)}${toBinary(rd, 5)}")
  def ld_w(rd: Int, rj: Int, offset: Int): BigInt = fromBinary(s"0010100010${toBinary(offset, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def st_w(rd: Int, rj: Int, offset: Int): BigInt = fromBinary(s"0010100110${toBinary(offset, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def ld_b(rd: Int, rj: Int, offset: Int): BigInt = fromBinary(s"0010100000${toBinary(offset, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  def st_b(rd: Int, rj: Int, offset: Int): BigInt = fromBinary(s"0010100100${toBinary(offset, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")


  // --- Branch/Jump Instructions ---
  // Format JIRL: opcode[31:26] | offs[15:0] | rj[9:5] | rd[4:0] (Correct format is offs16, rj, rd)
  // Let's re-verify from the PDF
  // JIRL rd, rj, offs -> 010011 | offs[15:0] | rj | rd
  // The builder looks correct based on this format.
  def jirl(rd: Int, rj: Int, offset: Int): BigInt = {
    val wordOffset = offset >> 2
    // The immediate is already a word offset in the encoding. So no shift. But the value is a byte offset. So shift is needed.
    // The spec says "offs" is a signed immediate. Let's assume the builder takes a byte offset.
    fromBinary(s"010011${toBinary(wordOffset, 16)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  // Format B/BL: opcode[31:26] | offs[25:16] | offs[15:0]
  private def buildJump26(opcode: String, offset: Int): BigInt = {
    val wordOffset = offset >> 2
    val imm26_str = toBinary(wordOffset, 26)
    val offs_25_16 = imm26_str.substring(0, 10)
    val offs_15_0  = imm26_str.substring(10)
    fromBinary(s"$opcode$offs_25_16$offs_15_0")
  }
  def b(offset: Int): BigInt  = buildJump26("010100", offset)
  def bl(offset: Int): BigInt = buildJump26("010101", offset)

  // Format BEQ/BNE/BLTU: opcode[31:26] | rj[25:21] | rd[20:16] | offs[15:0]
  // This is the correct format for these branches.
  private def buildBranch16(opcode: String, rj: Int, rd: Int, offset: Int): BigInt = {
    val wordOffset = offset >> 2
    fromBinary(s"$opcode${toBinary(rj, 5)}${toBinary(rd, 5)}${toBinary(wordOffset, 16)}")
  }
  // The provided encoding PDF has a different format! Let's follow the PDF:
  // BEQ rj, rd, offs16 -> 010110 | offs16[15:0] | rj | rd
  // So my `buildBranch16` is wrong.
  private def buildBranch16_pdf(opcode: String, rj: Int, rd: Int, offset: Int): BigInt = {
    val wordOffset = offset >> 2
    fromBinary(s"$opcode${toBinary(wordOffset, 16)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  def beq(rj: Int, rd: Int, offset: Int): BigInt  = buildBranch16_pdf("010110", rj, rd, offset)
  def bne(rj: Int, rd: Int, offset: Int): BigInt  = buildBranch16_pdf("010111", rj, rd, offset)
  def bltu(rj: Int, rd: Int, offset: Int): BigInt = buildBranch16_pdf("011010", rj, rd, offset)

  def nop(): BigInt = addi_w(0, 0, 0)
}


class LA32RInstrBuilderSpec extends AnyFunSuite {

  // Helper to compare BigInt with a hex string for easier debugging
  def assertHex(actual: BigInt, expectedHex: String, message: String): Unit = {
    val expectedBigInt = BigInt(expectedHex.replaceAll(" ", ""), 16)
    assert(actual == expectedBigInt, s"$message - Expected: 0x$expectedHex, Got: 0x${actual.toString(16).toUpperCase}")
  }

  test("nop() should generate the correct ADDI.W r0, r0, 0 instruction") {
    val expectedHex = "02800000"
    val actualInst = LA32RInstrBuilder.nop()
    assertHex(actualInst, expectedHex, "NOP instruction encoding")
  }

  test("addi_w should correctly encode I-Type instructions") {
    val inst1 = LA32RInstrBuilder.addi_w(rd = 5, rj = 10, imm = 1024)
    assertHex(inst1, "02900145", "ADDI.W with positive immediate")

    // This is the one you fixed, confirming the expected value.
    val inst2 = LA32RInstrBuilder.addi_w(rd = 1, rj = 2, imm = -1)
    assertHex(inst2, "02BFFC41", "ADDI.W with negative immediate")
  }

  test("add_w should correctly encode R-Type instructions") {
    // >>> Corrected Expected Value
    val inst = LA32RInstrBuilder.add_w(rd = 3, rj = 1, rk = 2)
    assertHex(inst, "00100823", "ADD.W instruction")
  }
  
  test("b should correctly encode unconditional jumps") {
    // >>> Corrected Expected Value
    val inst1 = LA32RInstrBuilder.b(offset = 16)
    assertHex(inst1, "14000004", "B with positive offset")
  }

  test("beq should correctly encode conditional branches") {
    // >>> Corrected Expected Value
    val inst1 = LA32RInstrBuilder.beq(rj = 1, rd = 2, offset = 32)
    assertHex(inst1, "15840008", "BEQ with positive offset")
  }
}
