package parallax.test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite

// Make sure these paths are correct for your project structure
import parallax.components.memory.{
  SimulatedMemory,
  SimulatedMemoryConfig,
  GenericMemoryBus,
  GenericMemoryBusConfig,
  GenericMemoryRsp
}

class SimulatedMemorySpec extends CustomSpinalSimFunSuite {

  val busAddrWidth = 32
  val busDataWidth = 32 // The width SimulatedMemory will expose on its bus (default for most tests)

  // Configuration for the GenericMemoryBus that SimulatedMemory will implement
  def getGenericBusConfig(dataWidth: Int = busDataWidth, addrWidth: Int = busAddrWidth) = GenericMemoryBusConfig(
    addressWidth = addrWidth,
    dataWidth = dataWidth
  )

  // Helper for initializing memory using DUT's direct write ports (simulation only)
  // 'address' is the byte address where the 'value' (of busDataWidthForValue) should start.
  // 'value' is data of busDataWidthForValue.
  // This helper decomposes 'value' into internalDataWidth chunks and writes them.
  def initMemViaSimPorts(
      dut: SimulatedMemory,
      address: Int,
      value: BigInt,
      internalDataWidth: Int,
      busDataWidthForValue: Int,
      clockDomain: ClockDomain
  ): Unit = {
    require(busDataWidthForValue >= internalDataWidth, "Value's width must be >= internal data width for this helper.")
    // Ensure busDataWidthForValue is a multiple of internalDataWidth IF it's greater.
    // If it's equal, no % check needed. If it's smaller, that's an error handled by require above.
    if (busDataWidthForValue > internalDataWidth) {
      require(
        busDataWidthForValue % internalDataWidth == 0,
        "Value's width must be a multiple of internal data width if greater."
      )
    }

    val numInternalChunks = busDataWidthForValue / internalDataWidth
    dut.io.writeEnable #= true
    for (i <- 0 until numInternalChunks) {
      val slice = (value >> (i * internalDataWidth)) & ((BigInt(1) << internalDataWidth) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidth / 8)
      dut.io.writeAddress #= internalChunkByteAddress
      dut.io.writeData #= slice
      clockDomain.waitSampling()
    }
    dut.io.writeEnable #= false
    clockDomain.waitSampling()
  }

  test("SimulatedMemory should handle back-to-back read requests") {
    val internalMemWidth = 16 // busDataWidth is 32 by default from class member
    val memSizeBytes = 1024
    val latencyCycles = 2
    val numRequests = 3
    val timeout = (latencyCycles + 10) * numRequests + 40 // Generous timeout

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = busDataWidth)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true

        val receivedResponses = mutable.Queue[(BigInt, Boolean)]() // (readData, error)
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          receivedResponses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val testData = Seq(
          (0x10, BigInt("11AA22BB", 16)),
          (0x20, BigInt("33CC44DD", 16)),
          (0x30, BigInt("55EE66FF", 16))
        )

        // Initialize memory
        testData.foreach { case (addr, data) =>
          initMemViaSimPorts(dut, addr, data, internalMemWidth, currentBusConfig.dataWidth, dut.clockDomain)
        }

        // Enqueue read commands
        testData.foreach { case (addr, _) =>
          cmdQueue.enqueue { cmd =>
            cmd.address #= addr
            cmd.isWrite #= false
            cmd.writeData #= 0
          }
        }

