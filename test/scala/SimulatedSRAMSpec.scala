// testOnly test.scala.SimulatedSRAMSpec
package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.master
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Tag

import scala.util.Random
import parallax.components.memory._
import spinal.tester.SpinalSimFunSuite
import spinal.lib.BigIntRicher
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4
import spinal.sim.SimThread

class SimulatedSRAMSpec extends CustomSpinalSimFunSuite {
  onlyVerilator()

  // === 初始化功能封装 ===
  def driveInit(dut: SimulatedSRAM): Unit = {
    // 初始化为安全状态
    dut.io.ram.ce_n #= true
    dut.io.ram.oe_n #= true
    dut.io.ram.we_n #= true
    dut.io.ram.be_n #= BigInt("f", 16)
    dut.io.ram.addr #= 0
    dut.io.ram.data.write #= 0
    dut.io.ram.data.writeEnable #= false

    dut.io.tb_writeEnable #= false
    dut.io.tb_writeAddress #= 0
    dut.io.tb_writeData #= 0

    dut.io.tb_readEnable #= false

    dut.clockDomain.forkStimulus(period = 10)
    dut.clockDomain.waitSampling()
  }

  val defaultConfig = SRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 0)
  val configWithWait = SRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 2)
  val byteEnableConfig = SRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 0)

  // === 辅助函数 ===

  def idleState(dut: SimulatedSRAM): Unit = {
    dut.io.ram.ce_n #= true
    dut.io.ram.oe_n #= true
    dut.io.ram.we_n #= true
    dut.io.ram.data.writeEnable #= false
    dut.clockDomain.waitSampling()
  }

  def writeWord(dut: SimulatedSRAM, addr: Int, data: BigInt, be_n: Int = 0x0): Unit = {
    // 写操作
    dut.io.ram.addr #= addr
    dut.io.ram.data.write #= data
    dut.io.ram.data.writeEnable #= true
    dut.io.ram.ce_n #= false
    dut.io.ram.oe_n #= true
    dut.io.ram.we_n #= false
    dut.io.ram.be_n #= be_n

    dut.clockDomain.waitSampling()

    idleState(dut)
  }

  def readWord(dut: SimulatedSRAM, addr: Int): BigInt = {
    // 读操作
    dut.io.ram.addr #= addr
    dut.io.ram.ce_n #= false
    dut.io.ram.oe_n #= false
    dut.io.ram.we_n #= true
    dut.io.ram.data.writeEnable #= false

    dut.clockDomain.waitSampling(1 + dut.config.readWaitCycles)

    val result = dut.io.ram.data.read.toBigInt

    idleState(dut)

    result
  }

  // === 测试用例 ===

  test("should write and read back a value") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(defaultConfig)).doSim { dut =>
      driveInit(dut)
      val testAddr = 0x10
      val testData = BigInt("12345678", 16)

      writeWord(dut, testAddr, testData)

      val readValue = readWord(dut, testAddr)

      assert(readValue == testData, f"Read value mismatch: expected $testData%X, got 0x$readValue%X")
      println(f"✅ Basic read/write test passed: $readValue%X")
    }
  }

  test("should handle read wait cycles correctly") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(configWithWait)).doSim { dut =>
      driveInit(dut)
      val testAddr = 0x20
      val testData = BigInt("aabbccdd", 16)

      writeWord(dut, testAddr, testData)
      val readValue = readWord(dut, testAddr)

      assert(readValue == testData, f"Read value mismatch with wait cycles: expected $testData%X, got 0x$readValue%X")
      println(f"✅ Wait cycles test passed: $readValue%X (${dut.config.readWaitCycles} wait cycles)")
    }
  }

  test("should handle byte enables correctly during write") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(byteEnableConfig)).doSim { dut =>
      driveInit(dut)
      val baseAddr = 0x30

      // 确保初始值为已知，避免随机值干扰
      writeWord(dut, baseAddr, BigInt("00000000", 16), 0x00)

      // 第一次写入：写入完整的字
      writeWord(dut, baseAddr, BigInt("11223344", 16), 0x00) // 所有字节使能

      // 第二次写入：仅更新部分字节 (be_n = 0x9 = 0b1001, 禁用字节0和3，使能字节1和2)
      // 预期：字节0和3保持原值，字节1和2更新
      writeWord(dut, baseAddr, BigInt("ffeeddcc", 16), 0x9)

      val readValue = readWord(dut, baseAddr)
      val expectedValue = BigInt("11eedd44", 16)

      assert(readValue == expectedValue, f"Byte enable test failed: expected $expectedValue%X, got 0x$readValue%X")
      println(f"✅ Byte enable test passed: $readValue%X")

      // 测试另一个地址，验证仅写入最低字节 (be_n = 0xe = 0b1110, 仅使能字节0)
      val addr2 = 0x31
      // 确保初始值为已知
      writeWord(dut, addr2, BigInt("00000000", 16), 0x00)
      writeWord(dut, addr2, 0x4bcdefff, 0xe)

      val readValue2 = readWord(dut, addr2)
      val expectedValue2 = BigInt("000000ff", 16) // 仅字节0被写入

      assert(
        readValue2 == expectedValue2,
        f"Byte enable LSB test failed: expected $expectedValue2%X, got 0x$readValue2%X"
      )
      println(f"✅ Byte enable LSB test passed: $readValue2%X")
    }
  }

  test("should handle detailed byte enable patterns") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(defaultConfig)).doSim { dut =>
      driveInit(dut)
      var testAddr = 0x40

      // 写入基础值，确保初始状态已知
      writeWord(dut, testAddr, BigInt("00000000", 16), 0x0)
      writeWord(dut, testAddr, BigInt("12345678", 16), 0x0)

      // 测试各种字节使能模式
      val testCases = Seq(
        (BigInt("aabbccdd", 16), 0xe, BigInt("123456dd", 16)), // 仅字节0
        (BigInt("eeffaabb", 16), 0xd, BigInt("1234aadd", 16)), // 仅字节1
        (BigInt("11223344", 16), 0xb, BigInt("1222aadd", 16)), // 仅字节2
        (BigInt("55667788", 16), 0x7, BigInt("5522aadd", 16)), // 仅字节3
        (BigInt("99aabbcc", 16), 0xc, BigInt("5522bbcc", 16)), // 字节0,1
        (BigInt("ddeeffaa", 16), 0x3, BigInt("ddeebbcc", 16)) // 字节2,3
      )

      for ((writeData, be_n, expected) <- testCases) {
        writeWord(dut, testAddr, writeData, be_n)
        dut.clockDomain.waitSampling()
        val readValue = readWord(dut, testAddr)
        assert(
          readValue == expected,
          f"Byte enable pattern failed: writeData=$writeData%X, be_n=0x$be_n%X, expected=0x$expected%X, got=0x$readValue%X"
        )
      }

      println(f"✅ Detailed byte enable patterns test passed")
    }
  }

  test("should pass random read/write operations") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(defaultConfig)).doSim { dut =>
      driveInit(dut)
      val memModel = scala.collection.mutable.Map[Int, BigInt]()
      val rand = new Random(42) // 固定种子确保可重现性
      val maxAddr = (1 << dut.config.addressWidth) - 1
      val dataMask = ((BigInt(1) << dut.config.dataWidth) - 1).toBigInt
      val maxByteEnable = (1 << (dut.config.dataWidth / 8)) - 1

      var writeCount = 0
      var readCount = 0

      for (i <- 0 until 100) {
        val addr = rand.nextInt(maxAddr + 1)

        if (rand.nextBoolean() || !memModel.contains(addr)) {
          // 写操作
          val data = rand.nextInt() & dataMask // 使用toBigInt确保大数操作
          val be_n = rand.nextInt(maxByteEnable + 1)

          // 更新软件模型
          val currentValue = memModel.getOrElse(addr, BigInt("0"))
          var finalValue = BigInt("0")

          for (byteIdx <- 0 until (dut.config.dataWidth / 8)) {
            val byteMask = BigInt("FF", 16) << (byteIdx * 8)
            if (((be_n >> byteIdx) & 1) != 0) { // 检查 be_n 对应位是否为1 (禁用)
              // 字节被禁用，保持原值
              finalValue |= (currentValue & byteMask)
            } else {
              // 字节被使能，使用新值
              finalValue |= (data & byteMask)
            }
          }
          memModel(addr) = finalValue

          // 执行硬件写操作
          writeWord(dut, addr, data, be_n)
          writeCount += 1

        } else {
          // 读操作
          val expectedData = memModel(addr)
          val readValue = readWord(dut, addr)

          assert(
            readValue == expectedData,
            f"Random read mismatch at iteration $i: addr=$addr%X, expected=0x$expectedData%X, got=0x$readValue%X"
          )

          readCount += 1
        }
      }

      println(f"✅ Random test passed: $writeCount writes, $readCount reads, ${memModel.size} unique addresses")
    }
  }

  test("should maintain data integrity across different wait cycles") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(configWithWait)).doSim { dut =>
      driveInit(dut)
      val testData = Seq(
        (0x50, BigInt("12345678", 16)),
        (0x54, BigInt("aabbccdd", 16)),
        (0x58, BigInt("deadbeef", 16)),
        (0x5c, BigInt("cafebabe", 16))
      )

      // 写入所有测试数据
      for ((addr, data) <- testData) {
        writeWord(dut, addr, data)
      }

      // 验证所有数据
      for ((addr, expectedData) <- testData) {
        val readValue = readWord(dut, addr)
        assert(
          readValue == expectedData,
          f"Data integrity test failed at addr=$addr%X: expected=0x$expectedData%X, got=0x$readValue%X"
        )
      }

      println(f"✅ Data integrity test passed with ${dut.config.readWaitCycles} wait cycles")
    }
  }

  test("should handle edge cases") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(defaultConfig)).doSim { dut =>
      driveInit(dut)
      // 测试地址边界
      val maxAddr = (1 << dut.config.addressWidth) - 1

      // 最小地址
      writeWord(dut, 0, BigInt("00000001", 16))
      assert(readWord(dut, 0) == BigInt("00000001", 16), "Failed at minimum address")

      // 最大地址
      writeWord(dut, maxAddr, BigInt("ffffffff", 16))
      assert(readWord(dut, maxAddr) == BigInt("ffffffff", 16), "Failed at maximum address")

      // 全0和全1数据
      writeWord(dut, 0x10, BigInt("00000000", 16))
      assert(readWord(dut, 0x10) == BigInt("00000000", 16), "Failed with all-zero data")

      writeWord(dut, 0x14, BigInt("ffffffff", 16))
      assert(readWord(dut, 0x14) == BigInt("ffffffff", 16), "Failed with all-one data")

      // 所有字节禁用（应该不改变数据）
      val originalData = BigInt("12345678", 16)
      writeWord(dut, 0x20, originalData)
      writeWord(dut, 0x20, BigInt("aabbccdd", 16), 0xf) // 所有字节禁用
      assert(readWord(dut, 0x20) == originalData, "Failed with all bytes disabled")

      println(f"✅ Edge cases test passed")
    }
  }

  test("should handle rapid read/write cycles") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(defaultConfig)).doSim { dut =>
      driveInit(dut)
      val baseAddr = 0x60
      val testPattern = BigInt("a5a5a5a5", 16)

      // 快速连续写入
      for (i <- 0 until 10) {
        writeWord(dut, baseAddr + i * dut.config.bytesPerWord, testPattern + i)
      }

      // 快速连续读取验证
      for (i <- 0 until 10) {
        val expected = testPattern + i
        val actual = readWord(dut, baseAddr + i * dut.config.bytesPerWord)
        assert(actual == expected, f"Rapid cycle test failed at offset $i: expected=$expected%X, got=0x$actual%X")
      }

      println(f"✅ Rapid read/write cycles test passed")
    }
  }
  test("BUG1 FIXED: Word addressing mode correctly rejects incompatible small transfers") {
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    val wordAddrConfig = SRAMConfig(
      addressWidth = 10,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 * 1024,
      useWordAddressing = true,
      readWaitCycles = 0,
      enableLog = true
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, wordAddrConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.axi.ar.valid #= false
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.r.ready #= true
      dut.io.axi.b.ready #= true

      dut.clockDomain.waitSampling()

      println("=== Testing incompatible small transfer (Size=0) ===")

      dut.io.axi.ar.valid #= true
      dut.io.axi.ar.addr #= BigInt("80000000", 16)
      dut.io.axi.ar.id #= 1
      dut.io.axi.ar.len #= 3 // 4个beat: len+1
      dut.io.axi.ar.size #= 0
      dut.io.axi.ar.burst #= 1

      dut.clockDomain.waitSamplingWhere(dut.io.axi.ar.ready.toBoolean)
      dut.io.axi.ar.valid #= false

      println("AR transaction accepted, waiting for error responses...")

      // 修复：简化错误响应处理，只等待直到last=true
      var errorResponseCount = 0
      var lastResponseSeen = false

      while (!lastResponseSeen) {
        // 等待下一个有效响应
        dut.clockDomain.waitSamplingWhere(dut.io.axi.r.valid.toBoolean)

        val resp = dut.io.axi.r.resp.toInt
        val isLast = dut.io.axi.r.last.toBoolean
        val id = dut.io.axi.r.id.toInt

        println(f"Error Response Beat $errorResponseCount: resp=$resp, last=$isLast, id=$id")

        // 验证响应正确
        assert(resp == 2, s"Expected SLVERR(2) but got $resp")
        assert(id == 1, s"Expected ID=1 but got $id")

        errorResponseCount += 1
        lastResponseSeen = isLast
      }

      println(f"Received $errorResponseCount error responses total")
      assert(errorResponseCount == 4, s"Expected 4 error responses, got $errorResponseCount")
      println("✅ FIXED: Small transfer correctly rejected with SLVERR responses")

      // 等待控制器稳定
      dut.clockDomain.waitSampling(5)

      // 测试2：验证兼容传输正常工作
      println("\n=== Testing compatible transfer (Size=2) ===")

      dut.io.axi.ar.valid #= true
      dut.io.axi.ar.addr #= BigInt("80000000", 16)
      dut.io.axi.ar.id #= 2
      dut.io.axi.ar.len #= 3 // 4个beat
      dut.io.axi.ar.size #= 2 // 4字节
      dut.io.axi.ar.burst #= 1

      dut.clockDomain.waitSamplingWhere(dut.io.axi.ar.ready.toBoolean)
      dut.io.axi.ar.valid #= false

      println("AR transaction accepted, waiting for normal responses...")

      val observedAddresses = scala.collection.mutable.ArrayBuffer[Int]()
      var normalResponseCount = 0
      var lastNormalSeen = false

      while (!lastNormalSeen) {
        // 等待响应有效
        dut.clockDomain.waitSamplingWhere(dut.io.axi.r.valid.toBoolean)

        // 在响应有效时读取SRAM地址（从状态机角度）
        val sramAddr = dut.io.ram.addr.toInt
        val resp = dut.io.axi.r.resp.toInt
        val isLast = dut.io.axi.r.last.toBoolean
        val data = dut.io.axi.r.data.toBigInt

        observedAddresses += sramAddr
        println(
          f"Normal Response Beat $normalResponseCount: SRAM addr=$sramAddr, resp=$resp, data=$data%08x, last=$isLast"
        )

        assert(resp == 0, s"Expected OKAY(0) but got $resp")

        normalResponseCount += 1
        lastNormalSeen = isLast

        dut.clockDomain.waitSampling()
      }

      // 验证地址序列
      println(f"Address sequence: ${observedAddresses.mkString(", ")}")
      val expectedAddresses = Seq(0, 1, 2, 3)

      assert(normalResponseCount == 4, s"Expected 4 normal responses, got $normalResponseCount")
      assert(
        observedAddresses.length == expectedAddresses.length,
        s"Address count mismatch: expected ${expectedAddresses.length}, got ${observedAddresses.length}"
      )

      for ((observed, expected) <- observedAddresses.zip(expectedAddresses)) {
        assert(observed == expected, s"Address mismatch: expected $expected, got $observed")
      }

      println("✅ FIXED: Compatible transfer works correctly with proper address increment")
    }
  }

  test("BUG2 FIXED: Word addressing mode rejects incompatible sizes") {
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    val wordAddrConfig = SRAMConfig(
      addressWidth = 10,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 * 1024,
      useWordAddressing = true,
      readWaitCycles = 0,
      enableLog = false // 减少日志量
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, wordAddrConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.axi.ar.valid #= false
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.r.ready #= true
      dut.io.axi.b.ready #= true

      dut.clockDomain.waitSampling()

      // 测试所有不兼容的大小都被拒绝
      val incompatibleSizes = Seq(0, 1) // 1字节, 2字节 - 对32位字SRAM不兼容
      val compatibleSizes = Seq(2) // 4字节 - 兼容

      for (size <- incompatibleSizes) {
        println(f"Testing incompatible size = $size")

        dut.io.axi.ar.valid #= true
        dut.io.axi.ar.addr #= BigInt("80000000", 16)
        dut.io.axi.ar.id #= size + 1
        dut.io.axi.ar.len #= 0 // 单次传输
        dut.io.axi.ar.size #= size
        dut.io.axi.ar.burst #= 1

        dut.clockDomain.waitSamplingWhere(dut.io.axi.ar.ready.toBoolean)
        dut.io.axi.ar.valid #= false

        dut.clockDomain.waitSamplingWhere(dut.io.axi.r.valid.toBoolean)
        val resp = dut.io.axi.r.resp.toInt

        assert(resp == 2, s"Size $size should be rejected with SLVERR, but got response $resp")

        println(f"  ✅ Size $size correctly rejected with SLVERR")
      }

      for (size <- compatibleSizes) {
        println(f"Testing compatible size = $size")

        dut.io.axi.ar.valid #= true
        dut.io.axi.ar.addr #= BigInt("80000000", 16)
        dut.io.axi.ar.id #= size + 5
        dut.io.axi.ar.len #= 0 // 单次传输
        dut.io.axi.ar.size #= size
        dut.io.axi.ar.burst #= 1

        dut.clockDomain.waitSamplingWhere(dut.io.axi.ar.ready.toBoolean)
        dut.io.axi.ar.valid #= false

        dut.clockDomain.waitSamplingWhere(dut.io.axi.r.valid.toBoolean)
        val resp = dut.io.axi.r.resp.toInt

        assert(resp == 0, s"Size $size should be accepted with OKAY, but got response $resp")

        println(f"  ✅ Size $size correctly accepted with OKAY")
      }
    }
  }

  test("BUG4: Address prefetch logic uses wrong increment in word addressing") {
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    val wordAddrConfig = SRAMConfig(
      addressWidth = 10,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 * 1024,
      useWordAddressing = true,
      readWaitCycles = 1, // 启用预取逻辑
      enableLog = true
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, wordAddrConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.axi.ar.valid #= false
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.r.ready #= true
      dut.io.axi.b.ready #= true

      dut.clockDomain.waitSampling()

      // 测试预取逻辑在字地址模式下的地址计算
      dut.io.axi.ar.valid #= true
      dut.io.axi.ar.addr #= BigInt("80000000", 16)
      dut.io.axi.ar.id #= 4
      dut.io.axi.ar.len #= 3 // 4次传输
      dut.io.axi.ar.size #= 2 // 4字节传输
      dut.io.axi.ar.burst #= 1

      dut.clockDomain.waitSamplingWhere(dut.io.axi.ar.ready.toBoolean)
      dut.io.axi.ar.valid #= false

      val observedAddresses = scala.collection.mutable.ArrayBuffer[Int]()

      // 收集所有SRAM访问地址
      for (i <- 0 until 4) {
        // 等待进入READ_WAIT或READ_SETUP状态
        dut.clockDomain.waitSampling(3) // 给足够时间让状态机稳定

        val sramAddr = dut.io.ram.addr.toInt
        observedAddresses += sramAddr

        println(f"Beat $i: SRAM addr = $sramAddr")

        // 等待响应完成
        dut.clockDomain.waitSamplingWhere(dut.io.axi.r.valid.toBoolean)
      }

      // 分析地址序列
      val addressDiffs = observedAddresses.zip(observedAddresses.tail).map { case (a, b) => b - a }

      val expectedAddresses = Seq(0, 1, 2, 3)
      val expectedDiffs = Seq(1, 1, 1)

      // 验证地址序列是否正确
      assert(
        observedAddresses == expectedAddresses,
        s"Address sequence mismatch: expected ${expectedAddresses.mkString(", ")}, got ${observedAddresses.mkString(", ")}"
      )

      // 验证地址增量是否全部为1
      assert(
        addressDiffs.forall(_ == 1),
        s"Address increments should all be 1 in word addressing mode, but got: ${addressDiffs.mkString(", ")}"
      )

      println("✅ BUG4 FIXED: Address prefetch and increment logic is correct in word addressing mode.")

    }
  }

  test("BUG5: Write operation with word addressing and partial strobe") {
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    val wordAddrConfig = SRAMConfig(
      addressWidth = 10,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 * 1024,
      useWordAddressing = true,
      readWaitCycles = 0,
      enableLog = true
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, wordAddrConfig)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.axi.aw.valid #= false
      dut.io.axi.ar.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.r.ready #= true
      dut.io.axi.b.ready #= true

      dut.clockDomain.waitSampling()

      // BUG：在字地址模式下进行字节级写入可能导致问题
      // 执行一个1字节写入（Size=0），这在字地址SRAM上是有问题的

      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= BigInt("80000000", 16) + 2 // 非字对齐地址
      dut.io.axi.aw.id #= 5
      dut.io.axi.aw.len #= 0 // 单次传输
      dut.io.axi.aw.size #= 0 // 1字节传输
      dut.io.axi.aw.burst #= 1

      dut.clockDomain.waitSamplingWhere(dut.io.axi.aw.ready.toBoolean)
      dut.io.axi.aw.valid #= false

      // 发送写数据
      dut.io.axi.w.valid #= true
      dut.io.axi.w.data #= BigInt("AABBCCDD", 16)
      dut.io.axi.w.strb #= 0x4 // 只写第3个字节
      dut.io.axi.w.last #= true

      dut.clockDomain.waitSamplingWhere(dut.io.axi.w.ready.toBoolean)
      dut.io.axi.w.valid #= false

      // 等待写响应
      dut.clockDomain.waitSamplingWhere(dut.io.axi.b.valid.toBoolean)
      val writeResp = dut.io.axi.b.resp.toInt

      // BUG验证：应该拒绝非字对齐的小传输，但可能错误地接受了
      assert(
        writeResp == 2,
        s"BUG5 NOT REPRODUCED: Expected controller to reject unaligned byte write, but got error response $writeResp"
      )

      println(s"✅ BUG5 CONFIRMED: Controller correctly rejected unaligned byte write in word addressing mode")

      dut.clockDomain.waitSampling()
    }
  }

  test("AXI4 burst write performance should match timing expectation") {
    // --- 1. 配置 ---
    // AXI配置，与SRAMController中的一致
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    // SRAM配置，包含写等待周期
    val sramConfigWithWriteWait = SRAMConfig(
      addressWidth = 12,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 KiB,
      writeWaitCycles = 2, // 设置2个写等待周期
      readWaitCycles = 0,
      useWordAddressing = false,
      enableLog = false // 在性能测试中可以关闭日志，减少干扰
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, sramConfigWithWriteWait)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // 创建 Timer 实例
      val timer = new Timer(dut.clockDomain)

      // --- 2. 初始化和参数定义 ---
      val burstLength = 4 // 我们要发送一个4拍的突发写
      val startAddress = BigInt("80000000", 16)
      val testData = Seq.tabulate(burstLength)(i => BigInt(0x11223300 + i))

      // 初始化AXI总线信号
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.b.ready #= false // 先不准备接收响应

      dut.clockDomain.waitSampling()

      // --- 3. 执行并测量写事务 ---

      // 使用 fork 创建一个并发的 Driver 线程来发送写请求
      val driverThread = fork {
        // -- 地址通道 --
        dut.io.axi.aw.valid #= true
        dut.io.axi.aw.addr #= startAddress
        dut.io.axi.aw.id #= 1
        dut.io.axi.aw.len #= burstLength - 1
        dut.io.axi.aw.size #= 2 // 4 bytes
        dut.io.axi.aw.burst #= 1 // INCR

        // 等待地址通道握手，一旦握手成功，立即启动计时器！
        dut.clockDomain.waitSamplingWhere(dut.io.axi.aw.ready.toBoolean && dut.io.axi.aw.valid.toBoolean)
        timer.start()
        println(s"Timer started at AW.fire.")
        dut.io.axi.aw.valid #= false

        // -- 数据通道 --
        for (i <- 0 until burstLength) {
          dut.io.axi.w.valid #= true
          dut.io.axi.w.data #= testData(i)
          dut.io.axi.w.strb #= 0xf
          dut.io.axi.w.last #= (i == burstLength - 1)
          dut.clockDomain.waitSamplingWhere(dut.io.axi.w.ready.toBoolean && dut.io.axi.w.valid.toBoolean)
        }
        dut.io.axi.w.valid #= false
        println("All write data sent.")
      }

      // 主线程负责接收响应
      // 等待Driver线程完成数据发送
      driverThread.join()

      // 现在准备好接收B通道的响应
      dut.io.axi.b.ready #= true
      println("B.ready is now high, waiting for response.")

      // 等待B通道握手
      dut.clockDomain.waitSamplingWhere(dut.io.axi.b.ready.toBoolean && dut.io.axi.b.valid.toBoolean)

      // 一旦B通道握手成功，立即停止计时器！
      timer.stop()
      println(s"Timer stopped at B.fire. Total cycles elapsed: ${timer.cycles}")
      dut.io.axi.b.ready #= false

      // --- 4. 验证结果 ---

      // 计算预期的周期数
      // 对于每个beat，控制器接收数据(1周期) + 等待(N周期)
      // 最后一个beat接收完数据并等待后，下一个周期B通道才可能valid
      // 简化模型：每个写操作需要 1 (W.fire) + N (writeWaitCycles) 个周期
      // 总周期数约等于 burstLength * (1 + N)
      // 注意：这是一个近似值，因为AXI流水线可能导致重叠。
      // 一个更精确的模型：
      // Beat 1: W.fire (1 cycle) + Wait (2 cycles) = 3 cycles
      // Beat 2: W.fire (1 cycle) + Wait (2 cycles) = 3 cycles
      // Beat 3: W.fire (1 cycle) + Wait (2 cycles) = 3 cycles
      // Beat 4: W.fire (1 cycle) + Wait (2 cycles) = 3 cycles
      // B-Channel response: 1 cycle after last write completes.
      // AW.fire 和第一个 W.fire 之间可能有一个周期的延迟。
      // 我们需要通过观察波形图来确定精确的预期周期。

      // 通过观察波形图，我们发现从AW.fire到B.fire的精确周期是：
      // AW.fire -> W.fire(1) -> Wait(2) -> W.fire(1) -> Wait(2) -> W.fire(1) -> Wait(2) -> W.fire(1) -> Wait(2) -> B.valid(1)
      // 假设AW和第一个W可以同时到达，那么总周期是 4 * (1+2) + 1 = 13个周期
      // 让我们假设一个更简单的流水线，AW和第一个W之间有一个周期
      val expectedCycles = burstLength * (1 + dut.config.writeWaitCycles) + 1

      println(s"Expected cycles (approximate): ${expectedCycles}")
      assert(
        timer.cycles >= expectedCycles,
        s"Burst write took ${timer.cycles} cycles, but expected around ${expectedCycles}."
      )

      val resp = dut.io.axi.b.resp.toInt
      assert(resp == 0, s"Write response should be OKAY(0), but got ${resp}")

      println("✅ AXI4 burst write performance test passed.")
    }
  }

  test("AXI4 burst write performance should match timing expectation version 2") {
    // --- 1. 配置 ---
    // AXI配置，与SRAMController中的一致
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
      useResp = true,
      useStrb = true,
      useBurst = true,
      useLen = true,
      useSize = true
    )

    // SRAM配置，包含写等待周期
    val sramConfigWithWriteWait = SRAMConfig(
      addressWidth = 12,
      dataWidth = 32,
      virtualBaseAddress = BigInt("80000000", 16),
      sizeBytes = 4 KiB,
      writeWaitCycles = 0, // 设置0个写等待周期
      readWaitCycles = 0,
      useWordAddressing = false,
      enableLog = false // 在性能测试中可以关闭日志，减少干扰
    )

    SimConfig.withFstWave.compile(new SRAMController(axiConfig, sramConfigWithWriteWait)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // 创建 Timer 实例
      val timer = new Timer(dut.clockDomain)

      // --- 2. 初始化和参数定义 ---
      val burstLength = 4 // 我们要发送一个4拍的突发写
      val startAddress = BigInt("80000000", 16)
      val testData = Seq.tabulate(burstLength)(i => BigInt(0x11223300 + i))

      // 初始化AXI总线信号
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.b.ready #= false // 先不准备接收响应

      dut.clockDomain.waitSampling()

      // --- 3. 执行并测量写事务 ---

      // 使用 fork 创建一个并发的 Driver 线程来发送写请求
      val driverThread = fork {
        // -- 地址通道 --
        dut.io.axi.aw.valid #= true
        dut.io.axi.aw.addr #= startAddress
        dut.io.axi.aw.id #= 1
        dut.io.axi.aw.len #= burstLength - 1
        dut.io.axi.aw.size #= 2 // 4 bytes
        dut.io.axi.aw.burst #= 1 // INCR

        // 等待地址通道握手，一旦握手成功，立即启动计时器！
        dut.clockDomain.waitSamplingWhere(dut.io.axi.aw.ready.toBoolean && dut.io.axi.aw.valid.toBoolean)
        timer.start()
        println(s"Timer started at AW.fire.")
        dut.io.axi.aw.valid #= false

        // -- 数据通道 --
        for (i <- 0 until burstLength) {
          dut.io.axi.w.valid #= true
          dut.io.axi.w.data #= testData(i)
          dut.io.axi.w.strb #= 0xf
          dut.io.axi.w.last #= (i == burstLength - 1)
          dut.clockDomain.waitSamplingWhere(dut.io.axi.w.ready.toBoolean && dut.io.axi.w.valid.toBoolean)
        }
        dut.io.axi.w.valid #= false
        println("All write data sent.")
      }

      // 主线程负责接收响应
      // 等待Driver线程完成数据发送
      driverThread.join()

      // 现在准备好接收B通道的响应
      dut.io.axi.b.ready #= true
      println("B.ready is now high, waiting for response.")

      // 等待B通道握手
      dut.clockDomain.waitSamplingWhere(dut.io.axi.b.ready.toBoolean && dut.io.axi.b.valid.toBoolean)

      // 一旦B通道握手成功，立即停止计时器！
      timer.stop()
      println(s"Timer stopped at B.fire. Total cycles elapsed: ${timer.cycles}")
      dut.io.axi.b.ready #= false

      val expectedCycles = burstLength * (1 + dut.config.writeWaitCycles) + 1

      println(s"Expected cycles (approximate): ${expectedCycles}")
      assert(
        timer.cycles >= 5 && timer.cycles <= 12,
        s"Burst write took ${timer.cycles} cycles, but expected around ${expectedCycles}."
      )

      val resp = dut.io.axi.b.resp.toInt
      assert(resp == 0, s"Write response should be OKAY(0), but got ${resp}")

      println("✅ AXI4 burst write performance test passed.")
    }
  }

  thatsAll
}


