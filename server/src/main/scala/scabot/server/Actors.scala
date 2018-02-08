package scabot
package server

import java.util.NoSuchElementException

import akka.actor._
import akka.event.LoggingAdapter
import com.amazonaws.services.dynamodbv2.document.{Item, PrimaryKey}
import com.amazonaws.services.dynamodbv2.model.KeyType
import scabot.amazon.DynamoDb
import scabot.core.BaseRef

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

/**
 * Created by adriaan on 1/15/15.
 */
trait Actors extends github.GithubApi with jenkins.JenkinsApi with lightbend.LightbendApi with DynamoDb with core.Util {
  def system: ActorSystem

  private lazy val githubActor = system.actorOf(Props(new GithubActor), "github")

  // project actors are supervised by the github actor
  // pull request actors are supervised by their project actor
  private def projectActorName(user: String, repo: String) = s"$user-$repo"

  def broadcast(user: String, repo: String)(msg: ProjectMessage) =
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
        log.info(s"Reading ${configs.size} configs.")
        configs map { case (name, config) =>
          context.actorOf(Props(new ProjectActor(config)), projectActorName(config.github.user, config.github.repo))
        } foreach { _ ! Synch }
    }
  }

  case object Synch extends ProjectMessage

  case class JenkinsJobResult(name: String, status: BuildStatus) extends PRMessage {
    lazy val sha = status.parameters(PARAM_REPO_REF)
  }

  // represents a github project at github.com/${config.github.user}/${config.github.repo}
  class ProjectActor(val config: Config) extends Actor with ActorLogging with Building {
    lazy val githubApi  = new GithubConnection(config.github)
    lazy val jenkinsApi = new JenkinsConnection(config.jenkins)
    private val REFS_HEADS = "refs/heads/"

    import context._

    // find or create actor responsible for PR #`nb`
    def prActor(nb: Int) = child(nb.toString).getOrElse(actorOf(Props(new PullRequestActor(nb, config)), nb.toString))

    def monitored(pullRequest: PullRequest) = {
      val monitor = config.github.branches(pullRequest.base.ref)
      if (!monitor) log.warning(s"Not monitoring #${pullRequest.number} because ${pullRequest.base.ref} not in ${config.github.branches}.")
      monitor
    }

    // supports messages of type ProjectMessage
    override def receive: Receive = {
      case Synch =>
        log.info("Synching up! Bleepy-dee-bloop.")

        githubApi.pullRequests.foreach { prs =>
          prs.filter(monitored).foreach { pr => prActor(pr.number) ! PullRequestEvent("synchronize", pr.number, pr)}
        }
        // synch every once in a while, just in case we missed a webhook event somehow
        // TODO make timeout configurable
        context.system.scheduler.scheduleOnce(30.minutes, self, Synch)

      case ev@PullRequestEvent(_, nb, pull_request) if monitored(pull_request) =>
        prActor(nb) ! ev

      case PullRequestReviewCommentEvent("created", pull_request, comment, _) if monitored(pull_request) =>
        prActor(pull_request.number) ! comment

      case IssueCommentEvent("created", issue, comment, _) =>
        prActor(issue.number) ! comment

      case PushEvent(ref, commits, _) if ref startsWith REFS_HEADS =>
        // only build for master-like branches (we always have milestones that mention this ref for those)
        val branch = ref.drop(REFS_HEADS.length)
        for(_ <- milestoneForBranch(branch))
          buildPushedCommits(new BaseRef(branch), commits)

      // there are two cases, unfortunately:
      //   - PARAM_PR's value is an integer ==> assumed to be PR number,
      //   - otherwise, it's assumed to be the branch name of a job launched for a pushed commit (e.g., a merge or a direct push)
      // TODO: report failure
      case js@JobState(name, _, BuildState(number, _, _, _, _, _, _)) =>
        // fetch the state from jenkins -- the webhook doesn't pass in result correctly (???)
        for {
          bs      <- jenkinsApi.buildStatus(name, number)
          prParam <- Future { bs.parameters(PARAM_PR) }
        } {
          log.info(s"Build status for $name #$number: $bs")
          val jobRes = JenkinsJobResult(name, bs)

          try prActor(prParam.toInt) ! jobRes
          catch { case _: NumberFormatException =>
            // check prParam is a branch name we recognize
            for(_ <- milestoneForBranch(prParam))
              postStatus(new BaseRef(prParam), jobRes)
          }
        }
    }

    // determine jobs needed to be built based on the commit's status, synching github's view with build statuses reported by jenkins
    private def buildPushedCommits(baseRef: BaseRef, commits: List[CommitInfo]): Future[List[List[String]]] = {
      val lastSha = commits.last.id.getOrElse("???") // the merge commit, typically
      Future.sequence(commits map { commit =>
        for {
          combiCs  <- fetchCommitStatus(commit.id.get)
          buildRes <- {
            val params = jobParams(baseRef.name, combiCs.sha, combiCs.sha == lastSha)
            Future.sequence(jobsTodo(baseRef, combiCs, rebuild = false).map(launchBuild(combiCs.sha, baseRef, params)(_)))
          }
        } yield buildRes
      })
    }
  }

  trait Building {
    def config: Config
    def githubApi: GithubConnection
    def jenkinsApi: JenkinsConnection
    def log: LoggingAdapter

    def milestoneForBranch(branch: String): Future[Milestone] = for {
      mss <- githubApi.repoMilestones()
      ms <- Future {
        val msOpt = mss.find(_.mergeBranch == Some(branch))
        log.debug(s"Looking for milestone for $branch: $msOpt")
        msOpt.get
      }
    } yield ms

    def fetchCommitStatus(sha: String) = {
      val fetcher = githubApi.commitStatus(sha)
      fetcher.onFailure { case e => log.warning(s"Couldn't get status for ${sha}: $e")}
      fetcher
    }

    implicit object jcl extends JobContextLense {
      // e.g., scala-2.11.x- for PR targeting 2.11.x of s"$user/scala" (for any user)
      def prefix(baseRef: BaseRef) = s"${config.github.repo}-${baseRef.name}-"

      // TODO: as we add more analyses to PR validation, update this predicate to single out jenkins jobs
      // NOTE: config.jenkins.job spawns other jobs, which we don't know about here, but still want to retry on /rebuild
      def contextForJob(job: String, baseRef: BaseRef): Option[String] =
        Some(job.replace(prefix(baseRef), "")) // TODO: should only replace *prefix*, not just anywhere in string

      def jobForContext(context: String, baseRef: BaseRef): Option[String] =
        if (CommitStatusConstants.jenkinsContext(context)) Some(prefix(baseRef) + context)
        else None
    }

    def mainValidationJob(baseRef: BaseRef) = jcl.prefix(baseRef) + config.jenkins.jobSuffix

    // TODO: is this necessary? just to be sure, as it looks like github refuses non-https links
    def urlForBuild(bs: BuildStatus) = Some(bs.url.map(_.replace("http://", "https://")).getOrElse(""))

    def stateForBuild(bs: BuildStatus) =
      if (bs.building || bs.queued) CommitStatusConstants.PENDING
      else if (bs.success) CommitStatusConstants.SUCCESS
      else CommitStatusConstants.FAILURE

    def contextForJob(jobName: String, baseRef: BaseRef): Option[String] = implicitly[JobContextLense].contextForJob(jobName, baseRef)

    def commitStatus(jobName: String, bs: BuildStatus, baseRef: BaseRef): CommitStatus = {
      val advice = if (bs.failed) " Say /rebuild on PR to retry *spurious* failure." else ""
      commitStatusForContext(contextForJob(jobName, baseRef), bs, advice)
    }

    def commitStatusForContext(context: Option[String], bs: BuildStatus, advice: String): CommitStatus = {
      CommitStatus(stateForBuild(bs), context,
        description = Some((bs.toString + advice) take 140),
        target_url = urlForBuild(bs))
    }

    def combiStatus(state: String, msg: String): CommitStatus =
      CommitStatus(state, Some(CommitStatusConstants.COMBINED), description = Some(msg.take(140)))

    def claStatus(signed: Option[Boolean], user: String, claKind: String, checkUrl: String, signUrl: String): CommitStatus = {
      val (state, msg, url) = signed match {
        case None        => (CommitStatusConstants.PENDING, s"Checking whether @$user signed the $claKind CLA.", checkUrl)
        case Some(true)  => (CommitStatusConstants.SUCCESS, s"@$user signed the $claKind CLA. Thanks!", checkUrl)
        case Some(false) => (CommitStatusConstants.FAILURE, s"@$user, please sign the $claKind CLA by clicking on 'Details' -->", signUrl)
      }
      CommitStatus(state, Some(CommitStatusConstants.CLA), description = Some(msg.take(140)), target_url = Some(url))
    }

    type Parameters = Map[String, String]
    def jobParams(pr: String, sha: String, lastCommit: Boolean): Parameters =
      Map(PARAM_REPO_USER -> config.github.user,
          PARAM_REPO_NAME -> config.github.repo,
          PARAM_PR        -> pr,  // pr number or branch name (for pushed commit)
          PARAM_REPO_REF  -> sha) ++ (
        if (lastCommit) Map(PARAM_LAST -> "1")
        else Map.empty
      )

    // result is a subset of (config.jenkins.job and the contexts found in combiCommitStatus.statuses that are jenkins jobs)
    // if not rebuilding or gathering all jobs, this subset is either empty or the main job (if no statuses were found for it)
    // unless gatherAllJobs, the result only includes jobs whose most recent status was a failure
    def jobsTodo(baseRef: BaseRef, combiCommitStatus: CombiCommitStatus, rebuild: Boolean): List[String] = {
      // TODO: filter out aborted stati?
      // TODO: for pending jobs, check that they are indeed pending!
      def considerStati(stati: List[CommitStatus]): Boolean =
        if (rebuild) stati.headOption.forall(_.failure) else stati.isEmpty

      val mainJobForPull = mainValidationJob(baseRef)
      val shouldConsider = Map(mainJobForPull -> true) ++: combiCommitStatus.statuses.groupBy(_.jobName(baseRef)).collect {
        case (Some(job), stati) => (job, considerStati(stati))
      }

      log.debug(s"shouldConsider for ${combiCommitStatus.sha.take(6)} (rebuild=$rebuild, consider main job: ${shouldConsider.get(mainJobForPull)}): $shouldConsider")

      val allToConsider = shouldConsider.collect{case (job, true) => job}

      // We've built this before and we were asked to rebuild. For all jobs that have ended in failure, launch a build.
      // TODO: once we support overriding main job with results of its downstream jobs,
      //       on rebuild, we should yield only the downstream jobs, overriding the main job once they finish
      // lazy val nonMainToBuild = allToConsider.toSet -  mainValidationJob

      val jobs =
      //        if (rebuild && nonMainToBuild.nonEmpty) nonMainToBuild.toList
        if (shouldConsider(mainJobForPull)) List(mainJobForPull)
        else Nil

      val jobMsg =
        if (jobs.isEmpty) "No need to build"
        else s"Found jobs ${jobs.mkString(", ")} TODO"

      log.debug(s"$jobMsg for ${combiCommitStatus.sha.take(6)} (rebuild=$rebuild), based on ${combiCommitStatus.total_count} statuses:\n${combiCommitStatus.statuses.groupBy(_.jobName(baseRef))}")

      jobs
    }


    def launchBuild(sha: String, baseRef: BaseRef, params: Parameters)(job: String = mainValidationJob(baseRef)): Future[String] = {
      val status = commitStatus(job, new QueuedBuildStatus(params, None), baseRef)

      val launcher = for {
        posting  <- githubApi.postStatus(sha, status)
        buildRes <- jenkinsApi.buildJob(job, params)
        _        <- Future.successful(log.info(s"Launched $job for $sha: $buildRes"))
      } yield buildRes

      launcher onFailure { case e => log.warning(s"FAILED launchBuild($job, $baseRef, $sha, $params): $e") }
      launcher
    }


    def postStatus(baseRef: BaseRef, jenkinsJobResult: JenkinsJobResult): Future[String] = {
      import jenkinsJobResult._
      (for {
        currentStatus <- githubApi.commitStatus(sha).map(_.statuses.filter(_.forJob(name, baseRef)).headOption)
        newStatus      = commitStatus(name, status, baseRef)
        _             <- Future.successful(log.info(s"New status (new? ${currentStatus != Some(newStatus)}) for $sha: $newStatus old: $currentStatus"))
        if currentStatus != Some(newStatus)
        posting       <- githubApi.postStatus(sha, newStatus)
        _             <- Future.successful(log.info(s"Posted status on $sha for $name $status:\n$posting"))
      } yield posting.toString).recover {
        case _: NoSuchElementException => s"No need to update status of $sha for context $name"
      }
    }
  }

  // for migration
  class NoopPullRequestActor extends Actor with ActorLogging {
    override def receive: Actor.Receive = { case _ => log.warning("NOOP ACTOR SAYS HELLO") }
  }

  class PullRequestActor(pr: Int, val config: Config) extends Actor with ActorLogging with Building {
    lazy val githubApi   = new GithubConnection(config.github)
    lazy val jenkinsApi  = new JenkinsConnection(config.jenkins)
    lazy val lightbendApi = new LightbendConnection()

    def baseRef(pull: PullRequest): BaseRef = new BaseRef(pull.base.ref)

    private def pull               = githubApi.pullRequest(pr)
    private def pullBranch         = pull.map(baseRef(_))
    private def pullRequestCommits = githubApi.pullRequestCommits(pr)
    private def lastSha            = pullRequestCommits map (_.last.sha)
    private def issueComments      = githubApi.issueComments(pr)

    def jobParams(sha: String, lastCommit: Boolean): Parameters = jobParams(pr.toString, sha, lastCommit)

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

      case jenkinsJobResult@JenkinsJobResult(name, bs) =>
        log.info(s"Job state for $name [${bs.number.getOrElse("?")}] @${jenkinsJobResult.sha.take(6)}: ${bs.status} at ${bs.url}") // result is not passed in correctly?
        val poster =
          for {
            baseRef <- pullBranch
            postRes <- postStatus(baseRef, jenkinsJobResult)
            pull    <- pull
            propRes <- propagateEarlierStati(pull, jenkinsJobResult.sha)
          //        if !(bs.queued || bs.building || bs.success)
          //        _       <- postFailureComment(pull, bs)
          } yield propRes

        poster onFailure { case e => log.warning(s"handleJobState($jenkinsJobResult) failed: $e") }

      case PullRequestComment(body, user, commitId, path, pos, created, update, id) =>
        log.info(s"Comment by $user on $commitId ($path:$pos):\n$body")
        // TODO do something with commit comments?

    }

    // requires pull.number == pr
    private def handlePR(action: String, pull: PullRequest, synchOnly: Boolean = false) = {
      if (config.github.checkCLA) checkCLA(pull)
      checkMilestone(pull)
      checkLGTM(pull)
      propagateEarlierStati(pull)
      // don't exec commands when synching, or we'll keep executing the /sync that triggered this handlePR execution
      if (!synchOnly) execCommands(pull)
      buildCommitsIfNeeded(baseRef(pull), synchOnly = synchOnly, lastOnly = lastOnly(pull.title))
    }


