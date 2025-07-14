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
  val FETCH_SUCC = 0x12
}

trait DebugDisplayService extends Service {
  def setDebugValue(value: UInt, expectIncr: Boolean = false): Unit
  def setDebugOnce(value: UInt, expectIncr: Boolean = false): Unit
  def getDpyOutputs(): (Bits, Bits)
}

class DebugDisplayPlugin extends Plugin with DebugDisplayService {
  val valueReg = Reg(UInt(8 bits)) init (0)
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

  override def setDebugOnce(value: UInt, expectIncr: Boolean = false): Unit = {
     new Area {
      val isFirst = Reg(Bool()) init (True)
      val isFirstNext = True
      when(isFirst) {
        setDebugValue(value, expectIncr)
        isFirstNext := False
      }
      isFirst := isFirstNext
    }
  }

  override def getDpyOutputs(): (Bits, Bits) = (hw.dpyController.io.dpy0_out, hw.dpyController.io.dpy1_out)
}

class SimDebugDisplayPlugin extends Plugin with DebugDisplayService {

  val valueReg = Reg(UInt(8 bits)) init (0)
  val _preventDCE = Reg(Bool()) init (False)

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

  override def setDebugOnce(value: UInt, expectIncr: Boolean = false): Unit = {
    new Area {
      val isFirst = Reg(Bool()) init (True)
      val isFirstNext = True
      when(isFirst) {
        setDebugValue(value, expectIncr)
        isFirstNext := False
      }
      isFirst := isFirstNext
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
