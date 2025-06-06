package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import scala.collection.mutable.ArrayBuffer
import _root_.parallax.utilities.ParallaxLogger

// DummyUop 现在接收完整的 robPtrWidth
case class DummyUop(robPtrWidth: BitCount) extends Bundle with Dumpable with HasRobPtr {
  val pc: UInt = UInt(32 bits)
  // robPtr 现在是完整的 ROB ID (物理索引 + 世代位)
  val robPtr: UInt = UInt(robPtrWidth) 
  val hasException: Bool = Bool()
  def setDefault(): this.type = {
    pc := 0
    robPtr := 0
    hasException := False
    this
  }

  def setDefaultForSim(): this.type = {
    pc #= 0
    robPtr #= 0
    hasException #= false
    this
  }

  def dump(): Seq[Any] = {
    L"DummyUop(pc=${pc}, robPtr=${robPtr}, hasException=${hasException})"
  }
}

class ReorderBufferTestBench(config: ROBConfig[DummyUop]) extends Component {
  val io = slave(ROBIo(config))

  val rob = new ReorderBuffer(config)
  rob.io <> io
  

  rob.io.simPublic()
  rob.headPtr_reg.simPublic()
  rob.tailPtr_reg.simPublic()
  rob.count_reg.simPublic()
  rob.statuses.foreach(_.genBit.simPublic()) // 公开 statuses 中的 genBit 以便测试
  rob.statuses.foreach(_.done.simPublic()) 
  rob.statuses.foreach(_.busy.simPublic()) 
}

class ReorderBufferSpec extends CustomSpinalSimFunSuite {

  // Default PipelineConfig for tests
  val pipelineCfg: PipelineConfig = PipelineConfig() // 确保 PipelineConfig 包含 robGenBitWidth

  // Helper: 从完整的 robPtr 中提取物理索引和世代位
  def getPhysIdx(robPtr: BigInt, physIdxWidth: Int): Int = {
    (robPtr & ((1 << physIdxWidth) - 1)).toInt
  }
  def getGenBit(robPtr: BigInt, physIdxWidth: Int): Boolean = {
    ((robPtr >> physIdxWidth) & 1) == 1
  }
  
  // --- Helper Functions for Driving ROB IO ---

  def driveDefaultDummyUop(targetUopPort: DummyUop): Unit = {
    targetUopPort.setDefaultForSim() 
  }

  def createDummyUop(
      robPtrWidth: BitCount, // 完整的 robPtr 宽度
      robPtrForUop: BigInt,    // 完整的 robPtr 值
      hasException: Bool = False,
      pcVal: Int = 0 
  ): DummyUop = {
    val uop = DummyUop(robPtrWidth)
    uop.setDefault()
    uop.robPtr := robPtrForUop 
    uop.hasException := hasException
    uop.pc := pcVal
    uop
  }

  def driveAllocate(
      dutIo: ROBIo[DummyUop],
      allocRequests: Seq[(Boolean, DummyUop, Int)] // Seq of (fire, uop, pc)
  ): Unit = {
    require(allocRequests.length <= dutIo.config.allocateWidth)
    for (i <- 0 until dutIo.config.allocateWidth) {
      val port = dutIo.allocate(i)
      if (i < allocRequests.length) {
        port.fire #= allocRequests(i)._1
        // Assign members of the uop Bundle individually
        val reqUop = allocRequests(i)._2
        // Assign directly from reqUop's fields (which hold SpinalHDL literals/values)
        // to port.uopIn's fields (which are DUT's hardware inputs).
        // The #= operator handles assignment between compatible SpinalHDL types.
        // No .toBoolean, .toInt, .toEnum needed here because both sides are SpinalHDL types.

        driveDefaultDummyUop(port.uopIn)

        port.pcIn #= allocRequests(i)._3.toInt
      } else {
        port.fire #= false
        driveDefaultDummyUop(port.uopIn) // 确保默认值
        port.pcIn #= 0
      }
    }
    sleep(1) 
  }

  def driveWriteback(
      dutIo: ROBIo[DummyUop],
      // wbRequests: Seq of (fire, fullRobPtr, hasException, excCode)
      wbRequests: Seq[(Boolean, BigInt, Boolean, Int)] 
  ): Unit = {
    require(wbRequests.length <= dutIo.config.numWritebackPorts)
    ParallaxLogger.log(
      s"[TB] Driving Writeback - Count: ${wbRequests.count(_._1)}, Requests (fire,fullRobPtr,exc,code): [" +
        wbRequests.map(r => s"(${r._1},#${r._2},${r._3},${r._4})").mkString(",") + "]"
    )
    for (i <- 0 until dutIo.config.numWritebackPorts) {
      if (i < wbRequests.length) {
        if(wbRequests(i)._1) {
          ParallaxLogger.log(s"[TB] Driving Writeback - Port $i: Fire=${wbRequests(i)._1}, FullRobPtr=${wbRequests(i)._2}, ExceptionOccurred=${wbRequests(i)._3}, ExceptionCode=${wbRequests(i)._4}")
        }
        dutIo.writeback(i).fire #= wbRequests(i)._1
        dutIo.writeback(i).robPtr #= wbRequests(i)._2 // 驱动完整的 robPtr
        dutIo.writeback(i).exceptionOccurred #= wbRequests(i)._3
        dutIo.writeback(i).exceptionCodeIn #= wbRequests(i)._4
      } else {
        dutIo.writeback(i).fire #= false
      }
    }
    sleep(1)
  }

  def setAllocateInputs(dutIo: ROBIo[DummyUop], allocRequests: Seq[(Boolean, DummyUop, Int)]): Unit = {
    require(allocRequests.length <= dutIo.config.allocateWidth)
    for (i <- 0 until dutIo.config.allocateWidth) {
      val port = dutIo.allocate(i)
      if (i < allocRequests.length) {
        port.fire #= allocRequests(i)._1
        port.uopIn := allocRequests(i)._2
        port.pcIn #= allocRequests(i)._3
      } else {
        port.fire #= false
        driveDefaultDummyUop(port.uopIn)
        port.pcIn #= 0
      }
    }
  }

  def driveCommitFire(dutIo: ROBIo[DummyUop], fires: Seq[Boolean]): Unit = {
    require(fires.length == dutIo.config.commitWidth)
    ParallaxLogger.log(s"[TB] Driving CommitFire - Fires: ${fires.mkString(",")}")
    for (i <- 0 until dutIo.config.commitWidth) {
      dutIo.commitFire(i) #= fires(i)
    }
    sleep(1)
  }

  def driveFlushNew(
      dutIo: ROBIo[DummyUop],
      fire: Boolean,
      reason: FlushReason.E = FlushReason.NONE,
      // targetRobPtr 现在是完整的 ROB ID
      targetRobPtr: BigInt = BigInt(0) 
  ): Unit = {
    ParallaxLogger.log(s"[TB] Driving Flush - Fire: $fire, Reason: ${reason.toString()}, TargetFullRobPtr: $targetRobPtr")
    dutIo.flush.valid #= fire
    if (fire) {
      dutIo.flush.payload.reason #= reason
      dutIo.flush.payload.targetRobPtr #= targetRobPtr // 驱动完整的 robPtr
    } else {
      dutIo.flush.payload.reason #= FlushReason.NONE
      dutIo.flush.payload.targetRobPtr #= 0
    }
  }

  def initRobInputs(dutIo: ROBIo[DummyUop]): Unit = {
    driveAllocate(dutIo, Seq.empty) 
    driveWriteback(dutIo, Seq.empty)
    driveCommitFire(dutIo, Seq.fill(dutIo.config.commitWidth)(false))
    driveFlushNew(dutIo, false) 
  }

