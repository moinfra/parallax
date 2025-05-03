package boson.demo

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import boson.utilities._ // Assuming Plugin, DataBase, NaxScope, Framework are here
import scala.collection.mutable.ArrayBuffer
import spinal.lib.pipeline.Connection.DIRECT
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline.Connection.S2M
import spinal.core.fiber.Lock

// ==========================================================================
// == Existing Code (Slightly Adjusted for Clarity/Consistency) ==
// ==========================================================================

// --- DemoCPU Top Level ---
class DemoCPU(val plugins: Seq[Plugin]) extends Component with LockedImpl {
  val database = new DataBase
  // Run the framework's setup asynchronously (as in original code)
  val framework = NaxScope(database) on new Framework(plugins) // Will run the generation asynchronously
}

// --- Fetch Pipeline ---
class FetchPipeline extends Plugin with LockedImpl {
  // Define pipeline structure early
  val pipeline = create early new Pipeline {
    val fetch0 = newStage()
    val fetch1 = newStage()
    connect(fetch0, fetch1)(M2S())
    // Add an S2M stage for bridging to Frontend
    val s2m = newStage()
    connect(fetch1, s2m)(S2M()) // Use S2M for the bridge connection point
  }
  pipeline.setCompositeName(this)

  def entryStage = pipeline.fetch0
  def exitStage = pipeline.s2m // The S2M stage is the exit point for the bridge
}

object FetchPipelineData extends AreaObject {
  val PC = Stageable(UInt(32 bits))
  val INSTRUCTION = Stageable(Bits(32 bits))
  val FETCH_FAULT = Stageable(Bool())
}

// --- ROM ---
object DemoRom extends AreaObject {
  // Example DemoArch Instructions:
  // 0x00402085: addi.w $r5, $r0, 2   => 0000000 00010 00000 00101 00010100
  // 0x0000001C: mul.w  $r1, $r0, $r0 => 0000000 00000 00000 00001 00011100 (Example, encoding might differ)
  // 0xA0000000: lw.w   $r0, $r0, 0   => 101000 00000 00000 00000000000000 (Example)
  // 0xAC000000: sw.w   $r0, $r0, 0   => 101011 00000 00000 00000000000000 (Example)
  val rom = Mem(Bits(32 bits), 8) init (
    Seq(
      // B"0000000_00010_00000_00101_00010100", // addi.w $r5, $r0, 2
      B(0x00402085L, 32 bits), // addi.w $r5, $r0, 2 (Corrected encoding)
      B(0x0000001cL, 32 bits), // Placeholder for mul.w $r1, $r0, $r0
      B(0x28000000L, 32 bits), // Placeholder for lw.w $r0, $r0, 0
      B(0x2c000000L, 32 bits), // Placeholder for sw.w $r0, $r0, 0
      B(0x00000000L, 32 bits),
      B(0x00000000L, 32 bits),
      B(0x00000000L, 32 bits),
      B(0x00000000L, 32 bits)
    )
  )
}

// --- Fetch Stage Logic ---
class Fetch0Plugin extends Plugin with LockedImpl {
  val fetchPipeline = getService[FetchPipeline]

  val logic = create late new Area {
    val stage = fetchPipeline.pipeline.fetch0

    // Basic PC logic
    val pcReg = Reg(UInt(32 bits)) init (0)
    val pcPlus4 = pcReg + 4
    val pcUpdate = CombInit(pcPlus4) // Can be overridden by jumps/branches later
    val doJump = False // Placeholder for branch/jump logic
    when(!stage.isStuck) { // Only update PC if the stage is moving
      pcReg := pcUpdate
    }
    // TODO: Add branch/jump prediction and update logic here

    // Read ROM
    val romAddress = pcUpdate(log2Up(DemoRom.rom.wordCount) + 1 downto 2) // Assuming byte addressable, align to 4 bytes
    val instruction = DemoRom.rom.readAsync(romAddress) // Use current PC for read

    // Output to pipeline
    stage(FetchPipelineData.PC) := pcUpdate
    stage(FetchPipelineData.INSTRUCTION) := instruction
    stage(FetchPipelineData.FETCH_FAULT) := False // Default to no fault
    // Example: Halt pipeline if PC is misaligned (though ROM read handles alignment here)
    // stage.haltIt(pcUpdate(1 downto 0) =/= 0)
  }
}

class Fetch1Plugin extends Plugin with LockedImpl {
  val fetchPipeline = getService[FetchPipeline]

  val logic = create late new Area {
    val stage0 = fetchPipeline.pipeline.fetch0
    val stage1 = fetchPipeline.pipeline.fetch1
    // Simple pass-through for this stage, no icache currently
    stage1(FetchPipelineData.PC) := stage0(FetchPipelineData.PC)
    stage1(FetchPipelineData.INSTRUCTION) := stage0(FetchPipelineData.INSTRUCTION)
    stage1(FetchPipelineData.FETCH_FAULT) := stage0(FetchPipelineData.FETCH_FAULT)
  }
}

