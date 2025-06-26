// filename: test/scala/fetch/FetchPipelinePluginSpec.scala
// cmd: testOnly test.scala.fetch.FetchPipelinePluginSpec
package test.scala.fetch

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import parallax.common._
import parallax.fetch._
import parallax.bpu._
import parallax.components.ifu.IFUService
import parallax.components.memory._
import parallax.utilities._

import scala.collection.mutable
import scala.util.Random
import spinal.lib.sim.FlowMonitor
import parallax.components.ifu.IFUPlugin
import parallax.components.dcache2.DataCachePluginConfig
import parallax.components.memory.InstructionFetchUnitConfig
import scala.math.BigInt
import parallax.components.bpu.BpuPipelinePlugin
import parallax.components.dcache2.DataCachePlugin
import test.scala.lsu.TestOnlyMemSystemPlugin
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.components.dcache2.DataCacheService

import test.scala.LA32RInstrBuilder._

// =========================================================================
//  Test Bench Setup
// =========================================================================

/** Test IO bundle for the FetchPipeline.
  * >>> FIX: Removed ifuCmd and ifuRsp as they are now internal to the DUT.
  */
case class FetchPipelineTestBenchIo(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig) extends Bundle {
  // --- To FetchPipeline (and its dependencies) ---
  val bpuUpdate = slave(Stream(BpuUpdate(pCfg)))
  val redirect = slave(Flow(UInt(pCfg.pcWidth)))

  // --- From FetchPipeline ---
  val fetchOutput = master(Stream(FetchOutput(pCfg, ifuCfg)))
  // We can optionally monitor BPU queries if needed for a specific test.
  val bpuQuery = master(Flow(BpuQuery(pCfg)))
}

/** A test-only plugin that connects the real BPU and Fetch services to the
  * top-level test bench IOs for external driving and monitoring.
  */
class FetchTestSetupPlugin(io: FetchPipelineTestBenchIo) extends Plugin {
  val setup = create early new Area {
    // --- Get handles to the real services ---
    val bpuService = getService[BpuService]
    val fetchService = getService[FetchPipelineService]
    val dcService = getService[DataCacheService]

    // --- BPU Connections ---
    // Connect BPU update port to test bench IO
    bpuService.newBpuUpdatePort() <> io.bpuUpdate

    // Monitor what the FetchPipeline is querying the BPU for.
    // Note: This creates a second query port on the BPU service. One for the FetchPipeline, one for us.
    // The BPU service must be able to handle multiple query ports if this is used.
    // For now, let's assume the BpuPipelinePlugin's service can handle this.
    // A simpler way if not needed for a test is to comment this out.
    io.bpuQuery <> bpuService.newBpuQueryPort()

    // --- FetchPipeline Connections ---
    // Connect the redirect input from the test bench to the fetch pipeline's redirect service.
    // The `redirect` method in the service pushes to an internal Flow.
    fetchService.getRedirectPort <> io.redirect

    // Connect the main output of the pipeline to our test bench's monitoring port.
    io.fetchOutput <> fetchService.fetchOutput()

    // --- D-Cache Connection for self-check ---
    val unusedStorePort = dcService.newStorePort()
    unusedStorePort.cmd.valid := False
    unusedStorePort.cmd.payload.assignDontCare()
  }
}

/** The top-level DUT for testing the FetchPipelinePlugin. */
class FetchPipelineTestBench(
    val pCfg: PipelineConfig,
    val ifuCfg: InstructionFetchUnitConfig,
    val dCfg: DataCachePluginConfig,
    val axiCfg: Axi4Config,
    bufferDepth: Int
) extends Component {
  val io = FetchPipelineTestBenchIo(pCfg, ifuCfg)
  io.simPublic()

  val framework = new Framework(
    Seq(
      new TestOnlyMemSystemPlugin(axiConfig = axiCfg),
      new DataCachePlugin(dCfg),
      new IFUPlugin(ifuCfg),
      new BpuPipelinePlugin(pCfg),
      new FetchPipelinePlugin(pCfg, ifuCfg, bufferDepth), // The DUT
      new FetchTestSetupPlugin(io)
    )
  )
}

// =========================================================================
//  Test Helper
// =========================================================================

class FetchTestHelper(dut: FetchPipelineTestBench)(implicit cd: ClockDomain) {
  // Get a handle to the simulated memory system
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
  val fetchedInstructions = mutable.Queue[FetchOutputCapture]()

  def init(): Unit = {
    dut.io.bpuUpdate.valid #= false
    dut.io.redirect.valid #= false
    dut.io.fetchOutput.ready #= false // Start with downstream stalled
    cd.waitSampling()
  }

