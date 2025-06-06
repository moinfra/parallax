// -- MODIFICATION START (Restore MARK_READY_FOR_COMMIT logic and other fixes) --
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

class LoadQueuePlugin(val lsuConfig: LsuConfig, val pipelineConfig: PipelineConfig)
    extends Plugin
    with LoadQueueService
    with LockedImpl {
  ParallaxLogger.log(s"LQPlugin: Creating LoadQueue with config: $lsuConfig")
  val enableLog = true
  // --- Helper for ROB ID comparison (handles generation bit wrap-around) ---
  private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
    val genA = robPtrA.msb
    val idxA = robPtrA(robPtrA.high - 1 downto 0)
    val genB = robPtrB.msb
    val idxB = robPtrB(robPtrB.high - 1 downto 0)

    (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
  }

  // --- Hardware Definition Area ---
  private val hw = create early new Area {
    ParallaxLogger.debug(s"LQPlugin: early stage")
    val entries = Mem(LoadQueueEntry(lsuConfig), lsuConfig.lqDepth)

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

    if (enableLog) ParallaxSim.debug(L"[LQPlugin] LQ is empty: ${isEmpty}, Full: ${isFull}, Count: ${entryCount}")

    // Service Port Instantiation
    val allocatePortInst = Stream(LoadQueueEntry(lsuConfig))
    val statusUpdatePortInst = Flow(LqStatusUpdate(lsuConfig))
    val releasePortsInst = Vec(Flow(UInt(pipelineConfig.robPtrWidth)), lsuConfig.commitWidth)
    val releaseMaskInst = Vec(Bool(), lsuConfig.commitWidth)
    val loadRequestPortInst = Stream(LsuAguRequest(lsuConfig))
    val lqFlushPortInst = Flow(ROBFlushPayload(pipelineConfig.robPtrWidth))

    // Internal feedback path to mark AGU dispatch
    val aguDispatchUpdatePort = Flow(LqStatusUpdate(lsuConfig))
    aguDispatchUpdatePort.valid := False
    aguDispatchUpdatePort.payload.assignDontCare()
  }

  // --- Service Implementation ---
  override def getAllocatePort(): Stream[LoadQueueEntry] = hw.allocatePortInst
  override def getStatusUpdatePort(): Flow[LqStatusUpdate] = hw.statusUpdatePortInst
  override def getReleasePorts(): Vec[Flow[UInt]] = hw.releasePortsInst
  override def getReleaseMask(): Vec[Bool] = hw.releaseMaskInst
  override def getLoadRequestPort(): Stream[LsuAguRequest] = hw.loadRequestPortInst
  override def getLQFlushPort(): Flow[ROBFlushPayload] = hw.lqFlushPortInst

  private def getPhysicalIndex(ptr: UInt): UInt = ptr(lsuConfig.lqIdxWidth.value - 1 downto 0)

  private val allocationLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin allocationLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin allocationLogic: Elaborate logic")
    hw.allocatePortInst.ready := !hw.isFull

    when(hw.allocatePortInst.fire) {
      assert(!hw.isFull, "LQ allocation fired when LQ is full! Upstream should check ready.")
      val newEntry = hw.allocatePortInst.payload
      val currentAllocPhysIdx = getPhysicalIndex(hw.allocPtrReg)

      newEntry.lqPtr := hw.allocPtrReg

      hw.entries.write(currentAllocPhysIdx, newEntry)
      hw.allocPtrReg := hw.allocPtrReg + 1
    }
  }

  private val requestGenerationLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin requestGenerationLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin requestGenerationLogic: Elaborate logic")

    val candidatesValid = Vec(Bool(), lsuConfig.lqDepth)
    val candidatesAguRequest = Vec.fill(lsuConfig.lqDepth)(LsuAguRequest(lsuConfig).assignDontCare())

    for (i <- 0 until lsuConfig.lqDepth) {
      val currentReadFullPtr = hw.commitPtrReg + U(i, lsuConfig.lqPtrWidth)
      val currentReadPhysIdx = getPhysicalIndex(currentReadFullPtr)
      val entry = hw.entries.readAsync(currentReadPhysIdx)

      val isWithinValidRange = U(i, log2Up(lsuConfig.lqDepth) + 1 bits) < hw.entryCount

      candidatesValid(i) := isWithinValidRange &&
        entry.isValid &&
        (entry.lqPtr === currentReadFullPtr) &&
        entry.waitOn.isStalledForAgu &&
        !entry.waitOn.aguDispatched

      when(candidatesValid(i)) {
        val aguReq = LsuAguRequest(lsuConfig)
        aguReq.basePhysReg := entry.aguBasePhysReg
        aguReq.immediate := entry.aguImmediate.resized
        aguReq.accessSize := entry.accessSize
        aguReq.usePc := entry.aguUsePcAsBase
        aguReq.pcForAgu := entry.pc
        aguReq.robPtr := entry.robPtr
        aguReq.isLoad := True
        aguReq.isStore := False
        aguReq.qPtr := entry.lqPtr
        aguReq.physDestOrSrc := entry.physDest
        aguReq.physDestOrSrcIsFpr := entry.physDestIsFpr
        aguReq.writePhysDestEn := entry.writePhysDestEn
        candidatesAguRequest(i) := aguReq
      }
    }

    val selectedOh = OHMasking.first(candidatesValid)
    val selectedValid = selectedOh.orR
    val selectedData = MuxOH(selectedOh, candidatesAguRequest)

    hw.loadRequestPortInst.valid := selectedValid
    hw.loadRequestPortInst.payload := selectedData

    when(hw.loadRequestPortInst.fire) {
      if (enableLog)
        ParallaxSim.debug(
          L"[LQPlugin] AGU Request sent for LQ Ptr ${selectedData.qPtr} (ROB ID ${selectedData.robPtr})"
        )
      hw.aguDispatchUpdatePort.valid := True
      hw.aguDispatchUpdatePort.payload.lqPtr := selectedData.qPtr
      hw.aguDispatchUpdatePort.payload.updateType := LqUpdateType.AGU_DISPATCHED
    }
  }

  private val statusUpdateLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Elaborate logic")

    val updateTaken = Bool()
    val update = LqStatusUpdate(lsuConfig)
    update.assignDontCare()

    when(hw.statusUpdatePortInst.valid) {
      updateTaken := True
      update := hw.statusUpdatePortInst.payload
    }.elsewhen(hw.aguDispatchUpdatePort.valid) {
      updateTaken := True
      update := hw.aguDispatchUpdatePort.payload
    }.otherwise {
      updateTaken := False
    }

    when(updateTaken) {
      val lqPhysIdxToUpdate = getPhysicalIndex(update.lqPtr)
      val currentEntry = hw.entries.readAsync(lqPhysIdxToUpdate)

      when(currentEntry.isValid && currentEntry.lqPtr === update.lqPtr) {
        val updatedEntryData = CombInit(currentEntry)

        if (enableLog)
          ParallaxSim.debug(
            L"[LQPlugin] Status Update for LQ Ptr ${update.lqPtr} (ROB ${currentEntry.robPtr}): Type=${update.updateType}"
          )

        // **FIX**: Restored the full switch statement from the version that passed earlier tests.
        switch(update.updateType) {
          is(LqUpdateType.AGU_DISPATCHED) {
            updatedEntryData.waitOn.aguDispatched := True
          }
          is(LqUpdateType.ADDRESS_GENERATED) {
            updatedEntryData.physicalAddress := update.physicalAddressAg
            updatedEntryData.waitOn.addressGenerated := True
            updatedEntryData.waitOn.dCacheRsp := True
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
              updatedEntryData.waitOn.dCacheRefill := currentEntry.waitOn.dCacheRefill | update.dCacheRefillSlotDr
              updatedEntryData.waitOn.dCacheRefillAny := currentEntry.waitOn.dCacheRefillAny | update.dCacheRefillAnyDr
              updatedEntryData.waitOn.aguDispatched := False
              updatedEntryData.waitOn.addressGenerated := False
            } otherwise {
              updatedEntryData.dataFromDCache := update.dataFromDCacheDr
            }
          }
          is(LqUpdateType.DCACHE_REFILL_WAIT) {
              updatedEntryData.waitOn.dCacheRefill := currentEntry.waitOn.dCacheRefill | update.dCacheRefillSlotDr
              updatedEntryData.waitOn.dCacheRefillAny := currentEntry.waitOn.dCacheRefillAny | update.dCacheRefillAnyDr
          }
          is(LqUpdateType.DCACHE_REFILL_DONE) {
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
            updatedEntryData.waitOn.sqBypass := False
            when(update.sqBypassSuccessSb){
                updatedEntryData.finalData := update.dataFromSqBypassSb
                updatedEntryData.waitOn.sqCompletion := False
            }
          }
          is(LqUpdateType.SQ_OLDER_STORE_COMPLETED) {
            updatedEntryData.waitOn.sqCompletion := False
          }
          is(LqUpdateType.EXCEPTION_OCCURRED) {
            updatedEntryData.waitOn.commit := True
            updatedEntryData.waitOn.addressGenerated := True; updatedEntryData.waitOn.dCacheRsp := False
            updatedEntryData.waitOn.dCacheRefill := 0; updatedEntryData.waitOn.dCacheRefillAny := False
            updatedEntryData.waitOn.sqBypass := False; updatedEntryData.waitOn.sqCompletion := False
          }
          is(LqUpdateType.MARK_READY_FOR_COMMIT) {
            when(currentEntry.waitOn.isReadyForCommit) {
                 updatedEntryData.waitOn.commit := True
            } otherwise {
                ParallaxSim.warning(L"[LQPlugin] LQ Ptr ${update.lqPtr} (ROB ${currentEntry.robPtr}): MARK_READY_FOR_COMMIT received, but not all waits cleared. Current waits: ${currentEntry.waitOn.format}")
            }
          }
        }
        hw.entries.write(lqPhysIdxToUpdate, updatedEntryData)
      } otherwise {
        ParallaxSim.warning(
          L"[LQPlugin] Stale or invalid status update ignored for LQ Ptr ${update.lqPtr}. Current entry valid: ${currentEntry.isValid}, current ptr: ${currentEntry.lqPtr}"
        )
      }
    }
  }

  private val releaseLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin releaseLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin releaseLogic: Elaborate logic")

    val releasedThisCycleCount =
      (0 until lsuConfig.commitWidth).foldLeft(U(0, log2Up(lsuConfig.commitWidth + 1) bits)) {
        (releasedCountSoFar, i) =>
          val robPtrToReleaseFromCommitSlot = hw.releasePortsInst(i).payload
          val doReleaseForThisSlot = hw.releasePortsInst(i).valid && hw.releaseMaskInst(i) && !hw.isEmpty

          val currentReleaseFullPtr = hw.commitPtrReg + releasedCountSoFar
          val headPhysIdx = getPhysicalIndex(currentReleaseFullPtr)
          val headEntry = hw.entries.readAsync(headPhysIdx)

          val success = False

          when(doReleaseForThisSlot) {
            assert(!hw.isEmpty, "LQ release fired when LQ is empty!")

            when(
              headEntry.isValid &&
                headEntry.lqPtr === currentReleaseFullPtr &&
                headEntry.robPtr === robPtrToReleaseFromCommitSlot &&
                headEntry.waitOn.commit
            ) {
              // Use setDefault to ensure a clean slate, preventing stale data issues
              val clearedEntry = LoadQueueEntry(lsuConfig).setDefault()
              hw.entries.write(headPhysIdx, clearedEntry)

              success := True
              if (enableLog)
                ParallaxSim.debug(
                  L"[LQPlugin] Released LQ Ptr ${currentReleaseFullPtr} for ROB ID ${robPtrToReleaseFromCommitSlot}"
                )
            } otherwise {
              ParallaxSim.warning(
                L"[LQPlugin] WARNING: Commit for ROB ID ${robPtrToReleaseFromCommitSlot} mismatch at LQ head. Expected Ptr: ${currentReleaseFullPtr}, Head Ptr: ${headEntry.lqPtr}, Head ROB: ${headEntry.robPtr}, Head waitOn.commit: ${headEntry.waitOn.commit}"
              )
            }
          }
          releasedCountSoFar + success.asUInt
      }

    hw.commitPtrReg := hw.commitPtrReg + releasedThisCycleCount
  }

  private val flushLogic = create late new Area {
    lock.await()
    when(hw.lqFlushPortInst.valid) {
      val flushCmd = hw.lqFlushPortInst.payload
      if (enableLog) ParallaxSim.debug(L"[LQPlugin] Precise Flush received: TargetROB=${flushCmd.targetRobPtr}")

      val newPtr = flushCmd.targetRobPtr.resized
      hw.allocPtrReg := newPtr
      hw.commitPtrReg := newPtr

      for (i <- 0 until lsuConfig.lqDepth) {
        val entryIdx = U(i, lsuConfig.lqIdxWidth)
        val entry = hw.entries.readAsync(entryIdx)
        when(entry.isValid && isNewerOrSame(entry.robPtr, flushCmd.targetRobPtr)) {
          // Use setDefault to ensure a clean slate
          val clearedEntry = LoadQueueEntry(lsuConfig).setDefault()
          hw.entries.write(entryIdx, clearedEntry)
          if (enableLog)
            ParallaxSim.debug(L"[LQPlugin] Invalidating LQ entry with ROB ID ${entry.robPtr} due to flush.")
        }
      }
    }
  }
}

// Helper for Exception Codes (should be in a common place)
object ExceptionCode {
  def LOAD_ADDR_MISALIGNED = U(4, 8 bits) // Example code
  def LOAD_ACCESS_FAULT = U(5, 8 bits) // Example code
  // Add other codes as needed
}
// -- MODIFICATION END --
