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

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SimpleFreeListConfig
) extends Plugin
    with LockedImpl {

  val enableLog = false

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
    // 获取在 setup 中创建的 renameUnit 实例
    val renameUnit = setup.renameUnit

    // ================================================================
    // ==           S1: 乐观请求与数据缓冲阶段                      ==
    // ================================================================
    val s1_logic = new Area {
      val decodedUops = s1_rename(signals.DECODED_UOPS)
      val needsPhysRegVec = Vec(decodedUops.map(_.writeArchDestEn))

      // 乐观地发出 FreeList 分配请求
      for (i <- 0 until pipelineConfig.renameWidth) {
        freeList.getAllocatePorts()(i).enable := s1_rename.isValid && needsPhysRegVec(i)
      }

      // 将请求掩码传递到 S2
      s1_rename(signals.NEEDS_PHYS_REG) := needsPhysRegVec
    }

    // ================================================================
    // ==           S2: 验证、重命名与写回阶段                     ==
    // ================================================================
    val s2_logic = new Area {
      val decodedUopsInS2 = s2_rob_alloc(signals.DECODED_UOPS)
      val lastCycleNeedsPhysReg = s2_rob_alloc(signals.NEEDS_PHYS_REG)
      val freeListPorts = freeList.getAllocatePorts()

      // 1. 验证 FreeList 结果
      val allocationOk = (0 until pipelineConfig.renameWidth).map { i =>
        !lastCycleNeedsPhysReg(i) || freeListPorts(i).success
      }.andR

      // 2. 如果分配失败，暂停流水线 (RobAllocPlugin 会处理 ROB 满的暂停)
      when(s2_rob_alloc.isValid && !allocationOk) {
        s2_rob_alloc.haltIt()
      }

      // --- 核心修改：在 S2 中连接 renameUnit 的所有输入 ---
      renameUnit.io.decodedUopsIn := decodedUopsInS2
      rat.getReadPorts() <> renameUnit.io.ratReadPorts
      for (i <- 0 until pipelineConfig.renameWidth) {
        renameUnit.io.physRegsIn(i) := freeListPorts(i).physReg
      }

      // renameUnit 是纯组合逻辑，其输出在 S2 立即有效
      val finalRenamedUops = renameUnit.io.renamedUopsOut

      // 3. 将结果放入 Stageable 供 RobAllocPlugin 使用
      s2_rob_alloc(signals.RENAMED_UOPS)(0) := finalRenamedUops(0)
      assert(!s2_rob_alloc.isFiring || allocationOk, "ASSERTION FAILED: Firing S2 stage with failed FreeList allocation!")
      // 4. 写回状态表 (当流水线发射时)
      rat.getWritePorts().zip(finalRenamedUops).foreach { case (ratPort, uop) =>
        ratPort.wen := s2_rob_alloc.isFiring && uop.rename.allocatesPhysDest
        ratPort.archReg := uop.decoded.archDest.idx
        ratPort.physReg := uop.rename.physDest.idx
      }

      for (i <- 0 until pipelineConfig.renameWidth) {
        val uopOut = finalRenamedUops(i)
        setup.btSetBusyPorts(i).valid := s2_rob_alloc.isFiring && uopOut.rename.allocatesPhysDest
        setup.btSetBusyPorts(i).payload := uopOut.rename.physDest.idx
      }
    }

    // Flush 逻辑，与您原来的一致
    val flush = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        val doHardRedirect = hr.doHardRedirect()
        when(doHardRedirect) {
          s1_rename.flushIt()
        }
      })
    }

    // --- 资源释放 ---
    setup.issuePpl.release();
    setup.busyTableService.release();
  }
}
