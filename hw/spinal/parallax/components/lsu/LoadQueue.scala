// -- MODIFICATION START (Fix combinational loops) --
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBFlushPayload, ROBService} // ROBFlushPayload
import parallax.components.dcache2.DataCachePluginConfig // For LsuConfig
import parallax.utilities.{Plugin, Service, LockedImpl, ParallaxLogger, ParallaxSim}

// --- Service Definition ---
trait LoadQueueService extends Service with LockedImpl {
  def getAllocatePort(): Stream[LoadQueueEntry] // 从 Rename/Dispatch 接收 LoadQueueEntry
  def getStatusUpdatePort(): Flow[LqStatusUpdate] // 从 LSU Core/DCache 更新 LQ 条目状态
  def getReleasePorts(): Vec[Flow[UInt]] // 从 Commit 接收释放的 ROB ID (完整 ID)
  def getReleaseMask(): Vec[Bool] // Commit 阶段的有效释放掩码 (输入给 LQ)
  def getLoadRequestPort(): Stream[LsuAguRequest] // LQ 向 LSU Scheduler/AGU 发送加载请求
  def getLQFlushPort(): Flow[ROBFlushPayload] // 从 ROB/FlushController 接收刷新命令
}

// --- Configuration ---

class LoadQueuePlugin(val lsuConfig: LsuConfig, val pipelineConfig: PipelineConfig) extends Plugin with LoadQueueService with LockedImpl {
  ParallaxLogger.log(s"LQPlugin: Creating LoadQueue with config: $lsuConfig")
  // --- Hardware Definition Area ---
  private val hw = create early new Area {
    ParallaxLogger.debug(s"LQPlugin: early stage")
    val entries = Mem(LoadQueueEntry(lsuConfig), lsuConfig.lqDepth)
    val defaultLqEntry = LoadQueueEntry(lsuConfig).setDefault()

    // Pointers (store full ID: physical index + generation bit)
    val allocPtrReg = Reg(UInt(lsuConfig.lqPtrWidth)) init (0)
    val commitPtrReg = Reg(UInt(lsuConfig.lqPtrWidth)) init (0)

    // Entry count and full/empty status
    val entryCount = UInt(log2Up(lsuConfig.lqDepth + 1) bits)
    val isFull = Bool()
    val isEmpty = Bool()

    val ptrsSameGen = allocPtrReg.msb === commitPtrReg.msb
    val allocIdxForCount = allocPtrReg(lsuConfig.lqIdxWidth.value - 1 downto 0)
    val commitIdxForCount = commitPtrReg(lsuConfig.lqIdxWidth.value - 1 downto 0)

    when(ptrsSameGen) {
      entryCount := (allocIdxForCount - commitIdxForCount).resize(entryCount.getWidth)
    } otherwise {
      entryCount := (U(lsuConfig.lqDepth, entryCount.getWidth bits) + allocIdxForCount - commitIdxForCount)
        .resize(entryCount.getWidth)
    }
    isFull := entryCount === lsuConfig.lqDepth
    isEmpty := entryCount === 0

    // Service Port Instantiation
    val allocatePortInst = Stream(LoadQueueEntry(lsuConfig)) // Receives LoadQueueEntry
    val statusUpdatePortInst = Flow(LqStatusUpdate(lsuConfig))
    val releasePortsInst = Vec(Flow(UInt(lsuConfig.robIdxWidth)), lsuConfig.commitWidth)
    val releaseMaskInst = Vec(Bool(), lsuConfig.commitWidth) // Input from Commit
    val loadRequestPortInst = Stream(LsuAguRequest(lsuConfig))
    val lqFlushPortInst = Flow(ROBFlushPayload(pipelineConfig.robIdxWidth)) // Pass PipelineConfig
  }

  // --- Service Implementation ---
  override def getAllocatePort(): Stream[LoadQueueEntry] = hw.allocatePortInst
  override def getStatusUpdatePort(): Flow[LqStatusUpdate] = hw.statusUpdatePortInst
  override def getReleasePorts(): Vec[Flow[UInt]] = hw.releasePortsInst
  override def getReleaseMask(): Vec[Bool] = hw.releaseMaskInst
  override def getLoadRequestPort(): Stream[LsuAguRequest] = hw.loadRequestPortInst
  override def getLQFlushPort(): Flow[ROBFlushPayload] = hw.lqFlushPortInst

