// testOnly test.scala.StreamUnpackerSpec
package test.scala

import spinal.core._
import spinal.lib._
import spinal.sim._
import spinal.lib.sim._
import spinal.core.sim._

// 导入被测试的组件 (DUT)
import parallax.components.memory.StreamUnpacker

// 导入测试框架和辅助工具
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// 为测试定义一个自定义的输入数据包Bundle
// 包含一个4元素的8位数据向量和一个4位的有效掩码
case class PackedData() extends Bundle {
    val data = Vec(UInt(8 bits), 4)
    val validMask = Bits(4 bits)
}

/**
 * StreamUnpacker 组件的单元测试套件。
 * 使用了 SpinalSim 和 ScalaTest。
 */
class StreamUnpackerSpec extends AnyFunSuite {

    /**
     * 一个测试辅助函数，用于编译DUT并设置仿真环境。
     * 这样可以避免在每个测试用例中重复编写编译和初始化代码。
     * @param testCode 包含实际测试逻辑的函数。
     */
    def withTestBench(testCode: (StreamUnpacker[PackedData, UInt]) => Unit): Unit = {
        // 使用 SimConfig.withWave 编译 DUT，这会生成 VCD 波形文件用于调试
        SimConfig.withWave.compile {
            val dut = new StreamUnpacker(
                input_type      = HardType(PackedData()),
                output_type     = HardType(UInt(8 bits)),
                unpack_func     = (packed: PackedData, index) => packed.data(index), // 解包函数：根据索引从向量中选择数据
                valid_mask_getter = (packed: PackedData) => packed.validMask // 有效掩码获取函数
            )
            dut
        }.doSim { dut =>
            // 为DUT创建一个时钟域
            dut.clockDomain.forkStimulus(period = 10)
            
            // 初始化DUT的输入端口，防止出现不确定状态 'x'
            dut.io.input.valid #= false
            dut.io.output.ready #= false
            dut.io.flush #= false
            
            // 在开始测试前等待几个周期，确保所有信号稳定
            dut.clockDomain.waitSampling(5)
            
            // 执行具体的测试代码
            testCode(dut)
        }
    }

    test("Full mask - should unpack all items") {
        withTestBench { dut =>
            val collected_data = mutable.ArrayBuffer[BigInt]()
            
            // 使用 StreamMonitor 监控输出流，并在每次有效传输时收集数据
            StreamMonitor(dut.io.output, dut.clockDomain) { payload =>
                collected_data += payload.toBigInt
            }
            
            // 假设消费者总是准备好接收数据
            dut.io.output.ready #= true
            
            // 驱动一个输入数据包，其中所有项都有效 (mask = 0b1111)
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0xF
            dut.io.input.payload.data(0) #= 0xAA
            dut.io.input.payload.data(1) #= 0xBB
            dut.io.input.payload.data(2) #= 0xCC
            dut.io.input.payload.data(3) #= 0xDD
            
            // 等待DUT接受输入数据
            dut.clockDomain.waitSamplingWhere(dut.io.input.fire.toBoolean)
            dut.io.input.valid #= false

            // 等待DUT处理完整个数据包（isBusy 信号变低）
            dut.clockDomain.waitSamplingWhere(!dut.io.isBusy.toBoolean)
            
            // 再等待一个周期，确保最后一个数据被Monitor捕获
            dut.clockDomain.waitSampling(1)
            
            // 断言：检查结果是否符合预期
            assert(collected_data.length == 4, s"Expected 4 items, but got ${collected_data.length}")
            val expected_data = Seq(0xAA, 0xBB, 0xCC, 0xDD).map(BigInt(_))
            assert(collected_data.toSeq == expected_data, s"Data mismatch: Got ${collected_data}, expected ${expected_data}")
        }
    }

