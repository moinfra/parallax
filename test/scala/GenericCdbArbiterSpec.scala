package parallax.test.scala // Corrected package

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder} // Added Scoreboard
import parallax.common._ // For CdbTargetedMessage, GenericCdbArbiter, PipelineConfig
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// Testbench for GenericCdbArbiter (remains the same)
class GenericCdbArbiterTestBench[K <: Data, T <: CdbTargetedMessage[K]](
    payloadHardType: HardType[T],
    numInputs: Int,
    numOutputs: Int,
    enableLogVal: Boolean
) extends Component {
  case class IO() extends Bundle {
    val arbiterInputs = Vec(slave Stream (payloadHardType()), numInputs)
    val arbiterOutputs = Vec(master Stream (payloadHardType()), numOutputs)
  }
  val io = new IO()
  val dut = new GenericCdbArbiter[K, T](
    payloadHardType,
    numInputs,
    numOutputs,
    arbiterId = "UnitTestArbiter",
    enableLog = enableLogVal
  )
  dut.io.inputs <> io.arbiterInputs
  dut.io.outputs <> io.arbiterOutputs
  io.simPublic()
}

// DummyMsg for testing (remains the same)
case class DummyMsg() extends CdbTargetedMessage[UInt] {
  val robIdx = UInt(8 bits)
  override def cdbTargetIdx: UInt = robIdx
  def setDefaultSimValuesOnPort(portPayload: DummyMsg): Unit = { portPayload.robIdx #= 0 }
}

object DummyMsg {
  // Factory to create a Scala instance with a value (NOT for #=)
  def apply(robIdxVal: BigInt): DummyMsg = {
    val msg = new DummyMsg()
    msg // robIdx will be assigned in the driver
  }
}

class GenericCdbArbiterSpec extends CustomSpinalSimFunSuite {

  val basePcfg = PipelineConfig() // Might not be used if DummyMsg is self-contained

  // Implicit evidence for K (UInt) to Bits if GenericCdbArbiter's logging needs it
  implicit val uintToBitsEv: UInt => Bits = _.asBits

  case class ArbiterTestParams(numInputs: Int, numOutputs: Int, enableLog: Boolean = false)
  val testParams1_1 = ArbiterTestParams(numInputs = 1, numOutputs = 1)
  val testParams4_2 = ArbiterTestParams(numInputs = 4, numOutputs = 2)
  val testParams2_4 = ArbiterTestParams(numInputs = 2, numOutputs = 4)
  val testParams0_1 = ArbiterTestParams(numInputs = 0, numOutputs = 1)

  test("GenericCdbArbiter - 1 Input, 1 Output - Basic Passthrough") {
    val p = testParams1_1
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 700) { dutTb =>
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val inputTransactions = mutable.Queue[BigInt]()
        val outputTransactions = mutable.Queue[BigInt]()

        StreamDriver(io.arbiterInputs(0), cd) { payload =>
          if (inputTransactions.nonEmpty) {
            payload.robIdx #= inputTransactions.dequeue() // Dequeue and use
            true
          } else {
            false
          }
        }
        StreamMonitor(io.arbiterOutputs(0), cd) { payload =>
          outputTransactions.enqueue(payload.robIdx.toBigInt)
        }
        // Consumer always ready for this simple test
        io.arbiterOutputs(0).ready #= true

        // Test 1: Send one transaction
        val data1 = 10L
        inputTransactions.enqueue(data1)
        waitUntil(outputTransactions.nonEmpty)
        assert(outputTransactions.dequeue() == data1, "Data mismatch for first transaction")

        // Test 2: Send a few more
        val data2_3 = Seq(20L, 30L)
        data2_3.foreach(inputTransactions.enqueue(_))
        waitUntil(outputTransactions.size == data2_3.length)
        data2_3.foreach(expected => assert(outputTransactions.dequeue() == expected, "Data mismatch in sequence"))

        sleep(10)
      }
  }

  test("GenericCdbArbiter - 1 Input, 1 Output - Backpressure") {
    val p = testParams1_1
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 701) { dutTb =>
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val inputTransactions = mutable.Queue[BigInt]()
        val outputTransactions = mutable.Queue[BigInt]()

        StreamDriver(io.arbiterInputs(0), cd) { payload =>
          if (inputTransactions.nonEmpty) {
            payload.robIdx #= inputTransactions.dequeue() // Dequeue and use
            true
          } else { false }
        }

        StreamMonitor(io.arbiterOutputs(0), cd) { payload =>
          outputTransactions.enqueue(payload.robIdx.toBigInt)
        }

        StreamReadyRandomizer(io.arbiterOutputs(0), dutTb.clockDomain)

        val testData = Seq(10L, 11L, 12L, 13L, 14L)
        testData.foreach(inputTransactions.enqueue(_))

        waitUntil(outputTransactions.size == testData.length)

        testData.foreach { expected =>
          assert(outputTransactions.dequeue() == expected, "Data mismatch with backpressure")
        }
        sleep(10)
      }
  }

  test("GenericCdbArbiter - 4 Inputs, 2 Outputs - Arbitration and Load Balancing") {
    val p = testParams4_2
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 702) { dutTb =>
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val euInputQueues = IndexedSeq.fill(p.numInputs)(mutable.Queue[BigInt]())
        val cdbOutputQueues = IndexedSeq.fill(p.numOutputs)(mutable.Queue[BigInt]())

        euInputQueues.zipWithIndex.foreach { case (q, idx) =>
          StreamDriver(io.arbiterInputs(idx), cd) { payload =>
            if (q.nonEmpty) {
              payload.robIdx #= q.dequeue() // Dequeue and use
              true
            } else { false }
          }
        }

        cdbOutputQueues.zipWithIndex.foreach { case (q, idx) =>
          StreamMonitor(io.arbiterOutputs(idx), cd) { payload =>
            q.enqueue(payload.robIdx.toBigInt)
          }
          io.arbiterOutputs(idx).ready #= true
        }

        val numTxPerEu = 5
        val totalTx = p.numInputs * numTxPerEu
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) {
            euInputQueues(euIdx).enqueue((euIdx * 50 + i).toLong) // Corrected: data within 8-bit range
          }
        }

        waitUntil(cdbOutputQueues.map(_.size).sum == totalTx)

        val cdb0Count = cdbOutputQueues(0).size
        val cdb1Count = cdbOutputQueues(1).size
        println(s"CDB0 received $cdb0Count, CDB1 received $cdb1Count transactions.")
        assert(cdb0Count + cdb1Count == totalTx, "Total transaction count mismatch")

        if (p.numInputs == 4 && p.numOutputs == 2) {
          assert(cdb0Count == 2 * numTxPerEu, "CDB0 should get tx from 2 EUs")
          assert(cdb1Count == 2 * numTxPerEu, "CDB1 should get tx from 2 EUs")
        }

        val allReceivedItems = cdbOutputQueues.flatten.toSet

        val originalSentItems = mutable.HashSet[BigInt]()
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) {
            originalSentItems.add((euIdx * 50 + i).toLong) // Corrected
          }
        }
        assert(allReceivedItems == originalSentItems, "Mismatch between sent and received items")
        sleep(10)
      }
  }

  test("GenericCdbArbiter - 2 Inputs, 4 Outputs - Some outputs unused") {
    val p = testParams2_4
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 703) { dutTb =>
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val input0Queue = mutable.Queue(10L, 11L)
        val input1Queue = mutable.Queue(20L, 21L)
        val outputQueues = IndexedSeq.fill(p.numOutputs)(mutable.Queue[BigInt]())

        StreamDriver(io.arbiterInputs(0), cd) { payload =>
          if (input0Queue.nonEmpty) { payload.robIdx #= input0Queue.dequeue(); true }
          else false
        }
        StreamDriver(io.arbiterInputs(1), cd) { payload =>
          if (input1Queue.nonEmpty) { payload.robIdx #= input1Queue.dequeue(); true }
          else false
        }

        outputQueues.zipWithIndex.foreach { case (q, idx) =>
          StreamMonitor(io.arbiterOutputs(idx), cd) { p => q.enqueue(p.robIdx.toBigInt) }
          io.arbiterOutputs(idx).ready #= true
        }

        val numTx = 2 + 2 // Manually sum sizes of input0Queue and input1Queue before they are modified by driver
        waitUntil(outputQueues.map(_.size).sum == numTx)

        assert(outputQueues(0).toList == List(10L, 11L), "CDB0 data mismatch")
        assert(outputQueues(1).toList == List(20L, 21L), "CDB1 data mismatch")
        assert(outputQueues(2).isEmpty, "CDB2 should be empty")
        assert(outputQueues(3).isEmpty, "CDB3 should be empty")

        sleep(10)
      }
  }

  thatsAll()
}
