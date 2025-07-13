// testOnly test.scala.SRAMControllerSpec
package test.scala

import spinal.core._
import spinal.lib._
import spinal.sim._
import spinal.lib.sim._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import scala.util.Random
import scala.collection.mutable
import parallax.components.memory._
import _root_.parallax.utilities.ParallaxLogger

// 测试台 - 连接SRAMController和SRAM模拟器
class ExtSRAMTestBench(axiConfig: Axi4Config, ramConfig: SRAMConfig) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    val sramStats = out(new Bundle {
      val readCount = UInt(32 bits)
      val writeCount = UInt(32 bits)
    })
  }

  // 实例化被测组件
  val dut = new SRAMController(axiConfig, ramConfig)
  val sram = new SimulatedSRAM(ramConfig)

  // 连接AXI接口
  io.axi <> dut.io.axi

  // 连接SRAM接口
  dut.io.ram <> sram.io.ram

  // 统计信息（用于性能分析）
  val readCounter = Counter(32 bits)
  val writeCounter = Counter(32 bits)

  when(dut.io.ram.oe_n.fall()) {
    readCounter.increment()
  }

  when(dut.io.ram.we_n.fall()) {
    writeCounter.increment()
  }

  io.sramStats.readCount := readCounter.value
  io.sramStats.writeCount := writeCounter.value
}

// AXI4 主设备测试辅助类 - 修复版本
class AxiMasterHelper(axi: Axi4, clockDomain: ClockDomain) {

  // 写事务跟踪
  private val pendingWrites = mutable.Map[Int, WriteTransaction]()
  private var nextWriteId = 0

  // 读事务跟踪
  private val pendingReads = mutable.Map[Int, ReadTransaction]()
  private var nextReadId = 0

  // 响应数据存储
  private val writeResponses = mutable.Map[Int, Int]()
  private val readResponses = mutable.Map[Int, (Seq[BigInt], Int)]()

  case class WriteTransaction(id: Int, addr: BigInt, data: Seq[BigInt], strb: Seq[Int], expectedResp: Int = 0)
  case class ReadTransaction(
      id: Int,
      addr: BigInt,
      len: Int,
      expectedData: Option[Seq[BigInt]] = None,
      expectedResp: Int = 0
  )

  // 初始化所有AXI信号
  def initialize(): Unit = {
    // AW通道
    axi.aw.valid #= false
    axi.aw.addr #= 0
    axi.aw.id #= 0
    axi.aw.len #= 0
    axi.aw.size #= 2 // 4字节
    axi.aw.burst #= 1 // INCR

    // W通道
    axi.w.valid #= false
    axi.w.data #= 0
    axi.w.strb #= 0
    axi.w.last #= false

    // B通道 - 始终准备接收响应
    axi.b.ready #= true

    // AR通道
    axi.ar.valid #= false
    axi.ar.addr #= 0
    axi.ar.id #= 0
    axi.ar.len #= 0
    axi.ar.size #= 2
    axi.ar.burst #= 1

    // R通道 - 始终准备接收响应
    axi.r.ready #= true

    // 启动响应监听器
    startResponseMonitors()
  }

  // 启动响应监听器 - 持续监听B和R通道
  private def startResponseMonitors(): Unit = {
    // B通道响应监听器
    fork {
      while (true) {
        clockDomain.waitSampling()
        if (axi.b.valid.toBoolean && axi.b.ready.toBoolean) {
          val id = axi.b.id.toInt
          val resp = axi.b.resp.toInt
          writeResponses(id) = resp
        }
      }
    }

    // R通道响应监听器
    fork {
      val readBuffers = mutable.Map[Int, mutable.ArrayBuffer[BigInt]]()

      while (true) {
        clockDomain.waitSampling()
        if (axi.r.valid.toBoolean && axi.r.ready.toBoolean) {
          val id = axi.r.id.toInt
          val data = axi.r.data.toBigInt
          val resp = axi.r.resp.toInt
          val last = axi.r.last.toBoolean

          // 获取或创建该ID的数据缓冲区
          val buffer = readBuffers.getOrElseUpdate(id, mutable.ArrayBuffer[BigInt]())
          buffer += data

          // 如果是最后一个beat，完成该事务
          if (last) {
            readResponses(id) = (buffer.toSeq, resp)
            readBuffers.remove(id)
          }
        }
      }
    }
  }

