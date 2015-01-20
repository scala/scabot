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

      case JobState(name, _, BuildState(number, phase, parameters, _, result, full_url, consoleLog)) =>
        log.info(s"Job $name [$number]: $phase --> $result")
        handleJobState(name, number, phase, result, parameters, full_url, consoleLog)

      case PullRequestComment(body, user, commitId, path, pos, created, update, id) =>
        log.info(s"Comment by $user on $commitId ($path:$pos):\n$body")
        // TODO do something with commit comments?

    }

    private def handlePR(action: String, pull: PullRequest) = {
      checkMilestone(pull)
      checkLGTM(pull)
      propagateEarlierStati(pull)
      buildCommitsIfNeeded(pull)
      //      execCommands(pull)
    }

    private def handleJobState(context: String, jobNumber: Int, phase: String, result: Option[String], parameters: Map[String, String], target_url: String, consoleLog: Option[String]) = {
      val status = (phase, result) match {
        case ("STARTED", _)       => "pending"
        case (_, Some("SUCCESS")) => "success"
        case _                    => "failure"
      }

      val msg = s"Job $context [$jobNumber] --> $result."
      for {
        sha    <- Future { parameters(PARAM_REPO_REF) }
        status <- githubApi.postStatus(sha, CommitStatus(status, Some(context), Some(msg), Some(target_url)))
      } yield status
    }  onFailure {
      case e => log.info(s"handleJobState($context, $jobNumber, $phase, $result, $parameters) failed: $e");
    }


    private def handleComment(comment: IssueComment) = {
    }


    private def launchBuild(sha: String): Future[String] = {
      val fut = jenkinsApi.buildJob(config.jenkins.job, Map(
        PARAM_PR        -> pr.toString,
        PARAM_REPO_USER -> config.github.user,
        PARAM_REPO_NAME -> config.github.repo,
        PARAM_REPO_REF  -> sha
      ))
      fut onFailure { case e =>  log.info(s"launchBuild($sha) failed: $e") }
      fut
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
      hasLGTM <- githubApi.pullRequestComments(pr).map(_.exists(_.body.startsWith("LGTM")))
      hasReviewedLabel <- hasLabelNamed("reviewed")
    } yield { // TODO react to labeled/unlabeled event on webhhook
      if (hasLGTM) { if (!hasReviewedLabel) githubApi.addLabel(pr, List(Label("reviewed"))) }
      else if (hasReviewedLabel) githubApi.deleteLabel(pr, "reviewed")
    }

    // propagate status of commits before the last one over to the last commit's status,
    // so that all statuses are (indirectly) considered by github when coloring the merge button green/red
    private def propagateEarlierStati(pull: PullRequest) = {
      import CommitStatus._
      for {
        commits       <- githubApi.pullRequestCommits(pr)
        earlierStati  <- Future.sequence(commits.init.map(c => githubApi.commitStatus(c.sha)))
        failingCommits = earlierStati.filterNot(_.success) // pending/failure
      } yield {
        (if (failingCommits.isEmpty) {
          // override any prior status in the COMBINED context
          // the last commit's status doesn't matter -- it'll be considered directly by github
          (SUCCESS, "All previous commits successful.")
        } else {
          val worstState = if (failingCommits.exists(_.failure)) FAILURE else PENDING
          (worstState, s"Found earlier commit(s) marked $worstState: ${failingCommits.map(_.sha.take(6)).mkString(", ")}")
        }) match { case (state, msg) =>
          githubApi.postStatus(commits.last.sha, CommitStatus(state, Some(COMBINED), Some(msg)))
        }
      }
    }

    private def relevantMostRecentBuild(url: Option[String], sha: String, ignoreBuildsBefore: Option[Int]): Future[BuildStatus] = {
      def relevant(bs: BuildStatus) = {
        val expected = Map(
          PARAM_PR        -> pr.toString,
          PARAM_REPO_USER -> config.github.user,
          PARAM_REPO_NAME -> config.github.repo,
          PARAM_REPO_REF  -> sha)

        bs.paramsMatch(expected) && ignoreBuildsBefore.forall(bs.number >= _)
      }

      // both futures either fail or yield the most recent relevant status
      val paramsMatchAtUrl = Future { url.get }.flatMap(url => jenkinsApi.buildStatus(url).filter(relevant))
      val hasMatchingBuild = jenkinsApi.buildStatusesForJob(config.jenkins.job).map(_.find(relevant).get)

      paramsMatchAtUrl fallbackTo hasMatchingBuild
    }

    /** make sure the commit has the expected status (updating it, to reflect what Jenkins told us)
      * if no status found, fail --> we'll launch a build as a "fallback"
      *
      */
    def ensureStatus(sha: String, url: Option[String], ignoreBuildsBefore: Option[Int], synchOnly: Boolean): Future[String] = (for {
      mrb <- relevantMostRecentBuild(url, sha, ignoreBuildsBefore)
    } yield {
      // there was a build that was more recent than the force build command (if any)
      if (url.contains(mrb.url)) "Relevant build found at $url (for ${sha.take(6)})"
      else {
        self ! mrb // the status we found on the PR didn't match what Jenkins told us --> synch while we're at it
        "Synching status for ${sha.take(6)} based on ${mrb.url}."
      }
    }).recover{case _ if synchOnly => "Not building because synchOnly."}


    // for all commits with pending status, or without status entirely, ensure that a jenkins job has been started
    // if `ignoreBuildsBefore` is specified, jobs before it will be ignored (by job number)
    private def buildCommitsIfNeeded(pull: PullRequest, ignoreBuildsBefore: Option[Int] = None, synchOnly: Boolean = false): Future[List[String]] =
      for {
        commits <- githubApi.pullRequestCommits(pr)
        results <- Future.sequence(for(commit <- commits) yield {
          log.debug(s"Build commit? $commit")
          import CommitStatus._

          val fetchCommitStatus = githubApi.commitStatus(commit.sha)
          fetchCommitStatus.onFailure {  case e => log.info(s"Couldn't get status for ${commit.sha}: $e") }

          (for {
            status <- fetchCommitStatus.map { status =>
              if (status.pending || ignoreBuildsBefore.nonEmpty) status
              else throw new NoSuchElementException(s"No need to build: ${commit.sha} is ${status.state}")
            }
            jobUrl = status.statuses.headOption.flatMap(_.target_url)
            buildRes <- ensureStatus(commit.sha, jobUrl, ignoreBuildsBefore, synchOnly) fallbackTo {
              // we couldn't find the expected status --> launch build
              launchBuild(commit.sha)
            }
          } yield buildRes).recover {
            case e: NoSuchElementException => e.getMessage
          }
        })
      } yield results

    // onFailure { case e => log.info(s"Couldn't retrieve list of commits: $e") }

