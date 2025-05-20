package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite

// Common project configuration
import parallax.common._

// Components under test and its dependencies
import parallax.components.memory._ // For GenericMemoryBusConfig, SimulatedSplitGeneralMemory, etc.
import parallax.components.icache._ // For AdvancedICacheConfig, AdvancedICacheCpuRsp, etc.

import scala.collection.mutable
import scala.util.Random

// Compile the DUT (InstructionFetchUnit)
class InstructionFetchUnitTestBench(
    val ifuConfig: InstructionFetchUnitConfig,
    val simMemInternalDataWidth: BitCount,
    val simMemSizeBytes: BigInt,
    val simMemLatency: Int
) extends Component {

  val io = new Bundle {
    val pcIn = slave(Stream(UInt(ifuConfig.pcWidth)))
    // Implicit config for AdvancedICacheCpuRsp used in dataOut
    implicit val advIcacheCfg: AdvancedICacheConfig = ifuConfig.icacheConfig
    val dataOut = master(Stream(AdvancedICacheCpuRsp()))
    // val flush = if(ifuConfig.enableFlush) slave(AdvancedICacheFlushBus()) else null // If flush testing is needed
  }

  val fma = new InstructionFetchUnit(ifuConfig)

  // Use SimulatedSplitGeneralMemory
  val mem = new SimulatedSplitGeneralMemory(
    memConfig = SimulatedMemoryConfig(
      internalDataWidth = simMemInternalDataWidth,
      memSize = simMemSizeBytes,
      initialLatency = simMemLatency
    ),
    busConfig = ifuConfig.memBusConfig // This busCfg is for the SplitGenericMemoryBus
  )

  // Connect IFU's SplitGenericMemoryBus to SimulatedSplitGeneralMemory's bus
  fma.io.memBus.read.cmd >> mem.io.bus.read.cmd
  mem.io.bus.read.rsp >> fma.io.memBus.read.rsp

  fma.io.memBus.write.cmd >> mem.io.bus.write.cmd
  mem.io.bus.write.rsp >> fma.io.memBus.write.rsp

  io.pcIn <> fma.io.pcIn
  fma.io.dataOut <> io.dataOut

  // if(ifuConfig.enableFlush) { // If flush testing is needed
  //   fma.io.flush <> io.flush
  // }

  // Expose SimulatedSplitGeneralMemory's direct write ports for initialization
  // These are mem.io.writeEnable, mem.io.writeAddress, mem.io.writeData
  // The SimMemInitDirect helper will use dut.mem.io.*
}

object SimMemInitDirect {

  /** Initializes SimulatedSplitGeneralMemory directly using its combinatorial write ports.
    * This function is intended to be called from within a compiled.doSim block.
    *
    * @param dut The compiled TestBench instance (which contains the 'mem' instance of SimulatedSplitGeneralMemory).
    * @param address Byte address to start writing at.
    * @param value The data value to write.
    * @param internalDataWidth SimulatedMemory's internal data width (bits).
    * @param busDataWidthForValue Width of the 'value' BigInt (bits), typically CPU data width.
    * @param clockDomain The clock domain for timing the writes.
    */
  def initMemDirect(
      dut: InstructionFetchUnitTestBench, // dut.mem is SimulatedSplitGeneralMemory
      address: Long,
      value: BigInt,
      internalDataWidth: BitCount,
      busDataWidthForValue: BitCount,
      clockDomain: ClockDomain
  ): Unit = {
    require(
      busDataWidthForValue.value >= internalDataWidth.value,
      "Value's width must be >= internal data width for this helper."
    )
    if (busDataWidthForValue.value > internalDataWidth.value) {
      require(
        busDataWidthForValue.value % internalDataWidth.value == 0,
        "Value's width must be a multiple of internal data width if greater."
      )
    }

    val numInternalChunks = busDataWidthForValue.value / internalDataWidth.value

    // Set default values for write ports to avoid them being floating
    dut.mem.io.writeEnable #= false
    dut.mem.io.writeAddress #= 0
    dut.mem.io.writeData #= 0
    clockDomain.waitSampling()

    for (i <- 0 until numInternalChunks) {
      val slice = (value >> (i * internalDataWidth.value)) & ((BigInt(1) << internalDataWidth.value) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidth.value / 8)

      dut.mem.io.writeEnable #= true
      dut.mem.io.writeAddress #= internalChunkByteAddress
      dut.mem.io.writeData #= slice
      clockDomain.waitSampling()

      dut.mem.io.writeEnable #= false // Deassert for next cycle or end
    }
    dut.mem.io.writeEnable #= false
    clockDomain.waitSampling()
  }
}

class InstructionFetchUnitSpec extends SpinalSimFunSuite {
  onlyVerilator // As per original

  def XLEN: Int = 32
  def ADDR_WIDTH = XLEN bits
  def DATA_WIDTH = XLEN bits // Instruction width, matches XLEN in this demo

