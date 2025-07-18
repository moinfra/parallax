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

  val logic = create late new Area {
    if (bypassSourceFlows.nonEmpty) {
      if (bypassSourceFlows.length > 1) {
        // 1. Convert input Flows from EUs to Streams.
        //    The .toStream adapter will drop data from the Flow if the StreamArbiter isn't ready for it in a cycle.
        //    This ensures the EU (driving the Flow) is not blocked.
        val streamsToArbiter = bypassSourceFlows.map(_.toStream)

        // 2. Arbitrate these streams.
        val arbitratedStream = StreamArbiterFactory().roundRobin.on(streamsToArbiter)

        // 3. Ensure the arbitrated stream is always ready to accept data from the arbiter's output.
        //    This prevents the arbiter from blocking its inputs if the final consumer (of the Flow) isn't "consuming".
        // arbitratedStream.ready := True

        // 4. Convert the always-ready arbitrated stream to a Flow for the consumer.
        mergedBypassFlow = arbitratedStream.toFlow

        ParallaxLogger.log(
          s"BypassService: Arbitrating ${bypassSourceFlows.length} bypass sources (Payload: ${payloadType.getClass.getSimpleName}) into one merged Flow."
        )
      } else {
        // Single source, no arbitration needed, directly use the Flow.
        mergedBypassFlow = bypassSourceFlows.head
        ParallaxLogger.log(
          s"BypassService: Using single bypass source Flow '${bypassSourceNames.head}' (Payload: ${payloadType.getClass.getSimpleName}) as merged Flow."
        )
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
    ParallaxLogger.log(s"BypassService logic built (Payload: ${payloadType.getClass.getSimpleName}).")
  }
}
