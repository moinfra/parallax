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
import parallax.components.rob.FlushReason
import parallax.execute._
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu._
import parallax.bpu.BpuService
import parallax.components.ifu._
import parallax.components.memory._
import parallax.components.dcache2._
import spinal.lib.bus.amba4.axi.Axi4Config
import LA32RInstrBuilder._
import scala.collection.mutable
import scala.util.Random
import parallax.components.lsu.LsuConfig
import parallax.components.lsu.AguPlugin
import parallax.components.lsu.StoreBufferPlugin
import parallax.components.lsu.LoadQueuePlugin

// =========================================================================
//  Helper Functions for Architectural Register Verification
// =========================================================================

object CpuFullSpecHelper {
  def readArchReg(dut: CpuFullTestBench, archRegIdx: Int): BigInt = {
    val cd = dut.clockDomain
    val physRegIdx = dut.ratMapping(archRegIdx).toBigInt
    dut.prfReadPort.valid #= true
    dut.prfReadPort.address #= physRegIdx
    dut.clockDomain.waitSampling(1)
    val rsp = dut.prfReadPort.rsp.toBigInt
    dut.prfReadPort.valid #= false
    return rsp
  }

  def writeInstructionsToMem(dut: CpuFullTestBench, address: BigInt, instructions: Seq[BigInt]): Unit = {
    val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
    val cd = dut.clockDomain
    
    println("=== ISOLATING MEMORY SYSTEM FOR TB WRITES ===")
    cd.waitSampling(100) 
    assert(dut.io.initMemEnable.toBoolean, "initMemEnable must be active during TB writes")
    
    var currentAddr = address
    println(s"Writing ${instructions.length} instructions starting at address 0x${address.toString(16)}")
    for ((inst, idx) <- instructions.zipWithIndex) {
      sram.io.tb_writeEnable #= true
      sram.io.tb_writeAddress #= currentAddr
      sram.io.tb_writeData #= inst
      println(s"  [${idx}] Address 0x${currentAddr.toString(16)} = 0x${inst.toString(16)}")
      cd.waitSampling(3)
      currentAddr += 4
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling(10)
    
    println("=== SYNCHRONIZING MEMORY SYSTEM AFTER TB WRITES ===")
    cd.waitSampling(200)
    
    // Verify writes were successful
    for ((inst, idx) <- instructions.zipWithIndex) {
      val readAddr = address + (idx * 4)
      sram.io.tb_readEnable #= true
      sram.io.tb_readAddress #= readAddr
      cd.waitSampling()
      val readData = sram.io.tb_readData.toBigInt
      println(s"  VERIFY [${idx}] Address 0x${readAddr.toString(16)} = 0x${readData.toString(16)} (expected 0x${inst.toString(16)})")
      assert(readData == inst, s"TB write verification failed at 0x${readAddr.toString(16)}: got 0x${readData.toString(16)}, expected 0x${inst.toString(16)}")
    }
    sram.io.tb_readEnable #= false
    
    println("Instruction writing completed and verified")
  }

  
      def verifyMemory(dut: CpuFullTestBench,address: BigInt, expectedValue: BigInt): Unit = {
        val cd = dut.clockDomain
        val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()
        sram.io.tb_readEnable #= true
        sram.io.tb_readAddress #= address
        cd.waitSampling()
        val actualValue = sram.io.tb_readData.toBigInt
        sram.io.tb_readEnable #= false
        assert(actualValue == expectedValue, 
          s"Memory mismatch at 0x${address.toString(16)}: expected 0x${expectedValue.toString(16)}, got 0x${actualValue.toString(16)}")
      }
}

class CpuFullSpec extends CustomSpinalSimFunSuite {
  val pCfg = PipelineConfig(
    aluEuCount = 1,
    lsuEuCount = 1,
    dispatchWidth = 1,
    bruEuCount = 1,
    renameWidth = 1,
    fetchWidth = 2,
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    robDepth = 32,
    commitWidth = 1,
    resetVector = BigInt("00000000", 16), // Use physical address 0x0
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

  
  // 通用 Commit 监控工具函数
  def monitorCommits(
    dut: CpuFullTestBench,
    cd: ClockDomain,
    expectedCount: Int,
    idleLimit: Int = 50
  ): Seq[BigInt] = {
    val commitedPCs = mutable.ArrayBuffer[BigInt]()
    var lastCommitCount = 0
    var idleCycles = 0
    while (idleCycles < idleLimit) {
      cd.waitSampling()
      if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
        val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
        commitedPCs += commitPC
        println(s"COMMIT #${commitedPCs.length}: PC=0x${commitPC.toString(16)}")
      }
      if (commitedPCs.length == lastCommitCount) {
        idleCycles += 1
        println(s"No new commits for $idleCycles cycles.")
      } else {
        idleCycles = 0
        lastCommitCount = commitedPCs.length
      }
    }
    println(s"Commit monitor exited after $idleCycles idle cycles. Got ${commitedPCs.length} commits, expect $expectedCount.")
    commitedPCs.toSeq
  }

  test("CpuFullTestBench compilation test") {
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(1000)
      
      // Initialize memory control signals
      dut.io.initMemEnable #= false
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      
      // Basic test to ensure the testbench compiles and runs
      dut.io.enableCommit #= false
      cd.waitSampling(10)
      
      // Just verify the basic structure is working
      println("CpuFullTestBench compilation test passed!")
      assert(true, "Compilation and basic simulation startup test passed.")
    }
  }

