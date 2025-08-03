package parallax.components.uart

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.fsm._

// 这个BlackBox精确匹配官方 uart_wrapper.v 的IO接口
class UartWrapperBlackbox(clk_freq: BigInt = 100000000L, uart_baud: Int = 9600) extends BlackBox {
  // 设置Verilog模块名和参数
  setBlackBoxName("uart_wrapper")
  addGeneric("clk_freq", clk_freq)
  addGeneric("uart_baud", uart_baud)

  addRTLPath("./soc/uart_wrapper.sv")
  addRTLPath("./soc/async.v")

  // 定义IO Bundle
  val io = new Bundle {
    val clk = in Bool ()
    val rst = in Bool ()

    // AXI4-Lite Slave Interface (扁平化)
    val io_uart_ar_ready = out Bool ()
    val io_uart_r_id = out Bits (8 bits)
    val io_uart_r_resp = out Bits (2 bits)
    val io_uart_r_data = out Bits (32 bits)
    val io_uart_r_last = out Bool ()
    val io_uart_r_valid = out Bool ()
    val io_uart_aw_ready = out Bool ()
    val io_uart_w_ready = out Bool ()
    val io_uart_b_id = out Bits (8 bits)
    val io_uart_b_resp = out Bits (2 bits)
    val io_uart_b_valid = out Bool ()

    val io_uart_ar_id = in Bits (8 bits)
    val io_uart_ar_addr = in Bits (32 bits)
    val io_uart_ar_len = in Bits (8 bits)
    val io_uart_ar_size = in Bits (3 bits)
    val io_uart_ar_burst = in Bits (2 bits)
    val io_uart_ar_valid = in Bool ()
    val io_uart_r_ready = in Bool ()
    val io_uart_aw_id = in Bits (8 bits)
    val io_uart_aw_addr = in Bits (32 bits)
    val io_uart_aw_len = in Bits (8 bits)
    val io_uart_aw_size = in Bits (3 bits)
    val io_uart_aw_burst = in Bits (2 bits)
    val io_uart_aw_valid = in Bool ()
    val io_uart_w_data = in Bits (32 bits)
    val io_uart_w_strb = in Bits (4 bits)
    val io_uart_w_last = in Bool ()
    val io_uart_w_valid = in Bool ()
    val io_uart_b_ready = in Bool ()

    // 物理串口线
    val txd = out Bool ()
    val rxd = in Bool ()
  }

  // 告诉SpinalHDL不要给IO信号添加 "io_" 前缀
  noIoPrefix()

  // 如果你的时钟域名不是 "clk"，需要手动映射
  mapCurrentClockDomain(clock = io.clk, reset = io.rst)
}

class async_receiver(clk_freq: BigInt, uart_baud: Int) extends BlackBox {
  setBlackBoxName("async_receiver")
  addGeneric("ClkFrequency", clk_freq)
  addGeneric("Baud", uart_baud)
  addRTLPath("./soc/async.v")

  val io = new Bundle {
    val clk = in Bool ()
    val RxD = in Bool ()
    val RxD_data_ready = out Bool ()
    val RxD_clear = in Bool ()
    val RxD_data = out(Bits(8 bits))
  }
  noIoPrefix()
  mapCurrentClockDomain(clock = io.clk)
}

class async_transmitter(clk_freq: BigInt, uart_baud: Int) extends BlackBox {
  setBlackBoxName("async_transmitter")
  addGeneric("ClkFrequency", clk_freq)
  addGeneric("Baud", uart_baud)
  addRTLPath("./soc/async.v")

  val io = new Bundle {
    val clk = in Bool ()
    val TxD = out Bool ()
    val TxD_busy = out Bool ()
    val TxD_start = in Bool ()
    val TxD_data = in(Bits(8 bits))
  }
  noIoPrefix()
  mapCurrentClockDomain(clock = io.clk)
}
// 定义配置参数，提高可复用性
case class UartAxiControllerConfig(
    axiConfig: Axi4Config,
    clk_freq: BigInt,
    uart_baud: Int,
    fifoDepth: Int = 8 // 接收FIFO深度
) {
  require(isPow2(fifoDepth), "fifoDepth must be a power of 2")
  // 只支持AXI4-Lite，所以一些AXI4的特性需要禁用
  // require(!axiConfig.useBurst, "UartAxiController only supports AXI4-Lite (no burst)")
  require(!axiConfig.useLock, "UartAxiController does not use lock")
}

class UartAxiController(val config: UartAxiControllerConfig) extends Component {
  val enableLog = false
  val io = new Bundle {
    // AXI4-Lite Slave接口，SpinalHDL会自动处理信号的拆分和命名
    val axi = slave(Axi4(config.axiConfig))

    // 物理串口线
    val txd = out Bool ()
    val rxd = in Bool ()
  }

