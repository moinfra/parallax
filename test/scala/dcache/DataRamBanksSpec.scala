// testOnly parallax.test.scala.dcache.DataRamBanksComponentSpec
package test.scala.dcache

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random
import parallax.components.dcache._ // Assuming DUT and its Bundles are here
import parallax.utilities.ParallaxLogger

// TestBench to wrap DataRamBanksComponent and expose signals
class DataRamBanksTestBench(p: DataCacheParameters) extends Component {
  val io = (DataRamBanksIO(p)) // TestBench IO mirrors DUT IO but as slave
  val dut = new DataRamBanksComponent(p)

  dut.io.speculativeReadCmdS0 <> io.speculativeReadCmdS0
  io.speculativeReadRspS1 <> dut.io.speculativeReadRspS1 // Master to slave connection
  dut.io.writeCmdS2 <> io.writeCmdS2
  dut.io.lineWriteCmdRefill <> io.lineWriteCmdRefill
  dut.io.lineReadCmdWb <> io.lineReadCmdWb
  io.lineReadRspWb <> dut.io.lineReadRspWb

  // Expose internal signals if needed for debugging or more complex assertions
  // For example, if you wanted to see the arbiter's choice in each way:
  // val way0ArbiterOutputValid = dut.ways_logic(0).writePortArbiter.io.output.valid.simPublic()
  // val way0ArbiterChoice = dut.ways_logic(0).writePortArbiter.io.chosen.simPublic()
  // For this example, we'll keep it simple and primarily test via IO.
  io.simPublic() // Make all IOs of the TestBench accessible
}

// Helper to prefill a way's memory
object DataRamBankTestHelpers {
  val DEFAULT_CMD_TIMEOUT_CYCLES = 50

  def extractWord(data: BigInt, wordOffsetFromLSB: Int, wordWidth: Int): BigInt = {
    val shiftedData = data >> wordOffsetFromLSB
    val mask = (BigInt(1) << wordWidth) - BigInt(1)
    val extractedWord = shiftedData & mask
    extractedWord
  }

  def resetDutInputs(tbIo: DataRamBanksIO): Unit = {
    tbIo.speculativeReadCmdS0.valid #= false
    tbIo.writeCmdS2.valid #= false
    tbIo.lineWriteCmdRefill.valid #= false
    tbIo.lineReadCmdWb.valid #= false
  }

  def prefillWay(
      tbIo: DataRamBanksIO, // Pass TestBench IO
      wayIdx: Int,
      lineIndex: Int,
      lineData: BigInt,
      clockDomain: ClockDomain,
      timeoutCycles: Int = DEFAULT_CMD_TIMEOUT_CYCLES
  ): Unit = {
    tbIo.lineWriteCmdRefill.valid #= true
    tbIo.lineWriteCmdRefill.payload.index #= lineIndex
    tbIo.lineWriteCmdRefill.payload.way #= wayIdx
    tbIo.lineWriteCmdRefill.payload.data #= lineData

    var fired = false
    val timedOut = clockDomain.waitSamplingWhere(timeoutCycles) {
      fired = tbIo.lineWriteCmdRefill.valid.toBoolean && tbIo.lineWriteCmdRefill.ready.toBoolean
      fired
    }
    if (timedOut || !fired) {
      simFailure(
        s"PrefillWay timed out or failed to fire lineWriteCmdRefill. Way: $wayIdx, Index: $lineIndex. TimedOut: $timedOut, Fired: $fired"
      )
    }
    tbIo.lineWriteCmdRefill.valid #= false
    clockDomain.waitSampling()
  }

  def driveSingleStoreCmd(
      tbIo: DataRamBanksIO,
      index: Int,
      way: Int,
      wordOffset: Int,
      data: BigInt,
      mask: Int,
      clockDomain: ClockDomain,
      timeoutCycles: Int = DEFAULT_CMD_TIMEOUT_CYCLES
  ): Unit = {
    tbIo.writeCmdS2.valid #= true
    tbIo.writeCmdS2.payload.index #= index
    tbIo.writeCmdS2.payload.way #= way
    tbIo.writeCmdS2.payload.wordOffset #= wordOffset
    tbIo.writeCmdS2.payload.data #= data
    tbIo.writeCmdS2.payload.mask #= mask

    var fired = false
    val timedOut = clockDomain.waitSamplingWhere(timeoutCycles) {
      fired = tbIo.writeCmdS2.valid.toBoolean && tbIo.writeCmdS2.ready.toBoolean
      fired
    }
    if (timedOut || !fired) {
      simFailure(s"driveSingleStoreCmd timed out or failed to fire writeCmdS2. TimedOut: $timedOut, Fired: $fired")
    }
    tbIo.writeCmdS2.valid #= false
  }

