package parallax.common

import spinal.lib.IMasterSlave
import spinal.core.Bundle
import spinal.lib.Flow
import spinal.core._
import spinal.lib._
import parallax.utilities.Service
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.Plugin
import parallax.utilities.ParallaxLogger
import parallax.utilities.LockedImpl
import parallax.utilities.ParallaxSim

case class PrfReadPort(
    val idxWidth: BitCount,
    val dataWidth: BitCount = 32 bits
) extends Bundle
    with IMasterSlave {

  val valid = Bool()
  val address = UInt(idxWidth)
  val rsp = Bits(dataWidth)

  override def asMaster(): Unit = {
    out(valid, address)
    inWithNull(rsp)
  }
}

case class PrfWritePort(
    val idxWidth: BitCount,
    val dataWidth: BitCount = 32 bits
) extends Bundle
    with IMasterSlave {
  val valid = Bool()
  val address = UInt(idxWidth)
  val data = Bits(dataWidth)

  override def asMaster() = {
    out(valid, address, data)
  }
}

trait PhysicalRegFileService extends Service with LockedImpl {
  def newPrfReadPort(): PrfReadPort
  def newPrfWritePort(traceName: String = "<null>"): PrfWritePort

  def readPort(index: Int): PrfReadPort
  def writePort(index: Int): PrfWritePort

  def isGprService(): Boolean = true
  def isFprService(): Boolean = false
}

class PhysicalRegFilePlugin(
    numPhysRegs: Int,
    dataWidth: BitCount = 32 bits
) extends Plugin
    with PhysicalRegFileService
    with LockedImpl {

  private case class PrfWritePortRequest(port: PrfWritePort, traceName: String)

  val regIdxWidth = log2Up(numPhysRegs) bits
  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePortRequest]()

  // 标记逻辑是否已经执行
  private var logicExecuted = false

  override def newPrfReadPort(): PrfReadPort = {
    this.framework.requireEarly()
    val port = PrfReadPort(regIdxWidth, dataWidth)
    readPortRequests += port
    port
  }

  override def newPrfWritePort(traceName: String = "<null>"): PrfWritePort = {
    this.framework.requireEarly()
    val port = PrfWritePort(regIdxWidth, dataWidth)
    // 将端口和它的名字一起存入请求列表
    writePortRequests += PrfWritePortRequest(port, traceName)
    port
  }

  def readPort(index: Int): PrfReadPort = readPortRequests(index)
  // 为了保持API一致性，writePort(index) 现在只返回端口本身
  def writePort(index: Int): PrfWritePort = writePortRequests(index).port

  val setup = create early new Area {
    ParallaxLogger.log("[PRegPlugin] early")
  }

  val logic = create late new Area {
    ParallaxLogger.log("[PRegPlugin] late")
    ParallaxLogger.log("[PRegPlugin] 物理寄存器在生成逻辑前，等待依赖它的插件就绪")
    lock.await()
    ParallaxLogger.log("[PRegPlugin] 好，物理寄存器开始连接读写逻辑")

    // 标记逻辑开始执行
    logicExecuted = true

    val regFile = Vec(RegInit(B(0, dataWidth)), numPhysRegs)

    readPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      val data = Mux(externalPort.address === 0, B(0, dataWidth), regFile(externalPort.address))
      externalPort.rsp := data
      ParallaxLogger.log(s"[PRegPlugin] PRF Port $i 已连接")
      when(externalPort.valid) {
        ParallaxSim.log(L"[PRegPlugin] PRF Port ${externalPort.address} read ${data}")
      }
    }

    // --- 自定义写端口仲裁逻辑 ---
    if (writePortRequests.nonEmpty) {
      writePortRequests.foreach { req =>
        when(req.port.valid) {
          ParallaxSim.log(
            L"[PRegPlugin] Write Request from `${req.traceName}`: wants to write ${req.port.data} to reg[${req.port.address}]"
          )
        }
      }

      // 从请求列表中提取出 PrfWritePort 用于仲裁逻辑
      val actualWritePorts = writePortRequests.map(_.port)

      // 创建一个Bundle来承载仲裁后的写请求
      val arbitratedWrite = PrfWritePort(regIdxWidth, dataWidth)

      // 1. 获取所有写请求的有效信号
      val writeValids = Vec(actualWritePorts.map(_.valid))

      // 实际上不应该产生并发写，如果有一定是bug
      assert(CountOne(writeValids) <= 1, "PhysicalRegFilePlugin: Multiple write requests detected")

      // 2. 生成一个one-hot的授权信号 (grant)，只有第一个有效的请求对应的位为1
      val writeGrants = OHMasking.first(writeValids)

      // 3. 决定最终的仲裁输出
      arbitratedWrite.valid := writeGrants.orR
      arbitratedWrite.address := MuxOH(writeGrants, actualWritePorts.map(_.address))
      arbitratedWrite.data := MuxOH(writeGrants, actualWritePorts.map(_.data))

      // 4. 使用仲裁后胜出的端口执行唯一的物理写操作
      when(arbitratedWrite.valid && (arbitratedWrite.address =/= 0)) {
        regFile(arbitratedWrite.address) := arbitratedWrite.data
      }

      // 仿真和编译日志 (仲裁后的最终结果)
      when(arbitratedWrite.valid && (arbitratedWrite.address =/= 0)) {
        ParallaxSim.log(L"[PRegPlugin] PRF Port ${arbitratedWrite.address} write ${arbitratedWrite.data} (Arbitrated Winner)")
      }
      ParallaxLogger.log(s"[PRegPlugin] ${writePortRequests.size}个物理寄存器堆写端口已连接到一个自定义的固定优先级仲裁器，形成一个物理写端口")

      val activeWritePortsCount = CountOne(writeValids)

      when(activeWritePortsCount > 1) {
        getServiceOption[DebugDisplayService].foreach(_.setDebugValue(DebugValue.REG_WRITE_CONFLICT))
      }
    }
    // --- 自定义写端口仲裁逻辑结束 ---
  }
}
