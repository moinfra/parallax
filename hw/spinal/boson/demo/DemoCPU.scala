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
import spinal.lib.StreamArbiterFactory
import spinal.lib.Fragment // Added for CommitEntry stream
import spinal.core.sim._ // Added for SimConfig
import spinal.sim.SimThread // Added for SimThread potentially

// ==========================================================================
// == Existing Code (Corrected based on feedback) ==
// ==========================================================================

// --- DemoCPU Top Level ---
class DemoCPU(val plugins: Seq[Plugin]) extends Component with LockedImpl {
  val database = new DataBase
  val framework = NaxScope(database) on new Framework(plugins)
}

// --- Fetch Pipeline ---
class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val fetch0 = newStage()
    val fetch1 = newStage()
    connect(fetch0, fetch1)(M2S())
  }
  pipeline.setCompositeName(this)

  def entryStage = pipeline.fetch0
  def exitStage = pipeline.fetch1 // Bridge connects from fetch1
}

object FetchPipelineData extends AreaObject {
  val PC = Stageable(UInt(32 bits))
  val INSTRUCTION = Stageable(Bits(32 bits))
  val FETCH_FAULT = Stageable(Bool())
}

// --- ROM ---
class DemoRom extends AreaObject {
  // Instructions corrected to align with DecodePlugin logic
  val rom = Mem(Bits(32 bits), 8) init (
    Seq(
      B(0x00402085L, 32 bits), // addi.w $r5, $r0, 2 (User provided)
      B(0x0e000001L, 32 bits), // mul.w  $r1, $r0, $r0 (Synthesized)
      B(0x14000000L, 32 bits), // lw.w   $r0, 0($r0)  (Synthesized)
      B(0x16800000L, 32 bits), // sw.w   $r0, 0($r0)  (Synthesized)
      B(0x00000000L, 32 bits), // NOP
      B(0x00000000L, 32 bits), // NOP
      B(0x00000000L, 32 bits), // NOP
      B(0x00000000L, 32 bits) // NOP
    )
  )
}

// --- Fetch Stage Logic ---
class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val stage = setup.fetchPipeline.pipeline.fetch0
    val pcReg = Reg(UInt(32 bits)) init (0)
    val pcPlus4 = pcReg + 4
    val pcUpdate = CombInit(pcPlus4)
    // Use when, not if, for run-time conditions
    when(!stage.isStuck) { pcReg := pcUpdate }
    val rom = (new DemoRom).rom
    val romAddress = pcReg(log2Up(rom.wordCount) + 1 downto 2)
    val instruction = rom.readAsync(romAddress)

    stage(FetchPipelineData.PC) := pcReg
    stage(FetchPipelineData.INSTRUCTION) := instruction
    stage(FetchPipelineData.FETCH_FAULT) := False
  }
}

class Fetch1Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }
  val logic = create late new Area {
    // No logic needed, M2S handles pass-through
  }
}

// --- Frontend Pipeline (Decode, Rename, Dispatch) ---
class FrontendPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val decode = newStage()
    val dispatch = newStage()
    connect(decode, dispatch)(M2S())
  }
  pipeline.setCompositeName(this)

  def firstStage = pipeline.decode
  def exitStage = pipeline.dispatch
}

// --- Frontend Data Structures ---
object OpType extends SpinalEnum {
  val NOP, ALU, MUL, LOAD, STORE = newElement()
}

case class MicroOp() extends Bundle {
  val opType = OpType()
  val rd = UInt(5 bits)
  val rj = UInt(5 bits)
  val rk = UInt(5 bits)
  val imm = SInt(16 bits)
  val pc = UInt(32 bits)
  val fault = Bool()
}

object FrontendPipelineData extends AreaObject {
  val INSTRUCTION = Stageable(Bits(32 bits))
  val PC = Stageable(UInt(32 bits))
  val FAULT = Stageable(Bool())
  val UOP = Stageable(MicroOp())
}