// --- Frontend Pipeline (Decode, Rename, Dispatch) ---
class FrontendPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val decode = newStage()
    // val rename = newStage() // Rename stage placeholder
    val dispatch = newStage()

    // connect(decode, rename)(M2S())
    // connect(rename, dispatch)(M2S())
    connect(decode, dispatch)(M2S()) // Simplified connection for now
  }
  pipeline.setCompositeName(this)

  def firstStage = pipeline.decode
  def exitStage = pipeline.dispatch
}

// --- Frontend Data Structures ---
// Define Micro-Operation Structure
// Based on DEMO32: ADD.W rd, rj, rk; MUL.W rd, rj, rk; LW.W rd, rj, imm; SW.W rk, rj, imm
// Need: OpType, DestReg, SourceReg1, SourceReg2, Immediate
// For simplicity, using physical register indices directly (no rename yet)
object OpType extends SpinalEnum {
  val NOP, ALU, MUL, LOAD, STORE = newElement()
}

// A simplified Micro-Operation (uOp) structure
case class MicroOp() extends Bundle {
  val opType = OpType()
  val rd = UInt(5 bits) // Destination register index
  val rj = UInt(5 bits) // Source register 1 index
  val rk = UInt(5 bits) // Source register 2 index / Store data source
  val imm = SInt(16 bits) // Immediate for LW/SW offset (adjust size as needed)
  val pc = UInt(32 bits) // Keep PC for debugging/exceptions
  val fault = Bool() // Carry faults forward
}

object FrontendPipelineData extends AreaObject {
  val INSTRUCTION = Stageable(Bits(32 bits)) // Comes from Fetch
  val PC = Stageable(UInt(32 bits)) // Comes from Fetch
  val FAULT = Stageable(Bool()) // Comes from Fetch
  val UOP = Stageable(MicroOp()) // Decoded Micro-Operation
}

// --- Decode Stage Plugin ---
class DecodePlugin extends Plugin with LockedImpl {
  val frontendPipeline = getService[FrontendPipeline]
  val fetchPipeline = getService[FetchPipeline]

  // Register signals needed from the input stage (Fetch's exit)
  val inputPc = fetchPipeline.exitStage(FetchPipelineData.PC)
  val inputInstruction = fetchPipeline.exitStage(FetchPipelineData.INSTRUCTION)
  val inputFault = fetchPipeline.exitStage(FetchPipelineData.FETCH_FAULT)

  val logic = create late new Area {
    val decodeStage = frontendPipeline.pipeline.decode
    import decodeStage._

    // Pass through basic info
    decodeStage(FrontendPipelineData.PC) := inputPc
    decodeStage(FrontendPipelineData.FAULT) := inputFault
    decodeStage(FrontendPipelineData.INSTRUCTION) := inputInstruction // For debug/inspection if needed

    // Basic Decoding Logic (Placeholder - very simplified)
    val uop = MicroOp() // Create a default uOp
    uop.pc := inputPc
    uop.fault := inputFault

    // DEMO32 ADD.W (opcode 0001100) rd[4:0] rk[9:5] rj[14:10]
    // DEMO32 MUL.W (opcode 0001110) rd[4:0] rk[9:5] rj[14:10]
    // DEMO32 LW.W  (opcode 0101000) rd[4:0] rj[9:5] imm[21:10]
    // DEMO32 SW.W  (opcode 0101011) rk[4:0] rj[9:5] imm[21:10]
    // DEMO32 ADDI.W(opcode 0001010) rd[4:0] rj[9:5] imm[21:10]

    val instruction = inputInstruction
    val opcode = instruction(31 downto 22) // Adjust based on actual DemoArch encoding
    val rd = instruction(4 downto 0).asUInt
    val rj_alu = instruction(14 downto 10).asUInt
    val rk_alu = instruction(9 downto 5).asUInt
    val rj_mem = instruction(9 downto 5).asUInt
    val rk_sw = instruction(4 downto 0).asUInt // SW uses rd field for rk
    val imm12 = instruction(21 downto 10) // For loads/stores/addi
    val imm16_lsb = instruction(25 downto 10) // For other immediate types if needed

    when(inputFault) {
      uop.opType := OpType.NOP // Treat fault as NOP for now
      uop.fault := True
    }.elsewhen(instruction === B(0)) { // Handle NOP explicitly
      uop.opType := OpType.NOP
    }.elsewhen(opcode(9 downto 3) === B"0001010") { // ADDI.W (Example partial match)
      uop.opType := OpType.ALU
      uop.rd := rd
      uop.rj := rj_mem // ADDI uses rj in this position
      uop.rk := 0 // rk not used directly by ADDI.W uOp
      uop.imm := S(imm12.asBits.resized) // Sign extend imm12
    }.elsewhen(opcode(9 downto 3) === B"0001100") { // ADD.W
      uop.opType := OpType.ALU
      uop.rd := rd
      uop.rj := rj_alu
      uop.rk := rk_alu
      uop.imm := 0
    }.elsewhen(opcode(9 downto 3) === B"0001110") { // MUL.W
      uop.opType := OpType.MUL
      uop.rd := rd
      uop.rj := rj_alu
      uop.rk := rk_alu
      uop.imm := 0
    }.elsewhen(opcode(9 downto 3) === B"0101000") { // LW.W
      uop.opType := OpType.LOAD
      uop.rd := rd
      uop.rj := rj_mem // Base register
      uop.rk := 0 // Not used
      uop.imm := S(imm12.asBits.resized) // Sign extend offset
    }.elsewhen(opcode(9 downto 3) === B"0101011") { // SW.W
      uop.opType := OpType.STORE
      uop.rd := 0 // Destination not used
      uop.rj := rj_mem // Base register
      uop.rk := rk_sw // Data source register (in rd field for SW)
      uop.imm := S(imm12.asBits.resized) // Sign extend offset
    }.otherwise {
      uop.opType := OpType.NOP // Unknown instruction
      uop.fault := True // Flag unsupported instruction as fault
    }

    // Assign the decoded uop to the pipeline stage
    decodeStage(FrontendPipelineData.UOP) := uop

    // TODO: Add stall logic if needed (e.g., waiting for rename resources)
  }
}

