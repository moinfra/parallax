// test/scala/SimulatedSplitGeneralMemorySpec.scala
package parallax.test.scala // Assuming same package, or adjust as needed

import org.scalatest.funsuite.AnyFunSuite // Keep, though CustomSpinalSimFunSuite is used
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.lib._

// Import the new DUT and its configurations
import parallax.components.memory._


class SimulatedSplitGeneralMemoryTestBench( // Renamed TestBench
    val memCfg: SimulatedMemoryConfig,
    val busCfg: GenericMemoryBusConfig // Using GenericMemoryBusConfig
) extends Component {
  case class IO() extends Bundle {
    val bus = slave(SplitGenericMemoryBus(busCfg)) // Using SplitGenericMemoryBus
    // Direct memory access for testbench initialization
    val tbWriteEnable = in Bool ()
    val tbWriteAddress = in UInt (busCfg.addressWidth) // addressWidth from GenericMemoryBusConfig
    val tbWriteData = in Bits (memCfg.internalDataWidth) // Write internal words directly
  }
  val io = IO()

  // Instantiate the new DUT
  val dut = new SimulatedSplitGeneralMemory(memCfg, busCfg) // Disabled log for cleaner test output
  dut.io.bus <> io.bus
  dut.io.writeEnable := io.tbWriteEnable
  dut.io.writeAddress := io.tbWriteAddress
  dut.io.writeData := io.tbWriteData

  // Expose internal DUT signals for better debugging if needed
  // e.g., dut.latencyCounterReg.simPublic()
}

class SimulatedSplitGeneralMemorySpec extends CustomSpinalSimFunSuite { // Renamed Spec class

  val busAddrWidth = 32 bits
  val busDataWidth = 32 bits
  // For GenericMemoryBusConfig, we'll default useId to false and idWidth to a minimal value
  // to keep test changes minimal, as per instruction.
  val busIdWidth = 4 bits

  def getBusConfig(dataWidth: BitCount = busDataWidth, addrWidth: BitCount = busAddrWidth) =
    GenericMemoryBusConfig( // Returning GenericMemoryBusConfig
        addressWidth = addrWidth,
        dataWidth = dataWidth,
        useId = false, // Set to false to keep response tuple structure simpler
        idWidth = busIdWidth
    )

  // Helper to initialize memory using the TestBench's direct write ports
  def initMemViaTbPorts(
      tbIo: SimulatedSplitGeneralMemoryTestBench#IO, // Updated TestBench IO type
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
    // println(s"[TB] Memory initialized via TB ports at 0x${address.toHexString} with 0x${value.toString(16)}") // Kept original comment status
  }

  // --- Test Cases ---

  test("SimSplitMem - Single Full Word Read/Write (Internal=BusWidth)") { // Test name updated for clarity
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

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut => // Using new TestBench
      dut.clockDomain.forkStimulus(10)

      // Separate command queues for read and write
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)

      // Set ready for both response channels
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true

