// filename: test/scala/fetch/FetchPipelinePluginSpec.scala
// cmd: testOnly test.scala.fetch.FetchPipelinePluginSpec

package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch._
import parallax.bpu._
import parallax.components.ifu._
import parallax.components.memory._
import parallax.utilities._
import parallax.components.dcache2._
import spinal.lib.bus.amba4.axi.Axi4Config
import LA32RInstrBuilder._
import scala.collection.mutable
import parallax.components.bpu.BpuPipelinePlugin

// =========================================================================
//  Test Bench Setup (Rewritten for single-instruction output)
// =========================================================================

/** Test IO bundle for the new SimpleFetchPipeline. */
case class SimpleFetchPipelineTestBenchIo(pCfg: PipelineConfig) extends Bundle {
  // --- To SimpleFetchPipeline (and its dependencies) ---
  val bpuUpdate = slave(Stream(BpuUpdate(pCfg)))
  val redirect = slave(Flow(UInt(pCfg.pcWidth)))

  // --- From SimpleFetchPipeline ---
  // The output is now a stream of single FetchedInstr
  val fetchOutput = master(Stream(FetchedInstr(pCfg)))
}

/** A test-only plugin connecting services to the top-level IOs. */
class SimpleFetchTestSetupPlugin(io: SimpleFetchPipelineTestBenchIo) extends Plugin {
  val setup = create early new Area {
    // Get handles to the real services, using the new service trait name
    val bpuService = getService[BpuService]
    val fetchService = getService[SimpleFetchPipelineService]
    val dcService = getService[DataCacheService]

    // BPU Connection
    bpuService.newBpuUpdatePort() <> io.bpuUpdate

    // SimpleFetchPipeline Connections
    fetchService.newRedirectPort(0) <> io.redirect
    io.fetchOutput <> fetchService.fetchOutput()

    // D-Cache Connection (unused but needed for service resolution)
    val unusedStorePort = dcService.newStorePort()
    unusedStorePort.cmd.valid := False
    unusedStorePort.cmd.payload.assignDontCare()
  }

  create late new Area {
    setup.bpuService.getBpuResponse().simPublic()
  }
}

/** The top-level DUT for testing the new SimpleFetchPipelinePlugin. */
class SimpleFetchPipelineTestBench(
    val pCfg: PipelineConfig,
    val ifuCfg: InstructionFetchUnitConfig,
    val dCfg: DataCachePluginConfig,
    val axiCfg: Axi4Config,
    fifoDepth: Int
) extends Component {
  // Use the new IO bundle
  val io = SimpleFetchPipelineTestBenchIo(pCfg)
  io.simPublic()

  val framework = new Framework(
    Seq(
      new TestOnlyMemSystemPlugin(axiConfig = axiCfg),
      new DataCachePlugin(dCfg),
      new IFUPlugin(ifuCfg),
      new BpuPipelinePlugin(pCfg),
      // Use the new SimpleFetchPipelinePlugin that outputs single instructions
      new SimpleFetchPipelinePlugin(pCfg, ifuCfg, fifoDepth),
      new SimpleFetchTestSetupPlugin(io)
    )
  )
}

// =========================================================================
//  Test Helper (Rewritten for single-instruction verification)
// =========================================================================

/** Helper class for verifying single instruction outputs */
class SimpleFetchTestHelper(dut: SimpleFetchPipelineTestBench)(implicit cd: ClockDomain) {
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
  // The queue now holds FetchedInstr objects
  val receivedInstrs = mutable.Queue[FetchedInstrCapture]()

  def init(): Unit = {
    dut.io.bpuUpdate.valid #= false
    dut.io.redirect.valid #= false
    dut.io.fetchOutput.ready #= false // Start with the backend stalled
    cd.waitSampling()
  }

