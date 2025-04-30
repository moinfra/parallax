package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._
import boson.interfaces._ // Import internal interfaces
import boson.components.AxiMemoryBusComponent
// Required for state machine if added later for AXI handling
// import spinal.lib.fsm._

class MemoryPlugin() extends Plugin[Boson] {

  override def setup(pipeline: Boson): Unit = {}

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    memory plug new Area {} // End Area
  } // End build
} // End class
