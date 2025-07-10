// filename: parallax/components/execute/DemoAlu.scala
package parallax.components.execute

import spinal.core._
import spinal.lib._
import parallax.common._ // Assuming PipelineConfig, RenamedUop, BaseUopCode, ImmUsageType, LogicOp etc. are here
import parallax.utilities.ParallaxSim

// Define the SpinalEnum for ALU Exception Codes
object AluExceptionCode extends SpinalEnum(binarySequential) {
  // NONE should ideally be the first element if its value is intended to be 0
  val NONE, UNDEFINED_ALU_OP, DISPATCH_TO_WRONG_EU, DECODE_EXCEPTION = newElement()
}

// Define the payload for ALU output
case class AluOutputPayload(config: PipelineConfig) extends Bundle with IMasterSlave {
  val data = Bits(config.dataWidth)
  val physDest = PhysicalRegOperand(config.physGprIdxWidth)
  val writesToPhysReg = Bool()
  val robPtr = UInt(config.robPtrWidth)
  val hasException = Bool()
  val exceptionCode = AluExceptionCode() // Use the SpinalEnum type

  override def asMaster(): Unit = {
    in(data, physDest, writesToPhysReg, robPtr, hasException, exceptionCode)
  }
}

case class DemoAluIo(config: PipelineConfig) extends Bundle with IMasterSlave {
  val uopIn = Flow(RenamedUop(config))
  val src1DataIn = Bits(config.dataWidth)
  val src2DataIn = Bits(config.dataWidth)
  val resultOut = Flow(AluOutputPayload(config))

  override def asMaster(): Unit = {
    master(uopIn)
    out(src1DataIn)
    out(src2DataIn)
    slave(resultOut)
  }
}

class DemoAlu(val config: PipelineConfig) extends Component {
  // Use the new DemoAluIo Bundle
  val io = slave(DemoAluIo(config))

  // --- Default outputs ---
  // Output is valid if input is valid (combinational path)
  io.resultOut.valid := io.uopIn.valid

  // Default assignments for the payload. These are overridden when io.uopIn.valid is true.
  io.resultOut.payload.data := B(0)
  io.resultOut.payload.physDest.idx := U(0) // Or use config.physGprIdxWidth for width if U(0) is ambiguous
  io.resultOut.payload.writesToPhysReg := False
  io.resultOut.payload.robPtr := U(0) // Or use config.robPtrWidth
  io.resultOut.payload.hasException := False
  io.resultOut.payload.exceptionCode := AluExceptionCode.NONE // Default to NONE

