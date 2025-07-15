package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBService, FlushReason}
import parallax.components.rename._
import parallax.utilities.{Plugin, Service, ParallaxSim}
import parallax.utilities.Formattable
import parallax.fetch.SimpleFetchPipelineService
import parallax.utilities.ParallaxSim

/**
 * Commit statistics bundle for monitoring commit stage performance.
 */
case class CommitStats(pCfg: PipelineConfig = null) extends Bundle with Formattable {
  private val cw = if (pCfg == null) 1 else pCfg.commitWidth
  val committedThisCycle = UInt(log2Up(cw + 1) bits)
  val totalCommitted = UInt(32 bits) addAttribute("mark_debug", "true")
  val robFlushCount = UInt(32 bits)
  val physRegRecycled = UInt(32 bits)
  val commitOOB = Bool() addAttribute("mark_debug", "true")  // Out-of-bounds commit detected
  val maxCommitPc = UInt(32 bits) addAttribute("mark_debug", "true")  // Maximum PC committed so far

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
   * Set maximum allowed PC for commit operations.
   * When enabled, commits beyond this PC will trigger commitOOB signal.
   */
  def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    throw new RuntimeException("not implemented")
  }
  
  /**
   * Get commit progress information for debugging/monitoring.
   */
  def getCommitStats(): CommitStats
  
  /**
   * Check if processor is currently idle (stopped by IDLE instruction).
   */
  def isIdle(): Bool
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
  private val commitEnableExt = Bool()
  private val commitStatsReg = Reg(CommitStats(pipelineConfig)).initZero()
  private val maxCommitPcExt = in UInt(pipelineConfig.pcWidth)
  private val maxCommitPcEnabledExt = in Bool()
  
  // IDLE instruction state
  private val committedIdleReg = Reg(Bool()) init(False)
  private val committedIdlePcReg = Reg(UInt(pipelineConfig.pcWidth))
  
  // PC tracking state
  private val maxCommitPcReg = Reg(UInt(pipelineConfig.pcWidth)) init(0)
  private val commitOOBReg = Reg(Bool()) init(False)
  
  override def setCommitEnable(enable: Bool): Unit = {
    commitEnableExt := enable
  }
  
  override def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    maxCommitPcExt := maxPc
    maxCommitPcEnabledExt := enabled
  }
  
  override def isIdle(): Bool = {
    committedIdleReg
  }
  
  private var forwardedStats: CommitStats = null

  override def getCommitStats(): CommitStats = {
    assert(forwardedStats != null, "forwardedStats has not been initialized. getCommitStats() called too early.")
    forwardedStats
  }
  
  
  val hw = create early new Area {
    val robService = getService[ROBService[RenamedUop]]
    val ratControlService = getService[RatControlService]
    val flControlService = getService[FreeListControlService]
    val checkpointManagerService = getService[CheckpointManagerService] 
    val fetchService = getService[SimpleFetchPipelineService]
    
    // === Fetch Pipeline Control for IDLE ===
    val fetchDisable = fetchService.newFetchDisablePort()
    val robFlushPort = robService.newFlushPort()
    
    ParallaxSim.log(L"[CommitPlugin] Early setup - acquired services and fetch disable port")
  }

  val logic = create late new Area {
    val robService = hw.robService
    val ratControlService = hw.ratControlService
    val flControlService = hw.flControlService
    val checkpointManagerService = hw.checkpointManagerService 
    val fetchDisable = hw.fetchDisable

    // === ROB Interface ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth) // 本插件会发送 commit 用于真正触发提交
    val robFlushPort = hw.robFlushPort

    // === Physical Register Recycling ===
    val freePorts = flControlService.getFreePorts()
    require(freePorts.length >= pipelineConfig.commitWidth, 
      s"FreeList must have at least ${pipelineConfig.commitWidth} free ports for commit width")
    
    // === Checkpoint Management Interface ===
    val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()

    // === Commit Logic ===
    val commitCount = UInt(log2Up(pipelineConfig.commitWidth + 1) bits) 

    // === IDLE Instruction Detection ===
    // Only check the HEAD of ROB (first commit slot) to prevent repeated detection
    val headSlot = commitSlots(0)
    val headUop = headSlot.entry.payload.uop
  
    val commitAckMasks = Vec(Bool(), pipelineConfig.commitWidth)
    assert(pipelineConfig.commitWidth > 0)
    commitAckMasks(0) := commitEnableExt && commitSlots(0).valid && !committedIdleReg
    for (i <- 1 until pipelineConfig.commitWidth) {
        commitAckMasks(i) := commitSlots(i).valid && commitAckMasks(i-1)
    }

    // === IDLE Instruction State Management ===
    // IDLE instruction should be committed when detected, then set IDLE state
    val commitIdleThisCycle = headUop.decoded.uopCode === BaseUopCode.IDLE && commitAckMasks(0)
    // Set IDLE state when IDLE instruction at head is being committed
    when(commitIdleThisCycle) {
      committedIdleReg := True // 下个周期起效
      committedIdlePcReg := headUop.decoded.pc
      ParallaxSim.log(L"[CommitPlugin] IDLE instruction committed at PC=0x${headUop.decoded.pc}, entering IDLE state")
    }
    report(L"commitIdleThisCycle=${commitIdleThisCycle}, commitAckMasks(0)=${commitAckMasks(0)}: commitEnableExt=${commitEnableExt}, commitSlots(0).valid=${commitSlots(0).valid}, !committedIdleReg=${!committedIdleReg}")
    
    // TODO: Add external interrupt handling to exit IDLE state
    // For now, IDLE state persists until reset

    // === Fetch Pipeline Control Logic ===
    // Use the IDLE state register to control fetch, avoiding combinatorial loops
    // Once in IDLE state, keep fetch disabled until reset
    fetchDisable := committedIdleReg // 下个周期起效

    // === Pipeline Flush Logic ===
    // Use a delayed register to break combinatorial loops
    // IDLE instruction detection and flush happen in different cycles
    val idleJustCommitted = RegNext(commitIdleThisCycle, init = False)
    
    // Directly control ROB flush port for IDLE instructions (delayed by one cycle)
    when(idleJustCommitted) {
      robFlushPort.valid := True
      robFlushPort.payload.reason := FlushReason.ROLLBACK_TO_ROB_IDX
      robFlushPort.payload.targetRobPtr := U(0, pipelineConfig.robPtrWidth)  // Flush everything after IDLE
      ParallaxSim.log(L"[CommitPlugin] Delayed ROB flush triggered by IDLE instruction")
    } otherwise {
      robFlushPort.valid := False
      robFlushPort.payload.reason := FlushReason.NONE
      robFlushPort.payload.targetRobPtr := 0
    }
    
    // Trigger checkpoint restore for additional cleanup (delayed)
    restoreCheckpointTrigger := idleJustCommitted

    // 新增：收集每个槽位的详细日志信息
    val commitSlotLogs = Vec(CommitSlotLog(pipelineConfig), pipelineConfig.commitWidth)

    // === PC Tracking and OOB Detection ===
    val commitPcs = Vec(UInt(pipelineConfig.pcWidth), pipelineConfig.commitWidth)
    val anyCommitOOB = Bool()
    val maxCommitPcThisCycle = UInt(pipelineConfig.pcWidth)
    
    // Collect PCs from all committed instructions
    for (i <- 0 until pipelineConfig.commitWidth) {
      commitPcs(i) := commitSlots(i).entry.payload.uop.decoded.pc
    }
    
    // Find maximum PC among committed instructions this cycle
    maxCommitPcThisCycle := commitPcs.zip(commitAckMasks).map { case (pc, ack) =>
      Mux(ack, pc, U(0, pipelineConfig.pcWidth))
    }.reduce((a, b) => Mux(a > b, a, b))
    
    // Check for out-of-bounds commits
    anyCommitOOB := maxCommitPcEnabledExt && commitAckMasks.zip(commitPcs).map { case (ack, pc) =>
      ack && (pc > maxCommitPcExt)
    }.reduce(_ || _)
    
    // Update PC tracking registers
    val hasCommitsThisCycle = commitAckMasks.reduce(_ || _)
    when(hasCommitsThisCycle) {
      when(maxCommitPcThisCycle > maxCommitPcReg) {
        maxCommitPcReg := maxCommitPcThisCycle
      }
    }
    
    // Set OOB flag if any commit is out of bounds
    when(anyCommitOOB) {
      commitOOBReg := True
      ParallaxSim.log(L"[CommitPlugin] CRITICAL: Out-of-bounds commit detected! PC=0x${maxCommitPcThisCycle}, maxAllowed=0x${maxCommitPcExt}")
    }

    for (i <- 0 until pipelineConfig.commitWidth) {
      val commitAck = commitAckMasks(i) // Let ROB handle flush blocking via canCommitFlags 
      val committedEntry = commitSlots(i).entry 
      val committedUop = committedEntry.payload.uop 

      // 驱动 FreeList free port
      freePorts(i).enable := commitAck && committedUop.rename.allocatesPhysDest 
      freePorts(i).physReg := Mux(commitAck && committedUop.rename.allocatesPhysDest, 
                                 committedUop.rename.oldPhysDest.idx, 
                                 U(0, pipelineConfig.physGprIdxWidth)) 

      // 驱动 commit acknowledgments to ROB
      commitAcks(i) := commitAck

      // 填充日志信息
      commitSlotLogs(i).valid := commitSlots(i).valid
      commitSlotLogs(i).canCommit := commitSlots(i).valid
      commitSlotLogs(i).doCommit := commitAck
      commitSlotLogs(i).robPtr := committedUop.robPtr
      commitSlotLogs(i).oldPhysDest := committedUop.rename.oldPhysDest.idx
      commitSlotLogs(i).allocatesPhysDest := committedUop.rename.allocatesPhysDest
    }
    
    commitCount := CountOne(commitAckMasks)
    
    // === 集中式周期性日志打印 ===
    // --- 计算本周期的增量值 ---
    val committedThisCycle_comb = CountOne(commitAcks)
    val recycledThisCycle_comb = CountOne(freePorts.map(_.enable))
    val flushedThisCycle_comb = robFlushPort.valid.asUInt
    
    // --- 更新内部寄存器状态 (这部分逻辑不变) ---
    commitStatsReg.committedThisCycle := committedThisCycle_comb
    commitStatsReg.totalCommitted     := commitStatsReg.totalCommitted + committedThisCycle_comb
    commitStatsReg.physRegRecycled    := commitStatsReg.physRegRecycled + recycledThisCycle_comb
    commitStatsReg.robFlushCount      := commitStatsReg.robFlushCount + flushedThisCycle_comb
    commitStatsReg.commitOOB          := commitOOBReg
    commitStatsReg.maxCommitPc        := maxCommitPcReg
    
    // restoreCheckpointTrigger is already set above in the IDLE logic
    getServiceOption[DebugDisplayService].foreach(dbg => { 
      dbg.setDebugValueOnce(committedThisCycle_comb.asBool, DebugValue.COMMIT_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(commitOOBReg, DebugValue.COMMIT_OOB_ERROR, expectIncr = false)
    })
    // ==============================================================================
    // 核心修改 2: 创建前馈统计信号
    // ==============================================================================
    val fwd = CommitStats(pipelineConfig)
    
    // committedThisCycle 本身就是组合逻辑，直接赋值
    fwd.committedThisCycle := committedThisCycle_comb
    
    // totalCommitted 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.totalCommitted := commitStatsReg.totalCommitted + committedThisCycle_comb
    
    // physRegRecycled 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.physRegRecycled := commitStatsReg.physRegRecycled + recycledThisCycle_comb
    
    // robFlushCount 的实时值 = 寄存器的旧值 + 本周期的增量
    fwd.robFlushCount := commitStatsReg.robFlushCount + flushedThisCycle_comb
    
    // OOB detection: use current cycle's result or previous register value
    fwd.commitOOB := commitOOBReg || anyCommitOOB
    
    // Maximum PC: use current cycle's maximum or previous register value
    fwd.maxCommitPc := Mux(hasCommitsThisCycle && (maxCommitPcThisCycle > maxCommitPcReg), 
                          maxCommitPcThisCycle, 
                          maxCommitPcReg)

    // 将这个新的、带有前馈逻辑的 Bundle 赋值给插件的成员变量
    forwardedStats = fwd
    // === 集中式周期性日志打印 ===
    val cycleLogger = new Area {
      if(enableLog) {
        
        report(
          L"[COMMIT] Cycle Log: commitEnableExt=${commitEnableExt}, commitCount=${commitCount}, " :+
          L"committedIdleReg=${committedIdleReg}, IDLE_AtHead=${headUop.decoded.uopCode === BaseUopCode.IDLE}, IDLE_BeingCommitted=${commitIdleThisCycle}, " :+
          L"committedIdlePcReg=0x${committedIdlePcReg}, " :+
          L"ROB_Head_Valid=${headSlot.valid}, ROB_Head_Done=${headSlot.entry.status.done}, ROB_Head_UopCode=${headUop.decoded.uopCode.asBits}, " :+
          L"ROB_Flush_Valid=${robFlushPort.valid}, ROB_Flush_Reason=${robFlushPort.payload.reason.asBits}, " :+
          L"Restore_Checkpoint_Trigger=${restoreCheckpointTrigger}, " :+
          L"Fetch_Disable=${fetchDisable}, " :+
          L"Stats=${commitStatsReg.format}\n" :+
          L"  Slot Details: ${commitSlotLogs.map(s => L"\n    Slot: ${s.format} commitAck=${commitAcks(0)} commitPc=0x${commitPcs(0)}")}" // 为每个槽位格式化输出，每个槽位独占一行
        )
      }
    }
  }
}
