package boson.demo2.common;

import spinal.core._

object OpType extends SpinalEnum {
  val NOP, ALU, MUL = newElement()
}

case class MicroOp() extends Bundle {
  val opType = OpType()
  val rd = UInt(Config.ARCH_REG_WIDTH)
  val rj = UInt(Config.ARCH_REG_WIDTH)
  val rk = UInt(Config.ARCH_REG_WIDTH)
  val imm = SInt(16 bits)
  val pc = UInt(Config.XLEN bits)
  val fault = Bool()

  def writesArf(): Bool = (opType === OpType.ALU || opType === OpType.MUL) && rd =/= 0
}