  test("IDLE after ADDI Test - addi+idle+addi+addi") {
    val pCfgWithLsu = pCfg.copy(lsuEuCount = 1)
    
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfgWithLsu, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(50000)

      // --- Test Setup ---
      dut.io.initMemEnable #= true
      cd.waitSampling(10)

      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 100),         // 0x00: r1 = 100 (should commit)
        idle(),                                   // 0x04: IDLE instruction (should commit, then stop)
        addi_w(rd = 2, rj = 0, imm = 200),         // 0x08: r2 = 200 (should NOT commit)
        addi_w(rd = 3, rj = 0, imm = 300)          // 0x0C: r3 = 300 (should NOT commit)
      )
      val expectedCommitPCs = Seq(baseAddr, baseAddr + 4) // Only first two instructions should commit
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)

      dut.io.initMemEnable #= false
      cd.waitSampling(20)
      dut.io.enableCommit #= true
      println("Starting IDLE after ADDI test...")

            val commitedPCs = monitorCommits(dut, cd, expectedCommitPCs.length)


      // --- Verify Result ---
      println("Verifying IDLE after ADDI test results...")
      assert(commitedPCs.length == expectedCommitPCs.length, s"Expected ${expectedCommitPCs.length} commits, but got ${commitedPCs.length}")
      assert(commitedPCs.toSeq == expectedCommitPCs, s"PC sequence mismatch!\nExpected: $expectedCommitPCs\nGot: ${commitedPCs.toSeq}")
      
      println("IDLE after ADDI Test passed - IDLE instruction correctly stopped further commits!")
    }
  }

  test("IDLE first Test - idle+addi+addi") {
    val pCfgWithLsu = pCfg.copy(lsuEuCount = 1)
    
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfgWithLsu, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(50000)

      // --- Test Setup ---
      dut.io.initMemEnable #= true
      cd.waitSampling(10)

      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        idle(),                                   // 0x00: IDLE instruction (should commit, then stop)
        addi_w(rd = 1, rj = 0, imm = 100),         // 0x04: r1 = 100 (should NOT commit)
        addi_w(rd = 2, rj = 0, imm = 200)          // 0x08: r2 = 200 (should NOT commit)
      )
      val expectedCommitPCs = Seq(baseAddr) // Only IDLE instruction should commit
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)

      dut.io.initMemEnable #= false
      cd.waitSampling(20)
      dut.io.enableCommit #= true
      println("Starting IDLE first test...")

            val commitedPCs = monitorCommits(dut, cd, expectedCommitPCs.length)


      // --- Verify Result ---
      println("Verifying IDLE first test results...")
      assert(commitedPCs.length == expectedCommitPCs.length, s"Expected ${expectedCommitPCs.length} commits, but got ${commitedPCs.length}")
      assert(commitedPCs.toSeq == expectedCommitPCs, s"PC sequence mismatch!\nExpected: $expectedCommitPCs\nGot: ${commitedPCs.toSeq}")
      
      println("IDLE first Test passed - IDLE instruction correctly stopped all subsequent commits!")
    }
  }

  test("Basic Addition and Store Test") {
    val pCfgWithLsu = pCfg.copy(lsuEuCount = 1)
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfgWithLsu, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(50000)
      // --- Test Setup ---
      dut.io.initMemEnable #= true
      cd.waitSampling(10)

      val baseAddr = BigInt("0", 16)
      val storeAddr = BigInt("1000", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 100),         // 0x00: r1 = 100
        addi_w(rd = 2, rj = 0, imm = 200),         // 0x04: r2 = 200
        add_w(rd = 3, rj = 1, rk = 2),             // 0x08: r3 = r1 + r2 = 300
        lu12i_w(rd=10, imm = storeAddr.toInt >> 12), // 0x0C: r10 = storeAddr base
        ori(rd=10, rj=10, imm = storeAddr.toInt & 0xFFF), // 0x10: r10 = storeAddr full
        st_w(rd = 3, rj = 10, offset = 0),         // 0x14: mem[r10] = r3
        idle()                                     // 0x18: Halt CPU
      )
      val expectedCommitPCs = instructions.indices.map(i => baseAddr + i * 4)
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)

      dut.io.initMemEnable #= false
      cd.waitSampling(20)
      dut.io.enableCommit #= true
      println("Starting execution...")

      // --- Monitor Commits ---
      val commitedPCs = monitorCommits(dut, cd, expectedCommitPCs.length)

      // --- Verify Result ---
      println("Verifying final commit count and sequence...")
      assert(commitedPCs.length == expectedCommitPCs.length, s"Expected ${expectedCommitPCs.length} commits, but got ${commitedPCs.length}")
      assert(commitedPCs.toSeq == expectedCommitPCs, s"Final PC sequence mismatch!\nExpected: $expectedCommitPCs\nGot: ${commitedPCs.toSeq}")
      cd.waitSampling(100)
      println(s"Verifying result at memory address 0x${storeAddr.toString(16)}...")
      CpuFullSpecHelper.verifyMemory(dut, storeAddr, 300)

      println("Basic Addition and Store Test passed with PC sequence and data verification!")
    }
  }
// =====          FIXED: Branch and Memory Instructions Test         =====
// =======================================================================
// 修正说明：
// 1. **修正了指令生成**：st.w/ld.w 使用了正确的参数名 (rd, rj)。
// 2. **修正了提交监控逻辑**：采用固定超时和事后断言，可以捕获过多或过少的提交。
// 3. **增加了数据结果验证**。
// 4. **增加了PC序列验证**。
test("Branch with Load/Store Test") {
    val pCfgWithLsu = pCfg.copy(lsuEuCount = 1)

    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfgWithLsu, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(8000)

      


      // --- Test Setup ---
      dut.io.initMemEnable #= true
      cd.waitSampling(10)

      val baseAddr = BigInt("0", 16)
      val dataAddr1 = BigInt("2000", 16)
      val dataAddr2 = BigInt("2004", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 777),                // 0x00: r1 = 777
        lu12i_w(rd = 10, imm = dataAddr1.toInt >> 12),    // 0x04: r10 = dataAddr1 base
        ori(rd=10, rj=10, imm = dataAddr1.toInt & 0xFFF), // 0x08: r10 = dataAddr1 full
        // FIX: Correct parameter names
        st_w(rd = 1, rj = 10, offset = 0),                // 0x0c: mem[0x2000] = 777
        beq(rj = 0, rd = 0, offset = 8),                  // 0x10: if r0==r0 (always), jump 2 instrs to 0x18
        addi_w(rd = 9, rj = 0, imm = 999),                // 0x14: (should be skipped)
        ld_w(rd = 4, rj = 10, offset = 0),                // 0x18: r4 = mem[0x2000]
        st_w(rd = 4, rj = 10, offset = 4),                // 0x1c: mem[0x2004] = r4
        idle()                                            // 0x20: Halt CPU
      )
      val expectedCommitPCs = Seq(0x00, 0x04, 0x08, 0x0c, 0x10, 0x18, 0x1c, 0x20).map(baseAddr + _)
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)

      dut.io.initMemEnable #= false
      cd.waitSampling(20)
      dut.io.enableCommit #= true
      println("Starting execution...")

      // --- Monitor Commits ---
      // FIX: New robust commit monitoring logic
      val commitedPCs = monitorCommits(dut, cd, expectedCommitPCs.length)
      
      // --- Verify Result ---
      println("Verifying final commit count and sequence...")
      assert(commitedPCs.length == expectedCommitPCs.length, s"Expected ${expectedCommitPCs.length} commits, but got ${commitedPCs.length}")
      assert(commitedPCs.toSeq == expectedCommitPCs, s"Final PC sequence mismatch!\nExpected: $expectedCommitPCs\nGot: ${commitedPCs.toSeq}")
      
      println("Verifying memory state after store and load operations...")
      CpuFullSpecHelper.verifyMemory(dut, dataAddr1, 777)
      CpuFullSpecHelper.verifyMemory(dut, dataAddr2, 777)

      println("Branch with Load/Store Test passed with PC sequence and data verification!")
    }
}


 // =======================================================================
