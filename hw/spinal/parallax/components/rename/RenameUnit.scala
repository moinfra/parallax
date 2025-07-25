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
  val flush = in(Bool) default(False)
  val renamedUopsOut = out(Vec(HardType(RenamedUop(pipelineConfig)), pipelineConfig.renameWidth))
  val numPhysRegsRequired = out(UInt(log2Up(pipelineConfig.renameWidth + 1) bits))
  val ratReadPorts = Vec(master(RatReadPort(ratConfig)), ratConfig.numReadPorts)
  val ratWritePorts = Vec(master(RatWritePort(ratConfig)), ratConfig.numWritePorts)

  override def asMaster(): Unit = {
    out(decodedUopsIn, physRegsIn, flush)
    in(renamedUopsOut, numPhysRegsRequired)
    ratReadPorts.foreach(slave(_))
    ratWritePorts.foreach(slave(_))
  }
}

class RenameUnit(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val freeListConfig: SimpleFreeListConfig
) extends Component {

  val io = slave(RenameUnitIo(pipelineConfig, ratConfig))

  // 假设 renameWidth = 1
  val slotIdx = 0
  val decodedUop = io.decodedUopsIn(slotIdx)
  val renamedUop = io.renamedUopsOut(slotIdx)

  val uopNeedsNewPhysDest = decodedUop.isValid && decodedUop.writeArchDestEn && (decodedUop.archDest.isGPR || decodedUop.archDest.isFPR) && !io.flush
  io.numPhysRegsRequired := Mux(io.flush, U(0, log2Up(pipelineConfig.renameWidth + 1) bits), uopNeedsNewPhysDest.asUInt.resized)


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
      report(
        Seq(
            L"[RegRes|RenameUnit] Rename for uop@${decodedUop.pc}: archDest=${decodedUop.archDest.idx} -> physReg=${io.physRegsIn(slotIdx)} (isFPR=${decodedUop.archDest.isFPR})",
            L"Src1: archSrc1=${decodedUop.archSrc1.idx} -> physReg=${renameInfo.physSrc1.idx} (isFPR=${decodedUop.archSrc1.isFPR}, bypassed=0)",
            L"Src2: archSrc2=${decodedUop.archSrc2.idx} -> physReg=${renameInfo.physSrc2.idx} (isFPR=${decodedUop.archSrc2.isFPR}, bypassed=0)",
            L"oldPhysDest: archDest=${decodedUop.archDest.idx} -> oldPhysReg=${renameInfo.oldPhysDest.idx} (isFPR=${decodedUop.archDest.isFPR})",
          )
        )
    } elsewhen(decodedUop.isValid) {
      report(
        Seq(
          L"[RegRes|RenameUnit] Mapping oprands for uop@${decodedUop.pc}: archSrc1=${decodedUop.archSrc1.idx} -> physReg=${renameInfo.physSrc1.idx} (isFPR=${decodedUop.archSrc1.isFPR}, bypassed=0)",
          L"archSrc2=${decodedUop.archSrc2.idx} -> physReg=${renameInfo.physSrc2.idx} (isFPR=${decodedUop.archSrc2.isFPR}, bypassed=0)",
        )
      )
    }
  }

  // ILA
  val debugDecodedArchSrc2 = RegNext(decodedUop.archSrc2.idx) addAttribute("MARK_DEBUG", "TRUE") addAttribute("DONT_TOUCH", "TRUE") setCompositeName(this, "debugDecodedArchSrc2")
  val debugDecodedUseArchSrc2 = RegNext(decodedUop.useArchSrc2) addAttribute("MARK_DEBUG", "TRUE") addAttribute("DONT_TOUCH", "TRUE") setCompositeName(this, "debugDecodedUseArchSrc2")
  val debugRenamedPhysSrc2 = RegNext(renameInfo.physSrc2.idx) addAttribute("MARK_DEBUG", "TRUE") addAttribute("DONT_TOUCH", "TRUE") setCompositeName(this, "debugRenamedPhysSrc2")

}
