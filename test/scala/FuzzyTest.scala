// run: testOnly test.scala.AdvancedFuzzyTest
package test.scala

import _root_.test.scala.LA32RInstrBuilder._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import java.io.File
import scala.sys.process.ProcessLogger

/** Configuration for a fuzzing test session.
  * (This remains unchanged)
  */
case class FuzzCfg(
    seed: Long,
    maxCommits: Int,
    maxStaleCommits: Int,
    instCount: Int,
    templateProb: Double,
    iSramBase: BigInt,
    iSramSize: BigInt,
    dSramBase: BigInt,
    dSramSize: BigInt,
    pcSafeLimit: BigInt,
    memCheckSize: Int,
    memFillValue: BigInt
)

/**
 * Manages the state and logic for a co-simulation run.
 * @param dut The device under test.
 * @param cfg The fuzzing configuration.
 */
class CoSimState(dut: LabTestBench, cfg: FuzzCfg) {
  // --- Simulation State ---
  var lastCommitPc: BigInt = 0
  var goldenModelFinalStatus: Option[CVmStatus.Value] = None
  var commitCounter: Long = 0
  var hitTerminationCondition = false
  var terminationReason = ""

  // --- Edge Coverage Tracking ---
  private val edgeCoverage = scala.collection.mutable.Set[(BigInt, BigInt)]()
  private var staleCommitCounter: Long = 0
  
  // --- Stall Detection State ---
  var lastCommitCycle: Long = 0
  val stallThresholdCycles: Long = 500 // Consider DUT stalled if no commit for this many cycles.

  /**
   * Processes a single valid instruction commit from the DUT.
   * This function contains the core logic that was previously in onSamplings.
   */
  def processCommit(currentCommitPc: BigInt, currentCycle: Long): Unit = {
    // 1. Update counters
    commitCounter += 1
    staleCommitCounter += 1
    lastCommitCycle = currentCycle // Update the cycle of the last commit

    // 2. Check for termination conditions
    if (commitCounter > cfg.maxCommits) {
      terminate(s"Reached maximum commit limit of ${cfg.maxCommits}.")
    } else {
      val newEdge = (lastCommitPc, currentCommitPc)
      if (!edgeCoverage.contains(newEdge)) {
        edgeCoverage.add(newEdge)
        staleCommitCounter = 0 // Reset on new edge
      }
    }
    if (staleCommitCounter > cfg.maxStaleCommits) {
      terminate(s"No new edge coverage in ${cfg.maxStaleCommits} commits. Likely in a tight loop.")
    }

    // 3. Co-simulate with Golden Model (if not already terminated)
    if (!hitTerminationCondition) {
      lastCommitPc = currentCommitPc // Update last PC for the next edge
      
      assert(currentCommitPc < cfg.pcSafeLimit, s"PC Out of Bounds! PC=0x${currentCommitPc.toString(16)}, Limit=0x${cfg.pcSafeLimit.toString(16)}")

      if (goldenModelFinalStatus.isEmpty) {
        val vmStatus = GoldenModel.stepUntil(currentCommitPc)
        checkGprs(currentCommitPc) // GPR check is now in its own function
        
        vmStatus match {
          case CVmStatus.Halted =>
            terminate(s"Golden Model halted naturally at PC 0x${currentCommitPc.toString(16)}.")
            goldenModelFinalStatus = Some(CVmStatus.Halted)
          case CVmStatus.Error =>
            terminate(s"Golden Model errored unexpectedly at PC 0x${currentCommitPc.toString(16)}.")
            goldenModelFinalStatus = Some(CVmStatus.Error)
            assert(false, terminationReason) // Fail fast
          case _ => // Running or Idle
        }
      }
    }
  }
  
  /** Checks for DUT stall (no commits for a long time). Called every cycle. */
  def checkStall(currentCycle: Long): Unit = {
    if (!hitTerminationCondition && (currentCycle - lastCommitCycle > stallThresholdCycles)) {
      terminate(s"DUT has not committed any instruction for ${stallThresholdCycles} cycles. DUT stalled.")
      // In a stall scenario, we can't be sure of the final state, but it's an error.
      assert(false, terminationReason)
    }
  }
  
  /** Performs GPR comparison between DUT and Golden Model. */
  private def checkGprs(pc: BigInt): Unit = {
    val dutGprs = dut.dut.simArchGprs.get.map(_.toBigInt)
    val goldenGprs = GoldenModel.getAllGprs()
    if (!dutGprs.slice(1, 32).sameElements(goldenGprs.slice(1, 32))) {
      println(s"\n!!! GPR MISMATCH at PC 0x${pc.toString(16)} !!!")
      (1 until 32).foreach { k =>
        if (dutGprs(k) != goldenGprs(k))
          println(s"r$k%-3d| DUT: 0x${dutGprs(k).toString(16)}, Golden: 0x${goldenGprs(k).toString(16)}")
      }
      assert(false, "GPR state mismatch.")
    }
  }

