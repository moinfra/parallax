// filename: src/main/scala/parallax/components/rename/RenameMapTable.scala
package parallax.components.rename

import spinal.core._
import spinal.lib._

case class RenameMapTableConfig(
    archRegCount: Int = 32,
    physRegCount: Int = 32,
    numReadPorts: Int = 2,
    numWritePorts: Int = 1
) {
  def physRegIdxWidth: BitCount = log2Up(physRegCount) bits
  def archRegIdxWidth: BitCount = log2Up(archRegCount) bits
}

case class RatReadPort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val archReg = UInt(config.archRegIdxWidth)
  val physReg = UInt(config.physRegIdxWidth)

  override def asMaster(): Unit = {
    out(archReg)
    in(physReg)
  }
}

case class RatWritePort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val wen = Bool() default(False)
  val archReg = UInt(config.archRegIdxWidth) default(U(0, config.archRegIdxWidth))
  val physReg = UInt(config.physRegIdxWidth) default(U(0, config.physRegIdxWidth))

  override def asMaster(): Unit = {
    out(wen, archReg, physReg)
  }
}

case class RatCheckpoint(config: RenameMapTableConfig) extends Bundle {
  val mapping = Vec(UInt(config.physRegIdxWidth), config.archRegCount)
}

case class RatCommitUpdatePort(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  val wen = Bool() default(False)
  val archReg = UInt(config.archRegIdxWidth) default(U(0, config.archRegIdxWidth))
  val physReg = UInt(config.physRegIdxWidth) default(U(0, config.physRegIdxWidth))

  override def asMaster(): Unit = {
    out(wen, archReg, physReg)
  }
}


case class RenameMapTableIo(config: RenameMapTableConfig) extends Bundle with IMasterSlave {
  // Read ports for source operands (from RenameUnit, reads RRAT)
  val readPorts = Vec(slave(RatReadPort(config)), config.numReadPorts)

  // Write port for destination operand mapping update (from RenameUnit, writes RRAT)
  val writePorts = Vec(slave(RatWritePort(config)), config.numWritePorts)

  // New: Commit update port (from CommitPlugin, writes ARAT)
  val commitUpdatePort = in (RatCommitUpdatePort(config))

  // Read-only port for monitoring current Architectural state (for external checkpoint management, reads ARAT)
  val currentState = RatCheckpoint(config)

  // Checkpoint restore mechanism (external manager provides ARAT state to restore RRAT)
  val checkpointRestore = slave Stream (RatCheckpoint(config))
  
  // BACKWARD COMPATIBILITY: Deprecated save port for existing tests (keeping for now, but will be unused)
  val checkpointSave = slave Stream (RatCheckpoint(config))

  override def asMaster(): Unit = {
    readPorts.foreach(master(_))
    writePorts.foreach(master(_))
    out(commitUpdatePort)
    
    // currentState: From caller's perspective, receives current state from RAT
    in(currentState)
    
    // checkpointRestore: External manager initiates restore by providing state
    master(checkpointRestore)
    
    // BACKWARD COMPATIBILITY: checkpointSave (deprecated)
    master(checkpointSave)
  }
}

class RenameMapTable(val config: RenameMapTableConfig) extends Component {
  // Configuration parameter validation
  require(config.archRegCount > 0, "Number of architectural registers must be positive.")
  require(config.physRegIdxWidth.value > 0, "Physical register index width must be positive.")
  require(config.numReadPorts > 0, "Number of read ports must be positive.")
  require(
    config.numWritePorts > 0,
    "Number of write ports must be positive."
  )
  require(
    config.archRegIdxWidth.value == log2Up(config.archRegCount),
    s"archRegIdxWidth (${config.archRegIdxWidth.value}) must be log2Up(archRegCount = ${config.archRegCount}), which is ${log2Up(config.archRegCount)}"
  )

  val io = slave(RenameMapTableIo(config))

  // --- ARAT (Architectural RAT) - 仅在Commit时更新，保存已提交的映射
  val aratMapReg = Reg(RatCheckpoint(config)) init (initRatCheckpoint())

  // --- RRAT (Rename RAT) - 被RenameUnit更新，包含推测性映射，分支错误时回滚
  val rratMapReg = Reg(RatCheckpoint(config)) init (initRatCheckpoint())

  // 私有初始化函数
  private def initRatCheckpoint(): RatCheckpoint = {
    val checkpoint = RatCheckpoint(config)
    for (i <- 0 until config.archRegCount) {
      if (i == 0) { // Assuming r0 is always 0 and maps to physical register 0
        checkpoint.mapping(i) := U(0, config.physRegIdxWidth)
      } else {
        checkpoint.mapping(i) := U(i, config.physRegIdxWidth) // rX maps to pX initially
      }
    }
    checkpoint
  }

  // --- Read Logic (读取 RRAT) ---
  for (i <- 0 until config.numReadPorts) {
    when(io.readPorts(i).archReg === U(0, config.archRegIdxWidth)) {
      io.readPorts(i).physReg := U(0, config.physRegIdxWidth) // r0 永远映射到 p0
    } otherwise {
      io.readPorts(i).physReg := rratMapReg.mapping(io.readPorts(i).archReg) // 从 RRAT 读取
    }
  }

  // --- RRAT Write Logic (来自 RenameUnit) ---
  val nextRratMapRegMapping = CombInit(rratMapReg.mapping) // 下一个 RRAT 状态的组合逻辑

  when(io.checkpointRestore.valid) {
    nextRratMapRegMapping := io.checkpointRestore.payload.mapping // 恢复到CheckpointManager提供的ARAT状态
  } otherwise {
    // 应用来自 RenameUnit 的写入（更新 RRAT）
    for (i <- 0 until config.numWritePorts) {
      when(io.writePorts(i).wen && io.writePorts(i).archReg =/= U(0, config.archRegIdxWidth)) {
        nextRratMapRegMapping(io.writePorts(i).archReg) := io.writePorts(i).physReg
      }
    }
  }
  rratMapReg.mapping := nextRratMapRegMapping // RRAT 寄存器更新

  // --- ARAT Write Logic (来自 CommitPlugin) ---
  val nextAratMapRegMapping = CombInit(aratMapReg.mapping) // 下一个 ARAT 状态的组合逻辑

  when(io.commitUpdatePort.wen && io.commitUpdatePort.archReg =/= U(0, config.archRegIdxWidth)) {
    nextAratMapRegMapping(io.commitUpdatePort.archReg) := io.commitUpdatePort.physReg
  }
  aratMapReg.mapping := nextAratMapRegMapping // ARAT 寄存器更新

  // --- Checkpoint Restore Logic (针对 RRAT 的恢复端口) ---
  io.checkpointRestore.ready := True // RRAT 恢复端口总是就绪

  // --- BACKWARD COMPATIBILITY: Checkpoint Save (deprecated) ---
  io.checkpointSave.ready := True // Always ready but ignored

  // --- Current State Output (暴露 ARAT 状态给 CheckpointManager) ---
  io.currentState.mapping := nextAratMapRegMapping // 暴露 ARAT 作为已提交的干净状态
}
