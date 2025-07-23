package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBService, FlushReason}
import parallax.components.rename._
import parallax.utilities.{Plugin, Service, ParallaxSim}
import parallax.utilities.Formattable
import parallax.utilities.ParallaxSim
import parallax.fetch2.FetchService

/**
 * Commit statistics bundle for monitoring commit stage performance.
 */
case class CommitStats(pCfg: PipelineConfig = null) extends Bundle with Formattable {
  private val cw = if (pCfg == null) 1 else pCfg.commitWidth
  val committedThisCycle  = UInt(log2Up(cw + 1) bits)
  val totalCommitted      = UInt(32 bits)
  val robFlushCount       = UInt(32 bits)
  val physRegRecycled     = UInt(32 bits)
  val commitOOB           = Bool()  // Out-of-bounds commit detected
  val maxCommitPc         = UInt(32 bits)  // Maximum PC committed so far

  def format: Seq[Any] = {
    Seq(
      L"CommitStats(",
      L"committedThisCycle=${committedThisCycle}, ",
      L"totalCommitted=${totalCommitted}, ",
      L"robFlushCount=${robFlushCount}, ",
      L"physRegRecycled=${physRegRecycled}, ",
      L"commitOOB=${commitOOB}, ",
      L"maxCommitPc=0x${maxCommitPc}",
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
      L", oldPhysDest=${oldPhysDest}, allocatesPhysDest=${allocatesPhysDest})"
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
   * Set maximum allowed PC for commit operations.
   * When enabled, commits beyond this PC will trigger commitOOB signal.
   */
  def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    throw new RuntimeException("not implemented")
  }
  
  /**
   * Get commit progress information for debugging/monitoring.
   */
    def getCommitStatsReg(): CommitStats = {
    throw new RuntimeException("not implemented")
  }
  /**
   * Check if processor is currently idle (stopped by IDLE instruction).
   */
  // def isIdle(): Bool
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
  assert(pipelineConfig.commitWidth == 1)
  
  val enableLog = true // 控制是否启用周期性详细日志
  
  // Service interface state
  private val commitEnableExt = Bool()
  private val commitStatsReg = Reg(CommitStats(pipelineConfig)).initZero()
  private val maxCommitPcExt = UInt(pipelineConfig.pcWidth)
  private val maxCommitPcEnabledExt = Bool()
  
  // PC tracking state
  private val maxCommitPcReg = Reg(UInt(pipelineConfig.pcWidth)) init(0) addAttribute("mark_debug", "true")
  private val commitOOBReg = Reg(Bool()) init(False) addAttribute("mark_debug", "true")
  
  override def setCommitEnable(enable: Bool): Unit = {
    commitEnableExt := enable
  }
  
  override def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    maxCommitPcExt := maxPc
    maxCommitPcEnabledExt := enabled
  }
  
  private var forwardedStats: CommitStats = null
  
  override def getCommitStatsReg(): CommitStats = {
    commitStatsReg
  }
  
  val hw = create early new Area {
    val robService               = getService[ROBService[RenamedUop]]
    val ratControlService        = getService[RatControlService]
    val flControlService         = getService[FreeListControlService]
    val checkpointManagerService = getService[CheckpointManagerService] 
    val fetchService             = getService[FetchService]
    
    // +++ NEW +++
    // 在 early 阶段获取 BusyTableService
    val busyTableService         = getService[BusyTableService]
    val busyTableClearPort       = busyTableService.newClearPort()

    val robFlushPort             = robService.newRobFlushPort()
    val redirectPort             = fetchService.newHardRedirectPort(priority = 10)  // High priority for branch misprediction
    
    redirectPort.setIdle()

    println(L"[CommitPlugin] Early setup - acquired services and fetch disable port")
  }

  val logic = create late new Area {
    val robService = hw.robService
    val ratControlService = hw.ratControlService
    val flControlService = hw.flControlService
    val checkpointManagerService = hw.checkpointManagerService 
    
    // === ROB Interface ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth) // 本插件会发送 commit 用于真正触发提交
    val robFlushPort = hw.robFlushPort
    robFlushPort.valid := False
    robFlushPort.payload.assignDontCare()

    // === Physical Register Recycling ===
    val freePorts = flControlService.getFreePorts()
    require(freePorts.length >= pipelineConfig.commitWidth, 
      s"FreeList must have at least ${pipelineConfig.commitWidth} free ports for commit width")
    
    // --- NEW ---
    // === BusyTable Clearing ===
    // 从服务获取一个清除端口
    // 默认情况下不清除
    val busyTableClearPort = hw.busyTableClearPort
    busyTableClearPort.valid := False
    busyTableClearPort.payload.assignDontCare()

    // === Checkpoint Management Interface ===
    val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()

    // === Commit Logic ===
    val commitCount = UInt(log2Up(pipelineConfig.commitWidth + 1) bits) 

    // ====================================================================
    // S0 阶段：组合逻辑阶段，处理需要立即响应的外部交互
    // ====================================================================
    val s0 = new Area {
      // --- 从ROB获取原始、实时的提交槽位信息 ---
      val rawCommitSlots = commitSlots // 这就是当前的commitSlots
      val enable = commitEnableExt
      val headSlot = rawCommitSlots(0)
      val headUop = headSlot.entry.payload.uop
      val headIsBranch = headUop.decoded.isBranchOrJump
      val isMispredictedBranch = enable && headSlot.canCommit && headIsBranch && headSlot.entry.status.isMispredictedBranch
      val isMispredictedBranchPrev = RegNext(isMispredictedBranch) init(False)
      val actualTargetOfBranchPrev = RegNext(headSlot.entry.status.result)
      // val isSafeToCommit = !hasInflightUnResolvedBranches || headIsBranch

      val commitAckMasks = Vec(Bool(), pipelineConfig.commitWidth)
      commitAckMasks(0) := False
      val commitIdleThisCycle = headUop.decoded.uopCode === BaseUopCode.IDLE && commitAckMasks(0)

      when(enable && headSlot.canCommit) {
        val commitAckMask = True
        commitAckMasks(0) := commitAckMask
        // 只要提交了指令就立刻一键备份Rat/FreeList/BusyTable
        val ckptSave = new Area {
            val ckptService = getService[CheckpointManagerService]
            val trigger = ckptService.getSaveCheckpointTrigger()
            trigger.addAttribute("MARK_DEBUG","TRUE")
            trigger := True
            report(L"CHECKPOINT: Save checkpoint triggered")
        }
      
      }
      // 在分支指令提交之后回滚
      when(isMispredictedBranchPrev) {
        // 1. 让 ROB 回滚到当前分支指令的 robPtr 位置
        // （当前分支指令以及其后所有指令需要清除，实际上相当于全部清空）
        var robRestore = new Area {
          val robFlushPort = hw.robFlushPort
          robFlushPort.valid := True
          robFlushPort.payload.reason := FlushReason.FULL_FLUSH
          hw.robFlushPort.payload.targetRobPtr.assignDontCare()
        }
        // 2. 触发 CheckpointManager 状态恢复，触发 HardRedirect 事件
        val ckptRestore = new Area {
            val checkpointManagerService = hw.checkpointManagerService 
            val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()
            restoreCheckpointTrigger := True
        }
        // 3. 前端 IF 全部清空并从正确地址取指
        val redirect = new Area {
          val redirectPort = hw.redirectPort
          redirectPort.valid := True
          redirectPort.payload := actualTargetOfBranchPrev.asUInt
        }
        // 4. 发射流水线清空 这个 ROB 监听者自己会搞定
        // 5. 发射队列清空 这个 ROB 监听者自己会搞定
        // 6. 执行单元无效化 这个 ROB 监听者自己会搞定
        // 7. LSQ 无效化 这个 ROB 监听者自己会搞定
        report(L"CHECKPOINT: Restore checkpoint triggered due to misprediction last cycle, redirecting to ${actualTargetOfBranchPrev}")
      } 

      // --- NEW: 将 BusyTable 清除逻辑与 FreeList 回收逻辑放在一起 ---
      // 1. 物理寄存器回收 (立即响应)
      for (i <- 0 until pipelineConfig.commitWidth) {
        val commitAck = commitAckMasks(i)
        val committedEntry = rawCommitSlots(i).entry 
        val committedUop = committedEntry.payload.uop 
        
        // 当一条指令提交，并且它分配了一个新的物理寄存器时...
        val canRecycle = commitAck && committedUop.rename.allocatesPhysDest 
        
        // a) ...它所覆盖的旧的物理寄存器 (`oldPhysDest`) 可以被回收
        
        // 回收到 FreeList (使其可用于未来的分配)
        freePorts(i).enable := canRecycle
        freePorts(i).physReg := committedUop.rename.oldPhysDest.idx
        
        // b) ...同时，也从 BusyTable 中清除，标记为不再繁忙
        when(canRecycle) {
          busyTableClearPort.valid   := True
          busyTableClearPort.payload := committedUop.rename.oldPhysDest.idx
          
          // 添加日志以确认清除操作
          ParallaxSim.log(
            L"[CommitPlugin->BusyTable] Clearing physReg=${committedUop.rename.oldPhysDest.idx} " :+
            L"because robPtr=${committedUop.robPtr} is committing."
          )
        }
      }

      for (i <- 0 until pipelineConfig.commitWidth) {
        commitAcks(i) := commitAckMasks(i)
      }
      
      // 3. 组合逻辑统计接口 (立即响应)
      val committedThisCycle_comb = CountOne(commitAckMasks)
      val recycledThisCycle_comb = CountOne(freePorts.map(_.enable))
      val flushedThisCycle_comb = robFlushPort.valid.asUInt
      
      // === PC Tracking and OOB Detection ===
      val commitPcs = Vec(UInt(pipelineConfig.pcWidth), pipelineConfig.commitWidth)
      val anyCommitOOB = Bool()
      val maxCommitPcThisCycle = UInt(pipelineConfig.pcWidth)
      
      // Collect PCs from all committed instructions
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitPcs(i) := rawCommitSlots(i).entry.payload.uop.decoded.pc
      }
      
      // Find maximum PC among committed instructions this cycle
      maxCommitPcThisCycle := commitPcs.zip(commitAckMasks).map { case (pc, ack) =>
        Mux(ack, pc, U(0, pipelineConfig.pcWidth))
      }.reduce((a, b) => Mux(a > b, a, b))
      
      // Check for out-of-bounds commits
      anyCommitOOB := maxCommitPcEnabledExt && commitAckMasks.zip(commitPcs).map { case (ack, pc) =>
        ack && (pc > maxCommitPcExt)
      }.reduce(_ || _)
      
      val hasCommitsThisCycle = commitAckMasks.reduce(_ || _)
      
      // 4. 前馈统计信号 (立即响应)
      val fwd = CommitStats(pipelineConfig)
      fwd.committedThisCycle := committedThisCycle_comb
      fwd.totalCommitted := commitStatsReg.totalCommitted + committedThisCycle_comb
      fwd.physRegRecycled := commitStatsReg.physRegRecycled + recycledThisCycle_comb
      fwd.robFlushCount := commitStatsReg.robFlushCount + flushedThisCycle_comb
      fwd.commitOOB := commitOOBReg || anyCommitOOB
      
      // Maximum PC: use current cycle's maximum or previous register value
      fwd.maxCommitPc := Mux(hasCommitsThisCycle && (maxCommitPcThisCycle > maxCommitPcReg), 
                            maxCommitPcThisCycle, 
                            maxCommitPcReg)
      
      forwardedStats = fwd
      
      val commitSlotLogs = Vec(CommitSlotLog(pipelineConfig), pipelineConfig.commitWidth)
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitSlotLogs(i).valid := rawCommitSlots(i).valid
        commitSlotLogs(i).canCommit := rawCommitSlots(i).canCommit
        commitSlotLogs(i).doCommit := commitAckMasks(i)
        commitSlotLogs(i).robPtr := rawCommitSlots(i).entry.payload.uop.robPtr
        commitSlotLogs(i).oldPhysDest := rawCommitSlots(i).entry.payload.uop.rename.oldPhysDest.idx
        commitSlotLogs(i).allocatesPhysDest := rawCommitSlots(i).entry.payload.uop.rename.allocatesPhysDest
      }
      
      commitCount := committedThisCycle_comb
    }
    
    // ====================================================================
    // S1 阶段：寄存器更新阶段，处理可以延迟的内部状态更新
    // ====================================================================
    val s1 = new Area {
      // 我们只需要注册那些需要在S1中用来更新持久状态（寄存器）的信号
      val s1_commitIdleThisCycle = RegNext(s0.commitIdleThisCycle, init = False)
      val s1_headUop = RegNext(s0.headUop)
      val s1_hasCommitsThisCycle = RegNext(s0.hasCommitsThisCycle, init = False)
      val s1_maxCommitPcThisCycle = RegNext(s0.maxCommitPcThisCycle, init = U(0, pipelineConfig.pcWidth))
      val s1_anyCommitOOB = RegNext(s0.anyCommitOOB, init = False)
      val s1_committedThisCycle_comb = RegNext(s0.committedThisCycle_comb, init = U(0, log2Up(pipelineConfig.commitWidth + 1) bits))
      val s1_recycledThisCycle_comb = RegNext(s0.recycledThisCycle_comb, init = U(0, log2Up(pipelineConfig.commitWidth + 1) bits))
      val s1_flushedThisCycle_comb = RegNext(s0.flushedThisCycle_comb, init = U(0, 1 bits))
      
      when(s1_hasCommitsThisCycle) {
        when(s1_maxCommitPcThisCycle > maxCommitPcReg) {
          maxCommitPcReg := s1_maxCommitPcThisCycle
        }
      }
      
      // Set OOB flag if any commit is out of bounds
      when(s1_anyCommitOOB) {
        commitOOBReg := True
        ParallaxSim.log(L"[CommitPlugin] CRITICAL: Out-of-bounds commit detected! PC=0x${s1_maxCommitPcThisCycle}, maxAllowed=0x${maxCommitPcExt}")
      }
      
      // === 更新持久的统计寄存器 ===
      commitStatsReg.committedThisCycle := s1_committedThisCycle_comb
      commitStatsReg.totalCommitted     := commitStatsReg.totalCommitted + s1_committedThisCycle_comb
      commitStatsReg.physRegRecycled    := commitStatsReg.physRegRecycled + s1_recycledThisCycle_comb
      commitStatsReg.robFlushCount      := commitStatsReg.robFlushCount + s1_flushedThisCycle_comb
      commitStatsReg.commitOOB          := commitOOBReg
      commitStatsReg.maxCommitPc        := maxCommitPcReg
    }
    
    // restoreCheckpointTrigger is already set above in the IDLE logic
    getServiceOption[DebugDisplayService].foreach(dbg => { 
      dbg.setDebugValueOnce(s0.committedThisCycle_comb.asBool, DebugValue.COMMIT_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(commitOOBReg, DebugValue.COMMIT_OOB_ERROR, expectIncr = false)
    })
    val counter = Reg(UInt(32 bits)) init(0)
    counter := counter + 1
    // === 集中式周期性日志打印 ===
      if(enableLog) {
        
        report(
          L"[COMMIT] Cycle ${counter} Log: " :+
          L"Stats=${commitStatsReg.format}\n" :+
          L"  Slot Details: ${s0.commitSlotLogs.map(s => L"\n    Slot: ${s.format} commitAck=${commitAcks(0)} commitPc=0x${s0.commitPcs(0)}")}" // 为每个槽位格式化输出，每个槽位独占一行
        )
      } else {
        // 只打印首次 valid=1 和 doCommit = 1
        val prevValid = RegNext(commitSlots(0).valid, init = False)
        when(!prevValid && commitSlots(0).valid) {
          report(
            L"[COMMIT] Cycle ${counter} Log: " :+
            L"Stats=${commitStatsReg.format}\n" :+
            L"  Slot Details: ${s0.commitSlotLogs(0).format} commitAck=${commitAcks(0)} commitPc=0x${s0.commitPcs(0)}" // 只打印第一个槽位
          )
        } elsewhen(commitAcks(0)) {
          report(
            L"[COMMIT] Cycle ${counter} Log: " :+
            L"Stats=${commitStatsReg.format}\n" :+
            L"  Slot Details: ${s0.commitSlotLogs(0).format} commitAck=${commitAcks(0)} commitPc=0x${s0.commitPcs(0)}" // 只打印第一个槽位
          )
        }
      }
  }
}
