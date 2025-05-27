package parallax.test.scala

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import parallax.common._
import scala.collection.mutable


object SimTestHelpers {
    def SimpleStreamDrive[T_DUT_PAYLOAD <: Data, T_QUEUE_ITEM](
        stream: Stream[T_DUT_PAYLOAD], 
        clockDomain: ClockDomain, 
        queue: mutable.Queue[T_QUEUE_ITEM]
    )(payloadAssignment: (T_DUT_PAYLOAD, T_QUEUE_ITEM) => Unit): Unit = {
      fork {
        stream.valid #= false
        var activeData: Option[T_QUEUE_ITEM] = None 
  
        while (true) {
          if (activeData.isEmpty) { 
            if (queue.nonEmpty) {
              activeData = Some(queue.head) 
            }
          }
  
          if (activeData.isDefined) {
            payloadAssignment(stream.payload, activeData.get)
            stream.valid #= true
            
            clockDomain.waitSamplingWhere(stream.ready.toBoolean && stream.valid.toBoolean) 
            
            if (queue.nonEmpty && activeData.isDefined && queue.headOption == activeData) { // Check headOption for safety
              queue.dequeue()
            }
            activeData = None 
            stream.valid #= false 
          } else {
            stream.valid #= false 
            clockDomain.waitSampling() 
          }
        }
      }
    }
  }
