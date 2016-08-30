package scabot
package jenkins

import spray.http.BasicHttpCredentials

import scala.concurrent.Future
import scala.util.Try

trait JenkinsApi extends JenkinsApiTypes with JenkinsJsonProtocol with JenkinsApiActions

trait JenkinsApiTypes extends core.Configuration {
  object Job {
    // avoid humongeous replies that would require more spray sophistication (chunking etc)
    val XPath = "?tree=name,description,nextBuildNumber,builds[number,url],queueItem[*],lastBuild[number,url],firstBuild[number,url]"
  }
  case class Job(name: String,
                 description: String,
                 nextBuildNumber: Int,
                 builds: List[Build],
                 queueItem: Option[QueueItem],
                 lastBuild: Build,
                 firstBuild: Build)

  case class Build(number: Int, url: String) extends Ordered[Build] {
    def num: Int = number.toInt

    def compare(that: Build) = that.num - this.num
  }

  // value is optional: password-valued parameters hide their value
  case class Param(name: String, value: Option[String]) {
    override def toString = "%s -> %s" format(name, value)
  }

  case class Action(parameters: Option[List[Param]]) {
    override def toString = "Parameters(%s)" format (parameters mkString ", ")
  }

  object BuildStatus {
    // avoid humongeous replies that would require more spray sophistication (chunking etc)
    val XPath = "?tree=number,result,building,duration,actions[parameters[*]],url"
  }
  case class BuildStatus(number: Option[Int],
                         result: Option[String],
                         building: Boolean,
                         duration: Long,
                         actions: Option[List[Action]],
                         url: Option[String]) { // TODO url may not need to be optional -- not sure
    def friendlyDuration = Try {
      val seconds = duration.toInt / 1000
      if (seconds == 0) ""
      else "Took "+ (if (seconds <= 90) s"$seconds s." else s"${seconds / 60} min.")
    } getOrElse ""

    def queued  = result.isEmpty
    def success = result == Some("SUCCESS")
    def failed  = !(queued || building || success)
    // TODO deal with ABORTED and intermittent failure (should retry these with different strategy from failures?)

    def status = if (building) "Building" else result.getOrElse("Pending")
    def parameters = (for {
      actions           <- actions.toList
      action            <- actions
      parameters        <- action.parameters.toList
      Param(n, Some(v)) <- parameters
    } yield (n, v)).toMap

    def paramsMatch(expectedArgs: Map[String, String]): Boolean =
      parameters.filterKeys(expectedArgs.isDefinedAt) == expectedArgs

    override def toString = s"[${number.getOrElse("?")}] $status. $friendlyDuration"
  }

  class QueuedBuildStatus(actions: Option[List[Action]], url: Option[String]) extends BuildStatus(None, None, false, 0, actions, url) {
    def this(params: Map[String, String], url: Option[String]) {
      this(Some(List(Action(Some(params.toList.map { case (k, v) => Param(k, Some(v))})))), url)
    }
  }

  case class Queue(items: List[QueueItem])

  case class QueueItem(actions: Option[List[Action]], task: Task, id: Int) {
    def jobName = task.name

    // the url is fake but needs to be unique
    def toStatus = new QueuedBuildStatus(actions, Some(task.url + "#queued-" + id))
  }

  case class Task(name: String, url: String)

  // for https://wiki.jenkins-ci.org/display/JENKINS/Notification+Plugin
  case class JobState(name: String, url: String, build: BuildState) extends ProjectMessage with PRMessage
  // phase = STARTED | COMPLETED | FINALIZED
  case class BuildState(number: Int, phase: String, parameters: Map[String, String], scm: ScmParams, result: Option[String], full_url: String, log: Option[String]) // TODO result always seems to be None??
  case class ScmParams(url: Option[String], branch: Option[String], commit: Option[String]) // TODO can we lift the `Option`s to `BuildState`'s `scm` arg?

}

