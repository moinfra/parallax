package parallax.components.memory

import spinal.core._
import spinal.lib._

object MemoryInitHelper {

  /** Pads a sequence of BigInt instructions with zeros to a specified total word count.
    * This is typically used for initializing memories where the initial content
    * is smaller than the total memory size.
    *
    * @param instructions The sequence of BigInt instructions to be placed at the beginning of memory.
    * @param totalWordCount The total number of words the memory can hold.
    * @param paddingValue The BigInt value to use for padding (default is 0).
    * @return A new sequence of BigInts with the instructions followed by padding values,
    *         matching totalWordCount in length.
    * @throws IllegalArgumentException if the instructions sequence is longer than totalWordCount.
    */
  def padInstructions(
      instructions: Seq[BigInt],
      totalWordCount: BigInt, // Use BigInt for totalWordCount to match config.internalWordCount
      paddingValue: BigInt = BigInt(0)
  ): Seq[BigInt] = {
    require(
      instructions.length <= totalWordCount.toInt,
      s"Initial instructions (${instructions.length}) cannot be longer than total memory word count (${totalWordCount})."
    )

    val wordsToPad = totalWordCount.toInt - instructions.length
    instructions ++ Seq.fill(wordsToPad)(paddingValue)
  }
}

class SimulatedSRAM(
    val config: SRAMConfig,
    val initialContent: Seq[BigInt] = Seq()
) extends Component {
  val prefix = this.getClass.getName.replace("$", "")
  val enableLog = false // Temporarily enable for debugging

  val io = new Bundle {
    val ram = slave(SRAMIO(config))

    val tb_writeEnable = in Bool () default (False)
    val tb_writeAddress = in UInt (config.byteAddrWidth bits) default (U(0, config.byteAddrWidth bits))
    val tb_writeData = in Bits (config.dataWidth bits) default (B(0, config.dataWidth bits))

    val tb_readEnable = in Bool () default (False)
    val tb_readAddress = in UInt (config.byteAddrWidth bits) default (U(0, config.byteAddrWidth bits))
    val tb_readData = out Bits (config.dataWidth bits)
  }
  // This line had a duplicate `default` which is a syntax error, I'm removing the second one.
  // io.tb_readAddress.setDefault(U(0, config.addressWidth bits))

  // --- MODIFIED: Conditional address range check ---
  val addrInRange = if (config.useWordAddressing) {
    // In word-addressing mode, the incoming address is a word address.
    // Check it against the maximum word address.
    io.ram.addr <= config.internalWordMaxAddr
  } else {
    // In byte-addressing mode, check against the maximum byte address.
    io.ram.addr <= config.internalMaxByteAddr
  }

  val writeCondition = !io.ram.we_n && !io.ram.ce_n && !io.tb_writeEnable
  val readCondition = !io.ram.oe_n && !io.ram.ce_n && io.ram.we_n

  // --- MODIFIED: Conditional word address calculation ---
  val wordAddr = if (config.useWordAddressing) {
    // In word-addressing mode, the controller has already done the conversion.
    // The incoming address is the word address.
    io.ram.addr.resize(config.internalWordAddrWidth)
  } else {
    // In byte-addressing mode, convert the byte address to a word address.
    (io.ram.addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)
  }

  assert(!(readCondition && writeCondition), "Read and write cannot be active simultaneously")
  assert(!(io.tb_writeEnable && writeCondition), "Test/normal write conflict")

  // Using config.internalWordCount as requested.
  val mem = Mem(Bits(config.dataWidth bits), wordCount = config.internalWordCount)
  // if (init) mem.init(Seq.fill((config.internalWordCount).toInt)(B(0, config.dataWidth bits)))
  if (initialContent.nonEmpty) {
    val paddedInitialContent = MemoryInitHelper.padInstructions(
      instructions = initialContent,
      totalWordCount = config.internalWordCount, // Pass BigInt directly
      paddingValue = BigInt(0) // Assuming 0 is NOP or desired default
    )

    // Convert BigInt to Bits for initialization
    val initialBitsSeq = paddedInitialContent.map(value => B(value, config.dataWidth bits))
    
    // The assert for length matching is now implicitly handled by MemoryInitHelper.padInstructions
    // but a final check here is still good for robustness if padding logic changes.
    assert(initialBitsSeq.length == config.internalWordCount.toInt, 
           s"Internal Error: Padded initial content size (${initialBitsSeq.length}) does not match memory word count (${config.internalWordCount}). This should not happen if MemoryInitHelper works correctly.")

    mem.init(initialBitsSeq)
  } 
  // --- NO CHANGE TO TB LOGIC ---
  // The testbench helper ports always work with byte addresses from the test's perspective,
  // so their internal logic to convert to a word address is always correct.
  private val tb_writeLogic = new Area {
    when(io.tb_writeEnable) {
      val addr = io.tb_writeAddress // This is a byte address
      // The max check for TB should be against byte address size
      when(addr <= config.internalMaxByteAddr) {
        if (enableLog) {
          report(L"TB_WRITE: Addr=${addr}, Data=${io.tb_writeData}")
        }
        val wordAddrForTb = (addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)
        mem.write(address = wordAddrForTb, data = io.tb_writeData)
      } otherwise {
        report(
          L"TB_WRITE: ILLEGAL ADDRESS Addr=${addr} (max addr ${config.internalMaxByteAddr} for ${config.sizeBytes} bytes of memory)"
        )
      }
    }
  }

  private val tb_readLogic = new Area {
    io.tb_readData.assignDontCare()
    when(io.tb_readEnable) {
      val addr = io.tb_readAddress // This is a byte address
      // The max check for TB should be against byte address size
      when(addr <= config.internalMaxByteAddr) {
        if (enableLog) {
          report(L"TB_READ: Addr=${addr}")
        }
        val wordAddrForTb = (addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)
        io.tb_readData := mem.readAsync(address = wordAddrForTb)
      } otherwise {
        report(
          L"TB_READ: ILLEGAL ADDRESS Addr=${addr} (max addr ${config.internalMaxByteAddr} for ${config.sizeBytes} bytes of memory)"
        )
      }
    }
  }

  // --- NO CHANGE TO WRITE/READ LOGIC ---
  // The logic below now correctly uses the conditionally defined `wordAddr` and `addrInRange`
  private val writeLogic = new Area {
    // 写操作

    // 将低有效的 be_n 转换为高有效的 mask
    // be_n 是 (dataWidth / 8) bits，每个位对应一个字节
    // mask 应该是 dataWidth bits，每个位对应一个数据位
    val byteEnableMask = Bits(config.dataWidth bits)
    for (byteIdx <- 0 until config.bytesPerWord) {
      // 如果 be_n(byteIdx) 为低（0），则对应字节被使能，mask 对应位为高（1）
      // 如果 be_n(byteIdx) 为高（1），则对应字节被禁用，mask 对应位为低（0）
      val byteMask = !io.ram.be_n(byteIdx) // 反转 be_n 的逻辑
      byteEnableMask(byteIdx * 8, 8 bits) := (default -> byteMask)
    }

    when(addrInRange && writeCondition) {
      // 使用 Mem.write 的 mask 参数
      mem.write(
        address = wordAddr, // This now uses the correct wordAddr
        data = io.ram.data.write,
        mask = byteEnableMask // 使用转换后的高有效 mask
      )
      if (enableLog) {
        report(
          L"written ${io.ram.data.write} to ${io.ram.addr} (word addr ${wordAddr}) with mask ${byteEnableMask}"
        )
      }
    }
  }

  private val readLogic = new Area {
    // 修复版本的SimulatedSRAM读逻辑
    val readDataRaw = mem.readAsync(wordAddr) // This now uses the correct wordAddr

    // 更明确的延迟实现
    val delayedData = if (config.readWaitCycles == 0) {
      readDataRaw
    } else {
      var delayed = readDataRaw
      for (i <- 0 until config.readWaitCycles) {
        delayed = RegNext(delayed, init = B(0, config.dataWidth bits))
      }
      delayed
    }

// 简化读取逻辑
    io.ram.data.read := Mux(readCondition && addrInRange, delayedData, B(0, config.dataWidth bits))

    if (enableLog) {
      when(readCondition) {
        report(
          Seq(
            L"READ: addr=${io.ram.addr} (word addr ${wordAddr})",
            L", raw=${readDataRaw}",
            L", delayed=${delayedData}",
            L", waitCycles=${config.readWaitCycles}",
            L", output=${io.ram.data.read}"
          )
        )
      }
    }

    if (config.readWaitCycles == 0) {
      when(readCondition) {
        assert(
          delayedData === readDataRaw,
          L"When readWaitCycles=0, delayed should equal raw. raw=${readDataRaw}, delayed=${delayedData}"
        )
      }
    }
  }
}
