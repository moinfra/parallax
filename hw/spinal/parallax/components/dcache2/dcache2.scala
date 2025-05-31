package parallax.components.dcache2

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}
import spinal.lib.bus.bmb.{Bmb, BmbAccessParameter, BmbParameter, BmbSourceParameter}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.pipeline.Connection.M2S
import spinal.lib.pipeline.{Pipeline, Stage, Stageable, StageableOffsetNone}
import spinal.lib.sim.SimData.dataToSimData

import scala.collection.mutable.ArrayBuffer

// 将地址和大小转换为掩码的工具函数
object AddressToMask {
  // 根据给定的地址、大小和位宽，生成一个掩码。
  // 例如，如果 width = 8 (字节)，size = 0 (1字节)，address = 0x1，则返回 0x02
  // 如果 size = 1 (2字节)，address = 0x0，则返回 0x03
  def apply(address: UInt, size: UInt, width: Int): Bits = {
    // muxListDc: 根据 size 的值选择对应的掩码。
    // U(i) -> B((1 << (1 << i)) - 1, width bits)): 创建对应大小的连续1的掩码。
    // 例如，size=0 (1字节) -> 1<<0 = 1, (1<<1)-1 = 1 => B"00000001"
    // size=1 (2字节) -> 1<<1 = 2, (1<<2)-1 = 3 => B"00000011"
    // |<< address(log2Up(width)-1 downto 0): 根据地址的低位进行左移，实现掩码的对齐。
    size.muxListDc((0 to log2Up(width)).map(i => U(i) -> B((1 << (1 << i)) - 1, width bits))) |<< address(
      log2Up(width) - 1 downto 0
    )
  }
}

// Reservation 类：用于实现资源（例如写端口、写使能）的仲裁和排他性访问。
// 允许多个请求者创建 Entry，并根据优先级决定哪个 Entry 可以“赢得”资源。
class Reservation {
  // Entry 类：代表一个请求者。
  class Entry(val priority: Int) extends Area {
    val win = Bool() // 指示该 Entry 是否赢得了资源（优先级最高且未被更高的优先级请求者“拿走”）
    val take = False // 指示该 Entry 正在主动“拿走”资源

    def takeIt() = take := True // 调用此方法表示该 Entry 尝试获取资源
  }
  val model = ArrayBuffer[Entry]() // 存储所有注册的 Entry
  def create(priority: Int): Entry = {
    val e = new Entry(priority) // 创建一个新 Entry
    model += e // 将其添加到模型中
    e
  }

  // afterElaboration 钩子：在 SpinalHDL 综合阶段的后期执行，用于连接仲裁逻辑。
  Component.current.afterElaboration {
    // 遍历所有 Entry，设置它们的 win 信号。
    // 如果没有更高优先级的 Entry 正在 take 资源，则该 Entry.win 为 True。
    for (e <- model) {
      e.win := !model.filter(_.priority < e.priority).map(_.take).orR
    }
  }
}

// LockPort 类：用于向数据缓存发出锁定请求，确保某个物理地址在独占状态。
case class LockPort() extends Bundle with IMasterSlave {
  val valid = Bool() // 请求是否有效
  val address = UInt(32 bits) // 要锁定的物理地址

  override def asMaster() = out(this) // 作为主接口，只输出
}

// DataLoadPort 类：定义数据加载端口的接口，用于从LSU向数据缓存发送加载命令。
case class DataLoadPort(
    preTranslationWidth: Int, // 翻译前的虚拟地址宽度
    postTranslationWidth: Int, // 翻译后的物理地址宽度
    dataWidth: Int, // 数据宽度
    refillCount: Int, // 重填槽位数量
    rspAt: Int, // 响应阶段的索引
    translatedAt: Int // 翻译完成的阶段索引
) extends Bundle
    with IMasterSlave {
  val cmd = Stream(DataLoadCmd(preTranslationWidth, dataWidth)) // 加载命令流
  val translated = DataLoadTranslated(postTranslationWidth) // 翻译后的地址信息
  val cancels = Bits(rspAt + 1 bits) // 用于取消流水线中后续加载请求的掩码
  val rsp = Flow(
    DataLoadRsp(dataWidth, refillCount)
  ) // 加载响应流，固定延迟 (rsp.valid 必须存在)

  override def asMaster() = {
    master(cmd) // 命令作为主接口
    out(translated) // 翻译信息作为输出
    out(cancels) // 取消掩码作为输出
    slave(rsp) // 响应作为从接口
  }
}

// DataLoadCmd 类：定义加载命令的负载（payload）。
case class DataLoadCmd(preTranslationWidth: Int, dataWidth: Int) extends Bundle {
  val virtual = UInt(preTranslationWidth bits) // 虚拟地址
  val size = UInt(log2Up(log2Up(dataWidth / 8) + 1) bits) // 加载大小（log2_bytes）
  val redoOnDataHazard = Bool() // 当发生数据冒险时是否重做（例如，MMU重填可能不是LSU保护的）
  val unlocked = Bool() // 加载是否允许在未锁定状态下进行
  val unique = Bool() // 用于原子操作，确保行处于一致的独占状态

  // format 方法：用于打印调试信息。
  def format(): Seq[Any] = {
    Seq(
      L"DataLoadCmd(virtual = ${virtual}, size = ${size}, redoOnDataHazard = ${redoOnDataHazard}, unlocked = ${unlocked}, unique = ${unique})"
    )
  }
}

// DataLoadTranslated 类：定义翻译后的地址信息，用于加载请求。
case class DataLoadTranslated(physicalWidth: Int) extends Bundle {
  val physical = UInt(physicalWidth bits) // 物理地址
  val abord = Bool() // 指示地址转换是否失败（例如，权限错误）

  // format 方法：用于打印调试信息。
  def format(): Seq[Any] = {
    Seq(
      L"DataLoadTranslated(physical = ${physical}, abord = ${abord})"
    )
  }
}

// DataLoadRsp 类：定义加载响应的负载。
case class DataLoadRsp(dataWidth: Int, refillCount: Int) extends Bundle {
  val data = Bits(dataWidth bits) // 加载到的数据
  val fault = Bool() // 是否发生错误（例如，权限错误）
  val redo = Bool() // 是否需要重做（例如，缓存缺失或冲突）
  val refillSlot = Bits(refillCount bits) // 重填槽位掩码（如果重填槽位任意，则为0）
  val refillSlotAny = Bool() // 重填槽位是否是任意的（如果不是缺失，则无效）

  // format 方法：用于打印调试信息。
  def format(): Seq[Any] = {
    Seq(
      L"DataLoadRsp(data = ${data}, fault = ${fault}, redo = ${redo}, refillSlot = ${refillSlot}, refillSlotAny = ${refillSlotAny})"
    )
  }
}

// DataStorePort 类：定义数据存储端口的接口，用于LSU向数据缓存发送存储命令。
case class DataStorePort(postTranslationWidth: Int, dataWidth: Int, refillCount: Int) extends Bundle with IMasterSlave {
  val cmd = Stream(DataStoreCmd(postTranslationWidth, dataWidth)) // 存储命令流
  val rsp = Flow(DataStoreRsp(postTranslationWidth, refillCount)) // 存储响应流

  override def asMaster() = {
    master(cmd) // 命令作为主接口
    slave(rsp) // 响应作为从接口
  }
}

// DataStoreCmd 类：定义存储命令的负载。
case class DataStoreCmd(postTranslationWidth: Int, dataWidth: Int) extends Bundle {
  val address = UInt(postTranslationWidth bits) // 物理地址
  val data = Bits(dataWidth bits) // 要存储的数据
  val mask = Bits(dataWidth / 8 bits) // 字节掩码
  val generation = Bool() // 缓存世代信息，用于一致性检查
  val io = Bool() // 是否是IO访问
  val flush = Bool() // 是否刷新给定地址行的所有缓存行，可能会导致rsp.redo
  val flushFree = Bool() // 刷新后是否释放（将状态设置为Invalid）
  val prefetch = Bool() // 是否是预取操作

  // format 方法：用于打印调试信息。
  def format(): Seq[Any] = {
    Seq(
      L"DataStoreCmd(address = ${address}, data = ${data}, mask = ${mask}, ",
      L"generation = ${generation}, io = ${io}, flush = ${flush}, flushFree = ${flushFree}, prefetch = ${prefetch})"
    )
  }
}

// DataStoreRsp 类：定义存储响应的负载。
case class DataStoreRsp(addressWidth: Int, refillCount: Int) extends Bundle {
  val fault = Bool() // 是否发生错误
  val redo = Bool() // 是否需要重做
  val refillSlot = Bits(refillCount bits) // 重填槽位掩码（如果重填槽位任意，则为0）
  val refillSlotAny = Bool() // 重填槽位是否是任意的（如果不是缺失，则无效）
  val generationKo = Bool() // 世代检查失败（如果此位为高，则忽略其他所有字段）
  val flush = Bool() // 响应对应的命令是否是 flush
  val prefetch = Bool() // 响应对应的命令是否是 prefetch
  val address = UInt(addressWidth bits) // 响应对应的地址
  val io = Bool() // 响应对应的命令是否是 IO 访问

  // format 方法：用于打印调试信息。
  def format(): Seq[Any] = {
    Seq(
      L"DataStoreRsp(fault = ${fault}, redo = ${redo}, refillSlot = ${refillSlot}, refillSlotAny = ${refillSlotAny}, ",
      L"generationKo = ${generationKo}, flush = ${flush}, prefetch = ${prefetch}, address = ${address}, io = ${io})"
    )
  }
}

// DataMemBusParameter 类：定义主存总线（通常是AXI或TileLink）的参数。
case class DataMemBusParameter(
    addressWidth: Int, // 地址宽度
    dataWidth: Int, // 数据宽度
    readIdCount: Int, // 读请求ID数量
    writeIdCount: Int, // 写请求ID数量
    probeIdWidth: Int, // 探测（Coherency Probe）ID宽度
    ackIdWidth: Int, // 应答（Coherency Ack）ID宽度
    lineSize: Int, // 缓存行大小（字节）
    withReducedBandwidth: Boolean, // 是否支持带宽缩减（例如，将宽总线适配到窄总线）
    withCoherency: Boolean // 是否支持缓存一致性
) {

  val readIdWidth = log2Up(readIdCount) // 读ID的位宽
  val writeIdWidth = log2Up(writeIdCount) // 写ID的位宽
}

// DataMemReadCmd 类：定义主存读命令的负载。
case class DataMemReadCmd(p: DataMemBusParameter) extends Bundle {
  val id = UInt(p.readIdWidth bits) // 读请求ID
  val address = UInt(p.addressWidth bits) // 地址
  val unique = p.withCoherency generate Bool() // 生成独占（Unique）访问，仅在支持一致性时有效
  val data = p.withCoherency generate Bool() // 请求数据，仅在支持一致性时有效
}

// DataMemReadRsp 类：定义主存读响应的负载。
case class DataMemReadRsp(p: DataMemBusParameter) extends Bundle {
  val id = UInt(p.readIdWidth bits) // 响应对应的读请求ID
  val data = Bits(p.dataWidth bits) // 读取到的数据
  val error = Bool() // 是否发生错误
  val unique = p.withCoherency generate Bool() // 物理地址是否是独占的，仅在支持一致性时有效
  val ackId = p.withCoherency generate UInt(p.ackIdWidth bits) // 应答ID，仅在支持一致性时有效
  val withData = p.withCoherency generate Bool() // 是否携带数据，仅在支持一致性时有效
}

// DataMemReadAck 类：定义主存读应答（Ack）的负载，用于缓存一致性协议。
case class DataMemReadAck(p: DataMemBusParameter) extends Bundle {
  val ackId = UInt(p.ackIdWidth bits) // 应答ID
}

// DataMemReadBus 类：定义主存读总线接口。
case class DataMemReadBus(p: DataMemBusParameter) extends Bundle with IMasterSlave {
  val cmd = Stream(DataMemReadCmd(p)) // 读命令流
  val rsp = Stream(DataMemReadRsp(p)) // 读响应流
  val ack = p.withCoherency generate Stream(DataMemReadAck(p)) // 读应答流，仅在支持一致性时有效

  override def asMaster() = {
    master(cmd, ack) // 命令和应答作为主接口
    slave(rsp) // 响应作为从接口
  }

  // << 操作符重载：方便连接两个 DataMemReadBus。
  def <<(m: DataMemReadBus): Unit = {
    m.cmd >> this.cmd // 命令从 m 流向当前总线
    m.rsp << this.rsp // 响应从当前总线流向 m
  }

  // resizer 方法：将总线的数据宽度调整到 newDataWidth。
  // newDataWidth 必须小于或等于当前数据宽度。
  def resizer(newDataWidth: Int): DataMemReadBus = new Composite(this, "resizer") {
    val ret = DataMemReadBus(
      p = p.copy(
        dataWidth = newDataWidth,
        withReducedBandwidth = p.withReducedBandwidth || newDataWidth > p.dataWidth // 如果数据宽度增加，则需要带宽缩减适配
      )
    )

    ret.cmd << self.cmd // 命令直接连接

    val rspOutputStream = Stream(Bits(p.dataWidth bits)) // 创建一个中间流，用于数据宽度适配
    StreamWidthAdapter(ret.rsp.translateWith(ret.rsp.data), rspOutputStream) // 宽度适配器

    rsp.valid := rspOutputStream.valid // 响应有效性
    rsp.data := rspOutputStream.payload // 响应数据
    rsp.id := ret.rsp.id // 响应ID
    rsp.error := ret.rsp.error // 响应错误
    // 响应就绪信号：如果支持带宽缩减，则依赖于下游的就绪信号；否则始终为True。
    rspOutputStream.ready := (if (p.withReducedBandwidth) rspOutputStream.ready else True)
  }.ret

  // toBmb 方法：将 DataMemReadBus 转换为 BMB (Bus Management Bus) 读总线。
  def toBmb(): Bmb = new Composite(this, "toBmb") {
    val bmbConfig = BmbAccessParameter(
      addressWidth = p.addressWidth,
      dataWidth = p.dataWidth
    ).addSources(
      p.readIdCount,
      BmbSourceParameter(
        contextWidth = 0,
        lengthWidth = log2Up(p.lineSize),
        alignment = BmbParameter.BurstAlignement.LENGTH,
        canWrite = false,
        withCachedRead = true
      )
    )

    val bmb = Bmb(bmbConfig) // 创建 BMB 总线
    bmb.cmd.arbitrationFrom(cmd) // 从 DataMemReadBus 的命令流获取仲裁信号
    bmb.cmd.setRead() // 设置为读命令
    bmb.cmd.address := cmd.address // 地址
    bmb.cmd.length := p.lineSize - 1 // 长度（缓存行大小-1）
    bmb.cmd.source := cmd.id // 源ID
    bmb.cmd.last := True // 最后一个突发传输

    rsp.arbitrationFrom(bmb.rsp) // 从 BMB 响应流获取仲裁信号
    rsp.id := bmb.rsp.source // 响应ID
    rsp.data := bmb.rsp.data // 响应数据
    rsp.error := bmb.rsp.isError // 响应错误
  }.bmb

}

