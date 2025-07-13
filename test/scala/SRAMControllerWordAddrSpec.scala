// testOnly test.scala.SRAMControllerWordAddrSpec
// file: test/scala/SRAMControllerWordAddrSpec.scala

package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.components.memory._
import scala.util.Random
import scala.collection.mutable

/**
 * Test suite specifically for the SRAMController's Word Addressing mode.
 * In this mode, the controller converts AXI byte addresses to SRAM word addresses.
 * For a 32-bit data bus (4 bytes/word), this means AXI address is divided by 4.
 * This mode requires word-aligned AXI accesses.
 */
class SRAMControllerWordAddrSpec extends CustomSpinalSimFunSuite {

  // AXI configuration remains the same
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  /**
   * Helper to create a SRAMConfig for word-addressing mode.
   * Example: 4MB SRAM, 32-bit data => 1M words => 20 address lines.
   */
  def createWordAddrRamConfig(readWaitCycles: Int = 1, sramSize: BigInt = 4 * 1024 * 1024, enableLog: Boolean = false): SRAMConfig = {
    val bytesPerWord = axiConfig.dataWidth / 8
    val wordCount = sramSize / bytesPerWord
    val addressWidth = log2Up(wordCount)

    SRAMConfig(
      addressWidth = addressWidth,
      dataWidth = axiConfig.dataWidth,
      virtualBaseAddress = 0x80000000L,
      sizeBytes = sramSize,
      readWaitCycles = readWaitCycles,
      useWordAddressing = true, // Enable the mode under test
      enableLog = enableLog // Keep logs clean unless debugging
    )
  }

  // The withTestBench helper is identical to the one in the other spec file
  def withTestBench[T](ramConfig: SRAMConfig)(testCode: (ExtSRAMTestBench, AxiMasterHelper, ClockDomain) => T) = {
    val compiled = SimConfig.withWave.compile(new ExtSRAMTestBench(axiConfig, ramConfig))
    compiled.doSim { dut =>
      val clockDomain = dut.clockDomain
      clockDomain.forkStimulus(period = 10)

      val axiMaster = new AxiMasterHelper(dut.io.axi, clockDomain)
      axiMaster.initialize()

      clockDomain.waitSampling(5)

      try {
        testCode(dut, axiMaster, clockDomain)
      } finally {
        axiMaster.cleanup()
      }
    }
  }

  test("WordAddr - Basic write and read with address translation") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      // AXI address 0x80000100 -> offset 0x100 -> word address 0x40
      val testAddr = 0x80000100L
      val testData = 0xdeadbeefL

      val writeTx = axiMaster.writeWord(testAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 0, "Write OKAY")

      val readTx = axiMaster.readWord(testAddr)
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)

      assert(readResp == 0, "Read OKAY")
      assert(readData.head == testData, f"Data mismatch: 0x${readData.head}%x != 0x$testData%x")
    }
  }

  test("WordAddr - Burst write and read, check address increment") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      // AXI addresses: 0x80000200, 0x80000204, 0x80000208, 0x8000020C
      // SRAM word addresses should be: 0x80, 0x81, 0x82, 0x83
      val baseAddr = 0x80000200L
      val testData = Seq(0x1111, 0x2222, 0x3333, 0x4444).map(BigInt(_))
      val strb = Seq.fill(testData.length)(0xf)

      val writeTx = axiMaster.writeBurst(baseAddr, testData, strb)
      assert(axiMaster.waitWriteResponse(writeTx) == 0, "Burst write OKAY")

      val readTx = axiMaster.readBurst(baseAddr, testData.length)
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)

      assert(readResp == 0, "Burst read OKAY")
      assert(readData == testData, "Burst data must match")
    }
  }

  test("WordAddr - Word alignment error") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      // Address is not a multiple of 4, should be rejected by the controller
      val unalignedAddr = 0x80000001L 
      val testData = 0xbadaddL

      val writeTx = axiMaster.writeWord(unalignedAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 2, "Write to unaligned address should return SLVERR")

      val readTx = axiMaster.readWord(unalignedAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 2, "Read from unaligned address should return SLVERR")
    }
  }

  test("WordAddr - Address out of bounds error") {
    // 4MB SRAM, size = 0x400000.
    // Valid AXI address range: 0x80000000 to 0x803FFFFF
    val sramSize = 4 * 1024 * 1024
    withTestBench(createWordAddrRamConfig(sramSize = sramSize)) { (dut, axiMaster, _) =>
      // This is the first byte address outside the 4MB range
      val invalidAddr = 0x80000000L + sramSize 
      val testData = 0x12345678L

      val writeTx = axiMaster.writeWord(invalidAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 2, "Write to out-of-bounds address should be SLVERR")

      val readTx = axiMaster.readWord(invalidAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 2, "Read from out-of-bounds address should be SLVERR")
    }
  }
  
  test("WordAddr - Stress test with random word-aligned operations") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      val random = new Random(42)
      val baseAddr = 0x80001000L
      val maxWords = 256
      val operations = 50

      val writtenData = mutable.Map[Long, Long]()

      for (i <- 0 until operations) {
        // Ensure generated address is always word-aligned
        val addr = baseAddr + (random.nextInt(maxWords) * 4) 

        if (random.nextBoolean() || writtenData.isEmpty) {
          // Write
          val data = random.nextLong() & 0xffffffffL
          val writeTx = axiMaster.writeWord(addr, data)
          assert(axiMaster.waitWriteResponse(writeTx) == 0, f"Random write to 0x$addr%x should succeed (op $i)")
          writtenData(addr) = data
        } else {
          // Read from a previously written address
          val readAddr = writtenData.keys.toSeq(random.nextInt(writtenData.size))
          val expectedData = writtenData(readAddr)

          val readTx = axiMaster.readWord(readAddr)
          val (readData, readResp) = axiMaster.waitReadResponse(readTx)
          
          assert(readResp == 0, f"Random read from 0x$readAddr%x should succeed (op $i)")
          assert(
            readData.head == expectedData,
            f"Data mismatch at 0x$readAddr%x: expected 0x$expectedData%x, got 0x${readData.head}%x"
          )
        }
      }
      println(s"WordAddr Stress test completed: $operations operations, ${writtenData.size} unique addresses written")
    }
  }

  test("WordAddr - Byte enable functionality remains correct") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      val testAddr = 0x80000200L

      val fullData = 0x12345678L
      axiMaster.waitWriteResponse(axiMaster.writeWord(testAddr, fullData, 0xf))

      // Update only the upper two bytes (strb = 1100b = 0xc)
      val partialData = 0xabcdef00L
      axiMaster.waitWriteResponse(axiMaster.writeWord(testAddr, partialData, 0xc))

      val (readData, _) = axiMaster.waitReadResponse(axiMaster.readWord(testAddr))

      val expectedData = (partialData & 0xffff0000L) | (fullData & 0x0000ffffL)
      assert(
        readData.head == expectedData,
        f"Byte enable result incorrect: expected 0x$expectedData%x, got 0x${readData.head}%x"
      )
    }
  }


  thatsAll
}
