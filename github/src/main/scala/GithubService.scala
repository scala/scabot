package scabot
package github

import akka.actor.{ActorSystem, Props}
import akka.http.marshalling._
import akka.http.model.{HttpRequest, ContentTypes, ContentType, HttpEntity}

import akka.http.server.Directives._
import akka.http.unmarshalling._
import akka.stream.FlowMaterializer
import akka.util.Timeout
import spray.json.{PrettyPrinter, JsonPrinter, RootJsonWriter, DefaultJsonProtocol}

import scala.concurrent.ExecutionContext

/**
 * Created by adriaan on 1/11/15.
 */
trait Service extends core.Service {
  import akka.pattern.ask
  import scala.concurrent.duration._
  implicit val timeout = Timeout(2.seconds)

  import GithubJsonProtocol._
  import akka.http.marshallers.sprayjson.SprayJsonSupport._



  // X-Github-Event:
  //  commit_comment	Any time a Commit is commented on.
  //  create	Any time a Branch or Tag is created.
  //  delete	Any time a Branch or Tag is deleted.
  //  deployment	Any time a Repository has a new deployment created from the API.
  //  deployment_status	Any time a deployment for a Repository has a status update from the API.
  //  fork	Any time a Repository is forked.
  //  gollum	Any time a Wiki page is updated.
  //  issue_comment	Any time an Issue is commented on.
  //  issues	Any time an Issue is assigned, unassigned, labeled, unlabeled, opened, closed, or reopened.
  //  member	Any time a User is added as a collaborator to a non-Organization Repository.
  //  membership	Any time a User is added or removed from a team. Organization hooks only.
  //  page_build	Any time a Pages site is built or results in a failed build.
  //  public	Any time a Repository changes from private to public.
  //  pull_request_review_comment	Any time a Commit is commented on while inside a Pull Request review (the Files Changed tab).
  //  pull_request	Any time a Pull Request is assigned, unassigned, labeled, unlabeled, opened, closed, reopened, or synchronized (updated due to a new push in the branch that the pull request is tracking).
  //  push	Any Git push to a Repository, including editing tags or branches. Commits via API actions that update references are also counted. This is the default event.
  //  repository	Any time a Repository is created. Organization hooks only.
  //  release	Any time a Release is published in a Repository.
  //  status	Any time a Repository has a status update from the API
  //  team_add	Any time a team is added or modified on a Repository.
  //  watch	Any time a User watches a Repository.


  // handle marshalling & routing between http clients and ServiceActor
  override def serviceRoute = super.serviceRoute ~ path("github") {
    post {
      headerValueByName("X-Github-Event") {
        // case "commit_comment"              =>
        // case "create"                      =>
        // case "delete"                      =>
        // case "deployment"                  =>
        // case "deployment_status"           =>
        // case "fork"                        =>
        // case "gollum"                      =>
        // case "issue_comment"               =>
        // case "issues"                      =>
        // case "member"                      =>
        // case "membership"                  =>
        // case "page_build"                  =>
        // case "public"                      =>
        // case "pull_request_review_comment" =>
        case "pull_request"                   => handleWith(pullRequestEvent) //(fromRequestUnmarshaller[PullRequestEvent](sprayJsonUnmarshaller[PullRequestEvent]), implicitly[ToResponseMarshaller[String]])
        // case "push"                        =>
        // case "repository"                  =>
        // case "release"                     =>
        // case "status"                      =>
        // case "team_add"                    =>
        // case "watch"                       =>
        case _                                => reject
      }

    }
  }

  def pullRequestEvent(ev: PullRequestEvent): String = ev match { case PullRequestEvent(action, nb, pr) =>
    println(ev)
    ""
  }

//  // actual processing of requests
//  class ServiceActor extends akka.actor.Actor {
//    def receive = {
//      case PullRequestEvent(action, nb, pr) =>
//    }
//  }

}

