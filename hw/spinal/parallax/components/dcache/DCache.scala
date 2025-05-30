package parallax.components.dcache

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4Ax}
import spinal.lib.pipeline._
import parallax.utilities.Formattable

case class DataCacheParameters(
    cacheSize: Int, // 总缓存大小 (字节)
    wayCount: Int, // 路数 (N-way associative)
    memDataWidth: Int, // 内存总线数据宽度 (位)
    cpuDataWidth: Int, // CPU数据路径宽度 (位)
    physicalWidth: Int, // 物理地址宽度 (位)
    lineSize: Int = 64, // 缓存行大小 (字节)
    refillBufferDepth: Int = 2, // 填充缓冲区深度 (简化，较小的值)
    writebackBufferDepth: Int = 2, // 回写缓冲区深度 (简化)
    tagsReadAsync: Boolean = true // 标签/状态SRAM是否异步读取 (影响S1时序)
) {
  // 派生参数
  val bytePerMemWord = memDataWidth / 8
  val bytePerCpuWord = cpuDataWidth / 8
  val waySize = cacheSize / wayCount
  val linePerWay = waySize / lineSize
  val memWordPerLine = lineSize / bytePerMemWord // 每行包含的内存字数
  val cpuWordPerLine = lineSize / bytePerCpuWord // 每行包含的CPU字数

  val tagWidth = physicalWidth - log2Up(waySize)
  val lineIndexWidth = log2Up(linePerWay)
  val lineOffsetWidth = log2Up(lineSize) // 行内字节偏移位宽
  val cpuWordOffsetWidth = log2Up(cpuWordPerLine) // 行内CPU字偏移位宽
  val cpuWordByteOffsetWidth = log2Up(bytePerCpuWord) // CPU字内字节偏移位宽 (用于对齐)

  // 地址范围
  val tagRange = physicalWidth - 1 downto (lineIndexWidth + lineOffsetWidth)
  val lineIndexRange = (lineIndexWidth + lineOffsetWidth - 1) downto lineOffsetWidth
  val lineOffsetRange = lineOffsetWidth - 1 downto 0

  def axi4Config = Axi4Config(
    addressWidth = physicalWidth,
    dataWidth = memDataWidth,
    idWidth = log2Up(refillBufferDepth max writebackBufferDepth max 1), // 确保至少1位
    useId = true,
    useRegion = false,
    useBurst = true,
    useLock = false,
    useCache = false,
    useSize = true, // AXI Size for burst length calculation by interconnect
    useQos = false,
    useLen = true, // AXI Len (burst length - 1)
    useLast = true,
    useResp = true,
    useProt = true, // Typically needed
    useStrb = true // For write data
  )
}

case class CacheTag(p: DataCacheParameters) extends Bundle with Formattable {
  val valid = Bool()
  val tag = UInt(p.tagWidth bits)

  def format: Seq[Any] = {
    Seq(
      L"CacheTag(valid=$valid, tag=$tag)"
    )
  }

  def setDefault(): this.type = {
    valid := False
    tag := 0
    this
  }
}

case class CacheLineStatus() extends Bundle {
  val dirty = Bool()

  def setDefault(): this.type = {
    dirty := False
    this
  }
}
case class DCacheCpuCmd(p: DataCacheParameters) extends Bundle {
  val write = Bool() // True for store, False for load
  val address = UInt(p.physicalWidth bits)
  val data = Bits(p.cpuDataWidth bits) // For store
  val mask = Bits(p.bytePerCpuWord bits) // For store (byte mask)
  val size = UInt(log2Up(p.bytePerCpuWord + 1) bits) // For load (bytes to load, e.g. 1,2,4,8)
  val io = Bool() // True for non-cacheable IO access
}

case class DCacheCpuRsp(p: DataCacheParameters) extends Bundle {
  val readData = Bits(p.cpuDataWidth bits) // For load
  val fault = Bool()
  val redo = Bool()
}

class DataCacheCpuIO(p: DataCacheParameters) extends Bundle {
  val cmd = slave(Stream(DCacheCpuCmd(p)))
  val rsp = master(Flow(DCacheCpuRsp(p))) // Flow because response is fixed latency on hit
}

class DataCacheIO(p: DataCacheParameters) extends Bundle {
  val cpu = new DataCacheCpuIO(p)
  val mem = master(Axi4(p.axi4Config))
  val invalidate = in Bool () // Global invalidate signal ( synchronous )
}

