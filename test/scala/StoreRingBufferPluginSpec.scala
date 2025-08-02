// filename: test/scala/lsu/StoreRingBufferPluginSpec.scala
// cmd: testOnly test.scala.StoreRingBufferPluginSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import org.scalatest.funsuite.AnyFunSuite

import parallax.common._
import parallax.components.lsu._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import parallax.bus._
import parallax.components.memory._

import scala.collection.mutable
import scala.util.Random
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4, Axi4CrossbarFactory}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.amba4.axi.Axi4Shared
import parallax.utilities.ParallaxLogger.success

class TestQueue[A] {
  private val buffer = mutable.ArrayBuffer[A]()

  def enqueue(elem: A): Unit = buffer += elem
  def dequeue(): A = buffer.remove(0)
  def head: A = buffer.head
  def isEmpty: Boolean = buffer.isEmpty
  def nonEmpty: Boolean = buffer.nonEmpty
  def size: Int = buffer.size
  def apply(idx: Int): A = buffer(idx)
  def clear(): Unit = buffer.clear() // <<<<<<< ADD THIS

  def filterInPlace(p: A => Boolean): this.type = {
    // 从后往前遍历以安全地移除元素
    for (i <- (buffer.length - 1) to 0 by -1) {
      if (!p(buffer(i))) { // 如果元素不满足条件 p
        buffer.remove(i) // 就移除它
      }
    }
    this
  }

  def toList: List[A] = buffer.toList
  def find(p: A => Boolean): Option[A] = buffer.find(p)
  override def toString: String = buffer.toString()
}

// --- Event Capture Classes (Corrected Implementation) ---

// Captures a Push event by extracting primitive data types from the hardware bus.
case class PushEventCapture(
    addr: BigInt,
    data: BigInt,
    be: BigInt,
    robPtr: BigInt,
    accessSize: MemAccessSize.E,
    isIO: Boolean,
    isCoherent: Boolean,
    hasEarlyException: Boolean,
    earlyExceptionCode: BigInt,
    isFlush: Boolean
) {
  override def toString: String = f"Push(rob=0x$robPtr%x, addr=0x$addr%x, size=${accessSize.toString.head})"
}
object PushEventCapture {
  def apply(payload: StoreBufferPushCmd): PushEventCapture = {
    new PushEventCapture(
      addr = payload.addr.toBigInt,
      data = payload.data.toBigInt,
      be = payload.be.toBigInt,
      robPtr = payload.robPtr.toBigInt,
      accessSize = payload.accessSize.toEnum,
      isIO = payload.isIO.toBoolean,
      isCoherent = payload.isCoherent.toBoolean,
      hasEarlyException = payload.hasEarlyException.toBoolean,
      earlyExceptionCode = payload.earlyExceptionCode.toBigInt,
      isFlush = payload.isFlush.toBoolean
    )
  }
}

// Captures a Pop event by extracting primitive data types.
case class PopEventCapture(
    txid: BigInt,
    addr: BigInt,
    data: BigInt,
    be: BigInt,
    robPtr: BigInt,
    isCommitted: Boolean
    // Add any other fields from StoreBufferSlot you need for checking
) {
  override def toString: String = f"Pop(rob=0x$robPtr%x, txid=0x$txid%x)"
}
object PopEventCapture {
  def apply(payload: StoreBufferSlot): PopEventCapture = {
    new PopEventCapture(
      txid = payload.txid.toBigInt,
      addr = payload.addr.toBigInt,
      data = payload.data.toBigInt,
      be = payload.be.toBigInt,
      robPtr = payload.robPtr.toBigInt,
      isCommitted = payload.isCommitted.toBoolean
    )
  }
}

// These were already correct as they only capture primitive types.
case class CommitEventCapture(robPtr: BigInt) {
  override def toString: String = f"Commit(rob=0x$robPtr%x)"
}
case class FlushEventCapture(reason: FlushReason.E) {
  override def toString: String = f"Flush(${reason.toString})"
}

// Captures an IO Response event
case class IoResponseEventCapture(txid: BigInt, hasError: Boolean) {
  override def toString: String = f"IO Response(txid=0x$txid%x, hasError=${if (hasError) "true" else "false"})"
}

// Captures an SRAM Write event
case class SramWriteEventCapture(addr: BigInt, data: BigInt, be: BigInt) {
  override def toString: String = f"SRAM Write(addr=0x$addr%x, data=0x$data%x, be=0x$be%x)"
}

// A sealed trait to represent all possible events in our system
sealed trait TestEvent
case class PushEvent(capture: PushEventCapture) extends TestEvent
case class PopEvent(capture: PopEventCapture) extends TestEvent
case class CommitEvent(capture: CommitEventCapture) extends TestEvent
case class FlushEvent(capture: FlushEventCapture) extends TestEvent
case class IoResponseEvent(capture: IoResponseEventCapture) extends TestEvent
case class SramWriteEvent(capture: SramWriteEventCapture) extends TestEvent

// The NEW Cycle Event Packet
// This class holds a collection of all events that occurred in a single clock cycle.
case class CycleEventPacket(
    cycle: Long, // For debugging, track which cycle this packet belongs to
    push: Option[PushEventCapture] = None,
    pop: Option[PopEventCapture] = None,
    commit: Option[CommitEventCapture] = None,
    flush: Option[FlushEventCapture] = None,
    ioResponse: Option[IoResponseEventCapture] = None, // Add IO response to packet
    sramWrite: Option[SramWriteEventCapture] = None // Add SRAM write to packet
)

/** Defines the IO bundle for the testbench, exposing all necessary control and
  * observation points to the test driver.
  */
case class StoreRingBufferTestBenchIo(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheCfg: DataCachePluginConfig)
    extends Bundle {
  val lsuPushIn = slave Stream (StoreBufferPushCmd(pCfg, lsuCfg))
  val bypassQueryAddr = in UInt (pCfg.pcWidth)
  val bypassQuerySize = in(MemAccessSize())
  val bypassDataOut = master Flow (StoreBufferBypassData(pCfg))
  val sqQueryPort = slave(SqQueryPort(lsuCfg, pCfg))

  val robAllocateIn = in(
    ROBAllocateSlot(
      ROBConfig(
        robDepth = pCfg.robDepth,
        pcWidth = pCfg.pcWidth,
        commitWidth = pCfg.commitWidth,
        allocateWidth = pCfg.renameWidth,
        numWritebackPorts = pCfg.totalEuCount,
        uopType = HardType(RenamedUop(pCfg)),
        defaultUop = () => RenamedUop(pCfg).setDefault(),
        exceptionCodeWidth = pCfg.exceptionCodeWidth
      )
    )
  )
  val robFlushIn = slave Flow (ROBFlushPayload(robPtrWidth = pCfg.robPtrWidth))
  val robFlushOut = master Flow (ROBFlushPayload(robPtrWidth = pCfg.robPtrWidth)) // Added for flush monitoring
  val robCommitAck = in Bool () // Testbench drives this to acknowledge commit
  val canRobAllocate = out Bool ()
  val allocatedRobPtr = out(UInt(pCfg.robPtrWidth))

  // MODIFIED: Renamed from committedStores for clarity and to reflect it's a general commit signal.
  val committedUop = master(
    Flow(
      ROBFullEntry[RenamedUop](
        ROBConfig(
          robDepth = pCfg.robDepth,
          pcWidth = pCfg.pcWidth,
          commitWidth = pCfg.commitWidth,
          allocateWidth = pCfg.renameWidth,
          numWritebackPorts = pCfg.totalEuCount,
          uopType = HardType(RenamedUop(pCfg)),
          defaultUop = () => RenamedUop(pCfg).setDefault(),
          exceptionCodeWidth = pCfg.exceptionCodeWidth
        )
      )
    )
  )
}

/** 这是一个为 StoreRingBufferPlugin 创建的新测试平台顶层。
  * 它与旧的 StoreBufferFullIntegrationTestBench 结构几乎完全相同，
  * 唯一的区别是它例化了新的 `StoreRingBufferPlugin` 而不是旧的 `StoreBufferPlugin`。
  * 这样可以复用所有的连接插件和测试辅助类。
  */
