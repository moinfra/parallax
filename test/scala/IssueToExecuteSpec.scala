// filename: test/scala/IssueToExecuteSpec.scala
// command: test test.scala.IssueToExecuteSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.issue._
import parallax.components.rob._
import parallax.execute._
import parallax.utilities._
import parallax.issue._

import scala.collection.mutable
import scala.util.Random

/**
 * Test setup plugin that connects all the components for the issue-to-execute flow
 */
class IssueToExecuteTestSetupPlugin(
    val pCfg: PipelineConfig,
    iqDispatchSetup: Flow[IqDispatchCmd] => Unit,
    robIoSetup: (ROBAllocateSlot[RenamedUop], Flow[ROBFlushPayload], ROBCommitSlot[RenamedUop], Bool) => Unit,
    prfIoSetup: (PrfReadPort, PrfWritePort) => Unit,
    wakeupSetup: Flow[WakeupPayload] => Unit
) extends Plugin {
  val setup = create early new Area {
    val issueQueueService = getService[IssueQueueService]
    val robService = getService[ROBService[RenamedUop]]
    val prfService = getService[PhysicalRegFileService]
    val wakeupService = getService[WakeupService]

    // Setup Issue Queue dispatch
    val iqDispatchFlow = Flow(IqDispatchCmd(pCfg))
    iqDispatchSetup(iqDispatchFlow)
    
    // Create issue queue streams and connect them
    val aluStream = issueQueueService.newIssueQueue(Seq(BaseUopCode.ALU))
    val branchStream = issueQueueService.newIssueQueue(Seq(BaseUopCode.BRANCH))
    
    // For simplicity, route all dispatches to ALU for now
    aluStream << iqDispatchFlow.toStream

    // Setup ROB ports
    val robAllocPort = robService.getAllocatePorts(1)(0)
    val robFlushPort = robService.getFlushPort()
    val robCommitSlot = robService.getCommitSlots(1)(0)
    val robCommitAck = robService.getCommitAcks(1)(0)
    robIoSetup(robAllocPort, robFlushPort, robCommitSlot, robCommitAck)
    
    // Setup PRF ports
    val prfReadPort = prfService.newReadPort()
    val prfWritePort = prfService.newWritePort()
    prfIoSetup(prfReadPort, prfWritePort)

    // Setup wakeup flow
    val wakeupFlow = wakeupService.getWakeupFlow()
    wakeupSetup(wakeupFlow)
  }
}

/**
 * Top-level DUT for the issue-to-execute integration testbench
 */
class IssueToExecuteTestBench(
    val pCfg: PipelineConfig
) extends Component {
  val io = IssueToExecuteTestBenchIo(pCfg)
  io.simPublic()

  val database = new DataBase

  val framework = new Framework(
    Seq(
      // Core services
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new BypassPlugin[BypassMessage](payloadType = HardType(BypassMessage(pCfg))),
      new WakeupPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      
      // Execution units
      new AluIntEuPlugin(euName = "testAluInt", pipelineConfig = pCfg),
      new BranchEuPlugin(euName = "testBranch", pipelineConfig = pCfg),
      
      // Linker plugin - this is the key component we're testing
      new LinkerPlugin(pCfg),
      
      // Test setup plugin
      new IssueToExecuteTestSetupPlugin(
        pCfg = pCfg,
        iqDispatchSetup = iqDispatchFlow => {
          // Connect the testbench's dispatch output to the issue queue service
          // This simulates the DispatchPlugin sending uops to IQs
          // Note: Service calls will be made inside the setup Area
          iqDispatchFlow.toStream
        },
        robIoSetup = (allocPort, flushPort, commitSlot, commitAck) => {
          allocPort.valid := io.robAllocate.valid
          allocPort.pcIn := io.robAllocate.pcIn
          allocPort.uopIn := io.robAllocate.uopIn
          io.robAllocate.robPtr := allocPort.robPtr
          io.robAllocate.ready := allocPort.ready

          flushPort <> io.robFlushIn

          io.committedOps.valid := commitSlot.valid
          io.committedOps.payload := commitSlot.entry
          commitAck := io.robCommitAck
        },
        prfIoSetup = (readPort, writePort) => {
          writePort <> io.tbPrfWrite
          readPort.address := io.tbPrfReadAddr
          readPort.valid := io.tbPrfReadValid
          io.tbPrfReadOut := readPort.rsp
        },
        wakeupSetup = wakeupFlow => {
          // Connect testbench wakeup to the service
          wakeupFlow <> io.tbWakeup
        }
      )
    )
  )

  // Get references to the execution units for monitoring
  val aluEu = framework.getServiceWhere[AluIntEuPlugin](_.euName == "testAluInt")
  val branchEu = framework.getServiceWhere[BranchEuPlugin](_.euName == "testBranch")
}

