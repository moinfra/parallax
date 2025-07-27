package parallax.fetch2

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.fetch.InstructionPredecoder
import parallax.fetch.icache.{ICacheCmd, ICacheRsp, ICacheConfig}
import parallax.utilities.ParallaxSim.success
import parallax.utilities.ParallaxSim.debug
import parallax.utilities.ParallaxSim.notice
import parallax.utilities.Formattable

// 从S1传来的请求负载
case class FetchRequestPayload(pCfg: PipelineConfig) extends Bundle with Formattable {
  val pc = UInt(pCfg.pcWidth) // 行对齐地址
  val rawPc = UInt(pCfg.pcWidth) // 原始字节地址

  override def format: Seq[Any] = Seq(
    L"pc=${pc}, ",
    L"rawPc=${rawPc}"
  )
}

// IFT中每个槽位的数据结构
case class IftSlot(pCfg: PipelineConfig, iCfg: ICacheConfig) extends Bundle {
  val valid = Bool()
  val pc = UInt(pCfg.pcWidth)
  val rawPc = UInt(pCfg.pcWidth)
  val tid = UInt(8 bits) // 假设TID为8位
  val done = Bool()
  val rspData = Vec(Bits(32 bits), iCfg.lineWords)

  def setDefault(): this.type = {
    valid := False
    pc.assignDontCare()
    rawPc.assignDontCare()
    tid.assignDontCare()
    done := False
    rspData.assignDontCare()
    this
  }

  def setInit(pc: UInt, rawPc: UInt, tid: UInt): this.type = {
    valid := True
    this.pc := pc
    this.rawPc := rawPc
    this.tid := tid
    done := False
    rspData.assignDontCare()
    this
  }
}

class FetchSequencer(pCfg: PipelineConfig, iCfg: ICacheConfig) extends Component {

  val io = new Bundle {
    // 来自S1 (或上游)
    val newRequest = slave(Stream(FetchRequestPayload(pCfg)))

    // 与ICache交互
    val iCacheCmd = master(Flow(ICacheCmd(pCfg.pcWidth.value)))
    val iCacheRsp = slave(Flow(ICacheRsp(iCfg)))

    // 发送给Dispatcher
    val dispatchGroupOut = master(Stream(FetchGroup(pCfg)))

    // 来自更高层级的硬冲刷
    val flush = in(Bool())
  }

  val cycle = Reg(UInt(32 bits)) init (0)
  cycle := cycle + 1

  // --- 内部状态和数据结构 ---
  val slots = Vec(RegInit((IftSlot(pCfg, iCfg).setDefault())), 2)
  val oldIndex = Reg(UInt(1 bit)) init (0)
  val nextTid = Reg(UInt(8 bit)) init (0)

  // --- 初始化和硬冲刷逻辑 ---
  when(io.flush) {
    slots.foreach(_.setDefault())
    oldIndex := 0

    debug(L"hard flush")
  }

  // --- 组合逻辑 Helpers ---
  val isFull = slots(0).valid && slots(1).valid
  val isEmpty = !slots(0).valid && !slots(1).valid
  val newIndex = oldIndex + 1

  val olderSlot = slots(oldIndex)
  val newerSlot = slots(newIndex)

  def is_pending(idx: UInt) = slots(idx).valid && !slots(idx).done
  def is_done(idx: UInt) = slots(idx).valid && slots(idx).done

  io.newRequest.ready := !isFull

  val icacheCmdLogic = new Area {
    io.iCacheCmd.valid := False
    io.iCacheCmd.payload.assignDontCare()

    when(is_pending(oldIndex)) {
      io.iCacheCmd.valid := True
      io.iCacheCmd.payload.address := olderSlot.pc
      io.iCacheCmd.payload.transactionId := olderSlot.tid
    } elsewhen (is_pending(newIndex)) {
      io.iCacheCmd.valid := True
      io.iCacheCmd.payload.address := newerSlot.pc
      io.iCacheCmd.payload.transactionId := newerSlot.tid
    } elsewhen (io.newRequest.fire) {
      io.iCacheCmd.valid := True
      io.iCacheCmd.payload.address := io.newRequest.payload.pc
      io.iCacheCmd.payload.transactionId := nextTid
    }
  }