  // 单个字写入 - 非阻塞版本
  def writeWord(addr: BigInt, data: BigInt, strb: Int = 0xf): WriteTransaction = {
    writeBurst(addr, Seq(data), Seq(strb))
  }

  // Burst写入 - 非阻塞版本
  def writeBurst(addr: BigInt, data: Seq[BigInt], strb: Seq[Int]): WriteTransaction = {
    val id = nextWriteId
    nextWriteId = (nextWriteId + 1) % 16

    val transaction = WriteTransaction(id, addr, data, strb)
    pendingWrites(id) = transaction

    // 使用fork异步发送请求，不阻塞调用者
    fork {
      // 启动写地址阶段
      clockDomain.waitSampling()
      axi.aw.valid #= true
      axi.aw.addr #= addr
      axi.aw.id #= id
      axi.aw.len #= data.length - 1
      axi.aw.size #= 2
      axi.aw.burst #= 1

      // 等待AW握手
      clockDomain.waitSamplingWhere(axi.aw.ready.toBoolean)
      axi.aw.valid #= false

      // 启动写数据阶段
      for (((dataWord, strbWord), idx) <- (data zip strb).zipWithIndex) {
        clockDomain.waitSampling()
        axi.w.valid #= true
        axi.w.data #= dataWord
        axi.w.strb #= strbWord
        axi.w.last #= (idx == data.length - 1)

        clockDomain.waitSamplingWhere(axi.w.ready.toBoolean)
        axi.w.valid #= false
      }
    }

    transaction
  }

  // 单个字读取 - 非阻塞版本
  def readWord(addr: BigInt): ReadTransaction = {
    readBurst(addr, 1)
  }

  // Burst读取 - 非阻塞版本
  def readBurst(addr: BigInt, length: Int): ReadTransaction = {
    val id = nextReadId
    nextReadId = (nextReadId + 1) % 16

    val transaction = ReadTransaction(id, addr, length)
    pendingReads(id) = transaction

    // 使用fork异步发送请求
    fork {
      // 启动读地址阶段
      clockDomain.waitSampling()
      axi.ar.valid #= true
      axi.ar.addr #= addr
      axi.ar.id #= id
      axi.ar.len #= length - 1
      axi.ar.size #= 2
      axi.ar.burst #= 1

      // 等待AR握手
      clockDomain.waitSamplingWhere(axi.ar.ready.toBoolean)
      axi.ar.valid #= false
    }

    transaction
  }

  // 等待写响应 - 改进版本
  def waitWriteResponse(transaction: WriteTransaction, timeout: Int = 100): Int = {
    var cycles = 0

    while (cycles < timeout) {
      clockDomain.waitSampling()
      writeResponses.get(transaction.id) match {
        case Some(resp) =>
          {
            ParallaxLogger.success(s"Got resp for write tx id=${transaction.id}")
            writeResponses.remove(transaction.id)
            pendingWrites.remove(transaction.id)
          }
          return resp
        case None => // 继续等待
      }
      cycles += 1
    }

    throw new RuntimeException(s"Write response timeout for transaction ${transaction.id}")
  }

  // 等待读响应 - 改进版本
  def waitReadResponse(transaction: ReadTransaction, timeout: Int = 100): (Seq[BigInt], Int) = {
    var cycles = 0

    while (cycles < timeout) {
      clockDomain.waitSampling()
      readResponses.get(transaction.id) match {
        case Some((data, resp)) => {
          ParallaxLogger.success(s"Got resp for read tx id=${transaction.id} ")
          readResponses.remove(transaction.id)
          pendingReads.remove(transaction.id)
          return (data, resp)
        }
        case None => // 继续等待
      }
      cycles += 1
    }

    throw new RuntimeException(s"Read response timeout for transaction ${transaction.id}")
  }

  // 检查事务是否完成 - 新增方法
  def isWriteComplete(transaction: WriteTransaction): Boolean = {
    writeResponses.contains(transaction.id)
  }

