package booflow

import java.nio.ByteBuffer

/**
 * Handler for flow messages
 */
trait FlowMessageHandler {
  /**
   * Called when a connection has been established between two transports
   */
  def connected(): Unit

  /**
   * Called when a connection has terminated between two transports
   */
  def disconnected(): Unit

  /**
   * Called when data is received to a flow.
   *
   * @param id Flow identifier
   * @param data Message data
   */
  def dataReceived(id: Int, data: ByteBuffer): Unit
}

/**
 * Trait for a flow transport
 */
trait FlowTransport {
  /**
   * Initiate connection to the other transport
   * @param handler Handler for flow messages
   */
  def connect(handler: FlowMessageHandler): Unit

  /**
   * Send flow data to the other transport
   * @param id Flow identifier
   * @param data Message data
   */
  def sendData(id: Int, data: ByteBuffer): Unit
}
