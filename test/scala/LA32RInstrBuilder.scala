// command: testOnly test.scala.LA32RInstrBuilderSpec
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.math.BigInt

object LA32RInstrBuilder {
  /**
   * Checks if a value fits within a specified number of bits (signed).
   * @param value The integer value to check.
   * @param bits The number of bits available for the signed value.
   * @return True if the value fits, false otherwise.
   */
  private def fitsInSignedBits(value: Int, bits: Int): Boolean = {
    val min = -(BigInt(1) << (bits - 1))
    val max = (BigInt(1) << (bits - 1)) - 1
    value >= min && value <= max
  }

  /**
   * Checks if a value fits within a specified number of bits (unsigned).
   * @param value The integer value to check.
   * @param bits The number of bits available for the unsigned value.
   * @return True if the value fits, false otherwise.
   */
  private def fitsInUnsignedBits(value: Int, bits: Int): Boolean = {
    val max = (BigInt(1) << bits) - 1
    value >= 0 && value <= max
  }

  /** Checks if a register index is valid (0-31). */
  private def requireValidRegister(reg: Int, name: String): Unit = {
    require(reg >= 0 && reg <= 31, s"Register $name must be between 0 and 31, but got $reg")
  }

  private def toBinary(value: BigInt, width: Int): String = {
    val mask = (BigInt(1) << width) - 1
    val maskedValue = value & mask
    val binaryString = maskedValue.toString(2)
    "0" * (width - binaryString.length) + binaryString
  }

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
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    requireValidRegister(rk, "rk")
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

