package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._

class DataRamBanksComponent(p: DataCacheParameters) extends Component {
  val io = new Bundle {

    val speculativeReadCmdS0 = slave(Stream(new Bundle {
      val index = UInt(p.lineIndexWidth bits)
      val wordOffset = UInt(p.cpuWordOffsetWidth bits)
    }))
    val speculativeReadRspS1 = master(Flow(Vec.fill(p.wayCount)(Bits(p.cpuDataWidth bits))))

    val writeCmdS2 = slave(Stream(new Bundle {
      val index = UInt(p.lineIndexWidth bits)
      val way = UInt(log2Up(p.wayCount) bits)
      val wordOffset = UInt(p.cpuWordOffsetWidth bits)
      val data = Bits(p.cpuDataWidth bits)
      val mask = Bits(p.bytePerCpuWord bits)
    }))

    val lineWriteCmdRefill = slave(Stream(new Bundle {
      val index = UInt(p.lineIndexWidth bits)
      val way = UInt(log2Up(p.wayCount) bits)
      val data = Bits(p.lineSize * 8 bits)
    }))

    val lineReadCmdWb = slave(Stream(new Bundle {
      val index = UInt(p.lineIndexWidth bits)
      val way = UInt(log2Up(p.wayCount) bits)
    }))
    val lineReadRspWb = master(Flow(Bits(p.lineSize * 8 bits)))

  }

  val ways_logic = Range(0, p.wayCount).map { wayIdx =>
    new Area {
      val mem = Mem(Bits(p.lineSize * 8 bits), p.linePerWay)

      val s0_cmd_index = io.speculativeReadCmdS0.payload.index
      val s0_cmd_wordOffset = io.speculativeReadCmdS0.payload.wordOffset
      val s0_cmd_fire = io.speculativeReadCmdS0.fire

      val data_from_s0_specRead_cmd = mem.readAsync(
        address = s0_cmd_index
      )

      val s1_wordOffset_latched_from_s0 = RegNextWhen(s0_cmd_wordOffset, s0_cmd_fire)
      val s1_s0_did_fire = RegNext(s0_cmd_fire, init = False)

      val s1_speculativeDataOutputForThisWay_registered = Reg(Bits(p.cpuDataWidth bits))
      when(s1_s0_did_fire) {
        s1_speculativeDataOutputForThisWay_registered := data_from_s0_specRead_cmd.subdivideIn(p.cpuDataWidth bits)(
          s1_wordOffset_latched_from_s0
        )
      }
      val s2_cmdPayload = io.writeCmdS2.payload
      val s2_fullLineDataToWrite = Bits(p.lineSize * 8 bits)
      val s2_lineBitMask = Bits(p.lineSize * 8 bits)
      s2_fullLineDataToWrite := 0
      s2_lineBitMask := 0

      val s2_is_targeting_this_way = io.writeCmdS2.valid && s2_cmdPayload.way === wayIdx
      when(s2_is_targeting_this_way) {
        val targetCpuWordOffsetInLine = s2_cmdPayload.wordOffset
        val cpuDataToWrite = s2_cmdPayload.data
        val cpuByteMask = s2_cmdPayload.mask
        val bitOffsetOfCpuWord = targetCpuWordOffsetInLine * p.cpuDataWidth

        s2_fullLineDataToWrite.subdivideIn(p.cpuDataWidth bits)(targetCpuWordOffsetInLine) := cpuDataToWrite
        // s2_fullLineDataToWrite((bitOffsetOfCpuWord + p.cpuDataWidth - 1) downto bitOffsetOfCpuWord) := cpuDataToWrite

        val cpuWordBitMask = Bits(p.cpuDataWidth bits)
        val byteMaskAllOnes = Bits(8 bits).setAll()
        val byteMaskAllZeros = Bits(8 bits).clearAll()
        for (byteIdx <- 0 until p.bytePerCpuWord) {
          cpuWordBitMask((byteIdx + 1) * 8 - 1 downto byteIdx * 8) := Mux(
            cpuByteMask(byteIdx),
            byteMaskAllOnes,
            byteMaskAllZeros
          )
        }

        s2_lineBitMask.subdivideIn(p.cpuDataWidth bits)(targetCpuWordOffsetInLine) := cpuWordBitMask
      }

      val ty = new Bundle {
        val address = UInt(p.lineIndexWidth bits)
        val data = Bits(p.lineSize * 8 bits)
        val mask = Bits(p.lineSize * 8 bits)
      }

      val refill_cmdPayload = io.lineWriteCmdRefill.payload
      val refill_is_targeting_this_way = io.lineWriteCmdRefill.valid && refill_cmdPayload.way === wayIdx

      val writeStream = Stream(ty)
      writeStream.valid := s2_is_targeting_this_way
      writeStream.payload.address := s2_cmdPayload.index
      writeStream.payload.data := s2_fullLineDataToWrite
      writeStream.payload.mask := s2_lineBitMask

      val refillStream = Stream(ty)
      refillStream.valid := refill_is_targeting_this_way
      refillStream.payload.address := refill_cmdPayload.index
      refillStream.payload.data := refill_cmdPayload.data
      refillStream.payload.mask.setAll()

      val writePortArbiter = StreamArbiterFactory().roundRobin.buildOn(writeStream, refillStream)

      mem.write(
        address = writePortArbiter.io.output.address,
        data = writePortArbiter.io.output.data,
        mask = writePortArbiter.io.output.mask,
        enable = writePortArbiter.io.output.fire
      )
      writePortArbiter.io.output.ready := True

      val wb_cmd_index = io.lineReadCmdWb.payload.index
      val wb_cmd_fire_for_this_way = io.lineReadCmdWb.fire && io.lineReadCmdWb.payload.way === wayIdx

      val data_from_wb_read_cmd = mem.readAsync(
        address = wb_cmd_index
      )

      val wb_lineDataOutputForThisWay_registered = Reg(Bits(p.lineSize * 8 bits))
      val wb_s0_did_fire_for_this_way = RegNext(wb_cmd_fire_for_this_way, init = False)

      when(wb_s0_did_fire_for_this_way) {
        wb_lineDataOutputForThisWay_registered := data_from_wb_read_cmd
      }
    }
  }

