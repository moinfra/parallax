package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBService, FlushReason}
import parallax.components.rename._
import parallax.utilities.{Plugin, Service, ParallaxLogger}
import parallax.utilities.Formattable

/**
 * Commit statistics bundle for monitoring commit stage performance.
 */
case class CommitStats(pCfg: PipelineConfig) extends Bundle with Formattable {
  val committedThisCycle = UInt(log2Up(pCfg.commitWidth + 1) bits)
  val totalCommitted = UInt(32 bits)
  val robFlushCount = UInt(32 bits)
  val physRegRecycled = UInt(32 bits)

  def format: Seq[Any] = {
    Seq(
      L"CommitStats(",
      L"committedThisCycle=${committedThisCycle}, ",
      L"totalCommitted=${totalCommitted}, ",
      L"robFlushCount=${robFlushCount}, ",
      L"physRegRecycled=${physRegRecycled}",
      L")"
    )
  }
}

// 新增：用于每个提交槽位日志的Bundle
case class CommitSlotLog(pCfg: PipelineConfig) extends Bundle with Formattable {
  val valid = Bool() // ROB槽位是否有效
  val canCommit = Bool() // 这个槽位是否可以提交（有效且全局使能）
  val doCommit = Bool() // 这个槽位是否实际提交了
  val robPtr = UInt(pCfg.robPtrWidth)
  val oldPhysDest = UInt(pCfg.physGprIdxWidth) // 旧的物理目的寄存器
  val allocatesPhysDest = Bool() // 该指令是否分配了新的物理目的寄存器

  def format: Seq[Any] = {
    Seq(
      L"(valid=${valid}, canCommit=${canCommit}, doCommit=${doCommit}, robPtr=${robPtr}",
      L", oldPhysDest=p${oldPhysDest}, allocatesPhysDest=${allocatesPhysDest})"
    )
  }
}

/**
 * Service interface for commit stage operations.
 * Provides control over commit enable/disable and commit status information.
 */
trait CommitService extends Service {
  /**
   * Enable or disable commit operations.
   * When disabled, no instructions will be committed from ROB.
   */
  def setCommitEnable(enable: Bool): Unit
  
  /**
   * Get current commit enable status.
   */
  def getCommitEnable(): Bool
  
  /**
   * Get commit progress information for debugging/monitoring.
   */
  def getCommitStats(): CommitStats
}
/**
 * CommitPlugin implements the commit stage of the out-of-order processor.
 * 
 * Key responsibilities:
 * 1. In-order commit from ROB head.
 * 2. Physical register recycling to FreeList.
 * 3. Branch misprediction recovery via checkpoints (triggers restore via CheckpointManagerService).
 * 4. Exception handling coordination (triggers restore/flush via CheckpointManagerService/ROB).
 * 5. Commit width and ordering management.
 * 
 * Architecture Integration:
 * - Consumes from: ROB commit slots.
 * - Produces to: FreeList recycling.
 * - Coordinates with: ROB (for commit and flush signals), CheckpointManager (for state restore).
 */
