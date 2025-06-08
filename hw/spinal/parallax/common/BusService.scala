package parallax.common
import parallax.utilities._
import spinal.lib.bus.amba4.axi._

trait DBusService extends Service with LockedImpl {
    def getBus(): Axi4
}
