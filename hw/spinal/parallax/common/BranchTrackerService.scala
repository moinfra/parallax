package parallax.common

import parallax.utilities.Service

import spinal.core._
import spinal.lib._
import parallax.utilities.Plugin

trait BranchTrackerService extends Service {
  def getCountListeningPortReg(): UInt
  def getCountPortReg(): UInt
  def isExceedLimit(bypassed: UInt): Bool
  def doIncrement(): Unit
  def doDecrement(): Unit
}

class BranchTrackerPlugin extends Plugin with BranchTrackerService {
    private val maxCount: Int = 1
    private val counterWidth = 4 bits
    private val countReg: UInt = Reg(UInt(counterWidth)) init(0)
    private val limit: Int = 1
    require(log2Up(limit) <= counterWidth.value, "Limit should be less than or equal to maxCount")
    override def getCountListeningPortReg: UInt = countReg
    override def getCountPortReg: UInt = countReg
    override def isExceedLimit(bypassed: UInt): Bool = countReg + bypassed > U(limit, counterWidth)
    override def doIncrement(): Unit = {
      assert(countReg < U(maxCount, counterWidth), "Count should be less than maxCount")
      countReg := countReg + 1
      report(L"Incremented count to $countReg")
    }
    override def doDecrement(): Unit = {
      assert(countReg > 0, "Count should be greater than 0")
      countReg := countReg - 1
      report(L"Decremented count to $countReg")
    }
    // report(L"Count $countReg")

}
