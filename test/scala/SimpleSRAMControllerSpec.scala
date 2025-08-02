// file: test/scala/SimpleSRAMControllerSpec.scala

package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.components.memory._ // 确保能引用到你的SRAMController和配置
import scala.util.Random
import scala.collection.mutable
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.slave

/** Test suite for the SimpleSRAMController.
  * This test focuses on single-beat AXI transactions (len=0), as this is the usage pattern
  * from the SplitGmbToAxi4Bridge. Burst functionality is not tested here.
  */
class SimpleSRAMControllerSpec extends CustomSpinalSimFunSuite {

  // AXI configuration matching the controller and bridge
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
    useResp = true,
    useStrb = true,
    // The following are not strictly needed for single beats, but kept for compatibility
    useBurst = true,
    useLen = true,
    useSize = true
  )

  // SRAM configuration for a 1MB SRAM memory
  val sramConfig = SRAMConfig(
    addressWidth = 20, // 1M words for 32-bit data (4MB)
    useWordAddressing = true,
    dataWidth = 32,
    virtualBaseAddress = 0x80000000L,
    sizeBytes = 4 * 1024 * 1024, // 4MB
    sramByteEnableIsActiveLow = true,
    readWaitCycles = 3, // A realistic wait cycle value
    writeDeassertCycles = 3
  )

  // --- Test Bench Setup ---
  // A generic test bench that wraps our controller.
  // We will simulate the SRAM behavior in the test bench itself.
  class SimpleSRAMControllerTestBench extends Component {
    val useBlackbox = true
    val io = new Bundle {
      val axi = slave(Axi4(axiConfig))
    }
    val controller = new PipelinedSRAMController(axiConfig, sramConfig)
    io.axi <> controller.io.axi

    if (useBlackbox) {
      // --- BlackBox 硬件模型模式 ---
      println("SRAM TestBench is using the Verilog BlackBox model.")
      // 1. 实例化两个 SRAM BlackBox 模型
      val sramModelHi = new SRAMModelBlackbox()
      val sramModelLo = new SRAMModelBlackbox()

      // 2. 为每个 BlackBox 创建一个独立的 16 位 Analog 信号
      val sramDataBusHi = Analog(Bits(16 bits))
      val sramDataBusLo = Analog(Bits(16 bits))

      // 3. 将每个 BlackBox 的 inout 端口连接到其对应的 Analog 信号
      sramModelHi.io.DataIO <> sramDataBusHi
      sramModelLo.io.DataIO <> sramDataBusLo

      // 4. 实现 TriState[Bits(32 bits)] 和两个 Analog 信号之间的三态逻辑

      // a. 读取路径：将两个 16 位的 Analog 总线拼接成 32 位，送给 dut
      //    使用 .asBits 将 Analog 转换为可读的 Bits 类型
      controller.io.ram.data.read := sramDataBusHi.asBits ## sramDataBusLo.asBits

      // b. 写入路径：当 dut 的单个 writeEnable 信号有效时，
      //    将 dut 的 32 位写数据分别驱动到两个 16 位的 Analog 总线上。
      when(controller.io.ram.data.writeEnable) {
        sramDataBusHi := controller.io.ram.data.write(31 downto 16)
        sramDataBusLo := controller.io.ram.data.write(15 downto 0)
      }

      // --- 将 BlackBox 的其他控制信号连接到 dut ---
      val sram_addr = controller.io.ram.addr
      val sram_ce_n = controller.io.ram.ce_n
      val sram_oe_n = controller.io.ram.oe_n
      val sram_we_n = controller.io.ram.we_n

      // 两个SRAM模型共享大部分控制信号
      sramModelHi.io.Address := sram_addr
      sramModelLo.io.Address := sram_addr

      sramModelHi.io.CE_n := sram_ce_n
      sramModelLo.io.CE_n := sram_ce_n

      sramModelHi.io.OE_n := sram_oe_n
      sramModelLo.io.OE_n := sram_oe_n

      sramModelHi.io.WE_n := sram_we_n
      sramModelLo.io.WE_n := sram_we_n

      // 字节使能信号
      sramModelHi.io.UB_n := controller.io.ram.be_n(3)
      sramModelHi.io.LB_n := controller.io.ram.be_n(2)
      sramModelLo.io.UB_n := controller.io.ram.be_n(1)
      sramModelLo.io.LB_n := controller.io.ram.be_n(0)
    }
  }

  // --- Simulation Helper ---
  def withTestBench[T](testCode: (SimpleSRAMControllerTestBench) => T): Unit = {
    val compiled = SimConfig
      .withConfig(
        SpinalConfig(
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
          defaultClockDomainFrequency = FixedFrequency(300 MHz),
        )
      )
      .withWave
      .withIVerilog
      .compile(new SimpleSRAMControllerTestBench)

    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(frequency = 300 MHz)

      // Initialize AXI master signals
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.ar.valid #= false
      dut.io.axi.r.ready #= false
      dut.io.axi.b.ready #= false
      dut.clockDomain.waitSampling(10)

      testCode(dut)
    }
  }

  // =============================================================================
  // --- TEST CASES ---
  // =============================================================================

 test("xBasic write and read") {
    withTestBench { dut =>
      val testAddr = 0x80000100L
      val testData = 0xdeadbeefL
      implicit val cd = dut.clockDomain.get

      // --- Use the corrected helper for the write transaction ---
      AxiTestHelper.write(dut.io.axi, testAddr, testData, 0xf, id = 1)
      println(s"Write to 0x${testAddr.toHexString} complete.")

      dut.clockDomain.waitSampling(5)

      // --- Read Transaction (was already correct) ---
      val readData = AxiTestHelper.read(dut.io.axi, testAddr, id = 2)
      
      assert(readData == testData,
        s"Read data mismatch: Got ${readData.toHexString}, expected ${testData.toHexString}"
      )

      println(s"Read from 0x${testAddr.toHexString} complete and verified.")
    }
  }

  test("Byte enable write and verify") {
    withTestBench { dut =>
      val testAddr = 0x80000200L
      implicit val cd = dut.clockDomain.get

      // 1. Write a full word first
      AxiTestHelper.write(dut.io.axi, testAddr, 0x11223344L, 0xf)
      println("Initial full word written.")

      // 2. Overwrite the middle two bytes (strb = 0110b = 0x6)
      AxiTestHelper.write(dut.io.axi, testAddr, 0xaaccddffL, 0x6) // Only CC and DD are effective
      println("Partial word written with byte enable.")

      // 3. Read back and verify
      val readData = AxiTestHelper.read(dut.io.axi, testAddr)
      val expectedData = 0x11ccdd44L // Byte 3,0 from first write; Byte 2,1 from second
      assert(
        readData == expectedData,
        s"Byte enable test failed: Got ${readData.toHexString}, expected ${expectedData.toHexString}"
      )

      println("Byte enable test verified successfully.")
    }
  }

  test("Random single write/read stress test") {
    withTestBench { dut =>
      val random = new Random(42)
      val baseAddr = 0x80001000L
      val addrRangeWords = 256
      val operations = 100
      val writtenData = mutable.Map[Long, Long]()
      implicit val cd = dut.clockDomain.get

      for (i <- 0 until operations) {
        // Generate a word-aligned address
        val addr = baseAddr + (random.nextInt(addrRangeWords) * 4)

        if (random.nextBoolean() || writtenData.isEmpty) {
          // Perform a write
          val data = random.nextLong() & 0xffffffffL
          AxiTestHelper.write(dut.io.axi, addr, data, 0xf)
          writtenData(addr) = data
          if (i % 20 == 0) println(s"Stress test op ${i}: Wrote 0x${data.toHexString} to 0x${addr.toHexString}")
        } else {
          // Perform a read and verify
          val readAddr = writtenData.keys.toSeq(random.nextInt(writtenData.size))
          val expectedData = writtenData(readAddr)
          val readData = AxiTestHelper.read(dut.io.axi, readAddr)

          assert(
            readData == expectedData,
            s"Stress test fail at op $i: Address 0x${readAddr.toHexString}, Got ${readData.toHexString}, Expected ${expectedData.toHexString}"
          )
          if (i % 20 == 0) println(s"Stress test op ${i}: Read from 0x${readAddr.toHexString} verified.")
        }
      }
      println(s"Stress test completed with $operations random operations.")
    }
  }
  
  startTests
}

