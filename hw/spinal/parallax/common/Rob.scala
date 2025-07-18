package parallax.common

import spinal.core._

trait RobIndexedData {
  def getRobPtr(): UInt
}

/** Concrete completion message from an EU to the ROB.
  * This is what will be arbitrated over the CDBs for ROB writeback.
  * It aligns with the information needed by Parallax's ROBWritebackPort.
  */
case class RobCompletionMsg(val pCfg: PipelineConfig) // pCfg for widths
    extends CdbTargetedMessage[UInt] {

  val robPtr = UInt(pCfg.robPtrWidth)
  val hasException = Bool()
  val exceptionCode = UInt(pCfg.exceptionCodeWidth)

  override def cdbTargetIdx: UInt = robPtr

  def setDefaultSimValues(): this.type = {
    import spinal.core.sim._
    
    robPtr #= 0
    hasException #= false
    exceptionCode #= 0
    this
  }
}