// --- Decode Stage Plugin ---
class DecodePlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }

  val logic = create late new Area {
    val decodeStage = setup.frontendPipeline.pipeline.decode
    import decodeStage._

    val inputPc = decodeStage(FrontendPipelineData.PC)
    val inputInstruction = decodeStage(FrontendPipelineData.INSTRUCTION)
    val inputFault = decodeStage(FrontendPipelineData.FAULT)

    val uop = MicroOp().assignDontCare()
    uop.pc := inputPc
    uop.fault := inputFault

    val instruction = inputInstruction
    val opcode = instruction(31 downto 22)
    val rd = instruction(4 downto 0).asUInt
    val rj_r_type = instruction(14 downto 10).asUInt
    val rk_r_type = instruction(9 downto 5).asUInt
    val rj_i_s_type = instruction(9 downto 5).asUInt
    val rk_s_type = instruction(4 downto 0).asUInt
    val imm12 = instruction(21 downto 10)

    // Use when for run-time signal 'inputFault'
    when(inputFault) {
      uop.opType := OpType.NOP
      uop.fault := True
    } otherwise { // Chain other conditions under the else part
      when(instruction === 0) {
        uop.opType := OpType.NOP
        uop.fault := False // NOP is not a fault
      }.elsewhen(opcode === B"0001010000") { // ADDI.W
        uop.opType := OpType.ALU
        uop.rd := rd
        uop.rj := rj_i_s_type
        uop.rk := 0
        uop.imm := S(imm12.asBits.resize(16))
      }.elsewhen(opcode === B"0001100000") { // ADD.W
        uop.opType := OpType.ALU
        uop.rd := rd
        uop.rj := rj_r_type
        uop.rk := rk_r_type
        uop.imm := 0
      }.elsewhen(opcode === B"0001110000") { // MUL.W
        uop.opType := OpType.MUL
        uop.rd := rd
        uop.rj := rj_r_type
        uop.rk := rk_r_type
        uop.imm := 0
      }.elsewhen(opcode === B"0101000000") { // LW.W
        uop.opType := OpType.LOAD
        uop.rd := rd
        uop.rj := rj_i_s_type
        uop.rk := 0
        uop.imm := S(imm12.asBits.resize(16))
      }.elsewhen(opcode === B"0101011000") { // SW.W
        uop.opType := OpType.STORE
        uop.rd := 0
        uop.rj := rj_i_s_type
        uop.rk := rk_s_type
        uop.imm := S(imm12.asBits.resize(16))
      }.otherwise {
        uop.opType := OpType.NOP // Unknown instruction
        uop.fault := True
        uop.rd := 0
        uop.rj := 0
        uop.rk := 0
        uop.imm := 0
      }
    } // End of outer otherwise block

    decodeStage(FrontendPipelineData.UOP) := uop
  }
}

// --- Dispatch Stage Plugin ---
class DispatchPlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }
  val logic = create late new Area {
    // Interaction logic is in IssueQueues plugin
  }
}

// --- Bridge between Fetch and Frontend ---
class FetchFrontendBridge extends Plugin {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val frontendPipeline = getService[FrontendPipeline]
    fetchPipeline.retain()
    frontendPipeline.retain()
    val s2m = fetchPipeline.pipeline.newStage().setCompositeName(fetchPipeline.pipeline, "fetch_S2M")
    fetchPipeline.pipeline.connect(fetchPipeline.exitStage, s2m)(S2M())
  }

  val logic = create late new Area {
    val inputStage = setup.s2m
    val outputStage = setup.frontendPipeline.firstStage

    outputStage(FrontendPipelineData.PC) := inputStage(FetchPipelineData.PC)
    outputStage(FrontendPipelineData.INSTRUCTION) := inputStage(FetchPipelineData.INSTRUCTION)
    outputStage(FrontendPipelineData.FAULT) := inputStage(FetchPipelineData.FETCH_FAULT)
    outputStage.valid := inputStage.valid
    inputStage.haltIt(!outputStage.isReady)

    setup.fetchPipeline.release()
    setup.frontendPipeline.release()
  }
}

// ==========================================================================
// == Backend: Issue, Execute, Commit ==
// ==========================================================================

// --- Issue Queues ---
class IssueQueues extends Plugin with LockedImpl {
  val integerIssueDepth = 8
  val memoryIssueDepth = 8
  val muldivIssueDepth = 4

  val setup = create early new Area {

    val intIssueQueue = StreamFifo(MicroOp(), integerIssueDepth)
    val memIssueQueue = StreamFifo(MicroOp(), memoryIssueDepth)
    val mulIssueQueue = StreamFifo(MicroOp(), muldivIssueDepth)

    val frontendPipeline = getService[FrontendPipeline]
  }

