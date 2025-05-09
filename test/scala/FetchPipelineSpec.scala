package boson.test.scala

import boson.demo2.{PhysicalRegFilePlugin, PhysicalRegFileService}
import boson.demo2.common.{Config}
import boson.demo2.components.icache.SimpleICacheConfig
import boson.demo2.fetch.FetchPipeline
import boson.utilities.{Framework, Plugin, ProjectScope, Service, DataBase}
import org.scalatest.funsuite.AnyFunSuite // Keep this import
import spinal.core.{HIGH, SpinalConfig, _}
import spinal.core.sim._
import spinal.lib.Stream
import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite
import boson.demo2.components.memory._
import boson.demo2.fetch.Fetch0Plugin
import boson.demo2.fetch.Fetch1Plugin

class FetchPipelineSpec extends SpinalSimFunSuite {

  onlyVerilator

  def simConfig = SimConfig.withWave.withVcdWave

  class TestPlugin extends Plugin {
    val setup = create early new Area {
      val regfile = getService[PhysicalRegFileService]
      val readPort = regfile.newReadPort().simPublic()
      val writePort = regfile.newWritePort().simPublic()
    }
  }
  var useICache = false
  def memBusConfig = GenericMemoryBusConfig(addressWidth = Config.XLEN, dataWidth = Config.XLEN)
  def icacheConfig = if (useICache) {
    SimpleICacheConfig(cacheSize = 2048, addressWidth = Config.XLEN, dataWidth = Config.XLEN, bytePerLine = 16)
  } else {
    SimpleICacheConfig(addressWidth = Config.XLEN, dataWidth = Config.XLEN)
  }
  def ifuConfig = InstructionFetchUnitConfig(
    useICache = useICache,
    enableFlush = false,
    cpuAddressWidth = Config.XLEN,
    cpuDataWidth = Config.XLEN,
    memBusConfig = memBusConfig,
    icacheConfig = icacheConfig
  )

  class FetchPipelineTestBench extends Component {
    val database = new DataBase
    val framework = ProjectScope(database) on new Framework(
      Seq(
        new SimulatedSimpleMemoryPlugin(
          memBusConfig,
        ),
        new Fetch0Plugin(),
        new Fetch1Plugin(ifuConfig),
        new FetchPipeline()
      )
    )

    def testPlugin = framework.getService[TestPlugin]
    def readPort = testPlugin.setup.readPort
    def writePort = testPlugin.setup.writePort
  }

  test("Write to a reg and read it back") {
    simConfig
      .compile(new FetchPipelineTestBench())
      .doSim { tb =>
        {
          tb.clockDomain.forkStimulus(period = 10)

        }
      }
  }

}
