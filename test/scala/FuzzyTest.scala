// run: testOnly test.scala.FuzzyTest
package test.scala

import _root_.test.scala.LA32RInstrBuilder._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import java.io.File
import scala.sys.process.ProcessLogger

/**
 * Configuration for a fuzzing test session.
 */
case class FuzzCfg(
    seed: Long,
    instCount: Int,
    maxCommits: Long,
    maxStaleCommits: Long,
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
  val stallThresholdCycles: Long = 1000 // Consider DUT stalled if no commit for this many cycles.
  var hasCommittedOnce: Boolean = false

  /**
   * Processes a single valid instruction commit from the DUT.
   */
  def processCommit(currentCommitPc: BigInt): Unit = {
    // Set the warmup flag on the very first commit
    if (!hasCommittedOnce) {
        hasCommittedOnce = true
        println("INFO: First instruction committed. Stall detector is now active.")
    }

    // 1. Update counters
    commitCounter += 1
    staleCommitCounter += 1

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
        checkGprs(currentCommitPc)
        
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
  
  /** Performs GPR comparison between DUT and Golden Model. */
  private def checkGprs(pc: BigInt): Unit = {
    val dutGprs = dut.dut.simArchGprs.get.map(_.toBigInt)
    val goldenGprs = GoldenModel.getAllGprs()
    if (!dutGprs.slice(1, 32).sameElements(goldenGprs.slice(1, 32))) {
      println(s"\n!!! GPR MISMATCH at PC 0x${pc.toString(16)} !!!")
      (1 until 32).foreach { k =>
        if (dutGprs(k) != goldenGprs(k)) {
          val dutHex = s"0x${dutGprs(k).toString(16).reverse.padTo(8, '0').reverse}"
          val gldHex = s"0x${goldenGprs(k).toString(16).reverse.padTo(8, '0').reverse}"
          println(f"r$k%-3d| DUT: $dutHex, Golden: $gldHex <- MISMATCH")
        }
      }
      assert(false, "GPR state mismatch.")
    }
  }

  /** Centralized function to set the termination flag and reason. */
  def terminate(reason: String): Unit = {
    if (!hitTerminationCondition) {
      hitTerminationCondition = true
      terminationReason = reason
    }
  }
}

/**
 * Provides concise, powerful utilities for fuzz testing.
 */
object Fuzz {
  
  def prepMemData(content: Seq[BigInt], totalWordCount: BigInt, paddingValue: BigInt): Seq[BigInt] = {
    require(content.length <= totalWordCount)
    content ++ Seq.fill((totalWordCount - content.length).toInt)(paddingValue)
  }

  def dumpAndDisasm(instructions: Seq[BigInt], baseName: String, objdumpPath: String): Unit = {
    val binFileName = s"$baseName.bin"
    val asmFileName = s"$baseName.S"
    LabHelper.dumpBinary(instructions, binFileName)
    println(s"Dumped ${instructions.length} instructions to $binFileName")
    val objdumpFile = new File(objdumpPath)
    if (!objdumpFile.exists() || !objdumpFile.canExecute) {
      println(s"Warning: objdump not found or not executable at '$objdumpPath'. Skipping disassembly.")
      return
    }
    try {
      val command = Seq(objdumpPath, "-D", "-b", "binary", "-m", "loongarch", binFileName)
      val processLogger = ProcessLogger(_ => (), err => println(s"objdump stderr: $err"))
      import scala.sys.process._
      if ((command #> new File(asmFileName)).!(processLogger) != 0) {
        println(s"Warning: objdump failed when disassembling $binFileName.")
      } else {
        println(s"Successfully disassembled to $asmFileName")
      }
    } catch {
      case e: Exception => println(s"An exception occurred while running objdump: ${e.getMessage}")
    }
  }

  def runCoSim(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    val cd = dut.clockDomain
    val state = new CoSimState(dut, cfg)

    // Fork a concurrent process for stall detection
    val stallDetector = fork {
      var lastCommitCount = state.commitCounter
      var cyclesWithoutCommit = 0
      while (!state.hitTerminationCondition) {
        cd.waitSampling()
        if (state.hasCommittedOnce) { // Stall detector is active only after the first commit
          if (state.commitCounter == lastCommitCount) {
            cyclesWithoutCommit += 1
          } else {
            lastCommitCount = state.commitCounter
            cyclesWithoutCommit = 0
          }
          if (cyclesWithoutCommit > state.stallThresholdCycles) {
            state.terminate(s"DUT has not committed any instruction for ${state.stallThresholdCycles} cycles. DUT stalled.")
            assert(false, state.terminationReason)
          }
        }
      }
    }

    // Main commit processing loop
    cd.onSamplings {
      val comm = dut.commitLog(0)
      if (comm.doCommit.toBoolean && !state.hitTerminationCondition) {
        val currentCommitPc = comm.pc.toBigInt
        if (currentCommitPc != state.lastCommitPc) {
          state.processCommit(currentCommitPc)
        }
      }
      if (state.goldenModelFinalStatus.contains(CVmStatus.Halted) && comm.doCommit.toBoolean) {
        state.terminate(s"DUT is still committing (PC: 0x${comm.pc.toBigInt.toString(16)}) after Golden Model has halted!")
        assert(false, state.terminationReason)
      }
    }

    cd.waitSamplingWhere(state.hitTerminationCondition)
    println(s"INFO: Test terminated. Reason: ${state.terminationReason}")
    stallDetector.join()
    cd.waitSampling(500)

    val finalStatus = GoldenModel.getStatus()
    if (state.terminationReason.contains("naturally")) {
      assert(finalStatus == CVmStatus.Halted, s"Program should have halted naturally, but final status is $finalStatus")
    } else {
      println(s"Final Golden Model status after forceful termination: $finalStatus. This is acceptable.")
    }
    println("GPR co-simulation phase passed successfully.")
  }

  def checkMemDump(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    println(s"--- Starting final memory consistency check (${cfg.memCheckSize} bytes) ---")
    val cd = dut.clockDomain
    val goldenMem = new Array[Byte](cfg.memCheckSize)
    assert(GoldenModel.readMemory(cfg.dSramBase.toInt, goldenMem) == 0, "Golden Model memory read failed.")
    val dutMem = new Array[Byte](cfg.memCheckSize)
    for (i <- 0 until cfg.memCheckSize by 4) {
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= i
      cd.waitSampling()
      val word = dut.dSram.io.tb_readData.toBigInt
      dutMem(i + 3) = ((word >> 24) & 0xff).toByte; dutMem(i + 2) = ((word >> 16) & 0xff).toByte
      dutMem(i + 1) = ((word >> 8) & 0xff).toByte; dutMem(i) = (word & 0xff).toByte
    }
    dut.dSram.io.tb_readEnable #= false
    if (!dutMem.sameElements(goldenMem)) {
      println("\n!!! MEMORY MISMATCH DETECTED !!!")
      var mismatchesFound = 0
      (0 until cfg.memCheckSize).foreach { i =>
        if (dutMem(i) != goldenMem(i) && mismatchesFound < 20) {
          mismatchesFound += 1
          println(f"  - Address 0x${cfg.dSramBase + i}%x: DUT=0x${dutMem(i) & 0xff}%02x, Golden=0x${goldenMem(i) & 0xff}%02x")
        }
      }
      assert(false, "Final memory consistency check FAILED.")
    }
    println("--- Memory consistency check PASSED ---")
  }
}

/**
 * Main test suite for advanced fuzzing.
 */
class FuzzyTest extends CustomSpinalSimFunSuite {

  val objdumpPath = "../la32r-vm/tools/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin/loongarch32r-linux-gnusf-objdump"
  val mainFuzzCfg = FuzzCfg(
    seed = 2,
    instCount = 8000,
    maxCommits = 8000,
    maxStaleCommits = 500,
    iSramBase = BigInt("80000000", 16),
    iSramSize = 4 * 1024 * 1024,
    dSramBase = BigInt("80400000", 16),
    dSramSize = 4 * 1024 * 1024,
    pcSafeLimit = BigInt("81000000", 16),
    memCheckSize = 8 * 1024,
    memFillValue = BigInt("DEADBEEF", 16)
  )

  test(s"Advanced Fuzz Test (seed=${mainFuzzCfg.seed})") {
    val rand = new Random(mainFuzzCfg.seed)
    println(s"--- Test Setup Phase (seed=${mainFuzzCfg.seed}) ---")

    val instructions = ProgramGenerator.generate(mainFuzzCfg, rand)
    val iSramContent = Fuzz.prepMemData(instructions, mainFuzzCfg.iSramSize / 4, 0)
    val dSramContent = Fuzz.prepMemData(Seq.empty, mainFuzzCfg.dSramSize / 4, mainFuzzCfg.memFillValue)
    
    val baseFileName = s"bin/advanced_fuzzy_test_seed_${mainFuzzCfg.seed}"
    Fuzz.dumpAndDisasm(instructions, baseFileName, objdumpPath)

    val memoryMap = Seq((mainFuzzCfg.iSramBase, mainFuzzCfg.iSramSize), (mainFuzzCfg.dSramBase, mainFuzzCfg.dSramSize))
    GoldenModel.initialize(mainFuzzCfg.iSramBase.toInt, memoryMap)

    def toBytes(words: Seq[BigInt]): Array[Byte] = words.flatMap { w =>
      Array((w & 0xff).toByte, ((w >> 8) & 0xff).toByte, ((w >> 16) & 0xff).toByte, ((w >> 24) & 0xff).toByte)
    }.toArray

    GoldenModel.initMemory(mainFuzzCfg.iSramBase.toInt, toBytes(iSramContent))
    GoldenModel.initMemory(mainFuzzCfg.dSramBase.toInt, toBytes(dSramContent))
    println("Golden Model initialized.")

    val compiled = SimConfig.withFstWave.compile(
      new LabTestBench(iDataWords = iSramContent, dDataWords = dSramContent)
    )
    println("DUT compiled.")

    compiled.doSim(s"FuzzTest_seed_${mainFuzzCfg.seed}") { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(4000000) // Increased timeout
      println("--- Simulation Started ---")

      Fuzz.runCoSim(dut, mainFuzzCfg)
      Fuzz.checkMemDump(dut, mainFuzzCfg)

      println(s"\n>>> Advanced Fuzz Test PASSED! (seed=${mainFuzzCfg.seed}) <<<")
    }

    GoldenModel.destroy()
    println("--- Test Finished ---")
  }

  thatsAll
}
