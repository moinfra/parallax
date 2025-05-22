package parallax

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
import parallax.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import parallax.utilities.LockedImpl

class DemoCPU(val plugins: Seq[Plugin]) extends Component {
  val database = new DataBase
  val framework = ProjectScope(database) on new Framework(plugins)
}

object DemoCPUGen extends App {

  def getPlugins: Seq[Plugin] = {
    // val numAlus = 2
    // val numMul = 1
    // val totalEuCount = numAlus + numMul

    val plugins = ArrayBuffer[Plugin]()

    plugins += new PhysicalRegFilePlugin(
      numPhysRegs = 32,
      dataWidth = 32 bits,
    )
    // plugins += new TestPRF()
    // plugins += new BypassPlugin()
    // plugins += new FetchPipeline()
    // plugins += new FrontendPipeline()
    // plugins += new FetchFrontendBridge()
    // plugins += new Fetch0Plugin()
    // plugins += new Fetch1Plugin()
    // plugins += new DecodePlugin()
    // plugins += new RenamePlugin()
    // plugins += new DispatchPlugin()
    // plugins += new IssueQueuesPlugin(numWakeupSources = totalEuCount)

    // var currentEuId = 0
    // var currentWakeupPortId = 0

    // for (i <- 0 until numAlus) {
    //   plugins += new AluExecutionUnit(euId = currentEuId, wakeupPortId = currentWakeupPortId)
    //   currentEuId += 1; currentWakeupPortId += 1
    // }
    // for (i <- 0 until numMul) {
    //   plugins += new MulDivExecutionUnit(euId = currentEuId, wakeupPortId = currentWakeupPortId)
    //   currentEuId += 1; currentWakeupPortId += 1
    // }

    // plugins += new CommitPlugin(executionUnitCount = totalEuCount)
    plugins.toSeq
  }

  val spinalConfig = SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    targetDirectory = "rtl/parallax/demo"
  )

  spinalConfig.generateVerilog(new DemoCPU(plugins = DemoCPUGen.getPlugins))
  println("Verilog Generation DONE")
}

object DemoCPUGenSim extends App {
  import spinal.core.sim._

  SimConfig.withWave
    .compile(new DemoCPU(plugins = DemoCPUGen.getPlugins))
    .doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling(200)
      println("Simulation DONE")
    }
}
