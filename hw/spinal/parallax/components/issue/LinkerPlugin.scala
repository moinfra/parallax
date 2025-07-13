// filename: hw/spinal/parallax/issue/LinkerPlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.execute.{EuBasePlugin, WakeupService}
import parallax.utilities._
import parallax.components.issue._ // 包含 IssueQueueComponent 和 IssueQueueService

/**
 * The LinkerPlugin is the "glue" that connects the Dispatch stage to the Issue Queues (IQs),
 * and the IQs to their corresponding Execution Units (EUs). It automates the instantiation
 * and wiring process based on discovered EU plugins.
 *
 * Its responsibilities include:
 * 1. Discovering all registered `EuBasePlugin` instances.
 * 2. For each EU, instantiating a matching `IssueQueueComponent`.
 * 3. Registering each new IQ with the `IssueQueueService` so the `DispatchPlugin` can find it.
 * 4. Wiring the output of each IQ to the input of its corresponding EU.
 * 5. Connecting global signals like wakeup and flush to all IQs.
 */
class LinkerPlugin(pCfg: PipelineConfig) extends Plugin with LockedImpl {

  // A helper case class to hold a pair of an EU and its IQ.
  // We use Any to avoid complex generic type issues while maintaining functionality.
  case class Connection(
      execUnit: EuBasePlugin, // The EU
      issueQueue: IssueQueueComponent[_ <: Bundle with IQEntryLike] // The IQ
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
      ParallaxLogger.log(s"LinkerPlugin: Registered IQ for EU '${eu.euName}' to handle UopCodes: [${eu.microOpsHandled.mkString(", ")}].")

      // Instantiate the generic IssueQueueComponent with the specific type and configuration derived from the EU.
      // The `.asInstanceOf` is unfortunate but necessary here to bridge the generic configuration creation
      // with the generic component instantiation. It's safe because we construct the config with the correct type.
      val iqComponent = new IssueQueueComponent(
        iqConfig = new IssueQueueConfig(
          pipelineConfig = pCfg,
          depth = iqDepth,
          exeUnitType = eu.getEuType,
          uopEntryType = eu.iqEntryType, // Directly use the HardType provided by the EU
          name = s"${eu.euName}_IQ"
        ).asInstanceOf[IssueQueueConfig[T_IQEntry forSome {type T_IQEntry <: Bundle with IQEntryLike}]],
        id = setup.allEus.indexOf(eu)
      )

      // Connect the port obtained from the service to the actual IQ hardware input.
      // Convert Stream to Flow for the allocateIn connection
      iqComponent.io.allocateIn << iqInputFromDispatch.toFlow

      // Create a `Connection` object that holds the EU and its corresponding IQ.
      Connection(eu, iqComponent)
    }

    // --- 2. Connect Global Signals (Wakeup & Flush) to all IQs ---
    val globalWakeupFlow = setup.wakeupService.getWakeupFlow()
    val globalFlushSignal = setup.issuePpl.pipeline.s3_dispatch(setup.issuePpl.signals.FLUSH_PIPELINE)

    for (conn <- connections) {
      conn.issueQueue.io.wakeupIn << globalWakeupFlow
      conn.issueQueue.io.flush := globalFlushSignal
    }
    ParallaxLogger.log("LinkerPlugin: Connected global wakeup and flush signals to all IQs.")
    ParallaxSim.logWhen(globalFlushSignal, L"LinkerPlugin: Global FLUSH_PIPELINE signal is asserted!")


    // --- 3. Connect each IQ's output to its corresponding EU's input ---
    for (conn <- connections) {
      // 调用EU自己的连接方法，不再需要在Linker中进行类型转换
      conn.execUnit.connectIssueQueue(conn.issueQueue.io.issueOut.asInstanceOf[Stream[Bundle with IQEntryLike]])
      
      ParallaxLogger.log(s"LinkerPlugin: Wired IQ '${conn.issueQueue.idStr}' output to EU '${conn.execUnit.euName}' input.")

      // Add simulation-time logging to trace the data flow between IQ and EU
      when(conn.execUnit.getEuInputPort.fire) {
        ParallaxSim.log(
          L"LinkerPlugin-Trace: Firing from IQ '${conn.issueQueue.idStr}' to EU '${conn.execUnit.euName}'. " :+
          L"RobPtr=${conn.execUnit.getEuInputPort.payload.robPtr}"
        )
      }
    }

    // --- 4. Finalization: Release all retained services ---
    setup.issuePpl.release()
    setup.iqService.release()
    setup.wakeupService.release()
    setup.allEus.foreach(_.release())
    ParallaxLogger.log("LinkerPlugin: Released all services. Wiring complete.")
  }
}
