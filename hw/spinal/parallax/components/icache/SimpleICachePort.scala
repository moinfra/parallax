package parallax.components.icache

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

// --- Configuration ---
case class SimpleICacheConfig(
    cacheSize: Int = (2 KiB).toInt, // Total size of cache in bytes (e.g., 2KB)
    bytePerLine: Int = 32, // Cache line size in bytes (e.g., 32 bytes)
    addressWidth: BitCount = 32 bits, // line address width (e.g., 32 bits)
    dataWidth: BitCount = 32 bits // CPU instruction width (and memory word width for simplicity)
) {
  // Derived parameters
  val lineCount: Int = cacheSize / bytePerLine
  require(isPow2(lineCount), "Cache line count must be a power of 2")
  require(isPow2(bytePerLine), "Bytes per line must be a power of 2")

  val bitsPerLine: Int = bytePerLine * 8
  val wordsPerLine: Int = bytePerLine / (dataWidth.value / 8) // Number of words per cache line, 32 / (32 / 8) = 8
  require(wordsPerLine > 0, "Line size must be at least dataWidth")
  val indexWidth: BitCount = log2Up(lineCount) bits
  val wordOffsetWidth: BitCount = log2Up(wordsPerLine) bits // Offset of a word within a line
  val byteOffsetWidth: BitCount = log2Up(bytePerLine) bits // Offset of a byte within a line
  // (tag starts above this)

  // Tag width: Address_width - Index_width - Byte_offset_in_line_width
  val tagWidth: BitCount = addressWidth - indexWidth - byteOffsetWidth

  // 低2位选择字中的哪个字节(32位=4字节)
  def lineAddress(fullAddress: UInt): UInt = fullAddress(addressWidth.value - 1 downto byteOffsetWidth.value)
  def tag(fullAddress: UInt): UInt = fullAddress(addressWidth.value - 1 downto indexWidth.value + byteOffsetWidth.value)
  def index(fullAddress: UInt): UInt = fullAddress(indexWidth.value + byteOffsetWidth.value - 1 downto byteOffsetWidth.value)
  def wordOffset(fullAddress: UInt): UInt = fullAddress(byteOffsetWidth.value - 1 downto log2Up(dataWidth.value / 8))
}

// --- CPU Interface for ICache ---
case class ICacheCpuCmd(implicit config: SimpleICacheConfig) extends Bundle {
  val address = UInt(config.addressWidth)
}

case class ICacheCpuRsp(implicit config: SimpleICacheConfig) extends Bundle {
  val instruction = Bits(config.dataWidth)
  val fault = Bool() // e.g., page fault, access error (simplified for now)
  val pc = UInt(config.addressWidth) // Echo back PC for verification/pipeline
}

case class ICacheCpuBus(implicit config: SimpleICacheConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(ICacheCpuCmd())
  val rsp = Stream(ICacheCpuRsp()) // Changed to Stream for pipelined responses

  override def asMaster(): Unit = { // CPU is master
    master(cmd)
    slave(rsp)
  }
}

// --- Memory Interface for ICache (to simulated RAM) ---
case class ICacheMemCmd(implicit config: SimpleICacheConfig) extends Bundle {
  // Requesting a full cache line.
  // lineAddress is effectively (physical_address / bytes_per_line)
  val lineAddress = UInt(config.addressWidth - config.byteOffsetWidth)
}

case class ICacheMemRsp(implicit config: SimpleICacheConfig) extends Bundle {
  val data = Bits(config.dataWidth) // Memory returns one CACHE word at a time
}

// Cacheline 读端口
case class ICacheMemBus(implicit config: SimpleICacheConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(ICacheMemCmd()) // Cache requests a line
  val rsp = Stream(ICacheMemRsp()) // Memory returns words for the line

  override def asMaster(): Unit = { // Cache is master to memory
    master(cmd)
    slave(rsp)
  }
}

// --- Flush Interface ---
case class ICacheFlushCmd() extends Bundle {
  val start = Bool()
}
case class ICacheFlushRsp() extends Bundle {
  val done = Bool()
}

// Cacheline 冲刷端口
case class ICacheFlushBus() extends Bundle with IMasterSlave {
  val cmd = Flow(ICacheFlushCmd()) // Use Flow for single-shot command
  val rsp = ICacheFlushRsp() // Response signals completion

  override def asMaster(): Unit = { // Controller is master
    master(cmd)
    in(rsp) // rsp is an input to the master
  }
}
