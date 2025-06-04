package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.ROBFlushPayload // Assuming ROBFlushPayload is accessible
import parallax.components.dcache2.DataCachePluginConfig
import parallax.utilities.Service
import parallax.utilities.LockedImpl

// --- Wait-On States for LQ/SQ ---
// These bits indicate what an entry is waiting for.
// An entry is "ready" for the next step if all relevant wait bits are false.
case class LqWaitOn(val dCacheConfig: DataCachePluginConfig) extends Bundle {
  val address       = Bool() // Waiting for AGU to produce the address
  val translation   = Bool() // Waiting for MMU/TLB translation (if applicable, here assumed physical)
  val dCacheRsp     = Bool() // Waiting for D-Cache response (hit/miss/data)
  val dCacheRefill  = Bits(dCacheConfig.refillCount bits) // Waiting for specific D-Cache refill slots
  val dCacheRefillAny = Bool() // Waiting for any D-Cache refill to complete
  val sqBypass      = Bool() // Waiting for a potential store-to-load bypass from SQ
  val sqCompletion  = Bool() // Waiting for a specific older store in SQ to complete
  val commit        = Bool() // Entry is complete, waiting for ROB to commit it
  val robFlush      = Bool() // Entry is stalled due to a ROB flush/reschedule

//   def clearAll(): Unit = {
//     address       := False
//     translation   := False
//     dCacheRsp     := False
//     dCacheRefill  := 0
//     dCacheRefillAny := False
//     sqBypass      := False
//     sqCompletion  := False
//     commit        := False
//     robFlush      := False
//   }

  def isStalled: Bool = address | translation | dCacheRsp | dCacheRefill.orR |
                        dCacheRefillAny | sqBypass | sqCompletion | robFlush
}

case class SqWaitOn(val dCacheConfig: DataCachePluginConfig) extends Bundle {
  val address       = Bool() // Waiting for AGU to produce the address
  val translation   = Bool() // Waiting for MMU/TLB translation
  val prfRead       = Bool() // Waiting for store data to be read from PRF
  val dCacheStoreRsp = Bool() // Waiting for D-Cache store/writeback response (e.g., ack or error)
  val dCacheRefill  = Bits(dCacheConfig.refillCount bits) // If a store causes a line allocation/refill
  val dCacheRefillAny = Bool()
  val youngerLoadStall = Bool() // Stalled because a younger load that conflicts needs to be replayed
  val robFlush      = Bool() // Entry is stalled due to a ROB flush/reschedule

//   def clearAll(): Unit = {
//     address       := False
//     translation   := False
//     prfRead       := False
//     dCacheStoreRsp := False
//     dCacheRefill  := 0
//     dCacheRefillAny := False
//     youngerLoadStall := False
//     robFlush      := False
//   }

  def isStalled: Bool = address | translation | prfRead | dCacheStoreRsp |
                        dCacheRefill.orR | dCacheRefillAny | youngerLoadStall | robFlush
}


