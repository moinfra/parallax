package test.scala

import parallax.execute.{BypassService, BypassPlugin}
import parallax.utilities._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{FlowDriver, FlowMonitor}
import spinal.tester.SpinalSimFunSuite

import scala.collection.mutable
import scala.util.Random

// Payload for testing
case class TestBypassPayload(data: UInt) extends Bundle {
  override def clone(): TestBypassPayload = TestBypassPayload(data.clone())
}

// Helper plugins (no changes from previous, but included for completeness)
class BypassTestSourcePlugin(euName: String, val payloadType: HardType[TestBypassPayload]) extends Plugin {
  var euProvidedFlow: Flow[TestBypassPayload] = null

  val setup = create early new Area {
    val bypassService = getService[BypassService[TestBypassPayload]]
    euProvidedFlow = bypassService.newBypassSource(euName).simPublic()
  }
}

class BypassTestConsumerPlugin(consumerName: String, val payloadType: HardType[TestBypassPayload]) extends Plugin {
  val io = new Bundle {
    val mergedBypassIn = slave(Flow(payloadType())).simPublic()
  }

  val logic = create late new Area {
    val bypassService = getService[BypassService[TestBypassPayload]]
    io.mergedBypassIn << bypassService.getBypassFlow(consumerName)
  }
}

class EarlyBypassTestConsumerPlugin(consumerName: String, val payloadType: HardType[TestBypassPayload]) extends Plugin {
  val io = new Bundle {
    val mergedBypassInEarly = slave(Flow(payloadType())).simPublic()
  }
  val setup = create early new Area {
    val bypassService = getService[BypassService[TestBypassPayload]]
    io.mergedBypassInEarly << bypassService.getBypassFlow(consumerName)
  }
}


class BypassPluginSpec extends SpinalSimFunSuite {
  def simConfig = SimConfig.withWave.withVcdWave
  val PAYLOAD_HT = HardType(TestBypassPayload(UInt(32 bits)))

  def createDut(numProducers: Int, includeConsumer: Boolean, includeEarlyConsumer: Boolean = false) = {
    class TestBench extends Component {
      val database = new DataBase
      val servicePlugin = new BypassPlugin(PAYLOAD_HT())
      val producerPluginsList = (0 until numProducers).map(i => new BypassTestSourcePlugin(s"EU$i", PAYLOAD_HT()))
      val consumerPluginOpt = if (includeConsumer) Some(new BypassTestConsumerPlugin("NormalConsumer", PAYLOAD_HT())) else None
      val earlyConsumerPluginOpt = if (includeEarlyConsumer) Some(new EarlyBypassTestConsumerPlugin("EarlyConsumer", PAYLOAD_HT())) else None

      var allPluginsSeq: Seq[Plugin] = Seq(servicePlugin) ++ producerPluginsList
      consumerPluginOpt.foreach(p => allPluginsSeq = allPluginsSeq :+ p)
      earlyConsumerPluginOpt.foreach(p => allPluginsSeq = allPluginsSeq :+ p)

      val framework = ProjectScope(database) on new Framework(allPluginsSeq)

      val producerFlowDriversAccess = producerPluginsList.map(p => p.euProvidedFlow)
      val consumerFlowMonitorAccessOpt = consumerPluginOpt.map(_.io.mergedBypassIn)
      val earlyConsumerFlowMonitorAccessOpt = earlyConsumerPluginOpt.map(_.io.mergedBypassInEarly)
    }
    new TestBench()
  }

  /** Helper to push a single transaction using FlowDriver's queue mechanism. */
  def pushTransaction[T <: Data](payloadSetter: T => Unit, driverQueue: mutable.Queue[T => Unit]): Unit = {
    driverQueue.enqueue(payloadSetter)
  }

