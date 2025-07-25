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
import parallax.bpu.BpuService

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
      L", oldPhysDest=${oldPhysDest}, allocPhysDest=${allocatesPhysDest})"
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
  assert(pipelineConfig.commitWidth == 1, "This revised logic currently supports commitWidth=1 only.")
  
  val enableLog = true // 控制是否启用周期性详细日志
  
  // Service interface state (这部分保持不变)
  private val commitEnableExt = Bool()
  private val commitStatsReg = Reg(CommitStats(pipelineConfig)).initZero()
  private val maxCommitPcExt = UInt(pipelineConfig.pcWidth)
  private val maxCommitPcEnabledExt = Bool()
  
  private val maxCommitPcReg = Reg(UInt(pipelineConfig.pcWidth)) init(0) addAttribute("mark_debug", "true")
  private val commitOOBReg = Reg(Bool()) init(False) addAttribute("mark_debug", "true")
  
  override def setCommitEnable(enable: Bool): Unit = {
    commitEnableExt := enable
  }
  
  override def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    maxCommitPcExt := maxPc
    maxCommitPcEnabledExt := enabled
  }
  
  override def getCommitStatsReg(): CommitStats = {
    commitStatsReg
  }
  
  val hw = create early new Area { // 'hw' 区域保持不变
    val robService               = getService[ROBService[RenamedUop]]
    val ratControlService        = getService[RatControlService]
    val flControlService         = getService[FreeListControlService]
    val checkpointManagerService = getService[CheckpointManagerService] 
    val fetchService             = getService[FetchService]
    val busyTableService         = getService[BusyTableService]
    val bpuServiceInst           = getService[BpuService] // 获取BPUService实例
    
    // val busyTableClearPort       = busyTableService.newClearPort()
    val robFlushPort             = robService.newRobFlushPort()
    val redirectPort             = fetchService.newHardRedirectPort(priority = 10)
    val bpuUpdatePort            = bpuServiceInst.newBpuUpdatePort() // 创建BPU更新端口
    val ratCommitUpdatePort      = ratControlService.getCommitPort() // 获取Commit更新端口

    redirectPort.setIdle()
    bpuUpdatePort.valid := False // 默认不更新BPU
    bpuUpdatePort.payload.assignDontCare()
    
    ratCommitUpdatePort.wen := False // 默认不更新ARAT
    ratCommitUpdatePort.archReg.assignDontCare()
    ratCommitUpdatePort.physReg.assignDontCare()
  }

  // =========================================================================
  //  !!!  L O G I C   A R E A   S T A R T S   H E R E  !!!
  //  这是主要修改区域
  // =========================================================================
  val logic = create late new Area {
    val robService = hw.robService
    val flControlService = hw.flControlService
    val checkpointManagerService = hw.checkpointManagerService 
    
    // === 接口和端口设置 (保持不变) ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth)
    val robFlushPort = hw.robFlushPort
    val freePorts = flControlService.getFreePorts()
    // // val busyTableClearPort = hw.busyTableClearPort
    val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()
    val bpuUpdatePort = hw.bpuUpdatePort // 引用BPU更新端口
    val ratCommitUpdatePort = hw.ratCommitUpdatePort

    // 默认值设置
    robFlushPort.valid := False
    robFlushPort.payload.assignDontCare()
    // busyTableClearPort.valid := False
    // busyTableClearPort.payload.assignDontCare()
    // restoreCheckpointTrigger := False
    
    // ====================================================================
    // S0 阶段：组合逻辑 - 核心修复在这里
    // ====================================================================
    val s0 = new Area {
      val enable = commitEnableExt
      
      // --- 从ROB获取头部槽位信息 ---
      val headSlot = commitSlots(0)
      val headUop = headSlot.entry.payload.uop
      val headIsBranch = headUop.decoded.isBranchOrJump
      
      // --- 关键判断：在当前周期检测预测失败 ---
      val isMispredictedBranch = 
        enable && 
        headSlot.valid &&                         // 槽位必须有效
        headSlot.entry.status.done &&        // 指令必须已完成执行
        headIsBranch &&                           // 必须是分支
        headSlot.entry.status.isMispredictedBranch // 且状态是预测失败
      
      // --- 提交决策逻辑 ---
      val commitAckMasks = Vec(Bool(), pipelineConfig.commitWidth)
      
      // 默认不提交
      for(i <- 0 until pipelineConfig.commitWidth) {
        commitAckMasks(i) := False
      }

      // BPU更新逻辑：无论是否预测成功都更新
      when(enable && headSlot.valid && headSlot.entry.status.done && headIsBranch) {
        bpuUpdatePort.valid := True
        bpuUpdatePort.payload.pc := headUop.decoded.pc
        bpuUpdatePort.payload.isTaken := headSlot.entry.status.isTaken // 假设result的MSB表示是否taken
        bpuUpdatePort.payload.target := headSlot.entry.status.result.asUInt // 假设result是最终目标地址
        report(L"[COMMIT] BPU UPDATE: pc=0x${bpuUpdatePort.payload.pc}, isTaken=${bpuUpdatePort.payload.isTaken}, target=0x${bpuUpdatePort.payload.target}")
      } otherwise {
        when(headIsBranch) {
          // report(L"[COMMIT] BPU Not upated because enable=${enable} headSlot.canCommit=${headSlot.canCommit} headIsBranch=${headIsBranch}")
        }
      }

      // 默认行为：如果可以提交且没有预测失败，则正常提交
      when(enable && headSlot.valid && headSlot.entry.status.done && !isMispredictedBranch) {
        commitAckMasks(0) := True
        
        // 保存检查点 (对于成功提交的指令)
        val ckptService = getService[CheckpointManagerService]
        val saveCheckpointTrigger = ckptService.getSaveCheckpointTrigger()
        saveCheckpointTrigger := True
        report(L"CHECKPOINT: Save checkpoint triggered on successful commit.")

        when(headUop.rename.writesToPhysReg) { // 如果该指令有写入目的寄存器 FIXME: JIRL 是不是也需要更新寄存器，哪怕预测失败？
        ratCommitUpdatePort.wen     := True
        ratCommitUpdatePort.archReg := headUop.decoded.archDest.idx
        ratCommitUpdatePort.physReg := headUop.rename.physDest.idx
        }
      }

      // *** 核心修复：如果检测到预测失败，则立即覆盖决策，并启动恢复流程 ***
      when(enable && headSlot.valid && headSlot.entry.status.done && isMispredictedBranch) {
        // 1. **否决提交**: 这是最重要的！确保错误的指令不会被提交。
        commitAckMasks(0) := False

        // 2. **发起ROB冲刷**: 立即清空ROB。
        robFlushPort.valid := True
        robFlushPort.payload.reason := FlushReason.FULL_FLUSH
        robFlushPort.payload.targetRobPtr.assignDontCare() // FULL_FLUSH不需要目标

        // 3. **触发检查点恢复**: 恢复RAT, FreeList, BusyTable到分支前的状态。
        restoreCheckpointTrigger := True
        report(
          L"[RegRes] freelist gc, busytable recover to last committed state. "
        )

        // 4. **发起前端硬重定向**: 告诉取指单元新的正确PC。
        hw.redirectPort.valid := True
        hw.redirectPort.payload := headSlot.entry.status.result.asUInt

        // --- 日志 ---
        ParallaxSim.notice(
          L"BRANCH MISPREDICT: Vetoing commit of robPtr=${headUop.robPtr}, PC=0x${headUop.decoded.pc} " :+
          L"and flushing pipeline. Redirecting to 0x${headSlot.entry.status.result}."
        )
      }

      // --- 物理寄存器回收与BusyTable清除 ---
      // 这部分逻辑现在是安全的，因为它依赖于最终的、正确的 `commitAckMasks`
      for (i <- 0 until pipelineConfig.commitWidth) {
        val doCommit = commitAckMasks(i)
        val committedEntry = commitSlots(i).entry 
        val committedUop = committedEntry.payload.uop 
        
        // 当恢复流程启动时，禁止所有回收操作
        val canRecycle = doCommit && committedUop.rename.allocatesPhysDest && !isMispredictedBranch
        
        // 回收到 FreeList
        freePorts(i).enable := canRecycle
        freePorts(i).physReg := committedUop.rename.oldPhysDest.idx
        when(canRecycle) {
          report(L"[RegRes] freelist recycle reg ${committedUop.rename.oldPhysDest.idx} (because commit uop@${committedUop.decoded.pc})")
        }
        
        // 从 BusyTable 中清除，这部分逻辑已删除，让具体的执行单元去做
        // (注意: 这里只处理了一个回收端口，如果commitWidth > 1，需要扩展)
        // when(canRecycle) {
        //   busyTableClearPort.valid   := True
        //   busyTableClearPort.payload := committedUop.rename.oldPhysDest.idx
        // }
      }

      // 将最终的提交决策发送给ROB
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitAcks(i) := commitAckMasks(i)
      }
      
      // --- 统计与日志逻辑 (这部分基本保持不变, 但现在基于正确的信号) ---
      val committedThisCycle_comb = CountOne(commitAckMasks)
      val recycledThisCycle_comb = CountOne(freePorts.map(_.enable))
      val flushedThisCycle_comb = robFlushPort.valid.asUInt
      
      val commitPcs = Vec(UInt(pipelineConfig.pcWidth), pipelineConfig.commitWidth)
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitPcs(i) := commitSlots(i).entry.payload.uop.decoded.pc
      }
      val maxCommitPcThisCycle = commitPcs.zip(commitAckMasks).map { case (pc, ack) =>
        Mux(ack, pc, U(0, pipelineConfig.pcWidth))
      }.reduce((a, b) => Mux(a > b, a, b))
      val anyCommitOOB = maxCommitPcEnabledExt && commitAckMasks.zip(commitPcs).map { case (ack, pc) =>
        ack && (pc > maxCommitPcExt)
      }.reduce(_ || _)
      
      // 准备日志Bundle
      val commitSlotLogs = Vec(CommitSlotLog(pipelineConfig), pipelineConfig.commitWidth)
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitSlotLogs(i).valid := commitSlots(i).valid
        commitSlotLogs(i).canCommit := commitSlots(i).canCommit && enable
        commitSlotLogs(i).doCommit := commitAckMasks(i)
        commitSlotLogs(i).robPtr := commitSlots(i).entry.payload.uop.robPtr
        commitSlotLogs(i).oldPhysDest := commitSlots(i).entry.payload.uop.rename.oldPhysDest.idx
        commitSlotLogs(i).allocatesPhysDest := commitSlots(i).entry.payload.uop.rename.allocatesPhysDest
      }
    }
    
    // ====================================================================
    // S1 阶段：寄存器更新 - 现在基于S0的正确决策
    // ====================================================================
    val s1 = new Area {
      // 锁存S0的组合逻辑结果
      val s1_committedThisCycle = RegNext(s0.committedThisCycle_comb) init(0)
      val s1_recycledThisCycle = RegNext(s0.recycledThisCycle_comb) init(0)
      val s1_flushedThisCycle = RegNext(s0.flushedThisCycle_comb) init(0)
      val s1_maxCommitPcThisCycle = RegNext(s0.maxCommitPcThisCycle) init(0)
      val s1_anyCommitOOB = RegNext(s0.anyCommitOOB) init(False)
      val s1_hasCommitsThisCycle = s1_committedThisCycle > 0

      // 更新PC跟踪寄存器
      when(s1_hasCommitsThisCycle && (s1_maxCommitPcThisCycle > maxCommitPcReg)) {
        maxCommitPcReg := s1_maxCommitPcThisCycle
      }
      
      // 更新OOB标志
      when(s1_anyCommitOOB) {
        commitOOBReg := True
      }
      
      // 更新持久的统计寄存器
      commitStatsReg.committedThisCycle := s1_committedThisCycle
      commitStatsReg.totalCommitted     := commitStatsReg.totalCommitted + s1_committedThisCycle
      commitStatsReg.physRegRecycled    := commitStatsReg.physRegRecycled + s1_recycledThisCycle
      commitStatsReg.robFlushCount      := commitStatsReg.robFlushCount + s1_flushedThisCycle
      commitStatsReg.commitOOB          := commitOOBReg
      commitStatsReg.maxCommitPc        := maxCommitPcReg
    }
    
    // restoreCheckpointTrigger is already set above in the IDLE logic
    getServiceOption[DebugDisplayService].foreach(dbg => { 
      dbg.setDebugValueOnce(s0.committedThisCycle_comb.asBool, DebugValue.COMMIT_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(commitOOBReg, DebugValue.COMMIT_OOB_ERROR, expectIncr = false)
    })

    // ====================================================================
    // 日志和调试
    // ====================================================================
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
