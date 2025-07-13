package parallax

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import parallax.components.memory._
import parallax.common._
import parallax.utilities._
import parallax.components.lsu._
import parallax.components.dcache2._
import parallax.bus.SplitGmbToAxi4Bridge
import spinal.lib.bus.misc.SizeMapping
import parallax.components.rename._
import parallax.components.rob._
import parallax.components.rob.FlushReason
import parallax.execute._
import parallax.fetch._
import parallax.issue._
import parallax.components.bpu._
import parallax.bpu.BpuService
import parallax.components.ifu._
import parallax.components.memory._
import parallax.components.dcache2._
import scala.collection.mutable.ArrayBuffer

// CoreNSCSCC IO Bundle - matches thinpad_top.v interface exactly
case class CoreNSCSCCIo(traceCommit: Boolean = false) extends Bundle {
  val commitStats = traceCommit generate out(CommitStats())

  // ISRAM (BaseRAM) interface
  val isram_dout = in Bits(32 bits)
  val isram_addr = out UInt(20 bits)
  val isram_din = out Bits(32 bits)
  val isram_en = out Bool()
  val isram_re = out Bool()
  val isram_we = out Bool()
  val isram_wmask = out Bits(4 bits)
  
  // DSRAM (ExtRAM) interface  
  val dsram_dout = in Bits(32 bits)
  val dsram_addr = out UInt(20 bits)
  val dsram_din = out Bits(32 bits)
  val dsram_en = out Bool()
  val dsram_re = out Bool()
  val dsram_we = out Bool()
  val dsram_wmask = out Bits(4 bits)
  
  // UART AXI interface
  val uart_ar_ready = in Bool()
  val uart_r_bits_id = in Bits(8 bits)
  val uart_r_bits_resp = in Bits(2 bits)
  val uart_r_bits_data = in Bits(32 bits)
  val uart_r_bits_last = in Bool()
  val uart_r_valid = in Bool()
  val uart_aw_ready = in Bool()
  val uart_w_ready = in Bool()
  val uart_b_bits_id = in Bits(8 bits)
  val uart_b_bits_resp = in Bits(2 bits)
  val uart_b_valid = in Bool()
  
  val uart_ar_bits_id = out Bits(8 bits)
  val uart_ar_bits_addr = out Bits(32 bits)
  val uart_ar_bits_len = out Bits(8 bits)
  val uart_ar_bits_size = out Bits(3 bits)
  val uart_ar_bits_burst = out Bits(2 bits)
  val uart_ar_valid = out Bool()
  val uart_r_ready = out Bool()
  val uart_aw_bits_id = out Bits(8 bits)
  val uart_aw_bits_addr = out Bits(32 bits)
  val uart_aw_bits_len = out Bits(8 bits)
  val uart_aw_bits_size = out Bits(3 bits)
  val uart_aw_bits_burst = out Bits(2 bits)
  val uart_aw_valid = out Bool()
  val uart_w_bits_data = out Bits(32 bits)
  val uart_w_bits_strb = out Bits(4 bits)
  val uart_w_bits_last = out Bool()
  val uart_w_valid = out Bool()
  val uart_b_ready = out Bool()
}

// Memory System Plugin for CoreNSCSCC
class CoreMemSysPlugin(axiConfig: Axi4Config, mmioConfig: Option[GenericMemoryBusConfig]) extends Plugin with SgmbService {
  import scala.collection.mutable.ArrayBuffer

  private val readPorts = ArrayBuffer[SplitGmbReadChannel]()
  private val writePorts = ArrayBuffer[SplitGmbWriteChannel]()
  private val sgmbConfig = mmioConfig.getOrElse(GenericMemoryBusConfig(
    addressWidth = 32 bits,
    dataWidth = 32 bits
  ))

  override def newReadPort(): SplitGmbReadChannel = {
    this.framework.requireEarly()
    val port = SplitGmbReadChannel(sgmbConfig)
    readPorts += port
    port
  }

  override def newWritePort(): SplitGmbWriteChannel = {
    this.framework.requireEarly()
    val port = SplitGmbWriteChannel(sgmbConfig)
    writePorts += port
    port
  }

