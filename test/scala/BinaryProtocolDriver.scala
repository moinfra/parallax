// filename: test/scala/BinaryProtocolDriver.scala
package test.scala

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import java.nio.{ByteBuffer, ByteOrder}
import java.util.concurrent.ConcurrentLinkedQueue

class BinaryProtocolDriver(txd: Bool, rxd: Bool, cd: ClockDomain) {
  // --- 底层字节队列和配置 ---
  private val txByteQueue = new ConcurrentLinkedQueue[Byte]()
  private val rxByteQueue = new ConcurrentLinkedQueue[Byte]()
  rxd #= true // 初始化RXD线为空闲状态

  // ====================================================================
  // Section 1: 底层 字节/数据 发送与接收 (核心)
  // ====================================================================

  /** (核心发送API) 发送一个或多个原始字节。
    * @param bytes 要发送的字节序列。
    */
  def sendBytes(bytes: Byte*): Unit = {
    bytes.foreach(txByteQueue.add)
  }

  /** (核心接收API) 尝试接收指定数量的字节，带有超时机制。
    * @param count 要接收的字节数。
    * @param timeoutCycles 超时周期数。
    * @return 包含接收到字节的 Array[Byte]，如果超时则抛出异常。
    */
  def receiveBytes(count: Int, timeoutCycles: Int = 100000): Array[Byte] = {
    val buffer = mutable.ArrayBuffer[Byte]()
    var timeout = timeoutCycles
    while (buffer.size < count && timeout > 0) {
      if (!rxByteQueue.isEmpty) {
        buffer += rxByteQueue.poll()
      } else {
        cd.waitSampling()
        timeout -= 1
      }
    }
    if (buffer.size < count) {
      simFailure(
        s"Timeout: Expected to receive $count bytes, but only got ${buffer.size}. " +
          s"Buffer content: ${buffer.map(b => f"0x$b%02x").mkString(" ")}"
      )
    }
    buffer.toArray
  }

  // ====================================================================
  // Section 2: 高层 协议封装 API (提供给测试用例使用)
  // ====================================================================

  /** 'R' - 读寄存器。发送'R'命令并接收所有寄存器的值。
    * @return 一个包含30个寄存器值(r2-r31)的数组。
    */
  def readRegisters(): Array[Int] = {
    println("[DRIVER] Sending 'Read Registers' (R) command...")
    sendBytes('R'.toByte)
    
    val rawBytes = receiveBytes(120)

    val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

    val regs = Array.fill(30)(buffer.getInt())

    println(s"[DRIVER] Received ${regs.length} registers (r0-r31).")
    regs
  }
  def drainInvalidRsp(context: String = "", cycles: Int = 200): Unit = {
    cd.waitSampling(cycles) // 等待一小段时间，确保所有传输都已完成
    val staleBytes = mutable.ArrayBuffer[Byte]()
    while (!rxByteQueue.isEmpty) {
      staleBytes += rxByteQueue.poll()
    }

    if (staleBytes.nonEmpty) {
      val hexString = staleBytes.map(b => f"0x$b%02x").mkString(" ")
      println(
        s"[DRIVER][WARNING] At '$context': Expected RX buffer to be empty, but found ${staleBytes.size} stale bytes: $hexString"
      )
    } else {
      println(s"[DRIVER][ASSERT] At '$context': RX buffer is clean as expected.")
    }
  }

  def assertAndClearRxBuffer(context: String, cycles: Int = 200): Unit = {
    cd.waitSampling(cycles) // 等待一小段时间，确保所有传输都已完成
    val staleBytes = mutable.ArrayBuffer[Byte]()
    while (!rxByteQueue.isEmpty) {
      staleBytes += rxByteQueue.poll()
    }

    if (staleBytes.nonEmpty) {
      val hexString = staleBytes.map(b => f"0x$b%02x").mkString(" ")
      simFailure(
        s"[DRIVER][ASSERTION FAILED] At '$context': Expected RX buffer to be empty, but found ${staleBytes.size} stale bytes: $hexString"
      )
    } else {
      println(s"[DRIVER][ASSERT] At '$context': RX buffer is clean as expected.")
    }
  }

