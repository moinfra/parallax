// [ParallaxSim]
package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBService, FlushReason}
import parallax.components.rename._
import parallax.utilities.{Plugin, Service, ParallaxSim}
import parallax.utilities.Formattable
import parallax.utilities.ParallaxSim
import parallax.utilities.ParallaxSim._
import parallax.fetch2.FetchService
import parallax.bpu.BpuService

/** Commit statistics bundle for monitoring commit stage performance.
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
  val pc = UInt(pCfg.pcWidth)
  val oldPhysDest = UInt(pCfg.physGprIdxWidth) // 旧的物理目的寄存器
  val allocatesPhysDest = Bool() // 该指令是否分配了新的物理目的寄存器

  def format: Seq[Any] = {
    Seq(
      L"(valid=${valid}, canCommit=${canCommit}, doCommit=${doCommit}, robPtr=${robPtr}, pc=0x${pc}",
      L", oldPhysDest=${oldPhysDest}, allocPhysDest(bool)=${allocatesPhysDest})"
    )
  }
}

/** Service interface for commit stage operations.
  * Provides control over commit enable/disable and commit status information.
  */
trait CommitService extends Service {

  /** Enable or disable commit operations.
    * When disabled, no instructions will be committed from ROB.
    */
  def setCommitEnable(enable: Bool): Unit

  /** Set maximum allowed PC for commit operations.
    * When enabled, commits beyond this PC will trigger commitOOB signal.
    */
  def setMaxCommitPc(maxPc: UInt, enabled: Bool): Unit = {
    throw new RuntimeException("not implemented")
  }

  /** Get commit progress information for debugging/monitoring.
    */
  def getCommitStatsReg(): CommitStats = {
    throw new RuntimeException("not implemented")
  }

  /** Check if processor is currently idle (stopped by IDLE instruction).
    */
  // def isIdle(): Bool
}

