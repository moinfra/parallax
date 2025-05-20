package parallax.bus // 或者你希望放置桥接器的包

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import parallax.components.memory.{GenericMemoryBusConfig, SplitGenericMemoryBus}

class Axi4ToSplitGmbBridge(
    val axiConfig: Axi4Config,
    val gmbConfig: GenericMemoryBusConfig
) extends Component {

  val io = new Bundle {
    val axiIn = slave(Axi4(axiConfig))
    val gmbOut = master(SplitGenericMemoryBus(gmbConfig))
  }

  // --- 编译时断言和检查 ---
  require(axiConfig.dataWidth == gmbConfig.dataWidth.value, "AXI dataWidth must match GMB dataWidth.")
  require(axiConfig.addressWidth == gmbConfig.addressWidth.value, "AXI addressWidth must match GMB addressWidth for direct mapping.")
  if (axiConfig.useId) {
    require(gmbConfig.useId, "If AXI uses ID, GMB must also use ID.")
    require(axiConfig.idWidth == gmbConfig.idWidth.value, "AXI idWidth must match GMB idWidth.")
  } else {
    require(!gmbConfig.useId, "If AXI does not use ID, GMB must also not use ID for this bridge.")
  }
  // 假设 ARLEN 和 AWLEN 总是 0，所以 RLAST 和 WLAST 总是 true。

  // --- 读通道 (AXI AR/R <-> GMB Read Cmd/Rsp) ---
  val axiAr = io.axiIn.ar
  val gmbReadCmd = io.gmbOut.read.cmd

  gmbReadCmd.valid := axiAr.valid
  gmbReadCmd.payload.address := axiAr.payload.addr
  if (axiConfig.useId) {
    gmbReadCmd.payload.id := axiAr.payload.id
  }
  axiAr.ready := gmbReadCmd.ready

  val gmbReadRsp = io.gmbOut.read.rsp
  val axiR = io.axiIn.r

  axiR.valid := gmbReadRsp.valid
  axiR.payload.data := gmbReadRsp.payload.data
  if (axiConfig.useId) {
    axiR.payload.id := gmbReadRsp.payload.id
  }
  axiR.payload.resp := Mux(gmbReadRsp.payload.error, Axi4.resp.SLVERR, Axi4.resp.OKAY)
  axiR.payload.last := True // 因为 ARLEN = 0
  gmbReadRsp.ready := axiR.ready


  // --- 写通道 (AXI AW/W/B <-> GMB Write Cmd/Rsp) ---
  val axiAw = io.axiIn.aw
  val axiW = io.axiIn.w
  val gmbWriteCmd = io.gmbOut.write.cmd

  // 为了同步 AW 和 W，我们将 AW 的信息暂存起来。
  // 使用一个简单的流队列（FIFO深度为1）来暂存AW通道的信息。
  // 这样，当W通道数据到达时，我们可以从队列中取出对应的AW信息。
  // 这也处理了AW和W通道之间的反压。

  val awQueue = StreamFifo(dataType = Axi4Aw(axiConfig), depth = 1) // 深度为1的FIFO足以处理AW/W的顺序性
  awQueue.io.push << axiAw // axiAw驱动FIFO的输入

  // 只有当AW信息已在队列中，并且W通道数据也有效时，才向GMB发出写命令
  gmbWriteCmd.valid := awQueue.io.pop.valid && axiW.valid
  awQueue.io.pop.ready := gmbWriteCmd.ready && axiW.valid // 从FIFO中取出AW信息
  axiW.ready := gmbWriteCmd.ready && awQueue.io.pop.valid  // W通道准备好接收

  gmbWriteCmd.payload.address := awQueue.io.pop.payload.addr
  if (axiConfig.useId) {
    gmbWriteCmd.payload.id := awQueue.io.pop.payload.id
  }
  gmbWriteCmd.payload.data := axiW.payload.data
  gmbWriteCmd.payload.byteEnables := axiW.payload.strb // AXI strb 映射到 GMB byteEnables

  // 写响应通道
  val gmbWriteRsp = io.gmbOut.write.rsp
  val axiB = io.axiIn.b

  axiB.valid := gmbWriteRsp.valid
  if (axiConfig.useId) {
    axiB.payload.id := gmbWriteRsp.payload.id
  }
  axiB.payload.resp := Mux(gmbWriteRsp.payload.error, Axi4.resp.SLVERR, Axi4.resp.OKAY)
  gmbWriteRsp.ready := axiB.ready
}
