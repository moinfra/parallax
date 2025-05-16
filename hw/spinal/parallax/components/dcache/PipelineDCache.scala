package parallax.components.dcache

import spinal.lib.pipeline.Stageable
import spinal.lib._
import spinal.core._

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.bus._ // Your bus definitions
import parallax.components.memory._ // For mem-side
import scala.collection.mutable.ArrayBuffer
import spinal.lib.pipeline.Connection.M2S

object MemoryWriteUtils {

  /** Applies byte-enabled write to a data word.
    * For each byte, if the corresponding enable bit is set, the new data's byte is used.
    * Otherwise, the original data's byte is preserved.
    *
    * @param originalData The original data word (e.g., read from memory or cache).
    * @param newData      The new data word from the CPU/writer.
    * @param byteEnables  A bitmask where each bit corresponds to a byte in the data word.
    *                     If byteEnables(i) is True, the i-th byte of newData is written.
    * @param dataWidth    The total width of originalData and newData in bits.
    * @return             The modified data word.
    */
  def applyByteEnables(
      originalData: Bits,
      newData: Bits,
      byteEnables: Bits,
      dataWidth: Int
  ): Bits = {
    require(originalData.getWidth == dataWidth, s"originalData width must be $dataWidth bits")
    require(newData.getWidth == dataWidth, s"newData width must be $dataWidth bits")
    val numBytes = dataWidth / 8
    require(byteEnables.getWidth == numBytes, s"byteEnables width must be $numBytes bits")

    val result = Bits(dataWidth bits)
    result.assignDontCare() // Initialize

    for (i <- 0 until numBytes) {
      val currentByteOriginal = originalData((i + 1) * 8 - 1 downto i * 8)
      val currentByteNew = newData((i + 1) * 8 - 1 downto i * 8)
      result((i + 1) * 8 - 1 downto i * 8) := Mux(byteEnables(i), currentByteNew, currentByteOriginal)
    }
    result
  }

  /** Updates a specific word within a larger data line using byte enables.
    *
    * @param originalLine  The entire data line (e.g., a cache line).
    * @param wordOffset    The index of the word within the line to be modified.
    * @param newDataForWord The new data for the specific word.
    * @param byteEnablesForWord Byte enables for the specific word.
    * @param wordDataWidth The width of a single word (and newDataForWord) in bits.
    * @param lineDataWidth The total width of the originalLine in bits.
    * @return              The modified data line.
    */
  def updateWordInLine(
      originalLine: Bits,
      wordOffset: UInt, // UInt for dynamic selection if needed, or Int if static
      newDataForWord: Bits,
      byteEnablesForWord: Bits,
      wordDataWidth: Int,
      lineDataWidth: Int
  ): Bits = {
    require(lineDataWidth % wordDataWidth == 0, "lineDataWidth must be a multiple of wordDataWidth")
    require(newDataForWord.getWidth == wordDataWidth, s"newDataForWord width must be $wordDataWidth bits")
    val numBytesInWord = wordDataWidth / 8
    require(byteEnablesForWord.getWidth == numBytesInWord, s"byteEnablesForWord width must be $numBytesInWord bits")
    val numWordsInLine = lineDataWidth / wordDataWidth
    require(
      wordOffset.getWidth >= log2Up(numWordsInLine),
      s"wordOffset width is too small for the number of words in the line. Need at least ${log2Up(numWordsInLine)} bits."
    )

    val modifiedWord = applyByteEnables(
      originalData = B(0, wordDataWidth bits), // Placeholder, will be selected below
      newData = newDataForWord,
      byteEnables = byteEnablesForWord,
      dataWidth = wordDataWidth
    )
    // We need to apply byte enables to the *correct original word* selected by wordOffset.

    val resultingLine = Bits(lineDataWidth bits)
    val originalWordsVec = originalLine.subdivideIn(wordDataWidth bits) // This is a Vec[Bits]

    for (i <- 0 until numWordsInLine) {
      val currentOriginalWord = originalWordsVec(i)
      when(wordOffset === U(i)) {
        // If this is the target word, apply byte enables to *this* original word and newData
        resultingLine.subdivideIn(wordDataWidth bits)(i) := applyByteEnables(
          currentOriginalWord,
          newDataForWord,
          byteEnablesForWord,
          wordDataWidth
        )
      } otherwise {
        // Otherwise, keep the original word for this part of the line
        resultingLine.subdivideIn(wordDataWidth bits)(i) := currentOriginalWord
      }
    }
    resultingLine
  }
}

