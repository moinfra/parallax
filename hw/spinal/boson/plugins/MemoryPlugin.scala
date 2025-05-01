package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._
import boson.interfaces._ // Import internal interfaces
import boson.components.AxiMemoryBusComponent
// Required for state machine if added later for AXI handling
// import spinal.lib.fsm._

class MemoryPlugin() extends Plugin[BosonArch] {

  override def setup(pipeline: BosonArch): Unit = {}

  override def build(pipeline: BosonArch): Unit = {
    import pipeline._
    import pipeline.config._

    memory plug new Area {} // End Area
  } // End build
} // End class