// DataMemWriteCmd 类：定义主存写命令的负载。
case class DataMemWriteCmd(p: DataMemBusParameter) extends Bundle {
  val address = UInt(p.addressWidth bits) // 地址
  val data = Bits(p.dataWidth bits) // 数据
  val id = UInt(p.writeIdWidth bits) // 写请求ID
  val coherent = p.withCoherency generate new Bundle {
    val release = Bool() // 是否释放（TileLink C通道操作，例如 ReleaseData）
    val dirty = Bool() // 是否为脏数据（意味着携带数据）
    val fromUnique = Bool() // 来源是否是独占状态
    val toShared = Bool() // 目标是否是共享状态
    val probeId = UInt(p.probeIdWidth bits) // 探测ID
  }
}

// DataMemWriteRsp 类：定义主存写响应的负载。
case class DataMemWriteRsp(p: DataMemBusParameter) extends Bundle {
  val error = Bool() // 是否发生错误
  val id = UInt(p.writeIdWidth bits) // 响应对应的写请求ID
}

// DataMemWriteBus 类：定义主存写总线接口。
case class DataMemWriteBus(p: DataMemBusParameter) extends Bundle with IMasterSlave {
  val cmd = Stream(Fragment(DataMemWriteCmd(p))) // 写命令流（带有Fragment标志，用于突发传输）
  val rsp = Flow(DataMemWriteRsp(p)) // 写响应流

  override def asMaster() = {
    master(cmd) // 命令作为主接口
    slave(rsp) // 响应作为从接口
  }

  // << 操作符重载：方便连接两个 DataMemWriteBus。
  def <<(m: DataMemWriteBus): Unit = {
    m.cmd >> this.cmd // 命令从 m 流向当前总线
    m.rsp << this.rsp // 响应从当前总线流向 m
  }

  // resizer 方法：将总线的数据宽度调整到 newDataWidth。
  def resizer(newDataWidth: Int): DataMemWriteBus = new Composite(this, "resizer") {
    val ret = DataMemWriteBus(
      p = p.copy(
        dataWidth = newDataWidth,
        withReducedBandwidth = p.withReducedBandwidth || newDataWidth > p.dataWidth // 如果数据宽度增加，则需要带宽缩减适配
      )
    )

    val cmdOutputStream = Stream(Fragment(Bits(newDataWidth bits))) // 创建中间流
    // 流片段宽度适配器：将写命令的数据部分进行宽度适配，并保留 last 标志。
    StreamFragmentWidthAdapter(cmd.translateWith(cmd.data).addFragmentLast(cmd.last), cmdOutputStream)

    ret.cmd.arbitrationFrom(cmdOutputStream) // 从适配后的流获取仲裁信号
    ret.cmd.id := self.cmd.id // ID
    ret.cmd.address := self.cmd.address // 地址
    ret.cmd.data := cmdOutputStream.fragment // 适配后的数据
    ret.cmd.last := cmdOutputStream.last // last 标志

    self.rsp << ret.rsp // 响应直接连接
  }.ret

  // toBmb 方法：将 DataMemWriteBus 转换为 BMB 写总线。
  def toBmb(): Bmb = new Composite(this, "toBmb") {
    val bmbConfig = BmbAccessParameter(
      addressWidth = p.addressWidth,
      dataWidth = p.dataWidth
    ).addSources(
      p.readIdCount, // BMB 读ID数量，这里用的是读ID数量，因为 BMB 的 SourceParameter 是通用的
      BmbSourceParameter(
        contextWidth = 0,
        lengthWidth = log2Up(p.lineSize),
        alignment = BmbParameter.BurstAlignement.LENGTH,
        canRead = false,
        withCachedRead = true
      )
    )

    val bmb = Bmb(bmbConfig) // 创建 BMB 总线
    bmb.cmd.arbitrationFrom(cmd) // 从 DataMemWriteBus 的命令流获取仲裁信号
    bmb.cmd.setWrite() // 设置为写命令
    bmb.cmd.address := cmd.address // 地址
    bmb.cmd.length := p.lineSize - 1 // 长度（缓存行大小-1）
    bmb.cmd.source := cmd.id // 源ID
    bmb.cmd.data := cmd.data // 数据
    bmb.cmd.last := cmd.last // 最后一个突发传输
    bmb.cmd.mask.setAll() // 掩码全1（表示写入所有字节）

    bmb.rsp.ready := True // BMB 响应始终就绪
    rsp.valid := bmb.rsp.valid // 响应有效性
    rsp.id := bmb.rsp.source // 响应ID
    rsp.error := bmb.rsp.isError // 响应错误
  }.bmb
}

// DataMemProbeCmd 类：定义主存探测命令的负载，用于缓存一致性协议。
case class DataMemProbeCmd(p: DataMemBusParameter) extends Bundle {
  val address = UInt(p.addressWidth bits) // 探测地址
  val id = UInt(p.probeIdWidth bits) // 探测ID
  val allowUnique = Bool() // 是否允许转换为独占状态
  val allowShared = Bool() // 是否允许转换为共享状态
}

// DataMemProbeRsp 类：定义主存探测响应的负载。
case class DataMemProbeRsp(p: DataMemBusParameter, fromProbe: Boolean) extends Bundle {
  val id = UInt(p.probeIdWidth bits) // 响应对应的探测ID
  val address = UInt(p.addressWidth bits) // 探测地址
  val fromUnique, fromShared = Bool() // 当前缓存行状态：是否为独占、是否为共享
  val toShared, toUnique = Bool() // 建议转换到的状态：是否转换为共享、是否转换为独占
  val allowShared, allowUnique = Bool() // 用于重做（redo）的权限信息，指示探测是否允许转换为共享/独占
  val redo = fromProbe generate Bool() // 是否需要重做探测（仅在 fromProbe 为True时生成）
  val writeback = fromProbe generate Bool() // 是否需要写回（仅在 fromProbe 为True时生成）

  // assignTilelinkC 方法：将探测响应的字段赋值给 TileLink C 通道（Channel C）的信号。
  def assignTilelinkC(c: ChannelC) = {
    c.opcode := tilelink.Opcode.C.PROBE_ACK() // 设置操作码为 ProbeAck
    // 设置 param 字段，报告缓存行状态转换信息。
    c.param := Param.report(
      fromUnique,
      fromShared,
      toUnique,
      toShared
    )
    c.source := id // 源ID
    c.address := address // 地址
    c.size := log2Up(p.lineSize) // 大小（缓存行大小的log2）
    c.data.assignDontCare() // 数据不关心
    c.corrupt.assignDontCare() // 损坏标志不关心
  }
}

// DataMemProbeBus 类：定义主存探测总线接口。
case class DataMemProbeBus(p: DataMemBusParameter) extends Bundle with IMasterSlave {
  val cmd = Flow(DataMemProbeCmd(p)) // 探测命令流
  val rsp = Flow(DataMemProbeRsp(p, true)) // 探测响应流

  override def asMaster() = {
    master(cmd) // 命令作为主接口
    slave(rsp) // 响应作为从接口
  }

  // << 操作符重载：方便连接两个 DataMemProbeBus。
  def <<(m: DataMemProbeBus): Unit = {
    m.cmd >> this.cmd // 命令从 m 流向当前总线
    m.rsp << this.rsp // 响应从当前总线流向 m
  }
}

// DataMemBus 类：定义完整的主存总线接口，包括读、写和可选的探测。
case class DataMemBus(p: DataMemBusParameter) extends Bundle with IMasterSlave {
  val read = DataMemReadBus(p) // 读总线
  val write = DataMemWriteBus(p) // 写总线
  val probe = p.withCoherency generate DataMemProbeBus(p) // 探测总线，仅在支持一致性时生成

  override def asMaster() = {
    master(read, write) // 读写总线作为主接口
    slave(probe) // 探测总线作为从接口
  }

  // resizer 方法：调整整个总线的数据宽度。
  def resizer(newDataWidth: Int): DataMemBus = new Composite(this, "resizer") {
    val ret = DataMemBus(
      p = p.copy(
        dataWidth = newDataWidth,
        withReducedBandwidth = p.withReducedBandwidth || newDataWidth > p.dataWidth // 如果数据宽度增加，则需要带宽缩减适配
      )
    )

    ret.read << read.resizer(newDataWidth) // 读总线进行宽度适配
    ret.write << write.resizer(newDataWidth) // 写总线进行宽度适配

  }.ret

  // toAxi4 方法：将 DataMemBus 转换为 AXI4 总线。
  def toAxi4(): Axi4 = new Composite(this, "toAxi4") {
    assert(!p.withCoherency) // 断言：如果转换为 AXI4，则不能支持一致性协议
    val idWidth = p.readIdWidth max p.writeIdWidth // ID 宽度取读写 ID 宽度的最大值

    // AXI4 配置
    val axiConfig = Axi4Config(
      addressWidth = p.addressWidth,
      dataWidth = p.dataWidth,
      idWidth = idWidth,
      useId = true,
      useRegion = false,
      useBurst = true,
      useLock = false,
      useCache = false,
      useSize = true,
      useQos = false,
      useLen = true,
      useLast = true,
      useResp = true,
      useProt = true,
      useStrb = true
    )

    val axi = Axi4(axiConfig) // 创建 AXI4 总线

    // READ 读通道适配
    axi.ar.valid := read.cmd.valid // AR 有效性
    axi.ar.addr := read.cmd.address // AR 地址
    axi.ar.id := read.cmd.id // AR ID
    axi.ar.prot := B"010" // AR 保护（非特权、可缓冲）
    axi.ar.len := p.lineSize * 8 / p.dataWidth - 1 // AR 长度（突发传输的beat数量-1）
    axi.ar.size := log2Up(p.dataWidth / 8) // AR 大小（字节为单位的传输大小的log2）
    axi.ar.setBurstINCR() // 设置为增量突发
    read.cmd.ready := axi.ar.ready // 读命令就绪信号

    read.rsp.valid := axi.r.valid // R 有效性
    read.rsp.data := axi.r.data // R 数据
    read.rsp.id := axi.r.id // R ID
    read.rsp.error := !axi.r.isOKAY() // R 错误
    axi.r.ready := (if (p.withReducedBandwidth) read.rsp.ready else True) // R 就绪信号

    // WRITE 写通道适配
    val (awRaw, wRaw) = StreamFork2(write.cmd) // 将写命令流分叉成两路：一路给 AW，一路给 W
    val awFiltred = awRaw.throwWhen(!awRaw.first) // AW 只取突发传输的第一个beat
    val aw = awFiltred.stage() // AW 流过一个阶段
    axi.aw.valid := aw.valid // AW 有效性
    axi.aw.addr := aw.address // AW 地址
    axi.aw.id := aw.id // AW ID
    axi.aw.prot := B"010" // AW 保护
    axi.aw.len := p.lineSize * 8 / p.dataWidth - 1 // AW 长度
    axi.aw.size := log2Up(p.dataWidth / 8) // AW 大小
    axi.aw.setBurstINCR() // 设置为增量突发
    aw.ready := axi.aw.ready // AW 就绪信号

    val w = wRaw.haltWhen(awFiltred.valid) // W 通道在 AW 有效时暂停
    axi.w.valid := w.valid // W 有效性
    axi.w.data := w.data // W 数据
    axi.w.strb.setAll() // W 字节选通（全1）
    axi.w.last := w.last // W 最后一个beat
    w.ready := axi.w.ready // W 就绪信号

    write.rsp.valid := axi.b.valid // B 有效性
    write.rsp.id := axi.b.id // B ID
    write.rsp.error := !axi.b.isOKAY() // B 错误
    axi.b.ready := True // B 始终就绪
  }.axi
}

