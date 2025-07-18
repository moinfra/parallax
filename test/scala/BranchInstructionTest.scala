// filename: test/scala/BranchInstructionTest.scala
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
import parallax.components.issue.{IQEntryBru}

import scala.collection.mutable
import scala.util.Random

// 分支指令测试用例参数
case class BranchTestCase(
    name: String,
    condition: BranchCondition.E,
    src1Value: BigInt,
    src2Value: BigInt,
    pc: BigInt,
    imm: BigInt,
    isJump: Boolean,
    isIndirect: Boolean,
    isLink: Boolean,
    expectedTaken: Boolean,
    expectedTarget: BigInt,
    expectedLinkValue: Option[BigInt] = None
)

class BranchInstructionTestBench(val pCfg: PipelineConfig) extends Component {
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
    // 分支指令输入
    val branchInput = slave(Stream(IQEntryBru(pCfg)))
    
    // 监控端口
    val euResult = out(Bool()) // EU结果有效信号
    val resultData = out(Bits(pCfg.dataWidth)) // 结果数据
    val branchTaken = out(Bool()) // 分支是否跳转
    val targetPC = out(UInt(pCfg.pcWidth)) // 目标PC
    
    // BPU更新监控
    val bpuUpdateValid = out(Bool())
    val bpuUpdatePC = out(UInt(pCfg.pcWidth))
    val bpuUpdateTaken = out(Bool())
    val bpuUpdateTarget = out(UInt(pCfg.pcWidth))
    
    // ROB Flush监控  
    
    // 提交控制
    val enableCommit = in Bool()
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
      new BranchEuPlugin("BranchEU", pCfg),
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg)
    )
  )

  // 获取服务引用
  val branchEuPlugin = framework.getService[BranchEuPlugin]
  val fetchService = framework.getService[MockFetchServiceBru]
  val commitController = framework.getService[MockCommitControllerBru]
  val gprService = framework.getService[PhysicalRegFileService]

  // 连接分支EU输入
  branchEuPlugin.getEuInputPort << io.branchInput

  // 连接监控端口
  io.euResult := branchEuPlugin.euResult.valid
  io.resultData := branchEuPlugin.euResult.data
  
  // 从BranchEuPlugin获取监控信号
  io.branchTaken := branchEuPlugin.monitorSignals.branchTaken
  io.targetPC := branchEuPlugin.monitorSignals.targetPC
  
  // BPU更新监控
  io.bpuUpdateValid := branchEuPlugin.hw.bpuUpdatePort.valid
  io.bpuUpdatePC := branchEuPlugin.hw.bpuUpdatePort.payload.pc
  io.bpuUpdateTaken := branchEuPlugin.hw.bpuUpdatePort.payload.isTaken
  io.bpuUpdateTarget := branchEuPlugin.hw.bpuUpdatePort.payload.target
  
  // 提交控制连接
  commitController.setCommitEnable(io.enableCommit)
  
  // 设置fetch service为空闲
  fetchService.fetchStreamIn.valid := False
  // fetchService.fetchStreamIn.payload需要正确初始化
  fetchService.fetchStreamIn.payload.instruction := 0
  fetchService.fetchStreamIn.payload.pc := 0
}

class BranchInstructionTest extends CustomSpinalSimFunSuite {

  val pCfg = PipelineConfig(
    aluEuCount = 0,  // 只测试BRU
    lsuEuCount = 0,
    dispatchWidth = 1,
    renameWidth = 1,
    fetchWidth = 1
  )

