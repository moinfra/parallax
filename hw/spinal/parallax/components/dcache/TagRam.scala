package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.pipeline._

case class TagRamReadCmd(lineIndexWidth: Int) extends Bundle {
  val index = UInt(lineIndexWidth bits)
}

case class TagRamWriteCmd(lineIndexWidth: Int, wayCount: Int, tagConfig: CacheTag) extends Bundle {
  val index = UInt(lineIndexWidth bits)
  val way = UInt(log2Up(wayCount) bits)
  val data = cloneOf(tagConfig)
}

case class TagRamIo(p: DataCacheParameters) extends Bundle {
  val readCmd = slave(Stream(TagRamReadCmd(p.lineIndexWidth)))
  val readRsp = master(Stream(Vec.fill(p.wayCount)(CacheTag(p))))
  val writeCmd = slave(Stream(TagRamWriteCmd(p.lineIndexWidth, p.wayCount, CacheTag(p))))
}

class TagRamComponent(p: DataCacheParameters) extends Component {
  val io = TagRamIo(p)
  val enableLog = false

  val ways_logic = Range(0, p.wayCount).map { wayIdx =>
    new Area {
      val mem = Mem.fill(p.linePerWay)(CacheTag(p)) init {
        Seq.fill(p.linePerWay)(CacheTag(p).setDefault())
      }

      val readDataForThisWay = mem.readAsync(
        address = io.readCmd.payload.index
      )
      if (enableLog) { report(L"Way ${wayIdx}: readDataForThisWay = ${readDataForThisWay.format}") }

      val isTargetThisWay = io.writeCmd.valid && io.writeCmd.payload.way === wayIdx
      if (enableLog) { report(L"Way ${wayIdx}: isTargetThisWay = ${isTargetThisWay}") }

      mem.write(
        address = io.writeCmd.payload.index,
        data = io.writeCmd.payload.data,
        enable = isTargetThisWay
      )

      val data = readDataForThisWay
    }
  }

  val s0_allWaysReadData = Vec(ways_logic.map(_.data))

  val readRspPayloadReg = Reg(Vec.fill(p.wayCount)(CacheTag(p)))
  val readRspValidReg = RegInit(False)

  when(io.readCmd.fire) { // 当新的读命令被接受
    readRspPayloadReg := s0_allWaysReadData
    readRspValidReg   := True       // 下个周期响应个 valid
}.elsewhen(io.readRsp.fire) { // 当当前的有效响应被下游消耗
    readRspValidReg   := False      // 在消耗的下一个周期标记为无效
}

  io.readRsp.valid := readRspValidReg
  io.readRsp.payload := readRspPayloadReg

  // 当响应无效或者 io.readRsp.ready 时，允许新的读命令
  // 当 T0 时，接受了一个命令
  // T1 readRspValidReg 还不成立。此时 io.readCmd.ready 会为真，导致继续接受一个命令。
  // T2 readRspValidReg 成立。此时响应第一个命令。但由于发送方已经发送了第二个命令，响应会被当作第二个命令的响应。
  io.readCmd.ready := !readRspValidReg || io.readRsp.ready
  if (enableLog) {
    report(L"io.readCmd.ready = ${io.readCmd.ready}, readRspValidReg = ${readRspValidReg} io.readRsp.ready = ${io.readRsp.ready}, io.readRsp.valid = ${io.readRsp.valid}, io.readRsp.payload = ${io.readRsp.payload.map(_.format)}")
  }
  when(io.readCmd.valid) {
    report(L"readCmd valid! readCmd.index = ${io.readCmd.payload.index}")
  }
  if (enableLog) { report(L"io.readCmd.ready = ${io.readCmd.ready}, io.readCmd.valid = ${io.readCmd.ready}") }
  when(io.readCmd.fire) {
    report(L"readCmd fire! readCmd.index = ${io.readCmd.payload.index}")
  }

  val writeTargetWayOH = UIntToOh(io.writeCmd.payload.way, p.wayCount)
  val writeReadysPerWay = Vec.fill(p.wayCount)(True)

  io.writeCmd.ready := Mux(
    io.writeCmd.valid,
    OhMux.or(writeTargetWayOH, writeReadysPerWay),
    True
  )
  if (enableLog) { report(L"io.writeCmd.ready = ${io.writeCmd.ready}") }
}
