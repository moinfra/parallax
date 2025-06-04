package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import parallax.components.rob.{ROBFlushPayload, ROBFlushReason, ROBService}
import parallax.components.dcache2.DataCachePluginConfig

// --- Service Definition ---
trait LoadQueueService extends Service with LockedImpl {
  def newAllocatePort(): Stream[RenamedUop] // Changed to Stream for backpressure
  def newStatusUpdatePort(): Flow[LqStatusUpdate]
  def newReleasePort(): Vec[Flow[UInt]]
  def newReleaseMask(): Vec[Bool]
  def getLoadRequestPort(): Stream[LsuAguRequest]
  def getLQFlushPort(): Flow[ROBFlushPayload[RenamedUop]]
}

// --- Configuration ---
case class LoadQueueConfig(
    pCfg: PipelineConfig,
    lqDepth: Int,
    sqIdxWidth: BitCount,
    dCacheConfig: DataCachePluginConfig
) {
  val lqIdxWidth = log2Up(lqDepth) bits
  val lqPtrWidth = (lqIdxWidth.value + 1).bits
  require(isPow2(lqDepth), "LQ depth must be a power of 2 for simple pointer arithmetic.")
  val commitWidth = pCfg.commitWidth
}

class LoadQueuePlugin(val lqConfig: LoadQueueConfig) extends Plugin with LoadQueueService with LockedImpl {

  import lqConfig._

  private val entries = Mem(LoadQueueEntry(pCfg, lqIdxWidth, sqIdxWidth, dCacheConfig), lqDepth)
  private val defaultLqEntry = LoadQueueEntry(pCfg, lqIdxWidth, sqIdxWidth, dCacheConfig).setDefault()
  entries.init(Seq.fill(lqDepth)(defaultLqEntry))

  private val allocPtrReg = Reg(UInt(lqPtrWidth)) init (0)
  private val commitPtrReg = Reg(UInt(lqPtrWidth)) init (0)
  private val entryCount = UInt(log2Up(lqDepth + 1) bits)
  private val isFull = Bool()
  private val isEmpty = Bool()

  private val ptrsSameGen = allocPtrReg.msb === commitPtrReg.msb
  private val allocIdxForCount = allocPtrReg(lqIdxWidth.value - 1 downto 0)
  private val commitIdxForCount = commitPtrReg(lqIdxWidth.value - 1 downto 0)

  when(ptrsSameGen) {
    entryCount := (allocIdxForCount - commitIdxForCount).resize(entryCount.getWidth)
  } otherwise {
    entryCount := (U(lqDepth, entryCount.getWidth bits) + allocIdxForCount - commitIdxForCount)
      .resize(entryCount.getWidth)
  }
  isFull := entryCount === lqDepth
  isEmpty := entryCount === 0

  // Service Port Instantiation
  private val allocatePortInst = Stream(RenamedUop(pCfg)) // Changed to Stream
  private val statusUpdatePortInst = Flow(LqStatusUpdate(pCfg, lqIdxWidth, dCacheConfig))
  private val releasePortsInst = Vec(Flow(UInt(pCfg.robIdxWidth)), commitWidth)
  private val releaseMaskInst = Vec(Bool(), commitWidth)
  private val loadRequestPortInst = Stream(LsuAguRequest(pCfg, lqIdxWidth, sqIdxWidth))
  private val lqFlushPortInst = Flow(ROBFlushPayload[RenamedUop](pCfg.robIdxWidth))

  // Service Implementation
  override def newAllocatePort(): Stream[RenamedUop] = allocatePortInst // Changed to Stream
  override def newStatusUpdatePort(): Flow[LqStatusUpdate] = statusUpdatePortInst
  override def newReleasePort(): Vec[Flow[UInt]] = releasePortsInst
  override def newReleaseMask(): Vec[Bool] = releaseMaskInst
  override def getLoadRequestPort(): Stream[LsuAguRequest] = loadRequestPortInst
  override def getLQFlushPort(): Flow[ROBFlushPayload[RenamedUop]] = lqFlushPortInst

