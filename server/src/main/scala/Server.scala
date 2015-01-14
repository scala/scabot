package scabot
package server

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.io.IO
import com.typesafe.config.{ConfigList, ConfigObject, ConfigValueType, ConfigValue}
import scabot.github.GithubService
import spray.can.Http
import spray.http.StatusCodes._
import spray.http._
import spray.routing.{RoutingSettings, _}
import spray.util.LoggingContext

import scala.util.control.NonFatal

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


trait Server {
  self: core.Service =>
  implicit def system: ActorSystem = ActorSystem("scabot")

  def startServer = {
    IO(Http)(system) ! Http.Bind(system.actorOf(Props(new RoutedHttpService(serviceRoute))), "0.0.0.0", port = 8888)
  }
}

import akka.event.Logging
import com.typesafe.config.{Config => TConfig, ConfigFactory}

trait Configuration { self: core.Service =>
  val log = Logging(system, this.getClass)

  import collection.JavaConverters._

  /**
   * Configuration for running a particular verification.
   */
  case class Config(githubUser: Credentials,
                    jenkinsUrl: String,
                    jenkinsUser: Credentials,
                    project: GithubProject,
                    jenkinsJobs: Set[JenkinsJob])

  case class Credentials(user: String, pw: String)
  case class GithubProject(user: String, project: String)
  case class JenkinsJob(name: String) {
    override def toString = name
  }
  object JenkinsJob {
    implicit object OrderedJenkinsJob extends Ordering[JenkinsJob] {
      def compare(x: JenkinsJob, y: JenkinsJob): Int = x.name.compare(y.name)
    }
  }

  if (configs.isEmpty) {
    log.error("No configuration defined!")
    System exit 1
  }

  lazy val configs = for {
    file <- configFiles
    if file.exists
    config <- getConfigs(ConfigFactory parseFile file)
  } yield config

//  // Load configuration
//  for ((name, c) <- configs) {
//    log.debug("Adding config for: " + name)
//    system addConfig c
//  }

  // TODO make configurable
  def configFiles: Seq[java.io.File] = Seq(new java.io.File("ghpr.conf"))

  def getConfigs(config: TConfig): Seq[(String, Config)] = {
    // TODO - useful error messages on failure.
    def configString(c: ConfigValue): Option[String] =
      if (c.valueType == ConfigValueType.STRING) Some(c.unwrapped.toString)
      else None

    def configObj(c: ConfigValue): Option[ConfigObject] =
      if (c.valueType == ConfigValueType.OBJECT) Some(c.asInstanceOf[ConfigObject])
      else None

    def configList(c: ConfigValue): Option[ConfigList] =
      if (c.valueType == ConfigValueType.LIST)
        Some(c.asInstanceOf[ConfigList])
      else None

    def configStringList(c: ConfigValue): Option[Seq[String]] =
      configList(c) map { x =>
        x.iterator.asScala flatMap configString toSeq
      }

    def c2cred(c: ConfigObject): Option[Credentials] =
      for {
        user <- configString(c.get("user"))
        pw <- configString(c.get("password"))
      } yield Credentials(user, pw)

    def c2proj(c: ConfigObject): Option[GithubProject] =
      for {
        user <- configString(c.get("user"))
        project <- configString(c.get("project"))
      } yield GithubProject(user, project)

    def c2c(c: ConfigObject): Option[Config] =
      for {
      // Jenkins Config
        jo <- configObj(c.get("jenkins"))
        jenkinsUrl <- configString(jo.get("url"))
        jusero <- configObj(jo.get("user"))
        jenkinsUser <- c2cred(jusero)
        rawJobs <- configStringList(jo.get("jobs"))
        jobs = (rawJobs map JenkinsJob.apply).toSet
        // Github config
        gho <- configObj(c.get("github"))
        ghuo <- configObj(gho.get("user"))
        ghuser <- c2cred(ghuo)
        gpo <- configObj(gho.get("project"))
        project <- c2proj(gpo)
      } yield Config(ghuser, jenkinsUrl, jenkinsUser, project, jobs)

    val configs =
      for {
        kv <- config.root.entrySet.iterator.asScala
        name = kv.getKey.toString
        obj <- configObj(kv.getValue)
        c <- c2c(obj)
      } yield name -> c

    configs.toSeq
  }


}

import akka.kernel.Bootable

class Scabot extends Bootable with Server with GithubService {
  def githubAuthToken = "meh"

  // TODO: read from config (which itself is generated by chef)
  def startup = {
    startServer
  }

  def shutdown = {
    system.shutdown()
  }
}
