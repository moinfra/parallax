package parallax.fetch2

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import parallax.common._
import parallax.utilities._
import parallax.bpu.{BpuQuery, BpuResponse}
import parallax.fetch.{FetchedInstr, PredecodeInfo}
import parallax.fetch.InstructionPredecoder

class SmartDispatcher(pCfg: PipelineConfig) extends Component {
  val io = new Bundle {
    // 输入流类型改变，接收原始指令组
    val fetchGroupIn = slave Stream (RawFetchGroup(pCfg))
    val bpuRsp = slave Flow (BpuResponse(pCfg))
    val fetchOutput = master Stream (FetchedInstr(pCfg))
    val bpuQuery = master Flow (BpuQuery(pCfg))
    val softRedirect = master Flow (UInt(pCfg.pcWidth))
    val flush = in Bool ()
  }

  val enableLog = false
  val cycleReg = Reg(UInt(32 bits)) init (0)
  cycleReg := cycleReg + 1
  if (enableLog) {
    report(L"------ RAW INSTR DISPATCHER CYCLE: ${cycleReg}")
  }

  // --- 内部状态 ---
  // fetchGroupReg 保持为 FetchGroup 类型，因为它将存储预解码后的完整信息
  val fetchGroupReg = Reg(FetchGroup(pCfg))
  val isBusyReg = Reg(Bool()) init (False)
  val dispatchIndexReg = Reg(UInt(log2Up(pCfg.fetchWidth) bits)) init (0)

  val bpuInFlightCounterReg = Reg(UInt(log2Up(pCfg.fetchWidth + 1) bits)) init (0)
  val bpuTransIdCounterReg = Reg(UInt(pCfg.bpuTransactionIdWidth)) init (0)
  val pendingBpuQueryReg = Reg(BranchInfo(pCfg))
  val outputReg = Reg(FetchedInstr(pCfg))

  // --- 默认输出 ---
  io.fetchOutput.setIdle()
  io.bpuQuery.setIdle()
  io.softRedirect.setIdle()

  // --- 输入握手 ---
  io.fetchGroupIn.ready := !isBusyReg

  // --- 当前指令信息 (组合逻辑) ---
  // 这部分逻辑现在完全不需要修改，因为它直接从 fetchGroupReg 中读取预解码信息，
  // 而我们将在接收时填充好 fetchGroupReg。
  val pcIncrReg = (dispatchIndexReg << log2Up(pCfg.dataWidth.value / 8))
  val currentPcReg = fetchGroupReg.pc + pcIncrReg
  val currentInstructionReg = fetchGroupReg.instructions(dispatchIndexReg)
  val currentPredecodeReg = fetchGroupReg.predecodeInfos(dispatchIndexReg)
  val lastInstructionIndexInGroup = fetchGroupReg.startInstructionIndex + fetchGroupReg.numValidInstructions - 1
  val hasValidInstructionReg = fetchGroupReg.numValidInstructions > 0
  val isLastInstructionReg = hasValidInstructionReg && dispatchIndexReg === lastInstructionIndexInGroup

  // 添加更详细的日志
  if (enableLog) {
    report(L"  DEBUG-VALID: fetchGroupReg numValidInstructions is ${fetchGroupReg.numValidInstructions}, last cycle it is ${RegNext(fetchGroupReg.numValidInstructions, U(0))}")
    report(L"  DEBUG: currentPc=0x${currentPcReg}, currentInstructionReg=0x${currentInstructionReg}, currentPredecodeReg.isBranch=${currentPredecodeReg.isBranch}, currentPredecodeReg.isDirectJump=${currentPredecodeReg.isDirectJump}")
    report(L"  DEBUG: fetchGroupReg.numValidInstructions=${fetchGroupReg.numValidInstructions}, dispatchIndexReg=${dispatchIndexReg}")
    report(L"  DEBUG: isLastInstruction=${isLastInstructionReg}")
  }

