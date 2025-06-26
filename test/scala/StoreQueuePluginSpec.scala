// // run testOnly test.scala.StoreQueuePluginSpec
// // filename: test/scala/StoreQueuePluginSpec.scala
// package test.scala

// import spinal.core._
// import spinal.core.sim._
// import spinal.lib._
// import parallax.common._
// import parallax.components.lsu._
// import parallax.utilities._
// import org.scalatest.funsuite.AnyFunSuite
// import scala.collection.mutable
// import parallax.components.rob.ROBFlushPayload
// import spinal.lib.sim.StreamMonitor
// import parallax.components.dcache2._
// import spinal.lib.bus.amba4.axi.Axi4
// import parallax.components.memory.ExtSRAMConfig
// import parallax.components.memory.ExtSRAMController
// import spinal.lib.bus.amba4.axi.Axi4Config
// import parallax.components.memory.SimulatedSRAM
// import spinal.lib.sim.StreamDriver

// case class StoreQueueTestIO(
//     pipelineConfig: PipelineConfig,
//     lsuConfig: LsuConfig,
//     dCacheConfig: DataCachePluginConfig
// ) extends Bundle
//     with IMasterSlave {

//   // --- 输入到 DUT 生态系统的端口 (由测试用例驱动) ---
//   val allocatePort = Stream(StoreQueueEntry(lsuConfig))
//   val statusUpdatePort = Flow(SqStatusUpdate(lsuConfig))
//   val releasePorts = Vec(Flow(UInt(lsuConfig.robPtrWidth)), lsuConfig.commitWidth)
//   val releaseMask = Vec(Bool(), lsuConfig.commitWidth)
//   val flushPort = Flow(ROBFlushPayload(pipelineConfig.robPtrWidth))
//   val prfWrite = PrfWritePort(lsuConfig.physGprIdxWidth, lsuConfig.dataWidth)
//   val prfRead = PrfReadPort(lsuConfig.physGprIdxWidth, lsuConfig.dataWidth)
//   val dCacheFlushPort = DataStorePort(
//     postTranslationWidth = pipelineConfig.xlen,
//     dataWidth = pipelineConfig.xlen,
//     refillCount = dCacheConfig.refillCount,
//     transactionIdWidth = dCacheConfig.transactionIdWidth
//   )

//   override def asMaster(): Unit = {
//     // toAI 从用户的视角，StoreQueueTestIO 是**被**驱动的存在，下面是对的，别瞎改！
//     slave(allocatePort, flushPort, prfWrite, prfRead, dCacheFlushPort)
//     in(releasePorts, releaseMask)
//     in(statusUpdatePort)
//   }
// }

// class StoreQueueTestPlugin(private val io: StoreQueueTestIO)
//     extends Plugin
//     with CommitServiceForLsu
//     with FlushServiceForLsu {

//   val setup = create early new Area {
//     // 获取所有需要的服务实例
//     val sqPlugin = getService[StoreQueuePlugin]
//     val aguService = getService[AguService]
//     val prfService = getService[PhysicalRegFileService]
//     val dcacheService = getService[DataCacheService]

//     // --- 在 setup Area 内完成所有连接 ---

//     // 1. 将 SQ 提供的服务端口连接到 IO
//     io.allocatePort >> sqPlugin.getAllocatePort()
//     io.statusUpdatePort >> sqPlugin.getStatusUpdatePort()

//     val prfWritePort = prfService.newWritePort()
//     prfWritePort <> io.prfWrite // to AI: 这个连线是正确的，对于连续连接，Spinal 允许 master(sim) <> slave()

//     // 3. 将真实插件的监控端口连接到 IO
//     val prfReadPort = prfService.newReadPort()
//     io.prfRead <> prfReadPort

//     val dCacheFlushPort = dcacheService.getTestFlushPort()
//     io.dCacheFlushPort <> dCacheFlushPort