  val hw = create early new Area {

    val sramSize = BigInt(4 * 1024 * 1024) // 4MB per SRAM (20-bit addressing)
    val baseramCfg = SRAMConfig(
      addressWidth = 20, // 1M * 4 bytes addressing 
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = sramSize,
      readWaitCycles = 0,
      useWordAddressing = true,
      enableLog = false
    )
    val extsramCfg = SRAMConfig(
      addressWidth = 20, // 1M * 4 bytes addressing 
      dataWidth = 32,
      virtualBaseAddress = BigInt("80400000", 16),
      sizeBytes = sramSize,
      useWordAddressing = true,
      readWaitCycles = 0,
      enableLog = false
    )

    val numMasters = 6 // DCache + SGMB bridges
    val sramAxi4Cfg = axiConfig.copy(idWidth = axiConfig.idWidth + log2Up(numMasters))
    
    // BaseRAM和ExtRAM使用相同的控制器
    val baseramCtrl = new SRAMController(sramAxi4Cfg, baseramCfg)
    val extramCtrl = new SRAMController(sramAxi4Cfg, extsramCfg)
  }

  val logic = create late new Area {
    lock.await()
    
    // 连接DCache
    val dcacheMaster = getService[DataCachePlugin].getDCacheMaster
    
    // 创建SGMB桥接器
    val readBridges = readPorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    val writeBridges = writePorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    
    // 连接SGMB端口到桥接器
    for ((port, bridge) <- readPorts.zip(readBridges)) {
      bridge.io.gmbIn.read.cmd <> port.cmd
      bridge.io.gmbIn.read.rsp <> port.rsp
      bridge.io.gmbIn.write.cmd.setIdle()
      bridge.io.gmbIn.write.rsp.ready := True
    }
    for ((port, bridge) <- writePorts.zip(writeBridges)) {
      bridge.io.gmbIn.write.cmd <> port.cmd
      bridge.io.gmbIn.write.rsp <> port.rsp
      bridge.io.gmbIn.read.cmd.setIdle()
      bridge.io.gmbIn.read.rsp.ready := True
    }
    
    // 收集所有AXI master
    val sramMasters = writeBridges.map(_.io.axiOut) ++ readBridges.map(_.io.axiOut) ++ Seq(dcacheMaster)
    require(sramMasters.size <= hw.numMasters, "Too many masters for SRAM controller")
    
    // 创建Crossbar，实现新的内存映射
    val crossbar = Axi4CrossbarFactory()
    
    // BaseRAM: 0x80000000～0x800FFFFF (1MB)
    crossbar.addSlave(hw.baseramCtrl.io.axi, SizeMapping(BigInt("80000000", 16), hw.sramSize))
    // ExtRAM: 0x80100000～0x801FFFFF (1MB) 
    crossbar.addSlave(hw.extramCtrl.io.axi, SizeMapping(BigInt("80400000", 16), hw.sramSize))
    
    // 暴露SRAM控制器以便外部连接
    def getBaseRamIo = hw.baseramCtrl.io.ram
    def getExtRamIo = hw.extramCtrl.io.ram
    
    // 暴露方法以获取UART连接 - 将UART添加到主crossbar作为MMIO slave
    def connectUartAxi(uartAxi: Axi4): Unit = {
      // UART MMIO: 0xbfd00000～0xbfd003FF (1KB UART registers)
      crossbar.addSlave(uartAxi, SizeMapping(0xbfd00000L, BigInt("400", 16)))
      
      // 为所有masters添加到所有slaves的连接（包括SRAM和UART）
      for (master <- sramMasters) {
        crossbar.addConnection(master, Seq(hw.baseramCtrl.io.axi, hw.extramCtrl.io.axi, uartAxi))
      }
      
      // 现在构建crossbar
      crossbar.build()
    }
  }
}

class CoreNSCSCC(traceCommit: Boolean = false) extends Component {
  val io = CoreNSCSCCIo(traceCommit)
  
  // 基本配置
  val pCfg = PipelineConfig(
    aluEuCount = 1,
    lsuEuCount = 1,
    dispatchWidth = 1,
    bruEuCount = 1,
    renameWidth = 1,
    fetchWidth = 2,
    xlen = 32,
    physGprCount = 64,
    archGprCount = 32,
    robDepth = 8,
    commitWidth = 1,
    resetVector = BigInt("80000000", 16), // 新的启动地址
    transactionIdWidth = 1,
    forceMMIO = true
  )
  
