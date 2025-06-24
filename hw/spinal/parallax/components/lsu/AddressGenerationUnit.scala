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
  val robPtr = UInt(6 bits)
  val valid = Bool()
}

// AGU服务接口
trait AguService extends Service with LockedImpl {
  def newAguPort(): AguPort
  def aguPort(index: Int): AguPort
}

// AGU端口定义
case class AguPort(lsuConfig: LsuConfig) extends Bundle with IMasterSlave {
  val input = Stream(AguInput(lsuConfig))
  val output = Stream(AguOutput(lsuConfig))
  val flush = Bool()

  override def asMaster(): Unit = {
    master(input)
    slave(output)
    out(flush)
  }
}

case class AguInput(lsuConfig: LsuConfig) extends Bundle with Formattable {
  val qPtr = UInt(lsuConfig.qPtrWidth)
  val basePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val immediate = SInt(12 bits)
  val accessSize = MemAccessSize()
  val usePc = Bool()
  val pc = UInt(lsuConfig.pcWidth)
  val dataReg = UInt(lsuConfig.physGprIdxWidth)
  // 上下文信息
  val robPtr = UInt(lsuConfig.robPtrWidth)
  val isLoad = Bool()
  val isStore = Bool()
  val isFlush = Bool()
  val physDst = UInt(lsuConfig.physGprIdxWidth)

  def format: Seq[Any] = {
    Seq(
      L"AguInput(",
      L"qPtr=${qPtr},",
      L"basePhysReg=${basePhysReg},",
      L"immediate=${immediate},",
      L"accessSize=${accessSize},",
      L"usePc=${usePc},",
      L"pc=${pc},",
      L"dataReg=${dataReg},",
      L"robPtr=${robPtr},",
      L"isLoad=${isLoad},",
      L"isStore=${isStore},",
      L"isFlush=${isFlush},",
      L"physDst=${physDst})"
    )
  }
}

case class AguOutput(lsuConfig: LsuConfig) extends Bundle with Formattable {
  val qPtr = UInt(lsuConfig.qPtrWidth)
  val address = UInt(lsuConfig.pcWidth)
  val alignException = Bool()
  val accessSize = MemAccessSize()
  val storeMask = Bits(lsuConfig.dataWidth.value / 8 bits)
  // 透传上下文信息
  val robPtr = UInt(lsuConfig.robPtrWidth)
  val isLoad = Bool()
  val isStore = Bool()
  val physDst = UInt(lsuConfig.physGprIdxWidth)
  val storeData = Bits(lsuConfig.dataWidth)
  val isFlush = Bool()

  def format: Seq[Any] = {
    Seq(
      L"AguOutput(",
      L"qPtr=${qPtr},",
      L"address=${address},",
      L"alignException=${alignException})",
      L"accessSize=${accessSize},",
      L"storeMask=${storeMask},",
      L"robPtr=${robPtr},",
      L"isLoad=${isLoad},",
      L"isStore=${isStore},",
      L"physDst=${physDst})"
    )
  }
}