/** CommitPlugin implements the commit stage of the out-of-order processor.
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
) extends Plugin
    with CommitService {
  assert(pipelineConfig.commitWidth == 1, "This revised logic currently supports commitWidth=1 only.")

  val enableLog = true // 控制是否启用周期性详细日志

  // Service interface state (这部分保持不变)
  private val commitEnableExt = Bool()
  private val commitStatsReg = Reg(CommitStats(pipelineConfig)).initZero()
  private val maxCommitPcExt = UInt(pipelineConfig.pcWidth)
  private val maxCommitPcEnabledExt = Bool()

  private val maxCommitPcReg = Reg(UInt(pipelineConfig.pcWidth)) init (0) addAttribute ("mark_debug", "true")
  private val commitOOBReg = Reg(Bool()) init (False) addAttribute ("mark_debug", "true")

  val commitSlotLogs = Vec(CommitSlotLog(pipelineConfig), pipelineConfig.commitWidth)
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

    // === 接口和端口设置 ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth)
    val robFlushPort = hw.robFlushPort
    val freePorts = flControlService.getFreePorts()
    val restoreCheckpointTrigger = checkpointManagerService.getRestoreCheckpointTrigger()
    val bpuUpdatePort = hw.bpuUpdatePort
    val ratCommitUpdatePort = hw.ratCommitUpdatePort

    // 默认值设置
    robFlushPort.valid := False
    robFlushPort.payload.assignDontCare()

    // ====================================================================
    // 阶段 T+1 寄存器: 用于延迟冲刷的状态
    // 这些寄存器在周期 T 被设置，在周期 T+1 被用来执行冲刷
    // ====================================================================
    val mispredictedBranchCanCommit = Bool()
    val scheduedFlush = RegNext(mispredictedBranchCanCommit, init = False)
    val hardRedirectTarget = RegNext(commitSlots(0).entry.status.targetPc)

    // ====================================================================
    // 阶段 T+1: 冲刷执行
    // 如果上个周期（T）标记了需要冲刷，则在这个周期（T+1）执行
    // ====================================================================
    when(scheduedFlush) {
      // 1. **发起ROB冲刷**: 清空ROB中所有推测性指令
      robFlushPort.valid := True
      robFlushPort.payload.reason := FlushReason.FULL_FLUSH
      robFlushPort.payload.targetRobPtr.assignDontCare() // FULL_FLUSH 冲刷所有

      // 2. **触发检查点恢复**: 恢复RAT, FreeList, BusyTable到分支前的状态
      restoreCheckpointTrigger := True

      // 3. **发起前端硬重定向**: 告诉取指单元新的正确PC
      hw.redirectPort.valid := True
      hw.redirectPort.payload := hardRedirectTarget

      // 4. 日志记录冲刷事件
      ParallaxSim.notice(
        L"FLUSH EXECUTION: Flushing pipeline due to mispredict. Redirecting to 0x${hardRedirectTarget}."
      )
    }

    // ====================================================================
    // 阶段 T: 组合逻辑 - 决策与标记
    // ====================================================================
    val s0 = new Area {
      val enable = commitEnableExt

      // --- 从ROB获取头部槽位信息 ---
      val headSlot = commitSlots(0)
      val headUop = headSlot.entry.payload.uop
      val headIsBranch = headUop.decoded.isBranchOrJump
      val headIsDone = headSlot.valid && headSlot.canCommit && headSlot.entry.status.done

      // 关键条件：在ROB头部发现了一条预测失败的分支指令
      mispredictedBranchCanCommit := enable && headIsDone && headIsBranch && headSlot.entry.status.isMispredictedBranch

      // --- 提交决策 与 架构状态更新 ---
      val commitAckMasks = Vec(Bool(), pipelineConfig.commitWidth)
      commitAckMasks.foreach(_ := False) // 默认不提交
      freePorts(0).enable := False
      freePorts(0).physReg := U(0)

      // 只要ROB头部的指令完成了（done），就无条件地提交它。
      // 预测失败的状态不会阻止这条分支指令本身的提交，只会触发后续的冲刷。
      when(enable && headIsDone && !scheduedFlush) {
        // 1. 向ROB确认提交，使其可以pop该条目
        commitAckMasks(0) := True

        // 2. 更新架构寄存器别名表 (ARAT)，如果该指令写寄存器
        when(headUop.rename.writesToPhysReg) {
          ratCommitUpdatePort.wen := True
          ratCommitUpdatePort.archReg := headUop.decoded.archDest.idx
          ratCommitUpdatePort.physReg := headUop.rename.physDest.idx
          debug(
            L"[RegRes] Update ARAT: archReg=a${ratCommitUpdatePort.archReg}, physReg=p${ratCommitUpdatePort.physReg}"
          )
        }

        // 3. 回收旧的物理寄存器到FreeList
        when(headUop.rename.allocatesPhysDest) {
          freePorts(0).enable := True
          freePorts(0).physReg := headUop.rename.oldPhysDest.idx
          debug(
            L"[RegRes] freelist recycle reg p${headUop.rename.oldPhysDest.idx} (committing PC=0x${headUop.decoded.pc})"
          )
        }

        // 4. 更新分支预测器 (BPU)，如果是分支指令
        when(headIsBranch) {
          bpuUpdatePort.valid := True
          bpuUpdatePort.payload.pc := headUop.decoded.pc
          bpuUpdatePort.payload.isTaken := headSlot.entry.status.isTaken
          bpuUpdatePort.payload.target := headSlot.entry.status.targetPc
          debug(
            L"[COMMIT] BPU UPDATE: pc=0x${bpuUpdatePort.payload.pc}, isTaken=${bpuUpdatePort.payload.isTaken}, target=0x${bpuUpdatePort.payload.target}"
          )
        }
      }

      // --- 关键决策: 标记冲刷 vs. 保存检查点 ---

      // 重置下一周期的冲刷标记。它只在检测到预测失败时才会被置位。
      // 这个赋值是组合逻辑，下一拍的值将由下面的逻辑决定。
      // 创建一个默认不触发的保存检查点信号
      val ckptService = getService[CheckpointManagerService]
      val saveCheckpointTrigger = ckptService.getSaveCheckpointTrigger()

      // 仅当ROB头部指令完成时，才做进一步的决策
      when(enable && headIsDone && !scheduedFlush) {

        // 无论怎样只要提交就创建检查点，尤其对于有副作用的BL和JIRL指令
        saveCheckpointTrigger := True
        debug(L"CHECKPOINT: Save checkpoint triggered on successful commit. PC=0x${headUop.decoded.pc}")
        when(mispredictedBranchCanCommit) {
          ParallaxSim.notice(
            L"MISPREDICT MARK (T): Marking for flush in next cycle. PC=0x${headUop.decoded.pc}, " :+
              L"Target=0x${headSlot.entry.status.targetPc}. This branch instruction itself is being committed."
          )
        }
      }

      // 将最终的提交决策发送给ROB
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitAcks(i) := commitAckMasks(i)
      }

      when(commitAcks(0)) {
        assert(!scheduedFlush, "Cannot commit this cycle when a mispredicted branch caused a flush.")
      }

      // --- 统计与日志的准备逻辑 ---
      val committedThisCycle_comb = CountOne(commitAckMasks)
      val recycledThisCycle_comb = CountOne(freePorts.map(_.enable))

      // 冲刷事件现在发生在T+1，所以组合逻辑中我们只看是否会冲刷。
      // 实际的统计更新将在s1阶段基于寄存器进行。
      val commitPcs = Vec(UInt(pipelineConfig.pcWidth), pipelineConfig.commitWidth)
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitPcs(i) := commitSlots(i).entry.payload.uop.decoded.pc
      }
      val maxCommitPcThisCycle = commitPcs
        .zip(commitAckMasks)
        .map { case (pc, ack) =>
          Mux(ack, pc, U(0, pipelineConfig.pcWidth))
        }
        .reduce((a, b) => Mux(a > b, a, b))
      val anyCommitOOB = maxCommitPcEnabledExt && commitAckMasks
        .zip(commitPcs)
        .map { case (ack, pc) =>
          ack && (pc > maxCommitPcExt)
        }
        .reduce(_ || _)

      // 准备日志Bundle
      for (i <- 0 until pipelineConfig.commitWidth) {
        commitSlotLogs(i).valid := commitSlots(i).valid
        commitSlotLogs(i).canCommit := commitSlots(i).canCommit && enable
        commitSlotLogs(i).doCommit := commitAckMasks(i)
        commitSlotLogs(i).robPtr := commitSlots(i).entry.payload.uop.robPtr
        commitSlotLogs(i).pc := commitSlots(i).entry.payload.uop.decoded.pc
        commitSlotLogs(i).oldPhysDest := commitSlots(i).entry.payload.uop.rename.oldPhysDest.idx
        commitSlotLogs(i).allocatesPhysDest := commitSlots(i).entry.payload.uop.rename.allocatesPhysDest
      }
    }

    // ====================================================================
    // S1 阶段：寄存器更新 - 现在基于S0的正确决策
    // ====================================================================
    val s1 = new Area {
      // 锁存S0的组合逻辑结果
      val s1_committedThisCycle = RegNext(s0.committedThisCycle_comb) init (0)
      val s1_recycledThisCycle = RegNext(s0.recycledThisCycle_comb) init (0)

      // 注意：冲刷统计现在基于 T+1 的实际冲刷动作
      val s1_flushedThisCycle = RegNext(scheduedFlush) init (False)

      val s1_maxCommitPcThisCycle = RegNext(s0.maxCommitPcThisCycle) init (0)
      val s1_anyCommitOOB = RegNext(s0.anyCommitOOB) init (False)
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
      commitStatsReg.robFlushCount      := commitStatsReg.robFlushCount
      commitStatsReg.commitOOB          := commitOOBReg
      commitStatsReg.maxCommitPc        := maxCommitPcReg
    }

    // restoreCheckpointTrigger 在本周期（T+1）的冲刷逻辑中被设置
    getServiceOption[DebugDisplayService].foreach(dbg => {
      dbg.setDebugValueOnce(s0.committedThisCycle_comb.asBool, DebugValue.COMMIT_FIRE, expectIncr = true)
      dbg.setDebugValueOnce(commitOOBReg, DebugValue.COMMIT_OOB_ERROR, expectIncr = false)
    })

    // ====================================================================
    // 日志和调试
    // ====================================================================
    val counter = Reg(UInt(32 bits)) init (0)
    counter := counter + 1
    // === 集中式周期性日志打印 ===
    if (enableLog) {

      debug(
        L"[COMMIT] Cycle ${counter} Log: " :+
          L"Stats=${commitStatsReg.format}\n" :+
          L"  Slot Details: ${commitSlotLogs.map(s => L"\n    Slot: ${s.format} commitPc=0x${s0.commitPcs(0)}")}" // 为每个槽位格式化输出，每个槽位独占一行
      )
    } else {
      // 只打印首次 valid=1 和 doCommit = 1
      val prevValid = RegNext(commitSlots(0).valid, init = False)
      when(!prevValid && commitSlots(0).valid) {
        debug(
          L"[COMMIT] Cycle ${counter} Log: " :+
            L"Stats=${commitStatsReg.format}\n" :+
            L"  Slot Details: ${commitSlotLogs(0).format} commitAck=${commitAcks(0)} commitPc=0x${s0.commitPcs(0)}" // 只打印第一个槽位
        )
      } elsewhen (commitAcks(0)) {
        debug(
          L"[COMMIT] Cycle ${counter} Log: " :+
            L"Stats=${commitStatsReg.format}\n" :+
            L"  Slot Details: ${commitSlotLogs(0).format} commitAck=${commitAcks(0)} commitPc=0x${s0.commitPcs(0)}" // 只打印第一个槽位
        )
      }
    }
  }
}