// Placeholder for DCache specific configuration
case class PipelineDCacheConfig(
    cacheSize: Int = (4 KiB).toInt,
    bytePerLine: Int = 32,
    cpuAddrWidth: Int = 32,
    cpuDataWidth: Int = 32, // Word width for CPU data access
    memDataWidth: Int = 32, // Width of the bus to main memory (can be same or wider than cpuDataWidth)
    // For WTNA, we might write cpuDataWidth chunks to memory.
    // For WBWA, we'd write memDataWidth chunks (a full line).
    // Let's assume memDataWidth is for line transfers for now.
    // And WTNA writes will use cpuDataWidth granularity on the GenericMemoryBus.
    wayCount: Int = 1, // Direct-mapped for now
    // Add other params: replacement policy (for N-way), etc.
    numMshrs: Int = 2,
) {
  // Derived parameters (similar to SimpleICacheConfig)
  val lineCount: Int = cacheSize / bytePerLine / wayCount
  require(isPow2(lineCount), "Cache line count must be a power of 2")
  require(isPow2(bytePerLine), "Bytes per line must be a power of 2")
  val bitsPerLine: Int = bytePerLine * 8
  val wordsPerLine: Int = bytePerLine / (cpuDataWidth / 8)
  require(wordsPerLine > 0)

  val indexWidth: Int = log2Up(lineCount)
  val lineOffsetWidth: Int = log2Up(wordsPerLine) // Offset of a word within a line
  val byteOffsetInLineWords: Int = log2Up(bytePerLine) // Offset of a byte within a line (used for tag calculation)
  val tagWidth: Int = cpuAddrWidth - indexWidth - byteOffsetInLineWords

  def tag(address: UInt): UInt = address(cpuAddrWidth - 1 downto indexWidth + byteOffsetInLineWords)
  def index(address: UInt): UInt = address(indexWidth + byteOffsetInLineWords - 1 downto byteOffsetInLineWords)
  def lineOffset(address: UInt): UInt = address(byteOffsetInLineWords - 1 downto log2Up(cpuDataWidth / 8))

  // Byte enables generation based on address and size
  def generateByteEnables(address: UInt, size: UInt, dataWidth: Int): Bits = {
    val byteEnables = Bits(dataWidth / 8 bits)
    byteEnables.clearAll()
    val startByte = address(log2Up(dataWidth / 8) - 1 downto 0)
    switch(size) {
      is(0) { // Byte
        byteEnables(startByte) := True
      }
      is(1) { // Half-word
        byteEnables(startByte, 2 bits) := B"11"
      }
      is(2) { // Word
        byteEnables.setAll()
      }
      // default { byteEnables.clearAll() } // Or handle error
    }
    byteEnables
  }
}

object PipelineDCacheConfig {

  // --- Scala Address Decomposition Functions (Runtime/Simulation time) ---
  // These operate on Scala BigInt/Long/Int types

  def tag(address: BigInt, cfg: PipelineDCacheConfig): BigInt = {
    val tagShift = cfg.indexWidth + cfg.byteOffsetInLineWords
    val tagMask = (BigInt(1) << cfg.tagWidth) - 1
    (address >> tagShift) & tagMask
  }

  def index(address: BigInt, cfg: PipelineDCacheConfig): BigInt = {
    val indexShift = cfg.byteOffsetInLineWords
    val indexMask = (BigInt(1) << cfg.indexWidth) - 1
    (address >> indexShift) & indexMask
  }

  def lineOffset(address: BigInt, cfg: PipelineDCacheConfig): BigInt = {
    val wordShift = log2Up(cfg.cpuDataWidth / 8)
    val offsetMask = (BigInt(1) << cfg.lineOffsetWidth) - 1 // lineOffsetWidth calculated based on wordsPerLine
    (address >> wordShift) & offsetMask
  }
  
