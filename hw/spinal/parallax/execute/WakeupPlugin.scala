// filename: hw/spinal/parallax/execute/WakeupPlugin.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer

// Payload on the wakeup bus is just the physical register tag
case class WakeupPayload(pCfg: PipelineConfig) extends Bundle {
    val physRegIdx = UInt(pCfg.physGprIdxWidth)
}

// The service trait
trait WakeupService extends Service with LockedImpl {
    /** Called by EUs to broadcast a completed tag */
    // --- OLD ---
    // def newWakeupSource(): Flow[WakeupPayload]
    // +++ NEW +++
    // 增加一个可选的String参数traceName
    def newWakeupSource(traceName: String = "UnnamedWakeupSource"): Flow[WakeupPayload]

    /** Called by IQs to listen to all wakeup events */
    // --- OLD ---
    // def getWakeupFlow(): Flow[WakeupPayload]
    // +++ NEW +++
    // 返回一个包含所有唤醒源的Flow向量
    def getWakeupFlows(): Vec[Flow[WakeupPayload]]
}

// The plugin that implements the service by merging all sources
class WakeupPlugin(pCfg: PipelineConfig) extends Plugin with WakeupService {

    // --- OLD ---
    // private val wakeupSources = ArrayBuffer[Flow[WakeupPayload]]()
    // +++ NEW +++
    // 存储 Flow 和其对应的 traceName
    val wakeupSources = ArrayBuffer[(Flow[WakeupPayload], String)]()

    // --- OLD ---
    // private var mergedWakeupFlow: Flow[WakeupPayload] = null
    // +++ NEW +++
    // 我们将直接暴露一个包含所有源的Vec
    private var allWakeupFlows: Vec[Flow[WakeupPayload]] = null

    // setup Area不再需要做任何事
    val setup = create early new Area {}

    // --- OLD ---
    // override def newWakeupSource(): Flow[WakeupPayload] = { ... }
    // +++ NEW +++
    override def newWakeupSource(traceName: String = "UnnamedWakeupSource"): Flow[WakeupPayload] = {
        this.framework.requireEarly()
        val source = Flow(WakeupPayload(pCfg))
        wakeupSources += ((source, traceName)) // 将 Flow 和 traceName 一起存储
        source
    }

    // --- OLD ---
    // override def getWakeupFlow(): Flow[WakeupPayload] = { ... }
    // +++ NEW +++
    override def getWakeupFlows(): Vec[Flow[WakeupPayload]] = {
        if (allWakeupFlows == null) {
            SpinalError("getWakeupFlows() called before WakeupPlugin late logic phase.")
        }
        allWakeupFlows
    }

    val logic = create late new Area {
        
        ParallaxLogger.log(s"WakeupPlugin: Found ${wakeupSources.length} wakeup sources")
        
        // 创建一个硬件Vec，其大小等于唤醒源的数量
        // 我们只关心 Flow 部分来创建 Vec
        allWakeupFlows = Vec(wakeupSources.map(s => cloneOf(s._1)))

        lock.await()
        // 将软件中收集的每个源连接到硬件Vec的对应端口
        // 遍历 wakeupSources，它现在是 (Flow, String) 对
        for (((sourceFlow, sourceName), idx) <- wakeupSources.zipWithIndex) {
            allWakeupFlows(idx) << sourceFlow
        }

        // ======================= 调试日志（非常重要！）=======================
        // 打印出所有同时有效的唤醒信号
        // 遍历 allWakeupFlows 和原始的 wakeupSources（包含 traceName）
        for (((flow, traceName), idx) <- allWakeupFlows.zip(wakeupSources.map(_._2)).zipWithIndex) {
            when(flow.valid) {
                // 使用 traceName 进行日志记录
                ParallaxSim.log(L"WakeupPlugin: Broadcasting from source[${idx}] ('${traceName}') for physReg=p${flow.payload.physRegIdx}")
            }
        }
        // ======================= 日志结束 =======================
    }
}