  def isReadComplete(transaction: ReadTransaction): Boolean = {
    readResponses.contains(transaction.id)
  }

  // 发送并发请求 - 新增便利方法
  def sendConcurrentRequests[T](requests: Seq[() => T]): Seq[T] = {
    requests.map(_.apply())
  }

  // 错误测试：发送无效的burst类型
  def writeWithInvalidBurst(addr: BigInt, data: BigInt, burstType: Int = 2): WriteTransaction = {
    val id = nextWriteId
    nextWriteId = (nextWriteId + 1) % 16

    val transaction = WriteTransaction(id, addr, Seq(data), Seq(0xf), expectedResp = 2) // SLVERR
    pendingWrites(id) = transaction

    fork {
      clockDomain.waitSampling()
      axi.aw.valid #= true
      axi.aw.addr #= addr
      axi.aw.id #= id
      axi.aw.len #= 0
      axi.aw.size #= 2
      axi.aw.burst #= burstType // 错误的burst类型

      clockDomain.waitSamplingWhere(axi.aw.ready.toBoolean)
      axi.aw.valid #= false

      // 发送数据
      clockDomain.waitSampling()
      axi.w.valid #= true
      axi.w.data #= data
      axi.w.strb #= 0xf
      axi.w.last #= true

      clockDomain.waitSamplingWhere(axi.w.ready.toBoolean)
      axi.w.valid #= false
    }

    transaction
  }

  // 清理所有挂起的事务
  def cleanup(): Unit = {
    writeResponses.clear()
    readResponses.clear()
    pendingWrites.clear()
    pendingReads.clear()
  }
}

class SRAMControllerSpec extends CustomSpinalSimFunSuite {

