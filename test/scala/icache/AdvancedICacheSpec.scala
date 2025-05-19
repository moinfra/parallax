package parallax.test.scala.icache

import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}

import parallax.components.icache._
import parallax.components.memory._

class AdvancedICacheSpec extends CustomSpinalSimFunSuite {

  // --- Testbench Component ---
  class AdvancedICacheTestbench(
      val cacheCfg: AdvancedICacheConfig,
      val memBusCfg: GenericMemoryBusConfig, // 使用 GenericMemoryBusConfig
      val simMemCfg: SimulatedMemoryConfig,
      val dutEnableLog: Boolean = true // 控制 DUT 内部日志
  ) extends Component {
    val io = new Bundle {
      val cpuBus = slave(AdvancedICacheCpuBus()(cacheCfg))
      val flushBus = slave(AdvancedICacheFlushBus())
    }

    val icache = new AdvancedICache()(cacheCfg, memBusCfg, dutEnableLog)
    val memory = new SimulatedSplitGeneralMemory(simMemCfg, memBusCfg, dutEnableLog) // 使用 SplitGMB 版本

    // 连接
    icache.io.mem <> memory.io.bus // SplitGMB 连接
    icache.io.cpu <> io.cpuBus
    icache.io.flush <> io.flushBus
  }

  // 辅助函数：通过 SimulatedSplitGeneralMemory 的直接写端口初始化内存
  def initSimulatedMemory(
      dutMemoryIO: SimulatedSplitGeneralMemory#IO,
      address: Long,
      data: BigInt, // 数据宽度与 memBusCfg.dataWidth 一致
      clockDomain: ClockDomain,
      internalDataWidthBytes: Int // 新增参数
  ): Unit = {
    // SimulatedSplitGeneralMemory 的直接写端口写入的是 internalDataWidth 块
    // 而我们通常传入的是总线宽度的数据。这里需要分解。
    val busWidthBytes = dutMemoryIO.bus.config.dataWidth.value / 8
    val numInternalChunks = busWidthBytes / internalDataWidthBytes

    require(busWidthBytes % internalDataWidthBytes == 0, "Bus width must be multiple of internal mem width for init helper")

    for (i <- 0 until numInternalChunks) {
      val chunkAddr = address + i * internalDataWidthBytes
      val chunkData = (data >> (i * internalDataWidthBytes * 8)) & ((BigInt(1) << (internalDataWidthBytes * 8)) - 1)

      dutMemoryIO.writeAddress #= chunkAddr
      dutMemoryIO.writeData #= chunkData
      dutMemoryIO.writeEnable #= true
      clockDomain.waitSampling()
    }
    dutMemoryIO.writeEnable #= false
    clockDomain.waitSampling() // 确保写操作完成
  }


  // --- 测试用例 ---

