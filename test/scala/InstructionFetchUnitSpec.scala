package boson.test.scala

import org.scalatest.funsuite.AnyFunSuite // Not strictly needed with SpinalSimFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite // Assuming this is the base class for tests
import boson.demo2.components.icache._

// Common project configuration
import boson.demo2.common.Config // Make sure this path is correct

// Components under test and its dependencies
import boson.demo2.components.memory.{InstructionFetchUnit, InstructionFetchUnitConfig}
import boson.demo2.components.memory._

import scala.collection.mutable
import scala.util.Random

// Compile the DUT (InstructionFetchUnit)
class InstructionFetchUnitTestBench(
    val ifuConfig: InstructionFetchUnitConfig,
    val simMemInternalDataWidth: Int,
    val simMemSizeBytes: BigInt,
    val simMemLatency: Int
) extends Component {

  val io = new Bundle {
    val pcIn = slave(Stream(UInt(ifuConfig.cpuAddressWidth bits)))
    implicit val icacheConfig: SimpleICacheConfig = ifuConfig.icacheConfig
    val dataOut = master(Stream(ICacheCpuRsp()))

  }

  val fma = new InstructionFetchUnit(ifuConfig)
  val memCfg = SimulatedMemoryConfig(
    internalDataWidth = simMemInternalDataWidth,
    memSize = simMemSizeBytes,
    initialLatency = simMemLatency
    // burstLatency is not used by the provided SimulatedMemory FSM
  )
  // The SimulatedMemory component itself takes the busConfig for its SimpleMemoryBus
  val mem = new SimulatedMemory(memCfg, ifuConfig.memBusConfig)
  fma.io.memBus.cmd >> mem.io.bus.cmd
  fma.io.memBus.rsp << mem.io.bus.rsp

  io.pcIn <> fma.io.pcIn
  fma.io.dataOut <> io.dataOut

  mem.io.simPublic()
}

object SimMemInitDirect {

  /** Initializes SimulatedMemory directly using its combinatorial write ports.
    * This function is intended to be called from within a compiled.doSim block.
    *
    * @param dut The compiled TestBench instance (which contains the 'mem' instance).
    * @param address Byte address to start writing at.
    * @param value The data value to write.
    * @param internalDataWidth SimulatedMemory's internal data width (bits).
    * @param busDataWidthForValue Width of the 'value' BigInt (bits), typically CPU data width.
    * @param clockDomain The clock domain for timing the writes.
    */
  def initMemDirect(
      dut: InstructionFetchUnitTestBench, // Pass the DUT to access dut.mem
      address: Long,
      value: BigInt,
      internalDataWidth: Int,
      busDataWidthForValue: Int,
      clockDomain: ClockDomain
  ): Unit = {
    require(
      busDataWidthForValue >= internalDataWidth,
      "Value's width must be >= internal data width for this helper."
    )
    if (busDataWidthForValue > internalDataWidth) {
      require(
        busDataWidthForValue % internalDataWidth == 0,
        "Value's width must be a multiple of internal data width if greater."
      )
    }

    val numInternalChunks = busDataWidthForValue / internalDataWidth

    // Set default values for write ports to avoid them being floating
    // These will be overridden momentarily during each write cycle
    dut.mem.io.writeEnable #= false
    dut.mem.io.writeAddress #= 0
    dut.mem.io.writeData #= 0

    clockDomain.waitSampling() // Ensure defaults are applied before starting writes

    for (i <- 0 until numInternalChunks) {
      // Extract the current chunk (little-endian chunking for consistency with bus behavior)
      val slice = (value >> (i * internalDataWidth)) & ((BigInt(1) << internalDataWidth) - 1)
      // Calculate byte address for the current internal chunk
      val internalChunkByteAddress = address + i * (internalDataWidth / 8)

      // Apply write signals
      dut.mem.io.writeEnable #= true
      dut.mem.io.writeAddress #= internalChunkByteAddress
      dut.mem.io.writeData #= slice
      clockDomain.waitSampling() // Hold write signals for one cycle

      // Deassert writeEnable for the next cycle (or if this is the last write)
      // This prevents writing the same data to the same address on subsequent cycles if not careful
      dut.mem.io.writeEnable #= false
    }
    // Ensure writeEnable is false after all writes
    dut.mem.io.writeEnable #= false
    clockDomain.waitSampling() // Allow last write to settle
  }
}

class InstructionFetchUnitSpec extends SpinalSimFunSuite {
  onlyVerilator

  def XLEN: Int = Config.XLEN
  def ADDR_WIDTH: Int = XLEN
  def DATA_WIDTH: Int = XLEN // Instruction width, matches XLEN in this demo

