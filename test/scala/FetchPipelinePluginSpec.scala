// filename: test/scala/fetch/FetchPipelinePluginSpec.scala
// cmd: testOnly test.scala.fetch.FetchPipelinePluginSpec

package test.scala.fetch

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
import test.scala.lsu.TestOnlyMemSystemPlugin
import spinal.lib.bus.amba4.axi.Axi4Config
import test.scala.LA32RInstrBuilder._

import scala.collection.mutable
import parallax.components.bpu.BpuPipelinePlugin

// =========================================================================
//  Test Bench Setup (Restored and Corrected)
// =========================================================================

/** Test IO bundle for the FetchPipeline. */
case class FetchPipelineTestBenchIo(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig) extends Bundle {
  // --- To FetchPipeline (and its dependencies) ---
  val bpuUpdate = slave(Stream(BpuUpdate(pCfg)))
  val redirect = slave(Flow(UInt(pCfg.pcWidth)))

  // --- From FetchPipeline ---
  val fetchOutput = master(Stream(IFetchRsp(ifuCfg)))
}

/** A test-only plugin that connects the real BPU and Fetch services to the
  * top-level test bench IOs for external driving and monitoring.
  */
class FetchTestSetupPlugin(io: FetchPipelineTestBenchIo) extends Plugin {
  val setup = create early new Area {
    // Get handles to the real services
    val bpuService = getService[BpuService]
    val fetchService = getService[FetchPipelineService]
    val dcService = getService[DataCacheService]

    // BPU Connection: Connect the update port from the testbench.
    bpuService.newBpuUpdatePort() <> io.bpuUpdate

    // FetchPipeline Connections
    fetchService.getRedirectPort() <> io.redirect
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
      new BpuPipelinePlugin(pCfg), // BPU is a dependency for FetchPipeline
      new FetchPipelinePlugin(pCfg, ifuCfg, bufferDepth), // The DUT
      new FetchTestSetupPlugin(io)
    )
  )
}

// =========================================================================
//  Test Helper (Restored and Corrected for Group-based Verification)
// =========================================================================

class FetchTestHelper(dut: FetchPipelineTestBench)(implicit cd: ClockDomain) {
  val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
  val receivedGroups = mutable.Queue[IFetchRspCapture]()

  def init(): Unit = {
    dut.io.bpuUpdate.valid #= false
    dut.io.redirect.valid #= false
    dut.io.fetchOutput.ready #= false // Start with the backend stalled
    cd.waitSampling()
  }

  def startMonitor(): Unit = {
    // Monitor captures the group output whenever it's valid and ready
    StreamMonitor(dut.io.fetchOutput, cd) { payload =>
      receivedGroups.enqueue(IFetchRspCapture(payload))
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
    dut.io.bpuUpdate.payload.pc       #= pc
    dut.io.bpuUpdate.payload.target   #= target
    dut.io.bpuUpdate.payload.isTaken  #= isTaken
    cd.waitSampling()
    dut.io.bpuUpdate.valid #= false
  }

  def expectGroup(expected: IFetchRspCapture, timeout: Int = 400): Unit = {
    // Set ready to true to consume one group
    dut.io.fetchOutput.ready #= true
    cd.waitSamplingWhere(timeout = timeout)(receivedGroups.nonEmpty)
    dut.io.fetchOutput.ready #= false // Stall again after consumption

    val received = receivedGroups.dequeue()
    assert(received.pc == expected.pc, s"PC Mismatch! Expected 0x${expected.pc.toString(16)}, Got 0x${received.pc.toString(16)}")
    assert(received.fault == expected.fault, s"Fault Mismatch! Expected ${expected.fault}, Got ${received.fault}")
    assert(received.validMask == expected.validMask, s"ValidMask Mismatch! Expected 0b${expected.validMask.toString(2)}, Got 0b${received.validMask.toString(2)}")
    assert(received.instructions == expected.instructions, "Instruction Mismatch!")
    assert(received.predecodeInfo == expected.predecodeInfo, s"PredecodeInfo Mismatch! Expected ${expected.predecodeInfo}, Got ${received.predecodeInfo}")
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
    pCfg = pCfg,
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

  test("FetchPipeline - Sequential Group Fetch") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()

      // Write two sequential instruction groups to memory
      val group1 = Seq(nop(), beq(1, 2, 16))
      val group2 = Seq(b(-8), nop())
      helper.writeInstructionsToMem(0x0, group1)
      helper.writeInstructionsToMem(0x8, group2) // Next group addr = 0x0 + 8 bytes

      helper.startMonitor()

      // The pipeline should start from the reset vector (0x0) and fetch the first group
      helper.expectGroup(IFetchRspCapture(0x0, group1, false, 3, Seq((false, false), (true, false))))
      
      // Then, it should automatically fetch the next sequential group
      helper.expectGroup(IFetchRspCapture(0x8, group2, false, 1, Seq((false, true), (false, false))))
    }
  }

  test("FetchPipeline - Redirect (Flush)") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()
      
      // Setup memory at different locations
      helper.writeInstructionsToMem(0x0, Seq(nop(), nop()))
      helper.writeInstructionsToMem(0x1000, Seq(b(0), nop()))
      
      helper.startMonitor()
      
      // Let the first group (from reset vector) be fetched and consumed
      helper.expectGroup(IFetchRspCapture(0x0, Seq(nop(), nop()), false, 3, Seq((false, false), (false, false))))
      
      // While the pipeline is about to fetch 0x8, redirect it to 0x1000
      helper.issueRedirect(0x1000)
      
      // Now, expect the group from the new, redirected PC
      helper.expectGroup(IFetchRspCapture(0x1000, Seq(b(0), nop()), false, 1, Seq((false, true), (false, false))))

