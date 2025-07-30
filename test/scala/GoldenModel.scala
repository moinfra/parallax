package test.scala

import com.sun.jna.{Library, Native, Pointer, NativeLibrary}

// Helper enum for VM status (from C header)
// This should be in a shared file if possible, or defined carefully
object CVmStatus extends Enumeration {
  type CVmStatus = Value
  val Running = Value(0, "Running")
  val Idle = Value(1, "Idle")
  val Halted = Value(2, "Halted")
  val Error = Value(-1, "Error")

  // Helper to convert Int from C to Scala enum
  def fromInt(value: Int): CVmStatus = value match {
    case 0 => Running
    case 1 => Idle
    case 2 => Halted
    case -1 => Error
    case _ => throw new IllegalArgumentException(s"Unknown CVmStatus value: $value")
  }
}


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
  def vm_step(handle: Pointer): Int // This returns the *new* status after the step
  def vm_get_pc(handle: Pointer): Int
  def vm_get_gpr(handle: Pointer, index: Int): Int
  def vm_get_all_gprs(handle: Pointer, out_gprs: Array[Int]): Unit
  def vm_get_cycle_count(handle: Pointer): Long
  def vm_read_memory(handle: Pointer, addr: Int, out_data: Array[Byte], size: Long): Int
  def vm_get_status(handle: Pointer): Int // Added to explicitly query status
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
   * @return The status of the VM after executing the `commitPc` instruction.
   */
  def stepUntil(commitPc: BigInt): CVmStatus.Value = { // Return CVmStatus now
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")

    var currentVmPc = fromU32Int(ffi.vm_get_pc(vmHandle))
    var currentVmStatus = getStatus() // Get initial status

    // Error if golden model is somehow ahead of the DUT or already errored
    if (currentVmStatus == CVmStatus.Error) {
      throw new RuntimeException(s"Golden model was already in ERROR state at PC 0x${currentVmPc.toString(16)}.")
    }
    if (currentVmPc > commitPc) {
      throw new RuntimeException(s"Golden model is ahead of DUT! Golden PC: 0x${currentVmPc.toString(16)}, DUT Commit PC: 0x${commitPc.toString(16)}")
    }

    // Loop execution until golden model's PC matches DUT's commit PC
    // This loop synchronizes the model to the state *before* the commit instruction is executed.
    while (currentVmPc < commitPc) {
      currentVmStatus = CVmStatus.fromInt(ffi.vm_step(vmHandle)) // Execute one step
      currentVmPc = fromU32Int(ffi.vm_get_pc(vmHandle))

      if (currentVmStatus != CVmStatus.Running) {
        // If VM halted or errored *before* reaching commitPc, it's an unexpected early stop
        // unless it's a planned halt (which should ideally be at haltPc)
        if (currentVmStatus == CVmStatus.Halted) {
          throw new RuntimeException(s"Golden model halted unexpectedly early at PC 0x${currentVmPc.toString(16)} (before reaching 0x${commitPc.toString(16)}). Status: ${currentVmStatus}")
        } else { // Error or other non-running status
          throw new RuntimeException(s"Golden model errored unexpectedly at PC 0x${currentVmPc.toString(16)} before reaching target PC 0x${commitPc.toString(16)}. Status: ${currentVmStatus}")
        }
      }
    }

    // At this point, currentVmPc == commitPc.
    // Execute one final step to simulate the DUT committing this instruction.
    currentVmStatus = CVmStatus.fromInt(ffi.vm_step(vmHandle)) // Execute the instruction at commitPc
    // The currentVmPc has now advanced or remained the same if it was a halt.
    
    // We explicitly return the status, letting the caller decide if Halted is acceptable.
    currentVmStatus
  }

  /**
   * Makes the golden model execute one instruction.
   * Useful for simple, non-lock-step scenarios.
   * @return The status of the VM after the step.
   */
  def step(): CVmStatus.Value = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    val status = CVmStatus.fromInt(ffi.vm_step(vmHandle))
    if (status != CVmStatus.Running) {
      val pc = fromU32Int(ffi.vm_get_pc(vmHandle))
      println(s"INFO: Golden model halted or errored. PC=0x${pc.toString(16)}, Status=$status")
    }
    status
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

  /** Retrieves the current status of the golden model.
    */
  def getStatus(): CVmStatus.Value = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    CVmStatus.fromInt(ffi.vm_get_status(vmHandle))
  }

  // Expose vm_read_memory for the final memory check
  def readMemory(addr: BigInt, outData: Array[Byte]): Int = {
    if (vmHandle == null) throw new IllegalStateException("GoldenModel not initialized.")
    ffi.vm_read_memory(vmHandle, addr.toInt, outData, outData.length)
  }
}
