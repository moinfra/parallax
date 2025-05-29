package parallax.components.memory

import spinal.core._
import spinal.lib._
import parallax.components.icache._

case class InstructionFetchUnitConfig(
    val useICache: Boolean = true,
    val enableFlush: Boolean = false,
    val pcWidth: BitCount = 32 bits,
    val instructionWidth: BitCount = 32 bits, // Width of a single instruction/word in a fetch group
    val fetchGroupDataWidth: BitCount = 64 bits, // Total data width fetched by IFU/ICache per request
    val memBusConfig: GenericMemoryBusConfig,
    var icacheConfig: BasicICacheConfig = null,
    val enableLog: Boolean = true
) {
  require(
    fetchGroupDataWidth.value % instructionWidth.value == 0,
    "fetchGroupDataWidth must be a multiple of instructionWidth"
  )
  val instructionsPerFetchGroup: Int = fetchGroupDataWidth.value / instructionWidth.value
  require(instructionsPerFetchGroup > 0, "Must fetch at least one instruction per group")

  // Create the icacheConfig regardless of useICache, as it defines the io.dataOut structure
  if (icacheConfig == null) {
    icacheConfig = BasicICacheConfig(
      addressWidth = pcWidth,
      dataWidth = instructionWidth, // This is the 'word' size for BasicICache entries
      fetchDataWidth = fetchGroupDataWidth // This is how much BasicICache fetches/outputs in its rsp bundle
      // Other BasicICacheConfig parameters can be defaulted or added here if needed
    )
  } else {
    // Ensure provided icacheConfig matches IFU's expectations
    require(icacheConfig.addressWidth == pcWidth, "Provided icacheConfig.addressWidth mismatch")
    require(icacheConfig.dataWidth == instructionWidth, "Provided icacheConfig.dataWidth mismatch")
    require(
      icacheConfig.fetchDataWidth == fetchGroupDataWidth,
      "Provided icacheConfig.fetchDataWidth mismatch"
    )
  }

  // General address width check for memory bus
  require(memBusConfig.addressWidth.value >= pcWidth.value, "memBus addressWidth too small for pcWidth")

  if (useICache) {
    // BasicICache requires its internal dataWidth (for cache lines/refills)
    // to match the memory bus dataWidth it uses for refills.
    require(
      icacheConfig.dataWidth == memBusConfig.dataWidth,
      "When using ICache, BasicICacheConfig.dataWidth (IFU instructionWidth) must match memBusConfig.dataWidth for cache refills."
    )
    // Address width for cache memory port should also match
    require(
      icacheConfig.addressWidth == memBusConfig.addressWidth,
      "When using ICache, BasicICacheConfig.addressWidth (IFU pcWidth) must match memBusConfig.addressWidth."
    )
  } else { // Direct memory access without ICache
    // For simplicity in the direct path, the memory bus should provide the entire fetch group in one go.
    require(
      fetchGroupDataWidth == memBusConfig.dataWidth,
      "When not using ICache, IFU fetchGroupDataWidth must match memBusConfig.dataWidth for direct memory access."
    )
    // PC width must match memory bus address width for direct addressing
    require(
      pcWidth == memBusConfig.addressWidth,
      "When not using ICache, IFU pcWidth must match memBusConfig.addressWidth."
    )
  }
}

class InstructionFetchUnit(val config: InstructionFetchUnitConfig) extends Component {
  // Implicitly use config.icacheConfig for BasicICache* types
  implicit val advCacheConfigForIFU: BasicICacheConfig = config.icacheConfig
  // Implicitly use config.memBusConfig for BasicICache's memory port constructor
  implicit val genericMemBusConfigForICacheMemPort: GenericMemoryBusConfig = config.memBusConfig

  val io = new Bundle {
    // Input from PC generation (e.g., F0 stage output)
    val pcIn = slave Stream (UInt(config.pcWidth))

    // Output to next pipeline stage (e.g., F1 stage output)
    // BasicICacheCpuRsp is implicitly configured by advCacheConfigForIFU
    val dataOut = master Stream (BasicICacheCpuRsp())

    // ICache Flush interface (only active if ICache is used)
    val flush = config.enableFlush generate slave(BasicICacheFlushBus())

    // External memory bus interface using SplitGenericMemoryBus
    val memBus = master(SplitGenericMemoryBus(config.memBusConfig))
  }

  // --- Component Instantiation ---
  val icache = if (config.useICache) {
    // BasicICache takes BasicICacheConfig and GenericMemoryBusConfig (for its mem port) implicitly
    new BasicICache() // enableLog can be passed if needed: new BasicICache(enableLog = true)
  } else null

