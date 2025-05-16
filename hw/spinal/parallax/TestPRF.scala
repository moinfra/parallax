package parallax

import parallax.utilities.Plugin
import spinal.core._
import spinal.lib._

class TestPRF extends Plugin {
  val setup = create early new Area {
    val regfile = getService[PhysicalRegFileService]
    val readPort = regfile.newReadPort()
    val writePort = regfile.newWritePort()
  }

  val logic = create late new Area {
    setup.readPort.address := 0
  }
}
