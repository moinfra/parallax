package boson.demo2.fetch

import spinal.core._
import spinal.core.fiber.{Handle, Lock} 
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.misc.pipeline.CtrlLink 
import spinal.lib.StreamArbiterFactory
import spinal.lib.pipeline.Stage
import spinal.lib.pipeline.Pipeline
import spinal.lib.fsm._ 

import scala.collection.mutable.ArrayBuffer
import boson.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import boson.utilities.LockedImpl
import boson.demo2.frontend.FrontendPipelineData
import boson.demo2.common._

class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val fetch0 = newStage()
    val fetch1 = newStage()
    connect(fetch0, fetch1)(M2S())
  }
  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.fetch0
  def exitStage: Stage = pipeline.fetch1
}

class DemoRom extends AreaObject {
  val initContent = Seq(
    B(0x00a00093L, 32 bits), // pc=0 : addi x1, x0, 10
    B(0x00108133L, 32 bits), // pc=4 : add  x2, x1, x1
    B(0x022081b3L, 32 bits), // pc=8 : mul  x3, x1, x2
    B(0x00000213L, 32 bits), // pc=C : addi x4, x0, 0
    B(0x00000013L, 32 bits), // pc=10: addi x0, x0, 0 (True NOP)
    B(0x00000013L, 32 bits), // pc=14: addi x0, x0, 0
    B(0x00000013L, 32 bits), // pc=18: addi x0, x0, 0
    B(0x00000013L, 32 bits) // pc=1C: addi x0, x0, 0
  )
  // Instantiate Mem inside a hardware context (will be when DemoRom is instantiated)
  lazy val rom = Mem(Bits(32 bits), initialContent = initContent)
  rom.addAttribute("ram_style", "block")
}

class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val stage = setup.fetchPipeline.pipeline.fetch0
    val romInstance = new DemoRom() // Instantiate DemoRom here
    val rom = romInstance.rom // Access the Mem inside DemoRom

    val pcReg = Reg(UInt(Config.XLEN bits)) init (0)
    val pcPlus4 = pcReg + 4
    val doIncr = False

    when(stage.isFiring) {
      pcReg := pcPlus4
      doIncr := True
    }

    // Ensure address uses enough bits for the ROM size before slicing
    val romAddrWidth = log2Up(rom.wordCount)
    val romAddress = pcReg(romAddrWidth + 1 downto 2) // Address bits for 32-bit instructions
    val instruction = rom.readAsync(romAddress)

    stage(FrontendPipelineData.PC) := pcReg
    stage(FrontendPipelineData.INSTRUCTION) := instruction
    stage(FrontendPipelineData.FETCH_FAULT) := False
  }
}

class Fetch1Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }
  val logic = create late new Area {
    // No specific logic needed, just passes data
  }
}
