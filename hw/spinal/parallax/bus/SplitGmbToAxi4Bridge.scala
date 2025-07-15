package parallax.bus

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._
import parallax.components.memory._

class SplitGmbToAxi4Bridge(
  val gmbConfig: GenericMemoryBusConfig,
  val axiConfig: Axi4Config, // Use the provided axiConfig
  // <<<<<<< 1. 参数化控制 >>>>>>>>>
  // cmdInStage:  在GMB命令输入端增加一级流水线
  // rspInStage:  在AXI响应输入端增加一级流水线 (即GMB响应输出的前一级)
  val cmdInStage: Boolean = true,
  val rspInStage: Boolean = true
) extends Component {

  val io = new Bundle {
    val gmbIn = slave(SplitGenericMemoryBus(gmbConfig))
    val axiOut = master(Axi4(axiConfig))
  }

  // --- Sanity Checks (Compile-time) ---
  assert(axiConfig.addressWidth >= gmbConfig.addressWidth.value,
    s"AXI addressWidth (${axiConfig.addressWidth}) must be >= GMB addressWidth (${gmbConfig.addressWidth.value})")
  assert(axiConfig.dataWidth == gmbConfig.dataWidth.value,
    s"AXI dataWidth (${axiConfig.dataWidth}) must be == GMB dataWidth (${gmbConfig.dataWidth.value}) for direct mapping")

  if (gmbConfig.useId) {
    assert(axiConfig.useId, "If GMB uses ID, AXI must also use ID (axiConfig.useId must be true)")
    assert(axiConfig.idWidth >= gmbConfig.idWidth.value,
      s"AXI idWidth (${axiConfig.idWidth}) must be >= GMB idWidth (${gmbConfig.idWidth.value})")
  }
  if(false) report(L"[SplitGmbToAxi4Bridge] io.gmbIn.write.cmd.ready=${io.gmbIn.write.cmd.ready}")
  if(false) report(L"[SplitGmbToAxi4Bridge] io.gmbIn.read.cmd.ready=${io.gmbIn.read.cmd.ready}")
  
  // <<<<<<< 6. 调试和性能监控 >>>>>>>>>
  if(false) {
    report(L"[SplitGmbToAxi4Bridge] Pipeline config: cmdInStage=${cmdInStage}, rspInStage=${rspInStage}")
    report(L"[SplitGmbToAxi4Bridge] Read path: gmbReadCmd.valid=${gmbReadCmd.valid}, axiAr.ready=${axiAr.ready}")
    report(L"[SplitGmbToAxi4Bridge] Write path: gmbWriteCmd.valid=${gmbWriteCmd.valid}, axiAw.valid=${axiAw.valid}, axiW.valid=${axiW.valid}")
  }
  // ====================================================================
  // 读通道 (Read Channel)
  // ====================================================================
  
  // <<<<<<< 2. 对输入的读命令进行流水线化 >>>>>>>>>
  val gmbReadCmd = if (cmdInStage) io.gmbIn.read.cmd.stage() else io.gmbIn.read.cmd
  val axiAr = io.axiOut.ar

  axiAr.valid := gmbReadCmd.valid
  axiAr.addr  := gmbReadCmd.address.resized // Resize in case AXI addressWidth > GMB addressWidth
  if (gmbConfig.useId) {
    axiAr.id := gmbReadCmd.id.resized // Resize in case AXI idWidth > GMB idWidth
  } else {
    if (axiConfig.useId) axiAr.id := 0 // Default ID if AXI uses IDs but GMB doesn't
  }
  // axiAr.prot  := B"010" // Normal, Non-secure, Data access
  axiAr.len   := 0     // Assuming single beat GMB transactions
  axiAr.size  := log2Up(gmbConfig.dataWidth.value / 8) // Size in bytes per beat
  axiAr.setBurstINCR() // INCR burst type (FIXED would also be okay for len=0)
  // Other AXI AR signals (lock, cache, qos, region) default to 0 or spec-defined values.

  gmbReadCmd.ready := axiAr.ready

  // <<<<<<< 3. 对输入的读响应进行流水线化 >>>>>>>>>
  val gmbReadRsp = io.gmbIn.read.rsp
  val axiR = if (rspInStage) io.axiOut.r.stage() else io.axiOut.r

  gmbReadRsp.valid := axiR.valid
  gmbReadRsp.data  := axiR.data
  if (gmbConfig.useId) {
    gmbReadRsp.id := axiR.id.resized // Resize AXI ID back to GMB ID width
  }
  gmbReadRsp.error := !axiR.isOKAY() // isOKAY checks if resp is OKAY (00)
  axiR.ready    := gmbReadRsp.ready // Backpressure from GMB slave for read data

  // ====================================================================
  // 写通道 (Write Channel)
  // ====================================================================
  
  // <<<<<<< 4. 对输入的写命令进行流水线化，并保证AW/W同步 >>>>>>>>>
  val gmbWriteCmd = if (cmdInStage) io.gmbIn.write.cmd.stage() else io.gmbIn.write.cmd
  val axiAw = io.axiOut.aw
  val axiW = io.axiOut.w
  val axiB = io.axiOut.b

  // GMB WriteCmd needs to be split into AXI AW (address/control) and AXI W (data)
  // We can use the StreamFork2 approach from the example, but adapt it.
  // Since each GMB write command is self-contained (address+data),
  // we effectively want to send AW and W for the *same* GMB command.
  // The GMB command is only "done" when both AW and W are accepted.

  val (awPathInfo, wPathInfo) = StreamFork2(gmbWriteCmd) // Both forks carry the full GMBWriteCmd payload

  // 关键改进：在Fork后、translate前进行stage，确保AW/W同步
  val awStaged = if (cmdInStage) awPathInfo.stage() else awPathInfo
  val wStaged = if (cmdInStage) wPathInfo.stage() else wPathInfo

  // AW Channel Path
  axiAw << awStaged.translateWith {
    val aw = Axi4Aw(axiConfig)
    aw.addr := awStaged.address.resized
    if (gmbConfig.useId) {
      aw.id := awStaged.id.resized
    } else {
      if (axiConfig.useId) aw.id := 0
    }
    // aw.prot := B"010"
    aw.len := 0
    aw.size := log2Up(gmbConfig.dataWidth.value / 8)
    aw.setBurstINCR()
    aw
  }

  // W Channel Path
  axiW << wStaged.translateWith {
    val w = Axi4W(axiConfig)
    w.data := wStaged.data
    w.strb := wStaged.byteEnables
    w.last := True
    w
  }

  // <<<<<<< 关键: 同步验证 >>>>>>>>>
  when(axiAw.valid || axiW.valid) {
    assert(axiAw.valid === axiW.valid, "AW and W must be synchronized")
  }

  // gmbWriteCmd.ready is implicitly (awPathInfo.ready && wPathInfo.ready)
  // due to StreamFork2. This ensures GMB command is consumed only when
  // both AW (pre-stage) and W paths are ready.

  // --- 写响应通道 ---
  // <<<<<<< 5. 对输入的写响应进行流水线化 >>>>>>>>>
  val gmbWriteRsp = io.gmbIn.write.rsp
  val axiBStaged = if (rspInStage) axiB.stage() else axiB
  
  gmbWriteRsp.valid := axiBStaged.valid
  if (gmbConfig.useId) {
    gmbWriteRsp.id := axiBStaged.id.resized
  }
  gmbWriteRsp.error := !axiBStaged.isOKAY()
  axiBStaged.ready := gmbWriteRsp.ready // Backpressure for write response

  when(axiBStaged.valid && !axiBStaged.isOKAY()) {
    if(false) report(L"[SplitGmbToAxi4Bridge] AXI write response error: ${axiBStaged.resp}")
  }
}
