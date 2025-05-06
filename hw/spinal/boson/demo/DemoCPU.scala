package boson.demo

import spinal.core._
// REMOVED: import spinal.core.fiber.{Handle, Lock, hardFork} // hardFork is removed
import spinal.core.fiber.{Handle, Lock} // Keep Lock if needed, Handle might be unused now
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.misc.pipeline.CtrlLink // For CtrlLink legacy, might not be needed directly now
import spinal.lib.StreamArbiterFactory
// Using specific imports where clarity helps
import spinal.lib.pipeline.Stage
import spinal.lib.pipeline.Pipeline
import spinal.lib.fsm._ // If needed for state machines, though not used here yet

import scala.collection.mutable.ArrayBuffer
import boson.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S

// ==========================================================================
// == Stack Component (NO CHANGES NEEDED HERE, Original code seems correct) ==
// ==========================================================================
case class StackConfig()

case class Stack[T <: Data](
    dataType: HardType[T],
    depth: Int,
    config: StackConfig = StackConfig()
) extends Component {

  require(depth > 0, "Stack depth must be positive")

  val io = new Bundle {
    val push = slave Stream (dataType())
    val pop = master Stream (dataType())
    val flush = in Bool () init (False)
    val occupancy = out UInt (log2Up(depth + 1) bits)
    val availability = out UInt (log2Up(depth + 1) bits)
  }

  val ram = Mem(dataType, wordCount = depth)
  ram.addAttribute("ram_style", "block")

  val ptr = Reg(UInt(log2Up(depth + 1) bits)) init (0)
  ptr.addAttribute("no_init") // Avoid Quartus warning if init is handled by reset

  val isEmpty = ptr === 0
  val isFull = ptr === U(depth)

  val pushing = io.push.fire
  val popping = io.pop.fire

  io.push.ready := !isFull
  io.pop.valid := !isEmpty

  when(pushing =/= popping) {
    when(pushing) { ptr := ptr + 1 }
    when(popping) { ptr := ptr - 1 }
  }

  when(io.flush) {
    ptr := 0
  }

  val ramAddrWidth = log2Up(depth)
  ram.write(
    address = ptr.resize(ramAddrWidth),
    data = io.push.payload,
    enable = pushing
  )

  io.pop.payload := ram.readSync(
    address = (ptr - 1).resize(ramAddrWidth),
    enable = !isEmpty // Read speculatively when not empty
  )

  io.occupancy := ptr
  val depth_resized = U(depth, ptr.getWidth bits)
  io.availability := depth_resized - ptr
}

object Stack {
  def apply[T <: Data](dataType: HardType[T], depth: Int): Stack[T] = {
    new Stack(dataType, depth)
  }
}

// ==========================================================================
// == Configuration == (NO CHANGES)
// ==========================================================================
object RenameConfig {
  val ARCH_REG_COUNT = 32
  val XLEN = 32
  val ARCH_REG_WIDTH = log2Up(ARCH_REG_COUNT) bits
  val PHYS_REG_COUNT = 64
  val PHYS_REG_TAG_WIDTH = log2Up(PHYS_REG_COUNT) bits
  val R0_PHYS_TAG = U(0, PHYS_REG_TAG_WIDTH)
}

// ==========================================================================
// == Data Structures == (NO CHANGES)
// ==========================================================================
object OpType extends SpinalEnum {
  val NOP, ALU, MUL = newElement()
}

case class MicroOp() extends Bundle {
  val opType = OpType()
  val rd = UInt(RenameConfig.ARCH_REG_WIDTH)
  val rj = UInt(RenameConfig.ARCH_REG_WIDTH)
  val rk = UInt(RenameConfig.ARCH_REG_WIDTH)
  val imm = SInt(16 bits)
  val pc = UInt(RenameConfig.XLEN bits)
  val fault = Bool()

  def writesArf(): Bool = (opType === OpType.ALU || opType === OpType.MUL) && rd =/= 0
}

object FrontendPipelineData extends AreaObject {
  val PC = Stageable(UInt(RenameConfig.XLEN bits))
  val INSTRUCTION = Stageable(Bits(RenameConfig.XLEN bits))
  val FETCH_FAULT = Stageable(Bool())
  val UOP = Stageable(MicroOp())
  val RENAMED_UOP = Stageable(RenamedMicroOp())
}

case class RenamedMicroOp() extends Bundle {
  val uop = MicroOp()
  val pDest = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
  val pSrc1 = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
  val pSrc2 = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
  val archDest = UInt(RenameConfig.ARCH_REG_WIDTH)
  val stalePDest = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
}

object ExecutionUnitData extends AreaObject {
  val RENAMED_UOP = Stageable(RenamedMicroOp())
  val SRC1_VALUE = Stageable(Bits(RenameConfig.XLEN bits))
  val SRC2_VALUE = Stageable(Bits(RenameConfig.XLEN bits))
  val WRITEBACK_VALUE = Stageable(Bits(RenameConfig.XLEN bits))
  val WRITEBACK_ENABLE = Stageable(Bool())
}

case class CommitEntry() extends Bundle {
  val uop = MicroOp()
  val writebackValue = Bits(RenameConfig.XLEN bits)
  val writebackEnable = Bool()
  val fault = Bool()
  val pDest = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
  val archDest = UInt(RenameConfig.ARCH_REG_WIDTH)
  val stalePDest = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
}

case class WakeupPortPayload() extends Bundle {
  val physicalTag = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
}

case class IssueSlotPayload() extends Bundle with IMasterSlave {
  val valid = Bool()
  val renamedUop = RenamedMicroOp()
  val pSrc1Ready = Bool()
  val pSrc2Ready = Bool()

  override def asMaster(): Unit = { out(valid, renamedUop, pSrc1Ready, pSrc2Ready) }
  override def asSlave(): Unit = { in(valid, renamedUop, pSrc1Ready, pSrc2Ready) }
}

