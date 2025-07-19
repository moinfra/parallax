package parallax.components.execute

import spinal.core._
import spinal.lib._
import parallax.utilities.ParallaxSim

case class Multiplier(
  aWidth: Int = 32,
  bWidth: Int = 32,
  pWidth: Int = 64,
  pipelineStages: Int = 6
) extends Component {

  val io = new Bundle {
    val A = in(SInt(aWidth bits)) // Multiplicand A
    val B = in(SInt(bWidth bits)) // Multiplier B
    val P = out(SInt(pWidth bits)) // Product P
  }

  val product = (io.A * io.B).resize(pWidth)

  // Pipeline the result for the specified number of stages.
  // The Delay function inserts registers that are clocked by the component's implicit clock domain.
  val pipelinedProduct = Delay(product, cycleCount = pipelineStages)

  io.P := pipelinedProduct

  when(True) {
    ParallaxSim.debug(Seq(
      L"Multiplier: A=${io.A}, B=${io.B}, product=${product}, P=${io.P}"
    ))
  }
}
