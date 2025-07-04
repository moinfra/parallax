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

  // State Registers: Reflect the state at the beginning of the cycle.
  val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
  val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))

  // --- 1. Combinational Logic for Current Cycle Decisions ---
  // All decisions (issue, canAccept) are based *only* on the registered state.

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
  // 【核心修正】: Search for a free slot based on the CURRENT `entryValids`, not `entryValidsNext`.
  val freeSlotsMask = entryValids.map(!_).asBits
  val canAccept = freeSlotsMask.orR
  val allocateIdx = OHToUInt(OHMasking.first(freeSlotsMask))
  
  // --- 2. Drive IO Outputs ---
  io.canAccept := canAccept && !io.flush
  io.issueOut.valid := issueRequestOh.orR && !io.flush
  io.issueOut.payload := entries(issueIdx)

  // Add logging for issue events
  when(io.issueOut.fire) {
    ParallaxSim.log(
      L"${idStr}: ISSUED entry at index ${issueIdx}, " :+
      L"RobPtr=${entries(issueIdx).robPtr}, " :+
      L"PhysDest=${entries(issueIdx).physDest.idx}, " :+
      L"WritesPhys=${entries(issueIdx).writesToPhysReg}, " :+
      L"Src1Ready=${entries(issueIdx).src1Ready}, " :+
      L"Src2Ready=${entries(issueIdx).src2Ready}"
    )
  }

  // --- 3. Combinational Calculation of Next Cycle's State ---
  val entriesNext = CombInit(entries)
  val entryValidsNext = CombInit(entryValids)

  // a. Update based on Issue: Clear the valid bit of the issued entry.
  when(io.issueOut.fire) {
    entryValidsNext(issueIdx) := False
  }

  // b. Update based on Allocation: Fill the chosen free slot.
  when(io.allocateIn.valid && io.canAccept && !io.flush) {
    // Initialize the entry with the uop data
    entriesNext(allocateIdx).initFrom(io.allocateIn.payload.uop, io.allocateIn.payload.uop.robPtr)
    
    // Override the src1Ready and src2Ready with the initial ready states from dispatch
    entriesNext(allocateIdx).src1Ready := io.allocateIn.payload.src1InitialReady
    entriesNext(allocateIdx).src2Ready := io.allocateIn.payload.src2InitialReady
    
    entryValidsNext(allocateIdx) := True
    
    // Add detailed logging for allocation
    ParallaxSim.log(
      L"${idStr}: ALLOCATED entry at index ${allocateIdx}, " :+
      L"RobPtr=${io.allocateIn.payload.uop.robPtr}, " :+
      L"PhysDest=${io.allocateIn.payload.uop.rename.physDest.idx}, " :+
      L"WritesPhys=${io.allocateIn.payload.uop.rename.writesToPhysReg}, " :+
      L"Src1Ready=${io.allocateIn.payload.src1InitialReady}, " :+
      L"Src2Ready=${io.allocateIn.payload.src2InitialReady}"
    )
  }
  
  // c. Update based on Wakeup events
  val localWakeupValid = io.issueOut.fire && io.issueOut.payload.writesToPhysReg
  val localWakeupTag = io.issueOut.payload.physDest.idx
  
  val globalWakeupValid = io.wakeupIn.valid
  val globalWakeupTag = io.wakeupIn.payload.physRegIdx

  // Add logging for wakeup events
  when(localWakeupValid) {
    ParallaxSim.log(
      L"${idStr}: LOCAL WAKEUP generated for PhysReg=${localWakeupTag} " :+
      L"from issued RobPtr=${io.issueOut.payload.robPtr}"
    )
  }
  
  when(globalWakeupValid) {
    ParallaxSim.log(
      L"${idStr}: GLOBAL WAKEUP received for PhysReg=${globalWakeupTag}"
    )
  }

  for (i <- 0 until iqConfig.depth) {
    val currentEntry = entries(i)
    val nextEntry = entriesNext(i)

    // Only update entries that will remain valid in the next cycle.
    // This prevents wasting logic on entries that are being cleared.
    when(entryValidsNext(i)) {
      // Wakeup logic only applies if the ready bit isn't already set.
      when(!currentEntry.src1Ready) {
        val s1LocalWakeup  = localWakeupValid  && currentEntry.src1Tag === localWakeupTag
        val s1GlobalWakeup = globalWakeupValid && currentEntry.src1Tag === globalWakeupTag
        when(s1LocalWakeup || s1GlobalWakeup) {
          nextEntry.src1Ready := True
          ParallaxSim.log(
            L"${idStr}: WAKEUP Src1 for entry ${i}, " :+
            L"RobPtr=${currentEntry.robPtr}, " :+
            L"Src1Tag=${currentEntry.src1Tag}, " :+
            L"Local=${s1LocalWakeup}, Global=${s1GlobalWakeup}"
          )
        }
      }
      when(!currentEntry.src2Ready) {
        val s2LocalWakeup  = localWakeupValid  && currentEntry.src2Tag === localWakeupTag
        val s2GlobalWakeup = globalWakeupValid && currentEntry.src2Tag === globalWakeupTag
        when(s2LocalWakeup || s2GlobalWakeup) {
          nextEntry.src2Ready := True
          ParallaxSim.log(
            L"${idStr}: WAKEUP Src2 for entry ${i}, " :+
            L"RobPtr=${currentEntry.robPtr}, " :+
            L"Src2Tag=${currentEntry.src2Tag}, " :+
            L"Local=${s2LocalWakeup}, Global=${s2GlobalWakeup}"
          )
        }
      }
    }
  }
  
  // d. Update based on Flush (Highest Priority)
  when(io.flush) {
    entryValidsNext.foreach(_ := False)
  }

  // --- 4. Register Update at Clock Edge ---
  entries := entriesNext
  entryValids := entryValidsNext

  // --- Debug and Monitoring ---
  val currentValidCount = CountOne(entryValids)

  // Add periodic status logging
  when(True) { // This will log every cycle
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
