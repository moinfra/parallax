// filename: hw/spinal/parallax/execute/LsuEuPlugin.scala
package parallax.execute

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._ // 仍然需要，因为 EuBasePlugin 依赖它
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.utilities._
import parallax.components.issue.IQEntryLsu
import parallax.components.lsu._
import scala.collection.mutable.ArrayBuffer

class LsuEuPlugin(
    override val euName: String,
    override val pipelineConfig: PipelineConfig,
    val lsuConfig: LsuConfig,
    val dCacheParams: DataCacheParameters
) extends EuBasePlugin(euName, pipelineConfig) {

  // =========================================================================
  // === EuBasePlugin 契约实现 (修正版) ===
  // =========================================================================

  // 关键修正：LSU EU 不再需要任何GPR读端口，所有读取由AGU服务完成。
  override def numGprReadPortsPerEu: Int = 0
  override def numFprReadPortsPerEu: Int = 0

  override type T_IQEntry = IQEntryLsu
  override def iqEntryType: HardType[IQEntryLsu] = HardType(IQEntryLsu(pipelineConfig))
  override def euRegType: EuRegType.C = EuRegType.GPR_ONLY // 仍然与GPR交互，但通过服务
  override def getEuType: ExeUnitType.E = ExeUnitType.MEM

  addMicroOp(BaseUopCode.LOAD)
  addMicroOp(BaseUopCode.STORE)

  // =========================================================================
  // === 硬件服务与端口获取 ===
  // =========================================================================
  val hw = create early new Area {
    val aguServiceInst = getService[AguService]
    val storeBufferServiceInst = getService[StoreBufferService]
    val loadQueueServiceInst = getService[LoadQueueService]
    val robServiceInst = getService[ROBService[RenamedUop]]

    // 从AGU服务获取端口。我们只需要一个，因为Load/Store可以仲裁后共用
    val aguPort = aguServiceInst.newAguPort()

    // 获取到后端队列的推送端口
    val sbPushPort = storeBufferServiceInst.getPushPort()
    val lqPushPort = loadQueueServiceInst.newPushPort()
    val robFlushPort = robServiceInst.getFlushPort()

    // 保留服务
        robServiceInst.retain() // 保留服务
    aguServiceInst.retain()
    storeBufferServiceInst.retain()
    loadQueueServiceInst.retain()
  }

  // =========================================================================
  // === EU 核心逻辑构建 ===
  // =========================================================================
  override def buildEuLogic(): Unit = {
    ParallaxLogger.log(s"[LsuEu ${euName}] Logic start definition.")
    hw.aguPort.flush := hw.robFlushPort.valid
    

    // --- 1. EU输入处理 & 分流 ---
    val euIn = getEuInputPort
    val uop = euIn.payload
    val isStore = uop.memCtrl.isStore

    // --- 2. 将EU输入流转换为AGU输入流 ---
    val aguInStream = euIn.translateWith {
      val aguCmd = AguInput(lsuConfig)
      aguCmd.qPtr        := 0 // AGU内部使用的指针，这里用不到
      aguCmd.basePhysReg := uop.src1Tag
      aguCmd.immediate   := uop.imm.asSInt
      aguCmd.accessSize  := uop.memCtrl.size
      aguCmd.usePc       := False // 假设PC相对寻址已在解码阶段处理
      aguCmd.pc          := 0
      aguCmd.dataReg     := uop.src2Tag // AGU需要知道从哪个寄存器读store data
      aguCmd.robPtr      := uop.robPtr
      aguCmd.isLoad      := !isStore
      aguCmd.isStore     := isStore
      aguCmd.isFlush     := False // Flush由ROB直接控制AGU
      aguCmd.physDst     := uop.physDest.idx
      aguCmd
    }

    // 将转换后的流连接到AGU端口
    hw.aguPort.input << aguInStream

    // --- 3. AGU响应处理 & 分派到后端队列 ---
    val aguOutStream = hw.aguPort.output
    val aguOutPayload = aguOutStream.payload

    // 将AGU输出流分流给Load Queue和Store Buffer
    val (lqDispatchStream, sbDispatchStream) = StreamDemux.two(aguOutStream, aguOutPayload.isStore)

    // 连接到Load Queue
    hw.lqPushPort << lqDispatchStream.translateWith {
        val cmd = LoadQueuePushCmd(pipelineConfig, lsuConfig)
        cmd.robPtr             := aguOutPayload.robPtr
        cmd.pdest              := aguOutPayload.physDst
        cmd.address            := aguOutPayload.address
        cmd.size               := aguOutPayload.accessSize
        cmd.hasEarlyException  := aguOutPayload.alignException
        cmd.earlyExceptionCode := ExceptionCode.LOAD_ADDR_MISALIGNED
        cmd
    }

    // 连接到Store Buffer
    hw.sbPushPort << sbDispatchStream.translateWith {
        val cmd = StoreBufferPushCmd(pipelineConfig, lsuConfig)
        cmd.addr               := aguOutPayload.address
        cmd.data               := aguOutPayload.storeData // 正确：使用AGU提供的storeData
        cmd.be                 := aguOutPayload.storeMask
        cmd.robPtr             := aguOutPayload.robPtr
        cmd.accessSize         := aguOutPayload.accessSize
        cmd.isFlush            := aguOutPayload.isFlush
        cmd.isIO               := False // TODO: Add MMIO detection
        cmd.hasEarlyException  := aguOutPayload.alignException
        cmd.earlyExceptionCode := ExceptionCode.STORE_ADDRESS_MISALIGNED
        cmd
    }

    // --- 4. 驱动 EuBasePlugin 的结果契约 ---
    // EU的"完成"事件，就是成功将AGU的结果推送到后端队列。
    // 这两个fire信号是互斥的，可以直接或操作。
    val dispatchCompleted = hw.lqPushPort.fire || hw.sbPushPort.fire

    // 我们需要知道是哪个AGU输出的信息触发了完成事件。
    // 因为lqPushPort和sbPushPort的valid信号直接来自aguOutStream.valid，
    // 所以当它们fire时，aguOutStream.payload中的数据是有效的。
    when(dispatchCompleted) {
        euResult.valid := True
        
        // `euResult.uop` 无法完美重建，因为AGU输出没有携带所有原始uop信息。
        // 但根据EuBasePlugin的实现，下游（ROB，Bypass等）主要关心
        // robPtr, physDest, writesToPreg, 和异常信息。我们填充这些关键字段。
        euResult.uop.robPtr       := aguOutPayload.robPtr
        euResult.uop.physDest.idx     := aguOutPayload.physDst.andMask(aguOutPayload.isLoad) // Store没有pdest
        euResult.uop.writesToPhysReg := aguOutPayload.isLoad // 只有Load指令最终会写回（由LQ完成）

        euResult.writesToPreg  := False // LsuEu自身不写寄存器
        euResult.hasException  := aguOutPayload.alignException
        euResult.exceptionCode := Mux(aguOutPayload.isLoad,
                                      ExceptionCode.LOAD_ADDR_MISALIGNED,
                                      ExceptionCode.STORE_ADDRESS_MISALIGNED)
        euResult.destIsFpr     := False

        ParallaxSim.logWhen(aguOutPayload.isLoad, L"[LsuEu] Dispatched LOAD to LQ: robPtr=${aguOutPayload.robPtr}")
        ParallaxSim.logWhen(aguOutPayload.isStore, L"[LsuEu] Dispatched STORE to SB: robPtr=${aguOutPayload.robPtr}")
    }

    // 释放服务
    hw.aguServiceInst.release()
    hw.storeBufferServiceInst.release()
    hw.loadQueueServiceInst.release()

    ParallaxLogger.log(s"[LsuEu ${euName}] Logic defined.")
  }

  // LsuEu不直接参与数据旁路，因此旁路端口保持虚拟即可
  override lazy val bypassService = null
  override lazy val bypassOutputPort = Flow(BypassMessage(pipelineConfig))
}
