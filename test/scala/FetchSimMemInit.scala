package test.scala // Or your test utility package

import parallax.components.memory.SimulatedSplitGeneralMemory // Updated import
import spinal.core._
import spinal.core.sim._
import parallax.utilities.ParallaxLogger

object FetchSimMemInit {
  def initMemWord(
      simMem: SimulatedSplitGeneralMemory, // Takes SimulatedSplitGeneralMemory
      address: Long,
      value: BigInt,
      valueDataWidthBits: Int,
      clockDomain: ClockDomain
  ): Unit = {
    val internalDataWidthBits = simMem.memConfig.internalDataWidth.value // Access memCfg
    ParallaxLogger.log(s"Initializing memory at address $address with value $value (data width $valueDataWidthBits bits), internalDataWidthBits $internalDataWidthBits")
    require(
      valueDataWidthBits >= internalDataWidthBits,
      s"Value's data width ($valueDataWidthBits) must be >= internal data width ($internalDataWidthBits)."
    )
    if (valueDataWidthBits > internalDataWidthBits) {
      require(
        valueDataWidthBits % internalDataWidthBits == 0,
        s"Value's data width ($valueDataWidthBits) must be a multiple of internal data width ($internalDataWidthBits) if greater."
      )
    }
    val numInternalChunks = valueDataWidthBits / internalDataWidthBits

    simMem.io.writeEnable #= false
    simMem.io.writeAddress #= 0
    simMem.io.writeData #= 0
    clockDomain.waitSampling()

    for (i <- 0 until numInternalChunks) {
      val slice = (value >> (i * internalDataWidthBits)) & ((BigInt(1) << internalDataWidthBits) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidthBits / 8)

      simMem.io.writeEnable #= true
      simMem.io.writeAddress #= internalChunkByteAddress
      simMem.io.writeData #= slice
      clockDomain.waitSampling()
    }
    simMem.io.writeEnable #= false
    clockDomain.waitSampling()
  }
}
