package parallax.bus

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._
import parallax.components.memory._
import spinal.lib.fsm._

object SplitGmbToAxi4Bridge {
  private var _nextInstanceId = 0
  def nextInstanceId: Int = {
    val id = _nextInstanceId
    _nextInstanceId += 1
    id
  }
}

class SplitGmbToAxi4Bridge(
  val gmbConfig: GenericMemoryBusConfig,
  val axiConfig: Axi4Config,
  val cmdInStage: Boolean = true,
  val rspInStage: Boolean = true
) extends Component {
  val instanceId = SplitGmbToAxi4Bridge.nextInstanceId
  val enableLog = false
  val enableReadLog = enableLog && true
  val enableWriteLog = enableLog && true

  val io = new Bundle {
    val gmbIn = slave(SplitGenericMemoryBus(gmbConfig))
    val axiOut = master(Axi4(axiConfig))
  }

  // --- Read Channel (保持不变) ---
  val gmbReadCmd = if (cmdInStage) io.gmbIn.read.cmd.stage() else io.gmbIn.read.cmd
  val axiAr = io.axiOut.ar
  axiAr.valid := gmbReadCmd.valid
  axiAr.addr := gmbReadCmd.address.resized
  if (gmbConfig.useId) {
    require(axiAr.id.getWidth >= gmbReadCmd.id.getWidth, "AXI4 ID width is smaller than GMB ID width")
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
    require(gmbReadRsp.id.getWidth >= axiR.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
    gmbReadRsp.id := axiR.id.resized
  }
  gmbReadRsp.error := !axiR.isOKAY()
  axiR.ready := gmbReadRsp.ready

  if(enableReadLog) {
    val cycle = Reg(UInt(32 bits)) init(0)
    cycle := cycle + 1
    report(
      L"Bridge $instanceId Cycle ${cycle}: Read Channel\n" :+
      L"  GMB Read: v=${gmbReadCmd.valid} r=${gmbReadCmd.ready} fire=${gmbReadCmd.fire} addr=${gmbReadCmd.address}\n" :+
      L"  AXI AR: v=${axiAr.valid} r=${axiAr.ready} fire=${axiAr.fire}\n" :+
      L"  AXI R: v=${axiR.valid} r=${axiR.ready} fire=${axiR.fire}"
    )
  }

// ====================================================================
// 写通道 (Write Channel)
// ====================================================================
val gmbWriteCmd = io.gmbIn.write.cmd
val axiAw = io.axiOut.aw
val axiW = io.axiOut.w
val axiB = io.axiOut.b

// 1. 缓存输入的GMB命令，这样我们就可以立即响应ready
val cmdStage = gmbWriteCmd.stage()

// 2. 使用StreamFork将一个命令分发给两个下游（AW和W）
//    StreamFork确保只有当两个下游都接受了数据，它才会从上游(cmdStage)消耗数据
val fork = StreamFork(cmdStage, 2)
val awStream = fork(0)
val wStream = fork(1)

// 3. 将分发后的流连接到AXI通道
axiAw.valid := awStream.valid
awStream.ready := axiAw.ready
axiAw.payload.addr := awStream.address.resized
axiAw.payload.len  := 0
axiAw.payload.size := log2Up(gmbConfig.dataWidth.value / 8)
axiAw.payload.setBurstINCR()
if (gmbConfig.useId) {
  axiAw.payload.id := awStream.id.resized
} else if (axiConfig.useId) {
  axiAw.payload.id := 0
}

axiW.valid := wStream.valid
wStream.ready := axiW.ready
axiW.payload.data := wStream.data
axiW.payload.strb := wStream.byteEnables
axiW.payload.last := True

  // --- 写响应通道 (保持不变) ---
  val gmbWriteRsp = io.gmbIn.write.rsp
  val axiB_staged = if (rspInStage) axiB.stage() else axiB
  gmbWriteRsp.valid := axiB_staged.valid
  gmbWriteRsp.payload.error := !axiB_staged.isOKAY()
  if(gmbConfig.useId) {
    require(gmbWriteRsp.payload.id.getWidth >= axiB_staged.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
    gmbWriteRsp.payload.id := axiB_staged.id.resized
  }
  axiB_staged.ready := gmbWriteRsp.ready
  
  // --- 调试日志 (可选) ---
  if(enableWriteLog) {
    val cycle = Reg(UInt(32 bits)) init(0)
    cycle := cycle + 1
    report(
      L"Bridge $instanceId Cycle ${cycle}: Write Channel\n" :+
      L"  GMB Write: v=${gmbWriteCmd.valid} r=${gmbWriteCmd.ready} fire=${gmbWriteCmd.fire} addr=${gmbWriteCmd.address}\n" :+
      L"  AXI AW: v=${axiAw.valid} r=${axiAw.ready} fire=${axiAw.fire} id=${axiAw.payload.id}\n" :+
      L"  AXI W: v=${axiW.valid} r=${axiW.ready} fire=${axiW.fire} data=${axiW.payload.data} strb=${axiW.payload.strb}\n" :+
      L"  AXI B: v=${axiB.valid} r=${axiB.ready} fire=${axiB.fire} id=${axiB.id} error=${axiB.isSLVERR()}"
    )
  }
}
