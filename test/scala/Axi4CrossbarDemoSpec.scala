package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.fsm._
import spinal.tester.SpinalSimFunSuite
import parallax.components.memory._
import spinal.lib.bus.misc.BusSlaveFactoryRead
import spinal.lib.bus.misc.BusSlaveFactoryWrite

object GmbAxiConverterUtils {
  def createAxiMasterConfig(gmbConf: GenericMemoryBusConfig, templateConfig: Axi4Config): Axi4Config = {
    Axi4Config(
      addressWidth = gmbConf.addressWidth.value,
      dataWidth = gmbConf.dataWidth.value,
      idWidth =
        if (templateConfig != null && templateConfig.useId) templateConfig.idWidth
        else if (gmbConf.useId) gmbConf.idWidth.value
        else 4, // Default if not specified by GMB and template doesn't override useId=false
      useId = if (templateConfig != null) templateConfig.useId else gmbConf.useId,
      useSize = if (templateConfig != null) templateConfig.useSize else true,
      useLen = if (templateConfig != null) templateConfig.useLen else true,
      useBurst = if (templateConfig != null) templateConfig.useBurst else true,
      useLast = if (templateConfig != null) templateConfig.useLast else true,
      useStrb = if (templateConfig != null) templateConfig.useStrb else true,
      useResp = if (templateConfig != null) templateConfig.useResp else true,
      useRegion = false,
      useLock = false,
      useCache = false,
      useProt = false,
      useQos = false
    )
  }
}

// The Bridge Component
  class GenericBusToAxi4SharedBridge(
      val gmbConfig: GenericMemoryBusConfig,
      axiConfigIn: Axi4Config = null
  ) extends Component {
    val effectiveAxiConfig = GmbAxiConverterUtils.createAxiMasterConfig(gmbConfig, axiConfigIn)

    val io = new Bundle {
      val gmbIn = slave(GenericMemoryBus(gmbConfig))
      val axiOut = master(Axi4Shared(effectiveAxiConfig))
    }

// --- Command Path Logic (GMB -> AXI) ---
    val gmbCmdIsWrite = io.gmbIn.cmd.payload.opcode === GenericMemoryBusOpcode.OP_WRITE

// Default assignments for AXI command outputs
    io.axiOut.sharedCmd.valid := False
    io.axiOut.sharedCmd.payload.assignDontCare() // Important to avoid latches
    io.axiOut.writeData.valid := False
    io.axiOut.writeData.payload.assignDontCare() // Important

// --- Response Path FSM (as defined above) ---
    val responseFsm = new StateMachine {
      // States
      val sIdle = new State with EntryPoint
      val sWaitingAxiReadRsp = new State
      val sWaitingAxiWriteRsp = new State

      val gmbTransactionIdReg = (gmbConfig.useId && effectiveAxiConfig.useId) generate Reg(UInt(gmbConfig.idWidth))

      // Default assignments for outputs controlled by this FSM part
      io.gmbIn.rsp.valid := False
      io.gmbIn.rsp.payload.readData.assignDontCare()
      io.gmbIn.rsp.payload.error := False
      if (gmbConfig.useId && io.gmbIn.rsp.payload.id != null) {
        io.gmbIn.rsp.payload.id.assignDontCare()
      }
      io.axiOut.readRsp.ready := False
      io.axiOut.writeRsp.ready := False

      sIdle
        .whenIsActive {
          // Transition when a GMB command fires and is accepted by AXI
          // The gmbIn.cmd.fire condition is implicitly io.gmbIn.cmd.valid && io.gmbIn.cmd.ready
          // and io.gmbIn.cmd.ready is true only if FSM is in sIdle and AXI is ready.
          when(io.gmbIn.cmd.fire) { // This means AXI command was also sent in this cycle
            if (gmbConfig.useId && effectiveAxiConfig.useId) {
              gmbTransactionIdReg := io.gmbIn.cmd.payload.id
            }
            when(gmbCmdIsWrite) { // gmbCmdIsWrite is based on current io.gmbIn.cmd
              goto(sWaitingAxiWriteRsp)
              report(
                L"[BridgeFSM] Transition: sIdle -> sWaitingAxiWriteRsp (GMB ID=${if (gmbConfig.useId && effectiveAxiConfig.useId)
                    io.gmbIn.cmd.payload.id
                  else U(0).setName("0")})"
              )
            } otherwise {
              goto(sWaitingAxiReadRsp)
              report(
                L"[BridgeFSM] Transition: sIdle -> sWaitingAxiReadRsp (GMB ID=${if (gmbConfig.useId && effectiveAxiConfig.useId)
                    io.gmbIn.cmd.payload.id
                  else U(0).setName("0")})"
              )
            }
          }
        }

      sWaitingAxiReadRsp
        .whenIsActive {
          io.axiOut.readRsp.ready := io.gmbIn.rsp.ready
          report(
            L"[BridgeFSM] sWaitingAxiReadRsp: axiOut.r.ready=${io.axiOut.readRsp.ready}, gmbIn.rsp.ready=${io.gmbIn.rsp.ready}"
          )
          when(io.axiOut.readRsp.fire) {
            val idMatch =
              if (gmbConfig.useId && effectiveAxiConfig.useId)
                io.axiOut.readRsp.payload.id === gmbTransactionIdReg.resized
              else True
            report(
              L"[BridgeFSM] sWaitingAxiReadRsp: AXI R Fire! axiId=${io.axiOut.readRsp.payload.id}, expectedGmbId=${if (gmbConfig.useId && effectiveAxiConfig.useId) gmbTransactionIdReg
                else U(0).setName("0")}, idMatch=${idMatch}"
            )
            when(idMatch) {
              io.gmbIn.rsp.valid := True
              io.gmbIn.rsp.payload.readData := io.axiOut.readRsp.payload.data.asBits
              io.gmbIn.rsp.payload.error := io.axiOut.readRsp.payload.resp =/= Axi4.resp.OKAY
              if (gmbConfig.useId && effectiveAxiConfig.useId && io.gmbIn.rsp.payload.id != null) {
                io.gmbIn.rsp.payload.id := io.axiOut.readRsp.payload.id.resized
              }
            } otherwise { report(L"[BridgeFSM] sWaitingAxiReadRsp: ID MISMATCH!") }
            when(io.gmbIn.rsp.fire) { // If GMB output was consumed
              goto(sIdle)
              report(L"[BridgeFSM] Transition: sWaitingAxiReadRsp -> sIdle (AXI R Fire & GMB Rsp Fire)")
            }
          }
        }

      sWaitingAxiWriteRsp
        .whenIsActive {
          io.axiOut.writeRsp.ready := io.gmbIn.rsp.ready
          report(
            L"[BridgeFSM] sWaitingAxiWriteRsp: axiOut.b.ready=${io.axiOut.writeRsp.ready}, gmbIn.rsp.ready=${io.gmbIn.rsp.ready}"
          )
          when(io.axiOut.writeRsp.fire) {
            val idMatch =
              if (gmbConfig.useId && effectiveAxiConfig.useId)
                io.axiOut.writeRsp.payload.id === gmbTransactionIdReg.resized
              else True
            report(
              L"[BridgeFSM] sWaitingAxiWriteRsp: AXI B Fire! axiId=${io.axiOut.writeRsp.payload.id}, expectedGmbId=${if (gmbConfig.useId && effectiveAxiConfig.useId) gmbTransactionIdReg
                else U(0).setName("0")}, idMatch=${idMatch}"
            )
            when(idMatch) {
              io.gmbIn.rsp.valid := True
              io.gmbIn.rsp.payload.error := io.axiOut.writeRsp.payload.resp =/= Axi4.resp.OKAY
              if (gmbConfig.useId && effectiveAxiConfig.useId && io.gmbIn.rsp.payload.id != null) {
                io.gmbIn.rsp.payload.id := io.axiOut.writeRsp.payload.id.resized
              }
            } otherwise { report(L"[BridgeFSM] sWaitingAxiWriteRsp: ID MISMATCH!") }
            when(io.gmbIn.rsp.fire) { // If GMB output was consumed
              goto(sIdle)
              report(L"[BridgeFSM] Transition: sWaitingAxiWriteRsp -> sIdle (AXI B Fire & GMB Rsp Fire)")
            }
          }
        }
    } // End FSM

// Drive AXI command outputs when bridge is ready to send (i.e., FSM is in sIdle)
// And GMB command is valid
    when(responseFsm.isActive(responseFsm.sIdle) && io.gmbIn.cmd.valid) {
      io.axiOut.sharedCmd.valid := True
      io.axiOut.sharedCmd.payload.addr := io.gmbIn.cmd.payload.address
      io.axiOut.sharedCmd.payload.write := gmbCmdIsWrite
      if (effectiveAxiConfig.useId) {
        io.axiOut.sharedCmd.payload.id := (if (gmbConfig.useId) io.gmbIn.cmd.payload.id
                                           else U(0, effectiveAxiConfig.idWidth bits).resized)
      }
      if (effectiveAxiConfig.useLen) io.axiOut.sharedCmd.payload.len := U(0)
      if (effectiveAxiConfig.useBurst) io.axiOut.sharedCmd.payload.burst := 1
      if (effectiveAxiConfig.useSize)
        io.axiOut.sharedCmd.payload.size := U(log2Up(gmbConfig.dataWidth.value / 8), effectiveAxiConfig.sizeWidth bits)

      when(gmbCmdIsWrite) {
        io.axiOut.writeData.valid := True
        io.axiOut.writeData.payload.data := io.gmbIn.cmd.payload.writeData
        io.axiOut.writeData.payload.strb := io.gmbIn.cmd.payload.writeByteEnables
        if (effectiveAxiConfig.useLast) io.axiOut.writeData.payload.last := True
      }
    }

