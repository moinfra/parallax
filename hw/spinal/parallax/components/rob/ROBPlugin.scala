package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.{Framework, Plugin, ParallaxLogger, ParallaxSim} // 导入 Plugin
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Formattable

class ROBPlugin[RU <: Data with Formattable with HasRobPtr](
    val pipelineConfig: PipelineConfig, // 用于派生 ROBConfig
    val uopType: HardType[RU], // 显式传递 Uop 类型
    val defaultUop: () => RU // 显式传递 Uop 默认值构造函数
) extends Plugin
    with ROBService[RU] {
      val enableLog = false
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

  // 用于存储每个写回端口的申请者信息 (EU名称, 端口实例)
  private val writebackPortInfo = ArrayBuffer[(String, ROBWritebackPort[RU])]()

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
    robComponent.io.allocate
  }

  override def getCanAllocateVec(width: Int): Vec[Bool] = {
    if (width != robConfig.allocateWidth) {
      SpinalError(
        s"ROBPlugin: Requested canAllocate width ($width) does not match ROB config allocateWidth (${robConfig.allocateWidth})"
      )
    }
    robComponent.io.canAllocate
  }

  // 写回阶段
  override def newWritebackPort(euName: String = ""): (ROBWritebackPort[RU]) = {
    this.framework.requireEarly()
    if (nextWbPortIdx >= robConfig.numWritebackPorts) {
      assert(
        false,
        s"ROBPlugin: No more physical writeback ports available. Increase numWritebackPorts in ROBConfig or request fewer EUs (robConfig.numWritebackPorts=${robConfig.numWritebackPorts})."
      )
      val dummyPort = slave(ROBWritebackPort(robConfig))
      dummyPort.setDefault()
      dummyPort
    } else {
      val physicalPortMaster = robComponent.io.writeback(nextWbPortIdx)
      nextWbPortIdx += 1

      // 记录端口和申请者名字
      writebackPortInfo += ((euName, physicalPortMaster))

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
    val flushPort = Flow(ROBFlushPayload(robConfig.robPtrWidth))
    flushPortsList += flushPort
    ParallaxLogger.log(
      s"ROBPlugin: Created flush port ${flushPortsList.size}. Total flush ports: ${flushPortsList.size}"
    )
    flushPort
  }

  override def doRobFlush(): (Flow[ROBFlushPayload]) = {
    aggregatedFlushSignal
  }

  val logic = create late new Area {
    lock.await()
    ParallaxLogger.log(s"ROBPlugin logic area entered. ROB component IO is now connected via service methods.")

    // 聚合所有 flush 端口的信号
    if (flushPortsList.nonEmpty) {
      val aggregatedValid = flushPortsList.map(_.valid).reduceBalancedTree(_ || _)
      aggregatedFlushSignal.valid := aggregatedValid

      val aggregatedPayload = ROBFlushPayload(robConfig.robPtrWidth)
      aggregatedPayload.assignDontCare()

      // 优先级选择逻辑：后面的端口优先级更高（覆盖前面的）
      for (flushPort <- flushPortsList.reverse) {
        when(flushPort.valid) {
          aggregatedPayload := flushPort.payload
        }
      }
      aggregatedFlushSignal.payload := aggregatedPayload

      ParallaxLogger.log(s"ROBPlugin: Aggregated ${flushPortsList.size} flush ports with OR logic")

      when(aggregatedFlushSignal.valid) {
        if(enableLog) report(L"[ROBPlugin] Aggregated flush signal is valid! Total ports: ${flushPortsList.size}")
      }
      for (i <- flushPortsList.indices) {
        when(flushPortsList(i).valid) {
          if(enableLog) report(L"[ROBPlugin] Flush port ${i} is valid (reason=${flushPortsList(i).payload.reason})")
        }
      }
    } else {
      aggregatedFlushSignal.valid := False
      aggregatedFlushSignal.payload.assignDontCare()
      ParallaxLogger.log(s"ROBPlugin: No flush ports created, keeping aggregated signal invalid")
    }

    // 将聚合信号连接到 ROB 组件
    robComponent.io.flush.valid := aggregatedFlushSignal.valid
    robComponent.io.flush.payload := aggregatedFlushSignal.payload

    when(robComponent.io.flush.valid) {
      if(enableLog) report(L"[ROBPlugin] ROB component flush input is valid!")
    }

    // 并行监控所有写回端口，用于调试
    for ((euName, wbPort) <- writebackPortInfo) {
      when(wbPort.fire) {
        // 每当有任何一个写回端口触发fire，就打印其来源和内容
        if(enableLog) report(
          L"[ROBPlugin] Writeback detected!" :+
            L" Source EU Name: '$euName'" :+
            L" robPtr: ${wbPort.robPtr}" :+
            L" Exception: ${wbPort.exceptionOccurred}" :+
            L" Result: ${wbPort.result}"
        )
      }
    }
  }
}
