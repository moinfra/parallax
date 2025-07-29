package test.scala

import com.sun.jna.{Library, Native, Pointer, NativeLibrary}

/** =================================================================================
  * JNA Interface Definition (`La32rFfi`) - The Unchangeable Boundary
  * =================================================================================
  *
  * This trait is NOT standard Scala code. It is a blueprint for JNA to map Scala calls
  * directly to the underlying C-compatible binary library (`.so`, `.dll`, `.dylib`).
  *
  * The types used here MUST match JNA's mapping rules for C types:
  *   - C `uint32_t` -> Scala `Int` (JNA handles the 32-bit pattern)
  *   - C `size_t` / `uint64_t` -> Scala `Long` (JNA handles the 64-bit pattern)
  *
  * We CANNOT use `BigInt` here, as JNA does not know how to convert a `BigInt` object
  * into a primitive C integer. This trait represents the raw, low-level FFI boundary.
  */
trait La32rFfi extends Library {
  def vm_create(start_pc: Int, bases: Array[Int], sizes: Array[Long], num_regions: Long): Pointer
  def vm_destroy(handle: Pointer): Unit
  def vm_load_binary(handle: Pointer, base_addr: Int, data: Array[Byte], data_len: Long): Int
  def vm_step(handle: Pointer): Int
  def vm_get_pc(handle: Pointer): Int
  def vm_get_gpr(handle: Pointer, index: Int): Int
  def vm_get_all_gprs(handle: Pointer, out_gprs: Array[Int]): Unit
  def vm_get_cycle_count(handle: Pointer): Long
  def vm_read_memory(handle: Pointer, addr: Int, out_data: Array[Byte], size: Long): Int
}

/** =================================================================================
  * Scala API Object (`GoldenModel`) - The Safe, `BigInt`-based World
  * =================================================================================
  *
  * This object provides the public API for all other Scala code. It exclusively uses
  * `BigInt` for addresses and register values to ensure correctness and prevent
  * signed/unsigned confusion.
  *
  * Inside its methods, it performs the necessary conversions from `BigInt` to `Int` or `Long`
  * right before calling the low-level `ffi` interface. This is the correct place
  * to manage the transition from the safe Scala world to the raw C world.
  */
object GoldenModel {
  // --- Setup and FFI Loading (unchanged) ---
  private def findLibraryDir(): String = {
    val projectRoot = System.getProperty("user.dir")
    val libraryPath = new java.io.File(s"$projectRoot/../la32r-vm/target/debug/").getCanonicalPath
    libraryPath
  }
  private val libraryPath = findLibraryDir()
  println(s"JNA search path set to: $libraryPath")
  NativeLibrary.addSearchPath("la32r_vm_ffi", libraryPath)
  private val ffi: La32rFfi = Native.load("la32r_vm_ffi", classOf[La32rFfi])

  private var vmHandle: Pointer = _
  private val Mask32Bit = (BigInt(1) << 32) - 1

  // Helper to safely convert a BigInt representing a u32 to a plain Int for JNA
  private def toU32Int(value: BigInt): Int = value.toInt
  // Helper to safely convert a BigInt representing a u64 to a plain Long for JNA
  private def toU64Long(value: BigInt): Long = value.toLong
  // Helper to convert a plain Int from JNA (representing a u32) to a BigInt
  private def fromU32Int(value: Int): BigInt = BigInt(value) & Mask32Bit

  /** Initializes the golden model instance.
    * THIS is the public API. Note the use of `BigInt` in the `memoryMap`.
    *
    * @param startPc The initial program counter.
    * @param memoryMap A sequence of (baseAddress, sizeInBytes) tuples.
    */
  def initialize(startPc: BigInt, memoryMap: Seq[(BigInt, BigInt)]): Unit = {
    if (vmHandle != null) destroy()

    // **CONVERSION HAPPENS HERE**: From BigInt to primitive types for the C call.
    val bases: Array[Int] = memoryMap.map(m => toU32Int(m._1)).toArray
    val sizes: Array[Long] = memoryMap.map(m => toU64Long(m._2)).toArray

    vmHandle = ffi.vm_create(toU32Int(startPc), bases, sizes, memoryMap.length.toLong)
    if (vmHandle == null) throw new RuntimeException("Failed to create Golden Model VM.")
  }