  private def getEntryIndex(ptrWithGenBit: UInt): UInt = ptrWithGenBit(lqIdxWidth.value - 1 downto 0)

  private def readEntryAsync(idxPtr: UInt): LoadQueueEntry = {
    entries.readAsync(getEntryIndex(idxPtr))
  }
  private def writeEntry(idxPtr: UInt, data: LoadQueueEntry): Unit = {
    entries.write(getEntryIndex(idxPtr), data)
  }

  // --- Internal Logic Areas ---

  private val allocationLogic = create late new Area {
    lock.await()
    // LQ is ready to allocate if it's not full.
    allocatePortInst.ready := !isFull // Drive ready for the Stream interface

    val currentAllocIdx = getEntryIndex(allocPtrReg)

    when(allocatePortInst.fire) { // .fire incorporates .valid & .ready
      assert(!isFull, "LQ allocation fired when LQ is full! Upstream should check ready.")

      val newEntry = LoadQueueEntry(pCfg, lqIdxWidth, sqIdxWidth, dCacheConfig).setDefault()
      newEntry.uop := allocatePortInst.payload
      newEntry.lqId := currentAllocIdx
      newEntry.isValid := True
      newEntry.waitOn.address := True
      newEntry.memCtrlFlags := allocatePortInst.payload.decoded.memCtrl
      newEntry.exception.robIdx := allocatePortInst.payload.robIdx

      writeEntry(allocPtrReg, newEntry)
      allocPtrReg := allocPtrReg + 1
      ParallaxSim.log(
        L"[LQPlugin] Allocated LQ genPtr ${allocPtrReg} (idx ${currentAllocIdx}) for ROB ID ${allocatePortInst.payload.robIdx}"
      )
    }
  }

  private val requestGenerationLogic = create late new Area {
    // TODO: Consider pipelining or hierarchical arbitration if lqDepth is large.
    lock.await()

    val candidatesValid = Vec(Bool(), lqDepth)
    val candidatesAguRequest = Vec(LsuAguRequest(pCfg, lqIdxWidth, sqIdxWidth), lqDepth)
    val numValidEntriesInQueue = entryCount

    for (i <- 0 until lqDepth) {
      val currentReadGenPtr = commitPtrReg + U(i)
      val currentReadIdx = getEntryIndex(currentReadGenPtr)
      val entry = readEntryAsync(currentReadGenPtr)

      val isWithinValidRange = U(i) < numValidEntriesInQueue
      candidatesValid(i) := isWithinValidRange &&
        entry.isValid &&
        entry.waitOn.address &&
        !entry.waitOn.robFlush &&
        !entry.waitOn.commit

      val aguReq = LsuAguRequest(pCfg, lqIdxWidth, sqIdxWidth)
      aguReq.uop := entry.uop
      aguReq.memCtrlFlags := entry.memCtrlFlags
      aguReq.isLoad := True
      aguReq.isStore := False
      aguReq.qId := currentReadIdx
      aguReq.robIdx := entry.robIdx
      candidatesAguRequest(i) := aguReq
    }

    val selectedValid = candidatesValid.orR
    val selectedOh = OHMasking.first(candidatesValid)
    val selectedData = MuxOH(selectedOh, candidatesAguRequest)

    loadRequestPortInst.valid := selectedValid
    loadRequestPortInst.payload := selectedData

    when(loadRequestPortInst.fire) {
      ParallaxSim.log(L"[LQPlugin] AGU Request sent for LQ ID ${selectedData.qId} (ROB ID ${selectedData.robIdx})")
    }
  }

