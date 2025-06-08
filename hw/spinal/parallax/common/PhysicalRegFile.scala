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
    val dataWidth: BitCount = 32 bits,
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
  def newReadPort(): PrfReadPort
  def newWritePort(): PrfWritePort

  def readPort(index: Int): PrfReadPort
  def writePort(index: Int): PrfWritePort

  def isGprService(): Boolean = true
  def isFprService(): Boolean = false
}

trait PhysicalRegFreeService extends Service {
  def getFreePort(): Flow[UInt] // Port for Commit to send stale tags
}

class PhysicalRegFilePlugin(
    numPhysRegs: Int,
    dataWidth: BitCount = 32 bits
) extends Plugin
    with PhysicalRegFileService
    with LockedImpl {

  val regIdxWidth = log2Up(numPhysRegs) bits
  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePort]()

  override def newReadPort(): PrfReadPort = {
    val port = PrfReadPort(regIdxWidth, dataWidth)
    readPortRequests += port
    port
  }

  override def newWritePort(): PrfWritePort = {
    val port = PrfWritePort(regIdxWidth, dataWidth)
    writePortRequests += port
    port
  }

  def readPort(index: Int): PrfReadPort = readPortRequests(index)
  def writePort(index: Int): PrfWritePort = writePortRequests(index)

  val setup = create early new Area {
    ParallaxLogger.log("[PRegPlugin] 物理寄存器堆已创建")
  }

  val logic = create late new Area {
    ParallaxLogger.log("[PRegPlugin] 物理寄存器在生成逻辑前，等待依赖它的插件就绪")
    lock.await()
    ParallaxLogger.log("[PRegPlugin] 好，物理寄存器开始连接读写逻辑")

    val regFile = Mem.fill(numPhysRegs)(Bits(dataWidth))

    readPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      val data = Mux(externalPort.address === 0, B(0, dataWidth), regFile.readAsync(address = externalPort.address))
      externalPort.rsp := data
      ParallaxLogger.log(s"[PRegPlugin] 物理寄存器堆读端口 $i 已连接")
    }

    writePortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      regFile.write(
        address = externalPort.address,
        data = externalPort.data,
        enable = externalPort.valid && (externalPort.address =/= 0)
      )
      ParallaxLogger.log(s"[PRegPlugin] 物理寄存器堆写端口 $i 已连接")
    }
  }
}
