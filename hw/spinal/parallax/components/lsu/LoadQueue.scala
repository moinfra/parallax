// -- MODIFICATION START (Apply Ptr/Idx naming convention) --
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

  // --- Helper for ROB ID comparison (handles generation bit wrap-around) ---
  private def isNewerOrSame(robIdA: UInt, robIdB: UInt): Bool = {
    val genA = robIdA.msb
    val idxA = robIdA(robIdA.high - 1 downto 0)
    val genB = robIdB.msb
    val idxB = robIdB(robIdB.high - 1 downto 0)

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

    // Service Port Instantiation
    val allocatePortInst = Stream(LoadQueueEntry(lsuConfig))
    val statusUpdatePortInst = Flow(LqStatusUpdate(lsuConfig))
    val releasePortsInst = Vec(Flow(UInt(pipelineConfig.robIdxWidth)), lsuConfig.commitWidth)
    val releaseMaskInst = Vec(Bool(), lsuConfig.commitWidth) // Gemini 注意：这里是无方向信号，不要擅自加上 in
    val loadRequestPortInst = Stream(LsuAguRequest(lsuConfig))
    val lqFlushPortInst = Flow(ROBFlushPayload(pipelineConfig.robIdxWidth))

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

  private def getPhysicalIndex(ptr: UInt): UInt = ptr(lsuConfig.lqIdxWidth.value - 1 downto 0) // RENAMED for clarity

  private val allocationLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin allocationLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin allocationLogic: Elaborate logic")
    hw.allocatePortInst.ready := !hw.isFull

    when(hw.allocatePortInst.fire) {
      assert(!hw.isFull, "LQ allocation fired when LQ is full! Upstream should check ready.")
      val newEntry = hw.allocatePortInst.payload
      val currentAllocPhysIdx = getPhysicalIndex(hw.allocPtrReg)

      newEntry.lqPtr := hw.allocPtrReg // Store the full pointer (with GenBit) in the entry

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
      val currentReadFullPtr = hw.commitPtrReg + U(i, lsuConfig.lqIdxWidth)
      val currentReadPhysIdx = getPhysicalIndex(currentReadFullPtr)
      val entry = hw.entries.readAsync(currentReadPhysIdx)

      val isWithinValidRange = U(i, lsuConfig.lqIdxWidth) < hw.entryCount

      candidatesValid(i) := isWithinValidRange &&
        entry.isValid &&
        (entry.lqPtr === currentReadFullPtr) && // Ensures we are looking at the correct generation
        entry.waitOn.isStalledForAgu &&
        !entry.waitOn.aguDispatched

      when(candidatesValid(i)) {
        val aguReq = LsuAguRequest(lsuConfig)
        aguReq.basePhysReg := entry.aguBasePhysReg
        aguReq.immediate := entry.aguImmediate.resized
        aguReq.accessSize := entry.accessSize
        aguReq.usePc := entry.aguUsePcAsBase
        aguReq.pcForAgu := entry.pc
        aguReq.robIdx := entry.robIdx
        aguReq.isLoad := True
        aguReq.isStore := False
        aguReq.qPtr := entry.lqPtr // Pass the full pointer
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
      ParallaxSim.debug(L"[LQPlugin] AGU Request sent for LQ Ptr ${selectedData.qPtr} (ROB ID ${selectedData.robIdx})")
      hw.aguDispatchUpdatePort.valid := True
      hw.aguDispatchUpdatePort.payload.lqPtr := selectedData.qPtr // Use the full pointer for update
      hw.aguDispatchUpdatePort.payload.updateType := LqUpdateType.AGU_DISPATCHED
    }
  }

  private val statusUpdateLogic = create late new Area {
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Wait dependents ready")
    lock.await()
    ParallaxLogger.debug("LQPlugin statusUpdateLogic: Elaborate logic")

    val updateTaken = Bool()
    val updatePayload = LqStatusUpdate(lsuConfig)
    updatePayload.assignDontCare()

    when(hw.statusUpdatePortInst.valid) {
      updateTaken := True
      updatePayload := hw.statusUpdatePortInst.payload
    }.elsewhen(hw.aguDispatchUpdatePort.valid) {
      updateTaken := True
      updatePayload := hw.aguDispatchUpdatePort.payload
    }.otherwise {
      updateTaken := False
    }

    when(updateTaken) {
      val update = updatePayload
      val lqPhysIdxToUpdate = getPhysicalIndex(update.lqPtr) // Extract physical index from full pointer
      val currentEntry = hw.entries.readAsync(lqPhysIdxToUpdate)

      // The crucial check to prevent stale updates.
      when(currentEntry.isValid && currentEntry.lqPtr === update.lqPtr) {
        val updatedEntryData = CombInit(currentEntry)

        ParallaxSim.debug(
          L"[LQPlugin] Status Update for LQ Ptr ${update.lqPtr} (ROB ${currentEntry.robIdx}): Type=${update.updateType}"
        )

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
          default {}
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
          val robIdToReleaseFromCommitSlot = hw.releasePortsInst(i).payload
          val doReleaseForThisSlot = hw.releasePortsInst(i).valid && hw.releaseMaskInst(i) && !hw.isEmpty

          val currentReleaseFullPtr = hw.commitPtrReg + releasedCountSoFar
          val headPhysIdx = getPhysicalIndex(currentReleaseFullPtr)
          val headEntry = hw.entries.readAsync(headPhysIdx)

          val success = False

          when(doReleaseForThisSlot) {
            assert(!hw.isEmpty, "LQ release fired when LQ is empty!")

            when(
              headEntry.isValid &&
                headEntry.lqPtr === currentReleaseFullPtr && // Crucial GenBit check
                headEntry.robIdx === robIdToReleaseFromCommitSlot &&
                headEntry.waitOn.commit
            ) {
              val clearedEntry = LoadQueueEntry(lsuConfig).setDefault().allowOverride()
              clearedEntry.isValid := False
              hw.entries.write(headPhysIdx, clearedEntry)

              success := True
              ParallaxSim.debug(
                L"[LQPlugin] Released LQ Ptr ${currentReleaseFullPtr} for ROB ID ${robIdToReleaseFromCommitSlot}"
              )
            } otherwise {
              ParallaxSim.warning(
                L"[LQPlugin] WARNING: Commit for ROB ID ${robIdToReleaseFromCommitSlot} mismatch at LQ head. Expected Ptr: ${currentReleaseFullPtr}, Head Ptr: ${headEntry.lqPtr}, Head ROB: ${headEntry.robIdx}, Head waitOn.commit: ${headEntry.waitOn.commit}"
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
      ParallaxSim.debug(L"[LQPlugin] Precise Flush received: TargetROB=${flushCmd.targetRobIdx}")

      val newPtr = flushCmd.targetRobIdx.resized
      hw.allocPtrReg := newPtr
      hw.commitPtrReg := newPtr

      for (i <- 0 until lsuConfig.lqDepth) {
        val entry = hw.entries.readAsync(U(i, lsuConfig.lqIdxWidth))
        when(entry.isValid && isNewerOrSame(entry.robIdx, flushCmd.targetRobIdx)) {
          val clearedEntry = LoadQueueEntry(lsuConfig).setDefault().allowOverride()
          clearedEntry.isValid := False
          hw.entries.write(U(i, lsuConfig.lqIdxWidth), clearedEntry)
          ParallaxSim.debug(L"[LQPlugin] Invalidating LQ entry with ROB ID ${entry.robIdx} due to flush.")
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
