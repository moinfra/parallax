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

  val defaultConfig = ExtSRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 0)
  val configWithWait = ExtSRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 2)
  val byteEnableConfig = ExtSRAMConfig(sizeBytes = 4 KiB, addressWidth = 12, dataWidth = 32, readWaitCycles = 0)

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
      
      assert(readValue == testData, f"Read value mismatch: expected 0x$testData%X, got 0x$readValue%X")
      println(f"✅ Basic read/write test passed: 0x$readValue%X")
    }
  }

  test("should handle read wait cycles correctly") {
    SimConfig.withFstWave.compile(new SimulatedSRAM(configWithWait)).doSim { dut =>
      driveInit(dut)
      val testAddr = 0x20
      val testData = BigInt("aabbccdd", 16)
      
      writeWord(dut, testAddr, testData)
      val readValue = readWord(dut, testAddr)
      
      assert(readValue == testData, f"Read value mismatch with wait cycles: expected 0x$testData%X, got 0x$readValue%X")
      println(f"✅ Wait cycles test passed: 0x$readValue%X (${dut.config.readWaitCycles} wait cycles)")
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
      
      assert(readValue == expectedValue, 
        f"Byte enable test failed: expected 0x$expectedValue%X, got 0x$readValue%X")
      println(f"✅ Byte enable test passed: 0x$readValue%X")

      // 测试另一个地址，验证仅写入最低字节 (be_n = 0xe = 0b1110, 仅使能字节0)
      val addr2 = 0x31
      // 确保初始值为已知
      writeWord(dut, addr2, BigInt("00000000", 16), 0x00) 
      writeWord(dut, addr2, 0x4bcdefff, 0xe)
      
      val readValue2 = readWord(dut, addr2)
      val expectedValue2 = BigInt("000000ff", 16) // 仅字节0被写入
      
      assert(readValue2 == expectedValue2,
        f"Byte enable LSB test failed: expected 0x$expectedValue2%X, got 0x$readValue2%X")
      println(f"✅ Byte enable LSB test passed: 0x$readValue2%X")
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
        (BigInt("aabbccdd", 16), 0xE, BigInt("123456dd", 16)), // 仅字节0
        (BigInt("eeffaabb", 16), 0xD, BigInt("1234aadd", 16)), // 仅字节1  
        (BigInt("11223344", 16), 0xB, BigInt("1222aadd", 16)), // 仅字节2
        (BigInt("55667788", 16), 0x7, BigInt("5522aadd", 16)), // 仅字节3
        (BigInt("99aabbcc", 16), 0xC, BigInt("5522bbcc", 16)), // 字节0,1
        (BigInt("ddeeffaa", 16), 0x3, BigInt("ddeebbcc", 16))  // 字节2,3
      )
      
      for ((writeData, be_n, expected) <- testCases) {
        writeWord(dut, testAddr, writeData, be_n)
        dut.clockDomain.waitSampling()
        val readValue = readWord(dut, testAddr)
        assert(readValue == expected,
          f"Byte enable pattern failed: writeData=0x$writeData%X, be_n=0x$be_n%X, expected=0x$expected%X, got=0x$readValue%X")
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
          
          assert(readValue == expectedData,
            f"Random read mismatch at iteration $i: addr=0x$addr%X, expected=0x$expectedData%X, got=0x$readValue%X")
          
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
        assert(readValue == expectedData,
          f"Data integrity test failed at addr=0x$addr%X: expected=0x$expectedData%X, got=0x$readValue%X")
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
        assert(actual == expected,
          f"Rapid cycle test failed at offset $i: expected=0x$expected%X, got=0x$actual%X")
      }
      
      println(f"✅ Rapid read/write cycles test passed")
    }
  }

  thatsAll
}
