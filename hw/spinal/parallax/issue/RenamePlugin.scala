package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rename._
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
import parallax.bpu.IssueBpuSignalService // 引入正确的 BPU 信号服务

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin with LockedImpl {

  val setup = create early new Area {
    // 1. 获取所需服务
    val issuePpl = getService[IssuePipeline]
    val bpuSignalService = getService[IssueBpuSignalService] // BPU信号服务

    // 2. 保留服务，防止它们过早完成
    issuePpl.retain()
    bpuSignalService.retain()

    // 3. 在 s1_rename 阶段声明所有需要的 Stageable
    val s1_rename = issuePpl.pipeline.s1_rename
    s1_rename(issuePpl.signals.DECODED_UOPS) // 输入
    s1_rename(issuePpl.signals.RENAMED_UOPS) // 输出
    s1_rename(issuePpl.signals.FLUSH_PIPELINE) // 输入 (用于冲刷)
    
    // 从 BPU 服务获取信号包并声明 Stageable
    val bpuSignals = bpuSignalService.getBpuSignals()
    s1_rename(bpuSignals.PREDICTION_INFO) // 输入
  }

  val logic = create late new Area {
    lock.await() // 等待所有依赖项就绪

    val issuePpl = setup.issuePpl
    val s1_rename = issuePpl.pipeline.s1_rename
    val issueSignals = issuePpl.signals
    val bpuSignals = setup.bpuSignals // 从 setup 区域获取信号包

    ParallaxLogger.log(s"RenamePlugin: logic LATE area entered for s1_rename")

    // --- 实例化核心组件 ---
    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val rat = new RenameMapTable(ratConfig)
    val freeList = new SuperScalarFreeList(flConfig)
    // TODO: 在未来，RAT 和 FreeList 可能是独立的服务插件，这里为了简化直接实例化。

    // --- 连接 RenameUnit, RAT, 和 FreeList ---
    renameUnit.io.ratReadPorts  <> rat.io.readPorts
    renameUnit.io.ratWritePorts <> rat.io.writePorts
    renameUnit.io.flAllocate    <> freeList.io.allocate
    renameUnit.io.numFreePhysRegs := freeList.io.numFreeRegs
    
    // TODO: FreeList 的 free 端口需要由 Commit 阶段驱动，这里暂时不连接
    freeList.io.free.foreach(_.enable := False)

    // TODO: RAT 和 FreeList 的 checkpoint/restore 端口需要由分支处理/异常恢复逻辑驱动
    rat.io.checkpointRestore.setIdle()
    freeList.io.restoreState.setIdle()

    // --- 将 RenameUnit 连接到流水线 s1_rename 阶段 ---

    // 1. 驱动 RenameUnit 的输入
    val decodedUopsFromStage = s1_rename(issueSignals.DECODED_UOPS)
    renameUnit.io.decodedUopsIn.valid   := s1_rename.isValid // 只有当 s1_rename 阶段有效时，才向 RenameUnit 发送有效请求
    renameUnit.io.decodedUopsIn.payload := decodedUopsFromStage
    
    // 2. 处理 RenameUnit 的输出和流水线暂停
    val renamedUopsFromRU = renameUnit.io.renamedUopsOut.payload
    val predictionInfoFromStage = s1_rename(bpuSignals.PREDICTION_INFO)

    // 组合逻辑：将预测信息附加到重命名后的Uop上
    val finalRenamedUops = Vec.fill(pipelineConfig.renameWidth)(RenamedUop(pipelineConfig))
    for(i <- 0 until pipelineConfig.renameWidth) {
      finalRenamedUops(i) := renamedUopsFromRU(i)
      // 只有有效的、且是分支/跳转的指令才需要附加预测信息
      when(renamedUopsFromRU(i).decoded.isValid && renamedUopsFromRU(i).decoded.isBranchOrJump) {
        finalRenamedUops(i).rename.branchPrediction := predictionInfoFromStage
      }
    }

    // 将最终结果驱动到 s1_rename 的输出 Stageable
    s1_rename(issueSignals.RENAMED_UOPS) := finalRenamedUops

    // 3. 控制流水线握手
    // 当 RenameUnit 不能接受新指令时 (例如资源不足)，暂停 s1_rename 阶段
    s1_rename.haltWhen(!renameUnit.io.decodedUopsIn.ready)
    
    // --- 冲刷逻辑 ---
    val doFlush = s1_rename(issueSignals.FLUSH_PIPELINE) || s1_rename.isFlushed
    renameUnit.io.flushIn := doFlush
    
    // 如果流水线被冲刷，RenameUnit 的输出将是无效的，这会自动阻止 s1_rename 向下游传递有效数据。
    
    // 释放服务
    setup.issuePpl.release()
    setup.bpuSignalService.release()

    ParallaxLogger.log(s"RenamePlugin: Logic elaborated.")
  }
}
