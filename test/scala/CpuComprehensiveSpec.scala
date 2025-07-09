package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.utilities._
import parallax.components.rename._
import parallax.components.rob._
import parallax.execute._
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu._
import parallax.bpu.BpuService
import parallax.components.ifu._
import parallax.components.memory._
import parallax.components.dcache2._
import spinal.lib.bus.amba4.axi.Axi4Config
import test.scala.LA32RInstrBuilder._
import scala.collection.mutable
import scala.util.Random

/**
 * Comprehensive CPU testing suite that starts simple and builds up complexity
 * Each test stage validates CPU correctness before moving to more complex scenarios
 */
class CpuComprehensiveSpec extends CustomSpinalSimFunSuite {
  
  val pCfg = PipelineConfig(
    aluEuCount = 1,
    lsuEuCount = 0,  // Start with no LSU to focus on core functionality
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 2,
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    robDepth = 32,
    commitWidth = 1,
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
    enableLog = false
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

  // Helper function to run a test with instructions and verify commits
  def runInstructionTest(
    testName: String,
    instructions: Seq[BigInt],
    expectedCommits: Int,
    timeout: Int = 2000
  ): Unit = {
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(timeout * 10) // Convert to simulation time units
      
      // Helper function to write instructions to memory
      def writeInstructionsToMem(address: BigInt, instructions: Seq[BigInt]): Unit = {
        val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
        
        // CRITICAL FIX: Ensure TB writes are completely isolated from cache operations
        println(s"=== ISOLATING MEMORY SYSTEM FOR TB WRITES [$testName] ===")
        
        // Step 1: Wait for any pending cache operations to complete
        cd.waitSampling(100) 
        
        // Step 2: Ensure initMemEnable is active (CPU should be redirected)
        assert(dut.io.initMemEnable.toBoolean, "initMemEnable must be active during TB writes")
        
        var currentAddr = address
        println(s"Writing ${instructions.length} instructions starting at address 0x${address.toString(16)}")
        for ((inst, idx) <- instructions.zipWithIndex) {
          sram.io.tb_writeEnable #= true
          sram.io.tb_writeAddress #= currentAddr
          sram.io.tb_writeData #= inst
          println(s"  [$testName] Writing instruction ${idx}: 0x${currentAddr.toString(16)} = 0x${inst.toString(16)}")
          cd.waitSampling(3) // Longer wait per write to ensure completion
          currentAddr += 4
        }
        sram.io.tb_writeEnable #= false
        cd.waitSampling(10)
        
        // Step 3: Force memory system synchronization
        println(s"=== SYNCHRONIZING MEMORY SYSTEM AFTER TB WRITES [$testName] ===")
        cd.waitSampling(200) // Extended wait for memory hierarchy to sync
        
        // Step 4: Verify writes were successful by reading back
        for ((inst, idx) <- instructions.zipWithIndex) {
          val readAddr = address + (idx * 4)
          sram.io.tb_readEnable #= true
          sram.io.tb_readAddress #= readAddr
          cd.waitSampling()
          val readData = sram.io.tb_readData.toBigInt
          println(s"  [$testName] VERIFY [${idx}] Address 0x${readAddr.toString(16)} = 0x${readData.toString(16)} (expected 0x${inst.toString(16)})")
          assert(readData == inst, s"TB write verification failed at 0x${readAddr.toString(16)}: got 0x${readData.toString(16)}, expected 0x${inst.toString(16)}")
        }
        sram.io.tb_readEnable #= false
        
        println(s"Instruction writing completed and verified for $testName")
      }
      
      // CRITICAL: Write instructions IMMEDIATELY after reset, before any cache initialization
      println(s"=== WRITING INSTRUCTIONS IMMEDIATELY AFTER RESET [$testName] ===")
      
      // Initialize memory control signals - activate CPU control IMMEDIATELY
      dut.io.initMemEnable #= true  // Activate BEFORE any cache operations
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      // Minimal reset time - just enough for hardware to stabilize
      println(s"=== MINIMAL RESET WAIT [$testName] ===")
      cd.waitSampling(10) // Minimal reset time
      
      println(s"=== CPU CONTROL ACTIVATED (initMemEnable=1) [$testName] ===")
      cd.waitSampling(2) // Minimal signal propagation
      
      // Write instructions to memory
      val baseAddr = BigInt("0", 16)
      writeInstructionsToMem(baseAddr, instructions)
      
      // CRITICAL: Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println(s"=== CPU CONTROL DEACTIVATED (initMemEnable=0) [$testName] ===")
      cd.waitSampling(5) // Allow signal to propagate
      println(s"Memory writing completed, CPU can now start for $testName")
      
      println(s"=== STARTING CPU EXECUTION [$testName] ===")
      cd.waitSampling(5)
      
      // Monitor commits
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, Int)]()
      var timeoutCycles = 0
      
