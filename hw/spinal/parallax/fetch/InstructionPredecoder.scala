package parallax.fetch

import spinal.core._
import parallax.common.PipelineConfig
import parallax.utilities._

case class PredecodeInfo() extends Bundle with Formattable {
  val isBranch = Bool()
  val isJump = Bool()
  // is a jump with a PC-relative offset known at this stage
  val isDirectJump = Bool()
  // The sign-extended, byte-addressed offset
  val jumpOffset = SInt(32 bits)
  // IDLE instruction detection - causes fetch pipeline to halt
  val isIdle = Bool()

  def setDefault(): this.type = {
    isBranch := False
    isJump := False
    isDirectJump := False
    jumpOffset := 0
    isIdle := False
    this
  }

  def setDefaultForSim(): this.type = {
    import spinal.core.sim._
    isBranch #= false
    isJump #= false
    isDirectJump #= false
    jumpOffset #= 0
    isIdle #= false
    this
  }

  override def format: Seq[Any] = {
    Seq(
      L"PredecodeInfo(",
      L"isBranch -> ", isBranch,
      L", isJump -> ", isJump,
      L", isDirectJump -> ", isDirectJump,
      L", jumpOffset -> ", jumpOffset,
      L", isIdle -> ", isIdle,
      L")"
    )
  }
}

class InstructionPredecoder(pCfg: PipelineConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (pCfg.dataWidth)
    val predecodeInfo = out(PredecodeInfo())
  }

  val opcode = io.instruction(31 downto 26)

  // --- 1. 并行比较，生成各个指令类型的标志位 ---
  // isBranch 指令的 Opcode 列表
  val isBceqzBcnez = (opcode === B"010010")
  val isJirl       = (opcode === B"010011")
  val isBeq        = (opcode === B"010110")
  val isBne        = (opcode === B"010111")
  val isBlt        = (opcode === B"011000")
  val isBge        = (opcode === B"011001")
  val isBltu       = (opcode === B"011010")
  val isBgeu       = (opcode === B"011011")

  // isJump (Direct) 指令的 Opcode 列表
  val isB       = (opcode === B"010100") // 包含 B 和 BL
  // 假设 010101 也是直接跳转，可以像这样添加
  val isBL = (opcode === B"010101")

  // --- 2. 汇总标志位，生成最终输出 (纯组合逻辑，无优先级) ---

  // 如果是条件分支或间接跳转，则 isBranch 为 True
  io.predecodeInfo.isBranch := isBceqzBcnez | isJirl | isBeq | isBne | isBlt | isBge | isBltu | isBgeu

  // 如果是直接跳转 (B/BL)，则 isJump 和 isDirectJump 都为 True
  val isAnyDirectJump = isB | isBL
  io.predecodeInfo.isJump       := isAnyDirectJump
  io.predecodeInfo.isDirectJump := isAnyDirectJump
  io.predecodeInfo.isIdle       := False // 初赛不管了
  if(false) report(L"predecodeInfo: input=${io.instruction}. output ${io.predecodeInfo.format}")

  // --- 3. 计算 jumpOffset (只在需要时有效) ---

  // 25:10 作为低位，9:0 作为高位
  val offs26 = io.instruction(9 downto 0) ## io.instruction(25 downto 10)

  // 符号扩展和移位，这也是无条件的组合逻辑
  val offset = (offs26.asSInt << 2).resize(pCfg.xlen) // 修正：直接移位2，然后符号扩展

  // 只有当指令是直接跳转时，才将计算出的 offset 赋给 jumpOffset
  // 否则 jumpOffset 为 0
  io.predecodeInfo.jumpOffset := Mux(
    isAnyDirectJump,
     offset,
     S(0, pCfg.xlen bits)
  )
}
