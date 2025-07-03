// command: testOnly test.scala.LA32RInstrBuilderSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.math.BigInt

object LA32RInstrBuilder {

  private def toBinary(value: Int, width: Int): String = {
    val bigValue = BigInt(value)
    val mask = (BigInt(1) << width) - 1
    val maskedValue = bigValue & mask
    val binaryString = maskedValue.toString(2)
    "0" * (width - binaryString.length) + binaryString
  }

  private def fromBinary(binStr: String): BigInt = {
    val cleanStr = binStr.replace(" ", "")
    if (cleanStr.length != 32) {
      throw new IllegalArgumentException(s"Binary string must be 32 bits long, but got ${cleanStr.length} for '$binStr'")
    }
    BigInt(cleanStr, 2)
  }

  // --- I-Type Instructions ---
  // Format: opcode[31:22] | si12[21:10] | rj[9:5] | rd[4:0]
  def addi_w(rd: Int, rj: Int, imm: Int): BigInt = {
    val opcode = "0000001010"
    fromBinary(s"$opcode${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  // --- R-Type Instructions ---
  // Format: opcode[31:15] | rk[14:10] | rj[9:5] | rd[4:0]
  def add_w(rd: Int, rj: Int, rk: Int): BigInt = {
    val opcode = "00000000000100000"
    fromBinary(s"$opcode${toBinary(rk, 5)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  // --- B-Type (Unconditional Direct Jump) ---
  // Format: opcode[31:26] | offs[25:16] | offs[15:0]
  def b(offset: Int): BigInt = {
    val opcode = "010100"
    val wordOffset = offset >> 2
    val imm26 = wordOffset & 0x3FFFFFF
    val imm15_0 = toBinary((imm26 >> 0), 16)
    val imm25_16 = toBinary((imm26 >> 16), 10)
    fromBinary(s"$opcode$imm25_16$imm15_0")
  }

  // --- BR-Type (Conditional Branch) ---
  // >>> FIX: The field order is opcode, rj, rd, immediate <<<
  // Format: opcode[31:26] | rj[25:21] | rd[20:16] | immediate[15:0]
def beq(rj: Int, rd: Int, offset: Int): BigInt = {
    val opcode = "010110"
    val wordOffset = offset >> 2
    
    val imm16 = wordOffset & 0xFFFF
    fromBinary(s"$opcode${toBinary(rj, 5)}${toBinary(rd, 5)}${toBinary(imm16, 16)}")
  }


  // NOP is ADDI.W r0, r0, 0
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
