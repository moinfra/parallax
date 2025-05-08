package boson.test.scala // Or your preferred test package

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite // If you use this specific base

// Import DUT and its dependencies
import boson.demo2.components.icache._
import boson.demo2.components.memory._

class SimpleICacheSpec extends CustomSpinalSimFunSuite {

  // --- 测试平台 (Test Bench) 组件 ---
  class SimpleICacheTestbench(
      val cacheCfg: SimpleICacheConfig,
      val memBusCfg: GenericMemoryBusConfig,
      val simMemCfg: SimulatedMemoryConfig
  ) extends Component {
    val io = new Bundle { // Testbench IO 尽量保持简洁
      val cpuBus = slave(ICacheCpuBus()(cacheCfg))
      val flushBus = slave(ICacheFlushBus())
      // 不再需要 simMemWriteEnable/Address/Data 和 icacheMemCmd/RspFire 等信号
      // 测试脚本将通过 dut.memory.io... 和 dut.icache.io.mem... 直接访问
    }

    // 实例化内部模块
    val icache = new SimpleICache()(cacheCfg, memBusCfg)
    val memory = new SimulatedMemory(simMemCfg, memBusCfg) // SimulatedMemory 的 io 是公开的
    memory.io.simPublic()
    // 内部连接
    icache.io.mem <> memory.io.bus
    icache.io.cpu <> io.cpuBus
    icache.io.flush <> io.flushBus
    icache.io.simPublic()
  }

