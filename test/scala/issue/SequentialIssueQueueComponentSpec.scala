// // filename: test/scala/SequentialIssueQueueSpec.scala
// // testOnly test.scala.SequentialIssueQueueSpec
// package test.scala

// import parallax.common._
// import parallax.components.issue._
// import parallax.execute.WakeupPayload
// import parallax.issue.IqDispatchCmd

// import spinal.core._
// import spinal.core.sim._
// import spinal.lib._

// import scala.collection.mutable
// import scala.util.Random

// // =========================================================================
// //  Test Infrastructure: IO Bundle and TestBench
// // =========================================================================

// /** A dedicated IO Bundle for the testbench. It's constructed with the full
//   * IssueQueueConfig to ensure type safety and compatibility.
//   */
// case class SeqIssueQueueTestIO[T_IQEntry <: Data with IQEntryLike](
//     val iqConfig: IssueQueueConfig[T_IQEntry],
//     val numWakeupPorts: Int
// ) extends Bundle
//     with IMasterSlave {
//   val allocateIn = Stream(IqDispatchCmd(iqConfig.pipelineConfig))
//   val issueOut = Stream(iqConfig.getIQEntry())
//   val wakeupIn = Vec.fill(numWakeupPorts)(Flow(WakeupPayload(iqConfig.pipelineConfig)))
//   val flush = Bool()

//   override def asMaster(): Unit = {
//     master(allocateIn); slave(issueOut); wakeupIn.foreach(master(_)); out(flush)
//   }
// }

// /** Testbench component that wraps our new SequentialIssueQueueComponent.
//   * It follows the best practice of creating complex, hardware-related config objects
//   * *inside* the Component's hardware generation context.
//   */
// class SequentialIssueQueueTestBench(
//     val pCfg: PipelineConfig,
//     val depth: Int,
//     val numWakeupPorts: Int
// ) extends Component {

//   // Create the specific, hardware-related IQ configuration here, in a safe context.
//   val iqConfig = IssueQueueConfig[IQEntryAluInt](
//     pipelineConfig = pCfg,
//     depth = depth,
//     exeUnitType = ExeUnitType.ALU_INT,
//     uopEntryType = HardType(IQEntryAluInt(pCfg)),
//     name = "TestSeqRingIQ"
//   )

//   val io = slave(SeqIssueQueueTestIO(iqConfig, numWakeupPorts))
//   io.simPublic()

//   // Instantiate the component under test
//   val iq = new SequentialIssueQueueComponent(
//     iqConfig = iqConfig,
//     numWakeupPorts = numWakeupPorts,
//     id = 0
//   )

//   // Expose internal state signals for detailed verification in the simulation
//   iq.storage.isFull.simPublic()
//   iq.storage.isEmpty.simPublic()

//   def storage = iq.storage

//   // Connect the testbench IO to the DUT IO
//   iq.io.allocateIn << io.allocateIn
//   io.issueOut <> iq.io.issueOut
//   iq.io.wakeupIn := io.wakeupIn
//   iq.io.flush := io.flush
// }

// // =========================================================================
// //  Test Specification
// // =========================================================================

// class SequentialIssueQueueSpec extends CustomSpinalSimFunSuite {

//   // Simulation Helpers (pure Sim DSL functions are safe outside the test case)
//   private def initDutIO(dut: SequentialIssueQueueTestBench)(implicit cd: ClockDomain): Unit = {
//     dut.io.allocateIn.valid #= false
//     dut.io.issueOut.ready #= false
//     dut.io.wakeupIn.foreach { port =>
//       port.valid #= false
//       port.payload.physRegIdx #= 0
//     }
//     dut.io.flush #= false
//     cd.waitSampling()
//   }

