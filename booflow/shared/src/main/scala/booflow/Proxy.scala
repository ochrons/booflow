package booflow

import boopickle.Pickler

/**
 * Proxy for Reactive Streams -like subscription
 */
trait SubscriptionProxy {
  def request(n: Long)
  def cancel()
}

/**
 * Proxy for Reactive Streams -like subscriber
 */
trait SubscriberProxy[T <: AnyRef] {
  def onSubscribe(s: SubscriptionProxy)
  def onNext(t: T)
  def onError(t: Throwable)
  def onComplete()
}

/**
 * Proxy for Reactive Streams -like producer, with the addition of pickler
 */
trait ProducerProxy[T <: AnyRef] {
  val pickler: Pickler[T]
  def subscribe(s: SubscriberProxy[T])
}

/**
 * Factory to create producers for given identifiers
 */
trait ProducerFactory {
  def createProducer(id: String): Option[ProducerProxy[_ <: AnyRef]]
}
