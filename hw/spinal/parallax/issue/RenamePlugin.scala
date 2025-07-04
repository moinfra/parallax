// filename: src/main/scala/parallax/issue/RenamePlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob.ROBService
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin 
    with LockedImpl
    with RatControlService
    with FreeListControlService {

  // --- 实例化硬件组件和获取服务 (在 'early' 区域) ---
  val early_setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val robService = getService[ROBService[RenamedUop]]
    issuePpl.retain()
    robService.retain()

    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val rat = new RenameMapTable(ratConfig)
    val freeList = new SuperScalarFreeList(flConfig)
    
    val s1_rename = issuePpl.pipeline.s1_rename
    s1_rename(issuePpl.signals.DECODED_UOPS)
    s1_rename(issuePpl.signals.RENAMED_UOPS)
    s1_rename(issuePpl.signals.FLUSH_PIPELINE)
  }

  // --- 服务接口的实现 ---
  override def newCheckpointSavePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointSave
  override def newCheckpointRestorePort(): Stream[RatCheckpoint] = early_setup.rat.io.checkpointRestore
  override def getFreePorts(): Vec[SuperScalarFreeListFreePort] = early_setup.freeList.io.free
  override def newRestorePort(): Stream[SuperScalarFreeListCheckpoint] = early_setup.freeList.io.restoreState

  // --- 连接逻辑 (在 'late' 区域) ---
  val logic = create late new Area {
    lock.await()
    val issuePpl = early_setup.issuePpl
    val s1_rename = early_setup.s1_rename
    val issueSignals = issuePpl.signals
    val robService = early_setup.robService
    
    val renameUnit = early_setup.renameUnit
    val rat = early_setup.rat
    val freeList = early_setup.freeList

    // --- 1. 连接 RenameUnit 的内部依赖 ---
    renameUnit.io.ratReadPorts  <> rat.io.readPorts
    renameUnit.io.ratWritePorts <> rat.io.writePorts
    renameUnit.io.flAllocate    <> freeList.io.allocate
    renameUnit.io.numFreePhysRegs := freeList.io.numFreeRegs
    
    // --- 2. 连接流水线与 RenameUnit / ROB (并行) ---
    val decodedUopsIn = s1_rename(issueSignals.DECODED_UOPS)
    val decodedUop = decodedUopsIn(0) // width = 1

    // a) 驱动 RenameUnit 的输入
    renameUnit.io.decodedUopsIn.valid   := s1_rename.isValid // RenameUnit 只在 s1 有效时才工作
    renameUnit.io.decodedUopsIn.payload := decodedUopsIn
    
    // b) 并行地向 ROB 请求分配
    val robAllocPort = robService.getAllocatePorts(pipelineConfig.renameWidth)(0)
    robAllocPort.valid := s1_rename.isValid && decodedUop.isValid // 只有有效的uop才请求分配
    robAllocPort.pcIn  := decodedUop.pc
    
    val tempUopForRob = RenamedUop(pipelineConfig)
    tempUopForRob.setDefault(decoded = decodedUop)
    robAllocPort.uopIn := tempUopForRob
    
    // -- MODIFICATION START: Correct assignment and pipeline control --

    // 3. 组合地构建最终的重命名 UOP
    // 这是解决 ASSIGNMENT OVERLAP 的最稳健方法
    val finalRenamedUop = RenamedUop(pipelineConfig)
    finalRenamedUop.decoded      := renameUnit.io.renamedUopsOut.payload(0).decoded
    finalRenamedUop.rename       := renameUnit.io.renamedUopsOut.payload(0).rename
    finalRenamedUop.uniqueId     := renameUnit.io.renamedUopsOut.payload(0).uniqueId
    finalRenamedUop.dispatched   := renameUnit.io.renamedUopsOut.payload(0).dispatched
    finalRenamedUop.executed     := renameUnit.io.renamedUopsOut.payload(0).executed
    finalRenamedUop.hasException := renameUnit.io.renamedUopsOut.payload(0).hasException
    finalRenamedUop.exceptionCode:= renameUnit.io.renamedUopsOut.payload(0).exceptionCode
    finalRenamedUop.robPtr       := robAllocPort.robPtr // 从 ROB 获取 robPtr

    // 将最终构建好的 uop 驱动到 Stageable
    s1_rename(issueSignals.RENAMED_UOPS)(0) := finalRenamedUop

    // 4. 处理握手和流水线控制 (无环路方式)
    
    // RenameUnit 的输出端口的 ready 信号应该由下游的 ready (s1_rename.isReady) 决定。
    // 这是正确的反压传递。
    renameUnit.io.renamedUopsOut.ready := s1_rename.isReady
    
    // Rename 阶段暂停的条件是：
    // 1. RenameUnit 自身由于资源不足而无法接收新的输入。
    // 2. 或者，ROB 无法分配新的条目。
    // 这两个条件都来自于“上游”或“同级”的模块，不依赖于下游的 `s1_rename.isReady`。
    val renameUnitNotReady = !renameUnit.io.decodedUopsIn.ready
    val robNotReady = !robAllocPort.ready
    
    // haltIt 会自动处理 s1_rename.isValid 的条件。
    // 当 haltIt(cond) 的 cond 为真时，它会强制 s1_rename.input.ready (即 s1_rename.isReady) 为 False。
    // 因为 renameUnitNotReady 和 robNotReady 不依赖于 s1_rename.isReady，所以环路被打破。
    s1_rename.haltWhen(renameUnitNotReady || robNotReady)

    // -- MODIFICATION END --
    
    // 5. 冲刷逻辑
    val doFlush = s1_rename(issueSignals.FLUSH_PIPELINE) || s1_rename.isFlushed
    renameUnit.io.flushIn := doFlush
    
    // 6. 释放服务锁定
    issuePpl.release()
    robService.release()
  }
}