class StoreRingBufferFullIntegrationTestBench(
    val pCfg: PipelineConfig,
    val lsuCfg: LsuConfig,
    val sbDepth: Int,
    val dCacheCfg: DataCachePluginConfig,
    val axiConfig: Axi4Config
) extends Component {
  val io = StoreRingBufferTestBenchIo(pCfg, lsuCfg, dCacheCfg)
  io.simPublic()

  val database = new DataBase
  val dCacheParams = DataCachePluginConfig.toDataCacheParameters(dCacheCfg)

  val mmioConfig = GenericMemoryBusConfig(
    addressWidth = axiConfig.addressWidth bits,
    dataWidth = axiConfig.dataWidth bits,
    useId = true,
    idWidth = axiConfig.idWidth bits
  )

  val framework = ProjectScope(database) on new Framework(
    Seq(
      new ROBPlugin[RenamedUop](pCfg, HardType(RenamedUop(pCfg)), () => RenamedUop(pCfg).setDefault()),
      new TestLsuWritebackPlugin(pCfg), // 模拟LSU向ROB发送完成信号
      new TestGenericEuWritebackPlugin(pCfg),
      new StoreRingBufferPlugin(pCfg, lsuCfg, dCacheParams, sbDepth, Some(mmioConfig)),
      new TestOnlyMemSystemPlugin(axiConfig, Some(mmioConfig)),
      new StoreRingBufferTestConnectionPlugin(io, pCfg, dCacheCfg)
    )
  )

  val logic = new Area {
    val popMonitor = framework.getService[StoreRingBufferPlugin].logic.pop_write_logic.popMonitor
    getSramHandle.io.simPublic()

    val ioWriteChannel = framework.getService[StoreRingBufferPlugin].hw.ioWriteChannel

    val push = framework.getService[StoreRingBufferPlugin].hw.pushPortInst
  }

  def getSramHandle(): SimulatedSRAM = framework.getService[TestOnlyMemSystemPlugin].getSram()
}

case class StoreOp(
    robPtr: BigInt,
    addr: BigInt,
    data: BigInt, // 注意：这里的 data 是已经根据 addr 对齐到字边界的数据
    be: BigInt,
    size: MemAccessSize.E,
    isIO: Boolean,
    isCoherent: Boolean,
    txid: BigInt, // Transaction ID for IO operations
    var isCommitted: Boolean = false,
    var isWaitingRsp: Boolean = false // State for IO operations waiting for response
)

/** Gold Model: 一个纯软件的行为模型，用于模拟Store Buffer的核心功能。
  * 它基于黑盒假设，不关心DUT的内部实现（如环形指针）。
  */
class StoreBufferGoldModel(capacity: Int, dataWidthBytes: Int, robPtrWidth: Int, pCfg: PipelineConfig) { // Added pCfg

  // 使用可变列表来模拟队列
  private val queue = new TestQueue[StoreOp]()
  private val memoryDefaultWord = BigInt("DEADBEEF", 16)
  private val memory = mutable.Map[BigInt, Byte]().withDefault { addr =>
    // For Little-Endian, the byte at the lowest address offset corresponds
    // to the least significant byte of the word.
    val byteOffset = (addr % dataWidthBytes).toInt
    ((memoryDefaultWord >> (byteOffset * 8)) & 0xff).toByte
  }

  // Add a txid counter to the model
  private var nextTxid: BigInt = 0

  // Internal pendingStore for pending store completions
  private val pendingStoreTxids = mutable.ListBuffer[BigInt]()

  // Internal lock for thread safety
  private val modelLock = new Object()

  def isStore(robPtr: BigInt): Boolean = modelLock.synchronized {
    queue.find(_.robPtr == robPtr).isDefined
  }

  def getPendingStoreSize: Int = modelLock.synchronized {
    pendingStoreTxids.size
  }

  def isPendingStoreEmpty: Boolean = modelLock.synchronized {
    pendingStoreTxids.isEmpty
  }

  // --- 公共API ---
  def isFull: Boolean = modelLock.synchronized { queue.size >= capacity }
  def isEmpty: Boolean = modelLock.synchronized { queue.isEmpty }
  def size: Int = modelLock.synchronized { queue.size }

  def checkForwarding(
      dutResponse: (Boolean, BigInt, Boolean),
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      isCoherentLoad: Boolean,
      comment: String = ""
  ): Boolean = modelLock.synchronized {
    val (dutHit, dutData, dutStall) = dutResponse

    // 1. Calculate the expected response based on the *current* model state.
    val (expectedHit, expectedData, expectedStall) = this.query(loadRobPtr, loadAddr, loadSize, isCoherentLoad)

    // 2. Compare the DUT's behavior with the model's prediction.
    val hitMatch = dutHit == expectedHit
    // Data only matters if both hit. If one hits and the other doesn't, it's a hit mismatch.
    val dataMatch = !dutHit || !expectedHit || (dutData == expectedData)
    val stallMatch = dutStall == expectedStall

    if (!hitMatch || !dataMatch || !stallMatch) {
      println("\n" + ("#" * 25) + " QUERY MISMATCH DETECTED " + ("#" * 25))
      val checkComment = if (comment.nonEmpty) s" ($comment)" else ""
      println(s"Mismatch details$checkComment:")
      if (!hitMatch) println(s"  - RSP.hit:   Got ${dutHit}, Expected ${expectedHit}")
      if (!dataMatch) println(f"  - RSP.data:  Got 0x${dutData.toString(16)}, Expected 0x${expectedData.toString(16)}")
      if (!stallMatch) println(s"  - RSP.stall: Got ${dutStall}, Expected ${expectedStall}")

      // 3. If there is a mismatch, provide a detailed diagnosis of why the model expected what it did.
      this.diagnoseQuery(loadRobPtr, loadAddr, loadSize, isCoherentLoad)

      return false // Indicate failure
    }

    return true // Indicate success
  }

  // The ONLY public entry point, now processes a whole cycle's worth of events at once.
  def processCycle(packet: CycleEventPacket): Unit = modelLock.synchronized {
    // println(s"--- GoldModel Processing Cycle ${packet.cycle} ---") // Uncomment for detailed cycle-by-cycle debug

    // The logic is now identical to the one we derived from analyzing the hardware RTL.
    // It correctly handles concurrent events based on hardware priority.

    if (packet.flush.isDefined) {
      // ==========================================================
      //  FLUSH PATH: Only the flush event matters.
      // ==========================================================
      val flushCapture = packet.flush.get
      // println(s"[GoldModel-FLUSH_PATH] Flush detected (Reason: ${flushCapture.reason}). Ignoring concurrent Push/Pop/Commit.") // Uncomment for detailed debug
      if (flushCapture.reason == FlushReason.FULL_FLUSH) {
        this.privateFullFlush()
      }

    } else {
      // ==========================================================
      //  NORMAL PATH: Concurrent events are processed.
      // ==========================================================
      // The order of processing here does not matter because they affect
      // different parts of the model's state, just like in the hardware's
      // parallel combinational logic.

      packet.push.foreach { capture =>
        // println(s"[GoldModel-NORMAL_PATH] Processing PUSH for robPtr=${capture.robPtr}.") // Uncomment for detailed debug
        this.privatePush( // Call the internal, private push method
          capture.robPtr,
          capture.addr,
          capture.data,
          capture.be,
          capture.accessSize,
          capture.isIO,
          capture.isCoherent
        )
      }

      packet.commit.foreach { capture =>
        // println(s"[GoldModel-NORMAL_PATH] Processing COMMIT for robPtr=${capture.robPtr}.") // Uncomment for detailed debug
        this.privateCommit(capture.robPtr)
      }

      packet.pop.foreach { capture =>
        val robPtrToPop = capture.robPtr
        // Check if a commit for the same robPtr happened in the same cycle
        val committedInSameCycle = packet.commit.exists(_.robPtr == robPtrToPop)
        val isConsideredCommitted = capture.isCommitted || committedInSameCycle
        assert(
          isConsideredCommitted,
          s"Checker: Popped slot for robPtr=$robPtrToPop was not committed in DUT (even considering same-cycle commit)!"
        )
        this.privateCompleteAndPop(capture.robPtr)
      }
    }

    // IO Responses and SRAM Writes are side effects that happen regardless of flush/normal path
    // They are processed after the main SB state updates for the cycle.
    packet.ioResponse.foreach { capture =>
      // println(s"[GoldModel] Processing IO Response for txid=${capture.txid}, error=${capture.hasError}.") // Uncomment for detailed debug
      this.privateHandleIoResponse(capture.txid, capture.hasError)
    }

    packet.sramWrite.foreach { capture =>
      // println(s"[GoldModel] Processing SRAM Write to addr=0x${capture.addr.toString(16)}, data=0x${capture.data.toString(16)}, be=0x${capture.be.toString(16)}.") // Uncomment for detailed debug
      this.privateWriteToMemory(capture.addr, capture.data, capture.be)
    }

    // println(s"--- GoldModel End of Cycle ${packet.cycle} ---") // Uncomment for detailed cycle-by-cycle debug
  }

  /** 模拟向SB推入一个新指令 */
  private def privatePush(
      robPtr: BigInt,
      addr: BigInt,
      data: BigInt, // <<-- 这是已对齐到字边界的数据
      be: BigInt,
      size: MemAccessSize.E,
      isIO: Boolean,
      isCoherent: Boolean // <<<< 新增参数
  ): Unit = { // Removed modelLock.synchronized as it's handled by processEvent
    require(!isFull, "GoldModel is full, cannot push.")
    val dataWidth = 32 // Assuming XLEN=32
    val byteOffset = (addr % dataWidthBytes).toInt
    val shiftedData = data << (byteOffset * 8)

    // --- THE KEY FIX: Truncate in the Gold Model as well ---
    val mask = (BigInt(1) << dataWidth) - 1
    val alignedData = shiftedData & mask

    // Assert that we didn't lose any information
    assert(
      (data >> (dataWidth - (byteOffset * 8))) == 0,
      s"Gold model data generation has a bug, high bits are not zero before shift: data=${data.toString(16)}, byteOffset=${byteOffset.toBigInt
          .toString(16)}, shiftedData=${shiftedData.toString(16)}, alignedData=${alignedData.toString(16)}"
    )

