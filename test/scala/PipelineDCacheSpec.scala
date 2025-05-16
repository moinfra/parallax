package parallax.test.scala // Or your preferred test package

import parallax.bus._
import parallax.components.dcache._ // Assuming PipelineDCache is here
import parallax.components.memory._
import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import scala.collection.mutable.Queue

class PipelineDCacheSpec extends CustomSpinalSimFunSuite {

  // --- Test Bench Component ---
  class PipelineDCacheTestbench(
      val dcacheCfg: PipelineDCacheConfig,
      val memBusCfg: GenericMemoryBusConfig, // For DCache <-> SimMem
      val simMemCfg: SimulatedMemoryConfig // For SimMem internal config
  ) extends Component {
    val io = new Bundle {
      // CPU-side interface for the testbench to drive/monitor
      val cpuBus = slave(DataMemoryAccessBus(dcacheCfg.cpuAddrWidth, dcacheCfg.cpuDataWidth))

      // (Optional) Flush interface if PipelineDCache implements it
      // val flush = master(SystemFlushBus())

      // For direct memory initialization via SimulatedMemory's test ports
      val simMemWriteEnable = in Bool ()
      val simMemWriteAddress = in UInt (memBusCfg.addressWidth bits)
      val simMemWriteData = in Bits (simMemCfg.internalDataWidth bits) // SimMem internal width

      // To monitor memory accesses from DCache to SimulatedMemory
      val dcacheMemCmdFire = out Bool ()
      val dcacheMemCmdAddr = out UInt (memBusCfg.addressWidth bits)
      val dcacheMemCmdIsWrite = out Bool ()
      val dcacheMemCmdData = out Bits (memBusCfg.dataWidth bits) // Monitor data being written to mem
      val dcacheMemRspFire = out Bool ()
    }

    val dcache = new PipelineDCache(dcacheCfg, memBusCfg)
    val memory = new SimulatedMemory(simMemCfg, memBusCfg)

    dcache.io.simPublic()
    // Connect DCache to SimulatedMemory
    // dcache.io.mem <> memory.io.bus

    // Connect Testbench IOs to DCache's CPU port
    dcache.io.cpu <> io.cpuBus // Master (io.cpuBus) to Slave (dcache.io.cpu)

    memory.io.simPublic()
    // Connect Testbench IOs for SimulatedMemory direct write
    memory.io.writeEnable := io.simMemWriteEnable // Corrected connection
    memory.io.writeAddress := io.simMemWriteAddress
    memory.io.writeData := io.simMemWriteData

    // Monitoring signals
    io.dcacheMemCmdFire := dcache.io.mem.cmd.fire
    io.dcacheMemCmdAddr := dcache.io.mem.cmd.payload.address
    // io.dcacheMemCmdIsWrite := dcache.io.mem.cmd.payload.isWrite
    io.dcacheMemCmdData := dcache.io.mem.cmd.payload.writeData
    io.dcacheMemRspFire := dcache.io.mem.rsp.fire

    // If PipelineDCache has a flush, connect it:
    // dcache.io.flush <> io.flush
  }

  // Helper for initializing SimulatedMemory (can reuse from ICacheSpec or adapt)
// Helper to initialize SimulatedMemory (same as in SimpleICacheSpec)
  def initSimulatedMemoryViaDUT( // Copied here for completeness
      dutMemoryIO: SimulatedMemory#IO,
      address: Long,
      value: BigInt,
      internalDataWidth: Int,
      busDataWidthForValue: Int,
      clockDomain: ClockDomain
  ): Unit = {
    val internalDataWidthBytes = internalDataWidth / 8
    val numInternalChunksPerBusWord = busDataWidthForValue / internalDataWidth

    require(
      busDataWidthForValue >= internalDataWidth,
      "Bus data width must be >= internal data width."
    )
    require(
      busDataWidthForValue % internalDataWidth == 0,
      "Bus data width must be a multiple of internal data width."
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

  // --- Test Case: Basic Read/Write Operations (WTNA) ---
  test("PipelineDCache - Basic Read Write (WTNA)") {
    val cpuAddrW = 32
    val cpuDataW = 32
    val lineSizeBytes = 32 // 8 words per line if cpuDataW is 32
    val cacheSizeBytes = 256 // 256 bytes => 8 lines

    val dcacheConfig = PipelineDCacheConfig(
      cacheSize = cacheSizeBytes,
      bytePerLine = lineSizeBytes,
      cpuAddrWidth = cpuAddrW,
      cpuDataWidth = cpuDataW,
      memDataWidth = cpuDataW // For WTNA, mem bus often matches cpu data width for writes
    )

    val memBusConfig = GenericMemoryBusConfig(
      addressWidth = cpuAddrW,
      dataWidth = cpuDataW // Matching DCache's memDataWidth
    )

    val simMemConfig = SimulatedMemoryConfig(
      internalDataWidth = cpuDataW, // Keep same for simplicity
      memSize = 8 KiB, // Sufficiently large
      initialLatency = 2 // Simulate some memory latency
    )

    // `def` for deferred instantiation
    def tb = new PipelineDCacheTestbench(dcacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave // Enable VCD/FST dumping
      .compile(tb)
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // --- Setup Drivers and Monitors ---
        val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
        StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)

        // Store (readData: BigInt, error: Boolean)
        val cpuRspBuffer = Queue[(BigInt, Boolean)]()
        StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
          cpuRspBuffer.enqueue((payload.readData.toBigInt, payload.error.toBoolean))
        }
        StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)
        StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain) // Randomize memory's cmd ready

