package parallax.execute

import spinal.core._
import spinal.lib._
import parallax.utilities.{Plugin, Service}
// PipelineConfig removed from constructor as per your last snippet,
// but payloadType might be derived from it where this plugin is instantiated.
// import parallax.common.PipelineConfig

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxLogger

trait BypassService[P <: Data] extends Service {

  /** EU 调用此方法来注册一个旁路数据源。
    * @param euName 产生此旁路数据的 EU 名称 (用于调试/追踪)
    * @return 一个 Flow[P]，EU 通过它发送旁路数据 (非阻塞)
    */
  def newBypassSource(euName: String): Flow[P] // Changed to Flow

  /** 旁路数据的消费者 (例如 Issue Queue) 调用此方法来获取旁路数据流。
    * @param consumerName 消费此旁路数据的单元名称
    * @return 一个 Flow[P]，包含所有源合并后的旁路数据 (非阻塞)
    */
  def getBypassFlow(consumerName: String): Flow[P] // Changed to Flow and renamed for clarity
}

// It's better to create a new plugin or make the pipelining optional
// to not break existing designs that might rely on the single-cycle behavior.
class BypassPlugin[P <: Data](
    val payloadType: HardType[P] // Explicitly pass the HardType for the generic payload P
) extends Plugin
    with BypassService[P] {

  private val bypassSourceFlows = ArrayBuffer[Flow[P]]() // Stores Flow[P] from EUs
  private val bypassSourceNames = ArrayBuffer[String]()

  private var mergedBypassFlow: Flow[P] = null // Final output will be Flow[P]

  override def newBypassSource( // Returns Flow[P]
      euName: String
  ): Flow[P] = {
    this.framework.requireEarly()
    val sourceFlow = Flow(payloadType) // Create a Flow endpoint
    bypassSourceFlows += sourceFlow
    bypassSourceNames += euName
    ParallaxLogger.log(
      s"BypassService: Registered bypass Flow source for EU '$euName' (Payload: ${payloadType.getClass.getSimpleName}). Total sources: ${bypassSourceFlows.length}"
    )
    sourceFlow
  }

  override def getBypassFlow( // Returns Flow[P]
      consumerName: String
  ): Flow[P] = {
    if (mergedBypassFlow == null) {
      ParallaxLogger.error(
        s"BypassService: Consumer '$consumerName' (Payload: ${payloadType.getClass.getSimpleName}) requested bypass Flow before it was built. Call in 'late' or after BypassPlugin.logic."
      )
      val errorFlow = Flow(payloadType)
      errorFlow.valid := False
      if (errorFlow.payload.isInstanceOf[Bundle]) {
        errorFlow.payload.assignDontCare()
      }
      return errorFlow
    }
    ParallaxLogger.log(
      s"BypassService: Providing merged bypass Flow to consumer '$consumerName' (Payload: ${payloadType.getClass.getSimpleName})."
    )
    mergedBypassFlow
  }

  // The logic area is where all the changes happen.
   val logic = create late new Area {
    if (bypassSourceFlows.nonEmpty) {
      if (bypassSourceFlows.length > 1) {
        ParallaxLogger.log(
          s"BypassService: Building a pipelined arbiter for ${bypassSourceFlows.length} sources using .stage()"
        )

        // 1. 将输入的 Flow 转换为 Stream
        val streamsToArbiter = bypassSourceFlows.map(_.toStream)

        // 2. 使用标准的 StreamArbiter.on() 来创建仲裁后的流。
        //    这部分仍然是一个大的组合逻辑块。
        val arbitratedStream = StreamArbiterFactory().roundRobin.on(streamsToArbiter)

        // 3. 在仲裁器的输出端插入一个流水线阶段。
        //    这会在 arbitratedStream 的 valid 和 payload 路径上各插入一个寄存器。
        //    关键点：这有效地将长的组合逻辑路径（仲裁过程）与后续逻辑分开了。
        val pipelinedStream = arbitratedStream.stage()

        // 4. 将流水线化后的 Stream 转换回 Flow 提供给消费者。
        mergedBypassFlow = pipelinedStream.toFlow

      } else {
        // 单输入源的情况，同样需要增加一个周期的延迟以保持一致的Bypass延迟。
        mergedBypassFlow = bypassSourceFlows.head.stage() 
      }
    } else {
      val emptyFlow = Flow(payloadType)
      emptyFlow.valid := False
      if (emptyFlow.payload.isInstanceOf[Bundle]) {
        emptyFlow.payload.assignDontCare()
      }
      mergedBypassFlow = emptyFlow
      ParallaxLogger.log(
        s"BypassService: No bypass sources registered (Payload: ${payloadType.getClass.getSimpleName}). Merged Flow will be inactive."
      )
    }
    ParallaxLogger.log(s"PipelinedBypassService logic built (Payload: ${payloadType.getClass.getSimpleName}).")
  }
}
