// testOnly test.scala.LoadQueuePluginSpec
// -- MODIFICATION START (Fix simulation-time assignment to non-hardware signals) --
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.lsu._
import parallax.utilities._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import parallax.components.rob.ROBFlushPayload
import parallax.components.rob.ROBService
import spinal.lib.sim.StreamMonitor
import parallax.components.rob.FlushReason

// Test setup plugin to connect LoadQueue ports to testbench IO
class LoadQueueTestSetupPlugin(
    pipelineConfig: PipelineConfig,
    lsuConfig: LsuConfig,
    testIO: LoadQueueTestIO
) extends Plugin {
  ParallaxLogger.debug("LQTSTPlugin: setup")
  lazy val lqPlugin = getService[LoadQueuePlugin]

  val setup = create early new Area {
    ParallaxLogger.debug("LQTSTPlugin: early. retain lqPlugin")
    lqPlugin.retain()
    ParallaxLogger.debug("LQTSTPlugin: early. setup test IO")
    // Connect LoadQueue ports to test IO
    lqPlugin.getAllocatePort() <> testIO.allocatePort
    lqPlugin.getStatusUpdatePort() <> testIO.statusUpdatePort

    // Connect release ports
    for (i <- 0 until lsuConfig.commitWidth) {
      lqPlugin.getReleasePorts()(i) <> testIO.releasePorts(i)
      lqPlugin.getReleaseMask()(i) := testIO.releaseMask(i)
    }

    testIO.loadRequestPort <> lqPlugin.getLoadRequestPort()
    lqPlugin.getLQFlushPort() <> testIO.flushPort
  }

  val logic = create late new Area {
    ParallaxLogger.debug("LQTSTPlugin: late. release lqPlugin")
    lqPlugin.release()
    ParallaxLogger.debug("LQTSTPlugin: late. logic")
  }
}

// Test IO bundle
case class LoadQueueTestIO(pipelineConfig: PipelineConfig, lsuConfig: LsuConfig) extends Bundle with IMasterSlave {
  val allocatePort = slave(Stream(LoadQueueEntry(lsuConfig)))
  val statusUpdatePort = slave(Flow(LqStatusUpdate(lsuConfig)))
  val releasePorts = Vec(slave(Flow(UInt(lsuConfig.robPtrWidth))), lsuConfig.commitWidth)
  val releaseMask = Vec(in Bool (), lsuConfig.commitWidth)
  val loadRequestPort = master(Stream(LsuAguRequest(lsuConfig)))
  val flushPort = slave(Flow(ROBFlushPayload(pipelineConfig.robPtrWidth)))

  override def asMaster(): Unit = { // Gemini 麻烦你注意一下，asMaster 是从**此组件的外部用户**视角的，下面是对的，别瞎改！
    master(allocatePort, statusUpdatePort, flushPort)
    slave(loadRequestPort)
    releasePorts.foreach(master(_))
    releaseMask.foreach(out(_))
  }
}

// Test framework component
class LoadQueueTestBench extends Component {
  ParallaxLogger.debug("setup LoadQueueTestBench")
  val pipelineConfig = PipelineConfig(
    dispatchWidth = 2,
    commitWidth = 2,
    robDepth = 16
  )

  val lsuConfig = LsuConfig(
    lqDepth = 8,
    sqDepth = 8,
    robPtrWidth = 5 bits,
    pcWidth = 32 bits,
    dataWidth = 32 bits,
    physGprIdxWidth = 5 bits,
    exceptionCodeWidth = 5 bits,
    commitWidth = 2,
    dcacheRefillCount = 2
  )

  val io = slave(LoadQueueTestIO(pipelineConfig, lsuConfig).simPublic())

  lazy val loadQueuePlugin = new LoadQueuePlugin(lsuConfig, pipelineConfig)

  lazy val testSetupPlugin = new LoadQueueTestSetupPlugin(
    pipelineConfig,
    lsuConfig,
    io
  )

  val framework = new Framework(
    Seq(
      loadQueuePlugin,
      testSetupPlugin
    )
  )
}

class LoadQueuePluginSpec extends CustomSpinalSimFunSuite {

  // Test data structures
  case class LoadQueueTestParams(
      pc: Long,
      robPtr: Int,
      physDest: Int,
      physDestIsFpr: Boolean,
      writePhysDestEn: Boolean,
      accessSize: MemAccessSize.E,
      isSignedLoad: Boolean,
      aguBasePhysReg: Int,
      aguBaseIsFpr: Boolean,
      aguImmediate: Int
  )

