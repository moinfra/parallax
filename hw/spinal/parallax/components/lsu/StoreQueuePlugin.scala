package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.{Pipeline, Stage, Stageable, Connection}
import parallax.common._
import parallax.components.rob.ROBFlushPayload
import parallax.utilities.{Plugin, Service, LockedImpl, ParallaxLogger, ParallaxSim}
import parallax.components.dcache2._

trait StoreQueueService extends Service with LockedImpl {
  def getAllocatePort(): Stream[StoreQueueEntry]
  def getStatusUpdatePort(): Flow[SqStatusUpdate]
}

trait CommitServiceForLsu extends Service {
  def getReleasePorts(): Vec[Flow[UInt]]
  def getReleaseMask(): Vec[Bool]
}

// 服务：由 ROB 或 Flush Controller 提供
trait FlushServiceForLsu extends Service {
  def getLsuFlushPort(): Flow[ROBFlushPayload]
}

class StoreQueuePlugin(
    val lsuConfig: LsuConfig,
    val pipelineConfig: PipelineConfig,
    val dCacheConfig: DataCacheParameters
) extends Plugin
    with StoreQueueService
    with LockedImpl {

  private val enableLog = true

  private val setup = create early new Area {
    // 1. Get dependent services
    val commitService = getService[CommitServiceForLsu]
    val flushService = getService[FlushServiceForLsu]
    val aguService = getService[AguService]
    val prfService = getService[PhysicalRegFileService]
    val dcacheService = getService[DataCacheService]

    // 2. Get the actual ports/signals from services and store them for later use
    val releasePorts = commitService.getReleasePorts()
    val releaseMask = commitService.getReleaseMask()
    val sqFlushPort = flushService.getLsuFlushPort()

    // Store the ports that SQ will drive or be driven by
    val aguPort = aguService.newAguPort()
    aguPort.flush := False
    aguPort.input.valid := False
    aguPort.input.payload.assignDontCare()

    val prfReadPort = prfService.newReadPort()
    prfReadPort.valid := False
    prfReadPort.address.assignDontCare()

    val dCacheStorePort = dcacheService.newStorePort()

    // 3. Define the ports that this plugin itself provides as a service
    val allocatePortInst = Stream(StoreQueueEntry(lsuConfig))
    val statusUpdatePortInst = Flow(SqStatusUpdate(lsuConfig))
  }

  private val logic = create late new Area {
    lock.await()
    ParallaxLogger.log("SQPlugin: Elaborating core logic.")

    private val validsReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val committedsReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val writebackCompletesReg = Vec(Reg(Bool()) init (False), lsuConfig.sqDepth)
    private val waitOnsReg = Vec.fill(lsuConfig.sqDepth)(Reg(SqWaitOn(lsuConfig)))

    private val sqPtrs = Mem(UInt(lsuConfig.sqPtrWidth), lsuConfig.sqDepth)
    private val robPtrsRegFile = Vec(Reg(UInt(pipelineConfig.robPtrWidth)), lsuConfig.sqDepth)
    private val pcs = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    private val physDataSrcs = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    private val physDataSrcIsFprs = Mem(Bool(), lsuConfig.sqDepth)
    private val agImm = Mem(SInt(12 bits), lsuConfig.sqDepth)
    private val agBasePhysReg = Mem(UInt(lsuConfig.physGprIdxWidth), lsuConfig.sqDepth)
    private val agUsePc = Mem(Bool(), lsuConfig.sqDepth)
    private val accessSizes = Mem(MemAccessSize(), lsuConfig.sqDepth)

    private val physicalAddresses = Mem(UInt(lsuConfig.pcWidth), lsuConfig.sqDepth)
    private val dataToWrites = Mem(Bits(lsuConfig.dataWidth), lsuConfig.sqDepth)
    private val storeMasks = Mem(Bits(lsuConfig.dataWidth.value / 8 bits), lsuConfig.sqDepth)

    private val allocPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val commitPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val writebackPtrReg = Reg(UInt(lsuConfig.sqPtrWidth)) init (0)
    private val priorityPtrReg = Reg(UInt(lsuConfig.sqIdxWidth)) init (0)

    private def isNewerOrSame(robPtrA: UInt, robPtrB: UInt): Bool = {
      val genA = robPtrA.msb; val idxA = robPtrA(robPtrA.high - 1 downto 0)
      val genB = robPtrB.msb; val idxB = robPtrB(robPtrB.high - 1 downto 0)
      (genA === genB && idxA >= idxB) || (genA =/= genB && idxA < idxB)
    }
    private def getPhysicalIndex(ptr: UInt): UInt = ptr(lsuConfig.sqIdxWidth.value - 1 downto 0)

    private val ptrsSameGen = allocPtrReg.msb === writebackPtrReg.msb
    private val allocIdxForCount = getPhysicalIndex(allocPtrReg)
    private val writebackIdxForCount = getPhysicalIndex(writebackPtrReg)
    private val entryCount = UInt(log2Up(lsuConfig.sqDepth + 1) bits)
    when(ptrsSameGen) { entryCount := (allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth) }
      .otherwise {
        entryCount := (U(lsuConfig.sqDepth) + allocIdxForCount - writebackIdxForCount).resize(entryCount.getWidth)
      }
    private val isFull = entryCount === lsuConfig.sqDepth
    if (enableLog)
      ParallaxSim.debug(L"[SQPlugin] Current SQ entry count: ${entryCount}, IsFull: ${isFull}")


    private val allocationLogic = new Area {
      setup.allocatePortInst.ready := !isFull
      if (enableLog)
        ParallaxSim.debug(L"[SQPlugin] AllocatePort ready: ${setup.allocatePortInst.ready}, valid: ${setup.allocatePortInst.valid}")

      when(setup.allocatePortInst.fire) {
        val newEntry = setup.allocatePortInst.payload
        val allocPhysIdx = getPhysicalIndex(allocPtrReg)
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] Allocating new entry at Ptr: ${allocPtrReg} (PhysIdx: ${allocPhysIdx}, ROB ID: ${newEntry.robPtr}, PC: ${newEntry.pc})")
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] New entry details - basePhysReg: ${newEntry.aguBasePhysReg}, immediate: ${newEntry.aguImmediate}, accessSize: ${newEntry.accessSize}, usePc: ${newEntry.aguUsePcAsBase}")


        sqPtrs.write(allocPhysIdx, allocPtrReg)
        robPtrsRegFile(allocPhysIdx) := newEntry.robPtr // Write to Vec(Reg)
        pcs.write(allocPhysIdx, newEntry.pc)
        physDataSrcs.write(allocPhysIdx, newEntry.physDataSrc)
        physDataSrcIsFprs.write(allocPhysIdx, newEntry.physDataSrcIsFpr)
        agImm.write(allocPhysIdx, newEntry.aguImmediate)
        agBasePhysReg.write(allocPhysIdx, newEntry.aguBasePhysReg)
        agUsePc.write(allocPhysIdx, newEntry.aguUsePcAsBase)
        accessSizes.write(allocPhysIdx, newEntry.accessSize)

        validsReg(allocPhysIdx) := True
        committedsReg(allocPhysIdx) := False
        writebackCompletesReg(allocPhysIdx) := False
        waitOnsReg(allocPhysIdx).setDefault()
        waitOnsReg(allocPhysIdx).isStalledForAgu := True

        // Read from PRF and write data to dataToWrites immediately upon allocation.
        // FPGA 保证 0 延迟
        val prfPort = setup.prfReadPort
        prfPort.valid := True
        prfPort.address := newEntry.physDataSrc
        dataToWrites.write(allocPhysIdx, prfPort.rsp)
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] PRF read triggered for physDataSrc: ${newEntry.physDataSrc}, rsp: ${prfPort.rsp}")


        allocPtrReg := allocPtrReg + 1
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] allocPtrReg updated to: ${allocPtrReg + 1}")
      }
    }

    private val releaseOnCommitLogic = new Area {
      // --- 并行检查每个提交槽位 ---
      val releaseHits = Vec(Bool(), lsuConfig.commitWidth)
      var commitPtrAhead = commitPtrReg // 组合逻辑指针，用于超前计算
      if (enableLog)
        ParallaxSim.debug(L"[SQPlugin] Commit check started. Current commitPtrReg: ${commitPtrReg}")

      for (i <- 0 until lsuConfig.commitWidth) {
        val physIdx = getPhysicalIndex(commitPtrAhead)
        val expectedRobPtr = robPtrsRegFile(physIdx)

        // 检查当前槽位的提交是否有效且匹配
        val doRelease = setup.releasePorts(i).valid &&
          setup.releaseMask(i) &&
          validsReg(physIdx) && // 确保SQ条目本身是有效的
          expectedRobPtr === setup.releasePorts(i).payload

        releaseHits(i) := doRelease
        if (enableLog)
          ParallaxSim.debug(
            L"[SQPlugin] Commit port ${i}: valid=${setup.releasePorts(i).valid}, mask=${setup.releaseMask(i)}, robPtr=${setup.releasePorts(i).payload}. " :+
              L"SQ entry PhysIdx=${physIdx}, expectedRobPtr=${expectedRobPtr}, isValid=${validsReg(physIdx)}. doRelease=${doRelease}"
          )


        // 如果当前槽位成功提交，则为下一个槽位的检查更新指针
        when(doRelease) {
          val commitPhysIdx = getPhysicalIndex(commitPtrAhead)
          committedsReg(commitPhysIdx) := True // 标记为已提交
          if (enableLog)
            ParallaxSim.debug(
              L"[SQPlugin] Committing entry at Ptr: ${commitPtrAhead} (PhysIdx: ${commitPhysIdx}, ROB ID: ${setup.releasePorts(i).payload})"
            )
        }

        // 关键：指针的超前计算
        commitPtrAhead = commitPtrAhead + U(doRelease)
      }

      // --- 一次性更新物理寄存器 ---
      // finalCommitPtrAfterCheck 是所有成功提交后最终的指针位置
      val finalCommitPtrAfterCheck = commitPtrAhead
      when(finalCommitPtrAfterCheck =/= commitPtrReg) {
        commitPtrReg := finalCommitPtrAfterCheck
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] commitPtrReg updated to: ${finalCommitPtrAfterCheck}")
      }
    }

    private val flushLogic = new Area {
      when(setup.sqFlushPort.valid) {
        val flushCmd = setup.sqFlushPort.payload
        if (enableLog) ParallaxSim.debug(L"[SQPlugin] Precise Flush received: TargetROB=${flushCmd.targetRobPtr}")

        // With robPtrsRegFile (a Vec[Reg]), we can perform parallel reads combinationally.
        // This correctly implements the intended logic without timing violations.
        for (i <- 0 until lsuConfig.sqDepth) {
          when(validsReg(i) && !committedsReg(i)) {
            val entryRobPtr = robPtrsRegFile(i)
            when(isNewerOrSame(entryRobPtr, flushCmd.targetRobPtr)) {
              validsReg(i) := False
              if (enableLog)
                ParallaxSim.debug(
                  L"[SQPlugin] Invalidating SQ entry at Idx ${i.toString} with ROB ID ${entryRobPtr} due to flush."
                )
            }
          }
        }
        allocPtrReg := commitPtrReg
        priorityPtrReg := getPhysicalIndex(commitPtrReg)
        if (enableLog)
          ParallaxSim.debug(L"[SQPlugin] Flush completed. allocPtrReg set to commitPtrReg (${commitPtrReg}), priorityPtrReg set to ${getPhysicalIndex(commitPtrReg)}")
      }
    }

    private val execution = new Area {
      // -- MODIFICATION START: Simplify execution pipeline to only handle AGU --
      val aguPipe = new Pipeline {
        val SCHEDULE, DISPATCH = newStage()
        connect(SCHEDULE, DISPATCH)(Connection.M2S())
      }

      val SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
      val AGU_BASE_PHYS_REG = Stageable(UInt(lsuConfig.physGprIdxWidth))
      val AGU_IMM = Stageable(SInt(12 bits))
      val ACCESS_SIZE = Stageable(MemAccessSize())
      val AGU_USE_PC = Stageable(Bool())
      val PC = Stageable(UInt(lsuConfig.pcWidth))
      val ROB_PTR = Stageable(UInt(lsuConfig.robPtrWidth))

      val scheduleStage = aguPipe.SCHEDULE
      scheduleStage(SQ_PTR).assignDontCare()
      scheduleStage(AGU_BASE_PHYS_REG).assignDontCare()
      scheduleStage(AGU_IMM).assignDontCare()
      scheduleStage(ACCESS_SIZE).assignDontCare()
      scheduleStage(AGU_USE_PC).assignDontCare()
      scheduleStage(PC).assignDontCare()
      scheduleStage(ROB_PTR).assignDontCare()
      
      val priorityMask = ~((U(1) << priorityPtrReg) - 1)
      val aguCandidates = B(validsReg.zip(waitOnsReg).map { case (v, w) => v && w.isStalledForAgu })
      val aguSelOh = OHMasking.roundRobin(aguCandidates, priorityMask)
      val aguTaskSelected = aguSelOh.orR

      scheduleStage.valid := aguTaskSelected
      if (enableLog)
        ParallaxSim.debug(L"[SQ AGU] Schedule stage valid: ${scheduleStage.valid}, aguCandidates: ${aguCandidates.asBits}, priorityPtrReg: ${priorityPtrReg}")

      when(scheduleStage.valid) {
        val selIdx = OHToUInt(aguSelOh)
        if (enableLog)
          ParallaxSim.debug(L"[SQ AGU] Schedule stage selected PhysIdx: ${selIdx}")
        
        scheduleStage(AGU_BASE_PHYS_REG) := agBasePhysReg.readAsync(selIdx)
        scheduleStage(AGU_IMM) := agImm.readAsync(selIdx)
        scheduleStage(ACCESS_SIZE) := accessSizes.readAsync(selIdx)
        scheduleStage(AGU_USE_PC) := agUsePc.readAsync(selIdx)
        scheduleStage(PC) := pcs.readAsync(selIdx)
        scheduleStage(ROB_PTR) := robPtrsRegFile(selIdx)
        scheduleStage(SQ_PTR) := sqPtrs.readAsync(selIdx)
        if (enableLog)
          ParallaxSim.debug(L"[SQ AGU] Scheduled AGU request for SQ Ptr ${sqPtrs.readAsync(selIdx)} (ROB ID: ${robPtrsRegFile(selIdx)})")
      }

      val dispatchStage = aguPipe.DISPATCH
      dispatchStage.haltWhen(dispatchStage.valid && !setup.aguPort.input.ready)
      if (enableLog)
        ParallaxSim.debug(L"[SQ AGU] Dispatch stage valid: ${dispatchStage.valid}, AGU input ready: ${setup.aguPort.input.ready}")

      when(dispatchStage.isFiring) {
        val aguIn = setup.aguPort.input
        aguIn.valid := True
        val p = aguIn.payload
        val sqPtr = dispatchStage(SQ_PTR)
        val sqIdx = getPhysicalIndex(sqPtr)

        p.basePhysReg := dispatchStage(AGU_BASE_PHYS_REG)
        p.immediate   := dispatchStage(AGU_IMM)
        p.accessSize  := dispatchStage(ACCESS_SIZE)
        p.usePc       := dispatchStage(AGU_USE_PC)
        p.pc          := dispatchStage(PC)
        p.robPtr      := dispatchStage(ROB_PTR)
        p.qPtr        := sqPtr
        p.isLoad      := False
        p.isStore     := True
        p.physDst     := 0

        waitOnsReg(sqIdx).isStalledForAgu := False
        priorityPtrReg := sqIdx + 1
        if (enableLog) ParallaxSim.debug(L"[SQ AGU] Fired AGU request for SQ Ptr ${sqPtr} (PhysIdx: ${sqIdx}), next priorityPtrReg: ${sqIdx + 1}")
      }
      aguPipe.build()
      // -- MODIFICATION END --
    }

    private val writebackAndRelease = new Area {
      val pipe = new Pipeline {
        val WB_SCHEDULE, WB_DRIVE = newStage()
        connect(WB_SCHEDULE, WB_DRIVE)(Connection.M2S())
      }

      val WB_SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
      val WB_ADDRESS = Stageable(UInt(lsuConfig.pcWidth))
      val WB_DATA = Stageable(Bits(lsuConfig.dataWidth))
      val WB_MASK = Stageable(Bits(lsuConfig.dataWidth.value / 8 bits))

      val scheduleStage = pipe.WB_SCHEDULE
      // -- MODIFICATION START (Fix Combinatorial Loop) --
      // The line `scheduleStage.haltWhen(scheduleStage.isValid && !scheduleStage.isReady)` was removed.
      // It creates a combinational loop as a stage's ready signal implicitly depends on its own halt signal.
      // The pipeline's built-in backpressure mechanism correctly handles this stalling condition.
      // -- MODIFICATION END --

      // -- MODIFICATION START (Rule 58: Prevent Latches) --
      // 为所有由本阶段产生的Stageable提供无条件的默认驱动
      scheduleStage(WB_SQ_PTR).assignDontCare()
      scheduleStage(WB_ADDRESS).assignDontCare()
      scheduleStage(WB_DATA).assignDontCare()
      scheduleStage(WB_MASK).assignDontCare()
      // -- MODIFICATION END --

      val ptr = writebackPtrReg
      val physIdx = getPhysicalIndex(ptr)

      // **写回条件**:
      // 1. 条目有效 (validsReg)
      // 2. 已被ROB提交 (committedsReg)
      // 3. 尚未完成最终的写回 (writebackCompletesReg)
      // 4. 地址已生成 (waitOnsReg.address)
      // 5. 不在等待DCache响应 (!waitOnsReg.dCacheStoreRsp)
      val canWriteback = validsReg(physIdx) &&
        committedsReg(physIdx) &&
        !writebackCompletesReg(physIdx) &&
        waitOnsReg(physIdx).address &&
        !waitOnsReg(physIdx).dCacheStoreRsp

      scheduleStage.valid := canWriteback
      if (enableLog)
        ParallaxSim.debug(L"[SQ Writeback] Schedule stage valid: ${scheduleStage.valid} for Ptr ${ptr} (PhysIdx: ${physIdx}). " :+
          L"Conditions: valid=${validsReg(physIdx)}, committed=${committedsReg(physIdx)}, writebackComplete=${writebackCompletesReg(physIdx)}, " :+
          L"addressReady=${waitOnsReg(physIdx).address}, dCacheRspPending=${waitOnsReg(physIdx).dCacheStoreRsp}")

      when(scheduleStage.isFiring) {
        scheduleStage(WB_SQ_PTR) := ptr
        scheduleStage(WB_ADDRESS) := physicalAddresses.readAsync(physIdx)
        scheduleStage(WB_DATA) := dataToWrites.readAsync(physIdx)
        scheduleStage(WB_MASK) := storeMasks.readAsync(physIdx)

        if (enableLog) ParallaxSim.debug(L"[SQ Writeback] SCHEDULE stage fired for SQ Ptr ${ptr} (PhysIdx: ${physIdx}). Address: ${physicalAddresses.readAsync(physIdx)}, Data: ${dataToWrites.readAsync(physIdx)}, Mask: ${storeMasks.readAsync(physIdx)}")
      }

      val driveStage = pipe.WB_DRIVE
      val dCacheCmdPort = setup.dCacheStorePort.cmd

      dCacheCmdPort.valid := driveStage.valid
      dCacheCmdPort.payload.assignDontCare() // 提供默认驱动

      when(driveStage.valid) {
        dCacheCmdPort.payload.address := driveStage(WB_ADDRESS)
        dCacheCmdPort.payload.data := driveStage(WB_DATA)
        dCacheCmdPort.payload.mask := driveStage(WB_MASK)
        dCacheCmdPort.payload.io := False
        dCacheCmdPort.payload.flush := False
        dCacheCmdPort.payload.flushFree := False
        dCacheCmdPort.payload.prefetch := False
        if (enableLog)
          ParallaxSim.debug(L"[SQ Writeback] DRIVE stage sending DCache command: Address=${driveStage(WB_ADDRESS)}, Data=${driveStage(WB_DATA)}, Mask=${driveStage(WB_MASK)}")
      }

      driveStage.haltWhen(driveStage.valid && !dCacheCmdPort.ready)
      if (enableLog)
        ParallaxSim.debug(L"[SQ Writeback] DRIVE stage valid: ${driveStage.valid}, DCacheCmdPort ready: ${dCacheCmdPort.ready}")


      when(dCacheCmdPort.fire) {
        val wbSqPtr = driveStage(WB_SQ_PTR)
        val wbPhysIdx = getPhysicalIndex(wbSqPtr)

        waitOnsReg(wbPhysIdx).dCacheStoreRsp := True
        if (enableLog) ParallaxSim.debug(L"[SQ Writeback] DRIVE stage fired DCache write for SQ Ptr ${wbSqPtr} (PhysIdx: ${wbPhysIdx}). Marking dCacheStoreRsp as True.")
      }

      val releasePhysIdx = getPhysicalIndex(writebackPtrReg)
      if (enableLog)
        ParallaxSim.debug(L"[SQ Release] Checking release for Ptr ${writebackPtrReg} (PhysIdx: ${releasePhysIdx}). writebackCompletesReg(${releasePhysIdx}): ${writebackCompletesReg(releasePhysIdx)}")

      when(writebackCompletesReg(releasePhysIdx)) {
        validsReg(releasePhysIdx) := False
        committedsReg(releasePhysIdx) := False
        writebackCompletesReg(releasePhysIdx) := False
        waitOnsReg(releasePhysIdx).setDefault()

        writebackPtrReg := writebackPtrReg + 1
        if (enableLog)
          ParallaxSim.debug(L"[SQ Release] Releasing SQ Ptr ${writebackPtrReg} (PhysIdx: ${releasePhysIdx}) after writeback completion. writebackPtrReg updated to ${writebackPtrReg + 1}.")
      }

      pipe.build()
    }

    private val statusUpdateLogic = new Area {
      val pipe = new Pipeline {
        val ARBITRATE, READ_MEM, EXECUTE = newStage()
        connect(ARBITRATE, READ_MEM)(Connection.M2S())
        connect(READ_MEM, EXECUTE)(Connection.M2S())
      }

      object UpdateSource extends SpinalEnum {
        val NONE, AGU, DCACHE_RSP, EXTERNAL_PORT = newElement()
      }
      val UPDATE_SOURCE = Stageable(UpdateSource())
      val UPDATE_PTR_IN = Stageable(UInt(lsuConfig.sqPtrWidth))
      val EXPECTED_SQ_PTR = Stageable(UInt(lsuConfig.sqPtrWidth))
      val AGU_PAYLOAD = Stageable(AguOutput(lsuConfig))
      val PORT_PAYLOAD = Stageable(SqStatusUpdate(lsuConfig))
      val DCACHE_RSP_PAYLOAD = Stageable(DataStoreRsp(dCacheConfig.postTranslationWidth, dCacheConfig.refillCount, dCacheConfig.transactionIdWidth))

      val arbitrateStage = pipe.ARBITRATE
      // -- MODIFICATION START (Fix Combinatorial Loop) --
      // The line `arbitrateStage.haltWhen(arbitrateStage.isValid && !arbitrateStage.isReady)` was removed.
      // It creates a combinational loop as a stage's ready signal implicitly depends on its own halt signal.
      // The pipeline's built-in backpressure mechanism correctly handles this stalling condition.
      // -- MODIFICATION END --

      // 这部分已经遵循了最佳实践：先提供默认值
      arbitrateStage(UPDATE_SOURCE).assignDontCare()
      arbitrateStage(UPDATE_PTR_IN).assignDontCare()
      arbitrateStage(AGU_PAYLOAD).assignDontCare()
      arbitrateStage(DCACHE_RSP_PAYLOAD).assignDontCare()
      arbitrateStage(PORT_PAYLOAD).assignDontCare()

      setup.aguPort.output.ready := False

      // 定义更新源的有效性，并设置优先级 (AGU > DCache > 外部端口)
      val aguTakesIt = setup.aguPort.output.valid
      val dcacheRspTakesIt = setup.dCacheStorePort.rsp.valid && !aguTakesIt
      val externalTakesIt = setup.statusUpdatePortInst.valid && !aguTakesIt && !dcacheRspTakesIt

      arbitrateStage.valid := aguTakesIt || dcacheRspTakesIt || externalTakesIt
      if (enableLog)
        ParallaxSim.debug(L"[SQ StatusUpdate] Arbitrate stage valid: ${arbitrateStage.valid}. " :+
          L"AGU valid: ${setup.aguPort.output.valid}, DCacheRsp valid: ${setup.dCacheStorePort.rsp.valid}, External valid: ${setup.statusUpdatePortInst.valid}")


      when(aguTakesIt) {
        arbitrateStage(UPDATE_SOURCE) := UpdateSource.AGU
        arbitrateStage(UPDATE_PTR_IN) := setup.aguPort.output.payload.qPtr
        arbitrateStage(AGU_PAYLOAD) := setup.aguPort.output.payload
        setup.aguPort.output.ready := arbitrateStage.isReady // 将反压传递回去
        if (enableLog)
          ParallaxSim.debug(L"[SQ StatusUpdate] Arbitrate selected AGU update for QPtr: ${setup.aguPort.output.payload.qPtr}")
      } elsewhen (dcacheRspTakesIt) {
        arbitrateStage(UPDATE_SOURCE) := UpdateSource.DCACHE_RSP
        // 关键: DCache响应没有ID，我们假设它对应于writebackPtrReg指向的请求。
        // 我们将writebackPtrReg的当前值捕获到流水线中。
        arbitrateStage(UPDATE_PTR_IN) := writebackPtrReg
        arbitrateStage(DCACHE_RSP_PAYLOAD) := setup.dCacheStorePort.rsp.payload
        if (enableLog)
          ParallaxSim.debug(L"[SQ StatusUpdate] Arbitrate selected DCache RSP update for writebackPtrReg: ${writebackPtrReg}")
      } elsewhen (externalTakesIt) {
        arbitrateStage(UPDATE_SOURCE) := UpdateSource.EXTERNAL_PORT
        arbitrateStage(UPDATE_PTR_IN) := setup.statusUpdatePortInst.payload.sqPtr
        arbitrateStage(PORT_PAYLOAD) := setup.statusUpdatePortInst.payload
        if (enableLog)
          ParallaxSim.debug(L"[SQ StatusUpdate] Arbitrate selected External Port update for SQPtr: ${setup.statusUpdatePortInst.payload.sqPtr}")
      }

      val readStage = pipe.READ_MEM
      val ptrToRead = readStage(UPDATE_PTR_IN)
      readStage(EXPECTED_SQ_PTR) := sqPtrs.readAsync(getPhysicalIndex(ptrToRead))
      if (enableLog)
        ParallaxSim.debug(L"[SQ StatusUpdate] Read stage - Ptr to read: ${ptrToRead}, Expected SQ Ptr: ${sqPtrs.readAsync(getPhysicalIndex(ptrToRead))}")


      val execStage = pipe.EXECUTE
      if (enableLog)
        ParallaxSim.debug(L"[SQ StatusUpdate] Execute stage firing: ${execStage.isFiring}")

      when(execStage.isFiring) {
        val updateSource = execStage(UPDATE_SOURCE)

        val finalSqPtr =
          (updateSource === UpdateSource.DCACHE_RSP) ? execStage(EXPECTED_SQ_PTR) | execStage(UPDATE_PTR_IN)
        val sqIdx = getPhysicalIndex(finalSqPtr)

        when(validsReg(sqIdx) && execStage(EXPECTED_SQ_PTR) === finalSqPtr) {
          if (enableLog)
            ParallaxSim.debug(L"[SQ StatusUpdate] Update for SQ Ptr ${finalSqPtr} (PhysIdx: ${sqIdx}): Type=${updateSource}. Update ACCEPTED.")

          val waitOn = waitOnsReg(sqIdx)

          switch(updateSource) {
            is(UpdateSource.AGU) {
              val aguOut = execStage(AGU_PAYLOAD)
              physicalAddresses.write(sqIdx, aguOut.address)
              storeMasks.write(
                sqIdx,
                AddressToMask(
                  aguOut.address,
                  MemAccessSize.toByteSize(aguOut.accessSize),
                  lsuConfig.dataWidth.value / 8
                )
              )
              waitOn.address := True
              waitOn.isStalledForAgu := False
              if (enableLog)
                ParallaxSim.debug(L"[SQ StatusUpdate] AGU update: Address=${aguOut.address}, Mask=${AddressToMask(aguOut.address, MemAccessSize.toByteSize(aguOut.accessSize), lsuConfig.dataWidth.value / 8)}. Marking address ready and AGU stall false.")
            }

            is(UpdateSource.DCACHE_RSP) {
              val rsp = execStage(DCACHE_RSP_PAYLOAD)
              waitOn.dCacheStoreRsp := False
              if (enableLog)
                ParallaxSim.debug(L"[SQ StatusUpdate] DCache RSP update received for Ptr ${finalSqPtr}. Fault: ${rsp.fault}, Redo: ${rsp.redo}.")

              when(rsp.fault) {
                if (enableLog) ParallaxSim.warning(L"[SQ StatusUpdate] DCache Store Fault for Ptr ${finalSqPtr}. Marking writeback complete.")
                writebackCompletesReg(sqIdx) := True
              } elsewhen (rsp.redo) {
                if (enableLog)
                  ParallaxSim.info(L"[SQ StatusUpdate] DCache Store Redo for Ptr ${finalSqPtr}, will be rescheduled. Clearing dCacheStoreRsp.")
              } otherwise {
                writebackCompletesReg(sqIdx) := True
                if (enableLog)
                  ParallaxSim.debug(L"[SQ StatusUpdate] DCache Store OK for Ptr ${finalSqPtr}, marking as complete.")
              }
            }

            is(UpdateSource.EXTERNAL_PORT) {
              val update = execStage(PORT_PAYLOAD)
              if (enableLog)
                ParallaxSim.debug(
                  L"[SQ StatusUpdate] External Port Update for SQ Ptr ${finalSqPtr}: Type=${update.updateType}, DataFromPrfPdr=${update.dataFromPrfPdr}"
                )

              switch(update.updateType) {
                dataToWrites.write(sqIdx, update.dataFromPrfPdr)
                is(SqUpdateType.DCACHE_REFILL_DONE) {
                  waitOn.dCacheRefill := waitOn.dCacheRefill & ~update.dCacheRefillSlotDsr
                  when(update.dCacheRefillAnyDsr) { waitOn.dCacheRefillAny := False }
                  if (enableLog)
                    ParallaxSim.debug(L"[SQ StatusUpdate] External update: DCache Refill Done. dCacheRefill: ${waitOn.dCacheRefill}, dCacheRefillAny: ${waitOn.dCacheRefillAny}")
                }
                default {
                  if (enableLog)
                    ParallaxSim.warning(L"[SQ StatusUpdate] External update: Unknown type ${update.updateType} for Ptr ${finalSqPtr}.")
                }
              }
            }
          }
        } otherwise {
          if (enableLog)
            ParallaxSim.debug(
              L"[SQ StatusUpdate] Update for SQ Ptr ${execStage(UPDATE_PTR_IN)} (Expected: ${execStage(EXPECTED_SQ_PTR)}): Update REJECTED (stale or invalid entry ${validsReg(sqIdx)})."
            )
        }
      }
      pipe.build()
    }
  }

  override def getAllocatePort(): Stream[StoreQueueEntry] = setup.allocatePortInst
  override def getStatusUpdatePort(): Flow[SqStatusUpdate] = setup.statusUpdatePortInst
}
