package parallax.test.scala // Or your test package


import parallax.common._
import parallax.fetch._
import parallax.utilities._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import spinal.tester.SpinalSimFunSuite
import parallax.components.memory._
import spinal.lib.bus.amba4.axi.Axi4Config // For Axi4Config
import scala.collection.mutable

class FetchPipelineTestBench(
    val pipelineCfgExternal: PipelineConfig, // General pipeline config
    val iBusMasterGmbCfg: GenericMemoryBusConfig, // GMB config for the I-Bus master (IFU)
    // Configs for the SimulatedUnifiedBusPlugin (or similar IBusService provider)
    val unifiedBus_iBusGmbCfg: GenericMemoryBusConfig, // GMB config for I-side service port
    val unifiedBus_fabricAxiCfg: Axi4Config,
    val unifiedBus_simMemGmbCfg: GenericMemoryBusConfig,
    val unifiedBus_simMemInternalCfg: SimulatedMemoryConfig
) extends Component {
  val database = new DataBase

  // Ensure the GMB config for IFU matches what the IBusService will provide on its iBus port
  require(iBusMasterGmbCfg == unifiedBus_iBusGmbCfg,
    "IFU's GMB config must match the IBusService provider's iBusGmbConfig")

  val framework = ProjectScope(database) on new Framework(
    Seq(
      // IBusService Provider (using SimulatedUnifiedBusPlugin as an example)
      new SimulatedUnifiedBusPlugin(
        iBusGmbConfig = unifiedBus_iBusGmbCfg,
        dBusGmbConfig = unifiedBus_iBusGmbCfg, // Assuming D-Bus uses same for simplicity here
        fabricAxiConfig = unifiedBus_fabricAxiCfg,
        simMemGmbConfig = unifiedBus_simMemGmbCfg,
        simMemInternalConfig = unifiedBus_simMemInternalCfg
        // ramBaseAddress can be defaulted or set
      ),
      new FetchOutputBridge(pipelineCfgExternal), // Use the passed pipeline config
      new FetchPipeline(pipelineCfgExternal), // Use the passed pipeline config
      new Fetch0Plugin(pcWidth = pipelineCfgExternal.pcWidth),
      new Fetch1Plugin(
        pipelineConfigFromParent = pipelineCfgExternal,
        iBusGmbConfigForIFU = iBusMasterGmbCfg // This is IFU's GMB master config
      ),
    )
  )

  val fetchPipeline = framework.getService[FetchPipeline]
  val iBusService = framework.getService[IBusService] // Get IBusService
  val fetchOutputBridge = framework.getService[FetchOutputBridge]
  val signals = fetchPipeline.signals

  // Accessing the simulated memory:
  // This depends on how SimulatedUnifiedBusPlugin is structured.
  // If it has a direct 'simulatedMemory' instance or a clearly named 'iSideMemory'.
  // For this example, let's assume SimulatedUnifiedBusPlugin has an internal 'logic' area
  // and instantiates 'simulatedMemory' within it, and we need to expose it.
  // This requires SimulatedUnifiedBusPlugin to make its internal memory instance accessible.
  // A simpler way for testing is if SimulatedUnifiedBusPlugin itself was the memory
  // or had a getter.
  // Let's modify SimulatedUnifiedBusPlugin slightly for testability if needed,
  // or assume a known path.
  // For now, this will cause a compile error if 'simulatedMemory' is not accessible.
  // We'll need to adjust SimulatedUnifiedBusPlugin to expose its memory for init.
  val unifiedBusPlugin = framework.getService[SimulatedUnifiedBusPlugin]
  // Assuming SimulatedUnifiedBusPlugin has a way to get its internal SimulatedSplitGeneralMemory
  // For example, if it has: val internalMemory = new SimulatedSplitGeneralMemory(...)
  // and internalMemory is made public for testing.
  // A more robust way: SimulatedUnifiedBusPlugin could offer a method for test init.
  // For now, let's assume a direct path for simplicity of this example,
  // knowing it might need adjustment in SimulatedUnifiedBusPlugin.
  // If SimulatedUnifiedBusPlugin's 'logic' area has 'val simulatedMemory = ...',
  // then it would be 'unifiedBusPlugin.logic.simulatedMemory'.
  // Let's assume we modify SimulatedUnifiedBusPlugin to have:
  //   val testSupport = create new Area { val mem = simulatedMemory }
  // Then we can access unifiedBusPlugin.testSupport.mem
  // For this example, I'll assume a direct field `exposedSimMem` for testing.
  // You would add to SimulatedUnifiedBusPlugin:
  //   val exposedSimMem = if (logic != null && logic.simulatedMemory != null) logic.simulatedMemory else null
  // This is a bit of a hack for testing. A dedicated test interface on the plugin is better.
  // Let's assume `SimulatedUnifiedBusPlugin` is modified to expose `simulatedMemory` from its `logic` area.
  val simMemForInit: SimulatedSplitGeneralMemory = unifiedBusPlugin.logic.simulatedMemory
  simMemForInit.io.simPublic()

  // Expose pipeline stages for test control/observation
  val s0 = fetchPipeline.pipeline.s0_PcGen
  s0.isReady.simPublic()
  s0.isValid.simPublic()
  s0(signals.PC).simPublic()

  val s1 = fetchPipeline.pipeline.s1_Fetch
  s1.isReady.simPublic()
  s1.isValid.simPublic()
  s1(signals.FETCHED_PC).simPublic()
  // s1(signals.INSTRUCTION_GROUP).simPublic() // simPublic on Vec might need care

  val io = new Bundle {
    val redirectPcValid = in Bool () simPublic ()
    val redirectPc = in UInt (pipelineCfgExternal.pcWidth) simPublic ()
    val output = master(Stream(FetchBridgeOutput(pipelineCfgExternal))) // Output type from bridge
  }

  io.output <> fetchOutputBridge.fetchOutput // Connect bridge output to testbench IO

  s0(signals.REDIRECT_PC_VALID) := io.redirectPcValid
  s0(signals.REDIRECT_PC) := io.redirectPc
}