// GMB command ready is high if FSM is idle AND AXI can accept the command
    io.gmbIn.cmd.ready := responseFsm.isActive(responseFsm.sIdle) &&
      Mux(gmbCmdIsWrite, io.axiOut.sharedCmd.ready && io.axiOut.writeData.ready, io.axiOut.sharedCmd.ready)

    when(io.axiOut.writeRsp.valid) { // When AXI BVALID is received by bridge
      report(
        L"[Bridge] AXI_WRITE_RSP Valid received by bridge: id=${if (effectiveAxiConfig.useId) io.axiOut.writeRsp.payload.id
          else U(0).setName("0")}, resp=${io.axiOut.writeRsp.payload.resp}"
      )
    }
    when(io.gmbIn.cmd.fire) {
      report(
        L"[Bridge] GMB_CMD Fire: addr=0x${io.gmbIn.cmd.payload.address}, op=${io.gmbIn.cmd.payload.opcode}, id=${if (gmbConfig.useId)
            io.gmbIn.cmd.payload.id
          else U(0).setName("0")}\n"
      )
    }

// Log AXI commands sent by the bridge
    when(io.axiOut.sharedCmd.fire) { // For AW or AR
      report(
        L"[Bridge] AXI_SHARED_CMD Fire: addr=0x${io.axiOut.sharedCmd.payload.addr}, write=${io.axiOut.sharedCmd.payload.write}, id=${if (effectiveAxiConfig.useId)
            io.axiOut.sharedCmd.payload.id
          else U(0).setName("0")}\n"
      )
    }
    when(io.axiOut.writeData.fire) { // For W
      report(L"[Bridge] AXI_WDATA Fire: data=0x${io.axiOut.writeData.payload.data}\n")
    }

// Log AXI responses received by the bridge
    when(io.axiOut.readRsp.fire) {
      report(
        L"[Bridge] AXI_READ_RSP Fire: data=0x${io.axiOut.readRsp.payload.data}, id=${if (effectiveAxiConfig.useId) io.axiOut.readRsp.payload.id
          else U(0).setName("0")}, resp=${io.axiOut.readRsp.payload.resp}\n"
      )
    }
    when(io.axiOut.writeRsp.fire) {
      report(L"[Bridge] AXI_WRITE_RSP Fire: id=${if (effectiveAxiConfig.useId) io.axiOut.writeRsp.payload.id
        else U(0).setName("0")}, resp=${io.axiOut.writeRsp.payload.resp}\n")
    }

// Log GMB responses sent by the bridge
    when(io.gmbIn.rsp.fire) {
      report(
        L"[Bridge] GMB_RSP Fire: data=0x${io.gmbIn.rsp.payload.readData}, error=${io.gmbIn.rsp.payload.error}, id=${if (gmbConfig.useId)
            io.gmbIn.rsp.payload.id
          else U(0).setName("0")}\n"
      )
    }

  }

class BridgeSpec extends SpinalSimFunSuite {
  onlyVerilator()
  test("GenericBusToAxi4SharedBridge_UnitTest") {
    val GMB_CONFIG = GenericMemoryBusConfig(
      addressWidth = 8 bits, // Use a reasonable address width for RAM
      dataWidth = 32 bits,
      useId = true,
      idWidth = 3 bits // Example GMB ID width
    )
// AXI config that the bridge will output, and the RAM slave will expect
    val AXI_SHARED_CONFIG = Axi4Config(
      addressWidth = GMB_CONFIG.addressWidth.value,
      dataWidth = GMB_CONFIG.dataWidth.value,
      idWidth = GMB_CONFIG.idWidth.value, // Bridge AXI ID width matches GMB ID width
      useId = GMB_CONFIG.useId,
      useSize = true,
      useLen = true,
      useBurst = true,
      useLast = true,
      useStrb = true,
      useResp = true,
      useRegion = false,
      useLock = false,
      useCache = false,
      useProt = false,
      useQos = false
    )

    SimConfig.withWave
      .compile(new Component {
        val io = new Bundle {
          val gmbCmdIn = slave(Stream(GenericMemoryBusCmd(GMB_CONFIG)))
          val gmbRspOut = master(Stream(GenericMemoryBusRsp(GMB_CONFIG)))
        }
        val bridge = new GenericBusToAxi4SharedBridge(GMB_CONFIG, AXI_SHARED_CONFIG)
        bridge.io.gmbIn.cmd << io.gmbCmdIn
        io.gmbRspOut << bridge.io.gmbIn.rsp

        // Use Axi4SharedOnChipRam as the AXI slave backend
        val axiRam = Axi4SharedOnChipRam(
          dataWidth = AXI_SHARED_CONFIG.dataWidth,
          byteCount = 256, // Smaller RAM for simpler testing, ensure addresses are within this
          idWidth = AXI_SHARED_CONFIG.idWidth
        )
        axiRam.io.axi <> bridge.io.axiOut

        // SimPublics for observation
        io.gmbCmdIn.simPublic()
        io.gmbCmdIn.payload.simPublic()
        io.gmbRspOut.simPublic()
        io.gmbRspOut.payload.simPublic()
        bridge.io.axiOut.simPublic()
        bridge.io.axiOut.sharedCmd.payload.simPublic()
        bridge.io.axiOut.writeData.payload.simPublic()
        bridge.io.axiOut.readRsp.payload.simPublic()
        bridge.io.axiOut.writeRsp.payload.simPublic()
        // Expose FSM state of the bridge for debugging
        // bridge.bridgeFsm.currentState.simPublic() // Assuming bridgeFsm is a val in the bridge
      })
      .doSimUntilVoid { dut =>
        implicit val cd = dut.clockDomain
        dut.clockDomain.forkStimulus(10)

        val gmbCmd = dut.io.gmbCmdIn
        val gmbRsp = dut.io.gmbRspOut

        // Testbench is always ready to accept GMB responses
        gmbRsp.ready #= true

        // Initialize GMB command stream
        gmbCmd.valid #= false
        gmbCmd.payload.assignDontCare()
        if (GMB_CONFIG.useId && gmbCmd.payload.id != null) gmbCmd.payload.id #= 0

        dut.clockDomain.waitSampling(5) // Initial settle

        // --- Write Operation ---
        val writeAddr = 0x40 // Address within the 256-byte RAM
        val writeData = BigInt("FEEDC0DE", 16)
        val writeId = 1

        println(
          s"BridgeUnitTest: Driving GMB Write: Addr=0x${writeAddr.toHexString}, Data=0x${writeData.toString(16)}, ID=$writeId"
        )
        gmbCmd.payload.address #= writeAddr
        gmbCmd.payload.opcode #= GenericMemoryBusOpcode.OP_WRITE
        gmbCmd.payload.writeData #= writeData
        gmbCmd.payload.writeByteEnables #= 0xf
        if (GMB_CONFIG.useId && gmbCmd.payload.id != null) gmbCmd.payload.id #= writeId
        dut.clockDomain.waitSampling() // Ensure payload is set

        gmbCmd.valid #= true

        // Wait for GMB command to be accepted by the bridge
        // This implies AXI AW and W were accepted by axiRam
        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbCmd.ready.toBoolean))
        println(s"BridgeUnitTest @${simTime() - 10}: GMB Write CMD accepted by bridge (ID=$writeId).")

        // IMPORTANT: Hold GMB valid for one more cycle AFTER it's accepted by the bridge
        // This is to accommodate the Axi4SlaveFactory-like behavior (if axiRam has it implicitly)
        // or to give bridge's FSM enough time if its AXI master side needs to hold valid.
        dut.clockDomain.waitSampling()
        println(
          s"BridgeUnitTest @${simTime() - 10}: Holding GMB Write CMD valid for one extra cycle. Now de-asserting."
        )
        gmbCmd.valid #= false

        // Wait for GMB response (from AXI B response)
        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbRsp.valid.toBoolean &&gmbRsp.ready.toBoolean) )
        println(
          s"BridgeUnitTest @${simTime() - 10}: GMB Write Rsp received: Error=${gmbRsp.payload.error.toBoolean}, ID=${if (
              GMB_CONFIG.useId && gmbRsp.payload.id != null
            ) gmbRsp.payload.id.toBigInt
            else -1}"
        )
        assert(!gmbRsp.payload.error.toBoolean, "GMB Write Rsp error flag was set")
        if (GMB_CONFIG.useId && gmbRsp.payload.id != null)
          assert(gmbRsp.payload.id.toBigInt == writeId, "GMB Write Rsp ID mismatch")

        dut.clockDomain.waitSampling(5)

        // --- Read Operation (to verify the write) ---
        // To correctly read from Axi4SharedOnChipRam, we might need the "dummy read" trick
        // if its read data path is indeed misaligned with its rvalid.

        // Dummy Read first
        val dummyReadId = 2
        println(s"BridgeUnitTest: Driving GMB Dummy Read (ID=$dummyReadId)")
        gmbCmd.payload.address #= 0x00 // Different address
        gmbCmd.payload.opcode #= GenericMemoryBusOpcode.OP_READ
        if (GMB_CONFIG.useId && gmbCmd.payload.id != null) gmbCmd.payload.id #= dummyReadId
        dut.clockDomain.waitSampling()
        gmbCmd.valid #= true
        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbCmd.ready.toBoolean))
        println(s"BridgeUnitTest @${simTime() - 10}: GMB Dummy Read CMD accepted (ID=$dummyReadId).")
        dut.clockDomain.waitSampling() // Hold valid
        gmbCmd.valid #= false
        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbRsp.valid.toBoolean &&gmbRsp.ready.toBoolean) )
        println(
          s"BridgeUnitTest @${simTime() - 10}: GMB Dummy Read Rsp received (ID=${if (GMB_CONFIG.useId && gmbRsp.payload.id != null) gmbRsp.payload.id.toBigInt
            else -1})."
        )

        dut.clockDomain.waitSampling(5)

        // Actual Read
        val readId = 3
        println(s"BridgeUnitTest: Driving GMB Read: Addr=0x${writeAddr.toHexString} (ID=$readId)")
        gmbCmd.payload.address #= writeAddr
        gmbCmd.payload.opcode #= GenericMemoryBusOpcode.OP_READ
        if (GMB_CONFIG.useId && gmbCmd.payload.id != null) gmbCmd.payload.id #= readId
        dut.clockDomain.waitSampling()
        gmbCmd.valid #= true

        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbCmd.ready.toBoolean))
        println(s"BridgeUnitTest @${simTime() - 10}: GMB Read CMD accepted (ID=$readId).")
        dut.clockDomain.waitSampling() // Hold valid
        gmbCmd.valid #= false

        assert(!dut.clockDomain.waitSamplingWhere(20)(gmbRsp.valid.toBoolean &&gmbRsp.ready.toBoolean))
        val readData = gmbRsp.payload.readData.toBigInt
        val rspReadId = if (GMB_CONFIG.useId && gmbRsp.payload.id != null) gmbRsp.payload.id.toBigInt else -1
        println(s"BridgeUnitTest @${simTime() - 10}: GMB Read Rsp received: Data=0x${readData
            .toString(16)}, Error=${gmbRsp.payload.error.toBoolean}, ID=${rspReadId}")
        assert(!gmbRsp.payload.error.toBoolean, "GMB Read Rsp error flag was set")
        if (GMB_CONFIG.useId && gmbRsp.payload.id != null) assert(rspReadId == readId, "GMB Read Rsp ID mismatch")
        assert(
          readData == writeData,
          s"Read data mismatch! Expected 0x${writeData.toString(16)}, got 0x${readData.toString(16)}"
        )

        dut.clockDomain.waitSampling(10)
        simSuccess()
      }
    println("--- Test: GenericBusToAxi4SharedBridge_UnitTest Passed ---")
  }

}

