// package boson.test.scala
// import org.scalatest.flatspec.AnyFlatSpec

// import spinal.core._
// import spinal.core.sim._
// import spinal.lib.StreamFifo
// import scala.collection.mutable
// import scala.util.Random
// import boson.components.DelayedMemoryConfig
// import boson.components.DelayedMemory

// class DelayedMemorySpec extends AnyFlatSpec {

//   val dutConfig = DelayedMemoryConfig(
//     addressWidth = 8,
//     dataWidth = 32,
//     writeQueueDepth = 2, // Keep queue small for easier testing of full condition
//     minReadDelay = 3,
//     maxReadDelay = 15,
//     minWriteProcessDelay = 5, // Shorten for sim speed, was 20
//     maxWriteProcessDelay = 10, // Shorten for sim speed, was 30
//     enableBypassWriteToRead = true
//   )

//   "DelayedMemory" should "work correctly" in {

//     SimConfig.withFstWave // VCD or FST for waveform viewing
//       .compile(new DelayedMemory(dutConfig))
//       .doSim { dut =>
//         dut.clockDomain.forkStimulus(period = 10)
//         dut.clockDomain.onRisingEdges({
//           println(f"[PosEdge ${simTime() / 10}]")
//         })

//         // --- Helper Functions ---
//         val scoreboard = mutable.Map[Int, BigInt]() // Address -> Data

//         def driveCmd(addr: Int, data: BigInt, isWrite: Boolean): Unit = {
//           dut.io.cmd.valid #= true
//           dut.io.cmd.payload.address #= addr
//           dut.io.cmd.payload.isWrite #= isWrite
//           dut.io.cmd.payload.writeData #= data
//         }

//         // 清空命令
//         def idleCmd(): Unit = {
//           dut.io.cmd.valid #= false
//           dut.io.cmd.payload.address.assignBigInt(0) // Or some default
//           dut.io.cmd.payload.isWrite #= false
//           dut.io.cmd.payload.writeData.assignBigInt(0)
//         }

//         // 默认设为没准备好接收
//         dut.io.rsp.ready #= false // Default, will be set true when expecting data
//         idleCmd()
//         dut.clockDomain.waitSampling()

//         println("Simulation Started")

//         // --- Test Case 1: Single Write then Read ---
//         println("Test 1: Single Write then Read")
//         val addr1 = 0x10
//         val data1 = BigInt("AABBCCDD", 16)

//         // 写入数据，应该立马完成
//         driveCmd(addr1, data1, isWrite = true)
//         var writeAcceptedCycle = -1
//         var cyclesWaitedForWriteAccept = 0

//         // 启动一个任务线程
//         fork {
//           while (!dut.io.cmd.ready.toBoolean && cyclesWaitedForWriteAccept < 5) { // Timeout, 但是应该 1 个周期内完成。因为有队列的。
//             println(f"Waiting for write cmd to be accepted. counter = $cyclesWaitedForWriteAccept")
//             cyclesWaitedForWriteAccept += 1
//             dut.clockDomain.waitSampling()
//           }
//           assert(dut.io.cmd.ready.toBoolean, "Write cmd should be accepted (queue not full)")
//           writeAcceptedCycle = simTime().toInt / 10
//           println(f"[Edge ${simTime() / 10}] Write cmd for Addr $addr1%x data $data1%x accepted.")
//           scoreboard(addr1) = data1 // Update scoreboard immediately on acceptance
//         }
//         // 等待读写端口重新就绪
//         dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
//         idleCmd()
//         dut.clockDomain.waitSampling(dutConfig.maxWriteProcessDelay + 5) // Wait for internal write to complete

//         // 读回来看看
//         println(f"[Edge ${simTime() / 10}] Starting read from Addr $addr1%x")
//         // 发送读命令
//         driveCmd(addr1, 0, isWrite = false)
//         dut.io.rsp.ready #= true // 准备好接收读的结果

