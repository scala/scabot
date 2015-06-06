package scabot
package core

import akka.actor.{ActorSelection, ActorRef, ActorSystem}
import akka.event.Logging
import akka.io.IO
import spray.can.Http
import spray.client.pipelining._
import spray.http.{HttpCredentials, HttpResponse}
import spray.httpx.unmarshalling._

import scala.concurrent.{Promise, Future, ExecutionContext}

trait Core extends Util {

  // We need an ActorSystem not only for the actors that make up
  // the server, but also in order to use akka.io.IO's HTTP support,
  // which is actor-based.  (It's not strictly necessary we use
  // the *same* actor system in both places, but whatevs.)
  implicit def system: ActorSystem

  // needed for marshalling implicits for the json api
  implicit def ec: ExecutionContext = system.dispatcher

  def broadcast(user: String, repo: String)(msg: ProjectMessage): Unit

  // marker for messages understood by ProjectActor
  trait ProjectMessage

  // marker for messages understood by PullRequestActor
  trait PRMessage

  type PullRequest

  // see also scala-jenkins-infra
  final val PARAM_REPO_USER = "repo_user"
  final val PARAM_REPO_NAME = "repo_name"
  final val PARAM_REPO_REF  = "repo_ref"
  final val PARAM_PR        = "_scabot_pr"
  final val PARAM_LAST      = "_scabot_last" // TODO: temporary until we run real integration on the actual merge commit

  trait JobContextLense {
    def contextForJob(job: String, pull: PullRequest): Option[String]
    def jobForContext(context: String, pull: PullRequest): Option[String]
  }
}

trait Util { self: Core =>
  def findFirstSequentially[T](futures: Stream[Future[T]])(p: T => Boolean): Future[T] = {
    val resultPromise = Promise[T]
    def loop(futures: Stream[Future[T]]): Unit =
      futures.headOption match {
        case Some(hd) =>
          val hdF = hd.filter(p)
          hdF onFailure {
            case filterEx: NoSuchElementException => loop(futures.tail)
            case e => resultPromise.failure(e)
          }

          hdF onSuccess { case v => resultPromise.success(v) }

        case _ => resultPromise.failure(new NoSuchElementException)
      }
    loop(futures)
    resultPromise.future
  }
}


trait HttpClient { self: Core =>
  import spray.can.Http.HostConnectorSetup
  import spray.client.pipelining._
  import spray.http.{HttpCredentials, HttpRequest}

  // TODO: use spray's url abstraction instead
  implicit class SlashyString(_str: String) { def /(o: Any) = _str +"/"+ o.toString }

  val logResponseBody = {response: HttpResponse => system.log.debug(response.entity.asString.take(2000)); response }

  // use this to initialize an implicit of type Future[SendReceive], for use with p (for "pipeline") and px below
  def setupConnection(host: String, credentials: Option[HttpCredentials] = None): Future[SendReceive] = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val timeout = Timeout(15.seconds)

    val noop: RequestTransformer = identity[HttpRequest]
    val auth: RequestTransformer = credentials.map(addCredentials).getOrElse(noop)

    for (
      Http.HostConnectorInfo(connector, _) <- IO(Http) ? HostConnectorSetup(host = host, port = 443, sslEncryption = true)
    ) yield auth ~> sendReceive(connector) ~> logResponseBody
  }


  def p[T: FromResponseUnmarshaller](req: HttpRequest)(implicit connection: Future[SendReceive]): Future[T] =
    connection flatMap { sr => (sr ~> unmarshal[T]).apply(req) }

  def px(req: HttpRequest)(implicit connection: Future[SendReceive]): Future[HttpResponse] =
    connection flatMap (_.apply(req))
}

// for experimenting with the actors logic
trait NOOPHTTPClient extends HttpClient { self: Core =>
  override def setupConnection(host: String, credentials: Option[HttpCredentials] = None): Future[SendReceive] =
    Future.successful{ x => logRequest(system.log, akka.event.Logging.InfoLevel).apply(x); Future.successful(HttpResponse()) }
}
