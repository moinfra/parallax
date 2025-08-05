// filename: parallax/components/lsu/LoadQueuePlugin.scala
package parallax.components.lsu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob._
import parallax.components.dcache2._
import parallax.components.memory._
import parallax.utilities._
import scala.collection.mutable.ArrayBuffer
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.execute.WakeupService
import parallax.components.rename.BusyTableService

// --- 输入到Load Queue的命令 ---
// 这个命令由LsuEu在地址计算后发出
case class LoadQueuePushCmd(pCfg: PipelineConfig, lsuCfg: LsuConfig) extends Bundle {
  val robPtr            = UInt(lsuCfg.robPtrWidth)
  val pdest             = UInt(pCfg.physGprIdxWidth)
  val pc                = UInt(lsuCfg.pcWidth)
  val address           = UInt(lsuCfg.pcWidth)
  val isIO              = Bool()
  val isCoherent        = Bool()
  val size              = MemAccessSize()
  val isSignedLoad      = Bool()
  val hasEarlyException = Bool()
  val earlyExceptionCode = UInt(8 bits)
}

// --- Load Queue 服务接口 ---
trait LoadQueueService extends Service with LockedImpl {
  def getPushPort(): Stream[LoadQueuePushCmd]
}

// LoadQueueSlot 保持不变，但我们移除里面不再需要的字段（如baseReg, immediate等）
// 因为地址已经在LsuEu中计算好了
case class LoadQueueSlot(pCfg: PipelineConfig, lsuCfg: LsuConfig, dCacheParams: DataCacheParameters) extends Bundle with Formattable {
    val valid             = Bool()
    val pc                = UInt(lsuCfg.pcWidth)
    val address           = UInt(lsuCfg.pcWidth)
    val size              = MemAccessSize()
    val robPtr            = UInt(lsuCfg.robPtrWidth)
    val pdest             = UInt(pCfg.physGprIdxWidth)
    val isIO              = Bool()
    val isCoherent        = Bool()
    val isSignedLoad      = Bool()

    val hasException      = Bool()
    val exceptionCode     = UInt(8 bits)

    val isWaitingForFwdRsp    = Bool()
    val isStalledByDependency = Bool()
    val isReadyForDCache      = Bool() // 可以安全地向数据缓存（D-Cache）发出加载请求。
    val isWaitingForRsp = Bool()
    
    def setDefault(): this.type = {
        this.valid                 := False
        this.pc                    := 0
        this.address               := 0
        this.size                  := MemAccessSize.W
        this.robPtr                := 0
        this.pdest                 := 0
        this.isIO                  := False
        this.isCoherent            := False
        this.isSignedLoad          := False
        this.hasException          := False
        this.exceptionCode         := 0
        this.isWaitingForFwdRsp    := False
        this.isStalledByDependency := False
        this.isReadyForDCache      := False
        this.isWaitingForRsp := False
        this
    }

    def initFromPushCmd(cmd: LoadQueuePushCmd): this.type = {
        this.valid                 := True
        this.pc                    := cmd.pc
        this.address               := cmd.address
        this.size                  := cmd.size
        this.robPtr                := cmd.robPtr
        this.pdest                 := cmd.pdest
        this.isIO                  := cmd.isIO
        this.isCoherent            := cmd.isCoherent
        this.isSignedLoad          := cmd.isSignedLoad
        this.hasException          := cmd.hasEarlyException
        this.exceptionCode         := cmd.earlyExceptionCode
        
        this.isWaitingForFwdRsp    := False // Will be set to true next cycle if it queries
        this.isStalledByDependency := False
        this.isReadyForDCache      := False // Will be set by forwarding logic or early exception handling
        this.isWaitingForRsp := False
        this
    }

    def initFromAguOutput(cmd: AguOutput): this.type = {
        this.valid                 := True
        this.pc                    := cmd.pc
        this.address               := cmd.address
        this.size                  := cmd.accessSize
        this.robPtr                := cmd.robPtr
        this.pdest                 := cmd.physDst
        this.isIO                  := cmd.isIO
        this.isCoherent            := cmd.isCoherent
        this.isSignedLoad          := cmd.isSignedLoad
        
        // 这些字段在 AguRspCmd 中可能没有直接对应，但为了完整性，这里也进行初始化
        // 如果 AguRspCmd 提供了这些信息，可以根据需要进行映射
        // this.baseReg               := cmd.basePhysReg
        // this.immediate             := cmd.immediate
        // this.usePc                 := cmd.usePc
        // this.pc                    := cmd.pc

        this.hasException          := cmd.alignException
        this.exceptionCode         := ExceptionCode.LOAD_ADDR_MISALIGNED // 假设只有对齐异常
        
        this.isWaitingForFwdRsp    := False
        this.isStalledByDependency := False
        this.isReadyForDCache      := False
        this.isWaitingForRsp := False
        this
    }

    // format 方法为了调试可以保留
    def format: Seq[Any] = {
        Seq(
            L"LQSlot(valid=${valid}, " :+
            L"pc=${pc}, " :+
            L"address=${address}, " :+
            L"size=${size}, " :+
            L"robPtr=${robPtr}, " :+
            L"pdest=${pdest}, " :+
            L"isIO=${isIO}, " :+
            L"isCoherent=${isCoherent}, " :+
            L"isSignedLoad=${isSignedLoad}, " :+
            L"hasException=${hasException}, " :+
            L"isWaitingForFwdRsp=${isWaitingForFwdRsp}, " :+
            L"isStalledByDependency=${isStalledByDependency}, " :+
            L"isReadyForDCache=${isReadyForDCache}, " :+
            L"isWaitingForRsp=${isWaitingForRsp})"
        )
    }
}