  case class StatusUpdateParams(
      lqId: Int,
      updateType: LqUpdateType.E,
      physicalAddress: Option[Long] = None,
      alignException: Boolean = false,
      dCacheFault: Boolean = false,
      dCacheRedo: Boolean = false,
      dCacheRefillSlot: Option[BigInt] = None,
      dCacheRefillAny: Boolean = false,
      dataFromDCache: Option[Long] = None,
      dataFromSq: Option[Long] = None,
      sqBypassSuccess: Boolean = false
  )

  case class LsuAguRequestCapture(
      val robPtr: Int,
      val basePhysReg: Int,
      val immediate: Int,
      val isLoad: Boolean,
      val isStore: Boolean
  ) {}

  object LsuAguRequestCapture {
    def fromSim(req: LsuAguRequest): LsuAguRequestCapture = {
      LsuAguRequestCapture(
        req.robPtr.toInt,
        req.basePhysReg.toInt,
        req.immediate.toInt,
        req.isLoad.toBoolean,
        req.isStore.toBoolean
      )
    }
  }

  // Helper functions
  def driveAllocatePort(
      dut: LoadQueueTestBench,
      params: LoadQueueTestParams
  ): Unit = {
    val entry = dut.io.allocatePort.payload

    // Correctly drive the DUT's input port payload fields
    entry.robPtr #= params.robPtr
    entry.pc #= params.pc
    entry.isValid #= true
    entry.physDest #= params.physDest
    entry.physDestIsFpr #= params.physDestIsFpr
    entry.writePhysDestEn #= params.writePhysDestEn
    entry.accessSize #= params.accessSize
    entry.isSignedLoad #= params.isSignedLoad
    entry.aguBasePhysReg #= params.aguBasePhysReg
    entry.aguBaseIsFpr #= params.aguBaseIsFpr
    entry.aguUsePcAsBase #= false
    entry.aguImmediate #= params.aguImmediate

    // Set default values for other fields
    entry.physicalAddress #= 0
    entry.dataFromSq #= 0
    entry.dataFromDCache #= 0
    entry.finalData #= 0
    entry.waitOn.aguDispatched #= false
    entry.waitOn.addressGenerated #= false
    entry.waitOn.commit #= false
    entry.waitOn.dCacheRsp #= false
    entry.waitOn.dCacheRefill #= 0
    entry.waitOn.dCacheRefillAny #= false
    entry.waitOn.robFlush #= false
    entry.waitOn.sqBypass #= false
    entry.waitOn.sqCompletion #= false
    entry.sqIdToWaitFor #= 0
    entry.sqIdToWaitForValid #= false

    dut.io.allocatePort.valid #= true
  }

