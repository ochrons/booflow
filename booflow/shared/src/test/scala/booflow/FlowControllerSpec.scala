package booflow

import java.nio.ByteBuffer

import boopickle._
import utest._
import utest.framework.TestSuite

import scala.collection.immutable.Queue

object FlowControllerSpec extends TestSuite {

  import scala.language.reflectiveCalls

  def createStreams: (Transport, FlowStreamMock, FlowStreamMock) = {
    val transport = new Transport
    val streamA = new FlowStreamMock(transport)
    val streamB = new FlowStreamMock(transport)
    transport.setStreamA(streamA)
    transport.setStreamB(streamB)
    (transport, streamA, streamB)
  }

  case class TestMessage(i: Int, s: String)

  /**
   * Provides base mock-ups for accessory classes around FlowControl and initializes the transport between two
   * FlowControl instances.
   */
  class MockBase {
    val (transport, streamA, streamB) = createStreams
    val mockSubscriber = new SubscriberProxy[TestMessage] {
      var isSubscribed = false
      var sProxy: SubscriptionProxy = null
      var lastError: Throwable = null
      var lastMessage: TestMessage = null
      var isComplete = false
      override def onSubscribe(s: SubscriptionProxy): Unit = {
        isSubscribed = true
        sProxy = s
      }
      override def onError(t: Throwable): Unit = lastError = t
      override def onComplete(): Unit = isComplete = true
      override def onNext(msg: TestMessage): Unit = lastMessage = msg
    }

    val mockProducer = new ProducerProxy[TestMessage] {
      var subProxy: SubscriberProxy[TestMessage] = null
      var lastRequest: Long = 0
      var isCancelled = false
      val subscriptionProxy = new SubscriptionProxy {
        override def cancel(): Unit = isCancelled = true
        override def request(n: Long): Unit = lastRequest = n
      }
      override val pickler: Pickler[TestMessage] = implicitly[Pickler[TestMessage]]
      override def subscribe(s: SubscriberProxy[TestMessage]): Unit = {
        subProxy = s
        s.onSubscribe(subscriptionProxy)
      }
    }
    val mockProducerFactory = new ProducerFactory {
      var lastId = ""
      override def createProducer(id: String): Option[ProducerProxy[_ <: AnyRef]] = {
        lastId = id
        Some(mockProducer)
      }
    }
    implicit object ExceptionPickler extends Pickler[java.lang.Exception] {
      override def pickle(obj: Exception)(implicit state: PickleState): Unit = {
        state.pickle(obj.getMessage)
      }
    }
    implicit object ExceptionUnpickler extends Unpickler[java.lang.Exception] {
      override def unpickle(implicit state: UnpickleState): Exception = {
        val msg = state.unpickle[String]
        new java.lang.Exception(msg)
      }
    }
    val exceptionPickler = CompositePickler[Throwable].addConcreteType[java.lang.Exception]
    val fcA = new FlowController(streamA, null, exceptionPickler)
    val fcB = new FlowController(streamB, mockProducerFactory, exceptionPickler)
    // handle ping-pong
    transport.receiveFor(streamB)
    transport.receiveFor(streamA)
    transport.receiveFor(streamB)
    transport.receiveFor(streamA)
  }