  // --- Helper Functions for Internal Use ---
  private def getEntryPhysicalIndex(ptrWithGenBit: UInt): UInt = ptrWithGenBit(lsuConfig.lqIdxWidth.value - 1 downto 0)
  private def getEntryGenBit(ptrWithGenBit: UInt): Bool = ptrWithGenBit.msb

  private def readEntryAsync(physicalIdx: UInt): LoadQueueEntry = {
    hw.entries.readAsync(physicalIdx)
  }
  private def writeEntryOnEdge(physicalIdx: UInt, data: LoadQueueEntry): Unit = {
    hw.entries.write(physicalIdx, data)
  }

  // --- Internal Logic Areas ---

  private val allocationLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin allocationLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin allocationLogic: Elaborate logic")
    hw.allocatePortInst.ready := !hw.isFull // Drive ready for the Stream interface

    val currentAllocPhysIdx = getEntryPhysicalIndex(hw.allocPtrReg)
    val currentAllocGenBit = getEntryGenBit(hw.allocPtrReg) // For logging/debug

    when(hw.allocatePortInst.fire) {
      assert(!hw.isFull, "LQ allocation fired when LQ is full! Upstream should check ready.")

      val newEntry = hw.allocatePortInst.payload

      writeEntryOnEdge(currentAllocPhysIdx, newEntry)
      hw.allocPtrReg := hw.allocPtrReg + 1

    }
  }

  private val requestGenerationLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin requestGenerationLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin requestGenerationLogic: Elaborate logic")

    val candidatesValid = Vec(Bool(), lsuConfig.lqDepth)
    val candidatesAguRequest = Vec.fill(lsuConfig.lqDepth)(LsuAguRequest(lsuConfig).assignDontCare())
    val numValidEntriesInQueue = hw.entryCount

    // Iterate through potential LQ entries starting from commitPtr
    for (i <- 0 until lsuConfig.lqDepth) {
      val currentReadFullPtr = hw.commitPtrReg + U(i)
      val currentReadPhysIdx = getEntryPhysicalIndex(currentReadFullPtr)
      val entry = readEntryAsync(currentReadPhysIdx)

      val isWithinValidRange = U(i) < numValidEntriesInQueue
      // A more robust gen bit check would be to store gen bit in entry and match
      // val genBitMatches = getEntryGenBit(currentReadFullPtr) === entry.genBitStoredAtAlloc
      // For now, rely on commitPtr tracking the "oldest valid generation"
      val genBitMatches = True // Simplified: assume commitPtr always points to a valid generation if entry is valid

      candidatesValid(i) := isWithinValidRange &&
                            entry.isValid &&
                            genBitMatches &&
                            entry.waitOn.isStalledForAgu && // Check specific wait condition
                            !entry.waitOn.robFlush

      when(candidatesValid(i)) {
        val aguReq = LsuAguRequest(lsuConfig)
        aguReq.basePhysReg    := entry.aguBasePhysReg
        aguReq.immediate      := entry.aguImmediate
        aguReq.accessSize     := entry.accessSize
        aguReq.usePc          := entry.aguUsePcAsBase
        aguReq.pcForAgu       := entry.pc
        aguReq.robIdx         := entry.robIdx
        aguReq.isLoad         := True
        aguReq.isStore        := False
        aguReq.qId            := entry.lqId // Physical LQ ID
        aguReq.physDestOrSrc  := entry.physDest
        aguReq.physDestOrSrcIsFpr := entry.physDestIsFpr
        aguReq.writePhysDestEn:= entry.writePhysDestEn
        candidatesAguRequest(i) := aguReq
      }
    }

    val selectedValid = candidatesValid.orR
    // Simple fixed-priority arbitration (older entries first)
    val selectedOh = OHMasking.first(candidatesValid)
    val selectedData = MuxOH(selectedOh, candidatesAguRequest)

    hw.loadRequestPortInst.valid := selectedValid
    hw.loadRequestPortInst.payload := selectedData

    when(hw.loadRequestPortInst.fire) {
      ParallaxSim.debug(L"[LQPlugin] AGU Request sent for LQ ID ${selectedData.qId} (ROB ID ${selectedData.robIdx})")
      // The LSU Core/AGU will send an LqStatusUpdate when the address is generated.
    }
  }

  private val statusUpdateLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Elaborate logic")
    when(hw.statusUpdatePortInst.valid) {
      val update = hw.statusUpdatePortInst.payload
      val lqPhysIdxToUpdate = update.lqId

      val currentEntry = readEntryAsync(lqPhysIdxToUpdate)
      // It's crucial that this update is for a valid entry of the current generation.
      // A robust check would involve matching robIdx or a stored generation bit.
      // For now, assume lqId is sufficient and points to the correct active entry.
      when(currentEntry.isValid) {
        val updatedEntryData = CombInit(currentEntry)

        ParallaxSim.debug(
          L"[LQPlugin] Status Update for LQ ID ${lqPhysIdxToUpdate} (ROB ${currentEntry.robIdx}): Type=${update.updateType}"
        )

        switch(update.updateType) {
          is(LqUpdateType.ADDRESS_GENERATED) {
            updatedEntryData.physicalAddress := update.physicalAddressAg
            updatedEntryData.waitOn.addressGenerated := True
            updatedEntryData.waitOn.dCacheRsp := True // Now wait for D-Cache
            when(update.alignExceptionAg) {
              updatedEntryData.waitOn.commit := True
              updatedEntryData.waitOn.dCacheRsp := False
            }
          }
          is(LqUpdateType.DCACHE_RESPONSE) {
            updatedEntryData.waitOn.dCacheRsp := False
            when(update.dCacheFaultDr) {
              updatedEntryData.waitOn.commit := True
            } elsewhen (update.dCacheRedoDr) {
              // Read from the original entry, not the one being modified
              updatedEntryData.waitOn.dCacheRefill := currentEntry.waitOn.dCacheRefill | update.dCacheRefillSlotDr
              updatedEntryData.waitOn.dCacheRefillAny := currentEntry.waitOn.dCacheRefillAny | update.dCacheRefillAnyDr
            } otherwise {
              updatedEntryData.dataFromDCache := update.dataFromDCacheDr
              // LSU Core will decide if this data is final or if SQ bypass is needed
            }
          }
          is(LqUpdateType.DCACHE_REFILL_WAIT) {
              // Read from the original entry
              updatedEntryData.waitOn.dCacheRefill := currentEntry.waitOn.dCacheRefill | update.dCacheRefillSlotDr
              updatedEntryData.waitOn.dCacheRefillAny := currentEntry.waitOn.dCacheRefillAny | update.dCacheRefillAnyDr
          }
          is(LqUpdateType.DCACHE_REFILL_DONE) {
            // Read from the original entry to avoid combinational loop
            val prevRefill = currentEntry.waitOn.dCacheRefill
            val prevRefillAny = currentEntry.waitOn.dCacheRefillAny

            updatedEntryData.waitOn.dCacheRefill := prevRefill & ~update.dCacheRefillSlotDr
            
            when(prevRefillAny && update.dCacheRefillAnyDr) {
                 updatedEntryData.waitOn.dCacheRefillAny := False
            }
            when( (prevRefill & ~update.dCacheRefillSlotDr) === 0 ) {
                updatedEntryData.waitOn.dCacheRefillAny := False
            }
          }
          is(LqUpdateType.SQ_BYPASS_READY) {
            updatedEntryData.dataFromSq := update.dataFromSqBypassSb
            // LSU Core will decide if this bypass is taken and set finalData
            updatedEntryData.waitOn.sqBypass := False // No longer waiting for this specific bypass opportunity
            when(update.sqBypassSuccessSb){ // If LSU Core confirms bypass is taken
                updatedEntryData.finalData := update.dataFromSqBypassSb
                updatedEntryData.waitOn.sqCompletion := False // Consider it completed w.r.t this store
            }
          }
          is(LqUpdateType.SQ_OLDER_STORE_COMPLETED) {
            updatedEntryData.waitOn.sqCompletion := False // The specific store we were waiting for is done
          }
          is(LqUpdateType.EXCEPTION_OCCURRED) {
            updatedEntryData.waitOn.commit := True
            updatedEntryData.waitOn.addressGenerated := True; updatedEntryData.waitOn.dCacheRsp := False
            updatedEntryData.waitOn.dCacheRefill := 0; updatedEntryData.waitOn.dCacheRefillAny := False
            updatedEntryData.waitOn.sqBypass := False; updatedEntryData.waitOn.sqCompletion := False
          }
          is(LqUpdateType.MARK_READY_FOR_COMMIT) {
            // Check based on the state of the original entry to avoid loop
            when(currentEntry.waitOn.isReadyForCommit) {
                 updatedEntryData.waitOn.commit := True
            } otherwise {
                ParallaxSim.warning(L"[LQPlugin] LQ ID ${lqPhysIdxToUpdate} (ROB ${currentEntry.robIdx}): MARK_READY_FOR_COMMIT received, but not all waits cleared. Current waits: ${currentEntry.waitOn.format}")
            }
          }
        }
        writeEntryOnEdge(lqPhysIdxToUpdate, updatedEntryData)
      } otherwise {
        ParallaxSim.warning(L"[LQPlugin] Status Update for LQ ID ${lqPhysIdxToUpdate} received, but entry is NOT VALID or GenBit mismatch.")
      }
    }
  }

  private val releaseLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin releaseLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin releaseLogic: Elaborate logic")

    // Use foldLeft to build a combinatorial chain instead of a loop.
    // The accumulator tracks the number of releases so far in this cycle.
    val releasedThisCycleCount = (0 until lsuConfig.commitWidth).foldLeft(U(0, log2Up(lsuConfig.commitWidth + 1) bits)) {
      (releasedCountSoFar, i) =>
        val robIdToReleaseFromCommitSlot = hw.releasePortsInst(i).payload
        val doReleaseForThisSlot = hw.releasePortsInst(i).valid && hw.releaseMaskInst(i) && !hw.isEmpty

        // Tentative pointer for this commit slot based on releases from previous slots
        val currentReleaseFullPtr = hw.commitPtrReg + releasedCountSoFar
        val headPhysIdx = getEntryPhysicalIndex(currentReleaseFullPtr)
        val headEntry = readEntryAsync(headPhysIdx)

        val success = False // Will be set to True if this slot successfully releases an entry

        when(doReleaseForThisSlot) {
          assert(!hw.isEmpty, "LQ release fired when LQ is empty!")

          when(headEntry.isValid && headEntry.robIdx === robIdToReleaseFromCommitSlot && headEntry.waitOn.commit) {
            val clearedEntry = LoadQueueEntry(lsuConfig).setDefault().allowOverride()
            clearedEntry.isValid := False
            writeEntryOnEdge(headPhysIdx, clearedEntry)

            success := True
            ParallaxSim.debug(L"[LQPlugin] Released LQ Ptr ${currentReleaseFullPtr} (PhysIdx ${headPhysIdx}) for ROB ID ${robIdToReleaseFromCommitSlot} from commit slot ${i.toString}")
          } otherwise {
            ParallaxSim.warning(
              L"[LQPlugin] WARNING: Commit slot ${i.toString} fired for ROB ID ${robIdToReleaseFromCommitSlot}, but LQ head (Ptr ${currentReleaseFullPtr}, ROB ID ${headEntry.robIdx}) mismatch or not ready. isValid=${headEntry.isValid}, waitOn.commit=${headEntry.waitOn.commit}"
            )
          }
        }
        // The accumulator for the next iteration is the previous count plus 1 if this iteration was successful.
        releasedCountSoFar + success.asUInt
    }

    hw.commitPtrReg := hw.commitPtrReg + releasedThisCycleCount
  }

  private val flushLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin flushLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin flushLogic: Elaborate logic")

    when(hw.lqFlushPortInst.valid) {
      val flushCmd = hw.lqFlushPortInst.payload
      ParallaxSim.debug(L"[LQPlugin] Flush received: Reason=${flushCmd.reason}, TargetROB=${flushCmd.targetRobIdx}")

      // Simplified flush: reset pointers and invalidate all entries.
      // A precise rollback would compare ROB IDs of LQ entries with flushCmd.targetRobIdx.
      hw.allocPtrReg := 0
      hw.commitPtrReg := 0
      for (i <- 0 until lsuConfig.lqDepth) {
        val entryToClear = LoadQueueEntry(lsuConfig).setDefault().allowOverride()
        entryToClear.isValid := False
        writeEntryOnEdge(U(i), entryToClear)
      }
      ParallaxSim.debug(L"[LQPlugin] LQ Flushed (Simplified: All entries invalidated, pointers reset).")
    }
  }
}

// Helper for Exception Codes (should be in a common place)
object ExceptionCode {
  def LOAD_ADDR_MISALIGNED = U(4, 8 bits) // Example code
  def LOAD_ACCESS_FAULT    = U(5, 8 bits) // Example code
  // Add other codes as needed
}
// -- MODIFICATION END --
