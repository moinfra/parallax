package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.fsm._
import spinal.lib.io.TriState
import spinal.lib.bus.amba4.axi.Axi4Aw

// ExtSRAM 的配置参数
case class ExtSRAMConfig(
    addressWidth: Int,
    dataWidth: Int,
    virtualBaseAddress: BigInt = 0x0,
    sizeBytes: BigInt,
    readWaitCycles: Int = 0,
    sramByteEnableIsActiveLow: Boolean = true,
    enableLog: Boolean = true
) {
  require(isPow2(dataWidth / 8), "dataWidth must be a power of 2 bytes")
  require(sizeBytes > 0 && sizeBytes % (dataWidth / 8) == 0, "sramSize must be a multiple of data bus width")
  require(sizeBytes <= (BigInt(1) << addressWidth), "sizeBytes exceeds addressable space")

  val sramSizeHw = sizeBytes
  val bytesPerWord: Int = dataWidth / 8
  val internalWordCount: BigInt = sizeBytes / bytesPerWord
  val internalWordMaxAddr: BigInt = internalWordCount - 1
  val internalMaxByteAddr = sizeBytes - 1
  val internalWordAddrWidth = log2Up(internalWordCount)
}

// ExtSRAM 的外部引脚定义
case class ExtSRAMIo(c: ExtSRAMConfig) extends Bundle with IMasterSlave {
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

// ExtSRAMController 组件
class ExtSRAMController(axiConfig: Axi4Config, ExtSRAMConfig: ExtSRAMConfig) extends Component {
  require(
    axiConfig.dataWidth == ExtSRAMConfig.dataWidth,
    s"AXI and SRAM data width must match axiConfig.dataWidth = ${axiConfig.dataWidth} ExtSRAMConfig.dataWidth = ${ExtSRAMConfig.dataWidth}"
  )
  val hasReadWaitCycles = ExtSRAMConfig.readWaitCycles > 0
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val ram = master(ExtSRAMIo(ExtSRAMConfig))
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
  // START PATCH: SRAM control signal defaults, registers for write, and address drive
  val sram_write_addr_reg = Reg(UInt(ExtSRAMConfig.addressWidth bits)) init (0)
  val sram_write_data_reg = Reg(Bits(ExtSRAMConfig.dataWidth bits)) init (0)
  // 初始化字节使能寄存器，如果低有效，则全1表示无效；如果高有效，则全0表示无效
  val sram_be_n_inactive_value =
    if (ExtSRAMConfig.sramByteEnableIsActiveLow)
      B((1 << (ExtSRAMConfig.dataWidth / 8)) - 1, ExtSRAMConfig.dataWidth / 8 bits)
    else B(0, ExtSRAMConfig.dataWidth / 8 bits)
  val sram_write_be_n_reg = Reg(Bits(ExtSRAMConfig.dataWidth / 8 bits)) init (sram_be_n_inactive_value)
  val sram_perform_write = Reg(Bool()) init (False)

  io.ram.ce_n := True
  io.ram.oe_n := True
  io.ram.we_n := True
  io.ram.be_n := sram_be_n_inactive_value // 默认所有字节不使能
  io.ram.addr := 0 // 默认地址为0，或使用 assignDontCare() 如果无关紧要

  // SRAM 写操作时的数据和地址由寄存器驱动
  io.ram.data.write := sram_write_data_reg
  io.ram.data.writeEnable := sram_perform_write
  // SRAM 地址在写和读状态下会被明确设置
  // END PATCH

  // --- 状态机定义 ---
  val fsm = new StateMachine {
    val ar_cmd_reg = Reg(cloneOf(io.axi.ar.payload))
    val aw_cmd_reg = Reg(cloneOf(io.axi.aw.payload))
    val burst_count_remaining = Reg(UInt(axiConfig.lenWidth + 1 bits))
    val current_sram_addr = Reg(UInt(ExtSRAMConfig.addressWidth bits))
    val read_data_buffer = Reg(Bits(ExtSRAMConfig.dataWidth bits))
    val read_wait_counter = hasReadWaitCycles generate Reg(UInt(log2Up(ExtSRAMConfig.readWaitCycles + 1) bits))
    val transaction_error_occurred = Reg(Bool()) init (False)

    val read_priority = Reg(Bool()) init (False)

    val next_sram_addr_prefetch = Reg(UInt(ExtSRAMConfig.addressWidth bits))
    val addr_prefetch_valid = Reg(Bool()) init (False)

    // START PATCH: Removed虚构方法
    // setTransitionInstantiator { (_, _) => new Transition }
    // END PATCH

    val IDLE: State = new State with EntryPoint {
      onEntry {
        sram_perform_write := False
      }
      whenIsActive {
        if (ExtSRAMConfig.enableLog) report(L"ExtSRAMController: IDLE, read_priority=${read_priority}")
        io.ram.ce_n := True
        io.ram.oe_n := True
        io.ram.we_n := True
        // START PATCH: SRAM 地址在IDLE时可以设为不关心或固定值
        io.ram.addr := 0 // 或者 current_sram_addr 如果希望保持上一个地址
        // END PATCH
        transaction_error_occurred := False
        addr_prefetch_valid := False

        io.axi.aw.ready := !read_priority || !io.axi.ar.valid
        io.axi.ar.ready := read_priority || !io.axi.aw.valid

        when(io.axi.aw.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${(io.axi.aw).size}" else L""
          if (ExtSRAMConfig.enableLog)
            report(
              L"ExtSRAMController: AW Fire. Addr=0x${io.axi.aw.addr}, ID=${io.axi.aw.id}, Len=${io.axi.aw.len}, Burst=${io.axi.aw.burst}${sizeInfo}"
            )
          aw_cmd_reg := io.axi.aw.payload
          burst_count_remaining := (io.axi.aw.len + 1).resize(burst_count_remaining.getWidth)
          val sram_addr_candidate = (io.axi.aw.addr - ExtSRAMConfig.virtualBaseAddress)
          current_sram_addr := sram_addr_candidate.resized
          read_priority := !read_priority

          val bytesPerBeat = if (axiConfig.useSize) U(1) << io.axi.aw.size else U(ExtSRAMConfig.dataWidth / 8)
          val burst_bytes_total = (io.axi.aw.len + 1) * bytesPerBeat
          val end_sram_addr_candidate = sram_addr_candidate + burst_bytes_total - bytesPerBeat
          val addr_aligned = (io.axi.aw.addr & (bytesPerBeat - 1).resize(io.axi.aw.addr.getWidth)) === 0

          when(io.axi.aw.burst =/= Axi4.burst.INCR) {
            if (ExtSRAMConfig.enableLog)
              report(L"ExtSRAMController: AW Error - Unsupported burst type: ${io.axi.aw.burst}")
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } elsewhen (!addr_aligned) {
            if (ExtSRAMConfig.enableLog)
              report(L"ExtSRAMController: AW Error - Address unaligned: 0x${io.axi.aw.addr} for size ${bytesPerBeat}")
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } elsewhen (sram_addr_candidate.asSInt < 0 || end_sram_addr_candidate >= ExtSRAMConfig.sramSizeHw) {
            if (ExtSRAMConfig.enableLog)
              report(
                L"ExtSRAMController: AW Error - Address out of bounds. SRAM Addr=0x${sram_addr_candidate}, End Addr=0x${end_sram_addr_candidate}, SRAM Size=0x${ExtSRAMConfig.sramSizeHw}"
              )
            transaction_error_occurred := True
            goto(WRITE_DATA_ERROR_CONSUME)
          } otherwise {
            goto(WRITE_DATA)
          }
        }

        when(io.axi.ar.fire) {
          val sizeInfo = if (axiConfig.useSize) L", Size=${(io.axi.ar).size}" else L""
          if (ExtSRAMConfig.enableLog)
            report(
              L"ExtSRAMController: AR Fire. Addr=0x${io.axi.ar.addr}, ID=${io.axi.ar.id}, Len=${io.axi.ar.len}, Burst=${io.axi.ar.burst}${sizeInfo}"
            )
          ar_cmd_reg := io.axi.ar.payload
          burst_count_remaining := (io.axi.ar.len + 1).resize(burst_count_remaining.getWidth)
          val sram_addr_candidate = (io.axi.ar.addr - ExtSRAMConfig.virtualBaseAddress)
          current_sram_addr := sram_addr_candidate.resized
          read_priority := !read_priority

          val bytesPerBeat = if (axiConfig.useSize) U(1) << io.axi.ar.size else U(ExtSRAMConfig.dataWidth / 8)
          val burst_bytes_total = (io.axi.ar.len + 1) * bytesPerBeat
          val end_sram_addr_candidate = sram_addr_candidate + burst_bytes_total - bytesPerBeat
          val addr_aligned = (io.axi.ar.addr & (bytesPerBeat - 1).resize(io.axi.ar.addr.getWidth)) === 0

          when(io.axi.ar.burst =/= Axi4.burst.INCR) {
            if (ExtSRAMConfig.enableLog)
              report(L"ExtSRAMController: AR Error - Unsupported burst type: ${io.axi.ar.burst}")
            transaction_error_occurred := True
            goto(READ_RESPONSE_ERROR)
          } elsewhen (!addr_aligned) {
            if (ExtSRAMConfig.enableLog)
              report(L"ExtSRAMController: AR Error - Address unaligned: 0x${io.axi.ar.addr} for size ${bytesPerBeat}")
            transaction_error_occurred := True
            goto(READ_RESPONSE_ERROR)
          } elsewhen (sram_addr_candidate.asSInt < 0 || end_sram_addr_candidate >= ExtSRAMConfig.sramSizeHw) {
            if (ExtSRAMConfig.enableLog)
              report(
                L"ExtSRAMController: AR Error - Address out of bounds. SRAM Addr=0x${sram_addr_candidate}, End Addr=0x${end_sram_addr_candidate}, SRAM Size=0x${ExtSRAMConfig.sramSizeHw}"
              )
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
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: WRITE_DATA. SRAM_Target_Addr=0x${current_sram_addr}, SRAM_Actual_Write_Addr=0x${sram_write_addr_reg}, BurstCountRem=${burst_count_remaining}, sram_perform_write=${sram_perform_write}"
          )

        io.axi.w.ready := !sram_perform_write
        io.ram.ce_n := False
        io.ram.oe_n := True
        // START PATCH: SRAM 地址由锁存的 sram_write_addr_reg 驱动，字节使能由锁存的 sram_write_be_n_reg 驱动
        io.ram.addr := sram_write_addr_reg
        io.ram.we_n := !sram_perform_write
        io.ram.be_n := sram_write_be_n_reg
        // END PATCH

        when(sram_perform_write) {
          sram_perform_write := False

          val bytesIncrement = if (axiConfig.useSize) U(1) << aw_cmd_reg.size else U(ExtSRAMConfig.dataWidth / 8)
          current_sram_addr := current_sram_addr + bytesIncrement // 更新下一个节拍的目标地址
          burst_count_remaining := burst_count_remaining - 1

          when(burst_count_remaining === 1) {
            goto(WRITE_RESPONSE)
          }
        } elsewhen (io.axi.w.fire) {
          if (ExtSRAMConfig.enableLog)
            report(
              L"ExtSRAMController: W Fire. Data=0x${io.axi.w.data}, Strb=0x${io.axi.w.strb}, Last=${io.axi.w.last}"
            )

          sram_write_addr_reg := current_sram_addr
          sram_write_data_reg := io.axi.w.data
          // START PATCH: 字节使能处理
          if (ExtSRAMConfig.sramByteEnableIsActiveLow) {
            sram_write_be_n_reg := ~io.axi.w.strb
          } else {
            sram_write_be_n_reg := io.axi.w.strb
          }
          // END PATCH
          sram_perform_write := True
        }
      }
    }

    val WRITE_DATA_ERROR_CONSUME: State = new State {
      whenIsActive {
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: WRITE_DATA_ERROR_CONSUME. BurstCountRem=${burst_count_remaining}"
          )
        io.axi.w.ready := True
        io.ram.ce_n := True
        io.ram.we_n := True
        io.ram.oe_n := True
        // START PATCH: SRAM 地址在错误消耗数据时可以设为不关心或固定值
        io.ram.addr := 0
        // END PATCH
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
        if (ExtSRAMConfig.enableLog)
          report(L"ExtSRAMController: WRITE_RESPONSE. ID=${aw_cmd_reg.id}, Resp=${resp_status}")
        io.ram.ce_n := True
        io.ram.we_n := True
        io.ram.oe_n := True
        // START PATCH: SRAM 地址
        io.ram.addr := 0 // 或者 sram_write_addr_reg 如果希望保持上一个有效写地址
        // END PATCH
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
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: READ_SETUP. SRAM Addr=0x${current_sram_addr}, BurstCountRem=${burst_count_remaining}"
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

    val READ_WAIT: State = new State {
      whenIsActive {
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: READ_WAIT. SRAM Addr=0x${current_sram_addr}, WaitCounter=${hasReadWaitCycles generate read_wait_counter}, AddrPrefetchValid=${addr_prefetch_valid}"
          )
        io.ram.ce_n := False
        io.ram.oe_n := False
        io.ram.we_n := True
        io.ram.addr := current_sram_addr

        val prefetch_trigger_cycle =
          if (ExtSRAMConfig.readWaitCycles == 0) U(0) else U(ExtSRAMConfig.readWaitCycles - 1)
        val prefetch_waitCond = if (hasReadWaitCycles) read_wait_counter === prefetch_trigger_cycle else True
        when(
          prefetch_waitCond &&
            burst_count_remaining > 1 &&
            !addr_prefetch_valid
        ) {
          val bytesIncrement = if (axiConfig.useSize) U(1) << ar_cmd_reg.size else U(ExtSRAMConfig.dataWidth / 8)
          next_sram_addr_prefetch := current_sram_addr + bytesIncrement
          addr_prefetch_valid := True
          if (ExtSRAMConfig.enableLog)
            report(
              L"ExtSRAMController: Address prefetch at wait_cycle ${hasReadWaitCycles generate read_wait_counter} - Next sram_addr 0x${(current_sram_addr + bytesIncrement)}"
            )
        }
        val waitCond = if (hasReadWaitCycles) read_wait_counter === ExtSRAMConfig.readWaitCycles else True
        when(waitCond) {
          read_data_buffer := io.ram.data.read
          if (ExtSRAMConfig.readWaitCycles == 0) {
            when(burst_count_remaining > 1 && !addr_prefetch_valid) {
              val bytesIncrement = if (axiConfig.useSize) U(1) << ar_cmd_reg.size else U(ExtSRAMConfig.dataWidth / 8)
              next_sram_addr_prefetch := current_sram_addr + bytesIncrement
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
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: READ_RESPONSE. ID=${ar_cmd_reg.id}, Data=0x${read_data_buffer}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.OKAY}, Last=${is_last_beat}"
          )

        io.axi.r.valid := True
        io.axi.r.payload.id := ar_cmd_reg.id
        io.axi.r.payload.data := read_data_buffer
        io.axi.r.payload.resp := Axi4.resp.OKAY
        io.axi.r.payload.last := is_last_beat
        // START PATCH: SRAM 地址在读响应发送期间可以设为不关心或保持
        io.ram.addr := current_sram_addr // 或者 0 / assignDontCare
        // END PATCH

        when(io.axi.r.fire) {
          if (ExtSRAMConfig.enableLog) report(L"ExtSRAMController: R Fire. Last=${io.axi.r.last}")
          burst_count_remaining := burst_count_remaining - 1
          when(is_last_beat) {
            goto(IDLE)
          } otherwise {
            when(addr_prefetch_valid) {
              current_sram_addr := next_sram_addr_prefetch
            } otherwise {
              val bytesIncrement = if (axiConfig.useSize) U(1) << ar_cmd_reg.size else U(ExtSRAMConfig.dataWidth / 8)
              current_sram_addr := current_sram_addr + bytesIncrement
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
        if (ExtSRAMConfig.enableLog)
          report(
            L"ExtSRAMController: READ_RESPONSE_ERROR. ID=${ar_cmd_reg.id}, BurstCountRem=${burst_count_remaining}, Resp=${Axi4.resp.SLVERR}, Last=${is_last_beat}"
          )

        io.ram.ce_n := True
        io.ram.oe_n := True
        io.ram.we_n := True
        // START PATCH: SRAM 地址
        io.ram.addr := 0 // 或者 current_sram_addr
        // END PATCH

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

object ExtSRAMControllerGen extends App {
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

  val ramConfig = ExtSRAMConfig(
    addressWidth = 20,
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 1 << 20,
    readWaitCycles = 0,
    sramByteEnableIsActiveLow = true, // 假设低有效
    enableLog = true
  )

  SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC)
  ).generateVerilog(new ExtSRAMController(axiConfig, ramConfig).setDefinitionName("ExtSRAMController"))
}
