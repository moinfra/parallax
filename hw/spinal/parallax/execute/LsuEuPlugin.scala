// filename: hw/spinal/parallax/execute/LsuEuPlugin.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import parallax.components.issue.IQEntryLsu
import parallax.components.lsu._
import scala.collection.mutable.ArrayBuffer

class LsuEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters
) extends EuBasePlugin(euName, pipelineConfig) {

  // LSU 需要2个GPR读端口（基址寄存器和存储数据寄存器）
  override def numGprReadPortsPerEu: Int = 2
  override def numFprReadPortsPerEu: Int = 0

  // --- EuBasePlugin Implementation ---
  override type T_IQEntry = IQEntryLsu
  override def iqEntryType: HardType[IQEntryLsu] = HardType(IQEntryLsu(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY
  override def getEuType: ExeUnitType.E = ExeUnitType.MEM

  // LSU 处理的微操作
  addMicroOp(BaseUopCode.LOAD)
  addMicroOp(BaseUopCode.STORE)

  // --- 硬件服务与端口获取 ---
  val hw = create early new Area {
    val dcacheServiceInst = getService[DataCacheService]
    val aguServiceInst = getService[AguService]
    val storeBufferServiceInst = getService[StoreBufferService]

    // 获取硬件端口
    val lqAguPort = aguServiceInst.newAguPort()
    val sqAguPort = aguServiceInst.newAguPort()
    val dCacheLoadPort = dcacheServiceInst.newLoadPort(priority = 1)
    val sbQueryPort = storeBufferServiceInst.getStoreQueueQueryPort()
    val sbPushPort = storeBufferServiceInst.getPushPort()

    // 保留服务
    dcacheServiceInst.retain()
    aguServiceInst.retain()
    storeBufferServiceInst.retain()
  }

  // 实现抽象方法
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[LsuEu ${euName}] Logic start definition.")

    // 1. 创建内部流水线
    val pipeline = new Pipeline {
      val s0_dispatch = newStage().setName("s0_Dispatch")
      val s1_aguExecute = newStage().setName("s1_AguExecute")
      val s2_memoryAccess = newStage().setName("s2_MemoryAccess")
    }.setCompositeName(this, "internal_pipeline")

    // 2. 定义 Stageable 信号
    val EU_INPUT_PAYLOAD = Stageable(iqEntryType())
    val S1_BASE_ADDR_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val S1_STORE_DATA = Stageable(Bits(pipelineConfig.dataWidth))
    val S1_EFFECTIVE_ADDR = Stageable(UInt(pipelineConfig.pcWidth))
    val S1_IS_LOAD = Stageable(Bool())
    val S1_IS_STORE = Stageable(Bool())
    val S1_ACCESS_SIZE = Stageable(MemAccessSize())

    // 3. 连接输入端口到流水线
    pipeline.s0_dispatch.driveFrom(getEuInputPort)
    pipeline.s0_dispatch(EU_INPUT_PAYLOAD) := getEuInputPort.payload

    // 连接流水线阶段
    pipeline.connect(pipeline.s0_dispatch, pipeline.s1_aguExecute)(Connection.M2S())
    pipeline.connect(pipeline.s1_aguExecute, pipeline.s2_memoryAccess)(Connection.M2S())

    // --- Stage S1: AGU Execute ---
    val uopAtS1 = pipeline.s1_aguExecute(EU_INPUT_PAYLOAD)
    
    // 读取寄存器数据
    val baseAddrData = connectGprRead(pipeline.s1_aguExecute, 0, uopAtS1.src1Tag, uopAtS1.useSrc1)
    val storeData = connectGprRead(pipeline.s1_aguExecute, 1, uopAtS1.src2Tag, uopAtS1.useSrc2)
    
    // 地址计算
    val effectiveAddr = (baseAddrData.asSInt + uopAtS1.imm.asSInt).asUInt.resized
    
    // 从memCtrl解析访问类型和大小
    val isLoad = !uopAtS1.memCtrl.isStore
    val isStore = uopAtS1.memCtrl.isStore
    val accessSize = uopAtS1.memCtrl.size
    
    // 存储到下一阶段
    pipeline.s1_aguExecute(S1_BASE_ADDR_DATA) := baseAddrData
    pipeline.s1_aguExecute(S1_STORE_DATA) := storeData
    pipeline.s1_aguExecute(S1_EFFECTIVE_ADDR) := effectiveAddr
    pipeline.s1_aguExecute(S1_IS_LOAD) := isLoad
    pipeline.s1_aguExecute(S1_IS_STORE) := isStore
    pipeline.s1_aguExecute(S1_ACCESS_SIZE) := accessSize

    // 连接AGU端口 - 始终驱动所有信号以避免latch
    val isFiring = pipeline.s1_aguExecute.isFiring
    val shouldSendToLoadAgu = isFiring && isLoad
    val shouldSendToStoreAgu = isFiring && isStore
    
    // Load AGU端口
    hw.lqAguPort.input.valid := shouldSendToLoadAgu
    when(shouldSendToLoadAgu) {
      hw.lqAguPort.input.payload.qPtr := 0  // 简化测试，使用固定值
      hw.lqAguPort.input.payload.isLoad := True
      hw.lqAguPort.input.payload.isStore := False
      hw.lqAguPort.input.payload.isFlush := False
      hw.lqAguPort.input.payload.robPtr := uopAtS1.robPtr
      hw.lqAguPort.input.payload.basePhysReg := uopAtS1.src1Tag
      hw.lqAguPort.input.payload.immediate := uopAtS1.imm.asSInt.resized
      hw.lqAguPort.input.payload.usePc := False
      hw.lqAguPort.input.payload.pc := 0
      hw.lqAguPort.input.payload.physDst := uopAtS1.physDest.idx
      hw.lqAguPort.input.payload.accessSize := accessSize
      hw.lqAguPort.input.payload.dataReg := 0
    } otherwise {
      // 使用明确的默认值替代 assignDontCare() 避免Verilog语法错误
      hw.lqAguPort.input.payload.qPtr := 0
      hw.lqAguPort.input.payload.isLoad := False
      hw.lqAguPort.input.payload.isStore := False
      hw.lqAguPort.input.payload.isFlush := False
      hw.lqAguPort.input.payload.robPtr := 0
      hw.lqAguPort.input.payload.basePhysReg := 0
      hw.lqAguPort.input.payload.immediate := 0
      hw.lqAguPort.input.payload.usePc := False
      hw.lqAguPort.input.payload.pc := 0
      hw.lqAguPort.input.payload.physDst := 0
      hw.lqAguPort.input.payload.accessSize := MemAccessSize.B
      hw.lqAguPort.input.payload.dataReg := 0
    }
    
    // Store AGU端口
    hw.sqAguPort.input.valid := shouldSendToStoreAgu
    when(shouldSendToStoreAgu) {
      hw.sqAguPort.input.payload.qPtr := 0  // 简化测试，使用固定值
      hw.sqAguPort.input.payload.isLoad := False
      hw.sqAguPort.input.payload.isStore := True
      hw.sqAguPort.input.payload.isFlush := False
      hw.sqAguPort.input.payload.robPtr := uopAtS1.robPtr
      hw.sqAguPort.input.payload.basePhysReg := uopAtS1.src1Tag
      hw.sqAguPort.input.payload.immediate := uopAtS1.imm.asSInt.resized
      hw.sqAguPort.input.payload.usePc := False
      hw.sqAguPort.input.payload.pc := 0
      hw.sqAguPort.input.payload.dataReg := uopAtS1.src2Tag
      hw.sqAguPort.input.payload.physDst := 0
      hw.sqAguPort.input.payload.accessSize := accessSize
    } otherwise {
      // 使用明确的默认值替代 assignDontCare() 避免Verilog语法错误
      hw.sqAguPort.input.payload.qPtr := 0
      hw.sqAguPort.input.payload.isLoad := False
      hw.sqAguPort.input.payload.isStore := False
      hw.sqAguPort.input.payload.isFlush := False
      hw.sqAguPort.input.payload.robPtr := 0
      hw.sqAguPort.input.payload.basePhysReg := 0
      hw.sqAguPort.input.payload.immediate := 0
      hw.sqAguPort.input.payload.usePc := False
      hw.sqAguPort.input.payload.pc := 0
      hw.sqAguPort.input.payload.dataReg := 0
      hw.sqAguPort.input.payload.physDst := 0
      hw.sqAguPort.input.payload.accessSize := MemAccessSize.B
    }

    // --- Stage S2: Memory Access ---
    val uopAtS2 = pipeline.s2_memoryAccess(EU_INPUT_PAYLOAD)
    val effectiveAddrS2 = pipeline.s2_memoryAccess(S1_EFFECTIVE_ADDR)
    val isLoadS2 = pipeline.s2_memoryAccess(S1_IS_LOAD)
    val isStoreS2 = pipeline.s2_memoryAccess(S1_IS_STORE)
    val accessSizeS2 = pipeline.s2_memoryAccess(S1_ACCESS_SIZE)
    val storeDataS2 = pipeline.s2_memoryAccess(S1_STORE_DATA)

    // 简化的内存访问逻辑 - 为了快速测试
    // 实际应该通过AGU和缓存进行复杂的内存访问流程
    val memAccessComplete = Bool()
    val memAccessData = Bits(pipelineConfig.dataWidth)
    val memAccessException = Bool()

    // 简化逻辑：假设内存访问总是成功
    memAccessComplete := pipeline.s2_memoryAccess.isFiring
    memAccessData := Mux(isLoadS2, 
      effectiveAddrS2.asBits.resized, // Load时返回地址作为数据（测试用）
      storeDataS2                     // Store时返回存储的数据
    )
    memAccessException := False

    // 驱动euResult
    when(pipeline.s2_memoryAccess.isFiring) {
      euResult.valid := True
      euResult.uop := uopAtS2
      euResult.data := memAccessData
      euResult.writesToPreg := isLoadS2 // 只有Load指令写寄存器
      euResult.hasException := memAccessException
      euResult.exceptionCode := 0
      euResult.destIsFpr := False
      
      // 添加日志
      ParallaxSim.log(L"[LsuEu ${euName}] S2 Firing: isLoad=${isLoadS2}, isStore=${isStoreS2}, addr=${effectiveAddrS2}, data=${memAccessData}")
    }

    // 构建流水线
    pipeline.build()
    
    // 释放服务
    hw.dcacheServiceInst.release()
    hw.aguServiceInst.release()
    hw.storeBufferServiceInst.release()
    
    ParallaxLogger.log(s"[LsuEu ${euName}] Logic defined and pipeline built.")
  }
  
  // 重写EuBasePlugin的旁路逻辑，因为我们不需要通用的BypassMessage
  // 但需要提供虚拟端口以避免空指针
  override lazy val bypassService = null
  override lazy val bypassOutputPort = Flow(BypassMessage(pipelineConfig))
  
  // 注意：不需要 disableBypass area，因为 EuBasePlugin 会完全处理 bypassOutputPort 的驱动
}