// DataCacheParameters 类：定义数据缓存的配置参数。
case class DataCacheParameters(
    cacheSize: Int, // 缓存总大小（字节）
    wayCount: Int, // 缓存的组相联路数
    refillCount: Int, // 重填请求的并发数量
    writebackCount: Int, // 写回请求的并发数量
    memDataWidth: Int, // 主存数据宽度
    cpuDataWidth: Int, // CPU数据宽度
    preTranslationWidth: Int, // 翻译前的虚拟地址宽度
    postTranslationWidth: Int, // 翻译后的物理地址宽度
    withCoherency: Boolean = false, // 是否支持缓存一致性
    var probeIdWidth: Int = -1, // 探测ID宽度
    var ackIdWidth: Int = -1, // 应答ID宽度
    loadRefillCheckEarly: Boolean = true, // 加载重填检查是否提前
    storeRefillCheckEarly: Boolean = true, // 存储重填检查是否提前
    lineSize: Int = 64, // 缓存行大小（字节）
    loadReadBanksAt: Int = 0, // 加载在哪个流水线阶段读取数据bank
    loadReadTagsAt: Int = 1, // 加载在哪个流水线阶段读取Tag
    loadTranslatedAt: Int = 1, // 加载在哪个流水线阶段完成地址翻译
    loadHitsAt: Int = 1, // 加载在哪个流水线阶段计算hit
    loadHitAt: Int = 1, // 加载在哪个流水线阶段确定最终hit
    loadBankMuxesAt: Int = 1, // 加载在哪个流水线阶段进行bank MUX
    loadBankMuxAt: Int = 2, // 加载在哪个流水线阶段完成bank MUX
    loadControlAt: Int = 2, // 加载在哪个流水线阶段进行控制逻辑
    loadRspAt: Int = 2, // 加载在哪个流水线阶段给出响应
    storeReadBanksAt: Int = 0, // 存储在哪个流水线阶段读取数据bank
    storeReadTagsAt: Int = 1, // 存储在哪个流水线阶段读取Tag
    storeHitsAt: Int = 1, // 存储在哪个流水线阶段计算hit
    storeHitAt: Int = 1, // 存储在哪个流水线阶段确定最终hit
    storeControlAt: Int = 2, // 存储在哪个流水线阶段进行控制逻辑
    storeRspAt: Int = 2, // 存储在哪个流水线阶段给出响应
    tagsReadAsync: Boolean = true, // Tag是否异步读取
    reducedBankWidth: Boolean = false, // 数据bank是否使用缩减宽度
    val enableLog: Boolean = false // 是否启用日志
) {
  // memParameter：生成 DataMemBusParameter，用于创建主存总线接口。
  def memParameter = DataMemBusParameter(
    addressWidth = postTranslationWidth,
    dataWidth = memDataWidth,
    readIdCount = refillCount,
    writeIdCount = writebackCount,
    probeIdWidth = probeIdWidth,
    ackIdWidth = ackIdWidth,
    lineSize = lineSize,
    withReducedBandwidth = false,
    withCoherency = withCoherency
  )

  // toTilelinkS2mSupported：将 TileLink 的 S2m 支持参数转换为支持探测（probe）。
  def toTilelinkS2mSupported(proposed: S2mSupport) = S2mSupport(S2mTransfers(probe = SizeRange(lineSize)))
}

// DataCache 类：实现一个数据缓存模块。
class DataCache(val p: DataCacheParameters) extends Component {
  import p._ // 导入参数

  val io = new Bundle {
    val lock = slave(LockPort()) // 锁定端口：用于锁定物理地址
    val load = slave(
      DataLoadPort(
        preTranslationWidth = preTranslationWidth,
        postTranslationWidth = postTranslationWidth,
        dataWidth = cpuDataWidth,
        refillCount = refillCount,
        rspAt = loadRspAt,
        translatedAt = loadTranslatedAt
      )
    ) // 加载端口
    val store = slave(
      DataStorePort(
        postTranslationWidth = postTranslationWidth,
        dataWidth = cpuDataWidth,
        refillCount = refillCount
      )
    ) // 存储端口
    val mem = master(DataMemBus(memParameter)) // 主存总线接口
    val refillCompletions = out Bits (refillCount bits) // 重填完成标志
    val refillEvent = out Bool () // 重填事件（表示有新的重填请求）
    val writebackEvent = out Bool () // 写回事件（表示有新的写回请求）
    val writebackBusy = out Bool () // 写回忙碌标志
  }

  // 缓存相关计算参数
  val cpuWordWidth = cpuDataWidth // CPU字宽
  val bytePerMemWord = memDataWidth / 8 // 每个主存字字节数
  val bytePerFetchWord = cpuDataWidth / 8 // 每个CPU字字节数
  val waySize = cacheSize / wayCount // 每路大小
  val linePerWay = waySize / lineSize // 每路缓存行数
  val memDataPerWay = waySize / bytePerMemWord // 每路主存数据量
  val memData = HardType(Bits(memDataWidth bits)) // 主存数据类型
  val memWordPerLine = lineSize / bytePerMemWord // 每缓存行主存字数
  val tagWidth = postTranslationWidth - log2Up(waySize) // Tag位宽

  val tagRange = postTranslationWidth - 1 downto log2Up(linePerWay * lineSize) // Tag地址范围
  val lineRange = tagRange.low - 1 downto log2Up(lineSize) // Line地址范围
  val refillRange = tagRange.high downto lineRange.low // 重填地址范围

  val bankCount = wayCount // Bank数量等于路数
  val bankWidth = if (!reducedBankWidth) memDataWidth else Math.max(cpuWordWidth, memDataWidth / wayCount) // Bank宽度
  val bankByteSize = cacheSize / bankCount // 每个Bank的字节大小
  val bankWordCount = bankByteSize * 8 / bankWidth // 每个Bank的字数
  val bankWordToCpuWordRange = log2Up(bankWidth / 8) - 1 downto log2Up(bytePerFetchWord) // Bank字到CPU字的范围
  val memToBankRatio = bankWidth * bankCount / memDataWidth // 主存到Bank的比例
  val bankWord = HardType(Bits(bankWidth bits)) // Bank字类型

  assert(bankWidth <= memDataWidth) // 断言：Bank宽度不能超过主存数据宽度

  // Stageable 定义：用于流水线阶段之间传递信号。
  val ADDRESS_PRE_TRANSLATION = Stageable(UInt(preTranslationWidth bits)) // 翻译前地址
  val ADDRESS_POST_TRANSLATION = Stageable(UInt(postTranslationWidth bits)) // 翻译后地址
  val ABORD = Stageable(Bool()) // 终止标志
  val CPU_WORD = Stageable(Bits(cpuWordWidth bits)) // CPU字
  val CPU_MASK = Stageable(Bits(cpuWordWidth / 8 bits)) // CPU掩码
  val WAYS_HAZARD = Stageable(Bits(wayCount bits)) // 路冒险掩码
  val REDO_ON_DATA_HAZARD = Stageable(Bool()) // 数据冒险时重做
  val BANK_BUSY = Stageable(Bits(bankCount bits)) // Bank忙碌掩码
  val BANK_BUSY_REMAPPED = Stageable(Bits(bankCount bits)) // 重新映射的Bank忙碌掩码
  val REFILL_HITS_EARLY = Stageable(Bits(refillCount bits)) // 早期重填命中
  val REFILL_HITS = Stageable(Bits(refillCount bits)) // 重填命中
  val LOCKED, UNLOCKED = Stageable(Bool()) // 锁定/解锁标志
  val NEED_UNIQUE = Stageable(Bool()) // 需要独占访问标志

  // Tag 类：定义缓存Tag的结构。
  case class Tag() extends Bundle {
    val loaded = Bool() // 是否已加载
    val address = UInt(tagWidth bits) // Tag地址
    val fault = Bool() // 错误标志
    val unique = withCoherency generate Bool() // 独占标志（仅在支持一致性时生成）
  }

  // Status 类：定义缓存行状态的结构。
  case class Status() extends Bundle {
    val dirty = Bool() // 脏标志
  }

  // Stageable 定义：用于传递缓存状态信息。
  val STATUS = Stageable(Vec.fill(wayCount)(Status())) // 缓存路状态
  val BANKS_WORDS = Stageable(Vec.fill(bankCount)(bankWord())) // Bank数据字
  val WAYS_TAGS = Stageable(Vec.fill(wayCount)(Tag())) // 缓存路Tag
  val WAYS_HITS = Stageable(Bits(wayCount bits)) // 缓存路命中掩码
  val WAYS_HIT = Stageable(Bool()) // 缓存命中标志
  val MISS = Stageable(Bool()) // 缓存缺失标志
  val FAULT = Stageable(Bool()) // 错误标志
  val REDO = Stageable(Bool()) // 重做标志
  val IO = Stageable(Bool()) // IO访问标志
  val REFILL_SLOT = Stageable(Bits(refillCount bits)) // 重填槽位
  val REFILL_SLOT_FULL = Stageable(Bool()) // 重填槽位已满
  val GENERATION, GENERATION_OK = Stageable(Bool()) // 世代标志
  val PREFETCH = Stageable(Bool()) // 预取标志
  val PROBE = Stageable(Bool()) // 探测标志
  val ALLOW_UNIQUE = Stageable(Bool()) // 允许独占标志
  val ALLOW_SHARED = Stageable(Bool()) // 允许共享标志
  val PROBE_ID = Stageable(UInt(probeIdWidth bits)) // 探测ID
  val FLUSH = Stageable(Bool()) // 刷新标志
  val FLUSH_FREE = Stageable(Bool()) // 刷新后释放标志

  val BANKS_MUXES = Stageable(Vec.fill(bankCount)(Bits(cpuWordWidth bits))) // Bank多路选择器输出

  // banks 区域：实现数据缓存的Bank（RAM）存储。
  val banks = for (id <- 0 until bankCount) yield new Area {
    val mem = Mem(Bits(bankWidth bits), bankWordCount) // 创建存储Bank数据的RAM
    val write = mem.writePortWithMask(mem.getWidth / 8) // 带掩码的写端口
    val read = new Area {
      val usedByWriteBack = False // 指示该读端口是否被写回操作使用
      val cmd = Flow(mem.addressType) // 读命令流
      val rsp = mem.readSync(cmd.payload, cmd.valid) // 同步读响应
      KeepAttribute(rsp) // 保持属性，防止优化掉

      cmd.setIdle() // 默认设为空闲状态，TODO：这可能需要修改以优化时序。
    }
  }

  // tagsOrStatusWriteArbitration：Tag或状态写操作的仲裁器。
  val tagsOrStatusWriteArbitration = new Reservation()
  // waysWrite 区域：处理缓存Tag和状态的写操作。
  val waysWrite = new Area {
    val mask = Bits(wayCount bits) // 写掩码，指示写入哪些路
    val address = UInt(log2Up(linePerWay) bits) // 写入的缓存行地址
    val tag = Tag() // 要写入的Tag数据

    mask := 0 // 默认写掩码为0
    address.assignDontCare() // 默认地址不关心
    tag.assignDontCare() // 默认Tag数据不关心

    // 用于流水线中冒险检测
    val maskLast = RegNext(mask) // 上一个周期的写掩码
    val addressLast = RegNext(address) // 上一个周期的写地址
  }

  // ways 区域：实现缓存Tag的存储。
  val ways = for (id <- 0 until wayCount) yield new Area {
    val mem = Mem.fill(linePerWay)(Tag()) // 创建存储Tag的RAM
    mem.write(waysWrite.address, waysWrite.tag, waysWrite.mask(id)) // Tag的写操作
    val loadRead = new Area {
      val cmd = Flow(mem.addressType) // 加载读命令
      val rsp = if (tagsReadAsync) mem.readAsync(cmd.payload) else mem.readSync(cmd.payload, cmd.valid) // 异步或同步读
      KeepAttribute(rsp)
    }
    val storeRead = new Area {
      val cmd = Flow(mem.addressType) // 存储读命令
      val rsp = if (tagsReadAsync) mem.readAsync(cmd.payload) else mem.readSync(cmd.payload, cmd.valid) // 异步或同步读
      KeepAttribute(rsp)
    }
  }

  // status 区域：实现缓存行状态的存储。
  val status = new Area {
    // 加载/存储之间的冒险解决：在给定时间点，只有一个可以触发重填/改变状态。
    val mem = Mem.fill(linePerWay)(Vec.fill(wayCount)(Status())) // 创建存储状态的RAM
    val write = mem.writePort.setIdle() // 写端口，默认空闲
    val loadRead = new Area {
      val cmd = Flow(mem.addressType) // 加载读命令
      val rsp = if (tagsReadAsync) mem.readAsync(cmd.payload) else mem.readSync(cmd.payload, cmd.valid) // 异步或同步读
      KeepAttribute(rsp)
    }
    val storeRead = new Area {
      val cmd = Flow(mem.addressType) // 存储读命令
      val rsp = if (tagsReadAsync) mem.readAsync(cmd.payload) else mem.readSync(cmd.payload, cmd.valid) // 异步或同步读
      KeepAttribute(rsp)
    }
    val writeLast = write.stage() // 上一个周期的写操作（用于旁路）

    // bypass 方法：实现对状态的旁路逻辑。
    // 如果最近有对同一地址的写操作，则使用写操作的数据进行旁路。
    def bypass(status: Vec[Status], address: UInt, withLast: Boolean): Vec[Status] = {
      val ret = CombInit(status) // 组合初始化为当前状态
      if (withLast) when(writeLast.valid && writeLast.address === address(lineRange)) {
        ret := writeLast.data // 如果上一个周期的写有效且地址匹配，则旁路数据
      }
      when(write.valid && write.address === address(lineRange)) {
        ret := write.data // 如果当前周期的写有效且地址匹配，则旁路数据
      }
      ret
    }
    // bypass 方法重载：简化在流水线阶段中旁路状态的操作。
    def bypass(stage: Stage, address: Stageable[UInt], withLast: Boolean): Unit = {
      stage.overloaded(STATUS) := bypass(stage(STATUS), stage(address), withLast)
    }
  }