//         var readCmdAcceptedCycle = -1
//         cyclesWaitedForWriteAccept = 0
//         fork {
//           while (!dut.io.cmd.ready.toBoolean && cyclesWaitedForWriteAccept < 5) {
//             println(f"Waiting for read cmd to be accepted. counter = $cyclesWaitedForWriteAccept")
//             cyclesWaitedForWriteAccept += 1
//             dut.clockDomain.waitSampling()
//           }
//           assert(dut.io.cmd.ready.toBoolean, "Read command should be accepted")
//           readCmdAcceptedCycle = simTime().toInt / 10
//           println(f"[Edge ${simTime() / 10}] Read cmd for Addr $addr1%x accepted.")
//         }
//         // 等待读写端口就绪
//         dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
//         idleCmd() // 清空命令，准备接收响应

//         var readDataValidCycle = -1
//         var cyclesWaitedForRead = 0
//         fork {
//           while (!dut.io.rsp.valid.toBoolean && cyclesWaitedForRead < dutConfig.maxReadDelay + 5) { // Timeout
//             println(f"Waiting for valid read resp data. counter = $cyclesWaitedForRead")
//             cyclesWaitedForRead += 1
//             dut.clockDomain.waitSampling()
//           }
//           assert(dut.io.rsp.valid.toBoolean, s"Read response for $addr1%x timed out")
//           readDataValidCycle = simTime().toInt / 10
//           val readDelay = readDataValidCycle - readCmdAcceptedCycle
//           println(
//             f"[Edge ${simTime() / 10}] Read data valid for Addr $addr1%x. Data: ${dut.io.rsp.payload.readData.toBigInt}%x. Delay: $readDelay cycles."
//           )
//           assert(dut.io.rsp.payload.readData.toBigInt == scoreboard(addr1), s"Read data mismatch for $addr1%x")
//           assert(
//             readDelay >= dutConfig.minReadDelay && readDelay <= dutConfig.maxReadDelay,
//             s"Read delay $readDelay out of range [${dutConfig.minReadDelay}-${dutConfig.maxReadDelay}]"
//           )
//         }
//         dut.clockDomain.waitSamplingWhere(dut.io.rsp.ready.toBoolean)
//         dut.io.rsp.ready #= false
//         dut.clockDomain.waitSampling()

//         // --- Test Case 2: Fill Write Queue and Block ---
//         println("\nTest 2: Fill Write Queue and Block")
//         val baseAddr = 0x20
//         for (i <- 0 until dutConfig.writeQueueDepth) {
//           val currentAddr = baseAddr + i
//           val currentData = BigInt(s"112233${i}${i}", 16)
//           driveCmd(currentAddr, currentData, isWrite = true)
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
//           idleCmd()
//           scoreboard(currentAddr) = currentData
//           println(
//             s"[Edge ${simTime() / 10}] Queued write ${i + 1}/${dutConfig.writeQueueDepth} to Addr ${currentAddr.toHexString}"
//           )
//         }

//         // Try one more write, should block initially
//         val overflowAddr = baseAddr + dutConfig.writeQueueDepth
//         val overflowData = BigInt("DEADBEEF", 16)
//         driveCmd(overflowAddr, overflowData, isWrite = true)
//         println(f"[Edge ${simTime() / 10}] Attempting write to Addr ${overflowAddr.toHexString} (expecting block)")

//         assert(!dut.io.cmd.ready.toBoolean, "CMD.ready should be low when write queue is full")
//         println(f"[Edge ${simTime() / 10}] Confirmed: cmd.ready is low.")

//         // Wait for one internal write to complete, then the new write should be accepted
//         println(f"[Edge ${simTime() / 10}] Waiting for an internal write to free up space...")
//         dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean) // This will happen when ready eventually goes high
//         idleCmd()
//         scoreboard(overflowAddr) = overflowData
//         println(f"[Edge ${simTime() / 10}] Overflow write to Addr ${overflowAddr.toHexString} accepted after delay.")

//         dut.clockDomain.waitSampling(
//           dutConfig.maxWriteProcessDelay * (dutConfig.writeQueueDepth + 2)
//         ) // wait for all writes to clear