/**
 * IO bundle for the issue-to-execute testbench
 */
case class IssueToExecuteTestBenchIo(pCfg: PipelineConfig) extends Bundle {
  // --- Inputs to drive the DUT ---
  val iqDispatch = master(Flow(IqDispatchCmd(pCfg)))
  val robAllocate = master(
    ROBAllocateSlot(
      ROBConfig(
        robDepth = pCfg.robDepth,
        pcWidth = pCfg.pcWidth,
        commitWidth = pCfg.commitWidth,
        allocateWidth = pCfg.renameWidth,
        numWritebackPorts = 4, // Fixed number for test
        uopType = HardType(RenamedUop(pCfg)),
        defaultUop = () => RenamedUop(pCfg).setDefault(),
        exceptionCodeWidth = pCfg.exceptionCodeWidth
      )
    )
  )
  val robFlushIn = slave Flow (ROBFlushPayload(robPtrWidth = pCfg.robPtrWidth))
  val robCommitAck = in Bool()
  val tbWakeup = master(Flow(WakeupPayload(pCfg)))
  
  // --- Outputs to observe DUT state ---
  val committedOps = master(
    Flow(
      ROBFullEntry[RenamedUop](
        ROBConfig(
          robDepth = pCfg.robDepth,
          pcWidth = pCfg.pcWidth,
          commitWidth = pCfg.commitWidth,
          allocateWidth = pCfg.renameWidth,
          numWritebackPorts = 4, // Fixed number for test
          uopType = HardType(RenamedUop(pCfg)),
          defaultUop = () => RenamedUop(pCfg).setDefault(),
          exceptionCodeWidth = pCfg.exceptionCodeWidth
        )
      )
    )
  )

  // --- Test-only verification ports ---
  val tbPrfWrite = slave(PrfWritePort(pCfg.physGprIdxWidth, pCfg.dataWidth))
  val tbPrfReadAddr = in UInt(pCfg.physGprIdxWidth)
  val tbPrfReadValid = in Bool()
  val tbPrfReadOut = out Bits(pCfg.dataWidth)
}

/**
 * Test helper for the issue-to-execute integration test
 */
class IssueToExecuteTestHelper(dut: IssueToExecuteTestBench)(implicit cd: ClockDomain) {
  var pcCounter = 0x8000L
  var robPtrCounter = 0L

  def init(): Unit = {
    dut.io.iqDispatch.valid #= false
    dut.io.robAllocate.valid #= false
    dut.io.robCommitAck #= false
    dut.io.robFlushIn.valid #= false
    dut.io.tbPrfWrite.valid #= false
    dut.io.tbPrfReadValid #= false
    dut.io.tbWakeup.valid #= false
    cd.waitSampling()
  }
  
  case class TestOp(
    uopCode: BaseUopCode.E,
    src1Reg: Int,
    src1Data: BigInt,
    src2Reg: Int,
    src2Data: BigInt,
    destReg: Int,
    imm: BigInt = 0,
    var robPtr: BigInt = -1
  )

