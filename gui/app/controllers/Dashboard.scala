package controllers

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.mvc.{ Action => PlayAction, _ }
import scabot.github.GithubService
import scabot.github.GithubApiActions
import scabot.jenkins.JenkinsService
import scabot.core
import scabot.server.Actors
import scabot.typesafe.TypesafeApi
import spray.json._
import scala.util._
import scala.concurrent.Future

class Dashboard @Inject() (val system: ActorSystem) extends Controller with GithubService with JenkinsService with TypesafeApi with core.Configuration with core.HttpClient with Actors {

  override def configFile = new java.io.File(sys.props("scabot.config.file"))

  def prs = {
    val gh = new GithubConnection(configs.values.head.github)
    for {
      prs <- gh.pullRequests
      issues <- Future.traverse(prs)(pr => gh.issue(pr.number))
    } yield issues
  }

  def index = PlayAction {
    Ok(views.html.index())
  }

  def pulls = PlayAction.async {
    prs.map(prs => Ok(views.html.pulls(prs)))
  }

  def break(by: String) = PlayAction.async {
    val grouped = prs.map(_.groupBy {
      case pr => by match {
        case "title" => pr.title
        case "milestone" => pr.milestone.map(_.title).getOrElse("No milestone")
        case "asignee" => pr.assignee.map(_.login).getOrElse("No asignee")
      }
    })
    grouped.map(prs => Ok(views.html.break(prs)))
  }

}
