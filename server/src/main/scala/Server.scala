package scabot
package server

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.io.IO
import scabot.github.{GithubApi, GithubService}
import scabot.jenkins.{JenkinsApi, JenkinsService}
import spray.can.Http
import spray.http.StatusCodes._
import spray.http._
import spray.routing.{RoutingSettings, _}
import spray.util.LoggingContext

import scala.util.control.NonFatal

// TODO (general): switch to async instead of for comprehensions

// https://github.com/eigengo/activator-akka-spray/blob/master/src/main/scala/api/services.scala

/**
 * Holds potential error response with the HTTP status and optional body
 *
 * @param responseStatus the status code
 * @param response the optional body
 */
case class ErrorResponseException(responseStatus: StatusCode, response: Option[HttpEntity]) extends Exception

/**
 * Allows you to construct Spray ``HttpService`` from a concatenation of routes; and wires in the error handler.
 * It also logs all internal server errors using ``SprayActorLogging``.
 *
 * @param route the (concatenated) route
 */
class RoutedHttpService(route: Route) extends Actor with HttpService with ActorLogging {
  implicit def actorRefFactory = context

  implicit val handler = ExceptionHandler {
    case NonFatal(ErrorResponseException(statusCode, entity)) => ctx =>
      ctx.complete((statusCode, entity))

    case NonFatal(e) => ctx => {
      log.error(e, InternalServerError.defaultMessage)
      ctx.complete(InternalServerError)
    }
  }

  def receive: Receive =
    runRoute(route)(handler, RejectionHandler.Default, context, RoutingSettings.default, LoggingContext.fromActorRefFactory)

}


trait Server { self: core.Core =>
  def startServer() = {
    // TODO make listen address/port configurable
    // TODO use https (probably by putting the webhook behind the same nginx as jenkins itself?)
    IO(Http)(system) ! Http.Bind(system.actorOf(Props(new RoutedHttpService(serviceRoute))), "0.0.0.0", port = 8888)
  }
}

import akka.kernel.Bootable

class Scabot extends Bootable with Server with GithubService with JenkinsService with core.Configuration with core.HttpClient with Actors {
  def startup = {
    startServer()
    startActors()
  }

  def shutdown = {
    system.shutdown()
  }
}

object testingScabot extends GithubApi with JenkinsApi with Actors
  with core.Configuration with core.NOOPHTTPClient with core.Core