class Axi4CrossbarDemoSpec extends SpinalSimFunSuite {

  onlyVerilator() // If you only want to run with Verilator

  class Axi4SimulatedMemorySlave(
      axiConfig: Axi4Config,
      simMemConfig: SimulatedMemoryConfig,
      genericBusConfig: GenericMemoryBusConfig,
      axiBaseAddress: BigInt
  ) extends Component {
    val io = new Bundle {
      val axi = slave(Axi4(axiConfig))
      val tb_writeEnable = in Bool () default (False)
      val tb_writeAddress = in UInt (genericBusConfig.addressWidth) default (U(0, genericBusConfig.addressWidth))
      val tb_writeData = in Bits (simMemConfig.internalDataWidth) default (B(0, simMemConfig.internalDataWidth))
    }

    // --- START MANUAL LOGGING for memSlave ---
    when(io.axi.ar.fire) {
      report(L"[MemSlave-AXI] AR Fire: addr=0x${io.axi.ar.addr}, id=${io.axi.ar.id}\n")
    }
    when(io.axi.aw.fire) {
      report(L"[MemSlave-AXI] AW Fire: addr=0x${io.axi.aw.addr}, id=${io.axi.aw.id}\n")
    }
    when(io.axi.w.fire) {
      report(L"[MemSlave-AXI] W Fire: data=0x${io.axi.w.data}, strb=0x${io.axi.w.strb}\n")
    }
    when(io.axi.r.fire) {
      report(L"[MemSlave-AXI] R Fire: data=0x${io.axi.r.data}, id=${io.axi.r.id}, resp=${io.axi.r.resp}\n")
    }
    when(io.axi.b.fire) {
      report(L"[MemSlave-AXI] B Fire: id=${io.axi.b.id}, resp=${io.axi.b.resp}\n")
    }
    // --- END MANUAL LOGGING for memSlave ---

    val simMem = new parallax.components.memory.SimulatedGeneralMemory( // Explicitly use package
      memConfig = simMemConfig,
      busConfig = genericBusConfig,
      enableLog = true
    )
    simMem.io.writeEnable := io.tb_writeEnable
    simMem.io.writeAddress := io.tb_writeAddress
    simMem.io.writeData := io.tb_writeData

    val axiSlaveFsm = new StateMachine {
      val ar = io.axi.ar; val aw = io.axi.aw; val w = io.axi.w; val r = io.axi.r; val b = io.axi.b
      val regAxiId = Reg(axiConfig.idType); val regAxiAddr = Reg(axiConfig.addressType)
      val regAxiLen = Reg(axiConfig.lenType); val regAxiSize = Reg(UInt(axiConfig.sizeWidth bits))
      val regIsWrite = Reg(Bool())

      ar.ready := False; aw.ready := False; w.ready := False
      r.valid := False; b.valid := False
      if (axiConfig.useId && r.payload.id != null) { // Check if id field exists
        r.payload.id.assignDontCare()
      }
      r.payload.data.assignDontCare(); r.payload.resp.assignDontCare()
      if (axiConfig.useLast) r.payload.last.assignDontCare()
      if (axiConfig.useId && b.payload.id != null) {
        b.payload.id.assignDontCare()
      }
      b.payload.resp.assignDontCare()
      simMem.io.bus.cmd.valid := False; simMem.io.bus.cmd.payload.assignDontCare(); simMem.io.bus.rsp.ready := False

      val sIdle: State = new State with EntryPoint {
        whenIsActive {
          report("[Axi4SimMemSlave] sIdle state active")

          ar.ready := True; aw.ready := True
          when(ar.fire) {
            regAxiId := ar.id; regAxiAddr := ar.addr; regAxiLen := ar.len; regAxiSize := ar.size
            regIsWrite := False; goto(sSendGenericCmd)
          } elsewhen (aw.fire) {
            regAxiId := aw.id; regAxiAddr := aw.addr; regAxiLen := aw.len; regAxiSize := aw.size
            regIsWrite := True; goto(sWaitForWriteData)
          }
        }
      }
      val sWaitForWriteData: State = new State {
        whenIsActive {
          report("[Axi4SimMemSlave] sWaitForWriteData state active")
          w.ready := True; when(w.fire) { goto(sSendGenericCmd) }
        }
      }
      val sSendGenericCmd: State = new State {
        whenIsActive {
          report("[Axi4SimMemSlave] sSendGenericCmd state active")

          simMem.io.bus.cmd.valid := True
          val effectiveAddressInSimMem = regAxiAddr - U(axiBaseAddress, axiConfig.addressWidth bits)
          simMem.io.bus.cmd.payload.address := effectiveAddressInSimMem.resize(genericBusConfig.addressWidth)

          simMem.io.bus.cmd.payload.opcode := Mux(
            regIsWrite,
            GenericMemoryBusOpcode.OP_WRITE,
            GenericMemoryBusOpcode.OP_READ
          )
          when(regIsWrite) {
            simMem.io.bus.cmd.payload.writeData := w.payload.data
            simMem.io.bus.cmd.payload.writeByteEnables := w.payload.strb
          } otherwise {
            simMem.io.bus.cmd.payload.writeData.assignDontCare()
            simMem.io.bus.cmd.payload.writeByteEnables.assignDontCare()
          }
          when(simMem.io.bus.cmd.ready) { goto(sWaitForGenericRsp) }
        }
      }
      val sWaitForGenericRsp: State = new State {
        whenIsActive {
          report("[Axi4SimMemSlave] sWaitForGenericRsp state active")
          simMem.io.bus.rsp.ready := True; when(simMem.io.bus.rsp.fire) { goto(sSendAxiRsp) }
        }
      }
      val sSendAxiRsp: State = new State {
        whenIsActive {
          report("[Axi4SimMemSlave] sSendAxiRsp state active")

          when(regIsWrite) {
            b.valid := True; if (axiConfig.useId && b.payload.id != null) b.payload.id := regAxiId
            b.payload.resp := simMem.io.bus.rsp.payload.error ? Axi4.resp.SLVERR | 0
            when(b.ready) { goto(sIdle) }
          } otherwise {
            r.valid := True; if (axiConfig.useId && r.payload.id != null) r.payload.id := regAxiId
            r.payload.data := simMem.io.bus.rsp.payload.readData
            r.payload.resp := simMem.io.bus.rsp.payload.error ? Axi4.resp.SLVERR | 0
            if (axiConfig.useLast) r.payload.last := True
            when(r.ready) { goto(sIdle) }
          }
        }
      }
    }
  }

  class ControlRegisterAxi4Slave(axiConfig: Axi4Config, axiBaseAddress: BigInt) extends Component {
    val io = new Bundle {
      val axi = slave(Axi4(axiConfig))
      val ctrlReg0Val = out UInt (axiConfig.dataWidth bits)
      val statusReg0Val = out UInt (axiConfig.dataWidth bits)
    }
    val factory = Axi4SlaveFactory(io.axi)
    val ctrlReg0 = factory.createReadWrite(UInt(axiConfig.dataWidth bits), axiBaseAddress, 0) init (0)
    val prevCtrlReg0 = RegNext(ctrlReg0) init (0)
    report(
      L"[CtrlSlave] Outputting: aw.ready=${io.axi.aw.ready}, w.ready=${io.axi.w.ready}, ar.ready=${io.axi.ar.ready}"
    )

    when(ctrlReg0 =/= prevCtrlReg0) {
      report(L"[CtrlSlave] ctrlReg0 changed from ${prevCtrlReg0} to ${ctrlReg0} at addr axiBaseAddress")
    }
    val statusReg0 =
      factory.createReadOnly(
        UInt(axiConfig.dataWidth bits),
        axiBaseAddress + 0x04,
        0
      ) init (0x12345678) allowUnsetRegToAvoidLatch ()
    io.ctrlReg0Val := ctrlReg0
    io.statusReg0Val := statusReg0