    // Generate and assign transaction ID
    val currentTxid = nextTxid
    val maxTxid = BigInt(1) << pCfg.transactionIdWidth
    nextTxid = (nextTxid + 1) % maxTxid // This handles the wraparound to 0 correctly.

    // When pushing an IO operation, mark it as waiting for a response
    val isWaiting = isIO
    val op = StoreOp(robPtr, addr, alignedData, be, size, isIO, isCoherent, currentTxid, false, isWaiting)
    queue.enqueue(op)

    // A store enters the scoreboard when it enters the ROB/SB
    pendingStoreTxids += op.txid
  }

  /** 模拟ROB提交一个指令 */
  private def privateCommit(robPtr: BigInt): Unit = { // Removed modelLock.synchronized
    // 从后往前找，找到最新的、尚未提交的匹配项
    queue.toList.lastIndexWhere(op => op.robPtr == robPtr && !op.isCommitted) match {
      case -1 =>
      // 没找到未提交的匹配项。
      // 可能是对一个非Store指令的commit，或者是对一个已提交Store的重复commit。忽略。
      // println(s"[GoldModel] Commit for robPtr=$robPtr received. No matching uncommitted store found. Ignored.") // Uncomment for detailed debug
      case index =>
        val op = queue(index)
        op.isCommitted = true
      // The txid was added to pendingStoreTxids at push time. No action needed here.
      // println(s"[GoldModel] Committed robPtr=${op.robPtr} (txid=${op.txid}). Model now has ${queue.size} entries.") // Uncomment for detailed debug
    }
  }

  // Method to handle IO response
  private def privateHandleIoResponse(txid: BigInt, hasError: Boolean): Unit = { // Removed modelLock.synchronized
    queue.find(_.txid == txid) match {
      case Some(op) =>
        op.isWaitingRsp = false
        if (hasError) {
          // In a real gold model, you might mark this operation as having an exception.
          // For now, we just clear the waiting status.
          // println(s"[GoldModel] IO Response ERROR for txid=${txid}") // Uncomment for detailed debug
        } else {
          // println(s"[GoldModel] IO Response SUCCESS for txid=${txid}") // Uncomment for detailed debug
        }
      case None =>
        assert(false, s"GoldModel received response for unknown txid=${txid}")
    }
  }

  private def privateCompleteAndPop(poppedRobPtr: BigInt): StoreOp = { // Removed modelLock.synchronized
    assert(queue.nonEmpty, s"GoldModel cannot pop, it's empty! DUT popped ${poppedRobPtr}, which is unexpected.")
    val op = queue.head
    assert(op.isCommitted, s"GoldModel head (robPtr=${op.robPtr}) was not committed, but DUT popped it.")
    assert(
      op.robPtr == poppedRobPtr,
      s"GoldModel head (robPtr=${op.robPtr}) does not match what DUT popped (robPtr=${poppedRobPtr})."
    )
    // Check the new state
    assert(
      !op.isWaitingRsp,
      s"GoldModel trying to pop op (robPtr=${op.robPtr}, txid=${op.txid}) that is still waiting for IO response!"
    )

    queue.dequeue()
    // Remove from pendingStoreTxids
    val index = pendingStoreTxids.indexOf(op.txid)
    assert(
      index != -1,
      s"PendingStore Error: GoldModel popped robPtr=${poppedRobPtr} (txid=${op.txid}), but this txid was not in the pendingStore! PendingStore state: ${pendingStoreTxids}"
    )
    pendingStoreTxids.remove(index)
    // println( // Uncomment for detailed debug
    //   s"[GoldModel] Popped robPtr=${op.robPtr} (txid=${op.txid}). Removed txid from pendingStore. Size: ${pendingStoreTxids.size}"
    // )
    op // Return the operation so the monitor can use its details
  }

  // --- Dedicated function to update memory model ---
  private def privateWriteToMemory(addr: BigInt, data: BigInt, be: BigInt): Unit = { // Removed modelLock.synchronized
    val baseAddr = addr - (addr % dataWidthBytes)
    for (i <- 0 until dataWidthBytes) {
      if (((be >> i) & 1) == 1) {
        val byteAddr = baseAddr + i
        val byteData = ((data >> (i * 8)) & 0xff).toByte
        memory(byteAddr) = byteData
        // println(f"[GoldModel-Mem] Write to 0x$byteAddr%x value 0x$byteData%02x (triggered by SRAM monitor)") // Uncomment for detailed debug
      }
    }
  }

  def readWordFromMemory(addr: BigInt): BigInt = modelLock.synchronized {
    // This logic now implicitly handles default values because of `withDefault`.
    val wordAddr = addr - (addr % dataWidthBytes)
    var wordData = BigInt(0)
    for (i <- 0 until dataWidthBytes) {
      val byteAddr = wordAddr + i
      // `memory(byteAddr)` will now automatically use the default logic if the key is not found.
      val byteData = memory(byteAddr) & 0xff
      wordData |= BigInt(byteData) << (i * 8)
    }
    wordData
  }

  /** 模拟从SB头部弹出一个完成的指令。
    * 在我们的模型中，一个条目在提交后就可以被视为可弹出。
    * 返回被弹出的操作，如果没有可弹出的则返回None。
    */
  def pop(): Option[StoreOp] = modelLock.synchronized {
    // A store can only be popped if it's committed AND, if it's an IO operation, its response has been received.
    if (queue.nonEmpty && queue.head.isCommitted && (!queue.head.isIO || !queue.head.isWaitingRsp)) {
      Some(queue.dequeue())
    } else {
      None
    }
  }

  // ROB指针比较函数
  private def isNewerOrSame(robPtrA: BigInt, robPtrB: BigInt): Boolean = {
    val width = robPtrWidth
    if (width <= 1) return robPtrA >= robPtrB
    val genA = (robPtrA >> (width - 1)) & 1
    val idxA = robPtrA & ((BigInt(1) << (width - 1)) - 1)
    val genB = (robPtrB >> (width - 1)) & 1
    val idxB = robPtrB & ((BigInt(1) << (width - 1)) - 1)
    (genA == genB && idxA >= idxB) || (genA != genB && idxA < idxB)
  }

  private def isOlder(robPtrA: BigInt, robPtrB: BigInt): Boolean = !isNewerOrSame(robPtrA, robPtrB)

  private def privateFullFlush(): Unit = { // Removed modelLock.synchronized
    // 日志: 打印FLUSH前的状态
    // println(s"\n[GoldModel-Debug] FLUSH START: queue=${queue.size}, pendingStore=${pendingStoreTxids.size}") // Uncomment for detailed debug

    // // 使用索引来遍历自定义的 TestQueue // Uncomment for detailed debug
    // for (i <- 0 until queue.size) {
    //   val slot = queue(i)
    //   println(s"  - Before: txid=${slot.txid} robPtr=${slot.robPtr}, isCommitted=${slot.isCommitted}")
    // }

    // 1. Identify the txids of the ops that are being flushed (the uncommitted ones).
    val txidsToFlush = queue.toList.filter(!_.isCommitted).map(_.txid)

    // 2. Flush the main queue (remove uncommitted entries).
    queue.filterInPlace(_.isCommitted)

    // 3. Flush the scoreboard by removing the corresponding txids. This is now safe and correct.
    pendingStoreTxids --= txidsToFlush

    // 日志: 打印FLUSH后的状态
    // println(s"[GoldModel-Debug] FLUSH END: queue=${queue.size}, pendingStore=${pendingStoreTxids.size}") // Uncomment for detailed debug

    // for (i <- 0 until queue.size) { // Uncomment for detailed debug
    //   val slot = queue(i)
    //   println(s"  - After: txid=${slot.txid} robPtr=${slot.robPtr}, isCommitted=${slot.isCommitted}")
    // }
  }

  /** 模拟Store-to-Load转发查询。这是Gold Model的核心验证逻辑。
    * @return (hit: Boolean, data: BigInt, olderStoreDataNotReady: Boolean)
    */
  def query(
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      isCoherentLoad: Boolean
  ): (Boolean, BigInt, Boolean) = modelLock.synchronized {
    val loadMask = MemAccessSize.toByteEnable_sw(loadSize, loadAddr.toInt & (dataWidthBytes - 1), dataWidthBytes)
    var forwardedData = BigInt(0)
    var hitMask = BigInt(0)

    var hasOlderOverlappingStore = false
    var olderOverlappingStoreIsMmio = false // Rule III.4

    // Rule II.4 & III.4: Forwarding is a privilege for Coherent operations.
    // A non-coherent (MMIO) Load cannot receive forwarded data.
    val canForwardData = isCoherentLoad

    for (store <- queue.toList.reverse) {
      if (isOlder(store.robPtr, loadRobPtr)) {
        val storeWordAddr = store.addr >> log2Up(dataWidthBytes)
        val loadWordAddr = loadAddr >> log2Up(dataWidthBytes)

        if (storeWordAddr == loadWordAddr) {
          hasOlderOverlappingStore = true

          // Rule III.4: Check for MMIO Store Hazard. An older overlapping MMIO store
          // forces a stall for ANY subsequent load.
          if (!store.isCoherent) {
            olderOverlappingStoreIsMmio = true
          }
          // Rule II.2 & II.4: Data can only be forwarded if both the Load and the Store are coherent.
          else if (canForwardData && store.isCoherent) {
            for (i <- 0 until dataWidthBytes) {
              val isByteNeeded = ((loadMask >> i) & 1) == 1
              val isByteAvailable = ((store.be >> i) & 1) == 1
              val isByteAlreadyHit = ((hitMask >> i) & 1) == 1

              if (isByteNeeded && isByteAvailable && !isByteAlreadyHit) {
                val byteData = (store.data >> (i * 8)) & 0xff
                forwardedData |= byteData << (i * 8)
                hitMask |= (BigInt(1) << i)
              }
            }
          }
        }
      }
    }

    val allRequiredBytesHit = (hitMask & loadMask) == loadMask

    // Consolidate all stall conditions based on the rulebook:
    // Rule III.4: Stall if there's an older overlapping MMIO store.
    val stallForMmioStore = olderOverlappingStoreIsMmio

    // Rule III.4: Stall if the load is MMIO and there's any older overlapping store.
    val stallForMmioLoad = !canForwardData && hasOlderOverlappingStore

    // Rule III.1 & III.2: Stall if the load is coherent but data coverage from coherent stores is incomplete.
    val stallForInsufficientCoverage = canForwardData && hasOlderOverlappingStore && !allRequiredBytesHit

    val finalStall = stallForMmioStore || stallForMmioLoad || stallForInsufficientCoverage

    // A "hit" is only when data forwarding is allowed, successful, and no stall occurs.
    val finalHit = canForwardData && allRequiredBytesHit && !finalStall

    (finalHit, forwardedData, finalStall)
  }

  override def toString: String = modelLock.synchronized {
    if (isEmpty) return "[GoldModel: Empty]"
    val content = queue.toList
      .map(op =>
        s"  Op(rob=${op.robPtr}, addr=0x${op.addr.toString(16)}, be=0x${op.be
            .toString(16)}, com=${op.isCommitted}, txid=${op.txid}, waitingRsp=${op.isWaitingRsp})"
      )
      .mkString("\n")
    s"[GoldModel: size=${size}/${capacity}, pendingStore=${pendingStoreTxids.size}\n$content\n]"
  }

  def deepClone(): StoreBufferGoldModel = {
    // 1. Create a new instance of the model.
    val newModel = new StoreBufferGoldModel(this.capacity, this.dataWidthBytes, this.robPtrWidth, this.pCfg)

    // 2. Deep copy the main store queue.
    //    'op.copy()' works because StoreOp is a case class with immutable fields.
    this.queue.toList.foreach(op => newModel.queue.enqueue(op.copy()))

    // 3. Deep copy the memory map.
    //    '++=' creates a new copy of the key-value pairs.
    newModel.memory ++= this.memory

    // 4. Copy the primitive state variables.
    newModel.nextTxid = this.nextTxid

    // 5. Deep copy the pending IO transaction scoreboard.
    //    This was the missing piece from the previous implementation.
    newModel.pendingStoreTxids ++= this.pendingStoreTxids
    // 6. Return the fully independent clone.
    newModel
  }

  /** A powerful debugging method to explain a query result.
    * It prints a step-by-step trace of how it arrived at its conclusion.
    */
  def diagnoseQuery(loadRobPtr: BigInt, loadAddr: BigInt, loadSize: MemAccessSize.E, isCoherentLoad: Boolean): Unit =
    modelLock.synchronized {
      println("\n" + ("=" * 20) + " GoldModel Query Diagnosis (Based on Arch Rules) " + ("=" * 20))

      val loadMask = MemAccessSize.toByteEnable_sw(loadSize, loadAddr.toInt & (dataWidthBytes - 1), dataWidthBytes)
      val loadWordAddr = loadAddr >> log2Up(dataWidthBytes)

      // Use toString(16) for hex representation
      println(
        f"[Load]  robPtr: ${loadRobPtr.toString(16)} addr: 0x${loadAddr.toString(16)} (word: 0x${loadWordAddr
            .toString(16)}) size: ${loadSize}%-1s mask: 0x${loadMask.toString(16)} isCoherent: ${isCoherentLoad}"
      )
      println(s" Current GoldModel State (size=${queue.size}, pendingStore=${pendingStoreTxids.size}):")
      if (queue.isEmpty) {
        println("  -> The Store Buffer model is empty.")
      } else {
        queue.toList.foreach { s =>
          println(
            f"  [Slot]  robPtr: ${s.robPtr.toString(16)} addr: 0x${s.addr.toString(16)} be: 0x${s.be.toString(16)} data: 0x${s.data
                .toString(16)} committed: ${s.isCommitted} isCoherent: ${s.isCoherent} txid: ${s.txid} waitingRsp: ${s.isWaitingRsp}"
          )
        }
      }
      println("-" * 75)

      var forwardedData = BigInt(0)
      var hitMask = BigInt(0)
      var hasOlderOverlappingStore = false
      var olderStoreIsUncoherent = false

      val canForward = isCoherentLoad
      println(s"-> Rule II.4: Forwarding Data Permission (canForward) = $canForward (based on isCoherentLoad)")

      for (store <- queue.toList.reverse) {
        val storeWordAddr = store.addr >> log2Up(dataWidthBytes)
        print(f"-> Checking [Store robPtr=${store.robPtr.toString(16)}]: ")

        if (!isOlder(store.robPtr, loadRobPtr)) {
          println("REJECTED (Store is not older)")
        } else if (storeWordAddr != loadWordAddr) {
          println("REJECTED (Address mismatch)")
        } else {
          hasOlderOverlappingStore = true
          println(s"ACCEPTED (Older & address matches)")

          if (!store.isCoherent) {
            olderStoreIsUncoherent = true
            println(f"   - Rule III.4: HAZARD! Un-coherent (MMIO) Store detected. Will force a stall.")
          } else { // store.isCoherent is true
            if (canForward) {
              println("   - Rule II.2: Coherent Store, eligible for forwarding data to Coherent Load.")
              val storeBE = store.be
              val contribution = (storeBE & loadMask) & (~hitMask)
              if (contribution > 0) {
                println(f"   - DATA: Contributing bytes for mask 0x${contribution.toString(16)}")
                // ... (data merging logic as before) ...
                hitMask |= contribution
                println(f"   - STATE: New hitMask is 0x${hitMask.toString(16)}")
              } else {
                println("   - INFO: No new bytes contributed.")
              }
            } else { // Load is not coherent
              println("   - INFO: Store is Coherent, but Load is not. No data forwarding as per Rule II.4.")
            }
          }
        }
      }

      val allRequiredBytesHit = (hitMask & loadMask) == loadMask
      
      // --- THE CRUCIAL FIX: Use the exact same stall logic as the 'query' function ---
      val stallForUncoherentStore = olderStoreIsUncoherent
      val stallForUncoherentLoad = !canForward && hasOlderOverlappingStore
      val stallForInsufficientCoverage = canForward && hasOlderOverlappingStore && !allRequiredBytesHit
      val finalStall = stallForUncoherentStore || stallForUncoherentLoad || stallForInsufficientCoverage

      val finalHit = canForward && allRequiredBytesHit && !finalStall

      println("-" * 75)
      println("[Result Analysis]")
      println(f"  - Final Hit Mask:         0x${hitMask.toString(16)} (Required: 0x${loadMask.toString(16)})")
      println(s"  - All Bytes Covered?      $allRequiredBytesHit")
      println(s"  - Older Overlapping Store? $hasOlderOverlappingStore")
      println(s"  - Stall from MMIO Store?  $stallForUncoherentStore (Rule III.4)")
      println(s"  - Stall from MMIO Load?   $stallForUncoherentLoad (Rule III.4)")
      println(s"  - Stall from Coverage?    $stallForInsufficientCoverage (Rule III.1)")
      println(s"  - Final 'stall' decision: $finalStall")
      println(s"  - Final 'hit' decision:   $finalHit")
      println("=" * 75 + "\n")
    }
}