// ==========================================================================
// == Services == (NO CHANGES)
// ==========================================================================
case class PrfReadPort() extends Bundle with IMasterSlave {
  val cmd = Flow(UInt(RenameConfig.PHYS_REG_TAG_WIDTH))
  val rsp = Bits(RenameConfig.XLEN bits)
  override def asMaster(): Unit = { master(cmd); in(rsp) }
  override def asSlave(): Unit = { slave(cmd); out(rsp) } // Clarify slave direction for rsp
}
case class PrfWritePort() extends Bundle with IMasterSlave {
  val cmd = Flow(new Bundle {
    val address = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
    val data = Bits(RenameConfig.XLEN bits)
  })
  override def asMaster(): Unit = { master(cmd) }
  override def asSlave(): Unit = { slave(cmd) } // Clarify slave direction
}

trait PhysicalRegisterFileService extends Service {
  def newReadPorts(count: Int): Vec[PrfReadPort]
  def newWritePort(): PrfWritePort
}

trait PhysicalRegisterFreeService extends Service {
  def getFreePort(): Flow[UInt] // Port for Commit to send stale tags
}

trait WakeupService extends Service {
  def getWakeupPorts(): Vec[Flow[WakeupPortPayload]] // Get all available wakeup ports
}

case class BypassSource() extends Bundle {
  val valid = Bool()
  val pDest = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
  val value = Bits(RenameConfig.XLEN bits)
}

trait BypassService extends Service {
  def addBypassSource(source: BypassSource): Unit
  def getAllBypassSources(): Seq[BypassSource]
}

// ==========================================================================
// == CPU Top Level Component == (NO CHANGES)
// ==========================================================================
class DemoCPU(val plugins: Seq[Plugin]) extends Component {
  val database = new DataBase
  val framework = ProjectScope(database) on new Framework(plugins)
}

// ==========================================================================
// == Global Shared Services Implementation ==
// ==========================================================================

// --- Physical Register File --- (NO CHANGES)
class PhysicalRegisterFilePlugin(
    numPhysRegs: Int = RenameConfig.PHYS_REG_COUNT,
    dataWidth: BitCount = RenameConfig.XLEN bits
) extends Plugin
    with PhysicalRegisterFileService {

  private val readPortRequests = ArrayBuffer[PrfReadPort]()
  private val writePortRequests = ArrayBuffer[PrfWritePort]()
  // Removed readPorts/writePorts ArrayBuffers, use requests directly if possible
  // Or populate them inside 'logic' area

  override def newReadPorts(count: Int): Vec[PrfReadPort] = {
    val ports = Vec.fill(count)(PrfReadPort())
    readPortRequests ++= ports.toSeq
    ports
  }

  override def newWritePort(): PrfWritePort = {
    val port = PrfWritePort()
    writePortRequests += port
    port
  }

  val logic = create late new Area {
    val regFile = Mem.fill(numPhysRegs)(Bits(dataWidth))

    // Use the stored request objects directly for clarity
    for (port <- readPortRequests) {
      val isReadR0 = port.cmd.payload === RenameConfig.R0_PHYS_TAG
      val readData = regFile.readAsync(
        address = port.cmd.payload
      )
      // Assign to the output signal 'rsp' of the read port
      port.rsp := Mux(isReadR0, B(0, dataWidth), readData)
    }

    for (port <- writePortRequests) {
      val writeEnable = port.cmd.valid && port.cmd.payload.address =/= RenameConfig.R0_PHYS_TAG
      regFile.write(
        address = port.cmd.payload.address,
        data = port.cmd.payload.data,
        enable = writeEnable
      )
    }
  }
}

// --- Bypass Service Implementation --- (NO CHANGES)
class BypassPlugin extends Plugin with BypassService with LockedImpl {
  private val sources = ArrayBuffer[BypassSource]()
  override def addBypassSource(source: BypassSource): Unit = sources += source
  override def getAllBypassSources(): Seq[BypassSource] = sources.toSeq
}

// ==========================================================================
// == Fetch Stage == (NO CHANGES)
// ==========================================================================
class FetchPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val fetch0 = newStage()
    val fetch1 = newStage()
    connect(fetch0, fetch1)(M2S())
  }
  pipeline.setCompositeName(this, "Fetch")

  def entryStage: Stage = pipeline.fetch0
  def exitStage: Stage = pipeline.fetch1
}

class DemoRom extends AreaObject {
  val initContent = Seq(
    B(0x00a00093L, 32 bits), // pc=0 : addi x1, x0, 10
    B(0x00108133L, 32 bits), // pc=4 : add  x2, x1, x1
    B(0x022081b3L, 32 bits), // pc=8 : mul  x3, x1, x2
    B(0x00000213L, 32 bits), // pc=C : addi x4, x0, 0
    B(0x00000013L, 32 bits), // pc=10: addi x0, x0, 0 (True NOP)
    B(0x00000013L, 32 bits), // pc=14: addi x0, x0, 0
    B(0x00000013L, 32 bits), // pc=18: addi x0, x0, 0
    B(0x00000013L, 32 bits) // pc=1C: addi x0, x0, 0
  )
  // Instantiate Mem inside a hardware context (will be when DemoRom is instantiated)
  lazy val rom = Mem(Bits(32 bits), initialContent = initContent)
  rom.addAttribute("ram_style", "block")
}

