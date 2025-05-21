package parallax.test.rename

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import parallax.common._
import parallax.issue._ // Assuming RenamedUop might be here or common
import parallax.components.rename._ // RenameUnit, RenameUnitIo
import parallax.components.decode._ // DecodedUop, InstructionOpcodesBigInts
import parallax.utilities.{Framework, ParallaxLogger, ProjectScope}

import scala.collection.mutable
import scala.util.Random

class RenameUnitTestBench(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Component {
  val io = slave(RenameUnitIo(pipelineConfig, ratConfig, flConfig)) // Testbench is slave to RenameUnit's master IOs

  val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
  renameUnit.io <> io // Connect RenameUnit to Testbench IOs for driving/monitoring

  // SimPublics for easier wave viewing
  io.simPublic()
  renameUnit.io.simPublic() // Also make internal RenameUnit IOs public if needed
  // renameUnit.stallLackingResources.simPublic() // 如果想看内部stall信号
}

class RenameUnitSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32

  // Default configs for testing
  // -- MODIFICATION START (Parameterize archGprCount) --
  def defaultPipelineConfig(renameWidth: Int = 1, numPhysRegs: Int = 16, archGprCount: Int = 8) = PipelineConfig(
    xlen = XLEN,
    renameWidth = renameWidth,
    archGprCount = archGprCount, // Now uses the parameter
    physGprCount = numPhysRegs // Physical register quantity
  )
  // -- MODIFICATION END --

  def defaultRatConfig(archRegs: Int, physRegs: Int, renameWidth: Int) = RenameMapTableConfig(
    archRegCount = archRegs,
    physRegCount = physRegs,
    numReadPorts = renameWidth * 3, // Assuming each uop has up to 2 sources + 1 old destination
    numWritePorts = renameWidth // Each uop has up to 1 destination
  )

  def defaultFlConfig(physRegs: Int, renameWidth: Int) = SuperScalarFreeListConfig(
    numPhysRegs = physRegs,
    resetToFull = true,
    numAllocatePorts = renameWidth,
    numFreePorts = renameWidth // Assuming free ports match allocate ports
  )

  // Helper to create DecodedUop for testing
  def createDecodedUop(
      pc: Long,
      isValid: Boolean,
      uopCode: BaseUopCode.E,
      exeUnit: ExeUnitType.E,
      writeArchDestEn: Boolean = false,
      archDestIdx: Int = 0,
      archDestType: ArchRegType.E = ArchRegType.GPR,
      useArchSrc1: Boolean = false,
      archSrc1Idx: Int = 0,
      archSrc1Type: ArchRegType.E = ArchRegType.GPR,
      useArchSrc2: Boolean = false,
      archSrc2Idx: Int = 0,
      archSrc2Type: ArchRegType.E = ArchRegType.GPR,
      cfg: PipelineConfig
  ): DecodedUop = {
    val uop = DecodedUop(cfg)
    uop.setDefaultForSim() // Start with defaults
    uop.pc #= pc
    uop.isValid #= isValid
    uop.uopCode #= uopCode
    uop.exeUnit #= exeUnit

    uop.writeArchDestEn #= writeArchDestEn
    if (writeArchDestEn) {
      uop.archDest.idx #= archDestIdx
      uop.archDest.rtype #= archDestType
    }
    uop.useArchSrc1 #= useArchSrc1
    if (useArchSrc1) {
      uop.archSrc1.idx #= archSrc1Idx
      uop.archSrc1.rtype #= archSrc1Type
    }
    uop.useArchSrc2 #= useArchSrc2
    if (useArchSrc2) {
      uop.archSrc2.idx #= archSrc2Idx
      uop.archSrc2.rtype #= archSrc2Type
    }
    uop
  }

  // --- Mocked RAT and FreeList Logic (within the test) ---
  class MockRat(ratCfg: RenameMapTableConfig, clockDomain: ClockDomain) {
    val mapping = Array.fill(ratCfg.archRegCount)(0) // phys reg 0 is special (always maps to arch 0)
    for (i <- 1 until ratCfg.archRegCount) mapping(i) = i // Initial mapping rX -> pX

    def process(dutIo: RenameUnitIo): Unit = {
      // Read ports (combinational read of current state)
      for (i <- 0 until ratCfg.numReadPorts) {
        val archReg = dutIo.ratReadPorts(i).archReg.toInt
        if (archReg < ratCfg.archRegCount) {
          dutIo.ratReadPorts(i).physReg #= mapping(archReg)
        } else {
          dutIo.ratReadPorts(i).physReg #= 0 // Default or error value
        }
      }
      // Write ports (writes will be visible in the *next* cycle's mapping array)
      // This needs to be called *after* dut's logic for the cycle has stabilized
      // or use a separate thread/fork to apply writes with 1 cycle delay.
      // For simplicity here, we can collect write requests and apply them at end of cycle.
    }
    def applyWrites(dutIo: RenameUnitIo): Unit = {
      for (i <- 0 until ratCfg.numWritePorts) {
        if (dutIo.ratWritePorts(i).wen.toBoolean) {
          val archReg = dutIo.ratWritePorts(i).archReg.toInt
          val physReg = dutIo.ratWritePorts(i).physReg.toInt
          if (archReg != 0 && archReg < ratCfg.archRegCount) { // Don't write to r0
            println(s"[MockRat] Write: Arch $archReg -> Phys $physReg")
            mapping(archReg) = physReg
          }
        }
      }
    }
  }

  class MockFreeList(flCfg: SuperScalarFreeListConfig, clockDomain: ClockDomain) {
    val freePool = mutable.Queue[Int]()
    // Initialize: p0 is never free, p1 to p(numPhysRegs-1) are initially free
    for (i <- 1 until flCfg.numPhysRegs) freePool.enqueue(i)

    def process(dutIo: RenameUnitIo): Unit = {
      dutIo.numFreePhysRegs #= freePool.size
      for (i <- 0 until flCfg.numAllocatePorts) {
        if (dutIo.flAllocate(i).enable.toBoolean) {
          if (freePool.nonEmpty) {
            val allocatedReg = freePool.dequeue()
            dutIo.flAllocate(i).success #= true
            dutIo.flAllocate(i).physReg #= allocatedReg
            println(s"[MockFreeList] Allocated p$allocatedReg for port $i. Pool size now: ${freePool.size}")
          } else {
            dutIo.flAllocate(i).success #= false
            dutIo.flAllocate(i).physReg #= 0 // Or some invalid indicator
            println(s"[MockFreeList] Allocation failed for port $i (pool empty).")
          }
        } else {
          dutIo.flAllocate(i).success #= false // Not enabled, so not successful by default
          dutIo.flAllocate(i).physReg #= 0
        }
      }
    }
    def addFreedReg(physReg: Int): Unit = { // Called by test to simulate commit/flush freeing regs
      if (physReg != 0 && !freePool.contains(physReg)) freePool.enqueue(physReg)
    }
  }

  // Helper functions defined inside the test class, accessible by all tests
  def readArchReg(dutTb: RenameUnitTestBench, portIdx: Int, archReg: Int): Unit = {
    dutTb.io.ratReadPorts(portIdx).archReg #= archReg
    sleep(1)
  }

  def expectPhysReg(dutTb: RenameUnitTestBench, portIdx: Int, expectedPhysReg: Int, msg: String = ""): Unit = {
    assert(
      dutTb.io.ratReadPorts(portIdx).physReg.toInt == expectedPhysReg,
      s"ReadPort $portIdx - $msg. Expected $expectedPhysReg, Got ${dutTb.io.ratReadPorts(portIdx).physReg.toInt}"
    )
  }

  def writeMap(dutTb: RenameUnitTestBench, portIdx: Int, wen: Boolean, archReg: Int, physReg: Int): Unit = {
    dutTb.io.ratWritePorts(portIdx).wen #= wen
    dutTb.io.ratWritePorts(portIdx).archReg #= archReg
    dutTb.io.ratWritePorts(portIdx).physReg #= physReg
    sleep(1)
  }

  // Test 1: Single instruction rename
  test("RenameUnit_Single_ADD") {
    val renameWidth = 1
    val numPhys = 8
    val numArch = 8 // Default archGprCount
    // -- MODIFICATION START (Pass archGprCount to defaultPipelineConfig) --
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    // -- MODIFICATION END --
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mockRat = new MockRat(rCfg, dut.clockDomain)
      val mockFl = new MockFreeList(fCfg, dut.clockDomain)

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain) // Randomize input ready

      val renamedUopsOutBuffer = mutable.Queue[Vec[RenamedUop]]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload =>
        renamedUopsOutBuffer.enqueue(payload) // Clone to snapshot the values
      }
      // Consumer is always ready in this test, so manually drive it. DO NOT use StreamReadyRandomizer here.
      dut.io.renamedUopsOut.ready #= true

      // Initial state
      decodedUopsInQueue.clear() // Ensure no pending uops
      dut.io.flushIn #= false
      mockRat.process(dut.io) // Drive RAT reads based on dontcare archRegs
      mockFl.process(dut.io) // Drive numFreePhysRegs and FL alloc responses

      dut.clockDomain.waitSampling()

      // --- Drive ADD r3, r1, r2 ---
      // Initial RAT: r1->p1, r2->p2, r3->p3. Free: p4,p5,p6,p7 (p0 not usable)
      println("[Test ADD] Driving ADD r3, r1, r2")
      val addUop = createDecodedUop(
        0x100,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 3,
        useArchSrc1 = true,
        archSrc1Idx = 1,
        useArchSrc2 = true,
        archSrc2Idx = 2,
        cfg = pCfg
      )
      decodedUopsInQueue.enqueue(op => op := Vec(addUop))

      // Cycle 1: RenameUnit processes inputs
      dut.clockDomain.waitSamplingWhere {
        mockRat.process(dut.io) // Mock RAT reads based on RenameUnit's archReg outputs
        mockFl.process(dut.io) // Mock FL responds to RenameUnit's alloc enables
        renamedUopsOutBuffer.nonEmpty // Wait until RenameUnit produces valid output
      }

      // At this point, RenameUnit has read RAT (for src1, src2, oldDest for r3)
      // and requested allocation from FL for r3's new physReg.
      // FL has responded (e.g., allocated p4).
      // RenameUnit has prepared renamedUopsOut.payload and set valid.

      // Check RenameUnit outputs to RAT/FL (requests)
      assert(dut.io.ratReadPorts(0).archReg.toInt == 1, "RAT read for r1 failed") // src1=r1
      assert(dut.io.ratReadPorts(1).archReg.toInt == 2, "RAT read for r2 failed") // src2=r2
      assert(dut.io.ratReadPorts(2).archReg.toInt == 3, "RAT read for old_r3 failed") // oldDest=r3
      assert(dut.io.flAllocate(0).enable.toBoolean, "FL allocate for r3's dest not enabled")

      // Check renamedUopsOut payload (should be available now)
      // -- MODIFICATION START (Removed .get) --
      val renamedVec = renamedUopsOutBuffer.dequeue().head
      // -- MODIFICATION END --
      println(
        s"Renamed ADD: PC=${renamedVec.decoded.pc.toLong.toHexString} Valid=${renamedVec.decoded.isValid.toBoolean}"
      )
      println(s"  PhysSrc1=${renamedVec.rename.physSrc1.idx.toInt}, PhysSrc2=${renamedVec.rename.physSrc2.idx.toInt}")
      println(
        s"  PhysDest=${renamedVec.rename.physDest.idx.toInt}, OldPhysDest=${renamedVec.rename.oldPhysDest.idx.toInt}, AllocSuc=${renamedVec.rename.allocatesPhysDest.toBoolean}"
      )

      assert(renamedVec.decoded.isValid.toBoolean)
      assert(renamedVec.rename.physSrc1.idx.toInt == 1, "PhysSrc1 for r1 should be p1")
      assert(renamedVec.rename.physSrc2.idx.toInt == 2, "PhysSrc2 for r2 should be p2")
      assert(renamedVec.rename.oldPhysDest.idx.toInt == 3, "OldPhysDest for r3 should be p3")
      assert(renamedVec.rename.allocatesPhysDest.toBoolean, "Should allocate for r3 dest")
      assert(
        renamedVec.rename.physDest.idx.toInt == 4,
        "New PhysDest for r3 should be p4 (first from free pool after p0,p1,p2,p3)"
      )

      // Simulate RAT write for next cycle (RenameUnit issued write this cycle)
      dut.clockDomain.waitSampling() // Advance to next cycle for RAT write to take effect
      mockRat.applyWrites(dut.io) // Apply writes to mock RAT based on RenameUnit's wen from previous cycle
      mockRat.process(dut.io) // Update RAT read port outputs for this new cycle
      mockFl.process(dut.io) // Update FL outputs for this new cycle

      // decodedUopsInQueue will automatically set valid to false if empty.
      // So no need for `dut.io.decodedUopsIn.valid #= false`.

      dut.clockDomain.waitSampling(5)
    }
  }