        // Helper to send a CPU command and wait for response
        // Returns (readData, error)
        def sendCpuCmd(address: Long, isWrite: Boolean, writeData: BigInt = 0, size: Int = 2 /*word*/ )
            : (BigInt, Boolean) = {
          cpuCmdQueue.enqueue { cmd =>
            cmd.address #= address
            cmd.isWrite #= isWrite
            cmd.data #= writeData
            cmd.size #= size
          }
          // Wait for response, with a generous timeout
          val timeout = 200 + dcacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5)
          assert(
            !dut.clockDomain.waitSamplingWhere(timeout)(cpuRspBuffer.nonEmpty),
            s"Timeout waiting for CPU RSP for addr 0x${address.toHexString}"
          )
          cpuRspBuffer.dequeue()
        }

        // Helper to count dcache.io.mem.cmd.fire for a duration
        // and optionally check write data if it's a write
        class MemCmdMonitor {
          var fires = 0
          var lastWriteData: BigInt = -1
          var lastWriteAddr: Long = -1
          var lastIsWrite: Boolean = false

          def run(durationCycles: Int, watchAddress: Option[Long] = None, watchIsWrite: Option[Boolean] = None)
              : Unit = {
            fires = 0
            lastWriteData = -1
            lastWriteAddr = -1
            lastIsWrite = false
            for (_ <- 0 until durationCycles) {
              if (dut.io.dcacheMemCmdFire.toBoolean) {
                val currentAddr = dut.io.dcacheMemCmdAddr.toLong
                val currentIsWrite = dut.io.dcacheMemCmdIsWrite.toBoolean
                var countThisFire = true
                if (watchAddress.isDefined && watchAddress.get != currentAddr) countThisFire = false
                if (watchIsWrite.isDefined && watchIsWrite.get != currentIsWrite) countThisFire = false

                if (countThisFire) {
                  fires += 1
                  if (currentIsWrite) {
                    lastWriteData = dut.io.dcacheMemCmdData.toBigInt
                    lastWriteAddr = currentAddr
                    lastIsWrite = true
                  } else {
                    lastIsWrite = false
                  }
                }
              }
              dut.clockDomain.waitSampling()
            }
          }
        }
        val memCmdMonitor = new MemCmdMonitor()

        // --- Test Sequence ---
        val addrA = 0x1000L
        val dataD1 = BigInt("AABBCCDD", 16)
        val dataD4 = BigInt("DEADBEEF", 16)

        val addrB = 0x2000L // Different line
        val dataD2 = BigInt("11223344", 16)
        initSimulatedMemoryViaDUT(
          dut.memory.io,
          addrB,
          dataD2,
          simMemConfig.internalDataWidth,
          memBusConfig.dataWidth,
          dut.clockDomain
        )

        val addrC = 0x3000L // Another different line
        val dataD3 = BigInt("CAFEBABE", 16)

        // 1. Write D1 to AddrA (Write-Through, No-Allocate initially if AddrA not in cache)
        // Since it's WTNA and cache is empty, this is like a Write Miss.
        // It should write to memory. Cache state for AddrA might remain invalid or get updated depending on exact WTNA.
        // For simplest WTNA (write-around on miss): only memory is written.
        // For WTNA (write-allocate on miss - less common for WTNA): line is fetched, then written.
        // Let's assume the "write-around" for WTNA miss for now.
        // If DCache *does* allocate on write miss and then writes through, that's also a valid WTNA variant.
        // Our PipelineDCache S2 logic for Write Miss (WTNA) currently does:
        //   s2_compare_execute(MEM_REQ_IS_NEEDED) := True (to memory)
        //   It does *not* update tag/valid/data arrays. This is "write-around".
        println(
          s"SIM: 1. Write D1 (0x${dataD1.toString(16)}) to AddrA (0x${addrA.toHexString}) - Expect WTNA (write-around)"
        )
        val (_, writeError1) = sendCpuCmd(addrA, isWrite = true, writeData = dataD1)
        assert(!writeError1, "Error during initial write to AddrA")

        memCmdMonitor.run(
          durationCycles = simMemConfig.initialLatency + 10,
          watchAddress = Some(addrA),
          watchIsWrite = Some(true)
        )
        assert(memCmdMonitor.fires == 1, s"Expected 1 memory write for WTNA to AddrA, got ${memCmdMonitor.fires}")
        assert(
          memCmdMonitor.lastWriteData == dataD1,
          s"Memory write data mismatch for AddrA. Expected ${dataD1.toString(16)}, got ${memCmdMonitor.lastWriteData.toString(16)}"
        )
        println(s"SIM: AddrA written to memory. Mem Fires: ${memCmdMonitor.fires}")

        // 2. Read from AddrA.
        // If WTNA was write-around, this is a Read Miss. Line will be fetched. D1 from memory.
        // If WTNA allocated and wrote, this is a Read Hit. D1 from cache.
        // Our current S2 logic for Write Miss does NOT allocate. So this will be a Read Miss.
        println(s"SIM: 2. Read from AddrA (0x${addrA.toHexString}) - Expect Read Miss, fetch D1")
        val (readDataA1, readErrorA1) = sendCpuCmd(addrA, isWrite = false)
        assert(!readErrorA1, "Error during first read from AddrA")
        assert(
          readDataA1 == dataD1,
          s"Read after write mismatch for AddrA. Expected ${dataD1.toString(16)}, got ${readDataA1.toString(16)}"
        )

        memCmdMonitor.run(
          durationCycles = (dcacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5)),
          watchAddress = Some(addrA & ~(lineSizeBytes - 1)),
          watchIsWrite = Some(false)
        ) // Watch line base addr for reads
        assert(
          memCmdMonitor.fires == dcacheConfig.wordsPerLine,
          s"Expected ${dcacheConfig.wordsPerLine} memory reads for AddrA line fill, got ${memCmdMonitor.fires}"
        )
        println(s"SIM: AddrA read (miss). Mem Read Fires for line: ${memCmdMonitor.fires}")

        // 3. Read from AddrA again (Now should be a Read Hit)
        println(s"SIM: 3. Read from AddrA (0x${addrA.toHexString}) - Expect Read Hit")
        val (readDataA2, readErrorA2) = sendCpuCmd(addrA, isWrite = false)
        assert(!readErrorA2, "Error during second read from AddrA")
        assert(
          readDataA2 == dataD1,
          s"Second read mismatch for AddrA. Expected ${dataD1.toString(16)}, got ${readDataA2.toString(16)}"
        )

        memCmdMonitor.run(durationCycles = 10, watchIsWrite = Some(false)) // Check for any memory reads
        assert(memCmdMonitor.fires == 0, s"Expected 0 memory reads for AddrA hit, got ${memCmdMonitor.fires}")
        println(s"SIM: AddrA read (hit). No new memory reads.")

        // 4. Read from AddrB (pre-initialized in memory) - Read Miss
        println(s"SIM: 4. Read from AddrB (0x${addrB.toHexString}) - Expect Read Miss, fetch D2")
        val (readDataB1, readErrorB1) = sendCpuCmd(addrB, isWrite = false)
        assert(!readErrorB1, "Error during read from AddrB")
        assert(
          readDataB1 == dataD2,
          s"Read mismatch for AddrB. Expected ${dataD2.toString(16)}, got ${readDataB1.toString(16)}"
        )

        memCmdMonitor.run(
          durationCycles = (dcacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5)),
          watchAddress = Some(addrB & ~(lineSizeBytes - 1)),
          watchIsWrite = Some(false)
        )
        assert(
          memCmdMonitor.fires == dcacheConfig.wordsPerLine,
          s"Expected ${dcacheConfig.wordsPerLine} memory reads for AddrB line fill, got ${memCmdMonitor.fires}"
        )
        println(s"SIM: AddrB read (miss). Mem Read Fires for line: ${memCmdMonitor.fires}")

        // 5. Write D4 to AddrA (now in cache) - Write Hit (WTNA)
        // Cache and memory should be updated.
        println(
          s"SIM: 5. Write D4 (0x${dataD4.toString(16)}) to AddrA (0x${addrA.toHexString}) - Expect Write Hit (WTNA)"
        )
        val (_, writeErrorA4) = sendCpuCmd(addrA, isWrite = true, writeData = dataD4)
        assert(!writeErrorA4, "Error during write D4 to AddrA")

        memCmdMonitor.run(
          durationCycles = simMemConfig.initialLatency + 10,
          watchAddress = Some(addrA),
          watchIsWrite = Some(true)
        )
        assert(memCmdMonitor.fires == 1, s"Expected 1 memory write for WTNA hit to AddrA, got ${memCmdMonitor.fires}")
        assert(
          memCmdMonitor.lastWriteData == dataD4,
          s"Memory write data mismatch for D4@AddrA. Expected ${dataD4.toString(16)}, got ${memCmdMonitor.lastWriteData.toString(16)}"
        )
        println(s"SIM: AddrA updated with D4 (hit). Mem Write Fires: ${memCmdMonitor.fires}")

        // 6. Read from AddrA again (Should be D4, Hit)
        println(s"SIM: 6. Read from AddrA (0x${addrA.toHexString}) - Expect Read Hit (D4)")
        val (readDataA3, readErrorA3) = sendCpuCmd(addrA, isWrite = false)
        assert(!readErrorA3, "Error during read D4 from AddrA")
        assert(
          readDataA3 == dataD4,
          s"Read D4 mismatch for AddrA. Expected ${dataD4.toString(16)}, got ${readDataA3.toString(16)}"
        )

        memCmdMonitor.run(durationCycles = 10, watchIsWrite = Some(false))
        assert(memCmdMonitor.fires == 0, s"Expected 0 memory reads for AddrA hit (D4), got ${memCmdMonitor.fires}")
        println(s"SIM: AddrA read (hit D4). No new memory reads.")

        // 7. Write to AddrC (Write Miss - WTNA, write-around)
        println(
          s"SIM: 7. Write D3 (0x${dataD3.toString(16)}) to AddrC (0x${addrC.toHexString}) - Expect Write Miss (WTNA, write-around)"
        )
        val (_, writeErrorC3) = sendCpuCmd(addrC, isWrite = true, writeData = dataD3)
        assert(!writeErrorC3, "Error during write to AddrC")

        memCmdMonitor.run(
          durationCycles = simMemConfig.initialLatency + 10,
          watchAddress = Some(addrC),
          watchIsWrite = Some(true)
        )
        assert(memCmdMonitor.fires == 1, s"Expected 1 memory write for WTNA miss to AddrC, got ${memCmdMonitor.fires}")
        assert(
          memCmdMonitor.lastWriteData == dataD3,
          s"Memory write data mismatch for AddrC. Expected ${dataD3.toString(16)}, got ${memCmdMonitor.lastWriteData.toString(16)}"
        )
        println(s"SIM: AddrC written to memory (miss). Mem Write Fires: ${memCmdMonitor.fires}")

        // 8. Read from AddrC (Read Miss, fetch D3 from memory)
        println(s"SIM: 8. Read from AddrC (0x${addrC.toHexString}) - Expect Read Miss, fetch D3")
        val (readDataC1, readErrorC1) = sendCpuCmd(addrC, isWrite = false)
        assert(!readErrorC1, "Error during read from AddrC")
        assert(
          readDataC1 == dataD3,
          s"Read mismatch for AddrC. Expected ${dataD3.toString(16)}, got ${readDataC1.toString(16)}"
        )

        memCmdMonitor.run(
          durationCycles = (dcacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5)),
          watchAddress = Some(addrC & ~(lineSizeBytes - 1)),
          watchIsWrite = Some(false)
        )
        assert(
          memCmdMonitor.fires == dcacheConfig.wordsPerLine,
          s"Expected ${dcacheConfig.wordsPerLine} memory reads for AddrC line fill, got ${memCmdMonitor.fires}"
        )
        println(s"SIM: AddrC read (miss). Mem Read Fires for line: ${memCmdMonitor.fires}")

        // TODO: Add tests for partial writes (byte/half-word)

        dut.clockDomain.waitSampling(50)
        println("SIM: Basic Read Write Test Finished.")
      }
  }
  thatsAll
}