// --- Load Queue Entry ---
case class LoadQueueEntry(
    val pCfg: PipelineConfig,
    val lqIdxWidth: BitCount,
    val sqIdxWidth: BitCount,
    val dCacheConfig: DataCachePluginConfig // For LqWaitOn
) extends Bundle with HasRobIdx {
  // --- Static Info (from Dispatch) ---
  val uop             = RenamedUop(pCfg) // Contains original decoded info and renamed phys regs
  val lqId            = UInt(lqIdxWidth)
  val isValid         = Bool()           // Is this LQ entry valid?
  val isLoadLinked    = Bool()           // LR instruction

  // --- Dynamic Info (updated during LSU pipeline) ---
  val physicalAddress = UInt(pCfg.pcWidth)
  val addressValid    = Bool()           // Physical address is calculated and valid
  val memCtrlFlags    = MemCtrlFlags()   // Copied from uop.decoded.memCtrl for convenience
  val dataFromSq      = Bits(pCfg.dataWidth) // Data bypassed from SQ
  val dataFromDCache  = Bits(pCfg.dataWidth) // Data from D-Cache
  val finalData       = Bits(pCfg.dataWidth) // Selected data after bypass/cache
  val exception       = RobCompletionMsg(pCfg) // Stores any exception encountered

  val waitOn          = LqWaitOn(dCacheConfig)
  val sqIdToWaitFor   = UInt(sqIdxWidth) // If waiting for a specific SQ entry

  // --- LR/SC ---
  val lrScReserved    = Bool() // For SC to check if reservation is held by this load's address
                               // Naxriscv has a global reservation, this is an alternative for entry-based

  override def robIdx: UInt = uop.robIdx

  def setDefault(): this.type = {
    uop.setDefault()
    lqId := 0
    isValid := False
    isLoadLinked := False
    physicalAddress := 0
    addressValid := False
    memCtrlFlags.setDefault()
    dataFromSq := 0
    dataFromDCache := 0
    finalData := 0
    exception.hasException := False
    exception.exceptionCode := 0
    exception.robIdx := 0 // Will be set from uop.robIdx
    waitOn.clearAll()
    sqIdToWaitFor := 0
    lrScReserved := False
    this
  }
}

// --- Store Queue Entry ---
case class StoreQueueEntry(
    val pCfg: PipelineConfig,
    val sqIdxWidth: BitCount,
    val lqIdxWidth: BitCount,
    val dCacheConfig: DataCachePluginConfig // For SqWaitOn
) extends Bundle with HasRobIdx {
  // --- Static Info (from Dispatch) ---
  val uop             = RenamedUop(pCfg)
  val sqId            = UInt(sqIdxWidth)
  val isValid         = Bool()
  // val isStoreCond     = Bool()  // SC instruction (deferred)
  // val isAtomic        = Bool()  // Atomic instruction (deferred)
  // val atomicOp        = Bits(5 bits) (deferred)

  // --- Dynamic Info ---
  val physicalAddress = UInt(pCfg.pcWidth)
  val addressValid    = Bool()
  val dataToWrite     = Bits(pCfg.dataWidth)
  val dataValid       = Bool()           // Store data is available (read from PRF)
  val memCtrlFlags    = MemCtrlFlags()
  val exception       = RobCompletionMsg(pCfg)

  val waitOn          = SqWaitOn(dCacheConfig)
  val committed       = Bool()           // ROB has committed this store
  val canWriteToDCache = Bool()          // All conditions met to send to D-Cache write port

  // --- LR/SC ---
  // val scSuccess       = Bool() // For SC result (deferred)

  override def robIdx: UInt = uop.robIdx

  def setDefault(): this.type = {
    uop.setDefault()
    sqId := 0
    isValid := False
    // isStoreCond := False
    // isAtomic := False
    // atomicOp := 0
    physicalAddress := 0
    addressValid := False
    dataToWrite := 0
    dataValid := False
    memCtrlFlags.setDefault()
    exception.hasException := False
    exception.exceptionCode := 0
    exception.robIdx := 0
    waitOn.clearAll()
    committed := False
    canWriteToDCache := False
    // scSuccess := False
    this
  }
}

