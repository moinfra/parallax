// testOnly parallax.test.scala.dcache2.DataCacheSpec

package parallax.test.scala.dcache2

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.amba4.axi.sim.{AxiMemorySim, AxiMemorySimConfig, MemoryPage, SparseMemory}
import spinal.lib.sim._
import parallax.components.dcache2._

import scala.collection.mutable
import scala.util.Random
import parallax.components.memory.SimulatedSRAM
import parallax.components.memory.ExtSRAMConfig
import parallax.components.memory.ExtSRAMController
import parallax.utilities.ParallaxLogger

// Define a helper class for Load Response data for easier comparison
case class SimDataLoadRsp(data: BigInt, fault: Boolean, redo: Boolean, refillSlot: BigInt, refillSlotAny: Boolean) {
  override def toString: String = {
    s"SimDataLoadRsp(data=${data.toString(16)}, fault=${fault}, redo=${redo}, refillSlot=${refillSlot.toString(16)}}, refillSlotAny=${refillSlotAny})"
  }
}

// Define a helper class for Store Response data
case class SimDataStoreRsp(
    fault: Boolean,
    redo: Boolean,
    refillSlot: BigInt,
    refillSlotAny: Boolean,
    // generationKo: Boolean,
    flush: Boolean,
    prefetch: Boolean,
    address: BigInt,
    io: Boolean
) {
  override def toString: String = {
    s"SimDataStoreRsp(fault=${fault}, redo=${redo}, refillSlot=${refillSlot}, refillSlotAny=${refillSlotAny}, flush=${flush}, prefetch=${prefetch}, address=${address}, io=${io})"
  }
}

class DataCacheTestbench(val p: DataCacheParameters, val useSimulatedSRAM: Boolean = false) extends Component {
  val dcache = new DataCache(p)
  val axiMasterNode = dcache.io.mem.toAxi4()

  val io = new Bundle {
    // val lock = slave(LockPort())
    val load = slave(
      DataLoadPort(
        preTranslationWidth = p.preTranslationWidth,
        postTranslationWidth = p.postTranslationWidth,
        dataWidth = p.cpuDataWidth,
        refillCount = p.refillCount,
        rspAt = p.loadRspAt,
        translatedAt = p.loadTranslatedAt
      )
    )
    val store = slave(
      DataStorePort(
        postTranslationWidth = p.postTranslationWidth,
        dataWidth = p.cpuDataWidth,
        refillCount = p.refillCount
      )
    )
    val mem_axi = !useSimulatedSRAM generate master(Axi4(axiMasterNode.config))
    val refillCompletions = out(Bits(p.refillCount bits))
    val refillEvent = out(Bool())
    val writebackEvent = out(Bool())
    val writebackBusy = out(Bool())
  }

  // dcache.io.lock <> io.lock
  dcache.io.load <> io.load
  dcache.io.store <> io.store

  var sram: SimulatedSRAM = null
  if (useSimulatedSRAM) {
    val sramSize = 10 KiB
    val extSramCfg = ExtSRAMConfig(
      addressWidth = 16,
      dataWidth = 64,
      virtualBaseAddress = 0x00000000L,
      sramSize = sramSize,
      readWaitCycles = 0,
      enableLog = true
    )
    sram = new SimulatedSRAM(extSramCfg)
    val ctrl = new ExtSRAMController(axiMasterNode.config, extSramCfg)
    ctrl.io.axi <> axiMasterNode
    ctrl.io.ram <> sram.io.ram
    ctrl.io.simPublic()
    sram.io.simPublic()
  } else {
    io.mem_axi <> axiMasterNode
  }

  io.refillCompletions := dcache.io.refillCompletions
  io.refillEvent := dcache.io.refillEvent
  io.writebackEvent := dcache.io.writebackEvent
  io.writebackBusy := dcache.io.writebackBusy
  dcache.io.simPublic()
  io.simPublic()
}

class DataCacheSpec extends CustomSpinalSimFunSuite {

