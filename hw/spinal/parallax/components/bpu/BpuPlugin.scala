package parallax.components.bpu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common.PipelineConfig
import parallax.bpu._
import parallax.utilities.Plugin
import parallax.utilities.ParallaxSim

case class BpuPluginConfig(
    phtDepth: Int = 4096,
    btbDepth: Int = 512,
    enableLog: Boolean = false
) {
  require(isPow2(phtDepth), "PHT depth must be a power of 2")
  require(isPow2(btbDepth), "BTB depth must be a power of 2")
}

class BpuPipelinePlugin(
    val pCfg: PipelineConfig,
    val bpuCfg: BpuPluginConfig = BpuPluginConfig()
) extends Plugin
    with BpuService {

  private val enableLog = bpuCfg.enableLog
  private val queryPortIn = Flow(BpuQuery(pCfg))
  private val responseFlowOut = Flow(BpuResponse(pCfg))
  private val updatePortIn = Stream(BpuUpdate(pCfg))

  override def newBpuQueryPort(): Flow[BpuQuery] = queryPortIn
  override def getBpuResponse(): Flow[BpuResponse] = responseFlowOut
  override def newBpuUpdatePort(): Stream[BpuUpdate] = updatePortIn

  val logic = create late new Area {
    // --- 内存和索引定义 (保持不变) ---
    val pht = Mem(Bits(2 bits), bpuCfg.phtDepth)
    pht.init(Seq.fill(bpuCfg.phtDepth)(B"01"))

    val btbTagWidth = pCfg.pcWidth.value - log2Up(bpuCfg.btbDepth) - 2
    case class BtbEntry() extends Bundle {
      val valid = Bool()
      val tag = UInt(btbTagWidth bits)
      val target = UInt(pCfg.pcWidth)
    }
    val btb = Mem(BtbEntry(), bpuCfg.btbDepth)
    btb.init(Seq.fill(bpuCfg.btbDepth)(BtbEntry().getZero))

    def phtIndex = (pc: UInt) => pc(log2Up(bpuCfg.phtDepth) + 1 downto 2)
    def btbIndex = (pc: UInt) => pc(log2Up(bpuCfg.btbDepth) + 1 downto 2)
    def btbTag = (pc: UInt) => pc(pCfg.pcWidth.value - 1 downto pCfg.pcWidth.value - btbTagWidth)

    // =================================================================
    //  查询流水线 (Query Pipeline)
    // =================================================================

    val Q_PC = Stageable(UInt(pCfg.pcWidth))
    val IS_TAKEN = Stageable(Bool())
    val TARGET_PC = Stageable(UInt(pCfg.pcWidth))

    val queryPipe = new Pipeline()
    val s1_read = queryPipe.newStage()
    val s2_predict = queryPipe.newStage()
    queryPipe.connect(s1_read, s2_predict)(Connection.M2S())

    s1_read.valid := queryPortIn.valid
    s1_read(Q_PC) := queryPortIn.payload.pc

    if (enableLog) {
      when(s1_read.isFiring) {
        ParallaxSim.debug(
          L"[BPU.S1] Query Firing for PC=0x${(s1_read(Q_PC))}, PHT Idx=0x${(phtIndex(s1_read(Q_PC)))}, BTB Idx=0x${(btbIndex(s1_read(Q_PC)))}"
        )
      }
    }

    val phtReadData_s1 = pht.readSync(phtIndex(s1_read(Q_PC)), enable = s1_read.isFiring)
    val btbReadData_s1 = btb.readSync(btbIndex(s1_read(Q_PC)), enable = s1_read.isFiring)

    val s2_pc = s2_predict(Q_PC)
    val s2_phtReadout = phtReadData_s1
    val s2_btbReadout = btbReadData_s1

    val phtPrediction = s2_phtReadout.msb
    val btbHit = s2_btbReadout.valid && s2_btbReadout.tag === btbTag(s2_pc)

    s2_predict(IS_TAKEN) := btbHit && phtPrediction
    s2_predict(TARGET_PC) := s2_btbReadout.target

    if (enableLog) {
      when(s2_predict.isFiring) {
        ParallaxSim.debug(
          L"[BPU.S2] Predict Firing for PC=0x${(s2_pc)} | PHT Readout=${s2_phtReadout} -> Predict=${phtPrediction} | BTB Readout: valid=${s2_btbReadout.valid} tag_match=${s2_btbReadout.tag === btbTag(
              s2_pc
            )} -> Hit=${btbHit} | Final Predict: isTaken=${s2_predict(IS_TAKEN)} target=0x${(s2_predict(TARGET_PC))}"
        )
      }
    }

    // =================================================================
    //  更新流水线 (Update Pipeline)
    // =================================================================
    val updatePipe = new Pipeline()
    val u1_read = updatePipe.newStage()
    val u2_write = updatePipe.newStage()
    updatePipe.connect(u1_read, u2_write)(Connection.M2S())

    val U_PAYLOAD = Stageable(BpuUpdate(pCfg))

    u1_read.driveFrom(updatePortIn)
    u1_read(U_PAYLOAD) := updatePortIn.payload
    ParallaxSim.debugStage(u1_read)
    if (enableLog) {
      when(u1_read.isFiring) {
        ParallaxSim.debug(
          L"[BPU.U1] Update Firing for PC=0x${(u1_read(U_PAYLOAD).pc)}, isTaken=${u1_read(U_PAYLOAD).isTaken}"
        )
      }
    }

    val oldPhtState_u1 = pht.readSync(phtIndex(u1_read(U_PAYLOAD).pc), enable = u1_read.isFiring)

    val u2_updatePayload = u2_write(U_PAYLOAD)
    val u2_oldPhtState = oldPhtState_u1

    val newPhtState = Bits(2 bits)
    switch(u2_oldPhtState) {
      is(B"00") { newPhtState := Mux(u2_updatePayload.isTaken, B"01", B"00") }
      is(B"01") { newPhtState := Mux(u2_updatePayload.isTaken, B"10", B"00") }
      is(B"10") { newPhtState := Mux(u2_updatePayload.isTaken, B"11", B"01") }
      is(B"11") { newPhtState := Mux(u2_updatePayload.isTaken, B"11", B"10") }
    }

    if (enableLog) {
      when(u2_write.isFiring) {
        ParallaxSim.debug(
          L"[BPU.U2] Write Firing for PC=0x${(u2_updatePayload.pc)} | Old PHT=${u2_oldPhtState} -> New PHT=${newPhtState} | isTaken=${u2_updatePayload.isTaken}, Wr BTB?=${u2_updatePayload.isTaken}"
        )
      }
    }

    when(u2_write.isFiring) {
      pht.write(phtIndex(u2_updatePayload.pc), newPhtState)

      when(u2_updatePayload.isTaken) {
        val newBtbEntry = BtbEntry()
        newBtbEntry.valid := True
        newBtbEntry.tag := btbTag(u2_updatePayload.pc)
        newBtbEntry.target := u2_updatePayload.target
        btb.write(btbIndex(u2_updatePayload.pc), newBtbEntry)
      }
    }

    // =================================================================
    //  数据冒险前递 (Forwarding Logic)
    // =================================================================
    val pht_hazard = u2_write.isValid && (phtIndex(u2_updatePayload.pc) === phtIndex(s2_pc))
    val btb_hazard = u2_write.isValid && (btbIndex(u2_updatePayload.pc) === btbIndex(s2_pc)) && (btbTag(
      u2_updatePayload.pc
    ) === btbTag(s2_pc))

    if (enableLog) {
      ParallaxSim.debug(
        L"[BPU.FWD] pht_hazard conds: u2_write.(input)isValid=${u2_write.isValid} phtIndex(u2_updatePayload.pc)=${phtIndex(u2_updatePayload.pc)} === phtIndex(s2_pc)=${phtIndex(s2_pc)}"
      )
      ParallaxSim.debug(
        L"[BPU.FWD] btb_hazard conds: u2_write.(input)isValid=${u2_write.isValid} btbIndex(u2_updatePayload.pc)=${btbIndex(
            u2_updatePayload.pc
          )} === btbIndex(s2_pc)=${btbIndex(s2_pc)} btbTag(u2_updatePayload.pc)=${btbTag(u2_updatePayload.pc)} === btbTag(s2_pc)=${btbTag(s2_pc)}"
      )
    }

    if (enableLog) {
      when(s2_predict.isFiring) {
        when(pht_hazard) {
          ParallaxSim.debug(L"[BPU.FWD] PHT Hazard detected! Overriding prediction.")
        }
        when(btb_hazard) {
          ParallaxSim.debug(L"[BPU.FWD] BTB Hazard detected! Overriding prediction.")
        }
      }
    }

    // 覆盖逻辑必须放在默认逻辑之后
    when(pht_hazard || btb_hazard) {
      // 当发生任何一种冒险时，我们需要重新评估预测

      // 1. 获取最准确的 PHT 预测 (如果PHT冒险，用新的；否则用旧的)
      val phtPredFwd = Mux(pht_hazard, newPhtState.msb, phtPrediction)

      // 2. 获取最准确的 BTB 命中状态 (如果BTB冒险且更新为taken，则命中；否则看原始BTB命中)
      val btbHitFwd = Mux(btb_hazard, u2_updatePayload.isTaken, btbHit)

      // 3. 计算最终的 isTaken 预测
      s2_predict(IS_TAKEN) := btbHitFwd && phtPredFwd

      // 4. 获取最准确的 Target PC (如果BTB冒险且更新为taken，用新的；否则用旧的)
      when(btb_hazard && u2_updatePayload.isTaken) {
        s2_predict(TARGET_PC) := u2_updatePayload.target
      }
    }

    // =================================================================
    //  连接流水线出口到 Service 响应端口
    // =================================================================
    responseFlowOut.valid := s2_predict.isValid
    responseFlowOut.payload.isTaken := s2_predict(IS_TAKEN)
    responseFlowOut.payload.target := s2_predict(TARGET_PC)
   
    queryPipe.build()
    updatePipe.build()

  }
}
