// filename: hw/spinal/parallax/common/LsuT
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.components.rob.ROBFlushPayload // 假设 ROBFlushPayload 是可访问的
import parallax.common.HasRobPtr
import parallax.components.lsu.AguInput
import parallax.common.RenamedUop
import parallax.common.PipelineConfig
import parallax.common.MemAccessSize
import parallax.utilities.Formattable

case class LsuConfig(
    lqDepth: Int,
    sqDepth: Int,
    robPtrWidth: BitCount,
    pcWidth: BitCount,
    dataWidth: BitCount,
    physGprIdxWidth: BitCount,
    exceptionCodeWidth: BitCount,
    commitWidth: Int,
    dcacheRefillCount: Int // 从 DataCachePluginConfig.pipelineConfig 中提取
) {
  val lqIdxWidth = log2Up(lqDepth) bits
  val lqPtrWidth = (lqIdxWidth.value + 1).bits // 物理索引 + 1位世代/进位

  val sqIdxWidth = log2Up(sqDepth) bits
  val sqPtrWidth = (sqIdxWidth.value + 1).bits

  val qIdxWidth = BitCount(Math.max(lqIdxWidth.value, sqIdxWidth.value))
  val qPtrWidth = (qIdxWidth.value + 1).bits
}

// --- Wait-On States for LQ/SQ ---
// These bits indicate what an entry is waiting for.
case class LqWaitOn(val lsuConfig: LsuConfig) extends Bundle with Formattable { // 移除 dCacheConfig，只保留 lsuConfig
  val aguDispatched = Bool() // True if sent to AGU, waiting for address
  val address = Bool() // True if AGU has produced the address
  val dCacheRsp = Bool() // Waiting for D-Cache response (hit/miss/data)
  val dCacheRefill = Bits(lsuConfig.dcacheRefillCount bits) // Waiting for specific D-Cache refill slots
  val dCacheRefillAny = Bool() // Waiting for any D-Cache refill to complete
  val sqBypass = Bool() // Waiting for a potential store-to-load bypass from SQ
  val sqCompletion = Bool() // Waiting for a specific older store in SQ to complete
  val commit = Bool() // Entry is complete, waiting for ROB to commit it
  val robFlush = Bool() // Entry is stalled due to a ROB flush/reschedule

  def setDefault(): this.type = {
    aguDispatched := False // Make sure to default new fields
    address := False
    dCacheRsp := False
    dCacheRefill := 0
    dCacheRefillAny := False
    sqBypass := False
    sqCompletion := False
    commit := False
    robFlush := False
    this
  }

  def isStalledForAgu: Bool = !address && !robFlush
  def isStalledForDCache: Bool = address && dCacheRsp && !robFlush
  def isStalledForCommit: Bool = commit && !robFlush // Waiting for ROB to pick it up
  def isReadyForCommit: Bool = address && !dCacheRsp && !dCacheRefill.orR &&
    !dCacheRefillAny && !sqBypass && !sqCompletion && !robFlush && !commit

  def format: Seq[Any] = {
    Seq(
      "LQWaitOn(",
      L"aguDispatched=$aguDispatched",
      L"address=$address",
      L"dCacheRsp=$dCacheRsp",
      L"dCacheRefill=$dCacheRefill",
      L"dCacheRefillAny=$dCacheRefillAny",
      L"sqBypass=$sqBypass",
      L"sqCompletion=$sqCompletion",
      L"commit=$commit",
      L"robFlush=$robFlush)"
    )
  }
}

case class SqWaitOn(val lsuConfig: LsuConfig) extends Bundle { // 移除 dCacheConfig，只保留 lsuConfig
  val address = Bool() // True if AGU has produced the address
  val dCacheStoreRsp = Bool() // Waiting for D-Cache store/writeback response
  val dCacheRefill = Bits(lsuConfig.dcacheRefillCount bits) // If a store causes a line allocation/refill
  val dCacheRefillAny = Bool()
  val youngerLoadStall = Bool() // Stalled because a younger load that conflicts needs to be replayed
  val robFlush = Bool() // Entry is stalled due to a ROB flush/reschedule

  def setDefault(): this.type = {
    address := False
    dCacheStoreRsp := False
    dCacheRefill := U(0, lsuConfig.dcacheRefillCount bits).asBits
    dCacheRefillAny := False
    youngerLoadStall := False
    robFlush := False
    this
  }
  def isStalledForAgu: Bool = !address && !robFlush
  def isStalledForPrfRead: Bool = address && !robFlush
  def isStalledForDCache: Bool = address && dCacheStoreRsp && !robFlush
}

