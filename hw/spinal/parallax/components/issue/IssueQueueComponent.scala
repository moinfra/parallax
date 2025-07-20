package parallax.components.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
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
  val enableLog = false
  val io = slave(IssueQueueComponentIo(iqConfig))
  val idStr = s"${iqConfig.name}-${id.toString()}"

  // ====================================================================
  //  State Area: Contains all state-holding registers.
  // ====================================================================
  val stateArea = new Area {
    val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
    val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))
  }

  // ====================================================================
  //  Pipeline Area: Defines the 2-stage structure for state updates.
  // ====================================================================
  val pipelineArea = new Area {
    val pipeline = new Pipeline()
    val s0_matchAndDecision = pipeline.newStage().setName("s0_MatchAndDecision")
    val s1_stateUpdate = pipeline.newStage().setName("s1_StateUpdate")
    pipeline.connect(s0_matchAndDecision, s1_stateUpdate)(Connection.M2S())

    // Stageables to pass all decisions and match results from S0 to S1
    val S0_ISSUE_OH = Stageable(Bits(iqConfig.depth bits))
    val S0_DO_ALLOCATE = Stageable(Bool())
    val S0_ALLOCATE_OH = Stageable(Bits(iqConfig.depth bits))
    val S0_ALLOCATE_CMD = Stageable(IqDispatchCmd(iqConfig.pipelineConfig))
    val S0_WAKEUP_SRC1_MATCH = Stageable(Vec(Bool(), iqConfig.depth))
    val S0_WAKEUP_SRC2_MATCH = Stageable(Vec(Bool(), iqConfig.depth))

  }

  // ====================================================================
  //  S0: Match and Decision Logic (Combinational)
  // ====================================================================
  val s0_logicArea = new Area {
    import pipelineArea._
    
    s0_matchAndDecision.valid := True // This pipeline runs every cycle

    // --- 1. Issue/Selection Logic (based on registered state) ---
    val entriesReadyToIssue = Vec.tabulate(iqConfig.depth) { i =>
      stateArea.entryValids(i) &&
      (!stateArea.entries(i).useSrc1 || stateArea.entries(i).src1Ready) &&
      (!stateArea.entries(i).useSrc2 || stateArea.entries(i).src2Ready)
    }
    val issueRequestMask = B(entriesReadyToIssue)
    val issueRequestOh = OHMasking.first(issueRequestMask)
    val issueIdx = OHToUInt(issueRequestOh)

    io.issueOut.valid := issueRequestMask.orR && !io.flush
    io.issueOut.payload := stateArea.entries(issueIdx)
    
    // --- 2. Allocation Logic ---
    val freeSlotsMask = stateArea.entryValids.map(!_).asBits
    val canAccept = freeSlotsMask.orR
    val allocateOh = OHMasking.first(freeSlotsMask)
    io.canAccept := canAccept && !io.flush
    val doAllocate = io.allocateIn.valid && canAccept && !io.flush
    
    // --- 3. Tag Matching Logic (uses S0 signals) ---
    val localWakeupValid = io.issueOut.fire && io.issueOut.payload.writesToPhysReg
    val localWakeupTag = io.issueOut.payload.physDest.idx
    
    val globalWakeupValid = io.wakeupIn.valid // Sample external IO in S0
    val globalWakeupTag = io.wakeupIn.payload.physRegIdx

    val src1WakeupMatch = Vec(Bool(), iqConfig.depth)
    val src2WakeupMatch = Vec(Bool(), iqConfig.depth)

    for (i <- 0 until iqConfig.depth) {
      val entry = stateArea.entries(i)
      val isValid = stateArea.entryValids(i)
      val s1LocalWakeup  = localWakeupValid  && isValid && entry.useSrc1 && !entry.src1Ready && (entry.src1Tag === localWakeupTag)
      val s1GlobalWakeup = globalWakeupValid && isValid && entry.useSrc1 && !entry.src1Ready && (entry.src1Tag === globalWakeupTag)
      src1WakeupMatch(i) := s1LocalWakeup || s1GlobalWakeup

      val s2LocalWakeup  = localWakeupValid  && isValid && entry.useSrc2 && !entry.src2Ready && (entry.src2Tag === localWakeupTag)
      val s2GlobalWakeup = globalWakeupValid && isValid && entry.useSrc2 && !entry.src2Ready && (entry.src2Tag === globalWakeupTag)
      src2WakeupMatch(i) := s2LocalWakeup || s2GlobalWakeup
    }
    
    // --- 4. Pass all results to S1 via Stageables ---
    s0_matchAndDecision(S0_ISSUE_OH) := issueRequestOh
    s0_matchAndDecision(S0_DO_ALLOCATE) := doAllocate
    s0_matchAndDecision(S0_ALLOCATE_OH) := allocateOh
    s0_matchAndDecision(S0_ALLOCATE_CMD) := io.allocateIn.payload // Latch the payload
    s0_matchAndDecision(S0_WAKEUP_SRC1_MATCH) := src1WakeupMatch
    s0_matchAndDecision(S0_WAKEUP_SRC2_MATCH) := src2WakeupMatch
  }

  // ====================================================================
  //  S1: State Update Logic (Sequential)
  // ====================================================================
  val s1_updateArea = new Area {
    import pipelineArea._

    // --- 1. Use CombInit to ensure safe default assignment ---
    val entriesNext = CombInit(stateArea.entries)
    val entryValidsNext = CombInit(stateArea.entryValids)
    
    // --- 2. Get all decisions and match results from S1's input Stageables ---
    val issueOh     = s1_stateUpdate(S0_ISSUE_OH)
    val doAllocate  = s1_stateUpdate(S0_DO_ALLOCATE)
    val allocateOh  = s1_stateUpdate(S0_ALLOCATE_OH)
    val allocateCmd = s1_stateUpdate(S0_ALLOCATE_CMD)
    val wakeupSrc1  = s1_stateUpdate(S0_WAKEUP_SRC1_MATCH)
    val wakeupSrc2  = s1_stateUpdate(S0_WAKEUP_SRC2_MATCH)

    // --- 3. Apply updates in a defined priority order ---
    
    // Wakeup logic has the lowest priority, modifying ready bits
    for(i <- 0 until iqConfig.depth) {
      when(wakeupSrc1(i)) { entriesNext(i).src1Ready := True }
      when(wakeupSrc2(i)) { entriesNext(i).src2Ready := True }
    }

    // Issue logic can clear a valid bit
    when(issueOh.orR) {
      val issueIdx = OHToUInt(issueOh)
      entryValidsNext(issueIdx) := False
    }

    // Allocation logic can override a full entry
    when(doAllocate) {
      val allocateIdx = OHToUInt(allocateOh)
      entriesNext(allocateIdx).initFrom(allocateCmd.uop, allocateCmd.uop.robPtr)
      entriesNext(allocateIdx).src1Ready := allocateCmd.src1InitialReady
      entriesNext(allocateIdx).src2Ready := allocateCmd.src2InitialReady
      entryValidsNext(allocateIdx) := True
    }

    // Flush has the highest priority
    when(io.flush) {
      entryValidsNext.foreach(_ := False)
    }
    
    // --- 4. Final Register Update ---
    when(s1_stateUpdate.isFiring) {
      stateArea.entries := entriesNext
      stateArea.entryValids := entryValidsNext
    }
  }

  pipelineArea.pipeline.build()
  // ====================================================================
  //  Logging and Debugging Area
  // ====================================================================
  val debugArea = new Area {
    import s0_logicArea._

    when(io.issueOut.fire) {
      if(enableLog) ParallaxSim.log(
        L"${idStr}: ISSUED entry at index ${issueIdx}, " :+
        L"RobPtr=${stateArea.entries(issueIdx).robPtr}, PhysDest=${stateArea.entries(issueIdx).physDest.idx}"
      )
    }
    when(io.allocateIn.fire) {
      ParallaxSim.log(
        L"${idStr}: ALLOCATED entry at index ${OHToUInt(allocateOh)}, " :+
        L"RobPtr=${io.allocateIn.payload.uop.robPtr}, PhysDest=${io.allocateIn.payload.uop.rename.physDest.idx}"
      )
    }
    when(localWakeupValid) {
      ParallaxSim.log(L"${idStr}: LOCAL WAKEUP generated for PhysReg=${localWakeupTag}")
    }
    when(globalWakeupValid) {
      ParallaxSim.log(L"${idStr}: GLOBAL WAKEUP received for PhysReg=${globalWakeupTag}")
    }
    for(i <- 0 until iqConfig.depth) {
        when(src1WakeupMatch(i)) {ParallaxSim.log(L"${idStr}: WAKEUP Src1 for entry ${i}, RobPtr=${stateArea.entries(i).robPtr}")}
        when(src2WakeupMatch(i)) {ParallaxSim.log(L"${idStr}: WAKEUP Src2 for entry ${i}, RobPtr=${stateArea.entries(i).robPtr}")}
    }
    
    val currentValidCount = CountOne(stateArea.entryValids)
    when(currentValidCount > 0 && (issueRequestOh.orR || io.allocateIn.valid)) {
      ParallaxSim.log(
        L"${idStr}: STATUS - ValidCount=${currentValidCount}, " :+
        L"CanAccept=${canAccept}, CanIssue=${issueRequestOh.orR}"
      )
      for (i <- 0 until iqConfig.depth) {
        when(stateArea.entryValids(i)) {
          ParallaxSim.log(
            L"${idStr}: ENTRY[${i}] - RobPtr=${stateArea.entries(i).robPtr}, " :+
            L"PhysDest=${stateArea.entries(i).physDest.idx}, " :+
            L"UseSrc1=${stateArea.entries(i).useSrc1}, Src1Tag=${stateArea.entries(i).src1Tag}, Src1Ready=${stateArea.entries(i).src1Ready}, " :+
            L"UseSrc2=${stateArea.entries(i).useSrc2}, Src2Tag=${stateArea.entries(i).src2Tag}, Src2Ready=${stateArea.entries(i).src2Ready}"
          )
        }
      }
    }

    if (spinal.core.GenerationFlags.simulation.isEnabled) {
      val simCycleCount = Reg(UInt(32 bits)) init (0)
      simCycleCount := simCycleCount + 1
    }
  }

  ParallaxLogger.log(
    s"${idStr} Component (depth ${iqConfig.depth}, wakeup enabled, type ${iqConfig.uopEntryType().getClass.getSimpleName}) elaborated."
  )
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
}
