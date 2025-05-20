package parallax.bus

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._

class Axi4SharedToFullConverter(config: Axi4Config) extends Component {
  val io = new Bundle {
    val axiSharedIn = slave(Axi4Shared(config))
    val axiFullOut = master(Axi4(config))
  }

  val isWriteTransaction = io.axiSharedIn.sharedCmd.payload.write

  io.axiFullOut.ar.valid := io.axiSharedIn.sharedCmd.valid && !isWriteTransaction
  io.axiFullOut.ar.payload.assignSomeByName(io.axiSharedIn.sharedCmd.payload)

  io.axiFullOut.aw.valid := io.axiSharedIn.sharedCmd.valid && isWriteTransaction
  io.axiFullOut.aw.payload.assignSomeByName(io.axiSharedIn.sharedCmd.payload)

  io.axiSharedIn.sharedCmd.ready := Mux(isWriteTransaction, io.axiFullOut.aw.ready, io.axiFullOut.ar.ready)
  io.axiFullOut.w << io.axiSharedIn.writeData
  io.axiSharedIn.readRsp << io.axiFullOut.r
  io.axiSharedIn.writeRsp << io.axiFullOut.b
}
