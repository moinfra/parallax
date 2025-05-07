package boson.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable
import scala.util.Random

import boson.demo2.components.icache._

class SimulatedMemorySpec extends AnyFunSuite {

  // Helper function to create and compile the DUT
  def simConfig(config: SimpleICacheConfig) = {
    SimConfig
      .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
      .withWave // Optional: enable VCD/FST waveform dumping
      .compile(new SimulatedMemory()(config))

  }

  test("SimulatedMemory should respond to a single line request") {
    implicit val config = SimpleICacheConfig(
      bytePerLine = 32, // 8 words per line
      dataWidth = 32,
      simulatedMemLatency = 3 // 3 cycles per word
    )
    simConfig(config).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize memory content for testing
      val testMem = mutable.HashMap[Long, Long]()
      for (i <- 0 until config.wordsPerLine) {
        testMem(i.toLong * (config.dataWidth / 8)) = (0xcafe0000 + i).toLong
        dut.io.writeEnable #= true
        dut.io.writeAddress #= i * (config.dataWidth / 8)
        dut.io.writeData #= testMem(i.toLong * (config.dataWidth / 8))
        dut.clockDomain.waitSampling()
      }
      dut.io.writeEnable #= false
      dut.clockDomain.waitSampling()

      // Drive the command
      dut.io.bus.cmd.valid #= false
      dut.io.bus.rsp.ready #= true // Cache is always ready to accept response
      dut.clockDomain.waitSampling()

      val lineAddrToRequest = 0L
      println(s"Requesting line address: ${lineAddrToRequest}")

      dut.io.bus.cmd.valid #= true
      dut.io.bus.cmd.payload.lineAddress #= lineAddrToRequest

      waitUntil(dut.io.bus.cmd.ready.toBoolean) // Wait for memory to be ready
      dut.clockDomain.waitSampling() // cmd.fire happens here
      dut.io.bus.cmd.valid #= false // Deassert valid after fire

      val receivedWords = mutable.ArrayBuffer[Long]()

      for (i <- 0 until config.wordsPerLine) {
        println(s"Waiting for word $i")
        // Wait for latency + 1 cycle for data to be valid (readSync)
        dut.clockDomain.waitSampling(config.simulatedMemLatency)
        waitUntil(dut.io.bus.rsp.valid.toBoolean)

        val data = dut.io.bus.rsp.payload.data.toLong
        receivedWords += data
        println(
          s"Received word $i: 0x${data.toHexString}, Expected: 0x${testMem(i.toLong * (config.dataWidth / 8)).toHexString}"
        )
        assert(data == testMem(i.toLong * (config.dataWidth / 8)), s"Word $i mismatch")

        dut.clockDomain.waitSampling() // rsp.fire happens here
      }

      assert(receivedWords.size == config.wordsPerLine)
      println("Single line request test passed.")
    }

  }

  test("SimulatedMemory should handle back-to-back line requests") {
    implicit val config = SimpleICacheConfig(
      bytePerLine = 16, // 4 words per line
      dataWidth = 32,
      simulatedMemLatency = 2
    )
    simConfig(config).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Preload memory for two lines
      val testMemLine0 = (0 until config.wordsPerLine).map(i => (0x11110000 + i).toLong).toArray
      val testMemLine1 = (0 until config.wordsPerLine).map(i => (0x22220000 + i).toLong).toArray

      for (i <- 0 until config.wordsPerLine) {
        dut.io.writeEnable #= true
        dut.io.writeAddress #= i * (config.dataWidth / 8) // Line 0
        dut.io.writeData #= testMemLine0(i)
        dut.clockDomain.waitSampling()
        dut.io.writeAddress #= config.bytePerLine + i * (config.dataWidth / 8) // Line 1
        dut.io.writeData #= testMemLine1(i)
        dut.clockDomain.waitSampling()
      }
      dut.io.writeEnable #= false
      dut.clockDomain.waitSampling()

      dut.io.bus.rsp.ready #= true

      // Request Line 0
      dut.io.bus.cmd.valid #= true
      dut.io.bus.cmd.payload.lineAddress #= 0
      waitUntil(dut.io.bus.cmd.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.bus.cmd.valid #= false

      for (i <- 0 until config.wordsPerLine) {
        dut.clockDomain.waitSampling(config.simulatedMemLatency)
        waitUntil(dut.io.bus.rsp.valid.toBoolean)
        assert(dut.io.bus.rsp.payload.data.toLong == testMemLine0(i))
        dut.clockDomain.waitSampling()
      }
      println("Line 0 read successfully.")

      // Immediately Request Line 1
      // Memory should be in sIdle, so cmd.ready should be high
      waitUntil(dut.io.bus.cmd.ready.toBoolean)
      dut.io.bus.cmd.valid #= true
      dut.io.bus.cmd.payload.lineAddress #= config.bytePerLine / (config.dataWidth / 8) // Line address for line 1

      waitUntil(dut.io.bus.cmd.ready.toBoolean) // Should be ready quickly
      dut.clockDomain.waitSampling()
      dut.io.bus.cmd.valid #= false

      for (i <- 0 until config.wordsPerLine) {
        dut.clockDomain.waitSampling(config.simulatedMemLatency)
        waitUntil(dut.io.bus.rsp.valid.toBoolean)
        assert(dut.io.bus.rsp.payload.data.toLong == testMemLine1(i))
        dut.clockDomain.waitSampling()
      }
      println("Line 1 read successfully after Line 0.")
      println("Back-to-back line requests test passed.")
    }
  }
}