  // 测试配置
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
    useLock = false,
    useCache = false,
    useProt = false,
    useQos = false,
    useRegion = false,
    useResp = true,
    useStrb = true,
    useBurst = true,
    useLen = true,
    useSize = true
  )

  def createRamConfig(readWaitCycles: Int = 1, sramSize: BigInt = 1 << 16): SRAMConfig = {
    SRAMConfig(
      addressWidth = 16,
      dataWidth = 32,
      virtualBaseAddress = 0x80000000L,
      sizeBytes = sramSize,
      readWaitCycles = readWaitCycles,
      enableLog = true
    )
  }

  def withTestBench[T](ramConfig: SRAMConfig)(testCode: (ExtSRAMTestBench, AxiMasterHelper, ClockDomain) => T) = {
    val compiled = SimConfig.withWave.compile(new ExtSRAMTestBench(axiConfig, ramConfig))
    compiled.doSim { dut =>
      val clockDomain = dut.clockDomain
      clockDomain.forkStimulus(period = 10)

      val axiMaster = new AxiMasterHelper(dut.io.axi, clockDomain)
      axiMaster.initialize()

      clockDomain.waitSampling(5) // 初始化延迟

      try {
        testCode(dut, axiMaster, clockDomain)
      } finally {
        axiMaster.cleanup()
      }
    }
  }

  test("Basic single word write and read") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val testAddr = 0x80000000L
      val testData = 0xdeadbeefL

      // 写入数据
      val writeTransaction = axiMaster.writeWord(testAddr, testData)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 0, "Write should succeed with OKAY response")

      // 读取数据
      val readTransaction = axiMaster.readWord(testAddr)
      val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)

      assert(readResp == 0, "Read should succeed with OKAY response")
      assert(readData.length == 1, "Should receive exactly one data word")
      assert(readData.head == testData, f"Read data 0x${readData.head}%x should match written data 0x$testData%x")
    }
  }

  test("Burst write and read") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val baseAddr = 0x80000100L
      val testData = Seq(0x11111111L.toBigInt, 0x22222222L.toBigInt, 0x33333333L.toBigInt, 0x44444444L.toBigInt)
      val strb = Seq.fill(testData.length)(0xf)

      // Burst写入
      val writeTransaction = axiMaster.writeBurst(baseAddr, testData, strb)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 0, "Burst write should succeed")

      // Burst读取
      val readTransaction = axiMaster.readBurst(baseAddr, testData.length)
      val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)

      assert(readResp == 0, "Burst read should succeed")
      assert(readData.length == testData.length, "Should receive correct number of data words")

      for (((expected, actual), idx) <- (testData zip readData).zipWithIndex) {
        assert(actual == expected, f"Data mismatch at beat $idx: expected 0x$expected%x, got 0x$actual%x")
      }
    }
  }

  test("Byte enable functionality") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val testAddr = 0x80000200L

      // 写入完整的字
      val fullData = 0x12345678L
      val writeTransaction1 = axiMaster.writeWord(testAddr, fullData, 0xf)
      axiMaster.waitWriteResponse(writeTransaction1)

      // 仅更新低字节
      val partialData = 0xabcdefaaL
      val writeTransaction2 = axiMaster.writeWord(testAddr, partialData, 0x1) // 仅低字节
      axiMaster.waitWriteResponse(writeTransaction2)

      // 读取并验证
      val readTransaction = axiMaster.readWord(testAddr)
      val (readData, _) = axiMaster.waitReadResponse(readTransaction)

      val expectedData = (fullData & 0xffffff00L) | (partialData & 0xffL)
      assert(
        readData.head == expectedData,
        f"Byte enable result incorrect: expected 0x$expectedData%x, got 0x${readData.head}%x"
      )
    }
  }

  test("Address out of bounds error") {
    withTestBench(createRamConfig(sramSize = 1024)) { (dut, axiMaster, clockDomain) =>
      val invalidAddr = 0x80001000L // 超出1KB SRAM范围
      val testData = 0x12345678L

      // 写入无效地址
      val writeTransaction = axiMaster.writeWord(invalidAddr, testData)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 2, "Write to invalid address should return SLVERR")

      // 读取无效地址
      val readTransaction = axiMaster.readWord(invalidAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTransaction)
      assert(readResp == 2, "Read from invalid address should return SLVERR")
    }
  }

  test("Address alignment error") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val unalignedAddr = 0x80000001L // 4字节传输的非对齐地址
      val testData = 0x12345678L

      // 写入非对齐地址
      val writeTransaction = axiMaster.writeWord(unalignedAddr, testData)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 2, "Write to unaligned address should return SLVERR")

      // 读取非对齐地址
      val readTransaction = axiMaster.readWord(unalignedAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTransaction)
      assert(readResp == 2, "Read from unaligned address should return SLVERR")
    }
  }

  test("Invalid burst type error") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val testAddr = 0x80000300L
      val testData = 0x12345678L

      // 发送不支持的burst类型 (WRAP = 2)
      val writeTransaction = axiMaster.writeWithInvalidBurst(testAddr, testData, burstType = 2)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 2, "Unsupported burst type should return SLVERR")
    }
  }

  test("Read-write arbitration fairness") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val readAddr = 0x80000400L
      val writeAddr = 0x80000404L
      val writeData = 0xaaaabbbbL

      // 预写入一些数据用于读取
      val setupWrite = axiMaster.writeWord(readAddr, 0x12345678L)
      axiMaster.waitWriteResponse(setupWrite, 15)

      // 并发发送多个读写请求
      val writeTransactions = mutable.ArrayBuffer[axiMaster.WriteTransaction]()
      val readTransactions = mutable.ArrayBuffer[axiMaster.ReadTransaction]()

      // 快速连续发送多个请求（真正的并发）
      for (i <- 0 until 5) {
        if (i % 2 == 0) {
          val readTx = axiMaster.readWord(readAddr)
          readTransactions += readTx
        } else {
          val writeTx = axiMaster.writeWord(writeAddr + i * 4, writeData + i)
          writeTransactions += writeTx
        }
        clockDomain.waitSampling(3)

      }

      println(s"Sent ${readTransactions.length} read and ${writeTransactions.length} write requests concurrently")

      // 等待所有写事务完成
      for (writeTx <- writeTransactions) {
        val resp = axiMaster.waitWriteResponse(writeTx, 20)
        assert(resp == 0, s"Write transaction ${writeTx.id} should succeed")
      }

      // 等待所有读事务完成
      for (readTx <- readTransactions) {
        val (data, resp) = axiMaster.waitReadResponse(readTx)
        assert(resp == 0, s"Read transaction ${readTx.id} should succeed")
        assert(data.head == 0x12345678L, "Read data should match expected value")
      }

      println("All concurrent transactions completed successfully")
    }
  }
  test("Zero wait cycles configuration") {
    withTestBench(createRamConfig(readWaitCycles = 0)) { (dut, axiMaster, clockDomain) =>
      val testAddr = 0x80000500L
      val testData = 0xfeedbeefL

      // 测试零等待周期的读写
      val writeTransaction = axiMaster.writeWord(testAddr, testData)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 0, "Write should succeed with zero wait cycles")

      val readTransaction = axiMaster.readWord(testAddr)
      val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)
      assert(readResp == 0, "Read should succeed with zero wait cycles")
      assert(readData.head == testData, "Data should match with zero wait cycles")
    }
  }

  test("Multiple wait cycles configuration") {
    withTestBench(createRamConfig(readWaitCycles = 3)) { (dut, axiMaster, clockDomain) =>
      val testAddr = 0x80000600L
      val testData = 0xcafebabeL

      // 测试多等待周期的读写
      val writeTransaction = axiMaster.writeWord(testAddr, testData)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      assert(writeResp == 0, "Write should succeed with multiple wait cycles")

      val readTransaction = axiMaster.readWord(testAddr)
      val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)
      assert(readResp == 0, "Read should succeed with multiple wait cycles")
      assert(readData.head == testData, "Data should match with multiple wait cycles")
    }
  }

  test("Large burst performance test") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val baseAddr = 0x80000700L
      val burstLength = 16
      val testData = (0 until burstLength).map(i => (0x10000000L + i).toBigInt)
      val strb = Seq.fill(burstLength)(0xf)

      // 大burst写入
      val startTime = System.nanoTime()
      val writeTransaction = axiMaster.writeBurst(baseAddr, testData, strb)
      val writeResp = axiMaster.waitWriteResponse(writeTransaction)
      val writeTime = System.nanoTime() - startTime

      assert(writeResp == 0, "Large burst write should succeed")

      // 大burst读取
      val readStartTime = System.nanoTime()
      val readTransaction = axiMaster.readBurst(baseAddr, burstLength)
      val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)
      val readTime = System.nanoTime() - readStartTime

      assert(readResp == 0, "Large burst read should succeed")
      assert(readData == testData, "Large burst data should match")

      println(f"Performance: Write time: ${writeTime / 1000}μs, Read time: ${readTime / 1000}μs")
    }
  }

  test("Stress test with random operations") {
    withTestBench(createRamConfig()) { (dut, axiMaster, clockDomain) =>
      val random = new Random(42) // 固定种子确保可重现
      val baseAddr = 0x80001000L
      val maxWords = 256
      val operations = 50

      val writtenData = mutable.Map[Long, Long]()

      for (_ <- 0 until operations) {
        val addr = baseAddr + (random.nextInt(maxWords) * 4)

        if (random.nextBoolean()) {
          // 随机写操作
          val data = random.nextLong() & 0xffffffffL
          val writeTransaction = axiMaster.writeWord(addr, data)
          val writeResp = axiMaster.waitWriteResponse(writeTransaction)
          assert(writeResp == 0, s"Random write to 0x${addr.toHexString} should succeed")
          writtenData(addr) = data

        } else if (writtenData.contains(addr)) {
          // 读操作 - 仅从已写入的地址读取
          val readTransaction = axiMaster.readWord(addr)
          val (readData, readResp) = axiMaster.waitReadResponse(readTransaction)
          assert(readResp == 0, s"Random read from 0x${addr.toHexString} should succeed")
          assert(
            readData.head == writtenData(addr),
            s"Data mismatch at 0x${addr.toHexString}: expected 0x${writtenData(addr).toHexString}, got 0x${readData.head}"
          )
        }
      }

      println(s"Stress test completed: $operations operations, ${writtenData.size} unique addresses written")
    }
  }


  thatsAll
}