/** StoreRingBufferPluginSpec - 针对新 StoreRingBufferPlugin 的完整测试套件
  */
class StoreRingBufferPluginSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32
  val DEFAULT_SB_DEPTH = 4

  // --- 辅助函数 (与旧测试文件相同) ---
  def createPConfig(commitWidth: Int = 1, robDepth: Int = 16, renameWidth: Int = 1): PipelineConfig = PipelineConfig(
    xlen = XLEN,
    commitWidth = commitWidth,
    robDepth = robDepth,
    renameWidth = renameWidth,
    physGprCount = 32,
    archGprCount = 32,
    fetchWidth = 1,
    dispatchWidth = 1,
    aluEuCount = 0,
    lsuEuCount = 1,
    transactionIdWidth = 8
  )

  def createLsuConfig(pCfg: PipelineConfig, lqDepth: Int, sqDepth: Int): LsuConfig = LsuConfig(
    lqDepth,
    sqDepth,
    pCfg.robPtrWidth,
    pCfg.pcWidth,
    pCfg.dataWidth,
    pCfg.physGprIdxWidth,
    pCfg.exceptionCodeWidth,
    pCfg.commitWidth,
    2
  )

  def createDCacheConfig(pCfg: PipelineConfig): DataCachePluginConfig = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = pCfg.dataWidth.value,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 64,
    transactionIdWidth = pCfg.transactionIdWidth
  )

  def createAxi4Config(pCfg: PipelineConfig): Axi4Config = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = pCfg.xlen,
    idWidth = 8,
    useLock = false,
    useCache = false,
    useProt = true,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  test("SB_Ring_Fuzzy_Test_With_Cycle_Packet_Architecture") {
    val sbDepth = 8
    val robDepthVal = 32
    val pCfg = createPConfig(robDepth = robDepthVal)
    val lsuCfg = createLsuConfig(pCfg, 16, sbDepth)
    val dCacheCfg = createDCacheConfig(pCfg)
    val axiConfig = createAxi4Config(pCfg)

    SimConfig.withVcdWave
      .compile(new StoreRingBufferFullIntegrationTestBench(pCfg, lsuCfg, sbDepth, dCacheCfg, axiConfig))
      .doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)

        // 1. ================== SETUP ==================
        val helper = new StoreRingBufferTestHelper(dut)
        helper.init()
        val rand = new Random(42)
        val dataWidthBytes = pCfg.dataWidth.value / 8
        val sramSizeBytes = dCacheCfg.cacheSize // e.g., 1024
        val sramSizeWords = sramSizeBytes / dataWidthBytes

        // --- 关键步骤: 内存填充 ---
        helper.sramFillWithPattern(BigInt("DEADBEEF", 16), sramSizeWords)

        // 2. 软件模型和状态
        val goldModel = new StoreBufferGoldModel(sbDepth, dataWidthBytes, pCfg.robPtrWidth.value, pCfg)

        // The central queue now holds entire cycle packets.
        val cyclePacketQueue = new mutable.Queue[CycleEventPacket]()
        var cycleCounter: Long = 0

        val addrPool = List.tabulate(64)(i => 0x0000 + i)
        val accessSizes = List(MemAccessSize.B, MemAccessSize.H, MemAccessSize.W)

        val sram = dut.getSramHandle()

        // 2. ================== GLOBAL MONITOR ==================
        // This single monitor runs every cycle, creating and queueing the CycleEventPacket.
        // This replaces all individual monitor forks.
        val globalMonitor = fork {
          while (true) {
            dut.clockDomain.waitSampling()
            helper.processPendingEvents(cyclePacketQueue, goldModel)
            cycleCounter += 1

            // Capture all potential events in this cycle
            val pushCapture =
              if (dut.io.lsuPushIn.valid.toBoolean && dut.io.lsuPushIn.ready.toBoolean)
                Some(PushEventCapture(dut.io.lsuPushIn.payload))
              else None
            val popCapture =
              if (dut.logic.popMonitor.valid.toBoolean) Some(PopEventCapture(dut.logic.popMonitor.payload)) else None
            // Commit needs to check both valid and ack from testbench to ensure it's a "fired" commit
            val commitCapture =
              if (dut.io.committedUop.valid.toBoolean)
                Some(CommitEventCapture(dut.io.committedUop.payload.payload.uop.robPtr.toBigInt))
              else None
            val flushCapture =
              if (dut.io.robFlushOut.valid.toBoolean) Some(FlushEventCapture(dut.io.robFlushOut.payload.reason.toEnum))
              else None

            val ioRspPort = dut.logic.ioWriteChannel.get.rsp
            val ioResponseCapture =
              if (ioRspPort.valid.toBoolean)
                Some(IoResponseEventCapture(ioRspPort.payload.id.toBigInt, ioRspPort.payload.error.toBoolean))
              else None

            // Check for SRAM write (assuming sram.io.ram.ce_n and sram.io.ram.we_n are active low)
            val sramWriteCapture = if (!sram.io.ram.ce_n.toBoolean && !sram.io.ram.we_n.toBoolean) {
              val writeAddr = sram.io.ram.addr.toBigInt
              val writeData = sram.io.ram.data.write.toBigInt
              val be_n = sram.io.ram.be_n.toBigInt
              val be = ~be_n & ((BigInt(1) << dataWidthBytes) - 1) // Convert active-low be_n to active-high be
              Some(SramWriteEventCapture(writeAddr, writeData, be))
            } else None

            // Create the packet for this cycle
            val packet = CycleEventPacket(
              cycle = cycleCounter,
              push = pushCapture,
              pop = popCapture,
              commit = commitCapture,
              flush = flushCapture,
              ioResponse = ioResponseCapture,
              sramWrite = sramWriteCapture
            )

            // Only enqueue if there's at least one event in the packet, or if it's a critical cycle for debugging
            if (
              packet.push.isDefined || packet.pop.isDefined || packet.commit.isDefined || packet.flush.isDefined || packet.ioResponse.isDefined || packet.sramWrite.isDefined
            ) {
              println(s"[Fuzzy] Enqueueing packet: $packet")
              cyclePacketQueue.enqueue(packet)
            }
          }
        }

        // 3. ================== MODEL PROCESSOR ==================
        // This thread's logic remains simple: dequeue and process.
        // val modelProcessor = fork {
        //   while (true) {
        //     if (cyclePacketQueue.nonEmpty) {
        //       helper.processPendingEvents(cyclePacketQueue, goldModel)
        //     } else {
        //       cd.waitSampling()
        //     }
        //   }
        // }

        // 4. ================== DRIVER ==================
        // The commit acker now needs to be separate again.
        val commitAcker = fork {
          while (true) {
            dut.io.robCommitAck #= false
            cd.waitSamplingWhere(dut.io.committedUop.valid.toBoolean)
            dut.io.robCommitAck #= true
            cd.waitSampling() // Acknowledge for one cycle
          }
        }

        // The main test loop (Driver)
        val totalCycles = 10000
        val maxRobPtrValue = BigInt(1) << dut.pCfg.robPtrWidth.value

        println(s"--- Starting Decoupled Fuzzy Test for $totalCycles cycles ---")

        for (cycle <- 1 to totalCycles) {
          print(
            f"\r[Cycle ${cycle}/${totalCycles}] GM size: ${goldModel.size}%d, Events in Q: ${cyclePacketQueue.size}   "
          )

          helper.weightedRandom(
            rand,
            70 -> (() => {
              if (dut.io.canRobAllocate.toBoolean) {
                val themes = Seq("OVERLAP_RANDOM", "MERGE_RANDOM", "MMIO_HAZARD_RANDOM")
                val theme = themes(rand.nextInt(themes.length))

                println(s"\n[Fuzzy-Forwarding] Executing DYNAMIC combo test with theme: $theme")

                // Generate a base address for the interaction
                val baseAddr = BigInt(addrPool(rand.nextInt(addrPool.size))) & ~BigInt(7)

                theme match {
                  case "OVERLAP_RANDOM" =>
                    // GOAL: Create two overlapping stores, where the younger one may
                    // partially or fully override the older one.
                    val numStores = 2 + rand.nextInt(3) // Issue 2 to 4 stores
                    var lastRobPtr = BigInt(0)

                    for (i <- 0 until numStores) {
                      val size = helper.getRandomSize(rand)
                      val offset = rand.nextInt(5) // 0 to 4, allows for interesting overlaps
                      var addr = baseAddr + offset
                      size match {
                        case MemAccessSize.W => addr = addr & ~BigInt(3) // Align to 4 bytes
                        case MemAccessSize.H => addr = addr & ~BigInt(1) // Align to 2 bytes
                        case MemAccessSize.B => // No alignment needed
                      }
                      val data = helper.getRandomData(rand, size)
                      lastRobPtr = helper.cpuIssueCacheableStore(addr, data, size)
                      if (rand.nextBoolean()) cd.waitSampling(rand.nextInt(3)) // Random delay
                    }

                    // Now, issue a random load within the affected address range
                    val loadSize = helper.getRandomSize(rand)
                    val loadOffset = rand.nextInt(8)
                    val loadAddr = baseAddr + loadOffset
                    val loadRobPtr = (lastRobPtr + 1 + rand.nextInt(5)) % maxRobPtrValue

                    helper.verifyForwarding(
                      loadRobPtr,
                      loadAddr,
                      loadSize,
                      true,
                      goldModel,
                      cyclePacketQueue,
                      "OVERLAP_RANDOM"
                    )

                  case "MERGE_RANDOM" =>
                    // GOAL: Create multiple non-overlapping or partially overlapping stores
                    // that a single larger load will need to merge.
                    var occupiedBytes = Set[Int]()
                    var lastRobPtr = BigInt(0)

                    for (i <- 0 until 2 + rand.nextInt(2)) { // 2 or 3 small stores
                      val size = if (rand.nextBoolean()) MemAccessSize.B else MemAccessSize.H
                      var offset: Int = 0
                      // Find an unoccupied spot
                      do {
                        offset = rand.nextInt(7)
                      } while ((offset until offset + (if (size == MemAccessSize.B) 1 else 2))
                        .exists(occupiedBytes.contains))

                      var addr = baseAddr + offset
                      size match {
                        case MemAccessSize.W => addr = addr & ~BigInt(3) // Align to 4 bytes
                        case MemAccessSize.H => addr = addr & ~BigInt(1) // Align to 2 bytes
                        case MemAccessSize.B => // No alignment needed
                      }
                      val data = helper.getRandomData(rand, size)
                      lastRobPtr = helper.cpuIssueCacheableStore(addr, data, size)

                      // Mark bytes as occupied
                      for (b <- 0 until (if (size == MemAccessSize.B) 1 else 2)) { occupiedBytes += (offset + b) }
                    }

                    // Now, issue a WORD load that covers the area
                    val loadRobPtr = (lastRobPtr + 1 + rand.nextInt(5)) % maxRobPtrValue
                    helper.verifyForwarding(
                      loadRobPtr,
                      baseAddr,
                      MemAccessSize.W,
                      true,
                      goldModel,
                      cyclePacketQueue,
                      "MERGE_RANDOM"
                    )

                  case "MMIO_HAZARD_RANDOM" =>
                    // GOAL: An older MMIO store should interact with a younger load.
                    // The load can be coherent or not, overlapping or not.

                    // Issue an MMIO store
                    val mmioSize = helper.getRandomSize(rand)
                    val mmioOffset = rand.nextInt(8)
                    var mmioAddr = baseAddr + mmioOffset
                    mmioSize match {
                      case MemAccessSize.W => mmioAddr = mmioAddr & ~BigInt(3)
                      case MemAccessSize.H => mmioAddr = mmioAddr & ~BigInt(1)
                      case MemAccessSize.B => // No alignment needed
                    }
                    val mmioData = helper.getRandomData(rand, mmioSize)
                    val mmioRobPtr = helper.cpuIssueMmioStore(mmioAddr, mmioData, mmioSize)

                    if (rand.nextBoolean()) cd.waitSampling(rand.nextInt(3))

                    // Issue a subsequent load
                    val loadIsCoherent = rand.nextBoolean()
                    val loadSize = helper.getRandomSize(rand)
                    // Make it likely to overlap
                    val loadOffset = mmioOffset + rand.nextInt(5) - 2
                    val loadAddr = baseAddr + loadOffset
                    val loadRobPtr = (mmioRobPtr + 1 + rand.nextInt(5)) % maxRobPtrValue

                    helper.verifyForwarding(
                      loadRobPtr,
                      loadAddr,
                      loadSize,
                      loadIsCoherent,
                      goldModel,
                      cyclePacketQueue,
                      "MMIO_HAZARD_RANDOM"
                    )
                }
              }
            }),
            // PUSH a new store
            40 -> (() => {
              if (dut.io.canRobAllocate.toBoolean) {
                val size = accessSizes(rand.nextInt(accessSizes.length))
                val randomBaseAddr = BigInt(addrPool(rand.nextInt(addrPool.size)))
                val addr = size match {
                  case MemAccessSize.B => randomBaseAddr
                  case MemAccessSize.H => randomBaseAddr & ~BigInt(1)
                  case MemAccessSize.W => randomBaseAddr & ~BigInt(3)
                }
                val dataBitWidth = MemAccessSize.sizeInBits(size)
                val randomLong = rand.nextLong()
                val mask = (BigInt(1) << dataBitWidth) - 1
                val data = BigInt(randomLong) & mask

                // Randomly decide if it's MMIO or Cacheable/RAM_IO
                val isIO = rand.nextBoolean()
                val isCoherent = if (isIO) rand.nextBoolean() else true // If not IO, it's always coherent (cacheable)

                if (isIO && !isCoherent) { // MMIO
                  helper.cpuIssueMmioStore(addr, data, size)
                } else if (isIO && isCoherent) { // RAM_IO
                  helper.cpuIssueRamIoStore(addr, data, size)
                } else { // Cacheable
                  helper.cpuIssueCacheableStore(addr, data, size)
                }
              }
            }),

            // FLUSH the pipeline
            5 -> (() => {
              println("\n[Fuzzy] Issuing a FULL FLUSH.")
              helper.issueRobFullFlush()
            }),

            // ISSUE a Load and perform verification (Checker Logic)
            55 -> (() => {
              // Only issue a load if the ROB can allocate and there are no pending stores that haven't been processed by the model
              if (dut.io.canRobAllocate.toBoolean && goldModel.isPendingStoreEmpty) {
                val loadRobPtr = (helper.allocateRobPtrForLoad()) % maxRobPtrValue
                cd.waitSampling()

                val loadSize = accessSizes(rand.nextInt(accessSizes.length))
                val loadAddr = BigInt(addrPool(rand.nextInt(addrPool.size)))
                val isCoherentLoad = true // For now, only test coherent loads

                // Query both DUT and Model
                val (goldHit, goldData, goldStall) = goldModel.query(loadRobPtr, loadAddr, loadSize, isCoherentLoad)

                val querySuccess = helper.queryAndCheck(
                  loadRobPtr,
                  loadAddr,
                  loadSize,
                  isCoherentLoad,
                  goldHit,
                  goldData,
                  goldStall,
                  s"Fuzzy Cycle $cycle",
                  Some(goldModel)
                )
                if (!querySuccess) {
                  goldModel.diagnoseQuery(loadRobPtr, loadAddr, loadSize, isCoherentLoad)
                  simFailure("Fuzzy test [Forwarding] mismatch!")
                }

                // End-to-end memory verification if no forwarding hit and no stall
                if (!goldHit && !goldStall) {
                  // println( // Uncomment for detailed debug
                  //   s"\n[Fuzzy-MemVerify] Load (robPtr=$loadRobPtr) missed forwarding. Verifying memory at addr 0x${loadAddr
                  //       .toString(16)}..."
                  // )
                  val loadWordAddr = loadAddr - (loadAddr % dataWidthBytes)
                  val dutMemData = helper.sramRead(loadWordAddr)
                  val goldMemData = goldModel.readWordFromMemory(loadWordAddr)

                  assert(
                    dutMemData == goldMemData,
                    f"Fuzzy test [Memory] mismatch at addr 0x${loadWordAddr.toString(16)}! DUT=0x${dutMemData
                        .toString(16)}, Gold=0x${goldMemData.toString(16)}"
                  )
                  // println(f"[Fuzzy-MemVerify] PASSED for addr 0x${loadWordAddr.toString(16)}") // Uncomment for detailed debug
                }
              }
            })
          )
          cd.waitSampling()
        }

        // 5. ================== CLEANUP & FINAL CHECKS ==================
        println("\n[Fuzzy] Main loop finished. Waiting for event queue to drain...")
        // Wait until the model has processed all events
        while (cyclePacketQueue.nonEmpty) {
          cd.waitSampling()
        }
        cd.waitSampling(100) // Extra wait for safety

        // Terminate monitor and processor threads
        commitAcker.terminate()
        // modelProcessor.terminate()
        // The global onSamplings monitor will terminate when the main sim thread ends.

        assert(
          cyclePacketQueue.isEmpty,
          s"Test End Error: Cycle packet queue is not empty! Remaining events: ${cyclePacketQueue.size}"
        )
        assert(goldModel.isPendingStoreEmpty, "Test End Error: PendingStore is not empty, some stores were not popped.")

        println("\n--- Final Cycle Packet Fuzzy Test PASSED ---")
        simSuccess()
      }
  }

  thatsAll()
}

