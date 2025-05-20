package parallax.test.scala.bus

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.sim._
import spinal.tester.SpinalSimFunSuite

import parallax.bus._
import parallax.components.memory._

import scala.collection.mutable
import scala.util.Random

// --- 测试平台 (DUT Wrapper) ---
class Axi4ToSplitGmbBridgeTestBench(
    val axiConfig: Axi4Config,
    val gmbConfig: GenericMemoryBusConfig,
    val simMemInternalConfig: SimulatedMemoryConfig // 用于 SimulatedSplitGeneralMemory
) extends Component {
  val io = new Bundle {
    // AXI Master Interface (driven by testbench)
    val axiMaster = slave(Axi4(axiConfig)) // 我们将驱动这个接口的master信号，连接到DUT的slave
  }

  val bridge = new Axi4ToSplitGmbBridge(axiConfig, gmbConfig)
  bridge.io.axiIn <> io.axiMaster

  val gmbSlaveMem = new SimulatedSplitGeneralMemory(
    memConfig = simMemInternalConfig,
    busConfig = gmbConfig,
    enableLog = true,
  )
  gmbSlaveMem.io.bus <> bridge.io.gmbOut // DUT的master连接到模拟的GMB slave

  // 可以暴露SimulatedSplitGeneralMemory的直接写端口用于初始化
  val tbMemWriteEnable = in Bool () default (False)
  val tbMemWriteAddress = in UInt (gmbConfig.addressWidth) default (U(0, gmbConfig.addressWidth))
  val tbMemWriteData =
    in Bits (simMemInternalConfig.internalDataWidth) default (B(0, simMemInternalConfig.internalDataWidth))

  gmbSlaveMem.io.writeEnable := tbMemWriteEnable
  gmbSlaveMem.io.writeAddress := tbMemWriteAddress
  gmbSlaveMem.io.writeData := tbMemWriteData
}

// --- 测试用例 ---
class Axi4ToSplitGmbBridgeSpec extends SpinalSimFunSuite {
  onlyVerilator() // 或者你偏好的后端

  // 手动定义 AXI 响应常量
  val AXI_RESP_OKAY = 0
  val AXI_RESP_SLVERR = 2

  // --- Helper: AXI Master Driver ---
  object AxiMasterDriver {
    def driveAr(axi: Axi4, addr: BigInt, id: Int = 0, len: Int = 0, size: Int = -1)(implicit cd: ClockDomain): Unit = {
      axi.ar.valid #= true
      axi.ar.payload.addr #= addr
      if (axi.config.useId) axi.ar.payload.id #= id
      if (axi.config.useLen) axi.ar.payload.len #= len
      val effectiveSize = if (size == -1) log2Up(axi.config.dataWidth / 8) else size
      if (axi.config.useSize) axi.ar.payload.size #= effectiveSize
      // 其他 AR 信号 (prot, cache, lock, qos, region) 可以根据需要设置，或依赖默认值
      if (cd.waitSamplingWhere(100)(axi.ar.ready.toBoolean)) {
        simFailure(s"Timeout waiting for AR.ready for Addr=0x${addr.toString(16)}, ID=$id")
      }
      axi.ar.valid #= false
    }

    def driveAw(axi: Axi4, addr: BigInt, id: Int = 0, len: Int = 0, size: Int = -1)(implicit cd: ClockDomain): Unit = {
      axi.aw.valid #= true
      axi.aw.payload.addr #= addr
      if (axi.config.useId) axi.aw.payload.id #= id
      if (axi.config.useLen) axi.aw.payload.len #= len
      val effectiveSize = if (size == -1) log2Up(axi.config.dataWidth / 8) else size
      if (axi.config.useSize) axi.aw.payload.size #= effectiveSize
      if (cd.waitSamplingWhere(100)(axi.aw.ready.toBoolean)) {
        simFailure(s"Timeout waiting for AW.ready for Addr=0x${addr.toString(16)}, ID=$id")
      }
      axi.aw.valid #= false
    }

    def driveW(axi: Axi4, data: BigInt, strb: BigInt = -1, last: Boolean = true, id: Int = 0)(implicit
        cd: ClockDomain
    ): Unit = {
      axi.w.valid #= true
      axi.w.payload.data #= data
      val effectiveStrb = if (strb == -1) (BigInt(1) << (axi.config.dataWidth / 8)) - 1 else strb
      if (axi.config.useStrb) axi.w.payload.strb #= effectiveStrb
      if (axi.config.useLast) axi.w.payload.last #= last
      // AXI4 W通道没有ID，但如果需要与AW关联，可以在driver层面跟踪
      if (cd.waitSamplingWhere(100)(axi.w.ready.toBoolean)) {
        simFailure(s"Timeout waiting for W.ready for Data=0x${data.toString(16)}")
      }
      axi.w.valid #= false
    }