  val s1_allWaysSpeculativeData = Vec(ways_logic.map(_.s1_speculativeDataOutputForThisWay_registered))
  val s1_specReadRspValidReg = RegNext(io.speculativeReadCmdS0.fire, init = False)

  io.speculativeReadCmdS0.ready := !s1_specReadRspValidReg

  io.speculativeReadRspS1.payload := s1_allWaysSpeculativeData
  io.speculativeReadRspS1.valid := s1_specReadRspValidReg

  val s2_write_target_way_oh = UIntToOh(io.writeCmdS2.payload.way, p.wayCount)
  val s2_arbiter_write_readys = Vec(ways_logic.map(_.writeStream.ready))

  io.writeCmdS2.ready := Mux(
    io.writeCmdS2.valid,
    OhMux.or(s2_write_target_way_oh, s2_arbiter_write_readys),
    True
  )

  val refill_write_target_way_oh = UIntToOh(io.lineWriteCmdRefill.payload.way, p.wayCount)
  val refill_arbiter_refill_readys = Vec(ways_logic.map(_.refillStream.ready))

  io.lineWriteCmdRefill.ready := Mux(
    io.lineWriteCmdRefill.valid,
    OhMux.or(refill_write_target_way_oh, refill_arbiter_refill_readys),
    True
  )

  val wb_allWaysLineData = Vec(ways_logic.map(_.wb_lineDataOutputForThisWay_registered))
  val wb_lineReadRspValidReg = RegNext(io.lineReadCmdWb.fire, init = False)
  val wb_activeWayReg = RegNextWhen(io.lineReadCmdWb.payload.way, io.lineReadCmdWb.fire)

  io.lineReadCmdWb.ready := !wb_lineReadRspValidReg

  io.lineReadRspWb.payload := wb_allWaysLineData(wb_activeWayReg)
  io.lineReadRspWb.valid := wb_lineReadRspValidReg
}
