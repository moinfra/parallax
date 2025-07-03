package parallax.components.memory

import spinal.core._
import spinal.lib._

class SimulatedSRAM(val config: ExtSRAMConfig) extends Component {
  val prefix = this.getClass.getName.replace("$", "")
  val enableLog = false
  val init = false
  val io = new Bundle {
    val ram = slave(ExtSRAMIo(config))

    val tb_writeEnable = in Bool () default (False)
    val tb_writeAddress = in UInt (config.addressWidth bits) default (U(0, config.addressWidth bits))
    val tb_writeData = in Bits (config.dataWidth bits) default (B(0, config.dataWidth bits))

    val tb_readEnable = in Bool () default (False)
    val tb_readAddress = in UInt (config.addressWidth bits) default (U(0, config.addressWidth bits)) default (U(
      0,
      config.addressWidth bits
    ))
    val tb_readData = out Bits (config.dataWidth bits)
  }

  val addrInRange = io.ram.addr <= config.internalMaxByteAddr
  val writeCondition = !io.ram.we_n && !io.ram.ce_n && !io.tb_writeEnable
  val readCondition = !io.ram.oe_n && !io.ram.ce_n && io.ram.we_n
  val wordAddr = (io.ram.addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)

  assert(!(readCondition && writeCondition), "Read and write cannot be active simultaneously")
  assert(!(io.tb_writeEnable && writeCondition), "Test/normal write conflict")

  val mem = Mem(Bits(config.dataWidth bits), config.internalWordCount)
  if (init) mem.init(Seq.fill((config.internalWordCount).toInt)(B(0, config.dataWidth bits)))

  private val tb_writeLogic = new Area {
    when(io.tb_writeEnable) {
      val addr = io.tb_writeAddress
      when(addr <= config.internalMaxByteAddr) {
        if (enableLog) {
          report(L"$prefix TB_WRITE: Addr=${addr}, Data=${io.tb_writeData}")
        }
        val wordAddr = (addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)
        mem.write(address = wordAddr, data = io.tb_writeData)
      } otherwise {
        report(
          L"$prefix TB_WRITE: ILLEGAL ADDRESS Addr=${addr} (max addr ${config.internalMaxByteAddr} for ${config.sizeBytes} bytes of memory)"
        )
      }
    }
  }

  private val tb_readLogic = new Area {
    io.tb_readData.assignDontCare()
    when(io.tb_readEnable) {
      val addr = io.tb_readAddress
      when(addr <= config.internalMaxByteAddr) {
        if (enableLog) {
          report(L"$prefix TB_READ: Addr=${addr}")
        }
        val wordAddr = (addr >> log2Up(config.bytesPerWord)).resize(config.internalWordAddrWidth)
        io.tb_readData := mem.readAsync(address = wordAddr)
      } otherwise {
        report(
          L"$prefix TB_READ: ILLEGAL ADDRESS Addr=${addr} (max addr ${config.internalMaxByteAddr} for ${config.sizeBytes} bytes of memory)"
        )
      }
    }
  }

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
      byteEnableMask(byteIdx * 8 + 7 downto byteIdx * 8) := (default -> byteMask)
    }

    when(addrInRange && writeCondition) {
      // 使用 Mem.write 的 mask 参数
      mem.write(
        address = wordAddr,
        data = io.ram.data.write,
        mask = byteEnableMask // 使用转换后的高有效 mask
      )
      if (enableLog) {
        report(L"$prefix written ${io.ram.data.write} to ${io.ram.addr} with mask ${byteEnableMask}")
      }
    }

  }

  private val readLogic = new Area {
    // 修复版本的SimulatedSRAM读逻辑
    val readDataRaw = mem.readAsync(wordAddr)

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
            L"READ: addr=${io.ram.addr}",
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
