// filename: src/test/scala/parallax/issue/RenameUnitSpec.scala
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

  renameMapTable.mapReg.mapping.simPublic()
  renameUnit.io.simPublic()
  
  renameMapTable.io.checkpointSave.ready := True
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
      
      // -- MODIFICATION START: Use correct member names for assertion --
      assert(dut.renameUnit.io.ratWritePorts(0).wen.toBoolean, "RAT write should be enabled")
      assert(dut.renameUnit.io.ratWritePorts(0).archReg.toInt == 3, "RAT write should target r3")
      assert(dut.renameUnit.io.ratWritePorts(0).physReg.toInt == 4, "RAT write should use p4")
      // -- MODIFICATION END --
    }
  }

  thatsAll()
}
