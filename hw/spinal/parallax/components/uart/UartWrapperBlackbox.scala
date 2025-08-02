package parallax.components.uart

import spinal.core._
import spinal.lib.bus.amba4.axi.Axi4

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
    val clk = in Bool()
    val rst = in Bool()

    // AXI4-Lite Slave Interface (扁平化)
    val io_uart_ar_ready = out Bool()
    val io_uart_r_id = out Bits(8 bits)
    val io_uart_r_resp = out Bits(2 bits)
    val io_uart_r_data = out Bits(32 bits)
    val io_uart_r_last = out Bool()
    val io_uart_r_valid = out Bool()
    val io_uart_aw_ready = out Bool()
    val io_uart_w_ready = out Bool()
    val io_uart_b_id = out Bits(8 bits)
    val io_uart_b_resp = out Bits(2 bits)
    val io_uart_b_valid = out Bool()

    val io_uart_ar_id = in Bits(8 bits)
    val io_uart_ar_addr = in Bits(32 bits)
    val io_uart_ar_len = in Bits(8 bits)
    val io_uart_ar_size = in Bits(3 bits)
    val io_uart_ar_burst = in Bits(2 bits)
    val io_uart_ar_valid = in Bool()
    val io_uart_r_ready = in Bool()
    val io_uart_aw_id = in Bits(8 bits)
    val io_uart_aw_addr = in Bits(32 bits)
    val io_uart_aw_len = in Bits(8 bits)
    val io_uart_aw_size = in Bits(3 bits)
    val io_uart_aw_burst = in Bits(2 bits)
    val io_uart_aw_valid = in Bool()
    val io_uart_w_data = in Bits(32 bits)
    val io_uart_w_strb = in Bits(4 bits)
    val io_uart_w_last = in Bool()
    val io_uart_w_valid = in Bool()
    val io_uart_b_ready = in Bool()

    // 物理串口线
    val txd = out Bool()
    val rxd = in Bool()
  }

  // 告诉SpinalHDL不要给IO信号添加 "io_" 前缀
  noIoPrefix()

  // 如果你的时钟域名不是 "clk"，需要手动映射
  mapCurrentClockDomain(clock = io.clk, reset = io.rst)
}
