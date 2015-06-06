package scabot
package github

import akka.event.Logging

import scala.util.{Success, Failure}


trait GithubService extends core.Core with GithubApi { self: core.HttpClient with core.Configuration =>
  import spray.httpx.SprayJsonSupport._

  private lazy val UserRepo = """([^/]+)/(.+)""".r
  def notifyProject(ev: ProjectMessage, repository: Repository): String = {
    val UserRepo(user, repo) = repository.full_name
    val log = s"Processing $ev for $user/$repo"
    system.log.info(log)
    broadcast(user, repo)(ev)
    log
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