  when(io.flush) {
    fetchGroupReg.setDefault()
  }

  when(io.bpuQuery.fire) {
    bpuInFlightCounterReg := bpuInFlightCounterReg + 1
    bpuTransIdCounterReg := bpuTransIdCounterReg + 1
  }

  when(io.bpuRsp.fire) {
    bpuInFlightCounterReg := bpuInFlightCounterReg - 1
  }

  // --- 主分发状态机 (零延迟决策版) ---
  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val DISPATCHING = new State
    val WAITING_FOR_BPU = new State
    val SEND_BRANCH = new State
    val DRAINING_BPU = new State
    setEncoding(binaryOneHot)

    IDLE.whenIsActive {
      when(io.flush) {
        // 在 IDLE 状态，flush 意味着什么都不做，保持 IDLE
        if (enableLog) report(L"DISPATCHER-IDLE: Flush asserted, remaining in IDLE.")
        goto(IDLE)
      }.elsewhen(io.fetchGroupIn.fire) {
        // =======================================================================
        // 关键修改点：所有在本周期内的决策，都必须使用 io.fetchGroupIn (组合输入)
        // =======================================================================

        val rawGroup = io.fetchGroupIn.payload
        val startIdx = rawGroup.startInstructionIndex

        // --- 组合逻辑路径：用于本周期的决策 ---
        // 1. 对整个输入组进行并行预解码 (组合逻辑)
        val decodedInfos = Vec.tabulate(pCfg.fetchWidth) { i =>
          val tempPredecoder = new InstructionPredecoder(pCfg)
          tempPredecoder.io.instruction := rawGroup.instructions(i)
          tempPredecoder.io.predecodeInfo
        }

        // 2. 提取第一条指令的预解码信息，用于立即决策
        val firstPredecode = decodedInfos(startIdx)
        val firstPC = rawGroup.firstPc
        val firstInstruction = rawGroup.instructions(startIdx)

        // --- 寄存器更新路径：为下一周期准备数据 ---
        // 在同一时刻，准备好要锁存到 fetchGroupReg 的完整数据
        fetchGroupReg.pc             := rawGroup.pc
        fetchGroupReg.instructions   := rawGroup.instructions
        fetchGroupReg.fault          := rawGroup.fault
        fetchGroupReg.numValidInstructions := rawGroup.numValidInstructions
        fetchGroupReg.startInstructionIndex := startIdx
        fetchGroupReg.predecodeInfos := decodedInfos // 使用刚刚计算出的组合结果
        fetchGroupReg.branchMask     := B(decodedInfos.map(_.isBranch).reverse)

        // 更新其他状态寄存器
        isBusyReg := True
        dispatchIndexReg := startIdx


        if (enableLog) {
          report(L"DISPATCHER-IDLE: New Group Received. PC=0x${io.fetchGroupIn.payload.pc}.")
          report(L"  DEBUG-IDLE-LOAD-PAYLOAD: io.fetchGroupIn.payload.numValidInstructions=${io.fetchGroupIn.payload.numValidInstructions}")
        }

        // --- NEW LOGIC START: Handle empty FetchGroup explicitly ---
        when(rawGroup.numValidInstructions === 0) {
          if (enableLog) report(L"DISPATCHER-IDLE: Received empty FetchGroup (numValidInstructions=0). Going back to IDLE.")
          isBusyReg := False // Not busy for an empty group
          dispatchIndexReg := 0 // Ensure dispatchIndexReg is reset
          goto(IDLE) // Go back to IDLE immediately, do nothing else
        } .otherwise {
          // =================================================================
          // 决策逻辑完全复用原始代码，但现在它安全地作用于组合逻辑结果！
          // =================================================================
          if (enableLog) report(L"DISPATCHER-IDLE: Analyzing first instruction (PC=0x${firstPC}, isBranch=${firstPredecode.isBranch}).")

          // Fast Path for first instruction
          when(!firstPredecode.isBranch) {
            if (enableLog) report(L"DISPATCHER-IDLE: First instruction is Fast Path. PC=0x${firstPC}")
            io.fetchOutput.valid := True
            io.fetchOutput.payload.pc := firstPC
            io.fetchOutput.payload.instruction := firstInstruction
            io.fetchOutput.payload.predecode := firstPredecode
            io.fetchOutput.payload.bpuPrediction.setDefault()

            when(io.fetchOutput.ready) {
              val redirecting = firstPredecode.isDirectJump
              if (enableLog) report(L"DISPATCHER-IDLE: First instruction (fast path) fired.")
              when(redirecting) {
                io.softRedirect.valid := True
                io.softRedirect.payload := firstPC + firstPredecode.jumpOffset.asUInt // FIXME: 这个运算非常重量级
                report(L"DISPATCHER-IDLE: First instruction is a direct jump. Redirecting to 0x${io.softRedirect.payload}.")
                dispatchIndexReg := 0
                isBusyReg := False
                goto(IDLE)
              } otherwise {
                // Corrected condition: is first instruction the ONLY instruction in the group?
                val isFirstInstructionLast = rawGroup.numValidInstructions === 1 // Using rawGroup which is current input
                if (enableLog) report(L"  DEBUG-IDLE-FIRST-ISLAST: rawGroup.numValidInstructions=${rawGroup.numValidInstructions}, isFirstInstructionLast=${isFirstInstructionLast} beacuse startIdx=${startIdx}")

                when(isFirstInstructionLast) {
                  if (enableLog) report(L"  DEBUG-IDLE-FAST: First instruction is last in group (not branch/redirect), Group finished. -> IDLE")
                  isBusyReg := False
                  dispatchIndexReg := 0
                  goto(IDLE)
                } otherwise {
                  dispatchIndexReg := startIdx + 1
                  if (enableLog) report(L"  DEBUG-IDLE-FAST: dispatchIndexReg set to ${dispatchIndexReg} + 1, FSM to DISPATCHING")
                  goto(DISPATCHING)
                }
              }
            } otherwise {
              if (enableLog) report(L"DISPATCHER-IDLE: First instruction (fast path) stalled. -> SEND_BRANCH")
              if (enableLog) report(L"  DEBUG-IDLE-STALL: FSM to SEND_BRANCH")
              // Ensure outputReg is properly populated for the stalled instruction
              outputReg.pc := firstPC
              outputReg.instruction := firstInstruction
              outputReg.predecode := firstPredecode
              outputReg.bpuPrediction.setDefault() // Stalled fast path means no prediction yet
              goto(SEND_BRANCH) // Re-use SEND_BRANCH to send the stalled output
            }
          }
            // Slow Path for first instruction
            .otherwise {
              if (enableLog)
                report(L"DISPATCHER-IDLE: First instruction is Slow Path. PC=0x${firstPC}")
              io.bpuQuery.valid := True
              io.bpuQuery.payload.pc := firstPC
              io.bpuQuery.payload.transactionId := bpuTransIdCounterReg

              // Store details of the instruction waiting for BPU response
              pendingBpuQueryReg.pc := firstPC
              pendingBpuQueryReg.instruction := firstInstruction
              pendingBpuQueryReg.predecodeInfo := firstPredecode
              pendingBpuQueryReg.bpuTransactionId := bpuTransIdCounterReg

              if (enableLog) ParallaxSim.notice(L"DISPATCHER-IDLE: BPU Query sent for first instruction. -> WAITING_FOR_BPU")
              goto(WAITING_FOR_BPU)
            }
        } // --- NEW LOGIC END: for non-empty FetchGroup ---
      }
    }