  val dCfg = DataCachePluginConfig(
    pipelineConfig = pCfg,
    memDataWidth = 32,
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    lineSize = 16,
    transactionIdWidth = pCfg.transactionIdWidth,
  )
  
  val minimalDCacheParams = DataCachePluginConfig.toDataCacheParameters(dCfg)
  val ifuCfg = InstructionFetchUnitConfig(
    pCfg = pCfg,
    dcacheParameters = minimalDCacheParams,
    pcWidth = pCfg.pcWidth,
    instructionWidth = pCfg.dataWidth,
    fetchGroupDataWidth = (pCfg.dataWidth.value * pCfg.fetchWidth) bits,
    enableLog = false
  )
  
  def createAxi4Config(pCfg: PipelineConfig): Axi4Config = Axi4Config(
    addressWidth = pCfg.xlen,
    dataWidth = pCfg.xlen,
    idWidth = pCfg.transactionIdWidth,
    useLock = false,
    useCache = false,
    useProt = true,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )
  
  val axiConfig = createAxi4Config(pCfg)
  val fifoDepth = 8
  
  val UOP_HT = HardType(RenamedUop(pCfg))
  
  val robConfig = ROBConfig[RenamedUop](
    robDepth = pCfg.robDepth,
    pcWidth = pCfg.pcWidth,
    commitWidth = pCfg.commitWidth,
    allocateWidth = pCfg.renameWidth,
    numWritebackPorts = pCfg.totalEuCount,
    uopType = UOP_HT,
    defaultUop = () => RenamedUop(pCfg).setDefault(),
    exceptionCodeWidth = pCfg.exceptionCodeWidth
  )
  
  val renameMapConfig = RenameMapTableConfig(
    archRegCount = pCfg.archGprCount,
    physRegCount = pCfg.physGprCount,
    numReadPorts = pCfg.renameWidth * 3,
    numWritePorts = pCfg.renameWidth
  )
  
  val flConfig = SuperScalarFreeListConfig(
    numPhysRegs = pCfg.physGprCount,
    resetToFull = true,
    numInitialArchMappings = pCfg.archGprCount,
    numAllocatePorts = pCfg.renameWidth,
    numFreePorts = pCfg.commitWidth
  )
  
  val lsuConfig = LsuConfig(
    lqDepth = 4,
    sqDepth = 4,
    robPtrWidth = pCfg.robPtrWidth,
    pcWidth = pCfg.pcWidth,
    dataWidth = pCfg.dataWidth,
    physGprIdxWidth = pCfg.physGprIdxWidth,
    exceptionCodeWidth = pCfg.exceptionCodeWidth,
    commitWidth = pCfg.commitWidth,
    dcacheRefillCount = 2
  )

  val dParams = DataCachePluginConfig.toDataCacheParameters(dCfg)
  
  // MMIO配置用于LSU插件
  val mmioConfig = Some(GenericMemoryBusConfig(
    addressWidth = 32 bits,
    dataWidth = 32 bits,
    useId = true,
    idWidth = pCfg.transactionIdWidth bits
  ))
  
  // 创建UART MMIO范围
  val uartMmioRange = MmioRange(
    start = U(0xbfd00000L, 32 bits),
    end = U(0xbfd00000L + 0x400000L, 32 bits)
  )
  
  // 创建内存系统插件
  val memSysPlugin = new CoreMemSysPlugin(axiConfig, mmioConfig)

  // CoreNSCSCC设置插件 - 连接必要的控制信号
  class CoreNSCSCCSetupPlugin(pCfg: PipelineConfig) extends Plugin {
    val setup = create early new Area {
      val fetchService = getService[SimpleFetchPipelineService]
      val bpuService = getService[BpuService]
      
      // 连接重定向端口 - 在正常运行时保持空闲
      val redirectPort = fetchService.newRedirectPort(0)
      redirectPort.valid := False
      redirectPort.payload := 0
      
      // BPU更新端口 - 设置为空闲状态
      val bpuUpdatePort = bpuService.newBpuUpdatePort()
      bpuUpdatePort.valid := False
      bpuUpdatePort.payload.assignDontCare()
    }
    
