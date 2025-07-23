package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.{WakeupPayload, WakeupService}
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.issue.IqDispatchCmd

case class IssueQueueComponentIo[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry]
) extends Bundle
    with IMasterSlave {
  val allocateIn = Flow(IqDispatchCmd(iqConfig.pipelineConfig))
  val canAccept = Bool()
  val issueOut = Stream(iqConfig.getIQEntry())
  val wakeupIn = Flow(WakeupPayload(iqConfig.pipelineConfig)) // Global wakeup bus
  val flush = Bool()

  override def asMaster(): Unit = {
    master(allocateIn)
    master(wakeupIn)
    in(canAccept)
    out(flush)
    slave(issueOut)
  }
}

class IssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val id: Int = 0
) extends Component {
  val io = slave(IssueQueueComponentIo(iqConfig))
  val idStr = s"${iqConfig.name}-${id.toString()}"

  // 状态寄存器保持不变
  val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
  val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))

  // ====================================================================
  //  帕累托改进: 逻辑重组以优化时序
  // ====================================================================

  // --- 步骤 1: 预计算唤醒匹配 (Pre-computation Area) ---
  // 这部分逻辑只依赖于上周期的状态和本周期的输入，可以独立并行计算。
  // 它的输出 (wokeUp...Mask) 将用于计算下一个周期的 srcReady 状态。
  // --------------------------------------------------------------------
  val localWakeupValid = io.issueOut.fire && io.issueOut.payload.writesToPhysReg
  val localWakeupTag = io.issueOut.payload.physDest.idx
  
  val globalWakeupValid = io.wakeupIn.valid
  val globalWakeupTag = io.wakeupIn.payload.physRegIdx
  
  val wokeUpSrc1Mask = Bits(iqConfig.depth bits)
  val wokeUpSrc2Mask = Bits(iqConfig.depth bits)
  wokeUpSrc1Mask.clearAll()
  wokeUpSrc2Mask.clearAll()

  for (i <- 0 until iqConfig.depth) {
    val entry = entries(i)
    when(entryValids(i)) {
      when(!entry.src1Ready) {
        val s1LocalMatch  = localWakeupValid  && entry.useSrc1 && (entry.src1Tag === localWakeupTag)
        val s1GlobalMatch = globalWakeupValid && entry.useSrc1 && (entry.src1Tag === globalWakeupTag)
        when(s1LocalMatch || s1GlobalMatch) {
          wokeUpSrc1Mask(i) := True
        }
      }
      when(!entry.src2Ready) {
        val s2LocalMatch  = localWakeupValid  && entry.useSrc2 && (entry.src2Tag === localWakeupTag)
        val s2GlobalMatch = globalWakeupValid && entry.useSrc2 && (entry.src2Tag === globalWakeupTag)
        when(s2LocalMatch || s2GlobalMatch) {
          wokeUpSrc2Mask(i) := True
        }
      }
    }
  }


  // --- 步骤 2: 基于上周期的状态进行决策 (Decision Area) ---
  // 【关键优化】: 发射选择逻辑现在只依赖于寄存器中的值 (entries, entryValids)。
  // 它不再等待本周期的唤醒结果，从而大大缩短了关键路径。
  // ----------------------------------------------------------------
  // a. Issue/Selection Logic
  val entriesReadyToIssue = Vec.tabulate(iqConfig.depth) { i =>
    val entry = entries(i)
    entryValids(i) &&
    (!entry.useSrc1 || entry.src1Ready) &&
    (!entry.useSrc2 || entry.src2Ready)
  }
  val issueRequestMask = B(entriesReadyToIssue)
  val issueRequestOh = OHMasking.first(issueRequestMask)
  val issueIdx = OHToUInt(issueRequestOh)

  // b. Allocation Logic
  val freeSlotsMask = entryValids.map(!_).asBits
  val canAccept = freeSlotsMask.orR
  val allocateIdx = OHToUInt(OHMasking.first(freeSlotsMask))
  
  // c. Drive IO Outputs
  io.canAccept := canAccept && !io.flush
  io.issueOut.valid := issueRequestOh.orR && !io.flush
  io.issueOut.payload := entries(issueIdx) // Payload is from registered state


  // --- 步骤 3: 计算下一周期的状态 (Next State Calculation Area) ---
  // 这部分逻辑现在合并了所有更新源。
  // --------------------------------------------------------------------
  val entriesNext = CombInit(entries)
  val entryValidsNext = CombInit(entryValids)

  // a. 应用预计算的唤醒结果
  for(i <- 0 until iqConfig.depth) {
    when(wokeUpSrc1Mask(i)) { entriesNext(i).src1Ready := True }
    when(wokeUpSrc2Mask(i)) { entriesNext(i).src2Ready := True }
  }
  
  // b. 应用发射结果
  when(io.issueOut.fire) {
    entryValidsNext(issueIdx) := False
  }

  // c. 应用分配结果
  // 分配操作会覆盖掉它所在槽位的旧内容。
  // 唤醒-分配的竞态依然由赋值顺序保证：如果本周期有唤醒，`entriesNext`的
  // `srcReady`位会被置位，即使`allocateIn`的初始值是false。
  when(io.allocateIn.valid && io.canAccept && !io.flush) {
    val allocCmd = io.allocateIn.payload
    val allocUop = allocCmd.uop
    
    entriesNext(allocateIdx).initFrom(allocUop, allocUop.robPtr)
    entriesNext(allocateIdx).src1Ready := allocCmd.src1InitialReady
    entriesNext(allocateIdx).src2Ready := allocCmd.src2InitialReady
    entryValidsNext(allocateIdx) := True
    
    // 如果分配的指令恰好被本周期的全局唤醒信号唤醒 (这是一个边缘但可能的情况)
    when(globalWakeupValid && allocUop.decoded.useArchSrc1 && allocUop.rename.physSrc1.idx === globalWakeupTag) {
      entriesNext(allocateIdx).src1Ready := True
    }
    when(globalWakeupValid && allocUop.decoded.useArchSrc2 && allocUop.rename.physSrc2.idx === globalWakeupTag) {
      entriesNext(allocateIdx).src2Ready := True
    }
  }

  // d. 应用Flush (最高优先级)
  when(io.flush) {
    entryValidsNext.foreach(_ := False)
  }

  // --- 步骤 4: 寄存器更新 (Register Update at Clock Edge) ---
  entries := entriesNext
  entryValids := entryValidsNext

  // --- Debug and Monitoring ---
  val currentValidCount = CountOne(entryValids)

  // Add periodic status logging (reduced frequency)
  when(currentValidCount > 0 && (issueRequestOh.orR || io.allocateIn.valid)) { // Only log when there's activity
    ParallaxSim.log(
      L"${idStr}: STATUS - ValidCount=${currentValidCount}, " :+
      L"CanAccept=${canAccept}, " :+
      L"CanIssue=${issueRequestOh.orR}"
    )
    
    // Log details of each valid entry
    for (i <- 0 until iqConfig.depth) {
      when(entryValids(i)) {
        ParallaxSim.log(
          L"${idStr}: ENTRY[${i}] - RobPtr=${entries(i).robPtr}, " :+
          L"PhysDest=${entries(i).physDest.idx}, " :+
          L"UseSrc1=${entries(i).useSrc1}, Src1Tag=${entries(i).src1Tag}, Src1Ready=${entries(i).src1Ready}, " :+
          L"UseSrc2=${entries(i).useSrc2}, Src2Tag=${entries(i).src2Tag}, Src2Ready=${entries(i).src2Ready}"
        )
      }
    }
  }

  ParallaxLogger.log(
    s"${idStr} Component (depth ${iqConfig.depth}, wakeup enabled, type ${iqConfig.uopEntryType().getClass.getSimpleName}) elaborated."
  )

  if (GenerationFlags.simulation.isEnabled) {
    val simCycleCount = Reg(UInt(32 bits)) init (0)
    simCycleCount := simCycleCount + 1
  }
}