  def printRegisters(regs: Array[Int]): Unit = {
    // LoongArch ABI 寄存器名称 (r0-r31)
    val abiNames = Array(
      "zero", "ra", "tp", "sp", "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7",
      "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "x",
      "fp", "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"
    )
    
    println("+------+----------+------------+")
    println("| Reg  | ABI Name |   Value    |")
    println("+------+----------+------------+")
    
    // *** 修复点：确保regs.length是32 ***
    for (i <- regs.indices) {
      val regNum = i
      val regName = f"$$r$regNum%-2s"
      val abiName = f"${abiNames(i + 2)}%-8s"
      val regValue = f"0x${regs(i)}%08x"
      println(f"| $regName | $abiName | $regValue |")
    }
    
    println("+------+----------+------------+")
  }
  /** 'D' - 读内存。
    * @param addr 起始地址。
    * @param numBytes 要读取的字节数 (必须是4的倍数)。
    * @return 包含内存数据的字节数组。
    */
  def dumpMemory(addr: Long, numBytes: Int): Array[Byte] = {
    println(f"[DRIVER] Sending 'Dump Memory' (D) command: addr=0x$addr%x, size=$numBytes")

    // *** ByteBuffer 登场！***
    // 1. 分配一个 8 字节的缓冲区来存放地址和长度
    val headerBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    // 2. 将地址和长度放入缓冲区
    //    注意：协议需要32位地址和长度，但我们的addr是Long(64位)，需要转换
    headerBuffer.putInt(addr.toInt)
    headerBuffer.putInt(numBytes)

    // 3. 发送命令码和打包好的二进制数据
    sendBytes('D'.toByte)
    sendBytes(headerBuffer.array(): _*) // .array() 获取底层字节数组

    val data = receiveBytes(numBytes)
    println(s"[DRIVER] Received $numBytes bytes from memory.")
    data
  }

  /** 'A' - 写内存。
    * @param addr 起始地址。
    * @param data 要写入的数据。
    */
  def writeMemory(addr: Long, data: Array[Byte]): Unit = {
    val numBytes = data.length
    println(f"[DRIVER] Sending 'Write Memory' (A) command: addr=0x$addr%x, size=$numBytes")

    val headerBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    headerBuffer.putInt(addr.toInt)
    headerBuffer.putInt(numBytes)

    sendBytes('A'.toByte)
    sendBytes(headerBuffer.array(): _*)
    sendBytes(data: _*) // 发送实际数据

    println("[DRIVER] Write memory command sent.")
  }

  /** 'G' - 执行。
    * @param addr 用户代码入口地址。
    * @return 用户程序在执行期间的所有串口输出 (ASCII字符串)。
    */
  def go(addr: Long): String = {
    println(f"[DRIVER] Sending 'Go' (G) command: addr=0x$addr%x")
    sendBytes('G'.toByte)
    val addrBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    addrBuffer.putInt(addr.toInt)
    sendBytes(addrBuffer.array(): _*)

    println("[DRIVER] Waiting for ACK (0x06)...")
    // 1. 等待启动信号 ACK (0x06)，重复尝试直到收到 ACK 或连续失败3次
    var ackAttempts = 0
    var receivedAck = false
    var lastReceivedByte: Byte = 0x00 // 用于记录最后收到的字节
    val maxAckAttempts = 3 // 连续非ACK的最大尝试次数

    while (!receivedAck && ackAttempts < maxAckAttempts) {
      val receivedBytes = receiveBytes(1) // 尝试接收一个字节
      if (receivedBytes.nonEmpty) {
        val byte = receivedBytes.head
        if (byte == 0x06) {
          receivedAck = true
          println("[DRIVER] Received ACK, user program is running...")
        } else {
          ackAttempts += 1
          lastReceivedByte = byte // 记录非ACK的字节
          println(
            f"[DRIVER] WARNING: Expected ACK (0x06) but received 0x$byte%02x. Retrying... (Attempt $ackAttempts/$maxAckAttempts)"
          )
        }
      } else {
        cd.waitSampling()
      }
    }

    if (!receivedAck) {
      simFailure(
        f"[DRIVER] 'Go' command failed: Did not receive ACK (0x06) after $maxAckAttempts continuous attempts. Last received: 0x$lastReceivedByte%02x."
      )
    }

    // 2. 接收所有字节，直到收到结束信号 BEL (0x07)
    val userOutput = new mutable.ArrayBuffer[Byte]()
    var finished = false
    var timeout = 2000000 // 加一个大的超时，防止无限等待
    while (!finished && timeout > 0) {
      // 尝试非阻塞地获取一个字节
      if (!rxByteQueue.isEmpty) {
        val byte = rxByteQueue.poll()
        if (byte == 0x07) {
          finished = true
          println("\n[DRIVER] Received BEL, user program finished.")
        } else {
          userOutput += byte
          // 实时打印用户输出，只在收到非BEL字节时打印
          print(byte.toChar)
        }
      } else {
        cd.waitSampling()
        timeout -= 1
      }
    }

    if (!finished) {
      simFailure(s"Timeout: Did not receive BEL (0x07) after 'Go' command.")
    }

    new String(userOutput.toArray, "UTF-8")
  }

