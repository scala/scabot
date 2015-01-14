package scabot
package core

import akka.actor.{ActorSystem}
import akka.io.IO
import spray.can.Http
import spray.http.HttpResponse
import spray.httpx.unmarshalling._
import spray.routing.Route
import spray.routing.Directives.reject

import scala.concurrent.{Future, ExecutionContext}

trait Service {
  implicit def system: ActorSystem

  // needed for marshalling implicits in github!!
  implicit def ec: ExecutionContext = system.dispatcher

  def serviceRoute: Route = reject
}


trait HttpClient { self : Service =>
  import spray.can.Http.HostConnectorSetup
  import spray.client.pipelining._
  import spray.http.{HttpCredentials, HttpRequest}

  implicit class SlashyString(_str: String) { def /(o: Any) = _str +"/"+ o.toString }

  // use this to initialize an implicit of type Future[SendReceive], for use with p (for "pipeline") and px below
  def setupConnection(host: String, credentials: HttpCredentials): Future[SendReceive] = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val timeout = Timeout(5 seconds)
    implicit val ec = self.ec

    for (
      Http.HostConnectorInfo(connector, _) <- IO(Http) ? HostConnectorSetup(host = host, port = 443, sslEncryption = true)
    ) yield addCredentials(credentials) ~> sendReceive(connector)
  }

  def p[T: FromResponseUnmarshaller](req: HttpRequest)(implicit connection: Future[SendReceive]): Future[T] =
    connection flatMap { sr => (sr ~> unmarshal[T]).apply(req) }

  def px(req: HttpRequest)(implicit connection: Future[SendReceive]): Future[HttpResponse] = connection flatMap (_.apply(req))
}
