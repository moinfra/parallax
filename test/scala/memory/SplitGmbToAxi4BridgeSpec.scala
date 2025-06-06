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
import parallax.bus.SplitGmbToAxi4Bridge
import parallax.components.memory.Axi4SlaveRam

class SplitGmbToAxi4BridgeSpec extends SpinalSimFunSuite {
    onlyVerilator()
  
    test("SplitGmbToAxi4Bridge_UnitTest") {
      val GMB_SPLIT_CONFIG = GenericMemoryBusConfig(
          addressWidth = 8 bits, dataWidth = 32 bits, useId = true, idWidth = 3 bits
      )
      val AXI_FULL_CONFIG_FOR_TEST = Axi4Config(
          addressWidth = GMB_SPLIT_CONFIG.addressWidth.value,
          dataWidth = GMB_SPLIT_CONFIG.dataWidth.value,
          idWidth = GMB_SPLIT_CONFIG.idWidth.value,
          useId = GMB_SPLIT_CONFIG.useId,
          useSize = true, useLen = true, useBurst = true, useLast = true,
          useStrb = true, useResp = true, useRegion = false, useLock = false,
          useCache = false, useProt = false, useQos = false
      )
  
      SimConfig.withWave.compile(new Component {
        val io = new Bundle {
          val gmbReadCmdIn = slave(Stream(SplitGmbReadCmd(GMB_SPLIT_CONFIG)))
          val gmbReadRspOut = master(Stream(SplitGmbReadRsp(GMB_SPLIT_CONFIG)))
          val gmbWriteCmdIn = slave(Stream(SplitGmbWriteCmd(GMB_SPLIT_CONFIG)))
          val gmbWriteRspOut = master(Stream(SplitGmbWriteRsp(GMB_SPLIT_CONFIG)))
        }
        val bridge = new SplitGmbToAxi4Bridge(GMB_SPLIT_CONFIG, AXI_FULL_CONFIG_FOR_TEST)
        bridge.io.gmbIn.read.cmd  << io.gmbReadCmdIn
        bridge.io.gmbIn.write.cmd << io.gmbWriteCmdIn
        io.gmbReadRspOut  << bridge.io.gmbIn.read.rsp
        io.gmbWriteRspOut << bridge.io.gmbIn.write.rsp
  
        // Use the Axi4SlaveRam defined in the companion object or at a higher scope
        val slaveRam = new Axi4SlaveRam(
            AXI_FULL_CONFIG_FOR_TEST,
            storageSizeInWords = 256 / (AXI_FULL_CONFIG_FOR_TEST.dataWidth/8) // 256 bytes / bytes_per_word
        )
        slaveRam.io.axi <> bridge.io.axiOut
  
        io.simPublic(); bridge.io.simPublic(); slaveRam.io.axi.simPublic()
      }).doSimUntilVoid { dut =>
        // ... (Your test logic from previous "SplitBridgeTest: Driving GMB Write..." etc.)
        // (Ensure to use BigInt for hex values assigned to data, and correct burst enum value)
        implicit val cd = dut.clockDomain
        dut.clockDomain.forkStimulus(10)
  
        val gmbReadCmd  = dut.io.gmbReadCmdIn
        val gmbReadRsp  = dut.io.gmbReadRspOut
        val gmbWriteCmd = dut.io.gmbWriteCmdIn
        val gmbWriteRsp = dut.io.gmbWriteRspOut
  
        gmbReadRsp.ready #= true
        gmbWriteRsp.ready #= true
        gmbReadCmd.valid #= false; gmbReadCmd.payload.assignDontCare()
        gmbWriteCmd.valid #= false; gmbWriteCmd.payload.assignDontCare()
        if(GMB_SPLIT_CONFIG.useId){
            if(gmbReadCmd.payload.id != null) gmbReadCmd.payload.id #= 0
            if(gmbWriteCmd.payload.id != null) gmbWriteCmd.payload.id #= 0
        }
        dut.clockDomain.waitSampling(5)
  
        val writeAddr = 0x80; val writeData = BigInt("1234ABCD", 16); val writeId = 1
        println(s"SplitBridgeTest: Driving GMB Write: Addr=0x${writeAddr.toHexString}, Data=0x${writeData.toString(16)}, ID=$writeId")
        gmbWriteCmd.payload.address #= writeAddr
        gmbWriteCmd.payload.data #= writeData
        gmbWriteCmd.payload.byteEnables #= 0xF
        if(GMB_SPLIT_CONFIG.useId && gmbWriteCmd.payload.id != null) gmbWriteCmd.payload.id #= writeId
        dut.clockDomain.waitSampling()
        gmbWriteCmd.valid #= true
        waitUntil(gmbWriteCmd.ready.toBoolean)
        println(s"SplitBridgeTest @${simTime()}: GMB Write CMD accepted by bridge (ID=$writeId).")
        dut.clockDomain.waitSampling() // Hold valid for one cycle as per factory behavior
        gmbWriteCmd.valid #= false
        waitUntil(gmbWriteRsp.valid.toBoolean && gmbWriteRsp.ready.toBoolean)
        println(s"SplitBridgeTest @${simTime()}: GMB Write Rsp received: Error=${gmbWriteRsp.payload.error.toBoolean}, ID=${if(GMB_SPLIT_CONFIG.useId && gmbWriteRsp.payload.id != null) gmbWriteRsp.payload.id.toBigInt else -1}")
        assert(!gmbWriteRsp.payload.error.toBoolean)
        if(GMB_SPLIT_CONFIG.useId && gmbWriteRsp.payload.id != null) assert(gmbWriteRsp.payload.id.toBigInt == writeId)
        dut.clockDomain.waitSampling(5)
  
        val readAddr = 0x80; val readId = 2
        println(s"SplitBridgeTest: Driving GMB Read: Addr=0x${readAddr.toHexString}, ID=$readId")
        gmbReadCmd.payload.address #= readAddr
        if(GMB_SPLIT_CONFIG.useId && gmbReadCmd.payload.id != null) gmbReadCmd.payload.id #= readId
        dut.clockDomain.waitSampling()
        gmbReadCmd.valid #= true
        waitUntil(gmbReadCmd.ready.toBoolean)
        println(s"SplitBridgeTest @${simTime()}: GMB Read CMD accepted (ID=$readId).")
        dut.clockDomain.waitSampling() // Hold valid for one cycle
        gmbReadCmd.valid #= false
        waitUntil(gmbReadRsp.valid.toBoolean && gmbReadRsp.ready.toBoolean)
        val rdata = gmbReadRsp.payload.data.toBigInt
        val rid = if(GMB_SPLIT_CONFIG.useId && gmbReadRsp.payload.id != null) gmbReadRsp.payload.id.toBigInt else -1
        println(s"SplitBridgeTest @${simTime()}: GMB Read Rsp received: Data=0x${rdata.toString(16)}, Error=${gmbReadRsp.payload.error.toBoolean}, ID=${rid}")
        assert(!gmbReadRsp.payload.error.toBoolean)
        if(GMB_SPLIT_CONFIG.useId && gmbReadRsp.payload.id != null) assert(rid == readId)
        assert(rdata == writeData)
        dut.clockDomain.waitSampling(10)
        simSuccess()
      }
      println("--- Test: SplitGmbToAxi4Bridge_UnitTest Passed ---")
    }
  }
  