// Define Stageable signals for the Data Cache Pipeline
case class DataCachePipelineSignals(p: DataCacheParameters) extends AreaObject {
  // Signals originating from CPU command or derived in S0
  val CPU_CMD = Stageable(DCacheCpuCmd(p))
  val PHYSICAL_ADDRESS = Stageable(UInt(p.physicalWidth bits))
  val LINE_INDEX = Stageable(UInt(p.lineIndexWidth bits))
  val CPU_WORD_OFFSET = Stageable(UInt(p.cpuWordOffsetWidth bits)) // Word offset in line
  val TAG_FROM_ADDRESS = Stageable(UInt(p.tagWidth bits))

  // Signals from RAMs, available in S1 based on S0's request
  val WAYS_TAGS_FROM_RAM = Stageable(Vec.fill(p.wayCount)(CacheTag(p)))
  val WAYS_STATUS_FROM_RAM = Stageable(Vec.fill(p.wayCount)(CacheLineStatus()))
  val SPECULATIVE_DATA_FROM_RAM = Stageable(Vec.fill(p.wayCount)(Bits(p.cpuDataWidth bits)))

  // Hit detection results from S1
  val CACHE_HIT = Stageable(Bool())
  val HIT_WAY = Stageable(UInt(log2Up(p.wayCount) bits))

  // Control signals determined in S2
  val FAULT = Stageable(Bool())
  val REDO = Stageable(Bool())
  val MISS = Stageable(Bool()) // Cache miss, not IO, not already redo

  // Victim selection from S2 for miss handling
  val EVICT_WAY = Stageable(UInt(log2Up(p.wayCount) bits))
  val VICTIM_TAG_FROM_RAM = Stageable(CacheTag(p)) // Tag of the victim line
  val VICTIM_STATUS_FROM_RAM = Stageable(CacheLineStatus()) // Status of the victim line
  val VICTIM_IS_DIRTY = Stageable(Bool())

  // Output data for load
  val LOAD_DATA_OUTPUT = Stageable(Bits(p.cpuDataWidth bits))
}