// =====      FIXED: Register and Memory State Verification Test     =====
// =======================================================================
// 修正说明：
// 1. **修正了指令生成**：st.w 使用了正确的参数名 (rd, rj)。
// 2. **修正了提交监控逻辑**：采用固定超时和事后断言，可以捕获过多或过少的提交。
// 3. **增加了数据结果验证**。
// 4. **增加了PC序列验证**。
test("Register and Memory State Verification via Stores") {
    val pCfgWithLsu = pCfg.copy(lsuEuCount = 1)

    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfgWithLsu, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(50000)

      


      // --- Test Setup ---
      dut.io.initMemEnable #= true
      cd.waitSampling(10)

      val baseAddr = BigInt("0", 16)
      val dataAddr = BigInt("8000", 16)
      val instructions = Seq(
        // Calculate values in registers
        addi_w(rd = 1, rj = 0, imm = 0x123),                     // 0x00: r1 = 0x123
        addi_w(rd = 2, rj = 0, imm = 0x456),                     // 0x04: r2 = 0x456
        add_w(rd = 3, rj = 1, rk = 2),                           // 0x08: r3 = 0x123 + 0x456 = 0x579
        // Load base address for storing
        lu12i_w(rd = 10, imm = dataAddr.toInt >> 12),            // 0x0c: r10 = dataAddr base
        ori(rd = 10, rj = 10, imm = dataAddr.toInt & 0xFFF),     // 0x10: r10 = dataAddr full
        // Store register values to memory
        // FIX: Correct parameter names
        st_w(rd = 1, rj = 10, offset = 0),                       // 0x14: mem[0x8000] = r1
        st_w(rd = 2, rj = 10, offset = 4),                       // 0x18: mem[0x8004] = r2
        st_w(rd = 3, rj = 10, offset = 8),                       // 0x1c: mem[0x8008] = r3
        idle()                                                   // 0x20: Halt CPU
      )
      val expectedCommitPCs = instructions.indices.map(i => baseAddr + i * 4)
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)

      dut.io.initMemEnable #= false
      cd.waitSampling(20)
      dut.io.enableCommit #= true
      println("Starting execution...")

      // --- Monitor Commits ---
      // FIX: New robust commit monitoring logic
      val commitedPCs = monitorCommits(dut, cd, expectedCommitPCs.length)

      // --- Verify Result ---
      println("Verifying final commit count and sequence...")
      assert(commitedPCs.length == expectedCommitPCs.length, s"Expected ${expectedCommitPCs.length} commits, but got ${commitedPCs.length}")
      assert(commitedPCs.toSeq == expectedCommitPCs, s"Final PC sequence mismatch!\nExpected: $expectedCommitPCs\nGot: ${commitedPCs.toSeq}")
      
      println("Verifying memory to confirm final register states...")
      CpuFullSpecHelper.verifyMemory(dut, dataAddr + 0, 0x123) // Verifies r1's final state
      CpuFullSpecHelper.verifyMemory(dut, dataAddr + 4, 0x456) // Verifies r2's final state
      CpuFullSpecHelper.verifyMemory(dut, dataAddr + 8, 0x579) // Verifies r3's final state (result of add_w)

      println("Register and Memory State Verification via Stores passed with PC sequence and data verification!")
    }
}
  
  test("Data Dependency Test - RAW Hazard Verification") {
    val compiled = SimConfig.withFstWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(50000)
      
      // Helper function to verify memory content
      
      // Helper function to write instructions to memory - following existing pattern
      
      
      // Setup test environment
      println("=== TESTING RAW HAZARD RESOLUTION ===")
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      cd.waitSampling(2)
      
      // Test the classic RAW hazard scenario
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 100),  // r1 = 100
        addi_w(rd = 2, rj = 0, imm = 200),  // r2 = 200  
        add_w(rd = 3, rj = 1, rk = 2),      // r3 = r1 + r2 = 300 (RAW dependency)
        idle()                              // IDLE instruction to halt CPU
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // Start CPU execution
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      
      println("=== STARTING RAW HAZARD TEST ===")
      cd.waitSampling(5)
      
      // Monitor commits with special attention to the dependency resolution
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      
      val expectedCommitPCs = mutable.Queue[BigInt]()
      expectedCommitPCs ++= Seq(baseAddr, baseAddr + 4, baseAddr + 8)  // All 3 should commit
      
      val commitMonitor = fork {
        while(commitCount < 3) {  // Expect 3 commits if RAW hazard is resolved
          cd.waitSampling()
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            val robEntry = dut.io.commitEntry.payload
            
            val instrType = commitCount match {
              case 0 => "ADDI r1,r0,100"
              case 1 => "ADDI r2,r0,200" 
              case 2 => "ADD r3,r1,r2 (RAW DEPENDENCY)"
              case _ => "UNKNOWN"
            }
            
            println(s"COMMIT $commitCount: PC=0x${commitPC.toString(16)} - $instrType")
            
            // Store committed PC and type
            commitedInstructions += ((commitPC, instrType))
            
            // Verify commit PC sequence
            if (expectedCommitPCs.nonEmpty) {
              val expectedPC = expectedCommitPCs.head
              assert(commitPC == expectedPC, 
                s"Commit $commitCount PC mismatch: expected 0x${expectedPC.toString(16)}, got 0x${commitPC.toString(16)}")
              expectedCommitPCs.dequeue()
            }
            
            commitCount += 1
            
            // Log register assignments if available
            if (robEntry.uop.rename.allocatesPhysDest.toBoolean) {
              val physDestIdx = robEntry.uop.rename.physDest.idx.toInt
              val oldPhysDestIdx = robEntry.uop.rename.oldPhysDest.idx.toInt
              println(s"  Physical register mapping: new=p$physDestIdx, old=p$oldPhysDestIdx")
            }
            
            // Special logging for the critical dependency instruction
            if (commitCount == 3) {
              println("✅ RAW HAZARD RESOLVED: Dependent ADD instruction successfully committed!")
            }
          }
        }
      }
      
      // Start execution
      cd.waitSampling(20)
      println("Starting RAW hazard test...")
      dut.io.enableCommit #= true
      
      // Wait for all instructions to complete with longer timeout for dependency resolution
      var timeout = 2000  // Longer timeout for dependency resolution
      while(commitCount < 3 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 200 == 0) {
          println(s"RAW Hazard Test: Waiting for commits: $commitCount/3, timeout: $timeout")
        }
      }
      
      if (commitCount == 3) {
        println("🎉 SUCCESS: All 3 instructions committed. RAW hazard resolution appears to be working correctly.")
        assert(true, "RAW hazard resolution test passed.")
      } else {
        println("🚨 FAILURE: Not all 3 instructions committed within timeout.")
        println(s"Actual: Only $commitCount/3 instructions committed (timeout: ${timeout <= 0})")
        println("Expected: All 3 instructions should commit if RAW hazards are resolved correctly.")
        println("\n🚨 CONFIRMED BUG: BusyTable RAW hazard resolution failure or hang.")
        println("Root cause analysis needed:")
        println("  - Instruction 3 (ADD r3,r1,r2) depends on r1 and r2 from instructions 1&2")
        println("  - BusyTable should track r1,r2 busy state and wake up instruction 3")
        println("  - Current behavior: instruction 3 never wakes up or stalls -> timeout")
        println("  - Investigation needed: BusyTablePlugin wakeup network or dependency tracking.")
        assert(false, "RAW hazard bug detected, test failed as expected.")
      }
      
      println("Data Dependency Test completed")
    }
  }

  test("Branch Prediction Misprediction Test - Force BPU Prediction Error") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      // Helper function to write instructions to memory - using the proven pattern
      

      // Setup test environment
      println("=== BRANCH PREDICTION MISPREDICTION TEST ===")
      dut.io.initMemEnable #= true  
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      println("=== MINIMAL RESET WAIT ===")
      cd.waitSampling(10)
      
      println("=== CPU CONTROL ACTIVATED (initMemEnable=1) ===")
      cd.waitSampling(2)
      
      // Create a test program designed to trigger branch misprediction
      // Strategy: Use a branch that will definitely be mispredicted by a fresh BPU
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),     // 0x00: r1 = 5
        bne(rj = 1, rd = 0, offset = 16),    // 0x04: if r1 != r0, jump to +16 (TRUE - SHOULD jump to 0x14)
        addi_w(rd = 2, rj = 0, imm = 100),   // 0x08: r2 = 100 (should be SKIPPED if branch taken)
        addi_w(rd = 3, rj = 0, imm = 200),   // 0x0c: r3 = 200 (should be SKIPPED if branch taken)
        addi_w(rd = 4, rj = 0, imm = 300),   // 0x10: r4 = 300 (should be SKIPPED if branch taken)
        addi_w(rd = 5, rj = 0, imm = 400),   // 0x14: r5 = 400 (branch target)
        idle()                               // 0x18: IDLE instruction to halt CPU
      )
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // CRITICAL: Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING CPU EXECUTION ===")
      cd.waitSampling(5)
      
      // Monitor commits and branch execution with misprediction detection
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 2000  
      
      val commitMonitor = fork {
        while(commitCount < 5 && timeoutCycles < maxCycles) { // Allow more commits for potential misprediction recovery
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            // Identify instruction type based on PC
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,5"
              case 0x4 => "bne r1,r0,+12"
              case 0x8 => "addi r2,r0,100 (SHOULD BE SKIPPED)"
              case 0xc => "addi r3,r0,200 (SHOULD BE SKIPPED)"
              case 0x10 => "addi r4,r0,300 (SHOULD BE SKIPPED)"
              case 0x14 => "addi r5,r0,400 (BRANCH TARGET)"
              case 0x18 => "idle (STOP)"
              case _ => s"unknown@0x${commitPC.toString(16)}"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"Committed instruction $commitCount: $instrType")
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout
      commitMonitor.join()
      
      println(s"\n=== BRANCH PREDICTION MISPREDICTION TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      // Analyze results
      val pcSequence = commitedInstructions.map(_._1.toInt)
      if (pcSequence.contains(0x8) || pcSequence.contains(0xc) || pcSequence.contains(0x10)) {
        println("🚨 FAILURE: Speculative instructions committed despite flush!")
        println("Actual behavior:")
        println(s"  Committed PCs: ${pcSequence.map(pc => f"0x$pc%02x").mkString(" -> ")}")
        println("Expected behavior:")
        println("  Should be: 0x00 -> 0x04 -> 0x14 -> 0x18 (skip 0x08,0x0c,0x10)")
        println("\n🚨 ROOT CAUSE: Branch prediction rollback mechanism failure or incorrect speculation handling.")
        println("Evidence of bugs:")
        println("  1. BranchEU may detect misprediction and send flush signal")
        println("  2. ROB may receive flush signal but speculative instructions still commit")
        println("  3. Timing race condition: flush vs commit decisions occur same cycle")
        println("  4. Multi-cycle flush state tracking may be insufficient")
        
        assert(false, "Branch prediction rollback bug (speculative instructions committed) detected, test failed.")
        
      } else if (pcSequence == Seq(0x0, 0x4, 0x14, 0x18)) {
        println("🎉 SUCCESS: Branch prediction and rollback working correctly!")
        println("✅ Perfect execution - correct branch behavior and proper program termination.")
        println("✅ CPU correctly stopped at idle instruction without committing it.")
        
        assert(true, "Branch prediction misprediction rollback test passed. Bug appears to be fixed.")
        
      } else {
        println("⚠️  UNEXPECTED EXECUTION PATTERN")
        println(s"Actual PC sequence: ${pcSequence.map(pc => f"0x$pc%02x").mkString(" -> ")}")
        println("Expected: 0x00 -> 0x04 -> 0x14 -> 0x18 (stops at idle) OR (if bug) contains 0x08, 0x0c, 0x10")
        assert(false, s"Unexpected execution pattern observed: ${pcSequence.map(pc => f"0x$pc%02x").mkString(" -> ")}. Test failed.")
      }
      
      println("Branch Prediction Misprediction Test completed")
    }
  }

  test("Branch Prediction Rollback Test - Single Misprediction Recovery") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      // Setup test environment - use proven initialization pattern
      println("=== BRANCH PREDICTION ROLLBACK TEST ===")
      dut.io.initMemEnable #= true  // Activate BEFORE any cache operations
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      // Minimal reset time
      println("=== MINIMAL RESET WAIT ===")
      cd.waitSampling(10)
      
      println("=== CPU CONTROL ACTIVATED (initMemEnable=1) ===")
      cd.waitSampling(2)
      
      // Create a test program designed to trigger branch misprediction
      // Strategy: Use a branch that BPU will initially predict incorrectly (e.g., always predict taken by default)
      // The branch condition is r1 != r2 (r1=5, r2=5 -> false), so it should NOT be taken.
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),     // 0x00: r1 = 5
        addi_w(rd = 2, rj = 0, imm = 5),     // 0x04: r2 = 5  
        bne(rj = 1, rd = 2, offset = 8),     // 0x08: if r1 != r2, jump to +8 (FALSE - should not jump, PC=0x0C)
        addi_w(rd = 3, rj = 0, imm = 100),   // 0x0c: r3 = 100 (should execute)
        addi_w(rd = 4, rj = 0, imm = 200),   // 0x10: r4 = 200 (sequential execution continues)  
        addi_w(rd = 5, rj = 0, imm = 300),   // 0x14: r5 = 300 (sequential execution continues)
        idle()                               // 0x18: IDLE instruction to halt CPU
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // CRITICAL: Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING CPU EXECUTION ===")
      cd.waitSampling(5)
      
      // Monitor commits and branch execution with rollback detection
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 2000  // Increased timeout for rollback handling
      
      // Expected successful execution flow:
      // 0x00: addi r1, r0, 5     -> commit
      // 0x04: addi r2, r0, 5     -> commit  
      // 0x08: bne r1, r2, +8     -> should be NOT taken (r1 == r2), commit
      // 0x0c: addi r3, r0, 100   -> commit (sequential execution)
      // 0x10: addi r4, r0, 200   -> commit (sequential execution continues)
      // 0x14: addi r5, r0, 300   -> commit (sequential execution continues)
      // Expected commits: 6 total
      val expectedFinalPCs = Seq(baseAddr, baseAddr + 4, baseAddr + 8, baseAddr + 12, baseAddr + 16, baseAddr + 20)
      
      val commitMonitor = fork {
        while(commitCount < expectedFinalPCs.length && timeoutCycles < maxCycles) {
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            // Identify instruction type based on PC
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,5"
              case 0x4 => "addi r2,r0,5"  
              case 0x8 => "bne r1,r2,+8"
              case 0xc => "addi r3,r0,100"
              case 0x10 => "addi r4,r0,200"
              case 0x14 => "addi r5,r0,300"
              case _ => s"unknown@0x${commitPC.toString(16)}"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"Committed instruction $commitCount: $instrType")
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout
      commitMonitor.join()
      
      println(s"\n=== BRANCH PREDICTION ROLLBACK TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      // HONEST ROLLBACK TEST REPORTING: Based on user feedback about remaining issues
      if (timeoutCycles >= maxCycles || commitCount != expectedFinalPCs.length) {
        println("🚨 FAILURE: Branch rollback mechanism failed or timed out.")
        println(s"Only $commitCount commits occurred before timeout (expected ${expectedFinalPCs.length})")
        println("🎯 TEST PURPOSE: Expose rollback bugs for investigation")
        println("Issues identified:")
        println("  - CPU either hanged or did not commit all expected instructions.")
        println("  - This indicates problems with branch misprediction handling, rollback, or pipeline recovery.")
        
        assert(false, s"Branch prediction rollback bug (timeout/incomplete commits) detected, test failed.")
        
      } else {
        println("🎉 SUCCESS: Branch prediction rollback mechanism working correctly!")
        println("Committed instructions:")
        commitedInstructions.foreach { case (pc, instr) => 
          println(s"  0x${pc.toString(16)}: $instr")
        }
        
        // Verify final committed PC sequence
        for (i <- expectedFinalPCs.indices) {
          val (actualPC, _) = commitedInstructions(i)
          assert(actualPC == expectedFinalPCs(i), 
            s"Commit $i: expected PC=0x${expectedFinalPCs(i).toString(16)}, got PC=0x${actualPC.toString(16)}")
        }
        
        println("✅ Branch condition correctly evaluated (r1 == r2 -> not taken)")
        println("✅ Rollback mechanism functional or no misprediction occurred (if prediction was correct from start)")
        
        assert(true, "Branch prediction rollback test passed. Bug appears to be fixed.")
      }
      
      println("Branch Prediction Rollback Test completed")
    }
  }

  test("Multi-Branch Instruction Bug Test - Expose RenamePlugin Throttling Issue") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      // Helper function to write instructions to memory - using the proven pattern
      

      // Setup test environment
      println("=== MULTI-BRANCH BUG EXPOSURE TEST ===")
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      cd.waitSampling(2)
      
      // Create a test program with MULTIPLE CLOSE branches to expose the throttling bug
      // This violates our <=1 branch constraint and should expose the missing throttling
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),     // 0x00: r1 = 5
        beq(rj = 1, rd = 1, offset = 8),     // 0x04: BRANCH 1 - if r1 == r1, jump to 0x04+8=0x0c (NOT taken, falls through)
        bne(rj = 0, rd = 1, offset = 8),     // 0x08: BRANCH 2 - if r0 != r1, jump to 0x08+8=0x10 (taken)
        addi_w(rd = 2, rj = 0, imm = 100),   // 0x0c: r2 = 100 (should be skipped)
        addi_w(rd = 3, rj = 0, imm = 200),   // 0x10: r3 = 200 (branch target)
        idle()                               // 0x14: IDLE instruction to halt CPU
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING MULTI-BRANCH BUG TEST ===")
      cd.waitSampling(5)
      
      // Monitor commits to observe the bug
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 1000  // Short timeout to catch hanging
      
      // Expected behavior with missing throttling:
      // - Two branch instructions (0x04, 0x08) enter rename stage simultaneously
      // - RenamePlugin has no branch throttling logic
      // - This causes speculation conflicts, checkpoint corruption, or hanging
      // - CPU should hang or produce incorrect execution
      
      val commitMonitor = fork {
        while(commitCount < 5 && timeoutCycles < maxCycles) {
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            // Identify instruction type based on PC
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,5"
              case 0x4 => "bne r1,r0,+8 (BRANCH 1)"
              case 0x8 => "beq r0,r0,+4 (BRANCH 2)"
              case 0xc => "addi r2,r0,100"
              case 0x10 => "addi r3,r0,200"
              case _ => s"unknown@0x${commitPC.toString(16)}"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"Multi-branch commit $commitCount: $instrType")
            
            // Special detection for the problematic branches
            if (commitPC.toInt == 0x4) {
              println("⚠️  FIRST BRANCH COMMITTED: This should trigger checkpoint creation")
            }
            if (commitPC.toInt == 0x8) {
              println("🚨 FAILURE: SECOND BRANCH COMMITTED! Multiple branches in pipeline!")
              println("This violates the <=1 branch constraint and exposes the throttling bug!")
              assert(false, "Multi-branch throttling bug: Committed second branch instruction. Test failed.")
            }
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout (expecting timeout due to bug)
      commitMonitor.join()
      
      println(s"\n=== MULTI-BRANCH BUG TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      val hasCommittedSecondBranch = commitedInstructions.exists(_._1 == BigInt(0x8))
      if (timeoutCycles >= maxCycles || hasCommittedSecondBranch) {
        if(hasCommittedSecondBranch) {
            println("🚨 CRITICAL FAILURE: Both branch instructions were processed, violating the <=1 branch constraint!")
        } else {
            println("🚨 FAILURE: Multi-branch bug successfully exposed (CPU hanged/timed out).")
        }
        println("Expected: CPU should correctly handle/throttle multiple branches.")
        println("Actual: CPU either hanged or incorrectly processed multiple branches, confirming a bug.")
        println("\nBug details:")
        println("  - RenamePlugin may lack branch instruction counting/throttling.")
        println("  - Multiple branches in flight can cause speculation or checkpoint conflicts, leading to a hang.")
        
        println(s"\nCommitted before hang: $commitCount/5 instructions")
        commitedInstructions.foreach { case (pc, instr) => 
          println(s"  0x${pc.toString(16)}: $instr")
        }
        
        assert(false, "Multi-branch bug (hanging or incorrect commit) detected. Test failed.")
        
      } else {
        println("🎉 SUCCESS: CPU completed execution without exposing the multi-branch bug.")
        println("This may indicate the bug has been fixed (throttling is working).")
        
        commitedInstructions.foreach { case (pc, instr) => 
          println(s"  0x${pc.toString(16)}: $instr")
        }
        
        assert(true, "Multi-branch throttling test passed. Bug appears to be fixed.")
      }
      
      println("Multi-Branch Instruction Bug Test completed")
    }
  }
  test("Branch Throttling Verification Test - RenamePlugin Fix") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      // Helper function to write instructions to memory - using the proven pattern
      

      // Setup test environment
      println("=== BRANCH THROTTLING VERIFICATION TEST ===")
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      cd.waitSampling(2)
      
      // Create a test program with a single branch that should work correctly
      // This verifies that the fix doesn't break single-branch functionality
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),     // 0x00: r1 = 5
        bne(rj = 1, rd = 0, offset = 8),     // 0x04: SINGLE BRANCH - if r1 != r0, jump to 0x04+8=0x0c
        addi_w(rd = 2, rj = 0, imm = 100),   // 0x08: r2 = 100 (should be skipped)
        addi_w(rd = 3, rj = 0, imm = 200),   // 0x0c: r3 = 200 (branch target)
        idle()                               // 0x10: IDLE instruction to halt CPU
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING BRANCH THROTTLING TEST ===")
      cd.waitSampling(5)
      
      // Monitor commits to verify single-branch functionality works
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 2000  // Longer timeout for proper execution
      
      // Expected execution with throttling fix:
      // - Single branch should work correctly 
      // - r1 != r0 -> branch taken -> skip 0x08 -> jump to 0x0c
      // - Expected commits: 0x00, 0x04, 0x0c (3 total)
      val expectedFinalPCs = Seq(baseAddr, baseAddr + 4, baseAddr + 12)
      
      val commitMonitor = fork {
        while(commitCount < expectedFinalPCs.length && timeoutCycles < maxCycles) { // Stop when expected commits reached
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            // Identify instruction type based on PC
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,5"
              case 0x4 => "bne r1,r0,+8 (SINGLE BRANCH)"
              case 0x8 => "addi r2,r0,100 (SHOULD BE SKIPPED)"
              case 0xc => "addi r3,r0,200 (BRANCH TARGET)"
              case _ => s"unknown@0x${commitPC.toString(16)}"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"✅ Single-branch commit $commitCount: $instrType")
            
            // Assert PC matches expected sequence (only for expected commits)
            if (commitCount <= expectedFinalPCs.length) {
              assert(commitPC == expectedFinalPCs(commitCount - 1), 
                s"Commit $commitCount PC mismatch: expected 0x${expectedFinalPCs(commitCount - 1).toString(16)}, got 0x${commitPC.toString(16)}")
            }
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout
      commitMonitor.join()
      
      println(s"\n=== BRANCH THROTTLING TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      val pcSequence = commitedInstructions.map(_._1.toInt)
      if (timeoutCycles >= maxCycles) {
        assert(false, "FAILURE: TIMEOUT - Branch throttling fix may have caused the CPU to hang. Test failed.")
      } else if (commitCount != expectedFinalPCs.length) {
        assert(false, s"FAILURE: Expected ${expectedFinalPCs.length} commits for correct execution, but got $commitCount. Test failed.")
      } else if (pcSequence.contains(0x8)) {
        assert(false, "FAILURE: Skipped instruction (0x08) was committed. Branch logic is incorrect. Test failed.")
      } else if (pcSequence != expectedFinalPCs.map(_.toInt)) {
        assert(false, s"FAILURE: Committed PC sequence does not match expected correct flow. Got: ${pcSequence.map(pc => f"0x$pc%02x").mkString(" -> ")}. Test failed.")
      } else {
        println("🎉 SUCCESS: Branch throttling fix working correctly!")
        println("Results:")
      
        commitedInstructions.foreach { case (pc, instr) => 
          println(s"  0x${pc.toString(16)}: $instr")
        }

        println("\n✅ MAIN SUCCESS: CPU completed execution without hanging and with correct branch behavior!")
        println("Branch throttling appears to be implemented correctly, allowing single branches to work.")
        assert(true, "Branch Throttling Verification Test passed: Fix is working.")
      }
      
      println("Branch Throttling Verification Test completed")
    }
  }
  
  
  test("Branch Prediction Test - Multiple Branches Speculation Failure") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      

      // Setup test environment
      println("=== BRANCH PREDICTION SPECULATION FAILURE TEST ===")
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      cd.waitSampling(2)
      
      // Create a test program with multiple branches to expose speculation failure
      // This test is designed to expose bugs where multiple branches can't be handled
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 5),     // 0x00: r1 = 5
        addi_w(rd = 2, rj = 0, imm = 10),    // 0x04: r2 = 10  
        beq(rj = 1, rd = 2, offset = 12),    // 0x08: Branch 1: if r1 == r2, jump to 0x08+12=0x14 (false, not taken)
        addi_w(rd = 3, rj = 0, imm = 100),   // 0x0c: r3 = 100 (should execute)
        beq(rj = 0, rd = 0, offset = 8),     // 0x10: Branch 2: if r0 == r0, jump to 0x10+8=0x18 (true, taken)
        addi_w(rd = 4, rj = 0, imm = 200),   // 0x14: r4 = 200 (should be skipped due to second branch)
        addi_w(rd = 5, rj = 0, imm = 300),   // 0x18: r5 = 300 (branch target)
        idle()                               // 0x1c: IDLE instruction to halt CPU
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING CPU EXECUTION ===")
      cd.waitSampling(5)
      
      // Monitor commits with detailed branch verification
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 2000
      
      // Expected execution flow if all branches are handled correctly:
      val expectedFinalPCs = Seq(BigInt(0x0), BigInt(0x4), BigInt(0x8), BigInt(0xc), BigInt(0x10), BigInt(0x18))
      
      val commitMonitor = fork {
        while(commitCount < expectedFinalPCs.length && timeoutCycles < maxCycles) { // Allow one extra for unexpected
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,5"
              case 0x4 => "addi r2,r0,10"  
              case 0x8 => "beq r1,r2,+12"
              case 0xc => "addi r3,r0,100"
              case 0x10 => "beq r0,r0,+8"
              case 0x14 => "addi r4,r0,200 (SHOULD BE SKIPPED)"
              case 0x18 => "addi r5,r0,300"
              case _ => s"unknown@0x${commitPC.toString(16)}"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"Committed instruction $commitCount: $instrType")

            // Assert PC matches expected sequence
            assert(commitCount - 1 < expectedFinalPCs.length, s"Too many commits. Expected ${expectedFinalPCs.length} but got $commitCount at PC=0x${commitPC.toString(16)}. Test failed.")
            assert(commitPC == expectedFinalPCs(commitCount - 1), 
              s"Commit $commitCount PC mismatch: expected 0x${expectedFinalPCs(commitCount - 1).toString(16)}, got 0x${commitPC.toString(16)}. Test failed.")
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout
      commitMonitor.join()
      
      println(s"\n=== BRANCH PREDICTION TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      val finalPcSequence = commitedInstructions.map(_._1)
      if (timeoutCycles >= maxCycles || commitCount != expectedFinalPCs.length || finalPcSequence != expectedFinalPCs) {
        println("🚨 FAILURE: Multi-branch speculation failure detected (timeout/incorrect execution).")
        println("Expected: CPU should handle multiple branches gracefully and commit correct sequence.")
        println(s"Actual committed PCs: ${finalPcSequence.map(pc => s"0x${pc.toString(16)}").mkString(" -> ")}")
        println(s"Expected committed PCs: ${expectedFinalPCs.map(pc => s"0x${pc.toString(16)}").mkString(" -> ")}")
        println("This indicates:")
        println("  1. Multiple branches in flight causing speculation conflicts or pipeline stall.")
        println("  2. Inability to rollback on misprediction, or incorrect prediction/resolution.")
        println("  3. BruEU or pipeline state corruption.")
        
        assert(false, "Multiple branch speculation failure detected. Test failed.")
        
      } else {
        println("🎉 SUCCESS: All instructions committed successfully and in correct order!")
        println("This means the CPU already handles multiple branches correctly.")
        commitedInstructions.foreach { case (pc, instr) => 
          println(s"  0x${pc.toString(16)}: $instr")
        }
        assert(true, "Multiple branch speculation failure test passed. Bug appears to be fixed.")
      }
      
      println("Branch Prediction Speculation Failure Test completed")
    }
  }

  
  // ===== IDLE INSTRUCTION HANDLING BUG TEST =====
  test("IDLE Instruction Handling Bug Test - Speculative Instructions Committed After IDLE") {
    SimConfig.withWave.compile(new CpuFullTestBench(pCfg, dCfg, ifuCfg, axiConfig, fifoDepth)).doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(10)

      val sram = dut.framework.getService[TestOnlyMemSystemPlugin].getSram()

      // Helper function to write instructions to memory
      

      // Setup test environment
      println("=== IDLE INSTRUCTION HANDLING BUG TEST ===")
      dut.io.initMemEnable #= true
      dut.io.initMemAddress #= 0
      dut.io.initMemData #= 0
      dut.io.enableCommit #= false
      
      cd.waitSampling(10)
      cd.waitSampling(2)
      
      // Create a test program designed to expose IDLE instruction handling bug
      val baseAddr = BigInt("0", 16)
      val instructions = Seq(
        addi_w(rd = 1, rj = 0, imm = 10),    // 0x00: r1 = 10
        addi_w(rd = 2, rj = 0, imm = 20),    // 0x04: r2 = 20  
        addi_w(rd = 3, rj = 0, imm = 30),    // 0x08: r3 = 30
        idle(),                              // 0x0c: CPU should stop HERE
        addi_w(rd = 4, rj = 0, imm = 40),    // 0x10: r4 = 40 (SHOULD NOT BE COMMITTED)
        addi_w(rd = 5, rj = 0, imm = 50),    // 0x14: r5 = 50 (SHOULD NOT BE COMMITTED)
        addi_w(rd = 6, rj = 0, imm = 60)     // 0x18: r6 = 60 (SHOULD NOT BE COMMITTED)
      )
      
      CpuFullSpecHelper.writeInstructionsToMem(dut, baseAddr, instructions)
      
      // Deactivate initMemEnable to allow CPU to start
      dut.io.initMemEnable #= false
      println("=== CPU CONTROL DEACTIVATED (initMemEnable=0) ===")
      cd.waitSampling(5)
      println("Memory writing completed, CPU can now start")
      
      println("=== STARTING IDLE INSTRUCTION HANDLING TEST ===")
      cd.waitSampling(5)
      
      // Monitor commits to observe the bug
      var commitCount = 0
      val commitedInstructions = mutable.ArrayBuffer[(BigInt, String)]()
      var timeoutCycles = 0
      val maxCycles = 1000  // Reasonable timeout
      
      val commitMonitor = fork {
        while(commitCount < 10 && timeoutCycles < maxCycles) {
          cd.waitSampling()
          timeoutCycles += 1
          
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"COMMIT: PC=0x${commitPC.toString(16)}")
            
            // Identify instruction type based on PC
            val instrType = commitPC.toInt match {
              case 0x0 => "addi r1,r0,10"
              case 0x4 => "addi r2,r0,20"
              case 0x8 => "addi r3,r0,30"
              case 0xc => "idle (SHOULD BE COMMITTED - CPU stops after this)"
              case 0x10 => "addi r4,r0,40 (SHOULD NOT BE COMMITTED)"
              case 0x14 => "addi r5,r0,50 (SHOULD NOT BE COMMITTED)"
              case 0x18 => "addi r6,r0,60 (SHOULD NOT BE COMMITTED)"
              case _ => s"GARBAGE@0x${commitPC.toString(16)} (SHOULD NOT BE COMMITTED)"
            }
            
            commitedInstructions += ((commitPC, instrType))
            commitCount += 1
            println(s"IDLE test commit $commitCount: $instrType")
            
            // Check for incorrect commits
            if (commitPC.toInt > 0xc) {
              println(s"🚨 BUG DETECTED: Committed instruction at PC=0x${commitPC.toString(16)} after IDLE!")
              println("This indicates that the IDLE instruction did not properly stop the CPU")
              println("or flush speculative instructions from the pipeline.")
            }
          }
        }
      }
      
      // Enable commits and start execution
      dut.io.enableCommit #= true
      
      // Wait for completion or timeout
      commitMonitor.join()
      
      println(s"\n=== IDLE INSTRUCTION HANDLING TEST RESULTS ===")
      println(s"Total commits: $commitCount")
      println(s"Timeout cycles: $timeoutCycles")
      
      // Analyze results
      val pcSequence = commitedInstructions.map(_._1.toInt)
      val expectedSequence = Seq(0x0, 0x4, 0x8, 0xc)  // Include IDLE instruction itself
      
      if (pcSequence == expectedSequence) {
        println("🎉 SUCCESS: IDLE instruction handling working correctly!")
        println("✅ IDLE instruction was committed as expected")
        println("✅ No speculative instructions after IDLE were committed")
        
        assert(true, "IDLE instruction handling test passed.")
        
      } else {
        println("🚨 FAILURE: IDLE instruction handling bug detected!")
        println("Actual behavior:")
        println(s"  Committed PCs: ${pcSequence.map(pc => f"0x$pc%02x").mkString(" -> ")}")
        println("Expected behavior:")
        println("  Should be: 0x00 -> 0x04 -> 0x08 -> 0x0c (IDLE committed, then stop)")
        println("\n🚨 ROOT CAUSE: IDLE instruction does not properly stop CPU execution")
        println("Evidence of bugs:")
        println("  1. IDLE instruction may not be committed correctly")
        println("  2. Instructions after IDLE are incorrectly committed")
        println("  3. Speculative instructions in pipeline are incorrectly committed")
        
        // More detailed analysis - instructions AFTER IDLE (> 0xc) should not be committed
        val incorrectCommits = pcSequence.filter(_ > 0xc)
        if (incorrectCommits.nonEmpty) {
          println(s"\n🚨 SPECIFIC BUG: ${incorrectCommits.length} incorrect commits detected:")
          incorrectCommits.foreach { pc =>
            println(s"     - 0x${pc.toBigInt.toString(16)} (should not be committed)")
          }
        }
        
        assert(false, "IDLE instruction handling bug detected, test failed.")
      }
      
      println("IDLE Instruction Handling Bug Test completed")
    }
  }


  thatsAll()
}

