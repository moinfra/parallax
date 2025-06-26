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

  // --- 循环队列指针 ---
  val pushPtr = Counter(depth)
  val popPtr = Counter(depth)
  val usage = Reg(UInt(log2Up(depth + 1) bits)) init (0)

  // --- 指令内的读指针 ---
  val instReadPtr = Reg(UInt(instIdxWidth bits)) init (0)

  // --- 状态信号 ---
  io.isEmpty := usage === 0
  io.isFull := usage === depth

  // --- Push 逻辑 ---
  io.push.ready := !io.isFull
  val pushing = io.push.fire
  val popping = io.pop.fire && (usage =/= 0)
  val branchDiscard = (usage =/= 0) && io.popOnBranch
  val isLastInstInPacket = instReadPtr === (ifuCfg.instructionsPerFetchGroup - 1)
  val packetConsumed = popping && isLastInstInPacket

  when(pushing) {
    val newSlot = BufferSlot()
    newSlot.pc := io.push.payload.pc
    newSlot.data := io.push.payload.instructions
    newSlot.fault := io.push.payload.fault
    buffer.write(pushPtr.value, newSlot)
    ParallaxSim.log(L"[FetchBuffer] PUSHED packet for PC=0x${io.push.payload.pc} to slot ${pushPtr.value}. Usage: ${usage + 1}")
  }

  // --- Pop 逻辑 ---
  val canPop = usage =/= 0
  val currentPacket = buffer.readAsync(popPtr.value)
  val currentPc = currentPacket.pc + (instReadPtr << log2Up(pCfg.dataWidth.value / 8)).resized
  val currentInstruction = currentPacket.data(instReadPtr)

  io.pop.valid := canPop
  io.pop.payload.pc := currentPc
  io.pop.payload.instruction := currentInstruction
  io.pop.payload.isFirstInGroup := (instReadPtr === 0)
  io.pop.payload.hasFault := currentPacket.fault

  // =========================================================
  //  控制与指针更新逻辑 (V3 核心改进)
  // =========================================================
  when(io.flush) {
    pushPtr.clear()
    popPtr.clear()
    usage := 0
    instReadPtr := 0
    ParallaxSim.log(L"[FetchBuffer] FLUSH received. All pointers and usage cleared.")
  } otherwise {
    // 优先级 2: Pop on Branch (丢弃当前包)
    when(branchDiscard) {
      popPtr.increment()
      usage := Mux(pushing, usage, usage - 1)
      instReadPtr := 0
      ParallaxSim.log(L"[FetchBuffer] POP_ON_BRANCH. Discarding rest of packet in slot ${popPtr.value}.")
    } otherwise {
      // 优先级 3: 正常的Push/Pop行为
      when(pushing && !io.isFull) {
        pushPtr.increment()
      }
      when(packetConsumed) {
        popPtr.increment()
      }
      // usage只在此处赋值
      val nextUsage = usage + (pushing.asUInt - (popping && !branchDiscard).asUInt)
      usage := nextUsage
      // instReadPtr只在此处赋值
      when(popping && !branchDiscard) {
        when(isLastInstInPacket) {
          instReadPtr := 0
        } otherwise {
          when(instReadPtr =/= (ifuCfg.instructionsPerFetchGroup - 1)) {
            instReadPtr := instReadPtr + 1
          }
        }
      }
    }
  }

  if (GenerationFlags.simulation) {
    assert(!(io.push.fire && io.isFull), "Push to a full FetchBuffer is not allowed!")
    assert(!(io.pop.fire && io.isEmpty), "Pop from an empty FetchBuffer is not allowed!")
  }
}
