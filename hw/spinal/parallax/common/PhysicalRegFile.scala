package parallax.common

import spinal.lib.IMasterSlave
import spinal.core.Bundle
import spinal.lib.Flow
import spinal.core._
import spinal.lib._
import parallax.utilities.Service
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Plugin

case class PrfReadPort(
    val idxWidth: BitCount
) extends Bundle
    with IMasterSlave {

  val valid = Bool()
  val address = UInt(idxWidth)
  val rsp = Bits(32 bits)

  override def asMaster(): Unit = {
    out(valid, address)
    inWithNull(rsp)
  }
}

case class PrfWritePort(
    val idxWidth: BitCount
) extends Bundle
    with IMasterSlave {
  val valid = Bool()
  val address = UInt(idxWidth)
  val data = Bits(32 bits)
  val rsp = Bool()

  override def asMaster() = {
    out(valid, address, data)
  }
}

trait PhysicalRegFileService extends Service {
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
    with PhysicalRegFileService {

  val regIdxWidth = log2Up(numPhysRegs) bits
  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePort]()

  override def newReadPort(): PrfReadPort = {
    val port = PrfReadPort(regIdxWidth)
    readPortRequests += port
    port
  }

  override def newWritePort(): PrfWritePort = {
    val port = PrfWritePort(regIdxWidth)
    writePortRequests += port
    port
  }

  def readPort(index: Int): PrfReadPort = readPortRequests(index)
  def writePort(index: Int): PrfWritePort = writePortRequests(index)

  val logic = create late new Area {
    val regFile = Mem.fill(numPhysRegs)(Bits(dataWidth))

    readPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      val data = Mux(externalPort.address === 0, B(0, dataWidth), regFile.readAsync(address = externalPort.address))
      externalPort.rsp := data
    }

    writePortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      regFile.write(
        address = externalPort.address,
        data = externalPort.data,
        enable = externalPort.valid && (externalPort.address =/= 0)
      )
    }
  }
}