// --- Dispatch Stage Plugin ---
// For now, just passes the uOp through. In a real CPU, would interact with Issue Queues.
class DispatchPlugin extends Plugin with LockedImpl {
  val frontendPipeline = getService[FrontendPipeline]

  val logic = create late new Area {
    val decodeStage = frontendPipeline.pipeline.decode // Assuming decode -> dispatch connection
    val dispatchStage = frontendPipeline.pipeline.dispatch
    import dispatchStage._

    // Pass through data from Decode
    dispatchStage(FrontendPipelineData.PC) := decodeStage(FrontendPipelineData.PC)
    dispatchStage(FrontendPipelineData.FAULT) := decodeStage(FrontendPipelineData.FAULT)
    dispatchStage(FrontendPipelineData.UOP) := decodeStage(FrontendPipelineData.UOP)

    // TODO: Add logic to check resource availability (Issue Queues, ROB, Phys Regs) and stall if necessary.
    // TODO: Route UOP to the correct Issue Queue based on uop.opType.
  }
}

// --- Bridge between Fetch and Frontend ---
class FetchFrontendBridge extends Plugin {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val frontendPipeline = getService[FrontendPipeline]

    fetchPipeline.retain()
    frontendPipeline.retain()

    // s2m 阶段，不具备特殊逻辑，只适用于用于连接两个流水线
    val s2m = fetchPipeline.pipeline.newStage()
    fetchPipeline.pipeline.connect(fetchPipeline.pipeline.fetch1, s2m)(
      spinal.lib.pipeline.Connection.S2M()
    ) // 可以换成带缓冲的连接，如 QueueLowLatency

  }

  val logic = create late new Area {
    val inputStage = setup.fetchPipeline.exitStage
    val outputStage = setup.frontendPipeline.firstStage

    outputStage(FrontendPipelineData.INSTRUCTION) := inputStage(FetchPipelineData.INSTRUCTION)
    outputStage.valid := inputStage.valid
    inputStage.haltIt(!outputStage.isReady)
    inputStage.flushIt(outputStage.isFlushed, root = false)

    setup.fetchPipeline.release()
    setup.frontendPipeline.release()
  }
}

// ==========================================================================
// == Backend: Issue, Execute, Commit ==
// ==========================================================================

// --- Issue Queues ---
// Using simple FIFOs as placeholders for issue queues
class IssueQueues extends Plugin with LockedImpl {
  // Configuration
  val integerIssueDepth = 8
  val memoryIssueDepth = 8
  val muldivIssueDepth = 4

  // Create FIFOs late after FrontendPipeline is established
  val intIssueQueue = StreamFifo(MicroOp(), integerIssueDepth)
  val memIssueQueue = StreamFifo(MicroOp(), memoryIssueDepth)
  val mulIssueQueue = StreamFifo(MicroOp(), muldivIssueDepth)