//         // Verify all written data
//         println("\nVerifying all writes from Test 2 by reading back:")
//         (0 to dutConfig.writeQueueDepth).foreach { i =>
//           val addrToRead = baseAddr + i
//           driveCmd(addrToRead, 0, isWrite = false)
//           dut.io.rsp.ready #= true
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
//           idleCmd()
//           dut.clockDomain.waitSamplingWhere(dut.io.rsp.ready.toBoolean)
//           val rdata = dut.io.rsp.payload.readData.toBigInt
//           println(f"[Edge ${simTime() / 10}] Read Addr ${addrToRead.toHexString}: ${rdata.toString(16)}")
//           assert(rdata == scoreboard(addrToRead), s"Data mismatch for Addr ${addrToRead.toHexString}")
//           dut.io.rsp.ready #= false
//         }

//         // --- Test Case 3: Interleaved Reads and Writes ---
//         println("\nTest 3: Interleaved Reads and Writes")
//         val addrR1 = 0xa0
//         val dataW1 = BigInt("C0C0C0C0", 16)
//         val addrW1 = 0xa1
//         val dataW2 = BigInt("D0D0D0D0", 16)
//         val addrW2 = 0xa2
//         val addrR2 = 0xa1 // Read data written by W1

//         // Initial write for R1
//         driveCmd(addrR1, 0xbeefbeef, true); scoreboard(addrR1) = BigInt("BEEFBEEF", 16)
//         dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean); idleCmd()
//         dut.clockDomain.waitSampling(dutConfig.maxWriteProcessDelay + 5)

//         // Fork a reader thread and a writer thread
//         val readerThread = fork {
//           dut.clockDomain.waitSampling(5) // Stagger start
//           // Read 1
//           driveCmd(addrR1, 0, false)
//           dut.io.rsp.ready #= true
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean); idleCmd()
//           dut.clockDomain.waitSamplingWhere(dut.io.rsp.ready.toBoolean)
//           println(f"[R-Cycle ${simTime() / 10}] Read 1 (Addr ${addrR1.toHexString}) data: ${dut.io.rsp.payload.readData.toBigInt
//               .toString(16)}")
//           assert(dut.io.rsp.payload.readData.toBigInt == scoreboard(addrR1))
//           dut.io.rsp.ready #= false

//           dut.clockDomain.waitSampling(Random.nextInt(10) + 5)

//           // Read 2 (value from writer thread)
//           driveCmd(addrR2, 0, false)
//           dut.io.rsp.ready #= true
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean); idleCmd()
//           dut.clockDomain.waitSamplingWhere(dut.io.rsp.ready.toBoolean)
//           println(f"[R-Cycle ${simTime() / 10}] Read 2 (Addr ${addrR2.toHexString}) data: ${dut.io.rsp.payload.readData.toBigInt
//               .toString(16)}")
//           assert(dut.io.rsp.payload.readData.toBigInt == scoreboard(addrR2))
//           dut.io.rsp.ready #= false
//         }

//         val writerThread = fork {
//           // Write 1
//           driveCmd(addrW1, dataW1, true); scoreboard(addrW1) = dataW1
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean); idleCmd()
//           println(f"[W-Cycle ${simTime() / 10}] Write 1 (Addr ${addrW1.toHexString}) accepted.")

//           dut.clockDomain.waitSampling(Random.nextInt(10) + 5)

//           // Write 2
//           driveCmd(addrW2, dataW2, true); scoreboard(addrW2) = dataW2
//           dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean); idleCmd()
//           println(f"[W-Cycle ${simTime() / 10}] Write 2 (Addr ${addrW2.toHexString}) accepted.")
//         }

//         readerThread.join()
//         writerThread.join()

//         dut.clockDomain.waitSampling(dutConfig.maxWriteProcessDelay * 3) // Let any pending writes complete
//         println("\nSimulation Finished.")
//       }
//   }
// }