  def intIssueQueue = setup.intIssueQueue
  def memIssueQueue = setup.memIssueQueue
  def mulIssueQueue = setup.mulIssueQueue

  val dispatchLogic = create late new Area {
    val dispatchStage = setup.frontendPipeline.exitStage

    intIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)
    memIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)
    mulIssueQueue.io.push.payload := dispatchStage(FrontendPipelineData.UOP)

    // Default assignments for control signals
    intIssueQueue.io.push.valid := False
    memIssueQueue.io.push.valid := False
    mulIssueQueue.io.push.valid := False
    dispatchStage.haltIt(False)

    // Use when for run-time control based on dispatchStage signals
    when(dispatchStage.isFireing) { // Check if stage is firing (valid and ready)
      val uop = dispatchStage(FrontendPipelineData.UOP)
      switch(uop.opType) {
        is(OpType.ALU) {
          intIssueQueue.io.push.valid := True // Push happens when firing
          when(!intIssueQueue.io.push.ready) { // Check queue readiness only when firing
            dispatchStage.haltIt() // Halt if queue is full
          }
        }
        is(OpType.LOAD, OpType.STORE) {
          memIssueQueue.io.push.valid := True
          when(!memIssueQueue.io.push.ready) {
            dispatchStage.haltIt()
          }
        }
        is(OpType.MUL) {
          mulIssueQueue.io.push.valid := True
          when(!mulIssueQueue.io.push.ready) {
            dispatchStage.haltIt()
          }
        }
        default { /* NOPs pass through */ }
      }
    } elsewhen (dispatchStage.isValid) { // If stage is valid but not firing (stalled downstream)
      // Check if the stall is due to the issue queue it *would* target
      val uop = dispatchStage(FrontendPipelineData.UOP)
      switch(uop.opType) {
        is(OpType.ALU) { when(!intIssueQueue.io.push.ready) { dispatchStage.haltIt() } }
        is(OpType.LOAD, OpType.STORE) { when(!memIssueQueue.io.push.ready) { dispatchStage.haltIt() } }
        is(OpType.MUL) { when(!mulIssueQueue.io.push.ready) { dispatchStage.haltIt() } }
        default { /* NOPs pass through */ }
      }
    }
  }
}

// --- Execution Unit Data ---
object ExecutionUnitData extends AreaObject {
  val UOP = Stageable(MicroOp())
  val ALU_RESULT = Stageable(Bits(32 bits))
  val MEM_ADDRESS = Stageable(UInt(32 bits))
  val MEM_DATA_WRITE = Stageable(Bits(32 bits))
  val MEM_DATA_READ = Stageable(Bits(32 bits))
  val MUL_RESULT = Stageable(Bits(32 bits))
  val WRITEBACK_VALUE = Stageable(Bits(32 bits))
  val WRITEBACK_DEST = Stageable(UInt(5 bits))
  val WRITEBACK_ENABLE = Stageable(Bool())
  val RS1_VALUE = Stageable(Bits(32 bits))
  val RS2_VALUE = Stageable(Bits(32 bits))
}