    DISPATCHING.whenIsActive {
      when(io.flush) {
        ParallaxSim.notice(L"DISPATCHER: Flush received in DISPATCHING. -> DRAINING_BPU")
        // isBusyReg 保持为 True，由 DRAINING_BPU 状态清理
        goto(DRAINING_BPU)
      }.elsewhen(isBusyReg) { // 确保只有当处于 Busy 状态时才尝试分发
        val isSimpleRedirect = currentPredecodeReg.isDirectJump // JAL, J
        val isComplexBranch = currentPredecodeReg.isBranch && !currentPredecodeReg.isDirectJump // BEQ, BNE, etc.

        when(fetchGroupReg.fault) {
          if (enableLog) report(L"DISPATCHER: Discarding faulted group PC=0x${fetchGroupReg.pc}. -> IDLE")
          isBusyReg := False
          dispatchIndexReg := 0
          goto(IDLE)
        }elsewhen(isComplexBranch) { // Slow Path: 只有复杂的、需要预测的分支才去查 BPU
            if (enableLog) report(L"DISPATCHER: Complex Branch detected at PC=0x${currentPcReg}. -> QUERY_BPU")
            io.bpuQuery.valid := True
            io.bpuQuery.payload.pc := currentPcReg
            io.bpuQuery.payload.transactionId := bpuTransIdCounterReg

            pendingBpuQueryReg.pc := currentPcReg
            pendingBpuQueryReg.instruction := currentInstructionReg
            pendingBpuQueryReg.predecodeInfo := currentPredecodeReg
            pendingBpuQueryReg.bpuTransactionId := bpuTransIdCounterReg

            if (enableLog) report(L"DISPATCH-BURST: BPU Query sent for PC=0x${currentPcReg}. -> WAITING_FOR_BPU")
            goto(WAITING_FOR_BPU)
          }otherwise { // Fast Path - Burst Mode: 处理普通指令和简单的直接跳转
            io.fetchOutput.valid := True
            io.fetchOutput.payload.pc := currentPcReg
            io.fetchOutput.payload.instruction := currentInstructionReg
            io.fetchOutput.payload.predecode := currentPredecodeReg
            io.fetchOutput.payload.bpuPrediction.setDefault()

            when(io.fetchOutput.ready) {
              val redirecting = isSimpleRedirect
              if (enableLog)
                report(
                  L"DISPATCH-BURST: Fired PC=0x${currentPcReg}. isLast=${isLastInstructionReg}, redirecting=${redirecting}"
                )

              when(redirecting) {
                io.softRedirect.valid := True
                io.softRedirect.payload := currentPcReg + currentPredecodeReg.jumpOffset.asUInt
              }

              when(isLastInstructionReg || redirecting) {
                if (enableLog) report(L"  DEBUG-DISPATCH-END: isLastInstruction=${isLastInstructionReg}, redirecting=${redirecting}. Group finished. -> IDLE")
                isBusyReg := False
                dispatchIndexReg := 0
                goto(IDLE)
              } otherwise {
                dispatchIndexReg := dispatchIndexReg + 1
                if (enableLog) report(L"  DEBUG-DISPATCH-CONT: dispatchIndexReg set to ${dispatchIndexReg} + 1. Staying in DISPATCHING")
                // 保持在DISPATCHING状态
              }
            } otherwise {
              if (enableLog) report(L"DISPATCH-BURST: Stalled for PC=0x${currentPcReg}")
            }
          }
      } otherwise {
        if (enableLog) report(L"DISPATCHER-WARNING: isBusyReg is false but FSM is in DISPATCHING state. -> IDLE")
        goto(IDLE)
      }
    }