class AguPlugin(
    lsuConfig: LsuConfig,
    supportPcRel: Boolean = true
) extends Plugin
    with AguService
    with LockedImpl {
  val enableLog = false // 默认设置为 false
  private val aguPortRequests = ArrayBuffer[AguPort]()

  override def newAguPort(): AguPort = {
    val port = AguPort(lsuConfig)
    aguPortRequests += port
    port
  }

  override def aguPort(index: Int): AguPort = aguPortRequests(index)

  // 依赖服务
  private var prfService: PhysicalRegFileService = null
  private var bypassService: Option[BypassService[AguBypassData]] = null

  val setup = create early new Area {
    // 获取物理寄存器服务
    prfService = getService[PhysicalRegFileService]

    // 获取旁路服务
    bypassService = getServiceOption[BypassService[AguBypassData]]

    ParallaxLogger.log("[AguPlugin] AGU插件已创建，依赖服务获取完成")
  }

  val logic = create late new Area {
    ParallaxLogger.log("[AguPlugin] AGU开始生成逻辑，等待锁定")
    lock.await()

    // 获取统一的旁路数据流
    val bypassFlow = bypassService.map(_.getBypassFlow("AguPlugin")).getOrElse(null)

    // 为每个AGU端口创建实例
    aguPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      ParallaxLogger.log(s"[AguPlugin] 创建AGU实例 $i")

      // 创建AGU核心逻辑
      val aguCore = new Area {
        // Stage0 流水线寄存器
        val stage0 = new Area {
          val fire = externalPort.input.valid && externalPort.input.ready && !externalPort.flush
          if (enableLog) {
            report(
              L"[AGU ${i}] Fire = ${fire} because valid=${externalPort.input.valid} and ready=${externalPort.input.ready} and flush=${externalPort.flush}"
            )
          }
          if (enableLog) {
            when(externalPort.flush) { report(L"[AGU ${i}] Flush") }
          }
          val payload = RegNextWhen(externalPort.input.payload, fire)
          val valid = RegNext(fire, init = False) clearWhen (externalPort.flush)
          if (enableLog) {
            report(L"[AGU ${i}] valid = ${valid}, payload = ${payload.format}")
          }
        }
        if (enableLog) {
          when(stage0.valid) {
            report(L"[AGU ${i}] 输入有效 ${stage0.payload.format}")
          }
        }
        // 获取寄存器读端口
        val regReadBase = master(prfService.newReadPort())
        regReadBase.valid := stage0.valid
        regReadBase.address := stage0.payload.basePhysReg
        val regReadBaseRsp = regReadBase.rsp
        if (enableLog) {
          when(regReadBase.valid) {
            report(L"[AGU ${i}] 寄存器读端口地址为 ${stage0.payload.basePhysReg}, 读端口响应为 ${regReadBaseRsp.asUInt}")
          }
        }

        val regReadData = master(prfService.newReadPort())
        // 只有当是Store指令时才需要读取数据
        regReadData.valid := stage0.valid && stage0.payload.isStore
        regReadData.address := stage0.payload.dataReg // << 使用LsuInputCmd传来的dataReg索引
        val regReadDataRsp = regReadData.rsp

        // 旁路逻辑：使用BypassService提供的统一旁路流
        val bypassLogic = new Area {
          var baseBypassValid: Bool = False
          var baseBypassData: Bits = B(0)
          var dataBypassValid: Bool = False
          var dataBypassData: Bits = B(0)

          if (bypassFlow != null) {
            val bypassPayload = bypassFlow.payload
            when(bypassFlow.valid && bypassPayload.valid) {
              // 检查基址寄存器的旁路
              when(bypassPayload.physRegIdx === stage0.payload.basePhysReg) {
                baseBypassValid := True
                baseBypassData := bypassPayload.physRegData
              }
              // 检查Store数据寄存器的旁路 (仅当是Store指令时)
              when(stage0.payload.isStore && bypassPayload.physRegIdx === stage0.payload.dataReg) {
                dataBypassValid := True
                dataBypassData := bypassPayload.physRegData
              }
            }
          }
        }

        // 数据选择
        val baseData = Mux(bypassLogic.baseBypassValid, bypassLogic.baseBypassData, regReadBaseRsp)
        val storeData = Mux(bypassLogic.dataBypassValid, bypassLogic.dataBypassData, regReadDataRsp)
        val baseReady = bypassLogic.baseBypassValid || True // 假设寄存器总是就绪

        // 数据就绪计算
        val dataReady = if (supportPcRel) {
          stage0.payload.usePc || baseReady
        } else {
          baseReady
        }

        // 地址计算
        val addressCalc = new Area {
          val baseValue = if (supportPcRel) {
            Mux(stage0.payload.usePc, stage0.payload.pc, baseData.asUInt)
          } else {
            baseData.asUInt
          }

          val extendedImm = stage0.payload.immediate.resize(32).asUInt
          val effectiveAddress = baseValue + extendedImm
        }

        // 对齐检查
        val alignmentCheck = new Area {
          val alignMask = UInt(3 bits)
          switch(stage0.payload.accessSize) {
            is(MemAccessSize.B) { alignMask := 0x0 } // Byte
            is(MemAccessSize.H) { alignMask := 0x1 } // Half-word
            is(MemAccessSize.W) { alignMask := 0x3 } // Word
            is(MemAccessSize.D) { alignMask := 0x7 } // Double-word
          }

          val mustAlign = stage0.payload.accessSize =/= MemAccessSize.B
          val misaligned = (addressCalc.effectiveAddress & alignMask.resized) =/= 0
          val alignException = misaligned && mustAlign
        }

        val maskCalc = new Area {
          val calculatedMask = Bits(lsuConfig.dataWidth.value / 8 bits)
          val addrLow = addressCalc.effectiveAddress(log2Up(lsuConfig.dataWidth.value / 8) - 1 downto 0)

          switch(stage0.payload.accessSize) {
            is(MemAccessSize.B) { calculatedMask := (B(1) |<< addrLow).resized }
            is(MemAccessSize.H) { calculatedMask := (B(3) |<< (addrLow >> 1)).resized } // 0b0011, 0b1100
            is(MemAccessSize.W) { calculatedMask := B"1111" } // 假设 dataWidth 是 32
            is(MemAccessSize.D) { calculatedMask := B"1111" } // FIXME 目前不支持 64 位
          }
        }

        // 输出逻辑
        externalPort.output.valid := stage0.valid && dataReady && !externalPort.flush
        externalPort.output.payload.address := addressCalc.effectiveAddress
        externalPort.output.payload.alignException := alignmentCheck.alignException
        externalPort.output.payload.storeMask := maskCalc.calculatedMask

        // 透传上下文信息
        externalPort.output.payload.robPtr := stage0.payload.robPtr
        externalPort.output.payload.accessSize := stage0.payload.accessSize
        externalPort.output.payload.qPtr := stage0.payload.qPtr
        externalPort.output.payload.isLoad := stage0.payload.isLoad
        externalPort.output.payload.isStore := stage0.payload.isStore
        externalPort.output.payload.physDst := stage0.payload.physDst
        externalPort.output.payload.storeData := storeData
        externalPort.output.payload.isFlush := stage0.payload.isFlush

        // Ready信号
        externalPort.input.ready := !externalPort.flush &&
          (!stage0.valid || (externalPort.output.ready && dataReady))
        if (enableLog) {
          when(externalPort.input.ready) {
            report(L"[AGU ${i}] 输入端口准备就绪")
          }
        }

        if (enableLog) {
          when(externalPort.output.valid) {
            report(L"[AGU ${i}] 输出有效：${externalPort.output.payload.format}")
          } otherwise {
            report(L"[AGU ${i}] 输出无效")
          }
        }
      }

      ParallaxLogger.log(s"[AguPlugin] AGU实例 $i 逻辑已连接")
    }

    ParallaxLogger.log(s"[AguPlugin] 总共创建了 ${aguPortRequests.length} 个AGU实例")
  }
}
