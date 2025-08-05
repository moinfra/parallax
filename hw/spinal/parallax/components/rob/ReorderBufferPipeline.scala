package parallax.components.rob

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._ // 核心：导入流水线库
import parallax.common._
import parallax.components.decode._
import parallax.utilities.{Formattable, ParallaxLogger, ParallaxSim}

// Data structure for the Allocation pipeline register.
case class AllocationInfo[RU <: Data with Formattable with HasRobPtr](robConfig: ROBConfig[RU]) extends Bundle {
  val valid = Bool()
  val uopIn = robConfig.uopType()
  val pcIn = UInt(robConfig.pcWidth)
  val robPtr = UInt(robConfig.robPtrWidth)
}

case class WritebackInfo[RU <: Data with Formattable with HasRobPtr](robConfig: ROBConfig[RU]) extends Bundle {
  val valid                 = Bool()
  val robPtr                = UInt(robConfig.robPtrWidth)
  val isTaken               = Bool()
  val isMispredictedBranch  = Bool()
  val targetPc              = UInt(robConfig.pcWidth)
  val result                = Bits(robConfig.pcWidth)
  val exceptionOccurred     = Bool()
  val exceptionCodeIn       = UInt(robConfig.exceptionCodeWidth)
}

// NOTE: These case classes are kept for IO compatibility but internal data passing uses Stageables.
// Flush pipeline stages will have their own internal data passing logic.

class ReorderBufferPipeline[RU <: Data with Formattable with HasRobPtr](val config: ROBConfig[RU]) extends Component {
  val enableLog  = false
  val verboseLog = false // Control detailed per-cycle/per-entry logs

  ParallaxLogger.log(
    s"Creating ReorderBuffer with MODERN Pipeline API. Config: ${config.format().mkString("")}"
  )
  val io = slave(ROBIo(config))

  // ==========================================================================================
  // --- 1. Core State & Storage ---
  // ==========================================================================================

  private val payloadsMem           = Mem(ROBPayload(config), config.robDepth) 
  private val statusesReg           = Vec(Reg(ROBStatus(config)), config.robDepth)
  statusesReg.foreach(_.init(ROBStatus(config).setDefault(setGenBitFalse = true)))

  private val allocPtrReg           = Reg(UInt(config.robPtrWidth)) init(0)
  private val commitPtrReg          = Reg(UInt(config.robPtrWidth)) init(0)
  private val freeSlotCountReg      = Reg(UInt(log2Up(config.robDepth + 1) bits)) init(config.robDepth)

  private val statusesNext          = CombInit(statusesReg)

  // ==========================================================================================
  // --- 2. Pipeline Definitions ---
  // ==========================================================================================