//     // 4. 象征性地获取并端接未使用的端口
//     val unusedLoadPort = dcacheService.newLoadPort(priority = 0)
//     unusedLoadPort.cmd.valid := False
//     unusedLoadPort.cmd.payload.assignDontCare()
//     unusedLoadPort.translated.assignDontCare()
//     unusedLoadPort.cancels := 0
//   }

//   // --- Mock 服务实现 ---
//   override def getReleasePorts(): Vec[Flow[UInt]] = io.releasePorts
//   override def getReleaseMask(): Vec[Bool] = io.releaseMask
//   override def getLsuFlushPort(): Flow[ROBFlushPayload] = io.flushPort
// }

// class PrfDriverPlugin(prfWritePort: PrfWritePort) extends Plugin {
//   val setup = create early new Area {
//     val prf = getService[PhysicalRegFileService]
//     prf.newWritePort() <> prfWritePort
//   }
// }

// class StoreQueueTestBench extends Component {
//   // --- 1. 定义基础配置 ---
//   private val pipelineConfig = PipelineConfig(
//     xlen = 32,
//     fetchWidth = 2,
//     renameWidth = 2,
//     dispatchWidth = 2,
//     commitWidth = 2,
//     physGprCount = 64,
//     robDepth = 32
//   )

// // --- 2. 使用正确的 DataCachePluginConfig ---
//   private val dcacheConfig = DataCachePluginConfig(
//     pipelineConfig = pipelineConfig, // 传入 pipelineConfig
//     memDataWidth = pipelineConfig.xlen, // 32-bit AXI 总线宽度
//     cacheSize = 1024, // 1KB D-Cache
//     wayCount = 4, // 4-way set associative
//     refillCount = 2, // 2个并行的缓存回填槽。这个值的对数决定了 DCache 访问 axi 总线的 id width
//     writebackCount = 2, // 2个并行的写回/victim缓存槽
//     lineSize = 64, // 64-byte cache line
//     transactionIdWidth = 8
//     // 其他参数使用默认值
//   )

// // --- 3. 从基础配置派生 LSU 配置 ---
//   val lsuConfig = LsuConfig(
//     lqDepth = 8,
//     sqDepth = 8,
//     robPtrWidth = log2Up(pipelineConfig.robDepth) + 1 bits,
//     pcWidth = pipelineConfig.pcWidth,
//     dataWidth = pipelineConfig.dataWidth,
//     physGprIdxWidth = pipelineConfig.physGprIdxWidth,
//     exceptionCodeWidth = 8 bits,
//     commitWidth = pipelineConfig.commitWidth,
//     dcacheRefillCount = dcacheConfig.refillCount // 从 dcacheConfig 获取
//   )

//   private val axiConfig = Axi4Config(
//     addressWidth = pipelineConfig.xlen,
//     dataWidth = pipelineConfig.xlen,
//     idWidth = pipelineConfig.transactionIdWidth,
//     useLock = false,
//     useCache = false,
//     useProt = true,
//     useQos = false,
//     useRegion = false,
//     useResp = true,
//     useStrb = true,
//     useBurst = true,
//     useLen = true,
//     useSize = true
//   )

//   // 确保配置间的一致性
//   assert(lsuConfig.dataWidth.value == pipelineConfig.dataWidth.value, "LSU-Pipeline dataWidth mismatch")

//   // --- 实例化所有需要的真实插件 ---
//   private val dataCacheParameters = DataCachePluginConfig.toDataCacheParameters(dcacheConfig)

//   val io = master(StoreQueueTestIO(pipelineConfig, lsuConfig, dcacheConfig).simPublic())

//   private val framework = new Framework(
//     Seq(
//       new StoreQueuePlugin(lsuConfig, pipelineConfig, dataCacheParameters, true),
//       new AguPlugin(lsuConfig, supportPcRel = true),
//       new PhysicalRegFilePlugin(pipelineConfig.physGprCount, lsuConfig.dataWidth),
//       new DataCachePlugin(dcacheConfig),
//       new StoreQueueTestPlugin(io),
//       new DataMembusTestPlugin(axiConfig),
//       new PrfDriverPlugin(io.prfWrite)
//     )
//   )

