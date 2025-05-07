// package boson.test.scala

// import boson.demo2.{PhysicalRegFilePlugin, PhysicalRegFileService}
// import boson.demo2.common.{Config}
// import boson.demo2.components.icache.SimpleICacheConfig
// import boson.demo2.fetch.FetchPipeline
// import boson.utilities.{Framework, Plugin, ProjectScope, Service, DataBase}
// import org.scalatest.funsuite.AnyFunSuite // Keep this import
// import spinal.core.{HIGH, SpinalConfig, _}
// import spinal.core.sim._
// import spinal.lib.Stream
// import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer}
// import spinal.tester.SpinalSimFunSuite

// // Rename to match file name
// class FetchPipelineSim(val useICache: Boolean) extends Component {
//   implicit val iCacheConfig: SimpleICacheConfig =
//     if (useICache) SimpleICacheConfig(addressWidth = Config.XLEN, simulatedMemLatency = 2) else null

//   val framework = Framework() // Basic framework for plugin services

//   // Instantiate pipeline and plugins
//   val fetchPipeline = framework.plug(new FetchPipeline)
//   val f0Plugin = framework.plug(new Fetch0Plugin)
//   val f1Plugin = framework.plug(new Fetch1Plugin(useICache))

//   // Build the pipeline
//   framework.build()

//   // Expose I/Os for testing
//   val io = new Bundle {
//     // Simulate CPU redirecting PC (for F0 testing)
//     val redirectValid = in Bool () default (False)
//     val redirectPc = in UInt (Config.XLEN bits) default (0)

//     // Observe output from Fetch1
//     val output = slave Stream (new Bundle {
//       val pc = UInt(Config.XLEN bits)
//       val instruction = Bits(32 bits)
//       val fault = Bool()
//     })

//     // Control for FetchMemorySystem (if ICache is used)
//     val memWriteEnable = if (useICache) in Bool () default (False) else null
//     val memWriteAddress = if (useICache) in UInt (Config.XLEN bits) default (0) else null
//     val memWriteData = if (useICache) in Bits (32 bits) default (0) else null
//     val flushCmdStart = if (useICache) in Bool () default (False) else null
//     val flushRspDone = if (useICache) out Bool () else null
//   }

//   // Connect redirect to F0
//   fetchPipeline.pipeline.fetch0(FrontendPipelineData.FETCH_REDIRECT_VALID) := io.redirectValid
//   fetchPipeline.pipeline.fetch0(FrontendPipelineData.FETCH_REDIRECT_PC) := io.redirectPc

//   // Connect output from F1
//   io.output.valid := fetchPipeline.pipeline.fetch1.isValidNotStuck
//   io.output.payload.pc := fetchPipeline.pipeline.fetch1(FrontendPipelineData.PC)
//   io.output.payload.instruction := fetchPipeline.pipeline.fetch1(FrontendPipelineData.INSTRUCTION)
//   io.output.payload.fault := fetchPipeline.pipeline.fetch1(FrontendPipelineData.FETCH_FAULT)
//   fetchPipeline.pipeline.fetch1.haltIt(!io.output.ready && fetchPipeline.pipeline.fetch1.isValid)

//   // Connect to FetchMemorySystem if ICache is used
//   if (useICache) {
//     val fmsProvider = framework.getService[FetchMemorySystemProvider]
//     val fms = fmsProvider.fetchSystem
//     assert(fms != null, "FetchMemorySystem service not found")

//     fms.io.memWriteEnable := io.memWriteEnable
//     fms.io.memWriteAddress := io.memWriteAddress
//     fms.io.memWriteData := io.memWriteData

//     fms.io.flush.cmd.valid := io.flushCmdStart // For now, directly drive. Could be a stream.
//     fms.io.flush.cmd.payload.start := io.flushCmdStart
//     io.flushRspDone := fms.io.flush.rsp.done
//   }
// }

// class FetchPipelineSpec extends SpinalSimFunSuite {

//   ghdlEnabled = false
//   iverilogEnabled = false
//   // Define a test clock domain
//   def simConfig = SimConfig.withWave.withVcdWave

//   // Helper function to create and compile the DUT
//   def createTestbench() = {
//     class TestPlugin extends Plugin {
//       val setup = create early new Area {
//         val regfile = getService[PhysicalRegFileService]
//         val readPort = regfile.newReadPort().simPublic()
//         val writePort = regfile.newWritePort().simPublic()
//       }
//     }

//     class TestBench extends Component {
//       val database = new DataBase
//       val framework = ProjectScope(database) on new Framework(
//         Seq(
//           new PhysicalRegFilePlugin(
//             numPhysRegs = Config.PHYS_REG_COUNT,
//             dataWidth = 32 bits
//           ),
//           new FetchPipeline(),
//           new TestPlugin()
//         )
//       )

//       def testPlugin = framework.getService[TestPlugin]
//       def readPort = testPlugin.setup.readPort
//       def writePort = testPlugin.setup.writePort
//     }
//     new TestBench()
//   }

//   test("Write to a reg and read it back") {
//     simConfig
//       .compile(createTestbench())
//       .doSim { tb =>
//         {
//           tb.clockDomain.forkStimulus(period = 10)

//         }
//       }
//   }
//   def testFetchWithMode(useICache: Boolean, testNameSuffix: String): Unit = {
//     test(s"Fetch Pipeline $testNameSuffix - Sequential Read") {
//       simConfig.compile(new FetchPipelineSim(useICache)).doSim { dut =>
//         dut.clockDomain.forkStimulus(period = 10)
//         SimTimeout(20000 * 10) // Increase timeout

