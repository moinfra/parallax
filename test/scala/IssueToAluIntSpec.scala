// filename: test/scala/IssueToAluIntSpec.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import parallax.common._
import parallax.components.rename._
import parallax.components.rob._
import parallax.execute.{AluIntEuPlugin, WakeupPlugin, BypassPlugin}
import parallax.fetch._
import parallax.issue._
import parallax.utilities._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._

import scala.collection.mutable
import scala.util.Random

// =========================================================================
//  Mock Services & Test Bench Helpers
// =========================================================================

class MockFetchService(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def newRedirectPort(priority: Int): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
}

class MockCommitController(pCfg: PipelineConfig) extends Plugin {
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

    // Handle ROB flush signals - set to idle for ALU-only test
    val robFlushPort = setup.robService.getFlushPort()
    robFlushPort.setIdle()

    val freePorts = setup.flControl.getFreePorts()
    val commitSlots = setup.robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = setup.robService.getCommitAcks(pCfg.commitWidth)

    // 修复：使用脉冲信号而不是持续信号
    // 我们需要确保commit ack只在指令真正可以提交且enableCommit为true时触发一次
    val commitPending = Vec(Reg(Bool()) init(False), pCfg.commitWidth)
    
    // 简化的commit controller实现
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

class IssueToAluIntTestBench(val pCfg: PipelineConfig) extends Component {
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
  }

  val framework = new Framework(
    Seq(
      new MockFetchService(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin(pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin(HardType(BypassMessage(pCfg))),
      new MockCommitController(pCfg),
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
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchService]
  fetchService.fetchStreamIn << io.fetchStreamIn

  val commitController = framework.getService[MockCommitController]
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

class IssueToAluIntSpec extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(
    aluEuCount = 1,
    lsuEuCount = 0,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1  // Set to 1 to match issue pipeline width
  )

  test("AluInt_ADD_Test") {
    val compiled = SimConfig.withFstWave.compile(new IssueToAluIntTestBench(pCfg))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(20000)

      def issueInstr(pc: BigInt, insn: BigInt): Unit = {
        println(s"DEBUG: [ISSUE] Starting to issue instruction at PC=0x${pc.toString(16)}, insn=0x${insn.toString(16)}")
        dut.io.fetchStreamIn.valid #= true
        dut.io.fetchStreamIn.payload.pc #= pc
        dut.io.fetchStreamIn.payload.instruction #= insn
        dut.io.fetchStreamIn.payload.predecode.setDefaultForSim()
        dut.io.fetchStreamIn.payload.bpuPrediction.valid #= false
        cd.waitSamplingWhere(dut.io.fetchStreamIn.ready.toBoolean)
        println(s"DEBUG: [ISSUE] Instruction accepted at PC=0x${pc.toString(16)}")
        dut.io.fetchStreamIn.valid #= false
        cd.waitSampling(2) // Wait a bit to let the instruction propagate
        println(s"DEBUG: [ISSUE] Finished issuing instruction at PC=0x${pc.toString(16)}")
      }

      val (r_dest, r_src1, r_src2) = (3, 1, 2)
      val (v_src1, v_src2) = (100, 200)
      val expectedResult = v_src1 + v_src2
      val pc_start = BigInt("80000000", 16)

      println(s"DEBUG: Expecting PC values: 0x${pc_start.toString(16)}, 0x${(pc_start + 4).toString(16)}, 0x${(pc_start + 8).toString(16)}")
      
      // Manual tracking instead of scoreboard
      val expectedCommits = scala.collection.mutable.Queue[BigInt]()
      expectedCommits += pc_start
      expectedCommits += (pc_start + 4)
      expectedCommits += (pc_start + 8)
      
      var commitCount = 0
      
      // Polling process for commits with detailed debugging
      val commitMonitor = fork {
        while(true) {
          cd.waitSampling()
          
          // 只有在enableCommit=true时才监控commit
          if (dut.io.enableCommit.toBoolean && dut.io.commitValid.toBoolean) {
            val commitPC = dut.io.commitEntry.payload.uop.decoded.pc.toBigInt
            val commitRobPtr = dut.io.commitEntry.payload.uop.robPtr.toBigInt
            println(s"DEBUG: [COMMIT] PC=0x${commitPC.toString(16)}, RobPtr=${commitRobPtr}")
            
            if (expectedCommits.nonEmpty) {
              val expectedPC = expectedCommits.dequeue()
              println(s"DEBUG: [COMMIT] Expected PC=0x${expectedPC.toString(16)}, Got PC=0x${commitPC.toString(16)}")
              assert(commitPC == expectedPC, s"PC mismatch: expected 0x${expectedPC.toString(16)}, got 0x${commitPC.toString(16)}")
              commitCount += 1
              
              // 假设检验：确认我们发送了commit ack
              println(s"DEBUG: [COMMIT] Sending commit ack for RobPtr=${commitRobPtr}")
            } else {
              println(s"DEBUG: [COMMIT] Unexpected commit with PC=0x${commitPC.toString(16)}")
            }
          }
        }
      }

      dut.io.enableCommit #= false
      cd.waitSampling(5)

      val instr_addi1 = LA32RInstrBuilder.addi_w(rd = r_src1, rj = 0, imm = v_src1)
      val instr_addi2 = LA32RInstrBuilder.addi_w(rd = r_src2, rj = 0, imm = v_src2)
      val instr_add = LA32RInstrBuilder.add_w(rd = r_dest, rj = r_src1, rk = r_src2)

      println(s"DEBUG: Issuing ADDI instruction at PC 0x${pc_start.toString(16)}")
      issueInstr(pc_start, instr_addi1)
      
      println(s"DEBUG: Issuing ADDI instruction at PC 0x${(pc_start + 4).toString(16)}")
      issueInstr(pc_start + 4, instr_addi2)

      println(s"DEBUG: Issuing ADD instruction at PC 0x${(pc_start + 8).toString(16)}")
      issueInstr(pc_start + 8, instr_add)

      println("DEBUG: All instructions issued. Waiting for execution to complete...")
      cd.waitSampling(10)

      println("Enabling commit and waiting for all instructions to commit...")
      dut.io.enableCommit #= true
      
      var timeout = 200  // 增加超时时间用于调试
      while(commitCount < 3 && timeout > 0) {
        cd.waitSampling()
        timeout -= 1
        
        // 每10个周期打印一次调试信息
        if (timeout % 10 == 0) {
          println(s"DEBUG: [TIMEOUT] Waiting for commits, current count: $commitCount/3, timeout remaining: $timeout")
        }
      }
      assert(timeout > 0, "Timeout waiting for all instructions to commit")
      assert(commitCount == 3, s"Expected 3 commits, got $commitCount")
      assert(expectedCommits.isEmpty, "Not all expected commits were processed")

      dut.io.enableCommit #= false
      println("All instructions committed successfully.")

      cd.waitSampling(5)

      dut.prfReadPorts(r_dest).valid #= true
      dut.prfReadPorts(r_dest).address #= r_dest
      cd.waitSampling()

      val result = dut.prfReadPorts(r_dest).rsp.toBigInt
      assert(result == expectedResult, s"PRF final value check failed: Result was ${result}, expected ${expectedResult}")
      println(s"SUCCESS: r${r_dest} contains ${result} as expected.")

      cd.waitSampling(10)
    }
  }

  thatsAll()
} 
