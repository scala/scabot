package scabot
package server

import akka.actor._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

/**
 * Created by adriaan on 1/15/15.
 */
trait Actors {
  self: core.Core with core.Configuration with github.GithubApi with jenkins.JenkinsApi =>

  def startActors() = {
    githubActor ! GithubActor.StartProjectActors(configs)
  }

  lazy val githubActor = system.actorOf(Props(new GithubActor), "github")

  object GithubActor {
    case class StartProjectActors(configs: Map[String, Config])
  }

  import GithubActor._

  class GithubActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case StartProjectActors(configs)   =>
        configs map { case (name, config) =>
          context.actorOf(Props(new ProjectActor(config)), s"${config.github.user}-${config.github.repo}")
        } foreach {
          _ ! Synch
        }
      case ProjectEvent(user, repo, msg) =>
        context.actorSelection(s"$user-$repo") ! msg
    }
  }

  case object Synch extends ProjectMessage

  class ProjectActor(config: Config) extends Actor with ActorLogging {
    lazy val githubApi = new GithubConnection(host = config.github.host, user = config.github.user, repo = config.github.repo, token = config.github.token)

    import context._

    // find or create actor responsible for PR #`nb`
    def prActor(nb: Int) = child(nb.toString).getOrElse(actorOf(Props(new PullRequestActor(nb, config)), nb.toString))

    // supports messages of type ProjectMessage
    override def receive: Receive = {
      case Synch =>
        githubApi.pullRequests.foreach { prs =>
          prs.foreach { pr => prActor(pr.number) ! PullRequestEvent("synchronize", pr.number, pr)}
        }
        context.system.scheduler.scheduleOnce(30 minutes, self, Synch) // synch every once in a while (not often, since we have the webhook events in principle)

      case ev@PullRequestEvent(action, nb, pull_request) =>
        prActor(nb) ! ev

      case PullRequestReviewCommentEvent("created", pull_request, comment, _) =>
        prActor(pull_request.number) ! comment

      case IssueCommentEvent("created", issue, comment, _) =>
        prActor(issue.number) ! comment

      case js@JobState(_, _, bs) =>
        for {
          prParam <- Try(bs.parameters(PARAM_PR)) // we only care about jobs we started, and which thus have this parameter (when restarted manually, they should be carried forward automatically)
          prNum <- Try(prParam.toInt)
        } prActor(prNum) ! js

    }
  }


  class PullRequestActor(pr: Int, config: Config) extends Actor with ActorLogging {
    lazy val githubApi = new GithubConnection(host = config.github.host, user = config.github.user, repo = config.github.repo, token = config.github.token)
    lazy val jenkinsApi = new JenkinsConnection(host = config.jenkins.host, user = config.jenkins.user, token = config.jenkins.token)

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

        handleJobState(name, number, phase, result, parameters, full_url)

      case PullRequestComment(body, user, commitId, path, pos, created, update, id) =>
        log.info(s"Comment by $user on $commitId ($path:$pos):\n$body")
        // TODO do something with commit comments?

    }

    private def handlePR(action: String, pull: PullRequest) = {
      checkMilestone(pull)
      checkLGTM(pull)
      combineSuccesses(pull)
      buildCommitsIfNeeded(pull) // TODO: distinguish synchronize/open action
      //      execCommands(pull)
    }

    private def handleJobState(context: String, jobNumber: Int, phase: String, result: Option[String], parameters: Map[String, String], target_url: String) = {
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


    private def launchBuild(sha: String) =
      jenkinsApi.buildJob(config.jenkins.job, Map(
        PARAM_PR -> pr.toString,
        PARAM_REPO_USER -> config.github.user,
        PARAM_REPO_NAME -> config.github.repo,
        PARAM_REPO_REF -> sha
      )) onFailure {
        case e => log.info(s"launchBuild($sha) failed: $e");
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
    } yield {
      if (hasLGTM) { if (!hasReviewedLabel) githubApi.addLabel(pr, List(Label("reviewed"))) }
      else if (hasReviewedLabel) githubApi.deleteLabel(pr, "reviewed")
    }

    // add failing status in context "combined" if commits before the last one failed (so that the merge button is green iff all commits are successes)
    private def combineSuccesses(pull: PullRequest) = for {
      commits       <- githubApi.pullRequestCommits(pr)
      lastStatus    <- githubApi.commitStatus(commits.last.sha)
      earlierStati  <- Future.sequence(commits.init.map(c => githubApi.commitStatus(c.sha)))
      failingCommits = earlierStati.collect{ case c if c.state != "success" => (c.sha, c.state) }
    } yield {
      if (failingCommits.isEmpty) {
        if (lastStatus.state != "success")
          githubApi.postStatus(commits.last.sha, CommitStatus("success", Some("combined"), Some("All previous commits successful.")))
      } else if (lastStatus.state == "success") {
        val worstState = if (failingCommits.map(_._2).contains("failure")) "failure" else "pending"
        githubApi.postStatus(commits.last.sha, CommitStatus(worstState, Some("combined"), Some(failingCommits.mkString)))
      }
    }

    // for all commits with pending status, or without status entirely, ensure that a jenkins job has been started
    private def buildCommitsIfNeeded(pull: PullRequest) =
      (for {
        commits <- githubApi.pullRequestCommits(pr)
      } yield {
        for(commit <- commits) yield {
          log.info(s"Build commit? $commit")

          for {
            status <- githubApi.commitStatus(commit.sha)
            _ = log.info(s"Status: $status")
            if status.state == "pending"
          } yield {
            if (status.total_count == 0 || status.statuses.headOption.flatMap(_.target_url).isEmpty) launchBuild(commit.sha)
            else jenkinsApi.buildStatus(status.statuses.head.target_url.get).onFailure{ case _ => launchBuild(commit.sha) }
          }
        }
      }) onFailure {
        case e => log.info(s"buildCommitsIfNeeded failed: $e");
      }

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
