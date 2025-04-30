package boson.plugins

import spinal.core._
import spinal.lib._
import boson._

class RegisterFilePlugin(registerCount: Int, dataWidth: Int) extends Plugin[Boson] {

  var regFile: Vec[Bits] = null

  override def setup(pipeline: Boson): Unit = {
    // Physical register file instance
    regFile = Vec.fill(registerCount)(Reg(Bits(dataWidth bits)) init (0))
  }

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    // --- Register Read Logic (in Issue/RegRead Stage) ---
    issueRegRead plug new Area {
     
    } // End of issueRegRead Area

    // --- Writeback Data Selection and Physical Write Logic (in Writeback Stage) ---
    writeback plug new Area {
      import writeback._

    } // End of writeback Area
  } // End of build method
} // End of class RegisterFilePlugin
