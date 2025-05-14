package boson.demo2.utils

import spinal.core._
import spinal.lib._

object Encoders {
  /**
   * Priority Encoder to One-Hot (LSB-first).
   * Given an input bitmask, returns a one-hot bitmask with only the LSB set.
   * If input is B"0000", output is B"0000".
   * If input is B"0110", output is B"0010".
   */
  def PriorityEncoderOH(inBits: Bits): Bits = {
    val uInput = U(inBits)
    val lsbIsolator = U(~inBits) + U(1) 
    (uInput & lsbIsolator).asBits
  }

  /**
   * Priority Encoder to One-Hot (MSB-first).
   * Given an input bitmask, returns a one-hot bitmask with only the MSB set.
   * If input is B"0000", output is B"0000".
   * If input is B"0110", output is B"0100".
   */
  def PriorityEncoderMSBOH(inBits: Bits): Bits = {
    val reversedIn = inBits.reversed
    val lsbOHOfReversed = PriorityEncoderOH(reversedIn)
    lsbOHOfReversed.reversed
  }
}