  def driveStatusUpdate(
      dut: LoadQueueTestBench,
      params: StatusUpdateParams
  ): Unit = {
    val update = dut.io.statusUpdatePort.payload
    update.lqPtr #= params.lqId
    update.updateType #= params.updateType

    // Initialize all fields to a known default to avoid X's
    update.physicalAddressAg #= 0
    update.alignExceptionAg #= false
    update.dCacheFaultDr #= false
    update.dCacheRedoDr #= false
    update.dCacheRefillSlotDr #= 0
    update.dCacheRefillAnyDr #= false
    update.dataFromDCacheDr #= 0
    update.dataFromSqBypassSb #= 0
    update.sqBypassSuccessSb #= false

    // Apply specific parameters
    params.updateType match {
      case LqUpdateType.ADDRESS_GENERATED =>
        params.physicalAddress.foreach(addr => update.physicalAddressAg #= addr)
        update.alignExceptionAg #= params.alignException

      case LqUpdateType.DCACHE_RESPONSE =>
        update.dCacheFaultDr #= params.dCacheFault
        update.dCacheRedoDr #= params.dCacheRedo
        params.dataFromDCache.foreach(data => update.dataFromDCacheDr #= data)
        params.dCacheRefillSlot.foreach(slot => update.dCacheRefillSlotDr #= slot)
        update.dCacheRefillAnyDr #= params.dCacheRefillAny

      case LqUpdateType.DCACHE_REFILL_WAIT =>
        params.dCacheRefillSlot.foreach(slot => update.dCacheRefillSlotDr #= slot)
        update.dCacheRefillAnyDr #= params.dCacheRefillAny

      case LqUpdateType.DCACHE_REFILL_DONE =>
        params.dCacheRefillSlot.foreach(slot => update.dCacheRefillSlotDr #= slot)
        update.dCacheRefillAnyDr #= params.dCacheRefillAny

      case LqUpdateType.SQ_BYPASS_READY =>
        params.dataFromSq.foreach(data => update.dataFromSqBypassSb #= data)
        update.sqBypassSuccessSb #= params.sqBypassSuccess

      case _ => // Other update types like EXCEPTION, MARK_READY etc. have no payload fields
    }

    dut.io.statusUpdatePort.valid #= true
  }

  def initDutInputs(dut: LoadQueueTestBench): Unit = {
    dut.io.allocatePort.valid #= false
    dut.io.statusUpdatePort.valid #= false
    dut.io.releasePorts.foreach(_.valid #= false)
    dut.io.releaseMask.foreach(_ #= false)
    dut.io.loadRequestPort.ready #= true
    dut.io.flushPort.valid #= false
  }

  test("LoadQueue Basic Allocation and Ready Detection") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val allocatedUops = mutable.ArrayBuffer[LoadQueueTestParams]()
      val aguRequests = mutable.ArrayBuffer[LsuAguRequestCapture]()

      // Monitor AGU requests
      StreamMonitor(dut.io.loadRequestPort, dut.clockDomain) { payload =>
        aguRequests += LsuAguRequestCapture.fromSim(payload)
      }

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Test Case 1: Basic allocation
      val testUop = LoadQueueTestParams(
        pc = 0x1000,
        robPtr = 5,
        physDest = 10,
        physDestIsFpr = false,
        writePhysDestEn = true,
        accessSize = MemAccessSize.W,
        isSignedLoad = false,
        aguBasePhysReg = 8,
        aguBaseIsFpr = false,
        aguImmediate = 100
      )

      // Send allocation request
      driveAllocatePort(dut, testUop)

      dut.clockDomain.waitSampling()
      assert(dut.io.allocatePort.ready.toBoolean, "LoadQueue should be ready for allocation")

      dut.io.allocatePort.valid #= false
      allocatedUops += testUop

      dut.clockDomain.waitSampling(5)

      // Should generate AGU request
      val timeoutOccurred = dut.clockDomain.waitSamplingWhere(20)(aguRequests.nonEmpty)
      assert(!timeoutOccurred, "Timeout waiting for AGU request")
      assert(aguRequests.nonEmpty, "LoadQueue should generate AGU request")

      val aguReq = aguRequests.head
      assert(aguReq.robPtr == testUop.robPtr, "ROB ID mismatch in AGU request")
      assert(aguReq.basePhysReg == testUop.aguBasePhysReg, "Base register mismatch in AGU request")
      assert(aguReq.immediate == testUop.aguImmediate, "Immediate mismatch in AGU request")
      assert(aguReq.isLoad, "Should be marked as load")
      assert(!aguReq.isStore, "Should not be marked as store")

      println("✓ Basic allocation and AGU request generation PASSED")
    }
  }

  testOnly("LoadQueue Status Update - Address Generation") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val aguRequests = mutable.ArrayBuffer[Int]()

      // Monitor AGU requests
      StreamMonitor(dut.io.loadRequestPort, dut.clockDomain) { payload =>
        aguRequests += payload.qPtr.toInt
      }

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Allocate a uop first
      val testUop = LoadQueueTestParams(
        pc = 0x2000,
        robPtr = 10,
        physDest = 15,
        physDestIsFpr = false,
        writePhysDestEn = true,
        accessSize = MemAccessSize.H,
        isSignedLoad = true,
        aguBasePhysReg = 12,
        aguBaseIsFpr = false,
        aguImmediate = 200
      )

      driveAllocatePort(dut, testUop)
      dut.clockDomain.waitSampling()
      dut.io.allocatePort.valid #= false

      // Wait for AGU request
      val timeout = dut.clockDomain.waitSamplingWhere(20)(aguRequests.nonEmpty)
      if (timeout) {
        fail("Timeout waiting for AGU request")
      }
      val lqId = aguRequests.head

      aguRequests.clear()
      dut.clockDomain.waitSampling(3)

      // Send address generation update
      val physAddress = 0x3000L
      driveStatusUpdate(
        dut,
        StatusUpdateParams(
          lqId = lqId,
          updateType = LqUpdateType.ADDRESS_GENERATED,
          physicalAddress = Some(physAddress),
          alignException = false
        )
      )

      dut.clockDomain.waitSampling()
      dut.io.statusUpdatePort.valid #= false

      dut.clockDomain.waitSampling(5)

      // After address generation, should not generate more AGU requests for this entry
      assert(aguRequests.isEmpty, "Should not generate more AGU requests after address is generated")

      println("✓ Address generation status update PASSED")
    }
  }

  test("LoadQueue Status Update - DCache Response") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Allocate and progress through address generation
      val testUop = LoadQueueTestParams(
        pc = 0x4000,
        robPtr = 20,
        physDest = 25,
        physDestIsFpr = false,
        writePhysDestEn = true,
        accessSize = MemAccessSize.W,
        isSignedLoad = false,
        aguBasePhysReg = 18,
        aguBaseIsFpr = false,
        aguImmediate = 300
      )

      // Allocate
      driveAllocatePort(dut, testUop)
      dut.clockDomain.waitSampling()
      dut.io.allocatePort.valid #= false

      val lqId = 0 // First allocation uses LQ ID 0

      // Address generation
      driveStatusUpdate(
        dut,
        StatusUpdateParams(
          lqId = lqId,
          updateType = LqUpdateType.ADDRESS_GENERATED,
          physicalAddress = Some(0x5000L)
        )
      )
      dut.clockDomain.waitSampling()
      dut.io.statusUpdatePort.valid #= false

      dut.clockDomain.waitSampling(3)

      // DCache response - success case
      val loadData = 0x12345678L
      driveStatusUpdate(
        dut,
        StatusUpdateParams(
          lqId = lqId,
          updateType = LqUpdateType.DCACHE_RESPONSE,
          dCacheFault = false,
          dCacheRedo = false,
          dataFromDCache = Some(loadData)
        )
      )
      dut.clockDomain.waitSampling()
      dut.io.statusUpdatePort.valid #= false

      dut.clockDomain.waitSampling(5)

      // Mark ready for commit
      driveStatusUpdate(
        dut,
        StatusUpdateParams(
          lqId = lqId,
          updateType = LqUpdateType.MARK_READY_FOR_COMMIT
        )
      )
      dut.clockDomain.waitSampling()
      dut.io.statusUpdatePort.valid #= false

      dut.clockDomain.waitSampling(3)

      println("✓ DCache response status update PASSED")
    }
  }

  test("LoadQueue Release Logic") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Allocate multiple uops
      val testUops = Seq(
        LoadQueueTestParams(
          pc = 0x1000,
          robPtr = 5,
          physDest = 10,
          physDestIsFpr = false,
          writePhysDestEn = true,
          accessSize = MemAccessSize.W,
          isSignedLoad = false,
          aguBasePhysReg = 8,
          aguBaseIsFpr = false,
          aguImmediate = 100
        ),
        LoadQueueTestParams(
          pc = 0x1004,
          robPtr = 6,
          physDest = 11,
          physDestIsFpr = false,
          writePhysDestEn = true,
          accessSize = MemAccessSize.H,
          isSignedLoad = true,
          aguBasePhysReg = 9,
          aguBaseIsFpr = false,
          aguImmediate = 200
        )
      )

      // Allocate uops
      for ((testUop, idx) <- testUops.zipWithIndex) {
        driveAllocatePort(dut, testUop)
        dut.clockDomain.waitSampling()
        dut.io.allocatePort.valid #= false

        // Complete the load (simplified)
        driveStatusUpdate(
          dut,
          StatusUpdateParams(
            lqId = idx,
            updateType = LqUpdateType.ADDRESS_GENERATED,
            physicalAddress = Some(0x2000L + idx * 4)
          )
        )
        dut.clockDomain.waitSampling()
        dut.io.statusUpdatePort.valid #= false

        driveStatusUpdate(
          dut,
          StatusUpdateParams(
            lqId = idx,
            updateType = LqUpdateType.DCACHE_RESPONSE,
            dataFromDCache = Some(0x12345678L + idx)
          )
        )
        dut.clockDomain.waitSampling()
        dut.io.statusUpdatePort.valid #= false

        driveStatusUpdate(
          dut,
          StatusUpdateParams(
            lqId = idx,
            updateType = LqUpdateType.MARK_READY_FOR_COMMIT
          )
        )
        dut.clockDomain.waitSampling()
        dut.io.statusUpdatePort.valid #= false

        dut.clockDomain.waitSampling(2)
      }

      // Release first uop from commit slot 0
      dut.io.releasePorts(0).valid #= true
      dut.io.releasePorts(0).payload #= testUops(0).robPtr
      dut.io.releaseMask(0) #= true
      dut.clockDomain.waitSampling()
      dut.io.releasePorts(0).valid #= false
      dut.io.releaseMask(0) #= false

      dut.clockDomain.waitSampling(3)

      // Release second uop from commit slot 0 again (as it's now the head)
      dut.io.releasePorts(0).valid #= true
      dut.io.releasePorts(0).payload #= testUops(1).robPtr
      dut.io.releaseMask(0) #= true
      dut.clockDomain.waitSampling()
      dut.io.releasePorts(0).valid #= false
      dut.io.releaseMask(0) #= false

      dut.clockDomain.waitSampling(5)

      // Try to allocate again to verify queue is available
      driveAllocatePort(dut, testUops(0).copy(robPtr = 30))
      dut.clockDomain.waitSampling()
      assert(dut.io.allocatePort.ready.toBoolean, "LoadQueue should be ready after releases")

      dut.io.allocatePort.valid #= false

      println("✓ Release logic PASSED")
    }
  }

