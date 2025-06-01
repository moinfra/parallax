package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import parallax.execute.BypassService
import scala.collection.mutable.ArrayBuffer

// AGU旁路数据类型
case class AguBypassData() extends Bundle {
  val physRegIdx = UInt(6 bits)
  val physRegData = Bits(32 bits)
  val robIdx = UInt(6 bits)
  val valid = Bool()
}

// AGU服务接口
trait AguService extends Service with LockedImpl {
  def newAguPort(): AguPort
  def aguPort(index: Int): AguPort
}

// AGU端口定义
case class AguPort() extends Bundle with IMasterSlave {
  val input = Stream(AguInput())
  val output = Stream(AguOutput())
  val flush = Bool()

  override def asMaster(): Unit = {
    master(input)
    slave(output)
    out(flush)
  }
}

case class AguInput() extends Bundle with Formattable {
  val basePhysReg = UInt(6 bits)
  val immediate = SInt(12 bits)
  val accessSize = UInt(3 bits)
  val usePc = Bool()
  val pc = UInt(32 bits)

  // 上下文信息
  val robId = UInt(6 bits)
  val isLoad = Bool()
  val isStore = Bool()
  val physDst = UInt(6 bits)

  def format: Seq[Any] = {
    Seq(
      L"AguInput(",
      L"basePhysReg=${basePhysReg},",
      L"immediate=${immediate},",
      L"accessSize=${accessSize},",
      L"usePc=${usePc},",
      L"pc=${pc})"
    )
  }
}

case class AguOutput() extends Bundle with Formattable{
  val address = UInt(32 bits)
  val alignException = Bool()

  // 透传上下文信息
  val robId = UInt(6 bits)
  val isLoad = Bool()
  val isStore = Bool()
  val physDst = UInt(6 bits)
  
  def format: Seq[Any] = {
    Seq(
      L"AguOutput(",
      L"address=${address},",
      L"alignException=${alignException})"
    )
  }
}

