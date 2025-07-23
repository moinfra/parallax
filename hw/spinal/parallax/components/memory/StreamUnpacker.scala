package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

class StreamUnpacker[T_IN <: Bundle, T_OUT <: Data](
    input_type: HardType[T_IN],
    output_type: HardType[T_OUT],
    unpack_func: (T_IN, UInt) => T_OUT,
    valid_mask_getter: T_IN => Bits
) extends Component {
    val enableLog = false
    val instructionsPerGroup = widthOf(valid_mask_getter(input_type()))

    val io = new Bundle {
        val input = slave Stream(input_type)
        val output = master Stream(output_type)
        val isBusy = out Bool()
        val flush = in Bool()
    }

    val buffer = Reg(input_type)
    val bufferValid = Reg(Bool()) init(False)
    val unpackIndex = Reg(UInt(log2Up(instructionsPerGroup) bits))

    io.isBusy := bufferValid
    io.input.ready := !bufferValid

    when(io.input.fire) {
        buffer := io.input.payload
        bufferValid := True
        unpackIndex := 0
    }

    val currentMaskBit = valid_mask_getter(buffer)(unpackIndex)
    io.output.payload := unpack_func(buffer, unpackIndex)
    io.output.valid := bufferValid && currentMaskBit

    val isLast = unpackIndex === instructionsPerGroup - 1
    val canAdvance = io.output.fire || (bufferValid && !currentMaskBit)

    when(canAdvance) {
        when(isLast) {
            bufferValid := False
            unpackIndex := 0
        } .otherwise {
            unpackIndex := unpackIndex + 1
        }
    }

    when(io.flush) {
        bufferValid := False
        unpackIndex := 0
    }
}
