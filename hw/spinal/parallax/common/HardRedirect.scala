package parallax.common
import spinal.core._
import parallax.utilities.Service

trait HardRedirectService extends Service {
    def getFlushListeningPort(): Bool
}
