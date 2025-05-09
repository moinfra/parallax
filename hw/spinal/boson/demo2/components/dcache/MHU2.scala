package boson.demo2.components.dcache // Or your preferred package

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import scala.collection.mutable.ArrayBuffer
import boson.demo2.components.memory._

// Payload from DCache pipeline (S2 stage) to MHU
case class MissRequestPayload(
    cpuAddrWidth: Int,
    cpuDataWidth: Int,
    tagWidth: Int,
    indexWidth: Int,
    byteOffsetForLineAddr: Int, // Number of LSB zero bits for a line address (log2Up(bitsPerLine/8))
    bitsPerLine: Int
) extends Bundle {
  val originalAddress = UInt(cpuAddrWidth bits)
  val dataToWrite = Bits(cpuDataWidth bits)
  val byteEnables = Bits(cpuDataWidth / 8 bits)
  val isWrite = Bool()
  val isRefill = Bool() // True for a read miss requiring line refill

  val targetIndex = UInt(indexWidth bits)
  val targetTag = UInt(tagWidth bits)

  def lineAddress: UInt = (targetTag ## targetIndex ## U(0, byteOffsetForLineAddr bits)).asUInt.resized
}

// Payload for refill completion notification
case class RefillCompletionInfo(dcacheCfg: PipelineDCacheConfig) extends Bundle {
  val index = UInt(dcacheCfg.indexWidth bits)
  val tag = UInt(dcacheCfg.tagWidth bits)
  val dataLine = Bits(dcacheCfg.bitsPerLine bits)
  val hadError = Bool()
}

// Payload for write-to-memory completion notification
case class WriteToMemCompletionInfo(cpuAddrWidth: Int) extends Bundle {
  val originalAddress = UInt(cpuAddrWidth bits)
  val hadError = Bool()
}

// Command to cache arrays to perform a write (e.g., for refill)
case class CacheWriteCmd(dcacheCfg: PipelineDCacheConfig) extends Bundle {
  val index = UInt(dcacheCfg.indexWidth bits)
  val tag = UInt(dcacheCfg.tagWidth bits)
  val dataLine = Bits(dcacheCfg.bitsPerLine bits)
  val writeValidBit = Bool()
}

// --- MHU IO Bundle ---
case class MhuIo(
    dcacheCfg: PipelineDCacheConfig,
    memBusCfg: GenericMemoryBusConfig
) extends Bundle {
  val missRequestIn = slave Stream MissRequestPayload(
    dcacheCfg.cpuAddrWidth,
    dcacheCfg.cpuDataWidth,
    dcacheCfg.tagWidth,
    dcacheCfg.indexWidth,
    dcacheCfg.byteOffsetInLineWords, // Use the value directly from config
    dcacheCfg.bitsPerLine
  )
  val memBus = master(GenericMemoryBus(memBusCfg)) // Assuming this is a Bundle of Streams
  val cacheWriteCmdOut = master Stream CacheWriteCmd(dcacheCfg)
  val refillDoneOut = master Stream RefillCompletionInfo(dcacheCfg)
  val writeToMemDoneOut = master Stream WriteToMemCompletionInfo(dcacheCfg.cpuAddrWidth)
  val busy = out Bool ()
}

// --- Individual MSHR FSM ---
class MshrFsm(
    dcacheCfg: PipelineDCacheConfig,
    memBusCfg: GenericMemoryBusConfig,
    mshrId: Int // For reporting/debugging
) extends Component {

  val io = new Bundle {
    val trigger = slave Stream MissRequestPayload(
      dcacheCfg.cpuAddrWidth,
      dcacheCfg.cpuDataWidth,
      dcacheCfg.tagWidth,
      dcacheCfg.indexWidth,
      dcacheCfg.byteOffsetInLineWords,
      dcacheCfg.bitsPerLine
    )
    val memCmd = master Stream GenericMemoryCmd(memBusCfg)
    val memRsp = slave Stream GenericMemoryRsp(memBusCfg)
    val cacheWriteCmd = master Stream CacheWriteCmd(dcacheCfg)
    val refillDone = master Stream RefillCompletionInfo(dcacheCfg)
    val writeToMemDone = master Stream WriteToMemCompletionInfo(dcacheCfg.cpuAddrWidth)
    val isActive = out Bool ()
  }

  val requestPayload = RegNextWhen(io.trigger.payload, io.trigger.fire)
  val refillBuffer = Reg(Bits(dcacheCfg.bitsPerLine bits)) init (0)

  // refillWordCounter is conditionally generated using an elaboration-time 'if'
  val refillWordCounter = if (dcacheCfg.wordsPerLine > 1) {
    Reg(UInt(log2Up(dcacheCfg.wordsPerLine) bits)) init (0)
  } else {
    null // Or U(0) if a placeholder is needed, but access should also be conditional
  }

  io.trigger.ready := False
  io.memCmd.valid := False
  io.memCmd.payload.assignDontCare()
  io.memRsp.ready := False
  io.cacheWriteCmd.valid := False
  io.cacheWriteCmd.payload.assignDontCare()
  io.refillDone.valid := False
  io.refillDone.payload.assignDontCare()
  io.writeToMemDone.valid := False
  io.writeToMemDone.payload.assignDontCare()

  val fsm = new StateMachine {
    val sIdle: State = new State with EntryPoint
    val sProcessRequest: State = new State
    val sMemRequest: State = new State
    val sMemWaitResponse: State = new State
    val sCacheWrite: State = new State
    val sNotifyAndIdle: State = new State

    io.isActive := !isActive(sIdle)

    sIdle.whenIsActive {
      io.trigger.ready := True
      when(io.trigger.fire) {
        // Elaboration-time 'if' to conditionally execute runtime assignment
        if (dcacheCfg.wordsPerLine > 1) {
          refillWordCounter := 0
        }
        refillBuffer.clearAll()
        report(
          L"MSHR ${mshrId}: Activated. Addr=${io.trigger.payload.originalAddress}, Refill=${io.trigger.payload.isRefill}, Write=${io.trigger.payload.isWrite}"
        )
        goto(sProcessRequest)
      }
    }

    sProcessRequest.whenIsActive {
      when(requestPayload.isRefill) { // Runtime condition
        report(L"MSHR ${mshrId}: Processing Read Miss Refill. Line Addr=${requestPayload.lineAddress}")
        goto(sMemRequest)
      } elsewhen (requestPayload.isWrite) { // Runtime condition
        report(L"MSHR ${mshrId}: Processing WTNA Write. Addr=${requestPayload.originalAddress}")
        goto(sMemRequest)
      } otherwise { // Runtime condition
        report(L"MSHR ${mshrId}: ERROR - Unexpected request type. Addr=${requestPayload.originalAddress}")
        io.writeToMemDone.payload.originalAddress := requestPayload.originalAddress
        io.writeToMemDone.payload.hadError := True
        io.writeToMemDone.valid := True
        goto(sNotifyAndIdle)
      }
    }

    sMemRequest.whenIsActive {
      io.memCmd.valid := True
      when(requestPayload.isRefill) { // Runtime condition
        // Elaboration-time 'if' selects how wordIdxHardware is derived
        val wordIdxHardware = if (dcacheCfg.wordsPerLine > 1) refillWordCounter else U(0)
        val wordByteOffset = (wordIdxHardware << log2Up(dcacheCfg.cpuDataWidth / 8)).resize(dcacheCfg.cpuAddrWidth)
        val currentWordAddress = requestPayload.lineAddress + wordByteOffset

        io.memCmd.payload.address := currentWordAddress
        io.memCmd.payload.opcode := GenericMemoryBusOpcode.OP_READ
        io.memCmd.payload.writeData.assignDontCare()
        io.memCmd.payload.writeByteEnables.assignDontCare()
        report(L"MSHR ${mshrId}: Requesting word ${wordIdxHardware} for refill. Addr=${currentWordAddress}")
      } otherwise { // Runtime alternative (implies requestPayload.isWrite is True due to sProcessRequest)
        io.memCmd.payload.address := requestPayload.originalAddress
        io.memCmd.payload.opcode := GenericMemoryBusOpcode.OP_WRITE
        io.memCmd.payload.writeData := requestPayload.dataToWrite
        io.memCmd.payload.writeByteEnables := requestPayload.byteEnables
        report(L"MSHR ${mshrId}: Requesting WTNA write. Addr=${requestPayload.originalAddress}")
      }

      when(io.memCmd.fire) { // Runtime condition
        report(L"MSHR ${mshrId}: Memory command fired.")
        goto(sMemWaitResponse)
      }
    }

    sMemWaitResponse.whenIsActive {
      io.memRsp.ready := True
      when(io.memRsp.fire) { // Runtime condition
        val rspError = io.memRsp.payload.error // Runtime signal
        report(L"MSHR ${mshrId}: Memory response received. Error=${rspError}")

        when(requestPayload.isRefill) { // Runtime condition
          when(!rspError) { // Runtime condition
            // Elaboration-time 'if' to determine hardware source for wordIdxHardware
            val wordIdxHardware = if (dcacheCfg.wordsPerLine > 1) refillWordCounter else U(0)
            // Assuming cpuDataWidth is the width of one word transferred over memBus
            refillBuffer.subdivideIn(dcacheCfg.cpuDataWidth bits)(
              wordIdxHardware.resized
            ) := io.memRsp.payload.readData.resized
          }

          // Elaboration-time 'if' to define how isLastWordHardware (a runtime Bool) is computed
          val isLastWordHardware = if (dcacheCfg.wordsPerLine > 1) {
            (refillWordCounter + U(1)) === dcacheCfg.wordsPerLine
          } else {
            True // Hardware True
          }

          // CRITICAL SECTION: Using runtime 'when/otherwise' for runtime decisions
          when(rspError || isLastWordHardware) { // Runtime condition: Refill attempt concludes
            io.refillDone.payload.index := requestPayload.targetIndex
            io.refillDone.payload.tag := requestPayload.targetTag
            io.refillDone.payload.dataLine := refillBuffer // Data sent even if error (might be partial)

            when(rspError) { // Runtime condition: Error occurred
              report(L"MSHR ${mshrId}: Refill error encountered.")
              io.refillDone.payload.hadError := True
              io.refillDone.valid := True // Signal refill completion (with error)
              goto(sNotifyAndIdle) // Skip cache write
            } otherwise { // No error, and it's the last word
              report(L"MSHR ${mshrId}: All words for refill received successfully.")
              // refillDone payload (hadError=False) and valid will be set in sCacheWrite state
              goto(sCacheWrite) // Proceed to write to cache
            }
          } otherwise { // More words needed (implies !rspError and !isLastWordHardware)
            // This 'otherwise' implies dcacheCfg.wordsPerLine > 1
            if (dcacheCfg.wordsPerLine > 1) { // Elaboration-time 'if' for conditional assignment
              refillWordCounter := refillWordCounter + U(1)
            }
            val nextReportedCounter = if (dcacheCfg.wordsPerLine > 1) refillWordCounter + U(1) else U(0) // For report
            report(L"MSHR ${mshrId}: More words needed. Next counter (approx): ${nextReportedCounter}")
            goto(sMemRequest)
          }
        } otherwise { // Not a refill (WTNA Write response)
          io.writeToMemDone.payload.originalAddress := requestPayload.originalAddress
          io.writeToMemDone.payload.hadError := rspError
          io.writeToMemDone.valid := True
          report(L"MSHR ${mshrId}: WTNA Write to memory complete. Error=${rspError}")
          goto(sNotifyAndIdle)
        }
      }
    }

    sCacheWrite.whenIsActive { // Only for successful, completed refills
      report(L"MSHR ${mshrId}: Attempting to write refilled line to Cache. Index=${requestPayload.targetIndex}")
      io.cacheWriteCmd.valid := True
      io.cacheWriteCmd.payload.index := requestPayload.targetIndex
      io.cacheWriteCmd.payload.tag := requestPayload.targetTag
      io.cacheWriteCmd.payload.dataLine := refillBuffer
      io.cacheWriteCmd.payload.writeValidBit := True

      when(io.cacheWriteCmd.fire) { // Runtime condition
        report(L"MSHR ${mshrId}: Cache write command accepted.")
        // Prepare and send the successful refillDone notification
        io.refillDone.payload.index := requestPayload.targetIndex
        io.refillDone.payload.tag := requestPayload.targetTag
        io.refillDone.payload.dataLine := refillBuffer
        io.refillDone.payload.hadError := False // Success
        io.refillDone.valid := True
        goto(sNotifyAndIdle)
      }
    }

    sNotifyAndIdle.whenIsActive {
      // Handles consuming the notification sent by a previous state
      val refillHandled = io.refillDone.ready || !io.refillDone.valid // Runtime Bools
      val writeHandled = io.writeToMemDone.ready || !io.writeToMemDone.valid // Runtime Bools

      when(refillHandled && writeHandled) { // Runtime condition
        report(L"MSHR ${mshrId}: Notification handled. Returning to Idle.")
        // Defensive clearing of valid flags if they were asserted by this MSHR
        // (though StreamArbiter's ready signal should mean they were consumed or never became an issue)
        when(io.refillDone.valid) { io.refillDone.valid := False }
        when(io.writeToMemDone.valid) { io.writeToMemDone.valid := False }
        goto(sIdle)
      }
    }
  }
}

// --- MissHandlingUnit ---
// The MissHandlingUnit class structure (MSHR Vec, arbiters, IO connections)
// would remain the same as in the previous detailed response.
// The key was ensuring the MshrFsm internals adhere to the elaboration vs. runtime rules.

class MissHandlingUnit(
    dcacheCfg: PipelineDCacheConfig,
    memBusCfg: GenericMemoryBusConfig
) extends Component {

  val io = MhuIo(dcacheCfg, memBusCfg) // Assuming MhuIo is defined

  val mshrs = ArrayBuffer.tabulate[MshrFsm](dcacheCfg.numMshrs)(i => new MshrFsm(dcacheCfg, memBusCfg, mshrId = i))

  // --- MSHR Allocation ---
  val freeMshrOH_values = mshrs.map(_.io.trigger.ready) // Collection of Bools
  val freeMshrOH = Vec(freeMshrOH_values).asBits // Convert to Bits for OHMasking

  val firstFreeMshrMask = OHMasking.first(freeMshrOH)
  val hasFreeMshr = freeMshrOH.orR

  io.missRequestIn.ready := hasFreeMshr

  for (i <- 0 until dcacheCfg.numMshrs) {
    // Runtime assignments based on runtime conditions
    mshrs(i).io.trigger.valid := io.missRequestIn.fire && firstFreeMshrMask(i)
    mshrs(i).io.trigger.payload := io.missRequestIn.payload // Payload assignment conditional on valid
  }

  // --- Busy Signal ---
  val anyMshrActive = mshrs.map(_.io.isActive).orR // Runtime Bool
  io.busy := anyMshrActive || (io.missRequestIn.fire && hasFreeMshr)

  // --- Memory Bus Command Arbitration ---
  val memCmdArbiter = StreamArbiterFactory.roundRobin.build(GenericMemoryCmd(memBusCfg), dcacheCfg.numMshrs)
  for (i <- 0 until dcacheCfg.numMshrs) {
    memCmdArbiter.io.inputs(i) << mshrs(i).io.memCmd
  }
  io.memBus.cmd << memCmdArbiter.io.output.stage()

  // --- Memory Bus Response Routing (Simplified) ---
  val memCmdGrantOH = memCmdArbiter.io.chosen // Runtime Bits
  val memRspTargetOH = RegNextWhen(memCmdGrantOH, io.memBus.cmd.fire, init = B(0).resized) // Runtime Reg

  io.memBus.rsp.ready := False
  val mshrMemRspReadies = Vec(Bool(), dcacheCfg.numMshrs) // Collect ready signals from MSHRs
  mshrMemRspReadies.foreach(_ := False) // Default

  for (i <- 0 until dcacheCfg.numMshrs) {
    mshrs(i).io.memRsp.valid := False // Default
    mshrs(i).io.memRsp.payload := io.memBus.rsp.payload

    when(memRspTargetOH(i) && io.memBus.rsp.valid) { // Runtime condition
      mshrs(i).io.memRsp.valid := True
      mshrMemRspReadies(i) := mshrs(i).io.memRsp.ready
    }
  }
  // Combine ready signals if any targeted MSHR is ready
  when(memRspTargetOH.orR) { // Check if any target is selected
    val targetIdx = OHToUInt(memRspTargetOH) // Assumes memRspTargetOH is indeed one-hot
    io.memBus.rsp.ready := mshrMemRspReadies(targetIdx)
  }

  // --- Cache Write Command Arbitration ---
  val cacheWriteArbiter = StreamArbiterFactory.roundRobin.build(CacheWriteCmd(dcacheCfg), dcacheCfg.numMshrs)
  for (i <- 0 until dcacheCfg.numMshrs) {
    cacheWriteArbiter.io.inputs(i) << mshrs(i).io.cacheWriteCmd
  }
  io.cacheWriteCmdOut << cacheWriteArbiter.io.output.stage()

  // --- Refill Done Notification Arbitration ---
  val refillDoneArbiter = StreamArbiterFactory.roundRobin.build(RefillCompletionInfo(dcacheCfg), dcacheCfg.numMshrs)
  for (i <- 0 until dcacheCfg.numMshrs) {
    refillDoneArbiter.io.inputs(i) << mshrs(i).io.refillDone
  }
  io.refillDoneOut << refillDoneArbiter.io.output.stage()

  // --- WriteToMem Done Notification Arbitration ---
  val writeToMemDoneArbiter =
    StreamArbiterFactory.roundRobin.build(WriteToMemCompletionInfo(dcacheCfg.cpuAddrWidth), dcacheCfg.numMshrs)
  for (i <- 0 until dcacheCfg.numMshrs) {
    writeToMemDoneArbiter.io.inputs(i) << mshrs(i).io.writeToMemDone
  }
  io.writeToMemDoneOut << writeToMemDoneArbiter.io.output.stage()
}
