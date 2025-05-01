package boson

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import boson.components.AxiMemoryBusComponent // Import to access simRam if needed
object BosonSim {

    def main(args: Array[String]): Unit = {
        // Simulation Configuration
        val simConfig = SimConfig
            .withConfig(Config.spinal) // Use project's SpinalConfig
            .withFstWave // Generate FST waveform
            // Optional: Add timeout
            // .withSimTimeOut(10000 * 10) // Example: 10000 cycles timeout

        // --- Select Configuration ---
        val useSimMem = true // <<< Set to true for this test
        val cpuConfig = if (useSimMem) {
            BosonConfig(
                axiSimMem = true,
                axiSimMemSize = 64 KiB, // Keep sim RAM reasonably small
                resetPc = 0x100 // Start fetching from address 0x100
            )
        } else {
             BosonConfig(axiSimMem = false, resetPc = 0x100)
        }

        println(s"Starting Boson Simulation (SimMem: ${useSimMem}, ResetPC: 0x${cpuConfig.resetPc.toString(16)})")

        // Compile the design
        simConfig.compile(new BosonArch(cpuConfig)).doSim { dut =>
            println("Simulation Started...")

            // --- Clock Domain ---
            dut.clockDomain.forkStimulus(period = 10) // 10ns clock period

            // --- Memory Initialization (if using SimMem) ---
            if (useSimMem) {
                println("Initializing Simulation Memory...")
                // Access the simulation RAM through the plugin instance
                // Note: This relies on finding the plugin and its simRam instance.
                // You might need to adjust the path based on generated names or make
                // the plugin/simRam more easily accessible if this fails.
                val axiPlugin = dut.plugins.collectFirst { case p: AxiMemoryBusComponent => p }.get
                val memory = axiPlugin.simRamSalve.ram

                // Example Instructions (LoongArch format - placeholders, replace with real encodings)
                // Assuming 32-bit instructions at word addresses
                // ADDI.W R4, R0, 10   (assuming R0=0) -> 0x0280000a (placeholder encoding)
                memory.setBigInt(0x100 / 4, 0x0280000aL) // @ 0x100
                // ADDI.W R5, R4, 20                     -> 0x02884014 (placeholder encoding)
                memory.setBigInt(0x104 / 4, 0x02884014L) // @ 0x104
                // SUB.W R6, R5, R4                      -> 0x00085886 (placeholder encoding)
                memory.setBigInt(0x108 / 4, 0x00085886L) // @ 0x108
                // NOP (or another simple instruction)
                memory.setBigInt(0x10C / 4, 0x03000000L) // Example NOP encoding

                println(s"Memory Initialized: 0x100=0x${memory.getBigInt(0x100 / 4).toString(16)}, 0x104=0x${memory.getBigInt(0x104 / 4).toString(16)}, ...")
            } else {
                println("Using External AXI - Memory Initialization Skipped (Assumed preloaded externally)")
                // Here you would need an AXI VIP/BFM in your testbench to respond to AXI requests
            }

            // --- Reset ---
            println("Applying Reset...")
            dut.clockDomain.assertReset()
            dut.clockDomain.waitSampling(5) // Hold reset for a few cycles
            dut.clockDomain.deassertReset()
            println("Reset Released.")

            // --- Run Simulation ---
            println("Running simulation...")
            var cycles = 0
            val maxCycles = 100 // Limit simulation duration

            while (cycles < maxCycles) {
                 dut.clockDomain.waitSampling()
                 cycles += 1
                 if (cycles % 10 == 0) {
                      println(s"[Sim Cycle ${cycles}]")
                 }
                 // Add conditions to stop simulation, e.g., check for specific PC or instruction commit
            }

            println(s"Simulation finished after ${cycles} cycles.")
            simSuccess() // Or simFailure() based on checks
        } // End doSim
    } // End main
}