  // T1.1: 单字 Miss 然后 Hit (窄取指)
  testOnly("T1.1 - Single Word Miss then Hit (Narrow Fetch)") {
    val lineSizeBytes = 32
    val coreDataWidth = 32 bits

    // 配置: fetchDataWidth == dataWidth
    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = 128, // 4 lines for simplicity
      bytePerLine = lineSizeBytes,
      wayCount = 1, // Direct mapped for this basic test
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth // 窄取指
    )
    val memBusConfig = GenericMemoryBusConfig(
        addressWidth = cacheConfig.addressWidth,
        dataWidth = cacheConfig.dataWidth, // 内存总线数据宽度与核心数据宽度一致
        useId = false)
    val simMemConfig = SimulatedMemoryConfig(
        internalDataWidth = cacheConfig.dataWidth, // 模拟内存内部字宽
        memSize = 1 KiB,
        initialLatency = 2) // 内存延迟

    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false // 初始化 flush 信号

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)

      val cpuRspBuffer = mutable.Queue[AdvancedICacheCpuRsp]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        cpuRspBuffer.enqueue(payload.copy()) // 复制 payload
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      // 监控内存总线读命令的次数
      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize() // 内存也随机 ready

      // 手动监控 onFire 条件
      fork {
        while(true) {
          dut.clockDomain.waitSampling()
          if (dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean) {
            memReadCmdFires += 1
          }
        }
      }


      val testAddr = 0x100L
      val testData = BigInt("AABBCCDD", 16)
      initSimulatedMemory(dut.memory.io, testAddr, testData, dut.clockDomain, internalMemDataWidthBytes)
      dut.clockDomain.waitSampling(5)

      // 1. 请求 testAddr (预期 MISS)
      println(s"SIM [T1.1]: 请求地址 0x${testAddr.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      assert(!dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty), s"CPU 响应队列在 MISS 后为空 (timeout $missTimeout)")

      val rsp1 = cpuRspBuffer.dequeue()
      println(s"SIM [T1.1]: MISS - 收到指令 ${rsp1.instructions(0).toBigInt.toString(16)} PC ${rsp1.pc.toBigInt.toString(16)}")
      assert(!rsp1.fault.toBoolean, "MISS 请求不应产生 fault")
      assert(rsp1.instructions(0).toBigInt == testData, s"MISS 数据不匹配. 预期 0x${testData.toString(16)}, 得到 0x${rsp1.instructions(0).toBigInt.toString(16)}")
      assert(rsp1.pc.toBigInt == testAddr, "MISS PC 不匹配")
      assert(memReadCmdFires == wordsPerLine, s"MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0 // 重置计数器

      // 2. 再次请求 testAddr (预期 HIT)
      println(s"SIM [T1.1]: 再次请求地址 0x${testAddr.toHexString} (预期 HIT)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val hitTimeout = 50
      assert(!dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty), s"CPU 响应队列在 HIT 后为空 (timeout $hitTimeout)")

      val rsp2 = cpuRspBuffer.dequeue()
      println(s"SIM [T1.1]: HIT - 收到指令 ${rsp2.instructions(0).toBigInt.toString(16)} PC ${rsp2.pc.toBigInt.toString(16)}")
      assert(!rsp2.fault.toBoolean, "HIT 请求不应产生 fault")
      assert(rsp2.instructions(0).toBigInt == testData, s"HIT 数据不匹配. 预期 0x${testData.toString(16)}, 得到 0x${rsp2.instructions(0).toBigInt.toString(16)}")
      assert(rsp2.pc.toBigInt == testAddr, "HIT PC 不匹配")
      assert(memReadCmdFires == 0, s"HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.1]: 测试 'Single Word Miss then Hit (Narrow Fetch)' 完成.")
    }
  }


  // T1.3: 宽取指 Miss 然后 Hit
  test("T1.3 - Wide Fetch Miss then Hit") {
    val lineSizeBytes = 64 // 确保行足够大以容纳宽取指
    val coreDataWidth = 32 bits
    val fetchWidth = 64 bits // 宽取指，例如取 2 个 32 位指令

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = 256, // 4 lines
      bytePerLine = lineSizeBytes,
      wayCount = 1,    // Direct mapped
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = fetchWidth
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false) // 内存总线仍然是 coreDataWidth
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 3)

    val wordsPerLine = cacheConfig.wordsPerLine
    val fetchWords = cacheConfig.fetchWordsPerFetchGroup // 应为 2
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8


    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[AdvancedICacheCpuRsp]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p => cpuRspBuffer.enqueue(p.copy()) }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while(true) {
          dut.clockDomain.waitSampling()
          if (dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean) {
            memReadCmdFires += 1
          }
        }
      }

      val testAddr = 0x200L // 确保地址对齐到 fetchDataWidth (这里是 8 bytes 对齐)
      val testDataGroup = Array(BigInt("11223344", 16), BigInt("AABBCCDD", 16)) // 两个 32 位数据

      // 初始化内存
      for (i <- 0 until fetchWords) {
        initSimulatedMemory(dut.memory.io, testAddr + i * (coreDataWidth.value / 8), testDataGroup(i), dut.clockDomain, internalMemDataWidthBytes)
      }
      dut.clockDomain.waitSampling(5)

      // 1. 请求 testAddr (预期 MISS)
      println(s"SIM [T1.3]: 请求地址 0x${testAddr.toHexString} (预期 MISS, 宽取指)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 100
      assert(!dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty), s"CPU 响应队列在宽取指 MISS 后为空 (timeout $missTimeout)")

      val rsp1 = cpuRspBuffer.dequeue()
      println(s"SIM [T1.3]: MISS - PC ${rsp1.pc.toBigInt.toString(16)}")
      assert(!rsp1.fault.toBoolean, "宽取指 MISS 不应产生 fault")
      for (i <- 0 until fetchWords) {
        println(s"  Instruction[${i}]: ${rsp1.instructions(i).toBigInt.toString(16)}")
        assert(rsp1.instructions(i).toBigInt == testDataGroup(i), s"宽取指 MISS 数据[${i}]不匹配.")
      }
      assert(rsp1.pc.toBigInt == testAddr, "宽取指 MISS PC 不匹配")
      assert(memReadCmdFires == wordsPerLine, s"宽取指 MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0

      // 2. 再次请求 testAddr (预期 HIT)
      println(s"SIM [T1.3]: 再次请求地址 0x${testAddr.toHexString} (预期 HIT, 宽取指)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val hitTimeout = 50
      assert(!dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty), s"CPU 响应队列在宽取指 HIT 后为空 (timeout $hitTimeout)")

      val rsp2 = cpuRspBuffer.dequeue()
      println(s"SIM [T1.3]: HIT - PC ${rsp2.pc.toBigInt.toString(16)}")
      assert(!rsp2.fault.toBoolean, "宽取指 HIT 不应产生 fault")
      for (i <- 0 until fetchWords) {
        println(s"  Instruction[${i}]: ${rsp2.instructions(i).toBigInt.toString(16)}")
        assert(rsp2.instructions(i).toBigInt == testDataGroup(i), s"宽取指 HIT 数据[${i}]不匹配.")
      }
      assert(rsp2.pc.toBigInt == testAddr, "宽取指 HIT PC 不匹配")
      assert(memReadCmdFires == 0, s"宽取指 HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.3]: 测试 'Wide Fetch Miss then Hit' 完成.")
    }
  }


  // T2.2: LRU 替换
  test("T2.2 - LRU Replacement") {
    val lineSizeBytes = 32
    val coreDataWidth = 32 bits
    val ways = 2 // 测试 2 路组相联

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = ways * lineSizeBytes * 2, // 例如, 每个组有 'ways' 路, 总共 2 个组
                                            // 2路 * 32B/路 * 2组 = 128B
      bytePerLine = lineSizeBytes,
      wayCount = ways,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth // 窄取指简化 LRU 验证
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 2)

    val wordsPerLine = cacheConfig.wordsPerLine
    val setCount = cacheConfig.setCount // 应为 2
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[AdvancedICacheCpuRsp]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p => cpuRspBuffer.enqueue(p.copy()) }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while(true) {
          dut.clockDomain.waitSampling()
          if (dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean) {
            memReadCmdFires += 1
          }
        }
      }

      // 地址选择: 确保它们映射到同一个组 (set)
      // 对于 setCount=2, lineSize=32B (byteOffsetWidth=5), setIndexWidth=1.
      // setIndex 是 bit 5.
      // Addr 0x000 (Set 0), Addr 0x020 (Set 1), Addr 0x040 (Set 0), Addr 0x060 (Set 1)
      val targetSetIdx = 0
      val addr_set0_tagA = 0x000L // Set 0, Tag A
      val data_tagA = BigInt("AAAAAAAA", 16)
      val addr_set0_tagB = 0x040L // Set 0, Tag B (0x040 >> 6 = 1, 0x000 >> 6 = 0, assuming tag starts after set index)
                                  // Corrected: tag = addr[31:setIdxWidth+byteOffsetWidth]
                                  // Addr 0x000: Tag = 0, SetIdx = 0
                                  // Addr 0x040: Tag = (0x040 >> (1+5)) = (64 >> 6) = 1. SetIdx = (0x040 >> 5) & 1 = (2 & 1) = 0.
      val data_tagB = BigInt("BBBBBBBB", 16)
      val addr_set0_tagC = 0x080L // Set 0, Tag C (0x080 >> 6 = 2)
      val data_tagC = BigInt("CCCCCCCC", 16)

      initSimulatedMemory(dut.memory.io, addr_set0_tagA, data_tagA, dut.clockDomain, internalMemDataWidthBytes)
      initSimulatedMemory(dut.memory.io, addr_set0_tagB, data_tagB, dut.clockDomain, internalMemDataWidthBytes)
      initSimulatedMemory(dut.memory.io, addr_set0_tagC, data_tagC, dut.clockDomain, internalMemDataWidthBytes)
      dut.clockDomain.waitSampling(5)

      def expectMiss(addr: Long, expectedData: BigInt, desc: String): Unit = {
        println(s"SIM [T2.2]: $desc - 请求地址 0x${addr.toHexString} (预期 MISS)")
        memReadCmdFires = 0
        cpuCmdQueue.enqueue(_.address #= addr)
        val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
        assert(!dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty), s"$desc - CPU 响应队列为空 (timeout $missTimeout)")
        val rsp = cpuRspBuffer.dequeue()
        assert(rsp.instructions(0).toBigInt == expectedData, s"$desc - 数据不匹配")
        assert(memReadCmdFires == wordsPerLine, s"$desc - 内存读命令次数不正确 (预期 ${wordsPerLine}, 得到 ${memReadCmdFires})")
      }
      def expectHit(addr: Long, expectedData: BigInt, desc: String): Unit = {
        println(s"SIM [T2.2]: $desc - 请求地址 0x${addr.toHexString} (预期 HIT)")
        memReadCmdFires = 0
        cpuCmdQueue.enqueue(_.address #= addr)
        val hitTimeout = 50
        assert(!dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty), s"$desc - CPU 响应队列为空 (timeout $hitTimeout)")
        val rsp = cpuRspBuffer.dequeue()
        assert(rsp.instructions(0).toBigInt == expectedData, s"$desc - 数据不匹配")
        assert(memReadCmdFires == 0, s"$desc - 内存读命令次数不正确 (预期 0, 得到 ${memReadCmdFires})")
      }

      // 1. Fill way 0 of set 0 with TagA
      expectMiss(addr_set0_tagA, data_tagA, "填充 Way 0 (TagA)")
      // 此时 TagA 在 Way0, Age=0 (MRU)

      // 2. Fill way 1 of set 0 with TagB
      expectMiss(addr_set0_tagB, data_tagB, "填充 Way 1 (TagB)")
      // 此时 TagB 在 Way1, Age=0 (MRU)
      // TagA 在 Way0, Age=1 (LRU)

      // 3. Request TagA (expect HIT, TagA becomes MRU)
      expectHit(addr_set0_tagA, data_tagA, "命中 TagA")
      // 此时 TagA 在 Way0, Age=0 (MRU)
      // TagB 在 Way1, Age=1 (LRU)

      // 4. Request TagC (expect MISS, replaces TagB which was LRU)
      expectMiss(addr_set0_tagC, data_tagC, "请求 TagC, 替换 TagB")
      // 此时 TagC 在 Way1, Age=0 (MRU)
      // TagA 在 Way0, Age=1 (LRU)

      // 5. Request TagB (expect MISS, it was evicted)
      expectMiss(addr_set0_tagB, data_tagB, "请求 TagB (已被替换)")

      dut.clockDomain.waitSampling(20)
      println("SIM [T2.2]: 测试 'LRU Replacement' 完成.")
    }
  }

  // T3.2: Flush 部分/完全填充的缓存
  test("T3.2 - Flush Partially Filled Cache") {
    val lineSizeBytes = 32
    val coreDataWidth = 32 bits
    val ways = 2

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = ways * lineSizeBytes * 2, // 128B
      bytePerLine = lineSizeBytes,
      wayCount = ways,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 1)
    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[AdvancedICacheCpuRsp]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p => cpuRspBuffer.enqueue(p.copy()) }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while(true) {
          dut.clockDomain.waitSampling()
          if (dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean) {
            memReadCmdFires += 1
          }
        }
      }

      val addr1_set0 = 0x000L
      val data1 = BigInt("11110000", 16)
      val addr2_set1 = 0x020L // Different set
      val data2 = BigInt("22220000", 16)

      initSimulatedMemory(dut.memory.io, addr1_set0, data1, dut.clockDomain, internalMemDataWidthBytes)
      initSimulatedMemory(dut.memory.io, addr2_set1, data2, dut.clockDomain, internalMemDataWidthBytes)
      dut.clockDomain.waitSampling(5)

      // Fill some cache lines
      println(s"SIM [T3.2]: 填充缓存 - Addr1 0x${addr1_set0.toHexString}")
      cpuCmdQueue.enqueue(_.address #= addr1_set0)
      assert(!dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty), "填充 Addr1: CPU 响应队列为空 (timeout 100)"); cpuRspBuffer.dequeue()

      println(s"SIM [T3.2]: 填充缓存 - Addr2 0x${addr2_set1.toHexString}")
      cpuCmdQueue.enqueue(_.address #= addr2_set1)
      assert(!dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty), "填充 Addr2: CPU 响应队列为空 (timeout 100)"); cpuRspBuffer.dequeue()

      dut.clockDomain.waitSampling(5) // Let fills complete

      // Perform Flush
      println("SIM [T3.2]: 执行 Flush 操作")
      dut.io.flushBus.cmd.valid #= true
      dut.io.flushBus.cmd.payload.start #= true
      dut.io.flushBus.rsp.ready #= true // Testbench is ready for flush response

      dut.clockDomain.waitSampling()
      dut.io.flushBus.cmd.valid #= false // cmd is usually a pulse

      // Wait for flush done
      // Timeout: setCount * wayCount * (cycles_per_invalidation_step) + buffer
      val flushTimeout = cacheConfig.setCount * cacheConfig.wayCount * 5 + 50
      assert(!dut.clockDomain.waitSamplingWhere(flushTimeout)(dut.io.flushBus.rsp.valid.toBoolean && dut.io.flushBus.rsp.payload.done.toBoolean), s"Flush 操作未完成或未发出 done 信号 (timeout $flushTimeout)")
      println("SIM [T3.2]: Flush 操作由 DUT 报告完成.")
      // Consume the flush response if it's a stream
      // If rsp.valid is high, it means it's ready to be consumed.
      // In a real system, the master of the flush bus would deassert ready after consumption.
      // For this test, we just check it was asserted. If it's a stream, we might need to manage ready.
      // Assuming flush rsp is a simple signal or a stream that gets consumed quickly.

      dut.clockDomain.waitSampling(5) // Allow flush to fully propagate

      // Request previously cached addresses, expect MISSes
      memReadCmdFires = 0
      println(s"SIM [T3.2]: 请求 Addr1 0x${addr1_set0.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= addr1_set0)
      assert(!dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty), "Addr1 post-flush: CPU 响应队列为空 (timeout 100)")
      val rsp_addr1 = cpuRspBuffer.dequeue()
      assert(rsp_addr1.instructions(0).toBigInt == data1, "Addr1 post-flush: 数据不匹配")
      assert(memReadCmdFires == wordsPerLine, s"Addr1 post-flush: 内存读命令次数应为 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      memReadCmdFires = 0
      println(s"SIM [T3.2]: 请求 Addr2 0x${addr2_set1.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= addr2_set1)
      assert(!dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty), "Addr2 post-flush: CPU 响应队列为空 (timeout 100)")
      val rsp_addr2 = cpuRspBuffer.dequeue()
      assert(rsp_addr2.instructions(0).toBigInt == data2, "Addr2 post-flush: 数据不匹配")
      assert(memReadCmdFires == wordsPerLine, s"Addr2 post-flush: 内存读命令次数应为 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T3.2]: 测试 'Flush Partially Filled Cache' 完成.")
    }
  }

  // T4.3: 尝试取指跨越行边界 (预期 Fault/Error)
  test("T4.3 - Fetch Group Crossing Line Boundary") {
    val lineSizeBytes = 32
    val coreDataWidth = 32 bits
    val fetchWidth = 64 bits // 2 words

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = 128,
      bytePerLine = lineSizeBytes, // 32 bytes
      wayCount = 1,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = fetchWidth // 64 bits = 8 bytes
    )
    // Line boundary at 0x...00, 0x...20, 0x...40, etc.
    // A fetch group is 8 bytes.
    // If we request addr = 0x1C (decimal 28), the 8-byte group is [0x1C, 0x23].
    // This crosses the line boundary at 0x20.
    val testAddrCross = 0x1CL

    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 1)
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8


    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[AdvancedICacheCpuRsp]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { p => cpuRspBuffer.enqueue(p.copy()) }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      // (Optional) Initialize memory around the boundary if we expect a miss path before fault
      // initSimulatedMemory(dut.memory.io, testAddrCross, BigInt("DEADBEEF",16), dut.clockDomain, internalMemDataWidthBytes)
      // initSimulatedMemory(dut.memory.io, testAddrCross + 4, BigInt("CAFEF00D",16), dut.clockDomain, internalMemDataWidthBytes)

      println(s"SIM [T4.3]: 请求跨行边界地址 0x${testAddrCross.toHexString}")
      cpuCmdQueue.enqueue(_.address #= testAddrCross)

      // 缓存应该在 sIdle 状态检测到这个问题并立即响应 fault (或进入错误状态)
      // 它不应该尝试去内存取数据。
      val faultTimeout = 20
      assert(!dut.clockDomain.waitSamplingWhere(faultTimeout)(cpuRspBuffer.nonEmpty), s"跨行边界取指: CPU 响应队列为空 (timeout $faultTimeout)")

      assert(cpuRspBuffer.nonEmpty, "跨行边界取指: CPU 响应队列为空") // Double check after timeout
      val rsp = cpuRspBuffer.dequeue()
      println(s"SIM [T4.3]: 收到响应 PC ${rsp.pc.toBigInt.toString(16)}, Fault=${rsp.fault.toBoolean}")

      assert(rsp.fault.toBoolean, "跨行边界取指应产生 fault")
      assert(rsp.pc.toBigInt == testAddrCross, "跨行边界取指 fault 的 PC 不匹配")
      // 验证指令部分可以是未定义或特定错误码, 这里不严格检查
      // assert(dut.icache.fsm.is(dut.icache.fsm.sIdle), "Cache FSM should return to Idle or an error state") // 检查 FSM 状态 (如果可访问)

      dut.clockDomain.waitSampling(10)
      println("SIM [T4.3]: 测试 'Fetch Group Crossing Line Boundary' 完成.")
    }
  }

  // 可以添加更多测试用例...
  // T1.2: Sequential Word Access Within Line (Narrow Fetch)
  // T1.4: Wide Fetch Sequential Access Within Line
  // T2.1: Filling All Ways in a Set, then Hit
  // T2.3: LRU Hit Promotion
  // T3.1: Flush Empty Cache
  // T4.1: Access to Uninitialized Memory (Memory Fault) - 需要 SimulatedMemory 支持错误注入
  // T4.2: Back-to-Back Requests (Pipelining/Stall) - 已经通过 StreamReadyRandomizer 部分覆盖

  // `thatsAll` 应该在所有测试用例之后
  thatsAll
}
