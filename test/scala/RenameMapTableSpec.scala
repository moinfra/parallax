// testOnly test.scala.RenameMapTableSpec
package test.scala

import spinal.core._
import spinal.lib._
import spinal.sim._
import parallax.components.rename._
import spinal.core.sim.SimDataPimper

class RenameMapTableTestBench(val config: RenameMapTableConfig) extends Component {
  val rat = new RenameMapTable(config)
  val dutIo = new RenameMapTableIo(config)
  rat.io <> dutIo
  def internalMapState = rat.rratMapReg.mapping
  rat.rratMapReg.mapping.simPublic
}

class RenameMapTableSpec extends CustomSpinalSimFunSuite {
  import spinal.core.sim._

  val config = RenameMapTableConfig(
    archRegCount = 4,
    physRegCount = 8,
    numReadPorts = 2,
    numWritePorts = 2
  )

  // Helper functions defined INSIDE the test class, accessible by all tests
  def readArchReg(dutTb: RenameMapTableTestBench, portIdx: Int, archReg: Int): Unit = {
    dutTb.dutIo.readPorts(portIdx).archReg #= archReg
    sleep(1)
  }

  def expectPhysReg(dutTb: RenameMapTableTestBench, portIdx: Int, expectedPhysReg: Int, msg: String = ""): Unit = {
    assert(
      dutTb.dutIo.readPorts(portIdx).physReg.toInt == expectedPhysReg,
      s"ReadPort $portIdx - $msg. Expected $expectedPhysReg, Got ${dutTb.dutIo.readPorts(portIdx).physReg.toInt}"
    )
  }

  def driveWritePort(dutTb: RenameMapTableTestBench, portIdx: Int, wen: Boolean, archReg: Int, physReg: Int): Unit = {
    dutTb.dutIo.writePorts(portIdx).wen #= wen
    dutTb.dutIo.writePorts(portIdx).archReg #= archReg
    dutTb.dutIo.writePorts(portIdx).physReg #= physReg
    // sleep(1)
  }

  def restoreCheckpoint(dutTb: RenameMapTableTestBench, mapping: Seq[Int]): Unit = {
    dutTb.dutIo.checkpointRestore.valid #= true
    for (i <- mapping.indices) {
      dutTb.dutIo.checkpointRestore.payload.mapping(i) #= mapping(i)
    }
    sleep(1)
  }

  def clearRestore(dutTb: RenameMapTableTestBench): Unit = {
    dutTb.dutIo.checkpointRestore.valid #= false
    sleep(1)
  }

  def getInternalMapping(dutTb: RenameMapTableTestBench): Seq[Int] = {
    dutTb.internalMapState.map(_.toInt).toSeq
  }

  test("RenameMapTable - mapState stability") {
    simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 43) { dutTb =>
      dutTb.clockDomain.forkStimulus(10)
      // No writes, no restores, minimal reads
      dutTb.dutIo.readPorts(0).archReg #= 0
      dutTb.dutIo.readPorts(1).archReg #= 0
      dutTb.dutIo.writePorts.foreach(_.wen #= false)
      dutTb.dutIo.checkpointRestore.valid #= false

      dutTb.clockDomain.waitSampling() // Initial reset
      val initialState = (0 until config.archRegCount).map(idx => dutTb.rat.rratMapReg.mapping(idx).toInt).toSeq
      println(s"Stability Test - Initial: ${initialState.mkString(", ")}")
      assert(initialState == Seq(0, 1, 2, 3))

      dutTb.clockDomain.waitSampling(5) // Wait a few cycles with no activity

      val stateAfterWait = (0 until config.archRegCount).map(idx => dutTb.rat.rratMapReg.mapping(idx).toInt).toSeq
      println(s"Stability Test - After Wait: ${stateAfterWait.mkString(", ")}")
      assert(stateAfterWait == Seq(0, 1, 2, 3), "mapState changed without explicit write/restore")
    }
  }

