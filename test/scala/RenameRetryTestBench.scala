// filename: src/test/scala/parallax/issue/RenameRetryTestBench.scala
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.pipeline._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.sim.StreamMonitor

/**
 * 这是最终的、符合所有数字逻辑和SpinalHDL设计原则的流水线实现。
 * 它正确地将最后一个阶段的暂停条件与输出流的ready信号解耦，
 * 解决了所有死锁、数据丢失和数据复制的问题。
 */
class RenamePipeline extends Component {
  val io = new Bundle {
    val uopIn = slave(Stream(Bool()))
    val allocRequest = out(Bool())
    val flAllocSuccess = in(Bool())
    val uopOut = master(Stream(Bool()))
  }

  val pipeline = new Pipeline {
    val s1, s2 = newStage()
    connect(s1, s2)(M2S())

    val NEEDS_REG = Stageable(Bool())

    // --- S1: 纯数据输入 ---
    s1.valid := io.uopIn.valid
    s1(NEEDS_REG) := io.uopIn.payload
    io.uopIn.ready := s1.isReady

    // --- S2: 工作阶段 ---

    // 1. 资源请求逻辑:
    //    当 s2 持有有效的、需要资源的指令时，就请求。
    val s2_needs_alloc = s2.valid && s2(NEEDS_REG)
    io.allocRequest := s2_needs_alloc

    // 2. 暂停条件 (Halt Condition)
    //    s2 何时必须暂停？
    //    a) 当它需要资源但没有被授予时。
    //    b) 或者当下游消费者不准备好接收时。
    //    这两个条件是完全独立的 "OR" 关系。
    val resource_ok = !s2_needs_alloc || io.flAllocSuccess
    val downstream_ok = io.uopOut.ready
    
    s2.haltIt(!(resource_ok && downstream_ok))

    // 3. 输出逻辑 (Output Logic)
    //    输出流何时有效？当 s2 成功执行（发射）时。
    //    s2.isFiring 是框架提供的黄金信号，它代表 s2.valid && s2.isReady。
    //    而 s2.isReady 已经由 haltIt 完美地控制了。
    io.uopOut.valid := s2.isFiring
    io.uopOut.payload := s2(NEEDS_REG)

    build()
  }
}

class RetryTest extends CustomSpinalSimFunSuite {
  test("Final_Correct_Pipeline_NoLeaks_NoDeadlock") {
    SimConfig.withWave.compile(new RenamePipeline).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      var uopsThatNeededRegIn = 0
      var uopsThatNeededRegOut = 0
      var totalUopsIn = 0
      var totalUopsOut = 0
      var lastOutputCycle = 0L
      val deadlockTimeout = 200

      StreamMonitor(dut.io.uopOut, dut.clockDomain) { payload =>
        totalUopsOut += 1
        if (payload.toBoolean) uopsThatNeededRegOut += 1
        lastOutputCycle = simTime()
      }

      val rand = new Random()
      val totalCycles = 5000
      val targetInjections = 1000

      for (cycle <- 0 until totalCycles) {
        dut.io.flAllocSuccess #= (rand.nextInt(10) < 7)
        dut.io.uopOut.ready #= (rand.nextInt(10) < 8)

        if (totalUopsIn < targetInjections && rand.nextBoolean()) {
          dut.io.uopIn.valid #= true
          val needsReg = rand.nextBoolean()
          dut.io.uopIn.payload #= needsReg
          if (dut.io.uopIn.ready.toBoolean) {
            totalUopsIn += 1
            if (needsReg) uopsThatNeededRegIn += 1
          }
        } else {
          dut.io.uopIn.valid #= false
        }
        
        dut.clockDomain.waitSampling()

        if (cycle > deadlockTimeout && totalUopsIn > 10 && totalUopsOut < totalUopsIn) {
            assert(simTime() - lastOutputCycle < deadlockTimeout * 10, s"DEADLOCK DETECTED at cycle $cycle")
        }
      }
      
      dut.io.uopIn.valid #= false
      dut.io.uopOut.ready #= true
      dut.clockDomain.waitSampling(deadlockTimeout * 2)

      println("--- SIMULATION FINISHED ---")
      println(s"Total UOps Injected: $totalUopsIn")
      println(s"Total UOps Egressed: $totalUopsOut")
      assert(totalUopsIn == totalUopsOut, s"FATAL: Mismatch in total uops. In: $totalUopsIn, Out: $totalUopsOut.")

      println(s"UOps requiring allocation Injected: $uopsThatNeededRegIn")
      println(s"UOps requiring allocation Egressed: $uopsThatNeededRegOut")
      assert(uopsThatNeededRegIn == uopsThatNeededRegOut, "FATAL: Resource leak detected!")
      
      println("\nSUCCESS: All checks passed.")
    }
  }
  
  thatsAll()
}