  // --- ALU Operation Logic ---
  when(io.uopIn.valid) {
    val uop = io.uopIn.payload
    val decoded = uop.decoded
    val renameInfo = uop.rename

    val src1 = io.src1DataIn
    val src2 = io.src2DataIn
    val imm = decoded.imm // Already sign/zero extended by decoder

    // Intermediate variables for results
    val resultData = Bits(config.dataWidth)
    var exceptionOccurred = False
    var exceptionCodeVal = AluExceptionCode() // Use the SpinalEnum type

    // Initialize with default/safe values for this valid transaction
    resultData.assignDontCare() // Or B(0) if a specific default is safer before assignment
    exceptionCodeVal := AluExceptionCode.NONE // Default to no exception for this transaction

    // Pass through ROB index and destination info
    io.resultOut.payload.robPtr := uop.robPtr
    io.resultOut.payload.physDest := renameInfo.physDest
    io.resultOut.payload.writesToPhysReg := renameInfo.writesToPhysReg

    // Handle decode-time exceptions first
    when(decoded.hasDecodeException) {
      exceptionOccurred := True
      // Assign a specific enum value for decode exceptions
      exceptionCodeVal := AluExceptionCode.DECODE_EXCEPTION
      resultData := B(0) // Or some other defined value on exception
    } otherwise {
      // Switch based on the BaseUopCode
      switch(decoded.uopCode) {
        is(BaseUopCode.ALU) {
          val effSrc2 = Mux(decoded.immUsage === ImmUsageType.SRC_ALU, imm, src2)

          // Assuming AluCtrlFlags contains LogicOp.OR, etc.
          // e.g. object AluCtrlFlags { val NONE = U"000"; val AND = U"001"; ... }
          // These should ideally come from parallax.common.AluCtrlFlags or similar definition for LogicOp

          when(decoded.aluCtrl.isSub) { // SUB, NEG, CMP (EQ, SGT)
            resultData := (src1.asSInt - effSrc2.asSInt).asBits
          } elsewhen (decoded.aluCtrl.logicOp =/= LogicOp.NONE) { // Check against a defined "NONE" or "0" for LogicOp
            when(decoded.aluCtrl.logicOp === LogicOp.AND) {
              resultData := src1 & effSrc2
            } elsewhen (decoded.aluCtrl.logicOp === LogicOp.OR) {
              resultData := src1 | effSrc2
            } elsewhen (decoded.aluCtrl.logicOp === LogicOp.XOR) {
              resultData := src1 ^ effSrc2
            } otherwise {
              // Unrecognized logicOp for an ALU type instruction
              exceptionOccurred := True
              exceptionCodeVal := AluExceptionCode.UNDEFINED_ALU_OP
              resultData := B(0) // Define result on exception
            }
          } elsewhen (decoded.aluCtrl.isAdd) { // Explicitly check for ADD (assuming isAdd flag exists)
            resultData := (src1.asSInt + effSrc2.asSInt).asBits
          } otherwise {
            // This case implies it's an ALU uopCode, but control flags (isSub, logicOp, isAdd) don't specify a known operation.
            exceptionOccurred := True
            exceptionCodeVal := AluExceptionCode.UNDEFINED_ALU_OP
            resultData := B(0) // Define result on exception
          }
        }
        is(BaseUopCode.SHIFT) {
          // Determine shift amount source: immediate or register
          val shiftAmount = Mux(
            decoded.immUsage === ImmUsageType.SRC_SHIFT_AMT,
            decoded.imm(4 downto 0).asUInt, // 5-bit immediate
            src2(4 downto 0).asUInt         // 5-bit from register src2
          )
          
          when(decoded.shiftCtrl.isRight) {
            when(decoded.shiftCtrl.isArithmetic) {
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
        }
        is(BaseUopCode.NOP) {
          // NOP: do nothing, just pass through (no result needed)
          resultData := B(0)
        }
        is(BaseUopCode.IDLE) {
          // IDLE: similar to NOP but marks CPU halt point
          // ALU just needs to complete successfully, actual halt happens at commit
          resultData := B(0)
        }
        is(BaseUopCode.MUL) {
          // NOT SUPPORTED
          exceptionOccurred := True
          exceptionCodeVal := AluExceptionCode.UNDEFINED_ALU_OP
          resultData := B(0)
        }
        // DIV, SHIFT, LOAD, STORE, BRANCH, JUMP etc. are not handled by this DemoAlu
        default {
          // This uop should not have been sent to this ALU.
          exceptionOccurred := True
          exceptionCodeVal := AluExceptionCode.DISPATCH_TO_WRONG_EU
          resultData := B(0) // Define result on exception
        }
      }
    }

    // Assign final results to output payload
    io.resultOut.payload.data := resultData
    io.resultOut.payload.hasException := exceptionOccurred
    io.resultOut.payload.exceptionCode := exceptionCodeVal
  } otherwise {
    // When io.uopIn is not valid, default assignments at the beginning of the class apply.
    // The ParallaxSim.fatal call here would trigger simulation failure on every cycle
    // where uopIn is not valid, which might not be the desired behavior if this ALU
    // can be idle. If it's truly an unexpected state for this specific design context,
    // it could remain, but typically, an ALU can have invalid inputs without it being fatal.
    // For this refactoring, I'm keeping it as it was in the original code.
    // If io.uopIn.valid is False, then io.resultOut.valid is also False.
    // The payload will take the default values assigned at the start of the class.
    // Consider if this fatal call is necessary or should be removed/conditionalized.
    report("Unexpected invalid input to DemoAlu (io.uopIn.valid is False)")
  }
}