  // Logic to connect Dispatch to Issue Queues
  val dispatchLogic = create late new Area {
    val frontendPipeline = getService[FrontendPipeline]
    val dispatchStage = frontendPipeline.exitStage // Dispatch stage

    // Default assignments
    intIssueQueue.io.push.valid := False
    intIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)
    memIssueQueue.io.push.valid := False
    memIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)
    mulIssueQueue.io.push.valid := False
    mulIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)

    // Route based on OpType when dispatch is valid and ready to accept
    when(dispatchStage.isFireing) { // isFiring means valid && !isStalled (or ready from receiver's perspective)
      val uop = dispatchStage(FrontendPipelineData.UOP)
      switch(uop.opType) {
        is(OpType.ALU) {
          intIssueQueue.io.push.valid := True
          // Stall dispatch if Int queue is full
          when(!intIssueQueue.io.push.ready) {
            dispatchStage.haltIt()
          }
        }
        is(OpType.LOAD, OpType.STORE) {
          memIssueQueue.io.push.valid := True
          // Stall dispatch if Mem queue is full
          when(!memIssueQueue.io.push.ready) {
            dispatchStage.haltIt()
          }
        }
        is(OpType.MUL) {
          mulIssueQueue.io.push.valid := True
          // Stall dispatch if Mul queue is full
          when(!mulIssueQueue.io.push.ready) {
            dispatchStage.haltIt()
          }
        }
        default { // NOPs or others just pass through dispatch without issuing
        }
      }
    }.otherwise {
      // If dispatch stage is not firing, don't push to issue queues
    }
  }
}

// --- Execution Unit Data ---
// Common data structures for execution pipelines
object ExecutionUnitData extends AreaObject {
  // Data coming INTO the execution pipeline (from Issue)
  val UOP = Stageable(MicroOp())

  // Data generated WITHIN the execution pipeline
  val ALU_RESULT = Stageable(Bits(32 bits))
  val MEM_ADDRESS = Stageable(UInt(32 bits))
  val MEM_DATA_WRITE = Stageable(Bits(32 bits)) // Data for Stores
  val MEM_DATA_READ = Stageable(Bits(32 bits)) // Data from Loads
  val MUL_RESULT = Stageable(Bits(32 bits)) // Assuming 32-bit result for MUL.W

  // Information needed for Writeback/Commit
  val WRITEBACK_VALUE = Stageable(Bits(32 bits))
  val WRITEBACK_DEST = Stageable(UInt(5 bits)) // Destination physical register
  val WRITEBACK_ENABLE = Stageable(Bool()) // Does this uOp write back?
}

// --- Base class for Execution Pipelines ---
// Simplifies defining common stages like RR, EX, WB
abstract class ExecutionPipeline(val pipelineName: String) extends Plugin with LockedImpl {
  // Define the pipeline structure
  val pipeline = create early new Pipeline {
    // Common stages for many execution units
    val RR = newStage() // Read Registers
    val EX = newStage() // Execute
    // Memory pipeline might insert stages here (AGU, CacheAccess)
    val WB = newStage() // Write Back (to ROB / Physical Register File)
    val stages = List(RR, EX, WB) // List of all stages for convenience
    // Default simple connection
    connect(RR, EX)(M2S())
    connect(EX, WB)(M2S())
  }
  pipeline.setCompositeName(this, pipelineName)

  // References to be populated by concrete implementations
  var issueQueueInput: Stream[MicroOp] = null // Input stream from issue queue
  var writebackOutput: Stream[Fragment[CommitEntry]] = null // Output stream to Commit/Writeback stage

  // Helper AreaObject for data specific to this pipeline instance if needed
  val pipeData = new AreaObject {}

  // Common logic to connect issue queue to RR stage
  val issueConnection = create late new Area {
    if (issueQueueInput != null) {
      val rrStage = pipeline.RR
      // Connect stream to stage valid/ready
      rrStage.valid := issueQueueInput.valid
      issueQueueInput.ready := rrStage.isReady

      rrStage(ExecutionUnitData.UOP) := issueQueueInput.payload
    } else {
      SpinalError(s"Issue Queue Input not set for pipeline $pipelineName")
    }
  }

  // Common logic to connect WB stage to writeback output
  val writebackConnection = create late new Area {
    if (writebackOutput != null) {
      val wbStage = pipeline.stages.last // Assuming WB is last
      // Connect stage valid/ready to stream
      writebackOutput.valid := wbStage.valid
      wbStage.isReady := writebackOutput.ready
      writebackOutput.payload.fragment := ??? // Needs implementation in subclass
      writebackOutput.payload.last := True // Assuming single beat commit entry for now
    } else {
      SpinalWarning(s"Writeback Output not set for pipeline $pipelineName. Results will be dropped.")
      // Ensure the pipeline doesn't stall forever if WB isn't connected
      pipeline.stages.last.haltIt(False) // Don't stall waiting for non-existent receiver
    }
  }

