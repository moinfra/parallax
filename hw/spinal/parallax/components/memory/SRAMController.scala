package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.io.TriState
import spinal.lib.bus.amba4.axi.Axi4Aw
import parallax.utilities.ParallaxLogger

// ExtSRAM 的配置参数
case class SRAMConfig(
    addressWidth: Int,
    dataWidth: Int,
    virtualBaseAddress: BigInt = 0x0,
    sizeBytes: BigInt,
    readWaitCycles: Int = 0,
    sramByteEnableIsActiveLow: Boolean = true,
    // --- NEW ---: 添加字地址模式配置
    useWordAddressing: Boolean = false,
    enableLog: Boolean = true
) {
  require(isPow2(dataWidth / 8), "dataWidth must be a power of 2 bytes")
  require(sizeBytes > 0 && sizeBytes % (dataWidth / 8) == 0, "sramSize must be a multiple of data bus width")

  val bytesPerWord: Int = dataWidth / 8
  val internalWordCount: BigInt = sizeBytes / bytesPerWord

  // --- MODIFIED ---: 根据寻址模式调整地址空间检查
  if (useWordAddressing) {
    require(internalWordCount <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) requires more word addresses than available with addressWidth=${addressWidth}")
  } else {
    require(sizeBytes <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) exceeds byte-addressable space with addressWidth=${addressWidth}")
  }

  // --- NEW ---: 计算地址转换所需的位移量
  // 如果是字地址，需要右移 log2(bytesPerWord) 位。例如 32bit dataWidth (4 bytes/word) -> 右移 2 位
  val addressShift: Int = if (useWordAddressing) log2Up(bytesPerWord) else 0

  val internalWordMaxAddr: BigInt = internalWordCount - 1
  val internalMaxByteAddr = sizeBytes - 1
  val internalWordAddrWidth = log2Up(internalWordCount)
  val byteAddrWidth = log2Up(sizeBytes)

}

// ExtSRAM 的外部引脚定义 (无变化)
case class SRAMIO(c: SRAMConfig) extends Bundle with IMasterSlave {
  val data = (TriState(Bits(c.dataWidth bits)))
  val addr = UInt(c.addressWidth bits)
  val be_n = Bits(c.dataWidth / 8 bits) // 保持 _n 后缀表示低有效
  val ce_n = Bool()
  val oe_n = Bool()
  val we_n = Bool()

  override def asMaster(): Unit = {
    master(data)
    out(addr, be_n, ce_n, oe_n, we_n)
  }
}

// SRAMController 组件
class SRAMController(axiConfig: Axi4Config, config: SRAMConfig) extends Component {
  require(
    axiConfig.dataWidth == config.dataWidth,
    s"AXI and SRAM data width must match axiConfig.dataWidth = ${axiConfig.dataWidth} ExtSRAMConfig.dataWidth = ${config.dataWidth}"
  )
  // --- NEW ---: 对字地址模式的额外要求
  if (config.useWordAddressing) {
    require(axiConfig.useSize, "AXI useSize must be enabled for word addressing mode to ensure proper burst increments.")
  }

