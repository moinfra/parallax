package boson.plugins
import spinal.core._, spinal.lib._, boson._
class IssuePlugin extends Plugin[BosonArch] {
  override def setup(pipeline: BosonArch): Unit = {}
  override def build(pipeline: BosonArch): Unit = {
     import pipeline._, pipeline.config._
     // Logic would go into issueRegRead stage (before RegRead)
     issueRegRead plug new Area {
        report(L"[Issue] Placeholder - No logic implemented.")
     }
  }
}
