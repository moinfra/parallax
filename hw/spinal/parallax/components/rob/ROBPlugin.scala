package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Framework, Plugin, ParallaxLogger} // 导入 Plugin
import scala.collection.mutable.ArrayBuffer

class ROBPlugin[RU <: Data with Dumpable with HasRobPtr](
    // ROBPlugin 的构造参数通常是更高层次的配置，比如 PipelineConfig，
    // 然后它在内部派生出具体的 ROBConfig。
    // 或者直接传递 ROBConfig。为了简单，我们假设它能获取或构建 ROBConfig。
    val pipelineConfig: PipelineConfig, // 用于派生 ROBConfig
    val uopType: HardType[RU], // 显式传递 Uop 类型
    val defaultUop: () => RU // 显式传递 Uop 默认值构造函数
) extends Plugin
    with ROBService[RU] {

  // 从 PipelineConfig 和传入参数构建 ROBConfig
  // 注意：这里的 numWritebackPorts 需要与系统中所有EU请求的总数匹配（独占方案）
  // 这个值可能需要由顶层设计者根据EU数量来设定，或者ROBPlugin在获取所有EU后动态计算（较复杂）。
  // 为简单起见，假设它在pipelineConfig中或作为ROBPlugin的参数传入。
  // 我们需要确保 ROBConfig 中的 numWritebackPorts >= 将要请求的 EU 数量。
  // 暂时假设 pipelineConfig 包含一个估算的 euWritebackPortsMaxCount
  val robConfig = ROBConfig[RU](
    robDepth = pipelineConfig.robDepth,
    pcWidth = pipelineConfig.pcWidth,
    commitWidth = pipelineConfig.commitWidth,
    allocateWidth = pipelineConfig.renameWidth, // 通常分配宽度匹配重命名/分派宽度
    numWritebackPorts = pipelineConfig.totalEuCount,
    uopType = uopType,
    defaultUop = defaultUop,
    exceptionCodeWidth = pipelineConfig.exceptionCodeWidth
  )

  // --- 内部状态 ---
  // 实例化 ReorderBuffer 组件 在 early 创建以便其用户可以连接到它的 io 端口
  val robComponent = create early {
    ParallaxLogger.log(s"ROBPlugin: Creating ReorderBuffer component with config: $robConfig")
    new ReorderBuffer[RU](robConfig)
  }

  // 用于分配物理 ROB 写回端口的计数器
  private var nextWbPortIdx = 0
  // private val writebackPortsLock = new Object // 用于同步分配（如果需要，但在SpinalHDL早期阶段通常单线程）

  // --- 实现 ROBService 接口 ---

  // 分配阶段
  override def getAllocatePorts(width: Int): Vec[(ROBAllocateSlot[RU])] = {
    if (width != robConfig.allocateWidth) {
      SpinalError(
        s"ROBPlugin: Requested allocate width ($width) does not match ROB config allocateWidth (${robConfig.allocateWidth})"
      )
    }
    // robComponent.io.allocate 是 Vec[master(ROBAllocateSlot)]，
    // 提供给外部的 Allocate 阶段需要 slave 视角。
    // 这里直接返回 robComponent.io.allocate，SpinalHDL 会自动处理方向。
    robComponent.io.allocate
  }

  override def getCanAllocateVec(width: Int): Vec[Bool] = {
    if (width != robConfig.allocateWidth) {
      SpinalError(
        s"ROBPlugin: Requested canAllocate width ($width) does not match ROB config allocateWidth (${robConfig.allocateWidth})"
      )
    }
    // robComponent.io.canAllocate 是 Vec[out(Bool)]，
    // 提供给外部的 Allocate 阶段需要 in 视角。
    // 这里直接返回 robComponent.io.canAllocate，SpinalHDL 会自动处理方向。
    robComponent.io.canAllocate
  }

  // 写回阶段
  override def newWritebackPort(euName: String = ""): (ROBWritebackPort[RU]) = {
    if (nextWbPortIdx >= robConfig.numWritebackPorts) {
      SpinalError(
        s"ROBPlugin: All ${robConfig.numWritebackPorts} physical ROB writeback ports have been allocated. Cannot provide more for EU '$euName'. Increase ROBConfig.numWritebackPorts or use an arbiter."
      )
      // 在错误情况下，返回一个无效的或者默认驱动的端口，以避免后续连接错误
      // 但更好的方式是让系统在配置检查时就失败。
      val dummyPort = slave(ROBWritebackPort(robConfig))
      // 尝试驱动默认值或使其无效，但这可能不是最佳做法，错误应更早捕获。
      dummyPort.fire := False
      dummyPort.robPtr.assignDontCare()
      dummyPort.exceptionOccurred.assignDontCare()
      dummyPort.exceptionCodeIn.assignDontCare()
      dummyPort
    } else {
      // robComponent.io.writeback 是 Vec[slave(ROBWritebackPort)]，
      // 提供给外部的 EU 需要 master 视角。
      // 这里直接返回 robComponent.io.writeback(idx)，SpinalHDL 会自动处理方向。
      val physicalPortMaster = robComponent.io.writeback(nextWbPortIdx)
      nextWbPortIdx += 1
      ParallaxLogger.log(
        s"ROBPlugin: Allocated physical writeback port ${nextWbPortIdx - 1} to EU '$euName'. Total allocated: $nextWbPortIdx / ${robConfig.numWritebackPorts}."
      )
      physicalPortMaster
    }
  }

  // 提交阶段
  override def getCommitSlots(width: Int): Vec[(ROBCommitSlot[RU])] = {
    if (width != robConfig.commitWidth) {
      SpinalError(
        s"ROBPlugin: Requested commit width ($width) does not match ROB config commitWidth (${robConfig.commitWidth})"
      )
    }
    // robComponent.io.commit 是 Vec[master(ROBCommitSlot)]，
    // 提供给外部的 Committer 阶段需要 slave 视角。
    // 这里直接返回 robComponent.io.commit，SpinalHDL 会自动处理方向。
    robComponent.io.commit
  }

  override def getCommitAcks(width: Int): Vec[(Bool)] = {
    if (width != robConfig.commitWidth) {
      SpinalError(
        s"ROBPlugin: Requested commitAcks width ($width) does not match ROB config commitWidth (${robConfig.commitWidth})"
      )
    }
    robComponent.io.commitFire
  }

  // 清空/恢复阶段
  override def getFlushPort(): (Flow[ROBFlushPayload]) = {
    // robComponent.io.flush 已经是 slave Flow(ROBFlushCommand[RU])，
    // 提供给外部的 Flusher 阶段需要 master 视角。
    // 这里直接返回 robComponent.io.flush，SpinalHDL 会自动处理方向。
    robComponent.io.flush
  }

  // ROBPlugin 可能还有自己的 setup/logic 区域来处理一些初始化或全局逻辑，
  // 但主要的服务实现是通过连接到 robComponent.io。
  val logic = create late new Area {
    ParallaxLogger.log(s"ROBPlugin logic area entered. ROB component IO is now connected via service methods.")
    // 如果 robComponent 需要在 late 阶段进行某些连接或构建，可以在这里完成。
    // 例如，如果 robComponent 内部有复杂的流水线，其 .build() 可能在这里调用。
    // 但通常组件的构建在其自身内部完成。
  }
}
