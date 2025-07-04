package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rename._
import parallax.utilities.ParallaxSim

case class RenameUnitIo(
    pipelineConfig: PipelineConfig,
    ratConfig: RenameMapTableConfig,
    freeListConfig: SuperScalarFreeListConfig
) extends Bundle
    with IMasterSlave {
  val decodedUopsIn = Stream(Vec(HardType(DecodedUop(pipelineConfig)), pipelineConfig.renameWidth))
  val numFreePhysRegs = UInt(log2Up(freeListConfig.numPhysRegs + 1) bits)
  val flushIn = Bool()
  val renamedUopsOut = Stream(Vec(HardType(RenamedUop(pipelineConfig)), pipelineConfig.renameWidth))
  val ratReadPorts = Vec((RatReadPort(ratConfig)), ratConfig.numReadPorts)
  val ratWritePorts = Vec((RatWritePort(ratConfig)), ratConfig.numWritePorts)


  val flAllocate = Vec(SuperScalarFreeListAllocatePort(freeListConfig), freeListConfig.numAllocatePorts)

  override def asMaster(): Unit = {
    // RenameUnit 作为 Master，驱动 decodedUopsIn.ready, rat/fl 请求, flushIn.ready (如果flush是Stream)
    // 并接收 decodedUopsIn.valid/payload, numFreePhysRegs, renamedUopsOut.ready, rat/fl 响应
    master(decodedUopsIn)
    out(numFreePhysRegs)
    out(flushIn)

    slave(renamedUopsOut) // 它产生这个Stream

    ratReadPorts.foreach(slave(_))
    ratWritePorts.foreach(slave(_))
    flAllocate.foreach(slave(_))
  }
}

