package boson.demo2.system

import boson.demo2.components.icache._
import boson.demo2.components.memory._

import spinal.core._
import spinal.lib._

case class MemorySubsystemConfig(
    useICache: Boolean = true,
    cpuAddressWidth: Int = 32,
    cpuDataWidth: Int = 32, // This would typically be instruction width for ICache
    icacheConfig: SimpleICacheConfig = SimpleICacheConfig(), // Default ICache config
    simMemConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(), // Default SimMem config
    // Generic bus config will be derived or explicitly set
    // It must match ICache's expectation if ICache is used
    // And SimMem's expectation
    genericMemBusDataWidth: Int = 32 // Should align with icacheConfig.dataWidth
) {
    // Ensure consistency if ICache is used
    if (useICache) {
        require(icacheConfig.addressWidth == cpuAddressWidth, "ICache address width mismatch with CPU.")
        require(icacheConfig.dataWidth == cpuDataWidth, "ICache data width mismatch with CPU data/instruction width.")
        require(icacheConfig.dataWidth == genericMemBusDataWidth, "ICache data width must match generic memory bus data width for fill operations.")
    } else {
        require(cpuDataWidth == genericMemBusDataWidth, "CPU data width must match generic memory bus data width for direct access.")
    }

    val genericMemBusAddrWidth = cpuAddressWidth // Memory bus uses full CPU address
}


class MemorySubsystem(val config: MemorySubsystemConfig) extends Component {
  val io = new Bundle {
    // This CPU interface needs to be flexible:
    // If ICache is used, it's ICacheCpuBus.
    // If ICache is bypassed, it needs to look like a direct memory access to GenericMemoryBus.
    // For simplicity, let's assume for now the "CPU" always talks ICacheCpuBus,
    // and we adapt if ICache is disabled.
    // A more advanced setup might have the CPU emit GenericMemoryBus commands directly.

    val cpu_cmd = slave Stream (ICacheCpuCmd()(config.icacheConfig)) // Using icacheConfig even if bypassed, for consistency of this example
    val cpu_rsp = master Stream (ICacheCpuRsp()(config.icacheConfig))

    // Flush interface, always present, might be no-op if no cache
    val flush_cmd = slave Flow (ICacheFlushCmd())
    val flush_rsp = out(ICacheFlushRsp())

    // Testbench port to SimulatedMemory
    val tb_mem_write_enable = in Bool () default (False)
    val tb_mem_write_address = in UInt (config.cpuAddressWidth bits) default (0)
    val tb_mem_write_data = in Bits (config.simMemConfig.internalDataWidth bits) default (0) // SimMem internal width
  }

  // Instantiate SimulatedMemory
  val genericMemBusActualConfig = GenericMemoryBusConfig(
      addressWidth = config.genericMemBusAddrWidth,
      dataWidth = config.genericMemBusDataWidth
  )
  val memory = new SimulatedMemory(
    memConfig = config.simMemConfig,
    busConfig = genericMemBusActualConfig
  )
  memory.io.writeEnable := io.tb_mem_write_enable
  memory.io.writeAddress := io.tb_mem_write_address
  memory.io.writeData := io.tb_mem_write_data


  if (config.useICache) {
    val icache = new SimpleICache()(config.icacheConfig, genericMemBusActualConfig)

    // Connect CPU to ICache
    icache.io.cpu.cmd << io.cpu_cmd
    icache.io.cpu.rsp >> io.cpu_rsp

    // Connect ICache to Memory
    icache.io.mem <> memory.io.bus

    // Connect Flush
    icache.io.flush.cmd << io.flush_cmd
    icache.io.flush.rsp <> io.flush_rsp

  } else { // ICache is bypassed, CPU (or an adapter) talks directly to memory
    // This part requires an adapter if io.cpu_cmd/rsp are to remain ICacheCpuBus format.
    // Adapter: ICacheCpuBus <-> GenericMemoryBus
    // For now, let's illustrate a conceptual direct connection,
    // assuming the CPU could emit GenericMemoryBus style requests.
    // Or, we make the bypass "fault" or return fixed data if accessed via ICacheCpuBus.

    // Simplest bypass: ICacheCpuBus signals a permanent fault or fixed data
    // as it's not really designed for direct memory access in its current form.
    // A true bypass would need more logic or a different CPU interface.

    // Let's make flush a no-op and CPU requests always fault if ICache is disabled
    // but the interface is still ICacheCpuBus.
    io.cpu_cmd.ready := True // Always ready to accept
    io.cpu_rsp.valid := io.cpu_cmd.fire // Respond immediately if command accepted
    io.cpu_rsp.payload.instruction := B(0) // Or some known "nop" or error instruction
    io.cpu_rsp.payload.fault := True // Indicate fault because ICache is "disabled"
    io.cpu_rsp.payload.pc := io.cpu_cmd.payload.address

    io.flush_rsp.done := True // Flush is instantaneously "done"

    // The memory.io.bus is unconnected from the CPU path in this simple bypass.
    // To make it useful, an adapter would be needed:
    // val directMemAdapter = new CpuToGenericMemAdapter(...)
    // directMemAdapter.io.cpu_cmd << io.cpu_cmd
    // directMemAdapter.io.cpu_rsp >> io.cpu_rsp
    // directMemAdapter.io.mem_bus <> memory.io.bus
    
    // For now, memory.io.bus is only used by testbench writes if ICache is disabled.
    // To prevent synthesis warnings about unconnected inputs on memory.io.bus:
    memory.io.bus.cmd.valid := False
    memory.io.bus.cmd.payload.address := 0
    memory.io.bus.cmd.payload.isWrite := False
    memory.io.bus.cmd.payload.writeData := B(0)
    memory.io.bus.rsp.ready := False
  }
}
