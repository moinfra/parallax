// testOnly test.scala.SRAMControllerWordAddrSpec
// file: test/scala/SRAMControllerWordAddrSpec.scala

package test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.components.memory._
import scala.util.Random
import scala.collection.mutable

/**
 * Test suite specifically for the SRAMController's Word Addressing mode.
 * In this mode, the controller converts AXI byte addresses to SRAM word addresses.
 * For a 32-bit data bus (4 bytes/word), this means AXI address is divided by 4.
 * This mode requires word-aligned AXI accesses.
 */
class SRAMControllerWordAddrSpec extends CustomSpinalSimFunSuite {

  // AXI configuration remains the same
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

  /**
   * Helper to create a SRAMConfig for word-addressing mode.
   * Example: 4MB SRAM, 32-bit data => 1M words => 20 address lines.
   */
  def createWordAddrRamConfig(readWaitCycles: Int = 0,writeWaitCycles:Int = 0,  sramSize: BigInt = 4 * 1024 * 1024, enableLog: Boolean = false): SRAMConfig = {
    val bytesPerWord = axiConfig.dataWidth / 8
    val wordCount = sramSize / bytesPerWord
    val addressWidth = log2Up(wordCount)

    SRAMConfig(
      addressWidth = addressWidth,
      dataWidth = axiConfig.dataWidth,
      virtualBaseAddress = 0x80000000L,
      sizeBytes = sramSize,
      readWaitCycles = readWaitCycles,
      writeWaitCycles = writeWaitCycles,
      useWordAddressing = true, // Enable the mode under test
      enableLog = enableLog // Keep logs clean unless debugging
    )
  }

  // The withTestBench helper is identical to the one in the other spec file
  def withTestBench[T](ramConfig: SRAMConfig)(testCode: (ExtSRAMTestBench, AxiMasterHelper, ClockDomain) => T) = {
    val compiled = SimConfig.withConfig(SpinalConfig(
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
    defaultClockDomainFrequency = FixedFrequency(162 MHz),
    ))
.withIVerilog  .withWave.compile(new ExtSRAMTestBench(axiConfig, ramConfig, useBlackbox = true))
    compiled.doSim { dut =>
      val clockDomain = dut.clockDomain
      clockDomain.forkStimulus(period = 10 * 1000)

      val axiMaster = new AxiMasterHelper(dut.io.axi, clockDomain)
      axiMaster.initialize()

      clockDomain.waitSampling(5)

      try {
        testCode(dut, axiMaster, clockDomain)
      } finally {
        axiMaster.cleanup()
      }
    }
  }

