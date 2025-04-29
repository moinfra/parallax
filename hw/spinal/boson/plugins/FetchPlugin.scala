// src/main/scala/boson/plugins/FetchPlugin.scala
package boson.plugins

import spinal.core._
import spinal.lib._
import boson.interfaces._ // Import internal interfaces
import boson._
import spinal.lib.fsm._ // Import FSM library

class FetchPlugin() extends Plugin[Boson] {


  override def setup(pipeline: Boson): Unit = {
  }

  override def build(pipeline: Boson): Unit = {
    import pipeline.{config => pcfg};

    pipeline.fetch plug new Area {
      import pipeline.fetch._ // Access arbitration signals like isValid, isStuck, haltItself, isFiring, removeIt

      val fetchPc = pipeline.fetch.input(pcfg.PC) // Get PC from PcManagerPlugin

      // --- Interface to AxiMemoryBusComponent ---
      val cmd = pipeline.memoryBus.io.iBusCmd
      val rsp = pipeline.memoryBus.io.iBusRsp

      // --- Response Holding Register ---
      // Latches the response from the memory bus plugin's FIFO
      // Initialize using the companion object method (assuming it exists and is accessible)
      val rspDataReg = Reg(InstrFetchRsp(pcfg)).init(InstrFetchRsp.zero(pcfg))
      val hasValidResponse = RegInit(False) // Indicates if rspDataReg holds fresh data

      // --- Default Signal Assignments ---
      // Set defaults for signals controlled by the FSM
      cmd.valid := False
      cmd.payload.address := fetchPc // Address is always the current fetchPc
      rsp.ready := False            // Default: Not ready to accept response
      arbitration.haltItself := False // Default: Don't stall pipeline stage
      insert(pcfg.INSTRUCTION).assignDontCare() // Avoid latches for data payload
      insert(pcfg.IS_FETCH_INST_VALID) := False        // Default: Instruction not valid downstream

      // --- Fetch State Machine using StateMachine library ---
      val fsm = new StateMachine {
        // Define States
        val IDLE = new State with EntryPoint // Initial state
        val WAIT_CMD_ACCEPT = new State
        val WAIT_RSP = new State
        val RSP_READY = new State

        // --- State Transitions and Actions ---

        // IDLE: Wait for valid PC and ready downstream, then issue command
        IDLE.whenIsActive {
          report(L"[Fetch FSM] IDLE (isValid=${arbitration.isValid}, isStuck=${arbitration.isStuck})")
          when(arbitration.isValid && !arbitration.isStuck) {
            cmd.valid := True // Attempt to send command
            when(cmd.ready) { // Command accepted by bus plugin?
              report(L"[Fetch FSM] IDLE -> WAIT_RSP (Cmd Accepted)")
              goto(WAIT_RSP)  // Yes, wait for response
            } otherwise {
              report(L"[Fetch FSM] IDLE -> WAIT_CMD_ACCEPT (Cmd NOT Accepted)")
              arbitration.haltItself := True // Stall pipeline here
              goto(WAIT_CMD_ACCEPT) // No, wait for command FIFO to be ready
            }
          }
        }

        // WAIT_CMD_ACCEPT: Retry sending command if bus wasn't ready
        WAIT_CMD_ACCEPT.whenIsActive {
          report(L"[Fetch FSM] WAIT_CMD_ACCEPT (Cmd NOT Accepted)")
          arbitration.haltItself := True // Keep pipeline stalled
          cmd.valid := True          // Keep asserting command
          when(cmd.ready) {          // Command accepted now?
            report(L"[Fetch FSM] WAIT_CMD_ACCEPT -> WAIT_RSP (Cmd Accepted)")
            goto(WAIT_RSP)           // Yes, wait for response
          }
        }

        // WAIT_RSP: Command sent, wait for response from bus plugin
        WAIT_RSP.whenIsActive {
          report(L"[Fetch FSM] WAIT_RSP (rsp.valid=${rsp.valid}, hasValidResponse=${hasValidResponse})")
          arbitration.haltItself := True     // Stall pipeline while waiting
          rsp.ready := !hasValidResponse // We are ready for a response *if* we aren't already holding one
          when(rsp.valid) {            // Response available from bus plugin?
            rspDataReg := rsp.payload    // Latch the response data
            hasValidResponse := True     // Mark that we have valid data
            report(
              L"[Fetch FSM] WAIT_RSP -> RSP_READY (Rsp Received: Instr=${rsp.payload.instruction} Err=${rsp.payload.error})"
            )
            goto(RSP_READY)              // Move to process the response
          }
        }

        // RSP_READY: Response received, ready to insert downstream
        RSP_READY.whenIsActive {
          report(L"[Fetch FSM] RSP_READY (isFiring=${arbitration.isFiring})")
          // Drive pipeline internal signals
          insert(pcfg.INSTRUCTION) := rspDataReg.instruction
          // Instruction is valid only if there was no fetch error
          insert(pcfg.IS_FETCH_INST_VALID) := !rspDataReg.error

          when(arbitration.isFiring) { // Can the instruction proceed downstream?
            report(L"[Fetch FSM] RSP_READY -> IDLE (Firing Downstream)")
            hasValidResponse := False    // Consume the response
            goto(IDLE)                 // Ready for the next fetch cycle
          } otherwise {
            // Downstream is stalled, hold the response and stall the pipeline stage
            report(L"[Fetch FSM] RSP_READY (Stalled Downstream)")
            arbitration.haltItself := True
          }
        }

        // --- Pipeline Flush Handling (Global Transition) ---
        // Use always clauses for conditions that should override state logic, like flushing.
        // This needs to be checked in every state to ensure immediate reaction.
        always {
          when(arbitration.removeIt) {
             report(L"[Fetch FSM] State Resetting due to Flush (removeIt=True)")
             hasValidResponse := False // Clear any latched response
             goto(IDLE)               // Force back to IDLE state
          }
        }
      } // End of StateMachine definition

      // --- Reporting --- (Combine state and key signals)
      // Use fsm.currentState for reporting the current state enum value
      report(
        L"[Fetch] State=${fsm.stateReg} Firing=${arbitration.isFiring} PC=${fetchPc} " ++
          L"Cmd(V=${cmd.valid}, R=${cmd.ready}) Rsp(V=${rsp.valid}, R=${rsp.ready}) " ++
          L"Halt=${arbitration.haltItself} HasRsp=${hasValidResponse} RspData(I=${rspDataReg.instruction}, E=${rspDataReg.error})"
      )

      // Optional: Add assertion for fetch error (can be handled later by exception logic)
      when(arbitration.isFiring && fsm.isActive(fsm.RSP_READY) && rspDataReg.error) {
        report(L"[Fetch] ERROR: Firing instruction with fetch error for PC=${fetchPc}")
        // spinal.core.assert(!rspDataReg.error, "Fetch error occurred when firing instruction", FAILURE) // Uncomment to fail simulation
      }
    } // End of fetch Area
  } // End of build method
} // End of FetchPlugin class