  private val logic = new Area {
    val s0_numAllocated       = UInt(log2Up(config.allocateWidth + 1) bits)
    val numToCommit           = UInt(log2Up(config.commitWidth + 1) bits)
    val doApplyFlushState     = Bool()
    val isFlushing            = Bool()

    private val flushPipe = new Pipeline {
      private val S0_Detect, S1_Propagate, S2_Apply = newStage()
      connect(S0_Detect, S1_Propagate)(Connection.M2S())
      connect(S1_Propagate, S2_Apply)(Connection.M2S())

      S0_Detect.valid := io.flush.valid && (io.flush.payload.reason === FlushReason.FULL_FLUSH)
      when(S0_Detect.isFiring)    { report(L"[ROB_FLUSH_S0] DETECT: Detected FULL_FLUSH request.") }
      when(S1_Propagate.isFiring) { report(L"[ROB_FLUSH_S1] PROPAGATE: Flushing signal is in flight.") }
      
      private val flushApplied = S2_Apply.isFiring
      doApplyFlushState := flushApplied
      when(flushApplied) {
        report(L"[ROB_FLUSH_S2] APPLY: Applying FULL_FLUSH state.")
        for (k <- 0 until config.robDepth) {
          statusesNext(k).setDefault(setGenBitFalse = true)
        }
      }

      isFlushing := S0_Detect.valid || S1_Propagate.valid || S2_Apply.valid
      build()
    }
    io.flushed := doApplyFlushState

    private val allocLogic = new Area {
      val s0_will_fire = Vec(Bool(), config.allocateWidth)

      private val allocPipes = List.fill(config.allocateWidth)(new Pipeline {})
      allocPipes.zipWithIndex.foreach { case (p, i) =>
        // Define stages and stageables within the context of pipeline instance 'p'
        val S0_Arbitrate = p.newStage().setName(s"Alloc_${i}_S0")
        val S1_Write     = p.newStage().setName(s"Alloc_${i}_S1")
        p.connect(S0_Arbitrate, S1_Write)(Connection.M2S())

        val UOP_IN  = Stageable(config.uopType())
        val PC_IN   = Stageable(UInt(config.pcWidth))
        val ROB_PTR = Stageable(UInt(config.robPtrWidth))

        // --- S0 Logic ---
        val numPrevSlotsFiring = if (i == 0) U(0) else CountOne(s0_will_fire.take(i))
        val spaceAvailable = (freeSlotCountReg - numPrevSlotsFiring) > 0
        val allocPort = io.allocate(i)

        S0_Arbitrate.valid := allocPort.valid && !isFlushing
        allocPort.ready    := S0_Arbitrate.isReady && spaceAvailable && !isFlushing
        S0_Arbitrate.haltWhen(!spaceAvailable)
        s0_will_fire(i) := S0_Arbitrate.isFiring

        val robPtrForThisSlot = allocPtrReg + numPrevSlotsFiring
        allocPort.robPtr := robPtrForThisSlot
        io.canAllocate(i) := spaceAvailable && !isFlushing

        S0_Arbitrate(UOP_IN)  := allocPort.uopIn
        S0_Arbitrate(PC_IN)   := allocPort.pcIn
        S0_Arbitrate(ROB_PTR) := robPtrForThisSlot

        if (enableLog && verboseLog) when(S0_Arbitrate.isFiring) {
          report(L"[ROB_ALLOC_S0-FIRING] Port[${i}]: Granting robPtr=${robPtrForThisSlot}")
        }

        // --- S1 Logic ---
        S1_Write.flushIt(isFlushing)
        when(S1_Write.isFiring) {
          val physIdx = S1_Write(ROB_PTR).resize(config.robPhysIdxWidth)
          
          val newPayload = ROBPayload(config).allowOverride
          newPayload.uop      := S1_Write(UOP_IN)
          newPayload.uop.robPtr := S1_Write(ROB_PTR)
          newPayload.pc       := S1_Write(PC_IN)
          payloadsMem.write(address = physIdx, data = newPayload)
          
          statusesNext(physIdx).busy                 := True
          statusesNext(physIdx).done                 := False
          statusesNext(physIdx).hasException         := False
          statusesNext(physIdx).isMispredictedBranch := False
          statusesNext(physIdx).isTaken              := False
          statusesNext(physIdx).genBit               := S1_Write(ROB_PTR).msb

          if (enableLog) report(L"[ROB_ALLOC_S1-WRITE] Writing to physIdx=${physIdx} for robPtr=${S1_Write(ROB_PTR)}. Set busy=T, genBit=${S1_Write(ROB_PTR).msb}")
        }
        
        p.build() // Finalize the pipeline construction
      }
      assert(CountOne(s0_will_fire) <= freeSlotCountReg, L"[ROB_ASSERT] FATAL: Tried to allocate more slots than available.")
    }
    this.s0_numAllocated := CountOne(allocLogic.s0_will_fire)

    private val wbLogic = new Area {
      private val writeDebugInfo = Vec(new Bundle {
        val isFiring = Bool()
        val physAddr = UInt(config.robPhysIdxWidth)
      }, config.numWritebackPorts)

      private val wbPipes = List.fill(config.numWritebackPorts)(new Pipeline {})
      wbPipes.zipWithIndex.foreach { case (p, i) =>
        val S0_Latch  = p.newStage().setName(s"WB_${i}_S0")
        val S1_Update = p.newStage().setName(s"WB_${i}_S1")
        p.connect(S0_Latch, S1_Update)(Connection.M2S())

        val WB_INFO = Stageable(WritebackInfo(config))

        // --- S0 Logic ---
        val wbPort = io.writeback(i)
        S0_Latch.valid := wbPort.fire

        S0_Latch(WB_INFO).valid                 := wbPort.fire
        S0_Latch(WB_INFO).robPtr                := wbPort.robPtr
        S0_Latch(WB_INFO).isTaken               := wbPort.isTaken
        S0_Latch(WB_INFO).isMispredictedBranch  := wbPort.isMispredictedBranch
        S0_Latch(WB_INFO).targetPc              := wbPort.targetPc
        S0_Latch(WB_INFO).result                := wbPort.result
        S0_Latch(WB_INFO).exceptionOccurred     := wbPort.exceptionOccurred
        S0_Latch(WB_INFO).exceptionCodeIn       := wbPort.exceptionCodeIn

        if(enableLog && verboseLog) when(S0_Latch.isFiring) { report(L"[ROB_WB_S0-LATCH] Port[${i}] for robPtr=${wbPort.robPtr}") }

        // --- S1 Logic ---
        S1_Update.flushIt(isFlushing)
        when(S1_Update.isFiring) {
          val wbReq           = S1_Update(WB_INFO)
          val wbPhysIdx       = wbReq.robPtr.resize(config.robPhysIdxWidth)
          val wbGenBit        = wbReq.robPtr.msb
          val statusBeforeWb  = statusesReg(wbPhysIdx)

          val isLegalWriteback = (wbGenBit === statusBeforeWb.genBit) && statusBeforeWb.busy
          assert(isLegalWriteback, L"[ROB_ASSERT] FATAL: Illegal writeback detected at S1 for robPtr=${wbReq.robPtr}")

          when(isLegalWriteback) {
            statusesNext(wbPhysIdx).busy                 := False
            statusesNext(wbPhysIdx).done                 := True
            statusesNext(wbPhysIdx).isMispredictedBranch := wbReq.isMispredictedBranch
            statusesNext(wbPhysIdx).targetPc             := wbReq.targetPc
            statusesNext(wbPhysIdx).isTaken              := wbReq.isTaken
            statusesNext(wbPhysIdx).result               := wbReq.result
            statusesNext(wbPhysIdx).hasException         := wbReq.exceptionOccurred
            statusesNext(wbPhysIdx).exceptionCode        := Mux(wbReq.exceptionOccurred, wbReq.exceptionCodeIn, statusBeforeWb.exceptionCode)
            if (enableLog) report(L"[ROB_WB_S1-WRITE] Updating status for physIdx=${wbPhysIdx} (robPtr=${wbReq.robPtr}). Set done=T, busy=F")
          }
        }

        // Export signals
        writeDebugInfo(i).isFiring := S1_Update.isFiring
        writeDebugInfo(i).physAddr := S1_Update(WB_INFO).robPtr.resize(config.robPhysIdxWidth)
        
        p.build() // Finalize the pipeline construction
      }
      
      for (i <- 0 until config.numWritebackPorts) {
        for (j <- i + 1 until config.numWritebackPorts) {
          val writeA = writeDebugInfo(i)
          val writeB = writeDebugInfo(j)
          assert(
            !(writeA.isFiring && writeB.isFiring && (writeA.physAddr === writeB.physAddr)),
            L"[ROB_ASSERT] FATAL: Multiple writebacks to the same physical slot in the same cycle detected! " :+
            L"idx_A=${i}, idx_B=${j}, physAddr=${writeA.physAddr}"
          )
        }
      }
    }

    private val commitLogic = new Area {

      private val actualCommitMask = Vec(Bool(), config.commitWidth)
      numToCommit := CountOne(actualCommitMask)

      for (i <- 0 until config.commitWidth) {
        val commitPort            = io.commit(i)
        val currentCommitPtr      = commitPtrReg + U(i)
        val physIdx               = currentCommitPtr.resize(config.robPhysIdxWidth)
        val currentStatus         = statusesReg(physIdx)
        val genBitMatch           = currentCommitPtr.msb === currentStatus.genBit
        val isOccupied            = currentStatus.busy || currentStatus.done
        val canCommitFlag         = genBitMatch && currentStatus.done && !isFlushing
        val prevCommitsAccepted = if (i == 0) True else actualCommitMask(i-1)
        
        commitPort.valid          := genBitMatch && isOccupied
        commitPort.canCommit      := canCommitFlag
        commitPort.entry.payload  := payloadsMem.readAsync(address = physIdx)
        commitPort.entry.status   := currentStatus
        
        actualCommitMask(i) := prevCommitsAccepted && canCommitFlag && io.commitAck(i)

        when(actualCommitMask(i)) {
          if (enableLog) report(L"[ROB_COMMIT] CLEAN: Cleaning status for committed robPtr=${currentCommitPtr}")
          statusesNext(physIdx).setDefault()
        }
        if (enableLog && verboseLog) when(genBitMatch && isOccupied) {
          report(L"[ROB_COMMIT_CHECK] Port[${i}]: robPtr=${currentCommitPtr}, done=${currentStatus.done}, flushBlocking=${isFlushing} => canCommit=${canCommitFlag}")
        }
      }
    }
  }

