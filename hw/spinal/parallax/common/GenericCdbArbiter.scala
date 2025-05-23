package parallax.common

import spinal.core._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer

/** 
 * Generic M-to-N CDB Arbiter for CdbTargetedMessage types.
 * 
 * @param payloadHardType The type of the payload of the targeted messages.
 * @param numInputs The number of input streams.
 * @param numOutputs The number of output streams.
 * @param arbiterId A string to identify the arbiter in logs.
 * @param enableLog Whether to enable logging.
 */
class GenericCdbArbiter[K <: Data, T <: CdbTargetedMessage[K]](
    payloadHardType: HardType[T],
    numInputs: Int,
    numOutputs: Int,
    arbiterId: String = "CdbArbiter", // For logging
    enableLog: Boolean = false
)
    extends Component { // Evidence for logging
  require(numInputs >= 0)
  require(numOutputs > 0)

  case class IO() {
    val inputs = Vec(slave Stream (payloadHardType()), numInputs)
    val outputs = Vec(master Stream (payloadHardType()), numOutputs)
  }

  val io = new IO

  if (numInputs == 0) {
    io.outputs.foreach { outStream =>
      outStream.valid := False
      outStream.payload.assignDontCare()
    }
  } else {
    val inputsPerCdbOutput = Vector.fill(numOutputs)(ArrayBuffer[Stream[T]]())
    io.inputs.zipWithIndex.foreach { case (inputStream, inputIdx) =>
      inputsPerCdbOutput(inputIdx % numOutputs) += inputStream.asInstanceOf[Stream[T]]
    }

    for (outputIdx <- 0 until numOutputs) {
      val currentArbiterInputs = inputsPerCdbOutput(outputIdx)
      val cdbOutStream = io.outputs(outputIdx)

      if (currentArbiterInputs.nonEmpty) {
        val arbitratedStream = StreamArbiterFactory().roundRobin.on(currentArbiterInputs)
        cdbOutStream << arbitratedStream

        if (enableLog) {
          when(cdbOutStream.fire) {
            val targetIdxAny = cdbOutStream.payload.cdbTargetIdx
            // Attempt to log the index; asBits.asUInt might work for UInt-like K
            // For more complex K, specific logging would be needed or disabled.
            // val targetIdxToLog = targetIdxAny.asBits.asUInt // This requires K to be convertible
            report(L"${arbiterId}: CDB[${outputIdx.toString}] FIRE (TargetIdx raw: ${targetIdxAny})")
          }
        }
      } else {
        cdbOutStream.valid := False
        cdbOutStream.payload.assignDontCare()
      }
    }
  }
}
