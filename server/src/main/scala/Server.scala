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
    implicit val routingSettings = RoutingSettings(system)
    implicit val routingSetup    = RoutingSetup.apply

    val serverBinding = Http(system).bind(interface = "localhost", port = 8080)

    serverBinding.connections foreach { connection =>
      println("Accepted new connection from " + connection.remoteAddress)

      connection.handleWith(Route.handlerFlow(serviceRoute))
    }
  }
}

import akka.kernel.Bootable

class Scabot extends Bootable with Server with github.Service {

  def startup = {
     // system.actorOf(Props[HelloActor]) ! Start
     startServer
  }
 
 def shutdown = {
   system.shutdown()
 }
}
