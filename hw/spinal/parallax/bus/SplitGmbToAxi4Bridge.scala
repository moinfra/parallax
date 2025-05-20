package parallax.bus

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._
import parallax.components.memory._

class SplitGmbToAxi4Bridge(
  val gmbConfig: GenericMemoryBusConfig,
  val axiConfig: Axi4Config // Use the provided axiConfig
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

  // --- READ CHANNEL ---
  val gmbReadCmd = io.gmbIn.read.cmd
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

  val gmbReadRsp = io.gmbIn.read.rsp
  val axiR = io.axiOut.r

  gmbReadRsp.valid := axiR.valid
  gmbReadRsp.data  := axiR.data
  if (gmbConfig.useId) {
    gmbReadRsp.id := axiR.id.resized // Resize AXI ID back to GMB ID width
  }
  gmbReadRsp.error := !axiR.isOKAY() // isOKAY checks if resp is OKAY (00)
  axiR.ready    := gmbReadRsp.ready // Backpressure from GMB slave for read data

  // --- WRITE CHANNEL ---
  val gmbWriteCmd = io.gmbIn.write.cmd
  val axiAw = io.axiOut.aw
  val axiW = io.axiOut.w
  val axiB = io.axiOut.b

  // GMB WriteCmd needs to be split into AXI AW (address/control) and AXI W (data)
  // We can use the StreamFork2 approach from the example, but adapt it.
  // Since each GMB write command is self-contained (address+data),
  // we effectively want to send AW and W for the *same* GMB command.
  // The GMB command is only "done" when both AW and W are accepted.

  // Staging AW is a good practice to decouple AW and W acceptance on the AXI side.
  // 1. Create an internal (unstaged) AW stream from GMB command.
  // 2. Stage this internal AW stream.
  // 3. Connect W channel data from GMB command.
  // 4. Manage ready signals carefully.

  val (awPathInfo, wPathInfo) = StreamFork2(gmbWriteCmd) // Both forks carry the full GMBWriteCmd payload

  // AW Channel Path (staged)
  val awUnstaged = Stream(Axi4Aw(axiConfig)) // Temporary stream for AW payload
  awUnstaged.valid      := awPathInfo.valid
  awUnstaged.payload.addr  := awPathInfo.address.resized
  if (gmbConfig.useId) {
    awUnstaged.payload.id := awPathInfo.id.resized
  } else {
    if (axiConfig.useId) awUnstaged.payload.id := 0
  }
  // awUnstaged.payload.prot  := B"010"
  awUnstaged.payload.len   := 0
  awUnstaged.payload.size  := log2Up(gmbConfig.dataWidth.value / 8)
  awUnstaged.payload.setBurstINCR()
  awPathInfo.ready  := awUnstaged.ready // awPath is ready if awUnstaged is ready to accept

  val awStaged = awUnstaged.stage() // Register the AW command
  axiAw << awStaged                  // Connect staged AW to AXI output

  // W Channel Path
  axiW.valid := wPathInfo.valid
  axiW.data  := wPathInfo.data
  axiW.strb  := wPathInfo.byteEnables // Use byteEnables from GMB
  axiW.last  := True                  // Single beat transaction, so always last
  wPathInfo.ready := axiW.ready      // wPath is ready if axiW is ready to accept

  // gmbWriteCmd.ready is implicitly (awPathInfo.ready && wPathInfo.ready)
  // due to StreamFork2. This ensures GMB command is consumed only when
  // both AW (pre-stage) and W paths are ready.

  // Write Response Channel
  val gmbWriteRsp = io.gmbIn.write.rsp
  gmbWriteRsp.valid := axiB.valid
  if (gmbConfig.useId) {
    gmbWriteRsp.id := axiB.id.resized
  }
  gmbWriteRsp.error := !axiB.isOKAY()
  axiB.ready     := gmbWriteRsp.ready // Backpressure for write response
}
