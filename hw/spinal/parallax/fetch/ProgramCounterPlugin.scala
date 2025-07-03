package parallax.fetch

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig
// import parallax.components.memory.InstructionFetchUnitConfig // 不再需要

// IO Bundle 保持不变
case class ProgramCounterIo(pCfg: PipelineConfig) extends Bundle with IMasterSlave {
  val softRedirect = in(Flow(UInt(pCfg.pcWidth)))
  val hardRedirect = in(Flow(UInt(pCfg.pcWidth)))
  val pcStream = master(Stream(UInt(pCfg.pcWidth)))

  override def asMaster(): Unit = {
    out(pcStream)
    in(softRedirect, hardRedirect)
  }
}

class ProgramCounter(
    pCfg: PipelineConfig,
    enableLog: Boolean = true // +++ 新增参数 +++
) extends Component {

  val io = ProgramCounterIo(pCfg)
  val pcReg = Reg(UInt(pCfg.pcWidth)) init (pCfg.resetVector)

  val sequentialPc = pcReg + pCfg.fetchGroupBytes
  
  val nextPc = Mux(
    sel      = io.hardRedirect.valid,
    whenTrue = io.hardRedirect.payload,
    whenFalse = Mux(
      sel      = io.softRedirect.valid,
      whenTrue = io.softRedirect.payload,
      whenFalse = sequentialPc
    )
  )

  val isRedirecting = io.hardRedirect.valid || io.softRedirect.valid

  io.pcStream.valid   := !isRedirecting
  io.pcStream.payload := pcReg

  // --- PC 寄存器更新 ---
  // +++ 将更新条件提取为信号，便于观察 +++
  val canFire = io.pcStream.ready
  val willUpdate = isRedirecting || canFire

  when(willUpdate) {
    pcReg := nextPc
  }

  // +++ 增加详细的日志打印 +++
  if (enableLog) {
    val log = new Area {
      // 使用 report 在每个时钟周期的组合逻辑求值后打印信息
      report(Seq(
        "[[PC-DBG]] ",
        "pcReg=0x", pcReg, " | ",
        "pcStream.payload=0x", io.pcStream.payload, " | ",
        "pcStream.valid=", io.pcStream.valid, " | ",
        "pcStream.ready=", io.pcStream.ready, " | ",
        "isRedirecting=", isRedirecting, " | ",
        "canFire=", canFire, " | ",
        "willUpdate=", willUpdate, " | ",
        "nextPc=0x", nextPc
      ))
    }
  }
}
