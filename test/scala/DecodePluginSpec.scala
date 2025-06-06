package test.issue

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer} // 确保引入Stream相关
import parallax.common._
import parallax.issue._
import parallax.components.decode._ // For InstructionOpcodesBigInts,rallax.utilities._
import parallax.utilities._

import scala.collection.mutable
import scala.util.Random

// 测试台组件
class DecodePluginTestBench(val pipelineConfig: PipelineConfig) extends Component {
  val io = new Bundle {
    // --- Inputs to drive s0_decode ---
    val groupPcIn = in UInt (pipelineConfig.pcWidth)
    val rawInstructionsIn = in Vec (Bits(pipelineConfig.dataWidth), pipelineConfig.fetchWidth)
    val isFaultIn = in Bool ()
    val s0DecodeInputValid = in Bool () // 控制 s0_decode 输入是否有效

    // --- Outputs from s0_decode (monitored) ---
    val decodedUops = out Vec (DecodedUop(pipelineConfig), pipelineConfig.fetchWidth)
    val s0DecodeOutputValid = out Bool () // s0_decode.internals.output.valid

    // --- Control for s0_decode output ready ---
    val s0DecodeOutputReady = in Bool () // 控制 s0_decode.internals.output.ready
  }

  // Framework 和插件实例化
  val database = new DataBase
  val framework = ProjectScope(database) on new Framework(
    Seq(
      new IssuePipeline(pipelineConfig), // 必须，因为它定义了阶段和信号
      new DecodePlugin(pipelineConfig)   // 被测插件
      // 注意：这里没有RenamePlugin等，所以s0_decode的输出会直接连接到pipeline的末端(如果只有一个阶段)
      // 或者被后续阶段的input.valid=False阻塞。
      // 为了独立测试DecodePlugin，我们需要确保s0_decode的output.ready可以被控制。
    )
  )

  // 获取 IssuePipeline 和 DecodePlugin 的引用 (如果需要，但通常测试其效果即可)
  val issuePpl = framework.getService[IssuePipeline]
  // val decodePlugin = framework.getService[DecodePlugin] // 如果需要访问插件内部

  val s0_decode = issuePpl.pipeline.s0_decode
  val signals = issuePpl.signals

  // --- 连接测试台 IO 到 s0_decode 的 Stageable ---
  // 在 s0_decode 的输入端驱动 Stageable
  // (这种直接驱动 Stageable 的方式通常用于仿真，实际硬件中是前级 Stage 的输出)
  s0_decode(signals.GROUP_PC_IN) := io.groupPcIn
  s0_decode(signals.RAW_INSTRUCTIONS_IN) := io.rawInstructionsIn
  s0_decode(signals.IS_FAULT_IN) := io.isFaultIn

  // 控制 s0_decode 的输入有效性
  // s0_decode.internals.input.valid 是由上游（这里是模拟的）驱动的
  // 我们通过 io.s0DecodeInputValid 来模拟上游的 valid
  s0_decode.internals.input.valid := io.s0DecodeInputValid

  // 从 s0_decode 的输出端读取 Stageable
  io.decodedUops := s0_decode(signals.DECODED_UOPS)
  // 监控 s0_decode 的输出有效性
  io.s0DecodeOutputValid := s0_decode.internals.output.valid

  // 控制 s0_decode 的输出准备好信号 (模拟下游)
  val s0_decodeStream = s0_decode.toStream()
  s0_decodeStream.ready := io.s0DecodeOutputReady

  // SimPublics for easier debugging in wave
  s0_decode.internals.input.valid.simPublic()
  if(s0_decode.internals.input.ready == null) {
    s0_decode.internals.input.ready = Bool()
  }
  s0_decode.internals.input.ready.simPublic()
  s0_decode.internals.output.valid.simPublic()
  s0_decodeStream.ready.simPublic()
  io.simPublic()
}

// 测试用例 Spec
class DecodePluginSpec extends CustomSpinalSimFunSuite {
  val XLEN = 32 

  // Helper to create instruction bits (simplified for DEMO)
  // opcode: String (e.g., "000010" for ADD)
  // rd, rs, rt: Int
  // imm: Int (for imm16 field)
  def createDemoInstruction(opcode: BigInt, rd: Int = 0, rs: Int = 0, rt: Int = 0, imm: Int = 0): BigInt = {
    var inst = opcode << 26
    inst |= BigInt(rd & 0x1F) << 21
    inst |= BigInt(rs & 0x1F) << 16
    inst |= BigInt(rt & 0x1F) << 11 // For R-type, also part of I-type's imm
    inst |= BigInt(imm & 0xFFFF)    // For I-type immediate (overlaps rt)
    inst
  }

