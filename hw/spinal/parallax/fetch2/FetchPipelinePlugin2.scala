/* 

软冲刷
    目标： 不破坏执行流程，仅将取指重定向到新的地址。这通常用于BPU预测分支跳转成功的情况。


硬冲刷
    目标： 清空整条流水线以及所有下游，从一个全新的PC开始。这通常用于分支预测错误、异常、
    中断等需要废弃所有当前工作的场景。

            +---------------------------+
            |        PC Generation      |
            |   (fetchPcReg, Redirects) |
            +---------------------------+
                        |
                        | RAW_PC (Unaligned PC for this cycle)
                        | PC (Line-aligned PC for ICache)
                        V
+-----------------------------------------------------------------+
|                       Fetch Pipeline (s1-s4)                    |
|                                                                 |
|   +-----------------------+      +-------------------------+    |
|   | s1: PC Gen            |----->| s2: ICache Access       |    |
|   | (Start Fetch Cycle)   |      | (Send Cmd to ICache)    |    |
|   +-----------------------+      +-------------------------+    |
|                                            |                    |
|                (ICache Latency: may have s3 for waiting)        |
|                                            |                    |
|   +-----------------------+      +-------------------------+    |
|   | s4: Predecode         |<-----| s3: ICache Wait (opt)   |    |
|   | (Receive Rsp, Decode) |      |                         |    |
|   +-----------------------+      +-------------------------+    |
|                                                                 |
+-----------------------------------------------------------------+
                        |
                        | FetchGroup (A bundle of instructions)
                        V
            +---------------------------+
            |      predecodedGroups     |  <- StreamFifo (Buffer)
            |      (FetchGroup FIFO)    |
            +---------------------------+
                        |
                        | FetchGroup
                        V
+-----------------------------------------------------------------+
|                                                                 |
|                       SmartDispatcher                           |
|                                                                 |
|   (Analyzes instructions one by one from the FetchGroup)        |
|                                                                 |
|   +--------------------+              +---------------------+   |
|   | Normal Instruction |              |  Branch Instruction |   |
|   +--------------------+              +---------------------+   |
|            |                                      |             |
|            |                                      | BPU Query   |
|            |                                      V             |
|            |                         +-----------------------+  |
|            |                         |      BPU Service      |  |
|            |                         +-----------------------+  |
|            |                                      |             |
|            |                                      | BPU Response|
|            |                                      V             |
|            +------------------+-------------------+             |
|                               |                                 |
|                               V                                 |
|                  (Combine with BPU Prediction)                  |
|                                                                 |
+-----------------------------------------------------------------+
                        |
                        | FetchedInstr (Single instruction with all info)
                        V
            +---------------------------+
            |        fetchOutput        |  <- StreamFifo (Output Buffer)
            |       (Output FIFO)       |
            +---------------------------+
                        |
                        | FetchedInstr
                        V
            +---------------------------+
            |      To Next Stage        |
            | (e.g., Decode/Rename)     |
            +---------------------------+

 */
package parallax.fetch2

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.fetch.{FetchedInstr, InstructionPredecoder}
import parallax.fetch.icache.{ICacheService, ICachePort, ICacheConfig, ICacheCmd, ICacheRsp} // 导入我们自己的ICache包

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxSim.notice
import parallax.utilities.ParallaxSim.log
import parallax.utilities.ParallaxSim.success
import parallax.utilities.ParallaxSim.warning

/**
 * 用于在 s4 和 s1 之间传递重试请求的命令。
 *
 * @param pCfg Pipeline配置
 */
case class RetryCommand(pCfg: PipelineConfig) extends Bundle {
  val lock = Bool() // 锁存位，为True表示有一个有效的重试请求
  val pc   = UInt(pCfg.pcWidth) // 需要重试的原始PC (RAW_PC)
  val id   = UInt(2 bits)
}

trait FetchService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchedInstr]
  def newHardRedirectPort(priority: Int): Flow[UInt]
  def newFetchDisablePort(): Bool
}

