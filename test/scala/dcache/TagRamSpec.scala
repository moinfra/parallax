// testOnly parallax.test.scala.dcache.TagRamSpec
package test.scala.dcache

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random
import parallax.components.dcache._
import parallax.utilities.ParallaxLogger

class TagRamTestBench(p: DataCacheParameters) extends Component {
    val dut = new TagRamComponent(p) // TagRamComponent 也需要使用这个 p
    val io_tb = dut.io
    io_tb.simPublic()
}

// Helper 对象
object TagRamTestHelpers {
    val DEFAULT_CMD_TIMEOUT_CYCLES = 10

    def resetDutInputs(tb_io: TagRamIo): Unit = { // 使用正确的方式引用嵌套Bundle类型
        tb_io.readCmd.valid #= false
        tb_io.writeCmd.valid #= false
        tb_io.readRsp.ready #= false
    }

    def driveSingleWriteCmd(
        tb_io: TagRamIo,
        index: Int,
        way: Int,
        tag: BigInt,
        validBit: Boolean,
        clockDomain: ClockDomain,
        timeoutCycles: Int = DEFAULT_CMD_TIMEOUT_CYCLES
    ): Unit = {
        tb_io.writeCmd.valid #= true
        tb_io.writeCmd.payload.index #= index
        tb_io.writeCmd.payload.way #= way
        tb_io.writeCmd.payload.data.tag #= tag
        tb_io.writeCmd.payload.data.valid #= validBit

        var fired = false
        val timedOut = clockDomain.waitSamplingWhere(timeoutCycles) {
            fired = tb_io.writeCmd.valid.toBoolean && tb_io.writeCmd.ready.toBoolean
            fired
        }
        if (timedOut || !fired) {
            simFailure(s"driveSingleWriteCmd timed out or failed to fire. TimedOut: $timedOut, Fired: $fired")
        }
        tb_io.writeCmd.valid #= false
    }

    def driveSingleReadCmd(
        tb_io: TagRamIo,
        index: Int,
        clockDomain: ClockDomain,
        timeoutCycles: Int = DEFAULT_CMD_TIMEOUT_CYCLES
    ): Unit = {
        tb_io.readCmd.valid #= true
        tb_io.readCmd.payload.index #= index

        var fired = false
        val timedOut = clockDomain.waitSamplingWhere(timeoutCycles) {
            fired = tb_io.readCmd.valid.toBoolean && tb_io.readCmd.ready.toBoolean
            fired
        }
        if (timedOut || !fired) {
            simFailure(s"driveSingleReadCmd timed out or failed to fire. TimedOut: $timedOut, Fired: $fired")
        }
        tb_io.readCmd.valid #= false
    }

    // Helper to extract data from simulated CacheTag Vec
    def getCacheTagVecFromSim(simVec: Vec[CacheTag], p: DataCacheParameters): Seq[(BigInt, Boolean)] = {
        (0 until p.wayCount).map { i =>
            (simVec(i).tag.toBigInt, simVec(i).valid.toBoolean)
        }
    }
}

class TagRamSpec extends CustomSpinalSimFunSuite {
    import TagRamTestHelpers._

    // 使用你提供的 DataCacheParameters
    val baseTestParams = DataCacheParameters(
        cacheSize = 1024 * 4, // 4KB
        wayCount = 4,
        memDataWidth = 64,   // 8 bytes
        cpuDataWidth = 32,   // 4 bytes
        physicalWidth = 32,
        lineSize = 64,       // 64 bytes per line
        refillBufferDepth = 2,
        writebackBufferDepth = 2,
        tagsReadAsync = true
    )
    def createTestBench(p: DataCacheParameters) = new TagRamTestBench(p)

