package rest
package jenkins

import scabot.core

trait JenkinsApiTypes {
  case class Job(name: String,
                 description: String,
                 nextBuildNumber: String,
                 builds: List[Build],
                 queueItem: Option[QueueItem],
                 lastBuild: Build,
                 firstBuild: Build)

  case class Build(number: String, url: String) extends Ordered[Build] {
    def num: Int = number.toInt

    def compare(that: Build) = this.num - that.num
  }

  // value is optional: password-valued parameters hide their value
  case class Param(name: String, value: Option[String]) {
    override def toString = "%s -> %s" format(name, value)
  }

  case class Actions(parameters: List[Param]) {
    override def toString = "Parameters(%s)" format (parameters mkString ", ")
  }

  case class BuildStatus(number: String,
                         result: String,
                         building: Boolean,
                         duration: String,
                         actions: Actions,
                         url: String) {

    assert(!(building && queued), "Cannot both be building and queued.")

    def friendlyDuration = {
      val seconds = try {
        duration.toInt / 1000
      } catch {
        case x: Exception => 0
      }
      "Took " + (if (seconds <= 90) seconds + " s." else (seconds / 60) + " min.")
    }

    def queued = false

    def isSuccess = !building && result == "SUCCESS"

    override def toString = s"Build $number: ${if (building) "BUILDING" else result} $friendlyDuration ($url)."
  }

  case class Queue(items: List[QueueItem])

  case class QueueItem(actions: Actions, task: Task, id: String) {
    def jobName = task.name

    // the url is fake but needs to be unique
    def toStatus = new BuildStatus("0", "Queued build for " + task.name + " id: " + id, false, "-1", actions, task.url + "/queued/" + id) {
      override def queued = true
    }
  }

  case class Task(name: String, url: String)
}


import spray.json.{RootJsonFormat, DefaultJsonProtocol}

// TODO: can we make this more debuggable?
trait JenkinsJsonProtocol extends JenkinsApiTypes with DefaultJsonProtocol {
  type RJF[x] = RootJsonFormat[x]

  implicit lazy val _fmtJob         : RJF[Job        ] = jsonFormat7(Job)
  implicit lazy val _fmtBuild       : RJF[Build      ] = jsonFormat2(Build)
  implicit lazy val _fmtParam       : RJF[Param      ] = jsonFormat2(Param)
  implicit lazy val _fmtActions     : RJF[Actions    ] = jsonFormat1(Actions)
  implicit lazy val _fmtBuildStatus : RJF[BuildStatus] = jsonFormat6(BuildStatus)
  implicit lazy val _fmtQueue       : RJF[Queue      ] = jsonFormat1(Queue)
  implicit lazy val _fmtQueueItem   : RJF[QueueItem  ] = jsonFormat3(QueueItem)
  implicit lazy val _fmtTask        : RJF[Task       ] = jsonFormat2(Task)
}

trait JenkinsApiActions extends JenkinsJsonProtocol with core.HttpClient { self: core.Service =>

  class JenkinsConnection(val host: String, val jenkinsPath: String, authToken: String) {
    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    def credentials = new GenericHttpCredentials("token", authToken)

    implicit lazy val jenkinsConnection = setupConnection(host, credentials)

    def api(rest: String) = Uri(jenkinsPath / rest)

    def buildJob(name: String, params: Map[String, String] = Map.empty) =
      p[String](Post(if (params.isEmpty) api(name / "build")
                     else                api("job" / name / "buildWithParameters") withQuery (params)))

    def buildStatus(name: String, buildNumber: String) =
      p[BuildStatus](Get(api("job" / name / buildNumber / "api/json")))

//    /** A traversable that lazily pulls build status information from jenkins.
//      *
//      * Only statuses for the specified job (`job.name`) that have parameters that match all of `expectedArgs`
//      */
//    def buildStatusForJob(job: JenkinsJob, expectedArgs: Map[String,String]): Stream[BuildStatus] = try {
//      val info = Http(makeReq("job/%s/api/json" format (job.name)) >- parseJsonTo[Job])
//      val reportedBuilds = info.builds.sorted
//
//      // hack: retrieve queued jobs from queue/api/json
//      val queuedStati =
//        try {
//          Http(makeReq("queue/api/json") >- parseJsonTo[Queue]).items.filter(_.jobName == job.name).map(_.toStatus)
//        } catch {
//          case e@(_: dispatch.classic.StatusCode | _: net.liftweb.json.MappingException) =>
//            println(s"Error: could not get queued jobs for $job: "+ e)
//            Nil
//        }
//
//
//      // work around https://issues.jenkins-ci.org/browse/JENKINS-15583 -- jenkins not reporting all running builds
//      // so hack it by closing the range from the last reported build to the lastBuild in the Json response, which is correct
//      // only the builds part of the reply is wrong
//      val start =
//        try {
//          if (reportedBuilds.isEmpty) info.firstBuild.number.toInt
//          else reportedBuilds.last.number.toInt
//        } catch { case x: Exception => 1 }
//
//      val allBuilds = {
//        val additionalBuilds = try {
//          (start to info.lastBuild.number.toInt) map { number =>
//            Build(number.toString, jenkinsUrl + "/job/" + job.name + "/" + number + "/")
//          }
//        } catch {
//          case x: Exception =>
//            List[Build]()
//        }
//        (reportedBuilds ++ additionalBuilds)
//      }
//
//      // queued items must come first, they have been added more recently or they wouldn't have been queued
//      val all = queuedStati.toStream ++ allBuilds.reverse.toStream.flatMap(b => buildStatus(job, b.number))
//      all.filter { status =>
//        val paramsForExpectedArgs = status.actions.parameters.collect {
//          case Param(n, Some(v)) if expectedArgs.isDefinedAt(n) => (n, v)
//        }.toMap
//
//        paramsForExpectedArgs == expectedArgs
//      }
//    } catch {
//      case e@(_: dispatch.classic.StatusCode | _: net.liftweb.json.MappingException) =>
//        println(s"Error: could not get buildStatusForJob for $job: "+ e)
//        Stream.empty[BuildStatus]
//    }

  }

}