  private val statusUpdateLogic = create late new Area {
    lock.await()

    when(statusUpdatePortInst.fire) {
      val update = statusUpdatePortInst.payload
      val lqIdToUpdate = update.lqId

      val currentEntry = readEntryAsync(lqIdToUpdate)
      val updatedEntryData = CombInit(currentEntry)

      ParallaxSim.log(
        L"[LQPlugin] Status Update for LQ ID ${lqIdToUpdate}: AddrGen=${update.addressGenerated}, MarkCommit=${update.markReadyForCommit}"
      )

      // Assuming sender drives fields meaningfully. If a field is not relevant for an update,
      // its value in 'update' payload should not cause unintended changes.
      // This requires careful construction of LqStatusUpdate messages by senders.
      // For truly optional fields, LqStatusUpdate would need explicit valid bits per field.

      when(update.addressGenerated) { // If this update indicates address is generated
        updatedEntryData.physicalAddress := update.physicalAddress
        updatedEntryData.addressValid := True
        updatedEntryData.waitOn.address := False
        updatedEntryData.waitOn.dCacheRsp := True
        ParallaxSim.log(
          L"[LQPlugin] LQ ID ${lqIdToUpdate}: Address generated ${update.physicalAddress}, now waiting D-Cache."
        )
      }

      when(update.finalDataValid || update.dCacheMissOrFault) {
        updatedEntryData.waitOn.dCacheRsp := False
        when(update.finalDataValid && !update.dCacheMissOrFault) {
          updatedEntryData.finalData := Mux(update.sqBypassComplete, update.dataFromSqBypass, update.dataFromDCache)
          ParallaxSim.log(L"[LQPlugin] LQ ID ${lqIdToUpdate}: Data received. SQBypassDone=${update.sqBypassComplete}")
        }
        when(update.dCacheMissOrFault) {
          when(update.dCacheNeedsRedo) {
            updatedEntryData.waitOn.dCacheRefill := update.dCacheRefillSlot
            updatedEntryData.waitOn.dCacheRefillAny := update.dCacheRefillAny
            ParallaxSim.log(
              L"[LQPlugin] LQ ID ${lqIdToUpdate}: D-Cache redo. Waiting refill slot ${update.dCacheRefillSlot} / any ${update.dCacheRefillAny}"
            )
          } otherwise {
            updatedEntryData.exception.hasException := True
            updatedEntryData.exception.exceptionCode := update.dCacheFaultCode
            updatedEntryData.waitOn.commit := True
            ParallaxSim.log(
              L"[LQPlugin] LQ ID ${lqIdToUpdate}: D-Cache fault (or redo not specified) ${update.dCacheFaultCode}."
            )
          }
        }
      }

      when(update.sqBypassAvailable) {
        updatedEntryData.waitOn.sqBypass := True
        updatedEntryData.dataFromSq := update.dataFromSqBypass
        ParallaxSim.log(L"[LQPlugin] LQ ID ${lqIdToUpdate}: SQ Bypass available.")
      }
      when(update.sqBypassComplete || update.sqOlderStoreCompleted) {
        updatedEntryData.waitOn.sqBypass := False
        updatedEntryData.waitOn.sqCompletion := False
        ParallaxSim.log(L"[LQPlugin] LQ ID ${lqIdToUpdate}: SQ Bypass/Completion resolved.")
      }

      when(update.exceptionOccurred) {
        updatedEntryData.exception.hasException := True
        updatedEntryData.exception.exceptionCode := update.exceptionCode
        updatedEntryData.waitOn.commit := True
        ParallaxSim.log(L"[LQPlugin] LQ ID ${lqIdToUpdate}: External Exception Occurred ${update.exceptionCode}.")
      }

      when(update.markReadyForCommit && !updatedEntryData.exception.hasException) {
        val canBeCommitted = !updatedEntryData.waitOn.address &&
          !updatedEntryData.waitOn.translation &&
          !updatedEntryData.waitOn.dCacheRsp &&
          !updatedEntryData.waitOn.dCacheRefill.orR &&
          !updatedEntryData.waitOn.dCacheRefillAny &&
          !updatedEntryData.waitOn.sqBypass &&
          !updatedEntryData.waitOn.sqCompletion &&
          !updatedEntryData.waitOn.robFlush
        when(canBeCommitted) {
          updatedEntryData.waitOn.commit := True
          ParallaxSim.log(L"[LQPlugin] LQ ID ${lqIdToUpdate}: Marked ready for commit.")
        } otherwise {
          ParallaxSim.warning(
            L"[LQPlugin] LQ ID ${lqIdToUpdate}: markReadyForCommit received, but other waits pending. Not marking. Waits: Addr=${updatedEntryData.waitOn.address}, DCRsp=${updatedEntryData.waitOn.dCacheRsp}, Refill=${(updatedEntryData.waitOn.dCacheRefill.orR || updatedEntryData.waitOn.dCacheRefillAny)}, SQB=${updatedEntryData.waitOn.sqBypass}, SQC=${updatedEntryData.waitOn.sqCompletion}"
          )
        }
      }
      writeEntry(lqIdToUpdate, updatedEntryData)
    }
  }

