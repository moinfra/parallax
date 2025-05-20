package parallax.components.memory
import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._

  // --- Helper: Axi4SlaveRam (as defined before) ---
  class Axi4SlaveRam(config: Axi4Config, storageSizeInWords: Int, baseAddress: Long = 0) extends Component {
    require(storageSizeInWords > 0, "RAM size must be positive")
    val io = new Bundle {
      val axi = slave(Axi4(config))
    }
    val factory = Axi4SlaveFactory(io.axi)

    // Simulate a small RAM by creating an array of registers
    // This is not efficient for large RAMs but okay for a small test slave.
    val ramData = Vec.fill(storageSizeInWords)(Reg(Bits(config.dataWidth bits)).init(0))

    // For each word in our simulated RAM, create read/write access
    for (i <- 0 until storageSizeInWords) {
      val wordAddress = i * (config.dataWidth / 8)
      factory.readAndWrite(ramData(i), address = baseAddress + wordAddress, bitOffset = 0)
    }

    // Logging
    val cycleCountRam = Reg(UInt(32 bits)) init(0); cycleCountRam := cycleCountRam + 1
    report(L"[Axi4SlaveRam Cycle ${cycleCountRam}] AW.v=${io.axi.aw.valid} AW.r=${io.axi.aw.ready} W.v=${io.axi.w.valid} W.r=${io.axi.w.ready} B.v=${io.axi.b.valid} B.r=${io.axi.b.ready}")
    when(io.axi.aw.fire) { report(L"[Axi4SlaveRam] AW Fire") }
    when(io.axi.w.fire)  { report(L"[Axi4SlaveRam] W Fire") }
    when(io.axi.b.fire)  { report(L"[Axi4SlaveRam] B Fire") }
  }
