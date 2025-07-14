package parallax.components.display

import spinal.core._
import spinal.lib._

object SevenSegmentDecoder {
  /* 
  segCode[6] -> g
  segCode[5] -> f
  segCode[4] -> a
  segCode[3] -> b
  segCode[2] -> e
  segCode[1] -> d
  segCode[0] -> c
   */

  def commonCathodeMap(value: UInt): Bits = {
    val segCode = Bits(7 bits)
    switch(value) {
      // ........................gfabedc
      is(U"0000") { segCode := B"0111111" } // 0: a,b,c,d,e,f on
      is(U"0001") { segCode := B"0001001" } // 1: b,c on
      is(U"0010") { segCode := B"1011110" } // 2: a,b,g,e,d on
      is(U"0011") { segCode := B"1011011" } // 3: a,b,g,c,d on
      is(U"0100") { segCode := B"1101001" } // 4: f,g,b,c on
      is(U"0101") { segCode := B"1110011" } // 5: a,f,g,c,d on
      is(U"0110") { segCode := B"1110111" } // 6: a,f,g,e,d,c on
      is(U"0111") { segCode := B"0011001" } // 7: a,b,c on
      is(U"1000") { segCode := B"1111111" } // 8: all segments on
      is(U"1001") { segCode := B"1111011" } // 9: a,b,c,d,f,g on
      is(U"1010") { segCode := B"1111101" } // A: a,b,c,e,f,g on
      is(U"1011") { segCode := B"1100111" } // b: c,d,e,f,g on
      is(U"1100") { segCode := B"0110110" } // C: a,d,e,f on
      is(U"1101") { segCode := B"1001111" } // d: b,c,d,e,g on
      is(U"1110") { segCode := B"1110110" } // E: a,d,e,f,g on
      is(U"1111") { segCode := B"1110100" } // F: a,e,f,g on
    } 
    segCode
  }
}

class EightSegmentDisplayController extends Component {
  val io = new Bundle {
    val value = in UInt (8 bits)
    val dp0 = in Bool ()
    val dp1 = in Bool ()
    val dpy0_out = out Bits (8 bits)
    val dpy1_out = out Bits (8 bits)
  }

  val displayArea = new Area {

    val digit0 = io.value(3 downto 0)
    val digit1 = io.value(7 downto 4)

    val seg0 = SevenSegmentDecoder.commonCathodeMap(digit0)
    val seg1 = SevenSegmentDecoder.commonCathodeMap(digit1)

    io.dpy0_out := seg0 ## io.dp0
    io.dpy1_out := seg1 ## io.dp1
  }
}

class BCDEightSegmentDisplayController extends Component {
  val io = new Bundle {
    val value = in UInt (8 bits)
    val dp0 = in Bool () default (False)
    val dp1 = in Bool () default (False)
    val dpy0_out = out Bits (8 bits)
    val dpy1_out = out Bits (8 bits)
  }

  val displayArea = new Area {
    val digit0 = io.value(3 downto 0)
    val digit1 = io.value(7 downto 4)

    val valid_digit0 = Mux(digit0 <= 9, digit0, U(0, 4 bits))
    val valid_digit1 = Mux(digit1 <= 9, digit1, U(0, 4 bits))

    val seg0 = SevenSegmentDecoder.commonCathodeMap(valid_digit0)
    val seg1 = SevenSegmentDecoder.commonCathodeMap(valid_digit1)

    io.dpy0_out := io.dp0 ## seg0
    io.dpy1_out := io.dp1 ## seg1
  }
}
