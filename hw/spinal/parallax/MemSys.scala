package parallax

import spinal.lib.bus.amba4.axi._
import spinal.core._
import spinal.lib._
import parallax.components.memory._
import parallax.common._
import parallax.utilities._
import parallax.components.lsu._
import parallax.components.dcache2.DataCachePlugin
import parallax.bus.SplitGmbToAxi4Bridge
import spinal.lib.bus.misc.SizeMapping

class MemSysPlugin(axiConfig: Axi4Config, sgmbConfig: GenericMemoryBusConfig, sram1io: SRAMIO, sram2io: SRAMIO)
    extends Plugin
    with DBusService // DBusService 已经弃用了
    with SgmbService {
  import scala.collection.mutable.ArrayBuffer

  // SGMB 部分保持不变
  private val readPorts = ArrayBuffer[SplitGmbReadChannel]()
  private val writePorts = ArrayBuffer[SplitGmbWriteChannel]()

  override def newReadPort(): SplitGmbReadChannel = {
    ParallaxLogger.debug("CALL newReadPort.")
    this.framework.requireEarly()
    val port = SplitGmbReadChannel(sgmbConfig)
    readPorts += port
    port
  }

  override def newWritePort(): SplitGmbWriteChannel = {
    ParallaxLogger.debug("CALL newWritePort.")
    this.framework.requireEarly()
    val port = SplitGmbWriteChannel(sgmbConfig)
    writePorts += port
    port
  }

  override def getBus(): Axi4 = {
    println("CALL getBus.")
    null
  }

  val hw = create early new Area {
    // SRAM 和控制器定义
    private val sramSize = BigInt("4000", 16)
    private val extSramCfg = SRAMConfig(
      addressWidth = 16,
      dataWidth = 32,
      virtualBaseAddress = BigInt("00000000", 16),
      sizeBytes = sramSize,
      readWaitCycles = 0,
      enableLog = true
    )
    val numMasters = 1 /*cache*/ + 5 /*先这样吧*/;
    val sram1Cfg = axiConfig.copy(idWidth = axiConfig.idWidth + log2Up(numMasters))
    val ctrl1 = new SRAMController(sram1Cfg, extSramCfg) // 这玩意儿是slave，必须留一些高位用来区分master
    val sram2Cfg = axiConfig.copy(idWidth = axiConfig.idWidth + log2Up(numMasters))
    val ctrl2 = new SRAMController(sram2Cfg, extSramCfg) // 这玩意儿是slave，必须留一些高位用来区分master
  }

  val logic = create late new Area {
    lock.await()
    hw.ctrl1.io.ram <> sram1io
    hw.ctrl2.io.ram <> sram2io

    val dcacheMaster = getService[DataCachePlugin].getDCacheMaster
    val readBridges = readPorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    val writeBridges = writePorts.map(_ => new SplitGmbToAxi4Bridge(sgmbConfig, axiConfig))
    ParallaxLogger.debug(s"readBridges.size = ${readBridges.size}, writeBridges.size = ${writeBridges.size}")
    for ((port, bridge) <- readPorts.zip(readBridges)) {
      bridge.io.gmbIn.read.cmd <> port.cmd
      bridge.io.gmbIn.read.rsp <> port.rsp
      bridge.io.gmbIn.write.cmd.setIdle()
      bridge.io.gmbIn.write.rsp.ready := True
    }
    for ((port, bridge) <- writePorts.zip(writeBridges)) {
      bridge.io.gmbIn.write.cmd <> port.cmd
      bridge.io.gmbIn.write.rsp <> port.rsp
      bridge.io.gmbIn.read.cmd.setIdle()
      bridge.io.gmbIn.read.rsp.ready := True
    }
    val sramMasters = writeBridges.map(_.io.axiOut) ++ readBridges.map(_.io.axiOut) ++ Seq(dcacheMaster)
    sramMasters.zipWithIndex.foreach { case (master, index) =>
      ParallaxLogger.info(s"  Master $index: idWidth = ${master.config.idWidth}")
    }
    require(sramMasters.size <= hw.numMasters, "Too many masters for SRAM controller")
    val crossbar = Axi4CrossbarFactory()
    val sramSize = BigInt("4000", 16)
    crossbar.addSlave(hw.ctrl1.io.axi, SizeMapping(0x0000L, sramSize))
    for (master <- sramMasters) {
      crossbar.addConnection(master, Seq(hw.ctrl1.io.axi))
    }
    crossbar.build()

  }
}
