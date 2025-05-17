// hw/spinal/parallax/components/memory/SimulatedMainMemoryPlugin.scala
package parallax.components.memory

import parallax.utilities.{Plugin, Service, Framework}
import spinal.lib._
import spinal.core._
import spinal.core.Area

// Service for MemoryArbiterPlugin to find and connect to this memory
trait ConnectableMemorySlave extends Service {
  def getMemorySlaveBus(): GenericMemoryBus // Returns the SLAVE bus of the memory
  def getMemoryConfig(): GenericMemoryBusConfig // Allows arbiter to check config
}

class SimulatedMainMemoryPlugin(
    val simMemConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(
      internalDataWidth = 32 bits,
      memSize = 8 KiB,
      initialLatency = 2
    ),
    val busConfig: GenericMemoryBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = 32 bits)
) extends Plugin
    with ConnectableMemorySlave {

  val memoryArea = create late new Area {
    val simMem = new SimulatedGeneralMemory(
      memConfig = simMemConfig,
      busConfig = busConfig,
      enableLog = true
    )
    simMem.setCompositeName(SimulatedMainMemoryPlugin.this, "simMem")
  }

  override def getMemorySlaveBus(): GenericMemoryBus = {
    memoryArea.simMem.io.bus
  }

  override def getMemoryConfig(): GenericMemoryBusConfig = {
    this.busConfig // The config this memory component is instantiated with
  }

}
