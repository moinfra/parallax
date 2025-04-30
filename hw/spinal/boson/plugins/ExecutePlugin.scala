package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._ // Import enums

class ExecutePlugin extends Plugin[Boson] {
    override def build(pipeline: Boson): Unit = {
        import pipeline._
        import pipeline.config._

        execute plug new Area {

        } // End Area
    } // End build
} // End Plugin