  ParallaxLogger.debug(s"Creating SRAMController with axiConfig=${axiConfig}, config=${config}")
  val hasReadWaitCycles = config.readWaitCycles > 0
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val ram = master(SRAMIO(config))
  }

  // --- AXI4 Slave 接口就绪信号 ---
  io.axi.aw.ready := False
  io.axi.ar.ready := False
  io.axi.w.ready := False
  io.axi.b.valid := False
  io.axi.r.valid := False

  // --- AXI4 Slave 接口默认 payload ---
  io.axi.b.payload.id := 0
  io.axi.b.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.id := 0
  io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
  io.axi.r.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.last := False

  // --- SRAM 控制信号初始化 ---
  val sram_write_addr_reg = Reg(UInt(config.addressWidth bits)) init (0)
  val sram_write_data_reg = Reg(Bits(config.dataWidth bits)) init (0)
  val sram_be_n_inactive_value =
    if (config.sramByteEnableIsActiveLow)
      B((1 << (config.dataWidth / 8)) - 1, config.dataWidth / 8 bits)
    else B(0, config.dataWidth / 8 bits)
  val sram_write_be_n_reg = Reg(Bits(config.dataWidth / 8 bits)) init (sram_be_n_inactive_value)
  val sram_perform_write = Reg(Bool()) init (False)

  io.ram.ce_n := True
  io.ram.oe_n := True
  io.ram.we_n := True
  io.ram.be_n := sram_be_n_inactive_value
  io.ram.addr := 0

  io.ram.data.write := sram_write_data_reg
  io.ram.data.writeEnable := sram_perform_write

  // --- 状态机定义 ---
  val fsm = new StateMachine {
    val ar_cmd_reg = Reg(cloneOf(io.axi.ar.payload))
    val aw_cmd_reg = Reg(cloneOf(io.axi.aw.payload))
    val burst_count_remaining = Reg(UInt(axiConfig.lenWidth + 1 bits))
    val current_sram_addr = Reg(UInt(config.addressWidth bits))
    val read_data_buffer = Reg(Bits(config.dataWidth bits))
    val read_wait_counter = hasReadWaitCycles generate Reg(UInt(log2Up(config.readWaitCycles + 1) bits))
    val transaction_error_occurred = Reg(Bool()) init (False)

    val read_priority = Reg(Bool()) init (False)

    val next_sram_addr_prefetch = Reg(UInt(config.addressWidth bits))
    val addr_prefetch_valid = Reg(Bool()) init (False)


    val IDLE: State = new State with EntryPoint {
      onEntry {
        sram_perform_write := False
      }
      whenIsActive {
        if (config.enableLog) report(L"SRAMController: IDLE, read_priority=${read_priority}")
        io.ram.ce_n := True
        io.ram.oe_n := True
        io.ram.we_n := True
        io.ram.addr := 0
        transaction_error_occurred := False
        addr_prefetch_valid := False

        io.axi.aw.ready := !read_priority || !io.axi.ar.valid
        io.axi.ar.ready := read_priority || !io.axi.aw.valid

        when(io.axi.aw.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${(io.axi.aw).size}" else L""
          if (config.enableLog)
            report(
              L"SRAMController: AW Fire. Addr=0x${io.axi.aw.addr}, ID=${io.axi.aw.id}, Len=${io.axi.aw.len}, Burst=${io.axi.aw.burst}${sizeInfo}"
            )
          aw_cmd_reg := io.axi.aw.payload
          burst_count_remaining := (io.axi.aw.len + 1).resize(burst_count_remaining.getWidth)
          
          // --- MODIFIED ---: 地址转换和对齐检查
          val byte_offset_addr = io.axi.aw.addr - config.virtualBaseAddress
          val sram_addr_candidate = (byte_offset_addr >> config.addressShift).resized
          current_sram_addr := sram_addr_candidate
          
          read_priority := !read_priority

          val bytesPerBeat = if (axiConfig.useSize) U(1) << io.axi.aw.size else U(config.bytesPerWord)
          val burst_bytes_total = (io.axi.aw.len + 1) * bytesPerBeat
          val end_byte_offset_addr = byte_offset_addr + burst_bytes_total - bytesPerBeat
          val addr_aligned = (io.axi.aw.addr & (bytesPerBeat - 1).resize(io.axi.aw.addr.getWidth)) === 0
          // 在字地址模式下，强制要求起始地址是字对齐的
          val word_aligned_if_needed = if (config.useWordAddressing) {
              (io.axi.aw.addr & (config.bytesPerWord - 1)) === 0
          } else {
              True
          }

          when(io.axi.aw.burst =/= Axi4.burst.INCR) {
            if (config.enableLog) report(L"SRAMController: AW Error - Unsupported burst type: ${io.axi.aw.burst}")
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } elsewhen (!addr_aligned || !word_aligned_if_needed) {
            if (config.enableLog) report(L"SRAMController: AW Error - Address unaligned: 0x${io.axi.aw.addr} for size ${bytesPerBeat} or word boundary")
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } elsewhen (byte_offset_addr.asSInt < 0 || end_byte_offset_addr >= config.sizeBytes) {
            if (config.enableLog) report(L"SRAMController: AW Error - Address out of bounds. Byte Offset=0x${byte_offset_addr}, End Offset=0x${end_byte_offset_addr}, SRAM Size=0x${config.sizeBytes}")
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } otherwise {
            goto(WRITE_DATA)
          }
        }

        when(io.axi.ar.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${(io.axi.ar).size}" else L""
          if (config.enableLog)
            report(
              L"SRAMController: AR Fire. Addr=0x${io.axi.ar.addr}, ID=${io.axi.ar.id}, Len=${io.axi.ar.len}, Burst=${io.axi.ar.burst}${sizeInfo}"
            )
          ar_cmd_reg := io.axi.ar.payload
          burst_count_remaining := (io.axi.ar.len + 1).resize(burst_count_remaining.getWidth)

          // --- MODIFIED ---: 地址转换和对齐检查
          val byte_offset_addr = io.axi.ar.addr - config.virtualBaseAddress
          val sram_addr_candidate = (byte_offset_addr >> config.addressShift).resized
          current_sram_addr := sram_addr_candidate
          
          read_priority := !read_priority

          val bytesPerBeat = if (axiConfig.useSize) U(1) << io.axi.ar.size else U(config.bytesPerWord)
          val burst_bytes_total = (io.axi.ar.len + 1) * bytesPerBeat
          val end_byte_offset_addr = byte_offset_addr + burst_bytes_total - bytesPerBeat
          val addr_aligned = (io.axi.ar.addr & (bytesPerBeat - 1).resize(io.axi.ar.addr.getWidth)) === 0
          // 在字地址模式下，强制要求起始地址是字对齐的
          val word_aligned_if_needed = if (config.useWordAddressing) {
              (io.axi.ar.addr & (config.bytesPerWord - 1)) === 0
          } else {
              True
          }

          when(io.axi.ar.burst =/= Axi4.burst.INCR) {
            if (config.enableLog) report(L"SRAMController: AR Error - Unsupported burst type: ${io.axi.ar.burst}")
            transaction_error_occurred := True
            goto(READ_RESPONSE_ERROR)
          } elsewhen (!addr_aligned || !word_aligned_if_needed) {
            if (config.enableLog) report(L"SRAMController: AR Error - Address unaligned: 0x${io.axi.ar.addr} for size ${bytesPerBeat} or word boundary")
            transaction_error_occurred := True
            goto(READ_RESPONSE_ERROR)
          } elsewhen (byte_offset_addr.asSInt < 0 || end_byte_offset_addr >= config.sizeBytes) {
            if (config.enableLog) report(L"SRAMController: AR Error - Address out of bounds. Byte Offset=0x${byte_offset_addr}, End Offset=0x${end_byte_offset_addr}, SRAM Size=0x${config.sizeBytes}")
            transaction_error_occurred := True
            goto(READ_RESPONSE_ERROR)
          } otherwise {
            hasReadWaitCycles generate { read_wait_counter := 0 }
            goto(READ_SETUP)
          }
        }
      }
    }

    val WRITE_DATA: State = new State {
      whenIsActive {
        if (config.enableLog)
          report(
            L"SRAMController: WRITE_DATA. SRAM_Target_Addr=0x${current_sram_addr}, SRAM_Actual_Write_Addr=0x${sram_write_addr_reg}, BurstCountRem=${burst_count_remaining}, sram_perform_write=${sram_perform_write}"
          )

        io.axi.w.ready := !sram_perform_write
        io.ram.ce_n := False
        io.ram.oe_n := True
        io.ram.addr := sram_write_addr_reg
        io.ram.we_n := !sram_perform_write
        io.ram.be_n := sram_write_be_n_reg

        when(sram_perform_write) {
          sram_perform_write := False

          // --- MODIFIED ---: 地址自增逻辑
          // 在字地址模式下，每次传输SRAM地址+1。在字节地址模式下，地址增加相应的字节数。
          val bytesIncrement = if (axiConfig.useSize) U(1) << aw_cmd_reg.size else U(config.bytesPerWord)
          val sramAddrIncrement = if (config.useWordAddressing) (bytesIncrement / config.bytesPerWord).resized else bytesIncrement.resized
          current_sram_addr := current_sram_addr + sramAddrIncrement
          
          burst_count_remaining := burst_count_remaining - 1

          when(burst_count_remaining === 1) {
            goto(WRITE_RESPONSE)
          }
        } elsewhen (io.axi.w.fire) {
          if (config.enableLog)
            report(
              L"SRAMController: W Fire. Data=0x${io.axi.w.data}, Strb=0x${io.axi.w.strb}, Last=${io.axi.w.last}"
            )

          sram_write_addr_reg := current_sram_addr
          sram_write_data_reg := io.axi.w.data
          if (config.sramByteEnableIsActiveLow) {
            sram_write_be_n_reg := ~io.axi.w.strb
          } else {
            sram_write_be_n_reg := io.axi.w.strb
          }
          sram_perform_write := True
        }
      }
    }

    val WRITE_DATA_ERROR_CONSUME: State = new State {
      whenIsActive {
        if (config.enableLog) report(L"SRAMController: WRITE_DATA_ERROR_CONSUME. BurstCountRem=${burst_count_remaining}")
        io.axi.w.ready := True
        io.ram.ce_n := True
        io.ram.we_n := True
        io.ram.oe_n := True
        io.ram.addr := 0
        sram_perform_write := False

        when(io.axi.w.fire) {
          burst_count_remaining := burst_count_remaining - 1
          when(burst_count_remaining === 1) {
            goto(WRITE_RESPONSE)
          }
        }
      }
    }

    val WRITE_RESPONSE: State = new State {
      onEntry {
        sram_perform_write := False
      }
      whenIsActive {
        val resp_status = transaction_error_occurred ? Axi4.resp.SLVERR | Axi4.resp.OKAY
        if (config.enableLog)
          report(L"SRAMController: WRITE_RESPONSE. ID=${aw_cmd_reg.id}, Resp=${resp_status}")
        io.ram.ce_n := True
        io.ram.we_n := True
        io.ram.oe_n := True
        io.ram.addr := 0
        io.axi.b.valid := True
        io.axi.b.payload.id := aw_cmd_reg.id
        io.axi.b.payload.resp := resp_status
        when(io.axi.b.ready) {
          goto(IDLE)
        }
      }
    }

    val READ_SETUP: State = new State {
      whenIsActive {
        if (config.enableLog)
          report(
            L"SRAMController: READ_SETUP. SRAM Addr=0x${current_sram_addr}, BurstCountRem=${burst_count_remaining}"
          )
        addr_prefetch_valid := False
        io.ram.ce_n := False
        io.ram.oe_n := False
        io.ram.we_n := True
        io.ram.addr := current_sram_addr
        hasReadWaitCycles generate { read_wait_counter := 0 }
        goto(READ_WAIT)
      }
    }
    
    // --- MODIFIED ---: 地址自增逻辑，封装成一个函数以供复用
    private def getNextSramAddr(currentAddr: UInt, axiSize: UInt): UInt = {
        val bytesIncrement = if (axiConfig.useSize) U(1) << axiSize else U(config.bytesPerWord)
        val sramAddrIncrement = if (config.useWordAddressing) (bytesIncrement / config.bytesPerWord).resized else bytesIncrement.resized
        return currentAddr + sramAddrIncrement
    }

    val READ_WAIT: State = new State {
      whenIsActive {
        if (config.enableLog)
          report(
            L"SRAMController: READ_WAIT. SRAM Addr=0x${current_sram_addr}, WaitCounter=${hasReadWaitCycles generate read_wait_counter}, AddrPrefetchValid=${addr_prefetch_valid}"
          )
        io.ram.ce_n := False
        io.ram.oe_n := False
        io.ram.we_n := True
        io.ram.addr := current_sram_addr

        val prefetch_trigger_cycle =
          if (config.readWaitCycles == 0) U(0) else U(config.readWaitCycles - 1)
        val prefetch_waitCond = if (hasReadWaitCycles) read_wait_counter === prefetch_trigger_cycle else True
        when(
          prefetch_waitCond &&
            burst_count_remaining > 1 &&
            !addr_prefetch_valid
        ) {
          next_sram_addr_prefetch := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
          addr_prefetch_valid := True
          if (config.enableLog)
            report(
              L"SRAMController: Address prefetch at wait_cycle ${hasReadWaitCycles generate read_wait_counter} - Next sram_addr 0x${getNextSramAddr(current_sram_addr, ar_cmd_reg.size)}"
            )
        }
        val waitCond = if (hasReadWaitCycles) read_wait_counter === config.readWaitCycles else True
        when(waitCond) {
          read_data_buffer := io.ram.data.read
          if (config.readWaitCycles == 0) {
            when(burst_count_remaining > 1 && !addr_prefetch_valid) {
              next_sram_addr_prefetch := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
              addr_prefetch_valid := True
            }
          }
          goto(READ_RESPONSE)
        } otherwise {
          hasReadWaitCycles generate { read_wait_counter := read_wait_counter + 1 }
        }
      }
    }

    val READ_RESPONSE: State = new State {
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (config.enableLog)
          report(
            L"SRAMController: READ_RESPONSE. ID=${ar_cmd_reg.id}, Data=0x${read_data_buffer}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.OKAY}, Last=${is_last_beat}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := read_data_buffer
        io.axi.r.payload.resp := Axi4.resp.OKAY
        io.axi.r.payload.last := is_last_beat
        io.ram.addr := current_sram_addr

        when(io.axi.r.fire) {
          if (config.enableLog) report(L"SRAMController: R Fire. Last=${io.axi.r.last}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            goto(IDLE)
          } otherwise {
            when(addr_prefetch_valid) {
              current_sram_addr := next_sram_addr_prefetch
            } otherwise {
              current_sram_addr := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
            }
            goto(READ_SETUP)
          }
        } otherwise {
          report(L"io.axi.r.valid = ${io.axi.r.valid}, io.axi.r.ready = ${io.axi.r.ready}")
        }
      }
      onExit {
        io.ram.oe_n := True
      }
    }

    val READ_RESPONSE_ERROR: State = new State {
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (config.enableLog)
          report(
            L"SRAMController: READ_RESPONSE_ERROR. ID=${ar_cmd_reg.id}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.SLVERR}, Last=${is_last_beat}"
          )

        io.ram.ce_n := True
        io.ram.oe_n := True
        io.ram.we_n := True
        io.ram.addr := 0

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
        io.axi.r.payload.resp := Axi4.resp.SLVERR
        io.axi.r.payload.last := is_last_beat

        when(io.axi.r.fire) {
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            goto(IDLE)
          }
        }
      }
    }
  }
}

