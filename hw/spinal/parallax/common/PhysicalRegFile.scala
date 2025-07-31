package parallax.common

import spinal.lib.IMasterSlave
import spinal.core.Bundle
import spinal.lib.Flow
import spinal.core._
import spinal.lib._
import parallax.utilities.Service
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Plugin
import parallax.utilities.ParallaxLogger
import parallax.utilities.LockedImpl
import parallax.utilities.ParallaxSim

case class PrfReadPort(
    val idxWidth: BitCount,
    val dataWidth: BitCount = 32 bits
) extends Bundle
    with IMasterSlave {

  val valid = Bool()
  val address = UInt(idxWidth)
  val rsp = Bits(dataWidth)

  override def asMaster(): Unit = {
    out(valid, address)
    inWithNull(rsp)
  }
}

case class PrfWritePort(
    val idxWidth: BitCount,
    val dataWidth: BitCount = 32 bits
) extends Bundle
    with IMasterSlave {
  val valid = Bool()
  val address = UInt(idxWidth)
  val data = Bits(dataWidth)

  override def asMaster() = {
    out(valid, address, data)
  }
}

trait PhysicalRegFileService extends Service with LockedImpl {
  def newPrfReadPort(traceName: String = "<null>", isFromDebugger: Boolean = false): PrfReadPort
  def newDebuggerReadPort(traceName: String = "debugger"): PrfReadPort
  def newPrfWritePort(traceName: String = "<null>"): PrfWritePort

  def readPort(index: Int): PrfReadPort
  def writePort(index: Int): PrfWritePort

  def isGprService(): Boolean = true
  def isFprService(): Boolean = false
}

class PhysicalRegFilePlugin(
    numPhysRegs: Int,
    dataWidth: BitCount = 32 bits
) extends Plugin
    with PhysicalRegFileService
    with LockedImpl {

  private case class PrfReadPortRequest(port: PrfReadPort, traceName: String, isFromDebugger: Boolean)
  private case class PrfWritePortRequest(port: PrfWritePort, traceName: String)

  val regIdxWidth = log2Up(numPhysRegs) bits
  private val readPortRequests = ArrayBuffer[PrfReadPortRequest]() // 类型已更新
  private val writePortRequests = ArrayBuffer[PrfWritePortRequest]()

  private var logicExecuted = false

  // *** CHANGE 3: newPrfReadPort 方法实现更新 ***
  override def newPrfReadPort(traceName: String = "<null>", isFromDebugger: Boolean = false): PrfReadPort = {
    this.framework.requireEarly()
    val port = PrfReadPort(regIdxWidth, dataWidth)
    readPortRequests += PrfReadPortRequest(port, traceName, isFromDebugger)
    port
  }

  override def newDebuggerReadPort(traceName: String = "debugger"): PrfReadPort = {
    newPrfReadPort(traceName, isFromDebugger = true)
  }

  override def newPrfWritePort(traceName: String = "<null>"): PrfWritePort = {
    this.framework.requireEarly()
    val port = PrfWritePort(regIdxWidth, dataWidth)
    writePortRequests += PrfWritePortRequest(port, traceName)
    port
  }

  def readPort(index: Int): PrfReadPort = readPortRequests(index).port
  def writePort(index: Int): PrfWritePort = writePortRequests(index).port

  val setup = create early new Area {
    ParallaxLogger.log("[PRegPlugin] early")
  }

  val logic = create late new Area {
    ParallaxLogger.log("[PRegPlugin] late")
    lock.await()
    ParallaxLogger.log("[PRegPlugin] Physical Register File logic is being generated.")

    logicExecuted = true

    val regFile = Vec(RegInit(B(0, dataWidth)), numPhysRegs)

    readPortRequests.foreach { req =>
      req.port.rsp := Mux(req.port.address === 0, B(0, dataWidth), regFile(req.port.address))
      
      if (!req.isFromDebugger) {
        when(req.port.valid) {
          ParallaxSim.log(
            L"[PRegPlugin] Read from `${req.traceName}` on reg[p${req.port.address}] -> ${req.port.rsp}"
          )
        }
      }
    }
    
    if (writePortRequests.nonEmpty) {
      val actualWritePorts = writePortRequests.map(_.port)

      // **核心冲突检测逻辑**
      // 遍历所有可能的写端口对 (i, j) where i < j
      for (i <- 0 until actualWritePorts.length; j <- i + 1 until actualWritePorts.length) {
        val portA = actualWritePorts(i)
        val portB = actualWritePorts(j)

        // 断言：如果两个不同的端口都有效，它们的目标地址必须不相同。
        assert(
          !(portA.valid && portB.valid && portA.address === portA.address),
          L"CRITICAL ERROR: Concurrent write to the same physical register p${portA.address} detected between " :+
          L"${writePortRequests(i).traceName} and ${writePortRequests(j).traceName}. " :+
          "This is a design flaw in the pipeline scheduling logic."
        )
      }

      // **执行所有有效的写操作**
      // 因为我们已经断言没有地址冲突，所以可以安全地为每个有效的写请求生成写逻辑。
      // 综合工具会根据这些并行的写操作来推断所需的多端口内存结构。
      writePortRequests.foreach { req =>
        when(req.port.valid && req.port.address =/= 0) {
          regFile(req.port.address) := req.port.data

          // 仿真日志
          ParallaxSim.log(
            L"[PRegPlugin] Write from `${req.traceName}` to reg[p${req.port.address}] with data ${req.port.data}"
          )
        }
      }

      ParallaxLogger.log(s"[PRegPlugin] ${writePortRequests.size} write ports connected. Concurrent writes to the same address are forbidden by assertion.")
    }
  }
}