  val defaultCacheP = DataCacheParameters(
    cacheSize = 1024,
    wayCount = 2,
    refillCount = 2,
    writebackCount = 2,
    memDataWidth = 64,
    cpuDataWidth = 64,
    preTranslationWidth = 32,
    postTranslationWidth = 32,
    lineSize = 64,
    // withCoherency = false,
    // probeIdWidth = 1,
    // ackIdWidth = 1,
    loadReadBanksAt = 0,
    loadReadTagsAt = 1,
    loadTranslatedAt = 1,
    loadHitsAt = 1,
    loadHitAt = 1,
    loadBankMuxesAt = 1,
    loadBankMuxAt = 2,
    loadControlAt = 2,
    loadRspAt = 2,
    storeReadBanksAt = 0,
    storeReadTagsAt = 1,
    storeHitsAt = 1,
    storeHitAt = 1,
    storeControlAt = 2,
    storeRspAt = 2,
    tagsReadAsync = true,
    reducedBankWidth = false
  )

  // -- MODIFICATION START (DUT Input Initialization Function) --
  def initDutInputs(dut: DataCacheTestbench, clockDomain: ClockDomain): Unit = {
    // dut.io.lock.valid #= false
    // dut.io.lock.address #= 0

    dut.io.load.cmd.valid #= false
    // dut.io.load.cmd.payload.assignDontCare() // Or set to defaults
    dut.io.load.cmd.payload.virtual #= 0
    dut.io.load.cmd.payload.size #= 0
    dut.io.load.cmd.payload.redoOnDataHazard #= false
    // dut.io.load.cmd.payload.unlocked #= false
    // dut.io.load.cmd.payload.unique #= false

    dut.io.load.translated.physical #= 0
    dut.io.load.translated.abord #= false
    dut.io.load.cancels #= 0
    // dut.io.load.rsp is Flow, master from DUT, no ready from testbench side

    dut.io.store.cmd.valid #= false
    // dut.io.store.cmd.payload.assignDontCare() // Or set to defaults
    dut.io.store.cmd.payload.address #= 0
    dut.io.store.cmd.payload.data #= 0
    dut.io.store.cmd.payload.mask #= 0
    // dut.io.store.cmd.payload.generation #= false
    dut.io.store.cmd.payload.io #= false
    dut.io.store.cmd.payload.flush #= false
    dut.io.store.cmd.payload.flushFree #= false
    dut.io.store.cmd.payload.prefetch #= false
    // dut.io.store.rsp is Flow, master from DUT, no ready from testbench side

    clockDomain.waitSampling() // Allow one cycle for initial values to settle
  }

  def sramFillData(
      dut: DataCacheTestbench, // Updated TestBench IO type
      address: BigInt,
      data: BigInt,
      dataWidthBytes: Int // Width of the 'value' being passed
  ): Unit = {
    val dataWidth = dataWidthBytes * 8
    val internalDataWidth = dut.sram.config.dataWidth
    require(
      dataWidth >= internalDataWidth,
      "Value's width must be >= internal data width."
    )
    if (dataWidth > internalDataWidth) {
      require(
        dataWidth % internalDataWidth == 0,
        "Value's width must be a multiple of internal data width if greater."
      )
    }
    val tbIo = dut.sram.io
    val numInternalChunks = dataWidth / internalDataWidth

    tbIo.tb_writeEnable #= true
    for (i <- 0 until numInternalChunks) {
      val slice = (data >> (i * internalDataWidth)) & ((BigInt(1) << internalDataWidth) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidth / 8)
      ParallaxLogger.debug(s"internalChunkByteAddress ${internalChunkByteAddress.toString(16)}")
      tbIo.tb_writeAddress #= address
      tbIo.tb_writeData #= slice
      dut.clockDomain.waitSampling()
    }
    tbIo.tb_writeEnable #= false
    dut.clockDomain.waitSampling()
    ParallaxLogger.success(
      s"Memory initialized via TB ports at 0x${address.toString(16)} with 0x${data.toString(16)} (${numInternalChunks} chunk(s))"
    ) // Kept original comment status
  }

  def toSim(rsp: DataLoadRsp): SimDataLoadRsp = SimDataLoadRsp(
    data = rsp.data.toBigInt,
    fault = rsp.fault.toBoolean,
    redo = rsp.redo.toBoolean,
    refillSlot = rsp.refillSlot.toBigInt,
    refillSlotAny = rsp.refillSlotAny.toBoolean
  )

