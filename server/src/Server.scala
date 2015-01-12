package scabot
package server

import akka.actor.{Props, ActorRefFactory, ActorSystem}
import akka.http.Http
import akka.http.model.{HttpResponse, HttpRequest}
import akka.http.server.{RoutingSettings, RoutingSetup, Route}
import akka.stream.FlowMaterializer

import scala.concurrent.ExecutionContext

trait Server { self: core.Service =>
  implicit def system: ActorSystem = ActorSystem("scabot")

  def startServer = {
    implicit val materializer = FlowMaterializer()
    implicit val routingSettings = RoutingSettings(system)
    implicit val ec: ExecutionContext = system.dispatcher
    implicit val routingSetup = RoutingSetup.apply

    val serverBinding = Http(system).bind(interface = "localhost", port = 8080)

    serverBinding.connections foreach { connection =>
      println("Accepted new connection from " + connection.remoteAddress)

      connection.handleWith(Route.handlerFlow(serviceRoute))
    }
  }

  sys.addShutdownHook(system.shutdown())
}

object scabot extends Server with github.Service {
  def main (args: Array[String]): Unit = {
    startServer
  }
}