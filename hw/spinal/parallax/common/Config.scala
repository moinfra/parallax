package parallax.common

import spinal.core._
import spinal.lib._

object Config {
  val ARCH_REG_COUNT = 32
  val XLEN = 32
  val ARCH_REG_WIDTH = log2Up(ARCH_REG_COUNT) bits
  val PHYS_REG_COUNT = 64
  val PHYS_REG_TAG_WIDTH = log2Up(PHYS_REG_COUNT) bits
  val R0_PHYS_TAG = U(0, PHYS_REG_TAG_WIDTH)
}
