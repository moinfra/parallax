// filename: src/test/scala/parallax/issue/SimpleIssuePipelineSpec.scala
// testOnly parallax.test.issue.SimpleIssuePipelineSpec
package parallax.test.issue

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import parallax.common._
import parallax.fetch._
import parallax.issue._
import parallax.components.rename._
import parallax.components.rob._
import parallax.utilities._
import test.scala.LA32RInstrBuilder
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

case class DecodedUopCapture(
    pc: BigInt,
    isValid: Boolean,
    uopCode: BaseUopCode.E,
    exeUnit: ExeUnitType.E,
    archDestIdx: Int,
    writeArchDestEn: Boolean,
    archSrc1Idx: Int,
    useArchSrc1: Boolean,
    archSrc2Idx: Int,
    useArchSrc2: Boolean,
    imm: BigInt
)
object DecodedUopCapture {
  def from(uop: DecodedUop): DecodedUopCapture = {
    import spinal.core.sim._
    DecodedUopCapture(
      pc = uop.pc.toBigInt,
      isValid = uop.isValid.toBoolean,
      uopCode = uop.uopCode.toEnum,
      exeUnit = uop.exeUnit.toEnum,
      archDestIdx = uop.archDest.idx.toInt,
      writeArchDestEn = uop.writeArchDestEn.toBoolean,
      archSrc1Idx = uop.archSrc1.idx.toInt,
      useArchSrc1 = uop.useArchSrc1.toBoolean,
      archSrc2Idx = uop.archSrc2.idx.toInt,
      useArchSrc2 = uop.useArchSrc2.toBoolean,
      imm = uop.imm.toBigInt // Assuming imm_sext_16_to_dataWidth.asBits.resized is the value
    )
  }
}

case class RenameInfoCapture(
    physSrc1Idx: Int,
    physSrc2Idx: Int,
    physDestIdx: Int,
    oldPhysDestIdx: Int,
    allocatesPhysDest: Boolean
)
object RenameInfoCapture {
  def from(renameInfo: RenameInfo): RenameInfoCapture = {
    import spinal.core.sim._
    RenameInfoCapture(
      physSrc1Idx = renameInfo.physSrc1.idx.toInt,
      physSrc2Idx = renameInfo.physSrc2.idx.toInt,
      physDestIdx = renameInfo.physDest.idx.toInt,
      oldPhysDestIdx = renameInfo.oldPhysDest.idx.toInt,
      allocatesPhysDest = renameInfo.allocatesPhysDest.toBoolean
    )
  }
}

case class RenamedUopCapture(
    decoded: DecodedUopCapture,
    rename: RenameInfoCapture,
    robPtr: Int // robPtr is a direct value, not a sub-bundle
)
object RenamedUopCapture {
  def from(uop: RenamedUop): RenamedUopCapture = {
    import spinal.core.sim._
    RenamedUopCapture(
      decoded = DecodedUopCapture.from(uop.decoded),
      rename = RenameInfoCapture.from(uop.rename),
      robPtr = uop.robPtr.toInt
    )
  }
}

// =========================================================================
//  Mock Fetch Service & Test Bench
// =========================================================================

class MockFetchService(pCfg: PipelineConfig) extends Plugin with SimpleFetchPipelineService {
  val fetchStreamIn = Stream(FetchedInstr(pCfg))
  override def fetchOutput(): Stream[FetchedInstr] = fetchStreamIn
  override def getRedirectPort(): Flow[UInt] = Flow(UInt(pCfg.pcWidth))
}

class MockControllerPlugin(pCfg: PipelineConfig) extends Plugin {
  val setup = create early new Area {
    val ratControl = getService[RatControlService]
    val flControl = getService[FreeListControlService]
    val robService = getService[ROBService[RenamedUop]] // Get ROB to simulate commit
  }

  val logic = create late new Area {
    // Drive RAT/FL control ports to idle state
    setup.ratControl.newCheckpointSavePort().setIdle()
    setup.ratControl.newCheckpointRestorePort().setIdle()
    setup.flControl.getFreePorts().foreach(_.setIdle())
    setup.flControl.newRestorePort().setIdle()

    // Simulate commit to free up ROB slots and physical registers
    val commitSlots = setup.robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = setup.robService.getCommitAcks(pCfg.commitWidth)
    
    for(i <- 0 until pCfg.commitWidth) {
      commitAcks(i) := commitSlots(i).valid
    }
  }
}

// filename: src/test/scala/parallax/issue/SimpleIssuePipelineSpec.scala
// ... (imports and capture classes remain the same) ...

