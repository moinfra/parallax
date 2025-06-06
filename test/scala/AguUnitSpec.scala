package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.lsu._
import parallax.execute.{BypassService, BypassPlugin}
import parallax.utilities._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

class TestSetupPlugin(
    aguIoSetup: AguPort => Unit,
    prfIoSetup: (PrfReadPort, PrfWritePort) => Unit,
    bypassIoSetup: (Flow[AguBypassData]) => Unit
) extends Plugin {
  lazy val aguPlugin = getService[AguPlugin]
  lazy val prfPlugin = getService[PhysicalRegFilePlugin]
  lazy val bypassPlugin = getService[BypassPlugin[AguBypassData]]
  val setup = create early new Area {
    aguPlugin.retain()
    prfPlugin.retain()
    val aguPort = aguPlugin.newAguPort().simPublic()
    aguIoSetup(aguPort)

    val prfWritePort = prfPlugin.newWritePort()
    val prfReadPort = prfPlugin.newReadPort()
    prfIoSetup(prfReadPort, prfWritePort)

    val bypassSource = bypassPlugin.newBypassSource("TestEU")
    bypassIoSetup(bypassSource)
  }
  val logic = create late new Area {
    aguPlugin.release()
    prfPlugin.release()
  }
}

// AGU测试Framework（使用BypassService）
class AguTestFrameworkWithBypass extends Component {

  val io = new Bundle {
    val testInput = slave(Stream(AguInput()))
    val testOutput = master(Stream(AguOutput()))
    val flush = in Bool ()

    val prfWrite = slave(PrfWritePort(6 bits, 32 bits))
    val prfRead = slave(PrfReadPort(6 bits, 32 bits))

    val bypassInject = slave(Flow(AguBypassData()))
  }

  lazy val prfPlugin = (new PhysicalRegFilePlugin(
    numPhysRegs = 64,
    dataWidth = 32 bits
  ))

  lazy val bypassPlugin = (new BypassPlugin[AguBypassData](
    payloadType = HardType(AguBypassData())
  ))

  lazy val aguPlugin = (new AguPlugin(
    supportPcRel = true
  ))

  lazy val testSetupPlugin = (new TestSetupPlugin(
    aguIoSetup = aguPort => {
      aguPort.input << io.testInput
      io.testOutput << aguPort.output
      aguPort.flush := io.flush
    },
    prfIoSetup = (prfReadPort, prfWritePort) => {
      prfWritePort.valid := io.prfWrite.valid
      prfWritePort.address := io.prfWrite.address
      prfWritePort.data := io.prfWrite.data

      prfReadPort.valid := io.prfRead.valid
      prfReadPort.address := io.prfRead.address
      io.prfRead.rsp := prfReadPort.rsp
    },
    bypassIoSetup = bypassSource => {
      bypassSource.valid := io.bypassInject.valid
      bypassSource.payload := io.bypassInject.payload
    }
  ))

  val framework = new Framework(
    Seq(
      prfPlugin,
      bypassPlugin,
      aguPlugin,
      testSetupPlugin
    )
  )

}

class AguPluginWithBypassSpec extends CustomSpinalSimFunSuite {

  // 测试输入参数
  case class AguTestParams(
      basePhysReg: Int,
      immediate: Int,
      accessSize: MemAccessSize.E,
      usePc: Boolean,
      pcVal: Long,
      robId: Int,
      isLoad: Boolean,
      isStore: Boolean,
      physDst: Int
  )

  // 输出快照
  case class AguResultSnapshot(
      address: BigInt,
      alignException: Boolean,
      robId: BigInt,
      isLoad: Boolean,
      isStore: Boolean,
      physDst: BigInt
  )

  // 预载寄存器
  def preloadRegister(
      dut: AguTestFrameworkWithBypass,
      addr: Int,
      data: BigInt,
      clockDomain: ClockDomain
  ): Unit = {
    dut.io.prfWrite.valid #= true
    dut.io.prfWrite.address #= addr
    dut.io.prfWrite.data #= data
    clockDomain.waitSampling()
    dut.io.prfWrite.valid #= false
    clockDomain.waitSampling()
  }