    val logic = create late new Area {
      val commitService = getService[CommitService]
      val fetchService = getService[SimpleFetchPipelineService]
      val issuePpl = getService[IssuePipeline]
      
      // 驱动commit enable信号 - 对于独立的CPU核心，总是启用commit
      commitService.setCommitEnable(True)
      
      // 连接fetch输出到decode阶段输入
      val fetchOutput = fetchService.fetchOutput()
      val issueEntryStage = issuePpl.entryStage
      val signals = issuePpl.signals
      
      // 创建指令向量（fetch输出单条指令，需要转换为向量格式）
      val instructionVec = Vec(Bits(pCfg.dataWidth), pCfg.fetchWidth)
      for (i <- 0 until pCfg.fetchWidth) {
        if (i == 0) {
          instructionVec(i) := fetchOutput.payload.instruction
        } else {
          instructionVec(i) := 0  // 其他位置填充0
        }
      }
      
      // 连接fetch到decode的输入信号
      issueEntryStage.valid := fetchOutput.valid
      when(fetchOutput.valid) {
        issueEntryStage(signals.GROUP_PC_IN) := fetchOutput.payload.pc
        issueEntryStage(signals.RAW_INSTRUCTIONS_IN) := instructionVec
        issueEntryStage(signals.VALID_MASK) := B"01"  // 只有第一条指令有效
        issueEntryStage(signals.IS_FAULT_IN) := False  // 简化：假设无故障
        issueEntryStage(signals.FLUSH_PIPELINE) := False
        issueEntryStage(signals.FLUSH_TARGET_PC) := 0
      } otherwise {
        issueEntryStage(signals.GROUP_PC_IN) := 0
        issueEntryStage(signals.RAW_INSTRUCTIONS_IN).assignDontCare()
        issueEntryStage(signals.VALID_MASK) := B"00"  // 无有效指令
        issueEntryStage(signals.IS_FAULT_IN) := False
        issueEntryStage(signals.FLUSH_PIPELINE) := False
        issueEntryStage(signals.FLUSH_TARGET_PC) := 0
      }
      fetchOutput.ready := issueEntryStage.isReady
    }
  }

  val framework = new Framework(
    Seq(
      // Memory system
      memSysPlugin,
      new DataCachePlugin(dCfg),
      new IFUPlugin(ifuCfg),
      
      // BPU and fetch
      new BpuPipelinePlugin(pCfg),
      new SimpleFetchPipelinePlugin(pCfg, ifuCfg, fifoDepth),
      
      // Infrastructure plugins
      new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
      new BusyTablePlugin(pCfg),
      new ROBPlugin[RenamedUop](pCfg, UOP_HT, () => RenamedUop(pCfg).setDefault()),
      new WakeupPlugin(pCfg),
      new BypassPlugin[BypassMessage](payloadType = HardType(BypassMessage(pCfg))),
      
      // CheckpointManagerPlugin for proper branch prediction recovery
      new CheckpointManagerPlugin(pCfg, renameMapConfig, flConfig),
      new RenameMapTablePlugin(ratConfig = renameMapConfig),
      new SuperScalarFreeListPlugin(flConfig),
      
      // Core pipeline
      new IssuePipeline(pCfg),
      new parallax.issue.CommitPlugin(pCfg),
      new DecodePlugin(pCfg),
      new RenamePlugin(pCfg, renameMapConfig, flConfig),
      new RobAllocPlugin(pCfg),
      new IssueQueuePlugin(pCfg),
      
      // Execution units
      new AluIntEuPlugin("AluIntEU", pCfg),
      new BranchEuPlugin("BranchEU", pCfg),
      new LsuEuPlugin("LsuEU", pCfg, lsuConfig = lsuConfig, dParams, pCfg.forceMMIO),
      new AguPlugin(lsuConfig, supportPcRel = true, mmioRanges = Seq(uartMmioRange)),
      new StoreBufferPlugin(pCfg, lsuConfig, dParams, lsuConfig.sqDepth, mmioConfig),
      new LoadQueuePlugin(pCfg, lsuConfig, dParams, lsuConfig.lqDepth, mmioConfig),
      
      // Dispatch and linking
      new LinkerPlugin(pCfg),
      new DispatchPlugin(pCfg),
      
      // CoreNSCSCC setup
      new CoreNSCSCCSetupPlugin(pCfg)
    )
  )
  
