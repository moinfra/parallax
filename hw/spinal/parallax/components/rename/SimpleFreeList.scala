package parallax.components.rename

import spinal.core._
import spinal.lib._
import spinal.core.sim._

// --- 配置和端口定义保持不变 ---
case class SimpleFreeListConfig(
    numPhysRegs: Int,
    numInitialArchMappings: Int = 1,
    numAllocatePorts: Int = 1,
    numFreePorts: Int = 1
) {
  require(numPhysRegs > 0)
  require(numInitialArchMappings >= 0 && numInitialArchMappings <= numPhysRegs)
  require(numAllocatePorts > 0)
  require(numFreePorts > 0)

  val physRegIdxWidth: BitCount = log2Up(numPhysRegs) bits
  val requiredDepth: Int = numPhysRegs - numInitialArchMappings

  // *** 核心约束：在这里或在组件中强制2的幂次 ***
  require(
    isPow2(requiredDepth),
    s"The number of allocatable registers (numPhysRegs - numInitialArchMappings) MUST be a power of two. Got $requiredDepth."
  )
}

case class SimpleFreeListAllocatePort(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val enable = Bool()
  val physReg = UInt(config.physRegIdxWidth)
  val success = Bool()
  override def asMaster(): Unit = {
    out(enable)
    in(physReg, success)
  }
}

case class SimpleFreeListFreePort(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val enable = Bool()
  val physReg = UInt(config.physRegIdxWidth)
  override def asMaster(): Unit = {
    out(enable, physReg)
  }
}

case class SimpleFreeListIO(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val allocate = Vec(slave(new SimpleFreeListAllocatePort(config)), config.numAllocatePorts)
  val free = Vec(slave(new SimpleFreeListFreePort(config)), config.numFreePorts)
  val recover = in Bool ()
  val numFreeRegs = out UInt (log2Up(config.requiredDepth + 1) bits)

  override def asMaster(): Unit = {
    allocate.foreach(master(_))
    free.foreach(master(_))
    out(recover)
    in(numFreeRegs)
  }
}


class SimpleFreeList(val config: SimpleFreeListConfig) extends Component {
  val io = slave(SimpleFreeListIO(config))

  // --- 1. 存储和状态寄存器 ---
  // 由于配置中已强制，requiredDepth 现在一定是2的幂次
  val queueDepth = config.requiredDepth
  val addressWidth = log2Up(queueDepth)

  // 所有槽位都是有效的，初始化更简单
  val dataVec = Vec.tabulate(queueDepth) { i =>
    RegInit(U(config.numInitialArchMappings + i, config.physRegIdxWidth))
  }

  val allocPtr = Reg(UInt(addressWidth bits)) init (0)
  val freePtr = Reg(UInt(addressWidth bits)) init (0) // 初始时，队列是满的，allocPtr 和 freePtr 指向相同位置

  // 使用一个额外的bit来区分满和空
  val isFull = RegInit(True)

  // occupancyCounter 现在可以更可靠地计算
  val occupancyCounter = Reg(UInt(log2Up(queueDepth + 1) bits)) init(queueDepth)

  // --- 2. 组合逻辑：计算本周期的事务 ---

  // a. Free端口仲裁
  val availableToFree = queueDepth - occupancyCounter
  val freeWants = Vec(io.free.map(_.enable))
  val freeGrants = Vec(Bool(), config.numFreePorts)
  var resourcesLeftForFree = availableToFree
  for (i <- 0 until config.numFreePorts) {
    // 只有在队列未满时才能接受 free 请求
    val grant = !io.recover && freeWants(i) && (resourcesLeftForFree > 0)
    freeGrants(i) := grant
    resourcesLeftForFree = Mux(grant, resourcesLeftForFree - 1, resourcesLeftForFree)
  }
  val freeCount = CountOne(freeGrants)

  // b. Allocate端口仲裁
  val availableToAlloc = occupancyCounter + freeCount
  val allocateWants = Vec(io.allocate.map(_.enable))
  val allocateGrants = Vec(Bool(), config.numAllocatePorts)
  var resourcesLeftForAlloc = availableToAlloc
  for (i <- 0 until config.numAllocatePorts) {
    val grant = !io.recover && allocateWants(i) && (resourcesLeftForAlloc > 0)
    allocateGrants(i) := grant
    resourcesLeftForAlloc = Mux(grant, resourcesLeftForAlloc - 1, resourcesLeftForAlloc)
  }
  val allocCount = CountOne(allocateGrants)
  
  // c. 释放端口写入逻辑 (写入到 freePtr 的位置)
  for (i <- 0 until config.numFreePorts) {
    when(freeGrants(i)) {
      dataVec(freePtr + i) := io.free(i).physReg
    }
  }

  // --- 3. 时序逻辑：更新状态和输出寄存器 ---
  
  // 输出寄存器 (包含直通逻辑)
  for (i <- 0 until config.numAllocatePorts) {
    io.allocate(i).success := RegNext(allocateGrants(i)).init(False)
    val physRegData = Reg(UInt(config.physRegIdxWidth))
    io.allocate(i).physReg := physRegData
    
    val defaultSourceData = dataVec(allocPtr + i)
    // 检查是否有来自本周期 free 端口的直通数据
    val bypassSources = io.free.zipWithIndex.map { case (freePort, freeIdx) =>
      val willBypass = freeGrants(freeIdx) && ((freePtr + freeIdx) === (allocPtr + i))
      (willBypass, freePort.physReg)
    }
    when(allocateGrants(i)) {
      physRegData := PriorityMux(bypassSources :+ (True -> defaultSourceData))
    } otherwise {
      physRegData := U(0, config.physRegIdxWidth)
    }
  }

  // 指针和计数器更新
  when(io.recover) {
    allocPtr         := 0
    freePtr          := 0
    occupancyCounter := queueDepth
  } otherwise {
    allocPtr         := allocPtr + allocCount
    freePtr          := freePtr + freeCount
    occupancyCounter := occupancyCounter + freeCount - allocCount
  }
  
  io.numFreeRegs := occupancyCounter

  val freeGrantsReg = RegNext(freeGrants, init = Vec.fill(config.numFreePorts)(False))
}
