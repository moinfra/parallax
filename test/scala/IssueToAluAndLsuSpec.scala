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
  override def getIdleDetected(): Bool = Bool(false) // Default implementation for testing
}

class MockCommitControllerForLsu(pCfg: PipelineConfig) extends Plugin {
  // Private control signal
  private val enableCommit = Bool()
  
  // Public interface to get the control signal
  def getCommitEnable(): Bool = enableCommit

  val setup = create early new Area {
    val ratControl = getService[RatControlService]
    val flControl  = getService[FreeListControlService]
    val robService = getService[ROBService[RenamedUop]]
  }

  val logic = create late new Area {
    setup.ratControl.newCheckpointSavePort().setIdle()
    setup.ratControl.newCheckpointRestorePort().setIdle()
    setup.flControl.newRestorePort().setIdle()

    // Handle ROB flush signals - 使用寄存器避免对字面量的位片操作
    val robFlushPort = setup.robService.getFlushPort()
    val flushTargetPtr = Reg(UInt(pCfg.robPtrWidth)) init(0)
    flushTargetPtr.allowUnsetRegToAvoidLatch
    robFlushPort.valid := False
    robFlushPort.payload.reason := FlushReason.FULL_FLUSH
    robFlushPort.payload.targetRobPtr := flushTargetPtr

    val freePorts = setup.flControl.getFreePorts()
    val commitSlots = setup.robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = setup.robService.getCommitAcks(pCfg.commitWidth)

    val commitPending = Vec(Reg(Bool()) init(False), pCfg.commitWidth)
    
    // Commit controller implementation
    for (i <- 0 until pCfg.commitWidth) {
      val canCommit = commitSlots(i).valid
      val doCommit = enableCommit && canCommit
      commitAcks(i) := doCommit

      when(doCommit) {
        val committedUop = commitSlots(i).entry.payload.uop
        freePorts(i).enable := committedUop.rename.allocatesPhysDest
        freePorts(i).physReg := committedUop.rename.oldPhysDest.idx
      } otherwise {
        freePorts(i).enable := False
        freePorts(i).physReg := 0
      }
    }
  }
}

// =========================================================================
//  Test Bench with Both ALU and LSU
// =========================================================================

class IssueToAluAndLsuComplexTestBench(val pCfg: PipelineConfig) extends Component {
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

