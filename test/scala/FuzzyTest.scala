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
  * @param seed          Random seed for reproducibility.
  * @param instCount     Total number of instructions to generate.
  * @param templateProb  Probability of inserting a hazard template.
  * @param iSramBase     Base address of instruction memory.
  * @param iSramSize     Size of instruction memory in bytes.
  * @param dSramBase     Base address of data memory.
  * @param dSramSize     Size of data memory in bytes.
  * @param pcSafeLimit   The upper bound for safe PC values.
  * @param memCheckSize  Size of the data memory region to verify at the end.
  * @param memFillValue  The magic value to fill unused data memory with.
  */
case class FuzzCfg(
    seed: Long,
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

/** Provides concise, powerful utilities for fuzz testing.
  * All helper methods are organized here to keep test cases clean.
  */
object Fuzz {

  type InstGen = (BigInt, Random) => BigInt
  type TemplateGen = Random => Seq[BigInt]

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
    *
    * @param instructions The sequence of instructions to dump.
    * @param baseName The base path and name for the output files (e.g., "bin/test1").
    *                 Will create "bin/test1.bin" and "bin/test1.S".
    * @param objdumpPath The full path to the loongarch32r objdump executable.
    */
  def dumpAndDisasm(
      instructions: Seq[BigInt],
      baseName: String,
      objdumpPath: String
  ): Unit = {
    val binFileName = s"$baseName.bin"
    val asmFileName = s"$baseName.S"

    // Step 1: Dump the binary file (using existing LabHelper)
    LabHelper.dumpBinary(instructions, binFileName)
    println(s"Dumped ${instructions.length} instructions to $binFileName")

    // Step 2: Check if objdump tool exists
    val objdumpFile = new File(objdumpPath)
    if (!objdumpFile.exists() || !objdumpFile.canExecute) {
      println(s"Warning: objdump not found or not executable at '$objdumpPath'. Skipping disassembly.")
      return
    }

    // Step 3: Run the objdump command
    try {
      // Command: objdump -D -b binary -m loongarch <bin_file> > <asm_file>
      val command = Seq(
        objdumpPath,
        "-D",
        "-b",
        "binary",
        "-m",
        "loongarch",
        binFileName
      )

      // Execute the command and redirect output to the .S file
      val processLogger = ProcessLogger(
        (output: String) => (), // Suppress standard output on console
        (error: String) => println(s"objdump stderr: $error") // Print errors to console
      )
      import scala.sys.process._

      // The `#>` operator handles the output redirection
      val exitCode = (command #> new File(asmFileName)).!(processLogger)

      if (exitCode == 0) {
        println(s"Successfully disassembled to $asmFileName")
      } else {
        println(s"Warning: objdump failed with exit code $exitCode when disassembling $binFileName.")
      }
    } catch {
      case e: Exception =>
        println(s"An exception occurred while trying to run objdump: ${e.getMessage}")
    }
  }

  /** Generates a stream of random and template-based instructions. */
  /** Generates a stream of random and template-based instructions. */
  def genInstStream(rand: Random, cfg: FuzzCfg): Seq[BigInt] = {
    val insts = new ArrayBuffer[BigInt]()

    // Helper to load a 32-bit immediate value into a register
    def load32bImm(rd: Int, value: BigInt): Seq[BigInt] = {
      val high20 = ((value >> 12) & 0xfffff).toInt
      val low12 = (value & 0xfff).toInt
      Seq(
        lu12i_w(rd, high20),
        ori(rd, rd, low12)
      )
    }

    // Define base and template generators
    val baseGens: Seq[InstGen] = Seq(
      // Arithmetic & Logical
      (_, r) => addi_w(r.nextInt(32), r.nextInt(32), r.nextInt(4096) - 2048),
      (_, r) => add_w(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => sub_w(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => mul_w(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => or(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => and(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => xor(r.nextInt(32), r.nextInt(32), r.nextInt(32)),
      (_, r) => lu12i_w(r.nextInt(32), r.nextInt(1 << 20)),
      // Memory Access
      (_, r) => {
        val baseReg = r.nextInt(31) + 1 // Avoid r0 as base
        st_w(r.nextInt(32), baseReg, (r.nextInt(1024) - 512) & ~3)
      },
      (_, r) => {
        val baseReg = r.nextInt(31) + 1
        ld_w(r.nextInt(32), baseReg, (r.nextInt(1024) - 512) & ~3)
      },
      // Branching (beq/bne)
      // *** FIX START: Ensure branch targets are within valid instruction memory ***
      (pc, r) => {
        // The valid range for instruction indices is [0, instCount].
        // The halt instruction is at index instCount.
        val maxInstIdx = cfg.instCount 

        // Define a reasonable local jump range (e.g., +/- 250 instructions).
        val jumpRange = 250 
        val currentIdx = (pc - cfg.iSramBase).toInt / 4

        // Calculate the safe minimum and maximum destination index for the jump.
        val minDestIdx = math.max(0, currentIdx - jumpRange)
        val maxDestIdx = math.min(maxInstIdx, currentIdx + jumpRange)
        
        // Pick a random valid destination index.
        val destIdx = if (minDestIdx >= maxDestIdx) minDestIdx else minDestIdx + r.nextInt(maxDestIdx - minDestIdx)

        // Calculate the target address and the required offset.
        val targetAddr = cfg.iSramBase + destIdx * 4
        // The offset is relative to the PC of the instruction *following* the branch.
        val offset = (targetAddr - (pc + 4)).toInt 

        // The calculated offset is guaranteed to be safe and within reasonable range for the instruction's immediate field.
        if (r.nextBoolean()) beq(r.nextInt(32), r.nextInt(32), offset) else bne(r.nextInt(32), r.nextInt(32), offset)
      }
      // *** FIX END ***
    )
    
    val templateGens: Seq[TemplateGen] = Seq(
      // Template: Load-Use Hazard (RAW)
      r => {
        val r_base = r.nextInt(28) + 1; val r_dest = r_base + 1; val r_src = r_base + 2
        load32bImm(r_base, cfg.dSramBase) ++
        Seq(
          addi_w(r_src, 0, 0x7ff),
          st_w(r_src, r_base, 0),
          ld_w(rd = r_dest, rj = r_base, offset = 0),
          add_w(rd = r_src, rj = r_dest, rk = r_dest) // Immediate use
        )
      },
      // Template: Store-Load Forwarding (with real delay)
      r => {
        val r_addr = r.nextInt(26) + 1; val r_store = r_addr + 1; val r_load = r_addr + 2
        val r_delay1 = 31; val r_delay2 = 30; val r_delay3 = 29
        val offset = (r.nextInt(128) & ~3)

        load32bImm(r_addr, cfg.dSramBase) ++
          load32bImm(r_store, 0xdeadbeef) ++
          Seq(
            st_w(r_store, r_addr, offset),
            // Real delay instructions
            add_w(r_delay1, r_delay2, r_delay3),
            sub_w(r_delay2, r_delay1, r_delay3),
            xor(r_delay3, r_delay1, r_delay2),
            // Load from the same address after a real delay
            ld_w(r_load, r_addr, offset)
          )
      },
      // *** FIX START: Add a new template for safe JIRL instructions ***
      // Template: Safe Register-Indirect Jump (JIRL)
      r => {
        // Choose a destination register for the link address (PC+4), and a base register for the jump target.
        // Ensure they are not r0.
        val r_link = r.nextInt(31) + 1 
        val r_base = ( (r_link % 30) + 1 ) // A different register from r_link

        // 1. Pick a random, valid target address within the instruction memory.
        val targetIndex = r.nextInt(cfg.instCount + 1) // Can jump to any instruction, including the final halt.
        val targetAddr = cfg.iSramBase + targetIndex * 4

        // 2. Generate instructions to load this safe address into r_base.
        val loadAddrSeq = load32bImm(r_base, targetAddr)

        // 3. Append the jirl instruction, which now jumps to a known-safe address.
        //    jirl rd, rj, offset -> target = GPR[rj] + offset; GPR[rd] = PC + 4
        loadAddrSeq :+ jirl(rd = r_link, rj = r_base, offset = 0)
      }
      // *** FIX END ***
    )

    // Generate instruction stream
    while (insts.length < cfg.instCount) {
      if (rand.nextDouble() < cfg.templateProb && insts.length < cfg.instCount - 20) {
        val template = templateGens(rand.nextInt(templateGens.length))(rand)
        if (template.nonEmpty) insts ++= template
      } else {
        val currentPc = cfg.iSramBase + insts.length * 4
        insts += baseGens(rand.nextInt(baseGens.length))(currentPc, rand)
      }
    }
    // Add halt instruction at the end. All jumps/branches are ensured to target within [0, instCount] indices.
    (insts.toSeq.take(cfg.instCount)) :+ beq(0, 0, 0) 
  }

  /** Runs the core co-simulation loop, checking GPRs at each step. */
  def runCoSim(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    val cd = dut.clockDomain
    var lastCommitPc = BigInt(0)

    cd.onSamplings {
      val comm = dut.commitLog(0)
      if (comm.doCommit.toBoolean && comm.pc.toBigInt != lastCommitPc) {
        val commitPc = comm.pc.toBigInt
        lastCommitPc = commitPc

        assert(commitPc < cfg.pcSafeLimit, f"PC Out of Bounds! PC=0x$commitPc%x, Limit=0x${cfg.pcSafeLimit}%x")

        GoldenModel.stepUntil(commitPc)

        val dutGprs = dut.dut.simArchGprs.get.map(_.toBigInt)
        val goldenGprs = GoldenModel.getAllGprs()

        // Compare GPRs (r1 to r31)
        if (!dutGprs.slice(1, 32).sameElements(goldenGprs.slice(1, 32))) {
          println(f"\n!!! GPR MISMATCH at PC 0x$commitPc%x !!!")
          (1 until 32).foreach { k =>
            if (dutGprs(k) != goldenGprs(k))
              println(f"r$k%-3d| DUT: 0x${dutGprs(k)}%08x, Golden: 0x${goldenGprs(k)}%08x <- MISMATCH")
          }
          assert(false, "GPR state mismatch. Halting test.")
        }
      }
    }

    // Wait until the simulation reaches the halt instruction or times out
    val haltPc = cfg.iSramBase + cfg.instCount * 4
    cd.waitSamplingWhere(dut.commitLog(0).pc.toBigInt >= haltPc)
    cd.waitSampling(500) // Allow pipeline to flush
    println("GPR co-simulation phase passed successfully.")
  }

  /** Performs a final, full memory consistency check. */
  def checkMemDump(dut: LabTestBench, cfg: FuzzCfg): Unit = {
    println(s"--- Starting final memory consistency check (${cfg.memCheckSize} bytes) ---")
    val cd = dut.clockDomain

    // 1. Get memory content from Golden Model
    val goldenMem = new Array[Byte](cfg.memCheckSize)
    val readStatus = GoldenModel.readMemory(cfg.dSramBase.toInt, goldenMem)
    assert(readStatus == 0, s"Golden Model memory read failed with status $readStatus")

    // 2. Get memory content from DUT's SRAM
    val dutMem = new Array[Byte](cfg.memCheckSize)
    for (i <- 0 until cfg.memCheckSize by 4) {
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= i
      cd.waitSampling()
      val word = dut.dSram.io.tb_readData.toBigInt
      dutMem(i) = (word & 0xff).toByte
      dutMem(i + 1) = ((word >> 8) & 0xff).toByte
      dutMem(i + 2) = ((word >> 16) & 0xff).toByte
      dutMem(i + 3) = ((word >> 24) & 0xff).toByte
    }
    dut.dSram.io.tb_readEnable #= false

    // 3. Compare the two memory dumps
    if (!dutMem.sameElements(goldenMem)) {
      println("\n!!! MEMORY MISMATCH DETECTED !!!")
      var mismatchesFound = 0
      for (i <- 0 until cfg.memCheckSize) {
        if (dutMem(i) != goldenMem(i) && mismatchesFound < 20) { // Print first 20 mismatches
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
class AdvancedFuzzyTest extends CustomSpinalSimFunSuite {

  // Define the main test configuration in one place
  val objdumpPath =
    "../la32r-vm/tools/loongson-gnu-toolchain-8.3-x86_64-loongarch32r-linux-gnusf-v2.0/bin/loongarch32r-linux-gnusf-objdump"
  val mainFuzzCfg = FuzzCfg(
    seed = 18931226L,
    instCount = 8000,
    templateProb = 0.1,
    iSramBase = BigInt("80000000", 16),
    iSramSize = 4 * 1024 * 1024,
    dSramBase = BigInt("80400000", 16),
    dSramSize = 4 * 1024 * 1024,
    pcSafeLimit = BigInt("80200000", 16), // 2MB safety limit
    memCheckSize = 8 * 1024, // Verify 8KB of D-SRAM
    memFillValue = BigInt("81709394", 16)
  )

  testSkip("a_plus_b_smokeTest") {
    println("--- Starting 'a+b' Smoke Test ---")

    // 1. Define the simple program: r4 = 100, r5 = 200, r6 = r4+r5, store r6 to mem[0]
    val instructions = Seq(
      addi_w(rd = 4, rj = 0, imm = 100), // r4 = 100
      addi_w(rd = 5, rj = 0, imm = 200), // r5 = 200
      add_w(rd = 6, rj = 4, rk = 5), // r6 = r4 + r5 = 300
      lu12i_w(rd = 7, imm = 0x80400), // r7 = 0x80400000 (base addr for store)
      st_w(rd = 6, rj = 7, offset = 0), // mem[r7 + 0] = r6
      beq(rj = 0, rd = 0, offset = 0) // Halt
    )

    // 2. Prepare memory content for DUT
    val iSramSize = 1 * 1024 * 1024 // 1MB is enough for this small test
    val dSramSize = 1 * 1024 * 1024

    // We need to pad the instructions to the full memory size for SimulatedSRAM
    val iSramContent = instructions ++ Seq.fill(iSramSize / 4 - instructions.length)(BigInt(0))
    // For this simple test, dSram can be empty (randomly filled)
    val dSramContent = Seq.empty[BigInt]

    // 3. Compile the DUT
    val compiled = SimConfig.withFstWave.compile(
      new LabTestBench(
        iDataWords = iSramContent,
        dDataWords = dSramContent
      )
    )

    // 4. Run the simulation
    compiled.doSim("a_plus_b_smoke_test") { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      // Wait for the program to complete. 6 instructions need ~100 cycles.
      cd.waitSampling(200)

      // 5. Verify the result in D-SRAM
      println("--- Verifying result in D-SRAM ---")
      val expectedValue = 300

      // Use the testbench port to read from address 0 of dSram
      dut.dSram.io.tb_readEnable #= true
      dut.dSram.io.tb_readAddress #= 0
      cd.waitSampling() // Wait one cycle for the read to complete
      val actualValue = dut.dSram.io.tb_readData.toBigInt
      dut.dSram.io.tb_readEnable #= false

      println(s"Expected value: $expectedValue, Got: $actualValue")
      assert(actualValue == expectedValue, "The 'a+b' result in memory is incorrect!")

      println("--- 'a+b' Smoke Test PASSED! ---")
    }
  }

  test(s"Advanced Fuzz Test (seed=${mainFuzzCfg.seed})") {

    val rand = new Random(mainFuzzCfg.seed)

    // --- SETUP ---
    println("--- Test Setup Phase ---")
    // 1. Prepare all data using the Fuzz helper
    val instructions = Fuzz.genInstStream(rand, mainFuzzCfg)
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
      Seq((w & 0xff).toByte, ((w >> 8) & 0xff).toByte, ((w >> 16) & 0xff).toByte, ((w >> 24) & 0xff).toByte)
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
    compiled.doSim(s"FuzzTest_seed_${mainFuzzCfg.seed}", seed = mainFuzzCfg.seed.toInt) { dut =>
      dut.clockDomain.forkStimulus(10)
      SimTimeout(1000000)
      println("--- Simulation Started ---")

      // 4. Run the main co-simulation loop
      Fuzz.runCoSim(dut, mainFuzzCfg)

      // 5. Run the final memory check
      Fuzz.checkMemDump(dut, mainFuzzCfg)

      println("\n>>> Advanced Fuzz Test PASSED! <<<")
    }

    // --- CLEANUP ---
    GoldenModel.destroy()
    println("--- Test Finished ---")
  }

  thatsAll
}
