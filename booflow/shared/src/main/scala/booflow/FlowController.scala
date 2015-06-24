package booflow

import java.nio.ByteBuffer

import boopickle._

import scala.collection.mutable

/**
 * `FlowController` manages one end-point of a flow transport. Each end-point acts symmetrically and clients can subscribe in either direction.
 *
 * @param flowTransport Binary data transport that delivers messages to the other `FlowControl`
 * @param producerFactory Creates Producers on-demand
 * @param exceptionPickler Special pickler for serializing any exception that may occur in the stream
 */
class FlowController(flowTransport: FlowTransport, producerFactory: ProducerFactory,
                     implicit val exceptionPickler: PicklerPair[Throwable] = ExceptionPickler.base) extends FlowMessageHandler {

  import FlowController._

  private final val CTRL = 0

  private var currentId = 1
  private var isConnected = false
  private var isEstablished = false
  private val inFlows = mutable.Map.empty[Int, InFlow[_]]
  private val outFlows = mutable.Map.empty[Int, OutFlow[_]]
  private val pendingMessages = mutable.Queue.empty[(Int, ByteBuffer)]

  protected val controlFlow = new ControlFlow {
    override def send(cm: ControlMessage): Unit = {
      val state = new PickleState(new Encoder)
      cmPickler.pickler.pickle(cm)(state)
      val bb = state.toByteBuffer
      sendFlowData(CTRL, bb)
    }
  }

  // connect to stream
  flowTransport.connect(this)

  /**
   * Sends a data packet under the given flow id
   *
   * @param id Identifies the flow
   * @param data Binary data to send
   */
  protected def sendFlowData(id: Int, data: ByteBuffer): Unit = {
    isConnected match {
      case true =>
        // if there are pending messages, send them first
        pendingMessages.dequeueAll { msg =>
          flowTransport.sendData(msg._1, msg._2)
          true
        }
        flowTransport.sendData(id, data)
      case false =>
        // save message in a queue
        pendingMessages.enqueue(id -> data)
        // try to establish connection
        flowTransport.connect(this)
    }
  }

  /**
   * Subscribe to a producer on the other side. Producer is identified by `producerId`. Once subscription is completed, the `SubscriberProxy`
   * will be called whenever there is activity in the flow. Use `initRequest` to manage initial back-pressure.
   *
   * @param producerId Identifier for the producer
   * @param subProxy Proxy for the Subscriber
   * @param initRequest Initial number of messages that the producer can send before back-pressure kicks in.
   * @param unpickler Unpickler that knows how to unpickle type `T`
   * @tparam T Type of the messages in this subscription
   */
  def subscribe[T <: AnyRef](producerId: String, subProxy: SubscriberProxy[T], initRequest: Long = 16)(implicit unpickler: Unpickler[T]): Unit = {
    val id = newId
    // create a new InFlow
    val flow = InFlow(id, subProxy, unpickler, controlFlow)
    inFlows.put(id, flow)
    // request a subscription
    controlFlow.send(SubscribeFlow(id, producerId, initRequest))
  }

  protected def newId = {
    val id = currentId
    currentId += 1
    id
  }

  /**
   * Called by `FlowTransport` when a connection has been established
   */
  override def connected(): Unit = {
    isConnected = true
    // send a Ping message
    controlFlow.send(Ping)
    // if there are pending messages, send them
    pendingMessages.dequeueAll { msg =>
      flowTransport.sendData(msg._1, msg._2)
      true
    }
  }

  /**
   * Called by `FlowTransport` when a connection has been terminated. Cleans up all active flows in both directions.
   */
  override def disconnected(): Unit = {
    isConnected = false
    isEstablished = false

    // close all flows
    inFlows.values.foreach(_.subscriberProxy.onError(new FlowDisconnected))
    outFlows.values.foreach(_.subscriptionProxy.foreach(_.cancel()))

    inFlows.clear()
    outFlows.clear()
  }

  /**
   * All incoming flow messages from `FlowTransport` are handled in this function.
   *
   * @param id Identifies the flow
   * @param data Message data
   */
  override def dataReceived(id: Int, data: ByteBuffer): Unit = {
    val upState = new UnpickleState(new Decoder(data))
    if (id == CTRL) {
      // handle control flow messages
      val cm = cmPickler.unpickler.unpickle(upState)
      cm match {
        case Ping =>
          // send Pong
          controlFlow.send(Pong)
        case Pong =>
          // handshake completed
          isEstablished = true
        case SubscribeFlow(flowId, producerId, initRequest) =>
          // find a producer for this flow
          producerFactory.createProducer(producerId).asInstanceOf[Option[ProducerProxy[AnyRef]]] match {
            case Some(producer) =>
              val outFlow = OutFlow(flowId, producer.pickler, initRequest, this)
              outFlows += flowId -> outFlow
              // subscribe to the producer
              producer.subscribe(outFlow.proxy)
            case None =>
              // no valid producer found, return an error
              val e: Throwable = new Exception("Illegal producer ID")
              controlFlow.send(FlowError(flowId, Pickle.intoBytes(e)))
          }
        case FlowCancel(flowId) =>
          outFlows.get(flowId).foreach { flow =>
            // cancel the subscription
            flow.subscriptionProxy.foreach(_.cancel())
          }
        case FlowRequest(flowId, n) =>
          outFlows.get(flowId).foreach { flow =>
            // request more messages for the subscription
            flow.subscriptionProxy.foreach(_.request(n))
          }
        case FlowSubscribed(flowId) =>
          inFlows.get(flowId).foreach { flow =>
            // flow subscription completed
            flow.subscriberProxy.onSubscribe(flow.subscription)
          }
        case FlowComplete(flowId) =>
          inFlows.get(flowId).foreach { flow =>
            // mark flow as complete
            flow.subscriberProxy.onComplete()
            // remove, no additional messages are coming
            inFlows.remove(flowId)
          }
        case FlowError(flowId, errorData) =>
          inFlows.get(flowId).foreach { flow =>
            val eState = new UnpickleState(new Decoder(errorData))
            val t = exceptionPickler.unpickler.unpickle(eState)
            flow.subscriberProxy.onError(t)
            // remove, no additional messages are coming
            inFlows.remove(flowId)
          }
      }
    } else {
      // normal flow data
      inFlows.get(id).foreach { flow =>
        flow.onNext(upState)
      }
    }
  }
}

