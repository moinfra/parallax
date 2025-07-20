// filename: test/scala/SimpleBruTest.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import parallax.common._
import parallax.components.rename._
import parallax.components.rob._
import parallax.execute.{BranchEuPlugin, WakeupPlugin, BypassPlugin}
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu.BpuPipelinePlugin
import parallax.utilities._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._

import scala.collection.mutable
import scala.util.Random

// 直接复制ALU测试的MockFetchService和MockCommitController
class MockFetchServiceBru(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def newHardRedirectPort(priority: Int): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
  override def newFetchDisablePort(): Bool = Bool()
}

class MockCommitControllerBru(pCfg: PipelineConfig) extends Plugin with CommitService {
  private val enableCommit = Bool()
  def getCommitEnable(): Bool = enableCommit

  override def setCommitEnable(enable: Bool): Unit = {
    enableCommit := enable
  }

  // override def isIdle(): Bool = False

  val setup = create early new Area {
    val ratControl = getService[RatControlService]
    val flControl  = getService[FreeListControlService]
    val robService = getService[ROBService[RenamedUop]]
  }

  val logic = create late new Area {
    setup.ratControl.newCheckpointSavePort().setIdle()
    setup.ratControl.newCheckpointRestorePort().setIdle()
    val robFlushPort = setup.robService.newRobFlushPort()
    robFlushPort.setIdle()

    val freePorts = setup.flControl.getFreePorts()
    val commitSlots = setup.robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = setup.robService.getCommitAcks(pCfg.commitWidth)

    for (i <- 0 until pCfg.commitWidth) {
      val canCommit = commitSlots(i).canCommit
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

class SimpleBruTestBench(val pCfg: PipelineConfig) extends Component {
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

  val io = new Bundle {
    val fetchStreamIn = slave(Stream(FetchedInstr(pCfg)))
    val enableCommit = in Bool ()
    val commitValid = out Bool()
    val commitEntry = out(ROBFullEntry(robConfig))
  }

  val framework = new Framework(
    Seq(
      new MockFetchServiceBru(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin(pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin(HardType(BypassMessage(pCfg))),
      new MockCommitControllerBru(pCfg),
      new BpuPipelinePlugin(pCfg),
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
new RenamePlugin(pCfg, renameMapConfig, flConfig),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      new BranchEuPlugin("BranchEU", pCfg),  // 重新添加BRU
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  // 简化连线逻辑，暂时不连接任何东西
  val fetchService = framework.getService[MockFetchServiceBru]
  val commitController = framework.getService[MockCommitControllerBru]
  val robService = framework.getService[ROBService[RenamedUop]]
  
  // 连接输入
  fetchService.fetchStreamIn << io.fetchStreamIn
  commitController.setCommitEnable(io.enableCommit)
  
  // 连接到issue pipeline
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

  // 连接提交逻辑
  val commitSlot = robService.getCommitSlots(pCfg.commitWidth).head
  io.commitValid := commitSlot.canCommit
  io.commitEntry := commitSlot.entry
}

class SimpleBruTest extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(
    aluEuCount = 0,  // 不要ALU
    lsuEuCount = 0,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1
  )

  test("SimpleBru_Test") {
    val compiled = simConfig.compile(new SimpleBruTestBench(pCfg))
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      SimTimeout(10000)

      dut.io.enableCommit #= false
      cd.waitSampling(5)
      
      println("SUCCESS: BRU basic framework test passed")
      cd.waitSampling(10)
    }
  }

  thatsAll()
}
