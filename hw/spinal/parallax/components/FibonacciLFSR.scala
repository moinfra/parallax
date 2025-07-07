package parallax.components
import spinal.core._
import spinal.lib._

/**
 * Fibonacci 线性反馈移位寄存器 (LFSR) 组件 配置参数
 *
 * @param width 生成的伪随机数的位宽 (必须 > 0)
 * @param seed  LFSR 的初始状态 (种子值，不能为 0，且必须能在 width 位内表示)
 * @param tap1  第一个抽头位置 (从 0 到 width-1)。
 * @param tap2  第二个抽头位置 (从 0 到 width-1)。
 * 注意：抽头位置的选择决定了序列的长度和统计特性。为了获得最大长度序列
 * (2^width - 1)，需要选择对应本原多项式的抽头。请查阅相关资料。
 */
case class FibonacciLFSRConfig(
    width: BitCount,
    seed: BigInt,
    tap1: Int,
    tap2: Int
) {
    // --- 参数检查 ---
    require(width.value > 0, "Width must be positive")
    require(seed != 0, "LFSR seed cannot be zero (会产生全零序列)")
    require(seed.bitLength <= width.value, s"Seed $seed (bitLength=${seed.bitLength}) does not fit in $width")
    require(tap1 >= 0 && tap1 < width.value, s"Tap1 ($tap1) is out of bounds [0, ${width.value-1}]")
    require(tap2 >= 0 && tap2 < width.value, s"Tap2 ($tap2) is out of bounds [0, ${width.value-1}]")
    require(tap1 != tap2, "Tap positions must be different for this simple 2-tap example")
    // 如果有更多抽头，需要添加相应的检查
}


/**
 * Fibonacci 线性反馈移位寄存器 (LFSR) 组件
 *
 * @param config 配置参数对象
 */
class FibonacciLFSR(val config: FibonacciLFSRConfig) extends Component {

    // --- 输入/输出接口 ---
    val io = new Bundle {
        // 使能信号，为高时 LFSR 在时钟上升沿更新状态
        val enable = in Bool()
        // 输出当前的 LFSR 状态值 (伪随机数)
        val value  = out Bits(config.width)
    }

    // --- 内部状态寄存器 ---
    // 使用 Reg 来存储 LFSR 的当前状态，并用 B(seed, width) 来初始化
    val shiftReg = Reg(Bits(config.width)) init(B(config.seed, config.width))

    // --- 核心逻辑 ---
    when(io.enable) {
        // 1. 计算反馈位 (Feedback Bit)
        //    Fibonacci LFSR：将指定抽头位置的值进行异或 (XOR)
        val feedback = shiftReg(config.tap1) ^ shiftReg(config.tap2)
        // 若有更多抽头: val feedback = shiftReg(config.tap1) ^ shiftReg(config.tap2) ^ shiftReg(config.tap3) ^ ...

        // 2. 移位和更新
        //    将寄存器内容右移一位，并将计算出的 feedback 位插入到最高位 (MSB)
        shiftReg := feedback ## shiftReg(config.width.value - 1 downto 1)
    }

    // --- 输出赋值 ---
    // 将当前寄存器的状态连接到输出端口
    io.value := shiftReg
}

// --- Companion Object for easy instantiation with default taps ---
object FibonacciLFSR {
    /**
     * 创建 FibonacciLFSR 实例，如果未指定抽头，则使用默认值。
     *
     * @param width 生成的伪随机数的位宽
     * @param seed  LFSR 的初始种子
     * @param tap1Opt 第一个抽头位置 (可选, 默认为 width - 1)
     * @param tap2Opt 第二个抽头位置 (可选, 默认为 width / 2 - 这个选择仅为示例，不保证最优)
     * @return FibonacciLFSR 组件实例
     */
    def apply(
        width: BitCount,
        seed: BigInt,
        tap1Opt: Option[Int] = None,
        tap2Opt: Option[Int] = None
    ): FibonacciLFSR = {
        // 如果用户没有提供 tap1 或 tap2，则计算默认值
        // !! 重要提示: 默认抽头 (width-1, width/2) 可能不是最优的，请根据实际应用选择合适的抽头 !!
        val finalTap1 = tap1Opt.getOrElse(width.value - 1)
        val finalTap2 = tap2Opt.getOrElse(width.value / 2)

        // 创建配置对象
        val config = FibonacciLFSRConfig(
            width = width,
            seed = seed,
            tap1 = finalTap1,
            tap2 = finalTap2
        )
        // 使用配置对象创建组件实例
        new FibonacciLFSR(config)
    }

    // (可选) 提供一个直接接受 Config 对象的 apply 方法，虽然不常用，但保持完整性
    def apply(config: FibonacciLFSRConfig): FibonacciLFSR = new FibonacciLFSR(config)
}