  // 3. 驱动 dispatchGroupOut
  io.dispatchGroupOut.valid := is_done(oldIndex) && !io.flush
  io.dispatchGroupOut.payload.assignDontCare()
  when(io.dispatchGroupOut.valid) {
    val payload = io.dispatchGroupOut.payload
    val lineBytes = iCfg.bytesPerLine

    payload.pc := olderSlot.pc
    payload.firstPc := olderSlot.rawPc
    payload.instructions := olderSlot.rspData
    payload.fault := False

    val pcOffset = olderSlot.rawPc - olderSlot.pc
    val byteOffsetInLine = pcOffset(log2Up(lineBytes) - 1 downto 0)
    val wordOffsetInLine = byteOffsetInLine >> 2 // Assuming 32-bit instructions (4 bytes)

    payload.startInstructionIndex := wordOffsetInLine.resized
    payload.numValidInstructions := U(iCfg.lineWords) - payload.startInstructionIndex

    val predecodedInfos = Vec.tabulate(iCfg.lineWords) { i =>
      val predecoder = new InstructionPredecoder(pCfg)
      predecoder.io.instruction := olderSlot.rspData(i)
      predecoder.io.predecodeInfo
    }
    payload.predecodeInfos := predecodedInfos
    payload.branchMask := B(predecodedInfos.map(_.isBranch).reverse)

    payload.potentialJumpTargets := Vec.tabulate(iCfg.lineWords) { i =>
      val instruction = olderSlot.rspData(i)
      val offs26 = instruction(25 downto 0)
      val operandA = olderSlot.pc.asSInt
      val operandB = S(i << 2, pCfg.pcWidth)
      val operandC = (offs26.asSInt << 2).resize(pCfg.pcWidth)
      val result = (operandA + operandB + operandC).asUInt.resized
      report(L"jump target for instruction ${i} is ${result} because a=${operandA}, b=${operandB}, c=${operandC}")
      result
    
    }

    when(io.dispatchGroupOut.fire) {
      success(L"group @${io.dispatchGroupOut.payload.pc} sent to dispatcher. data: ${io.dispatchGroupOut.payload.format}")
    }
  }

  // --- 核心状态更新逻辑 (at clock edge) ---

  // TID计数器只在分配新事务时更新
  when(io.newRequest.fire) {
    nextTid := nextTid + 1
    val freeSlotIndex = Mux(!slots(0).valid, U(0), U(1))
    slots(freeSlotIndex).setInit(io.newRequest.payload.pc, io.newRequest.payload.rawPc, nextTid)
    notice(L"Got new request: ${io.newRequest.format}")

    // 如果IFT从空变为非空，新分配的slot成为队首
    when(isEmpty) {
      oldIndex := freeSlotIndex
    }
  }

  // ICache响应只在非redo时更新IFT
  when(io.iCacheRsp.valid && !io.iCacheRsp.payload.redo && io.iCacheRsp.payload.wasHit) {
    for (i <- 0 until 2) {
      when(!slots(i).done && slots(i).valid && slots(i).tid === io.iCacheRsp.payload.transactionId) {
        slots(i).done := True
        slots(i).rspData := io.iCacheRsp.payload.instructions
        success(L"slot ${i} (pc=${slots(i).pc}) done with tid=${io.iCacheRsp.payload.transactionId} cacheline=${io.iCacheRsp.payload.instructions}")
      }
    }
  }
  // 队首事务处理 (Hit/Miss)
  when(is_done(oldIndex) && io.dispatchGroupOut.fire) {
    // 退休老的
    slots(oldIndex).valid := False
    // 晋升新的
    oldIndex := newIndex
    debug(L"slot ${newIndex} promoted to front")
  }

  // 硬冲刷具有最高优先级，覆盖所有其他更新
  when(io.flush) {
    slots(0).valid := False
    slots(1).valid := False
    oldIndex := 0
  }

  val dbg2 = new Area {
    val verbose = true
    when(io.iCacheCmd.valid) {
      if(verbose) debug(L"oldIndex=${oldIndex} sending ${io.iCacheCmd.payload.format}")
    }
    when(io.iCacheRsp.valid) {
      if(verbose) debug(L"oldIndex=${oldIndex} received ${io.iCacheRsp.payload.format}")
    }
    when(io.dispatchGroupOut.fire) {
      success(L"oldIndex=${oldIndex} sending group @${io.dispatchGroupOut.payload.pc}")
    }
  }
}