    WAITING_FOR_BPU.whenIsActive {
      report(L"DISPATCH-WAIT-DEBUG: Current State (BEFORE flush check): io.flush=${io.flush}, io.bpuRsp.valid=${io.bpuRsp.valid}, io.bpuRsp.payload.transactionId=${io.bpuRsp.payload.transactionId}, pendingTID=${pendingBpuQueryReg.bpuTransactionId}")
      when(io.flush) {
        ParallaxSim.notice(L"DISPATCHER: Flushing while WAITING_FOR_BPU. -> DRAINING_BPU")
        // isBusyReg 保持为 True，由 DRAINING_BPU 状态清理
        goto(DRAINING_BPU)
      }.otherwise {
        val bpuRsp = io.bpuRsp
        val bpuResponseMatches = bpuRsp.valid && bpuRsp.payload.transactionId === pendingBpuQueryReg.bpuTransactionId
        when(!bpuResponseMatches) {
          report(
            L"DISPATCH-WAIT: BPU Rsp mismatch because: valid=${bpuRsp.valid}, transactionId=${bpuRsp.payload.transactionId}, pending=${pendingBpuQueryReg.bpuTransactionId}"
          )
        }
        if (enableLog) when(isBusyReg && !bpuResponseMatches) {
          report(
            L"DISPATCH-WAIT: Waiting BPU Rsp for PC=0x${pendingBpuQueryReg.pc}, TID=${pendingBpuQueryReg.bpuTransactionId}"
          )
        }

        when(bpuResponseMatches) {
          if (enableLog)
            ParallaxSim.notice(
              L"DISPATCH-WAIT: BPU Rsp Match! PC=0x${pendingBpuQueryReg.pc}, Taken=${bpuRsp.payload.isTaken}. -> SEND_BRANCH"
            )

          // 注意: 计数器在这里不递减，而在bpuRsp.fire时统一处理

          outputReg.pc := pendingBpuQueryReg.pc
          outputReg.instruction := pendingBpuQueryReg.instruction
          outputReg.predecode := pendingBpuQueryReg.predecodeInfo
          outputReg.bpuPrediction.wasPredicted := True
          outputReg.bpuPrediction.isTaken := bpuRsp.payload.isTaken
          outputReg.bpuPrediction.target := bpuRsp.payload.target
          if (enableLog) {
            report(L"  DEBUG-OUTPUT-REG-UPDATED: outputReg.bpuPrediction.isTaken_ASSIGNED=${bpuRsp.payload.isTaken}, PC_ASSIGNED=${pendingBpuQueryReg.pc}") // 打印赋值的源
            report(L"  DEBUG-OUTPUT-REG-UPDATED: outputReg.bpuPrediction.isTaken_REG_OUT=${outputReg.bpuPrediction.isTaken}, PC_REG_OUT=${outputReg.pc}") // 打印寄存器当前输出
          }
          goto(SEND_BRANCH)
        }
      }
    }

