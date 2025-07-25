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
    val dCacheParams: DataCacheParameters,
    val defaultIsIO: Boolean = false
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
    hw.aguPort.flush := robFlushPort.valid
    

    // --- 1. EU输入处理 & 分流 ---
    val euIn = getEuInputPort
    val uop = euIn.payload
    val isStore = uop.memCtrl.isStore

    // --- 2. 将EU输入流转换为AGU输入流 ---
    val aguInStream = euIn.translateWith {
      val aguCmd = AguInput(lsuConfig)
      aguCmd.qPtr        := 0
      aguCmd.basePhysReg := uop.src1Tag
      aguCmd.immediate   := uop.imm.asSInt
      aguCmd.accessSize  := uop.memCtrl.size
      aguCmd.isSignedLoad:= uop.memCtrl.isSignedLoad
      aguCmd.usePc       := uop.usePc
      aguCmd.pc          := uop.pcData
      aguCmd.dataReg     := uop.src2Tag
      aguCmd.robPtr      := uop.robPtr
      aguCmd.isLoad      := !isStore
      aguCmd.isStore     := isStore
      aguCmd.isFlush     := False // 这里是 Cache 刷新信号，不是ROB 的
      aguCmd.isIO        := Bool(defaultIsIO) // 使用配置参数
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
        cmd.isIO               := aguOutPayload.isIO
        cmd.size               := aguOutPayload.accessSize
        cmd.isSignedLoad       := aguOutPayload.isSignedLoad
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
        cmd.isIO               := aguOutPayload.isIO
        cmd.hasEarlyException  := aguOutPayload.alignException
        cmd.earlyExceptionCode := ExceptionCode.STORE_ADDRESS_MISALIGNED
        cmd
    }

    // --- 4. 驱动 EuBasePlugin 的结果契约 (修正版) ---
    val dispatchCompleted = hw.lqPushPort.fire || hw.sbPushPort.fire
    val isStoreDispatch = hw.sbPushPort.fire

    // 默认情况下，euResult 无效，这个赋值在基类已经做了，这里注释掉
    // euResult.valid := False


    // 只有当分派事件发生时才进行处理
    when(dispatchCompleted) {
        ParallaxSim.logWhen(aguOutPayload.isLoad, L"[LsuEu] Dispatched LOAD to LQ: robPtr=${aguOutPayload.robPtr}")
        ParallaxSim.logWhen(aguOutPayload.isStore, L"[LsuEu] Dispatched STORE to SB: robPtr=${aguOutPayload.robPtr} pc=${uop.pcData} addr=${aguOutPayload.address} data=${aguOutPayload.storeData}")
        
        // =======================================================================
    // >> 关键修正 <<
    // =======================================================================
    // 对于Store指令，进入Store Buffer就可以认为其“执行阶段”完成。
    // ROB可以继续处理，等待该Store指令成为队头再提交到内存。
    // 因此，Store指令需要在此处向ROB报告完成。
    //
    // 对于Load指令，进入Load Queue仅仅是开始。它必须等待数据返回。
    // 因此，LsuEu绝对不能在此处为Load指令报告完成。这个责任完全在LoadQueuePlugin。
    // FIXME: 对于 LOAD 指令，其结果本应该写入 euResult
    // 这里没有写入，这会导致一系列严重问题：缺失 ROB 完成、旁路网络输出、唤醒的逻辑
      when(isStoreDispatch) {
          euResult.valid := True
          
          // 填充ROB需要的信息
          euResult.uop.robPtr          := aguOutPayload.robPtr
          euResult.uop.physDest.idx    := 0 // Store没有物理目标寄存器
          euResult.uop.writesToPhysReg := False

          euResult.writesToPreg  := False
          euResult.hasException  := aguOutPayload.alignException
          euResult.exceptionCode := ExceptionCode.STORE_ADDRESS_MISALIGNED
          euResult.destIsFpr     := False
      }
    }

    // 释放服务
    hw.aguServiceInst.release()
    hw.storeBufferServiceInst.release()
    hw.loadQueueServiceInst.release()
    hw.robServiceInst.release()

    ParallaxLogger.log(s"[LsuEu ${euName}] Logic defined.")
  }

  // LsuEu不直接参与数据旁路，因此旁路端口保持虚拟即可
  override lazy val bypassService = null
  override lazy val bypassOutputPort = Flow(BypassMessage(pipelineConfig))
}
