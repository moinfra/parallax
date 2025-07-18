package parallax.common
import spinal.core._
import parallax.utilities.Service
import parallax.utilities.LockedImpl

trait HardRedirectService extends Service with LockedImpl {
    def doHardRedirect(): Bool
}
