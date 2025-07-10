package parallax.components.rename

import spinal.core._
import spinal.lib._

case class RenameMapTableConfig(
    archRegCount: Int = 32,
    physRegCount: Int = 32,
    numReadPorts: Int = 2,
    numWritePorts: Int = 1
) {
  def physRegIdxWidth: BitCount = log2Up(physRegCount) bits
  def archRegIdxWidth: BitCount = log2Up(archRegCount) bits
}

// Bundle for a single read port
case class RatReadPort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val archReg = UInt(config.archRegIdxWidth) // Input to RAT from master's perspective
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
  val archReg = UInt(config.archRegIdxWidth) // Input to RAT
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
  // arch -> phys mapping
  val mapping = Vec(UInt(config.physRegIdxWidth), config.archRegCount)
}

// The IO Bundle for RenameMapTable
case class RenameMapTableIo(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  // Read ports for source operands
  val readPorts = Vec(slave(RatReadPort(config)), config.numReadPorts)

  // Write port for destination operand mapping update
  val writePorts = Vec(slave(RatWritePort(config)), config.numWritePorts)

  // Read-only port for monitoring current internal state (for external checkpoint management)
  val currentState = RatCheckpoint(config)

  // Checkpoint restore mechanism (external manager provides state to restore)
  val checkpointRestore = slave Stream (RatCheckpoint(config))
  
  // BACKWARD COMPATIBILITY: Deprecated save port for existing tests
  val checkpointSave = slave Stream (RatCheckpoint(config))

  override def asMaster(): Unit = {
    readPorts.foreach(master(_))
    writePorts.foreach(master(_))
    
    // currentState: From caller's perspective, receives current state from RAT
    in(currentState)
    
    // checkpointRestore: External manager initiates restore by providing state
    master(checkpointRestore)
    
    // BACKWARD COMPATIBILITY: checkpointSave (deprecated)
    master(checkpointSave)
  }
}

class RenameMapTable(val config: RenameMapTableConfig) extends Component {
  // Configuration parameter validation at elaboration time
  require(config.archRegCount > 0, "Number of architectural registers must be positive.")
  require(config.physRegIdxWidth.value > 0, "Physical register index width must be positive.")
  require(config.numReadPorts > 0, "Number of read ports must be positive.")
  require(
    config.numWritePorts > 0,
    "Number of write ports must be positive."
  )

  require(
    config.archRegIdxWidth.value == log2Up(config.archRegCount),
    s"archRegIdxWidth (${config.archRegIdxWidth.value}) must be log2Up(archRegCount = ${config.archRegCount}), which is ${log2Up(config.archRegCount)}"
  )

  val io = slave(RenameMapTableIo(config))

  val mapReg = Reg(RatCheckpoint(config)) init (initRatCheckpoint())

  private def initRatCheckpoint(): RatCheckpoint = {
    val checkpoint = RatCheckpoint(config)
    // Validations ensure archRegCount > 0 and physRegIdxWidth > 0
    for (i <- 0 until config.archRegCount) {
      if (i == 0) { // Assuming r0 is always 0 and maps to physical register 0
        checkpoint.mapping(i) := U(0, config.physRegIdxWidth)
      } else {
        checkpoint.mapping(i) := U(i, config.physRegIdxWidth) // rX maps to pX initially
      }
    }
    checkpoint
  }
  val nextMapRegMapping = CombInit(mapReg.mapping)

  // --- Read Logic ---
  for (i <- 0 until config.numReadPorts) {
    // r0 (architectural register 0) always returns physical register 0
    when(io.readPorts(i).archReg === U(0, config.archRegIdxWidth)) {
      io.readPorts(i).physReg := U(0, config.physRegIdxWidth)
    } otherwise {
      io.readPorts(i).physReg := mapReg.mapping(io.readPorts(i).archReg)
    }
  }

  // --- Write and Restore Logic ---
  // Create a combinational signal for the next state of the mapping, initially copying the current state

  when(io.checkpointRestore.valid) {
    // Restore has highest priority
    nextMapRegMapping := io.checkpointRestore.payload.mapping
  } otherwise {
    // Apply updates from each write port.
    // Loop provides implicit priority: later ports in the loop (higher index 'i')
    // will overwrite earlier ports if they target the same architectural register.
    for (i <- 0 until config.numWritePorts) {
      when(io.writePorts(i).wen && io.writePorts(i).archReg =/= U(0, config.archRegIdxWidth)) {
        nextMapRegMapping(io.writePorts(i).archReg) := io.writePorts(i).physReg
      }
    }
  }
  // Assign the calculated next state to the actual register at the clock edge
  mapReg.mapping := nextMapRegMapping

  // --- Checkpoint Restore Logic ---
  io.checkpointRestore.ready := True // RAT is always ready to restore
  
  // --- BACKWARD COMPATIBILITY: Checkpoint Save (deprecated) ---
  io.checkpointSave.ready := True // Always ready but ignored

  // --- Current State Output (Read-only monitoring port) ---
  io.currentState.mapping := mapReg.mapping
}
