package parallax.components.lsu

import spinal.core._
import spinal.lib._

// 这是一个通用的Skid Buffer组件，可以复用
class SkidBuffer[T <: Data](dataType: HardType[T]) extends Component {
  val io = new Bundle {
    val input = slave(Stream(dataType))
    val output = master(Stream(dataType))
  }

  // 存储元件，用于在下游不准备好时“抓住”数据
  val occupied = Reg(Bool()) init (False)
  val dataReg = Reg(dataType)

  // 默认连接
  io.output.valid := io.input.valid || occupied
  io.output.payload := Mux(occupied, dataReg, io.input.payload)
  io.input.ready := io.output.ready && !occupied

  when(io.output.fire) {
    // 当输出发生传输时
    when(occupied) {
      // 如果我们发送的是寄存器里的数据，清空寄存器
      occupied := False
    }
  }.elsewhen(io.input.fire) {
    // 当输出没有传输，但输入有传输时（这意味着下游不ready，但上游ready）
    // 我们必须“抓住”这个输入数据
    occupied := True
    dataReg := io.input.payload
  }
}

object skid {
  def apply[T <: Data](input: Stream[T]): Stream[T] = {
    val skid = new SkidBuffer(HardType(input.payload))
    skid.io.input << input
    skid.io.output >> input
    skid.io.output
  }
}