    SEND_BRANCH.whenIsActive {
      when(io.flush) {
        ParallaxSim.notice(L"DISPATCHER: Flush received in SEND_BRANCH. -> DRAINING_BPU")
        // isBusyReg 保持为 True，由 DRAINING_BPU 状态清理
        goto(DRAINING_BPU)
      } otherwise {
        io.fetchOutput.valid := True
        io.fetchOutput.payload := outputReg

        when(io.fetchOutput.fire) {
          if (enableLog) report(L"DISPATCH-SEND: Fired Branch/Stalled-FastPath PC=0x${outputReg.pc}")
          if (enableLog) report(L"  DEBUG-OUTPUT-FIRED: outputReg.bpuPrediction.isTaken=${outputReg.bpuPrediction.isTaken}, PC=0x${outputReg.pc}")
          val redirecting =
            (outputReg.predecode.isDirectJump) || (outputReg.bpuPrediction.wasPredicted && outputReg.bpuPrediction.isTaken)
          when(redirecting) {
            io.softRedirect.valid := True
            io.softRedirect.payload := Mux(
              outputReg.predecode.isDirectJump,
              outputReg.pc + outputReg.predecode.jumpOffset.asUInt,
              outputReg.bpuPrediction.target
            )
            if (enableLog) report(L"DISPATCH-SEND: Soft redirect triggered to 0x${io.softRedirect.payload}")
          }

          when(isLastInstructionReg || redirecting) { // isLastInstruction 此时是基于已更新的 fetchGroupReg
            if (enableLog) report(L"  DEBUG-SEND-END: isLastInstruction=${isLastInstructionReg}, redirecting=${redirecting}. Group finished. -> IDLE")
            isBusyReg := False
            if (enableLog) report(L"DISPATCH-SEND: Group finished. -> IDLE")
            dispatchIndexReg := 0
            goto(IDLE)
          } otherwise {
            dispatchIndexReg := dispatchIndexReg + 1
            if (enableLog) report(L"  DEBUG-SEND-CONT: dispatchIndexReg set to ${dispatchIndexReg} + 1. -> DISPATCHING")
            if (enableLog) report(L"DISPATCH-SEND: More instructions in group. -> DISPATCHING")
            goto(DISPATCHING)
          }
        } otherwise {
          if (enableLog) report(L"DISPATCH-SEND: Stalled on output. PC=0x${outputReg.pc}")
          if (enableLog) report(L"  DEBUG-SEND-STALL: Stalled on output. PC=0x${outputReg.pc}")
        }
      }
    }