  test("BypassPlugin - no producers") {
    val dut = simConfig.compile(createDut(numProducers = 0, includeConsumer = true))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val receivedItems = mutable.ArrayBuffer[BigInt]()

      FlowMonitor(dut.consumerFlowMonitorAccessOpt.get, dut.clockDomain) { payload =>
        receivedItems += payload.data.toBigInt
      }

      dut.clockDomain.waitSampling(10)

      assert(receivedItems.isEmpty, "Should not receive any data when there are no producers")
      println("Test 'no producers' PASSED")
    }
  }

  test("BypassPlugin - one producer") {
    val dut = simConfig.compile(createDut(numProducers = 1, includeConsumer = true))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val (producerDriver, producerQueue) = FlowDriver.queue(dut.producerFlowDriversAccess.head, dut.clockDomain)
      val receivedItems = mutable.ArrayBuffer[BigInt]()

      FlowMonitor(dut.consumerFlowMonitorAccessOpt.get, dut.clockDomain) { payload =>
        receivedItems += payload.data.toBigInt
      }

      dut.clockDomain.waitSampling()

      val testData1 = BigInt(0x123)
      pushTransaction[TestBypassPayload](p => p.data #= testData1, producerQueue)
      dut.clockDomain.waitSampling() // Allow one cycle for transaction to be potentially sent

      val testData2 = BigInt(0x456)
      pushTransaction[TestBypassPayload](p => p.data #= testData2, producerQueue)

      val timedOut = dut.clockDomain.waitSamplingWhere(50)(receivedItems.length >= 2)
      assert(!timedOut, "Timeout waiting for 2 items from single producer")

      assert(receivedItems.length == 2, s"Expected 2 items, got ${receivedItems.length}")
      assert(receivedItems(0) == testData1, s"Data mismatch for item 0: Expected $testData1, got ${receivedItems(0)}")
      assert(receivedItems(1) == testData2, s"Data mismatch for item 1: Expected $testData2, got ${receivedItems(1)}")
      println("Test 'one producer' PASSED")
    }
  }

  test("BypassPlugin - two producers, sequential send") {
    val dut = simConfig.compile(createDut(numProducers = 2, includeConsumer = true))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val (producer0Driver, producer0Queue) = FlowDriver.queue(dut.producerFlowDriversAccess(0), dut.clockDomain)
      val (producer1Driver, producer1Queue) = FlowDriver.queue(dut.producerFlowDriversAccess(1), dut.clockDomain)
      val receivedItems = mutable.ArrayBuffer[BigInt]()

      FlowMonitor(dut.consumerFlowMonitorAccessOpt.get, dut.clockDomain) { payload =>
        receivedItems += payload.data.toBigInt
      }

      dut.clockDomain.waitSampling()
      val dataP0_1 = BigInt(0xA0A0)
      pushTransaction[TestBypassPayload](p => p.data #= dataP0_1, producer0Queue) // Producer 0 sends
      dut.clockDomain.waitSampling() // Let it pass through arbiter

      val dataP1_1 = BigInt(0xB1B1)
      pushTransaction[TestBypassPayload](p => p.data #= dataP1_1, producer1Queue) // Producer 1 sends
      dut.clockDomain.waitSampling() // Let it pass

      val dataP0_2 = BigInt(0xC2C2)
      pushTransaction[TestBypassPayload](p => p.data #= dataP0_2, producer0Queue) // Producer 0 sends again
      dut.clockDomain.waitSampling() // Allow transaction to be potentially sent

      val timedOut = dut.clockDomain.waitSamplingWhere(50)(receivedItems.length >= 3)
      assert(!timedOut, "Timeout waiting for 3 items from sequential send")

      assert(receivedItems.length == 3, s"Expected 3 items, got ${receivedItems.length}")
      assert(receivedItems(0) == dataP0_1)
      assert(receivedItems(1) == dataP1_1)
      assert(receivedItems(2) == dataP0_2)
      println("Test 'two producers, sequential send' PASSED")
    }
  }

  test("BypassPlugin - two producers, simultaneous send (one data drop expected)") {
    val dut = simConfig.compile(createDut(numProducers = 2, includeConsumer = true))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val (producer0Driver, producer0Queue) = FlowDriver.queue(dut.producerFlowDriversAccess(0), dut.clockDomain)
      val (producer1Driver, producer1Queue) = FlowDriver.queue(dut.producerFlowDriversAccess(1), dut.clockDomain)
      val receivedItems = mutable.ArrayBuffer[BigInt]()
      FlowMonitor(dut.consumerFlowMonitorAccessOpt.get, dut.clockDomain) { payload =>
        receivedItems += payload.data.toBigInt
      }

      dut.clockDomain.waitSampling()
      val dataP0 = BigInt(0x1111)
      val dataP1 = BigInt(0x2222)

      // Both producers push data in the same cycle via their queues
      pushTransaction[TestBypassPayload](p => p.data #= dataP0, producer0Queue)
      pushTransaction[TestBypassPayload](p => p.data #= dataP1, producer1Queue)
      // The FlowDrivers will attempt to drive their respective flows valid in the same sampling phase.
      // The StreamArbiter will pick one.

      dut.clockDomain.waitSampling() // Cycle where arbiter makes a choice

      val timedOutFirst = dut.clockDomain.waitSamplingWhere(20)(receivedItems.length > 0)
      assert(!timedOutFirst, "Timeout waiting for the first item from simultaneous send")

      assert(receivedItems.length == 1, s"Expected 1 item after simultaneous send (one dropped), got ${receivedItems.length}")
      val pickedData = receivedItems.head
      val droppedData = if(pickedData == dataP0) dataP1 else dataP0
      println(s"Simultaneous send: Picked 0x${pickedData.toString(16)}. Expected one (0x${droppedData.toString(16)}) to be dropped.")

      val dataNext = BigInt(0x3333)
      if (pickedData == dataP0) { // If P0 was picked, P1 sends next
        pushTransaction[TestBypassPayload](p => p.data #= dataNext, producer1Queue)
      } else { // If P1 was picked, P0 sends next
        pushTransaction[TestBypassPayload](p => p.data #= dataNext, producer0Queue)
      }
      dut.clockDomain.waitSampling() // Allow transaction to be potentially sent
      
      val timedOutSecond = dut.clockDomain.waitSamplingWhere(20)(receivedItems.length > 1)
      assert(!timedOutSecond, "Timeout waiting for the second item after simultaneous send recovery")

      assert(receivedItems.length == 2, s"Expected 2 items in total now, got ${receivedItems.length}")
      assert(receivedItems(1) == dataNext, "Second received item mismatch after recovery attempt")

      println("Test 'two producers, simultaneous send (one data drop expected)' PASSED")
    }
  }
  
  test("BypassPlugin - consumer requests flow early") {
    val dut = simConfig.compile(createDut(numProducers = 0, includeConsumer = false, includeEarlyConsumer = true))
    dut.doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      val receivedItems = mutable.ArrayBuffer[BigInt]()

      FlowMonitor(dut.earlyConsumerFlowMonitorAccessOpt.get, dut.clockDomain) { payload =>
        receivedItems += payload.data.toBigInt
      }

      dut.clockDomain.waitSampling(5)

      assert(!dut.earlyConsumerFlowMonitorAccessOpt.get.valid.toBoolean, "Flow obtained 'early' should have its 'valid' signal driven False.")
      assert(receivedItems.isEmpty, "Should not receive any data on an 'early' requested (error) flow.")
      
      println("Test 'consumer requests flow early' PASSED (verified inactive flow)")
    }
  }
}