    def initAxiMasterSignals(axi: Axi4)(implicit cd: ClockDomain): Unit = {
      axi.ar.valid #= false
      axi.aw.valid #= false
      axi.w.valid #= false
      axi.r.ready #= false
      axi.b.ready #= false
      // 初始化 payload 为 don't care 或 0
      axi.ar.payload.assignDontCare()
      axi.aw.payload.assignDontCare()
      axi.w.payload.assignDontCare()
      cd.waitSampling()
    }

    def expectR(
        axi: Axi4,
        expectedData: BigInt,
        expectedId: Int,
        expectedResp: Int = AXI_RESP_OKAY,
        backpressureProb: Double = 0.0,
        maxStallCycles: Int = 5
    )(implicit cd: ClockDomain): Unit = {
      var stallCycles = 0
      while (
        cd.waitSamplingWhere(100)(
          !axi.r.valid.toBoolean || (Random.nextDouble() < backpressureProb && stallCycles < maxStallCycles)
        )
      ) {
        axi.r.ready #= (Random.nextDouble() >= backpressureProb || stallCycles >= maxStallCycles)
        if (axi.r.valid.toBoolean && !axi.r.ready.toBoolean) {
          println(s"[Driver] AXI R: Stalling ready (valid=true, prob=$backpressureProb, stallCnt=$stallCycles)")
          stallCycles += 1
        } else if (!axi.r.valid.toBoolean) {
          axi.r.ready #= false // 如果 R.valid 未置位，不应该拉高 R.ready
        }
        if (stallCycles >= 100) { // 防止死锁
          assert(false, s"Timeout in expectR waiting for R.valid or overcoming artificial stall. ID=$expectedId")
        }
      }
// Valid is high, and we decided to be ready
      axi.r.ready #= true
      cd.waitSampling() // Sample the data

      val actualData = axi.r.payload.data.toBigInt
      val actualId = if (axi.config.useId) axi.r.payload.id.toInt else -1
      val actualResp = axi.r.payload.resp.toInt

      println(s"[Driver] AXI R Received: ID=${actualId}, Data=0x${actualData.toString(16)}, RESP=${actualResp}")
      assert(
        actualResp == expectedResp,
        s"AXI R.RESP mismatch: Expected $expectedResp, Got $actualResp (ID=$expectedId)"
      )
      if (axi.config.useId) assert(actualId == expectedId, s"AXI R.ID mismatch: Expected $expectedId, Got $actualId")
      if (expectedResp == AXI_RESP_OKAY) {
        assert(
          actualData == expectedData,
          s"AXI R.DATA mismatch: Expected 0x${expectedData.toString(16)}, Got 0x${actualData.toString(16)} (ID=$expectedId)"
        )
      }
      assert(axi.r.payload.last.toBoolean, "AXI R.LAST should be true for single beat")
      axi.r.ready #= false // Deassert ready for next transaction
    }

    def expectB(
        axi: Axi4,
        expectedId: Int,
        expectedResp: Int = AXI_RESP_OKAY,
        backpressureProb: Double = 0.0,
        maxStallCycles: Int = 5
    )(implicit cd: ClockDomain): Unit = {
      var stallCycles = 0
      while (
        cd.waitSamplingWhere(100)(
          !axi.b.valid.toBoolean || (Random.nextDouble() < backpressureProb && stallCycles < maxStallCycles)
        )
      ) {
        axi.b.ready #= (Random.nextDouble() >= backpressureProb || stallCycles >= maxStallCycles)
        if (axi.b.valid.toBoolean && !axi.b.ready.toBoolean) {
          println(s"[Driver] AXI B: Stalling ready (valid=true, prob=$backpressureProb, stallCnt=$stallCycles)")
          stallCycles += 1
        } else if (!axi.b.valid.toBoolean) {
          axi.b.ready #= false
        }
        if (stallCycles >= 100) {
          assert(false, s"Timeout in expectB waiting for B.valid or overcoming artificial stall. ID=$expectedId")
        }
      }
      axi.b.ready #= true
      cd.waitSampling()

      val actualId = if (axi.config.useId) axi.b.payload.id.toInt else -1
      val actualResp = axi.b.payload.resp.toInt

      println(s"[Driver] AXI B Received: ID=${actualId}, RESP=${actualResp}")
      assert(
        actualResp == expectedResp,
        s"AXI B.RESP mismatch: Expected $expectedResp, Got $actualResp (ID=$expectedId)"
      )
      if (axi.config.useId) assert(actualId == expectedId, s"AXI B.ID mismatch: Expected $expectedId, Got $actualId")
      axi.b.ready #= false
    }
  }

