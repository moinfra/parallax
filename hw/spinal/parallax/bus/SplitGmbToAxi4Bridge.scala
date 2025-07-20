// filename: parallax/bus/SplitGmbToAxi4Bridge.scala
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

  // --- Read Channel ---
  val gmbReadCmd = if (cmdInStage) io.gmbIn.read.cmd.stage() else io.gmbIn.read.cmd
  val axiAr = io.axiOut.ar
  val arCmd = Stream(Axi4Ar(axiConfig))

  arCmd.valid := gmbReadCmd.valid
  gmbReadCmd.ready := arCmd.ready
  
  arCmd.payload.addr := gmbReadCmd.address.resized
  if (gmbConfig.useId) {
    require(axiAr.id.getWidth >= gmbReadCmd.id.getWidth, "AXI4 ID width is smaller than GMB ID width")
    arCmd.payload.id := gmbReadCmd.id.resized
  } else {
    if (axiConfig.useId) arCmd.payload.id := 0
  }
  arCmd.payload.len := 0
  arCmd.payload.size := log2Up(gmbConfig.dataWidth.value / 8)
  arCmd.payload.setBurstINCR()

  axiAr << arCmd.stage()

  val gmbReadRsp = io.gmbIn.read.rsp
  val axiR = if (rspInStage) io.axiOut.r.stage() else io.axiOut.r
  
  // +++ CHANGED: Create an intermediate, registered stream for the response +++
  // This ensures the logic driving gmbReadRsp is contained within the bridge.
  val gmbReadRspInternal = Stream(SplitGmbReadRsp(gmbConfig))
  gmbReadRspInternal.valid := axiR.valid
  gmbReadRspInternal.payload.data := axiR.data
  if (gmbConfig.useId) {
    require(gmbReadRsp.id.getWidth >= axiR.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
    gmbReadRspInternal.payload.id := axiR.id.resized
  }
  gmbReadRspInternal.payload.error := !axiR.isOKAY()
  axiR.ready := gmbReadRspInternal.ready
  
  // Connect the registered internal stream to the final output
  gmbReadRsp << gmbReadRspInternal.stage()


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

  val cmdStage = gmbWriteCmd.stage()
  val fork = StreamFork(cmdStage, 2)

  val awStream = fork(0)
  val wStream = fork(1)

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

  // --- 写响应通道 (Write Response Channel) ---
  val gmbWriteRsp = io.gmbIn.write.rsp
  val axiB_staged = if (rspInStage) axiB.stage() else axiB
  
  // +++ CHANGED: Apply the same robust staging pattern as the read channel +++
val gmbWriteRspInternal = Stream(SplitGmbWriteRsp(gmbConfig))
  gmbWriteRspInternal.valid := axiB_staged.valid
  gmbWriteRspInternal.payload.error := !axiB_staged.isOKAY()
  if(gmbConfig.useId) {
    require(gmbWriteRsp.payload.id.getWidth >= axiB_staged.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
    gmbWriteRspInternal.payload.id := axiB_staged.id.resized
  }
  axiB_staged.ready := gmbWriteRspInternal.ready
  
  gmbWriteRsp << gmbWriteRspInternal.stage()

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