class FetchPipelineSpec extends SpinalSimFunSuite {

  onlyVerilator

  val XLEN_VAL: Int = 32
  val XLEN: BitCount = XLEN_VAL bits

  // Helper to create a default PipelineConfig for tests
  def testPipelineConfig(fetchWidth: Int = 1) = PipelineConfig(fetchWidth = fetchWidth)

  // Helper to create GMB config for IFU's master port and IBusService's slave port
  def testMasterGmbConfig = GenericMemoryBusConfig(
    addressWidth = XLEN, dataWidth = XLEN, useId = true, idWidth = 4 bits
  )

  // Helper to create Axi4Config for the fabric (example values)
  def testFabricAxiConfig(gmbIdWidth: Int = 4, numMastersToCrossbar: Int = 2) = Axi4Config(
    addressWidth = XLEN_VAL, dataWidth = XLEN_VAL,
    idWidth = gmbIdWidth + (if (numMastersToCrossbar > 1) log2Up(numMastersToCrossbar) else 0),
    useLen = true, useBurst = true, useSize = true, useStrb = true, useResp = true, useLast = true
  )

  // Helper to create GMB config for the SimulatedSplitGeneralMemory
  def testSimMemGmbConfig(fabricAxiIdWidth: Int = 5) = GenericMemoryBusConfig(
    addressWidth = XLEN, dataWidth = XLEN, useId = true, idWidth = fabricAxiIdWidth bits
  )

  // Helper to create SimulatedMemoryConfig for the internal simulated memory
  def testSimMemInternalConfig = SimulatedMemoryConfig(
    internalDataWidth = XLEN, // For simplicity, match dataWidth
    memSize = 8 KiB,
    initialLatency = 2 // Example latency
  )