  // 获取内存系统的SRAM接口
  val baseRamIo = memSysPlugin.logic.getBaseRamIo
  val extRamIo = memSysPlugin.logic.getExtRamIo
  
  // 连接BaseRAM (ISRAM)
  io.isram_addr := baseRamIo.addr
  io.isram_din := baseRamIo.data.write
  io.isram_en := !baseRamIo.ce_n
  io.isram_re := !baseRamIo.oe_n
  io.isram_we := !baseRamIo.we_n
  io.isram_wmask := ~baseRamIo.be_n // 转换为高有效
  baseRamIo.data.read := io.isram_dout
  
  // 连接ExtRAM (DSRAM)
  io.dsram_addr := extRamIo.addr
  io.dsram_din := extRamIo.data.write
  io.dsram_en := !extRamIo.ce_n
  io.dsram_re := !extRamIo.oe_n
  io.dsram_we := !extRamIo.we_n
  io.dsram_wmask := ~extRamIo.be_n // 转换为高有效
  extRamIo.data.read := io.dsram_dout
  
  // 创建UART AXI接口 - 使用与SRAM控制器相同的ID宽度配置
  val uartAxi = Axi4(axiConfig.copy(idWidth = axiConfig.idWidth + log2Up(6)))
  
  // 连接UART AXI (映射到MMIO范围)
  io.uart_ar_bits_id := uartAxi.ar.id.asBits.resized
  io.uart_ar_bits_addr := uartAxi.ar.addr.asBits
  io.uart_ar_bits_len := uartAxi.ar.len.asBits.resized
  io.uart_ar_bits_size := uartAxi.ar.size.asBits.resized
  io.uart_ar_bits_burst := uartAxi.ar.burst
  io.uart_ar_valid := uartAxi.ar.valid
  uartAxi.ar.ready := io.uart_ar_ready
  
  uartAxi.r.id := io.uart_r_bits_id.asUInt.resized
  uartAxi.r.resp := io.uart_r_bits_resp.asBits
  uartAxi.r.data := io.uart_r_bits_data
  uartAxi.r.last := io.uart_r_bits_last
  uartAxi.r.valid := io.uart_r_valid
  io.uart_r_ready := uartAxi.r.ready
  
  io.uart_aw_bits_id := uartAxi.aw.id.asBits.resized
  io.uart_aw_bits_addr := uartAxi.aw.addr.asBits
  io.uart_aw_bits_len := uartAxi.aw.len.asBits.resized
  io.uart_aw_bits_size := uartAxi.aw.size.asBits.resized
  io.uart_aw_bits_burst := uartAxi.aw.burst
  io.uart_aw_valid := uartAxi.aw.valid
  uartAxi.aw.ready := io.uart_aw_ready
  
  io.uart_w_bits_data := uartAxi.w.data
  io.uart_w_bits_strb := uartAxi.w.strb
  io.uart_w_bits_last := uartAxi.w.last
  io.uart_w_valid := uartAxi.w.valid
  uartAxi.w.ready := io.uart_w_ready
  
  uartAxi.b.id := io.uart_b_bits_id.asUInt.resized
  uartAxi.b.resp := io.uart_b_bits_resp.asBits
  uartAxi.b.valid := io.uart_b_valid
  io.uart_b_ready := uartAxi.b.ready
  
  // 连接UART AXI到LSU的MMIO路径
  memSysPlugin.logic.connectUartAxi(uartAxi)

  val commitService = framework.getService[CommitPlugin]
  io.commitStats := commitService.getCommitStats()

}

// Verilog生成器
object CoreNSCSCCGen extends App {
  val spinalConfig = SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(162 MHz),
    targetDirectory = "soc"
  )
  
  spinalConfig.generateVerilog(new CoreNSCSCC)
  println("CoreNSCSCC Verilog Generation DONE")
}
