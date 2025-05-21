package scala

import spinal.tester.SpinalSimFunSuite
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxLogger.warning

class CustomSpinalSimFunSuite extends SpinalSimFunSuite {
    
  onlyVerilator

  def simConfig = SimConfig.withWave // FST is generally preferred over VCD

  val tests = ArrayBuffer[(String, () => Unit)]()
  val testsOnly = ArrayBuffer[(String, () => Unit)]()
  override def test(testName: String)(testFun: => Unit): Unit = {
    tests += ((testName, () => testFun))
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
}
