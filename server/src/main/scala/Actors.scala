package scabot
package server

import java.util.NoSuchElementException

import akka.actor._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
 * Created by adriaan on 1/15/15.
 */
trait Actors {
  self: core.Core with core.Configuration with github.GithubApi with jenkins.JenkinsApi =>
  implicit lazy val system: ActorSystem = ActorSystem("scabot")

  private lazy val githubActor = system.actorOf(Props(new GithubActor), "github")

  // project actors are supervised by the github actor
  // pull requst actors are supervised by their project actor
  private def projectActorName(user: String, repo: String) = s"$user-$repo"

  def tellProjectActor(user: String, repo: String)(msg: ProjectMessage) =
    system.actorSelection(githubActor.path / projectActorName(user, repo)) ! msg


  def startActors() = {
    githubActor ! GithubActor.StartProjectActors(configs)
  }


  object GithubActor {
    case class StartProjectActors(configs: Map[String, Config])
  }

  import GithubActor._
  class GithubActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case StartProjectActors(configs)   =>
        configs map { case (name, config) =>
          context.actorOf(Props(new ProjectActor(config)), projectActorName(config.github.user, config.github.repo))
        } foreach { _ ! Synch }
    }
  }

  case object Synch extends ProjectMessage

  // represents a github project at github.com/${config.github.user}/${config.github.repo}
  class ProjectActor(config: Config) extends Actor with ActorLogging {
    lazy val githubApi = new GithubConnection(config.github)
    import context._

    // find or create actor responsible for PR #`nb`
    def prActor(nb: Int) = child(nb.toString).getOrElse(actorOf(Props(new PullRequestActor(nb, config)), nb.toString))

    // supports messages of type ProjectMessage
    override def receive: Receive = {
      case Synch =>
        githubApi.pullRequests.foreach { prs =>
          prs.foreach { pr => prActor(pr.number) ! PullRequestEvent("synchronize", pr.number, pr)}
        }
        // synch every once in a while, just in case we missed a webhook event somehow
        // TODO make timeout configurable
        context.system.scheduler.scheduleOnce(30 minutes, self, Synch) 

      case ev@PullRequestEvent(_, nb, _) =>
        prActor(nb) ! ev

      case PullRequestReviewCommentEvent("created", pull_request, comment, _) =>
        prActor(pull_request.number) ! comment

      case IssueCommentEvent("created", issue, comment, _) =>
        prActor(issue.number) ! comment

      case js@JobState(_, _, bs) =>
        (for {
          prParam <- Try(bs.parameters(PARAM_PR)) // we only care about jobs we started, and which thus have this parameter (when restarted manually, they should be carried forward automatically)
          prNum   <- Try(prParam.toInt)
        } prActor(prNum) ! js) // TODO: report failure
    }
  }


  class PullRequestActor(pr: Int, config: Config) extends Actor with ActorLogging {
    lazy val githubApi  = new GithubConnection(config.github)
    lazy val jenkinsApi = new JenkinsConnection(config.jenkins)

    private var lastSynchronized: Date = None

    // TODO: distrust input, go back to source to verify
    // supports messages of type PRMessage
    // PullRequestEvent.action: “assigned”, “unassigned”, “labeled”, “unlabeled”, “opened”, “closed”, or “reopened”, or “synchronize”
    override def receive: Actor.Receive = {
      // process all commits (need to launch builds?) & PR comments
      case PullRequestEvent(a@"synchronize", _, pull_request) if pull_request.updated_at != lastSynchronized =>
        lastSynchronized = pull_request.updated_at
        log.info(s"PR synch --> $pull_request")
        handlePR(a, pull_request)

      case PullRequestEvent(a@("opened" | "reopened"), _, pull_request) =>
        log.info(s"PR open --> $pull_request")
        handlePR(a, pull_request)

      case PullRequestEvent("closed", _, _) =>
        log.info(s"PR closed!")
        context.stop(self)

      case comment@IssueComment(body, user, created_at, updated_at, id) =>
        log.info(s"Comment by $user:\n$body")
        handleComment(comment)

      // TODO: on CommitStatusEvent, propagateEarlierStati(pull)

      case js@JobState(name, _, BuildState(number, phase, parameters, _, _, full_url, consoleLog)) =>
        for { // fetch the state from jenkins -- the webhook doesn't pass in result correctly (???)
          bs <- jenkinsApi.buildStatus(name, number)
          _  <- Future.successful(log.debug(s"Build status for $name: $bs"))
        } yield {
          val sha = parameters(PARAM_REPO_REF)
          log.info(s"Job state for $name [$number] @${sha.take(6)}: ${bs.status} at ${bs.url}") // result is not passed in correctly?
          handleJobState(name, sha, bs)
        }

      case PullRequestComment(body, user, commitId, path, pos, created, update, id) =>
        log.info(s"Comment by $user on $commitId ($path:$pos):\n$body")
        // TODO do something with commit comments?

    }

    private def handlePR(action: String, pull: PullRequest) = {
      checkMilestone(pull)
      checkLGTM(pull)
      propagateEarlierStati(pull)
      buildCommitsIfNeeded(pull)
      execCommands(pull)
    }

    // TODO: is this necessary? in any case, github refuses non-https links, it seems
    private def commitTargetUrl(url: String) = url.replace("http://", "https://")

    private def commitState(bs: BuildStatus) =
      if (bs.building) CommitStatus.PENDING
      else if (bs.isSuccess) CommitStatus.SUCCESS
      else CommitStatus.FAILURE

    private def commitStatus(jobName: String, bs: BuildStatus): CommitStatus = {
      CommitStatus(commitState(bs),
        context     = Some(jobName),
        description = Some(s"[${bs.number}}] ${bs.result}, ${bs.friendlyDuration}".take(140)),
        target_url  = Some(commitTargetUrl(bs.url)))
    }

    private def combiStatus(state: String, msg: String): CommitStatus =
      CommitStatus(state, context = Some(CommitStatus.COMBINED), description = Some(msg.take(140)))


    private def handleJobState(jobName: String, sha: String, bs: BuildStatus) = {
      def postFailureComment(bs: BuildStatus) =
        for {
          pull    <- githubApi.pullRequest(pr)
          comment <- githubApi.postCommitComment(sha, PullRequestComment(
                           s"Job $jobName failed for ${sha.take(8)}, ${bs.friendlyDuration} (ping @${pull.user.login}) [(results)](${bs.url}):\n"+
                           s"If you suspect the failure was spurious, comment `PLS REBUILD $sha` on PR ${pr} to retry.\n"+
                            "NOTE: New commits are rebuilt automatically as they appear. A forced rebuild is only necessary for transient failures.\n"+
                            "`PLS REBUILD` without a sha will force a rebuild for all commits."))
        } yield comment.body

      val postStatus = for {
        posting <- githubApi.postStatus(sha, commitStatus(jobName, bs))
        _       <- if (bs.building || bs.isSuccess) Future.successful("")
                   else postFailureComment(bs)
      } yield posting

      postStatus onFailure { case e => log.warning(s"handleJobState($jobName, ${bs.number}, $sha) failed: $e") }
      postStatus
    }

    private def jobParams(sha: String): Map[String, String] = Map (
      PARAM_PR        -> pr.toString,
      PARAM_REPO_USER -> config.github.user,
      PARAM_REPO_NAME -> config.github.repo,
      PARAM_REPO_REF  -> sha
    )

    private def launchBuild(sha: String, job: String = config.jenkins.job): Future[String] = {
      val fut = jenkinsApi.buildJob(job, jobParams(sha))
      fut onFailure { case e => log.warning(s"launchBuild($sha, $job) failed: $e") }
      fut
    }

    private def synchBuildStatus(combiCommitStatus: CombiCommitStatus, job: String): Future[String] = {
      val expected = jobParams(combiCommitStatus.sha)

      for {
        mostRecentBuild <- jenkinsApi.buildStatusesForJob(job).map(_.find(_.paramsMatch(expected))) // first == most recent
        jenkinsView = mostRecentBuild.map(bs => (commitState(bs), commitTargetUrl(bs.url)))
        githubView  = combiCommitStatus.statuses.find(_.context == Some(job)).flatMap(cs => cs.target_url.map(url => (cs.state, url)))
        if jenkinsView != githubView
      } yield {
        // the status we found on the PR didn't match what Jenkins told us --> synch
        mostRecentBuild foreach (bs => handleJobState(job, combiCommitStatus.sha, bs))
        s"Updating ${combiCommitStatus.sha} of #$pr from ${combiCommitStatus.statuses.headOption} to $mostRecentBuild."
      }
    }


    private def jobsTodo(combiCommitStatus: CombiCommitStatus, rebuild: Boolean, gatherAllJobs: Boolean = false): List[String] = {
      // We've built this before and we were asked to rebuild. For all jobs that have ended in failure, launch a build.
      if (combiCommitStatus.total_count > 0 && (rebuild || gatherAllJobs)) {
        combiCommitStatus.statuses.groupBy(_.context).collect {
          case (Some(job), stati)
            if job != CommitStatus.COMBINED &&
               // most recent status was a failure (or, weirdly, there were no statuses -- that would be a github bug)
               (stati.headOption.forall(_.failure) || gatherAllJobs) => job
        }.toList
      } else {
        if (combiCommitStatus.total_count == 0 || gatherAllJobs) List(config.jenkins.job) // first time
        else Nil // rebuild wasn't requested, and results were there: we're done
      }
    }

    // for all commits with pending status, or without status entirely, ensure that a jenkins job has been started
    // if `forceRebuild` is specified, jobs before it will be ignored (by job number)
    private def buildCommitsIfNeeded(pull: PullRequest, forceRebuild: Boolean = false, synchOnly: Boolean = false): Future[List[List[String]]] = {
      def fetchCommitStatus(sha: String) = {
        val fetcher = githubApi.commitStatus(sha)
        fetcher.onFailure { case e => log.warning(s"Couldn't get status for ${sha}: $e")}
        fetcher
      }

      for {
        commits <- githubApi.pullRequestCommits(pr)
        results <- Future.sequence(commits map { commit =>
          log.debug(s"Build commit? $commit force=$forceRebuild synch=$synchOnly")
          for {
            combiCs  <- fetchCommitStatus(commit.sha)
            todo      = jobsTodo(combiCs, rebuild = forceRebuild, gatherAllJobs = synchOnly)
            synchRes <- Future.sequence(todo.map(synchBuildStatus(combiCs, _)))
            buildTodo = if (synchOnly) Nil else todo
            buildRes <- Future.sequence(buildTodo.map(launchBuild(combiCs.sha, _))) // using combiCs.sha for consistency with jobsTodo
          } yield buildRes
        })
      } yield results
    }


    // propagate status of commits before the last one over to the last commit's status,
    // so that all statuses are (indirectly) considered by github when coloring the merge button green/red
    private def propagateEarlierStati(pull: PullRequest) = {
      import CommitStatus._
      for {
        commits       <- githubApi.pullRequestCommits(pr)
        earlierStati  <- Future.sequence(commits.init.map(c => githubApi.commitStatus(c.sha)))
        failingCommits = earlierStati.filterNot(_.success) // pending/failure
        posting       <- githubApi.postStatus(commits.last.sha,
          if (failingCommits.isEmpty) {
            // override any prior status in the COMBINED context
            // the last commit's status doesn't matter -- it'll be considered directly by github
            combiStatus(SUCCESS, "All previous commits successful.")
          } else {
            val worstState = if (failingCommits.exists(_.failure)) FAILURE else PENDING
            combiStatus(worstState, s"Found earlier commit(s) marked $worstState: ${failingCommits.map(_.sha.take(6)).mkString(", ")}")
          })
      } yield posting
    }


    def milestoneForBranch(branch: String): Future[Milestone] = for {
      mss <- githubApi.repoMilestones()
    } yield mss.find(ms => Milestone.mergeBranch(ms) == branch).get


    // if there's a milestone with description "Merge to ${pull.base.ref}.", set it as the PR's milestone
    private def checkMilestone(pull: PullRequest) =
      milestoneForBranch(pull.base.ref) foreach { milestone =>
        for {
          issue <- githubApi.issue(pr)
          if issue.milestone.isEmpty
        } yield {
          log.debug(s"Setting milestone to ${milestone.title}")
          githubApi.setMilestone(pr, milestone.number)
        }
      }

    private def hasLabelNamed(name: String) = githubApi.labels(pr).map(_.exists(_.name == name))
    private def checkLGTM(pull: PullRequest) = for {
    // purposefully only at start of line to avoid conditional LGTMs
      hasLGTM <- githubApi.issueComments(pr).map(_.exists(_.body.startsWith("LGTM")))
      hasReviewedLabel <- hasLabelNamed("reviewed")
    } yield { // TODO react to labeled/unlabeled event on webhhook
      if (hasLGTM) { if (!hasReviewedLabel) githubApi.addLabel(pr, List(Label("reviewed"))) }
      else if (hasReviewedLabel) githubApi.deleteLabel(pr, "reviewed")
    }


    private def handleComment(comment: IssueComment): Future[Unit] = {
      implicit val replyWithMemento: String => Unit = msg => githubApi.postIssueComment(pr, IssueComment(mementoFor(comment) + ")\n" + msg))
      comment.body match {
        case REBUILD_SHA(sha) => commandRebuildSha(sha)
        case REBUILD_ALL()    => commandRebuildAll()
        case SYNCH()          => commandSynch()
        case _                => Future {log.debug(s"Unhandled comment: $comment")}
      }
    }

    private final val REBUILD_SHA = """^PLS REBUILD (\w+)""".r.unanchored
    def commandRebuildSha(sha: String)(implicit reply: String => Unit) =
      for (res <- launchBuild(sha)) yield {
        reply(s":cat: Roger! Rebuilding ${sha take 6}. :rotating_light:\n$res")
      }

    private final val REBUILD_ALL = """^PLS REBUILD""".r.unanchored
    def commandRebuildAll()(implicit reply: String => Unit) =
      for {
        pull     <- githubApi.pullRequest(pr)
        buildRes <- buildCommitsIfNeeded(pull, forceRebuild = true)
      } yield {
        reply(s":cat: Roger! Rebuilding all the commits! :rotating_light:\n")
      }

    private final val SYNCH = """^PLS SYNCH""".r.unanchored
    def commandSynch()(implicit reply: String => Unit) =
      for {
        pull     <- githubApi.pullRequest(pr)
        buildRes <- buildCommitsIfNeeded(pull, forceRebuild = false, synchOnly = true)
      } yield {
        reply(":cat: Synchronaising! :pray:")
      }

    final private val IGNORE_NOTE_TO_SELF = "(kitty-note-to-self: ignore "

    private def hasCommand(body: String) = body.startsWith("PLS ")

    // must add a comment that starts with the first element of each returned pair
    private def unprocessedCommands(comments: List[IssueComment]): List[IssueComment] =
      comments filter { c => hasCommand(c.body) && !comments.exists(_.body.startsWith(mementoFor(c))) }

    private def mementoFor(c: IssueComment): String =
      IGNORE_NOTE_TO_SELF + c.id.getOrElse("???")

    def execCommands(pullRequest: PullRequest) = for {
      comments       <- githubApi.issueComments(pr)
      commentResults <- Future.sequence(unprocessedCommands(comments).map(handleComment))
    } yield commentResults
  }
}