class Fetch0Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }

  val logic = create late new Area {
    val stage = setup.fetchPipeline.pipeline.fetch0
    val romInstance = new DemoRom() // Instantiate DemoRom here
    val rom = romInstance.rom // Access the Mem inside DemoRom

    val pcReg = Reg(UInt(RenameConfig.XLEN bits)) init (0)
    val pcPlus4 = pcReg + 4
    val doIncr = False

    when(stage.isFiring) {
      pcReg := pcPlus4
      doIncr := True
    }

    // Ensure address uses enough bits for the ROM size before slicing
    val romAddrWidth = log2Up(rom.wordCount)
    val romAddress = pcReg(romAddrWidth + 1 downto 2) // Address bits for 32-bit instructions
    val instruction = rom.readAsync(romAddress)

    stage(FrontendPipelineData.PC) := pcReg
    stage(FrontendPipelineData.INSTRUCTION) := instruction
    stage(FrontendPipelineData.FETCH_FAULT) := False
  }
}

class Fetch1Plugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
  }
  val logic = create late new Area {
    // No specific logic needed, just passes data
  }
}

// ==========================================================================
// == Frontend Pipeline (Decode, Rename, Dispatch) == (NO CHANGES)
// ==========================================================================
class FrontendPipeline extends Plugin with LockedImpl {
  val pipeline = create early new Pipeline {
    val decode = newStage()
    val rename = newStage()
    val dispatch = newStage()
    connect(decode, rename)(M2S())
    connect(rename, dispatch)(M2S())
  }
  pipeline.setCompositeName(this, "Frontend")

  def firstStage: Stage = pipeline.decode
  def exitStage: Stage = pipeline.dispatch
}

// --- Decode --- (NO CHANGES)
class DecodePlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }

  val logic = create late new Area {
    val stage = setup.frontendPipeline.pipeline.decode
    import stage._

    val inputPc = stage(FrontendPipelineData.PC)
    val inputInstruction = stage(FrontendPipelineData.INSTRUCTION)
    val inputFault = stage(FrontendPipelineData.FETCH_FAULT)

    val uop = MicroOp().assignDontCare()
    uop.pc := inputPc
    uop.fault := inputFault

    val opcode = inputInstruction(6 downto 0)
    val funct3 = inputInstruction(14 downto 12)
    val funct7 = inputInstruction(31 downto 25)
    val rd = inputInstruction(11 downto 7).asUInt
    val rs1 = inputInstruction(19 downto 15).asUInt
    val rs2 = inputInstruction(24 downto 20).asUInt
    val imm_i = S(inputInstruction(31 downto 20))

    uop.opType := OpType.NOP
    uop.rd := 0
    uop.rj := 0
    uop.rk := 0
    uop.imm := 0

    when(inputFault) {
      uop.opType := OpType.NOP
      uop.fault := True
    } otherwise {
      switch(opcode) {
        is(B"0010011") { // OP-IMM (ADDI)
          when(funct3 === B"000") { // ADDI
            uop.opType := OpType.ALU
            uop.rd := rd
            uop.rj := rs1
            uop.rk := 0
            uop.imm := imm_i.resize(16)
          } otherwise {
            uop.opType := OpType.NOP; uop.fault := True
          }
        }
        is(B"0110011") { // OP (ADD, MUL)
          when(funct7 === B"0000000") {
            when(funct3 === B"000") { // ADD
              uop.opType := OpType.ALU
              uop.rd := rd
              uop.rj := rs1
              uop.rk := rs2
              uop.imm := 0
            } otherwise {
              uop.opType := OpType.NOP; uop.fault := True
            }
          } elsewhen (funct7 === B"0000001") { // M-extension standard
            when(funct3 === B"000") { // MUL
              uop.opType := OpType.MUL
              uop.rd := rd
              uop.rj := rs1
              uop.rk := rs2
              uop.imm := 0
            } otherwise {
              uop.opType := OpType.NOP; uop.fault := True
            }
          } otherwise {
            uop.opType := OpType.NOP; uop.fault := True
          }
        }
        default {
          uop.opType := OpType.NOP
          when(inputInstruction =/= 0) {
            // uop.fault := True // Optional: Fault on unknown
          }
        }
      }
    }
    stage(FrontendPipelineData.UOP) := uop
  }
}

// --- Rename --- (**FIXED: Removed hardFork init**)
class RenamePlugin extends Plugin with LockedImpl with PhysicalRegisterFreeService {