  val wayRandom = CounterFreeRun(wayCount) // 随机选择路数，用于替换策略

  // invalidate 区域：处理缓存失效（Invalidate）操作。
  val invalidate = new Area {
    val counter = Reg(UInt(log2Up(linePerWay) + 1 bits)) init (0) // 计数器，用于遍历所有缓存行
    val done = counter.msb // 失效操作是否完成
    val reservation = tagsOrStatusWriteArbitration.create(0) // 仲裁器，确保在失效时独占写Tag或状态
    when(!done && reservation.win) { // 如果未完成且赢得了仲裁
      reservation.takeIt() // 占用仲裁
      counter := counter + 1 // 计数器递增
      waysWrite.mask.setAll() // 设置所有路的写掩码
      waysWrite.address := counter.resized // 写地址为当前计数器值
      waysWrite.tag.loaded := False // 标记Tag为未加载（即失效）
    }
  }

  // PriorityArea 类：实现优先级仲裁逻辑。
  class PriorityArea(slots: Seq[(Bool, Bits)]) extends Area {
    val slotsWithId = slots.zipWithIndex.map(e => (e._1._1, e._1._2, e._2)) // (有效性，优先级掩码，ID)
    val hits = B(slots.map(_._1)) // 所有槽位的有效性
    val hit = hits.orR // 至少一个槽位有效
    // oh：One-hot 编码的优先级选择。
    // 逻辑：如果一个槽位有效，且没有更高优先级的槽位有效，则选择该槽位。
    val oh =
      hits & B(slotsWithId.map(slot => (B(slotsWithId.filter(_ != slot).map(other => hits(other._3))) & slot._2) === 0))
    val sel = OHToUInt(oh) // 选中的槽位的ID
    val lock = RegNext(oh) init (0) // 锁定选中的槽位，确保其在下一个周期继续被选中
    when(lock.orR) { oh := lock } // 如果有锁定的槽位，则使用锁定值。
  }

  // refill 区域：处理缓存重填（Refill）操作。
  val refill = new Area {
    val slots = for (refillId <- 0 until refillCount) yield new Area {
      val id = refillId // 重填槽位ID
      val valid = RegInit(False) // 槽位是否有效（正在进行重填）
      val address = Reg(UInt(postTranslationWidth bits)) // 重填地址
      val way = Reg(UInt(log2Up(wayCount) bits)) // 重填到哪一路
      val cmdSent = Reg(Bool()) // 命令是否已发送给主存
      val priority = Reg(Bits(refillCount - 1 bits)) // 优先级（用于仲裁器）
      val unique = withCoherency generate Reg(Bool()) // 请求是否独占（仅在支持一致性时生成）
      val data = withCoherency generate Reg(Bool()) // 请求是否携带数据（仅在支持一致性时生成）
      val ackId = withCoherency generate Reg(UInt(ackIdWidth bits)) // 应答ID（仅在支持一致性时生成）
      val ackValid = withCoherency generate RegInit(False) // 应答是否有效（仅在支持一致性时生成）

      // 这个计数器确保在重填内存传输结束前开始但结束后才完成的加载/存储操作需要重试。
      val loaded = Reg(Bool()) // 重填数据是否已加载到缓存中
      val loadedCounterMax = (loadControlAt - 1) max (storeControlAt - 1) // 加载完成前的最大延迟
      val loadedCounter = Reg(UInt(log2Up(loadedCounterMax + 1) bits)) // 计数器，用于追踪加载延迟
      val loadedDone = loadedCounter === loadedCounterMax // 加载延迟是否完成
      loadedCounter := loadedCounter + U(loaded && !loadedDone).resized // 计数器递增
      // 槽位有效性：如果 loadedDone 且一致性协议中 ackValid 也完成，则该槽位失效。
      valid clearWhen (loadedDone && withCoherency.mux(!ackValid, True))

      val free = !valid // 槽位是否空闲

      val victim = Reg(Bits(writebackCount bits)) // 受害者（被替换掉的写回槽位）
      val writebackHazards = Reg(Bits(writebackCount bits)) // 写回冒险掩码
    }

    // isLineBusy 方法：检查给定地址的缓存行是否正在被重填。
    def isLineBusy(address: UInt) = slots.map(s => s.valid && s.address(lineRange) === address(lineRange)).orR

    val free = B(OHMasking.first(slots.map(_.free))) // 找到第一个空闲的重填槽位
    val full = slots.map(!_.free).andR // 所有重填槽位是否都已满

    // push 端口：用于发起新的重填请求。
    val push = Flow(new Bundle {
      val address = UInt(postTranslationWidth bits) // 重填地址
      val way = UInt(log2Up(wayCount) bits) // 重填到哪一路
      val victim = Bits(writebackCount bits) // 受害者（被替换掉的写回槽位）
      val unique = Bool() // 请求是否独占
      val data = Bool() // 请求是否携带数据
    }).setIdle() // 默认空闲

    // 遍历所有重填槽位，处理 push 请求。
    for (slot <- slots) when(push.valid) { // 如果有新的重填请求
      when(free(slot.id)) { // 如果当前槽位空闲
        slot.valid := True // 标记槽位有效
        slot.address := push.address // 存储地址
        slot.way := push.way // 存储路数
        slot.cmdSent := False // 命令未发送
        slot.priority.setAll() // 优先级全设为高
        slot.loaded := False // 未加载
        slot.loadedCounter := 0 // 计数器清零
        slot.victim := push.victim // 存储受害者
        slot.writebackHazards := 0 // 写回冒险清零
        if (withCoherency) { // 如果支持一致性
          slot.unique := push.unique // 存储独占标志
          slot.data := push.data // 存储数据标志
        }
        if (enableLog) { report(L"Refill: slot ${slot.id} allocated for address ${push.address} (way ${push.way})") }
      } otherwise {
        val freeFiltred = free.asBools.patch(slot.id, Nil, 1) // 过滤掉当前槽位后的空闲掩码
        // 降低未选中槽位的优先级，确保仲裁器能够选中被选中的槽位。
        (slot.priority.asBools, freeFiltred).zipped.foreach(_ clearWhen (_))
      }
    }

    // read 区域：处理从主存读取重填数据。
    val read = new Area {
      val arbiter = new PriorityArea(
        slots.map(s => (s.valid && !s.cmdSent && s.victim === 0 && s.writebackHazards === 0, s.priority))
      ) // 仲裁器：选择下一个要发送给主存的重填请求。

      val writebackHazards = Bits(writebackCount bits) // 写回冒险掩码
      val writebackHazard = writebackHazards.orR // 是否有写回冒险
      when(io.mem.read.cmd.fire || writebackHazard) { arbiter.lock := 0 } // 如果读命令发送或有写回冒险，则清除仲裁锁定。

      val cmdAddress =
        slots.map(_.address(tagRange.high downto lineRange.low)).read(arbiter.sel) @@ U(0, lineRange.low bit) // 命令地址
      io.mem.read.cmd.valid := arbiter.hit && !writebackHazard // 读命令有效性
      io.mem.read.cmd.id := arbiter.sel // 读命令ID
      io.mem.read.cmd.address := cmdAddress // 读命令地址
      if (withCoherency) { // 如果支持一致性
        io.mem.read.cmd.unique := slots.map(_.unique).read(arbiter.sel) // 读命令独占标志
        io.mem.read.cmd.data := slots.map(_.data).read(arbiter.sel) // 读命令数据标志
      }
      whenMasked(slots, arbiter.oh) { slot => // 当仲裁器选中某个槽位时
        slot.writebackHazards := writebackHazards // 更新写回冒险掩码
        slot.cmdSent setWhen (io.mem.read.cmd.ready && !writebackHazard) // 命令发送成功
        if (enableLog) { report(L"Refill: slot ${slot.id} command sent for address ${slot.address}") }
      }

      val rspAddress = slots.map(_.address).read(io.mem.read.rsp.id) // 响应地址
      val way = slots.map(_.way).read(io.mem.read.rsp.id) // 响应的路数
      val wordIndex = KeepAttribute(Reg(UInt(log2Up(memWordPerLine) bits)) init (0)) // 字索引
      val rspWithData = p.withCoherency.mux(io.mem.read.rsp.withData, True) // 响应是否携带数据
      // 断言：如果要求带数据但没收到，则报错（仅在一致性协议中）。
      // assert(!(io.mem.read.rsp.valid && !rspWithData && slots.map(_.data).read(io.mem.read.rsp.id)), "Data cache asked for data but didn't recieved any :(")

      val bankWriteNotif = B(0, bankCount bits) // Bank写通知
      for ((bank, bankId) <- banks.zipWithIndex) { // 遍历所有Bank
        if (!reducedBankWidth) { // 如果Bank宽度没有缩减
          bankWriteNotif(bankId) := io.mem.read.rsp.valid && rspWithData && way === bankId // Bank写有效性
          bank.write.valid := bankWriteNotif(bankId) // 设置Bank写有效
          bank.write.address := rspAddress(lineRange) @@ wordIndex // 设置Bank写地址
          bank.write.data := io.mem.read.rsp.data // 设置Bank写数据
        } else { // 如果Bank宽度缩减
          val sel = U(bankId) - way // Bank选择
          val groupSel = way(log2Up(bankCount) - 1 downto log2Up(bankCount / memToBankRatio)) // 组选择
          val subSel = sel(log2Up(bankCount / memToBankRatio) - 1 downto 0) // 子选择
          bankWriteNotif(bankId) := io.mem.read.rsp.valid && rspWithData && groupSel === (bankId >> log2Up(
            bankCount / memToBankRatio
          )) // Bank写有效性
          bank.write.valid := bankWriteNotif(bankId) // 设置Bank写有效
          bank.write.address := rspAddress(lineRange) @@ wordIndex @@ (subSel) // 设置Bank写地址
          bank.write.data := io.mem.read.rsp.data.subdivideIn(bankCount / memToBankRatio slices)(subSel) // 设置Bank写数据
        }
        banks(bankId).write.mask := (default -> true) // Bank写掩码全1
      }

      val hadError = RegInit(False) setWhen (io.mem.read.rsp.valid && io.mem.read.rsp.error) // 是否有错误
      val fire = False // 重填操作是否完成
      val reservation = tagsOrStatusWriteArbitration.create(0) // 仲裁器：用于写Tag或状态
      val faulty = hadError || io.mem.read.rsp.error // 是否有错误

      io.refillCompletions := 0 // 重填完成标志清零
      io.mem.read.rsp.ready := True // 内存响应始终就绪
      when(io.mem.read.rsp.valid) { // 如果内存响应有效
        when(rspWithData) { // 如果响应携带数据
          wordIndex := wordIndex + 1 // 字索引递增
        }
        when(wordIndex === wordIndex.maxValue || !rspWithData) { // 如果是最后一个字或没有数据
          hadError := False // 清除错误标志
          fire := True // 标记重填完成
          if (!withCoherency) io.refillCompletions(io.mem.read.rsp.id) := True // 设置重填完成标志（非一致性协议）
          reservation.takeIt() // 占用Tag/状态写仲裁
          waysWrite.mask(way) := True // 设置Tag写掩码
          waysWrite.address := rspAddress(lineRange) // 设置Tag写地址
          waysWrite.tag.fault := faulty // 写入Tag错误标志
          waysWrite.tag.address := rspAddress(tagRange) // 写入Tag地址
          waysWrite.tag.loaded := True // 写入Tag加载标志
          if (withCoherency) { // 如果支持一致性
            waysWrite.tag.unique := io.mem.read.rsp.unique // 写入Tag独占标志
          }
          slots.onSel(io.mem.read.rsp.id) { s => // 选中对应的重填槽位
            s.loaded := True // 标记槽位数据已加载
            if (withCoherency) { // 如果支持一致性
              s.ackValid := True // 应答有效
              s.ackId := io.mem.read.rsp.ackId // 存储应答ID
            }
          }
          if (enableLog) {
            report(L"Refill: slot ${io.mem.read.rsp.id} data loaded for address ${rspAddress}, fault ${faulty}")
          }
        }
      }
    }

    // ackSender 区域：发送主存读应答（仅在支持一致性时生成）。
    val ackSender = withCoherency generate new Area {
      val requests = slots.map(_.ackValid) // 所有重填槽位的应答有效性
      val oh = OHMasking.first(requests) // 找到第一个需要发送应答的槽位
      io.mem.read.ack.valid := requests.orR // 应答有效性
      io.mem.read.ack.ackId := OhMux.or(oh, slots.map(_.ackId)) // 应答ID
      when(io.mem.read.ack.ready) { // 如果应答就绪
        io.refillCompletions.asBools.onMask(oh)(_ := True) // 设置重填完成标志
        slots.onMask(oh)(_.ackValid := False) // 清除槽位的应答有效标志
        if (enableLog) { report(L"Refill: ack sent for slot ${OHToUInt(oh)}") }
      }
    }
  }

