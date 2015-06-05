package scabot
package jenkins

import akka.event.Logging

trait JenkinsService extends core.Core with JenkinsApi { self: core.HttpClient with core.Configuration =>

  def jenkinsEvent(jobState: JobState): String = jobState match {
    case JobState(name, _, BuildState(number, phase, parameters, scm, result, full_url, log)) =>
      system.log.info(s"Job $name [$number]: $phase ($result) at $full_url.\n  Scm: scm\n  Params: $parameters\n $log")

      {
        for {
          user <- parameters.get(PARAM_REPO_USER)
          repo <- parameters.get(PARAM_REPO_NAME)
        } yield tellProjectActor(user, repo)(jobState)
      } getOrElse {
        system.log.warning(s"Couldn't identify project for job based on $PARAM_REPO_USER/$PARAM_REPO_NAME in $parameters. Was it started by us?")
      }

      "Fascinating, dear Jenkins!"
  }
}

