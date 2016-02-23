name := "scabot"

organization in ThisBuild := "com.lightbend"
version      in ThisBuild := "0.1.0"
scalaVersion in ThisBuild := "2.11.7"

scalacOptions in ThisBuild ++=
  Seq("-feature", "-deprecation", "-Xfatal-warnings")

lazy val deps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor"     % "2.3.14",
    "com.typesafe.akka" %% "akka-kernel"    % "2.3.14",
    "io.spray"          %% "spray-client"   % "1.3.2",
    "io.spray"          %% "spray-json"     % "1.3.1"
  ))

lazy val amazonDeps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.9.13")

lazy val guiSettings: Seq[sbt.Def.Setting[_]] = Seq(
  routesGenerator := InjectedRoutesGenerator
)

// TODO: why do we need to define this explicitly
// this is the root project, aggregating all sub projects
lazy val root = Project(
    id   = "root",
    base = file("."),
    // configure your native packaging settings here
    settings = Seq(
        maintainer           := "Adriaan Moors <adriaan@lightbend.com>",
        packageDescription   := "Scala Bot",
        packageSummary       := "Automates stuff on Github"),
    // always run all commands on each sub project
    aggregate = Seq(core, amazon, github, cli, jenkins, server)
) dependsOn(gui) // this does the actual aggregation

lazy val core    = project settings (deps: _*)
lazy val github  = project dependsOn (core)
lazy val jenkins = project dependsOn (core)
lazy val typesafe = project dependsOn (core)
lazy val cli     = project dependsOn (github)
lazy val amazon  = project dependsOn (core) settings (amazonDeps: _*)
lazy val server  = project dependsOn (amazon, github, jenkins, typesafe)
lazy val gui     = project dependsOn (server) enablePlugins(PlayScala) settings (guiSettings: _*)
