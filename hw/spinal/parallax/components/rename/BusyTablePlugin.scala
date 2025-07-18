// filename: hw/spinal/parallax/components/rename/BusyTablePlugin.scala
package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer

// --- Service Definition ---
trait BusyTableService extends Service with LockedImpl {

  /** Called by Rename to mark a new physical register as busy */
  def newSetPort(): Vec[Flow[UInt]]

  /** Called by EUs to claim an unused clear port */
  def newClearPort(): Flow[UInt]

  /** Combinational query port for IQs or other units */
  def getBusyBitsComb(): Bits // For s1 rename
  def getBusyBitsReg(): Bits // For dispatch
  def getClearBypass(): Bits // 新增：获取本周期的清零掩码

}

case class BusyTableCheckpoint(pCfg: PipelineConfig) extends Bundle {
  val busyBits = Bits(pCfg.physGprCount bits)
}

trait BusyTableCheckpointService extends Service with LockedImpl {
  def getBusyTableState(): BusyTableCheckpoint
  def newRestorePort(): Flow[BusyTableCheckpoint]
}

// --- Plugin Implementation ---
class BusyTablePlugin(pCfg: PipelineConfig)
    extends Plugin
    with BusyTableService
    with BusyTableCheckpointService {
  // HONEST ERROR REPORTING: Enable detailed logging for RAW hazard debugging
  val enableLog = false // FORCE enable logging for debugging
  println("[BusyTablePlugin] enableLog: " + enableLog)
  private val setPorts = ArrayBuffer[Flow[UInt]]()
  private val clearPortsBuffer = ArrayBuffer[Flow[UInt]]() // 使用ArrayBuffer存储所有清除端口
  private val restorePorts = ArrayBuffer[Flow[BusyTableCheckpoint]]()

  // All hardware resources must be declared in early or late Area
  val early_setup = create early new Area {
    val busyTableReg = Reg(Bits(pCfg.physGprCount bits)) init (0)
    val clearMask = Bits(pCfg.physGprCount bits)
    val setMask = Bits(pCfg.physGprCount bits)

  }

  val logic = create late new Area {
    lock.await()

    val clearMask = early_setup.clearMask
    val setMask = early_setup.setMask
    val busyTableReg = early_setup.busyTableReg
    

    // CRITICAL FIX: Connect to WakeupService for global wakeup coordination
    val wakeupService = getService[parallax.execute.WakeupService]
    val globalWakeupFlow = wakeupService.getWakeupFlow()

    // Handle clears first (higher priority)
    clearMask.clearAll()

    // CRITICAL FIX: Add global wakeup as a clear source
    when(globalWakeupFlow.valid) {
      clearMask(globalWakeupFlow.payload.physRegIdx) := True
      if(enableLog) report(L"[BusyTable] Global wakeup clear: physReg=${globalWakeupFlow.payload.physRegIdx}")
    }

    // Handle individual EU clear ports (still needed for direct clears)
    for (port <- clearPortsBuffer) {
      when(port.valid) {
        clearMask(port.payload) := True
        if(enableLog) report(L"[BusyTable] Clear port valid: physReg=${port.payload}")
      }
    }

    // Handle sets
    setMask.clearAll()
    for (port <- setPorts; if port != null) {
      when(port.valid) {
        setMask(port.payload) := True
        if(enableLog) report(L"[BusyTable] Set port valid: physReg=${port.payload}")
      }
    }

    // Combine clear and set operations in one statement: clear has higher priority
    val busyTableNext = (busyTableReg & ~clearMask) | setMask
    // val busyTableNext = (busyTableReg | setMask) & ~clearMask

    if(enableLog) report(L"[BusyTable] Current: busyTableReg=${busyTableReg}, clearMask=${clearMask}, setMask=${setMask}, next=${busyTableNext}")

    // Handle Restore
    val restorePort = restorePorts.head // Assuming single restore port for now
    when(restorePort.valid) {
      busyTableReg := restorePort.payload.busyBits
      if(enableLog) report(L"[BusyTable] Restored from checkpoint: busyBits=${restorePort.payload.busyBits}")
    } .otherwise {
      busyTableReg := busyTableNext
    }

    // Connect the combinational output
    combinationalBusyBits := busyTableNext
  }

  // Public signal for combinational queries that considers current cycle updates
  val combinationalBusyBits = Bits(pCfg.physGprCount bits)

  override def newSetPort(): Vec[Flow[UInt]] = {
    this.framework.requireEarly()
    val ports = Vec.fill(pCfg.renameWidth)(Flow(UInt(pCfg.physGprIdxWidth)))
    setPorts ++= ports
    ports
  }

  // 【修正】: 每次调用都创建一个新的、唯一的端口
  override def newClearPort(): Flow[UInt] = {
    this.framework.requireEarly()
    val port = Flow(UInt(pCfg.physGprIdxWidth))
    clearPortsBuffer += port // 将新端口加入Buffer
    port
  }

  override def getBusyBitsComb(): Bits = {
    // Return the combinational result that considers current cycle clears
    // This prevents read-after-write hazards
    combinationalBusyBits
  }

  // 用于决策
  override def getBusyBitsReg(): Bits = {
    early_setup.busyTableReg
  }

  // 用于备份检查点
  override def getBusyTableState(): BusyTableCheckpoint = {
    val state = BusyTableCheckpoint(pCfg)
    state.busyBits := logic.busyTableReg
    state
  }

  override def newRestorePort(): Flow[BusyTableCheckpoint] = {
    this.framework.requireEarly()
    val port = Flow(BusyTableCheckpoint(pCfg))
    restorePorts += port
    port
  }

  override def getClearBypass(): Bits = early_setup.clearMask
}

