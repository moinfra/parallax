// test/scala/SimulatedGeneralMemorySpec.scala
package parallax.test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.lib._

import parallax.components.memory._

class SimulatedGeneralMemoryTestBench(
    val memCfg: SimulatedMemoryConfig,
    val busCfg: GenericMemoryBusConfig
) extends Component {
  case class IO() extends Bundle {
    val bus = slave(GenericMemoryBus(busCfg))
    // Direct memory access for testbench initialization
    val tbWriteEnable = in Bool ()
    val tbWriteAddress = in UInt (busCfg.addressWidth)
    val tbWriteData = in Bits (memCfg.internalDataWidth) // Write internal words directly
  }
  val io = IO()

  val dut = new SimulatedGeneralMemory(memCfg, busCfg)
  dut.io.bus <> io.bus
  dut.io.writeEnable := io.tbWriteEnable
  dut.io.writeAddress := io.tbWriteAddress
  dut.io.writeData := io.tbWriteData

  // Expose internal DUT signals for better debugging if needed
  // e.g., dut.latencyCounterReg.simPublic()
}

class SimulatedGeneralMemorySpec extends CustomSpinalSimFunSuite {

  val busAddrWidth = 32 bits
  val busDataWidth = 32 bits

  def getBusConfig(dataWidth: BitCount = busDataWidth, addrWidth: BitCount = busAddrWidth) =
    GenericMemoryBusConfig(addressWidth = addrWidth, dataWidth = dataWidth)

  // Helper to initialize memory using the TestBench's direct write ports
  def initMemViaTbPorts(
      tbIo: SimulatedGeneralMemoryTestBench#IO, // IO bundle of the TestBench
      address: Int,
      value: BigInt,
      internalDataWidthBits: Int,
      busDataWidthForValueBits: Int, // Width of the 'value' being passed
      clockDomain: ClockDomain
  ): Unit = {
    require(
      busDataWidthForValueBits >= internalDataWidthBits,
      "Value's width must be >= internal data width."
    )
    if (busDataWidthForValueBits > internalDataWidthBits) {
      require(
        busDataWidthForValueBits % internalDataWidthBits == 0,
        "Value's width must be a multiple of internal data width if greater."
      )
    }
    val numInternalChunks = busDataWidthForValueBits / internalDataWidthBits

    tbIo.tbWriteEnable #= true
    for (i <- 0 until numInternalChunks) {
      val slice = (value >> (i * internalDataWidthBits)) & ((BigInt(1) << internalDataWidthBits) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidthBits / 8)
      tbIo.tbWriteAddress #= internalChunkByteAddress
      tbIo.tbWriteData #= slice
      clockDomain.waitSampling()
    }
    tbIo.tbWriteEnable #= false
    clockDomain.waitSampling()
    println(
      s"[TB] Memory initialized via TB ports at 0x${address.toHexString} with 0x${value.toString(16)}"
    ) // Added log
  }

  // --- Test Cases ---

