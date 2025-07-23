package test.scala

import spinal.core._
import spinal.core.sim._

/**
 * A robust cycle timer for performance measurement in SpinalSim.
 * @param cd The clock domain to which the timer is synchronized.
 */
class CycleTimer(implicit cd: ClockDomain) {
  private var startTime: Long = -1L
  private var stopTime: Long = -1L
  private var running: Boolean = false
  private var cycleCount: Long = 0L

  // This block registers a callback that executes on every clock sampling edge.
  cd.onSamplings {
    if (running) {
      cycleCount += 1
    }
  }

  /**
   * Starts or restarts the timer.
   * If the timer was already running, it resets and starts again.
   */
  def start(): Unit = {
    if (running) {
      println("[CycleTimer WARNING] Timer was already running. Resetting and starting again.")
    }
    cycleCount = 0L
    running = true
  }

  /**
   * Stops the timer.
   * @return The total number of elapsed cycles since start().
   */
  def stop(): Long = {
    if (!running) {
      println("[CycleTimer WARNING] stop() called on a non-running timer. Returning last count.")
      return cycleCount
    }
    running = false
    // The last cycle is counted in the onSamplings block before running becomes false.
    // So, cycleCount is the final correct value.
    cycleCount
  }

  /**
   * Gets the current elapsed cycles without stopping the timer.
   * @return The number of elapsed cycles so far.
   */
  def elapsed(): Long = {
    cycleCount
  }

  /**
   * Resets the timer to its initial state.
   */
  def reset(): Unit = {
    running = false
    cycleCount = 0L
  }
}
