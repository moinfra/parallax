package boson.components // Changed package slightly for clarity, adjust as needed

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping // Keep relevant imports
// Removed StreamArbiterFactory as it wasn't used in the provided snippet
// Removed SizeMapping as it wasn't used in the provided snippet

// Assuming BosonConfig and interface types are accessible from these imports
import boson.BosonConfig
import boson.interfaces._

/**
 * An AXI Memory Bus Component.
 *
 * This component acts as a bridge between internal instruction/data bus requests
 * and an external AXI4 master interface. It includes internal FIFOs for decoupling
 * and optional internal simulation memory.
 *
 * @param pipelineConfig Configuration object containing bus parameters (e.g., address width).
 * @param useSimMem If true, instantiate an internal AXI memory slave for simulation.
 * @param simMemSize The size of the internal simulation memory (if useSimMem is true).
 * @param axiConfig The AXI4 configuration for the master interface.
 */
case class AxiMemoryBusComponent(
    pipelineConfig: BosonConfig,
    useSimMem: Boolean = true,
    simMemSize: BigInt = 1 MiB,
    axiConfig: Axi4Config
) extends Component {

    // Define the Input/Output Bundle for the Component
    val io = new Bundle {
        // Internal Bus Interface (connections to CPU/other modules)
        val iBusCmd = slave Stream (InstrFetchCmd(pipelineConfig)) // Instruction fetch requests IN
        val iBusRsp = master Stream (InstrFetchRsp(pipelineConfig)) // Instruction fetch responses OUT
        val dBusCmd = slave Stream (DataMemCmd(pipelineConfig))    // Data memory requests IN
        val dBusRsp = master Stream (DataMemRsp(pipelineConfig))    // Data memory responses OUT

        // External AXI Master Interface
        val axiMaster = master(Axi4(axiConfig)).setName("axiMaster")
    }

    // --- Simulation Memory Instance (if used) ---
    // Declared here but instantiated conditionally later
    var simRamSalve: Axi4MemorySlave = null

    // --- Instantiate Internal Bus FIFOs ---
    // These decouple the component's IO from the internal AXI logic.

    // Instruction Bus Command FIFO
    val iBusCmdFifo = StreamFifo(dataType = InstrFetchCmd(pipelineConfig), depth = 2)
    iBusCmdFifo.io.push << io.iBusCmd // Connect component input to FIFO input

    // Instruction Bus Response FIFO
    val iBusRspFifo = StreamFifo(dataType = InstrFetchRsp(pipelineConfig), depth = 2)
    io.iBusRsp << iBusRspFifo.io.pop // Connect FIFO output to component output

    // Data Bus Command FIFO (Placeholder logic)
    val dBusCmdFifo = StreamFifo(dataType = DataMemCmd(pipelineConfig), depth = 2)
    dBusCmdFifo.io.push << io.dBusCmd       // Connect component input to FIFO input
    dBusCmdFifo.io.pop.ready := False       // Consume nothing for now

    // Data Bus Response FIFO (Placeholder logic)
    val dBusRspFifo = StreamFifo(dataType = DataMemRsp(pipelineConfig), depth = 2)
    io.dBusRsp << dBusRspFifo.io.pop        // Connect FIFO output to component output
    dBusRspFifo.io.push.valid := False      // Produce nothing for now
    dBusRspFifo.io.push.payload.assignDontCare()

    // --- Optional Simulation Memory Connection ---
    if (useSimMem) {
        simRamSalve = new Axi4MemorySlave(axiConfig, simMemSize).setName("simRam")
        // Connect the component's AXI master IO directly to the internal simulation slave
        io.axiMaster <> simRamSalve.io.axi
    }
    // If useSimMem is false, io.axiMaster remains unconnected here and must be
    // connected externally. The logic below will drive io.axiMaster.

    // --- Handle Instruction Fetch AXI Transactions ---
    val arBusy = RegInit(False).setName("arBusy") // Tracks if an AR transaction is outstanding

    // AXI AR Channel (Read Address) - Driven by instruction command FIFO's output
    io.axiMaster.ar.valid := iBusCmdFifo.io.pop.valid && !arBusy // Send address when FIFO has data and no pending read
    io.axiMaster.ar.payload.addr := iBusCmdFifo.io.pop.payload.address
    io.axiMaster.ar.payload.id := 0 // Example ID, adjust if needed
    io.axiMaster.ar.payload.len := 0 // Single beat burst
    io.axiMaster.ar.payload.size := U(log2Up(axiConfig.bytePerWord)) // Transfer size = word size
    io.axiMaster.ar.payload.burst := Axi4.burst.INCR // Incrementing burst (standard for single beat)

    // Consume command from FIFO only when AXI AR channel accepts it
    iBusCmdFifo.io.pop.ready := io.axiMaster.ar.ready && !arBusy

    when(io.axiMaster.ar.fire) {
        arBusy := True // Mark that a read transaction has started
        report(L"[AxiBusComponent] AR Fired: Addr=${io.axiMaster.ar.payload.addr}")
    }

    // AXI R Channel (Read Data) - Drives instruction response FIFO's input
    iBusRspFifo.io.push.valid := io.axiMaster.r.valid && arBusy // Push to response FIFO when AXI R has valid data and we expect it
    iBusRspFifo.io.push.payload.instruction := io.axiMaster.r.payload.data
    iBusRspFifo.io.push.payload.error := io.axiMaster.r.payload.resp =/= Axi4.resp.OKAY // Check for AXI errors

    // Accept data from AXI R channel only if the response FIFO has space
    io.axiMaster.r.ready := iBusRspFifo.io.push.ready

    when(iBusRspFifo.io.push.fire) {
        arBusy := False // Mark that the read transaction has completed
        report(
            L"[AxiBusComponent] R Fired & Pushed: Data=${io.axiMaster.r.payload.data} Err=${io.axiMaster.r.payload.resp =/= Axi4.resp.OKAY}"
        )
    }

    // --- Handle Data Memory AXI Transactions (Placeholder) ---
    // Tie off Write Address, Write Data, and Write Response channels for now
    io.axiMaster.aw.valid := False
    io.axiMaster.aw.payload.assignDontCare()
    io.axiMaster.w.valid := False
    io.axiMaster.w.payload.assignDontCare()
    io.axiMaster.b.ready := False // Don't accept write responses yet

   // --- Drive Default/Tie-Off Values for Undriven Master Signals ---
    // We must drive all output signals of the master interface if they
    // exist based on axiConfig and aren't already driven by the main logic.

    // AR Channel - Already driven: valid, addr. Explicitly drive others if they exist.
    // Note: id, len, size, burst were already driven in the main logic above.
    if (axiConfig.useProt && io.axiMaster.ar.payload.prot != null) {
        // Provide a default protection type if not driven by specific logic.
        // Adjust default as needed (e.g., based on security requirements).
        io.axiMaster.ar.payload.prot := Axi4.prot.NON_SECURE
    }
    if (axiConfig.useLock && io.axiMaster.ar.payload.lock != null) {
        // Default to normal, non-locked access
        io.axiMaster.ar.payload.lock := Axi4.lock.NORMAL
    }
    if (axiConfig.useCache && io.axiMaster.ar.payload.cache != null) {
        // Default to non-cacheable, non-bufferable (safe for peripherals/device memory)
        // Adjust if targeting cacheable memory primarily.
        io.axiMaster.ar.payload.cache := 0
    }
    if (axiConfig.useQos && io.axiMaster.ar.payload.qos != null) {
        // Default Quality of Service identifier to 0
        io.axiMaster.ar.payload.qos := 0
    }
    if (axiConfig.useRegion && io.axiMaster.ar.payload.region != null) {
        // Default region identifier to 0
        io.axiMaster.ar.payload.region := 0
    }
    // Add user signal tie-off if your Axi4Config supports it
    // if (axiConfig.useUser && io.axiMaster.ar.payload.user != null) {
    //     io.axiMaster.ar.payload.user := 0 // Adjust width/value as needed
    // }


    // AW Channel - Currently aw.valid is hardcoded to False.
    // However, we should still define default values for the payload
    // signals in case the logic changes or for tool correctness.
    if (io.axiMaster.aw != null) { // Check channel exists
        // Assign defaults even though valid is False (safer for tools/future)
        io.axiMaster.aw.payload.addr  := U(0) // Default address
        io.axiMaster.aw.payload.id    := U(0) // Default ID
        io.axiMaster.aw.payload.len   := U(0) // Default len (single beat)
        io.axiMaster.aw.payload.size  := U(log2Up(axiConfig.bytePerWord)) // Default size
        io.axiMaster.aw.payload.burst := Axi4.burst.INCR // Default burst

        if (axiConfig.useProt && io.axiMaster.aw.payload.prot != null) {
            io.axiMaster.aw.payload.prot := Axi4.prot.NON_SECURE
        }
        if (axiConfig.useLock && io.axiMaster.aw.payload.lock != null) {
            io.axiMaster.aw.payload.lock := Axi4.lock.NORMAL
        }
        if (axiConfig.useCache && io.axiMaster.aw.payload.cache != null) {
            io.axiMaster.aw.payload.cache := 0
        }
        if (axiConfig.useQos && io.axiMaster.aw.payload.qos != null) {
            io.axiMaster.aw.payload.qos := 0
        }
        if (axiConfig.useRegion && io.axiMaster.aw.payload.region != null) {
            io.axiMaster.aw.payload.region := 0
        }
        // Add user signal tie-off if your Axi4Config supports it
        // if (axiConfig.useUser && io.axiMaster.aw.payload.user != null) {
        //     io.axiMaster.aw.payload.user := 0 // Adjust width/value as needed
        // }
    }


    // W Channel - Currently w.valid is hardcoded to False.
    // Drive payload defaults similarly to AW.
    if (io.axiMaster.w != null) { // Check channel exists
        // Assign defaults even though valid is False
        io.axiMaster.w.payload.data := B(0) // Default data to all zeros
        io.axiMaster.w.payload.last := True // Default last (for single beat or when valid=F)

        if (axiConfig.useStrb && io.axiMaster.w.payload.strb != null) {
            // Default strobes to all low (no bytes valid) when valid=False
            io.axiMaster.w.payload.strb := B(0)
        }
        // Add user signal tie-off if your Axi4Config supports it
        // if (axiConfig.useUser && io.axiMaster.w.payload.user != null) {
        //     io.axiMaster.w.payload.user := 0 // Adjust width/value as needed
        // }
    }

    // R Channel Inputs (from Slave to Master): valid, data, resp, last, id, user
    // Master drives: ready (Handled above)
    // -> No tie-offs needed here for master outputs

    // B Channel Inputs (from Slave to Master): valid, resp, id, user
    // Master drives: ready (Handled above)
    // -> No tie-offs needed here for master outputs
}