class StoreRingBufferTestHelper(dut: StoreRingBufferFullIntegrationTestBench)(implicit cd: ClockDomain) {

  val sram = dut.getSramHandle()
  val dataWidthBytes = dut.pCfg.dataWidth.value / 8

  def sramFillWithPattern(pattern: BigInt, words: Int, startAddr: BigInt = 0): Unit = {
    println(f"[Helper] Filling SRAM with pattern 0x${pattern.toString(16)} for $words words...")
    sram.io.tb_writeEnable #= true
    for (i <- 0 until words) {
      sram.io.tb_writeAddress #= startAddr + i * dataWidthBytes
      sram.io.tb_writeData #= pattern
      cd.waitSampling()
    }
    sram.io.tb_writeEnable #= false
    cd.waitSampling() // Ensure the last write completes
  }

  // def syncModel(cyclePacketQueue: mutable.Queue[CycleEventPacket], timeout: Int = 100): Unit = {
  //   var waitCycles = 1
  //   println("[Helper-Sync] Waiting for model processor to catch up...")
  //   while (cyclePacketQueue.nonEmpty) {
  //     cd.waitSampling()
  //     waitCycles += 1
  //     if (waitCycles > timeout) {
  //       simFailure("Model synchronization timed out!")
  //     }
  //   }
  //   cd.waitSampling(1)
  //   println(s"[Helper-Sync] Model is synchronized after $waitCycles cycles.")
  // }

