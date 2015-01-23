package scabot
package server

import java.util.NoSuchElementException

import akka.actor._
import com.amazonaws.services.dynamodbv2.document.{Item, PrimaryKey}
import com.amazonaws.services.dynamodbv2.model.KeyType

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
 * Created by adriaan on 1/15/15.
 */
trait Actors extends DynamoDb { self: core.Core with core.Configuration with github.GithubApi with jenkins.JenkinsApi =>
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
    def prActor(nb: Int) = child(nb.toString).getOrElse(mkPRActor(nb))

    // ignore PRs owned by the kitteh
    def mkPRActor(nb: Int): ActorRef =
      if (config.github.user != "scala" || nb > 4265) actorOf(Props(new PullRequestActor(nb, config)), nb.toString)
      else actorOf(Props(new NoopPullRequestActor), nb.toString)


    // supports messages of type ProjectMessage
    override def receive: Receive = {
      case Synch =>
        log.info("Synching up! Bleepy-dee-bloop.")

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

  // for migration
  class NoopPullRequestActor extends Actor with ActorLogging {
    override def receive: Actor.Receive = { case _ => log.warning("NOOP ACTOR SAYS HELLO") }
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
        log.info(s"Comment by ${user.getOrElse("???")}:\n$body")
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

    private def handlePR(action: String, pull: PullRequest, synchOnly: Boolean = false) = {
      checkMilestone(pull)
      checkLGTM(pull)
      propagateEarlierStati(pull)
      // don't exec commands when synching, or we'll keep executing the /sync that triggered this handlePR execution
      if (!synchOnly) execCommands(pull)
      buildCommitsIfNeeded(pull, synchOnly = synchOnly)
    }

    // TODO: is this necessary? just to be sure, as it looks like github refuses non-https links
    private def commitTargetUrl(bs: BuildStatus) = bs.url.replace("http://", "https://")

    private def commitState(bs: BuildStatus) =
      if (bs.building || bs.queued) CommitStatusConstants.PENDING
      else if (bs.success) CommitStatusConstants.SUCCESS
      else CommitStatusConstants.FAILURE

    private def commitStatus(jobName: String, bs: BuildStatus): CommitStatus = {
      val advice = if (bs.failed) "Say /rebuild on PR to retry *spurious* failure." else ""
      CommitStatus(commitState(bs),
        context = Some(jobName),
        description = Some(if (bs.queued) bs.status else s"[${bs.number}] ${bs.status}. ${bs.friendlyDuration} $advice".take(140)),
        target_url = Some(commitTargetUrl(bs)))
    }


    // TODO: as we add more analyses to PR validation, update this predicate to single out jenkins jobs
    // NOTE: config.jenkins.job spawns other jobs, which we don't know about here, but still want to retry on /rebuild
    private def contextIsJenkinsJob(context: String) = context != CommitStatusConstants.COMBINED

    private def combiStatus(state: String, msg: String): CommitStatus =
      CommitStatus(state, context = Some(CommitStatusConstants.COMBINED), description = Some(msg.take(140)))


    private def handleJobState(jobName: String, sha: String, bs: BuildStatus) = {
      // not called -- see if we can live with less noise
      def postFailureComment(pull: PullRequest, bs: BuildStatus) =
        (for {
          comments <- githubApi.commitComments(sha)
          header    = s"Job $jobName failed for ${sha.take(8)}, ${bs.friendlyDuration} (ping @${pull.user.login}) [(results)](${bs.url}):\n"
          if !comments.exists(_.body.startsWith(header))
          details = s"If you suspect the failure was spurious, comment `/rebuild $sha` on PR ${pr} to retry.\n"+
                     "NOTE: New commits are rebuilt automatically as they appear. A forced rebuild is only necessary for transient failures.\n"+
                     "`/rebuild` without a sha will force a rebuild for all commits."
          comment <- githubApi.postCommitComment(sha, PullRequestComment(header+details))
        } yield comment.body).recover {
          case _: NoSuchElementException => s"Avoiding double-commenting on $sha for $jobName"
        }

      val newStatus = commitStatus(jobName, bs)
      val postStatus = (for {
        currentStatus <- githubApi.commitStatus(sha).map(_.statuses.filter(_.context == Some(jobName)).headOption)
        if currentStatus != Some(newStatus)
        posting <- githubApi.postStatus(sha, newStatus)
        _       <- Future.successful(log.debug(s"Posted status on $sha for $jobName $bs:\n$posting"))
        pull    <- githubApi.pullRequest(pr)
        _       <- propagateEarlierStati(pull, sha)
//        if !(bs.queued || bs.building || bs.success)
//        _       <- postFailureComment(pull, bs)
      } yield posting).recover {
        case _: NoSuchElementException => s"No need to update status of $sha for context $jobName"
      }

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
      val launcher = for {
        buildRes <- jenkinsApi.buildJob(job, jobParams(sha))
        _        <- Future.successful(log.debug(s"Launched $job for $sha: $buildRes"))
      } yield buildRes

      launcher onFailure { case e => log.warning(s"FAILED launchBuild($sha, $job): $e") }
      launcher
    }

    private def synchBuildStatus(combiCommitStatus: CombiCommitStatus, job: String): Future[String] = {
      val jobStatus    = combiCommitStatus.statuses.find(_.context == Some(job))
      val githubReport = jobStatus.flatMap(cs => cs.target_url.map(url => (cs.state, url)))

      // summarize jenkins's report as the salient parts of a CommitStatus (should match what github reported in combiCommitStatus)
      def summarizeBuildStatus(bs: BuildStatus) = (commitState(bs), commitTargetUrl(bs))
      val expected = jobParams(combiCommitStatus.sha)

      val syncher = (for {
        bss <- jenkinsApi.buildStatusesForJob(job)
        mostRecentBuild <- jenkinsApi.buildStatusesForJob(job).map(_.find(_.paramsMatch(expected))) // first == most recent
        if githubReport != mostRecentBuild.map(summarizeBuildStatus)
      } yield {
        // the status we found on the PR didn't match what Jenkins told us --> synch
        mostRecentBuild foreach (bs => handleJobState(job, combiCommitStatus.sha, bs))
        val msg = s"Updating ${combiCommitStatus.sha} of #$pr from ${combiCommitStatus.statuses.headOption} to $mostRecentBuild."
        log.debug(msg)
        msg
      }) recover { case _: NoSuchElementException => // filtered out
        val msg = s"No need to synch ${combiCommitStatus.sha} of #$pr. Jenkins in synch with: $githubReport."
        log.debug(msg)
        msg
      }

      syncher onFailure { case e => log.error(s"FAILED synchBuildStatus($combiCommitStatus, $job): $e") } // should never happen with the recover
      syncher
    }

    // result is a subset of (config.jenkins.job and the contexts found in combiCommitStatus.statuses that are jenkins jobs)
    // if not rebuilding or gathering all jobs, this subset is either empty or the main job (if no statuses were found for it)
    // unless gatherAllJobs, the result only includes jobs whose most recent status was a failure
    private def jobsTodo(combiCommitStatus: CombiCommitStatus, rebuild: Boolean, gatherAllJobs: Boolean = false): List[String] = {
      val mainJob     = config.jenkins.job
      val statusByJob = Map(mainJob -> Nil) ++: combiCommitStatus.statuses.groupBy(_.context).collect { case (Some(job), stati) if contextIsJenkinsJob(job) => (job, stati) }

      def shouldBuild(stati: List[CommitStatus]): Boolean = gatherAllJobs || stati.headOption.forall(_.failure)

      // We've built this before and we were asked to rebuild. For all jobs that have ended in failure, launch a build.
      val jobs =
        if (rebuild || gatherAllJobs) statusByJob.collect { case (job, stati) if shouldBuild(stati) => job }.toList
        else if (statusByJob(mainJob).isEmpty) List(mainJob)
        else Nil

      val jobMsg =
        if (jobs.isEmpty) "No need to build"
        else s"Found jobs ${jobs.mkString(", ")} TODO"

      log.debug(s"$jobMsg for ${combiCommitStatus.sha.take(6)} (rebuild=$rebuild, all=$gatherAllJobs), based on ${combiCommitStatus.total_count} statuses:\n${combiCommitStatus.statuses.groupBy(_.context)}")

      jobs
    }

    // determine jobs needed to be built based on the commit's status, synching github's view with build statuses reported by jenkins
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
            jobs      = jobsTodo(combiCs, rebuild = forceRebuild, gatherAllJobs = synchOnly)
            buildRes <- Future.sequence(
              if (synchOnly) jobs.map(synchBuildStatus(combiCs, _))
              else jobs.map(launchBuild(combiCs.sha, _)))
          } yield buildRes
        })
      } yield results
    }


    // propagate status of commits before the last one over to the last commit's status,
    // so that all statuses are (indirectly) considered by github when coloring the merge button green/red
    private def propagateEarlierStati(pull: PullRequest, causeSha: String = ""): Future[List[CommitStatus]] = {
      import CommitStatusConstants._
      (for {
        commits       <- githubApi.pullRequestCommits(pr)
        if commits.nonEmpty && causeSha != commits.last.sha // ignore if caused by an update to the last commit
        earlierStati  <- Future.sequence(commits.init.map(c => githubApi.commitStatus(c.sha)))
        failingCommits = earlierStati.filterNot(_.success) // pending/failure
        posting       <- Future.sequence{
          if (earlierStati.isEmpty) Nil
          else if (failingCommits.isEmpty) {
            // override any prior status in the COMBINED context
            // the last commit's status doesn't matter -- it'll be considered directly by github
            List(githubApi.postStatus(commits.last.sha, combiStatus(SUCCESS, "All previous commits successful.")))
          } else {
            val worstState = if (failingCommits.exists(_.failure)) FAILURE else PENDING
            val updateObsoleteCombinedStati: List[Future[CommitStatus]] = earlierStati.filter(_.statuses.exists(st => st.combined && !st.success)).map { st =>
              githubApi.postStatus(st.sha, combiStatus(SUCCESS, "Nothing to see here -- no longer last commit."))
            }
            val lastDesc = s"Found earlier commit(s) marked $worstState: ${failingCommits.map(_.sha.take(6)).mkString(", ")}"
            val updateLast: Future[CommitStatus] = githubApi.postStatus(commits.last.sha, combiStatus(worstState, lastDesc))
            updateLast :: updateObsoleteCombinedStati
          }
        }
      } yield posting).recover { case _: NoSuchElementException => Nil }
    }


    def milestoneForBranch(branch: String): Future[Milestone] = for {
      mss <- githubApi.repoMilestones()
    } yield mss.find(_.mergeBranch == branch).get


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


    
    private def execCommands(pullRequest: PullRequest) = for {
      comments       <- githubApi.issueComments(pr)
      commentResults <- Future.sequence(comments.map(handleComment))
    } yield commentResults

    private lazy val seenCommandsTable = {
      val ddc    = new DynoDbClient
      val _table = new ddc.DynoDbTable("scabot-seen-commands")
      if (!_table.exists) _table.create(List(("Id", KeyType.HASH)), List(("Id", "N")))
      _table
    }

    private def seenCommentId(id: Long): Future[Boolean] =
      seenCommandsTable.get(new PrimaryKey("Id", id)).map(_.nonEmpty)

    private def sawCommentId(id: Long): Future[String] =
      seenCommandsTable.put((new Item).withPrimaryKey("Id", id)).map(_.getPutItemResult.toString)

    private def hasCommand(body: String) = body.startsWith("/")

    private def handleComment(comment: IssueComment): Future[Any] =
     if (!hasCommand(comment.body)) {
       log.debug(s"No command in $comment")
       Future.successful("No Command found")
     } else {
       (for {
         id   <- Future { comment.id.get } // the get will fail the future if somehow id is empty
         seen <- seenCommentId(id)
         if !seen
         _    <- sawCommentId(id)
         res  <- {
           log.debug(s"Executing command for ${comment.body}")
           comment.body match {
             case REBUILD_SHA(sha) => commandRebuildSha(sha)
             case REBUILD_ALL()    => commandRebuildAll()
             case SYNCH()          => commandSynch()
           }
         }
       } yield res).recover {
         case _: NoSuchElementException =>
           val msg = s"Already handled $comment"
           log.debug(msg)
           msg
         case _: MatchError =>
           val msg = s"Unknown command in $comment"
           log.debug(msg)
           msg
       }
     }

    private final val REBUILD_SHA = """^/rebuild (\w+)""".r.unanchored
    def commandRebuildSha(sha: String) = launchBuild(sha)

    private final val REBUILD_ALL = """^/rebuild""".r.unanchored
    def commandRebuildAll() =
      for {
        pull     <- githubApi.pullRequest(pr)
        buildRes <- buildCommitsIfNeeded(pull, forceRebuild = true)
      } yield buildRes

    private final val SYNCH = """^/sync""".r.unanchored
    def commandSynch() =
      for {
        pull     <- githubApi.pullRequest(pr)
        synchRes <- handlePR("synchronize", pull, synchOnly = true)
      } yield synchRes
  }
}