// --- LSU Internal Payloads for AGU/DCache Communication ---
// Payload from LQ/SQ to AGU
case class LsuAguRequest(pCfg: PipelineConfig, lqIdWidth: BitCount, sqIdWidth: BitCount) extends Bundle {
  val uop             = RenamedUop(pCfg) // For base register, immediate, pc
  val memCtrlFlags    = MemCtrlFlags()   // For access size
  val isLoad          = Bool()
  val isStore         = Bool()
  val qId             = UInt(Math.max(lqIdWidth.value, sqIdWidth.value) bits) // LQ or SQ ID
  val robIdx          = UInt(pCfg.robIdxWidth) // For context

  def toAguInput(physRegBase: UInt): AguInput = { // Helper to convert to AguInput
    val aguIn = AguInput()
    aguIn.basePhysReg := physRegBase // This needs to be resolved before calling
    aguIn.immediate   := uop.decoded.imm(11 downto 0).asSInt // Assuming I-type/S-type offset
    aguIn.accessSize  := memCtrlFlags.size.asBits.resized.asUInt // MemAccessSize to UInt
    aguIn.usePc       := False // LSU generally uses GPRs, PC-rel handled by decoder into GPR
    aguIn.pc          := uop.decoded.pc
    aguIn.robId       := robIdx
    aguIn.isLoad      := isLoad
    aguIn.isStore     := isStore
    aguIn.physDst     := uop.rename.physDest.idx // For loads, or AMO dest
    aguIn
  }
}

// Payload from AGU back to LQ/SQ or DCache stage
case class LsuAddressGenerated(pCfg: PipelineConfig, lqIdWidth: BitCount, sqIdWidth: BitCount) extends Bundle {
  val physicalAddress = UInt(pCfg.pcWidth)
  val alignException  = Bool()
  val memCtrlFlags    = MemCtrlFlags()
  val isLoad          = Bool()
  val isStore         = Bool()
  val qId             = UInt(Math.max(lqIdWidth.value, sqIdWidth.value) bits)
  val robIdx          = UInt(pCfg.robIdxWidth)
  val uop             = RenamedUop(pCfg) // Pass through for context, esp. physDest for loads

  def fromAguOutput(aguOut: AguOutput, uopIn: RenamedUop, memCtrlIn: MemCtrlFlags, qIdIn: UInt): this.type = {
    physicalAddress := aguOut.address
    alignException  := aguOut.alignException
    memCtrlFlags    := memCtrlIn
    isLoad          := aguOut.isLoad
    isStore         := aguOut.isStore
    qId             := qIdIn
    robIdx          := aguOut.robId
    uop             := uopIn
    this
  }
}

// Payload to DCache for Load
case class DCacheLoadReq(pCfg: PipelineConfig, lqIdWidth: BitCount) extends Bundle {
  val physicalAddress = UInt(pCfg.pcWidth)
  val size            = MemAccessSize()
  val isSigned        = Bool()
  val lqId            = UInt(lqIdWidth) // To tag the request
  val robIdx          = UInt(pCfg.robIdxWidth)
}

// Payload from DCache for Load
case class DCacheLoadRsp(pCfg: PipelineConfig, lqIdWidth: BitCount) extends Bundle {
  val data            = Bits(pCfg.dataWidth)
  val fault           = Bool() // Access error from D-Cache/memory system
  val lqId            = UInt(lqIdWidth)
  val robIdx          = UInt(pCfg.robIdxWidth)
  // D-Cache refill info (passed through from DataCachePlugin.DataLoadPort.Rsp)
  val dCacheNeedsRedo = Bool()
  val refillSlot      = Bits(1 bits) // Simplified, assume single refill slot for now. Match DCachePlugin.
  val refillSlotAny   = Bool()
}

// Payload to DCache for Store
case class DCacheStoreReq(pCfg: PipelineConfig, sqIdWidth: BitCount) extends Bundle {
  val physicalAddress = UInt(pCfg.pcWidth)
  val data            = Bits(pCfg.dataWidth)
  val mask            = Bits(pCfg.dataWidth.value / 8 bits)
  val size            = MemAccessSize()
  val sqId            = UInt(sqIdWidth) // To tag the request
  val robIdx          = UInt(pCfg.robIdxWidth)
}

// Payload from DCache for Store
case class DCacheStoreRsp(pCfg: PipelineConfig, sqIdWidth: BitCount) extends Bundle {
  val fault           = Bool()
  val sqId            = UInt(sqIdWidth)
  val robIdx          = UInt(pCfg.robIdxWidth)
  // D-Cache refill info for store-allocate
  val dCacheNeedsRedo = Bool()
  val refillSlot      = Bits(1 bits) // Simplified.
  val refillSlotAny   = Bool()
}

