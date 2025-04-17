package boson

import spinal.core._
import spinal.core.sim._

object Config {
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen/boson", // Updated directory
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH // Or LOW depending on your reset convention
    ),
    onlyStdLogicVectorAtTopLevelIo = true // Good practice for top-level
  )

  // Simulation configuration
  def sim = SimConfig.withConfig(spinal).withFstWave
}
