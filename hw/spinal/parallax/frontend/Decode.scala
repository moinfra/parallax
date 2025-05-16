package parallax.frontend

import spinal.core._
import spinal.core.fiber.{Handle, Lock} 
import spinal.lib._
import spinal.lib.pipeline._
import spinal.lib.misc.pipeline.CtrlLink 
import spinal.lib.StreamArbiterFactory
import spinal.lib.pipeline.Stage
import spinal.lib.pipeline.Pipeline
import spinal.lib.fsm._ 

import scala.collection.mutable.ArrayBuffer
import parallax.utilities.{Plugin, DataBase, ProjectScope, Framework, Service}
import spinal.lib.pipeline.Connection.M2S
import parallax.utilities.LockedImpl
import parallax.common._

class DecodePlugin extends Plugin with LockedImpl {
  val setup = create early new Area {
    val frontendPipeline = getService[FrontendPipeline]
  }

  val logic = create late new Area {
    val stage = setup.frontendPipeline.pipeline.decode
    import stage._

    val inputPc = stage(FrontendPipelineKeys.PC)
    val inputInstruction = stage(FrontendPipelineKeys.INSTRUCTION)
    val inputFault = stage(FrontendPipelineKeys.FETCH_FAULT)

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
    stage(FrontendPipelineKeys.UOP) := uop
  }
}
