package scabot
package core

import akka.actor.{ActorSystem}
import akka.http.server.Route
import akka.http.server.Directives._
import akka.stream.FlowMaterializer

import scala.concurrent.ExecutionContext

trait Service {
  implicit def system: ActorSystem

  // needed for marshalling implicits in github!!
  implicit def ec: ExecutionContext = system.dispatcher
  implicit def materializer         = FlowMaterializer()

  def serviceRoute: Route = reject
}