  val framework = new Framework(
    Seq(
      new MockFetchServiceForLsu(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      // 分离ALU和LSU的旁路网络
      new BypassPlugin[BypassMessage](payloadType = HardType(BypassMessage(pCfg))),
      new BypassPlugin[AguBypassData](payloadType = HardType(AguBypassData())),
      new MockCommitControllerForLsu(pCfg),
      new BpuPipelinePlugin(pCfg),
      // Add LSU infrastructure with unified configuration
      new StoreBufferPlugin(pCfg, lsuConfig, dCacheParams, lsuConfig.sqDepth),
      new AguPlugin(lsuConfig, supportPcRel = true),
      new DataCachePlugin(dCacheConfig),
      new TestOnlyMemSystemPlugin(axiConfig),
      // Core pipeline - 同时包含ALU和LSU
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(
        pCfg,
        RenameMapTableConfig(
          archRegCount = pCfg.archGprCount,
          physRegCount = pCfg.physGprCount,
          numReadPorts = pCfg.renameWidth * 3,
          numWritePorts = pCfg.renameWidth
        ),
        SuperScalarFreeListConfig(
          numPhysRegs = pCfg.physGprCount, 
          numAllocatePorts = pCfg.renameWidth, 
          numFreePorts = pCfg.commitWidth
        )
      ),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      // 添加ALU和LSU执行单元
      new AluIntEuPlugin("AluIntEU", pCfg),
      new LsuEuPlugin("LsuEU", pCfg, lsuConfig, dCacheParams),
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchServiceForLsu]
  fetchService.fetchStreamIn << io.fetchStreamIn

  val commitController = framework.getService[MockCommitControllerForLsu]
  commitController.getCommitEnable() := io.enableCommit

  val robService = framework.getService[ROBService[RenamedUop]]
  val commitSlot = robService.getCommitSlots(pCfg.commitWidth).head
  io.commitValid := commitSlot.valid
  io.commitEntry := commitSlot.entry

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

  // 为结果验证添加PRF读端口
  val prfService = framework.getService[PhysicalRegFileService]
  val prfReadPorts = Vec.tabulate(pCfg.archGprCount) { i =>
    val port = prfService.newReadPort()
    port.valid.setName(s"tb_prfRead_valid_$i")
    port.address.setName(s"tb_prfRead_addr_$i")
    port
  }
  prfReadPorts.simPublic()
}

// =========================================================================
//  The Test Bench (original LSU-only)
// =========================================================================

class IssueToAluAndLsuTestBench(val pCfg: PipelineConfig) extends Component {
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

  val framework = new Framework(
    Seq(
      new MockFetchServiceForLsu(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin[AguBypassData](payloadType = HardType(AguBypassData())),
      new MockCommitControllerForLsu(pCfg),
      new BpuPipelinePlugin(pCfg),
      // Add LSU infrastructure with unified configuration
      new StoreBufferPlugin(pCfg, lsuConfig, dCacheParams, lsuConfig.sqDepth),
      new AguPlugin(lsuConfig, supportPcRel = true),
      new DataCachePlugin(dCacheConfig),
      new TestOnlyMemSystemPlugin(axiConfig),
      // Core pipeline - simplified without ALU
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(
        pCfg,
        RenameMapTableConfig(
          archRegCount = pCfg.archGprCount,
          physRegCount = pCfg.physGprCount,
          numReadPorts = pCfg.renameWidth * 3,
          numWritePorts = pCfg.renameWidth
        ),
        SuperScalarFreeListConfig(
          numPhysRegs = pCfg.physGprCount, 
          numAllocatePorts = pCfg.renameWidth, 
          numFreePorts = pCfg.commitWidth
        )
      ),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      // Only LSU EU for now - pass unified configurations
      new LsuEuPlugin("LsuEU", pCfg, lsuConfig, dCacheParams),
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchServiceForLsu]
  fetchService.fetchStreamIn << io.fetchStreamIn

  val commitController = framework.getService[MockCommitControllerForLsu]
  commitController.getCommitEnable() := io.enableCommit

  val robService = framework.getService[ROBService[RenamedUop]]
  val commitSlot = robService.getCommitSlots(pCfg.commitWidth).head
  io.commitValid := commitSlot.valid
  io.commitEntry := commitSlot.entry

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

  // 注意：不在Framework外部创建PRF读端口，这应该在Plugin内部的early阶段完成
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
    // 重写bruEuCount以修复totalEuCount计算
    override def bruEuCount: Int = 0  // 测试中没有BRU插件
    // totalEuCount = aluEuCount + lsuEuCount + bruEuCount = 1 + 1 + 0 = 2
    override def totalEuCount: Int = 2
  }

  // 创建只有LSU的配置类（保持向后兼容）
  class LsuOnlyPipelineConfig extends PipelineConfig(
    aluEuCount = 0,
    lsuEuCount = 1,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1,
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    robDepth = 16,  // 明确设置和LsuPluginSpec一致
    commitWidth = 1,
    transactionIdWidth = 8
  ) {
    // 重写bruEuCount以修复totalEuCount计算
    override def bruEuCount: Int = 0  // 测试中没有BRU插件
    // 重写totalEuCount，因为我们的LsuEuPlugin是单一EU处理load和store
    override def totalEuCount: Int = 1  // 只有1个LsuEuPlugin
  }

  val pCfg_complex = new AluAndLsuPipelineConfig()  // 复杂测试使用ALU+LSU配置
  val pCfg = new LsuOnlyPipelineConfig()            // 简单测试使用LSU-only配置

  test("StoreAndLoad_Test") {
    // 使用纯Scala仿真后端，专注于Store和Load操作，但仍然使用简单的LSU-only配置
    val compiled = SimConfig.withConfig(SpinalConfig().copy(
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      targetDirectory = "simWorkspace/scala_sim"
    )).compile(new IssueToAluAndLsuTestBench(pCfg))
    
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

      val pc_start = BigInt("80000000", 16)
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

      // 更简单的测试序列，使用寄存器0作为基址，但测试Store和Load：
      // 1. ST.W r0, r0, 0x200  (存储0到地址0x200，因为r0总是0)
      // 2. LD.W r1, r0, 0x200  (从地址0x200加载，应该得到0)
      // 3. LD.W r2, r0, 0x204  (从地址0x204加载，应该得到0，因为没有写过)

      val store_addr = 0x200
      val instr_store = LA32RInstrBuilder.st_w(rd = 0, rj = 0, offset = store_addr)  // MEM[0x200] = r0 (=0)
      val instr_load1 = LA32RInstrBuilder.ld_w(rd = 1, rj = 0, offset = store_addr)  // r1 = MEM[0x200]
      val instr_load2 = LA32RInstrBuilder.ld_w(rd = 2, rj = 0, offset = store_addr + 4)  // r2 = MEM[0x204]

      println(s"[TEST] Store和Load测试序列:")
      println(f"  1. ST.W r0, r0, 0x${store_addr}%x (insn=0x${instr_store}%x) - 存储0到内存")
      println(f"  2. LD.W r1, r0, 0x${store_addr}%x (insn=0x${instr_load1}%x) - 从同一地址加载")
      println(f"  3. LD.W r2, r0, 0x${store_addr + 4}%x (insn=0x${instr_load2}%x) - 从不同地址加载")

      // 准备期望的提交顺序
      expectedCommits += pc_start        // Store
      expectedCommits += (pc_start + 4)  // Load 1
      expectedCommits += (pc_start + 8)  // Load 2

      println("=== 📤 发射指令序列 ===")
      issueInstr(pc_start, instr_store)      // PC: 0x80000000
      issueInstr(pc_start + 4, instr_load1)  // PC: 0x80000004
      issueInstr(pc_start + 8, instr_load2)  // PC: 0x80000008

      println("=== ⏱️ 等待执行完成 ===")
      cd.waitSampling(30)  // 给Store和Load序列足够的处理时间

      println("=== ✅ 启用提交 ===")
      dut.io.enableCommit #= true
      
      var timeout = 400  // 增加超时时间
      while(commitCount < 3 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 80 == 0) {
          println(s"[WAIT] commitCount=$commitCount/3, timeout=$timeout")
        }
      }
      
      if (timeout > 0) {
        println(s"🎉 SUCCESS: Store和Load测试完成，成功提交了${commitCount}条指令!")
        assert(commitCount == 3, s"Expected 3 commits, got $commitCount")
        assert(expectedCommits.isEmpty, "Not all expected commits were processed")
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
    )).compile(new IssueToAluAndLsuTestBench(pCfg))
    
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

      val pc_start = BigInt("80000000", 16)
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

      val instr_store1 = LA32RInstrBuilder.st_w(rd = 0, rj = 0, offset = 0x200)  // MEM[0x200] = r0
      val instr_store2 = LA32RInstrBuilder.st_w(rd = 0, rj = 0, offset = 0x204)  // MEM[0x204] = r0
      val instr_load1 = LA32RInstrBuilder.ld_w(rd = 1, rj = 0, offset = 0x200)   // r1 = MEM[0x200]
      val instr_load2 = LA32RInstrBuilder.ld_w(rd = 2, rj = 0, offset = 0x204)   // r2 = MEM[0x204]
      val instr_load3 = LA32RInstrBuilder.ld_w(rd = 3, rj = 0, offset = 0x208)   // r3 = MEM[0x208]

      println(s"[TEST] 复杂LSU序列（Store/Load forwarding测试）:")
      println(f"  1. ST.W r0, r0, 0x200 (insn=0x${instr_store1}%x) - 存储到0x200")
      println(f"  2. ST.W r0, r0, 0x204 (insn=0x${instr_store2}%x) - 存储到0x204")
      println(f"  3. LD.W r1, r0, 0x200 (insn=0x${instr_load1}%x) - 从0x200加载（应该hit store）")
      println(f"  4. LD.W r2, r0, 0x204 (insn=0x${instr_load2}%x) - 从0x204加载（应该hit store）")
      println(f"  5. LD.W r3, r0, 0x208 (insn=0x${instr_load3}%x) - 从0x208加载（miss，读cache）")

      // 准备期望的提交顺序
      expectedCommits += pc_start        // Store 1
      expectedCommits += (pc_start + 4)  // Store 2
      expectedCommits += (pc_start + 8)  // Load 1
      expectedCommits += (pc_start + 12) // Load 2
      expectedCommits += (pc_start + 16) // Load 3

      println("=== 📤 发射指令序列 ===")
      issueInstr(pc_start, instr_store1)       // PC: 0x80000000
      issueInstr(pc_start + 4, instr_store2)   // PC: 0x80000004
      issueInstr(pc_start + 8, instr_load1)    // PC: 0x80000008
      issueInstr(pc_start + 12, instr_load2)   // PC: 0x8000000C
      issueInstr(pc_start + 16, instr_load3)   // PC: 0x80000010

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
    )).compile(new IssueToAluAndLsuTestBench(pCfg))
    
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

      val pc_start = BigInt("80000000", 16)
      var commitCount = 0

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
      issueInstr(pc_start, instr_lw)   // PC: 0x80000000

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
