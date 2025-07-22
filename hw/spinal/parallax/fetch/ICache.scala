// filename: src/main/scala/parallax/fetch/icache/package.scala
package parallax.fetch

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4Config
import parallax.utilities.Service
import spinal.lib.bus.amba4.axi.Axi4ReadOnly

/**
  * This package object defines the public interfaces and data structures for the ICache.
  * It serves as the "contract" between the ICachePlugin and the rest of the CPU,
  * containing no implementation details.
  */
package object icache {

  /**
    * Configuration for the Instruction Cache.
    *
    * @param totalSize      Total size of the cache (e.g., 4 KiB).
    * @param ways           Number of ways (associativity).
    * @param bytesPerLine   Size of a single cache line in bytes.
    * @param fetchWidth     Number of instructions fetched per cycle.
    * @param enableLog      Enable detailed logging for debugging.
    */
  case class ICacheConfig(
      totalSize:      Int,
      ways:           Int,
      bytesPerLine:   Int,
      fetchWidth:     Int, // Note: fetchWidth is for future use in instruction extraction logic.
      enableLog:      Boolean = true
  ) {
    // Derived parameters for easy use in the design
    val linesPerWay = totalSize.toInt / ways / bytesPerLine
    val sets = linesPerWay
    val lineWords = bytesPerLine / (32 / 8) // Assuming 32-bit instructions

    // Address decomposition widths
    val offsetWidth = log2Up(bytesPerLine)
    val indexWidth = log2Up(sets)
    
    // Tag width calculation needs the PC width, so it's a method
    def tagWidth(pcWidth: Int) = pcWidth - offsetWidth - indexWidth

    // Address decomposition ranges
    def offsetRange(pcWidth: Int) = (offsetWidth - 1) downto 0
    def indexRange(pcWidth: Int) = (offsetWidth + indexWidth - 1) downto offsetWidth
    def tagRange(pcWidth: Int) = (pcWidth - 1) downto (offsetWidth + indexWidth)
    
    // Configuration validation
    require(isPow2(ways), "ways must be a power of 2")
    require(isPow2(sets), "sets must be a power of 2")
    require(isPow2(bytesPerLine), "bytesPerLine must be a power of 2")
  }

  /**
    * Command sent from Fetch Control to the ICache.
    *
    * @param pcWidth Width of the program counter.
    */
  case class ICacheCmd(pcWidth: Int) extends Bundle {
    val address = UInt(pcWidth bits)
    // transactionId helps in tracing and asserting request-response matching.
    val transactionId = UInt(8 bits)
  }

  /**
    * Response sent from the ICache to Fetch Control.
    *
    * @param iCfg The ICache configuration.
    */
  case class ICacheRsp(iCfg: ICacheConfig) extends Bundle {
    val transactionId = UInt(8 bits)
    // The ICache always returns a full line of data.
    val instructions  = Vec(Bits(32 bits), iCfg.lineWords)
    val wasHit        = Bool()
    // If redo is True, Fetch Control must resend the last request.
    val redo          = Bool()
  }

  /**
    * The complete ICache port bundle, containing command and response flows.
    *
    * @param iCfg    The ICache configuration.
    * @param pcWidth Width of the program counter.
    */
  case class ICachePort(iCfg: ICacheConfig, pcWidth: Int) extends Bundle with IMasterSlave {
    val cmd = Flow(ICacheCmd(pcWidth))
    val rsp = Flow(ICacheRsp(iCfg))

    override def asMaster(): Unit = {
      master(cmd)
      slave(rsp)
    }
  }

  /**
    * Service trait for the ICache.
    * Allows other plugins to interact with the ICache in a decoupled manner.
    */
  trait ICacheService extends Service {
    def newICachePort(): ICachePort
    // A simple signal to trigger a full cache invalidation.
    val invalidate = Bool()
  }

  /**
    * Defines the structure of a single way's metadata (tag).
    * Placed in package object to be accessible globally within the package.
    */
  case class ICacheTagLine(iCfg: ICacheConfig, pcWidth: Int) extends Bundle {
    val tag = UInt(iCfg.tagWidth(pcWidth) bits)
  }

  /**
    * Defines the structure of a full metadata line in the Tag/LRU RAM.
    * Contains tags for all ways and the LRU bit.
    */
  case class ICacheMetaLine(iCfg: ICacheConfig, pcWidth: Int) extends Bundle {
    val ways = Vec(ICacheTagLine(iCfg, pcWidth), iCfg.ways)
    val lru  = Bool()
  }

}

trait AxiReadOnlyMasterService extends Service {
  def getAxi4ReadOnlyMaster(): Axi4ReadOnly
}
