package test.scala // Or your test package

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
import spinal.lib.pipeline.Stage

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

  val io = new Bundle {
    val redirectPcValid = in Bool () simPublic ()
    val redirectPc = in UInt (pipelineCfgExternal.pcWidth) simPublic ()
    val output = master(Stream(FetchBridgeOutput(pipelineCfgExternal))) // Output type from bridge
  }

  // Ensure the GMB config for IFU matches what the IBusService will provide on its iBus port
  require(
    iBusMasterGmbCfg == unifiedBus_iBusGmbCfg,
    "IFU's GMB config must match the IBusService provider's iBusGmbConfig"
  )

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
      new FetchOutputBridge(pipelineCfgExternal, io.output), // Use the passed pipeline config
      new FetchPipeline(pipelineCfgExternal), // Use the passed pipeline config
      new Fetch0Plugin(pcWidth = pipelineCfgExternal.pcWidth),
      new Fetch1Plugin(
        pipelineConfigFromParent = pipelineCfgExternal,
        iBusGmbConfigForIFU = iBusMasterGmbCfg // This is IFU's GMB master config
      )
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
  s0.internals.input.ready.simPublic()
  s0.internals.input.valid.simPublic()
  s0(signals.PC).simPublic()

  val s1 = fetchPipeline.pipeline.s1_Fetch
  s1.internals.input.ready.simPublic()
  s1.internals.input.valid.simPublic()
  s1(signals.FETCHED_PC).simPublic()
  // s1(signals.INSTRUCTION_GROUP).simPublic() // simPublic on Vec might need care

  s0(signals.REDIRECT_PC_VALID) := io.redirectPcValid
  s0(signals.REDIRECT_PC) := io.redirectPc
}

class FetchPipelineSpec extends CustomSpinalSimFunSuite {

  onlyVerilator

  val XLEN_VAL: Int = 32
  val XLEN: BitCount = XLEN_VAL bits

  // Helper to create a default PipelineConfig for tests
  def testPipelineConfig(fetchWidth: Int = 1) = PipelineConfig(fetchWidth = fetchWidth)

  // Helper to create GMB config for IFU's master port and IBusService's slave port
  def testMasterGmbConfig = GenericMemoryBusConfig(
    addressWidth = XLEN,
    dataWidth = XLEN,
    useId = true,
    idWidth = 4 bits
  )

  // Helper to create Axi4Config for the fabric (example values)
  def testFabricAxiConfig(gmbIdWidth: Int = 4, numMastersToCrossbar: Int = 2) = Axi4Config(
    addressWidth = XLEN_VAL,
    dataWidth = XLEN_VAL,
    idWidth = gmbIdWidth + (if (numMastersToCrossbar > 1) log2Up(numMastersToCrossbar) else 0),
    useLen = true,
    useBurst = true,
    useSize = true,
    useStrb = true,
    useResp = true,
    useLast = true
  )

  // Helper to create GMB config for the SimulatedSplitGeneralMemory
  def testSimMemGmbConfig(fabricAxiIdWidth: Int = 5) = GenericMemoryBusConfig(
    addressWidth = XLEN,
    dataWidth = XLEN,
    useId = true,
    idWidth = fabricAxiIdWidth bits
  )

