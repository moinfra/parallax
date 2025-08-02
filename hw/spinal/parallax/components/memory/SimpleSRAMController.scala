package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Ar, Axi4B, Axi4R, Axi4Config}

// --- 辅助对象和Case Class ---
object PipelinedSRAMControllerUtils {
  case class SramWriteCmd(axiConfig: Axi4Config, sramConfig: SRAMConfig) extends Bundle {
    val sram_addr = UInt(sramConfig.addressWidth bits)
    val data = Bits(sramConfig.dataWidth bits)
    val be_n = Bits(sramConfig.dataWidth / 8 bits)
    val id = UInt(axiConfig.idWidth bits)
  }

  case class ArbitratedCmd(axiConfig: Axi4Config, sramConfig: SRAMConfig) extends Bundle with IMasterSlave {
    val isWrite = Bool()
    val writeCmd = SramWriteCmd(axiConfig, sramConfig)
    val readCmd = Axi4Ar(axiConfig)
    override def asMaster(): Unit = out(isWrite, writeCmd, readCmd)
  }

  case class ExecutionMeta(axiConfig: Axi4Config) extends Bundle {
    val id = UInt(axiConfig.idWidth bits)
    val isWrite = Bool()
  }
}

class PipelinedSRAMController(val axiConfig: Axi4Config, val config: SRAMConfig) extends Component {
  import PipelinedSRAMControllerUtils._

  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val ram = master(SRAMIO(config))
  }

  // =========================================================================
  // === 1. DECODE Stage: AXI请求仲裁与缓冲
  // =========================================================================
  val decodeStage = new Area {
    val aw_stream = io.axi.aw.throwWhen(io.axi.aw.len =/= 0)
    val w_stream = io.axi.w
    val ar_stream = io.axi.ar.throwWhen(io.axi.ar.len =/= 0)

    val write_cmd_stream = StreamJoin.apply(aw_stream, w_stream).translateWith {
      val cmd = SramWriteCmd(axiConfig, config)
      cmd.sram_addr := calculateSramAddress(aw_stream.payload.addr)
      cmd.data := w_stream.payload.data
      cmd.be_n := (if (config.sramByteEnableIsActiveLow) ~w_stream.payload.strb else w_stream.payload.strb)
      cmd.id := aw_stream.payload.id
      cmd
    }

    val unified_write_stream = write_cmd_stream.map { p =>
      val cmd = ArbitratedCmd(axiConfig, config)
      cmd.isWrite := True
      cmd.writeCmd := p
      cmd.readCmd.assignDontCare()
      cmd
    }
    val unified_read_stream = ar_stream.map { p =>
      val cmd = ArbitratedCmd(axiConfig, config)
      cmd.isWrite := False
      cmd.writeCmd.assignDontCare()
      cmd.readCmd := p
      cmd
    }

    val arbiter = StreamArbiterFactory.roundRobin.on(Seq(unified_read_stream, unified_write_stream))

    val request_fifo = StreamFifo(dataType = ArbitratedCmd(axiConfig, config), depth = 4)
    request_fifo.io.push << arbiter
  }

  // =========================================================================
  // === 信号作用域提升 (SCOPE FIX)
  // =========================================================================
  // 将 sram_cmd 定义在 executeStage 和 outputDriver 外部，使其在组件内全局可见
  val sram_cmd = decodeStage.request_fifo.io.pop

  // =========================================================================
  // === 2. EXECUTE Stage: 完全扁平化的时序控制
  // =========================================================================
  val executeStage = new Area {
    val executing_fifo = StreamFifo(
      dataType = ExecutionMeta(axiConfig),
      depth = config.readWaitCycles + config.writeDeassertCycles + 5
    )

    val op_in_progress = RegInit(False)
    val max_op_cycles = Math.max(config.writeDeassertCycles, config.readWaitCycles) + 2
    val op_counter = Reg(UInt(log2Up(max_op_cycles) bits)) init (0)
    val current_op_is_write = Reg(Bool()) init (False)
    val read_data_buffer = Reg(Bits(config.dataWidth bits))

    sram_cmd.ready := !op_in_progress && executing_fifo.io.push.ready

    val is_write_we_deassert_phase = op_in_progress && current_op_is_write && (op_counter === config.writeDeassertCycles)
    val is_write_ce_deassert_phase = op_in_progress && current_op_is_write && (op_counter === (config.writeDeassertCycles + 1))
    val is_read_sampling_phase = op_in_progress && !current_op_is_write && (op_counter === config.readWaitCycles)
    val is_read_ceoe_deassert_phase = op_in_progress && !current_op_is_write && (op_counter === (config.readWaitCycles + 1))
    val op_is_finishing = is_write_ce_deassert_phase || is_read_ceoe_deassert_phase

    op_in_progress := (op_in_progress && !op_is_finishing) || sram_cmd.fire
    op_counter := Mux(sram_cmd.fire || op_is_finishing, U(0), op_counter + 1)
    current_op_is_write := Mux(sram_cmd.fire, sram_cmd.payload.isWrite, current_op_is_write)

    executing_fifo.io.push.valid := sram_cmd.fire
    executing_fifo.io.push.payload.id := Mux(sram_cmd.payload.isWrite, sram_cmd.payload.writeCmd.id, sram_cmd.payload.readCmd.id)
    executing_fifo.io.push.payload.isWrite := sram_cmd.payload.isWrite
    executing_fifo.io.pop.ready := op_is_finishing

    when(is_read_sampling_phase) {
      read_data_buffer := io.ram.data.read
    }
  }

  // =========================================================================
  // === 输出驱动区域 (版本 5 - 最终修复)
  // =========================================================================
  // =========================================================================
  // === 输出驱动区域 (黄金法则版)
  // =========================================================================
  val outputDriver = new Area {
    // --- 引用状态 ---
    val op_in_progress = executeStage.op_in_progress
    val current_op_is_write = executeStage.current_op_is_write
    val sram_cmd_fire = sram_cmd.fire
    val sram_cmd_payload = sram_cmd.payload
    val op_is_finishing = executeStage.op_is_finishing
    
    // --- 状态寄存器 ---
    // 这个信号表示“我们正处于地址建立周期”
    val addr_setup_active = RegNext(sram_cmd_fire) init(False)

    // --- 锁存地址、数据、字节使能 ---
    val sram_addr_reg  = RegNextWhen(Mux(sram_cmd_payload.isWrite, sram_cmd_payload.writeCmd.sram_addr, calculateSramAddress(sram_cmd_payload.readCmd.addr)), sram_cmd_fire) init(0)
    val sram_wdata_reg = RegNextWhen(sram_cmd_payload.writeCmd.data, sram_cmd_fire) init(0)
    val sram_be_n_reg  = RegNextWhen(sram_cmd_payload.writeCmd.be_n, sram_cmd_fire) init(B((1 << config.dataWidth/8) - 1))
    
    // --- 控制信号逻辑 ---
    // CE_n 在地址建立和操作执行阶段都有效
    io.ram.ce_n := !(addr_setup_active || (op_in_progress && !op_is_finishing))

    // WE_n 只在操作执行阶段有效 (注意：不是地址建立阶段)
    io.ram.we_n := !(op_in_progress && current_op_is_write && !op_is_finishing)
    
    // OE_n 只在操作执行阶段有效
    io.ram.oe_n := !(op_in_progress && !current_op_is_write && !op_is_finishing)
    
    // --- 数据总线驱动 ---
    io.ram.addr           := sram_addr_reg
    io.ram.data.write     := sram_wdata_reg
    io.ram.data.writeEnable := (op_in_progress && current_op_is_write)
    io.ram.be_n           := Mux(op_in_progress && current_op_is_write, sram_be_n_reg, B((1 << config.dataWidth/8) - 1))
  }
  // =========================================================================
  // === 3. WRITEBACK Stage: AXI响应发送
  // =========================================================================
  val writebackStage = new Area {
    val b_response_fifo = StreamFifo(Axi4B(axiConfig), depth = 4)
    val r_response_fifo = StreamFifo(Axi4R(axiConfig), depth = 4)

    b_response_fifo.io.push.setIdle()
    r_response_fifo.io.push.setIdle()

    when(executeStage.executing_fifo.io.pop.fire) {
      val popped_cmd = executeStage.executing_fifo.io.pop.payload
      when(popped_cmd.isWrite) {
        b_response_fifo.io.push.valid := True
        b_response_fifo.io.push.payload.id := popped_cmd.id
        b_response_fifo.io.push.payload.resp := Axi4.resp.OKAY
      } otherwise {
        r_response_fifo.io.push.valid := True
        r_response_fifo.io.push.payload.id := popped_cmd.id
        r_response_fifo.io.push.payload.data := executeStage.read_data_buffer
        r_response_fifo.io.push.payload.resp := Axi4.resp.OKAY
        r_response_fifo.io.push.payload.last := True
      }
    }

    io.axi.b << b_response_fifo.io.pop
    io.axi.r << r_response_fifo.io.pop
  }

  // --- 辅助函数 ---
  private def calculateSramAddress(axiAddr: UInt): UInt = {
    val vBaseBitLength = if (config.virtualBaseAddress == 0) 1 else log2Up(config.virtualBaseAddress) + 1
    val extendedWidth = Math.max(axiConfig.addressWidth, vBaseBitLength) + 1
    val addr_extended = axiAddr.resize(extendedWidth)
    val vbase_extended = U(config.virtualBaseAddress, extendedWidth bits)
    val byte_offset_addr = addr_extended - vbase_extended
    (byte_offset_addr(config.byteAddrWidth - 1 downto 0) >> config.addressShift).resized
  }
}