  private val releaseLogic = create late new Area {
    lock.await()

    var tempCommitPtr = commitPtrReg

    for (i <- 0 until commitWidth) {
      val robIdToReleaseFromCommitSlot = releasePortsInst(i).payload
      val doReleaseForThisSlot = releasePortsInst(i).fire && releaseMaskInst(i) && !isEmpty

      when(doReleaseForThisSlot) {
        assert(!isEmpty, "LQ release fired when LQ is empty!")

        val headEntry = readEntryAsync(tempCommitPtr)

        when(headEntry.isValid && headEntry.robIdx === robIdToReleaseFromCommitSlot && headEntry.waitOn.commit) {
          val clearedEntry = LoadQueueEntry(pCfg, lqIdxWidth, sqIdxWidth, dCacheConfig).setDefault()
          clearedEntry.isValid := False
          writeEntry(tempCommitPtr, clearedEntry)

          tempCommitPtr \= tempCommitPtr + 1
          ParallaxSim.log(L"[LQPlugin] Released LQ (genPtr ${tempCommitPtr}, idx ${getEntryIndex(
              tempCommitPtr - U(1, tempCommitPtr.getWidth bits)
            )}) for ROB ID ${robIdToReleaseFromCommitSlot} from commit slot ${i.toString}")
        } otherwise {
          ParallaxSim.warning(
            L"[LQPlugin] WARNING: Commit slot ${i.toString} fired for ROB ID ${robIdToReleaseFromCommitSlot}, but LQ head (genPtr ${tempCommitPtr}, ROB ID ${headEntry.robIdx}) mismatch or not ready. isValid=${headEntry.isValid}, waitOn.commit=${headEntry.waitOn.commit}"
          )
          assert(
            False,
            L"LQ Release Mismatch: CommitSlot ROBID=${robIdToReleaseFromCommitSlot} vs LQHead ROBID=${headEntry.robIdx}, isValid=${headEntry.isValid}, waitCommit=${headEntry.waitOn.commit}"
          )
        }
      }
    }
    commitPtrReg := tempCommitPtr
  }

  private val flushLogic = create late new Area {
    lock.await()

    when(lqFlushPortInst.fire) {
      val flushCmd = lqFlushPortInst.payload
      ParallaxSim.log(L"[LQPlugin] Flush received: Reason=${flushCmd.reason}, TargetROB=${flushCmd.targetRobIdx}")

      allocPtrReg := 0
      commitPtrReg := 0

      for (i <- 0 until lqDepth) {
        val entryToClear = LoadQueueEntry(pCfg, lqIdxWidth, sqIdxWidth, dCacheConfig).setDefault()
        entryToClear.isValid := False
        entries.write(U(i), entryToClear)
      }
      ParallaxSim.log(L"[LQPlugin] LQ Flushed. Pointers reset, all entries invalidated.")
    }
  }

  // Default assignments for service ports
  allocatePortInst.valid := False
  allocatePortInst.payload.assignDontCare()
  statusUpdatePortInst.valid := False
  statusUpdatePortInst.payload.assignDontCare()
  releasePortsInst.foreach { p => p.valid := False; p.payload.assignDontCare() }
  releaseMaskInst.foreach(_ := False) // Mask is an input, should be driven by Commit
  loadRequestPortInst.valid := False
  loadRequestPortInst.payload.assignDontCare()
  lqFlushPortInst.valid := False
  lqFlushPortInst.payload.assignDontCare()
}