  def toSim(rsp: DataStoreRsp): SimDataStoreRsp = SimDataStoreRsp(
    fault = rsp.fault.toBoolean,
    redo = rsp.redo.toBoolean,
    refillSlot = rsp.refillSlot.toBigInt,
    refillSlotAny = rsp.refillSlotAny.toBoolean,
    // generationKo = rsp.generationKo.toBoolean,
    flush = rsp.flush.toBoolean,
    prefetch = rsp.prefetch.toBoolean,
    address = rsp.address.toBigInt,
    io = rsp.io.toBoolean
  )

  def executeCmdWithRedo[CmdType <: Bundle, RspPayloadType <: Bundle, SimRspType](
      cmdAction: () => Unit,
      rspBuffer: mutable.Queue[SimRspType],
      rspExtractor: SimRspType => (Boolean, SimRspType),
      maxRetries: Int,
      timeoutPerAttempt: Int,
      clockDomain: ClockDomain,
      opName: String
  ): SimRspType = {
    var retries = 0
    var successfulResponse: Option[SimRspType] = None

    while (retries <= maxRetries && successfulResponse.isEmpty) {
      if (retries > 0) {
        println(s"SIM [$opName]: Retrying command (attempt ${retries + 1}) due to redo.")
        clockDomain.waitSampling(Random.nextInt(5) + 1)
      }
      cmdAction()
      val startTime = System.nanoTime()
      var attemptTimedOut = false
      while (rspBuffer.isEmpty && !attemptTimedOut) {
        if ((System.nanoTime() - startTime) / 1e6 > timeoutPerAttempt) {
          attemptTimedOut = true
        }
        clockDomain.waitSampling(1)
      }

      if (attemptTimedOut && rspBuffer.isEmpty) {
        println(s"SIM [$opName]: Timeout waiting for response on attempt ${retries + 1}")
        // Consider this a failed attempt for retry counting if timeout implies redo
        retries += 1
      } else if (rspBuffer.nonEmpty) {
        val rawRsp = rspBuffer.dequeue()
        val (isRedo, actualRsp) = rspExtractor(rawRsp)
        if (isRedo) {
          println(s"SIM [$opName]: Received REDO on attempt ${retries + 1}. Response: $actualRsp")
          retries += 1
        } else {
          successfulResponse = Some(actualRsp)
        }
      } else {
        println(s"SIM [$opName]: Buffer empty but not timed out on attempt ${retries + 1} - this is unexpected.")
      }
      if (retries > maxRetries && successfulResponse.isEmpty) {
        assert(false, s"SIM [$opName]: Command failed after $maxRetries retries due to persistent redos or timeouts.")
      }
    }
    assert(
      successfulResponse.isDefined,
      s"SIM [$opName]: Command failed to get a successful response after all attempts."
    )
    successfulResponse.get
  }