  def startMonitor(): Unit = {
    StreamMonitor(dut.io.fetchOutput, cd) { payload =>
      val captured = FetchOutputCapture(payload)
      fetchedInstructions.enqueue(captured)
    }
    StreamReadyRandomizer(dut.io.fetchOutput, cd)
  }

  def writeInstructionsToMem(address: BigInt, instructions: Seq[BigInt]): Unit = {
    var currentAddr = address
    for (inst <- instructions) {
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= currentAddr
      sram.io.tb_writeData #= inst
      cd.waitSampling()
      currentAddr += 4
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(5)
  }

  def issueRedirect(newPc: BigInt): Unit = {
    dut.io.redirect.valid #= true
    dut.io.redirect.payload #= newPc
    cd.waitSampling()
    dut.io.redirect.valid #= false
  }

  def expectInstructions(expected: Seq[(BigInt, BigInt)], timeout: Int = 400): Unit = {
    for ((expectedPc, expectedInst) <- expected) {
      val timedOut = cd.waitSamplingWhere(timeout = timeout) {
        fetchedInstructions.nonEmpty
      }
      assert(!timedOut, s"Timeout waiting for instruction at PC 0x${expectedPc.toString(16)}")

      val received = fetchedInstructions.dequeue()
      assert(
        received.pc.toBigInt == expectedPc,
        s"PC mismatch! Got 0x${received.pc.toBigInt.toString(16)}, Expected 0x${expectedPc.toString(16)}"
      )
      assert(
        received.instruction.toBigInt == expectedInst,
        s"Instruction mismatch! Got 0x${received.instruction.toBigInt.toString(16)}, Expected 0x${expectedInst.toString(16)}"
      )
    }
  }
}

class FetchPipelinePluginSpec extends CustomSpinalSimFunSuite {

  // --- Configuration constants ---
  val pCfg = PipelineConfig(
    xlen = 32,
    fetchWidth = 2, // Important: IFU fetches 2 instructions at a time
    resetVector = BigInt("00000000", 16).toInt,
    transactionIdWidth = 1
  )

  val dCfg = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = 32,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 16,
    transactionIdWidth = pCfg.transactionIdWidth
  )

  val minimalDCacheParams = DataCachePluginConfig.toDataCacheParameters(dCfg)

  val ifuCfg = InstructionFetchUnitConfig(
    dcacheParameters = minimalDCacheParams,
    pcWidth = pCfg.pcWidth,
    instructionWidth = pCfg.dataWidth,
    fetchGroupDataWidth = (pCfg.dataWidth.value * pCfg.fetchWidth) bits,
    enableLog = true
  )

