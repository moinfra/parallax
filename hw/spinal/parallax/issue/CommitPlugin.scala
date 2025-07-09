package parallax.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.components.rob.{ROBService, FlushReason}
import parallax.components.rename.{RatCheckpoint, SuperScalarFreeListCheckpoint}
import parallax.utilities.{Plugin, Service, ParallaxLogger}

/**
 * Service interface for commit stage operations.
 * Provides control over commit enable/disable and commit status information.
 */
trait CommitService extends Service {
  /**
   * Enable or disable commit operations.
   * When disabled, no instructions will be committed from ROB.
   */
  def setCommitEnable(enable: Bool): Unit
  
  /**
   * Get current commit enable status.
   */
  def getCommitEnable(): Bool
  
  /**
   * Get commit progress information for debugging/monitoring.
   */
  def getCommitStats(): CommitStats
}

/**
 * Commit statistics bundle for monitoring commit stage performance.
 */
case class CommitStats(pCfg: PipelineConfig) extends Bundle {
  val committedThisCycle = UInt(log2Up(pCfg.commitWidth + 1) bits)
  val totalCommitted = UInt(32 bits)
  val robFlushCount = UInt(32 bits)
  val physRegRecycled = UInt(32 bits)
}

/**
 * CommitPlugin implements the commit stage of the out-of-order processor.
 * 
 * Key responsibilities:
 * 1. In-order commit from ROB head
 * 2. Physical register recycling to FreeList
 * 3. Branch misprediction recovery via checkpoints
 * 4. Exception handling coordination
 * 5. Commit width and ordering management
 * 
 * Architecture Integration:
 * - Consumes from: ROB commit slots
 * - Produces to: FreeList recycling, Checkpoint management
 * - Coordinates with: Branch prediction unit, Exception handler
 */
class CommitPlugin(
    val pipelineConfig: PipelineConfig
) extends Plugin with CommitService {
  
  val enableLog = true
  
  // Service interface state
  private val commitEnable = Bool()
  private val commitStats = Reg(CommitStats(pipelineConfig))
  
  override def setCommitEnable(enable: Bool): Unit = {
    commitEnable := enable
  }
  
  override def getCommitEnable(): Bool = commitEnable
  
  override def getCommitStats(): CommitStats = commitStats
  
  val early_setup = create early new Area {
    // Get required services
    val robService = getService[ROBService[RenamedUop]]
    val ratControlService = getService[RatControlService]
    val flControlService = getService[FreeListControlService]
    
    if (enableLog) {
      ParallaxLogger.log(s"CommitPlugin: Early setup - acquired services")
    }
  }
  
  val logic = create late new Area {
    val robService = early_setup.robService
    val ratControlService = early_setup.ratControlService
    val flControlService = early_setup.flControlService
    
    if (enableLog) {
      ParallaxLogger.log(s"CommitPlugin: Logic area - starting commit implementation")
    }
    
    // === ROB Interface ===
    val commitSlots = robService.getCommitSlots(pipelineConfig.commitWidth)
    val commitAcks = robService.getCommitAcks(pipelineConfig.commitWidth)
    val robFlushPort = robService.getFlushPort()
    
    // === Physical Register Recycling ===
    val freePorts = flControlService.getFreePorts()
    require(freePorts.length >= pipelineConfig.commitWidth, 
      s"FreeList must have at least ${pipelineConfig.commitWidth} free ports for commit width")
    
    // === Commit Logic ===
    val committedThisCycle = UInt(log2Up(pipelineConfig.commitWidth + 1) bits)
    val commitCount = UInt(log2Up(pipelineConfig.commitWidth + 1) bits)
    
    // Count how many instructions can be committed this cycle
    val canCommitVec = Vec(Bool(), pipelineConfig.commitWidth)
    for (i <- 0 until pipelineConfig.commitWidth) {
      canCommitVec(i) := commitSlots(i).valid && commitEnable
    }
    
    // In-order commit: stop at first non-committable instruction
    val commitMask = Vec(Bool(), pipelineConfig.commitWidth)
    commitMask(0) := canCommitVec(0)
    for (i <- 1 until pipelineConfig.commitWidth) {
      commitMask(i) := canCommitVec(i) && commitMask(i-1)
    }
    
    // Generate commit acknowledgments and handle register recycling
    for (i <- 0 until pipelineConfig.commitWidth) {
      val doCommit = commitMask(i)
      
      when(doCommit) {
        val committedUop = commitSlots(i).entry.payload.uop
        
        // Recycle old physical register if instruction allocated a new one
        when(committedUop.rename.allocatesPhysDest) {
          freePorts(i).enable := True
          freePorts(i).physReg := committedUop.rename.oldPhysDest.idx
          
          if (enableLog) {
            report(L"[COMMIT] Recycling physical register p${committedUop.rename.oldPhysDest.idx} from committed instruction")
          }
        } otherwise {
          freePorts(i).enable := False
          freePorts(i).physReg := 0
        }
        
        if (enableLog) {
          report(L"[COMMIT] Committed instruction at ROB slot ${i}: robPtr=${committedUop.robPtr}, physDest=${committedUop.rename.physDest.idx}")
        }
      } otherwise {
        freePorts(i).enable := False
        freePorts(i).physReg := 0
      }
    }
    
    // Count committed instructions this cycle
    commitCount := CountOne(commitMask)
    committedThisCycle := commitCount
    
    // Drive commit acknowledgments to ROB
    for (i <- 0 until pipelineConfig.commitWidth) {
      commitAcks(i) := commitMask(i)
      
      if (enableLog) {
        when(commitSlots(i).valid || commitEnable.rise() || commitMask(i)) {
          report(L"[COMMIT] ACK[${i}]: slotValid=${commitSlots(i).valid}, commitEnable=${commitEnable}, canCommit=${canCommitVec(i)}, commitMask=${commitMask(i)}")
        }
      }
    }
    
    // === Statistics and Monitoring ===
    when(commitCount > 0) {
      commitStats.totalCommitted := commitStats.totalCommitted + commitCount.resized
      commitStats.physRegRecycled := commitStats.physRegRecycled + CountOne(Vec(freePorts.map(_.enable))).resized
    }
    
    commitStats.committedThisCycle := committedThisCycle
    commitStats.robFlushCount := 0  // Initialize to 0 - ROB flushes are handled by other components
    
    if (enableLog) {
      when(commitCount > 0) {
        report(L"[COMMIT] Committed ${commitCount} instructions this cycle")
      }
    }
    
    if (enableLog) {
      ParallaxLogger.log(s"CommitPlugin: Logic area complete - commit pipeline active")
    }
  }
}