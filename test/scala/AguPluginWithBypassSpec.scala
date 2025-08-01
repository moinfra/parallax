// testOnly test.scala.AguPluginWithBypassSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import parallax.components.lsu._
import parallax.execute.{BypassService, BypassPlugin}
import parallax.utilities._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import spinal.lib.sim.StreamMonitor
import spinal.lib.sim.StreamDriver
import spinal.lib.sim.StreamReadyRandomizer

class TestSetupPlugin(
    aguIoSetup: AguPort => Unit,
    prfIoSetup: (PrfReadPort, PrfWritePort) => Unit,
    bypassIoSetup: (Flow[BypassMessage]) => Unit
) extends Plugin {
  lazy val aguPlugin = getService[AguPlugin]
  lazy val prfPlugin = getService[PhysicalRegFilePlugin]
  lazy val bypassPlugin = getService[BypassPlugin[BypassMessage]]
  val setup = create early new Area {
    aguPlugin.retain()
    prfPlugin.retain()
    val aguPort = aguPlugin.newAguPort().simPublic()
    aguIoSetup(aguPort)

    val prfWritePort = prfPlugin.newPrfWritePort()
    val prfReadPort = prfPlugin.newPrfReadPort()
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
class AguTestFrameworkWithBypass() extends Component {


  val pCfg = PipelineConfig()
    val lsuConfig = LsuConfig(
    lqDepth = 16,
    sqDepth = 16,
    robPtrWidth = pCfg.robPtrWidth,
    pcWidth = pCfg.pcWidth,
    dataWidth = pCfg.dataWidth,
    physGprIdxWidth = pCfg.physGprIdxWidth,
    exceptionCodeWidth = pCfg.exceptionCodeWidth,
    commitWidth = pCfg.commitWidth,
    dcacheRefillCount = 2
  )
  val io = new Bundle {
    val testInput = slave(Stream(AguInput(lsuConfig)))
    val testOutput = master(Stream(AguOutput(lsuConfig)))
    val flush = in Bool ()

    val prfWrite = slave(PrfWritePort(6 bits, 32 bits))
    val prfRead = slave(PrfReadPort(6 bits, 32 bits))

    val bypassInject = slave(Flow(BypassMessage(pCfg)))
  }

  lazy val prfPlugin = (new PhysicalRegFilePlugin(
    numPhysRegs = 64,
    dataWidth = 32 bits
  ))

  lazy val bypassPlugin = (new BypassPlugin[BypassMessage](
    payloadType = HardType(BypassMessage(pCfg))
  ))

  lazy val aguPlugin = (new AguPlugin(
    lsuConfig,
    supportPcRel = true,
    mmioRanges = Seq(
      MmioRange(U(0x10000000L, 32 bits), U(0x1000FFFFL, 32 bits)), // MMIO range 1: 256MB-256MB+64KB
      MmioRange(U(0x20000000L, 32 bits), U(0x2000FFFFL, 32 bits))  // MMIO range 2: 512MB-512MB+64KB
    )
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
      dataReg: Int,
      immediate: Int,
      accessSize: MemAccessSize.E,
      usePc: Boolean,
      pcVal: Long,
      robPtr: Int,
      isLoad: Boolean,
      isStore: Boolean,
      physDst: Int,
      isIO: Boolean = false
  )

  // 输出快照
  case class AguResultSnapshot(
      address: BigInt,
      alignException: Boolean,
      robPtr: BigInt,
      isLoad: Boolean,
      isStore: Boolean,
      physDst: BigInt,
      storeData: BigInt,
      isIO: Boolean
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
      robPtr: Int,
      clockDomain: ClockDomain
  ): Unit = {
    dut.io.bypassInject.valid #= true
    dut.io.bypassInject.payload.physRegIdx #= physRegIdx
    dut.io.bypassInject.payload.physRegData #= data
    dut.io.bypassInject.payload.robPtr #= robPtr
    clockDomain.waitSampling(10)
    dut.io.bypassInject.valid #= false
    clockDomain.waitSampling()
  }

  // 驱动AGU输入
  def driveAguInput(payload: AguInput, params: AguTestParams): Unit = {
    payload.basePhysReg #= params.basePhysReg
    payload.immediate #= params.immediate
    payload.dataReg #= params.dataReg
    payload.accessSize #= params.accessSize
    payload.usePc #= params.usePc
    payload.pc #= params.pcVal
    payload.robPtr #= params.robPtr
    payload.isLoad #= params.isLoad
    payload.isStore #= params.isStore
    payload.physDst #= params.physDst
    payload.isFlush #= false
    payload.qPtr #= 0
    payload.isIO #= params.isIO
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
            payload.robPtr.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt,
            payload.storeData.toBigInt,
            payload.isIO.toBoolean
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
      val baseValue = BigInt("2000", 16)
      val immediate = 300
      val expectedAddr = baseValue + immediate

      preloadRegister(dut, baseReg, baseValue, dut.clockDomain)

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate,
          accessSize = MemAccessSize.H,
          usePc = false,
          pcVal = 0,
          robPtr = 15,
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
        s"Address mismatch: expected ${expectedAddr.toString(16)}, got ${result.address.toString(16)}"
      )
      assert(!result.alignException, "Should not have alignment exception")
      assert(result.robPtr == 15, "ROB ID mismatch")

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
            payload.robPtr.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt,
            payload.storeData.toBigInt,
            payload.isIO.toBoolean
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
      val regValue = BigInt("1000", 16) // 寄存器中的旧值
      val bypassValue = BigInt("3000", 16) // 旁路的新值（应该被使用）
      val immediate = 500
      val expectedAddr = bypassValue + immediate // 应该使用旁路值

      preloadRegister(dut, baseReg, regValue, dut.clockDomain)

      fork {
        injectBypassData(dut, baseReg, bypassValue, 25, dut.clockDomain)
      }

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate,
          accessSize = MemAccessSize.H,
          usePc = false,
          pcVal = 0,
          robPtr = 30,
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
        s"Bypass data not used: expected ${expectedAddr.toString(16)}, got ${result.address.toString(16)}"
      )
      assert(!result.alignException, "Should not have alignment exception")
      assert(result.robPtr == 30, "ROB ID mismatch")
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
            payload.robPtr.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt,
            payload.storeData.toBigInt,
            payload.isIO.toBoolean
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
        (5, BigInt("4000", 16), 100, 40),
        (6, BigInt("5000", 16), 200, 41),
        (7, BigInt("6000", 16), 300, 42)
      )

      for ((reg, bypassVal, imm, robPtr) <- testCases) {
        outputSnapshots.clear()

        // 注入旁路数据
        fork {
          injectBypassData(dut, reg, bypassVal, robPtr, dut.clockDomain)
        }

        inputQueue.enqueue(
          AguTestParams(
            basePhysReg = reg,
            dataReg = 0,
            immediate = imm,
            accessSize = MemAccessSize.H,
            usePc = false,
            pcVal = 0,
            robPtr = robPtr,
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
          s"Reg $reg: expected ${expectedAddr.toString(16)}, got ${result.address.toString(16)}"
        )
        assert(result.robPtr == robPtr, s"Reg $reg: ROB ID mismatch")

        dut.clockDomain.waitSampling(3)
      }

      println("✓ Multiple bypass sources test PASSED")
    }
  }

  test("AGU Plugin - Store Data Path with Register and Bypass (using SimStream lib)") {
    simConfig.compile(new AguTestFrameworkWithBypass()).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // 1. 设置输入驱动器 (Master)
      // StreamDriver.queue返回一个控制器和一个可以向其添加任务的队列。
      val (inputDriver, inputCmdQueue) = StreamDriver.queue(dut.io.testInput, dut.clockDomain)

      // 2. 设置输出监控器 (Slave)
      // 创建一个队列来收集来自DUT的输出结果
      val outputQueue = mutable.Queue[AguResultSnapshot]()
      StreamMonitor(dut.io.testOutput, dut.clockDomain) { payload =>
        // 每当一个有效的输出被接收时，这个回调函数就会被调用
        outputQueue.enqueue(
          AguResultSnapshot(
            payload.address.toBigInt,
            payload.alignException.toBoolean,
            payload.robPtr.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt,
            payload.storeData.toBigInt,
            payload.isIO.toBoolean
          )
        )
        // 为了调试，打印收到的数据
        println(s"[Monitor] @${simTime()} Received output for robPtr=${payload.robPtr.toBigInt}")
      }

      // 3. 随机化下游的 'ready' 信号，以模拟真实的背压
      StreamReadyRandomizer(dut.io.testOutput, dut.clockDomain).setFactor(0.2f)

      // 初始化
      dut.io.flush #= false
      dut.io.bypassInject.valid #= false
      dut.clockDomain.waitSampling(5)

      // --- Part 1: Test Store Data from Register ---
      println("--- Testing Store Data from Register ---")
      val baseReg1 = 10
      val dataReg1 = 11
      val baseValue1 = 0x1000
      val storeDataValue1 = 0xaaaaaaaaL
      val testParams1 = AguTestParams(
        basePhysReg = baseReg1,
        dataReg = dataReg1,
        immediate = 100,
        accessSize = MemAccessSize.W,
        usePc = false,
        pcVal = 0,
        robPtr = 1,
        isLoad = false,
        isStore = true,
        physDst = 0
      )

      // 预加载寄存器
      preloadRegister(dut, baseReg1, baseValue1, dut.clockDomain)
      preloadRegister(dut, dataReg1, storeDataValue1, dut.clockDomain)

      // 将测试命令加入输入队列
      inputCmdQueue.enqueue { payload =>
        driveAguInput(payload, testParams1)
      }

      // 等待，直到我们在输出队列中收到一个结果
      val timedout = dut.clockDomain.waitSamplingWhere(timeout=200)(outputQueue.nonEmpty)
      if (timedout) {
        fail(s"No output received for testParams1")
      }

      // 从队列中取出结果并断言
      val result1 = outputQueue.dequeue()
      assert(
        result1.address == baseValue1 + 100,
        s"Part 1: Address mismatch. Expected ${baseValue1 + 100}, got ${result1.address}"
      )
      // >> 关键断言 <<
      // 由于我们在 AguOutput 中没有 storeData 字段了（按照最新的设计），
      // 这里的检查应该在下一级，即SB的输入端。
      // 但为了单元测试AGU，我们需要在 AguOutput 中临时加上 storeData
      // 假设 AguOutput 中有 storeData:
      // assert(result1.storeData == storeDataValue1, s"Part 1: Store data mismatch. Expected $storeDataValue1, got ${result1.storeData}")
      println("✓ Store data from register PASSED")

      dut.clockDomain.waitSampling(5)

      // --- Part 2: Test Store Data from Bypass ---
      println("--- Testing Store Data from Bypass ---")
      val baseReg2 = 20
      val dataReg2 = 21
      val baseValue2 = 0x2000
      val storeDataRegValue2 = BigInt("BBBBBBBB", 16)
      val storeDataBypassValue2 = BigInt("CCCCCCCC", 16)
      val testParams2 = AguTestParams(
        basePhysReg = baseReg2,
        dataReg = dataReg2,
        immediate = 200,
        accessSize = MemAccessSize.W,
        usePc = false,
        pcVal = 0,
        robPtr = 2,
        isLoad = false,
        isStore = true,
        physDst = 0
      )

      // 预加载寄存器（旧值）
      preloadRegister(dut, baseReg2, baseValue2, dut.clockDomain)
      preloadRegister(dut, dataReg2, storeDataRegValue2, dut.clockDomain)

      // Fork一个线程来注入旁路数据（新值）
      fork {
        injectBypassData(dut, dataReg2, storeDataBypassValue2, 2, dut.clockDomain)
      }

      // 将测试命令加入输入队列
      inputCmdQueue.enqueue { payload =>
        driveAguInput(payload, testParams2)
      }

      // 等待结果
      val timedout2 = dut.clockDomain.waitSamplingWhere(timeout=10)(outputQueue.nonEmpty)
      if (timedout2) {
        fail(s"No output received for testParams2")
      }

      // 检查结果
      val result2 = outputQueue.dequeue()
      assert(
        result2.address == baseValue2 + 200,
        s"Part 2: Address mismatch. Expected ${baseValue2 + 200}, got ${result2.address}"
      )
      // 假设 AguOutput 中有 storeData:
      assert(result2.storeData == storeDataBypassValue2, s"Part 2: Store data from bypass is wrong. Expected $storeDataBypassValue2, got ${result2.storeData}")
      println("✓ Store data from bypass PASSED")

      dut.clockDomain.waitSampling(10)
    }
  }

  test("AGU Plugin - MMIO Address Detection") {
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
            payload.robPtr.toBigInt,
            payload.isLoad.toBoolean,
            payload.isStore.toBoolean,
            payload.physDst.toBigInt,
            payload.storeData.toBigInt,
            payload.isIO.toBoolean
          )
        }
      }

      // 初始化
      dut.io.testOutput.ready #= true
      dut.io.flush #= false
      dut.io.prfRead.valid #= false
      dut.io.bypassInject.valid #= false

      dut.clockDomain.waitSampling(5)

      // Test case 1: 地址在MMIO范围1内 (0x10000000-0x1000FFFF)
      val baseReg = 10
      val baseValue = BigInt("10000000", 16)  // MMIO range 1 start
      val immediate = 0x1000       // Still within range 1
      val expectedAddr = baseValue + immediate

      preloadRegister(dut, baseReg, baseValue, dut.clockDomain)

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate,
          accessSize = MemAccessSize.W,
          usePc = false,
          pcVal = 0,
          robPtr = 10,
          isLoad = true,
          isStore = false,
          physDst = 20,
          isIO = false // 输入的isIO=false，但应该被AGU自动检测为true
        )
      )

      // 等待结果
      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received for MMIO test case 1")
      val result1 = outputSnapshots.head
      outputSnapshots.clear()

      assert(
        result1.address == expectedAddr,
        s"MMIO Test 1: Address mismatch: expected 0x${expectedAddr.toString(16)}, got 0x${result1.address.toString(16)}"
      )
      assert(result1.isIO, "MMIO Test 1: isIO should be true for MMIO address range")
      println("✓ MMIO detection test 1 (range 1) PASSED")

      // Test case 2: 地址在MMIO范围2内 (0x20000000-0x2000FFFF)
      val baseValue2 = BigInt("20000500", 16)  // MMIO range 2
      val immediate2 = 0x100
      val expectedAddr2 = baseValue2 + immediate2

      preloadRegister(dut, baseReg, baseValue2, dut.clockDomain)

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate2,
          accessSize = MemAccessSize.W,
          usePc = false,
          pcVal = 0,
          robPtr = 11,
          isLoad = false,
          isStore = true,
          physDst = 21,
          isIO = false // 输入的isIO=false，但应该被AGU自动检测为true
        )
      )

      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received for MMIO test case 2")
      val result2 = outputSnapshots.head
      outputSnapshots.clear()

      assert(
        result2.address == expectedAddr2,
        s"MMIO Test 2: Address mismatch: expected 0x${expectedAddr2.toString(16)}, got 0x${result2.address.toString(16)}"
      )
      assert(result2.isIO, "MMIO Test 2: isIO should be true for MMIO address range")
      println("✓ MMIO detection test 2 (range 2) PASSED")

      // Test case 3: 地址不在MMIO范围内
      val baseValue3 = BigInt("30000000", 16)  // 不在任何MMIO范围内
      val immediate3 = 0x1000
      val expectedAddr3 = baseValue3 + immediate3

      preloadRegister(dut, baseReg, baseValue3, dut.clockDomain)

      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate3,
          accessSize = MemAccessSize.W,
          usePc = false,
          pcVal = 0,
          robPtr = 12,
          isLoad = true,
          isStore = false,
          physDst = 22,
          isIO = false
        )
      )

      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received for non-MMIO test case")
      val result3 = outputSnapshots.head
      outputSnapshots.clear()

      assert(
        result3.address == expectedAddr3,
        s"Non-MMIO Test: Address mismatch: expected 0x${expectedAddr3.toString(16)}, got 0x${result3.address.toString(16)}"
      )
      assert(!result3.isIO, "Non-MMIO Test: isIO should be false for non-MMIO address")
      println("✓ Non-MMIO detection test PASSED")

      // Test case 4: 输入isIO=true时应该保持true，即使地址不在MMIO范围内
      inputQueue.enqueue(
        AguTestParams(
          basePhysReg = baseReg,
          dataReg = 0,
          immediate = immediate3,
          accessSize = MemAccessSize.W,
          usePc = false,
          pcVal = 0,
          robPtr = 13,
          isLoad = true,
          isStore = false,
          physDst = 23,
          isIO = true // 显式设置为true
        )
      )

      dut.clockDomain.waitSamplingWhere(100)(outputSnapshots.nonEmpty)

      assert(outputSnapshots.nonEmpty, "No AGU output received for explicit isIO test")
      val result4 = outputSnapshots.head

      assert(result4.isIO, "Explicit isIO Test: isIO should remain true when explicitly set")
      println("✓ Explicit isIO preservation test PASSED")

      println("✓ All MMIO address detection tests PASSED")
    }
  }
  thatsAll()
}
