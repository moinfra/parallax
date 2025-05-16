package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.utils.Encoders.PriorityEncoderOH

case class SuperScalarFreeListConfig(
    numPhysRegs: Int,
    resetToFull: Boolean = true,
    numAllocatePorts: Int = 1,
    numFreePorts: Int = 1
) {
  val physRegIdxWidth: BitCount = BitCount(log2Up(numPhysRegs))
  require(numPhysRegs > 0)
  require(numAllocatePorts > 0)
  require(numFreePorts > 0)
}

case class SuperScalarFreeListCheckpoint(config: SuperScalarFreeListConfig) extends Bundle {
  val freeMask = Bits(config.numPhysRegs bits)
}

case class SuperScalarFreeListIO(config: SuperScalarFreeListConfig) extends Bundle with IMasterSlave {
  // Allocate ports: requests N free physical registers
  val allocate = Vec(
    new Bundle { // Vec for N allocation requests
      val enable = Bool() // Master wants to allocate for THIS slot
      val physReg = UInt(config.physRegIdxWidth) // Allocated physReg for THIS slot
      val success = Bool() // Was allocation for THIS slot successful?
    },
    config.numAllocatePorts
  )

  // Free ports: return M physical registers
  val free = Vec(
    new Bundle { // Vec for M free requests
      val enable = Bool()
      val physReg = UInt(config.physRegIdxWidth)
    },
    config.numFreePorts
  )

  // Checkpoint and status
  val currentState = SuperScalarFreeListCheckpoint(config) // Output current state
  val restoreState = Stream(SuperScalarFreeListCheckpoint(config)) // Input to restore

  val numFreeRegs = UInt(log2Up(config.numPhysRegs + 1) bits) // Output

  override def asMaster(): Unit = {
    // Master DRIVES 'enable' for allocate and free
    allocate.foreach { p => out(p.enable) }
    free.foreach { p => out(p.enable); out(p.physReg) }

    // Master RECEIVES 'physReg' and 'success' for allocate
    allocate.foreach { p => in(p.physReg); in(p.success) }

    // Master RECEIVES 'currentState' and 'numFreeRegs'
    in(currentState)
    in(numFreeRegs)

    // Master DRIVES 'restoreState' Stream
    master(restoreState)
  }
}

class SuperScalarFreeList(val config: SuperScalarFreeListConfig) extends Component {
  val io = slave(SuperScalarFreeListIO(config))

  val freeRegsMask = Reg(Bits(config.numPhysRegs bits)) setName ("freeRegsMask_reg")
  val initMask = Bits(config.numPhysRegs bits)
  if (config.resetToFull) {
    initMask.setAll()
    if (config.numPhysRegs > 0) { initMask(0) := False } // p0 not free
  } else {
    initMask := B(0, config.numPhysRegs bits)
  }
  freeRegsMask.init(initMask)
  report(L"[DUT] Initial freeRegsMask (after init): ${freeRegsMask}")

  // --- Allocation Logic (Superscalar) ---
  var currentMaskForAlloc_iter = freeRegsMask // This var will hold the mask state as it passes through ports
  val availableRegsForAlloc_at_cycle_start = CountOne(freeRegsMask)

  report(
    L"[SSFreeList: Alloc Phase] Start of cycle. freeRegsMask_reg = ${freeRegsMask}, availableRegsForAlloc_at_cycle_start = ${availableRegsForAlloc_at_cycle_start}"
  )

  for (i <- 0 until config.numAllocatePorts) {
    val port = io.allocate(i)
    report(L"[SSFreeList: Alloc Port ${i.toString()}] Input enable = ${port.enable}")

    val isAnyFreeInCurrentIterMask = currentMaskForAlloc_iter.orR
    val enoughOverallRegsForThisPort = availableRegsForAlloc_at_cycle_start > U(i)
    val canAllocateThisPort = isAnyFreeInCurrentIterMask && enoughOverallRegsForThisPort
    val chosenPhysReg = OHToUInt(PriorityEncoderOH(currentMaskForAlloc_iter))

    port.success := canAllocateThisPort && port.enable
    port.physReg := chosenPhysReg

    report(L"[SSFreeList: Alloc Port ${i.toString()}] currentMaskForAlloc_iter (before this port) = ${currentMaskForAlloc_iter}, ")
    report(
      L"    isAnyFreeInCurrentIterMask = ${isAnyFreeInCurrentIterMask}, enoughOverallRegs = ${enoughOverallRegsForThisPort}, "
    )
    report(L"    canAllocateThisPort (pre-enable) = ${canAllocateThisPort}, chosenPhysReg (pre-enable) = ${chosenPhysReg}")
    report(L"[SSFreeList: Alloc Port ${i.toString()}] Output success = ${port.success}, Output physReg = ${port.physReg}")

    // Define the mask for the *next* port based on this port's action
    val maskAfterThisPort = Bits(config.numPhysRegs bits) // This signal will always be assigned.
    when(port.enable && canAllocateThisPort) {
      maskAfterThisPort := currentMaskForAlloc_iter
      maskAfterThisPort(chosenPhysReg) := False
      report(
        L"[SSFreeList: Alloc Port ${i.toString()}] SUCCESSFUL ALLOC. Chosen p${chosenPhysReg}. maskAfterThisPort calculated as ${maskAfterThisPort} (from ${currentMaskForAlloc_iter})"
      )
    } otherwise {
      maskAfterThisPort := currentMaskForAlloc_iter // No change if no alloc
      report(
        L"[SSFreeList: Alloc Port ${i.toString()}] NO ALLOC or disabled. maskAfterThisPort is ${maskAfterThisPort} (same as currentMaskForAlloc_iter)"
      )
    }
    currentMaskForAlloc_iter = maskAfterThisPort // Update the 'var' for the next iteration
  }