// === melanjutkan dari RenameUnitSpec ===

  // Test 2: Superscalar rename (renameWidth = 2) - independent instructions
  test("RenameUnit_Dual_Independent") {
    val renameWidth = 2
    val numPhys = 10 // p0, p1..p9
    val numArch = 5 // r0, r1..r4
    // -- MODIFICATION START (Pass archGprCount to defaultPipelineConfig) --
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    // -- MODIFICATION END (Pass archGprCount to defaultPipelineConfig) --
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mockRat = new MockRat(rCfg, dut.clockDomain) // Initial: rX -> pX
      val mockFl = new MockFreeList(fCfg, dut.clockDomain) // Free: p5,p6,p7,p8,p9 (assuming r0-r4 map to p0-p4)
      // Let's adjust MockFreeList for this:
      mockFl.freePool.clear()
      for (i <- numArch until numPhys) mockFl.freePool.enqueue(i) // p5,p6,p7,p8,p9 are free

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[Vec[RenamedUop]]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload =>
        renamedUopsOutBuffer.enqueue(payload)
      }
      dut.io.renamedUopsOut.ready #= true

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      mockRat.process(dut.io)
      mockFl.process(dut.io)
      dut.clockDomain.waitSampling()

      // Drive:
      // Slot 0: ADD r1, r2, r3  (p_new_1 = r2_val + r3_val) -> new p_alloc_0
      // Slot 1: ADD r4, r0, r1  (p_new_4 = r0_val + r1_val_old) -> new p_alloc_1
      val uopsVec = Vec(HardType(DecodedUop(pCfg)), renameWidth) // Create a hardware Vec for temporary assignments
      uopsVec(0) := createDecodedUop(
        0x100,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 1, // r1
        useArchSrc1 = true,
        archSrc1Idx = 2, // r2
        useArchSrc2 = true,
        archSrc2Idx = 3, // r3
        cfg = pCfg
      )
      uopsVec(1) := createDecodedUop(
        0x104,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 4, // r4
        useArchSrc1 = true,
        archSrc1Idx = 0, // r0
        useArchSrc2 = true,
        archSrc2Idx = 1, // r1 (will read old p1, not new p_alloc_0)
        cfg = pCfg
      )

      decodedUopsInQueue.enqueue(op => op := uopsVec)

      dut.clockDomain.waitSamplingWhere {
        mockRat.process(dut.io)
        mockFl.process(dut.io)
        renamedUopsOutBuffer.nonEmpty
      }

      // Check renamedUopsOut payload
      val renamedVecs = renamedUopsOutBuffer.dequeue() // Get the Vec of RenamedUop
      // -- MODIFICATION START (Removed .get) --
      val renamed0 = renamedVecs(0)
      val renamed1 = renamedVecs(1)
      // -- MODIFICATION END --

      // Slot 0 checks
      assert(renamed0.rename.physSrc1.idx.toInt == 2, "Slot0: PhysSrc1 for r2 should be p2") // from mockRat initial
      assert(renamed0.rename.physSrc2.idx.toInt == 3, "Slot0: PhysSrc2 for r3 should be p3") // from mockRat initial
      assert(renamed0.rename.oldPhysDest.idx.toInt == 1, "Slot0: OldPhysDest for r1 should be p1")
      assert(renamed0.rename.allocatesPhysDest.toBoolean, "Slot0: Should allocate for r1 dest")
      assert(renamed0.rename.physDest.idx.toInt == 5, "Slot0: New PhysDest for r1 should be p5") // p5 is first free

      // Slot 1 checks
      assert(renamed1.rename.physSrc1.idx.toInt == 0, "Slot1: PhysSrc1 for r0 should be p0") // r0 always p0
      assert(renamed1.rename.physSrc2.idx.toInt == 1, "Slot1: PhysSrc2 for r1 should be p1 (old value, no bypass)")
      assert(renamed1.rename.oldPhysDest.idx.toInt == 4, "Slot1: OldPhysDest for r4 should be p4")
      assert(renamed1.rename.allocatesPhysDest.toBoolean, "Slot1: Should allocate for r4 dest")
      assert(renamed1.rename.physDest.idx.toInt == 6, "Slot1: New PhysDest for r4 should be p6") // p6 is second free

      // Check RAT write requests from RenameUnit
      // Slot 0 writes r1 -> p5
      assert(dut.io.ratWritePorts(0).wen.toBoolean, "Slot0: RAT write for r1 not enabled")
      assert(dut.io.ratWritePorts(0).archReg.toInt == 1, "Slot0: RAT write archReg not r1")
      assert(dut.io.ratWritePorts(0).physReg.toInt == 5, "Slot0: RAT write physReg not p5")
      // Slot 1 writes r4 -> p6
      assert(dut.io.ratWritePorts(1).wen.toBoolean, "Slot1: RAT write for r4 not enabled")
      assert(dut.io.ratWritePorts(1).archReg.toInt == 4, "Slot1: RAT write archReg not r4")
      assert(dut.io.ratWritePorts(1).physReg.toInt == 6, "Slot1: RAT write physReg not p6")

      dut.clockDomain.waitSampling()
      mockRat.applyWrites(dut.io)
      mockRat.process(dut.io)
      mockFl.process(dut.io)

      assert(mockRat.mapping(1) == 5, "RAT r1 should map to p5")
      assert(mockRat.mapping(4) == 6, "RAT r4 should map to p6")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 3: Stall when FreeList is (almost) empty
  test("RenameUnit_Stall_NoFreeRegs") {
    val renameWidth = 1
    val numPhys = 2 // p0 (for r0), p1 (only one truly freeable)
    val numArch = 2 // r0, r1
    // -- MODIFICATION START (Pass archGprCount to defaultPipelineConfig) --
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    // -- MODIFICATION END (Pass archGprCount to defaultPipelineConfig) --
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mockRat = new MockRat(rCfg, dut.clockDomain)
      val mockFl = new MockFreeList(fCfg, dut.clockDomain) // Initially p1 is free

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[Vec[RenamedUop]]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload =>
        renamedUopsOutBuffer.enqueue(payload)
      }
      dut.io.renamedUopsOut.ready #= true // Consumer always ready

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      mockRat.process(dut.io)
      mockFl.process(dut.io) // numFreePhysRegs = 1 (p1)
      dut.clockDomain.waitSampling()

      // Send ADDI r1, r0, 1 (needs 1 phys reg for r1)
      println("[Test Stall] Driving ADDI r1, r0, 1. FreeList has 1 (p1).")
      val uop1 = createDecodedUop(
        0x100,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 1, // r1
        useArchSrc1 = true,
        archSrc1Idx = 0, // r0
        cfg = pCfg
      )
      decodedUopsInQueue.enqueue(op => op := Vec(uop1))

      dut.clockDomain.waitSamplingWhere { // Let RenameUnit process uop1
        mockRat.process(dut.io)
        mockFl.process(dut.io) // FL will allocate p1
        renamedUopsOutBuffer.nonEmpty // Wait for output fire
      }
      println(s"[Test Stall] ADDI r1 processed. FL numFree: ${mockFl.freePool.size}") // Should be 0 now
      assert(mockFl.freePool.isEmpty, "FreeList should be empty after allocating p1")
      // -- MODIFICATION START (Removed .get) --
      val renamed1 = renamedUopsOutBuffer.dequeue().head
      // -- MODIFICATION END --
      assert(renamed1.rename.physDest.idx.toInt == 1, "r1 should be mapped to p1")

      // Apply RAT write for r1->p1 for the next cycle
      dut.clockDomain.waitSampling()
      mockRat.applyWrites(dut.io) // r1 -> p1 written to RAT
      mockRat.process(dut.io)
      mockFl.process(dut.io) // numFreePhysRegs = 0
      // decodedUopsInQueue will automatically set valid to false if empty.

      // Now try to send another instruction that needs a phys reg: ADDI r1, r0, 2
      // FreeList is empty, RenameUnit should stall.
      println("[Test Stall] Driving ADDI r1, r0, 2. FreeList is empty.")
      val uop2 = createDecodedUop(
        0x104,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 1, // r1 (new instance)
        useArchSrc1 = true,
        archSrc1Idx = 0, // r0
        cfg = pCfg
      )
      decodedUopsInQueue.enqueue(op => op := Vec(uop2))

      // Check for stall
      dut.clockDomain.waitSampling() // Give RenameUnit a cycle to see the input & numFreePhysRegs=0
      mockRat.process(dut.io)
      mockFl.process(dut.io)

      assert(dut.io.decodedUopsIn.valid.toBoolean, "decodedUopsIn should be valid (driven by StreamDriver)")
      assert(
        !dut.io.decodedUopsIn.ready.toBoolean,
        "RenameUnit should not be ready for uop2 due to no free regs (stall)"
      )
      assert(!dut.io.renamedUopsOut.valid.toBoolean, "renamedUopsOut should not be valid when stalled")
      println(
        s"[Test Stall] RenameUnit correctly stalled: decodedIn.valid=${dut.io.decodedUopsIn.valid.toBoolean}, decodedIn.ready=${dut.io.decodedUopsIn.ready.toBoolean}"
      )

      // Now, free a register (e.g., p1 is committed) and see if RenameUnit unstalls
      println("[Test Stall] Simulating free of p1.")
      mockFl.addFreedReg(1) // p1 becomes free

      dut.clockDomain.waitSamplingWhere { // Wait for uop2 to be processed and output
        mockRat.process(dut.io)
        mockFl.process(dut.io) // FL will allocate p1 again
        renamedUopsOutBuffer.nonEmpty // Wait for output to be ready
      }
      val renamed2 = renamedUopsOutBuffer.dequeue().head
      assert(renamed2.rename.physDest.idx.toInt == 1, "New r1 should be mapped to p1 (now free again)")
      assert(
        renamed2.rename.oldPhysDest.idx.toInt == 1,
        "OldPhysDest for new r1 should be the p1 from previous mapping"
      )

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 4: Renaming r0 (architectural register 0)
  test("RenameUnit_R0_Handling") {
    val renameWidth = 1
    val numPhys = 4
    val numArch = 2 // r0, r1
    // -- MODIFICATION START (Pass archGprCount to defaultPipelineConfig) --
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    // -- MODIFICATION END (Pass archGprCount to defaultPipelineConfig) --
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val mockRat = new MockRat(rCfg, dut.clockDomain)
      val mockFl = new MockFreeList(fCfg, dut.clockDomain) // Free: p1,p2,p3

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[Vec[RenamedUop]]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload =>
        renamedUopsOutBuffer.enqueue(payload)
      }
      dut.io.renamedUopsOut.ready #= true

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      mockRat.process(dut.io)
      mockFl.process(dut.io)
      dut.clockDomain.waitSampling()

      // Drive ADDI r0, r0, 1 (write to r0)
      val uopWriteR0 = createDecodedUop(
        0x100,
        true,
        BaseUopCode.ALU,
        ExeUnitType.ALU_INT,
        writeArchDestEn = true,
        archDestIdx = 0, // r0 dest
        useArchSrc1 = true,
        archSrc1Idx = 0, // r0 src
        cfg = pCfg
      )
      decodedUopsInQueue.enqueue(op => op := Vec(uopWriteR0))

      dut.clockDomain.waitSamplingWhere {
        mockRat.process(dut.io)
        mockFl.process(dut.io)
        renamedUopsOutBuffer.nonEmpty
      }

      // Check RenameUnit behavior for r0
      assert(!dut.io.flAllocate(0).enable.toBoolean, "Should not enable FL allocate for r0 dest")
      assert(!dut.io.ratWritePorts(0).wen.toBoolean, "Should not enable RAT write for r0 dest")

      // -- MODIFICATION START (Removed .get) --
      val renamedR0 = renamedUopsOutBuffer.dequeue().head
      // -- MODIFICATION END --
      assert(renamedR0.decoded.isValid.toBoolean)
      assert(renamedR0.rename.physSrc1.idx.toInt == 0, "PhysSrc1 for r0 should be p0")
      assert(!renamedR0.rename.allocatesPhysDest.toBoolean, "Should not allocate physReg for r0 dest")
      assert(renamedR0.rename.physDest.idx.toInt == 0, "PhysDest for r0 should be p0") // Default or hardwired
      assert(renamedR0.rename.oldPhysDest.idx.toInt == 0, "OldPhysDest for r0 should be p0")

      // Ensure FreeList state unchanged
      assert(mockFl.freePool.size == (numPhys - 1), "FreeList count should not change for r0 write")

      dut.clockDomain.waitSampling(5)
    }
  }

// === end of RenameUnitSpec ===
  thatsAll()
}