  def runFetchPipelineTest(
      currentPipelineConfig: PipelineConfig, // Pass the pipeline config
      testNameSuffix: String
  )(
      testLogic: (FetchPipelineTestBench, ClockDomain) => Unit
  ): Unit = {
    val testName = s"FetchPipeline (fetchWidth=${currentPipelineConfig.fetchWidth}) - $testNameSuffix"
    test(testName) {
      // These configs need to be consistent
      val masterGmbCfg = testMasterGmbConfig
      val fabricAxiCfg = testFabricAxiConfig(gmbIdWidth = masterGmbCfg.idWidth.value)
      val simMemGmbCfg = testSimMemGmbConfig(fabricAxiIdWidth = fabricAxiCfg.idWidth)
      val simMemIntCfg = testSimMemInternalConfig

      SimConfig.withWave.withVcdWave.compile(new FetchPipelineTestBench(
        pipelineCfgExternal = currentPipelineConfig,
        iBusMasterGmbCfg = masterGmbCfg,
        unifiedBus_iBusGmbCfg = masterGmbCfg, // Must match iBusMasterGmbCfg
        unifiedBus_fabricAxiCfg = fabricAxiCfg,
        unifiedBus_simMemGmbCfg = simMemGmbCfg,
        unifiedBus_simMemInternalCfg = simMemIntCfg
      )).doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.redirectPcValid #= false
        dut.io.redirectPc #= 0
        dut.io.output.ready #= false // Initialize to not ready
        testLogic(dut, dut.clockDomain)
      }
    }
  }

  // --- Test Cases ---
  val singleFetchPplCfg = testPipelineConfig(fetchWidth = 1)
  val dualFetchPplCfg = testPipelineConfig(fetchWidth = 2) // Example for future

  runFetchPipelineTest(singleFetchPplCfg, "sequential fetch") { (dut, clockDomain) =>
    val instructionsToLoad = Map(
      0x0L -> BigInt("11223344", 16),
      0x4L -> BigInt("55667788", 16),
      0x8L -> BigInt("99AABBCC", 16),
      0xcL -> BigInt("DDEEFF00", 16)
    )

    instructionsToLoad.foreach { case (addr, data) =>
      FetchSimMemInit.initMemWord(dut.simMemForInit, addr, data, XLEN_VAL, clockDomain)
    }
    println("Reset testbench and start simulation...")
    clockDomain.assertReset()
    sleep(10) // ns
    clockDomain.deassertReset()
    sleep(10) // ns

    println("[Test Seq] Fetching instructions from memory...")
    // Queue stores (pc, instruction0, fault) assuming fetchWidth=1 for this test
    val receivedOutputs = mutable.Queue[(Long, BigInt, Boolean)]()
    StreamMonitor(dut.io.output, clockDomain) { payload =>
      println(
        f"[Test Seq] Received PC:0x${payload.pc.toLong}%x Instr0:0x${payload.instructions(0).toBigInt}%x Fault:${payload.fault.toBoolean}%b"
      )
      receivedOutputs.enqueue(
        (payload.pc.toLong, payload.instructions(0).toBigInt, payload.fault.toBoolean)
      )
    }
    StreamReadyRandomizer(dut.io.output, clockDomain)

    val timeoutCycles = instructionsToLoad.size * 50 + 300 // Increased timeout for more complex path
    assert(
      !clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedOutputs.size == instructionsToLoad.size),
      s"Timeout: Did not receive all ${instructionsToLoad.size} instructions. Got ${receivedOutputs.size}"
    )

    instructionsToLoad.toList.sortBy(_._1).foreach { case (addr, instr) =>
      val (recPc, recInstr, recFault) = receivedOutputs.dequeue()
      println(
        f"[Test Seq] Expected PC:0x$addr%x Instr:0x$instr%x | Got PC:0x$recPc%x Instr:0x$recInstr%x Fault:$recFault%b"
      )
      assert(!recFault, f"Fetch fault at PC 0x$recPc%x")
      assert(recPc == addr, f"PC mismatch: expected 0x$addr%x, got 0x$recPc%x")
      assert(recInstr == instr, f"Instruction mismatch at PC 0x$addr%x: expected 0x$instr%x, got 0x$recInstr%x")
    }
    clockDomain.waitSampling(10)
  }

  runFetchPipelineTest(singleFetchPplCfg, "PC redirection") { (dut, clockDomain) =>
    val instructions = Map(
      0x00L -> BigInt("11111111", 16),
      0x04L -> BigInt("22222222", 16),
      0x08L -> BigInt("33333333", 16),
      0x80L -> BigInt("AAAAAAAA", 16),
      0x84L -> BigInt("BBBBBBBB", 16)
    )
    instructions.foreach { case (addr, data) =>
      FetchSimMemInit.initMemWord(dut.simMemForInit, addr, data, XLEN_VAL, clockDomain)
    }

    val receivedOutputs = mutable.Queue[(Long, BigInt, Boolean)]()
    StreamMonitor(dut.io.output, clockDomain) { payload =>
        receivedOutputs.enqueue((payload.pc.toLong, payload.instructions(0).toBigInt, payload.fault.toBoolean))
    }
    dut.io.output.ready #= true // Consumer always ready for simpler redirect timing

    clockDomain.assertReset()
    sleep(10)
    clockDomain.deassertReset()
    clockDomain.waitSampling(1)

    def expectOutput(pc: Long, instr: BigInt, fault: Boolean = false): Unit = {
        assert(!clockDomain.waitSamplingWhere(50)(receivedOutputs.nonEmpty), s"Timeout waiting for PC 0x${pc.toHexString}")
        val (rPc, rInstr, rFault) = receivedOutputs.dequeue()
        println(f"[Redirect] Got PC:0x$rPc%x Instr:0x$rInstr%x Fault:$rFault%b")
        assert(rPc == pc && rInstr == instr && rFault == fault, f"Mismatch for PC 0x$pc%x")
    }

    expectOutput(0x00L, instructions(0x00L)) // PC=0 in S0, then S1
    expectOutput(0x04L, instructions(0x04L)) // PC=4 in S0, then S1. S0 now has PC=8.

    assert(dut.s0(dut.signals.PC).toLong == 0x08L, "S0 should have PC=0x08 before redirect injection")
    println(s"[Redirect Test] S0 PC is 0x08. Injecting redirect to 0x80.")
    dut.io.redirectPcValid #= true
    dut.io.redirectPc #= 0x80L
    clockDomain.waitSampling() // Redirect affects Fetch0Plugin.pcReg this cycle. s0(PC) still 0x08 for S1.
    dut.io.redirectPcValid #= false

    expectOutput(0x08L, instructions(0x08L)) // S1 processes 0x08. S0 now outputs PC=0x80.
    assert(dut.s0(dut.signals.PC).toLong == 0x80L, s"S0 PC should be 0x80 after redirect, but is 0x${dut.s0(dut.signals.PC).toLong}")

    expectOutput(0x80L, instructions(0x80L))
    expectOutput(0x84L, instructions(0x84L))

    clockDomain.waitSampling(10)
  }

  runFetchPipelineTest(singleFetchPplCfg, "output stall") { (dut, clockDomain) =>
    val instructions = Map(
      0x0L -> BigInt("12345678", 16),
      0x4L -> BigInt("87654321", 16),
      0x8L -> BigInt("AABBCCDD", 16)
    )
    instructions.foreach { case (addr, data) =>
      FetchSimMemInit.initMemWord(dut.simMemForInit, addr, data, XLEN_VAL, clockDomain)
    }

    val receivedPcs = mutable.Queue[Long]() // Only storing PC for simplicity here
    StreamMonitor(dut.io.output, clockDomain) { payload => receivedPcs.enqueue(payload.pc.toLong) }

    clockDomain.assertReset()
    sleep(10)
    dut.io.output.ready #= true
    clockDomain.deassertReset()
    clockDomain.waitSampling(1)

    assert(!clockDomain.waitSamplingWhere(50)(receivedPcs.nonEmpty), "Timeout waiting for first instruction")
    assert(receivedPcs.head == 0x0L) // error
    println(s"[Stall Test] Fetched PC: ${receivedPcs.dequeue()}") // PC=0 fetched

    // After PC=0 is fetched and S1 fires:
    // Fetch0Plugin.pcReg = 0x08. s0(signals.PC) = 0x08 (output to S1 for next cycle)
    // S1 input payload has PC = 0x04. S1 starts fetching 0x04.

    println("[Stall Test] Stalling S1 output (output.ready = false)")
    dut.io.output.ready #= false
    clockDomain.waitSampling(10) // Let states settle

    // S1 has fetched PC=0x04 and its internal regDataFromIfuIsValid should be true.
    // s1(signals.FETCHED_PC) should be 0x04.
    // S0 should have tried to send PC=0x08 to S1. If S1 is stalled because its output is not ready,
    // S1's input ready (s1.ready) will be false. This will stall S0.
    // So, s0(signals.PC) should still be 0x08 (the value it's trying to send).
    // Fetch0Plugin.pcReg would have advanced to 0x0C if S0 had fired with 0x08.
    // Since S0 is stalled, pcReg in Fetch0Plugin should remain at 0x08.

    assert(!dut.io.output.valid.toBoolean || !dut.io.output.ready.toBoolean, "Output should not be firing when stalled")
    assert(dut.s1.isStuck.toBoolean, "S1 should be stuck due to output stall")
    assert(dut.s0.isStuck.toBoolean, "S0 should be stuck because S1 is stuck")


    println(s"[Stall Test] While stalled: S0_payload_PC=0x${dut.s0(dut.signals.PC).toLong.toHexString}, S1_output_FETCHED_PC=0x${dut.s1(dut.signals.FETCHED_PC).toLong.toHexString}")
    assert(dut.s1(dut.signals.FETCHED_PC).toLong == 0x4L, s"S1 output FETCHED_PC should hold 0x4 when stalled with its data")
    assert(dut.s0(dut.signals.PC).toLong == 0x8L, s"S0 payload PC should hold 0x8 (stuck trying to send to S1)")
    assert(receivedPcs.isEmpty, "No new instructions should be received during stall")

    println("[Stall Test] Unstalling S1 output (output.ready = true)")
    dut.io.output.ready #= true
    assert(!clockDomain.waitSamplingWhere(50)(receivedPcs.nonEmpty), "Timeout waiting for PC=0x4 after unstall")
    assert(receivedPcs.head == 0x4L)
    println(s"[Stall Test] Fetched PC: ${receivedPcs.dequeue()} after unstall") // PC=4 fetched

    clockDomain.waitSampling(10)
  }

  // Add a test for dual fetch if desired, e.g.:
  // runFetchPipelineTest(dualFetchPplCfg, "sequential dual fetch") { (dut, clockDomain) => ... }
}