  val simMemInternalDataWidth: Int = 32 // bits, memory is an array of 32-bit words in SimMem
  val simMemSizeBytes: BigInt = 8 * 1024 // 8 KiB
  val simMemLatency: Int = 2 // cycles for each internal part access in SimulatedMemory

  def simConfig = SimConfig.withWave

  def runTest0(useICache: Boolean, testDescription: String): Unit = {
    val testName = s"InstructionFetchUnit ${if (useICache) "with ICache" else "without ICache"} - $testDescription"
    test(testName) {
      def memBusCfg = GenericMemoryBusConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)
      def icacheCfg = if (useICache) {
        SimpleICacheConfig(cacheSize = 2048, addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH, bytePerLine = 16)
      } else {
        SimpleICacheConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)
      }
      def ifuConfig = InstructionFetchUnitConfig(
        useICache = useICache,
        enableFlush = false,
        cpuAddressWidth = ADDR_WIDTH,
        cpuDataWidth = DATA_WIDTH,
        memBusConfig = memBusCfg,
        icacheConfig = icacheCfg
      )

      def compiled = simConfig.compile(
        new InstructionFetchUnitTestBench(ifuConfig, simMemInternalDataWidth, simMemSizeBytes, simMemLatency)
      )

      compiled.doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(5)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(5)

        val (pcInDriver, pcCmdQueue) = StreamDriver.queue(dut.io.pcIn, dut.clockDomain)
        val receivedDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
        StreamMonitor(dut.io.dataOut, dut.clockDomain) { payload =>
          receivedDataQueue.enqueue(
            (payload.pc.toBigInt, payload.instruction.toBigInt, payload.fault.toBoolean)
          )
        }
        StreamReadyRandomizer(dut.io.dataOut, dut.clockDomain) // Keep for realistic backpressure

        // Initialize a single instruction
        val testAddr = 0x200L
        val testInstr = BigInt("DEADBEEF", 16)
        SimMemInitDirect.initMemDirect(
          dut,
          testAddr,
          testInstr,
          simMemInternalDataWidth,
          DATA_WIDTH,
          dut.clockDomain
        )
        dut.clockDomain.waitSampling(5) // Settle memory write

        // Fetch the instruction twice
        val expectedResults = mutable.Queue[(Long, BigInt, Boolean)]()

