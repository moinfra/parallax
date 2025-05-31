package parallax.components.memory

import spinal.core._
import spinal.lib._

class SimulatedSRAM(c: ExtSRAMConfig) extends Component {
  val sizeBytes = BigInt(1) << c.addressWidth
  val prefix = this.getClass.getName.replace("$", "")
  val enableLog = true
  val init = false
  val io = new Bundle {
    val ram = slave(ExtSRAMIo(c))

    val tb_writeEnable = in Bool () default (False)
    val tb_writeAddress = in UInt (c.addressWidth bits) default (U(0, c.addressWidth bits))
    val tb_writeData = in Bits (c.dataWidth bits) default (B(0, c.dataWidth bits))
  }

  def config = c
  def tb_writeLogic(mem: Mem[Bits]) = {
    when(io.tb_writeEnable) {
      val addr = io.tb_writeAddress
      when(addr <= c.internalMaxAddr) {
        if (enableLog) {
          report(L"$prefix TB_WRITE: Addr=${addr}, Data=${io.tb_writeData}")
        }
        mem.write(address = addr, data = io.tb_writeData)
      } otherwise {
        report(L"$prefix TB_WRITE: ILLEGAL ADDRESS Addr=${addr} (max addr ${c.internalMaxAddr} for ${sizeBytes} bytes of memory)")
      }
    }
  }

  val memory = Mem(Bits(c.dataWidth bits), sizeBytes)
  if (init) memory.init(Seq.fill((sizeBytes).toInt)(B(0, c.dataWidth bits)))
  tb_writeLogic(memory)

  val writeCondition = !io.ram.we_n && !io.ram.ce_n
  val readCondition = !io.ram.oe_n && !io.ram.ce_n && io.ram.we_n
  assert(!(readCondition && writeCondition), "Read and write cannot be active simultaneously")

  // 写操作（保持原来的逻辑）
  when(writeCondition && io.ram.data.writeEnable) {
    val dataFromController = io.ram.data.write
    val currentMemValue = memory.readAsync(io.ram.addr)
    val finalWriteDataVec = Vec(Bits(8 bits), c.dataWidth / 8)
    val inputDataBytes = dataFromController.subdivideIn(8 bits)
    val currentMemValueBytes = currentMemValue.subdivideIn(8 bits)

    for (byteIdx <- 0 until (c.dataWidth / 8)) {
      when(!io.ram.be_n(byteIdx)) {
        finalWriteDataVec(byteIdx) := inputDataBytes(byteIdx)
      } otherwise {
        finalWriteDataVec(byteIdx) := currentMemValueBytes(byteIdx)
      }
    }

    val writtenData = Cat(finalWriteDataVec)
    memory.write(io.ram.addr, writtenData)
    if (enableLog) {
      report(L"$prefix written ${writtenData} to ${io.ram.addr}")
    }
  }

// 修复版本的SimulatedSRAM读逻辑
  val readDataRaw = memory.readAsync(io.ram.addr)

// 更明确的延迟实现
  val delayedData = if (c.readWaitCycles == 0) {
    readDataRaw
  } else {
    var delayed = readDataRaw
    for (i <- 0 until c.readWaitCycles) {
      delayed = RegNext(delayed, init = B(0, c.dataWidth bits))
    }
    delayed
  }

// 简化读取逻辑
  io.ram.data.read := Mux(readCondition, delayedData, B(0, c.dataWidth bits))

  if (enableLog) {
    when(readCondition) {
      report(
        Seq(
          L"READ: addr=${io.ram.addr}",
          L", raw=${readDataRaw}",
          L", delayed=${delayedData}",
          L", waitCycles=${c.readWaitCycles}",
          L", output=${io.ram.data.read}"
        )
      )
    }
  }

  if (c.readWaitCycles == 0) {
    when(readCondition) {
      assert(
        delayedData === readDataRaw,
        L"When readWaitCycles=0, delayed should equal raw. raw=${readDataRaw}, delayed=${delayedData}"
      )
    }
  }
}
