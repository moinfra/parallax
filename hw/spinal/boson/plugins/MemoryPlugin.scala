package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._
import boson.interfaces._ // Import internal interfaces
import boson.components.AxiMemoryBusComponent
// Required for state machine if added later for AXI handling
// import spinal.lib.fsm._

class MemoryPlugin() extends Plugin[Boson] {

  override def setup(pipeline: Boson): Unit = {}

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    memory plug new Area {
      import memory._ // Access arbitration signals

      // --- Inputs from Execute stage ---
      // These signals are available via input()
      val eff_address_in = input(EFF_ADDRESS)
      val store_data_in = input(RK_DATA) // Data to write comes from RK usually
      val is_load_in = input(IS_LOAD)
      val is_store_in = input(IS_STORE)
      val mem_op_in = input(MEM_OP)
      val mem_signed_in = input(MEM_SIGNED)
      // Pass-through signals, also read via input()
      val rd_in = input(RD)
      val write_enable_in = input(WRITE_ENABLE)
      val alu_result_in = input(ALU_RESULT)
      val is_jump_link_in = input(IS_JUMP_LINK)
      val link_addr_in = input(LINK_ADDR)

      // --- Memory Bus Interface ---
      // NOTE: The dBus signals are currently placeholders in AxiMemoryBusComponent
      val cmd = pipeline.memoryBus.io.dBusCmd
      val rsp = pipeline.memoryBus.io.dBusRsp

      // --- Prepare Memory Command ---
      val accessSize = UInt(2 bits) // 0=byte, 1=half, 2=word
      val writeMask = Bits(config.dataWidth / 8 bits)
      val writeDataAligned = Bits(config.dataWidth bits)

      writeDataAligned := store_data_in // Default
      writeMask := 0
      switch(mem_op_in) {
        is(MemOp.LOAD_B, MemOp.LOAD_BU, MemOp.STORE_B) {
          accessSize := 0
          // Correct mask calculation based on address lower bits
          writeMask := (B(1) << eff_address_in(1 downto 0)).resize(config.dataWidth / 8 bits)
          // Align byte data for store based on address LSBs
          writeDataAligned := store_data_in(7 downto 0) << (eff_address_in(1 downto 0) * 8)
        }
        is(MemOp.LOAD_H, MemOp.LOAD_HU, MemOp.STORE_H) {
          accessSize := 1
          when(eff_address_in(1)) { // High half-word
            writeMask := B"1100" // Assuming 32-bit bus
            writeDataAligned := store_data_in(15 downto 0) << 16
          } otherwise { // Low half-word
            writeMask := B"0011"
            writeDataAligned := store_data_in(15 downto 0)
          }
        }
        is(MemOp.LOAD_W, MemOp.STORE_W) {
          accessSize := 2
          writeMask := B"1111" // Full word
          writeDataAligned := store_data_in
        }
        default { // MemOp.NONE or others
          accessSize := 0 // Default size
          writeMask := 0
          writeDataAligned := 0
        }
      }

      // --- AXI Data Bus Interaction Logic ---
      // TODO: This section needs proper state management for robust AXI handling,
      // similar to the corrected FetchPlugin, especially for handling multi-cycle
      // command acceptance and response latency for loads.
      // The current logic with simple Regs is a placeholder.

      // --- Send Memory Request (Placeholder) ---
      cmd.valid := arbitration.isValid && !arbitration.isStuck && (is_load_in || is_store_in)
      cmd.payload.address := eff_address_in
      cmd.payload.data := writeDataAligned
      cmd.payload.write := is_store_in
      cmd.payload.mask := writeMask
      cmd.payload.size := accessSize

      // --- Receive Memory Response (Placeholder for Loads) ---
      val readDataRaw = Bits(dataWidth bits)
      val readDataAligned = Bits(dataWidth bits)
      val loadError = Bool()

      // Placeholder response handling - replace with robust FSM or FIFO logic
      val responseValid = RegInit(False).setWhen(rsp.valid).clearWhen(arbitration.isFiring) // Simplified tracking
      val responseData = RegNextWhen(rsp.payload.data, rsp.valid && rsp.ready) init (0) // Simplified data latch
      val responseError = RegNextWhen(rsp.payload.error, rsp.valid && rsp.ready) init (False) // Simplified error latch

      rsp.ready := !arbitration.isStuck // Simplified readiness

      readDataRaw := responseData // Use latched response
      // Crude error check - needs improvement with proper state tracking
      loadError := responseError || (is_load_in && arbitration.isValid && !responseValid && !rsp.valid)

      // Extract and sign/zero-extend data based on MemOp and address alignment
      readDataAligned.assignDontCare()
      val byteOffset = eff_address_in(1 downto 0)
      val halfOffset = eff_address_in(1)
      val shiftAmount = byteOffset << 3

      switch(mem_op_in) {
        is(MemOp.LOAD_B) {
          val byte = (readDataRaw >> shiftAmount)(7 downto 0)
          readDataAligned := S(byte).resize(dataWidth bits).asBits
        }
        is(MemOp.LOAD_BU) {
          val byte = (readDataRaw >> shiftAmount)(7 downto 0)
          readDataAligned := U(byte).resize(dataWidth bits).asBits
        }
        is(MemOp.LOAD_H) {
          val half = (readDataRaw >> (halfOffset.asUInt << 4))(15 downto 0)
          readDataAligned := S(half).resize(dataWidth bits).asBits
        }
        is(MemOp.LOAD_HU) {
          val half = (readDataRaw >> (halfOffset.asUInt << 4))(15 downto 0)
          readDataAligned := U(half).resize(dataWidth bits).asBits
        }
        is(MemOp.LOAD_W) {
          readDataAligned := readDataRaw
        }
        default {
          readDataAligned := B(0)
        }
      }

      // --- Pipeline Signal Generation and Propagation ---

      // ** Insert ** signals originating in this stage
      val w_mem_read_data = insert(MEM_READ_DATA)
      w_mem_read_data := Mux(is_load_in && !loadError, readDataAligned, B(0)) // Value depends on load outcome

      // ** Output ** signals passing through to Writeback stage
      output(RD) := rd_in
      output(WRITE_ENABLE) := write_enable_in
      output(ALU_RESULT) := alu_result_in // Pass ALU result for non-load WB
      output(IS_LOAD) := is_load_in // Let WB know if data comes from memory
      output(IS_JUMP_LINK) := is_jump_link_in
      output(LINK_ADDR) := link_addr_in

      // ** Output ** the signal inserted in this stage
      output(MEM_READ_DATA) := w_mem_read_data

      // --- Stalling Logic (Placeholder - depends on robust AXI handling) ---
      val isMemoryAccess = is_load_in || is_store_in
      // Placeholder stall conditions - replace with actual state checks
      val waitingToSend = arbitration.isValid && isMemoryAccess && !cmd.ready
      val waitingForLoadResponse = arbitration.isValid && is_load_in && cmd.fire && !rsp.valid // Very basic check

      arbitration.haltItself := waitingToSend || waitingForLoadResponse // Needs refinement

      // --- Reporting ---
      when(arbitration.isFiring) {
        when(is_store_in) {
          report(
            L"[Memory] Store Firing: Addr=${eff_address_in} Op=${mem_op_in} Data=${store_data_in} Mask=${writeMask} AlignedData=${writeDataAligned} CmdV=${cmd.valid} CmdR=${cmd.ready} Stall=${arbitration.haltItself}"
          )
        } elsewhen (is_load_in) {
          report(
            L"[Memory] Load Firing: Addr=${eff_address_in} Op=${mem_op_in} CmdV=${cmd.valid} CmdR=${cmd.ready} RspV=${rsp.valid} RspR=${rsp.ready} Stall=${arbitration.haltItself} -> RawData=${readDataRaw} Result=${readDataAligned} Err=${loadError}"
          )
        } otherwise {
          report(L"[Memory] Firing: No Load/Store Stall=${arbitration.haltItself}")
        }
      }

      when(arbitration.isFiring && is_load_in && loadError) {
        report(L"[Memory] ERROR: Load failed for Addr=${eff_address_in}")
        // spinal.core.assert(!loadError, "Memory load error occurred", FAILURE) // Optional assertion
      }
    } // End Area
  } // End build
} // End class
