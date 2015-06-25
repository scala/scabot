package scabot.core

import com.typesafe.config.{Config => TConfig, _}

trait Configuration { self: Core =>

  def configFile: java.io.File

  // TODO generate config using chef
  lazy val configs: Map[String, Config] = parseConfig(configFile)

  object Config {
    // only PRs targeting a branch in `branches` will be monitored
    case class Github(user: String, repo: String, branches: Set[String], lastCommitOnly: Boolean, host: String, token: String)
    // the launched job will be BuildHelp.mainValidationJob(pull),
    // for example, if `jobSuffix` == "validate-main", we'll launch "scala-2.11.x-validate-main" for repo "scala", PR target "2.11.x"
    case class Jenkins(jobSuffix: String, host: String, user: String, token: String)
  }
  import Config._
  case class Config(github: Github, jenkins: Jenkins)

  // TODO - useful error messages on failure.
  def parseConfig(file: java.io.File): Map[String, Config] = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory parseFile file

    def configString(c: ConfigValue): Option[String] =
      if (c.valueType == ConfigValueType.STRING) Some(c.unwrapped.toString)
      else None

    def configBoolean(c: ConfigValue): Option[Boolean] =
      if (c.valueType == ConfigValueType.BOOLEAN) Some(c.unwrapped.asInstanceOf[java.lang.Boolean])
      else None

    def configObj(c: ConfigValue): Option[ConfigObject] =
      if (c.valueType == ConfigValueType.OBJECT) Some(c.asInstanceOf[ConfigObject])
      else None

    def configList(c: ConfigValue): Option[ConfigList] =
      if (c.valueType == ConfigValueType.LIST)
        Some(c.asInstanceOf[ConfigList])
      else None

    def configStringList(c: ConfigValue): Option[Seq[String]] =
      configList(c) map { x =>
        x.iterator.asScala.flatMap(configString).toSeq
      }

    def jenkins(c: ConfigObject): Option[Jenkins] =
      for {
        jobSuffix   <- configString(c.get("jobSuffix"))
        host  <- configString(c.get("host"))
        user  <- configString(c.get("user"))
        token <- configString(c.get("token"))
      } yield Jenkins(jobSuffix, host, user, token)

    def defaultGitHubToken: Option[String] =
      Option(sys.props("scabot.github.token"))

    def github(c: ConfigObject): Option[Github] =
      for {
        user  <- configString(c.get("user"))
        repo  <- configString(c.get("repo"))
        branches  <- configStringList(c.get("branches"))
        lastCommitOnly <- configBoolean(c.get("lastCommitOnly")) orElse Some(false) // default for scala/scala
        host  <- configString(c.get("host"))
        token <- configString(c.get("token")).filter(_.nonEmpty) orElse defaultGitHubToken
      } yield Github(user, repo, branches.toSet, lastCommitOnly, host, token)

    def c2c(c: ConfigObject): Option[Config] =
      for {
        // Jenkins Config
        jenkinsConf <- configObj(c.get("jenkins"))
        jenkinsObj <- jenkins(jenkinsConf)
        // Github config
        githubConf <- configObj(c.get("github"))
        githubObj <- github(githubConf)
      } yield Config(githubObj, jenkinsObj)

    val configs =
      for {
        kv <- config.root.entrySet.iterator.asScala
        name = kv.getKey.toString
        obj <- configObj(kv.getValue)
        c <- c2c(obj)
      } yield name -> c

    configs.toMap
  }


}