  // 注入旁路数据
  def injectBypassData(
      dut: AguTestFrameworkWithBypass,
      physRegIdx: Int,
      data: BigInt,
      robIdx: Int,
      clockDomain: ClockDomain
  ): Unit = {
    dut.io.bypassInject.valid #= true
    dut.io.bypassInject.payload.physRegIdx #= physRegIdx
    dut.io.bypassInject.payload.physRegData #= data
    dut.io.bypassInject.payload.robIdx #= robIdx
    dut.io.bypassInject.payload.valid #= true
    clockDomain.waitSampling(3)
    dut.io.bypassInject.valid #= false
    clockDomain.waitSampling()
  }

  // 驱动AGU输入
  def driveAguInput(payload: AguInput, params: AguTestParams): Unit = {
    payload.basePhysReg #= params.basePhysReg
    payload.immediate #= params.immediate
    payload.accessSize #= params.accessSize
    payload.usePc #= params.usePc
    payload.pc #= params.pcVal
    payload.robId #= params.robId
    payload.isLoad #= params.isLoad
    payload.isStore #= params.isStore
    payload.physDst #= params.physDst
  }

  test("AGU Plugin with BypassService - Basic Address Calculation") {
    simConfig.compile(new AguTestFrameworkWithBypass()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[AguTestParams]()
      val outputSnapshots = mutable.ArrayBuffer[AguResultSnapshot]()

      // 输入驱动器
      dut.clockDomain.onSamplings {
        if (dut.io.testInput.ready.toBoolean && inputQueue.nonEmpty) {
          val params = inputQueue.dequeue()
          dut.io.testInput.valid #= true
          driveAguInput(dut.io.testInput.payload, params)
        } else {
          dut.io.testInput.valid #= false
        }
      }

      // 输出监控器
      dut.clockDomain.onSamplings {
        if (dut.io.testOutput.valid.toBoolean && dut.io.testOutput.ready.toBoolean) {
          val payload = dut.io.testOutput.payload
          outputSnapshots += AguResultSnapshot(
            payload.address.toBigInt,
            payload.alignException.toBoolean,
            payload.robId.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt
          )
        }
      }

      // 初始化
      dut.io.testOutput.ready #= true
      dut.io.flush #= false
      dut.io.prfRead.valid #= false
      dut.io.bypassInject.valid #= false

      dut.clockDomain.waitSampling(5)

      // 测试用例：使用寄存器数据
      val baseReg = 10
      val baseValue = 0x2000
      val immediate = 300
      val expectedAddr = baseValue + immediate

      preloadRegister(dut, baseReg, baseValue, dut.clockDomain)

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          immediate = immediate,
          accessSize = MemAccessSize.H,
          usePc = false,
          pcVal = 0,
          robId = 15,
          isLoad = true,
          isStore = false,
          physDst = 20
        )
      )

      // 等待结果
      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received")
      val result = outputSnapshots.head

      assert(
        result.address == expectedAddr,
        s"Address mismatch: expected ${expectedAddr.toHexString}, got ${result.address.toString(16)}"
      )
      assert(!result.alignException, "Should not have alignment exception")
      assert(result.robId == 15, "ROB ID mismatch")

