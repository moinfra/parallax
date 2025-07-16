package parallax.components.memory

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}

/**
 * 封装一个用于测试的、提供AXI接口的4MB SRAM模型。
 *
 * 该组件内部集成了一个SRAMController和两个SRAMModelBlackbox（用于模拟32位宽的SRAM），
 * 对外仅暴露一个AXI4从接口，简化了在测试平台中的集成。
 *
 * @param baseAddress AXI总线上的基地址。
 * @param sramReadWaitCycles SRAM的读等待周期，用于模拟不同速度的SRAM。
 */
class TestOnlyAxiSRAMModel(
    baseAddress: BigInt = 0x80000000L,
    sramReadWaitCycles: Int = 1
) extends Component {

  // 1. 定义AXI总线和SRAM的配置
  // AXI配置：使用标准的32位地址和32位数据总线
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth    = 32,
    idWidth      = 4,
    useLock      = false,
    useCache     = false,
    useProt      = false,
    useQos       = false,
    useRegion    = false,
    useResp      = true,
    useStrb      = true,
    useBurst     = true,
    useLen       = true,
    useSize      = true
  )

  // SRAM配置：
  // - 4MB容量，32位数据宽度，需要 4 * 1024 * 1024 / 4 = 1,048,576个字。
  // - 寻址这么多字需要 log2(1,048,576) = 20位地址。
  val sramConfig = SRAMConfig(
    addressWidth       = 20,
    dataWidth          = 32,
    virtualBaseAddress = baseAddress,
    sizeBytes          = 4 * 1024 * 1024,
    readWaitCycles     = sramReadWaitCycles,
    enableLog          = false
  )

  // 2. 定义组件的IO接口
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
  }

  // 3. 实例化内部组件
  // a. SRAM控制器
  val sramController = new SRAMController(axiConfig, sramConfig)

  // b. 两个16位的SRAM黑盒模型，共同组成32位SRAM
  val sramModelHi = new SRAMModelBlackbox() // 高16位
  val sramModelLo = new SRAMModelBlackbox() // 低16位

  // 4. 连接AXI接口
  // 将外部AXI总线连接到SRAM控制器
  io.axi <> sramController.io.axi

  // 5. 连接SRAM控制器和两个SRAM黑盒模型
  // a. 为每个黑盒的inout数据端口创建独立的Analog信号
  val sramDataBusHi = Analog(Bits(16 bits))
  val sramDataBusLo = Analog(Bits(16 bits))

  // b. 将黑盒的inout端口连接到对应的Analog信号
  sramModelHi.io.DataIO <> sramDataBusHi
  sramModelLo.io.DataIO <> sramDataBusLo

  // c. 实现三态逻辑
  //    - 读取路径：将两个16位Analog总线拼接成32位，送给SRAM控制器的读数据端口
  sramController.io.ram.data.read := sramDataBusHi.asBits ## sramDataBusLo.asBits

  //    - 写入路径：当控制器使能写操作时，将32位写数据拆分到两个16位的Analog总线上
  when(sramController.io.ram.data.writeEnable) {
    sramDataBusHi := sramController.io.ram.data.write(31 downto 16)
    sramDataBusLo := sramController.io.ram.data.write(15 downto 0)
  }

  // d. 连接控制信号
  //    地址和主要控制信号在两个SRAM模型间共享
  sramModelHi.io.Address := sramController.io.ram.addr
  sramModelLo.io.Address := sramController.io.ram.addr

  sramModelHi.io.CE_n := sramController.io.ram.ce_n
  sramModelLo.io.CE_n := sramController.io.ram.ce_n

  sramModelHi.io.OE_n := sramController.io.ram.oe_n
  sramModelLo.io.OE_n := sramController.io.ram.oe_n

  sramModelHi.io.WE_n := sramController.io.ram.we_n
  sramModelLo.io.WE_n := sramController.io.ram.we_n

  //    字节使能信号(be_n)被正确地分配给每个16位SRAM模型的上下字节使能
  //    be_n(3) -> sramModelHi 的高字节
  //    be_n(2) -> sramModelHi 的低字节
  //    be_n(1) -> sramModelLo 的高字节
  //    be_n(0) -> sramModelLo 的低字节
  sramModelHi.io.UB_n := sramController.io.ram.be_n(3)
  sramModelHi.io.LB_n := sramController.io.ram.be_n(2)
  sramModelLo.io.UB_n := sramController.io.ram.be_n(1)
  sramModelLo.io.LB_n := sramController.io.ram.be_n(0)
}