  /** Centralized function to set the termination flag and reason. */
  def terminate(reason: String): Unit = {
    if (!hitTerminationCondition) { // Ensure termination reason is set only once
      hitTerminationCondition = true
      terminationReason = reason
    }
  }
}

/** Provides concise, powerful utilities for fuzz testing.
  * All helper methods are organized here to keep test cases clean.
  */
object Fuzz {
  // NOTE: genInstStream is now removed from here and lives in ProgramGenerator.scala

  /** Prepares a fully-sized memory initialization sequence. */
  def prepMemData(
      content: Seq[BigInt],
      totalWordCount: BigInt,
      paddingValue: BigInt
  ): Seq[BigInt] = {
    require(content.length <= totalWordCount)
    content ++ Seq.fill(totalWordCount.toInt - content.length)(paddingValue)
  }

  /** Dumps instructions to a binary file and also creates a human-readable
    * disassembly file using an external objdump tool.
    */
  def dumpAndDisasm(
      instructions: Seq[BigInt],
      baseName: String,
      objdumpPath: String
  ): Unit = {
    val binFileName = s"$baseName.bin"
    val asmFileName = s"$baseName.S"

    // Step 1: Dump the binary file
    LabHelper.dumpBinary(instructions, binFileName)
    println(s"Dumped ${instructions.length} instructions to $binFileName")

    // Step 2: Run objdump for disassembly
    val objdumpFile = new File(objdumpPath)
    if (!objdumpFile.exists() || !objdumpFile.canExecute) {
      println(s"Warning: objdump not found or not executable at '$objdumpPath'. Skipping disassembly.")
      return
    }
    try {
      val command = Seq(objdumpPath, "-D", "-b", "binary", "-m", "loongarch", binFileName)
      val processLogger = ProcessLogger(_ => (), err => println(s"objdump stderr: $err"))
      import scala.sys.process._
      if ((command #> new File(asmFileName)).!(processLogger) == 0) {
        println(s"Successfully disassembled to $asmFileName")
      } else {
        println(s"Warning: objdump failed when disassembling $binFileName.")
      }
    } catch {
      case e: Exception => println(s"An exception occurred while trying to run objdump: ${e.getMessage}")
    }
  }

    /** Runs the core co-simulation loop, with stall detection and clear state management. */
  def runCoSim(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    val cd = dut.clockDomain
    val state = new CoSimState(dut, cfg)

    // Fork a concurrent process for stall detection
    val stallDetector = fork {
      while (!state.hitTerminationCondition) {
        state.checkStall(simTime())
        cd.waitSampling()
      }
    }

    // Main commit processing loop
    cd.onSamplings {
      val comm = dut.commitLog(0)
      if (comm.doCommit.toBoolean && !state.hitTerminationCondition) {
        val currentCommitPc = comm.pc.toBigInt
        if (currentCommitPc != state.lastCommitPc) {
          state.processCommit(currentCommitPc, simTime())
        }
      }
      // Check if DUT continues after GM halted
      if (state.goldenModelFinalStatus.contains(CVmStatus.Halted) && comm.doCommit.toBoolean) {
        state.terminate(s"DUT is still committing (PC: 0x${comm.pc.toBigInt.toString(16)}) after Golden Model halted!")
        assert(false, state.terminationReason)
      }
    }

    // Wait until any termination condition is met
    cd.waitSamplingWhere(state.hitTerminationCondition)
    println(s"INFO: Test terminated. Reason: ${state.terminationReason}")
    
    // Cleanly stop the stall detector process
    stallDetector.join()

    // Allow pipeline to flush
    cd.waitSampling(500)

    // Final Status Check
    val finalStatus = GoldenModel.getStatus()
    if (state.terminationReason.contains("naturally")) {
        assert(finalStatus == CVmStatus.Halted, s"Program should have halted naturally, but final status is $finalStatus")
    } else {
        println(s"Final Golden Model status after forceful termination: $finalStatus. This is acceptable.")
    }

    println("GPR co-simulation phase passed successfully.")
  }

  /** Performs a final, full memory consistency check. */
  def checkMemDump(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    println(s"--- Starting final memory consistency check (${cfg.memCheckSize} bytes) ---")
    val cd = dut.clockDomain

    val goldenMem = new Array[Byte](cfg.memCheckSize)
    val readStatus = GoldenModel.readMemory(cfg.dSramBase.toInt, goldenMem)
    assert(readStatus == 0, s"Golden Model memory read failed with status $readStatus")

    val dutMem = new Array[Byte](cfg.memCheckSize)
    for (i <- 0 until cfg.memCheckSize by 4) {
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= i
      cd.waitSampling()
      val word = dut.dSram.io.tb_readData.toBigInt
      dutMem(i + 3) = ((word >> 24) & 0xff).toByte
      dutMem(i + 2) = ((word >> 16) & 0xff).toByte
      dutMem(i + 1) = ((word >> 8) & 0xff).toByte
      dutMem(i) = (word & 0xff).toByte
    }
    dut.dSram.io.tb_readEnable #= false

    if (!dutMem.sameElements(goldenMem)) {
      println("\n!!! MEMORY MISMATCH DETECTED !!!")
      var mismatchesFound = 0
      for (i <- 0 until cfg.memCheckSize) {
        if (dutMem(i) != goldenMem(i) && mismatchesFound < 20) {
          mismatchesFound += 1
          val address = cfg.dSramBase + i
          println(f"  - Address 0x$address%x: DUT=0x${dutMem(i) & 0xff}%02x, Golden=0x${goldenMem(i) & 0xff}%02x")
        }
      }
      assert(false, "Final memory consistency check FAILED.")
    }
    println("--- Memory consistency check PASSED ---")
  }
}

/** Main test suite for advanced fuzzing.
  * Uses the Fuzz helper object to keep test logic clean and declarative.
  */
class FuzzyTest extends CustomSpinalSimFunSuite {

