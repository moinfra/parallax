// filename: src/main/scala/parallax/issue/DecodePlugin.scala
package parallax.issue

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._
import parallax.common._
import parallax.components.decode._
import parallax.utilities.{LockedImpl, ParallaxLogger, Plugin}

class DecodePlugin(val issueConfig: PipelineConfig) extends Plugin with LockedImpl {

  val setup = create early new Area {
    val issuePpl = getService[IssuePipeline]
    issuePpl.retain()

    val s0_decode = issuePpl.pipeline.s0_decode
    s0_decode(issuePpl.signals.GROUP_PC_IN)
    s0_decode(issuePpl.signals.RAW_INSTRUCTIONS_IN)
    s0_decode(issuePpl.signals.IS_FAULT_IN)
    s0_decode(issuePpl.signals.VALID_MASK) // Consumes VALID_MASK
    s0_decode(issuePpl.signals.DECODED_UOPS)
  }

  val logic = create late new Area {
    lock.await()
    val issuePpl = setup.issuePpl
    val signals = issuePpl.signals
    val s0_decode = issuePpl.pipeline.s0_decode

    ParallaxLogger.log(s"DecodePlugin: logic LATE area entered for s0_decode")

    val groupPcIn = s0_decode(signals.GROUP_PC_IN)
    val rawInstructionsIn = s0_decode(signals.RAW_INSTRUCTIONS_IN)
    val isGroupFaultIn = s0_decode(signals.IS_FAULT_IN)
    val groupValidMask = s0_decode(signals.VALID_MASK)

    val decodedUopsOutputVec = Vec(HardType(DecodedUop(issueConfig)), issueConfig.fetchWidth)

    for (i <- 0 until issueConfig.fetchWidth) {
      val decoder = new LA32RSimpleDecoder(issueConfig)
      val instructionPC = groupPcIn + U(i * issueConfig.bytesPerInstruction)

      decoder.io.instruction := rawInstructionsIn(i)
      decoder.io.pcIn := instructionPC

      val currentDecodedUop = DecodedUop(issueConfig)
      currentDecodedUop := decoder.io.decodedUop

      // -- MODIFICATION START: Early NOP discard logic --
      
      // A uop is a NOP if it's an ALU/SHIFT op that doesn't write a register.
      // This is the most common form of NOP in many ISAs (e.g., add r0, r0, 0).
      val isAluNop = (currentDecodedUop.uopCode === BaseUopCode.ALU || currentDecodedUop.uopCode === BaseUopCode.SHIFT) && !currentDecodedUop.writeArchDestEn
      
      // A decoded NOP uop is also a NOP.
      val isDecodedNop = currentDecodedUop.uopCode === BaseUopCode.NOP
      
      val isNop = isAluNop || isDecodedNop

      // If it's a NOP, invalidate it so subsequent stages ignore it.
      when(isNop) {
        currentDecodedUop.isValid := False
      }
      
      // -- MODIFICATION END --
      
      when(isGroupFaultIn) {
        currentDecodedUop.isValid := False
        currentDecodedUop.hasDecodeException := True
        currentDecodedUop.decodeExceptionCode := DecodeExCode.FETCH_ERROR
        currentDecodedUop.uopCode := BaseUopCode.ILLEGAL
      }
      
      when(!groupValidMask(i)) {
        currentDecodedUop.isValid := False
      }
      
      decodedUopsOutputVec(i) := currentDecodedUop
    }

    s0_decode(signals.DECODED_UOPS) := decodedUopsOutputVec

    when(s0_decode.isFiring) {
      report(L"DecodePlugin (s0_decode): Firing. Input PC_Group=${groupPcIn}, Input GroupFault=${isGroupFaultIn}")
      for (i <- 0 until issueConfig.fetchWidth) {
        val pc = groupPcIn + U(i * issueConfig.bytesPerInstruction)
        report(
          L"  Slot ${i.toString()}: RawInstr=${rawInstructionsIn(i)}, Calc PC=${pc} -> Decoded PC=${decodedUopsOutputVec(i).pc}, Valid=${decodedUopsOutputVec(
              i
            ).isValid}, UopCode=${decodedUopsOutputVec(i).uopCode}, Excp=${decodedUopsOutputVec(i).hasDecodeException}"
        )
      }
    }
    report(L"DEBUG: s0_decode.isFiring=${s0_decode.isFiring}, groupValidMask=${groupValidMask}, isGroupFaultIn=${isGroupFaultIn}")

    setup.issuePpl.release()
  }
}