  val freePortIn = Flow(UInt(RenameConfig.PHYS_REG_TAG_WIDTH)) // Input from Commit
  override def getFreePort(): Flow[UInt] = freePortIn

  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }

  val logic = create late new Area {
    val stage = setup.frontendPipeline.pipeline.rename
    import stage._

    // --- State ---
    val mappingTable = Mem.fill(RenameConfig.ARCH_REG_COUNT)(UInt(RenameConfig.PHYS_REG_TAG_WIDTH))
    mappingTable.initBigInt(
      (0 until RenameConfig.ARCH_REG_COUNT).map(i => BigInt(i))
    )

    val freeList = new Stack(
      dataType = UInt(RenameConfig.PHYS_REG_TAG_WIDTH),
      depth = RenameConfig.PHYS_REG_COUNT
    )
    freeList.setCompositeName(this, "freeListStack")

    // --- Initialization Logic using Reset ---
    val resetInitArea = new Area {
      val initCounter = Reg(UInt(log2Up(RenameConfig.PHYS_REG_COUNT + 1) bits)) init (0)
      val initActive = RegInit(True) // Start in init state

      // Assume push is normally driven by commit logic below
      // Temporarily override during init
      val initPushValid = False
      val initPushPayload = UInt(RenameConfig.PHYS_REG_TAG_WIDTH)
      initPushPayload.assignDontCare()

      val isReset = ClockDomain.current.isResetActive // Check reset state

      when(initActive) {
        val currentTag = U(RenameConfig.ARCH_REG_COUNT) + initCounter
        val numToInit = RenameConfig.PHYS_REG_COUNT - RenameConfig.ARCH_REG_COUNT
        val lastTagIndex = U(numToInit - 1)

        initPushValid := True // Try to push during init
        initPushPayload := currentTag.resized

        when(freeList.io.push.ready && initPushValid) { // If stack accepts the push from init
          initCounter := initCounter + 1
          when(initCounter === lastTagIndex) { // Check if last tag was just pushed
            initActive := False // Exit init state next cycle
          }
        }
        // If still in reset, keep initActive True, otherwise (reset just ended), exit init
        when(!isReset) {
          initActive := False
        }

      } elsewhen (isReset) {
        // If reset occurs *after* initialization phase, re-enter init state
        initActive := True
        initCounter := 0
      }

      // Multiplex push source: Init logic has priority
      freeList.io.push.valid := initPushValid || (freePortIn.valid && !initActive)
      freeList.io.push.payload := Mux(initActive, initPushPayload, freePortIn.payload)

      val blockCommitPush = initActive // Used below? Maybe not needed if muxing push source
    }

    // --- Input MicroOp ---
    val inputUop = stage(FrontendPipelineData.UOP)
    val renamedUop = RenamedMicroOp().assignDontCare()
    renamedUop.uop := inputUop
    renamedUop.archDest := inputUop.rd

    // --- Renaming Logic ---
    val archRd = inputUop.rd
    val archRj = inputUop.rj
    val archRk = inputUop.rk

    val pSrc1TagCurrent = mappingTable.readAsync(address = archRj)
    val pSrc2TagCurrent = mappingTable.readAsync(address = archRk)
    val stalePDestCurrent = mappingTable.readAsync(address = archRd)

    renamedUop.pSrc1 := Mux(archRj === 0, RenameConfig.R0_PHYS_TAG, pSrc1TagCurrent)
    renamedUop.pSrc2 := Mux(archRk === 0, RenameConfig.R0_PHYS_TAG, pSrc2TagCurrent)
    renamedUop.stalePDest := Mux(archRd === 0, RenameConfig.R0_PHYS_TAG, stalePDestCurrent)

    val writesReg = inputUop.writesArf()
    val allocationRequired = stage.isValid && writesReg

    val allocatedPDest = freeList.io.pop.payload
    freeList.io.pop.ready := False // Default

    renamedUop.pDest := RenameConfig.R0_PHYS_TAG

    when(allocationRequired) {
      when(freeList.io.pop.valid) { // Free register available?
        renamedUop.pDest := allocatedPDest
        freeList.io.pop.ready := stage.isReady // Pop only if rename stage not stalled

        when(stage.isFiring) { // Update RAT only when instruction advances
          mappingTable.write(
            address = archRd,
            data = allocatedPDest,
            enable = True
          )
        }
      } otherwise {
        stage.haltIt() // Stall if no free registers
      }
    }

    stage(FrontendPipelineData.RENAMED_UOP) := renamedUop

    // Free List Input Logic is handled by the muxing logic in resetInitArea
  } // End logic Area
}

// --- Dispatch --- (**FIXED: Use intermediate ports from IssueQueuesPlugin**)
class DispatchPlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
    val queuesPlugin = getService[IssueQueuesPlugin]
    // Get the intermediate ports from IssueQueuesPlugin
    val intQ0IntermediatePush = queuesPlugin.getIntQ0IntermediatePushPort()
    val intQ1IntermediatePush = queuesPlugin.getIntQ1IntermediatePushPort()
    val mulQIntermediatePush = queuesPlugin.getMulQIntermediatePushPort()
  }

  val logic = create late new Area {
    val stage = setup.frontendPipeline.pipeline.dispatch
    import stage._

    val renamedUopInput = stage(FrontendPipelineData.RENAMED_UOP)

    // Use intermediate push ports obtained in setup
    val intQ0Push = setup.intQ0IntermediatePush
    val intQ1Push = setup.intQ1IntermediatePush
    val mulQPush = setup.mulQIntermediatePush

    // Default assignments (inactive) to intermediate ports
    intQ0Push.valid := False
    intQ1Push.valid := False
    mulQPush.valid := False
    intQ0Push.payload.assignDontCare() // Avoid latches
    intQ1Push.payload.assignDontCare()
    mulQPush.payload.assignDontCare()

    val blockDispatch = False

    when(isValid) {
      val uopType = renamedUopInput.uop.opType
      // Assign payload once, conditionally set valid bits
      intQ0Push.payload := renamedUopInput
      intQ1Push.payload := renamedUopInput
      mulQPush.payload := renamedUopInput

      switch(uopType) {
        is(OpType.ALU) {
          when(intQ0Push.ready) {
            intQ0Push.valid := True
          } elsewhen (intQ1Push.ready) {
            intQ1Push.valid := True
          } otherwise {
            blockDispatch := True // Both ALU queues full
          }
        }
        is(OpType.MUL) {
          when(mulQPush.ready) {
            mulQPush.valid := True
          } otherwise {
            blockDispatch := True // Mul queue full
          }
        }
        default { // NOPs etc.
          // No action needed, don't push to any queue
        }
      }
    }

    // Halt dispatch stage if blocked or downstream stalled
    when(blockDispatch || !stage.isReady) {
      stage.haltIt()
    }

    // Ensure streams only fire when the dispatch stage itself fires
    when(!stage.isFiring) {
      intQ0Push.valid := False
      intQ1Push.valid := False
      mulQPush.valid := False
    }
  }
}

// ==========================================================================
// == Bridge: Fetch -> Frontend == (NO CHANGES)
// ==========================================================================
class FetchFrontendBridge extends Plugin with LockedImpl {
  val setup = create early new Area {
    val fetchPipeline = getService[FetchPipeline]
    val frontendPipeline = getService[FrontendPipeline]
    fetchPipeline.retain()
    frontendPipeline.retain()
  }