/*
class DataCache(val p: DataCacheParameters) extends Component with LockedImpl {
    val io = new DataCacheIO(p)

    // Instantiate Components
    val tagRam = new TagRamComponent(p)
    val statusRam = new StatusRamComponent(p)
    val dataBanks = new DataRamBanksComponent(p)
    val replacer = new RandomReplacerComponent(p)

    // Create an instance of the pipeline signals
    val signals = DataCachePipelineSignals(p)

    // --- Pipeline Definition ---
    // Create early to define stages, connect them, and allow other plugins/logic
    // to interact with the pipeline structure if needed (though not used in this simple PIPT)
    val pipeline = create early new Pipeline {
        val s0_Request = newStage().setName("S0_Request_and_RAM_Issue")
        val s1_HitDetect = newStage().setName("S1_RAM_Receive_and_Hit_Detect")
        val s2_Control = newStage().setName("S2_Control_and_Response")

        connect(s0_Request, s1_HitDetect, s2_Control)(List(Connection.M2S()))
    }

    // --- Shared Resources Arbitration ---
    // Used by S2 (store hit dirty write), Refill, Writeback, Invalidate
    // NaxRiscv uses a Reservation class; here's a simplified arbiter concept.
    // For simplicity, we'll assume direct writes for now and note where arbitration is critical.
    // A proper design would use a dedicated arbiter component for Tag/Status/Data RAM write ports
    // if multiple sources can write in the same cycle.
    // The pipeline stages S0/S1 primarily read, S2 might write for store hits.
    // Refill/Writeback/Invalidate are background tasks.

    // --- Global Invalidate Logic ---
    val invalidateLogic = new Area {
        val counter = Reg(UInt(p.lineIndexWidth + log2Up(p.wayCount) bits)) init(0)
        val inProgress = RegInit(False)
        val done = counter === ( (1 << (p.lineIndexWidth + log2Up(p.wayCount))) -1 ) // Check all entries

        when(io.invalidate && !inProgress && !done) {
            inProgress := True
            counter := 0
        }
        when(inProgress && done){
            inProgress := False
        }

        val currentInvalidateIndex = counter(p.lineIndexWidth + log2Up(p.wayCount) -1 downto log2Up(p.wayCount))
        val currentInvalidateWay = counter(log2Up(p.wayCount)-1 downto 0)

        // Invalidate Tag RAM
        tagRam.io.writeCmd.valid := False
        tagRam.io.writeCmd.payload.index := currentInvalidateIndex
        tagRam.io.writeCmd.payload.way := currentInvalidateWay
        tagRam.io.writeCmd.payload.data.valid := False
        tagRam.io.writeCmd.payload.data.address.assignDontCare()

        // Invalidate Status RAM
        statusRam.io.writeCmd.valid := False
        statusRam.io.writeCmd.payload.index := currentInvalidateIndex
        statusRam.io.writeCmd.payload.way := currentInvalidateWay
        statusRam.io.writeCmd.payload.data.dirty := False

        when(inProgress && !done) {
            // Assuming writes take one cycle and RAMs are ready
            tagRam.io.writeCmd.valid := True
            statusRam.io.writeCmd.valid := True
            when(tagRam.io.writeCmd.ready && statusRam.io.writeCmd.ready){ // Ensure RAMs accept
                 counter := counter + 1
            }
        }
    }


    // --- Refill Area ---
    val refillCtrl = new Area {
        val slots = Vec.tabulate(p.refillBufferDepth) { i => new Area {
            val id = U(i, p.axi4Config.idWidth bits)
            val valid = RegInit(False)
            val address = Reg(UInt(p.physicalWidth bits))
            val wayToRefill = Reg(UInt(log2Up(p.wayCount) bits))
            val lineBuffer = Reg(Bits(p.lineSize * 8 bits))
            val currentMemWord = Reg(UInt(log2Up(p.memWordPerLine + 1) bits)) init(0)
            val axiCmdSent = RegInit(False)
            val free = !valid
            def isBusyWithLine(lineIdx: UInt) = valid && address(p.lineIndexRange) === lineIdx
        }}

        val canAllocate = slots.map(_.free).orR
        val allocSlotOH = OHMasking.first(slots.map(_.free))
        val allocSlotIdx = OHToUInt(allocSlotOH)

        val push = Stream(new Bundle { // Changed to Stream for clear handshake
            val address = UInt(p.physicalWidth bits)
            val wayToRefill = UInt(log2Up(p.wayCount) bits)
        })
        push.setIdle() // Default

        when(push.fire && canAllocate) {
            val slot = slots(allocSlotIdx)
            slot.valid := True
            slot.address := push.address
            slot.wayToRefill := push.wayToRefill
            slot.currentMemWord := 0
            slot.axiCmdSent := False
        }
        push.ready := canAllocate

        // AXI AR Channel - Simplified: one outstanding read per refill controller
        val readyToIssueAxiRead = slots.map(s => s.valid && !s.axiCmdSent)
        val axiReadSlotIdx = PriorityEncoder(readyToIssueAxiRead) // Could be simpler if only one outstanding
        val axiReadSlot = slots(axiReadSlotIdx)

        io.mem.ar.valid := readyToIssueAxiRead.orR
        io.mem.ar.payload.addr := axiReadSlot.address
        io.mem.ar.payload.id := axiReadSlot.id
        io.mem.ar.payload.len := p.memWordPerLine - 1
        io.mem.ar.payload.size := log2Up(p.bytePerMemWord)
        Axi4Ax.setBurstINCR(io.mem.ar.payload) // Helper for burst type
        io.mem.ar.payload.prot := B"010"

        when(io.mem.ar.fire) {
            axiReadSlot.axiCmdSent := True
        }

        // AXI R Channel
        io.mem.r.ready := True // Assume always ready to accept refill data
        when(io.mem.r.fire) {
            val respSlot = slots(io.mem.r.payload.id.resized) // Simpler indexing if IDs are 0..N-1

            respSlot.lineBuffer.subdivideIn(p.memDataWidth bits)(respSlot.currentMemWord) := io.mem.r.payload.data
            respSlot.currentMemWord := respSlot.currentMemWord + 1

            when(io.mem.r.payload.last) {
                dataBanks.io.lineWriteCmdRefill.valid := True
                dataBanks.io.lineWriteCmdRefill.payload.index := respSlot.address(p.lineIndexRange)
                dataBanks.io.lineWriteCmdRefill.payload.way := respSlot.wayToRefill
                dataBanks.io.lineWriteCmdRefill.payload.data := respSlot.lineBuffer

                when(dataBanks.io.lineWriteCmdRefill.fire){ // Ensure DataBanks accepted
                    tagRam.io.writeCmd.valid := True
                    tagRam.io.writeCmd.payload.index := respSlot.address(p.lineIndexRange)
                    tagRam.io.writeCmd.payload.way := respSlot.wayToRefill
                    tagRam.io.writeCmd.payload.data.valid := True
                    tagRam.io.writeCmd.payload.data.address := respSlot.address(p.tagRange)

                    statusRam.io.writeCmd.valid := True
                    statusRam.io.writeCmd.payload.index := respSlot.address(p.lineIndexRange)
                    statusRam.io.writeCmd.payload.way := respSlot.wayToRefill
                    statusRam.io.writeCmd.payload.data.dirty := False

                    // Ensure Tag/Status RAMs accept write before freeing slot
                    when(tagRam.io.writeCmd.ready && statusRam.io.writeCmd.ready){
                        respSlot.valid := False
                    }
                }
            }
        }
    }

    // --- Writeback Area ---
    val writebackCtrl = new Area {
        val slots = Vec.tabulate(p.writebackBufferDepth) { i => new Area {
            val id = U(i, p.axi4Config.idWidth bits)
            val valid = RegInit(False)
            val physicalAddress = Reg(UInt(p.physicalWidth bits))
            val wayToEvict = Reg(UInt(log2Up(p.wayCount) bits))
            val lineBuffer = Reg(Bits(p.lineSize * 8 bits))
            val currentMemWord = Reg(UInt(log2Up(p.memWordPerLine + 1) bits)) init(0)
            val dataReadFromBanks = RegInit(False)
            val axiAwSent = RegInit(False)
            val free = !valid
            def isBusyWithLine(lineIdx: UInt) = valid && physicalAddress(p.lineIndexRange) === lineIdx
        }}

        val canAllocate = slots.map(_.free).orR
        val allocSlotOH = OHMasking.first(slots.map(_.free))
        val allocSlotIdx = OHToUInt(allocSlotOH)

        val push = Stream(new Bundle {
            val physicalAddress = UInt(p.physicalWidth bits)
            val wayToEvict = UInt(log2Up(p.wayCount) bits)
        })
        push.setIdle()

        when(push.fire && canAllocate) {
            val slot = slots(allocSlotIdx)
            slot.valid := True
            slot.physicalAddress := push.physicalAddress
            slot.wayToEvict := push.wayToEvict
            slot.currentMemWord := 0
            slot.dataReadFromBanks := False
            slot.axiAwSent := False
        }
        push.ready := canAllocate

        // Read from DataBanks for WB
        val pendingBankReadSlots = slots.map(s => s.valid && !s.dataReadFromBanks && !s.axiAwSent)
        val bankReadSlotIdx = PriorityEncoder(pendingBankReadSlots)
        val bankReadSlot = slots(bankReadSlotIdx)

        dataBanks.io.lineReadCmdWb.valid := pendingBankReadSlots.orR
        dataBanks.io.lineReadCmdWb.payload.index := bankReadSlot.physicalAddress(p.lineIndexRange)
        dataBanks.io.lineReadCmdWb.payload.way := bankReadSlot.wayToEvict

        when(dataBanks.io.lineReadCmdWb.fire){ // Data will be in lineReadRspWb next cycle
            // No specific action here, response handling below
        }
        // Latch data from DataBanks response
        when(dataBanks.io.lineReadRspWb.fire){
             // This assumes the response corresponds to the slot that requested it.
             // In a multi-outstanding system, need to match ID or ensure only one outstanding.
             // For simplicity, assume the response is for the currently selected bankReadSlot if it was valid.
            val respondingSlotIdx = RegNextWhen(bankReadSlotIdx, dataBanks.io.lineReadCmdWb.fire) // Get ID of who requested
            val respondingSlot = slots(respondingSlotIdx)
            when(respondingSlot.valid && !respondingSlot.dataReadFromBanks){ // Ensure it's for this slot
                respondingSlot.lineBuffer := dataBanks.io.lineReadRspWb.payload
                respondingSlot.dataReadFromBanks := True
            }
        }


        // AXI AW Channel
        val readyToIssueAxiAw = slots.map(s => s.valid && s.dataReadFromBanks && !s.axiAwSent)
        val axiAwSlotIdx = PriorityEncoder(readyToIssueAxiAw)
        val axiAwSlot = slots(axiAwSlotIdx)

        io.mem.aw.valid := readyToIssueAxiAw.orR
        io.mem.aw.payload.addr := axiAwSlot.physicalAddress
        io.mem.aw.payload.id := axiAwSlot.id
        io.mem.aw.payload.len := p.memWordPerLine - 1
        io.mem.aw.payload.size := log2Up(p.bytePerMemWord)
        Axi4Ax.setBurstINCR(io.mem.aw.payload)
        io.mem.aw.payload.prot := B"010"

        when(io.mem.aw.fire) {
            axiAwSlot.axiAwSent := True
        }

        // AXI W Channel
        val readyToSendAxiW = slots.map(s => s.valid && s.axiAwSent && s.dataReadFromBanks && (s.currentMemWord < p.memWordPerLine))
        // This needs an arbiter if multiple slots could be ready, or ensure only one AW is inflight.
        // Simplified: use axiAwSlot if it's in the right state
        val axiWSlot = axiAwSlot // Assume W follows AW for the same slot for simplicity

        io.mem.w.valid := axiWSlot.valid && axiWSlot.axiAwSent && axiWSlot.dataReadFromBanks && (axiWSlot.currentMemWord < p.memWordPerLine)
        io.mem.w.payload.data := axiWSlot.lineBuffer.subdivideIn(p.memDataWidth bits)(axiWSlot.currentMemWord)
        io.mem.w.payload.strb.setAll()
        io.mem.w.payload.last := axiWSlot.currentMemWord === p.memWordPerLine - 1
        // io.mem.w.payload.id := axiWSlot.id // W channel doesn't have ID in AXI4

        when(io.mem.w.fire) {
            axiWSlot.currentMemWord := axiWSlot.currentMemWord + 1
        }

        // AXI B Channel
        io.mem.b.ready := True
        when(io.mem.b.fire) {
            val respSlot = slots(io.mem.b.payload.id.resized)
            respSlot.valid := False // Writeback complete
        }
    }


    // --- S0: Request Intake & RAM Read Command Issue ---
    val s0_logic = new Area {
        val stage = pipeline.s0_Request
        import stage._ // Allows direct use of stage.isValid, stage.isStalled etc.

        // CPU Command Intake & Initial Decode
        val cpuCmd = io.cpu.cmd.m2sPipe(flushPipe = false) // Buffer incoming command

        arbitration.haltItself := False // Default, allow pipeline to run

        when(cpuCmd.valid) { // Only proceed if there's a command
            // Output signals for S0 -> S1 registers
            stage(signals.CPU_CMD) := cpuCmd.payload
            stage(signals.PHYSICAL_ADDRESS) := cpuCmd.payload.address
            val lineIdx = cpuCmd.payload.address(p.lineIndexRange)
            stage(signals.LINE_INDEX) := lineIdx
            stage(signals.TAG_FROM_ADDRESS) := cpuCmd.payload.address(p.tagRange)
            stage(signals.CPU_WORD_OFFSET) := (cpuCmd.payload.address(p.lineOffsetRange) >> p.cpuWordByteOffsetWidth).resize(p.cpuWordOffsetWidth)

            // Issue reads to RAMs
            when(!cpuCmd.payload.io) { // Only for cacheable accesses
                tagRam.io.readCmd.valid := arbitration.isFiring // isFiring = isValid && !isStalledByOthers && !haltItself
                tagRam.io.readCmd.payload := lineIdx

                statusRam.io.readCmd.valid := arbitration.isFiring
                statusRam.io.readCmd.payload := lineIdx

                dataBanks.io.readCmdS0.valid := arbitration.isFiring
                dataBanks.io.readCmdS0.payload.index := lineIdx
                dataBanks.io.readCmdS0.payload.wordOffset := stage(signals.CPU_WORD_OFFSET)

                // Stall S0 if RAMs are not ready to accept new commands
                when(arbitration.isFiring && (!tagRam.io.readCmd.ready || !statusRam.io.readCmd.ready || !dataBanks.io.readCmdS0.ready)) {
                    arbitration.haltItself := True
                }
            }
        }
        cpuCmd.ready := arbitration.isFiring // Consume CPU cmd if S0 fires

        // Default for non-IO reads if S0 is stalled or no cmd
        when(!arbitration.isFiring || !cpuCmd.valid || cpuCmd.payload.io){
            tagRam.io.readCmd.valid := False
            statusRam.io.readCmd.valid := False
            dataBanks.io.readCmdS0.valid := False
        }
    }

    // --- S1: RAM Data Receive & Hit Detection ---
    val s1_logic = new Area {
        val stage = pipeline.s1_HitDetect
        import stage._

        // Data from RAMs is available at the start of this stage
        // (registered output from RAM components due to S0 readCmd.fire)
        stage(signals.WAYS_TAGS_FROM_RAM) := tagRam.io.readRsp.payload
        stage(signals.WAYS_STATUS_FROM_RAM) := statusRam.io.readRsp.payload
        stage(signals.SPECULATIVE_DATA_FROM_RAM) := dataBanks.io.readRspS1.payload

        // Consume RAM responses if S1 fires and it was a cacheable access in S0
        val s0_was_cacheable_and_fired = Mux(up.isValid && up.isFiring, !up(signals.CPU_CMD).io, False)

        tagRam.io.readRsp.ready := arbitration.isFiring && s0_was_cacheable_and_fired
        statusRam.io.readRsp.ready := arbitration.isFiring && s0_was_cacheable_and_fired
        dataBanks.io.readRspS1.ready := arbitration.isFiring && s0_was_cacheable_and_fired


        // Hit Detection (combinational logic based on S1 inputs)
        val hits = Bits(p.wayCount bits)
        val hitWay = UInt(log2Up(p.wayCount) bits)
        hitWay.assignDontCare()
        hits := 0

        val currentTag = stage(signals.TAG_FROM_ADDRESS) // Tag from address (passed from S0)
        val waysTagsFromRam = stage(signals.WAYS_TAGS_FROM_RAM)

        when(!stage(signals.CPU_CMD).io) { // Only for cacheable accesses
            for (i <- 0 until p.wayCount) {
                when(waysTagsFromRam(i).valid && waysTagsFromRam(i).address === currentTag) {
                    hits(i) := True
                    hitWay := U(i).resized
                }
            }
        }
        stage(signals.CACHE_HIT) := hits.orR
        stage(signals.HIT_WAY) := hitWay

        // Hazard Detection
        val lineIdx = stage(signals.LINE_INDEX)
        val lineBusyByRefill = refillCtrl.slots.map(_.isBusyWithLine(lineIdx)).orR
        val lineBusyByWriteback = writebackCtrl.slots.map(_.isBusyWithLine(lineIdx)).orR
        // This hazard check result will be used in S2
    }

    // --- S2: Control Logic & Response / Miss Handling ---
    val s2_logic = new Area {
        val stage = pipeline.s2_Control
        import stage._

        val cpuCmd = stage(signals.CPU_CMD)
        val cacheHit = stage(signals.CACHE_HIT)
        val hitWay = stage(signals.HIT_WAY)
        val waysTags = stage(signals.WAYS_TAGS_FROM_RAM)
        val waysStatus = stage(signals.WAYS_STATUS_FROM_RAM)
        val speculativeData = stage(signals.SPECULATIVE_DATA_FROM_RAM)
        val physicalAddress = stage(signals.PHYSICAL_ADDRESS)
        val lineIndex = stage(signals.LINE_INDEX)

        // Check S1's pre-calculated hazard based on S0's address. Re-evaluate if needed.
        // For simplicity, assuming S1's hazard check on lineIndex is sufficient for S2.
        // val s1_pre_redo_hazard = stage(signals.S1_PRE_REDO_HAZARD) // If it was calculated in S1
        // Or re-calculate here based on S1's line_index
        val lineBusyByRefill = refillCtrl.slots.map(_.isBusyWithLine(lineIndex)).orR
        val lineBusyByWriteback = writebackCtrl.slots.map(_.isBusyWithLine(lineIndex)).orR
        var redo_internal = (lineBusyByRefill || lineBusyByWriteback) && !cpuCmd.io


        val fault_internal = cpuCmd.io
        val miss_internal = !cacheHit && !fault_internal && !redo_internal

        // Output defaults
        io.cpu.rsp.valid := False
        io.cpu.rsp.payload.assignDontCare()
        dataBanks.io.writeCmdS2.valid := False
        statusRam.io.writeCmd.payload.assignDontCare()
        statusRam.io.writeCmd.valid := False
        replacer.io.update := False

        // --- Final decision logic in S2 ---
        when(arbitration.isFiring) { // Only process if S2 is not stalled
            when(fault_internal) {
                io.cpu.rsp.valid := True
                io.cpu.rsp.payload.fault := True
                io.cpu.rsp.payload.redo := False
            } elsewhen (redo_internal) {
                io.cpu.rsp.valid := True
                io.cpu.rsp.payload.fault := False
                io.cpu.rsp.payload.redo := True
            } elsewhen (cacheHit) {
                replacer.io.update := True // Update replacer on hit
                if (cpuCmd.write) { // Store Hit
                    // Attempt to write to DataBanks and StatusRAM
                    dataBanks.io.writeCmdS2.valid := True
                    dataBanks.io.writeCmdS2.payload.index := lineIndex
                    dataBanks.io.writeCmdS2.payload.way := hitWay
                    dataBanks.io.writeCmdS2.payload.wordOffset := stage(signals.CPU_WORD_OFFSET)
                    dataBanks.io.writeCmdS2.payload.data := cpuCmd.data
                    dataBanks.io.writeCmdS2.payload.mask := cpuCmd.mask

                    statusRam.io.writeCmd.valid := True
                    statusRam.io.writeCmd.payload.index := lineIndex
                    statusRam.io.writeCmd.payload.way := hitWay
                    statusRam.io.writeCmd.payload.data.dirty := True

                    // Stall S2 if write ports are not ready
                    when(!dataBanks.io.writeCmdS2.ready || !statusRam.io.writeCmd.ready) {
                        arbitration.haltItself := True
                        // Propagate redo if stalled
                        io.cpu.rsp.valid := True
                        io.cpu.rsp.payload.redo := True
                    } otherwise {
                        io.cpu.rsp.valid := True // Store ack
                        io.cpu.rsp.payload.fault := False
                        io.cpu.rsp.payload.redo := False
                    }
                } else { // Load Hit
                    io.cpu.rsp.valid := True
                    io.cpu.rsp.payload.readData := speculativeData(hitWay)
                    io.cpu.rsp.payload.fault := False
                    io.cpu.rsp.payload.redo := False
                }
            } elsewhen (miss_internal) { // Cache Miss
                val evictWay = replacer.io.evictWay
                val victimTag = waysTags(evictWay)       // From S1 RAM read
                val victimStatus = waysStatus(evictWay) // From S1 RAM read
                val victimIsDirty = victimStatus.dirty && victimTag.valid

                when((victimIsDirty && !writebackCtrl.canAllocate) || !refillCtrl.canAllocate) {
                    redo_internal := True // Re-evaluate redo if cannot start miss handling
                    io.cpu.rsp.valid := True
                    io.cpu.rsp.payload.redo := True
                } otherwise {
                    if (victimIsDirty) {
                        writebackCtrl.push.valid := True
                        val victimPhysicalAddress = victimTag.address @@ lineIndex @@ U(0, p.lineOffsetWidth bits)
                        writebackCtrl.push.payload.physicalAddress := victimPhysicalAddress
                        writebackCtrl.push.payload.wayToEvict := evictWay
                    }
                    refillCtrl.push.valid := True
                    refillCtrl.push.payload.address := physicalAddress
                    refillCtrl.push.payload.wayToRefill := evictWay

                    io.cpu.rsp.valid := True // Miss started, CPU must redo
                    io.cpu.rsp.payload.redo := True
                }
            }

            // Global Invalidation check (highest priority stall if in progress)
            when(invalidateLogic.inProgress){
                arbitration.haltItself := True // Stall pipeline
                // Send redo for any active command in S2 during invalidation
                io.cpu.rsp.valid := True
                io.cpu.rsp.payload.redo := True
            }
        }

        // Pass final control signals to Stageable, mostly for debug/observation
        stage(signals.FAULT) := fault_internal
        stage(signals.REDO) := redo_internal
        stage(signals.MISS) := miss_internal
        stage(signals.EVICT_WAY) := replacer.io.evictWay // Value used if miss
        stage(signals.LOAD_DATA_OUTPUT) := Mux(cacheHit && !cpuCmd.write, speculativeData(hitWay), B(0))

    }

}
 */