  // Define the main test configuration in one place
  val objdumpPath =
    "../la32r-vm/tools/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin/loongarch32r-linux-gnusf-objdump"
  val mainFuzzCfg = FuzzCfg(
    seed = 1,
    instCount = 8000,
    templateProb = 0.1, // This is no longer used by ProgramGenerator, but kept for legacy
    iSramBase = BigInt("80000000", 16),
    iSramSize = 4 * 1024 * 1024,
    dSramBase = BigInt("80400000", 16),
    dSramSize = 4 * 1024 * 1024,
    pcSafeLimit = BigInt("81000000", 16), // Increased safe limit
    memCheckSize = 8 * 1024,
    maxCommits = 10000, 
    maxStaleCommits = 100,
    memFillValue = BigInt("DEADBEEF", 16)
  )

  test(s"Advanced Fuzz Test (seed=${mainFuzzCfg.seed})") {

    val rand = new Random(mainFuzzCfg.seed)

    // --- SETUP ---
    println("--- Test Setup Phase ---")
    
    // 1. Generate instructions using the external ProgramGenerator
    println(s"Generating program with seed: ${mainFuzzCfg.seed}")
    val instructions = ProgramGenerator.generate(mainFuzzCfg, rand)
    
    // The rest of the test setup remains the same
    val iSramContent = Fuzz.prepMemData(instructions, mainFuzzCfg.iSramSize / 4, 0)
    val dSramContent = Fuzz.prepMemData(Seq.empty, mainFuzzCfg.dSramSize / 4, mainFuzzCfg.memFillValue)
    println(s"Generated ${instructions.length} instructions, prepared full memory content.")

    val baseFileName = s"bin/advanced_fuzzy_test_seed_${mainFuzzCfg.seed}"
    Fuzz.dumpAndDisasm(instructions, baseFileName, objdumpPath)

    // 2. Initialize Golden Model
    val memoryMap =
      Seq((mainFuzzCfg.iSramBase, mainFuzzCfg.iSramSize), (mainFuzzCfg.dSramBase, mainFuzzCfg.dSramSize))

    GoldenModel.initialize(mainFuzzCfg.iSramBase.toInt, memoryMap)

    def toBytes(words: Seq[BigInt]): Array[Byte] = words.flatMap { w =>
      val bytes = new Array[Byte](4)
      bytes(3) = ((w >> 24) & 0xff).toByte
      bytes(2) = ((w >> 16) & 0xff).toByte
      bytes(1) = ((w >> 8) & 0xff).toByte
      bytes(0) = (w & 0xff).toByte
      bytes
    }.toArray

    GoldenModel.initMemory(mainFuzzCfg.iSramBase.toInt, toBytes(iSramContent))
    GoldenModel.initMemory(mainFuzzCfg.dSramBase.toInt, toBytes(dSramContent))
    println("Golden Model initialized.")

    // 3. Compile DUT
    val compiled = SimConfig.withFstWave.compile(
      new LabTestBench(
        iDataWords = iSramContent,
        dDataWords = dSramContent
      )
    )
    println("DUT compiled.")

    // --- EXECUTION & VERIFICATION ---
    compiled.doSim(s"FuzzTest_seed_${mainFuzzCfg.seed}") { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(2000000) // Increased timeout for potentially longer programs
      println("--- Simulation Started ---")

      // 4. Run the main co-simulation loop
      Fuzz.runCoSim(dut, mainFuzzCfg)

      // 5. Run the final memory check
      Fuzz.checkMemDump(dut, mainFuzzCfg)

      println(s"\n>>> Advanced Fuzz Test PASSED! (seed=${mainFuzzCfg.seed}) <<<")
    }

    // --- CLEANUP ---
    GoldenModel.destroy()
    println("--- Test Finished ---")
  }

  thatsAll
}
