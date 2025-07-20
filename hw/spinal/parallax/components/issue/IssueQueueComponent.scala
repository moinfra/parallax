package parallax.components.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.execute.{WakeupPayload, WakeupService}
import parallax.utilities._
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
  //  State Area
  // ====================================================================
  val stateArea = new Area {
    val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
    val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))

    // +++ NEW: Register for pipelining the local wakeup signal +++
    val issuedEntryPayload = Reg(iqConfig.getIQEntry())
    val issuedEntryValid = Reg(Bool()) init(False)
  }

  // ====================================================================
  //  Pipeline Area
  // ====================================================================
  val pipelineArea = new Area {
    val pipeline = new Pipeline()
    val s0_match = pipeline.newStage().setName("s0_Match")
    val s1_update = pipeline.newStage().setName("s1_Update")
    pipeline.connect(s0_match, s1_update)(Connection.M2S())

    val WAKEUP_SRC1_MATCH = Stageable(Vec(Bool(), iqConfig.depth))
    val WAKEUP_SRC2_MATCH = Stageable(Vec(Bool(), iqConfig.depth))
    
  }

  // ====================================================================
  //  Combinational & S0 Logic Area
  // ====================================================================
  val combinationalArea = new Area {
    // --- 1. Issue Logic (based on registered state) ---
    val entriesReadyToIssue = Vec.tabulate(iqConfig.depth) { i =>
      stateArea.entryValids(i) &&
      (!stateArea.entries(i).useSrc1 || stateArea.entries(i).src1Ready) &&
      (!stateArea.entries(i).useSrc2 || stateArea.entries(i).src2Ready)
    }
    val issueRequestMask = B(entriesReadyToIssue)
    val issueRequestOh = OHMasking.first(issueRequestMask)
    val issueIdx = OHToUInt(issueRequestOh)

    io.issueOut.valid := issueRequestOh.orR && !io.flush
    io.issueOut.payload := stateArea.entries(issueIdx)

    // --- 2. Allocation Logic ---
    val freeSlotsMask = stateArea.entryValids.map(!_).asBits
    val canAccept = freeSlotsMask.orR
    val allocateIdx = OHToUInt(OHMasking.first(freeSlotsMask))
    io.canAccept := canAccept && !io.flush

    // --- 3. Pipelined Local Wakeup Logic ---
    // At the end of the cycle, latch the issued instruction's info.
    stateArea.issuedEntryValid := io.issueOut.fire && io.issueOut.payload.writesToPhysReg
    stateArea.issuedEntryPayload := io.issueOut.payload

    // --- 4. Tag Matching (Wakeup Stage 1) ---
    // *** CRITICAL CHANGE: Use the *registered* local wakeup info from the PREVIOUS cycle ***
    val localWakeupValid = stateArea.issuedEntryValid
    val localWakeupTag = stateArea.issuedEntryPayload.physDest.idx
    
    val globalWakeupValid = io.wakeupIn.valid
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

    // --- 5. Drive S0 of the pipeline ---
    pipelineArea.s0_match.valid := True
    pipelineArea.s0_match(pipelineArea.WAKEUP_SRC1_MATCH) := src1WakeupMatch
    pipelineArea.s0_match(pipelineArea.WAKEUP_SRC2_MATCH) := src2WakeupMatch
  }

  // ====================================================================
  //  Sequential Logic Area (Driven by S1)
  // ====================================================================
  val sequentialArea = new Area {
    val entriesNext = CombInit(stateArea.entries)
    val entryValidsNext = CombInit(stateArea.entryValids)

    val s1_wakeupSrc1 = pipelineArea.s1_update(pipelineArea.WAKEUP_SRC1_MATCH)
    val s1_wakeupSrc2 = pipelineArea.s1_update(pipelineArea.WAKEUP_SRC2_MATCH)

    for(i <- 0 until iqConfig.depth) {
      when(s1_wakeupSrc1(i)) { entriesNext(i).src1Ready := True }
      when(s1_wakeupSrc2(i)) { entriesNext(i).src2Ready := True }
    }

    when(io.issueOut.fire) {
      entryValidsNext(combinationalArea.issueIdx) := False
    }

    when(io.allocateIn.fire) {
      entriesNext(combinationalArea.allocateIdx).initFrom(io.allocateIn.payload.uop, io.allocateIn.payload.uop.robPtr)
      entriesNext(combinationalArea.allocateIdx).src1Ready := io.allocateIn.payload.src1InitialReady
      entriesNext(combinationalArea.allocateIdx).src2Ready := io.allocateIn.payload.src2InitialReady
      entryValidsNext(combinationalArea.allocateIdx) := True
    }

    when(io.flush) {
      entryValidsNext.foreach(_ := False)
      stateArea.issuedEntryValid := False // Also flush the pipelined wakeup register
    }

    when(pipelineArea.s1_update.isFiring) {
      stateArea.entries := entriesNext
      stateArea.entryValids := entryValidsNext
    }
  }

  // Finalize the pipeline construction
  pipelineArea.pipeline.build()

  // ====================================================================
  //  Logging and Debugging Area
  // ====================================================================
  val debugArea = new Area {
    // We can reference combinational signals for logging as they reflect the current cycle's decisions
    import combinationalArea._

    when(io.issueOut.fire) {
      if(enableLog) ParallaxSim.log(
        L"${idStr}: ISSUED entry at index ${issueIdx}, " :+
        L"RobPtr=${stateArea.entries(issueIdx).robPtr}, PhysDest=${stateArea.entries(issueIdx).physDest.idx}"
      )
    }
    when(io.allocateIn.fire) {
      ParallaxSim.log(
        L"${idStr}: ALLOCATED entry at index ${allocateIdx}, " :+
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

    if (GenerationFlags.simulation.isEnabled) {
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
