package parallax.components.memory

import spinal.core._
import spinal.lib._

// Configuration for the generic memory bus
case class GenericMemoryBusConfig(
    addressWidth: BitCount, // Byte address
    dataWidth: BitCount
)

object GenericMemoryBusOpcode extends SpinalEnum { val OP_READ, OP_WRITE = newElement() }

// Command from Master to Slave
case class GenericMemoryBusCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth)
  val opcode = GenericMemoryBusOpcode()
  val writeData = Bits(config.dataWidth)
  val writeByteEnables = Bits(config.dataWidth.value / 8 bits)
}

// Response from Slave to Master
case class GenericMemoryBusRsp(config: GenericMemoryBusConfig) extends Bundle {
  val readData = Bits(config.dataWidth)
  val error = Bool()
}

// The bus itself
case class GenericMemoryBus(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(GenericMemoryBusCmd(config))
  val rsp = Stream(GenericMemoryBusRsp(config))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}

case class SimpleMemoryCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth)
  val isWrite = Bool()
  val writeData = Bits(config.dataWidth)
}

case class SimpleMemoryRsp(config: GenericMemoryBusConfig) extends Bundle {
  val readData = Bits(config.dataWidth)
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
