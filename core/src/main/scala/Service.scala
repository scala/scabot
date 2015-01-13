package scabot
package core

import akka.actor.{ActorSystem}
import spray.routing.Route
import spray.routing.Directives.reject

import scala.concurrent.ExecutionContext

trait Service {
  implicit def system: ActorSystem

  // needed for marshalling implicits in github!!
  implicit def ec: ExecutionContext = system.dispatcher

  def serviceRoute: Route = reject
}

