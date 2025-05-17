// hw/spinal/parallax/components/memory/MemoryArbiterPlugin.scala
package parallax.components.memory

import spinal.core._
import spinal.lib._
// StreamArbiterFactory is already in spinal.lib, no need for specific sub-package import
// import spinal.lib.StreamArbiterFactory 

import parallax.utilities._
import scala.collection.mutable.ArrayBuffer

// --- Service for Masters to request a memory bus port from the arbiter ---
trait MemoryArbiterService extends Service {
  /**
   * Master (e.g., ICache, DCache) calls this to get a port to the memory system.
   * @param config The GenericMemoryBusConfig for this master's connection.
   * @param name An optional name for debugging/tracing this master's port.
   * @return A GenericMemoryBus master port that will be arbitrated.
   */
  def newMemoryMasterPort(config: GenericMemoryBusConfig, name: String = ""): GenericMemoryBus
}

// --- Service that provides the arbitrated MAIN bus (output of the arbiter) ---
// This is what the actual memory (or next level cache) connects to as a slave.
trait ArbitratedMemoryBusProvider extends Service {
  def getArbitratedMemoryBus(): GenericMemoryBus // Returns a MASTER bus from the arbiter's perspective
}


class MemoryArbiterPlugin(
  val arbiterPolicy: StreamArbiterFactory = StreamArbiterFactory.roundRobin,
  val mainBusConfigOverride: Option[GenericMemoryBusConfig] = None // Optional: force a specific config for the main output bus
) extends Plugin with MemoryArbiterService with ArbitratedMemoryBusProvider {

  private val masterPortRequests = ArrayBuffer[(GenericMemoryBus, String)]()

  override def newMemoryMasterPort(config: GenericMemoryBusConfig, name: String = ""): GenericMemoryBus = {
    // This port is a MASTER from the perspective of the component requesting it.
    // The arbiter will connect to its slave side for commands and master side for responses.
    val port = GenericMemoryBus(config) 
    masterPortRequests += ((port, name))
    report(L"[MemoryArbiterPlugin] Master port '${name}' requested: AddrW=${config.addressWidth}, DataW=${config.dataWidth}")
    port
  }
  
  // The main output bus of the arbiter. This is a MASTER bus.
  // Its configuration can be overridden or derived from masters.
  val arbitratedMainBus = create late {
    val busConf = mainBusConfigOverride.getOrElse {
      if (masterPortRequests.nonEmpty) {
        // Basic policy: use the config of the first registered master.
        // More sophisticated logic could ensure compatibility or use widest settings.
        masterPortRequests.head._1.config
      } else {
        // Default if no masters (arbiter would be idle). This should be configured properly in a real system.
        report(L"WARNING: [MemoryArbiterPlugin] No masters registered to derive mainBusConfig. Using default 32bits Addr/Data.")
        GenericMemoryBusConfig(32 bits, 32 bits)
      }
    }
    report(L"[MemoryArbiterPlugin] Creating arbitratedMainBus (MASTER from Arbiter PoV) with config: AddrW=${busConf.addressWidth}, DataW=${busConf.dataWidth}")
    master(GenericMemoryBus(busConf)) // This is a MASTER port from the Arbiter's perspective
  }

  override def getArbitratedMemoryBus(): GenericMemoryBus = arbitratedMainBus

  val logic = create late new Area {
    if (masterPortRequests.isEmpty) {
      report(L"WARNING: MemoryArbiterPlugin has no master ports. Arbiter output (arbitratedMainBus) will be idle.")
      arbitratedMainBus.cmd.valid := False
      arbitratedMainBus.cmd.payload.assignDontCare()
      arbitratedMainBus.rsp.ready := False // Or True, effectively consuming nothing.
    } else {
      // Sanity check master port configurations against the arbitratedMainBus config.
      masterPortRequests.foreach { case (port, name) =>
        if (port.config.addressWidth != arbitratedMainBus.config.addressWidth ||
            port.config.dataWidth != arbitratedMainBus.config.dataWidth) {
          SpinalWarning(s"MemoryArbiterPlugin: Master '$name' (AddrW=${port.config.addressWidth}, DataW=${port.config.dataWidth}) " +
                        s"has a different config than arbitratedMainBus (AddrW=${arbitratedMainBus.config.addressWidth}, DataW=${arbitratedMainBus.config.dataWidth}). " +
                        "Direct connection might be problematic without bus width/address converters.")
        }
      }

      val masterCmdStreams = masterPortRequests.map(_._1.cmd) // These are Streams of GenericMemoryBusCmd
      
      // Instantiate the command arbiter explicitly to get chosenOH signal
      val cmdArbiter = arbiterPolicy.build(masterCmdStreams.head.payloadType, masterCmdStreams.length)
      cmdArbiter.setCompositeName(MemoryArbiterPlugin.this, "cmdArbiter")
      
      // Connect each master's command stream to the arbiter's input ports
      for((masterCmdPort, arbiterInputPort) <- masterCmdStreams.zip(cmdArbiter.io.inputs)) {
        // masterCmdPort is what the master drives (master Stream).
        // arbiterInputPort is the slave Stream input of the arbiter.
        masterCmdPort >> arbiterInputPort
      }
      
      // Logic to handle one transaction at a time with the main bus (downstream memory)
      val busyWithMainBus = RegInit(False).setName("busyWithMainBus_reg")
      val chosenMasterOH_reg = Reg(Bits(masterPortRequests.length bits)).setName("chosenMasterOH_reg").init(0)

      // Connect arbiter output to main bus command, qualified by !busyWithMainBus
      // arbitratedMainBus.cmd is the MASTER stream from the arbiter to memory
      arbitratedMainBus.cmd.valid   := cmdArbiter.io.output.valid && !busyWithMainBus
      arbitratedMainBus.cmd.payload := cmdArbiter.io.output.payload
      // Arbiter's output is ready if the main bus is ready AND we are not already busy
      cmdArbiter.io.output.ready    := arbitratedMainBus.cmd.ready && !busyWithMainBus

      when(arbitratedMainBus.cmd.fire) { // A command is successfully sent to the main bus
        busyWithMainBus    := True
        chosenMasterOH_reg := cmdArbiter.io.chosenOH // Latch which master got selected
        report(L"[MemoryArbiterPlugin] CMD fired to mainBus. ChosenOH=${cmdArbiter.io.chosenOH}. busyWithMainBus set to True.")
      }

      // arbitratedMainBus.rsp is the SLAVE stream from memory back to the arbiter
      when(arbitratedMainBus.rsp.fire) { // A response is successfully received from the main bus
        busyWithMainBus := False // Ready for a new command from arbiter to main bus
         report(L"[MemoryArbiterPlugin] RSP fired from mainBus. busyWithMainBus set to False.")
      }

      // Demux the response from arbitratedMainBus.rsp to the selected master's rsp port
      arbitratedMainBus.rsp.ready := False // Default: Arbiter not ready to accept response from memory
      for (i <- 0 until masterPortRequests.size) {
        val masterClientPort = masterPortRequests(i)._1 // This is the master's GenericMemoryBus instance
        val masterClientRspPort = masterClientPort.rsp // This is the SLAVE rsp port on the Master side

        masterClientRspPort.payload := arbitratedMainBus.rsp.payload
        // Response is valid for master i if:
        // 1. Main bus has a valid response.
        // 2. Master i was the one latched (chosenMasterOH_reg).
        // 3. The arbiter is currently busyWithMainBus (meaning a response is expected for chosenMasterOH_reg).
        masterClientRspPort.valid   := arbitratedMainBus.rsp.valid && chosenMasterOH_reg(i) && busyWithMainBus
        
        when(chosenMasterOH_reg(i) && busyWithMainBus) { // If this master is expecting the response
          arbitratedMainBus.rsp.ready := masterClientRspPort.ready // Arbiter is ready if chosen master is ready
        }
      }
      report(L"[MemoryArbiterPlugin] Arbitration logic (1-txn-at-a-time to main bus) established for ${masterPortRequests.size} masters.")
    }
  }
}