class FetchPipelinePlugin(
    pCfg: PipelineConfig,
    iCfg: ICacheConfig, // 增加对ICache配置的依赖
    fetchGroupFifoDepth: Int = 2,
    outputFifoDepth: Int = 4
) extends Plugin with FetchService with HardRedirectService {

    val enableLog = true
    val verbose = false
    val icacheLatency = 1

    val debug = new Area {
        val cycles = enableLog generate Reg(UInt(16 bits)) init(0)
        val c = enableLog generate Reg(UInt(3 bits)) init(0)
            enableLog generate {
            cycles := cycles + 1
            c := c + 1
            val alwaysLog = true
            if(enableLog) {
                if (alwaysLog) {
                    log(L"------------ End of cycle 0x$cycles")
                } else {
                    when(c === 0) {
                        log(L"------------ End of cycle 0x$cycles")
                    }
                }
            }
        }
    }

    // --- 服务接口实现 ---
    private val hardRedirectPorts = ArrayBuffer[(Int, Flow[UInt])]()
    private val doHardRedirect_listening = Bool()
    override def newHardRedirectPort(priority: Int): Flow[UInt] = {
        framework.requireEarly(); val port = Flow(UInt(pCfg.pcWidth)); hardRedirectPorts += (priority -> port); port
    }
    private val fetchDisablePorts = ArrayBuffer[Bool]()
    override def newFetchDisablePort(): Bool = {
        framework.requireEarly(); val port = Bool(); fetchDisablePorts += port; port
    }
    override def doHardRedirect(): Bool = doHardRedirect_listening
    override def fetchOutput(): Stream[FetchedInstr] = {
        framework.requireEarly()
        setup.fetchOutput.io.pop
    }

    def calculateStartIndex(rawPc: UInt, linePc: UInt): UInt = {
        val pcOffset = rawPc - linePc

        // --- 断言 ---
        assert(pcOffset(log2Up(pCfg.dataWidth.value / 8) - 1 downto 0) === 0, 
            "RAW_PC is not instruction-aligned within the cache line!")
        assert(pcOffset < iCfg.bytesPerLine, 
            "pcOffset exceeds cache line boundary!")

        // --- 计算 ---
        val byteOffsetInLine = pcOffset(log2Up(iCfg.bytesPerLine) - 1 downto 0)
        val wordOffsetInLine = byteOffsetInLine >> log2Up(pCfg.dataWidth.value / 8)
        
        wordOffsetInLine.resized
    }

    val setup = create early new Area {
        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; setup")
        val iCacheService = getService[ICacheService]
        val bpuService = getService[BpuService]
        val iCachePort = iCacheService.newICachePort()
        val iCacheInvalidatePort = iCacheService.invalidate
        val bpuQueryPort = bpuService.newBpuQueryPort()
        val bpuResponse = bpuService.getBpuResponse()
        val fetchOutput = StreamFifo(FetchedInstr(pCfg), depth = outputFifoDepth)

        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; retain")
        iCacheService.retain();
        bpuService.retain()
        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; retained")
    }

    val logic = create late new Area {
        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; before logic")
        lock.await()
        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; after logic")
        setup.iCacheInvalidatePort := False

        val retryIdCounter = Counter(start = 1, end = 3) // 0 for invalid
        val doRetryFlush = False
        val fetchPipeline = new Pipeline {
            // --- Stageables ---
            val RAW_PC = Stageable(UInt(pCfg.pcWidth)) // 原始、未对齐的PC
            val PC = Stageable(UInt(pCfg.pcWidth))     // 行对齐的PC

            // --- 流水线阶段定义 ---
            val s1_pcGen = newStage().setName("s1_PC_Gen")
            val s2_iCacheAccess = newStage().setName("s2_ICache_Access")
            val s3_iCacheWait = newStage().setName("s3_ICache_Wait")
            val s4_predecode = newStage().setName("s4_Predecode")

                if(enableLog && verbose) log(Seq(
                    L"IssuePipeline status: \n",
                    formatStage(s1_pcGen),
                    formatStage(s2_iCacheAccess),
                    formatStage(s3_iCacheWait),
                    formatStage(s4_predecode)
                ))

            private val seq = if (icacheLatency == 1) {
                Seq(s1_pcGen, s2_iCacheAccess, s4_predecode)
            } else if (icacheLatency == 2) {
                Seq(s1_pcGen, s2_iCacheAccess, s3_iCacheWait, s4_predecode)
            } else {
                assert(false, "Invalid icacheLatency value!")
                Seq()
            }

            for (i <- 0 until seq.length - 1) {
                connect(seq(i), seq(i + 1))(Connection.M2S())
            }

        }.setCompositeName(this, "FetchPipeline")

        import fetchPipeline._

        val fetchOutput = setup.fetchOutput
        val dispatcher = new SmartDispatcher(pCfg)

        // --- 重定向与排空逻辑 ---
        val softRedirect = dispatcher.io.softRedirect
        when(softRedirect.valid) {
            ParallaxSim.notice(L"[${debug.cycles}] !!!!GOT A SOFT REDIRECT to 0x${softRedirect.payload}!!!!")
        }
        val hardRedirect = Flow(UInt(pCfg.pcWidth))
        val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)
        if (sortedHardRedirects.nonEmpty) {
            val valids = sortedHardRedirects.map(_.valid)
            hardRedirect.valid   := valids.orR
            hardRedirect.payload := MuxOH(OHMasking.first(valids), sortedHardRedirects.map(_.payload))
        } else { hardRedirect.setIdle() }
        
        doHardRedirect_listening := hardRedirect.valid

        // NOTE: 不要删除这个注释。Soft Flush 是指不破坏指令序列地从新地址开始取指
        val doSoftFlush = softRedirect.valid 
        // NOTE: 不要删除这个注释。Hard Flush 是指清空任何已经取指的部分，包括下游，从取指流水线到提交阶段都全部清空
        val doHardFlush = hardRedirect.valid 
        val doAnyFlush = doSoftFlush || doHardFlush

        when(doHardFlush) {
            ParallaxSim.notice(L"[${debug.cycles}] !!!!GOT A HARD FLUSH to ${hardRedirect.payload}")
        }

        fetchOutput.io.flush := doHardFlush
        fetchOutput.io.push << dispatcher.io.fetchOutput

        when(fetchOutput.io.pop.fire) {
            ParallaxSim.debug(L"[${debug.cycles}] Dispatcher output a instr: PC=0x${fetchOutput.io.pop.payload.pc}, instr=0x${fetchOutput.io.pop.payload.instruction}")
        }

        val dispatchSoftFlushReg = RegNext(doSoftFlush) init(False)
        val dispatchHardFlushReg = RegNext(doHardFlush) init(False)
        val dispatchAnyFlushReg = RegNext(doAnyFlush) init(False)

        // iCacheInFlightCounter 用于计算需要排空的 ICache 请求数，以便消除幽灵响应
        val iCacheInFlightCounter = CounterUpDown(1 << log2Up(pCfg.fetchWidth + 1))
        val isDrainingCacheRspReg = Reg(Bool()) init(False)

        when(doAnyFlush) {
            isDrainingCacheRspReg := True
            notice(L"[${debug.cycles}] Draining the pipeline due to a flush. iCacheInFlightCounter.value=${iCacheInFlightCounter.value}")
        }
        when(isDrainingCacheRspReg) {
            when(iCacheInFlightCounter.value === 0) {
                isDrainingCacheRspReg := False
                success(L"[${debug.cycles}] Pipeline drained last cycle")
            }
            .otherwise {
                notice(L"[${debug.cycles}] Pipeline still has ${iCacheInFlightCounter.value} cycles to drain")
            }
        }
        
        // 用于S4 -> S1通信的重试命令寄存器
        val retryCmd = Reg(RetryCommand(pCfg)) init {
            val cmd = RetryCommand(pCfg)
            cmd.lock := False
            cmd.pc.assignDontCare()
            cmd.id.assignDontCare()
            cmd
        }

        val lineBytes       = iCfg.bytesPerLine
        val lineAlignBits   = log2Up(lineBytes)
        def alignToLine(pc: UInt): UInt = (pc(pCfg.pcWidth.value-1 downto lineAlignBits) ## U(0, lineAlignBits bits)).asUInt

        // --- s1: PC生成 ---
        val s1_logic = new Area {
            val s1 = fetchPipeline.s1_pcGen
            val fetchPcReg = Reg(UInt(pCfg.pcWidth)) init(pCfg.resetVector)
            
            // S1本地寄存器，用于记录上一次处理的重试ID，防止重复响应同一个重试请求
            val lastRetryIdReg = Reg(UInt(2 bits)) init(0)

            // 当有一个锁定的重试命令，并且其ID与我们上次处理的ID不同时，需要进行重做
            val needRedo = retryCmd.lock && retryCmd.id =/= lastRetryIdReg && 
                            !doAnyFlush && 
                            !dispatchAnyFlushReg && 
                            !isDrainingCacheRspReg



            // 1. 决定当前周期S1阶段要使用的PC (RAW_PC)
            //    最高优先级：来自S4的重试请求。
            //    否则：使用 fetchPc 寄存器的当前值。

            val rawPcToUse  = Mux(needRedo, retryCmd.pc , fetchPcReg)
            s1(RAW_PC)      := rawPcToUse
            s1(PC)          := alignToLine(rawPcToUse)
            
            // 2. 计算下一周期的PC (nextPc)
            //    这个计算只在 s1 发射时才有意义。
            //    下一行的PC，是基于当前 s1 正在发射的PC (s1(PC)) 计算的。
            val nextLinePc = fetchPcReg + lineBytes
            // 常规情况下的下一个PC（处理软重定向或顺序执行）
            val nextPcRegular = nextLinePc
            
            when(doHardFlush) {
                fetchPcReg := hardRedirect.payload
                retryCmd.lock := False // 硬冲刷取消任何挂起的重试
                lastRetryIdReg := 0    // 同时重置lastRetryId
                log(L"[${debug.cycles}] FETCH-S1: fetchPcReg reset to ${hardRedirect.payload} due to hard flush, retry cancelled.")
            }
            .elsewhen(doSoftFlush) {
                fetchPcReg := softRedirect.payload
                retryCmd.lock := False // 软重定向也应该取消挂起的重试，因为它改变了控制流
                lastRetryIdReg := 0
            }
            .elsewhen(needRedo) {
                // 我们正在重试 `retryCmd.pc`。这次重试成功后的下一次取指
                // 应该来自下一个缓存行。因此，我们现在就将 fetchPcReg 纠正到该值。
                // 这会覆盖掉投机性的、超前过度的值。
                val correctedNextPc = alignToLine(retryCmd.pc) + lineBytes
                fetchPcReg := correctedNextPc
                log(L"[${debug.cycles}] FETCH-S1: Correcting fetchPcReg for next cycle to 0x${correctedNextPc}")
            }
            .elsewhen(s1.isFiring && !retryCmd.lock) {
                fetchPcReg := nextPcRegular
                log(L"[${debug.cycles}] FETCH-S1: fetchPcReg updated to ${nextPcRegular} (from raw PC=0x${s1(RAW_PC)})")
            }

            // 当S1发射一个重试请求时，记录其ID，这样如果S1在下一周期停顿，就不会再次为同一个ID发射请求
            when(s1.isFiring && needRedo) {
                lastRetryIdReg := retryCmd.id
                notice(L"[${debug.cycles}] FETCH-S1: Acting on retry for ID ${retryCmd.id}, PC=0x${retryCmd.pc}. Locking this ID in s1.")
            }

            // 4. 流水线控制
            val fetchDisabled = fetchDisablePorts.orR
            s1.valid := True
            when(isDrainingCacheRspReg 
                || fetchDisabled 
                // || (iCacheInFlightCounter.value =/= 0) 这个逻辑删了也能测试通过，吞吐量略微上升
                || (retryCmd.lock && retryCmd.id === lastRetryIdReg) // 如果刚刚发送了重试，则也需要暂停
            )
            {
                s1.haltIt()
                log(L"[${debug.cycles}] FETCH-S1: Halting due to isDrainingCacheRspReg=${isDrainingCacheRspReg}, " :+ 
                L"fetchDisabled=${fetchDisabled}, iCacheInFlightCounter.value=${iCacheInFlightCounter.value}" :+
                L"retryCmd.lock=${retryCmd.lock} retryCmd.id=${retryCmd.id} === lastRetryIdReg=${lastRetryIdReg}")
            }

            if(enableLog && verbose) when(s1.isFiring) {
                log(L"[${debug.cycles}] FETCH-S1: Firing Aligned PC=0x${s1(PC)} (from raw PC=0x${s1(RAW_PC)})")
            }
        }

        // --- s2: I-Cache 访问 ---
        val s2_logic = new Area {
            val s2 = fetchPipeline.s2_iCacheAccess
            val iCachePort = setup.iCachePort

            iCachePort.cmd.valid := s2.isFiring && !isDrainingCacheRspReg
            when(iCachePort.cmd.valid) {
                if(enableLog && verbose) notice(L"[${debug.cycles}] FETCH-S2: Sending ICache request for PC=0x${s2(PC)}")
            }
            val cmdPayload = ICacheCmd(pCfg.pcWidth.value)
            cmdPayload.address := s2(PC)
            cmdPayload.transactionId := (s2(PC) >> 4).resized
            iCachePort.cmd.payload := cmdPayload

            when(iCachePort.cmd.fire) { iCacheInFlightCounter.increment() }
            if(enableLog && verbose) when(s2.isFiring) { log(L"[${debug.cycles}] FETCH-S2: ICache request sent for PC=0x${s2(PC)}") }
        }

        // --- s3: I-Cache 等待 ---
        val s3_logic = new Area {
            val s3 = fetchPipeline.s3_iCacheWait
            if(enableLog && verbose && icacheLatency == 2) when(s3.isFiring) { log(L"[${debug.cycles}] FETCH-S3: Waiting for ICache Rsp for PC=0x${s3(PC)}") }
        }

        // --- s4: I-Cache响应接收与并行预解码 ---
        val s4_logic = new Area {
            val s4 = fetchPipeline.s4_predecode
            val iCacheRsp = setup.iCachePort.rsp
            // log(L"[${debug.cycles}] iCacheRsp: ${iCacheRsp.format}")
            
            when(iCacheRsp.fire) {
                iCacheInFlightCounter.decrement() 
            }
            
            val predecodedGroups = StreamFifo(FetchGroup(pCfg), depth = fetchGroupFifoDepth)
            predecodedGroups.io.push.setIdle()

            val predecoders = Seq.fill(pCfg.fetchWidth)(new InstructionPredecoder(pCfg))
            predecoders.foreach(_.io.instruction.assignDontCare())

            val handleRsp = s4.isFiring && iCacheRsp.valid && !isDrainingCacheRspReg
            if (enableLog) {
                log(L"[${debug.cycles}] FETCH-S4: Handling Rsp for PC=0x${s4(PC)}? ${handleRsp} because" :+
                    L" isFiring=${s4.isFiring}, iCacheRsp.valid=${iCacheRsp.valid}, isDrainingCacheRspReg=${isDrainingCacheRspReg}")
            }
            val hasHigherPriorityStuff = doAnyFlush || dispatchAnyFlushReg || isDrainingCacheRspReg
            // ICache响应到达，但下游已满，无法处理 -> 触发重试！
            val backpressureRedo = !s4.isFiring && iCacheRsp.valid && iCacheRsp.payload.wasHit && !isDrainingCacheRspReg && !predecodedGroups.io.push.ready &&
                                    !hasHigherPriorityStuff
            when(backpressureRedo) {
                when(!retryCmd.lock) { // 仅当没有挂起的重试时才设置新的重试
                    retryCmd.lock := True
                    retryCmd.pc   := s4(RAW_PC) 
                    retryCmd.id   := retryIdCounter.value
                    retryIdCounter.increment()
                    doRetryFlush := True
                    if(enableLog) notice(L"[FETCH-S4] BACKPRESSURE REDO requested for raw PC=0x${s4(RAW_PC)}, ID=${iCacheRsp.payload.transactionId} because downstream FIFO is full, retryCmd.id=${retryIdCounter.value}.")
                }
            }
            
            when(handleRsp) { // 只有收到有效数据且有容量处理才执行下面的逻辑
                if(enableLog && verbose) log(L"[${debug.cycles}] FETCH-S4: Rsp received for PC=0x${s4(PC)}. Redo=${iCacheRsp.payload.redo}, WasHit=${iCacheRsp.payload.wasHit}, TID=${iCacheRsp.payload.transactionId}")
                assert(iCacheRsp.payload.transactionId === (s4(PC) >> 4).resized, L"ICache response TID mismatch! Expect ${(s4(PC) >> 4).resized}, got ${iCacheRsp.payload.transactionId}") 

                when(iCacheRsp.payload.redo) {
                    val canRescheduleRetry = (!retryCmd.lock || (retryCmd.lock && alignToLine(s4(PC)) === alignToLine(retryCmd.pc))) && !hasHigherPriorityStuff
                    when(canRescheduleRetry) {
                        retryCmd.lock := True
                        retryCmd.pc   := s4(RAW_PC)
                        retryCmd.id   := retryIdCounter.value
                        retryIdCounter.increment()
                        doRetryFlush := True
                        if(enableLog) notice(L"[${debug.cycles}] FETCH-S4: Requesting retry for raw PC=0x${s4(RAW_PC)}, ID=${iCacheRsp.payload.transactionId}, retryCmd.id=${retryIdCounter.value}")
                    } .otherwise {
                        if(enableLog) {
                            when(retryCmd.lock && alignToLine(s4(PC)) =/= alignToLine(retryCmd.pc)) {
                                warning(L"[${debug.cycles}] FETCH-S4: ICache requests retry for ${s4(PC)}, but another retry for ${retryCmd.pc} is already locked. Stalling.")
                                assert(False)
                            }
                        }
                    }
                } .otherwise {
                    if(enableLog) success(L"[${debug.cycles}] FETCH-S4: ICache hit for PC=0x${s4(PC)}")
                    // 如果此成功响应对应于挂起的重试请求，则清除重试锁
                    when(retryCmd.lock && alignToLine(s4(PC)) === alignToLine(retryCmd.pc)) {
                        retryCmd.lock := False
                        if(enableLog) success(L"[${debug.cycles}] FETCH-S4: Retry for ID ${retryCmd.id} completed. Clearing lock.")
                    }
                    // 仅当没有挂起的重试，或者此响应就是针对该重试的成功响应时，才处理数据
                    when(!retryCmd.lock || alignToLine(s4(PC)) === alignToLine(retryCmd.pc)) {
                        val predecodedGroup = FetchGroup(pCfg)
                        val instructionSlots = iCacheRsp.payload.instructions
                        val startInstructionIndex = calculateStartIndex(s4(RAW_PC), s4(PC))
                        predecodedGroup.startInstructionIndex := startInstructionIndex
                        predecodedGroup.pc := s4(PC)
                        predecodedGroup.fault := !iCacheRsp.payload.wasHit

                        assert(instructionSlots.length == pCfg.fetchWidth, s"Expected ${pCfg.fetchWidth} instruction slots, got ${instructionSlots.length}")
                        assert(predecodedGroup.predecodeInfos.length == pCfg.fetchWidth, s"Expected ${pCfg.fetchWidth} predecoded infos, got ${predecodedGroup.predecodeInfos.length}")
                        
                        predecodedGroup.instructions := Vec(instructionSlots)
                        
                        for (i <- 0 until pCfg.fetchWidth) {
                            predecoders(i).io.instruction := instructionSlots(i)
                            predecodedGroup.predecodeInfos(i) := predecoders(i).io.predecodeInfo
                        }
                        predecodedGroup.branchMask := B(predecodedGroup.predecodeInfos.map(_.isBranch).reverse)

                        predecodedGroup.numValidInstructions := U(pCfg.fetchWidth) - startInstructionIndex
                        
                        predecodedGroups.io.push.valid   := True
                        predecodedGroups.io.push.payload := predecodedGroup
                        
                        s4.haltWhen(!predecodedGroups.io.push.ready)
                        if(enableLog) when(predecodedGroups.io.push.fire) { 
                            log(L"FETCH-S4: Predecoded and pushing to FIFO. PC=0x${s4(PC)}, startIndex=${predecodedGroup.startInstructionIndex}, numValid=${predecodedGroup.numValidInstructions}") 
                        }
                    }

                }
            }
        }

        // --- 连接到智能分发器 ---
        dispatcher.io.fetchGroupIn << s4_logic.predecodedGroups.io.pop
        dispatcher.io.bpuRsp << setup.bpuResponse
        setup.bpuQueryPort << dispatcher.io.bpuQuery

        if(enableLog) when(dispatcher.io.fetchGroupIn.fire) {
            val group = dispatcher.io.fetchGroupIn.payload
            val firstIsBranch = group.predecodeInfos(group.startInstructionIndex).isBranch
            notice(L"[DISPATCHER-IN] POP from predecodedGroups FIFO. PC=0x${group.pc}, startIdx=${group.startInstructionIndex}, numValid=${group.numValidInstructions}, firstIsBranch=${firstIsBranch}")
        }

        // --- 流水线冲刷 ---
        fetchPipeline.s1_pcGen.flushIt(doHardFlush)
        fetchPipeline.s2_iCacheAccess.flushIt(doHardFlush || doRetryFlush)
        fetchPipeline.s3_iCacheWait.flushIt(doHardFlush || doRetryFlush)
        fetchPipeline.s4_predecode.flushIt(doHardFlush)

        /* 
        软冲刷时：
            当dispatcher发出软重定向后，它已经消费了包含分支指令的那个取指包。
            但此时，predecodedGroups FIFO里可能还缓存着来自错误路径（即分支不跳转的顺序路径）的指令包。
            在下一个周期 (RegNext)，当s1开始从新地址取指时，这个flush信号会清空这些陈旧的、
            不再需要的指令包。
         */
        s4_logic.predecodedGroups.io.flush := dispatchAnyFlushReg || isDrainingCacheRspReg
        dispatcher.io.flush := dispatchAnyFlushReg // 硬冲刷才需要清空下游
        when(s4_logic.predecodedGroups.io.flush) {
            notice(L"[${debug.cycles}] Flush predecodedGroups")
        }
        when(dispatcher.io.flush) {
            ParallaxSim.notice(L"[${debug.cycles}] Flush dispatcher")
        }
        if(enableLog) when(dispatchAnyFlushReg) { // Use the registered version as it aligns with the action
            val isHard = dispatchHardFlushReg
            val isSoft = dispatchSoftFlushReg
            notice(L"[FLUSH LOGIC] dispatchAnyFlushReg is ACTIVE (Cause: isHard=${isHard}, isSoft=${isSoft}. Flushing predecodedGroups and Dispatcher.")
        }
        fetchPipeline.build()
        
        // --- 资源释放 ---
        setup.iCacheService.release()
        setup.bpuService.release()
    }
}
