package parallax

import spinal.lib.IMasterSlave
import spinal.core.Bundle
import spinal.lib.Flow
import spinal.core._
import spinal.lib._
import parallax.utilities.Service
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Plugin
import parallax.common._
import spinal.core.sim.SimDataPimper

case class PrfReadPort() extends Bundle with IMasterSlave {

  val valid = Bool()
  val address = UInt(Config.PHYS_REG_TAG_WIDTH)
  val rsp = Bits(32 bits)

  override def asMaster(): Unit = {
    out(valid, address)
    inWithNull(rsp)
  }
}

case class PrfWritePort() extends Bundle with IMasterSlave {
  val valid = Bool()
  val address = UInt(Config.PHYS_REG_TAG_WIDTH)
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
}

trait PhysicalRegFreeService extends Service {
  def getFreePort(): Flow[UInt] // Port for Commit to send stale tags
}

class PhysicalRegFilePlugin(
    numPhysRegs: Int = Config.PHYS_REG_COUNT,
    dataWidth: BitCount = 32 bits
) extends Plugin
    with PhysicalRegFileService {

  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePort]()

  override def newReadPort(): PrfReadPort = {
    val port = PrfReadPort()
    readPortRequests += port
    port
  }

  override def newWritePort(): PrfWritePort = {
    val port = PrfWritePort()
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
