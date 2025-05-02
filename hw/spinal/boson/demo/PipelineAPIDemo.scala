package boson.demo

import spinal.core._

import spinal.lib.pipeline.Pipeline
import boson.utilities.Plugin
import boson.utilities.DataBase
import boson.utilities.NaxScope
import boson.utilities.Framework
import scala.collection.mutable.ArrayBuffer
import spinal.lib.pipeline.Connection.DIRECT
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline.Stageable
import spinal.lib.pipeline.Connection
import spinal.core.fiber.Lock

import spinal.core.fiber._
trait LockedService {
  def retain()
  def release()
}

trait LockedImpl extends LockedService {
  val lock = Lock()
  override def retain() = lock.retain()
  override def release() = lock.release()
}

class Demoware(val plugins: Seq[Plugin]) extends Component with LockedImpl {
  val database = new DataBase
  val framework = NaxScope(database) on new Framework(plugins) // Will run the generation asynchronously
}

object Gen extends App {
  val spinalConfig = SpinalConfig(inlineRom = true)
  val report = spinalConfig.generateVerilog(
    new Demoware(
      Seq(
        new DemoPipelinePlugin,
        new Stage1LogicPlugin,
        new Stage2LogicPlugin,
        new Stage3LogicPlugin
      )
    )
  )

  val nax = report.toplevel
}

object GenSim extends App {
  import spinal.core.sim._

  SimConfig.withWave
    .compile(
      new Demoware(
        Seq(
          new DemoPipelinePlugin,
          new Stage1LogicPlugin,
          new Stage2LogicPlugin,
          new Stage3LogicPlugin
        )
      )
    )
    .doSim(10 * 10) { dut =>
      dut.clockDomain.forkStimulus(period = 10) // 10ns clock period
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
    }
}

object DemoPipelinePlugin extends AreaObject {
  val STAGE1_OUT = Stageable(UInt(8 bits))
  val STAGE2_OUT = Stageable(UInt(8 bits))
  val STAGE3_OUT = Stageable(UInt(8 bits))

}

class DemoPipelinePlugin extends Plugin {
  val pipeline = create early new Pipeline {
    val stage1 = newStage()
    val stage2 = newStage()
    val stage3 = newStage()

    connect(stage1, stage2)(DIRECT())
    connect(stage2, stage3)(M2S())
  }

  pipeline.setCompositeName(this)

  val builder = create late new Area {
    pipeline.build()
  }
}

object DemoPipeline extends AreaObject {
  val STAGE1_OUT = Stageable(UInt(8 bits))
  val STAGE2_OUT = Stageable(UInt(8 bits))
  val STAGE3_OUT = Stageable(UInt(8 bits))

}

class Stage1LogicPlugin extends Plugin {
  val setup = create early new Area {
    val demoPipeline = getService[DemoPipelinePlugin]

  }
  val logic = create late new Area {
    val stage = setup.demoPipeline.pipeline.stage1;
    val reg = Reg(UInt(8 bits)) init(0)
    reg := reg + 0x02;
    val out = reg;
    stage(DemoPipeline.STAGE1_OUT) := out;
    report(L"Stage1LogicPlugin: out = $out")
  }

}

class Stage2LogicPlugin extends Plugin {
  val setup = create early new Area {
    val demoPipeline = getService[DemoPipelinePlugin]

  }
  val logic = create late new Area {
    val prev = setup.demoPipeline.pipeline.stage1
    val stage = setup.demoPipeline.pipeline.stage2
    val out = 0x01 + prev(DemoPipeline.STAGE1_OUT)
    stage(DemoPipeline.STAGE2_OUT) := out;
    report(L"Stage2LogicPlugin: out = $out")
  }
}

class Stage3LogicPlugin extends Plugin {
  val setup = create early new Area {
    val demoPipeline = getService[DemoPipelinePlugin]

  }
  val logic = create late new Area {
    val prev = setup.demoPipeline.pipeline.stage2
    val stage = setup.demoPipeline.pipeline.stage3
    val out = 0x01 + prev(DemoPipeline.STAGE2_OUT)
    stage(DemoPipeline.STAGE3_OUT) := out;
    report(L"Stage3LogicPlugin: out = $out\n\n")
  }
}
