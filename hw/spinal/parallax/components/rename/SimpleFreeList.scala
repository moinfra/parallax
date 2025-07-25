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
  // FreeList 的 recover 只是一个垃圾回收机制。它本身并不关心恢复到哪个特定的历史状态。
  // 它只是把所有“借出去”但还没正式“提交归还”的寄存器全部收回来，让 Rename 阶段可以重新开始分配。
  // 说穿了就是个类似 Arena 机制的 GC
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
  val enableLog = false
  // --- 1. 存储和状态寄存器 ---
  val queueDepth = config.requiredDepth
  val addressWidth = log2Up(queueDepth)

  val dataVec = Vec.tabulate(queueDepth) { i =>
    RegInit(U(config.numInitialArchMappings + i, config.physRegIdxWidth))
  }

  val allocPtr = Reg(UInt(addressWidth bits)) init (0)
  val freePtr  = Reg(UInt(addressWidth bits)) init (0)
  val isRisingOccupancy = RegInit(True)

  // --- 2. 组合逻辑：计算当前状态和本周期事务 ---

  val isEmpty = allocPtr === freePtr && !isRisingOccupancy
  val isFull  = allocPtr === freePtr && isRisingOccupancy

  val occupancy = UInt(log2Up(queueDepth + 1) bits)
  when(allocPtr === freePtr) {
    occupancy := Mux(isFull, U(queueDepth), U(0))
  } otherwise {
    occupancy := (freePtr - allocPtr).resized
  }
  io.numFreeRegs := occupancy

  val countWidth = log2Up(Math.max(config.numFreePorts, config.numAllocatePorts) + 1)

  // --- 嵌入式日志 区域 1: 状态和输入 ---
  val cycleCounter = Reg(UInt(32 bits)) init(0)
  cycleCounter := cycleCounter + 1
  
  if(enableLog) report(
    L"====== [DUT DBG] Cycle=${cycleCounter} ======\n" :+
    L"Inputs: recover=${io.recover}, alloc_en=${io.allocate.map(_.enable).asBits}, free_en=${io.free.map(_.enable).asBits}\n" :+
    L"State: allocPtr=${allocPtr}, freePtr=${freePtr}, isRising=${isRisingOccupancy} -> isFull=${isFull}, isEmpty=${isEmpty}, occupancy=${occupancy}"
  )

  for (i <- 0 until config.numAllocatePorts) {
    if(enableLog) report(
      L"AllocPort[${i}]: enable=${io.allocate(i).enable}, physReg=${io.allocate(i).physReg}, success=${io.allocate(i).success}"
    )
  }

  // b. Free端口仲裁
  val rawFreeRequests = UInt(countWidth bits)
  rawFreeRequests := PriorityMux(io.free.map(!_.enable).asBits.asBools :+ True, (0 to config.numFreePorts).map(i => U(i, countWidth bits)))
  val canAcceptFree = Mux(isFull, U(0), U(queueDepth) - occupancy) // 修正：U(queueDepth) 确保类型匹配
  val freeCount = Mux(io.recover, U(0, countWidth bits), Mux(rawFreeRequests > canAcceptFree, canAcceptFree.resized, rawFreeRequests))

  // --- 嵌入式日志 区域 2: Free Count 计算 ---
  if(enableLog) report(
    L"FreeCalc: rawFreeReq=${rawFreeRequests}, canAccept=${canAcceptFree} -> freeCount=${freeCount}"
  )

  // c. Allocate端口仲裁
  val rawAllocRequests = UInt(countWidth bits)
  rawAllocRequests := PriorityMux(io.allocate.map(!_.enable).asBits.asBools :+ True, (0 to config.numAllocatePorts).map(i => U(i, countWidth bits)))
  val canAllocate = occupancy + freeCount
  val allocCount = Mux(io.recover, U(0, countWidth bits), Mux(rawAllocRequests > canAllocate, canAllocate.resized, rawAllocRequests))

  // --- 嵌入式日志 区域 3: Alloc Count 计算 ---
  if(enableLog) report(
    L"AllocCalc: rawAllocReq=${rawAllocRequests}, canAllocate=${canAllocate} -> allocCount=${allocCount}"
  )

  // d. 释放端口写入逻辑
  // 增加 report 打印 dataVec 的写入情况
  for (i <- 0 until config.numFreePorts) {
    val willWrite = i < freeCount && !io.recover
    val writeAddress = freePtr + i
    val writeData = io.free(i).physReg
    when(willWrite) {
      dataVec(writeAddress) := writeData
    }
    if(enableLog) report(
      L"FreeWrite[${i}]: willWrite=${willWrite}, writeAddr=${writeAddress}, writeData=${writeData}"
    )
  }

  // --- 3. 时序逻辑：更新状态和输出寄存器 ---

  // a. 分配端口输出逻辑
  for (i <- 0 until config.numAllocatePorts) {
    val willBeGranted = i < allocCount
    val nextSuccess = RegNext(willBeGranted && !io.recover, init = False)
    io.allocate(i).success := nextSuccess

    val defaultSourceData = dataVec(allocPtr + i)
    val bypassSources = io.free.zipWithIndex.map { case (freePort, freeIdx) =>
      val willBypass = (freeIdx < freeCount) && ((freePtr + freeIdx) === (allocPtr + i))
      (willBypass, freePort.physReg)
    }
    val bypassedData = PriorityMux(bypassSources :+ (True -> defaultSourceData))
    val nextPhysReg = RegNext(bypassedData)
    io.allocate(i).physReg := nextPhysReg

    // 增加 report 打印每个分配端口的详细信息
    if(enableLog) report(
      L"AllocPort[${i}]: willBeGranted=${willBeGranted}, nextSuccess=${nextSuccess}, defaultSource=${defaultSourceData}, bypassedData=${bypassedData}, nextPhysReg=${nextPhysReg}"
    )
    for ((bypassCond, bypassData) <- bypassSources.zipWithIndex) {
      if(enableLog) report(L"  BypassSource[${bypassData}]: willBypass=${bypassCond._1}, data=${bypassCond._2}")
    }
  }

  // b. 指针和状态位更新
  val nextAllocPtr = Mux(io.recover, allocPtr, allocPtr + allocCount)
  val nextFreePtr  = Mux(io.recover, allocPtr, freePtr + freeCount) // 修正：恢复时freePtr也指向allocPtr
  val nextIsRisingOccupancy = Mux(io.recover, True, Mux(allocCount =/= freeCount, freeCount > allocCount, isRisingOccupancy))

  when(io.recover) {
    allocPtr := allocPtr
    freePtr := allocPtr
    isRisingOccupancy := True
  } otherwise {
    allocPtr := allocPtr + allocCount
    freePtr  := freePtr + freeCount
    
    when(allocCount =/= freeCount) {
      isRisingOccupancy := freeCount > allocCount
    }
  }

  // 增加 report 打印指针和状态位的更新前和更新后值
  if(enableLog) report(
    L"PointerUpdate: Current: allocPtr=${allocPtr}, freePtr=${freePtr}, isRising=${isRisingOccupancy}\n" :+
    L"  Updates: allocCount=${allocCount}, freeCount=${freeCount}, recover=${io.recover}\n" :+
    L"  Next (Calculated): nextAllocPtr=${nextAllocPtr}, nextFreePtr=${nextFreePtr}, nextIsRising=${nextIsRisingOccupancy}"
  )

  // --- 嵌入式日志 区域 4: 最终输出和下一状态预览 ---
  // (这个预览仍然是模拟，但现在我们可以和上面真实的计算值对比)
  // 这里的 nextAllocPtr, nextFreePtr, nextIsRising 应该是基于当前周期的输入计算出的下一周期的值
  // 它们与上面用于更新寄存器的值是相同的
  if(enableLog) report(
    L"Outputs: numFreeRegs=${io.numFreeRegs}, next_success=${B(Vec(for (i <- 0 until config.numAllocatePorts) yield i < allocCount && !io.recover))}\n" :+
    L"NextState (Preview): nextAllocPtr=${nextAllocPtr}, nextFreePtr=${nextFreePtr}, nextIsRising=${nextIsRisingOccupancy}"
  )

  // 增加 report 打印 dataVec 的当前内容 (如果深度不大，可以考虑打印部分或全部)
  // 注意：打印整个 Vec 可能会导致非常长的报告字符串，尤其是在深度很大的情况下。
  // 这里仅为示例，实际使用时可能需要根据需求调整。
  if(enableLog) report(L"DataVec Content:")
  for (i <- 0 until queueDepth) {
    if(enableLog) report(L"  dataVec[${i}] = ${dataVec(i)}")
  }
}