  def startMonitor(): Unit = {
    // Monitor captures single instructions whenever they are fired
    StreamMonitor(dut.io.fetchOutput, cd) { payload =>
      // Clone the payload to avoid race conditions with simulation state changes
      receivedInstrs.enqueue(FetchedInstrCapture(payload))
    }
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

  def updateBpu(pc: BigInt, target: BigInt, isTaken: Boolean): Unit = {
    dut.io.bpuUpdate.valid #= true
    dut.io.bpuUpdate.payload.pc #= pc
    dut.io.bpuUpdate.payload.target #= target
    dut.io.bpuUpdate.payload.isTaken #= isTaken
    cd.waitSampling()
    dut.io.bpuUpdate.valid #= false
  }

  /** Verifies a single instruction. */
  def expectInstr(
      expectedPc: BigInt,
      expectedInst: BigInt,
      expectedIsBranch: Boolean,
      expectedIsJump: Boolean,
      timeout: Int = 400
  ): Unit = {
    dut.io.fetchOutput.ready #= true
    cd.waitSamplingWhere(timeout = timeout)(receivedInstrs.nonEmpty)
    dut.io.fetchOutput.ready #= false // Stall again after consumption

    val received = receivedInstrs.dequeue()
    assert(
      received.pc.toBigInt == expectedPc,
      f"PC Mismatch! Expected 0x${expectedPc}%x, Got 0x${received.pc.toBigInt}%x"
    )
    assert(
      received.instruction.toBigInt == expectedInst,
      f"Instruction Mismatch! Expected 0x${expectedInst}%x, Got 0x${received.instruction.toBigInt}%x"
    )
    assert(
      received.isBranch == expectedIsBranch,
      s"isBranch Mismatch! Expected ${expectedIsBranch}, Got ${received.isBranch}"
    )
    assert(
      received.isJump == expectedIsJump,
      s"isJump Mismatch! Expected ${expectedIsJump}, Got ${received.isJump}"
    )
  }

  /** Helper to verify a sequence of instructions. */
  def expectInstrs(instructions: (BigInt, BigInt, Boolean, Boolean)*): Unit = {
    for ((pc, inst, isBranch, isJump) <- instructions) {
      expectInstr(pc, inst, isBranch, isJump)
    }
  }
}

class SimpleFetchPipelinePluginSpec extends CustomSpinalSimFunSuite {

  // --- Configuration constants (unchanged) ---
  val pCfg = PipelineConfig(
    xlen = 32,
    fetchWidth = 2,
    resetVector = BigInt("00000000", 16),
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
    transactionIdWidth = pCfg.transactionIdWidth,
  )
  val minimalDCacheParams = DataCachePluginConfig.toDataCacheParameters(dCfg)
  val ifuCfg = InstructionFetchUnitConfig(
    pCfg = pCfg,
    dcacheParameters = minimalDCacheParams,
    pcWidth = pCfg.pcWidth,
    instructionWidth = pCfg.dataWidth,
    fetchGroupDataWidth = (pCfg.dataWidth.value * pCfg.fetchWidth) bits,
    enableLog = false // Disable log in tests for cleaner output
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
  val fifoDepth = 8

  test("FetchPipeline - Sequential Instruction Fetch") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      // ================== THE FIX ==================
      // The original instruction sequence had a jump `b(-8)` which creates a loop.
      // For a sequential test, we must use non-control-flow instructions.
      val insts = Seq(
          nop(),                // 0x0
          addi_w(1, 1, 1),    // 0x4
          addi_w(2, 2, 2),    // 0x8
          addi_w(3, 3, 3)     // 0xC
      )
      // ===============================================

      helper.writeInstructionsToMem(0x0, insts)

      helper.startMonitor()

      // Expect each instruction to come out one by one, in order
      helper.expectInstrs(
        (0x0, nop(), false, false),
        (0x4, addi_w(1, 1, 1), false, false),
        (0x8, addi_w(2, 2, 2), false, false),
        (0xc, addi_w(3, 3, 3), false, false)
      )
    }
  }

  test("FetchPipeline - Redirect (Hard Flush)") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      helper.writeInstructionsToMem(0x0, Seq(nop(), nop()))
      helper.writeInstructionsToMem(0x1000, Seq(b(0), nop()))

      helper.startMonitor()

      // Consume the first instruction from the reset vector
      helper.expectInstr(0x0, nop(), false, false)

      // While the pipeline has fetched the next instruction (at 0x4) but not yet consumed it,
      // issue a hard redirect.
      helper.issueRedirect(0x1000)

      // Now, expect the instruction from the new, redirected PC. The one at 0x4 should have been flushed.
      // The IFU will fetch the group at 0x1000, but the 'b(0)' will truncate the stream.
      helper.expectInstr(0x1000, b(0), false, true)

