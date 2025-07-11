// filename: test/scala/IssueToAluAndLsuSpec.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import parallax.common._
import parallax.components.rename._
import parallax.components.rob._
import parallax.execute.{AluIntEuPlugin, LsuEuPlugin, WakeupPlugin, BypassPlugin}
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu.BpuPipelinePlugin
import parallax.components.lsu._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.bus._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.utilities._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4}

import scala.collection.mutable
import scala.util.Random

// =========================================================================
//  Test Helper Classes
// =========================================================================

/** This plugin provides a concrete memory system implementation (a simulated SRAM)
  * for the DataCache to connect to via the DBusService.
  */
class TestOnlyMemSystemPlugin(axiConfig: Axi4Config) extends Plugin with DBusService {
  val hw = create early new Area {
    private val sramSize = BigInt("4000", 16) // 16 KiB
    private val extSramCfg = ExtSRAMConfig(
      addressWidth = 16,
      dataWidth = 32,
      virtualBaseAddress = BigInt("00000000", 16),
      sizeBytes = sramSize,
      readWaitCycles = 0,
      enableLog = true
    )
    val sram = new SimulatedSRAM(extSramCfg)
    val ctrl = new ExtSRAMController(axiConfig, extSramCfg)
    ctrl.io.ram <> sram.io.ram
    ctrl.io.simPublic()
    sram.io.simPublic()
  }

  override def getBus(): Axi4 = hw.ctrl.io.axi
  def getSram(): SimulatedSRAM = hw.sram
}

// =========================================================================
//  Mock Services & Test Bench Helpers
// =========================================================================

class MockFetchServiceForLsu(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def newRedirectPort(priority: Int): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
}

// Mock flush source to provide default values for ROB flush signals
class MockFlushService(pCfg: PipelineConfig) extends Plugin {
  val logic = create late new Area {
    val robService = getService[ROBService[RenamedUop]]
    val robFlushPort = robService.getFlushPort()
    
    // Create a register to drive targetRobPtr to avoid constant optimization issues
    val flushTargetReg = Reg(UInt(pCfg.robPtrWidth)) init(0)
    
    // Provide default inactive values
    robFlushPort.valid := False
    robFlushPort.payload.reason := FlushReason.NONE 
    robFlushPort.payload.targetRobPtr := flushTargetReg  // Use register instead of constant
  }
}


// =========================================================================
//  The Test Bench (original LSU-only)
// =========================================================================

class IssueToAluAndLsuTestBench(val pCfg: PipelineConfig) extends Component {
  ParallaxLogger.warning(s"pCfg.totalEuCount = ${pCfg.totalEuCount}");

  val UOP_HT = HardType(RenamedUop(pCfg))

  val io = new Bundle {
    val fetchStreamIn = slave(Stream(FetchedInstr(pCfg)))
    val enableCommit = in Bool ()
    // Expose commit port for monitoring
    val commitValid = out Bool()
    val commitEntry = out(ROBFullEntry(ROBConfig(
      robDepth = pCfg.robDepth,
      pcWidth = pCfg.pcWidth,
      commitWidth = pCfg.commitWidth,
      allocateWidth = pCfg.renameWidth,
      numWritebackPorts = pCfg.totalEuCount,
      uopType = UOP_HT,
      defaultUop = () => RenamedUop(pCfg).setDefault(),
      exceptionCodeWidth = pCfg.exceptionCodeWidth
    )))
  }

  // 在Framework创建前统一创建所有配置
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

