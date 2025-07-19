package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.utils.Encoders.PriorityEncoderOH
import parallax.utilities.ParallaxLogger

case class SuperScalarFreeListCheckpoint(config: SimpleFreeListConfig) extends Bundle {
  val freeMask = Bits(config.numPhysRegs bits)
}

case class SuperScalarFreeListAllocatePort(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val enable = Bool()
  val physReg = UInt(config.physRegIdxWidth)
  val success = Bool()
  override def asMaster(): Unit = { out(enable); in(physReg); in(success) }
}

case class SuperScalarFreeListFreePort(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  val enable = Bool()
  val physReg = UInt(config.physRegIdxWidth)
  override def asMaster(): Unit = { out(enable); out(physReg) }

  def setIdle(): Unit = {
    enable := False
    physReg := 0
  }
}

case class SuperScalarFreeListIO(config: SimpleFreeListConfig) extends Bundle with IMasterSlave {
  // Allocate ports: requests N free physical registers
  val allocate = Vec(
    new SuperScalarFreeListAllocatePort(config),
    config.numAllocatePorts
  )

  // Free ports: return M physical registers
  val free = Vec(
    new SuperScalarFreeListFreePort(config),
    config.numFreePorts
  )

  // Checkpoint and status
  val currentState = SuperScalarFreeListCheckpoint(config) // Output current state
  val restoreState = Stream(SuperScalarFreeListCheckpoint(config)) // Input to restore

  val numFreeRegs = UInt(log2Up(config.numPhysRegs + 1) bits) // Output

  override def asMaster(): Unit = {
    // Master DRIVES 'enable' for allocate and free
    allocate.foreach { p => master(p) }
    free.foreach { p => master(p) }

    // Master RECEIVES 'physReg' and 'success' for allocate
    allocate.foreach { p => in(p.physReg); in(p.success) }

    // Master RECEIVES 'currentState' and 'numFreeRegs'
    in(currentState)
    in(numFreeRegs)

    // Master DRIVES 'restoreState' Stream
    master(restoreState)
  }
}

class SuperScalarFreeList(val config: SimpleFreeListConfig) extends Component {
  val io = slave(SuperScalarFreeListIO(config))
  val enableLog = false
  val freeRegsMask = Reg(Bits(config.numPhysRegs bits)) setName ("freeRegsMask_reg")
  val initMask = Bits(config.numPhysRegs bits)

  // Initialize based on numInitialArchMappings and resetToFull
  // Physical registers from 0 to (numInitialArchMappings - 1) are always considered 'used' (not free) at reset.
  // The 'resetToFull' flag determines the state of the remaining physical registers.
  initMask.clearAll() // Start with all physical registers marked as 'used' (False)

    // If resetToFull is true, mark physical registers from numInitialArchMappings
    // up to numPhysRegs-1 as 'free' (True).
    for (i <- config.numInitialArchMappings until config.numPhysRegs) {
      initMask(i) := True
    }
  // If resetToFull is false, initMask remains all False, meaning the free list is empty.
  // In both cases (resetToFull true or false), the first 'numInitialArchMappings' registers
  // (indices 0 to numInitialArchMappings-1) remain False (used) as per initMask.clearAll().

  freeRegsMask.init(initMask)
  if(enableLog) report(L"[DUT] Initial freeRegsMask (after init): ${freeRegsMask}")

  // --- Allocation Logic (Superscalar) ---
  // 1. Find all available free registers in parallel
  val freeRegsOh = freeRegsMask.asBools.map(b => b.asBits).asBits // Get an OH representation of all free regs
  val availableRegs = CountOne(freeRegsMask) // 组合逻辑计算

  // 新增一个寄存器来锁存计算结果
  val numFreeRegs_reg = RegNext(availableRegs) init(config.numPhysRegs - config.numInitialArchMappings)


  // 2. Find the first N free registers for the N allocate ports
  val allocatedRegsOh = Vec(Bits(config.numPhysRegs bits), config.numAllocatePorts)
  val allocatedRegsIdx = Vec(UInt(config.physRegIdxWidth), config.numAllocatePorts)

  // This logic finds the top N set bits in parallel, which is much better for timing.
  // We use a temporary variable to hold the mask as we select bits from it.
  var tempMask = freeRegsOh
  for (i <- 0 until config.numAllocatePorts) {
    val oh = OHMasking.first(tempMask) // Find the first available register
    allocatedRegsOh(i) := oh
    allocatedRegsIdx(i) := OHToUInt(oh)
    tempMask = tempMask & ~oh // Remove the found register from the mask for the next iteration
  }
  
  // 3. Drive port outputs in parallel and generate the final allocation mask
  for (i <- 0 until config.numAllocatePorts) {
    val port = io.allocate(i)
    val canAllocate = availableRegs > i
    
    port.success := port.enable && canAllocate
    port.physReg := allocatedRegsIdx(i)
  }

  // Now, construct the allocatedMask based on the final success signals in a parallel and safe way.
  val allocatedMask = (0 until config.numAllocatePorts).map { i =>
    // If the i-th port was successful, take its one-hot allocated register. Otherwise, take a mask of all zeros.
    Mux(io.allocate(i).success, allocatedRegsOh(i), B(0, config.numPhysRegs bits))
  }.reduce(_ | _) // OR all the individual masks together to get the final mask.

