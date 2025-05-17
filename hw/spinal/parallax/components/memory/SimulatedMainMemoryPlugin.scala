// hw/spinal/parallax/components/memory/SimulatedMainMemoryPlugin.scala
package parallax.components.memory

import parallax.utilities._
import spinal.lib._
import spinal.core._

class SimulatedMainMemoryPlugin(
    val simMemConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(
      internalDataWidth = 32 bits, // Default internal width for easy use
      memSize = 8 KiB, // Default memory size
      initialLatency = 2 // Default latency
    ),
    // This busConfig defines what kind of bus this SimulatedGeneralMemory component ITSELF uses.
    // It must match the config of the bus it's connecting to (e.g., arbitratedMainBus).
    val busConfig: GenericMemoryBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = 32 bits)
) extends Plugin {

  // The Area where the actual memory component is instantiated.
  // 'create late' ensures this hardware is built after services are available.
  val memoryArea = create late new Area {
    val simMem = new SimulatedGeneralMemory(
      memConfig = simMemConfig,
      busConfig = busConfig, // The SimulatedGeneralMemory is configured with this bus spec.
      enableLog = true // Or make this configurable via plugin constructor
    )
  }

  // The Area where connections are made.
  // 'create late' is also appropriate here as it depends on services and other plugins' late phases.
  val connection = create late new Area {
    // Get the service that provides the arbitrated memory bus.
    val arbitratedBusProvider = getService[ArbitratedMemoryBusProvider]
    // Get the actual arbitrated bus (this is a MASTER bus from the arbiter's perspective).
    val arbitratedBus = arbitratedBusProvider.getArbitratedMemoryBus()

    // Sanity check: The bus provided by the arbiter should match what our memory component expects.
    // In a real system, if they don't match, a bus adapter/converter would be needed here.
    if (
      arbitratedBus.config.addressWidth != busConfig.addressWidth ||
      arbitratedBus.config.dataWidth != busConfig.dataWidth
    ) {
      SpinalError(
        s"SimulatedMainMemoryPlugin: Mismatch between arbitrated bus config (AddrW=${arbitratedBus.config.addressWidth}, DataW=${arbitratedBus.config.dataWidth}) " +
          s"and internal memory bus config (AddrW=${busConfig.addressWidth}, DataW=${busConfig.dataWidth}). " +
          "Configs must match or a bus converter is required."
      )
    }

    report(
      L"[SimulatedMainMemoryPlugin] Connecting its internal memory (slave bus) to the arbitrated memory bus (master bus)."
    )
    // Connect our simMem's SLAVE bus to the MASTER bus from the arbiter by connecting individual streams.
    // memoryArea.simMem.io.bus is a slave GenericMemoryBus. Its .cmd is a slave Stream.
    // arbitratedBus is a master GenericMemoryBus. Its .cmd is a master Stream.
    memoryArea.simMem.io.bus.cmd << arbitratedBus.cmd  // Master drives slave: arbitratedBus.cmd -> simMem.io.bus.cmd

    // memoryArea.simMem.io.bus.rsp is a master Stream.
    // arbitratedBus.rsp is a slave Stream.
    memoryArea.simMem.io.bus.rsp >> arbitratedBus.rsp  // Master drives slave: simMem.io.bus.rsp -> arbitratedBus.rsp
  }
}
