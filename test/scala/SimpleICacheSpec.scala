package boson.test.scala // Or your preferred test package

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite

import boson.demo2.components.icache._
import boson.demo2.components.memory._

class SimpleICacheSpec extends CustomSpinalSimFunSuite {

  // --- Testbench Component ---
  class SimpleICacheTestbench(
      val cacheCfg: SimpleICacheConfig,
      val memBusCfg: GenericMemoryBusConfig,
      val simMemCfg: SimulatedMemoryConfig
  ) extends Component {
    val io = new Bundle {
      val cpuBus = slave(ICacheCpuBus()(cacheCfg))
      val flushBus = slave(ICacheFlushBus())
    }

    val icache = new SimpleICache()(cacheCfg, memBusCfg)
    val memory = new SimulatedMemory(simMemCfg, memBusCfg)

    // Expose IOs for simulation purposes
    memory.io.simPublic()
    icache.io.simPublic() // Expose ICache's IOs, including its internal FSM state if made an output

    // Connections
    icache.io.mem <> memory.io.bus
    icache.io.cpu <> io.cpuBus
    icache.io.flush <> io.flushBus
  }

  // Helper function to initialize SimulatedMemory via DUT's internal memory instance's IO
  def initSimulatedMemoryViaDUT(
      dutMemoryIO: SimulatedMemory#IO,
      address: Long,
      value: BigInt,
      internalDataWidth: Int, // Width of one word in SimulatedMemory
      busDataWidthForValue: Int, // Width of the 'value' being passed (typically bus width)
      clockDomain: ClockDomain
  ): Unit = {
    val internalDataWidthBytes = internalDataWidth / 8
    val numInternalChunksPerBusWord = busDataWidthForValue / internalDataWidth

    require(busDataWidthForValue >= internalDataWidth, "Value's width must be >= internal data width.")
    require(
      busDataWidthForValue % internalDataWidth == 0,
      "Value's width must be a multiple of internal data width if greater."
    )

    dutMemoryIO.writeEnable #= true
    for (i <- 0 until numInternalChunksPerBusWord) {
      val slice = (value >> (i * internalDataWidth)) & ((BigInt(1) << internalDataWidth) - 1)
      val internalChunkByteAddress = address + i * internalDataWidthBytes
      dutMemoryIO.writeAddress #= internalChunkByteAddress
      dutMemoryIO.writeData #= slice
      clockDomain.waitSampling()
    }
    dutMemoryIO.writeEnable #= false
    clockDomain.waitSampling()
  }