  // --- State Update Logic ---
  // Start with the current mask, remove allocated registers, then add freed registers.
  val nextFreeRegsMask_after_alloc = freeRegsMask & ~allocatedMask
  val nextFreeRegsMask_final_comb = CombInit(nextFreeRegsMask_after_alloc)
  if(enableLog) report(
    L"[SSFreeList: Free Phase] nextFreeRegsMask_after_alloc = ${nextFreeRegsMask_after_alloc} nextFreeRegsMask_final_comb = ${nextFreeRegsMask_final_comb}")

  for (i <- 0 until config.numFreePorts) {
    val port = io.free(i)
    if(enableLog) report(L"[SSFreeList: Free Port ${i.toString()}] Input enable = ${port.enable}, Input physReg = ${port.physReg}")

    when(port.enable) {
      // Physical register 0 (if numInitialArchMappings > 0) should ideally not be freed explicitly
      // as it's considered permanently mapped or special.
      // However, the logic here allows freeing any register.
      // The primary protection for p0 (or initial arch regs) is that they are not initially free.
      // If one of these is mistakenly freed and then re-allocated, it could lead to issues
      // if the architecture assumes p0 (or similar) has special properties beyond its initial value.
      // Current logic: canFreeThisReg allows freeing p0 unless it's the *only* register (edge case).
      // This behavior is kept, but users should be cautious about freeing registers in the
      // [0, numInitialArchMappings-1] range if they have special architectural significance.
      val canFreeThisReg = port.physReg =/= U(0, config.physRegIdxWidth) ||
        Bool(config.numPhysRegs == 1 && port.physReg == U(0)) // Original condition for p0

      if(enableLog) report(L"[SSFreeList: Free Port ${i.toString()}] canFreeThisReg = ${canFreeThisReg}")

      when(canFreeThisReg) {
        // IMPORTANT: If multiple free ports target the same physReg, this is fine (idempotent True).
        // If an alloc port and a free port target the same physReg, the free operation comes "after"
        // in the calculation of nextFreeRegsMask_final_comb, so it would effectively "win" for that bit.
        val physRegToFree = port.physReg
        val oldBitVal = nextFreeRegsMask_final_comb(physRegToFree)
        nextFreeRegsMask_final_comb(physRegToFree) := True
        if(enableLog) report(
          L"[SSFreeList: Free Port ${i.toString()}] SUCCESSFUL FREE. Marking p${physRegToFree} as True. Old bit value was ${oldBitVal}. nextFreeRegsMask_final_comb now ${nextFreeRegsMask_final_comb}"
        )
      } otherwise {
        if(enableLog) report(
          L"[SSFreeList: Free Port ${i.toString()}] Cannot free this reg (p0 special or invalid). No change to mask from this port."
        )
      }
    }
  }

  if(enableLog) report(
    L"[SSFreeList: Update Phase] Final nextFreeRegsMask_final_comb (before restore check) = ${nextFreeRegsMask_final_comb}"
  )

  // --- Checkpoint Restore and Final Update ---
  io.restoreState.ready := True // Always ready to accept restore
  if(enableLog) report(
    L"[SSFreeList: Update Phase] Restore input: valid=${io.restoreState.valid}, payload=${io.restoreState.payload.freeMask}"
  )

  when(io.restoreState.valid) {
    freeRegsMask := io.restoreState.payload.freeMask
    if(enableLog) report(L"[SSFreeList: Update Phase] RESTORING freeRegsMask_reg to ${io.restoreState.payload.freeMask}")
  } otherwise {
    freeRegsMask := nextFreeRegsMask_final_comb
    if(enableLog) report(
      L"[SSFreeList: Update Phase] UPDATING freeRegsMask_reg with nextFreeRegsMask_final_comb = ${nextFreeRegsMask_final_comb}"
    )
  }

  // --- Outputs ---
  io.currentState.freeMask := freeRegsMask // This reflects the *register's* value
  io.numFreeRegs := numFreeRegs_reg // This also reflects the *register's* value 这里是一个悲观值，主要是优化时序。

  // If(enableLog) Report final register value at the end of the cycle evaluation (for next cycle's start)
  // This needs to be done carefully, perhaps in a post-cycle check if the simulator allows,
  // or rely on the "Start of cycle" if(enableLog) report in the next cycle.
  // For now, let's add a if(enableLog) report that shows what io.numFreeRegs and io.currentState will be based on the update.
  val finalRegValueForNextCycle =
    Mux(io.restoreState.valid, io.restoreState.payload.freeMask, nextFreeRegsMask_final_comb)
  if(enableLog) report(L"[SSFreeList: End Of Cycle Eval] freeRegsMask_reg will be ${finalRegValueForNextCycle} for next cycle.")
  if(enableLog) report(L"[SSFreeList: End Of Cycle Eval] io.numFreeRegs output will be ${CountOne(finalRegValueForNextCycle)}.")
  if(enableLog) report(L"[SSFreeList: End Of Cycle Eval] io.currentState.freeMask output will be ${finalRegValueForNextCycle}.")
}
