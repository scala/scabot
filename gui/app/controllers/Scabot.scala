package controllers

import akka.actor.ActorSystem
import javax.inject.Inject

import play.api.http.LazyHttpErrorHandler
import play.api.http.Status.REQUEST_ENTITY_TOO_LARGE
import play.api.libs.iteratee.{Cont, Done, Input, Iteratee, Traversable}
import play.api.mvc.{Action => PlayAction, _}
import scabot.github.GithubService
import scabot.jenkins.JenkinsService
import scabot.core
import scabot.server.Actors
import spray.json.ParserInput.ByteArrayBasedParserInput
import spray.json._

import scala.concurrent.Future
import scala.util._

class Scabot @Inject() (val system: ActorSystem) extends Controller with GithubService with JenkinsService with core.Configuration with core.HttpClient with Actors {

  override def configFile = new java.io.File(sys.props("scabot.config.file"))

  startActors()

  // X-Github-Event:
  //  commit_comment  Any time a Commit is commented on.
  //  create  Any time a Branch or Tag is created.
  //  delete  Any time a Branch or Tag is deleted.
  //  deployment  Any time a Repository has a new deployment created from the API.
  //  deployment_status Any time a deployment for a Repository has a status update from the API.
  //  fork  Any time a Repository is forked.
  //  gollum  Any time a Wiki page is updated.
  //  issue_comment Any time an Issue is commented on.
  //  issues  Any time an Issue is assigned, unassigned, labeled, unlabeled, opened, closed, or reopened.
  //  member  Any time a User is added as a collaborator to a non-Organization Repository.
  //  membership  Any time a User is added or removed from a team. Organization hooks only.
  //  page_build  Any time a Pages site is built or results in a failed build.
  //  public  Any time a Repository changes from private to public.
  //  pull_request_review_comment Any time a Commit is commented on while inside a Pull Request review (the Files Changed tab).
  //  pull_request  Any time a Pull Request is assigned, unassigned, labeled, unlabeled, opened, closed, reopened, or synchronized (updated due to a new push in the branch that the pull request is tracking).
  //  push  Any Git push to a Repository, including editing tags or branches. Commits via API actions that update references are also counted. This is the default event.
  //  repository  Any time a Repository is created. Organization hooks only.
  //  release Any time a Release is published in a Repository.
  //  status  Any time a Repository has a status update from the API
  //  team_add  Any time a team is added or modified on a Repository.
  //  watch Any time a User watches a Repository.

  def github() = PlayAction(sprayBodyParser) { implicit request =>
    request.headers.get("X-GitHub-Event").collect {
      case "issue_comment"               => handleWith(issueCommentEvent)
      case "pull_request_review_comment" => handleWith(pullRequestReviewCommentEvent)
      case "pull_request"                => handleWith(pullRequestEvent)
      case "push"                        => handleWith(pushEvent)
      // case "status"                   => TODO: use this to propagate combined contexts -- problem: the (payload)[https://developer.github.com/v3/activity/events/types/#statusevent] does not specify the PR
    } match {
      case Some(Success(message)) => Ok(message)
      case Some(Failure(ex)) => InternalServerError(ex.getMessage)
      case None => Status(404)
    }
  }

  def jenkins() = PlayAction(sprayBodyParser) { implicit request =>
    handleWith(jenkinsEvent) match {
      case Success(message) => Ok(message)
      case Failure(ex) =>
        system.log.error(s"Couldn't handle Jenkins event: ${request.body}.\n Fail: $ex")
        InternalServerError(ex.getMessage)
    }
  }

  private def sprayBodyParser: BodyParser[JsValue] =
    BodyParsers.parse.raw(memoryThreshold = 640 * 1024 /*should be enough to keep most requests in memory...*/).mapM {
      buffer =>
        val parsed =
          Future { JsonParser(new ByteArrayBasedParserInput(buffer.asBytes().get)) }
        parsed.onFailure{ case ex => system.log.error(s"Fail in spray body parser: $ex") }
        parsed.onSuccess{ case jsv => system.log.debug(s"Received JSON: $jsv") }
        parsed
    }

  def handleWith[T](handler: T => String)(implicit reader: JsonReader[T], request: Request[JsValue]): Try[String] =
    Try(handler(reader.read(request.body)))

  def index = PlayAction {
    Ok("ohi scabot")
  }
}