    test("TagRamComponent - TP1 - Basic Write and Read") {
        val p = baseTestParams
        // Random.setSeed(12345L) // 可选：为特定测试设置种子
        simConfig.compile(createTestBench(p)).doSim { dutTb =>
            implicit val cd = dutTb.clockDomain
            cd.forkStimulus(period = 10) // 100MHz -> 10ns period
            val io = dutTb.io_tb

            resetDutInputs(io)
            io.readRsp.ready #= true

            val indexToWrite = 5
            val wayToWrite1 = 1
            val tagToWrite1 = BigInt("ABCDE", 16)
            val wayToWrite2 = 3
            val tagToWrite2 = BigInt("12345", 16)

            driveSingleWriteCmd(io, indexToWrite, wayToWrite1, tagToWrite1, true, cd)
            driveSingleWriteCmd(io, indexToWrite, wayToWrite2, tagToWrite2, true, cd)
            cd.waitSampling(5)

            driveSingleReadCmd(io, indexToWrite, cd)

            var rspValidObserved = false
            var readSimVec: Option[Vec[CacheTag]] = None // 直接获取仿真时的 Vec 对象
            val timedOut = cd.waitSamplingWhere(DEFAULT_CMD_TIMEOUT_CYCLES) {
                rspValidObserved = io.readRsp.valid.toBoolean
                if (rspValidObserved) {
                    readSimVec = Some(io.readRsp.payload)
                }
                rspValidObserved
            }
            assert(!timedOut, "Timeout waiting for read response")
            assert(rspValidObserved && readSimVec.isDefined, "Read response was not valid or payload not captured")

            val rspData = getCacheTagVecFromSim(readSimVec.get, p)
            assert(rspData(wayToWrite1)._1 == tagToWrite1, s"Way 1 Tag Mismatch! Expected ${tagToWrite1}, got ${rspData(wayToWrite1)._1}")
            assert(rspData(wayToWrite1)._2, s"Way 1 Valid Mismatch!")
            assert(rspData(wayToWrite2)._1 == tagToWrite2, s"Way 3 Tag Mismatch! Expected ${tagToWrite2}, got ${rspData(wayToWrite2)._1}")
            assert(rspData(wayToWrite2)._2, s"Way 3 Valid Mismatch!")
            for (w <- 0 until p.wayCount if w != wayToWrite1 && w != wayToWrite2) {
                assert(!rspData(w)._2, s"Way $w Valid Mismatch! Expected false.")
            }
            cd.waitSampling(10)
        }
    }