// Abstracted IO bundle for better type safety
case class CpuFullTestBenchIo(robConfig: ROBConfig[RenamedUop]) extends Bundle {
  val enableCommit = in Bool()
  val commitValid = out Bool()
  val commitEntry = out(ROBFullEntry[RenamedUop](robConfig))
  // Test helper ports for memory initialization - use 16-bit address to match SRAM
  val initMemAddress = in UInt(16 bits)
  val initMemData = in UInt(32 bits)
  val initMemEnable = in Bool()
}

class CpuFullTestBench(val pCfg: PipelineConfig, val dCfg: DataCachePluginConfig, val ifuCfg: InstructionFetchUnitConfig, val axiConfig: Axi4Config, val fifoDepth: Int) extends Component {
  val UOP_HT = HardType(RenamedUop(pCfg))
  
  // Configuration for supporting components - need to define before io
  val robConfig = ROBConfig[RenamedUop](
    robDepth = pCfg.robDepth,
    pcWidth = pCfg.pcWidth,
    commitWidth = pCfg.commitWidth,
    allocateWidth = pCfg.renameWidth,
    numWritebackPorts = pCfg.totalEuCount, // Include ALU and BRU
    uopType = UOP_HT,
    defaultUop = () => RenamedUop(pCfg).setDefault(),
    exceptionCodeWidth = pCfg.exceptionCodeWidth
  )
  
