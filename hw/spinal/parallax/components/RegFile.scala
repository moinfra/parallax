package parallax.components

import spinal.core._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer
import spinal.sim._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import spinal.tester.SpinalSimFunSuite

case class RegFileGenerics[T <: Data](
    dataType: HardType[T],
    depth: Int
) {
    val addressWidth = log2Up(depth) bits
    def portAddressType = UInt(addressWidth)
}

case class RegFileReadPort[T <: Data](generics: RegFileGenerics[T]) extends Bundle with IMasterSlave {
    val address = generics.portAddressType
    val data    = generics.dataType()

    override def asMaster(): Unit = {
        out(address) // Master 驱动地址
        in(data)     // Master 接收数据
    }
}

case class RegFileWritePort[T <: Data](generics: RegFileGenerics[T]) extends Bundle with IMasterSlave {
    val address = generics.portAddressType
    val data    = generics.dataType()
    val enable  = Bool()

    override def asMaster(): Unit = {
        out(address, data, enable) // Master 驱动所有写信号
    }
}

// --- PhysicalRegFile (修正Bypass逻辑和接口声明) ---
class PhysicalRegFile[T <: Data](val generics: RegFileGenerics[T]) extends Component {
    val io = new Bundle {} // PhysicalRegFile 本身通常没有固定的IO，端口是动态创建和返回的

    private val mem = Mem(generics.dataType, generics.depth)

    private val _readPortsCreated = ArrayBuffer[RegFileReadPort[T]]()
    private val _writePortsCreated = ArrayBuffer[RegFileWritePort[T]]() // 存储的是 slave 视角的端口

    def readPortsCreated: Seq[RegFileReadPort[T]] = _readPortsCreated
    def writePortsCreated: Seq[RegFileWritePort[T]] = _writePortsCreated

    def createReadPort(
        allowBypass: Boolean = false,
        readUnderWritePolicy: ReadUnderWritePolicy = readFirst
    ): RegFileReadPort[T] = {
        // PhysicalRegFile 实现此端口的 SLAVE 侧
        val port = slave(RegFileReadPort(generics)) // <<<< 关键修正

        val rawMemData: T = mem.readAsync(
            address = port.address, // port.address 对 PhysicalRegFile 是 IN
            readUnderWrite = readUnderWritePolicy
        )

        if (allowBypass && _writePortsCreated.nonEmpty) {
            val finalBypassData: T = _writePortsCreated.reverse.foldRight(rawMemData) {
                (wp, elseValue) => // wp 是 _writePortsCreated 中的一个 RegFileWritePort (slave视角)
                    // wp.address, wp.data, wp.enable 对 PhysicalRegFile 都是 IN
                    val condition = wp.enable && wp.address === port.address
                    Mux(condition, wp.data, elseValue)
            }
            port.data := finalBypassData // port.data 对 PhysicalRegFile 是 OUT
        } else {
            port.data := rawMemData
        }

        _readPortsCreated += port // 存储 slave 接口的引用
        port // 返回 slave 接口
    }

    def createWritePort(): RegFileWritePort[T] = {
        // PhysicalRegFile 实现此端口的 SLAVE 侧
        val port = slave(RegFileWritePort(generics)) // <<<< 关键修正

        mem.write(
            address = port.address, // 对 PhysicalRegFile 是 IN
            data    = port.data,    // 对 PhysicalRegFile 是 IN
            enable  = port.enable,  // 对 PhysicalRegFile 是 IN
        )
        _writePortsCreated += port // 存储 slave 接口的引用
        port // 返回 slave 接口
    }
}

// --- RegFileExample (修正接口连接) ---
class RegFileExample extends Component {
    val generics = RegFileGenerics(dataType = Bits(32 bits), depth = 16)
    val regFile = new PhysicalRegFile(generics)

    val io = new Bundle {
        val readPortNormal_address = in UInt(generics.addressWidth)
        val readPortNormal_data = out Bits(32 bits)
        val readPortBypass_address = in UInt(generics.addressWidth)
        val readPortBypass_data = out Bits(32 bits)
        val writePort1_address = in UInt(generics.addressWidth)
        val writePort1_data = in Bits(32 bits)
        val writePort1_enable = in Bool()
        val writePort2_address = in UInt(generics.addressWidth)
        val writePort2_data = in Bits(32 bits)
        val writePort2_enable = in Bool()
    }

    // RegFileExample 作为 MASTER 连接到 PhysicalRegFile 返回的 SLAVE 接口
    val rpNormal = master(regFile.createReadPort()) // <<<< 关键修正
    rpNormal.address := io.readPortNormal_address   // rpNormal.address 对 RegFileExample 是 OUT
    io.readPortNormal_data := rpNormal.data.asBits  // rpNormal.data 对 RegFileExample 是 IN

    val rpBypass = master(regFile.createReadPort(allowBypass = true)) // <<<< 关键修正
    rpBypass.address := io.readPortBypass_address
    io.readPortBypass_data := rpBypass.data.asBits

    val wp1 = master(regFile.createWritePort()) // <<<< 关键修正
    wp1.address := io.writePort1_address        // wp1信号对 RegFileExample 是 OUT
    wp1.data    := io.writePort1_data
    wp1.enable  := io.writePort1_enable

    val wp2 = master(regFile.createWritePort()) // <<<< 关键修正
    wp2.address := io.writePort2_address
    wp2.data    := io.writePort2_data
    wp2.enable  := io.writePort2_enable

    // SpinalInfo 保持不变
    SpinalInfo(s"RegFileExample: Created ${regFile.readPortsCreated.size} read ports.")
    SpinalInfo(s"RegFileExample: Created ${regFile.writePortsCreated.size} write ports.")
}