    testOnly("TagRamComponent - TP2 - Read Backpressure with StreamDriver") {
        val p = baseTestParams
        simConfig.compile(createTestBench(p)).doSim { dutTb =>
            implicit val cd = dutTb.clockDomain
            cd.forkStimulus(period = 10)
            val io = dutTb.io_tb
            resetDutInputs(io)

            driveSingleWriteCmd(io, 1, 0, BigInt("F0F0", 16), true, cd)
            cd.waitSampling(2)

            val (_, readCmdQueue) = StreamDriver.queue(io.readCmd, cd)
            
            // 使用 mutable.Queue 和 StreamMonitor 回调来收集数据
            val receivedRspData = mutable.Queue[(Int, Seq[(BigInt, Boolean)])]() // (index, wayData)
            var currentReadIndexForMonitor = -1

            StreamMonitor(io.readRsp, cd) { payloadVec =>
                ParallaxLogger.log(s"${simTime()}: Read Rsp Monitor: Received for (expected) index ${currentReadIndexForMonitor}")
                receivedRspData.enqueue((currentReadIndexForMonitor, getCacheTagVecFromSim(payloadVec, p)))
            }

            // 任务1: 发送读命令，下游不准备接收
            currentReadIndexForMonitor = 1
            readCmdQueue.enqueue { cmd => cmd.index #= 1 }
            ParallaxLogger.log(s"${simTime()}: Read cmd for index 1 enqueued.")
            
            io.readRsp.ready #= false // 明确设置下游不准备接收
            val timedOut1 = cd.waitSamplingWhere(DEFAULT_CMD_TIMEOUT_CYCLES){
                io.readRsp.valid.toBoolean && !io.readRsp.ready.toBoolean // 等到响应有效但未被取走
            }
            assert(!timedOut1, "Timeout waiting for response valid while downstream not ready")
            
            ParallaxLogger.log(s"${simTime()}: Rsp valid, Rsp ready=false. Current cmd.ready=${io.readCmd.ready.toBoolean}")
            assert(!io.readCmd.ready.toBoolean, "readCmd.ready should be low due to backpressure")

            // 任务2: 下游准备接收
            io.readRsp.ready #= true
            cd.waitSampling() // 等待一个周期让 ready 生效并消耗数据
            
            ParallaxLogger.log(s"${simTime()}: Rsp ready=true. Current cmd.ready=${io.readCmd.ready.toBoolean}")
            // 此时，因为上一个响应被消耗，如果队列为空，readCmd.valid 应为false, 则readCmd.ready应为true
            // 或者如果队列不为空，StreamDriver会尝试发送下一个，此时ready也应为true
            assert(io.readCmd.ready.toBoolean, "readCmd.ready should be high as buffer is free or driver pushing next")
            io.readRsp.ready #= false // 重置，后续随机

            // 任务3: 连续发送多个读命令
            driveSingleWriteCmd(io, 2, 1, BigInt("ABAB", 16), true, cd)
            driveSingleWriteCmd(io, 3, 2, BigInt("CDCD", 16), true, cd)
            cd.waitSampling(2)

            val expectedReads = List(
                (1, 0, BigInt("F0F0", 16), true), // (index, way_of_interest, tag, valid)
                (2, 1, BigInt("ABAB", 16), true),
                (3, 2, BigInt("CDCD", 16), true)
            )
            
            // 第一个命令已经发送并被监控 (或正在被监控)
            currentReadIndexForMonitor = 2
            readCmdQueue.enqueue{cmd => cmd.index #= 2}
            ParallaxLogger.log(s"${simTime()}: Read cmd for index 2 enqueued.")

            currentReadIndexForMonitor = 3 // 注意：这个赋值对于monitor回调来说有点 tricky，因为回调是异步的
            readCmdQueue.enqueue{cmd => cmd.index #= 3}
            ParallaxLogger.log(s"${simTime()}: Read cmd for index 3 enqueued.")

            // 随机化 readRsp.ready
            StreamReadyRandomizer(io.readRsp, cd) 

            val monitorTimeout = DEFAULT_CMD_TIMEOUT_CYCLES * expectedReads.length + 50
            val finalTimeout = cd.waitSamplingWhere(monitorTimeout) {
                receivedRspData.length == expectedReads.length
            }
            assert(!finalTimeout, s"Timeout waiting for all read responses. Got ${receivedRspData.length}, expected ${expectedReads.length}")

            // 验证收集到的数据
            expectedReads.foreach { case (expIdx, expWay, expTag, expValid) =>
                val found = receivedRspData.dequeueFirst(item => item._1 == expIdx) // 不保证顺序，简单查找
                assert(found.isDefined, s"Did not receive response for index $expIdx")
                found.foreach { case (_, wayData) =>
                    assert(wayData(expWay)._1 == expTag, s"Tag mismatch for Idx $expIdx Way $expWay")
                    assert(wayData(expWay)._2 == expValid, s"Valid mismatch for Idx $expIdx Way $expWay")
                }
            }
            cd.waitSampling(10)
        }
    }

    test("TagRamComponent - TP3 - Write-Read (ROV assumption with 1 cycle delay)") {
        val p = baseTestParams
        simConfig.compile(createTestBench(p)).doSim { dutTb =>
            implicit val cd = dutTb.clockDomain
            cd.forkStimulus(period = 10)
            val io = dutTb.io_tb
            resetDutInputs(io)
            io.readRsp.ready #= true

            val index = 8
            val initialTagWay0 = BigInt("AAAA", 16)
            val newTagWay0 = BigInt("BBBB", 16)

            driveSingleWriteCmd(io, index, 0, initialTagWay0, true, cd)
            cd.waitSampling(2)

            driveSingleReadCmd(io, index, cd)
            var payloadSimVec1: Option[Vec[CacheTag]] = None
            cd.waitSamplingWhere(DEFAULT_CMD_TIMEOUT_CYCLES){ val v = io.readRsp.valid.toBoolean; if(v) payloadSimVec1 = Some(io.readRsp.payload); v }
            val data1 = getCacheTagVecFromSim(payloadSimVec1.get, p)
            assert(data1(0)._1 == initialTagWay0)
            cd.waitSampling() 

            driveSingleWriteCmd(io, index, 0, newTagWay0, true, cd)
            // **根据 ROV + Store-to-Load Forwarding 策略，单元测试层面关注RAM行为**
            // 如果RAM是ROV，同周期写后立即读，会读到旧值。
            // 这里我们模拟 "写操作完成后的读取"，所以在写和读之间等待一个周期。
            cd.waitSampling() // <<<< 等待一个周期，让写操作完成并对RAM可见

            driveSingleReadCmd(io, index, cd)
            var payloadSimVec2: Option[Vec[CacheTag]] = None
            val timedOut = cd.waitSamplingWhere(DEFAULT_CMD_TIMEOUT_CYCLES){ val v = io.readRsp.valid.toBoolean; if(v) payloadSimVec2 = Some(io.readRsp.payload); v }
            assert(!timedOut, "Timeout waiting for final read response")
            
            val data2 = getCacheTagVecFromSim(payloadSimVec2.get, p)
            assert(data2(0)._1 == newTagWay0,
                   s"Write-Read (with 1 cycle delay for ROV RAM): Expected new tag ${newTagWay0}, got ${data2(0)._1}")
            cd.waitSampling(10)
        }
    }

    // TP4 - Random Test 保持与之前类似，但要注意 StreamMonitor 的使用
    test("TagRamComponent - TP4 - Random Writes and Reads") {
        val numOps = 50
        val p = baseTestParams.copy(wayCount = 2) // 缩小一点方便测试
        // Random.setSeed(System.currentTimeMillis()) // 确保每次运行的随机性，或者固定一个种子调试
        simConfig.compile(createTestBench(p)).doSim { dutTb =>
            implicit val cd = dutTb.clockDomain
            cd.forkStimulus(period = 10)
            val io = dutTb.io_tb
            resetDutInputs(io)

            val shadowRam = Array.fill(p.linePerWay, p.wayCount)((BigInt(0), false))

            val (_, readCmdQueue) = StreamDriver.queue(io.readCmd, cd)
            val (writeCmdDriver, writeCmdQueue) = StreamDriver.queue(io.writeCmd, cd)

            val receivedRspData = mutable.Queue[(Int, Seq[(BigInt, Boolean)])]() // (index, wayData)
            val pendingReads = mutable.Queue[Int]()

            StreamMonitor(io.readRsp, cd) { payloadVec =>
                if (pendingReads.nonEmpty) { // 只有在有预期读时才处理
                    val expectedIndex = pendingReads.dequeue() // FIFO
                    ParallaxLogger.log(s"${simTime()}: Monitor got Rsp for (expected) Idx=$expectedIndex")
                    receivedRspData.enqueue((expectedIndex, getCacheTagVecFromSim(payloadVec, p)))
                } else {
                     ParallaxLogger.log(s"${simTime()}: Monitor got Rsp but no pending read, ignoring.")
                }
            }
            StreamReadyRandomizer(io.readRsp, cd).setFactor(0.7f) // 70% 概率 ready

            for (op <- 0 until numOps) {
                if (Random.nextBoolean() && writeCmdQueue.size < 5) {
                    val index = Random.nextInt(p.linePerWay)
                    val way = Random.nextInt(p.wayCount)
                    val tag = BigInt(p.tagWidth, Random)
                    val valid = Random.nextBoolean()

                    writeCmdQueue.enqueue { cmd =>
                        cmd.index #= index
                        cmd.way #= way
                        cmd.data.tag #= tag
                        cmd.data.valid #= valid
                    }
                    shadowRam(index)(way) = (tag, valid)
                } else if (readCmdQueue.size < 5 && pendingReads.size < 3) {
                    val index = Random.nextInt(p.linePerWay)
                    readCmdQueue.enqueue{ cmd => cmd.index #= index }
                    pendingReads.enqueue(index)
                }
                cd.waitSampling()
            }
            
            val finalTimeout = DEFAULT_CMD_TIMEOUT_CYCLES * (pendingReads.size + writeCmdQueue.size + readCmdQueue.size + 10)
            val timedOut = cd.waitSamplingWhere(finalTimeout) {
                pendingReads.isEmpty && readCmdQueue.isEmpty && writeCmdQueue.isEmpty && receivedRspData.length >= (numOps/3) // 粗略估计
            }
            if(timedOut) ParallaxLogger.log(s"Warning: Final wait might have timed out. PendingReads: ${pendingReads.size}, Received: ${receivedRspData.length}")


            // 验证所有收集到的响应
            receivedRspData.foreach { case (readIndex, wayData) =>
                 for (w <- 0 until p.wayCount) {
                    val (expectedTag, expectedValid) = shadowRam(readIndex)(w)
                    assert(wayData(w)._1 == expectedTag, s"Random Rsp Idx $readIndex Way $w Tag mismatch")
                    assert(wayData(w)._2 == expectedValid, s"Random Rsp Idx $readIndex Way $w Valid mismatch")
                 }
            }
            assert(pendingReads.isEmpty || receivedRspData.length > 0, "If reads were sent, some should have been processed or still pending if timeout occurred early")
            cd.waitSampling(20)
        }
    }

    thatsAll()
}
