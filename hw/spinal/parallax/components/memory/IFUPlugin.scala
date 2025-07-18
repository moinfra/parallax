// filename: parallax/components/ifu/IFUPlugin.scala
package parallax.components.ifu

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.memory.{InstructionFetchUnit, InstructionFetchUnitConfig}
import parallax.components.dcache2.{
  DataCacheParameters,
  DataCacheService,
  DataLoadPort
} // Ensure DataCacheService is imported
import parallax.utilities.Service
import parallax.utilities.Plugin
import parallax.utilities.LockedImpl
import parallax.components.memory.IFetchPort
import scala.collection.mutable.ArrayBuffer
import parallax.utilities.ParallaxLogger
import parallax.components.memory.IFUIO

/** Service provided by the IFUPlugin.
  * The CPU core will request this service to get the instruction fetch port.
  */
trait IFUService extends Service with LockedImpl {
  def newFetchPort(): IFetchPort
}

class IFUPlugin(
    val ifuConfigExternal: InstructionFetchUnitConfig
) extends Plugin
    with IFUService {
  ParallaxLogger.log(s"Creating IFUPlugin with config $ifuConfigExternal")
  require(
    ifuConfigExternal.dcacheParameters != null,
    "IFUPlugin requires dcacheParameters in InstructionFetchUnitConfig"
  )

  private val ifPorts = ArrayBuffer[IFetchPort]()

  override def newFetchPort(): IFetchPort = {
    this.framework.requireEarly()
    assert(ifPorts.length == 0, "Only one IFetchPort is allowed per IFUPlugin")
    val port = IFetchPort(ifuConfigExternal)
    ifPorts += port
    port
  }

  val setup = create early new Area {
    val dcacheService = getService[DataCacheService]
    dcacheService.retain()
    val ifuDCacheLoadPort = dcacheService.newLoadPort(priority = 0)
  }

  val logic = create late new Area {
    val ifu = new InstructionFetchUnit(ifuConfigExternal)
    lock.await()
    assert(ifPorts.length == 1, "No IFetchPort available for IFUPlugin")
    private val ifPort = ifPorts.head
    ifPort <> ifu.io.cpuPort

    ifu.io.dcacheLoadPort <> setup.ifuDCacheLoadPort
    setup.dcacheService.release()
  }
}
