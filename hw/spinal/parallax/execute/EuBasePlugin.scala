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
import parallax.execute.{WakeupService, WakeupPayload}

// 引入 BusyTableService
import parallax.components.rename.BusyTableService

// 引入 IQEntryLike trait
import parallax.components.issue.IQEntryLike

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
    val pipelineConfig: PipelineConfig
) extends Plugin // Plugin 已经继承了 Area 和 Service
    with LockedImpl { // LockedImpl 用于 lock.await()

  // --- 需要由派生 EU 实现的抽象成员 ---
  type T_IQEntry <: Bundle with IQEntryLike // EU 特定 IQ Entry Bundle 的类型
  def iqEntryType: HardType[T_IQEntry] // 返回 EU 特定 IQ Entry 的 HardType
  def euRegType: EuRegType.C // 指定此 EU 与哪些物理寄存器文件交互
  def getEuType: ExeUnitType.E
  // 新增：由子类决定端口数量
  def numGprReadPortsPerEu: Int
  def numFprReadPortsPerEu: Int
  // **新的抽象方法**: 子类必须实现这个方法来构建它们的内部逻辑
  def buildEuLogic(): Unit

  // --- 通用基础设施 ---
  // EU的输入端口，由Linker连接
  lazy val euInputPort = Stream(iqEntryType())
  def getEuInputPort: Stream[T_IQEntry] = euInputPort

  // 为此 EU Plugin 内的所有信号/组件添加前缀，以便在 Verilog 中清晰显示
  this.withPrefix(euName)

  // ROBService 的泛型参数类型，通常是 RenamedUop
  type RU_TYPE_FOR_ROB = RenamedUop

  // --- 新增：类型安全的连接方法 ---
  // 这个方法接收一个"任何类型"的IQ输出流
  // 它在内部进行类型安全的检查和连接
  def connectIssueQueue(iqOutput: Stream[Bundle with IQEntryLike]): Unit = {
    this.euInputPort << iqOutput.asInstanceOf[Stream[T_IQEntry]]
  }

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

  // --- 新增服务: BusyTableService 和 WakeupService ---
  lazy val busyTableService: BusyTableService = getService[BusyTableService]
  lazy val wakeupService: WakeupService = getService[WakeupService]
  lazy val wakeupSourcePort: Flow[WakeupPayload] = wakeupService.newWakeupSource()

  // --- PRF 读/写端口 ---
  // 由子类决定端口数量
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

  // --- 新的"结果契约" Area ---
  // 子类必须在完成计算时驱动这个区域的信号
  protected val euResult = new Area {
    val valid = Bool() // 子类在结果就绪时置为 True
    val uop = iqEntryType() // 产生该结果的微指令
    val data = Bits(pipelineConfig.dataWidth) // 计算结果
    val writesToPreg = Bool() // 是否写物理寄存器
    val hasException = Bool()
    val exceptionCode = UInt(pipelineConfig.exceptionCodeWidth)
    val destIsFpr = Bool() // 目标是否为 FPR

    // 必须在 buildEuLogic 之外为它们设置默认值，以防子类未驱动时产生锁存器
    valid := False
    uop.setDefault() // 使用 setDefault() 而不是 assignDontCare()
    data := B(0)
    writesToPreg := False
    hasException := False
    exceptionCode := U(0)
    destIsFpr := False
  }

  // Setup 阶段：获取服务，创建端口
  val setup = create early new Area {
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

    // --- 新增：保留服务 ---
    // 在 setup 阶段保留我们需要的服务，防止它们提前进入 late 阶段
    if (gprFileService != null) gprFileService.retain()
    if (fprFileService != null) fprFileService.retain()

    ParallaxLogger.log(s"EUBase ($euName): Setup 阶段完成。")
  }

  // Logic 阶段：连接 EU 特定逻辑，然后是通用的写回/旁路/ROB 逻辑
  val logicPhase = create late new Area {
    ParallaxLogger.log(s"EUBase ($euName): Logic 阶段入口。")
    lock.await() // 等待所有 early 操作完成

    // 1. 调用子类来构建它们自己的内部逻辑
    buildEuLogic()
    ParallaxLogger.log(s"EUBase ($euName): 子类 EU 特定逻辑已构建。")

    // 2. 将 "结果契约" (euResult) 连接到所有输出服务
    val uopAtWb = euResult.uop
    val finalDestIsFpr = euResult.destIsFpr

    val executionCompletes = euResult.valid && euResult.writesToPreg
    val completesSuccessfully = executionCompletes && !euResult.hasException

    // 用于调试的日志
    when(euResult.valid) {
      report(L"EUBase ($euName): Result valid, writesToPreg=${euResult.writesToPreg}, hasException=${euResult.hasException}, executionCompletes=${executionCompletes}, completesSuccessfully=${completesSuccessfully}")
    }

    // 1. 物理寄存器文件写
    if (gprWritePort != null) { // 仅当 gprWritePort 有效时连接 (即 gprFileService 存在且 EU 类型匹配)
      gprWritePort.valid := completesSuccessfully && !finalDestIsFpr
      gprWritePort.address := uopAtWb.physDest.idx
      gprWritePort.data := euResult.data
    }

    if (fprWritePort != null) { // 仅当 fprWritePort 有效时连接
      fprWritePort.valid := completesSuccessfully && finalDestIsFpr
      fprWritePort.address := uopAtWb.physDest.idx
      fprWritePort.data := euResult.data
    }
    ParallaxLogger.log(s"EUBase ($euName): PRF 写逻辑已连接。")

    // 2. ROB 完成
    // 总是向ROB报告完成状态，无论有无异常。ROB需要记录异常信息。
    robWritebackPortBundle.fire := euResult.valid
    robWritebackPortBundle.robPtr := uopAtWb.robPtr
    robWritebackPortBundle.exceptionOccurred := euResult.hasException
    robWritebackPortBundle.exceptionCodeIn := euResult.exceptionCode
    ParallaxLogger.log(s"EUBase ($euName): ROB 完成逻辑已连接。")

    // 3. 旁路网络输出 (基于 Flow)
    // Bypass网络也需要广播结果，但会携带异常标志。
    // 消费者可以选择如何处理带有异常标志的旁路数据。
    bypassOutputPort.valid := executionCompletes // 即使有异常也要广播，让消费者知道这个tag已完成
    bypassOutputPort.payload.physRegIdx := uopAtWb.physDest.idx
    bypassOutputPort.payload.physRegData := euResult.data // 数据可以是任意值，因为hasException会为true
    bypassOutputPort.payload.robPtr := uopAtWb.robPtr
    bypassOutputPort.payload.isFPR := finalDestIsFpr
    bypassOutputPort.payload.hasException := euResult.hasException
    bypassOutputPort.payload.exceptionCode := euResult.exceptionCode
    // Flow 没有 .ready 信号
    ParallaxLogger.log(s"EUBase ($euName): 旁路输出 Flow 逻辑已连接。")

    // *** 4. 唤醒总线输出 (核心修正) ***
    // 只要指令执行完成（无论有无异常），就必须广播其tag以唤醒等待者。
    wakeupSourcePort.valid := executionCompletes
    wakeupSourcePort.payload.physRegIdx := uopAtWb.physDest.idx
    ParallaxLogger.log(s"EUBase ($euName): 唤醒总线逻辑已连接。")

    // *** 5. BusyTable 清除端口 ***
    // 当指令执行完成时，清除对应的BusyTable位
    // 注意：无论有无异常，只要指令写寄存器，就必须清除BusyTable位
    // - 有异常：指令不写入PRF，但BusyTable位需要清除（寄存器不再"in flight"）
    // - 无异常：指令成功写入PRF，BusyTable位需要清除（寄存器现在有有效数据）
    val clearBusyPort = busyTableService.newClearPort()
    clearBusyPort.valid := executionCompletes
    clearBusyPort.payload := uopAtWb.physDest.idx
    ParallaxLogger.log(s"EUBase ($euName): BusyTable 清除逻辑已连接。")

    // --- 新增：释放服务 ---
    // EU 逻辑已全部连接，可以释放之前保留的服务
    if (gprFileService != null) gprFileService.release()
    if (fprFileService != null) fprFileService.release()

    ParallaxLogger.log(s"EUBase ($euName): Logic 阶段完成。")
  }

  def connectGprRead(
      stage: Stage, // 发起读取的阶段
      prfReadPortIdx: Int, // 使用此 EU 的哪个 GPR 读端口 (0 到 numGprReadPortsPerEu-1)
      physRegIdx: UInt, // 要读取的物理寄存器索引
      useThisSrcSignal: Bool // 此读取激活的条件 (例如 uop.useArchSrc1)
  ): Bits = {
    ParallaxLogger.debug(s"$euName: 连接 GPR 读取 (端口 $prfReadPortIdx)")

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