  // Placeholder for physical register file access (would be a separate service/plugin)
  val regFile = getServiceOption[RegisterFilePlugin] match {
    case Some(rf) => rf
    case None     => null // Handle case where RF plugin isn't present
  }

  // --- RR Stage Logic (Common Part) ---
  val rrLogic = create late new Area {
    val rrStage = pipeline.RR
    import rrStage._
    val uop = rrStage(ExecutionUnitData.UOP)

    // Placeholder: Read register values based on uop.rj, uop.rk
    // Actual implementation requires interacting with a RegisterFilePlugin
    // For framework, just pass the uop through
    pipeline.EX(ExecutionUnitData.UOP) := uop

    // TODO: Implement actual register read logic using regFile.read(uop.rj) etc.
    // TODO: Implement forwarding logic
  }

  // --- WB Stage Logic (Common Part) ---
  val wbLogic = create late new Area {
    val exStage = pipeline.EX
    val wbStage = pipeline.WB
    import wbStage._

    // Pass through data needed for writeback from EX stage
    wbStage(ExecutionUnitData.UOP) := exStage(ExecutionUnitData.UOP) // Pass uOp for context
    wbStage(ExecutionUnitData.WRITEBACK_VALUE) := exStage(ExecutionUnitData.WRITEBACK_VALUE)
    wbStage(ExecutionUnitData.WRITEBACK_DEST) := exStage(ExecutionUnitData.WRITEBACK_DEST)
    wbStage(ExecutionUnitData.WRITEBACK_ENABLE) := exStage(ExecutionUnitData.WRITEBACK_ENABLE)

    // TODO: Write to Physical Register File (using regFile.write(...)) if not handled by Commit
    // TODO: Write to ROB (requires ROB plugin)
  }

}

// --- ALU Execution Unit Plugin ---
class AluExecutionUnit(val id: Int) extends ExecutionPipeline(s"ALU_Unit_$id") {
  // Connect to Integer Issue Queue
  val issueSetup = create early new Area {
    val queues = getService[IssueQueues]
    // TODO: Add arbitration if multiple ALUs share the same queue output
    // For now, assume separate issue logic or only one ALU connects directly
    if (id == 0) { // Example: only ALU 0 connects for simplicity
      issueQueueInput = queues.intIssueQueue.io.pop // Connect pop side of FIFO stream
    } else {
      // Make ALU 1 consume from a different point or add arbitration logic
      // For now, leave ALU 1 disconnected from issue queue
      issueQueueInput = Stream(MicroOp()) // Dummy stream
      issueQueueInput.valid := False
      issueQueueInput.payload.assignDontCare()
    }

    // Connect to Writeback/Commit stage
    val commit = getService[CommitPlugin]
    writebackOutput = commit.commitInputPipe // Connect to the commit stage's input
  }

  // --- EX Stage Logic (ALU Specific) ---
  val exLogic = create late new Area {
    val rrStage = pipeline.RR
    val exStage = pipeline.EX
    import exStage._

    val uop = rrStage(ExecutionUnitData.UOP)
    val rs1_value = Bits(32 bits).assignDontCare() // Placeholder for RegFile read result
    val rs2_value = Bits(32 bits).assignDontCare() // Placeholder for RegFile read result
    val imm_value = uop.imm.asBits.resize(32)

    // Basic ALU operation based on simplified uOp
    val result = Bits(32 bits)
    // Placeholder logic: Assumes opType = ALU implies ADD.W or ADDI.W based on imm presence for this simple example
    // A real implementation needs a more detailed opcode within the MicroOp.
    when(uop.imm =/= 0) { // Crude check for ADDI.W based on immediate
      result := (rs1_value.asSInt + imm_value.asSInt).asBits
    }.otherwise { // Assume ADD.W
      result := (rs1_value.asSInt + rs2_value.asSInt).asBits
    }

    // Set writeback data
    exStage(ExecutionUnitData.WRITEBACK_VALUE) := result
    exStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
    exStage(
      ExecutionUnitData.WRITEBACK_ENABLE
    ) := (uop.opType === OpType.ALU && uop.rd =/= 0) // Write only if dest is not R0

    // Pass through UOP for WB/Commit stage context
    exStage(ExecutionUnitData.UOP) := uop
  }

  // --- WB Stage Logic (ALU Specific) ---
  val aluWbLogic = create late new Area {
    // Populate the commit entry payload in the writebackConnection logic
    if (writebackOutput != null) {
      val wbStage = pipeline.WB
      val commitEntry = CommitEntry()
      commitEntry.uop := wbStage(ExecutionUnitData.UOP) // Pass full uop for commit info
      commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
      commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
      commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
      commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault // Propagate fault

      writebackOutput.payload.fragment := commitEntry
    }
  }
}

