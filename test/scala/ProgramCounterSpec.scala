// cmd: testOnly test.scala.ProgramCounterSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.StreamDriver
import org.scalatest.funsuite.AnyFunSuite
import parallax.common.PipelineConfig
import parallax.fetch.ProgramCounter

object TestConfig {
  def pCfg = PipelineConfig(
    resetVector = BigInt("80000000", 16),
    fetchWidth = 2,
  )
}

class ProgramCounterSpec extends AnyFunSuite {
  
  def compiled = SimConfig.withWave.compile(
    new ProgramCounter(pCfg = TestConfig.pCfg)
  )

  test("1. Sequential Fetch Test") {
    compiled.doSim("SequentialFetch") { dut =>
      // --- 初始化 ---
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.softRedirect.valid #= false
      dut.io.hardRedirect.valid #= false
      dut.io.pcStream.ready #= true // 下游始终准备好

      dut.clockDomain.waitSampling()

      // --- 验证启动PC ---
      assert(dut.io.pcStream.valid.toBoolean, "PC stream should be valid at start")
      assert(dut.io.pcStream.payload.toLong == 0x80000000L, "Initial PC should be resetVector")
      println(f"[T=0] Initial PC: 0x${dut.io.pcStream.payload.toLong}%X")

      // --- 验证顺序执行 ---
      for (i <- 1 to 5) {
        dut.clockDomain.waitSampling()
        val expectedPc = 0x80000000L + i * TestConfig.pCfg.fetchGroupBytes
        assert(dut.io.pcStream.valid.toBoolean, s"PC stream should be valid at cycle $i")
        assert(dut.io.pcStream.payload.toLong == expectedPc, s"PC at cycle $i should be $expectedPc")
        println(f"[T=$i] Sequential PC: 0x${dut.io.pcStream.payload.toLong}%X")
      }
    }
  }
  
  test("2. Stall Test") {
    compiled.doSim("Stall") { dut =>
      // --- 初始化 ---
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.softRedirect.valid #= false
      dut.io.hardRedirect.valid #= false
      dut.io.pcStream.ready #= true 

      dut.clockDomain.waitSampling(1) // 等待PC变为 0x80000008
      println(f"[T=1] PC before stall: 0x${dut.io.pcStream.payload.toLong}%X")

      // --- 制造停顿 ---
      println("[T=2] Stalling the pipeline (pcStream.ready = false)")
      dut.io.pcStream.ready #= false
      dut.clockDomain.waitSampling(3) // 停顿3个周期

      // --- 验证PC是否保持不变 ---
      println(f"[T=5] Checking PC after stall")
      assert(dut.io.pcStream.valid.toBoolean, "PC stream should remain valid during stall")
      assert(dut.io.pcStream.payload.toLong == 0x80000008L, "PC must not change during stall")
      println(f"[T=5] PC is still: 0x${dut.io.pcStream.payload.toLong}%X")
      
      // --- 解除停顿 ---
      println("[T=6] Resuming the pipeline (pcStream.ready = true)")
      dut.io.pcStream.ready #= true
      dut.clockDomain.waitSamplingWhere(dut.io.pcStream.ready.toBoolean)
      dut.clockDomain.waitSampling()
      
      // --- 验证PC是否继续执行 ---
      assert(dut.io.pcStream.payload.toLong == 0x80000010L, "PC should advance after stall is released")
      println(f"[T=6] PC advanced to: 0x${dut.io.pcStream.payload.toLong}%X")
    }
  }

  test("3. Soft Redirect Test") {
    compiled.doSim("SoftRedirect") { dut =>
      // --- 初始化 ---
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.softRedirect.valid #= false
      dut.io.hardRedirect.valid #= false
      dut.io.pcStream.ready #= true

      dut.clockDomain.waitSampling(2) // 让PC前进到 0x80000010
      println(f"[T=2] PC before redirect: 0x${dut.io.pcStream.payload.toLong}%X")
      
      // --- 发起软重定向 ---
      println("[T=2] Asserting soft redirect to 0xA0000000")
      dut.io.softRedirect.valid #= true
      dut.io.softRedirect.payload #= 0xA0000000L
      dut.clockDomain.waitSampling()
      
      // --- 验证重定向周期的行为 ---
      // 在重定向请求被断言的那个周期，pcStream应该是无效的
      assert(!dut.io.pcStream.valid.toBoolean, "PC stream must be invalid during the cycle a redirect is asserted")
      println("[T=3] PC stream is invalid, as expected.")
      
      // 撤销重定向请求，模拟单周期脉冲
      dut.io.softRedirect.valid #= false
      dut.clockDomain.waitSampling()
      
      // --- 验证下一周期的PC ---
      assert(dut.io.pcStream.valid.toBoolean, "PC stream must be valid after redirect")
      assert(dut.io.pcStream.payload.toLong == 0xA0000000L, "PC should be updated to the redirected address")
      println(f"[T=4] PC has been redirected to: 0x${dut.io.pcStream.payload.toLong}%X")

      // --- 验证PC从新地址开始顺序执行 ---
      dut.clockDomain.waitSampling()
      assert(dut.io.pcStream.payload.toLong == 0xA0000008L, "PC should increment sequentially from the new address")
      println(f"[T=5] PC is now sequentially fetching at: 0x${dut.io.pcStream.payload.toLong}%X")
    }
  }

  test("4. Hard Redirect Priority Test") {
    compiled.doSim("HardRedirectPriority") { dut =>
      // --- 初始化 ---
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.pcStream.ready #= true
      
      dut.clockDomain.waitSampling(2) // 让PC前进到 0x80000010
      println(f"[T=2] PC before redirect: 0x${dut.io.pcStream.payload.toLong}%X")

      // --- 同时发起软、硬重定向 ---
      println("[T=2] Asserting BOTH soft redirect (to 0xA0000000) and hard redirect (to 0xC0000000)")
      dut.io.softRedirect.valid #= true
      dut.io.softRedirect.payload #= 0xA0000000L
      dut.io.hardRedirect.valid #= true
      dut.io.hardRedirect.payload #= 0xC0000000L
      dut.clockDomain.waitSampling()
      
      // --- 验证重定向周期的行为 ---
      assert(!dut.io.pcStream.valid.toBoolean, "PC stream must be invalid during redirect")
      println("[T=3] PC stream is invalid, as expected.")

      // 撤销请求
      dut.io.softRedirect.valid #= false
      dut.io.hardRedirect.valid #= false
      dut.clockDomain.waitSampling()
      
      // --- 验证PC是否跳转到硬重定向地址 ---
      assert(dut.io.pcStream.valid.toBoolean, "PC stream must be valid after redirect")
      assert(dut.io.pcStream.payload.toLong == 0xC0000000L, "PC must take the hard redirect address due to priority")
      println(f"[T=4] PC correctly redirected to HARD target: 0x${dut.io.pcStream.payload.toLong}%X")

      // --- 验证PC从新地址开始顺序执行 ---
      dut.clockDomain.waitSampling()
      assert(dut.io.pcStream.payload.toLong == 0xC0000008L, "PC should increment sequentially from the hard redirect address")
      println(f"[T=5] PC is now sequentially fetching at: 0x${dut.io.pcStream.payload.toLong}%X")
    }
  }
}
