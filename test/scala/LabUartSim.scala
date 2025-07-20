// test:run test.scala.InteractiveUartSimApp
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite.AxiLite4SlaveFactory
import scala.collection.mutable.ArrayBuffer
import java.io.InputStream
import java.io.PrintStream
import LA32RInstrBuilder._
import spinal.lib.fsm._
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 模拟UART设备，提供AXI4-Lite从接口供CPU交互。
 * 这个模块本身只包含可综合的硬件逻辑。
 * 串行通信的仿真驱动（读写控制台）将在外部的仿真环境中实现。
 */
class LabUartSim(
    axiConfig: Axi4Config, // 注意这里仍然是 Axi4Config，因为CoreNSCSCC提供的是 Axi4 接口
    baseAddress: BigInt, // AXI MMIO的基地址，例如 0xbfd00000L
    dataRegOffset: BigInt = 0x3F8, // UART数据寄存器相对于基地址的偏移
    statusRegOffset: BigInt = 0x3FC, // UART状态寄存器相对于基地址的偏移
    baudRate: Int = 9600, // 波特率，这里假设为 9600
    clockHz: Long = 100000000L // 时钟频率，这里假设为 100MHz
) extends Component { // <-- 注意这里不再需要 baudRate 和 clockHz 参数，因为它们只用于仿真
  // 定义 AXI4 Slave 接口
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig)) // 连接到CPU的AXI Master端口
    val txd = out Bool ()             // CPU发送的串行数据
    val rxd = in Bool ()             // 模拟器发送给CPU的串行数据

    // --- NEW: 用于从仿真环境注入 RX 数据和控制信号的端口 ---
    val simRxData = in Bits(8 bits)
    val simRxDataValid = in Bool()
  }

  // --- UART 内部寄存器模拟 (可综合硬件逻辑) ---
  // Rx Data Register (CPU Read)
  private val uartRxData = Reg(Bits(8 bits)) init(0)
  val uartRxDataValid = Reg(Bool()) init(False) simPublic() // 对应 uart_wrapper 中的 ext_uart_ready
  
  // Tx Data Register (CPU Write)
  val uartTxData = Reg(Bits(8 bits)) init(0)
  val uartTxStart = Reg(Bool()) init(False) // 对应 uart_wrapper 中的 ext_uart_start
  val uartTxBusy = Reg(Bool()) init(False)   // 对应 uart_wrapper 中的 ext_uart_busy

  // Status Register (CPU Read)
  // {6'b0, ext_uart_ready, !ext_uart_busy}
  val uartStatus = Bits(8 bits)
  uartStatus(7 downto 2) := 0
  uartStatus(1) := uartRxDataValid // ext_uart_ready
  uartStatus(0) := !uartTxBusy     // !ext_uart_busy (TX Idle)

  // --- AXI4 Slave 接口信号 ---
  io.axi.ar.ready := True
  val ar_fire = io.axi.ar.fire

  io.axi.aw.ready := True
  val aw_fire = io.axi.aw.fire

  io.axi.w.ready := !uartTxBusy // Ready only if TX is not busy (to avoid blocking on full buffer)
  val w_fire = io.axi.w.fire

  io.axi.r.id    := io.axi.ar.id
  io.axi.r.resp  := B"00" // OKAY
  io.axi.r.last  := True // AXI4-Lite is always single beat
  io.axi.r.valid := False
  io.axi.r.data  := 0 // Default to 0

  io.axi.b.id    := io.axi.aw.id
  io.axi.b.resp  := B"00" // OKAY
  io.axi.b.valid := False


  // --- 状态机定义 ---
  val fsm = new StateMachine {
    val IDLE       = new State with EntryPoint
    val READ_REQ   = new State
    val WRITE_REQ  = new State // Renamed from WRITE_DATA in previous version
    val WRITE_RESP = new State

    val currentReadAddr  = Reg(UInt(axiConfig.addressWidth bits)) init(0)
    val currentReadId    = Reg(UInt(axiConfig.idWidth bits)) init(0)
    val currentWriteAddr = Reg(UInt(axiConfig.addressWidth bits)) init(0)
    val currentWriteId   = Reg(UInt(axiConfig.idWidth bits)) init(0)


    IDLE.whenIsActive {
      io.axi.ar.ready := True
      io.axi.aw.ready := True
      io.axi.w.ready := False // W Channel is only ready when AW is fired and we are waiting for data

      // Reset uartTxStart when going back to IDLE
      uartTxStart := False

      when(ar_fire) {
        currentReadAddr := io.axi.ar.addr
        currentReadId   := io.axi.ar.id
        goto(READ_REQ)
      } elsewhen(aw_fire) {
        currentWriteAddr := io.axi.aw.addr
        currentWriteId   := io.axi.aw.id
        goto(WRITE_REQ)
      }
    }

    READ_REQ.whenIsActive {
      // Prepare R channel and wait for R_READY from master.
      // 使用显式位宽的常量，确保与 currentReadAddr 的位宽匹配
      val statusRegFullAddr = U(baseAddress + statusRegOffset, axiConfig.addressWidth bits)
      val dataRegFullAddr   = U(baseAddress + dataRegOffset, axiConfig.addressWidth bits)
      
      io.axi.r.valid := (currentReadAddr === statusRegFullAddr && !uartTxBusy) || 
                        (currentReadAddr === dataRegFullAddr && uartRxDataValid)

      // Mux 的两个分支必须返回相同类型，并且 resize 到 AXI dataWidth
      io.axi.r.data  := Mux(
                          currentReadAddr === statusRegFullAddr, 
                          uartStatus.asBits.resize(axiConfig.dataWidth), // <-- 明确 resized 到 AXI 数据宽度
                          uartRxData.asBits.resize(axiConfig.dataWidth)  // <-- 明确 resized 到 AXI 数据宽度
                        )

      io.axi.r.id    := currentReadId
      io.axi.r.resp  := B"00" // OKAY
      io.axi.r.last  := True // Single beat AXI4-Lite transaction

      when(io.axi.r.fire) {
            report(L"AXI_READ_FIRE: addr=${(currentReadAddr)}, data=${(io.axi.r.data)}")
        when(currentReadAddr === dataRegFullAddr) {
          uartRxDataValid := False // Clear data valid flag after CPU reads data register
        }
        goto(IDLE)
      }
    }

    WRITE_REQ.whenIsActive { // This state waits for W_VALID for the current AW transaction
      // W Channel is ready to receive data, but also depends on uartTxBusy
      io.axi.w.ready := !uartTxBusy 

      when(w_fire) {
        uartTxData := io.axi.w.data(7 downto 0) // Capture lower 8 bits for UART
        uartTxStart := True // Trigger UART TX hardware
            report(L"AXI_WRITE_FIRE: addr=${(currentWriteAddr)}, data=${(io.axi.w.data(7 downto 0))}")

        // After receiving W data, immediately go to send B response
        // The hardware `uartTxBusy` will manage its own duration.
        goto(WRITE_RESP)
      }
    }

    WRITE_RESP.whenIsActive { // This state sends the B response
      io.axi.b.valid := True
      io.axi.b.id    := currentWriteId
      io.axi.b.resp  := B"00" // OKAY

      when(io.axi.b.fire) {
        goto(IDLE)
      }
    }
  }


  fsm.build()
  val statePrev = RegNext(fsm.stateReg)
  when(statePrev =/= fsm.stateReg) {
    report(L"State transition: ${statePrev} -> ${fsm.stateReg}")
  }

  // --- Make internal signals public for simulation (simPublic) ---
  // These calls are fine within Component, they just add metadata for the simulator
  uartTxData.simPublic()
  uartTxStart.simPublic()
  uartTxBusy.simPublic()
  uartRxData.simPublic()
  uartRxDataValid.simPublic()
  uartStatus.simPublic()
  io.txd.simPublic()
  io.rxd.simPublic()

  val cyclesPerBit = clockHz / baudRate
    // val tx_total_cycles = cyclesPerBit * 10 // Not directly used in this FSM style
  // val tx_bit_cycles = cyclesPerBit // Redundant if using cyclesPerBit directly in counter init

  // TXD FSM
  val txFsm = new StateMachine { // <-- NEW: 使用正确的 StateMachine 定义
    val TX_IDLE  = new State with EntryPoint
    val TX_START = new State
    val TX_DATA  = new State
    val TX_STOP  = new State

    val tx_bit_counter = Reg(UInt(log2Up(cyclesPerBit) bits)) init(0)
    val tx_bit_index = Reg(UInt(3 bits)) init(0) // 0 to 7 for 8 data bits
    val tx_shift_reg = Reg(Bits(8 bits)) init(0)
    val tx_shift_reg_prev = RegNext(tx_shift_reg) // For detecting changes in tx_shift_reg
    when(tx_shift_reg_prev =/= tx_shift_reg) {
      report(L"TX_SHIFT_REG_CHANGED: tx_shift_reg=${(tx_shift_reg)}")
    }

    // Default txd signal (idle high)
    io.txd := True // Default is high

    TX_IDLE.whenIsActive {
      io.txd := True // Ensure idle high
      when(uartTxStart) { // Priority: AXI write requests new transmission
        tx_bit_counter := cyclesPerBit - 1 // Count down to 0 for the first bit
        tx_bit_index := 0
        tx_shift_reg := uartTxData // Load data to be sent
        goto(TX_START)
      }
    }
    TX_START.whenIsActive { // Sending Start Bit (Low)
      io.txd := False
      when(tx_bit_counter === 0) {
        tx_bit_counter := cyclesPerBit - 1
        goto(TX_DATA)
      } .otherwise {
        tx_bit_counter := tx_bit_counter - 1
      }
    }
    TX_DATA.whenIsActive { // Sending Data Bits (LSB first)
      io.txd := tx_shift_reg(tx_bit_index)
      when(tx_bit_counter === 0) {
        tx_bit_counter := cyclesPerBit - 1
        when(tx_bit_index === 7) { // Last data bit sent
          goto(TX_STOP)
        } .otherwise {
          tx_bit_index := tx_bit_index + 1
        }
      } .otherwise {
        tx_bit_counter := tx_bit_counter - 1
      }
    }
    TX_STOP.whenIsActive { // Sending Stop Bit (High)
      io.txd := True
      when(tx_bit_counter === 0) {
        goto(TX_IDLE) // Transmission complete
      } .otherwise {
        tx_bit_counter := tx_bit_counter - 1
      }
    }
  }

  txFsm.build()
  uartTxBusy := (txFsm.isActive(txFsm.TX_START) || txFsm.isActive(txFsm.TX_DATA) || txFsm.isActive(txFsm.TX_STOP))


  // --- Make IO ports public for simulation ---
  io.txd.simPublic()
  io.rxd.simPublic()
  io.simRxData.simPublic()
  io.simRxDataValid.simPublic()

  // Make internal registers public for waveform/debug
  uartRxData.simPublic()
  uartRxDataValid.simPublic()
  uartTxData.simPublic()
  uartTxStart.simPublic()
  uartTxBusy.simPublic()
  uartStatus.simPublic()
  txFsm.stateReg.simPublic()       // Debug TX FSM state

  val prevValid = RegNext(uartRxDataValid)
  when(uartRxDataValid && !prevValid) {
    report(L"UART_RX_DATA_VALID: uartRxDataValid=${uartRxDataValid}, uartRxData=${(uartRxData)}")
  }

  when(io.simRxDataValid && !uartRxDataValid) {
    uartRxData := io.simRxData
    uartRxDataValid := True
    report(L"RX_DATA_LATCHED: Latched new data ${(io.simRxData)} into uartRxData. uartRxDataValid is now TRUE.")
  }
}


