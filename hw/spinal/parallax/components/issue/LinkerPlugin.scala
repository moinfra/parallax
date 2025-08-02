// filename: hw/spinal/parallax/issue/LinkerPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.{EuBasePlugin, WakeupService}
import parallax.utilities._
import parallax.components.issue._ // 确保这里引入了所有IQ相关的类和Trait

/**
 * The LinkerPlugin is the "glue" that connects the Dispatch stage to the Issue Queues (IQs),
 * and the IQs to their corresponding Execution Units (EUs). It automates the instantiation
 * and wiring process based on discovered EU plugins.
 *
 * Its responsibilities include:
 * 1. Discovering all registered `EuBasePlugin` instances.
 * 2. For each EU, instantiating a matching `IssueQueueComponent` (or `SequentialIssueQueueComponent` for MEM).
 * 3. Registering each new IQ with the `IssueQueueService` so the `DispatchPlugin` can find it.
 * 4. Wiring the output of each IQ to the input of its corresponding EU.
 * 5. Connecting global signals like wakeup and flush to all IQs.
 */
class LinkerPlugin(pCfg: PipelineConfig) extends Plugin with LockedImpl {
  val enableLog = false // Enable simulation-time logging for data flow between IQ and EU
  // A helper case class to hold a pair of an EU and its IQ.
  // We use Any to avoid complex generic type issues while maintaining functionality.
  // Note: While 'issueQueue' is typed as IssueQueueComponent, we will instantiate
  // SequentialIssueQueueComponent and then cast it to IssueQueueComponent for this case class.
  // This is safe because SequentialIssueQueueComponent extends IssueQueueComponent.
  case class Connection(
      execUnit: EuBasePlugin, // The EU
      issueQueue: IssueQueueLike[_ <: Bundle with IQEntryLike] // The IQ
  )

  // --- Early Stage: Discover all relevant services and plugins ---
  val setup = create early new Area {
    ParallaxLogger.log("LinkerPlugin: Early setup phase started.")

    // 1. Get singleton services that we will need to connect to.
    val issuePpl      = getService[IssuePipeline]
    val iqService     = getService[IssueQueueService]
    val wakeupService = getService[WakeupService]

    // 2. Discover all execution units that inherit from EuBasePlugin using the correct API.
    val allEus = getServicesOf[EuBasePlugin]
    if (allEus.isEmpty) {
      SpinalError("LinkerPlugin: No EuBasePlugin instances found. At least one execution unit must be registered in the framework.")
    }
    ParallaxLogger.log(s"LinkerPlugin: Discovered ${allEus.length} EuBasePlugins: ${allEus.map(_.euName).mkString(", ")}")

    // 3. Retain services to ensure they are not pruned by the hardware generator if they appear unused initially.
    issuePpl.retain()
    iqService.retain()
    wakeupService.retain()
    allEus.foreach(_.retain())
    ParallaxLogger.log("LinkerPlugin: All required services have been discovered and retained.")
  }