    when(io.axi.ar.fire) {
      report(L"[CtrlSlave-AXI] AR Fire: addr=0x${io.axi.ar.addr}, id=${io.axi.ar.id}\n")
    }
    when(io.axi.aw.fire) {
      report(L"[CtrlSlave-AXI] AW Fire: addr=0x${io.axi.aw.addr}, id=${io.axi.aw.id}\n")
    }
    when(io.axi.w.fire) {
      report(L"[CtrlSlave-AXI] W Fire: data=0x${io.axi.w.data}, strb=0x${io.axi.w.strb}\n")
    }
    when(io.axi.r.fire) {
      report(L"[CtrlSlave-AXI] R Fire: data=0x${io.axi.r.data}, id=${io.axi.r.id}, resp=${io.axi.r.resp}\n")
    }
    when(io.axi.b.fire) {
      report(L"[CtrlSlave-AXI] B Fire: id=${io.axi.b.id}, resp=${io.axi.b.resp}\n")
    }
    // In ControlRegisterAxi4Slave, around B channel handling
    when(io.axi.b.valid) { // Log when slave asserts b.valid
      report(L"[CtrlSlave-AXI] B Valid asserted: id=${io.axi.b.payload.id}, resp=${io.axi.b.payload.resp}")
    }
    report(L"[CtrlSlave-AXI] b.ready input from master/crossbar = ${io.axi.b.ready}")
    when(io.axi.b.ready) { // Log when master asserts b.ready
      report(L"[CtrlSlave-AXI] B Ready asserted by master")
    }
    when(io.axi.b.fire) { // This is the crucial one
      report(L"[CtrlSlave-AXI] B Fire: id=${io.axi.b.id}, resp=${io.axi.b.resp}")
    }
  }

  // --- TopSystemUsingBridge ---
  class TopSystemUsingBridge extends Component {
    // Define the "actual" ID width our GMB masters will use.
    val THE_MASTER_ID_WIDTH = 3 // Example: GMB masters use 3-bit IDs

    // Configuration for the GenericMemoryBus
    val genericBusConfig = GenericMemoryBusConfig(
      addressWidth = 32 bits,
      dataWidth = 32 bits,
      useId = true,
      idWidth = THE_MASTER_ID_WIDTH bits
    )

    // Configuration for the AXI Master interface that the bridge will output.
    val bridgeOutputAxiConfig = Axi4Config(
      addressWidth = genericBusConfig.addressWidth.value,
      dataWidth = genericBusConfig.dataWidth.value,
      idWidth = THE_MASTER_ID_WIDTH, // Bridge outputs AXI with this ID width
      useId = true,
      useSize = true,
      useLen = true,
      useBurst = true,
      useLast = true,
      useStrb = true,
      useResp = true,
      useRegion = false,
      useLock = false,
      useCache = false,
      useProt = false,
      useQos = false
    )
    // println(s"TopSystem: Bridge Output AXI Config: idWidth=${bridgeOutputAxiConfig.idWidth}, useId=${bridgeOutputAxiConfig.useId}")

    // Configuration for the AXI Slave interfaces.
    val inputsCountToSlaves = 2 // Each slave is connected to 2 masters (axiMaster0, axiMaster1)
    val arbiterRoutingBits = log2Up(inputsCountToSlaves) // = 1 bit for 2 masters
    val slaveEffectiveIdWidth = THE_MASTER_ID_WIDTH + arbiterRoutingBits
    val slaveAxiConfig = bridgeOutputAxiConfig.copy(
      idWidth = slaveEffectiveIdWidth
    )
    // println(s"TopSystem: Slave AXI Config: idWidth=${slaveAxiConfig.idWidth}, useId=${slaveAxiConfig.useId}")

    val simMemInternalCfg = SimulatedMemoryConfig(
      internalDataWidth = 16 bits,
      memSize = 4 KiB,
      initialLatency = 1,
      burstLatency = 0
    )

    val io = new Bundle {
      val master0ExternalCmd = slave Stream (GenericMemoryBusCmd(genericBusConfig))
      val master0ExternalRsp = master Stream (GenericMemoryBusRsp(genericBusConfig))
      val master1ExternalCmd = slave Stream (GenericMemoryBusCmd(genericBusConfig))
      val master1ExternalRsp = master Stream (GenericMemoryBusRsp(genericBusConfig))
      val ctrlSlaveCtrlReg0 = out UInt (bridgeOutputAxiConfig.dataWidth bits)
      val simMemTbWriteEnable = in Bool () default (False)
    }

    val bridge0 = new GenericBusToAxi4SharedBridge(genericBusConfig, bridgeOutputAxiConfig)
    bridge0.io.gmbIn.cmd << io.master0ExternalCmd
    io.master0ExternalRsp << bridge0.io.gmbIn.rsp
    val axiMaster0 = bridge0.io.axiOut

    val bridge1 = new GenericBusToAxi4SharedBridge(genericBusConfig, bridgeOutputAxiConfig)
    bridge1.io.gmbIn.cmd << io.master1ExternalCmd
    io.master1ExternalRsp << bridge1.io.gmbIn.rsp
    val axiMaster1 = bridge1.io.axiOut

    val memSlaveAxiBaseAddress = 0x80000000L
    val ctrlSlaveAxiBaseAddress = 0x90000000L
    val memSlave = new Axi4SimulatedMemorySlave(
      axiConfig = slaveAxiConfig,
      simMemConfig = simMemInternalCfg,
      genericBusConfig = genericBusConfig, // Internal GMB can use base GMB config
      axiBaseAddress = memSlaveAxiBaseAddress
    )
    memSlave.io.tb_writeEnable := io.simMemTbWriteEnable

    val ctrlSlave = new ControlRegisterAxi4Slave(slaveAxiConfig, ctrlSlaveAxiBaseAddress)
    io.ctrlSlaveCtrlReg0 := ctrlSlave.io.ctrlReg0Val

    val crossbar = Axi4CrossbarFactory()
    crossbar.addSlave(memSlave.io.axi, SizeMapping(memSlaveAxiBaseAddress, simMemInternalCfg.memSize))
    crossbar.addSlave(ctrlSlave.io.axi, SizeMapping(ctrlSlaveAxiBaseAddress, 1 KiB))
    crossbar.addConnection(axiMaster0, Seq(memSlave.io.axi, ctrlSlave.io.axi))
    crossbar.addConnection(axiMaster1, Seq(memSlave.io.axi, ctrlSlave.io.axi))
    crossbar.build()
  }

  object SimAxi4Master {
    def write(axi: Axi4, address: BigInt, data: BigInt, id: Int = 0, strb: BigInt = -1, cd: ClockDomain): Unit = {
      axi.aw.valid #= true; axi.aw.payload.addr #= address; axi.aw.payload.id #= id
      if (axi.config.useLen) axi.aw.payload.len #= 0
      else if (axi.aw.payload.len != null) axi.aw.payload.len.assignDontCare()
      if (axi.config.useSize) axi.aw.payload.size #= log2Up(axi.config.dataWidth / 8)
      else if (axi.aw.payload.size != null) axi.aw.payload.size.assignDontCare()
      if (axi.config.useBurst) axi.aw.payload.burst #= 1
      else if (axi.aw.payload.burst != null) axi.aw.payload.burst.assignDontCare()
      axi.w.valid #= true; axi.w.payload.data #= data
      axi.w.payload.strb #= (if (strb == -1) (BigInt(1) << axi.config.dataWidth / 8) - 1 else strb)
      if (axi.config.useLast) axi.w.payload.last #= true
      else if (axi.w.payload.last != null) axi.w.payload.last.assignDontCare()
      assert(!cd.waitSamplingWhere(5)(axi.aw.ready.toBoolean && axi.w.ready.toBoolean))
      axi.aw.valid #= false; axi.w.valid #= false
    }
    def read(axi: Axi4, address: BigInt, id: Int = 0, cd: ClockDomain): Unit = {
      axi.ar.valid #= true; axi.ar.payload.addr #= address; axi.ar.payload.id #= id
      if (axi.config.useLen) axi.ar.payload.len #= 0
      else if (axi.ar.payload.len != null) axi.ar.payload.len.assignDontCare()
      if (axi.config.useSize) axi.ar.payload.size #= log2Up(axi.config.dataWidth / 8)
      else if (axi.ar.payload.size != null) axi.ar.payload.size.assignDontCare()
      if (axi.config.useBurst) axi.ar.payload.burst #= 1
      else if (axi.ar.payload.burst != null) axi.ar.payload.burst.assignDontCare()
      assert(!cd.waitSamplingWhere(5)(axi.ar.ready.toBoolean))
      axi.ar.valid #= false
    }
  }

