package boson

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._

import boson.plugins._

case class Boson(val config: BosonConfig) extends Component with Pipeline {
  type T = Boson
  import config._ // Make Stageables directly accessible

  // --- Pipeline Stages ---
  def newStage(): Stage = { val s = new Stage; stages += s; s }
  // Stage 0: Fetch
  val fetch = newStage()
  fetch.setName("fetch")

  // Stage 1: Decode / Rename (start with Decode)
  val decodeRename = newStage()
  decodeRename.setName("decodeRename")

  // Stage 2: Rename / Dispatch (placeholder)
  val renameDispatch = newStage()
  renameDispatch.setName("renameDispatch")

  // Stage 3: Issue / Register Read
  val issueRegRead = newStage()
  issueRegRead.setName("issueRegRead")

  // Stage 4: Execute
  val execute = newStage()
  execute.setName("execute")

  // Stage 5: Memory Access
  val memory = newStage()
  memory.setName("memory")

  // Stage 6: Writeback
  val writeback = newStage()
  writeback.setName("writeback")

  // Commit is handled implicitly by WB writing to Arch RF, or explicitly with ROB

  // --- Plugins ---
  // Order can matter for setup dependencies or build order if plugins interact directly
  plugins ++= Seq(
    // Core Pipeline Functionality
    new PcManagerPlugin(),
    new FetchPlugin(instructionMemSize = 1024), // Example size
    new DecodePlugin(),
    new RegisterFilePlugin(registerCount = 32, dataWidth = config.dataWidth),
    new ExecutePlugin(),
    new MemoryPlugin(dataMemSize = 1024), // Example size

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

  // The Pipeline trait's build() method (called automatically via Component.addPrePopTask)
  // connects stages and plugin logic based on Stageable usage.
}

// --- Main object for Verilog generation ---
object BosonVerilog extends App {
  println("Generating Boson Verilog...")
  val cpuConfig = BosonConfig() // Use default config or customize
  Config.spinal.generateVerilog(new Boson(cpuConfig)).printPruned() // printPruned helps view generated signals
  println("Verilog generation complete.")
}