  val logic = create late new Area {
    val sourceStage = setup.fetchPipeline.exitStage
    val destStage = setup.frontendPipeline.firstStage

    destStage(FrontendPipelineData.PC) := sourceStage(FrontendPipelineData.PC)
    destStage(FrontendPipelineData.INSTRUCTION) := sourceStage(FrontendPipelineData.INSTRUCTION)
    destStage(FrontendPipelineData.FETCH_FAULT) := sourceStage(FrontendPipelineData.FETCH_FAULT)

    destStage.valid := sourceStage.valid
    sourceStage.haltIt(!destStage.isReady)

    setup.fetchPipeline.release()
    setup.frontendPipeline.release()
  }
}

// ==========================================================================
// == Issue Stage Components ==
// ==========================================================================

// --- Issue Queue Component --- (NO CHANGES)
class IssueQueue(val depth: Int, val numWakeupPorts: Int) extends Component {

  val io = new Bundle {
    val dispatchPush = slave Stream (RenamedMicroOp())
    val issuePop = master Stream (RenamedMicroOp())
    val wakeupPorts = Vec(slave(Flow(WakeupPortPayload())), numWakeupPorts)
    val fuAvailable = in Bool ()
  }

  val slots = Vec.fill(depth)(Reg(IssueSlotPayload()))
  val initPayload = IssueSlotPayload().assignDontCare()
  initPayload.valid := False
  slots.foreach(_.init(initPayload))

  val freeSlotOh = B(slots.map(!_.valid))
  val hasFreeSlot = freeSlotOh.orR
  val freeSlotAddr = OHToUInt(freeSlotOh)

  io.dispatchPush.ready := hasFreeSlot

  when(io.dispatchPush.fire) {
    val dispatchedUop = io.dispatchPush.payload
    val newSlot = IssueSlotPayload().assignDontCare()
    newSlot.valid := True
    newSlot.renamedUop := dispatchedUop
    newSlot.pSrc1Ready := dispatchedUop.pSrc1 === RenameConfig.R0_PHYS_TAG
    newSlot.pSrc2Ready := dispatchedUop.pSrc2 === RenameConfig.R0_PHYS_TAG
    slots(freeSlotAddr) := newSlot
  }

  for (wakeup <- io.wakeupPorts) {
    when(wakeup.valid) {
      val wakeupTag = wakeup.payload.physicalTag
      for (slot <- slots) {
        when(slot.valid) {
          when(!slot.pSrc1Ready && slot.renamedUop.pSrc1 === wakeupTag && wakeupTag =/= RenameConfig.R0_PHYS_TAG) {
            slot.pSrc1Ready := True
          }
          when(!slot.pSrc2Ready && slot.renamedUop.pSrc2 === wakeupTag && wakeupTag =/= RenameConfig.R0_PHYS_TAG) {
            slot.pSrc2Ready := True
          }
        }
      }
    }
  }

  val slotReady = Vec(slots.map(s => s.valid && s.pSrc1Ready && s.pSrc2Ready))
  val hasReadyInst = slotReady.orR

  val issueRequestOh = OHMasking.first(slotReady.asBits)
  val issueSlotAddr = OHToUInt(issueRequestOh)

  val issueGrant = hasReadyInst && io.fuAvailable && io.issuePop.ready

  io.issuePop.valid := hasReadyInst && io.fuAvailable
  io.issuePop.payload := slots(issueSlotAddr).renamedUop

  when(issueGrant) {
    slots(issueSlotAddr).valid := False
  }
}

// --- Issue Queues Plugin --- (**FIXED: Instantiate queues in Area, add intermediate ports**)
// --- Issue Queues Plugin --- (**FIXED: Compile-time vs Runtime check**)
class IssueQueuesPlugin(
    val integerIssueDepth: Int = 8,
    val muldivIssueDepth: Int = 4,
    val numWakeupSources: Int
) extends Plugin
    with LockedImpl
    with WakeupService {

  private val internalWakeupPorts = Vec(Flow(WakeupPortPayload()), numWakeupSources)
  override def getWakeupPorts(): Vec[Flow[WakeupPortPayload]] = internalWakeupPorts

  val logic = create late new Area {
    val intQueue0 = new IssueQueue(depth = integerIssueDepth, numWakeupPorts = numWakeupSources)
    val intQueue1 = new IssueQueue(depth = integerIssueDepth, numWakeupPorts = numWakeupSources)
    val mulQueue = new IssueQueue(depth = muldivIssueDepth, numWakeupPorts = numWakeupSources)

    intQueue0.io.wakeupPorts <> internalWakeupPorts
    intQueue1.io.wakeupPorts <> internalWakeupPorts
    mulQueue.io.wakeupPorts <> internalWakeupPorts

    val dispatchToIntQ0 = Stream(RenamedMicroOp())
    val dispatchToIntQ1 = Stream(RenamedMicroOp())
    val dispatchToMulQ = Stream(RenamedMicroOp())

    dispatchToIntQ0 >> intQueue0.io.dispatchPush
    dispatchToIntQ1 >> intQueue1.io.dispatchPush
    dispatchToMulQ >> mulQueue.io.dispatchPush

    val intFu0Available = Bool()
    val intFu1Available = Bool()
    val mulFuAvailable = Bool()

    intQueue0.io.fuAvailable := intFu0Available
    intQueue1.io.fuAvailable := intFu1Available
    mulQueue.io.fuAvailable := mulFuAvailable

    // --- Logic to connect FU Availability and Issue Streams ---
    // Get services (compile-time operation)
    val aluExecUnits = getServicesOf[AluExecutionUnit].sortBy(_.euId)
    val mulExecUnitOpt = getServiceOption[MulDivExecutionUnit]

    // Connect FU Availability FROM EUs TO internal signals (**FIXED**)
    // Use compile-time check to guard hardware connection generation
    if (aluExecUnits.length >= 1) {
      intFu0Available := aluExecUnits(0).setup.isAvailable // Runtime signal connection
    } else {
      intFu0Available := False // Default if component doesn't exist
    }

    if (aluExecUnits.length >= 2) {
      intFu1Available := aluExecUnits(1).setup.isAvailable // Runtime signal connection
    } else {
      intFu1Available := False // Default if component doesn't exist
    }

    if (mulExecUnitOpt.isDefined) {
      mulFuAvailable := mulExecUnitOpt.get.setup.isAvailable // Runtime signal connection
    } else {
      mulFuAvailable := False // Default if component doesn't exist
    }

    // Connect Issue Queue Pop Streams TO EU Push Streams
    if (aluExecUnits.length >= 1) intQueue0.io.issuePop >> aluExecUnits(0).setup.pipelineInput
    else intQueue0.io.issuePop.ready := False

    if (aluExecUnits.length >= 2) intQueue1.io.issuePop >> aluExecUnits(1).setup.pipelineInput
    else intQueue1.io.issuePop.ready := False

    if (mulExecUnitOpt.isDefined) mulQueue.io.issuePop >> mulExecUnitOpt.get.setup.pipelineInput
    else mulQueue.io.issuePop.ready := False
  }

  // Accessors remain the same, referring to logic.XXX
  def getIntQ0IntermediatePushPort(): Stream[RenamedMicroOp] = logic.dispatchToIntQ0
  def getIntQ1IntermediatePushPort(): Stream[RenamedMicroOp] = logic.dispatchToIntQ1
  def getMulQIntermediatePushPort(): Stream[RenamedMicroOp] = logic.dispatchToMulQ
  def getIntQueue0IssuePort(): Stream[RenamedMicroOp] = logic.intQueue0.io.issuePop
  def getIntQueue1IssuePort(): Stream[RenamedMicroOp] = logic.intQueue1.io.issuePop
  def getMulQueueIssuePort(): Stream[RenamedMicroOp] = logic.mulQueue.io.issuePop
}