object IssueQueueComponent {
  def IntIQ(pipelineConfig: PipelineConfig, depth: Int, id: Int = 0): IssueQueueComponent[IQEntryAluInt] = {
    val iqConf = IssueQueueConfig[IQEntryAluInt](
      pipelineConfig = pipelineConfig,
      depth = depth,
      exeUnitType = ExeUnitType.ALU_INT,
      uopEntryType = HardType(IQEntryAluInt(pipelineConfig)),
      usesSrc3 = false,
      name = "IntIQ"
    )
    new IssueQueueComponent(iqConf, id)
  }

  // def FpuIQ(
  //     pipelineConfig: PipelineConfig,
  //     depth: Int,
  //     usesSrc3: Boolean,
  //     id: Int = 0
  // ): IssueQueueComponent[IQEntryFpu] = {
  //   val iqConf = IssueQueueConfig[IQEntryFpu](
  //     pipelineConfig = pipelineConfig,
  //     depth = depth,
  //     exeUnitType = ExeUnitType.FPU_ADD_MUL_CVT_CMP,
  //     uopEntryType = HardType(IQEntryFpu(pipelineConfig, usesSrc3)),
  //     usesSrc3 = usesSrc3,
  //     name = "FpuIQ"
  //   )
  //   new IssueQueueComponent(iqConf, id)
  // }
}
