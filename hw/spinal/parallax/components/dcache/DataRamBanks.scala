package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
// IO Bundle definitions
case class DrbSpeculativeReadCmd(p: DataCacheParameters) extends Bundle {
  val index = UInt(p.lineIndexWidth bits)
  val wordOffset = UInt(p.cpuWordOffsetWidth bits)
}

case class DrbWriteCmd(p: DataCacheParameters) extends Bundle {
  val index = UInt(p.lineIndexWidth bits)
  val way = UInt(log2Up(p.wayCount) max 1 bits)
  val wordOffset = UInt(p.cpuWordOffsetWidth bits)
  val data = Bits(p.cpuDataWidth bits)
  val mask = Bits(p.bytePerCpuWord bits)
}

case class DrbLineWriteCmd(p: DataCacheParameters) extends Bundle {
  val index = UInt(p.lineIndexWidth bits)
  val way = UInt(log2Up(p.wayCount) max 1 bits)
  val data = Bits(p.lineSize * 8 bits)
}

case class DrbLineReadCmd(p: DataCacheParameters) extends Bundle {
  val index = UInt(p.lineIndexWidth bits)
  val way = UInt(log2Up(p.wayCount) max 1 bits)
}

case class DataRamBanksIO(p: DataCacheParameters) extends Bundle {
  val speculativeReadCmdS0 = slave(Stream(DrbSpeculativeReadCmd(p)))
  val speculativeReadRspS1 = master(Flow(Vec.fill(p.wayCount)(Bits(p.cpuDataWidth bits))))
  val writeCmdS2 = slave(Stream(DrbWriteCmd(p)))
  val lineWriteCmdRefill = slave(Stream(DrbLineWriteCmd(p)))
  val lineReadCmdWb = slave(Stream(DrbLineReadCmd(p)))
  val lineReadRspWb = master(Flow(Bits(p.lineSize * 8 bits)))
}

case class WriteStreamPayload(p: DataCacheParameters) extends Bundle {
  val address = UInt(p.lineIndexWidth bits)
  val data = Bits(p.lineSize * 8 bits)
  val mask = Bits(p.lineSize * 8 bits)
}

