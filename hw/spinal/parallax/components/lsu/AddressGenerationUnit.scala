// filename: parallax/components/lsu/AguPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities._
import parallax.execute.BypassService
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxSim.notice
import parallax.utilities.Verification.assertFlushSilence

// AGU旁路数据类型
// case class AguBypassData() extends Bundle {
//   val physRegIdx = UInt(6 bits)
//   val physRegData = Bits(32 bits)
//   val robPtr = UInt(6 bits)
// }

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

// AGU 输入定义
case class AguInput(lsuConfig: LsuConfig) extends Bundle with Formattable {
  val qPtr = UInt(lsuConfig.qPtrWidth)
  val basePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val immediate = SInt(lsuConfig.dataWidth)
  val accessSize = MemAccessSize()
  val isSignedLoad = Bool()
  val usePc = Bool()
  val pc = UInt(lsuConfig.pcWidth)
  val dataReg = UInt(lsuConfig.physGprIdxWidth)
  // 上下文信息
  val robPtr = UInt(lsuConfig.robPtrWidth)
  val isLoad = Bool()
  val isStore = Bool()
  val isFlush = Bool()
  val isIO = Bool()
  val isCoherent = Bool()
  val physDst = UInt(lsuConfig.physGprIdxWidth)

  def format: Seq[Any] = {
    Seq(
      L"AguInput(",
      L"qPtr=${qPtr},",
      L"basePhysReg=p${basePhysReg},",
      L"immediate=${immediate},",
      L"accessSize=${accessSize},",
      L"isSignedLoad=${isSignedLoad},",
      L"usePc=${usePc},",
      L"pc=${pc},",
      L"dataReg=p${dataReg},",
      L"robPtr=${robPtr},",
      L"isLoad=${isLoad},",
      L"isStore=${isStore},",
      L"isFlush=${isFlush},",
      L"isIO=${isIO},",
      L"isCoherent=${isCoherent},",
      L"physDst=${physDst})"
    )
  }
}

// AGU 输出定义
case class AguOutput(lsuConfig: LsuConfig) extends Bundle with Formattable {
  val qPtr = UInt(lsuConfig.qPtrWidth)
  val address = UInt(lsuConfig.pcWidth)
  val alignException = Bool()
  val accessSize = MemAccessSize()
  val isSignedLoad = Bool()
  val storeMask = Bits(lsuConfig.dataWidth.value / 8 bits)
  // 透传上下文信息
  val basePhysReg = UInt(lsuConfig.physGprIdxWidth)
  val immediate = SInt(lsuConfig.dataWidth)
  val usePc = Bool()
  val pc = UInt(lsuConfig.pcWidth)
  val robPtr = UInt(lsuConfig.robPtrWidth)
  val isLoad = Bool()
  val isStore = Bool()
  val physDst = UInt(lsuConfig.physGprIdxWidth)
  val storeData = Bits(lsuConfig.dataWidth)
  val isFlush = Bool()
  val isIO = Bool()
  val isCoherent = Bool()

  def format: Seq[Any] = {
    Seq(
      L"AguOutput(",
      L"qPtr=${qPtr},",
      L"address=${address},",
      L"alignException=${alignException},", // 修正了原始代码中多余的 ')'
      L"accessSize=${accessSize},",
      L"isSignedLoad=${isSignedLoad},",
      L"storeMask=${storeMask},",
      L"basePhysReg=p${basePhysReg},",
      L"immediate=${immediate},",
      L"usePc=${usePc},",
      L"pc=${pc},",
      L"robPtr=${robPtr},",
      L"isLoad=${isLoad},",
      L"isStore=${isStore},",
      L"isIO=${isIO},",
      L"isCoherent=${isCoherent},",
      L"physDst=${physDst},",
      L"storeData=${storeData})"
    )
  }
}

// MMIO地址范围定义
case class MmioRange(start: UInt, end: UInt) {
  def contains(address: UInt): Bool = address >= start && address <= end
}