  def destroy(): Unit = {
    if (vmHandle != null) {
      ffi.vm_destroy(vmHandle)
      vmHandle = null
    }
  }

  /** A unified function to initialize a region of the VM's memory.
    * This replaces both loadInstructions and the internal magic fill logic.
    *
    * @param baseAddr The starting address to write to.
    * @param data The byte array to write.
    */
  def initMemory(baseAddr: Int, data: Array[Byte]): Unit = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized. Call initialize() first.")

    val status = ffi.vm_load_binary(vmHandle, baseAddr, data, data.length)
    if (status != 0) {
      throw new RuntimeException(s"Failed to initialize memory at 0x${baseAddr.toHexString}. Status code: $status")
    }
  }

  /**
   * Makes the golden model execute until its PC matches the target commit PC,
   * then executes the instruction at that PC. This is a common pattern for
   * lock-step verification with a hardware simulator (DUT).
   *
   * @param commitPc The PC of the instruction the DUT is about to commit.
   */
  def stepUntil(commitPc: BigInt): Unit = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")

    var currentVmPc = fromU32Int(ffi.vm_get_pc(vmHandle))

    // Error if golden model is somehow ahead of the DUT
    if (currentVmPc > commitPc) {
      throw new RuntimeException(s"Golden model is ahead of DUT! Golden PC: 0x${currentVmPc.toString(16)}, DUT Commit PC: 0x${commitPc.toString(16)}")
    }

    // Loop execution until golden model's PC matches DUT's commit PC
    // This loop synchronizes the model to the state *before* the commit instruction is executed.
    while (currentVmPc < commitPc) {
      val status = ffi.vm_step(vmHandle)
      if (status != 0) { // 0 is VmStatus::Running
        throw new RuntimeException(s"Golden model halted or errored unexpectedly at PC 0x${currentVmPc.toString(16)} before reaching target PC 0x${commitPc.toString(16)}. Status code: $status")
      }
      currentVmPc = fromU32Int(ffi.vm_get_pc(vmHandle))
    }

    // Ensure we stopped exactly at the target PC and didn't overshoot
    if (currentVmPc != commitPc) {
      throw new RuntimeException(s"Golden model overshot the target PC! Expected: 0x${commitPc.toString(16)}, Got: 0x${currentVmPc.toString(16)}")
    }

    // Now, the golden model's PC is at the same instruction the DUT is about to commit.
    // Execute one final step to simulate this commit.
    val status = ffi.vm_step(vmHandle)
    if (status != 0) { // Not Running
      println(s"INFO: Golden model halted or errored on instruction at PC 0x${commitPc.toString(16)}. This may be the intended end of the program. Status: $status")
    }
  }

  /**
   * Makes the golden model execute one instruction.
   * Useful for simple, non-lock-step scenarios.
   */
  def step(): Unit = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    val status = ffi.vm_step(vmHandle)
    if (status != 0) { // 0 is VmStatus::Running
      val pc = fromU32Int(ffi.vm_get_pc(vmHandle))
      println(s"INFO: Golden model halted or errored. PC=0x${pc.toString(16)}, Status=$status")
    }
  }

  /** Retrieves all GPRs from the golden model.
    * @return An array of 32 `BigInt`s representing the GPRs.
    */
  def getAllGprs(): Array[BigInt] = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    val gprs = new Array[Int](32)
    ffi.vm_get_all_gprs(vmHandle, gprs)
    // **CONVERSION HAPPENS HERE**: From `Int` array to `BigInt` array for safe use in Scala.
    gprs.map(fromU32Int)
  }

  /** Retrieves the current PC from the golden model.
    */
  def getPc(): BigInt = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    fromU32Int(ffi.vm_get_pc(vmHandle))
  }

  // Expose vm_read_memory for the final memory check
  def readMemory(addr: BigInt, outData: Array[Byte]): Int = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    ffi.vm_read_memory(vmHandle, addr.toInt, outData, outData.length)
  }
}