class DataRamBanksComponent(p: DataCacheParameters) extends Component {
  val io = DataRamBanksIO(p)
  val enableLog = false
  val ways_logic = Range(0, p.wayCount).map { wayIdx =>
    new Area {
      val mem = Mem(Bits(p.lineSize * 8 bits), p.linePerWay)

      val s0_cmd_index = io.speculativeReadCmdS0.payload.index
      val s0_cmd_wordOffset = io.speculativeReadCmdS0.payload.wordOffset
      val s0_cmd_fire = io.speculativeReadCmdS0.fire

      val s0_specReadLineData = mem.readAsync(
        address = s0_cmd_index
      )
      if (enableLog) {

        when(s0_cmd_fire) {
          report(
            L"Way ${wayIdx}: s0_cmd_index = ${s0_cmd_index}, s0_cmd_wordOffset = ${s0_cmd_wordOffset}, s0_cmd_fire = ${s0_cmd_fire}"
          )
        }
      }
      if (enableLog) {
        when(s0_cmd_fire) {
          report(L"Way ${wayIdx}: s0_specReadLineData = ${s0_specReadLineData}")
        }
      }

      val s1_specReadWayDataReg = Reg(Bits(p.cpuDataWidth bits)) // 在 s0 写入，在 s1 可用
      when(s0_cmd_fire) {
        report(L"Total ${s0_specReadLineData.getWidth / p.cpuDataWidth} words in the line ${s0_specReadLineData}")
        s1_specReadWayDataReg := s0_specReadLineData.subdivideIn(p.cpuDataWidth bits)(
          s0_cmd_wordOffset
        )
      }
      if (enableLog) {
        report(
          L"Way ${wayIdx}: s1_specReadWayDataReg (of prev cycle) = ${s1_specReadWayDataReg}"
        )
      }

      val s2_cmdPayload = io.writeCmdS2.payload
      val s2_writeLineData = Bits(p.lineSize * 8 bits)
      val s2_writeLineBitMask = Bits(p.lineSize * 8 bits)
      s2_writeLineData := 0
      s2_writeLineBitMask := 0

      val s2_targeThisWay = io.writeCmdS2.valid && s2_cmdPayload.way === wayIdx
      when(s2_targeThisWay) {

        val writeWordOff = s2_cmdPayload.wordOffset
        val cpuDataToWrite = s2_cmdPayload.data
        val cpuByteMask = s2_cmdPayload.mask
        val bitOffsetOfCpuWord = writeWordOff * p.cpuDataWidth

        s2_writeLineData.subdivideIn(p.cpuDataWidth bits)(writeWordOff) := cpuDataToWrite
        // s2_writeLineData((bitOffsetOfCpuWord + p.cpuDataWidth - 1) downto bitOffsetOfCpuWord) := cpuDataToWrite

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

        s2_writeLineBitMask.subdivideIn(p.cpuDataWidth bits)(writeWordOff) := cpuWordBitMask

        if (enableLog) {
          report(L"Way ${wayIdx}: s2_targeThisWay = ${s2_targeThisWay}, wordOff = ${writeWordOff}, cpuDataToWrite = ${cpuDataToWrite}, cpuByteMask = ${cpuByteMask}, bitOffsetOfCpuWord = ${bitOffsetOfCpuWord}, cpuWordBitMask = ${cpuWordBitMask}")
        }
      } otherwise {
        report(L"Way ${wayIdx}: s2_targeThisWay = ${s2_targeThisWay}")
      }
      if (enableLog) {
        report(L"Way ${wayIdx}: s2_writeLineData = ${s2_writeLineData}, s2_writeLineBitMask = ${s2_writeLineBitMask}")
      }

      val refill_cmdPayload = io.lineWriteCmdRefill.payload
      val refill_is_targeting_this_way = io.lineWriteCmdRefill.valid && refill_cmdPayload.way === wayIdx
      if (enableLog) {
        report(L"Way ${wayIdx}: refill_is_targeting_this_way = ${refill_is_targeting_this_way}")
      }

      val writeStream = Stream(WriteStreamPayload(p))
      writeStream.valid := s2_targeThisWay
      writeStream.payload.address := s2_cmdPayload.index
      writeStream.payload.data := s2_writeLineData
      writeStream.payload.mask := s2_writeLineBitMask
      if (enableLog) {
        report(
          L"Way ${wayIdx}: writeStream.valid = ${writeStream.valid}, writeStream.payload.address = ${writeStream.payload.address}, writeStream.payload.data = ${writeStream.payload.data}, writeStream.payload.mask = ${writeStream.payload.mask}"
        )
      }

      val refillStream = Stream(WriteStreamPayload(p))
      refillStream.valid := refill_is_targeting_this_way
      refillStream.payload.address := refill_cmdPayload.index
      refillStream.payload.data := refill_cmdPayload.data
      refillStream.payload.mask.setAll()
      if (enableLog) {
        report(
          L"Way ${wayIdx}: refillStream.valid = ${refillStream.valid}, refillStream.payload.address = ${refillStream.payload.address}, refillStream.payload.data = ${refillStream.payload.data}, refillStream.payload.mask = ${refillStream.payload.mask}"
        )
      }

      val writePortArbiter = StreamArbiterFactory().roundRobin.buildOn(writeStream, refillStream)
      if (enableLog) {
        report(
          L"Way ${wayIdx}: writePortArbiter.io.output.valid = ${writePortArbiter.io.output.valid}, writePortArbiter.io.output.address = ${writePortArbiter.io.output.address}, writePortArbiter.io.output.data = ${writePortArbiter.io.output.data}, writePortArbiter.io.output.mask = ${writePortArbiter.io.output.mask}, writePortArbiter.io.output.fire = ${writePortArbiter.io.output.fire}"
        )
      }

      mem.write(
        address = writePortArbiter.io.output.address,
        data = writePortArbiter.io.output.data,
        mask = writePortArbiter.io.output.mask,
        enable = writePortArbiter.io.output.fire
      )
      writePortArbiter.io.output.ready := True

      val wb_cmdIdx = io.lineReadCmdWb.payload.index
      val wb_fireWay = io.lineReadCmdWb.fire && io.lineReadCmdWb.payload.way === wayIdx
      if (enableLog) {
        report(L"Way ${wayIdx}: wb_cmdIdx = ${wb_cmdIdx}, wbFire = ${wb_fireWay}")
      }

      val wb_readLineData = mem.readAsync(
        address = wb_cmdIdx
      )
      if (enableLog) {
        report(L"Way ${wayIdx}: wb_readLineData = ${wb_readLineData}")
      }

      val wb_lineDataOut = wb_readLineData
      
    }
  }

