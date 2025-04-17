package boson.plugins
import spinal.core._, spinal.lib._, boson._
class DispatchPlugin extends Plugin[Boson] {
  override def setup(pipeline: Boson): Unit = {}
  override def build(pipeline: Boson): Unit = {
     import pipeline._, pipeline.config._
     // Logic would go into renameDispatch stage
     renameDispatch plug new Area {
        report(L"[Dispatch] Placeholder - No logic implemented.")
     }
  }
}
