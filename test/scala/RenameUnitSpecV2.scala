package parallax.test.rename

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import parallax.common._
import parallax.issue._ // Assuming RenamedUop might be here or common
import parallax.components.rename._ // RenameUnit, RenameUnitIo, RenameMapTable, SuperScalarFreeList
import parallax.components.decode._ // DecodedUop, InstructionOpcodesBigInts
import parallax.utilities.{Framework, ParallaxLogger, ProjectScope}

import scala.collection.mutable
import scala.util.Random

class RenameUnitTestBench(
    val pipelineConfig: PipelineConfig,
    ratConfig: RenameMapTableConfig, // Keep configs for component instantiation
    flConfig: SuperScalarFreeListConfig
) extends Component {
  // Top-level IO for the testbench, acting as the external interface for the entire rename stage
  val io = new Bundle {
    val decodedUopsIn = slave Stream (Vec(HardType(DecodedUop(pipelineConfig)), pipelineConfig.renameWidth))
    val renamedUopsOut = master Stream (Vec(HardType(RenamedUop(pipelineConfig)), pipelineConfig.renameWidth))
    val flushIn = in Bool()
    // Add any other top-level inputs/outputs that the rename stage might expose
  }

  // Instantiate the actual components
  val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
  val renameMapTable = new RenameMapTable(ratConfig) // Actual RAT component
  val freeList = new SuperScalarFreeList(flConfig) // Actual FreeList component

  // Connect RenameUnit to its internal dependencies (RAT and FreeList)
  // Master on RenameUnit side (requesting), Slave on RAT/FreeList side (providing service)
  renameUnit.io.ratReadPorts <> renameMapTable.io.readPorts
  renameUnit.io.ratWritePorts <> renameMapTable.io.writePorts
  renameUnit.io.flAllocate <> freeList.io.allocate
  renameUnit.io.numFreePhysRegs <> freeList.io.numFreeRegs

  // Connect top-level testbench IOs to RenameUnit
  renameUnit.io.decodedUopsIn <> io.decodedUopsIn
  renameUnit.io.renamedUopsOut <> io.renamedUopsOut
  renameUnit.io.flushIn <> io.flushIn

  // Checkpoint interfaces (if RenameUnit doesn't drive them, sink them to avoid floating signals)
  // In a real CPU, these would be driven by the Commit/ROB stage.
  // renameMapTable.io.checkpointSave.ready := False
  renameMapTable.io.checkpointRestore.valid := False
  renameMapTable.io.checkpointRestore.payload.mapping.assignDontCare()
  freeList.io.restoreState.freeMask.setAll() // Default init, but FL init handles this
  freeList.io.restoreState.valid := False

  freeList.io.free.foreach(_.enable := False) // Default init, but FL init handles this
  freeList.io.free.foreach(_.physReg.setAll()) // Default init, but FL init handles this

  // SimPublics for internal component states for easier wave viewing and assertions
  renameMapTable.mapReg.mapping.simPublic() // Expose RAT's internal mapping register
  freeList.freeRegsMask.simPublic() // Expose FreeList's internal free mask register
  freeList.io.numFreeRegs.simPublic() // Expose FreeList's output for number of free regs
  renameMapTable.io.simPublic()
  freeList.io.simPublic()
  renameUnit.io.simPublic()
}

class RenameUnitSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32

  // Default configs for testing
  def defaultPipelineConfig(renameWidth: Int = 1, numPhysRegs: Int = 16, archGprCount: Int = 8) = PipelineConfig(
    xlen = XLEN,
    renameWidth = renameWidth,
    archGprCount = archGprCount, // Now uses the parameter
    physGprCount = numPhysRegs // Physical register quantity
  )

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

  // Scala data structures to capture hardware signal values for monitoring
  case class ArchRegData(idx: Int, rtype: ArchRegType.E)

  case class RenamedUopData(
      // DecodedUop fields
      pc: Long,
      isValid: Boolean,
      uopCode: BaseUopCode.E,
      exeUnit: ExeUnitType.E,
      writeArchDestEn: Boolean,
      archDest: ArchRegData,
      useArchSrc1: Boolean,
      archSrc1: ArchRegData,
      useArchSrc2: Boolean,
      archSrc2: ArchRegData,

      // RenamedInfo fields
      physSrc1Idx: Int,
      physSrc2Idx: Int,
      physDestIdx: Int,
      oldPhysDestIdx: Int,
      allocatesPhysDest: Boolean,

      // Top-level RenamedUop fields
      hasException: Boolean,
      exceptionCode: Int
  ) {
    // Helper to print for debugging
    override def toString: String = {
      val destInfo = if (writeArchDestEn) s"r${archDest.idx}(p$physDestIdx,oldP$oldPhysDestIdx)" else "N/A"
      val src1Info = if (useArchSrc1) s"r${archSrc1.idx}(p$physSrc1Idx)" else "N/A"
      val src2Info = if (useArchSrc2) s"r${archSrc2.idx}(p$physSrc2Idx)" else "N/A"
      s"PC:0x${pc.toHexString} Valid:$isValid Uop:$uopCode Exe:$exeUnit Dest:$destInfo Src1:$src1Info Src2:$src2Info Alloc:$allocatesPhysDest Fault:$hasException($exceptionCode)"
    }
  }

  object RenamedUopData {
    def from(uop: RenamedUop): RenamedUopData = {
      RenamedUopData(
        pc = uop.decoded.pc.toLong,
        isValid = uop.decoded.isValid.toBoolean,
        uopCode = uop.decoded.uopCode.toEnum,
        exeUnit = uop.decoded.exeUnit.toEnum,
        writeArchDestEn = uop.decoded.writeArchDestEn.toBoolean,
        archDest = ArchRegData(uop.decoded.archDest.idx.toInt, uop.decoded.archDest.rtype.toEnum),
        useArchSrc1 = uop.decoded.useArchSrc1.toBoolean,
        archSrc1 = ArchRegData(uop.decoded.archSrc1.idx.toInt, uop.decoded.archSrc1.rtype.toEnum),
        useArchSrc2 = uop.decoded.useArchSrc2.toBoolean,
        archSrc2 = ArchRegData(uop.decoded.archSrc2.idx.toInt, uop.decoded.archSrc2.rtype.toEnum),
        physSrc1Idx = uop.rename.physSrc1.idx.toInt,
        physSrc2Idx = uop.rename.physSrc2.idx.toInt,
        physDestIdx = uop.rename.physDest.idx.toInt,
        oldPhysDestIdx = uop.rename.oldPhysDest.idx.toInt,
        allocatesPhysDest = uop.rename.allocatesPhysDest.toBoolean,
        hasException = uop.hasException.toBoolean,
        exceptionCode = uop.exceptionCode.toInt
      )
    }
  }

  // For the Vec[RenamedUop] payload
  case class RenamedUopGroupData(uops: Seq[RenamedUopData])


  // Helper to drive DecodedUop signals for testing
  def driveDecodedUop(
      targetUop: DecodedUop, // This is the hardware signal to drive
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
      archSrc2Type: ArchRegType.E = ArchRegType.GPR
  ): Unit = { // Returns Unit, as it's a driving function
    targetUop.setDefaultForSim() // Start with defaults, ensuring all fields are initialized
    targetUop.pc #= pc
    targetUop.isValid #= isValid
    targetUop.uopCode #= uopCode
    targetUop.exeUnit #= exeUnit

    targetUop.writeArchDestEn #= writeArchDestEn
    if (writeArchDestEn) {
      targetUop.archDest.idx #= archDestIdx
      targetUop.archDest.rtype #= archDestType
    }
    targetUop.useArchSrc1 #= useArchSrc1
    if (useArchSrc1) {
      targetUop.archSrc1.idx #= archSrc1Idx
      targetUop.archSrc1.rtype #= archSrc1Type
    }
    targetUop.useArchSrc2 #= useArchSrc2
    if (useArchSrc2) {
      targetUop.archSrc2.idx #= archSrc2Idx
      targetUop.archSrc2.rtype #= archSrc2Type
    }
  }

  // Test 1: Single instruction rename
  testOnly("RenameUnit_Single_ADD") {
    val renameWidth = 1
    val numPhys = 8
    val numArch = 8 // Default archGprCount
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain) // Randomize input ready

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      // Consumer is always ready in this test, so manually drive it. DO NOT use StreamReadyRandomizer here.
      dut.io.renamedUopsOut.ready #= true

      // Initial state
      decodedUopsInQueue.clear() // Ensure no pending uops
      dut.io.flushIn #= false

      dut.clockDomain.waitSampling() // Let DUT initialize

      // --- Drive ADD r3, r1, r2 ---
      // Initial RAT: r1->p1, r2->p2, r3->p3. Free: p4,p5,p6,p7 (p0 not usable)
      ParallaxLogger.log("[Test ADD] Driving ADD r3, r1, r2")
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0), // Pass the hardware instance to the driver function
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 3,
          useArchSrc1 = true,
          archSrc1Idx = 1,
          useArchSrc2 = true,
          archSrc2Idx = 2
        )
      }

      // Cycle 1: RenameUnit processes inputs
      dut.clockDomain.waitSamplingWhere {
        renamedUopsOutBuffer.nonEmpty // Wait until RenameUnit produces valid output
      }

      // At this point, RenameUnit has read RAT (for src1, src2, oldDest for r3)
      // and requested allocation from FL for r3's new physReg.
      // FL has responded (e.g., allocated p4).
      // RenameUnit has prepared renamedUopsOut.payload and set valid.

      // Check RenameUnit outputs for current cycle (combinational)
      // The RenameUnit's internal IOs reflect the requests to RAT/FL.
      // RAT read ports are not directly observed from dut.io anymore, but RenameUnit internal logic reflects them.
      // FL allocate enable/success is now driven by RenameUnit, and actual FreeList is connected.
      assert(dut.renameUnit.io.flAllocate(0).enable.toBoolean, "FL allocate for r3's dest not enabled")

      val renamedGroup = renamedUopsOutBuffer.dequeue()
      val renamedVec = renamedGroup.uops.head
      ParallaxLogger.log(
        s"Renamed ADD: PC=${renamedVec.pc.toLong.toHexString} Valid=${renamedVec.isValid}"
      )
      ParallaxLogger.log(s"  PhysSrc1=${renamedVec.physSrc1Idx}, PhysSrc2=${renamedVec.physSrc2Idx}")
      ParallaxLogger.log(
        s"  PhysDest=${renamedVec.physDestIdx}, OldPhysDest=${renamedVec.oldPhysDestIdx}, AllocSuc=${renamedVec.allocatesPhysDest}"
      )

      assert(renamedVec.isValid)
      assert(renamedVec.physSrc1Idx == 1, "PhysSrc1 for r1 should be p1")
      assert(renamedVec.physSrc2Idx == 2, "PhysSrc2 for r2 should be p2")
      assert(renamedVec.oldPhysDestIdx == 3, "OldPhysDest for r3 should be p3")
      assert(renamedVec.allocatesPhysDest, "Should allocate for r3 dest")
      assert(
        renamedVec.physDestIdx == 4,
        "New PhysDest for r3 should be p4 (first from free pool after p0,p1,p2,p3)"
      )

      // Simulate RAT write for next cycle (RenameUnit issued write this cycle)
      // RAT's internal mapReg updates on the next cycle, so we wait 1 cycle.
      dut.clockDomain.waitSampling()
      // Check RAT mapping state (now via real RAT component)
      assert(dut.renameMapTable.mapReg.mapping(3).toInt == 4, "RAT mapping for r3 should now be p4")
      // Check FreeList remaining count
      assert(dut.freeList.io.numFreeRegs.toInt == (numPhys - 1 - 1), "FreeList size should be reduced by 1") // p0 not free, then 1 allocated

      dut.clockDomain.waitSampling(5)
    }
  }