  // --- Helper: 初始化 GMB Slave Memory ---
  def initGmbMemory(
      dut: Axi4ToSplitGmbBridgeTestBench,
      address: BigInt,
      value: BigInt,
      clockDomain: ClockDomain
  ): Unit = {
    // 假设 value 的宽度与 GMB 总线宽度一致，且 GMB 总线宽度是内部存储宽度的整数倍
    val numInternalChunks = dut.gmbConfig.dataWidth.value / dut.simMemInternalConfig.internalDataWidth.value
    val internalDataWidth = dut.simMemInternalConfig.internalDataWidth

    dut.tbMemWriteEnable #= false
    clockDomain.waitSampling()

    for (i <- 0 until numInternalChunks) {
      val slice = (value >> (i * internalDataWidth.value)) & ((BigInt(1) << internalDataWidth.value) - 1)
      val internalChunkByteAddress = address + i * (internalDataWidth.value / 8)

      dut.tbMemWriteEnable #= true
      dut.tbMemWriteAddress #= internalChunkByteAddress
      dut.tbMemWriteData #= slice
      clockDomain.waitSampling()
    }
    dut.tbMemWriteEnable #= false
    clockDomain.waitSampling()
  }

  // Helper function to enqueue elements into a mutable Queue
  def enqueue[T](queue: mutable.Queue[T], elements: T*): Unit = {
    elements.foreach(queue.enqueue(_))
  }

  // --- 测试配置 ---
  val baseAddrWidth = 16
  val baseDataWidth = 32
  val baseIdWidth = 8

  // 配置1: 使用ID
  val axiConfigWithId = Axi4Config(
    addressWidth = baseAddrWidth,
    dataWidth = baseDataWidth,
    idWidth = baseIdWidth,
    useId = true,
    useRegion = false,
    useBurst = false,
    useLock = false,
    useCache = false,
    useSize = true, // useBurst = false
    useQos = false,
    useLen = true,
    useLast = true,
    useResp = true,
    useProt = false,
    useStrb = true
  )
  val gmbConfigWithId = GenericMemoryBusConfig(
    addressWidth = baseAddrWidth bits,
    dataWidth = baseDataWidth bits,
    useId = true,
    idWidth = baseIdWidth bits
  )

  // 配置2: 不使用ID
  val axiConfigNoId = Axi4Config(
    addressWidth = baseAddrWidth,
    dataWidth = baseDataWidth,
    idWidth = 1,
    useId = false, // idWidth 即使 useId=false 也需要 > 0
    useRegion = false,
    useBurst = false,
    useLock = false,
    useCache = false,
    useSize = true, // useBurst = false
    useQos = false,
    useLen = true,
    useLast = true,
    useResp = true,
    useProt = false,
    useStrb = true
  )
  val gmbConfigNoId = GenericMemoryBusConfig(
    addressWidth = baseAddrWidth bits,
    dataWidth = baseDataWidth bits,
    useId = false,
    idWidth = 1 bits
  )

  val simMemInternalCfg = SimulatedMemoryConfig(
    internalDataWidth = baseDataWidth bits, // 为了简单，让内部存储宽度等于总线宽度
    memSize = 4 KiB,
    initialLatency = 0,
    burstLatency = 0,
  )

