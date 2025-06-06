package test.scala.memory

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
import parallax.bus.Axi4SharedToFullConverter


class Axi4SharedToFullConverterSpec extends SpinalSimFunSuite { // Or as part of Axi4CrossbarDemoSpec

    onlyVerilator()
    // --- Helper: Axi4SlaveRam (as defined before) ---
    class Axi4SlaveRam(config: Axi4Config, storageSizeInWords: Int) extends Component {
      // ... (your working Axi4SlaveRam using Axi4SlaveFactory and Vec.fill(Reg...))
      // (Ensure it uses Axi4.resp.OKAY for resp assignments)
      require(storageSizeInWords > 0, "RAM size must be positive")
      val io = new Bundle {
        val axi = slave(Axi4(config))
      }
      val factory = Axi4SlaveFactory(io.axi)
      val ramData = Vec.fill(storageSizeInWords)(Reg(Bits(config.dataWidth bits)).init(0))
      for (i <- 0 until storageSizeInWords) {
        val wordAddress = i * (config.dataWidth / 8)
        factory.readAndWrite(ramData(i), address = wordAddress, bitOffset = 0)
      }
      // Logging inside Axi4SlaveRam
      val cycleCountRam = Reg(UInt(32 bits)) init (0); cycleCountRam := cycleCountRam + 1
      val s_aw_valid = Bool().simPublic(); s_aw_valid := io.axi.aw.valid
    }
  
    // --- Test Component for the Converter ---
    class Axi4SharedToFullConverterTestbench(axiConfig: Axi4Config) extends Component {
      val io = new Bundle {
        val sharedMasterCmdIn = slave(Stream(Axi4Arw(axiConfig)))
        val sharedMasterWIn = slave(Stream(Axi4W(axiConfig)))
        val control_converter_axiSharedIn_readRsp_ready = in Bool ()
        val control_converter_axiSharedIn_writeRsp_ready = in Bool ()
      }
  
      val converter = new Axi4SharedToFullConverter(axiConfig)
  
      converter.io.axiSharedIn.sharedCmd << io.sharedMasterCmdIn
      converter.io.axiSharedIn.writeData << io.sharedMasterWIn
      converter.io.axiSharedIn.readRsp.ready := io.control_converter_axiSharedIn_readRsp_ready
      converter.io.axiSharedIn.writeRsp.ready := io.control_converter_axiSharedIn_writeRsp_ready
  
      val slaveRam = new Axi4SlaveRam(axiConfig, storageSizeInWords = 256) // Example size
      slaveRam.io.axi <> converter.io.axiFullOut
  
      // SimPublics for observing converter's shared response output (which is an input to converter.io.axiSharedIn)
      // These are master streams from converter's perspective, so testbench observes their valid/payload
      converter.io.axiSharedIn.readRsp.simPublic() // Make the stream itself public
      converter.io.axiSharedIn.readRsp.payload.simPublic()
      converter.io.axiSharedIn.writeRsp.simPublic()
      converter.io.axiSharedIn.writeRsp.payload.simPublic()
  
      // SimPublics for driving converter's shared command input
      io.sharedMasterCmdIn.simPublic()
      io.sharedMasterCmdIn.payload.simPublic()
      io.sharedMasterWIn.simPublic()
      io.sharedMasterWIn.payload.simPublic()
    }
  
