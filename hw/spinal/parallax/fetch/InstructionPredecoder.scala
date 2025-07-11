package parallax.fetch

import spinal.core._
import parallax.common.PipelineConfig

case class PredecodeInfo() extends Bundle {
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
}

class InstructionPredecoder(pCfg: PipelineConfig) extends Component {
  val io = new Bundle {
    val instruction = in Bits (pCfg.dataWidth)
    // 输出现在是一个完整的 PredecodeInfo bundle
    val predecodeInfo = out(PredecodeInfo())
  }

  val opcode = io.instruction(31 downto 26)
  
  // IDLE instruction pattern: 0000011 00100 10001 level[14:0]
  val opcode_7b = io.instruction(31 downto 25)
  val idle_fixed_bits = io.instruction(24 downto 15)

  // 默认值
  io.predecodeInfo.isBranch := False
  io.predecodeInfo.isJump := False
  io.predecodeInfo.isDirectJump := False
  io.predecodeInfo.jumpOffset := 0
  io.predecodeInfo.isIdle := False

  // 根据手册，offs26 是由 offs[15:0] 和 offs[25:16] 拼接而成
  // offs[15:0] 位于 instruction[15:0]  
  // offs[25:16] 位于 instruction[25:16]
  val offs26 = io.instruction(25 downto 16) ## io.instruction(15 downto 0)

  // 计算偏移量：{offs26, 2'b0} 然后符号扩展 - 只在需要时计算
  val offset = Cat(offs26, B"00").asSInt.resize(pCfg.xlen)
  
  // IDLE instruction detection
  when(opcode_7b === B"0000011" && idle_fixed_bits === B"0010010001") {
    io.predecodeInfo.isIdle := True
    report(L"Found IDLE instruction: ${io.instruction.asUInt}")
  }

  switch(opcode) {
    // --- Conditional Branches & Indirect Jumps (isBranch = True) ---
    is(B"010010", B"010011", B"010110", B"010111", B"011000", B"011001", B"011010", B"011011") {
      io.predecodeInfo.isBranch := True
      // 分支指令的jumpOffset保持为0，因为它们不是直接跳转
    }

    // --- Unconditional Direct Jumps (isJump = True, isDirectJump = True) ---
    is(B"010100", B"010101") { // B and BL
      io.predecodeInfo.isJump := True
      io.predecodeInfo.isDirectJump := True
      io.predecodeInfo.jumpOffset := offset  // 只有直接跳转指令才设置jumpOffset
    }
    
    // --- 默认情况 ---
    default {
      // 保持默认值：所有predecode字段都为False/0
      // jumpOffset保持为0，避免使用指令中的随机位
    }
  }
}
