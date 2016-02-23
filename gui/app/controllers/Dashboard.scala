package controllers

import akka.actor.ActorSystem
import javax.inject.Inject
import play.api.mvc.{ Action => PlayAction, _ }
import scabot.github._
import scabot.jenkins.JenkinsService
import scabot.core
import scabot.server.Actors
import scabot.lightbend.LightbendApi
import spray.json._
import scala.util._
import scala.concurrent.Future

object Dashboard {
  case class ReportRow(
      pr: GithubApiTypes#PullRequest,
      issue: GithubApiTypes#Issue,
      cla: String,
      reviewed: String)
}

class Dashboard @Inject() (val system: ActorSystem) extends Controller with GithubService with JenkinsService with LightbendApi with core.Configuration with core.HttpClient with Actors {
  import Dashboard._

  override def configFile = new java.io.File(sys.props("scabot.config.file"))

  def getCommitStatus(ctx: String, ccs: CombiCommitStatus) = {
    ccs.byContext.get(Some(ctx)).flatMap(_.headOption) match {
      case Some(status) if status.success => "✔"
      case Some(status) if status.pending => "…"
      case Some(status) if status.failure => "✘"
      case None => "?"
    }
  }

  def prs = {
    val gh = new GithubConnection(configs("scala").github)
    gh.pullRequests.flatMap { prs =>
      Future.traverse(prs) { pr =>
        for {
          issue <- gh.issue(pr.number)
          lastCommitSha <- gh.pullRequestCommits(pr.number).map(_.last.sha)
          status <- gh.commitStatus(lastCommitSha)
        } yield ReportRow(pr, issue,
            getCommitStatus(CommitStatusConstants.CLA, status),
            getCommitStatus(CommitStatusConstants.REVIEWED, status))
      }
    }
  }

  def index = PlayAction {
    Ok(views.html.index())
  }

  def pulls = PlayAction.async {
    prs.map(prs => Ok(views.html.pulls(prs)))
  }

  def break(by: String) = PlayAction.async {
    val grouped = prs.map(_.groupBy {
      case row => by match {
        case "milestone" => row.issue.milestone.map(_.title).getOrElse("No milestone")
        case "asignee" => row.issue.assignee.map(_.login).getOrElse("No asignee")
        case "cla" => row.cla
        case "reviewed" => row.reviewed
      }
    })
    grouped.map(prs => Ok(views.html.break(prs)))
  }

}