    test("Axi4SharedToFullConverter_WriteReadTest_WithFactoryBehaviorAndCorrections") {
      val testIdWidth = 3
      val COMMON_AXI_CONFIG = Axi4Config(
        addressWidth = 32,
        dataWidth = 32,
        idWidth = testIdWidth,
        useId = true,
        useRegion = false,
        useBurst = true,
        useLock = false,
        useCache = false,
        useSize = true,
        useQos = false,
        useLen = true,
        useLast = true,
        useResp = true,
        useProt = false,
        useStrb = true
      )
  
      SimConfig.withWave.compile(new Axi4SharedToFullConverterTestbench(COMMON_AXI_CONFIG)).doSimUntilVoid { dut =>
        implicit val cd = dut.clockDomain
        dut.clockDomain.forkStimulus(10)
  
        val sharedCmdIn = dut.io.sharedMasterCmdIn
        val sharedWIn = dut.io.sharedMasterWIn
        val converterSharedRspOut_valid = dut.converter.io.axiSharedIn.readRsp.valid
        val converterSharedRspOut_payload = dut.converter.io.axiSharedIn.readRsp.payload
        val converterSharedBOut_valid = dut.converter.io.axiSharedIn.writeRsp.valid
        val converterSharedBOut_payload = dut.converter.io.axiSharedIn.writeRsp.payload
  
        dut.io.control_converter_axiSharedIn_readRsp_ready #= true
        dut.io.control_converter_axiSharedIn_writeRsp_ready #= true
  
        sharedCmdIn.valid #= false
        sharedWIn.valid #= false
        sharedCmdIn.payload.assignDontCare()
        sharedWIn.payload.assignDontCare()
        if (COMMON_AXI_CONFIG.useId && sharedCmdIn.payload.id != null) sharedCmdIn.payload.id #= 0
  
        dut.clockDomain.waitSampling(5)
  
        // --- Write Operation via Shared Interface ---
        val writeAddr = 0x100
        val writeData = BigInt("FEEDC0DE", 16)
        val writeId = 1
  
        println(
          s"ConverterTest: Initiating Shared Write: Addr=0x${writeAddr.toHexString}, Data=0x${writeData.toString(16)}, ID=$writeId"
        )
  
        sharedCmdIn.payload.addr #= writeAddr
        sharedCmdIn.payload.write #= true
        if (COMMON_AXI_CONFIG.useId && sharedCmdIn.payload.id != null) sharedCmdIn.payload.id #= writeId
        if (COMMON_AXI_CONFIG.useLen && sharedCmdIn.payload.len != null) sharedCmdIn.payload.len #= 0
        if (COMMON_AXI_CONFIG.useBurst && sharedCmdIn.payload.burst != null)
          sharedCmdIn.payload.burst #= 1 // Axi4.burst.INCR.toInt
        if (COMMON_AXI_CONFIG.useSize && sharedCmdIn.payload.size != null)
          sharedCmdIn.payload.size #= log2Up(COMMON_AXI_CONFIG.dataWidth / 8)
        dut.clockDomain.waitSampling() // Ensure GMB Cmd payload is set
  
        sharedWIn.payload.data #= writeData
        sharedWIn.payload.strb #= 0xf
        if (COMMON_AXI_CONFIG.useLast && sharedWIn.payload.last != null) sharedWIn.payload.last #= true
        dut.clockDomain.waitSampling() // Ensure GMB WData payload is set
  
        sharedCmdIn.valid #= true
        sharedWIn.valid #= true
  
        assert(!dut.clockDomain.waitSamplingWhere(20)(sharedCmdIn.ready.toBoolean && sharedWIn.ready.toBoolean))
        println(s"ConverterTest @${simTime() - 10}: Shared AW & W accepted by converter.")
        // KEEP sharedCmdIn.valid and sharedWIn.valid HIGH
  
        println(s"ConverterTest @${simTime() - 10}: Waiting for BFIRE from converter.shared.writeRsp.")
        assert(!dut.clockDomain.waitSamplingWhere(20)(converterSharedBOut_valid.toBoolean))
        println(s"ConverterTest @${simTime() - 10}: Converter's shared BVALID is HIGH.")
  
        // BFIRE occurs this cycle because control_..._writeRsp_ready is high and converterSharedBOut_valid is high
  
        dut.clockDomain.waitSampling() // Wait one more cycle BEFORE de-asserting command valids
        println(s"ConverterTest @${simTime() - 10}: One cycle after BFIRE. Now de-asserting shared CMD/W VALIDS.")
        sharedCmdIn.valid #= false
        sharedWIn.valid #= false
  
        val bPayload = converterSharedBOut_payload
        println(s"ConverterTest: Shared B Rsp (captured on fire): ID=${bPayload.id.toInt}, Resp=${bPayload.resp.toInt}")
        assert(bPayload.resp.toInt == 0, "Write B resp not OKAY") // Axi4.resp.OKAY is 0
        if (COMMON_AXI_CONFIG.useId) assert(bPayload.id.toInt == writeId, "Write B ID mismatch")
  
        dut.clockDomain.waitSampling(5)
  
        // --- Read Operation via Shared Interface ---
        val readAddr = 0x100
        val readId = 2
        println(s"ConverterTest: Initiating Shared Read: Addr=0x${readAddr.toHexString}, ID=$readId")
  
        sharedCmdIn.payload.addr #= readAddr
        sharedCmdIn.payload.write #= false
        if (COMMON_AXI_CONFIG.useId && sharedCmdIn.payload.id != null) sharedCmdIn.payload.id #= readId
        if (COMMON_AXI_CONFIG.useLen && sharedCmdIn.payload.len != null) sharedCmdIn.payload.len #= 0
        if (COMMON_AXI_CONFIG.useBurst && sharedCmdIn.payload.burst != null)
          sharedCmdIn.payload.burst #= 1 // Axi4.burst.INCR.toInt
        if (COMMON_AXI_CONFIG.useSize && sharedCmdIn.payload.size != null)
          sharedCmdIn.payload.size #= log2Up(COMMON_AXI_CONFIG.dataWidth / 8)
        dut.clockDomain.waitSampling()
        sharedCmdIn.valid #= true
  
        assert(!dut.clockDomain.waitSamplingWhere(20)(sharedCmdIn.ready.toBoolean))
        println(s"ConverterTest @${simTime() - 10}: Shared AR accepted by converter.")
        // KEEP ARVALID HIGH (based on write path behavior discovery)
  
        println(
          s"ConverterTest @${simTime() - 10}: Waiting for RFIRE from converter.shared.readRsp. Keeping shared AR VALID HIGH."
        )
        assert(!dut.clockDomain.waitSamplingWhere(20)(converterSharedRspOut_valid.toBoolean))
        println(s"ConverterTest @${simTime() - 10}: Converter's shared RVALID is HIGH.")
  
        dut.clockDomain.waitSampling() // One more cycle with ARVALID high
        println(s"ConverterTest @${simTime() - 10}: One cycle after RFIRE. Now de-asserting shared AR VALID.")
        sharedCmdIn.valid #= false
  
        val rPayload = converterSharedRspOut_payload
        val rdata = rPayload.data.toBigInt
        val rid = rPayload.id.toInt
        println(
          s"ConverterTest: Shared R Rsp (captured on fire): ID=${rid}, Data=0x${rdata.toString(16)}, Resp=${rPayload.resp.toInt}"
        )
        assert(rPayload.resp.toInt == 0, "Read R resp not OKAY") // Axi4.resp.OKAY is 0
        if (COMMON_AXI_CONFIG.useId) assert(rid == readId, "Read R ID mismatch")
        assert(
          rdata == writeData,
          s"Read data mismatch! Expected 0x${writeData.toString(16)}, got 0x${rdata.toString(16)}"
        )
  
        dut.clockDomain.waitSampling(10)
        simSuccess()
      }
      println("--- Test: Axi4SharedToFullConverter_WriteReadTest_WithFactoryBehaviorAndCorrections Passed ---")
    }
  }
