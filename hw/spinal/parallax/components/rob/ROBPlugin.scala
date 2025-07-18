package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Framework, Plugin, ParallaxLogger, ParallaxSim} // 导入 Plugin
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Formattable

class ROBPlugin[RU <: Data with Formattable with HasRobPtr](
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
  
  // 用于管理多个 flush 端口的聚合
  private val flushPortsList = ArrayBuffer[Flow[ROBFlushPayload]]()
  
  // 聚合信号，在late阶段填充
  val aggregatedFlushSignal = Flow(ROBFlushPayload(robConfig.robPtrWidth))

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
    this.framework.requireEarly()
    if (nextWbPortIdx >= robConfig.numWritebackPorts) {
      assert(false, "ROBPlugin: No more physical writeback ports available. Increase numWritebackPorts in ROBConfig or request fewer EUs.")
      val dummyPort = slave(ROBWritebackPort(robConfig))
      dummyPort.setDefault()
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
    robComponent.io.commitAck
  }

  // 清空/恢复阶段
  override def newRobFlushPort(): (Flow[ROBFlushPayload]) = {
    this.framework.requireEarly()
    // 创建一个新的 flush 端口
    val flushPort = Flow(ROBFlushPayload(robConfig.robPtrWidth))
    
    // 将其添加到 flush 端口列表中
    flushPortsList += flushPort
    
    ParallaxLogger.log(s"ROBPlugin: Created flush port ${flushPortsList.size}. Total flush ports: ${flushPortsList.size}")
    
    // 返回新创建的端口
    flushPort
  }

  override def doRobFlush(): (Flow[ROBFlushPayload]) = {
    // 返回聚合信号而不是直接返回 robComponent.io.flush
    // 这样其他服务可以监听聚合后的flush信号
    aggregatedFlushSignal
  }
  // ROBPlugin 可能还有自己的 setup/logic 区域来处理一些初始化或全局逻辑，
  // 但主要的服务实现是通过连接到 robComponent.io。
  val logic = create late new Area {
    lock.await()
    ParallaxLogger.log(s"ROBPlugin logic area entered. ROB component IO is now connected via service methods.")
    
    // 聚合所有 flush 端口的信号
    if (flushPortsList.nonEmpty) {
      // 使用 OR 逻辑聚合所有 flush 端口的 valid 信号，模仿DataCachePlugin的reduceBalancedTree
      val aggregatedValid = flushPortsList.map(_.valid).reduceBalancedTree(_ || _)
      aggregatedFlushSignal.valid := aggregatedValid
      
      // 当有任何一个 flush 端口有效时，使用优先级选择逻辑选择第一个有效端口的 payload
      // 这里采用优先级选择逻辑，优先级按照端口创建顺序
      val aggregatedPayload = ROBFlushPayload(robConfig.robPtrWidth)
      aggregatedPayload.assignDontCare()
      
      // 从后往前遍历，后面的端口优先级更高（覆盖前面的）
      for (flushPort <- flushPortsList.reverse) {
        when(flushPort.valid) {
          aggregatedPayload := flushPort.payload
        }
      }
      aggregatedFlushSignal.payload := aggregatedPayload
      
      ParallaxLogger.log(s"ROBPlugin: Aggregated ${flushPortsList.size} flush ports with OR logic")
      
      // 添加调试日志
      when(aggregatedFlushSignal.valid) {
        report(L"[ROBPlugin] Aggregated flush signal is valid! Total ports: ${flushPortsList.size}")
      }
      for (i <- flushPortsList.indices) {
        when(flushPortsList(i).valid) {
          report(L"[ROBPlugin] Flush port ${i} is valid (reason=${flushPortsList(i).payload.reason})")
        }
      }
    } else {
      // 如果没有 flush 端口，则保持聚合信号为无效
      aggregatedFlushSignal.valid := False
      aggregatedFlushSignal.payload.assignDontCare()
      ParallaxLogger.log(s"ROBPlugin: No flush ports created, keeping aggregated signal invalid")
    }
    
    // 将聚合信号连接到 ROB 组件，直接连接而不使用<<操作符
    robComponent.io.flush.valid := aggregatedFlushSignal.valid
    robComponent.io.flush.payload := aggregatedFlushSignal.payload
    
    // 添加调试日志
    when(robComponent.io.flush.valid) {
      report(L"[ROBPlugin] ROB component flush input is valid!")
    }
    
    // 如果 robComponent 需要在 late 阶段进行某些连接或构建，可以在这里完成。
    // 例如，如果 robComponent 内部有复杂的流水线，其 .build() 可能在这里调用。
    // 但通常组件的构建在其自身内部完成。
  }
}