// 注意 allocatesPhysDest 必须真实反映情况，例如如果 rd 是 0 则不应分配寄存器，所以 allocatesPhysDest 要设置为 false. 
// RU假设解码阶段会做这个保证。
class RenameUnit(
    val pipelineConfig: PipelineConfig,
    val ratConfig: RenameMapTableConfig,
    val freeListConfig: SuperScalarFreeListConfig
) extends Component {

  val io = slave(RenameUnitIo(pipelineConfig, ratConfig, freeListConfig))

  // --- 1. 分析每个Uop的需求 (组合逻辑) ---
  val uopIsValidAndDecoded = Vec(Bool(), pipelineConfig.renameWidth)
  val uopNeedsNewPhysDest = Vec(Bool(), pipelineConfig.renameWidth) // 指示此uop是否需要分配新的物理目标

  for (i <- 0 until pipelineConfig.renameWidth) {
    val uop = io.decodedUopsIn.payload(i) // 使用 .payload 因为是 Stream
    uopIsValidAndDecoded(i) := uop.isValid // 假设解码阶段已确保isValid的准确性
    when(!uop.isValid) {
      report(L"RenameUnit: Slot ${i.toString()} uop input is invalid.")
    }
    // 条件：uop有效，解码结果表明它写架构目标 (writeArchDestEn应包含对r0的检查)
    // 并且它是GPR或FPR类型（假设都需要重命名）
    // 真实场景需要更精细的判断，例如STORE指令的数据源寄存器不应在此分配新物理寄存器。
    // 假设 DecodedUop.writeArchDestEn 已经足够精确，表明这是一个会产生结果并写回的指令。
    uopNeedsNewPhysDest(i) :=
      uopIsValidAndDecoded(
        i
      ) && uop.writeArchDestEn && (uop.archDest.isGPR || uop.archDest.isFPR)
    
      when(!uopNeedsNewPhysDest(i)) {
        when(!uopIsValidAndDecoded(i)) {
        ParallaxSim.debug(L"RenameUnit: Slot ${i.toString()} uop does not need new phys dest because it is invalid.")
        } elsewhen(!uop.writeArchDestEn) {
        ParallaxSim.debug(L"RenameUnit: Slot ${i.toString()} uop does not need new phys dest because writeArchDestEn is false.")
        } elsewhen(!(uop.archDest.isGPR || uop.archDest.isFPR)) {
        ParallaxSim.debug(L"RenameUnit: Slot ${i.toString()} uop does not need new phys dest because it is not GPR or FPR.")
        }
      }
  }

  // --- 2. 计算本周期总共需要分配的物理寄存器数量 ---
  val numPhysRegsToAllocateThisCycle = CountOne(uopNeedsNewPhysDest)
  // 确保位宽足够比较 (log2Up(0)会有问题，但renameWidth>=1保证numPhysRegsToAllocateThisCycle的计数器至少1位)
  val numPhysRegsToAllocateResized = numPhysRegsToAllocateThisCycle.resize(log2Up(pipelineConfig.renameWidth + 1))

  // --- 3. Stall 条件判断 ---
  val notEnoughPhysRegs = io.numFreePhysRegs < numPhysRegsToAllocateResized

  // stallLackingResources: 当有有效输入，但物理寄存器不足时，RenameUnit内部需要stall
  val stallLackingResources = notEnoughPhysRegs && io.decodedUopsIn.valid
  when(io.decodedUopsIn.valid && notEnoughPhysRegs) {
    ParallaxSim.warning(L"RenameUnit: Not enough free physical registers to allocate ${numPhysRegsToAllocateResized} physical registers.")
  }
  // --- 4. 初始化所有IO端口驱动的默认值 ---
  // RAT读端口：默认不指定读取哪个架构寄存器 (或指定一个安全默认值)
  io.ratReadPorts.foreach(_.archReg.assignDontCare()) // 或者 := U(0)

  // FreeList分配端口：默认不使能
  io.flAllocate.foreach(_.enable := False)

  // RAT写端口：默认不使能写
  io.ratWritePorts.foreach { wp =>
    wp.wen := False
    wp.archReg.assignDontCare()
    wp.physReg.assignDontCare()
  }
  // 对于上面assignDontCare()的wen，如果RAT组件内部依赖wen为明确的0/1，则应设为False。
  // 假设RAT组件在wen=False时不关心其他输入。更安全的做法是 io.ratWritePorts.foreach(_.wen := False)

  // --- 5. 并行处理每个Uop (组合逻辑，结果在 io.renamedUopsOut.valid 为高时输出) ---
  // 这些信号将在一个周期内计算完成。
  val calculatedRenamedUops = Vec.tabulate(pipelineConfig.renameWidth) { slotIdx =>
    val decodedUop = io.decodedUopsIn.payload(slotIdx)
    report(L"RenameUnit: Slot ${slotIdx.toString()} processing uop:")

    ParallaxSim.dump(decodedUop.tinyDump())

    val renamedUop = RenamedUop(pipelineConfig).setDefault(
      decoded = decodedUop  // 复制解码信息
    ) // 初始化为默认值
    renamedUop.uniqueId.assignDontCare() // TODO: uniqueId 生成
    renamedUop.robPtr.assignDontCare() // 由Dispatch阶段填充

    // 只在 RenameUnit 不 stall、输入流有效、且当前 uop 有效时，才进行实际的重命名操作
    val proceedWithUop =
      io.decodedUopsIn.valid && !stallLackingResources && !io.flushIn && uopIsValidAndDecoded(slotIdx)

    when(proceedWithUop) {
      // --- 5a. 读取源操作数的物理映射 ---
      // 假设 RenamePlugin 的 setup 中已用 require 保证端口数量足够
      // 每个uop slotIdx 使用固定的端口范围来避免运行时索引计算的复杂性
      // 例如：slot 0 用读端口 0,1,2; slot 1 用读端口 3,4,5 等。
      // 这要求 ratConfig.numReadPorts >= pipelineConfig.renameWidth * 3 (假设最多2源+1旧目标)
      val baseReadPortIdx = slotIdx * 3 // 每个uop的读端口基址
      val physSrc1Port = io.ratReadPorts(baseReadPortIdx)
      val physSrc2Port = io.ratReadPorts(baseReadPortIdx + 1)
      val oldDestReadPort = io.ratReadPorts(baseReadPortIdx + 2)

      when(decodedUop.useArchSrc1) {
        physSrc1Port.archReg := decodedUop.archSrc1.idx
        renamedUop.rename.physSrc1.idx := physSrc1Port.physReg
        renamedUop.rename.physSrc1IsFpr := decodedUop.archSrc1.isFPR
      }
      when(decodedUop.useArchSrc2) {
        physSrc2Port.archReg := decodedUop.archSrc2.idx
        renamedUop.rename.physSrc2.idx := physSrc2Port.physReg
        renamedUop.rename.physSrc2IsFpr := decodedUop.archSrc2.isFPR
      }
      // TODO: 处理 archSrc3 暂时没必要，反而扩大硬件面积

      // --- 5b. 分配新物理目标并读取旧物理目标 (如果需要) ---
      when(uopNeedsNewPhysDest(slotIdx)) {
        renamedUop.rename.writesToPhysReg := True // 标记会写真实物理目标

        // 读取旧的物理目标 (在写新映射之前)
        oldDestReadPort.archReg := decodedUop.archDest.idx
        renamedUop.rename.oldPhysDest.idx := oldDestReadPort.physReg
        renamedUop.rename.oldPhysDestIsFpr := decodedUop.archDest.isFPR

        // 从FreeList分配新的物理目标
        // 假设 freeListConfig.numAllocatePorts >= pipelineConfig.renameWidth
        // 假设 ratConfig.numWritePorts >= pipelineConfig.renameWidth
        // 直接使用 slotIdx 作为物理端口索引
        val flPort = io.flAllocate(slotIdx)
        flPort.enable := True // 在 proceedWithUop 条件下发出分配请求
        ParallaxSim.debug(L"RenameUnit: Slot ${slotIdx.toString()} FreeList allocation request.")
        // FreeList的响应 (flPort.success, flPort.physReg) 是组合可见的
        when(flPort.success) {
          ParallaxSim.success(L"RenameUnit: Slot ${slotIdx.toString()} allocated new physical register ${flPort.physReg}.")
          renamedUop.rename.allocatesPhysDest := True
          renamedUop.rename.physDest.idx := flPort.physReg
          renamedUop.rename.physDestIsFpr := decodedUop.archDest.isFPR

          // 更新RAT (发出写请求，将在下一个周期生效)
          val ratWp = io.ratWritePorts(slotIdx)
          ratWp.wen := True // 只有成功分配了才写RAT
          ratWp.archReg := decodedUop.archDest.idx
          ratWp.physReg := flPort.physReg
        } otherwise {
          // 如果 flPort.enable 为真但分配失败 (理论上已被 stallLackingResources 避免)
          // 这表示一个更严重的问题或 FreeList 的意外行为
          ParallaxSim.error(L"RenameUnit: Slot ${slotIdx.toString()} FreeList allocation failed unexpectedly when enable was high. flPort.physReg=${flPort.physReg}")
          renamedUop.decoded.isValid := False // 将此uop标记为无效以阻止后续处理
          renamedUop.rename.allocatesPhysDest := False
          renamedUop.rename.writesToPhysReg := False
          // RAT写端口的 wen 不会置位，因为 flPort.success 为假
        }
      }
    } otherwise { // if not proceedWithUop (i.e., stall, or flush, or uop invalid)
      // renamedUop 保持其 setDefault() 的值，特别是 decoded.isValid 为 False
      // 确保不向RAT/FL发出意外的使能信号 (已由外部默认值处理)
      // 如果uop本身就无效 (uopIsValidAndDecoded(slotIdx)为假)，则renamedUop.decoded.isValid也应为假
      when(!uopIsValidAndDecoded(slotIdx)) {
        ParallaxSim.warning(L"RenameUnit: Slot ${slotIdx.toString()} uop is invalid.")
        renamedUop.decoded.isValid := False
      } otherwise {
        when(!io.decodedUopsIn.valid){
          ParallaxSim.debug(L"RenameUnit: Slot ${slotIdx.toString()} stall or flush or invalid, not processing uop because io.decodedUopsIn.valid is false.")
        }
        when(stallLackingResources){
          ParallaxSim.debug(L"RenameUnit: Slot ${slotIdx.toString()} stall or flush or invalid, not processing uop because stallLackingResources is true.")
        }
         when(!io.flushIn){
          ParallaxSim.debug(L"RenameUnit: Slot ${slotIdx.toString()} stall or flush or invalid, not processing uop because io.flushIn is true.")
        }
      }
    }
    renamedUop
  }
  
  // --- 6. 输出流控制 ---
  val overallOutputValid = io.decodedUopsIn.valid && !stallLackingResources && !io.flushIn
  io.renamedUopsOut.valid := overallOutputValid
  io.renamedUopsOut.payload := calculatedRenamedUops
  io.decodedUopsIn.ready := !stallLackingResources && !io.flushIn

  // --- 7. Flush Logic ---
  // 当 io.flushIn 为高时，overallOutputValid 会因为 !io.flushIn 而变为 False。
  // 这将导致 io.renamedUopsOut.valid 为 False。
  // 并且，所有条件驱动的端口使能（flAllocate.enable, ratWritePorts.wen）也会因为
  // proceedWithUop 条件中的 !io.flushIn 而变为 False。
  // 所以，不需要在 when(io.flushIn) 块中再次显式取消端口使能，它们已经被全局条件控制了。
  // 如果 RenameUnit 有内部状态寄存器（我们这里没有），则需要在 flush 时复位它们。
}