  // --- Helper: 初始化 GMB Slave Memory 并验证为零 ---
  def initGmbMemoryAndVerifyZero(dut: Axi4ToSplitGmbBridgeTestBench, address: BigInt, clockDomain: ClockDomain, axiConf: Axi4Config): Unit = {
    implicit val cd = clockDomain

    // 1. 初始化内存为零
    println(s"[Helper] Initializing memory at 0x${address.toString(16)} to zero.")
    initGmbMemory(dut, address, BigInt(0), cd)
    cd.waitSampling(5) // 等待初始化完成

    // 2. 通过 AXI 读取并验证为零
    println(s"[Helper] Verifying memory at 0x${address.toString(16)} is zero via AXI read.")
    val verifyReadId = if (axiConf.useId) (Random.nextInt(1 << axiConf.idWidth)) else 0 // 使用随机ID进行验证读取
    AxiMasterDriver.driveAr(dut.io.axiMaster, address, verifyReadId)

    if (cd.waitSamplingWhere(200)(dut.io.axiMaster.r.valid.toBoolean)) {
      simFailure(s"[Helper] Timeout waiting for AXI R valid during zero verification read at 0x${address.toString(16)}, ID=$verifyReadId")
    }

    val readBackData = dut.io.axiMaster.r.payload.data.toBigInt
    val actualResp = dut.io.axiMaster.r.payload.resp.toInt
    val actualId = if(axiConf.useId) dut.io.axiMaster.r.payload.id.toInt else -1

    println(s"[Helper] AXI Read Response (R) for zero verification: ID=$actualId, Data=0x${readBackData.toString(16)}, RESP=$actualResp")

    assert(actualResp == AXI_RESP_OKAY, s"[Helper] Zero verification AXI R.RESP should be OKAY at 0x${address.toString(16)}")
    if (axiConf.useId) {
      assert(actualId == verifyReadId, s"[Helper] Zero verification AXI R.ID mismatch at 0x${address.toString(16)}: Expected $verifyReadId, Got $actualId")
    }
    assert(readBackData == BigInt(0), s"[Helper] Zero verification failed at 0x${address.toString(16)}: Expected 0x0, Got 0x${readBackData.toString(16)}")

    dut.io.axiMaster.r.ready #= false // Deassert ready
    cd.waitSampling()
    println(s"[Helper] Zero verification successful at 0x${address.toString(16)}.")
  }