  // Test "Axi4SharedOnChipRam_Direct_Axi4Shared_Drive_Debug" - This is the one that passed with 0xcafebabe
  test("Axi4SharedOnChipRam_Direct_Axi4Shared_Drive_Debug") {
    val testIdWidth = 3
    SimConfig.withWave
      .compile({
        val dut = Axi4SharedOnChipRam(dataWidth = 32, byteCount = 256, idWidth = testIdWidth)
        dut.io.axi.simPublic()
        dut.io.axi.sharedCmd.payload.simPublic()
        dut.io.axi.writeData.payload.simPublic()
        dut.io.axi.readRsp.payload.simPublic() // <<<< ADD THIS
        dut.io.axi.writeRsp.payload.simPublic() // <<<< ADD THI
        dut
      })
      .doSimUntilVoid { dut =>
        implicit val cd = dut.clockDomain
        dut.clockDomain.forkStimulus(10)
        val axi = dut.io.axi
        axi.readRsp.ready #= true; axi.writeRsp.ready #= true

        println("RAM_Debug_Shared: Writing 0xcafebabe to 0x10 (ID=5)")
        axi.sharedCmd.valid #= true; axi.sharedCmd.payload.addr #= 0x10; axi.sharedCmd.payload.id #= 5
        axi.sharedCmd.payload.write #= true
        if (axi.config.useLen) axi.sharedCmd.payload.len #= 0
        else if (axi.sharedCmd.payload.len != null) axi.sharedCmd.payload.len.assignDontCare()
        if (axi.config.useBurst) axi.sharedCmd.payload.burst #= 1
        else if (axi.sharedCmd.payload.burst != null) axi.sharedCmd.payload.burst.assignDontCare()
        if (axi.config.useSize) axi.sharedCmd.payload.size #= log2Up(axi.config.dataWidth / 8)
        else if (axi.sharedCmd.payload.size != null) axi.sharedCmd.payload.size.assignDontCare()
        axi.writeData.valid #= true; axi.writeData.payload.data #= 0xcafebabeL; axi.writeData.payload.strb #= 0xf
        if (axi.config.useLast) axi.writeData.payload.last #= true
        else if (axi.writeData.payload.last != null) axi.writeData.payload.last.assignDontCare()
        cd.waitSamplingWhere(axi.sharedCmd.ready.toBoolean && axi.writeData.ready.toBoolean)
        axi.sharedCmd.valid #= false; axi.writeData.valid #= false
        cd.waitSamplingWhere(axi.writeRsp.valid.toBoolean && axi.writeRsp.payload.id.toInt == 5)
        println(s"RAM_Debug_Shared: Write Rsp ID=${axi.writeRsp.payload.id.toInt}, Resp=${axi.writeRsp.payload.resp}")
        assert(axi.writeRsp.payload.resp.toInt == 0)
        cd.waitSampling(5)

        println("RAM_Debug_Shared: Dummy Reading from 0x50 (ID=7)")
        axi.sharedCmd.valid #= true; axi.sharedCmd.payload.addr #= 0x50; axi.sharedCmd.payload.id #= 7
        axi.sharedCmd.payload.write #= false
        if (axi.config.useLen) axi.sharedCmd.payload.len #= 0
        else if (axi.sharedCmd.payload.len != null) axi.sharedCmd.payload.len.assignDontCare()
        if (axi.config.useBurst) axi.sharedCmd.payload.burst #= 1
        else if (axi.sharedCmd.payload.burst != null) axi.sharedCmd.payload.burst.assignDontCare()
        if (axi.config.useSize) axi.sharedCmd.payload.size #= log2Up(axi.config.dataWidth / 8)
        else if (axi.sharedCmd.payload.size != null) axi.sharedCmd.payload.size.assignDontCare()
        assert(!cd.waitSamplingWhere(20)(axi.sharedCmd.ready.toBoolean))
        axi.sharedCmd.valid #= false
        assert(!cd.waitSamplingWhere(20)(axi.readRsp.valid.toBoolean && axi.readRsp.payload.id.toInt == 7))
        println(
          s"RAM_Debug_Shared: Dummy Read Rsp ID=${axi.readRsp.payload.id.toInt}, Data=0x${axi.readRsp.payload.data.toBigInt
              .toString(16)}"
        )
        cd.waitSampling(5)

        println("RAM_Debug_Shared: Reading from 0x10 (ID=6)")
        axi.sharedCmd.valid #= true; axi.sharedCmd.payload.addr #= 0x10; axi.sharedCmd.payload.id #= 6
        axi.sharedCmd.payload.write #= false
        if (axi.config.useLen) axi.sharedCmd.payload.len #= 0
        else if (axi.sharedCmd.payload.len != null) axi.sharedCmd.payload.len.assignDontCare()
        if (axi.config.useBurst) axi.sharedCmd.payload.burst #= 1
        else if (axi.sharedCmd.payload.burst != null) axi.sharedCmd.payload.burst.assignDontCare()
        if (axi.config.useSize) axi.sharedCmd.payload.size #= log2Up(axi.config.dataWidth / 8)
        else if (axi.sharedCmd.payload.size != null) axi.sharedCmd.payload.size.assignDontCare()
        assert(!cd.waitSamplingWhere(20)(axi.sharedCmd.ready.toBoolean))
        axi.sharedCmd.valid #= false
        assert(!cd.waitSamplingWhere(20)(axi.readRsp.valid.toBoolean && axi.readRsp.payload.id.toInt == 6))
        val dataRead = axi.readRsp.payload.data.toBigInt; val idRead = axi.readRsp.payload.id.toInt
        println(
          s"RAM_Debug_Shared: Read Rsp ID=${idRead}, Data=0x${dataRead.toString(16)}, Resp=${axi.readRsp.payload.resp}"
        )
        assert(axi.readRsp.payload.resp.toInt == 0)
        assert(idRead == 6, s"RAM_Debug_Shared: Read ID mismatch! Expected 6, got $idRead")
        assert(
          dataRead == 0xcafebabeL,
          s"RAM_Debug_Shared: Read data mismatch! Expected 0xcafebabe, got 0x${dataRead.toString(16)}"
        )
        simSuccess()
      }
    println("--- Test: Axi4SharedOnChipRam_Direct_Axi4Shared_Drive_Debug Passed ---")
  }

  test("GenericBusToAxi4SharedBridge_Loopback") {
    val gmbConf = GenericMemoryBusConfig(addressWidth = 8 bits, dataWidth = 32 bits, useId = true, idWidth = 2 bits)
    val axiConfForBridgeAndRam = GmbAxiConverterUtils
      .createAxiMasterConfig(gmbConf, null)
      .copy(
        addressWidth = gmbConf.addressWidth.value,
        idWidth = gmbConf.idWidth.value,
        useId = gmbConf.useId,
        // Ensure these match Axi4SharedOnChipRamPort's expectations or defaults if it has any
        useLen = true,
        useBurst = true // Axi4SharedOnChipRamPort uses these
      )

    SimConfig.withWave
      .compile(new Component {
        val gmbDriverCmd = slave Stream (GenericMemoryBusCmd(gmbConf))
        val gmbMonitorRsp = master Stream (GenericMemoryBusRsp(gmbConf))
        val bridge = new GenericBusToAxi4SharedBridge(gmbConf, axiConfForBridgeAndRam)
        bridge.io.gmbIn.cmd << gmbDriverCmd
        gmbMonitorRsp << bridge.io.gmbIn.rsp

        val ramWordCount = (BigInt(1) << axiConfForBridgeAndRam.addressWidth) / (axiConfForBridgeAndRam.dataWidth / 8)
        val ramDevice = Mem(Bits(axiConfForBridgeAndRam.dataWidth bits), ramWordCount.toInt)
        val axiRamPort = Axi4SharedOnChipRamPort(axiConfForBridgeAndRam, ramDevice) // From spinal.lib
        axiRamPort.axi <> bridge.io.axiOut
      })
      .doSimUntilVoid { dut =>
        dut.clockDomain.forkStimulus(10)
        val driver = dut.gmbDriverCmd
        val monitor = dut.gmbMonitorRsp
        monitor.ready #= true

        println("TestBridge: Writing 0xcafebabe to 0x10 (ID=1 via GMB)")
        driver.valid #= true; driver.payload.address #= 0x10
        driver.payload.opcode #= GenericMemoryBusOpcode.OP_WRITE
        driver.payload.writeData #= 0xcafebabeL; driver.payload.writeByteEnables #= 0xf
        if (gmbConf.useId) driver.payload.id #= 1
        assert(!dut.clockDomain.waitSamplingWhere(20)(driver.ready.toBoolean), "wait sampling timeout")
        driver.valid #= false
        assert(!dut.clockDomain.waitSamplingWhere(20)(monitor.valid.toBoolean))
        println(
          s"TestBridge: Write Rsp Error=${monitor.payload.error.toBoolean} (ID=${if (gmbConf.useId) monitor.payload.id.toBigInt
            else -1})"
        )
        assert(!monitor.payload.error.toBoolean)
        if (gmbConf.useId) assert(monitor.payload.id.toBigInt == 1)
        dut.clockDomain.waitSampling(5)

        println("TestBridge: Reading 0x10 (ID=2 via GMB)")
        driver.valid #= true; driver.payload.address #= 0x10
        driver.payload.opcode #= GenericMemoryBusOpcode.OP_READ
        if (gmbConf.useId) driver.payload.id #= 2
        assert(!dut.clockDomain.waitSamplingWhere(20)(driver.ready.toBoolean), "wait sampling timeout")
        driver.valid #= false
        assert(!dut.clockDomain.waitSamplingWhere(20)(monitor.valid.toBoolean))
        val dataRead = monitor.payload.readData.toBigInt
        val idRead = if (gmbConf.useId) monitor.payload.id.toBigInt else -1
        println(
          s"TestBridge: Read Rsp Data=0x${dataRead.toString(16)}, Error=${monitor.payload.error.toBoolean} (ID=${idRead})"
        )
        assert(!monitor.payload.error.toBoolean)
        assert(
          dataRead == 0xcafebabeL,
          s"TestBridge: Read data mismatch! Expected 0xcafebabe, got 0x${dataRead.toString(16)}"
        )
        if (gmbConf.useId) assert(idRead == 2)

        dut.clockDomain.waitSampling(10)
        simSuccess()
      }
    println("--- Test: GenericBusToAxi4SharedBridge_Loopback Passed ---")
  }

  test("TopSystemUsingBridge integration test") {
    SimConfig.withWave.compile(new TopSystemUsingBridge()).doSimUntilVoid { dut =>
      dut.clockDomain.forkStimulus(10)
      val m0Cmd = dut.io.master0ExternalCmd; val m0Rsp = dut.io.master0ExternalRsp; m0Rsp.ready #= true
      val m1Cmd = dut.io.master1ExternalCmd; val m1Rsp = dut.io.master1ExternalRsp; m1Rsp.ready #= true

      // Initialize command streams
      m0Cmd.valid #= false
      m0Cmd.payload.assignDontCare()
      if (dut.genericBusConfig.useId && m0Cmd.payload.id != null) m0Cmd.payload.id #= 0

      m1Cmd.valid #= false
      m1Cmd.payload.assignDontCare()
      if (dut.genericBusConfig.useId && m1Cmd.payload.id != null) m1Cmd.payload.id #= 0

      dut.clockDomain.waitSampling(1) // Initial settle

      val master0Thread = fork {
        val m0WriteId = 6 // Assign specific ID
        val m0ReadId = 7 // Assign specific ID

        assert(!dut.clockDomain.waitSamplingWhere(20)(m0Cmd.ready.toBoolean))
        println(s"TopSystemTest M0 @${simTime()}: Writing 0x11223344 to 0x80000040 (ID=$m0WriteId)")

        m0Cmd.payload.address #= 0x80000040L
        m0Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_WRITE
        m0Cmd.payload.writeData #= 0x11223344L
        m0Cmd.payload.writeByteEnables #= 0xf
        if (dut.genericBusConfig.useId && m0Cmd.payload.id != null) m0Cmd.payload.id #= m0WriteId
        m0Cmd.valid #= true
        println(s"TopSystemTest M0 @${simTime()}: GMB Write CMD to memSlave accepted (ID=$m0WriteId).")
        dut.clockDomain.waitSampling() // Hold GMB valid for one extra cycle
        println(s"TopSystemTest M0 @${simTime()}: De-asserting GMB Write CMD valid (ID=$m0WriteId).")

        assert(!dut.clockDomain.waitSamplingWhere(20)(m0Rsp.valid.toBoolean))
        m0Cmd.valid #= false
        println(
          s"TopSystemTest M0 @${simTime()}: Write Rsp Error=${m0Rsp.payload.error.toBoolean} (ExpID=$m0WriteId, GotID=${if (
              dut.genericBusConfig.useId && m0Rsp.payload.id != null
            ) m0Rsp.payload.id.toBigInt
            else -1})"
        )
        assert(!m0Rsp.payload.error.toBoolean)
        if (dut.genericBusConfig.useId && m0Rsp.payload.id != null) assert(m0Rsp.payload.id.toBigInt == m0WriteId)
        dut.clockDomain.waitSampling(5)

        println(s"TopSystemTest M0 @${simTime()}: Reading 0x80000040 (ID=$m0ReadId)")
        m0Cmd.payload.address #= 0x80000040L
        m0Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_READ
        if (dut.genericBusConfig.useId && m0Cmd.payload.id != null) m0Cmd.payload.id #= m0ReadId
        dut.clockDomain.waitSampling()
        m0Cmd.valid #= true

        assert(!dut.clockDomain.waitSamplingWhere(20)(m0Cmd.ready.toBoolean))
        println(s"TopSystemTest M0 @${simTime()}: GMB Read CMD to memSlave accepted (ID=$m0ReadId).")
        // dut.clockDomain.waitSampling() // Hold GMB valid for one extra cycle
        println(s"TopSystemTest M0 @${simTime()}: De-asserting GMB Read CMD valid (ID=$m0ReadId).")
        m0Cmd.valid #= false

        assert(!dut.clockDomain.waitSamplingWhere(20)(m0Rsp.valid.toBoolean))
        val m0Data = m0Rsp.payload.readData.toBigInt
        val m0RspId = if (dut.genericBusConfig.useId && m0Rsp.payload.id != null) m0Rsp.payload.id.toBigInt else -1
        println(s"TopSystemTest M0 @${simTime()}: Read Rsp Data=0x${m0Data
            .toString(16)}, Error=${m0Rsp.payload.error.toBoolean} (ExpID=$m0ReadId, GotID=${m0RspId})")
        assert(!m0Rsp.payload.error.toBoolean)
        assert(m0Data == 0x11223344L)
        if (dut.genericBusConfig.useId && m0Rsp.payload.id != null) assert(m0RspId == m0ReadId)
        println("TopSystemTest M0: sequence finished.")
      }

      val master1Thread = fork {
        // dut.clockDomain.waitSampling(10) // Offset to reduce contention at start
        // val m1WriteId = 5 // Assign specific ID
        // val m1ReadId = 2  // Assign specific ID

        // println(s"TopSystemTest M1 @${simTime()}: Writing 0xAABBCCDD to 0x90000000 (CtrlReg0, ID=$m1WriteId)")
        // m1Cmd.payload.address #= 0x90000000L
        // m1Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_WRITE
        // m1Cmd.payload.writeData #= BigInt("AABBCCDD", 16)
        // m1Cmd.payload.writeByteEnables #= 0xf
        // if(dut.genericBusConfig.useId && m1Cmd.payload.id != null) m1Cmd.payload.id #= m1WriteId
        // dut.clockDomain.waitSampling()
        // m1Cmd.valid #= true

        // assert(!dut.clockDomain.waitSamplingWhere(20)(m1Cmd.ready.toBoolean))
        // println(s"TopSystemTest M1 @${simTime()}: GMB Write CMD to ctrlSlave accepted (ID=$m1WriteId).")
        // // BUG: dut.clockDomain.waitSampling() // Hold GMB valid for one extra cycle
        // println(s"TopSystemTest M1 @${simTime()}: De-asserting GMB Write CMD valid (ID=$m1WriteId).")
        // m1Cmd.valid #= false
        // assert(!dut.clockDomain.waitSamplingWhere(20)(m1Rsp.valid.toBoolean))
        // val m1RspIdWrite = if(dut.genericBusConfig.useId && m1Rsp.payload.id != null) m1Rsp.payload.id.toBigInt else -1
        // println(s"TopSystemTest M1 @${simTime()}: Write Rsp to Ctrl Error=${m1Rsp.payload.error.toBoolean} (ExpID=$m1WriteId, GotID=${m1RspIdWrite})")
        // assert(!m1Rsp.payload.error.toBoolean)
        // dut.clockDomain.waitSampling(1) // Allow register to update

        // if(dut.genericBusConfig.useId && m1Rsp.payload.id != null) assert(m1RspIdWrite == m1WriteId)

        // dut.clockDomain.waitSampling(1) // Allow register to update
        // val ctrlReg0Val = dut.io.ctrlSlaveCtrlReg0.toBigInt
        // println(s"TopSystemTest M1 @${simTime()}: CtrlReg0 value after write: 0x${ctrlReg0Val.toString(16)}")
        // assert(ctrlReg0Val == BigInt("AABBCCDD", 16))
        // dut.clockDomain.waitSampling(5)

        // println(s"TopSystemTest M1 @${simTime()}: Reading 0x90000004 (StatusReg0, ID=$m1ReadId)")
        // m1Cmd.payload.address #= 0x90000004L
        // m1Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_READ
        // if(dut.genericBusConfig.useId && m1Cmd.payload.id != null) m1Cmd.payload.id #= m1ReadId
        // dut.clockDomain.waitSampling()
        // m1Cmd.valid #= true

        // assert(!dut.clockDomain.waitSamplingWhere(20)(m1Cmd.ready.toBoolean))
        // println(s"TopSystemTest M1 @${simTime()}: GMB Read CMD to ctrlSlave accepted (ID=$m1ReadId).")
        // // BUG: dut.clockDomain.waitSampling() // Hold GMB valid for one extra cycle
        // println(s"TopSystemTest M1 @${simTime()}: De-asserting GMB Read CMD valid (ID=$m1ReadId).")
        // m1Cmd.valid #= false

        // assert(!dut.clockDomain.waitSamplingWhere(20)(m1Rsp.valid.toBoolean))
        // val m1Data = m1Rsp.payload.readData.toBigInt
        // val m1RspIdRead = if(dut.genericBusConfig.useId && m1Rsp.payload.id != null) m1Rsp.payload.id.toBigInt else -1
        // println(s"TopSystemTest M1 @${simTime()}: Read Rsp from Ctrl Data=0x${m1Data.toString(16)}, Error=${m1Rsp.payload.error.toBoolean} (ExpID=$m1ReadId, GotID=${m1RspIdRead})")
        // assert(!m1Rsp.payload.error.toBoolean)
        // assert(m1Data == 0x12345678L) // Status reg init value
        // if(dut.genericBusConfig.useId && m1Rsp.payload.id != null) assert(m1RspIdRead == m1ReadId)
        // println("TopSystemTest M1: sequence finished.")
      }

      master0Thread.join()
      master1Thread.join()
      dut.clockDomain.waitSampling(20)
      simSuccess()
    }
    println("--- Test: TopSystemUsingBridge_Integration Passed ---")
  }

  test("ControlRegisterAxi4Slave_DirectAxi4Drive") {
    val AXI_CONFIG = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 3,
      useId = true, // Use 3-bit ID
      useSize = true,
      useLen = true,
      useBurst = true,
      useLast = true, // Enable typical AXI signals
      useStrb = true,
      useResp = true,
      useRegion = false,
      useLock = false,
      useCache = false,
      useProt = false,
      useQos = false
    )

    SimConfig.withWave.compile(new ControlRegisterAxi4Slave(AXI_CONFIG, 0)).doSimUntilVoid { dut =>
      val cd = dut.clockDomain
      dut.clockDomain.forkStimulus(10)

      // Master is always ready to accept responses from slave
      dut.io.axi.r.ready #= true
      dut.io.axi.b.ready #= true

      dut.clockDomain.waitSampling(5) // Initial settle

      // --- Write Operation ---
      val writeAddr = 0x00
      val writeData = BigInt("DEADBEEF", 16)
      val writeId = 1
      println(s"DirectCtrlSlaveTest: Writing 0x${writeData.toString(16)} to 0x${writeAddr.toHexString} (ID=${writeId})")
      SimAxi4Master.write(dut.io.axi, writeAddr, writeData, id = writeId, cd = cd)

      // Wait for B response from slave
      assert(
        !dut.clockDomain.waitSamplingWhere(20)(dut.io.axi.b.valid.toBoolean && dut.io.axi.b.payload.id.toInt == writeId)
      )
      println(
        s"DirectCtrlSlaveTest: Write Rsp ID=${dut.io.axi.b.payload.id.toInt}, Resp=${dut.io.axi.b.payload.resp.toInt}"
      )
      assert(dut.io.axi.b.payload.resp.toInt == 0, "Write response not OKAY")
      dut.clockDomain.waitSampling(1) // Allow propagation of write

      val reg0Val = dut.io.ctrlReg0Val.toBigInt
      println(s"DirectCtrlSlaveTest: ctrlReg0Val = 0x${reg0Val.toString(16)}")
      assert(
        reg0Val == writeData,
        s"CtrlReg0 data mismatch! Expected 0x${writeData.toString(16)}, got 0x${reg0Val.toString(16)}"
      )

      dut.clockDomain.waitSampling(5)

      // --- Read Operation ---
      val readAddr = 0x00
      val readId = 2
      println(s"DirectCtrlSlaveTest: Reading from 0x${readAddr.toHexString} (ID=${readId})")
      SimAxi4Master.read(dut.io.axi, readAddr, id = readId, cd = cd)

      // Wait for R response from slave
      assert(
        !dut.clockDomain.waitSamplingWhere(20)(dut.io.axi.r.valid.toBoolean && dut.io.axi.r.payload.id.toInt == readId)
      )
      val readDataVal = dut.io.axi.r.payload.data.toBigInt
      println(s"DirectCtrlSlaveTest: Read Rsp ID=${dut.io.axi.r.payload.id.toInt}, Data=0x${readDataVal
          .toString(16)}, Resp=${dut.io.axi.r.payload.resp.toInt}")
      assert(dut.io.axi.r.payload.resp.toInt == 0, "Read response not OKAY")
      assert(
        readDataVal == writeData,
        s"Read back data mismatch! Expected 0x${writeData.toString(16)}, got 0x${readDataVal.toString(16)}"
      )

      simSuccess()
    }
    println("--- Test: ControlRegisterAxi4Slave_DirectAxi4Drive Passed (hopefully) ---")
  }

  class TopSystemDirectConnect extends Component {
    val THE_MASTER_ID_WIDTH = 3
    val GMB_CONFIG = GenericMemoryBusConfig(
      addressWidth = 32 bits,
      dataWidth = 32 bits,
      useId = true,
      idWidth = THE_MASTER_ID_WIDTH bits
    )

    val SHARED_AXI_CONFIG_FOR_BRIDGE_OUTPUT = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = THE_MASTER_ID_WIDTH,
      // Explicitly name all boolean flags from Axi4Config that you want to set
      useId = true,
      useQos = false,
      useLen = true, // Bridge/GMB implies len=0, AXI bus can support it
      useLast = true,
      useResp = true,
      useProt = false, // Keep simple
      useStrb = true
      // arUserWidth, awUserWidth etc. can be left to default if not used
    )

    val FULL_AXI_CONFIG_FOR_SLAVE = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = THE_MASTER_ID_WIDTH, // Slave will see this ID width directly in this test
      useId = true,
      useQos = false,
      useLen = true,
      useLast = true,
      useResp = true,
      useProt = false,
      useStrb = true
    )

    val io = new Bundle {
      val master1ExternalCmd = slave Stream (GenericMemoryBusCmd(GMB_CONFIG))
      val master1ExternalRsp = master Stream (GenericMemoryBusRsp(GMB_CONFIG))
      val ctrlSlaveCtrlReg0 = out UInt (FULL_AXI_CONFIG_FOR_SLAVE.dataWidth bits)
    }

    val bridge1 = new GenericBusToAxi4SharedBridge(GMB_CONFIG, SHARED_AXI_CONFIG_FOR_BRIDGE_OUTPUT)
    bridge1.io.gmbIn.cmd << io.master1ExternalCmd
    io.master1ExternalRsp << bridge1.io.gmbIn.rsp
    val axiSharedMaster = bridge1.io.axiOut // This is master(Axi4Shared)

    val ctrlSlave = new ControlRegisterAxi4Slave(FULL_AXI_CONFIG_FOR_SLAVE, 0) // Slave expects Axi4
    io.ctrlSlaveCtrlReg0 := ctrlSlave.io.ctrlReg0Val

    // --- Convert Axi4Shared master to Axi4 (full) master ---
    // val axiFullMaster = Axi4SharedToFull(axiSharedMaster) // This is a common pattern
    // Or, if Axi4Shared has a .toAxi4() method:
    // val axiFullMaster = axiSharedMaster.toAxi4()
    // Let's check SpinalHDL lib for the standard way.
    // Axi4Shared can be directly connected to Axi4 if the config allows.
    // No, Axi4Shared and Axi4 are distinct. We need a bridge/converter.
    // `Axi4SharedToFullFactory` or `Axi4MasterDemux` (if we consider Axi4Shared as a "muxed" master)
    // The most straightforward is often an explicit bridge component if one exists, or manual connection.

    // Manual conversion / or using a library converter if available
    // Let's try to use toAxi4() if Axi4Shared has it, or create a simple converter area
    // SpinalHDL's Axi4Shared usually connects to an Axi4Shared slave.
    // To connect Axi4Shared (master) to Axi4 (slave), we need to demux sharedCmd.

    val axiFullMaster = Axi4(FULL_AXI_CONFIG_FOR_SLAVE)

    // Drive axiFullMaster (outputs) from axiSharedMaster (outputs)
    // Shared Command to AR/AW
    axiFullMaster.ar.valid := axiSharedMaster.sharedCmd.valid && !axiSharedMaster.sharedCmd.payload.write
    axiFullMaster.aw.valid := axiSharedMaster.sharedCmd.valid && axiSharedMaster.sharedCmd.payload.write
    axiFullMaster.ar.payload.assignSomeByName(axiSharedMaster.sharedCmd.payload)
    axiFullMaster.aw.payload.assignSomeByName(axiSharedMaster.sharedCmd.payload)
    // W Channel
    axiFullMaster.w.valid := axiSharedMaster.writeData.valid
    axiFullMaster.w.payload := axiSharedMaster.writeData.payload

    // Drive axiSharedMaster's ready inputs from axiFullMaster's ready inputs
    axiSharedMaster.sharedCmd.ready := Mux(
      axiSharedMaster.sharedCmd.payload.write,
      axiFullMaster.aw.ready,
      axiFullMaster.ar.ready
    )
    axiSharedMaster.writeData.ready := axiFullMaster.w.ready

    // Drive axiSharedMaster's response inputs from axiFullMaster's response inputs
    // R Channel
    axiSharedMaster.readRsp.valid := axiFullMaster.r.valid
    axiSharedMaster.readRsp.payload := axiFullMaster.r.payload
    // B Channel
    axiSharedMaster.writeRsp.valid := axiFullMaster.b.valid
    axiSharedMaster.writeRsp.payload := axiFullMaster.b.payload

    // Drive axiFullMaster's response ready outputs from axiSharedMaster's response ready outputs
    axiFullMaster.r.ready := axiSharedMaster.readRsp.ready
    axiFullMaster.b.ready := axiSharedMaster.writeRsp.ready

    // Final connection to the slave
    ctrlSlave.io.axi <> axiFullMaster
  }

  test("TopSystem_DirectConnect_CtrlSlave_Write_CorrectedHold") {
    SimConfig.withWave.compile(new TopSystemDirectConnect()).doSimUntilVoid { dut =>
      implicit val cd = dut.clockDomain
      dut.clockDomain.forkStimulus(10)
      val m1Cmd = dut.io.master1ExternalCmd
      val m1Rsp = dut.io.master1ExternalRsp
      m1Rsp.ready #= true

      m1Cmd.valid #= false
      m1Cmd.payload.assignDontCare()
      if (dut.GMB_CONFIG.useId && m1Cmd.payload.id != null) m1Cmd.payload.id #= 0
      dut.clockDomain.waitSampling(5)

      println("DirectConnectTest M1: Writing 0xAABBCCDD to 0x00")
      m1Cmd.payload.address #= 0x00
      m1Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_WRITE
      m1Cmd.payload.writeData #= BigInt("AABBCCDD", 16)
      m1Cmd.payload.writeByteEnables #= 0xf
      if (dut.GMB_CONFIG.useId) m1Cmd.payload.id #= 5
      dut.clockDomain.waitSampling()

      m1Cmd.valid #= true

      // Wait for the bridge to accept the GMB command.
      // This implies the bridge's AXI output (AW/W) was accepted by ctrlSlave.
      assert(!dut.clockDomain.waitSamplingWhere(20)(m1Cmd.ready.toBoolean))
      println(s"DirectConnectTest M1 @${simTime()}: GMB CMD accepted by bridge (m1Cmd.fire).")

      // IMPORTANT: Keep GMB valid for one more cycle AFTER it's accepted by the bridge.
      // This ensures the bridge keeps its AXI valid signals high for an extra cycle,
      // allowing Axi4SlaveFactory to complete its internal write.
      println(s"DirectConnectTest M1 @${simTime()}: Holding GMB CMD valid for one extra cycle. Now de-asserting.")
      // Now wait for the GMB response (which comes from AXI B response)
      assert(!dut.clockDomain.waitSamplingWhere(20)(m1Rsp.valid.toBoolean))
      m1Cmd.valid #= false
      println(s"DirectConnectTest M1: Write Rsp to Ctrl Error=${m1Rsp.payload.error.toBoolean}")
      assert(!m1Rsp.payload.error.toBoolean)
      if (dut.GMB_CONFIG.useId) assert(m1Rsp.payload.id.toBigInt == 5, "GMB Rsp ID mismatch")

      dut.clockDomain.waitSampling(5)

      val regVal = dut.io.ctrlSlaveCtrlReg0.toBigInt
      println(s"DirectConnectTest M1: CtrlReg0 value after write: 0x${regVal.toString(16)}")
      assert(
        regVal == BigInt("AABBCCDD", 16),
        s"CtrlReg0 data mismatch! Expected 0xAABBCCDD, got 0x${regVal.toString(16)}"
      )

      // --- Read Operation --- (Read path is usually less sensitive to this "hold valid" issue)
      println("DirectConnectTest M1: Reading from 0x00 (CtrlReg0)")
      m1Cmd.payload.address #= 0x00
      m1Cmd.payload.opcode #= GenericMemoryBusOpcode.OP_READ
      if (dut.GMB_CONFIG.useId) m1Cmd.payload.id #= 6
      dut.clockDomain.waitSampling()
      m1Cmd.valid #= true

      assert(!dut.clockDomain.waitSamplingWhere(20)(m1Cmd.ready.toBoolean))
      println(s"DirectConnectTest M1 @${simTime()}: GMB Read CMD accepted by bridge.")
      // For reads, de-asserting valid immediately after ready is usually fine.
      // But to be safe and symmetrical with the write discovery:
      println(s"DirectConnectTest M1 @${simTime()}: Holding GMB Read CMD valid for one extra cycle. Now de-asserting.")

      assert(!dut.clockDomain.waitSamplingWhere(20)(m1Rsp.valid.toBoolean))
      m1Cmd.valid #= false
      val readData = m1Rsp.payload.readData.toBigInt
      println(
        s"DirectConnectTest M1: Read Rsp from Ctrl Data=0x${readData.toString(16)}, Error=${m1Rsp.payload.error.toBoolean}"
      )
      assert(!m1Rsp.payload.error.toBoolean)
      assert(readData == BigInt("AABBCCDD", 16), "Read back data mismatch")
      if (dut.GMB_CONFIG.useId) assert(m1Rsp.payload.id.toBigInt == 6, "GMB Read Rsp ID mismatch")

      simSuccess()
    }
    println("--- Test: TopSystem_DirectConnect_CtrlSlave_Write_CorrectedHold Passed (hopefully) ---")
  }
}

