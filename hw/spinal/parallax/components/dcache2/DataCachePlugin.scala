package parallax.components.dcache2

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline.Pipeline
import parallax.common._
import parallax.utilities._

import scala.collection.mutable.ArrayBuffer

case class DataCachePluginConfig(
    val pipelineConfig: PipelineConfig,
    val memDataWidth: Int,
    val cacheSize: Int,
    val wayCount: Int,
    val refillCount: Int,
    val writebackCount: Int,
    val lineSize: Int = 64,
    val loadRefillCheckEarly: Boolean = true,
    val storeRefillCheckEarly: Boolean = true,
    val loadReadBanksAt: Int = 0,
    val loadReadTagsAt: Int = 1,
    val loadTranslatedAt: Int = 1,
    val loadHitsAt: Int = 1,
    val loadHitAt: Int = 2,
    val loadBankMuxesAt: Int = 1,
    val loadBankMuxAt: Int = 2,
    val loadControlAt: Int = 2,
    val loadRspAt: Int = 2,
    val storeReadBanksAt: Int = 0,
    val storeReadTagsAt: Int = 1,
    val storeHitsAt: Int = 1,
    val storeHitAt: Int = 1,
    val storeControlAt: Int = 2,
    val storeRspAt: Int = 2,
    val tagsReadAsync: Boolean = true,
    val reducedBankWidth: Boolean = false
) {}

class DataCachePlugin(config: DataCachePluginConfig) extends Plugin with LockedImpl {
  import config._

  val PHYSICAL_WIDTH = pipelineConfig.pcWidth.value
  val VIRTUAL_EXT_WIDTH = pipelineConfig.pcWidth.value
  val XLEN = pipelineConfig.xlen

  def loadRspLatency = loadRspAt
  def storeRspLatency = storeRspAt

  def storeRspHazardFreeLatency = (storeControlAt + 1) - storeRspAt
  def loadCmdHazardFreeLatency = (loadReadBanksAt)

  def waySize = cacheSize / wayCount
  def linePerWay = waySize / lineSize
  def lineRange = log2Up(linePerWay * lineSize) - 1 downto log2Up(lineSize)

  def cpuDataWidth = XLEN

  def writebackBusy = setup.writebackBusy

  case class LoadPortSpec(port: DataLoadPort, priority: Int)
  val loadPorts = ArrayBuffer[LoadPortSpec]()
  def newLoadPort(priority: Int): DataLoadPort = {
    loadPorts
      .addRet(
        LoadPortSpec(
          DataLoadPort(
            preTranslationWidth = VIRTUAL_EXT_WIDTH,
            postTranslationWidth = PHYSICAL_WIDTH,
            dataWidth = cpuDataWidth,
            refillCount = refillCount,
            rspAt = loadRspAt,
            translatedAt = loadTranslatedAt
          ),
          priority
        )
      )
      .port
  }

  case class StorePortSpec(port: DataStorePort)
  val storePorts = ArrayBuffer[StorePortSpec]()
  def newStorePort(): DataStorePort = {
    storePorts
      .addRet(
        StorePortSpec(
          DataStorePort(
            postTranslationWidth = PHYSICAL_WIDTH,
            dataWidth = cpuDataWidth,
            refillCount = refillCount
          )
        )
      )
      .port
  }

  def refillCompletions = setup.refillCompletions

  val setup = create early new Area {

    val writebackBusy = Bool()

    val refillCompletions = Bits(refillCount bits)

    val dataCacheParameters = DataCacheParameters(
      cacheSize = cacheSize,
      wayCount = wayCount,
      memDataWidth = memDataWidth,
      cpuDataWidth = cpuDataWidth,
      refillCount = refillCount,
      writebackCount = writebackCount,
      preTranslationWidth = VIRTUAL_EXT_WIDTH,
      postTranslationWidth = PHYSICAL_WIDTH,
      lineSize = lineSize,
      loadRefillCheckEarly = loadRefillCheckEarly,
      storeRefillCheckEarly = storeRefillCheckEarly,
      loadReadBanksAt = loadReadBanksAt,
      loadReadTagsAt = loadReadTagsAt,
      loadTranslatedAt = loadTranslatedAt,
      loadHitsAt = loadHitsAt,
      loadHitAt = loadHitAt,
      loadBankMuxesAt = loadBankMuxesAt,
      loadBankMuxAt = loadBankMuxAt,
      loadControlAt = loadControlAt,
      loadRspAt = loadRspAt,
      storeReadBanksAt = storeReadBanksAt,
      storeReadTagsAt = storeReadTagsAt,
      storeHitsAt = storeHitsAt,
      storeHitAt = storeHitAt,
      storeControlAt = storeControlAt,
      storeRspAt = storeRspAt,
      tagsReadAsync = tagsReadAsync,
      reducedBankWidth = reducedBankWidth
    )
  }

  val logic = create late new Area {
    // Removed lock.await() as LockedImpl is removed

    val cache = new DataCache(
      setup.dataCacheParameters
    )

    setup.writebackBusy <> cache.io.writebackBusy
    // Removed lockPort connection as cache.io.lock and setup.lockPort are removed
    // setup.lockPort <> cache.io.lock

    setup.refillCompletions := cache.io.refillCompletions

    val load = new Area {
      assert(loadPorts.map(_.priority).distinct.size == loadPorts.size)
      val sorted = loadPorts.sortBy(_.priority).reverse // High priority first
      val hits = B(sorted.map(_.port.cmd.valid))
      val hit = hits.orR
      val oh = OHMasking.firstV2(hits)
      val ohHistory = History(oh, 0 to loadRspAt, init = B(0, sorted.size bits))

      cache.io.load.cmd.valid := hit
      cache.io.load.cmd.payload := OhMux(oh, sorted.map(_.port.cmd.payload))
      (sorted, oh.asBools).zipped.foreach(_.port.cmd.ready := _)

      cache.io.load.cancels := sorted.map(_.port.cancels).reduceBalancedTree(_ | _)
      cache.io.load.translated := OhMux(ohHistory(loadTranslatedAt), sorted.map(_.port.translated))

      for ((spec, sel) <- (sorted, ohHistory(loadRspAt).asBools).zipped) {
        spec.port.rsp.valid := cache.io.load.rsp.valid && sel
        spec.port.rsp.payload := cache.io.load.rsp.payload
      }
    }

    val store = new Area {
      assert(storePorts.size == 1) // Kept assertion as it is not coherency related
      cache.io.store <> storePorts.head.port
    }
  }

  val mem = create late logic.cache.io.mem.toIo()
}
