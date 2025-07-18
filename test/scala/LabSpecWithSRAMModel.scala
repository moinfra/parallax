// testOnly test.scala.LabSpecWithSRAMModel
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4
import parallax.CoreNSCSCC
import parallax.components.memory.{SRAMModelBlackbox}
import parallax.issue.CommitStats
import _root_.test.scala.LA32RInstrBuilder._
import scala.collection.mutable.ArrayBuffer
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4Config
import java.io.BufferedOutputStream
import java.io.FileOutputStream

/**
 * Testbench for LabSpec using SRAMModelBlackbox for realistic timing.
 * This bench wraps the CoreNSCSCC DUT, connects it to SRAM blackbox models,
 * and uses a simulation-only AXI port to inject instructions at runtime.
 */
class LabTestBenchWithSRAMModel extends Component {

  val io = new Bundle {
    val commitStats = out(CommitStats())
    // +++ 1. 定义仿真专用IO，以便在测试中驱动 +++
    val fetchDisable = in(Bool())
    val axiInjector = slave (Axi4(Axi4Config(
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
      )))
  }
  val dut = new CoreNSCSCC(simDebug = true, injectAxi = true)
  io.axiInjector.simPublic()
  dut.io.axiInjectorMaster <> io.axiInjector
  // Instantiate the DUT in simulation mode
  io.commitStats := dut.io.commitStats
  io.commitStats.simPublic()

  // +++ 2. 将Testbench的IO连接到DUT的仿真专用IO +++
  dut.io.fetchDisable := io.fetchDisable

  // --- Instruction SRAM (iSram) connection using two 16-bit Blackboxes ---
  val isram_hi = new SRAMModelBlackbox()
  val isram_lo = new SRAMModelBlackbox()
  val isram_be_n = ~dut.io.isram_wmask
  val isramDataBusHi = Analog(Bits(16 bits))
  val isramDataBusLo = Analog(Bits(16 bits))
  isram_hi.io.DataIO <> isramDataBusHi
  isram_lo.io.DataIO <> isramDataBusLo
  // write: sram <- dut
  when(dut.io.isram_we) {
    isramDataBusHi := dut.io.isram_din(31 downto 16)
    isramDataBusLo := dut.io.isram_din(15 downto 0)
  }
  // read: dut <- sram
  dut.io.isram_dout.assignDontCare()
  when(!dut.io.isram_we) {
    dut.io.isram_dout(31 downto 16) := isramDataBusHi
    dut.io.isram_dout(15 downto 0) := isramDataBusLo
  }
  isram_hi.io.Address := dut.io.isram_addr
  isram_lo.io.Address := dut.io.isram_addr
  isram_hi.io.CE_n := !dut.io.isram_en
  isram_lo.io.CE_n := !dut.io.isram_en
  isram_hi.io.OE_n := !dut.io.isram_re
  isram_lo.io.OE_n := !dut.io.isram_re
  isram_hi.io.WE_n := !dut.io.isram_we
  isram_lo.io.WE_n := !dut.io.isram_we
  isram_hi.io.UB_n := isram_be_n(3)
  isram_hi.io.LB_n := isram_be_n(2)
  isram_lo.io.UB_n := isram_be_n(1)
  isram_lo.io.LB_n := isram_be_n(0)

  // --- Data SRAM (dSram) connection using two 16-bit Blackboxes ---
  val dsram_hi = new SRAMModelBlackbox()
  val dsram_lo = new SRAMModelBlackbox()
  val dsram_be_n = ~dut.io.dsram_wmask

  val dsramDataBusHi = Analog(Bits(16 bits))
  val dsramDataBusLo = Analog(Bits(16 bits))
  dsram_hi.io.DataIO <> dsramDataBusHi
  dsram_lo.io.DataIO <> dsramDataBusLo
  
  // write: sram <- dut
  when(dut.io.dsram_we) {
    dsramDataBusHi := dut.io.dsram_din(31 downto 16)
    dsramDataBusLo := dut.io.dsram_din(15 downto 0)
  }
  // read: dut <- sram
  dut.io.dsram_dout.assignDontCare()
  when(!dut.io.dsram_we) {
    dut.io.dsram_dout(31 downto 16) := dsramDataBusHi
    dut.io.dsram_dout(15 downto 0) := dsramDataBusLo
  }
  dsram_hi.io.Address := dut.io.dsram_addr
  dsram_lo.io.Address := dut.io.dsram_addr
  dsram_hi.io.CE_n := !dut.io.dsram_en
  dsram_lo.io.CE_n := !dut.io.dsram_en
  dsram_hi.io.OE_n := !dut.io.dsram_re
  dsram_lo.io.OE_n := !dut.io.dsram_re
  dsram_hi.io.WE_n := !dut.io.dsram_we
  dsram_lo.io.WE_n := !dut.io.dsram_we
  dsram_hi.io.UB_n := dsram_be_n(3)
  dsram_hi.io.LB_n := dsram_be_n(2)
  dsram_lo.io.UB_n := dsram_be_n(1)
  dsram_lo.io.LB_n := dsram_be_n(0)

