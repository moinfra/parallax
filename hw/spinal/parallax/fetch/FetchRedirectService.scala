package parallax.fetch

import spinal.core._
import spinal.lib._
import parallax.utilities.Service
import parallax.utilities.LockedImpl

// 这个服务允许流水线的下游阶段向上游的PC生成器请求重定向
trait FetchRedirectService extends Service with LockedImpl {
    // 任何需要重定向的阶段都可以驱动这个 Flow
    // 优先级由驱动逻辑决定（例如，使用 StreamArbiter）
    def redirectFlow: Flow[UInt] 
}
