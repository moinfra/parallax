// filename: src/main/scala/parallax/components/rename/RenameUnit.scala
package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.ParallaxSim

case class RenameUnitIo(
    pipelineConfig: PipelineConfig,
    ratConfig: RenameMapTableConfig
) extends Bundle with IMasterSlave {
  val decodedUopsIn = in(Vec(HardType(DecodedUop(pipelineConfig)), pipelineConfig.renameWidth))
  val physRegsIn = in(Vec(HardType(UInt(ratConfig.physRegIdxWidth)), pipelineConfig.renameWidth))
  val renamedUopsOut = out(Vec(HardType(RenamedUop(pipelineConfig)), pipelineConfig.renameWidth))
  val numPhysRegsRequired = out(UInt(log2Up(pipelineConfig.renameWidth + 1) bits))
  val ratReadPorts = Vec(master(RatReadPort(ratConfig)), ratConfig.numReadPorts)
  val ratWritePorts = Vec(master(RatWritePort(ratConfig)), ratConfig.numWritePorts)

  override def asMaster(): Unit = {
    out(decodedUopsIn, physRegsIn)
    in(renamedUopsOut, numPhysRegsRequired)
    ratReadPorts.foreach(slave(_))
    ratWritePorts.foreach(slave(_))
  }
}

class RenameUnit(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val freeListConfig: SuperScalarFreeListConfig
) extends Component {

  val io = slave(RenameUnitIo(pipelineConfig, ratConfig))

  val uopNeedsNewPhysDest = Vec.tabulate(pipelineConfig.renameWidth) { i =>
    val uop = io.decodedUopsIn(i)
    uop.isValid && uop.writeArchDestEn && (uop.archDest.isGPR || uop.archDest.isFPR)
  }
  
  io.numPhysRegsRequired := CountOne(uopNeedsNewPhysDest).resized

  for (slotIdx <- 0 until pipelineConfig.renameWidth) {
    val decodedUop = io.decodedUopsIn(slotIdx)
    val renamedUop = io.renamedUopsOut(slotIdx)
    
    // -- MODIFICATION START: Build the output bundle field-by-field without setDefault --

    // 1. Connect the parts that are always passed through
    renamedUop.decoded := decodedUop
    renamedUop.uniqueId.assignDontCare()
    renamedUop.robPtr.assignDontCare()
    // Default values for other fields in RenamedUop
    renamedUop.dispatched := False
    renamedUop.executed := False
    renamedUop.hasException := False
    renamedUop.exceptionCode := 0

    // 2. Drive RAT ports
    val baseReadPortIdx = slotIdx * 3
    val physSrc1Port = io.ratReadPorts(baseReadPortIdx)
    val physSrc2Port = io.ratReadPorts(baseReadPortIdx + 1)
    val oldDestReadPort = io.ratReadPorts(baseReadPortIdx + 2)
    val ratWp = io.ratWritePorts(slotIdx)

    physSrc1Port.archReg := Mux(decodedUop.useArchSrc1, decodedUop.archSrc1.idx, U(0))
    physSrc2Port.archReg := Mux(decodedUop.useArchSrc2, decodedUop.archSrc2.idx, U(0))
    oldDestReadPort.archReg := Mux(uopNeedsNewPhysDest(slotIdx), decodedUop.archDest.idx, U(0))
    
    // 3. Drive the RenameInfo bundle field by field using Mux logic
    val renameInfo = renamedUop.rename

    renameInfo.physSrc1.idx   := physSrc1Port.physReg
    renameInfo.physSrc1IsFpr  := decodedUop.archSrc1.isFPR

    renameInfo.physSrc2.idx   := physSrc2Port.physReg
    renameInfo.physSrc2IsFpr  := decodedUop.archSrc2.isFPR
    
    // Default values for other fields in RenameInfo
    renameInfo.physSrc3.setDefault()
    renameInfo.physSrc3IsFpr := False
    renameInfo.branchPrediction.setDefault()

    // Conditional logic for destination register
    when(uopNeedsNewPhysDest(slotIdx)) {
      renameInfo.writesToPhysReg    := True
      renameInfo.oldPhysDest.idx    := oldDestReadPort.physReg
      renameInfo.oldPhysDestIsFpr   := decodedUop.archDest.isFPR
      renameInfo.allocatesPhysDest  := True
      renameInfo.physDest.idx       := io.physRegsIn(slotIdx)
      renameInfo.physDestIsFpr      := decodedUop.archDest.isFPR
    } .otherwise {
      renameInfo.writesToPhysReg    := False
      renameInfo.oldPhysDest.setDefault()
      renameInfo.oldPhysDestIsFpr   := False
      renameInfo.allocatesPhysDest  := False
      renameInfo.physDest.setDefault()
      renameInfo.physDestIsFpr      := False
    }

    // Drive RAT write port
    ratWp.wen     := uopNeedsNewPhysDest(slotIdx)
    ratWp.archReg := decodedUop.archDest.idx
    ratWp.physReg := io.physRegsIn(slotIdx)
    
    // -- MODIFICATION END --
  }
}
