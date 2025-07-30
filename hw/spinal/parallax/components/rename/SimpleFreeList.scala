package parallax.components.rename

import spinal.core._
import spinal.lib._
import spinal.core.sim._

// --- 配置和端口定义保持不变 ---
case class SimpleFreeListConfig(
    numPhysRegs: Int,
    numInitialArchMappings: Int = 1,
    numAllocatePorts: Int = 1,
    numFreePorts: Int = 1,
    debugging: Boolean = false
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
  val canAllocate = UInt(log2Up(config.requiredDepth + 1) bits)
  override def asMaster(): Unit = {
    out(enable)
    in(canAllocate, physReg, success)
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
  val debug_arat_used_mask = config.debugging generate {in(Bits(config.numPhysRegs bits)) default(U(0, config.numPhysRegs bits).asBits)}

  override def asMaster(): Unit = {
    allocate.foreach(master(_))
    free.foreach(master(_))
    out(recover)
    in(numFreeRegs)
    config.debugging generate {out(debug_arat_used_mask)}
  }
}


class SimpleFreeList(val config: SimpleFreeListConfig) extends Component {
  val io = slave(SimpleFreeListIO(config))
  val enableLog = true // 启用日志
  val verbose = false // 启用日志
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

  // val occupancy = UInt(log2Up(queueDepth + 1) bits)
  // when(allocPtr === freePtr) {
  //   occupancy := Mux(isFull, U(queueDepth), U(0))
  // } otherwise {
  //   occupancy := (freePtr - allocPtr).resized
  // }
  // io.numFreeRegs := occupancy
    val occupancy = UInt(log2Up(queueDepth + 1) bits)
  when(allocPtr === freePtr) {
    occupancy := Mux(isFull, U(queueDepth), U(0))
  } .elsewhen(isRisingOccupancy) { // isRising 为 True 表示 freePtr 已经“绕圈”超过了 allocPtr
    occupancy := U(queueDepth) + freePtr - allocPtr
  } otherwise { // 正常情况，allocPtr 领先 freePtr
    occupancy := (freePtr - allocPtr).resized
  }
  io.numFreeRegs := occupancy
  when(occupancy =/= RegNext(occupancy, init = U(0))) {
    if(enableLog) report(
      L"[RegRes|FreeList] [Cycle ${cycleCounter}] OCCUPANCY: Current: occupancy=${occupancy}, isEmpty=${isEmpty}, isFull=${isFull}"
    )
  }

  val countWidth = log2Up(Math.max(config.numFreePorts, config.numAllocatePorts) + 1)

  val cycleCounter = Reg(UInt(32 bits)) init(0)
  cycleCounter := cycleCounter + 1
  
  // b. Free端口仲裁
  val rawFreeRequests = UInt(countWidth bits)
  rawFreeRequests := PriorityMux(io.free.map(!_.enable).asBits.asBools :+ True, (0 to config.numFreePorts).map(i => U(i, countWidth bits)))
  val canAcceptFree = Mux(isFull, U(0), U(queueDepth) - occupancy)
  val freeCount = Mux(False, U(0, countWidth bits), Mux(rawFreeRequests > canAcceptFree, canAcceptFree.resized, rawFreeRequests))

  // c. Allocate端口仲裁
  val rawAllocRequests = UInt(countWidth bits)
  rawAllocRequests := PriorityMux(io.allocate.map(!_.enable).asBits.asBools :+ True, (0 to config.numAllocatePorts).map(i => U(i, countWidth bits)))
  val canAllocate = occupancy + freeCount // 可分配的数量 = 当前空闲 + 本周期归还的
  val allocCount = Mux(False, U(0, countWidth bits), Mux(rawAllocRequests > canAllocate, canAllocate.resized, rawAllocRequests))
  for (i <- 0 until config.numAllocatePorts) {
    // print enable status
    report(L"[RegRes|FreeList] [Cycle ${cycleCounter}] ALLOCATE: AllocPort[${i}] enable=${io.allocate(i).enable}, canAllocate=${canAllocate}")
  }
  io.allocate.foreach(_.canAllocate := canAllocate)
  // --- 3. 时序逻辑：更新状态和输出寄存器 ---

  // d. 释放端口写入逻辑 (归还事件)
  for (i <- 0 until config.numFreePorts) {
    // 恢复 willWrite 到原始逻辑
    val willWrite = i < freeCount && !io.recover
    val writeAddress = freePtr + i
    val writeData = io.free(i).physReg
    when(willWrite) {
      dataVec(writeAddress) := writeData
      if(enableLog) report(
        L"[RegRes|FreeList] [Cycle ${cycleCounter}] RETURN: FreePort[${i}] returns P${writeData} to dataVec[${writeAddress}] (freePtr=${freePtr}, freeCount=${freeCount})"
      )
    }
  }

  // a. 分配端口输出逻辑 (分配事件)
  for (i <- 0 until config.numAllocatePorts) {
    // 恢复 willBeGranted 到原始逻辑
    val willBeGranted = i < allocCount
    val nextSuccess = RegNext(willBeGranted && !io.recover, init = False)
    io.allocate(i).success := nextSuccess

    val defaultSourceData = dataVec(allocPtr + i)
    val bypassSources = io.free.zipWithIndex.map { case (freePort, freeIdx) =>
      val willBypass = (freeIdx < freeCount) && ((freePtr + freeIdx) === (allocPtr + i))
      (willBypass, freePort.physReg)
    }
    val bypassedData = PriorityMux(bypassSources :+ (True -> defaultSourceData))
    val nextPhysReg = RegNext(bypassedData) // 实际分配的物理寄存器号
    io.allocate(i).physReg := nextPhysReg

    when(willBeGranted && !io.recover) {
      if(enableLog && verbose) report(
        L"[RegRes|FreeList] [Cycle ${cycleCounter}] ALLOCATE: AllocPort[${i}] granted P${nextPhysReg} (from defaultSource=${defaultSourceData}, bypassed=${bypassedData}, allocPtr=${allocPtr}, allocCount=${allocCount})"
      )
    }
  }

  if(enableLog) report(
      L"[RegRes|FreeList] freePtr=${freePtr} allocPtr=${allocPtr}"
    )

  when(io.recover) {
    val prevAllocPtr = allocPtr
    val prevFreePtr = freePtr
    val prevIsRisingOccupancy = isRisingOccupancy
    
    freePtr := allocPtr
    isRisingOccupancy := True
    if(enableLog) report(
      L"[RegRes|FreeList] [Cycle ${cycleCounter}] RECOVER (ROLLBACK): FreeList recovered: freePtr=${freePtr} allocPtr=${allocPtr}.\n" :+
      L"[RegRes|FreeList]   Previous State: allocPtr=${prevAllocPtr}, freePtr=${prevFreePtr}, isRisingOccupancy=${prevIsRisingOccupancy}, FreeRegs=${occupancy}\n" :+
      L"[RegRes|FreeList]   New State: allocPtr=${allocPtr}, freePtr=${allocPtr}, isRisingOccupancy=True, FreeRegs=${config.requiredDepth}" // 恢复后理论上所有寄存器都空闲
    )
    if(enableLog) {
      // report(L"[RegRes|FreeList] [Cycle ${cycleCounter}] DataVec Content (allocPtr=${allocPtr}, freePtr=${freePtr}):")
      // for (i <- 0 until queueDepth) {
      //   report(L"[RegRes|FreeList] [Cycle ${cycleCounter}]   dataVec[${i}] = ${dataVec(i)}")
      // }
    }
  } otherwise {
    // 只有发生分配或归还时才打印指针更新和 DataVec 内容
    
  // b. 指针和状态位更新 (恢复/回滚事件，以及常规的指针更新)
  val nextAllocPtr = allocPtr + allocCount
  val nextFreePtr  = freePtr + freeCount

  // *** 关键修复 2: 修正 isRisingOccupancy 的常规更新逻辑 ***
  // 只有当指针跨越边界时，标志位才会翻转
  when(allocCount =/= freeCount) {
    val alloc_crossed_free = allocPtr <= freePtr && nextAllocPtr > nextFreePtr
    val free_crossed_alloc = freePtr <= allocPtr && nextFreePtr > nextAllocPtr
    
    when(alloc_crossed_free) {
        isRisingOccupancy := False
    } .elsewhen(free_crossed_alloc) {
        isRisingOccupancy := True
    }
  }

  val nextIsRisingOccupancy = Mux(io.recover, True, Mux(allocCount =/= freeCount, freeCount > allocCount, isRisingOccupancy))
  

    when(allocCount > U(0) || freeCount > U(0)) { 
      if(enableLog) report(
        L"[RegRes|FreeList] [Cycle ${cycleCounter}] POINTER_UPDATE: Current: allocPtr=${allocPtr}, freePtr=${freePtr}, isRising=${isRisingOccupancy}, FreeRegs=${occupancy}\n" :+
        L"[RegRes|FreeList] [Cycle ${cycleCounter}]   Updates: allocCount=${allocCount}, freeCount=${freeCount}\n" :+
        L"[RegRes|FreeList] [Cycle ${cycleCounter}]   Next (Calculated): nextAllocPtr=${nextAllocPtr}, nextFreePtr=${nextFreePtr}, nextIsRising=${nextIsRisingOccupancy}"
      )
      // if(enableLog) {
      //   report(L"[RegRes|FreeList] [Cycle ${cycleCounter}] DataVec Content (allocPtr=${allocPtr}, freePtr=${freePtr}):")
      //   for (i <- 0 until queueDepth) {
      //     report(L"[RegRes|FreeList] [Cycle ${cycleCounter}]   dataVec[${i}] = ${dataVec(i)}")
      //   }
      // }
    }
    // allocPtr := allocPtr + allocCount
    // freePtr  := freePtr + freeCount
    
    // when(allocCount =/= freeCount) {
    //   isRisingOccupancy := freeCount > allocCount
    // }
  }

  val consistencyCheck = config.debugging generate new Area {
    // === 1. 生成 freeMask ===
    val freeMask = Bits(config.numPhysRegs bits)
    freeMask.clearAll()

    // 遍历所有可能的空闲槽位
    // 这个循环在硬件中是并行的
    for (i <- 0 until queueDepth) {
      // 只有当这个槽位在当前的空闲队列中有效时才进行操作
      when(U(i) < occupancy) {
        val free_phys_reg_idx = dataVec(allocPtr + i)
        // 排除 p0, 尽管 p0 理论上不应该出现在 FreeList 中
        when(free_phys_reg_idx =/= 0) {
          freeMask(free_phys_reg_idx) := True
        }
      }
    }

    // === 2. 生成 aratUsedMask ===
    val aratUsedMask = io.debug_arat_used_mask
    // === 3. 计算交集并检查 ===
    val conflictMask = freeMask & aratUsedMask

    // --- 4. 为每个可能的冲突生成独立的断言 ---
    for (pRegIdx <- 1 until config.numPhysRegs) { // 从 1 开始，排除 p0
      when(conflictMask(pRegIdx)) {
        assert(False, Seq(
          L"=======================================================================\n",
          L"[ASSERTION] FreeList/ARAT Inconsistency Detected!\n",
          L"  Cycle: ", cycleCounter, "\n",
          L"  CONFLICT FOUND FOR: p", U(pRegIdx, config.physRegIdxWidth), "\n",
          L"  Reason: This physical register is marked as FREE in FreeList, but is also actively USED in ARAT.\n",
          L"-----------------------------------------------------------------------\n",
          L"  FreeList State Snippet:\n",
          L"    allocPtr = ", allocPtr, ", freePtr = ", freePtr, ", occupancy = ", occupancy, "\n",
          L"======================================================================="
        ))
      }
    }
  }
}