  // --- State Update Logic ---
  // nextFreeRegsMask_final_comb starts with the mask *after* all allocations in the current cycle are considered
  val nextFreeRegsMask_final_comb = CombInit(
    currentMaskForAlloc_iter
  ) // Use the final state of currentMaskForAlloc_iter
  report(
    L"[SSFreeList: Free Phase] Mask after all alloc ports (currentMaskForAlloc_iter) = ${currentMaskForAlloc_iter}"
  )
  report(L"[SSFreeList: Free Phase] Initial nextFreeRegsMask_final_comb = ${nextFreeRegsMask_final_comb}")

  for (i <- 0 until config.numFreePorts) {
    val port = io.free(i)
    report(L"[SSFreeList: Free Port ${i.toString()}] Input enable = ${port.enable}, Input physReg = ${port.physReg}")

    when(port.enable) {
      val canFreeThisReg = port.physReg =/= U(0, config.physRegIdxWidth) ||
        Bool(config.numPhysRegs == 1 && port.physReg == U(0))

      report(L"[SSFreeList: Free Port ${i.toString()}] canFreeThisReg = ${canFreeThisReg}")

      when(canFreeThisReg) {
        // IMPORTANT: If multiple free ports target the same physReg, this is fine (idempotent True).
        // If an alloc port and a free port target the same physReg, the free operation comes "after"
        // in the calculation of nextFreeRegsMask_final_comb, so it would effectively "win" for that bit.
        val physRegToFree = port.physReg
        val oldBitVal = nextFreeRegsMask_final_comb(physRegToFree)
        nextFreeRegsMask_final_comb(physRegToFree) := True
        report(
          L"[SSFreeList: Free Port ${i.toString()}] SUCCESSFUL FREE. Marking p${physRegToFree} as True. Old bit value was ${oldBitVal}. nextFreeRegsMask_final_comb now ${nextFreeRegsMask_final_comb}"
        )
      } otherwise {
        report(
          L"[SSFreeList: Free Port ${i.toString()}] Cannot free this reg (p0 special or invalid). No change to mask from this port."
        )
      }
    }
  }

  report(
    L"[SSFreeList: Update Phase] Final nextFreeRegsMask_final_comb (before restore check) = ${nextFreeRegsMask_final_comb}"
  )

  // --- Checkpoint Restore and Final Update ---
  io.restoreState.ready := True // Always ready to accept restore
  report(
    L"[SSFreeList: Update Phase] Restore input: valid=${io.restoreState.valid}, payload=${io.restoreState.payload.freeMask}"
  )

  when(io.restoreState.valid) {
    freeRegsMask := io.restoreState.payload.freeMask
    report(L"[SSFreeList: Update Phase] RESTORING freeRegsMask_reg to ${io.restoreState.payload.freeMask}")
  } otherwise {
    freeRegsMask := nextFreeRegsMask_final_comb
    report(
      L"[SSFreeList: Update Phase] UPDATING freeRegsMask_reg with nextFreeRegsMask_final_comb = ${nextFreeRegsMask_final_comb}"
    )
  }

  // --- Outputs ---
  io.currentState.freeMask := freeRegsMask // This reflects the *register's* value
  io.numFreeRegs := CountOne(freeRegsMask) // This also reflects the *register's* value

  // Report final register value at the end of the cycle evaluation (for next cycle's start)
  // This needs to be done carefully, perhaps in a post-cycle check if the simulator allows,
  // or rely on the "Start of cycle" report in the next cycle.
  // For now, let's add a report that shows what io.numFreeRegs and io.currentState will be based on the update.
  val finalRegValueForNextCycle =
    Mux(io.restoreState.valid, io.restoreState.payload.freeMask, nextFreeRegsMask_final_comb)
  report(L"[SSFreeList: End Of Cycle Eval] freeRegsMask_reg will be ${finalRegValueForNextCycle} for next cycle.")
  report(L"[SSFreeList: End Of Cycle Eval] io.numFreeRegs output will be ${CountOne(finalRegValueForNextCycle)}.")
  report(L"[SSFreeList: End Of Cycle Eval] io.currentState.freeMask output will be ${finalRegValueForNextCycle}.")
}