        // Wait for all responses
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(receivedResponses.size == numRequests),
          s"Timeout: Expected $numRequests responses, got ${receivedResponses.size}"
        )

        // Verify responses
        testData.foreach { case (_, expectedData) =>
          assert(receivedResponses.nonEmpty, "Not enough responses received")
          val (readData, error) = receivedResponses.dequeue()
          assert(!error, s"Error flag set for read of $expectedData")
          assert(readData == expectedData, s"Data mismatch. Expected $expectedData, got $readData")
        }
        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should respond to a single word read request") {
    val internalMemWidth = 16
    val memSizeBytes = 1024
    val latencyCycles = 4
    val timeout = latencyCycles + 40 // Adjusted timeout

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = 32) // Explicitly using 32-bit bus for this test

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // --- Testbench Write Initialization ---
        val internalWordsPerBus = currentBusConfig.dataWidth / internalMemWidth
        val testAddrByte = 0x40
        val testDataBusWidth = BigInt("CAFEBABE", 16)

        initMemViaSimPorts(
          dut,
          testAddrByte,
          testDataBusWidth,
          internalMemWidth,
          currentBusConfig.dataWidth,
          dut.clockDomain
        )

        // --- DUT Interaction ---
        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)

        var receivedData: Option[BigInt] = None
        var receivedError: Boolean = false
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          receivedData = Some(payload.readData.toBigInt)
          receivedError = payload.error.toBoolean
        }

        dut.io.bus.rsp.ready #= true

        // 发送读命令
        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= false
          cmd.writeData #= 0
        }

        // 等待响应
        assert(!dut.clockDomain.waitSamplingWhere(timeout)(receivedData.isDefined), "Timeout waiting for read response")

        assert(
          receivedData.get == testDataBusWidth,
          s"Read data mismatch. Expected $testDataBusWidth, got ${receivedData.get}"
        )
        assert(!receivedError, "Error signal was asserted on read")

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should respond to a single word write request") {
    val internalMemWidth = 16
    val memSizeBytes = 1024
    val latencyCycles = 1
    val timeout = latencyCycles + 40 // Adjusted timeout

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = 32)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)

        val responses = mutable.Queue[(BigInt, Boolean)]() // (readData, error)
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }
        dut.io.bus.rsp.ready #= true

        val testAddrByte = 0x80
        val testWriteData = BigInt("FEEDFACE", 16)

        // 发送写命令
        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= true
          cmd.writeData #= testWriteData
        }

        // 等待响应
        assert(!dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty), "Timeout waiting for write response")
        val (_, writeError) = responses.dequeue()
        assert(!writeError, "Write operation signaled an error")
        dut.clockDomain.waitEdge(5)

        // 验证写入 - 发送读命令
        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= false
          cmd.writeData #= 0
        }

        // 等待并验证读取结果
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          "Timeout waiting for read back response"
        )
        val (readBackData, readError) = responses.dequeue()
        assert(!readError, "Read back operation signaled an error")
        assert(
          readBackData == testWriteData,
          s"Read back after write mismatch. Expected $testWriteData, got $readBackData"
        )

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should handle back-to-back write requests followed by reads") {
    val internalMemWidth = 8 // busDataWidth is 32
    val memSizeBytes = 1024
    val latencyCycles = 1
    val numRequests = 3
    val timeout = (latencyCycles + 10) * numRequests * 2 + 40 // For writes then reads

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = busDataWidth)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true

        val receivedResponses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          receivedResponses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val testData = Seq(
          (0x100, BigInt("AABBCCDD", 16)),
          (0x104, BigInt("EEFF0011", 16)), // Next word address
          (0x108, BigInt("22334455", 16))
        )

        // Enqueue write commands
        testData.foreach { case (addr, data) =>
          cmdQueue.enqueue { cmd =>
            cmd.address #= addr
            cmd.isWrite #= true
            cmd.writeData #= data
          }
        }

        // Wait for all write responses
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout / 2)(receivedResponses.size == numRequests),
          s"Timeout: Expected $numRequests write responses, got ${receivedResponses.size}"
        )

        // Verify write responses (ack, no error)
        for (i <- 0 until numRequests) {
          assert(receivedResponses.nonEmpty, "Not enough write responses")
          val (_, error) = receivedResponses.dequeue()
          assert(!error, s"Error flag set for write operation ${i + 1}")
        }

        // Enqueue read commands to verify
        testData.foreach { case (addr, _) =>
          cmdQueue.enqueue { cmd =>
            cmd.address #= addr
            cmd.isWrite #= false
            cmd.writeData #= 0
          }
        }

        // Wait for all read responses
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout / 2)(receivedResponses.size == numRequests),
          s"Timeout: Expected $numRequests read responses, got ${receivedResponses.size}"
        )

        // Verify read responses
        testData.foreach { case (i, expectedData) =>
          assert(receivedResponses.nonEmpty, s"Case $i Not enough read responses")
          val (readData, error) = receivedResponses.dequeue()
          assert(!error, s"Case $i Error flag set for read of $expectedData")
          assert(readData == expectedData, s"Case $i Read back data mismatch. Expected $expectedData, got $readData")
        }
        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should handle mixed read and write requests") {
    val internalMemWidth = 32 // busDataWidth is 32
    val memSizeBytes = 1024
    val latencyCycles = 2
    val timeout = (latencyCycles + 15) * 5 + 40 // 5 operations

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = busDataWidth)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true

        val receivedResponses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          receivedResponses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val addrA = 0x50
        val dataA_orig = BigInt("AAAAAAAA", 16)
        val addrB = 0x60
        val dataB_new = BigInt("BBBBBBBB", 16)
        val addrC = 0x70
        val dataC_new = BigInt("CCCCCCCC", 16)

        // Initialize memory for addrA
        initMemViaSimPorts(dut, addrA, dataA_orig, internalMemWidth, currentBusConfig.dataWidth, dut.clockDomain)

        // Sequence: Read A, Write B, Read A (old), Write C, Read B (new), Read C (new)
        // 1. Read A
        cmdQueue.enqueue { cmd => cmd.address #= addrA; cmd.isWrite #= false; cmd.writeData #= 0 }
        // 2. Write B
        cmdQueue.enqueue { cmd => cmd.address #= addrB; cmd.isWrite #= true; cmd.writeData #= dataB_new }
        // 3. Read A again
        cmdQueue.enqueue { cmd => cmd.address #= addrA; cmd.isWrite #= false; cmd.writeData #= 0 }
        // 4. Write C
        cmdQueue.enqueue { cmd => cmd.address #= addrC; cmd.isWrite #= true; cmd.writeData #= dataC_new }
        // 5. Read B
        cmdQueue.enqueue { cmd => cmd.address #= addrB; cmd.isWrite #= false; cmd.writeData #= 0 }
        // 6. Read C
        cmdQueue.enqueue { cmd => cmd.address #= addrC; cmd.isWrite #= false; cmd.writeData #= 0 }

        val totalOps = 6
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(receivedResponses.size == totalOps),
          s"Timeout: Expected $totalOps responses, got ${receivedResponses.size}"
        )

        // Verify responses in order
        // 1. Read A
        val (data, error) = receivedResponses.dequeue()
        assert(!error && data == dataA_orig, s"Read A (1) failed. Expected $dataA_orig, Got $data, Error: $error")
        // 2. Write B ack
        val (_, error2) = receivedResponses.dequeue()
        assert(!error2, "Write B ack failed.")
        // 3. Read A again
        val (data3, error3) = receivedResponses.dequeue()
        assert(!error3 && data3 == dataA_orig, s"Read A (2) failed. Expected $dataA_orig, Got $data3, Error: $error3")
        // 4. Write C ack
        val (_, error4) = receivedResponses.dequeue()
        assert(!error4, "Write C ack failed.")
        // 5. Read B
        val (data5, error5) = receivedResponses.dequeue()
        assert(!error5 && data5 == dataB_new, s"Read B failed. Expected $dataB_new, Got $data5, Error: $error5")
        // 6. Read C
        val (data6, error6) = receivedResponses.dequeue()
        assert(!error6 && data6 == dataC_new, s"Read C failed. Expected $dataC_new, Got $data6, Error: $error6")

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should allow access to address 0") {
    val internalMemWidth = 16
    val memSizeBytes = 1024
    val latencyCycles = 3
    val timeout = latencyCycles + 40

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = busDataWidth)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true
        val responses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val testAddr = 0
        val testData = BigInt("00DEAD00", 16)

        // Write to address 0
        cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.isWrite #= true; cmd.writeData #= testData }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          "Timeout waiting for write to addr 0 response"
        )
        var (_, error) = responses.dequeue()
        assert(!error, "Error on write to address 0")

        // Read from address 0
        cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.isWrite #= false; cmd.writeData #= 0 }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          "Timeout waiting for read from addr 0 response"
        )
        var (readData, readError) = responses.dequeue()
        assert(!readError, "Error on read from address 0")
        assert(readData == testData, s"Data mismatch at address 0. Expected $testData, got $readData")

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should allow access to max valid address") {
    val internalMemWidth = 8
    val memSizeBytes = 256 // Small memory for testing boundary
    val latencyCycles = 2
    val currentBusConfig = getGenericBusConfig(dataWidth = 16) // bus uses 16-bit data
    val maxAddr = memSizeBytes - (currentBusConfig.dataWidth / 8) // Max start address for a bus-width access
    val timeout = latencyCycles + 20

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth, // Memory is byte-addressable internally
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true
        val responses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val testData = BigInt("FADE", 16) // 16-bit data

        // Write to max address
        cmdQueue.enqueue { cmd => cmd.address #= maxAddr; cmd.isWrite #= true; cmd.writeData #= testData }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          s"Timeout waiting for write to max_addr ($maxAddr) response"
        )
        var (_, error) = responses.dequeue()
        assert(!error, s"Error on write to max address $maxAddr")

        // Read from max address
        cmdQueue.enqueue { cmd => cmd.address #= maxAddr; cmd.isWrite #= false; cmd.writeData #= 0 }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          s"Timeout waiting for read from max_addr ($maxAddr) response"
        )
        var (readData, readError) = responses.dequeue()
        assert(!readError, s"Error on read from max address $maxAddr")
        assert(readData == testData, s"Data mismatch at max address $maxAddr. Expected $testData, got $readData")

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should signal error for out-of-bounds access") {
    val internalMemWidth = 16
    val memSizeBytes = 128
    val latencyCycles = 1
    val currentBusConfig = getGenericBusConfig(dataWidth = 32) // busDataWidth = 32
    // Out-of-bounds addresses for a 32-bit access (4 bytes)
    val oobAddr1 = memSizeBytes // Starts exactly at end boundary
    val oobAddr2 = memSizeBytes + 4
    // An address that starts valid but extends OOB (if DUT checks end of access)
    // For this test, we rely on DUT checking start_addr >= memSize
    // val straddleAddr = memSizeBytes - (currentBusConfig.dataWidth / 8) + 1

    val timeout = latencyCycles + 20

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true
        val responses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        // Test 1: Read from oobAddr1
        cmdQueue.enqueue { cmd => cmd.address #= oobAddr1; cmd.isWrite #= false; cmd.writeData #= 0 }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          s"Timeout waiting for OOB read response from $oobAddr1"
        )
        val (_, error) = responses.dequeue()
        assert(error, s"Expected error for OOB read from $oobAddr1, but no error signaled.")

        // Test 2: Write to oobAddr2
        cmdQueue.enqueue { cmd =>
          cmd.address #= oobAddr2; cmd.isWrite #= true; cmd.writeData #= BigInt("DEADBEEF", 16)
        }
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty),
          s"Timeout waiting for OOB write response to $oobAddr2"
        )
        val (_, error2) = responses.dequeue()
        assert(error2, s"Expected error for OOB write to $oobAddr2, but no error signaled.")

        dut.clockDomain.waitEdge(10)
      }
  }

  // --- Different internalDataWidth vs busDataWidth scenarios ---

  test("SimulatedMemory should work when internalDataWidth == busDataWidth") {
    val commonWidth = 32
    val memSizeBytes = 512
    val latencyCycles = 2
    val timeout = latencyCycles + 20

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = commonWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )
    val currentBusConfig = getGenericBusConfig(dataWidth = commonWidth)

    simConfig
      .compile(new SimulatedMemory(memCfg, currentBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        val (_, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        dut.io.bus.rsp.ready #= true
        val responses = mutable.Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responses.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }

        val testAddr = 0xa0
        val testData = BigInt("12345678", 16)

        // Write
        cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.isWrite #= true; cmd.writeData #= testData }
        assert(!dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty), "Timeout waiting for write response")
        var (_, error) = responses.dequeue()
        assert(!error, "Error on write (internalWidth == busWidth)")

        // Read
        cmdQueue.enqueue { cmd => cmd.address #= testAddr; cmd.isWrite #= false; cmd.writeData #= 0 }
        assert(!dut.clockDomain.waitSamplingWhere(timeout)(responses.nonEmpty), "Timeout waiting for read response")
        var (readData, readError) = responses.dequeue()
        assert(!readError, "Error on read (internalWidth == busWidth)")
        assert(readData == testData, s"Data mismatch. Expected $testData, got $readData")

        dut.clockDomain.waitEdge(10)
      }
  }

  thatsAll // mark end of tests
}
