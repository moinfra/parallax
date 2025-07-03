package parallax.components.memory

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._

// FetchBufferOutput 和 FetchBufferOutputCapture 不再需要，因为我们将直接使用 IFetchRsp
// case class FetchBufferOutput(...)
// case class FetchBufferOutputCapture(...)

// IO 定义更新: pop 的 payload 现在是 IFetchRsp
case class FetchBufferIo(ifuCfg: InstructionFetchUnitConfig) extends Bundle with IMasterSlave{
  // push 和 pop 的数据类型对称，都是一个取指响应包
  val push = slave Stream (IFetchRsp(ifuCfg))
  val pop = master Stream (IFetchRsp(ifuCfg))
  val flush = in Bool ()
  val popOnBranch = in Bool () // 当分支发生时，丢弃当前缓冲的指令包
  val isEmpty = out Bool ()
  val isFull = out Bool ()

  override def asMaster(): Unit = {
    slave(pop)
    out(push, flush, popOnBranch)
    in(isEmpty, isFull)
  }


}

/**
 * FetchBuffer - 指令预取缓冲区 (按组操作)
 *
 * 该缓冲区以“取指组” (Fetch Group / Packet) 为单位进行操作。
 * 每次 push 推入一个完整的 IFetchRsp。
 * 每次 pop 弹出一个完整的 IFetchRsp。
 *
 * @param ifuCfg 指令获取单元配置
 * @param depth  缓冲区的深度（必须是2的幂）
 */
class FetchBuffer(
    ifuCfg: InstructionFetchUnitConfig,
    depth: Int = 4
) extends Component {

  require(depth > 1 && isPow2(depth), "FetchBuffer depth must be a power of 2")

  // 注意：pCfg 不再直接需要，因为 IFetchRsp 已经由 ifuCfg 完全定义
  val io = FetchBufferIo(ifuCfg)

  // --- 内部存储 ---
  // BufferSlot 的内容直接对应 IFetchRsp，简化映射
  val buffer = Mem(IFetchRsp(ifuCfg), depth)

  // --- 循环队列指针和状态 ---
  val pushPtr = Counter(depth)
  val popPtr = Counter(depth)
  // usage 追踪有多少个“组”在缓冲区中
  val usage = Reg(UInt(log2Up(depth + 1) bits)) init (0)

  // --- 状态信号 ---
  io.isEmpty := usage === 0
  io.isFull := usage === depth

  // --- Push/Pop 事件 ---
  val pushing = io.push.fire
  val popping = io.pop.fire
  
  // popOnBranch 信号用于丢弃缓冲区头部的项目，即使下游不接收 (pop.ready 为 false)
  // 当缓冲区非空且收到 popOnBranch 指令时，头部项目被丢弃
  val discardingOnBranch = io.popOnBranch && !io.isEmpty
  
  // 头部的一个“组”被消耗掉的条件：
  // 1. 下游成功接收 (popping)
  // 2. 或者，因分支跳转被强制丢弃 (discardingOnBranch)
  val headConsumed = popping || discardingOnBranch
  
  // --- Push/Pop 握手逻辑 ---
  io.push.ready := !io.isFull
  io.pop.valid := !io.isEmpty

  // --- 输出组合逻辑 ---
  // 直接从 buffer 读取整个 IFetchRsp 包
  io.pop.payload := buffer.readAsync(popPtr.value)

  // ====================== 核心逻辑 ======================

  // Flush 具有最高优先级
  when(io.flush) {
    pushPtr.clear()
    popPtr.clear()
    usage := 0
  } otherwise {

  // +++ START: 增加诊断日志 +++
  val usageWillUpdate = pushing =/= headConsumed
  val usageWillIncrement = usageWillUpdate && pushing
  val usageWillDecrement = usageWillUpdate && !pushing

  ParallaxSim.debug(
    Seq(
      L"[[FB-DBG]] Usage Logic | ",
      L"pushing=${pushing} ",
      L"popping=${popping} ",
      L"discardingOnBranch=${discardingOnBranch} ",
      L"-> headConsumed=${headConsumed} | ",
      L"cond(pushing =/= headConsumed)=${usageWillUpdate} | ",
      L"usage_in=${usage} -> will_inc=${usageWillIncrement}, will_dec=${usageWillDecrement}"
    )
  )
  // +++ END: 增加诊断日志 +++

    // 1. usage 更新
    // 根据推入和消耗事件更新计数器
    // - pushing 和 headConsumed 可能同时发生，此时 usage 不变
    // - 只有 pushing 时, usage + 1
    // - 只有 headConsumed 时, usage - 1
    when(pushing =/= headConsumed) {
      when(pushing) {
        usage := usage + 1
      } otherwise {
        usage := usage - 1
      }
    }

    // 2. Push 指针和写操作
    when(pushing) {
      buffer.write(pushPtr.value, io.push.payload)
      pushPtr.increment()
    }

    // 3. Pop 指针更新
    // 只要头部的包被消耗（正常弹出或分支丢弃），popPtr 就前进
    when(headConsumed) {
      popPtr.increment()
    }
  }
  // =========================================================

  // --- 详细日志 ---
  // ParallaxSim.log(
  //   Seq(
  //     L"[[FB]] ",
  //     L"push(v=${io.push.valid},r=${io.push.ready},fire=${pushing}) ",
  //     L"pop(v=${io.pop.valid},r=${io.pop.ready},fire=${popping}) ",
  //     L"flush=${io.flush} popOnBranch=${io.popOnBranch} | ",
  //     L"State(usage=${usage}, pushPtr=${pushPtr.value}, popPtr=${popPtr.value}) | ",
  //     L"Conditions(headConsumed=${headConsumed}, discardingOnBranch=${discardingOnBranch})"
  //   )
  // )

  if (GenerationFlags.simulation) {
    // 检查：不能在缓冲区满时推入
    assert(!(io.push.valid && io.isFull), "Push to a full FetchBuffer is not allowed!")
    // 检查：不能在下游未准备好时推入
    assert(!(pushing && !io.push.ready), "Pushing should not happen when not ready!")
    // 检查：不能在缓冲区空时弹出
    assert(!(popping && io.isEmpty), "Pop from an empty FetchBuffer is not allowed!")
    // 检查：popOnBranch 和 flush 不应同时有效，因为 flush 优先级更高
    assert(!(io.popOnBranch && io.flush), "popOnBranch and flush should not be asserted simultaneously")
  }
}
