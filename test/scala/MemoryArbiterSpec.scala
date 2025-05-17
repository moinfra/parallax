// test/scala/parallax/components/memory/MemoryArbiterSpec.scala
package parallax.test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}
import spinal.lib._

import parallax.components.memory._
import parallax.utilities.{Plugin, Service, Framework, DataBase, ProjectScope}

// --- Demo Master Plugin for Testing ---
object DemoMemoryMasterPlugin {
  // Define TestIo as a case class for clarity if used in Vecs directly or as IOs
  case class TestIoConfig(numOperations: Int) // To parameterize TestIo if needed

  // This Bundle is for simulation control/observation of the master plugin
  class TestIo(val cfg: TestIoConfig) extends Bundle {
    val finished = out Bool ()
    val operationsSent = out UInt (log2Up(cfg.numOperations + 1) bits)
    val responsesReceived = out UInt (log2Up(cfg.numOperations + 1) bits)
  }
}

class DemoMemoryMasterPlugin(
    val masterName: String,
    val busConfig: GenericMemoryBusConfig, // Bus config this master will use
    val numOperations: Int,
    val startAddress: Long,
    val addressStride: Long = 4,
    val writeProbability: Double = 0.5
) extends Plugin {

  // Instantiate TestIo using its config
  val testIoCfg = DemoMemoryMasterPlugin.TestIoConfig(numOperations)
  val testIo = new DemoMemoryMasterPlugin.TestIo(testIoCfg)
  // testIo will be an internal signal bundle within this plugin Area.
  // It can be exposed via simPublic() on the plugin instance if needed by the testbench.

  val logic = create late new Area {
    val arbiterService = getService[MemoryArbiterService]
    val memBus = arbiterService.newMemoryMasterPort(busConfig, masterName) // Get a port to the arbiter

    val finishedReg = RegInit(False).setName(s"${masterName}_finishedReg")
    testIo.finished := finishedReg

    val operationsSentCounter = Reg(UInt(log2Up(numOperations + 1) bits)) init (0)
    val responsesReceivedCounter = Reg(UInt(log2Up(numOperations + 1) bits)) init (0)
    testIo.operationsSent := operationsSentCounter
    testIo.responsesReceived := responsesReceivedCounter

    val currentAddress = Reg(UInt(busConfig.addressWidth)) init (U(startAddress, busConfig.addressWidth))
    // Seed Random with masterName's hashcode for deterministic but different behavior per master.
    val randomGen = new Random(masterName.hashCode())

    // Drive memBus.cmd
    memBus.cmd.valid := False // Default
    memBus.cmd.payload.opcode := GenericMemoryBusOpcode.OP_READ // Default
    memBus.cmd.payload.address := currentAddress
    memBus.cmd.payload.writeData := B(0)
    memBus.cmd.payload.writeByteEnables := B(0)

    when(!finishedReg && operationsSentCounter < numOperations) {
      memBus.cmd.valid := True // Attempt to send if operations are pending
      if (randomGen.nextDouble() < writeProbability) {
        memBus.cmd.payload.opcode := GenericMemoryBusOpcode.OP_WRITE
        memBus.cmd.payload.writeData := BigInt(busConfig.dataWidth.value, randomGen) // Random data
        memBus.cmd.payload.writeByteEnables.setAll() // Enable all bytes for simplicity
      } else {
        memBus.cmd.payload.opcode := GenericMemoryBusOpcode.OP_READ
        // writeData and byteEnables are don't care for read by GenericMemoryBus definition
      }
    }

    when(memBus.cmd.fire) {
      report(
        L"Master [$masterName]: Sent CMD op=${memBus.cmd.payload.opcode} addr=${memBus.cmd.payload.address} data=${memBus.cmd.payload.writeData} (Sent ops: ${operationsSentCounter + U(1)})"
      )
      operationsSentCounter := operationsSentCounter + 1
      currentAddress := currentAddress + U(addressStride)
    }

    // Handle memBus.rsp
    memBus.rsp.ready := True // Always ready to accept responses in this simple master

    when(memBus.rsp.fire) {
      responsesReceivedCounter := responsesReceivedCounter + 1
      report(
        L"Master [$masterName]: Received RSP data=${memBus.rsp.payload.readData} err=${memBus.rsp.payload.error} (Rcvd ops: ${responsesReceivedCounter + U(1)})"
      )
    }

    // Determine when finished
    when(responsesReceivedCounter === numOperations && operationsSentCounter === numOperations) {
      finishedReg := True
      report(L"Master [$masterName]: All operations completed.")
    }
  }
}