// ==========================================================================
// == Execution Stage Components == (NO CHANGES TO STRUCTURE)
// ==========================================================================

// --- Base class for Execution Pipelines ---
abstract class ExecutionPipeline(
    val pipelineName: String,
    val euId: Int,
    val wakeupPortId: Int
) extends Plugin
    with LockedImpl {

  val pipeline = create early new Pipeline {
    val RR = newStage()
    val EX = newStage()
    val WB = newStage()
    connect(RR, EX)(M2S())
    connect(EX, WB)(M2S())
  }
  pipeline.setCompositeName(this, pipelineName)

 // --- Setup: Dependencies, Resource Allocation, and INTERFACE DECLARATION ---
  val setup = create early new Area {
    // === Declare Hardware Interface Signals HERE ===
    val pipelineInput = slave Stream (RenamedMicroOp()) // Input from Issue Queue
    val commitOutput = master Stream (Fragment(CommitEntry())) // Output to Commit Arbiter
    val isAvailable = out Bool() // Output signal: Can RR stage accept input?

    // Get required services
    val prfService = getService[PhysicalRegisterFileService]
    val wakeupService = getService[WakeupService]
    val commitService = getService[CommitPlugin]
    val bypassService = getService[BypassService] // Get the SHARED bypass service

    // Allocate PRF ports (typically 2 read, 1 write)
    val prfReadPorts = prfService.newReadPorts(2)
    val prfWritePort = prfService.newWritePort()

    // Get the specific wakeup port assigned to this EU
    val wakeupPort = wakeupService.getWakeupPorts()(wakeupPortId)

    // Connect commit output stream (declared above) to the arbiter input slot
    commitOutput >> commitService.arbiter.io.inputs(euId)
    commitOutput.payload.assignDontCare() // Ensure default payload
  }


  // --- RR Stage Logic (Common for ALU/MUL) ---
  val rrLogic = create late new Area {
    val rrStage = pipeline.RR
    import rrStage._

    // Connect availability signal (drive the output signal in setup Area)
    setup.isAvailable := rrStage.isReady // FU is available if RR stage is ready

    // Consume input stream (access via setup Area)
    val inputPayload = setup.pipelineInput.payload
    setup.pipelineInput.ready := rrStage.isReady // Backpressure to issue queue

    rrStage.valid := setup.pipelineInput.valid // Stage valid mirrors input stream valid
    when(rrStage.valid) { // Use payload only when valid/firing
        rrStage(ExecutionUnitData.RENAMED_UOP) := inputPayload
    }

    // --- Read PRF (using latched UOP) ---
    val currentUop = rrStage(ExecutionUnitData.RENAMED_UOP)
    val pSrc1Tag = currentUop.pSrc1
    val pSrc2Tag = currentUop.pSrc2

    setup.prfReadPorts(0).cmd.valid := rrStage.isValid && pSrc1Tag =/= RenameConfig.R0_PHYS_TAG
    setup.prfReadPorts(0).cmd.payload := pSrc1Tag
    setup.prfReadPorts(1).cmd.valid := rrStage.isValid && pSrc2Tag =/= RenameConfig.R0_PHYS_TAG
    setup.prfReadPorts(1).cmd.payload := pSrc2Tag

    val operand1FromPRF = setup.prfReadPorts(0).rsp
    val operand2FromPRF = setup.prfReadPorts(1).rsp

    // --- Bypass Logic --- (Using previous fix for MuxOH)
    val allBypassSources = setup.bypassService.getAllBypassSources()
    val bypassWidth = RenameConfig.XLEN bits

    def findBypass(srcTag: UInt): (Bool, Bits) = {
      if (allBypassSources.isEmpty) {
        (False, B(0, bypassWidth))
      } else {
        val hitsBoolSeq   = allBypassSources.map(s => s.valid && s.pDest === srcTag && srcTag =/= RenameConfig.R0_PHYS_TAG)
        val valuesBitsSeq = allBypassSources.map(_.value)
        val bypassFound   = hitsBoolSeq.orR
        val valuesVec     = Vec(valuesBitsSeq)
        val bypassValue   = Bits(bypassWidth)
        bypassValue := B(0)
        when(bypassFound) {
          bypassValue := MuxOH(hitsBoolSeq.toIndexedSeq, valuesVec)
        }
        (bypassFound, bypassValue)
      }
    }

    val (bypassFound1, bypassValue1) = findBypass(pSrc1Tag)
    val (bypassFound2, bypassValue2) = findBypass(pSrc2Tag)

    val finalOperand1 = Mux(pSrc1Tag === RenameConfig.R0_PHYS_TAG, B(0, bypassWidth), Mux(bypassFound1, bypassValue1, operand1FromPRF))
    val finalOperand2 = Mux(pSrc2Tag === RenameConfig.R0_PHYS_TAG, B(0, bypassWidth), Mux(bypassFound2, bypassValue2, operand2FromPRF))

    rrStage(ExecutionUnitData.SRC1_VALUE) := finalOperand1
    rrStage(ExecutionUnitData.SRC2_VALUE) := finalOperand2
  }
  // --- EX Stage Logic (Abstract - Must be implemented by subclasses) ---
  val exLogic: Area // Abstract

  // --- WB Stage Logic (Common for ALU/MUL) ---
  val wbLogic = create late new Area {
    val wbStage = pipeline.WB
    import wbStage._

    val uopResult = wbStage(ExecutionUnitData.RENAMED_UOP)
    val writebackValue = wbStage(ExecutionUnitData.WRITEBACK_VALUE)
    val writebackEnable = wbStage(ExecutionUnitData.WRITEBACK_ENABLE)
    val pDest = uopResult.pDest
    val isNotR0 = pDest =/= RenameConfig.R0_PHYS_TAG
    val doWrite = isValid && writebackEnable && isNotR0 && !uopResult.uop.fault

    // 1. Drive Wakeup Port
    setup.wakeupPort.valid := isFiring && writebackEnable && isNotR0 && !uopResult.uop.fault
    setup.wakeupPort.payload.physicalTag := pDest

    // 2. Drive PRF Write Port
    setup.prfWritePort.cmd.valid := isFiring && writebackEnable && isNotR0 && !uopResult.uop.fault
    setup.prfWritePort.cmd.payload.address := pDest
    setup.prfWritePort.cmd.payload.data := writebackValue

    // 3. Populate and Drive Commit Output Stream (access via setup Area)
    val commitEntry = CommitEntry()
    commitEntry.uop := uopResult.uop
    commitEntry.writebackValue := writebackValue
    commitEntry.writebackEnable := writebackEnable && isNotR0
    commitEntry.fault := uopResult.uop.fault
    commitEntry.pDest := pDest
    commitEntry.archDest := uopResult.archDest
    commitEntry.stalePDest := uopResult.stalePDest

    setup.commitOutput.valid := isValid // Send if WB stage is valid
    setup.commitOutput.payload.fragment := commitEntry
    setup.commitOutput.payload.last := True // Single fragment per instruction

    // Handle backpressure from commit arbiter (access via setup Area)
    when(isValid) {
      wbStage.haltIt(!setup.commitOutput.ready)
    }
  }

  val bypassPopulation = create late new Area {
    val exStage = pipeline.EX
    val wbStage = pipeline.WB

    def createBypassSource(stage: Stage): BypassSource = {
      val source = BypassSource()
      val uop = stage(ExecutionUnitData.RENAMED_UOP)
      val writeEnable = stage(ExecutionUnitData.WRITEBACK_ENABLE)
      val pDest = uop.pDest
      val isNotR0 = pDest =/= RenameConfig.R0_PHYS_TAG

      source.valid := stage.isFiring && writeEnable && isNotR0 && !uop.uop.fault
      source.pDest := pDest
      source.value := stage(ExecutionUnitData.WRITEBACK_VALUE)
      source
    }

    setup.bypassService.addBypassSource(createBypassSource(exStage))
    setup.bypassService.addBypassSource(createBypassSource(wbStage))
  }
}

