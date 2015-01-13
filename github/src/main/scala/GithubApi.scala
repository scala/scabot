package scabot
package github

import spray.http.HttpResponse
import spray.httpx.unmarshalling.FromResponseUnmarshaller

import scala.concurrent.{Future, ExecutionContext}

import akka.io.IO
import spray.can.Http


trait GithubApi extends GithubApiTypes with GithubJsonProtocol with GithubApiActions { self: core.Service => }

// definitions in topo-order, no cycles in dependencies
trait GithubApiTypes {
  type Date = Option[Either[String, Long]]

  case class User(login: String)
  case class Author(name: String, email: String) // , username: Option[String]
  
  case class Repository(name: String, full_name: String, git_url: String, updated_at: Date, created_at: Date, pushed_at: Date) // owner: Either[User, Author] 

  case class GitRef(sha: String, label: String, ref: String, repo: Repository, user: User)
  
  case class PullRequest(number: Int, state: String, title: String, body: String, created_at: Date, updated_at: Date, closed_at: Date, merged_at:  Date, head: GitRef, base: GitRef, user: User, mergeable: Option[Boolean], merged_by: Option[User]) //, comments: Int, commits: Int, additions: Int, deletions: Int, changed_files: Int)

  case class Label(name: String, color: String, url: Option[String] = None)
  case class Milestone(number: Int, state: String, title: String, description: String, creator: User, created_at: Date, updated_at: Date, closed_at: Date, due_on: Option[Date])
  case class Issue(number: Int, state: String, title: String, body: String, user: User, labels: List[Label], assignee: Option[User], milestone: Option[Milestone], created_at: Date, updated_at: Date, closed_at: Date)

  case class CommitInfo(distinct: Boolean, message: String, timestamp: Date, author: Author, committer: Author, added: List[String], removed: List[String], modified: List[String])
  case class Commit(sha: String, commit: CommitInfo, url: Option[String] = None)
  case class CommitStatus(state: String, context: Option[String] = None, description: Option[String] = None, target_url: Option[String] = None)

  case class IssueComment(body: String, user: User, created_at: Date, updated_at: Date, id: Option[Long] = None)
  case class PullRequestComment(body: String, user: User, commit_id: String, path: String, position: Int, created_at: Date, updated_at: Date, id: Option[Long] = None) // diff_hunk, original_position, original_commit_id

  case class PullRequestEvent(action: String, number: Int, pull_request: PullRequest)
  case class PushEvent(ref: String, before: String, after: String, created: Boolean, deleted: Boolean, forced: Boolean, base_ref: Option[String], commits: List[CommitInfo], head_commit: CommitInfo, repository: Repository, pusher: Author)
  case class PullRequestReviewCommentEvent(action: String, pull_request: PullRequest, comment: PullRequestComment, repository: Repository)

  case class IssueCommentEvent(action: String, issue: Issue, comment: IssueComment, repository: Repository)

  case class AuthApp(name: String, url: String)
  case class Authorization(token: String, app: AuthApp, note: Option[String])
}

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

// TODO: can we make this more debuggable?
// TODO: test against https://github.com/github/developer.github.com/tree/master/lib/webhooks
trait GithubJsonProtocol extends GithubApiTypes with DefaultJsonProtocol { type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtUser             : RJF[User]                          = jsonFormat1(User)
  implicit lazy val _fmtAuthor           : RJF[Author]                        = jsonFormat2(Author)
  implicit lazy val _fmtRepository       : RJF[Repository]                    = jsonFormat6(Repository)

  implicit lazy val _fmtGitRef           : RJF[GitRef]                        = jsonFormat5(GitRef)

  implicit lazy val _fmtPullRequest      : RJF[PullRequest]                   = jsonFormat13(PullRequest)

  implicit lazy val _fmtLabel            : RJF[Label]                         = jsonFormat3(Label)
  implicit lazy val _fmtMilestone        : RJF[Milestone]                     = jsonFormat9(Milestone)
  implicit lazy val _fmtIssue            : RJF[Issue]                         = jsonFormat11(Issue)

