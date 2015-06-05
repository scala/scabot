import com.typesafe.sbt.SbtNativePackager._

name := "scabot"

organization in ThisBuild := "com.typesafe"
version      in ThisBuild := "0.1.0"
scalaVersion in ThisBuild := "2.11.6"

scalacOptions in ThisBuild ++=
  Seq("-feature", "-deprecation", "-Xfatal-warnings")

lazy val deps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor"     % "2.3.9",
    "com.typesafe.akka" %% "akka-kernel"    % "2.3.9",
    "io.spray"          %% "spray-can"      % "1.3.2",
    "io.spray"          %% "spray-client"   % "1.3.2",
    "io.spray"          %% "spray-routing"  % "1.3.2",
    "io.spray"          %% "spray-http"     % "1.3.2",
    "io.spray"          %% "spray-httpx"    % "1.3.2",
    "io.spray"          %% "spray-json"     % "1.3.1"
  ))

lazy val amazonDeps: Seq[sbt.Def.Setting[_]] =  Seq(
  libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.9.13")

// TODO: why do we need to define this explicitly
// this is the root project, aggregating all sub projects
lazy val root = Project(
    id   = "root",
    base = file("."),
    // configure your native packaging settings here
    settings = packageArchetype.akka_application ++ Seq(
        maintainer           := "Adriaan Moors <adriaan@typesafe.com>",
        packageDescription   := "Scala Bot",
        packageSummary       := "Automates stuff on Github",
        // entrypoint
        mainClass in Compile := Some("scabot.server.Scabot")),
    // always run all commands on each sub project
    aggregate = Seq(core, amazon, github, jenkins, server)
) dependsOn(core, amazon, github, jenkins, server) // this does the actual aggregation

// enablePlugins(AkkaAppPackaging)

lazy val core    = project settings (deps: _*)
lazy val github  = project dependsOn (core)
lazy val jenkins = project dependsOn (core)
lazy val amazon  = project dependsOn (core) settings (amazonDeps : _*)
lazy val server  = project dependsOn (amazon, github, jenkins)
