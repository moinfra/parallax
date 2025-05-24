package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer}
import parallax.common._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// Testbench (不变)
class MallocNStreamArbiterTestBench[T_REQ <: Data, T_RSP <: Data](
    reqType: HardType[T_REQ],
    rspType: HardType[T_RSP],
    numRequesters: Int,
    numGrantSlots: Int,
    enableDutLog: Boolean // Assuming this is used inside DUT or passed differently
) extends Component {
  val io_tb = MallocNStreamArbiterIo(reqType, rspType, numRequesters, numGrantSlots)

  val dut = new MallocNStreamArbiter[T_REQ, T_RSP](
    reqType,
    rspType,
    numRequesters,
    numGrantSlots
  ) {
    // If your DUT has an 'enableLog' field that can be overridden or set:
    // override val enableLog = enableDutLog
    // Otherwise, ensure the DUT's constructor or internal logic handles logging based on a passed param if needed.
    // For this example, we'll assume the DUT's `enableLog` is managed by its primary constructor
    // or is a `var` that could be set if explicitly exposed.
    // The provided DUT code has `val enableLog = true` hardcoded, so the param here is for testbench structure.
  }

  io_tb.requester_req <> dut.io.requester_req
  io_tb.requester_rsp <> dut.io.requester_rsp
  io_tb.grant_req <> dut.io.grant_req
  io_tb.grant_rsp <> dut.io.grant_rsp
  io_tb.simPublic()
}

// DummyMsg2 (不变)
case class DummyMsg2() extends CdbTargetedMessage[UInt] {
  val robIdx = UInt(8 bits)
  override def cdbTargetIdx: UInt = robIdx
}

// --- SimTestHelpers2 Object (不变) ---
object SimTestHelpers2 {
  def SimpleStreamDriveRobIdx(
      stream: Stream[DummyMsg2],
      clockDomain: ClockDomain,
      queue: mutable.Queue[BigInt]
  ): Unit = {
    fork {
      stream.valid #= false
      var activeData: Option[BigInt] = None

      while (true) {
        if (activeData.isEmpty) {
          if (queue.nonEmpty) {
            activeData = Some(queue.head)
          }
        }

        if (activeData.isDefined) {
          stream.payload.robIdx #= activeData.get
          stream.valid #= true

          clockDomain.waitSamplingWhere(stream.ready.toBoolean && stream.valid.toBoolean)

          if (queue.nonEmpty && activeData.isDefined && queue.headOption == activeData) {
            queue.dequeue()
          }
          activeData = None
          stream.valid #= false
        } else {
          stream.valid #= false
          clockDomain.waitSampling()
        }
      }
    }
  }
}

// --- DummyMsg2Collector (不变) ---
class DummyMsg2Collector(stream: Stream[DummyMsg2], clockDomain: ClockDomain) {
  val receivedRobIdxs = mutable.Queue[BigInt]()
  StreamMonitor(stream, clockDomain) { payload =>
    receivedRobIdxs.enqueue(payload.robIdx.toBigInt)
  }
}


// --- Grant Slot Processor Simulation Helper (不变) ---
object GrantSlotProcessorSim {
  def apply(
      grantReqPort: Stream[DummyMsg2],
      grantRspPort: Stream[DummyMsg2],
      grantSlotIdx: Int,
      clockDomain: ClockDomain,
      rspRobIdxGen: (BigInt) => BigInt,
      processingDelayCycles: () => Int = () => Random.nextInt(5) + 1,
      enableProcessingLog: Boolean = false,
      logPrefix: String = "GrantSlot"
  ): Unit = {
    fork {
      grantReqPort.ready #= false
      grantRspPort.valid #= false
      grantRspPort.payload.robIdx.randomize()

      while(true) {
        grantReqPort.ready #= true
        clockDomain.waitSamplingWhere(grantReqPort.valid.toBoolean && grantReqPort.ready.toBoolean)

        val receivedReqRobIdx = grantReqPort.payload.robIdx.toBigInt
        grantReqPort.ready #= false

        if(enableProcessingLog) println(s"[SimTime ${simTime()}] $logPrefix[${grantSlotIdx.toString()}] RECV REQ_robIdx: ${receivedReqRobIdx}")

        val delay = processingDelayCycles()
        if(delay > 0) sleep(delay)

        val responseRobIdxToSend = rspRobIdxGen(receivedReqRobIdx)

        grantRspPort.payload.robIdx #= responseRobIdxToSend
        grantRspPort.valid #= true

        if(enableProcessingLog) println(s"[SimTime ${simTime()}] $logPrefix[${grantSlotIdx.toString()}] SEND RSP_robIdx: ${responseRobIdxToSend}")

        clockDomain.waitSamplingWhere(grantRspPort.valid.toBoolean && grantRspPort.ready.toBoolean)
        grantRspPort.valid #= false
      }
    }
  }
}


