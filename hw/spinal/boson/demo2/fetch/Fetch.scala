package boson.demo2.fetch

import boson.utilities.{Plugin, LockedImpl, Service}
import boson.demo2.common.Config
import boson.demo2.frontend.FrontendPipelineKeys
import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.{Pipeline, Stage, Connection}
import boson.demo2.components.memory._

class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val s0_PcGen = newStage().setName("s0_PcGen") // PC Generation
    val s1_Fetch = newStage().setName("s1_Fetch") // Instruction Fetch
    connect(s0_PcGen, s1_Fetch)(Connection.M2S())
    build()
  }
  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.s0_PcGen
  def exitStage: Stage = pipeline.s1_Fetch
}

// Fetch0Plugin: Generates PC
class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val s0_PcGen = setup.fetchPipeline.pipeline.s0_PcGen

    val pcReg = Reg(UInt(Config.XLEN bits)) init (0) // Hardware reset to 0
    val pcPlus4 = pcReg + 4

    val redirectValid = s0_PcGen(FrontendPipelineKeys.REDIRECT_PC_VALID)
    val redirectPc = s0_PcGen(FrontendPipelineKeys.REDIRECT_PC)

    // --- Initialization Logic (Simplified) ---
    val bootDelay = 2 // 复位后等待2个周期
    val initCounter = Reg(UInt(log2Up(bootDelay + 1) bits)) init (0)
    val booted = Reg(Bool()) init (False) // 使用一个寄存器来锁存booted状态

    when(!booted) {
      initCounter := initCounter + 1
      when(initCounter === bootDelay) {
        booted := True
      }
    }

    // --- PC Generation Logic ---
    val pcLogic = new Area {
      val currentPc = pcReg
      val pcToLoad = Mux(redirectValid, redirectPc, pcPlus4)

      when(booted) { // 只在 booted 后才更新 pcReg
        when(s0_PcGen.isFiring || redirectValid) {
          pcReg := pcToLoad
        }
      }

      // s0_PcGen is valid only after booting and if not halted by fetcher (if such signal existed)
      val isValidToStage = booted //  && !fetcherHalt (if you had one)
    }

    // --- Connect PC Generation Logic to s0_PcGen Stage ---
    s0_PcGen.valid := pcLogic.isValidToStage
    s0_PcGen.isReady := True // PCGen is always ready to accept input
    report(
      L"[Fetch0Plugin] s0_PcGen.valid: ${s0_PcGen.valid} booted: ${booted} redirectValid: ${redirectValid} pc: ${pcReg}"
    )
    s0_PcGen(FrontendPipelineKeys.PC) := pcLogic.currentPc
  }
}

// Fetch1Plugin: Instantiates InstructionFetchUnit and performs the fetch
class Fetch1Plugin(val ifuConfig: InstructionFetchUnitConfig) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val memPlugin = getService[SimpleMemoryService]
    val fetchPipeline = getService[FetchPipeline]
    val s0_PcGen = fetchPipeline.pipeline.s0_PcGen
    val s1_Fetch = fetchPipeline.pipeline.s1_Fetch
    val ifu = new InstructionFetchUnit(ifuConfig)
    ifu.io.memBus <> memPlugin.memBus
  }
// 问题：ifu 可能需要多个周期才能返回，如何确保 ifu 请求期间，s1 不再接受新的请求？
  val logic = create late new Area {
    val s1 = setup.s1_Fetch // 使用短别名
    val ifu = setup.ifu

    // 状态：指示 S1 是否已经发送了 PC 给 IFU 并且正在等待 IFU 的数据返回
    val s1_is_busy_waiting_for_ifu = Reg(Bool()) init(False) setName("s1_is_busy_waiting_for_ifu")

    // 驱动 IFU 的输入 (pcIn Stream)
    // 只有当 S1 有效，并且 S1 当前不处于“等待IFU返回”状态时，才向 IFU 发送新的 PC 请求
    ifu.io.pcIn.valid   := s1.isValid && !s1_is_busy_waiting_for_ifu
    ifu.io.pcIn.payload := s1(FrontendPipelineKeys.PC) // PC 来自 S1 的输入

    // 更新 s1_is_busy_waiting_for_ifu 状态
    when(!s1.isValid) { // 如果 S1 本身无效 (例如被冲刷，或上游 S0 没有有效数据)
      s1_is_busy_waiting_for_ifu := False
    } .elsewhen(ifu.io.pcIn.fire) { // IFU 接受了 PC (s1.isValid && !s1_is_busy && ifu.io.pcIn.ready)
      s1_is_busy_waiting_for_ifu := True
    } .elsewhen(s1_is_busy_waiting_for_ifu && ifu.io.dataOut.fire) { // IFU 返回了数据且 S1 消耗了它
      s1_is_busy_waiting_for_ifu := False
    }
    // 注意: .elsewhen 的顺序很重要。
    // 1. 如果S1无效，清除busy状态 (最高优先级)。
    // 2. 否则，如果IFU接受了新的PC，设置busy状态。
    // 3. 否则，如果IFU正忙且返回了数据，清除busy状态。

    // S1 阶段的暂停逻辑 (haltBySelf)
    val stall_s1_condition = s1.isValid && ( // S1 必须有有效数据才考虑暂停
      (!s1_is_busy_waiting_for_ifu && !ifu.io.pcIn.ready) || // S1 想发送新PC，但IFU未准备好接收
      (s1_is_busy_waiting_for_ifu && !ifu.io.dataOut.valid)    // S1 已发送PC，正在等待IFU数据返回
    )
    s1.haltIt(stall_s1_condition)

    // 将 IFU 的输出连接到 S1 阶段的输出 PipelineKey
    // 这些赋值仅在 s1.isFiring 时真正生效并传递到下一级
    s1(FrontendPipelineKeys.PC)          := ifu.io.dataOut.payload.pc
    s1(FrontendPipelineKeys.INSTRUCTION) := ifu.io.dataOut.payload.instruction
    s1(FrontendPipelineKeys.FETCH_FAULT) := ifu.io.dataOut.payload.fault

    // S1 是否准备好消耗 IFU 的输出数据 (ifu.io.dataOut.ready)
    // 当 S1 期望从 IFU 获取数据 (s1_is_busy_waiting_for_ifu 为 true)
    // 并且 S1 本身能够将数据发射到下游 (s1.output.ready 为 true) 时，
    // S1 就准备好接收 IFU 的数据了。
    // 同时，S1 本身也需要是有效的。
    val s1_can_consume_ifu_data = s1.internals.output.ready && s1.isValid && s1_is_busy_waiting_for_ifu &&
                                  !(!s1_is_busy_waiting_for_ifu && !ifu.io.pcIn.ready) // 确保不是因为pcIn.ready低而stall
                                  // 上面最后一行可以简化，因为如果pcIn.ready低导致stall, s1_is_busy_waiting_for_ifu 不会为真（对于当前指令）
    ifu.io.dataOut.ready := s1.internals.output.ready &&s1.isValid && s1_is_busy_waiting_for_ifu

    when(s1.isFiring) {
      report(L"[Fetch1Plugin] S1 FIRING: PC_in_s1_input_reg=${s1(FrontendPipelineKeys.PC)} DataOut.PC=${ifu.io.dataOut.payload.pc} DataOut.INSTR=${ifu.io.dataOut.payload.instruction}")
    }
  }
}