    test("Sparse mask - should unpack only valid items") {
        withTestBench { dut =>
            val collected_data = mutable.ArrayBuffer[BigInt]()
            StreamMonitor(dut.io.output, dut.clockDomain) { payload => collected_data += payload.toBigInt }
            dut.io.output.ready #= true
            
            // 驱动一个稀疏掩码的数据包 (mask = 0b1011)，第2项无效
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0xB // 0b1011
            dut.io.input.payload.data(0) #= 0xAA
            dut.io.input.payload.data(1) #= 0xBB
            dut.io.input.payload.data(2) #= 0xCC // 此项无效
            dut.io.input.payload.data(3) #= 0xDD
            
            dut.clockDomain.waitSamplingWhere(dut.io.input.fire.toBoolean)
            dut.io.input.valid #= false

            dut.clockDomain.waitSamplingWhere(!dut.io.isBusy.toBoolean)
            dut.clockDomain.waitSampling(1)
            
            assert(collected_data.length == 3, s"Expected 3 valid items, but got ${collected_data.length}")
            val expected_data = Seq(0xAA, 0xBB, 0xDD).map(BigInt(_))
            assert(collected_data.toSeq == expected_data, s"Data mismatch: Got ${collected_data}, expected ${expected_data}")
        }
    }

    test("Empty mask - should unpack no items") {
        withTestBench { dut =>
            val collected_data = mutable.ArrayBuffer[BigInt]()
            StreamMonitor(dut.io.output, dut.clockDomain) { payload => collected_data += payload.toBigInt }
            dut.io.output.ready #= true
            
            assert(dut.io.input.ready.toBoolean, "DUT should be ready initially")
            
            // 驱动一个空掩码的数据包 (mask = 0b0000)
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0x0
            
            dut.clockDomain.waitSamplingWhere(!dut.io.input.ready.toBoolean)
            dut.io.input.valid #= false

            assert(dut.io.isBusy.toBoolean, "DUT should be busy after accepting a packet")
            
            // DUT应该在4个周期后处理完（因为内部索引会遍历所有4个位置）
            dut.clockDomain.waitSamplingWhere(!dut.io.isBusy.toBoolean)
            dut.clockDomain.waitSampling(1)
            
            assert(collected_data.isEmpty, s"Expected 0 items, but got ${collected_data.length}")
            assert(dut.io.input.ready.toBoolean, "DUT should be ready again after processing empty packet")
        }
    }

