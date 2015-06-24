package booflow.example

import java.nio.ByteBuffer

import booflow.{FlowMessageHandler, FlowTransport}
import boopickle.{Encoder, PickleState, UnpickleState}
import org.reactivestreams.{Subscriber, Publisher}

class WSTransport extends FlowTransport {
  var msgHandler: FlowMessageHandler = _

  override def connect(handler: FlowMessageHandler): Unit = {
    msgHandler = handler
  }

  override def sendData(id: Int, data: ByteBuffer): Unit = {
    val state = new PickleState(new Encoder)
    state.pickle(id)
    state.pickle(data)
    output.onNext(state.toByteBuffer)
  }

  var output: Subscriber[_ >: ByteBuffer] = null

  def asPublisher: Publisher[ByteBuffer] = {
    msgHandler.connected()
    new Publisher[ByteBuffer] {
      override def subscribe(s: Subscriber[_ >: ByteBuffer]): Unit = {
        output = s
      }
    }
  }

  def receiveData(data: ByteBuffer): ByteBuffer = {
    val state = UnpickleState(data)
    val id = state.unpickle[Int]
    val msg = state.unpickle[ByteBuffer]
    msgHandler.dataReceived(id, msg)
    data
  }
}
