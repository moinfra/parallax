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
import parallax.fetch.{FetchedInstr, InstructionPredecoder, PredecodeInfo} // 导入PredecodeInfo以支持新的PredecodedFetchGroup
import parallax.fetch.icache.{ICacheService, ICachePort, ICacheConfig, ICacheCmd, ICacheRsp} // 导入我们自己的ICache包

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxSim._

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
    predecodedFifoDepth: Int = 2, // 新增：s4 -> s5 之间直传，但 s5 -> dispatcher 依然需要FIFO
    fetchGroupFifoDepth: Int = 2, // 保持：s5 -> dispatcher 的 FIFO 深度
    outputFifoDepth: Int = 4
) extends Plugin with FetchService with HardRedirectService with SoftRedirectService {

    val enableLog = true
    val verbose = true
    val icacheLatency = 1

    val dbg = new Area {
        val cycles = true generate { Reg(UInt(16 bits)) init(0) }
        val c = true generate { Reg(UInt(3 bits)) init(0) }
        true generate {
            cycles := cycles + 1
            c := c + 1
            val alwaysLog = true
            if (alwaysLog) {
                if(enableLog) log(L"------------ End of cycle 0x$cycles")
            } else {
                when(c === 0) {
                    if(enableLog) log(L"------------ End of cycle 0x$cycles")
                }
            }
        }
    }

    // --- 服务接口实现 ---
    private val hardRedirectPorts = ArrayBuffer[(Int, Flow[UInt])]()
    private val doHardRedirect_listening = Bool()
    private val doSoftRedirect_listening = Bool()
    override def newHardRedirectPort(priority: Int): Flow[UInt] = {
        framework.requireEarly(); val port = Flow(UInt(pCfg.pcWidth)); hardRedirectPorts += (priority -> port); port
    }
    private val fetchDisablePorts = ArrayBuffer[Bool]()
    override def newFetchDisablePort(): Bool = {
        framework.requireEarly(); val port = Bool(); fetchDisablePorts += port; port
    }
    override def doHardRedirect(): Bool = doHardRedirect_listening
    override def doSoftRedirect(): Bool = doSoftRedirect_listening
    var called = false
    override def fetchOutput(): Stream[FetchedInstr] = {
        require(!called, "fetchOutput() can only be called once!")
        called = true
        framework.requireEarly()
        setup.relayedFetchOutput
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
    val doRetryFlush = False

    val setup = create early new Area {
        ParallaxLogger.debug("Plugin: FetchPipelinePlugin; setup")
        val iCacheService = getService[ICacheService]
        val bpuService = getService[BpuService]
        val iCachePort = iCacheService.newICachePort()
        val iCacheInvalidatePort = iCacheService.invalidate
        val bpuQueryPort = bpuService.newBpuQueryPort()
        val bpuResponse = bpuService.getBpuResponse()
        
        // NOTE: 这个FIFO存储的是已经被Dispatcher完全处理并准备送往解码阶段的单条指令。
        // 软冲刷时，这些指令是“已经发射”的，不应被丢弃。
        val fetchOutputRawBuffer = StreamFifo(FetchedInstr(pCfg), depth = outputFifoDepth)
        val relayedFetchOutput = fetchOutputRawBuffer.io.pop.throwWhen(doHardRedirect_listening || doRetryFlush)
        when(doHardRedirect_listening) {
            assert(!relayedFetchOutput.valid)
        }

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
        val fetchPipeline = new Pipeline {
            // --- Stageables ---
            val RAW_PC = Stageable(UInt(pCfg.pcWidth)) // 原始、未对齐的PC
            val PC = Stageable(UInt(pCfg.pcWidth))     // 行对齐的PC
            
            // 新增 Stageable: 用于 s4 传递到 s5 的原始 ICacheRsp Payload
            // 这样 s5 可以直接访问原始指令和 ICache 响应的其他信息
            val ICACHE_RSP_PAYLOAD = Stageable(ICacheRsp(iCfg))
            // 新增 Stageable: 用于 s4 传递到 s5 的预解码信息
            val PREDECODE_INFOS = Stageable(Vec(PredecodeInfo(), pCfg.fetchWidth))
            // 新增 Stageable: 用于 s4 传递到 s5 的起始指令索引
            val START_INSTR_INDEX = Stageable(UInt(log2Up(pCfg.fetchWidth) bits))
            // 新增 Stageable: 用于 s4 传递到 s5 的有效指令数量
            val NUM_VALID_INSTRS = Stageable(UInt(log2Up(pCfg.fetchWidth + 1) bits))
            // 新增 Stageable: 用于 s4 传递到 s5 的 fault 标志
            val FAULT = Stageable(Bool())


            // --- 流水线阶段定义 ---
            val s1_pcGen = newStage().setName("s1_PC_Gen")
            val s2_iCacheAccess = newStage().setName("s2_ICache_Access")
            val s3_iCacheWait = newStage().setName("s3_ICache_Wait")
            val s4_predecode = newStage().setName("s4_Predecode")
            val s5_targetCalc = newStage().setName("s5_Target_Calc") // <<< 新增阶段

                if(enableLog && verbose) log(Seq(
                    L"IssuePipeline status: \n",
                    formatStage(s1_pcGen),
                    formatStage(s2_iCacheAccess),
                    formatStage(s3_iCacheWait),
                    formatStage(s4_predecode),
                    formatStage(s5_targetCalc) // <<< 新增日志
                ))

            private val seq = if (icacheLatency == 1) {
                // 1周期延迟 ICache: s1 -> s2 -> s4 -> s5
                Seq(s1_pcGen, s2_iCacheAccess, s4_predecode, s5_targetCalc)
            } else if (icacheLatency == 2) {
                // 2周期延迟 ICache: s1 -> s2 -> s3 -> s4 -> s5
                Seq(s1_pcGen, s2_iCacheAccess, s3_iCacheWait, s4_predecode, s5_targetCalc)
            } else {
                assert(false, "Invalid icacheLatency value!")
                Seq()
            }

            // 连接流水线阶段：M2S 连接支持直传
            for (i <- 0 until seq.length - 1) {
                if(seq(i) == s4_predecode) { // 如果当前阶段是 s4
                    connect(seq(i), seq(i + 1))(Connection.S2M())
                    // if(enableLog) notice(L"[${dbg.cycles}]Connecting s4 -> s5 with S2M buffer.")
                } else { // 其他阶段保持 M2S 连接
                    connect(seq(i), seq(i + 1))(Connection.M2S())
                }
            }

        }.setCompositeName(this, "FetchPipeline")

        import fetchPipeline._
        val fetchDisabled = fetchDisablePorts.orR
        val fetchOutputRawBuffer = setup.fetchOutputRawBuffer
        // SmartDispatcher现在接收FetchGroup (最终类型)
        val dispatcher = new SmartDispatcher(pCfg)

        // 输出 FIFO，给 Dispatcher
        val fetchGroupsOut = StreamFifo(FetchGroup(pCfg), depth = fetchGroupFifoDepth)
        fetchGroupsOut.io.push.setIdle()
        // --- 重定向与排空逻辑 ---
        val softRedirect = dispatcher.io.softRedirect
        when(softRedirect.valid) {
            if(enableLog) notice(L"[${dbg.cycles}] !!!!GOT A SOFT REDIRECT to 0x${softRedirect.payload}!!!!")
        }
        val hardRedirect = Flow(UInt(pCfg.pcWidth))
        val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)
        if (sortedHardRedirects.nonEmpty) {
            val valids = sortedHardRedirects.map(_.valid)
            hardRedirect.valid   := valids.orR
            hardRedirect.payload := MuxOH(OHMasking.first(valids), sortedHardRedirects.map(_.payload))
        } else { hardRedirect.setIdle() }
        
        doHardRedirect_listening := hardRedirect.valid
        doSoftRedirect_listening := softRedirect.valid

        val doSoftFlush = softRedirect.valid 
        val doHardFlush = hardRedirect.valid 
        val doExternalFlush = doSoftFlush || doHardFlush

        when(doHardFlush) {
            notice(L"[${dbg.cycles}] !!!!GOT A HARD FLUSH to ${hardRedirect.payload}")
        }
        when(doSoftFlush) {
            notice(L"[${dbg.cycles}] !!!!GOT A SOFT FLUSH to ${softRedirect.payload}")
        }
        fetchOutputRawBuffer.io.flush := doHardFlush
        fetchOutputRawBuffer.io.push.valid := dispatcher.io.fetchOutput.valid && !doHardFlush
        dispatcher.io.fetchOutput.ready := fetchOutputRawBuffer.io.push.ready
        fetchOutputRawBuffer.io.push.payload := dispatcher.io.fetchOutput.payload
        
when(doHardFlush) {
    if(enableLog) notice(L"[${dbg.cycles}]FLUSH_LOGIC] doHardFlush is HIGH at cycle 0x${dbg.cycles}!")
}


// 监控最终送给FIFO的flush信号
when(fetchOutputRawBuffer.io.flush) {
    if(enableLog) notice(L"[${dbg.cycles}]FLUSH_TO_FIFO] fetchOutput.io.flush is DRIVEN HIGH at cycle 0x${dbg.cycles}!")
}
        when(setup.relayedFetchOutput.fire) {
            notice(L"[${dbg.cycles}] output a instr to decoder: PC=0x${fetchOutputRawBuffer.io.pop.payload.pc}, instr=0x${fetchOutputRawBuffer.io.pop.payload.instruction}")
        }

        val iCacheInFlightToDrainCounter = CounterUpDown(1 << log2Up(pCfg.fetchWidth + 1))
        val isDrainingCacheRspReg = Reg(Bool()) init(False)

        when(doExternalFlush && iCacheInFlightToDrainCounter.value > 0) {
            isDrainingCacheRspReg := True
            if(enableLog) notice(L"[${dbg.cycles}] Draining the pipeline due to a flush. iCacheInFlightToDrainCounter.value=${iCacheInFlightToDrainCounter.value}")
        }
        when(isDrainingCacheRspReg) {
            when(iCacheInFlightToDrainCounter.value === 0) {
                isDrainingCacheRspReg := False
                success(L"[${dbg.cycles}] Pipeline still has ${iCacheInFlightToDrainCounter.value} cycles to drain")
            }
            .otherwise {
                if(enableLog) notice(L"[${dbg.cycles}] Pipeline still has ${iCacheInFlightToDrainCounter.value} cycles to drain")
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
                            !doExternalFlush && 
                            !isDrainingCacheRspReg &&
                            !fetchDisabled

            val rawPcToUse = Mux(doHardFlush, hardRedirect.payload, Mux(needRedo, retryCmd.pc , fetchPcReg))
            s1(RAW_PC)      := rawPcToUse
            s1(PC)          := alignToLine(rawPcToUse)
            
            /* fetchPcReg的角色是：为了让指令流连续，下次应该取指令的起始地址。
                如果是重定向，那么下次应该从精确地址开始。
                如果是常规更新，那么下次应该从下一行开头开始（而非中间） */
            val nextLinePc = alignToLine(fetchPcReg) + lineBytes
            val nextPcRegular = nextLinePc
            
            when(doHardFlush) {
                fetchPcReg := hardRedirect.payload
                retryCmd.lock := False
                lastRetryIdReg := 0
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: fetchPcReg reset to ${hardRedirect.payload} due to hard flush, retry cancelled.")
            }
            .elsewhen(doSoftFlush) {
                fetchPcReg := softRedirect.payload
                retryCmd.lock := False
                lastRetryIdReg := 0
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: fetchPcReg reset to ${softRedirect.payload} due to soft flush, retry cancelled.")
            }
            .elsewhen(needRedo) {
                val correctedNextPc = alignToLine(retryCmd.pc) + lineBytes
                fetchPcReg := correctedNextPc
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: Correcting fetchPcReg to next line (because retryCmd.pc=0x${retryCmd.pc}, next cache line is at 0x${correctedNextPc})")
            }
            .elsewhen(s1.isFiring && !retryCmd.lock && !doExternalFlush) {
                fetchPcReg := nextPcRegular
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: fetchPcReg updated to ${nextPcRegular} (regular + 4*4, from raw PC=0x${s1(RAW_PC)})")
            }

            when(s1.isFiring && needRedo) {
                lastRetryIdReg := retryCmd.id
                if(enableLog) notice(L"[${dbg.cycles}] FETCH-S1: Got a retry command. Acting on retry for ID ${retryCmd.id}, PC=0x${retryCmd.pc}. Locking this ID in s1.")
            }

            s1.valid := True
            // 避免 S1 连续重试两次，S1 已经处理了该重试请求，它必须继续 halt，否则 retry 条件依然满足，导致产生两个在途的 retry 请求。
            // 当连续命中时，会产生两组完全相同的 fetch group
            when(isDrainingCacheRspReg 
                || fetchDisabled 
                || (retryCmd.lock && retryCmd.id === lastRetryIdReg) //  
            )
            {
                s1.haltIt()
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: Halting due to isDrainingCacheRspReg=${isDrainingCacheRspReg}, " :+ 
                L"fetchDisabled=${fetchDisabled}, iCacheInFlightToDrainCounter.value=${iCacheInFlightToDrainCounter.value}" :+
                L"retryCmd.lock=${retryCmd.lock} retryCmd.id=${retryCmd.id} === lastRetryIdReg=${lastRetryIdReg}")
            }

            if(enableLog && verbose) when(s1.isFiring) {
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: Firing Aligned PC=0x${s1(PC)} (from raw PC=0x${s1(RAW_PC)})")
            } otherwise {
                if(enableLog) log(L"[${dbg.cycles}] FETCH-S1: Idle. PC=0x${s1(PC)}")
            }
        }

        // --- s2: I-Cache 访问 ---
        val s2_logic = new Area {
            val s2 = fetchPipeline.s2_iCacheAccess
            val iCachePort = setup.iCachePort

            iCachePort.cmd.valid := s2.isFiring && !isDrainingCacheRspReg && !doExternalFlush
            when(iCachePort.cmd.valid) {
                if(enableLog && verbose) notice(L"[${dbg.cycles}] FETCH-S2: Sending ICache request for PC=0x${s2(PC)}")
            }
            val cmdPayload = ICacheCmd(pCfg.pcWidth.value)
            cmdPayload.address := s2(PC)
            cmdPayload.transactionId := (s2(PC) >> 4).resized
            iCachePort.cmd.payload := cmdPayload

            when(iCachePort.cmd.fire) { iCacheInFlightToDrainCounter.increment() }
            if(enableLog && verbose) when(s2.isFiring) { log(L"[${dbg.cycles}] FETCH-S2: ICache request sent for PC=0x${s2(PC)}") }
        }

        // --- s3: I-Cache 等待 ---
        val s3_logic = new Area {
            val s3 = fetchPipeline.s3_iCacheWait
            if(enableLog && verbose && icacheLatency == 2) when(s3.isFiring) { log(L"[${dbg.cycles}] FETCH-S3: Waiting for ICache Rsp for PC=0x${s3(PC)}") }
        }
        val s4_logic = new Area {
            val s4 = fetchPipeline.s4_predecode
            val iCacheRsp = setup.iCachePort.rsp

            s4(ICACHE_RSP_PAYLOAD) := ICacheRsp(iCfg).getZero
            s4(PREDECODE_INFOS).foreach(_.setDefault())
            s4(START_INSTR_INDEX)  .assignDontCare()
            s4(NUM_VALID_INSTRS)   .assignDontCare()
            s4(FAULT)              := True

            // s4.valid 决定了数据是否推进到 s5
            when(s4.isFiring) {
                if(enableLog && verbose) log(L"[${dbg.cycles}] FETCH-S4: Firing read cache rsp and predecode stage (s4) for PC=0x${s4(PC)}")
            }
            when(iCacheRsp.fire) {
                iCacheInFlightToDrainCounter.decrement() 
            }
            
            val handleRsp = s4.isValid  && iCacheRsp.valid && !isDrainingCacheRspReg && !doExternalFlush && s5_targetCalc.isReady
            if (enableLog) {
               log(L"[${dbg.cycles}] FETCH-S4: Handling Rsp for PC=0x${s4(PC)}? ${handleRsp} because" :+
                    L" isReady=${s4.isReady}, isValid=${s4.isValid}, iCacheRsp.valid=${iCacheRsp.valid}, isDrainingCacheRspReg=${isDrainingCacheRspReg} anyFlush=${doExternalFlush} s5Ready=${s5_targetCalc.isReady}")
            }
            val hasHigherPriorityStuff = doExternalFlush || isDrainingCacheRspReg

            // Backpressure: 如果S5阶段无法接收数据，则S4也必须暂停
            // s4.haltWhen(!s5_targetCalc.isReady) // <<< 关键修改：直接根据S5的ready信号暂停S4

            // ICache响应到达，但下游已满，无法处理 -> 触发重试！
            // 现在这个判断要看s5_targetCalc.isReady，而不是某个FIFO的ready
            // val backpressureRedo = !s4.isFiring && !retryCmd.lock && s5_targetCalc.isReady && !fetchGroupsOut.io.push.ready && !fetchGroupsOut.io.pop.ready && iCacheRsp.valid && iCacheRsp.payload.wasHit && !isDrainingCacheRspReg && !hasHigherPriorityStuff
            val cacheHit = iCacheRsp.valid && iCacheRsp.payload.wasHit
            val backpressureRedo = !s5_targetCalc.isReady && cacheHit
                                    !hasHigherPriorityStuff && !retryCmd.lock // 仅当没有挂起的重试时才设置新的重试

            when(backpressureRedo) {
                retryCmd.lock := True
                retryCmd.pc   := s4(RAW_PC) 
                retryCmd.id   := retryIdCounter.value
                retryIdCounter.increment()
                doRetryFlush := True
                if(enableLog) notice(L"[${dbg.cycles}]FETCH-S4] BACKPRESSURE REDO requested for raw PC=0x${s4(RAW_PC)}, ID=${iCacheRsp.payload.transactionId} because downstream S5 is not ready, retryCmd.id=${retryIdCounter.value}.")
            }
            
            when(handleRsp) { // 只有收到有效数据且有容量处理才执行下面的逻辑
                if(enableLog && verbose) log(L"[${dbg.cycles}] FETCH-S4: Rsp received for PC=0x${s4(PC)}. Redo=${iCacheRsp.payload.redo}, WasHit=${iCacheRsp.payload.wasHit}, TID=${iCacheRsp.payload.transactionId}")
                assert(iCacheRsp.payload.transactionId === (s4(PC) >> 4).resized, L"ICache response TID mismatch! Expect ${(s4(PC) >> 4).resized}, got ${iCacheRsp.payload.transactionId}") 

                when(iCacheRsp.payload.redo) {
                    val canRescheduleRetry = (!retryCmd.lock || (retryCmd.lock && alignToLine(s4(PC)) === alignToLine(retryCmd.pc))) && !hasHigherPriorityStuff
                    when(canRescheduleRetry) {
                        retryCmd.lock := True
                        retryCmd.pc   := s4(RAW_PC)
                        retryCmd.id   := retryIdCounter.value
                        retryIdCounter.increment()
                        doRetryFlush := True
                        if(enableLog) notice(L"[${dbg.cycles}] FETCH-S4: CACHE MISS REDO requested for raw PC=0x${s4(RAW_PC)}, ID=${iCacheRsp.payload.transactionId}, retryCmd.id=${retryIdCounter.value}.")
                    } .otherwise {
                        if(enableLog) {
                            when(retryCmd.lock && alignToLine(s4(PC)) =/= alignToLine(retryCmd.pc)) {
                                warning(L"[${dbg.cycles}] FETCH-S4: ICache requests retry for ${s4(PC)}, but another retry for ${retryCmd.pc} is already locked. Stalling.")
                                assert(False)
                            }
                        }
                    }
                } .otherwise {
                    if(enableLog) success(L"[${dbg.cycles}] FETCH-S4: ICache hit for PC=0x${s4(PC)}")
                    // 如果此成功响应对应于挂起的重试请求，则清除重试锁
                    when(retryCmd.lock && alignToLine(s4(PC)) === alignToLine(retryCmd.pc)) {
                        retryCmd.lock := False
                        if(enableLog) success(L"[${dbg.cycles}] FETCH-S4: Retry for ID ${retryCmd.id} completed. Clearing lock.")
                    }
                    // 仅当没有挂起的重试，或者此响应就是针对该重试的成功响应时，才处理数据
                    when(!retryCmd.lock || alignToLine(s4(PC)) === alignToLine(retryCmd.pc)) {
                        // 1. 并行预解码
                        val decodedInfos = Vec.tabulate(pCfg.fetchWidth) { i =>
                            val predecoder = new InstructionPredecoder(pCfg)
                            predecoder.io.instruction := iCacheRsp.payload.instructions(i)
                            predecoder.io.predecodeInfo
                        }

                        // 2. 传递 Stageables 给 S5
                        assert(s5_targetCalc.isReady)
                        s4(ICACHE_RSP_PAYLOAD) := iCacheRsp.payload // 传递原始响应，s5需要其中的指令
                        s4(PREDECODE_INFOS)    := decodedInfos      // 传递预解码结果
                        s4(START_INSTR_INDEX)  := calculateStartIndex(s4(RAW_PC), s4(PC))
                        s4(NUM_VALID_INSTRS)   := U(pCfg.fetchWidth) - s4(START_INSTR_INDEX)
                        s4(FAULT)              := !iCacheRsp.payload.wasHit
                        
                        // s4 的 valid 信号由 Pipeline 自动管理，这里只需确保当满足条件时，s4 不被 halt
                        // 如果s5 not ready，s4 会被 s4.haltWhen(!s5_targetCalc.isReady) 暂停
                        if(enableLog) {
                            log(L"[${dbg.cycles}]FETCH-S4: Set stageables predecoded data. is firing=${s4.isFiring}. PC=0x${s4(PC)}, startIndex=${s4(START_INSTR_INDEX)}, numValid=${s4(NUM_VALID_INSTRS)}")
                        }
                    }
                }
            }
            // S4 不再有内部 FIFO 需要冲刷
            // s4_logic.rawFetchGroups.io.flush := doAnyFlush || isDrainingCacheRspReg (已移除)
        }

        // --- s5: 跳转目标计算阶段 (全新) ---
        val s5_logic = new Area {
            val s5 = fetchPipeline.s5_targetCalc            
            when(fetchGroupsOut.io.push.fire) {
                val group = fetchGroupsOut.io.push.payload
                if(enableLog) success(L"[${dbg.cycles}]FETCH-S5 PUSH to fetchGroupsOut: " :+
                    L"PC=0x${group.pc}, firstPC=0x${group.firstPc}, startIdx=${group.startInstructionIndex}, numValid=${group.numValidInstructions}")
            }
            when(fetchGroupsOut.io.flush) {
                if(enableLog) warning(L"[${dbg.cycles}]FLUSH_MONITOR] FLUSHING fetchGroupsOut FIFO!")
            }
            when(fetchOutputRawBuffer.io.flush) {
                if(enableLog) warning(L"[${dbg.cycles}]FLUSH_MONITOR] FLUSHING fetchOutput FIFO!")
            }
            when(dispatcher.io.fetchOutput.fire) {
             val instr = dispatcher.io.fetchOutput.payload
             if(enableLog) success(L"[${dbg.cycles}]DISPATCH_TO_FIFO] PUSH to fetchOutput: " :+ 
                L"PC=0x${instr.pc}, isBranch=${instr.predecode.isBranch}, predTaken=${instr.bpuPrediction.isTaken}")
            }
            // S5 阶段的有效性由上游 S4 驱动
            when(s5.isFiring) { // 当 S5 处于活动状态时 (即 s4.isFiring 为 true 且 s5.isReady 为 true)
                // 从 Stageable 获取所有输入数据
                val pc             = s5(PC) // Cache Line 对齐的 PC
                val rawPc          = s5(RAW_PC) // 原始 PC
                val instructions   = s5(ICACHE_RSP_PAYLOAD).instructions
                val predecodeInfos = s5(PREDECODE_INFOS)
                val startIndex     = s5(START_INSTR_INDEX)
                val numValid       = s5(NUM_VALID_INSTRS)
                val fault          = s5(FAULT)

                // 1. 执行重量级的三输入加法 (并行计算所有指令的潜在跳转目标)
                // 修正 Vec.tabulate 类型推断问题：显式指定 Vec 的类型为 UInt(pCfg.pcWidth)
                val speculativeJumpTargets = Vec.tabulate(pCfg.fetchWidth) { i =>
                    val offs26 = instructions(i)(25 downto 0)
                    val operandA = pc.asSInt
                    val operandB = S(U(i) << log2Up(pCfg.dataWidth.value / 8), pCfg.pcWidth)
                    val operandC = (offs26.asSInt << 2).resize(pCfg.pcWidth)
                    // 确保返回的类型是 UInt(pCfg.pcWidth)
                    val result  = (operandA + operandB + operandC).asUInt.resized
                    report(L"[${dbg.cycles}]FETCH-S5] speculativeJumpTargets PC=0x${pc}, offs26=0x${offs26}, operandA=0x${operandA}, operandB=0x${operandB}, operandC=0x${operandC}, result=0x${result}")
                    result
                }

                // 2. 创建最终的 FetchGroup
                val finalGroup = FetchGroup(pCfg)
                finalGroup.pc                 := pc
                finalGroup.firstPc            := rawPc // 传递免计算的 firstPc
                finalGroup.instructions       := instructions
                finalGroup.fault              := fault
                finalGroup.numValidInstructions := numValid
                finalGroup.startInstructionIndex := startIndex
                finalGroup.predecodeInfos     := predecodeInfos
                finalGroup.branchMask         := B(predecodeInfos.map(_.isBranch).reverse)
                finalGroup.potentialJumpTargets := speculativeJumpTargets // 存入计算结果

                // 3. 推入最终的 FIFO (给 Dispatcher)
                fetchGroupsOut.io.push.valid   := !fault 
                fetchGroupsOut.io.push.payload := finalGroup

                if (enableLog) {
                    report(L"[${dbg.cycles}]FETCH-S5: PC=0x${finalGroup.pc}, firstPC=0x${finalGroup.firstPc}, startIdx=${finalGroup.startInstructionIndex}, numValid=${finalGroup.numValidInstructions}(${numValid}), fault=${finalGroup.fault}")
                }
                when(fetchGroupsOut.io.push.valid) {
                    report(L"[${dbg.cycles}]FETCH-S5: PUSH to fetchGroupsOut(PC=0x${finalGroup.pc})")
                    assert(!fault, "Push validity should be driven by fault status")
                    assert(rawPc >= pc && rawPc < (pc + lineBytes), "First PC (rawPc) is outside the current cache line bounds")
                }

            }
            
            // S5 暂停条件：如果下游 FIFO 满了，S5 必须暂停，进而导致 S4 暂停
            s5.haltWhen(!fetchGroupsOut.io.push.ready)
            when(!fetchGroupsOut.io.push.ready) {
               if (enableLog) report(L"[${dbg.cycles}]FETCH-S5] S5 is blocked by downstream fetchGroupsOut FIFO")
            }

        }


        {
            val correctedPop = Stream(FetchGroup(pCfg))
            correctedPop.payload := fetchGroupsOut.io.pop.payload
            fetchGroupsOut.io.pop.ready := correctedPop.ready

            // 用可信的 occupancy 信号来修复/门控不可信的 valid 信号
            correctedPop.valid := fetchGroupsOut.io.pop.valid && (fetchGroupsOut.io.occupancy =/= 0) && !doHardFlush
            dispatcher.io.fetchGroupIn << correctedPop
            
             // 软冲刷时无法避免 correctedPop.valid 可能为高，但软冲刷源 dispatcher 应该自己处理
            // when(doAnyFlush || isDrainingCacheRspReg) {
                // assert(!correctedPop.valid, "Dispatcher should not receive new groups during a flush or drain")
            // }
        }

        report(L"[${dbg.cycles}]FETCH-S5] fetchGroupsOut.io.occupancy=${fetchGroupsOut.io.occupancy}")
        dispatcher.io.bpuRsp << setup.bpuResponse
        setup.bpuQueryPort << dispatcher.io.bpuQuery

        if(enableLog) when(dispatcher.io.fetchGroupIn.fire) {
            val group = dispatcher.io.fetchGroupIn.payload
            if(enableLog) notice(L"[${dbg.cycles}]DISPATCHER-IN] POP from io.fetchGroupIn FIFO. PC=0x${group.pc}, startIdx=${group.startInstructionIndex}, numValid=${group.numValidInstructions}, firstPc=0x${group.firstPc}")
        }

        // --- 流水线冲刷 ---
        fetchPipeline.s1_pcGen.flushIt(doHardFlush)
        fetchPipeline.s2_iCacheAccess.flushIt(doExternalFlush || doRetryFlush)
        fetchPipeline.s3_iCacheWait.flushIt(doExternalFlush || doRetryFlush)
        fetchPipeline.s4_predecode.flushIt(doExternalFlush || doRetryFlush) // S4 冲刷 (不考虑 retryFlush，因为 s4 不会因 retry 而改变其内容)
        fetchPipeline.s5_targetCalc.flushIt(doExternalFlush || doRetryFlush) // <<< 冲刷 s5 阶段
        // FIFO 冲刷逻辑
        // fetchGroupsOut.io.flush := doHardFlush || isDrainingCacheRspReg || (RegNext(doSoftFlush) init(False))
        fetchGroupsOut.io.flush := doExternalFlush || doRetryFlush || isDrainingCacheRspReg // 会组合环路

        // dispatcher 只需要处理硬冲刷，软冲刷它内部会自己处理
        dispatcher.io.flush := doHardFlush
        when(dispatcher.io.flush) {
            if(enableLog) notice(L"[${dbg.cycles}] Flush dispatcher")
        }
        when(RegNext(doExternalFlush || isDrainingCacheRspReg)) {
            assert(fetchGroupsOut.io.occupancy === 0, "Flush should empty the fetchGroupsOut FIFO")
        }
        when(doRetryFlush) {
            if(enableLog) notice(L"[${dbg.cycles}] Retry flush")
        }
        fetchPipeline.build()
        // --- 资源释放 ---
        setup.iCacheService.release()
        setup.bpuService.release()
    }
}