  implicit lazy val _fmtCommitInfo       : RJF[CommitInfo]                    = jsonFormat8(CommitInfo)
  implicit lazy val _fmtCommit           : RJF[Commit]                        = jsonFormat3(Commit)
  implicit lazy val _fmtCommitStatus     : RJF[CommitStatus]                  = jsonFormat4(CommitStatus)

  implicit lazy val _fmtIssueComment     : RJF[IssueComment]                  = jsonFormat5(IssueComment)
  implicit lazy val _fmtPullRequestComment: RJF[PullRequestComment]           = jsonFormat8(PullRequestComment)

  implicit lazy val _fmtPullRequestEvent : RJF[PullRequestEvent]              = jsonFormat3(PullRequestEvent)
  implicit lazy val _fmtPushEvent        : RJF[PushEvent]                     = jsonFormat11(PushEvent)
  implicit lazy val _fmtPRCommentEvent   : RJF[PullRequestReviewCommentEvent] = jsonFormat4(PullRequestReviewCommentEvent)
  implicit lazy val _fmtIssueCommentEvent: RJF[IssueCommentEvent]             = jsonFormat4(IssueCommentEvent)

  implicit lazy val _fmtAuthorization    : RJF[Authorization]                 = jsonFormat3(Authorization)
  implicit lazy val _fmtAuthApp          : RJF[AuthApp]                       = jsonFormat2(AuthApp)
}



trait GithubApiActions extends GithubJsonProtocol { self : core.Service =>
  def githubAuthToken: String

  import spray.can.Http.HostConnectorSetup
  import spray.http.{GenericHttpCredentials, Uri, HttpRequest}
  import spray.client.pipelining._

  import spray.httpx.SprayJsonSupport._

  import akka.pattern.ask
  import akka.util.Timeout
  import scala.concurrent.duration._
  implicit val timeout = Timeout(5 seconds)

  // addHeader("X-My-Special-Header", "fancy-value")
  // "Accept" -> "application/vnd.github.v3+json"
  def setup = for (
    Http.HostConnectorInfo(connector, _) <- IO(Http) ? HostConnectorSetup(host = "api.github.com", port = 443, sslEncryption = true)
  ) yield sendReceive(connector)


  def credentials = new GenericHttpCredentials("token", githubAuthToken)

  def p[T: FromResponseUnmarshaller](req: HttpRequest): Future[T] = setup flatMap { sr => (
         addCredentials(credentials)
      ~> sr
      ~> unmarshal[T]).apply(req)
  }

  def px(req: HttpRequest): Future[HttpResponse] = (
    addCredentials(credentials)
      ~> sendReceive).apply(req)

  class For(user: String, repo: String) {
    implicit class SlashyString(_str: String) { def /(o: Any) = _str +"/"+ o.toString }
    type N[x] = Future[List[x]]
    type A[x] = Future[x]

    def api = s"/repos/$user/$repo"

    def pullRequests                                         = p[List[PullRequest]](Get(Uri(api / "pulls")))
    def closedPullRequests                                   = p[List[PullRequest]](Get(Uri(api / "pulls") withQuery Map("state" -> "closed")))
    def pullRequest(number: String)                          = p[PullRequest]      (Get(Uri(api / "pulls" / number)))
    def pullRequestCommits(number: String)                   = p[List[Commit]]     (Get(Uri(api / "pulls" / number / "commits")))
    def deletePRComment(id: String)                          = px               (Delete(Uri(api / "pulls" / "comments" / id)))

    def pullRequestComments(number: String)                  = p[List[PullRequestComment]](Get(Uri(api / "issues" / number / "comments")))
    def addPRComment(number: String, comment: IssueComment)  = p[IssueComment]           (Post(Uri(api / "issues" / number / "comments"), comment))
    def issue(number: String)                                = p[Issue]                   (Get(Uri(api / "issues" / number)))
//    def setMilestone(number: String, milestone: Int)       = px                       (Patch(Uri(api / "issues" / number, JObject(List(JField("milestone", JInt(milestone)))))))
    def addLabel(number: String, labels: List[Label])        = p[Label]                  (Post(Uri(api / "issues" / number / "labels"), labels))
    def deleteLabel(number: String, label: String)           = px                      (Delete(Uri(api / "issues" / number / "labels" / label)))
    def labels(number: String)                               = p[List[Label]]             (Get(Uri(api / "issues" / number / "labels")))