  val s1_allWaysSpeculativeDataRegs = Vec(ways_logic.map(_.s1_specReadWayDataReg))
  val s1_specReadRspValidReg = RegNext(io.speculativeReadCmdS0.fire, init = False)
  if (enableLog) {
    report(
      L"s1_allWaysSpeculativeDataRegs = ${s1_allWaysSpeculativeDataRegs}, s1_specReadRspValidReg = ${s1_specReadRspValidReg}"
    )
  }

  io.speculativeReadCmdS0.ready := !s1_specReadRspValidReg
  if (enableLog) {
    report(L"io.speculativeReadCmdS0.ready = ${io.speculativeReadCmdS0.ready} valid = ${io.speculativeReadCmdS0.valid}")
  }

  io.speculativeReadRspS1.payload := s1_allWaysSpeculativeDataRegs
  io.speculativeReadRspS1.valid := s1_specReadRspValidReg
  if (enableLog) {
    report(
      L"io.speculativeReadRspS1.payload = ${io.speculativeReadRspS1.payload}, io.speculativeReadRspS1.valid = ${io.speculativeReadRspS1.valid}"
    )
  }

  val s2_write_target_way_oh = UIntToOh(io.writeCmdS2.payload.way, p.wayCount)
  val s2_arbiter_write_readys = Vec(ways_logic.map(_.writeStream.ready))
  if (enableLog) {
    report(L"s2_write_target_way_oh = ${s2_write_target_way_oh}, s2_arbiter_write_readys = ${s2_arbiter_write_readys}")
  }

  io.writeCmdS2.ready := Mux(
    io.writeCmdS2.valid,
    OhMux.or(s2_write_target_way_oh, s2_arbiter_write_readys),
    True
  )
  if (enableLog) {
    report(L"io.writeCmdS2.ready = ${io.writeCmdS2.ready}")
  }

  val refill_write_target_way_oh = UIntToOh(io.lineWriteCmdRefill.payload.way, p.wayCount)
  val refill_arbiter_refill_readys = Vec(ways_logic.map(_.refillStream.ready))
  if (enableLog) {
    report(
      L"refill_write_target_way_oh = ${refill_write_target_way_oh}, refill_arbiter_refill_readys = ${refill_arbiter_refill_readys}"
    )
  }

  io.lineWriteCmdRefill.ready := Mux(
    io.lineWriteCmdRefill.valid,
    OhMux.or(refill_write_target_way_oh, refill_arbiter_refill_readys),
    True
  )
  if (enableLog) {
    report(L"io.lineWriteCmdRefill.ready = ${io.lineWriteCmdRefill.ready}")
  }

  val wb_allWaysLineData = Vec(ways_logic.map(_.wb_lineDataOut))
  val wb_lineReadRspValid = io.lineReadCmdWb.valid
  val wb_activeWay = io.lineReadCmdWb.payload.way
  if (enableLog) {
    report(
      L"wb_allWaysLineData = ${wb_allWaysLineData}, wb_lineReadRspValidReg = ${wb_lineReadRspValid}, wb_activeWayReg = ${wb_activeWay}"
    )
  }

  io.lineReadCmdWb.ready := True
  if (enableLog) {
    report(L"io.lineReadCmdWb.ready = ${io.lineReadCmdWb.ready}")
  }

  if(wb_allWaysLineData.size == 1) {
    io.lineReadRspWb.payload := wb_allWaysLineData(0) // 没办法，不这么写spinal不让编译通过
  } else {
    io.lineReadRspWb.payload := wb_allWaysLineData(wb_activeWay)
  }
  io.lineReadRspWb.valid := wb_lineReadRspValid
  if (enableLog) {
    report(
      L"io.lineReadRspWb.payload = ${io.lineReadRspWb.payload}, io.lineReadRspWb.valid = ${io.lineReadRspWb.valid}"
    )
  }
}