  def lineAddress(address: BigInt, cfg: PipelineDCacheConfig): BigInt = {
      val mask = (BigInt(1) << cfg.byteOffsetInLineWords) - 1
      address & ~mask // Zero out the byte offset within the line
  }

  // Optional: Scala version of generateByteEnables if needed in testbench logic
  def generateByteEnables(address: BigInt, size: Int, dataWidth: Int): BigInt = {
      val numBytes = dataWidth / 8
      var byteEnables: BigInt = 0
      val startByte = address & (numBytes - 1) // Get byte offset within the word

      size match {
          case 0 => // Byte
              byteEnables = BigInt(1) << startByte.toInt
          case 1 => // Half-word
              require((startByte & 1) == 0, "Half-word access must be 2-byte aligned")
              byteEnables = BigInt(3) << startByte.toInt
          case 2 => // Word
              require((startByte & (numBytes -1)) == 0 || numBytes <= 1, s"Word access must be $numBytes-byte aligned")
              byteEnables = (BigInt(1) << numBytes) - 1
          case _ => 
              throw new IllegalArgumentException(s"Unsupported size: $size")
      }
      byteEnables
  }
}


class PipelineDCache(
    val dcacheConfig: PipelineDCacheConfig,
    val memBusConfig: GenericMemoryBusConfig
) extends Component {

  val cpuAddrWidth = dcacheConfig.cpuAddrWidth
  val cpuDataWidth = dcacheConfig.cpuDataWidth
  val memDataWidth = memBusConfig.dataWidth
  val bitsPerLine = dcacheConfig.bitsPerLine
  val wordsPerLine = dcacheConfig.wordsPerLine
  val tagWidth = dcacheConfig.tagWidth
  val indexWidth = dcacheConfig.indexWidth
  val lineOffsetWidth = dcacheConfig.lineOffsetWidth
  val byteOffsetInLineWords = dcacheConfig.byteOffsetInLineWords // Renamed for clarity from byteOffsetInLine

  val io = new Bundle {
    val cpu = slave(DataMemoryAccessBus(cpuAddrWidth, cpuDataWidth))
    val mem = master(GenericMemoryBus(memBusConfig))
    // val flush = slave(...) // 暂不实现 Flush
  }

  // --- Stageable signals for DCache pipeline ---
  object DCachePipelineContext {
    val CPU_CMD_PAYLOAD = Stageable(DataAccessCmdPayload(cpuAddrWidth, cpuDataWidth))
    val PHYSICAL_ADDRESS = Stageable(UInt(cpuAddrWidth bits))

    val TAG = Stageable(UInt(tagWidth bits))
    val INDEX = Stageable(UInt(indexWidth bits))
    val LINE_OFFSET = Stageable(UInt(lineOffsetWidth bits))
    val BYTE_ENABLES = Stageable(Bits(cpuDataWidth / 8 bits))
    val IS_WRITE = Stageable(Bool()) // Store isWrite for later stages

    val TAG_MEM_READ_VALID_BIT = Stageable(Bool()) // Valid bit from validArray
    val TAG_MEM_READ_TAG_DATA = Stageable(UInt(tagWidth bits))
    val DATA_MEM_READ_LINE = Stageable(Bits(bitsPerLine bits))

    val HIT_STATUS = Stageable(Bool())

    // For memory interaction (WTNA or Read Miss Fill)
    val MEM_REQ_NEEDED = Stageable(Bool()) // True if this operation needs to access main memory
    val MEM_REQ_ADDRESS = Stageable(UInt(cpuAddrWidth bits)) // Address for memory (byte or line)
    val MEM_REQ_IS_WRITE = Stageable(Bool()) // Type of memory access
    val MEM_REQ_DATA = Stageable(Bits(cpuDataWidth bits)) // Data for memory write (WTNA)
    val MEM_REQ_BYTE_ENABLES = Stageable(Bits(cpuDataWidth / 8 bits)) // Byte enables for memory write (WTNA)

    // For line refill
    val IS_READ_MISS_REFILL = Stageable(Bool()) // True if current memory op is for a read miss line fill
    val REFILL_TARGET_INDEX = Stageable(UInt(indexWidth bits)) // Index being refilled
    val REFILL_TARGET_TAG = Stageable(UInt(tagWidth bits)) // Tag for the line being refilled
    val REFILL_BUFFER = Stageable(Bits(bitsPerLine bits))
    val REFILL_WORD_COUNTER = Stageable(UInt(log2Up(wordsPerLine) bits)) // Counts words received for a line

    // Output to CPU
    val CPU_RSP_DATA = Stageable(Bits(cpuDataWidth bits))
    val CPU_RSP_FAULT = Stageable(Bool())
  }
  import DCachePipelineContext._

  // --- Cache Memory Declarations ---
  val tagArray = Mem(UInt(tagWidth bits), dcacheConfig.lineCount)
  val validArray = Mem(Bool(), dcacheConfig.lineCount)
  validArray.init(Seq.fill(dcacheConfig.lineCount)(False))
  val dataArray = Mem(Bits(bitsPerLine bits), dcacheConfig.lineCount)

  // --- Pipeline Definition ---
  val pipeline = new Pipeline {
    // Helper to create and connect stages
    val stages = ArrayBuffer[Stage]()
    var previousStage: Stage = null
    def appendStage(name: String): Stage = {
      val s = newStage().setName(name)
      if (previousStage != null) connect(previousStage, s)(M2S()) // Default M->S connection
      stages += s
      previousStage = s
      s
    }

    // --- S0: Command Decode ---
    val s0_decode = appendStage("S0_Decode")
    val onS0 = new Area {
      import s0_decode._ // Import stage context for easy access to signals

      // Connect to CPU command input
      // haltIt(io.cpu.cmd.isStall) // If pipeline is stalled by S0, reflect to CPU
      s0_decode(CPU_CMD_PAYLOAD) := io.cpu.cmd.payload
      s0_decode(PHYSICAL_ADDRESS) := io.cpu.cmd.payload.address
      s0_decode(IS_WRITE) := io.cpu.cmd.payload.isWrite
      s0_decode.valid := io.cpu.cmd.valid
      io.cpu.cmd.ready := isReady // Pipeline ready for new command

      // Decompose address
      val addr = s0_decode(PHYSICAL_ADDRESS)
      s0_decode(TAG) := dcacheConfig.tag(addr)
      s0_decode(INDEX) := dcacheConfig.index(addr)
      s0_decode(LINE_OFFSET) := dcacheConfig.lineOffset(addr)
      s0_decode(BYTE_ENABLES) := dcacheConfig.generateByteEnables(
        addr,
        s0_decode(CPU_CMD_PAYLOAD).size,
        cpuDataWidth
      )
      // Initialize signals for later stages
      s0_decode(MEM_REQ_NEEDED) := False
      s0_decode(IS_READ_MISS_REFILL) := False
      s0_decode(CPU_RSP_FAULT) := False
    }

    // --- S1: Cache Read (Tag and Data) ---
    val s1_cacheRead = appendStage("S1_CacheRead")
    val onS1 = new Area {
      import s1_cacheRead._

      val indexToRead = s1_cacheRead(INDEX)
      s1_cacheRead(TAG_MEM_READ_VALID_BIT) := validArray.readAsync(address = indexToRead)
      s1_cacheRead(TAG_MEM_READ_TAG_DATA) := tagArray.readAsync(address = indexToRead)
      s1_cacheRead(DATA_MEM_READ_LINE) := dataArray.readAsync(address = indexToRead)

      // Pass through necessary signals
      s1_cacheRead(CPU_CMD_PAYLOAD) := s0_decode(CPU_CMD_PAYLOAD) // Implicitly passed if not modified
      s1_cacheRead(PHYSICAL_ADDRESS) := s0_decode(PHYSICAL_ADDRESS)
      s1_cacheRead(IS_WRITE) := s0_decode(IS_WRITE)
      s1_cacheRead(TAG) := s0_decode(TAG)
      s1_cacheRead(INDEX) := s0_decode(INDEX) // Needed for write-back or refill write
      s1_cacheRead(LINE_OFFSET) := s0_decode(LINE_OFFSET)
      s1_cacheRead(BYTE_ENABLES) := s0_decode(BYTE_ENABLES)
      s1_cacheRead(MEM_REQ_NEEDED) := s0_decode(MEM_REQ_NEEDED)
      s1_cacheRead(IS_READ_MISS_REFILL) := s0_decode(IS_READ_MISS_REFILL)
      s1_cacheRead(CPU_RSP_FAULT) := s0_decode(CPU_RSP_FAULT)
    }

    // --- S2: Compare, Hit/Miss Logic, Prepare Memory Request / CPU Response ---
    val s2_compare = appendStage("S2_Compare")
    
    val onS2 = new Area {
      import s2_compare._

      val cmd = s2_compare(CPU_CMD_PAYLOAD)
      val isWrite = s2_compare(IS_WRITE)
      val tagMatch = s2_compare(TAG_MEM_READ_TAG_DATA) === s2_compare(TAG)
      val lineIsValid = s2_compare(TAG_MEM_READ_VALID_BIT)
      val hit = tagMatch && lineIsValid
      s2_compare(HIT_STATUS) := hit

      // Default values for outputs from this stage
      s2_compare(CPU_RSP_DATA).assignDontCare()
      // s2_compare(CPU_RSP_FAULT) is passed through from S1 and potentially modified.

      s2_compare(MEM_REQ_NEEDED) := False // Default: no memory request from this operation yet
      s2_compare(MEM_REQ_ADDRESS).assignDontCare()
      s2_compare(MEM_REQ_IS_WRITE) := False
      s2_compare(MEM_REQ_DATA).assignDontCare()
      s2_compare(MEM_REQ_BYTE_ENABLES).assignDontCare()

      s2_compare(IS_READ_MISS_REFILL) := False // Default: not a read miss refill
      s2_compare(REFILL_TARGET_INDEX).assignDontCare()
      s2_compare(REFILL_TARGET_TAG).assignDontCare()


      // MEM_REQ_NEEDED, IS_READ_MISS_REFILL, CPU_RSP_FAULT are passed through and modified below

      // Cache write ports - default to no write
      val doDataWrite = False
      val dataToWrite = Bits(bitsPerLine bits).assignDontCare()
      dataArray.write(
        address = s2_compare(INDEX),
        data = dataToWrite,
        enable = s2_compare.isFiring && doDataWrite // Write only when stage fires and flag is set
      )
      val doTagWrite = False
      val tagToWrite = UInt(tagWidth bits).assignDontCare()
      tagArray.write(
        address = s2_compare(INDEX),
        data = tagToWrite,
        enable = s2_compare.isFiring && doTagWrite
      )
      val doValidWrite = False
      val validBitToWrite = Bool().assignDontCare()
      validArray.write( // Assuming Mem(Bool) can be written this way, or use Reg Vec
        address = s2_compare(INDEX),
        data = validBitToWrite,
        enable = s2_compare.isFiring && doValidWrite
      )

      when(hit) { // HIT
        report(L"DCache S2: HIT. Addr=${s2_compare(PHYSICAL_ADDRESS)}, IsWrite=${isWrite}")
        when(isWrite) { // Write Hit (WTNA)
          // 1. Update Cache Data Array
          val originalLine = s2_compare(DATA_MEM_READ_LINE)
          val cpuData = cmd.data
          val lineOffset = s2_compare(LINE_OFFSET)
          val byteEnables = s2_compare(BYTE_ENABLES)

          dataToWrite := MemoryWriteUtils.updateWordInLine(
            originalLine = originalLine,
            wordOffset = lineOffset, // Pass the UInt directly
            newDataForWord = cpuData,
            byteEnablesForWord = byteEnables,
            wordDataWidth = cpuDataWidth,
            lineDataWidth = bitsPerLine
          )
          doDataWrite := True
          // Tag and Valid remain unchanged for write hit

          // 2. Prepare Write-Through to memory
          s2_compare(MEM_REQ_NEEDED) := True
          s2_compare(MEM_REQ_ADDRESS) := s2_compare(PHYSICAL_ADDRESS) // Byte address
          s2_compare(MEM_REQ_IS_WRITE) := True
          s2_compare(MEM_REQ_DATA) := cpuData // WTNA writes cpuDataWidth
          s2_compare(MEM_REQ_BYTE_ENABLES) := byteEnables
          // CPU response for write will be an ack after memory op (or error)
          // No data to return to CPU now. Fault will be set in S4 if mem op fails.
        } otherwise { // Read Hit
          val lineData = s2_compare(DATA_MEM_READ_LINE)
          val wordOffset = s2_compare(LINE_OFFSET)
          val byteEnables = s2_compare(BYTE_ENABLES) // For potential sub-word selection

          // Extract word (already word aligned due to LINE_OFFSET)
          var selectedData = lineData.subdivideIn(cpuDataWidth bits).reverse(wordOffset)
          // Apply byte selection if needed (e.g. for LDBU, LBHU - though typically done in CPU)
          // For now, assume CPU handles sub-word extraction from the word.
          s2_compare(CPU_RSP_DATA) := selectedData
          s2_compare(MEM_REQ_NEEDED) := False // No memory access for read hit
        }
      } otherwise { // MISS
        report(L"DCache S2: MISS. Addr=${s2_compare(PHYSICAL_ADDRESS)}, IsWrite=${isWrite}")
        when(isWrite) { // Write Miss (WTNA: No-Write-Allocate / Write-Around)
          s2_compare(MEM_REQ_NEEDED) := True
          s2_compare(MEM_REQ_ADDRESS) := s2_compare(PHYSICAL_ADDRESS)
          s2_compare(MEM_REQ_IS_WRITE) := True
          s2_compare(MEM_REQ_DATA) := cmd.data
          s2_compare(MEM_REQ_BYTE_ENABLES) := s2_compare(BYTE_ENABLES)
          // Cache is not modified. CPU response after memory op.
        } otherwise { // Read Miss
          s2_compare(MEM_REQ_NEEDED) := True
          s2_compare(IS_READ_MISS_REFILL) := True // Signal that this mem req is for a refill
          // Request the whole line from memory
          val lineBaseAddress =
            (s2_compare(TAG) ## s2_compare(INDEX) ## U(0, byteOffsetInLineWords bits)).asUInt.resized
          s2_compare(MEM_REQ_ADDRESS) := lineBaseAddress
          s2_compare(MEM_REQ_IS_WRITE) := False
          s2_compare(MEM_REQ_DATA).assignDontCare() // No data for read
          s2_compare(MEM_REQ_BYTE_ENABLES).setAll() // Read whole words from memory for line

          // Store context for refill completion
          s2_compare(REFILL_TARGET_INDEX) := s2_compare(INDEX)
          s2_compare(REFILL_TARGET_TAG) := s2_compare(TAG)

          // This stage (and upstream) must HALT until refill is complete.
          // The actual halt signal will be driven by S3 based on refill progress.
          // S2 itself doesn't know when refill finishes, S3 will manage that.
          // We expect S3 to stall S2 if a refill is ongoing.
          report(L"DCache S2: Read MISS - Initiating line refill request. Addr=${lineBaseAddress}")
        }
      }
      
      s2_compare(CPU_CMD_PAYLOAD) := s1_cacheRead(CPU_CMD_PAYLOAD)
      s2_compare(PHYSICAL_ADDRESS) := s1_cacheRead(PHYSICAL_ADDRESS)
      s2_compare(IS_WRITE) := s1_cacheRead(IS_WRITE)
      s2_compare(TAG) := s1_cacheRead(TAG)
      s2_compare(INDEX) := s1_cacheRead(INDEX)
      s2_compare(LINE_OFFSET) := s1_cacheRead(LINE_OFFSET)
      s2_compare(BYTE_ENABLES) := s1_cacheRead(BYTE_ENABLES)
      s2_compare(CPU_RSP_FAULT) := s1_cacheRead(CPU_RSP_FAULT)
    }

    // --- S3: Memory Access & Refill Control ---
    // This stage interacts with io.mem.
    // For Read Misses, it manages the line refill process.
    // For WTNA writes, it sends the write to memory.
    val s3_memoryAccess = appendStage("S3_MemoryAccess")
    val onS3 = new Area {
      import s3_memoryAccess._

      // Refill state registers (only active if IS_READ_MISS_REFILL)
      val refillWordCounter = Reg(UInt(log2Up(wordsPerLine) bits)) init (0)
      val refillBuffer = Reg(Bits(bitsPerLine bits)) init (0)
      val isRefilling = Reg(Bool()) init (False) // True if a line refill is in progress

      report(L"DCache S3: Entering stage. MEM_REQ_NEEDED=${s3_memoryAccess(MEM_REQ_NEEDED)}, IS_READ_MISS_REFILL=${s3_memoryAccess(IS_READ_MISS_REFILL)}, isRefilling=${isRefilling}")

      // Default memory interface signals
      io.mem.cmd.valid := False
      io.mem.cmd.payload.assignDontCare()
      io.mem.rsp.ready := False

      // Default pass-through for CPU response data (might be overwritten by refill)
      s3_memoryAccess(CPU_RSP_DATA) := s3_memoryAccess(CPU_RSP_DATA) // from S2
      s3_memoryAccess(CPU_RSP_FAULT) := s3_memoryAccess(CPU_RSP_FAULT) // from S2

      when(s3_memoryAccess(MEM_REQ_NEEDED) && !isRefilling) { // New memory request from S2
        when(s3_memoryAccess(IS_READ_MISS_REFILL)) { // Read Miss - Start Refill
          report(
            L"DCache S3: Starting Read Miss Refill. LineAddr=${s3_memoryAccess(MEM_REQ_ADDRESS)}, TargetIdx=${s3_memoryAccess(REFILL_TARGET_INDEX)}"
          )
          isRefilling := True
          refillWordCounter := 0
          refillBuffer := B(0) // Clear buffer
          // S3 (and thus S2, S1, S0) will be stalled by !isRefilling condition below
          // until refill completes.
          haltIt() // Halt S3 to start refill FSM logic here
        } otherwise { // Write-Through or Write-Miss (WTNA)
          report(L"DCache S3: Processing WTNA Memory Write. Addr=${s3_memoryAccess(MEM_REQ_ADDRESS)}")
          io.mem.cmd.valid := True // Send the single word write
          io.mem.cmd.payload.address := s3_memoryAccess(MEM_REQ_ADDRESS)
          io.mem.cmd.payload.opcode := GenericMemoryBusOpcode.OP_WRITE
          io.mem.cmd.payload.writeData := s3_memoryAccess(MEM_REQ_DATA)
          // Assuming GenericMemoryBus handles byte enables if memBusConfig.dataWidth matches cpuDataWidth
          // If memBusConfig.dataWidth is wider, MEM_REQ_BYTE_ENABLES needs careful mapping.
          // For now, assume memBusConfig.dataWidth == cpuDataWidth for WTNA writes.
          // io.mem.cmd.payload.byteEnable := s3_memoryAccess(MEM_REQ_BYTE_ENABLES) // If supported

          io.mem.rsp.ready := True // Be ready for a response (e.g., error ack)
          when(io.mem.cmd.fire) {
            report(L"DCache S3: WTNA Memory Write CMD Fired. Addr=${io.mem.cmd.payload.address}")
            // For WTNA, we might not wait for rsp if it's just an ack.
            // Or we wait for rsp to get error status.
            // If we wait, S3 needs to stall.
            // For now, assume WTNA write completes quickly or is buffered by memory.
            // If io.mem.rsp.fire, then s3_memoryAccess(CPU_RSP_FAULT) |= io.mem.rsp.payload.error
            // This part needs to be robust: wait for rsp if mem protocol requires it.
            // Let's assume a simple model: fire cmd, if rsp comes with error, set fault.
            // This requires S3 to stall until rsp.
            haltIt() // Halt until response for this write
          }
          when(io.mem.rsp.fire) { // Response for the WTNA write
            s3_memoryAccess(CPU_RSP_FAULT) := io.mem.rsp.payload.error // Update fault status
            haltIt(False) // Unstall S3
            report(L"DCache S3: WTNA Memory Write RSP Fired. Error=${io.mem.rsp.payload.error}")
          }
        }
      }

      when(isRefilling) { // Actively refilling a line
        haltIt() // Keep S3 (and upstream) stalled during refill

        val currentRefillAddress = s3_memoryAccess(MEM_REQ_ADDRESS) + (refillWordCounter << log2Up(memDataWidth / 8))
        report(
          L"DCache S3: Refilling... Word ${refillWordCounter}/${(wordsPerLine - 1).toString()}. Requesting Addr ${currentRefillAddress}"
        )

        io.mem.cmd.valid := True
        io.mem.cmd.payload.address := currentRefillAddress
        io.mem.cmd.payload.opcode := GenericMemoryBusOpcode.OP_READ
        io.mem.rsp.ready := True

        when(io.mem.cmd.fire) {
          report(L"DCache S3: Refill CMD Fired for word ${refillWordCounter}. Addr ${currentRefillAddress}")
        }

        when(io.mem.rsp.fire) {
          report(L"DCache S3: Refill RSP Fired for word ${refillWordCounter}. Data ${io.mem.rsp.payload.readData}")
          // Place received data into refillBuffer
          // Assuming memDataWidth is the granularity of refill.
          // If memDataWidth == bitsPerLine, one read is enough.
          // If memDataWidth < bitsPerLine, multiple reads are needed.
          // The refillWordCounter here counts chunks of memDataWidth.
          // For simplicity, assume memDataWidth is cpuDataWidth for now, matching wordsPerLine.
          val wordIdxInLine = refillWordCounter
          refillBuffer.subdivideIn(cpuDataWidth bits).reverse(wordIdxInLine) := io.mem.rsp.payload.readData.resized

          s3_memoryAccess(CPU_RSP_FAULT) := io.mem.rsp.payload.error // Accumulate errors

          refillWordCounter := refillWordCounter + 1
          when(refillWordCounter === (wordsPerLine - 1)) { // Last word received
            report(L"DCache S3: Refill COMPLETE. Writing to cache. Fault status: ${s3_memoryAccess(CPU_RSP_FAULT)}")
            // Write to cache arrays
            dataArray.write(s3_memoryAccess(REFILL_TARGET_INDEX), refillBuffer) // Write the completed line
            tagArray.write(s3_memoryAccess(REFILL_TARGET_INDEX), s3_memoryAccess(REFILL_TARGET_TAG))
            validArray.write(s3_memoryAccess(REFILL_TARGET_INDEX), True)

            isRefilling := False // Refill done
            haltIt(False) // Unstall S3, S2 will re-evaluate next cycle

            // Prepare data for CPU if this was a read miss
            // S2 will re-evaluate and it should be a hit.
            // The data for CPU_RSP_DATA will be picked up by S2's hit logic.
          }
        }
      }
      // Pass through signals needed by S4
      s3_memoryAccess(CPU_CMD_PAYLOAD) := s2_compare(CPU_CMD_PAYLOAD)
      s3_memoryAccess(PHYSICAL_ADDRESS) := s2_compare(PHYSICAL_ADDRESS)
      s3_memoryAccess(IS_WRITE) := s2_compare(IS_WRITE)
      s3_memoryAccess(HIT_STATUS) := s2_compare(HIT_STATUS)
      s3_memoryAccess(LINE_OFFSET) := s2_compare(LINE_OFFSET)
      // CPU_RSP_DATA and CPU_RSP_FAULT are updated here or passed from S2
    }

    // --- S4: Response to CPU ---
    val s4_response = appendStage("S4_Response")
    val onS4 = new Area {
      import s4_response._

      io.cpu.rsp.valid := isValid // Send response if this stage is valid and not stalled
      io.cpu.rsp.payload.error := s4_response(CPU_RSP_FAULT)

      when(s4_response(IS_WRITE)) {
        io.cpu.rsp.payload.readData.assignDontCare() // No data for writes
      } otherwise { // Reads
        // If it was a Read Hit in S2, data is in s4_response(CPU_RSP_DATA)
        // If it was a Read Miss, S2 was stalled, S3 refilled, S2 re-ran and hit,
        // so s4_response(CPU_RSP_DATA) should now contain the correct data.
        io.cpu.rsp.payload.readData := s4_response(CPU_RSP_DATA)
      }
      haltIt(!io.cpu.rsp.ready && isValid)
    }

    // Build the pipeline
    build()
  }
}