  // -- MODIFICATION START (Helper function for retrying store operations with generationKo handling) --
  /** Executes a store command and handles potential redos and generationKo.
    *
    * @param driveCmdFn Function to generate the store command payload, taking the current generation bit.
    * @param storeCmdQueue      Queue for sending store commands.
    * @param storeRspBuffer     Buffer to check for store responses.
    * @param maxRetries         Maximum number of retries for redo/generationKo.
    * @param timeoutPerAttempt  Timeout for each attempt.
    * @param clockDomain        Clock domain for waiting.
    * @param opName             Name of the operation for logging.
    * @return                   The final successful store response.
    */
  def executeStoreCmdWithGenerationKo(
      driveCmdFn: (DataStoreCmd, Boolean) => Unit, // Takes current generation, returns cmd payload
      storeCmdQueue: mutable.Queue[DataStoreCmd => Unit],
      storeRspBuffer: mutable.Queue[SimDataStoreRsp],
      maxRetries: Int,
      timeoutPerAttempt: Int,
      clockDomain: ClockDomain,
      opName: String
  ): SimDataStoreRsp = {
    var retries = 0
    var successfulResponse: Option[SimDataStoreRsp] = None
    var currentGenerationForCmd = false // Matches DUT's initial RegInit(False) for 'target'

    while (retries <= maxRetries && successfulResponse.isEmpty) {

      if (retries > 0) {
        println(s"SIM [$opName]: Retrying command (attempt ${retries + 1}) with generation=${currentGenerationForCmd}")
        clockDomain.waitSampling(Random.nextInt(5) + 2) // Small random delay before retry
      }

      storeCmdQueue.enqueue(cmdPayload => {
        driveCmdFn(cmdPayload, currentGenerationForCmd)
      })

      val startTime = System.nanoTime()
      var attemptTimedOut = false
      while (storeRspBuffer.isEmpty && !attemptTimedOut) {
        if ((System.nanoTime() - startTime) / 1e6 > timeoutPerAttempt) {
          attemptTimedOut = true
        }
        clockDomain.waitSampling(1)
      }

      if (attemptTimedOut && storeRspBuffer.isEmpty) {
        println(s"SIM [$opName]: Timeout waiting for store response on attempt ${retries + 1}")
        retries += 1
      } else if (storeRspBuffer.nonEmpty) {
        val simRsp = storeRspBuffer.dequeue()
        if (false) {
        // if (simRsp.generationKo) {
          println(
            s"SIM [$opName]: Received GENERATION_KO on attempt ${retries + 1}. Response: $simRsp. Flipping generation for next retry."
          )
          currentGenerationForCmd = !currentGenerationForCmd // Simulate LSU flipping its 'target'
          retries += 1
        } else if (simRsp.redo) {
          println(
            s"SIM [$opName]: Received REDO on attempt ${retries + 1}. Response: $simRsp. Retrying with same generation."
          )
          retries += 1
        } else {
          successfulResponse = Some(simRsp)
        }
      } else {
        println(s"SIM [$opName]: Store buffer empty but not timed out on attempt ${retries + 1} - this is unexpected.")
      }

      if (retries > maxRetries && successfulResponse.isEmpty) {
        assert(
          false,
          s"SIM [$opName]: Store command failed after $maxRetries retries due to persistent redos/generationKo or timeouts."
        )
      }
    }
    assert(
      successfulResponse.isDefined,
      s"SIM [$opName]: Store command failed to get a successful response after all attempts."
    )
    successfulResponse.get
  }
  // -- MODIFICATION END --

