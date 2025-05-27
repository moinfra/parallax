package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import parallax.common._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// Testbench (no changes)
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

// DummyMsg (no changes)
case class DummyMsg() extends CdbTargetedMessage[UInt] {
  val robIdx = UInt(32 bits)
  override def cdbTargetIdx: UInt = robIdx
  def setDefaultSimValuesOnPort(portPayload: DummyMsg): Unit = { portPayload.robIdx #= 0 }
}

object DummyMsg {
  def apply(robIdxVal: BigInt): DummyMsg = {
    val msg = new DummyMsg()
    msg
  }
}

// --- StreamMonitorCounter Class ---
class StreamMonitorCounter[T <: Data](stream: Stream[T], clockDomain: ClockDomain,เก็บPayload: Boolean = false) {
  private var _transactionCount = 0L // Use Long for potentially many transactions
  private val _receivedDataInternal = mutable.ArrayBuffer[BigInt]() // Store BigInt if T is DummyMsg

  StreamMonitor(stream, clockDomain) { payload =>
    _transactionCount += 1
    if (เก็บPayload) {
      payload match {
        case p: DummyMsg => _receivedDataInternal += p.robIdx.toBigInt
        case _           => // Or handle other types, or throw an error
      }
    }
  }
  def transactionCount: Long = _transactionCount
  def receivedData: List[BigInt] = _receivedDataInternal.toList
}

// --- StreamMonitorAggregator Class ---
case class MonitoredTransaction[T <: Data](payload: T, outputPort: Int, time: Long)

object StreamMonitorAggregator {
    def apply[T <: Data](streams: Vec[Stream[T]], clockDomain: ClockDomain, clonePayload: Boolean = true)
                        (implicit tag: reflect.ClassTag[T]): StreamMonitorAggregator[T] = {
        new StreamMonitorAggregator(streams, clockDomain, clonePayload)
    }
}
class StreamMonitorAggregator[T <: Data](streams: Vec[Stream[T]], clockDomain: ClockDomain, clonePayload: Boolean = true)
                                        (implicit tag: reflect.ClassTag[T]){
    val receivedTransactions = mutable.ArrayBuffer[MonitoredTransaction[T]]()
    streams.zipWithIndex.foreach { case (stream, portIdx) =>
        StreamMonitor(stream, clockDomain) { payload =>
            val payloadToStore = if(clonePayload && payload != null) {
                val p = payload.asInstanceOf[Data] // Cast to Data for clone
                val c = cloneOf(p)
                c.assignFrom(p)
                c.asInstanceOf[T]
            } else {
                payload
            }
            receivedTransactions += MonitoredTransaction(payloadToStore, portIdx, simTime())
        }
    }
}


class GenericCdbArbiterSpec extends CustomSpinalSimFunSuite {

  val basePcfg = PipelineConfig() 
  implicit val uintToBitsEv: UInt => Bits = _.asBits

  case class ArbiterTestParams(numInputs: Int, numOutputs: Int, enableLog: Boolean = false)
  val testParams1_1 = ArbiterTestParams(numInputs = 1, numOutputs = 1, enableLog = false) // Less log for passing tests
  val testParams4_2 = ArbiterTestParams(numInputs = 4, numOutputs = 2, enableLog = true)
  val testParams2_4 = ArbiterTestParams(numInputs = 2, numOutputs = 4, enableLog = true)
  val testParams0_1 = ArbiterTestParams(numInputs = 0, numOutputs = 1, enableLog = false)

  val defaultTimeout = 200 

  // --- EXISTING PASSED TESTS (using SimpleStreamDrive now) ---
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

        SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(0), cd, inputTransactions) { (payload, data) =>
          payload.robIdx #= data
        }
        StreamMonitor(io.arbiterOutputs(0), cd) { payload =>
          outputTransactions.enqueue(payload.robIdx.toBigInt)
        }
        io.arbiterOutputs(0).ready #= true

        val data1 = 10L
        inputTransactions.enqueue(data1)
        var timedOut = dutTb.clockDomain.waitSamplingWhere(defaultTimeout)(outputTransactions.nonEmpty)
        assert(!timedOut, "Timeout waiting for first transaction")
        if (outputTransactions.nonEmpty) assert(outputTransactions.dequeue() == data1, "Data mismatch for first transaction")

        val data2_3 = Seq(20L, 30L)
        data2_3.foreach(inputTransactions.enqueue(_))
        timedOut = dutTb.clockDomain.waitSamplingWhere(defaultTimeout)(outputTransactions.size == data2_3.length)
        assert(!timedOut, s"Timeout waiting for sequence of ${data2_3.length} transactions")
        data2_3.foreach(expected => if (outputTransactions.nonEmpty) assert(outputTransactions.dequeue() == expected, "Data mismatch in sequence"))
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

        SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(0), cd, inputTransactions) { (payload, data) =>
          payload.robIdx #= data
        }
        StreamMonitor(io.arbiterOutputs(0), cd) { payload =>
          outputTransactions.enqueue(payload.robIdx.toBigInt)
        }
        StreamReadyRandomizer(io.arbiterOutputs(0), dutTb.clockDomain)

        val testData = Seq(10L, 11L, 12L, 13L, 14L)
        testData.foreach(inputTransactions.enqueue(_))
        val timedOut = dutTb.clockDomain.waitSamplingWhere(defaultTimeout * testData.length + 200) (outputTransactions.size == testData.length) 
        assert(!timedOut, "Timeout waiting for all transactions with backpressure")
        testData.foreach { expected =>
          if (outputTransactions.nonEmpty) assert(outputTransactions.dequeue() == expected, "Data mismatch with backpressure")
        }
        sleep(10)
      }
  }

  test("GenericCdbArbiter - 4 Inputs, 2 Outputs - Arbitration and Load Balancing (Revised Driver)") {
    val p = testParams4_2.copy(enableLog = true) 
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
          SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(idx), cd, q) { (payload, data) =>
            payload.robIdx #= data
          }
        }
        cdbOutputQueues.zipWithIndex.foreach { case (q, idx) =>
          StreamMonitor(io.arbiterOutputs(idx), cd) { payload => q.enqueue(payload.robIdx.toBigInt) }
          io.arbiterOutputs(idx).ready #= true
        }

        val numTxPerEu = 5
        val totalTx = p.numInputs * numTxPerEu
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) {
            euInputQueues(euIdx).enqueue((euIdx * 50 + i).toLong)
          }
        }
        
        cd.waitSampling(25) 

        val timeoutCycles = defaultTimeout * totalTx / p.numOutputs + 500 
        val timedOut = dutTb.clockDomain.waitSamplingWhere(timeoutCycles) (cdbOutputQueues.map(_.size).sum == totalTx) 
        assert(!timedOut, s"Timeout waiting for all M:N transactions. Got ${cdbOutputQueues.map(_.size).sum}/$totalTx")

        val cdb0Count = cdbOutputQueues(0).size
        val cdb1Count = cdbOutputQueues(1).size
        println(s"CDB0 received $cdb0Count, CDB1 received $cdb1Count transactions.")
        assert(cdb0Count + cdb1Count == totalTx, "Total transaction count mismatch")

        val expectedPerCdb = totalTx.toDouble / p.numOutputs
        val tolerance = Math.max(p.numInputs.toDouble, expectedPerCdb * 0.3).toInt + 1 
        
        println(s"Expected per CDB: $expectedPerCdb, Tolerance: $tolerance")

        assert(Math.abs(cdb0Count - expectedPerCdb) <= tolerance, s"CDB0 count $cdb0Count not balanced around $expectedPerCdb (tolerance $tolerance)")
        assert(Math.abs(cdb1Count - expectedPerCdb) <= tolerance, s"CDB1 count $cdb1Count not balanced around $expectedPerCdb (tolerance $tolerance)")
        
        val allReceivedItems = cdbOutputQueues.flatten.toSet
        val originalSentItems = mutable.HashSet[BigInt]()
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) { originalSentItems.add((euIdx * 50 + i).toLong) }
        }
        assert(allReceivedItems == originalSentItems, "Mismatch between sent and received items values")
        sleep(10)
      }
  }

  test("GenericCdbArbiter - 2 Inputs, 4 Outputs - Some outputs unused (Revised Driver)") {
    val p = testParams2_4.copy(enableLog = true)
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 703) { dutTb =>
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val input0Queue = mutable.Queue(BigInt(10L), BigInt(11L))
        val input1Queue = mutable.Queue(BigInt(20L), BigInt(21L))
        val outputQueues = IndexedSeq.fill(p.numOutputs)(mutable.Queue[BigInt]())

        SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(0), cd, input0Queue) { (payload, data) =>
          payload.robIdx #= data
        }
        SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(1), cd, input1Queue) { (payload, data) =>
          payload.robIdx #= data
        }

        outputQueues.zipWithIndex.foreach { case (q, idx) =>
          StreamMonitor(io.arbiterOutputs(idx), cd) { p => q.enqueue(p.robIdx.toBigInt) }
          io.arbiterOutputs(idx).ready #= true
        }

        cd.waitSampling(25) 

        val numTx = 4 
        val timedOut = dutTb.clockDomain.waitSamplingWhere(defaultTimeout) (outputQueues.map(_.size).sum == numTx)
        assert(!timedOut, "Timeout waiting for all transactions in 2-to-4 test")

        println(s"[Test End] Transactions received. Output Queues (2-to-4):")
        outputQueues.zipWithIndex.foreach { case (q, i) => println(s"  Out[${i.toString()}]: ${q.toList.mkString(",")}") }

        assert(outputQueues(0).toList == List(10L, 11L), s"CDB0 data mismatch. Expected (10,11), Got: ${outputQueues(0).toList.mkString(",")}")
        assert(outputQueues(1).toList == List(20L, 21L), s"CDB1 data mismatch. Expected (20,21), Got: ${outputQueues(1).toList.mkString(",")}")
        assert(outputQueues(2).isEmpty, s"CDB2 should be empty, Got: ${outputQueues(2).toList.mkString(",")}")
        assert(outputQueues(3).isEmpty, s"CDB3 should be empty, Got: ${outputQueues(3).toList.mkString(",")}")
        sleep(10)
      }
  }

  // --- NEW TEST CASE 1: Sustained Load Fairness (8 Inputs, 2 Outputs) ---
  test("GenericCdbArbiter - 8 Inputs, 2 Outputs - Sustained Load Fairness") {
    val numTestInputs = 8
    val numTestOutputs = 2
    // Enable detailed DUT logging for this new complex test initially
    val p = ArbiterTestParams(numInputs = numTestInputs, numOutputs = numTestOutputs, enableLog = true) 
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 705) { dutTb => // New seed
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val euInputQueues = IndexedSeq.fill(p.numInputs)(mutable.Queue[BigInt]())
        // Use StreamMonitorCounter to easily get counts and optionally data
        val cdbOutputMonitors = IndexedSeq.tabulate(p.numOutputs){ idx =>
            new StreamMonitorCounter(io.arbiterOutputs(idx), cd,เก็บPayload = true)
        }

        euInputQueues.zipWithIndex.foreach { case (q, idx) =>
          SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(idx), cd, q) { (payload, data) =>
            payload.robIdx #= data
          }
        }
        io.arbiterOutputs.foreach(_.ready #= true) // Outputs always ready

        val numTxPerEu = 200 // More transactions for a better fairness assessment
        val totalTx = p.numInputs * numTxPerEu
        
        println(s"[Test Start ${p.numInputs}x${p.numOutputs} Fairness] Sending $totalTx total transactions...")
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) {
            euInputQueues(euIdx).enqueue(BigInt(euIdx * 100000 + i)) // Ensure unique data
          }
        }
        
        cd.waitSampling(30) // Allow drivers to stabilize

        val timeoutCycles = defaultTimeout + totalTx * 3 + 1000 // Generous timeout for many transactions
        val timedOut = dutTb.clockDomain.waitSamplingWhere(timeoutCycles) {
          cdbOutputMonitors.map(_.transactionCount).sum >= totalTx
        }
        
        val finalSum = cdbOutputMonitors.map(_.transactionCount).sum
        assert(!timedOut, s"Timeout: Expected $totalTx transactions, got $finalSum")
        println(s"[Test End ${p.numInputs}x${p.numOutputs} Fairness] Transactions processed: $finalSum")

        val counts = cdbOutputMonitors.map(_.transactionCount)
        counts.zipWithIndex.foreach { case (count, idx) =>
          println(s"  Output[${idx.toString()}] received $count transactions.")
        }
        assert(counts.sum == totalTx, "Total transaction count mismatch after processing.")

        val expectedPerOutput = totalTx.toDouble / p.numOutputs
        val fairnessToleranceRatio = 0.15 // Allow 15% deviation
        val fairnessAbsoluteTolerance = (expectedPerOutput * fairnessToleranceRatio).toLong
        
        counts.foreach { count =>
          assert(Math.abs(count - expectedPerOutput) <= Math.max(p.numInputs.toLong, fairnessAbsoluteTolerance),
                 s"Output count $count is too far from expected mean $expectedPerOutput (tolerance ${Math.max(p.numInputs.toLong, fairnessAbsoluteTolerance)})")
        }
        
        val allReceivedItems = cdbOutputMonitors.flatMap(_.receivedData).toSet
        val originalSentItems = mutable.HashSet[BigInt]()
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) { 
            originalSentItems.add(BigInt(euIdx * 100000 + i)) 
          }
        }
        assert(allReceivedItems.size == totalTx, s"Number of unique received items ${allReceivedItems.size} does not match total transactions $totalTx")
        assert(allReceivedItems == originalSentItems, "Mismatch between unique sent and received items values")

        sleep(20)
      }
  }

  // --- NEW TEST CASE 2: Output Backpressure with M:N (4 Inputs, 2 Outputs) ---
  test("GenericCdbArbiter - 4 Inputs, 2 Outputs - Output Backpressure") {
    val p = testParams4_2.copy(enableLog = true)
    val payloadHT = HardType(DummyMsg())
    simConfig
      .compile(new GenericCdbArbiterTestBench[UInt, DummyMsg](payloadHT, p.numInputs, p.numOutputs, p.enableLog))
      .doSim(seed = 706) { dutTb => // New seed
        implicit val cd = dutTb.clockDomain
        val io = dutTb.io
        dutTb.clockDomain.forkStimulus(10)

        val euInputQueues = IndexedSeq.fill(p.numInputs)(mutable.Queue[BigInt]())
        val cdbOutputMonitors = IndexedSeq.tabulate(p.numOutputs){idx =>
            new StreamMonitorCounter(io.arbiterOutputs(idx), cd,เก็บPayload = true)
        }

        euInputQueues.zipWithIndex.foreach { case (q, idx) =>
          SimTestHelpers.SimpleStreamDrive(io.arbiterInputs(idx), cd, q) { (payload, data) =>
            payload.robIdx #= data
          }
        }

        // Output 0 is always ready
        io.arbiterOutputs(0).ready #= true
        // Output 1 has random backpressure
        StreamReadyRandomizer(io.arbiterOutputs(1), cd).setFactor(0.3f) // Low ready factor for significant backpressure

        val numTxPerEu = 50 // Send a decent number of transactions
        val totalTx = p.numInputs * numTxPerEu

        println(s"[Test Start ${p.numInputs}x${p.numOutputs} Backpressure] Sending $totalTx total transactions...")
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) {
            euInputQueues(euIdx).enqueue(BigInt(euIdx * 1000 + i)) // Unique data
          }
        }
        cd.waitSampling(30)

        val timeoutCycles = defaultTimeout + totalTx * 10 + 1000 // Increased timeout due to backpressure
        val timedOut = dutTb.clockDomain.waitSamplingWhere(timeoutCycles) {
          cdbOutputMonitors.map(_.transactionCount).sum >= totalTx
        }
        val finalSum = cdbOutputMonitors.map(_.transactionCount).sum
        assert(!timedOut, s"Timeout: Expected $totalTx transactions, got $finalSum")
        println(s"[Test End ${p.numInputs}x${p.numOutputs} Backpressure] Transactions processed: $finalSum")

        val counts = cdbOutputMonitors.map(_.transactionCount)
        counts.zipWithIndex.foreach { case (count, idx) =>
          println(s"  Output[${idx.toString()}] received $count transactions.")
        }
        assert(counts.sum == totalTx, "Total transaction count mismatch after processing.")

        // With backpressure on output 1, output 0 should take significantly more.
        assert(counts(0) > counts(1), "Output 0 should have processed more transactions than backpressured Output 1")
        println(s"Output 0 count: ${counts(0)}, Output 1 count: ${counts(1)}")

        val allReceivedItems = cdbOutputMonitors.flatMap(_.receivedData).toSet
        val originalSentItems = mutable.HashSet[BigInt]()
        for (euIdx <- 0 until p.numInputs) {
          for (i <- 0 until numTxPerEu) { 
            originalSentItems.add(BigInt(euIdx * 1000 + i)) 
          }
        }
        assert(allReceivedItems.size == totalTx, s"Number of unique received items ${allReceivedItems.size} does not match total transactions $totalTx")
        assert(allReceivedItems == originalSentItems, "Mismatch between unique sent and received items values")

        sleep(20)
      }
  }

  thatsAll()
}
