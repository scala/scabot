package scabot.core

import com.typesafe.config.{Config => TConfig, _}

trait Configuration { self: Core =>
  // TODO make file location configurable
  // TODO generate config using chef
  lazy val configs: Map[String, Config] = parseConfig(new java.io.File("scabot.conf"))

  object Config {
    case class Github(user: String, repo: String, host: String, token: String)
    case class Jenkins(job: String, host: String, user: String, token: String)
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

    def configObj(c: ConfigValue): Option[ConfigObject] =
      if (c.valueType == ConfigValueType.OBJECT) Some(c.asInstanceOf[ConfigObject])
      else None

    def configList(c: ConfigValue): Option[ConfigList] =
      if (c.valueType == ConfigValueType.LIST)
        Some(c.asInstanceOf[ConfigList])
      else None

    def configStringList(c: ConfigValue): Option[Seq[String]] =
      configList(c) map { x =>
        x.iterator.asScala flatMap configString toSeq
      }

    def jenkins(c: ConfigObject): Option[Jenkins] =
      for {
        job   <- configString(c.get("job"))
        host  <- configString(c.get("host"))
        user  <- configString(c.get("user"))
        token <- configString(c.get("token"))
      } yield Jenkins(job, host, user, token)

    def github(c: ConfigObject): Option[Github] =
      for {
        user  <- configString(c.get("user"))
        repo  <- configString(c.get("repo"))
        host  <- configString(c.get("host"))
        token <- configString(c.get("token"))
      } yield Github(user, repo, host, token)

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
