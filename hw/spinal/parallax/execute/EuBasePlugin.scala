// filename: hw/spinal/parallax/execute/ParallaxEuBase.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax._
import parallax.components.rob._
import parallax.utilities._ // 包含 Plugin, Service, ParallaxLogger, findService, getService 等
import scala.collection.mutable.ArrayBuffer
import parallax.common.{PhysicalRegFileService, PrfReadPort, PrfWritePort}

case class EuPushPortPayload[T_EuSpecificContext <: Bundle](
    euSpecificContextType: HardType[T_EuSpecificContext],
    config: PipelineConfig
) extends Bundle
    with RobIndexedData {
  val renamedUop = RenamedUop(config)
  val src1Data = Bits(config.dataWidth)
  val src2Data = Bits(config.dataWidth)
  val euSpecificPayload = euSpecificContextType()
  override def getRobIdx(): UInt = renamedUop.robIdx
  def setDefault(): this.type = {
    renamedUop.setDefault()
    src1Data := B(0)
    src2Data := B(0)
    if (euSpecificContextType != null && euSpecificPayload.isInstanceOf[Bundle]) euSpecificPayload.assignDontCare()
    this
  }
}

trait EuInputSourceService[T_Ctx <: Bundle] extends Service {
  def getEuInputPort(): Stream[EuPushPortPayload[T_Ctx]]
  def getTargetName(): String
}

case class ParallaxEuCommonSignals[T_EuSpecificContext <: Bundle](
    euSpecificContextType: HardType[T_EuSpecificContext],
    config: PipelineConfig
) {
  val EU_INPUT_PAYLOAD = Stageable(
    EuPushPortPayload(euSpecificContextType, config)
  ) // Stageable now uses the same type as obtainedEuInput

  val EXEC_RESULT_DATA = Stageable(Bits(config.dataWidth))
  val EXEC_WRITES_TO_PREG = Stageable(Bool())
  val EXEC_HAS_EXCEPTION = Stageable(Bool())
  val EXEC_EXCEPTION_CODE = Stageable(UInt(config.exceptionCodeWidth))
  val EXEC_DEST_IS_FPR = Stageable(Bool()) // 可选：如果目标类型改变，EU 可以驱动此信号
}

// --- EU 寄存器文件类型枚举 ---
sealed trait EuRegType
object EuRegType {
  case object GPR_ONLY extends EuRegType
  case object FPR_ONLY extends EuRegType
  case object MIXED_GPR_FPR extends EuRegType
  case object NONE extends EuRegType

  type C = EuRegType
}