class AguPlugin(
    lsuConfig: LsuConfig,
    supportPcRel: Boolean = true,
    mmioRanges: Seq[MmioRange] = Seq()
) extends Plugin
    with AguService
    with LockedImpl {
  val enableLog = true

  // --- 结构性修复 1: 改变数据结构以存储绑定的资源 ---
  // ArrayBuffer 现在存储一个元组: (外部请求的AGU端口, 为其创建的PRF基址读端口, 为其创建的PRF数据读端口)
  private val portResources = ArrayBuffer[(AguPort, PrfReadPort, PrfReadPort)]()

  // newAguPort 现在不仅创建 AguPort，还立即创建它所依赖的 PRF 端口
  override def newAguPort(): AguPort = {
    this.framework.requireEarly()
    // 因为 newAguPort() 是在用户的 `early` 区域被调用的，所以这里的 `newReadPort()` 调用也是在 `early` 阶段
    val aguPort = AguPort(lsuConfig)
    val prfReadBase = setup.prfService.newPrfReadPort().setCompositeName(aguPort, "prfReadBase")
    val prfReadData = setup.prfService.newPrfReadPort().setCompositeName(aguPort, "prfReadData")
    
    // 将这个完整的“资源包”保存起来
    portResources += ((aguPort, prfReadBase, prfReadData))
    aguPort
  }

  override def aguPort(index: Int): AguPort = portResources(index)._1

  // --- 结构性修复 2: 在 setup 阶段获取服务实例 ---
  val setup = create early new Area {
    val prfService = getService[PhysicalRegFileService]
    val bypassService = getServiceOption[BypassService[BypassMessage]]
    ParallaxLogger.log("[AguPlugin] AGU插件已创建，依赖服务获取完成")
  }

  // --- 结构性修复 3: logic 阶段只做连接，不再请求资源 ---
  val logic = create late new Area {
    // setup.bypassService 的引用是安全的，因为 getBypassFlow 是在 logic 阶段调用的
    val bypassService = setup.bypassService

    ParallaxLogger.log("[AguPlugin] AGU开始生成逻辑，等待锁定")
    lock.await()

    val bypassFlow = bypassService.map(_.getBypassFlow("AguPlugin")).getOrElse(null)

    // 遍历已创建的资源包
    portResources.zipWithIndex.foreach { case ((externalPort, prfReadBase, prfReadData), i) =>
      ParallaxLogger.log(s"[AguPlugin] 创建AGU实例 $i")

      val aguCore = new Area {
        // --- 第一级 (S0): 指令分发 & PRF读取启动 ---
        val s0 = externalPort.input
        val s0_fire = s0.fire
        
        // 使用在 setup 阶段已经创建好的 prf 端口
        val regReadBase = prfReadBase
        regReadBase.valid := s0_fire
        regReadBase.address := s0.payload.basePhysReg

        val regReadData = prfReadData
        regReadData.valid := s0_fire && s0.payload.isStore
        regReadData.address := s0.payload.dataReg

        // --- 第二级 (S1): 旁路, 地址计算, 对齐检查 ---
        val s1 = new Area {
            val valid = RegNext(s0_fire, init = False) clearWhen (externalPort.flush)
            val payload = RegNextWhen(s0.payload, s0_fire)
            val prfBaseRsp = RegNext(regReadBase.rsp)
            val prfDataRsp = RegNext(regReadData.rsp)
        }
        
        // --- 增加 AGU S0 和 S1 阶段的详细输入日志 ---
        when(s0.fire) {
          if(enableLog) report(L"[AGU-${i}-S0-Input] PC=0x${s0.payload.pc}, basePhysReg=p${s0.payload.basePhysReg}, dataReg=p${s0.payload.dataReg}, isLoad=${s0.payload.isLoad}, isStore=${s0.payload.isStore}, robPtr=${s0.payload.robPtr}")
        }
        when(s1.valid) {
          if(enableLog) report(L"[AGU-${i}-S1-Input] PC=0x${s1.payload.pc}, basePhysReg=p${s1.payload.basePhysReg}, dataReg=p${s1.payload.dataReg}, prfBaseRsp=0x${s1.prfBaseRsp}, prfDataRsp=0x${s1.prfDataRsp}")
        }

        // --- storeData 依赖修复 ---
        val bypassLogic = new Area {
          val baseBypassValid: Bool = False
          val baseBypassData: Bits = B(0)
          val dataBypassValid: Bool = False
          val dataBypassData: Bits = B(0)

          // 增加旁路流状态日志
          if (bypassFlow != null) {
            when(s1.valid) { // 只要 s1 阶段有效，就打印旁路流的状态
              if(enableLog) report(L"[AGU-${i}-BypassFlowState] PC=0x${s1.payload.pc}, bypassFlow.valid=${bypassFlow.valid}, bypassFlow.physRegIdx=${bypassFlow.payload.physRegIdx}, bypassFlow.data=0x${bypassFlow.payload.physRegData}")
            }
            
            val bypassPayload = bypassFlow.payload
            when(bypassFlow.valid) {
              when(bypassPayload.physRegIdx === s1.payload.basePhysReg) {
                baseBypassValid := True
                baseBypassData := bypassPayload.physRegData
                if(enableLog) report(L"[AGU-${i}-BypassMatch] PC=0x${s1.payload.pc}, BaseReg Matched! PhysReg=p${s1.payload.basePhysReg}, BypassData=0x${bypassPayload.physRegData}")
              }
              when(s1.payload.isStore && bypassPayload.physRegIdx === s1.payload.dataReg) {
                dataBypassValid := True
                dataBypassData := bypassPayload.physRegData
                if(enableLog) report(L"[AGU-${i}-BypassMatch] PC=0x${s1.payload.pc}, DataReg Matched! PhysReg=p${s1.payload.dataReg}, BypassData=0x${bypassPayload.physRegData}")
              }
            }
          }
        }

        val baseData = Mux(bypassLogic.baseBypassValid, bypassLogic.baseBypassData, s1.prfBaseRsp)

        val storeData = Bits(lsuConfig.dataWidth)
        storeData.assignDontCare()
        when(s1.payload.isStore) {
            storeData := Mux(bypassLogic.dataBypassValid, bypassLogic.dataBypassData, s1.prfDataRsp)
        }

        val addressCalc = new Area {
          val baseValue = if (supportPcRel) Mux(s1.payload.usePc, s1.payload.pc, baseData.asUInt) else baseData.asUInt
          val extendedImm = s1.payload.immediate.resize(32).asUInt
          val effectiveAddress = baseValue + extendedImm
        }

        val mmioDetection = new Area {
          val isInMmioRange = Bool()
          val mmioHits = mmioRanges.map(_.contains(addressCalc.effectiveAddress))
          isInMmioRange := mmioHits.fold(False)(_ || _)
          when(isInMmioRange) {
            notice(L"MMIO range hit! address=0x${addressCalc.effectiveAddress}")
          }
        }

        val alignmentCheck = new Area {
          val alignMask = UInt(3 bits)
          switch(s1.payload.accessSize) {
            is(MemAccessSize.B) { alignMask := 0x0 }
            is(MemAccessSize.H) { alignMask := 0x1 }
            is(MemAccessSize.W) { alignMask := 0x3 }
            is(MemAccessSize.D) { alignMask := 0x7 }
          }
          val mustAlign = s1.payload.accessSize =/= MemAccessSize.B
          val misaligned = (addressCalc.effectiveAddress & alignMask.resized) =/= 0
          val alignException = misaligned && mustAlign
          assert(!s1.valid || !alignException, L"Alignment exception due to misaligned address 0x${addressCalc.effectiveAddress} (accessSize=${s1.payload.accessSize}, effAddr=${addressCalc.effectiveAddress}, mustAlign=${mustAlign}, misaligned=${misaligned})")

          // 原始的 AGU Debug 日志，现在更名为 AGU-S1-Output-Debug
          when(s1.valid) {
              ParallaxSim.log(
                L"[AGU-${i}-S1-Output-Debug] s1.payload=${s1.payload.format} " :+
                L"baseData=0x${(baseData)} " :+ // 打印最终选择的 baseData
                L"==> effAddr=0x${(addressCalc.effectiveAddress)}"
              )
          }
        }
        
        val maskCalc = new Area {
            val numBytes = lsuConfig.dataWidth.value / 8
            val calculatedMask = Bits(numBytes bits)
            val addrLow = addressCalc.effectiveAddress(log2Up(numBytes) - 1 downto 0)
            val byteMask = B(1, numBytes bits) |<< addrLow
            val halfMask = B(3, numBytes bits) |<< (addrLow & ~U(1, log2Up(numBytes) bits))
            val wordMask = B(15, numBytes bits)
            calculatedMask := s1.payload.accessSize.mux(
                MemAccessSize.B -> byteMask,
                MemAccessSize.H -> halfMask,
                MemAccessSize.W -> wordMask,
                default -> B(BigInt(1) << numBytes - 1, numBytes bits)
            )
        }
        
        // --- Stream.stage() 输出修复 ---
        val s1_stream = Stream(AguOutput(lsuConfig))
        s1_stream.valid := s1.valid && !externalPort.flush

        s1_stream.payload.address       := addressCalc.effectiveAddress
        s1_stream.payload.alignException  := alignmentCheck.alignException
        s1_stream.payload.storeMask     := maskCalc.calculatedMask
        s1_stream.payload.basePhysReg   := s1.payload.basePhysReg
        s1_stream.payload.immediate     := s1.payload.immediate
        s1_stream.payload.usePc         := s1.payload.usePc
        s1_stream.payload.pc            := s1.payload.pc
        s1_stream.payload.storeData     := storeData
        s1_stream.payload.robPtr        := s1.payload.robPtr
        s1_stream.payload.accessSize    := s1.payload.accessSize
        s1_stream.payload.isSignedLoad  := s1.payload.isSignedLoad
        s1_stream.payload.qPtr          := s1.payload.qPtr
        s1_stream.payload.isLoad        := s1.payload.isLoad
        s1_stream.payload.isStore       := s1.payload.isStore
        s1_stream.payload.physDst       := s1.payload.physDst
        s1_stream.payload.isFlush       := s1.payload.isFlush
        s1_stream.payload.isIO          := mmioDetection.isInMmioRange || s1.payload.isIO
        s1_stream.payload.isCoherent    := !mmioDetection.isInMmioRange && s1.payload.isCoherent

        externalPort.output << s1_stream.queueOfReg(size = 2).throwWhen(externalPort.flush)
        s0.ready := s1_stream.ready && !externalPort.flush
        assertFlushSilence(externalPort.output, externalPort.flush, silenceCycles = 3)
      }

      ParallaxLogger.log(s"[AguPlugin] AGU实例 $i 逻辑已连接")
    }
  }
}
