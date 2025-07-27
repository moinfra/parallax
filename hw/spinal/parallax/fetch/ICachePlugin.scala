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
    val enableLog = iCacheCfg.enableLog
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
        val writeHappening = tag_write_logic.writeEnable
        val writeReadHazard = writeHappening && (tag_write_logic.writeAddress === f1_index)

        pipe.F1(F2_USE_FORWARDED_META) := writeReadHazard
        pipe.F1(F2_FORWARDED_META) := tag_write_logic.writeData

        if (enableLog) {
          when(f1_isFiring) {
            report(L"[ICache F1] Firing! Addr=0x${f1_address}, Index=0x${f1_index}, TID=${pipe.F1(F1_CMD).transactionId}")
            when(writeReadHazard) {
              report(L"[ICache F1] HAZARD DETECTED! Forwarding write data for Index=0x${f1_index}")
            }
          }
        }
      }

      // --- F2 阶段: 命中检查 & LRU更新触发 ---
      val f2 = new Area {
        val f2_cmd = pipe.F2(F1_CMD)
        val f2_index = f2_cmd.address(iCacheCfg.indexRange(pcWidth))
        val f2_tag = f2_cmd.address(iCacheCfg.tagRange(pcWidth))
        val f2_valids = pipe.F2(F1_VALID_MASK)

        // **[CRITICAL CHANGE]** 使用前推的数据（如果需要）
        val f2_metaLineFromRam = f1.metaReadData
        val f2_metaLine = Mux(pipe.F2(F2_USE_FORWARDED_META), pipe.F2(F2_FORWARDED_META), f2_metaLineFromRam)

        val f2_dataLines = f1.dataReadData

        val hit_ways = Vec((0 until iCacheCfg.ways).map { i => f2_valids(i) && f2_metaLine.ways(i).tag === f2_tag })
        val isHit = hit_ways.orR
        val hitWayData = MuxOH(hit_ways, f2_dataLines)
        val hitWayIdx = OHToUInt(hit_ways.asBits)

        // --- LRU 更新写请求 ---
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

        if (enableLog) when(pipe.F2.isFiring) {
          report(L"[ICache F2] Firing! Addr=0x${f2_cmd.address}, TID=${f2_cmd.transactionId}. Using Forwarded=${pipe.F2(F2_USE_FORWARDED_META)}. Valids=${f2_valids}, Tags=${f2_metaLine.ways.map(_.tag)}, Expected Tag=0x${f2_tag} -> Hits=${hit_ways}, isHit=${isHit}")
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
          port.rsp.payload.instructions := pipeline.f2.hitWayData
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
      val victimWayReg = Reg(UInt(log2Up(iCacheCfg.ways) bits))
      val lineBuffer = Reg(Vec(Bits(32 bits), iCacheCfg.lineWords))
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
            victimWayReg := pipeline.f2.f2_metaLine.lru.asUInt
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
            lineBuffer(refillCounter) := axi.r.payload.data
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
          val wayToWrite = victimWayReg

          // 1. 写 DataRAM
          for (wayId <- 0 until iCacheCfg.ways) {
            when(wayToWrite === wayId) {
              storage.dataRams(wayId).write(indexToWrite, lineBuffer)
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
