package parallax.bus

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._
import parallax.components.memory._
import spinal.lib.fsm._

class SplitGmbToAxi4Bridge(
  val gmbConfig: GenericMemoryBusConfig,
  val axiConfig: Axi4Config,
  val cmdInStage: Boolean = true,
  val rspInStage: Boolean = true
) extends Component {

  val io = new Bundle {
    val gmbIn = slave(SplitGenericMemoryBus(gmbConfig))
    val axiOut = master(Axi4(axiConfig))
  }

  // --- Read Channel ---
  val gmbReadCmd = if (cmdInStage) io.gmbIn.read.cmd.stage() else io.gmbIn.read.cmd
  val axiAr = io.axiOut.ar
  axiAr.valid := gmbReadCmd.valid
  axiAr.addr := gmbReadCmd.address.resized
  if (gmbConfig.useId) {
    axiAr.id := gmbReadCmd.id.resized
  } else {
    if (axiConfig.useId) axiAr.id := 0
  }
  axiAr.len := 0
  axiAr.size := log2Up(gmbConfig.dataWidth.value / 8)
  axiAr.setBurstINCR()
  gmbReadCmd.ready := axiAr.ready

  val gmbReadRsp = io.gmbIn.read.rsp
  val axiR = if (rspInStage) io.axiOut.r.stage() else io.axiOut.r
  gmbReadRsp.valid := axiR.valid
  gmbReadRsp.data := axiR.data
  if (gmbConfig.useId) {
    gmbReadRsp.id := axiR.id.resized
  }
  gmbReadRsp.error := !axiR.isOKAY()
  axiR.ready := gmbReadRsp.ready

  // ====================================================================
  // 写通道 (Write Channel) - 高性能并行实现
  // ====================================================================
  val gmbWriteCmd = if (cmdInStage) io.gmbIn.write.cmd.stage() else io.gmbIn.write.cmd
  val axiAw = io.axiOut.aw
  val axiW = io.axiOut.w
  val axiB = io.axiOut.b

  // 直接连接，实现最大吞吐量
  // AW通道直接连接
  axiAw.valid := gmbWriteCmd.valid
  axiAw.payload.addr := gmbWriteCmd.address.resized
  axiAw.payload.len  := 0
  axiAw.payload.size := log2Up(gmbConfig.dataWidth.value / 8)
  axiAw.payload.setBurstINCR()
  if (gmbConfig.useId) {
    axiAw.payload.id := gmbWriteCmd.id.resized
  } else {
    if (axiConfig.useId) axiAw.payload.id := 0
  }
  
  // W通道直接连接
  axiW.valid := gmbWriteCmd.valid
  axiW.payload.data := gmbWriteCmd.data
  axiW.payload.strb := gmbWriteCmd.byteEnables
  axiW.payload.last := True

  // 只有当两个通道都准备好时，GMB命令才ready
  gmbWriteCmd.ready := axiAw.ready && axiW.ready

  // --- 写响应通道 ---
  val gmbWriteRsp = io.gmbIn.write.rsp
  val axiB_staged = if (rspInStage) axiB.stage() else axiB
  gmbWriteRsp.valid := axiB_staged.valid
  gmbWriteRsp.payload.error := !axiB_staged.isOKAY()
  if(gmbConfig.useId) {
    gmbWriteRsp.payload.id := axiB_staged.id.resized
  }
  axiB_staged.ready := gmbWriteRsp.ready
  // --- 调试日志 (可选) ---
  val logEnable = false
  if(logEnable) {
    val cycle = Reg(UInt(32 bits)) init(0)
    cycle := cycle + 1
    report(
      L"Cycle ${cycle}: Direct Bridge Mode\n" :+
      L"  GMB Write: v=${gmbWriteCmd.valid} r=${gmbWriteCmd.ready} fire=${gmbWriteCmd.fire} addr=${gmbWriteCmd.address}\n" :+
      L"  AXI AW: v=${axiAw.valid} r=${axiAw.ready} fire=${axiAw.fire}\n" :+
      L"  AXI W: v=${axiW.valid} r=${axiW.ready} fire=${axiW.fire}\n" :+
      L"  AXI B: v=${axiB.valid} r=${axiB.ready} fire=${axiB.fire}"
    )
  }
}
