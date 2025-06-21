package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.{Pipeline, Stage, Stageable, Connection}
import parallax.common._
import parallax.components.rob.ROBFlushPayload
import parallax.utilities._
import parallax.components.dcache2._

trait StoreQueueService extends Service with LockedImpl {
  def getAllocatePort(): Stream[StoreQueueEntry]
  def getStatusUpdatePort(): Flow[SqStatusUpdate]
}

trait CommitServiceForLsu extends Service {
  def getReleasePorts(): Vec[Flow[UInt]]
  def getReleaseMask(): Vec[Bool]
}

trait FlushServiceForLsu extends Service {
  def getLsuFlushPort(): Flow[ROBFlushPayload]
}

class StoreQueuePlugin(
    val lsuConfig: LsuConfig,
    val pipelineConfig: PipelineConfig,
    val dCacheConfig: DataCacheParameters,
    val flushWhenWb: Boolean = false
) extends Plugin
    with StoreQueueService
    with LockedImpl {

  val enableLog = true

  private val setup = create early new Area {
    // 1. Get dependent services
    val commitService = getService[CommitServiceForLsu]
    val flushService = getService[FlushServiceForLsu]
    val aguService = getService[AguService]
    val prfService = getService[PhysicalRegFileService]
    val dcacheService = getService[DataCacheService]
    val refillCompletions = dcacheService.getRefillCompletions()

    // 2. Get the actual ports/signals from services and store them for later use
    val releasePorts = commitService.getReleasePorts()
    val releaseMask = commitService.getReleaseMask()
    val sqFlushPort = flushService.getLsuFlushPort()

    // 3. Store the ports that SQ will drive or be driven by
    val aguPort = aguService.newAguPort()
    val prfReadPort = prfService.newReadPort()
    val dCacheStorePort = dcacheService.newStorePort()
    val dCacheRelatedQPtr = UInt(lsuConfig.sqPtrWidth)

    // 4. Define the ports that this plugin itself provides as a service
    val allocatePortInst = Stream(StoreQueueEntry(lsuConfig))
    val statusUpdatePortInst = Flow(SqStatusUpdate(lsuConfig))
  }

  private val logic = create late new Area {
    lock.await()
    ParallaxLogger.log("SQPlugin: Elaborating core logic.")

    // --- Data Storage: Registers and Memories ---
    private val validsReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val committedsReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val writebackCompletesReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val waitOnsReg = Vec.fill(lsuConfig.sqDepth)(Reg(SqWaitOn(lsuConfig)))

    private val sqPtrs = Mem(UInt(lsuConfig.sqPtrWidth), lsuConfig.sqDepth)
    private val robPtrsRegFile = Vec(Reg(UInt(pipelineConfig.robPtrWidth)), lsuConfig.sqDepth)
    private val pcs = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    private val physDataSrcs = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    private val physDataSrcIsFprs = Mem(Bool(), lsuConfig.sqDepth)
    private val agImm = Mem(SInt(12 bits), lsuConfig.sqDepth)
    private val agBasePhysReg = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    private val agUsePc = Mem(Bool(), lsuConfig.sqDepth)
    private val accessSizes = Mem(MemAccessSize(), lsuConfig.sqDepth)

    private val physicalAddresses = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    private val dataToWrites = Mem(Bits(lsuConfig.dataWidth), lsuConfig.sqDepth)
    private val storeMasks = Mem(Bits(lsuConfig.dataWidth.value / 8 bits), lsuConfig.sqDepth)

    // --- Pointers ---
    private val allocPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val commitPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val writebackPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val priorityPtrReg = Reg(UInt(lsuConfig.sqIdxWidth)) init (0)

    // --- Helper functions and Status ---
    private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
      val genA = robPtrA.msb; val idxA = robPtrA(robPtrA.high - 1 downto 0)
      val genB = robPtrB.msb; val idxB = robPtrB(robPtrB.high - 1 downto 0)
      (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
    }
    private def getPhysicalIndex(ptr: UInt): UInt = ptr(lsuConfig.sqIdxWidth.value - 1 downto 0)

    private val ptrsSameGen = allocPtrReg.msb === writebackPtrReg.msb
    private val allocIdxForCount = getPhysicalIndex(allocPtrReg)
    private val writebackIdxForCount = getPhysicalIndex(writebackPtrReg)
    private val entryCount = UInt(log2Up(lsuConfig.sqDepth + 1) bits)
    when(ptrsSameGen) { entryCount := (allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth) }
      .otherwise {
        entryCount := (U(lsuConfig.sqDepth) + allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth)
      }

    private val isFull = entryCount === lsuConfig.sqDepth
    if (enableLog)
      ParallaxSim.debug(L"[SQPlugin] Current SQ entry count: ${entryCount}, IsFull: ${isFull}")

    // --- Area 1: Action Units ---
    // These areas read the current state and send commands to external units (PRF, AGU, DCache).
    // They DO NOT modify the core state registers (validsReg, committedsReg, waitOnsReg).
    private val actionUnits = new Area {

      // -- Allocation Handler: Manages new entries and PRF read --
      val allocation = new Area {
        setup.allocatePortInst.ready := !isFull
        val fire = setup.allocatePortInst.fire
        val physIdx = getPhysicalIndex(allocPtrReg)
        val prfPort = setup.prfReadPort

        // prevent latch
        prfPort.valid := False
        prfPort.address.assignDontCare()
        prfPort.rsp.assignDontCare()

        when(fire) {
          val newEntry = setup.allocatePortInst.payload
          // Write all non-state-machine data
          sqPtrs.write(physIdx, allocPtrReg)
          robPtrsRegFile(physIdx) := newEntry.robPtr
          pcs.write(physIdx, newEntry.pc)
          physDataSrcs.write(physIdx, newEntry.physDataSrc)
          physDataSrcIsFprs.write(physIdx, newEntry.physDataSrcIsFpr)
          agImm.write(physIdx, newEntry.aguImmediate)
          agBasePhysReg.write(physIdx, newEntry.aguBasePhysReg)
          agUsePc.write(physIdx, newEntry.aguUsePcAsBase)
          accessSizes.write(physIdx, newEntry.accessSize)

          // Immediately read PRF data (as per 0-latency assumption)
          prfPort.valid := True
          prfPort.address := newEntry.physDataSrc
          dataToWrites.write(physIdx, prfPort.rsp)

          // Update local pointer
          allocPtrReg := allocPtrReg + 1
        }
      }

      // -- AGU Requester: Schedules and dispatches address generation requests --
      val aguRequester = new Area {
        val aguPipe = new Pipeline {
          val SCHEDULE, DISPATCH = newStage()
          connect(SCHEDULE, DISPATCH)(Connection.M2S())
        }
        val SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
        val AGU_BASE_PHYS_REG = Stageable(UInt(lsuConfig.physGprIdxWidth))
        val AGU_IMM = Stageable(SInt(12 bits))
        val ACCESS_SIZE = Stageable(MemAccessSize())
        val AGU_USE_PC = Stageable(Bool())
        val PC = Stageable(UInt(lsuConfig.pcWidth))
        val ROB_PTR = Stageable(UInt(lsuConfig.robPtrWidth))

        val scheduleStage = aguPipe.SCHEDULE
        scheduleStage(SQ_PTR).assignDontCare()
        scheduleStage(AGU_BASE_PHYS_REG).assignDontCare()
        scheduleStage(AGU_IMM).assignDontCare()
        scheduleStage(ACCESS_SIZE).assignDontCare()
        scheduleStage(AGU_USE_PC).assignDontCare()
        scheduleStage(PC).assignDontCare()
        scheduleStage(ROB_PTR).assignDontCare()

        val aguCandidates = B(validsReg.zip(waitOnsReg).map { case (v, w) => v && w.isStalledForAgu })
        val aguSelOh = OHMasking.roundRobin(aguCandidates, ~((U(1) << priorityPtrReg) - 1))
        scheduleStage.valid := aguSelOh.orR

        when(scheduleStage.valid) {
          val selIdx = OHToUInt(aguSelOh)
          scheduleStage(SQ_PTR) := sqPtrs.readAsync(selIdx)
          scheduleStage(AGU_BASE_PHYS_REG) := agBasePhysReg.readAsync(selIdx)
          scheduleStage(AGU_IMM) := agImm.readAsync(selIdx)
          scheduleStage(ACCESS_SIZE) := accessSizes.readAsync(selIdx)
          scheduleStage(AGU_USE_PC) := agUsePc.readAsync(selIdx)
          scheduleStage(PC) := pcs.readAsync(selIdx)
          scheduleStage(ROB_PTR) := robPtrsRegFile(selIdx)
        }

        val dispatchStage = aguPipe.DISPATCH
        setup.aguPort.input.arbitrationFrom(dispatchStage.toStream())
        setup.aguPort.input.payload.assignDontCare()
        setup.aguPort.flush := False // never

        when(dispatchStage.valid) {
          val p = setup.aguPort.input.payload
          p.qPtr := dispatchStage(SQ_PTR)
          p.basePhysReg := dispatchStage(AGU_BASE_PHYS_REG)
          p.immediate := dispatchStage(AGU_IMM)
          p.accessSize := dispatchStage(ACCESS_SIZE)
          p.usePc := dispatchStage(AGU_USE_PC)
          p.pc := dispatchStage(PC)
          p.robPtr := dispatchStage(ROB_PTR)
          p.isLoad := False
          p.isStore := True
          p.physDst := 0
        }
        aguPipe.build()
      }

      // -- Writeback Requester: Schedules and sends store requests to DCache --
      private val writebackRequester = new Area {
        private val wbPipe = new Pipeline {
          val WB_SCHEDULE, WB_DRIVE = newStage()
          connect(WB_SCHEDULE, WB_DRIVE)(Connection.M2S())
        }
        private val WB_SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
        private val WB_ADDRESS = Stageable(UInt(lsuConfig.pcWidth))
        private val WB_DATA = Stageable(Bits(lsuConfig.dataWidth))
        private val WB_MASK = Stageable(Bits(lsuConfig.dataWidth.value / 8 bits))

        private val scheduleStage = wbPipe.WB_SCHEDULE
        scheduleStage(WB_SQ_PTR) := U(BigInt("CDCDCDCD", 16)).resized
        scheduleStage(WB_ADDRESS) := U(BigInt("CDCDCDCD", 16)).resized
        scheduleStage(WB_DATA) := U(BigInt("CDCDCDCD", 16)).asBits.resized
        scheduleStage(WB_MASK) := U(BigInt("CDCDCDCD", 16)).asBits.resized

        private val ptr = writebackPtrReg
        private val physIdx = getPhysicalIndex(ptr)

        private val canWriteback = validsReg(physIdx) && committedsReg(physIdx) &&
          !writebackCompletesReg(physIdx) && waitOnsReg(physIdx).address && waitOnsReg(physIdx).dCacheRefill === 0 &&
          !waitOnsReg(physIdx).dCacheRefillAny &&
          !waitOnsReg(physIdx).dCacheStoreRsp // prevent sending a command when another one is already in-flight.


        scheduleStage.valid := canWriteback

        when(scheduleStage.isValid) {
          scheduleStage(WB_SQ_PTR) := ptr
          scheduleStage(WB_ADDRESS) := physicalAddresses.readAsync(physIdx)
          scheduleStage(WB_DATA) := dataToWrites.readAsync(physIdx)
          scheduleStage(WB_MASK) := storeMasks.readAsync(physIdx)
        }

        private val driveStage = wbPipe.WB_DRIVE
        val dCacheCmdPort = setup.dCacheStorePort.cmd

        dCacheCmdPort.arbitrationFrom(driveStage.toStream())
        dCacheCmdPort.payload.assignDontCare()
        setup.dCacheRelatedQPtr.assignDontCare()

        when(driveStage.valid) {
          dCacheCmdPort.payload.address := driveStage(WB_ADDRESS)
          dCacheCmdPort.payload.data := driveStage(WB_DATA)
          dCacheCmdPort.payload.mask := driveStage(WB_MASK)
          dCacheCmdPort.payload.io := False
          dCacheCmdPort.payload.flush := False
          dCacheCmdPort.payload.flushFree := False
          dCacheCmdPort.payload.prefetch := False
          setup.dCacheRelatedQPtr := driveStage(WB_SQ_PTR)
          ParallaxSim.debug(L"[SQPlugin] Writing to D-Cache: Address: ${driveStage(WB_ADDRESS)}, Data: ${driveStage(WB_DATA)}, Mask: ${driveStage(WB_MASK)}")
        }
        when(dCacheCmdPort.fire) {
          ParallaxSim.warning(L"[SQPlugin] DCache Store command fired: Address: ${dCacheCmdPort.payload.address}, Data: ${dCacheCmdPort.payload.data}, Mask: ${dCacheCmdPort.payload.mask}")
        }
        wbPipe.build()
      }
    }

    // --- Area 2: Event Detectors ---
    // These areas detect single-cycle events and produce simple boolean flags or hit vectors.
    private val eventDetectors = new Area {

      val allocationHitMask = B(0, lsuConfig.sqDepth bits)
      when(actionUnits.allocation.fire) {
        allocationHitMask(actionUnits.allocation.physIdx) := True
      }

      val aguRsp = new Area {
        val fire = setup.aguPort.output.valid
        val sqIdx = getPhysicalIndex(setup.aguPort.output.payload.qPtr)
        val payload = setup.aguPort.output.payload
        setup.aguPort.output.ready := True // AGU Rsp is a Flow, always ready
      }

      val dcacheRsp = new Area {
        val fire = setup.dCacheStorePort.rsp.valid
        val sqIdx = getPhysicalIndex(writebackPtrReg) // Still assumes in-order response
        val payload = setup.dCacheStorePort.rsp.payload
      }

      val commitHits = Vec.fill(lsuConfig.sqDepth)(False)
      val commitPtrUpdater = new Area {
        var ptr = commitPtrReg
        for (i <- 0 until lsuConfig.commitWidth) {
          val physIdx = getPhysicalIndex(ptr)
          val doRelease = setup.releasePorts(i).valid && setup.releaseMask(i) &&
            validsReg(physIdx) && robPtrsRegFile(physIdx) === setup.releasePorts(i).payload
          when(doRelease) {
            commitHits(physIdx) := True
          }
          ptr = ptr + U(doRelease)
        }
        val finalPtr = ptr
        when(finalPtr =/= commitPtrReg) {
          commitPtrReg := finalPtr
        }
      }

      val release = new Area {
        val fire =
          validsReg(getPhysicalIndex(writebackPtrReg)) && writebackCompletesReg(getPhysicalIndex(writebackPtrReg))
        val physIdx = getPhysicalIndex(writebackPtrReg)
        when(fire) {
          writebackPtrReg := writebackPtrReg + 1
        }
      }

      val flush = new Area {
        val fire = setup.sqFlushPort.valid
        val hitMask = B(for (i <- 0 until lsuConfig.sqDepth) yield {
          validsReg(i) && !committedsReg(i) && isNewerOrSame(robPtrsRegFile(i), setup.sqFlushPort.payload.targetRobPtr)
        })
        when(fire) {
          allocPtrReg := commitPtrReg
          priorityPtrReg := getPhysicalIndex(commitPtrReg)
        }
      }
    }
// --- Area 3: Central State Authority ---
// This area is the single source of truth for all SQ entry state transitions.
// It reads events detected in the previous area and updates the core state registers
// based on a clear priority, ensuring no race conditions or combinational loops.
   private val stateMachine = new Area {
      // Get the broadcast signal of D-Cache refill completions.
      val refillCompletions = setup.dcacheService.getRefillCompletions

      // Iterate through each entry in the Store Queue.
      for (i <- 0 until lsuConfig.sqDepth) {
        // --- 1. Define 'next' value signals for all state registers of the current entry ---
        // This pattern is crucial to avoid multi-assignments and combinational loops.
        // All logic will write to these '...Next' signals.
        val validNext = CombInit(validsReg(i))
        val committedNext = CombInit(committedsReg(i))
        val writebackCompleteNext = CombInit(writebackCompletesReg(i))
        val waitOnNext = CombInit(waitOnsReg(i))

        // --- 3. High-priority, mutually exclusive events (Resets/Clears) ---
        // These events override any other state transition for the current cycle.
        when(eventDetectors.flush.fire && eventDetectors.flush.hitMask(i)) {
          // On a flush hit, invalidate the entry.
          validNext := False
          report(L"[SQPlugin] Flushing entry $i")
        }.elsewhen(eventDetectors.release.fire && eventDetectors.release.physIdx === i) {
          // On release (after successful writeback), clear all state and invalidate.
          validNext := False
          committedNext := False
          writebackCompleteNext := False
          waitOnNext.setDefault()
          report(L"[SQPlugin] Releasing entry $i")
        }.elsewhen(eventDetectors.allocationHitMask(i)) {
          // On new allocation, validate the entry and set its initial state.
          validNext := True
          committedNext := False
          writebackCompleteNext := False
          waitOnNext.setDefault()
          waitOnNext.isStalledForAgu := True
          report(L"[SQPlugin] Allocating entry $i")
        }.otherwise {
          // --- 4. Normal, parallel state transitions for an active entry ---
          // These events can occur simultaneously and update different aspects of the state.

          // -- Committed Status Update --
          when(eventDetectors.commitHits(i)) {
            committedNext := True
            report(L"[SQPlugin] Committing entry $i")
          }

          // -- AGU Response Handling --
          val aguRspHit = eventDetectors.aguRsp.fire && (eventDetectors.aguRsp.sqIdx === i)
          when(aguRspHit) {
            // Update wait-on state
            waitOnNext.isStalledForAgu := False
            waitOnNext.address := True

            // **FIX**: Get the AGU payload and write the calculated address and mask to memory.
            val aguPayload = eventDetectors.aguRsp.payload
            physicalAddresses.write(i, aguPayload.address)
            storeMasks.write(i, aguPayload.storeMask)
            report(L"[SQPlugin] AGU response for entry $i: Address: ${aguPayload.address}, Mask: ${aguPayload.storeMask}")
          }

            val dcacheRspHit = eventDetectors.dcacheRsp.fire && (eventDetectors.dcacheRsp.sqIdx === i)
            val dcacheCmdFiredForThisEntry = setup.dCacheStorePort.cmd.fire && (getPhysicalIndex(setup.dCacheRelatedQPtr) === i)

            // First, calculate the next state of refill waits based on completions this cycle.
            val refillCleared = waitOnsReg(i).dCacheRefill & ~refillCompletions
            val refillAnyCleared = waitOnsReg(i).dCacheRefillAny & !refillCompletions.orR
            
            // Assign the cleared values. This will be the base for this cycle's update.
            waitOnNext.dCacheRefill := refillCleared
            waitOnNext.dCacheRefillAny := refillAnyCleared

            // Now, handle the DCache response and command fire events with correct priority.
            when(dcacheRspHit) {
                val rspPayload = eventDetectors.dcacheRsp.payload

                // A response of any kind (redo or final) means the in-flight transaction is over.
                // Clear the flag to allow a potential resend later.
                waitOnNext.dCacheStoreRsp := False

                when(rspPayload.redo) {
                    // DCache asks to retry. Set wait conditions for the *next* attempt.
                    // We OR with the already-cleared values to handle edge cases.
                    waitOnNext.dCacheRefill := refillCleared | rspPayload.refillSlot
                    waitOnNext.dCacheRefillAny := refillAnyCleared | rspPayload.refillSlotAny
                    ParallaxSim.warning(L"[SQPlugin] D-Cache requested a redo for entry $i: Slot: ${rspPayload.refillSlot}, Any: ${rspPayload.refillSlotAny}")
                } otherwise {
                    // This was a final response (success or fault). The transaction is complete.
                    writebackCompleteNext := !rspPayload.fault
                    ParallaxSim.success(L"[SQPlugin] D-Cache response for entry $i: Fault: ${rspPayload.fault}, Address: ${rspPayload.address}")
                }
            } .elsewhen(dcacheCmdFiredForThisEntry) {
                // A command just fired. Set the in-flight flag.
                // This has lower priority than handling a response for the same entry in the same cycle.
                waitOnNext.dCacheStoreRsp := True
            }
        }

        // --- Final, unconditional assignment to the actual state registers ---
        validsReg(i) := validNext
        committedsReg(i) := committedNext
        writebackCompletesReg(i) := writebackCompleteNext
        waitOnsReg(i) := waitOnNext
      }


      // --- Pointer Updates ---
      // These are separate state elements updated based on events.

      // The 'waitOn.dCacheStoreRsp' flag is set here, when a write command successfully fires.
      when(setup.dCacheStorePort.cmd.fire) {
        val firedSqPtr = setup.dCacheRelatedQPtr
        val firedPhysIdx = getPhysicalIndex(firedSqPtr)
        waitOnsReg(firedPhysIdx).dCacheStoreRsp := True
      }

      // The priority pointer for AGU arbitration is updated when an AGU request is dispatched.
      when(actionUnits.aguRequester.aguPipe.DISPATCH.isFiring) {
        val dispatchedSqPtr = actionUnits.aguRequester.aguPipe.DISPATCH(actionUnits.aguRequester.SQ_PTR)
        priorityPtrReg := getPhysicalIndex(dispatchedSqPtr) + 1
      }
      // Flush has the highest priority for resetting the priority pointer.
      when(eventDetectors.flush.fire) {
        priorityPtrReg := getPhysicalIndex(commitPtrReg)
      }
    }
  }

  override def getAllocatePort(): Stream[StoreQueueEntry] = setup.allocatePortInst
  override def getStatusUpdatePort(): Flow[SqStatusUpdate] = setup.statusUpdatePortInst
}