object FlowController {
  class FlowDisconnected extends Exception

  /**
   * Messages sent over the control flow
   */
  protected sealed trait ControlMessage

  protected case object Ping extends ControlMessage

  protected case object Pong extends ControlMessage

  /**
   * Messages related to incoming flows
   */
  protected sealed trait InFlowMessage extends ControlMessage {
    val flowId: Int
  }

  protected case class FlowComplete(flowId: Int) extends InFlowMessage

  protected case class FlowError(flowId: Int, error: ByteBuffer) extends InFlowMessage

  protected case class FlowSubscribed(flowId: Int) extends InFlowMessage

  /**
   * Messages related to outgoing flows
   */
  protected sealed trait OutFlowMessage extends ControlMessage {
    val flowId: Int
  }

  protected case class SubscribeFlow(flowId: Int, producerId: String, initRequest: Long) extends OutFlowMessage

  protected case class FlowCancel(flowId: Int) extends OutFlowMessage

  protected case class FlowRequest(flowId: Int, n: Long) extends OutFlowMessage

  // pickler for all control messages
  protected val cmPickler = CompositePickler[ControlMessage].
    addConcreteType[Ping.type].
    addConcreteType[Pong.type].
    addConcreteType[SubscribeFlow].
    addConcreteType[FlowSubscribed].
    addConcreteType[FlowRequest].
    addConcreteType[FlowComplete].
    addConcreteType[FlowCancel].
    addConcreteType[FlowError]

  protected trait ControlFlow {
    /**
     * Serializes and sends a message on the control stream
     * @param cm Message to be sent
     */
    def send(cm: ControlMessage): Unit
  }

  /**
   * Manages a single inbound flow.
   *
   * @param id Flow identifier
   * @param subscriberProxy Proxy for the subscriber that gets called when messages arrive
   * @param unpickler Unpickler for type `T` messages
   * @param controlFlow Flow for control messages
   * @tparam T Type of messages
   */
  protected case class InFlow[T <: AnyRef](id: Int, subscriberProxy: SubscriberProxy[T], unpickler: Unpickler[T], controlFlow: ControlFlow) {
    val subscription = new SubscriptionProxy {
      override def cancel(): Unit = {
        controlFlow.send(FlowCancel(id))
      }
      override def request(n: Long): Unit = {
        controlFlow.send(FlowRequest(id, n))
      }
    }

    def onNext(state: UnpickleState): Unit = subscriberProxy.onNext(unpickler.unpickle(state))
  }

  /**
   * Manages a single outbound flow.
   *
   * @param id Flow identifier
   * @param pickler Pickler for type `T` messages
   * @param initRequest Initial number of messages to request
   * @param flowController FlowController to pickle exceptions and provide the control flow
   * @tparam T Type of messages
   */
  protected case class OutFlow[T <: AnyRef](id: Int, pickler: Pickler[T], initRequest: Long, flowController: FlowController)(implicit exceptionPickler: PicklerPair[Throwable]) {
    var subscriptionProxy = Option.empty[SubscriptionProxy]
    var isOpen = false
    // a subscriber proxy given to the Provider which will redirect calls over the flow
    val proxy = new SubscriberProxy[T] {
      override def onSubscribe(s: SubscriptionProxy): Unit = {
        isOpen = true
        subscriptionProxy = Some(s)
        flowController.controlFlow.send(FlowSubscribed(id))
        // request initial amount
        s.request(initRequest)
      }

      override def onError(t: Throwable): Unit = {
        if (isOpen) {
          isOpen = false
          val errorData = Pickle.intoBytes(t)
          flowController.controlFlow.send(FlowError(id, errorData))
        }
      }

      override def onComplete(): Unit = {
        if (isOpen) {
          isOpen = false
          flowController.controlFlow.send(FlowComplete(id))
        }
      }

      override def onNext(t: T): Unit = {
        if (isOpen) {
          implicit def dataPickler = pickler
          val data = Pickle.intoBytes(t)
          flowController.sendFlowData(id, data)
        }
      }
    }
  }

}

