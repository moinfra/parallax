package boson.test.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import spinal.tester.SpinalSimFunSuite

// Make sure these paths are correct for your project structure
import boson.demo2.components.memory.{SimulatedMemory, SimulatedMemoryConfig, GenericMemoryBus, GenericMemoryBusConfig}
import boson.demo2.common.Config // Assuming Config.XLEN is defined

class SimulatedMemorySpec extends SpinalSimFunSuite {

  onlyVerilator

  def simConfig = SimConfig.withWave // FST is generally preferred over VCD

  val busAddrWidth = 32
  val busDataWidth = 32 // The width SimulatedMemory will expose on its bus

  // Configuration for the GenericMemoryBus that SimulatedMemory will implement
  val genericBusConfig = GenericMemoryBusConfig(
    addressWidth = busAddrWidth,
    dataWidth = busDataWidth
  )

 test("SimulatedMemory should respond to a single word read request") {
    val internalMemWidth = 16
    val memSizeBytes = 1024
    val latencyCycles = 2

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )

    simConfig
      .compile(new SimulatedMemory(memCfg, genericBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // --- Testbench Write Initialization ---
        val internalWordsPerBus = busDataWidth / internalMemWidth
        val testAddrByte = 0x40
        val testDataBusWidth = BigInt("CAFEBABE", 16)

        dut.io.writeEnable #= true
        for (i <- 0 until internalWordsPerBus) {
          val currentByteAddr = testAddrByte + i * (internalMemWidth / 8)
          val dataSlice = (testDataBusWidth >> (i * internalMemWidth)) & ((BigInt(1) << internalMemWidth) - 1)
          dut.io.writeAddress #= currentByteAddr
          dut.io.writeData #= dataSlice
          dut.clockDomain.waitSampling()
        }
        dut.io.writeEnable #= false
        dut.clockDomain.waitSampling()

        // --- DUT Interaction ---
        val (cmdDriver, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        
        // 监视器只捕获读取数据
        var receivedData: Option[BigInt] = None
        val rspMonitor = StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          receivedData = Some(payload.readData.toBigInt)
        }
        
        StreamReadyRandomizer(dut.io.bus.rsp, dut.clockDomain)

        // 发送读命令
        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= false
          cmd.writeData #= 0
        }

        // 等待响应
        dut.clockDomain.waitSamplingWhere(receivedData.isDefined)

        assert(receivedData.get == testDataBusWidth, 
          s"Read data mismatch. Expected $testDataBusWidth, got ${receivedData.get}")
        assert(!dut.io.bus.rsp.payload.error.toBoolean, "Error signal was asserted")

        dut.clockDomain.waitEdge(10)
      }
  }

  test("SimulatedMemory should respond to a single word write request") {
    val internalMemWidth = 16
    val memSizeBytes = 1024
    val latencyCycles = 1

    val memCfg = SimulatedMemoryConfig(
      internalDataWidth = internalMemWidth,
      memSize = memSizeBytes,
      initialLatency = latencyCycles
    )

    simConfig
      .compile(new SimulatedMemory(memCfg, genericBusConfig))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        val (cmdDriver, cmdQueue) = StreamDriver.queue(dut.io.bus.cmd, dut.clockDomain)
        
        // 监视器只检查错误状态
        var responseReceived = false
        var responseError = false
        val rspMonitor = StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          responseReceived = true
          responseError = payload.error.toBoolean
        }
        
        StreamReadyRandomizer(dut.io.bus.rsp, dut.clockDomain)

        val testAddrByte = 0x80
        val testWriteData = BigInt("FEEDFACE", 16)

        // 发送写命令
        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= true
          cmd.writeData #= testWriteData
        }
        
        // 等待响应
        dut.clockDomain.waitSamplingWhere(responseReceived)
        assert(!responseError, "Write operation signaled an error")
        dut.clockDomain.waitEdge(5)

        // 验证写入 - 发送读命令
        var readBackData: Option[BigInt] = None
        val readMonitor = StreamMonitor(dut.io.bus.rsp, dut.clockDomain) { payload =>
          readBackData = Some(payload.readData.toBigInt)
        }

        cmdQueue.enqueue { cmd =>
          cmd.address #= testAddrByte
          cmd.isWrite #= false
          cmd.writeData #= 0
        }
        
        // 等待并验证读取结果
        dut.clockDomain.waitSamplingWhere(readBackData.isDefined)
        assert(readBackData.get == testWriteData, 
          s"Read back after write mismatch. Expected $testWriteData, got ${readBackData.get}")
        assert(!dut.io.bus.rsp.payload.error.toBoolean, "Read back error")
        
        dut.clockDomain.waitEdge(10)
      }
  }

  // Add more tests:
  // - Back-to-back read requests
  // - Back-to-back write requests
  // - Mixed read/write requests
  // - Access to address 0
  // - Access to max address
  // - Out-of-bounds access (expect error signal if implemented, or specific behavior)
  // - Different internalDataWidth vs busDataWidth scenarios
}
