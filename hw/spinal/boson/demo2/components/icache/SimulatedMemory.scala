package boson.demo2.components.icache

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._ 

// 模拟内存，带延迟
class SimulatedMemory(implicit config: SimpleICacheConfig) extends Component {
  val io = new Bundle {
    val bus = slave(ICacheMemBus())
    // For testbench to pre-load memory
    val writeEnable = in Bool() default(False)
    val writeAddress = in UInt(config.addressWidth bits) default(0)
    val writeData = in Bits(config.dataWidth bits) default(0)
  }

  val mem = Mem(Bits(config.dataWidth bits), wordCount = 1 << (config.addressWidth - log2Up(config.dataWidth/8)))
  mem.init(Seq.fill(mem.wordCount)(B(0)))

  // Testbench write port
  when(io.writeEnable) {
    mem.write(
      address = io.writeAddress(config.addressWidth - 1 downto log2Up(config.dataWidth/8)),
      data = io.writeData
    )
  }

  // Registers used by the FSM logic
  val wordCounter = Reg(UInt(config.wordOffsetWidth bits)) init (0)
  val currentLineAddrReg = Reg(UInt(config.addressWidth - config.byteOffsetWidth bits))
  val latencyCounter = Reg(UInt(log2Up(config.simulatedMemLatency + 1) bits)) init(0)

  // Combinational calculation of current word address
  val addressBase = (currentLineAddrReg ## U(0, config.wordOffsetWidth bits)).asUInt
  val currentWordAddress = addressBase + wordCounter

  // This signal will be controlled by the FSM to enable memory read
  val memReadEnable = Bool() 
  memReadEnable := False // Default to False

  // Memory read operation
  // The FSM will set memReadEnable = True in the cycle data is expected to be valid *after* latency
  val memDataOut = mem.readSync(
    address = currentWordAddress(config.addressWidth - 1 downto log2Up(config.dataWidth/8)),
    enable = memReadEnable 
  )

  // --- State Machine Definition ---
  val fsm = new StateMachine {
    // Default assignments for outputs (good practice)
    io.bus.cmd.ready := False
    io.bus.rsp.valid := False
    io.bus.rsp.payload.data := 0 // Will be overridden by memDataOut when valid

    // --- States ---
    val sIdle: State = new State with EntryPoint // Mark sIdle as the starting state
    val sRead: State = new State

    // --- sIdle State Logic ---
    sIdle.whenIsActive {
      io.bus.cmd.ready := True
      when(io.bus.cmd.fire) {
        currentLineAddrReg := io.bus.cmd.payload.lineAddress
        wordCounter := 0
        latencyCounter := 0 // Reset latency counter for the new request
        goto(sRead)
      }
    }

    // --- sRead State Logic ---
    sRead.whenIsActive {
      // Simulate memory read latency for each word
      val responseReady = latencyCounter === config.simulatedMemLatency

      when(latencyCounter < config.simulatedMemLatency) {
        latencyCounter := latencyCounter + 1
      }
      
      when(responseReady) {
        memReadEnable := True       // Enable the actual memory read for this cycle
        io.bus.rsp.valid := True
        io.bus.rsp.payload.data := memDataOut // Data from mem.readSync

        when(io.bus.rsp.fire) { // Wait for cache to accept the word
          latencyCounter := 0     // Reset latency for the next word in the line
          wordCounter := wordCounter + 1
          when(wordCounter === (config.wordsPerLine - 1)) { // Last word of the line
            goto(sIdle)
          }
          // Otherwise, stay in sRead to send the next word (wordCounter already incremented)
          // latencyCounter will restart from 0 for the next word's latency
        }
      }
    }
  } // End of StateMachine
}
