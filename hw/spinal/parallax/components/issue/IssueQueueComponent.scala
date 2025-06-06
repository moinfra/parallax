package parallax.components.issue

import spinal.core._
import spinal.lib._
import parallax.common._
import parallax.utilities.ParallaxLogger

case class IssueQueueComponentIo[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry]
) extends Bundle
    with IMasterSlave {
  val allocateIn = Flow(iqConfig.getIQEntry())
  val canAccept = Bool()
  val issueOut = Stream(iqConfig.getIQEntry())
  val bypassIn = Vec(Flow(BypassMessage(iqConfig.pipelineConfig)), iqConfig.bypassNetworkSources)
  val flush = Bool()

  override def asMaster(): Unit = {
    master(allocateIn)
    out(bypassIn)
    out(flush)
    in(canAccept)
    slave(issueOut)
  }
}

class IssueQueueComponent[T_IQEntry <: Data with IQEntryLike](
    val iqConfig: IssueQueueConfig[T_IQEntry],
    val id: Int = 0
) extends Component {
  val io = slave(IssueQueueComponentIo(iqConfig))
  val idStr = s"${iqConfig.name}-${id.toString()}"

  val entries = Vec.fill(iqConfig.depth)(Reg(iqConfig.getIQEntry()))
  val entryValids = Vec.fill(iqConfig.depth)(Reg(Bool()) init (False))

  io.canAccept := OHMasking.first(entryValids.map(!_)).orR && !io.flush

  when(io.allocateIn.valid && io.canAccept && !io.flush) {
    val allocateIdx = OHToUInt(OHMasking.first(entryValids.map(!_)))
    entries(allocateIdx) := io.allocateIn.payload 
    // srcXReady fields in payload are initialized by its initFrom/setDefault
    entryValids(allocateIdx) := True
    report(L"${idStr}: Allocated entry at index ${allocateIdx}, RobPtr=${io.allocateIn.payload.robPtr}")
  }

  val entriesReadyToIssueComb = Vec(Bool(), iqConfig.depth)
  entriesReadyToIssueComb.foreach(_ := False)

  for (i <- 0 until iqConfig.depth) {
    val currentEntryReg = entries(i) // Registered state from start of cycle

    when(entryValids(i)) {
      val s1RegisteredReady = currentEntryReg.src1Ready
      val s2RegisteredReady = currentEntryReg.src2Ready
      val s3RegisteredReady =
        if (iqConfig.usesSrc3) currentEntryReg.asInstanceOf[IQEntryFpu].src3Ready
        else True // Assuming src3Ready is present

      val src1NeedsWakeup = currentEntryReg.useSrc1 && !s1RegisteredReady
      val s1WokenByBypassThisCycle = io.bypassIn.foldLeft(False) { (acc, bypassPort) =>
        val matches =
          currentEntryReg.src1Tag === bypassPort.payload.physRegIdx && currentEntryReg.src1IsFpr === bypassPort.payload.isFPR
        val canWake = bypassPort.valid && src1NeedsWakeup && matches
        when(canWake && !acc) {
          entries(i).src1Data := bypassPort.payload.physRegData
          entries(i).src1Ready := True // Update the register for next cycle
        }
        acc || canWake
      }
      val s1EffectivelyReadyThisCycle = s1RegisteredReady || s1WokenByBypassThisCycle

      val src2NeedsWakeup = currentEntryReg.useSrc2 && !s2RegisteredReady
      val s2WokenByBypassThisCycle = io.bypassIn.foldLeft(False) { (acc, bypassPort) =>
        val matches =
          currentEntryReg.src2Tag === bypassPort.payload.physRegIdx && currentEntryReg.src2IsFpr === bypassPort.payload.isFPR
        val canWake = bypassPort.valid && src2NeedsWakeup && matches
        when(canWake && !acc) {
          entries(i).src2Data := bypassPort.payload.physRegData
          entries(i).src2Ready := True // Update the register for next cycle
        }
        acc || canWake
      }
      val s2EffectivelyReadyThisCycle = s2RegisteredReady || s2WokenByBypassThisCycle

      val s3EffectivelyReadyThisCycle = if (iqConfig.usesSrc3) {
        val entryWithSrc3 = currentEntryReg.asInstanceOf[IQEntryFpu]
        val src3NeedsWakeup = entryWithSrc3.useSrc3 && !entryWithSrc3.src3Ready
        val s3WokenByBypassThisCycle = io.bypassIn.foldLeft(False) { (acc, bypassPort) =>
          val matches =
            entryWithSrc3.src3Tag === bypassPort.payload.physRegIdx && entryWithSrc3.src3IsFpr === bypassPort.payload.isFPR
          val canWake = bypassPort.valid && src3NeedsWakeup && matches
          when(canWake && !acc) {
            entries(i).asInstanceOf[IQEntryFpu].src3Data := bypassPort.payload.physRegData
            entries(i).asInstanceOf[IQEntryFpu].src3Ready := True // Update the register for next cycle
          }
          acc || canWake
        }
        s3RegisteredReady || s3WokenByBypassThisCycle
      } else {
        True
      }

      val useSrc1Comb = currentEntryReg.useSrc1
      val useSrc2Comb = currentEntryReg.useSrc2
      val useSrc3Comb = if (iqConfig.usesSrc3) currentEntryReg.asInstanceOf[IQEntryFpu].useSrc3 else False

      entriesReadyToIssueComb(i) := // This is the combinational ready signal
        (!useSrc1Comb || s1EffectivelyReadyThisCycle) &&
          (!useSrc2Comb || s2EffectivelyReadyThisCycle) &&
          (if (iqConfig.usesSrc3) !useSrc3Comb || s3EffectivelyReadyThisCycle else True)
    }
  }

  val readyToIssueMask = B(entriesReadyToIssueComb)
  val issueRequestOh = OHMasking.first(readyToIssueMask)
  val canIssue = issueRequestOh.orR
  val issueIdx = OHToUInt(issueRequestOh)

  val grantedIndexIsActuallyValid = if (iqConfig.depth > 0) entryValids(issueIdx) else False
  io.issueOut.valid := canIssue && grantedIndexIsActuallyValid && !io.flush
  io.issueOut.payload := entries(issueIdx) // Payload is simply the content of the selected entry

  val currentValidCount = CountOne(entryValids)

  when(io.issueOut.fire) {
    entryValids(issueIdx) := False
    report(L"${idStr}: Issued entry at index ${issueIdx}, RobPtr=${entries(issueIdx).robPtr}.")
  }

  when(io.flush) {
    entryValids.foreach(_ := False)
    report(L"${idStr}: FLUSHED. All entries invalidated.")
  }

  ParallaxLogger.log(
    s"${idStr} Component (depth ${iqConfig.depth}, bypass ${iqConfig.bypassNetworkSources.toString()}, type ${iqConfig.uopEntryType().getClass.getSimpleName}) elaborated."
  )

  if (GenerationFlags.simulation.isEnabled) {
    val simCycleCount = Reg(UInt(32 bits)) init (0)
    simCycleCount := simCycleCount + 1
    // report(
    //   L"${idStr} Cycle ${simCycleCount}: canAccept=${io.canAccept}, issueOut.fire=${io.issueOut.fire}, flush=${io.flush}, ValidCount(Reg)=${currentValidCount}"
    // )
  }
}

object IssueQueueComponent {
  def IntIQ(pipelineConfig: PipelineConfig, depth: Int, id: Int = 0): IssueQueueComponent[IQEntryAluInt] = {
    val iqConf = IssueQueueConfig[IQEntryAluInt](
      pipelineConfig = pipelineConfig,
      depth = depth,
      exeUnitType = ExeUnitType.ALU_INT,
      uopEntryType = HardType(IQEntryAluInt(pipelineConfig)),
      usesSrc3 = false,
      name = "IntIQ"
    )
    new IssueQueueComponent(iqConf, id)
  }

  def FpuIQ(
      pipelineConfig: PipelineConfig,
      depth: Int,
      usesSrc3: Boolean,
      id: Int = 0
  ): IssueQueueComponent[IQEntryFpu] = {
    val iqConf = IssueQueueConfig[IQEntryFpu](
      pipelineConfig = pipelineConfig,
      depth = depth,
      exeUnitType = ExeUnitType.FPU_ADD_MUL_CVT_CMP,
      uopEntryType = HardType(IQEntryFpu(pipelineConfig, usesSrc3)),
      usesSrc3 = usesSrc3,
      name = "FpuIQ"
    )
    new IssueQueueComponent(iqConf, id)
  }
}