        def fetchAndExpect(pc: Long, expectedInstr: BigInt, expectedFault: Boolean = false): Unit = {
          pcCmdQueue.enqueue(u => u #= pc)
          expectedResults.enqueue((pc, expectedInstr, expectedFault))
          println(
            s"[TB] Sent PC: 0x${pc.toHexString}, Expecting Instr: 0x${expectedInstr.toString(16)}, Fault: $expectedFault"
          )
        }

        // First fetch (could be a miss)
        fetchAndExpect(testAddr, testInstr)
        // Second fetch (could be a hit if caching is active and line was filled)
        fetchAndExpect(testAddr, testInstr)

        // Wait for transactions
        val timeoutCycles =
          2 * ((simMemLatency + 1) * (DATA_WIDTH / simMemInternalDataWidth) + 20) * 5 + 200 // Simplified timeout
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedDataQueue.size == expectedResults.size),
          s"Timeout: Received ${receivedDataQueue.size} of ${expectedResults.size} expected results."
        )

        // Assertions
        assert(
          receivedDataQueue.size == expectedResults.size,
          s"[ASSERT FAIL] Transaction count mismatch. Expected ${expectedResults.size}, got ${receivedDataQueue.size}"
        )

        for (i <- 0 until expectedResults.size) {
          val (expectedPc, expectedInstrData, expectedFaultData) = expectedResults.dequeue()
          val (actualPc, actualInstr, actualFault) = receivedDataQueue.dequeue()

          println(
            f"[TB] Checking result ${i + 1}: Expected (PC:0x$expectedPc%x, Instr:0x$expectedInstrData%x, Fault:$expectedFaultData%b) --- Got (PC:0x$actualPc%x, Instr:0x$actualInstr%x, Fault:$actualFault%b)"
          )
          assert(
            actualPc == expectedPc,
            f"[ASSERT FAIL] PC mismatch for transaction ${i + 1}. Expected 0x$expectedPc%x, got 0x$actualPc%x"
          )
          assert(
            actualFault == expectedFaultData,
            f"[ASSERT FAIL] Fault mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected $expectedFaultData%b, got $actualFault%b"
          )
          if (!expectedFaultData) {
            assert(
              actualInstr == expectedInstrData,
              f"[ASSERT FAIL] Instruction mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected 0x$expectedInstrData%x, got 0x$actualInstr%x"
            )
          }
        }
        dut.clockDomain.waitSampling(20)
      }
    }
  }

  // Test case generator
  def runTest(useICache: Boolean, testDescription: String): Unit = {
    val testName = s"InstructionFetchUnit ${if (useICache) "with ICache" else "without ICache"} - $testDescription"
    test(testName) {
      // 1. Configure InstructionFetchUnit and its dependencies
      // This bus config is for InstructionFetchUnit's external memory bus (io.memBus)
      // AND for ICache's memory port if ICache is used.
      def memBusCfg = GenericMemoryBusConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)

      def icacheCfg = if (useICache) {
        SimpleICacheConfig(
          cacheSize = 2048, // e.g., 2048 Bytes
          addressWidth = ADDR_WIDTH, // CPU-side address width
          dataWidth = DATA_WIDTH, // CPU-side data width (instruction width)
          bytePerLine = 16 // e.g., 4 words if DATA_WIDTH = 32 bits (4 Bytes/word)
        )
      } else {
        SimpleICacheConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)
      }

      def ifuConfig = InstructionFetchUnitConfig(
        useICache = useICache,
        cpuAddressWidth = ADDR_WIDTH,
        cpuDataWidth = DATA_WIDTH,
        memBusConfig = memBusCfg,
        icacheConfig = icacheCfg // Pass the configured or dummy ICache config
      )

      def compiled = simConfig.compile(
        new InstructionFetchUnitTestBench(ifuConfig, simMemInternalDataWidth, simMemSizeBytes, simMemLatency)
      )

      compiled.doSim { dut =>
        // 2. Setup Clock, Reset
        dut.clockDomain.forkStimulus(period = 10) // 10ns clock period (100 MHz)
        dut.clockDomain.assertReset()
        dut.clockDomain.waitSampling(5)
        dut.clockDomain.deassertReset()
        dut.clockDomain.waitSampling(5)

        // 4. Setup Stream Drivers and Monitors for DUT's CPU-side interfaces
        val (pcInDriver, pcCmdQueue) = StreamDriver.queue(dut.io.pcIn, dut.clockDomain)
        val receivedDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]() // Stores (pc, instruction, fault)
        StreamMonitor(dut.io.dataOut, dut.clockDomain) { payload =>
          receivedDataQueue.enqueue(
            (payload.pc.toBigInt, payload.instruction.toBigInt, payload.fault.toBoolean)
          )
        }
        // Randomize DUT's consumption of output data to test backpressure handling
        // dataOut.ready is an input to the DUT's master stream interface
        StreamReadyRandomizer(dut.io.dataOut, dut.clockDomain)

        // 5. Initialize SimulatedMemory with test instructions
        val instructions = mutable.LinkedHashMap[Long, BigInt]()
        def addRandomInstruction(addr: Long): Unit = {
          // Generate a random instruction of DATA_WIDTH bits
          instructions += (addr -> BigInt(DATA_WIDTH, Random))
        }

        addRandomInstruction(0x100L)
        addRandomInstruction(0x104L)
        addRandomInstruction(0x108L)
        addRandomInstruction(0x10cL) // Belongs to the same 16-byte cache line as 0x100

        if (useICache) {
          // Address for cache replacement test. For a 2KB, 1-way, 16B-line cache:
          // NumSets = 2048 Bytes / 16 Bytes/line = 128 sets.
          // Index for 0x100: (0x100 >> 4) % 128 = (0x10) % 128 = 16.
          // An address that maps to the same set index 16:
          // e.g., 0x100 + (NumSets * LineSize) = 0x100 + (128 * 16) = 0x100 + 2048 = 0x100 + 0x800 = 0x900.
          addRandomInstruction(0x900L)
          addRandomInstruction(0x904L) // Belongs to the same cache line as 0x900
        }

        instructions.foreach { case (addr, data) =>
          SimMemInitDirect.initMemDirect( // Call the new direct initialization helper
            dut, // Pass the dut instance
            addr,
            data,
            simMemInternalDataWidth,
            DATA_WIDTH,
            dut.clockDomain
          )
        }

        dut.clockDomain.waitSampling(5) // Allow memory writes via sim ports to settle

        // 6. Test Execution: Send PC requests and queue expected results
        val expectedResults = mutable.Queue[(Long, BigInt, Boolean)]()

        // Helper to enqueue a PC fetch request and its expected outcome
        def fetchAndExpect(pc: Long, expectedInstrProvider: Long => BigInt, expectedFault: Boolean = false): Unit = {
          pcCmdQueue.enqueue(u => u #= pc) // Send PC value to DUT
          // If fault is expected, instruction data might be undefined or a specific fault pattern.
          // For simplicity, we'll expect 0 as instruction data when a fault occurs.
          val expectedInstr = if (expectedFault) BigInt(0) else expectedInstrProvider(pc)
          expectedResults.enqueue((pc, expectedInstr, expectedFault))
          println(
            s"[TB] Sent PC: 0x${pc.toHexString}, Expecting Instr: 0x${expectedInstr.toString(16)}, Fault: $expectedFault"
          )
        }

        // --- Define Test Sequence ---
        // Test 1: Sequential fetches (these will be misses initially, then hits if cached)
        fetchAndExpect(0x100L, instructions)
        fetchAndExpect(0x104L, instructions)
        fetchAndExpect(0x108L, instructions)

        if (useICache) {
          // Test 2: Cache hits (these addresses should now be in the cache)
          fetchAndExpect(0x10cL, instructions) // Should be a hit (same line as 0x100, 0x104, 0x108)
          fetchAndExpect(0x100L, instructions) // Definite hit
        }

        if (useICache) {
          // Test 3: Cache miss causing replacement (0x900 maps to the same set as 0x100)
          fetchAndExpect(0x900L, instructions) // Miss, loads line for 0x900, should evict 0x100's line
          fetchAndExpect(0x904L, instructions) // Hit on 0x900's newly loaded line

          // Test 4: Fetch original line again (0x100 should now be a miss due to replacement)
          fetchAndExpect(0x100L, instructions) // Miss again
        }

        // Wait for all transactions to be processed by DUT and collected by the monitor
        // Timeout calculation needs to be generous:
        // - SimulatedMemory latency depends on internalDataWidth vs busDataWidth and its initialLatency.
        // - ICache introduces its own latency (hit/miss).
        // - StreamReadyRandomizer adds variability.
        val cyclesPerSimMemPart = simMemLatency + 1 // Approx cycles per internal word in SimMem
        val partsPerBusTx = DATA_WIDTH / simMemInternalDataWidth
        val simMemEffectiveLatency = cyclesPerSimMemPart * partsPerBusTx
        val baseCyclesPerTx =
          if (useICache) simMemEffectiveLatency + 15
          else simMemEffectiveLatency + 5 // Extra for ICache logic or direct path logic
        val randomizerEffectFactor = 5 // Multiplier to account for StreamReadyRandomizer induced delays
        val timeoutCycles = expectedResults.size * baseCyclesPerTx * randomizerEffectFactor + 2000 // Overall buffer

        assert(
          !dut.clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedDataQueue.size == expectedResults.size),
          "timeout"
        )

        // 7. Assertions: Compare received data with expected results
        assert(
          receivedDataQueue.size == expectedResults.size,
          s"[ASSERT FAIL] Transaction count mismatch. Expected ${expectedResults.size}, got ${receivedDataQueue.size}"
        )

        for (i <- 0 until expectedResults.size) {
          val (expectedPc, expectedInstr, expectedFault) = expectedResults.dequeue()
          val (actualPc, actualInstr, actualFault) = receivedDataQueue.dequeue()

          println(
            f"[TB] Checking result ${i + 1}: Expected (PC:0x$expectedPc%x, Instr:0x$expectedInstr%x, Fault:$expectedFault%b) --- Got (PC:0x$actualPc%x, Instr:0x$actualInstr%x, Fault:$actualFault%b)"
          )

          assert(
            actualPc == expectedPc,
            f"[ASSERT FAIL] PC mismatch for transaction ${i + 1}. Expected 0x$expectedPc%x, got 0x$actualPc%x"
          )
          assert(
            actualFault == expectedFault,
            f"[ASSERT FAIL] Fault mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected $expectedFault%b, got $actualFault%b"
          )

          if (!expectedFault) { // Only check instruction data if no fault is expected
            assert(
              actualInstr == expectedInstr,
              f"[ASSERT FAIL] Instruction mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected 0x$expectedInstr%x, got 0x$actualInstr%x"
            )
          }
        }
        dut.clockDomain.waitSampling(20) // Final settling time before sim ends
      }
    }
  }
  runTest0(useICache = false, "basic single instruction fetch (no cache)")
  runTest0(useICache = true, "basic single instruction fetch (with cache)")

  // === Execute Test Cases ===
  runTest(useICache = false, "basic fetches and out-of-bounds access")
  runTest(useICache = true, "cache hits, misses, replacement, and out-of-bounds access")

}