  /** Processes all events currently in the queue.
    * This function is called synchronously from the driver thread,
    * ensuring the model is up-to-date before any verification.
    */
  def processPendingEvents(
      cyclePacketQueue: mutable.Queue[CycleEventPacket],
      goldModel: StoreBufferGoldModel
  ): Unit = {
    // println(s"[Sync] Processing ${cyclePacketQueue.size} pending events...") // Optional logging
    while (cyclePacketQueue.nonEmpty) {
      val packet = cyclePacketQueue.dequeue()
      goldModel.processCycle(packet)
    }
    // println("[Sync] All pending events processed.") // Optional logging
  }

  def isNewerOrSame(robPtrA: BigInt, robPtrB: BigInt): Boolean = {
    val width = dut.pCfg.robPtrWidth.value
    if (width <= 1) return robPtrA >= robPtrB
    val genA = (robPtrA >> (width - 1)) & 1
    val idxA = robPtrA & ((BigInt(1) << (width - 1)) - 1)
    val genB = (robPtrB >> (width - 1)) & 1
    val idxB = robPtrB & ((BigInt(1) << (width - 1)) - 1)
    (genA == genB && idxA >= idxB) || (genA != genB && idxA < idxB)
  }

  def weightedRandom(rand: Random, weights: (Int, () => Unit)*): Unit = {
    val total = weights.map(_._1).sum
    if (total == 0) return
    var r = rand.nextInt(total)
    for ((weight, task) <- weights) {
      if (r < weight) {
        task()
        return
      }
      r -= weight
    }
  }