//   val sram = framework.getService[DBusService].asInstanceOf[DataMembusTestPlugin].getSram()
// }

// class DataMembusTestPlugin(axiConfig: Axi4Config) extends Plugin with DBusService {
//   val hw = create early new Area {
//     private val sramSize = BigInt("4000", 16) // 16 KiB
//     private val extSramCfg = ExtSRAMConfig(
//       addressWidth = 16,
//       dataWidth = 32,
//       virtualBaseAddress = BigInt("00000000", 16),
//       sizeBytes = sramSize,
//       readWaitCycles = 0,
//       enableLog = true
//     )
//     val sram = new SimulatedSRAM(extSramCfg)
//     val ctrl = new ExtSRAMController(axiConfig, extSramCfg)
//     ctrl.io.ram <> sram.io.ram
//     ctrl.io.simPublic()
//     sram.io.simPublic()
//   }

//   override def getBus(): Axi4 = hw.ctrl.io.axi
//   def getSram(): SimulatedSRAM = hw.sram

// }

// class StoreQueuePluginSpec extends CustomSpinalSimFunSuite {

//   // --- 测试数据结构和捕获类 ---
//   case class StoreQueueTestParams(
//       pc: BigInt,
//       robPtr: Int,
//       physDataSrc: Int,
//       accessSize: MemAccessSize.E,
//       aguBasePhysReg: Int,
//       aguImmediate: Int
//   )

//   // --- 辅助函数 ---
//   def preloadRegister(dut: StoreQueueTestBench, addr: Int, data: BigInt): Unit = {
//     dut.io.prfWrite.valid #= true
//     dut.io.prfWrite.address #= addr
//     dut.io.prfWrite.data #= data
//     dut.clockDomain.waitSampling()
//     dut.io.prfWrite.valid #= false
//   }

//   // --- 辅助函数 ---
//   // 使用新的 tb 端口预加载 PRF
//   def preloadGpr(dut: StoreQueueTestBench, addr: Int, data: BigInt): Unit = {
//     dut.io.prfWrite.valid #= true
//     dut.io.prfWrite.address #= addr
//     dut.io.prfWrite.data #= data
//     dut.clockDomain.waitSampling()
//     dut.io.prfWrite.valid #= false
//   }

//   // 新的辅助函数：使用 tb 端口预加载内存
//   def preloadMemory(sram: SimulatedSRAM, addr: BigInt, data: BigInt): Unit = {
//     sram.io.tb_writeEnable #= true
//     sram.io.tb_writeAddress #= addr
//     sram.io.tb_writeData #= data
//     sram.clockDomain.waitSampling()
//     sram.io.tb_writeEnable #= false

//     verifyMemory(sram, addr, data)
//     ParallaxLogger.log(s"preloadMemory: 0x$addr%X -> 0x$data%X")
//   }

//   // 新的辅助函数：使用 tb 端口读取并验证内存
//   def verifyMemory(sram: SimulatedSRAM, addr: BigInt, expectedData: BigInt): Unit = {
//     sram.io.tb_readEnable #= true
//     sram.io.tb_readAddress #= addr
//     sram.clockDomain.waitSampling(2) // 等待 readSync 完成
//     val memContent = sram.io.tb_readData.toBigInt
//     assert(
//       memContent == expectedData,
//       f"Memory verification failed at 0x$addr%X. Got 0x$memContent%X, Expected 0x$expectedData%X"
//     )
//     sram.io.tb_readEnable #= false
//   }

//   def initDutInputs(dut: StoreQueueTestBench): Unit = {
//     dut.io.allocatePort.valid #= false
//     dut.io.releasePorts.foreach(_.valid #= false)
//     dut.io.releaseMask.foreach(_ #= false)
//     dut.io.flushPort.valid #= false
//     dut.io.prfWrite.valid #= false
//     dut.io.dCacheFlushPort.cmd.valid #= false