class MallocNStreamArbiterSpec extends CustomSpinalSimFunSuite {

  implicit val uintToBitsEv: UInt => Bits = _.asBits // If needed for specific conversions

  case class MallocTestParams(
      numRequesters: Int,
      numGrantSlots: Int,
      enableDutLog: Boolean = false,
      enableTestLog: Boolean = false
  )

  val defaultTimeout = 500

  val mallocTest_1_1 = MallocTestParams(1, 1, enableDutLog = true, enableTestLog = true)
  val mallocTest_2_1 = MallocTestParams(2, 1, enableDutLog = true, enableTestLog = true)

  // --- Existing Test 1 (不变) ---
  test("MallocNStreamArbiter - 1 Req, 1 Slot - Basic Request-Response") {
    val p = mallocTest_1_1
    val reqHT = HardType(DummyMsg2())
    val rspHT = HardType(DummyMsg2())

    simConfig
      .compile(new MallocNStreamArbiterTestBench[DummyMsg2, DummyMsg2](reqHT, rspHT, p.numRequesters, p.numGrantSlots, p.enableDutLog))
      .doSim(seed = 800) { dutTb =>
        implicit val cd = dutTb.clockDomain
        dutTb.clockDomain.forkStimulus(10)

        val requester0_req_q = mutable.Queue[BigInt]()
        val requester0_rsp_collector = new DummyMsg2Collector(dutTb.io_tb.requester_rsp(0), cd)

        SimTestHelpers2.SimpleStreamDriveRobIdx(dutTb.io_tb.requester_req(0), cd, requester0_req_q)

        GrantSlotProcessorSim(
          dutTb.io_tb.grant_req(0), dutTb.io_tb.grant_rsp(0), 0, cd,
          rspRobIdxGen = (reqRobIdx) => (reqRobIdx + 100) & 0xFFL,
          enableProcessingLog = p.enableTestLog
        )

        dutTb.io_tb.requester_rsp(0).ready #= true

        val tx1_req_robIdx = 10L
        requester0_req_q.enqueue(tx1_req_robIdx)
        if(p.enableTestLog) println(s"[Test 1-1] Sent REQ_robIdx: ${tx1_req_robIdx}")

        var timedOut = cd.waitSamplingWhere(defaultTimeout)(requester0_rsp_collector.receivedRobIdxs.nonEmpty)
        assert(!timedOut, "Timeout waiting for first response")

        assert(requester0_rsp_collector.receivedRobIdxs.nonEmpty, "Response queue should not be empty")
        val tx1_rsp_robIdx = requester0_rsp_collector.receivedRobIdxs.dequeue()
        val expectedRspRobIdx = (10L + 100L) & 0xFFL
        assert(tx1_rsp_robIdx == expectedRspRobIdx,
               s"RSP1 mismatch. Expected robIdx $expectedRspRobIdx, got ${tx1_rsp_robIdx}")
        if(p.enableTestLog) println(s"[Test 1-1] Recv RSP_robIdx: ${tx1_rsp_robIdx}")

        val tx2_req_robIdx = 25L
        requester0_req_q.enqueue(tx2_req_robIdx)
        if(p.enableTestLog) println(s"[Test 1-1] Sent REQ_robIdx: ${tx2_req_robIdx}")

        timedOut = cd.waitSamplingWhere(defaultTimeout)(requester0_rsp_collector.receivedRobIdxs.nonEmpty)
        assert(!timedOut, "Timeout waiting for second response")

        assert(requester0_rsp_collector.receivedRobIdxs.nonEmpty, "Second response queue should not be empty")
        val tx2_rsp_robIdx = requester0_rsp_collector.receivedRobIdxs.dequeue()
        val expectedRspRobIdx2 = (25L + 100L) & 0xFFL
        assert(tx2_rsp_robIdx == expectedRspRobIdx2,
               s"Second response data mismatch. Expected robIdx $expectedRspRobIdx2, got ${tx2_rsp_robIdx}")
        if(p.enableTestLog) println(s"[Test 1-1] Recv RSP_robIdx: ${tx2_rsp_robIdx}")

        cd.waitSampling(20)
      }
  }

