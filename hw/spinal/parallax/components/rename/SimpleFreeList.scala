/* 

  dataVec: [p20, p21, p22, p10, p11, p12, p13, p14]
  Index:     0    1    2    3    4    5    6    7
             ^              ^
             |              |
           freePtr        allocPtr
          (回收点)         (分配点)
   ---------><===已分配出去的区域===><----可分配区域-----

  注意：所有被使能的端口必须是连续的
 */
package parallax.components.rename

import spinal.core._
import spinal.lib._
import spinal.core.sim._
import parallax.utilities.ParallaxSim.fatal
import parallax.utilities.ParallaxSim.log

// --- 配置和端口定义 ---
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

// --- IO Bundle ---
case class SimpleFreeListIO(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val allocate = Vec(slave(new SimpleFreeListAllocatePort(config)), config.numAllocatePorts)
  val free = Vec(slave(new SimpleFreeListFreePort(config)), config.numFreePorts)
  val recover = in Bool ()
  val numFreeRegs = out UInt (log2Up(config.requiredDepth + 1) bits)

  // 调试端口，使用 generate 语句，使其只在需要时生成
  val debug_arat_used_mask = config.debugging generate { in(Bits(config.numPhysRegs bits)) } // 物理寄存器是否被ARAT占用

  override def asMaster(): Unit = {
    allocate.foreach(master(_))
    free.foreach(master(_))
    out(recover)
    in(numFreeRegs)
    config.debugging generate { out(debug_arat_used_mask) } // Master 驱动这个信号，所以是 out
  }
}

// --- SimpleFreeList 组件主体 ---
class SimpleFreeList(val config: SimpleFreeListConfig) extends Component {
  val io = slave(SimpleFreeListIO(config))
  val enableLog = false
  val verbose = false

  // =========================================================================
  // === 1. 存储和状态寄存器 (Storage and State Registers)
  // =========================================================================
  val queueDepth = config.requiredDepth
  val addressWidth = log2Up(queueDepth)

  // 存储空闲物理寄存器索引的向量
  val dataVec = Vec.tabulate(queueDepth) { i =>
    RegInit(U(config.numInitialArchMappings + i, config.physRegIdxWidth))
  }

  // 分配指针 (指向下一个可分配的槽位，即读指针)
  val allocPtrReg = Reg(UInt(addressWidth bits)) init (0)
  // 回收指针 (指向下一个可写入回收寄存器的槽位，即写指针)
  val freePtrReg = Reg(UInt(addressWidth bits)) init (0)
  // 状态标志位，用于区分 allocPtr == freePtr 时的“满”和“空”状态
  val wrapModeReg = RegInit(True) // 初始是回绕模式，表示满

  // 仿真用的周期计数器
  val cycleCounter = if (enableLog) Reg(UInt(32 bits)) init (0) else null
  if (enableLog) cycleCounter := cycleCounter + 1