      // Check for stale instructions from the old path
      cd.waitSampling(50)
      assert(helper.receivedGroups.isEmpty, "Pipeline did not flush correctly!")
    }
  }

  testOnly("FetchPipeline - BPU Prediction Taken") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()

      // 1. Prime the BPU with a "Taken" prediction for PC 0x0, pointing to 0x2000.
      helper.updateBpu(pc = 0x0, target = 0x2000, isTaken = true)
      cd.waitSampling(5) // Wait for BPU update to be processed

      // 2. Write instructions to memory
      val groupAtReset = Seq(beq(1, 2, 8), nop())     // A branch at PC=0x0
      val groupAtSeq = Seq(BigInt(0xDEAD), BigInt(0xBEEF)) // At sequential path 0x8 (should NOT be fetched)
      val groupAtTarget = Seq(b(0), nop())             // At predicted target 0x2000
      
      helper.writeInstructionsToMem(0x0, groupAtReset)
      helper.writeInstructionsToMem(0x8, groupAtSeq)
      helper.writeInstructionsToMem(0x2000, groupAtTarget)
      
      helper.startMonitor()

      // 3. Expect the first group from the reset vector
      helper.expectGroup(IFetchRspCapture(0x0, groupAtReset, false, 3, Seq((true, false), (false, false))))

      // 4. CRITICAL: Expect the next group from the BPU's predicted target, not the sequential one
      helper.expectGroup(IFetchRspCapture(0x2000, groupAtTarget, false, 1, Seq((false, true), (false, false))))

      // Verify that the sequential path was indeed skipped
      cd.waitSampling(50)
      assert(helper.receivedGroups.isEmpty, "Pipeline fetched from sequential path instead of BPU predicted path!")
    }
  }

  testOnly("FetchPipeline - Stall and Resume") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth = 2)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()
      
      // Write more groups than the buffer can hold to test backpressure
      val groups = (0 until 4).map(i => Seq(nop(), BigInt(i)))
      groups.zipWithIndex.foreach { case (grp, i) => helper.writeInstructionsToMem(i * 8, grp) }

      helper.startMonitor()
      
      // Keep the backend stalled (ready=false). The buffer should fill up, and PC generation should stop.
      dut.io.fetchOutput.ready #= false
      cd.waitSampling(100)
      assert(helper.receivedGroups.isEmpty, "Instructions were consumed during stall period!")
      
      // Now, resume and consume all groups one by one
      for (i <- 0 until 4) {
        val expectedGroup = groups(i)
        helper.expectGroup(IFetchRspCapture(i * 8, expectedGroup, false, 3, Seq((false,false),(false,false))))
      }
    }
  }
  
  test("FetchPipeline - Back-to-Back Redirects") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()

      // Instructions at three different potential paths
      helper.writeInstructionsToMem(0x0, Seq(nop(), nop()))          // Original path (from reset)
      helper.writeInstructionsToMem(0x1000, Seq(nop(), nop()))       // First redirect target (should be flushed)
      helper.writeInstructionsToMem(0x2000, Seq(b(0), nop())) // Second redirect target (final destination)

      helper.startMonitor()

      // Let the pipeline start fetching from PC=0 but don't consume yet.
      dut.io.fetchOutput.ready #= false
      cd.waitSampling(50)
      assert(helper.receivedGroups.isEmpty, "No instructions should be consumed while stalled.")

      // Issue two redirects in consecutive cycles. The second should override the first.
      helper.issueRedirect(newPc = 0x1000)
      helper.issueRedirect(newPc = 0x2000)
      
      // Now, start consuming. 
      // We should ONLY see the instruction group from the final redirect target (0x2000).
      helper.expectGroup(
        IFetchRspCapture(
            pc = 0x2000,
            instructions = Seq(b(0), nop()),
            fault = false,
            validMask = 1, // Truncated by the 'b(0)' jump
            predecodeInfo = Seq((false, true), (false, false))
        )
      )

      // Verify that no stale packets from 0x0 or 0x1000 made it through.
      cd.waitSampling(50)
      assert(
        helper.receivedGroups.isEmpty,
        s"Pipeline did not flush correctly! Found unexpected groups from old paths: ${helper.receivedGroups.mkString(", ")}"
      )
    }
  }

  // in class FetchPipelinePluginSpec

  test("FetchPipeline - Predecode Info Propagation") {
    SimConfig.withWave.compile(new FetchPipelineTestBench(pCfg, ifuCfg, dCfg, axiConfig, bufferDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      dut.clockDomain.forkStimulus(10)

      val helper = new FetchTestHelper(dut)
      helper.init()

      // Create a program with a mix of instruction types
      val group1 = Seq(nop(), beq(1, 2, 16))
      val group2 = Seq(b(-8), addi_w(1,1,1))
      helper.writeInstructionsToMem(0x0, group1)
      helper.writeInstructionsToMem(0x8, group2)
      
      helper.startMonitor()

      // Expect the first group and verify its predecode info
      helper.expectGroup(
        IFetchRspCapture(
          pc = 0x0,
          instructions = group1,
          fault = false,
          validMask = 3, // Both instructions are valid
          predecodeInfo = Seq(
            (false, false), // nop
            (true, false)   // beq
          )
        )
      )

      // Expect the second group and verify its predecode info
      helper.expectGroup(
        IFetchRspCapture(
          pc = 0x8,
          instructions = group2,
          fault = false,
          validMask = 1, // 'b(-8)' is a jump, so it truncates the next instruction
          predecodeInfo = Seq(
            (false, true),  // b
            (false, false)  // addi_w
          )
        )
      )
    }
  }

  
  thatsAll()
}
