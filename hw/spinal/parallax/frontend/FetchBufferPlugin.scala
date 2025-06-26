// package parallax.frontend.v2

// import spinal.core._
// import spinal.lib._
// import parallax.common._
// import parallax.utilities.{Plugin, LockedImpl}
// import parallax.components.memory.InstructionFetchUnit
// import parallax.components.lsu.IBusService

// trait FetchService extends Service {
//   def getFetchPacketStream(): Stream[FetchPacket]
// }

// class FetchBufferPlugin(pCfg: PipelineConfig, ifqDepth: Int = 8) extends Plugin with FetchService with LockedImpl {

//   // --- 服务获取 ---
//   val setup = create early new Area {
//     val requestService = getService[FetchRequestService]
//     val iBusProvider = getService[IBusService]

//     // IFU 实例化
//     val ifuConfig = InstructionFetchUnitConfig(/* ... */ axiConfig = iBusProvider.iBusConfig)
//     val ifu = new InstructionFetchUnit(ifuConfig)
//     ifu.io.memBus <> iBusProvider.getIBus()
//   }

//   // --- 硬件逻辑 ---
//   val logic = create late new Area {
//     val fetchRequest = setup.requestService.getFetchRequestPort()

//     // IFQ: 核心的指令取指队列
//     val ifq = new StreamFifo(FetchPacket(pCfg), ifqDepth)

//     // --- 连接 IFU 和请求流 ---
//     // 这个简化版假设请求和响应一一对应，可以用一个简单的流水线连接
//     val fetchRequestPiped = fetchRequest.pipelined(m2s=true, s2m=true)

//     setup.ifu.io.pcIn.valid   := fetchRequestPiped.valid
//     setup.ifu.io.pcIn.payload := fetchRequestPiped.payload.pc
//     fetchRequestPiped.ready   := setup.ifu.io.pcIn.ready
    
//     val dataOut = setup.ifu.io.dataOut

//     // --- 组装完整的 FetchPacket 并推入IFQ ---
//     ifq.io.push.valid := dataOut.valid
//     dataOut.ready := ifq.io.push.ready

//     // 当IFU数据返回时，我们需要从请求流中拿到匹配的bpuResponse
//     // 在这个简化顺序模型中，我们可以假设请求和响应是同步的。
//     // 注意：这在真实乱序场景下是错误的！
//     ifq.io.push.payload.pc           := dataOut.payload.pc
//     ifq.io.push.payload.instructions := dataOut.payload.instructions
//     ifq.io.push.payload.fault        := dataOut.payload.fault
//     ifq.io.push.payload.bpuResponse  := fetchRequestPiped.payload.bpuResponse

//     // --- 服务实现 ---
//     override def getFetchPacketStream(): Stream[FetchPacket] = ifq.io.pop
//   }
// }