//    // not called -- see if we can live with less noise
//    def postFailureComment(pull: PullRequest, bs: BuildStatus) =
//      (for {
//        comments <- githubApi.commitComments(sha)
//        header    = s"Job $jobName failed for ${sha.take(8)}, ${bs.friendlyDuration} (ping @${pull.user.login}) [(results)](${bs.url}):\n"
//        if !comments.exists(_.body.startsWith(header))
//        details = s"If you suspect the failure was spurious, comment `/rebuild $sha` on PR ${pr} to retry.\n"+
//          "NOTE: New commits are rebuilt automatically as they appear. A forced rebuild is only necessary for transient failures.\n"+
//          "`/rebuild` without a sha will force a rebuild for all commits."
//        comment <- githubApi.postCommitComment(sha, PullRequestComment(header+details))
//      } yield comment.body).recover {
//        case _: NoSuchElementException => s"Avoiding double-commenting on $sha for $jobName"
//      }


    // synch contexts assumed to correspond to jenkins jobs with the most recent result of the corresponding build of the jenkins job specified by the context
    private def synchBuildStatuses(expected: Parameters, baseRef: BaseRef, combiCommitStatus: CombiCommitStatus): Future[List[String]] = {
      case class GitHubReport(state: String, url: Option[String])
      def toReport(bs: BuildStatus) = GitHubReport(stateForBuild(bs), urlForBuild(bs))

      def checkLinked(url: String): Future[BuildStatus] = for {
        linkedBuild <- jenkinsApi.buildStatus(url)
      } yield linkedBuild

      def checkMostRecent(job: String): Future[BuildStatus] = for {
      // summarize jenkins's report as the salient parts of a CommitStatus (should match what github reported in combiCommitStatus)
        bss <- jenkinsApi.buildStatusesForJob(job)
        // don't bombard poor jenkins, find in sequence (usually, the first try is successful)
        mostRecentBuild <- findFirstSequentially(bss)(_.paramsMatch(expected)) //  first == most recent
      } yield mostRecentBuild

//      // the status we found on the PR didn't match what Jenkins told us --> synch
//      def updateStatus(job: String, bs: BuildStatus) = for {
//        res <- handleJobState(job, combiCommitStatus.sha, bs)
//      } yield {
//        val msg = s"Updating ${combiCommitStatus.sha} of #$pr from ${combiCommitStatus.statuses.headOption} to $bs."
//        log.debug(msg)
//        msg
//      }

      val githubReports = combiCommitStatus.byContext.collect {
        case (Some(context), mostRecentStatus :: _) if CommitStatusConstants.jenkinsContext(context) =>
          (context, GitHubReport(mostRecentStatus.state, mostRecentStatus.target_url))
      }

      def synchLinked(context: String, report: GitHubReport) = for {
        url <- Future { report.url.get }
        bs  <- checkLinked(url)
        if bs.paramsMatch(expected)
      } yield
        if (toReport(bs) == report) None
        else Some(commitStatusForContext(Some(context), bs, ""))

      def synchMostRecent(context: String, report: GitHubReport) = for {
        job  <- Future { jcl.jobForContext(context, baseRef).get }
        bs   <- checkMostRecent(job)
      } yield
        if (toReport(bs) == report) None
        else Some(commitStatus(job, bs, baseRef))


      val syncher = Future.sequence(githubReports.toList.map { case (context, report) =>
        (for {
          cs <-
            synchMostRecent(context, report) recoverWith { case _: spray.httpx.UnsuccessfulResponseException | _ : NoSuchElementException =>
              synchLinked(context, report) recover { case _: spray.httpx.UnsuccessfulResponseException | _ : NoSuchElementException =>
                  Some(CommitStatus(CommitStatusConstants.FAILURE, Some(context),
                    description = Some("No corresponding job found on Jenkins. Failed to launch? Try /rebuild"),
                    target_url = report.url))
              }}
          res <- githubApi.postStatus(combiCommitStatus.sha, cs.get)
        } yield res.toString) recover { case _ : NoSuchElementException => "No need to synch." }
      })

      syncher onFailure { case e => log.error(s"FAILED synchBuildStatuses($combiCommitStatus): $e") } // should never happen with the recover
      syncher
    }

    // /nothingtoseehere
    private def overrideFailures(combiCommitStatus: CombiCommitStatus): Future[List[CommitStatus]] =
      Future.sequence(combiCommitStatus.byContext.collect {
        case (Some(context), mostRecentStatus :: _) if !mostRecentStatus.success =>
          CommitStatus(CommitStatusConstants.SUCCESS, Some(context), description = Some("Failure overridden. Nothing to see here."), target_url = mostRecentStatus.target_url)
      }.toList.map(githubApi.postStatus(combiCommitStatus.sha, _)))



    private def lastOnly(pullTitle: String) = config.github.lastCommitOnly || pullTitle.contains("[ci: last-only]") // only test last commit when requested in PR's title (e.g., for large PRs)

    // determine jobs needed to be built based on the commit's status, synching github's view with build statuses reported by jenkins
    private def buildCommitsIfNeeded(baseRef: BaseRef, forceRebuild: Boolean = false, synchOnly: Boolean = false, lastOnly: Boolean = false): Future[List[List[String]]] = {
      for {
        commits <- pullRequestCommits
        _ <- Future { log.debug(s"buildCommitsIfNeeded? ${baseRef.name} ${commits.map(_.sha.take(6))} force=$forceRebuild synch=$synchOnly") }
        lastSha  = commits.last.sha // safe to assume commits of a pr is nonEmpty
        results <- Future.sequence(commits map { commit =>
          log.debug(s"Build commit? $commit ")
          for {
            combiCs  <- fetchCommitStatus(commit.sha)
            buildRes <- {
              val params = jobParams(combiCs.sha, combiCs.sha == lastSha)
              if (synchOnly) synchBuildStatuses(params, baseRef, combiCs)
              else if (lastOnly && combiCs.sha != lastSha) Future.successful(List(s"Skipped ${combiCs.sha} on request"))
              else Future.sequence(jobsTodo(baseRef, combiCs, rebuild = forceRebuild).map(launchBuild(combiCs.sha, baseRef, params)(_)))
            }
          } yield buildRes
        })
      } yield results
    }

    // propagate status of commits before the last one over to the last commit's status,
    // so that all statuses are (indirectly) considered by github when coloring the merge button green/red
    private def propagateEarlierStati(pull: PullRequest, causeSha: String = ""): Future[List[CommitStatus]] = {
      import CommitStatusConstants._

      def postLast(lastSha: String, desc: String, state: String, lastStss: List[CommitStatus]) =
        for { _ <- Future.successful(())
          if ! lastStss.exists(st => st.state == state && st.description == Some(desc))
          res <- githubApi.postStatus(lastSha, combiStatus(state, desc)) } yield res

      def outdated(earlier: List[CombiCommitStatus]) = earlier.filter(_.statuses.exists(st => st.combined && !st.success))
      def nothingToSee(st: CombiCommitStatus) = githubApi.postStatus(st.sha, combiStatus(SUCCESS, "Nothing to see here -- no longer last commit."))

      def combine(earlierStati: List[CombiCommitStatus], lastSha: String, lastStss: List[CommitStatus]) = {
        val failingCommits = earlierStati.filterNot(_.success)
        val worst = if (failingCommits.exists(_.failure)) FAILURE else if (failingCommits.isEmpty) SUCCESS else PENDING

        if (worst == SUCCESS) postLast(lastSha, "All previous commits successful.", worst, lastStss).map(List(_))
        else for {
          last <- postLast(lastSha, s"Found earlier commit(s) marked $worst: ${failingCommits.map(_.sha.take(6)).mkString(", ")}", worst, lastStss)
          earlier <- Future.sequence(outdated(earlierStati) map nothingToSee) // we know this status is not there
        } yield last :: earlier
      }

      if (lastOnly(pull.title)) Future.successful(Nil)
      else (for {
        commits       <- pullRequestCommits

        lastSha = commits.lastOption.map(_.sha).getOrElse("")
        if commits.nonEmpty && commits.tail.nonEmpty && causeSha != lastSha // ignore if caused by an update to the last commit

        earlierStati  <- Future.sequence(commits.init.map(c => githubApi.commitStatus(c.sha)))

        lastStss      <- githubApi.commitStatus(lastSha).map(_.statuses)

        posting       <- combine(earlierStati, lastSha, lastStss)

      } yield posting).recover { case _: NoSuchElementException => Nil }
    }


    // MILESTONE

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

    // REVIEWED
    private def hasLabelNamed(name: String) = githubApi.labels(pr).map(_.exists(_.name == name))
    private def synchReviewedLabel(hasLGTM: Boolean) = for {
      hasReviewedLabel <- hasLabelNamed("reviewed")
    } yield { // TODO react to labeled/unlabeled event on webhhook
      if (hasLGTM) { if (!hasReviewedLabel) githubApi.addLabel(pr, List(Label("reviewed"))) }
      else if (hasReviewedLabel) githubApi.deleteLabel(pr, "reviewed")
    }

    private def checkLGTM(pull: PullRequest) = for {
      hasLGTM <- issueComments.map(_.exists(Commands.isLGTM))
    } yield synchReviewedLabel(hasLGTM)

    // CLA
    // last commit has successful most recent status under the CLA context
    private def successfulCLA(pull: PullRequest, last: String) = for {
      lastStatus <- fetchCommitStatus(last)
      claStatus  <- Future { lastStatus(CommitStatusConstants.CLA).get.head }
      if claStatus.success
    } yield claStatus

    // user signed CLA -- update commit status
    private def signedCLA(pull: PullRequest, last: String) = {
      val user = pull.user.login
      // TODO make these configurable:
      val signUrl  = "https://www.lightbend.com/contribute/cla/scala"
      val checkUrl = s"$signUrl/check/$user"
      val claKind  = "Scala"

      def checkCla = {
        val fetcher = lightbendApi.checkCla(user).map(_._1) // ignore status code for now (404 is returned if CLA is not signed...)
        fetcher.onFailure { case e => log.warning(s"Couldn't get CLA for ${user}: $e")}
        fetcher
      }

      for {
        pending   <- githubApi.postStatus(last, claStatus(None, user, claKind, checkUrl, signUrl))
        claRecord <- checkCla
        res       <- githubApi.postStatus(last, claStatus(Some(claRecord.signed), user, claKind, checkUrl, signUrl))
      } yield res
    }

    private def checkCLA(pull: PullRequest) = for {
      last  <- lastSha
      res   <- successfulCLA(pull, last) fallbackTo signedCLA(pull, last)
    } yield res

    // commands
    private def execCommands(pullRequest: PullRequest) = for {
      comments       <- issueComments
      commentResults <- Future.sequence(comments.map(handleComment))
    } yield commentResults

    private object seenComments {
      private val ddc    = new DynoDbClient
      private val _table = {
        val t = new ddc.DynoDbTable("scabot-seen-commands")
        if (!t.exists) t.create(List(("Id", KeyType.HASH)), List(("Id", "N")))
        t
      }

      def apply(id: Long): Future[Boolean] =
        _table.get(new PrimaryKey("Id", id)).map(_.nonEmpty)

      def +=(id: Long): Future[String] =
        _table.put((new Item).withPrimaryKey("Id", id)).map(_.getPutItemResult.toString)
    }

    private def handleComment(comment: IssueComment): Future[Any] = {
      import Commands._

      if (isLGTM(comment)) synchReviewedLabel(true)
      else if (isReviewRequest(comment)) {
        val REVIEW_BY(reviewer) = comment.body.toLowerCase
        log.debug(s"Review requested from $reviewer")
        requestReview(reviewer)
      }
      else if (!hasCommand(comment.body)) {
        log.debug(s"No command in $comment")
        Future.successful("No Command found")
      } else {
        (for {
          id <- Future {
            comment.id.get
          } // the get will fail the future if somehow id is empty
          seen <- seenComments(id)
          if !seen
          _ <- seenComments += id
          res <- {
            log.debug(s"Executing command for ${comment.body}")
            comment.body match {
              case REBUILD_SHA(sha)   => rebuildSha(sha)
              case REBUILD_ALL()      => rebuildAll()
              case SYNCH()            => synch()
              case NOTHINGTOSEEHERE() => nothingToSeeHere()
            }
          }
        } yield res).recover {
          case _: NoSuchElementException =>
            val msg = s"Already handled $comment"
            log.debug(msg)
            msg
          case _: MatchError             =>
            val msg = s"Unknown command in $comment"
            log.debug(msg)
            msg
        }
      }
    }

    object Commands {
      // purposefully only at start of line to avoid conditional LGTMs
      def isLGTM(comment: IssueComment): Boolean = comment.body.startsWith("LGTM")

      final val REVIEW_BY = """^review (?:by )?@(\w+)""".r.unanchored
      def isReviewRequest(comment: IssueComment): Boolean = comment.body.toLowerCase match {
        case REVIEW_BY(_) => true
        case _ => false
      }
      def requestReview(user: String) = githubApi.requestReview(pr, Reviewers(List(user)))

      def hasCommand(body: String) = body.startsWith("/")

      final val REBUILD_SHA = """^/rebuild (\w+)""".r.unanchored
      def rebuildSha(sha: String) = for {
        commits <- pullRequestCommits
        baseRef <- pullBranch
        build   <- launchBuild(sha, baseRef, jobParams(sha, sha == commits.last.sha))() // safe to assume commits of a pr is nonEmpty, so commits.last won't bomb
      } yield build

      final val REBUILD_ALL = """^/rebuild""".r.unanchored
      def rebuildAll() =
        for {
          pull     <- pull
          baseRef <- pullBranch
          buildRes <- buildCommitsIfNeeded(baseRef, forceRebuild = true, lastOnly = lastOnly(pull.title))
        } yield buildRes

      final val SYNCH = """^/sync""".r.unanchored
      def synch() =
        for {
          pull     <- pull
          synchRes <- handlePR("synchronize", pull, synchOnly = true)
        } yield synchRes

      final val NOTHINGTOSEEHERE = """^/nothingtoseehere""".r.unanchored
      def nothingToSeeHere() =
        for {
          commits <- pullRequestCommits
          results <- Future.sequence(commits map { commit =>
            for {
              combiCs <- fetchCommitStatus(commit.sha)
              res <- overrideFailures(combiCs)
            } yield res })
        } yield results

    }
  }
}