  def driveSingleSpecReadCmd(
      tbIo: DataRamBanksIO,
      index: Int,
      wordOffset: Int,
      clockDomain: ClockDomain,
      timeoutCycles: Int = DEFAULT_CMD_TIMEOUT_CYCLES
  ): Unit = {
    ParallaxLogger.log(
      f"driveSingleSpecReadCmd: $simTime Driving Speculative Read Command for index: $index, wordOffset: $wordOffset"
    )
    tbIo.speculativeReadCmdS0.valid #= true
    tbIo.speculativeReadCmdS0.payload.index #= index
    tbIo.speculativeReadCmdS0.payload.wordOffset #= wordOffset

    var fired = false
    val timedOut = clockDomain.waitSamplingWhere(timeoutCycles) {
      fired = tbIo.speculativeReadCmdS0.valid.toBoolean && tbIo.speculativeReadCmdS0.ready.toBoolean
      if (fired) {
        ParallaxLogger.log(f"driveSingleSpecReadCmd: fired")
      }
      fired
    }
    if (timedOut || !fired) {
      simFailure(
        s"driveSingleSpecReadCmd timed out or failed to fire speculativeReadCmdS0. TimedOut: $timedOut, Fired: $fired"
      )
    }
    sleep(1)
    tbIo.speculativeReadCmdS0.valid #= false
    ParallaxLogger.log(f"driveSingleSpecReadCmd: $simTime Done.")
  }
}

class DataRamBanksComponentSpec extends CustomSpinalSimFunSuite {

  val defaultCacheParams = DataCacheParameters(
    cacheSize = 1024,
    wayCount = 2,
    memDataWidth = 64,
    cpuDataWidth = 32,
    physicalWidth = 32,
    lineSize = 64,
    refillBufferDepth = 2,
    writebackBufferDepth = 2,
    tagsReadAsync = true
  )
  assert(defaultCacheParams.lineSize * 8 / defaultCacheParams.cpuDataWidth > 0, "Line must fit at least one CPU word")

  val testSpecificTimeoutCycles = 100

  // SimConfig is now inherited from CustomSpinalSimFunSuite
  // def createSimConfig(...) is not needed unless you want to override the base simConfig

  test("DataRamBanksComponent - TP1.1.1 - Speculative Read - Single Command") {
    val p = defaultCacheParams.copy(wayCount = 2)
    // Use inherited simConfig
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._

      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io // IO of the TestBench
      resetDutInputs(io)
      val lineDataWay0 = BigInt("AAAAAAAAAAAAAAAAAAAAAAAA87654321", 16)
      val lineDataWay1 = BigInt("BBBBBBBBBBBBBBBBBBBBBBBB87654321", 16)
      prefillWay(io, 0, 5, lineDataWay0, cd) // Pass TestBench IO
      prefillWay(io, 1, 5, lineDataWay1, cd)
      cd.waitSampling(5)

      ParallaxLogger.log("Initializations done. Starting test")
      driveSingleSpecReadCmd(io, 5, 0, cd)

      var rspValidObserved = false
      var way0Data: BigInt = 0
      var way1Data: BigInt = 0

      val timedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
        rspValidObserved = io.speculativeReadRspS1.valid.toBoolean
        if (rspValidObserved) {
          way0Data = io.speculativeReadRspS1.payload(0).toBigInt
          way1Data = io.speculativeReadRspS1.payload(1).toBigInt
        }
        rspValidObserved
      }
      sleep(1)
      assert(!timedOut, "Timeout: SpeculativeReadRspS1Valid did not go high")
      ParallaxLogger.log(f"Way 0 data: ${way0Data}%X")
      ParallaxLogger.log(f"Way 1 data: ${way1Data}%X")

      val expectedWordWay0 = extractWord(lineDataWay0, 0 * p.cpuDataWidth, p.cpuDataWidth)
      val expectedWordWay1 = extractWord(lineDataWay1, 0 * p.cpuDataWidth, p.cpuDataWidth)