class CommitPlugin(
    val pipelineConfig: PipelineConfig
) extends Plugin with CommitService {
  
  val enableLog = true // 控制是否启用周期性详细日志
  
  // Service interface state
  private val commitEnable = Bool() 
  private val commitStats = Reg(CommitStats(pipelineConfig)).initZero()
  
  override def setCommitEnable(enable: Bool): Unit = {
    commitEnable := enable
  }
  
  override def getCommitEnable(): Bool = commitEnable
  
  private var forwardedStats: CommitStats = null

  override def getCommitStats(): CommitStats = {
    // 确保这个方法在 'logic' Area 执行之后被调用 (这是 Framework 的默认行为)
    assert(forwardedStats != null, "forwardedStats has not been initialized. getCommitStats() called too early.")
    forwardedStats
  }
  
  
  val early_setup = create early new Area {
    val robService = getService[ROBService[RenamedUop]]
    val ratControlService = getService[RatControlService]
    val flControlService = getService[FreeListControlService]
    val checkpointManagerService = getService[CheckpointManagerService] 
    
    // 静态日志，只在插件初始化时打印一次
    ParallaxLogger.log(s"[CommitPlugin] Early setup - acquired services")
  }

  val logic = create late new Area {
    val robService = early_setup.robService
    val ratControlService = early_setup.ratControlService
    val flControlService = early_setup.flControlService
    val checkpointManagerService = early_setup.checkpointManagerService 

    // === ROB Interface ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth)
    val robFlushPort = robService.getFlushPort()

    // === Physical Register Recycling ===
    val freePorts = flControlService.getFreePorts()
    require(freePorts.length >= pipelineConfig.commitWidth, 
      s"FreeList must have at least ${pipelineConfig.commitWidth} free ports for commit width")
    
    // === Checkpoint Management Interface ===
    val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()

    // === Commit Logic ===
    val commitCount = UInt(log2Up(pipelineConfig.commitWidth + 1) bits) 

    val canCommitVec = Vec(Bool(), pipelineConfig.commitWidth)
    for (i <- 0 until pipelineConfig.commitWidth) {
      canCommitVec(i) := commitSlots(i).valid && commitEnable && commitSlots(i).entry.status.done 
    }
    
    val commitMask = Vec(Bool(), pipelineConfig.commitWidth)
    if (pipelineConfig.commitWidth > 0) { 
        commitMask(0) := canCommitVec(0)
        for (i <- 1 until pipelineConfig.commitWidth) {
            commitMask(i) := canCommitVec(i) && commitMask(i-1)
        }
    } else {
        commitMask.clearAll()
    }

    // 新增：收集每个槽位的详细日志信息
    val commitSlotLogs = Vec(CommitSlotLog(pipelineConfig), pipelineConfig.commitWidth)

    for (i <- 0 until pipelineConfig.commitWidth) {
      val doCommit = commitMask(i) // Let ROB handle flush blocking via canCommitFlags 
      val committedEntry = commitSlots(i).entry 
      val committedUop = committedEntry.payload.uop 

      // 驱动 FreeList free port
      freePorts(i).enable := doCommit && committedUop.rename.allocatesPhysDest 
      freePorts(i).physReg := Mux(doCommit && committedUop.rename.allocatesPhysDest, 
                                 committedUop.rename.oldPhysDest.idx, 
                                 U(0, pipelineConfig.physGprIdxWidth)) 

      // 驱动 commit acknowledgments to ROB
      commitAcks(i) := doCommit

      // 填充日志信息
      commitSlotLogs(i).valid := commitSlots(i).valid
      commitSlotLogs(i).canCommit := canCommitVec(i)
      commitSlotLogs(i).doCommit := doCommit
      commitSlotLogs(i).robPtr := committedUop.robPtr
      commitSlotLogs(i).oldPhysDest := committedUop.rename.oldPhysDest.idx
      commitSlotLogs(i).allocatesPhysDest := committedUop.rename.allocatesPhysDest
    }
    
    commitCount := CountOne(commitMask)
    
    // === Pipeline Flush and Checkpoint Restore Logic ===
  // --- 计算本周期的增量值 ---
    val committedThisCycle_comb = CountOne(commitAcks)
    val recycledThisCycle_comb = CountOne(freePorts.map(_.enable))
    val flushedThisCycle_comb = robFlushPort.valid.asUInt
    
    // --- 更新内部寄存器状态 (这部分逻辑不变) ---
    commitStats.committedThisCycle := committedThisCycle_comb
    commitStats.totalCommitted     := commitStats.totalCommitted + committedThisCycle_comb
    commitStats.physRegRecycled    := commitStats.physRegRecycled + recycledThisCycle_comb
    commitStats.robFlushCount      := commitStats.robFlushCount + flushedThisCycle_comb
    
    restoreCheckpointTrigger := robFlushPort.valid

    // ==============================================================================
    // 核心修改 2: 创建前馈统计信号
    // ==============================================================================
    val fwd = CommitStats(pipelineConfig)
    
    // committedThisCycle 本身就是组合逻辑，直接赋值
    fwd.committedThisCycle := committedThisCycle_comb
    
    // totalCommitted 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.totalCommitted := commitStats.totalCommitted + committedThisCycle_comb
    
    // physRegRecycled 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.physRegRecycled := commitStats.physRegRecycled + recycledThisCycle_comb
    
    // robFlushCount 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.robFlushCount := commitStats.robFlushCount + flushedThisCycle_comb

    // 将这个新的、带有前馈逻辑的 Bundle 赋值给插件的成员变量
    forwardedStats = fwd
    // === 集中式周期性日志打印 ===
    val cycleLogger = new Area {
      if(enableLog) {
        
        report(
          L"[COMMIT] Cycle Log: commitEnable=${commitEnable}, commitCount=${commitCount}, " :+
          L"ROB_Flush_Valid=${robFlushPort.valid}, ROB_Flush_Reason=${robFlushPort.payload.reason.asBits}, " :+
          L"Restore_Checkpoint_Trigger=${restoreCheckpointTrigger}, " :+
          L"Stats=${commitStats.format}\n" :+
          L"  Slot Details: ${commitSlotLogs.map(s => L"\n    Slot: ${s.format} commitAck=${commitAcks(0)}")}" // 为每个槽位格式化输出，每个槽位独占一行
        )
      }
    }
  }
}
