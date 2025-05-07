package boson.demo2.system

import spinal.core._
import spinal.lib._
import boson.demo2.common.Config // Assuming Config.XLEN exists
import boson.demo2.components.icache._
import boson.demo2.components.memory._ // For GenericMemoryBus and SimulatedMemory

// Re-use DemoRomProvider
object DemoRomProvider {
  class DemoRom extends AreaObject { 
    val initContent = Seq(
      B(0x00a00093L, 32 bits), B(0x00108133L, 32 bits), B(0x022081b3L, 32 bits), B(0x00000213L, 32 bits),
      B(0x00000013L, 32 bits), B(0x00000013L, 32 bits), B(0x00000013L, 32 bits), B(0x00000013L, 32 bits)
    )
    lazy val rom = Mem(Bits(32 bits), initialContent = initContent)
    rom.addAttribute("ram_style", "block")
  }
}


case class FetchMemorySubsystemConfig(
    useICache: Boolean = true,
    cpuAddressWidth: Int = Config.XLEN, // Ensure this matches global XLEN
    cpuDataWidth: Int = Config.XLEN,    // Instruction width
    // ICache specific config, only used if useICache is true
    icacheConfig: SimpleICacheConfig = SimpleICacheConfig(addressWidth = Config.XLEN, dataWidth = Config.XLEN),
    // SimulatedMemory specific config, only used if useICache is true
    simMemConfig: SimulatedMemoryConfig = SimulatedMemoryConfig(internalDataWidth = 16), // Example internal width
    // Generic bus config for ICache <-> SimMem communication
    // This data width MUST match icacheConfig.dataWidth
    genericMemBusDataWidth: Int = Config.XLEN
) {
    if (useICache) {
        require(icacheConfig.addressWidth == cpuAddressWidth, "ICache address width mismatch.")
        require(icacheConfig.dataWidth == cpuDataWidth, "ICache data width mismatch.")
        require(icacheConfig.dataWidth == genericMemBusDataWidth, "ICache data width must match generic memory bus for fills.")
    }
    val genericMemBusAddrWidth = cpuAddressWidth
}


class FetchMemorySubsystem(val fmConfig: FetchMemorySubsystemConfig) extends Component {
  // Implicitly use fmConfig.icacheConfig if needed by SimpleICache constructor
  implicit val iCacheConfigForInst: SimpleICacheConfig = if(fmConfig.useICache) fmConfig.icacheConfig else null

  val io = new Bundle {
    // Input from PC generation (e.g., F0 stage output)
    val pcIn = slave Stream (UInt(fmConfig.cpuAddressWidth bits))

    // Output to next pipeline stage (e.g., F1 stage output)
    val dataOut = master Stream (new Bundle {
      val pc = UInt(fmConfig.cpuAddressWidth bits)
      val instruction = Bits(fmConfig.cpuDataWidth bits)
      val fault = Bool()
    })

    // ICache Flush interface (only active if ICache is used)
    val flush = slave(ICacheFlushBus())

    // Testbench memory write port (only active if ICache and SimMem are used)
    val tb_mem_write_enable = if(fmConfig.useICache) in Bool() default(False) else null
    val tb_mem_write_address = if(fmConfig.useICache) in UInt(fmConfig.cpuAddressWidth bits) default(0) else null
    // tb_mem_write_data should match SimMem's internalDataWidth
    val tb_mem_write_data = if(fmConfig.useICache) in Bits(fmConfig.simMemConfig.internalDataWidth bits) default(0) else null
  }

  // --- Component Instantiation ---
  val rom = if (!fmConfig.useICache) new DemoRomProvider.DemoRom() else null

  val icache = if (fmConfig.useICache) {
    // This configuration is for the GenericMemoryBus BETWEEN ICache and SimulatedMemory
    val genericMemBusCfg = GenericMemoryBusConfig(
      addressWidth = fmConfig.genericMemBusAddrWidth,
      dataWidth = fmConfig.genericMemBusDataWidth
    )
    new SimpleICache()(fmConfig.icacheConfig, genericMemBusCfg) // Pass both configs
  } else null

  val simMem = if (fmConfig.useICache) {
    val genericMemBusCfg = GenericMemoryBusConfig(
      addressWidth = fmConfig.genericMemBusAddrWidth,
      dataWidth = fmConfig.genericMemBusDataWidth
    )
    new SimulatedMemory(
      memConfig = fmConfig.simMemConfig,
      busConfig = genericMemBusCfg
    )
  } else null


  // --- Connections ---
  if (fmConfig.useICache) {
    // Connect ICache to CPU-like interface (pcIn, dataOut)
    icache.io.cpu.cmd.valid := io.pcIn.valid
    icache.io.cpu.cmd.payload.address := io.pcIn.payload
    io.pcIn.ready := icache.io.cpu.cmd.ready

    io.dataOut.valid := icache.io.cpu.rsp.valid
    io.dataOut.payload.pc := icache.io.cpu.rsp.payload.pc
    io.dataOut.payload.instruction := icache.io.cpu.rsp.payload.instruction
    io.dataOut.payload.fault := icache.io.cpu.rsp.payload.fault
    icache.io.cpu.rsp.ready := io.dataOut.ready

    // Connect ICache to SimulatedMemory via GenericMemoryBus
    icache.io.mem <> simMem.io.bus

    // Connect Flush
    icache.io.flush <> io.flush

    // Connect Testbench write port
    simMem.io.writeEnable := io.tb_mem_write_enable
    simMem.io.writeAddress := io.tb_mem_write_address
    simMem.io.writeData := io.tb_mem_write_data

  } else { // Direct ROM access
    val romMem = rom.rom // Get the actual Mem object
    val romAddrWidth = log2Up(romMem.wordCount) // Address width for the ROM words

    // ROM access is combinational (readAsync), so it's always "ready" for a new PC.
    // The Stream nature means we only produce output when pcIn is valid and dataOut is ready.

    // To better model pipeline behavior and match potential ICache latency,
    // we can introduce a single cycle delay for ROM reads to align output timing.
    // This also makes io.pcIn.ready dependent on io.dataOut.ready.

    val pcReg = Reg(UInt(fmConfig.cpuAddressWidth bits))
    val pcValidReg = Reg(Bool()) init(False)

    io.pcIn.ready := !pcValidReg || io.dataOut.ready // Can accept new PC if buffer is empty or downstream is ready

    when(io.pcIn.fire) {
      pcReg := io.pcIn.payload
      pcValidReg := True
    }

    when(pcValidReg && io.dataOut.ready) {
      pcValidReg := False // Consume the buffered request
    }
    
    // Handle reset for pcValidReg if necessary, e.g. pcValidReg.clearWhen(resetCtrl.isReset)

    // Perform ROM read using the buffered PC
    // Assuming PC is byte-addressed and ROM stores 32-bit words.
    // Convert byte address from PC to word address for ROM.
    val romWordAddress = pcReg >> 2 // pcReg / 4
    
    io.dataOut.valid := pcValidReg
    io.dataOut.payload.pc := pcReg
    io.dataOut.payload.instruction := romMem.readAsync(romWordAddress(romAddrWidth - 1 downto 0))
    io.dataOut.payload.fault := False // ROM access typically doesn't fault in this simple model

    // Flush is a no-op for ROM
    io.flush.rsp.done := io.flush.cmd.fire && io.flush.cmd.payload.start // Done immediately
  }
}
