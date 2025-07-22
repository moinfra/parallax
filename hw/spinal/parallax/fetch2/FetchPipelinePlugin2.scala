package parallax.fetch2

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.components.dcache2.DataCacheService
import parallax.fetch.{FetchedInstr, InstructionPredecoder} // 复用

// 复用旧的服务接口定义
import parallax.fetch.SimpleFetchPipelineService
import parallax.components.dcache2.DataCachePluginConfig
import scala.collection.mutable.ArrayBuffer

trait FetchService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchedInstr]
  def newHardRedirectPort(priority: Int): Flow[UInt]
  def newFetchDisablePort(): Bool
}

class FetchPipelinePlugin(
    pCfg: PipelineConfig,
    dCfg: DataCachePluginConfig,
    fetchGroupFifoDepth: Int = 2,
    outputFifoDepth: Int = 4
) extends Plugin with FetchService with HardRedirectService {

    val enableLog = true
    val genIdWidth = pCfg.fetchGenIdWidth
    val instructionsPerGroup = dCfg.lineSize / pCfg.dataWidth.value
    require(instructionsPerGroup == pCfg.fetchWidth, s"dCache memDataWidth(${dCfg.lineSize}) and pCfg.fetchWidth(${pCfg.fetchWidth}) mismatch")

    // --- 服务接口实现 ---
    private val hardRedirectPorts = ArrayBuffer[(Int, Flow[UInt])]()
    private val doHardRedirect_ = Bool()
    override def newHardRedirectPort(priority: Int): Flow[UInt] = {
        framework.requireEarly(); val port = Flow(UInt(pCfg.pcWidth)); hardRedirectPorts += (priority -> port); port
    }
    private val fetchDisablePorts = ArrayBuffer[Bool]()
    override def newFetchDisablePort(): Bool = {
        framework.requireEarly(); val port = Bool(); fetchDisablePorts += port; port
    }
    override def doHardRedirect(): Bool = doHardRedirect_
    override def fetchOutput(): Stream[FetchedInstr] = logic.fetchOutput.io.pop

    val setup = create early new Area {
        val dcacheService = getService[DataCacheService]
        val bpuService = getService[BpuService]
        dcacheService.retain(); bpuService.retain()
        val iCachePort = dcacheService.newLoadPort(priority = 1)
        val bpuQueryPort = bpuService.newBpuQueryPort()
        val bpuResponse = bpuService.getBpuResponse()
    }

    val logic = create late new Area {
        lock.await()

        val dispatcher = new SmartDispatcher(pCfg)
        val fetchOutput = StreamFifo(FetchedInstr(pCfg), depth = outputFifoDepth)
        fetchOutput.io.push << dispatcher.io.fetchOutput

        val fetchPipeline = new Pipeline {
            val PC = Stageable(UInt(pCfg.pcWidth))
            val FETCH_GROUP = Stageable(FetchGroup(pCfg))
            val RETRY_PC = Stageable(UInt(pCfg.pcWidth))
            val S3_NEEDS_RETRY = Stageable(Bool())

            val s1_pcGen = newStage().setName("s1_PC_Gen")
            val s2_iCacheDelay = newStage().setName("s2_ICache_Delay")
            val s3_predecode = newStage().setName("s3_Predecode")

            connect(s1_pcGen, s2_iCacheDelay)(Connection.M2S())
            connect(s2_iCacheDelay, s3_predecode)(Connection.M2S())
        }.setCompositeName(this, "FetchPipeline")

        import fetchPipeline._

        // --- 重定向与排空逻辑 ---
        val softRedirect = dispatcher.io.softRedirect
        val hardRedirect = Flow(UInt(pCfg.pcWidth))
        val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)
        if (sortedHardRedirects.nonEmpty) {
            val valids = sortedHardRedirects.map(_.valid)
            hardRedirect.valid   := valids.orR
            hardRedirect.payload := MuxOH(OHMasking.first(valids), sortedHardRedirects.map(_.payload))
        } else { hardRedirect.setIdle() }
        doHardRedirect_ := hardRedirect.valid

        val doSoftFlush = softRedirect.valid
        val doHardFlush = hardRedirect.valid
        
        val iCacheInFlightCounter = CounterUpDown(pCfg.fetchWidth + 1)
        val isDraining = Reg(Bool()) init(False)

        when(doSoftFlush || doHardFlush) {
            isDraining := True
        }
        when(isDraining && iCacheInFlightCounter.value === 0) {
            isDraining := False
        }
        
        // --- s1: PC生成 ---
        val s1_logic = new Area {
            val s1 = fetchPipeline.s1_pcGen
            val fetchPc = Reg(UInt(pCfg.pcWidth)) init(pCfg.resetVector)
            
            val s3NeedsRetry = fetchPipeline.s3_predecode(S3_NEEDS_RETRY)
            val retryPc = fetchPipeline.s3_predecode(RETRY_PC)

            val nextPc = Mux(doHardRedirect, hardRedirect.payload,
                         Mux(softRedirect.valid, softRedirect.payload, 
                             fetchPc + (dCfg.memDataWidth / 8)))
            
            val pcWillUpdate = s1.isValid && s1.isReady
            when(pcWillUpdate && !s3NeedsRetry) {
                fetchPc := nextPc
            } .elsewhen(doHardFlush) {
                 fetchPc := hardRedirect.payload
            }
            
            s1(PC) := Mux(s3NeedsRetry, retryPc, fetchPc)
            
            // --- FIX: Implement fetchDisablePorts functionality ---
            val fetchDisabled = fetchDisablePorts.orR
            s1.haltWhen(isDraining || fetchDisabled)
            
            if(enableLog) when(s1.isFiring) {
                report(L"FETCH-S1: Firing PC=0x${s1(PC)}")
            }
        }

        // --- s2: I-Cache 访问与延迟 ---
        val s2_logic = new Area {
            val s2 = fetchPipeline.s2_iCacheDelay
            val iCachePort = setup.iCachePort

            iCachePort.cmd.valid   := s2.isFiring
            iCachePort.cmd.virtual := s2(PC)
            iCachePort.translated.physical := iCachePort.cmd.payload.virtual
            iCachePort.translated.abord := False
            iCachePort.cancels.clearAll()

            when(iCachePort.cmd.fire) { iCacheInFlightCounter.increment() }
            when(setup.iCachePort.rsp.fire) { iCacheInFlightCounter.decrement() }

            s2.haltWhen(!iCachePort.cmd.ready)

            if(enableLog) when(s2.isFiring) { report(L"FETCH-S2: ICache request sent for PC=0x${s2(PC)}") }
        }

        // --- s3: I-Cache响应接收与并行预解码 ---
        val s3_logic = new Area {
            val s3 = fetchPipeline.s3_predecode
            val iCacheRsp = setup.iCachePort.rsp
            
            s3(S3_NEEDS_RETRY) := False
            s3(RETRY_PC).assignDontCare()

            s3.haltWhen(!iCacheRsp.valid)
            
            val fetchGroupFifo = StreamFifo(FetchGroup(pCfg), depth = fetchGroupFifoDepth)
            fetchGroupFifo.io.push.setIdle()

            // --- FIX: Instantiate predecoders once, outside of conditional logic ---
            val predecoders = Seq.fill(pCfg.fetchWidth)(new InstructionPredecoder(pCfg))

            when(s3.isFiring) {
                if(enableLog) report(L"FETCH-S3: Rsp received for PC=0x${s3(PC)}. Redo=${iCacheRsp.redo}, Fault=${iCacheRsp.fault}")

                when(iCacheRsp.redo) {
                    s3(S3_NEEDS_RETRY) := True
                    s3(RETRY_PC)       := s3(PC) // 保存需要重试的PC
                    if(enableLog) report(L"FETCH-S3: Requesting retry for PC=0x${s3(PC)}")
                } .otherwise {
                    val predecodedGroup = FetchGroup(pCfg)
                    val instructionSlots = iCacheRsp.data.subdivideIn(pCfg.dataWidth)
                    
                    predecodedGroup.pc := s3(PC)
                    predecodedGroup.fault := iCacheRsp.fault
                    predecodedGroup.instructions := Vec(instructionSlots)
                    
                    // --- FIX: Connect to the existing predecoders and calculate numValidInstructions ---
                    for (i <- 0 until pCfg.fetchWidth) {
                        predecoders(i).io.instruction := instructionSlots(i)
                        predecodedGroup.predecodeInfos(i) := predecoders(i).io.predecodeInfo
                    }
                    predecodedGroup.branchMask := B(predecodedGroup.predecodeInfos.map(_.isBranch).reverse)

                    // If the cache line has a fault, the number of valid instructions is 0.
                    // Otherwise, all instructions in the fetch group are considered physically valid.
                    predecodedGroup.numValidInstructions := Mux(iCacheRsp.fault, U(0), U(pCfg.fetchWidth))
                    
                    fetchGroupFifo.io.push.valid   := True
                    fetchGroupFifo.io.push.payload := predecodedGroup
                    
                    s3.haltWhen(!fetchGroupFifo.io.push.ready)
                    if(enableLog) when(fetchGroupFifo.io.push.fire) { 
                        report(L"FETCH-S3: Predecoded and pushing to FIFO. PC=0x${s3(PC)}, NumValid=${predecodedGroup.numValidInstructions}") 
                    }
                }
            }
        }

        // --- 连接到智能分发器 ---
        dispatcher.io.fetchGroupIn << s3_logic.fetchGroupFifo.io.pop
        dispatcher.io.bpuRsp << setup.bpuResponse
        setup.bpuQueryPort << dispatcher.io.bpuQuery

        // --- 流水线冲刷 ---
        fetchPipeline.s1_pcGen.flushIt(doHardFlush) // 只有硬冲刷会复位s1
        fetchPipeline.s2_iCacheDelay.flushIt(doHardFlush || doSoftFlush)
        fetchPipeline.s3_predecode.flushIt(doHardFlush || doSoftFlush)
        
        val dispatchFlush = doHardFlush || doSoftFlush
        s3_logic.fetchGroupFifo.io.flush := dispatchFlush
        dispatcher.io.flush := dispatchFlush

        fetchPipeline.build()
        
        // --- 资源释放 ---
        setup.dcacheService.release()
        setup.bpuService.release()
    }
}
