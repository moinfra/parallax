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
) extends Component {
  val instanceId = SplitGmbToAxi4Bridge.nextInstanceId
  val enableLog = false
  val enableReadLog = enableLog && true
  val enableWriteLog = enableLog && true

  val io = new Bundle {
    val gmbIn = slave(SplitGenericMemoryBus(gmbConfig))
    val axiOut = master(Axi4(axiConfig))
  }

  // ====================================================================
  // 读通道 (Read Channel)
  // ====================================================================
  val gmbReadCmdIn = io.gmbIn.read.cmd
  val axiArOut = io.axiOut.ar
  val gmbReadRspOut = io.gmbIn.read.rsp
  val axiRIn = io.axiOut.r

  // --- 读请求 ---
  val gmbReadCmdBuffered = gmbReadCmdIn.queue(2)
  if(enableLog) {
    report(
      L"Bridge $instanceId Read Queue Status\n" :+
      L"  Queue Out: v=${gmbReadCmdBuffered.valid} r=${gmbReadCmdBuffered.ready} payload.addr=${gmbReadCmdBuffered.payload.address} payload.id=${gmbReadCmdBuffered.payload.id}\n"
    )
  }
  // +++ CORRECTED: 使用不带参数的 translateWith lambda 语法 +++
  axiArOut << gmbReadCmdBuffered.translateWith {
    val ar = Axi4Ar(axiConfig)
    // 直接引用 gmbReadCmdBuffered.payload
    ar.addr := gmbReadCmdBuffered.payload.address.resized
    ar.len := 0
    ar.size := log2Up(gmbConfig.dataWidth.value / 8)
    ar.setBurstINCR()
    if (gmbConfig.useId) {
      require(axiArOut.id.getWidth >= gmbReadCmdBuffered.payload.id.getWidth, "AXI4 ID width is smaller than GMB ID width")
      ar.id := gmbReadCmdBuffered.payload.id.resized
    } else if (axiConfig.useId) { ar.id := 0 }
    ar
  }

  // --- 读响应 ---
  val axiR_buffered = axiRIn.queue(2)
  if(enableReadLog) {
    report(
      L"Bridge $instanceId : AXI R Queue Status\n" :+
      L"  AXI R Queue Out: v=${axiR_buffered.valid} payload.data=${axiR_buffered.payload.data} payload.id=${axiR_buffered.payload.id}\n"
    )
  }
  if(enableReadLog) {
    report(
      L"Bridge $instanceId : GMB Read Rsp Out\n" :+
      L"  GMB Read Rsp Out: v=${gmbReadRspOut.valid} r=${gmbReadRspOut.ready} fire=${gmbReadRspOut.fire} data=${gmbReadRspOut.payload.data} id=${gmbReadRspOut.payload.id} error=${gmbReadRspOut.payload.error}\n"
    )
  }
  // +++ CORRECTED: 使用不带参数的 translateWith lambda 语法 +++
  gmbReadRspOut << axiR_buffered.translateWith {
    val rsp = SplitGmbReadRsp(gmbConfig)
    // 直接引用 axiR_buffered.payload
    rsp.data := axiR_buffered.payload.data
    if (gmbConfig.useId) {
      require(gmbReadRspOut.id.getWidth >= axiR_buffered.payload.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
      rsp.id := axiR_buffered.payload.id.resized
    }
    rsp.error := !axiR_buffered.payload.isOKAY()
    rsp
  }

  if(enableReadLog) {
    val cycle = Reg(UInt(32 bits)) init(0)
    cycle := cycle + 1
    report(
      L"Bridge $instanceId Cycle ${cycle}: Read Channel\n" :+
      L"  GMB Read In:  v=${gmbReadCmdIn.valid} r=${gmbReadCmdIn.ready} fire=${gmbReadCmdIn.fire} addr=${gmbReadCmdIn.address}\n" :+
      L"  AXI AR Out:   v=${axiArOut.valid} r=${axiArOut.ready} fire=${axiArOut.fire}\n" :+
      L"  AXI R In:     v=${axiRIn.valid} r=${axiRIn.ready} fire=${axiRIn.fire}"
    )
  }

  // ====================================================================
  // 写通道 (Write Channel)
  // ====================================================================
  val gmbWriteCmdIn = io.gmbIn.write.cmd
  val gmbWriteRspOut = io.gmbIn.write.rsp
  val axiAwOut = io.axiOut.aw
  val axiWOut = io.axiOut.w
  val axiBIn = io.axiOut.b

  // --- 写请求 ---
  val gmbWriteCmdBuffered = gmbWriteCmdIn.queue(2)
  val fork = StreamFork(gmbWriteCmdBuffered, 2)
  val awStream = fork(0)
  val wStream = fork(1)

  // +++ CORRECTED: 使用不带参数的 translateWith lambda 语法 +++
  axiAwOut << awStream.translateWith {
    val aw = Axi4Aw(axiConfig)
    // 直接引用 awStream.payload
    aw.addr := awStream.payload.address.resized
    aw.len := 0
    aw.size := log2Up(gmbConfig.dataWidth.value / 8)
    aw.setBurstINCR()
    if (gmbConfig.useId) { 
      require(aw.id.getWidth >= awStream.payload.id.getWidth, "AXI4 ID width is smaller than GMB ID width")
      aw.id := awStream.payload.id.resized
    } 
    else if (axiConfig.useId) { aw.id := 0 }
    aw
  }

  // +++ CORRECTED: 使用不带参数的 translateWith lambda 语法 +++
  axiWOut << wStream.translateWith {
    val w = Axi4W(axiConfig)
    // 直接引用 wStream.payload
    w.data := wStream.payload.data
    w.strb := wStream.payload.byteEnables
    w.last := True
    w
  }

  // --- 写响应 ---
  val axiB_buffered = axiBIn.queue(2)
  
  // +++ CORRECTED: 使用不带参数的 translateWith lambda 语法 +++
  gmbWriteRspOut << axiB_buffered.translateWith {
    val rsp = SplitGmbWriteRsp(gmbConfig)
    // 直接引用 axiB_buffered.payload
    rsp.error := !axiB_buffered.payload.isOKAY()
    if(gmbConfig.useId) {
      require(rsp.id.getWidth >= axiB_buffered.payload.id.getWidth, "GMB ID width is smaller than AXI4 ID width")
      rsp.id := axiB_buffered.payload.id.resized
    }
    rsp
  }
  
  if(enableWriteLog) {
    val cycle = Reg(UInt(32 bits)) init(0)
    cycle := cycle + 1
    report(
      L"Bridge $instanceId Cycle ${cycle}: Write Channel\n" :+
      L"  GMB Write In: v=${gmbWriteCmdIn.valid} r=${gmbWriteCmdIn.ready} fire=${gmbWriteCmdIn.fire} addr=${gmbWriteCmdIn.address}\n" :+
      L"  AXI AW Out:   v=${axiAwOut.valid} r=${axiAwOut.ready} fire=${axiAwOut.fire} id=${axiAwOut.payload.id}\n" :+
      L"  AXI W Out:    v=${axiWOut.valid} r=${axiWOut.ready} fire=${axiWOut.fire} data=${axiWOut.payload.data} strb=${axiWOut.payload.strb}\n" :+
      L"  AXI B In:     v=${axiBIn.valid} r=${axiBIn.ready} fire=${axiBIn.fire} id=${axiBIn.id} error=${axiBIn.isSLVERR()}"
    )
  }
}