object InteractiveUartSimApp extends App {

  // CPU 程序：简单的 UART 回显 (Echo) 程序
  // 定义 UART 寄存器地址和状态位，这应该与你的 uart_wrapper.v 保持一致
  val UART_DATA_REG   = 0xBFD003F8L
  val UART_STATUS_REG = 0xBFD003FCL
  val TX_IDLE_BIT     = 0 
  val RX_READY_BIT    = 1 

  val instructions = ArrayBuffer[BigInt]()

  // 1. 设置 UART 寄存器地址 (仅执行一次，在循环外部)
  instructions += lu12i_w(rd = 16, imm = (UART_DATA_REG >>> 12).toInt)
  instructions += ori(rd = 16, rj = 16, imm = (UART_DATA_REG & 0xFFF).toInt)
  instructions += lu12i_w(rd = 18, imm = (UART_STATUS_REG >>> 12).toInt)
  instructions += ori(rd = 18, rj = 18, imm = (UART_STATUS_REG & 0xFFF).toInt)

  val loop_start_idx = instructions.length

  // 2a. 轮询状态寄存器直到 RX_READY (读取字符)
  instructions += ld_w(rd = 19, rj = 18, offset = 0)
  instructions += andi(rd = 19, rj = 19, imm = (1 << RX_READY_BIT))
  instructions += beq(rj = 19, rd = 0, offset = -12)

