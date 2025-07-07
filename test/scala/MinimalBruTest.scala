// filename: test/scala/MinimalBruTest.scala
package test.scala

import org.scalatest.funsuite.AnyFunSuite
import parallax.common._
import parallax.execute.BranchEuPlugin
import parallax.common.PhysicalRegFilePlugin
import parallax.components.bpu.BpuPipelinePlugin
import parallax.utilities._
import spinal.core._
import spinal.core.sim._

class MinimalBruTest extends CustomSpinalSimFunSuite {

  test("MinimalBru_Creation_Test") {
    val pCfg = PipelineConfig()
    
    // 添加BRU的基本依赖
    val compiled = SimConfig.withFstWave.compile(new Component {
      val framework = new Framework(
        Seq(
          new PhysicalRegFilePlugin(pCfg.physGprCount, pCfg.dataWidth),
          new BpuPipelinePlugin(pCfg),
          new BranchEuPlugin("TestBRU", pCfg)
        )
      )
    })
    
    compiled.doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(period = 10)
      cd.waitSampling(5)
      println("SUCCESS: BRU creation test passed")
    }
  }

  thatsAll()
}