  def issue(op: TestOp): TestOp = {
    // 1. Allocate in ROB
    dut.io.robAllocate.valid #= true
    dut.io.robAllocate.pcIn #= pcCounter
    val uop = dut.io.robAllocate.uopIn
    uop.setDefaultForSim()
    
    // Set up the uop based on operation type
    uop.decoded.uopCode #= op.uopCode
    uop.decoded.exeUnit #= (op.uopCode match {
      case BaseUopCode.ALU => ExeUnitType.ALU_INT
      case BaseUopCode.BRANCH => ExeUnitType.BRU
      case _ => ExeUnitType.ALU_INT
    })
    
    // Set up source operands
    uop.decoded.useArchSrc1 #= true
    uop.decoded.archSrc1.idx #= op.src1Reg
    uop.decoded.archSrc1.rtype #= ArchRegType.GPR
    
    uop.decoded.useArchSrc2 #= true
    uop.decoded.archSrc2.idx #= op.src2Reg
    uop.decoded.archSrc2.rtype #= ArchRegType.GPR
    
    // Set up destination
    uop.decoded.writeArchDestEn #= true
    uop.decoded.archDest.idx #= op.destReg
    uop.decoded.archDest.rtype #= ArchRegType.GPR
    
    // Set up rename info
    uop.rename.physSrc1.idx #= op.src1Reg
    uop.rename.physSrc2.idx #= op.src2Reg
    uop.rename.physDest.idx #= op.destReg
    uop.rename.writesToPhysReg #= true
    uop.rename.allocatesPhysDest #= true
    
    cd.waitSamplingWhere(dut.io.robAllocate.ready.toBoolean)
    val robPtr = dut.io.robAllocate.robPtr.toBigInt
    dut.io.robAllocate.valid #= false
    val currentPc = pcCounter
    pcCounter += 4
    
    // 2. Send to Issue Queue via dispatch
    val dispatchPayload = dut.io.iqDispatch.payload
    dispatchPayload.uop := uop
    dispatchPayload.src1InitialReady #= true  // Assume sources are ready initially
    dispatchPayload.src2InitialReady #= true
    
    dut.io.iqDispatch.valid #= true
    cd.waitSampling()
    dut.io.iqDispatch.valid #= false
    
    ParallaxLogger.debug(s"[Helper] Issued robPtr=$robPtr (${op.uopCode}) to Issue Queue.")
    
    op.copy(robPtr = robPtr)
  }

  def waitForCommit(robPtr: BigInt): Unit = {
    val timedOut = cd.waitSamplingWhere(timeout = 200)(
      dut.io.committedOps.valid.toBoolean && dut.io.committedOps.payload.payload.uop.robPtr.toBigInt == robPtr
    )
    assert(!timedOut, s"Timeout waiting for robPtr=$robPtr to be committed.")
    dut.io.robCommitAck #= true
    cd.waitSampling()
    dut.io.robCommitAck #= false
  }

  def prfWrite(preg: Int, data: BigInt): Unit = {
    dut.io.tbPrfWrite.valid #= true
    dut.io.tbPrfWrite.address #= preg
    dut.io.tbPrfWrite.data #= data
    cd.waitSampling()
    dut.io.tbPrfWrite.valid #= false
    
    // Verify write
    cd.waitSampling(1)
    dut.io.tbPrfReadValid #= true
    dut.io.tbPrfReadAddr #= preg
    cd.waitSampling()
    val readbackData = dut.io.tbPrfReadOut.toBigInt
    dut.io.tbPrfReadValid #= false
    
    val success = readbackData == data
    ParallaxLogger.info(
      s"[Helper] prfWrite: reg=${preg}, data=0x${data.toString(16)}, " +
      s"readback=0x${readbackData.toString(16)} -> " + 
      (if(success) "SUCCESS" else "FAILURE")
    )
    assert(success, s"PRF write verification failed for pReg $preg!")
    
    cd.waitSampling(1)
  }

  def prfVerify(preg: Int, expectedData: BigInt): Unit = {
    dut.io.tbPrfReadValid #= true
    dut.io.tbPrfReadAddr #= preg
    cd.waitSampling()
    val readData = dut.io.tbPrfReadOut.toBigInt
    dut.io.tbPrfReadValid #= false
    assert(readData == expectedData, s"PRF verification failed at pReg $preg. Got 0x${readData.toString(16)}, Expected 0x${expectedData.toString(16)}")
  }

  def sendWakeup(physReg: Int): Unit = {
    dut.io.tbWakeup.valid #= true
    dut.io.tbWakeup.payload.physRegIdx #= physReg
    cd.waitSampling()
    dut.io.tbWakeup.valid #= false
  }
}