  test("SimpleICache - Miss then Hit on same line") {
    val lineSizeBytes = 32
    val dataWidthBits = 32
    val wordsPerLineCache = lineSizeBytes / (dataWidthBits / 8)

    val cacheConfig = SimpleICacheConfig(
      cacheSize = 128, // 4 lines
      bytePerLine = lineSizeBytes,
      addressWidth = 32,
      dataWidth = dataWidthBits
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32, dataWidth = dataWidthBits)
    val simMemConfig = SimulatedMemoryConfig(
      internalDataWidth = dataWidthBits,
      memSize = 8 KiB, // Increased memory size
      initialLatency = 2
    )

    def tb = new SimpleICacheTestbench(cacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      // Initialize flush signals to inactive
      dut.io.flushBus.cmd.valid #= false
      dut.io.flushBus.cmd.payload.start #= false

      // --- Setup Drivers and Monitors ---
      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)

      val cpuRspBuffer = mutable.Queue[(BigInt, Boolean, BigInt)]() // (instruction, fault, pc)
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        cpuRspBuffer.enqueue(
          (payload.instruction.toBigInt, payload.fault.toBoolean, payload.pc.toBigInt)
        )
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)
      StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain) // Randomize memory's cmd ready

      // --- Prepare Test Data ---
      val testAddr1 = 0x1000L // Belongs to line index 0 for this config
      val testData1 = BigInt("AABBCCDD", 16)
      val testAddr2 = testAddr1 + (dataWidthBits / 8) // 0x1004, same line, next word
      val testData2 = BigInt("11223344", 16)

      initSimulatedMemoryViaDUT(
        dut.memory.io,
        testAddr1,
        testData1,
        simMemConfig.internalDataWidth,
        memBusConfig.dataWidth,
        dut.clockDomain
      )
      initSimulatedMemoryViaDUT(
        dut.memory.io,
        testAddr2,
        testData2,
        simMemConfig.internalDataWidth,
        memBusConfig.dataWidth,
        dut.clockDomain
      )
      // It's good practice to initialize the whole line if testing line fills thoroughly
      // For now, these two words are sufficient for this specific test.

      dut.clockDomain.waitSampling(5) // Allow memory writes to settle

      // --- 1. Request testAddr1 (Expect Cache MISS) ---
      println(s"SIM: Requesting address 0x${testAddr1.toHexString} (Expect MISS)")
      cpuCmdQueue.enqueue { cmdPayload =>
        cmdPayload.address #= testAddr1
      }

      var memCmdFiresForMiss = 0
      val timeoutCyclesMiss = (simMemConfig.initialLatency + 10) * wordsPerLineCache + 100 // Generous timeout

      assert(
        !dut.clockDomain.waitSamplingWhere(timeoutCyclesMiss) {
          val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
          val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
          if (cmd_valid && cmd_ready) {
            memCmdFiresForMiss += 1
          }
          cpuRspBuffer.nonEmpty
        },
        s"Timeout: CPU did not receive response for MISS request (Addr: 0x${testAddr1.toHexString}). Memory commands fired: $memCmdFiresForMiss"
      )

      assert(cpuRspBuffer.nonEmpty, "CPU response buffer is empty after MISS phase.")
      val (rsp1_instr, rsp1_fault, rsp1_pc) = cpuRspBuffer.dequeue()
      println(s"SIM: MISS - Received instruction 0x${rsp1_instr.toString(16)} for PC 0x${rsp1_pc.toString(16)}")
      assert(!rsp1_fault, s"MISS request (Addr: 0x${testAddr1.toHexString}) resulted in a fault.")
      assert(
        rsp1_instr == testData1,
        s"MISS data mismatch. Expected 0x${testData1.toString(16)}, got 0x${rsp1_instr.toString(16)}"
      )
      assert(
        rsp1_pc == testAddr1,
        s"MISS PC mismatch. Expected 0x${testAddr1.toHexString}, got 0x${rsp1_pc.toString(16)}"
      )
      assert(
        memCmdFiresForMiss == wordsPerLineCache,
        s"MISS memory command count incorrect. Expected ${wordsPerLineCache}, got ${memCmdFiresForMiss}"
      )

      dut.clockDomain.waitSampling(10)

      // --- 2. Request testAddr2 (Expect Cache HIT) ---
      println(s"SIM: Requesting address 0x${testAddr2.toHexString} (Expect HIT)")
      cpuCmdQueue.enqueue { cmdPayload =>
        cmdPayload.address #= testAddr2
      }

      var memCmdFiresForHit = 0 // Reset counter for hit phase
      val timeoutCyclesHit = 50 // Hit should be much faster

      assert(
        !dut.clockDomain.waitSamplingWhere(timeoutCyclesHit) {
          val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
          val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
          if (cmd_valid && cmd_ready) { // This will count any memory fire, ensure it's 0 for hit
            memCmdFiresForHit += 1
          }
          cpuRspBuffer.nonEmpty
        },
        s"Timeout: CPU did not receive response for HIT request (Addr: 0x${testAddr2.toHexString}). Memory commands fired: $memCmdFiresForHit"
      )

      assert(cpuRspBuffer.nonEmpty, "CPU response buffer is empty after HIT phase.")
      val (rsp2_instr, rsp2_fault, rsp2_pc) = cpuRspBuffer.dequeue()
      println(s"SIM: HIT - Received instruction 0x${rsp2_instr.toString(16)} for PC 0x${rsp2_pc.toString(16)}")
      assert(!rsp2_fault, s"HIT request (Addr: 0x${testAddr2.toHexString}) resulted in a fault.")
      assert(
        rsp2_instr == testData2,
        s"HIT data mismatch. Expected 0x${testData2.toString(16)}, got 0x${rsp2_instr.toString(16)}"
      )
      assert(
        rsp2_pc == testAddr2,
        s"HIT PC mismatch. Expected 0x${testAddr2.toHexString}, got 0x${rsp2_pc.toString(16)}"
      )
      assert(memCmdFiresForHit == 0, s"HIT memory command count incorrect. Expected 0, got ${memCmdFiresForHit}")

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'SimpleICache - Miss then Hit on same line' finished.")
    }
  }

  test("SimpleICache - Line Replacement (Direct Mapped)") {
    val lineSizeBytes = 32
    val dataWidthBits = 32
    val wordsPerLineCache = lineSizeBytes / (dataWidthBits / 8)
    val numCacheLines = 4 // For this test, ensure at least 2 distinct line indices can be targeted
    // e.g., cacheSize = 128, bytePerLine = 32 => 4 lines, indexWidth = 2

    val cacheConfig = SimpleICacheConfig(
      cacheSize = numCacheLines * lineSizeBytes,
      bytePerLine = lineSizeBytes,
      addressWidth = 32,
      dataWidth = dataWidthBits
    )
    // With indexWidth = 2, line indices are 0, 1, 2, 3.
    // Addr 0x0000 -> Index 0
    // Addr 0x0020 -> Index 1
    // Addr 0x0040 -> Index 2
    // Addr 0x0080 -> Index 0 (Tag will be different from 0x0000)

    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32, dataWidth = dataWidthBits)
    val simMemConfig = SimulatedMemoryConfig(
      internalDataWidth = dataWidthBits,
      memSize = 16 KiB,
      initialLatency = 2
    )

    def tb = new SimpleICacheTestbench(cacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false
      dut.io.flushBus.cmd.payload.start #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[(BigInt, Boolean, BigInt)]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p =>
        cpuRspBuffer.enqueue((p.instruction.toBigInt, p.fault.toBoolean, p.pc.toBigInt))
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)
      StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain)

      // Addresses for testing (ensure they map to the same cache line index but have different tags)
      val addrA_line0_tag1 = 0x0000L // Maps to index 0, tag T1 (e.g., 0x0)
      val dataA = BigInt("AAAAAAAA", 16)
      // This address also maps to index 0, but has a different tag due to higher bits
      // For bytePerLine=32, indexWidth=2 (4 lines), byteOffsetWidth=5.
      // Tag bits start at bit 7. index bits are 6:5.
      // 0x0000 => Tag=0, Index=0
      // 0x0080 => Tag=4, Index=0 (0b100_00_xxxxx) (assuming addressWidth=32, tagWidth=27, 0x0080 >> 7 = 1)
      // Let's pick addresses further apart for clearer tag differences if tagWidth is small.
      // If numCacheLines=4, line size=32B, total size=128B.
      // Addresses 0x0000, 0x0080, 0x0100, 0x0180 etc. all map to index 0.
      val addrC_line0_tag2 = 0x0080L // Maps to index 0, tag T2 (e.g., 0x4 if tag starts at bit 7 for 32B lines)
      val dataC = BigInt("CCCCCCCC", 16)

      initSimulatedMemoryViaDUT(
        dut.memory.io,
        addrA_line0_tag1,
        dataA,
        simMemConfig.internalDataWidth,
        memBusConfig.dataWidth,
        dut.clockDomain
      )
      initSimulatedMemoryViaDUT(
        dut.memory.io,
        addrC_line0_tag2,
        dataC,
        simMemConfig.internalDataWidth,
        memBusConfig.dataWidth,
        dut.clockDomain
      )
      dut.clockDomain.waitSampling(5)

      var memCmdFires = 0
      def countMemCmdFires(timeout: Int, condition: => Boolean): Unit = {
        memCmdFires = 0
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout) {
            val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
            val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
            if (cmd_valid && cmd_ready) {
              memCmdFires += 1
            }
            condition
          },
          s"Timeout waiting. MemCmds: $memCmdFires"
        )
      }

      // 1. Request addrA (Line 0, Tag1) -> Miss
      println(s"SIM: Replacement - Req AddrA (0x${addrA_line0_tag1.toHexString}), expect MISS")
      cpuCmdQueue.enqueue(_.address #= addrA_line0_tag1)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rspA1_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rspA1_instr == dataA, "Data mismatch for AddrA first access")
      assert(
        memCmdFires == wordsPerLineCache,
        s"Mem cmds for AddrA first access: $memCmdFires, expected $wordsPerLineCache"
      )

      // 2. Request addrA (Line 0, Tag1) -> Hit
      println(s"SIM: Replacement - Req AddrA (0x${addrA_line0_tag1.toHexString}), expect HIT")
      cpuCmdQueue.enqueue(_.address #= addrA_line0_tag1)
      countMemCmdFires(50, cpuRspBuffer.nonEmpty)
      val (rspA2_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rspA2_instr == dataA, "Data mismatch for AddrA second access (hit)")
      assert(memCmdFires == 0, s"Mem cmds for AddrA second access (hit): $memCmdFires, expected 0")

      // 3. Request addrC (Line 0, Tag2) -> Miss (replaces Line 0 with Tag2)
      println(s"SIM: Replacement - Req AddrC (0x${addrC_line0_tag2.toHexString}), expect MISS (replacement)")
      cpuCmdQueue.enqueue(_.address #= addrC_line0_tag2)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rspC1_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rspC1_instr == dataC, "Data mismatch for AddrC first access (replacement)")
      assert(
        memCmdFires == wordsPerLineCache,
        s"Mem cmds for AddrC first access (replacement): $memCmdFires, expected $wordsPerLineCache"
      )

      // 4. Request addrA (Line 0, Tag1) -> Miss again (because Line 0 now holds Tag2)
      println(s"SIM: Replacement - Req AddrA (0x${addrA_line0_tag1.toHexString}), expect MISS (after replacement)")
      cpuCmdQueue.enqueue(_.address #= addrA_line0_tag1)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rspA3_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rspA3_instr == dataA, "Data mismatch for AddrA after AddrC replaced its line")
      assert(
        memCmdFires == wordsPerLineCache,
        s"Mem cmds for AddrA after AddrC (Miss): $memCmdFires, expected $wordsPerLineCache"
      )

      // 5. Request addrC (Line 0, Tag2) -> MISS (because Line 0 was replaced by AddrA in step 4)
      println(
        s"SIM: Replacement - Req AddrC (0x${addrC_line0_tag2.toHexString}), expect MISS (after AddrA replaced its line again)"
      )
      cpuCmdQueue.enqueue(_.address #= addrC_line0_tag2)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rspC2_instr, _, _) = cpuRspBuffer.dequeue()
      assert(
        rspC2_instr == dataC,
        "Data mismatch for AddrC after its line was replaced by AddrA and then AddrC re-fetched"
      )
      assert(
        memCmdFires == wordsPerLineCache,
        s"Mem cmds for AddrC re-fetch (Miss): $memCmdFires, expected $wordsPerLineCache"
      )

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'SimpleICache - Line Replacement (Direct Mapped)' finished.")
    }
  }

  test("SimpleICache - Flush Operation") {
    val lineSizeBytes = 32
    val dataWidthBits = 32
    val wordsPerLineCache = lineSizeBytes / (dataWidthBits / 8)
    val numCacheLines = 4

    val cacheConfig = SimpleICacheConfig(
      cacheSize = numCacheLines * lineSizeBytes,
      bytePerLine = lineSizeBytes,
      addressWidth = 32,
      dataWidth = dataWidthBits
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32, dataWidth = dataWidthBits)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = dataWidthBits, memSize = 8 KiB, initialLatency = 2)

    def tb = new SimpleICacheTestbench(cacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false
      dut.io.flushBus.cmd.payload.start #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[(BigInt, Boolean, BigInt)]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p =>
        cpuRspBuffer.enqueue((p.instruction.toBigInt, p.fault.toBoolean, p.pc.toBigInt))
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)
      StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain)

      val addrA = 0x1000L
      val dataA = BigInt("DDCCBBAA", 16)
      initSimulatedMemoryViaDUT(
        dut.memory.io,
        addrA,
        dataA,
        simMemConfig.internalDataWidth,
        memBusConfig.dataWidth,
        dut.clockDomain
      )
      dut.clockDomain.waitSampling(5)

      var memCmdFires = 0
      def countMemCmdFires(timeout: Int, condition: => Boolean): Unit = {
        memCmdFires = 0
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout) {
            val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
            val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
            if (cmd_valid && cmd_ready) {
              memCmdFires += 1
            }
            condition
          },
          s"Timeout waiting. MemCmds: $memCmdFires"
        )
      }

      // 1. Request addrA -> Miss, fill cache
      println(s"SIM: Flush - Req AddrA (0x${addrA.toHexString}), expect MISS")
      cpuCmdQueue.enqueue(_.address #= addrA)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      cpuRspBuffer.dequeue() // Consume response
      assert(memCmdFires == wordsPerLineCache, "Mem cmds for initial fill not as expected.")

      // 2. Request addrA -> Hit
      println(s"SIM: Flush - Req AddrA (0x${addrA.toHexString}), expect HIT")
      cpuCmdQueue.enqueue(_.address #= addrA)
      countMemCmdFires(50, cpuRspBuffer.nonEmpty)
      cpuRspBuffer.dequeue()
      assert(memCmdFires == 0, "Mem cmds for hit not as expected.")

      // 3. Perform Flush operation
      println("SIM: Flush - Initiating flush operation.")
      dut.io.flushBus.cmd.valid #= true
      dut.io.flushBus.cmd.payload.start #= true
      dut.clockDomain.waitSampling()
      dut.io.flushBus.cmd.valid #= false // Flow is typically single cycle
      // dut.io.flushBus.cmd.payload.start #= false // Optional for payload

      // Wait for flush done signal, timeout based on number of lines
      val flushTimeout = numCacheLines * 5 + 50 // 5 cycles per line넉넉하게
      assert(
        !dut.clockDomain.waitSamplingWhere(flushTimeout)(dut.io.flushBus.rsp.done.toBoolean),
        "Timeout: Flush operation did not complete (done signal not asserted)."
      )
      println("SIM: Flush - Flush operation reported done by DUT.")

      // Ensure done signal goes low after one cycle if it's a pulse (or stays high if level)
      // For now, just check it was asserted.

      // 4. Request addrA -> Should be Miss again
      println(s"SIM: Flush - Req AddrA (0x${addrA.toHexString}) post-flush, expect MISS")
      cpuCmdQueue.enqueue(_.address #= addrA)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rspA_postFlush_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rspA_postFlush_instr == dataA, "Data mismatch for AddrA post-flush.")
      assert(memCmdFires == wordsPerLineCache, "Mem cmds for post-flush access not as expected (should be a miss).")

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'SimpleICache - Flush Operation' finished.")
    }
  }

  test("SimpleICache - Sequential Access Within Line") {
    val lineSizeBytes = 32 // e.g., 8 words
    val dataWidthBits = 32
    val wordsPerLineCache = lineSizeBytes / (dataWidthBits / 8)

    val cacheConfig = SimpleICacheConfig(
      cacheSize = 128,
      bytePerLine = lineSizeBytes,
      addressWidth = 32,
      dataWidth = dataWidthBits
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32, dataWidth = dataWidthBits)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = dataWidthBits, memSize = 16 KiB, initialLatency = 2)

    def tb = new SimpleICacheTestbench(cacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false
      dut.io.flushBus.cmd.payload.start #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[(BigInt, Boolean, BigInt)]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p =>
        cpuRspBuffer.enqueue((p.instruction.toBigInt, p.fault.toBoolean, p.pc.toBigInt))
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)
      StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain)

      val baseAddr = 0x2000L
      val lineData = Array.tabulate(wordsPerLineCache)(i => BigInt(s"CAFE${i.toHexString.toUpperCase}00", 16) + i)

      // Initialize the entire line in memory
      for (i <- 0 until wordsPerLineCache) {
        initSimulatedMemoryViaDUT(
          dut.memory.io,
          baseAddr + i * (dataWidthBits / 8),
          lineData(i),
          simMemConfig.internalDataWidth,
          memBusConfig.dataWidth,
          dut.clockDomain
        )
      }
      dut.clockDomain.waitSampling(5)

      var memCmdFires = 0
      def countMemCmdFires(timeout: Int, condition: => Boolean): Unit = {
        memCmdFires = 0
        assert(
          !dut.clockDomain.waitSamplingWhere(timeout) {
            val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
            val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
            if (cmd_valid && cmd_ready) {
              memCmdFires += 1
            }
            condition
          },
          s"Timeout waiting. MemCmds: $memCmdFires"
        )
      }

      // 1. Request first word of the line -> Miss
      println(s"SIM: Sequential - Req Addr 0x${(baseAddr).toHexString} (word 0), expect MISS")
      cpuCmdQueue.enqueue(_.address #= baseAddr)
      countMemCmdFires(150, cpuRspBuffer.nonEmpty)
      val (rsp0_instr, _, _) = cpuRspBuffer.dequeue()
      assert(rsp0_instr == lineData(0), "Data mismatch for word 0 (miss)")
      assert(memCmdFires == wordsPerLineCache, "Mem cmds for initial line fill not as expected.")

      // 2. Request subsequent words in the line -> Hit
      for (i <- 1 until wordsPerLineCache) {
        val currentAddr = baseAddr + i * (dataWidthBits / 8)
        println(s"SIM: Sequential - Req Addr 0x${currentAddr.toHexString} (word $i), expect HIT")
        cpuCmdQueue.enqueue(_.address #= currentAddr)
        countMemCmdFires(50, cpuRspBuffer.nonEmpty)
        val (rsp_instr, _, pc) = cpuRspBuffer.dequeue()
        assert(
          rsp_instr == lineData(i),
          s"Data mismatch for word $i (hit). Expected ${lineData(i).toString(16)}, got ${rsp_instr
              .toString(16)} at PC ${pc.toString(16)}"
        )
        assert(memCmdFires == 0, s"Mem cmds for word $i (hit) not as expected (should be 0).")
      }

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'SimpleICache - Sequential Access Within Line' finished.")
    }
  }

  thatsAll
}