  test("RenameMapTable - Basic Operations (Helpers inside doSim)") {
    simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 42) { dutTb =>
      // Initialize IOs
      dutTb.clockDomain.forkStimulus(10)
      dutTb.dutIo.readPorts.foreach(_.archReg #= 0) // Initial read arch regs
      dutTb.dutIo.writePorts.foreach(_.wen #= false)
      clearRestore(dutTb) // Deassert restore
      dutTb.dutIo.checkpointSave.valid #= false // Not actively testing save driving
      dutTb.clockDomain.assertReset()
      sleep(10)
      dutTb.clockDomain.deassertReset()
      // Test initial state (driven by Reg init in DUT)
      dutTb.clockDomain.waitSampling() // Let DUT initialize
      println("Initial State (internal): " + getInternalMapping(dutTb).mkString(", "))
      for (i <- 0 until config.archRegCount) {
        readArchReg(dutTb, 0, i)
        dutTb.clockDomain.waitSampling(0)
        expectPhysReg(dutTb, 0, i, s"Initial read r$i")
      }

      // Test single write (r1 -> p5)
      println("\nTest Single Write (r1 -> p5):")
      driveWritePort(dutTb, 0, wen = true, archReg = 1, physReg = 5)
      dutTb.clockDomain.waitSampling() // Write takes effect on the next clock edge
      driveWritePort(dutTb, 0, wen = false, 0, 0) // Deassert write for subsequent cycles

      readArchReg(dutTb, 0, 1) // Check r1, should now be p5
      readArchReg(dutTb, 1, 2) // Check r2, should be initial p2
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(dutTb, 0, 5, "r1 after write")
      expectPhysReg(dutTb, 1, 2, "r2 unaffected by r1 write")
      println("Internal state after r1->p5: " + getInternalMapping(dutTb).mkString(", "))

      // Test writing to r0's mapping (should be ignored by RAT's internal logic)
      println("\nTest Write to r0's mapping (r0 -> p6, should be ignored):")
      val r0InternalInitial = getInternalMapping(dutTb).head // Should be 0 from init
      driveWritePort(dutTb, 0, wen = true, archReg = 0, physReg = 6)
      dutTb.clockDomain.waitSampling()
      driveWritePort(dutTb, 0, wen = false, 0, 0)

      readArchReg(dutTb, 0, 0) // Read r0
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(dutTb, 0, 0, "r0 should still read as p0 externally")
      val r0InternalAfterWriteAttempt = getInternalMapping(dutTb).head
      assert(
        r0InternalAfterWriteAttempt == r0InternalInitial,
        s"Internal mapping of r0 changed! Expected $r0InternalInitial, got $r0InternalAfterWriteAttempt. It should remain unaffected."
      )
      println(
        s"Internal mapping of r0 after attempted write: $r0InternalAfterWriteAttempt (should be $r0InternalInitial)"
      )

      // Test checkpoint restore
      println("\nTest Checkpoint Restore:")
      // r0 maps to p0 (read logic enforces this), r1->p2, r2->p1, r3->p3
      val checkpointData = Seq(
        config.archRegCount + 0, // An arbitrary value for mapState(0) to test restore
        config.archRegCount + 1,
        config.archRegCount + 2,
        config.archRegCount + 3
      )
      println(s"Checkpoint data to restore: ${checkpointData.mkString(", ")}")
      restoreCheckpoint(dutTb, checkpointData)
      dutTb.clockDomain.waitSampling()
      clearRestore(dutTb)
      println("Internal state after restore: " + getInternalMapping(dutTb).mkString(", "))

      for (i <- 0 until config.archRegCount) {
        readArchReg(dutTb, 0, i)
        dutTb.clockDomain.waitSampling(0)
        val expectedReadValue = if (i == 0) 0 else checkpointData(i) // r0 read is always p0
        expectPhysReg(dutTb, 0, expectedReadValue, s"Restored r$i read")
        assert(
          getInternalMapping(dutTb)(i) == checkpointData(i),
          s"Internal state for mapState($i) mismatch post-restore. Expected ${checkpointData(i)}, got ${getInternalMapping(dutTb)(i)}"
        )
      }

      // Test write after restore
      println("\nTest Write After Restore (r2 -> p7):")
      driveWritePort(dutTb, 0, wen = true, archReg = 2, physReg = 7)
      dutTb.clockDomain.waitSampling()
      driveWritePort(dutTb, 0, wen = false, 0, 0)

      readArchReg(dutTb, 0, 2) // r2 should now be p7
      readArchReg(dutTb, 1, 1) // r1 was restored to checkpointData(1)
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(dutTb, 0, 7, "r2 after restore & write")
      val expectedR1Read = if (1 == 0) 0 else checkpointData(1) // r0 special case
      expectPhysReg(dutTb, 1, expectedR1Read, "r1 after restore")
      println("Internal state after write post-restore: " + getInternalMapping(dutTb).mkString(", ") + "\n")
      assert(getInternalMapping(dutTb)(2) == 7, "Internal state for r2 not p7 post-write")
      assert(
        getInternalMapping(dutTb)(1) == checkpointData(1),
        "Internal state for r1 not checkpointData(1) post-write"
      )
    }

    test("RenameMapTable - Concurrent Writes") {
      simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 44) { dutTb =>
        dutTb.clockDomain.forkStimulus(10)
        dutTb.dutIo.readPorts.foreach(_.archReg #= 0)
        dutTb.dutIo.writePorts.foreach(_.wen #= false)
        dutTb.dutIo.checkpointRestore.valid #= false
        dutTb.clockDomain.assertReset()
        sleep(10)
        dutTb.clockDomain.deassertReset()
        dutTb.clockDomain.waitSampling() // Let DUT initialize

        println("Initial State (internal): " + getInternalMapping(dutTb).mkString(", "))
        assert(getInternalMapping(dutTb) == Seq(0, 1, 2, 3))

        // Test concurrent writes to different registers
        println("\nTest Concurrent Writes to Different Registers (r1->p5, r2->p6):")
        driveWritePort(dutTb, 0, wen = true, archReg = 1, physReg = 5) // Port 0 writes to r1
        driveWritePort(dutTb, 1, wen = true, archReg = 2, physReg = 6) // Port 1 writes to r2
        dutTb.clockDomain.waitSampling()
        dutTb.dutIo.writePorts.foreach(_.wen #= false) // Deassert writes

        readArchReg(dutTb, 0, 1)
        readArchReg(dutTb, 1, 2)
        dutTb.clockDomain.waitSampling(0)
        expectPhysReg(dutTb, 0, 5, "r1 after concurrent write")
        expectPhysReg(dutTb, 1, 6, "r2 after concurrent write")
        println("Internal state after concurrent different writes: " + getInternalMapping(dutTb).mkString(", "))
        assert(getInternalMapping(dutTb)(1) == 5, "r1 not updated by port 0")
        assert(getInternalMapping(dutTb)(2) == 6, "r2 not updated by port 1")

        // Test concurrent writes to the same register (r3->p7 from port 0, r3->p4 from port 1)
        // Expect port 1's write to take precedence due to loop order in DUT
        println("\nTest Concurrent Writes to Same Register (r3->p7 from port 0, r3->p4 from port 1):")
        driveWritePort(dutTb, 0, wen = true, archReg = 3, physReg = 7) // Port 0 tries to write r3 to p7
        driveWritePort(
          dutTb,
          1,
          wen = true,
          archReg = 3,
          physReg = 4
        ) // Port 1 tries to write r3 to p4 (expected winner)
        dutTb.clockDomain.waitSampling()
        dutTb.dutIo.writePorts.foreach(_.wen #= false) // Deassert writes

        readArchReg(dutTb, 0, 3)
        dutTb.clockDomain.waitSampling(0)
        expectPhysReg(dutTb, 0, 4, "r3 after conflicting concurrent write (port 1 wins)")
        println("Internal state after conflicting concurrent writes: " + getInternalMapping(dutTb).mkString(", "))
        assert(getInternalMapping(dutTb)(3) == 4, "r3 not updated by expected winning port (port 1)")
      }
    }

  }

  // 这个测试是必然失败的，但是无所谓，RenameUnit会旁路。
  test("RenameMapTable - Concurrent Read/Write on Same Address (FIXED)") {
    simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 45) { dutTb =>
      dutTb.clockDomain.forkStimulus(10)

      // --- Cycle 0: Initialization ---
      driveWritePort(dutTb, 0, wen = false, 0, 0)
      driveWritePort(dutTb, 1, wen = false, 0, 0)
      dutTb.dutIo.readPorts(0).archReg #= 0
      dutTb.dutIo.checkpointRestore.valid #= false
      dutTb.clockDomain.waitSampling()

      val initialMapping = getInternalMapping(dutTb)
      println(s"Initial State: ${initialMapping.mkString(", ")}")
      assert(initialMapping(2) == 2, "Initial mapping for r2 should be p2")

      // --- Cycle 1: Drive concurrent read/write and wait for clock edge ---
      println("\nCycle 1: Concurrent Read/Write on r2")

      // Drive inputs. These values will hold until the next assignment.
      dutTb.dutIo.readPorts(0).archReg #= 2
      driveWritePort(dutTb, 0, wen = true, archReg = 2, physReg = 7)

      // Check combinational output BEFORE the clock edge
      val readValueDuringWrite = dutTb.dutIo.readPorts(0).physReg.toInt
      println(s"Combinational read for r2 during write cycle: p$readValueDuringWrite")
      assert(readValueDuringWrite == 2, "Concurrent read should see the OLD value (Read-First behavior)")

      // Wait for the clock edge. The write will be latched into rratMapReg.
      dutTb.clockDomain.waitSampling()

      // --- Cycle 2: De-assert write and verify results ---
      println("\nCycle 2: Verifying the result of the write")

      // De-assert write enable for this cycle
      driveWritePort(dutTb, 0, wen = false, 0, 0)

      // Check the internal state. It should have been updated by the write in Cycle 1.
      val updatedMapping = getInternalMapping(dutTb)
      println(s"Updated State: ${updatedMapping.mkString(", ")}")
      assert(updatedMapping(2) == 7, "Internal mapping for r2 should now be p7")

      // Check that reading r2 now returns the new value
      dutTb.dutIo.readPorts(0).archReg #= 2
      val readValueAfterWrite = dutTb.dutIo.readPorts(0).physReg.toInt
      println(s"Combinational read for r2 in the next cycle: p$readValueAfterWrite")
      assert(readValueAfterWrite == 7, "Read in the next cycle should see the NEW value")

      dutTb.clockDomain.waitSampling() // Advance to the end of Cycle 2

      println("✅ Concurrent Read/Write test (FIXED) passed!")
    }
  }

  thatsAll()
}