class Axi4SlaveFactorySpec extends SpinalSimFunSuite {

  onlyVerilator()


  // --- Minimal DUT for testing Axi4SlaveFactory ---
  class FactoryMinimalTestDUT(axiConfig: Axi4Config) extends Component {
    val io = new Bundle {
      val axi = slave(Axi4(axiConfig))
      val testRegOut = out(Bits(axiConfig.dataWidth bits))
    }

    val factory = Axi4SlaveFactory(io.axi)
    val testReg = factory.createReadAndWrite(Bits(axiConfig.dataWidth bits), address = 0, bitOffset = 0) init (0)
    io.testRegOut := testReg

    // Optional: Add simPublic to observe testReg directly if needed for debug
    testReg.simPublic()

  }

  test("Axi4SlaveFactory_MinimalWriteAndRead") {
    // Define an AXI4Config that is fully specified for single beat transfers
    val AXI_CONF = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 3, // Example ID width
      useId = true,
      useRegion = false, // Keep simple
      useBurst = true, // Required by unburstify logic, will send INCR
      useLock = false,
      useCache = false,
      useSize = true, // Required by unburstify logic, will send full width size
      useQos = false,
      useLen = true, // Required by unburstify logic, will send len=0
      useLast = true, // WLAST and RLAST will be true for single beat
      useResp = true,
      useProt = false,
      useStrb = true
    )