//   private def driveAllocRequest(
//       allocTarget: Stream[IqDispatchCmd],
//       p: ModelIQEntry,
//       pCfg: PipelineConfig
//   ): Unit = {
//     allocTarget.valid #= true
//     val cmd = allocTarget.payload; val uop = cmd.uop
//     uop.setDefaultForSim(); uop.robPtr #= p.robPtr; uop.decoded.isValid #= true
//     uop.rename.writesToPhysReg #= true; uop.rename.physDest.idx #= p.robPtr % 32
//     uop.decoded.useArchSrc1 #= p.use1; uop.rename.physSrc1.idx #= p.tag1
//     uop.decoded.useArchSrc2 #= p.use2; uop.rename.physSrc2.idx #= p.tag2
//     cmd.src1InitialReady #= p.rdy1; cmd.src2InitialReady #= p.rdy2
//   }

//   private def driveWakeup(wakeupPorts: Vec[Flow[WakeupPayload]], portIdx: Int, pRegIdx: Int): Unit = {
//     wakeupPorts(portIdx).valid #= true
//     wakeupPorts(portIdx).payload.physRegIdx #= pRegIdx
//   }

//   private def deassertWakeup(wakeupPorts: Vec[Flow[WakeupPayload]], portIdx: Int): Unit = {
//     wakeupPorts(portIdx).valid #= false
//   }

// // file: test/scala/issue/SequentialIssueQueueSpec.scala

//   // --- Main Test Case ---
//   test("SequentialIQ - Ring Buffer Golden Model Fuzzy Test") {

//     SimConfig.withWave
//       .compile {
//         // --- Hardware Elaboration Phase ---
//         // All hardware configuration and instantiation happens here to ensure
//         // a valid SpinalHDL context.

//         // 1. Define constants for this test
//         val IQ_DEPTH = 8
//         val MOCK_WAKEUP_PORTS = 2

//         // 2. Create the base PipelineConfig
//         val pCfg = PipelineConfig(
//           xlen = 32,
//           physGprCount = 32 + 16,
//           archGprCount = 32,
//           robDepth = 16 // This determines the robPtr's width (log2(16)+1 = 5 bits)
//         )

//         // 3. Instantiate the TestBench, passing primitive configs
//         new SequentialIssueQueueTestBench(
//           pCfg = pCfg,
//           depth = IQ_DEPTH,
//           numWakeupPorts = MOCK_WAKEUP_PORTS
//         )

//       }
//       .doSim { dut =>
//         // --- Simulation Phase ---
//         implicit val cd = dut.clockDomain.get
//         cd.forkStimulus(10)

//         // Retrieve final hardware configuration from the DUT to ensure consistency
//         val pCfg = dut.pCfg
//         val IQ_DEPTH = dut.depth
//         val MOCK_WAKEUP_PORTS = dut.numWakeupPorts

//         // Initialize DUT IO ports
//         initDutIO(dut)

//         // Setup the Golden Model and simulation variables
//         val model = new SequentialRingIQModel(IQ_DEPTH)
//         val rand = new Random(seed = System.currentTimeMillis())

//         // --- CRITICAL FIX for Bit Width Overflow ---
//         // The robPtrCounter must respect the hardware's bit width.
//         val robDepth = pCfg.robDepth
//         val robPtrCycle = robDepth * 2 // robPtr range is 0 to (robPtrCycle - 1)
//         var robPtrCounter = 0 // Start from 0 and wrap around

//         val totalInstructions = 1000
//         var cycles = 0
//         val timeout = totalInstructions * 10

//         // Main simulation loop
//         while (model.totalAllocated < totalInstructions && cycles < timeout) {
//           cycles += 1
//           val time = simTime()

