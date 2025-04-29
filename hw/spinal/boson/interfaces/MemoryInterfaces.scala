// src/main/scala/boson/interfaces/MemoryInterfaces.scala
package boson.interfaces

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._ // Import AXI
import boson.BosonConfig

// --- AXI Configuration ---
// Keep this consistent with BosonConfig
object AxiConfig {
  def getAxi4Config(config: BosonConfig): Axi4Config = Axi4Config(
    addressWidth = config.addrWidth,
    dataWidth = config.dataWidth,
    idWidth = 4,
    useId = true,
    useRegion = false, // Region signal not used
    useBurst = true, // Enable burst transactions
    useLock = false, // Lock signal not used
    useCache = false, // Cache signal not used
    useSize = true, // Enable size signal (specifies beat size)
    useQos = false, // QoS signal not used
    useLen = true, // Enable len signal (specifies burst length)
    useLast = true, // Enable last signal (indicates last beat in burst)
    useResp = true, // Enable resp signal (response status)
    useProt = false, // Protection signal not used
    useStrb = true // Enable strb signal (byte strobes for partial writes)
    // User signals are disabled by default in Axi4Config
  )
}

// --- Internal CPU -> Memory Bus Interfaces ---

// Instruction Fetch
case class InstrFetchCmd(config: BosonConfig) extends Bundle {
  val address = UInt(config.addrWidth bits)
}
case class InstrFetchRsp(config: BosonConfig) extends Bundle {
  val instruction = Bits(config.instructionWidth bits)
  val error = Bool() // Optional: Indicate fetch error (e.g., AXI SLVERR/DECERR)
}

object InstrFetchRsp {
  def zero(config: BosonConfig): InstrFetchRsp = {
    val z = InstrFetchRsp(config)
    z.instruction := 0 // SpinalHDL 通常能推断宽度，或者使用 B(0)
    z.error := False
    z
  }
}

// Data Memory Access
case class DataMemCmd(config: BosonConfig) extends Bundle {
  val address = UInt(config.addrWidth bits)
  val data = Bits(config.dataWidth bits)
  val write = Bool()
  // Use mask for byte enables (consistent with AXI strb)
  val mask = Bits(config.dataWidth / 8 bits)
  // Size encoding (log2 bytes: 0=byte, 1=half, 2=word) - matches AXI AxSIZE
  val size = UInt(2 bits)
}
case class DataMemRsp(config: BosonConfig) extends Bundle {
  val data = Bits(config.dataWidth bits)
  val error = Bool() // Optional: Indicate access error
}