  test("DataCache - Load Miss then Hit") {
    val cacheP = defaultCacheP
    val sram = true
    simConfig.compile(new DataCacheTestbench(cacheP, sram)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDutInputs(dut, dut.clockDomain.get)
      val axiMemSim = if (!sram) {
        val ret = AxiMemorySim(
          dut.io.mem_axi,
          dut.clockDomain,
          AxiMemorySimConfig(readResponseDelay = 2, writeResponseDelay = 2)
        )
        ret.start()
        ret
      } else null

      dut.clockDomain.assertReset()
      sleep(10)
      dut.clockDomain.deassertReset()

      val loadRspBuffer = mutable.Queue[SimDataLoadRsp]()
      FlowMonitor(dut.io.load.rsp, dut.clockDomain) { payload =>
        loadRspBuffer.enqueue(toSim(payload))
      }

      val (loadCmdStreamDriver, loadCmdQueue) =
        StreamDriver.queue(dut.io.load.cmd, dut.clockDomain) // Get the queue from StreamDriver
      StreamReadyRandomizer(dut.io.load.cmd, dut.clockDomain)
// 
      val testAddr = BigInt("100", 16)
      val testData = BigInt("11223344AABBCCDD", 16)
      val dataWidthBytes = cacheP.cpuDataWidth / 8
      val lineSizeBytes = cacheP.lineSize
      val wordsPerLine = lineSizeBytes / dataWidthBytes

      val lineBaseAddr = (testAddr / lineSizeBytes) * lineSizeBytes
      println(s"SIM: Initializing memory for line starting at 0x${lineBaseAddr.toString(16)}")
      for (i <- 0 until wordsPerLine) {
        val currentWordAddr = lineBaseAddr + i * dataWidthBytes
        val dataForWord = if (currentWordAddr == testAddr) testData else testData + i + 1
        if (sram) {
          sramFillData(dut, currentWordAddr, dataForWord, dataWidthBytes)
        } else {
          axiMemSim.memory.writeBigInt(currentWordAddr.toLong, dataForWord, dataWidthBytes)
        }
        println(s"  Wrote 0x${dataForWord.toString(16)} to 0x${currentWordAddr.toString(16)}")
      }
      dut.clockDomain.waitSampling(5)

      println(s"SIM: Issuing load to 0x${testAddr.toString(16)} (expect miss)")
      val missTimeoutPerAttempt = wordsPerLine * (2 + 5) + cacheP.loadRspAt + 100
      val maxLoadRetries = 5

      val rsp1 = executeCmdWithRedo[DataLoadCmd, DataLoadRsp, SimDataLoadRsp](
        cmdAction = () => {
          // Use the queue obtained from StreamDriver.queue
          loadCmdQueue.enqueue { cmd =>
            cmd.virtual #= testAddr
            cmd.size #= log2Up(dataWidthBytes)
            // cmd.unlocked #= true
            // cmd.unique #= false
            cmd.redoOnDataHazard #= false
            dut.io.load.translated.physical #= testAddr
            dut.io.load.translated.abord #= false
          }
        },
        rspBuffer = loadRspBuffer,
        rspExtractor = simRsp => (simRsp.redo, simRsp),
        maxRetries = maxLoadRetries,
        timeoutPerAttempt = missTimeoutPerAttempt,
        clockDomain = dut.clockDomain,
        opName = "Load Miss"
      )

      println(s"SIM: Received final miss response: $rsp1 for address 0x${testAddr.toString(16)}")
      assert(!rsp1.fault, "Load (miss) resulted in fault")
      assert(
        rsp1.data == testData,
        s"Load (miss) data mismatch. Expected ${testData.toString(16)}, got ${rsp1.data.toString(16)}"
      )

      println(s"SIM: Issuing load to 0x${testAddr.toString(16)} (expect hit)")
      val hitTimeoutPerAttempt = cacheP.loadRspAt + 20
      val rsp2 = executeCmdWithRedo[DataLoadCmd, DataLoadRsp, SimDataLoadRsp](
        cmdAction = () => {
          loadCmdQueue.enqueue { cmd =>
            cmd.virtual #= testAddr
            cmd.size #= log2Up(dataWidthBytes)
            // cmd.unlocked #= true
            // cmd.unique #= false
            cmd.redoOnDataHazard #= false
            dut.io.load.translated.physical #= testAddr
            dut.io.load.translated.abord #= false
          }
        },
        rspBuffer = loadRspBuffer,
        rspExtractor = simRsp => (simRsp.redo, simRsp),
        maxRetries = maxLoadRetries,
        timeoutPerAttempt = hitTimeoutPerAttempt,
        clockDomain = dut.clockDomain,
        opName = "Load Hit"
      )

      println(s"SIM: Received hit response: $rsp2 for address 0x${testAddr.toString(16)}")
      assert(!rsp2.fault, "Load (hit) resulted in fault")
      assert(
        rsp2.data == testData,
        s"Load (hit) data mismatch. Expected ${testData.toString(16)}, got ${rsp2.data.toString(16)}"
      )

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'Load Miss then Hit' finished.")
    }
  }