  // --- Late Stage: Instantiate IQs and perform all physical wiring ---
  val logic = create late new Area {
    lock.await() // Wait for all 'early' setup phases across all plugins to complete.
    ParallaxLogger.log("LinkerPlugin: Late logic phase started. Beginning wiring.")

    // Get the global wakeup bus before instantiating IQs.
    val allWakeupFlows = setup.wakeupService.getWakeupFlows()
    val numWakeupPorts = allWakeupFlows.length // This is the bus width
    ParallaxLogger.log(s"LinkerPlugin: Global wakeup bus has ${numWakeupPorts} ports.")

    // --- 1. Instantiate Issue Queues and create typed connections ---
    // We iterate through each discovered EU and create a corresponding IQ.
    // The result is a sequence of `Connection` objects, which preserves the type safety.
    val connections = setup.allEus.map { eu =>
      
      // Determine IQ depth based on EU type. This can be configured more flexibly if needed.
      val iqDepth = eu.getEuType match {
        case ExeUnitType.MEM     => 4 // LSU often benefits from a deeper queue
        case ExeUnitType.MUL_INT => 4
        case ExeUnitType.DIV_INT => 2
        case _                   => 4 // Default depth for ALU, BRU, etc.
      }
      
      ParallaxLogger.log(s"LinkerPlugin: Preparing to instantiate IQ for EU '${eu.euName}' (Type: ${eu.getEuType}) with depth ${iqDepth}.")

      // For each IQ, we must request an input channel from the central IssueQueueService.
      // The DispatchPlugin will use this channel to send uops to this specific IQ.
      val iqInputFromDispatch = setup.iqService.newIssueQueue(eu.microOpsHandled)
      ParallaxLogger.log(s"LinkerPlugin: Registered IQ for EU '${eu.euName}' to handle UopCodes: [${eu.microOpsHandled.mkString(", ")}]")

      // Instantiate the appropriate IssueQueueComponent based on EU type.
      // We use the IssueQueueLike trait to unify the interface, then cast for the Connection case class.
      val iqComponent: IssueQueueLike[_ <: Bundle with IQEntryLike] = eu.getEuType match {
        case ExeUnitType.MEM =>
          // For MEM type EUs, instantiate SequentialIssueQueueComponent
          new SequentialIssueQueueComponent(
            iqConfig = new IssueQueueConfig(
              pipelineConfig = pCfg,
              depth = iqDepth,
              exeUnitType = eu.getEuType,
              uopEntryType = eu.iqEntryType, // Directly use the HardType provided by the EU
              name = s"${eu.euName}_IQ"
            ).asInstanceOf[IssueQueueConfig[T_IQEntry forSome {type T_IQEntry <: Bundle with IQEntryLike}]],
            numWakeupPorts = numWakeupPorts,
            id = setup.allEus.indexOf(eu)
          )
        case _ =>
          // For all other EU types, instantiate the generic IssueQueueComponent
          new IssueQueueComponent(
            iqConfig = new IssueQueueConfig(
              pipelineConfig = pCfg,
              depth = iqDepth,
              exeUnitType = eu.getEuType,
              uopEntryType = eu.iqEntryType, // Directly use the HardType provided by the EU
              name = s"${eu.euName}_IQ"
            ).asInstanceOf[IssueQueueConfig[T_IQEntry forSome {type T_IQEntry <: Bundle with IQEntryLike}]],
            numWakeupPorts = numWakeupPorts,
            id = setup.allEus.indexOf(eu)
          )
      }

      // Connect the port obtained from the service to the actual IQ hardware input.
      // Convert Stream to Flow for the allocateIn connection
      iqComponent.io.allocateIn << iqInputFromDispatch

      // Create a `Connection` object. We safely cast iqComponent back to IssueQueueComponent
      // because SequentialIssueQueueComponent extends IssueQueueComponent and shares the same IO.
      Connection(eu, iqComponent.asInstanceOf[IssueQueueLike[_ <: Bundle with IQEntryLike]])
    }

    // --- 2. Connect Global Signals (Wakeup & Flush) to all IQs ---
    for (conn <- connections) {
      // Use := for vector-to-vector connection
      conn.issueQueue.io.wakeupIn := allWakeupFlows
    }
    ParallaxLogger.log("LinkerPlugin: Connected global multi-port wakeup bus to all IQs.")

    val flush = new Area {
      getServiceOption[HardRedirectService].foreach(hr => {
        val doHardRedirect = hr.doHardRedirect()
        for (conn <- connections) {
          conn.issueQueue.io.flush := doHardRedirect
        }
        if(enableLog) ParallaxSim.logWhen(doHardRedirect, L"LinkerPlugin: Global doHardRedirect signal is asserted!")
      })
    }

    // --- 3. Connect each IQ's output to its corresponding EU's input ---
    for (conn <- connections) {
      // Call the EU's own connection method; no need for type conversion in Linker
      conn.execUnit.connectIssueQueue(conn.issueQueue.io.issueOut.asInstanceOf[Stream[Bundle with IQEntryLike]])
      
      ParallaxLogger.log(s"LinkerPlugin: Wired IQ '${conn.issueQueue.idStr}' output to EU '${conn.execUnit.euName}' input.")

      // Add simulation-time logging to trace the data flow between IQ and EU
      when(conn.execUnit.getEuInputPort.fire) {
        if(enableLog) ParallaxSim.log(
          L"LinkerPlugin-Trace: Firing from IQ '${conn.issueQueue.idStr}' to EU '${conn.execUnit.euName}'. " :+
          L"RobPtr=${conn.execUnit.getEuInputPort.payload.robPtr}"
        )
      }
    }

    getServiceOption[DebugDisplayService].foreach(dbg => { 
      dbg.setDebugValueOnce(connections(0).execUnit.getEuInputPort.fire, DebugValue.ISSUE_FIRE, expectIncr = true)
    })

    // --- 4. Finalization: Release all retained services ---
    setup.issuePpl.release()
    setup.iqService.release()
    setup.wakeupService.release()
    setup.allEus.foreach(_.release())
    ParallaxLogger.log("LinkerPlugin: Released all services. Wiring complete.")
  }
}
