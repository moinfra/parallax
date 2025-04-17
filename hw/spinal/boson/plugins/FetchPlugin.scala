package boson.plugins

import spinal.core._
import spinal.lib._
import boson._

// Basic Fetch Stage using a synchronous memory (simulation friendly)
// Replace with a proper bus interface (e.g., Wishbone, AXI) for synthesis.
class FetchPlugin(instructionMemSize: BigInt) extends Plugin[Boson] {

  var instructionMemory: Mem[Bits] = null

  override def setup(pipeline: Boson): Unit = {
    instructionMemory = Mem(Bits(pipeline.config.instructionWidth bits), wordCount = instructionMemSize / 4)
    // instructionMemory.initBigInt(List(
    //    BigInt("...", 16), // Add initial instructions here for simulation
    //    BigInt("...", 16)
    // ))
    println(s"FetchPlugin: Initialized ${instructionMemory.wordCount} words Instruction Memory.")
  }

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    fetch plug new Area {
      import fetch._ // Access arbitration signals

      val fetchPc = input(PC) // Get PC from PcManagerPlugin

      // --- Instruction Memory Read ---
      // Simple synchronous read based on PC. Assumes word alignment.
      // A real cache/bus interface would be more complex (request/grant/response).
      val readAddress = fetchPc(log2Up(instructionMemory.wordCount * 4) - 1 downto 2)
      val fetchedInstruction = instructionMemory.readSync(
        address = readAddress,
        enable = arbitration.isValid && !arbitration.isStuck // Read only when stage is firing
      )

      // Insert fetched instruction and PC into the pipeline
      insert(INSTRUCTION) := fetchedInstruction
      insert(IS_VALID) := arbitration.isValid // Pass validity downstream

      // Stall condition (e.g., waiting for memory response) would set haltItself
      // arbitration.haltItself := !instructionMemory.readRspValid // Example

      report(L"[Fetch] Firing=${arbitration.isFiring} PC=${fetchPc} -> Instr=${fetchedInstruction}")
    }
  }
}