      assert(way0Data == expectedWordWay0, f"Way 0 data mismatch. Expected ${expectedWordWay0}%X, Got ${way0Data}%X")
      assert(way1Data == expectedWordWay1, f"Way 1 data mismatch. Expected ${expectedWordWay1}%X, Got ${way1Data}%X")

      cd.waitSampling(10)
    }
  }

  test("DataRamBanksComponent - TP1.2.1 - Store Write - Masked Write") {
    val p = defaultCacheParams.copy(cpuDataWidth = 32, lineSize = 64)
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._

      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)
      val lineIdx = 3
      val wayToTest = 0
      val initialLineData = BigInt("11223344AABBCCDD", 16)
      prefillWay(io, wayToTest, lineIdx, initialLineData, cd)
      cd.waitSampling(5)

      val storeData = BigInt("EEFF0011", 16)
      val storeMask = 0x0c
      val storeWordOffset = 1

      driveSingleStoreCmd(io, lineIdx, wayToTest, storeWordOffset, storeData, storeMask, cd)
      cd.waitSampling(5)

      val readBackWordsWay0 = mutable.ArrayBuffer[BigInt]()
      var wordsReceived = 0

      for (wo <- 0 until p.cpuWordPerLine) {
        driveSingleSpecReadCmd(io, lineIdx, wo, cd)

        var currentWordReceived = false
        val iterTimedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
          currentWordReceived = io.speculativeReadRspS1.valid.toBoolean
          if (currentWordReceived) {
            readBackWordsWay0 += io.speculativeReadRspS1.payload(wayToTest).toBigInt
          }
          currentWordReceived
        }
        assert(!iterTimedOut, s"Timeout reading back word $wo for verification")
        wordsReceived += 1
      }

      assert(
        readBackWordsWay0.size == p.cpuWordPerLine,
        s"Did not receive all words. Got ${readBackWordsWay0.size}, Expected ${p.cpuWordPerLine}"
      )

      val expectedWord0 = BigInt("AABBCCDD", 16)
      val expectedWord1 = BigInt("EEFF3344", 16)

      assert(
        readBackWordsWay0(0) == expectedWord0,
        f"Word 0 mismatch. Expected ${expectedWord0}%X, Got ${readBackWordsWay0(0)}%X"
      )
      assert(
        readBackWordsWay0(1) == expectedWord1,
        f"Word 1 mismatch. Expected ${expectedWord1}%X, Got ${readBackWordsWay0(1)}%X"
      )

      cd.waitSampling(10)
    }
  }

  test("DataRamBanksComponent - TP2.1.1 - Write Port Arbiter - S2 Store vs Refill (Same Way)") {
    val p = defaultCacheParams.copy(wayCount = 1)
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._
      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)

      val lineIdx = 1
      val testWay = 0

      var s2TransactionsCompleted = 0L
      var refillTransactionsCompleted = 0L

      val s2Process = fork {
        val (driver, queue) = StreamDriver.queue(io.writeCmdS2, cd)
        queue.enqueue { payload =>
          payload.index #= lineIdx
          payload.way #= testWay
          payload.wordOffset #= 0
          payload.data #= BigInt("DEADBEEF", 16)
          payload.mask #= 0xf
        }
        // println(s"${simTime()} S2 Store enqueued transaction via StreamDriver queue")
      }

      val refillProcess = fork {
        val (driver, queue) = StreamDriver.queue(io.lineWriteCmdRefill, cd)
        queue.enqueue { payload =>
          payload.index #= lineIdx
          payload.way #= testWay
          payload.data #= BigInt("CAFECAFECAFECAFECAFECAFECAFECAFE", 16)
        }
        // println(s"${simTime()} Refill enqueued transaction via StreamDriver queue")
      }

      StreamMonitor(io.writeCmdS2, cd) { _ =>
        s2TransactionsCompleted += 1
      // println(s"${simTime()} S2 Store CMD COMPLETED (Monitor)")
      }
      StreamMonitor(io.lineWriteCmdRefill, cd) { _ =>
        refillTransactionsCompleted += 1
      // println(s"${simTime()} Refill CMD COMPLETED (Monitor)")
      }

      val arbiterTestTimeout = testSpecificTimeoutCycles * 2 + 40
      val timedOut = cd.waitSamplingWhere(arbiterTestTimeout) {
        s2TransactionsCompleted == 1 && refillTransactionsCompleted == 1
      }
      // Temporarily disable detailed timeout message for cleaner output if tests pass often
      if (timedOut) {
        simFailure(
          s"Timeout waiting for both S2 store and Refill to complete. S2_Completed: $s2TransactionsCompleted, Refill_Completed: $refillTransactionsCompleted"
        )
      }
      // assert(!timedOut, s"Timeout waiting for both S2 store and Refill to complete. S2_Completed: $s2TransactionsCompleted, Refill_Completed: $refillTransactionsCompleted")

      // println(s"Final counts - S2 Fired: $s2TransactionsCompleted, Refill Fired: $refillTransactionsCompleted")

      assert(s2TransactionsCompleted == 1, "S2 store should have completed one transaction")
      assert(refillTransactionsCompleted == 1, "Refill should have completed one transaction")

      cd.waitSampling(10)
    }
  }

  // --- NEW TESTS START HERE ---

  test("DataRamBanksComponent - TP1.3.1 - Line Write (Refill) - Basic") {
    val p = defaultCacheParams.copy(wayCount = 2)
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._
      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)

      val lineIdx = 7
      val wayToTest = 1
      val refillData = BigInt("0123456789ABCDEF" * 4, 16) // 64 bytes

      // Use the helper, which internally waits for fire
      prefillWay(io, wayToTest, lineIdx, refillData, cd)
      cd.waitSampling(2) // Allow write to settle

      // Verify by reading back word by word using speculative read
      val readBackWords = mutable.ArrayBuffer[BigInt]()
      for (wo <- 0 until p.cpuWordPerLine) {
        driveSingleSpecReadCmd(io, lineIdx, wo, cd)
        val timedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
          val v = io.speculativeReadRspS1.valid.toBoolean
          if (v) readBackWords += io.speculativeReadRspS1.payload(wayToTest).toBigInt
          v
        }
        assert(!timedOut, s"Timeout reading back word $wo after refill for verification")
      }

      assert(readBackWords.size == p.cpuWordPerLine, "Did not receive all words after refill.")

      // Reconstruct the line from read words and compare
      var reconstructedLine = BigInt(0)
      for (i <- 0 until p.cpuWordPerLine) {
        reconstructedLine = (reconstructedLine << p.cpuDataWidth) | readBackWords(p.cpuWordPerLine - 1 - i)
      }
      // Note: This reconstruction assumes MSB word is at index 0 of readBackWords if read in increasing word offset.
      // Let's verify individual words against expected parts of refillData.
      for (wo <- 0 until p.cpuWordPerLine) {
        val expectedWord = extractWord(refillData, wo * p.cpuDataWidth, p.cpuDataWidth)
        assert(
          readBackWords(wo) == expectedWord,
          f"Refilled word $wo mismatch. Expected ${expectedWord}%X, Got ${readBackWords(wo)}%X"
        )
      }
      cd.waitSampling(10)
    }
  }

  test("DataRamBanksComponent - TP1.4.1 - Line Read (Writeback) - Basic") {
    val p = defaultCacheParams.copy(wayCount = 1) // Single way for simplicity
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._
      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)

      val lineIdx = 2
      val wayToTest = 0
      val prefillData = BigInt("FEDCBA9876543210" * 4, 16) // 64 bytes
      prefillWay(io, wayToTest, lineIdx, prefillData, cd)
      cd.waitSampling(5)

      // Drive Line Read Command for Writeback
      io.lineReadCmdWb.valid #= true
      io.lineReadCmdWb.payload.index #= lineIdx
      io.lineReadCmdWb.payload.way #= wayToTest
      var readLineData: BigInt = 0

      val thread = fork {
        // Response is valid one cycle after cmd.fire
        var rspValidObserved = false
        var rspTimedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
          rspValidObserved = io.lineReadRspWb.valid.toBoolean
          if (rspValidObserved) {
            readLineData = io.lineReadRspWb.payload.toBigInt
          }
          rspValidObserved
        }
        assert(!rspTimedOut, "LineReadRspWb.valid did not go high")
      }

      var readCmdFired = false
      var readTimeout = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
        readCmdFired = (io.lineReadCmdWb.valid.toBoolean && io.lineReadCmdWb.ready.toBoolean)
        readCmdFired
      }
      assert(!readTimeout && readCmdFired, "LineReadCmdWb did not fire")
      io.lineReadCmdWb.valid #= false
      thread.join()
      assert(readLineData == prefillData, f"Line read data mismatch. Expected ${prefillData}%X, Got ${readLineData}%X")

      cd.waitSampling(10)
    }
  }

  test("DataRamBanksComponent - TP3.1 - Read-After-Write (Same Word, Same Way)") {
    val p = defaultCacheParams.copy(cpuDataWidth = 32, lineSize = 64)
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._
      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)

      val lineIdx = 4
      val wayToTest = 0
      val initialWord0 = BigInt("12345678", 16)
      val initialWord1 = BigInt("87654321", 16)
      // Line: 8765432112345678 (assuming word0 is LSW, word1 is MSW for BigInt construction)
      // To match extractWord: data is MSB first.
      // Line: initialWord1 ## initialWord0
      val initialLineData = (initialWord1 << 32) | initialWord0

      prefillWay(io, wayToTest, lineIdx, initialLineData, cd)
      cd.waitSampling(5)

      // Store new data to word 0
      val storeData = BigInt("FFFFFFFF", 16)
      val storeMask = 0xf // Full word mask
      val storeWordOffset = 0
      driveSingleStoreCmd(io, lineIdx, wayToTest, storeWordOffset, storeData, storeMask, cd)
      cd.waitSampling(5) // Allow store to complete

      // Speculative Read the same word
      driveSingleSpecReadCmd(io, lineIdx, storeWordOffset, cd)

      var rspValidObserved = false
      var readDataWay0: BigInt = 0
      val timedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles) {
        rspValidObserved = io.speculativeReadRspS1.valid.toBoolean
        if (rspValidObserved) {
          readDataWay0 = io.speculativeReadRspS1.payload(wayToTest).toBigInt
        }
        rspValidObserved
      }
      assert(!timedOut, "Timeout for speculative read after write")

      assert(readDataWay0 == storeData, f"Read-after-write mismatch. Expected ${storeData}%X, Got ${readDataWay0}%X")

      cd.waitSampling(10)
    }
  }

  test("DataRamBanksComponent - TP3.3 - Back-to-back Speculative Reads") {
    val p = defaultCacheParams.copy(wayCount = 1)
    simConfig.compile(new DataRamBanksTestBench(p)).doSim(seed = Random.nextInt()) { dutTb =>
      import DataRamBankTestHelpers._
      implicit val cd = dutTb.clockDomain
      dutTb.clockDomain.forkStimulus(10)
      val io = dutTb.io
      resetDutInputs(io)

      val lineIdx = 6
      val wayToTest = 0
      // Line: Word1 (MSB) | Word0 (LSB)
      val lineData = BigInt("DDCCBBAA0000000011223344", 16)
      val word0Expected = BigInt("11223344", 16)
      val word2Expected = BigInt("DDCCBBAA", 16)
      prefillWay(io, wayToTest, lineIdx, lineData, cd)
      cd.waitSampling(5)

      val receivedData = mutable.ArrayBuffer[BigInt]()
      val scoreboard = new ScoreboardInOrder[BigInt]() // Simple scoreboard

      // Monitor responses
      var transactionsReceived = 0
      FlowMonitor(io.speculativeReadRspS1, cd) { payload =>
        val data = payload(wayToTest).toBigInt
        ParallaxLogger.log(f"Back-to-back: Received data ${data}%X")
        scoreboard.pushDut(data)
        transactionsReceived += 1
      }

      // Drive two speculative reads back-to-back
      // Fork to allow commands to be potentially issued closer together
      // if DUT's ready allows.
      scoreboard.pushRef(word0Expected)
      driveSingleSpecReadCmd(io, lineIdx, 0, cd) // Read word 0

      scoreboard.pushRef(word2Expected)
      driveSingleSpecReadCmd(io, lineIdx, 2, cd) // Read word 2

      val timedOut = cd.waitSamplingWhere(testSpecificTimeoutCycles * 3) {
        transactionsReceived == 2
      }
      assert(!timedOut, "Timeout waiting for two back-to-back speculative reads")

      cd.waitSampling(10)
    }
  }
  // --- NEW TESTS END HERE ---

  thatsAll() // Collect all tests defined with 'test("...")'
}