//           // 1. Generate Randomized Stimulus
//           val do_alloc = rand.nextFloat() < 0.6
//           val alloc_payload_model = if (do_alloc) {
//             val use1 = rand.nextFloat() < 0.8; val rdy1 = !use1 || rand.nextFloat() < 0.3;
//             val tag1 = if (use1 && !rdy1) rand.nextInt(pCfg.physGprCount) else 0
//             val use2 = rand.nextFloat() < 0.8; val rdy2 = !use2 || rand.nextFloat() < 0.3;
//             val tag2 = if (use2 && !rdy2) rand.nextInt(pCfg.physGprCount) else 0
//             Some(ModelIQEntry(robPtrCounter, use1, tag1, rdy1, use2, tag2, rdy2))
//           } else None

//           val wakeup_events = (0 until MOCK_WAKEUP_PORTS)
//             .map(_ => if (rand.nextFloat() < 0.25) Some(rand.nextInt(pCfg.physGprCount)) else None)
//             .flatten
//             .distinct
//           val issue_ready = rand.nextFloat() < 0.9
//           val do_flush = cycles > 50 && rand.nextFloat() < 0.02

//           // 2. Drive DUT and Model with identical stimulus
//           model.driveInputs(do_alloc, alloc_payload_model, wakeup_events, issue_ready, do_flush)

//           if (do_alloc && alloc_payload_model.isDefined) {
//             driveAllocRequest(dut.io.allocateIn, alloc_payload_model.get, pCfg)
//           } else {
//             dut.io.allocateIn.valid #= false
//           }
//           wakeup_events.zipWithIndex.foreach { case (tag, idx) => driveWakeup(dut.io.wakeupIn, idx, tag) }
//           dut.io.issueOut.ready #= issue_ready
//           dut.io.flush #= do_flush

//           // 3. Advance clock and let the model evolve
//           cd.waitSampling()
//           model.cycle()
//           sleep(0)

//           // 4. Compare DUT vs. Model (Checker)
//           assert(
//             dut.io.allocateIn.ready.toBoolean == model.isReadyToAlloc,
//             s"[$time] allocateIn.ready MISMATCH! DUT=${dut.io.allocateIn.ready.toBoolean}, Model=${model.isReadyToAlloc}"
//           )
//           assert(
//             dut.io.issueOut.valid.toBoolean == model.isIssuing,
//             s"[$time] issueOut.valid MISMATCH! DUT=${dut.io.issueOut.valid.toBoolean}, Model=${model.isIssuing}"
//           )
//           if (model.isIssuing) {
//             assert(
//               dut.io.issueOut.payload.robPtr.toInt == model.getIssuedEntry.get.robPtr,
//               s"[$time] Issued robPtr MISMATCH! DUT=${dut.io.issueOut.payload.robPtr.toInt}, Model=${model.getIssuedEntry.get.robPtr}"
//             )
//           }
//           assert(
//             dut.storage.isFull.toBoolean == model.isFull,
//             s"[$time] isFull MISMATCH! DUT=${dut.storage.isFull.toBoolean}, Model=${model.isFull}"
//           )
//           assert(
//             dut.storage.isEmpty.toBoolean == model.isEmpty,
//             s"[$time] isEmpty MISMATCH! DUT=${dut.storage.isEmpty.toBoolean}, Model=${model.isEmpty}"
//           )

//           // 5. Cleanup and advance counters for the next cycle
//           wakeup_events.indices.foreach(idx => deassertWakeup(dut.io.wakeupIn, idx))
//           if (do_alloc && dut.io.allocateIn.ready.toBoolean) {
//             // Use modulo arithmetic to ensure the counter wraps around, just like hardware.
//             robPtrCounter = (robPtrCounter + 1) % robPtrCycle
//           }
//         }

//         // Final checks
//         assert(
//           cycles < timeout,
//           s"Test timed out after $timeout cycles! Instructions processed: ${model.totalAllocated}/$totalInstructions"
//         )
//         println(
//           s"SUCCESS: Sequential Ring IQ Fuzzy Test PASSED! ${model.totalAllocated} instructions processed in $cycles cycles."
//         )
//       }
//   }
//   thatsAll
// }

