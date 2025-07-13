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
    val dataWidth: BitCount = 32 bits,
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
  def newPrfWritePort(): PrfWritePort

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

  val regIdxWidth = log2Up(numPhysRegs) bits
  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePort]()

  // 标记逻辑是否已经执行
  private var logicExecuted = false

  override def newPrfReadPort(): PrfReadPort = {
    assert(!logicExecuted, "Cannot create read port after logic has been executed")
    val port = PrfReadPort(regIdxWidth, dataWidth)
    readPortRequests += port
    port
  }

  override def newPrfWritePort(): PrfWritePort = {
    assert(!logicExecuted, "Cannot create write port after logic has been executed")
    val port = PrfWritePort(regIdxWidth, dataWidth)
    writePortRequests += port
    port
  }

  def readPort(index: Int): PrfReadPort = readPortRequests(index)
  def writePort(index: Int): PrfWritePort = writePortRequests(index)

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

    val regFile = Mem.fill(numPhysRegs)(Bits(dataWidth))
    // Initialize all physical registers to 0, especially physical register 0
    regFile.init(Seq.fill(numPhysRegs)(B(0, dataWidth)))

    readPortRequests.zipWithIndex.foreach { case (externalPort, i) =>
      val data = Mux(externalPort.address === 0, B(0, dataWidth), regFile.readAsync(address = externalPort.address))
      externalPort.rsp := data
      ParallaxLogger.log(s"[PRegPlugin] PRF Port $i 已连接")
      when(externalPort.valid) {
        ParallaxSim.log(L"[PRegPlugin] PRF Port ${externalPort.address} read ${data}")
      }
    }

    // --- 自定义写端口仲裁逻辑 ---
    if (writePortRequests.nonEmpty) {
      // 创建一个Bundle来承载仲裁后的写请求
      val arbitratedWrite = PrfWritePort(regIdxWidth, dataWidth)

      // 1. 获取所有写请求的有效信号
      val writeValids = Vec(writePortRequests.map(_.valid))

      // 2. 生成一个one-hot的授权信号 (grant)，只有第一个有效的请求对应的位为1
      // OHMasking.first 正是用于固定优先级仲裁
      val writeGrants = OHMasking.first(writeValids)

      // 3. 决定最终的仲裁输出
      // 如果任何一个请求被授权，则仲裁后的端口有效
      arbitratedWrite.valid := writeGrants.orR

      // 使用 MuxOH (One-Hot Mux) 根据授权信号选择获胜者的数据和地址
      arbitratedWrite.address := MuxOH(writeGrants, writePortRequests.map(_.address))
      arbitratedWrite.data    := MuxOH(writeGrants, writePortRequests.map(_.data))

      // 4. 使用仲裁后胜出的端口执行唯一的物理写操作
      regFile.write(
        address = arbitratedWrite.address,
        data    = arbitratedWrite.data,
        enable  = arbitratedWrite.valid && (arbitratedWrite.address =/= 0)
      )

      // 仿真和编译日志
      when(arbitratedWrite.valid && (arbitratedWrite.address =/= 0)) {
        ParallaxSim.log(L"[PRegPlugin] PRF Port ${arbitratedWrite.address} write ${arbitratedWrite.data} (Arbitrated)")
      }
      ParallaxLogger.log(s"[PRegPlugin] ${writePortRequests.size}个物理寄存器堆写端口已连接到一个自定义的固定优先级仲裁器，形成一个物理写端口")
    }
    // --- 自定义写端口仲裁逻辑结束 ---
  }
}