  // =========================================================================
  // === 2. 组合逻辑 (Combinational Logic)
  // =========================================================================
  val combinationalArea = new Area {
    // --- 状态计算 ---
    // allocatable 代表 FreeList 中实际可供分配的物理寄存器数量
    val allocatable = UInt(log2Up(queueDepth + 1) bits)
    val isFull = allocPtrReg === freePtrReg && wrapModeReg
    val isEmpty = allocPtrReg === freePtrReg && !wrapModeReg

    when(isEmpty) {
      allocatable := U(0)
    }.elsewhen(isFull) {
      allocatable := U(queueDepth)
    } elsewhen (wrapModeReg) { // No-wrap mode, allocPtr >= freePtr
      // 可分配数 = queueDepth - (allocPtr - freePtr)
      // 这是总容量减去“空洞”的大小
      allocatable := (U(queueDepth) - (allocPtrReg - freePtrReg)).resized
    } otherwise { // Wrap mode, allocPtr < freePtr
      // 可分配数 = freePtr - allocPtr
      allocatable := (freePtrReg - allocPtrReg).resized
    }
    io.numFreeRegs := allocatable

    if (enableLog) {
      when(allocatable =/= RegNext(allocatable, init = U(0))) {
        log(
          L"[RegRes|FreeList] [Cycle ${cycleCounter}] Current: allocatable=${allocatable}, isEmpty=${isEmpty}, isFull=${isFull}"
        )
      }
    }

    // --- 分配/回收计数 ---
    val countWidth = log2Up(Math.max(config.numFreePorts, config.numAllocatePorts) + 1)

    // Free 端口仲裁 (计算本周期要回收多少个寄存器)
    val rawFreeRequests = PriorityMux(io.free.map(!_.enable).asBits.asBools :+ True, (0 to config.numFreePorts).map(U(_, countWidth bits)))
    // 可接受回收的数量 (队列中剩余的空闲槽位 = queueDepth - allocatable)
    val canAcceptFree = U(queueDepth) - allocatable 
    val freeCount = Mux(io.recover, U(0), Mux(rawFreeRequests > canAcceptFree, canAcceptFree.resized, rawFreeRequests))

    // Allocate 端口仲裁 (计算本周期要分配多少个寄存器)
    val rawAllocRequests = PriorityMux(io.allocate.map(!_.enable).asBits.asBools :+ True, (0 to config.numAllocatePorts).map(U(_, countWidth bits)))
    val canAllocate = allocatable // 可分配数即为当前队列中的空闲寄存器数量
    val allocCount = Mux(io.recover, U(0), Mux(rawAllocRequests > canAllocate, canAllocate.resized, rawAllocRequests))

    // --- 驱动 IO 端口 ---
    for (i <- 0 until config.numAllocatePorts) {
      io.allocate(i).canAllocate := canAllocate
    }
  } // End of combinationalArea

  // =========================================================================
  // === 3. 时序逻辑 (Sequential Logic)
  // =========================================================================
  val sequentialArea = new Area {
    // 从组合逻辑区域获取计算好的计数值
    import combinationalArea._

    // --- a. 分配端口输出逻辑 (Allocation Port Output Logic) ---
    // 这个逻辑在时钟沿将分配结果输出给 Rename 阶段
    for (i <- 0 until config.numAllocatePorts) {
      val success = (i < allocCount) && !io.recover
      // 分配成功与否的信号延迟一拍输出，与物理寄存器索引同步
      io.allocate(i).success := success

      // 旁路逻辑: 检查本周期回收的寄存器是否立刻被重新分配
      // 这可以减少一拍的延迟，让刚释放的寄存器能立即被使用
      val defaultSourceData = dataVec(allocPtrReg + i)
      val bypassSources = io.free.zipWithIndex.map { case (freePort, freeIdx) =>
        // val willBypass = (freeIdx < freeCount) && ((freePtrReg + freeIdx) === (allocPtrReg + i))
        val willBypass = False
        (willBypass, freePort.physReg)
      }
      val bypassedData = PriorityMux(bypassSources :+ (True -> defaultSourceData))

      // 分配的物理寄存器索引也延迟一拍输出
      io.allocate(i).physReg := bypassedData

      if (enableLog) {
        when(success) {
          log(
            L"[RegRes|FreeList] [Cycle ${cycleCounter}] ALLOCATE: AllocPort[${i}] allocated p${bypassedData} " +:
            L"(from defaultSource=${defaultSourceData}, bypassed=${bypassedData}, allocPtr=${allocPtrReg}, allocCount=${allocCount})"
          )
        }
      }
    }

    // --- d. 释放端口写入逻辑 (Free Port Write Logic) ---
    // 这个逻辑将 Commit 阶段回收的物理寄存器写回到 dataVec 中
    for (i <- 0 until config.numFreePorts) {
      val willWrite = i < freeCount
      val writeAddress = freePtrReg + i
      when(willWrite) {
        val writeData = io.free(i).physReg
        dataVec(writeAddress) := writeData

        if (enableLog) {
          log(
            L"[RegRes|FreeList] [Cycle ${cycleCounter}] RETURN: FreePort[${i}] returns p${io
                .free(i)
                .physReg} to dataVec[${writeAddress}] (freePtr=${freePtrReg}, freeCount=${freeCount})" :+
                L" override p${dataVec(writeAddress)}"
          )
        }
      }
      
      config.debugging generate {
        // 如果被覆盖的preg（记作pOver)不位于 arat 中，已知arat包含了所有不free的寄存器，则pOver 应当是free。pOver 被覆盖意味着这个寄存器将会永久丢失
        // 延迟一周期检查，因为arat在下一拍更新
        val victim = RegNext(dataVec(writeAddress), init = U(0))
        when(RegNext(willWrite) && !io.debug_arat_used_mask(victim)) {
          fatal(L"Last cycle, we overwrote a physical register p${victim} that is not in ARAT!!!")
        }
      }
    }

