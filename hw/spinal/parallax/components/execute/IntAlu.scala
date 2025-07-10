// filename: parallax/components/execute/IntAlu.scala
package parallax.components.execute

import spinal.core._
import spinal.lib._
import parallax.common._ // Assuming PipelineConfig, IQEntryAluInt, LogicOp etc. are here
import parallax.utilities.ParallaxSim
import parallax.components.issue.IQEntryAluInt

// Define the SpinalEnum for ALU Exception Codes
object IntAluExceptionCode extends SpinalEnum(binarySequential) {
  // NONE should ideally be the first element if its value is intended to be 0
  val NONE, UNDEFINED_ALU_OP, DISPATCH_TO_WRONG_EU, DECODE_EXCEPTION = newElement()
}

// Define the payload for ALU output
case class IntAluOutputPayload(config: PipelineConfig) extends Bundle with IMasterSlave {
  val data = Bits(config.dataWidth)
  val physDest = PhysicalRegOperand(config.physGprIdxWidth)
  val writesToPhysReg = Bool()
  val robPtr = UInt(config.robPtrWidth)
  val hasException = Bool()
  val exceptionCode = IntAluExceptionCode() // Use the SpinalEnum type

  override def asMaster(): Unit = {
    in(data, physDest, writesToPhysReg, robPtr, hasException, exceptionCode)
  }
}

case class IntAluIO(config: PipelineConfig) extends Bundle with IMasterSlave {
  val iqEntryIn = Flow(IQEntryAluInt(config))
  val resultOut = Flow(IntAluOutputPayload(config))

  override def asMaster(): Unit = {
    master(iqEntryIn)
    slave(resultOut)
  }
}

class IntAlu(val config: PipelineConfig) extends Component {
  // Use the new IntAluIO Bundle
  val io = slave(IntAluIO(config))

  // --- Default outputs ---
  // Output is valid if input is valid (combinational path)
  io.resultOut.valid := io.iqEntryIn.valid

  // Default assignments for the payload. These are overridden when io.iqEntryIn.valid is true.
  io.resultOut.payload.data := B(0)
  io.resultOut.payload.physDest.idx := U(0) // Or use config.physGprIdxWidth for width if U(0) is ambiguous
  io.resultOut.payload.writesToPhysReg := False
  io.resultOut.payload.robPtr := U(0) // Or use config.robPtrWidth
  io.resultOut.payload.hasException := False
  io.resultOut.payload.exceptionCode := IntAluExceptionCode.NONE // Default to NONE

  // --- ALU Operation Logic ---
  when(io.iqEntryIn.valid) {
    val iqEntry = io.iqEntryIn.payload

    val src1 = iqEntry.src1Data
    val src2 = iqEntry.src2Data

    // Intermediate variables for results
    val resultData = Bits(config.dataWidth)
    var exceptionOccurred = False
    var exceptionCodeVal = IntAluExceptionCode() // Use the SpinalEnum type

    // Initialize with default/safe values for this valid transaction
    resultData.assignDontCare() // Or B(0) if a specific default is safer before assignment
    exceptionCodeVal := IntAluExceptionCode.NONE // Default to no exception for this transaction

    // Pass through ROB index and destination info
    io.resultOut.payload.robPtr := iqEntry.robPtr
    io.resultOut.payload.physDest := iqEntry.physDest
    io.resultOut.payload.writesToPhysReg := iqEntry.writesToPhysReg

    // ALU Operation Logic based on aluCtrl flags
    when(iqEntry.aluCtrl.isSub) { // SUB, NEG, CMP (EQ, SGT)
      // Handle signed vs unsigned operations
      when(iqEntry.aluCtrl.isSigned) {
        resultData := (src1.asSInt - src2.asSInt).asBits
      } otherwise {
        resultData := (src1.asUInt - src2.asUInt).asBits
      }
    } elsewhen (iqEntry.aluCtrl.logicOp =/= LogicOp.NONE) { // Check against a defined "NONE" or "0" for LogicOp
      when(iqEntry.aluCtrl.logicOp === LogicOp.AND) {
        resultData := src1 & src2
      } elsewhen (iqEntry.aluCtrl.logicOp === LogicOp.OR) {
        resultData := src1 | src2
      } elsewhen (iqEntry.aluCtrl.logicOp === LogicOp.XOR) {
        resultData := src1 ^ src2
      } otherwise {
        // Unrecognized logicOp for an ALU type instruction
        exceptionOccurred := True
        exceptionCodeVal := IntAluExceptionCode.UNDEFINED_ALU_OP
        resultData := B(0) // Define result on exception
      }
    } elsewhen (iqEntry.aluCtrl.isAdd) { // Explicitly check for ADD (assuming isAdd flag exists)
      // Handle signed vs unsigned operations
      when(iqEntry.aluCtrl.isSigned) {
        resultData := (src1.asSInt + src2.asSInt).asBits
      } otherwise {
        resultData := (src1.asUInt + src2.asUInt).asBits
      }
    } elsewhen (!iqEntry.aluCtrl.isSub && iqEntry.aluCtrl.logicOp === LogicOp.NONE && !iqEntry.aluCtrl.isAdd) { // SHIFT operations - detect when no ALU flags are set
      // Determine shift amount source: immediate or register
      val shiftAmount = Mux(
        iqEntry.immUsage === ImmUsageType.SRC_SHIFT_AMT,
        iqEntry.imm(4 downto 0).asUInt, // 5-bit immediate
        src2(4 downto 0).asUInt         // 5-bit from register src2
      )
      
      when(iqEntry.shiftCtrl.isRight) {
        when(iqEntry.shiftCtrl.isArithmetic) {
          // Arithmetic right shift
          resultData := (src1.asSInt >> shiftAmount).asBits.resize(config.dataWidth)
        } otherwise {
          // Logical right shift
          resultData := (src1.asUInt >> shiftAmount).asBits.resize(config.dataWidth)
        }
      } otherwise {
        // Left shift (always logical) - explicitly resize to target width
        resultData := (src1.asUInt << shiftAmount).asBits.resize(config.dataWidth)
      }
    } otherwise {
      // This case implies it's an ALU uopCode, but control flags (isSub, logicOp, isAdd) don't specify a known operation.
      exceptionOccurred := True
      exceptionCodeVal := IntAluExceptionCode.UNDEFINED_ALU_OP
      resultData := B(0) // Define result on exception
    }

    // Assign final results to output payload
    io.resultOut.payload.data := resultData
    io.resultOut.payload.hasException := exceptionOccurred
    io.resultOut.payload.exceptionCode := exceptionCodeVal
  } otherwise {
    // When io.iqEntryIn is not valid, default assignments at the beginning of the class apply.
    report("IntAlu: iqEntryIn.valid is false")
  }
}