  // writeback 区域：处理缓存行写回（Writeback）操作。
  val writeback = new Area {
    val slots = for (writebackId <- 0 until writebackCount) yield new Area {
      val id = writebackId // 写回槽位ID
      val fire = False // 写回操作是否完成
      val valid = RegInit(False) clearWhen (fire) // 槽位是否有效
      val address = Reg(UInt(postTranslationWidth bits)) // 写回地址
      val way = Reg(UInt(log2Up(wayCount) bits)) // 写回哪一路
      val priority = Reg(Bits(writebackCount - 1 bits)) // 优先级
      val readCmdDone = Reg(Bool()) // 读命令是否完成
      val victimBufferReady = Reg(Bool()) // 受害者缓冲是否就绪
      val readRspDone = Reg(Bool()) // 读响应是否完成
      val writeCmdDone = Reg(Bool()) // 写命令是否完成

      val coherency = withCoherency generate new Bundle { // 一致性相关标志
        val release = Bool() // 是否释放
        val dirty = Bool() // 是否脏
        val fromUnique = Bool() // 来源是否独占
        val toShared = Bool() // 目标是否共享
        val probeId = UInt(probeIdWidth bits) // 探测ID
      }

      val free = !valid // 槽位是否空闲

      // 更新重填槽位的写回冒险掩码：如果当前写回槽位有效且地址匹配重填槽位，则标记为冒险。
      refill.read.writebackHazards(id) := valid && address(refillRange) === refill.read.cmdAddress(refillRange)
      when(fire) { refill.slots.foreach(_.writebackHazards(id) := False) } // 如果写回完成，清除重填槽位的冒险标记。
    }

    io.writebackBusy := slots.map(_.valid).orR // 写回忙碌标志：任一写回槽位有效即为忙碌。

    // isLineBusy 方法：检查给定地址的缓存行是否正在被写回。
    def isLineBusy(address: UInt) =
      False // slots.map(s => s.valid && s.way === way && s.address(lineRange) === address(lineRange)).orR

    val free = B(OHMasking.first(slots.map(_.free))) // 找到第一个空闲的写回槽位
    val full = slots.map(!_.free).andR // 所有写回槽位是否都已满

    // push 端口：用于发起新的写回请求。
    val push = Flow(new Bundle {
      val address = UInt(postTranslationWidth bits) // 写回地址
      val way = UInt(log2Up(wayCount) bits) // 写回哪一路

      // TtoB TtoN BtoN （一致性协议中的状态转换，仅在支持一致性时生成）
      val dirty = withCoherency generate Bool()
      val fromUnique = withCoherency generate Bool()
      val toShared = withCoherency generate Bool()
      val release = withCoherency generate Bool()
      val probeId = withCoherency generate UInt(probeIdWidth bits)
    }).setIdle() // 默认空闲

    // 遍历所有写回槽位，处理 push 请求。
    for (slot <- slots) when(push.valid) { // 如果有新的写回请求
      when(free(slot.id)) { // 如果当前槽位空闲
        slot.valid := True // 标记槽位有效
        slot.address := push.address // 存储地址
        slot.way := push.way // 存储路数

        slot.writeCmdDone := False // 写命令未完成
        slot.priority.setAll() // 优先级全设为高
        if (withCoherency) { // 如果支持一致性
          slot.coherency.release := push.release // 存储释放标志
          slot.coherency.dirty := push.dirty // 存储脏标志
          slot.coherency.fromUnique := push.fromUnique // 存储来源独占标志
          slot.coherency.toShared := push.toShared // 存储目标共享标志
          slot.coherency.probeId := push.probeId // 存储探测ID
          slot.readCmdDone := !push.dirty // 读命令完成（如果不是脏数据，则不需要读数据）
          slot.readRspDone := !push.dirty // 读响应完成
          slot.victimBufferReady := !push.dirty // 受害者缓冲就绪
        } else { // 如果不支持一致性
          slot.readCmdDone := False // 读命令未完成
          slot.readRspDone := False // 读响应未完成
          slot.victimBufferReady := False // 受害者缓冲未就绪
        }
        if (enableLog) { report(L"Writeback: slot ${slot.id} allocated for address ${push.address} (way ${push.way})") }
      } otherwise {
        val freeFiltred = free.asBools.patch(slot.id, Nil, 1) // 过滤掉当前槽位后的空闲掩码
        // 降低未选中槽位的优先级。
        (slot.priority.asBools, freeFiltred).zipped.foreach(_ clearWhen (_))
      }
    }

    val victimBuffer = Mem.fill(writebackCount * memWordPerLine)(Bits(memDataWidth bits)) // 受害者缓冲：存储被替换缓存行的数据。
    // read 区域：从Bank读取写回数据到 victimBuffer。
    val read = new Area {
      val arbiter = new PriorityArea(slots.map(s => (s.valid && !s.readCmdDone, s.priority))) // 仲裁器：选择下一个要读Bank的写回请求。

      val address = slots.map(_.address).read(arbiter.sel) // 读取地址
      val way = slots.map(_.way).read(arbiter.sel) // 读取路数
      val wordIndex = KeepAttribute(Reg(UInt(log2Up(memWordPerLine) bits)) init (0)) // 字索引

      val slotRead = Flow(new Bundle { // 读槽位信号
        val id = UInt(log2Up(writebackCount) bits) // 写回槽位ID
        val last = Bool() // 是否是最后一个字
        val wordIndex = UInt(log2Up(memWordPerLine) bits) // 字索引
        val way = UInt(log2Up(wayCount) bits) // 路数
      })
      slotRead.valid := arbiter.hit // 读槽位有效性
      slotRead.id := arbiter.sel // 读槽位ID
      slotRead.wordIndex := wordIndex // 读槽位字索引
      slotRead.way := way // 读槽位路数
      slotRead.last := wordIndex === wordIndex.maxValue // 是否是最后一个字
      wordIndex := wordIndex + U(slotRead.valid) // 字索引递增
      when(slotRead.valid && slotRead.last) { // 如果读槽位有效且是最后一个字
        whenMasked(slots, arbiter.oh) { _.readCmdDone := True } // 标记读命令完成
        arbiter.lock := 0 // 清除仲裁锁定
        if (enableLog) { report(L"Writeback: slot ${slotRead.id} all bank reads commanded") }
      }
      when(slotRead.fire) { // 如果读槽位触发
        for (slot <- refill.slots) slot.victim(slotRead.id) := False // 清除重填槽位的受害者标记
      }

      for ((bank, bankId) <- banks.zipWithIndex) { // 遍历所有Bank
        if (!reducedBankWidth) { // 如果Bank宽度没有缩减
          when(slotRead.valid && way === bankId) { // 如果读槽位有效且路数匹配
            bank.read.cmd.valid := True // Bank读命令有效
            bank.read.cmd.payload := address(lineRange) @@ wordIndex // Bank读命令地址
            bank.read.usedByWriteBack := True // Bank读端口被写回使用
          }
        } else { // 如果Bank宽度缩减
          val sel = U(bankId) - way // Bank选择
          val groupSel = way(log2Up(bankCount) - 1 downto log2Up(bankCount / memToBankRatio)) // 组选择
          val subSel = sel(log2Up(bankCount / memToBankRatio) - 1 downto 0) // 子选择
          when(arbiter.hit && groupSel === (bankId >> log2Up(bankCount / memToBankRatio))) { // 如果仲裁命中且组选择匹配
            bank.read.cmd.valid := True // Bank读命令有效
            bank.read.cmd.payload := address(lineRange) @@ wordIndex @@ (subSel) // Bank读命令地址
            bank.read.usedByWriteBack := True // Bank读端口被写回使用
          }
        }
      }

      val slotReadLast = slotRead.stage() // 读槽位流过一个阶段
      val readedData = Bits(memDataWidth bits) // 读取到的数据

      if (!reducedBankWidth) { // 如果Bank宽度没有缩减
        readedData := banks.map(_.read.rsp).read(slotReadLast.way) // 从Bank读响应中读取数据
      } else { // 如果Bank宽度缩减
        for ((slice, sliceId) <- readedData.subdivideIn(bankWidth bits).zipWithIndex) {
          ??? // TODO：实现缩减宽度时的Bank数据组合
        }
      }

      when(slotReadLast.valid) { // 如果读槽位有效
        victimBuffer.write(slotReadLast.id @@ slotReadLast.wordIndex, readedData) // 将读取到的数据写入受害者缓冲
        whenIndexed(slots, slotReadLast.id) { _.victimBufferReady := True } // 标记受害者缓冲就绪
        when(slotReadLast.last) { // 如果是最后一个字
          whenIndexed(slots, slotReadLast.id) { _.readRspDone := True } // 标记读响应完成
          if (enableLog) { report(L"Writeback: slot ${slotReadLast.id} victim buffer loaded") }
        }
      }
    }

    // write 区域：将 victimBuffer 中的数据写回主存。
    val write = new Area {
      val arbiter = new PriorityArea(
        slots.map(s => (s.valid && s.victimBufferReady && !s.writeCmdDone, s.priority))
      ) // 仲裁器：选择下一个要写回主存的请求。
      val wordIndex = KeepAttribute(Reg(UInt(log2Up(memWordPerLine) bits)) init (0)) // 字索引
      val last = wordIndex === wordIndex.maxValue // 是否是最后一个字

      val bufferRead = Stream(new Bundle { // 受害者缓冲读信号
        val id = UInt(log2Up(writebackCount) bits) // 写回槽位ID
        val address = UInt(postTranslationWidth bits) // 地址
        val last = Bool() // 是否是最后一个字
        val coherency = withCoherency generate new Bundle { // 一致性相关标志
          val release = Bool() // 是否释放
          val dirty = Bool() // 是否脏
          val fromUnique = Bool() // 来源是否独占
          val toShared = Bool() // 目标是否共享
          val probeId = UInt(p.probeIdWidth bits) // 探测ID
        }
      })
      bufferRead.valid := arbiter.hit // 受害者缓冲读有效性
      bufferRead.id := arbiter.sel // 受害者缓冲读ID
      bufferRead.last := last // 受害者缓冲读是否是最后一个字
      bufferRead.address := slots.map(_.address).read(arbiter.sel) // 受害者缓冲读地址
      val c = withCoherency generate new Area { // 一致性逻辑
        last setWhen (!bufferRead.coherency.dirty) // 如果不是脏数据，则该传输是最后一个（TileLink C通道）
        bufferRead.coherency.release := slots.map(_.coherency.release).read(arbiter.sel) // 释放标志
        bufferRead.coherency.dirty := slots.map(_.coherency.dirty).read(arbiter.sel) // 脏标志
        bufferRead.coherency.fromUnique := slots.map(_.coherency.fromUnique).read(arbiter.sel) // 来源独占标志
        bufferRead.coherency.toShared := slots.map(_.coherency.toShared).read(arbiter.sel) // 目标共享标志
        bufferRead.coherency.probeId := slots.map(_.coherency.probeId).read(arbiter.sel) // 探测ID
      }
      wordIndex := wordIndex + U(bufferRead.fire && withCoherency.mux(bufferRead.coherency.dirty, True)) // 字索引递增
      when(bufferRead.fire && last) { // 如果受害者缓冲读触发且是最后一个字
        whenMasked(slots, arbiter.oh)(_.writeCmdDone := True) // 标记写命令完成
        arbiter.lock := 0 // 清除仲裁锁定
        if (enableLog) { report(L"Writeback: slot ${bufferRead.id} all mem writes commanded") }
      }

      val cmd = bufferRead.stage() // 命令流过一个阶段
      val word = victimBuffer.readSync(bufferRead.id @@ wordIndex, bufferRead.ready) // 从受害者缓冲同步读取数据
      io.mem.write.cmd.arbitrationFrom(cmd) // 从命令流获取仲裁信号
      io.mem.write.cmd.address := cmd.address // 写命令地址
      io.mem.write.cmd.data := word // 写命令数据
      io.mem.write.cmd.id := cmd.id // 写命令ID
      io.mem.write.cmd.last := cmd.last // 写命令最后一个beat
      if (withCoherency) { // 如果支持一致性
        io.mem.write.cmd.coherent.release := cmd.coherency.release // 释放标志
        io.mem.write.cmd.coherent.dirty := cmd.coherency.dirty // 脏标志
        io.mem.write.cmd.coherent.fromUnique := cmd.coherency.fromUnique // 来源独占标志
        io.mem.write.cmd.coherent.toShared := cmd.coherency.toShared // 目标共享标志
        io.mem.write.cmd.coherent.probeId := cmd.coherency.probeId // 探测ID
        when(cmd.fire && cmd.last && !cmd.coherency.release) { // 如果命令触发且是最后一个beat且不是释放操作
          slots.onSel(cmd.id) { s => // 选中对应的写回槽位
            s.fire := True // 标记写回完成
          }
        }
      }

      when(io.mem.write.rsp.valid) { // 如果主存写响应有效
        whenIndexed(slots, io.mem.write.rsp.id) { s => // 选中对应的写回槽位
          s.fire := True // 标记写回完成
          if (enableLog) {
            report(L"Writeback: slot ${io.mem.write.rsp.id} completed, error ${io.mem.write.rsp.error}")
          }
        }
      }
    }
  }

  // isLineBusy 方法：检查给定地址的缓存行是否正在被重填或写回。
  def isLineBusy(address: UInt) = refill.isLineBusy(address) || writeback.isLineBusy(address)

  // waysHazard 方法：标记流水线中可能发生的缓存行冒险。
  def waysHazard(stages: Seq[Stage], address: Stageable[UInt]): Unit = {
    for (s <- stages) { // 遍历指定的流水线阶段
      s.overloaded(WAYS_HAZARD) := s(WAYS_HAZARD) | waysWrite.maskLast.andMask(
        waysWrite.addressLast === s(address)(lineRange)
      ) // 如果上一个周期的写操作与当前阶段的地址匹配，则标记为冒险。
    }
  }

