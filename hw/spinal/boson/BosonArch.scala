package boson

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4 // Import AXI

import boson.plugins._
import boson.interfaces.AxiConfig // Import AxiConfig helper
import plugins.Decoder.DecodePlugin

case class BosonArch(val config: BosonConfig) extends Component with Pipeline {
  type T = BosonArch
  import config._ // Make Stageables directly accessible

  // --- Pipeline Stages ---
  def newStage(): Stage = { val s = new Stage; stages += s; s }
  // Stage 0: Fetch
  val fetch = newStage().setName("Fetch")

  // Stage 1: Decode / Rename (start with Decode)
  val decodeRename = newStage().setName("Decode/Rename")

  // Stage 2: Rename / Dispatch (placeholder)
  val renameDispatch = newStage().setName("Rename/Dispatch")

  // Stage 3: Issue / Register Read
  val issueRegRead = newStage().setName("Issue/RegRead")

  // Stage 4: Execute
  val execute = newStage().setName("Execute")

  // Stage 5: Memory Access
  val memory = newStage().setName("Memory")

  // Stage 6: Writeback
  val writeback = newStage().setName("Writeback")

  // Commit is handled implicitly by WB writing to Arch RF, or explicitly with ROB

  // --- IO Definition ---
  // Expose AXI bus if not using simulation memory
  val axiExpose = if (config.axiSimMem) { // Use slave() only if axiSimMem is false
    null
  } else {
    Axi4(AxiConfig.getAxi4Config(config))
  }

  val memoryBus = new components.AxiMemoryBusComponent(
    pipelineConfig = config,
    useSimMem = config.axiSimMem,
    simMemSize = config.axiSimMemSize,
    axiConfig = AxiConfig.getAxi4Config(config)
  )
  // --- Plugins ---
  // Order can matter for setup dependencies or build order if plugins interact directly
  plugins ++= Seq(
    // Core Pipeline Functionality
    new PcManagerPlugin(),
    new FetchPlugin(), // Example size
    new DecodePlugin(),
    new RegisterFilePlugin(registerCount = 32, dataWidth = config.dataWidth),
    new ExecutePlugin(),
    new MemoryPlugin(), // Example size

    // Supporting Plugins
    new HazardUnitPlugin(), // Detects hazards, signals forwarding/stalls

    // Placeholder Plugins (for future expansion)
    new RenamePlugin(),
    new DispatchPlugin(),
    new IssuePlugin()

    // new CsrPlugin(),
    // new FpuPlugin(),
    // new RobPlugin() // If implementing Reorder Buffer
  )

  // --- Global Connections / IO ---
  // Example: Expose memory interfaces if not handled internally by plugins
  // val iBus = master(new InstructionBus()) // Define your bus interface
  // val dBus = master(new DataBus())
  // FetchPlugin would use iBus, MemoryPlugin would use dBus

  // --- Connect Top-Level AXI (if needed) ---
  // The AxiMemoryBusComponent handles the internal connection or exposing the master.
  // We just need to connect the top-level slave IO (if it exists) to the plugin's master.
  addPrePopTask(() => { // Ensure plugin is built before this connection
    if (config.axiSimMem) {
    } else {
      // NOT IMPLEMENTED: Connect top-level AXI to plugin's master
    }
  })

  // The Pipeline trait's build() method (called automatically via Component.addPrePopTask)
  // connects stages and plugin logic based on Stageable usage.
}

// --- Main object for Verilog generation ---
object BosonVerilog extends App {
  println("Generating Boson Verilog...")

  // Configure for Simulation (Internal RAM)
  val cpuSimConfig = BosonConfig(
    axiSimMem = true,
    axiSimMemSize = 64 KiB // Smaller sim RAM example
  )
  println("Generating with Simulation Memory...")
  Config.spinal.generateVerilog(new BosonArch(cpuSimConfig)).printPruned()

  // Configure for Synthesis (External AXI)
  val cpuSynthConfig = BosonConfig(axiSimMem = false)
  println("\nGenerating with External AXI Interface...")
  val report = Config.spinal.generateVerilog(new BosonArch(cpuSynthConfig))
  report.printPruned() // printPruned helps view generated signals

  // Print top-level IO for synthesis version
  println("\nTop Level IO (Synthesis):")
  report.toplevel.getAllIo.foreach(println)

  println("\nVerilog generation complete.")
}
