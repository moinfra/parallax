// filename: hw/spinal/parallax/components/issue/IssueQueueComponent.scala
package parallax.components.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.execute.WakeupPayload
import parallax.utilities.{ParallaxLogger, ParallaxSim}
import parallax.issue.IqDispatchCmd

case class IssueQueueComponentIo[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry]
) extends Bundle
    with IMasterSlave {
  // From Dispatch: Command to allocate a new instruction
  val allocateIn = Flow(IqDispatchCmd(iqConfig.pipelineConfig))
  // To Dispatch: Indicates if the IQ can accept a new instruction
  val canAccept = Bool()
  // To Execute: A ready-to-issue instruction
  val issueOut = Stream(iqConfig.getIQEntry())
  // From WakeupService: Global wakeup bus for completed instructions
  val wakeupIn = Flow(WakeupPayload(iqConfig.pipelineConfig))
  // From Pipeline Control: Flush signal
  val flush = Bool()

  override def asMaster(): Unit = {
    master(allocateIn, wakeupIn)
    in(canAccept)
    out(flush)
    slave(issueOut)
  }
}

class IssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val id: Int = 0
) extends Component {
  val enableLog = true // Enable logging for simulation
  val io = slave(IssueQueueComponentIo(iqConfig))
  val idStr = s"${iqConfig.name}-${id.toString()}"

  // ====================================================================
  //  State Area: All state-holding registers.
  // ====================================================================
  val stateArea = new Area {
    // Physical storage for IQ entries
    val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
    val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))
    // Note: srcReady bits are part of the IQEntry bundle itself
  }

  // ====================================================================
  //  Pipeline Area: Defines the 2-stage structure for all logic.
  // ====================================================================
  val pipelineArea = new Area {
    val pipeline = new Pipeline()
    val s0_MatchAndDecision = pipeline.newStage().setName("s0_MatchAndDecision")
    val s1_StateUpdate = pipeline.newStage().setName("s1_StateUpdate")
    pipeline.connect(s0_MatchAndDecision, s1_StateUpdate)(Connection.M2S())

    // Stageables to pass all decisions from S0 to S1
    val S0_ISSUE_OH = Stageable(Bits(iqConfig.depth bits))
    val S0_DO_ALLOCATE = Stageable(Bool())
    val S0_ALLOCATE_OH = Stageable(Bits(iqConfig.depth bits))
    val S0_ALLOCATE_CMD = Stageable(IqDispatchCmd(iqConfig.pipelineConfig))
    val S0_WAKEUP_SRC1_MATCHES = Stageable(Bits(iqConfig.depth bits))
    val S0_WAKEUP_SRC2_MATCHES = Stageable(Bits(iqConfig.depth bits))
  }

  // ====================================================================
  //  S0: Match and Decision Logic (Combinational)
  //  Reads state from previous cycle, makes decisions for the next cycle.
  // ====================================================================
  val s0_logicArea = new Area {
    import pipelineArea._
    
    // S0 is always active
    s0_MatchAndDecision.valid := True

    // --- 1. Issue/Selection Logic (based on registered state) ---
    val entriesReadyToIssue = Vec.tabulate(iqConfig.depth) { i =>
      stateArea.entryValids(i) &&
      (!stateArea.entries(i).useSrc1 || stateArea.entries(i).src1Ready) &&
      (!stateArea.entries(i).useSrc2 || stateArea.entries(i).src2Ready)
    }
    val issueRequestMask = B(entriesReadyToIssue)
    // Simple oldest-first picker
    val issueRequestOh = OHMasking.first(issueRequestMask)
    val issueIdx = OHToUInt(issueRequestOh)
    
    // Drive issue output based on this cycle's decision
    io.issueOut.valid := issueRequestMask.orR && !io.flush
    io.issueOut.payload := stateArea.entries(issueIdx)
    
    // --- 2. Allocation Logic ---
    val freeSlotsMask = stateArea.entryValids.map(!_).asBits
    val canAccept = freeSlotsMask.orR
    // Simple oldest-first allocator
    val allocateOh = OHMasking.first(freeSlotsMask)
    
    io.canAccept := canAccept && !io.flush
    val doAllocate = io.allocateIn.valid && canAccept && !io.flush
    
    // --- 3. Wakeup Matching Logic (with Lookahead View) ---
    
    // Create a "lookahead view" of the IQ state as it will be at the *end* of this cycle
    // This view considers allocations happening in this same cycle.
    val view_isValid   = Vec(Bool(), iqConfig.depth)
    val view_useSrc1   = Vec(Bool(), iqConfig.depth)
    val view_src1Ready = Vec(Bool(), iqConfig.depth)
    val view_src1Tag   = Vec(UInt(iqConfig.pipelineConfig.physGprIdxWidth), iqConfig.depth)
    val view_useSrc2   = Vec(Bool(), iqConfig.depth)
    val view_src2Ready = Vec(Bool(), iqConfig.depth)
    val view_src2Tag   = Vec(UInt(iqConfig.pipelineConfig.physGprIdxWidth), iqConfig.depth)

    for(i <- 0 until iqConfig.depth) {
      // Is an allocation happening to this specific slot `i`?
      val isAllocatedToThisSlot = doAllocate && allocateOh(i)
      val allocCmd = io.allocateIn.payload
      val allocUop = allocCmd.uop

      // Default to the current registered state
      view_isValid(i)   := stateArea.entryValids(i)
      view_useSrc1(i)   := stateArea.entries(i).useSrc1
      view_src1Ready(i) := stateArea.entries(i).src1Ready
      view_src1Tag(i)   := stateArea.entries(i).src1Tag
      view_useSrc2(i)   := stateArea.entries(i).useSrc2
      view_src2Ready(i) := stateArea.entries(i).src2Ready
      view_src2Tag(i)   := stateArea.entries(i).src2Tag

      // If an allocation is happening, override the view with the new instruction's info
      when(isAllocatedToThisSlot) {
        view_isValid(i)   := True
        view_useSrc1(i)   := allocUop.decoded.useArchSrc1
        view_src1Ready(i) := allocCmd.src1InitialReady // Use pre-calculated readiness
        view_src1Tag(i)   := allocUop.rename.physSrc1.idx
        view_useSrc2(i)   := allocUop.decoded.useArchSrc2
        view_src2Ready(i) := allocCmd.src2InitialReady // Use pre-calculated readiness
        view_src2Tag(i)   := allocUop.rename.physSrc2.idx
      }
    }

    val globalWakeupValid = io.wakeupIn.valid
    val globalWakeupTag = io.wakeupIn.payload.physRegIdx
    val src1WakeupMatches = Bits(iqConfig.depth bits)
    val src2WakeupMatches = Bits(iqConfig.depth bits)
    
    src1WakeupMatches.clearAll()
    src2WakeupMatches.clearAll()

    when(globalWakeupValid) {
      for (i <- 0 until iqConfig.depth) {
        // Match against the lookahead view to correctly handle allocate/wakeup race condition
        val matchSrc1 = view_isValid(i) && view_useSrc1(i) && !view_src1Ready(i) && (view_src1Tag(i) === globalWakeupTag)
        when(matchSrc1) { src1WakeupMatches(i) := True }

        val matchSrc2 = view_isValid(i) && view_useSrc2(i) && !view_src2Ready(i) && (view_src2Tag(i) === globalWakeupTag)
        when(matchSrc2) { src2WakeupMatches(i) := True }
      }
    }
    
    // --- 4. Pass results to S1 via Stageables ---
    s0_MatchAndDecision(S0_ISSUE_OH) := issueRequestOh
    s0_MatchAndDecision(S0_DO_ALLOCATE) := doAllocate
    s0_MatchAndDecision(S0_ALLOCATE_OH) := allocateOh
    s0_MatchAndDecision(S0_ALLOCATE_CMD) := io.allocateIn.payload
    s0_MatchAndDecision(S0_WAKEUP_SRC1_MATCHES) := src1WakeupMatches
    s0_MatchAndDecision(S0_WAKEUP_SRC2_MATCHES) := src2WakeupMatches
  }

  // ====================================================================
  //  S1: State Update Logic (Sequential)
  //  Reads decisions from S0, computes next state, and updates registers.
  // ====================================================================
  val s1_updateArea = new Area {
    import pipelineArea._

    // Get all decisions from S1's input Stageables
    val issueOh     = s1_StateUpdate(S0_ISSUE_OH)
    val doAllocate  = s1_StateUpdate(S0_DO_ALLOCATE)
    val allocateOh  = s1_StateUpdate(S0_ALLOCATE_OH)
    val allocateCmd = s1_StateUpdate(S0_ALLOCATE_CMD)
    val wakeupSrc1  = s1_StateUpdate(S0_WAKEUP_SRC1_MATCHES)
    val wakeupSrc2  = s1_StateUpdate(S0_WAKEUP_SRC2_MATCHES)

    // --- Create a temporary, modifiable copy of the state for the next cycle ---
    val entriesNext = Vec(iqConfig.getIQEntry(), iqConfig.depth)
    val entryValidsNext = Vec(Bool(), iqConfig.depth)
    for(i <- 0 until iqConfig.depth) {
      entriesNext(i) := stateArea.entries(i)
      entryValidsNext(i) := stateArea.entryValids(i)
    }

    // --- Apply updates in a defined, non-overlapping priority order ---

    // Priority 1: Issue - A vacated entry becomes invalid.
    when(issueOh.orR) {
      val issueIdx = OHToUInt(issueOh)
      entryValidsNext(issueIdx) := False
    }

    // Priority 2: Wakeup - This modifies the ready bits of existing or newly allocated entries.
    for(i <- 0 until iqConfig.depth) {
      when(wakeupSrc1(i)) { entriesNext(i).src1Ready := True }
      when(wakeupSrc2(i)) { entriesNext(i).src2Ready := True }
    }

    // Priority 3: Allocate - An allocated entry overwrites everything in its slot.
    // This is applied *after* wakeup so that a new entry can be woken up in the same cycle it is allocated.
    when(doAllocate) {
      val allocateIdx = OHToUInt(allocateOh)
      entryValidsNext(allocateIdx) := True
      entriesNext(allocateIdx).initFrom(allocateCmd.uop, allocateCmd.uop.robPtr)
      // Set the *initial* ready state from the dispatch command.
      // This is OR'd with the wakeup logic implicitly because wakeup modifies the same `entriesNext`.
      entriesNext(allocateIdx).src1Ready := allocateCmd.src1InitialReady
      entriesNext(allocateIdx).src2Ready := allocateCmd.src2InitialReady
    }
    
    // --- Final Register Update ---
    when(s1_StateUpdate.isFiring) {
      stateArea.entries := entriesNext
      stateArea.entryValids := entryValidsNext
    }

    // --- Highest Priority: Flush ---
    // This overrides everything, including pending updates.
    when(io.flush) {
      stateArea.entryValids.foreach(_ := False)
    }
  }

  pipelineArea.pipeline.build()
  
  // ====================================================================
  //  Logging and Debugging Area for Simulation
  // ====================================================================
  val debugArea = new Area {
    import s0_logicArea._
    import pipelineArea._

    when(s0_MatchAndDecision.isFiring) {
      when(io.issueOut.fire) {
        ParallaxSim.log(
          L"${idStr}: S0 -> ISSUING entry at index ${issueIdx}, " :+
          L"RobPtr=${io.issueOut.payload.robPtr}, PhysDest=${io.issueOut.payload.physDest.idx}"
        )
      }
      when(doAllocate) {
        ParallaxSim.log(
          L"${idStr}: S0 -> ALLOCATING entry at index ${OHToUInt(allocateOh)}, " :+
          L"RobPtr=${io.allocateIn.payload.uop.robPtr}, " :+
          L"s1_initial_ready=${io.allocateIn.payload.src1InitialReady}, s2_initial_ready=${io.allocateIn.payload.src2InitialReady}"
        )
      }
      when(globalWakeupValid) {
        ParallaxSim.log(L"${idStr}: S0 -> WAKEUP BUS active for PhysReg=${globalWakeupTag}")
        for(i <- 0 until iqConfig.depth) {
          when(src1WakeupMatches(i)) {ParallaxSim.log(L"${idStr}: S0 ->   Wakeup match for Src1 of entry ${i}")}
          when(src2WakeupMatches(i)) {ParallaxSim.log(L"${idStr}: S0 ->   Wakeup match for Src2 of entry ${i}")}
        }
      }
    }
    val currentValidCount = CountOne(stateArea.entryValids)

    when(s1_StateUpdate.isFiring) {
        ParallaxSim.log(L"--- ${idStr}: S1 -> STATE UPDATE ---")
        ParallaxSim.log(L"${idStr}: S1 -> Current valid count = ${currentValidCount}")
        for(i <- 0 until iqConfig.depth){
            when(stateArea.entryValids(i)){
                ParallaxSim.log(
                    L"${idStr}: S1 -> Entry[${i}] state: " :+
                    L"RobPtr=${stateArea.entries(i).robPtr}, " :+
                    L"PDEST=${stateArea.entries(i).physDest.idx}, " :+
                    L"s1_tag=${stateArea.entries(i).src1Tag}(${stateArea.entries(i).src1Ready}), " :+
                    L"s2_tag=${stateArea.entries(i).src2Tag}(${stateArea.entries(i).src2Ready})"
                )
            }
        }
    }
  }
  ParallaxLogger.log(s"[${idStr}] New pipelined component elaborated successfully.")
}