  test("DataCache - Store Miss (Write Allocate) then Load Hit") {
    val cacheP = defaultCacheP
    simConfig.compile(new DataCacheTestbench(cacheP)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDutInputs(dut, dut.clockDomain.get)

      val axiMemSim =
        AxiMemorySim(dut.io.mem_axi, dut.clockDomain, AxiMemorySimConfig(readResponseDelay = 2, writeResponseDelay = 2))
      axiMemSim.start()
      dut.clockDomain.assertReset()
      sleep(10)
      dut.clockDomain.deassertReset()

      val loadRspBuffer = mutable.Queue[SimDataLoadRsp]()
      FlowMonitor(dut.io.load.rsp, dut.clockDomain)(payload => loadRspBuffer.enqueue(toSim(payload)))
      val (_, loadCmdQueue) = StreamDriver.queue(dut.io.load.cmd, dut.clockDomain)
      StreamReadyRandomizer(dut.io.load.cmd, dut.clockDomain)

      val storeRspBuffer = mutable.Queue[SimDataStoreRsp]()
      FlowMonitor(dut.io.store.rsp, dut.clockDomain)(payload => storeRspBuffer.enqueue(toSim(payload)))
      val (_, storeCmdQueueFromDriver) = StreamDriver.queue(dut.io.store.cmd, dut.clockDomain) // Get the queue
      StreamReadyRandomizer(dut.io.store.cmd, dut.clockDomain)

      val testAddr = 0x200L
      val initialMemData = BigInt("AAAAAAAAAAAAAAAA", 16)
      val storeData = BigInt("DEADBEEFDEADBEEF", 16)
      val dataWidthBytes = cacheP.cpuDataWidth / 8
      val lineSizeBytes = cacheP.lineSize
      val wordsPerLine = lineSizeBytes / dataWidthBytes
      val maxStoreRetries = 5 // Allow more retries for stores due to generationKo

      val lineBaseAddr = (testAddr / lineSizeBytes) * lineSizeBytes
      println(s"SIM: Initializing memory for line starting at 0x${lineBaseAddr.toHexString} for store test")
      for (i <- 0 until wordsPerLine) {
        val currentWordAddr = lineBaseAddr + i * dataWidthBytes
        axiMemSim.memory.writeBigInt(currentWordAddr, initialMemData + i, dataWidthBytes)
      }
      dut.clockDomain.waitSampling(5)

      println(
        s"SIM: Issuing store to 0x${testAddr.toHexString} with data ${storeData.toString(16)} (expect miss -> allocate)"
      )
      val storeMissTimeoutPerAttempt = wordsPerLine * (2 + 5) + cacheP.storeRspAt + 150

      // -- MODIFICATION START (Use executeStoreCmdWithGenerationKo for store miss) --
      val storeRsp1 = executeStoreCmdWithGenerationKo(
        driveCmdFn = (cmdPayload: DataStoreCmd, currentGen: Boolean) => {
          // Construct the payload for DataStoreCmd
          // This requires a bit of indirection as DataStoreCmd is a Bundle, not its Payload directly
          cmdPayload.address #= testAddr
          cmdPayload.data #= storeData
          cmdPayload.mask #= (1 << cmdPayload.mask.getWidth) - 1 // 显式赋值为全1
          // cmdPayload.generation #= currentGen
          cmdPayload.io #= false
          cmdPayload.flush #= false
          cmdPayload.flushFree #= false
          cmdPayload.prefetch #= false

        },
        storeCmdQueue = storeCmdQueueFromDriver, // Pass the queue from StreamDriver
        storeRspBuffer = storeRspBuffer,
        maxRetries = maxStoreRetries,
        timeoutPerAttempt = storeMissTimeoutPerAttempt,
        clockDomain = dut.clockDomain,
        opName = "Store Miss"
      )
      // -- MODIFICATION END --

      println(s"SIM: Received final store response: ${storeRsp1.toString} for address 0x${testAddr.toHexString}")
      assert(!storeRsp1.fault, "Store (miss) resulted in fault")
      // assert(!storeRsp1.generationKo, "Store (miss) still has generationKo after retries")

      println(s"SIM: Issuing load to 0x${testAddr.toHexString} (expect hit with stored data)")
      val loadHitTimeoutPerAttempt = cacheP.loadRspAt + 20
      val maxLoadRetries = 5
      val loadRsp1 = executeCmdWithRedo[DataLoadCmd, DataLoadRsp, SimDataLoadRsp](
        cmdAction = () => {
          loadCmdQueue.enqueue { cmd =>
            cmd.virtual #= testAddr
            cmd.size #= log2Up(dataWidthBytes)
            // cmd.unlocked #= true
            // cmd.unique #= false
            dut.io.load.translated.physical #= testAddr
            dut.io.load.translated.abord #= false
          }
        },
        rspBuffer = loadRspBuffer,
        rspExtractor = simRsp => (simRsp.redo, simRsp),
        maxRetries = maxLoadRetries,
        timeoutPerAttempt = loadHitTimeoutPerAttempt,
        clockDomain = dut.clockDomain,
        opName = "Load Hit after Store"
      )

      println(s"SIM: Received load response: ${loadRsp1.toString} for address 0x${testAddr.toHexString}")
      assert(!loadRsp1.fault, "Load (hit after store) resulted in fault")
      assert(
        loadRsp1.data == storeData,
        s"Load (hit after store) data mismatch. Expected ${storeData.toString(16)}, got ${loadRsp1.data.toString(16)}"
      )

      dut.clockDomain.waitSampling(20)
      println("SIM: Test 'Store Miss (Write Allocate) then Load Hit' finished.")
    }
  }

  thatsAll
}