class IssueToExecuteSpec extends CustomSpinalSimFunSuite {
  
  def createPipelineConfig(): PipelineConfig = PipelineConfig(
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    bypassNetworkSources = 4,
    uopUniqueIdWidth = 8 bits,
    exceptionCodeWidth = 8 bits,
    fetchWidth = 2,
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 32
  )

  test("IssueToExecute_Integration - Basic ALU Operation") {
    val pCfg = createPipelineConfig()

    SimConfig.withWave
      .compile(new IssueToExecuteTestBench(pCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new IssueToExecuteTestHelper(dut)
        helper.init()
        
        // Test parameters
        val src1Reg = 5
        val src2Reg = 6
        val destReg = 10
        val src1Data = BigInt("12345678", 16)
        val src2Data = BigInt("87654321", 16)
        val expectedResult = src1Data + src2Data  // Simple addition

        println(s"[Test] Starting Basic ALU Operation Test")
        println(s"[Test] src1=0x${src1Data.toString(16)}, src2=0x${src2Data.toString(16)}, expected=0x${expectedResult.toString(16)}")

        // 1. Setup PRF with source data
        helper.prfWrite(src1Reg, src1Data)
        helper.prfWrite(src2Reg, src2Data)
        cd.waitSampling(5)

        // 2. Issue ALU operation
        val aluOp = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = src1Reg,
          src1Data = src1Data,
          src2Reg = src2Reg,
          src2Data = src2Data,
          destReg = destReg
        ))
        println(s"[Test] ALU instruction issued with robPtr=${aluOp.robPtr}")

        // 3. Wait for commit
        helper.waitForCommit(aluOp.robPtr)
        println(s"[Test] ALU instruction (robPtr=${aluOp.robPtr}) has been committed")

        // 4. Verify result in PRF
        helper.prfVerify(destReg, expectedResult)
        
        println("Test 'IssueToExecute_Integration - Basic ALU Operation' PASSED")
      }
  }

  test("IssueToExecute_Integration - Dependent Operations") {
    val pCfg = createPipelineConfig()

    SimConfig.withWave
      .compile(new IssueToExecuteTestBench(pCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new IssueToExecuteTestHelper(dut)
        helper.init()
        
        // Test parameters for a chain of dependent operations
        val reg1 = 1
        val reg2 = 2
        val reg3 = 3
        val reg4 = 4
        
        val initialValue = BigInt("1000", 16)
        val expectedResult = initialValue + 1 + 2 + 3  // Chain of additions

        println(s"[Test] Starting Dependent Operations Test")
        println(s"[Test] Initial value=0x${initialValue.toString(16)}, expected final=0x${expectedResult.toString(16)}")

        // 1. Setup initial value
        helper.prfWrite(reg1, initialValue)
        cd.waitSampling(5)

        // 2. Issue chain of dependent operations
        val op1 = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = reg1,
          src1Data = initialValue,
          src2Reg = 0,  // Use immediate-like approach
          src2Data = 1,
          destReg = reg2
        ))
        
        val op2 = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = reg2,
          src1Data = initialValue + 1,
          src2Reg = 0,
          src2Data = 2,
          destReg = reg3
        ))
        
        val op3 = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = reg3,
          src1Data = initialValue + 1 + 2,
          src2Reg = 0,
          src2Data = 3,
          destReg = reg4
        ))

        println(s"[Test] Issued 3 dependent operations: robPtrs=${op1.robPtr}, ${op2.robPtr}, ${op3.robPtr}")

        // 3. Wait for all operations to commit
        helper.waitForCommit(op1.robPtr)
        helper.waitForCommit(op2.robPtr)
        helper.waitForCommit(op3.robPtr)
        
        println(s"[Test] All operations have been committed")

        // 4. Verify final result
        helper.prfVerify(reg4, expectedResult)
        
        println("Test 'IssueToExecute_Integration - Dependent Operations' PASSED")
      }
  }

  test("IssueToExecute_Integration - Wakeup Mechanism") {
    val pCfg = createPipelineConfig()

    SimConfig.withWave
      .compile(new IssueToExecuteTestBench(pCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new IssueToExecuteTestHelper(dut)
        helper.init()
        
        // Test parameters
        val producerReg = 5
        val consumerReg = 10
        val producerData = BigInt("DEADBEEF", 16)
        val consumerData = BigInt("CAFEBABE", 16)
        val expectedResult = producerData + consumerData

        println(s"[Test] Starting Wakeup Mechanism Test")
        println(s"[Test] Producer will write 0x${producerData.toString(16)} to reg $producerReg")
        println(s"[Test] Consumer depends on reg $producerReg and adds 0x${consumerData.toString(16)}")

        // 1. Setup consumer data
        helper.prfWrite(consumerReg, consumerData)
        cd.waitSampling(5)

        // 2. Issue producer operation (will write to producerReg)
        val producerOp = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = 0,
          src1Data = 0,
          src2Reg = 0,
          src2Data = 0,
          destReg = producerReg
        ))
        
        // 3. Issue consumer operation (depends on producerReg)
        val consumerOp = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = producerReg,  // Depends on producer
          src1Data = 0,  // Will be filled by wakeup
          src2Reg = consumerReg,
          src2Data = consumerData,
          destReg = 15  // Result register
        ))

        println(s"[Test] Issued producer (robPtr=${producerOp.robPtr}) and consumer (robPtr=${consumerOp.robPtr})")

        // 4. Wait for producer to commit
        helper.waitForCommit(producerOp.robPtr)
        println(s"[Test] Producer committed, should trigger wakeup")

        // 5. Send wakeup signal for the produced register
        helper.sendWakeup(producerReg)
        println(s"[Test] Sent wakeup signal for reg $producerReg")

        // 6. Wait for consumer to commit
        helper.waitForCommit(consumerOp.robPtr)
        println(s"[Test] Consumer committed")

        // 7. Verify result
        helper.prfVerify(15, expectedResult)
        
        println("Test 'IssueToExecute_Integration - Wakeup Mechanism' PASSED")
      }
  }

  test("IssueToExecute_Integration - Multiple EU Types") {
    val pCfg = createPipelineConfig()

    SimConfig.withWave
      .compile(new IssueToExecuteTestBench(pCfg))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        val helper = new IssueToExecuteTestHelper(dut)
        helper.init()
        
        // Test parameters
        val aluSrc1 = 1
        val aluSrc2 = 2
        val aluDest = 10
        val branchSrc1 = 3
        val branchSrc2 = 4
        val branchDest = 11
        
        val aluData1 = BigInt("11111111", 16)
        val aluData2 = BigInt("22222222", 16)
        val branchData1 = BigInt("33333333", 16)
        val branchData2 = BigInt("44444444", 16)

        println(s"[Test] Starting Multiple EU Types Test")

        // 1. Setup PRF data
        helper.prfWrite(aluSrc1, aluData1)
        helper.prfWrite(aluSrc2, aluData2)
        helper.prfWrite(branchSrc1, branchData1)
        helper.prfWrite(branchSrc2, branchData2)
        cd.waitSampling(5)

        // 2. Issue operations to different EU types
        val aluOp = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.ALU,
          src1Reg = aluSrc1,
          src1Data = aluData1,
          src2Reg = aluSrc2,
          src2Data = aluData2,
          destReg = aluDest
        ))
        
        val branchOp = helper.issue(helper.TestOp(
          uopCode = BaseUopCode.BRANCH,
          src1Reg = branchSrc1,
          src1Data = branchData1,
          src2Reg = branchSrc2,
          src2Data = branchData2,
          destReg = branchDest
        ))

        println(s"[Test] Issued ALU (robPtr=${aluOp.robPtr}) and BRANCH (robPtr=${branchOp.robPtr}) operations")

        // 3. Wait for both operations to commit
        helper.waitForCommit(aluOp.robPtr)
        helper.waitForCommit(branchOp.robPtr)
        
        println(s"[Test] Both operations have been committed")

        // 4. Verify results (note: branch operations might not write to dest reg)
        // For this test, we just verify that both operations completed successfully
        // by checking that they were committed
        
        println("Test 'IssueToExecute_Integration - Multiple EU Types' PASSED")
      }
  }

  thatsAll()
} 