  // --- 寄存器地址映射 ---
  val ADDR_DATA = 0x00
  val ADDR_STATUS = 0x04

  // --- 实例化底层UART收发模块 (黑盒) ---
  val uartRx = new async_receiver(config.clk_freq, config.uart_baud)
  uartRx.io.RxD := io.rxd

  val uartTx = new async_transmitter(config.clk_freq, config.uart_baud)
  io.txd := Mux(uartTx.io.TxD_busy, uartTx.io.TxD, True)


  // --- 接收路径 (RX Path) ---
  val rxFifo = StreamFifo(dataType = Bits(8 bits), depth = config.fifoDepth)

  // ++++++++++++++++ 双重保险修复方案 ++++++++++++++++
  
  // 第一重保险：用 rxStarted 标志位，确保只在真实起始位后才开始处理数据
  val rxStarted = Reg(Bool()) init(False)
  when(!io.rxd) {
    rxStarted := True
  }
  // 在复位时，将 rxStarted 也清零
  when(ClockDomain.current.isResetActive) {
      rxStarted := False
  }
  
  // 第二重保险：仅在 rxStarted 为 True 后，才相信 data_ready
  val genuine_data_ready = uartRx.io.RxD_data_ready && rxStarted

  // 将UART接收器连接到FIFO的输入端 (使用我们处理过的信号)
  rxFifo.io.push.valid   := genuine_data_ready
  rxFifo.io.push.payload := uartRx.io.RxD_data
  uartRx.io.RxD_clear    := rxFifo.io.push.fire 



  // --- 发送路径 (TX Path) ---
  // 这两个信号将被写状态机控制
  val txData = Reg(Bits(8 bits)) init (0)
  val txStart = Reg(Bool()) init (False)

  uartTx.io.TxD_data := txData
  uartTx.io.TxD_start := txStart

  // --- AXI-Lite 接口逻辑 ---

  // 寄存器用于存储响应信息
  val r_valid = Reg(Bool()) init (False)
  val r_data = Reg(Bits(config.axiConfig.dataWidth bits)) init (0)
  val r_resp = Reg(Bits(2 bits)) init (Axi4.resp.OKAY)
  val r_id = Reg(config.axiConfig.idType) init(0)

  val b_valid = Reg(Bool()) init (False)
  val b_resp = Reg(Bits(2 bits)) init (Axi4.resp.OKAY)
  val b_id = Reg(config.axiConfig.idType) init(0)


  // --- 默认输出值 ---
  // AXI Read Channels
  io.axi.ar.ready := False
  io.axi.r.valid := r_valid
  io.axi.r.payload.data := r_data
  io.axi.r.payload.resp := r_resp
  if (config.axiConfig.useId) io.axi.r.id := r_id
  if (config.axiConfig.useLast) io.axi.r.last := True // Lite事务总是last

  // AXI Write Channels
  io.axi.aw.ready := False
  io.axi.w.ready := False
  io.axi.b.valid := b_valid
  io.axi.b.payload.resp := b_resp
  if(config.axiConfig.useId) io.axi.b.id := b_id

  // --- 主控制逻辑 ---

  // 在R通道握手后复位r_valid
  when(io.axi.r.fire) {
    r_valid := False
  }

  // 在B通道握手后复位b_valid
  when(io.axi.b.fire) {
    b_valid := False
  }

  // 在TX Start脉冲发出后，自动将其拉低
  when(txStart) {
    txStart := False
  }

  // --- 组合逻辑：根据AXI请求地址进行多路选择 ---
  val aw_fire = io.axi.aw.fire
  val ar_fire = io.axi.ar.fire
  val w_fire = io.axi.w.fire

  // 地址解码
  val readAddress = io.axi.ar.payload.addr(log2Up(ADDR_STATUS) downto 2)
  val writeAddress = io.axi.aw.payload.addr(log2Up(ADDR_STATUS) downto 2)

  val isDataAddrRead = readAddress === (ADDR_DATA >> 2)
  val isStatusAddrRead = readAddress === (ADDR_STATUS >> 2)

  val isDataAddrWrite = writeAddress === (ADDR_DATA >> 2)
  val isStatusAddrWrite = writeAddress === (ADDR_STATUS >> 2) // 状态寄存器通常只读

  // 默认让FIFO准备好接收数据
  rxFifo.io.pop.ready := False

  // --- 状态机 (简化版，适用于AXI-Lite) ---
  // AXI-Lite的事务非常简单，有时不需要完整的FSM，但为清晰起见，
  // 我们可以将其视为隐式的状态。