    // most recent status comes first in the resulting list!
    def commitStatus(sha: String)                            = p[List[CommitStatus]]      (Get(Uri(api / "statuses" / sha)))
    def setCommitStatus(sha: String, status: CommitStatus)   = p[CommitStatus]           (Post(Uri(api / "statuses" / sha), status))

    def allLabels                                            = p[List[Label]]             (Get(Uri(api / "labels")))
    def createLabel(label: Label)                            = p[List[Label]]            (Post(Uri(api / "labels"), label))

    def addCommitComment(sha: String, comment: IssueComment) = p[IssueComment]           (Post(Uri(api / "commits" / sha / "comments"), comment))
    def commitComments(sha: String)                          = p[List[IssueComment]]      (Get(Uri(api / "commits" / sha / "comments")))

    def deleteCommitComment(id: String): Unit                = px                      (Delete(Uri(api / "comments" / id)))

    def repoMilestones(state: String = "open")               = p[List[Milestone]]         (Get(Uri(api / "milestones") withQuery Map("state" -> state)))

  }

  // def editPRComment(user: String, repo: String, id: String, comment: IssueComment)    = patch[IssueComment](pulls + "/comments/$id")
  // // Normalize sha if it's not 40 chars
  // // GET /repos/:owner/:repo/commits/:sha
  // def normalizeSha(user: String, repo: String, sha: String): String =
  //   if (sha.length == 40) sha
  //   else try {
  //     val url = makeAPIurl(s"/repos/$user/$repo/commits/$sha")
  //     val action = url >- (x => parseJsonTo[PRCommit](x).sha)
  //     Http(action)
  //   } catch {
  //     case e: Exception =>
  //       println(s"Error: couldn't normalize $sha (for $user/$repo): "+ e)
  //       sha
  //   }


}




//// note: it looks like the buildbot github user needs administrative permission to create labels,
//// but also to set the commit status
//object Authenticate {
//
//  private[this] val authorizations = :/("api.github.com").secure / "authorizations" <:< Map("User-Agent" -> USER_AGENT)
//
//  val authScopes = """{
// "scopes": [
//   "user",
//   "repo",
//   "repo:status"
// ],
// "note": "scabot API Access"
//}"""
//
//  /** This method looks for a previous GH authorization for this API and retrieves it, or
//    * creates a new one.
//    */
//  def authenticate(user: String, pw: String): Authorization = {
//    val previousAuth: Option[Authorization] =
//      (getAuthentications(user,pw) filter (_.note == Some("scabot API Access"))).headOption
//    previousAuth getOrElse makeAuthentication(user, pw)
//  }
//
//
//  def makeAuthentication(user: String, pw: String): Authorization =
//    Http(authorizations.POST.as_!(user, pw) << authScopes >- parseJsonTo[Authorization])
//
//  def getAuthentications(user: String, pw: String): List[Authorization] =
//    Http(authorizations.as_!(user, pw) >- parseJsonTo[List[Authorization]])
//
//  def deleteAuthentication(auth: Authorization, user: String, pw: String): Unit =
//    Http( (authorizations / auth.id).DELETE.as_!(user,pw) >|)
//
//  def deleteAuthentications(user: String, pw: String): Unit =
//    getAuthentications(user, pw) foreach { a =>
//      deleteAuthentication(a, user, pw)
//    }
//}
//