// --- ALU Execution Unit --- (NO CHANGES)
class AluExecutionUnit(
    override val euId: Int,
    override val wakeupPortId: Int
) extends ExecutionPipeline(s"ALU_Unit_$euId", euId, wakeupPortId) {

  override val exLogic = create late new Area {
    val exStage = pipeline.EX
    import exStage._

    // --- Read inputs from previous stage (RR) ---
    val uopInputFromRR = exStage(ExecutionUnitData.RENAMED_UOP) // UOP bundle entering EX
    val src1 = exStage(ExecutionUnitData.SRC1_VALUE)
    val src2 = exStage(ExecutionUnitData.SRC2_VALUE)
    // Note: imm was part of uopInputFromRR.uop

    // --- Intermediate signals ---
    val result = Bits(RenameConfig.XLEN bits)
    val faultFromPrevious = uopInputFromRR.uop.fault
    val faultGeneratedInEX = False // Placeholder if EX could generate new faults

    // --- Perform EX stage calculation ---
    when(uopInputFromRR.uop.opType === OpType.ALU) {
      // Use a clear check based on the MicroOp's intended structure for ALU ops
      // Assuming ADDI uses imm and ADD uses rk != 0 (refine based on actual MicroOp definition)
      val isAddiLike = uopInputFromRR.uop.rk === 0 // Example check - Needs verification!
      val immediateValue = uopInputFromRR.uop.imm
      result := (src1.asSInt + Mux(isAddiLike, immediateValue, src2.asSInt)).asBits
    } otherwise {
      result := B(0) // Default result for unexpected op types in ALU
      // faultGeneratedInEX := True // Optionally set fault for unexpected types
    }

    // --- Prepare outputs for next stage (WB) ---

    // 1. Determine final fault status to pass to WB
    val faultOutputToWB = faultFromPrevious || faultGeneratedInEX

    // 2. Create the RenamedMicroOp bundle to pass to WB
    //    Start with the input UOP and update the fault field.
    //    Using CombInit creates a new wire bundle initialized from the input.
    val uopOutputToWB = CombInit(uopInputFromRR)
    uopOutputToWB.uop.fault := faultOutputToWB // Assign the final fault status

    // 3. Assign all output values to stageables for WB stage
    exStage(ExecutionUnitData.RENAMED_UOP)       := uopOutputToWB     // Pass the potentially modified UOP bundle
    exStage(ExecutionUnitData.WRITEBACK_VALUE)   := result            // Pass the calculated result
    exStage(ExecutionUnitData.WRITEBACK_ENABLE) := uopInputFromRR.uop.writesArf() // Pass write enable based on original UOP
  }
}

