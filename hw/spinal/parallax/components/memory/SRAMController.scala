package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.io.TriState
import spinal.lib.bus.amba4.axi.Axi4Aw
import parallax.utilities.ParallaxLogger
import parallax.utilities.Formattable
import parallax.utilities.ParallaxSim.fatal
import parallax.utilities.MuxGen

// ExtSRAM 的配置参数 (无变化)
case class SRAMConfig(
    addressWidth: Int,
    dataWidth: Int,
    virtualBaseAddress: BigInt = 0x0,
    sizeBytes: BigInt,
    readWaitCycles: Int = 0,
    writeWaitCycles: Int = 0,
    writeDeassertCycles: Int = 1,
    sramByteEnableIsActiveLow: Boolean = true,
    useWordAddressing: Boolean = false,
    enableLog: Boolean = false,
    enableValidation: Boolean = false
) {
  require(isPow2(dataWidth / 8), "dataWidth must be a power of 2 bytes")
  require(sizeBytes > 0 && sizeBytes % (dataWidth / 8) == 0, "sramSize must be a multiple of data bus width")

  val bytesPerWord: Int = dataWidth / 8
  val internalWordCount: BigInt = sizeBytes / bytesPerWord

  if (useWordAddressing) {
    require(internalWordCount <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) requires more word addresses than available with addressWidth=${addressWidth}")
  } else {
    require(sizeBytes <= (BigInt(1) << addressWidth), s"sizeBytes (${sizeBytes}) exceeds byte-addressable space with addressWidth=${addressWidth}")
  }

  val addressShift: Int = if (useWordAddressing) log2Up(bytesPerWord) else 0
  val internalWordMaxAddr: BigInt = internalWordCount - 1
  val internalMaxByteAddr = sizeBytes - 1
  val internalWordAddrWidth = log2Up(internalWordCount)
  val byteAddrWidth = log2Up(sizeBytes)
}

// ExtSRAM 的外部引脚定义 (无变化)
case class SRAMIO(c: SRAMConfig) extends Bundle with IMasterSlave with Formattable {
  val data = (TriState(Bits(c.dataWidth bits)))
  val addr = UInt(c.addressWidth bits)
  val be_n = Bits(c.dataWidth / 8 bits)
  val ce_n = Bool()
  val oe_n = Bool()
  val we_n = Bool()

  override def asMaster(): Unit = {
    master(data)
    out(addr, be_n, ce_n, oe_n, we_n)
  }

  private def formatTriState(tri: TriState[Bits]): Seq[Any] = {
    Seq(
      L"TriState(",
      L"writeEnable=${tri.writeEnable}, ",
      L"read=${tri.read}, ",
      L"write=${tri.write})"
    )
  }

  override def format: Seq[Any] = {
    Seq(
      L"SRAMIO(",
      L"ce_n=${ce_n}, ",
      L"we_n=${we_n}, ",
      L"oe_n=${oe_n}, ",
      L"addr=${addr}, ",
      L"data=${formatTriState(data)}, ",
      L"be_n=${be_n})"
    )
  }
}

object SRAMController {
  private var _nextInstanceId = 0
  def nextInstanceId: Int = {
    val id = _nextInstanceId
    _nextInstanceId += 1
    id
  }
}

// SRAMController 组件
class SRAMController(val axiConfig: Axi4Config, val config: SRAMConfig) extends Component {

  private case class ValidationInput(
      addr: UInt,
      len: UInt,
      size: UInt,
      burst: Bits
  ) extends Bundle {}