      // Verify that no other instructions (like the one at 0x4, or the nop after the jump) come out.
      cd.waitSampling(50)
      assert(
        helper.receivedInstrs.isEmpty,
        s"Pipeline did not flush correctly! Found stale instructions: ${helper.receivedInstrs.mkString(", ")}"
      )
    }
  }

  test("FetchPipeline - BPU Prediction Taken (Soft Redirect)") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      helper.updateBpu(pc = 0x4, target = 0x2000, isTaken = true)
      cd.waitSampling(10)

      val instsReset = Seq(nop(), beq(1, 2, 8)) // PC 0x0, 0x4 (this is the one we predict on)
      val instsSeq = Seq(BigInt(0x0eadbeef), nop()) // PC 0x8, 0xC (should be skipped)
      val instsTarget = Seq(b(0), nop()) // PC 0x2000, 0x2004

      helper.writeInstructionsToMem(0x0, instsReset)
      helper.writeInstructionsToMem(0x8, instsSeq)
      helper.writeInstructionsToMem(0x2000, instsTarget)

      helper.startMonitor()

      // Expect the first two instructions from the reset path
      helper.expectInstr(0x0, nop(), false, false)
      helper.expectInstr(0x4, beq(1, 2, 8), true, false)

      // CRITICAL: Expect the next instruction from the BPU's predicted target (0x2000), not the sequential one (0x8)
      helper.expectInstr(0x2000, b(0), false, true)

      cd.waitSampling(50)
      assert(helper.receivedInstrs.isEmpty, "Pipeline fetched from sequential path instead of BPU predicted path!")
    }
  }

  test("FetchPipeline - Stall and Resume") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth = 4)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      // Write more instructions than the FIFO can hold
      val insts = (0 until 8).map(i => addi_w(1, 1, i))
      helper.writeInstructionsToMem(0x0, insts)

      helper.startMonitor()

      // Keep the backend stalled (ready=false). The FIFO should fill up and cause backpressure.
      dut.io.fetchOutput.ready #= false
      cd.waitSampling(100)
      assert(helper.receivedInstrs.isEmpty, "Instructions were consumed during stall period!")

      // Now, resume and consume all instructions one by one
      for (i <- 0 until 8) {
        helper.expectInstr(i * 4, addi_w(1, 1, i), false, false)
      }
    }
  }

  test("FetchPipeline - Back-to-Back Redirects") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      helper.writeInstructionsToMem(0x0, Seq(nop(), nop()))
      helper.writeInstructionsToMem(0x1000, Seq(nop(), nop()))
      helper.writeInstructionsToMem(0x2000, Seq(b(0), nop()))

      helper.startMonitor()

      // Wait for the pipeline to fetch the first instruction group but don't consume it
      cd.waitSamplingWhere(dut.io.fetchOutput.valid.toBoolean)

      // Issue two redirects in consecutive cycles. The second (harder) one should win.
      helper.issueRedirect(newPc = 0x1000)
      helper.issueRedirect(newPc = 0x2000)

      // Now, start consuming. We should ONLY see the instruction from the final redirect target.
      helper.expectInstr(0x2000, b(0), false, true)

      cd.waitSampling(50)
      assert(
        helper.receivedInstrs.isEmpty,
        s"Pipeline did not flush correctly! Found stale instructions: ${helper.receivedInstrs.mkString(", ")}"
      )
    }
  }