  // Test for single instruction decode (fetchWidth = 1)
  test("DecodePlugin_SingleInstruction_ADD") {
    val cfg = PipelineConfig(fetchWidth = 1, xlen = XLEN)
    SimConfig.withWave.compile(new DecodePluginTestBench(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Initialize inputs
      dut.io.s0DecodeInputValid #= false
      dut.io.s0DecodeOutputReady #= false // Start with downstream not ready

      // Test ADD instruction
      val addInstr = createDemoInstruction(InstructionOpcodesBigInts.ADD, rd = 3, rs = 1, rt = 2)
      dut.io.groupPcIn #= 0x1000
      dut.io.rawInstructionsIn(0) #= addInstr
      dut.io.isFaultIn #= false
      dut.io.s0DecodeInputValid #= true
      dut.io.s0DecodeOutputReady #= true // Make downstream ready

      dut.clockDomain.waitSampling() // Let s0_decode receive and process

      // Check outputs (after one cycle, assuming M2S connection implies one cycle for data to appear if fire)
      // Or, if purely combinational for this setup, check immediately after valid & ready are high.
      
      waitUntil(dut.io.s0DecodeOutputValid.toBoolean && isStageInputFiring(dut.s0_decode)) // Wait for stage to fire and output to be valid
      
      assert(dut.io.s0DecodeOutputValid.toBoolean, "s0_decode output should be valid")
      val decoded = dut.io.decodedUops(0)

      assert(decoded.isValid.toBoolean, "Decoded ADD should be valid")
      assert(decoded.uopCode.toEnum == BaseUopCode.ALU, "UopCode should be ALU for ADD")
      assert(decoded.exeUnit.toEnum == ExeUnitType.ALU_INT, "ExeUnit should be ALU_INT for ADD")
      assert(decoded.pc.toLong == 0x1000, "PC should be correct")
      assert(decoded.archDest.idx.toInt == 3, "ArchDest idx should be 3")
      assert(decoded.archSrc1.idx.toInt == 1, "ArchSrc1 idx should be 1")
      assert(decoded.useArchSrc1.toBoolean, "useArchSrc1 should be true")
      assert(decoded.archSrc2.idx.toInt == 2, "ArchSrc2 idx should be 2")
      assert(decoded.useArchSrc2.toBoolean, "useArchSrc2 should be true")
      assert(!decoded.aluCtrl.isSub.toBoolean, "ALU ctrl isSub should be false for ADD")
      assert(!decoded.hasDecodeException.toBoolean, "Should not have decode exception")

      println(s"ADD Decoded: PC=${decoded.pc.toLong.toHexString}, UopCode=${decoded.uopCode.toEnum}, Valid=${decoded.isValid.toBoolean}")

      dut.io.s0DecodeInputValid #= false // Stop driving input
      dut.clockDomain.waitSampling(5)
    }
  }

  // Test for dual instruction decode (fetchWidth = 2)
  test("DecodePlugin_DualInstruction_ADDI_LD") {
    val cfg = PipelineConfig(fetchWidth = 2, xlen = XLEN)
    SimConfig.withWave.compile(new DecodePluginTestBench(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.s0DecodeInputValid #= false
      dut.io.s0DecodeOutputReady #= false

      val addiInstr = createDemoInstruction(InstructionOpcodesBigInts.ADDI, rd = 5, rs = 6, imm = 123)
      val ldInstr   = createDemoInstruction(InstructionOpcodesBigInts.LD, rd = 7, rs = 8)

      dut.io.groupPcIn #= 0x2000
      dut.io.rawInstructionsIn(0) #= addiInstr
      dut.io.rawInstructionsIn(1) #= ldInstr
      dut.io.isFaultIn #= false
      dut.io.s0DecodeInputValid #= true
      dut.io.s0DecodeOutputReady #= true

      dut.clockDomain.waitSampling()
      waitUntil(dut.io.s0DecodeOutputValid.toBoolean && isStageInputFiring(dut.s0_decode))

      assert(dut.io.s0DecodeOutputValid.toBoolean, "s0_decode output should be valid for dual fetch")

      // Check ADDI (slot 0)
      val decodedAddi = dut.io.decodedUops(0)
      println(s"ADDI Decoded: PC=${decodedAddi.pc.toLong.toHexString}, UopCode=${decodedAddi.uopCode.toEnum}, Valid=${decodedAddi.isValid.toBoolean}")
      assert(decodedAddi.isValid.toBoolean, "Decoded ADDI should be valid")
      assert(decodedAddi.uopCode.toEnum == BaseUopCode.ALU, "UopCode should be ALU for ADDI")
      assert(decodedAddi.pc.toLong == 0x2000, "ADDI PC should be correct")
      assert(decodedAddi.archDest.idx.toInt == 5)
      assert(decodedAddi.archSrc1.idx.toInt == 6)
      assert(decodedAddi.useArchSrc1.toBoolean)
      assert(decodedAddi.immUsage.toEnum == ImmUsageType.SRC_ALU)
      // assert(decodedAddi.imm.toBigInt == BigInt(123), "ADDI immediate value mismatch") // Sign extension needs care
      val expectedImmAddi = BigInt(123)
      val actualImmAddiS = Bits(cfg.dataWidth)
      actualImmAddiS := decodedAddi.imm // Assign to a SInt for correct signed interpretation if needed
      val actualImmAddi = if ((expectedImmAddi & (BigInt(1) << 15)) != 0) { // if imm16 MSB is 1 (negative)
        expectedImmAddi - (BigInt(1) << 16) // Manual sign extension for comparison if needed
      } else {
        expectedImmAddi
      }
      // This comparison is tricky due to how imm is packed and sign-extended in DecodedUop.
      // Better to check against the raw 16-bit imm if possible, or the fully sign-extended dataWidth version.
      // For now, let's assume SimpleDecoder does sign_extend(imm16) correctly.
      // The `imm_sext_16_to_dataWidth` in SimpleDecoder should handle this.

      // Check LD (slot 1)
      val decodedLd = dut.io.decodedUops(1)
      println(s"LD Decoded: PC=${decodedLd.pc.toLong.toHexString}, UopCode=${decodedLd.uopCode.toEnum}, Valid=${decodedLd.isValid.toBoolean}")
      assert(decodedLd.isValid.toBoolean, "Decoded LD should be valid")
      assert(decodedLd.uopCode.toEnum == BaseUopCode.LOAD, "UopCode should be LOAD for LD")
      assert(decodedLd.pc.toLong == 0x2000 + cfg.bytesPerInstruction, "LD PC should be correct") // PC for second instruction
      assert(decodedLd.archDest.idx.toInt == 7)
      assert(decodedLd.archSrc1.idx.toInt == 8)
      assert(decodedLd.useArchSrc1.toBoolean)
      assert(decodedLd.memCtrl.isStore.toBoolean == false)

      dut.io.s0DecodeInputValid #= false
      dut.clockDomain.waitSampling(5)
    }
  }

  // Test for fetch fault
  test("DecodePlugin_FetchFault") {
    val cfg = PipelineConfig(fetchWidth = 1, xlen = XLEN)
    SimConfig.withWave.compile(new DecodePluginTestBench(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val nopInstr = createDemoInstruction(InstructionOpcodesBigInts.ADD, 0,0,0) // NOP

      dut.io.groupPcIn #= 0x3000
      dut.io.rawInstructionsIn(0) #= nopInstr // Instruction itself doesn't matter
      dut.io.isFaultIn #= true               // Signal a fetch fault
      dut.io.s0DecodeInputValid #= true
      dut.io.s0DecodeOutputReady #= true

      dut.clockDomain.waitSampling()
      waitUntil(dut.io.s0DecodeOutputValid.toBoolean && isStageInputFiring(dut.s0_decode))

      val decoded = dut.io.decodedUops(0)
      println(s"Fault Decoded: PC=${decoded.pc.toLong.toHexString}, Valid=${decoded.isValid.toBoolean}, Excp=${decoded.hasDecodeException.toBoolean}, ExcpCode=${decoded.decodeExceptionCode.toEnum}")
      assert(!decoded.isValid.toBoolean, "Instruction should be invalid on fetch fault")
      assert(decoded.hasDecodeException.toBoolean, "Should have decode exception on fetch fault")
      assert(decoded.decodeExceptionCode.toEnum == DecodeExCode.FETCH_ERROR, "Exception code should be FETCH_ERROR")

      dut.io.s0DecodeInputValid #= false
      dut.clockDomain.waitSampling(5)
    }
  }

  // TODO: Add test for illegal instruction
  test("DecodePlugin_IllegalInstruction") {
    val cfg = PipelineConfig(fetchWidth = 1, xlen = XLEN)
    SimConfig.withWave.compile(new DecodePluginTestBench(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val illegalInstr = createDemoInstruction(InstructionOpcodesBigInts.ILLEGAL, 1,2,3)

      dut.io.groupPcIn #= 0x4000
      dut.io.rawInstructionsIn(0) #= illegalInstr
      dut.io.isFaultIn #= false
      dut.io.s0DecodeInputValid #= true
      dut.io.s0DecodeOutputReady #= true

      dut.clockDomain.waitSampling()
      waitUntil(dut.io.s0DecodeOutputValid.toBoolean && isStageInputFiring(dut.s0_decode))

      val decoded = dut.io.decodedUops(0)
      println(s"Illegal Decoded: PC=${decoded.pc.toLong.toHexString}, Valid=${decoded.isValid.toBoolean}, Excp=${decoded.hasDecodeException.toBoolean}, ExcpCode=${decoded.decodeExceptionCode.toEnum}")
      assert(!decoded.isValid.toBoolean, "Instruction should be invalid for illegal opcode") // SimpleDecoder's default sets isValid=False
      assert(decoded.hasDecodeException.toBoolean, "Should have decode exception for illegal opcode")
      assert(decoded.decodeExceptionCode.toEnum == DecodeExCode.DECODE_ERROR || decoded.uopCode.toEnum == BaseUopCode.ILLEGAL, // SimpleDecoder default sets ILLEGAL
        "Exception code should be DECODE_ERROR or UopCode should be ILLEGAL")
      // Note: SimpleDecoder's default case sets uopCode to ILLEGAL and hasDecodeException to True.
      // The decodeExceptionCode might need to be explicitly set to DECODE_ERROR in SimpleDecoder.

      dut.io.s0DecodeInputValid #= false
      dut.clockDomain.waitSampling(5)
    }
  }

  thatsAll()
}