  // 辅助函数：通过 DUT 内部的 memory 实例的 IO 直接初始化 SimulatedMemory
  def initSimulatedMemoryViaDUT(
      dutMemoryIO: SimulatedMemory#IO, // 直接传入 SimulatedMemory 的 IO Bundle
      address: Long,
      value: BigInt,
      internalDataWidth: Int, // SimulatedMemory 的内部数据宽度
      busDataWidthForValue: Int, // 传入的 value 的数据宽度 (通常是总线宽度)
      clockDomain: ClockDomain
  ): Unit = {
    val internalDataWidthBytes = internalDataWidth / 8
    val numInternalChunksPerBusWord = busDataWidthForValue / internalDataWidth

    require(busDataWidthForValue >= internalDataWidth, "...")
    require(busDataWidthForValue % internalDataWidth == 0, "...")

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

  test("SimpleICache - Miss then Hit on same line (先Miss后同一行Hit)") {
    val lineSizeBytes = 32
    val dataWidthBits = 32
    val wordsPerLineCache = lineSizeBytes / (dataWidthBits / 8)

    val cacheConfig = SimpleICacheConfig(
      cacheSize = 128,
      bytePerLine = lineSizeBytes,
      addressWidth = 32,
      dataWidth = dataWidthBits
    )
    val memBusConfig = GenericMemoryBusConfig(addressWidth = 32, dataWidth = dataWidthBits)
    val simMemConfig = SimulatedMemoryConfig(
      internalDataWidth = dataWidthBits,
      memSize = 8 KiB,
      initialLatency = 2
    )

    def tb = new SimpleICacheTestbench(cacheConfig, memBusConfig, simMemConfig)

    simConfig.withWave.compile(tb).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.flushBus.cmd.valid #= false
      dut.io.flushBus.cmd.payload.start #= false
      
      // --- 设置驱动器和监视器 ---
      val (_, cpuCmdQueue) = StreamDriver.queue(dut.io.cpuBus.cmd, dut.clockDomain) // 解构元组
      StreamReadyRandomizer(dut.io.cpuBus.cmd, dut.clockDomain) // 无 ratio 参数

      // 存储响应数据的元组: (instruction: BigInt, fault: Boolean, pc: BigInt)
      val cpuRspBuffer = mutable.Queue[(BigInt, Boolean, BigInt)]()
      StreamMonitor(dut.io.cpuBus.rsp, dut.clockDomain) { payload =>
        cpuRspBuffer.enqueue(
          (payload.instruction.toBigInt, payload.fault.toBoolean, payload.pc.toBigInt)
        )
      }
      StreamReadyRandomizer(dut.io.cpuBus.rsp, dut.clockDomain) // 无 ratio 参数
      StreamReadyRandomizer(dut.memory.io.bus.cmd, dut.clockDomain) // 无 ratio 参数

      // --- 准备测试数据 ---
      val testAddr1 = 0x1000L
      val testData1 = BigInt("AABBCCDD", 16)
      val testAddr2 = testAddr1 + (dataWidthBits / 8)
      val testData2 = BigInt("11223344", 16)

      // 通过 dut.memory.io 来初始化内存
      initSimulatedMemoryViaDUT(
        dut.memory.io, // 传入 SimulatedMemory 的 io 对象
        testAddr1,
        testData1,
        simMemConfig.internalDataWidth, // SimMem 内部宽度
        memBusConfig.dataWidth, // testData1 的宽度 (总线宽度)
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

      dut.clockDomain.waitSampling(5)

      // --- 1. 请求 testAddr1 (预期 Cache MISS) ---
      println(s"SIM: 请求地址 0x${testAddr1.toHexString} (预期 MISS)")
      cpuCmdQueue.enqueue { cmdPayload => // cmdPayload 是 Stream 的 payload 对象
        cmdPayload.address #= testAddr1
      }

      var memCmdFiresForMiss = 0
      val timeoutCyclesMiss = (simMemConfig.initialLatency + 5) * wordsPerLineCache + 50

      assert(
        !dut.clockDomain.waitSamplingWhere(timeoutCyclesMiss) {
          // 直接通过 dut.icache 访问其 mem 总线信号进行监控
          val cmd_valid = dut.icache.io.mem.cmd.valid.toBoolean
          val cmd_ready = dut.icache.io.mem.cmd.ready.toBoolean
          if (cmd_valid && cmd_ready) {
            memCmdFiresForMiss += 1
          }
          cpuRspBuffer.nonEmpty
        },
        s"超时: CPU 未收到 MISS 请求 (地址: 0x${testAddr1.toHexString}) 的响应. 内存请求次数: $memCmdFiresForMiss"
      )

      val (rsp1_instr, rsp1_fault, rsp1_pc) = cpuRspBuffer.dequeue()
      println(s"SIM: MISS场景 - 收到指令 0x${rsp1_instr.toString(16)} (来自PC 0x${rsp1_pc.toString(16)})")
      assert(!rsp1_fault, s"MISS 请求 (地址: 0x${testAddr1.toHexString}) 发生错误 (fault)")
      assert(rsp1_instr == testData1, s"MISS 数据不匹配.")
      assert(rsp1_pc == testAddr1, s"MISS PC不匹配.")
      assert(
        memCmdFiresForMiss == wordsPerLineCache,
        s"MISS 场景内存请求次数错误. 预期 ${wordsPerLineCache}, 实际 ${memCmdFiresForMiss}"
      )

      dut.clockDomain.waitSampling(10)

      // --- 2. 请求 testAddr2 (预期 Cache HIT) ---
      println(s"SIM: 请求地址 0x${testAddr2.toHexString} (预期 HIT)")
      cpuCmdQueue.enqueue { cmdPayload =>
        cmdPayload.address #= testAddr2
      }

      var memCmdFiresForHit = 0
      val timeoutCyclesHit = 50

      assert(
        !dut.clockDomain.waitSamplingWhere(timeoutCyclesHit) {
          if (dut.icache.io.mem.cmd.fire.toBoolean) memCmdFiresForHit += 1
          cpuRspBuffer.nonEmpty
        },
        s"超时: CPU 未收到 HIT 请求 (地址: 0x${testAddr2.toHexString}) 的响应. 内存请求次数: $memCmdFiresForHit"
      )

      val (rsp2_instr, rsp2_fault, rsp2_pc) = cpuRspBuffer.dequeue()
      println(s"SIM: HIT场景 - 收到指令 0x${rsp2_instr.toString(16)} (来自PC 0x${rsp2_pc.toString(16)})")
      assert(!rsp2_fault, s"HIT 请求 (地址: 0x${testAddr2.toHexString}) 发生错误 (fault)")
      assert(rsp2_instr == testData2, s"HIT 数据不匹配.")
      assert(rsp2_pc == testAddr2, s"HIT PC不匹配.")
      assert(memCmdFiresForHit == 0, s"HIT 场景内存请求次数错误. 预期 0 次, 实际 ${memCmdFiresForHit} 次")

      dut.clockDomain.waitSampling(20)
      println("SIM: 测试 ('SimpleICache - Miss then Hit on same line') 完成。")
    }
  }

  thatsAll
}
