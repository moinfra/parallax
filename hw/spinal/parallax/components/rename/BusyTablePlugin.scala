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

  /** Called by Bypass/Writeback to mark a physical register as ready */
  def newClearPort(): Vec[Flow[UInt]]

  /** Combinational query port for IQs or other units */
  def getBusyBits(): Bits
}

// --- Plugin Implementation ---
class BusyTablePlugin(pCfg: PipelineConfig) extends Plugin with BusyTableService {

  private val setPorts = ArrayBuffer[Flow[UInt]]()
  private val clearPorts = ArrayBuffer[Flow[UInt]]()

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
    for (port <- clearPorts; if port != null) {
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
  }

  override def newSetPort(): Vec[Flow[UInt]] = {
    val ports = Vec.fill(pCfg.renameWidth)(Flow(UInt(pCfg.physGprIdxWidth)))
    setPorts ++= ports
    ports
  }
  override def newClearPort(): Vec[Flow[UInt]] = {
    // Allow multiple EUs to clear bits
    val port = Flow(UInt(pCfg.physGprIdxWidth))
    clearPorts += port
    Vec(port) // Return as a Vec for consistency, though it's a single element
  }
  override def getBusyBits(): Bits = early_setup.busyTableReg
}
