package parallax

import spinal.core._
import spinal.lib._
// ==========================================================================
// == Stack Component (NO CHANGES NEEDED HERE, Original code seems correct) ==
// ==========================================================================
case class StackConfig()

case class Stack[T <: Data](
    dataType: HardType[T],
    depth: Int,
    config: StackConfig = StackConfig()
) extends Component {

  require(depth > 0, "Stack depth must be positive")

  val io = new Bundle {
    val push = slave Stream (dataType())
    val pop = master Stream (dataType())
    val flush = in Bool () init (False)
    val occupancy = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val ram = Mem(dataType, wordCount = depth)

  val ptr = Reg(UInt(log2Up(depth + 1) bits)) init (0)
  ptr.addAttribute("no_init") // Avoid Quartus warning if init is handled by reset

  val isEmpty = ptr === 0
  val isFull = ptr === U(depth)

  val pushing = io.push.fire
  val popping = io.pop.fire

  io.push.ready := !isFull
  io.pop.valid := !isEmpty

  when(pushing =/= popping) {
    when(pushing) { ptr := ptr + 1 }
    when(popping) { ptr := ptr - 1 }
  }

  when(io.flush) {
    ptr := 0
  }

  val ramAddrWidth = log2Up(depth)
  ram.write(
    address = ptr.resize(ramAddrWidth),
    data = io.push.payload,
    enable = pushing
  )

  io.pop.payload := ram.readSync(
    address = (ptr - 1).resize(ramAddrWidth),
    enable = !isEmpty // Read speculatively when not empty
  )

  io.occupancy := ptr
  val depth_resized = U(depth, ptr.getWidth bits)
  io.availability := depth_resized - ptr
}

object Stack {
  def apply[T <: Data](dataType: HardType[T], depth: Int): Stack[T] = {
    new Stack(dataType, depth)
  }
}