// --- Load Queue Entry (扁平化) ---
case class LoadQueueEntry(
    val lsuConfig: LsuConfig // 替换 pCfg
) extends Bundle
    with HasRobPtr {
  // --- Static Info (from Dispatch) ---
  val robPtr = UInt(lsuConfig.robPtrWidth) // 指令在 ROB 中的 ID (包含世代位)
  val lqPtr = UInt(lsuConfig.lqPtrWidth) // LQ 内部物理索引
  val pc = UInt(lsuConfig.pcWidth)
  val isValid = Bool() // Is this LQ entry valid?

  // Decoded instruction info needed for execution
  val physDest = UInt(lsuConfig.physGprIdxWidth) // 目标物理寄存器
  val physDestIsFpr = Bool()
  val writePhysDestEn = Bool() // 是否需要写入物理目标寄存器

  val accessSize = MemAccessSize()
  val isSignedLoad = Bool()
  // Base register for AGU (if not PC-relative)
  val aguBasePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val aguBaseIsFpr = Bool() // 通常为 False
  val aguUsePcAsBase = Bool()
  val aguImmediate = SInt(12 bits) // 假设 S-type/I-type offset

  // --- Dynamic Info (updated during LSU pipeline) ---
  val physicalAddress = UInt(lsuConfig.pcWidth) // 由 AGU 计算
  val dataFromSq = Bits(lsuConfig.dataWidth) // Data bypassed from SQ
  val dataFromDCache = Bits(lsuConfig.dataWidth) // Data from D-Cache
  val finalData = Bits(lsuConfig.dataWidth) // Selected data after bypass/cache

  val waitOn = LqWaitOn(lsuConfig) // 传递 lsuConfig
  val sqIdToWaitFor = UInt(lsuConfig.sqPtrWidth) // 关联的 SQ ID (物理索引 + 进位)
  val sqIdToWaitForValid = Bool()

  def setDefault(): this.type = {
    robPtr := 0; lqPtr := 0; pc := 0; isValid := False
    physDest := 0; physDestIsFpr := False; writePhysDestEn := False
    aguBasePhysReg := 0; aguBaseIsFpr := False; aguUsePcAsBase := False; aguImmediate := 0
    physicalAddress := 0
    dataFromSq := 0; dataFromDCache := 0; finalData := 0
    waitOn.setDefault()
    sqIdToWaitFor := 0
    sqIdToWaitForValid := False
    accessSize := MemAccessSize.W // 默认字访问
    isSignedLoad := False
    this
  }
}

// --- Store Queue Entry (扁平化) ---
case class StoreQueueEntry(
    val lsuConfig: LsuConfig // 替换 pCfg
) extends Bundle
    with HasRobPtr {
  // --- Static Info (from Dispatch) ---
  val robPtr = UInt(lsuConfig.robPtrWidth) // 指令在 ROB 中的 ID (包含世代位)
  val sqPtr = UInt(lsuConfig.sqPtrWidth)
  val pc = UInt(lsuConfig.pcWidth)
  val isValid = Bool()

  // Decoded instruction info
  val physDataSrc = UInt(lsuConfig.physGprIdxWidth) // 存储数据的源物理寄存器
  val physDataSrcIsFpr = Bool()

  // Base register for AGU (if not PC-relative)
  val aguBasePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val aguBaseIsFpr = Bool()
  val aguUsePcAsBase = Bool()
  val aguImmediate = SInt(12 bits)

  val accessSize = MemAccessSize()
  // --- Dynamic Info ---
  val physicalAddress = UInt(lsuConfig.pcWidth)
  val dataToWrite = Bits(lsuConfig.dataWidth) // 从 PRF 读取的数据
  val storeMask = Bits(lsuConfig.dataWidth.value / 8 bits) // 根据大小和地址低位计算

  val waitOn = SqWaitOn(lsuConfig) // 传递 lsuConfig
  val committed = Bool() // ROB has committed this store
  val dataReadFromPrf = Bool() // Store data has been read from PRF
  val address = Bool() // AGU has generated the address

  val lqIdToReplay = UInt(lsuConfig.lqPtrWidth) // 如果需要重放年轻加载
  val lqIdToReplayValid = Bool()

  def setDefault(): this.type = {
    robPtr := 0; sqPtr := 0; pc := 0; isValid := False
    physDataSrc := 0; physDataSrcIsFpr := False
    aguBasePhysReg := 0; aguBaseIsFpr := False; aguUsePcAsBase := False; aguImmediate := 0
    physicalAddress := 0; dataToWrite := 0; storeMask := 0
    waitOn.setDefault()
    committed := False; dataReadFromPrf := False; address := False
    lqIdToReplay := 0; lqIdToReplayValid := False
    accessSize := MemAccessSize.W

    this
  }
}