  val io = CpuFullTestBenchIo(robConfig)
  
  // Test setup plugin for connecting SimpleFetchPipelinePlugin to testbench
  class CpuFullTestSetupPlugin(testIo: CpuFullTestBenchIo, pCfg: PipelineConfig) extends Plugin {
    val setup = create early new Area {
      val fetchService = getService[SimpleFetchPipelineService]
      val dcService = getService[DataCacheService]
      val bpuService = getService[BpuService]
      
      // Connect the fetch service - SimpleFetchPipelinePlugin outputs directly
      // No need for external input stream
      
      // Connect redirect port - CRITICAL: Use this to control CPU startup
      val redirectPort = fetchService.newHardRedirectPort(0)
      redirectPort.valid := testIo.initMemEnable  // Keep redirecting while writing memory
      redirectPort.payload := 0  // Keep PC at reset vector
      
      // DEBUG: Add logging to track redirect control
      when(ClockDomain.current.isResetActive) {
        // During reset, don't log
      } otherwise {
        when(testIo.initMemEnable) {
          report(L"[CPU_CONTROL] initmem... prevent pc incr")
        } otherwise {
          report(L"[CPU_CONTROL] initMemEnable=0, CPU free to run")
        }
      }
      
      // BPU update port - set to idle for basic tests
      val bpuUpdatePort = bpuService.newBpuUpdatePort()
      bpuUpdatePort.valid := False
      bpuUpdatePort.payload.assignDontCare()
    }
    