// --- Memory Execution Unit Plugin ---
class MemoryExecutionUnit extends Plugin with LockedImpl {
  // Memory pipeline needs more stages: RR, AGU, LoadStore, WB
  val pipeline = create early new Pipeline {
    val RR = newStage() // Read Registers (Base address, Store data)
    val AGU = newStage() // Address Generation (Base + Offset)
    val LS = newStage() // Load/Store Access (Interface with Cache/Memory)
    val WB = newStage() // Write Back result (for Loads)

    connect(RR, AGU)(M2S())
    connect(AGU, LS)(M2S())
    connect(LS, WB)(M2S())
  }
  pipeline.setCompositeName(this, "MEM_Unit")

  var issueQueueInput: Stream[MicroOp] = null
  var writebackOutput: Stream[Fragment[CommitEntry]] = null

  // Connect to Memory Issue Queue and Commit
  val connections = create early new Area {
    val queues = getService[IssueQueues]
    issueQueueInput = queues.memIssueQueue.io.pop

    val commit = getService[CommitPlugin]
    writebackOutput = commit.commitInputPipe
  }

  // Connect Issue Queue -> RR
  val issueConnection = create late new Area {
    val rrStage = pipeline.RR
    rrStage.valid := issueQueueInput.valid
    issueQueueInput.ready := rrStage.isReady

    rrStage(ExecutionUnitData.UOP) := issueQueueInput.payload
  }

  // Connect WB -> Commit
  val writebackConnectionLogic = create late new Area {
    if (writebackOutput != null) {
      val wbStage = pipeline.WB
      writebackOutput.valid := wbStage.valid
      wbStage.isReady := writebackOutput.ready

      // Create and populate commit entry
      val commitEntry = CommitEntry()
      commitEntry.uop := wbStage(ExecutionUnitData.UOP)
      commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
      commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
      commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
      commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault // Propagate fault

      writebackOutput.payload.fragment := commitEntry
      writebackOutput.payload.last := True
    } else {
      pipeline.WB.haltIt(False) // Don't stall if not connected
    }
  }

  // --- RR Stage (Memory Specific) ---
  val rrLogic = create late new Area {
    val rrStage = pipeline.RR
    import rrStage._
    val uop = rrStage(ExecutionUnitData.UOP)

    // Placeholder: Read Base Register (rj) and Store Data Register (rk for SW)
    val baseAddrRegVal = Bits(32 bits).assignDontCare()
    val storeDataRegVal = Bits(32 bits).assignDontCare()
    // TODO: Actual RegFile read + Forwarding

    pipeline.AGU(ExecutionUnitData.UOP) := uop
    pipeline.AGU(ExecutionUnitData.MEM_ADDRESS) := baseAddrRegVal.asUInt // Pass base address value
    pipeline.AGU(ExecutionUnitData.MEM_DATA_WRITE) := storeDataRegVal // Pass store data value
  }

  // --- AGU Stage ---
  val aguLogic = create late new Area {
    val rrStage = pipeline.RR
    val aguStage = pipeline.AGU
    import aguStage._

    val uop = aguStage(ExecutionUnitData.UOP)
    val baseAddress = aguStage(ExecutionUnitData.MEM_ADDRESS)
    val offset = uop.imm

    // Calculate final address
    val effectiveAddress = (baseAddress.asSInt + offset).asUInt
    // TODO: Add TLB lookup, address translation, exception checks (alignment, permissions)

    pipeline.LS(ExecutionUnitData.UOP) := uop
    pipeline.LS(ExecutionUnitData.MEM_ADDRESS) := effectiveAddress
    pipeline.LS(ExecutionUnitData.MEM_DATA_WRITE) := aguStage(ExecutionUnitData.MEM_DATA_WRITE) // Pass store data
  }

  // --- LS Stage (Load/Store Access) ---
  val lsLogic = create late new Area {
    val aguStage = pipeline.AGU
    val lsStage = pipeline.LS
    import lsStage._

    val uop = lsStage(ExecutionUnitData.UOP)
    val address = lsStage(ExecutionUnitData.MEM_ADDRESS)
    val dataToWrite = lsStage(ExecutionUnitData.MEM_DATA_WRITE)

    // Placeholder for Memory Interaction
    // Needs connection to a Cache Plugin or Memory Interface
    val readData = Bits(32 bits).assignDontCare()
    val memFault = False
    val memAcknowledge = True // Assume memory access completes in one cycle for now

    // TODO: Implement D-Cache access logic (request, wait, response)
    // TODO: Handle Cache hits/misses, stalls
    // TODO: Handle Store buffer interaction (stores might wait until commit)
    // TODO: Connect to external memory bus interface

    // Example: Simple synchronous read/write (highly unrealistic)
    when(isValid && uop.opType === OpType.LOAD) {
      // readData := memory.readSync(address) // Example conceptual read
    }
    when(isValid && uop.opType === OpType.STORE) {
      // memory.write(address, dataToWrite) // Example conceptual write
    }

    // Stall LS stage if memory access is not complete
    haltIt(!memAcknowledge)

    // Propagate data to WB stage
    pipeline.WB(ExecutionUnitData.UOP) := uop
    pipeline.WB(ExecutionUnitData.MEM_DATA_READ) := readData // Pass loaded data
    // Update fault status if memory access failed
    pipeline.WB(ExecutionUnitData.UOP).fault := uop.fault || memFault
  }