  private def performValidation(cmd: ValidationInput): (Bool, UInt) = {
    val virtualBaseAddressBitLength = if (config.virtualBaseAddress == 0) 1 else log2Up(config.virtualBaseAddress) + 1
    val maxAddressBitLength = Math.max(axiConfig.addressWidth, virtualBaseAddressBitLength)
    val extendedWidth = maxAddressBitLength + 1
    val addr_extended = cmd.addr.resize(extendedWidth)
    val vbase_extended = U(config.virtualBaseAddress, extendedWidth bits)
    val byte_offset_addr = addr_extended - vbase_extended
    val bytesPerBeat = if (axiConfig.useSize) U(1) << cmd.size else U(config.bytesPerWord)
    val shiftAmount = if (axiConfig.useSize) cmd.size else U(log2Up(config.bytesPerWord))
    val actualShiftAmount = shiftAmount.resize(log2Up(extendedWidth) + 1)
    val burst_len_extended = (cmd.len + 1).resize(extendedWidth)
    val actual_burst_beats = burst_len_extended - 1
    val burst_bytes_offset_for_last_beat = (actual_burst_beats << actualShiftAmount).resize(extendedWidth)
    val end_byte_offset_addr = byte_offset_addr + burst_bytes_offset_for_last_beat
    val addr_aligned = (cmd.addr & (bytesPerBeat - 1).resize(cmd.addr.getWidth)) === 0
    val word_aligned_if_needed = if (config.useWordAddressing) {
        (cmd.addr & U(config.bytesPerWord - 1, cmd.addr.getWidth bits)) === 0
    } else { True }
    val size_compatible_if_needed = if (config.useWordAddressing && axiConfig.useSize) {
        cmd.size === U(log2Up(config.bytesPerWord))
    } else { True }
    val is_out_of_bounds = end_byte_offset_addr >= U(config.sizeBytes, extendedWidth bits)
    val sram_addr_candidate = (byte_offset_addr(config.addressWidth-1 downto 0) >> config.addressShift).resized
    val is_error = (cmd.burst =/= Axi4.burst.INCR) || !addr_aligned || !word_aligned_if_needed || !size_compatible_if_needed || is_out_of_bounds
    when(is_error) {
      fatal(L"INVALID AXI COMMAND: addr=${cmd.addr}, len=${cmd.len}, size=${cmd.size}, burst=${cmd.burst} Conditions: burst!=INCR?=${cmd.burst =/= Axi4.burst.INCR}, !addr_aligned?=${!addr_aligned}, !word_aligned_if_needed?=${!word_aligned_if_needed}, !size_compatible_if_needed?=${!size_compatible_if_needed}, is_out_of_bounds?=${is_out_of_bounds}")
    }
    (is_error, sram_addr_candidate)
  }

  private def calculateSramAddress(axiAddr: UInt): UInt = {
      val vBaseBitLength = if (config.virtualBaseAddress == 0) 1 else log2Up(config.virtualBaseAddress) + 1
      val extendedWidth = Math.max(axiConfig.addressWidth, vBaseBitLength) + 1
      val addr_extended = axiAddr.resize(extendedWidth)
      val vbase_extended = U(config.virtualBaseAddress, extendedWidth bits)
      val byte_offset_addr = addr_extended - vbase_extended
      val word_offset_addr = byte_offset_addr >> config.addressShift
      val sram_addr = word_offset_addr(config.addressWidth - 1 downto 0).resized
      return sram_addr
  }
  
  val instanceId = SRAMController.nextInstanceId
  require(
    axiConfig.dataWidth == config.dataWidth,
    s"AXI and SRAM data width must match axiConfig.dataWidth = ${axiConfig.dataWidth} ExtSRAMConfig.dataWidth = ${config.dataWidth}"
  )
  val enableLog = config.enableLog || false
  if (config.useWordAddressing) {
    require(axiConfig.useSize, "AXI useSize must be enabled for word addressing mode to ensure proper burst increments.")
  }

