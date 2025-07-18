package parallax

import spinal.core._
import spinal.lib._
import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import spinal.core._
import spinal.lib._
import java.io.File
import java.nio.file.{Files, StandardCopyOption}


class SimpleMemTester extends Component {
  val axiConfig = spinal.lib.bus.amba4.axi.Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 4)
  val io = CoreNSCSCCIo(simDebug = false, injectAxi = false, axiConfig = axiConfig)

  val clockFrequency = 50 MHz
  val halfSecondCycles = (clockFrequency.toBigDecimal / 2).toBigInt

  object TestState extends SpinalEnum {
    val S_WRITE, S_WAIT_AFTER_WRITE, S_READ, S_WAIT_AFTER_READ = newElement()
  }
  val state = Reg(TestState()) init (TestState.S_WRITE)

  val timer = Counter(halfSecondCycles + 1) // 定时器范围稍大一点以确保触发

  val writeAddr = Reg(UInt(20 bits)) init (0)
  val writeData = Reg(Bits(32 bits)) init (0xA0A0A0A0L)
  val ledDisplay = Reg(UInt(16 bits)) init (0)
  io.leds := ledDisplay

  // --- 默认输出 ---
  io.dsram_en := False
  io.dsram_we := False
  io.dsram_re := False
  io.dsram_addr := 0
  io.dsram_din := 0
  io.dsram_wmask := B"0000"
  // 其他未使用端口的默认值...
  io.isram_en := False; io.isram_we := False; io.isram_re := False; io.isram_addr := 0; io.isram_din := 0; io.isram_wmask := B"0000"
  io.uart_ar_valid := False; io.uart_aw_valid := False; io.uart_w_valid := False; io.uart_r_ready := False; io.uart_b_ready := False
  io.uart_ar_bits_id := 0; io.uart_ar_bits_addr := 0; io.uart_ar_bits_len := 0; io.uart_ar_bits_size := 0; io.uart_ar_bits_burst := 0
  io.uart_aw_bits_id := 0; io.uart_aw_bits_addr := 0; io.uart_aw_bits_len := 0; io.uart_aw_bits_size := 0; io.uart_aw_bits_burst := 0
  io.uart_w_bits_data := 0; io.uart_w_bits_strb := 0; io.uart_w_bits_last := False
  io.dpy0 := 0; io.dpy1 := 0

  // --- 状态机逻辑 ---
  timer.increment()

  switch(state) {
    is(TestState.S_WRITE) {
      io.dsram_en := True
      io.dsram_we := True
      io.dsram_addr := writeAddr
      io.dsram_din := writeData
      io.dsram_wmask := B"1111"

      state := TestState.S_WAIT_AFTER_WRITE
      timer.clear()
    }
    is(TestState.S_WAIT_AFTER_WRITE) {
      when(timer.willOverflow) { // 使用 willOverflow 更稳健
        state := TestState.S_READ
        timer.clear()
      }
    }
    is(TestState.S_READ) {
      io.dsram_en := True
      io.dsram_re := True
      io.dsram_addr := writeAddr

      state := TestState.S_WAIT_AFTER_READ
      timer.clear()
    }
    is(TestState.S_WAIT_AFTER_READ) {
      ledDisplay := io.dsram_dout(15 downto 0).asUInt

      when(timer.willOverflow) {
        state := TestState.S_WRITE
        timer.clear()

        // *** 两个修正都在这里 ***
        // 1. 将 Bits 转为 UInt 进行加法运算
        // 2. 将加数 1 显式指定为20位宽
        writeData := (writeData.asUInt + 1).asBits
        writeAddr := writeAddr + U(1, 20 bits)
      }
    }
  }
}

// Verilog生成器对象
object SimpleMemTesterGen extends App {
  val spinalConfig = SpinalConfig(
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
    defaultClockDomainFrequency = FixedFrequency(50 MHz),
    targetDirectory = "soc_tester"
  )
  spinalConfig.generateVerilog(new SimpleMemTester)
  println("SimpleMemTester Verilog Generation DONE")
  println(s"Verilog file is located in: ${new File("soc_tester").getAbsolutePath}")
}