  // --- WB Stage (Memory Specific) ---
  val memWbLogic = create late new Area {
    val lsStage = pipeline.LS
    val wbStage = pipeline.WB
    import wbStage._

    val uop = wbStage(ExecutionUnitData.UOP)

    // Setup Writeback data ONLY for LOAD operations
    val writeEnable = (uop.opType === OpType.LOAD && uop.rd =/= 0 && !uop.fault)
    wbStage(ExecutionUnitData.WRITEBACK_VALUE) := wbStage(ExecutionUnitData.MEM_DATA_READ)
    wbStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
    wbStage(ExecutionUnitData.WRITEBACK_ENABLE) := writeEnable

    // Stores do not write back to the register file in this stage
    // They are committed to memory (potentially from a store buffer) later.

    // TODO: Write Load results to Physical Register File if not handled by Commit stage
    // TODO: Signal ROB entry completion
  }
}

// --- Multiply/Divide Execution Unit Plugin ---
// Simplified: Only implements MUL.W for now
class MulDivExecutionUnit extends ExecutionPipeline("MulDiv_Unit") {

  // Connect to Mul/Div Issue Queue
  val issueSetup = create early new Area {
    val queues = getService[IssueQueues]
    issueQueueInput = queues.mulIssueQueue.io.pop

    val commit = getService[CommitPlugin]
    writebackOutput = commit.commitInputPipe
  }

  // --- EX Stage Logic (Mul Specific) ---
  val exLogic = create late new Area {
    val rrStage = pipeline.RR
    val exStage = pipeline.EX
    import exStage._

    val uop = rrStage(ExecutionUnitData.UOP)
    val rs1_value = Bits(32 bits).assignDontCare() // Placeholder for RegFile read rj
    val rs2_value = Bits(32 bits).assignDontCare() // Placeholder for RegFile read rk

    // Basic MUL.W operation
    // TODO: Implement multi-cycle multiplication if needed
    val result = (rs1_value.asSInt * rs2_value.asSInt).resize(32).asBits // Signed multiplication, take lower 32 bits

    // Set writeback data
    exStage(ExecutionUnitData.WRITEBACK_VALUE) := result
    exStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
    exStage(ExecutionUnitData.WRITEBACK_ENABLE) := (uop.opType === OpType.MUL && uop.rd =/= 0 && !uop.fault)

    exStage(ExecutionUnitData.UOP) := uop // Pass uop context
  }

  // --- WB Stage Logic (Mul Specific) ---
  val mulWbLogic = create late new Area {
    // Populate the commit entry payload
    // writebackConnection.setup {
    if (writebackOutput != null) {
      val wbStage = pipeline.WB
      val commitEntry = CommitEntry()
      commitEntry.uop := wbStage(ExecutionUnitData.UOP)
      commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
      commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
      commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
      commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault

      writebackOutput.payload.fragment := commitEntry
    }
    // }
  }
}

// --- Commit Stage ---
// Represents the final stage where instructions are retired in-order.
// Needs to interact with ROB, Register Files, handle exceptions, etc.
// For this framework, it's primarily a sink for results from execution units.

// Data structure for entries going into Commit
case class CommitEntry() extends Bundle {
  val uop = MicroOp() // Contains original instruction info, PC, etc.
  val writebackValue = Bits(32 bits)
  val writebackDest = UInt(5 bits)
  val writebackEnable = Bool()
  val fault = Bool()
  // Add fields for ROB index, etc. if implementing a ROB
}

class CommitPlugin extends Plugin with LockedImpl {
  // Input stream combining results from all execution pipelines
  // Using StreamArbiterFactory for combining multiple streams into one
  val arbiter =
    StreamArbiterFactory.roundRobin.build(Fragment(CommitEntry()), 3) // Arity = num execution pipelines feeding commit

  // Define the input stream interface for execution units to connect to
  val commitInputPipe: Stream[Fragment[CommitEntry]] = arbiter.io.inputs(0) // Placeholder, will connect properly below

