package boson.components
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._ // FSM library for state machines\

/** A simple AXI4 Slave component acting as a memory model.
  * - Implements a basic RAM using SpinalHDL's Mem.
  * - Supports single beat read and write operations (LEN=0).
  * - Uses a state machine for handling the write transaction phases (AW, W, B).
  * - Uses registered logic for handling read transaction phases (AR, R) considering Mem read latency.
  *
  * @param axiConfig The AXI4 configuration.
  * @param size The size of the memory in bytes.
  */
class Axi4MemorySlave(axiConfig: Axi4Config, size: BigInt) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig)) // AXI4 Slave interface
  }

  // Ensure memory size is valid for Mem synthesis (fits in Int)
  require(size > 0, "Memory size must be positive")
  require(size % axiConfig.bytePerWord == 0, "Memory size must be a multiple of bus width")
  require(size <= Int.MaxValue, "For synthesis with Mem, size must fit in Int") // Limitation of Mem

  // Calculate memory parameters
  val wordCount = (size / axiConfig.bytePerWord).toInt
  val ramAddrWidth = log2Up(wordCount) // Address width required for the internal RAM
  val axiAddrLsb = log2Up(axiConfig.bytePerWord) // LSB index of the AXI address used for word selection
  val axiAddrMsb = ramAddrWidth + axiAddrLsb - 1 // MSB index of the AXI address used for word selection

  // Internal RAM model
  val ram = Mem(Bits(axiConfig.dataWidth bits), wordCount = wordCount)

  // --- Write Channel Logic (using State Machine) ---
  val writeAddrReg = Reg(UInt(axiConfig.addressWidth bits)) // Register to store AXI write address
  val writeIdReg = if (axiConfig.useId) Reg(UInt(axiConfig.idWidth bits)) else null // Register for AXI write ID

  // Default assignments for write channel outputs (Slave -> Master)
  io.axi.aw.ready := False // Not ready to accept address by default
  io.axi.w.ready := False // Not ready to accept write data by default
  io.axi.b.valid := False // Write response not valid by default
  io.axi.b.payload.resp := Axi4.resp.OKAY // Default OKAY response
  if (axiConfig.useId) io.axi.b.payload.id := 0 // Default ID 0

  // Write Finite State Machine (FSM)
  val writeFsm = new StateMachine {
    val sWIdle = new State with EntryPoint // Idle state, waiting for Write Address (AW)
    val sWWaitData = new State // Waiting for Write Data (W)
    val sWWaitResp = new State // Waiting to send Write Response (B)

    // State: Idle - Waiting for Write Address
    sWIdle.whenIsActive {
      io.axi.aw.ready := True // Ready to accept a new write address request
      when(io.axi.aw.fire) { // When AW handshake occurs (valid & ready)
        writeAddrReg := io.axi.aw.payload.addr.resized // Latch the AXI address
        if (axiConfig.useId) writeIdReg := io.axi.aw.payload.id // Latch the ID
        goto(sWWaitData) // Transition to wait for write data state
      }
    }

    // State: Wait Data - Waiting for Write Data
    sWWaitData.whenIsActive {
      io.axi.w.ready := True // Ready to accept write data
      when(io.axi.w.fire) { // When W handshake occurs (valid & ready)
        // Calculate the word address for the internal RAM
        val wordAddr = writeAddrReg(axiAddrMsb downto axiAddrLsb)
        // Perform the RAM write operation
        ram.write(
          address = wordAddr, // Use the calculated word address
          data = io.axi.w.payload.data,
          enable = True, // Write is enabled by the 'when(w.fire)' condition
          mask = io.axi.w.payload.strb // Use byte strobes from the W channel
        )
        // Report write execution (for simulation)
        report(
          L"Slave Write Executed: AXI_Addr=${writeAddrReg} Word_Addr=${wordAddr} Data=${io.axi.w.payload.data} Strb=${io.axi.w.payload.strb}\n"
        )
        goto(sWWaitResp) // Transition to wait for response state
      }
    }

    // State: Wait Response - Ready to send Write Response
    sWWaitResp.whenIsActive {
      io.axi.b.valid := True // Drive write response valid high
      io.axi.b.payload.resp := Axi4.resp.OKAY // Set response to OKAY
      if (axiConfig.useId) io.axi.b.payload.id := writeIdReg // Drive the latched ID

      when(io.axi.b.fire) { // When B handshake occurs (valid & ready)
        goto(sWIdle) // Write transaction complete, return to idle state
      }
    }
  }

  // --- Read Channel Logic (using registered stages for timing) ---
  val readCmdBusy = RegInit(False) // Flag to indicate if the read channel is busy
  val readWordAddrReg = Reg(UInt(ramAddrWidth bits)).init(0) // Register to store word address for read operation
  val readIdReg = if (axiConfig.useId) Reg(UInt(axiConfig.idWidth bits)) else null // Register for AXI read ID
  val readEnablePipe =
    RegNext(io.axi.ar.fire) init (False) // AR fire signal delayed by 1 cycle, used for RAM read enable

  // AR Channel: Accept Read Address
  io.axi.ar.ready := !readCmdBusy // Ready to accept read address only when not busy
  when(io.axi.ar.fire) { // When AR handshake occurs (valid & ready)
    readCmdBusy := True // Set busy flag
    if (axiConfig.useId) readIdReg := io.axi.ar.payload.id // Latch the ID
    // Calculate and latch the word address in this cycle
    readWordAddrReg := io.axi.ar.payload.addr(axiAddrMsb downto axiAddrLsb)
    // Report accepted address (for simulation)
    // Note: readWordAddrReg reported here will show previous value due to register timing
    report(
      L"Slave Read Addr Accepted: AXI_Addr=${io.axi.ar.payload.addr} (Latched Word Addr for next cycle: ${io.axi.ar.payload
          .addr(axiAddrMsb downto axiAddrLsb)})\n"
    )
  }

  // Internal RAM Read Operation
  // Use the registered address (readWordAddrReg) and the delayed enable (readEnablePipe)
  // This ensures the address is stable one cycle before the RAM read is enabled.
  val ramReadPort = ram.readSync(
    address = readWordAddrReg, // Use word address latched in the previous cycle
    enable = readEnablePipe // Enable RAM read one cycle after AR handshake
  )

  // R Channel: Send Read Data
  // R.valid must be asserted one cycle after readEnablePipe (due to readSync latency)
  // This means R.valid is asserted two cycles after the initial AR handshake (ar.fire)
  io.axi.r.valid := RegNext(readEnablePipe) init (False)
  io.axi.r.payload.data := ramReadPort // Drive read data from RAM port
  io.axi.r.payload.resp := Axi4.resp.OKAY // Always OKAY response
  if (axiConfig.useLast) io.axi.r.payload.last := True // Assume single beat read (AR.LEN=0)
  if (axiConfig.useId) io.axi.r.payload.id := readIdReg // Drive the ID latched during AR phase

  // Clear busy flag when the read data transfer is complete
  when(io.axi.r.fire && io.axi.r.payload.last) {
    readCmdBusy := False
  }

  // Report read data when R channel is valid (for simulation)
  when(io.axi.r.valid) {
    report(L"Slave Read Data Valid: Word_Addr=${readWordAddrReg} Data=${io.axi.r.payload.data}")
  }
}
