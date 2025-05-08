package boson.demo2.components.memory

import spinal.core._
import spinal.lib._

// Configuration for the generic memory bus
case class GenericMemoryBusConfig(
    addressWidth: Int, // Byte address
    dataWidth: Int
)

// Command from Master to Slave
case class GenericMemoryCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth bits)
  val isWrite = Bool()
  val writeData = Bits(config.dataWidth bits)
}

// Response from Slave to Master
case class GenericMemoryRsp(config: GenericMemoryBusConfig) extends Bundle {
  val readData = Bits(config.dataWidth bits)
  val error = Bool()
}

// The bus itself
case class GenericMemoryBus(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(GenericMemoryCmd(config))
  val rsp = Stream(GenericMemoryRsp(config))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}