// --- Base class for Execution Pipelines --- (Used by ALU, MUL)
abstract class ExecutionPipeline(val pipelineName: String) extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val RR = newStage()
    val EX = newStage()
    val WB = newStage()
    connect(RR, EX)(M2S())
    connect(EX, WB)(M2S())
  }
  pipeline.setCompositeName(this, pipelineName)

  val setup = create early new Area {
    var issueQueueInput: Stream[MicroOp] = null
    var writebackOutput: Stream[Fragment[CommitEntry]] = null
    val regFile = getServiceOption[RegisterFilePlugin]
  }

  val issueConnection = create late new Area {
    if (setup.issueQueueInput != null) {
      val rrStage = pipeline.RR
      rrStage.valid := setup.issueQueueInput.valid
      setup.issueQueueInput.ready := rrStage.isReady

      when(setup.issueQueueInput.fire) {
        rrStage(ExecutionUnitData.UOP) := setup.issueQueueInput.payload
      } otherwise {
        rrStage(ExecutionUnitData.UOP).assignDontCare()
      }
    } else {
      pipeline.RR.valid := False
      SpinalWarning(s"Issue Queue Input not set for pipeline $pipelineName. RR stage disabled.")
    }
  }

  val writebackConnection = create late new Area {
    if (setup.writebackOutput != null) {
      val wbStage = pipeline.WB
      setup.writebackOutput.valid := wbStage.valid
      setup.writebackOutput.payload.last := True
      wbStage.isReady := setup.writebackOutput.ready
    } else {
      SpinalWarning(s"Writeback Output not set for pipeline $pipelineName. Results will be dropped.")
      pipeline.WB.isReady := True
    }
  }

  // --- RR Stage Logic (Common Part) ---
  val rrLogic = create late new Area {
    val rrStage = pipeline.RR
    import rrStage._

    val uop = rrStage(ExecutionUnitData.UOP)
    val rs1Value = Bits(32 bits).assignDontCare()
    val rs2Value = Bits(32 bits).assignDontCare()

    // Generate read ports unconditionally if RF exists (compile-time check)
    if (setup.regFile.isDefined) {
      val rf = setup.regFile.get
      rs1Value := rf.readReg(0, uop.rj)
      rs2Value := rf.readReg(0, uop.rk)

      // TODO: Implement forwarding logic here (would mux forwarded value into rs1Value/rs2Value)
    }

    // Pass data to EX stage only when firing (run-time check)
    when(isFireing) {
      rrStage(ExecutionUnitData.UOP) := uop
      rrStage(ExecutionUnitData.RS1_VALUE) := rs1Value
      rrStage(ExecutionUnitData.RS2_VALUE) := rs2Value
    }
  }

  // --- WB Stage Logic (Common Part) ---
  val wbLogic = create late new Area {
    val wbStage = pipeline.WB
    import wbStage._

    // Generate write logic conditionally based on RF existence (compile-time check)
    if (setup.regFile.isDefined && setup.writebackOutput == null) { // Example direct write if not committing
      val rf = setup.regFile.get
      // Control write using run-time signals
      when(isFireing && wbStage(ExecutionUnitData.WRITEBACK_ENABLE)) {
        rf.writeReg(0, wbStage(ExecutionUnitData.WRITEBACK_DEST), wbStage(ExecutionUnitData.WRITEBACK_VALUE), True)
      }
    }
  }
}

// --- ALU Execution Unit Plugin ---
class AluExecutionUnit(val id: Int) extends ExecutionPipeline(s"ALU_Unit_$id") {

  val aluSetup = create early new Area {
    val queues = getService[IssueQueues]
    val commit = getService[CommitPlugin]
    setup.issueQueueInput = queues.intIssueQueue.io.pop
    if (id >= commit.arbiter.io.inputs.length) SpinalError(s"ALU ID $id exceeds Commit inputs")
    setup.writebackOutput = commit.arbiter.io.inputs(id)
  }

  val exLogic = create late new Area {
    val exStage = pipeline.EX
    import exStage._

    val uop = exStage(ExecutionUnitData.UOP)
    val rs1_value = exStage(ExecutionUnitData.RS1_VALUE)
    val rs2_value = exStage(ExecutionUnitData.RS2_VALUE)

    val result = Bits(32 bits)
    // Use 'when' for run-time operation check
    when(uop.opType === OpType.ALU) {
      when(uop.rk === 0 && uop.imm =/= 0) { // Infer ADDI
        result := (rs1_value.asSInt + uop.imm).asBits
      } otherwise { // Assume ADD.W
        result := (rs1_value.asSInt + rs2_value.asSInt).asBits
      }
    } otherwise {
      result := B(0)
    }

    exStage(ExecutionUnitData.WRITEBACK_VALUE) := result
    exStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
    exStage(ExecutionUnitData.WRITEBACK_ENABLE) := (uop.opType === OpType.ALU && uop.rd =/= 0 && !uop.fault)
  }

  val aluWbLogic = create late new Area {
    if (setup.writebackOutput != null) {
      val wbStage = pipeline.WB
      import wbStage._

      // Use 'when' for run-time check before assigning payload
      when(isFireing) {
        val commitEntry = CommitEntry()
        commitEntry.uop := wbStage(ExecutionUnitData.UOP)
        commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
        commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
        commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
        commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault

        setup.writebackOutput.payload.fragment := commitEntry
      }
    }
  }
}

// --- Memory Execution Unit Plugin --- (Independent Pipeline)
class MemoryExecutionUnit extends Plugin with LockedImpl {

