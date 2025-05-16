package parallax.bus

import spinal.core._
import spinal.lib._

// --- Instruction Fetch Bus ---
case class InstructionFetchCmd(addrWidth: BitCount) extends Bundle {
  val address = UInt(addrWidth)
}
case class InstructionFetchRsp(instrWidth: BitCount) extends Bundle {
  val instruction = Bits(instrWidth)
  val fault = Bool() // e.g., page fault, access error
  val pc = UInt(instrWidth) // Echo back PC, assuming addrWidth can be instrWidth for PC
}
// InstructionFetchBus: CPU IFU -> ICache/Memory
case class InstructionFetchBus(addrWidth: BitCount, instrWidth: BitCount) extends Bundle with IMasterSlave {
  val cmd = Stream(InstructionFetchCmd(addrWidth))
  val rsp = Stream(InstructionFetchRsp(instrWidth))

  override def asMaster(): Unit = { // CPU IFU is master
    master(cmd)
    slave(rsp)
  }
  // asSlave() is inferred for ICache/Memory side
}

// --- Data Memory Access Bus ---
case class DataAccessCmdPayload(addrWidth: BitCount, dataWidth: BitCount) extends Bundle {
  val address = UInt(addrWidth)
  val data    = Bits(dataWidth) // Data for write operations
  val isWrite = Bool()
  // Size: 00=byte, 01=half-word, 10=word (assuming dataWidth is word size)
  // Adjust size bits based on max alignment (e.g. log2Up(log2Up(dataWidth/8)+1))
  val size    = UInt(2 bits) // Example: For 32-bit dataWidth, supports byte, half, word
  // Optional: byte enables for sub-word writes if size indicates word but only some bytes change
  // val byteEnable = Bits(dataWidth/8 bits)
}
case class DataAccessRspPayload(dataWidth: BitCount) extends Bundle {
  val readData = Bits(dataWidth)
  val error    = Bool() // e.g., bus error, alignment error, protection fault
}
// DataMemoryAccessBus: CPU LSU/LSQ -> DCache/Memory
case class DataMemoryAccessBus(addrWidth: BitCount, dataWidth: BitCount) extends Bundle with IMasterSlave {
  val cmd = Stream(DataAccessCmdPayload(addrWidth, dataWidth))
  val rsp = Stream(DataAccessRspPayload(dataWidth))

  override def asMaster(): Unit = { // CPU LSU/LSQ is master
    master(cmd)
    slave(rsp)
  }
  // asSlave() is inferred for DCache/Memory side
}


// --- System Flush Bus (Simplified) ---
case class SystemFlushCmd() extends Bundle {
  val start = Bool()
  // Could add flags for IFlush, DFlush, specific address, etc.
}
case class SystemFlushRsp() extends Bundle {
  val done = Bool()
}
case class SystemFlushBus() extends Bundle with IMasterSlave {
  val cmd = Flow(SystemFlushCmd())
  val rsp = SystemFlushRsp() // Output from subsystem

  override def asMaster(): Unit = { // Controller is master
    master(cmd)
    in(rsp)
  }
  // asSlave() is inferred for subsystem
}
