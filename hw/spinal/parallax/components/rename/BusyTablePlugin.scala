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
  def getBusyBits(): Bits
}

// --- Plugin Implementation ---
class BusyTablePlugin(pCfg: PipelineConfig) extends Plugin with BusyTableService {

  private val setPorts = ArrayBuffer[Flow[UInt]]()
  private val clearPortsBuffer = ArrayBuffer[Flow[UInt]]() // 使用ArrayBuffer存储所有清除端口

  // All hardware resources must be declared in early or late Area
  val early_setup = create early new Area {
    val busyTableReg = Reg(Bits(pCfg.physGprCount bits)) init (0)
  }

  val logic = create late new Area {
    lock.await()
    val busyTableReg = early_setup.busyTableReg

    // Handle clears first (higher priority)
    val clearMask = Bits(pCfg.physGprCount bits)
    clearMask.clearAll()
    // 现在迭代的是 ArrayBuffer 中的端口
    for (port <- clearPortsBuffer) {
      when(port.valid) {
        clearMask(port.payload) := True
        report(L"[BusyTable] Clear port valid: physReg=${port.payload}")
      }
    }

    // Handle sets
    val setMask = Bits(pCfg.physGprCount bits)
    setMask.clearAll()
    for (port <- setPorts; if port != null) {
      when(port.valid) {
        setMask(port.payload) := True
        report(L"[BusyTable] Set port valid: physReg=${port.payload}")
      }
    }

    // Combine clear and set operations in one statement: clear has higher priority
    val busyTableNext = (busyTableReg & ~clearMask) | setMask

    report(L"[BusyTable] Current: busyTableReg=${busyTableReg}, clearMask=${clearMask}, setMask=${setMask}, next=${busyTableNext}")

    busyTableReg := busyTableNext
    
    // Connect the combinational output
    combinationalBusyBits := busyTableNext
  }

  // Public signal for combinational queries that considers current cycle updates
  val combinationalBusyBits = Bits(pCfg.physGprCount bits)

  override def newSetPort(): Vec[Flow[UInt]] = {
    val ports = Vec.fill(pCfg.renameWidth)(Flow(UInt(pCfg.physGprIdxWidth)))
    setPorts ++= ports
    ports
  }
  
  // 【修正】: 每次调用都创建一个新的、唯一的端口
  override def newClearPort(): Flow[UInt] = {
    val port = Flow(UInt(pCfg.physGprIdxWidth))
    clearPortsBuffer += port // 将新端口加入Buffer
    port
  }
  
  override def getBusyBits(): Bits = {
    // Return the combinational result that considers current cycle clears
    // This prevents read-after-write hazards
    combinationalBusyBits
  }
}