      println("✓ Basic address calculation with registers PASSED")
    }
  }

  test("AGU Plugin with BypassService - Bypass Data Usage") {
    simConfig.compile(new AguTestFrameworkWithBypass()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[AguTestParams]()
      val outputSnapshots = mutable.ArrayBuffer[AguResultSnapshot]()

      // 输入驱动器
      dut.clockDomain.onSamplings {
        println(s"$simTime clk")
        if (dut.io.testInput.ready.toBoolean && inputQueue.nonEmpty) {
          val params = inputQueue.dequeue()
          dut.io.testInput.valid #= true
          driveAguInput(dut.io.testInput.payload, params)
          ParallaxLogger.debug(s"Sent agu input: ${params}")
        } else {
          dut.io.testInput.valid #= false
          ParallaxLogger.debug(s"Sent agu input valid pull down")
        }
      }

      // 输出监控器
      dut.clockDomain.onSamplings {
        if (dut.io.testOutput.valid.toBoolean && dut.io.testOutput.ready.toBoolean) {
          val payload = dut.io.testOutput.payload
          outputSnapshots += AguResultSnapshot(
            payload.address.toBigInt,
            payload.alignException.toBoolean,
            payload.robId.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt
          )
          ParallaxLogger.debug(s"Received agu output: ${outputSnapshots}")

        }
      }

      // 初始化
      dut.io.testOutput.ready #= true
      dut.io.flush #= false
      dut.io.prfRead.valid #= false
      dut.io.bypassInject.valid #= false

      dut.clockDomain.waitSampling(5)

      val baseReg = 12
      val regValue = 0x1000 // 寄存器中的旧值
      val bypassValue = 0x3000 // 旁路的新值（应该被使用）
      val immediate = 500
      val expectedAddr = bypassValue + immediate // 应该使用旁路值

      preloadRegister(dut, baseReg, regValue, dut.clockDomain)

      fork {
        injectBypassData(dut, baseReg, bypassValue, 25, dut.clockDomain)
      }

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          immediate = immediate,
          accessSize = MemAccessSize.H,
          usePc = false,
          pcVal = 0,
          robId = 30,
          isLoad = false,
          isStore = true,
          physDst = 35
        )
      )

      // 等待结果
      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received")
      val result = outputSnapshots.head
      dut.clockDomain.waitSampling()

      assert(
        result.address == expectedAddr,
        s"Bypass data not used: expected ${expectedAddr.toHexString}, got ${result.address.toString(16)}"
      )
      assert(!result.alignException, "Should not have alignment exception")
      assert(result.robId == 30, "ROB ID mismatch")
      assert(result.isStore, "IsStore flag mismatch")

      println("✓ Bypass data usage test PASSED")
    }
  }

  test("AGU Plugin with BypassService - Multiple Bypass Sources") {
    simConfig.compile(new AguTestFrameworkWithBypass()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[AguTestParams]()
      val outputSnapshots = mutable.ArrayBuffer[AguResultSnapshot]()

      // 输入驱动器
      dut.clockDomain.onSamplings {
        if (dut.io.testInput.ready.toBoolean && inputQueue.nonEmpty) {
          val params = inputQueue.dequeue()
          dut.io.testInput.valid #= true
          driveAguInput(dut.io.testInput.payload, params)
        } else {
          dut.io.testInput.valid #= false
        }
      }

      // 输出监控器
      dut.clockDomain.onSamplings {
        if (dut.io.testOutput.valid.toBoolean && dut.io.testOutput.ready.toBoolean) {
          val payload = dut.io.testOutput.payload
          outputSnapshots += AguResultSnapshot(
            payload.address.toBigInt,
            payload.alignException.toBoolean,
            payload.robId.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt
          )
        }
      }

      // 初始化
      dut.io.testOutput.ready #= true
      dut.io.flush #= false
      dut.io.prfRead.valid #= false
      dut.io.bypassInject.valid #= false

      dut.clockDomain.waitSampling(5)

      // 测试连续的旁路数据使用不同寄存器
      val testCases = Seq(
        (5, 0x4000, 100, 40),
        (6, 0x5000, 200, 41),
        (7, 0x6000, 300, 42)
      )

      for ((reg, bypassVal, imm, robId) <- testCases) {
        outputSnapshots.clear()

        // 注入旁路数据
        fork {
          injectBypassData(dut, reg, bypassVal, robId, dut.clockDomain)
        }

        inputQueue.enqueue(
          AguTestParams(
            basePhysReg = reg,
            immediate = imm,
            accessSize = MemAccessSize.H,
            usePc = false,
            pcVal = 0,
            robId = robId,
            isLoad = true,
            isStore = false,
            physDst = reg + 10
          )
        )

        dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

        assert(outputSnapshots.nonEmpty, s"No output for reg $reg")
        val result = outputSnapshots.head
        val expectedAddr = bypassVal + imm

        assert(
          result.address == expectedAddr,
          s"Reg $reg: expected ${expectedAddr.toHexString}, got ${result.address.toString(16)}"
        )
        assert(result.robId == robId, s"Reg $reg: ROB ID mismatch")

        dut.clockDomain.waitSampling(3)
      }

      println("✓ Multiple bypass sources test PASSED")
    }
  }

  thatsAll()
}