// --- Multiply Execution Unit --- (NO CHANGES)
class MulDivExecutionUnit(
    override val euId: Int,
    override val wakeupPortId: Int
) extends ExecutionPipeline(s"MulDiv_Unit_$euId", euId, wakeupPortId) {

  override val exLogic = create late new Area {
    val exStage = pipeline.EX
    import exStage._

    val uopInput = exStage(ExecutionUnitData.RENAMED_UOP)
    val src1 = exStage(ExecutionUnitData.SRC1_VALUE)
    val src2 = exStage(ExecutionUnitData.SRC2_VALUE)
    val currentFault = uopInput.uop.fault

    val result = (src1.asSInt * src2.asSInt).resize(RenameConfig.XLEN).asBits

    exStage(ExecutionUnitData.WRITEBACK_VALUE) := result
    exStage(ExecutionUnitData.WRITEBACK_ENABLE) := uopInput.uop.writesArf()
    exStage(ExecutionUnitData.RENAMED_UOP).uop.fault := currentFault // Propagate fault
  }
}

// ==========================================================================
// == Commit Stage == (NO CHANGES)
// ==========================================================================
class CommitPlugin(val executionUnitCount: Int) extends Plugin with LockedImpl {
  val setup = create early new Area {
    val arbiter = StreamArbiterFactory.roundRobin.build(Fragment(CommitEntry()), executionUnitCount)
    val freeRegService = getService[PhysicalRegisterFreeService]
    val freePort = freeRegService.getFreePort() // Output Flow port
  }
  def arbiter: StreamArbiter[Fragment[CommitEntry]] = setup.arbiter // Expose arbiter

  val commitLogic = create late new Area {
    val inputCommitStream = setup.arbiter.io.output

    setup.freePort.valid := False // Default
    setup.freePort.payload.assignDontCare()

    inputCommitStream.ready := True // Assume commit is always ready (simplification)

    when(inputCommitStream.fire) {
      val commitData = inputCommitStream.payload.fragment

      // Free Stale Physical Register
      when(commitData.stalePDest =/= RenameConfig.R0_PHYS_TAG && !commitData.fault) {
        setup.freePort.valid := True
        setup.freePort.payload := commitData.stalePDest
      }

      // Architectural State Update (Simplified: Debug only)
      report(
        Seq(
          L"COMMIT: PC=0x${commitData.uop.pc}%x OP=${commitData.uop.opType} ",
          L"ArchRD=${commitData.archDest} PDest=${commitData.pDest} Stale=${commitData.stalePDest} ",
          L"WEn=${commitData.writebackEnable} WVal=0x${commitData.writebackValue}%x Fault=${commitData.fault}"
        )
      )
    }
  }
}

// ==========================================================================
// == Main CPU Instantiation & Simulation == (NO CHANGES)
// ==========================================================================
object DemoCPUGen extends App {

  def getPlugins: Seq[Plugin] = {
    val numAlus = 2
    val numMul = 1
    val totalEuCount = numAlus + numMul

    val plugins = ArrayBuffer[Plugin]()

    plugins += new PhysicalRegisterFilePlugin()
    plugins += new BypassPlugin()
    plugins += new FetchPipeline()
    plugins += new FrontendPipeline()
    plugins += new FetchFrontendBridge()
    plugins += new Fetch0Plugin()
    plugins += new Fetch1Plugin()
    plugins += new DecodePlugin()
    plugins += new RenamePlugin()
    plugins += new DispatchPlugin()
    plugins += new IssueQueuesPlugin(numWakeupSources = totalEuCount)

    var currentEuId = 0
    var currentWakeupPortId = 0

    for (i <- 0 until numAlus) {
      plugins += new AluExecutionUnit(euId = currentEuId, wakeupPortId = currentWakeupPortId)
      currentEuId += 1; currentWakeupPortId += 1
    }
    for (i <- 0 until numMul) {
      plugins += new MulDivExecutionUnit(euId = currentEuId, wakeupPortId = currentWakeupPortId)
      currentEuId += 1; currentWakeupPortId += 1
    }

    plugins += new CommitPlugin(executionUnitCount = totalEuCount)
    plugins.toSeq
  }

  val spinalConfig = SpinalConfig(
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    targetDirectory = "rtl/boson/demo"
  )

  spinalConfig.generateVerilog(new DemoCPU(plugins = DemoCPUGen.getPlugins))
  println("Verilog Generation DONE")
}

object DemoCPUGenSim extends App {
  import spinal.core.sim._

  SimConfig.withWave
    .compile(new DemoCPU(plugins = DemoCPUGen.getPlugins))
    .doSim(seed = 42) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      dut.clockDomain.waitSampling(200)
      println("Simulation DONE")
    }
}