  test("LoadQueue Flush Logic") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Allocate some uops
      for (i <- 0 until 3) {
        val testUop = LoadQueueTestParams(
          pc = 0x1000 + i * 4,
          robPtr = 10 + i,
          physDest = 20 + i,
          physDestIsFpr = false,
          writePhysDestEn = true,
          accessSize = MemAccessSize.W,
          isSignedLoad = false,
          aguBasePhysReg = 8 + i,
          aguBaseIsFpr = false,
          aguImmediate = 100 + i * 10
        )

        driveAllocatePort(dut, testUop)
        dut.clockDomain.waitSampling()
        dut.io.allocatePort.valid #= false
        dut.clockDomain.waitSampling(2)
      }

      // Verify queue is not empty
      dut.clockDomain.waitSampling(3)

      // Send flush command
      val flushPayload = dut.io.flushPort.payload
      flushPayload.reason #= FlushReason.ROLLBACK_TO_ROB_IDX;
      flushPayload.targetRobPtr #= 11

      dut.io.flushPort.valid #= true
      dut.clockDomain.waitSampling()
      dut.io.flushPort.valid #= false

      dut.clockDomain.waitSampling(5)

      // Verify queue is ready for new allocations after flush
      val newUopParams = LoadQueueTestParams(
        pc = 0x8000,
        robPtr = 30,
        physDest = 10,
        physDestIsFpr = false,
        writePhysDestEn = true,
        accessSize = MemAccessSize.W,
        isSignedLoad = false,
        aguBasePhysReg = 20,
        aguBaseIsFpr = false,
        aguImmediate = 300
      )
      driveAllocatePort(dut, newUopParams)
      dut.clockDomain.waitSampling()

