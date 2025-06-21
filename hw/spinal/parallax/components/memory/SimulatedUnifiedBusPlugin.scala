package parallax.components.memory // Assuming this is the correct package

import parallax.utilities.{Plugin, Service}
import spinal.lib._
import spinal.core._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4CrossbarFactory}
import spinal.lib.bus.misc.SizeMapping
import parallax.bus.{SplitGmbToAxi4Bridge, Axi4ToSplitGmbBridge} // Import both bridges

// IBusService and DBusService traits remain the same
trait IBusServiceSGMB extends Service {
  def iBus: SplitGenericMemoryBus
}

trait DBusServiceSGMB extends Service {
  def dBus: SplitGenericMemoryBus
}

class SimulatedUnifiedBusPlugin(
    // --- GMB configurations for the ports EXPOSED by this plugin ---
    val iBusGmbConfig: GenericMemoryBusConfig =
      GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = 32 bits, useId = true, idWidth = 4 bits),
    val dBusGmbConfig: GenericMemoryBusConfig =
      GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = 32 bits, useId = true, idWidth = 4 bits),

    // --- AXI configuration for the segment BETWEEN GMB-to-AXI bridges and the AXI-to-GMB bridge ---
    // This is the AXI "fabric" configuration.
    // The ID width here must accommodate GMB IDs from iBus/dBus, plus routing bits from the crossbar.
    // Let numMastersToCrossbar = 2 (I-bridge, D-bridge). Routing bits = log2Up(2) = 1.
    // So, fabricAxiConfig.idWidth should be at least max(iBusGmbConfig.idWidth, dBusGmbConfig.idWidth) + 1.
    val fabricAxiConfig: Axi4Config = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = (4 + 1), // Example: 4-bit GMB ID + 1 routing bit
      useLock = false,
      useRegion = false,
      useCache = false,
      useProt = false,
      useQos = false,
      useLen = true,
      useBurst = true,
      useSize = true,
      useStrb = true,
      useResp = true,
      useLast = true
    ),

    // --- GMB configuration for the SimulatedSplitGeneralMemory ---
    // This must match the GMB side of the Axi4ToSplitGmbBridge.
    // For simplicity, let's make it match the fabric AXI properties where applicable (addr/data width).
    // Its ID width should match the fabricAxiConfig.idWidth if the Axi4ToSplitGmbBridge passes IDs through.
    val simMemGmbConfig: GenericMemoryBusConfig = GenericMemoryBusConfig(
      addressWidth = 32 bits, // Matches fabricAxiConfig.addressWidth
      dataWidth = 32 bits, // Matches fabricAxiConfig.dataWidth
      useId = true, // Assuming Axi4ToSplitGmbBridge handles IDs
      idWidth = (4 + 1) bits // Matches fabricAxiConfig.idWidth
    ),

    // --- Configuration for the SimulatedSplitGeneralMemory itself ---
    val simMemInternalConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(
      internalDataWidth = 32 bits,
      memSize = 16 KiB,
      initialLatency = 2
    ),
    val simMemBaseAddress: Long = 0x00000000L
) extends Plugin
    with IBusServiceSGMB
    with DBusServiceSGMB {

  // The GMB slave ports that this plugin will offer as services
  val iBusPort = slave(SplitGenericMemoryBus(iBusGmbConfig))
  val dBusPort = slave(SplitGenericMemoryBus(dBusGmbConfig))

  // Implementation of the service methods
  override def iBus: SplitGenericMemoryBus = iBusPort
  override def dBus: SplitGenericMemoryBus = dBusPort

  val logic = create late new Area {
    // 1. GMB-to-AXI Bridges (Masters to the Crossbar)
    //    The AXI output config of these bridges must be compatible with the crossbar's master inputs.
    //    The crossbar will potentially add routing bits, so the fabricAxiConfig.idWidth
    //    (which is the input to Axi4ToSplitGmbBridge) must account for this.
    //    The bridgeOutputAxiConfig for SplitGmbToAxi4Bridge should have an ID width
    //    that is the GMB ID width (e.g., 4 bits). The crossbar then maps this to
    //    fabricAxiConfig.idWidth (e.g., 5 bits).

    val gmbMasterOutputAxiConfig = fabricAxiConfig.copy(
      idWidth = iBusGmbConfig.idWidth.value // AXI output of GMB->AXI bridge has GMB's ID width
    )
    // If dBusGmbConfig.idWidth is different, you might need another config or ensure they are the same.
    assert(
      iBusGmbConfig.idWidth.value == dBusGmbConfig.idWidth.value,
      "GMB ID widths for I and D bus must match for this simplified bridge output config"
    )

    val iBusToAxiBridge = new SplitGmbToAxi4Bridge(iBusGmbConfig, gmbMasterOutputAxiConfig)
    // Connect iBusPort (plugin's slave GMB) to iBusToAxiBridge.io.gmbIn (bridge's slave GMB)
    // Command: iBusPort -> bridge
    iBusToAxiBridge.io.gmbIn.read.cmd << iBusPort.read.cmd
    iBusToAxiBridge.io.gmbIn.write.cmd << iBusPort.write.cmd
    // Response: bridge -> iBusPort
    iBusPort.read.rsp << iBusToAxiBridge.io.gmbIn.read.rsp
    iBusPort.write.rsp << iBusToAxiBridge.io.gmbIn.write.rsp

    val dBusToAxiBridge = new SplitGmbToAxi4Bridge(dBusGmbConfig, gmbMasterOutputAxiConfig)
    // Connect dBusPort (plugin's slave GMB) to dBusToAxiBridge.io.gmbIn (bridge's slave GMB)
    // Command: dBusPort -> bridge
    dBusToAxiBridge.io.gmbIn.read.cmd << dBusPort.read.cmd
    dBusToAxiBridge.io.gmbIn.write.cmd << dBusPort.write.cmd
    // Response: bridge -> dBusPort
    dBusPort.read.rsp << dBusToAxiBridge.io.gmbIn.read.rsp
    dBusPort.write.rsp << dBusToAxiBridge.io.gmbIn.write.rsp


    // 2. AXI Crossbar
    val crossbar = Axi4CrossbarFactory()

    // 3. AXI-to-GMB Bridge (Slave to the Crossbar, Master to SimulatedSplitGeneralMemory)
    //    Its AXI input config is fabricAxiConfig.
    //    Its GMB output config is simMemGmbConfig.
    val axiToGmbBridge = new Axi4ToSplitGmbBridge(fabricAxiConfig, simMemGmbConfig)

    // 4. SimulatedSplitGeneralMemory (Slave to the Axi4ToSplitGmbBridge)
    val simulatedMemory = new SimulatedSplitGeneralMemory(
      memConfig = simMemInternalConfig,
      busConfig = simMemGmbConfig // Its bus config matches the GMB output of axiToGmbBridge
    )
    simulatedMemory.io.bus <> axiToGmbBridge.io.gmbOut // Connect GMBs

    // 5. Connect Crossbar
    // Add the AXI input of axiToGmbBridge as a slave to the crossbar
    crossbar.addSlave(axiToGmbBridge.io.axiIn, SizeMapping(simMemBaseAddress, simMemInternalConfig.memSize))

    // Add connections from GMB-to-AXI bridge outputs (masters) to the crossbar,
    // specifying they can access the axiToGmbBridge's AXI slave port.
    crossbar.addConnection(iBusToAxiBridge.io.axiOut, Seq(axiToGmbBridge.io.axiIn))
    crossbar.addConnection(dBusToAxiBridge.io.axiOut, Seq(axiToGmbBridge.io.axiIn))

    crossbar.build()
  }
}