object SRAMControllerGen extends App {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
    useLock = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  // 示例1：原始的字节寻址模式 (1MB SRAM)
  val ramConfigByteAddr = SRAMConfig(
    addressWidth = 20, // 2^20 = 1M, 匹配字节数
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 1 << 20, // 1MB
    useWordAddressing = false, // 明确禁用字地址模式
    readWaitCycles = 0,
    sramByteEnableIsActiveLow = true,
    enableLog = true
  )
  
  println("--- Generating SRAMController with Byte Addressing ---")
  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
  ).generateVerilog(new SRAMController(axiConfig, ramConfigByteAddr).setDefinitionName("SRAMControllerByteAddr"))


  // --- NEW ---
  // 示例2：新的字寻址模式 (4MB SRAM)
  // 您的示例：4MB = 4 * 1024 * 1024 = 2^22 bytes
  // 数据宽度 32-bit (4 bytes/word)
  // 字的数量 = (2^22 bytes) / (4 bytes/word) = 2^20 words
  // 因此，SRAM 物理地址线只需要 20 位 (addressWidth = 20)
  val ramConfigWordAddr = SRAMConfig(
    addressWidth = 20, // 2^20 = 1M words, 匹配字数
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 4 * 1024 * 1024, // 4MB
    useWordAddressing = true,    // 启用字地址模式
    readWaitCycles = 1,
    sramByteEnableIsActiveLow = true,
    enableLog = true
  )
  
  println("\n--- Generating SRAMController with Word Addressing ---")
  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
  ).generateVerilog(new SRAMController(axiConfig, ramConfigWordAddr).setDefinitionName("SRAMControllerWordAddr"))

}