  // ==========================================================================================
  // --- 4. Final State Update & IO Drivers ---
  // ==========================================================================================
  
  private val stateUpdateLogic = new Area {
    private val allocPtrNext      = UInt(config.robPtrWidth)
    private val commitPtrNext     = UInt(config.robPtrWidth)
    private val freeSlotCountNext = UInt(log2Up(config.robDepth + 1) bits)

    when(logic.doApplyFlushState) {
      allocPtrNext        := 0
      commitPtrNext       := 0
      freeSlotCountNext   := config.robDepth
    } otherwise {
      allocPtrNext        := allocPtrReg + logic.s0_numAllocated
      commitPtrNext       := commitPtrReg + logic.numToCommit
      freeSlotCountNext   := freeSlotCountReg - logic.s0_numAllocated + logic.numToCommit
    }

    allocPtrReg         := allocPtrNext
    commitPtrReg        := commitPtrNext
    freeSlotCountReg    := freeSlotCountNext
    statusesReg         := statusesNext

    io.empty            := freeSlotCountReg === config.robDepth
    io.countOut         := (config.robDepth - freeSlotCountReg).resized
    io.headPtrOut       := commitPtrReg
    io.tailPtrOut       := allocPtrReg

    private val expectedUsedSlots = UInt(config.robPtrWidth.value + 1 bits)
    private val head_ext = commitPtrReg.resize(config.robPtrWidth.value + 1)
    private val tail_ext = allocPtrReg.resize(config.robPtrWidth.value + 1)
    when(tail_ext >= head_ext) { expectedUsedSlots := tail_ext - head_ext } 
    .otherwise { expectedUsedSlots := tail_ext + (U(1) << config.robPtrWidth.value) - head_ext }
    
    assert(freeSlotCountReg === (config.robDepth - expectedUsedSlots.resized),
           L"[ROB_ASSERT] FATAL: freeSlotCountReg does not match pointer difference!")
  }

