package parallax.common

import spinal.core._
import spinal.lib._

import parallax.common._
import parallax.utilities._
import parallax.components.display.EightSegmentDisplayController
import spinal.lib.KeepAttribute.keep

object DebugValue {
  // 0x00 ~ 0xff
  val DCACHE_INIT = 0x01
  val FETCH_START = 0x11
  val FETCH_FIRE = 0x12
  val DECODE_FIRE = 0x13
  val ROBALLOC_FIRE = 0x14
  val RENAME_FIRE = 0x15
  val DISPATCH_FIRE = 0x16
  val ISSUE_FIRE = 0x17
  val EXEC_FIRE = 0x18
  val COMMIT_FIRE = 0x19

  // Error
  val REG_WRITE_CONFLICT = 0xE0
}

trait DebugDisplayService extends Service {
  val valueReg = Reg(UInt(8 bits)) init (0)

  def setDebugValue(value: UInt, expectIncr: Boolean = false): Unit
  def getDpyOutputs(): (Bits, Bits)

  def setDebugValueOnce(cond: Bool, value: UInt, expectIncr: Boolean = false): Unit = {
    val oneshot = new OneShot()
    if (expectIncr) { oneshot.io.triggerIn := cond && (valueReg < value) }
    else { oneshot.io.triggerIn := cond }
    when(oneshot.io.pulseOut) {
      setDebugValue(value, expectIncr)
    }
  }
}

class DebugDisplayPlugin extends Plugin with DebugDisplayService {
  val hw = create early new Area {
    val dpyController = new EightSegmentDisplayController()
  }
  val logic = create late new Area {
    val displayArea = new Area {
      val divider = new FrequencyDivider(100000000, 1) // 100MHz -> 1Hz
      val updateTick = divider.io.tick

      val dpToggle = Reg(Bool()) init (False)
      when(updateTick) {
        dpToggle := !dpToggle // 交替闪烁证明 clk 连接正确
      }
    }

    hw.dpyController.io.value := valueReg
    hw.dpyController.io.dp0 := !displayArea.dpToggle
    hw.dpyController.io.dp1 := displayArea.dpToggle
  }

  override def setDebugValue(value: UInt, expectIncr: Boolean = false): Unit = {
    val _value = value.resized
    if (expectIncr) {
      when(valueReg < _value) {
        valueReg := _value
        report(L"[DbgSvc] Set (expect incremental) value to 0x${_value}")
      }
    } else {
      valueReg := _value
      report(L"[DbgSvc] Set value to 0x${_value}")
    }
  }

  override def getDpyOutputs(): (Bits, Bits) = (hw.dpyController.io.dpy0_out, hw.dpyController.io.dpy1_out)
}

class SimDebugDisplayPlugin extends Plugin with DebugDisplayService {

  override def setDebugValue(value: UInt, expectIncr: Boolean = false): Unit = {
    report(L"Call setDebugValue")
    val _value = value.resized
    if (expectIncr) {
      when(valueReg < _value) {
        valueReg := _value
        report(L"[SimDbgSvc] Set (expect incremental) value to 0x${_value}")
      }
    } else {
      valueReg := _value
      report(L"[SimDbgSvc] Set value to 0x${_value}")
    }
  }

  override def getDpyOutputs(): (Bits, Bits) = {
    // Split the 16-bit valueReg into two 8-bit values to mimic the hardware interface
    val dpy0_out = Bits(8 bits)
    val dpy1_out = Bits(8 bits)

    dpy0_out := valueReg(7 downto 0).asBits // Lower byte
    dpy1_out := valueReg(15 downto 8).asBits // Upper byte

    (dpy0_out, dpy1_out)
  }
}

class FrequencyDivider(inputFreq: Int, outputFreq: Int) extends Component {
  val io = new Bundle {
    val tick = out Bool ()
  }

  val divideRatio = inputFreq / outputFreq
  val counterMax = divideRatio - 1
  val counterWidth = log2Up(divideRatio)

  val counter = Reg(UInt(counterWidth bits)) init (0)
  io.tick := counter === counterMax

  when(io.tick) {
    counter := 0
  } otherwise {
    counter := counter + 1
  }
}

class OneShot extends Component {
  val io = new Bundle {
    // Input: The external event that triggers the one-shot logic.
    val triggerIn = in Bool ()

    // Output: A single-cycle pulse. Will be True for one clock cycle
    //         the first time `triggerIn` is high, and False otherwise.
    val pulseOut = out Bool ()
  }

  // Internal state register. It acts as a "has fired" flag.
  // It's initialized to False, meaning it has not fired yet.
  val hasFired = Reg(Bool()) init (False)

  // Default assignment for the output pulse.
  io.pulseOut := False

  // The core logic:
  // Check if the trigger is active AND we haven't fired before.
  when(io.triggerIn && !hasFired) {
    io.pulseOut := True // Generate the output pulse for this cycle.
    hasFired := True // Set the flag to True to prevent future firing.
  }
}