  def getInternalRobPointers(tb: ReorderBufferTestBench): (BigInt, BigInt, Int) = {
    val head = tb.rob.headPtr_reg.toBigInt
    val tail = tb.rob.tailPtr_reg.toBigInt
    val count = tb.rob.count_reg.toInt
    (head, tail, count)
  }

  // --- Test Cases ---
  // 调整 robDepth 和相关的 robPtrWidth
  val robDepth = 16
  val allocW = 2
  val commitW = 2
  val wbW = 2
  val baseRobConfig = ROBConfig(
    robDepth = robDepth,
    commitWidth = commitW,
    allocateWidth = allocW,
    numWritebackPorts = wbW,
    // DummyUop 的 robPtrWidth 现在是完整的 ROB ID 宽度
    uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)), 
    defaultUop = () => DummyUop(log2Up(robDepth) + 1 bits).setDefault(),
    pcWidth = 32 bits,
    exceptionCodeWidth = 8 bits
  )

  test("ROB - Initialization and Empty/Full") {
    val testConfig = baseRobConfig
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value

    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 300) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io) 

      dut.clockDomain.waitSampling() 
      sleep(1) 

      assert(dut.io.empty.toBoolean, "ROB should be empty after init")
      for (i <- 0 until testConfig.allocateWidth) {
        assert(dut.io.canAllocate(i).toBoolean, s"ROB canAllocate($i) should be true for empty ROB")
      }

      val (h, t, c) = getInternalRobPointers(dut)
      assert(h == 0, s"Initial headPtr should be 0 (full ID), got $h")
      assert(t == 0, s"Initial tailPtr should be 0 (full ID), got $t")
      assert(c == 0, s"Initial count should be 0, got $c")

      // Fill the ROB
      var allocatedIndices = List[BigInt]() // 存储完整的 ROB ID
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until testConfig.robDepth / testConfig.allocateWidth) {
        val uopsToAlloc = (0 until testConfig.allocateWidth)
          .map { slot =>
            val currentFullRobPtr = expectedNextFullRobPtr
            expectedNextFullRobPtr += 1
            (
              true,
              // createDummyUop 的 robPtrForUop 现在是完整的 ROB ID
              createDummyUop(testConfig.robPtrWidth, currentFullRobPtr), 
              (i * testConfig.allocateWidth + slot) * 4 // PC
            )
          }
        driveAllocate(dut.io, uopsToAlloc.map(req => (req._1, req._2, req._3)))
        
        for (k <- 0 until testConfig.allocateWidth) {
          assert(dut.io.canAllocate(k).toBoolean, s"Fill loop iter $i, slot $k: canAllocate should be true")
          allocatedIndices = allocatedIndices :+ dut.io.allocate(k).robPtr.toBigInt
        }
        dut.clockDomain.waitSampling() 
        sleep(1)
      }
      val (hFull, tFull, cFull) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After filling: Head=$hFull, Tail=$tFull, Count=$cFull. Indices: ${allocatedIndices.mkString(",")}")
      assert(cFull == testConfig.robDepth, s"ROB count should be ${testConfig.robDepth}, got $cFull")
      assert(!dut.io.empty.toBoolean, "ROB should not be empty when full")

      for (i <- 0 until testConfig.allocateWidth) {
        assert(!dut.io.canAllocate(i).toBoolean, s"ROB canAllocate($i) should be false for full ROB")
      }

      driveAllocate(
        dut.io,
        Seq(
          (true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr), 0)
        )
      )
      dut.clockDomain.waitSampling()
      sleep(1)
      val (hAfterOverflow, tAfterOverflow, cAfterOverflow) = getInternalRobPointers(dut)
      assert(cAfterOverflow == testConfig.robDepth, "ROB count should not change when trying to overflow")
      assert(tAfterOverflow == tFull, "ROB tailPtr should not change when trying to overflow")

      initRobInputs(dut.io) 
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Basic Allocate, Writeback, Commit (Single Stream)") {
    val testConfig = baseRobConfig.copy(allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 301) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      var currentPC = 100
      val numOps = 3

      var allocatedRobIndices = ArrayBuffer[BigInt]() // 存储完整的 ROB ID
      var expectedFullRobPtr = BigInt(0)
      for (i <- 0 until numOps) {
        // createDummyUop 的 robPtrForUop 现在是完整的 ROB ID
        val uop = createDummyUop(testConfig.robPtrWidth, expectedFullRobPtr) 
        driveAllocate(dut.io, Seq((true, uop, currentPC)))
        assert(dut.io.canAllocate(0).toBoolean, s"Alloc $i: canAllocate should be true")
        val fullRobPtr = dut.io.allocate(0).robPtr.toBigInt
        allocatedRobIndices += fullRobPtr
        ParallaxLogger.log(s"[TB] Allocated op $i to Full ROB index $fullRobPtr, PC $currentPC")
        assert(fullRobPtr == expectedFullRobPtr, s"Allocated fullRobPtr mismatch. Expected $expectedFullRobPtr, got $fullRobPtr")
        expectedFullRobPtr += 1
        dut.clockDomain.waitSampling(); sleep(1)
        currentPC += 4
      }
      driveAllocate(dut.io, Seq.empty) 
      val (h1, t1, c1) = getInternalRobPointers(dut)
      assert(c1 == numOps, s"Count should be $numOps after allocation, got $c1")
      // t1 是完整的 ROB ID，需要提取物理部分进行比较
      assert(
        getPhysIdx(t1, robPhysIdxWidth) == numOps % testConfig.robDepth,
        s"Tail pointer (phys) mismatch. Expected ${numOps % testConfig.robDepth}, got ${getPhysIdx(t1, robPhysIdxWidth)}"
      )

      val wbOrder =
        Seq(allocatedRobIndices(1), allocatedRobIndices(0), allocatedRobIndices(2))
      for (fullRobPtrToWb <- wbOrder) {
        driveWriteback(dut.io, Seq((true, fullRobPtrToWb, false, 0))) // 使用完整的 ROB ID
        ParallaxLogger.log(s"[TB] Writing back Full ROB index $fullRobPtrToWb")
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty) 

      var committedCount = 0
      for (i <- 0 until numOps) {
        dut.clockDomain.waitSampling(0) 
        assert(
          dut.io.commit(0).valid.toBoolean,
          s"Commit $i: commit(0).valid should be true. Head: ${dut.rob.headPtr_reg.toBigInt}, Count: ${dut.rob.count_reg.toInt}"
        )
        val committedEntry = dut.io.commit(0).entry
        val committedFullRobPtr = dut.rob.headPtr_reg.toBigInt
        println(
          s"[TB] Commit $i: Full ROB Idx ${committedFullRobPtr}, Valid=${dut.io.commit(0).valid.toBoolean}, " +
            s"PC=${committedEntry.payload.pc.toBigInt}, Done=${committedEntry.status.done.toBoolean}, GenBitInStatus=${dut.rob.statuses(getPhysIdx(committedFullRobPtr, robPhysIdxWidth)).genBit.toBoolean}"
        )
        assert(committedEntry.status.done.toBoolean, s"Commit $i: Entry should be done.")
        // 查找原始分配的 PC
        val originalAllocatedUop = allocatedRobIndices.zipWithIndex.find(_._1 == committedFullRobPtr)
        assert(originalAllocatedUop.isDefined, s"Committed Full ROB Idx $committedFullRobPtr not found in allocated list.")
        val originalPC = 100 + originalAllocatedUop.get._2 * 4
        assert(
          committedEntry.payload.pc.toBigInt == originalPC,
          s"Committed PC mismatch. Expected $originalPC, got ${committedEntry.payload.pc.toBigInt}"
        ) 

        driveCommitFire(dut.io, Seq(true)) 
        dut.clockDomain.waitSampling(); sleep(1)
        committedCount += 1
      }
      driveCommitFire(dut.io, Seq(false)) 
      val (h2, t2, c2) = getInternalRobPointers(dut)
      assert(c2 == 0, s"Count should be 0 after all commits, got $c2")
      assert(dut.io.empty.toBoolean, "ROB should be empty after all commits")
      assert(h2 == t2, s"Head and Tail should match when empty. H=$h2, T=$t2")

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Superscalar Allocate (2-wide) and Commit (2-wide)") {
    val numSlots = 2
    val testConfig = baseRobConfig.copy(allocateWidth = numSlots, commitWidth = numSlots, numWritebackPorts = numSlots)
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 302) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      val numOpsToProcess = 4 
      assert(numOpsToProcess % numSlots == 0)
      var pcCounter = 200
      var allocatedRobPtrList = List[BigInt]() // 存储完整的 ROB ID
      var expectedNextFullRobPtr = BigInt(0)

      println("--- Phase 1: Superscalar Allocation ---")
      for (i <- 0 until numOpsToProcess / numSlots) {
        val uopsForCycle = (0 until numSlots).map { j =>
          val currentFullRobPtr = expectedNextFullRobPtr
          expectedNextFullRobPtr += 1
          val uop = createDummyUop(testConfig.robPtrWidth, currentFullRobPtr)
          (true, uop, pcCounter + j * 4)
        }
        driveAllocate(dut.io, uopsForCycle)
        for (k_alloc <- 0 until numSlots) {
          assert(dut.io.canAllocate(k_alloc).toBoolean, s"Cycle $i, Alloc slot $k_alloc: canAllocate should be true")
          val allocatedFullId = dut.io.allocate(k_alloc).robPtr.toBigInt
          allocatedRobPtrList = allocatedRobPtrList :+ allocatedFullId
          println(
            s"[TB] Allocated for PC ${pcCounter + k_alloc * 4} to Full ROB index ${allocatedFullId}"
          )
        }
        dut.clockDomain.waitSampling(); sleep(1)
        pcCounter += numSlots * 4
      }
      driveAllocate(dut.io, Seq.empty)
      val (h_after_alloc, t_after_alloc, c_after_alloc) = getInternalRobPointers(dut)
      assert(c_after_alloc == numOpsToProcess, s"Count mismatch after alloc: $c_after_alloc vs $numOpsToProcess")
      assert(t_after_alloc == expectedNextFullRobPtr, s"Tail pointer mismatch after alloc: $t_after_alloc vs $expectedNextFullRobPtr")


      println("--- Phase 2: Superscalar Writeback (all ops) ---")
      val wbBatches = allocatedRobPtrList.grouped(testConfig.numWritebackPorts).toList
      for (batch <- wbBatches) {
        val wbRequestsForCycle = batch.map(idx => (true, idx, false, 0)) // 使用完整的 ROB ID
        driveWriteback(dut.io, wbRequestsForCycle)
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)

      println("--- Phase 3: Superscalar Commit ---")
      var totalCommitted = 0
      for (i <- 0 until numOpsToProcess / numSlots) {
        dut.clockDomain.waitSampling(0) 

        for (k_commit <- 0 until numSlots) {
          assert(
            dut.io.commit(k_commit).valid.toBoolean,
            s"Commit cycle $i, slot $k_commit: Expected commit valid. Head=${dut.rob.headPtr_reg.toBigInt}, Count=${dut.rob.count_reg.toInt}"
          )
          val entry = dut.io.commit(k_commit).entry
          assert(entry.status.done.toBoolean, s"Commit cycle $i, slot $k_commit: Entry not done.")
          
          val expectedFullRobPtrForCommitSlot = (dut.rob.headPtr_reg.toBigInt + k_commit)
          val originalIdxInAllocList = allocatedRobPtrList.indexOf(expectedFullRobPtrForCommitSlot)
          assert(originalIdxInAllocList != -1, s"Committed Full ROB index $expectedFullRobPtrForCommitSlot not found in original allocation list")
          val expectedPC = 200 + originalIdxInAllocList * 4
          assert(
            entry.payload.pc.toBigInt == expectedPC,
            s"Commit cycle $i, slot $k_commit: PC mismatch. Expected $expectedPC, got ${entry.payload.pc.toBigInt}. Full ROB Idx: $expectedFullRobPtrForCommitSlot"
          )
          println(
            s"[TB] Commit candidate slot $k_commit: Full ROB Idx $expectedFullRobPtrForCommitSlot, PC ${entry.payload.pc.toBigInt}"
          )
        }

        driveCommitFire(dut.io, Seq.fill(numSlots)(true)) 
        dut.clockDomain.waitSampling(); sleep(1)
        totalCommitted += numSlots
      }
      driveCommitFire(dut.io, Seq.fill(numSlots)(false))
      val (h_after_commit, t_after_commit, c_after_commit) = getInternalRobPointers(dut)
      assert(c_after_commit == 0, s"Count should be 0 after all commits, got $c_after_commit")
      assert(dut.io.empty.toBoolean, "ROB should be empty")
      assert(
        h_after_commit == t_after_alloc,
        s"Head should be at old tail. H=$h_after_commit, OldTail=$t_after_alloc"
      ) 

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Exception Propagation and Commit Handling") {
    val testConfig = baseRobConfig.copy(allocateWidth = 1, commitWidth = 1, numWritebackPorts = 1)
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 304) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io) 
      dut.clockDomain.waitSampling()

      val pcOp1 = 100
      val pcOp2Exc = 104 
      val pcOp3 = 108
      val testExceptionCode = 0xab

      var expectedNextFullRobPtr = BigInt(0)

      println("--- Phase 1: Allocation ---")
      // Op1
      val uop1 = createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr)
      setAllocateInputs(dut.io, Seq((true, uop1, pcOp1)))
      dut.clockDomain.waitSampling(0) 
      val robPtrOp1 = dut.io.allocate(0).robPtr.toBigInt
      ParallaxLogger.log(s"[TB CAPTURE] robPtrOp1 CAPTURED AS: $robPtrOp1, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toBigInt}")
      assert(robPtrOp1 == expectedNextFullRobPtr)
      expectedNextFullRobPtr += 1
      dut.clockDomain.waitSampling(); sleep(1)

      // Op2
      val uop2 = createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, hasException = False) 
      setAllocateInputs(dut.io, Seq((true, uop2, pcOp2Exc)))
      dut.clockDomain.waitSampling(0)
      val robPtrOp2Exc = dut.io.allocate(0).robPtr.toBigInt
      ParallaxLogger.log(s"[TB CAPTURE] robPtrOp2Exc CAPTURED AS: $robPtrOp2Exc, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toBigInt}")
      assert(robPtrOp2Exc == expectedNextFullRobPtr)
      expectedNextFullRobPtr += 1
      dut.clockDomain.waitSampling(); sleep(1)

      // Op3
      val uop3 = createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr)
      setAllocateInputs(dut.io, Seq((true, uop3, pcOp3)))
      dut.clockDomain.waitSampling(0)
      val robPtrOp3 = dut.io.allocate(0).robPtr.toBigInt
      ParallaxLogger.log(s"[TB CAPTURE] robPtrOp3 CAPTURED AS: $robPtrOp3, dut.rob.tailPtr_reg=${dut.rob.tailPtr_reg.toBigInt}")
      assert(robPtrOp3 == expectedNextFullRobPtr)
      expectedNextFullRobPtr += 1
      dut.clockDomain.waitSampling(); sleep(1)

      setAllocateInputs(dut.io, Seq.empty)
      dut.clockDomain.waitSampling(0) 

      ParallaxLogger.log(s"[TB FINAL CAPTURED IDs] robPtrOp1=$robPtrOp1, robPtrOp2Exc=$robPtrOp2Exc, robPtrOp3=$robPtrOp3")
      assert(robPtrOp1 == 0, "robPtrOp1 should be 0")
      assert(robPtrOp2Exc == 1, "robPtrOp2Exc should be 1")
      assert(robPtrOp3 == 2, "robPtrOp3 should be 2")

      println("--- Phase 2: Writeback ---")
      // Op1 (normal)
      driveWriteback(dut.io, Seq((true, robPtrOp1, false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op2 (exception) - This is where the exception is signaled during writeback
      driveWriteback(dut.io, Seq((true, robPtrOp2Exc, true, testExceptionCode)))
      dut.clockDomain.waitSampling(); sleep(1)
      // Op3 (normal, after exception op)
      driveWriteback(dut.io, Seq((true, robPtrOp3, false, 0)))
      dut.clockDomain.waitSampling(); sleep(1)
      driveWriteback(dut.io, Seq.empty)
      dut.clockDomain.waitSampling(0) 

      println("--- Phase 3: Commit ---")
      // Commit Op1 (should be fine)
      println("[TB] Attempting to commit Op1 (robPtr=0)")
      dut.clockDomain.waitSampling(0); 
      sleep(1) 
      assert(dut.io.commit(0).valid.toBoolean, "Op1 should be valid for commit")
      assert(!dut.io.commit(0).entry.status.hasException.toBoolean, "Op1 should not have exception")
      driveCommitFire(dut.io, Seq(true));
      dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op2 (exception op)
      println("[TB] Attempting to commit Op2 (robPtr=1, the exception op)")
      dut.clockDomain.waitSampling(0); sleep(1)
      assert(dut.io.commit(0).valid.toBoolean, "Op2 (exception) should be valid for commit check")
      assert(dut.io.commit(0).entry.status.done.toBoolean, "Op2 (exception) should be done")
      assert(dut.io.commit(0).entry.status.hasException.toBoolean, "Op2 should have exception flag set")
      assert(dut.io.commit(0).entry.status.exceptionCode.toInt == testExceptionCode, "Op2 exception code mismatch")
      assert(dut.io.commit(0).entry.payload.pc.toBigInt == pcOp2Exc, "Op2 PC mismatch")
      
      driveCommitFire(dut.io, Seq(true));
      dut.clockDomain.waitSampling(); sleep(1)

      // Commit Op3 (after an exception op)
      println("[TB] Checking state after Op2 (exception) commit")
      val (h_after_exc, t_after_exc, c_after_exc) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"ROB state: Head=$h_after_exc, Tail=$t_after_exc, Count=$c_after_exc")

      if (c_after_exc > 0 && h_after_exc == robPtrOp3) { 
        println("[TB] Attempting to commit Op3 (robPtr=2)")
        dut.clockDomain.waitSampling(0); sleep(1)
        assert(dut.io.commit(0).valid.toBoolean, "Op3 should be valid for commit if not flushed")
        assert(!dut.io.commit(0).entry.status.hasException.toBoolean, "Op3 should not have exception")
        driveCommitFire(dut.io, Seq(true));
        dut.clockDomain.waitSampling(); sleep(1)
        assert(dut.io.empty.toBoolean, "ROB should be empty after committing Op3")
      } else if (c_after_exc == 0) {
        println("[TB] ROB is empty after Op2 exception commit, as expected if flush occurred.")
        assert(dut.io.empty.toBoolean, "ROB should be empty if Op2 caused full flush on commit")
      } else {
        println(
          s"[TB] ROB state after Op2 commit is unexpected for Op3 check. Head is $h_after_exc (expected $robPtrOp3 if Op3 is next)."
        )
      }

      driveCommitFire(dut.io, Seq(false)) 
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Pointer Wrapping Behavior") {
    val robDepth = 8 
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      commitWidth = 1,
      numWritebackPorts = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 305) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      var pc = 0
      var allocatedFullRobIndices = ArrayBuffer[BigInt]() 
      var expectedNextFullRobPtr = BigInt(0)

      println("--- Filling ROB to wrap tail ---")
      for (i <- 0 until testConfig.robDepth + 2) { 
        val canAllocNow = dut.io.canAllocate(0).toBoolean
        if (canAllocNow) {
          driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = pc), pc)))
          val allocatedFullId = dut.io.allocate(0).robPtr.toBigInt
          assert(allocatedFullId == expectedNextFullRobPtr, s"Alloc iter $i, Full ID mismatch. Expected $expectedNextFullRobPtr, got $allocatedFullId")
          allocatedFullRobIndices += allocatedFullId
          expectedNextFullRobPtr += 1
          pc += 4
        } else {
          driveAllocate(dut.io, Seq.empty) 
          ParallaxLogger.log(s"ROB full at iter $i, cannot allocate.")
        }
        dut.clockDomain.waitSampling(); sleep(1)
        val (h, t, c) = getInternalRobPointers(dut)
        println(
          s"Iter $i: Alloc. Head=${h}, Tail=${t} (PhysT=${getPhysIdx(t, robPhysIdxWidth)}), Count=${c}. Last Alloc Full Idx: ${if (canAllocNow) allocatedFullRobIndices.last else -1}"
        )
        // 当分配了 robDepth 个条目后，下一个分配的物理索引应该是0，但世代位会翻转
        if (i == testConfig.robDepth - 1 && canAllocNow) {
          // 此时 tail 应该是 (robDepth-1) 的物理索引，下一条会绕回
        }
      }
      driveAllocate(dut.io, Seq.empty)
      val (h1, t1, c1) = getInternalRobPointers(dut)
      assert(c1 == testConfig.robDepth, "ROB should be full")
      // 当满时，tail 指向下一个可用槽的完整 ID，其物理部分应该等于 head 的物理部分
      assert(getPhysIdx(t1, robPhysIdxWidth) == getPhysIdx(h1, robPhysIdxWidth), s"Tail (phys) should meet head (phys) when full. T_phys=${getPhysIdx(t1, robPhysIdxWidth)}, H_phys=${getPhysIdx(h1, robPhysIdxWidth)}")
      // 并且它们的世代位应该不同
      assert(getGenBit(t1, robPhysIdxWidth) != getGenBit(h1, robPhysIdxWidth), "Tail gen bit should differ from head gen bit when full and wrapped.")


      println("--- Phase 2: Writeback all ---")
      // 只有 ROB 内部的条目才需要写回
      allocatedFullRobIndices.take(testConfig.robDepth).foreach { fullIdx => 
        driveWriteback(dut.io, Seq((true, fullIdx, false, 0)))
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveWriteback(dut.io, Seq.empty)

      println("--- Phase 3: Commit all to wrap head ---")
      var initialTailAfterFill = t1
      for (i <- 0 until testConfig.robDepth) {
        dut.clockDomain.waitSampling(0); sleep(1)
        assert(dut.io.commit(0).valid.toBoolean, s"Commit iter $i: Expected valid. Head ${dut.rob.headPtr_reg.toBigInt}")
        driveCommitFire(dut.io, Seq(true))
        dut.clockDomain.waitSampling(); sleep(1)
        val (h, t, c) = getInternalRobPointers(dut)
        ParallaxLogger.log(s"Iter $i: Commit. Head=$h (PhysH=${getPhysIdx(h,robPhysIdxWidth)}), Tail=$t, Count=$c")
        if (i == testConfig.robDepth - 1) {
          // 提交完所有条目后，head 应该等于填充满了时的 tail
          assert(h == initialTailAfterFill, s"Head should wrap to meet initial tail $initialTailAfterFill, got $h")
        }
      }
      driveCommitFire(dut.io, Seq(false))
      val (h2, t2, c2) = getInternalRobPointers(dut)
      assert(c2 == 0, "ROB should be empty")
      assert(h2 == t2, "Head and tail should match when empty")

      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - Concurrent Allocate, Writeback, Commit (Single Cycle Stress)") {
    val robDepth = 8 // 使用较小的 ROB 深度以便观察指针行为
    val allocW = 2
    val commitW = 2
    val wbW = 2
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = allocW,
      commitWidth = commitW,
      numWritebackPorts = wbW,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False), // 完整的 robPtr 宽度
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value

    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 306) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      // --- 1. Pre-fill ROB ---
      var pcCounter = 0
      var allocatedOpsFull = ArrayBuffer[(BigInt, Int)]() // (fullRobPtr, pc)
      var expectedNextFullRobPtr_prefill = BigInt(0)

      // Allocate 4 ops (ops 0, 1, 2, 3 with fullRobPtr 0, 1, 2, 3)
      val numOpsToPrefill = 4
      assert(numOpsToPrefill % allocW == 0, "numOpsToPrefill should be a multiple of allocW for simplicity")

      ParallaxLogger.log("--- Phase 1: Pre-filling ROB ---")
      for (i <- 0 until numOpsToPrefill / allocW) {
        val uopsForCycle = (0 until allocW).map { j =>
          val currentFullRobPtr = expectedNextFullRobPtr_prefill
          val currentPc = pcCounter
          expectedNextFullRobPtr_prefill += 1
          pcCounter += 4
          (true, createDummyUop(testConfig.robPtrWidth, currentFullRobPtr, pcVal = currentPc), currentPc)
        }
        driveAllocate(dut.io, uopsForCycle)
        for (k <- 0 until allocW) {
            val allocatedFullId = dut.io.allocate(k).robPtr.toBigInt
            allocatedOpsFull += ((allocatedFullId, uopsForCycle(k)._3))
        }
        dut.clockDomain.waitSampling(); sleep(1)
      }
      driveAllocate(dut.io, Seq.empty) // Stop allocation
      val (h_pre, t_pre, c_pre) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"Pre-fill: Allocated ${allocatedOpsFull.length} ops. H=$h_pre, T=$t_pre, C=$c_pre. Indices: ${allocatedOpsFull.map(_._1).mkString(",")}")
      assert(c_pre == numOpsToPrefill, s"Count after pre-fill should be $numOpsToPrefill, got $c_pre")
      // expectedNextFullRobPtr_prefill 此时是下一个可分配的 fullRobPtr

      // Mark first 'commitW' ops as done for commit in the concurrent cycle
      // These are allocatedOpsFull(0) and allocatedOpsFull(1)
      val opsToMarkDone = allocatedOpsFull.take(commitW)
      ParallaxLogger.log(s"--- Phase 1.1: Marking first ${commitW} ops as done for commit: ${opsToMarkDone.map(_._1).mkString(",")} ---")
      val wbReqsForDone = opsToMarkDone.map { case (fullRobPtr, _) => (true, fullRobPtr, false, 0) }
      driveWriteback(dut.io, wbReqsForDone)
      dut.clockDomain.waitSampling(); sleep(1) // Writeback takes effect
      driveWriteback(dut.io, Seq.empty) // Stop writeback

      // Verify they are done (optional, but good for sanity)
      opsToMarkDone.foreach { case (fullRobPtr, _) =>
        val physIdx = getPhysIdx(fullRobPtr, robPhysIdxWidth)
        val genBit = getGenBit(fullRobPtr, robPhysIdxWidth)
        assert(dut.rob.statuses(physIdx).done.toBoolean, s"ROB entry fullId $fullRobPtr (phys $physIdx) should be done.")
        assert(dut.rob.statuses(physIdx).genBit.toBoolean == genBit, s"ROB entry fullId $fullRobPtr (phys $physIdx) genBit mismatch.")
      }

      // --- 2. Concurrent Operations Cycle ---
      ParallaxLogger.log("--- Phase 2: Concurrent Operations Cycle ---")
      // Get state before concurrent ops are driven
      val (h_before_concurrent, t_before_concurrent, c_before_concurrent) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"State BEFORE driving concurrent ops: H=$h_before_concurrent, T=$t_before_concurrent, C=$c_before_concurrent")


      // Setup Commit for the ops marked done (opsToMarkDone)
      // These are at the head of the ROB
      ParallaxLogger.log(s"Driving Commit for ${commitW} ops (expected fullRobPtr: ${allocatedOpsFull.take(commitW).map(_._1).mkString(",")})")
      driveCommitFire(dut.io, Seq.fill(commitW)(true)) // This helper includes sleep(1) - NO, it's a set and then one sleep

      // Setup Allocate for new ops
      var expectedNextFullRobPtr_alloc = t_before_concurrent // New allocations start from current tail
      val newPcBase = 800
      val allocReqs = (0 until allocW).map { i =>
        val currentFullRobPtr = expectedNextFullRobPtr_alloc
        val currentPc = newPcBase + i * 4
        expectedNextFullRobPtr_alloc += 1
        (true, createDummyUop(testConfig.robPtrWidth, currentFullRobPtr, pcVal = currentPc), currentPc)
      }
      ParallaxLogger.log(s"Driving Allocate for ${allocW} new ops (expected starting fullRobPtr: ${t_before_concurrent})")
      // driveAllocate(dut.io, allocReqs) // This helper includes sleep(1) - NO
      setAllocateInputs(dut.io, allocReqs) // Use set, no sleep

      // Setup Writeback for the remaining pre-filled ops
      // These are allocatedOpsFull(commitW) to allocatedOpsFull(numOpsToPrefill - 1)
      val opsForWbConcurrent = allocatedOpsFull.slice(commitW, numOpsToPrefill)
      ParallaxLogger.log(s"Driving Writeback for ${opsForWbConcurrent.length} ops: ${opsForWbConcurrent.map(_._1).mkString(",")}")
      val wbReqsConcurrent = opsForWbConcurrent.map { case (fullRobPtr, _) => (true, fullRobPtr, false, 0) }
      // driveWriteback(dut.io, wbReqsConcurrent) // This helper includes sleep(1) - NO
      // Manually drive writeback inputs without sleep
      for (i <- 0 until dut.io.config.numWritebackPorts) {
        if (i < wbReqsConcurrent.length) {
          dut.io.writeback(i).fire #= wbReqsConcurrent(i)._1
          dut.io.writeback(i).robPtr #= wbReqsConcurrent(i)._2
          dut.io.writeback(i).exceptionOccurred #= wbReqsConcurrent(i)._3
          dut.io.writeback(i).exceptionCodeIn #= wbReqsConcurrent(i)._4
        } else {
          dut.io.writeback(i).fire #= false
        }
      }


      // --- Combinational Checks (Before the clock edge) ---
      // Check commit.valid based on the state *before* this cycle's clock edge
      // The first commitW ops (0 and 1) should be valid for commit
      for (i <- 0 until commitW) {
        assert(dut.io.commit(i).valid.toBoolean, s"Concurrent: commit($i).valid should be true (for pre-filled op ${allocatedOpsFull(i)._1})")
        val committedEntry = dut.io.commit(i).entry
        val expectedFullRobPtrAtCommitSlot = h_before_concurrent + i
        val physIdx = getPhysIdx(expectedFullRobPtrAtCommitSlot, robPhysIdxWidth)
        val genBit = getGenBit(expectedFullRobPtrAtCommitSlot, robPhysIdxWidth)
        assert(committedEntry.payload.uop.robPtr.toBigInt == expectedFullRobPtrAtCommitSlot, s"Commit slot $i Full ROB ID mismatch")
        assert(dut.rob.statuses(physIdx).done.toBoolean, s"Commit slot $i (phys $physIdx) should be done")
        assert(dut.rob.statuses(physIdx).genBit.toBoolean == genBit, s"Commit slot $i (phys $physIdx) genBit mismatch")
        ParallaxLogger.log(s"Concurrent: Commit slot $i valid for FullRobPtr ${expectedFullRobPtrAtCommitSlot}, PC ${committedEntry.payload.pc.toBigInt}")
      }

      // Check canAllocate based on the state *before* this cycle's clock edge (but considering potential commits)
      // If c_before_concurrent - commitW + allocW <= robDepth, then canAllocate should be true
      val countAfterPotentialCommit = c_before_concurrent - commitW
      for (i <- 0 until allocW) {
        val canAlloc = (countAfterPotentialCommit + i) < testConfig.robDepth
        assert(dut.io.canAllocate(i).toBoolean == canAlloc, s"Concurrent: canAllocate($i) expected $canAlloc. Count Before=${c_before_concurrent}, Commits=${commitW}, AllocsSoFar=${i}")
      }
      val newAllocatedFullRobPtrs = dut.io.allocate.takeWhile(_.fire.toBoolean).map(_.robPtr.toBigInt)
      ParallaxLogger.log(s"Concurrent: Allocating to Full ROB indices (combinational): ${newAllocatedFullRobPtrs.mkString(",")}")


      // --- THE BIG CLOCK EDGE ---
      ParallaxLogger.log("--- Applying Concurrent Clock Edge ---")
      dut.clockDomain.waitSampling() // All inputs take effect on ROB internal registers
      sleep(1) // Let combinational outputs propagate from new register values

      // De-assert all inputs for the next cycle
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling(); sleep(1)

      // --- 3. Verification ---
      val (h_post, t_post, c_post) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"State AFTER Concurrent Cycle: H=$h_post (PhysH=${getPhysIdx(h_post, robPhysIdxWidth)}), T=$t_post (PhysT=${getPhysIdx(t_post, robPhysIdxWidth)}), C=$c_post")

      // Expected state:
      // Count = c_before_concurrent - commitW + (number of actual new allocations)
      // Head = h_before_concurrent + commitW
      // Tail = t_before_concurrent + (number of actual new allocations)
      // Number of actual new allocations depends on canAllocate in the concurrent cycle.
      // In this setup, canAllocate should allow all 'allocW' new allocations.
      val numActuallyAllocated = allocW 

      val expected_c_post = c_before_concurrent - commitW + numActuallyAllocated
      val expected_h_post = (h_before_concurrent + commitW) // Full ID arithmetic handles wrap
      val expected_t_post = (t_before_concurrent + numActuallyAllocated) // Full ID arithmetic handles wrap

      assert(c_post == expected_c_post, s"Count after concurrent ops. Expected $expected_c_post, got $c_post")
      assert(h_post == expected_h_post, s"Head pointer after concurrent. Expected $expected_h_post, got $h_post")
      assert(t_post == expected_t_post, s"Tail pointer after concurrent. Expected $expected_t_post, got $t_post")

      // Verify that the ops that were meant to be written back concurrently are now 'done'
      ParallaxLogger.log("--- Verifying concurrently written-back ops ---")
      opsForWbConcurrent.foreach { case (fullRobPtr, pc) =>
        val physIdx = getPhysIdx(fullRobPtr, robPhysIdxWidth)
        val genBit = getGenBit(fullRobPtr, robPhysIdxWidth)
        assert(dut.rob.statuses(physIdx).done.toBoolean, s"ROB entry fullId $fullRobPtr (phys $physIdx, pc $pc) should be done after concurrent WB.")
        assert(dut.rob.statuses(physIdx).genBit.toBoolean == genBit, s"ROB entry fullId $fullRobPtr (phys $physIdx) genBit mismatch after WB.")
         ParallaxLogger.log(s"Verified concurrently WB op: FullID=$fullRobPtr, PC=$pc, PhysIdx=$physIdx, StoredGen=${dut.rob.statuses(physIdx).genBit.toBoolean}, ExpectedGen=$genBit, Done=${dut.rob.statuses(physIdx).done.toBoolean}")
      }
      
      // Verify that the newly allocated ops have correct initial status (busy=T, done=F, correct genBit)
      ParallaxLogger.log(s"--- Verifying newly allocated ops (expected full IDs from ${t_before_concurrent} to ${expected_t_post-1}) ---")
      var checkFullId = t_before_concurrent
      for(i <- 0 until numActuallyAllocated){
          val physIdx = getPhysIdx(checkFullId, robPhysIdxWidth)
          val genBit = getGenBit(checkFullId, robPhysIdxWidth)
          assert(dut.rob.statuses(physIdx).busy.toBoolean, s"Newly allocated op fullId $checkFullId (phys $physIdx) should be busy.")
          assert(!dut.rob.statuses(physIdx).done.toBoolean, s"Newly allocated op fullId $checkFullId (phys $physIdx) should not be done.")
          assert(dut.rob.statuses(physIdx).genBit.toBoolean == genBit, s"Newly allocated op fullId $checkFullId (phys $physIdx) genBit mismatch. Stored=${dut.rob.statuses(physIdx).genBit.toBoolean}, Expected=${genBit}")
          ParallaxLogger.log(s"Verified new op: FullID=$checkFullId, PhysIdx=$physIdx, StoredGen=${dut.rob.statuses(physIdx).genBit.toBoolean}, ExpectedGen=$genBit, Busy=${dut.rob.statuses(physIdx).busy.toBoolean}")
          checkFullId += 1
      }


      dut.clockDomain.waitSampling(10)
    }
  }

  test("ROB - FULL_FLUSH - Partially Filled") {
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 400) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      // 1. Allocate 3 entries
      val numToAlloc = 3
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until numToAlloc) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 100 + i * 4), 100 + i * 4)))
        expectedNextFullRobPtr += 1
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io) 
      dut.clockDomain.waitSampling()

      var (h, t, c) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After alloc: H=$h, T=$t, C=$c")
      assert(c == numToAlloc, s"Count should be $numToAlloc, got $c")
      assert(t == expectedNextFullRobPtr, s"Tail should be $expectedNextFullRobPtr, got $t")
      assert(!dut.io.empty.toBoolean)

      // 2. Drive FULL_FLUSH
      driveFlushNew(dut.io, fire = true, reason = FlushReason.FULL_FLUSH)
      dut.clockDomain.waitSampling() 

      // 3. Check io.flushed and ROB state
      assert(dut.io.flushed.toBoolean, "io.flushed should be true after FULL_FLUSH command")
      initRobInputs(dut.io) 
      dut.clockDomain.waitSampling() 

      val (h2, t2, c2) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After FULL_FLUSH: H=$h2, T=$t2, C=$c2")
      assert(h2 == 0, s"Head should be 0 after FULL_FLUSH, got $h2")
      assert(t2 == 0, s"Tail should be 0 after FULL_FLUSH, got $t2")
      assert(c2 == 0, s"Count should be 0 after FULL_FLUSH, got $c2")
      assert(dut.io.empty.toBoolean, "ROB should be empty after FULL_FLUSH")
      assert(!dut.io.flushed.toBoolean, "io.flushed should be false in the cycle after flush command")
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - FULL_FLUSH - Completely Full") {
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 401) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      // 1. Fill the ROB
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until testConfig.robDepth) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 100 + i * 4), 100 + i * 4)))
        expectedNextFullRobPtr += 1
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      var (h, t, c) = getInternalRobPointers(dut)
      assert(c == testConfig.robDepth, s"Count should be ${testConfig.robDepth}, got $c")
      assert(t == expectedNextFullRobPtr, s"Tail should be $expectedNextFullRobPtr, got $t")


      // 2. Drive FULL_FLUSH
      driveFlushNew(dut.io, fire = true, reason = FlushReason.FULL_FLUSH)
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      // 3. Check
      assert(dut.io.flushed.toBoolean, "io.flushed should be true")
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      val (h2, t2, c2) = getInternalRobPointers(dut)

      assert(h2 == 0 && t2 == 0 && c2 == 0, "ROB should be empty after FULL_FLUSH from full state")
      assert(dut.io.empty.toBoolean)
      assert(!dut.io.flushed.toBoolean)
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - ROLLBACK_TO_ROB_IDX - Simple No Wrap") {
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 402) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      // 1. Allocate 5 entries (full IDs 0, 1, 2, 3, 4)
      // Head=0, Tail=5, Count=5
      val numToAlloc = 5
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until numToAlloc) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 100 + i * 4), 100 + i * 4)))
        expectedNextFullRobPtr += 1
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      var (h, t, c) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"Before rollback: H=$h, T=$t, C=$c")
      assert(c == numToAlloc)
      assert(t == expectedNextFullRobPtr)

      // 2. Rollback to full ID 2. Entries 0, 1 should remain. New tail=2, new count=2.
      val targetFullIdx = BigInt(2)
      driveFlushNew(dut.io, fire = true, reason = FlushReason.ROLLBACK_TO_ROB_IDX, targetRobPtr = targetFullIdx)
      dut.clockDomain.waitSampling()

      // 3. Check
      assert(dut.io.flushed.toBoolean, "io.flushed should be true")
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      val (h2, t2, c2) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After rollback to $targetFullIdx: H=$h2, T=$t2, C=$c2")
      assert(h2 == 0, s"Head should be 0, got $h2")
      assert(t2 == targetFullIdx, s"Tail should be $targetFullIdx, got $t2")
      assert(c2 == targetFullIdx - h2, s"Count should be ${targetFullIdx - h2}, got $c2") 
      assert(!dut.io.flushed.toBoolean)
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - ROLLBACK_TO_ROB_IDX - To Empty (target is head)") {
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 403) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      // 1. Allocate 3 entries. Head=0, Tail=3, Count=3
      val numToAlloc = 3
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until numToAlloc) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 100 + i * 4), 100 + i * 4)))
        expectedNextFullRobPtr += 1
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      var (h_before, t_before, c_before) = getInternalRobPointers(dut)
      assert(c_before == numToAlloc)
      assert(t_before == expectedNextFullRobPtr)

      // 2. Rollback to index 0 (current head). ROB should become empty.
      val targetFullIdx = h_before 
      driveFlushNew(dut.io, fire = true, reason = FlushReason.ROLLBACK_TO_ROB_IDX, targetRobPtr = targetFullIdx)
      dut.clockDomain.waitSampling()

      // 3. Check
      assert(dut.io.flushed.toBoolean)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      val (h, t, c) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After rollback to head ($targetFullIdx): H=$h, T=$t, C=$c")
      assert(h == h_before, s"Head should remain $h_before, got $h")
      assert(t == targetFullIdx, s"Tail should be $targetFullIdx, got $t")
      assert(c == 0, s"Count should be 0, got $c")
      assert(dut.io.empty.toBoolean)
      assert(!dut.io.flushed.toBoolean)
      dut.clockDomain.waitSampling(5)
    }
  }

  test("ROB - ROLLBACK_TO_ROB_IDX - With Pointer Wrap") {
    // ROB Depth = 8. AllocW=1, CommitW=1
    // 1. Alloc 6 (full IDs 0,1,2,3,4,5). H=0, T=6, C=6
    // 2. Commit 4 (full IDs 0,1,2,3). H=4, T=6, C=2. Entries in ROB: (full IDs) 4, 5
    // 3. Alloc 5 more (full IDs 6,7,8,9,10).
    //    - Alloc full ID 6 (ROB entry 6). H=4, T=7, C=3. Entries: 4,5,6
    //    - Alloc full ID 7 (ROB entry 7). H=4, T=8, C=4. Entries: 4,5,6,7
    //    - Alloc full ID 8 (ROB entry 0, gen 1). H=4, T=9, C=5. Entries: 4,5,6,7,8
    //    - Alloc full ID 9 (ROB entry 1, gen 1). H=4, T=10, C=6. Entries: 4,5,6,7,8,9
    //    - Alloc full ID 10 (ROB entry 2, gen 1). H=4, T=11, C=7. Entries: 4,5,6,7,8,9,10
    //    Current state: Head=4 (phys 4, gen 0), Tail=11 (phys 3, gen 1), Count=7.
    //    Valid ROB full IDs: 4, 5, 6, 7, 8, 9, 10 (in logical order)
    // 4. Rollback to Full ROB ID 7 (targetRobPtr=7).
    //    Expected: Head=4, Tail=7, Count=3. Entries 4,5,6 remain.
    //    Entries 7,8,9,10 are flushed.
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      commitWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 404) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      var pc = 0
      var allocatedFullRobIndices = ArrayBuffer[BigInt]() 
      var expectedNextFullRobPtr = BigInt(0)

      println("--- Phase 1: Alloc 6 ---")
      for (i <- 0 until 6) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = pc), pc)))
        val allocatedFullId = dut.io.allocate(0).robPtr.toBigInt
        assert(allocatedFullId == expectedNextFullRobPtr)
        allocatedFullRobIndices += allocatedFullId
        expectedNextFullRobPtr += 1
        pc += 4
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io); dut.clockDomain.waitSampling()
      var (h_alloc6, t_alloc6, c_alloc6) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After alloc 6: H=$h_alloc6, T=$t_alloc6, C=$c_alloc6. Indices: ${allocatedFullRobIndices.mkString(",")}")
      assert(c_alloc6 == 6 && h_alloc6 == 0 && t_alloc6 == 6)

      println("--- Phase 2: Commit 4 ---")
      for (i <- 0 until 4) {
        driveWriteback(dut.io, Seq((true, allocatedFullRobIndices(i), false, 0)))
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io); dut.clockDomain.waitSampling()
      for (i <- 0 until 4) {
        assert(dut.io.commit(0).valid.toBoolean)
        driveCommitFire(dut.io, Seq(true))
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io); dut.clockDomain.waitSampling()
      val (h_commit4, t_commit4, c_commit4) = getInternalRobPointers(dut)
      // Head should be 4 (full ID), Tail 6 (full ID), Count 2
      // Phys head = 4, Gen head = 0. Phys tail = 6, Gen tail = 0.
      ParallaxLogger.log(s"After commit 4: H=$h_commit4 (PhysH=${getPhysIdx(h_commit4,robPhysIdxWidth)}), T=$t_commit4 (PhysT=${getPhysIdx(t_commit4,robPhysIdxWidth)}), C=$c_commit4")
      assert(c_commit4 == 2 && h_commit4 == 4 && t_commit4 == 6) 

      println("--- Phase 3: Alloc 5 more (to cause wrap) ---")
      // Expected full IDs for allocation: 6, 7, 8, 9, 10
      // Phys IDs: 6, 7, 0 (gen 1), 1 (gen 1), 2 (gen 1)
      val allocTargetFullIds = Seq(BigInt(6), BigInt(7), BigInt(8), BigInt(9), BigInt(10))
      for (i <- 0 until 5) {
        val targetAllocFullId = allocTargetFullIds(i)
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, targetAllocFullId, pcVal = pc), pc)))
        pc += 4
        dut.clockDomain.waitSampling()
        val allocatedFullId = dut.io.allocate(0).robPtr.toBigInt
        assert(allocatedFullId == targetAllocFullId)
        val (h_alloc, t_alloc, c_alloc) = getInternalRobPointers(dut)
        ParallaxLogger.log(s"Alloc iter ${6 + i}: H=$h_alloc, T=$t_alloc (PhysT=${getPhysIdx(t_alloc,robPhysIdxWidth)}), C=$c_alloc. ROB Full Idx: ${allocatedFullId}")
      }
      initRobInputs(dut.io); dut.clockDomain.waitSampling()
      val (h_after_wrap, t_after_wrap, c_after_wrap) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After alloc 5 more: H=$h_after_wrap (PhysH=${getPhysIdx(h_after_wrap,robPhysIdxWidth)}), T=$t_after_wrap (PhysT=${getPhysIdx(t_after_wrap,robPhysIdxWidth)}), C=$c_after_wrap")
      // Expected: H=4 (gen0, phys4), T=11 (gen1, phys3), C=7
      assert(h_after_wrap == 4, s"Head should be 4, got $h_after_wrap")
      assert(t_after_wrap == 11, s"Tail should be 11 (full ID), got $t_after_wrap") 
      assert(getPhysIdx(t_after_wrap, robPhysIdxWidth) == 3, "Tail phys idx should be 3")
      assert(getGenBit(t_after_wrap, robPhysIdxWidth), "Tail gen bit should be 1")
      assert(c_after_wrap == 7, s"Count should be 7, got $c_after_wrap")

      // Current logical entries (full IDs): 4, 5, 6, 7, 8, 9, 10
      // Physical indices: 4, 5, 6, 7, 0(gen1), 1(gen1), 2(gen1)

      println("--- Phase 4: Rollback to Full ROB ID 7 ---")
      // We want to keep entries with Full ID 4, 5, 6. Target for new tail is Full ID 7.
      val targetFullIdxForRollback = BigInt(7) 
      driveFlushNew(dut.io, fire = true, reason = FlushReason.ROLLBACK_TO_ROB_IDX, targetRobPtr = targetFullIdxForRollback)
      dut.clockDomain.waitSampling()

      assert(dut.io.flushed.toBoolean)
      initRobInputs(dut.io); dut.clockDomain.waitSampling()

      val (h_final, t_final, c_final) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"After rollback to Full ID $targetFullIdxForRollback: H=$h_final (PhysH=${getPhysIdx(h_final,robPhysIdxWidth)}), T=$t_final (PhysT=${getPhysIdx(t_final,robPhysIdxWidth)}), C=$c_final")
      // Expected: Head=4 (gen0, phys4), Tail=7 (gen0, phys7), Count=3
      assert(h_final == 4, s"Head should be 4, got $h_final")
      assert(t_final == targetFullIdxForRollback, s"Tail should be $targetFullIdxForRollback, got $t_final")
      assert(c_final == 3, s"Count should be 3, got $c_final") 
      assert(!dut.io.flushed.toBoolean)
      dut.clockDomain.waitSampling(5)
    }
  }
  
  test("ROB - Flush Concurrently with Allocate/Commit") {
    val robDepth = 8
    val testConfig = baseRobConfig.copy(
      robDepth = robDepth,
      allocateWidth = 1,
      commitWidth = 1,
      defaultUop = () => createDummyUop(log2Up(robDepth) + 1 bits, BigInt(0), hasException = False),
      uopType = HardType(DummyUop(log2Up(robDepth) + 1 bits)),
    )
    val robPhysIdxWidth = testConfig.robPhysIdxWidth.value
    simConfig.compile(new ReorderBufferTestBench(testConfig)).doSim(seed = 405) { dut =>
      dut.clockDomain.forkStimulus(10)
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()

      // 1. Pre-fill: Alloc 4 ops (full IDs 0,1,2,3). Mark op 0 done.
      // State: H=0, T=4, C=4. Op 0 is committable.
      var expectedNextFullRobPtr = BigInt(0)
      for (i <- 0 until 4) {
        driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 100 + i * 4), 100 + i * 4)))
        expectedNextFullRobPtr += 1
        dut.clockDomain.waitSampling()
      }
      initRobInputs(dut.io)
      val seq = Seq((true, BigInt(0), false, 0)) // Mark op at ROB full ID 0 done
      ParallaxLogger.log("seq length " + seq.length.toString())
      assert(getInternalRobPointers(dut)._3 == 4)
      driveWriteback(dut.io, seq) 
      dut.clockDomain.waitSampling()
      initRobInputs(dut.io)
      dut.clockDomain.waitSampling()
      var (h_pre, t_pre, c_pre) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"Pre-concurrent: H=$h_pre, T=$t_pre, C=$c_pre")
      assert(c_pre == 4)
      assert(dut.io.commit(0).valid.toBoolean, "Op 0 should be committable")

      // 2. Concurrent operations in one cycle:
      //    - Drive FULL_FLUSH
      //    - Drive allocate for a new op (full ID 4)
      //    - Drive commit fire for op 0
      println("--- Driving Concurrent Flush, Alloc, Commit ---")
      driveFlushNew(dut.io, fire = true, reason = FlushReason.FULL_FLUSH)
      driveAllocate(dut.io, Seq((true, createDummyUop(testConfig.robPtrWidth, expectedNextFullRobPtr, pcVal = 200), 200)))
      driveCommitFire(dut.io, Seq(true)) 

      dut.clockDomain.waitSampling() 

      // 3. Check results: Flush should take precedence
      assert(dut.io.flushed.toBoolean, "io.flushed should be true due to FULL_FLUSH")
      initRobInputs(dut.io) 
      dut.clockDomain.waitSampling()

      val (h_post, t_post, c_post) = getInternalRobPointers(dut)
      ParallaxLogger.log(s"Post-concurrent: H=$h_post, T=$t_post, C=$c_post")
      assert(h_post == 0, "Head should be 0 due to FULL_FLUSH")
      assert(t_post == 0, "Tail should be 0 due to FULL_FLUSH")
      assert(c_post == 0, "Count should be 0 due to FULL_FLUSH")
      assert(dut.io.empty.toBoolean)
      assert(!dut.io.flushed.toBoolean, "io.flushed should be false in the cycle after flush")

      dut.clockDomain.waitSampling(5)
    }
  }

  thatsAll()
}