      // Separate response queues
      val readResponses = mutable.Queue[(BigInt, Boolean)]() // (data, error)
      val writeResponses = mutable.Queue[(BigInt, Boolean)]() // (dummy_data, error)

      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p =>
        readResponses.enqueue((p.data.toBigInt, p.error.toBoolean))
      }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p =>
        writeResponses.enqueue((BigInt(0), p.error.toBoolean)) // Enqueue dummy data for write responses
      }

      initMemViaTbPorts(dut.io, testAddr, 0, commonWidth.value, commonWidth.value, dut.clockDomain)

      // Write
      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        // cmd.opcode is removed; channel implies opcode
        cmd.data #= testData // SplitGmbWriteCmd has 'data'
        cmd.byteEnables #= BigInt("1111", 2)
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(writeResponses.nonEmpty), "Timeout on write response") // Check writeResponses
      val (_, wErr) = writeResponses.dequeue() // Dequeue from writeResponses
      assert(!wErr, "Write error signaled")

      // Read
      readCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        // cmd.opcode is removed
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(readResponses.nonEmpty), "Timeout on read response") // Check readResponses
      val (rData, rErr) = readResponses.dequeue() // Dequeue from readResponses
      assert(!rErr, "Read error signaled")
      assert(rData == testData, s"Read data mismatch. Expected ${testData.toString(16)}, got ${rData.toString(16)}")
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Partial Word Write (Byte Enables, Internal=BusWidth)") { // Test name updated
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 2
    val testAddr = 0x200
    val initialData = BigInt("12345678", 16)
    val writeDataPart = BigInt("AB", 16)
    val expectedDataAfterPartialWrite = BigInt("12AB5678", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      initMemViaTbPorts(dut.io, testAddr, initialData, commonWidth.value, commonWidth.value, dut.clockDomain)

      val writeCmdData = (initialData & ~BigInt(0x00ff0000L)) | (writeDataPart << 16)

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= writeCmdData
        cmd.byteEnables #= BigInt("0100", 2)
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(writeResponses.nonEmpty), "Timeout on partial write response")
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Partial write error signaled")

      readCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 20)(readResponses.nonEmpty), "Timeout on read-back response")
      val (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Read-back error signaled")
      assert(
        rData == expectedDataAfterPartialWrite,
        s"Partial write data mismatch. Expected ${expectedDataAfterPartialWrite.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Partial Word Write (Byte Enables, Internal=Byte, Bus=Word)") { // Test name updated
    val internalWidth = 8 bits
    val busW = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x300
    val initialDataBytes = Seq(BigInt(0x21), BigInt(0x43), BigInt(0x65), BigInt(0x87))
    val initialDataWord = initialDataBytes.zipWithIndex.map { case (b, i) => b << (i * 8) }.reduce(_ | _)
    // println(s"[TB] Initial data word: ${initialDataWord.toString(16)}")
    val writeDataPartial = BigInt("BA", 16)
    val expectedDataAfterPartialWriteBytes = Seq(BigInt(0x21), BigInt(0x43), writeDataPartial, BigInt(0x87))
    val expectedDataAfterPartialWord =
      expectedDataAfterPartialWriteBytes.zipWithIndex.map { case (b, i) => b << (i * 8) }.reduce(_ | _)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = internalWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

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

      val busWritePayload = writeDataPartial << 16

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= busWritePayload
        cmd.byteEnables #= BigInt("0100", 2)
      }
      val expectedWriteCycles = latency + (busW.value / 8) // This timeout might be too short if DUT latency is per-chunk
      assert(
        !dut.clockDomain.waitSamplingWhere(expectedWriteCycles + 20)(writeResponses.nonEmpty), // Check writeResponses
        "Timeout on partial write response (byte internal)"
      )
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Partial write error signaled (byte internal)")

      // println(s"[TB] Read back the full 32-bit word") // Kept comment
      readCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
      }
      val expectedReadCycles = latency + (busW.value / 8) // This timeout might be too short
      assert(
        !dut.clockDomain.waitSamplingWhere(expectedReadCycles + 100)(readResponses.nonEmpty), // Check readResponses
        "Timeout on read-back response (byte internal)"
      )
      val (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Read-back error signaled (byte internal)")
      assert(
        rData == expectedDataAfterPartialWord,
        s"Partial write data mismatch (byte internal). Expected ${expectedDataAfterPartialWord
            .toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Out-of-Bounds Access") { // Test name updated
    val internalWidth = 16 bits
    val busW = 32 bits
    val memSizeBytes = 128
    val latency = 1

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = internalWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    val oobAddr_startAtBoundary = memSizeBytes
    val oobAddr_fullyPastBoundary = memSizeBytes + 4
    val straddleAddr = memSizeBytes - (busW.value / 8) + 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      val opCycles = latency + (busW.value / internalWidth.value) // Timeout might be short

      readCmdQueue.enqueue { cmd => cmd.address #= oobAddr_startAtBoundary }
      assert(!dut.clockDomain.waitSamplingWhere(opCycles + 15)(readResponses.nonEmpty), "Timeout OOB 1")
      var (_, err) = readResponses.dequeue(); assert(err, "Expected error for OOB read 1")

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= oobAddr_fullyPastBoundary;
        cmd.data #= 0xdead; cmd.byteEnables #= allBytesEnabledValue
      }
      assert(!dut.clockDomain.waitSamplingWhere(opCycles + 10)(writeResponses.nonEmpty), "Timeout OOB 2")
      var (_, err2) = writeResponses.dequeue(); assert(err2, "Expected error for OOB write 2")

      val straddleTestAddr = memSizeBytes - (busW.value / 8) + (memCfg.internalDataWidthBytes)
      if (straddleTestAddr < memSizeBytes) {
        // println(s"Straddle test addr: $straddleTestAddr") // Kept comment
        readCmdQueue.enqueue { cmd => cmd.address #= straddleTestAddr }
        assert(!dut.clockDomain.waitSamplingWhere(opCycles + 10)(readResponses.nonEmpty), "Timeout OOB Straddle")
        var (_, err3) = readResponses.dequeue(); assert(err3, s"Expected error for OOB straddle read at $straddleTestAddr")
      }
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - RMW - Multiple Discontinuous Byte Enables (Internal=BusWidth)") { // Test name updated
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x40
    val initialData = BigInt("11223344", 16)
    val writeCmdData = BigInt("00BB00AA", 16)
    val expectedData = BigInt("11BB33AA", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      initMemViaTbPorts(dut.io, testAddr, initialData, commonWidth.value, commonWidth.value, dut.clockDomain)

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= writeCmdData
        cmd.byteEnables #= BigInt("0101", 2)
      }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(writeResponses.nonEmpty), "Timeout on write response")
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Write error signaled")

      readCmdQueue.enqueue { cmd => cmd.address #= testAddr }
      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(readResponses.nonEmpty), "Timeout on read response")
      val (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Read error signaled")
      assert(
        rData == expectedData,
        s"RMW Multi-byte mismatch. Expected ${expectedData.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Read/Write with Zero Latency (Internal=BusWidth)") { // Test name updated
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 0
    val testAddr = 0x50
    val testData = BigInt("CAFED00D", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency, burstLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      initMemViaTbPorts(dut.io, testAddr, 0, commonWidth.value, commonWidth.value, dut.clockDomain)

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= testData
        cmd.byteEnables #= allBytesEnabledValue
      }
      assert(
        !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(writeResponses.nonEmpty),
        "Timeout on write response (0 latency)"
      )
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Write error signaled (0 latency)")

      readCmdQueue.enqueue { cmd => cmd.address #= testAddr }
      assert(
        !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(readResponses.nonEmpty),
        "Timeout on read response (0 latency)"
      )
      val (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Read error signaled (0 latency)")
      assert(
        rData == testData,
        s"0-latency Read mismatch. Expected ${testData.toString(16)}, got ${rData.toString(16)}"
      )
      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Back-to-Back Operations (Write then Read)") { // Test name updated
    val commonWidth = 32 bits
    val memSizeBytes = 1024
    val latency = 2
    val addr1 = 0x60
    val data1 = BigInt("12345678", 16)
    val addr2 = 0x64
    val data2 = BigInt("87654321", 16)

    val memCfg =
      SimulatedMemoryConfig(internalDataWidth = commonWidth, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = commonWidth)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= addr1; cmd.data #= data1;
        cmd.byteEnables #= allBytesEnabledValue
      }
      writeCmdQueue.enqueue { cmd =>
        cmd.address #= addr2; cmd.data #= data2;
        cmd.byteEnables #= allBytesEnabledValue
      }

      for (i <- 1 to 2) {
        assert(
          !dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(writeResponses.nonEmpty),
          s"Timeout on write response $i"
        )
        val (_, wErr) = writeResponses.dequeue()
        assert(!wErr, s"Write error signaled $i")
      }

      readCmdQueue.enqueue { cmd => cmd.address #= addr1 }
      readCmdQueue.enqueue { cmd => cmd.address #= addr2 }

      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(readResponses.nonEmpty), "Timeout on read 1 response")
      var (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Read 1 error signaled")
      assert(rData == data1, s"Read 1 mismatch. Expected ${data1.toString(16)}, got ${rData.toString(16)}")

      assert(!dut.clockDomain.waitSamplingWhere(latency + 2 + 20)(readResponses.nonEmpty), "Timeout on read 2 response")
      val (rData2, rErr2) = readResponses.dequeue()
      assert(!rErr2, "Read 2 error signaled")
      assert(rData2 == data2, s"Read 2 mismatch. Expected ${data2.toString(16)}, got ${rData2.toString(16)}")

      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Multi-Part Read/Write (Internal=8b, Bus=32b)") { // Test name updated
    val internalW = 8 bits
    val busW = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x70

    val byte0 = BigInt("11", 16)
    val byte1 = BigInt("22", 16)
    val byte2 = BigInt("33", 16)
    val byte3 = BigInt("44", 16)
    val testDataWord = (byte3 << 24) | (byte2 << 16) | (byte1 << 8) | byte0

    val memCfg = SimulatedMemoryConfig(internalDataWidth = internalW, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      for (i <- 0 until 4) {
        initMemViaTbPorts(dut.io, testAddr + i, 0, internalW.value, internalW.value, dut.clockDomain)
      }

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= testDataWord
        cmd.byteEnables #= allBytesEnabledValue
      }
      val numInternalChunks = busW.value / internalW.value
      val estimatedCyclesForOp = numInternalChunks * (latency + 1) + 50 // Timeout might be an issue due to DUT latency model change

      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(writeResponses.nonEmpty),
        "Timeout on multi-part write response"
      )
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Multi-part write error signaled")

      readCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
      }
      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(readResponses.nonEmpty),
        "Timeout on multi-part read response"
      )
      val (rData, rErr) = readResponses.dequeue()
      assert(!rErr, "Multi-part read error signaled")
      assert(
        rData == testDataWord,
        s"Multi-part Read data mismatch. Expected ${testDataWord.toString(16)}, got ${rData.toString(16)}"
      )

      dut.clockDomain.waitSampling(5)
    }
  }

  test("SimSplitMem - Multi-Part RMW (Internal=8b, Bus=32b, Partial BE)") { // Test name updated
    val internalW = 8 bits
    val busW = 32 bits
    val memSizeBytes = 1024
    val latency = 1
    val testAddr = 0x80

    val initialBytes = Seq(BigInt(0x11), BigInt(0x22), BigInt(0x33), BigInt(0x44))
    val initialDataWord = (initialBytes(3) << 24) | (initialBytes(2) << 16) | (initialBytes(1) << 8) | initialBytes(0)

    val writePayload = (BigInt(0xbb) << 16) | (BigInt(0xaa) << 8)
    val expectedDataWord =
      (initialBytes(3) << 24) | (BigInt(0xbb) << 16) | (BigInt(0xaa) << 8) | initialBytes(0)

    val memCfg = SimulatedMemoryConfig(internalDataWidth = internalW, memSize = memSizeBytes, initialLatency = latency)
    val busCfg = getBusConfig(dataWidth = busW)
    val numByteEnables = busCfg.dataWidth.value / 8
    val allBytesEnabledValue = (BigInt(1) << numByteEnables) - 1

    simConfig.compile(new SimulatedSplitGeneralMemoryTestBench(memCfg, busCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (_, writeCmdQueue) = StreamDriver.queue(dut.io.bus.write.cmd, dut.clockDomain)
      val (_, readCmdQueue) = StreamDriver.queue(dut.io.bus.read.cmd, dut.clockDomain)
      dut.io.bus.read.rsp.ready #= true
      dut.io.bus.write.rsp.ready #= true
      val readResponses = mutable.Queue[(BigInt, Boolean)]()
      val writeResponses = mutable.Queue[(BigInt, Boolean)]()
      StreamMonitor(dut.io.bus.read.rsp, dut.clockDomain) { p => readResponses.enqueue((p.data.toBigInt, p.error.toBoolean)) }
      StreamMonitor(dut.io.bus.write.rsp, dut.clockDomain) { p => writeResponses.enqueue((BigInt(0), p.error.toBoolean)) }

      for (i <- 0 until 4) {
        initMemViaTbPorts(dut.io, testAddr + i, initialBytes(i), internalW.value, internalW.value, dut.clockDomain)
      }

      writeCmdQueue.enqueue { cmd =>
        cmd.address #= testAddr
        cmd.data #= writePayload
        cmd.byteEnables #= BigInt("0110", 2)
      }
      val numInternalChunks = busW.value / internalW.value
      val estimatedCyclesForOp = numInternalChunks * (latency + 1) + 50 // Timeout might be an issue

      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(writeResponses.nonEmpty),
        "Timeout on multi-part RMW response"
      )
      val (_, wErr) = writeResponses.dequeue()
      assert(!wErr, "Multi-part RMW error signaled")

      readCmdQueue.enqueue { cmd => cmd.address #= testAddr }
      assert(
        !dut.clockDomain.waitSamplingWhere(estimatedCyclesForOp + 20)(readResponses.nonEmpty),
        "Timeout on multi-part RMW read-back"
      )
      val (rData, rErr) = readResponses.dequeue()
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
