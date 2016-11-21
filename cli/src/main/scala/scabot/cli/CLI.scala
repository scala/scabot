package scabot
package cli

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class CLI(configName: String)
    extends github.GithubApi with lightbend.LightbendApi
    with core.Configuration with core.HttpClient with core.Core {

  override def configFile =
    new java.io.File("./cli.conf")

  override def broadcast(
      user: String, repo: String)(msg: ProjectMessage) =
    throw new UnsupportedOperationException

  override val system =
    akka.actor.ActorSystem()

  lazy val github =
    new GithubConnection(configs(configName).github)

  lazy val lightbend =
    new LightbendConnection()

  def await[T](f: Future[T]): T =
    Await.result(f, Duration.Inf)

  def shutdown(): Unit = system.shutdown()

}