    val logic = create late new Area {
      val checkpointService = getService[CheckpointManagerService]
      val robService = getService[ROBService[RenamedUop]]
      val fetchService = getService[SimpleFetchPipelineService]
      
      // CRITICAL: Connect checkpoint triggers for real branch prediction recovery
      // 1. Save checkpoint trigger: For simplicity, always save on any branch prediction
      //    In a real system, this would be driven by the branch prediction logic
      // NOTE: Checkpoint triggers are now handled by RenamePlugin and CommitPlugin
      // - saveCheckpointTrigger: Driven by RenamePlugin when branch instructions are renamed
      // - restoreCheckpointTrigger: Driven by CommitPlugin when ROB flush occurs
      
      // ROB flush port - will be controlled by IDLE handling logic in main testbench area
      // (removed setIdle() call since IDLE handling needs to control flush)
      
      // Add debug logging for checkpoint operations
      when(checkpointService.getSaveCheckpointTrigger()) {
        report(L"[CHECKPOINT] Save checkpoint triggered")
      }
      when(checkpointService.getRestoreCheckpointTrigger()) {
        report(L"[CHECKPOINT] Restore checkpoint triggered due to ROB flush")
      }
    }
  }
  
  val renameMapConfig = RenameMapTableConfig(
    archRegCount = pCfg.archGprCount,
    physRegCount = pCfg.physGprCount,
    numReadPorts = pCfg.renameWidth * 3,
    numWritePorts = pCfg.renameWidth
  )
  
