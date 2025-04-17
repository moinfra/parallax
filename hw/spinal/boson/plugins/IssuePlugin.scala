package boson.plugins
import spinal.core._, spinal.lib._, boson._
class IssuePlugin extends Plugin[Boson] {
  override def setup(pipeline: Boson): Unit = {}
  override def build(pipeline: Boson): Unit = {
     import pipeline._, pipeline.config._
     // Logic would go into issueRegRead stage (before RegRead)
     issueRegRead plug new Area {
        report(L"[Issue] Placeholder - No logic implemented.")
     }
  }
}