// --- TestBench for MemoryArbiter ---
class MemoryArbiterTestBench(
    val numMasters: Int,
    val operationsPerMaster: Int,
    val mainMemBusCfg: GenericMemoryBusConfig,
    val simMemInternalCfg: SimulatedMemoryConfig
) extends Component {

  val database = new DataBase
  val pluginsList = mutable.ArrayBuffer[Plugin]() // Renamed to avoid conflict

  // 1. Arbiter Plugin
  pluginsList += new MemoryArbiterPlugin(
    mainBusConfigOverride = Some(mainMemBusCfg)
  )

  // 2. Simulated Memory Plugin
  pluginsList += new SimulatedMainMemoryPlugin(
    simMemConfig = simMemInternalCfg,
    busConfig = mainMemBusCfg // Memory's bus must match arbiter's output bus
  )

  // 3. Demo Master Plugins
  val demoMasterPlugins = mutable.ArrayBuffer[DemoMemoryMasterPlugin]()
  for (i <- 0 until numMasters) {
    val masterBusCfg = mainMemBusCfg // For this test, masters use the same config as the main bus
    val master = new DemoMemoryMasterPlugin(
      masterName = s"Master$i",
      busConfig = masterBusCfg,
      numOperations = operationsPerMaster,
      startAddress = (i * operationsPerMaster * 32).toLong, // Try to give somewhat distinct address regions
      addressStride = masterBusCfg.dataWidth.value / 8,
      writeProbability = 0.5 + (i % 2) * 0.1 // Vary write probability slightly
    )
    pluginsList += master
    demoMasterPlugins += master
  }

  val framework = ProjectScope(database) on new Framework(pluginsList.toSeq)

  // Expose test IOs from demo masters for simulation observation
  val masterTestObs = Vec(demoMasterPlugins.map(_.testIo))
  masterTestObs.simPublic() // Make the Vec and its Bundles accessible in sim

  // Expose memory's sim ports for initialization by the testbench
  val simMemoryComponent = framework.getService[SimulatedMainMemoryPlugin].memoryArea.simMem
  simMemoryComponent.io.simPublic() // Exposes writeEnable, writeAddress, writeData
}

class MemoryArbiterSpec extends CustomSpinalSimFunSuite {

  onlyVerilator()
  val mainBusAddrWidth = 32 bits
  val mainBusDataWidth = 32 bits // e.g., CPU word size

  val simMemInternalDataWidth = 16 bits // Simulated memory has 16-bit internal words
  val simMemSizeBytes = 16 KiB
  val simMemLatency = 2 // Latency for each internal 16-bit chunk access

  def runArbiterTest(numMasters: Int, opsPerMaster: Int, testNameSuffix: String): Unit = {
    test(s"MemoryArbiter - $numMasters Masters, $opsPerMaster ops/master - $testNameSuffix") {

      val mainMemBusCfg = GenericMemoryBusConfig(mainBusAddrWidth, mainBusDataWidth)
      val simMemInternalCfg = SimulatedMemoryConfig(simMemInternalDataWidth, simMemSizeBytes, simMemLatency)

      def tb = new MemoryArbiterTestBench(numMasters, opsPerMaster, mainMemBusCfg, simMemInternalCfg)

      simConfig.withVcdWave.compile(tb).doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        // Generous timeout: numMasters * opsPerMaster * (latency per op + arb delay) * clock period scale
        // Latency per op can be: (mainBusDataWidth/simMemInternalDataWidth) * simMemLatency + arbiter + FSM states
        val cyclesPerMainBusOpRoughly =
          (mainBusDataWidth.value / simMemInternalDataWidth.value) * (simMemLatency + 1) + 5 // +5 for arbiter/FSM
        SimTimeout(opsPerMaster * numMasters * cyclesPerMainBusOpRoughly * 20)

        // Initialize memory (optional, can be done here if specific read values are needed)
        // dut.simMemoryComponent.io.tbWriteEnable #= ...

        dut.clockDomain.waitSampling(10) // Initial settling time after reset

        // Wait for all masters to signal they have finished
        var allMastersFinished = false
        val maxSimCycles = opsPerMaster * numMasters * cyclesPerMainBusOpRoughly * 15 // Slightly tighter for sim loop
        var currentSimCycles = 0

        while (!allMastersFinished && currentSimCycles < maxSimCycles) {
          dut.clockDomain.waitSampling()
          // Check if all 'finished' signals from the masterTestObs Vec are true
          allMastersFinished = dut.masterTestObs.map(_.finished.toBoolean).reduce(_ && _)
          currentSimCycles += 1
        }

        assert(
          allMastersFinished,
          s"Timeout: Not all masters finished after $currentSimCycles simulation cycles. " +
            s"Finished states: ${dut.masterTestObs.map(_.finished.toBoolean).mkString(",")}"
        )

        println(s"All $numMasters masters finished their $opsPerMaster operations in $currentSimCycles cycles.")

        // Basic check: ensure all operations were sent and received by each master
        for (i <- 0 until numMasters) {
          // Accessing directly from the Vec exposed by simPublic
          val sent = dut.masterTestObs(i).operationsSent.toInt
          val received = dut.masterTestObs(i).responsesReceived.toInt
          println(s"Master $i: Sent $sent ops, Received $received responses.")
          assert(sent == opsPerMaster, s"Master $i did not send all operations. Expected $opsPerMaster, got $sent")
          assert(
            received == opsPerMaster,
            s"Master $i did not receive all responses. Expected $opsPerMaster, got $received"
          )
        }

        // More advanced verification (e.g., using a scoreboard for data integrity) would go here.
        // For this test, ensuring all masters complete their operations and report correct counts is the primary goal.

        dut.clockDomain.waitSampling(20) // Final wait for waveform
      }
    }
  }

  // --- Test Scenarios ---
  runArbiterTest(1, 5, "Single Master basic")
  // runArbiterTest(2, 8, "Two Masters concurrent")
  // runArbiterTest(4, 10, "Four Masters, more ops")
  // Add more tests: e.g., all masters reading, all masters writing, mixed with different configs if arbiter supports it.

  thatsAll()
}