abstract class EuBasePlugin(
    val euName: String,
    val pipelineConfig: PipelineConfig,
    val readPhysRsDataFromPush: Boolean
) extends Plugin // Plugin 已经继承了 Area 和 Service
    with LockedImpl { // LockedImpl 用于 lock.await()

  // --- 需要由派生 EU 实现的抽象成员 ---
  type T_EuSpecificContext <: Bundle // EU 特定上下文 Bundle 的类型 (如果不需要，可以是 EmptyBundle)
  def euSpecificContextType: HardType[T_EuSpecificContext] // 返回 EU 特定上下文的 HardType
  def euRegType: EuRegType.C // 指定此 EU 与哪些物理寄存器文件交互

  // 派生 EU 必须实现这些方法来定义其微流水线
  def buildMicroPipeline(pipeline: Pipeline): Unit
  def connectPipelineLogic(pipeline: Pipeline): Unit
  // 派生 EU 必须返回其结果准备就绪的最终阶段
  def getWritebackStage(pipeline: Pipeline): Stage

  // --- 通用基础设施 ---
  lazy val commonSignals = ParallaxEuCommonSignals(euSpecificContextType, pipelineConfig)
  // EU 流水线输入 Payload 的 Stageable

  lazy val euInputPayloadType = EuPushPortPayload(euSpecificContextType, pipelineConfig)
  var obtainedEuInput: Stream[EuPushPortPayload[T_EuSpecificContext]] = null

  // 为此 EU Plugin 内的所有信号/组件添加前缀，以便在 Verilog 中清晰显示
  this.withPrefix(euName)

  // ROBService 的泛型参数类型，通常是 RenamedUop
  type RU_TYPE_FOR_ROB = RenamedUop

  // --- 服务依赖 (使用 lazy 实现延迟获取) ---
  lazy val gprFileService: PhysicalRegFileService = {
    if (euRegType == EuRegType.GPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR) {
      ParallaxLogger.log("寻找 PhysicalRegFileService")
      val ret = findService[PhysicalRegFileService](_.isGprService()) // findService 需要一个过滤器
      if (ret == null) SpinalError(s"$euName: 找不到 PhysicalRegFileService。")
      ParallaxLogger.log(s"找到 PhysicalRegFileService")
      ret
    } else {
      null // 如果 EU 类型不涉及 GPR，则为 null
    }
  }

  lazy val fprFileService: PhysicalRegFileService = {
    if (pipelineConfig.hasFpu && (euRegType == EuRegType.FPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR)) {
      findService[PhysicalRegFileService](_.isFprService()) // findService 需要一个过滤器
    } else null // 如果 EU 类型不涉及 FPR 或 FPU 未启用，则为 null
  }

  // ROBService 和 BypassService 是强制的，使用 getService 获取（如果按类型唯一）
  lazy val robService: ROBService[RU_TYPE_FOR_ROB] = getService[ROBService[RU_TYPE_FOR_ROB]]
  // BypassService 现在是泛型的，我们需要指定 Payload 类型 (BypassMessage)
  lazy val bypassService: BypassService[BypassMessage] = getService[BypassService[BypassMessage]]

  // --- PRF 读/写端口 ---
  val numGprReadPortsPerEu = if (readPhysRsDataFromPush) 0 else 2 // 每个 EU 实例的 GPR 读端口数量
  val numFprReadPortsPerEu = if (pipelineConfig.hasFpu && readPhysRsDataFromPush) 2 else 0 // 每个 EU 实例的 FPR 读端口数量

  lazy val gprReadPorts: Seq[PrfReadPort] =
    if (euRegType == EuRegType.GPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR) {
      if (gprFileService == null) SpinalError(s"$euName: GPRFileService 为 null，但 GPR 操作需要它。")
      ParallaxLogger.log(s"生成了 ${numGprReadPortsPerEu} 个 GPR 读端口。")
      Seq.fill(numGprReadPortsPerEu)(gprFileService.newReadPort())
    } else Seq.empty

  lazy val fprReadPorts: Seq[PrfReadPort] =
    if (pipelineConfig.hasFpu && (euRegType == EuRegType.FPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR)) {
      if (fprFileService == null) SpinalError(s"$euName: FPRFileService 为 null，但启用 FPU 时的 FPR 操作需要它。")
      ParallaxLogger.log(s"生成了 ${numFprReadPortsPerEu} 个 FPR 读端口。")
      Seq.fill(numFprReadPortsPerEu)(fprFileService.newReadPort())
    } else Seq.empty

  lazy val gprWritePort: PrfWritePort =
    if (euRegType == EuRegType.GPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR) {
      if (gprFileService == null) SpinalError(s"$euName: GPRFileService 为 null，但 GPR 写端口需要它。")
      gprFileService.newWritePort()
    } else null

  lazy val fprWritePort: PrfWritePort =
    if (pipelineConfig.hasFpu && (euRegType == EuRegType.FPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR)) {
      if (fprFileService == null) SpinalError(s"$euName: FPRFileService 为 null，但启用 FPU 时的 FPR 写端口需要它。")
      fprFileService.newWritePort()
    } else null

  // --- ROB 完成端口 ---
  // EU 从 ROBService 获取一个 ROBWritebackPort[RU_TYPE_FOR_ROB] 的 SLAVE 接口。
  // EU 将驱动此 Bundle 的字段。
  lazy val robWritebackPortBundle: ROBWritebackPort[RU_TYPE_FOR_ROB] = robService.newWritebackPort(euName)

  // --- 旁路输出端口 (基于 Flow) ---
  lazy val bypassOutputPort: Flow[BypassMessage] = bypassService.newBypassSource(euName)

  // --- 此 EU 处理的微操作 ---
  val microOpsHandled = ArrayBuffer[BaseUopCode.C]()
  def addMicroOp(uopCode: BaseUopCode.C): Unit = {
    if (!microOpsHandled.contains(uopCode)) microOpsHandled += uopCode
  }
  // def handlesUop(uopCode: BaseUopCode.C): Boolean = microOpsHandled.contains(uopCode) // 可选的辅助方法

  // --- 流水线构建 ---
  // EU 的内部微流水线
  val euPipeline = create early {
    this.obtainedEuInput =
      findService[EuInputSourceService[T_EuSpecificContext]](_.getTargetName() == euName).getEuInputPort()
    setName(s"${euName}_internal_pipeline")
    ParallaxLogger.log(s"EUBase ($euName): 微流水线创建。")
    if (gprFileService != null) {
      gprFileService.retain()
      ParallaxLogger.log(s"EUBase ($euName): 要求 GPR 先别生成读写逻辑，因为我们要增加一些端口。")
    }
    new Pipeline
  }

  // Setup 阶段：获取服务，构建流水线结构
  val setupPhase = create early new Area {
    ParallaxLogger.log(s"EUBase ($euName): Setup 阶段入口。")
    // 服务获取的有效性检查（gprFileService 和 fprFileService 可能为 null）
    if ((euRegType == EuRegType.GPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR) && gprFileService == null) {
      SpinalError(s"$euName 配置为 GPR 使用，但 GPRFileService 未找到 (null)。请检查服务注册和过滤器。")
    }
    if (
      pipelineConfig.hasFpu && (euRegType == EuRegType.FPR_ONLY || euRegType == EuRegType.MIXED_GPR_FPR) && fprFileService == null
    ) {
      SpinalError(s"$euName 配置为 FPR 使用 (且 FPU 已启用)，但 FPRFileService 未找到 (null)。请检查服务注册和过滤器。")
    }
    // robService 和 bypassService 由 getService 获取，如果未找到会抛出异常，所以它们不会是 null。

    buildMicroPipeline(euPipeline) // 调用派生 EU 的方法来定义阶段和连接

    // 派生 EU 负责在其 getWritebackStage 返回的阶段上声明 commonSignals.EXEC_DEST_IS_FPR
    // 并且必须驱动它为一个确定的布尔值 (True 或 False)。
    // ParallaxEuBase 将读取此值。我们在此处使用 assignDontCare 确保它被声明，
    // 如果 EU 未驱动它，其值将为 'X'，这表示 EU 的实现存在问题。
    getWritebackStage(euPipeline)(commonSignals.EXEC_DEST_IS_FPR).assignDontCare()

    ParallaxLogger.log(s"EUBase ($euName): 微流水线结构已构建。")
  }

  // Logic 阶段：连接 EU 特定逻辑，然后是通用的写回/旁路/ROB 逻辑
  val logicPhase = create late new Area {
    ParallaxLogger.log(s"EUBase ($euName): Logic 阶段入口。")
    lock.await() // 等待所有 early 操作完成

    // 派生 EU 连接其特定的数据处理逻辑
    connectPipelineLogic(euPipeline)
    ParallaxLogger.log(s"EUBase ($euName): EU 特定流水线逻辑已连接。")

    // --- 连接通用输出 (PRF 写、ROB、旁路) ---
    val wbStage = getWritebackStage(euPipeline) // 从派生 EU 获取最终阶段
    val uopFullPayloadAtWb = wbStage(commonSignals.EU_INPUT_PAYLOAD) // 获取该阶段的完整 Uop 输入
    val renamedUopAtWb = uopFullPayloadAtWb.renamedUop

    // EU 执行逻辑产生的数据
    val resultData = wbStage(commonSignals.EXEC_RESULT_DATA)
    val resultWritesToPreg = wbStage(commonSignals.EXEC_WRITES_TO_PREG)
    val resultHasException = wbStage(commonSignals.EXEC_HAS_EXCEPTION)
    val resultExceptionCode = wbStage(commonSignals.EXEC_EXCEPTION_CODE)

    // 最终目标是否为 FPR。派生 EU 必须在其 wbStage 中驱动 commonSignals.EXEC_DEST_IS_FPR。
    val finalDestIsFpr: Bool = wbStage(commonSignals.EXEC_DEST_IS_FPR)

    // 1. 物理寄存器文件写
    if (gprWritePort != null) { // 仅当 gprWritePort 有效时连接 (即 gprFileService 存在且 EU 类型匹配)
      gprWritePort.valid := wbStage.isFiring && resultWritesToPreg && !finalDestIsFpr && !resultHasException
      gprWritePort.address := renamedUopAtWb.rename.physDest.idx
      gprWritePort.data := resultData
    }

    if (fprWritePort != null) { // 仅当 fprWritePort 有效时连接
      fprWritePort.valid := wbStage.isFiring && resultWritesToPreg && finalDestIsFpr && !resultHasException
      fprWritePort.address := renamedUopAtWb.rename.physDest.idx
      fprWritePort.data := resultData
    }
    ParallaxLogger.log(s"EUBase ($euName): PRF 写逻辑已连接。")

    // 2. ROB 完成
    // 驱动从 ROBService 获取的 slave ROBWritebackPort Bundle 的字段
    robWritebackPortBundle.fire := wbStage.isFiring // EU 发信号表示有完成需要报告
    robWritebackPortBundle.robIdx := renamedUopAtWb.robIdx
    robWritebackPortBundle.exceptionOccurred := resultHasException
    robWritebackPortBundle.exceptionCodeIn := resultExceptionCode
    ParallaxLogger.log(s"EUBase ($euName): ROB 完成逻辑已连接。")

    // 3. 旁路网络输出 (基于 Flow)
    bypassOutputPort.valid := wbStage.isFiring && resultWritesToPreg && !resultHasException
    bypassOutputPort.payload.physRegIdx := renamedUopAtWb.rename.physDest.idx
    bypassOutputPort.payload.physRegData := resultData
    bypassOutputPort.payload.physRegDataValid := True // 如果旁路，数据有效
    bypassOutputPort.payload.robIdx := renamedUopAtWb.robIdx
    bypassOutputPort.payload.isFPR := finalDestIsFpr
    bypassOutputPort.payload.hasException := resultHasException // 旁路消息可以携带异常信息供唤醒逻辑使用
    bypassOutputPort.payload.exceptionCode := resultExceptionCode
    // Flow 没有 .ready 信号
    ParallaxLogger.log(s"EUBase ($euName): 旁路输出 Flow 逻辑已连接。")

    // 最后，在所有连接完成后构建 EU 的内部流水线
    euPipeline.build()
    ParallaxLogger.log(s"EUBase ($euName): Logic 阶段完成。EU 流水线已构建。是否从 Push 读取数据: $readPhysRsDataFromPush。")
    if (gprFileService != null) {
      gprFileService.release()
      ParallaxLogger.log(s"EUBase ($euName): 释放对 GPRFileService 的锁定。")
    }
  }

  def connectGprRead(
      stage: Stage, // 发起读取的阶段
      prfReadPortIdx: Int, // 使用此 EU 的哪个 GPR 读端口 (0 到 numGprReadPortsPerEu-1)
      physRegIdx: UInt, // 要读取的物理寄存器索引
      useThisSrcSignal: Bool // 此读取激活的条件 (例如 uop.useArchSrc1)
  ): Bits = {
    if (gprFileService == null) {
      ParallaxLogger.panic(s"$euName: 尝试 GPR 读取，但 GPR 服务不存在。")
    }
    if (euRegType == EuRegType.FPR_ONLY || euRegType == EuRegType.NONE || gprFileService == null) {
      val reason = if (gprFileService == null) "gpr_service_null" else euRegType.toString()
      ParallaxLogger.panic(s"$euName (类型: $reason) 尝试 GPR 读取。设计错误。")
    }
    if (prfReadPortIdx < gprReadPorts.length) { // 检查端口索引是否在范围内
      val gprPort = gprReadPorts(prfReadPortIdx)
      gprPort.valid := stage.isFiring && useThisSrcSignal // 当阶段有效触发且需要此源时，读取命令有效
      gprPort.address := physRegIdx
      return gprPort.rsp
    } else {
      ParallaxLogger.panic(s"$euName: 请求的 GPR 读端口索引 $prfReadPortIdx 超出范围 (${gprReadPorts.length} 可用)。")
    }
  }

  def connectFprRead(
      stage: Stage,
      prfReadPortIdx: Int,
      physRegIdx: UInt,
      useThisSrcSignal: Bool,
      prfDataTarget: Stageable[Bits]
  ): Unit = {
    if (!pipelineConfig.hasFpu) { // 如果 FPU 未在全局配置中启用
      // ParallaxLogger.error(s"$euName 尝试 FPR 读取，但 FPU 未全局启用。") // 此错误更适合在配置时捕获
      stage(prfDataTarget).assignDontCare()
      when(stage.isFiring && useThisSrcSignal) {
        report(L"SIM_ERROR: $euName: FPU 在 pipelineConfig 中禁用时尝试 FPR 读取。物理寄存器: ${physRegIdx}")
        stage.haltIt()
      }
      return
    }
    if (euRegType == EuRegType.GPR_ONLY || euRegType == EuRegType.NONE || fprFileService == null) {
      val reason = if (fprFileService == null) "fpr_service_null" else euRegType.toString()
      ParallaxLogger.error(s"$euName (类型: $reason) 尝试 FPR 读取。设计错误。")
      stage(prfDataTarget).assignDontCare()
      when(stage.isFiring && useThisSrcSignal) {
        report(L"SIM_ERROR: $euName: 非 FPR EU 或 FPR 服务缺失时尝试 FPR 读取。物理寄存器: ${physRegIdx}")
        stage.haltIt()
      }
      return
    }
    if (prfReadPortIdx < fprReadPorts.length) { // 检查端口索引是否在范围内
      val fprPort = fprReadPorts(prfReadPortIdx)
      fprPort.valid := stage.isFiring && useThisSrcSignal
      fprPort.address := physRegIdx
      stage(prfDataTarget) := fprPort.rsp // 类似 GPR 读取的延迟注意事项
    } else {
      ParallaxLogger.error(s"$euName: 请求的 FPR 读端口索引 $prfReadPortIdx 超出范围 (${fprReadPorts.length} 可用)。")
      stage(prfDataTarget).assignDontCare()
      when(stage.isFiring && useThisSrcSignal) {
        report(L"SIM_ERROR: $euName: FPR 读端口索引 ${prfReadPortIdx.toString} 超出范围。物理寄存器: ${physRegIdx}")
        stage.haltIt()
      }
    }
  }
}
