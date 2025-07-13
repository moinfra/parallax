// src/main/scala/parallax/components/display/SevenSegmentDecoder.scala
package parallax.components.display

import spinal.core._
import spinal.lib._

object SevenSegmentDecoder {
  // Common Anode (共阳极) - Segment is active LOW
  // Common Cathode (共阴极) - Segment is active HIGH (您提供的约束文件是共阴极)

  // Segments: a, b, c, d, e, f, g, dp (dp is bit 7)
  // For common cathode, 1 means ON, 0 means OFF
  def commonCathodeMap(value: UInt): Bits = {
    val segCode = Bits(8 bits)
    switch(value) {
      is(U"0000") { segCode := B"00111111" } // 0
      is(U"0001") { segCode := B"00000110" } // 1
      is(U"0010") { segCode := B"01011011" } // 2
      is(U"0011") { segCode := B"01001111" } // 3
      is(U"0100") { segCode := B"01100110" } // 4
      is(U"0101") { segCode := B"01101101" } // 5
      is(U"0110") { segCode := B"01111101" } // 6
      is(U"0111") { segCode := B"00000111" } // 7
      is(U"1000") { segCode := B"01111111" } // 8
      is(U"1001") { segCode := B"01101111" } // 9
      is(U"1010") { segCode := B"01110111" } // A
      is(U"1011") { segCode := B"01111100" } // b
      is(U"1100") { segCode := B"00111001" } // C
      is(U"1101") { segCode := B"01011110" } // d
      is(U"1110") { segCode := B"01111001" } // E
      is(U"1111") { segCode := B"01110001" } // F
    }
    segCode(7) := False // Keep decimal point off by default
    segCode
  }
}

class SevenSegmentDisplayController extends Component {
  val io = new Bundle {
    val value = in UInt(8 bits) // 8位输入值 (0x00 - 0xFF)
    val dpy0_out = out Bits(8 bits) // 低位 (个位)
    val dpy1_out = out Bits(8 bits) // 高位 (十位)
  }

  // Clock domain for the controller (assuming it's in the main clock domain)
  val displayArea = new Area {
    // Extract digits
    val digit0 = io.value(3 downto 0) // Low nibble (0-F)
    val digit1 = io.value(7 downto 4) // High nibble (0-F)

    // Decode digits to segment codes
    io.dpy0_out := SevenSegmentDecoder.commonCathodeMap(digit0)
    io.dpy1_out := SevenSegmentDecoder.commonCathodeMap(digit1)
  }
}
