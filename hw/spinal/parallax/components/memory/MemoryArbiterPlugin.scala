// hw/spinal/parallax/components/memory/MemoryArbiterPlugin.scala
package parallax.components.memory

import spinal.core._
import spinal.lib._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer

// MemoryArbiterService and ArbitratedMemoryBusProvider remain the same for now,
// though ArbitratedMemoryBusProvider might become less relevant if the arbiter directly connects.
// For now, let's assume something might still want to observe the arbitrated bus externally.
trait MemoryArbiterService extends Service {
  def newMemoryMasterPort(config: GenericMemoryBusConfig, name: String = ""): GenericMemoryBus
}

trait ArbitratedMemoryBusProvider extends Service {
  def getArbitratedMemoryBus(): GenericMemoryBus
}

class MemoryArbiterPlugin(
  val arbiterPolicy: StreamArbiterFactory = StreamArbiterFactory.roundRobin,
  // mainBusConfigOverride can still be useful if the memory component's config
  // needs to be dictated by the arbiter (e.g. if memory is more generic).
  val mainBusConfigOverride: Option[GenericMemoryBusConfig] = None 
) extends Plugin with MemoryArbiterService with ArbitratedMemoryBusProvider {

  private val masterPortRequests = ArrayBuffer[(GenericMemoryBus, String)]()

  override def newMemoryMasterPort(config: GenericMemoryBusConfig, name: String = ""): GenericMemoryBus = {
    val port = GenericMemoryBus(config) 
    masterPortRequests += ((port, name))
    report(L"[MemoryArbiterPlugin] Master port '${name}' requested: AddrW=${config.addressWidth}, DataW=${config.dataWidth}")
    port
  }
  
  // This arbitratedMainBus is now an INTERNAL master bus within the arbiter plugin,
  // which it will then connect to a discovered slave memory.
  val arbitratedMainBusInternal = create late { // Renamed for clarity
    val busConf = mainBusConfigOverride.getOrElse {
      // Derivation logic... (can be simpler if we fetch memory's config)
      // --- START OF MODIFICATION for config derivation ---
      val memorySlaveServices = getServicesOf[ConnectableMemorySlave]
      if (memorySlaveServices.nonEmpty) {
        if (memorySlaveServices.length > 1) {
          SpinalWarning("MemoryArbiterPlugin found multiple ConnectableMemorySlave services. Connecting to the first one.")
        }
        memorySlaveServices.head.getMemoryConfig() // Use the config of the memory we'll connect to
      } else if (masterPortRequests.nonEmpty) {
        masterPortRequests.head._1.config // Fallback to first master's config
      } else {
        GenericMemoryBusConfig(32 bits, 32 bits) // Ultimate fallback
      }
      // --- END OF MODIFICATION for config derivation ---
    }
    report(L"[MemoryArbiterPlugin] Creating internal arbitratedMainBus (MASTER) with config: AddrW=${busConf.addressWidth}, DataW=${busConf.dataWidth}")
    master(GenericMemoryBus(busConf)) // Still a master from Arbiter's PoV
  }

  // The ArbitratedMemoryBusProvider might now just return this internal bus if needed,
  // but its primary connection is now handled internally.
  override def getArbitratedMemoryBus(): GenericMemoryBus = arbitratedMainBusInternal

  val logic = create late new Area {
    if (masterPortRequests.isEmpty) {
      report(L"WARNING: MemoryArbiterPlugin has no master ports. Arbiter logic will be minimal.")
      // arbitratedMainBusInternal might not be connected if no memory slave either.
      arbitratedMainBusInternal.cmd.valid := False
      arbitratedMainBusInternal.cmd.payload.assignDontCare()
      arbitratedMainBusInternal.rsp.ready := False 
    } else {
      // ... (arbiter logic for masterCmdStreams and cmdArbiter remains the same) ...
      val masterCmdStreams = masterPortRequests.map(_._1.cmd)
      val cmdArbiter = arbiterPolicy.build(masterCmdStreams.head.payloadType, masterCmdStreams.length)
      cmdArbiter.setCompositeName(MemoryArbiterPlugin.this, "cmdArbiter")
      for((masterCmdPort, arbiterInputPort) <- masterCmdStreams.zip(cmdArbiter.io.inputs)) {
        masterCmdPort >> arbiterInputPort
      }
      
      val busyWithMainBus = RegInit(False).setName("busyWithMainBus_reg")
      val chosenMasterOH_reg = Reg(Bits(masterPortRequests.length bits)).setName("chosenMasterOH_reg").init(0)

      // --- START OF MODIFICATION: Connecting arbitratedMainBusInternal to a slave memory ---
      val memorySlaveServices = getServicesOf[ConnectableMemorySlave]
      if (memorySlaveServices.isEmpty) {
        SpinalError("MemoryArbiterPlugin: No ConnectableMemorySlave service found to connect to.")
        // Make arbitratedMainBusInternal idle to prevent issues
        arbitratedMainBusInternal.cmd.valid := False
        arbitratedMainBusInternal.cmd.payload.assignDontCare()
        arbitratedMainBusInternal.rsp.ready := False
      } else {
        if (memorySlaveServices.length > 1) {
          SpinalWarning("MemoryArbiterPlugin: Found multiple ConnectableMemorySlave services. Connecting to the first one.")
        }
        val targetMemorySlaveBus = memorySlaveServices.head.getMemorySlaveBus()
        val targetMemoryConfig = memorySlaveServices.head.getMemoryConfig()

        // Config check between arbiter's output and memory's input
        if (arbitratedMainBusInternal.config.addressWidth != targetMemoryConfig.addressWidth ||
            arbitratedMainBusInternal.config.dataWidth != targetMemoryConfig.dataWidth) {
          SpinalError(s"MemoryArbiterPlugin: Mismatch between its arbitrated bus config (AddrW=${arbitratedMainBusInternal.config.addressWidth}, DataW=${arbitratedMainBusInternal.config.dataWidth}) " +
                      s"and target memory slave bus config (AddrW=${targetMemoryConfig.addressWidth}, DataW=${targetMemoryConfig.dataWidth}).")
        }

        report(L"[MemoryArbiterPlugin] Connecting its internal arbitrated bus (master) to discovered memory slave bus.")
        // arbitratedMainBusInternal is MASTER (output of arbiter core logic)
        // targetMemorySlaveBus is SLAVE (input to memory component)
        targetMemorySlaveBus.cmd << arbitratedMainBusInternal.cmd
        targetMemorySlaveBus.rsp >> arbitratedMainBusInternal.rsp
        // Now, the `:=` assignments within these `<<` and `>>` operators
        // are happening within the `MemoryArbiterPlugin`'s `logic` Area.
        // For `targetMemorySlaveBus.cmd << arbitratedMainBusInternal.cmd`:
        //   targetMemorySlaveBus.cmd.valid   := arbitratedMainBusInternal.cmd.valid (Arbiter drives slave's input - OK)
        //   targetMemorySlaveBus.cmd.payload := arbitratedMainBusInternal.cmd.payload (Arbiter drives slave's input - OK)
        //   arbitratedMainBusInternal.cmd.ready := targetMemorySlaveBus.cmd.ready (Arbiter takes input from slave's output - OK)
        // This should be fine because `targetMemorySlaveBus` is effectively an external interface from arbiter's perspective.
      }
      // --- END OF MODIFICATION ---

      // Connect arbiter output to arbitratedMainBusInternal.cmd
      arbitratedMainBusInternal.cmd.valid   := cmdArbiter.io.output.valid && !busyWithMainBus
      arbitratedMainBusInternal.cmd.payload := cmdArbiter.io.output.payload
      cmdArbiter.io.output.ready    := arbitratedMainBusInternal.cmd.ready && !busyWithMainBus

      when(arbitratedMainBusInternal.cmd.fire) {
        busyWithMainBus    := True
        chosenMasterOH_reg := cmdArbiter.io.chosenOH
        report(L"[MemoryArbiterPlugin] CMD fired to arbitratedMainBusInternal. ChosenOH=${cmdArbiter.io.chosenOH}. busyWithMainBus set to True.")
      }

      when(arbitratedMainBusInternal.rsp.fire) {
        busyWithMainBus := False
         report(L"[MemoryArbiterPlugin] RSP fired from arbitratedMainBusInternal. busyWithMainBus set to False.")
      }

      // Demux the response from arbitratedMainBusInternal.rsp to the selected master
      arbitratedMainBusInternal.rsp.ready := False
      for (i <- 0 until masterPortRequests.size) {
        val masterClientRspPort = masterPortRequests(i)._1.rsp
        masterClientRspPort.payload := arbitratedMainBusInternal.rsp.payload
        masterClientRspPort.valid   := arbitratedMainBusInternal.rsp.valid && chosenMasterOH_reg(i) && busyWithMainBus
        when(chosenMasterOH_reg(i) && busyWithMainBus) {
          arbitratedMainBusInternal.rsp.ready := masterClientRspPort.ready
        }
      }
      report(L"[MemoryArbiterPlugin] Arbitration logic established for ${masterPortRequests.size} masters.")
    }
  }
}