/** A simple helper object for driving single-beat AXI transactions in simulation.
  */
object AxiTestHelper {
    def write(axi: Axi4, address: Long, data: Long, strb: Int, id: Int = 0)(implicit cd: ClockDomain): Unit = {

    // 1. Set up payload for both AW and W channels
    axi.aw.payload.addr #= address
    axi.aw.payload.id #= id
    axi.aw.payload.len #= 0 // For single beat transaction

    axi.w.payload.data #= data
    axi.w.payload.strb #= strb
    axi.w.payload.last #= true // For single beat transaction

    // 2. Assert VALID signals for both AW and W channels SIMULTANEOUSLY
    axi.aw.valid #= true
    axi.w.valid #= true

    // 3. Wait for BOTH AWREADY and WREADY to be asserted by the DUT
    // This ensures the StreamJoin in the DUT can fire
    if(cd.waitSamplingWhere(10)(axi.aw.ready.toBoolean && axi.w.ready.toBoolean)) {
      assert(false, "timeout: AXI AW/W handshake failed")
    }

    // 4. Once both are ready (and thus the transaction has fired in the DUT),
    // de-assert VALID signals.
    axi.aw.valid #= false
    axi.w.valid #= false

    // 5. Handle B channel (write response) - this part is correct
    axi.b.ready #= true
    if(cd.waitSamplingWhere(10)(axi.b.valid.toBoolean)) {
      assert(false, "timeout: AXI B response not received")
    }
    if (axi.b.payload.resp.toInt != 0) {
      println(s"AXI Write Error: Received response ${axi.b.payload.resp.toInt} for address 0x${address.toHexString}")
    }
    axi.b.ready #= false
  }

  // read function is mostly fine as AR and R are separate, but let's review for completeness
  def read(axi: Axi4, address: Long, id: Int = 0)(implicit cd: ClockDomain): Long = {
    var readData: Long = -1

    // AR channel
    axi.ar.valid #= true
    axi.ar.payload.addr #= address
    axi.ar.payload.id #= id
    axi.ar.payload.len #= 0 // For single beat transaction

    if(cd.waitSamplingWhere(10)(axi.ar.ready.toBoolean)) {
      assert(false, "timeout: AXI AR handshake failed")
    }
    axi.ar.valid #= false // De-assert ARVALID after handshake
cd.waitSamplingWhere(10)(axi.r.valid.toBoolean)
    // R channel
    axi.r.ready #= true
    if(cd.waitSamplingWhere(10)(axi.r.valid.toBoolean)) {
      assert(false, "timeout: AXI R data not received")
    }
    if (axi.r.payload.resp.toInt != 0) {
      println(s"AXI Read Error: Received response ${axi.r.payload.resp.toInt} for address 0x${address.toHexString}")
    }
    readData = axi.r.payload.data.toLong
    axi.r.ready #= false // De-assert RREADY after receiving data

    readData
  }
}