case class LqStatusUpdate(pCfg: PipelineConfig, lqIdWidth: BitCount, dCacheConfig: DataCachePluginConfig) extends Bundle {
  val lqId                = UInt(lqIdWidth)
  val addressGenerated    = Bool()
  val physicalAddress     = UInt(pCfg.pcWidth)
  val dataFromDCache      = Bits(pCfg.dataWidth)
  val dataFromSqBypass    = Bits(pCfg.dataWidth)
  val finalDataValid      = Bool() // True if data is ready (from DCache or SQ bypass)
  val dCacheHit           = Bool() // Optional: if dcache can provide hit info early
  val dCacheMissOrFault   = Bool()
  val dCacheFaultCode     = UInt(pCfg.exceptionCodeWidth) // If fault
  val dCacheNeedsRedo     = Bool()
  val dCacheRefillSlot    = Bits(dCacheConfig.refillCount bits)
  val dCacheRefillAny     = Bool()
  val sqBypassAvailable   = Bool()
  val sqBypassComplete    = Bool()
  val sqOlderStoreCompleted = Bool()
  val exceptionOccurred   = Bool()
  val exceptionCode       = UInt(pCfg.exceptionCodeWidth)
  val markReadyForCommit  = Bool()
}

trait StoreQueueService extends Service with LockedImpl {
  def newAllocatePort(): Flow[RenamedUop] // Dispatch sends RenamedUop to SQ
  def newStatusUpdatePort(): Flow[SqStatusUpdate] // AGU/DCache/Scheduler/PRF updates SQ entry status
  def newCommitNotifyPort(): Flow[UInt] // Commit notifies SQ entry is committed by ROB ID
  def newReleasePort(): Flow[UInt] // DCache writeback completion releases SQ entry by SQ ID

  def getStoreDataReadPort(): Stream[SqDataReadRequest] // SQ requests data from PRF
  def dataReadDone(sqId: UInt, data: Bits): Unit // PRF provides data back to SQ

  def getStoreAguRequestPort(): Stream[LsuAguRequest] // SQ sends store requests to Scheduler for AGU
  def getStoreWritebackPort(): Stream[DCacheStoreReq] // SQ sends committed stores to DCache
  def getSQFlushPort(): Flow[ROBFlushPayload[RenamedUop]]
}

case class SqStatusUpdate(pCfg: PipelineConfig, sqIdWidth: BitCount, dCacheConfig: DataCachePluginConfig) extends Bundle {
  val sqId                = UInt(sqIdWidth)
  val addressGenerated    = Bool()
  val physicalAddress     = UInt(pCfg.pcWidth)
  val dataFromPrfValid    = Bool()
  val dataFromPrf         = Bits(pCfg.dataWidth)
  val dCacheStoreAck      = Bool() // Store completed by DCache/memory
  val dCacheStoreFault    = Bool()
  val dCacheStoreFaultCode= UInt(pCfg.exceptionCodeWidth)
  val dCacheNeedsRedo     = Bool() // e.g., for store-allocate miss
  val dCacheRefillSlot    = Bits(dCacheConfig.refillCount bits)
  val dCacheRefillAny     = Bool()
  val youngerLoadNeedsReplay = Bool() // If this store causes a younger load to replay
  val exceptionOccurred   = Bool()
  val exceptionCode       = UInt(pCfg.exceptionCodeWidth)
  val markReadyForWriteback = Bool()
}

case class SqDataReadRequest(pCfg: PipelineConfig, sqIdWidth: BitCount) extends Bundle {
  val sqId         = UInt(sqIdWidth)
  val physRegSrc   = UInt(pCfg.physGprIdxWidth) // Assuming GPR for store data
  // Add isFPR if needed
}