  ParallaxLogger.debug(s"Creating SRAMController with axiConfig=${axiConfig}, config=${config}")
  val hasReadWaitCycles = config.readWaitCycles > 0
  val hasWriteWaitCycles = config.writeWaitCycles > 0
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val ram = master(SRAMIO(config))
  }

  io.axi.aw.ready := False
  io.axi.ar.ready := False
  io.axi.w.ready := False
  io.axi.b.valid := False
  io.axi.r.valid := False
  io.axi.b.payload.id := 0
  io.axi.b.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.id := 0
  io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
  io.axi.r.payload.resp := Axi4.resp.OKAY
  io.axi.r.payload.last := False

  val sram_be_n_inactive_value = if (config.sramByteEnableIsActiveLow) B((1 << (config.dataWidth / 8)) - 1, config.dataWidth / 8 bits) else B(0, config.dataWidth / 8 bits)
  val sram_addr_out_reg        = Reg(UInt(config.addressWidth bits)) addAttribute("mark_debug","TRUE") init(0)
  val sram_data_out_reg        = Reg(Bits(config.dataWidth bits)) init(0) addAttribute("mark_debug","TRUE")
  val sram_be_n_out_reg        = Reg(Bits(config.dataWidth / 8 bits)) addAttribute("mark_debug","TRUE") init(sram_be_n_inactive_value)
  val sram_ce_n_out_reg        = Reg(Bool()) addAttribute("mark_debug","TRUE") init(True)
  val sram_oe_n_out_reg        = Reg(Bool()) addAttribute("mark_debug","TRUE") init(True)
  val sram_we_n_out_reg        = Reg(Bool()) addAttribute("mark_debug","TRUE") init(True)
  val sram_data_writeEnable_out_reg = Reg(Bool()) addAttribute("mark_debug","TRUE") init(False)

  io.ram.addr := sram_addr_out_reg
  io.ram.ce_n := sram_ce_n_out_reg
  io.ram.oe_n := sram_oe_n_out_reg
  io.ram.we_n := sram_we_n_out_reg
  io.ram.be_n := sram_be_n_out_reg
  io.ram.data.write := sram_data_out_reg
  io.ram.data.writeEnable := sram_data_writeEnable_out_reg

  val fsm = new StateMachine {
    val ar_cmd_reg             = Reg(cloneOf(io.axi.ar.payload)) addAttribute("mark_debug","TRUE")
    val aw_cmd_reg             = Reg(cloneOf(io.axi.aw.payload)) addAttribute("mark_debug","TRUE")
    val burst_count_remaining  = Reg(UInt(axiConfig.lenWidth + 1 bits))  init(U(0, axiConfig.lenWidth + 1 bits)) addAttribute("mark_debug","TRUE")
    val current_sram_addr      = Reg(UInt(config.addressWidth bits))     init(U(0, config.addressWidth bits)) addAttribute("mark_debug","TRUE")
    val read_data_buffer       = Reg(Bits(config.dataWidth bits)) addAttribute("mark_debug","TRUE")
    val read_wait_counter      = hasReadWaitCycles generate Reg(UInt(log2Up(config.readWaitCycles + 1) bits))

    // --- OPTIMIZED ---: Counter for the new micro-sequenced write state
    val totalWriteCycles = 1 + config.writeWaitCycles + config.writeDeassertCycles
    val write_cycle_counter = Reg(UInt(log2Up(totalWriteCycles) bits)) addAttribute("mark_debug","TRUE")

    val transaction_error_occurred = Reg(Bool()) init (False) addAttribute("MARK_DEBUG","TRUE")
    val read_priority = Reg(Bool()) init (False)
    val next_sram_addr_prefetch = Reg(UInt(config.addressWidth bits)) addAttribute("mark_debug","TRUE")
    val addr_prefetch_valid = Reg(Bool()) init (False) addAttribute("mark_debug","TRUE")
    val is_write_transaction = config.enableValidation generate Reg(Bool()) addAttribute("mark_debug","TRUE")

    def reportRegAssignment(regName: String, oldVal: Data, newVal: Data, state: String, assignType: String): Unit = {
      if (enableLog) {
        report(L"Assignment: State=${state}, Type=${assignType}, Reg=${regName}, OldValue=${oldVal}, NewValue=${newVal}")
      }
    }

    val IDLE: State = new State with EntryPoint {
      onEntry {
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        transaction_error_occurred := False
        addr_prefetch_valid := False
        val awHasRequest = io.axi.aw.valid
        val arHasRequest = io.axi.ar.valid
        when(awHasRequest && arHasRequest) {
          io.axi.aw.ready := !read_priority
          io.axi.ar.ready := read_priority
        } otherwise {
          io.axi.aw.ready := awHasRequest
          io.axi.ar.ready := arHasRequest
        }
        io.axi.w.ready := False

        when(io.axi.aw.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${io.axi.aw.size}" else L""
          if (enableLog) {
            report(
              L"$instanceId AW Fire. Addr=0x${io.axi.aw.addr}, ID=${io.axi.aw.id}, Len=${io.axi.aw.len}, Burst=${io.axi.aw.burst}${sizeInfo}"
            )
          }
          aw_cmd_reg := io.axi.aw.payload
          burst_count_remaining := (io.axi.aw.len + 1).resize(burst_count_remaining.getWidth)
          read_priority := !read_priority
          if (config.enableValidation) {
            is_write_transaction := True
            goto(VALIDATE)
          } else {
            transaction_error_occurred := False
            current_sram_addr := calculateSramAddress(io.axi.aw.addr) 
            goto(WRITE_DATA_FETCH)
          }
        }

        when(io.axi.ar.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${io.axi.ar.size}" else L""
          if (enableLog) {
            report(
              L"$instanceId AR Fire. Addr=0x${io.axi.ar.addr}, ID=${io.axi.ar.id}, Len=${io.axi.ar.len}, Burst=${io.axi.ar.burst}${sizeInfo}"
            )
          }
          ar_cmd_reg := io.axi.ar.payload
          burst_count_remaining := (io.axi.ar.len + 1).resize(burst_count_remaining.getWidth)
          read_priority := !read_priority
          if (config.enableValidation) {
            is_write_transaction := False
            goto(VALIDATE)
          } else {
            transaction_error_occurred := False
            current_sram_addr := calculateSramAddress(io.axi.ar.addr)
            // --- OPTIMIZED ---: Directly jump to the fused setup-and-wait state
            goto(READ_SETUP_AND_WAIT)
          }
        }
      }
    }

    val VALIDATE: State = new State {
      if (config.enableValidation) {
        whenIsActive {
          val cmd_addr  = Mux(is_write_transaction, aw_cmd_reg.addr,  ar_cmd_reg.addr)
          val cmd_len   = Mux(is_write_transaction, aw_cmd_reg.len,   ar_cmd_reg.len)
          val cmd_size  = Mux(is_write_transaction, aw_cmd_reg.size,  ar_cmd_reg.size)
          val cmd_burst = Mux(is_write_transaction, aw_cmd_reg.burst, ar_cmd_reg.burst)
          val validationCmd = ValidationInput(addr = cmd_addr, len = cmd_len, size = cmd_size, burst = cmd_burst)
          val (is_error, sram_addr) = performValidation(validationCmd)

          when(is_error) {
            transaction_error_occurred := True
            when(is_write_transaction) { goto(WRITE_DATA_ERROR_CONSUME) } otherwise { goto(READ_RESPONSE_ERROR) }
          } otherwise {
            transaction_error_occurred := False
            current_sram_addr := sram_addr
            when(is_write_transaction) {
              goto(WRITE_DATA_FETCH)
            } otherwise {
              // --- OPTIMIZED ---: Directly jump to the fused setup-and-wait state
              goto(READ_SETUP_AND_WAIT)
            }
          }
        }
      }
    }

    val WRITE_DATA_FETCH: State = new State {
      onEntry {
        sram_data_writeEnable_out_reg := False
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
      }
      whenIsActive {
        io.axi.w.ready := True
        when(io.axi.w.fire) {
              if (enableLog) {
                report(L"W Fire in WRITE_DATA_FETCH! strb = ${io.axi.w.strb}")
              }
            // --- OPTIMIZED ---: Based on len, choose the fast path or the burst path
            when(aw_cmd_reg.len === 0) {
              goto(WRITE_SINGLE_CYCLE) // Fast path for len=0
            } otherwise {
              goto(WRITE_BURST_EXECUTE) // Burst path for len > 0
            }
        }
      }
    }
    
    // --- OPTIMIZED ---: New state for single-cycle writes (len=0)
    val WRITE_SINGLE_CYCLE: State = new State {
      onEntry {
        // Cycle 0: Assert write signals with data from W channel
        write_cycle_counter := 0
        sram_addr_out_reg     := current_sram_addr
        sram_data_out_reg     := io.axi.w.data
        sram_data_writeEnable_out_reg := True
        sram_be_n_out_reg     := MuxGen(config.sramByteEnableIsActiveLow, ~io.axi.w.strb, io.axi.w.strb)
        sram_ce_n_out_reg     := False
        sram_oe_n_out_reg     := True
        sram_we_n_out_reg     := False // WE_n goes low, starts the write
        if (enableLog) report(L"WRITE_SINGLE_CYCLE: onEntry. sram_addr_out_reg=${sram_addr_out_reg}, sram_data_out_reg=${sram_data_out_reg}, sram_be_n_out_reg=${sram_be_n_out_reg}")
      }

      whenIsActive {
        write_cycle_counter := write_cycle_counter + 1
        io.axi.b.valid := False // AXI B channel not ready yet

        // De-assert WE_n at the end of the write pulse width (t_WP)
        // This happens after (1 + writeWaitCycles) cycles.
        when(write_cycle_counter === U(config.writeWaitCycles)) {
          sram_we_n_out_reg := True // WE_n goes high
          if (enableLog) report(L"WRITE_SINGLE_CYCLE: WE_n high at cycle ${write_cycle_counter}")
        }

        // The full write cycle (t_WC) is over, send response
        // Total duration is (1 + writeWaitCycles + writeDeassertCycles)
        val total_duration = U(config.writeWaitCycles + config.writeDeassertCycles)
        when(write_cycle_counter === total_duration) {
          // Release SRAM bus completely
          sram_ce_n_out_reg := True
          sram_data_writeEnable_out_reg := False
          sram_be_n_out_reg := sram_be_n_inactive_value // Return to inactive state
          sram_addr_out_reg := 0 // Reset address
          if (enableLog) report(L"WRITE_SINGLE_CYCLE: SRAM released at cycle ${write_cycle_counter}")
          
          // Send AXI response
          io.axi.b.valid := True
          io.axi.b.payload.id := aw_cmd_reg.id
          io.axi.b.payload.resp := Mux(transaction_error_occurred, Axi4.resp.SLVERR, Axi4.resp.OKAY)
          if (enableLog) report(L"$instanceId WRITE_SINGLE_CYCLE_RESPONSE. ID=${aw_cmd_reg.id}, Resp=${io.axi.b.payload.resp}")

          when(io.axi.b.ready) {
            goto(IDLE)
          }
        }
      }
    }

    // --- OPTIMIZED ---: Renamed from WRITE_EXECUTE to be specific to burst
    val WRITE_BURST_EXECUTE: State = new State {
      val write_wait_counter = hasWriteWaitCycles generate Reg(UInt(log2Up(config.writeWaitCycles + 1) bits)) addAttribute("mark_debug","TRUE")
      onEntry {
        sram_addr_out_reg     := current_sram_addr
        sram_data_out_reg     := io.axi.w.data
        sram_data_writeEnable_out_reg := True
        sram_be_n_out_reg     := MuxGen(config.sramByteEnableIsActiveLow, ~io.axi.w.strb, io.axi.w.strb)
        sram_ce_n_out_reg     := False
        sram_oe_n_out_reg     := True
        sram_we_n_out_reg     := False
        hasWriteWaitCycles generate { write_wait_counter := 0 }
        if (enableLog) report(L"WRITE_BURST_EXECUTE: onEntry. sram_addr_out_reg=${sram_addr_out_reg}, sram_data_out_reg=${sram_data_out_reg}, sram_be_n_out_reg=${sram_be_n_out_reg}")
      }
      whenIsActive {
        val waitCond = if (hasWriteWaitCycles) write_wait_counter === config.writeWaitCycles else True
        when(waitCond) {
            burst_count_remaining := burst_count_remaining - 1
            goto(WRITE_BURST_DEASSERT)
        } otherwise {
            hasWriteWaitCycles generate { write_wait_counter := write_wait_counter + 1 }
        }
      }
    }

    // --- OPTIMIZED ---: Renamed from WRITE_DEASSERT
    val WRITE_BURST_DEASSERT: State = new State {
      val write_deassert_counter = Reg(UInt(log2Up(config.writeDeassertCycles) bits)) init(0) addAttribute("mark_debug","TRUE")
      onEntry {
        sram_we_n_out_reg := True
        write_deassert_counter := 0
        if (enableLog) report(L"WRITE_BURST_DEASSERT: onEntry. WE_n high.")
      }
      whenIsActive {
        val waitCond = write_deassert_counter === (config.writeDeassertCycles - 1)
        when(waitCond) {
            goto(WRITE_BURST_FINALIZE)
        } otherwise {
            write_deassert_counter.getWidth > 0 generate { write_deassert_counter := write_deassert_counter + 1 }
        }
      }
    }
    
    val WRITE_BURST_FINALIZE: State = new State {
        onEntry {
            sram_ce_n_out_reg := True
            sram_data_writeEnable_out_reg := False
            if (enableLog) report(L"WRITE_BURST_FINALIZE: onEntry. CE_n high, data_writeEnable_out_reg false.")
        }
        whenIsActive {
            when(burst_count_remaining === 0) {
                goto(WRITE_RESPONSE)
            } otherwise {
                current_sram_addr := getNextSramAddr(current_sram_addr, aw_cmd_reg.size)
                goto(WRITE_DATA_FETCH)
            }
        }
    }

    val WRITE_DATA_ERROR_CONSUME: State = new State {
      onEntry {
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        io.axi.aw.ready := False
        io.axi.ar.ready := False
        io.axi.w.ready := True
        
        if (enableLog) report(L"$instanceId WRITE_DATA_ERROR_CONSUME. BurstCountRem=${burst_count_remaining}")
        
        when(io.axi.w.fire) {
          burst_count_remaining := burst_count_remaining - 1
          when(burst_count_remaining === 0) { // Corrected from === 1 to === 0
            goto(WRITE_RESPONSE)
          }
        }
      }
    }

    val WRITE_RESPONSE: State = new State {
      onEntry {
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
      whenIsActive {
        val resp_status = transaction_error_occurred ? Axi4.resp.SLVERR | Axi4.resp.OKAY
        if (enableLog) report(L"$instanceId WRITE_RESPONSE. ID=${aw_cmd_reg.id}, Resp=${resp_status}, Addr=${aw_cmd_reg.addr}, Len=${aw_cmd_reg.len}, Size=${aw_cmd_reg.size}, Burst=${aw_cmd_reg.burst}, Lock=${aw_cmd_reg.lock}, Cache=${aw_cmd_reg.cache}, Prot=${aw_cmd_reg.prot}, Qos=${aw_cmd_reg.qos}, Region=${aw_cmd_reg.region}")
        
        io.axi.b.valid := True
        io.axi.b.payload.id := aw_cmd_reg.id
        io.axi.b.payload.resp := resp_status
        when(io.axi.b.ready) {
          if (enableLog)
            report(L"$instanceId B Ready. ID=${aw_cmd_reg.id}, Resp=${resp_status}")
          goto(IDLE)
        }
      }
    }

    // --- OPTIMIZED ---: New fused state for read setup and wait
    val READ_SETUP_AND_WAIT: State = new State {
      onEntry {
        // Assert read signals and address in one go
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True
        sram_addr_out_reg := current_sram_addr
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits) // Full byte enable for read
        
        hasReadWaitCycles generate { read_wait_counter := 0 }
        addr_prefetch_valid := False
        if (enableLog) report(L"$instanceId READ_SETUP_AND_WAIT: onEntry. SRAM Addr=0x${current_sram_addr}")
      }
      whenIsActive {
        // Keep signals asserted during wait
        sram_ce_n_out_reg := False
        sram_oe_n_out_reg := False
        sram_we_n_out_reg := True // Ensure WE_n stays high during read
        sram_data_writeEnable_out_reg := False // Ensure data bus is input
        sram_addr_out_reg := current_sram_addr // Ensure address stays stable
        sram_be_n_out_reg := B(0, config.dataWidth / 8 bits) // Ensure BE_n stays active for read

        // Prefetch logic for burst reads
        val prefetch_trigger_cycle = if (config.readWaitCycles == 0) U(0) else U(config.readWaitCycles - 1)
        val prefetch_waitCond = if (hasReadWaitCycles) read_wait_counter === prefetch_trigger_cycle else True
        when(prefetch_waitCond && ar_cmd_reg.len > 0 && !addr_prefetch_valid) {
          next_sram_addr_prefetch := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
          addr_prefetch_valid := True
          if (enableLog) report(L"$instanceId READ_SETUP_AND_WAIT: Address prefetch at wait_cycle ${hasReadWaitCycles generate read_wait_counter} - Next sram_addr 0x${next_sram_addr_prefetch}")
        }

        val waitCond = if (hasReadWaitCycles) read_wait_counter === config.readWaitCycles else True
        when(waitCond) {
          read_data_buffer := io.ram.data.read // Latch SRAM data
          if (enableLog) report(L"$instanceId READ_SETUP_AND_WAIT: Read data latched: 0x${read_data_buffer}")
          when(ar_cmd_reg.len === 0) {
              goto(READ_RESPOND_AND_FINISH) // Fast path for len=0
          } otherwise {
              goto(READ_RESPONSE) // Burst path for len > 0
          }
        } otherwise {
          hasReadWaitCycles generate { read_wait_counter := read_wait_counter + 1 }
        }
      }
    }

    // --- OPTIMIZED ---: Fast path response state for single reads (len=0)
    val READ_RESPOND_AND_FINISH: State = new State {
      onEntry {
        // Immediately release SRAM bus, data is already latched
        sram_ce_n_out_reg := True
        sram_oe_n_out_reg := True
        sram_we_n_out_reg := True // Ensure WE_n is high
        sram_data_writeEnable_out_reg := False // Ensure data bus is input/high-Z
        sram_be_n_out_reg := sram_be_n_inactive_value // Inactive byte enable
        sram_addr_out_reg := 0 // Reset address
        if (enableLog) report(L"READ_RESPOND_AND_FINISH: onEntry. SRAM released.")
      }
      whenIsActive {
        val resp_status = Mux(transaction_error_occurred, Axi4.resp.SLVERR, Axi4.resp.OKAY)
        val data_payload = Mux(transaction_error_occurred, B(0, axiConfig.dataWidth bits), read_data_buffer)

        io.axi.r.valid := True
        io.axi.r.payload.id   := ar_cmd_reg.id
        io.axi.r.payload.data := data_payload
        io.axi.r.payload.resp := resp_status
        io.axi.r.payload.last := True // Always last in this state

        if (enableLog) {
          report(L"$instanceId FAST_READ_RESPONSE. ID=${ar_cmd_reg.id}, Data=0x${data_payload}, Last=True")
        }

        when(io.axi.r.fire) {
          goto(IDLE)
        }
      }
    }

    private def getNextSramAddr(currentAddr: UInt, axiSize: UInt): UInt = {
        val bytesIncrement = if (axiConfig.useSize) U(1) << axiSize else U(config.bytesPerWord)
        val sramAddrIncrement = if (config.useWordAddressing) (bytesIncrement / config.bytesPerWord).resized else bytesIncrement.resized
        return currentAddr + sramAddrIncrement
    }

    val READ_RESPONSE: State = new State {
      onEntry {
        // No SRAM signal changes on entry, they are held from READ_SETUP_AND_WAIT or will be set onExit
      }
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (enableLog)
          report(
            L"$instanceId READ_RESPONSE. ID=${ar_cmd_reg.id}, Data=0x${read_data_buffer}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.OKAY}, Last=${is_last_beat}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := read_data_buffer
        io.axi.r.payload.resp := Axi4.resp.OKAY
        io.axi.r.payload.last := is_last_beat

        when(io.axi.r.fire) {
          if (enableLog) report(L"$instanceId R Fire. Last=${io.axi.r.last}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            goto(IDLE)
          } otherwise {
            when(addr_prefetch_valid) {
              current_sram_addr := next_sram_addr_prefetch
              addr_prefetch_valid := False // 已经使用了预取地址，清空标志
            } otherwise {
              current_sram_addr := getNextSramAddr(current_sram_addr, ar_cmd_reg.size)
            }
            // --- OPTIMIZED ---: Loop back to the fused setup-and-wait state
            goto(READ_SETUP_AND_WAIT)
          }
        } otherwise {
          if (enableLog) report(L"io.axi.r.valid = ${io.axi.r.valid}, io.axi.r.ready = ${io.axi.r.ready}")
        }
      }
      onExit {
        // --- MODIFIED: 确保退出读响应状态时，SRAM信号恢复非活动状态 ---
        sram_oe_n_out_reg := True
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
    }

    val READ_RESPONSE_ERROR: State = new State {
      onEntry {
        // No SRAM signal changes on entry, they are held from previous state or will be set onExit
      }
      whenIsActive {
        val is_last_beat = burst_count_remaining === 1
        if (enableLog)
          report(
            L"$instanceId READ_RESPONSE_ERROR. ID=${ar_cmd_reg.id}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.SLVERR}, Last=${is_last_beat}, r.valid=${io.axi.r.valid}, r.ready=${io.axi.r.ready}, r.fire=${io.axi.r.fire}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := B(0, axiConfig.dataWidth bits)
        io.axi.r.payload.resp := Axi4.resp.SLVERR
        io.axi.r.payload.last := is_last_beat

        when(io.axi.r.fire) {
          if (enableLog)
            report(L"$instanceId READ_RESPONSE_ERROR - r.fire detected! BurstCountRem=${burst_count_remaining}, is_last_beat=${is_last_beat}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            if (enableLog)
              report(L"$instanceId READ_RESPONSE_ERROR - Going to IDLE")
            goto(IDLE)
          }
        }
      }
      onExit {
        // Ensure SRAM signals are inactive when exiting error state
        sram_oe_n_out_reg := True
        sram_ce_n_out_reg := True
        sram_we_n_out_reg := True
        sram_data_writeEnable_out_reg := False
        sram_be_n_out_reg := sram_be_n_inactive_value
        sram_addr_out_reg := 0
      }
    }
  }

  fsm.build()
  fsm.stateReg.addAttribute("MARK_DEBUG", "TRUE")
  Seq(io.axi.aw.fire, io.axi.ar.fire, io.axi.w.fire, io.axi.r.fire, io.axi.b.fire).foreach(
    _.addAttribute("mark_debug", "true")
  )

  if (enableLog) {
    val currentCycle = Reg(UInt(32 bits)) init(0)
    currentCycle := currentCycle + 1
    report(
      L"SRAMController ${instanceId} - Cycle ${currentCycle}: AXI Status\n" :+
      L"  FSM State: ${fsm.stateReg}\n" :+
      L"  AW: v=${io.axi.aw.valid} r=${io.axi.aw.ready} fire=${io.axi.aw.fire} addr=${io.axi.aw.addr} id=${io.axi.aw.id} len=${io.axi.aw.len} size=${io.axi.aw.size} burst=${io.axi.aw.burst}\n" :+
      L"  AR: v=${io.axi.ar.valid} r=${io.axi.ar.ready} fire=${io.axi.ar.fire} addr=${io.axi.ar.addr}\n" :+
      L"  W: v=${io.axi.w.valid} r=${io.axi.w.ready} fire=${io.axi.w.fire} data=${io.axi.w.data} strb=${io.axi.w.strb} last=${io.axi.w.last}\n" :+
      L"  R: v=${io.axi.r.valid} r=${io.axi.r.ready} fire=${io.axi.r.fire} data=${io.axi.r.data} last=${io.axi.r.last}\n" :+
      L"  B: v=${io.axi.b.valid} r=${io.axi.b.ready} fire=${io.axi.b.fire}\n" :+
      L"  Internal: BurstRemaining=${fsm.burst_count_remaining}, CurrentSRAMAddr=${fsm.current_sram_addr}, ReadPriority=${fsm.read_priority}"
    )
  }

  
  val sram_ce_n_out_reg_prev = RegNext(sram_ce_n_out_reg, True)
  val sram_we_n_out_reg_prev = RegNext(sram_we_n_out_reg, True)
  when(sram_ce_n_out_reg_prev === False && sram_we_n_out_reg_prev === False) {
    when(sram_ce_n_out_reg === True && sram_we_n_out_reg === True) {
      assert(False, "ERROR: SRAM CE and WE are both active at the same time.")
    }
  }
}
