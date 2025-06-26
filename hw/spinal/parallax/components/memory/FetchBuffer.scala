package parallax.components.memory

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._

case class FetchBufferOutput(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig) extends Bundle {
  val pc = UInt(pCfg.pcWidth)
  val instruction = Bits(pCfg.dataWidth)
  val isFirstInGroup = Bool()
  val hasFault = Bool() // << 新增: 传递取指错误
}

case class FetchBufferOutputCapture(
    val pc: BigInt,
    val instruction: BigInt,
    val isFirstInGroup: Boolean,
    val hasFault: Boolean
) {}

object FetchBufferOutputCapture {
  def apply(output: FetchBufferOutput): FetchBufferOutputCapture = {
    import spinal.core.sim._
    FetchBufferOutputCapture(
      output.pc.toBigInt,
      output.instruction.toBigInt,
      output.isFirstInGroup.toBoolean,
      output.hasFault.toBoolean
    )
  }
}

// IO定义基本不变
case class FetchBufferIo(pCfg: PipelineConfig, ifuCfg: InstructionFetchUnitConfig) extends Bundle with IMasterSlave {
  val push = slave Stream (IFetchRsp(ifuCfg))
  val pop = master Stream (FetchBufferOutput(pCfg, ifuCfg))
  val flush = in Bool ()
  val popOnBranch = in Bool ()
  val isEmpty = out Bool ()
  val isFull = out Bool ()

  override def asMaster(): Unit = {
    slave(pop)
    out(push, flush, popOnBranch)
    in(isEmpty, isFull)
  }
}

// filename: 
// filename: parallax/components/fetch/FetchBuffer.scala

// filename: parallax/components/fetch/FetchBuffer.scala

class FetchBuffer(
    pCfg: PipelineConfig,
    ifuCfg: InstructionFetchUnitConfig,
    depth: Int = 4
) extends Component {

  require(depth > 1 && isPow2(depth), "FetchBuffer depth must be a power of 2")
  val io = FetchBufferIo(pCfg, ifuCfg)

  val instIdxWidth = log2Up(ifuCfg.instructionsPerFetchGroup)

  // --- 内部存储 ---
  case class BufferSlot() extends Bundle {
    val pc = UInt(pCfg.pcWidth)
    val data = Vec(Bits(pCfg.dataWidth), ifuCfg.instructionsPerFetchGroup)
    val fault = Bool()
  }
  val buffer = Mem(BufferSlot(), depth)

  // --- 循环队列指针和状态 ---
  val pushPtr = Counter(depth)
  val popPtr = Counter(depth)
  val usage = Reg(UInt(log2Up(depth + 1) bits)) init (0)
  val instReadPtr = Reg(UInt(instIdxWidth bits)) init (0)

  // --- 状态信号 ---
  io.isEmpty := usage === 0
  io.isFull := usage === depth

  // --- Push/Pop 事件 ---
  val instReadPtrMax = U(ifuCfg.instructionsPerFetchGroup - 1, instReadPtr.getWidth bits)
  
  io.push.ready := (usage =/= depth)
  val pushing = io.push.fire

  io.pop.valid := (usage =/= 0)
  val popping = io.pop.fire

  // --- 输出组合逻辑 ---
  val currentPacket = buffer.readAsync(popPtr.value)
  val currentPc = currentPacket.pc + (instReadPtr << log2Up(pCfg.dataWidth.value / 8)).resized
  val currentInstruction = currentPacket.data(instReadPtr)

  io.pop.payload.pc := currentPc
  io.pop.payload.instruction := currentInstruction
  io.pop.payload.isFirstInGroup := (instReadPtr === 0)
  io.pop.payload.hasFault := currentPacket.fault

  // ====================== 核心逻辑修复 ======================
  // --- 定义独立的消耗/丢弃条件 ---

  // 条件1: 正常弹出一个 slot 的最后一条指令
  val slotNormallyConsumed = popping && (instReadPtr === instReadPtrMax)

  // 条件2: 丢弃当前 slot (因分支)，此操作独立于 pop.fire
  // 只要 popOnBranch 为高，并且缓冲区非空，就执行丢弃
  val slotDiscardedOnBranch = io.popOnBranch && (usage > 0)

  // 任何一个条件满足，都意味着一个 slot 被消耗/丢弃了
  val slotConsumedOrDiscarded = slotNormallyConsumed || slotDiscardedOnBranch

  // --- Flush 优先 ---
  when(io.flush) {
    pushPtr.clear()
    popPtr.clear()
    usage := 0
    instReadPtr := 0
  } otherwise {
    // 1. usage 更新
    // 注意：这里的 pushing 和 slotConsumedOrDiscarded 是互斥的吗？
    // 一个周期内，可能既 pushing，又 slotDiscardedOnBranch。
    // 所以，需要分别计算增量和减量。
    val usageWillIncrement = pushing
    val usageWillDecrement = slotConsumedOrDiscarded
    
    when(usageWillIncrement && !usageWillDecrement) {
      usage := usage + 1
    } .elsewhen(!usageWillIncrement && usageWillDecrement) {
      usage := usage - 1
    } // 如果同时发生或都不发生，usage 不变

    // 2. Pop 指针更新
    when(slotConsumedOrDiscarded) {
      // 无论是正常消耗还是分支丢弃，都移动到下一个 slot
      popPtr.increment()
      instReadPtr := 0
    } .elsewhen(popping) {
      // 只有在没有消耗/丢弃整个 slot 的情况下，才在 slot 内部移动
      instReadPtr := instReadPtr + 1
    }

    // 3. Push 指针和写操作
    when(pushing) {
      val newSlot = BufferSlot()
      newSlot.pc := io.push.payload.pc
      newSlot.data := io.push.payload.instructions
      newSlot.fault := io.push.payload.fault
      buffer.write(pushPtr.value, newSlot)
      pushPtr.increment()
    }
  }
  // =========================================================

  // --- 详细日志 ---
  // 为了调试，我们把新信号也加到日志里
  ParallaxSim.log(
    Seq(
      L"[[FB]] ",
      L"push(v=${io.push.valid},r=${io.push.ready},fire=${pushing}) ",
      L"pop(v=${io.pop.valid},r=${io.pop.ready},fire=${popping}) ",
      L"flush=${io.flush} popOnBranch=${io.popOnBranch} | ",
      L"State(usage=${usage}, pushPtr=${pushPtr.value}, popPtr=${popPtr.value}, instReadPtr=${instReadPtr}) | ",
      L"Conditions(consumed=${slotNormallyConsumed}, discarded=${slotDiscardedOnBranch})"
    )
  )

  if (GenerationFlags.simulation) {
    assert(!(io.push.fire && io.isFull), "Push to a full FetchBuffer is not allowed!")
    assert(!(io.pop.fire && io.isEmpty), "Pop from an empty FetchBuffer is not allowed!")
  }
}