    // --- b. 指针和状态位更新 (Pointer and State Update) ---
    val nextAllocPtr = allocPtrReg + allocCount
    val nextFreePtr = freePtrReg + freeCount

    // 状态标志位 wrapModeReg 的更新逻辑
    // 检查指针是否跨越了0点（注意：回滚时，freePtrReg 会非线性变化，所以需要进行跨越检测）
    // 仅当有实际的分配或回收操作时，才更新指针和状态
    when(allocCount =/= 0 || freeCount =/= 0) {
        allocPtrReg := nextAllocPtr
        freePtrReg := nextFreePtr

        val allocWrapped = nextAllocPtr < allocPtrReg
        val freeWrapped = nextFreePtr < freePtrReg

        when(nextAllocPtr === nextFreePtr) {
            // 仅在重合时，根据谁追上谁来决定是满是空
            wrapModeReg := (freeCount > allocCount)
        } elsewhen(allocWrapped =/= freeWrapped) {
            // 当且仅当只有一个指针跨越0点时，模式才会翻转
            wrapModeReg := !wrapModeReg
        } otherwise {
            // 如果指针移动了，但既不重合也不跨越，wrapModeReg 保持不变
            // （由于上面的默认赋值，这里无需任何操作）
        }
    }

    // --- 回滚逻辑拥有最高优先级，直接覆盖上面的常规赋值 ---
    when(io.recover) {
      assert(allocCount === U(0), "Cannot alloc/free when recover")
      assert(freeCount === U(0), "Cannot alloc/free when recover")
      // 回收指针被拉回到分配指针的位置，进行垃圾回收
      freePtrReg := allocPtrReg
      // 回滚后，将状态设置为“满”(所有推测分配的寄存器都被回收，那么所有寄存器都应当是可用的)
      wrapModeReg := True

          // 调试打印
      if (enableLog) {
          log(L"[RegRes|FreeList] --- dataVec Content at time of RECOVER (Reg) ---")
          for (i <- 0 until queueDepth) {
            val regs_per_line = 32
            if (i % regs_per_line == 0) {
              val indices = (i until Math.min(i + regs_per_line, queueDepth)).map(idx => L"${idx.toHexString.padTo(3, " ")} ")
              log(L"  Slot Index:  ${indices}")
              
              val values = (i until Math.min(i + regs_per_line, queueDepth)).map(idx => dataVec(idx)).toList
              log(Seq(L"  PhysReg Val: ", Seq(values.map(v => L"p${v} "))))
            }
          }
          log(L"[RegRes|FreeList] ---allocPtr=${allocPtrReg} freePtr=${freePtrReg}---")
      }
    } otherwise {
          // 调试打印
      if (enableLog) {
          log(L"[RegRes|FreeList] --- dataVec Content at time of normal (Reg) ---")
          for (i <- 0 until queueDepth) {
            val regs_per_line = 32
            if (i % regs_per_line == 0) {
              val indices = (i until Math.min(i + regs_per_line, queueDepth)).map(idx => L"${idx.toHexString.padTo(3, " ")} ")
              log(L"  Slot Index:  ${indices}")
              val values = (i until Math.min(i + regs_per_line, queueDepth)).map(idx => dataVec(idx)).toList
              log(Seq(L"  PhysReg Val: ", Seq(values.map(v => L"p${v} "))))
            }
          }
          log(L"[RegRes|FreeList] ---allocPtr=${allocPtrReg} freePtr=${freePtrReg}---")
      }
    }