  // 读逻辑 (在一个周期内完成地址握手和数据准备)
  when(!r_valid) { // 仅当不忙于上一次读响应时
    io.axi.ar.ready := True
    when(ar_fire) {
      r_valid := True
      r_resp := Axi4.resp.OKAY
      if(config.axiConfig.useId) r_id := io.axi.ar.id

      when(isDataAddrRead) {
        // 读数据寄存器：从FIFO弹出一个字节
        r_data := rxFifo.io.pop.payload.resize(config.axiConfig.dataWidth) // 零扩展
        rxFifo.io.pop.ready := True // 发出弹出请求
      } elsewhen (isStatusAddrRead) {
        // ********** 这是修复的地方 **********
        // 严格按照官方文档的位定义
        // bit 0: TX_IDLE (串口空闲, 1 = 可发送)
        // bit 1: RX_READY (收到数据, 1 = 有数据)
        
        val status = B(0, config.axiConfig.dataWidth bits)
        
        // bit 0: TX_IDLE. 发送器不忙(TxD_busy=0)时，TX就是IDLE(1)。
        status(0) := !uartTx.io.TxD_busy
        
        // bit 1: RX_READY. FIFO中有数据(pop.valid=1)时，RX就是READY(1)。
        status(1) := rxFifo.io.pop.valid
        
        // (可选) 你可以继续在其他位上提供额外信息，但0和1必须正确。
        // bit 8-15: rx_fifo_occupancy (FIFO中有多少字节)
        status(15 downto 8) := rxFifo.io.occupancy.asBits.resize(8 bits)
        
        r_data := status
      } otherwise {
        // 地址错误
        r_resp := Axi4.resp.SLVERR
        r_data := 0
      }
    }
  }

  // 写逻辑
  val writeReady = !b_valid && !uartTx.io.TxD_busy
  when(!b_valid) { 
    // 只有在准备好时才拉高ready信号
    io.axi.aw.ready := writeReady
    io.axi.w.ready  := writeReady 

    when(aw_fire && w_fire) { // 地址和数据都到达
      b_valid := True
      b_resp  := Axi4.resp.OKAY
      if(config.axiConfig.useId) b_id := io.axi.aw.id

      // 因为我们只在不忙的时候接受事务，所以这里不再需要检查 uartTx.io.TxD_busy
      // isDataAddrWrite的判断仍然是必要的
      when(isDataAddrWrite) {
        txData  := io.axi.w.payload.data(7 downto 0)
        txStart := True
      } elsewhen (isStatusAddrWrite) {
        b_resp := Axi4.resp.SLVERR
      } otherwise {
        b_resp := Axi4.resp.SLVERR
      }
    }
  }


  when(io.axi.ar.valid) {
    assert(io.axi.ar.len === 0, "UartAxiController received a burst read request (ARLEN != 0), which is not supported!")
  }

  when(io.axi.aw.valid) {
    assert(
      io.axi.aw.len === 0,
      "UartAxiController received a burst write request (AWLEN != 0), which is not supported!"
    )
  }

  when(uartRx.io.RxD_data_ready) {
    if(enableLog) report(L"UART_CTRL: async_receiver data ready! Data: 0x${(uartRx.io.RxD_data)}")
  }
  
  when(rxFifo.io.push.fire) {
    if(enableLog) report(L"UART_CTRL: rxFifo PUSH fired! Occupancy is now: ${rxFifo.io.occupancy + 1}")
  }

  when(io.axi.ar.fire) {
    if(enableLog) report(L"UART_CTRL: AXI Read Request (AR) fired! Addr: 0x${(io.axi.ar.addr)}")
  }
  
  when(isDataAddrRead && io.axi.ar.fire) {
      if(enableLog) report(L"UART_CTRL: Reading from DATA register. FIFO valid=${rxFifo.io.pop.valid}")
  }
  
  when(isStatusAddrRead && io.axi.ar.fire) {
      if(enableLog) report(L"UART_CTRL: Reading from STATUS register.")
  }

  when(io.axi.r.fire && r_valid) { // 仅在有效数据被取走时报告
    if(enableLog) report(L"UART_CTRL: AXI Read Response (R) fired! Data: 0x${(io.axi.r.data)}")
  }

    when(io.axi.aw.fire) {
    if(enableLog) report(L"UART_CTRL: AXI Write Address (AW) fired! Addr: 0x${(io.axi.aw.addr)}")
  }

  when(io.axi.w.fire) {
    if(enableLog) report(L"UART_CTRL: AXI Write Data (W) fired! Data: 0x${(io.axi.w.data(7 downto 0))}, Strobe: ${io.axi.w.strb}")
  }

  when(txStart) {
    if(enableLog) report(L"UART_CTRL: txStart pulse generated! Sending 0x${(txData)} to async_transmitter.")
  }
  
  when(io.axi.b.fire && b_valid) {
    if(enableLog) report(L"UART_CTRL: AXI Write Response (B) fired!")
  }
}
