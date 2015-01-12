package scabot
package core

import akka.actor.{ActorSystem}
import akka.http.model.{HttpResponse, HttpRequest}
import akka.http.server.Route
import akka.http.server.Directives._

trait Service {
  implicit def system: ActorSystem

  def serviceRoute: Route = reject
}