//         val outputQueue = Queue[BigInt]()
//         StreamMonitor(dut.io.output, dut.clockDomain) { payload =>
//           outputQueue.enqueue(payload.instruction.toBigInt)
//         }
//         StreamReadyRandomizer(dut.io.output, dut.clockDomain)

//         // Preload memory if ICache is used
//         if (useICache) {
//           dut.io.memWriteEnable #= true
//           val romContent = DemoRomProvider.DemoRom().initContent // Get content
//           for (i <- romContent.indices) {
//             dut.io.memWriteAddress #= (i * 4)
//             dut.io.memWriteData #= romContent(i).toBigInt
//             dut.clockDomain.waitSampling()
//           }
//           dut.io.memWriteEnable #= false
//           dut.clockDomain.waitSampling()

//           // Flush ICache initially
//           println("Flushing ICache...")
//           dut.io.flushCmdStart #= true
//           dut.clockDomain.waitSampling()
//           dut.io.flushCmdStart #= false
//           waitUntil(dut.io.flushRspDone.toBoolean)
//           println("ICache Flush Done.")
//           dut.clockDomain.waitSampling()
//         }

//         dut.clockDomain.waitSampling(10)

//         val expectedInstructions = DemoRomProvider.DemoRom().initContent.map(_.toBigInt)

//         for (i <- 0 until expectedInstructions.length + (if (useICache) 5 else 0)) { // Read a bit more to see cache effects
//           // Wait until output is available or timeout
//           var waitCycles = 0
//           val maxWait = if (useICache) 100 else 20 // Cache miss can take time
//           while (outputQueue.isEmpty && waitCycles < maxWait) {
//             dut.clockDomain.waitSampling()
//             waitCycles += 1
//           }
//           if (outputQueue.nonEmpty) {
//             val receivedInst = outputQueue.dequeue()
//             val pcVal = dut.io.output.payload.pc.toBigInt // Sample PC when instruction is valid
//             println(s"Cycle: ${simTime()} PC: 0x${pcVal.toString(16)}, Received: 0x${receivedInst.toString(16)}")
//             if (pcVal / 4 < expectedInstructions.length) {
//               assert(
//                 receivedInst == expectedInstructions((pcVal / 4).toInt),
//                 s"PC ${pcVal}: Expected ${expectedInstructions((pcVal / 4).toInt).toString(16)}, got ${receivedInst.toString(16)}"
//               )
//             }
//           } else {
//             println(s"Cycle: ${simTime()} Timeout waiting for instruction at expected PC approx ${i * 4}")
//             if (i < expectedInstructions.length) fail(s"Timeout waiting for instruction at PC ${i * 4}")
//           }
//         }
//         dut.clockDomain.waitSampling(20)

//         if (useICache) {
//           println("--- Test Cache Hit ---")
//           // Ensure cache is populated from previous reads for PC=0
//           // Re-request PC=0, should be a hit
//           outputQueue.clear()
//           dut.io.redirectValid #= true
//           dut.io.redirectPc #= 0
//           dut.clockDomain.waitSampling()
//           dut.io.redirectValid #= false
//           dut.clockDomain.waitSampling()

//           var waitCyclesHit = 0
//           while (outputQueue.isEmpty && waitCyclesHit < 20) { // Hit should be fast
//             dut.clockDomain.waitSampling()
//             waitCyclesHit += 1
//           }
//           assert(outputQueue.nonEmpty, "Did not receive instruction on expected hit")
//           val hitInst = outputQueue.dequeue()
//           val hitPc = dut.io.output.payload.pc.toBigInt
//           println(
//             s"Cycle: ${simTime()} Cache Hit Test - PC: 0x${hitPc.toString(16)}, Received: 0x${hitInst.toString(16)}"
//           )
//           assert(hitInst == expectedInstructions(0), "Cache hit failed for PC=0")

//           println("--- Test Cache Miss (Conflict/New Line) ---")
//           // Request an address that maps to the same index as PC=0 but different tag (if cache is small enough)
//           // Or just a new line far away
//           val farAddress = 1024 // Example, ensure it's a new line
//           val farInstData = BigInt("DEADBEEF", 16)

//           dut.io.memWriteEnable #= true
//           dut.io.memWriteAddress #= farAddress
//           dut.io.memWriteData #= farInstData
//           dut.clockDomain.waitSampling()
//           dut.io.memWriteEnable #= false
//           dut.clockDomain.waitSampling()

//           outputQueue.clear()
//           dut.io.redirectValid #= true
//           dut.io.redirectPc #= farAddress
//           dut.clockDomain.waitSampling()
//           dut.io.redirectValid #= false
//           dut.clockDomain.waitSampling()

//           var waitCyclesMiss = 0
//           while (outputQueue.isEmpty && waitCyclesMiss < 100) {
//             dut.clockDomain.waitSampling()
//             waitCyclesMiss += 1
//           }
//           assert(outputQueue.nonEmpty, s"Did not receive instruction on expected miss for PC=$farAddress")
//           val missInst = outputQueue.dequeue()
//           val missPc = dut.io.output.payload.pc.toBigInt
//           println(
//             s"Cycle: ${simTime()} Cache Miss Test - PC: 0x${missPc.toString(16)}, Received: 0x${missInst.toString(16)}"
//           )
//           assert(missInst == farInstData, "Cache miss (new line) failed")
//         }
//       }
//     }
//   }

//   // Run tests for both modes
//   testFetchWithMode(useICache = false, "Direct ROM")
//   testFetchWithMode(useICache = true, "ICache")

// }