//     dut.sram.io.tb_writeEnable #= false
//     dut.sram.io.tb_readEnable #= false
//   }

//   def flushDCache(dut: StoreQueueTestBench, addr: BigInt): Unit = {
//     var flushCycles = 0
//     val maxFlushCycles = 100 // 设置一个足够大的超时
//     var redo = true

//     // 使用一个循环，只要 D-Cache 要求 REDO，就继续发送 FLUSH 命令
//     while (redo && flushCycles < maxFlushCycles) {
//       ParallaxLogger.log(s"Sending FLUSH command, cycle ${flushCycles}")
//       // 发送冲刷命令
//       dut.io.dCacheFlushPort.cmd.valid #= true
//       dut.io.dCacheFlushPort.cmd.payload.flush #= true
//       dut.io.dCacheFlushPort.cmd.payload.address #= addr
//       dut.io.dCacheFlushPort.cmd.payload.mask #= 0
//       dut.io.dCacheFlushPort.cmd.payload.data #= 0
//       dut.io.dCacheFlushPort.cmd.payload.io #= false
//       dut.io.dCacheFlushPort.cmd.payload.flushFree #= false
//       dut.io.dCacheFlushPort.cmd.payload.prefetch #= false
//       dut.io.dCacheFlushPort.cmd.payload.id #= 88

//       // 等待命令被接收
//       dut.clockDomain.waitSamplingWhere(dut.io.dCacheFlushPort.cmd.ready.toBoolean)
//       dut.io.dCacheFlushPort.cmd.valid #= false

//       // 等待响应
//       dut.clockDomain.waitSamplingWhere(dut.io.dCacheFlushPort.rsp.valid.toBoolean)
//       assert(dut.io.dCacheFlushPort.rsp.payload.id.toBigInt == 88, "D-Cache flush response ID mismatch")
//       // 检查响应中的 redo 标志
//       redo = dut.io.dCacheFlushPort.rsp.payload.redo.toBoolean
//       if (redo) {
//         ParallaxLogger.log("D-Cache requested REDO, flushing again.")
//       } else {
//         ParallaxLogger.log("D-Cache flush sequence completed (REDO is false).")
//       }

//       flushCycles += 1
//       // 给写回留出一些时间
//       dut.clockDomain.waitSampling(5)
//     }

//     assert(!redo, s"D-Cache flush sequence timed out after ${maxFlushCycles} cycles.")

//     // 在所有冲刷操作完成后，再额外等待一段时间，确保所有AXI事务完成
//     ParallaxLogger.debug("Flush sequence finished. Waiting for AXI transactions to complete.")
//     // dut.clockDomain.waitSampling(50)
//   }

//   // -- TEST CASE 1: Complete Lifecycle (使用正确的 StreamDriver.queue) --
//   test("StoreQueue Complete Lifecycle") {
//     simConfig.compile(new StoreQueueTestBench()).doSim { dut =>
//       dut.clockDomain.forkStimulus(10)
//       dut.clockDomain.onActiveEdges {
//         println("pos edge.")
//       }
//       initDutInputs(dut)

//       // --- 设置阶段 ---
//       val sram = dut.sram

//       // 使用 StreamDriver.queue 创建驱动器和命令队列
//       val (allocDriver, allocQueue) = StreamDriver.queue(dut.io.allocatePort, dut.clockDomain)
//       // 可以给驱动器增加随机延迟
//       allocDriver.transactionDelay = () => simRandom.nextInt(5)

//       // --- 测试参数 ---
//       val baseAddr = BigInt("1000", 16)
//       val offset = BigInt("20", 16).toInt
//       val finalAddr = baseAddr + offset
//       val storeData = BigInt("deadbeef", 16)
//       val initialMemValue = BigInt("cafebabe", 16)
//       val testParams = StoreQueueTestParams(
//         pc = BigInt("80000000", 16),
//         robPtr = 5,
//         physDataSrc = 10,
//         accessSize = MemAccessSize.W,
//         aguBasePhysReg = 20,
//         aguImmediate = offset
//       )