  override def tests = TestSuite {
    "Flow subscription" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // was a control message sent?
        assert(transport.a2b.size == 1)
        assert(transport.b2a.isEmpty)
        // println(s"Data size ${transport.a2b.head._2.array().slice(0, transport.a2b.head._2.remaining()).mkString(",")}")
        // forward it to the other controller
        transport.receiveFor(streamB)
        // was the producer factory contacted?
        assert(mockProducerFactory.lastId == "Test")
        // subscription completed?
        assert(mockProducer.subProxy != null)
        // was a message sent?
        assert(transport.b2a.size == 1)
        // forward it
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
      }
    }
    "Flow data" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // forward it to the other controller
        transport.receiveFor(streamB)
        // forward response back
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
        // send a message from Provider
        private val message = TestMessage(42, "Answer")
        mockProducer.subProxy.onNext(message)
        // forward to client
        transport.receiveFor(streamA)
        assert(mockSubscriber.lastMessage == message)
      }
    }
    "Back-pressure" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // forward it to the other controller
        transport.receiveFor(streamB)
        // forward response back
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
        // request more messages
        mockSubscriber.sProxy.request(10)
        // forward to server
        transport.receiveFor(streamB)
        assert(mockProducer.lastRequest == 10)
      }
    }
    "Cancelling" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // forward it to the other controller
        transport.receiveFor(streamB)
        // forward response back
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
        assert(!mockProducer.isCancelled)
        // cancel the flow
        mockSubscriber.sProxy.cancel()
        // forward to server
        transport.receiveFor(streamB)
        assert(mockProducer.isCancelled)
      }
    }
    "Error passing" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // forward it to the other controller
        transport.receiveFor(streamB)
        // forward response back
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
        // send error
        mockProducer.subProxy.onError(new Exception("Test error"))
        // forward to client
        transport.receiveFor(streamA)
        assert(mockSubscriber.lastError.getMessage == "Test error")
        // try sending error again
        mockProducer.subProxy.onError(new Exception("Test error"))
        // should not go anywhere
        assert(transport.b2a.isEmpty)
      }
    }
    "Flow completion" - {
      new MockBase {
        fcA.subscribe[TestMessage]("Test", mockSubscriber)
        // forward it to the other controller
        transport.receiveFor(streamB)
        // forward response back
        transport.receiveFor(streamA)
        // check that subscription message is delivered to original subscriber
        assert(mockSubscriber.isSubscribed)
        assert(!mockSubscriber.isComplete)
        // send complete
        mockProducer.subProxy.onComplete()
        // forward to client
        transport.receiveFor(streamA)
        assert(mockSubscriber.isComplete)
        // try sending complete again
        mockProducer.subProxy.onComplete()
        // should not go anywhere
        assert(transport.b2a.isEmpty)
      }
    }
  }
}

/**
 * Transport mimics the functionality of a real transport, like WebSocket. It allows deterministic control over when messages
 * are passed to the other party.
 */
class Transport {
  var a2b = Queue.empty[(Int, ByteBuffer)]
  var b2a = Queue.empty[(Int, ByteBuffer)]

  var streamA: FlowStreamMock = _
  var streamB: FlowStreamMock = _

  def setStreamA(stream: FlowStreamMock) = streamA = stream

  def setStreamB(stream: FlowStreamMock) = streamB = stream

  def sendFrom(stream: FlowStreamMock, id: Int, data: ByteBuffer): Unit = {
    if (stream eq streamA)
      a2b = a2b.enqueue(id -> data)
    else if (stream eq streamB)
      b2a = b2a.enqueue(id -> data)
    else
      throw new IllegalStateException
  }

  def receiveFor(stream: FlowStreamMock): Unit = {
    if (stream eq streamA) {
      val ((id, data), q) = b2a.dequeue
      b2a = q
      stream.receiveData(id, data)
    } else if (stream eq streamB) {
      val ((id, data), q) = a2b.dequeue
      a2b = q
      stream.receiveData(id, data)
    } else
      throw new IllegalStateException
  }

  def reset(): Unit = {
    a2b = Queue.empty[(Int, ByteBuffer)]
    b2a = Queue.empty[(Int, ByteBuffer)]
  }
}

class FlowStreamMock(transport: Transport) extends FlowTransport {
  var messageHandler: FlowMessageHandler = _

  override def connect(handler: FlowMessageHandler): Unit = {
    messageHandler = handler
    messageHandler.connected()
  }

  override def sendData(id: Int, data: ByteBuffer): Unit = {
    transport.sendFrom(this, id, data)
  }

  def receiveData(id: Int, data: ByteBuffer): Unit = {
    messageHandler.dataReceived(id, data)
  }
}