package booflow.example

import booflow.{ProducerProxy, ProducerFactory}

class StreamFactory extends ProducerFactory{
  override def createProducer(id: String): Option[ProducerProxy[_ <: AnyRef]] = {
    ???
  }
}