  def init(): Unit = {
    dut.io.lsuPushIn.valid #= false
    dut.io.robAllocateIn.valid #= false
    dut.io.robCommitAck #= false
    dut.io.bypassQueryAddr #= 0x0L
    dut.io.bypassQuerySize #= MemAccessSize.W
    dut.io.robFlushIn.valid #= false
    dut.io.sqQueryPort.cmd.valid #= false
    cd.waitSampling()
  }

  def allocateRobPtrForOp(uopCode: BaseUopCode.E, pc: Long = 0x8000): BigInt = {
    dut.io.robAllocateIn.valid #= true
    dut.io.robAllocateIn.pcIn #= pc
    dut.io.robAllocateIn.uopIn.decoded.uopCode #= uopCode
    cd.waitActiveEdge()
    val robPtr = dut.io.allocatedRobPtr.toBigInt
    dut.io.robAllocateIn.valid #= false
    cd.waitSampling()
    robPtr
  }

  def allocateRobPtrForLoad(): BigInt = {
    allocateRobPtrForOp(BaseUopCode.LOAD)
  }

  /** 增强版 cpuIssueStore:
    * 1. 自动处理任意大小和对齐方式。
    * 2. 正确计算 byte enable (be) 和移位后的数据 (alignedData)。
    * 3. `data` 参数现在代表的是在指定地址`addr`处、符合`size`大小的原始数据。
    */
  def cpuIssueStore(
      addr: BigInt,
      data: BigInt,
      size: MemAccessSize.E,
      pc: Long = 0x8000,
      isIO: Boolean,
      isCoherent: Boolean
  ): BigInt = {
    val robPtr = allocateRobPtrForOp(BaseUopCode.STORE, pc)

    val byteOffset = addr.toInt & (dataWidthBytes - 1)
    val be = MemAccessSize.toByteEnable_sw(size, byteOffset, dataWidthBytes)

    dut.io.lsuPushIn.valid #= true
    val p = dut.io.lsuPushIn.payload
    p.addr #= addr
    p.data #= data
    p.be #= be
    p.robPtr #= robPtr
    p.accessSize #= size
    p.isIO #= isIO
    p.isCoherent #= isCoherent
    p.hasEarlyException #= false
    p.isFlush #= false
    cd.waitSamplingWhere(dut.io.lsuPushIn.ready.toBoolean)
    dut.io.lsuPushIn.valid #= false
    robPtr
  }

  // 创建新的、更清晰的辅助函数来代表不同类型的Store

  // Cacheable Store (最常见的)
  def cpuIssueCacheableStore(
      addr: BigInt,
      data: BigInt,
      size: MemAccessSize.E = MemAccessSize.W,
      pc: Long = 0x8000
  ): BigInt = {
    cpuIssueStore(addr, data, size, pc, isIO = false, isCoherent = true)
  }

  // RAM I/O Store (可转发，走总线)
  def cpuIssueRamIoStore(
      addr: BigInt,
      data: BigInt,
      size: MemAccessSize.E = MemAccessSize.W,
      pc: Long = 0x8000
  ): BigInt = {
    cpuIssueStore(addr, data, size, pc, isIO = true, isCoherent = true)
  }

  // MMIO Store (不可转发，走总线)
  def cpuIssueMmioStore(
      addr: BigInt,
      data: BigInt,
      size: MemAccessSize.E = MemAccessSize.W,
      pc: Long = 0x8000
  ): BigInt = {
    cpuIssueStore(addr, data, size, pc, isIO = true, isCoherent = false)
  }

  // 修改 queryAndCheck 函数，增加 isCoherent 参数
  def queryAndCheck(
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      isCoherentLoad: Boolean, // << 新增参数，Load也需要知道自己的类型
      shouldHit: Boolean,
      expectedData: BigInt = 0,
      shouldStall: Boolean = false, // 重命名 shouldHaveDep 为 shouldStall 更清晰
      comment: String = "",
      goldModelForDebug: Option[StoreBufferGoldModel] = None
  ): Boolean = {
    val sq_cmd = dut.io.sqQueryPort.cmd
    sq_cmd.valid #= true
    sq_cmd.payload.robPtr #= loadRobPtr
    sq_cmd.payload.address #= loadAddr
    sq_cmd.payload.size #= loadSize
    sq_cmd.payload.isCoherent #= isCoherentLoad // << 设置Load的isCoherent属性
    // isIO for load is implicitly handled by isCoherentLoad
    sq_cmd.payload.isIO #= !isCoherentLoad

    cd.waitSampling()

    val rsp = dut.io.sqQueryPort.rsp
    val dutHit = rsp.hit.toBoolean
    val dutData = rsp.data.toBigInt
    val dutStall = rsp.olderStoreDataNotReady.toBoolean

    sq_cmd.valid #= false

    val hitMatch = dutHit == shouldHit
    val dataMatch = !shouldHit || (dutData == expectedData)
    val stallMatch = dutStall == shouldStall

    if (!hitMatch || !dataMatch || !stallMatch) {

      println("\n" + ("#" * 25) + " QUERY MISMATCH DETECTED " + ("#" * 25))
      val checkComment = if (comment.nonEmpty) s" ($comment)" else ""
      println(s"Mismatch details$checkComment:")
      if (!hitMatch) println(s"  - RSP.hit:   Got ${dutHit}, Expected ${shouldHit}")
      if (!dataMatch)
        println(s"  - RSP.data:  Got 0x${dutData.toString(16)}, Expected 0x${expectedData.toString(16)}")
      if (!stallMatch) println(s"  - RSP.stall: Got ${dutStall}, Expected ${shouldStall}")

      // Call the powerful debugger on the Gold Model
      goldModelForDebug.foreach(_.diagnoseQuery(loadRobPtr, loadAddr, loadSize, isCoherentLoad))

      return false // Indicate failure
    }

    return true // Indicate success
  }

  // In class StoreRingBufferTestHelper

  /** STEP 1: Queries the DUT and returns its response.
    * This function drives the DUT for one cycle and advances simulation time.
    * @return A tuple containing the DUT's (hit, data, stall) response.
    */
  def queryDutForwarding(
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      isCoherentLoad: Boolean
  ): (Boolean, BigInt, Boolean) = {
    val sq_cmd = dut.io.sqQueryPort.cmd
    sq_cmd.valid #= true
    sq_cmd.payload.robPtr #= loadRobPtr
    sq_cmd.payload.address #= loadAddr
    sq_cmd.payload.size #= loadSize
    sq_cmd.payload.isCoherent #= isCoherentLoad
    sq_cmd.payload.isIO #= !isCoherentLoad

    cd.waitSampling() // Advances time from T to T+1

    val rsp = dut.io.sqQueryPort.rsp
    val dutHit = rsp.hit.toBoolean
    val dutData = rsp.data.toBigInt
    val dutStall = rsp.olderStoreDataNotReady.toBoolean

    sq_cmd.valid #= false

    (dutHit, dutData, dutStall)
  }

