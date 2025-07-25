// filename: src/test/scala/parallax/issue/RenameUnitSpecV2.scala
// testOnly test.issue.RenameUnitSpec
package test.issue

import parallax.common._
import parallax.components.issue._
import parallax.utilities.ParallaxLogger

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer, ScoreboardInOrder}

import scala.collection.mutable
import scala.util.Random
import parallax.components.rename._

class RenameUnitTestBench(
    val pipelineConfig: PipelineConfig,
    ratConfig: RenameMapTableConfig
) extends Component {
  val io = new Bundle {
    val decodedUopsIn = in(Vec(HardType(DecodedUop(pipelineConfig)), pipelineConfig.renameWidth))
    val renamedUopsOut = out(Vec(HardType(RenamedUop(pipelineConfig)), pipelineConfig.renameWidth))
    val allocatedPhysRegsIn = in(Vec(HardType(UInt(ratConfig.physRegIdxWidth)), pipelineConfig.renameWidth))
  }

  val renameUnit = new RenameUnit(pipelineConfig, ratConfig, null)
  val renameMapTable = new RenameMapTable(ratConfig)

  renameUnit.io.ratReadPorts <> renameMapTable.io.readPorts
  renameUnit.io.ratWritePorts <> renameMapTable.io.writePorts
  
  renameUnit.io.decodedUopsIn := io.decodedUopsIn
  renameUnit.io.physRegsIn := io.allocatedPhysRegsIn
  io.renamedUopsOut := renameUnit.io.renamedUopsOut

  renameMapTable.rratMapReg.mapping.simPublic()
  renameUnit.io.simPublic()
  
  renameMapTable.io.checkpointSave.valid := False
  renameMapTable.io.checkpointSave.payload.assignDontCare()
  renameMapTable.io.checkpointRestore.valid := False
  renameMapTable.io.checkpointRestore.payload.assignDontCare()
}

class RenameUnitSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32

  def defaultPipelineConfig(renameWidth: Int = 1, numPhysRegs: Int = 16, archGprCount: Int = 8) = PipelineConfig(
    xlen = XLEN,
    renameWidth = renameWidth,
    archGprCount = archGprCount,
    physGprCount = numPhysRegs
  )

  def defaultRatConfig(archRegs: Int, physRegs: Int, renameWidth: Int) = RenameMapTableConfig(
    archRegCount = archRegs,
    physRegCount = physRegs,
    numReadPorts = renameWidth * 3,
    numWritePorts = renameWidth
  )
  
  case class ArchRegData(idx: Int, rtype: ArchRegType.E)

  case class RenamedUopData(
      pc: Long, isValid: Boolean, uopCode: BaseUopCode.E, exeUnit: ExeUnitType.E,
      writeArchDestEn: Boolean, archDest: ArchRegData,
      useArchSrc1: Boolean, archSrc1: ArchRegData,
      useArchSrc2: Boolean, archSrc2: ArchRegData,
      physSrc1Idx: Int, physSrc2Idx: Int, physDestIdx: Int,
      oldPhysDestIdx: Int, allocatesPhysDest: Boolean,
      hasException: Boolean, exceptionCode: Int
  ) {
    override def toString: String = {
      val destInfo = if (writeArchDestEn) s"r${archDest.idx}(p$physDestIdx,oldP$oldPhysDestIdx)" else "N/A"
      val src1Info = if (useArchSrc1) s"r${archSrc1.idx}(p$physSrc1Idx)" else "N/A"
      val src2Info = if (useArchSrc2) s"r${archSrc2.idx}(p$physSrc2Idx)" else "N/A"
      s"PC:0x${pc.toHexString} Valid:$isValid Uop:$uopCode Dest:$destInfo Src1:$src1Info Src2:$src2Info Alloc:$allocatesPhysDest"
    }
  }

  object RenamedUopData {
    def from(uop: RenamedUop): RenamedUopData = {
      RenamedUopData(
        pc = uop.decoded.pc.toLong, isValid = uop.decoded.isValid.toBoolean,
        uopCode = uop.decoded.uopCode.toEnum, exeUnit = uop.decoded.exeUnit.toEnum,
        writeArchDestEn = uop.decoded.writeArchDestEn.toBoolean,
        archDest = ArchRegData(uop.decoded.archDest.idx.toInt, uop.decoded.archDest.rtype.toEnum),
        useArchSrc1 = uop.decoded.useArchSrc1.toBoolean,
        archSrc1 = ArchRegData(uop.decoded.archSrc1.idx.toInt, uop.decoded.archSrc1.rtype.toEnum),
        useArchSrc2 = uop.decoded.useArchSrc2.toBoolean,
        archSrc2 = ArchRegData(uop.decoded.archSrc2.idx.toInt, uop.decoded.archSrc2.rtype.toEnum),
        physSrc1Idx = uop.rename.physSrc1.idx.toInt, physSrc2Idx = uop.rename.physSrc2.idx.toInt,
        physDestIdx = uop.rename.physDest.idx.toInt, oldPhysDestIdx = uop.rename.oldPhysDest.idx.toInt,
        allocatesPhysDest = uop.rename.allocatesPhysDest.toBoolean,
        hasException = uop.hasException.toBoolean, exceptionCode = uop.exceptionCode.toInt
      )
    }
  }

  def driveDecodedUop(
      targetUop: DecodedUop,
      pc: Long, isValid: Boolean, uopCode: BaseUopCode.E, exeUnit: ExeUnitType.E,
      writeArchDestEn: Boolean = false, archDestIdx: Int = 0, archDestType: ArchRegType.E = ArchRegType.GPR,
      useArchSrc1: Boolean = false, archSrc1Idx: Int = 0, archSrc1Type: ArchRegType.E = ArchRegType.GPR,
      useArchSrc2: Boolean = false, archSrc2Idx: Int = 0, archSrc2Type: ArchRegType.E = ArchRegType.GPR
  ): Unit = {
    targetUop.setDefaultForSim()
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
 def driveIdle(dut: RenameUnitTestBench, cycles: Int = 1): Unit = {
    for (_ <- 0 until cycles) {
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0, isValid = false, uopCode = BaseUopCode.NOP, exeUnit = ExeUnitType.NONE
      )
      dut.io.allocatedPhysRegsIn(0) #= 0
      dut.clockDomain.waitSampling()
    }
  }
  test("RenameUnit_Single_ADD (Refactored Test)") {
    val renameWidth = 1
    val numPhys = 8
    val numArch = 4
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    
    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling()

      driveDecodedUop(
        dut.io.decodedUopsIn(0),
        pc = 0x100, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 3, // r3
        useArchSrc1 = true, archSrc1Idx = 1,     // r1
        useArchSrc2 = true, archSrc2Idx = 2      // r2
      )
      dut.io.allocatedPhysRegsIn(0) #= 4 
      
      dut.clockDomain.waitSampling()

      val renamedUopData = RenamedUopData.from(dut.io.renamedUopsOut(0))
      
      assert(renamedUopData.isValid, "Output uop should be valid")
      assert(renamedUopData.physSrc1Idx == 1, "PhysSrc1 for r1 should be p1")
      assert(renamedUopData.physSrc2Idx == 2, "PhysSrc2 for r2 should be p2")
      assert(renamedUopData.oldPhysDestIdx == 3, "OldPhysDest for r3 should be p3")
      assert(renamedUopData.allocatesPhysDest, "allocatesPhysDest should be true")
      assert(renamedUopData.physDestIdx == 4, "New PhysDest for r3 should be p4 (from input)")
      
      assert(dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "RAT write should be enabled")
      assert(dut.renameUnit.io.ratWritePorts(0).archReg.toInt == 3, "RAT write should target r3")
      assert(dut.renameUnit.io.ratWritePorts(0).physReg.toInt == 4, "RAT write should use p4")
    }
  }

  test("RenameUnit_RAW_Hazard_Sequence") {
    val renameWidth = 1
    val numPhys = 16  // Use more physical registers to avoid aliasing
    val numArch = 8
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    
    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling()

      println("=== Testing RAW Hazard Sequence ===")
      
      // Instruction 1: addi r1, r0, 100 (r1 gets new physical register)
      println("Step 1: addi r1, r0, 100")
      driveDecodedUop(
        dut.io.decodedUopsIn(0),
        pc = 0x0, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 1, // r1
        useArchSrc1 = true, archSrc1Idx = 0,     // r0
        useArchSrc2 = false
      )
      dut.io.allocatedPhysRegsIn(0) #= 8 // Allocate p8 for r1
      
      dut.clockDomain.waitSampling()
      
      val uop1 = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Instruction 1 result: $uop1")
      assert(uop1.physDestIdx == 8, s"r1 should be mapped to p8, got p${uop1.physDestIdx}")
      assert(uop1.physSrc1Idx == 0, s"r0 should be mapped to p0, got p${uop1.physSrc1Idx}")
      
      // Instruction 2: addi r2, r0, 200 (r2 gets new physical register)  
      println("Step 2: addi r2, r0, 200")
      driveDecodedUop(
        dut.io.decodedUopsIn(0),
        pc = 0x4, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 2, // r2
        useArchSrc1 = true, archSrc1Idx = 0,     // r0
        useArchSrc2 = false
      )
      dut.io.allocatedPhysRegsIn(0) #= 9 // Allocate p9 for r2
      
      dut.clockDomain.waitSampling()
      
      val uop2 = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Instruction 2 result: $uop2")
      assert(uop2.physDestIdx == 9, s"r2 should be mapped to p9, got p${uop2.physDestIdx}")
      assert(uop2.physSrc1Idx == 0, s"r0 should be mapped to p0, got p${uop2.physSrc1Idx}")
      
      // Instruction 3: add r3, r1, r2 (RAW dependency on both r1 and r2)
      println("Step 3: add r3, r1, r2 (RAW dependency)")
      driveDecodedUop(
        dut.io.decodedUopsIn(0),
        pc = 0x8, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 3, // r3
        useArchSrc1 = true, archSrc1Idx = 1,     // r1 (should use p8)
        useArchSrc2 = true, archSrc2Idx = 2      // r2 (should use p9)
      )
      dut.io.allocatedPhysRegsIn(0) #= 10 // Allocate p10 for r3
      
      dut.clockDomain.waitSampling()
      
      val uop3 = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Instruction 3 result: $uop3")
      
      // Critical assertions for RAW hazard handling
      assert(uop3.physDestIdx == 10, s"r3 should be mapped to p10, got p${uop3.physDestIdx}")
      assert(uop3.physSrc1Idx == 8, s"r1 should be mapped to p8 (from instruction 1), got p${uop3.physSrc1Idx}")
      assert(uop3.physSrc2Idx == 9, s"r2 should be mapped to p9 (from instruction 2), got p${uop3.physSrc2Idx}")
      
      // Wait for mapping table to be updated on clock edge
      dut.clockDomain.waitSampling()
      
      // Additional verification: Check that the mapping table is correctly updated
      println("=== Verification: Final mapping table state ===")
      println(s"r1 -> p${dut.renameMapTable.rratMapReg.mapping(1).toInt}")
      println(s"r2 -> p${dut.renameMapTable.rratMapReg.mapping(2).toInt}")
      println(s"r3 -> p${dut.renameMapTable.rratMapReg.mapping(3).toInt}")
      
      assert(dut.renameMapTable.rratMapReg.mapping(1).toInt == 8, "r1 should map to p8 in table")
      assert(dut.renameMapTable.rratMapReg.mapping(2).toInt == 9, "r2 should map to p9 in table") 
      assert(dut.renameMapTable.rratMapReg.mapping(3).toInt == 10, "r3 should map to p10 in table")
      
      println("✅ RAW Hazard sequence test passed!")
    }
  }

  test("RenameUnit_RAW_Internal_Hazard (e.g. add r1, r1, r2)") {
      val renameWidth = 1
    val numPhys = 16
    val numArch = 8
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    
    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      println("=== Testing Correct Handling of Self-Dependency (add r1, r1, r2) ===")

      // --- Step 1: Setup ---
      // First, establish initial mappings for r1 and r2 so they don't have their default identity mappings.
      // Instruction 1: addi r1, r0, 5   (maps r1 -> p8)
      println("Step 1: Setup - 'addi r1, r0, 5' to map r1 -> p8")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x100, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 1, // dest: r1
        useArchSrc1 = true, archSrc1Idx = 0
      )
      dut.io.allocatedPhysRegsIn(0) #= 8 // r1 is allocated p8
      dut.clockDomain.waitSampling()

      // Instruction 2: addi r2, r0, 99  (maps r2 -> p9)
      println("Step 1: Setup - 'addi r2, r0, 99' to map r2 -> p9")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x104, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 2, // dest: r2
        useArchSrc1 = true, archSrc1Idx = 0
      )
      dut.io.allocatedPhysRegsIn(0) #= 9 // r2 is allocated p9
      dut.clockDomain.waitSampling()

      // At this point, the RAT state should be: r1 -> p8, r2 -> p9.
      println("\nRAT state before test: r1 is mapped to p8, r2 is mapped to p9")

      // --- Step 2: The Trigger Instruction ---
      // Now, issue the instruction with the self-dependency.
      println("\nStep 2: Trigger - 'add r1, r1, r2'")
      driveDecodedUop(
        dut.io.decodedUopsIn(0),
        pc = 0x108, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 1, // Dest is r1
        useArchSrc1 = true, archSrc1Idx = 1,     // Src1 is also r1
        useArchSrc2 = true, archSrc2Idx = 2      // Src2 is r2
      )
      // The free list allocates a new physical register for the destination r1.
      dut.io.allocatedPhysRegsIn(0) #= 10 // New destination for r1 is p10
      
      dut.clockDomain.waitSampling()

      // --- Step 3: Verification ---
      // We must check if the renamed uop has the correct physical register indices.
      println("\nStep 3: Verification")
      val uop = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Result: $uop")

      // CRITICAL ASSERTION: The source operand (physSrc1) must be the *old* mapping of r1 (p8).
      // If this fails, it means the hardware is incorrectly using the new destination (p10) as a source.
      assert(uop.physSrc1Idx == 8, s"LOGIC ERROR: Source r1 should use its old mapping (p8), but got p${uop.physSrc1Idx}. This indicates an incorrect self-bypass.")
      
      // Check other operands for correctness.
      assert(uop.physSrc2Idx == 9, s"RAT Read FAILED: Source r2 should be mapped to p9, but got p${uop.physSrc2Idx}")
      assert(uop.physDestIdx == 10, s"Allocation FAILED: New destination r1 should be mapped to p10, but got p${uop.physDestIdx}")
      assert(uop.oldPhysDestIdx == 8, s"Old Destination FAILED: The old physical register for r1 should be p8, but got p${uop.oldPhysDestIdx}")

      println("\n✅ Correct handling of self-dependency test passed!")
      
      // --- Optional: Verify final RAT state ---
      driveIdle(dut) // Drive an idle cycle to let the last write commit and be readable
      println("\nVerifying final RAT state after one cycle...")
      assert(dut.renameMapTable.rratMapReg.mapping(1).toInt == 10, s"Final RAT state for r1 should be p10, but it is p${dut.renameMapTable.rratMapReg.mapping(1).toInt}")
      assert(dut.renameMapTable.rratMapReg.mapping(2).toInt == 9, s"Final RAT state for r2 should be p9, but it is p${dut.renameMapTable.rratMapReg.mapping(2).toInt}")
      println("Final RAT state is correct.")
    }
}
test("Bug 1 - RenameUnit Self-Dependency Error (e.g. add r1, r1, r2)") {
    val renameWidth = 1
    val numPhys = 16
    val numArch = 8
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    
    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      println("=== Testing Bug 1: RenameUnit Self-Dependency (add r1, r1, r2) ===")
      
      // Step 1: Establish an initial mapping for r1.
      // Instruction: addi r1, r0, 5  (maps r1 -> p8)
      println("Step 1: Setup - 'addi r1, r0, 5' to map r1 -> p8")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x100, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 1, useArchSrc1 = true, archSrc1Idx = 0
      )
      dut.io.allocatedPhysRegsIn(0) #= 8 // r1 is allocated p8
      dut.clockDomain.waitSampling()

      // Step 2: The trigger instruction with self-dependency.
      // Instruction: add r1, r1, r2
      // We expect the *source* r1 to use the *old* physical register (p8).
      // The *destination* r1 will be allocated a *new* physical register (p10).
      println("\nStep 2: Trigger - 'add r1, r1, r2'")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x104, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 1, // Dest: r1
        useArchSrc1 = true, archSrc1Idx = 1,     // Src1: r1
        useArchSrc2 = true, archSrc2Idx = 2      // Src2: r2
      )
      dut.io.allocatedPhysRegsIn(0) #= 10 // New destination for r1 is p10
      
      dut.clockDomain.waitSampling()

      // Step 3: Verification
      // The buggy code will incorrectly bypass the new p10 to physSrc1.
      // The correct code will read p8 from the RAT for physSrc1.
      println("\nStep 3: Verification")
      val uop = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Result: $uop")

      // CRITICAL ASSERTION FOR BUG 1
      assert(uop.physSrc1Idx == 8, s"BUG 1 DETECTED: Source r1 should use its old mapping (p8), but got p${uop.physSrc1Idx}. The new destination was incorrectly bypassed to the source.")
      
      // Other correctness checks
      assert(uop.physSrc2Idx == 2, s"Source r2 should be initially mapped to p2, but got p${uop.physSrc2Idx}")
      assert(uop.physDestIdx == 10, s"Destination r1 should be newly mapped to p10, but got p${uop.physDestIdx}")
      assert(uop.oldPhysDestIdx == 8, s"Old destination for r1 should be p8, but got p${uop.oldPhysDestIdx}")

      println("\n✅ Bug 1 Test Passed (or would have failed on buggy code).")
    }
  }


  test("Bug 2 - RAT Missing Write-to-Read Bypass") {
    val renameWidth = 1
    val numPhys = 16
    val numArch = 8
    val pCfg = defaultPipelineConfig(renameWidth, numPhysRegs = numPhys, archGprCount = numArch)
    val rCfg = defaultRatConfig(pCfg.archGprCount, pCfg.physGprCount, renameWidth)
    
    SimConfig.withWave.compile(new RenameUnitTestBench(pCfg, rCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      println("=== Testing Bug 2: RAT Missing Write-to-Read Bypass ===")
      
      // Step 1: Write to r5. This creates a write request for the RAT.
      // Instruction: addi r5, r0, 10 (maps r5 -> p8)
      // This write will only be committed to the RAT's internal register at the clock edge.
      println("Step 1 (Cycle 0): 'addi r5, r0, 10' writes to r5, allocating p8.")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x200, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 5, useArchSrc1 = true, archSrc1Idx = 0
      )
      dut.io.allocatedPhysRegsIn(0) #= 8 // r5 is allocated p8
      dut.clockDomain.waitSampling()
      
      val uop1 = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Result 1: $uop1")
      assert(uop1.physDestIdx == 8, "Cycle 0: r5 should be mapped to p8")

      // Step 2: In the very next cycle, read from r5.
      // Instruction: addi r6, r5, 20
      // The RAT's read port must get the value from the *pending write* of the previous cycle (p8),
      // not from the RAT's internal register which still holds the old value (p5).
      println("\nStep 2 (Cycle 1): 'addi r6, r5, 20' reads r5 immediately.")
      driveDecodedUop(
        dut.io.decodedUopsIn(0), pc = 0x204, isValid = true, uopCode = BaseUopCode.ALU, exeUnit = ExeUnitType.ALU_INT,
        writeArchDestEn = true, archDestIdx = 6, // Dest: r6
        useArchSrc1 = true, archSrc1Idx = 5      // Src: r5
      )
      dut.io.allocatedPhysRegsIn(0) #= 9 // r6 is allocated p9
      dut.clockDomain.waitSampling()

      // Step 3: Verification
      println("\nStep 3: Verification")
      val uop2 = RenamedUopData.from(dut.io.renamedUopsOut(0))
      println(s"Result 2: $uop2")
      
      // CRITICAL ASSERTION FOR BUG 2
      assert(uop2.physSrc1Idx == 8, s"BUG 2 DETECTED: RAT should have bypassed the write from the previous cycle, mapping source r5 to p8. Instead, it returned the stale mapping p${uop2.physSrc1Idx}.")

      // Other correctness checks
      assert(uop2.physDestIdx == 9, s"Destination r6 should be newly mapped to p9, but got p${uop2.physDestIdx}")
      assert(uop2.oldPhysDestIdx == 6, s"Old destination for r6 should be its initial mapping p6, but got p${uop2.oldPhysDestIdx}")

      // Final check: after this cycle, the RAT state itself should be updated
      driveIdle(dut)
      assert(dut.renameMapTable.rratMapReg.mapping(5).toInt == 8, "RAT internal state for r5 was not updated to p8.")
      assert(dut.renameMapTable.rratMapReg.mapping(6).toInt == 9, "RAT internal state for r6 was not updated to p9.")
      
      println("\n✅ Bug 2 Test Passed (or would have failed on buggy code).")
    }
  }

  thatsAll()
}