    DRAINING_BPU.whenIsActive {
      if (enableLog) report(L"DISPATCHER: Draining BPU requests. InFlight=${bpuInFlightCounterReg}")
      when(bpuInFlightCounterReg === 0) {
        isBusyReg := False // 在这里清理 isBusyReg
        dispatchIndexReg := 0
        if (enableLog) report(L"  DEBUG-ASSIGN: dispatchIndexReg assigned 0 from DRAINING_BPU")
        if (enableLog) report(L"DISPATCHER: BPU Draining complete. -> IDLE")
        goto(IDLE)
      }
    }
  }

  fsm.build() // 确保FSM被构建

  // when(io.bpuRsp.valid && !fsm.isActive(fsm.WAITING_FOR_BPU) && !fsm.isActive(fsm.DRAINING_BPU)) {
  //   assert(False, "BPU Response received but current state is not WAITING_FOR_BPU or DRAINING_BPU. This should not happen.")
  // }

  // --- 详细日志 ---
  if (enableLog) {
    report(
      L"DISPATCHER STATE: isBusyReg=${isBusyReg}, dispatchIndexReg=${dispatchIndexReg}, fsmState=${fsm.stateReg}, bpuInFlight=${bpuInFlightCounterReg}"
    )
    when(io.fetchGroupIn.fire) { report(L"  -> EVENT: fetchGroupIn.fire") }
    when(io.fetchOutput.fire) { report(L"  -> EVENT: fetchOutput.fire") }
    when(io.bpuQuery.fire) { report(L"  -> EVENT: bpuQuery.fire") }
    when(io.bpuRsp.fire) { report(L"  -> EVENT: bpuRsp.fire") }
    when(io.softRedirect.valid) { report(L"  -> EVENT: softRedirect.valid") }
    when(io.flush) { report(L"  -> EVENT: FLUSH asserted") }
  }

  import spinal.core.sim._
  // 添加一个专门用于仿真的输出端口
  val sim_fsmStateId = UInt(3 bits) simPublic()

  // 手动将状态映射到一个整数ID
  when(fsm.isActive(fsm.IDLE)) { sim_fsmStateId := 0 }
  .elsewhen(fsm.isActive(fsm.DISPATCHING)) { sim_fsmStateId := 1 }
  .elsewhen(fsm.isActive(fsm.WAITING_FOR_BPU)) { sim_fsmStateId := 2 }
  .elsewhen(fsm.isActive(fsm.SEND_BRANCH)) { sim_fsmStateId := 3 }
  .elsewhen(fsm.isActive(fsm.DRAINING_BPU)) { sim_fsmStateId := 4 }
  .otherwise { sim_fsmStateId := 5 } // Unknown/Boot state
}
