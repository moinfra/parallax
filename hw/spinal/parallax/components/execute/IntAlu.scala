// filename: parallax/components/execute/IntAlu.scala
package parallax.components.execute

import spinal.core._
import spinal.lib._
import parallax.common._ // Assuming PipelineConfig, IQEntryAluInt, LogicOp etc. are here
import parallax.utilities.ParallaxSim
import parallax.components.issue.IQEntryAluInt

object IntAluExceptionCode extends SpinalEnum(binarySequential) {
  val NONE, UNDEFINED_ALU_OP = newElement()
}

case class IntAluOutputPayload(config: PipelineConfig) extends Bundle with IMasterSlave {
  val data = Bits(config.dataWidth)
  val physDest = PhysicalRegOperand(config.physGprIdxWidth)
  val writesToPhysReg = Bool()
  val robPtr = UInt(config.robPtrWidth)
  val hasException = Bool()
  val exceptionCode = IntAluExceptionCode()

  override def asMaster(): Unit = {
    out(data, physDest, writesToPhysReg, robPtr, hasException, exceptionCode)
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
  val io = slave(IntAluIO(config))

  // --- 默认输出 ---
  io.resultOut.valid := io.iqEntryIn.valid
  io.resultOut.payload.data := 0
  io.resultOut.payload.physDest.idx := 0
  io.resultOut.payload.writesToPhysReg := False
  io.resultOut.payload.robPtr := 0
  io.resultOut.payload.hasException := False
  io.resultOut.payload.exceptionCode := IntAluExceptionCode.NONE

  // --- 主要ALU逻辑 ---
  when(io.iqEntryIn.valid) {
    val iqEntry = io.iqEntryIn.payload
    val src1 = iqEntry.src1Data
    val src2 = iqEntry.src2Data

    // --- 1. 并行计算所有可能的结果 ---
    val addResult = (src1.asUInt + src2.asUInt).asBits
    val subResult = (src1.asUInt - src2.asUInt).asBits
    val sltResult = Mux(src1.asSInt < src2.asSInt, U(1, config.dataWidth), U(0, config.dataWidth)).asBits
    val sltuResult = Mux(src1.asUInt < src2.asUInt, U(1, config.dataWidth), U(0, config.dataWidth)).asBits
    
    val andResult = src1 & src2
    val orResult  = src1 | src2
    val xorResult = src1 ^ src2
    val norResult = ~(src1 | src2)
    
    val shiftAmount = Mux(
      iqEntry.immUsage === ImmUsageType.SRC_SHIFT_AMT,
      iqEntry.imm(log2Up(config.dataWidth.value)-1 downto 0).asUInt,
      src2(log2Up(config.dataWidth.value)-1 downto 0).asUInt
    )
    val sllResult = (src1 << shiftAmount).resize(config.dataWidth)
    val srlResult = (src1 >> shiftAmount).resize(config.dataWidth)
    val sraResult = (src1.asSInt >> shiftAmount).resize(config.dataWidth).asBits
    
    // --- 2. 并行生成精确且互斥的选择条件 ---
    val aluCtrl = iqEntry.aluCtrl
    
    val isAddOp = aluCtrl.isAdd
    val isSubOp = aluCtrl.isSub
    
    val isSltOp  = aluCtrl.condition === BranchCondition.LT
    val isSltuOp = aluCtrl.condition === BranchCondition.LTU

    val isAndOp = aluCtrl.logicOp === LogicOp.AND
    val isOrOp  = aluCtrl.logicOp === LogicOp.OR
    val isNorOp  = aluCtrl.logicOp === LogicOp.NOR
    val isXorOp = aluCtrl.logicOp === LogicOp.XOR
    
    val shiftCtrl = iqEntry.shiftCtrl
    val isSllOp = shiftCtrl.valid && !shiftCtrl.isRight
    val isSrlOp = shiftCtrl.valid &&  shiftCtrl.isRight && !shiftCtrl.isArithmetic
    val isSraOp = shiftCtrl.valid &&  shiftCtrl.isRight &&  shiftCtrl.isArithmetic
    
    // --- 3. 手动构建真正的扁平化MUX (Sum of Products) ---
    val zero = B(0, config.dataWidth)
    val resultData = 
      Mux(isAddOp, addResult, zero) |
      Mux(isSubOp, subResult, zero) |
      Mux(isSltOp, sltResult, zero) |
      Mux(isSltuOp, sltuResult, zero) |
      Mux(isAndOp, andResult, zero) |
      Mux(isOrOp,  orResult,  zero) |
      Mux(isNorOp, norResult, zero) |
      Mux(isXorOp, xorResult, zero) |
      Mux(isSllOp, sllResult, zero) |
      Mux(isSrlOp, srlResult, zero) |
      Mux(isSraOp, sraResult, zero)
    
    // --- 4. 并行处理异常 ---
    // 一个操作是已知的，当且仅当它是任何一个已定义的操作类型
    val isKnownOp = isAddOp | isSubOp | isSltOp | isSltuOp | isAndOp | isOrOp | isNorOp | isXorOp | isSllOp | isSrlOp | isSraOp
    assert(isKnownOp, L"Impossible ALU operation at pc=${iqEntry.pc} robPtr=${iqEntry.robPtr}")
    
    io.resultOut.payload.hasException := !isKnownOp
    io.resultOut.payload.exceptionCode := Mux(!isKnownOp, IntAluExceptionCode.UNDEFINED_ALU_OP, IntAluExceptionCode.NONE)
    
    // --- 5. 最终输出选择 (若有异常则覆盖为0) ---
    io.resultOut.payload.data := Mux(!isKnownOp, zero, resultData)
    
    // --- 6. 透传其他控制信号 ---
    io.resultOut.payload.robPtr := iqEntry.robPtr
    io.resultOut.payload.physDest := iqEntry.physDest
    io.resultOut.payload.writesToPhysReg := iqEntry.writesToPhysReg
  }
}
