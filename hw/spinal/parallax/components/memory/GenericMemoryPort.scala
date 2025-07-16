package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import parallax.utilities.Formattable

// Configuration for the generic memory bus
case class GenericMemoryBusConfig(
    addressWidth: BitCount, // Byte address
    dataWidth: BitCount,
    // Add an optional ID field if AXI ID mapping is desired directly from GenericBus
    useId: Boolean = false,
    idWidth: BitCount = 4 bits // Example, only used if useId is true
)


object GenericMemoryBusOpcode extends SpinalEnum { val OP_READ, OP_WRITE = newElement() }

// Command from Master to Slave
case class GenericMemoryBusCmd(config: GenericMemoryBusConfig) extends Bundle {
  val address = UInt(config.addressWidth)
  val opcode = GenericMemoryBusOpcode()
  val writeData = Bits(config.dataWidth)
  val writeByteEnables = Bits(config.dataWidth.value / 8 bits)
  val id = if (config.useId) UInt(config.idWidth) else null // Optional ID
}

// Response from Slave to Master
case class GenericMemoryBusRsp(config: GenericMemoryBusConfig) extends Bundle {
  val readData = Bits(config.dataWidth)
  val error = Bool()
  val id = if (config.useId) UInt(config.idWidth) else null // Optional ID
}

// The bus itself
case class GenericMemoryBus(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(GenericMemoryBusCmd(config))
  val rsp = Stream(GenericMemoryBusRsp(config))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }

  def <<(m: GenericMemoryBus): Unit = {
    m.cmd >> this.cmd // this.cmd is slave, m.cmd is master
    this.rsp >> m.rsp // this.rsp is master, m.rsp is slave
    // Note: Original was m.rsp << this.rsp, which implies this.rsp is slave
    // If 'this' is the master interface being converted, then this.rsp is slave (receives data)
    // and m.rsp (if m is another GenericMemoryBus master) would also be slave.
    // The operator '<<' here is for connecting two GenericMemoryBus interfaces.
    // My previous interpretation of this operator for the current context might be off.
    // Let's focus on toAxi4Shared.
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

// --- Read Channel ---
case class SplitGmbReadCmd(config: GenericMemoryBusConfig) extends Bundle  with Formattable {
  val address = UInt(config.addressWidth)
  val id = if (config.useId) UInt(config.idWidth) else null

  override def format: Seq[Any] = {
    Seq(
      L"SplitGmbReadCmd(",
      L"address=${address},",
      L"id=${if (config.useId) id else "None"}",
      L")"
    )
  }
}
case class SplitGmbReadRsp(config: GenericMemoryBusConfig) extends Bundle  with Formattable {
  val data = Bits(config.dataWidth)
  val error = Bool()
  val id = if (config.useId) UInt(config.idWidth) else null

  override def format: Seq[Any] = {
    Seq(
      L"SplitGmbReadRsp(",
      L"data=${data},",
      L"error=${error},",
      L"id=${if (config.useId) id else "None"}",
      L")"
    )
  }
}
case class SplitGmbReadChannel(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(SplitGmbReadCmd(config))
  val rsp = Stream(SplitGmbReadRsp(config))
  override def asMaster(): Unit = { master(cmd); slave(rsp) }
}

// --- Write Channel ---
case class SplitGmbWriteCmd(config: GenericMemoryBusConfig) extends Bundle with Formattable {
  val address = UInt(config.addressWidth)
  val data = Bits(config.dataWidth)
  val byteEnables = Bits(config.dataWidth.value / 8 bits)
  val id = if (config.useId) UInt(config.idWidth) else null
  val last = Bool()

  override def format: Seq[Any] = {
    Seq(
      L"SplitGmbWriteCmd(",
      L"address=${address},",
      L"data=${data},",
      L"byteEnables=${byteEnables},",
      L"id=${if (config.useId) id else "None"},",
      L"last=${last}",
      L")"
    )
  }
}
case class SplitGmbWriteRsp(config: GenericMemoryBusConfig) extends Bundle  with Formattable {
  val error = Bool()
  val id = if (config.useId) UInt(config.idWidth) else null

  override def format: Seq[Any] = {
    Seq(
      L"SplitGmbWriteRsp(",
      L"error=${error},",
      L"id=${if (config.useId) id else "None"}",
      L")"
    )
  }
}
case class SplitGmbWriteChannel(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(SplitGmbWriteCmd(config)) // This stream now contains both address and data for write
  val rsp = Stream(SplitGmbWriteRsp(config))
  override def asMaster(): Unit = { master(cmd); slave(rsp) }
}

// --- The Split Generic Memory Bus ---
case class SplitGenericMemoryBus(config: GenericMemoryBusConfig) extends Bundle with IMasterSlave {
  val read = SplitGmbReadChannel(config)
  val write = SplitGmbWriteChannel(config)

  override def asMaster(): Unit = {
    master(read) // read channel is master
    master(write) // write channel is master
  }
  // Note: If this bus is used as a SLAVE interface on a memory component,
  // then read.cmd would be slave, read.rsp master, etc.
  // The asMaster() here defines how it behaves when this SplitGmb itself is a master port.
}
