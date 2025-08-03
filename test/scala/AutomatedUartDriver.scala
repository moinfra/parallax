// filename: test/scala/AutomatedUartDriver.scala
package test.scala

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import java.util.concurrent.ConcurrentLinkedQueue

class AutomatedUartDriver(txd: Bool, rxd: Bool, cd: ClockDomain, interactive: Boolean = false) {
  val enableLog = false // 是否开启日志记录
  // --- (公共 API 和发送逻辑保持不变) ---
  private val commandQueue = new ConcurrentLinkedQueue[String]()
  private val txByteQueue = new ConcurrentLinkedQueue[Byte]()
  private val rxByteQueue = new ConcurrentLinkedQueue[Byte]()

  rxd #= true

  def sendString(str: String): Unit = {
    if (interactive) {
      println("[DRIVER-WARN] sendString() has no effect in interactive mode. Type in the console instead.")
      return
    }
    commandQueue.add(str)
  }

  def getReceivedByte(): Option[Byte] = {
    if (rxByteQueue.isEmpty) None else Some(rxByteQueue.poll())
  }
  
  def getReceivedString(clearBuffer: Boolean = true): String = {
    val sb = new StringBuilder
    while(!rxByteQueue.isEmpty) {
        sb.append(rxByteQueue.poll().toChar)
    }
    sb.toString()
  }

  def expectString(expected: String, timeoutCycles: Int = 100000, caseSensitive: Boolean = true): Unit = {
    val receivedBuffer = new StringBuilder
    var timeout = timeoutCycles
    var found = false

    println(s"""[TEST] Expecting to receive: "$expected"...""")

    while (timeout > 0 && !found) {
      getReceivedByte().foreach { byte =>
        val char = byte.toChar
        receivedBuffer.append(char)
        if (interactive) {
          println(s"Got character: '$char'")
        }
      }

      val received = if (caseSensitive) receivedBuffer.toString else receivedBuffer.toString.toLowerCase
      val expectedCmp = if (caseSensitive) expected else expected.toLowerCase

      if (received.contains(expectedCmp)) {
        found = true
        println(s"""\n[DRIVER] Successfully matched expected string: "$expected"""")
        println(s"""[DRIVER] Full buffer content: "${receivedBuffer.toString().replace("\n", "\\n").replace("\r", "\\r")}"""")
      } else {
        cd.waitSampling(1)
        timeout -= 1
      }
    }
    if (!found) {
      simFailure(s"""Timeout: Did not receive expected string "$expected" within $timeoutCycles cycles. """ +
        s"""Buffer content: "${receivedBuffer.toString().replace("\n", "\\n").replace("\r", "\\r")}"""")
    }
  }


  // --- Background Fork Process ---
  def start(): Unit = {
    // --- TX FORK (Receiving from DUT) ---
    fork {
      var lastTxd = true
      var bitCounter = 0
      var currentRxByte = 0
      var receiving = false

      while (true) {
        cd.waitSampling()
        val currentTxd = txd.toBoolean

        // --- 添加日志：报告 txd 线的每一次变化 ---
        if (lastTxd != currentTxd) {
            if(enableLog) println(f"[DRIVER-RX-TRACE @${simTime()}%8d] TXD line changed: ${if(lastTxd) 1 else 0} -> ${if(currentTxd) 1 else 0}")
        }

        if (!receiving && lastTxd && !currentTxd) { // Detect start bit (falling edge)
          if(enableLog) println(s"[DRIVER-RX] Start bit detected!")
          receiving = true
          bitCounter = 0
          currentRxByte = 0
        } else if (receiving) {
          if (bitCounter < 8) { // Data bits
            if (currentTxd) {
              currentRxByte |= (1 << bitCounter)
            }
            // --- 添加日志：报告每个采样的数据位 ---
            if(enableLog) println(s"[DRIVER-RX] Sampling data bit ${bitCounter}: ${if(currentTxd) 1 else 0}. currentRxByte = 0x${currentRxByte.toHexString}")
            bitCounter += 1
          } else { // Stop bit
            if(enableLog) println(s"[DRIVER-RX] Expecting stop bit...")
            if (currentTxd) { // Valid stop bit
                println(f"[DRIVER-RX] Stop bit OK. Byte received: 0x${currentRxByte.toHexString} ('${currentRxByte.toChar}')")
                if(interactive) {
                    print(currentRxByte.toChar)
                } else {
                    rxByteQueue.add(currentRxByte.toByte)
                }
            } else {
              println(s"[DRIVER-RX-ERROR] Framing error: Expected stop bit (high) but got low. Discarding byte 0x${currentRxByte.toHexString}.")
            }
            receiving = false
          }
        }
        lastTxd = currentTxd
      }
    }

    // --- RX FORK (Sending to DUT) ---
    // (这部分逻辑保持不变，因为它工作正常)
    fork {
      if (interactive) {
        println("\n" + "="*50)
        println("  Starting INTERACTIVE UART Session (Bit-Synchronous)")
        println("  Type characters in this console. They will be sent to the DUT.")
        println("="*50 + "\n")
        val readerThread = new Thread(() => {
          try { while (true) txByteQueue.add(System.in.read().toByte) }
          catch { case e: Exception => }
        })
        readerThread.setDaemon(true)
        readerThread.start()
      }

      while (true) {
        if (!interactive && txByteQueue.isEmpty && !commandQueue.isEmpty) {
          commandQueue.poll().foreach(c => txByteQueue.add(c.toByte))
        }

        if (!txByteQueue.isEmpty) {
          val byteToSend = txByteQueue.poll()
          if (byteToSend != null) {
            println(f"[DRIVER-TX @${simTime()}%8d] Sending byte: 0x${byteToSend.toHexString} ('${byteToSend.toChar}')")
            rxd #= false // Start bit
            cd.waitSampling()

            (0 until 8).foreach { i => 
              rxd #= ((byteToSend >> i) & 1) != 0
              cd.waitSampling()
            }

            rxd #= true // Stop bit
            cd.waitSampling()
          }
        } else {
          rxd #= true // Idle
          cd.waitSampling()
        }
      }
    }
  }
}
