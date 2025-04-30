package boson

// File: SimpleBus.scala
import spinal.core._
import spinal.lib._

// 1. Define a simple Bundle with Master/Slave capabilities
case class SimpleBus() extends Bundle with IMasterSlave {
  val req = Bool()  // Request signal (Master -> Slave)
  val ack = Bool()  // Acknowledge signal (Slave -> Master)

  // Define directions from Master perspective
  override def asMaster(): Unit = {
    out(req) // Master drives req
    in(ack)  // Master receives ack
  }
  // Default 'slave' directions: in(req), out(ack)
}


// File: Components.scala
import spinal.core._
import spinal.lib._

// 2. Define a Slave component
class SimpleSlave extends Component {
  val io = new Bundle {
    val bus = slave(SimpleBus()) // Declare as Slave interface
  }
  // Simple logic: Always acknowledge requests
  io.bus.ack := io.bus.req
}

// 3. Define the TopLevel component demonstrating the issue
class TopLevelBug extends Component {
  // --- Scenario that works: Connecting component instances ---
  val masterComp = new Component { // Inline master component for simplicity
      val io = new Bundle {
          val bus = master(SimpleBus())
      }
  }.setName("masterComp")
  val slaveComp = new SimpleSlave().setName("slaveComp")

  // This connection works because both 'masterComp.io.bus' and 'slaveComp.io.bus'
  // are ports of child components relative to TopLevelBug.
  // dirSolve flips both, resulting in matching (in, out) and (out, in) normalized pairs.
  masterComp.io.bus <> slaveComp.io.bus
  masterComp.io.bus.req := True // Drive the working master

  println("-" * 30)
  println("Attempting connection that triggers the bug:")
  println("-" * 30)

  // --- Scenario that FAILS: Connecting local master to child slave ---
  // Create a master bus interface directly within TopLevelBug
  val localMasterBus = master(SimpleBus()).setName("localMasterBus")

  // Instantiate a slave component (child of TopLevelBug)
  val childSlaveComp = new SimpleSlave().setName("childSlaveComp")

  // Drive the local master bus
  localMasterBus.req := True

  // *** This connection is expected to fail ***
  // localMasterBus is local (dirSolve doesn't flip)
  // childSlaveComp.io.bus is child (dirSolve flips)
  // Results in comparing (out, out) for req and (in, in) for ack -> Mismatch Error
  try {
      localMasterBus <> childSlaveComp.io.bus
      println("BUG NOT REPRODUCED (Connection succeeded unexpectedly)")
  } catch {
      case e: Throwable =>
          println(s"BUG REPRODUCED: Connection failed as expected.")
          // Optionally re-throw or just print stack trace for info
          // e.printStackTrace()
          // NOTE: In a real build, SpinalHDL's LocatedPendingError would halt compilation.
          // The try-catch here is just for demonstration within a runnable context
          // if we were to adapt this further (e.g. into a test case).
          // For simple generation, the compiler will just error out.
  }
}


// File: GenerateBug.scala
import spinal.core._

object GenerateBug {
  def main(args: Array[String]): Unit = {
    println("Generating TopLevelBug...")
    try {
      SpinalConfig(
        targetDirectory = "rtl_bug",
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT)
      ).generateVerilog(new TopLevelBug)
      println("Generation finished (BUG MIGHT NOT HAVE BEEN TRIGGERED if try/catch swallowed it).")
      println("Check compiler output for 'AUTOCONNECT FAILED' errors.")
    } catch {
        case e : Throwable =>
            println("Generation failed due to error (likely the intended bug).")
            // e.printStackTrace() // Uncomment for full stack trace
    }
  }
}