  val simMemInternalDataWidth: BitCount = 32 bits
  val simMemSizeBytes: BigInt = 8 * 1024 // 8 KiB
  val simMemLatency: Int = 2

  def simConfig = SimConfig.withWave

  // Common method to create IFU config
  def createIfuConfig(
      useCache: Boolean,
      enableFlush: Boolean,
      pcWidth: BitCount,
      instrWidth: BitCount,
      fetchGrpWidth: BitCount,
      memBusCfg: GenericMemoryBusConfig
  ): InstructionFetchUnitConfig = {
    InstructionFetchUnitConfig(
      useICache = useCache,
      enableFlush = enableFlush,
      pcWidth = pcWidth,
      instructionWidth = instrWidth,
      fetchGroupDataWidth = fetchGrpWidth,
      memBusConfig = memBusCfg,
      icacheConfig = if (useCache) {
        AdvancedICacheConfig( // Using AdvancedICacheConfig
          cacheSize = 2048, // Bytes
          bytePerLine = 16, // Bytes
          wayCount = 1,     // For simpler replacement logic in this test
          addressWidth = pcWidth,
          dataWidth = instrWidth,       // Corresponds to IFUConfig.instructionWidth
          fetchDataWidth = fetchGrpWidth // Corresponds to IFUConfig.fetchGroupDataWidth
        )
      } else {
        // Minimal AdvancedICacheConfig, as IFUConfig needs its structure for io.dataOut
        AdvancedICacheConfig(
          addressWidth = pcWidth,
          dataWidth = instrWidth,
          fetchDataWidth = fetchGrpWidth
        )
      }
    )
  }