  test("WordAddr - Basic write and read with address translation") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, _) =>
      // AXI address 0x80000100 -> offset 0x100 -> word address 0x40
      val testAddr = 0x80000100L
      val testData = 0xdeadbeefL

      val writeTx = axiMaster.writeWord(testAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 0, "Write OKAY")

      val readTx = axiMaster.readWord(testAddr)
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)

      assert(readResp == 0, "Read OKAY")
      assert(readData.head == testData, f"Data mismatch: 0x${readData.head}%x != 0x$testData%x")
    }
  }

  test("WordAddr - Burst write and read, check address increment") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      // AXI addresses: 0x80000200, 0x80000204, 0x80000208, 0x8000020C
      // SRAM word addresses should be: 0x80, 0x81, 0x82, 0x83
      val baseAddr = 0x80000200L
      val testData = Seq(0x1111, 0x2222, 0x3333, 0x4444).map(BigInt(_))
      val strb = Seq.fill(testData.length)(0xf)

      val writeTx = axiMaster.writeBurst(baseAddr, testData, strb)
      assert(axiMaster.waitWriteResponse(writeTx) == 0, "Burst write OKAY")

      val readTx = axiMaster.readBurst(baseAddr, testData.length)
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)

      assert(readResp == 0, "Burst read OKAY")
      assert(readData == testData, "Burst data must match")
    }
  }

  test("WordAddr - Word alignment error") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      // Address is not a multiple of 4, should be rejected by the controller
      val unalignedAddr = 0x80000001L 
      val testData = 0xbadaddL

      val writeTx = axiMaster.writeWord(unalignedAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 2, "Write to unaligned address should return SLVERR")

      val readTx = axiMaster.readWord(unalignedAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 2, "Read from unaligned address should return SLVERR")
    }
  }

  test("WordAddr - Address out of bounds error") {
    // 4MB SRAM, size = 0x400000.
    // Valid AXI address range: 0x80000000 to 0x803FFFFF
    val sramSize = 4 * 1024 * 1024
    withTestBench(createWordAddrRamConfig(sramSize = sramSize)) { (dut, axiMaster, _) =>
      // This is the first byte address outside the 4MB range
      val invalidAddr = 0x80000000L + sramSize 
      val testData = 0x12345678L

      val writeTx = axiMaster.writeWord(invalidAddr, testData)
      assert(axiMaster.waitWriteResponse(writeTx) == 2, "Write to out-of-bounds address should be SLVERR")

      val readTx = axiMaster.readWord(invalidAddr)
      val (_, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 2, "Read from out-of-bounds address should be SLVERR")
    }
  }
  
  test("WordAddr - Stress test with random word-aligned operations") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      val random = new Random(42)
      val baseAddr = 0x80001000L
      val maxWords = 256
      val operations = 50

      val writtenData = mutable.Map[Long, Long]()

      for (i <- 0 until operations) {
        // Ensure generated address is always word-aligned
        val addr = baseAddr + (random.nextInt(maxWords) * 4) 

        if (random.nextBoolean() || writtenData.isEmpty) {
          // Write
          val data = random.nextLong() & 0xffffffffL
          val writeTx = axiMaster.writeWord(addr, data)
          assert(axiMaster.waitWriteResponse(writeTx) == 0, f"Random write to 0x$addr%x should succeed (op $i)")
          writtenData(addr) = data
        } else {
          // Read from a previously written address
          val readAddr = writtenData.keys.toSeq(random.nextInt(writtenData.size))
          val expectedData = writtenData(readAddr)

          val readTx = axiMaster.readWord(readAddr)
          val (readData, readResp) = axiMaster.waitReadResponse(readTx)
          
          assert(readResp == 0, f"Random read from 0x$readAddr%x should succeed (op $i)")
          assert(
            readData.head == expectedData,
            f"Data mismatch at 0x$readAddr%x: expected 0x$expectedData%x, got 0x${readData.head}%x"
          )
        }
      }
      println(s"WordAddr Stress test completed: $operations operations, ${writtenData.size} unique addresses written")
    }
  }

  test("WordAddr - Byte enable functionality remains correct") {
    withTestBench(createWordAddrRamConfig()) { (dut, axiMaster, _) =>
      val testAddr = 0x80000200L

      val fullData = 0x12345678L
      axiMaster.waitWriteResponse(axiMaster.writeWord(testAddr, fullData, 0xf))

      // Update only the upper two bytes (strb = 1100b = 0xc)
      val partialData = 0xabcdef00L
      axiMaster.waitWriteResponse(axiMaster.writeWord(testAddr, partialData, 0xc))

      val (readData, _) = axiMaster.waitReadResponse(axiMaster.readWord(testAddr))

      val expectedData = (partialData & 0xffff0000L) | (fullData & 0x0000ffffL)
      assert(
        readData.head == expectedData,
        f"Byte enable result incorrect: expected 0x$expectedData%x, got 0x${readData.head}%x"
      )
    }
  }

  // =============================================================================
  // AXI协议违规问题测试 - 更严格的测试来暴露SRAMController的所有AXI协议违规问题
  // =============================================================================

  test("AXI_VIOLATION_1 - Valid signal instability during handshake") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露valid信号在握手期间不稳定的问题
      val testAddr = 0x80000000L
      
      // 手动驱动AW通道
      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= testAddr
      dut.io.axi.aw.id #= 1
      dut.io.axi.aw.len #= 0
      dut.io.axi.aw.size #= 2
      dut.io.axi.aw.burst #= 1
      
      // 强制让ready信号延迟响应，检查valid信号的稳定性
      var validHistory = scala.collection.mutable.ArrayBuffer[Boolean]()
      var readyHistory = scala.collection.mutable.ArrayBuffer[Boolean]()
      var addrHistory = scala.collection.mutable.ArrayBuffer[BigInt]()
      
      // 监控更多周期，检查valid信号的稳定性
      for (i <- 0 until 20) {
        clockDomain.waitSampling(1)
        
        val currentValid = dut.io.axi.aw.valid.toBoolean
        val currentReady = dut.io.axi.aw.ready.toBoolean
        val currentAddr = dut.io.axi.aw.addr.toBigInt
        
        validHistory += currentValid
        readyHistory += currentReady
        addrHistory += currentAddr
        
        println(s"Cycle $i: AW.valid=$currentValid, AW.ready=$currentReady, addr=0x${currentAddr.toString(16)}")
        
        // 检查一旦ready为true，valid是否意外变化
        if (currentReady && currentValid) {
          println(s"Handshake occurred at cycle $i")
        }
      }
      
      // 严格验证：检查valid信号在ready为false时是否保持稳定
      var validViolations = 0
      var addrViolations = 0
      
      for (i <- 1 until validHistory.length) {
        if (!readyHistory(i-1) && validHistory(i-1)) {
          // valid在上一个周期为true且ready为false时，这个周期valid应该保持true
          if (!validHistory(i)) {
            validViolations += 1
            println(s"ERROR: Valid signal dropped at cycle $i while ready was false")
          }
          // 地址也应该保持稳定
          if (addrHistory(i) != addrHistory(i-1)) {
            addrViolations += 1
            println(s"ERROR: Address changed at cycle $i while handshake not complete")
          }
        }
      }
      
      // 这里应该FAIL来暴露问题
      assert(validViolations == 0, s"Valid signal violated AXI protocol $validViolations times")
      assert(addrViolations == 0, s"Address signal violated AXI protocol $addrViolations times")
      
      // 清理
      dut.io.axi.aw.valid #= false
    }
  }

  test("AXI_VIOLATION_2 - Write Response protocol violation") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露写响应协议违规问题 - 更严格的检查
      val testAddr = 0x80000000L
      val testData = 0x12345678L
      val expectedId = 1

      // 发起写事务但不立即接收响应
      val writeTx = axiMaster.writeWord(testAddr, testData)
      
      // 强制B通道ready为false，检查valid是否意外撤销
      dut.io.axi.b.ready #= false
      
      var bValidHistory = scala.collection.mutable.ArrayBuffer[Boolean]()
      var bRespHistory = scala.collection.mutable.ArrayBuffer[Int]()
      var bIdHistory = scala.collection.mutable.ArrayBuffer[Int]()
      
      // 监控更长时间的B通道行为
      for (i <- 0 until 50) {
        clockDomain.waitSampling(1)
        
        val currentBValid = dut.io.axi.b.valid.toBoolean
        val currentResp = dut.io.axi.b.resp.toInt
        val currentId = dut.io.axi.b.id.toInt
        
        bValidHistory += currentBValid
        bRespHistory += currentResp
        bIdHistory += currentId
        
        if (currentBValid) {
          println(s"Cycle $i: B.valid=true, resp=$currentResp, id=$currentId")
        }
      }
      
      // 严格检查AXI协议违规
      var bValidViolations = 0
      var bRespViolations = 0
      var bIdViolations = 0
      
      var firstValidIndex = -1
      for (i <- bValidHistory.indices) {
        if (bValidHistory(i) && firstValidIndex == -1) {
          firstValidIndex = i
        }
      }
      
      if (firstValidIndex >= 0) {
        val expectedResp = bRespHistory(firstValidIndex)
        val expectedId = bIdHistory(firstValidIndex)
        
        for (i <- firstValidIndex until bValidHistory.length) {
          if (bValidHistory(i)) {
            // 一旦valid为true，在ready为false的情况下应该保持稳定
            if (i > firstValidIndex && !bValidHistory(i-1)) {
              bValidViolations += 1
              println(s"ERROR: B.valid dropped and reasserted at cycle $i")
            }
            
            // 响应和ID必须保持稳定
            if (bRespHistory(i) != expectedResp) {
              bRespViolations += 1
              println(s"ERROR: B.resp changed from $expectedResp to ${bRespHistory(i)} at cycle $i")
            }
            if (bIdHistory(i) != expectedId) {
              bIdViolations += 1
              println(s"ERROR: B.id changed from $expectedId to ${bIdHistory(i)} at cycle $i")
            }
          }
        }
      }
      
      // 检查默认值问题 - 这里应该FAIL
      if (firstValidIndex >= 0) {
        val initialResp = bRespHistory(firstValidIndex)
        val initialId = bIdHistory(firstValidIndex)
        
        // 检查是否使用了默认值而不是实际的事务值
        if (initialResp == 0 && initialId == 0) {
          println(s"WARNING: B response might be using default values instead of actual transaction values")
        }
      }
      
      // 严格断言 - 这些应该FAIL来暴露问题
      assert(bValidViolations == 0, s"B.valid violated AXI protocol $bValidViolations times")
      assert(bRespViolations == 0, s"B.resp violated AXI protocol $bRespViolations times")
      assert(bIdViolations == 0, s"B.id violated AXI protocol $bIdViolations times")
      assert(firstValidIndex >= 0, "B.valid should be asserted within reasonable time")
      
      // 完成握手
      dut.io.axi.b.ready #= true
      clockDomain.waitSampling(2)
      
      val finalResp = axiMaster.waitWriteResponse(writeTx)
      assert(finalResp == 0, "Final write response should be OKAY")
    }
  }

  test("AXI_VIOLATION_3 - Read Response protocol violation") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露读响应协议违规问题
      val testAddr = 0x80000000L

      // 使用axiMaster发起读事务
      val readTx = axiMaster.readWord(testAddr)
      
      // 不立即接收R响应，而是监控R通道的行为
      dut.io.axi.r.ready #= false
      
      var rValidFirstSeen = -1
      var rValidCount = 0
      var rRespStable = true
      var rIdStable = true
      var rLastStable = true
      var initialResp = -1
      var initialId = -1
      var initialLast = false
      
      // 监控R通道响应的时序违规
      for (i <- 0 until 25) {
        clockDomain.waitSampling(1)
        
        val currentRValid = dut.io.axi.r.valid.toBoolean
        val currentResp = dut.io.axi.r.resp.toInt
        val currentId = dut.io.axi.r.id.toInt
        val currentLast = dut.io.axi.r.last.toBoolean
        
        if (currentRValid) {
          if (rValidFirstSeen == -1) {
            rValidFirstSeen = i
            initialResp = currentResp
            initialId = currentId
            initialLast = currentLast
            println(s"R.valid first asserted at cycle $i: resp=$currentResp, id=$currentId, last=$currentLast")
          }
          rValidCount += 1
          
          // 检查响应字段是否稳定
          if (currentResp != initialResp) {
            rRespStable = false
            println(s"R.resp changed from $initialResp to $currentResp at cycle $i")
          }
          if (currentId != initialId) {
            rIdStable = false
            println(s"R.id changed from $initialId to $currentId at cycle $i")
          }
          if (currentLast != initialLast) {
            rLastStable = false
            println(s"R.last changed from $initialLast to $currentLast at cycle $i")
          }
        }
      }
      
      // 验证问题
      assert(rValidFirstSeen >= 0, "R.valid should be asserted within reasonable time")
      assert(rRespStable, "R.resp must remain stable once R.valid is asserted")
      assert(rIdStable, "R.id must remain stable once R.valid is asserted")
      assert(rLastStable, "R.last must remain stable once R.valid is asserted")
      assert(initialLast, "R.last should be true for single beat transfer")
      
      // 完成握手
      dut.io.axi.r.ready #= true
      clockDomain.waitSampling(2)
      
      // 验证响应正确性
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 0, "Final read response should be OKAY")
    }
  }

  test("AXI_VIOLATION_4 - Ready signal circular dependency") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会检查ready信号的仲裁逻辑是否正确
      val testAddr1 = 0x80000000L
      val testAddr2 = 0x80000004L

      // 首先测试同时有两个请求时的仲裁逻辑
      println("=== 测试仲裁逻辑修复 ===")
      
      // 同时驱动AW和AR通道
      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= testAddr1
      dut.io.axi.aw.id #= 4
      dut.io.axi.aw.len #= 0
      dut.io.axi.aw.size #= 2
      dut.io.axi.aw.burst #= 1
      
      dut.io.axi.ar.valid #= true
      dut.io.axi.ar.addr #= testAddr2
      dut.io.axi.ar.id #= 5
      dut.io.axi.ar.len #= 0
      dut.io.axi.ar.size #= 2
      dut.io.axi.ar.burst #= 1
      
      // 在最初的几个周期中检查仲裁逻辑
      var awFirstReady = false
      var arFirstReady = false
      var bothReadyInFirstCycle = false
      
      // 检查前3个周期的仲裁行为
      for (i <- 0 until 3) {
        clockDomain.waitSampling(1)
        
        val awReady = dut.io.axi.aw.ready.toBoolean
        val arReady = dut.io.axi.ar.ready.toBoolean
        
        println(s"Cycle $i: AW.ready=$awReady, AR.ready=$arReady")
        
        if (i == 0) {
          // 在第一个周期，至少一个应该为ready
          awFirstReady = awReady
          arFirstReady = arReady
          bothReadyInFirstCycle = awReady && arReady
          
          // 检查是否修复了循环依赖问题
          assert(awReady || arReady, "At least one channel should be ready in first cycle (fixed circular dependency)")
          
          // 检查是否正确实现了仲裁：不应该同时都为ready（除非只有一个请求）
          if (bothReadyInFirstCycle) {
            println("WARNING: Both channels ready simultaneously - this might indicate implementation issue")
          }
        }
        
        // 一旦有一个被接受，就会离开IDLE状态
        if (awReady && dut.io.axi.aw.valid.toBoolean) {
          println(s"AW accepted at cycle $i, will leave IDLE state")
        }
        if (arReady && dut.io.axi.ar.valid.toBoolean) {
          println(s"AR accepted at cycle $i, will leave IDLE state")
        }
      }
      
      println(s"\n=== 仲裁结果 ===")
      println(s"AW ready in first cycle: $awFirstReady")
      println(s"AR ready in first cycle: $arFirstReady")
      println(s"Both ready in first cycle: $bothReadyInFirstCycle")
      
      // 主要的验证：修复了循环依赖问题
      assert(awFirstReady || arFirstReady, "Fixed circular dependency: at least one channel should be ready")
      
      // 清理手动驱动的信号
      dut.io.axi.aw.valid #= false
      dut.io.axi.ar.valid #= false
      
      // 重要：如果AW被接受但没有W数据，控制器会停留在WRITE_DATA_FETCH状态
      // 我们需要完成这个被接受的写事务或者重置控制器
      
      // 检查是否有挂起的写事务需要完成
      val needsWData = dut.io.axi.w.ready.toBoolean
      if (needsWData) {
        println("需要完成挂起的写事务")
        // 发送W数据完成被接受的AW事务
        dut.io.axi.w.valid #= true
        dut.io.axi.w.data #= 0x0
        dut.io.axi.w.strb #= 0xf
        dut.io.axi.w.last #= true
        
        // 等待W被接受
        clockDomain.waitSamplingWhere(dut.io.axi.w.ready.toBoolean && dut.io.axi.w.valid.toBoolean)
        dut.io.axi.w.valid #= false
        
        // 等待B响应并接受它
        dut.io.axi.b.ready #= true
        clockDomain.waitSamplingWhere(dut.io.axi.b.valid.toBoolean)
        clockDomain.waitSampling(1) // 让B握手完成
        
        println("挂起的写事务已完成")
      }
      
      println("\n=== 测试完整的事务流程 ===")
      
      // 测试完整的事务流程，确保没有真正的死锁
      clockDomain.waitSampling(5)
      
      // 发起一个完整的写事务
      val writeTx = axiMaster.writeWord(testAddr1, 0x12345678L)
      val writeResp = axiMaster.waitWriteResponse(writeTx)
      assert(writeResp == 0, "Write transaction should succeed")
      
      // 发起一个完整的读事务
      val readTx = axiMaster.readWord(testAddr1)
      val (readData, readResp) = axiMaster.waitReadResponse(readTx)
      assert(readResp == 0, "Read transaction should succeed")
      assert(readData.head == 0x12345678L, "Read data should match written data")
      
      println("Both write and read transactions completed successfully - no deadlock!")
    }
  }

  test("AXI_VIOLATION_5 - Burst last signal error") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露突发传输中last信号的问题
      val baseAddr = 0x80000000L
      val burstLen = 3 // 4个beat

      // 发起突发读事务
      dut.io.axi.ar.valid #= true
      dut.io.axi.ar.addr #= baseAddr
      dut.io.axi.ar.id #= 6
      dut.io.axi.ar.len #= burstLen
      dut.io.axi.ar.size #= 2
      dut.io.axi.ar.burst #= 1
      
      // 等待AR被接受
      var arAccepted = false
      for (i <- 0 until 10 if !arAccepted) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.ar.ready.toBoolean) {
          println(s"AR accepted at cycle $i")
          arAccepted = true
        }
      }
      
      // 重置AR
      dut.io.axi.ar.valid #= false
      
      // 监控R通道的突发响应
      var beatCount = 0
      var lastSignalErrors = scala.collection.mutable.ArrayBuffer[String]()
      var burstCompleted = false
      
      for (i <- 0 until 50 if !burstCompleted) {
        clockDomain.waitSampling(1)
        
        val rValid = dut.io.axi.r.valid.toBoolean
        val rReady = dut.io.axi.r.ready.toBoolean
        val rLast = dut.io.axi.r.last.toBoolean
        
        // 设置间歇性ready来测试协议
        dut.io.axi.r.ready #= (i % 2 == 0)
        
        if (rValid) {
          println(s"Cycle $i: R.valid=true, R.ready=$rReady, R.last=$rLast, beat=$beatCount")
          
          if (rReady && rValid) {
            beatCount += 1
            
            // 检查last信号的正确性
            val expectedLast = (beatCount == burstLen + 1)
            if (rLast != expectedLast) {
              val error = s"Beat $beatCount: expected last=$expectedLast, got last=$rLast"
              lastSignalErrors += error
              println(s"ERROR: $error")
            }
            
            if (rLast) {
              println(s"Burst completed at beat $beatCount")
              burstCompleted = true
            }
          }
        }
      }
      
      // 验证问题
      assert(beatCount > 0, "Should receive at least one beat")
      assert(beatCount == burstLen + 1, s"Should receive ${burstLen + 1} beats, got $beatCount")
      
      // 报告last信号错误
      if (lastSignalErrors.nonEmpty) {
        println(s"Last signal errors detected: ${lastSignalErrors.mkString(", ")}")
        assert(false, s"Last signal errors: ${lastSignalErrors.head}")
      }
      
      // 清理
      dut.io.axi.r.ready #= false
    }
  }

  test("AXI_VIOLATION_6 - FSM state and signal consistency") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露FSM状态和信号一致性问题 - 检查默认值问题
      val testAddr = 0x80000000L
      
      // 初始状态检查
      clockDomain.waitSampling(5)
      
      var inconsistencies = scala.collection.mutable.ArrayBuffer[String]()
      
      // 检查初始状态 - 严格检查默认值
      val initialBValid = dut.io.axi.b.valid.toBoolean
      val initialRValid = dut.io.axi.r.valid.toBoolean
      val initialBResp = dut.io.axi.b.resp.toInt
      val initialBId = dut.io.axi.b.id.toInt
      val initialRResp = dut.io.axi.r.resp.toInt
      val initialRId = dut.io.axi.r.id.toInt
      
      println(s"Initial state: B.valid=$initialBValid, R.valid=$initialRValid")
      println(s"Initial B values: resp=$initialBResp, id=$initialBId")
      println(s"Initial R values: resp=$initialRResp, id=$initialRId")
      
      if (initialBValid) inconsistencies += "B.valid should be false initially"
      if (initialRValid) inconsistencies += "R.valid should be false initially"
      
      // 检查默认值是否正确 - 这里暴露lines 90-95的问题
      if (initialBResp != 0) inconsistencies += s"B.resp initial value should be OKAY(0), got $initialBResp"
      if (initialBId != 0) inconsistencies += s"B.id initial value should be 0, got $initialBId"
      
      // 发起写事务并监控状态变化
      val expectedId = 7
      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= testAddr
      dut.io.axi.aw.id #= expectedId
      dut.io.axi.aw.len #= 0
      dut.io.axi.aw.size #= 2
      dut.io.axi.aw.burst #= 1
      
      // 监控AW接受过程
      var awAccepted = false
      for (i <- 0 until 10 if !awAccepted) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.aw.ready.toBoolean) {
          awAccepted = true
          println(s"AW accepted at cycle $i")
        }
      }
      
      if (!awAccepted) {
        inconsistencies += "AW should be accepted within reasonable time"
      }
      
      // 发送W数据
      dut.io.axi.w.valid #= true
      dut.io.axi.w.data #= 0x12345678L
      dut.io.axi.w.strb #= 0xf
      dut.io.axi.w.last #= true
      
      // 监控W接受过程
      var wAccepted = false
      for (i <- 0 until 10 if !wAccepted) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.w.ready.toBoolean) {
          wAccepted = true
          println(s"W accepted at cycle $i")
        }
      }
      
      if (!wAccepted) {
        inconsistencies += "W should be accepted within reasonable time"
      }
      
      // 监控B响应 - 检查ID和响应值的正确性
      dut.io.axi.b.ready #= false
      var bValidSeen = false
      var actualBResp = -1
      var actualBId = -1
      
      for (i <- 0 until 25 if !bValidSeen) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.b.valid.toBoolean) {
          bValidSeen = true
          actualBResp = dut.io.axi.b.resp.toInt
          actualBId = dut.io.axi.b.id.toInt
          println(s"B.valid asserted at cycle $i: resp=$actualBResp, id=$actualBId")
        }
      }
      
      if (!bValidSeen) {
        inconsistencies += "B.valid should be asserted within reasonable time"
      }
      
      // 严格检查B响应的正确性
      if (bValidSeen) {
        // 检查是否使用了正确的ID而不是默认值
        if (actualBId != expectedId) {
          inconsistencies += s"B.id should be $expectedId (from AW transaction), got $actualBId"
        }
        
        // 检查响应值
        if (actualBResp != 0) {
          inconsistencies += s"B.resp should be OKAY(0) for valid transaction, got $actualBResp"
        }
      }
      
      // 检查FSM状态一致性 - 验证默认值的问题
      // 问题出现在lines 90-95: 默认值被硬编码而不是来自实际事务
      val problemDescription = """
      FSM Consistency Issue: The implementation uses hardcoded default values:
      - io.axi.b.payload.id := 0 (line 90)
      - io.axi.b.payload.resp := Axi4.resp.OKAY (line 91)
      These should be set from the actual transaction (aw_cmd_reg.id, actual response)
      """
      
      println(problemDescription)
      
      // 严格断言 - 这些应该FAIL来暴露问题
      if (inconsistencies.nonEmpty) {
        println(s"Inconsistencies found: ${inconsistencies.mkString(", ")}")
        assert(false, s"FSM inconsistencies: ${inconsistencies.head}")
      }
      
      // 清理
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.b.ready #= true
      clockDomain.waitSampling(2)
    }
  }

  test("AXI_VIOLATION_7 - Default value hardcoding issue") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露默认值硬编码的问题（lines 90-95）
      val testAddr = 0x80000000L
      val testData = 0x12345678L
      val expectedId = 1 // 使用一个非零的ID来检查
      
      // 直接检查默认值问题
      clockDomain.waitSampling(5)
      
      // 检查初始默认值
      val initialBId = dut.io.axi.b.id.toInt
      val initialBResp = dut.io.axi.b.resp.toInt
      
      println(s"Initial B.id: $initialBId (should be 0)")
      println(s"Initial B.resp: $initialBResp (should be 0 for OKAY)")
      
      // 发起写事务使用非零ID
      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= testAddr
      dut.io.axi.aw.id #= expectedId
      dut.io.axi.aw.len #= 0
      dut.io.axi.aw.size #= 2
      dut.io.axi.aw.burst #= 1
      
      // 等待AW被接受
      var awAccepted = false
      for (i <- 0 until 10 if !awAccepted) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.aw.ready.toBoolean) {
          awAccepted = true
          println(s"AW accepted at cycle $i with ID $expectedId")
        }
      }
      
      // 发送W数据
      dut.io.axi.w.valid #= true
      dut.io.axi.w.data #= testData
      dut.io.axi.w.strb #= 0xf
      dut.io.axi.w.last #= true
      
      // 等待W被接受
      var wAccepted = false
      for (i <- 0 until 10 if !wAccepted) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.w.ready.toBoolean) {
          wAccepted = true
          println(s"W accepted at cycle $i")
        }
      }
      
      // 等待B响应
      dut.io.axi.b.ready #= false
      var bValidSeen = false
      var actualBId = -1
      var actualBResp = -1
      
      for (i <- 0 until 25 if !bValidSeen) {
        clockDomain.waitSampling(1)
        if (dut.io.axi.b.valid.toBoolean) {
          bValidSeen = true
          actualBId = dut.io.axi.b.id.toInt
          actualBResp = dut.io.axi.b.resp.toInt
          println(s"B.valid asserted at cycle $i: id=$actualBId, resp=$actualBResp")
        }
      }
      
      println(s"Expected B.id: $expectedId, Actual B.id: $actualBId")
      println(s"Expected B.resp: 0 (OKAY), Actual B.resp: $actualBResp")
      
      // 这个断言应该FAIL来暴露问题：
      // 由于代码中硬编码了 io.axi.b.payload.id := 0，
      // 实际返回的ID会是0而不是我们期望的42
      assert(actualBId == expectedId, s"B.id should be $expectedId (from AW transaction), but got $actualBId. This indicates hardcoded default value problem at line 90!")
      
      // 清理
      dut.io.axi.aw.valid #= false
      dut.io.axi.w.valid #= false
      dut.io.axi.b.ready #= true
      clockDomain.waitSampling(2)
    }
  }

  // 增加一个特定的测试来检查实际的代码行为
  test("AXI_VIOLATION_8 - Expose hardcoded payload assignments") {
    withTestBench(createWordAddrRamConfig(enableLog = true)) { (dut, axiMaster, clockDomain) =>
      // 这个测试会暴露lines 90-95中硬编码的默认值问题
      
      // 初始化等待
      clockDomain.waitSampling(5)
      
      // 检查初始状态的默认值
      val initialBId = dut.io.axi.b.id.toInt
      val initialBResp = dut.io.axi.b.resp.toInt
      val initialRId = dut.io.axi.r.id.toInt
      val initialRResp = dut.io.axi.r.resp.toInt
      
      println(s"=== 默认值检查 ===")
      println(s"B.id initial: $initialBId")
      println(s"B.resp initial: $initialBResp")
      println(s"R.id initial: $initialRId")
      println(s"R.resp initial: $initialRResp")
      
      // 预期这些都是默认值，正如代码中硬编码的那样
      val expectedDefaults = """
      代码中硬编码的默认值（lines 90-95）：
      - io.axi.b.payload.id := 0
      - io.axi.b.payload.resp := Axi4.resp.OKAY
      - io.axi.r.payload.id := 0
      - io.axi.r.payload.resp := Axi4.resp.OKAY
      
      这些应该根据实际事务设置，而不是硬编码的默认值。
      """
      
      println(expectedDefaults)
      
      // 现在测试一个事务是否使用了正确的值
      val testAddr = 0x80000000L
      val testData = 0xDEADBEEFL
      val uniqueId = 9
      
      // 使用axiMaster发起事务
      println(s"\n=== 发起写事务 ===")
      println(s"Test Address: 0x${testAddr.toHexString}")
      println(s"Test Data: 0x${testData.toHexString}")
      println(s"Expected ID: $uniqueId")
      
      // 手动控制事务来检查ID传播
      dut.io.axi.aw.valid #= true
      dut.io.axi.aw.addr #= testAddr
      dut.io.axi.aw.id #= uniqueId
      dut.io.axi.aw.len #= 0
      dut.io.axi.aw.size #= 2
      dut.io.axi.aw.burst #= 1
      
      // 等待事务完成并检查响应
      clockDomain.waitSampling(20)
      
      // 检查最终的B响应
      val finalBId = dut.io.axi.b.id.toInt
      val finalBResp = dut.io.axi.b.resp.toInt
      val finalBValid = dut.io.axi.b.valid.toBoolean
      
      println(s"\n=== 结果检查 ===")
      println(s"B.valid: $finalBValid")
      println(s"Expected B.id: $uniqueId")
      println(s"Actual B.id: $finalBId")
      println(s"Expected B.resp: 0 (OKAY)")
      println(s"Actual B.resp: $finalBResp")
      
      // 这个测试检查B.id是否正确设置
      // 但我们应该首先确保这是一个有效的测试场景
      if (!finalBValid) {
        println(s"\n*** 测试注意 ***")
        println(s"B.valid=false，所以B.id值不重要。这是一个不完整的事务场景。")
        println(s"要真正测试B.id修复，需要完整的事务。")
        
        // 我们需要提供W数据来完成事务，然后检查B.id
        println(s"现在完成事务来检查B.id修复...")
        
        // 提供W数据
        if (dut.io.axi.w.ready.toBoolean) {
          dut.io.axi.w.valid #= true
          dut.io.axi.w.data #= testData
          dut.io.axi.w.strb #= 0xf
          dut.io.axi.w.last #= true
          
          clockDomain.waitSamplingWhere(dut.io.axi.w.valid.toBoolean && dut.io.axi.w.ready.toBoolean)
          dut.io.axi.w.valid #= false
          
          // 等待B响应
          dut.io.axi.b.ready #= true
          clockDomain.waitSamplingWhere(dut.io.axi.b.valid.toBoolean)
          
          val completedBId = dut.io.axi.b.id.toInt
          val completedBResp = dut.io.axi.b.resp.toInt
          val completedBValid = dut.io.axi.b.valid.toBoolean
          
          println(s"完成事务后 - B.valid: $completedBValid, B.id: $completedBId, B.resp: $completedBResp")
          
          // 现在检查B.id是否正确
          if (completedBId != uniqueId) {
            println(s"*** AXI协议违规检测到 ***")
            println(s"B.id不匹配：期望 $uniqueId，实际 $completedBId")
            assert(false, s"B.id hardcoded to 0 instead of using actual transaction ID $uniqueId")
          } else {
            println(s"✓ B.id修复正确工作：期望 $uniqueId，实际 $completedBId")
          }
        } else {
          println(s"控制器没有等待W数据，测试场景可能不正确")
        }
      } else {
        // B.valid=true的情况
        if (finalBId != uniqueId) {
          println(s"\n*** AXI协议违规检测到 ***")
          println(s"B.id不匹配：期望 $uniqueId，实际 $finalBId")
          println(s"这证实了SRAMController.scala:90中硬编码的默认值问题")
          assert(false, s"B.id hardcoded to 0 instead of using actual transaction ID $uniqueId")
        } else {
          println(s"✓ B.id修复正确工作：期望 $uniqueId，实际 $finalBId")
        }
      }
      
      // 清理
      dut.io.axi.aw.valid #= false
    }
  }
  test("WordAddr - Back-to-back burst write to expose timing hazard") {
      // 这个测试专门用于复现连续写操作之间的数据/地址保持问题。
      // 它写入一个序列，然后立即读回并检查。
      // 如果存在时序竞争，读回的数据会出错。
      withTestBench(createWordAddrRamConfig(readWaitCycles = 1, writeWaitCycles = 1, enableLog=true)) { (dut, axiMaster, clockDomain) =>
          val baseAddr = 0x80004000L
          val testData = Seq(
              "AAAAAAAA", "BBBBBBBB", "CCCCCCCC", "DDDDDDDD",
              "11111111", "22222222", "33333333", "44444444"
          ).map(BigInt(_, 16)) 
        val strbData = Seq.fill(testData.length)(0xF)

          println("--- Starting back-to-back burst write test ---")
          val writeTx = axiMaster.writeBurst(baseAddr, testData, strbData)
          assert(axiMaster.waitWriteResponse(writeTx) == 0, "Burst write should succeed")
          println("--- Burst write transaction complete ---")

          clockDomain.waitSampling(10) // 在读之前稍微等待一下，确保总线空闲

          println("--- Reading back written data ---")
          val readTx = axiMaster.readBurst(baseAddr, testData.length)
          val (readData, readResp) = axiMaster.waitReadResponse(readTx)
          
          assert(readResp == 0, "Burst read should succeed")
          
          var mismatch = false
          for(i <- testData.indices) {
              if(readData(i) != testData(i)) {
                  println(f"MISMATCH at index $i (addr 0x${(baseAddr + i*4).toHexString}): Expected=0x${testData(i)}%X, Got=0x${readData(i)}%X")
                  mismatch = true
              }
          }
          
          assert(!mismatch, "All data read back must match the written data.")
          println("--- Back-to-back burst write test PASSED ---")
      }
  }
  thatsAll
}