  // 定义测试用例
  val testCases = Seq(
    // BEQ 分支测试
    BranchTestCase("BEQ_taken", BranchCondition.EQ, 10, 10, 0x1000, 0x20, 
                   isJump = false, isIndirect = false, isLink = false, 
                   expectedTaken = true, expectedTarget = 0x1020),
    BranchTestCase("BEQ_not_taken", BranchCondition.EQ, 10, 20, 0x1000, 0x20,
                   isJump = false, isIndirect = false, isLink = false, 
                   expectedTaken = false, expectedTarget = 0x1004),
    
    // BNE 分支测试  
    BranchTestCase("BNE_taken", BranchCondition.NE, 10, 20, 0x1000, 0x20,
                   isJump = false, isIndirect = false, isLink = false,
                   expectedTaken = true, expectedTarget = 0x1020),
    BranchTestCase("BNE_not_taken", BranchCondition.NE, 10, 10, 0x1000, 0x20,
                   isJump = false, isIndirect = false, isLink = false,
                   expectedTaken = false, expectedTarget = 0x1004),
    
    // JAL 测试
    BranchTestCase("JAL", BranchCondition.NUL, 0, 0, 0x1000, 0x100,
                   isJump = true, isIndirect = false, isLink = true,
                   expectedTaken = true, expectedTarget = 0x1100, expectedLinkValue = Some(0x1004)),
    
    // JALR 测试
    BranchTestCase("JALR", BranchCondition.NUL, 0x2000, 0, 0x1000, 0x20,
                   isJump = true, isIndirect = true, isLink = true,
                   expectedTaken = true, expectedTarget = 0x2020, expectedLinkValue = Some(0x1004))
  )

  for (testCase <- testCases) {
    test(s"BranchInstruction_${testCase.name}") {
      val compiled = simConfig.compile(new BranchInstructionTestBench(pCfg))
      compiled.doSim { dut =>
        val cd = dut.clockDomain
        cd.forkStimulus(period = 10)
        SimTimeout(10000)

        dut.io.enableCommit #= false
        cd.waitSampling(5)

        // 设置GPR数据 (通过预设值，而不是动态写入)
        cd.waitSampling(5)

        // 构造分支指令
        dut.io.branchInput.valid #= true
        val payload = dut.io.branchInput.payload
        payload.setDefaultForSim()
        
        payload.robPtr #= 1
        payload.physDest.idx #= 3
        payload.physDestIsFpr #= false
        payload.writesToPhysReg #= testCase.isLink
        
        payload.useSrc1 #= (testCase.src1Value != 0)
        payload.src1Tag #= 1
        payload.src1Ready #= true
        payload.src1IsFpr #= false
        payload.src1Data #= testCase.src1Value  // 直接设置数据而不是从PRF读取
        
        payload.useSrc2 #= (testCase.src2Value != 0)  
        payload.src2Tag #= 2
        payload.src2Ready #= true
        payload.src2IsFpr #= false
        payload.src2Data #= testCase.src2Value  // 直接设置数据而不是从PRF读取
        
        payload.branchCtrl.condition #= testCase.condition
        payload.branchCtrl.isJump #= testCase.isJump
        payload.branchCtrl.isIndirect #= testCase.isIndirect
        payload.branchCtrl.isLink #= testCase.isLink
        payload.branchCtrl.linkReg.setDefaultForSim()
        payload.branchCtrl.laCfIdx #= 0
        
        payload.imm #= testCase.imm
        payload.pc #= testCase.pc

        // 等待处理完成
        cd.waitSamplingWhere(50)(dut.io.euResult.toBoolean)
        
        // 验证BPU更新
        assert(dut.io.bpuUpdateValid.toBoolean, s"${testCase.name}: BPU update should be valid")
        assert(dut.io.bpuUpdatePC.toBigInt == testCase.pc, s"${testCase.name}: BPU update PC mismatch")
        assert(dut.io.bpuUpdateTaken.toBoolean == testCase.expectedTaken, s"${testCase.name}: BPU update taken mismatch")
        assert(dut.io.bpuUpdateTarget.toBigInt == testCase.expectedTarget, s"${testCase.name}: BPU update target mismatch")
        
        // 验证结果数据（对于链接指令）
        if (testCase.isLink && testCase.expectedLinkValue.isDefined) {
          assert(dut.io.resultData.toBigInt == testCase.expectedLinkValue.get, 
                 s"${testCase.name}: Link value mismatch")
        }

        dut.io.branchInput.valid #= false
        cd.waitSampling(5)
        
        println(s"${testCase.name}: PASSED")
      }
    }
  }

  thatsAll()
}
