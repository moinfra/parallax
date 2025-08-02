// filename: src/main/scala/parallax/issue/RenamePlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.rename._
import parallax.components.rob.ROBService
import parallax.utilities.{Plugin, LockedImpl, ParallaxLogger}
import parallax.components.rename.BusyTableService
import parallax.utilities.ParallaxSim.notice
import parallax.utilities.ParallaxSim.fatal
import parallax.utilities.ParallaxSim.debug

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SimpleFreeListConfig
) extends Plugin
    with LockedImpl {

  val enableLog = false
  // --- 定义新的流水线载荷 (Stageable) ---
  // 用于从 S1 传递分配好的物理寄存器号到 S2
  val ALLOCATED_PHYS_REGS = Stageable(Vec.fill(pipelineConfig.renameWidth)(UInt(ratConfig.physRegIdxWidth)))
  val ALLOC_REQUESTED_S1 = Stageable(Vec.fill(pipelineConfig.renameWidth)(Bool()))

  // --- 服务和流水线阶段的早期设置 ---
  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    val busyTableService = getService[BusyTableService]
    val rat = getService[RatControlService]
    val freeList = getService[FreeListControlService]
    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val btSetBusyPorts = busyTableService.newSetPort()

    issuePpl.retain(); busyTableService.retain();

    val s1_rename = issuePpl.pipeline.s1_rename
    val s2_rob_alloc = issuePpl.pipeline.s2_rob_alloc
    val signals = issuePpl.signals
  }
  val doGlobalFlush = Bool()

  // --- 核心逻辑实现 ---
  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val s1_rename = setup.s1_rename
    val s2_rob_alloc = setup.s2_rob_alloc
    val signals = setup.signals
    val rat = setup.rat
    val freeList = setup.freeList
    val busyTableService = setup.busyTableService
    val renameUnit = setup.renameUnit

    getServiceOption[HardRedirectService].foreach(hr => {
      doGlobalFlush := hr.doHardRedirect()
      when(doGlobalFlush) {
        s1_rename.flushIt()
      }
    })

    // ================================================================
    // ==           S1: 物理寄存器分配阶段                          ==
    // ================================================================

    val successCounter = RegInit(U(0, 6 bits))
    val consumeCounter = RegInit(U(0, 6 bits))

    when(doGlobalFlush) {
      successCounter := U(0)
      consumeCounter := U(0)
    }

    val s1_logic = new Area {
      val decodedUops = s1_rename(signals.DECODED_UOPS)
      val flAllocPorts = freeList.getAllocatePorts()
      val allocationGrantedReg = Vec.fill(pipelineConfig.renameWidth)(RegInit(False)) // 避免流水线暂停时重复分配
      val allocatedPhysRegsReg = Vec.fill(pipelineConfig.renameWidth)(Reg(UInt(ratConfig.physRegIdxWidth)))

      when(flAllocPorts(0).success) {
          successCounter := successCounter + 1
      }

      // 1. 根据微操需求，在 S1 发起分配请求
      val needsPhysRegVec = Vec(decodedUops.map(_.writeArchDestEn))
      for (i <- 0 until pipelineConfig.renameWidth) {
        // 请求条件：需要寄存器，且【尚未】为当前指令分配成功
        val wantsToAllocate = s1_rename.isValid && needsPhysRegVec(i) && !allocationGrantedReg(i)
        flAllocPorts(i).enable := wantsToAllocate // FreeList 会在 enable 为 True 时分配物理寄存器，如果持续 True，会导致分配泄露
        when(wantsToAllocate) {
          if(enableLog) debug(L"pc=${decodedUops(i).pc} wantsToAllocate is true and success=${flAllocPorts(i).success}," :+
          L" in.valid = ${s1_rename.isValid} in.ready = ${s1_rename.isReady} ":+
          L"granted_reg=${allocationGrantedReg(i)} allocated_reg=${flAllocPorts(i).physReg} " :+
          L"isFiring=${s1_rename.isFiring} isFlushed=${s1_rename.isFlushed}"
          )
        }

        // 当分配成功时，锁存这个状态
        when(flAllocPorts(i).success) {
          allocationGrantedReg(i) := True
          assert(!allocationGrantedReg(i), "Alread allocated, but allocation granted again")
        }
      }

      when(s1_rename.isFireing || doGlobalFlush) {
        allocationGrantedReg.foreach(_ := False)
        allocatedPhysRegsReg.foreach(_ := U(0))
      }

      // 2. 检查分配结果 (组合逻辑)
      val allocationOk = (0 until pipelineConfig.renameWidth).map { i =>
        !needsPhysRegVec(i) || allocationGrantedReg(i) || flAllocPorts(i).success
      }.andR

      // 3. 如果分配失败，暂停 S1
      when(!allocationOk) {
        s1_rename.haltIt()
        if(enableLog) notice(L"[RegAlloc] S1: Failed to allocate physical registers. Halting pipeline at S1.")
      }

      for(i <- 0 until pipelineConfig.renameWidth) {
        when(flAllocPorts(i).success) {
          allocatedPhysRegsReg(i) := flAllocPorts(i).physReg
        }
        // 如果已经分配过，就使用锁存的值；否则使用当前周期分配的值
        s1_rename(ALLOCATED_PHYS_REGS)(i) := Mux(allocationGrantedReg(i), allocatedPhysRegsReg(i), flAllocPorts(i).physReg)
      }
    }

    // ================================================================
    // ==           S2: 重命名与状态写回阶段                         ==
    // ================================================================
    val s2_logic = new Area {
      val decodedUopsInS2 = s2_rob_alloc(signals.DECODED_UOPS)
      // 从 S1 取得已经分配好的物理寄存器
      val allocatedPhysRegs = s2_rob_alloc(ALLOCATED_PHYS_REGS)

      // --- 将 RenameUnit 的连接放到一个独立的 Area 中 ---
      val renameArea = new Area {
        renameUnit.io.decodedUopsIn := decodedUopsInS2
        renameUnit.io.flush := doGlobalFlush || RegNext(doGlobalFlush) || !s2_rob_alloc.isFiring
        rat.getReadPorts() <> renameUnit.io.ratReadPorts
        // 直接将 S1 分配好的物理寄存器送入 RenameUnit
        renameUnit.io.physRegsIn := allocatedPhysRegs
      }

      // --- 将重命名结果写回流水线和状态表 ---
      // 默认值设置
      s2_rob_alloc(signals.RENAMED_UOPS).foreach(_.setDefault())
      rat.getWritePorts().foreach { port =>
        port.wen := False
        port.archReg.assignDontCare()
        port.physReg.assignDontCare()
      }
      setup.btSetBusyPorts.foreach { port =>
        port.valid := False
        port.payload.assignDontCare()
      }

      // 当 S2 有效地向下游传递指令时 (isFireing)，更新所有状态
      when(s2_rob_alloc.isFireing) {
        // 1. 将 RenameUnit 的结果放入 Stageable
        val renamedUops = renameUnit.io.renamedUopsOut
        s2_rob_alloc(signals.RENAMED_UOPS) := renamedUops

        // 2. 写回状态表 (SRAT 和 BusyTable)
        rat.getWritePorts().zip(renamedUops).foreach { case (ratPort, uop) =>
          ratPort.wen := uop.rename.allocatesPhysDest && !doGlobalFlush && !RegNext(doGlobalFlush)
          ratPort.archReg := uop.decoded.archDest.idx
          ratPort.physReg := uop.rename.physDest.idx
        }

        for (i <- 0 until pipelineConfig.renameWidth) {
          val uopOut = renamedUops(i)
          setup.btSetBusyPorts(i).valid := uopOut.rename.allocatesPhysDest && !doGlobalFlush && !RegNext(doGlobalFlush)
          setup.btSetBusyPorts(i).payload := uopOut.rename.physDest.idx

          // 可选的调试信息
          when(uopOut.rename.allocatesPhysDest) {
            if(enableLog) notice(L"uop@${uopOut.decoded.pc} renamed with p${uopOut.rename.physDest.idx} (allocated in S1)")
          }
        }
        
        when(renamedUops(0).rename.allocatesPhysDest) {
            consumeCounter := consumeCounter + 1
        }

        val prevSuccCounter = RegNext(successCounter, init = U(0))
        when(consumeCounter =/= prevSuccCounter){
          if(enableLog) fatal(L"Some pregs are leaked or reused!!! Allocated: ${prevSuccCounter}, Consumed: ${consumeCounter}")
        }
      }
    }

    // --- 资源释放 ---
    setup.issuePpl.release();
    setup.busyTableService.release();
  }
}
