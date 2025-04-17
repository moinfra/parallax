package boson.plugins
import spinal.core._, spinal.lib._, boson._
class RenamePlugin extends Plugin[Boson] {
  override def setup(pipeline: Boson): Unit = {}
  override def build(pipeline: Boson): Unit = {
    import pipeline._, pipeline.config._
    // Logic would go into decodeRename or renameDispatch stages
    renameDispatch plug new Area {
      report(L"[Rename] Placeholder - No logic implemented.")
    }
  }
}
