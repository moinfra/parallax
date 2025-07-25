package parallax.common

import parallax.utilities.Plugin
import spinal.core._
import spinal.lib._

class PerfCounterPlugin extends Plugin {
    private val cycles = Reg(UInt(32 bits)) init(0)
    private val logic = create late new Area {
        cycles := cycles + 1
    }

    def value = cycles
}

class PerfCounter extends Component {
    val io = new Bundle {
        val value = out(UInt(32 bits))
    }

    def value = io.value

    private val cycles = Reg(UInt(32 bits)) init(0)
    val logic = new Area {
        cycles := cycles + 1
    }
}
