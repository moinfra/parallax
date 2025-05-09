package boson.demo2.components.memory

import spinal.core._
import spinal.lib._

// Configuration for the generic memory bus
case class GenericMemoryBusConfig(
    addressWidth: Int, // Byte address
    dataWidth: Int
)

object GenericMemoryBusOpcode extends SpinalEnum { val OP_READ, OP_WRITE = newElement() }

// Command from Master to Slave
case class GenericMemoryCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth bits)
  val opcode = GenericMemoryBusOpcode()
  val writeData = Bits(config.dataWidth bits)
  val writeByteEnables = Bits(config.dataWidth / 8 bits)
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

case class SimpleMemoryCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth bits)
  val isWrite = Bool()
  val writeData = Bits(config.dataWidth bits)
}

case class SimpleMemoryRsp(config: GenericMemoryBusConfig) extends Bundle {
  val readData = Bits(config.dataWidth bits)
  val error = Bool()
}

// 一个只能简单的串行读写的访存总线
case class SimpleMemoryBus(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(SimpleMemoryCmd(config))
  val rsp = Stream(SimpleMemoryRsp(config))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}