      val commitMonitor = fork {
        while(commitCount < expectedCommits + 2 && timeoutCycles < timeout) { // +2 buffer for safety
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            commitedInstructions += ((commitPC, commitCount))
            commitCount += 1
            println(s"  [$testName] COMMIT $commitCount: PC=0x${commitPC.toString(16)}")
            
            if (commitCount >= expectedCommits) {
              // Allow a few more cycles to ensure no unexpected commits
              cd.waitSampling(10)
              // Exit the loop naturally
            }
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion
      commitMonitor.join()
      
      // Verify results
      println(s"  [$testName] Results: $commitCount commits in $timeoutCycles cycles")
      
      if (timeoutCycles >= timeout) {
        assert(false, s"$testName: Test timed out after $timeout cycles")
      }
      
      if (commitCount != expectedCommits) {
        println(s"  [$testName] Expected $expectedCommits commits, got $commitCount")
        println(s"  [$testName] Committed PCs: ${commitedInstructions.map(_._1.toString(16)).mkString(", ")}")
        assert(false, s"$testName: Incorrect number of commits")
      }
      
      // Verify PC sequence is correct
      val expectedPCs = (0 until expectedCommits).map(i => baseAddr + i * 4)
      for (i <- expectedPCs.indices) {
        val actualPC = commitedInstructions(i)._1
        assert(actualPC == expectedPCs(i), 
          s"$testName: PC mismatch at commit $i: expected 0x${expectedPCs(i).toString(16)}, got 0x${actualPC.toString(16)}")
      }
      
      println(s"  [$testName] ✅ PASSED")
    }
  }

  // ========== STEP 1: Single Instruction Tests ==========
  
  test("Step 1.1: Single ADDI instruction") {
    runInstructionTest(
      "Single ADDI",
      Seq(addi_w(rd = 1, rj = 0, imm = 42), idle()),
      expectedCommits = 1 // ADDI should be committed, then IDLE should stop CPU
    )
  }
  
  test("Step 1.2: Single ADDI with different immediate") {
    runInstructionTest(
      "Single ADDI Different",
      Seq(addi_w(rd = 2, rj = 0, imm = 100), idle()),
      expectedCommits = 1
    )
  }
  
  test("Step 1.3: Single ADD instruction") {
    runInstructionTest(
      "Single ADD",
      Seq(add_w(rd = 1, rj = 0, rk = 0), idle()),
      expectedCommits = 1
    )
  }

  // ========== STEP 2: Simple Arithmetic Sequences ==========
  