  // --- I-Type (2R1I) Instructions ---
  private def buildIType12S(opcode: String, rd: Int, rj: Int, imm: Int): BigInt = {
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    require(fitsInSignedBits(imm, 12), s"12-bit signed immediate must be between -2048 and 2047, but got $imm")
    fromBinary(s"$opcode${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  private def buildIType12U(opcode: String, rd: Int, rj: Int, imm: Int): BigInt = {
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    require(fitsInUnsignedBits(imm, 12), s"12-bit unsigned immediate must be between 0 and 4095, but got $imm")
    fromBinary(s"$opcode${toBinary(imm, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  def addi_w(rd: Int, rj: Int, imm: Int): BigInt = buildIType12S("0000001010", rd, rj, imm)

  def slti(rd: Int, rj: Int, imm: Int): BigInt   = buildIType12U("0000001000", rd, rj, imm)
  def ori(rd: Int, rj: Int, imm: Int): BigInt    = buildIType12U("0000001110", rd, rj, imm)
  def andi(rd: Int, rj: Int, imm: Int): BigInt   = buildIType12U("0000001101", rd, rj, imm)
  def xori(rd: Int, rj: Int, imm: Int): BigInt   = buildIType12U("0000001111", rd, rj, imm)

  // --- I-Type (2R1I) Shift Instructions ---
  private def buildShiftImm(minorOp: String, rd: Int, rj: Int, imm: Int): BigInt = {
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    require(fitsInUnsignedBits(imm, 5), s"5-bit unsigned shift immediate must be between 0 and 31, but got $imm")
    val majorOp = "0000000"
    fromBinary(s"$majorOp$minorOp${toBinary(imm, 5)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }
  
  def slli_w(rd: Int, rj: Int, imm: Int): BigInt = buildShiftImm(s"0010000001", rd, rj, imm)
  def srli_w(rd: Int, rj: Int, imm: Int): BigInt = buildShiftImm(s"0010001001", rd, rj, imm)

  // --- I-Type (LU12I, PCADDU12I) ---
  def lu12i_w(rd: Int, imm: Int): BigInt = {
    requireValidRegister(rd, "rd")
    // NOTE：指令集手册上写了 si20，但实际上作为无符号数处理
    require(fitsInUnsignedBits(imm, 20), s"20-bit unsigned immediate for LU12I.W must be between 0 and 1048575, but got $imm")
    fromBinary(s"0001010${toBinary(imm, 20)}${toBinary(rd, 5)}")
  }
  def pcaddu12i(rd: Int, imm: Int): BigInt = {
    requireValidRegister(rd, "rd")
    // NOTE：指令集手册上写了 si20，但实际上作为无符号数处理
    require(fitsInUnsignedBits(imm, 20), s"20-bit unsigned immediate for PCADDU12I must be between 0 and 1048575, but got $imm")
    fromBinary(s"0001110${toBinary(imm, 20)}${toBinary(rd, 5)}")
  }

  // --- I-Type (LD/ST) ---
  private def buildLoadStore(opcode: String, rd: Int, rj: Int, offset: Int): BigInt = {
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    require(fitsInSignedBits(offset, 12), s"12-bit signed offset for LD/ST must be between -2048 and 2047, but got $offset")
    fromBinary(s"$opcode${toBinary(offset, 12)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }
  def ld_w(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010100010", rd, rj, offset)
  def st_w(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010100110", rd, rj, offset)
  def ld_b(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010100000", rd, rj, offset)
  def st_b(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010100100", rd, rj, offset)


  def ld_bu(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010101000", rd, rj, offset)
  def ld_h(rd: Int, rj: Int, offset: Int): BigInt  = buildLoadStore("0010100001", rd, rj, offset)
  def ld_hu(rd: Int, rj: Int, offset: Int): BigInt = buildLoadStore("0010101001", rd, rj, offset)
  def st_h(rd: Int, rj: Int, offset: Int): BigInt  = buildLoadStore("0010100101", rd, rj, offset)

  // --- Branch/Jump Instructions ---
/**
   * 跳转并链接到寄存器。
   * @param rd 目标寄存器，用于存放 PC + 4。
   * @param rj 基址寄存器。
   * @param offset 字节偏移量。必须是4的倍数，且在18位有符号数范围内。
   *               范围: [-131072, 131068]
   * @return 32位指令编码。
   */
  def jirl(rd: Int, rj: Int, offset: Int): BigInt = {
    requireValidRegister(rd, "rd")
    requireValidRegister(rj, "rj")
    
    require(offset % 4 == 0, s"JIRL offset must be 4-byte aligned, but got $offset")

    val offs16 = offset >> 2

    require(fitsInSignedBits(offs16, 16), s"JIRL offset is out of range. It must be between -131072 and 131068, but got $offset (which corresponds to a 16-bit immediate of $offs16)")

    val opcode = "010011"
    fromBinary(s"$opcode${toBinary(offs16, 16)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  private def buildJump26(opcode: String, offset: Int): BigInt = {
    require(fitsInSignedBits(offset, 28), s"B/BL offset must be a 26-bit signed value shifted by 2 (range [-134217728, 134217724]), but got $offset")
    require(offset % 4 == 0, s"B/BL offset must be 4-byte aligned, but got $offset")
    val wordOffset = offset >> 2
    val imm26_str = toBinary(wordOffset, 26)
    val offs_25_16 = imm26_str.substring(0, 10)
    val offs_15_0  = imm26_str.substring(10)
    fromBinary(s"$opcode$offs_15_0$offs_25_16")
  }

  // NOTE: byte offset
  def b(offset: Int): BigInt  = buildJump26("010100", offset)
  def bl(offset: Int): BigInt = buildJump26("010101", offset)

  private def buildBranch16(opcode: String, rj: Int, rd: Int, offset: Int): BigInt = {
    requireValidRegister(rj, "rj")
    requireValidRegister(rd, "rd")
    require(fitsInSignedBits(offset, 18), s"Branch offset must be a 16-bit signed value shifted by 2 (range [-131072, 131068]), but got $offset")
    require(offset % 4 == 0, s"Branch offset must be 4-byte aligned, but got $offset")
    val wordOffset = offset >> 2
    fromBinary(s"$opcode${toBinary(wordOffset, 16)}${toBinary(rj, 5)}${toBinary(rd, 5)}")
  }

  def beq(rj: Int, rd: Int, offset: Int): BigInt  = buildBranch16("010110", rj, rd, offset)
  def bne(rj: Int, rd: Int, offset: Int): BigInt  = buildBranch16("010111", rj, rd, offset)
  def bltu(rj: Int, rd: Int, offset: Int): BigInt = buildBranch16("011010", rj, rd, offset)

  // --- Special Instructions ---
  def nop(): BigInt = addi_w(0, 0, 0)
  
  def idle(level: Int = 0): BigInt = {
    require(fitsInUnsignedBits(level, 15), s"15-bit unsigned level for IDLE must be between 0 and 32767, but got $level")
    val opcode = "0000011"
    val fixed_bits = "0010010001"
    fromBinary(s"$opcode$fixed_bits${toBinary(level, 15)}")
  }
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
  
  test("idle should correctly encode IDLE instruction") {
    // IDLE instruction: 0000011 00100 10001 level[14:0]
    // Binary: 0000011 0010010001 000000000000000 = 00000110010010001000000000000000
    val inst1 = LA32RInstrBuilder.idle(level = 0)
    assertHex(inst1, "06488000", "IDLE with level 0")
    
    // With level=1: 0000011 0010010001 000000000000001 = 00000110010010001000000000000001
    val inst2 = LA32RInstrBuilder.idle(level = 1)
    assertHex(inst2, "06488001", "IDLE with level 1")
  }
}
