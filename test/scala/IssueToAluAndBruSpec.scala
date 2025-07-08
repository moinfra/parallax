// filename: test/scala/IssueToAluAndBruSpec.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import parallax.common._
import parallax.components.rename._
import parallax.components.rob._
import parallax.execute.{AluIntEuPlugin, BranchEuPlugin, WakeupPlugin, BypassPlugin}
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu.BpuPipelinePlugin
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.utilities._

import scala.collection.mutable
import scala.util.Random

// =========================================================================
//  Mock Services & Test Bench Helpers
// =========================================================================

class MockFetchServiceForBru(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def newRedirectPort(priority: Int): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
}

class MockCommitControllerForBru(pCfg: PipelineConfig) extends Plugin {
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

    // Handle ROB flush signals - important for branch misprediction handling
    val robFlushPort = setup.robService.getFlushPort()
    robFlushPort.setIdle()

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
//  The Test Bench
// =========================================================================

class IssueToAluAndBruTestBench(val pCfg: PipelineConfig) extends Component {
  val UOP_HT = HardType(RenamedUop(pCfg))

  val robConfig = ROBConfig[RenamedUop](
    robDepth = pCfg.robDepth,
    pcWidth = pCfg.pcWidth,
    commitWidth = pCfg.commitWidth,
    allocateWidth = pCfg.renameWidth,
    numWritebackPorts = pCfg.totalEuCount,
    uopType = UOP_HT,
    defaultUop = () => RenamedUop(pCfg).setDefault(),
    exceptionCodeWidth = pCfg.exceptionCodeWidth
  )

  val io = new Bundle {
    val fetchStreamIn = slave(Stream(FetchedInstr(pCfg)))
    val enableCommit = in Bool ()
    // Expose commit port for monitoring
    val commitValid = out Bool()
    val commitEntry = out(ROBFullEntry(robConfig))
    // Expose branch redirect port for monitoring
    val branchRedirectValid = out Bool()
    val branchRedirectPC = out UInt(pCfg.pcWidth)
  }

  val framework = new Framework(
    Seq(
      new MockFetchServiceForBru(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin(pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin(HardType(BypassMessage(pCfg))),
      new MockCommitControllerForBru(pCfg),
      new BpuPipelinePlugin(pCfg), // Add BPU service
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
      new AluIntEuPlugin("AluIntEU", pCfg),
      new BranchEuPlugin("BranchEU", pCfg),
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchServiceForBru]
  fetchService.fetchStreamIn << io.fetchStreamIn

  val commitController = framework.getService[MockCommitControllerForBru]
  commitController.getCommitEnable() := io.enableCommit

  val robService = framework.getService[ROBService[RenamedUop]]
  val commitSlot = robService.getCommitSlots(pCfg.commitWidth).head
  io.commitValid := commitSlot.valid
  io.commitEntry := commitSlot.entry

  // Connect branch redirect port
  val branchRedirectPort = fetchService.newRedirectPort(0)
  io.branchRedirectValid := branchRedirectPort.valid
  io.branchRedirectPC := branchRedirectPort.payload

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
//  The Test Suite
// =========================================================================

class IssueToAluAndBruSpec extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(
    aluEuCount = 1,
    lsuEuCount = 0,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1
  )

  test("SimpleBranch_Test") {
    val compiled = SimConfig.withFstWave.compile(new IssueToAluAndBruTestBench(pCfg))
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

      // Simple commit monitoring
      val commitMonitor = fork {
        while(true) {
          cd.waitSampling()
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            println(s"[COMMIT] PC=0x${commitPC.toString(16)}")
            commitCount += 1
          }
        }
      }

      dut.io.enableCommit #= false
      cd.waitSampling(5)

      // 只测试一个分支指令：设置r1=100, r2=100, 然后BEQ r1,r2,+4 (应该跳转)
      val instr_addi1 = LA32RInstrBuilder.addi_w(rd = 1, rj = 0, imm = 100)  // r1 = 100
      val instr_addi2 = LA32RInstrBuilder.addi_w(rd = 2, rj = 0, imm = 100)  // r2 = 100
      val instr_beq = LA32RInstrBuilder.beq(rj = 1, rd = 2, offset = 4)      // BEQ r1, r2, +4

      println("=== 发射指令序列 ===")
      issueInstr(pc_start, instr_addi1)     // PC: 0x80000000
      issueInstr(pc_start + 4, instr_addi2) // PC: 0x80000004  
      issueInstr(pc_start + 8, instr_beq)   // PC: 0x80000008

      println("=== 等待执行完成 ===")
      cd.waitSampling(10)

      println("=== 启用提交 ===")
      dut.io.enableCommit #= true
      
      var timeout = 150
      while(commitCount < 3 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        if (timeout % 30 == 0) {
          println(s"[WAIT] commitCount=$commitCount/3, timeout=$timeout")
        }
      }
      
      assert(timeout > 0, "Timeout waiting for commits")
      assert(commitCount == 3, s"Expected 3 commits, got $commitCount")
      
      println(s"SUCCESS: 简单分支测试完成, 提交了${commitCount}条指令")
      cd.waitSampling(5)
    }
  }

  thatsAll()
}