// case class ModelIQEntry(
//     robPtr: Int,
//     use1: Boolean,
//     tag1: Int,
//     var rdy1: Boolean,
//     use2: Boolean,
//     tag2: Int,
//     var rdy2: Boolean
// )

// class SequentialRingIQModel(val depth: Int) {
//   private var queue = Array.fill[Option[ModelIQEntry]](depth)(None)
//   private var allocPtr = 0; private var freePtr = 0; private var wrapMode = false
//   var totalAllocated = 0
//   private var drive_alloc_valid = false; private var drive_alloc_payload: Option[ModelIQEntry] = None
//   private var drive_wakeup_tags: Seq[Int] = Seq(); private var drive_issue_ready = false
//   private var drive_flush = false
//   var isReadyToAlloc = false; var isIssuing = false
//   var getIssuedEntry: Option[ModelIQEntry] = None
//   def isFull: Boolean = allocPtr == freePtr && wrapMode
//   def isEmpty: Boolean = allocPtr == freePtr && !wrapMode
//   def driveInputs(
//       allocValid: Boolean,
//       allocPayload: Option[ModelIQEntry],
//       wakeupTags: Seq[Int],
//       issueReady: Boolean,
//       flush: Boolean
//   ): Unit = {
//     this.drive_alloc_valid = allocValid; this.drive_alloc_payload = allocPayload
//     this.drive_wakeup_tags = wakeupTags; this.drive_issue_ready = issueReady
//     this.drive_flush = flush
//   }
//   def cycle(): Unit = {
//     isReadyToAlloc = !isFull && !drive_flush
//     val headEntryOpt = queue(freePtr)
//     var headIsReady = false
//     if (!isEmpty && headEntryOpt.isDefined) {
//       val head = headEntryOpt.get
//       val finalRdy1 = head.rdy1 || (head.use1 && drive_wakeup_tags.contains(head.tag1))
//       val finalRdy2 = head.rdy2 || (head.use2 && drive_wakeup_tags.contains(head.tag2))
//       headIsReady = (!head.use1 || finalRdy1) && (!head.use2 || finalRdy2)
//     }
//     isIssuing = !isEmpty && headIsReady && drive_issue_ready && !drive_flush
//     getIssuedEntry = if (isIssuing) headEntryOpt else None
//     val do_alloc = drive_alloc_valid && isReadyToAlloc; val do_issue = isIssuing
//     var next_allocPtr = allocPtr; var next_freePtr = freePtr; var next_wrapMode = wrapMode
//     val next_queue = queue.map(_.map(_.copy()))
//     for (i <- 0 until depth) {
//       next_queue(i).foreach { entry =>
//         if (entry.use1 && !entry.rdy1 && drive_wakeup_tags.contains(entry.tag1)) entry.rdy1 = true
//         if (entry.use2 && !entry.rdy2 && drive_wakeup_tags.contains(entry.tag2)) entry.rdy2 = true
//       }
//     }
//     if (do_alloc) {
//       next_queue(allocPtr) = drive_alloc_payload; totalAllocated += 1
//       next_allocPtr = (allocPtr + 1) % depth
//     }
//     if (do_issue) {
//       next_queue(freePtr) = None
//       next_freePtr = (freePtr + 1) % depth
//     }
//     if (do_alloc != do_issue) {
//       if (do_alloc) { // 队列净增长
//         if (next_allocPtr == freePtr) {
//           next_wrapMode = true
//         }
//       } else { // 队列净收缩
//         if (next_freePtr == allocPtr) {
//           next_wrapMode = false
//         }
//       }
//     }
//     if (drive_flush) {
//       next_allocPtr = 0; next_freePtr = 0; next_wrapMode = false
//       next_queue.indices.foreach(i => next_queue(i) = None)
//     }
//     allocPtr = next_allocPtr; freePtr = next_freePtr; wrapMode = next_wrapMode; queue = next_queue
//   }
// }