  // ====================================================================
  // Section 3: 辅助与测试 API
  // ====================================================================

  /** (可选) 清空接收缓冲区。
    */
  def clearReceiverBuffer(): Unit = {
    rxByteQueue.clear()
  }

  /** 用于接收并校验ASCII字符串的辅助函数，比如欢迎信息。
    */
  def expectString(expected: String, timeoutCycles: Int = 100000): Unit = {
    // 这个函数的实现可以基本保持不变，但要从我们的rxByteQueue里读
    val buffer = new mutable.ArrayBuffer[Byte]()
    var timeout = timeoutCycles
    var found = false
    println(s"""[TEST] Expecting to receive ASCII string: "$expected"...""")
    while (timeout > 0 && !found) {
      if (!rxByteQueue.isEmpty) {
        buffer += rxByteQueue.poll()
      } else {
        cd.waitSampling()
        timeout -= 1
      }
      if (new String(buffer.toArray, "UTF-8").contains(expected)) {
        found = true
      }
    }
    if (!found) {
      simFailure(
        s"""Timeout: Did not receive expected string "$expected" within $timeoutCycles cycles. """ +
          s"""Buffer content: "${new String(buffer.toArray, "UTF-8").replace("\n", "\\n")}""""
      )
    }
    println(s"""[DRIVER] Successfully matched expected string: "$expected"""")
  }

  /** 启动后台的发送和接收线程。
    */
  /** 启动后台的发送和接收线程 (适配SIMULATION模式)。
    */
  def start(): Unit = {
    // --- TX FORK (从DUT接收数据) ---
    fork {
      // 在SIMULATION模式下，我们不需要精确的波特率定时
      // 我们只需要在每个时钟周期检查txd线的状态
      var lastTxd = true
      var bitCounter = 0
      var currentRxByte = 0
      var receiving = false

      while (true) {
        cd.waitSampling() // 每个周期检查一次
        val currentTxd = txd.toBoolean

        if (!receiving && lastTxd && !currentTxd) { // 检测起始位 (1 -> 0)
          receiving = true
          bitCounter = 0
          currentRxByte = 0
        } else if (receiving) {
          if (bitCounter < 8) { // 接收8个数据位
            if (currentTxd) {
              currentRxByte |= (1 << bitCounter)
            }
            bitCounter += 1
          } else { // 接收停止位
            if (currentTxd) { // 停止位必须是1
              rxByteQueue.add(currentRxByte.toByte)
            } else {
              println(
                s"[DRIVER-RX-ERROR] Framing error: Expected stop bit (1) but got 0. Discarding byte 0x${currentRxByte.toHexString}."
              )
            }
            receiving = false
          }
        }
        lastTxd = currentTxd
      }
    }

    // --- RX FORK (向DUT发送数据) ---
    fork {
      // 持续将rxd驱动为高电平，除非有数据要发送
      rxd #= true

      while (true) {
        // 阻塞地等待队列非空
        // waituntil(txByteQueue.nonEmpty) 是一种思路，但可能与并发队列不兼容
        // 一个更简单的方法是轮询
        if (!txByteQueue.isEmpty) {
          val byteToSend = txByteQueue.poll()
          if (byteToSend != null) {
            // 发送数据时，临时接管 rxd 的驱动权
            rxd #= false // Start bit
            cd.waitSampling(1)

            (0 until 8).foreach { i =>
              rxd #= ((byteToSend >> i) & 1) != 0
              cd.waitSampling(1)
            }

            rxd #= true // Stop bit
            cd.waitSampling(1)

            // 发送完成后，再次将 rxd 驱动为高电平
            rxd #= true
          }
        } else {
          // 如果队列为空，什么也不做，继续循环，rxd 保持高电平
          cd.waitSampling(1)
        }
      }
    }
  }
}