class SimpleIssuePipelineTestBench(
    val pCfg: PipelineConfig,
    val ratCfg: RenameMapTableConfig,
    val flCfg: SuperScalarFreeListConfig
) extends Component {

  val io = new Bundle {
    val fetchStreamIn = slave(Stream(FetchedInstr(pCfg)))
    val aluIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val mulIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val divIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val lsuIqOut = master(Stream(IqDispatchCmd(pCfg)))
    val bruIqOut = master(Stream(IqDispatchCmd(pCfg)))
  }

  val UOP_HT = HardType(RenamedUop(pCfg))

  val framework = new Framework(
    Seq(
      new MockFetchService(pCfg),
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.xlen bits),
      new ROBPlugin(pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new IssuePipeline(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(pCfg, ratCfg, flCfg),
      new IssueQueuePlugin(pCfg),
      new DispatchPlugin(pCfg),
      new MockControllerPlugin(pCfg)
    )
  )

  val fetchService = framework.getService[MockFetchService]
  val iqService = framework.getService[IssueQueueService]
  val issuePipeline = framework.getService[IssuePipeline]
  val robService = framework.getService[ROBService[RenamedUop]]

  val connections = new Area {
    io.fetchStreamIn <> fetchService.fetchStreamIn
    
    val aluIqPort = iqService.newIssueQueue(Seq(BaseUopCode.ALU, BaseUopCode.SHIFT))
    val mulIqPort = iqService.newIssueQueue(Seq(BaseUopCode.MUL))
    val divIqPort = iqService.newIssueQueue(Seq(BaseUopCode.DIV))
    val lsuIqPort = iqService.newIssueQueue(Seq(BaseUopCode.LOAD, BaseUopCode.STORE))
    val bruIqPort = iqService.newIssueQueue(Seq(BaseUopCode.BRANCH, BaseUopCode.JUMP_REG))

    io.aluIqOut << aluIqPort
    io.mulIqOut << mulIqPort
    io.divIqOut << divIqPort
    io.lsuIqOut << lsuIqPort
    io.bruIqOut << bruIqPort
    
    // -- MODIFICATION START: Add logging for internal ready signals --
    when(issuePipeline.entryStage.isFiring) { // Log only when pipeline is active to reduce spam
        report(L"TestBench IO Readies -> ALU: ${io.aluIqOut.ready}, MUL: ${io.mulIqOut.ready}, LSU: ${io.lsuIqOut.ready}, BRU: ${io.bruIqOut.ready}")
        report(L"IQService Ports Readies -> ALU: ${aluIqPort.ready}, MUL: ${mulIqPort.ready}, LSU: ${lsuIqPort.ready}, BRU: ${bruIqPort.ready}")
    }
    // -- MODIFICATION END --

    val fetchOutStream = fetchService.fetchOutput()
    val issueEntryStage = issuePipeline.entryStage
    val issueSignals = issuePipeline.signals

    issueEntryStage.valid := fetchOutStream.valid
    fetchOutStream.ready := issueEntryStage.isReady

    val fetched = fetchOutStream.payload
    val instructionVec = Vec(Bits(pCfg.dataWidth), 1)
    instructionVec(0) := fetched.instruction
    
    issueEntryStage(issueSignals.GROUP_PC_IN)          := fetched.pc
    issueEntryStage(issueSignals.RAW_INSTRUCTIONS_IN)  := instructionVec
    issueEntryStage(issueSignals.VALID_MASK)           := B"1"
    issueEntryStage(issueSignals.IS_FAULT_IN)          := False
    issueEntryStage(issueSignals.FLUSH_PIPELINE)       := False
    issueEntryStage(issueSignals.FLUSH_TARGET_PC)      := 0

    robService.getFlushPort().setIdle()

    for(i <- 0 until pCfg.totalEuCount) {
      val wbPort = robService.newWritebackPort(s"TestBench_Dummy_EU_$i")
      wbPort.fire := False
      wbPort.robPtr.assignDontCare()
      wbPort.exceptionOccurred.assignDontCare()
      wbPort.exceptionCodeIn.assignDontCare()
    }
  }
}

// ... (The rest of the file, SimpleIssuePipelineSpec and its test cases, remains exactly the same as before) ...
class SimpleIssuePipelineSpec extends CustomSpinalSimFunSuite {

    val pCfg = PipelineConfig(xlen = 32, fetchWidth = 1, renameWidth = 1, archGprCount = 32, physGprCount = 64, robDepth = 32, commitWidth = 1)
    val ratCfg = RenameMapTableConfig(archRegCount = pCfg.archGprCount, physRegCount = pCfg.physGprCount, numReadPorts = pCfg.renameWidth * 3, numWritePorts = pCfg.renameWidth)
    val flCfg = SuperScalarFreeListConfig(numPhysRegs = pCfg.physGprCount, resetToFull = true, numInitialArchMappings = pCfg.archGprCount, numAllocatePorts = pCfg.renameWidth, numFreePorts = pCfg.commitWidth)

    def driveFetchedInstr(queue: mutable.Queue[FetchedInstr => Unit], pc: BigInt, inst: BigInt): Unit = {
        queue.enqueue { p =>
            p.pc #= pc
            p.instruction #= inst
            p.predecode.setDefaultForSim()
            p.bpuPrediction.valid #= false
        }
    }

    test("Issue Pipeline should correctly dispatch a full mix of instructions") {
        val compiled = SimConfig.withFstWave.compile(
            new SimpleIssuePipelineTestBench(pCfg, ratCfg, flCfg)
        )
        
        compiled.doSim { dut =>
            dut.clockDomain.forkStimulus(10)
            
            val (feederDriver, feederQueue) = StreamDriver.queue(dut.io.fetchStreamIn, dut.clockDomain)

            val aluReceived = mutable.Queue[RenamedUopCapture]()
            val mulReceived = mutable.Queue[RenamedUopCapture]()
            val lsuReceived = mutable.Queue[RenamedUopCapture]()
            val bruReceived = mutable.Queue[RenamedUopCapture]()

            StreamMonitor(dut.io.aluIqOut, dut.clockDomain) { p => aluReceived.enqueue(RenamedUopCapture.from(p.uop)) }
            StreamMonitor(dut.io.mulIqOut, dut.clockDomain) { p => mulReceived.enqueue(RenamedUopCapture.from(p.uop)) }
            StreamMonitor(dut.io.lsuIqOut, dut.clockDomain) { p => lsuReceived.enqueue(RenamedUopCapture.from(p.uop)) }
            StreamMonitor(dut.io.bruIqOut, dut.clockDomain) { p => bruReceived.enqueue(RenamedUopCapture.from(p.uop)) }

            dut.io.aluIqOut.ready #= true
            dut.io.mulIqOut.ready #= true
            dut.io.lsuIqOut.ready #= true
            dut.io.bruIqOut.ready #= true

            driveFetchedInstr(feederQueue, 0x1000, LA32RInstrBuilder.add_w(1, 2, 3))
            driveFetchedInstr(feederQueue, 0x1004, LA32RInstrBuilder.mul_w(4, 5, 6))
            driveFetchedInstr(feederQueue, 0x1008, LA32RInstrBuilder.ld_w(7, 8, 12))
            driveFetchedInstr(feederQueue, 0x100C, LA32RInstrBuilder.beq(1, 4, 64))
            driveFetchedInstr(feederQueue, 0x1010, LA32RInstrBuilder.slli_w(9, 7, 2))
            
            val gg = dut.clockDomain.waitSamplingWhere(timeout = 40)(
                aluReceived.size == 2 && mulReceived.size == 1 && lsuReceived.size == 1 && bruReceived.size == 1
            )
            
            if(gg) {
              fail("Timeout waiting for instructions to be dispatched")
            }
            
            assert(aluReceived.size == 2, s"ALU IQ count mismatch")
            assert(mulReceived.size == 1, s"MUL IQ count mismatch")
            assert(lsuReceived.size == 1, s"LSU IQ count mismatch")
            assert(bruReceived.size == 1, s"BRU IQ count mismatch")
            
            val aluUop1 = aluReceived.dequeue()
            val mulUop1 = mulReceived.dequeue()
            val lsuUop1 = lsuReceived.dequeue()
            val bruUop1 = bruReceived.dequeue()
            val aluUop2 = aluReceived.dequeue()

            assert(aluUop1.decoded.pc == 0x1000 && aluUop1.robPtr == 0)
            assert(mulUop1.decoded.pc == 0x1004 && mulUop1.robPtr == 1)
            assert(lsuUop1.decoded.pc == 0x1008 && lsuUop1.robPtr == 2)
            assert(bruUop1.decoded.pc == 0x100C && bruUop1.robPtr == 3)
            assert(aluUop2.decoded.pc == 0x1010 && aluUop2.robPtr == 4)

            assert(bruUop1.rename.physSrc1Idx == aluUop1.rename.physDestIdx, "BEQ dependency on r1 (from ADD) failed")
            assert(bruUop1.rename.physSrc2Idx == mulUop1.rename.physDestIdx, "BEQ dependency on r4 (from MUL) failed")

            assert(aluUop2.rename.physSrc1Idx == lsuUop1.rename.physDestIdx, "SLLI dependency on r7 (from LD) failed")

            println("Test 'Issue Pipeline dispatch with real ROB and PRF' PASSED!")
        }
    }
    thatsAll()
}
