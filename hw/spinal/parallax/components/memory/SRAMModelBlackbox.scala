package parallax.components.memory

import spinal.core._
import spinal.lib.io.TriState

class SRAMModelBlackbox extends BlackBox {
  setBlackBoxName("sram_model")

  val io = new Bundle {
    val Address = in UInt (20 bits)
    val DataIO = inout(Analog(Bits(16 bits)))
    val OE_n = in Bool()
    val CE_n = in Bool()
    val WE_n = in Bool()
    val LB_n = in Bool()
    val UB_n = in Bool()
  }
  noIoPrefix()
  addRTLPath("./rtl/sram_model.v")

}
