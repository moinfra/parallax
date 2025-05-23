package parallax.common

import spinal.core._

trait RobIndexedData {
  def getRobIdx(): UInt
}

/** Minimal Trait for messages broadcast over a CDB identifying a target.
  * @tparam K Type of the index/tag (e.g., ROB index).
  */
trait CdbTargetedMessage[K <: Data] extends Bundle {
  def cdbTargetIdx: K // Renamed for brevity
}

/** Concrete completion message from an EU to the ROB.
  * This is what will be arbitrated over the CDBs for ROB writeback.
  * It aligns with the information needed by Parallax's ROBWritebackPort.
  */
case class RobCompletionMsg(val pCfg: PipelineConfig) // pCfg for widths
    extends CdbTargetedMessage[UInt] {

  val robIdx = UInt(pCfg.robIdxWidth)
  val hasException = Bool()
  val exceptionCode = UInt(pCfg.exceptionCodeWidth)

  override def cdbTargetIdx: UInt = robIdx

  def setDefaultSimValues(): this.type = {
    import spinal.core.sim._
    
    robIdx #= 0
    hasException #= false
    exceptionCode #= 0
    this
  }
}
