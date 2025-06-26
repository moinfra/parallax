package parallax.common
import parallax.utilities._
import spinal.lib.bus.amba4.axi._

trait DBusService extends Service with LockedImpl {
    def getBus(): Axi4
}

/**
 * 提供指令总线 (AXI4 接口) 的服务。
 */
trait IBusService extends Service with LockedImpl {
  /**
   * 获取 AXI4 指令总线。
   * @return 一个 AXI4 总线实例。
   */
  def getIBus(): Axi4

  /**
   * 获取指令总线的 AXI4 配置。
   * 这对于实例化连接到总线的组件非常有用。
   * @return Axi4Config 实例。
   */
  def iBusConfig: Axi4Config
}