  def createAxi4Config(pCfg: PipelineConfig): Axi4Config = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = pCfg.xlen,
    idWidth = pCfg.transactionIdWidth,
    useLock = false,
    useCache = false,
    useProt = true,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )
  val axiConfig = createAxi4Config(pCfg)

  val bufferDepth = 8

  test("FetchPipeline - Sequential Fetch") {
    SimConfig.withWave
      .compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new FetchTestHelper(dut)
        helper.init()

        // --- Test Plan ---
        // 1. Write the test "program" into the simulated main memory (SRAM).
        // 2. Start the pipeline and its monitors.
        // 3. The pipeline should fetch from the reset vector, and the real IFU/DCache
        //    will pull the data from our SRAM.
        // 4. Verify the fetched instructions arrive at the output in the correct order.

        // >>> FIX: Use writeInstructionsToMem instead of respondWithPacket
        val instructionsPacket1 = Seq(BigInt(0x11), BigInt(0x22))
        val instructionsPacket2 = Seq(BigInt(0x33), BigInt(0x44))
        helper.writeInstructionsToMem(address = BigInt("00000000", 16), instructions = instructionsPacket1)
        helper.writeInstructionsToMem(address = BigInt("00000008", 16), instructions = instructionsPacket2)

        helper.startMonitor()

        helper.expectInstructions(
          Seq(
            (BigInt("00000000", 16), BigInt(0x11)), // PC, Instruction
            (BigInt("00000004", 16), BigInt(0x22)),
            (BigInt("00000008", 16), BigInt(0x33)),
            (BigInt("0000000C", 16), BigInt(0x44))
          )
        )

        println("Test 'FetchPipeline - Sequential Fetch' PASSED")
      }
  }

  testOnly("FetchPipeline - Redirect (Flush)") {
    SimConfig.withWave
      .compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new FetchTestHelper(dut)
        helper.init()

        // --- Test Plan ---
        // 1. Write instructions for both the original path and the redirected path into memory.
        // 2. Let the pipeline start fetching and consume one instruction.
        // 3. Issue a redirect to a new PC.
        // 4. Verify that buffered/in-flight instructions from the old path are discarded
        //    and that the pipeline correctly starts fetching from the new path.

        // >>> FIX: Use writeInstructionsToMem instead of respondWithPacket
        helper.writeInstructionsToMem(BigInt("00000000", 16), Seq(BigInt(0x11), BigInt(0x22)))
        helper.writeInstructionsToMem(BigInt("00000008", 16), Seq(BigInt(0x33), BigInt(0x44)))
        helper.writeInstructionsToMem(BigInt("00001000", 16), Seq(BigInt(0xaa), BigInt(0xbb)))

        helper.startMonitor()

        // Allow the first instruction to be consumed.
        helper.expectInstructions(Seq((BigInt("00000000", 16), BigInt(0x11))))

        // Now, issue the redirect. At this point, instructions 0x22, 0x33, 0x44 are likely
        // in-flight within the IFU/DCache or already in the FetchBuffer.
        helper.issueRedirect(newPc = BigInt("00001000", 16))

        // After the redirect, we should see the new instruction stream.
        helper.expectInstructions(
          Seq(
            (BigInt("00001000", 16), BigInt(0xaa)),
            (BigInt("00001004", 16), BigInt(0xbb))
          )
        )

        // Crucially, verify that no more old-path instructions are in the queue.
        cd.waitSampling(50) // Wait some time to ensure no more instructions are coming
        assert(
          helper.fetchedInstructions.isEmpty,
          s"Pipeline did not flush correctly! Found unexpected instructions: ${helper.fetchedInstructions.mkString(", ")}"
        )

        println("Test 'FetchPipeline - Redirect (Flush)' PASSED")
      }
  }

    // --- New Test for BPU/Predecoder Integration ---
  test("FetchPipeline - Predecoder and Branch Recognition") {
    SimConfig.withWave
      .compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new FetchTestHelper(dut)
        helper.init()
        
        // --- Test Plan ---
        // 1. Create a program with various instruction types, including branches and jumps.
        //    - A NOP (normal instruction)
        //    - A BEQ (isBranch = true)
        //    - A B (isJump = true)
        //    - Another NOP
        // 2. Write this program to memory.
        // 3. Start the monitor on fetchOutput.
        // 4. Verify that each fetched instruction has the correct `isBranch` and `isJump` flags.


        // Instruction sequence: NOP, BEQ, B, NOP
        val program = Seq(
          nop(),                                     // PC 0x00: Normal
          beq(rj = 1, rd = 2, offset = 16),          // PC 0x04: Conditional Branch (isBranch=true)
          b(offset = -8),                            // PC 0x08: Unconditional Jump (isJump=true)
          nop()                                      // PC 0x0C: Normal
        )

        helper.writeInstructionsToMem(address = 0, instructions = program)

        helper.startMonitor()

        // We expect to fetch all 4 instructions sequentially.
        // The BPU is not trained, so it should predict "not taken" for the BEQ.
        // The PC generation logic will ignore the `isJump` flag for now, as BPU is not predicting a jump.
        cd.waitSamplingWhere(timeout = 500) {
          helper.fetchedInstructions.size >= 4
        }
        
        assert(helper.fetchedInstructions.size == 4, s"Expected 4 instructions, but got ${helper.fetchedInstructions.size}")

        // --- Verification ---
        // 1. First instruction: NOP
        val inst1 = helper.fetchedInstructions.dequeue()
        assert(inst1.pc == 0x00 && inst1.instruction == nop(), "First instruction should be NOP at PC 0x0")
        assert(!inst1.isBranch && !inst1.isJump, "NOP should not be a branch or jump")

        // 2. Second instruction: BEQ
        val inst2 = helper.fetchedInstructions.dequeue()
        assert(inst2.pc == 0x04, "Second instruction should be at PC 0x4")
        assert(inst2.isBranch, "BEQ should be detected as a branch (isBranch=true)")
        assert(!inst2.isJump, "BEQ should not be detected as a jump")
        
        // 3. Third instruction: B
        val inst3 = helper.fetchedInstructions.dequeue()
        assert(inst3.pc == 0x08, "Third instruction should be at PC 0x8")
        assert(!inst3.isBranch, "B should not be detected as a branch")
        assert(inst3.isJump, "B should be detected as a jump (isJump=true)")

        // 4. Fourth instruction: NOP
        val inst4 = helper.fetchedInstructions.dequeue()
        assert(inst4.pc == 0x0C && inst4.instruction == nop(), "Fourth instruction should be NOP at PC 0xC")
        assert(!inst4.isBranch && !inst4.isJump, "NOP should not be a branch or jump")
        
        println("Test 'FetchPipeline - Predecoder and Branch Recognition' PASSED")
      }
  }

  thatsAll()
}
