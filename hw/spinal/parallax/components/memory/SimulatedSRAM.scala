package parallax.components.memory

import spinal.core._
import spinal.lib._

class SimulatedSRAM(c: ExtSRAMConfig) extends Component {
  val enableLog = true
  val init = false
  val io = new Bundle {
    val ram = slave(ExtSRAMIo(c))
  }

  def config = c

  val memory = Mem(Bits(c.dataWidth bits), BigInt(1) << c.addressWidth)
  if(init) memory.init(Seq.fill((BigInt(1) << c.addressWidth).toInt)(B(0, c.dataWidth bits)))

  val writeCondition = !io.ram.we_n && !io.ram.ce_n
  val readCondition = !io.ram.oe_n && !io.ram.ce_n && io.ram.we_n

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
      report(L"written ${writtenData} to ${io.ram.addr}")
    }
  }

  // ⭐ 最简单的读操作：使用循环创建延迟
  val readDataRaw = memory.readAsync(io.ram.addr)
  var delayedData = readDataRaw
  
  // 手动创建延迟链
  for (i <- 0 until c.readWaitCycles) {
    delayedData = RegNext(delayedData, init = B(0, c.dataWidth bits))
  }

  io.ram.data.read := readCondition ? delayedData | B(0, c.dataWidth bits)

  if (enableLog) {
    when(readCondition) {
      report(L"read addr=${io.ram.addr}, raw=${readDataRaw}, delayed=${delayedData}")
    }
  }
}
