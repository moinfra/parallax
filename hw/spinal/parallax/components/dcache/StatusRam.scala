package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4Ax}
import spinal.lib.pipeline._ // Ensure new pipeline API is imported


class StatusRamComponent(p: DataCacheParameters) extends Component {
    val io = new Bundle {
        val readCmd = slave(Stream(UInt(p.lineIndexWidth bits)))
        val readRsp = master(Stream(Vec.fill(p.wayCount)(CacheLineStatus())))
        val writeCmd = slave(Stream(new Bundle {
            val index = UInt(p.lineIndexWidth bits)
            val way = UInt(log2Up(p.wayCount) bits)
            val data = CacheLineStatus()
        }))
    }

    // Each way has its own memory and logic
    val ways_logic = Range(0, p.wayCount).map { wayIdx =>
        new Area {
            report(L"Creating Status RAM for way ${wayIdx}")
            val mem = Mem.fill(p.linePerWay)(CacheLineStatus())

            // Read path for this way
            // The read command is broadcast to all ways, and each way reads its own data
            val readDataForThisWay = mem.readAsync(io.readCmd.payload)
            report(L"Way ${wayIdx}: readDataForThisWay = ${readDataForThisWay}")

            // Write path for this way
            val is_targeting_this_way = io.writeCmd.valid && io.writeCmd.payload.way === wayIdx
            report(L"Way ${wayIdx}: is_targeting_this_way = ${is_targeting_this_way}")

            // For Status RAM, typically a single write port is sufficient.
            // If multiple write sources are needed in the future, a StreamArbiter would be used here.
            mem.write(
                address = io.writeCmd.payload.index,
                data = io.writeCmd.payload.data,
                enable = is_targeting_this_way // Only enable write for the targeted way
            )

            // Expose the read data for aggregation
            val s0_read_data = readDataForThisWay
        }
    }

    // Aggregate read responses from all ways
    val s0_allWaysReadData = Vec(ways_logic.map(_.s0_read_data))

    // Register the read response to pipeline it to the next stage
    val readRspPayloadReg = Reg(Vec.fill(p.wayCount)(CacheLineStatus()))
    val readRspValidReg = RegInit(False)

    // Pipeline the read response
    when(io.readCmd.fire) {
        readRspPayloadReg := s0_allWaysReadData
    }
    readRspValidReg := RegNext(io.readCmd.fire) init(False)

    io.readRsp.valid := readRspValidReg
    io.readRsp.payload := readRspPayloadReg
    // The read command is ready if no valid response is pending, or if the current response is being consumed
    io.readCmd.ready := !readRspValidReg || io.readRsp.ready
    report(L"io.readRsp.valid = ${io.readRsp.valid}, io.readRsp.payload = ${io.readRsp.payload}")
    report(L"io.readCmd.ready = ${io.readCmd.ready}")


    // Handle write command ready signal
    // The write command is ready if the target way is ready to accept the write.
    // Since each way's RAM has a single write port and we enable based on 'is_targeting_this_way',
    // the write is always ready as long as the command is valid and targets a specific way.
    // We can use OhMux.or for consistency, though for a single write port per way, it simplifies to True.
    val write_target_way_oh = UIntToOh(io.writeCmd.payload.way, p.wayCount)
    // In this simple case, each way's write port is always ready if enabled, so we can consider
    // a "ready" signal from each way's write logic to be always True when targeted.
    // For a more complex scenario with internal write buffers or arbitration, this would be a Vec of actual ready signals.
    val write_readys_per_way = Vec.fill(p.wayCount)(True) // Assuming RAM write port is always ready

    io.writeCmd.ready := Mux(
        io.writeCmd.valid,
        OhMux.or(write_target_way_oh, write_readys_per_way),
        True // If writeCmd.valid is False, we are always ready to accept
    )
    report(L"io.writeCmd.ready = ${io.writeCmd.ready}")
}
