package parallax.fetch2

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.bpu._
import parallax.utilities._
import parallax.fetch._
import parallax.fetch.icache._
import parallax.utilities.ParallaxSim._ 

trait FetchService extends Service with LockedImpl {
  def fetchOutput(): Stream[FetchedInstr]
  def newHardRedirectPort(priority: Int): Flow[UInt]
  def newFetchDisablePort(): Bool
}

/**
 * A modern, decoupled Fetch Pipeline implementation.
 * This version encapsulates complexity within FetchSequencer and SmartDispatcher,
 * resulting in a clean, robust, and high-throughput top-level design.
 * It includes extensive logging and assertions for debuggability and correctness verification.
 */
class FetchPipelinePlugin(
    pCfg: PipelineConfig,
    iCfg: ICacheConfig,
    outputFifoDepth: Int = 24
) extends Plugin with FetchService with HardRedirectService with SoftRedirectService {
    val enableLog = false // Master switch for logging in this plugin

    // --- Service Interface (implements contracts for other plugins) ---
    private val hardRedirectPorts = collection.mutable.ArrayBuffer[(Int, Flow[UInt])]()
    private val doHardRedirect_listening = Bool()
    private val doSoftRedirect_listening = Bool()
    private var fetchOutputCalled = false

    override def newHardRedirectPort(priority: Int): Flow[UInt] = {
        framework.requireEarly()
        val port = Flow(UInt(pCfg.pcWidth))
        hardRedirectPorts += (priority -> port)
        port
    }
    
    // Add back the fetch disable port if needed by other parts of the design
    private val fetchDisablePorts = collection.mutable.ArrayBuffer[Bool]()
    override def newFetchDisablePort(): Bool = {
        framework.requireEarly(); val port = Bool(); fetchDisablePorts += port; port
    }
    
    override def doHardRedirect(): Bool = doHardRedirect_listening
    override def doSoftRedirect(): Bool = doSoftRedirect_listening
    
    override def fetchOutput(): Stream[FetchedInstr] = {
        require(!fetchOutputCalled, "fetchOutput() can only be called once!")
        fetchOutputCalled = true
        framework.requireEarly()
        setup.relayedFetchOutput
    }

    // --- Setup Area: Resource Acquisition and Final Output Buffer ---
    val setup = create early new Area {
        // Acquire services from other plugins
        val iCacheService = getService[ICacheService]
        val bpuService = getService[BpuService]
        
        // Retain services to ensure they are available during logic generation
        iCacheService.retain()
        bpuService.retain()

        // Create the final output buffer. Its pop side is the official output of the fetch stage.
        val fetchOutputRawBuffer = StreamFifo(FetchedInstr(pCfg), depth = outputFifoDepth)
        val relayedFetchOutput = Stream(FetchedInstr(pCfg))
    }

    // --- Logic Area: The New, Simplified "Pipeline" ---
    val logic = create late new Area {
        lock.await() // Wait for services to be available

        // 1. Instantiate the core components
        val sequencer  = new FetchSequencer(pCfg, iCfg)
        val dispatcher = new SmartDispatcher(pCfg)
        val fetchOutputRawBuffer = setup.fetchOutputRawBuffer

        // 2. PC Generation and Control (The new "S1")
        val pcGen = new Area {
            val fetchPcReg = Reg(UInt(pCfg.pcWidth)) init (pCfg.resetVector)
            
            // Function to align PC to cache line boundary
            def alignToLine(pc: UInt): UInt = pc & ~U(iCfg.bytesPerLine - 1, pCfg.pcWidth)

            // --- Redirect Logic ---
            val softRedirect = dispatcher.io.softRedirect
            val hardRedirect = Flow(UInt(pCfg.pcWidth))
            
            val sortedHardRedirects = hardRedirectPorts.sortBy(-_._1).map(_._2)
            if (sortedHardRedirects.nonEmpty) {
                val valids = sortedHardRedirects.map(_.valid)
                hardRedirect.valid   := valids.orR
                hardRedirect.payload := MuxOH(OHMasking.first(valids), sortedHardRedirects.map(_.payload))
            } else {
                hardRedirect.setIdle()
            }

            val redirectValid = hardRedirect.valid || softRedirect.valid
            val redirectPc = Mux(hardRedirect.valid, hardRedirect.payload, softRedirect.payload)

            // --- PC Register Update Logic ---
            when(redirectValid) {
                fetchPcReg := redirectPc
                if(enableLog) {
                    when(hardRedirect.valid) {
                        notice(L"PC_GEN: Hard Redirect to 0x${hardRedirect.payload}")
                    } otherwise {
                        notice(L"PC_GEN: Soft Redirect to 0x${softRedirect.payload}")
                    }
                }
            } elsewhen (sequencer.io.newRequest.fire) {
                val nextLinePc = alignToLine(fetchPcReg) + iCfg.bytesPerLine
                fetchPcReg := nextLinePc
                if(enableLog) debug(L"PC_GEN: Advancing PC to next line 0x${nextLinePc}")
            }

            // --- Drive the Sequencer's Input ---
            val fetchDisabled = fetchDisablePorts.orR
            sequencer.io.newRequest.valid   := !redirectValid && !fetchDisabled
            sequencer.io.newRequest.payload.pc      := alignToLine(fetchPcReg)
            sequencer.io.newRequest.payload.rawPc   := fetchPcReg
            
            if(enableLog) {
                when(!sequencer.io.newRequest.ready) {
                    report(L"PC_GEN: Stalled by Sequencer (not ready). Current PC=0x${fetchPcReg}")
                }
                when(sequencer.io.newRequest.fire) {
                    success(L"PC_GEN: Firing new request to Sequencer. PC=0x${alignToLine(fetchPcReg)}, RawPC=0x${fetchPcReg}")
                }
            }
        }
        // End of the first part
        // Continued from the first part...

        // 3. Connect all the components together ("Plumbing")
        
        // Sequencer <-> ICache
        val iCachePort = setup.iCacheService.newICachePort()
        sequencer.io.iCacheCmd >> iCachePort.cmd
        iCachePort.rsp         >> sequencer.io.iCacheRsp
        setup.iCacheService.invalidate := False // Default value

        // Sequencer -> Dispatcher
        sequencer.io.dispatchGroupOut.setName("sequencerToDispatcher") >> dispatcher.io.fetchGroupIn

        // Dispatcher <-> BPU
        val bpuQueryPort = setup.bpuService.newBpuQueryPort()
        val bpuResponse = setup.bpuService.getBpuResponse()
        dispatcher.io.bpuQuery >> bpuQueryPort
        bpuResponse            >> dispatcher.io.bpuRsp

        // Dispatcher -> Final Output Buffer
        dispatcher.io.fetchOutput.setName("dispatcherToFifo") >> fetchOutputRawBuffer.io.push

        // 4. Handle Hard Flushes
        // A hard flush is the only event that needs to reset the components.
        val doHardFlush = pcGen.hardRedirect.valid
        val doSoftFlush = dispatcher.io.softRedirect.valid
        
        sequencer.io.flush              := doHardFlush || doSoftFlush
        dispatcher.io.flush             := doHardFlush
        fetchOutputRawBuffer.io.flush   := doHardFlush
        
        when(doHardFlush) {
            if(enableLog) notice(L"!!! SYSTEM: Hard Flush asserted!")
        }
        when(doSoftFlush) {
            if(enableLog) notice(L"!!! SYSTEM: Soft Flush asserted!")
        }
        // 5. Final Output Stage Logic
        val relayedFetchOutput = setup.relayedFetchOutput
        
        // The `throwWhen` is a clean way to discard output during a flush
        relayedFetchOutput << fetchOutputRawBuffer.io.pop.throwWhen(doHardFlush)

        if(enableLog) {
            when(fetchOutputRawBuffer.io.pop.fire && doHardFlush) {
                warning(L"FETCH_OUT: Instruction popped from FIFO but discarded due to hard flush.")
            }
            when(relayedFetchOutput.fire) {
                success(L"FETCH_OUT: Output a instruction to Decode. PC=0x${relayedFetchOutput.payload.pc}, Instr=0x${relayedFetchOutput.payload.instruction}")
            }
            report(L"FIFO_STATUS: Sequencer->Dispatcher valid=${sequencer.io.dispatchGroupOut.valid}, ready=${sequencer.io.dispatchGroupOut.ready}")
            report(L"FIFO_STATUS: Dispatcher->Output valid=${dispatcher.io.fetchOutput.valid}, ready=${dispatcher.io.fetchOutput.ready}")
            report(L"FIFO_STATUS: Final Output Buffer occupancy=${fetchOutputRawBuffer.io.occupancy}, valid=${fetchOutputRawBuffer.io.pop.valid}, ready=${fetchOutputRawBuffer.io.pop.ready}")
        }

        // --- Assertions for correctness ---
        
        // On a hard flush, the output FIFO should become empty in the next cycle.
        val hardFlushDelayed = RegNext(doHardFlush, init=False)
        when(hardFlushDelayed) {
            assert(fetchOutputRawBuffer.io.occupancy === 0, "Output FIFO should be empty one cycle after a hard flush")
        }

        // Sequencer should not accept new requests if it's full.
        when(!sequencer.io.newRequest.ready) {
            assert(!sequencer.io.newRequest.fire, "Sequencer should not fire when not ready")
        }
        
        // 6. Service Interface Implementation (listening ports)
        doHardRedirect_listening := doHardFlush
        doSoftRedirect_listening := pcGen.softRedirect.valid

        // Release services at the end of logic generation
        setup.iCacheService.release()
        setup.bpuService.release()
    }
}