  val flConfig = SimpleFreeListConfig(
    numPhysRegs = pCfg.physGprCount,
    
    numInitialArchMappings = pCfg.archGprCount,
    numAllocatePorts = pCfg.renameWidth,
    numFreePorts = pCfg.commitWidth
  )
  
    val lsuConfig = LsuConfig(
    lqDepth = 16,
    sqDepth = 16,
    robPtrWidth = pCfg.robPtrWidth,
    pcWidth = pCfg.pcWidth,
    dataWidth = pCfg.dataWidth,
    physGprIdxWidth = pCfg.physGprIdxWidth,
    exceptionCodeWidth = pCfg.exceptionCodeWidth,
    commitWidth = pCfg.commitWidth,
    dcacheRefillCount = 2
  )

  val dParams = DataCachePluginConfig.toDataCacheParameters(dCfg)

  val framework = new Framework(
    Seq(
      // Memory system
      new TestOnlyMemSystemPlugin(axiConfig = axiConfig),
      new DataCachePlugin(dCfg),
      new IFUPlugin(ifuCfg),
      
      // BPU and fetch
      new BpuPipelinePlugin(pCfg),
      new SimpleFetchPipelinePlugin(pCfg, ifuCfg, fifoDepth),
      
      // Infrastructure plugins
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin[RenamedUop](pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin[BypassMessage](payloadType = HardType(BypassMessage(pCfg))),
      
      // CheckpointManagerPlugin for proper branch prediction recovery
      new CheckpointManagerPlugin(pCfg, renameMapConfig, flConfig),
      new RenameMapTablePlugin(ratConfig = renameMapConfig),
      new SuperScalarFreeListPlugin(flConfig),
      // Core pipeline
      new IssuePipeline(pCfg),
      
      // Real CommitPlugin from parallax.issue package (moved after pipeline)
      new parallax.issue.CommitPlugin(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(pCfg, renameMapConfig, flConfig),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      
      // Execution units - ALU and BRU
      new AluIntEuPlugin("AluIntEU", pCfg),
      new BranchEuPlugin("BranchEU", pCfg),
      new LsuEuPlugin("LsuEU", pCfg, lsuConfig = lsuConfig, dParams),
      new AguPlugin(lsuConfig, supportPcRel = true),
      new StoreBufferPlugin(pCfg, lsuConfig, dParams, lsuConfig.sqDepth),
      new LoadQueuePlugin(pCfg, lsuConfig, dParams, lsuConfig.lqDepth),
      // Dispatch and linking
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg),
      
      // Test setup
      new CpuFullTestSetupPlugin(io, pCfg)
    )
  )
  
  // Connect commit controller - let CommitPlugin handle IDLE logic
  val commitController = framework.getService[CommitService]
  val robService = framework.getService[ROBService[RenamedUop]]
  val commitSlots = robService.getCommitSlots(pCfg.commitWidth)
  
  // Simple commit enable without IDLE handling - CommitPlugin handles IDLE
  commitController.setCommitEnable(io.enableCommit)
  
  // Connect commit output
  io.commitValid := commitSlots.head.canCommit // Assuming commitWidth=1 for simplicity in testbench IO
  io.commitEntry := commitSlots.head.entry
  
  // Connect fetch service to issue pipeline
  val fetchService = framework.getService[SimpleFetchPipelineService]
  val issuePipeline = framework.getService[IssuePipeline]
  val fetchOutStream = fetchService.fetchOutput()
  val issueEntryStage = issuePipeline.entryStage
  val issueSignals = issuePipeline.signals
  
  issueEntryStage.valid := fetchOutStream.valid
  fetchOutStream.ready := issueEntryStage.isReady

  val fetched = fetchOutStream.payload
  
  // For fetchWidth=2, we need to properly unpack both instructions
  val instructionVec = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  
  // SimpleFetchPipelinePlugin might provide only one instruction at a time, or packed.
  // Assuming it provides one 32-bit instruction per cycle for simplicity here,
  // even if fetchGroupDataWidth is wider.
  instructionVec(0) := fetched.instruction
  for (i <- 1 until pCfg.fetchWidth) {
    instructionVec(i) := 0  // Placeholder, assuming only 1 instruction valid per cycle from SimpleFetchPipelinePlugin
  }

  // Only connect valid data when fetch output is valid
  when(fetchOutStream.valid) {
    issueEntryStage(issueSignals.GROUP_PC_IN) := fetched.pc
    issueEntryStage(issueSignals.RAW_INSTRUCTIONS_IN) := instructionVec
    issueEntryStage(issueSignals.VALID_MASK) := B"01"  // Mark only first instruction as valid
    issueEntryStage(issueSignals.IS_FAULT_IN) := False
  } otherwise {
    issueEntryStage(issueSignals.GROUP_PC_IN) := 0
    issueEntryStage(issueSignals.RAW_INSTRUCTIONS_IN).assignDontCare()
    issueEntryStage(issueSignals.VALID_MASK) := B"00"  // No valid instructions
    issueEntryStage(issueSignals.IS_FAULT_IN) := False
  }
  
  // === PRF Access for Architectural Register Verification ===
  val prfService = framework.getService[PhysicalRegFileService]
  val prfReadPort = prfService.newPrfReadPort()
  prfReadPort.simPublic()
  prfReadPort.valid   := False 
  prfReadPort.address := 0
  // === RAT Query Interface for Testing ===
  val ratService = framework.getService[RatControlService]
  val ratMapping = ratService.getCurrentState().mapping
  ratMapping.simPublic()
}
