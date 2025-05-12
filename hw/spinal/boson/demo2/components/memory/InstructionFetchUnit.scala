package boson.demo2.components.memory

import spinal.core._
import spinal.lib._
import boson.demo2.common.Config // Assuming Config.XLEN exists
import boson.demo2.components.icache._
import boson.demo2.components.memory._ // For GenericMemoryBus and SimulatedMemory

case class InstructionFetchUnitConfig(
    val useICache: Boolean = true,
    val enableFlush: Boolean = false,
    val cpuAddressWidth: Int = Config.XLEN,
    val cpuDataWidth: Int = Config.XLEN, // Instruction width
    val memBusConfig: GenericMemoryBusConfig,
    var icacheConfig: SimpleICacheConfig = null
) {
  if (icacheConfig == null) {
    icacheConfig = SimpleICacheConfig(addressWidth = cpuAddressWidth, dataWidth = cpuDataWidth)
  }
  // General address width check
  require(memBusConfig.addressWidth >= cpuAddressWidth)

  if (useICache) {
    require(icacheConfig.addressWidth == cpuAddressWidth)
    require(icacheConfig.dataWidth == cpuDataWidth)
    // ICache's memory port (GenericMemoryBus) will use memBusConfig for its width.
    // And the ICache's output dataWidth (cpuDataWidth) must match the memory bus data width for this design.
    require(icacheConfig.dataWidth == memBusConfig.dataWidth)
    require(icacheConfig.addressWidth == memBusConfig.addressWidth)

  } else { // Direct memory access without ICache
    require(cpuDataWidth == memBusConfig.dataWidth)
    require(cpuAddressWidth == memBusConfig.addressWidth)
  }
}

class InstructionFetchUnit(val config: InstructionFetchUnitConfig) extends Component {
  // Implicitly use config.icacheConfig for SimpleICache constructor
  implicit val iCacheConfigForInst: SimpleICacheConfig = config.icacheConfig
  // Implicitly use config.memBusConfig for SimpleICache's memory port constructor
  implicit val genericMemBusConfigForICacheMemPort: GenericMemoryBusConfig =
    if (config.useICache) config.memBusConfig else null

  val io = new Bundle {
    // Input from PC generation (e.g., F0 stage output)
    val pcIn = slave Stream (UInt(config.cpuAddressWidth bits))

    // Output to next pipeline stage (e.g., F1 stage output)
    val dataOut = master Stream (new ICacheCpuRsp())

    // ICache Flush interface (only active if ICache is used)
    val flush = config.enableFlush generate slave(ICacheFlushBus())

    // External memory bus interface
    val memBus = master(SimpleMemoryBus(config.memBusConfig))
  }

  // --- Component Instantiation ---
  // ROM is no longer used directly.
  // SimMem is no longer instantiated here.

  val icache = if (config.useICache) {
    // SimpleICache takes SimpleICacheConfig and GenericMemoryBusConfig (for its mem port) implicitly
    new SimpleICache()
  } else null

  // --- Connections ---
  if (config.useICache) {
    // Connect ICache to CPU-like interface (pcIn, dataOut)
    icache.io.cpu.cmd.valid := io.pcIn.valid
    icache.io.cpu.cmd.payload.address := io.pcIn.payload
    io.pcIn.ready := icache.io.cpu.cmd.ready

    io.dataOut <> icache.io.cpu.rsp

    // Command Path: ICache.io.mem.cmd -> io.memBus.cmd
    io.memBus.cmd << icache.io.mem.cmd
    io.memBus.rsp >> icache.io.mem.rsp

    if (config.enableFlush) {
      icache.io.flush <> io.flush
    } else {
      if (icache.io.flush != null) {
        icache.io.flush.cmd.valid := False
        icache.io.flush.cmd.payload.assignDontCare()
      }
    }

  } else { // No ICache, direct access to external memory via SimpleMemoryBus
    val pcReg = Reg(UInt(config.cpuAddressWidth bits)) init (0)
    // pcRegValid indicates if pcReg holds a PC for an outstanding memory request
    val pcRegValid = Reg(Bool()) init (False)

    // Drive SimpleMemoryBus command
    io.memBus.cmd.valid := io.pcIn.valid && !pcRegValid // Only send new command if no request is pending
    io.memBus.cmd.payload.address := io.pcIn.payload
    io.memBus.cmd.payload.isWrite := False // Always reading for instruction fetch
    io.memBus.cmd.payload.writeData.assignDontCare() // Or B(0). Unused for read.
    io.pcIn.ready := io.memBus.cmd.ready && !pcRegValid

    when(io.memBus.cmd.fire) {
      pcReg := io.pcIn.payload
      pcRegValid := True
    }

    // Drive dataOut from SimpleMemoryBus response
    io.dataOut.valid := io.memBus.rsp.valid && pcRegValid // Response is valid and corresponds to our request
    io.dataOut.payload.pc := pcReg
    io.dataOut.payload.instruction := io.memBus.rsp.payload.readData
    io.dataOut.payload.fault := io.memBus.rsp.payload.error
    io.memBus.rsp.ready := io.dataOut.ready

    when(io.dataOut.fire) {
      pcRegValid := False // Our request has been served and consumed
    }

    if (config.enableFlush) {
      // Flush is a no-op if no ICache
      io.flush.rsp.done := io.flush.cmd.fire && io.flush.cmd.payload.start // Done immediately
    }
  }
}
