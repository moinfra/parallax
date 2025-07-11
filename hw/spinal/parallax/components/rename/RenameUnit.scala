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

  // 假设 renameWidth = 1
  val slotIdx = 0
  val decodedUop = io.decodedUopsIn(slotIdx)
  val renamedUop = io.renamedUopsOut(slotIdx)

  val uopNeedsNewPhysDest = decodedUop.isValid && decodedUop.writeArchDestEn && (decodedUop.archDest.isGPR || decodedUop.archDest.isFPR)
  io.numPhysRegsRequired := uopNeedsNewPhysDest.asUInt.resized

  // --- 1. 驱动所有RAT读端口 ---
  val physSrc1Port    = io.ratReadPorts(0)
  val physSrc2Port    = io.ratReadPorts(1)
  val oldDestReadPort = io.ratReadPorts(2)

  physSrc1Port.archReg    := Mux(decodedUop.useArchSrc1, decodedUop.archSrc1.idx, U(0))
  physSrc2Port.archReg    := Mux(decodedUop.useArchSrc2, decodedUop.archSrc2.idx, U(0))
  oldDestReadPort.archReg := Mux(uopNeedsNewPhysDest, decodedUop.archDest.idx, U(0))

  // --- 2. 构建输出 (renamedUop) ---
  // 直通部分
  renamedUop.decoded      := decodedUop
  renamedUop.uniqueId.assignDontCare()
  renamedUop.robPtr.assignDontCare()
  renamedUop.dispatched   := False
  renamedUop.executed     := False
  renamedUop.hasException := False
  renamedUop.exceptionCode:= 0

  val renameInfo = renamedUop.rename
  
  // --- 3. 源寄存器映射：总是使用RAT读出的值 ---
  
  // 对 physSrc1 进行赋值 - 不需要bypassing，因为单指令内源和目标相同时应该读取当前值
  renameInfo.physSrc1.idx := physSrc1Port.physReg
  renameInfo.physSrc1IsFpr := decodedUop.archSrc1.isFPR

  // 对 physSrc2 进行赋值 - 同样不需要bypassing
  renameInfo.physSrc2.idx := physSrc2Port.physReg
  renameInfo.physSrc2IsFpr := decodedUop.archSrc2.isFPR

  // 其他字段
  renameInfo.physSrc3.setDefault()
  renameInfo.physSrc3IsFpr := False
  renameInfo.branchPrediction.setDefault()

  // 目的寄存器相关信息
  when(uopNeedsNewPhysDest) {
    renameInfo.writesToPhysReg    := True
    // oldPhysDest 永远从RAT读取，它代表写入前的状态，所以不需要旁路
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

  // --- 4. 驱动RAT写端口 ---
  val ratWp = io.ratWritePorts(slotIdx)
  ratWp.wen     := uopNeedsNewPhysDest
  ratWp.archReg := decodedUop.archDest.idx
  ratWp.physReg := io.physRegsIn(slotIdx)

  val logger = new Area {
    when(uopNeedsNewPhysDest) {
      report(L"[RenameUnit] Rename: archDest=${decodedUop.archDest.idx} -> physReg=${io.physRegsIn(slotIdx)} (isFPR=${decodedUop.archDest.isFPR})")
      report(L"[RenameUnit] Src1: archSrc1=${decodedUop.archSrc1.idx} -> physReg=${renameInfo.physSrc1.idx} (isFPR=${decodedUop.archSrc1.isFPR}, bypassed=0)")
      report(L"[RenameUnit] Src2: archSrc2=${decodedUop.archSrc2.idx} -> physReg=${renameInfo.physSrc2.idx} (isFPR=${decodedUop.archSrc2.isFPR}, bypassed=0)")
      report(L"[RenameUnit] oldPhysDest: archDest=${decodedUop.archDest.idx} -> oldPhysReg=${renameInfo.oldPhysDest.idx} (isFPR=${decodedUop.archDest.isFPR})")
    }
  }
}
