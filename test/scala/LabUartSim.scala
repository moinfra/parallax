// filename: test/scala/InteractiveUartSimApp.scala
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

/** A simple application to run an INTERACTIVE simulation session with the CPU.
  *
  * This app boots the CPU with a specified kernel (e.g., an echo program or the full monitor)
  * and then attaches an interactive UART driver. This allows you to type characters in the
  * console and see the CPU's response in real-time (simulation time).
  *
  * It leverages the standard `LabTestBench` and the `AutomatedUartDriver` in interactive mode.
  */
object InteractiveUartSimApp extends App {

  // --- Step 1: Choose the program to load into the CPU ---

  // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
  val instructions = InteractHelper.load_kernel_bin()
  // <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
  assert(instructions.nonEmpty, s"No instructions read from path. Test cannot proceed.")

  // --- Step 2: Configure and compile the simulation ---

  val spinalConfig = SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  )

  val compiled = SimConfig
    .withConfig(spinalConfig)
    .withFstWave
    .addSimulatorFlag("+define+SIMULATION") 
    .compile(new LabTestBench(iDataWords = instructions))

  // --- Step 3: Run the simulation with the driver in interactive mode ---

  compiled.doSim { dut =>
    val cd = dut.clockDomain.get
    cd.forkStimulus(frequency = 100 MHz)

    println("--- Starting Interactive Simulation Session ---")
    println(s"--- CPU booted with program of size ${instructions.length} words. ---")

    // Instantiate and start the UART driver in INTERACTIVE mode.
    // This will handle reading from your console and printing the DUT's output.
    val uartDriver = new AutomatedUartDriver(dut.io.txd, dut.io.rxd, cd, interactive = true)
    uartDriver.start()

    // Keep the simulation running for a very long time to allow for user interaction.
    // The simulation will effectively run until you manually stop it (e.g., with Ctrl+C).
    cd.waitSampling(10000000) // Run for 10 million cycles, should be enough for interaction.

    println("\n--- Simulation time limit reached. Stopping. ---")
  }
}
object InteractHelper {
  def program_infinite_loop(): Seq[BigInt] = {
      val instructions = new ArrayBuffer[BigInt]()
      // b offset=0. The offset is in bytes.
      instructions += b(offset = 0) 
      return instructions.toSeq
  }
  def program_echo(): Seq[BigInt] = {
    val instructions = new ArrayBuffer[BigInt]()
    val TX_IDLE_BIT = 0
    val RX_READY_BIT = 1
    val UART_DATA_REG = 0xbfd003f8L
    val UART_STATUS_REG = 0xbfd003fcL
    
    // 1. 设置地址 (不变)
    instructions += lu12i_w(rd = 16, imm = (UART_DATA_REG >>> 12).toInt)
    instructions += ori(rd = 16, rj = 16, imm = (UART_DATA_REG & 0xfff).toInt)
    instructions += lu12i_w(rd = 18, imm = (UART_STATUS_REG >>> 12).toInt)
    instructions += ori(rd = 18, rj = 18, imm = (UART_STATUS_REG & 0xfff).toInt)

    val loop_start_idx = instructions.length

    // 2a. 轮询状态 (使用 ld.b)
    instructions += ld_b(rd = 19, rj = 18, offset = 0) // <--- 修改点
    instructions += andi(rd = 19, rj = 19, imm = (1 << RX_READY_BIT))
    instructions += beq(rj = 19, rd = 0, offset = -8)

    // 2b. 读取数据 (使用 ld.b)
    instructions += ld_b(rd = 17, rj = 16, offset = 0) // <--- 修改点

    // 2c. 轮询状态 (使用 ld.b)
    instructions += ld_b(rd = 19, rj = 18, offset = 0) // <--- 修改点
    instructions += andi(rd = 19, rj = 19, imm = (1 << TX_IDLE_BIT))
    instructions += beq(rj = 19, rd = 0, offset = -8)

    // 2d. 写入数据 (使用 st.b)
    instructions += st_b(rd = 17, rj = 16, offset = 0) // <--- 修改点

    // 2e. 跳转 (不变)
    val current_idx = instructions.length
    val offset_bytes = (loop_start_idx - current_idx) * 4
    instructions += beq(rj = 0, rd = 0, offset = offset_bytes)

    LabHelper.dumpBinary(instructions, "bin/uart_echo_interactive.bin")
    return instructions.toSeq
}

  def load_kernel_bin(): Seq[BigInt] = {
    val instructions = LabHelper.readBinary("bin/kernel.bin")
    return instructions
  }
}
