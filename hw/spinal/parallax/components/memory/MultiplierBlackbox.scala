package parallax.components.memory

import spinal.core._
import spinal.lib._

case class MultiplierBlackbox(
  aWidth: Int = 32,
  bWidth: Int = 32,
  pWidth: Int = 64,
  pipelineStages: Int = 6
) extends BlackBox {

  setBlackBoxName("my_multiplier")

  val io = new Bundle {
    val CLK = if(pipelineStages > 0) in(Bool()) else null
    val RST = if(pipelineStages > 0) in(Bool()) else null

    val A = in(SInt(aWidth bits)) // Multiplicand A
    val B = in(SInt(bWidth bits)) // Multiplier B
    val P = out(SInt(pWidth bits)) // Product P
  }

  noIoPrefix()
}