  // Helper to create SimulatedMemoryConfig for the internal simulated memory
  def testSimMemInternalConfig = SimulatedMemoryConfig(
    internalDataWidth = XLEN, // For simplicity, match dataWidth
    memSize = 8 KiB,
    initialLatency = 1 // Example latency
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

      SimConfig.withWave.withVcdWave
        .compile(
          new FetchPipelineTestBench(
            pipelineCfgExternal = currentPipelineConfig,
            iBusMasterGmbCfg = masterGmbCfg,
            unifiedBus_iBusGmbCfg = masterGmbCfg, // Must match iBusMasterGmbCfg
            unifiedBus_fabricAxiCfg = fabricAxiCfg,
            unifiedBus_simMemGmbCfg = simMemGmbCfg,
            unifiedBus_simMemInternalCfg = simMemIntCfg
          )
        )
        .doSim { dut =>
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
  runFetchPipelineTest(singleFetchPplCfg, "output stall - revised") { (dut, clockDomain) =>
    val instructions = Map(
      0x0L -> BigInt("12345678", 16),
      0x4L -> BigInt("23333333", 16),
      0x8L -> BigInt("AABBCCDD", 16)
    )
    instructions.foreach { case (addr, data) =>
      FetchSimMemInit.initMemWord(dut.simMemForInit, addr, data, XLEN_VAL, clockDomain)
    }

    val receivedDatas = mutable.Queue[(Long, BigInt)]()
    StreamMonitor(dut.io.output, clockDomain) { payload =>
      println(f"[Stall Test Revised] Received PC:0x${payload.pc.toLong}%x Instr0:0x${payload.instructions(0).toBigInt}%x")
      receivedDatas.enqueue((payload.pc.toLong, payload.instructions(0).toBigInt))
    }

    clockDomain.assertReset()
    sleep(10)
    dut.io.output.ready #= true // Initially ready
    clockDomain.deassertReset()
    clockDomain.waitSampling(1)
    println(s"[Stall Test Revised] Init mem done, starting test...")

    // 1. Fetch PC=0
    assert(!clockDomain.waitSamplingWhere(200)(receivedDatas.nonEmpty), "Timeout waiting for PC=0")
    val (pc0, instr0) = receivedDatas.dequeue()
    assert(pc0 == 0x0L && instr0 == instructions(0x0L), f"Expected PC=0x0, Instr=${instructions(0x0L)}%x; Got PC=0x$pc0%x, Instr=0x$instr0%x")
    println(f"[Stall Test Revised] Fetched PC=0x$pc0%x")

    // 2. Fetch PC=4 (allow it to be fully fetched and output once)
    assert(!clockDomain.waitSamplingWhere(200)(receivedDatas.nonEmpty), "Timeout waiting for PC=4")
    val (pc4, instr4) = receivedDatas.dequeue()
    assert(pc4 == 0x4L && instr4 == instructions(0x4L), f"Expected PC=0x4, Instr=${instructions(0x4L)}%x; Got PC=0x$pc4%x, Instr=0x$instr4%x")
    println(f"[Stall Test Revised] Fetched PC=0x$pc4%x. S1 has now fired with PC=4 data.")
    // 此刻，s1(signals.FETCHED_PC) 寄存器的 Q 端应该是 0x4L

    // 3. Now, stall the output
    println("[Stall Test Revised] Stalling S1 output (output.ready = false)")
    dut.io.output.ready #= false
    clockDomain.waitSampling(10) // Let states settle. S0 should be stalled trying to send PC=8.
                                  // S1 should be stalled, its output.valid might be high (if spawnIt works and IFU is not involved anymore for PC=4)
                                  // or S1 might be idle if it doesn't hold output.

    // 4. Assertions during stall
    def isStuck(s: Stage): Boolean = {
      s.internals.input.valid.toBoolean && !s.internals.input.ready.toBoolean
    }
    // dut.io.output.valid might be high if s1_Fetch can hold its output valid (e.g. via spawnIt and IFU no longer being the source)
    // but dut.io.output.fire must be false
    assert(!(dut.io.output.ready.toBoolean && dut.io.output.valid.toBoolean), "Output stream should not be firing when stalled")

    // S1 should be "stuck" in the sense that its output.ready is false.
    // Its input.ready might be true if it's idle and ready for new data from S0,
    // OR its input.ready might be false if it's trying to make S0 wait for some internal processing.
    // Given S1 has already processed PC=4, it should be idle or waiting for S0 to send PC=8.
    // If S0 is sending PC=8, S1's input.valid would be true.
    // If S1 cannot accept PC=8 (e.g. if it needed to do something else before taking new PC), its input.ready would be false.
    // Let's assume S1 is simple enough that after firing, it's ready for new input unless explicitly halted.

    // If S1 holds its last fired value (PC=4), then:
    // S1(FETCHED_PC) should be 4.
    // S0 should be trying to send PC=8. So s0(signals.PC) (S1's input) should be 8.
    // S1's input.valid (for PC=8) is true. S1's input.ready might be true or false depending on S1's internal state.
    // If S1's input.ready is false, then S0 is stuck.

    assert(isStuck(dut.s0), "S0 should be stuck trying to send PC=8 to S1, as S1's output is stalled, leading to backpressure if S1 can't buffer.")
    // This assertion on s1 being stuck might be tricky depending on S1's state after firing PC=4.
    // If S1 has fired PC=4 and is now idle waiting for S0 to send PC=8, and S0 *is* sending PC=8,
    // then S1's input.valid = true. S1's input.ready could be true.
    // However, because S1's *output* is stalled, it cannot *fire* PC=8 even if it accepted it.

    println(
      s"[Stall Test Revised] While stalled: S0_payload_PC(S1_input)=0x${dut.s0(dut.signals.PC).toLong.toHexString}, S1_output_FETCHED_PC(Q)=0x${dut.s1(dut.signals.FETCHED_PC).toLong.toHexString}"
    )
    weakAssert(
      dut.s1(dut.signals.FETCHED_PC).toLong == 0x4L, // CRITICAL: Check the latched value AFTER PC=4 has fired
      s"S1 output FETCHED_PC should HOLD 0x4 (last fired value) when stalled. Got 0x${dut.s1(dut.signals.FETCHED_PC).toLong.toHexString}"
    )
    weakAssert(dut.s0(dut.signals.PC).toLong == 0x8L, s"S0 payload PC (input to S1) should be 0x8. Got 0x${dut.s0(dut.signals.PC).toLong.toHexString}")
    assert(receivedDatas.isEmpty, "No new instructions should be received by monitor during stall")

    // 5. Unstall the output
    println("[Stall Test Revised] Unstalling S1 output (output.ready = true)")
    dut.io.output.ready #= true
    // Now, S1 should process PC=8 which was waiting at its input.
    assert(!clockDomain.waitSamplingWhere(200)(receivedDatas.nonEmpty), "Timeout waiting for PC=8 after unstall")
    val (pc8, instr8) = receivedDatas.dequeue()
    assert(pc8 == 0x8L && instr8 == instructions(0x8L), f"Expected PC=0x8, Instr=${instructions(0x8L)}%x; Got PC=0x$pc8%x, Instr=0x$instr8%x")
    println(f"[Stall Test Revised] Fetched PC=0x$pc8%x after unstall")

    clockDomain.waitSampling(10)
  }
  
  runFetchPipelineTest(dualFetchPplCfg, "sequential dual fetch") { (dut, clockDomain) =>
    val cfg = dualFetchPplCfg // For easier access to fetchWidth
    val bytesPerInstr = cfg.bytesPerInstruction

    // Instructions: PC -> (Inst0, Inst1) for a fetch group
    val instructionGroupsToLoad = List(
      (0x0L, (BigInt("00000013", 16), BigInt("00100113", 16))), // PC=0 -> nop, addi x2,x0,1
      (0x8L, (BigInt("00200193", 16), BigInt("00300213", 16))), // PC=8 -> addi x3,x0,2, addi x4,x0,3
      (0x10L, (BigInt("00400293", 16), BigInt("00500313", 16)))  // PC=10-> addi x5,x0,4, addi x6,x0,5
    )

    // Load individual instructions into memory
    instructionGroupsToLoad.foreach { case (startPc, insts) =>
      FetchSimMemInit.initMemWord(dut.simMemForInit, startPc, insts._1, XLEN_VAL, clockDomain)
      FetchSimMemInit.initMemWord(dut.simMemForInit, startPc + bytesPerInstr, insts._2, XLEN_VAL, clockDomain)
    }

    println(s"[Test Dual Seq] Reset testbench (fetchWidth=${cfg.fetchWidth})...")
    clockDomain.assertReset()
    sleep(10)
    clockDomain.deassertReset()
    sleep(10)

    println("[Test Dual Seq] Fetching instruction groups...")
    // Queue stores (pcOfGroup, Vec[Instruction], fault)
    val receivedGroups = mutable.Queue[(Long, Seq[BigInt], Boolean)]()

    StreamMonitor(dut.io.output, clockDomain) { payload =>
      val instrs = payload.instructions.map(_.toBigInt).toSeq
      println(
        f"[Test Dual Seq] Received PC:0x${payload.pc.toLong}%x Instrs:[${instrs.map(i => f"0x$i%x").mkString(", ")}] Fault:${payload.fault.toBoolean}%b"
      )
      receivedGroups.enqueue((payload.pc.toLong, instrs, payload.fault.toBoolean))
    }
    StreamReadyRandomizer(dut.io.output, clockDomain)

    val expectedGroupCount = instructionGroupsToLoad.size
    val timeoutCycles = expectedGroupCount * 100 + 500 // Adjust timeout as needed

    assert(
      !clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedGroups.size == expectedGroupCount),
      s"Timeout: Did not receive all $expectedGroupCount instruction groups. Got ${receivedGroups.size}"
    )

    instructionGroupsToLoad.foreach { case (expectedPc, expectedInstrsTuple) =>
      val (recPc, recInstrsSeq, recFault) = receivedGroups.dequeue()
      val expectedInstrsSeq = Seq(expectedInstrsTuple._1, expectedInstrsTuple._2)

      println(
        f"[Test Dual Seq] Expected PC:0x$expectedPc%x Instrs:[${expectedInstrsSeq.map(i => f"0x$i%x").mkString(", ")}]" +
          f" | Got PC:0x$recPc%x Instrs:[${recInstrsSeq.map(i => f"0x$i%x").mkString(", ")}] Fault:$recFault%b"
      )
      assert(!recFault, f"Fetch fault at PC group 0x$recPc%x")
      assert(recPc == expectedPc, f"PC mismatch: expected 0x$expectedPc%x, got 0x$recPc%x")
      assert(recInstrsSeq.size == cfg.fetchWidth, f"Incorrect number of instructions in group: expected ${cfg.fetchWidth}, got ${recInstrsSeq.size}")
      recInstrsSeq.zip(expectedInstrsSeq).zipWithIndex.foreach { case ((recInstr, expInstr), idx) =>
        assert(recInstr == expInstr, f"Instruction mismatch at PC 0x${expectedPc + idx * bytesPerInstr}%x (Group PC 0x$expectedPc%x, Index $idx): expected 0x$expInstr%x, got 0x$recInstr%x")
      }
    }
    clockDomain.waitSampling(10)
  }
  
  thatsAll
}