  test("Step 2.1: Two independent ADDI instructions") {
    runInstructionTest(
      "Two ADDI",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),
        addi_w(rd = 2, rj = 0, imm = 20),
        idle()
      ),
      expectedCommits = 2
    )
  }
  
  test("Step 2.2: Three independent ADDI instructions") {
    runInstructionTest(
      "Three ADDI",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),
        addi_w(rd = 2, rj = 0, imm = 20),
        addi_w(rd = 3, rj = 0, imm = 30),
        idle()
      ),
      expectedCommits = 3
    )
  }
  
  test("Step 2.3: Five independent ADDI instructions") {
    runInstructionTest(
      "Five ADDI",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),
        addi_w(rd = 2, rj = 0, imm = 20),
        addi_w(rd = 3, rj = 0, imm = 30),
        addi_w(rd = 4, rj = 0, imm = 40),
        addi_w(rd = 5, rj = 0, imm = 50),
        idle()
      ),
      expectedCommits = 5
    )
  }

  // ========== STEP 3: Basic Dependency Tests ==========
  
  test("Step 3.1: Simple RAW dependency") {
    runInstructionTest(
      "RAW Dependency",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),  // r1 = 10
        addi_w(rd = 2, rj = 1, imm = 5),   // r2 = r1 + 5 = 15
        idle()
      ),
      expectedCommits = 2
    )
  }
  
  test("Step 3.2: Chain of RAW dependencies") {
    runInstructionTest(
      "RAW Chain",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),  // r1 = 10
        addi_w(rd = 2, rj = 1, imm = 5),   // r2 = r1 + 5 = 15
        addi_w(rd = 3, rj = 2, imm = 3),   // r3 = r2 + 3 = 18
        idle()
      ),
      expectedCommits = 3
    )
  }
  
  test("Step 3.3: Multiple register dependencies") {
    runInstructionTest(
      "Multiple Dependencies",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),  // r1 = 10
        addi_w(rd = 2, rj = 0, imm = 20),  // r2 = 20
        add_w(rd = 3, rj = 1, rk = 2),     // r3 = r1 + r2 = 30
        idle()
      ),
      expectedCommits = 3
    )
  }

  // ========== STEP 4: Arithmetic Operations ==========
  
  test("Step 4.1: Mixed arithmetic operations") {
    runInstructionTest(
      "Mixed Arithmetic",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 10),   // r1 = 10
        addi_w(rd = 2, rj = 0, imm = 5),    // r2 = 5
        add_w(rd = 3, rj = 1, rk = 2),      // r3 = r1 + r2 = 15
        sub_w(rd = 4, rj = 1, rk = 2),      // r4 = r1 - r2 = 5
        idle()
      ),
      expectedCommits = 4
    )
  }
  
  test("Step 4.2: Shift operations") {
    runInstructionTest(
      "Shift Operations",
      Seq(
        addi_w(rd = 1, rj = 0, imm = 8),     // r1 = 8
        slli_w(rd = 2, rj = 1, imm = 2),     // r2 = r1 << 2 = 32
        srli_w(rd = 3, rj = 2, imm = 1),     // r3 = r2 >> 1 = 16
        idle()
      ),
      expectedCommits = 3
    )
  }

  // ========== STEP 5: Control Flow Tests ==========
  
  test("Step 5.1: Simple unconditional branch") {
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(20000)
      
      // Helper function to write instructions to memory
      def writeInstructionsToMem(address: BigInt, instructions: Seq[BigInt]): Unit = {
        val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
        
        cd.waitSampling(10)
        assert(dut.io.initMemEnable.toBoolean, "initMemEnable must be active during TB writes")
        
        var currentAddr = address
        for ((inst, idx) <- instructions.zipWithIndex) {
          sram.io.tb_writeEnable #= true
          sram.io.tb_writeAddress #= currentAddr
          sram.io.tb_writeData #= inst
          println(s"  [Branch Test] Writing instruction ${idx}: 0x${currentAddr.toString(16)} = 0x${inst.toString(16)}")
          cd.waitSampling(3)
          currentAddr += 4
        }
        sram.io.tb_writeEnable #= false
        cd.waitSampling(10)
      }
      
      // Setup test environment
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      
      // Create a simple branch test
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),    // 0x00: r1 = 5
        beq(rj = 0, rd = 0, offset = 8),    // 0x04: if r0 == r0 (always true), jump to 0x0C
        addi_w(rd = 2, rj = 0, imm = 999),  // 0x08: r2 = 999 (should be skipped)
        addi_w(rd = 3, rj = 0, imm = 10),   // 0x0C: r3 = 10 (branch target)
        idle()                              // 0x10: IDLE
      )
      
      writeInstructionsToMem(baseAddr, instructions)
      
      // Start CPU execution
      dut.io.initMemEnable #= false
      cd.waitSampling(5)
      
      // Monitor commits
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[BigInt]()
      var timeoutCycles = 0
      val maxCycles = 2000
      
      val commitMonitor = fork {
        while(commitCount < 5 && timeoutCycles < maxCycles) {
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            commitedInstructions += commitPC
            commitCount += 1
            println(s"  [Branch Test] COMMIT $commitCount: PC=0x${commitPC.toString(16)}")
            
            if (commitCount >= 3) {
              cd.waitSampling(10)
              // Exit the loop naturally
            }
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion
      commitMonitor.join()
      
      // Verify results
      println(s"  [Branch Test] Results: $commitCount commits in $timeoutCycles cycles")
      
      if (timeoutCycles >= maxCycles) {
        assert(false, "Branch test timed out")
      }
      
      // Expected sequence: 0x00, 0x04, 0x0C (skip 0x08 due to branch)
      val expectedPCs = Seq(BigInt(0x00), BigInt(0x04), BigInt(0x0C))
      
      if (commitCount != 3) {
        println(s"  [Branch Test] Expected 3 commits, got $commitCount")
        println(s"  [Branch Test] Committed PCs: ${commitedInstructions.map(_.toString(16)).mkString(", ")}")
        assert(false, "Branch test: Incorrect number of commits")
      }
      
      for (i <- expectedPCs.indices) {
        val actualPC = commitedInstructions(i)
        assert(actualPC == expectedPCs(i), 
          s"Branch test: PC mismatch at commit $i: expected 0x${expectedPCs(i).toString(16)}, got 0x${actualPC.toString(16)}")
      }
      
      println(s"  [Branch Test] ✅ PASSED")
    }
  }

  // ========== STEP 6: Progressive Complexity Tests ==========
  
  test("Step 6.1: Ten instruction sequence") {
    val instructions = mutable.ArrayBuffer[BigInt]()
    for (i <- 1 to 10) {
      instructions += addi_w(rd = i, rj = 0, imm = i * 10)
    }
    instructions += idle()
    
    runInstructionTest(
      "Ten Instructions",
      instructions.toSeq,
      expectedCommits = 10
    )
  }
  
  test("Step 6.2: Twenty instruction sequence") {
    val instructions = mutable.ArrayBuffer[BigInt]()
    for (i <- 1 to 20) {
      instructions += addi_w(rd = i % 30 + 1, rj = 0, imm = i * 5) // Cycle through registers
    }
    instructions += idle()
    
    runInstructionTest(
      "Twenty Instructions",
      instructions.toSeq,
      expectedCommits = 20,
      timeout = 4000
    )
  }

  thatsAll()
}