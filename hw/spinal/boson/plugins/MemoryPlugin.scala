package boson.plugins

import spinal.core._
import spinal.lib._
import boson._
import boson.BosonConfig._

// Basic Memory Stage using synchronous memory (simulation friendly)
class MemoryPlugin(dataMemSize: BigInt) extends Plugin[Boson] {

  var dataMemory: Mem[Bits] = null

  override def setup(pipeline: Boson): Unit = {
    dataMemory = Mem(Bits(8 bits), wordCount = dataMemSize) // Byte-addressable memory
    println(s"MemoryPlugin: Initialized ${dataMemory.wordCount} bytes Data Memory.")
  }

  override def build(pipeline: Boson): Unit = {
    import pipeline._
    import pipeline.config._

    memory plug new Area {
      import memory._

      // Inputs from Execute stage
      val address = input(EFF_ADDRESS)
      val storeData = input(RK_DATA) // Data to write comes from RK usually
      val isLoad = input(IS_LOAD)
      val isStore = input(IS_STORE)
      val memOp = input(MEM_OP)
      val memSigned = input(MEM_SIGNED)

      // Pass through necessary data/control to Writeback stage
      insert(RD) := input(RD)
      insert(WRITE_ENABLE) := input(WRITE_ENABLE)
      insert(ALU_RESULT) := input(ALU_RESULT) // Pass ALU result for non-load WB
      insert(IS_LOAD) := isLoad // Let WB know if data comes from memory
      insert(IS_JUMP_LINK) := input(IS_JUMP_LINK) // Pass through for WB stage
      insert(LINK_ADDR) := input(LINK_ADDR) // Pass through for WB stage


      // --- Data Memory Access ---
      val readData = Bits(dataWidth bits)
      readData.assignDontCare() // Default

      when(arbitration.isFiring) {
        when(isStore) {
          switch(memOp) {
            is(MemOp.STORE_B) {
            }
            is(MemOp.STORE_H) {
            }
            is(MemOp.STORE_W) {
            }
          }

        } elsewhen (isLoad) {
          // Synchronous Read (result available next cycle)
          // This simple Mem doesn't easily support byte/half reads directly with sync.
          // A real implementation would use read masks or read full word and shift/mask.
          // Simulation placeholder: Read the aligned word containing the address.
          val wordAddress = address(log2Up(dataMemory.wordCount) - 1 downto 2)
          val wordData = dataMemory.readSync(wordAddress, enable = arbitration.isFiring)

          // Extract and sign-extend based on MemOp and address alignment
          val byteOffset = address(1 downto 0)
          val halfOffset = address(1)

          switch(memOp) {
            is(MemOp.LOAD_B) {
            }
            is(MemOp.LOAD_BU) {
            }
            is(MemOp.LOAD_H) {
            }
            is(MemOp.LOAD_HU) {
            }
            is(MemOp.LOAD_W) {
            }
          }
           report(L"[Memory] Load Firing: Addr=${address} Op=${memOp} -> RawWord=${wordData} Result=${readData}")
        } otherwise {
             report(L"[Memory] Firing: No Load/Store")
        }
      }

      // Insert the potentially read data (available in WB stage)
      insert(MEM_READ_DATA) := RegNext(readData) init(0) // Register read data for WB

      // Memory stage can stall if memory is not ready (e.g., cache miss)
      // arbitration.haltItself := isLoad && !dataMemory.readRspValid // Example stall
    }
  }
}
