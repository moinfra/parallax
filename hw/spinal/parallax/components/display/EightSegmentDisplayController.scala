// src/main/scala/parallax/components/display/SevenSegmentDecoder.scala
package parallax.components.display

import spinal.core._
import spinal.lib._

object SevenSegmentDecoder {
  // Common Cathode (共阴极) - Segment is active HIGH
  // Segments: a, b, c, d, e, f, g (7 bits, dp excluded)
  // Bit assignment: bit 6=a, bit 5=b, bit 4=c, bit 3=d, bit 2=e, bit 1=f, bit 0=g
  
  def commonCathodeMap(value: UInt): Bits = {
    val segCode = Bits(7 bits)
    switch(value) {
      is(U"0000") { segCode := B"0111111" } // 0: a,b,c,d,e,f on
      is(U"0001") { segCode := B"0000110" } // 1: b,c on
      is(U"0010") { segCode := B"1011011" } // 2: a,b,g,e,d on
      is(U"0011") { segCode := B"1001111" } // 3: a,b,g,c,d on
      is(U"0100") { segCode := B"1100110" } // 4: f,g,b,c on
      is(U"0101") { segCode := B"1101101" } // 5: a,f,g,c,d on
      is(U"0110") { segCode := B"1111101" } // 6: a,f,g,e,d,c on
      is(U"0111") { segCode := B"0000111" } // 7: a,b,c on
      is(U"1000") { segCode := B"1111111" } // 8: all segments on
      is(U"1001") { segCode := B"1101111" } // 9: a,b,c,d,f,g on
      is(U"1010") { segCode := B"1110111" } // A: a,b,c,e,f,g on
      is(U"1011") { segCode := B"1111100" } // b: c,d,e,f,g on
      is(U"1100") { segCode := B"0111001" } // C: a,d,e,f on
      is(U"1101") { segCode := B"1011110" } // d: b,c,d,e,g on
      is(U"1110") { segCode := B"1111001" } // E: a,d,e,f,g on
      is(U"1111") { segCode := B"1110001" } // F: a,e,f,g on
      // default     { segCode := B"0000000" } // blank for invalid values
    }
    segCode
  }
}

class EightSegmentDisplayController extends Component {
  val io = new Bundle {
    val value = in UInt(8 bits)      // 8位输入值 (0x00 - 0xFF)
    val dp0 = in Bool()              // 低位小数点控制
    val dp1 = in Bool()              // 高位小数点控制
    val dpy0_out = out Bits(8 bits)  // 低位输出 (个位): [dp, a, b, c, d, e, f, g]
    val dpy1_out = out Bits(8 bits)  // 高位输出 (十位): [dp, a, b, c, d, e, f, g]
  }

  val displayArea = new Area {
    // Extract digits
    val digit0 = io.value(3 downto 0) // Low nibble (0-F)
    val digit1 = io.value(7 downto 4) // High nibble (0-F)

    // Decode digits to segment codes (7 bits each)
    val seg0 = SevenSegmentDecoder.commonCathodeMap(digit0)
    val seg1 = SevenSegmentDecoder.commonCathodeMap(digit1)

    // Combine segments with decimal points
    io.dpy0_out := io.dp0 ## seg0  // [dp, a, b, c, d, e, f, g]
    io.dpy1_out := io.dp1 ## seg1  // [dp, a, b, c, d, e, f, g]
  }
}

// 如果需要BCD模式的版本，可以添加这个类
class BCDEightSegmentDisplayController extends Component {
  val io = new Bundle {
    val value = in UInt(8 bits)  // BCD输入：高4位是十位，低4位是个位
    val dp0 = in Bool() default(False)              // 低位小数点控制
    val dp1 = in Bool() default(False)             // 高位小数点控制
    val dpy0_out = out Bits(8 bits)  // 低位输出
    val dpy1_out = out Bits(8 bits)  // 高位输出
  }

  val displayArea = new Area {
    val digit0 = io.value(3 downto 0) // 个位
    val digit1 = io.value(7 downto 4) // 十位

    // 限制BCD输入范围 (0-9)
    val valid_digit0 = Mux(digit0 <= 9, digit0, U(0, 4 bits))
    val valid_digit1 = Mux(digit1 <= 9, digit1, U(0, 4 bits))

    val seg0 = SevenSegmentDecoder.commonCathodeMap(valid_digit0)
    val seg1 = SevenSegmentDecoder.commonCathodeMap(valid_digit1)

    io.dpy0_out := io.dp0 ## seg0
    io.dpy1_out := io.dp1 ## seg1
  }
}