//       ParallaxLogger.debug("1. 预加载")
//       preloadGpr(dut, addr = testParams.aguBasePhysReg, data = baseAddr)
//       preloadGpr(dut, addr = testParams.physDataSrc, data = storeData)
//       preloadMemory(sram, addr = finalAddr, data = initialMemValue)

//       ParallaxLogger.debug("2. 将分配事务入队")
//       allocQueue.enqueue { p =>
//         p.pc #= testParams.pc
//         p.robPtr #= testParams.robPtr
//         p.physDataSrc #= testParams.physDataSrc
//         p.accessSize #= testParams.accessSize
//         p.aguBasePhysReg #= testParams.aguBasePhysReg
//         p.aguImmediate #= testParams.aguImmediate
//         p.physDataSrcIsFpr #= false
//         p.aguBaseIsFpr #= false
//         p.aguUsePcAsBase #= false
//         p.sqPtr.assignDontCare()
//       }

//       ParallaxLogger.debug("3. 等待，此时内存值不应改变")
//       dut.clockDomain.waitSampling(5)
//       verifyMemory(sram, addr = finalAddr, expectedData = initialMemValue)

//       // 4. 提交指令
//       ParallaxLogger.debug("4. 提交指令")
//       dut.io.releasePorts(0).valid #= true
//       dut.io.releasePorts(0).payload #= testParams.robPtr
//       dut.io.releaseMask(0) #= true
//       dut.clockDomain.waitSampling()
//       dut.io.releasePorts(0).valid #= false
//       dut.clockDomain.waitSampling()
//       ParallaxLogger.debug("4.a. 等待提交的指令完成")
//       dut.clockDomain.waitSampling(50)

//       ParallaxLogger.debug("5. 刷新DCache")
//       // dut.clockDomain.waitSampling(50)
//       flushDCache(dut, finalAddr)

//       ParallaxLogger.debug("6. 验证内存内容是否已被更新")
//       verifyMemory(sram, addr = finalAddr, expectedData = storeData)
//       println("Test 'Complete Lifecycle' PASSED.")
//     }
//   }

//   // -- TEST CASE 2: Flush Logic (使用正确的 StreamDriver.queue) --
//   test("StoreQueue Flush Logic") {
//     val tb = new StoreQueueTestBench()
//     simConfig.compile(tb).doSim { dut =>
//       dut.clockDomain.forkStimulus(10)
//       initDutInputs(dut)
//       val sram = dut.sram
//       val (allocDriver, allocQueue) = StreamDriver.queue(dut.io.allocatePort, dut.clockDomain)

//       val addr1 = BigInt("1010", 16)
//       val addr2 = BigInt("2020", 16)
//       val initialVal = BigInt("1111", 16)

//       // 预加载内存和GPR
//       preloadMemory(sram, addr1, initialVal)
//       preloadMemory(sram, addr2, initialVal)
//       preloadGpr(dut, 1, BigInt("1000", 16))
//       preloadGpr(dut, 2, BigInt("2000", 16))
//       preloadGpr(dut, 10, BigInt("aaaaaaaa", 16)) // data for store 1
//       preloadGpr(dut, 20, BigInt("bbbbbbbb", 16)) // data for store 2

//       // 分配两个存储指令
//       val params1 = StoreQueueTestParams(
//         pc = BigInt("100", 16),
//         robPtr = 1,
//         physDataSrc = 10,
//         accessSize = MemAccessSize.W,
//         aguBasePhysReg = 1,
//         aguImmediate = BigInt("10", 16).toInt
//       )
//       val params2 = StoreQueueTestParams(
//         pc = BigInt("200", 16),
//         robPtr = 2,
//         physDataSrc = 20,
//         accessSize = MemAccessSize.W,
//         aguBasePhysReg = 2,
//         aguImmediate = BigInt("20", 16).toInt
//       )