  // Tie off UART signals
  dut.io.uart_ar_ready := True
  dut.io.uart_r_valid := False
  dut.io.uart_aw_ready := True
  dut.io.uart_w_ready := True
  dut.io.uart_b_valid := False
  dut.io.uart_r_bits_id.assignDontCare()
  dut.io.uart_r_bits_resp.assignDontCare()
  dut.io.uart_r_bits_data.assignDontCare()
  dut.io.uart_r_bits_last.assignDontCare()
  dut.io.uart_b_bits_id.assignDontCare()
  dut.io.uart_b_bits_resp.assignDontCare()

  // report(L"dut.io.isram_we=${dut.io.isram_we}, dut.io.isram_en=${dut.io.isram_en}\n")
}

class LabSpecWithSRAMModel extends CustomSpinalSimFunSuite {

  // Helper function to manually drive an AXI4 write transaction
  def axiWrite(axi: Axi4, address: BigInt, data: BigInt, strb: BigInt = 0xF)(implicit cd: ClockDomain): Unit = {
      axi.aw.valid #= true
      axi.aw.addr #= address
      axi.aw.id.assignDontCare()
      axi.aw.len #= 0
      axi.aw.size #= 2
      axi.aw.burst #= 1
      cd.waitSamplingWhere(axi.aw.ready.toBoolean)
      axi.aw.valid #= false
      axi.w.valid #= true
      axi.w.data #= data
      axi.w.strb #= strb
      axi.w.last #= true
      cd.waitSamplingWhere(axi.w.ready.toBoolean)
      axi.w.valid #= false
      axi.b.ready #= true
      cd.waitSamplingWhere(axi.b.valid.toBoolean)
      axi.b.ready #= false
      cd.waitSampling()
  }

    def axiWriteAndVerify(axi: Axi4, address: BigInt, data: BigInt, strb: BigInt = 0xF)(implicit cd: ClockDomain): Unit = {
      axiWrite(axi, address, data, strb)
      cd.waitSampling()
      val readData = axiRead(axi, address)
      assert(readData == data, f"AXI4 write/read mismatch at address 0x$address%x! Expected 0x$data%x, but got 0x$readData%x")
      println(s"AXI4 write/read verification passed, got ${readData.toString(16)} at ${address.toString(16)}}")
    }

  // Helper function to manually drive an AXI4 read transaction
  def axiRead(axi: Axi4, address: BigInt)(implicit cd: ClockDomain): BigInt = {
      axi.ar.valid #= true
      axi.ar.addr #= address
      axi.ar.id.assignDontCare()
      axi.ar.len #= 0
      axi.ar.size #= 2
      axi.ar.burst #= 1
      cd.waitSamplingWhere(axi.ar.ready.toBoolean)
      axi.ar.valid #= false
      axi.r.ready #= true
      cd.waitSamplingWhere(axi.r.valid.toBoolean)
      val readData = axi.r.data.toBigInt
      axi.r.ready #= false
      readData
  }
  /**
   * Dumps a region of memory to a file and prints it to the console via AXI.
   */
  def ramdump(axi: Axi4, vaddr: BigInt, size: Int, filename: String)(implicit cd: ClockDomain): Unit = {
      println(f"--- Starting RAM dump: vaddr=0x$vaddr%x, size=$size bytes, file='$filename' ---")
      val byteBuffer = new ArrayBuffer[Byte]()
      for (i <- 0 until size by 4) {
          val addr = vaddr + i
          val word = axiRead(axi, addr)
          byteBuffer += ((word >> 0) & 0xff).toByte; byteBuffer += ((word >> 8) & 0xff).toByte
          byteBuffer += ((word >> 16) & 0xff).toByte; byteBuffer += ((word >> 24) & 0xff).toByte
      }
      val data = byteBuffer.take(size).toArray
      val fos = new BufferedOutputStream(new FileOutputStream(filename)); try { fos.write(data) } finally { fos.close() }
      println(s"Wrote $size bytes to $filename")
      println("--- Hexdump ---")
      for (i <- data.indices by 16) {
          val chunk = data.slice(i, i + 16)
          val addressPart = f"${vaddr + i}%08x:"
          val hexPart = chunk.map(b => f"${b & 0xff}%02x").padTo(16, "  ").mkString(" ")
          val asciiPart = chunk.map { b => val c = b.toChar; if (c.isControl || c > 126) '.' else c }.mkString
          println(s"$addressPart $hexPart |$asciiPart|")
      }
      println("--- RAM dump finished ---")
  }