  // load 区域：实现加载流水线。
  val load = new Area {
    val pipeline = new Pipeline {
      val stages = Array.fill(loadRspAt + 1)(newStage()) // 创建加载流水线阶段
      connect(stages)(List(M2S())) // 连接流水线阶段（Master to Slave）

      for ((stage, stageId) <- stages.zipWithIndex) { // 遍历流水线阶段
        stage.throwIt(io.load.cancels(stageId)) // 根据取消掩码抛弃流水线中的请求
      }
    }

    // 定义加载流水线中的各个关键阶段。
    val readBanksStage = pipeline.stages(loadReadBanksAt) // 读取Bank阶段
    val readTagsStage = pipeline.stages(loadReadTagsAt) // 读取Tag阶段
    val translatedStage = pipeline.stages(loadTranslatedAt) // 地址翻译完成阶段
    val hitsStage = pipeline.stages(loadHitsAt) // 计算命中阶段
    val hitStage = pipeline.stages(loadHitAt) // 确定最终命中阶段
    val bankMuxesStage = pipeline.stages(loadBankMuxesAt) // Bank MUX阶段
    val bankMuxStage = pipeline.stages(loadBankMuxAt) // Bank MUX完成阶段
    val preControlStage = pipeline.stages(loadControlAt - 1) // 控制逻辑前一阶段
    val controlStage = pipeline.stages(loadControlAt) // 控制逻辑阶段
    val rspStage = pipeline.stages(loadRspAt) // 响应阶段

    // 标记从 loadReadBanksAt + 1 到 loadReadBanksAt + 1 阶段的写冒险。
    waysHazard((loadReadBanksAt + 1 to loadReadBanksAt + 1).map(pipeline.stages(_)), ADDRESS_PRE_TRANSLATION)
    // start 区域：加载流水线的起始阶段。
    val start = new Area {
      val stage = pipeline.stages.head // 第一个阶段

      import stage._ // 导入当前阶段的信号

      io.load.cmd.ready := True // 加载命令始终就绪
      isValid := io.load.cmd.valid // 阶段有效性来自命令有效性
      ADDRESS_PRE_TRANSLATION := io.load.cmd.virtual // 翻译前地址
      REDO_ON_DATA_HAZARD := io.load.cmd.redoOnDataHazard // 数据冒险时重做
      WAYS_HAZARD := 0 // 冒险掩码清零
      UNLOCKED := io.load.cmd.unlocked // 未锁定
      NEED_UNIQUE := io.load.cmd.unique // 需要独占访问
      if (enableLog) { report(L"Load: cmd received. ${io.load.cmd.format()}") }
    }

    // fetch 区域：加载流水线的取数和Tag检查阶段。
    val fetch = new Area {
      for ((bank, bankId) <- banks.zipWithIndex) yield new Area { // 遍历所有Bank
        {
          import readBanksStage._ // 导入读取Bank阶段的信号
          BANK_BUSY(bankId) := bank.read.usedByWriteBack // Bank忙碌：被写回操作使用
          when(!BANK_BUSY(bankId)) { // 如果Bank不忙碌
            bank.read.cmd.valid := !isStuck // Bank读命令有效
            bank.read.cmd.payload := ADDRESS_PRE_TRANSLATION(lineRange.high downto log2Up(bankWidth / 8)) // Bank读命令地址
          }
          // 重新映射 Bank 忙碌掩码，考虑 Bank 写操作的数据冒险。
          overloaded(BANK_BUSY)(bankId) := BANK_BUSY(bankId) || bank.write.valid && REDO_ON_DATA_HAZARD
        }

        {
          val stage = pipeline.stages(loadReadBanksAt + 1) // 下一个阶段
          import stage._ // 导入当前阶段的信号
          BANKS_WORDS(bankId) := banks(bankId).read.rsp // Bank数据字来自Bank读响应

          // wayToBank：将路ID转换为Bank ID（用于缩减Bank宽度的情况）。
          def wayToBank(way: Int): UInt = {
            val wayId = U(way, log2Up(wayCount) bits)
            if (!reducedBankWidth) return wayId // 如果没有缩减Bank宽度，路ID就是Bank ID
            // 否则根据地址和路ID计算Bank ID
            (wayId >> log2Up(bankCount / memToBankRatio)) @@ ((wayId + (ADDRESS_PRE_TRANSLATION(
              log2Up(bankWidth / 8),
              log2Up(bankCount) bits
            ))).resize(log2Up(bankCount / memToBankRatio)))
          }

          BANK_BUSY_REMAPPED(bankId) := BANK_BUSY(wayToBank(bankId)) // 重新映射Bank忙碌状态
        }

        {
          import bankMuxesStage._; // 导入Bank MUX阶段的信号
          // Bank多路选择器输出：从Bank数据字中根据地址选择CPU字。
          BANKS_MUXES(bankId) := BANKS_WORDS(bankId)
            .subdivideIn(cpuWordWidth bits)
            .read(ADDRESS_PRE_TRANSLATION(bankWordToCpuWordRange))
        }
      }

      val bankMuxStd = !reducedBankWidth generate new Area { // 标准Bank MUX（不缩减Bank宽度）
        import bankMuxStage._ // 导入Bank MUX完成阶段的信号
        CPU_WORD := OhMux.or(WAYS_HITS, BANKS_MUXES) // 根据命中路数选择Bank MUX输出
      }

      val bankMuxReduced = reducedBankWidth generate new Area { // 缩减Bank宽度时的Bank MUX
        import bankMuxStage._ // 导入Bank MUX完成阶段的信号
        val wayId = OHToUInt(WAYS_HITS) // 命中路数的One-hot转二进制
        // 根据路ID和地址计算Bank ID
        val bankId = (wayId >> log2Up(bankCount / memToBankRatio)) @@ ((wayId + (ADDRESS_PRE_TRANSLATION(
          log2Up(bankWidth / 8),
          log2Up(bankCount) bits
        ))).resize(log2Up(bankCount / memToBankRatio)))
        CPU_WORD := BANKS_MUXES.read(bankId) // 从Bank MUX输出中读取数据
      }

      translatedStage(ADDRESS_POST_TRANSLATION) := io.load.translated.physical // 翻译后地址
      translatedStage(ABORD) := io.load.translated.abord // 终止标志

      for ((way, wayId) <- ways.zipWithIndex) yield new Area { // 遍历所有路
        {
          import readTagsStage._ // 导入读取Tag阶段的信号
          way.loadRead.cmd.valid := !isStuck // Tag读命令有效
          way.loadRead.cmd.payload := ADDRESS_PRE_TRANSLATION(lineRange) // Tag读命令地址
        }
        pipeline.stages(loadReadTagsAt + (!tagsReadAsync).toInt)(WAYS_TAGS)(wayId) := ways(wayId).loadRead.rsp; // Tag数据
        {
          import hitsStage._; // 导入计算命中阶段的信号
          // 命中判断：Tag已加载且Tag地址与翻译后地址的Tag部分匹配。
          WAYS_HITS(wayId) := WAYS_TAGS(wayId).loaded && WAYS_TAGS(wayId).address === ADDRESS_POST_TRANSLATION(tagRange)
        }
      }

      {
        import hitStage._; // 导入最终命中阶段的信号
        WAYS_HIT := B(WAYS_HITS).orR // 最终命中标志
      }

      status.loadRead.cmd.valid := !readTagsStage.isStuck // 状态读命令有效
      status.loadRead.cmd.payload := readTagsStage(ADDRESS_PRE_TRANSLATION)(lineRange) // 状态读命令地址
      pipeline.stages(loadReadTagsAt + (!tagsReadAsync).toInt)(STATUS) := status.loadRead.rsp // 状态数据

      val statusBypassOn =
        (loadReadTagsAt + (!tagsReadAsync).toInt until loadControlAt).map(pipeline.stages(_)) // 需要进行状态旁路的阶段
      // 状态旁路：确保读到的是最新的状态。
      statusBypassOn.foreach(stage => status.bypass(stage, ADDRESS_POST_TRANSLATION, stage == statusBypassOn.head))
    }

    // refillCheckEarly 区域：早期重填检查。
    val refillCheckEarly = loadRefillCheckEarly generate new Area {
      val stage = pipeline.stages(loadControlAt - 1) // 控制逻辑前一阶段
      import stage._ // 导入当前阶段的信号

      // 重填命中：检查是否有正在进行或即将进行的重填操作与当前加载地址匹配。
      REFILL_HITS_EARLY := B(
        refill.slots.map(r => r.valid && r.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange))
      )
      // refillPushHit：是否有新的重填请求与当前加载地址匹配。
      val refillPushHit =
        refill.push.valid && refill.push.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange)
      when(refillPushHit) { // 如果有重填push命中
        whenMasked(REFILL_HITS_EARLY.asBools, refill.free)(_ := True) // 标记重填命中（如果对应槽位空闲）
      }

