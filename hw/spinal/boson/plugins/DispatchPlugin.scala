package boson.plugins
import spinal.core._, spinal.lib._, boson._
class DispatchPlugin extends Plugin[BosonArch] {
  override def setup(pipeline: BosonArch): Unit = {}
  override def build(pipeline: BosonArch): Unit = {
     import pipeline._, pipeline.config._
     // Logic would go into renameDispatch stage
     renameDispatch plug new Area {
        report(L"[Dispatch] Placeholder - No logic implemented.")
     }
  }
}