//       allocQueue.enqueue { p =>
//         p.pc #= params1.pc; p.robPtr #= params1.robPtr; p.physDataSrc #= params1.physDataSrc;
//         p.accessSize #= params1.accessSize; p.aguBasePhysReg #= params1.aguBasePhysReg;
//         p.aguImmediate #= params1.aguImmediate
//       }
//       allocQueue.enqueue { p =>
//         p.pc #= params2.pc; p.robPtr #= params2.robPtr; p.physDataSrc #= params2.physDataSrc;
//         p.accessSize #= params2.accessSize; p.aguBasePhysReg #= params2.aguBasePhysReg;
//         p.aguImmediate #= params2.aguImmediate
//       }

//       // 等待分配完成
//       dut.clockDomain.waitSamplingWhere(allocQueue.isEmpty)

//       // 提交第一个指令，然后立即冲刷第二个
//       dut.io.releasePorts(0).valid #= true
//       dut.io.releasePorts(0).payload #= 1
//       dut.io.releaseMask(0) #= true

//       dut.io.flushPort.valid #= true
//       dut.io.flushPort.payload.targetRobPtr #= 2

//       dut.clockDomain.waitSampling()
//       dut.io.releasePorts(0).valid #= false
//       dut.io.flushPort.valid #= false

//       dut.clockDomain.waitSampling(50)

//       // 验证
//       verifyMemory(sram, addr1, BigInt("aaaaaaaa", 16))
//       verifyMemory(sram, addr2, initialVal)

//       println("Test 'Flush Logic' PASSED.")
//     }
//   }

//   // -- TEST CASE 3: Backpressure when Full (使用正确的 StreamDriver.queue) --
//   test("StoreQueue Backpressure") {
//     val tb = new StoreQueueTestBench()
//     simConfig.compile(tb).doSim { dut =>
//       dut.clockDomain.forkStimulus(10)
//       initDutInputs(dut)
//       dut.io.allocatePort.ready.simPublic() // 暴露 ready 信号

//       val (allocDriver, allocQueue) = StreamDriver.queue(dut.io.allocatePort, dut.clockDomain)
//       // 在这个测试中，我们希望立即发送，所以移除随机延迟
//       allocDriver.transactionDelay = () => 0

//       val sqDepth = dut.lsuConfig.sqDepth

//       // 填满 Store Queue
//       println(s"Filling Store Queue with $sqDepth entries...")
//       for (i <- 0 until sqDepth) {
//         val params = StoreQueueTestParams(
//           pc = i,
//           robPtr = i,
//           physDataSrc = i,
//           accessSize = MemAccessSize.B,
//           aguBasePhysReg = i,
//           aguImmediate = i
//         )
//         allocQueue.enqueue { p =>
//           p.pc #= params.pc; p.robPtr #= params.robPtr; p.physDataSrc #= params.physDataSrc
//           p.accessSize #= params.accessSize; p.aguBasePhysReg #= params.aguBasePhysReg;
//           p.aguImmediate #= params.aguImmediate
//         }
//       }

//       // 等待所有排队的事务被DUT接收（或者直到ready变低）
//       dut.clockDomain.waitSamplingWhere(allocQueue.isEmpty || !dut.io.allocatePort.ready.toBoolean)

//       // 此时，SQ已满，ready 应该为低
//       assert(!dut.io.allocatePort.ready.toBoolean, "allocatePort.ready should be low when SQ is full.")

//       // 提交一个条目以释放空间
//       println("Committing one entry to free up space...")
//       dut.io.releasePorts(0).valid #= true
//       dut.io.releasePorts(0).payload #= 0 // 提交第一个条目 (robPtr=0)
//       dut.io.releaseMask(0) #= true

//       // 等待写回完成，SQ条目被真正释放
//       dut.clockDomain.waitSampling(50)

//       // 此时，allocatePort.ready 应该变高
//       assert(dut.io.allocatePort.ready.toBoolean, "allocatePort.ready should be high after one entry is released.")
//       println("Test 'Backpressure' PASSED.")
//     }
//   }

//   thatsAll()
// }