// --- LSU Internal Payloads for AGU/DCache Communication ---
// Payload from LQ/SQ Scheduler to AGU
case class LsuAguRequest(lsuConfig: LsuConfig) extends Bundle { // 替换 pCfg
  // AGU Input fields
  val basePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val immediate = SInt(12 bits)
  val accessSize = MemAccessSize()
  val usePc = Bool()
  val pcForAgu = UInt(lsuConfig.pcWidth) // PC 值，如果 usePc 为 true

  // Context to pass through AGU
  // 本来打算直通的，但是好像让 AGU 直通这些信息不太符合迪米特法则。
  val robPtr = UInt(lsuConfig.robPtrWidth)
  val isLoad = Bool()
  val isStore = Bool()
  val qPtr = UInt(Math.max(lsuConfig.lqPtrWidth.value, lsuConfig.sqPtrWidth.value) bits)
  val physDestOrSrc = UInt(lsuConfig.physGprIdxWidth) // 对于 Load 是 physDest, 对于 Store 是 physDataSrc
  val physDestOrSrcIsFpr = Bool()
  val writePhysDestEn = Bool() // 对于 Load

  def toAguInput(): AguInput = {
    val aguIn = AguInput(lsuConfig)
    aguIn.basePhysReg := basePhysReg
    aguIn.immediate := immediate
    aguIn.accessSize := accessSize
    aguIn.usePc := usePc
    aguIn.pc := pcForAgu
    aguIn.robPtr := robPtr
    aguIn.isLoad := isLoad
    aguIn.isStore := isStore
    aguIn.physDst := Mux(isLoad, physDestOrSrc, U(0)) // AGU 的 physDst 主要用于 Load
    aguIn
  }
}

// --- LSU 内部状态更新消息 (与之前的定义类似，但使用新的 LQ/SQ Entry 字段) ---
// 用于更新 LQ 条目状态的消息
case class LqStatusUpdate(lsuConfig: LsuConfig) extends Bundle { // 替换 pCfg, 移除 dCacheConfig
  val lqPtr = UInt(lsuConfig.lqPtrWidth) // 目标 LQ 条目 ID (物理索引)
  val updateType = LqUpdateType()

  // Data for ADDRESS_GENERATED
  val physicalAddressAg = UInt(lsuConfig.pcWidth)
  val alignExceptionAg = Bool()

  // Data for DCACHE_RESPONSE
  val dataFromDCacheDr = Bits(lsuConfig.dataWidth)
  val dCacheFaultDr = Bool()
  val dCacheRedoDr = Bool()

  // Data for DCACHE_REFILL_WAIT / DCACHE_REFILL_DONE
  val dCacheRefillSlotDr = Bits(lsuConfig.dcacheRefillCount bits)
  val dCacheRefillAnyDr = Bool()

  // Data for SQ_BYPASS
  val dataFromSqBypassSb = Bits(lsuConfig.dataWidth)
  val sqBypassSuccessSb = Bool()

  // Data for SQ_COMPLETION (no specific data, just the event)

  // Data for EXCEPTION_OCCURRED
  val exceptionOccurredEx = Bool()
  val exceptionCodeEx = UInt(lsuConfig.exceptionCodeWidth)

  // Data for MARK_READY_FOR_COMMIT (no specific data, just the event)
}

