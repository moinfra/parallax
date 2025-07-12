package parallax.components.memory

import parallax.utilities.Plugin
import parallax.utilities.Service
import spinal.lib._
import spinal.core._
import spinal.core.Area


trait SimpleMemoryService extends Service {
  def memBus: SimpleMemoryBus
}

class SimulatedSimpleMemoryPlugin(
    val memBusConfig: GenericMemoryBusConfig =
      GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = 32 bits, useId = false),
    val simMemConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(
      internalDataWidth = 16 bits,
      memSize = 8 KiB,
      initialLatency = 2
    )
) extends Plugin
    with SimpleMemoryService {

  val setup = create early new Area {

    val memory = new SimulatedMemory(
      simMemConfig,
      memBusConfig
    )
  }

  def memBus = setup.memory.io.bus
}