  // --- 测试主体 ---
  def runSingleTransactionTest(
      axiConf: Axi4Config,
      gmbConf: GenericMemoryBusConfig,
      isWrite: Boolean,
      address: BigInt,
      writeData: BigInt = 0L, // 仅用于写操作
      expectedReadData: BigInt = 0L, // 仅用于读操作
      byteEnable: BigInt = -1L, // -1 表示全使能
      id: Int = 0,
      gmbSlaveShouldError: Boolean = false,
      testNameSuffix: String
  ): Unit = {
    val testName = s"Axi4ToSplitGmbBridge_Single_${if (isWrite) "Write" else "Read"}_${if (gmbSlaveShouldError) "Error"
      else "Success"}_ID[${axiConf.useId}]_" + testNameSuffix
    test(testName) {
      SimConfig.withWave.compile(new Axi4ToSplitGmbBridgeTestBench(axiConf, gmbConf, simMemInternalCfg)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        AxiMasterDriver.initAxiMasterSignals(dut.io.axiMaster)

        // 模拟 GMB Slave 的行为 (通过 SimulatedSplitGeneralMemory 的配置或特定操作)
        // SimulatedSplitGeneralMemory 内部会根据地址范围判断是否OOB并产生错误
        // 这里我们主要通过地址来触发错误
        val effectiveAddress = if (gmbSlaveShouldError) simMemInternalCfg.memSize + 0x100L else address

        // 如果是写操作且不期望错误，先将目标地址初始化为零并验证
        if (isWrite && !gmbSlaveShouldError) {
            initGmbMemoryAndVerifyZero(dut, address.toLong, cd, axiConf)
        } else if (!isWrite && !gmbSlaveShouldError) {
          // 如果是读操作且不期望错误，则初始化内存为期望的读数据
          initGmbMemory(dut, address.toLong, expectedReadData, cd)
        }

        dut.io.axiMaster.r.ready #= true
        dut.io.axiMaster.b.ready #= true

        if (isWrite) {
          // 写操作
          println(s"$testName: Driving AXI Write: Addr=0x${effectiveAddress.toString(16)}, Data=0x${writeData
              .toString(16)}, ID=$id, STRB=0x${byteEnable.toString(16)}")
          AxiMasterDriver.driveAw(dut.io.axiMaster, effectiveAddress, id)
          AxiMasterDriver.driveW(
            dut.io.axiMaster,
            writeData,
            strb = byteEnable,
            id = id
          ) // W id is for driver tracking, not protocol

          // 等待 B 通道响应
          if (cd.waitSamplingWhere(200)(dut.io.axiMaster.b.valid.toBoolean)) {
            simFailure(s"$testName: Timeout waiting for AXI B valid for ID=$id")
          }
          println(s"$testName: AXI Write Response (B): ID=${if (axiConf.useId) dut.io.axiMaster.b.payload.id.toInt
            else -1}, RESP=${dut.io.axiMaster.b.payload.resp.toInt}")
          if (gmbSlaveShouldError) {
            assert(
              dut.io.axiMaster.b.payload.resp.toInt == AXI_RESP_SLVERR,
              "AXI B.RESP should be SLVERR for GMB error"
            )
          } else {
            assert(dut.io.axiMaster.b.payload.resp.toInt == AXI_RESP_OKAY, "AXI B.RESP should be OKAY")
          }
          if (axiConf.useId) {
            assert(dut.io.axiMaster.b.payload.id.toInt == id, "AXI B.ID mismatch")
          }

          // 如果写成功，且需要验证，则发起一次读来验证
          if (!gmbSlaveShouldError) {
            val verifyReadId = if (axiConf.useId) (id + 1) % (1 << axiConf.idWidth) else 0
            println(s"$testName: Verifying Write via AXI Read: Addr=0x${address.toString(16)}, ID=$verifyReadId")
            AxiMasterDriver.driveAr(dut.io.axiMaster, address, verifyReadId)
            if (cd.waitSamplingWhere(200)(dut.io.axiMaster.r.valid.toBoolean)) {
              simFailure(s"$testName: Timeout waiting for AXI R valid for verification read, ID=$verifyReadId")
            }
            val readBackData = dut.io.axiMaster.r.payload.data.toBigInt
            println(s"$testName: AXI Read Response (R): ID=${if (axiConf.useId) dut.io.axiMaster.r.payload.id.toInt
              else -1}, Data=0x${readBackData.toString(16)}, RESP=${dut.io.axiMaster.r.payload.resp.toInt}")
            assert(dut.io.axiMaster.r.payload.resp.toInt == AXI_RESP_OKAY, "Verification AXI R.RESP should be OKAY")
            if (axiConf.useId) {
              assert(dut.io.axiMaster.r.payload.id.toInt == verifyReadId, "Verification AXI R.ID mismatch")
            }
            // 根据字节使能来确定期望的读回数据
            var expectedVerifiedData = BigInt(0)
            val originalBytes = (0 until axiConf.dataWidth / 8).map(i => (writeData >> (i * 8)) & 0xff)
            val beBytes = (0 until axiConf.dataWidth / 8).map(i => (byteEnable >> i) & 1)

            if (byteEnable == -1L || byteEnable == ((BigInt(1) << (axiConf.dataWidth / 8)) - 1)) { // 全写
              expectedVerifiedData = writeData
            } else { // 部分写，需要从0开始构建（因为SimulatedSplitGeneralMemory在部分写时，未被选通的字节可能保持原样或为0）
              // 为了简化验证，我们假设未被选通的字节读回为0，如果SimulatedSplitGeneralMemory是这样行为的话
              // 一个更健壮的验证需要知道SimulatedSplitGeneralMemory在部分写时对未选通字节的行为
              // 或者在写之前将内存初始化为已知值（例如全0）
              for (i <- 0 until axiConf.dataWidth / 8) {
                if (((byteEnable >> i) & 1) == 1) {
                  expectedVerifiedData |= ((writeData >> (i * 8)) & 0xff) << (i * 8)
                }
              }
            }
            assert(
              readBackData == expectedVerifiedData,
              s"Write verification failed: Expected 0x${expectedVerifiedData.toString(16)}, Got 0x${readBackData.toString(16)}"
            )
          }

        } else {
          // 读操作
          println(s"$testName: Driving AXI Read: Addr=0x${effectiveAddress.toString(16)}, ID=$id")
          AxiMasterDriver.driveAr(dut.io.axiMaster, effectiveAddress, id)

          // 等待 R 通道响应
          if (cd.waitSamplingWhere(200)(dut.io.axiMaster.r.valid.toBoolean)) {
            simFailure(s"$testName: Timeout waiting for AXI R valid for ID=$id")
          }
          val actualReadData = dut.io.axiMaster.r.payload.data.toBigInt
          println(s"$testName: AXI Read Response (R): ID=${if (axiConf.useId) dut.io.axiMaster.r.payload.id.toInt
            else -1}, Data=0x${actualReadData.toString(16)}, RESP=${dut.io.axiMaster.r.payload.resp.toInt}")

          if (gmbSlaveShouldError) {
            assert(
              dut.io.axiMaster.r.payload.resp.toInt == AXI_RESP_SLVERR,
              "AXI R.RESP should be SLVERR for GMB error"
            )
          } else {
            assert(dut.io.axiMaster.r.payload.resp.toInt == AXI_RESP_OKAY, "AXI R.RESP should be OKAY")
            assert(
              actualReadData == expectedReadData,
              s"Read data mismatch: Expected 0x${expectedReadData.toString(16)}, Got 0x${actualReadData.toString(16)}"
            )
          }
          if (axiConf.useId) {
            assert(dut.io.axiMaster.r.payload.id.toInt == id, "AXI R.ID mismatch")
          }
        }
        dut.clockDomain.waitSampling(10)
      }
    }
  }

  // --- TC1.1: 成功读 ---
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = false,
    address = 0x100L,
    expectedReadData = 0xcafed00dL,
    id = 1,
    testNameSuffix = "Read_WithId"
  )
  runSingleTransactionTest(
    axiConfigNoId,
    gmbConfigNoId,
    isWrite = false,
    address = 0x108L,
    expectedReadData = 0xabadbabeL,
    testNameSuffix = "Read_NoId"
  )

  // --- TC1.2: GMB 从设备报错读 ---
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = false,
    address = 0x200L,
    id = 2,
    gmbSlaveShouldError = true,
    testNameSuffix = "ReadError_WithId"
  )

  // --- TC2.1: 成功写 ---
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = true,
    address = 0x110L,
    writeData = 0xdeadbeefL,
    id = 3,
    testNameSuffix = "Write_WithId"
  )
  runSingleTransactionTest(
    axiConfigNoId,
    gmbConfigNoId,
    isWrite = true,
    address = 0x118L,
    writeData = 0xfeedfaceL,
    testNameSuffix = "Write_NoId"
  )

  // --- TC2.2: GMB 从设备报错写 ---
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = true,
    address = 0x208L,
    writeData = 0xbadaddL,
    id = 4,
    gmbSlaveShouldError = true,
    testNameSuffix = "WriteError_WithId"
  )

  // --- TC2.3: 写操作字节使能 ---
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = true,
    address = 0x120L,
    writeData = 0xaabbccddL,
    byteEnable = 0x1L,
    id = 5,
    testNameSuffix = "Write_BE_0x1_WithId"
  ) // LSB
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = true,
    address = 0x124L,
    writeData = 0xaabbccddL,
    byteEnable = 0x8L,
    id = 6,
    testNameSuffix = "Write_BE_0x8_WithId"
  ) // MSB
  runSingleTransactionTest(
    axiConfigWithId,
    gmbConfigWithId,
    isWrite = true,
    address = 0x128L,
    writeData = 0xaabbccddL,
    byteEnable = 0x6L,
    id = 7,
    testNameSuffix = "Write_BE_0x6_WithId"
  ) // Middle bytes

  def runAwWDifferentialDelayTest(
      axiConf: Axi4Config,
      gmbConf: GenericMemoryBusConfig,
      address: BigInt,
      writeData: BigInt,
      id: Int,
      aw_w_delay_cycles: Int, // AW发送后，W发送前的延迟周期数
      testNameSuffix: String
  ): Unit = {
    val testName = s"Axi4ToSplitGmbBridge_AwWDelay${aw_w_delay_cycles}c_ID[${axiConf.useId}]_" + testNameSuffix
    test(testName) {
      SimConfig.withWave.compile(new Axi4ToSplitGmbBridgeTestBench(axiConf, gmbConf, simMemInternalCfg)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        AxiMasterDriver.initAxiMasterSignals(dut.io.axiMaster)
        dut.io.axiMaster.b.ready #= true // Master 总是准备好接收B响应

        println(s"$testName: Driving AXI Write with AW-W delay: Addr=0x${address.toString(16)}, Data=0x${writeData
            .toString(16)}, ID=$id, Delay=$aw_w_delay_cycles cycles")

        val awThread = fork {
          AxiMasterDriver.driveAw(dut.io.axiMaster, address, id)
          println(s"$testName: AW sent for ID=$id.")
        }

        // 等待指定的延迟周期
        if (aw_w_delay_cycles > 0) {
          println(s"$testName: Waiting $aw_w_delay_cycles cycles before sending W for ID=$id.")
          cd.waitSampling(aw_w_delay_cycles)
        }

        val wThread = fork {
          AxiMasterDriver.driveW(dut.io.axiMaster, writeData, id = id) // W id is for driver tracking
          println(s"$testName: W sent for ID=$id.")
        }

        // 等待两个线程都完成发送（即AW和W都被桥接器接受）
        awThread.join()
        wThread.join()
        println(s"$testName: Both AW and W have been accepted by the bridge for ID=$id.")

        // 等待 B 通道响应
        if (cd.waitSamplingWhere(200)(dut.io.axiMaster.b.valid.toBoolean)) {
          simFailure(s"$testName: Timeout waiting for AXI B valid for ID=$id")
        }
        println(s"$testName: AXI Write Response (B): ID=${if (axiConf.useId) dut.io.axiMaster.b.payload.id.toInt
          else -1}, RESP=${dut.io.axiMaster.b.payload.resp.toInt}")
        assert(dut.io.axiMaster.b.payload.resp.toInt == AXI_RESP_OKAY, "AXI B.RESP should be OKAY")
        if (axiConf.useId) {
          assert(dut.io.axiMaster.b.payload.id.toInt == id, "AXI B.ID mismatch")
        }
        dut.clockDomain.waitSampling(10)
      }
    }
  }

  // 调用 AW/W 延迟测试
  runAwWDifferentialDelayTest(
    axiConfigWithId,
    gmbConfigWithId,
    address = 0x400L,
    writeData = 0x11223344L,
    id = 20,
    aw_w_delay_cycles = 0,
    testNameSuffix = "NoDelay"
  )
  runAwWDifferentialDelayTest(
    axiConfigWithId,
    gmbConfigWithId,
    address = 0x404L,
    writeData = 0x55667788L,
    id = 21,
    aw_w_delay_cycles = 3,
    testNameSuffix = "3CycleDelay"
  )
  runAwWDifferentialDelayTest(
    axiConfigWithId,
    gmbConfigWithId,
    address = 0x408L,
    writeData = 0x99aabbccL,
    id = 22,
    aw_w_delay_cycles = 5,
    testNameSuffix = "5CycleDelay"
  )

  // --- TC4.2: 连续同类型请求测试 ---
  case class Transaction(isWrite: Boolean, addr: BigInt, data: BigInt, id: Int, var received: Boolean = false)

  def runConsecutiveTransactionsTest(
      axiConf: Axi4Config,
      gmbConf: GenericMemoryBusConfig,
      transactions: Seq[Transaction],
      testNameSuffix: String
  ): Unit = {
    val testName = s"Axi4ToSplitGmbBridge_Consecutive_${transactions.headOption
        .map(t => if (t.isWrite) "Writes" else "Reads")
        .getOrElse("Ops")}_ID[${axiConf.useId}]_" + testNameSuffix
    test(testName) {
      SimConfig.withWave.compile(new Axi4ToSplitGmbBridgeTestBench(axiConf, gmbConf, simMemInternalCfg)).doSim { dut =>
        implicit val cd = dut.clockDomain.get
        dut.clockDomain.forkStimulus(10)
        AxiMasterDriver.initAxiMasterSignals(dut.io.axiMaster)

        // Master 总是准备好接收响应
        dut.io.axiMaster.r.ready #= true
        dut.io.axiMaster.b.ready #= true

        // 初始化内存 (用于读测试)
        transactions.filterNot(_.isWrite).foreach { t =>
          initGmbMemory(dut, t.addr.toLong, t.data, cd) // data 字段此时用作 expectedReadData
        }
        cd.waitSampling(5)

        val transactionQueue = mutable.Queue[Transaction]()
        enqueue(transactionQueue, transactions: _*) // 使用辅助函数enqueue
        val activeTransactions = mutable.Map[Int, Transaction]() // Track by ID if IDs are used

        // Driver thread: 发送所有请求
        val driverThread = fork {
          for (t <- transactions) {
            if (t.isWrite) {
              println(
                s"$testName: Driving AXI Write: Addr=0x${t.addr.toString(16)}, Data=0x${t.data.toString(16)}, ID=${t.id}"
              )
              // AW 和 W 可以稍微交错发送，或者一起发送
              val awFork = fork(AxiMasterDriver.driveAw(dut.io.axiMaster, t.addr, t.id))
              // cd.waitSampling(Random.nextInt(2)) // 可选的微小延迟
              val wFork = fork(AxiMasterDriver.driveW(dut.io.axiMaster, t.data, id = t.id))
              awFork.join()
              wFork.join()
            } else {
              println(s"$testName: Driving AXI Read: Addr=0x${t.addr.toString(16)}, ID=${t.id}")
              AxiMasterDriver.driveAr(dut.io.axiMaster, t.addr, t.id)
            }
            if (axiConf.useId) {
              activeTransactions(t.id) = t
            }
            // 请求之间可以有小的随机间隔
            if (transactionQueue.nonEmpty) {
              cd.waitSampling(Random.nextInt(3) + 1)
            }
          }
          println(s"$testName: All AXI requests sent.")
        }

        // Monitor thread: 监控并验证响应
        var responsesReceived = 0
        val monitorTimeoutCycles = transactions.length * 200 + 500

        while (responsesReceived < transactions.length) {
          if (
            cd.waitSamplingWhere(monitorTimeoutCycles)(
              dut.io.axiMaster.r.valid.toBoolean || dut.io.axiMaster.b.valid.toBoolean
            )
          ) {
            simFailure(
              s"$testName: Timeout waiting for any AXI response. Received $responsesReceived out of ${transactions.length}."
            )
          }

          if (dut.io.axiMaster.r.valid.toBoolean) {
            val r = dut.io.axiMaster.r.payload
            val rId = if (axiConf.useId) r.id.toInt else 0 // 如果不用ID，我们无法精确匹配，只能按顺序
            val t =
              if (axiConf.useId) activeTransactions.getOrElse(rId, null)
              else transactionQueue.headOption.filterNot(_.isWrite).orNull

            assert(t != null, s"$testName: Received unexpected AXI Read Rsp or ID mismatch. Rsp ID=$rId")
            assert(
              !t.isWrite,
              s"$testName: Received AXI Read Rsp for a Write transaction (ID=$rId). This shouldn't happen."
            )
            assert(!t.received, s"$testName: Received duplicate AXI Read Rsp for ID=$rId.")

            println(
              s"$testName: AXI Read Response (R): ID=$rId, Data=0x${r.data.toBigInt.toString(16)}, RESP=${r.resp.toInt}"
            )
            assert(r.resp.toInt == AXI_RESP_OKAY, s"AXI R.RESP should be OKAY for ID=$rId")
            assert(
              r.data.toBigInt == t.data,
              s"Read data mismatch for ID=$rId: Expected 0x${t.data.toString(16)}, Got 0x${r.data.toBigInt.toString(16)}"
            )
            t.received = true
            responsesReceived += 1
            if (!axiConf.useId && transactionQueue.nonEmpty && !transactionQueue.head.isWrite)
              transactionQueue.dequeue() // 按顺序出队，如果是读事务
          }

          if (dut.io.axiMaster.b.valid.toBoolean) {
            val b = dut.io.axiMaster.b.payload
            val bId = if (axiConf.useId) b.id.toInt else 0
            val t =
              if (axiConf.useId) activeTransactions.getOrElse(bId, null)
              else transactionQueue.headOption.filter(_.isWrite).orNull

            assert(t != null, s"$testName: Received unexpected AXI Write Rsp or ID mismatch. Rsp ID=$bId")
            assert(t.isWrite, s"$testName: Received AXI Write Rsp for a Read transaction (ID=$bId).")
            assert(!t.received, s"$testName: Received duplicate AXI Write Rsp for ID=$bId.")

            println(s"$testName: AXI Write Response (B): ID=$bId, RESP=${b.resp.toInt}")
            assert(b.resp.toInt == AXI_RESP_OKAY, s"AXI B.RESP should be OKAY for ID=$bId")
            t.received = true
            responsesReceived += 1
            if (!axiConf.useId && transactionQueue.nonEmpty && transactionQueue.head.isWrite)
              transactionQueue.dequeue() // 按顺序出队，如果是写事务
          }
        }
        driverThread.join() // 确保所有请求都已发出

        assert(
          responsesReceived == transactions.length,
          s"$testName: Did not receive all responses. Expected ${transactions.length}, Got $responsesReceived"
        )
        transactions.foreach(t => assert(t.received, s"$testName: Transaction not marked as received: $t"))

        dut.clockDomain.waitSampling(20)
      }
    }
  }

  // 调用连续同类型请求测试
  val consecutiveReads = Seq(
    Transaction(isWrite = false, addr = 0x500L, data = 0x11111111L, id = 30),
    Transaction(isWrite = false, addr = 0x504L, data = 0x22222222L, id = 31),
    Transaction(isWrite = false, addr = 0x508L, data = 0x33333333L, id = 32)
  )
  runConsecutiveTransactionsTest(axiConfigWithId, gmbConfigWithId, consecutiveReads, "ConsecutiveReads_WithId")

  val consecutiveWrites = Seq(
    Transaction(isWrite = true, addr = 0x600L, data = 0xaaaaaaaaL, id = 40),
    Transaction(isWrite = true, addr = 0x604L, data = 0xbbbbbbbbL, id = 41),
    Transaction(isWrite = true, addr = 0x608L, data = 0xccccccccL, id = 42)
  )
  runConsecutiveTransactionsTest(axiConfigWithId, gmbConfigWithId, consecutiveWrites, "ConsecutiveWrites_WithId")
}