  val pipeline = create early new Pipeline {
    val RR, AGU, LS, WB = newStage() // Define stages directly
    connect(RR, AGU)(M2S())
    connect(AGU, LS)(M2S())
    connect(LS, WB)(M2S())
  }
  pipeline.setCompositeName(this, "MEM_Unit")

  val setup = create early new Area {
    val queues = getService[IssueQueues]
    val commit = getService[CommitPlugin]
    val regFile = getServiceOption[RegisterFilePlugin]
    val issueQueueInput: Stream[MicroOp] = queues.memIssueQueue.io.pop
    val memArbiterSlot = 2
    if (memArbiterSlot >= commit.arbiter.io.inputs.length) SpinalError(s"MEM Unit slot exceeds Commit inputs")
    val writebackOutput: Stream[Fragment[CommitEntry]] = commit.arbiter.io.inputs(memArbiterSlot)
  }

  val issueConnection = create late new Area {
    val rrStage = pipeline.RR
    rrStage.valid := setup.issueQueueInput.valid
    setup.issueQueueInput.ready := rrStage.isReady
    when(setup.issueQueueInput.fire) {
      rrStage(ExecutionUnitData.UOP) := setup.issueQueueInput.payload
    } otherwise {
      rrStage(ExecutionUnitData.UOP).assignDontCare()
    }
  }

  val writebackConnectionLogic = create late new Area {
    val wbStage = pipeline.WB
    setup.writebackOutput.valid := wbStage.valid
    wbStage.isReady := setup.writebackOutput.ready
    setup.writebackOutput.payload.last := True

    when(wbStage.isFireing) { // Use 'when' for run-time check
      val commitEntry = CommitEntry()
      commitEntry.uop := wbStage(ExecutionUnitData.UOP)
      commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
      commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
      commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
      commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault
      setup.writebackOutput.payload.fragment := commitEntry
    }
  }

  // --- RR Stage (Memory Specific Reads) ---
  val rrLogic = create late new Area {
    val rrStage = pipeline.RR
    import rrStage._

    val uop = rrStage(ExecutionUnitData.UOP)
    val baseAddrRegVal = Bits(32 bits).assignDontCare()
    val storeDataRegVal = Bits(32 bits).assignDontCare()

    // Generate read ports unconditionally if RF exists (compile-time check)
    if (setup.regFile.isDefined) {
      val rf = setup.regFile.get
      baseAddrRegVal := rf.readReg(2, uop.rj)
      // Use 'when' for run-time check of operation type for second read
      when(uop.opType === OpType.STORE) {
        storeDataRegVal := rf.readReg(3, uop.rk)

      }
      // TODO: Forwarding
    }

    // Pass data to AGU stage conditionally on firing (run-time check)
    when(isFireing) {
      rrStage(ExecutionUnitData.UOP) := uop
      rrStage(ExecutionUnitData.RS1_VALUE) := baseAddrRegVal
      rrStage(ExecutionUnitData.RS2_VALUE) := storeDataRegVal
    }
  }

  // --- AGU Stage ---
  val aguLogic = create late new Area {
    val aguStage = pipeline.AGU
    import aguStage._

    val uop = aguStage(ExecutionUnitData.UOP)
    val baseAddress = aguStage(ExecutionUnitData.RS1_VALUE).asUInt
    val offset = uop.imm
    val storeData = aguStage(ExecutionUnitData.RS2_VALUE)

    val effectiveAddress = (baseAddress.asSInt + offset).asUInt
    val aguFault = False // Placeholder

    // Pass data to LS stage conditionally on firing
    when(isFireing) {
      val nextUop = uop
      nextUop.fault := uop.fault || aguFault // Update fault status potentially
      aguStage(ExecutionUnitData.UOP) := nextUop
      aguStage(ExecutionUnitData.MEM_ADDRESS) := effectiveAddress
      aguStage(ExecutionUnitData.MEM_DATA_WRITE) := storeData
    }
  }