  val dCacheConfig = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = pCfg.dataWidth.value,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 64,
    transactionIdWidth = pCfg.transactionIdWidth
  )

  val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCacheConfig)

  val axiConfig = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = pCfg.xlen,
    idWidth = 1,
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

  val renameMapConfig = RenameMapTableConfig(
    archRegCount = pCfg.archGprCount,
    physRegCount = pCfg.physGprCount,
    numReadPorts = pCfg.renameWidth * 3,
    numWritePorts = pCfg.renameWidth
  )
  
  val flConfig = SuperScalarFreeListConfig(
    numPhysRegs = pCfg.physGprCount,
    resetToFull = true,
    numInitialArchMappings = pCfg.archGprCount,
    numAllocatePorts = pCfg.renameWidth,
    numFreePorts = pCfg.commitWidth
  )

  val framework = new Framework(
    Seq(
      new MockFetchServiceForLsu(pCfg),
      new MockFlushService(pCfg),  // Add mock flush service
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin[BypassMessage](payloadType = HardType(BypassMessage(pCfg))),
      new CommitPlugin(pCfg),
      new BpuPipelinePlugin(pCfg),
      new LoadQueuePlugin(pCfg, lsuConfig, dCacheParams, lsuConfig.lqDepth),
      new StoreBufferPlugin(pCfg, lsuConfig, dCacheParams, lsuConfig.sqDepth),
      new AguPlugin(lsuConfig, supportPcRel = true),
      new DataCachePlugin(dCacheConfig),
      new TestOnlyMemSystemPlugin(axiConfig),
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
      new CheckpointManagerPlugin(pCfg, renameMapConfig, flConfig),
      new RenameMapTablePlugin(ratConfig = renameMapConfig),
      new SuperScalarFreeListPlugin(flConfig),
new RenamePlugin(pCfg, renameMapConfig, flConfig),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      // Add ALU EU for ADDI support and LSU EU for store/load
      new AluIntEuPlugin("AluEU", pCfg),
      new LsuEuPlugin("LsuEU", pCfg, lsuConfig, dCacheParams),
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchServiceForLsu]
  fetchService.fetchStreamIn << io.fetchStreamIn

  val commitController = framework.getService[CommitPlugin]
  commitController.getCommitEnable() := io.enableCommit

  val robService = framework.getService[ROBService[RenamedUop]]
  val commitSlot = robService.getCommitSlots(pCfg.commitWidth).head
  io.commitValid := commitSlot.valid
  io.commitEntry := commitSlot.entry

  // Expose memory system for verification
  val memSystem = framework.getService[TestOnlyMemSystemPlugin]
  
  // Connect fetch service to issue pipeline
  val issuePipeline = framework.getService[IssuePipeline]
  val fetchOutStream = fetchService.fetchOutput()
  val issueEntryStage = issuePipeline.entryStage
  val issueSignals = issuePipeline.signals
  
  issueEntryStage.valid := fetchOutStream.valid
  fetchOutStream.ready := issueEntryStage.isReady

  val fetched = fetchOutStream.payload
  val instructionVec = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
  instructionVec(0) := fetched.instruction
  for (i <- 1 until pCfg.fetchWidth) {
    instructionVec(i) := 0
  }

  issueEntryStage(issueSignals.GROUP_PC_IN) := fetched.pc
  issueEntryStage(issueSignals.RAW_INSTRUCTIONS_IN) := instructionVec
  issueEntryStage(issueSignals.VALID_MASK) := B"1"
  issueEntryStage(issueSignals.IS_FAULT_IN) := False
  issueEntryStage(issueSignals.FLUSH_PIPELINE) := False
  issueEntryStage(issueSignals.FLUSH_TARGET_PC) := 0

  // === PRF Access for Architectural Register Verification ===
  val prfService = framework.getService[PhysicalRegFileService]
  val prfReadPort = prfService.newReadPort()
  prfReadPort.simPublic()
    prfReadPort.valid   := False 
  prfReadPort.address := 0
  // === RAT Query Interface for Testing ===
  val ratService = framework.getService[RatControlService]
  val ratMapping = ratService.getCurrentState().mapping
  ratMapping.simPublic()
  
  // === Memory System for Direct Access ===
  val memSystemPlugin = framework.getService[TestOnlyMemSystemPlugin]
  val sram = memSystemPlugin.getSram()
  sram.io.simPublic()
}

// =========================================================================
//  Helper Functions for Architectural Register Verification
// =========================================================================

object IssueToAluAndLsuSpecHelper {
  def readArchReg(dut: IssueToAluAndLsuTestBench, archRegIdx: Int): BigInt = {
    val cd = dut.clockDomain
    val physRegIdx = dut.ratMapping(archRegIdx).toBigInt
    dut.prfReadPort.valid #= true
    dut.prfReadPort.address #= physRegIdx
    dut.clockDomain.waitSampling(1)
    val rsp = dut.prfReadPort.rsp.toBigInt
    dut.prfReadPort.valid #= false
    return rsp
  }
  
