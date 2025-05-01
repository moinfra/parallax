package boson.plugins
import spinal.core._, spinal.lib._, boson._
class RenamePlugin extends Plugin[BosonArch] {
  override def setup(pipeline: BosonArch): Unit = {}
  override def build(pipeline: BosonArch): Unit = {
    import pipeline._, pipeline.config._
    // Logic would go into decodeRename or renameDispatch stages
    renameDispatch plug new Area {
      report(L"[Rename] Placeholder - No logic implemented.")
    }
  }
}
