// filename: hw/spinal/parallax/components/rob/ROBService.scala (或类似路径)
package parallax.components.rob

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.Service

// Forward declaration of Bundles used in the service, assuming they are in the same package or imported.
// case class ROBAllocateSlot[RU <: Data with Dumpable with HasRobIdx](...) // Defined in ReorderBuffer.scala
// case class ROBCommitSlot[RU <: Data with Dumpable with HasRobIdx](...)   // Defined in ReorderBuffer.scala
// case class ROBWritebackPort[RU <: Data with Dumpable with HasRobIdx](...) // Defined in ReorderBuffer.scala
// case class ROBFlushCommand[RU <: Data with Dumpable with HasRobIdx](...) // Defined in ReorderBuffer.scala

/**
 * ROBService 提供了与 Reorder Buffer 交互的接口。
 * @tparam RU ROB中存储的Uop的数据类型 (通常是 RenamedUop)
 */
trait ROBService[RU <: Data with Dumpable with HasRobIdx] extends Service {

  // --- 分配阶段 (Allocation Interface) ---

  /**
   * Rename/Dispatch 阶段调用此方法来获取 ROB 的分配端口组。
   * ROBService 的实现者（如 ROBPlugin）将连接这些端口到实际 ROB 组件的分配端口。
   * @param width 并行分配的 Uops 数量。
   * @return 一个 Vec，其中每个元素是 ROBAllocateSlot 的 slave 视角。
   *         Rename/Dispatch 驱动 fire, uopIn, pcIn。
   */
  def getAllocatePorts(width: Int): Vec[(ROBAllocateSlot[RU])]

  /**
   * Rename/Dispatch 阶段调用此方法来获取 ROB 是否可以为每个分配槽分配条目的信号。
   * @param width 并行分配的 Uops 数量。
   * @return 一个 Vec of Bool，表示每个槽是否可以分配 (ROB 驱动)。
   */
  def getCanAllocateVec(width: Int): Vec[Bool]


  // --- 执行完成/写回阶段 (Writeback Interface) ---

  /**
   * 执行单元 (EU) 调用此方法来获取一个专属的 ROB 写回端口。
   * ROBService 的实现者（如 ROBPlugin）负责从 ROB 组件的物理写回端口池中分配一个。
   * @param euName 请求端口的 EU 名称 (用于调试或追踪，可选)。
   * @return 一个 ROBWritebackPort 的 slave 视角。EU 驱动 fire, robIdx, exceptionOccurred, exceptionCodeIn。
   */
  def newWritebackPort(euName: String = ""): (ROBWritebackPort[RU])


  // --- 提交阶段 (Commit Interface) ---

  /**
   * Commit 阶段调用此方法来获取 ROB 的可提交指令槽。
   * @param width 并行提交的 Uops 数量。
   * @return 一个 Vec，其中每个元素是 ROBCommitSlot 的 master 视角。ROB 驱动 valid, entry。
   */
  def getCommitSlots(width: Int): Vec[(ROBCommitSlot[RU])]

  /**
   * Commit 阶段调用此方法来获取驱动 ROB 提交确认信号的端口。
   * @param width 并行提交的 Uops 数量。
   * @return 一个 Vec of Bool 的 slave 视角。Commit 阶段驱动这些信号。
   */
  def getCommitAcks(width: Int): Vec[(Bool)]


  // --- 清空/恢复阶段 (Flush Interface) ---

  /**
   * 分支预测恢复逻辑或异常处理逻辑调用此方法来获取向 ROB 发送清空命令的端口。
   * @return 一个 ROBFlushCommand 的 slave Flow 视角。调用者驱动 valid 和 payload。
   */
  def getFlushPort(): (Flow[ROBFlushPayload])
}
