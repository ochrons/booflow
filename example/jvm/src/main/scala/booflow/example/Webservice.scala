package booflow.example

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.stage._
import akka.util.ByteString
import booflow.FlowController

import scala.concurrent.duration._

import akka.http.scaladsl.server.Directives
import akka.stream.FlowMaterializer
import akka.stream.scaladsl._

class Webservice(implicit fm: FlowMaterializer, system: ActorSystem) extends Directives {
  def route =
    get {
      pathSingleSlash {
        getFromResource("web/index.html")
      } ~
        // Scala-JS puts them in the root of the resource directory per default,
        // so that's where we pick them up
        path("example-fastopt.js")(getFromResource("example-fastopt.js")) ~
        path("example-opt.js")(getFromResource("example-opt.js")) ~
        path("ws") {
          handleWebsocketMessages(webSocketFlow)
        }
    }

  def webSocketFlow: Flow[Message, Message, Unit] = {
    val wsTransport = new WSTransport
    val fc = new FlowController(wsTransport, null)
    Flow() { implicit b =>
      import FlowGraph.Implicits._

      val collect = b.add(Flow[Message].collect( {
        case BinaryMessage.Strict(bs) => bs.asByteBuffer
      }))

      val filter = b.add(Flow[ByteBuffer].map(bb => wsTransport.receiveData(bb)).filter( _ => false))
      val merge = b.add(Merge[ByteBuffer](2))
      val out = b.add(Flow[ByteBuffer].map(data => BinaryMessage.Strict(ByteString(data))))
      val r = b.add(Source(wsTransport.asPublisher))
      collect ~> filter ~> merge
      r ~> merge ~> out
      (collect.inlet, out.outlet)
    }
  }
}
