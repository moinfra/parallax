package parallax.components.rename

import spinal.core._
import spinal.lib._
import parallax.utils.Encoders.PriorityEncoderOH

case class FreeListConfig(
    numPhysRegs: Int,
    // On reset, should all phys regs (except maybe p0) be free?
    resetToFull: Boolean = true
) {
  val physRegIdxWidth: BitCount = BitCount(log2Up(numPhysRegs))
  require(numPhysRegs > 0)
}

case class FreeListCheckpoint(config: FreeListConfig) extends Bundle {
  val freeMask = Bits(config.numPhysRegs bits)
}

case class FreeListIO(config: FreeListConfig) extends Bundle with IMasterSlave{
  // Allocate port: requests a free physical register
  val allocate = new Bundle {
    val enable = Bool ()
    // Output: Allocated physical register index
    val physReg = UInt (config.physRegIdxWidth)
    val success = Bool ()
  }

  // Free ports: return physical registers to the free list
  // We might need multiple free ports if multiple uops can commit/be flushed simultaneously
  // For simplicity, start with one.
  val free = new Bundle {
    // Input: Master wants to free a register
    val enable = Bool ()
    // Input: Physicawhen(io.checkpointRestorel register to free
    val physReg = UInt (config.physRegIdxWidth)
  }

  // Checkpoint
  val currentState = FreeListCheckpoint(config)
  val restoreState = Stream (FreeListCheckpoint(config))

  val numFreeRegs = UInt (log2Up(config.numPhysRegs + 1) bits)

  
  override def asMaster(): Unit = {
    // Master DRIVES 'enable' inputs to FreeList
    out(allocate.enable)
    out(free.enable)
    out(free.physReg) // Master provides the physReg to be freed

    // Master RECEIVES 'physReg' and 'success' from FreeList
    in(allocate.physReg)
    in(allocate.success)

    // Master RECEIVES 'currentState' from FreeList
    in(currentState)

    // Master DRIVES 'restoreState' Stream to FreeList
    // Stream's asMaster() will handle its internal directions correctly.
    // Here, we declare the Stream itself as a master port from FreeListIO's master.
    master(restoreState) // This means TestBench will act as master to restoreState stream

    // Master RECEIVES 'numFreeRegs' from FreeList
    in(numFreeRegs)
  }
}

class FreeList(val config: FreeListConfig) extends Component {
  val io = slave(FreeListIO(config))

  // Bitmask: 1 indicates free, 0 indicates allocated
  // Physical Register 0 is often special (always zero, never allocated/freed conventionally)
  // So, mask(0) might always be 0.
  val freeRegsMask = Reg(Bits(config.numPhysRegs bits))

  // Initial state
  val initMask = Bits(config.numPhysRegs bits)
  if (config.resetToFull) {
    initMask.setAll();
    if (config.numPhysRegs > 0) { initMask(0) := False; }
  } else {
    initMask := B(0, config.numPhysRegs bits) // Or some other specific initial state
  }
  // report(Seq("[FreeList] initial state: ", initMask.asBits))

  freeRegsMask.init(initMask)

  // --- Allocation Logic ---
  // Find the first '1' (free register) in the mask.
  // PriorityEncoder an LSB-first encoder.
  val firstFreeFound = freeRegsMask.orR // Is there any '1' in the mask?
  val firstFreeIdx = OHToUInt(PriorityEncoderOH(freeRegsMask)) // Index of the LSB '1'

  io.allocate.success := firstFreeFound
  io.allocate.physReg := firstFreeIdx

  // --- State Update on Allocate/Free ---
  val nextFreeRegsMask = CombInit(freeRegsMask) // Start with current mask
  when(io.allocate.enable && firstFreeFound) {
    nextFreeRegsMask(firstFreeIdx) := False // Mark as allocated
    report(
      L"[FreeList] Allocate Condition Met: nextFreeRegsMask(${firstFreeIdx}) will be False. Calculated nextFreeRegsMask=${nextFreeRegsMask}"
    )
  }

  when(io.free.enable) {
    // Ensure we are not trying to free p0 if it's special, or handle appropriately
    when(io.free.physReg =/= U(0, config.physRegIdxWidth) || Bool(config.numPhysRegs == 1 && io.free.physReg == U(0))) { // Allow freeing p0 only if it's the only register
      // TODO: Add assertion: assert(!freeRegsMask(io.free.physReg), "Trying to free an already free register") in sim
      nextFreeRegsMask(io.free.physReg) := True // Mark as free
      report(
        L"[FreeList] Free Condition Met: nextFreeRegsMask(${io.free.physReg}) will be True. Calculated nextFreeRegsMask=${nextFreeRegsMask}"
      )
    } otherwise {
      report(
        L"[FreeList] Free Condition Met: Trying to free p0, which is special. Ignoring."
      )
    }
  }
  report(L"[DEBUG] free.physReg=${io.free.physReg}")
  report(
    L"[FreeList PRE-UPDATE] freeRegsMask=${freeRegsMask}, nextFreeRegsMask=${nextFreeRegsMask}, io.free.enable=${io.free.enable},  io.restoreState.valid=${io.restoreState.valid}, restorePayload=${Mux(io.restoreState.valid, io.restoreState.payload.freeMask, B(0))}"
  )

  // --- Checkpoint Restore ---
  // Restore has higher priority than normal operations for updating the mask
  // io.restoreState.ready := !io.restoreState.valid && !io.allocate.enable && !io.free.enable
  io.restoreState.ready := True

  // The restore itself happens when io.checkpointRestore.valid is high.
  when(io.restoreState.valid) {
    freeRegsMask := io.restoreState.payload.freeMask
    report(L"[FreeList] Restoring: payload=${io.restoreState.payload.freeMask}. New freeRegsMask will be this.")
  } otherwise {
    freeRegsMask := nextFreeRegsMask
    report(
      L"[FreeList] Updating freeRegsMask with nextFreeRegsMask=${nextFreeRegsMask}. Old freeRegsMask=${freeRegsMask}"
    )
  }

  // State Explosure
  io.currentState.freeMask := freeRegsMask

  // --- Debug ---
  io.numFreeRegs := CountOne(freeRegsMask)
  report(L"[FreeList] numFreeRegs=${io.numFreeRegs}")
}
