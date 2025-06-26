// package parallax.frontend.v2

// import spinal.core._
// import spinal.lib._
// import parallax.common.PipelineConfig
// import parallax.utilities.{Plugin, LockedImpl}
// import parallax.bpu.BpuService
// import parallax.fetch.FetchRedirectService

// trait FetchRequestService extends Service {
//   def getFetchRequestPort(): Stream[FetchRequest]
// }

// class PcGenPlugin(pCfg: PipelineConfig) extends Plugin with FetchRequestService with LockedImpl {

//   // --- 服务获取 ---
//   val setup = create early new Area {
//     val bpuService = getService[BpuService]
//     bpuService.retain()
//     val redirectService = getService[FetchRedirectService]
//     redirectService.retain()
//   }

//   // --- 硬件逻辑 ---
//   val logic = create late new Area {
//     val pcReg = Reg(UInt(pCfg.pcWidth)) init(pCfg.resetVector)
//     val bpuQueryPort = setup.bpuService.newBpuQueryPort()
//     val bpuResponse = setup.bpuService.getBpuResponse()
//     val redirect = setup.redirectService.redirectFlow

//     // 'ticker' Stream: 驱动整个PC生成循环
//     // 它的 'valid' 信号表示“本周期，我们希望生成一个新的PC”
//     val ticker = Stream(UInt(pCfg.pcWidth))
//     ticker.payload := pcReg

//     // pcSent: 对发往BPU的PC进行2周期追踪
//     // 它的输出与BPU的响应完美对齐
//     val pcSent = ticker.m2sPipe(latency = 2)
    
//     // --- BPU 查询 ---
//     bpuQueryPort.valid   := ticker.valid
//     bpuQueryPort.payload.pc := ticker.payload

//     // --- 下一个PC的计算 ---
//     val nextPc = UInt(pCfg.pcWidth)
    
//     when(bpuResponse.isTaken) {
//       nextPc := bpuResponse.target
//     } otherwise {
//       // pcSent.payload 是2个周期前的PC，所以要用它来计算顺序PC
//       nextPc := pcSent.payload + (pCfg.fetchWidth * pCfg.bytesPerInstruction)
//     }

//     // --- PC 寄存器更新 ---
//     when(pcSent.fire) { // 当一个PC预测周期完成时，更新pcReg
//       pcReg := nextPc
//     }
    
//     // --- 处理外部重定向 (最高优先级) ---
//     when(redirect.valid) {
//       pcReg := redirect.payload
//       // 当发生重定向时，必须冲刷掉流水线中所有正在处理的PC
//       // ticker.valid 需要被拉低，BPU查询也需要被无效化
//       // 这是冲刷逻辑的关键部分，我们先用一个简单的覆盖来处理
//       ticker.valid := False // 暂时停止生成
//       pcSent.valid := False // 清空追踪流水线
//       pcReg := redirect.payload // 在下一个周期使用新的PC
//     }

//     // --- 输出到FetchBuffer的请求流 ---
//     val fetchRequestPort = Stream(FetchRequest(pCfg))
//     fetchRequestPort.valid          := pcSent.valid && bpuResponse.valid
//     fetchRequestPort.payload.pc     := pcSent.payload
//     fetchRequestPort.payload.bpuResponse := bpuResponse
    
//     // --- 反压逻辑 ---
//     // 如果下游(FetchBuffer)或BPU响应没准备好，就暂停整个循环
//     pcSent.ready := fetchRequestPort.ready && bpuResponse.valid
//     ticker.ready := pcSent.ready // 反压一直传递到源头

//     // --- 服务实现 ---
//     override def getFetchRequestPort(): Stream[FetchRequest] = fetchRequestPort

//     // --- 释放服务 ---
//     setup.bpuService.release()
//     setup.redirectService.release()
//   }
// }
