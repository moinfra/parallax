// filename: src/main/src/main/scala/parallax/fetch/icache/ICachePlugin.scala
package parallax.fetch.icache

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4ReadOnly}
import spinal.lib.pipeline._
import parallax.utilities.{Plugin, ParallaxLogger}
import parallax.fetch.AxiReadOnlyMasterService
import spinal.lib.fsm._
import parallax.utilities.ParallaxSim.notice

class ICachePlugin(
    iCacheCfg: ICacheConfig,
    axiCfg: Axi4Config,
    pcWidth: Int
) extends Plugin
    with ICacheService
    with AxiReadOnlyMasterService {

  // --- 服务接口实现 ---
  private val port = ICachePort(iCacheCfg, pcWidth)
  override def newICachePort(): ICachePort = port
  override val invalidate = Bool() default(False)

  private var axiMaster: Axi4ReadOnly = null
  override def getAxi4ReadOnlyMaster(): Axi4ReadOnly = {
    assert(logic != null, "ICache logic must be built before getting AXI master")
    if (axiMaster == null) {
      axiMaster = Axi4ReadOnly(axiCfg).setCompositeName(this, "axiMaster")
    }
    axiMaster
  }

  // --- 内部逻辑区域 ---
  val logic = create late new Area {
    lock.await()
    val enableLog = iCacheCfg.enableLog || false
    if (enableLog) ParallaxLogger.log("Plugin: ICache; logic generation started.")

    // =================================================================
    //  存储区域 (Storage Area) - 无变化
    // =================================================================
    val storage = new Area {
      val valids = Vec(Vec(Reg(Bool()) init (False), iCacheCfg.ways), iCacheCfg.sets)
      // BRAM: 1 Read Port (for F1), 1 Write Port (for unified writer)
      val tagLruRam = Mem(ICacheMetaLine(iCacheCfg, pcWidth), iCacheCfg.sets)
      val dataRams = Seq.fill(iCacheCfg.ways)(
        Mem(Vec(Bits(32 bits), iCacheCfg.lineWords), iCacheCfg.sets)
      )

      val victimWayReg = Reg(UInt(log2Up(iCacheCfg.ways) bits))
      val lineBuffer = Reg(Vec(Bits(32 bits), iCacheCfg.lineWords))

    }

    // =================================================================
    //  统一的Tag/LRU RAM写逻辑 (Unified Write Logic)
    // =================================================================
    val tag_write_logic = new Area {
      val fsmIsCommitting = False // Will be set by FSM
      val f2IsUpdatingLru = False // Will be set by Pipeline F2

      val writeAddress = UInt(iCacheCfg.indexWidth bits)
      val writeData = ICacheMetaLine(iCacheCfg, pcWidth)
      
      // 默认值
      writeAddress.assignDontCare()
      writeData.assignDontCare()

      // 仲裁：FSM的提交操作优先级高于F2的LRU更新
      val writeEnable = fsmIsCommitting || f2IsUpdatingLru
      
      storage.tagLruRam.write(
        address = writeAddress,
        data = writeData,
        enable = writeEnable
      )
    }

    // =================================================================
    //  流水线区域 (Pipeline Area)
    // =================================================================
    val pipeline = new Area {
      val pipe = new Pipeline {
        val F1 = newStage().setName("ICache_F1_Access")
        val F2 = newStage().setName("ICache_F2_HitCheck")
      }
      pipe.connect(pipe.F1, pipe.F2)(Connection.M2S())

      val F1_CMD = Stageable(ICacheCmd(pcWidth))
      val F1_VALID_MASK = Stageable(Vec(Bool(), iCacheCfg.ways))
      // **[NEW]** Stageable to carry the forwarding signal
      val F2_USE_FORWARDED_META = Stageable(Bool())
      val F2_FORWARDED_META = Stageable(ICacheMetaLine(iCacheCfg, pcWidth))
      val F2_FORWARDED_DATA = Stageable(Vec(Bits(32 bits), iCacheCfg.lineWords))
      val F2_USE_FORWARDED_DATA = Stageable(Bool())
      val F2_VICTIM_WAY_ON_HAZARD = Stageable(UInt(log2Up(iCacheCfg.ways) bits))

      // --- F1 阶段: 读 & 冲突检测 ---
      val f1 = new Area {
        pipe.F1.valid := port.cmd.valid
        pipe.F1(F1_CMD) := port.cmd.payload

        val f1_address = pipe.F1(F1_CMD).address
        val f1_index = f1_address(iCacheCfg.indexRange(pcWidth))

        when(pipe.F1.valid) {
          assert(f1_address(iCacheCfg.offsetRange(pcWidth)) === 0, "ICache request must be line-aligned!")
        }

        val f1_isFiring = pipe.F1.isFiring
        pipe.F1(F1_VALID_MASK) := storage.valids(f1_index)

        // 永远进行同步读
        val metaReadData = storage.tagLruRam.readSync(f1_index, enable = f1_isFiring)
        val dataReadData = Vec(storage.dataRams.map(_.readSync(f1_index, enable = f1_isFiring)))

        // --- 前推逻辑 (Forwarding Logic) ---
        // 读写冲突检测：当F1正在读取某个 set index 时，是否有写操作正在对同一个 set index 进行
        val writeHappening = tag_write_logic.writeEnable
        val writeReadHazard = writeHappening && (tag_write_logic.writeAddress === f1_index)

        // **[FIX START]**
        // 关键修复：数据转发 (Data Forwarding) 只能在FSM正在COMMIT时发生。
        // FSM COMMIT是唯一会更新 lineBuffer 并将其写入Data RAM的场景。
        // 而F2的LRU更新只会写Tag/LRU RAM，此时 lineBuffer 中的数据是陈旧的，绝对不能转发。
        
        // 信号，表明FSM正在进行提交操作，这是数据转发的先决条件。
        val fsmIsCommitting = tag_write_logic.fsmIsCommitting

        // Meta数据转发的条件：只要发生读写冲突就需要，因为无论是FSM提交还是F2更新LRU，
        // 写入的meta数据都是下一周期F2需要看到的最新版本。
        pipe.F1(F2_USE_FORWARDED_META) := writeReadHazard
        pipe.F1(F2_FORWARDED_META) := tag_write_logic.writeData
        
        // 数据转发的条件（更严格）：必须是读写冲突，且冲突的来源是FSM的COMMIT操作。
        val dataForwardingIsRequired = writeReadHazard && fsmIsCommitting
        pipe.F1(F2_USE_FORWARDED_DATA) := dataForwardingIsRequired
        pipe.F1(F2_FORWARDED_DATA) := storage.lineBuffer // 数据源永远是lineBuffer
        
        // victimWay只有在发生hazard时才有意义，传递给F2用于判断是否命中正在被替换的way。
        pipe.F1(F2_VICTIM_WAY_ON_HAZARD) := storage.victimWayReg
        // **[FIX END]**

        if (enableLog) {
          when(f1_isFiring) {
            report(L"[ICache F1] Firing! Addr=0x${f1_address}, Index=0x${f1_index}, TID=${pipe.F1(F1_CMD).transactionId}")
            when(writeReadHazard) {
              // 细化日志，明确冲突类型
              when(fsmIsCommitting) {
                 report(L"[ICache F1] HAZARD DETECTED (FSM Commit)! Forwarding Meta+Data for Index=0x${f1_index}")
              } otherwise {
                 report(L"[ICache F1] HAZARD DETECTED (LRU Update)! Forwarding Meta-Only for Index=0x${f1_index}")
              }
            }
          }
        }
      }

      // --- F2 阶段: 命中检查 & LRU更新触发 ---
      val f2 = new Area {
        // --- 0. 从F1获取所有必要的输入 ---
        val f2_cmd = pipe.F2(F1_CMD)
        val f2_index = f2_cmd.address(iCacheCfg.indexRange(pcWidth))
        val f2_tag = f2_cmd.address(iCacheCfg.tagRange(pcWidth))
        val f2_valids = pipe.F2(F1_VALID_MASK)
        
        // 前推相关信号
        val f2_useForwardedMeta = pipe.F2(F2_USE_FORWARDED_META)
        val f2_useForwardedData = pipe.F2(F2_USE_FORWARDED_DATA)
        val f2_metaLineForwarded = pipe.F2(F2_FORWARDED_META)
        val f2_dataLineForwarded = pipe.F2(F2_FORWARDED_DATA)
        val f2_victimWayOnHazard = pipe.F2(F2_VICTIM_WAY_ON_HAZARD)

        // 来自RAM的原始数据
        val f2_metaLineFromRam = f1.metaReadData
        val f2_dataLinesFromRam = f1.dataReadData

        // --- 1. 计算命中逻辑 (基于正确的Meta数据) ---
        val f2_metaLine = Mux(f2_useForwardedMeta, f2_metaLineForwarded, f2_metaLineFromRam)
        val hit_ways = Vec((0 until iCacheCfg.ways).map { i => f2_valids(i) && f2_metaLine.ways(i).tag === f2_tag })
        val isHit = hit_ways.orR
        val hitWayIdx = OHToUInt(hit_ways.asBits)

        // --- 2. 选择正确的数据通路 ---
        //    这里的逻辑是：我们首先根据命中信号从N路RAM数据中选出一路，
        //    然后再判断是否应该用前推数据覆盖掉这个选出的结果。
        
        // 步骤2a: 从RAM数据中选出命中的那一路
        val hitWayDataFromRam = MuxOH(hit_ways, f2_dataLinesFromRam)

        // 步骤2b: 判断是否需要用前推数据覆盖
        val hitWayIsVictimWay = isHit && (hitWayIdx === f2_victimWayOnHazard)
        val needsDataForwarding = f2_useForwardedData && hitWayIsVictimWay
        
        // 步骤2c: 最终的数据选择
        val finalHitData = Mux(needsDataForwarding, f2_dataLineForwarded, hitWayDataFromRam)

        // --- 3. LRU 更新逻辑 (基于命中结果) ---
        val f2_can_update_lru = pipe.F2.isFiring && isHit
        when(f2_can_update_lru) {
          tag_write_logic.f2IsUpdatingLru := True
          tag_write_logic.writeAddress := f2_index
          val updatedLine = ICacheMetaLine(iCacheCfg, pcWidth)
          updatedLine.ways := f2_metaLine.ways
          updatedLine.lru := !hitWayIdx.lsb
          tag_write_logic.writeData := updatedLine
          if (enableLog) report(L"[ICache F2] Hit! Requesting LRU Write for Index=0x${f2_index} to ${updatedLine.lru}")
        }

        // --- 4. 将F2阶段的结果传递给ctrl Area ---
        //    为了清晰，我们可以定义一些输出信号，但也可以直接在ctrl Area中使用它们。
        val f2_output_instructions = finalHitData
        val f2_output_isHit = isHit
        
        if (enableLog) {
            when(pipe.F2.isFiring && needsDataForwarding) {
                notice(L"[ICache F2] DATA FORWARDING ACTIVATED! Using data from refill buffer for Way ${hitWayIdx}.")
            }
        }
      }
    }


    // =================================================================
    //  响应与控制区域 (Response and Control Area) - 无变化
    // =================================================================
    val ctrl = new Area {
      port.rsp.valid := False
      port.rsp.payload.assignDontCare()
      when(pipeline.pipe.F2.isValid) {
        port.rsp.valid := True
        port.rsp.payload.transactionId := pipeline.f2.f2_cmd.transactionId
        port.rsp.payload.wasHit := pipeline.f2.isHit
        when(pipeline.f2.isHit) {
          port.rsp.payload.instructions := pipeline.f2.f2_output_instructions
          port.rsp.payload.redo := False
        } otherwise {
          port.rsp.payload.instructions.foreach(_ := 0)
          port.rsp.payload.redo := True
        }
        if (enableLog) when(pipeline.pipe.F2.isFiring) {
          report(L"[ICache Rsp] Firing! Hit=${pipeline.f2.isHit}, Redo=${!pipeline.f2.isHit}, TID=${pipeline.f2.f2_cmd.transactionId}")
        }
      }
    }

    // =================================================================
    //  Refill 状态机区域 (Refill FSM Area)
    // =================================================================
    val refill = new Area {
      val axi = getAxi4ReadOnlyMaster()
      axi.setIdle()

      // **[MODIFICATION]** 将寄存器提升到Area级别，以便其他Area可以访问
      val refillCmdReg = Reg(ICacheCmd(pcWidth))
      val refillCounter = Counter(iCacheCfg.lineWords)
      // **[MODIFICATION]** 锁存F2阶段看到的MetaLine，用于重建写回数据
      val latchedMetaOnMiss = Reg(ICacheMetaLine(iCacheCfg, pcWidth))

      val fsm = new StateMachine {
        setEncoding(binaryOneHot)
        val IDLE = new State with EntryPoint
        val SEND_REQ = new State
        val RECEIVE_DATA = new State
        val COMMIT = new State

        IDLE.whenIsActive {
          val startRefill = pipeline.pipe.F2.isValid && !pipeline.f2.isHit
          when(startRefill) {
            refillCmdReg := pipeline.f2.f2_cmd
            storage.victimWayReg := pipeline.f2.f2_metaLine.lru.asUInt
            latchedMetaOnMiss := pipeline.f2.f2_metaLine // 锁存F2看到的Meta数据
            if (enableLog) report(L"[RefillFSM] Start triggered! Addr=0x${pipeline.f2.f2_cmd.address}. Victim Way=${pipeline.f2.f2_metaLine.lru.asUInt}.")
            goto(SEND_REQ)
          }
        }

        SEND_REQ.onEntry {
          // 在进入状态时复位计数器
          refillCounter.clear()
        }
        SEND_REQ.whenIsActive {
          axi.ar.valid := True
          axi.ar.payload.addr := refillCmdReg.address
          axi.ar.payload.len := iCacheCfg.lineWords - 1
          axi.ar.payload.size := log2Up(32 / 8)
          axi.ar.payload.burst := Axi4.burst.INCR

          when(axi.ar.fire) {
            if (enableLog) report(L"[RefillFSM] AXI AR Sent.")
            goto(RECEIVE_DATA)
          }
        }

        RECEIVE_DATA.whenIsActive {
          axi.r.ready := True

          when(axi.r.fire) {
            if (enableLog) report(L"[RefillFSM] AXI R Received. Word ${refillCounter.value}, Data=0x${axi.r.payload.data}")
            storage.lineBuffer(refillCounter) := axi.r.payload.data
            refillCounter.increment()

            when(axi.r.payload.last) {
              if (enableLog) report(L"[RefillFSM] Last AXI R received. Moving to COMMIT state.")
              goto(COMMIT) // 俺们故意引入一个周期，避免读写冲突
            }
          }
        }

        COMMIT.whenIsActive {
          if (enableLog) report(L"[RefillFSM] In COMMIT state. Writing to cache memories.")
          val indexToWrite = refillCmdReg.address(iCacheCfg.indexRange(pcWidth))
          val wayToWrite = storage.victimWayReg

          // 1. 写 DataRAM
          for (wayId <- 0 until iCacheCfg.ways) {
            when(wayToWrite === wayId) {
              storage.dataRams(wayId).write(indexToWrite, storage.lineBuffer)
            }
          }

          // 2. **[CRITICAL CHANGE]** 请求向统一写逻辑写入Tag/LRU
          tag_write_logic.fsmIsCommitting := True
          tag_write_logic.writeAddress := indexToWrite
          
          // 重建要写入的MetaLine
          val newMetaLine = ICacheMetaLine(iCacheCfg, pcWidth)
          // 从锁存的旧数据中恢复未被替换的way的tag
          newMetaLine.ways := latchedMetaOnMiss.ways 
          // 插入新的tag
          newMetaLine.ways(wayToWrite).tag := refillCmdReg.address(iCacheCfg.tagRange(pcWidth))
          // 更新LRU位
          newMetaLine.lru := !wayToWrite.lsb 
          tag_write_logic.writeData := newMetaLine

          // 3. 更新 Valid 位
          storage.valids(indexToWrite)(wayToWrite) := True

          if (enableLog) report(L"[RefillFSM] Commit complete. Requesting write to central logic. New Meta=${newMetaLine.ways.map(_.tag)}, New LRU=${newMetaLine.lru}")
          goto(IDLE)
        }
      }
      fsm.build()
    }

    // =================================================================
    //  管理区域 (Management Area)
    // =================================================================
    val management = new Area {
      when(invalidate) {
        storage.valids.foreach(_.foreach(_ := False))
        if (enableLog) report(L"[ICache Mgmt] Full cache invalidation triggered.")
      }
      val sim_fsmStateId = UInt(3 bits) simPublic ()
      sim_fsmStateId := 7 // Default
      when(refill.fsm.isActive(refill.fsm.IDLE)) { sim_fsmStateId := 0 }
      when(refill.fsm.isActive(refill.fsm.SEND_REQ)) { sim_fsmStateId := 1 }
      when(refill.fsm.isActive(refill.fsm.RECEIVE_DATA)) { sim_fsmStateId := 2 }
      when(refill.fsm.isActive(refill.fsm.COMMIT)) { sim_fsmStateId := 3 }
      if (enableLog) {
        val cycles = Reg(UInt(32 bits)) init (0)
        cycles := cycles + 1
        report(L"[ICache Mgmt] ------ cycle ${cycles} end, Last FSM State = ${refill.fsm.stateReg}")
      }
    }
    
    pipeline.pipe.build()
  }
}