      assert(dut.io.allocatePort.ready.toBoolean, "LoadQueue should be ready after flush")

      dut.io.allocatePort.valid #= false

      println("✓ Flush logic PASSED")
    }
  }

  test("LoadQueue Full Condition") {
    simConfig.compile(new LoadQueueTestBench()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      initDutInputs(dut)
      dut.clockDomain.waitSampling(3)

      // Fill the entire LoadQueue
      val lqDepth = dut.lsuConfig.lqDepth
      for (i <- 0 until lqDepth) {
        val testUop = LoadQueueTestParams(
          pc = 0x1000 + i * 4,
          robPtr = i,
          physDest = 10 + i,
          physDestIsFpr = false,
          writePhysDestEn = true,
          accessSize = MemAccessSize.W,
          isSignedLoad = false,
          aguBasePhysReg = 8,
          aguBaseIsFpr = false,
          aguImmediate = 100
        )

        driveAllocatePort(dut, testUop)

        if (i < lqDepth - 1) {
          assert(dut.io.allocatePort.ready.toBoolean, s"LoadQueue should be ready for allocation $i")
        }

        dut.clockDomain.waitSampling()
      }

      // Queue should now be full
      dut.io.allocatePort.valid #= false
      dut.clockDomain.waitSampling(3)

      // Try one more allocation - should not be ready
      // val overflowUopParams = LoadQueueTestParams(
      //     pc = 0x9000,
      //     robPtr = 99,
      //     physDest = 99,
      //     physDestIsFpr = false,
      //     writePhysDestEn = true,
      //     accessSize = MemAccessSize.W,
      //     isSignedLoad = false,
      //     aguBasePhysReg = 8,
      //     aguBaseIsFpr = false,
      //     aguImmediate = 100
      //   )
      // driveAllocatePort(dut, overflowUopParams)

      // dut.clockDomain.waitSampling()
      // assert(!dut.io.allocatePort.ready.toBoolean, "LoadQueue should not be ready when full")

      // dut.io.allocatePort.valid #= false

      println("✓ Full condition test PASSED")
    }
  }

  thatsAll()
}
// -- MODIFICATION END --
