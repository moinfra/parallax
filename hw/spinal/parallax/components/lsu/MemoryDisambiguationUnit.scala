package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig

// =========================================================
// >> MDU 接口定义
// =========================================================

// --- Load 查询请求 ---
case class MduLoadQuery(pCfg: PipelineConfig) extends Bundle {
  val robPtr = UInt(pCfg.robPtrWidth)
}

// --- Load 查询响应 ---
case class MduLoadRsp() extends Bundle {
  val canProceed = Bool()
}

// --- Load 查询端口 (请求/响应模式) ---
case class MduQueryPort(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(MduLoadQuery(pCfg))
  val rsp = out(MduLoadRsp()) // 响应是组合逻辑，所以是 out()

  override def asMaster(): Unit = {
    master(cmd)
    in(rsp)
  }
}

// =========================================================
// >> MDU 组件主体
// =========================================================
class MemoryDisambiguationUnit(
    val pCfg: PipelineConfig,
    val pendingStoreDepth: Int = 8 // 可配置的在途Store追踪深度
) extends Component {

  // --- 1. IO 定义 ---
  val io = new Bundle {
    // 从上游LSU EU接收的事件
    val storeDispatched      = slave(Flow(UInt(pCfg.robPtrWidth)))
    val storeAddressResolved = slave(Flow(UInt(pCfg.robPtrWidth)))
    
    // Load查询端口
    val queryPort            = slave(MduQueryPort(pCfg))

    // 全局冲刷信号
    val flush                = in(Bool())
  }
  case class StoreInfo() extends Bundle {
    val valid  = Bool()
    val robPtr = UInt(pCfg.robPtrWidth)
  }
  // 创建一个默认的 StoreInfo 实例用于初始化
  val defaultStoreInfo = StoreInfo()
  defaultStoreInfo.valid  := False
  defaultStoreInfo.robPtr := 0 // 或者 assignDontCare()

  // "在途Store"的寄存器文件
  val pendingStores = Vec.fill(pendingStoreDepth)(Reg(StoreInfo()).init(defaultStoreInfo))

  // --- 3. 辅助函数 ---
  // 比较带回绕的ROB指针 (A是否比B旧)
  // 假设 robPtr 包含一个环绕位 (generation bit) 作为最高位
  private def isOlder(robPtrA: UInt, robPtrB: UInt): Bool = {
    val width = robPtrA.getWidth
    require(width == robPtrB.getWidth, "ROB pointer widths must match")

    if (width <= 1) { // 处理没有generation bit的简单情况
      return robPtrA < robPtrB
    }

    val genA = robPtrA.msb
    val idxA = robPtrA(width - 2 downto 0)
    val genB = robPtrB.msb
    val idxB = robPtrB(width - 2 downto 0)
    
    // A比B旧的条件:
    // 1. 在同一个环绕周期内，A的索引小于B的索引。
    // 2. 在不同的环绕周期内 (A旧B新)，A的索引大于B的索引 (因为B已经回绕了)。
    (genA === genB && idxA < idxB) || (genA =/= genB && idxA > idxB)
  }

  // --- 4. 核心逻辑 ---

  // a. Store 入队逻辑 (当Store被发射到LSU时)
  val freeSlotMask = B(pendingStores.map(!_.valid))
  val hasFreeSlot = freeSlotMask.orR
  val freeSlotOh = OHMasking.first(freeSlotMask)

  when(io.storeDispatched.valid) {
    // 断言：必须有空位才能接收新的Store。上游应确保这一点。
    assert(hasFreeSlot, "MDU pendingStores overflow!")
    
    for (i <- 0 until pendingStoreDepth) {
      when(freeSlotOh(i)) {
        pendingStores(i).valid  := True
        pendingStores(i).robPtr := io.storeDispatched.payload
      }
    }
  }

  // b. Store 出队逻辑 (当Store地址解析完成时)
  when(io.storeAddressResolved.valid) {
    for (i <- 0 until pendingStoreDepth) {
      when(pendingStores(i).valid && pendingStores(i).robPtr === io.storeAddressResolved.payload) {
        pendingStores(i).valid := False
      }
    }
  }

  // c. Load 查询逻辑 (组合逻辑)
  val queryCmd = io.queryPort.cmd
  val queryRsp = io.queryPort.rsp
  
  // MDU总是准备好响应查询
  queryCmd.ready := True

  val loadIsBlocked = False
  when(queryCmd.valid) {
    for (pendingStore <- pendingStores) {
      // 检查是否存在一个有效的、且比当前Load更旧的pendingStore
      when(pendingStore.valid && isOlder(pendingStore.robPtr, queryCmd.payload.robPtr)) {
        loadIsBlocked := True
      }
    }
  }
  
  // 驱动响应端口
  queryRsp.canProceed := !loadIsBlocked

  // d. 冲刷逻辑
  when(io.flush) {
    for (slot <- pendingStores) {
      slot.valid := False
    }
  }
}
