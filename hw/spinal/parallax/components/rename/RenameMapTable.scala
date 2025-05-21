package parallax.components.rename

import spinal.core._
import spinal.lib._

case class RenameMapTableConfig(
    numArchRegs: Int = 32,
    physRegIdxWidth: BitCount = 6 bits,
    numReadPorts: Int = 2,
    numWritePorts: Int = 1, // -- MODIFICATION START (Add numWritePorts) --
    archGprIdxWidth: BitCount = log2Up(32) bits
)

// Bundle for a single read port
case class RatReadPort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val archReg = UInt(config.archGprIdxWidth) // Input to RAT from master's perspective
  val physReg = UInt(config.physRegIdxWidth) // Output from RAT from master's perspective

  override def asMaster(): Unit = {
    // When this Bundle is used as a master port (e.g., on the component requesting a read)
    // archReg is an output (driving the RAT's input)
    // physReg is an input (receiving from the RAT's output)
    out(archReg)
    in(physReg)
  }
  // asSlave() will be automatically inferred by SpinalHDL by flipping directions from asMaster()
}

// Bundle for a single write port
case class RatWritePort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val wen = Bool() // Input to RAT
  val archReg = UInt(config.archGprIdxWidth) // Input to RAT
  val physReg = UInt(config.physRegIdxWidth) // Input to RAT

  override def asMaster(): Unit = {
    // When this Bundle is used as a master port (e.g., on the component initiating a write)
    // all these signals are outputs.
    out(wen, archReg, physReg)
  }
}

// Bundle for checkpointing - This is a data structure, not typically a port itself.
// Its directionality is handled by the Stream that carries it.
// So, it usually does NOT need to implement IMasterSlave unless used directly as an IO port.
case class RatCheckpoint(config: RenameMapTableConfig) extends Bundle {
  val mapping = Vec(UInt(config.physRegIdxWidth), config.numArchRegs)
}

// The IO Bundle for RenameMapTable
case class RenameMapTableIo(config: RenameMapTableConfig) extends Bundle {
  // Read ports for source operands
  // slave(RatReadPort(config)) means the RenameMapTable component has slave read ports.
  // The signals inside RatReadPort will have their directions flipped from its asMaster() definition.
  // So, for the RAT:
  //   readPorts.archReg will be an INPUT
  //   readPorts.physReg will be an OUTPUT
  val readPorts = Vec(slave(RatReadPort(config)), config.numReadPorts)

  // Write port for destination operand mapping update
  // For the RAT:
  //   writePort.wen, writePort.archReg, writePort.physReg will all be INPUTS
  val writePort = slave(RatWritePort(config)) // -- MODIFICATION START (Rename to writePorts and make it a Vec) --
  val writePorts = Vec(slave(RatWritePort(config)), config.numWritePorts) // -- MODIFICATION END --

  // Checkpoint mechanism
  // For slave Stream, payload is an input, valid is an input, ready is an output.
  val checkpointSave = slave Stream (RatCheckpoint(config))
  val checkpointRestore = slave Stream (RatCheckpoint(config))
}

class RenameMapTable(val config: RenameMapTableConfig) extends Component {
  // Configuration parameter validation at elaboration time
  require(config.numArchRegs > 0, "Number of architectural registers must be positive.")
  require(config.physRegIdxWidth.value > 0, "Physical register index width must be positive.")
  require(config.numReadPorts > 0, "Number of read ports must be positive.")
  require(
    config.numWritePorts > 0,
    "Number of write ports must be positive."
  ) // -- MODIFICATION START (Add validation for numWritePorts) --
  require(
    config.archGprIdxWidth.value == log2Up(config.numArchRegs),
    s"archGprIdxWidth (${config.archGprIdxWidth.value}) must be log2Up(numArchRegs = ${config.numArchRegs}), which is ${log2Up(config.numArchRegs)}"
  ) // -- MODIFICATION END --

  val io = RenameMapTableIo(config)

  // -- MODIFICATION START (Rename mapState to mapReg and refactor write logic) --
  val mapReg = Reg(RatCheckpoint(config)) init (initRatCheckpoint())

  private def initRatCheckpoint(): RatCheckpoint = {
    val checkpoint = RatCheckpoint(config)
    // Validations ensure numArchRegs > 0 and physRegIdxWidth > 0
    for (i <- 0 until config.numArchRegs) {
      if (i == 0) { // Assuming r0 is always 0 and maps to physical register 0
        checkpoint.mapping(i) := U(0, config.physRegIdxWidth)
      } else {
        checkpoint.mapping(i) := U(i, config.physRegIdxWidth) // rX maps to pX initially
      }
    }
    checkpoint
  }

  // --- Read Logic ---
  for (i <- 0 until config.numReadPorts) {
    // r0 (architectural register 0) always returns physical register 0
    when(io.readPorts(i).archReg === U(0, config.archGprIdxWidth)) {
      io.readPorts(i).physReg := U(0, config.physRegIdxWidth)
    } otherwise {
      io.readPorts(i).physReg := mapReg.mapping(io.readPorts(i).archReg)
    }
  }

  // --- Write and Restore Logic ---
  // Create a combinational signal for the next state of the mapping, initially copying the current state
  val nextMapRegMapping = CombInit(mapReg.mapping)

  when(io.checkpointRestore.valid) {
    // Restore has highest priority
    nextMapRegMapping := io.checkpointRestore.payload.mapping
  } otherwise {
    // Apply updates from each write port.
    // Loop provides implicit priority: later ports in the loop (higher index 'i')
    // will overwrite earlier ports if they target the same architectural register.
    for (i <- 0 until config.numWritePorts) {
      when(io.writePorts(i).wen && io.writePorts(i).archReg =/= U(0, config.archGprIdxWidth)) {
        nextMapRegMapping(io.writePorts(i).archReg) := io.writePorts(i).physReg
      }
    }
  }
  // Assign the calculated next state to the actual register at the clock edge
  mapReg.mapping := nextMapRegMapping
  // -- MODIFICATION END --

  // --- Checkpoint Save IO ---
  io.checkpointSave.ready := True // RAT is always ready to have its state read for a save
  io.checkpointRestore.ready := True // RAT is always ready to restore
}