  val connections = create late new Area {
    val alu0 = findService[AluExecutionUnit](_.id == 0) // Find ALU 0
    val mem = getService[MemoryExecutionUnit]
    val mul = getService[MulDivExecutionUnit]
    // val alu1 = getService[AluExecutionUnit](_.id == 1) // If using second ALU

    // Connect execution unit writeback outputs to the arbiter inputs
    arbiter.io.inputs(0) << alu0.writebackOutput
    arbiter.io.inputs(1) << mem.writebackOutput
    arbiter.io.inputs(2) << mul.writebackOutput
    // arbiter.io.inputs(3) << alu1.writebackOutput // If using second ALU

  }

  val commitLogic = create late new Area {
    val inputCommitStream = arbiter.io.output // The arbitrated stream of commit entries

    // Consume the commit stream
    inputCommitStream.ready := True // Always ready to accept for now (no backpressure)

    when(inputCommitStream.fire) { // When a commit entry is valid and ready
      val commitData = inputCommitStream.payload.fragment

      // TODO: Interact with ROB: Mark instruction as completed
      // TODO: Interact with Physical Register File: Write back results if enabled
      // TODO: Interact with Architectural Register File / Rename Map: Free physical registers, update mapping
      // TODO: Handle exceptions/faults reported in commitData.fault
      // TODO: Handle stores (signal Store Buffer to write to memory)
      // TODO: Flush pipeline on misprediction or exception

      // Example: Print commit information (for simulation/debug)
      report(
        Seq(
          L"COMMIT: PC=0x${commitData.uop.pc}, OP=${commitData.uop.opType}, ",
          L"RD=${commitData.writebackDest}, WEn=${commitData.writebackEnable}, ",
          L"WVal=0x${commitData.writebackValue}, Fault=${commitData.fault}\n"
        )
      )
    }
  }
}

// --- Placeholder for Register File ---
// In a real design, this would manage physical registers, read/write ports, and forwarding
class RegisterFilePlugin extends Plugin with LockedImpl {
  // Configuration
  val registerCount = 32 // DemoArch has 32 GPRs
  val readPorts = 6 // Example: 2 per ALU, 1 for Mem base, 1 for Mem data (SW)
  val writePorts = 3 // Example: 1 per pipeline writing back (ALU, Mem Load, Mul)

  // TODO: Implement register file storage (RegFile)
  // TODO: Implement read ports logic
  // TODO: Implement write ports logic (arbitration needed)
  // TODO: Implement forwarding network logic

  def readAsync(address: UInt): Bits = {
    // Placeholder: Return dummy data
    B(0, 32 bits)
  }

  def write(address: UInt, data: Bits, enable: Bool): Unit = {
    // Placeholder: Logic to handle write requests
  }
}

// ==========================================================================
// == Main CPU Instantiation ==
// ==========================================================================
object DemoCpuGen extends App {
  SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generateVerilog(
    new DemoCPU(
      plugins = Seq(
        // 1. Pipelines & Bridges
        new FetchPipeline,
        new FrontendPipeline,
        new FetchFrontendBridge, // Connects Fetch -> Frontend

        // 2. Fetch Stages
        new Fetch0Plugin,
        new Fetch1Plugin,

        // 3. Frontend Stages
        new DecodePlugin,
        new DispatchPlugin,

        // 4. Backend Infrastructure
        new IssueQueues,
        new RegisterFilePlugin, // Register file needed by execution units
        new CommitPlugin, // Commit stage (collects results)

        // 5. Execution Units (Connect Issue -> Execute -> Commit)
        new AluExecutionUnit(id = 0), // First ALU unit
        new AluExecutionUnit(id = 1), // Second ALU unit (optional, needs connection logic)
        new MemoryExecutionUnit,
        new MulDivExecutionUnit

        // TODO: Add other necessary plugins (CSR handling, Interrupt Controller, etc.)
      )
    )
  )
}

object DemoCPUGenSim extends App {
  import spinal.core.sim._

  SimConfig.withWave
    .compile(
      new Demoware(
        Seq(
          // 1. Pipelines & Bridges
          new FetchPipeline,
          new FrontendPipeline,
          new FetchFrontendBridge, // Connects Fetch -> Frontend

          // 2. Fetch Stages
          new Fetch0Plugin,
          new Fetch1Plugin,

          // 3. Frontend Stages
          new DecodePlugin,
          new DispatchPlugin,

          // 4. Backend Infrastructure
          new IssueQueues,
          new RegisterFilePlugin, // Register file needed by execution units
          new CommitPlugin, // Commit stage (collects results)

          // 5. Execution Units (Connect Issue -> Execute -> Commit)
          new AluExecutionUnit(id = 0), // First ALU unit
          new AluExecutionUnit(id = 1), // Second ALU unit (optional, needs connection logic)
          new MemoryExecutionUnit,
          new MulDivExecutionUnit
        )
      )
    )
    .doSim(10 * 10) { dut =>
      dut.clockDomain.forkStimulus(period = 10) // 10ns clock period
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
    }
}
