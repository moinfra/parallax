package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._

class RandomReplacerComponent(p: DataCacheParameters) extends Component {
    val io = new Bundle {
        val way = out(UInt(log2Up(p.wayCount) bits))
        val update = in(Bool()) // Request to update LFSR
    }
    if (p.wayCount > 1) {
        val lfsrWidth = log2Up(p.wayCount) max 1
        val lfsr = Reg(UInt(lfsrWidth bits)) init(1) // Ensure non-zero init for LFSR
        when(io.update) {
            // A simple LFSR, taps might need adjustment for good randomness over small N
            val newBit = lfsr.xorR
            lfsr := (lfsr |<< 1) | newBit.asUInt.resized
        }
        io.way := lfsr(log2Up(p.wayCount)-1 downto 0) // Ensure output matches wayCount width
    } else {
        io.way := 0
    }
}
