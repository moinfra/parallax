package boson.test.scala

import spinal.core._
import spinal.lib._
import spinal.sim._
import boson.demo2.components.rename._
import spinal.core.sim.SimDataPimper

class RenameMapTableTestBench(val config: RenameMapTableConfig) extends Component {
  val rat = new RenameMapTable(config)
  val dutIo = RenameMapTableIo(config)
  rat.io <> dutIo
  def internalMapState = rat.mapState.mapping // Continuously assign for observation
  rat.mapState.mapping.simPublic
}

class RenameMapTableSpec extends CustomSpinalSimFunSuite {
  import spinal.core.sim._

  val config = RenameMapTableConfig(
    numArchRegs = 4,
    physRegIdxWidth = 3 bits,
    numReadPorts = 2,
    archRegIdxWidth = 2 bits // log2Up(4)
  )

  test("RenameMapTable - mapState stability") {
    simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 43) { dutTb =>
      dutTb.clockDomain.forkStimulus(10)
      // No writes, no restores, minimal reads
      dutTb.dutIo.readPorts(0).archReg #= 0
      dutTb.dutIo.readPorts(1).archReg #= 0
      dutTb.dutIo.writePort.wen #= false
      dutTb.dutIo.checkpointRestore.valid #= false

      dutTb.clockDomain.waitSampling() // Initial reset
      val initialState = (0 until config.numArchRegs).map(idx => dutTb.rat.mapState.mapping(idx).toInt).toSeq
      println(s"Stability Test - Initial: ${initialState.mkString(", ")}")
      assert(initialState == Seq(0, 1, 2, 3))

      dutTb.clockDomain.waitSampling(5) // Wait a few cycles with no activity

      val stateAfterWait = (0 until config.numArchRegs).map(idx => dutTb.rat.mapState.mapping(idx).toInt).toSeq
      println(s"Stability Test - After Wait: ${stateAfterWait.mkString(", ")}")
      assert(stateAfterWait == Seq(0, 1, 2, 3), "mapState changed without explicit write/restore")
    }
  }

  test("RenameMapTable - Basic Operations (Helpers inside doSim)") {
    simConfig.compile(new RenameMapTableTestBench(config)).doSim(seed = 42) { dutTb =>
      // dutTb IS THE SIMULATION CONTEXT DUT

      // === Helper functions defined INSIDE the doSim block ===
      def readArchReg(portIdx: Int, archReg: Int): Unit = {
        dutTb.dutIo.readPorts(portIdx).archReg #= archReg
        sleep(1)
      }

      def expectPhysReg(portIdx: Int, expectedPhysReg: Int, msg: String = ""): Unit = {
        assert(
          dutTb.dutIo.readPorts(portIdx).physReg.toInt == expectedPhysReg,
          s"ReadPort $portIdx - $msg. Expected $expectedPhysReg, Got ${dutTb.dutIo.readPorts(portIdx).physReg.toInt}"
        )
      }

      def writeMap(wen: Boolean, archReg: Int, physReg: Int): Unit = {
        dutTb.dutIo.writePort.wen #= wen
        dutTb.dutIo.writePort.archReg #= archReg
        dutTb.dutIo.writePort.physReg #= physReg
        sleep(1)
      }

      def restoreCheckpoint(mapping: Seq[Int]): Unit = {
        dutTb.dutIo.checkpointRestore.valid #= true
        for (i <- mapping.indices) {
          dutTb.dutIo.checkpointRestore.payload.mapping(i) #= mapping(i)
        }
        sleep(1)
      }

      def clearRestore(): Unit = {
        dutTb.dutIo.checkpointRestore.valid #= false
        sleep(1)
      }

      def getInternalMapping(): Seq[Int] = {
        dutTb.internalMapState.map(_.toInt).toSeq
      }
      // === End of helper functions ===

      // Initialize IOs using helpers or direct assignment
      dutTb.clockDomain.forkStimulus(10)
      dutTb.dutIo.readPorts.foreach(_.archReg #= 0) // Initial read arch regs
      writeMap(wen = false, 0, 0) // Deassert write
      clearRestore() // Deassert restore
      dutTb.dutIo.checkpointSave.valid #= false // Not actively testing save driving
      dutTb.clockDomain.assertReset()
      sleep(10)
      dutTb.clockDomain.deassertReset()
      // Test initial state (driven by Reg init in DUT)
      dutTb.clockDomain.waitSampling() // Let DUT initialize
      println("Initial State (internal): " + getInternalMapping().mkString(", "))
      for (i <- 0 until config.numArchRegs) {
        readArchReg(0, i)
        dutTb.clockDomain.waitSampling(0)
        expectPhysReg(0, i, s"Initial read r$i")
      }

      // Test single write (r1 -> p5)
      println("\nTest Single Write (r1 -> p5):")
      writeMap(wen = true, archReg = 1, physReg = 5)
      dutTb.clockDomain.waitSampling() // Write takes effect on the next clock edge
      writeMap(wen = false, 0, 0) // Deassert write for subsequent cycles

      readArchReg(0, 1) // Check r1, should now be p5
      readArchReg(1, 2) // Check r2, should be initial p2
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(0, 5, "r1 after write")
      expectPhysReg(1, 2, "r2 unaffected by r1 write")
      println("Internal state after r1->p5: " + getInternalMapping().mkString(", "))

      // Test writing to r0's mapping (should be ignored by RAT's internal logic)
      println("\nTest Write to r0's mapping (r0 -> p6, should be ignored):")
      val r0InternalInitial = getInternalMapping().head // Should be 0 from init
      writeMap(wen = true, archReg = 0, physReg = 6)
      dutTb.clockDomain.waitSampling()
      writeMap(wen = false, 0, 0)

      readArchReg(0, 0) // Read r0
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(0, 0, "r0 should still read as p0 externally")
      val r0InternalAfterWriteAttempt = getInternalMapping().head
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
        config.numArchRegs + 0, // An arbitrary value for mapState(0) to test restore
        config.numArchRegs + 1,
        config.numArchRegs + 2,
        config.numArchRegs + 3
      )
      println(s"Checkpoint data to restore: ${checkpointData.mkString(", ")}")
      restoreCheckpoint(checkpointData)
      dutTb.clockDomain.waitSampling()
      clearRestore()
      println("Internal state after restore: " + getInternalMapping().mkString(", "))

      for (i <- 0 until config.numArchRegs) {
        readArchReg(0, i)
        dutTb.clockDomain.waitSampling(0)
        val expectedReadValue = if (i == 0) 0 else checkpointData(i) // r0 read is always p0
        expectPhysReg(0, expectedReadValue, s"Restored r$i read")
        assert(
          getInternalMapping()(i) == checkpointData(i),
          s"Internal state for mapState($i) mismatch post-restore. Expected ${checkpointData(i)}, got ${getInternalMapping()(i)}"
        )
      }

      // Test write after restore
      println("\nTest Write After Restore (r2 -> p7):")
      writeMap(wen = true, archReg = 2, physReg = 7)
      dutTb.clockDomain.waitSampling()
      writeMap(wen = false, 0, 0)

      readArchReg(0, 2) // r2 should now be p7
      readArchReg(1, 1) // r1 was restored to checkpointData(1)
      dutTb.clockDomain.waitSampling(0)
      expectPhysReg(0, 7, "r2 after restore & write")
      val expectedR1Read = if (1 == 0) 0 else checkpointData(1) // r0 special case
      expectPhysReg(1, expectedR1Read, "r1 after restore")
      println("Internal state after write post-restore: " + getInternalMapping().mkString(", "))
      assert(getInternalMapping()(2) == 7, "Internal state for r2 not p7 post-write")
      assert(getInternalMapping()(1) == checkpointData(1), "Internal state for r1 not checkpointData(1) post-write")
    }

    thatsAll()
  }
}
