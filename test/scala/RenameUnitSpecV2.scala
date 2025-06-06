package test.rename

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
    val flushIn = in Bool ()
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
  // renameMapTable.io.checkpointSave.ready := False // Not used in this testbench directly
  renameMapTable.io.checkpointRestore.valid := False
  renameMapTable.io.checkpointRestore.payload.mapping.assignDontCare() // Or use .clear() if available
  freeList.io.restoreState.freeMask.setAll() // Default init, but FL init handles this
  freeList.io.restoreState.valid := False

  freeList.io.free.foreach { port =>
    port.enable := False
    port.physReg.assignDontCare() // Or #= 0, ensure it's driven
  }

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

  def defaultFlConfig(archRegs: Int, physRegs: Int, renameWidth: Int) = SuperScalarFreeListConfig(
    numPhysRegs = physRegs,
    resetToFull = true,
    numInitialArchMappings = archRegs, // This means archRegs (r0 to r(N-1)) are initially mapped
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
  test("RenameUnit_Single_ADD") {
    val renameWidth = 1
    val numPhys = 8
    val numArch = 4 // Default archGprCount
    val initialNumFree = numPhys - numArch // Should be 8 - 4 = 4 initially
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numArch, numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val (decodedUopsInDriver, decodedUopsInQueue) = StreamDriver.queue(dut.io.decodedUopsIn, dut.clockDomain)
      StreamReadyRandomizer(dut.io.decodedUopsIn, dut.clockDomain) // Randomize input ready

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true

      decodedUopsInQueue.clear()
      dut.io.flushIn #= false
      dut.clockDomain.waitSampling()

      assert(dut.freeList.io.numFreeRegs.toInt == initialNumFree, s"Initial FreeList count should be $initialNumFree")

      ParallaxLogger.log("[Test ADD] Driving ADD r3, r1, r2")
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0),
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = true,
          archDestIdx = 3, // r3
          useArchSrc1 = true,
          archSrc1Idx = 1, // r1
          useArchSrc2 = true,
          archSrc2Idx = 2 // r2
        )
      }

      val timeoutOccurred1 = dut.clockDomain.waitSamplingWhere(20) {
        renamedUopsOutBuffer.nonEmpty
      }
      assert(!timeoutOccurred1, "Timeout waiting for first renamed uop output")

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
      // Initial RAT: r0->p0, r1->p1, r2->p2, r3->p3. Free: p4,p5,p6,p7
      assert(renamedVec.physSrc1Idx == 1, "PhysSrc1 for r1 should be p1")
      assert(renamedVec.physSrc2Idx == 2, "PhysSrc2 for r2 should be p2")
      assert(renamedVec.oldPhysDestIdx == 3, "OldPhysDest for r3 should be p3")
      assert(renamedVec.allocatesPhysDest, "Should allocate for r3 dest")
      assert(
        renamedVec.physDestIdx == numArch, // numArch is 4, so p4 (0-indexed)
        s"New PhysDest for r3 should be p$numArch (first from free pool after p0..p${numArch - 1})"
      )

      dut.clockDomain.waitSampling()
      assert(dut.renameMapTable.mapReg.mapping(3).toInt == numArch, s"RAT mapping for r3 should now be p$numArch")
      assert(dut.freeList.io.numFreeRegs.toInt == (initialNumFree - 1), "FreeList size should be reduced by 1")

      dut.clockDomain.waitSampling(5)
    }
  }

