package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common.PipelineConfig // 假设RenameMapTableConfig可从PipelineConfig推断或独立配置
import parallax.utilities.{Framework, LockedImpl, ParallaxLogger, Plugin, Service}
import parallax.components.rename.{RenameMapTable, RenameMapTableConfig, RenameMapTableIo, SuperScalarFreeList, SuperScalarFreeListConfig, SuperScalarFreeListIO}


// --- RenameMapTableService ---
// 这个服务提供对RenameMapTable的访问
// 为了让RenamePlugin能够获取RAT的配置，我们让服务也持有它
class RenameMapTableService(
  val ratConfig: RenameMapTableConfig // 配置在实例化服务时传入
) extends Plugin with Service with LockedImpl { // 实现Service标记

  val ratIo = slave(RenameMapTableIo(ratConfig)) // 暴露RAT的IO，方向从服务的角度看

  val setup = create early new Area{
    lock.retain() // 服务自身构建锁
  }

  val logic = create late new Area {
    lock.await() // 确保所有依赖本服务的setup完成（如果其他插件retain了本服务）
                 // 或者确保本服务自身的配置完成。

    val rat = new RenameMapTable(ratConfig) // 实例化RAT组件
    ratIo <> rat.io // 将内部RAT的IO连接到服务的IO接口

    ParallaxLogger.log(s"RenameMapTableService: RenameMapTable instantiated with ${ratConfig.numWritePorts} write ports.")
    lock.release() // 服务构建完成
  }
}

// --- FreeListService ---
class FreeListService(
  val freeListConfig: SuperScalarFreeListConfig // 配置在实例化服务时传入
) extends Plugin with Service with LockedImpl {

  val freeListIo = slave(SuperScalarFreeListIO(freeListConfig)) // 暴露FreeList的IO

  val setup = create early new Area{
    lock.retain()
  }

  val logic = create late new Area {
    lock.await()

    val freeList = new SuperScalarFreeList(freeListConfig) // 实例化FreeList组件
    freeListIo <> freeList.io

    ParallaxLogger.log(s"FreeListService: SuperScalarFreeList instantiated with ${freeListConfig.numAllocatePorts} allocate ports.")
    lock.release()
  }
}
