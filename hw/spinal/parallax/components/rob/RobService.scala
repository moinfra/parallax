// -- MODIFICATION START (RobServicePlugin implementation) --
package parallax.components.rob // 假设放在 components.rob 包下

import spinal.core._
import spinal.lib._
import parallax.common._ 
import parallax.utilities._


trait ROBService[RU <: Data] extends Service {

  // --- 分配 (Allocation) 相关接口 ---

  /**
   * Dispatch/Rename 阶段调用此方法来请求分配 ROB 条目。
   * 返回一个 Vec of ROBAllocateSlot，Dispatch/Rename 作为 Slave 端。
   * Dispatch/Rename 需要检查 ROBService 提供的 canAllocate 信号。
   *
   * @param width 分配宽度 (与请求方能力匹配)
   * @return 一个 Vec，每个元素代表一个分配槽的 Master 视角接口 (ROB是Master)
   */
  def newAllocatePorts(width: Int): Vec[ROBAllocateSlot[RU]] // ROB 作为 Master

  /**
   * Dispatch/Rename 阶段查询 ROB 是否能为每个分配槽分配条目。
   *
   * @param width 分配宽度
   * @return 一个 Vec of Bool，表示每个槽是否可以分配
   */
  def getCanAllocate(width: Int): Vec[Bool]


  // --- 写回 (Writeback) 相关接口 ---

  /**
   * 执行单元 (EU) 调用此方法来获取一个写回端口。
   * EU 作为 Slave 端，需要驱动 fire, robIdx, exceptionOccurred, exceptionCodeIn。
   *
   * @param euName 执行单元名称 (用于调试或追踪)
   * @return 一个 ROBWritebackPort 的 Master 视角接口 (ROB是Master)
   */
  def newWritebackPort(euName: String): ROBWritebackPort[RU] // ROB 作为 Master


  // --- 提交 (Commit) 相关接口 ---

  /**
   * Commit 阶段调用此方法来获取提交端口。
   * Commit 阶段作为 Master 端，会读取 valid 和 entry，并驱动 commitFire。
   *
   * @param width 提交宽度
   * @return 一个 Vec，每个元素代表一个提交槽的 Slave 视角接口 (ROB是Slave)
   */
  def getCommitPorts(width: Int): Vec[ROBCommitSlot[RU]] // ROB 作为 Slave

  /**
   * Commit 阶段驱动此信号，指示哪些槽位被实际提交。
   *
   * @param width 提交宽度
   * @return 一个 Vec of Bool，用于驱动 ROB 的 commitFire 输入
   */
  def driveCommitFire(width: Int): Vec[Bool] // ROB 作为 Slave (ROB 接收这个输入)


  // --- 清空/恢复 (Flush) 相关接口 ---

  /**
   * 异常处理或分支恢复逻辑调用此方法来获取清空 ROB 的流接口。
   * 调用者作为 Master 端，发送 ROBFlushCommand。
   *
   * @return 一个 Flow[ROBFlushCommand[RU]] 的 Master 视角接口 (ROB是Slave)
   */
  def getFlushPort(): Flow[ROBFlushCommand[RU]] // ROB 作为 Slave


  // --- 状态查询接口 ---

  /**
   * 查询 ROB 是否为空。
   * @return Bool 信号，真表示 ROB 为空
   */
  def isEmpty(): Bool

  /**
   * 获取 ROB 当前的头指针。
   * @return UInt 信号，表示头指针的 ROB 索引
   */
  def getHeadPtr(): UInt

  /**
   * 获取 ROB 当前的尾指针。
   * @return UInt 信号，表示尾指针的 ROB 索引
   */
  def getTailPtr(): UInt

  /**
   * 获取 ROB 中当前有效的条目数量。
   * @return UInt 信号，表示 ROB 中的条目数
   */
  def getCount(): UInt

  /**
   * (高级接口，可选) 请求一个可用的 ROB 索引。
   * 这个接口可能需要 ROBService 内部维护一些额外的状态或与分配逻辑交互。
   * 对于你的设计，由于 ROBAllocateSlot 直接返回 robIdx，这个可能不是必需的，
   * Dispatch 可以直接从 ROBAllocateSlot.robIdx 获取。
   * 但如果需要服务层做更多管理（例如，确保索引在分配和写回之间的状态），则可能有用。
   *
   * @return Flow[UInt] - 一个流，当 ROB 有空间时，会发出一个可用的 ROB 索引。
   *         或者更简单地，如果与 newAllocatePorts 配合使用，可以是一个直接的 UInt。
   */
  // def requestRobIndex(): Flow[UInt] // 示例，具体实现待定

  /**
   * (高级接口，可选) 获取指定 ROB 索引的完整条目信息 (通常用于调试或特殊恢复)。
   * 这需要 ROBService 提供读取 ROB 内部存储器的方法。
   * @param robIdx 要查询的 ROB 索引
   * @return ROBFullEntry[RU]
   */
  // def getRobEntryDebug(robIdx: UInt): ROBFullEntry[RU] // 示例
}