//
//object makeJson { def apply(x: Any): String = ??? }
//
//case class AuthApp(name: String, url: String)
//case class Authorization(id: String, token: String, app: AuthApp, note: Option[String])
//case class PullMini(state: String,
//                    number: String,
//                    title: String,
//                    body: String,
//                    user: User,
//                    updated_at: String) extends Ordered[PullMini] {
//  def compare(other: PullMini): Int = number compare other.number
//}
//
///** A link to something. You know, like a URL.*/
//case class Link(href: String)
///** Lots of data structure have links.  This helps us use the right naming convention for them. */
//trait HasLinks {
//  def _links: Map[String, Link]
//}
//
//case class User(
//                 login: String,
//                 name: Option[String],
//                 email: Option[String],
//                 repository: Option[Repository]
//                 )
//
//case class Repository(
//                       name: String,
//                       owner: User,
//                       url: String,
//                       git_url: String,
//                       updated_at: String,
//                       created_at: String,
//                       pushed_at: String
//                       )
//
//case class PullRequest(
//                 number: Int,
//                 head: GitRef,
//                 base: GitRef,
//                 user: User,
//                 title: String,
//                 body: String,
//                 state: String,
//                 updated_at: String,
//                 created_at: String,
//                 mergeable: Option[Boolean],
//                 milestone: Option[Milestone] // when treating an issue as a pull
//                 ) extends Ordered[PullRequest] {
//  def compare(other: PullRequest): Int = number compare other.number
//  def sha10  = head.sha10
//  def ref    = head.ref
//  def branch = head.label.replace(':', '/')
//  def date   = updated_at takeWhile (_ != 'T')
//  def time   = updated_at drop (date.length + 1)
//
//  override def toString = s"${base.repo.owner.login}/${base.repo.name}#$number"
//}
//
////action	string	The action that was performed. Can be one of “assigned”, “unassigned”, “labeled”, “unlabeled”, “opened”, “closed”, or “reopened”, or “synchronize”. If the action is “closed” and the merged key is false, the pull request was closed with unmerged commits. If the action is “closed” and the merged key is true, the pull request was merged.
////number	integer	The pull request number.
////pull_request	object	The pull request itself.
//case class PullRequestEvent(action: String, number: Int, pull_request: PullRequest)
//
//case class Issue(milestone: Option[Milestone])
//
//case class GitRef(
//                   sha: String,
//                   label: String,
//                   ref: String,
//                   repo: Repository,
//                   user: User
//                   ) {
//  def sha10 = sha take 10
//}
//
//case class PRCommit(
//                     sha: String,
//                     url: String,
//                     commit: CommitInfo) {
//  // meh
//  def shaMatches(other: String) = other.length >= 5 && sha.startsWith(other) || other.startsWith(sha)
//}
//
//case class CommitInfo(
//                       committer: CommitAuthor,
//                       author: CommitAuthor,
//                       message: String
//                       )
//
//case class CommitAuthor(
//                         email: String,
//                         name: String,
//                         date: String
//                         )
//
//case class Comment(
//                    url: String,
//                    id: String,
//                    body: String,
//                    user: User,
//                    created_at: String,
//                    updated_at: String)
//
//case class CommitStatus(
//                         // User defined
//                         state: String,
//                         target_url: Option[String]=None,
//                         description: Option[String]=None) {
//  //  // Github Added
//  //  id: Option[String] = None,
//  //  created_at: Option[String]=None,
//  //  updated_at: Option[String]=None,
//  //  url: Option[String]=None,
//  //  creator: Option[User]=None) {
//  def toJson = makeJson(this)
//
//  import CommitStatus._
//
//  def job = description.flatMap(_.split(" ").headOption)
//
//  def forJob(job: String) = description match { case Some(s) if s.startsWith(job) => true case _ => false }
//  // jenkins job is running
//  def pending = state == PENDING
//  // jenkins job was successful
//  def success = state == SUCCESS
//  // jenkins job found an error
//  def error   = state == ERROR
//
//  // we don't add a SUCCESS job when there's other pending jobs waiting
//  // we add a PENDING job with a description like "$job OK $message"
//  def fakePending = {
//    pending && description.flatMap(_.split(" ", 3).toList.drop(1).take(1).headOption).exists(_ == FAKE_PENDING)
//  }
//  def done    = success || error || fakePending
//
//  def finishedUnsuccessfully = error || failed
//
//  // something went wrong
//  def failed  = state == FAILURE
//
//  def stateString = (if (target_url.nonEmpty) "["+state+"]("+ target_url.get +")" else state)
//  override def toString = stateString +": "+ description.getOrElse("")
//}
//object CommitStatus {
//  final val PENDING = "pending"
//  final val SUCCESS = "success"
//  final val ERROR   = "error"
//  final val FAILURE = "failure"
//
//  // to distinguish PENDING jobs that are done but waiting on other PENDING jobs from truly pending jobs
//  // the message of other PENDING jobs should never start with "$job OK"
//  final val FAKE_PENDING = "OK"
//
//  // TODO: assert(!name.contains(" ")) for all job* methods below
//  def jobQueued(name: String) = CommitStatus(PENDING, None, Some(name +" queued."))
//  def jobStarted(name: String, url: String) = CommitStatus(PENDING, Some(url), Some(name +" started."))
//  // assert(!message.startsWith(FAKE_PENDING))
//  def jobEnded(name: String, url: String, ok: Boolean, message: String) =
//    CommitStatus(if(ok) SUCCESS else ERROR, Some(url), Some((name +" "+ message).take(140)))
//
//  // only used for last commit
//  def jobEndedBut(name: String, url: String, message: String)(prev: String) =
//    CommitStatus(PENDING, Some(url), Some((name +" "+ FAKE_PENDING +" but waiting for "+ prev).take(140)))
//
//  // depends on the invariant maintained by overruleSuccess so that we only have to look at the most recent status
//  def jobDoneOk(cs: List[CommitStatus]) = cs.headOption.map(st => st.success || st.fakePending).getOrElse(false)
//
//
//  /** Find commit status that's either truly pending (not fake pending) or that found an error,
//    * and for which there's no corresponding successful commit status
//    */
//  def notDoneOk(commitStati: List[CommitStatus]): Iterable[CommitStatus] = {
//    val grouped  = commitStati.groupBy(_.job)
//    val problems = grouped.flatMap {
//      case (Some(jobName), jobAndCommitStati) if !jobAndCommitStati.exists(_.success) =>
//        jobAndCommitStati.filter(cs => (cs.pending && !cs.fakePending) || cs.error)
//      case _ =>
//        Nil
//    }
//    // println("notDoneOk grouped: "+ grouped.mkString("\n"))
//    // println("problems: "+ problems)
//    problems
//  }
//}
//
//
//case class IssueComment(body: String) {
//  // import net.liftweb.json._
//  // import JsonAST._
//  // import Printer._
//
//  def toJson = makeJson(this) //pretty(render(JObject(List(JField("body", JString(body))))))
//}
//
//case class Label(name: String, color: String = "FFFFFF", url: Option[String] = None) {
//  override def equals(o: Any) = o match {
//    case Label(`name`, _, _) => true
//    case _ => false
//  }
//}
//
//// {
////    "url": "https://api.github.com/repos/octocat/Hello-World/milestones/1",
////    "number": 1,
////    "state": "open",
////    "title": "v1.0",
////    "description": "",
////    "creator": {
////      "login": "octocat",
////      "id": 1,
////      "avatar_url": "https://github.com/images/error/octocat_happy.gif",
////      "gravatar_id": "somehexcode",
////      "url": "https://api.github.com/users/octocat"
////    },
////    "open_issues": 4,
////    "closed_issues": 8,
////    "created_at": "2011-04-10T20:09:31Z",
////    "due_on": null
////  }
////]
//case class Milestone(number: Int, title: String, description: String) {
//  // don't know how to ignore the trailing dot using java regexes, so using stripFinalDot...
//  private val regex = "Merge to (\\S*)".r
//  private def stripFinalDot(s: String) = (if(s.nonEmpty && s.last == '.') s.init else s).trim
//
//  def mergeBranch =
//    try regex.findFirstMatchIn(description).flatMap(m => m.subgroups.headOption.map(stripFinalDot))
//    catch { case _: NullPointerException => None } // no idea how this happens, no time to find out
//}