  // --- Connections ---
  if (config.useICache) {
    val typedIcach = icache // Just for type inference if needed, already BasicICache

    // Connect ICache to CPU-like interface (pcIn, dataOut)
    // pcIn (Stream[UInt]) -> icache.io.cpu.cmd (Stream[BasicICacheCpuCmd])
    typedIcach.io.cpu.cmd.valid := io.pcIn.valid
    typedIcach.io.cpu.cmd.payload.address := io.pcIn.payload
    io.pcIn.ready := typedIcach.io.cpu.cmd.ready

    io.dataOut <> typedIcach.io.cpu.rsp

    // Connect ICache's memory port (SplitGenericMemoryBus) to IFU's external memBus
    // ICache io.mem is master, IFU io.memBus is master. Connections:
    // IFU.master.cmd << ICache.master.cmd
    // ICache.master.rsp << IFU.master.rsp (Data flows from memBus back to ICache)

    io.memBus.read.cmd << typedIcach.io.mem.read.cmd
    typedIcach.io.mem.read.rsp << io.memBus.read.rsp

    io.memBus.write.cmd << typedIcach.io.mem.write.cmd
    typedIcach.io.mem.write.rsp << io.memBus.write.rsp

    if (config.enableFlush) {
      typedIcach.io.flush <> io.flush
    } else {
      // Ensure flush is properly terminated if present on ICache but not enabled externally
      if (typedIcach.io.flush != null) { // Should always be non-null due to BasicICache definition
        typedIcach.io.flush.cmd.valid := False
        typedIcach.io.flush.cmd.payload.assignDontCare()
        typedIcach.io.flush.rsp.ready := True // Or False, True is usually safer for unused slave inputs
      }
    }

  } else { // No ICache, direct access to external memory via SplitGenericMemoryBus
    val pcReg = Reg(UInt(config.pcWidth)) init (0)
    // pcRegValid indicates if pcReg holds a PC for an outstanding memory request
    val pcRegValid = Reg(Bool()) init (False)

    // We only use the read channel of the SplitGenericMemoryBus for instruction fetch
    io.memBus.write.cmd.valid := False // Never write instructions
    io.memBus.write.cmd.payload.assignDontCare()
    io.memBus.write.rsp.ready := True // Always ready for (non-existent) write responses


    // Drive SplitGenericMemoryBus read command
    io.memBus.read.cmd.valid := io.pcIn.valid && !pcRegValid // Only send new command if no request is pending
    io.memBus.read.cmd.payload.address := io.pcIn.payload
    if(config.memBusConfig.useId) { // Handle ID if membus uses it
        io.memBus.read.cmd.payload.id.assignDontCare() // Or assign a fixed ID, e.g., U(0)
    }
    io.pcIn.ready := io.memBus.read.cmd.ready && !pcRegValid

    when(io.memBus.read.cmd.fire) {
      pcReg := io.pcIn.payload
      pcRegValid := True
    }

    // Drive dataOut from SplitGenericMemoryBus read response
    io.dataOut.valid := io.memBus.read.rsp.valid && pcRegValid // Response is valid and corresponds to our request
    io.dataOut.payload.pc := pcReg
    io.dataOut.payload.fault := io.memBus.read.rsp.payload.error

    // Convert memBus.read.rsp.payload.data into Vec[Bits(instructionWidth)]
    // config.fetchGroupDataWidth == memBusConfig.dataWidth (checked in config)
    // config.icacheConfig.dataWidth == config.instructionWidth
    // config.icacheConfig.fetchDataWidth == config.fetchGroupDataWidth
    // config.icacheConfig.fetchWordsPerFetchGroup == config.instructionsPerFetchGroup
    val rawWordsFromMem = io.memBus.read.rsp.payload.data.subdivideIn(config.instructionWidth)
    
    // The .subdivideIn gives MSB chunk at index 0.
    // If instructions are packed in memory such that the instruction at the lowest address
    // is in the LSB part of the fetched data word, we need to reverse.
    // Example: fetchData=0xDDCCBBAA, instructionWidth=8. subdivideIn gives [DD,CC,BB,AA].
    // If AA is inst0, BB is inst1 etc., then reverse is needed: [AA,BB,CC,DD]
    // Let's assume this LSB-first packing for instructions within a fetch group.
    val orderedInstructions = Vec(rawWordsFromMem.reverse)

    for (i <- 0 until config.instructionsPerFetchGroup) {
      io.dataOut.payload.instructions(i) := orderedInstructions(i)
    }
    
    io.memBus.read.rsp.ready := io.dataOut.ready

    when(io.dataOut.fire) {
      pcRegValid := False // Our request has been served and consumed
    }

    if (config.enableFlush) {
      // Flush is a no-op if no ICache, but bus must be handled
      io.flush.rsp.valid := io.flush.cmd.valid // Respond when command comes
      io.flush.rsp.payload.done := True       // Done immediately
      io.flush.cmd.ready := io.flush.rsp.ready // Ready if downstream is ready
    }
  }
}
