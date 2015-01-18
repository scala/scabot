package scabot
package github

import akka.event.Logging
import spray.routing.Directives

import scala.util.{Success, Failure}


trait GithubService extends core.Core with GithubApi with Directives { self: core.HttpClient with core.Configuration =>
  import spray.httpx.SprayJsonSupport._

  private lazy val UserRepo = """([^/]+)/(.+)""".r
  def notifyProject(ev: ProjectMessage, repository: Repository): String = {
    val UserRepo(user, repo) = repository.full_name
    val log = s"Processing $ev for $user/$repo"
    system.log.info(log)
    tellProjectActor(user, repo)(ev)
    log
  }

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
      logRequestResponse(("github-event"/*, Logging.InfoLevel*/)) {
        headerValueByName("X-GitHub-Event") {
          // case "commit_comment"           =>
          // case "create"                   =>
          // case "delete"                   =>
          // case "deployment"               =>
          // case "deployment_status"        =>
          // case "fork"                     =>
          // case "gollum"                   =>
          // case "issues"                   =>
          // case "member"                   =>
          // case "membership"               =>
          // case "page_build"               =>
          // case "public"                   =>
          // case "repository"               =>
          // case "release"                  =>
          // case "status"                   =>
          // case "team_add"                 =>
          // case "watch"                    =>
          case "issue_comment"               => handleWith(issueCommentEvent)
          case "pull_request_review_comment" => handleWith(pullRequestReviewCommentEvent)
          case "pull_request"                => handleWith(pullRequestEvent) //(fromRequestUnmarshaller[PullRequestEvent](sprayJsonUnmarshaller[PullRequestEvent]), implicitly[ToResponseMarshaller[String]])
//          case "push"                        => handleWith(pushEvent)
          case _                             => reject
        }
      }
    }
  }

  def pullRequestEvent(ev: PullRequestEvent): String = ev match {
    case PullRequestEvent(action, number, pull_request) =>
      notifyProject(ev, ev.pull_request.base.repo)
  }

//  def pushEvent(ev: PushEvent): String = ev match {
//    case PushEvent(ref, before, after, created, deleted, forced, base_ref, commits, head_commit, repository, pusher) =>
//      println(ev)
//      ev.toString
//  }

  def issueCommentEvent(ev: IssueCommentEvent): String = ev match {
    case IssueCommentEvent(action, issue, comment, repository) =>
      notifyProject(ev, repository)
  }

  def pullRequestReviewCommentEvent(ev: PullRequestReviewCommentEvent): String = ev match {
    case PullRequestReviewCommentEvent(action, pull_request, comment, repository) =>
      notifyProject(ev, repository)
  }

}