  test("Fibonacci Test with SRAMModel") {
    val instructions = ArrayBuffer[BigInt]()
    // $t0=r12, $t1=r13, $t2=r14, $t3=r15, $a0=r4, $a1=r5
    instructions += addi_w(rd = 12, rj = 0, imm = 1)
    instructions += addi_w(rd = 13, rj = 0, imm = 1)
    instructions += lu12i_w(rd = 4, imm = 0x80400)
    instructions += addi_w(rd = 5, rj = 4, imm = 0x100)
    instructions += add_w(rd = 14, rj = 12, rk = 13)
    instructions += addi_w(rd = 12, rj = 13, imm = 0)
    instructions += addi_w(rd = 13, rj = 14, imm = 0)
    instructions += st_w(rd = 14, rj = 4, offset = 0)
    instructions += ld_w(rd = 15, rj = 4, offset = 0)
    instructions += bne(rj = 14, rd = 15, offset = 12)
    instructions += addi_w(rd = 4, rj = 4, imm = 4)
    instructions += bne(rj = 4, rd = 5, offset = -28)
    instructions += bne(rj = 4, rd = 0, offset = 0)

    LabHelper.dumpBinary(instructions, "bin/LabSpec_SRAMModel.bin")

    val compiled = SimConfig.withConfig(SpinalConfig(
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
          defaultClockDomainFrequency = FixedFrequency(162 MHz),
    )).withIVerilog.withFstWave.compile(new LabTestBenchWithSRAMModel)

    compiled.doSim { dut =>
      implicit val cd = dut.clockDomain.get
      cd.forkStimulus(period = 10 * 1000)
      SimTimeout(300000 * 1000)

      // --- Phase 1: Inject Instructions ---
      println("--- Phase 1: Starting instruction injection ---")
      dut.io.fetchDisable #= true // Drive the TB's IO
      cd.assertReset()
      cd.waitSampling(10)

      // Initialize AXI signals to default values
      dut.io.axiInjector.aw.valid #= false
      dut.io.axiInjector.w.valid #= false
      dut.io.axiInjector.ar.valid #= false
      dut.io.axiInjector.r.ready #= false
      dut.io.axiInjector.b.ready #= false
      
      for ((instr, i) <- instructions.zipWithIndex) {
        val address = BigInt("80000000", 16) + i * 4
        println(f"Injecting instruction 0x$instr%x at address 0x$address%x")
        axiWriteAndVerify(dut.io.axiInjector, address, instr) // Drive the TB's IO
        ramdump(dut.io.axiInjector, BigInt("80000000", 16), instructions.length * 4, s"bin/LabSpec_SRAMModel_Fibonacci.instr.${i}.bin")
      }
      cd.waitSampling(10)
      ramdump(dut.io.axiInjector, BigInt("80000000", 16), instructions.length * 4, "bin/LabSpec_SRAMModel_Fibonacci.instr.bin")
      println("--- Phase 1: Instruction injection complete ---")

      // --- Phase 2: Reset CPU and Start Execution ---
      println("--- Phase 2: Resetting CPU and starting execution ---")
      dut.io.fetchDisable #= false // Drive the TB's IO
      cd.deassertReset()
      cd.waitSampling(10)

      // --- Wait for program to finish ---
      val numInitInstructions = 4
      val numLoopInstructions = 8
      val numHaltInstructions = 1
      val numIterations = 0x100 / 4
      val expectedTotalCommitted = numInitInstructions + (numIterations * numLoopInstructions) + numHaltInstructions
      
      var currentCommitted = 0
      while (currentCommitted < expectedTotalCommitted) {
        cd.waitSampling()
        currentCommitted = dut.io.commitStats.totalCommitted.toBigInt.toInt
      }
      cd.waitSampling(100)
      println(s"--- Execution finished: Committed $currentCommitted instructions ---")
      ramdump(dut.io.axiInjector, BigInt("80400000", 16), instructions.length * 4, "bin/LabSpec_SRAMModel_Fibonacci.data.bin")

      // --- Phase 3: Verify Results ---
      println("--- Phase 3: Starting verification ---")
      var fib_prev = BigInt(1)
      var fib_curr = BigInt(1)
      val MASK_32_BITS = (BigInt(1) << 32) - 1

      for (i <- 0 until numIterations) {
        val byteAddr = BigInt("80400000", 16) + i * 4
        val next_fib_untruncated = fib_prev + fib_curr
        val expected_fib = next_fib_untruncated & MASK_32_BITS

        val actual_val = axiRead(dut.io.axiInjector, byteAddr) // Drive the TB's IO
        
        println(f"Checking dSram[0x${(byteAddr - 0x80400000L)}]: Expected=0x$expected_fib%x, Got=0x$actual_val%x")
        assert(
          actual_val == expected_fib,
          f"Fibonacci sequence mismatch at index $i! Expected 0x$expected_fib%x, but got 0x$actual_val%x"
        )
        fib_prev = fib_curr
        fib_curr = expected_fib
      }

      println("--- Fibonacci Test with SRAMModel Passed ---")
    }
  }
  thatsAll()
}