object LqUpdateType extends SpinalEnum {
  val AGU_DISPATCHED, ADDRESS_GENERATED, DCACHE_RESPONSE, DCACHE_REFILL_WAIT, DCACHE_REFILL_DONE, SQ_BYPASS_READY,
      SQ_OLDER_STORE_COMPLETED, EXCEPTION_OCCURRED, MARK_READY_FOR_COMMIT = newElement()
}

// 用于更新 SQ 条目状态的消息
case class SqStatusUpdate(lsuConfig: LsuConfig) extends Bundle { // 替换 pCfg, 移除 dCacheConfig
  val sqPtr = UInt(lsuConfig.sqPtrWidth) // 目标 SQ 条目 ID (物理索引)
  val updateType = SqUpdateType()

  // Data for ADDRESS_GENERATED
  val physicalAddressAg = UInt(lsuConfig.pcWidth)
  val alignExceptionAg = Bool()
  val storeMaskAg = Bits(lsuConfig.dataWidth.value / 8 bits) // Mask is calculated with address

  // Data for PRF_DATA_READY
  val dataFromPrfPdr = Bits(lsuConfig.dataWidth)

  // --- 来自 DCache Store Response 的数据 ---
  val dCacheFaultDsr = Bool()
  val dCacheRedoDsr = Bool()
  val dCacheRefillSlotDsr = Bits(lsuConfig.dcacheRefillCount bits) // DSR = DCache Store Response
  val dCacheRefillAnyDsr = Bool() // DSR = DCache Store Response

  // --- 来自 DCache Refill Done 事件的数据 ---
  val dCacheRefillSlotDr = Bits(lsuConfig.dcacheRefillCount bits) // DR = DCache Refill (Done)
  val dCacheRefillAnyDr = Bool()

  // Data for YOUNGER_LOAD_REPLAY
  val lqIdToReplayYlr = UInt(lsuConfig.lqPtrWidth)

  // Data for EXCEPTION_OCCURRED
  val exceptionOccurredEx = Bool()
  val exceptionCodeEx = UInt(lsuConfig.exceptionCodeWidth)

  // Data for MARK_COMMITTED (no specific data, just the event)
  // Data for MARK_WRITTEN_BACK (no specific data, just the event)
}

object SqUpdateType extends SpinalEnum {
  val ADDRESS_GENERATED, DCACHE_STORE_RESPONSE, DCACHE_REFILL_WAIT, DCACHE_REFILL_DONE,
      YOUNGER_LOAD_REPLAY, EXCEPTION_OCCURRED, MARK_COMMITTED, MARK_WRITTEN_BACK = newElement()
}

// --- LSU 核心模块到 PRF 的写回消息 (LSU 自己的结果) ---
// LsuCompletionMsg 保持不变，因为它已经是扁平化的

// --- 重命名阶段到 LSU 的 Uop (包含分配的 LQ/SQ ID) ---
case class LsuAllocateReq(lsuConfig: LsuConfig, pipelineConfig: PipelineConfig) extends Bundle { // 替换 pCfg
  val uop = RenamedUop(pipelineConfig) // 包含所有重命名后的信息
  // 分配的 LQ/SQ ID (物理索引 + 进位位)
  val lqIdFull = UInt(lsuConfig.lqPtrWidth)
  val sqIdFull = UInt(lsuConfig.sqPtrWidth)
  val allocateLq = Bool() // 是否为该 uop 分配 LQ
  val allocateSq = Bool() // 是否为该 uop 分配 SQ
}

// SqDataReadRequest
case class SqDataReadRequest(lsuConfig: LsuConfig) extends Bundle { // 替换 pCfg, 移除 sqIdWidth
  val sqId = UInt(lsuConfig.sqIdxWidth) // 使用 lsuConfig 中的 sqIdxWidth
  val physRegSrc = UInt(lsuConfig.physGprIdxWidth) // Assuming GPR for store data
  // Add isFPR if needed
}