// === melanjutkan dari RenameUnitSpec ===

  // Test 2: Superscalar rename (renameWidth = 2) - independent instructions
  test("RenameUnit_Dual_Independent") {
    val renameWidth = 2
    val numPhys = 10 // p0, p1..p9
    val numArch = 5 // r0, r1..r4
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      // For r0-r4 mapping to p0-p4, and p5-p9 free, ensure RAT starts like that (default init) and FL is adjusted.
      // Actual FreeList init: p1-p9 free (9 regs).
      // Here, we want p5-p9 free, meaning p1-p4 are "occupied" by initial r1-r4 mapping.
      // This is handled by the overall system's init, or by a checkpoint.
      // For this test, let's assume the default p1..p(N-1) free is what we test against.
      // If r0-r4 map to p0-p4, then p5-p9 are free. Initial FreeList has p1..p9 free. So (p1,p2,p3,p4) are "taken".
      // Let's explicitly "take" p1,p2,p3,p4 from the freelist at start.
      dut.freeList.freeRegsMask #= BigInt("1111100000", 2) // p0-p4 taken (0), p5-p9 free (1). Total 5 free.
      dut.clockDomain.waitSampling() // Let it settle
      ParallaxLogger.log(s"Initial FreeList mask: ${dut.freeList.freeRegsMask.toBigInt.toString(2)}")
      ParallaxLogger.log(s"Initial FreeList numFreeRegs: ${dut.freeList.io.numFreeRegs.toInt}")
      assert(dut.freeList.io.numFreeRegs.toInt == 5, "Initial FreeList count incorrect")

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      dut.clockDomain.waitSampling()

      // Drive:
      // Slot 0: ADD r1, r2, r3  (p_new_1 = r2_val + r3_val) -> new p_alloc_0
      // Slot 1: ADD r4, r0, r1  (p_new_4 = r0_val + r1_val_old) -> new p_alloc_1
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0), // Drive slot 0
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 1, // r1
          useArchSrc1 = true,
          archSrc1Idx = 2, // r2
          useArchSrc2 = true,
          archSrc2Idx = 3 // r3
        )
        driveDecodedUop(
          payloadVec(1), // Drive slot 1
          pc = 0x104,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 4, // r4
          useArchSrc1 = true,
          archSrc1Idx = 0, // r0
          useArchSrc2 = true,
          archSrc2Idx = 1 // r1 (will read old p1, not new p_alloc_0)
        )
      }

      dut.clockDomain.waitSamplingWhere {
        renamedUopsOutBuffer.nonEmpty
      }

      // Check renamedUopsOut payload
      val renamedGroup = renamedUopsOutBuffer.dequeue() // Get the Vec of RenamedUop
      val renamed0 = renamedGroup.uops(0)
      val renamed1 = renamedGroup.uops(1)

      // Slot 0 checks
      assert(renamed0.physSrc1Idx == 2, "Slot0: PhysSrc1 for r2 should be p2") // from mockRat initial
      assert(renamed0.physSrc2Idx == 3, "Slot0: PhysSrc2 for r3 should be p3") // from mockRat initial
      assert(renamed0.oldPhysDestIdx == 1, "Slot0: OldPhysDest for r1 should be p1")
      assert(renamed0.allocatesPhysDest, "Slot0: Should allocate for r1 dest")
      assert(renamed0.physDestIdx == 5, "Slot0: New PhysDest for r1 should be p5") // p5 is first free

      // Slot 1 checks
      assert(renamed1.physSrc1Idx == 0, "Slot1: PhysSrc1 for r0 should be p0") // r0 always p0
      assert(renamed1.physSrc2Idx == 1, "Slot1: PhysSrc2 for r1 should be p1 (old value, no bypass)")
      assert(renamed1.oldPhysDestIdx == 4, "Slot1: OldPhysDest for r4 should be p4")
      assert(renamed1.allocatesPhysDest, "Slot1: Should allocate for r4 dest")
      assert(renamed1.physDestIdx == 6, "Slot1: New PhysDest for r4 should be p6") // p6 is second free

      // Check RAT write requests from RenameUnit (these are requests output by RenameUnit, not actual RAT state)
      assert(dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Slot0: RAT write for r1 not enabled")
      assert(dut.renameUnit.io.ratWritePorts(0).archReg.toInt == 1, "Slot0: RAT write archReg not r1")
      assert(dut.renameUnit.io.ratWritePorts(0).physReg.toInt == 5, "Slot0: RAT write physReg not p5")
      assert(dut.renameUnit.io.ratWritePorts(1).wen.toBoolean, "Slot1: RAT write for r4 not enabled")
      assert(dut.renameUnit.io.ratWritePorts(1).archReg.toInt == 4, "Slot1: RAT write archReg not r4")
      assert(dut.renameUnit.io.ratWritePorts(1).physReg.toInt == 6, "Slot1: RAT write physReg not p6")

      dut.clockDomain.waitSampling() // Advance one cycle for RAT and FreeList internal states to update
      assert(dut.renameMapTable.mapReg.mapping(1).toInt == 5, "RAT r1 should map to p5")
      assert(dut.renameMapTable.mapReg.mapping(4).toInt == 6, "RAT r4 should map to p6")
      // Initial free was 5. Allocated 2. So 5 - 2 = 3 free.
      assert(dut.freeList.io.numFreeRegs.toInt == 3, "FreeList count incorrect after dual alloc")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 3: Stall when FreeList is (almost) empty
  test("RenameUnit_Stall_NoFreeRegs") {
    val renameWidth = 1
    val numPhys = 2 // p0 (for r0), p1 (only one truly freeable)
    val numArch = 2 // r0, r1
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      // Ensure FreeList starts with only p1 free (p0 is special, p1 is the only one)
      dut.freeList.freeRegsMask #= BigInt("01", 2) // p0 not free, p1 free
      dut.clockDomain.waitSampling()
      assert(dut.freeList.io.numFreeRegs.toInt == 1, "Initial FreeList count incorrect (expected 1)")

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true // Consumer always ready

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      dut.clockDomain.waitSampling()

      // Send ADDI r1, r0, 1 (needs 1 phys reg for r1)
      ParallaxLogger.log("[Test Stall] Driving ADDI r1, r0, 1. FreeList has 1 (p1).")
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0),
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 1, // r1
          useArchSrc1 = true,
          archSrc1Idx = 0 // r0
        )
      }

      dut.clockDomain.waitSamplingWhere { // Let RenameUnit process uop1
        renamedUopsOutBuffer.nonEmpty // Wait for output fire
      }
      ParallaxLogger.log(s"[Test Stall] ADDI r1 processed. FreeList numFree: ${dut.freeList.io.numFreeRegs.toInt}") // Should be 0 now
      assert(dut.freeList.io.numFreeRegs.toInt == 0, "FreeList should be empty after allocating p1")
      val renamed1 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamed1.physDestIdx == 1, "r1 should be mapped to p1")

      // Apply RAT write for r1->p1 for the next cycle
      dut.clockDomain.waitSampling() // Advance for RAT and FreeList state updates
      assert(dut.renameMapTable.mapReg.mapping(1).toInt == 1, "RAT r1 should map to p1") // Initially p1, now p1 after alloc
      // FreeList is still 0
      assert(dut.freeList.io.numFreeRegs.toInt == 0, "FreeList should remain empty")

      // Now try to send another instruction that needs a phys reg: ADDI r1, r0, 2
      // FreeList is empty, RenameUnit should stall.
      ParallaxLogger.log("[Test Stall] Driving ADDI r1, r0, 2. FreeList is empty.")
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0),
          pc = 0x104,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 1, // r1 (new instance)
          useArchSrc1 = true,
          archSrc1Idx = 0 // r0
        )
      }

      // Check for stall (Wait a few cycles, RenameUnit should remain stalled)
      dut.clockDomain.waitSampling(5) // Wait a few cycles, allowing time for potential un-stall

      assert(dut.io.decodedUopsIn.valid.toBoolean, "decodedUopsIn should be valid (driven by StreamDriver)")
      assert(
        !dut.io.decodedUopsIn.ready.toBoolean,
        "RenameUnit should not be ready for uop2 due to no free regs (stall)"
      )
      assert(!dut.io.renamedUopsOut.valid.toBoolean, "renamedUopsOut should not be valid when stalled")
      ParallaxLogger.log(
        s"[Test Stall] RenameUnit correctly stalled: decodedIn.valid=${dut.io.decodedUopsIn.valid.toBoolean}, decodedIn.ready=${dut.io.decodedUopsIn.ready.toBoolean}"
      )
      assert(dut.freeList.io.numFreeRegs.toInt == 0, "FreeList should still be empty during stall")


      // Now, free a register (e.g., p1 is committed) and see if RenameUnit unstalls
      ParallaxLogger.log("[Test Stall] Simulating free of p1.")
      dut.freeList.io.free(0).enable #= true // Enable the free port
      dut.freeList.io.free(0).physReg #= 1 // Free p1
      dut.clockDomain.waitSampling() // Advance one cycle to apply free and for RenameUnit to react
      dut.freeList.io.free(0).enable #= false // Deassert free enable
      assert(dut.freeList.io.numFreeRegs.toInt == 1, "FreeList should have 1 free reg after freeing p1")


      dut.clockDomain.waitSamplingWhere { // Wait for uop2 to be processed and output
        renamedUopsOutBuffer.nonEmpty // Wait for output to be ready
      }
      val renamed2 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamed2.physDestIdx == 1, "New r1 should be mapped to p1 (now free again)")
      // OldPhysDest for new r1 will be the physical register currently mapped to archReg 1.
      // This is p1 from the *previous* allocation. So, it should be p1.
      assert(
        renamed2.oldPhysDestIdx == 1,
        "OldPhysDest for new r1 should be the p1 from previous mapping"
      )
      ParallaxLogger.log(s"[Test Stall] ADDI r1 (2nd) processed. FreeList numFree: ${dut.freeList.io.numFreeRegs.toInt}") // Should be 0 again
      assert(dut.freeList.io.numFreeRegs.toInt == 0, "FreeList should be empty after allocating p1 again")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 4: Renaming r0 (architectural register 0)
  test("RenameUnit_R0_Handling") {
    val renameWidth = 1
    val numPhys = 4
    val numArch = 2 // r0, r1
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      // Initial FreeList state: p1,p2,p3 free (p0 special). Total 3 free.
      assert(dut.freeList.io.numFreeRegs.toInt == (numPhys - 1), "Initial FreeList count incorrect")

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain)

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      dut.clockDomain.waitSampling()

      // Drive ADDI r0, r0, 1 (write to r0)
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0),
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 0, // r0 dest
          useArchSrc1 = true,
          archSrc1Idx = 0 // r0 src
        )
      }

      dut.clockDomain.waitSamplingWhere {
        renamedUopsOutBuffer.nonEmpty
      }

      // Check RenameUnit behavior for r0
      assert(!dut.renameUnit.io.flAllocate(0).enable.toBoolean, "Should not enable FL allocate for r0 dest")
      assert(!dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Should not enable RAT write for r0 dest")

      val renamedR0 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamedR0.isValid)
      assert(renamedR0.physSrc1Idx == 0, "PhysSrc1 for r0 should be p0")
      assert(!renamedR0.allocatesPhysDest, "Should not allocate physReg for r0 dest")
      assert(renamedR0.physDestIdx == 0, "PhysDest for r0 should be p0") // Default or hardwired
      assert(renamedR0.oldPhysDestIdx == 0, "OldPhysDest for r0 should be p0")

      // Ensure FreeList state unchanged
      // The FreeList has p0 taken by design, so its count is numPhys-1.
      // An instruction writing to r0 should NOT change this count.
      assert(dut.freeList.io.numFreeRegs.toInt == (numPhys - 1), "FreeList count should not change for r0 write")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 4: Renaming r0 (architectural register 0) - Manual Driving
  test("RenameUnit_R0_Handling_ManualDrive") {
    val renameWidth = 1
    val numPhys = 4
    val numArch = 2 // r0, r1
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.assertReset()
      sleep(10) // ns
      dut.clockDomain.deassertReset()
      sleep(10) // ns

      // Initial FreeList state: p1,p2,p3 free (p0 special). Total 3 free.
      assert(dut.freeList.io.numFreeRegs.toInt == (numPhys - 1), "Initial FreeList count incorrect")

      // --- Manual Driving Setup for decodedUopsIn ---
      // No StreamDriver.queue for input.
      dut.io.decodedUopsIn.valid #= false // Initially deassert valid
      // Initialize payload to default values so it's always driven
      dut.io.decodedUopsIn.payload(0).setDefaultForSim()

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true // Consumer always ready

      dut.io.flushIn #= false
      dut.clockDomain.waitSampling() // Initial wait for reset and components to settle

      // --- Drive ADDI r0, r0, 1 (write to r0) MANUALLY ---
      println("[Test R0 Manual] Driving ADDI r0, r0, 1 (manual).")

      // 1. Set payload and assert valid
      driveDecodedUop(
        dut.io.decodedUopsIn.payload(0),
        pc = 0x100,
        isValid = true,
        uopCode = BaseUopCode.ALU,
        exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = false,
        archDestIdx = 0, // r0 dest
        useArchSrc1 = true,
        archSrc1Idx = 0 // r0 src
      )
      dut.io.decodedUopsIn.valid #= true
      sleep(0) // Let the DUT consume the input
      // Check the PC value just driven
      // Note: direct #= on payload is ok for current cycle, then DUT reads it.
      // dut.io.decodedUopsIn.payload(0).pc.toLong is value of the hardware signal at this point.
      println(f"Driving pc=${dut.io.decodedUopsIn.payload(0).pc.toLong}%x")
      assert(dut.io.decodedUopsIn.payload(0).pc.toLong == 0x100, "Payload PC not set correctly before waitSampling")

      // 2. Wait for DUT to be ready and consume the input
      dut.clockDomain.waitSamplingWhere {
        // Condition: DUT is ready to accept input, AND output has appeared (expected in this single-cycle path)
        dut.io.decodedUopsIn.ready.toBoolean && renamedUopsOutBuffer.nonEmpty
      }
      // At this point, the DUT has consumed the input, and produced an output.

      // 3. Deassert valid for next cycle
      dut.io.decodedUopsIn.valid #= false
      dut.clockDomain.waitSampling() // Advance one cycle to see valid go low, and for RAT writes to apply

      // Check RenameUnit behavior for r0
      assert(!dut.renameUnit.io.flAllocate(0).enable.toBoolean, "Should not enable FL allocate for r0 dest")
      assert(!dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Should not enable RAT write for r0 dest")

      val renamedR0 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamedR0.isValid)
      assert(renamedR0.physSrc1Idx == 0, "PhysSrc1 for r0 should be p0")
      assert(!renamedR0.allocatesPhysDest, "Should not allocate physReg for r0 dest")
      assert(renamedR0.physDestIdx == 0, "PhysDest for r0 should be p0") // Default or hardwired
      assert(renamedR0.oldPhysDestIdx == 0, "OldPhysDest for r0 should be p0")

      // Ensure FreeList state unchanged
      assert(dut.freeList.io.numFreeRegs.toInt == (numPhys - 1), "FreeList count should not change for r0 write")
      // Assert that RAT mapping for r0 remains p0
      assert(dut.renameMapTable.mapReg.mapping(0).toInt == 0, "RAT mapping for r0 should remain p0")

      dut.clockDomain.waitSampling(5)
    }
  }
// === end of RenameUnitSpec ===
  thatsAll()
}
