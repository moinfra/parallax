// filename: hw/spinal/parallax/issue/SequentialIssueQueueComponent.scala
package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.WakeupPayload
import parallax.utilities.{ParallaxLogger, ParallaxSim}

class SequentialIssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val numWakeupPorts: Int,
    val id: Int = 0
) extends Component
    with IssueQueueLike[T_IQEntry] {

  override val io = slave(IssueQueueComponentIo(iqConfig, numWakeupPorts))
  val idStr = s"${iqConfig.name}-SEQ-${id.toString()}"

  // ====================================================================
  // 1. 状态存储区 (State Storage Area)
  // ====================================================================
  val state = new Area {
    val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
    val entryValids = Reg(Bits(iqConfig.depth bits)) init (0)

    val validCount = CountOne(entryValids)
    val isFull = validCount === iqConfig.depth
    val isEmpty = validCount === 0

    // 修正4：正确地初始化寄存器
    val wakeupInReg = Reg(Vec.fill(numWakeupPorts)(Flow(WakeupPayload(iqConfig.pipelineConfig))))
    wakeupInReg.foreach(_.valid.init(False)) // 使用.init确保是编译时初始化
  }

  // ====================================================================
  // 2. 唤醒逻辑区 (Wakeup Logic Area)
  // ====================================================================
  val wakeup = new Area {
    // 寄存唤醒信号以改善时序
    state.wakeupInReg := io.wakeupIn

    val wokeUpSrc1Mask = Bits(iqConfig.depth bits)
    val wokeUpSrc2Mask = Bits(iqConfig.depth bits)
    wokeUpSrc1Mask.clearAll()
    wokeUpSrc2Mask.clearAll()

    for (i <- 0 until iqConfig.depth) {
      val entry = state.entries(i)
      when(state.entryValids(i)) {
        val canWakeupSrc1 = !entry.src1Ready && entry.useSrc1
        val canWakeupSrc2 = !entry.src2Ready && entry.useSrc2

        for (wakeup <- state.wakeupInReg) {
          when(wakeup.valid) {
            when(canWakeupSrc1 && (entry.src1Tag === wakeup.payload.physRegIdx)) {
              wokeUpSrc1Mask(i) := True
            }
            when(canWakeupSrc2 && (entry.src2Tag === wakeup.payload.physRegIdx)) {
              wokeUpSrc2Mask(i) := True
            }
          }
        }
      }
    }
  }

  // ====================================================================
  // 3. 发射与分配逻辑区 (Issue & Allocation Logic Area)
  // ====================================================================
  val issue_alloc = new Area {
    // --- 发射选择 (只看队首) ---
    val headEntry = state.entries(0)
    val headIsValid = state.entryValids(0)
    val headIsReady = (!headEntry.useSrc1 || headEntry.src1Ready) &&
      (!headEntry.useSrc2 || headEntry.src2Ready)

    val canIssue = headIsValid && headIsReady

    io.issueOut.valid := canIssue && !io.flush
    io.issueOut.payload := headEntry
    val issueFired = io.issueOut.fire

    // --- 分配与反压 ---
    io.allocateIn.ready := !state.isFull && !io.flush
    val allocationFired = io.allocateIn.fire
  }

  // ====================================================================
  // 4. 下一周期状态更新区 (Next State Update Area)
  // ====================================================================
  val update = new Area {

    // --- 步骤 1: 计算下一周期的 entryValids (队列结构变化) ---
    val nextEntryValids = B(state.entryValids)

    when(issue_alloc.issueFired) {
      // 发射成功，队列左移一位
      nextEntryValids := (state.entryValids |<< 1).resize(iqConfig.depth)
    }

    when(issue_alloc.allocationFired) {
      // 新指令入队，在尾部置位
      // 使用一位热编码来设置，更安全
      nextEntryValids(state.validCount) := True
    }

    // --- 步骤 2: 计算下一周期的 entries (数据移动和更新) ---
    val nextEntries = Vec.fill(iqConfig.depth)(iqConfig.getIQEntry())
    nextEntries := state.entries // 默认保持不变

    // 修正2 & 3: 使用明确的、分层的更新逻辑，而不是复杂的if-elsewhen链

    // 首先，处理压缩 (Compression)
    when(issue_alloc.issueFired) {
      for (i <- 0 until iqConfig.depth - 1) {
        nextEntries(i) := state.entries(i + 1)
      }
      nextEntries(iqConfig.depth - 1).assignDontCare() // 最后一个槽位被清空
    }

    // 然后，处理入队 (Allocation)，这会覆盖压缩逻辑的结果
    when(issue_alloc.allocationFired) {
      val allocCmd = io.allocateIn.payload
      val allocUop = allocCmd.uop
      val newEntry = iqConfig.getIQEntry()

      newEntry.initFrom(allocUop, allocUop.robPtr).allowOverride()

      val s1WakesUp = state.wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc1.idx).orR
      val s2WakesUp = state.wakeupInReg.map(w => w.valid && w.payload.physRegIdx === allocUop.rename.physSrc2.idx).orR

      newEntry.src1Ready := allocCmd.src1InitialReady || s1WakesUp
      newEntry.src2Ready := allocCmd.src2InitialReady || s2WakesUp

      // 写入尾部
      assert(state.validCount < iqConfig.depth)
      nextEntries(state.validCount.resized) := newEntry
    }

    // 最后，处理唤醒 (Wakeup)，这会更新所有未被移动的条目
    for (i <- 0 until iqConfig.depth) {
      val c = Bool(i < iqConfig.depth - 1)
      val shouldUpdateWakeup =
        // 如果发射了，只有被移过来的槽位需要更新唤醒 (i < depth-1)
        (issue_alloc.issueFired && c && wakeup.wokeUpSrc1Mask(i + 1)) ||
          // 如果没发射，所有有效槽位都需要更新唤醒
          (!issue_alloc.issueFired && state.entryValids(i) && wakeup.wokeUpSrc1Mask(i))

      when(shouldUpdateWakeup) {
        nextEntries(i).src1Ready := True
      }
      // (对src2做同样的操作)
      val shouldUpdateWakeup2 =
        (issue_alloc.issueFired && c && wakeup.wokeUpSrc2Mask(i + 1)) ||
          (!issue_alloc.issueFired && state.entryValids(i) && wakeup.wokeUpSrc2Mask(i))
      when(shouldUpdateWakeup2) {
        nextEntries(i).src2Ready := True
      }
    }

    // --- 步骤 3: 最终赋值给寄存器 ---
    state.entries := nextEntries
    state.entryValids := nextEntryValids

    // 冲刷具有最高优先级
    when(io.flush) {
      state.entryValids := 0
    }
  }

  // ====================================================================
  // 5. 调试与日志记录区 (Debug & Monitoring Area)
  // ====================================================================
  val debug = new Area {
    val logCondition = io.allocateIn.fire || io.issueOut.fire
    when(logCondition) {
      ParallaxSim.log(
        L"${idStr}: STATUS - ValidCount=${state.validCount}, " :+
          L"allocateIn(valid=${io.allocateIn.valid}, ready=${io.allocateIn.ready}), " :+
          L"issueOut(valid=${io.issueOut.valid}, ready=${io.issueOut.ready})"
      )
    }
  }
}
