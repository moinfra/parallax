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
  override val invalidate = Bool ()

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
    //  存储区域 (Storage Area)
    // =================================================================
    val storage = new Area {

      val valids = Vec(Vec(Reg(Bool()) init (False), iCacheCfg.ways), iCacheCfg.sets)
      val tagLruRam = Mem(ICacheMetaLine(iCacheCfg, pcWidth), iCacheCfg.sets) addAttribute("ram_style", "block")

      val dataRams = Seq.fill(iCacheCfg.ways)(
        Mem(Vec(Bits(32 bits), iCacheCfg.lineWords), iCacheCfg.sets) addAttribute("ram_style", "block")
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

      // ToGemini: F1_ 等前缀表示数据插入流水线的时间，我们的流水线 API 是不可变性的，一个数据只能插入到流水线一次！
      val F1_CMD = Stageable(ICacheCmd(pcWidth))
      val F1_VALID_MASK = Stageable(Vec(Bool(), iCacheCfg.ways))

      // --- F1 阶段 ---
      val f1 = new Area {
        // ICache 永远准备好接收请求
        pipe.F1.valid := port.cmd.valid
        pipe.F1(F1_CMD) := port.cmd.payload

        val f1_address = pipe.F1(F1_CMD).address
        val f1_index = f1_address(iCacheCfg.indexRange(pcWidth))

        when(pipe.F1.valid) {
          assert(f1_address(iCacheCfg.offsetRange(pcWidth)) === 0, "ICache request must be line-aligned!")
        }

        // 并行访问存储
        val f1_isFiring = pipe.F1.isFiring
        val f1_isReady = pipe.F1.isReady
        assert(f1_isReady, "ICache F1 stage must be always ready to receive request!")
        pipe.F1(F1_VALID_MASK) := storage.valids(f1_index)

        /* public */ val metaReadData = storage.tagLruRam.readSync(f1_index, enable = f1_isFiring)
        /* public */ val dataReadData = Vec(storage.dataRams.map(_.readSync(f1_index, enable = f1_isFiring)))

        if (enableLog) {
          when(f1_isFiring) {
            report(
              L"[ICache F1] Firing! Addr=0x${f1_address}, Index=0x${f1_index}, TID=${pipe.F1(F1_CMD).transactionId}"
            )
          }
        }
      }

      // --- F2 阶段 ---
      val f2 = new Area {
        val f2_cmd = pipe.F2(F1_CMD)
        val f2_index = f2_cmd.address(iCacheCfg.indexRange(pcWidth))
        val f2_tag = f2_cmd.address(iCacheCfg.tagRange(pcWidth))

        // 从 Stageable 中获取数据
        val f2_valids = pipe.F2(F1_VALID_MASK)
        val f2_metaLine = f1.metaReadData // F1读出的稳定Meta数据
        val f2_dataLines = f1.dataReadData // BRAM读出的数据

        val hit_ways = Vec((0 until iCacheCfg.ways).map { i =>
          f2_valids(i) && f2_metaLine.ways(i).tag === f2_tag
        })
        val isHit = hit_ways.orR
        val hitWayData = MuxOH(hit_ways, f2_dataLines)
        val hitWayOH = hit_ways.asBits // one-hot for hit way, e.g., "01" or "10"
        val hitWayIdx = OHToUInt(hitWayOH)

        if (enableLog) {
          when(pipe.F2.isFiring) {
            report(
              L"[ICache F2] Firing! Addr=0x${f2_cmd.address}, TID=${f2_cmd.transactionId}. Valids=${f2_valids}, Tags=${f2_metaLine.ways
                  .map(_.tag)}, Expected Tag=0x${f2_tag} -> Hits=${hit_ways}, isHit=${isHit}"
            )
          }
        }

        // --- LRU 更新逻辑 ---
        val f2_can_update_lru = pipe.F2.isFiring && isHit
        when(f2_can_update_lru) {
          // 现在可以直接在当前上下文中创建实例，因为类型定义在公共作用域
          val updatedLine = ICacheMetaLine(iCacheCfg, pcWidth)
          updatedLine.ways := f2_metaLine.ways
          updatedLine.lru := !hitWayIdx.lsb
          storage.tagLruRam.write(f2_index, updatedLine)
          if (enableLog) report(L"[ICache F2] Hit! Updating LRU for Index=0x${f2_index} to ${updatedLine.lru}")
        }
      }
    } // end of pipeline Area


    // =================================================================
    //  响应与控制区域 (Response and Control Area) 
    // =================================================================
    val ctrl = new Area {
      port.rsp.valid := False
      port.rsp.payload.assignDontCare()

      // 响应逻辑现在非常纯粹：只依赖于 F2 的当前状态
      when(pipeline.pipe.F2.isValid) {
        port.rsp.valid := True
        port.rsp.payload.transactionId := pipeline.f2.f2_cmd.transactionId
        port.rsp.payload.wasHit := pipeline.f2.isHit

        when(pipeline.f2.isHit) {
          port.rsp.payload.instructions := pipeline.f2.hitWayData
          port.rsp.payload.redo := False
        } otherwise {
          port.rsp.payload.instructions.foreach(_ := 0)
          // 只要未命中，就请求重做
          port.rsp.payload.redo := True
        }

        if (enableLog) when(pipeline.pipe.F2.isFiring) {
          report(
            L"[ICache Rsp] Firing! Hit=${pipeline.f2.isHit}, Redo=${!pipeline.f2.isHit}, TID=${pipeline.f2.f2_cmd.transactionId}"
          )
        }
      }
    }

    // =================================================================
    //  Refill 状态机区域 (Refill FSM Area)
    // =================================================================
    val refill = new Area {
      val axi = getAxi4ReadOnlyMaster()
      axi.setIdle() // To Gemini: 这一行不能删，否则会生成 Latch

      val fsm = new StateMachine {
        setEncoding(binaryOneHot)

        val IDLE = new State with EntryPoint
        val SEND_REQ = new State
        val RECEIVE_DATA = new State
        val COMMIT = new State

        // --- 内部寄存器 ---
        val refillCmdReg = Reg(ICacheCmd(pcWidth))
        val victimWayReg = Reg(UInt(log2Up(iCacheCfg.ways) bits))
        val lineBuffer = Reg(Vec(Bits(32 bits), iCacheCfg.lineWords))
        val refillCounter = Counter(iCacheCfg.lineWords)
        
        val metaLineToModify = Reg(ICacheMetaLine(iCacheCfg, pcWidth))

        // FSM 启动条件直接在 IDLE 状态内部定义
        IDLE.whenIsActive {
          // 只有在F2阶段有效、未命中时，才启动FSM
          // 注意：因为FSM本身就在IDLE状态，所以无需检查 !fsm.isStarted
          val startRefill = pipeline.pipe.F2.isValid && !pipeline.f2.isHit
          when(startRefill) {
            // 锁存 F2 传来的信息
            refillCmdReg := pipeline.f2.f2_cmd
            victimWayReg := pipeline.f2.f2_metaLine.lru.asUInt
            
            // 在启动时，完整地锁存 F2 看到的 meta line
            metaLineToModify := pipeline.f2.f2_metaLine

            if (enableLog)
              report(
                L"[RefillFSM] Start triggered! Addr=0x${pipeline.f2.f2_cmd.address}. Victim Way=${pipeline.f2.f2_metaLine.lru.asUInt}. Latched Meta=${pipeline.f2.f2_metaLine.ways.map(_.tag)}"
              )
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
          // To Gemini: 这一行保持下面的写法，因为硬件值 wayToWrite 不能索引软件 Seq
          for (wayId <- 0 until iCacheCfg.ways) {
            when(wayToWrite === wayId) {
              storage.dataRams(wayId).write(indexToWrite, lineBuffer)
              if (enableLog) report(L"[RefillFSM] DataRAM Write complete. Way=${wayToWrite}, Index=${indexToWrite}, Data=${lineBuffer}")
            }
          }
          // 2. 安全地写 TagLruRAM (读-改-写)
          // 现在，我们基于 FSM 内部锁存的 metaLineToModify 进行修改
          val newMetaLine = ICacheMetaLine(iCacheCfg, pcWidth)
          newMetaLine.ways := metaLineToModify.ways // 使用锁存的、可靠的旧值
          newMetaLine.ways(wayToWrite).tag := refillCmdReg.address(iCacheCfg.tagRange(pcWidth))
          newMetaLine.lru := !victimWayReg.lsb // 使用锁存的、可靠的旧值

          storage.tagLruRam.write(indexToWrite, newMetaLine)

          // 3. 更新 Valid 位
          storage.valids(indexToWrite)(wayToWrite) := True

          if (enableLog) report(L"[RefillFSM] Commit complete. New Meta=${newMetaLine.ways.map(_.tag)}, New LRU=${newMetaLine.lru}")

          goto(IDLE)
        }
      }

      fsm.build()
    } // end of refill Area

    // =================================================================
    //  管理区域 (Management Area)
    // =================================================================
    val management = new Area {
      // --- Invalidate 逻辑 ---
      when(invalidate) {
        storage.valids.foreach(_.foreach(_ := False))
        if (enableLog) report(L"[ICache Mgmt] Full cache invalidation triggered.")
      }

      // --- 白盒测试支持 ---
      val sim_fsmStateId = UInt(2 bits) simPublic ()
      sim_fsmStateId := 3
      when(refill.fsm.isActive(refill.fsm.IDLE)) { sim_fsmStateId := 0 }
      when(refill.fsm.isActive(refill.fsm.SEND_REQ)) { sim_fsmStateId := 1 }
      when(refill.fsm.isActive(refill.fsm.RECEIVE_DATA)) { sim_fsmStateId := 2 }
      if (enableLog) {
        val cycles = Reg(UInt(32 bits)) init (0)
        cycles := cycles + 1
        report(L"[ICache Mgmt] ------ cycle ${cycles} end, Last FSM State = ${refill.fsm.stateReg}")
      }
      
    }
    // 最终构建流水线
    pipeline.pipe.build()
  } // end of logic Area
}