  def verifyForwarding(
      loadRobPtr: BigInt,
      loadAddr: BigInt,
      loadSize: MemAccessSize.E,
      isCoherentLoad: Boolean,
      goldModel: StoreBufferGoldModel,
      cyclePacketQueue: mutable.Queue[CycleEventPacket],
      comment: String = ""
  ): Unit = {

    val dutResponse = queryDutForwarding(loadRobPtr, loadAddr, loadSize, isCoherentLoad)
    processPendingEvents(cyclePacketQueue, goldModel)

    val checkSuccess = goldModel.checkForwarding(
      dutResponse,
      loadRobPtr,
      loadAddr,
      loadSize,
      isCoherentLoad,
      comment
    )

    if (!checkSuccess) {
      simFailure(s"Forwarding verification FAILED for '$comment'. DUT behavior did not match Gold Model.")
    } else {
      println(s"[VerifyFwd] PASSED for '$comment'.")
    }
  }

  def getRandomSize(rand: Random): MemAccessSize.E = {
    val sizes = List(MemAccessSize.B, MemAccessSize.H, MemAccessSize.W)
    sizes(rand.nextInt(sizes.length))
  }

  def getRandomData(rand: Random, size: MemAccessSize.E): BigInt = {
    val dataBitWidth = MemAccessSize.sizeInBits(size)
    val mask = if (dataBitWidth >= 64) BigInt(-1) else (BigInt(1) << dataBitWidth) - 1
    BigInt(rand.nextLong()) & mask
  }
  def issueRobFullFlush(): Unit = {
    dut.io.robFlushIn.valid #= true
    dut.io.robFlushIn.payload.reason #= FlushReason.FULL_FLUSH
    cd.waitSampling()
    dut.io.robFlushIn.valid #= false
  }

  def forceFlushAndWait(address: BigInt): Unit = {
    cd.waitSampling(50)
  }

  def sramWrite(addr: BigInt, data: BigInt): Unit = {
    sram.io.tb_writeEnable #= true
    sram.io.tb_writeAddress #= addr
    sram.io.tb_writeData #= data
    cd.waitSampling()
    sram.io.tb_writeEnable #= false
  }

  def sramRead(addr: BigInt): BigInt = {
    sram.io.tb_readEnable #= true
    sram.io.tb_readAddress #= addr
    // 等待足够的时间让SRAM的读操作完成
    cd.waitSampling(sram.config.readWaitCycles + 2)
    val readData = sram.io.tb_readData.toBigInt
    sram.io.tb_readEnable #= false
    readData
  }

  def sramClear(startAddr: BigInt, words: Int): Unit = {
    println(f"[Helper] Clearing SRAM from 0x$startAddr%x for $words words...")
    for (i <- 0 until words) {
      sramWrite(startAddr + i * dataWidthBytes, 0)
    }
    cd.waitSampling() // Ensure writes complete
  }

  def sramVerify(addr: BigInt, expectedData: BigInt): Unit = {
    sram.io.tb_readEnable #= true
    sram.io.tb_readAddress #= addr
    cd.waitSampling(sram.config.readWaitCycles + 2)
    val readData = sram.io.tb_readData.toBigInt
    assert(
      readData == expectedData,
      s"Memory verification failed at 0x${addr.toString(16)}. Got 0x${readData.toString(16)}, Expected 0x${expectedData.toString(16)}"
    )
    sram.io.tb_readEnable #= false
  }
}

/** This plugin connects the abstract services required by the StoreBuffer DUT
  * to the concrete IO of the testbench. It acts as a bridge.
  */
class StoreRingBufferTestConnectionPlugin(
    tbIo: StoreRingBufferTestBenchIo,
    pCfg: PipelineConfig,
    dCacheCfg: DataCachePluginConfig
) extends Plugin {
  val setup = create early new Area {
    val sbPlugin = getService[StoreBufferService]
    val robService = getService[ROBService[RenamedUop]]

    // Connect Testbench IO to DUT (StoreBufferService)
    sbPlugin.getPushPort() <> tbIo.lsuPushIn
    sbPlugin.getBypassQueryAddressInput() := tbIo.bypassQueryAddr
    sbPlugin.getBypassQuerySizeInput() := tbIo.bypassQuerySize
    tbIo.bypassDataOut <> sbPlugin.getBypassDataOutput()
    sbPlugin.getStoreQueueQueryPort() <> tbIo.sqQueryPort

    // Drive ROB allocation from Testbench IO
    val robAllocPorts = robService.getAllocatePorts(pCfg.renameWidth)
    robAllocPorts(0).valid := tbIo.robAllocateIn.valid
    robAllocPorts(0).pcIn := tbIo.robAllocateIn.pcIn
    robAllocPorts(0).uopIn := tbIo.robAllocateIn.uopIn

    tbIo.canRobAllocate := robService.getCanAllocateVec(pCfg.renameWidth)(0)
    tbIo.allocatedRobPtr := robAllocPorts(0).robPtr

    tbIo.robFlushIn <> robService.newRobFlushPort()
    tbIo.robFlushOut <> robService.doRobFlush() // Connect robFlushOut for monitoring

    // MODIFIED: Correctly connect the commit monitoring and acknowledgement signals
    val commitSlots = robService.getCommitSlots(pCfg.commitWidth)
    val commitAcks = robService.getCommitAcks(pCfg.commitWidth)

    // Testbench listens to the ROB's commit signal
    tbIo.committedUop.valid := commitSlots(0).canCommit
    tbIo.committedUop.payload := commitSlots(0).entry

    // ROB listens to the testbench's acknowledgement
    commitAcks(0) := tbIo.robCommitAck
  }
}

/** 这个插件专门用于StoreBuffer的独立测试环境。
  * 它的唯一作用是模拟LSU的行为：当一个Store指令成功推入StoreBuffer后，
  * 立即向ROB报告该指令的执行阶段已完成 (writeback)。
  * 这解决了ROB因为等待一个不存在的执行单元的完成信号而卡住的问题。
  */
class TestLsuWritebackPlugin(pCfg: PipelineConfig) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val sbService = getService[StoreBufferService]
    val robService = getService[ROBService[RenamedUop]]
    sbService.retain()
    robService.retain()

    val robStoreWritebackPort = robService.newWritebackPort("TestLSU_Store")
  }

  val logic = create late new Area {
    lock.await() // Ensure services are locked before use
    val pushPort = setup.sbService.getPushPort()
    val wbPort = setup.robStoreWritebackPort

    wbPort.setDefault()

    when(pushPort.fire) {
      wbPort.fire := True
      wbPort.robPtr := pushPort.payload.robPtr
      wbPort.exceptionOccurred := pushPort.payload.hasEarlyException
      wbPort.exceptionCodeIn := pushPort.payload.earlyExceptionCode

      ParallaxLogger.debug(s"[TestLsuWritebackPlugin] Firing writeback for robPtr=${pushPort.payload.robPtr}")
    }

    setup.sbService.release()
    setup.robService.release()
  }
}

/** 这个插件模拟一个通用的执行单元（如ALU）。
  * 它会为所有非STORE类型的指令自动发送一个“完成”信号给ROB。
  * 这对于确保ROB能正确提交非LSU指令至关重要，特别是在Fuzzy测试中。
  */
class TestGenericEuWritebackPlugin(pCfg: PipelineConfig) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val robService = getService[ROBService[RenamedUop]]
    robService.retain()
    // 为这个模拟的EU申请一个写回端口
    val robWritebackPort = robService.newWritebackPort("TestGenericEU")
  }

  val logic = create late new Area {
    lock.await()
    val robAllocPorts = setup.robService.getAllocatePorts(pCfg.renameWidth)
    val wbPort = setup.robWritebackPort

    // 监听ROB的分配事件
    val allocFire = robAllocPorts(0).valid && robAllocPorts(0).ready
    val allocatedUop = robAllocPorts(0).uopIn
    val allocatedRobPtr = robAllocPorts(0).robPtr

    // 如果分配的指令不是STORE，就立即报告完成
    // 使用RegNext来避免组合逻辑环路，模拟一个周期的执行延迟
    wbPort.setDefault().allowOverride
    wbPort.fire := RegNext(allocFire && (allocatedUop.decoded.uopCode =/= BaseUopCode.STORE)) init (False)
    wbPort.robPtr := RegNext(allocatedRobPtr)

    when(wbPort.fire) {
      ParallaxLogger.debug(s"[TestGenericEuWritebackPlugin] Firing writeback for robPtr=${wbPort.robPtr}")
    }

    setup.robService.release()
  }
}