//    def execCommands(pullRequest: PullRequest) = {
//      val IGNORE_NOTE_TO_SELF = "(kitty-note-to-self: ignore "
//      val comments = ghapi.pullrequestcomments(user, repo, pullNum)
//      // must add a comment that starts with the first element of each returned pair
//      def findNewCommands(command: String): List[(String, String)] =
//        comments collect { case c
//          if c.body.contains(command)
//            && !comments.exists(_.body.startsWith(IGNORE_NOTE_TO_SELF+ c.id)) =>
//
//          (IGNORE_NOTE_TO_SELF+ c.id +")\n", c.body)
//        }
//
//      findNewCommands("PLS REBUILD") foreach { case (prefix, body) =>
//        val job = (
//          (for (
//            jobNameLines <- body.split("PLS REBUILD").lastOption;
//            jobName <- jobNameLines.lines.take(1).toList.headOption)
//          yield jobName) getOrElse ""
//          )
//        val trimmed = job.trim
//        val (jobs, shas) =
//          if (trimmed.startsWith("ALL")) (jenkinsJobs, commits.map(_.sha))
//          else if (trimmed.startsWith("/")) trimmed.drop(1).split("@") match { // generated by us for a (spurious) abort
//            case Array(job, sha) => (Set(JenkinsJob(job)), List(ghapi.normalizeSha(user, repo, sha)))
//            case _ => (Set[JenkinsJob](), Nil)
//          }
//          else (jenkinsJobs.filter(j => trimmed.contains(j.name)), commits.map(_.sha))
//
//        ghapi.addPRComment(user, repo, pullNum,
//          prefix +":cat: Roger! Rebuilding "+ jobs.map(_.name).mkString(", ") +" for "+ shas.map(_.take(8)).mkString(", ") +". :rotating_light:\n")
//
//        shas foreach (sha => jobs foreach (j => buildCommit(sha, j, force = true)))
//      }
//
//      findNewCommands("BUILDLOG?") map { case (prefix, body) =>
//        ghapi.addPRComment(user, repo, pullNum, prefix + buildLog(commits))
//      }
//
//      findNewCommands("PLS SYNCH") map { case (prefix, body) =>
//        ghapi.addPRComment(user, repo, pullNum, prefix + ":cat: Synchronaising! :pray:")
//        synch(commits)
//      }
//
//      // delete all commit comments -- don't delete PR comments as they would re-trigger
//      // the commands that caused them originally
//      findNewCommands("NOLITTER!") map { case (prefix, body) =>
//        ghapi.addPRComment(user, repo, pullNum, prefix + ":cat: cleaning up... sorry! :cat:")
//        cleanLitter(pull, commits)
//      }
//
//    }

//    private def jobState() = {
//      if (success) checkSuccess(pull)
//      else needsAttention(pull)
//
//      active.-=((sha, job))
//      forced.-=((sha, job))
//
//    }
  }


}