  def runTest0(useICache: Boolean, testDescription: String): Unit = {
    val testName = s"InstructionFetchUnit ${if (useICache) "with ICache" else "without ICache"} - $testDescription"
    test(testName) {
      def memBusCfg = GenericMemoryBusConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)
      // For runTest0, fetch group is a single instruction
      def ifuConfig = createIfuConfig(
          useCache = useICache,
          enableFlush = false,
          pcWidth = ADDR_WIDTH,
          instrWidth = DATA_WIDTH,
          fetchGrpWidth = DATA_WIDTH, // Single instruction per fetch group
          memBusCfg = memBusCfg
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
        // Queue stores (pc, instruction, fault)
        val receivedDataQueue = mutable.Queue[(BigInt, BigInt, Boolean)]()
        StreamMonitor(dut.io.dataOut, dut.clockDomain) { payload =>
          // Assuming fetchWordsPerFetchGroup is 1 due to fetchGrpWidth = DATA_WIDTH
          val instruction = payload.instructions(0).toBigInt
          receivedDataQueue.enqueue(
            (payload.pc.toBigInt, instruction, payload.fault.toBoolean)
          )
        }
        StreamReadyRandomizer(dut.io.dataOut, dut.clockDomain)

        val testAddr = 0x200L
        val testInstr = BigInt("DEADBEEF", 16)
        SimMemInitDirect.initMemDirect(
          dut,
          testAddr,
          testInstr,
          simMemInternalDataWidth,
          DATA_WIDTH, // Value being written is DATA_WIDTH wide
          dut.clockDomain
        )
        dut.clockDomain.waitSampling(5)

        val expectedResults = mutable.Queue[(Long, BigInt, Boolean)]()
        def fetchAndExpect(pc: Long, expectedInstr: BigInt, expectedFault: Boolean = false): Unit = {
          pcCmdQueue.enqueue(u => u #= pc)
          expectedResults.enqueue((pc, expectedInstr, expectedFault))
          println(
            s"[TB] Sent PC: 0x${pc.toHexString}, Expecting Instr: 0x${expectedInstr.toString(16)}, Fault: $expectedFault"
          )
        }

        fetchAndExpect(testAddr, testInstr)
        fetchAndExpect(testAddr, testInstr)

        val timeoutCycles = 2 * ((simMemLatency + 1) * (DATA_WIDTH.value / simMemInternalDataWidth.value) + 20) * 5 + 2000
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedDataQueue.size == expectedResults.size),
          s"Timeout: Received ${receivedDataQueue.size} of ${expectedResults.size} expected results."
        )

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
          assert(actualPc == expectedPc, f"[ASSERT FAIL] PC mismatch for transaction ${i + 1}. Expected 0x$expectedPc%x, got 0x$actualPc%x")
          assert(actualFault == expectedFaultData, f"[ASSERT FAIL] Fault mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected $expectedFaultData%b, got $actualFault%b")
          if (!expectedFaultData) {
            assert(actualInstr == expectedInstrData, f"[ASSERT FAIL] Instruction mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected 0x$expectedInstrData%x, got 0x$actualInstr%x")
          }
        }
        dut.clockDomain.waitSampling(20)
      }
    }
  }

  def runTest(useICache: Boolean, testDescription: String): Unit = {
    val testName = s"InstructionFetchUnit ${if (useICache) "with ICache" else "without ICache"} - $testDescription"
    test(testName) {
      def memBusCfg = GenericMemoryBusConfig(addressWidth = ADDR_WIDTH, dataWidth = DATA_WIDTH)
      // For runTest, fetch group is also a single instruction for simplicity with current test structure
      def ifuConfig = createIfuConfig(
          useCache = useICache,
          enableFlush = false, // Kept false for these tests
          pcWidth = ADDR_WIDTH,
          instrWidth = DATA_WIDTH,
          fetchGrpWidth = DATA_WIDTH, // Single instruction per fetch group
          memBusCfg = memBusCfg
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
          val instruction = payload.instructions(0).toBigInt // Assuming 1 instruction per group
          receivedDataQueue.enqueue(
            (payload.pc.toBigInt, instruction, payload.fault.toBoolean)
          )
        }
        StreamReadyRandomizer(dut.io.dataOut, dut.clockDomain)

        val instructions = mutable.LinkedHashMap[Long, BigInt]()
        def addRandomInstruction(addr: Long): Unit = {
          instructions += (addr -> BigInt(DATA_WIDTH.value, Random))
        }

        addRandomInstruction(0x100L)
        addRandomInstruction(0x104L)
        addRandomInstruction(0x108L)
        addRandomInstruction(0x10cL)

        if (useICache) {
          // Cache config: 2048B size, 16B/line, 1-way => 128 sets.
          // 0x100 maps to set (0x100 >> 4) % 128 = 0x10 % 128 = 16.
          // 0x900 maps to set (0x900 >> 4) % 128 = 0x90 % 128 = 16. (0x90 = 144, 144 % 128 = 16)
          // Correction: 0x90 = 144. 144 % 128 = 16. This is correct.
          // The original calculation was (0x10 + 0x80) << 4 = 0x90 << 4 = 0x900. 0x80 is 128 (number of sets).
          addRandomInstruction(0x900L)
          addRandomInstruction(0x904L)
        }

        instructions.foreach { case (addr, data) =>
          SimMemInitDirect.initMemDirect(
            dut,
            addr,
            data,
            simMemInternalDataWidth,
            DATA_WIDTH, // Value being written is DATA_WIDTH wide
            dut.clockDomain
          )
        }
        dut.clockDomain.waitSampling(5)

        val expectedResults = mutable.Queue[(Long, BigInt, Boolean)]()
        def fetchAndExpect(pc: Long, expectedInstrProvider: Long => BigInt, expectedFault: Boolean = false): Unit = {
          pcCmdQueue.enqueue(u => u #= pc)
          val expectedInstr = if (expectedFault) BigInt(0) else expectedInstrProvider(pc)
          expectedResults.enqueue((pc, expectedInstr, expectedFault))
          println(
            s"[TB] Sent PC: 0x${pc.toHexString}, Expecting Instr: 0x${expectedInstr.toString(16)}, Fault: $expectedFault"
          )
        }

        fetchAndExpect(0x100L, instructions)
        fetchAndExpect(0x104L, instructions)
        fetchAndExpect(0x108L, instructions)

        if (useICache) {
          fetchAndExpect(0x10cL, instructions)
          fetchAndExpect(0x100L, instructions)
          fetchAndExpect(0x900L, instructions)
          fetchAndExpect(0x904L, instructions)
          fetchAndExpect(0x100L, instructions)
        }

        val cyclesPerSimMemPart = simMemLatency + 1
        val partsPerBusTx = (DATA_WIDTH.value / simMemInternalDataWidth.value)
        val simMemEffectiveLatency = cyclesPerSimMemPart * partsPerBusTx
        val baseCyclesPerTx = if (useICache) simMemEffectiveLatency + 15 else simMemEffectiveLatency + 5
        val randomizerEffectFactor = 5
        val timeoutCycles = expectedResults.size * baseCyclesPerTx * randomizerEffectFactor + 2000

        assert(
          !dut.clockDomain.waitSamplingWhere(timeout = timeoutCycles)(receivedDataQueue.size == expectedResults.size),
          s"Timeout: Received ${receivedDataQueue.size} of ${expectedResults.size} expected results."
        )

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
          assert(actualPc == expectedPc, f"[ASSERT FAIL] PC mismatch for transaction ${i + 1}. Expected 0x$expectedPc%x, got 0x$actualPc%x")
          assert(actualFault == expectedFault, f"[ASSERT FAIL] Fault mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected $expectedFault%b, got $actualFault%b")
          if (!expectedFault) {
            assert(actualInstr == expectedInstr, f"[ASSERT FAIL] Instruction mismatch for PC 0x$expectedPc%x (transaction ${i + 1}). Expected 0x$expectedInstr%x, got 0x$actualInstr%x")
          }
        }
        dut.clockDomain.waitSampling(20)
      }
    }
  }

  runTest0(useICache = false, "basic single instruction fetch (no cache)")
  runTest0(useICache = true, "basic single instruction fetch (with cache)")

  runTest(useICache = false, "basic fetches (no cache)") // Simplified description
  runTest(useICache = true, "cache hits, misses, replacement (with cache)") // Simplified
}