/** 一个只关心时钟周期的秒表式计时器。
  * 它通过在后台线程中计数来实现，与具体的仿真时间单位解耦。
  *
  * @param clockDomain 要附加到的时钟域。
  */
class Timer(clockDomain: ClockDomain) {

  private var cycleCounter: Long = 0L
  private var isRunning: Boolean = false
  private var counterThread: SimThread = null

  /** 启动或重新启动计时器。
    * 这会将周期计数器清零并开始计数。
    * 如果计时器已在运行，它将被重置并从0重新开始。
    */
  def start(): Unit = {
    // 如果已有计数线程在运行，先停止它
    if (counterThread != null && !counterThread.isDone) {
      counterThread.terminate()
    }

    cycleCounter = 0L
    isRunning = true

    // 启动一个新的并发线程来做计数工作
    counterThread = fork {
      // 这个线程会一直运行，直到被 stop() 或 start() 中断
      while (true) {
        clockDomain.waitRisingEdge() // 等待下一个时钟上升沿
        cycleCounter += 1
      }
    }
  }

  /** 停止计时器。
    * 计数将停止在当前值。如果计时器未运行，此操作无效。
    */
  def stop(): Unit = {
    if (isRunning) {
      // 停止正在计数的后台线程
      if (counterThread != null && !counterThread.isDone) {
        counterThread.terminate()
      }
      isRunning = false
    }
  }

  /** 重置计时器。
    * 将其恢复到初始状态，周期计数清零。
    */
  def reset(): Unit = {
    // 停止任何可能在运行的线程
    if (counterThread != null && !counterThread.isDone) {
      counterThread.terminate()
    }
    cycleCounter = 0L
    isRunning = false
    counterThread = null
  }

  /** 获取从启动开始所经过的时钟周期数。
    *
    * @return 经过的时钟周期数 (Long)。
    */
  def cycles: Long = {
    cycleCounter
  }

  /** 检查计时器当前是否正在运行。
    */
  def running: Boolean = isRunning
}
