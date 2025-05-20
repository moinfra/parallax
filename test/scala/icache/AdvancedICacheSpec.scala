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

  // 定义一个临时的容器类来存储 CPU 响应数据
  case class CpuRspData(
      instructions: Seq[BigInt],
      pc: BigInt,
      fault: Boolean
  )

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

    val icache = new AdvancedICache()(cacheCfg, memBusCfg, false)
    val memory = new SimulatedSplitGeneralMemory(simMemCfg, memBusCfg, /*dutEnableLog*/ false) // 使用 SplitGMB 版本
    // val memory = new SimulatedSplitGeneralMemory(simMemCfg, memBusCfg, /*dutEnableLog*/ true) // 使用 SplitGMB 版本

    memory.io.simPublic()
    icache.io.simPublic()
    io.cpuBus.simPublic()
    io.flushBus.simPublic()

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

    require(
      busWidthBytes % internalDataWidthBytes == 0,
      "Bus width must be multiple of internal mem width for init helper"
    )

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
  test("T1.1 - Single Word Miss then Hit (Narrow Fetch)") {
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
      useId = false
    )
    val simMemConfig = SimulatedMemoryConfig(
      internalDataWidth = cacheConfig.dataWidth, // 模拟内存内部字宽
      memSize = 1 KiB,
      initialLatency = 0,
      burstLatency = 0
    ) // 内存延迟

    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false // 初始化 flush 信号

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)

      val cpuRspBuffer = mutable.Queue[CpuRspData]() // 修改队列类型为 CpuRspData
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        // 将仿真信号转换为 Scala 数据类型并存储到容器类中
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      // 监控内存总线读命令的次数
      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize() // 内存也随机 ready

      // 手动监控 onFire 条件
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
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
      dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty) // 等待响应到达队列
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 MISS 后为空 (timeout $missTimeout)") // 检查队列是否为空

      val rspData1 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      println(s"SIM [T1.1]: MISS - 收到指令 ${rspData1.instructions(0).toString(16)} PC ${rspData1.pc.toString(16)}")
      assert(!rspData1.fault, "MISS 请求不应产生 fault")
      assert(
        rspData1.instructions(0) == testData,
        s"MISS 数据不匹配. 预期 0x${testData.toString(16)}, 得到 0x${rspData1
            .instructions(0)
            .toString(16)}" + s"${rspData1.instructions(0).toString(16)} vs ${testData.toString(16)}"
      )
      assert(rspData1.pc == testAddr, "MISS PC 不匹配")
      assert(memReadCmdFires == wordsPerLine, s"MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0 // 重置计数器

      // 2. 再次请求 testAddr (预期 HIT)
      println(s"SIM [T1.1]: 再次请求地址 0x${testAddr.toHexString} (预期 HIT)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val hitTimeout = 50
      dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty) // 等待响应到达队列
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 HIT 后为空 (timeout $hitTimeout)") // 检查队列是否为空

      val rspData2 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      println(s"SIM [T1.1]: HIT - 收到指令 ${rspData2.instructions(0).toString(16)} PC ${rspData2.pc.toString(16)}")
      assert(!rspData2.fault, "HIT 请求不应产生 fault")
      assert(
        rspData2.instructions(0) == testData,
        s"HIT 数据不匹配. 预期 0x${testData.toString(16)}, 得到 0x${rspData2
            .instructions(0)
            .toString(16)}" + s"${rspData2.instructions(0).toString(16)} vs ${testData.toString(16)}"
      )
      assert(rspData2.pc == testAddr, "HIT PC 不匹配")
      assert(memReadCmdFires == 0, s"HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.1]: 测试 'Single Word Miss then Hit (Narrow Fetch)' 完成.")
    }
  }

  // T1.2: Sequential Word Access Within Line (Narrow Fetch)
  test("T1.2 - Sequential Word Access Within Line (Narrow Fetch)") {
    val lineSizeBytes = 32 // 8 words
    val coreDataWidth = 32 bits

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = 128, // 4 lines
      bytePerLine = lineSizeBytes,
      wayCount = 1,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth // 窄取指
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 2)

    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }

      val baseAddr = 0x300L
      val testData =
        Array.tabulate(wordsPerLine)(i => BigInt(s"${(i + 1).toString * 8}", 16)) // 11111111, 22222222, ...

      // Initialize memory with sequential data
      for (i <- 0 until wordsPerLine) {
        initSimulatedMemory(
          dut.memory.io,
          baseAddr + i * (coreDataWidth.value / 8),
          testData(i),
          dut.clockDomain,
          internalMemDataWidthBytes
        )
      }
      dut.clockDomain.waitSampling(5)

      // 1. Request baseAddr (Expected MISS) - Fills the line
      println(s"SIM [T1.2]: 请求基地址 0x${baseAddr.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= baseAddr)
      val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 MISS 后为空 (timeout $missTimeout)"); cpuRspBuffer.dequeue()
      assert(memReadCmdFires == wordsPerLine, s"MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0

      // 2. Request sequential addresses within the line (Expected HITs)
      println(s"SIM [T1.2]: 顺序请求行内地址 (预期 HITs)")
      for (i <- 1 until wordsPerLine) { // Start from the second word
        val currentAddr = baseAddr + i * (coreDataWidth.value / 8)
        println(s"SIM [T1.2]: 请求地址 0x${currentAddr.toHexString}")
        cpuCmdQueue.enqueue(_.address #= currentAddr)
        val hitTimeout = 50
        dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在顺序 HIT 后为空 (timeout $hitTimeout)")
        val rspData = cpuRspBuffer.dequeue()
        assert(!rspData.fault, s"顺序 HIT 请求不应产生 fault (Addr 0x${currentAddr.toHexString})")
        assert(
          rspData.instructions(0) == testData(i),
          s"顺序 HIT 数据不匹配 (Addr 0x${currentAddr.toHexString}). 预期 0x${testData(i).toString(16)}, 得到 0x${rspData
              .instructions(0)
              .toString(16)}" + s"${rspData.instructions(0).toString(16)} vs ${testData(i).toString(16)}"
        )
        assert(rspData.pc == currentAddr, s"顺序 HIT PC 不匹配 (Addr 0x${currentAddr.toHexString})")
      }

      assert(memReadCmdFires == 0, s"顺序 HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.2]: 测试 'Sequential Word Access Within Line (Narrow Fetch)' 完成.")
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
      wayCount = 1, // Direct mapped
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = fetchWidth
    )
    val memBusConfig =
      GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false) // 内存总线仍然是 coreDataWidth
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
      val cpuRspBuffer = mutable.Queue[CpuRspData]() // 修改队列类型为 CpuRspData
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        // 将仿真信号转换为 Scala 数据类型并存储到容器类中
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }

      val testAddr = 0x200L // 确保地址对齐到 fetchDataWidth (这里是 8 bytes 对齐)
      val testDataGroup = Array(BigInt("11223344", 16), BigInt("AABBCCDD", 16)) // 两个 32 位数据

      // 初始化内存
      for (i <- 0 until fetchWords) {
        initSimulatedMemory(
          dut.memory.io,
          testAddr + i * (coreDataWidth.value / 8),
          testDataGroup(i),
          dut.clockDomain,
          internalMemDataWidthBytes
        )
      }
      dut.clockDomain.waitSampling(5)

      // 1. 请求 testAddr (预期 MISS)
      println(s"SIM [T1.3]: 请求地址 0x${testAddr.toHexString} (预期 MISS, 宽取指)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 100
      dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在宽取指 MISS 后为空 (timeout $missTimeout)")

      val rspData1 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      println(s"SIM [T1.3]: MISS - PC ${rspData1.pc.toString(16)}")
      assert(!rspData1.fault, "宽取指 MISS 不应产生 fault")
      for (i <- 0 until fetchWords) {
        println(s"  Instruction[${i}]: ${rspData1.instructions(i).toString(16)}")
        assert(rspData1.instructions(i) == testDataGroup(i), s"宽取指 MISS 数据[${i}]不匹配.")
      }
      assert(rspData1.pc == testAddr, "宽取指 MISS PC 不匹配")
      assert(memReadCmdFires == wordsPerLine, s"宽取指 MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0

      // 2. 再次请求 testAddr (预期 HIT)
      println(s"SIM [T1.3]: 再次请求地址 0x${testAddr.toHexString} (预期 HIT, 宽取指)")
      cpuCmdQueue.enqueue(_.address #= testAddr)

      val hitTimeout = 50
      dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在宽取指 HIT 后为空 (timeout $hitTimeout)")

      val rspData2 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      println(s"SIM [T1.3]: HIT - PC ${rspData2.pc.toString(16)}")
      assert(!rspData2.fault, "宽取指 HIT 不应产生 fault")
      for (i <- 0 until fetchWords) {
        println(s"  Instruction[${i}]: ${rspData2.instructions(i).toString(16)}")
        assert(rspData2.instructions(i) == testDataGroup(i), s"宽取指 HIT 数据[${i}]不匹配.")
      }
      assert(rspData2.pc == testAddr, "宽取指 HIT PC 不匹配")
      assert(memReadCmdFires == 0, s"宽取指 HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.3]: 测试 'Wide Fetch Miss then Hit' 完成.")
    }
  }

  // T1.4: Wide Fetch Sequential Access Within Line
  test("T1.4 - Wide Fetch Sequential Access Within Line") {
    val lineSizeBytes = 64 // 8 words, 4 fetch groups (64 bits = 8 bytes)
    val coreDataWidth = 32 bits
    val fetchWidth = 64 bits // 2 words

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = 256, // 4 lines
      bytePerLine = lineSizeBytes,
      wayCount = 1,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = fetchWidth
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 4 KiB, initialLatency = 2)

    val wordsPerLine = cacheConfig.wordsPerLine
    val fetchBytes = cacheConfig.fetchDataWidth.value / 8 // 8 bytes
    val fetchGroupsPerLine = lineSizeBytes / fetchBytes // 4 fetch groups
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }

      val baseAddr = 0x400L // Align to fetch group boundary
      // testDataWords 数组存储 64 位的字
      val testDataWords = Array.tabulate(wordsPerLine)(i =>
        BigInt(s"${(i + 10).toString * 8}", 16)
      ) // 1010101010101010, 1111111111111111, ...

      // Initialize memory
      for (i <- 0 until wordsPerLine) {
        // 将 64 位数据拆分成两个 32 位数据
        val dataLower32Bit = (testDataWords(i) & 0xffffffffL).toInt // 低 32 位
        val dataUpper32Bit = ((testDataWords(i) >> 32) & 0xffffffffL).toInt // 高 32 位

        // 写入低 32 位数据
        initSimulatedMemory(
          dut.memory.io,
          baseAddr + i * 2 * internalMemDataWidthBytes,
          dataLower32Bit,
          dut.clockDomain,
          internalMemDataWidthBytes
        )

        // 写入高 32 位数据到下一个地址
        initSimulatedMemory(
          dut.memory.io,
          baseAddr + i * 2 * internalMemDataWidthBytes + internalMemDataWidthBytes,
          dataUpper32Bit,
          dut.clockDomain,
          internalMemDataWidthBytes
        )
      }
      dut.clockDomain.waitSampling(5)

      // 1. Request baseAddr (Expected MISS) - Fills the line
      println(s"SIM [T1.4]: 请求基地址 0x${baseAddr.toHexString} (预期 MISS, 宽取指)")
      cpuCmdQueue.enqueue(_.address #= baseAddr)
      val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 MISS 后为空 (timeout $missTimeout)"); cpuRspBuffer.dequeue()
      assert(memReadCmdFires == wordsPerLine, s"MISS 内存读命令次数不正确. 预期 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0

      // 2. Request sequential addresses within the line, aligned to fetch group (Expected HITs)
      println(s"SIM [T1.4]: 顺序请求行内地址 (按取指宽度对齐, 预期 HITs)")
      for (i <- 1 until fetchGroupsPerLine) { // Start from the second fetch group
        val currentAddr = baseAddr + i * fetchBytes
        println(s"SIM [T1.4]: 请求地址 0x${currentAddr.toHexString}")
        cpuCmdQueue.enqueue(_.address #= currentAddr)
        val hitTimeout = 100
        dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在顺序 HIT 后为空 (timeout $hitTimeout)")
        val rspData = cpuRspBuffer.dequeue()
        assert(!rspData.fault, s"顺序 HIT 请求不应产生 fault (Addr 0x${currentAddr.toHexString})")

        // Verify the instructions in the wide fetch group
        // Calculate the index of the 64-bit word in testDataWords that corresponds to this fetch group
        val wordIdxInTestData = i // Since each fetch group is a 64-bit word in this configuration

        // The first 32-bit instruction in the fetch group is the lower 32 bits of the 64-bit word
        val expectedInstruction0 = testDataWords(wordIdxInTestData) & 0xffffffffL
        // The second 32-bit instruction in the fetch group is the upper 32 bits of the 64-bit word
        val expectedInstruction1 = (testDataWords(wordIdxInTestData) >> 32).toBigInt

        assert(
          rspData.instructions.size == 2,
          s"顺序 HIT 返回的指令数量不正确 (Addr 0x${currentAddr.toHexString}). 预期 2, 得到 ${rspData.instructions.size}"
        )
        assert(
          rspData.instructions(0) == expectedInstruction0,
          s"顺序 HIT 数据[0]不匹配 (Addr 0x${currentAddr.toHexString}). 预期 0x${expectedInstruction0
              .toString(16)}, 得到 0x${rspData.instructions(0).toString(16)}"
        )
        assert(
          rspData.instructions(1) == expectedInstruction1,
          s"顺序 HIT 数据[1]不匹配 (Addr 0x${currentAddr.toHexString}). 预期 0x${expectedInstruction1
              .toString(16)}, 得到 0x${rspData.instructions(1).toString(16)}"
        )

        assert(rspData.pc == currentAddr, s"顺序 HIT PC 不匹配 (Addr 0x${currentAddr.toHexString})")
      }

      assert(memReadCmdFires == 0, s"顺序 HIT 内存读命令次数不正确. 预期 0, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T1.4]: 测试 'Wide Fetch Sequential Access Within Line' 完成.")
    }
  }

  // T2.1: Filling All Ways in a Set, then Hit
  testOnly("T2.1 - Filling All Ways in a Set, then Hit") {
    val lineSizeBytes = 32
    val coreDataWidth = 32 bits
    val ways = 4 // 测试 4 路组相联

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = ways * lineSizeBytes * 1, // 一个组
      bytePerLine = lineSizeBytes,
      wayCount = ways,
      addressWidth = 32 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 4 KiB, initialLatency = 2)

    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }
      dut.clockDomain.waitSampling()

      // Addresses mapping to the same set
      val baseAddr = 0x500L // Set 0
      val addresses = Array.tabulate(ways)(i =>
        baseAddr + i * (cacheConfig.setCount * cacheConfig.bytePerLine)
      ) // Addr 0x500, 0x580, 0x600, 0x680
      val testData = Array.tabulate(ways)(i => BigInt(s"${(i + 20).toString * 8}", 16)) // 20202020, 21212121, ...

      // Initialize memory
      for (i <- 0 until ways) {
        // 将 64 位数据拆分成两个 32 位数据
        val dataLower32Bit = (testData(i) & 0xffffffffL).toInt // 低 32 位
        val dataUpper32Bit = ((testData(i) >> 32) & 0xffffffffL).toInt // 高 32 位

        // 写入低 32 位数据
        println(s"SIM [T2.1]: 初始化内存地址 0x${addresses(i).toHexString} 为低 32 位数据 0x${dataLower32Bit.toHexString}")
        initSimulatedMemory(dut.memory.io, addresses(i), dataLower32Bit, dut.clockDomain, internalMemDataWidthBytes)

        // 写入高 32 位数据到下一个地址
        val upperAddr = addresses(i) + internalMemDataWidthBytes
        println(s"SIM [T2.1]: 初始化内存地址 0x${upperAddr.toHexString} 为高 32 位数据 0x${dataUpper32Bit.toHexString}")
        initSimulatedMemory(dut.memory.io, upperAddr, dataUpper32Bit, dut.clockDomain, internalMemDataWidthBytes)
      }
      dut.clockDomain.waitSampling(5)

      // Fill all ways in the set (Expected MISSes)
      println(s"SIM [T2.1]: 填充 Set 0 的所有 Way (预期 MISSes)")
      for (i <- 0 until ways) {
        println(s"SIM [T2.1]: 请求地址 0x${addresses(i).toHexString}")
        memReadCmdFires = 0
        cpuCmdQueue.enqueue(_.address #= addresses(i))
        val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
        dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在填充 Way ${i} 后为空 (timeout $missTimeout)")
        val rspData = cpuRspBuffer.dequeue()
        assert(!rspData.fault, s"填充 Way ${i} 不应产生 fault")
        // 比较返回的 32 位指令与 testData 中对应位置的 32 位数据
        // 由于请求的是 addresses(i)，这是 64 位数据的低 32 位地址
        val expectedInstruction = testData(i) & 0xffffffffL
        assert(rspData.instructions.size == 1, s"填充 Way ${i} 返回的指令数量不正确. 预期 1, 得到 ${rspData.instructions.size}")
        assert(
          rspData.instructions(0) == expectedInstruction,
          s"填充 Way ${i} 数据不匹配" + s"${rspData.instructions(0).toString(16)} vs ${expectedInstruction.toString(16)}"
        )
        assert(memReadCmdFires == wordsPerLine, s"填充 Way ${i} 内存读命令次数不正确")
      }

      dut.clockDomain.waitSampling(10)
      memReadCmdFires = 0

      // Request the first address again (Expected HIT)
      println(s"SIM [T2.1]: 再次请求第一个地址 0x${addresses(0).toHexString} (预期 HIT)")
      cpuCmdQueue.enqueue(_.address #= addresses(0))
      val hitTimeout = 50
      dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 HIT 后为空 (timeout $hitTimeout)")
      val rspData = cpuRspBuffer.dequeue()
      assert(!rspData.fault, "HIT 请求不应产生 fault")
      // 比较返回的 32 位指令与 testData 中对应位置的 32 位数据
      val expectedInstruction = testData(0) & 0xffffffffL
      assert(rspData.instructions.size == 1, s"HIT 返回的指令数量不正确. 预期 1, 得到 ${rspData.instructions.size}")
      assert(
        rspData.instructions(0) == expectedInstruction,
        "HIT 数据不匹配" + s"${rspData.instructions(0).toString(16)} vs ${expectedInstruction.toString(16)}"
      )
      assert(memReadCmdFires == 0, "HIT 内存读命令次数不正确")

      dut.clockDomain.waitSampling(20)
      println("SIM [T2.1]: 测试 'Filling All Ways in a Set, then Hit' 完成.")
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
      val cpuRspBuffer = mutable.Queue[CpuRspData]() // 修改队列类型为 CpuRspData
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        // 将仿真信号转换为 Scala 数据类型并存储到容器类中
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }
      dut.clockDomain.waitSampling()

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
        dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"$desc - CPU 响应队列为空 (timeout $missTimeout)")
        val rspData = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
        assert(
          rspData.instructions(0) == expectedData,
          s"$desc - 数据不匹配" + s"${rspData.instructions(0).toString(16)} vs ${expectedData.toString(16)}"
        )
        assert(memReadCmdFires == wordsPerLine, s"$desc - 内存读命令次数不正确 (预期 ${wordsPerLine}, 得到 ${memReadCmdFires})")
      }
      def expectHit(addr: Long, expectedData: BigInt, desc: String): Unit = {
        println(s"SIM [T2.2]: $desc - 请求地址 0x${addr.toHexString} (预期 HIT)")
        memReadCmdFires = 0
        cpuCmdQueue.enqueue(_.address #= addr)
        val hitTimeout = 50
        dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"$desc - CPU 响应队列为空 (timeout $hitTimeout)")
        val rspData = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
        assert(
          rspData.instructions(0) == expectedData,
          s"$desc - 数据不匹配" + s"${rspData.instructions(0).toString(16)} vs ${expectedData.toString(16)}"
        )
        assert(memReadCmdFires == 0, s"$desc - 内存读命令次数不正确 (预期 0, 得到 ${memReadCmdFires})")
      }

      println("[TB] 1. Fill way 0 of set 0 with TagA")
      expectMiss(addr_set0_tagA, data_tagA, "填充 Way 0 (TagA)")
      // 此时 TagA 在 Way0, Age=0 (MRU)

      println("[TB] 2. Fill way 1 of set 0 with TagB")
      expectMiss(addr_set0_tagB, data_tagB, "填充 Way 1 (TagB)")
      // 此时 TagB 在 Way1, Age=0 (MRU)
      // TagA 在 Way0, Age=1 (LRU)

      println("[TB] 3. Request TagA (expect HIT, TagA becomes MRU)")
      expectHit(addr_set0_tagA, data_tagA, "命中 TagA")
      // 此时 TagA 在 Way0, Age=0 (MRU)
      // TagB 在 Way1, Age=1 (LRU)

      println("[TB] 4. Request TagC (expect MISS, replaces TagB which was LRU)")
      expectMiss(addr_set0_tagC, data_tagC, "请求 TagC, 替换 TagB")
      // 此时 TagC 在 Way1, Age=0 (MRU)
      // TagA 在 Way0, Age=1 (LRU)

      println("[TB] 5. Request TagB (expect MISS, it was evicted)")
      expectMiss(addr_set0_tagB, data_tagB, "请求 TagB (已被替换)")

      dut.clockDomain.waitSampling(20)
      println("SIM [T2.2]: 测试 'LRU Replacement' 完成.")
    }
  }

  // T2.3: LRU Hit Promotion
  test("T2.3 - LRU Hit Promotion") {
    val lineSizeBytes = 32
    val coreDataWidth = 64 bits
    val ways = 4 // 测试 4 路组相联

    implicit val cacheConfig = AdvancedICacheConfig(
      cacheSize = ways * lineSizeBytes * 1, // 一个组
      bytePerLine = lineSizeBytes,
      wayCount = ways,
      addressWidth = 64 bits,
      dataWidth = coreDataWidth,
      fetchDataWidth = coreDataWidth
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 64 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 4 KiB, initialLatency = 1)

    val wordsPerLine = cacheConfig.wordsPerLine
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }
      dut.clockDomain.waitSampling()

      // Addresses mapping to the same set
      val baseAddr = 0x600L // Set 0
      val addresses = Array.tabulate(ways)(i =>
        baseAddr + i * (cacheConfig.setCount * cacheConfig.bytePerLine)
      ) // Addr 0x600, 0x680, 0x700, 0x780
      val testData = Array.tabulate(ways)(i => BigInt(s"${(i + 30).toString * 8}", 16)) // 30303030, 31313131, ...

      // Initialize memory
      for (i <- 0 until ways) {
        initSimulatedMemory(dut.memory.io, addresses(i), testData(i), dut.clockDomain, internalMemDataWidthBytes)
      }
      dut.clockDomain.waitSampling(5)

      // Fill all ways in the set in order (Tag0, Tag1, Tag2, Tag3)
      println(s"SIM [T2.3]: 填充 Set 0 的所有 Way (Tag0-Tag3)")
      for (i <- 0 until ways) {
        memReadCmdFires = 0
        cpuCmdQueue.enqueue(_.address #= addresses(i))
        val missTimeout = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
        dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
        assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在填充 Way ${i} 后为空 (timeout $missTimeout)"); cpuRspBuffer.dequeue()
        assert(memReadCmdFires == wordsPerLine, s"填充 Way ${i} 内存读命令次数不正确")
      }
      dut.clockDomain.waitSampling(10)

      // At this point, LRU order should be Tag0 (LRU), Tag1, Tag2, Tag3 (MRU)

      // Request Tag1 (Expected HIT, Tag1 becomes MRU)
      println(s"SIM [T2.3]: 请求地址 0x${addresses(1).toHexString} (预期 HIT, Tag1 晋升为 MRU)")
      memReadCmdFires = 0
      cpuCmdQueue.enqueue(_.address #= addresses(1))
      val hitTimeout = 50
      dut.clockDomain.waitSamplingWhere(hitTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在命中 Tag1 后为空 (timeout $hitTimeout)")
      val rspData1 = cpuRspBuffer.dequeue()
      assert(!rspData1.fault, "命中 Tag1 不应产生 fault")
      assert(
        rspData1.instructions(0) == testData(1),
        "命中 Tag1 数据不匹配" + s"${rspData1.instructions(0).toString(16)} vs ${testData(1).toString(16)}"
      )
      assert(memReadCmdFires == 0, "命中 Tag1 内存读命令次数不正确")
      dut.clockDomain.waitSampling(10)

      // Now LRU order should be Tag0, Tag2, Tag3, Tag1 (MRU)

      // Request a new tag (Tag4) for the same set (Expected MISS, replaces Tag0 which is now LRU)
      val addr_set0_tag4 = baseAddr + ways * (cacheConfig.setCount * cacheConfig.bytePerLine) // Addr 0x800
      val data_tag4 = BigInt("DDDDDDDD", 16)
      initSimulatedMemory(dut.memory.io, addr_set0_tag4, data_tag4, dut.clockDomain, internalMemDataWidthBytes)
      dut.clockDomain.waitSampling(5)

      println(s"SIM [T2.3]: 请求新地址 0x${addr_set0_tag4.toHexString} (预期 MISS, 替换 Tag0)")
      memReadCmdFires = 0
      cpuCmdQueue.enqueue(_.address #= addr_set0_tag4)
      val missTimeout2 = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      dut.clockDomain.waitSamplingWhere(missTimeout2)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在请求 Tag4 后为空 (timeout $missTimeout2)")
      val rspData2 = cpuRspBuffer.dequeue()
      assert(!rspData2.fault, "请求 Tag4 不应产生 fault")
      assert(
        rspData2.instructions(0) == data_tag4,
        "请求 Tag4 数据不匹配" + s"${rspData2.instructions(0).toString(16)} vs ${data_tag4.toString(16)}"
      )
      assert(memReadCmdFires == wordsPerLine, "请求 Tag4 内存读命令次数不正确")
      dut.clockDomain.waitSampling(10)

      // Now LRU order should be Tag2, Tag3, Tag1, Tag4 (MRU)

      // Request Tag0 again (Expected MISS, it was evicted)
      println(s"SIM [T2.3]: 再次请求 Tag0 地址 0x${addresses(0).toHexString} (预期 MISS)")
      memReadCmdFires = 0
      cpuCmdQueue.enqueue(_.address #= addresses(0))
      val missTimeout3 = wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      dut.clockDomain.waitSamplingWhere(missTimeout3)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在再次请求 Tag0 后为空 (timeout $missTimeout3)")
      val rspData3 = cpuRspBuffer.dequeue()
      assert(!rspData3.fault, "再次请求 Tag0 不应产生 fault")
      assert(
        rspData3.instructions(0) == testData(0),
        "再次请求 Tag0 数据不匹配" + s"${rspData3.instructions(0).toString(16)} vs ${testData(0).toString(16)}"
      )
      assert(memReadCmdFires == wordsPerLine, "再次请求 Tag0 内存读命令次数不正确")

      dut.clockDomain.waitSampling(20)
      println("SIM [T2.3]: 测试 'LRU Hit Promotion' 完成.")
    }
  }

  // T3.1: Flush Empty Cache
  testOnly("T3.1 - Flush Empty Cache") {
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
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 4 KiB, initialLatency = 1)
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
        }
      }
      dut.clockDomain.waitSampling()

      // Perform Flush on an empty cache
      println("SIM [T3.1]: 执行 Flush 操作 (空缓存)")
      dut.io.flushBus.cmd.valid #= true
      dut.io.flushBus.cmd.payload.start #= true
      dut.io.flushBus.rsp.ready #= true

      dut.clockDomain.waitSampling()
      dut.io.flushBus.cmd.valid #= false

      // Wait for flush done
      val flushTimeout = cacheConfig.setCount * cacheConfig.wayCount * 5 + 50 // Should be fast for empty cache
      dut.clockDomain.waitSamplingWhere(flushTimeout)(
        dut.io.flushBus.rsp.valid.toBoolean && dut.io.flushBus.rsp.payload.done.toBoolean
      )
      assert(
        dut.io.flushBus.rsp.valid.toBoolean && dut.io.flushBus.rsp.payload.done.toBoolean,
        s"Flush 操作未完成或未发出 done 信号 (timeout $flushTimeout)"
      )
      println("SIM [T3.1]: Flush 操作由 DUT 报告完成.")

      dut.clockDomain.waitSampling(5)

      // Request an address (Expected MISS)
      val testAddr = 0x700L
      val testData = BigInt("EEEEEEEE", 16)
      initSimulatedMemory(dut.memory.io, testAddr, testData, dut.clockDomain, internalMemDataWidthBytes)
      dut.clockDomain.waitSampling(5)

      memReadCmdFires = 0
      println(s"SIM [T3.1]: 请求地址 0x${testAddr.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= testAddr)
      val missTimeout = cacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5) + 50
      dut.clockDomain.waitSamplingWhere(missTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"CPU 响应队列在 Flush 后为空 (timeout $missTimeout)")
      val rspData = cpuRspBuffer.dequeue()
      assert(!rspData.fault, "Flush 后请求不应产生 fault")
      assert(
        rspData.instructions(0) == testData,
        "Flush 后数据不匹配" + s"${rspData.instructions(0).toString(16)} vs ${testData.toString(16)}"
      )
      assert(
        memReadCmdFires == cacheConfig.wordsPerLine,
        s"Flush 后内存读命令次数应为 ${cacheConfig.wordsPerLine}, 得到 ${memReadCmdFires}"
      )

      dut.clockDomain.waitSampling(20)
      println("SIM [T3.1]: 测试 'Flush Empty Cache' 完成.")
    }
  }

  // T3.2: Flush Partially Filled Cache
  testOnly("T3.2 - Flush Partially Filled Cache") {
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
      val cpuRspBuffer = mutable.Queue[CpuRspData]() // 修改队列类型为 CpuRspData
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        // 将仿真信号转换为 Scala 数据类型并存储到容器类中
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      var memReadCmdFires = 0
      dut.memory.io.bus.read.cmd.ready.randomize()
      // 手动监控 onFire 条件
      fork {
        while (true) {
          dut.clockDomain
            .waitSamplingWhere(dut.memory.io.bus.read.cmd.valid.toBoolean && dut.memory.io.bus.read.cmd.ready.toBoolean)
          memReadCmdFires += 1
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
      dut.clockDomain.waitSamplingWhere(200)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, "填充 Addr1: CPU 响应队列为空 (timeout 200)"); cpuRspBuffer.dequeue()

      println(s"SIM [T3.2]: 填充缓存 - Addr2 0x${addr2_set1.toHexString}")
      cpuCmdQueue.enqueue(_.address #= addr2_set1)
      dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, "填充 Addr2: CPU 响应队列为空 (timeout 100)"); cpuRspBuffer.dequeue()

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
      dut.clockDomain.waitSamplingWhere(flushTimeout)(
        dut.io.flushBus.rsp.valid.toBoolean && dut.io.flushBus.rsp.payload.done.toBoolean
      )
      assert(
        dut.io.flushBus.rsp.valid.toBoolean && dut.io.flushBus.rsp.payload.done.toBoolean,
        s"Flush 操作未完成或未发出 done 信号 (timeout $flushTimeout)"
      )
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
      dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, "Addr1 post-flush: CPU 响应队列为空 (timeout 100)")
      val rspData_addr1 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      assert(
        rspData_addr1.instructions(0) == data1,
        "Addr1 post-flush: 数据不匹配" + s"${rspData_addr1.instructions(0).toString(16)} vs ${data1.toString(16)}"
      )
      assert(memReadCmdFires == wordsPerLine, s"Addr1 post-flush: 内存读命令次数应为 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      memReadCmdFires = 0
      println(s"SIM [T3.2]: 请求 Addr2 0x${addr2_set1.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue(_.address #= addr2_set1)
      dut.clockDomain.waitSamplingWhere(100)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, "Addr2 post-flush: CPU 响应队列为空 (timeout 100)")
      val rspData_addr2 = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      assert(
        rspData_addr2.instructions(0) == data2,
        "Addr2 post-flush: 数据不匹配" + s"${rspData_addr2.instructions(0).toString(16)} vs ${data2.toString(16)}"
      )
      assert(memReadCmdFires == wordsPerLine, s"Addr2 post-flush: 内存读命令次数应为 ${wordsPerLine}, 得到 ${memReadCmdFires}")

      dut.clockDomain.waitSampling(20)
      println("SIM [T3.2]: 测试 'Flush Partially Filled Cache' 完成.")
    }
  }

  // T4.1: Access to Uninitialized Memory (Memory Fault) - 需要 SimulatedMemory 支持错误注入
  // 注意：SimulatedSplitGeneralMemory 当前版本可能不支持直接注入读错误。
  // 如果需要测试此场景，可能需要修改 SimulatedSplitGeneralMemory 或使用不同的内存模型。
  // 此处仅提供测试用例结构，实际测试需要内存模型支持。
  // test("T4.1 - Access to Uninitialized Memory (Memory Fault)") {
  //   val lineSizeBytes = 32
  //   val coreDataWidth = 32 bits

  //   implicit val cacheConfig = AdvancedICacheConfig(
  //     cacheSize = 128,
  //     bytePerLine = lineSizeBytes,
  //     wayCount = 1,
  //     addressWidth = 32 bits,
  //     dataWidth = coreDataWidth,
  //     fetchDataWidth = coreDataWidth
  //   )
  //   val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
  //   // 配置 SimulatedMemory 以模拟未初始化区域的访问错误
  //   val simMemConfig = SimulatedMemoryConfig(
  //       internalDataWidth = coreDataWidth,
  //       memSize = 1 KiB,
  //       initialLatency = 1,
  //       // 假设 SimulatedMemoryConfig 支持模拟错误响应
  //       // 例如：uninitializedFault = true
  //       // uninitializedFault = true
  //   )
  //   val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

  //   def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

  //   simConfig.compile(tb).doSim { dut =>
  //     dut.clockDomain.forkStimulus(period = 10)
  //     dut.io.flushBus.cmd.valid #= false

  //     val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
  //     StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
  //     val cpuRspBuffer = mutable.Queue[CpuRspData]()
  //     StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
  //       val rspData = CpuRspData(
  //         instructions = payload.instructions.map(_.toBigInt),
  //         pc = payload.pc.toBigInt,
  //         fault = payload.fault.toBoolean
  //       )
  //       cpuRspBuffer.enqueue(rspData)
  //     }
  //     StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

  //     // Request an address in an uninitialized area of memory
  //     val uninitializedAddr = 0x900L // Assuming this address is not initialized
  //     println(s"SIM [T4.1]: 请求未初始化内存地址 0x${uninitializedAddr.toHexString} (预期 Fault)")
  //     cpuCmdQueue.enqueue(_.address #= uninitializedAddr)

  //     // Cache should request from memory and receive a fault response
  //     val faultTimeout = cacheConfig.wordsPerLine * (simMemConfig.initialLatency + 5) + 50
  //     dut.clockDomain.waitSamplingWhere(faultTimeout)(cpuRspBuffer.nonEmpty)
  //     assert(cpuRspBuffer.nonEmpty, s"未初始化内存访问: CPU 响应队列为空 (timeout $faultTimeout)")

  //     val rspData = cpuRspBuffer.dequeue()
  //     println(s"SIM [T4.1]: 收到响应 PC ${rspData.pc.toString(16)}, Fault=${rspData.fault}")

  //     assert(rspData.fault, "访问未初始化内存应产生 fault")
  //     assert(rspData.pc == uninitializedAddr, "未初始化内存访问 fault 的 PC 不匹配")
  //     // 验证指令部分可以是未定义或特定错误码

  //     dut.clockDomain.waitSampling(10)
  //     println("SIM [T4.1]: 测试 'Access to Uninitialized Memory (Memory Fault)' 完成.")
  //   }
  // }

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
    val testAddrCross = 0x1cL

    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32 bits, dataWidth = coreDataWidth, useId = false)
    val simMemConfig = SimulatedMemoryConfig(internalDataWidth = coreDataWidth, memSize = 1 KiB, initialLatency = 1)
    val internalMemDataWidthBytes = simMemConfig.internalDataWidth.value / 8

    def tb = new AdvancedICacheTestbench(cacheConfig, memBusConfig, simMemConfig, dutEnableLog = true)

    simConfig.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false

      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain)
      val cpuRspBuffer = mutable.Queue[CpuRspData]() // 修改队列类型为 CpuRspData
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        // 将仿真信号转换为 Scala 数据类型并存储到容器类中
        val rspData = CpuRspData(
          instructions = payload.instructions.map(_.toBigInt),
          pc = payload.pc.toBigInt,
          fault = payload.fault.toBoolean
        )
        cpuRspBuffer.enqueue(rspData)
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain)

      // (Optional) Initialize memory around the boundary if we expect a miss path before fault
      // initSimulatedMemory(dut.memory.io, testAddrCross, BigInt("DEADBEEF",16), dut.clockDomain, internalMemDataWidthBytes)
      // initSimulatedMemory(dut.memory.io, testAddrCross + 4, BigInt("CAFEF00D",16), dut.clockDomain, internalMemDataWidthBytes)

      println(s"SIM [T4.3]: 请求跨行边界地址 0x${testAddrCross.toHexString}")
      cpuCmdQueue.enqueue(_.address #= testAddrCross)

      // 缓存应该在 sIdle 状态检测到这个问题并立即响应 fault (或进入错误状态)
      // 它不应该尝试去内存取数据。
      val faultTimeout = 20
      dut.clockDomain.waitSamplingWhere(faultTimeout)(cpuRspBuffer.nonEmpty)
      assert(cpuRspBuffer.nonEmpty, s"跨行边界取指: CPU 响应队列为空 (timeout $faultTimeout)")

      val rspData = cpuRspBuffer.dequeue() // 从队列中取出 CpuRspData 实例
      println(s"SIM [T4.3]: 收到响应 PC ${rspData.pc.toString(16)}, Fault=${rspData.fault}")

      assert(rspData.fault, "跨行边界取指应产生 fault")
      assert(rspData.pc == testAddrCross, "跨行边界取指 fault 的 PC 不匹配")
      // 验证指令部分可以是未定义或特定错误码, 这里不严格检查
      // assert(dut.icache.fsm.is(dut.icache.fsm.sIdle), "Cache FSM should return to Idle or an error state") // 检查 FSM 状态 (如果可访问)

      dut.clockDomain.waitSampling(10)
      println("SIM [T4.3]: 测试 'Fetch Group Crossing Line Boundary' 完成.")
    }
  }

  // `thatsAll` 应该在所有测试用例之后
  thatsAll
}