  // --- Existing Test 2 (不变) ---
  test("MallocNStreamArbiter - 2 Requesters, 1 Slot - Request RoundRobin") {
    val p = mallocTest_2_1
    val reqHT = HardType(DummyMsg2())
    val rspHT = HardType(DummyMsg2())

    simConfig
      .compile(new MallocNStreamArbiterTestBench[DummyMsg2, DummyMsg2](reqHT, rspHT, p.numRequesters, p.numGrantSlots, p.enableDutLog))
      .doSim(seed = 801) { dutTb =>
        implicit val cd = dutTb.clockDomain
        dutTb.clockDomain.forkStimulus(10)

        val reqQueues = IndexedSeq.fill(p.numRequesters)(mutable.Queue[BigInt]())
        val rspCollectors = IndexedSeq.tabulate(p.numRequesters)(idx => new DummyMsg2Collector(dutTb.io_tb.requester_rsp(idx), cd))

        reqQueues.zipWithIndex.foreach { case (q, idx) =>
          SimTestHelpers2.SimpleStreamDriveRobIdx(dutTb.io_tb.requester_req(idx), cd, q)
        }
        dutTb.io_tb.requester_rsp.foreach(_.ready #= true)

        GrantSlotProcessorSim(
          dutTb.io_tb.grant_req(0), dutTb.io_tb.grant_rsp(0), 0, cd,
          rspRobIdxGen = (reqRobIdx) => (reqRobIdx + 100) & 0xFFL,
          enableProcessingLog = p.enableTestLog
        )

        val numTxPerReq = 2
        val expectedResponses = mutable.Map[Int, mutable.Queue[BigInt]]()
        for(r <- 0 until p.numRequesters) expectedResponses(r) = mutable.Queue[BigInt]()

        for (i <- 0 until numTxPerReq) {
          for (reqIdx <- 0 until p.numRequesters) {
            val robIdxVal = BigInt(reqIdx * 100 + i * 10 + reqIdx)
            reqQueues(reqIdx).enqueue(robIdxVal)
            expectedResponses(reqIdx).enqueue((robIdxVal + 100) & 0xFFL)
            if(p.enableTestLog) println(s"[Test 2R-1S] Req[${reqIdx.toString}] Enqueued REQ_robIdx=${robIdxVal}")
          }
        }

        val totalTx = numTxPerReq * p.numRequesters
        val timedOut = cd.waitSamplingWhere(defaultTimeout * totalTx + 200) {
          rspCollectors.map(_.receivedRobIdxs.size).sum == totalTx
        }
        assert(!timedOut, s"Timeout waiting for all $totalTx responses. Got ${rspCollectors.map(_.receivedRobIdxs.size).sum}")

        for (reqIdx <- 0 until p.numRequesters) {
          assert(rspCollectors(reqIdx).receivedRobIdxs.size == numTxPerReq, s"Requester $reqIdx did not receive all $numTxPerReq responses.")
          for (i <- 0 until numTxPerReq) {
            val expected = expectedResponses(reqIdx).dequeue()
            val received = rspCollectors(reqIdx).receivedRobIdxs.dequeue()
            assert(received == expected, s"RSP mismatch for Req[$reqIdx], tx $i. Expected $expected, got $received")
          }
        }
        if(p.enableTestLog) println(s"[Test 2R-1S] All responses verified.")
        cd.waitSampling(20)
      }
  }

  // --- NEW TEST CASE for Single Cycle Response Path ---
  test("MallocNStreamArbiter - Single Cycle Response Path Forwarding") {
    val p = MallocTestParams(numRequesters = 1, numGrantSlots = 1, enableDutLog = true, enableTestLog = true)
    val reqHT = HardType(DummyMsg2())
    val rspHT = HardType(DummyMsg2())

    simConfig
      .compile(new MallocNStreamArbiterTestBench[DummyMsg2, DummyMsg2](reqHT, rspHT, p.numRequesters, p.numGrantSlots, p.enableDutLog))
      .doSim(seed = 802) { dutTb =>
        implicit val cd = dutTb.clockDomain
        dutTb.clockDomain.forkStimulus(10)

        val requester0_req_q = mutable.Queue[BigInt]()
        val requester0_rsp_collector = new DummyMsg2Collector(dutTb.io_tb.requester_rsp(0), cd)

        SimTestHelpers2.SimpleStreamDriveRobIdx(dutTb.io_tb.requester_req(0), cd, requester0_req_q)

        // GrantSlotProcessor will introduce a small delay *after* grant
        // before sending its response on grant_rsp. This ensures slotBusy/Owner regs are set in DUT.
        val grantSlotProcessingDelay = 1 // At least 1 cycle for slotOwnerRegs to be updated from the grant
        GrantSlotProcessorSim(
          dutTb.io_tb.grant_req(0),
          dutTb.io_tb.grant_rsp(0),
          0, // grantSlotIdx
          cd,
          rspRobIdxGen = (reqRobIdx) => (reqRobIdx + 100) & 0xFFL,
          processingDelayCycles = () => grantSlotProcessingDelay,
          enableProcessingLog = p.enableTestLog,
          logPrefix = "GrantSlotSCRPF"
        )

        dutTb.io_tb.requester_rsp(0).ready #= true // Consumer is always ready

        var grantRspFireCycleTime = -1L
        var requesterRspValidAtGrantRspFireCycle = false
        var requesterRspPayloadAtGrantRspFireCycle = BigInt(-1)
        var grantRspPayloadAtFire = BigInt(-1) // To store the payload of grant_rsp when it fires

        fork {
          while (true) {
            // We want to capture the state in the exact cycle where grant_rsp(0) *fires* (valid and ready)
            if (dutTb.io_tb.grant_rsp(0).valid.toBoolean &&
                dutTb.io_tb.grant_rsp(0).ready.toBoolean && // Ensure it actually fires
                grantRspFireCycleTime == -1L) { // Capture only the first time it fires for this test instance
  
              grantRspFireCycleTime = simTime()
              grantRspPayloadAtFire = dutTb.io_tb.grant_rsp(0).payload.robIdx.toBigInt
  
              // Check the DUT's output requester_rsp(0) in the *same* cycle
              requesterRspValidAtGrantRspFireCycle = dutTb.io_tb.requester_rsp(0).valid.toBoolean
              if (requesterRspValidAtGrantRspFireCycle) { // Only read payload if valid to avoid Xes
                requesterRspPayloadAtGrantRspFireCycle = dutTb.io_tb.requester_rsp(0).payload.robIdx.toBigInt
              }
  
              if (p.enableTestLog) {
                println(s"[SimTime ${grantRspFireCycleTime}] GrantSlotSCRPF[0] grant_rsp(0) FIRED. Payload: ${grantRspPayloadAtFire}")
                println(s"  >>> In SAME cycle: dut.requester_rsp(0).valid = ${requesterRspValidAtGrantRspFireCycle}, payload = ${if(requesterRspValidAtGrantRspFireCycle) requesterRspPayloadAtGrantRspFireCycle else "N/A"}")
              }
            }
            sleep(1)
          }
        }

        val testReqRobIdx = 55L
        requester0_req_q.enqueue(testReqRobIdx)
        if (p.enableTestLog) println(s"[Test SCRPF @ ${simTime()}] Enqueued REQ_robIdx: ${testReqRobIdx} to requester0_req_q")

        // Wait for the response to be collected by the standard collector.
        // This ensures the transaction (including the grant_rsp fire event) has occurred.
        val timedOut = cd.waitSamplingWhere(defaultTimeout) {
          requester0_rsp_collector.receivedRobIdxs.nonEmpty
        }
        assert(!timedOut, "Timeout waiting for response in collector. grant_rsp might not have fired.")

        // Now, perform the assertions based on the values captured by onSamplings
        assert(grantRspFireCycleTime != -1L, "grant_rsp(0) never fired. Check GrantSlotProcessorSim or DUT response path logic.")

        val expectedRspRobIdx = (testReqRobIdx + 100) & 0xFFL
        // Verify the payload that was on grant_rsp when it fired
        assert(grantRspPayloadAtFire == expectedRspRobIdx,
               s"Payload on grant_rsp(0) at fire (T=${grantRspFireCycleTime}) was ${grantRspPayloadAtFire}, expected ${expectedRspRobIdx}")

        // THE CRITICAL ASSERTION: requester_rsp was valid in the SAME cycle as grant_rsp fired
        assert(requesterRspValidAtGrantRspFireCycle,
               s"dut.requester_rsp(0).valid was false in the same cycle (T=${grantRspFireCycleTime}) that grant_rsp(0) fired.")

        // Verify the payload on requester_rsp in that same cycle
        assert(requesterRspPayloadAtGrantRspFireCycle == expectedRspRobIdx,
               s"dut.requester_rsp(0).payload was ${requesterRspPayloadAtGrantRspFireCycle} in cycle T=${grantRspFireCycleTime}, expected ${expectedRspRobIdx}")

        // Final sanity check with the collector
        assert(requester0_rsp_collector.receivedRobIdxs.nonEmpty, "Collector queue is empty after timeout condition met.")
        val collectedRsp = requester0_rsp_collector.receivedRobIdxs.dequeue()
        assert(collectedRsp == expectedRspRobIdx,
               s"Collector received ${collectedRsp}, expected ${expectedRspRobIdx}")

        if (p.enableTestLog) println(s"[Test SCRPF] Single cycle response path forwarding successfully verified for cycle ${grantRspFireCycleTime}.")
        cd.waitSampling(10) // Allow simulation to settle
      }
  }
  

  thatsAll()
}
