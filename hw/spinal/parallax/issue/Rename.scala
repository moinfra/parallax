package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig // 假设RenameMapTableConfig可从PipelineConfig推断或独立配置
import parallax.utilities.{Framework, LockedImpl, ParallaxLogger, Plugin, Service}
import parallax.components.rename.{RenameMapTable, RenameMapTableConfig, RenameMapTableIo, SuperScalarFreeList, SuperScalarFreeListConfig, SuperScalarFreeListIO}
import parallax.components.rename.RenameUnit


// // --- RenameMapTableService ---
// // 这个服务提供对RenameMapTable的访问
// // 为了让RenamePlugin能够获取RAT的配置，我们让服务也持有它
// class RenameMapTableService(
//   val ratConfig: RenameMapTableConfig // 配置在实例化服务时传入
// ) extends Plugin with Service with LockedImpl { // 实现Service标记

//   val ratIo = slave(RenameMapTableIo(ratConfig)) // 暴露RAT的IO，方向从服务的角度看

//   val setup = create early new Area{
//     lock.retain() // 服务自身构建锁
//   }

//   val logic = create late new Area {
//     lock.await() // 确保所有依赖本服务的setup完成（如果其他插件retain了本服务）
//                  // 或者确保本服务自身的配置完成。

//     val rat = new RenameMapTable(ratConfig) // 实例化RAT组件
//     ratIo <> rat.io // 将内部RAT的IO连接到服务的IO接口

//     ParallaxLogger.log(s"RenameMapTableService: RenameMapTable instantiated with ${ratConfig.numWritePorts} write ports.")
//     lock.release() // 服务构建完成
//   }
// }

// // --- FreeListService ---
// class FreeListService(
//   val freeListConfig: SuperScalarFreeListConfig // 配置在实例化服务时传入
// ) extends Plugin with Service with LockedImpl {

//   val freeListIo = slave(SuperScalarFreeListIO(freeListConfig)) // 暴露FreeList的IO

//   val setup = create early new Area{
//     lock.retain()
//   }

//   val logic = create late new Area {
//     lock.await()

//     val freeList = new SuperScalarFreeList(freeListConfig) // 实例化FreeList组件
//     freeListIo <> freeList.io

//     ParallaxLogger.log(s"FreeListService: SuperScalarFreeList instantiated with ${freeListConfig.numAllocatePorts} allocate ports.")
//     lock.release()
//   }
// }

class RenamePlugin(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val flConfig: SuperScalarFreeListConfig
) extends Plugin {
  val setup = create early new Area {
    // getService for RAT/FL if they were services, or just instantiate
    // For simplicity, instantiate here. In a larger system, RAT/FL might be global services.
  }

  val logic = create late new Area {
    val s1_Rename = getService[IssuePipeline].pipeline.s1_rename
    val issueSignals = getService[IssuePipeline].signals // To access DECODED_UOPS, RENAMED_UOPS

    val renameUnit = new RenameUnit(pipelineConfig, ratConfig, flConfig)
    val rat = new RenameMapTable(ratConfig)
    val freeList = new SuperScalarFreeList(flConfig)

    // Connect RenameUnit to RAT and FreeList
    renameUnit.io.ratReadPorts <> rat.io.readPorts
    renameUnit.io.ratWritePorts <> rat.io.writePorts
    renameUnit.io.flAllocate <> freeList.io.allocate
    renameUnit.io.numFreePhysRegs := freeList.io.numFreeRegs // Note: direct assignment

    // --- Input to RenameUnit from s1_rename Stageable ---
    renameUnit.io.decodedUopsIn.valid := s1_Rename.valid && !s1_Rename.isStuck // s1_Rename.valid implies input is valid
    renameUnit.io.decodedUopsIn.payload := s1_Rename(issueSignals.DECODED_UOPS)
    s1_Rename.haltIt(renameUnit.io.decodedUopsIn.valid && !renameUnit.io.decodedUopsIn.ready) // Stall s1 if RU not ready

    // --- Output from RenameUnit to s1_rename Stageable ---
    // This drives s1_rename.output.valid
    when(renameUnit.io.renamedUopsOut.fire) { 
      s1_Rename(issueSignals.RENAMED_UOPS) := renameUnit.io.renamedUopsOut.payload
    } otherwise {
      // Ensure payload is safe if not firing (e.g. set all uops to invalid)
      s1_Rename(issueSignals.RENAMED_UOPS).foreach(_.decoded.isValid := False)
    }
    // Let the pipeline manage s1_rename.output.valid based on renameUnit.io.renamedUopsOut.valid
    // And s1_rename.output.ready will drive renameUnit.io.renamedUopsOut.ready
    // A direct way if s1_rename only contains this stream:
    // s1_Rename.output << renameUnit.io.renamedUopsOut.s1_Rename() // s1_Rename() converts Stream to Flow, then implicit conversion to Stage output
                                                       // This handles valid/ready propagation correctly.
    s1_Rename.haltWhen(!renameUnit.io.renamedUopsOut.valid)
    // Flush Logic
    renameUnit.io.flushIn := s1_Rename(issueSignals.FLUSH_PIPELINE) || s1_Rename.isFlushed // Or just s1_Rename.isFlushed
    
    // If RAT/FL need checkpoint/restore, connect those too (e.g., from a CommitPlugin service)
    // For now, assume they are reset externally or don't need explicit pipeline flush linkage here
    rat.io.checkpointRestore.valid := False // Default
    rat.io.checkpointRestore.payload.assignDontCare()
    freeList.io.restoreState.valid := False // Default
    freeList.io.restoreState.freeMask.assignDontCare()
  }
}