    SimConfig.withWave.compile(new FactoryMinimalTestDUT(AXI_CONF)).doSimUntilVoid { dut =>
      implicit val cd = dut.clockDomain
      dut.clockDomain.forkStimulus(10)
      val axi = dut.io.axi

      axi.r.ready #= true
      axi.b.ready #= true // Master is ALWAYS ready for B response

      axi.aw.valid #= false
      axi.w.valid #= false
      axi.ar.valid #= false
      if (AXI_CONF.useId) {
        if (axi.aw.payload.id != null) axi.aw.payload.id #= 0
        if (axi.ar.payload.id != null) axi.ar.payload.id #= 0
      }

      dut.clockDomain.waitSampling(5)

      // --- Perform a single AXI Write ---
      val writeAddr = 0
      val writeData = BigInt("CAFEF00D", 16)
      val writeId = 1

      println(s"Sim: Driving AW & W: Addr=0x${writeAddr.toHexString}, ID=$writeId, Data=0x${writeData.toString(16)}")
      // Set payload
      axi.aw.payload.addr #= writeAddr
      if (AXI_CONF.useId && axi.aw.payload.id != null) axi.aw.payload.id #= writeId
      if (AXI_CONF.useLen && axi.aw.payload.len != null) axi.aw.payload.len #= 0
      if (AXI_CONF.useBurst && axi.aw.payload.burst != null) axi.aw.payload.burst #= 1
      if (AXI_CONF.useSize && axi.aw.payload.size != null) axi.aw.payload.size #= log2Up(AXI_CONF.dataWidth / 8)

      axi.w.payload.data #= writeData
      if (AXI_CONF.useStrb && axi.w.payload.strb != null) axi.w.payload.strb #= 0xf
      if (AXI_CONF.useLast && axi.w.payload.last != null) axi.w.payload.last #= true
      dut.clockDomain.waitSampling() // Ensure payload is set

      // Assert valids
      axi.aw.valid #= true
      axi.w.valid #= true

      // Wait for AW and W to be accepted by the DUT.
      assert(!dut.clockDomain.waitSamplingWhere(20)(axi.aw.ready.toBoolean && axi.w.ready.toBoolean))
      println(s"Sim @${simTime() - 10}: AW and W handshake (aw.fire and w.fire) occurred with DUT.")
      // According to your finding, KEEP aw.valid and w.valid HIGH.

      // Wait for B response fire (b.valid && b.ready)
      println(s"Sim @${simTime() - 10}: Waiting for BFIRE (BVALID and BREADY). aw.valid and w.valid are kept HIGH.")
      assert(!dut.clockDomain.waitSamplingWhere(20)(axi.b.ready.toBoolean && axi.b.valid.toBoolean))
      println(s"Sim @${simTime() - 10}: BFIRE occurred!")
      dut.clockDomain.waitSampling()

      // NOW, after BFIRE, de-assert aw.valid and w.valid
      axi.aw.valid #= false
      axi.w.valid #= false

      println(s"Sim: B-Channel: ID=${if (AXI_CONF.useId && axi.b.payload.id != null) axi.b.payload.id.toInt
        else -1}, RESP=${if (AXI_CONF.useResp && axi.b.payload.resp != null) axi.b.payload.resp.toInt else -1}")
      if (AXI_CONF.useResp) assert(axi.b.payload.resp.toInt == 0, "B RESP not OKAY")
      if (AXI_CONF.useId && axi.b.payload.id != null) assert(axi.b.payload.id.toInt == writeId, "B ID mismatch")

      dut.clockDomain.waitSampling(5)

      val regValAfterWrite = dut.io.testRegOut.toBigInt
      println(s"Sim: testRegOut after write = 0x${regValAfterWrite.toString(16)}")
      assert(
        regValAfterWrite == writeData,
        s"Write check: testRegOut data mismatch! Expected 0x${writeData.toString(16)}, got 0x${regValAfterWrite.toString(16)}"
      )

      // --- Perform a single AXI Read ---
      // (Read path should be less problematic with valid de-assertion timing)
      val readId = 2
      println(s"Sim: Driving AR: Addr=0x${writeAddr.toHexString}, ID=$readId")
      axi.ar.payload.addr #= writeAddr
      if (AXI_CONF.useId && axi.ar.payload.id != null) axi.ar.payload.id #= readId
      if (AXI_CONF.useLen && axi.ar.payload.len != null) axi.ar.payload.len #= 0
      if (AXI_CONF.useBurst && axi.ar.payload.burst != null) axi.ar.payload.burst #= 1
      if (AXI_CONF.useSize && axi.ar.payload.size != null) axi.ar.payload.size #= log2Up(AXI_CONF.dataWidth / 8)

      dut.clockDomain.waitSampling()
      axi.ar.valid #= true

      assert(!dut.clockDomain.waitSamplingWhere(20)(axi.ar.ready.toBoolean))
      println(s"Sim @${simTime() - 10}: AR accepted by DUT.")
      dut.clockDomain.waitSampling()
      axi.ar.valid #= false // De-assert ARVALID after ARFIRE

      assert(!dut.clockDomain.waitSamplingWhere(20)(axi.r.ready.toBoolean && axi.r.valid.toBoolean))
      val readDataVal = axi.r.payload.data.toBigInt
      println(
        s"Sim: R-Channel: ID=${if (AXI_CONF.useId && axi.r.payload.id != null) axi.r.payload.id.toInt else -1}, Data=0x${readDataVal
            .toString(16)}, RESP=${if (AXI_CONF.useResp && axi.r.payload.resp != null) axi.r.payload.resp.toInt else -1}"
      )
      if (AXI_CONF.useResp) assert(axi.r.payload.resp.toInt == 0, "R RESP not OKAY")
      if (AXI_CONF.useId && axi.r.payload.id != null) assert(axi.r.payload.id.toInt == readId, "R ID mismatch")
      assert(
        readDataVal == writeData,
        s"Read back check: testRegOut data mismatch! Expected 0x${writeData.toString(16)}, got 0x${readDataVal.toString(16)}"
      )

      dut.clockDomain.waitSampling(10)
      simSuccess()
    }
    println("--- Test: Axi4SlaveFactory_MinimalWriteAndRead_HoldValidUntilBFire Passed ---")
  }
}