/*
{
    "build": {
        "artifacts": {},
        "full_url": "https://scala-ci.typesafe.com/job/scala-2.11.x-validate-main/28/",
        "number": 28,
        "parameters": {
            "prDryRun": "",
            "repo_name": "scala",
            "repo_ref": "61a16f54b64d1bf020f36c2a6b4baff58c6ec70d",
            "repo_user": "adriaanm"
        },
        "phase": "FINALIZED",
        "scm": {},
        "status": "FAILURE",
        "url": "job/scala-2.11.x-validate-main/28/"
    },
    "name": "scala-2.11.x-validate-main",
    "url": "job/scala-2.11.x-validate-main/"
}
 */

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

// TODO: can we make this more debuggable?
trait JenkinsJsonProtocol extends JenkinsApiTypes with DefaultJsonProtocol {
  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtJob         : RJF[Job        ] = jsonFormat7(Job.apply)
  implicit lazy val _fmtBuild       : RJF[Build      ] = jsonFormat2(Build)
  implicit lazy val _fmtParam       : RJF[Param      ] = jsonFormat2(Param)
  implicit lazy val _fmtActions     : RJF[Action     ] = jsonFormat1(Action)
  implicit lazy val _fmtBuildStatus : RJF[BuildStatus] = jsonFormat6(BuildStatus.apply)
  implicit lazy val _fmtQueue       : RJF[Queue      ] = jsonFormat1(Queue)
  implicit lazy val _fmtQueueItem   : RJF[QueueItem  ] = jsonFormat3(QueueItem)
  implicit lazy val _fmtTask        : RJF[Task       ] = jsonFormat2(Task)
  implicit lazy val _fmtJobState    : RJF[JobState   ] = jsonFormat3(JobState)
  implicit lazy val _fmtBuildState  : RJF[BuildState ] = jsonFormat7(BuildState)
  implicit lazy val _fmtScmState    : RJF[ScmParams  ] = jsonFormat3(ScmParams)
}

trait JenkinsApiActions extends JenkinsJsonProtocol with core.HttpClient {
  class JenkinsConnection(config: Config.Jenkins) {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection(config.host, Some(BasicHttpCredentials(config.user, config.token)))

    def api(rest: String) = Uri("/" + rest)

    def buildJob(name: String, params: Map[String, String] = Map.empty) =
      p[String](Post(
        if (params.isEmpty) api(name / "build")
        else api("job" / name / "buildWithParameters") withQuery (params)
      ))

    def buildStatus(name: String, buildNumber: Int) =
      p[BuildStatus](Get(api("job" / name / buildNumber / "api/json"+ BuildStatus.XPath)))


    def buildStatus(url: String) =
      p[BuildStatus](Get(url / "api/json"+ BuildStatus.XPath))

    /** A traversable that lazily pulls build status information from jenkins.
      *
      * Only statuses for the specified job (`job.name`) that have parameters that match all of `expectedArgs`
      */
    def buildStatusesForJob(job: String): Future[Stream[Future[BuildStatus]]] = {
      def queuedStati(q: Queue) = q.items.toStream.filter(_.jobName == job).map(qs => Future.successful(qs.toStatus))
      def reportedStati(info: Job) = info.builds.sorted.toStream.map(b => buildStatus(job, b.number))

      // hack: retrieve queued jobs from queue/api/json
      // queued items must come first, they have been added more recently or they wouldn't have been queued
      for {
        queued   <- p[Queue](Get(api("queue/api/json"))).map(queuedStati)
        reported <-   p[Job](Get(api("job" / job / "api/json"+ Job.XPath))).map(reportedStati)
      } yield queued ++ reported
    }

    def jobInfo(name: String) =
      p[Job](Get(api("job" / name / "api/json")))

    def nextBuildNumber(name: String): Future[Int] =
      jobInfo(name).map(_.lastBuild.number.toInt)

  }
}