class AguPlugin(
    supportPcRel: Boolean = true
) extends Plugin
    with AguService
    with LockedImpl {

  private val aguPortRequests = ArrayBuffer[AguPort]()

  override def newAguPort(): AguPort = {
    val port = AguPort()
    aguPortRequests += port
    port
  }

  override def aguPort(index: Int): AguPort = aguPortRequests(index)

  // 依赖服务
  private var prfService: PhysicalRegFileService = null
  private var bypassService: BypassService[AguBypassData] = null

  val setup = create early new Area {
    // 获取物理寄存器服务
    prfService = getService[PhysicalRegFileService]

    // 获取旁路服务
    bypassService = getService[BypassService[AguBypassData]]

    ParallaxLogger.log("[AguPlugin] AGU插件已创建，依赖服务获取完成")
  }

  val logic = create late new Area {
    ParallaxLogger.log("[AguPlugin] AGU开始生成逻辑，等待锁定")
    lock.await()

    // 获取统一的旁路数据流
    val bypassFlow = bypassService.getBypassFlow("AguPlugin")

    // 为每个AGU端口创建实例
    aguPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      ParallaxLogger.log(s"[AguPlugin] 创建AGU实例 $i")

      // 创建AGU核心逻辑
      val aguCore = new Area {
        // Stage0 流水线寄存器
        val stage0 = new Area {
          val fire = externalPort.input.valid && externalPort.input.ready && !externalPort.flush
          report(L"[AGU ${i}] Fire = ${fire} because valid=${externalPort.input.valid} and ready=${externalPort.input.ready} and flush=${externalPort.flush}")
          when(externalPort.flush) { report(L"[AGU ${i}] Flush") }
          val payload = RegNextWhen(externalPort.input.payload, fire)
          val valid = RegNext(fire, init = False) clearWhen (externalPort.flush)
          report(L"[AGU ${i}] valid = ${valid}, payload = ${payload.format}")
        }
        when(stage0.valid) {
          report(L"[AGU ${i}] 输入有效 ${stage0.payload.format}")
        }
        // 获取寄存器读端口
        val regRead = prfService.newReadPort()
        regRead.valid := stage0.valid
        regRead.address := stage0.payload.basePhysReg
        val regReadRsp = regRead.rsp
        when(regRead.valid) { report(L"[AGU ${i}] 寄存器读端口地址为 ${stage0.payload.basePhysReg}, 读端口响应为 ${regReadRsp.asUInt}") }
        // 旁路逻辑：使用BypassService提供的统一旁路流
        val bypassLogic = new Area {
          // 检查旁路命中
          val hit = bypassFlow.valid &&
            bypassFlow.payload.valid &&
            bypassFlow.payload.physRegIdx === stage0.payload.basePhysReg

          // 旁路数据选择
          val data = bypassFlow.payload.physRegData.asUInt
          val valid = hit
          when(valid) {
            report(L"[AGU ${i}] 旁路命中，使用旁路数据 ${data}")
          } otherwise {
            report(L"[AGU ${i}] 旁路未命中，使用寄存器读端口响应 ${regReadRsp.asUInt}")
          }
          ParallaxLogger.log(s"[AguPlugin] AGU实例 $i 旁路逻辑已连接到BypassService")
        }

        // 数据选择
        val baseData = bypassLogic.valid ? bypassLogic.data | regReadRsp.asUInt
        val baseReady = bypassLogic.valid || True // 假设寄存器总是就绪

        // 数据就绪计算
        val dataReady = if (supportPcRel) {
          stage0.payload.usePc || baseReady
        } else {
          baseReady
        }

        // 地址计算
        val addressCalc = new Area {
          val baseValue = if (supportPcRel) {
            Mux(stage0.payload.usePc, stage0.payload.pc, baseData)
          } else {
            baseData
          }

          val extendedImm = stage0.payload.immediate.resize(32).asUInt
          val effectiveAddress = baseValue + extendedImm
        }

        // 对齐检查
        val alignmentCheck = new Area {
          val alignMask = UInt(3 bits)
          switch(stage0.payload.accessSize) {
            is(0) { alignMask := 0x0 } // Byte
            is(1) { alignMask := 0x1 } // Half-word
            is(2) { alignMask := 0x3 } // Word
            is(3) { alignMask := 0x7 } // Double-word
            default { alignMask := 0x0 }
          }

          val mustAlign = stage0.payload.accessSize =/= 0
          val misaligned = (addressCalc.effectiveAddress & alignMask.resized) =/= 0
          val alignException = misaligned && mustAlign
        }

        // 输出逻辑
        externalPort.output.valid := stage0.valid && dataReady && !externalPort.flush
        externalPort.output.payload.address := addressCalc.effectiveAddress
        externalPort.output.payload.alignException := alignmentCheck.alignException

        // 透传上下文信息
        externalPort.output.payload.robId := stage0.payload.robId
        externalPort.output.payload.isLoad := stage0.payload.isLoad
        externalPort.output.payload.isStore := stage0.payload.isStore
        externalPort.output.payload.physDst := stage0.payload.physDst

        // Ready信号
        externalPort.input.ready := !externalPort.flush &&
          (!stage0.valid || (externalPort.output.ready && dataReady))
        when(externalPort.input.ready) {
          report (L"[AGU ${i}] 输入端口准备就绪")
        }
        
        when(externalPort.output.valid) {
          report(L"[AGU ${i}] 输出有效：${externalPort.output.payload.format}")
        } otherwise {
          report(L"[AGU ${i}] 输出无效")
        }
      }

      ParallaxLogger.log(s"[AguPlugin] AGU实例 $i 逻辑已连接")
    }

    ParallaxLogger.log(s"[AguPlugin] 总共创建了 ${aguPortRequests.length} 个AGU实例")
  }
}
