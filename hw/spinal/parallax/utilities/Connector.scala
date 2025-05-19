package parallax.utilities

import spinal.core._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer

// Generic representation of a connection to be made
// We can make this more specific if needed, e.g., for Stream-to-Stream
case class StreamConnectionPoint[T <: Data](
    sourceStreamProvider: () => Stream[T], // Function that returns the master stream
    sinkStreamProvider:   () => Stream[T], // Function that returns the slave stream
    connectionName: String = ""            // Optional name for debugging
)

// Service for registering connection points
trait ConnectionPointService extends Service {
  // Plugin calls this to register a connection it wants to make
  def addStreamConnection[T <: Data](
      sourceStreamProvider: () => Stream[T],
      sinkStreamProvider:   () => Stream[T],
      name: String = ""
  ): Unit

  def addStreamConnection[T <: Data](point: StreamConnectionPoint[T]): Unit = {
    addStreamConnection(point.sourceStreamProvider, point.sinkStreamProvider, point.connectionName)
  }

  // Method for the connector (parent/dedicated plugin) to get all registered points
  def getConnectionPoints(): Seq[StreamConnectionPoint[_ <: Data]]
}

// Implementation of the service, typically as a Plugin itself or managed by Framework
class ConnectionPointPlugin extends Plugin with ConnectionPointService {
  private val connectionPoints = ArrayBuffer[StreamConnectionPoint[_ <: Data]]()

  override def addStreamConnection[T <: Data](
      sourceStreamProvider: () => Stream[T],
      sinkStreamProvider:   () => Stream[T],
      name: String = ""
  ): Unit = {
    connectionPoints += StreamConnectionPoint(sourceStreamProvider, sinkStreamProvider, name)
    ParallaxLogger.log(s"[ConnectionPointPlugin] Connection point registered: $name (Source: ${sourceStreamProvider().getName()}, Sink: ${sinkStreamProvider().getName()})")
  }

  override def getConnectionPoints(): Seq[StreamConnectionPoint[_ <: Data]] = {
    connectionPoints.toSeq
  }
}