      // 控制阶段的重填命中：来自早期检查和实际有效的重填槽位。
      controlStage(REFILL_HITS) := controlStage(REFILL_HITS_EARLY) & refill.slots.map(_.valid).asBits()
    }

    // refillCheckLate 区域：后期重填检查。
    val refillCheckLate = !loadRefillCheckEarly generate new Area {
      import controlStage._ // 导入控制逻辑阶段的信号
      REFILL_HITS := B(
        refill.slots.map(r => r.valid && r.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange))
      ) // 重填命中
    }

    // preControlStage(LOCKED)：如果地址被锁定且未解锁，则标记为锁定状态。
    preControlStage(LOCKED) := !preControlStage(UNLOCKED) && io.lock.valid && io.lock.address(
      lineRange
    ) === preControlStage(ADDRESS_PRE_TRANSLATION)(lineRange)

    // ctrl 区域：加载流水线的控制逻辑。
    val ctrl = new Area {
      import controlStage._ // 导入控制逻辑阶段的信号

      val reservation = tagsOrStatusWriteArbitration.create(2) // 仲裁器：用于写Tag或状态
      val refillWay = CombInit(wayRandom.value) // 重填路数（随机选择）
      val refillWayNeedWriteback =
        WAYS_TAGS(refillWay).loaded && withCoherency.mux(True, STATUS(refillWay).dirty) // 重填路是否需要写回
      val refillHit = REFILL_HITS.orR // 重填命中
      val refillLoaded = (B(refill.slots.map(_.loaded)) & REFILL_HITS).orR // 重填数据是否已加载
      val lineBusy = isLineBusy(ADDRESS_PRE_TRANSLATION) // 缓存行是否忙碌
      val bankBusy = (BANK_BUSY_REMAPPED & WAYS_HITS) =/= 0 // Bank是否忙碌（与命中路数相关）
      val waysHitHazard = (WAYS_HITS & resulting(WAYS_HAZARD)).orR // 缓存路命中冒险
      val hitUnique = p.withCoherency.mux((WAYS_HITS & WAYS_TAGS.map(_.unique).asBits).orR, True) // 是否命中独占状态
      val uniqueMiss = NEED_UNIQUE && !hitUnique // 是否需要独占但未命中独占状态

      // REDO：是否需要重做。条件包括未命中、有冒险、Bank忙碌、重填命中、锁定、或需要独占但未命中独占。
      REDO := !WAYS_HIT || waysHitHazard || bankBusy || refillHit || LOCKED || uniqueMiss
      // MISS：是否缓存缺失。条件包括未命中、无冒险、无重填命中、未锁定。
      MISS := !WAYS_HIT && !waysHitHazard && !refillHit && !LOCKED
      // FAULT：是否发生错误。
      FAULT := (WAYS_HITS & WAYS_TAGS.map(_.fault).asBits).orR
      // canRefill：是否可以重填。条件包括重填槽位未满、缓存行不忙碌、赢得了仲裁、且重填路不需要写回（或写回队列不满）。
      val canRefill = !refill.full && !lineBusy && reservation.win && !(refillWayNeedWriteback && writeback.full)
      // askRefill：是否请求重填。条件包括缺失、可以重填、且没有重填命中。
      val askRefill = MISS && canRefill && !refillHit
      // askUpgrade：是否请求升级（从共享到独占）。条件包括未缺失、可以重填、且未命中独占。
      val askUpgrade = !MISS && canRefill && uniqueMiss
      // startRefill：开始重填。
      val startRefill = isValid && askRefill
      // startUpgrade：开始升级。
      val startUpgrade = isValid && askUpgrade
      val wayId = OHToUInt(WAYS_HITS) // 命中路数的ID

      when(ABORD) { // 如果地址转换失败
        REDO := False // 不重做
        MISS := False // 不缺失
        askUpgrade := False // 不请求升级
        if (enableLog) {
          report(L"Load: aborded for address ${controlStage(ADDRESS_PRE_TRANSLATION)} ${io.load.translated.format()}")
        }
      }

      when(startRefill || startUpgrade) { // 如果开始重填或升级
        reservation.takeIt() // 占用仲裁
        if (enableLog) {
          report(L"Load: reservation taken for refill/upgrade for address ${controlStage(ADDRESS_PRE_TRANSLATION)}")
        }

        refill.push.valid := True // 重填push有效
        refill.push.address := ADDRESS_POST_TRANSLATION // 重填地址
        refill.push.unique := NEED_UNIQUE // 重填是否独占
        refill.push.data := askRefill // 重填是否携带数据（仅重填时为true，升级时为false）
        if (enableLog) {
          report(
            L"Load: refill push valid for address ${controlStage(ADDRESS_PRE_TRANSLATION)}, unique ${controlStage(NEED_UNIQUE)}, data ${askRefill}"
          )
        }
      }

      when(askUpgrade) { // 如果请求升级
        refill.push.way := wayId // 重填路数设为命中路数
        refill.push.victim := 0 // 无受害者
      } otherwise { // 否则（请求重填）
        refill.push.way := refillWay // 重填路数设为随机选择的路数
        refill.push.victim := writeback.free.andMask(
          refillWayNeedWriteback && STATUS(refillWay).dirty
        ) // 受害者为需要写回且脏的写回槽位
      }

      when(startRefill) { // 如果开始重填
        status.write.valid := True // 状态写有效
        status.write.address := ADDRESS_PRE_TRANSLATION(lineRange) // 状态写地址
        status.write.data := STATUS // 状态写数据（所有路的状态）
        status.write.data(refillWay).dirty := False // 重填路的脏标志清零
        if (enableLog) { report(L"Load: status write (clear dirty) for refill way ${refillWay}") }

        waysWrite.mask(refillWay) := True // Tag写掩码
        waysWrite.address := ADDRESS_PRE_TRANSLATION(lineRange) // Tag写地址
        waysWrite.tag.loaded := False // Tag未加载（变为Invalid）
        if (enableLog) { report(L"Load: ways write (invalidate) for refill way ${refillWay}") }

        writeback.push.valid := refillWayNeedWriteback // 写回push有效
        // 写回地址：Tag地址与行地址组合
        writeback.push.address := (WAYS_TAGS(refillWay).address @@ ADDRESS_PRE_TRANSLATION(lineRange)) << lineRange.low
        writeback.push.way := refillWay // 写回路数
        if (withCoherency) { // 如果支持一致性
          writeback.push.dirty := STATUS(refillWay).dirty // 脏标志
          writeback.push.fromUnique := WAYS_TAGS(refillWay).unique // 来源独占标志
          writeback.push.toShared := False // 目标不共享
          writeback.push.release := True // 释放标志
        }
        if (enableLog) { report(L"Load: writeback push valid (refill way) for address ${writeback.push.address}") }
      }

      // REFILL_SLOT_FULL：重填槽位是否已满且有缺失。
      REFILL_SLOT_FULL := MISS && !refillHit && refill.full
      // REFILL_SLOT：实际重填槽位（如果重填命中且未加载，则使用命中槽位；否则使用空闲槽位）。
      REFILL_SLOT := REFILL_HITS.andMask(!refillLoaded) | refill.free.andMask(askRefill)
    }

    // inject 区域：加载流水线的响应注入。
    val inject = new Area {
      import rspStage._ // 导入响应阶段的信号

      io.load.rsp.valid := isValid // 响应有效性
      io.load.rsp.data := CPU_WORD // 响应数据
      io.load.rsp.fault := FAULT // 响应错误
      io.load.rsp.redo := REDO // 响应重做

      (loadRspAt - loadControlAt) match { // 根据响应延迟处理重填槽位信息。
        case 0 => { // 如果响应阶段与控制阶段相同
          io.load.rsp.refillSlotAny := REFILL_SLOT_FULL // 重填槽位任意
          io.load.rsp.refillSlot := REFILL_SLOT // 重填槽位
        }
        case 1 => { // 如果响应阶段比控制阶段晚一个周期
          io.load.rsp.refillSlotAny := REFILL_SLOT_FULL && !io.refillCompletions.orR // 重填槽位任意，且没有重填完成。
          io.load.rsp.refillSlot := REFILL_SLOT & io.refillCompletions // 重填槽位，且有重填完成。
        }
      }
      if (enableLog) { report(L"Load: rsp sent. ${io.load.rsp.format()}") }
    }

    pipeline.build() // 构建加载流水线
  }

  // store 区域：实现存储流水线。
  val store = new Area {
    val pipeline = new Pipeline {
      val stages = Array.fill(storeRspAt + 1)(newStage()) // 创建存储流水线阶段
      connect(stages)(List(M2S())) // 连接流水线阶段

      val discardAll = False // 抛弃所有请求
      for ((stage, stageId) <- stages.zipWithIndex) { // 遍历流水线阶段
        stage.throwIt(discardAll) // 抛弃流水线中的请求
      }
    }

    // 定义存储流水线中的各个关键阶段。
    val readBanksStage = pipeline.stages(storeReadBanksAt) // 读取Bank阶段
    val readTagsStage = pipeline.stages(storeReadTagsAt) // 读取Tag阶段
    val hitsStage = pipeline.stages(storeHitsAt) // 计算命中阶段
    val hitStage = pipeline.stages(storeHitAt) // 确定最终命中阶段
    val controlStage = pipeline.stages(storeControlAt) // 控制逻辑阶段
    val rspStage = pipeline.stages(storeRspAt) // 响应阶段

    val target = RegInit(False) // 世代目标：用于世代检查

    // 标记从 storeReadBanksAt + 1 到 storeControlAt 阶段的写冒险。
    waysHazard((storeReadBanksAt + 1 to storeControlAt).map(pipeline.stages(_)), ADDRESS_POST_TRANSLATION)
    // start 区域：存储流水线的起始阶段。
    val start = new Area {
      val stage = pipeline.stages.head // 第一个阶段

      import stage._ // 导入当前阶段的信号

      isValid := io.store.cmd.valid // 阶段有效性来自命令有效性
      ADDRESS_POST_TRANSLATION := io.store.cmd.address // 翻译后地址
      CPU_WORD := io.store.cmd.data // CPU字
      CPU_MASK := io.store.cmd.mask // CPU掩码
      IO := io.store.cmd.io && !io.store.cmd.flush // IO访问
      FLUSH := io.store.cmd.flush // 刷新
      FLUSH_FREE := io.store.cmd.flushFree // 刷新后释放
      PREFETCH := io.store.cmd.prefetch // 预取

      GENERATION := io.store.cmd.generation // 世代
      WAYS_HAZARD := 0 // 冒险掩码清零

      io.store.cmd.ready := True // 存储命令始终就绪
      if (withCoherency) { // 如果支持一致性
        PROBE := io.mem.probe.cmd.valid // 探测有效
        ALLOW_SHARED := io.mem.probe.cmd.allowShared // 允许共享
        ALLOW_UNIQUE := io.mem.probe.cmd.allowUnique // 允许独占
        PROBE_ID := io.mem.probe.cmd.id // 探测ID
        when(io.mem.probe.cmd.valid) { // 如果探测命令有效
          io.store.cmd.ready := False // 存储命令暂停
          isValid := True // 阶段有效
          ADDRESS_POST_TRANSLATION := io.mem.probe.cmd.address // 探测地址
          IO := False // 非IO
          FLUSH := False // 非刷新
          FLUSH_FREE := False // 非刷新后释放
          PREFETCH := False // 非预取
        }
      }
      if (enableLog) { report(L"Store: cmd received. ${io.store.cmd.format()}") }
    }

    // fetch 区域：存储流水线的取数和Tag检查阶段。
    val fetch = new Area {
      for ((way, wayId) <- ways.zipWithIndex) yield new Area { // 遍历所有路
        {
          import readTagsStage._ // 导入读取Tag阶段的信号
          way.storeRead.cmd.valid := !isStuck // Tag读命令有效
          way.storeRead.cmd.payload := ADDRESS_POST_TRANSLATION(lineRange) // Tag读命令地址
        }
        pipeline.stages(storeReadTagsAt + (!tagsReadAsync).toInt)(WAYS_TAGS)(wayId) := ways(
          wayId
        ).storeRead.rsp; // Tag数据
        {
          import hitsStage._; // 导入计算命中阶段的信号
          // 命中判断：Tag已加载且Tag地址与翻译后地址的Tag部分匹配。
          WAYS_HITS(wayId) := WAYS_TAGS(wayId).loaded && WAYS_TAGS(wayId).address === ADDRESS_POST_TRANSLATION(tagRange)
        }
      }

      {
        import hitStage._; // 导入最终命中阶段的信号
        WAYS_HIT := B(WAYS_HITS).orR // 最终命中标志
      }

      status.storeRead.cmd.valid := !readTagsStage.isStuck // 状态读命令有效
      status.storeRead.cmd.payload := readTagsStage(ADDRESS_POST_TRANSLATION)(lineRange) // 状态读命令地址
      pipeline.stages(storeReadTagsAt + (!tagsReadAsync).toInt)(STATUS) := status.storeRead.rsp // 状态数据

      val statusBypassOn =
        (storeReadTagsAt + (!tagsReadAsync).toInt until storeControlAt).map(pipeline.stages(_)) // 需要进行状态旁路的阶段
      // 状态旁路：确保读到的是最新的状态。
      statusBypassOn.foreach(stage => status.bypass(stage, ADDRESS_POST_TRANSLATION, stage == statusBypassOn.head))
    }

    // refillCheckEarly 区域：早期重填检查。
    val refillCheckEarly = storeRefillCheckEarly generate new Area {
      val stage = pipeline.stages(storeControlAt - 1) // 控制逻辑前一阶段
      import stage._ // 导入当前阶段的信号

      // 重填命中：检查是否有正在进行或即将进行的重填操作与当前存储地址匹配。
      REFILL_HITS_EARLY := B(
        refill.slots.map(r => r.valid && r.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange))
      )
      // refillPushHit：是否有新的重填请求与当前存储地址匹配。
      val refillPushHit =
        refill.push.valid && refill.push.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange)
      when(refillPushHit) { // 如果有重填push命中
        whenMasked(REFILL_HITS_EARLY.asBools, refill.free)(_ := True) // 标记重填命中（如果对应槽位空闲）
      }

      // 控制阶段的重填命中：来自早期检查和实际有效的重填槽位。
      controlStage(REFILL_HITS) := controlStage(REFILL_HITS_EARLY) & refill.slots.map(_.valid).asBits()
    }

    // refillCheckLate 区域：后期重填检查。
    val refillCheckLate = !storeRefillCheckEarly generate new Area {
      import controlStage._ // 导入控制逻辑阶段的信号
      REFILL_HITS := B(
        refill.slots.map(r => r.valid && r.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange))
      ) // 重填命中
    }

    // ctrl 区域：存储流水线的控制逻辑。
    val ctrl = new Area {
      import controlStage._ // 导入控制逻辑阶段的信号
      if (!withCoherency) PROBE := False // 如果不支持一致性，探测标志始终为False

      // GENERATION_OK：世代检查是否通过，或为预取/探测操作。
      GENERATION_OK := GENERATION === target || PREFETCH || PROBE
      if (enableLog) {
        report(
          L"Store: GENERATION ${controlStage(GENERATION)}, target ${target}, GENERATION_OK ${controlStage(GENERATION_OK)}"
        )
      }

      val reservation = tagsOrStatusWriteArbitration.create(3) // 仲裁器：用于写Tag或状态
      val replacedWay = CombInit(wayRandom.value) // 被替换路数（随机选择）
      val replacedWayNeedWriteback =
        WAYS_TAGS(replacedWay).loaded && withCoherency.mux(True, STATUS(replacedWay).dirty) // 被替换路是否需要写回
      val refillHit = (REFILL_HITS & B(refill.slots.map(_.valid))).orR // 重填命中
      val lineBusy = isLineBusy(ADDRESS_POST_TRANSLATION) // 缓存行是否忙碌
      val waysHitHazard = (WAYS_HITS & resulting(WAYS_HAZARD)).orR // 缓存路命中冒险
      val wasClean = !(B(STATUS.map(_.dirty)) & WAYS_HITS).orR // 缓存行是否干净
      val bankBusy = !FLUSH && !PREFETCH && !PROBE && (WAYS_HITS & refill.read.bankWriteNotif).orR // Bank是否忙碌
      val hitUnique = withCoherency.mux((WAYS_HITS & B(WAYS_TAGS.map(_.unique))).orR, True) // 是否命中独占状态
      val hitFault = (WAYS_HITS & B(WAYS_TAGS.map(_.fault))).orR // 是否命中错误行

      // REDO：是否需要重做。条件包括缺失、有冒险、Bank忙碌、重填命中、世代检查失败、或需要独占但未命中独占。
      REDO := MISS || waysHitHazard || bankBusy || refillHit || !GENERATION_OK || (wasClean && !reservation.win) || !hitUnique
      // MISS：是否缓存缺失。条件包括未命中、无冒险、无重填命中。
      MISS := !WAYS_HIT && !waysHitHazard && !refillHit

      // canRefill：是否可以重填。条件包括重填槽位未满、缓存行不忙碌、赢得了仲裁。
      val canRefill = !refill.full && !lineBusy && !load.ctrl.startRefill && reservation.win
      // askRefill：是否请求重填。
      val askRefill = MISS && canRefill && !refillHit && !(replacedWayNeedWriteback && writeback.full)
      // askUpgrade：是否请求升级。
      val askUpgrade = !MISS && canRefill && !hitUnique
      // startRefill：开始重填。
      val startRefill = isValid && GENERATION_OK && askRefill
      // startUpgrade：开始升级。
      val startUpgrade = isValid && GENERATION_OK && askUpgrade

      // REFILL_SLOT_FULL：重填槽位是否已满且有缺失。
      REFILL_SLOT_FULL := MISS && !refillHit && refill.full
      // REFILL_SLOT：实际重填槽位。
      REFILL_SLOT := refill.free.andMask(askRefill || askUpgrade)

      // writeCache：是否写缓存。
      val writeCache = isValid && GENERATION_OK && !REDO && !PREFETCH && !PROBE
      // setDirty：是否设置脏标志。
      val setDirty = writeCache && wasClean
      val wayId = OHToUInt(WAYS_HITS) // 命中路ID
      // bankHitId：命中Bank ID。
      val bankHitId =
        if (!reducedBankWidth) wayId
        else
          (wayId >> log2Up(bankCount / memToBankRatio)) @@ ((wayId + (ADDRESS_POST_TRANSLATION(
            log2Up(bankWidth / 8),
            log2Up(bankCount) bits
          ))).resize(log2Up(bankCount / memToBankRatio)))

      // needFlushs：需要刷新的缓存行（已加载且脏）。
      val needFlushs = B(WAYS_TAGS.map(_.loaded)) & B(STATUS.map(_.dirty))
      val needFlushOh = OHMasking.firstV2(needFlushs) // 第一个需要刷新的缓存行（One-hot）
      val needFlushSel = OHToUInt(needFlushOh) // 第一个需要刷新的缓存行ID
      val needFlush = needFlushs.orR // 是否有需要刷新的缓存行
      val canFlush =
        reservation.win && !writeback.full && !refill.slots.map(_.valid).orR && !resulting(WAYS_HAZARD).orR // 是否可以刷新
      val startFlush = isValid && FLUSH && GENERATION_OK && needFlush && canFlush // 开始刷新

      val refillWay = CombInit(replacedWay) // 重填路数
      when(askUpgrade) { // 如果请求升级
        refillWay := wayId // 重填路数设为命中路ID
      }

      when(FLUSH) { // 如果是刷新命令
        REDO := needFlush || resulting(WAYS_HAZARD).orR // 重做：如果有需要刷新或有冒险
        setDirty := False // 不设置脏标志
        writeCache := False // 不写缓存
        startRefill := False // 不开始重填
        startUpgrade := False // 不开始升级
        if (enableLog) { report(L"Store: FLUSH command. REDO ${controlStage(REDO)}") }
      }

      when(IO) { // 如果是IO访问
        REDO := False // 不重做
        MISS := False // 不缺失
        setDirty := False // 不设置脏标志
        writeCache := False // 不写缓存
        startUpgrade := False // 不开始升级
        if (enableLog) { report(L"Store: IO command. REDO ${controlStage(REDO)}") }
      }

      when(startRefill || startUpgrade || setDirty || startFlush) { // 如果开始重填/升级/设置脏/刷新
        reservation.takeIt() // 占用仲裁
        status.write.valid := True // 状态写有效
        status.write.address := ADDRESS_POST_TRANSLATION(lineRange) // 状态写地址
        status.write.data := STATUS // 状态写数据
        if (enableLog) {
          report(L"Store: reservation taken for status write. Address ${ADDRESS_POST_TRANSLATION(lineRange)}")
        }
      }

      when(startRefill || startFlush) { // 如果开始重填或刷新
        writeback.push.valid := replacedWayNeedWriteback || startFlush // 写回push有效
        writeback.push.address := (WAYS_TAGS(writeback.push.way).address @@ ADDRESS_POST_TRANSLATION(
          lineRange
        )) << lineRange.low // 写回地址
        writeback.push.way := FLUSH ? needFlushSel | refillWay // 写回路数
        if (withCoherency) { // 如果支持一致性
          writeback.push.dirty := STATUS(refillWay).dirty // 脏标志
          writeback.push.fromUnique := WAYS_TAGS(refillWay).unique // 来源独占标志
          writeback.push.toShared := False // 目标不共享
          writeback.push.release := True // 释放标志
          when(startFlush) { // 如果是刷新命令
            writeback.push.toShared := True // 目标共享
            writeback.push.dirty := STATUS(needFlushSel).dirty // 脏标志
            writeback.push.fromUnique := WAYS_TAGS(needFlushSel).unique // 来源独占标志
            status.write.data.onSel(needFlushSel)(_.dirty := False) // 清除被刷新行的脏标志
          }
        }
        if (enableLog) { report(L"Store: writeback push valid. Address ${writeback.push.address}") }
      }

      when(startRefill || startUpgrade) { // 如果开始重填或升级
        refill.push.valid := True // 重填push有效
        refill.push.address := ADDRESS_POST_TRANSLATION // 重填地址
        refill.push.way := refillWay // 重填路数
        refill.push.victim := writeback.free.andMask(
          replacedWayNeedWriteback && askRefill && STATUS(refillWay).dirty
        ) // 受害者为需要写回且脏的写回槽位
        refill.push.unique := True // 重填独占
        refill.push.data := askRefill // 重填携带数据

        waysWrite.mask(refillWay) := True // Tag写掩码
        waysWrite.address := ADDRESS_POST_TRANSLATION(lineRange) // Tag写地址
        waysWrite.tag.loaded := False // Tag未加载（变为Invalid）
        if (enableLog) { report(L"Store: refill push valid. Address ${refill.push.address}") }

        whenIndexed(status.write.data, refillWay)(_.dirty := False) // 清除重填路的脏标志
      }

      when(writeCache) { // 如果写缓存
        for ((bank, bankId) <- banks.zipWithIndex) when(WAYS_HITS(bankId)) { // 遍历所有Bank
          bank.write.valid := bankId === bankHitId // Bank写有效
          bank.write.address := ADDRESS_POST_TRANSLATION(lineRange.high downto log2Up(bankWidth / 8)) // Bank写地址
          bank.write.data.subdivideIn(cpuWordWidth bits).foreach(_ := CPU_WORD) // Bank写数据
          bank.write.mask := 0 // 掩码清零
          // 根据CPU掩码和地址设置Bank写掩码。
          bank.write.mask.subdivideIn(cpuWordWidth / 8 bits)(
            ADDRESS_POST_TRANSLATION(bankWordToCpuWordRange)
          ) := CPU_MASK
          when(bank.write.valid) {
            if (enableLog) {
              report(
                L"Store: write cache for bank ${bankId}, data ${bank.write.data}, address ${controlStage(ADDRESS_POST_TRANSLATION)} mask ${bank.write.mask}"
              )
            }
          }
        }
        if (enableLog) { report(L"Store: write cache for address ${controlStage(ADDRESS_POST_TRANSLATION)}") }
      }
      when(setDirty) { // 如果设置脏标志
        whenMasked(status.write.data, WAYS_HITS)(_.dirty := True) // 设置命中路的脏标志
        if (enableLog) { report(L"Store: set dirty for address ${controlStage(ADDRESS_POST_TRANSLATION)}") }
      }
      when(startFlush) { // 如果开始刷新
        whenMasked(status.write.data, needFlushOh)(_.dirty := False) // 清除需要刷新行的脏标志
        when(FLUSH_FREE) { // 如果刷新后释放
          whenMasked(waysWrite.mask.asBools, needFlushOh)(_ := True) // 设置Tag写掩码，使行失效
        }
        waysWrite.address := ADDRESS_POST_TRANSLATION(lineRange) // Tag写地址
        if (withCoherency) when(startFlush) { // 如果支持一致性且开始刷新
          waysWrite.mask(needFlushSel) := True // Tag写掩码
          waysWrite.tag.loaded := True // Tag加载
          waysWrite.tag.fault := B(WAYS_TAGS.map(_.fault))(needFlushSel) // Tag错误
          waysWrite.tag.unique := False // Tag非独占
          waysWrite.tag.address := WAYS_TAGS(writeback.push.way).address // Tag地址
        }
        else { // 如果不支持一致性
          waysWrite.tag.loaded := False // Tag未加载
        }
        if (enableLog) {
          report(L"Store: FLUSH start, invalidate/release for address ${controlStage(ADDRESS_POST_TRANSLATION)}")
        }
      }

      when(isValid && REDO && GENERATION_OK && !PREFETCH && !PROBE) { // 如果有效且重做且世代检查通过且非预取/探测
        target := !target // 翻转世代目标（用于LSU的同步）
        if (enableLog) { report(L"Store: REDO due to generations change") }
      }

      val snoop = withCoherency generate new Area { // 侦听（Coherency Snoop）逻辑，仅在支持一致性时生成
        val askSomething = PROBE && WAYS_HIT // 是否有探测请求且命中
        val askWriteback = !wasClean && !ALLOW_UNIQUE // 是否需要写回
        val askTagUpdate = (!ALLOW_SHARED || !ALLOW_UNIQUE && hitUnique) // 是否需要更新Tag
        val canUpdateTag = !(askSomething && askTagUpdate && !reservation.win) // 是否可以更新Tag
        val canWriteback = !(askSomething && askWriteback && (!reservation.win || writeback.full)) // 是否可以写回
        val alreadyInWb = writeback.slots // 检查是否已在写回队列中
          .map(slot => slot.valid && slot.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange))
          .orR
        val isUnique = (WAYS_TAGS.map(_.unique).asBits() & WAYS_HITS).orR // 是否是独占状态
        val locked =
          io.lock.valid && io.lock.address(refillRange) === ADDRESS_POST_TRANSLATION(refillRange) && isUnique // 是否被锁定
        val success = !waysHitHazard && canUpdateTag && canWriteback && !alreadyInWb && !locked // 操作是否成功

        val didTagUpdate = RegNext(False) init (False) // 是否更新了Tag
        io.writebackBusy setWhen (didTagUpdate) // 如果更新了Tag，则写回忙碌

        // 禁用常规存储流水线的副作用
        when(PROBE) {
          askRefill := False // 不请求重填
          askUpgrade := False // 不请求升级
        }

        when(isValid && askSomething) { // 如果有效且有探测请求
          when(askWriteback || askTagUpdate) { // 如果需要写回或更新Tag
            reservation.takeIt() // 占用仲裁
          }

          when(success) { // 如果操作成功
            when(askWriteback || askTagUpdate) { // 如果需要写回或更新Tag
              reservation.takeIt() // 占用仲裁

              waysWrite.mask(wayId) := True // Tag写掩码
              waysWrite.address := ADDRESS_POST_TRANSLATION(lineRange) // Tag写地址
              waysWrite.tag.loaded := ALLOW_SHARED || ALLOW_UNIQUE // Tag加载
              waysWrite.tag.fault := hitFault // Tag错误
              waysWrite.tag.unique := hitUnique && ALLOW_UNIQUE // Tag独占
              waysWrite.tag.address := ADDRESS_POST_TRANSLATION(tagRange) // Tag地址

              didTagUpdate := True // 标记Tag已更新
            }

            when(askWriteback) { // 如果需要写回
              writeback.push.valid := True // 写回push有效
              writeback.push.address := ADDRESS_POST_TRANSLATION // 写回地址
              writeback.push.way := wayId // 写回路数
              writeback.push.dirty := (STATUS.map(_.dirty).asBits() & WAYS_HITS).orR // 脏标志
              writeback.push.fromUnique := isUnique // 来源独占
              writeback.push.toShared := ALLOW_SHARED // 目标共享
              writeback.push.release := False // 不释放
              writeback.push.probeId := PROBE_ID // 探测ID

              status.write.valid := True // 状态写有效
              status.write.address := ADDRESS_POST_TRANSLATION(lineRange) // 状态写地址
              status.write.data := STATUS // 状态写数据
              whenMasked(status.write.data, WAYS_HITS)(_.dirty := False) // 清除命中路的脏标志
            }
          }
        }

        io.mem.probe.rsp.valid := isValid && PROBE // 探测响应有效
        io.mem.probe.rsp.toShared := WAYS_HIT && ALLOW_SHARED && !(ALLOW_UNIQUE && hitUnique) // 转换为共享
        io.mem.probe.rsp.toUnique := WAYS_HIT && ALLOW_UNIQUE && hitUnique // 转换为独占
        io.mem.probe.rsp.fromUnique := WAYS_HIT && WAYS_TAGS(refillWay).unique // 来源独占
        io.mem.probe.rsp.fromShared := WAYS_HIT && !WAYS_TAGS(refillWay).unique // 来源共享
        io.mem.probe.rsp.address := ADDRESS_POST_TRANSLATION // 地址
        io.mem.probe.rsp.id := PROBE_ID // ID
        io.mem.probe.rsp.redo := !success // 重做
        io.mem.probe.rsp.allowUnique := ALLOW_UNIQUE // 允许独占
        io.mem.probe.rsp.allowShared := ALLOW_SHARED // 允许共享
        io.mem.probe.rsp.writeback := askWriteback // 写回
        if (enableLog) {
          report(
            L"Store: probe rsp sent. Address ${io.mem.probe.rsp.address}, redo ${io.mem.probe.rsp.redo}, writeback ${io.mem.probe.rsp.writeback}"
          )
        }
      }
    }

    // inject 区域：存储流水线的响应注入。
    val inject = new Area {
      import rspStage._ // 导入响应阶段的信号

      assert(rspStage == controlStage, "Need to implement refillSlot bypass otherwise") // 断言：响应阶段必须与控制阶段相同
      io.store.rsp.valid := isValid && !PROBE // 响应有效性
      io.store.rsp.generationKo := !GENERATION_OK // 世代检查失败
      io.store.rsp.fault := False // TODO：错误标志
      io.store.rsp.redo := REDO // 重做
      io.store.rsp.refillSlotAny := REFILL_SLOT_FULL // 重填槽位任意
      io.store.rsp.refillSlot := REFILL_SLOT // 重填槽位
      io.store.rsp.flush := FLUSH // 刷新
      io.store.rsp.prefetch := PREFETCH // 预取
      io.store.rsp.address := ADDRESS_POST_TRANSLATION // 地址
      io.store.rsp.io := IO // IO
      if (enableLog) { report(L"Store: rsp sent. ${io.store.rsp.format()}") }
    }
    pipeline.build() // 构建存储流水线
  }

  // 输出重填事件和写回事件。
  io.refillEvent := refill.push.valid
  io.writebackEvent := writeback.push.valid
}