  // 2b. 从数据寄存器读取字符到 R17
  instructions += ld_w(rd = 17, rj = 16, offset = 0)

  // 2c. 轮询状态寄存器直到 TX_IDLE (发送字符)
  instructions += ld_w(rd = 19, rj = 18, offset = 0)
  instructions += andi(rd = 19, rj = 19, imm = (1 << TX_IDLE_BIT))
  instructions += beq(rj = 19, rd = 0, offset = -12)

  // 2d. 将 R17 中的字符写入数据寄存器 (发送)
  instructions += st_w(rd = 17, rj = 16, offset = 0)

  // 2e. 跳转回循环开始处 (形成无限回显循环)
  val current_idx = instructions.length
  val offset_bytes = (loop_start_idx - current_idx) * 4
  instructions += bne(rj = 0, rd = 0, offset = offset_bytes)

  LabHelper.dumpBinary(instructions, "bin/uart_echo_interactive.bin")

  // =========================================================================
  // 仿真配置和启动 (所有仿真相关的 fork, sleep, println 都在这里)
  // =========================================================================
  val UART_BAUD_RATE = 9600
  val CPU_CLOCK_HZ = 100000000L // Matches clk_cpu frequency from LabTestBench

  SimConfig.withConfig(SpinalConfig(
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
          defaultClockDomainFrequency = FixedFrequency(100 MHz),
    )).withFstWave.compile(new LabTestBench(instructions)).doSim { dut =>
    val cd = dut.clockDomain.get
    cd.forkStimulus(period = 10 * 1000)

    val bitPeriodNs = 1000000000L / UART_BAUD_RATE
    val cyclesPerBit = (CPU_CLOCK_HZ / UART_BAUD_RATE).toInt

    dut.io.rxd #= true
    dut.io.simRxData #= 0
    dut.io.simRxDataValid #= false

    cd.waitSampling(600)
    // =====================================================================
    // TX (CPU -> txd -> Console) Serial Receiver (打印 CPU 输出)
    // =====================================================================
    val txProcess = fork { 
      val printStream: PrintStream = System.out
      var rxDataBits = new ArrayBuffer[Boolean]()
      var inByte = 0.toByte
      var bitCount = 0
      var inProgress = false

      println(s"[DBG-APP-TX] TX Receiver initialized. txd high.")

      // CRITICAL: Keep this sim process alive indefinitely
      while (true) {
        cd.waitSampling() // Keep this sim process alive and synchronized with clock

        if (!inProgress && !dut.io.txd.toBoolean) {
          inProgress = true
          bitCount = 0
          rxDataBits.clear()
          println(s"[DBG-APP-TX] Detected start bit (txd low).")
          cd.waitSampling(cyclesPerBit / 2) 
        }

        if (inProgress) {
          cd.waitSampling(cyclesPerBit)
          if (bitCount < 8) {
            rxDataBits += dut.io.txd.toBoolean
            bitCount += 1
            println(s"[DBG-APP-TX] Received data bit ${bitCount}: ${dut.io.txd.toBoolean}.")
          } else {
            println(s"[DBG-APP-TX] Checking stop bit. txd: ${dut.io.txd.toBoolean}.")
            if (dut.io.txd.toBoolean) {
              inByte = 0
              for (i <- 0 until 8) {
                if (rxDataBits(i)) {
                  inByte = (inByte | (1 << i)).toByte
                }
              }
              printStream.print(inByte.toChar)
              printStream.println(s"[DBG-APP-TX] CPU sent char: '${inByte.toChar}' (0x${inByte.toHexString}).")
            } else {
              printStream.println(s"[UART Sim TX Error] Stop bit low at ${simTime()}ns. Expected high.")
            }
            inProgress = false
            println(s"[DBG-APP-TX] Transmission cycle complete. Resetting.")
          }
        }
      }
    }

    // =====================================================================
    // RX (Console -> rxd -> CPU) Serial Transmitter (发送终端输入给 CPU)
    // =====================================================================
    val rxQueue = new ConcurrentLinkedQueue[Byte]()
    val inputStream: InputStream = System.in

    // 独立 Java 线程：专门负责从 System.in 读取数据并添加到 rxQueue
    // 这个 SimProcess 监护着 Java 线程，并需要无限期地等待以保持 SimProcess 自身活跃
    val javaReaderThreadProcess = fork {
      val reader = new Thread(() => {
        try {
          var charRead = -1
          println(s"[DBG-APP-RX] Java Reader Thread Started.")
          while ({ charRead = inputStream.read(); charRead != -1 }) {
            rxQueue.add(charRead.toByte)
            println(s"[DBG-APP-RX] Char read from console: ${charRead.toChar} (0x${charRead.toHexString}) - added to queue.")
          }
        } catch {
          case e: Exception => println(s"[UART Sim RX Error] Input stream closed: ${e.getMessage}")
        }
        println(s"[DBG-APP-RX] Java Reader Thread Exited.")
      })
      reader.setDaemon(true)
      reader.start()

      // CRITICAL: Keep this sim process alive indefinitely.
      // This `fork` block must never exit, or its started Java thread might be terminated.
      while(true) {
        cd.waitSampling(1000) // Periodically yield control to the simulator
      }
    }

    // 主要的仿真驱动线程：从 rxQueue 取数据并驱动 DUT 接口
    val mainRxDriverProcess = fork {
      cd.waitSampling(100) 

      // CRITICAL: Keep this sim process alive indefinitely
      while (true) {
        cd.waitSampling() // Synchronize with clock

      if (!rxQueue.isEmpty()) { 
          println(s"[DBG-APP-RX] Queue has data. wait uartRxDataValid==false...")
          cd.waitSamplingWhere(!dut.labUartSim.uartRxDataValid.toBoolean)
          println(s"[DBG-APP-RX] Queue has data. uartRxDataValid is false. Send data to DUT...")
          val byteToSend = rxQueue.poll()
          println(s"[DBG-APP-RX] Processing char to send to DUT: '${byteToSend.toChar}' (0x${byteToSend.toHexString}).")

          // --- 物理层模拟 (bit-banging on rxd) ---
          cd.waitSampling(cyclesPerBit)
          dut.io.rxd #= false
          cd.waitSampling(cyclesPerBit)
          for (i <- 0 until 8) {
            dut.io.rxd #= ((byteToSend >> i) & 1) != 0
            cd.waitSampling(cyclesPerBit)
          }
          dut.io.rxd #= true
          cd.waitSampling(cyclesPerBit)
          println(s"[DBG-APP-RX] Bit-banging finished on rxd for char: '${byteToSend.toChar}'.")

          // --- 注入数据到 LabUartSim 的 IO 端口 ---
          val cpuCyclesPerByte = (CPU_CLOCK_HZ.toDouble / UART_BAUD_RATE) * 10
          cd.waitSampling((cpuCyclesPerByte * 2).toInt) 
          println(s"[DBG-APP-RX] Waited for async_receiver.")
          
          dut.io.simRxData #= byteToSend.toInt
          dut.io.simRxDataValid #= true
          println(s"[DBG-APP-RX] Set simRxData='${byteToSend.toChar}', simRxDataValid=true.")
          cd.waitSampling(1) 
          dut.io.simRxDataValid #= false
          println(s"[DBG-APP-RX] Set simRxDataValid=false.")
        }
      }
    }

    println("\n" + "="*50)
    println("  Starting Interactive UART Echo Session  ")
    println("  Type characters in this console and press Enter.")
    println("  The CPU will echo them back.")
    println("  Press Ctrl+C to stop the simulation.")
    println("="*50 + "\n")

    // CRITICAL: Join all long-running simulation processes to keep the `doSim` block alive.
    // The `SimTimeout(Long.MaxValue)` is still useful as a maximum duration,
    // but the `join` ensures `doSim` doesn't exit prematurely if the forks end for some reason.
    txProcess.join()
    javaReaderThreadProcess.join()
    mainRxDriverProcess.join()
  }
}