  test("SimGenMem - Single Full Word Read/Write (Internal=BusWidth)") {
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 2
    val testAddr = 0x100
    val testData = BigInt("AABBCCDD", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      initMemViaTbPorts(dut.io, testAddr, 0, commonWidth.value, commonWidth.value, dut.clockDomain) // Clear initially

      // Write
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= testData
        cmd.writeByteEnables #= BigInt("1111", 2) // Enable all bytes
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(responses.nonEmpty), "Timeout on write response")
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Write error signaled")

      // Read
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_READ
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(responses.nonEmpty), "Timeout on read response")
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read error signaled")
      assert(rData == testData, s"Read data mismatch. Expected ${testData.toString(16)}, got ${rData.toString(16)}")
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - Partial Word Write (Byte Enables, Internal=BusWidth)") {
    val commonWidth = 32 bits // Bus and internal are 32-bit
    val memSizeBytes = 1024
    val latency = 2
    val testAddr = 0x200
    val initialData = BigInt("12345678", 16)
    val writeDataPart = BigInt("AB", 16) // Byte to write
    val expectedDataAfterPartialWrite = BigInt("12AB5678", 16) // Byte 2 (0-indexed MSB) updated

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth) // Bus data width is 32 bits
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      initMemViaTbPorts(dut.io, testAddr, initialData, commonWidth.value, commonWidth.value, dut.clockDomain)

      val writeCmdData = (initialData & ~BigInt(0x00ff0000L)) | (writeDataPart << 16)

      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= writeCmdData // Data to pick from
        cmd.writeByteEnables #= BigInt("0100", 2)
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(responses.nonEmpty), "Timeout on partial write response")
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Partial write error signaled")

      // Read back
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_READ
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(responses.nonEmpty), "Timeout on read-back response")
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read-back error signaled")
      assert(
        rData == expectedDataAfterPartialWrite,
        s"Partial write data mismatch. Expected ${expectedDataAfterPartialWrite.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - Partial Word Write (Byte Enables, Internal=Byte, Bus=Word)") {
    val internalWidth = 8 bits
    val busW = 32 bits // Bus is 4 bytes
    val memSizeBytes = 1024
    val latency = 1 // Latency per internal byte access
    val testAddr = 0x300
    val initialDataBytes = Seq(BigInt(0x21), BigInt(0x43), BigInt(0x65), BigInt(0x87)) // 0x87654321
    val initialDataWord = initialDataBytes.zipWithIndex.map { case (b, i) => b << (i * 8) }.reduce(_ | _)
    println(s"[TB] Initial data word: ${initialDataWord.toString(16)}") // 87654321
    val writeDataPartial = BigInt("BA", 16) // Byte to write into byte 2
    val expectedDataAfterPartialWriteBytes = Seq(BigInt(0x21), BigInt(0x43), writeDataPartial, BigInt(0x87))
    val expectedDataAfterPartialWord =
      expectedDataAfterPartialWriteBytes.zipWithIndex.map { case (b, i) => b << (i * 8) }.reduce(_ | _)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = internalWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      // Initialize memory byte by byte
      for (i <- 0 until initialDataBytes.length) {
        initMemViaTbPorts(
          dut.io,
          testAddr + i,
          initialDataBytes(i),
          internalWidth.value,
          internalWidth.value,
          dut.clockDomain
        )
      }

      // Partial Write: Update only byte 2 (0-indexed from LSB) of the 32-bit word.
      val busWritePayload = writeDataPartial << 16

      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= busWritePayload
        cmd.writeByteEnables #= BigInt("0100", 2) // Enable byte 2 (0-indexed LSB)
      }
      // Latency for write: initialLatency for first byte + (numInternalChunksPerBusWord-1)*burstLatency (effectively 1 cycle per byte)
      val expectedWriteCycles = latency + (busW.value / 8)
      assert(
        !dut.clockDomain.waitSamplingWhere(expectedWriteCycles + 20)(responses.nonEmpty),
        "Timeout on partial write response (byte internal)"
      )
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Partial write error signaled (byte internal)")

      // Read back the full 32-bit word
      println(s"[TB] Read back the full 32-bit word")
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_READ
      }
      val expectedReadCycles = latency + (busW.value / 8)
      assert(
        !dut.clockDomain.waitSamplingWhere(expectedReadCycles + 100)(responses.nonEmpty),
        "Timeout on read-back response (byte internal)"
      )
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read-back error signaled (byte internal)")
      assert(
        rData == expectedDataAfterPartialWord,
        s"Partial write data mismatch (byte internal). Expected ${expectedDataAfterPartialWord
            .toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - Out-of-Bounds Access") {
    val internalWidth = 16 bits
    val busW = 32 bits
    val memSizeBytes = 128 // Small memory
    val latency = 1

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = internalWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW) // 32-bit bus
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    // OOB addresses for a 32-bit access (4 bytes)
    val oobAddr_startAtBoundary = memSizeBytes
    val oobAddr_fullyPastBoundary = memSizeBytes + 4
    // Straddle: starts valid, ends OOB. For busWidth=4B, internalWidth=2B.
    // Last valid start for 4B access: memSizeBytes - 4.
    // So, memSizeBytes - 2 would straddle if mem is byte addressable with 16 bit chunks.
    // memSizeBytes = 128. Max internal word index = 128/2 - 1 = 63. Max byte addr = 127.
    // Access at byte 126 (internal word 63) for 4 bytes: bytes 126,127,128,129. 128,129 are OOB.
    val straddleAddr = memSizeBytes - (busW.value / 8) + 1 // e.g. 128 - 4 + 1 = 125. (If bus is 4B)
    // If access starts at 125, it covers 125,126,127,128. 128 OOB.
    // This test currently only checks if start_addr + bus_width > mem_size
    // or if any internal_chunk_addr >= internal_word_count.
    // The current error check might simplify this; let's test based on start address first.

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      val opCycles = latency + (busW.value / internalWidth.value)

      // 1. Read from oobAddr_startAtBoundary
      cmdQueue.enqueue { cmd => cmd.address #= oobAddr_startAtBoundary; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
      assert(!dut.clockDomain.waitSamplingWhere(opCycles + 10)(responses.nonEmpty), "Timeout OOB 1")
      var (_, err) = responses.dequeue(); assert(err, "Expected error for OOB read 1")

      // 2. Write to oobAddr_fullyPastBoundary
      cmdQueue.enqueue { cmd =>
        cmd.address #= oobAddr_fullyPastBoundary; cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE;
        cmd.writeData #= 0xdead; cmd.writeByteEnables #= allBytesEnabledValue
      }
      assert(!dut.clockDomain.waitSamplingWhere(opCycles + 10)(responses.nonEmpty), "Timeout OOB 2")
      var (_, err2) = responses.dequeue(); assert(err2, "Expected error for OOB write 2")

      // 3. Straddling read (if current error logic catches it based on endByteOffsetOfCurrentBusWord)
      // For busW=32b (4B), internal=16b (2B). memSize=128B. Max byte=127.
      // Last valid 4B aligned access is 124 (covers 124,125,126,127).
      // Access at 126: wants 126,127,128,129.
      // Internal chunks for bus addr 126:
      //   part0: internal addr (126/2)=63 (bytes 126,127) -> OK
      //   part1: internal addr (128/2)=64 (bytes 128,129) -> OOB internal word index (max is 63)
      // The busLevelAccessOutOfBounds check (endByteOffsetOfCurrentBusWord >= memConfig.memSize) should catch this.
      val straddleTestAddr =
        memSizeBytes - (busW.value / 8) + (memCfg.internalDataWidthBytes) // Start valid, but end of bus word is OOB. Ex: 128-4+2 = 126
      if (straddleTestAddr < memSizeBytes) { // Ensure test is meaningful
        println(s"Straddle test addr: $straddleTestAddr")
        cmdQueue.enqueue { cmd => cmd.address #= straddleTestAddr; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
        assert(!dut.clockDomain.waitSamplingWhere(opCycles + 10)(responses.nonEmpty), "Timeout OOB Straddle")
        var (_, err3) = responses.dequeue(); assert(err3, s"Expected error for OOB straddle read at $straddleTestAddr")
      }
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - RMW - Multiple Discontinuous Byte Enables (Internal=BusWidth)") {
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x40
    val initialData = BigInt("11223344", 16)
    // Enable byte 0 (LSB) and byte 2 (from LSB)
    // writeByteEnables = B"....0101" (binary) = 5
    // New data for byte 0: 0xAA
    // New data for byte 2: 0xBB
    val writeCmdData = BigInt("00BB00AA", 16) // Provide new bytes in their respective positions
    val expectedData = BigInt("11BB33AA", 16) // Old:11, New:BB, Old:33, New:AA

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      initMemViaTbPorts(dut.io, testAddr, initialData, commonWidth.value, commonWidth.value, dut.clockDomain)

      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= writeCmdData
        cmd.writeByteEnables #= BigInt("0101", 2)
      }
      // Adjust timeout based on your FSM's actual response latency
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty), "Timeout on write response")
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Write error signaled")

      cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty), "Timeout on read response")
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read error signaled")
      assert(
        rData == expectedData,
        s"RMW Multi-byte mismatch. Expected ${expectedData.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - Read/Write with Zero Latency (Internal=BusWidth)") {
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 0 // Zero latency
    val testAddr = 0x50
    val testData = BigInt("CAFED00D", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency, burstLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      initMemViaTbPorts(dut.io, testAddr, 0, commonWidth.value, commonWidth.value, dut.clockDomain)

      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= testData
        cmd.writeByteEnables #= allBytesEnabledValue
      }
      assert(
        !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty),
        "Timeout on write response (0 latency)"
      )
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Write error signaled (0 latency)")

      cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
      assert(
        !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty),
        "Timeout on read response (0 latency)"
      )
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read error signaled (0 latency)")
      assert(
        rData == testData,
        s"0-latency Read mismatch. Expected ${testData.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimGenMem - Back-to-Back Operations (Write then Read)") {
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 2
    val addr1 = 0x60
    val data1 = BigInt("12345678", 16)
    val addr2 = 0x64 // Different address
    val data2 = BigInt("87654321", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      // Write 1
      cmdQueue.enqueue { cmd =>
        cmd.address #= addr1; cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE; cmd.writeData #= data1;
        cmd.writeByteEnables #= allBytesEnabledValue
      }
      // Write 2 (back-to-back if cmdQueue allows, or ASAP)
      cmdQueue.enqueue { cmd =>
        cmd.address #= addr2; cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE; cmd.writeData #= data2;
        cmd.writeByteEnables #= allBytesEnabledValue
      }

      // Wait for both write responses
      for (i <- 1 to 2) {
        assert(
          !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty),
          s"Timeout on write response $i"
        )
        val (_, wErr) = responses.dequeue()
        assert(!wErr, s"Write error signaled $i")
      }

      // Read 1
      cmdQueue.enqueue { cmd => cmd.address #= addr1; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
      // Read 2
      cmdQueue.enqueue { cmd => cmd.address #= addr2; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }

      // Verify Read 1
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty), "Timeout on read 1 response")
      var (rData, rErr) = responses.dequeue()
      assert(!rErr, "Read 1 error signaled")
      assert(rData == data1, s"Read 1 mismatch. Expected ${data1.toString(16)}, got ${rData.toString(16)}")

      // Verify Read 2
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(responses.nonEmpty), "Timeout on read 2 response")
      val (rData2, rErr2) = responses.dequeue()
      assert(!rErr2, "Read 2 error signaled")
      assert(rData2 == data2, s"Read 2 mismatch. Expected ${data2.toString(16)}, got ${rData2.toString(16)}")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test for internal data width smaller than bus width (multi-part transfers)
  // This test will be more complex due to partCounterReg and assemblyBufferReg
  test("SimGenMem - Multi-Part Read/Write (Internal=8b, Bus=32b)") {
    val internalW = 8 bits
    val busW = 32 bits
    val memSizeBytes = 1024
    val latency = 1 // Latency per internal byte access
    val testAddr = 0x70 // Base address for the 32-bit word

    // Data to write (32-bit word, LSB first in sequence for clarity of assembly)
    val byte0 = BigInt("11", 16)
    val byte1 = BigInt("22", 16)
    val byte2 = BigInt("33", 16)
    val byte3 = BigInt("44", 16) // MSB
    val testDataWord = (byte3 << 24) | (byte2 << 16) | (byte1 << 8) | byte0 // 0x44332211

    val memCfg = SimulatedMemoryConfig(internalDataWidth = internalW, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      // Clear memory first
      for (i <- 0 until 4) {
        initMemViaTbPorts(dut.io, testAddr + i, 0, internalW.value, internalW.value, dut.clockDomain)
      }

      // Write the 32-bit word (will be broken into 4 byte writes internally by DUT)
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= testDataWord
        cmd.writeByteEnables #= allBytesEnabledValue // Enable all 4 bytes of the 32-bit word
      }
      // Expected latency: The FSM processes one internal chunk per (initialLatency + 1 or 2) cycles.
      // If initialLatency is per chunk: numChunks * (initialLatency + cycle_for_processing_part)
      // Your FSM resets latencyCounterReg for each part if numChunksPerWord > 1.
      // So, it's numChunksPerWord * (initialLatency + 1 stage for processing)
      // For the test to pass, it's numChunksPerWord * (1 cycle to get out of latency + 1 cycle to process).
      // Total cycles for a multi-part op = numChunksPerWord * (memCfg.initialLatency + 1_cycle_for_processing_that_chunk_rsp_etc)
      // More precisely, if rsp is only for last part: (N-1)*(latency+1) + (latency+2_for_rsp_valid)
      // Let's use a generous timeout for now, FSM for multi-part needs careful cycle counting.
      val numInternalChunks = busW.value / internalW.value
      val estimatedCyclesForOp = numInternalChunks * (latency + 1) + 50 // +5 margin

      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(responses.nonEmpty),
        "Timeout on multi-part write response"
      )
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Multi-part write error signaled")

      // Read back the 32-bit word
      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_READ
      }
      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(responses.nonEmpty),
        "Timeout on multi-part read response"
      )
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Multi-part read error signaled")
      assert(
        rData == testDataWord,
        s"Multi-part Read data mismatch. Expected ${testDataWord.toString(16)}, got ${rData.toString(16)}"
      )

      dut.clockDomain.waitSampling(5)
    }
  }

  // Add a test for multi-part RMW
  test("SimGenMem - Multi-Part RMW (Internal=8b, Bus=32b, Partial BE)") {
    val internalW = 8 bits
    val busW = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x80

    val initialBytes = Seq(BigInt(0x11), BigInt(0x22), BigInt(0x33), BigInt(0x44)) // 0x44332211
    val initialDataWord = (initialBytes(3) << 24) | (initialBytes(2) << 16) | (initialBytes(1) << 8) | initialBytes(0)

    // Write 0xAA to byte 1 (0x22), 0xBB to byte 2 (0x33)
    // BE = "0110" (enable byte 1 and byte 2, LSB-indexed)
    // writeData on bus: MSB_X | BB | AA | LSB_X. E.g., 0x00BB AA00
    val writePayload = (BigInt(0xbb) << 16) | (BigInt(0xaa) << 8)
    val expectedDataWord =
      (initialBytes(3) << 24) | (BigInt(0xbb) << 16) | (BigInt(0xaa) << 8) | initialBytes(0) // 0x44BB AA11

    val memCfg = SimulatedMemoryConfig(internalDataWidth = internalW, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
      dut.io.bus.rsp.ready #= true
      val responses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { p =>
        responses.enqueue((p.readData.toBigInt, p.error.toBoolean))
      }

      for (i <- 0 until 4) {
        initMemViaTbPorts(dut.io, testAddr + i, initialBytes(i), internalW.value, internalW.value, dut.clockDomain)
      }

      cmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.opcode #= GenericMemoryBusOpcode.OP_WRITE
        cmd.writeData #= writePayload
        cmd.writeByteEnables #= BigInt("0110", 2)
      }
      val numInternalChunks = busW.value / internalW.value
      val estimatedCyclesForOp = numInternalChunks * (latency + 1) + 50

      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(responses.nonEmpty),
        "Timeout on multi-part RMW response"
      )
      val (_, wErr) = responses.dequeue()
      assert(!wErr, "Multi-part RMW error signaled")

      cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.opcode #= GenericMemoryBusOpcode.OP_READ }
      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(responses.nonEmpty),
        "Timeout on multi-part RMW read-back"
      )
      val (rData, rErr) = responses.dequeue()
      assert(!rErr, "Multi-part RMW read-back error")
      assert(
        rData == expectedDataWord,
        s"Multi-part RMW mismatch. Expected ${expectedDataWord.toString(16)}, got ${rData.toString(16)}"
      )

      dut.clockDomain.waitSampling(5)
    }
  }

  thatsAll
}