    test("Back-pressure from consumer should stall the unpacker") {
        withTestBench { dut =>
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0xF
            (0 until 4).foreach(i => dut.io.input.payload.data(i) #= 10 + i)
            
            dut.clockDomain.waitSamplingWhere(dut.io.input.fire.toBoolean)
            dut.io.input.valid #= false
            
            // 消费者准备好接收第一个数据
            dut.io.output.ready #= true
            dut.clockDomain.waitSamplingWhere(dut.io.output.valid.toBoolean)
            
            assert(dut.io.output.payload.toInt == 10)
            assert(dut.unpackIndex.toInt == 0)
            
            // 施加反压
            println("Applying back-pressure")
            dut.io.output.ready #= false
            dut.clockDomain.waitSampling(5)
            
            // 检查DUT状态是否被冻结
            assert(dut.io.output.valid.toBoolean, "Output should remain valid during stall")
            assert(dut.io.output.payload.toInt == 10, "Payload should be stable during stall")
            assert(dut.unpackIndex.toInt == 0, "Unpack index should not advance during stall")

            // 释放反压
            println("Releasing back-pressure")
            dut.io.output.ready #= true
            dut.clockDomain.waitSampling(1)
            
            // 检查DUT是否前进到下一项
            assert(dut.unpackIndex.toInt == 1, "Unpack index should advance after stall")
            assert(dut.io.output.valid.toBoolean, "Next item should be valid")
            assert(dut.io.output.payload.toInt == 11, "Payload should be the next item")
            
            // 让其处理完毕
            dut.clockDomain.waitSamplingWhere(!dut.io.isBusy.toBoolean)
        }
    }

    test("Flush signal should interrupt unpacking and reset state") {
        withTestBench { dut =>
            val collected_data = mutable.ArrayBuffer[BigInt]()
            StreamMonitor(dut.io.output, dut.clockDomain) { payload => collected_data += payload.toBigInt }
            dut.io.output.ready #= true

            // 驱动一个全有效的数据包
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0xF
            (0 until 4).foreach(i => dut.io.input.payload.data(i) #= 10 + i)

            dut.clockDomain.waitSamplingWhere(dut.io.input.fire.toBoolean)
            dut.io.input.valid #= false

            // 等待至少一个数据项被解包
            dut.clockDomain.waitSamplingWhere(dut.io.output.fire.toBoolean)
            dut.clockDomain.waitSampling(1) // 等待解包第二个数据项
            assert(collected_data.length == 2)
            assert(dut.io.isBusy.toBoolean)
            
            // 发送 flush 信号
            println("Asserting flush")
            dut.io.flush #= true
            dut.clockDomain.waitSampling(1)
            dut.io.flush #= false
            
            // 检查 flush 后的状态
            assert(!dut.io.isBusy.toBoolean, "isBusy should be false after flush")
            assert(dut.io.input.ready.toBoolean, "input.ready should be true after flush")
            assert(!dut.io.output.valid.toBoolean, "output.valid should be false after flush")
            
            dut.clockDomain.waitSampling(5)
            
            // 确认只有 flush 前的数据被收集
            assert(collected_data.length == 2, "Only two items should be collected before flush")
            
            // 驱动一个新数据包，验证DUT是否能正常恢复工作
            println("Driving a new packet post-flush")
            dut.io.input.valid #= true
            dut.io.input.payload.validMask #= 0xC // 0b1100
            (0 until 4).foreach(i => dut.io.input.payload.data(i) #= 20 + i)
            
            dut.clockDomain.waitSamplingWhere(dut.io.input.fire.toBoolean)
            dut.io.input.valid #= false

            dut.clockDomain.waitSamplingWhere(!dut.io.isBusy.toBoolean)
            dut.clockDomain.waitSampling(1)
            
            // 检查最终收集到的数据
            val expected_data = Seq(10, 11, 22, 23).map(BigInt(_))
            assert(collected_data.toSeq == expected_data, s"Final data mismatch: Got ${collected_data}, expected ${expected_data}")
        }
    }
    
    test("Continuous stream of packets") {
        withTestBench { dut =>
            val collected_data = mutable.ArrayBuffer[BigInt]()
            val packet_queue = mutable.Queue[PackedData => Unit]()

            // 定义两个数据包的生成逻辑
            packet_queue.enqueue { p =>
                p.validMask #= 0xF // 0b1111
                (0 until 4).foreach(i => p.data(i) #= 0x11 + i)
            }
            packet_queue.enqueue { p =>
                p.validMask #= 0x5 // 0b0101
                (0 until 4).foreach(i => p.data(i) #= 0x55 + i)
            }

            // 使用 StreamDriver 从队列中驱动输入流
            StreamDriver(dut.io.input, dut.clockDomain) { payload =>
                if (packet_queue.nonEmpty) {
                    packet_queue.dequeue().apply(payload)
                    true
                } else {
                    false
                }
            }

            StreamMonitor(dut.io.output, dut.clockDomain) { payload =>
                collected_data += payload.toBigInt
            }
            
            dut.io.output.ready #= true
            
            // 等待足够长的时间让所有数据包被处理
            dut.clockDomain.waitSampling(40)
            
            val expected_data = Seq(0x11, 0x12, 0x13, 0x14, 0x55, 0x57).map(BigInt(_))
            assert(collected_data.toSeq == expected_data, s"Data mismatch: Got ${collected_data}, expected ${expected_data}")
        }
    }
}