  def readMemoryWord(dut: IssueToAluAndLsuTestBench, address: BigInt): BigInt = {
    val cd = dut.clockDomain
    
    // Use SRAM testbench interface for direct memory access
    dut.sram.io.tb_readEnable #= true
    dut.sram.io.tb_readAddress #= address // 直接使用字节地址，与DCache一致
    dut.sram.io.tb_writeEnable #= false
    
    // Wait a few cycles for SRAM read
    cd.waitSampling(3)
    
    val data = dut.sram.io.tb_readData.toBigInt
    
    // Clean up
    dut.sram.io.tb_readEnable #= false
    
    return data
  }
}

// =========================================================================
//  The Test Suite
// =========================================================================

class IssueToAluAndLsuSpec extends CustomSpinalSimFunSuite {

  // 创建支持ALU和LSU的配置类
  class AluAndLsuPipelineConfig extends PipelineConfig(
    aluEuCount = 1,  // 需要ALU来执行ADDI指令
    lsuEuCount = 1,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1,
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    robDepth = 16,
    commitWidth = 1,
    transactionIdWidth = 8
  ) {
  }

  val pCfg_complex = new AluAndLsuPipelineConfig()  // 复杂测试使用ALU+LSU配置

  test("StoreAndLoad_Test") {
    // 使用ALU+LSU配置来支持ADDI指令
    val compiled = SimConfig.withConfig(SpinalConfig().copy(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      targetDirectory = "simWorkspace/scala_sim"
    )).allOptimisation.workspacePath("simWorkspace/scala_sim").compile(new IssueToAluAndLsuTestBench(pCfg_complex))
    
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(30000)

      def issueInstr(pc: BigInt, insn: BigInt): Unit = {
        println(s"[ISSUE] PC=0x${pc.toString(16)}, insn=0x${insn.toString(16)}")
        dut.io.fetchStreamIn.valid #= true
        dut.io.fetchStreamIn.payload.pc #= pc
        dut.io.fetchStreamIn.payload.instruction #= insn
        dut.io.fetchStreamIn.payload.predecode.setDefaultForSim()
        dut.io.fetchStreamIn.payload.bpuPrediction.valid #= false
        cd.waitSamplingWhere(dut.io.fetchStreamIn.ready.toBoolean)
        dut.io.fetchStreamIn.valid #= false
        cd.waitSampling(1)
      }

      val pc_start = BigInt("00000000", 16)
      var commitCount = 0
      val expectedCommits = scala.collection.mutable.Queue[BigInt]()

      // Simple commit monitoring with detailed logging
      val commitMonitor = fork {
        while(true) {
          cd.waitSampling()
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"[COMMIT] ✅ PC=0x${commitPC.toString(16)}")
            
            if (expectedCommits.nonEmpty) {
              val expectedPC = expectedCommits.dequeue()
              assert(commitPC == expectedPC, s"PC mismatch: expected 0x${expectedPC.toString(16)}, got 0x${commitPC.toString(16)}")
              commitCount += 1
            }
          }
        }
      }

      println("=== 🚀 开始Store和Load测试 ===")
      dut.io.enableCommit #= false
      cd.waitSampling(5)

      // 真正有说服力的测试序列：
      // 1. ADDI r3, r0, 0x123  (r3 = 0 + 0x123 = 0x123, 给r3设置一个非零值)
      // 2. ST.W r3, r0, 0x200  (存储r3的值0x123到地址0x200)
      // 3. LD.W r1, r0, 0x200  (从地址0x200加载，应该得到0x123)
      // 4. ST.W r0, r0, 0x204  (存储r0的值0到地址0x204)  
      // 5. LD.W r2, r0, 0x204  (从地址0x204加载，应该得到0)

      val store_addr = 0x200
      val test_value = 0x123
      
      val instr_addi = LA32RInstrBuilder.addi_w(rd = 3, rj = 0, imm = test_value)  // r3 = r0 + 0x123
      val instr_store1 = LA32RInstrBuilder.st_w(rd = 3, rj = 0, offset = store_addr)  // MEM[0x200] = r3 (=0x123)
      val instr_load1 = LA32RInstrBuilder.ld_w(rd = 1, rj = 0, offset = store_addr)  // r1 = MEM[0x200]
      val instr_store2 = LA32RInstrBuilder.st_w(rd = 0, rj = 0, offset = store_addr + 4)  // MEM[0x204] = r0 (=0)
      val instr_load2 = LA32RInstrBuilder.ld_w(rd = 2, rj = 0, offset = store_addr + 4)  // r2 = MEM[0x204]

      println(s"[TEST] 多数据模式Store和Load测试序列:")
      println(f"  1. ADDI r3, r0, 0x${test_value}%x (insn=0x${instr_addi}%x) - 设置r3=0x${test_value}%x")
      println(f"  2. ST.W r3, r0, 0x${store_addr}%x (insn=0x${instr_store1}%x) - 存储非零值0x${test_value}%x")
      println(f"  3. LD.W r1, r0, 0x${store_addr}%x (insn=0x${instr_load1}%x) - 验证非零值读取")
      println(f"  4. ST.W r0, r0, 0x${store_addr + 4}%x (insn=0x${instr_store2}%x) - 存储零值0")
      println(f"  5. LD.W r2, r0, 0x${store_addr + 4}%x (insn=0x${instr_load2}%x) - 验证零值读取")

      // 准备期望的提交顺序
      expectedCommits += pc_start          // ADDI r3, r0, 0x123
      expectedCommits += (pc_start + 4)    // Store 1 (非零值)
      expectedCommits += (pc_start + 8)    // Load 1
      expectedCommits += (pc_start + 12)   // Store 2 (零值)
      expectedCommits += (pc_start + 16)   // Load 2

      println("=== 📤 发射指令序列 ===")
      issueInstr(pc_start, instr_addi)         // PC: 0x00000000
      issueInstr(pc_start + 4, instr_store1)   // PC: 0x00000004
      issueInstr(pc_start + 8, instr_load1)    // PC: 0x00000008
      issueInstr(pc_start + 12, instr_store2)  // PC: 0x0000000C
      issueInstr(pc_start + 16, instr_load2)   // PC: 0x00000010

      println("=== ⏱️ 等待执行完成 ===")
      cd.waitSampling(30)  // 给Store和Load序列足够的处理时间

      println("=== ✅ 启用提交 ===")
      dut.io.enableCommit #= true
      
      var timeout = 600  // 增加超时时间，因为有5条指令
      while(commitCount < 5 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 100 == 0) {
          println(s"[WAIT] commitCount=$commitCount/5, timeout=$timeout")
        }
      }
      
      if (timeout > 0) {
        println(s"🎉 SUCCESS: 多数据模式测试完成，成功提交了${commitCount}条指令!")
        assert(commitCount == 5, s"Expected 5 commits, got $commitCount")
        assert(expectedCommits.isEmpty, "Not all expected commits were processed")
        
        // === 验证Store/Load指令序列的语义正确性 ===
        println("=== 🔍 验证 Store 指令通过 Load 指令 ===")
        cd.waitSampling(50)  // 等待更长时间确保数据稳定
        
        // 验证 ADDI 指令是否正确设置了 r3 = 0x123
        println("验证 ADDI 指令是否正确设置了 r3 = 0x123")
        val r3_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 3)
        assert(r3_value == test_value, s"r3 final value check failed: Result was ${r3_value}, expected ${test_value}")
        println(f"✅ ADDI 指令验证通过: r3 = 0x${r3_value}%x")
        
        // 验证 Load1 指令从 Store1 地址读取的数据
        println("验证 Load1 指令从 Store1 地址读取的数据")
        val r1_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 1)
        assert(r1_value == test_value, s"r1 final value check failed: Result was ${r1_value}, expected ${test_value}")
        println(f"✅ Load1 指令验证通过: r1 = 0x${r1_value}%x")
        
        // 验证 Load2 指令从 Store2 地址读取的数据
        println("验证 Load2 指令从 Store2 地址读取的数据")
        val r2_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 2)
        assert(r2_value == 0, s"r2 final value check failed: Result was ${r2_value}, expected 0")
        println(f"✅ Load2 指令验证通过: r2 = 0x${r2_value}%x")
        
        println("✅ Store/Load指令序列验证完成: Load 指令成功读取到 Store 的数据!")
      } else {
        println("⚠️ TIMEOUT: 指令未能在预期时间内提交")
        println("这可能表明LSU EU的Store/Load序列处理存在问题，需要分析日志")
        fail("Timeout waiting for commits - Store/Load test failed")
      }
      
      cd.waitSampling(10)
    }
  }

  test("ComplexLoadStore_Test") {
    // 使用LSU-only testbench但执行更复杂的Store/Load序列
    val compiled = SimConfig.withConfig(SpinalConfig().copy(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      targetDirectory = "simWorkspace/scala_sim"
    )).compile(new IssueToAluAndLsuTestBench(pCfg_complex))
    
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(30000)

      def issueInstr(pc: BigInt, insn: BigInt): Unit = {
        println(s"[ISSUE] PC=0x${pc.toString(16)}, insn=0x${insn.toString(16)}")
        dut.io.fetchStreamIn.valid #= true
        dut.io.fetchStreamIn.payload.pc #= pc
        dut.io.fetchStreamIn.payload.instruction #= insn
        dut.io.fetchStreamIn.payload.predecode.setDefaultForSim()
        dut.io.fetchStreamIn.payload.bpuPrediction.valid #= false
        cd.waitSamplingWhere(dut.io.fetchStreamIn.ready.toBoolean)
        dut.io.fetchStreamIn.valid #= false
        cd.waitSampling(1)
      }

      val pc_start = BigInt("00000000", 16)
      var commitCount = 0
      val expectedCommits = scala.collection.mutable.Queue[BigInt]()

      // Simple commit monitoring with detailed logging
      val commitMonitor = fork {
        while(true) {
          cd.waitSampling()
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"[COMMIT] ✅ PC=0x${commitPC.toString(16)}")
            
            if (expectedCommits.nonEmpty) {
              val expectedPC = expectedCommits.dequeue()
              assert(commitPC == expectedPC, s"PC mismatch: expected 0x${expectedPC.toString(16)}, got 0x${commitPC.toString(16)}")
              commitCount += 1
            }
          }
        }
      }

      println("=== 🚀 开始复杂LSU序列测试 ===")
      dut.io.enableCommit #= false
      cd.waitSampling(5)

      // 复杂LSU测试序列（避免使用ALU，专注于LSU的多种操作模式）：
      // 1. ST.W r0, r0, 0x200  (存储：MEM[0x200] = r0 = 0)
      // 2. ST.W r0, r0, 0x204  (存储：MEM[0x204] = r0 = 0)  
      // 3. LD.W r1, r0, 0x200  (加载：r1 = MEM[0x200] = 0)
      // 4. LD.W r2, r0, 0x204  (加载：r2 = MEM[0x204] = 0)
      // 5. LD.W r3, r0, 0x208  (加载：r3 = MEM[0x208] = 未初始化)
      // 这测试了多个连续的Store和Load操作，以及Store-to-Load forwarding

     val instr_addi1 = LA32RInstrBuilder.addi_w(rd = 10, rj = 0, imm = 0x123)
    val instr_addi2 = LA32RInstrBuilder.addi_w(rd = 11, rj = 0, imm = 0x456)

    // 修改指令序列
    // 1. ST.W r10, r0, 0x200  (存储：MEM[0x200] = 0x123)
    val instr_store1 = LA32RInstrBuilder.st_w(rd = 10, rj = 0, offset = 0x200)
    // 2. ST.W r11, r0, 0x204  (存储：MEM[0x204] = 0x456)
    val instr_store2 = LA32RInstrBuilder.st_w(rd = 11, rj = 0, offset = 0x204)
    // 3. LD.W r3, r0, 0x200  (加载：r3 = MEM[0x200] = 0x123)
    val instr_load1 = LA32RInstrBuilder.ld_w(rd = 3, rj = 0, offset = 0x200)
    // 4. LD.W r4, r0, 0x204  (加载：r4 = MEM[0x204] = 0x456)
    val instr_load2 = LA32RInstrBuilder.ld_w(rd = 4, rj = 0, offset = 0x204)
    // 5. LD.W r5, r0, 0x200  (加载：r5 = MEM[0x200] = 0x123, 再次加载验证)
    val instr_load3 = LA32RInstrBuilder.ld_w(rd = 5, rj = 0, offset = 0x200)

      // 准备期望的提交顺序
      expectedCommits += pc_start        // Store 1
      expectedCommits += (pc_start + 4)  // Store 2
      expectedCommits += (pc_start + 8)  // Load 1
      expectedCommits += (pc_start + 12) // Load 2
      expectedCommits += (pc_start + 16) // Load 3

      println("=== 📤 发射指令序列 ===")
      issueInstr(pc_start, instr_addi1)
      issueInstr(pc_start + 4, instr_addi2)
      issueInstr(pc_start + 8, instr_store1)
      issueInstr(pc_start + 12, instr_store2)
      issueInstr(pc_start + 16, instr_load1)
      issueInstr(pc_start + 20, instr_load2)
      issueInstr(pc_start + 24, instr_load3)

      println("=== ⏱️ 等待执行完成 ===")
      cd.waitSampling(50)  // 给复杂LSU序列更多处理时间

      println("=== ✅ 启用提交 ===")
      dut.io.enableCommit #= true
      
      var timeout = 500  // 增加超时时间用于复杂LSU序列
      while(commitCount < 5 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 100 == 0) {
          println(s"[WAIT] commitCount=$commitCount/5, timeout=$timeout")
        }
      }
      
      if (timeout > 0) {
        println(s"🎉 SUCCESS: 复杂LSU序列测试完成，成功提交了${commitCount}条指令!")
        println("✅ 测试覆盖: Store-to-Load forwarding, 多重Store, 多重Load, Cache miss处理")
        assert(commitCount == 5, s"Expected 5 commits, got $commitCount")
        assert(expectedCommits.isEmpty, "Not all expected commits were processed")
        
        // === 验证复杂Store/Load序列的语义正确性 ===
        println("🔍 开始验证复杂Store/Load序列...")
        cd.waitSampling(50)  // 等待更长时间确保数据稳定
        
        // 验证第三条指令 lw x3, 0x200 的结果 (应该得到第一条store指令的值0x123)
        val r3_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 3)
        println(s"📍 寄存器 r3 = 0x${r3_value.toString(16)} (期望来自store/load序列的0x123)")
        
        // 验证第四条指令 lw x4, 0x204 的结果 (应该得到第二条store指令的值0x456)  
        val r4_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 4)
        println(s"📍 寄存器 r4 = 0x${r4_value.toString(16)} (期望来自store/load序列的0x456)")
        
        // 验证第五条指令 lw x5, 0x200 的结果 (应该得到第一条store指令的值0x123)
        val r5_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 5)
        println(s"📍 寄存器 r5 = 0x${r5_value.toString(16)} (期望来自store/load序列的0x123)")
        
        // 验证store/load序列的语义正确性
        assert(r3_value == BigInt("123", 16), 
               s"Store/Load sequence failed for 0x200: r3=0x${r3_value.toString(16)}, expected 0x123")
        
        assert(r4_value == BigInt("456", 16), 
               s"Store/Load sequence failed for 0x204: r4=0x${r4_value.toString(16)}, expected 0x456")
               
        assert(r5_value == BigInt("123", 16), 
               s"Store/Load sequence failed for 0x200 (second load): r5=0x${r5_value.toString(16)}, expected 0x123")
        
        println("✅ 复杂Store/Load序列的语义验证通过!")
        println("   这验证了Store指令、Load指令、Store-to-Load forwarding和Cache操作的正确性")
        cd.waitSampling(50)  // 增加等待时间，让数据有机会写回内存
      } else {
        println("⚠️ TIMEOUT: 指令未能在预期时间内提交")
        println("这可能表明复杂LSU序列处理存在问题，需要分析Store/Load依赖关系")
        fail("Timeout waiting for commits - Complex LSU sequence test failed")
      }
      
      cd.waitSampling(10)
    }
  }

  test("SimpleLoad_Test") {
    // 使用纯Scala仿真后端避开Verilator语法问题
    val compiled = SimConfig.withConfig(SpinalConfig().copy(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      targetDirectory = "simWorkspace/scala_sim"
    )).compile(new IssueToAluAndLsuTestBench(pCfg_complex))
    
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(15000)

      def issueInstr(pc: BigInt, insn: BigInt): Unit = {
        println(s"[ISSUE] PC=0x${pc.toString(16)}, insn=0x${insn.toString(16)}")
        dut.io.fetchStreamIn.valid #= true
        dut.io.fetchStreamIn.payload.pc #= pc
        dut.io.fetchStreamIn.payload.instruction #= insn
        dut.io.fetchStreamIn.payload.predecode.setDefaultForSim()
        dut.io.fetchStreamIn.payload.bpuPrediction.valid #= false
        cd.waitSamplingWhere(dut.io.fetchStreamIn.ready.toBoolean)
        dut.io.fetchStreamIn.valid #= false
        cd.waitSampling(1)
      }

      val pc_start = BigInt("00000000", 16)
      var commitCount = 0

      // 预先初始化内存地址0x100为已知值
      val test_value = BigInt("deadbeef", 16)
      println(s"🔧 预初始化内存 0x100 = 0x${test_value.toString(16)}")
      dut.sram.io.tb_writeEnable #= true
      dut.sram.io.tb_writeAddress #= BigInt("100", 16) // 直接使用字节地址，与DCache一致
      dut.sram.io.tb_writeData #= test_value
      cd.waitSampling(2)
      dut.sram.io.tb_writeEnable #= false
      
      // 验证初始化成功
      val verify_value = IssueToAluAndLsuSpecHelper.readMemoryWord(dut, BigInt("100", 16))
      println(s"🔍 验证初始化: 内存 0x100 = 0x${verify_value.toString(16)}")
      assert(verify_value == test_value, s"Memory initialization failed: got 0x${verify_value.toString(16)}, expected 0x${test_value.toString(16)}")

      // Simple commit monitoring with detailed logging
      val commitMonitor = fork {
        while(true) {
          cd.waitSampling()
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"[COMMIT] ✅ PC=0x${commitPC.toString(16)}")
            commitCount += 1
          }
        }
      }

      println("=== 🚀 开始LSU EU集成测试 ===")
      dut.io.enableCommit #= false
      cd.waitSampling(5)

      // 简化测试：Load指令，使用寄存器0（总是0）作为基址，但使用SRAM范围内的地址
      val instr_lw = LA32RInstrBuilder.ld_w(rd = 2, rj = 0, offset = 0x100)  // r2 = MEM[r0 + 0x100] = MEM[0x100]
      println(s"[TEST] Load指令: ld.w r2, r0, 0x100 (insn=0x${instr_lw.toString(16)})")

      println("=== 📤 发射指令序列 ===")
      issueInstr(pc_start, instr_lw)   // PC: 0x00000000

      println("=== ⏱️ 等待执行完成 ===")
      cd.waitSampling(20)  // 增加等待时间给LSU更多处理时间

      println("=== ✅ 启用提交 ===")
      dut.io.enableCommit #= true
      
      var timeout = 200  // 增加超时时间
      while(commitCount < 1 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 50 == 0) {
          println(s"[WAIT] commitCount=$commitCount/1, timeout=$timeout")
        }
      }
      
      if (timeout > 0) {
        println(s"🎉 SUCCESS: LSU测试完成，成功提交了${commitCount}条指令!")
        assert(commitCount == 1, s"Expected 1 commits, got $commitCount")
        // === 验证load指令结果 ===
        println("🔍 开始验证load指令结果...")
        cd.waitSampling(50)  // 等待更长时间确保数据写回
        
        // 先检查物理寄存器映射
        val physReg2 = dut.ratMapping(2).toBigInt
        println(s"📍 寄存器r2映射到物理寄存器p${physReg2}")
        
        // 验证load指令是否正确读取了预初始化的内存数据并存储到寄存器
        val r2_value = IssueToAluAndLsuSpecHelper.readArchReg(dut, 2)
        println(s"📍 寄存器 r2 = 0x${r2_value.toString(16)} (期望 0x${test_value.toString(16)})")
        
        // 再次检查内存中的值确认初始化正确
        val current_mem_value = IssueToAluAndLsuSpecHelper.readMemoryWord(dut, BigInt("100", 16))
        println(s"📍 内存 0x100 = 0x${current_mem_value.toString(16)} (确认初始化)")
        
        // 验证load指令正确地从内存读取了预初始化的数据
        assert(r2_value == test_value, 
               s"Load instruction failed: r2=0x${r2_value.toString(16)}, expected 0x${test_value.toString(16)}. " +
               s"Memory contains 0x${current_mem_value.toString(16)}. " +
               s"Physical register mapping: r2->p${physReg2}")
        
        println("✅ Load指令验证通过!")

      } else {
        println("⚠️ TIMEOUT: 指令未能在预期时间内提交")
        println("这可能表明LSU EU的某个阶段存在问题，需要分析日志")
        fail("Timeout waiting for commits - LSU EU may have issues")
      }
      
      cd.waitSampling(5)
    }
  }

  thatsAll()
}