  // ==========================================================================================
  // --- 5. Debug and Verification ---
  // ==========================================================================================
  private val perfCounter = Reg(UInt(32 bits)) init(0)
  perfCounter := perfCounter + 1

  private val full_state_dump = enableLog generate new Area {
    report(
      L"[ROB_SNAPSHOT] Cycle=${perfCounter} | " :+
      L"head=${io.headPtrOut}, tail=${io.tailPtrOut}, count=${io.countOut} | " :+
      L"freeSlots=${freeSlotCountReg} | " :+
      L"allocated_this_cycle=${logic.s0_numAllocated}, committed_this_cycle=${logic.numToCommit} | " :+
      L"flush_in_progress=${logic.isFlushing}, flush_applied_this_cycle=${logic.doApplyFlushState}"
    )
    when(io.countOut > 0) {
      report(L"  -- ROB Contents --")
      for(i <- 0 until config.robDepth) {
          val current_ptr_candidate = commitPtrReg + i
          val physIdx = current_ptr_candidate.resize(config.robPhysIdxWidth)
          val status = statusesReg(physIdx)
          val is_valid_entry = (current_ptr_candidate.msb === status.genBit) && (status.busy || status.done)

          when(U(i) < io.countOut && is_valid_entry){
              val payload = payloadsMem.readAsync(physIdx)
              report(
                  L"  -> Entry[robPtr=${current_ptr_candidate} (phys=${physIdx})]: " :+
                  L"status(busy=${status.busy}, done=${status.done}, gen=${status.genBit}, ex=${status.hasException}, mpred=${status.isMispredictedBranch})"
              )
          }
      }
    }
  }
}
