// filename: parallax/components/lsu/AguPlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._ // 核心：导入流水线库
import parallax.common._
import parallax.utilities._
import parallax.execute.BypassService
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxSim.notice
import parallax.utilities.Verification.assertFlushSilence

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
  val enableLog = false

  // --- 资源管理部分 ---
  private val portResources = ArrayBuffer[(AguPort, PrfReadPort, PrfReadPort)]()

  override def newAguPort(): AguPort = {
    this.framework.requireEarly()
    // 因为 newAguPort() 是在用户的 `early` 区域被调用的，所以这里的 `newReadPort()` 调用也是在 `early` 阶段
    val aguPort = AguPort(lsuConfig)
    val prfReadBase = setup.prfService.newPrfReadPort().setCompositeName(aguPort, "prfReadBase")
    val prfReadData = setup.prfService.newPrfReadPort().setCompositeName(aguPort, "prfReadData")
    portResources += ((aguPort, prfReadBase, prfReadData))
    aguPort
  }

  override def aguPort(index: Int): AguPort = portResources(index)._1

  val setup = create early new Area {
    val prfService = getService[PhysicalRegFileService]
    val bypassService = getServiceOption[BypassService[BypassMessage]]
    ParallaxLogger.log("[AguPlugin] AGU插件已创建，依赖服务获取完成")
  }

  // =========================================================================
  // === 核心逻辑 (完全重构) ===
  // =========================================================================
  val logic = create late new Area {
    val bypassService = setup.bypassService
    lock.await()
    val bypassFlow = bypassService.map(_.getBypassFlow("AguPlugin")).getOrElse(null)

    // 为每个请求的AGU端口创建一个独立的、带流水线的AGU核
    portResources.zipWithIndex.foreach { case ((externalPort, prfReadBase, prfReadData), i) =>
      ParallaxLogger.log(s"[AguPlugin] 创建基于 Pipeline 的 AGU 实例 $i")

      val aguPipe = new Pipeline {
        // --- 1. 定义流水线阶段 ---
        val S0 = newStage().setName(s"AGU_${i}_S0_Input")
        val S1 = newStage().setName(s"AGU_${i}_S1_Calc")

        // --- 2. 定义阶段间传递的数据 (Stageables) ---
        val UOP_IN       = Stageable(AguInput(lsuConfig))
        val PRF_BASE_RSP = Stageable(Bits(lsuConfig.dataWidth))
        val PRF_DATA_RSP = Stageable(Bits(lsuConfig.dataWidth))

        // 连接 S0 和 S1，采用M2S（主->从）连接，这是最常见的
        connect(S0, S1)(Connection.M2S())

        // --- 3. 构建 S0 阶段逻辑 (输入 & PRF读取) ---
        val s0_logic = new Area {
          S0.flushIt(externalPort.flush)

          S0.valid     := externalPort.input.valid
          S0(UOP_IN)   := externalPort.input.payload
          externalPort.input.ready := S0.isReady

          // 为PRF读取发起请求
          // 使用 isFiring 来确保只在流水线实际前进时才发起读请求
          prfReadBase.valid   := S0.isFiring
          prfReadBase.address := S0(UOP_IN).basePhysReg
          
          prfReadData.valid   := S0.isFiring && S0(UOP_IN).isStore
          prfReadData.address := S0(UOP_IN).dataReg

          // 将PRF的响应数据存入Stageable，以便传递到S1
          // 注意：这里假设PRF是组合读或1周期延迟读。
          // 这里的赋值发生在S0，数据将在S1中可用。
          S0(PRF_BASE_RSP) := prfReadBase.rsp
          S0(PRF_DATA_RSP) := prfReadData.rsp

          if(enableLog) when(S0.isFiring) {
            report(L"[AGU-${i}-S0-FIRING] PC=0x${S0(UOP_IN).pc}, robPtr=${S0(UOP_IN).robPtr}, isStore=${S0(UOP_IN).isStore}")
          }
        }

        // --- 4. 构建 S1 阶段逻辑 (计算 & 输出) ---
        val s1_logic = new Area {
          S1.flushIt(externalPort.flush)
          val u = S1(UOP_IN)

          val bypassLogic = new Area {
            val baseBypassValid = False
            val baseBypassData  = Bits(lsuConfig.dataWidth)
            val dataBypassValid = False
            val dataBypassData  = Bits(lsuConfig.dataWidth)
            baseBypassData.assignDontCare()
            dataBypassData.assignDontCare()
            if (bypassFlow != null) {
              when(S1.isValid && bypassFlow.valid) {
                val bypassPayload = bypassFlow.payload
                when(bypassPayload.physRegIdx === u.basePhysReg) {
                  baseBypassValid := True
                  baseBypassData  := bypassPayload.physRegData
                }
                when(u.isStore && bypassPayload.physRegIdx === u.dataReg) {
                  dataBypassValid := True
                  dataBypassData  := bypassPayload.physRegData
                }
              }
            }
          }

          val baseData = Mux(bypassLogic.baseBypassValid, bypassLogic.baseBypassData, S1(PRF_BASE_RSP))
          
          // storeData 的定义需要放在 bypassLogic 之后，但在使用它的 outP.storeData 之前
          val storeData = Bits(lsuConfig.dataWidth)
          storeData.assignDontCare()
          when(u.isStore) {
              storeData := Mux(bypassLogic.dataBypassValid, bypassLogic.dataBypassData, S1(PRF_DATA_RSP))
          }

          // =================================================
          // === 严格恢复的分区逻辑 START ===
          // =================================================
          val addressCalc = new Area {
            val baseValue = if (supportPcRel) Mux(u.usePc, u.pc, baseData.asUInt) else baseData.asUInt
            val extendedImm = u.immediate.resize(32).asUInt
            val effectiveAddress = baseValue + extendedImm
          }

          val mmioDetection = new Area {
            val isInMmioRange = Bool()
            val mmioHits = mmioRanges.map(_.contains(addressCalc.effectiveAddress))
            isInMmioRange := mmioHits.fold(False)(_ || _)
            when(S1.isFiring && isInMmioRange) {
              if(enableLog) notice(L"MMIO range hit! address=0x${addressCalc.effectiveAddress}")
            }
          }

          val alignmentCheck = new Area {
            val alignMask = UInt(3 bits)
            switch(u.accessSize) {
              is(MemAccessSize.B) { alignMask := 0x0 }
              is(MemAccessSize.H) { alignMask := 0x1 }
              is(MemAccessSize.W) { alignMask := 0x3 }
              is(MemAccessSize.D) { alignMask := 0x7 }
            }
            val mustAlign = u.accessSize =/= MemAccessSize.B
            val misaligned = (addressCalc.effectiveAddress & alignMask.resized) =/= 0
            val alignException = misaligned && mustAlign
          }
          
          val maskCalc = new Area {
              val numBytes = lsuConfig.dataWidth.value / 8
              val calculatedMask = Bits(numBytes bits)
              val addrLow = addressCalc.effectiveAddress(log2Up(numBytes) - 1 downto 0)
              val byteMask = B(1, numBytes bits) |<< addrLow
              val halfMask = B(3, numBytes bits) |<< (addrLow & ~U(1, log2Up(numBytes) bits))
              val wordMask = B(15, numBytes bits)
              calculatedMask := u.accessSize.mux(
                  MemAccessSize.B -> byteMask,
                  MemAccessSize.H -> halfMask,
                  MemAccessSize.W -> wordMask,
                  default -> B(BigInt(1) << numBytes - 1, numBytes bits)
              )
          }
          // =================================================
          // === 严格恢复的分区逻辑 END ===
          // =================================================
          
          if(enableLog) {
            when(S1.isFiring) { report(L"[AGU-${i}-S1-FIRING] PC=0x${u.pc}, robPtr=${u.robPtr}, EffAddr=0x${addressCalc.effectiveAddress}") }
            when(S1.isValid && !S1.isFiring) { report(L"[AGU-${i}-S1-STALLED] PC=0x${u.pc}, robPtr=${u.robPtr}") }
          }
          
          // --- S1驱动AGU的最终输出 ---
          val outStream = externalPort.output
          outStream.valid := S1.valid
          S1.haltIt(!outStream.ready)
          
          val outPayload = outStream.payload

          outPayload.address       := addressCalc.effectiveAddress
          outPayload.alignException  := alignmentCheck.alignException
          outPayload.storeMask     := maskCalc.calculatedMask
          outPayload.basePhysReg   := u.basePhysReg
          outPayload.immediate     := u.immediate
          outPayload.usePc         := u.usePc
          outPayload.pc            := u.pc
          outPayload.storeData     := storeData
          outPayload.robPtr        := u.robPtr
          outPayload.accessSize    := u.accessSize
          outPayload.isSignedLoad  := u.isSignedLoad
          outPayload.qPtr          := u.qPtr
          outPayload.isLoad        := u.isLoad
          outPayload.isStore       := u.isStore
          outPayload.physDst       := u.physDst
          outPayload.isFlush       := u.isFlush
          outPayload.isIO          := mmioDetection.isInMmioRange || u.isIO
          outPayload.isCoherent    := !mmioDetection.isInMmioRange && u.isCoherent
        }

        assertFlushSilence(externalPort.output, externalPort.flush, silenceCycles = 3)


        // --- 5. 构建流水线 ---
        build()
      }
    }
  }
}