  // --- LS Stage (Load/Store Access) ---
  val lsLogic = create late new Area {
    val lsStage = pipeline.LS
    import lsStage._

    val uop = lsStage(ExecutionUnitData.UOP)
    val address = lsStage(ExecutionUnitData.MEM_ADDRESS)
    val dataToWrite = lsStage(ExecutionUnitData.MEM_DATA_WRITE)

    val readData = Bits(32 bits).assignDontCare()
    val memFault = False
    val memAcknowledge = True // Placeholder

    // Use 'when' for run-time stall condition
    when(!memAcknowledge) {
      lsStage.haltIt()
    }

    // Use 'when' for run-time operation type checks
    when(isValid && memAcknowledge) { // Ensure stage is valid for operation
      when(uop.opType === OpType.LOAD) { readData := B"32'xCAFEBABE" }
      when(uop.opType === OpType.STORE) { /* memory.write(...) */ }
    }

    val finalFault = uop.fault || memFault

    // Pass data to WB stage conditionally on firing
    when(isFireing) {
      val nextUop = uop
      nextUop.fault := finalFault
      lsStage(ExecutionUnitData.UOP) := nextUop
      lsStage(ExecutionUnitData.MEM_DATA_READ) := readData

      val writeEnable = (uop.opType === OpType.LOAD && uop.rd =/= 0 && !finalFault)
      lsStage(ExecutionUnitData.WRITEBACK_VALUE) := readData
      lsStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
      lsStage(ExecutionUnitData.WRITEBACK_ENABLE) := writeEnable
    }
  }

  // --- WB Stage (Memory Specific) ---
  val memWbLogic = create late new Area {
    val wbStage = pipeline.WB
    import wbStage._

    // Generate write logic conditionally based on RF existence
    if (setup.regFile.isDefined) {
      val rf = setup.regFile.get
      // Control write using run-time signals
      when(isFireing && wbStage(ExecutionUnitData.WRITEBACK_ENABLE)) {
        val addr =  wbStage(ExecutionUnitData.WRITEBACK_DEST)
        val data = wbStage(ExecutionUnitData.WRITEBACK_VALUE)
        rf.writeReg(1, addr, data, True)
      }
    }
  }
}

// --- Multiply/Divide Execution Unit Plugin ---
class MulDivExecutionUnit extends ExecutionPipeline("MulDiv_Unit") {

  val mulSetup = create early new Area {
    val queues = getService[IssueQueues]
    val commit = getService[CommitPlugin]
    setup.issueQueueInput = queues.mulIssueQueue.io.pop
    val mulArbiterSlot = 3
    if (mulArbiterSlot >= commit.arbiter.io.inputs.length) SpinalError(s"MUL Unit slot exceeds Commit inputs")
    setup.writebackOutput = commit.arbiter.io.inputs(mulArbiterSlot)
  }

  val mulExLogic = create late new Area {
    val exStage = pipeline.EX
    import exStage._

    val uop = exStage(ExecutionUnitData.UOP)
    val rs1_value = exStage(ExecutionUnitData.RS1_VALUE)
    val rs2_value = exStage(ExecutionUnitData.RS2_VALUE)

    val result = (rs1_value.asSInt * rs2_value.asSInt).resize(32).asBits
    val finalFault = uop.fault

    exStage(ExecutionUnitData.WRITEBACK_VALUE) := result
    exStage(ExecutionUnitData.WRITEBACK_DEST) := uop.rd
    exStage(ExecutionUnitData.WRITEBACK_ENABLE) := (uop.opType === OpType.MUL && uop.rd =/= 0 && !finalFault)
    exStage(ExecutionUnitData.UOP).fault := finalFault
  }

  val mulWbLogic = create late new Area {
    if (setup.writebackOutput != null) {
      val wbStage = pipeline.WB
      import wbStage._
      when(isFireing) { // Use 'when' for run-time check
        val commitEntry = CommitEntry()
        commitEntry.uop := wbStage(ExecutionUnitData.UOP)
        commitEntry.writebackValue := wbStage(ExecutionUnitData.WRITEBACK_VALUE)
        commitEntry.writebackDest := wbStage(ExecutionUnitData.WRITEBACK_DEST)
        commitEntry.writebackEnable := wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
        commitEntry.fault := wbStage(ExecutionUnitData.UOP).fault
        setup.writebackOutput.payload.fragment := commitEntry
      }
    }
  }
}

// --- Commit Stage ---
case class CommitEntry() extends Bundle {
  val uop = MicroOp()
  val writebackValue = Bits(32 bits)
  val writebackDest = UInt(5 bits)
  val writebackEnable = Bool()
  val fault = Bool()
}

class CommitPlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val executionUnitCount = 4
    val arbiter = StreamArbiterFactory.roundRobin.build(Fragment(CommitEntry()), executionUnitCount)
    val regFile = getService[RegisterFilePlugin]
  }
  def arbiter = setup.arbiter

  val commitLogic = create late new Area {
    val inputCommitStream = Stream(Fragment(CommitEntry()))
    inputCommitStream << setup.arbiter.io.output
    val rf = setup.regFile

    inputCommitStream.ready := True

    // Use 'when' for run-time commit processing
    when(inputCommitStream.fire) {
      val commitData = inputCommitStream.payload.fragment

      // Generate write logic
      // Control write based on run-time commit data
      when(commitData.writebackEnable && !commitData.fault) {
        rf.writeReg(2, commitData.writebackDest, commitData.writebackValue, True)
      }
      report(
        Seq(
          L"COMMIT: PC=0x${commitData.uop.pc}%x OP=${commitData.uop.opType} ",
          L"RD=${commitData.writebackDest} WEn=${commitData.writebackEnable} ",
          L"WVal=0x${commitData.writebackValue}%x Fault=${commitData.fault}"
        )
      )
    }
  }
}

class RegisterFilePlugin(
    val numRegisters: Int = 32,
    val numReadPorts: Int = 6,
    val numWritePorts: Int = 4
) extends Plugin {

  // 内部信号，用于存储写请求
  val writeSignals = create early new Area {
    val addresses = Vec(UInt(log2Up(numRegisters) bits), numWritePorts)
    val datas = Vec(Bits(32 bits), numWritePorts)
    val enables = Vec(Bool(), numWritePorts)

    for (i <- 0 until numWritePorts) {
      addresses(i) := 0
      datas(i) := 0
      enables(i) := False
    }
  }

  val readSignals = create early new Area {
    val addresses = Vec(UInt(log2Up(numRegisters) bits), numReadPorts)
    val data = Vec(Bits(32 bits), numReadPorts)

    for (i <- 0 until numReadPorts) {
      addresses(i) := 0
      data(i) := 0
    }
  }

  // 创建IO接口，但现在是输出而不是输入
  val io = create early new Area {
    val reads = Vec(
      new Bundle {
        val address = in UInt (log2Up(numRegisters) bits)
        val data = out Bits (32 bits)
      },
      numReadPorts
    )
  }

  val regFileLogic = create late new Area {
    val registerFile = Mem(Bits(32 bits), numRegisters)

    // 处理读操作
    for (i <- 0 until numReadPorts) {
      when(readSignals.addresses(i) =/= 0) {
        io.reads(i).data := registerFile.readAsync(readSignals.addresses(i))
      } otherwise {
        io.reads(i).data := 0
      }
    }

    // 处理写操作
    for (i <- 0 until numWritePorts) {
      when(writeSignals.enables(i) && writeSignals.addresses(i) =/= 0) {
        registerFile.write(writeSignals.addresses(i), writeSignals.datas(i))
      }
    }
  }

  // 写入方法直接操作内部信号
  def writeReg(portIndex: Int, address: UInt, data: Bits, enable: Bool): Unit = {
    writeSignals.addresses(portIndex) := address
    writeSignals.datas(portIndex) := data
    writeSignals.enables(portIndex) := enable
  }

  def readReg(portIndex: Int, address: UInt): Bits = {
    readSignals.addresses(portIndex) := address
    readSignals.data(portIndex)
  }
}

// ==========================================================================
// == Main CPU Instantiation & Simulation ==
// ==========================================================================
object DemoCpuGen extends App {
  SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz))
    .generateVerilog(new DemoCPU(plugins = DemoCpuGen.getPlugins))
  println("Verilog Generation DONE")

  def getPlugins: Seq[Plugin] = Seq(
    new RegisterFilePlugin,
    new FetchPipeline,
    new FrontendPipeline,
    new FetchFrontendBridge,
    new Fetch0Plugin,
    new Fetch1Plugin,
    new DecodePlugin,
    new DispatchPlugin,
    new IssueQueues,
    new CommitPlugin,
    new AluExecutionUnit(id = 0),
    new AluExecutionUnit(id = 1),
    new MemoryExecutionUnit,
    new MulDivExecutionUnit
  )
}

object DemoCPUGenSim extends App {
  SimConfig.withWave
    .compile(
      new DemoCPU(plugins = DemoCpuGen.getPlugins)
    )
    .doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling(200)
      println("Simulation DONE")
    }
}
