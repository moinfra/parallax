package scala

import spinal.tester.SpinalSimFunSuite
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxLogger.warning
import spinal.lib.pipeline.Stage
import spinal.core.sim.SimBoolPimper
import spinal.core.Data
import spinal.core.ClockDomain
import spinal.core.sim.SimClockDomainPimper
import spinal.core.fiber.Handle
import parallax.utilities.ParallaxLogger

class CustomSpinalSimFunSuite extends SpinalSimFunSuite {

  onlyVerilator

  def simConfig = SimConfig.withWave // FST is generally preferred over VCD

  val tests = ArrayBuffer[(String, () => Unit)]()
  val testsOnly = ArrayBuffer[(String, () => Unit)]()
  override def test(testName: String)(testFun: => Unit): Unit = {
    println(s"add test $testName")
    tests += ((testName, () => testFun))
  }

  def testSkip(testName: String)(testFun: => Unit): Unit = {
    warning(s"Skipping test $testName")
  }

  def hello() = {
    print("hello")
  }

  def testOnly(testName: String)(testFun: => Unit): Unit = {
    testsOnly += ((testName, () => testFun))
  }

  def thatsAll(): Unit = {
    if (testsOnly.nonEmpty) {
      for ((name, testFn) <- testsOnly) {
        super.test(name)(testFn())
      }
    } else {
      for ((name, testFn) <- tests) {
        super.test(name)(testFn())
      }
    }
  }

  def weakAssert(cond: Boolean, msg: String = "")(implicit line: sourcecode.Line, file: sourcecode.File): Unit = {
    if (!cond) {
      warning("Assertion failed" + (if (msg.nonEmpty) s": $msg" else ""))(line, file)
    }
  }

  def isStageInputFiring(stage: Stage): Boolean = {
    stage.internals.input.ready.toBoolean && stage.internals.input.valid.toBoolean
  }

  def isStreamFiring[T <: Data](stream: spinal.lib.Stream[T]): Boolean = {
    stream.valid.toBoolean && stream.ready.toBoolean
  }

  var __cycleNo = 0
  def enableCycleTicker(cd: ClockDomain): Unit = {
    ParallaxLogger.warning("Cycle Ticker enabled")
    cd.onRisingEdges(() => {
      ParallaxLogger.warning(s"Cycle ${__cycleNo}")
      __cycleNo += 1
    })
  }
}