// file: test/scala/fetch/FetchPipelinePluginSpec.scala

  test("FetchPipeline - Predecode Info Propagation") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()
      
      // ================== THE FIX ==================
      // Replaced the unconditional jump `b(-8)` with a non-control-flow instruction
      // to avoid breaking the sequential expectation.
      val insts = Seq(
          nop(),                // 0x0
          beq(1, 2, 16),      // 0x4
          addi_w(1, 1, 1),    // 0x8 (was b(-8))
          addi_w(2, 2, 2)     // 0xC
      )
      // ===============================================

      helper.writeInstructionsToMem(0x0, insts)

      helper.startMonitor()

      helper.expectInstrs(
        (0x0, nop(), false, false),
        (0x4, beq(1, 2, 16), true, false), // beq is a branch
        (0x8, addi_w(1, 1, 1), false, false), // addi_w is not jump/branch
        (0xc, addi_w(2, 2, 2), false, false)
      )
    }
  }

  test("FetchPipeline - BPU Prediction Not Taken") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      // BPU is explicitly told that the branch at 0x4 is NOT taken
      helper.updateBpu(pc = 0x4, target = 0x2000, isTaken = false)
      cd.waitSampling(10)

      val insts = Seq(
        nop(),            // 0x0
        beq(1, 2, 8),     // 0x4 -> BPU predicts NOT taken
        addi_w(1,1,1),  // 0x8 -> This should be executed
        addi_w(2,2,2)   // 0xC -> This should also be executed
      )
      helper.writeInstructionsToMem(0x0, insts)
      helper.writeInstructionsToMem(0x2000, Seq(b(0))) // Should not be reached

      helper.startMonitor()

      // Expect the full sequential path
      helper.expectInstrs(
        (0x0, nop(), false, false),
        (0x4, beq(1, 2, 8), true, false),
        (0x8, addi_w(1,1,1), false, false),
        (0xC, addi_w(2,2,2), false, false)
      )
      
      cd.waitSampling(50)
      assert(helper.receivedInstrs.isEmpty, "Pipeline incorrectly took a not-taken branch!")
    }
  }

    test("FetchPipeline - BPU Prediction vs Hard Flush Conflict") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      // BPU predicts the branch at 0x4 will go to 0x2000
      helper.updateBpu(pc = 0x4, target = 0x2000, isTaken = true)
      cd.waitSampling(10)

      helper.writeInstructionsToMem(0x0, Seq(nop(), beq(1, 2, 8))) // 0x0, 0x4
      helper.writeInstructionsToMem(0x2000, Seq(nop())) // BPU target
      helper.writeInstructionsToMem(0x3000, Seq(b(0)))  // Hard flush target

      helper.startMonitor()

      // Consume the first two instructions, this will trigger the BPU prediction
      helper.expectInstr(0x0, nop(), false, false)
      helper.expectInstr(0x4, beq(1, 2, 8), true, false)
      
      // Wait until BPU response is valid, but before we consume the predicted instruction
      cd.waitSamplingWhere(dut.framework.getService[BpuService].getBpuResponse().valid.toBoolean)

      // NOW, issue a high-priority hard flush from the "backend"
      helper.issueRedirect(0x3000)

      // The hard flush MUST win. The BPU's prediction to 0x2000 should be discarded.
      helper.expectInstr(0x3000, b(0), false, true)

      cd.waitSampling(50)
      assert(helper.receivedInstrs.isEmpty, "Hard flush did not override BPU prediction!")
    }
  }

    test("FetchPipeline - Jump at end of fetch group") {
    // Our fetchWidth is 2, so a fetch group is 8 bytes.
    // We place a branch at the end of the first group (0x4)
    // and a jump at the end of the second group (0xC).
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      helper.updateBpu(pc = 0x4, target = 0x8, isTaken = true)

      val insts = Seq(
        nop(),            // 0x0
        beq(1, 2, 4),     // 0x4 -> Branch to 0x8 (start of next group)
        nop(),            // 0x8
        b(8)              // 0xC -> Jump to 0x14
      )
      helper.writeInstructionsToMem(0x0, insts)
      helper.writeInstructionsToMem(0x14, Seq(addi_w(1,1,1)))

      helper.startMonitor()

      helper.expectInstrs(
        (0x0, nop(), false, false),
        (0x4, beq(1, 2, 4), true, false), // BPU predicts taken, we go to 0x8
        (0x8, nop(), false, false),
        (0xC, b(8), false, true)          // This is an unconditional jump, IFU must not truncate
      )
      
      // After the unconditional jump at 0xC, we should see the instruction from 0x14
      helper.expectInstr(0x14, addi_w(1,1,1), false, false)

      cd.waitSampling(50)
      assert(helper.receivedInstrs.isEmpty, "Incorrect PC calculation after jump.")
    }
  }

    test("FetchPipeline - Soft Redirect after long stall") {
    SimConfig.withWave.compile(new SimpleFetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new SimpleFetchTestHelper(dut)
      helper.init()

      helper.updateBpu(pc = 0x4, target = 0x2000, isTaken = true)

      val insts = Seq(nop(), beq(1,2,4)) // 0x0, 0x4
      helper.writeInstructionsToMem(0x0, insts)
      helper.writeInstructionsToMem(0x2000, Seq(b(0)))

      helper.startMonitor()

      // Consume the first instruction
      helper.expectInstr(0x0, nop(), false, false)

      // Stall the pipeline BEFORE consuming the branch instruction
      dut.io.fetchOutput.ready #= false
      cd.waitSamplingWhere(dut.io.fetchOutput.valid.toBoolean) // Wait for beq to be ready
      
      // Wait for a long time. The BPU response should have arrived and be pending.
      cd.waitSampling(50)
      
      // Now, consume the branch instruction
      helper.expectInstr(0x4, beq(1,2,4), true, false)

      // Immediately after, we should get the predicted instruction, not the sequential one.
      helper.expectInstr(0x2000, b(0), false, true)
    }
  }

  thatsAll()
}
