package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.{WakeupPayload, WakeupService}
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.issue.IqDispatchCmd

/**
 * IssueQueueComponent IO Bundle
 *
 * 使用 Stream 接口进行分配，通过其内建的 ready 信号实现反压。
 * @param iqConfig IQ 的配置
 * @param numWakeupPorts 并行唤醒端口的数量
 */
case class IssueQueueComponentIo[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val numWakeupPorts: Int
) extends Bundle
    with IMasterSlave  {

  // 从 Dispatch 阶段接收新的微操作 (uop)
  val allocateIn = Stream(IqDispatchCmd(iqConfig.pipelineConfig))
  
  // 向对应的执行单元 (EU) 发射准备好的微操作
  val issueOut = Stream(iqConfig.getIQEntry())
  
  // 接收来自所有唤醒源的广播信号
  val wakeupIn = Vec.fill(numWakeupPorts)(Flow(WakeupPayload(iqConfig.pipelineConfig)))
  
  // 接收全局的流水线刷新信号
  val flush = Bool()

  // 定义主从接口的方向
  override def asMaster(): Unit = {
    master(allocateIn)
    wakeupIn.foreach(master(_))
    out(flush)
    slave(issueOut)
  }
}


trait IssueQueueLike[T_IQEntry <: Data with IQEntryLike] extends Component {
    // 【修改】移除抽象类型成员
    // type T_IQEntry <: Data with IQEntryLike 
    
    // 【修改】现在这些成员可以直接使用Trait的泛型参数 T_IQEntry
    def iqConfig: IssueQueueConfig[T_IQEntry]
    def numWakeupPorts: Int

    // IO的类型也直接使用Trait的泛型参数
    def io: IssueQueueComponentIo[T_IQEntry]
    def idStr: String
}


/**
 * 通用发射队列 (Issue Queue) 组件
 *
 * 这是一个乱序执行处理器的核心组件，它负责：
 * 1. 暂存从 Dispatch 阶段派发过来的指令。
 * 2. 监听全局唤醒总线和本地发射端口，以确定指令的源操作数何时就绪。
 * 3. 当一条指令的所有源操作数都就绪时，将其标记为可发射。
 * 4. 从所有可发射的指令中，根据预定的策略（如最老优先）选择一条，并将其发射到执行单元。
 * 5. 当队列满时，通过反压机制暂停上游流水线。
 *
 * @param iqConfig       IQ 的配置，包括深度、类型等
 * @param numWakeupPorts 并行唤醒端口的数量
 * @param id             组件的唯一ID，用于日志记录
 */
class IssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val numWakeupPorts: Int,
    val id: Int = 0
) extends Component with IssueQueueLike[T_IQEntry]  {
  
  // 实例化 IO
  val io = slave (IssueQueueComponentIo(iqConfig, numWakeupPorts))
  val idStr = s"${iqConfig.name}-${id.toString()}"

  val wakeupInReg = RegNextWhen(io.wakeupIn, cond = !io.flush) initZero()
  when(io.flush) {
    wakeupInReg.clearAll()
  }

  // ====================================================================
  // 1. 状态寄存器 (State Registers)
  // ====================================================================

  // 存储 IQ 中每个条目的具体信息
  val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
  // 标记每个槽位是否有效
  val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))

  // ====================================================================
  // 2. 唤醒逻辑 (Wakeup Logic)
  // ====================================================================
  
  // 这部分是纯组合逻辑，用于计算哪些条目在当前周期被唤醒。
  // 它的结果 (wokeUp...Mask) 将在步骤4中用于更新下一周期的状态。

  // a. 本地唤醒 (Local Wakeup / Bypass)
  // 当本IQ成功发射一条指令时，它的结果可以立即用于唤醒队列中的其他指令。
  // val localWakeupValid = io.issueOut.fire && io.issueOut.payload.writesToPhysReg
  val localWakeupValid = False // 大意了，想得太简单了，在指令发射的瞬间就对它的依赖者进行了唤醒，这是错的。必须是结果出来才能唤醒。
  val localWakeupTag = io.issueOut.payload.physDest.idx
  
  // b. 预计算唤醒掩码
  val wokeUpSrc1Mask = Bits(iqConfig.depth bits)
  val wokeUpSrc2Mask = Bits(iqConfig.depth bits)
  wokeUpSrc1Mask.clearAll()
  wokeUpSrc2Mask.clearAll()

  for (i <- 0 until iqConfig.depth) {
    val entry = entries(i)
    when(entryValids(i) && !io.flush) {
      // 检查每个源操作数是否需要并可以被唤醒
      val canWakeupSrc1 = !entry.src1Ready && entry.useSrc1
      val canWakeupSrc2 = !entry.src2Ready && entry.useSrc2

      // 检查本地唤醒
      when(canWakeupSrc1 && localWakeupValid && (entry.src1Tag === localWakeupTag)) {
        wokeUpSrc1Mask(i) := True
      }
      when(canWakeupSrc2 && localWakeupValid && (entry.src2Tag === localWakeupTag)) {
        wokeUpSrc2Mask(i) := True
      }
      
      // 检查所有并行的全局唤醒端口
      for (wakeup <- wakeupInReg) {
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

  // ====================================================================
  // 3. 发射与反压逻辑 (Issue and Back-Pressure Logic)
  // ====================================================================
  
  // 这部分逻辑基于上一个周期的状态 (寄存器中的值) 来做出本周期的决策。
  
  // a. 发射选择 (Issue Selection)
  // 找出所有源操作数都已就绪的条目
  val entriesReadyToIssue = Vec.tabulate(iqConfig.depth) { i =>
    val entry = entries(i)
    entryValids(i) &&
    (!entry.useSrc1 || entry.src1Ready) &&
    (!entry.useSrc2 || entry.src2Ready) && !io.flush
  }
  
  // 将就绪条目转换为Bitmask，并使用优先级编码器选择最老的一个
  // (OHMasking.first 默认选择最低位的 '1'，对应索引最小的槽位，即最老的)
  val issueRequestMask = B(entriesReadyToIssue)
  val issueRequestOh = OHMasking.first(issueRequestMask)
  val issueIdx = OHToUInt(issueRequestOh)

  // 驱动发射端口
  io.issueOut.valid := issueRequestOh.orR && !io.flush
  io.issueOut.payload := entries(issueIdx) // 使用寄存器中的值，避免长组合路径

  // b. 分配与反压 (Allocation and Back-Pressure)
  // 计算当前空闲的槽位
  val freeSlotsMask = entryValids.map(!_).asBits
  
  // 我们可以接受一个新请求，如果：
  // 1. IQ中本来就有空槽 (freeSlotsMask.orR)
  // 2. 或者，本周期我们将成功发射一条指令来腾出空间 (io.issueOut.fire)
  //    (.fire 包含了对下游 .ready 的判断，这是最关键的改进)
  val hasSpaceForNewEntry = freeSlotsMask.orR || io.issueOut.fire
  
  // 驱动 allocateIn.ready 信号，实现对上游的反压
  io.allocateIn.ready := hasSpaceForNewEntry && !io.flush
  
  // 为新分配的指令选择一个槽位
  // 优先使用一直都空的槽位，如果没有，再使用本周期因发射而变空的槽位
  val firedSlotMask = B(0, iqConfig.depth bits)
  when(io.issueOut.fire) {
    firedSlotMask(issueIdx) := True
  }
  val allocationMask = OHMasking.first(freeSlotsMask | firedSlotMask)
  val allocateIdx = OHToUInt(allocationMask)
// (接第一部分的代码...)

  // ====================================================================
  // 4. 下一周期状态计算 (Next State Calculation)
  // ====================================================================

  // Iterate through each slot and determine its next state in a parallel way
for (i <- 0 until iqConfig.depth) {

  // --- Condition 1: A new instruction is allocated to this slot ---
  val isAllocatingToThisSlot = io.allocateIn.fire && (allocateIdx === i)

  // --- Condition 2: This slot is being issued and thus cleared ---
  val isIssuingFromThisSlot = io.issueOut.fire && (issueIdx === i)
  
  when(isAllocatingToThisSlot) {
    // If we are allocating here, the entry is completely overwritten.
    val allocCmd = io.allocateIn.payload
    val allocUop = allocCmd.uop
    
    entries(i).initFrom(allocUop, allocUop.robPtr).allowOverride()
    entryValids(i) := True
    
    // Check for same-cycle wakeup for the new entry
    val s1WakesUp = wakeupInReg.map(w => w.valid && (allocUop.rename.physSrc1.idx === w.payload.physRegIdx)).orR
    val s2WakesUp = wakeupInReg.map(w => w.valid && (allocUop.rename.physSrc2.idx === w.payload.physRegIdx)).orR

    entries(i).src1Ready := allocCmd.src1InitialReady || s1WakesUp
    entries(i).src2Ready := allocCmd.src2InitialReady || s2WakesUp

  } elsewhen (isIssuingFromThisSlot) {
    // If we are issuing from here, the slot becomes invalid.
    entryValids(i) := False
    // The rest of the entry's state doesn't matter as it's invalid.

  } otherwise {
    // If not being allocated to or issued from, just update the ready bits based on wakeup.
    // The valid bit and other fields remain the same.
    when(wokeUpSrc1Mask(i)) { entries(i).src1Ready := True }
    when(wokeUpSrc2Mask(i)) { entries(i).src2Ready := True }
  }
}

// Apply Flush with highest priority, overriding all other logic.
when(io.flush) {
  entryValids.foreach(_ := False)
}

  // ====================================================================
  // 6. 调试与日志记录 (Debug and Monitoring)
  // ====================================================================
  
  // 计算当前有效的条目数量，用于调试和暴露给测试平台
  val currentValidCount = CountOne(entryValids)

  // 为了减少日志量，只在有活动（分配或发射）时打印状态
  val logCondition = io.allocateIn.fire || io.issueOut.fire
  
  when(logCondition) {
    ParallaxSim.log(
      L"${idStr}: STATUS - ValidCount=${currentValidCount}, " :+
      L"allocateIn(valid=${io.allocateIn.valid}, ready=${io.allocateIn.ready}), " :+
      L"issueOut(valid=${io.issueOut.valid}, ready=${io.issueOut.ready})"
    )
  }
  
  // 打印每个有效条目的详细信息
  // 注意：这里打印的是上一个周期的状态 (来自寄存器)，这样可以与当前周期的决策对应起来
  when(logCondition && currentValidCount > 0) {
    for (i <- 0 until iqConfig.depth) {
      when(entryValids(i)) {
        ParallaxSim.log(
          L"${idStr}: (LAST CYCLE) ENTRY[${i}] - RobPtr=${entries(i).robPtr}, " :+
          L"PhysDest=${entries(i).physDest.idx}, " :+
          L"UseSrc1=${entries(i).useSrc1}, Src1Tag=${entries(i).src1Tag}, Src1Ready=${entries(i).src1Ready}, " :+
          L"UseSrc2=${entries(i).useSrc2}, Src2Tag=${entries(i).src2Tag}, Src2Ready=${entries(i).src2Ready}"
        )
      }
    }
  }

  // 在Verilog生成时打印一条信息，确认组件被正确例化
  ParallaxLogger.log(
    s"${idStr} Component (depth ${iqConfig.depth}, wakeup ports ${numWakeupPorts}, type ${iqConfig.uopEntryType().getClass.getSimpleName}) elaborated with Stream-based back-pressure."
  )
}
