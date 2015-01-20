package scabot
package jenkins

import spray.http.BasicHttpCredentials

import scala.concurrent.Future
import scala.util.Try

trait JenkinsApi extends JenkinsApiTypes with JenkinsJsonProtocol with JenkinsApiActions { self: core.Core with core.Configuration with core.HttpClient => }

trait JenkinsApiTypes { self: core.Core with core.Configuration =>
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

  implicit class BuildStatusOps(_bs: BuildStatus) {
    def isSuccess = !_bs.building && _bs.result == "SUCCESS"

    def paramsMatch(expectedArgs: Map[String, String]): Boolean =
      _bs.actions.flatMap(_.parameters).flatten.collect {
        case Param(n, Some(v)) if expectedArgs.isDefinedAt(n) => (n, v)
      }.toMap == expectedArgs
  }

  case class BuildStatus(number: Int,
                         result: String,
                         building: Boolean,
                         duration: Long,
                         actions: List[Action],
                         url: String) {
    assert(!(building && queued), "Cannot both be building and queued.")

    def friendlyDuration = Try {
      val seconds = duration.toInt / 1000
      "Took "+ (if (seconds <= 90) s"$seconds s." else s"${seconds / 60} min.")
    } getOrElse ""

    def queued = false

    override def toString = s"Build $number: ${if (building) "BUILDING" else result} $friendlyDuration ($url)."
  }

  case class Queue(items: List[QueueItem])

  case class QueueItem(actions: List[Action], task: Task, id: Int) {
    def jobName = task.name

    // the url is fake but needs to be unique
    def toStatus = new BuildStatus(0, s"Queued build for ${task.name } id: ${id}", false, -1, actions, task.url + "/queued/" + id) {
      override def queued = true
    }
  }

  case class Task(name: String, url: String)

  // for https://wiki.jenkins-ci.org/display/JENKINS/Notification+Plugin
  case class JobState(name: String, url: String, build: BuildState) extends ProjectMessage with PRMessage
  // phase = STARTED | COMPLETED | FINALIZED
  case class BuildState(number: Int, phase: String, parameters: Map[String, String], scm: ScmParams, result: Option[String], full_url: String, log: Option[String])
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
trait JenkinsJsonProtocol extends JenkinsApiTypes with DefaultJsonProtocol { self: core.Core with core.Configuration =>
  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtJob         : RJF[Job        ] = jsonFormat7(Job)
  implicit lazy val _fmtBuild       : RJF[Build      ] = jsonFormat2(Build)
  implicit lazy val _fmtParam       : RJF[Param      ] = jsonFormat2(Param)
  implicit lazy val _fmtActions     : RJF[Action     ] = jsonFormat1(Action)
  implicit lazy val _fmtBuildStatus : RJF[BuildStatus] = jsonFormat6(BuildStatus)
  implicit lazy val _fmtQueue       : RJF[Queue      ] = jsonFormat1(Queue)
  implicit lazy val _fmtQueueItem   : RJF[QueueItem  ] = jsonFormat3(QueueItem)
  implicit lazy val _fmtTask        : RJF[Task       ] = jsonFormat2(Task)
  implicit lazy val _fmtJobState    : RJF[JobState   ] = jsonFormat3(JobState)
  implicit lazy val _fmtBuildState  : RJF[BuildState ] = jsonFormat7(BuildState)
  implicit lazy val _fmtScmState    : RJF[ScmParams  ] = jsonFormat3(ScmParams)
}

trait JenkinsApiActions extends JenkinsJsonProtocol { self: core.Core with core.Configuration with core.HttpClient =>
  class JenkinsConnection(config: Config.Jenkins) {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection(config.host, BasicHttpCredentials(config.user, config.token))

    def api(rest: String) = Uri("/" + rest)

    def buildJob(name: String, params: Map[String, String] = Map.empty) =
      p[String](Post(
        if (params.isEmpty) api(name / "build")
        else api("job" / name / "buildWithParameters") withQuery (params)
      ))

    def buildStatus(name: String, buildNumber: Int) =
      p[BuildStatus](Get(api("job" / name / buildNumber / "api/json")))


    def buildStatus(url: String) =
      p[BuildStatus](Get(url / "api/json"))

    /** A traversable that lazily pulls build status information from jenkins.
      *
      * Only statuses for the specified job (`job.name`) that have parameters that match all of `expectedArgs`
      */
    def buildStatusesForJob(job: String): Future[Stream[BuildStatus]] = {
      def queuedStati(q: Queue) = q.items.toStream.filter(_.jobName == job).map(_.toStatus)
      def reportedStati(info: Job) = Future.sequence(info.builds.sorted.toStream.map(b => buildStatus(job, b.number)))

      // hack: retrieve queued jobs from queue/api/json
      // queued items must come first, they have been added more recently or they wouldn't have been queued
      for {
        queued   <- p[Queue](Get(api("queue/api/json"))).map(queuedStati)
        reported <-   p[Job](Get(api("job" / job / "api/json"))).flatMap(reportedStati)
      } yield queued ++ reported
    }

    def jobInfo(name: String) =
      p[Job](Get(api("job" / name / "api/json")))

    def nextBuildNumber(name: String): Future[Int] =
      jobInfo(name).map(_.lastBuild.number.toInt)

  }
}