    if (enableLog) {
      // 打印详细的指针更新日志
      when(allocCount > 0 || freeCount > 0 || io.recover) {
        val prevAllocPtr = allocPtrReg
        val prevFreePtr = freePtrReg
        val prevNoWrapMode = wrapModeReg

        when(io.recover) {
          log(
            L"[RegRes|FreeList] [Cycle ${cycleCounter}] RECOVER (ROLLBACK): FreeList recovered.\n" :+
              L"[RegRes|FreeList]   Previous State: allocPtr=${prevAllocPtr}, freePtr=${prevFreePtr}, wrapModeReg=${prevNoWrapMode}, FreeRegs=${allocatable}\n" :+
              L"[RegRes|FreeList]   New State: allocPtr=${allocPtrReg}, freePtr=${allocPtrReg}, wrapModeReg=True, FreeRegs=${config.requiredDepth}"
          )
        } otherwise {

          log(
            L"[RegRes|FreeList] [Cycle ${cycleCounter}] POINTER_UPDATE: Current: allocPtr=${allocPtrReg}, freePtr=${freePtrReg}, isRising=${wrapModeReg}, FreeRegs=${allocatable}\n" :+
              L"[RegRes|FreeList] [Cycle ${cycleCounter}]   Updates: allocCount=${allocCount}, freeCount=${freeCount}\n" :+
              L"[RegRes|FreeList] [Cycle ${cycleCounter}]   Next (Applied): nextAllocPtr=${nextAllocPtr}, nextFreePtr=${nextFreePtr}, nextIsRising=?"
          )
        }
      }
    }
  } // End of sequentialArea

  // =========================================================================
  // === 4. 断言和一致性检查 (Assertions and Consistency Checks)
  // =========================================================================
  val assertionArea = new Area {
    import combinationalArea._

    // --- a. 关键假设断言 ---
    // 这个断言验证了我们的核心假设：在回滚周期，禁止释放，但是可以分配（虽然不会成功）
    assert(
      !(io.recover && io.free.map(_.enable).orR),
      "FATAL ASSUMPTION VIOLATED: A free request occurred during a recovery cycle!"
    )

    // 这个断言检查更新后的指针是否保持合法关系
    // 这个断言在 sequentialArea 内部检查指针更新前后的状态
    // 我们将其移动到这里，作为对 sequentialArea 内部指针更新结果的检查
    // 确保 nextFreePtr 没有错误地超前 nextAllocPtr (或反之)
    // 尽管 freeCount/allocCount 的计算已经防止了越界，这个断言提供了额外的安全网
    val nextAllocPtr = allocPtrReg + allocCount
    val nextFreePtr = freePtrReg + freeCount

    when(wrapModeReg) { // 当前是 allocPtr 领先 freePtr 或两者相等且队列为满
        val allocPtrWillWrap = (allocPtrReg + allocCount) < allocPtrReg
        // 如果 allocPtr 不回绕，那么 nextFreePtr 必须小于等于 nextAllocPtr
        // 如果 allocPtr 回绕了，这个数值比较会失效，但逻辑上是合法的，因为 freeCount 的限制已经确保了有效性
        assert(allocPtrWillWrap || (nextFreePtr <= nextAllocPtr),
            L"FATAL ERROR: Free pointer (${nextFreePtr}) illegally overtook allocation pointer (${nextAllocPtr}) in no-wrap mode. " :+
            L"(current allocPtr=${allocPtrReg}, freePtr=${freePtrReg}, allocCount=${allocCount}, freeCount=${freeCount})"
        )
    } otherwise { // 当前是 freePtr 领先 allocPtr 或两者相等且队列为空
        val freePtrWillWrap = (freePtrReg + freeCount) < freePtrReg
        // 如果 freePtr 不回绕，那么 nextAllocPtr 必须小于等于 nextFreePtr
        // 如果 freePtr 回绕了，这个数值比较会失效，但逻辑上是合法的
        assert(freePtrWillWrap || (nextAllocPtr <= nextFreePtr),
            L"FATAL ERROR: Allocation pointer (${nextAllocPtr}) illegally overtook free pointer (${nextFreePtr}) in wrap mode. " :+
            L"(current allocPtr=${allocPtrReg}, freePtr=${freePtrReg}, allocCount=${allocCount}, freeCount=${freeCount})"
        )
    }


    // --- b. 在线一致性检查器 (Online Consistency Checker) ---
    // 这个检查器只在配置了 debugging 标志时生成，避免在最终综合时产生不必要的硬件。
    val consistencyCheck = config.debugging generate new Area {
      // === 1. 生成 freeMask ===
      // 这个掩码表示了 FreeList 中所有当前可用的物理寄存器。
      val freeMask = Bits(config.numPhysRegs bits)
      freeMask.clearAll()

      // 遍历所有可能的空闲槽位 (这个循环在硬件中是并行的)
      // allocatable 代表 FreeList 队列中元素的数量
      for (i <- 0 until queueDepth) {
        // 只有当这个槽位在当前的空闲队列中有效时才进行操作
        when(U(i) < allocatable) { // 检查 i 是否小于当前队列的实际元素数量
          val free_phys_reg_idx = dataVec(allocPtrReg + i) // 从 allocPtr (读指针) 处读取元素
          // 排除 p0, 尽管 p0 理论上不应该出现在 FreeList 中
          when(free_phys_reg_idx =/= 0) {
            freeMask(free_phys_reg_idx) := True
          }
        }
      }

      // === 2. 获取 aratUsedMask ===
      // 从 IO 端口直接获取 ARAT 的使用情况掩码。
      val aratUsedMask = io.debug_arat_used_mask

      // === 3. 计算交集并检查 ===
      // 如果一个物理寄存器同时在 freeMask 和 aratUsedMask 中，说明状态不一致。
      val conflictMask = freeMask & aratUsedMask
      val checkFailed = conflictMask.orR // 如果交集的任何一位是'1'，则检查失败

      // === 4. 为每个可能的冲突生成独立的断言，以便清晰报告 ---
      for (pRegIdx <- 1 until config.numPhysRegs) { // 从 1 开始，以排除 p0
        when(checkFailed && conflictMask(pRegIdx)) {
          assert(
            False,
            Seq(
              L"\n=======================================================================\n",
              L"[ASSERTION] FreeList/ARAT Inconsistency Detected!\n",
              L"  Cycle: ",
              cycleCounter,
              "\n",
              L"  CONFLICT FOUND FOR: p",
              U(pRegIdx, config.physRegIdxWidth),
              "\n",
              L"  Reason: This physical register is marked as FREE in FreeList, but is also actively USED in ARAT.\n",
              L"-----------------------------------------------------------------------\n",
              L"  FreeList State Snippet:\n",
              L"    allocPtr = ",
              allocPtrReg,
              ", freePtr = ",
              freePtrReg,
              ", allocatable = ",
              allocatable,
              ", wrapModeReg = ",
              wrapModeReg,
              "\n",
              L"======================================================================="
            )
          )
        }
      }
    } // End of consistencyCheck Area
  } // End of assertionArea
} // End of SimpleFreeList Component