// === melanjutkan dari RenameUnitSpec ===

  // Test 2: Superscalar rename (renameWidth = 2) - independent instructions
  test("RenameUnit_Dual_Independent") {
    val renameWidth = 2
    val numPhys = 10 // p0..p9
    val numArch = 5 // r0..r4
    val initialNumFree = numPhys - numArch // 10 - 5 = 5 free (p5, p6, p7, p8, p9)
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numArch, numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling() // Let DUT initialize

      ParallaxLogger.log(s"Initial FreeList mask from DUT: ${dut.freeList.freeRegsMask.toBigInt.toString(2)}")
      ParallaxLogger.log(s"Initial FreeList numFreeRegs from DUT: ${dut.freeList.io.numFreeRegs.toInt}")
      assert(
        dut.freeList.io.numFreeRegs.toInt == initialNumFree,
        s"Initial FreeList count incorrect, expected $initialNumFree"
      )

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
      // dut.clockDomain.waitSampling() // Already waited after DUT init

      // Drive:
      // Slot 0: ADD r1, r2, r3  (p_new_1 = r2_val + r3_val) -> new p_alloc_0
      // Slot 1: ADD r4, r0, r1  (p_new_4 = r0_val + r1_val_old) -> new p_alloc_1
      // Initial RAT: r0->p0, r1->p1, r2->p2, r3->p3, r4->p4. Free: p5,p6,p7,p8,p9
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

      val timeoutOccurred2 = dut.clockDomain.waitSamplingWhere(100) {
        renamedUopsOutBuffer.nonEmpty
      }
      assert(!timeoutOccurred2, "Timeout waiting for dual independent uop output")

      val renamedGroup = renamedUopsOutBuffer.dequeue()
      val renamed0 = renamedGroup.uops(0)
      val renamed1 = renamedGroup.uops(1)

      // Slot 0 checks: r1 = r2 + r3. New r1 gets p5.
      assert(renamed0.physSrc1Idx == 2, "Slot0: PhysSrc1 for r2 should be p2")
      assert(renamed0.physSrc2Idx == 3, "Slot0: PhysSrc2 for r3 should be p3")
      assert(renamed0.oldPhysDestIdx == 1, "Slot0: OldPhysDest for r1 should be p1")
      assert(renamed0.allocatesPhysDest, "Slot0: Should allocate for r1 dest")
      assert(renamed0.physDestIdx == numArch + 0, s"Slot0: New PhysDest for r1 should be p${numArch + 0} (p5)") // p5

      // Slot 1 checks: r4 = r0 + r1(old). New r4 gets p6.
      assert(renamed1.physSrc1Idx == 0, "Slot1: PhysSrc1 for r0 should be p0")
      assert(renamed1.physSrc2Idx == 1, "Slot1: PhysSrc2 for r1 should be p1 (old value, no bypass)")
      assert(renamed1.oldPhysDestIdx == 4, "Slot1: OldPhysDest for r4 should be p4")
      assert(renamed1.allocatesPhysDest, "Slot1: Should allocate for r4 dest")
      assert(renamed1.physDestIdx == numArch + 1, s"Slot1: New PhysDest for r4 should be p${numArch + 1} (p6)") // p6

      assert(dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Slot0: RAT write for r1 not enabled")
      assert(dut.renameUnit.io.ratWritePorts(0).archReg.toInt == 1, "Slot0: RAT write archReg not r1")
      assert(
        dut.renameUnit.io.ratWritePorts(0).physReg.toInt == numArch + 0,
        s"Slot0: RAT write physReg not p${numArch + 0}"
      )
      assert(dut.renameUnit.io.ratWritePorts(1).wen.toBoolean, "Slot1: RAT write for r4 not enabled")
      assert(dut.renameUnit.io.ratWritePorts(1).archReg.toInt == 4, "Slot1: RAT write archReg not r4")
      assert(
        dut.renameUnit.io.ratWritePorts(1).physReg.toInt == numArch + 1,
        s"Slot1: RAT write physReg not p${numArch + 1}"
      )

      dut.clockDomain.waitSampling()
      assert(dut.renameMapTable.mapReg.mapping(1).toInt == numArch + 0, "RAT r1 should map to p${numArch + 0}")
      assert(dut.renameMapTable.mapReg.mapping(4).toInt == numArch + 1, "RAT r4 should map to p${numArch + 1}")
      assert(dut.freeList.io.numFreeRegs.toInt == initialNumFree - 2, "FreeList count incorrect after dual alloc")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 3: Stall when FreeList is (almost) empty
  test("RenameUnit_Stall_NoFreeRegs") {
    val renameWidth = 1
    val numPhys = 3 // p0, p1, p2
    val numArch = 2 // r0, r1. So, r0->p0, r1->p1. p2 is free.
    val initialNumFree = numPhys - numArch // 3 - 2 = 1 free (p2)
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numArch, numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling() // Let DUT initialize

      ParallaxLogger.log(s"Initial FreeList mask from DUT: ${dut.freeList.freeRegsMask.toBigInt.toString(2)}")
      assert(
        dut.freeList.io.numFreeRegs.toInt == initialNumFree,
        s"Initial FreeList count incorrect (expected $initialNumFree)"
      )

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
      // dut.clockDomain.waitSampling() // Already waited

      // Send ADDI r1, r0, 1 (needs 1 phys reg for r1).
      // Initial: r0->p0, r1->p1. Free: p2.
      ParallaxLogger.log(s"[Test Stall] Driving ADDI r1, r0, 1. FreeList has $initialNumFree (p${numArch}).")
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

      val timeoutOccurred3a = dut.clockDomain.waitSamplingWhere(100) {
        renamedUopsOutBuffer.nonEmpty
      }
      assert(!timeoutOccurred3a, "Timeout waiting for first uop output (stall test)")

      // After allocating p2 for r1, FreeList should be empty.
      // numFreeRegs is checked after RAT update cycle below.
      val renamed1 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamed1.oldPhysDestIdx == 1, "Old physDest for r1 should be p1")
      assert(renamed1.physDestIdx == numArch, s"r1 should be mapped to p$numArch (p2)") // numArch = 2, so p2

      dut.clockDomain.waitSampling() // Advance for RAT and FreeList state updates
      assert(dut.renameMapTable.mapReg.mapping(1).toInt == numArch, s"RAT r1 should map to p$numArch")
      assert(dut.freeList.io.numFreeRegs.toInt == 0, "FreeList should be empty after allocating p${numArch}")

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

      dut.clockDomain.waitSampling(5)

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

      val physRegToFree = numArch // This was p2, allocated to the first r1
      ParallaxLogger.log(s"[Test Stall] Simulating free of p$physRegToFree.")
      dut.freeList.io.free(0).enable #= true
      dut.freeList.io.free(0).physReg #= physRegToFree
      dut.clockDomain.waitSampling()
      dut.freeList.io.free(0).enable #= false
      sleep(0)
      assert(dut.freeList.io.numFreeRegs.toInt == 1, s"FreeList should have 1 free reg after freeing p$physRegToFree")

      val timeoutOccurred3b = dut.clockDomain.waitSamplingWhere(100) {
        renamedUopsOutBuffer.nonEmpty
      }
      assert(!timeoutOccurred3b, "Timeout waiting for second uop output (stall test, after free)")

      val renamed2 = renamedUopsOutBuffer.dequeue().uops.head
      // OldPhysDest for this new r1 is the physical register previously mapped to archReg 1.
      // This was p2 (numArch) from the *previous* allocation.
      assert(renamed2.oldPhysDestIdx == numArch, s"OldPhysDest for new r1 should be p$numArch from previous mapping")
      assert(renamed2.physDestIdx == physRegToFree, s"New r1 should be mapped to p$physRegToFree (now free again)")

      dut.clockDomain.waitSampling() // For RAT and FreeList update
      assert(dut.renameMapTable.mapReg.mapping(1).toInt == physRegToFree, s"RAT r1 should now map to p$physRegToFree")
      ParallaxLogger
        .log(s"[Test Stall] ADDI r1 (2nd) processed. FreeList numFree: ${dut.freeList.io.numFreeRegs.toInt}")
      assert(dut.freeList.io.numFreeRegs.toInt == 0, s"FreeList should be empty after allocating p$physRegToFree again")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 4: Renaming r0 (architectural register 0)
  test("RenameUnit_R0_Handling") {
    val renameWidth = 1
    val numPhys = 4
    val numArch = 2 // r0, r1
    val initialNumFree = numPhys - numArch // 4 - 2 = 2 free (p2, p3)
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numArch, numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling() // Let DUT initialize

      assert(
        dut.freeList.io.numFreeRegs.toInt == initialNumFree,
        s"Initial FreeList count incorrect, expected $initialNumFree"
      )

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
      // dut.clockDomain.waitSampling() // Already waited

      // Drive ADDI r0, r0, 1 (write to r0)
      decodedUopsInQueue.enqueue { payloadVec =>
        driveDecodedUop(
          payloadVec(0),
          pc = 0x100,
          isValid = true,
          uopCode = BaseUopCode.ALU,
          exeUnit = ExeUnitType.ALU_INT,
          writeArchDestEn = false,
          archDestIdx = 0, // r0 dest
          useArchSrc1 = true,
          archSrc1Idx = 0 // r0 src
        )
      }

      val timeoutOccurred4 = dut.clockDomain.waitSamplingWhere(100) {
        renamedUopsOutBuffer.nonEmpty
      }
      assert(!timeoutOccurred4, "Timeout waiting for R0 handling uop output")

      // Check RenameUnit behavior for r0
      assert(!dut.renameUnit.io.flAllocate(0).enable.toBoolean, "Should not enable FL allocate for r0 dest")
      assert(!dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Should not enable RAT write for r0 dest")

      val renamedR0 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamedR0.isValid)
      assert(renamedR0.physSrc1Idx == 0, "PhysSrc1 for r0 should be p0")
      assert(!renamedR0.allocatesPhysDest, "Should not allocate physReg for r0 dest")
      assert(renamedR0.physDestIdx == 0, "PhysDest for r0 should be p0")
      assert(renamedR0.oldPhysDestIdx == 0, "OldPhysDest for r0 should be p0")

      assert(dut.freeList.io.numFreeRegs.toInt == initialNumFree, "FreeList count should not change for r0 write")

      dut.clockDomain.waitSampling(5)
    }
  }

  // Test 5: Renaming r0 (architectural register 0) - Manual Driving (Corrected name to avoid clash)
  test("RenameUnit_R0_Handling_ManualDrive") {
    val renameWidth = 1
    val numPhys = 4
    val numArch = 2 // r0, r1
    val initialNumFree = numPhys - numArch // 4 - 2 = 2 free
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    val fCfg = defaultFlConfig(numArch, numPhys, renameWidth)

    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg, fCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.forkSimSpeedPrinter(2) // Optional: print sim speed
      dut.clockDomain.waitSampling(2) // Initial wait for reset and components to settle

      assert(
        dut.freeList.io.numFreeRegs.toInt == initialNumFree,
        s"Initial FreeList count incorrect, expected $initialNumFree"
      )

      dut.io.decodedUopsIn.valid #= false
      dut.io.decodedUopsIn.payload(0).setDefaultForSim()

      val renamedUopsOutBuffer = mutable.Queue[RenamedUopGroupData]()
      StreamMonitor(dut.io.renamedUopsOut, dut.clockDomain) { payload: Vec[RenamedUop] =>
        val convertedUops = payload.map(RenamedUopData.from)
        renamedUopsOutBuffer.enqueue(RenamedUopGroupData(convertedUops.toSeq))
      }
      dut.io.renamedUopsOut.ready #= true
      dut.io.flushIn #= false
      // dut.clockDomain.waitSampling() // Already waited

      println("[Test R0 Manual] Driving ADDI r0, r0, 1 (manual).")

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
      // sleep(0) // Not necessary before waitSamplingWhere

      val timeoutOccurred4b = dut.clockDomain.waitSamplingWhere(100) {
        dut.io.decodedUopsIn.ready.toBoolean && renamedUopsOutBuffer.nonEmpty // Ensure input is taken and output is produced
      }

      println(f"Driving pc=${dut.io.decodedUopsIn.payload(0).pc.toLong}%x")
      assert(dut.io.decodedUopsIn.payload(0).pc.toLong == 0x100, "Payload PC not set correctly before waitSampling")

      assert(!timeoutOccurred4b, "Timeout waiting for manual R0 handling uop output")

      dut.io.decodedUopsIn.valid #= false // Deassert after it's been consumed
      dut.clockDomain.waitSampling() // Allow internal state changes (e.g. RAT updates if any)

      assert(!dut.renameUnit.io.flAllocate(0).enable.toBoolean, "Should not enable FL allocate for r0 dest")
      assert(!dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "Should not enable RAT write for r0 dest")

      val renamedR0 = renamedUopsOutBuffer.dequeue().uops.head
      assert(renamedR0.isValid)
      assert(renamedR0.physSrc1Idx == 0, "PhysSrc1 for r0 should be p0")
      assert(!renamedR0.allocatesPhysDest, "Should not allocate physReg for r0 dest")
      assert(renamedR0.physDestIdx == 0, "PhysDest for r0 should be p0")
      assert(renamedR0.oldPhysDestIdx == 0, "OldPhysDest for r0 should be p0")

      assert(dut.freeList.io.numFreeRegs.toInt == initialNumFree, "FreeList count should not change for r0 write")
      assert(dut.renameMapTable.mapReg.mapping(0).toInt == 0, "RAT mapping for r0 should remain p0")

      dut.clockDomain.waitSampling(5)
    }
  }
// === end of RenameUnitSpec ===
  thatsAll()
}
